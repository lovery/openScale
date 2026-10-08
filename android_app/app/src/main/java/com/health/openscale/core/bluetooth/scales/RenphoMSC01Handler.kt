/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.Wla37BodyComposition
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/**
 * RENPHO R-MSC01 ("Morpho Scan", ICOMON hardware) GATT handler.
 *
 * Service: 0xFFB0
 *  0xFFB1 – acknowledgements (App → Scale, ?bytes)
 *  0xFFB2 – live weight NOTIFY (Scale → App, 12 bytes)
 *  0xFFB3 – handshake, round acks, result sets (Scale → App, )
 *  0xFFB5 – profile and "go" frames (App → Scale, )
 *
 *  Note: 0xFFB4 is never used by the Renpho app
 *
 * Every frame ends in a checksum: (sum of bytes 4 .. last-1) and 0x1F.
 *
 * Session, as seen in BLE captures of the official app:
 *  1. phone enables indications on 0xFFB3, then notifications on 0xFFB2
 *  2. scale indicates a handshake (`00 00 1e 00 aa ...`, then `00 00 1e 01 00 ...`)
 *  3. phone writes the profile (round 0): height, sex, age and phone time
 *  4. scale streams live weight; the phone writes ack 0 after the 2nd frame and "go" (round 0)
 *     after the 4th
 *  5. scale indicates `xx 00 03 00 a0 00`: phone writes the profile again (round 2) and, from the
 *     7th live frame on, "go" (round 2)
 *  6. after ~50 stable frames the scale indicates a 3-frame result set (`xx 00 26 00..02`) with
 *     the weight, the scale's Unix time and ten segment impedances; the phone acknowledges it.
 *     A set measured while no phone was connected (kind 0xA5) comes before the live one (0xA7).
 *
 * Without the profile write the scale repeats the handshake forever.
 *
 * Body composition: the scale sends no fat / water / muscle values, only the ten segment
 * impedances. [Wla37BodyComposition] computes them the way the Renpho Morpho Scan app does.
 */
class RenphoMSC01Handler : ScaleDeviceHandler() {

