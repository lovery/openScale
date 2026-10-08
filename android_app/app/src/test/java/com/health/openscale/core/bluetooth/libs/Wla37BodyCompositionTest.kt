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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [Wla37BodyComposition], against 17 reports of the Renpho Morpho Scan app.
 *
 * Impedances of the first 13 are the app's report (the BLE captures agree to 0.1 ohm), with the
 * trunk divided by the app's 0.826 to get the scale's value back; the last 4 are from the capture. The app shows one decimal; it rounds some values
 * a little differently, so a few are 0.1 off.
 */
class Wla37BodyCompositionTest {

    /** One Morpho Scan report: scale data, profile height and the values the app showed. */
    private data class Report(
        val heightCm: Int, val weightKg: Double, val z: DoubleArray,
        val fatPct: Double, val ffm: Double, val water: Double, val protein: Double, val bone: Double,
        val muscle: Double, val skeletalMuscle: Double, val bmr: Int, val subcutaneousPct: Double,
        val visceral: Int? = null,
    )

    /**
     * Impedances as the report lists them (right arm, left arm, trunk, right leg, left leg at
     * 20 kHz, then 100 kHz), in the library's order and with the trunk as the scale sends it.
     */
    private fun z(vararg v: Double) = scale(v[0], v[1], v[2] / 0.826, v[3], v[4], v[5], v[6], v[7] / 0.826, v[8], v[9])

    /** The same order, with the trunk as the scale sends it. */
    private fun scale(vararg v: Double) = doubleArrayOf(v[2], v[1], v[0], v[4], v[3], v[7], v[6], v[5], v[9], v[8])

    private val reports = listOf(
        Report(165, 58.02, z(439.9, 458.5, 21.1, 299.9, 284.7, 402.2, 416.4, 18.6, 272.2, 259.1),
            28.0, 41.9, 30.6, 8.4, 2.9, 38.9, 22.9, 1270, 20.0),
        Report(165, 58.02, z(447.7, 459.5, 20.9, 299.7, 288.2, 411.0, 417.1, 18.4, 273.2, 263.3),
            28.7, 41.5, 30.3, 8.3, 2.9, 38.6, 22.7, 1264, 20.5),
        Report(180, 58.01, z(443.8, 456.5, 21.2, 300.1, 286.8, 405.6, 415.7, 18.3, 273.0, 261.8),
            12.6, 50.7, 37.2, 10.1, 3.4, 47.3, 28.4, 1465, 9.0),
        Report(165, 58.01, z(440.4, 456.2, 21.1, 303.5, 287.0, 402.5, 413.8, 18.8, 276.4, 262.5),
            28.5, 41.6, 30.4, 8.3, 2.9, 38.7, 22.7, 1266, 20.4),
        Report(165, 58.01, z(441.9, 459.1, 21.1, 301.2, 286.7, 404.5, 418.0, 18.1, 274.1, 262.3),
            28.4, 41.6, 30.4, 8.3, 2.9, 38.7, 22.7, 1266, 20.3),
        Report(150, 58.01, z(437.8, 454.8, 20.3, 298.9, 284.4, 400.4, 414.5, 17.9, 272.1, 260.2),
            42.0, 33.6, 24.6, 6.7, 2.3, 31.3, 17.9, 1095, 29.9),
        Report(175, 66.93, z(446.8, 463.3, 21.0, 302.5, 285.6, 409.9, 420.9, 18.7, 275.3, 260.8),
            27.3, 48.7, 35.7, 9.7, 3.3, 45.4, 27.1, 1420, 19.5),
        Report(195, 66.93, z(445.1, 455.9, 20.9, 303.2, 284.0, 408.8, 416.0, 18.7, 276.1, 259.7),
            6.7, 62.5, 45.8, 12.5, 4.2, 58.2, 35.6, 1718, 4.8),
        Report(163, 68.59, z(373.6, 372.7, 19.7, 282.1, 286.5, 339.3, 341.1, 16.5, 251.7, 256.3),
            32.8, 46.1, 33.8, 9.2, 3.1, 43.0, 25.6, 1365, 23.4, visceral = 9),
        Report(165, 86.07, z(342.5, 343.5, 20.7, 232.0, 230.0, 307.7, 310.3, 17.7, 206.8, 204.7),
            40.0, 51.7, 37.9, 10.3, 3.5, 48.2, 28.9, 1485, 28.5, visceral = 15),
        Report(165, 111.44, z(271.2, 280.4, 19.2, 251.1, 249.5, 245.7, 253.4, 16.4, 225.1, 224.6),
            53.2, 52.2, 38.2, 10.5, 3.5, 48.7, 29.3, 1497, 37.7, visceral = 20),
        Report(170, 79.21, z(258.4, 283.2, 20.9, 238.1, 226.2, 225.3, 249.8, 17.2, 210.6, 199.9),
            25.1, 59.4, 43.5, 11.9, 4.0, 55.4, 33.7, 1650, 17.9, visceral = 7),
        Report(170, 99.06, z(347.0, 347.3, 21.6, 243.4, 248.3, 315.4, 316.2, 18.4, 212.6, 218.2),
            43.9, 55.5, 40.7, 11.1, 3.7, 51.8, 31.3, 1569, 31.2, visceral = 19),
        Report(165, 56.9, scale(437.7, 449.7, 25.0, 304.0, 295.9, 400.0, 407.1, 21.1, 277.9, 270.9),
            27.2, 41.5, 30.3, 8.3, 2.9, 38.6, 22.7, 1264, 19.4, visceral = 6),
        Report(165, 56.9, scale(438.5, 449.9, 25.6, 305.3, 294.6, 400.3, 406.7, 23.0, 278.9, 269.6),
            27.6, 41.4, 30.2, 8.3, 2.9, 38.5, 22.5, 1259, 19.7, visceral = 6),
        Report(165, 56.9, scale(443.7, 451.6, 25.6, 303.9, 292.6, 405.6, 407.3, 23.0, 277.2, 268.1),
            27.6, 41.4, 30.2, 8.3, 2.9, 38.5, 22.5, 1259, 19.7, visceral = 6),
        Report(165, 56.9, scale(443.9, 451.6, 25.5, 307.1, 294.7, 406.0, 408.6, 21.5, 280.5, 270.3),
            27.5, 41.5, 30.3, 8.3, 2.9, 38.5, 22.6, 1262, 19.6, visceral = 6),
    )

