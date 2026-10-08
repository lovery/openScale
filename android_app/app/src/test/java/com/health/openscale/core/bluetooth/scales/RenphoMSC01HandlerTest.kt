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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.libs.Wla37BodyComposition
import com.health.openscale.core.bluetooth.scales.RenphoMSC01Handler.Companion.Indication
import org.junit.Test

/**
 * Unit tests for [RenphoMSC01Handler]: device matching, the frame codec and the body composition
 * computed from the decoded frames.
 *
 * Every frame is taken verbatim from a BTSnoop capture of the Renpho Morpho Scan app
 * (2026-10-16, session 2: female, 31, 165 cm, 58.02 kg). The impedances match the app's report
 * for that session to 0.1 ohm.
 */
class RenphoMSC01HandlerTest {

    private val serviceFfb0 = uuid16(0xFFB0)

    // Result set of session 2 (indications on 0xFFB3).
    private val resultHead = hex("01 00 26 00 a7 6a d2 16 ca 25 88 e2 a4 00 0a 00 ff 11 e9 19")
    private val resultFrame1 = hex("01 00 26 01 11 2f 0b 1f 0b b7 00 e1 10 44 0f b6 0a 1f 0a 19")
    private val resultFrame2 = hex("01 00 26 02 a2 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02")

    @Test
    fun `matches R-MSC01`() {
        assertThat(RenphoMSC01Handler().supportFor(device("R-MSC01", serviceFfb0))).isNotNull()
        assertThat(RenphoMSC01Handler().supportFor(device("R-MSC02", serviceFfb0))).isNull()
    }

    @Test
    fun `R-MSC01 reaches its own handler before MGBHandler`() {
        // MGBHandler claims every device with service 0xFFB0, so the registry order matters.
        val handler = ScaleFactory.createHandlers().first { it.supportFor(device("R-MSC01", serviceFfb0)) != null }
        assertThat(handler).isInstanceOf(RenphoMSC01Handler::class.java)
    }

    @Test
    fun `builds the profile frames the app wrote`() {
        val unixSec = 0x6AD216C9L
        assertThat(RenphoMSC01Handler.buildProfile(0, unixSec, heightCm = 165, ageYears = 31, male = false))
            .isEqualTo(hex("00 00 17 00 be 6a d2 16 c9 00 3c 01 a5 17 70 1f 13 88 13 0f"))
        assertThat(RenphoMSC01Handler.buildProfile(2, unixSec, heightCm = 165, ageYears = 31, male = false))
            .isEqualTo(hex("02 00 17 00 be 6a d2 16 c9 00 3c 01 a5 17 70 1f 13 88 13 0f"))
    }

    @Test
    fun `builds the go and ack frames the app wrote`() {
        assertThat(RenphoMSC01Handler.buildGo(0)).isEqualTo(hex("00 00 17 01 88 0f 00 00 00 00 00 00 17"))
        assertThat(RenphoMSC01Handler.buildGo(2)).isEqualTo(hex("02 00 17 01 88 0f 00 00 00 00 00 00 17"))
        assertThat(RenphoMSC01Handler.buildAck(0)).isEqualTo(hex("01 00 03 00 b0 00 00 10"))
        assertThat(RenphoMSC01Handler.buildAck(1)).isEqualTo(hex("03 00 03 00 b0 01 00 11"))
    }

    @Test
    fun `decodes live weight frames`() {
        val settling = RenphoMSC01Handler.parseLiveWeight(hex("43 00 07 00 a2 01 25 88 14 14 00 18"))
        assertThat(settling).isEqualTo(RenphoMSC01Handler.Companion.LiveWeight(stable = false, grams = 5140))

        val stable = RenphoMSC01Handler.parseLiveWeight(hex("02 00 07 00 a2 03 25 88 e2 a4 00 18"))
        assertThat(stable).isEqualTo(RenphoMSC01Handler.Companion.LiveWeight(stable = true, grams = 58020))
    }

    @Test
    fun `rejects frames with a bad checksum`() {
        assertThat(RenphoMSC01Handler.parseLiveWeight(hex("02 00 07 00 a2 03 25 88 e2 a4 00 19"))).isNull()
        val broken = resultHead.copyOf().also { it[19] = 0x00 }
        assertThat(RenphoMSC01Handler.classifyIndication(broken)).isEqualTo(Indication.UNKNOWN)
    }

    @Test
    fun `classifies indications`() {
        fun classify(s: String) = RenphoMSC01Handler.classifyIndication(hex(s))
        assertThat(classify("00 00 1e 00 aa b3 71 16 20 64 25 01 0a 25 00 00 00 00 00 1d")).isEqualTo(Indication.HANDSHAKE_1)
        assertThat(classify("00 00 1e 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")).isEqualTo(Indication.HANDSHAKE_2)
        assertThat(classify("00 00 03 00 a0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")).isEqualTo(Indication.ROUND_0_CONFIRMED)
        assertThat(classify("01 00 03 00 a0 02 00 00 00 00 00 00 00 00 00 00 00 00 00 02")).isEqualTo(Indication.ROUND_2_CONFIRMED)
        assertThat(RenphoMSC01Handler.classifyIndication(resultFrame1)).isEqualTo(Indication.RESULT)
    }

