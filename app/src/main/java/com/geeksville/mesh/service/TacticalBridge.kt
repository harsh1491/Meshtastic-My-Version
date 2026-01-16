package com.geeksville.mesh.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TacticalBridge {
    private const val TAG = "TacticalBridge"
    private const val SERVER_IP = "127.0.0.1"
    private const val SERVER_PORT = 9090

    var isEnabled = true
    private val json = Json { encodeDefaults = true }

    // --- UPDATED SIGNATURE: Now accepts opId and missionId ---
    fun onNewMessage(
        payloadText: String?,
        senderId: String,
        receiverId: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        operationId: String,  // <--- PASSED FROM MESH SERVICE
        missionId: String     // <--- PASSED FROM MESH SERVICE
    ) {
        if (!isEnabled || payloadText.isNullOrEmpty()) return

        val cleanText = payloadText.trim()
        val category = getCategory(cleanText)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val timestamp = getCurrentIsoTimestamp()

                // specific payload parsing
                val finalPayload = when (category) {
                    "ZONE_ADD" -> parseZonePayload(cleanText)
                    "ZONE_DELETE" -> parseZoneDeletePayload(cleanText)
                    "CHAT" -> cleanText.removePrefix("CHAT|")
                    else -> cleanText
                }

                val message = TacticalMessage(
                    operation_id = operationId, // Use the passed ID
                    mission_id = missionId,     // Use the passed ID
                    timestamp = timestamp,
                    category = category,
                    source_id = senderId,
                    destination_id = receiverId,
                    latitude = latitude,
                    longitude = longitude,
                    payload = finalPayload
                )

                val jsonString = json.encodeToString(message)
                sendViaSocket(jsonString)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to process tactical message", e)
            }
        }
    }

    private fun getCategory(text: String): String {
        return when {
            text.startsWith("TRK", ignoreCase = true) -> "POSITION"
            text.startsWith("ZONE", ignoreCase = true) -> "ZONE_ADD"
            text.startsWith("DELZONE", ignoreCase = true) -> "ZONE_DELETE"
            text.startsWith("CHAT", ignoreCase = true) -> "CHAT"
            else -> "CHAT"
        }
    }

    private fun parseZonePayload(text: String): String {
        val parts = text.split("|")
        return try {
            val label = parts.getOrElse(1) { "Unknown" }
            val radius = parts.getOrElse(4) { "0" }.toInt()
            val zoneData = ZonePayload("DANGER", radius, label)
            json.encodeToString(zoneData)
        } catch (e: Exception) { "{}" }
    }

    private fun parseZoneDeletePayload(text: String): String {
        val parts = text.split("|")
        return try {
            val id = parts.getOrElse(1) { "Unknown" }
            val deleteData = ZoneDeletePayload(zone_id = id)
            json.encodeToString(deleteData)
        } catch (e: Exception) { "{}" }
    }

    private fun sendViaSocket(data: String) {
        Log.d(TAG, "Generated JSON: $data")
        try {
            val socket = Socket(SERVER_IP, SERVER_PORT)
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)
            writer.println(data)

            // --- ADD THIS LINE ---
            writer.flush()
            // ---------------------

            // --- THE FIX: Wait 100ms for data to travel ---
            Thread.sleep(100)
            // -

            writer.close()
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
        }
    }

    private fun getCurrentIsoTimestamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }
}