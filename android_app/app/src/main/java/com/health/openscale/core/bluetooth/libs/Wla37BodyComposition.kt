/*
 * openScale
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

/**
 * Body composition as computed by the Fitdays/icomon `WLA37` algorithm, used by the "Morpho Scan"
 * app for the Renpho R-MSC01 scale.
 *
 * `ICBodyFatAlgorithmWLA37::calc` is `WLA25` with a different fat mass algorithm, so everything else
 * is re-used from the Wla25BodyComposition.
 * The fat mass comes from `calcBF`, a linear regression with three coefficient sets chosen by
 * the sum of the five 20 kHz impedances divided by the height (see [FatCoefficients]).
 *
 * Sex and age enters are used in the metabolic age calculation and the app's reference ranges
 * but not in the fat mass calculation.
 *
 * The scale measures ten impedances in ohm: trunk, left arm, right arm, left leg, right leg at 20 kHz,
 * and the same at 100 kHz. The trunk values the library scales them by 0.826, as [Wla25BodyComposition] does.
 *
 * Checked against 17 Morpho Scan reports of 6 people (150-195 cm, 57-111 kg): every value agrees
 * with the app to its last digit or, in a few cases, 0.1 off. The exception is the app's
 * "fat-free body weight", which it shows up to 0.2 kg above its own weight minus fat mass.
 */
object Wla37BodyComposition {

    /**
     * Compute every field, or `null` if the height, the weight or the impedances are outside
     * what the library accepts. [rawWeightKg] is the weight off the wire; it is rounded here.
     */
    fun compute(heightCm: Int, rawWeightKg: Double, imps: DoubleArray): Wla25BodyComposition.Result? {
        if (!isInRange(heightCm, rawWeightKg, imps)) return null
        val weight = Wla25BodyComposition.round1(rawWeightKg)
        val fat = fatMass(heightCm, weight, imps)
        return Wla25BodyComposition.fromFatMass(heightCm, weight, fat)
    }

    /** Metabolic age, which the library computes as WLA25. */
    fun bodyAge(age: Int, fatPercent: Double, male: Boolean): Int = Wla25BodyComposition.bodyAge(
        age, fatPercent, if (male) Wla25BodyComposition.SEX_MALE else Wla25BodyComposition.SEX_FEMALE,
    )

    /** The library's gate: the WLA25 impedance check plus a height and weight range. */
    fun isInRange(heightCm: Int, weightKg: Double, imps: DoubleArray): Boolean =
        heightCm in 100..220 && weightKg in 20.0..200.0 && Wla25BodyComposition.impedancesValid(imps)

    /** `calcBF`: fat mass in kg, returned in single precision as the library does. */
    private fun fatMass(heightCm: Int, weight: Double, imps: DoubleArray): Double {
        val trunk20 = imps[0] * 0.826
        // A 100 kHz trunk above the 20 kHz one is replaced by the 20 kHz one minus 3 ohm.
        val trunk100 = if (imps[5] <= imps[0]) imps[5] * 0.826 else trunk20 - 3.0
        val leftArm20 = imps[1]; val rightArm20 = imps[2]; val leftLeg20 = imps[3]; val rightLeg20 = imps[4]
        val leftArm100 = imps[6]; val rightArm100 = imps[7]; val leftLeg100 = imps[8]; val rightLeg100 = imps[9]

        val h = heightCm.toDouble()
        val h2 = h * h
        val bmi = Wla25BodyComposition.bmi(heightCm, weight)
        val sum20 = trunk20 + leftArm20 + rightArm20 + leftLeg20 + rightLeg20
        val sum100 = trunk100 + leftArm100 + rightArm100 + leftLeg100 + rightLeg100
        // Arms in parallel, legs in parallel, in series with the trunk: the whole body.
        val body20 = trunk20 + parallel(leftArm20, rightArm20) + parallel(leftLeg20, rightLeg20)
        val body100 = trunk100 + parallel(leftArm100, rightArm100) + parallel(leftLeg100, rightLeg100)

        val c = when {
            sum20 / h < 9.0 -> LOW_IMPEDANCE
            sum20 / h < 12.0 -> MID_IMPEDANCE
            else -> HIGH_IMPEDANCE
        }
        return c.intercept +
            c.weight * weight + c.weightSquared * weight * weight + c.height * h + c.heightSquared * h2 + c.bmi * bmi +
            c.body20PerHeightSquared * body20 / h2 + c.body100PerHeightSquared * body100 / h2 +
            c.sum20PerHeightSquared * sum20 / h2 + c.sum100PerHeightSquared * sum100 / h2 +
            c.rightArm20TimesBmi * rightArm20 * bmi + c.leftArm20TimesBmi * leftArm20 * bmi +
            c.rightLeg20TimesBmi * rightLeg20 * bmi + c.leftLeg20TimesBmi * leftLeg20 * bmi +
            c.rightLeg100TimesBmi * rightLeg100 * bmi + c.leftLeg100TimesBmi * leftLeg100 * bmi +
            c.rightArmFrequencyDrop * (rightArm20 - rightArm100) + c.leftArmFrequencyDrop * (leftArm20 - leftArm100) +
            c.trunkFrequencyDrop * (trunk20 - trunk100) +
            c.rightLegFrequencyDrop * (rightLeg20 - rightLeg100) + c.leftLegFrequencyDrop * (leftLeg20 - leftLeg100) +
            c.rightArmFrequencyRatio * rightArm20 / rightArm100 + c.leftArmFrequencyRatio * leftArm20 / leftArm100
    }

    private fun parallel(a: Double, b: Double) = a * b / (a + b)