    @Test
    fun `decodes the result head`() {
        val head = RenphoMSC01Handler.parseResultHead(resultHead)!!
        assertThat(head.kind).isEqualTo(RenphoMSC01Handler.KIND_LIVE)
        assertThat(head.isStored).isFalse()
        assertThat(head.unixSec).isEqualTo(0x6AD216CAL)
        assertThat(head.grams).isEqualTo(58020)
        assertThat(RenphoMSC01Handler.parseResultHead(resultFrame1)).isNull()
    }

    @Test
    fun `decodes weights above 65,535 kg from the 17th bit`() {
        // Session 8: 66.93 kg, flag byte 0x89 carries bit 16.
        val head = RenphoMSC01Handler.parseResultHead(hex("01 00 26 00 a7 6a d2 18 b7 25 89 05 72 00 0a 00 fe 12 19 0a"))!!
        assertThat(head.grams).isEqualTo(66930)
    }

    @Test
    fun `decodes the ten segment impedances as the app reports them`() {
        val s = RenphoMSC01Handler.parseSegments(resultHead, resultFrame1, resultFrame2)!!
        // App report, 20 kHz: RA 439.9, LA 458.5, trunk 21.1, RL 299.9, LL 284.7 (the app shows trunk × 0.826)
        assertThat(s.rightArm20).isWithin(0.05).of(439.9)
        assertThat(s.leftArm20).isWithin(0.05).of(458.5)
        assertThat(s.trunk20 * 0.826).isWithin(0.05).of(21.1)
        assertThat(s.rightLeg20).isWithin(0.05).of(299.9)
        assertThat(s.leftLeg20).isWithin(0.05).of(284.7)
        // 100 kHz: RA 402.2, LA 416.4, trunk 18.6, RL 272.2, LL 259.1
        assertThat(s.rightArm100).isWithin(0.05).of(402.2)
        assertThat(s.leftArm100).isWithin(0.05).of(416.4)
        assertThat(s.trunk100 * 0.826).isWithin(0.05).of(18.6)
        assertThat(s.rightLeg100).isWithin(0.05).of(272.2)
        assertThat(s.leftLeg100).isWithin(0.05).of(259.1)
    }

    // 2026-10-20, 111.44 kg: flag byte 0x8d (bit 2 set, meaning unknown) and much lower impedances.
    private val heavyHead = hex("01 00 26 00 a7 6a d7 97 5e 25 8d b3 50 00 0a 00 e8 0a f4 02")
    private val heavyFrame1 = hex("01 00 26 01 0a 98 09 bf 09 cf 00 c6 09 e6 09 99 08 c6 08 0f")
    private val heavyFrame2 = hex("01 00 26 02 cb 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0b")

    @Test
    fun `decodes a 111 kg result set`() {
        assertThat(RenphoMSC01Handler.parseResultHead(heavyHead)!!.grams).isEqualTo(111440)
        val s = RenphoMSC01Handler.parseSegments(heavyHead, heavyFrame1, heavyFrame2)!!
        // App report, 20 kHz: RA 271.2, LA 280.4, trunk 19.2, RL 251.1, LL 249.5
        assertThat(s.rightArm20).isWithin(0.05).of(271.2)
        assertThat(s.leftArm20).isWithin(0.05).of(280.4)
        assertThat(s.trunk20 * 0.826).isWithin(0.05).of(19.2)
        assertThat(s.rightLeg20).isWithin(0.05).of(251.1)
        assertThat(s.leftLeg20).isWithin(0.05).of(249.5)
        // 100 kHz: RA 245.7, LA 253.4, trunk 16.4, RL 225.1, LL 224.6
        assertThat(s.rightArm100).isWithin(0.05).of(245.7)
        assertThat(s.leftArm100).isWithin(0.05).of(253.4)
        assertThat(s.trunk100 * 0.826).isWithin(0.05).of(16.4)
        assertThat(s.rightLeg100).isWithin(0.05).of(225.1)
        assertThat(s.leftLeg100).isWithin(0.05).of(224.6)
    }

    @Test
    fun `body composition from the frames matches the app`() {
        // Session 2 of 2026-10-16 (165 cm, 58.02 kg) and the 111.44 kg woman (165 cm).
        val lightSegments = RenphoMSC01Handler.parseSegments(resultHead, resultFrame1, resultFrame2)!!
        val light = Wla37BodyComposition.compute(165, 58.02, lightSegments.toWla37Order())!!
        assertThat(light.fat).isEqualTo(28.0f)
        assertThat(light.lbmKg.toDouble()).isWithin(0.21).of(41.9)

        val heavySegments = RenphoMSC01Handler.parseSegments(heavyHead, heavyFrame1, heavyFrame2)!!
        val heavy = Wla37BodyComposition.compute(165, 111.44, heavySegments.toWla37Order())!!
        assertThat(heavy.fat).isEqualTo(53.2f)
        assertThat(heavy.visceralFat).isEqualTo(20)
    }

    @Test
    fun `segments need the three frames in order`() {
        assertThat(RenphoMSC01Handler.parseSegments(resultHead, resultFrame2, resultFrame1)).isNull()
    }
}
