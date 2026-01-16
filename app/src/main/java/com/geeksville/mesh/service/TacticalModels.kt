package com.geeksville.mesh.service

import kotlinx.serialization.Serializable

/**
 * These classes define the exact JSON structure for the Server.
 * We use @Serializable so Kotlin can automatically turn them into JSON text.
 */

// 1. The Main Wrapper (Scenario A, B, C)
@Serializable
data class TacticalMessage(
    val operation_id: String,
    val mission_id: String,
    val timestamp: String,       // ISO 8601: "2026-01-10T14:30:05.123Z"
    val category: String,        // "CHAT", "POSITION", "ZONE_ADD", "ZONE_DELETE"
    val source_id: String,       // e.g., "Unit-01"
    val destination_id: String,  // e.g., "BROADCAST"
    val latitude: Double,
    val longitude: Double,
    val payload: String          // The message text OR the Zone JSON string
)

// 2. The Payload for Zones (Scenario B)
// This goes INSIDE the 'payload' field above when category is ZONE_ADD
@Serializable
data class ZonePayload(
    val zone_type: String,       // e.g., "DANGER", "LZ"
    val radius: Int,             // e.g., 500
    val label: String            // e.g., "Minefield"
)

// 3. The Payload for Deleting Zones (Scenario C)
// This goes INSIDE the 'payload' field above when category is ZONE_DELETE
@Serializable
data class ZoneDeletePayload(
    val zone_id: String          // e.g., "RED", "BLUE"
)