    /**
     * Coefficients of the fat mass equation, one per term. Impedances are in ohm, height in cm,
     * weight in kg; "20" and "100" are the frequency in kHz. The library has five more terms
     * (trunk, left and right leg 20/100 kHz ratios, trunk 20 and 100 kHz × BMI) whose
     * coefficients are zero in every set; they are left out. The values are the library's,
     * rounded to 7 significant digits (the precision of its single-precision result); fewer
     * digits change some results by a tenth.
     */
    private data class FatCoefficients(
        val intercept: Double,

        // Body size
        val weight: Double,
        val weightSquared: Double,
        val height: Double,
        val heightSquared: Double,
        val bmi: Double,

        // Whole-body impedance (trunk + arms in parallel + legs in parallel) / height²
        val body20PerHeightSquared: Double,
        val body100PerHeightSquared: Double,

        // Sum of the five segment impedances / height²
        val sum20PerHeightSquared: Double,
        val sum100PerHeightSquared: Double,

        // Limb impedance × BMI
        val rightArm20TimesBmi: Double,
        val leftArm20TimesBmi: Double,
        val rightLeg20TimesBmi: Double,
        val leftLeg20TimesBmi: Double,
        val rightLeg100TimesBmi: Double,
        val leftLeg100TimesBmi: Double,

        // Impedance drop from 20 to 100 kHz per segment; these change the result by under 0.001 kg
        val rightArmFrequencyDrop: Double,
        val leftArmFrequencyDrop: Double,
        val trunkFrequencyDrop: Double,
        val rightLegFrequencyDrop: Double,
        val leftLegFrequencyDrop: Double,

        // 20 kHz / 100 kHz impedance ratio; about 1e-12, kept as the library has them
        val rightArmFrequencyRatio: Double,
        val leftArmFrequencyRatio: Double,
    )

    /** Sum of the 20 kHz impedances / height below 9 ohm/cm: lean, low impedance. */
    private val LOW_IMPEDANCE = FatCoefficients(
        intercept = -120.4092,
        weight = -0.6479386,
        weightSquared = 0.004367991,
        height = 1.413764,
        heightSquared = -0.004381009,
        bmi = 0.8972441,
        body20PerHeightSquared = 3659.568,
        body100PerHeightSquared = 6296.514,
        sum20PerHeightSquared = -1804.57,
        sum100PerHeightSquared = -1066.976,
        rightArm20TimesBmi = 0.001321301,
        leftArm20TimesBmi = 0.001476299,
        rightLeg20TimesBmi = -0.005092672,
        leftLeg20TimesBmi = -0.002131949,
        rightLeg100TimesBmi = 0.007120547,
        leftLeg100TimesBmi = 0.003926916,
        rightArmFrequencyDrop = 3.744637e-7,
        leftArmFrequencyDrop = 1.86228e-6,
        trunkFrequencyDrop = -3.216678e-6,
        rightLegFrequencyDrop = -1.470834e-7,
        leftLegFrequencyDrop = 2.441052e-8,
        rightArmFrequencyRatio = 3.183231e-12,
        leftArmFrequencyRatio = 1.818989e-12,
    )

    /** Sum of the 20 kHz impedances / height from 9 to 12 ohm/cm: the middle range. */
    private val MID_IMPEDANCE = FatCoefficients(
        intercept = -62.12201,
        weight = 0.02138167,
        weightSquared = 0.001555788,
        height = 0.4323575,
        heightSquared = -0.001381905,
        bmi = 0.9006858,
        body20PerHeightSquared = -64.66868,
        body100PerHeightSquared = 5129.882,
        sum20PerHeightSquared = -553.2129,
        sum100PerHeightSquared = -709.2416,
        rightArm20TimesBmi = 0.0007544688,
        leftArm20TimesBmi = 0.0005358785,
        rightLeg20TimesBmi = -0.003472533,
        leftLeg20TimesBmi = -0.001170729,
        rightLeg100TimesBmi = 0.004522028,
        leftLeg100TimesBmi = 0.001783771,
        rightArmFrequencyDrop = -1.293716e-5,
        leftArmFrequencyDrop = -6.320107e-7,
        trunkFrequencyDrop = -1.203235e-6,
        rightLegFrequencyDrop = -1.044319e-7,
        leftLegFrequencyDrop = -1.385585e-8,
        rightArmFrequencyRatio = 8.185452e-12,
        leftArmFrequencyRatio = -2.273737e-12,
    )

    /** Sum of the 20 kHz impedances / height of 12 ohm/cm and more: high impedance. */
    private val HIGH_IMPEDANCE = FatCoefficients(
        intercept = 459.0765,
        weight = -4.523962,
        weightSquared = 0.01834023,
        height = -3.721954,
        heightSquared = 0.01135886,
        bmi = 0.7795223,
        body20PerHeightSquared = 5488.248,
        body100PerHeightSquared = 1398.754,
        sum20PerHeightSquared = -4140.393,
        sum100PerHeightSquared = 446.51,
        rightArm20TimesBmi = 0.00598359,
        leftArm20TimesBmi = 0.00345402,
        rightLeg20TimesBmi = 0.002920009,
        leftLeg20TimesBmi = 0.002092333,
        rightLeg100TimesBmi = 0.001665311,
        leftLeg100TimesBmi = 0.003211134,
        rightArmFrequencyDrop = -3.361592e-6,
        leftArmFrequencyDrop = -4.12878e-6,
        trunkFrequencyDrop = 1.885596e-6,
        rightLegFrequencyDrop = 9.05501e-7,
        leftLegFrequencyDrop = 2.902472e-9,
        rightArmFrequencyRatio = 2.046363e-12,
        leftArmFrequencyRatio = -3.637979e-12,
    )
}
