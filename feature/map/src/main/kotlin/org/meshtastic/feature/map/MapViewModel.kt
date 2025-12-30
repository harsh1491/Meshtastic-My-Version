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

    // --- STATIC MEMORY (Survives Tab Switching) ---
    companion object {
        // These live as long as the App is open. They never die on navigation.
        private val _staticLocalZones = MutableStateFlow<List<MapZone>>(emptyList())

        // We also save the camera statically so it doesn't reset if VM dies
        var staticCenter: GeoPoint? = null
        var staticZoom: Double = 15.0
    }
    // ----------------------------------------------

    val ourNodeInfo: StateFlow<Node?> = nodeRepository.ourNodeInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isConnected: StateFlow<Boolean> = flowOf(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val nodesWithPosition: StateFlow<List<Node>> = nodeRepository.nodeDBbyNum
        .map { nodeMap ->
            nodeMap.values.filter { node ->
                val lat = node.position?.latitudeI ?: 0
                val lon = node.position?.longitudeI ?: 0
                val hasPosition = lat != 0 && lon != 0
                val isNotMe = node.num != (ourNodeInfo.value?.num ?: 0)
                hasPosition && isNotMe
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Use the STATIC list for zones
    val localZones = _staticLocalZones.asStateFlow()

    private val _incomingZones = MutableStateFlow<List<MapZone>>(emptyList())
    val incomingZones = _incomingZones.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            serviceRepository.meshPacketFlow.collect { packet ->
                try {
                    if (packet.decoded.portnum.number == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE) {
                        val text = packet.decoded.payload.toStringUtf8()
                        if (text.startsWith("ZONE|")) parseAndAddZone(text)
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

    // Helper to get saved state for the UI
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

    private fun parseAndAddZone(text: String) {
        try {
            val parts = text.split("|")
            if (parts.size >= 5) {
                val lat = parts[1].toDouble()
                val lon = parts[2].toDouble()
                val rad = parts[3].toFloat()
                val type = parts[4].toInt()
                val color = when (type) {
                    1 -> android.graphics.Color.argb(100, 255, 0, 0)
                    2 -> android.graphics.Color.argb(100, 255, 255, 0)
                    else -> android.graphics.Color.argb(100, 0, 255, 0)
                }
                val center = GeoPoint(lat, lon)
                val points = Polygon.pointsAsCircle(center, rad.toDouble())
                val newZone = MapZone(UUID.randomUUID().toString(), center, rad, color, points)
                val currentList = _incomingZones.value.toMutableList()
                currentList.add(newZone)
                _incomingZones.value = currentList
            }
        } catch (e: Exception) { }
    }

    fun sendZone(centerLat: Double, centerLon: Double, radius: Float, colorInt: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val service = serviceRepository.meshService ?: return@launch
            if (ourNodeInfo.value == null) return@launch

            val type = when (colorInt) {
                android.graphics.Color.argb(100, 255, 0, 0) -> 1
                android.graphics.Color.argb(100, 255, 255, 0) -> 2
                else -> 3
            }
            val message = "ZONE|$centerLat|$centerLon|${radius.toInt()}|$type"
            val packet = DataPacket(to = "^all", channel = 0, text = message)
            try { service.send(packet) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}