/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon

/**
 * Segmental body fat and muscle, in % of each segment and in kg, shared by every handler that reports
 * them (Beurer BF1000, Renpho R-MSC01).
 *
 * Semantic, vendor-neutral identities: a scale reporting the same quantities writes into the
 * same columns, so the user's history survives a scale change. The icon says *where* on the
 * body, the colour says *what* is measured: fat segments are shades of the predefined BODY_FAT
 * red, muscle segments shades of the MUSCLE green, darkening from the arms down to the legs.
 * The anchor shades themselves stay reserved for the whole-body totals.
 */
internal object SegmentalMeasurementTypes {
    val FAT_LEFT_ARM   = fat("left_arm",  R.string.measurement_type_segmental_fat_left_arm,   MeasurementTypeIcon.IC_BICEPS, 0xFFE57373)
    val FAT_RIGHT_ARM  = fat("right_arm", R.string.measurement_type_segmental_fat_right_arm,  MeasurementTypeIcon.IC_BICEPS, 0xFFF44336)
    val FAT_TORSO      = fat("torso",     R.string.measurement_type_segmental_fat_torso,      MeasurementTypeIcon.IC_CHEST,  0xFFE53935)
    val FAT_LEFT_LEG   = fat("left_leg",  R.string.measurement_type_segmental_fat_left_leg,   MeasurementTypeIcon.IC_THIGH,  0xFFD32F2F)
    val FAT_RIGHT_LEG  = fat("right_leg", R.string.measurement_type_segmental_fat_right_leg,  MeasurementTypeIcon.IC_THIGH,  0xFFC62828)

    val MUSCLE_LEFT_ARM  = muscle("left_arm",  R.string.measurement_type_segmental_muscle_left_arm,  MeasurementTypeIcon.IC_BICEPS, 0xFF81C784)
    val MUSCLE_RIGHT_ARM = muscle("right_arm", R.string.measurement_type_segmental_muscle_right_arm, MeasurementTypeIcon.IC_BICEPS, 0xFF4CAF50)
    val MUSCLE_TORSO     = muscle("torso",     R.string.measurement_type_segmental_muscle_torso,     MeasurementTypeIcon.IC_CHEST,  0xFF43A047)
    val MUSCLE_LEFT_LEG  = muscle("left_leg",  R.string.measurement_type_segmental_muscle_left_leg,  MeasurementTypeIcon.IC_THIGH,  0xFF388E3C)
    val MUSCLE_RIGHT_LEG = muscle("right_leg", R.string.measurement_type_segmental_muscle_right_leg, MeasurementTypeIcon.IC_THIGH,  0xFF2E7D32)

    // The same segments in kg. Only scales that know the segment masses report these
    // (Renpho R-MSC01); the % of a segment cannot be turned back into kg without them.
    val FAT_MASS_LEFT_ARM   = fatMass("left_arm",  R.string.measurement_type_segmental_fat_mass_left_arm,   MeasurementTypeIcon.IC_BICEPS, 0xFFE57373)
    val FAT_MASS_RIGHT_ARM  = fatMass("right_arm", R.string.measurement_type_segmental_fat_mass_right_arm,  MeasurementTypeIcon.IC_BICEPS, 0xFFF44336)
    val FAT_MASS_TORSO      = fatMass("torso",     R.string.measurement_type_segmental_fat_mass_torso,      MeasurementTypeIcon.IC_CHEST,  0xFFE53935)
    val FAT_MASS_LEFT_LEG   = fatMass("left_leg",  R.string.measurement_type_segmental_fat_mass_left_leg,   MeasurementTypeIcon.IC_THIGH,  0xFFD32F2F)
    val FAT_MASS_RIGHT_LEG  = fatMass("right_leg", R.string.measurement_type_segmental_fat_mass_right_leg,  MeasurementTypeIcon.IC_THIGH,  0xFFC62828)

    val MUSCLE_MASS_LEFT_ARM  = muscleMass("left_arm",  R.string.measurement_type_segmental_muscle_mass_left_arm,  MeasurementTypeIcon.IC_BICEPS, 0xFF81C784)
    val MUSCLE_MASS_RIGHT_ARM = muscleMass("right_arm", R.string.measurement_type_segmental_muscle_mass_right_arm, MeasurementTypeIcon.IC_BICEPS, 0xFF4CAF50)
    val MUSCLE_MASS_TORSO     = muscleMass("torso",     R.string.measurement_type_segmental_muscle_mass_torso,     MeasurementTypeIcon.IC_CHEST,  0xFF43A047)
    val MUSCLE_MASS_LEFT_LEG  = muscleMass("left_leg",  R.string.measurement_type_segmental_muscle_mass_left_leg,  MeasurementTypeIcon.IC_THIGH,  0xFF388E3C)
    val MUSCLE_MASS_RIGHT_LEG = muscleMass("right_leg", R.string.measurement_type_segmental_muscle_mass_right_leg, MeasurementTypeIcon.IC_THIGH,  0xFF2E7D32)

    private fun fatMass(part: String, nameResId: Int, icon: MeasurementTypeIcon, color: Long) =
        MeasurementType.deviceKg("segmental.fat_mass.$part", nameResId, icon = icon, color = color.toInt())

    private fun muscleMass(part: String, nameResId: Int, icon: MeasurementTypeIcon, color: Long) =
        MeasurementType.deviceKg("segmental.muscle_mass.$part", nameResId, icon = icon, color = color.toInt())

    private fun fat(part: String, nameResId: Int, icon: MeasurementTypeIcon, color: Long) =
        MeasurementType.devicePercent("segmental.fat.$part", nameResId, icon = icon, color = color.toInt())

    private fun muscle(part: String, nameResId: Int, icon: MeasurementTypeIcon, color: Long) =
        MeasurementType.devicePercent("segmental.muscle.$part", nameResId, icon = icon, color = color.toInt())
}