    companion object {
        /** Metabolic age; the same identity as the other drivers, so the column stays continuous. */
        val METABOLIC_AGE = MeasurementType.deviceInt(
            "metabolic_age", R.string.measurement_type_metabolic_age,
            icon = MeasurementTypeIcon.IC_M_TIMER, color = 0xFF795548.toInt()
        )

        /** Every capture had exactly 50 stable frames before the result set. */
        private const val STABLE_FRAMES_BEFORE_FALLBACK = 80

        /** Time for the last acknowledgement write to leave before we drop the link. */
        private const val DISCONNECT_DELAY_MS = 1500L

        private const val MIN_GRAMS = 2_000

        const val KIND_STORED = 0xA5
        const val KIND_LIVE = 0xA7

        /** One decoded live weight notification (0xFFB2). */
        data class LiveWeight(val stable: Boolean, val grams: Int)

        /** First frame of a result set: kind, the scale's Unix time and the weight. */
        data class ResultHead(val kind: Int, val unixSec: Long, val grams: Int) {
            val isStored: Boolean get() = kind == KIND_STORED
        }

        /**
         * The ten impedances of an eight-electrode measurement in ohm, at 20 and 100 kHz, as the
         * scale sends them. The app's report shows the trunk values multiplied by 0.826.
         */
        data class SegmentImpedances(
            val rightArm20: Double, val leftArm20: Double, val trunk20: Double, val rightLeg20: Double, val leftLeg20: Double,
            val rightArm100: Double, val leftArm100: Double, val trunk100: Double, val rightLeg100: Double, val leftLeg100: Double,
        ) {
            /** The order [Wla37BodyComposition] takes: trunk, left arm, right arm, left leg, right leg. */
            fun toWla37Order() = doubleArrayOf(
                trunk20, leftArm20, rightArm20, leftLeg20, rightLeg20,
                trunk100, leftArm100, rightArm100, leftLeg100, rightLeg100,
            )
        }

        /** What an indication on 0xFFB3 is. All of them are 20 bytes with a valid checksum. */
        enum class Indication { HANDSHAKE_1, HANDSHAKE_2, ROUND_0_CONFIRMED, ROUND_2_CONFIRMED, RESULT, UNKNOWN }

        /** (sum of bytes 4 until [end]) and 0x1F; [end] is the index of the checksum byte. */
        fun checksum(f: ByteArray, end: Int): Int {
            var sum = 0
            for (i in 4 until end) sum += f[i].toInt() and 0xFF
            return sum and 0x1F
        }

        private fun hasValidChecksum(f: ByteArray) =
            f.size > 4 && checksum(f, f.lastIndex) == (f.last().toInt() and 0xFF)

        // --- phone -> scale -----------------------------------------------------------------

        /**
         * Profile frame, 20 bytes. Only byte 0 ([round], 0 or 2) and the checksum differ between rounds.
         *   0 round  2 0x17  4 0xBE  5-8 Unix time (BE)  10-11 3C 01  12 height cm
         *   13-14 17 70  15 bit 7 = male, bits 0-6 = age  16-18 13 88 13  19 checksum
         * The constant bytes are copied from the captures; their meaning is unknown.
         */
        fun buildProfile(round: Int, unixSec: Long, heightCm: Int, ageYears: Int, male: Boolean): ByteArray {
            val f = ByteArray(20)
            f[0] = round.toByte()
            f[2] = 0x17
            f[4] = 0xBE.toByte()
            ConverterUtils.toInt32Be(f, 5, unixSec)
            f[10] = 0x3C
            f[11] = 0x01
            f[12] = heightCm.coerceIn(0, 255).toByte()
            f[13] = 0x17
            f[14] = 0x70
            f[15] = ((if (male) 0x80 else 0x00) or ageYears.coerceIn(0, 127)).toByte()
            f[16] = 0x13
            f[17] = 0x88.toByte()
            f[18] = 0x13
            f[19] = checksum(f, 19).toByte()
            return f
        }

        /** "Go" frame, 13 bytes. Meaning not decoded; identical in every capture. */
        fun buildGo(round: Int): ByteArray {
            val f = ByteArray(13)
            f[0] = round.toByte()
            f[2] = 0x17
            f[3] = 0x01
            f[4] = 0x88.toByte()
            f[5] = 0x0F
            f[12] = checksum(f, 12).toByte()
            return f
        }

        /** Acknowledgement, 8 bytes. [k] is 0, 1, 2, …; the leading sequence byte is 1, 3, 4, …. */
        fun buildAck(k: Int): ByteArray {
            val f = ByteArray(8)
            f[0] = (if (k == 0) 1 else k + 2).toByte()
            f[2] = 0x03
            f[4] = 0xB0.toByte()
            f[5] = k.toByte()
            f[7] = checksum(f, 7).toByte()
            return f
        }

        // --- scale -> phone -----------------------------------------------------------------

        /**
         * `seq 00 07 00 a2 status 25 8x wHi wLo 00 chk`, status 01 = settling, 03 = stable.
         * The weight is in grams and 17 bits wide; bit 16 is the low bit of byte 7.
         */
        fun parseLiveWeight(n: ByteArray): LiveWeight? {
            if (n.size != 12 || n[2] != 0x07.toByte() || !hasValidChecksum(n)) return null
            return LiveWeight(stable = n[5] == 0x03.toByte(), grams = weightGrams(n, 7))
        }

        fun classifyIndication(d: ByteArray): Indication {
            if (d.size != 20 || !hasValidChecksum(d)) return Indication.UNKNOWN
            val type = d[2].toInt() and 0xFF
            val sub = d[3].toInt() and 0xFF
            val roundAck = type == 0x03 && (d[4].toInt() and 0xFF) == 0xA0
            return when {
                type == 0x1E && sub == 0x00 -> Indication.HANDSHAKE_1
                type == 0x1E && sub == 0x01 -> Indication.HANDSHAKE_2
                roundAck && d[5] == 0x00.toByte() -> Indication.ROUND_0_CONFIRMED
                roundAck && d[5] == 0x02.toByte() -> Indication.ROUND_2_CONFIRMED
                type == 0x26 && sub in 0..2 -> Indication.RESULT
                else -> Indication.UNKNOWN
            }
        }

        /** Index (0, 1, 2) of a result frame inside its set. Only valid for [Indication.RESULT]. */
        fun resultIndex(d: ByteArray): Int = d[3].toInt() and 0xFF

        /** Result frame 0, payload: kind(1) unixTime(4) 25 8x weight(2) 00 0a trunk20(2) leftArm20(2). */
        fun parseResultHead(d: ByteArray): ResultHead? {
            if (classifyIndication(d) != Indication.RESULT || resultIndex(d) != 0) return null
            return ResultHead(
                kind = d[4].toInt() and 0xFF,
                unixSec = ConverterUtils.fromUnsignedInt32Be(d, 5),
                grams = weightGrams(d, 10),
            )
        }

        /**
         * The ten impedances of a result set, 16-bit words in 0.1 ohm:
         *   frame 0: 15-16 trunk 20k, 17-18 left arm 20k
         *   frame 1: 4 right arm 20k, 6 left leg 20k, 8 right leg 20k, 10 trunk 100k, 12 left arm 100k,
         *            14 right arm 100k, 16 left leg 100k, 18 high byte of right leg 100k
         *   frame 2: 4 low byte of right leg 100k
         */
        fun parseSegments(head: ByteArray, c1: ByteArray, c2: ByteArray): SegmentImpedances? {
            if (parseResultHead(head) == null) return null
            if (classifyIndication(c1) != Indication.RESULT || resultIndex(c1) != 1) return null
            if (classifyIndication(c2) != Indication.RESULT || resultIndex(c2) != 2) return null
            fun ohm(f: ByteArray, i: Int) = ConverterUtils.fromUnsignedInt16Be(f, i) / 10.0
            val rightLeg100 = (((c1[18].toInt() and 0xFF) shl 8) or (c2[4].toInt() and 0xFF)) / 10.0
            return SegmentImpedances(
                rightArm20 = ohm(c1, 4), leftArm20 = ohm(head, 17), trunk20 = ohm(head, 15),
                rightLeg20 = ohm(c1, 8), leftLeg20 = ohm(c1, 6),
                rightArm100 = ohm(c1, 14), leftArm100 = ohm(c1, 12), trunk100 = ohm(c1, 10),
                rightLeg100 = rightLeg100, leftLeg100 = ohm(c1, 16),
            )
        }

        /** Bit 16 is the low bit of the `25 8x` byte at [flagIndex], bits 0-15 follow it. */
        private fun weightGrams(b: ByteArray, flagIndex: Int): Int =
            ((b[flagIndex].toInt() and 0x01) shl 16) or ConverterUtils.fromUnsignedInt16Be(b, flagIndex + 1)
    }