    private fun compute(r: Report) = Wla37BodyComposition.compute(r.heightCm, r.weightKg, r.z)!!

    @Test
    fun values_matchTheMorphoScanApp_forSeventeenReports() {
        for (r in reports) {
            val c = compute(r)
            fun kg(percent: Float) = percent * c.weightKg / 100.0
            assertThat(c.fat.toDouble()).isWithin(0.11).of(r.fatPct)
            // The app's "fat-free body weight" reads up to 0.2 kg above its weight minus its fat mass.
            assertThat(c.lbmKg.toDouble()).isWithin(0.31).of(r.ffm)
            assertThat(kg(c.water)).isWithin(0.15).of(r.water)
            assertThat(kg(c.protein)).isWithin(0.15).of(r.protein)
            assertThat(c.boneKg.toDouble()).isWithin(0.11).of(r.bone)
            assertThat(c.muscleKg.toDouble()).isWithin(0.11).of(r.muscle)
            assertThat(kg(c.skeletalMuscle)).isWithin(0.15).of(r.skeletalMuscle)
            assertThat(c.bmrKcal).isIn(r.bmr - 1..r.bmr + 1)
            assertThat(c.subcutaneousFat.toDouble()).isWithin(0.11).of(r.subcutaneousPct)
            if (r.visceral != null) assertThat(c.visceralFat).isEqualTo(r.visceral)
        }
    }

    @Test
    fun metabolicAge_matchesTheMorphoScanApp_forEachSexAndAge() {
        val profiles = listOf(31 to false, 31 to true, 47 to false, 50 to true)
        val appAges = listOf(29, 33, 45, 52)
        for (i in profiles.indices) {
            val (age, male) = profiles[i]
            val fat = compute(reports[13 + i]).fat.toDouble()
            val sex = if (male) Wla25BodyComposition.SEX_MALE else Wla25BodyComposition.SEX_FEMALE
            assertThat(Wla25BodyComposition.bodyAge(age, fat, sex)).isEqualTo(appAges[i])
        }
    }

    @Test
    fun compartments_addUp() {
        for (r in reports) {
            val c = compute(r)
            // Fat mass and fat % are rounded separately to one decimal.
            val fatKg = c.weightKg - c.lbmKg
            assertThat(fatKg.toDouble()).isWithin(0.1).of(c.fat * c.weightKg / 100.0)
        }
    }

    @Test
    fun fatPercentage_isLimitedTo3to60() {
        // Same impedances with an unlikely weight for the height: the raw equation gives -30 % and 77 %.
        val z = reports.first().z
        assertThat(Wla37BodyComposition.compute(195, 40.0, z)!!.fat).isEqualTo(3.0f)
        assertThat(Wla37BodyComposition.compute(150, 120.0, z)!!.fat).isEqualTo(60.0f)
    }

    @Test
    fun higherTrunk100_isReplacedLikeTheApp() {
        // A 100 kHz trunk above the 20 kHz one is replaced by the 20 kHz one minus 3 ohm (after scaling).
        val r = reports.first()
        val swapped = r.z.copyOf().also { it[5] = 30.0 }
        val replaced = r.z.copyOf().also { it[5] = (it[0] * 0.826 - 3.0) / 0.826 }
        assertThat(Wla37BodyComposition.compute(r.heightCm, r.weightKg, swapped))
            .isEqualTo(Wla37BodyComposition.compute(r.heightCm, r.weightKg, replaced))
    }

    @Test
    fun inputsOutOfRange_giveNoResult() {
        val z = reports.first().z
        fun ok(heightCm: Int = 165, weightKg: Double = 58.0, imps: DoubleArray = z) =
            Wla37BodyComposition.compute(heightCm, weightKg, imps) != null
        assertThat(ok()).isTrue()
        assertThat(ok(heightCm = 99)).isFalse()
        assertThat(ok(heightCm = 221)).isFalse()
        assertThat(ok(weightKg = 19.9)).isFalse()
        assertThat(ok(weightKg = 200.1)).isFalse()
        assertThat(ok(imps = z.copyOf().also { it[8] = 99.0 })).isFalse()
        assertThat(ok(imps = z.copyOf().also { it[0] = 0.0 })).isFalse()
    }
}
