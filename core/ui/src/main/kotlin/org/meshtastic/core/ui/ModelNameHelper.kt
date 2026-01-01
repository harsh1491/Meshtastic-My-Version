package org.meshtastic.core.ui

object ModelNameHelper {
    fun getFriendlyName(rawName: String?): String {
        if (rawName == null) return "Unknown Unit"

        return when (rawName) {
            // TARGET: The one you are using
            "LILYGO_TBEAM_S3_CORE" -> "Defend_Dynamix_Soldier_Unit"

            // EXAMPLES: You can rename others if you have them
            "TBEAM" -> "Standard Unit"
            "HELTEC_V3" -> "Commander Radio"
            "RAK4631" -> "Scout Unit"

            // DEFAULT: If we don't know it, just show the original
            else -> rawName
        }
    }
}