    private val SVC get() = uuid16(0xFFB0)
    private val CHR_COMMAND get() = uuid16(0xFFB5)
    private val CHR_WEIGHT get() = uuid16(0xFFB2)
    private val CHR_RESULT get() = uuid16(0xFFB3)
    private val CHR_ACK get() = uuid16(0xFFB1)

    /** Per-connection state; replaced as a whole on every connect. */
    private class Session {
        var profileTimeSec = 0L          // phone clock; the same value is reused in round 2
        var profile0Sent = false
        var profile2Sent = false
        var go0Sent = false
        var go2Sent = false
        var ackCount = 0
        var liveFrames = 0               // the scale's own seq byte does not restart per session
        var stableFrames = 0
        var lastStableGrams = 0
        val resultFrames = arrayOfNulls<ByteArray>(3)
        val publishedTimes = HashSet<Long>()   // the scale repeats unacknowledged sets
        var finished = false
    }

    private var session = Session()

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Registered before MGBHandler in ScaleFactory, which claims every device with service 0xFFB0.
        if (!device.name.trim().equals("R-MSC01", ignoreCase = true)) return null

        return DeviceSupport(
            displayName = "RENPHO R-MSC01 (Morpho Scan)",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.BODY_COMPOSITION,
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.BODY_COMPOSITION,
            ),
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        session = Session()
        logI("connected, user id=${user.id} height=${user.bodyHeight} cm age=${user.age} male=${user.gender.isMale()}")

