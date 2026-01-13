package org.meshtastic.core.ui

// IMPORTANT: Ensure this R import matches your project structure
// import com.geeksville.mesh.R

/**
 * Manages tactical markers and IDs for the Defense Dynamics project.
 * Handles: Unit Icons 🛡️, Operation IDs (Op), and Mission IDs {Mission}
 */
object TacticalIconManager {

    // --- The Secret Codes (Emojis) ---
    private const val CODE_TANK = "🛡️"
    private const val CODE_HELI = "🚁"
    private const val CODE_DRONE = "✈️"

    // Stores the user's choice temporarily
    var myCurrentEmoji: String? = null

    // --- Marker Logic ---

    fun getMarkerDrawable(longName: String?): Int {
        if (longName == null) return R.drawable.marker_soldier
        return when {
            longName.contains(CODE_TANK) -> R.drawable.marker_tank
            longName.contains(CODE_HELI) -> R.drawable.marker_heli
            longName.contains(CODE_DRONE) -> R.drawable.marker_drone
            else -> R.drawable.marker_soldier
        }
    }

    fun getTypeFromName(longName: String?): UnitType {
        if (longName == null) return UnitType.SOLDIER
        return when {
            longName.contains(CODE_TANK) -> UnitType.TANK
            longName.contains(CODE_HELI) -> UnitType.HELI
            longName.contains(CODE_DRONE) -> UnitType.DRONE
            else -> UnitType.SOLDIER
        }
    }

    fun getEmojiForType(type: UnitType): String {
        return when (type) {
            UnitType.SOLDIER -> ""
            UnitType.TANK -> CODE_TANK
            UnitType.HELI -> CODE_HELI
            UnitType.DRONE -> CODE_DRONE
        }
    }

    // --- NEW: Operation & Mission ID Logic ---

    /**
     * 1. Extract The Real Name (Viper)
     * Removes Emojis 🛡️, Op IDs (Alpha), and Mission IDs {Rescue}
     */
    fun getCleanName(longName: String?): String {
        if (longName == null) return "Unknown"
        var name = longName

        // Remove Emojis
        name = name.replace(CODE_TANK, "")
            .replace(CODE_HELI, "")
            .replace(CODE_DRONE, "")

        // Remove Op ID -> matches anything inside parentheses (...)
        name = name.replace("\\(.*?\\)".toRegex(), "")

        // Remove Mission ID -> matches anything inside brackets {...}
        name = name.replace("\\{.*?\\}".toRegex(), "")

        return name.trim()
    }

    /**
     * 2. Extract Operation ID
     * Looks for text inside (). Example: "Viper (Alpha)" -> returns "Alpha"
     */
    fun getOperationId(longName: String?): String {
        if (longName == null) return ""
        // Regex: Find text between ( and )
        val match = "\\((.*?)\\)".toRegex().find(longName)
        return match?.groupValues?.get(1) ?: ""
    }

    /**
     * 3. Extract Mission ID
     * Looks for text inside {}. Example: "Viper {Rescue}" -> returns "Rescue"
     */
    fun getMissionId(longName: String?): String {
        if (longName == null) return ""
        // Regex: Find text between { and }
        val match = "\\{(.*?)\\}".toRegex().find(longName)
        return match?.groupValues?.get(1) ?: ""
    }

    /**
     * 4. The Master Builder
     * Takes all the pieces and builds the final string: "Name 🛡️ (Op) {Mission}"
     * * You can pass specific new values (e.g. newOpId), otherwise it keeps the existing ones.
     */
    fun generateFullLongName(
        currentLongName: String,
        newOpId: String? = null,
        newMissionId: String? = null,
        newUnitType: UnitType? = null
    ): String {

        // A. Get the base name (e.g. "Viper") without any junk
        val baseName = getCleanName(currentLongName)

        // B. Determine which values to use (New one? or Keep existing?)
        val opId = newOpId ?: getOperationId(currentLongName)
        val missionId = newMissionId ?: getMissionId(currentLongName)
        val type = newUnitType ?: getTypeFromName(currentLongName)

        val emoji = getEmojiForType(type)

        // C. Format the parts
        val opPart = if (opId.isNotBlank()) "($opId)" else ""
        val missionPart = if (missionId.isNotBlank()) "{$missionId}" else ""

        // D. Combine: "Viper" + "🛡️" + "(Alpha)" + "{Rescue}"
        // .replace removes double spaces if a part is missing
        return "$baseName $emoji $opPart $missionPart".replace("\\s+".toRegex(), " ").trim()
    }

    enum class UnitType {
        SOLDIER, TANK, HELI, DRONE
    }
}