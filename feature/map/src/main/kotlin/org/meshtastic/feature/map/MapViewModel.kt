package org.meshtastic.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.meshtastic.core.data.repository.NodeRepository
import org.meshtastic.core.database.model.Node
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.ServiceRepository
import org.meshtastic.proto.Portnums
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon
import java.util.UUID
import javax.inject.Inject

import kotlinx.coroutines.flow.combine

import org.meshtastic.proto.MeshProtos
import kotlinx.coroutines.flow.combine // Fixes the combine function

data class MapZone(
    val id: String,
    val center: GeoPoint,
    val radius: Float,
    val color: Int,
    val polygonPoints: List<GeoPoint>
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
    private val serviceRepository: ServiceRepository
) : ViewModel() {

    companion object {
        // 1. EXISTING: Local zones (created by me)
        private val _staticLocalZones = MutableStateFlow<List<MapZone>>(emptyList())

        // 2. NEW CHANGE: Move Incoming Zones here too!
        // This makes them static, so they survive screen rotation and tab switching.
        private val _staticIncomingZones = MutableStateFlow<List<MapZone>>(emptyList())

        // 1. NEW: Store the "Text Hack" locations here (NodeID -> Position)
        private val _staticLocationOverrides = MutableStateFlow<Map<Int, GeoPoint>>(emptyMap())

        var staticCenter: GeoPoint? = null
        var staticZoom: Double = 15.0
    }

    val ourNodeInfo: StateFlow<Node?> = nodeRepository.ourNodeInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isConnected: StateFlow<Boolean> = flowOf(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 2. UPDATED: Merge Database Nodes with our Text Overrides
    val nodesWithPosition: StateFlow<List<Node>> = combine(
        nodeRepository.nodeDBbyNum,
        _staticLocationOverrides
    ) { nodeMap, overrides ->
        nodeMap.values.map { node ->
            val overridePos = overrides[node.num]

            // LOG 3: Check if we found a match
            if (overridePos != null) {
                timber.log.Timber.e("DEBUG_TRK: MATCH FOUND for node ${node.num}! Moving to ${overridePos.latitude}")

                val newPos = MeshProtos.Position.newBuilder()
                    .setLatitudeI((overridePos.latitude * 1e7).toInt())
                    .setLongitudeI((overridePos.longitude * 1e7).toInt())
                    .build()

                node.copy(position = newPos)
            } else {
                // timber.log.Timber.e("DEBUG_TRK: No override for node ${node.num}")
                node
            }
        }.filter { node ->
            val lat = node.position?.latitudeI ?: 0
            val lon = node.position?.longitudeI ?: 0
            val hasPosition = lat != 0 && lon != 0
            val isNotMe = node.num != (ourNodeInfo.value?.num ?: 0)
            hasPosition && isNotMe
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localZones = _staticLocalZones.asStateFlow()

    private val _incomingZones = MutableStateFlow<List<MapZone>>(emptyList())
    // 3. UPDATE THIS LINE: Link to the static variable
    val incomingZones = _staticIncomingZones.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            serviceRepository.meshPacketFlow.collect { packet ->
                try {
                    if (packet.decoded.portnum.number == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE) {
                        val text = packet.decoded.payload.toStringUtf8()

                        // 1. Zones
                        if (text.startsWith("ZONE|")) {
                            parseAndAddZone(text)
                        }
                        else if (text.startsWith("DELZONE|")) {
                            parseAndRemoveZone(text)
                        }
                        // 3. NEW: Handle Location Text Hack
                        else if (text.startsWith("TRK|")) {
                            parseAndMoveNode(packet.from, text) // Pass Sender ID and Text
                        }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    // --- Actions ---

    fun saveMapState(center: GeoPoint, zoom: Double) {
        staticCenter = center
        staticZoom = zoom
    }

    fun getSavedCenter(): GeoPoint? = staticCenter
    fun getSavedZoom(): Double = staticZoom

    fun addLocalZone(zone: MapZone) {
        val current = _staticLocalZones.value.toMutableList()
        current.add(zone)
        _staticLocalZones.value = current
    }

    fun removeLocalZone(zone: MapZone) {
        val current = _staticLocalZones.value.toMutableList()
        current.remove(zone)
        _staticLocalZones.value = current
    }

    // --- Message Parsing ---

    private fun parseAndAddZone(text: String) {
        try {
            val parts = text.split("|")
            // New Format: ZONE | ID | lat | lon | rad | type
            if (parts.size >= 6) {
                val id = parts[1] // Use the ID sent by the other phone
                val lat = parts[2].toDouble()
                val lon = parts[3].toDouble()
                val rad = parts[4].toFloat()
                val type = parts[5].toInt()

                val color = when (type) {
                    1 -> android.graphics.Color.argb(100, 255, 0, 0)
                    2 -> android.graphics.Color.argb(100, 255, 255, 0)
                    else -> android.graphics.Color.argb(100, 0, 255, 0)
                }

                val center = GeoPoint(lat, lon)
                val points = Polygon.pointsAsCircle(center, rad.toDouble())
                val newZone = MapZone(id, center, rad, color, points)

                // Update List
                // 4. UPDATE THIS BLOCK: Use the STATIC variable
                val currentList = _staticIncomingZones.value.toMutableList()
                // Prevent duplicates if radio sends same packet twice
                currentList.removeAll { it.id == id }
                currentList.add(newZone)

                _staticIncomingZones.value = currentList
            }
        } catch (e: Exception) { }
    }

    private fun parseAndRemoveZone(text: String) {
        try {
            // Format: DELZONE | ID
            val parts = text.split("|")
            if (parts.size >= 2) {
                val idToDelete = parts[1]

                val currentList = _staticIncomingZones.value.toMutableList()
                val wasRemoved = currentList.removeAll { it.id == idToDelete }

                if (wasRemoved) {
                    _staticIncomingZones.value = currentList
                }
            }
        } catch (e: Exception) { }
    }



    private fun parseAndMoveNode(senderId: Int, text: String) {
        try {
            // LOG 1: Did we even catch the message?
            timber.log.Timber.e("DEBUG_TRK: Packet received from ID: $senderId. Text: $text")

            val parts = text.split("|")
            if (parts.size >= 3) {
                val lat = parts[1].toDouble()
                val lon = parts[2].toDouble()

                val newPoint = GeoPoint(lat, lon)

                val currentMap = _staticLocationOverrides.value.toMutableMap()
                currentMap[senderId] = newPoint
                _staticLocationOverrides.value = currentMap

                // LOG 2: Did we save it to the map?
                timber.log.Timber.e("DEBUG_TRK: SAVED override for ID $senderId at $lat, $lon")
            } else {
                timber.log.Timber.e("DEBUG_TRK: Parsing Failed. Not enough parts.")
            }
        } catch (e: Exception) {
            timber.log.Timber.e("DEBUG_TRK: Error - ${e.message}")
        }
    }

    // --- Sending Commands ---

    fun sendZone(zone: MapZone, colorInt: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val service = serviceRepository.meshService ?: return@launch
            if (ourNodeInfo.value == null) return@launch

            val type = when (colorInt) {
                android.graphics.Color.argb(100, 255, 0, 0) -> 1
                android.graphics.Color.argb(100, 255, 255, 0) -> 2
                else -> 3
            }

            // NEW FORMAT: Includes ID
            val message = "ZONE|${zone.id}|${zone.center.latitude}|${zone.center.longitude}|${zone.radius.toInt()}|$type"
            val packet = DataPacket(to = "^all", channel = 0, text = message)
            try { service.send(packet) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun sendDeleteZone(zoneId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val service = serviceRepository.meshService ?: return@launch

            // NEW COMMAND: Delete this specific ID
            val message = "DELZONE|$zoneId"
            val packet = DataPacket(to = "^all", channel = 0, text = message)
            try { service.send(packet) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}