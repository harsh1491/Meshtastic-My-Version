package org.meshtastic.core.ui

// IMPORTANT: If 'R' is red, click it and press Alt+Enter to import your app's R file.
// It will likely be: import com.geeksville.mesh.R
// OR: import org.meshtastic.core.ui.R (if you moved images to core)

/**
 * Manages tactical markers for the Defense Dynamics project.
 * Detects hidden emojis in user names and returns the correct military icon.
 */
object TacticalIconManager {

    // --- The Secret Codes (Emojis) ---
    // We append these to the Long Name to signal what unit we are.
    // NOTE: These specific emojis act as our "Digital Handshake"
    private const val CODE_TANK = "🛡️"
    private const val CODE_HELI = "🚁"
    private const val CODE_DRONE = "✈️"

    // --- The Marker Logic ---

    /**
     * Looks at a user's name and returns the correct PNG resource ID.
     * Usage: Image(painterResource(id = TacticalIconManager.getMarkerDrawable(node.longName)))
     */
    fun getMarkerDrawable(longName: String?): Int {
        if (longName == null) return R.drawable.marker_soldier // Default Safety

        return when {
            longName.contains(CODE_TANK) -> R.drawable.marker_tank
            longName.contains(CODE_HELI) -> R.drawable.marker_heli
            longName.contains(CODE_DRONE) -> R.drawable.marker_drone
            else -> R.drawable.marker_soldier // Default for everyone else
        }
    }


    /**
     * Helper to find out what type a user currently is.
     * Useful for the Settings Page "Current Selection" text.
     */
    fun getTypeFromName(longName: String?): UnitType {
        if (longName == null) return UnitType.SOLDIER
        return when {
            longName.contains(CODE_TANK) -> UnitType.TANK
            longName.contains(CODE_HELI) -> UnitType.HELI
            longName.contains(CODE_DRONE) -> UnitType.DRONE
            else -> UnitType.SOLDIER
        }
    }

    /**
     * Cleans the name for display.
     * Removes the secret emoji so the user just sees "Commander" instead of "Commander 🛡️"
     */
    fun getCleanName(longName: String?): String {
        if (longName == null) return "Unknown"

        return longName
            .replace(CODE_TANK, "")
            .replace(CODE_HELI, "")
            .replace(CODE_DRONE, "")
            .trim() // Removes any extra spaces left behind
    }

    /**
     * Used in the Settings Page.
     * Returns the Emoji code that corresponds to a specific type.
     */
    fun getEmojiForType(type: UnitType): String {
        return when (type) {
            UnitType.SOLDIER -> "" // Soldier is default, no emoji needed
            UnitType.TANK -> CODE_TANK
            UnitType.HELI -> CODE_HELI
            UnitType.DRONE -> CODE_DRONE
        }
    }

    // Simple Enum to use in our Dropdown Menu later
    enum class UnitType {
        SOLDIER, TANK, HELI, DRONE
    }
}