        // Same order as the Renpho app: indications first, then notifications.
        setNotifyOn(SVC, CHR_RESULT)
        setNotifyOn(SVC, CHR_WEIGHT)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (session.finished) return
        when (characteristic) {
            CHR_WEIGHT -> handleLiveWeight(data, user)
            CHR_RESULT -> handleIndication(data, user)
            else -> logD("unhandled chr=$characteristic ${data.toHexPreview(20)}")
        }
    }

    // --- Handlers ------------------------------------------------------------------------

    /** 0xFFB2 live weight; also paces the ack and "go" writes like the Renpho app does. */
    private fun handleLiveWeight(data: ByteArray, user: ScaleUser) {
        val weight = parseLiveWeight(data) ?: return
        val s = session

        s.liveFrames++
        if (s.liveFrames >= 2 && s.ackCount == 0) sendAck()
        if (s.liveFrames >= 4 && !s.go0Sent) { s.go0Sent = true; sendGo(0) }
        if (s.liveFrames >= 7 && s.profile2Sent && !s.go2Sent) { s.go2Sent = true; sendGo(2) }

        if (!weight.stable) return
        s.stableFrames++
        s.lastStableGrams = weight.grams
        // Normally the result set arrives after ~50 stable frames. If it never does, still save
        // the last stable weight so the measurement is not lost.
        if (s.stableFrames >= STABLE_FRAMES_BEFORE_FALLBACK && s.resultFrames[0] == null) {
            logW("no result set after ${s.stableFrames} stable frames, saving last stable weight")
            saveMeasurement(user, s.lastStableGrams, Date(), segments = null)
            finishSession()
        }
    }

    /** 0xFFB3 handshake, round confirmations and result frames. */
    private fun handleIndication(data: ByteArray, user: ScaleUser) {
        val s = session
        when (classifyIndication(data)) {
            Indication.HANDSHAKE_2 -> if (!s.profile0Sent) { s.profile0Sent = true; sendProfile(0, user) }
            Indication.ROUND_0_CONFIRMED -> if (!s.profile2Sent) { s.profile2Sent = true; sendProfile(2, user) }
            Indication.RESULT -> handleResultFrame(data, user)
            Indication.HANDSHAKE_1, Indication.ROUND_2_CONFIRMED -> Unit
            Indication.UNKNOWN -> logD("unknown indication ${data.toHexPreview(20)}")
        }
    }

    private fun handleResultFrame(data: ByteArray, user: ScaleUser) {
        val s = session
        val index = resultIndex(data)
        s.resultFrames[index] = data
        if (index != 2) return

        val (f0, f1, f2) = s.resultFrames
        s.resultFrames.fill(null)
        val head = f0?.let { parseResultHead(it) } ?: return
        val segments = if (f1 != null && f2 != null) parseSegments(f0, f1, f2) else null

        if (s.publishedTimes.add(head.unixSec)) {
            logI("result kind=0x%02X grams=%d time=%s %s".format(head.kind, head.grams, Date(head.unixSec * 1000L), segments))
            saveMeasurement(user, head.grams, Date(head.unixSec * 1000L), segments)
        }
        sendAck()
        // A stored set is followed by the live one; any other set ends the session.
        if (!head.isStored) finishSession()
    }

    // --- Saving --------------------------------------------------------------------------

    private fun saveMeasurement(user: ScaleUser, grams: Int, time: Date, segments: SegmentImpedances?) {
        if (grams < MIN_GRAMS) {
            logW("ignoring implausible weight $grams g")
            return
        }
        val weightKg = grams / 1000f
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = time
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            if (segments != null) {
                this[MeasurementType.IMPEDANCE] = Ohm(segments.leftArm20.toFloat())
                addBodyComposition(this, user, weightKg, segments)
            }
        }
        logI("save weight=$weightKg kg time=$time")
        publish(m)
    }

    /** Adds what the Morpho Scan app shows, computed from the ten impedances. */
    private fun addBodyComposition(m: ScaleMeasurement, user: ScaleUser, weightKg: Float, segments: SegmentImpedances) {
        val r = Wla37BodyComposition.compute(user.bodyHeight.toInt(), weightKg.toDouble(), segments.toWla37Order())
        if (r == null) {
            logW("no body composition: height ${user.bodyHeight} cm, weight $weightKg kg or impedances out of range")
            return
        }
        logI("body composition: $r")
        m[MeasurementType.BODY_FAT] = Percent(r.fat)
        m[MeasurementType.WATER] = Percent(r.water)
        m[MeasurementType.MUSCLE] = Percent(r.skeletalMuscle)
        m[MeasurementType.PROTEIN] = Percent(r.protein)
        m[MeasurementType.BONE] = Kg(r.boneKg)
        m[MeasurementType.LBM] = Kg(r.lbmKg)
        m[MeasurementType.BMR] = Kcal(r.bmrKcal.toFloat())
        m[MeasurementType.VISCERAL_FAT] = r.visceralFat.toFloat()
        Wla37BodyComposition.bodyAge(
            age = user.age,
            fatPercent = r.fat.toDouble(),
            sex = user.gender.isMale(),
        ).takeIf { it in 10..99 }?.let { m[METABOLIC_AGE] = it }
    }

    private fun finishSession() {
        session.finished = true
        scope.launch {
            delay(DISCONNECT_DELAY_MS)   // let the acknowledgement write go out first
            requestDisconnect()
        }
    }

    // --- I/O helpers ---------------------------------------------------------------------

    /** User data goes out here: height, sex, age and the phone's clock. */
    private fun sendProfile(round: Int, user: ScaleUser) {
        val s = session
        if (s.profileTimeSec == 0L) s.profileTimeSec = System.currentTimeMillis() / 1000L
        val height = user.bodyHeight.toInt()
        if (height !in 50..250) logW("height $height cm looks wrong; set it in the user profile")

        val frame = buildProfile(round, s.profileTimeSec, height, user.age, user.gender.isMale())
        logD("-> profile round $round ${frame.toHexPreview(20)}")
        writeTo(SVC, CHR_COMMAND, frame, withResponse = true)
    }

    private fun sendGo(round: Int) {
        val frame = buildGo(round)
        logD("-> go round $round ${frame.toHexPreview(13)}")
        writeTo(SVC, CHR_COMMAND, frame, withResponse = true)
    }

    private fun sendAck() {
        val frame = buildAck(session.ackCount++)
        logD("-> ack ${frame.toHexPreview(8)}")
        writeTo(SVC, CHR_ACK, frame, withResponse = true)
    }
}
