package org.meshtastic.feature.map

import org.meshtastic.core.ui.TacticalIconManager // Import your new Manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.preference.PreferenceManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.meshtastic.core.database.model.Node
import org.meshtastic.feature.map.overlays.CircleDrawingOverlay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
// If R is red, import your app's R file here manually:
// import com.geeksville.mesh.R
// OR import org.meshtastic.core.ui.R

@Composable
fun MapView(
    isDrawingMode: Boolean,
    isDeleteMode: Boolean,
    zones: List<MapZone>,
    networkZones: List<MapZone>,
    nodes: List<Node>,
    triggerCenterLocation: Int,
    hasLocationPermission: Boolean,
    initialCenter: GeoPoint?,
    initialZoom: Double,
    onMapMoved: (GeoPoint, Double) -> Unit,
    onCircleFinished: (GeoPoint, Float) -> Unit,
    onZoneClick: (MapZone) -> Unit
) {
    val context = LocalContext.current
    Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))

    val drawOverlay = remember {
        CircleDrawingOverlay { center, radius -> onCircleFinished(center, radius) }
    }

    // --- ICONS CONFIGURATION ---

    // 1. My Icon: Find "Me" by looking for the node with 0 hops
    val myIconBitmap = remember(nodes) {
        // Since you are testing alone, we look for the node that has one of our tactical emojis.
        // This acts as a "Name Match" to find yourself.
        val myNode = nodes.find {
            val name = it.user.longName
            name.contains("🛡️") || name.contains("🚁") || name.contains("✈️")
        }

        // If we found a node with an emoji, use its icon. Otherwise, default to Soldier.
        val myName = myNode?.user?.longName
        val myIconId = TacticalIconManager.getMarkerDrawable(myName)

        // Debug: If you still see a Soldier, you can uncomment the line below to FORCE a Tank:
        // val myIconId = org.meshtastic.core.ui.R.drawable.marker_tank

        createMarkerBitmap(context, myIconId, null)
    }

    // ---------------------------

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)

                if (initialCenter != null) {
                    controller.setCenter(initialCenter)
                    controller.setZoom(initialZoom)
                } else {
                    controller.setZoom(15.0)
                }

                addMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        val center = mapCenter as? GeoPoint
                        if (center != null) onMapMoved(center, zoomLevelDouble)
                        return true
                    }
                    override fun onZoom(event: ZoomEvent?): Boolean {
                        val center = mapCenter as? GeoPoint
                        if (center != null) onMapMoved(center, zoomLevelDouble)
                        return true
                    }
                })

                // --- SETUP "MY LOCATION" ---
                val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)

                if (myIconBitmap != null) {
                    locationOverlay.setPersonIcon(myIconBitmap)
                    locationOverlay.setDirectionIcon(myIconBitmap)
                }

                locationOverlay.enableFollowLocation()
                overlays.add(locationOverlay)

                val mbtilesFile = File(ctx.getExternalFilesDir(null), "offline.mbtiles")
                if (mbtilesFile.exists()) {
                    try {
                        val provider = OfflineTileProvider(SimpleRegisterReceiver(ctx), arrayOf(mbtilesFile))
                        setTileProvider(provider)
                        setTileSource(XYTileSource("mbtiles", 0, 22, 256, ".png", arrayOf("http://placeholder.org")))
                        setUseDataConnection(false)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        },
        update = { mapView ->
            val locationOverlay = mapView.overlays.firstOrNull { it is MyLocationNewOverlay } as? MyLocationNewOverlay
            if (hasLocationPermission && locationOverlay != null && !locationOverlay.isMyLocationEnabled) {
                locationOverlay.enableMyLocation()
            }
            if (triggerCenterLocation > 0 && hasLocationPermission && locationOverlay?.myLocation != null) {
                mapView.controller.animateTo(locationOverlay.myLocation)
                mapView.controller.setZoom(18.0)
            }

            if (isDrawingMode) {
                if (!mapView.overlays.contains(drawOverlay)) mapView.overlays.add(drawOverlay)
            } else {
                mapView.overlays.remove(drawOverlay)
            }

            val polygonsToRemove = mapView.overlays.filterIsInstance<Polygon>()
            mapView.overlays.removeAll(polygonsToRemove)

            zones.forEach { zone ->
                val polygon = Polygon().apply {
                    points = zone.polygonPoints
                    fillPaint.color = zone.color
                    outlinePaint.color = android.graphics.Color.parseColor("#80444444")
                    outlinePaint.strokeWidth = 3f
                    title = "My Zone"
                    setOnClickListener { _, _, _ ->
                        if (isDeleteMode) {
                            onZoneClick(zone)
                            return@setOnClickListener true
                        }
                        return@setOnClickListener false
                    }
                }
                mapView.overlays.add(polygon)
            }

            networkZones.forEach { zone ->
                val polygon = Polygon().apply {
                    points = zone.polygonPoints
                    fillPaint.color = zone.color
                    outlinePaint.color = android.graphics.Color.BLUE
                    outlinePaint.strokeWidth = 5f
                    title = "Team Zone"
                }
                mapView.overlays.add(polygon)
            }

            // --- SETUP OTHER NODES ---
            val markersToRemove = mapView.overlays.filterIsInstance<Marker>()
            mapView.overlays.removeAll(markersToRemove)

            nodes.forEach { node ->
                if (node.position != null) {
                    val lat = node.position.latitudeI * 1e-7
                    val lon = node.position.longitudeI * 1e-7
                    if (lat != 0.0 && lon != 0.0) {

                        // 1. DECIDE: Which icon does this specific node need? (Tank? Heli?)
                        val markerResId = TacticalIconManager.getMarkerDrawable(node.user?.longName)

                        // 2. CLEAN: Get the name without the emoji code
                        val cleanName = TacticalIconManager.getCleanName(node.user?.longName)

                        val marker = Marker(mapView).apply {
                            position = GeoPoint(lat, lon)
                            title = node.user?.shortName ?: "Unknown"
                            snippet = cleanName // Show the clean name in the bubble

                            // 3. CREATE: Generate the bitmap dynamically for THIS node
                            // We pass the specific 'markerResId' we found above
                            val nodeBitmap = createMarkerBitmap(context, markerResId, android.graphics.Color.BLUE)

                            if (nodeBitmap != null) {
                                icon = BitmapDrawable(context.resources, nodeBitmap)
                            }

                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(marker)
                    }
                }
            }
            mapView.invalidate()
        }
    )
}

// --- HELPER FUNCTION ---
// Renamed to be more generic. It takes a specific Resource ID now.
fun createMarkerBitmap(context: Context, markerResId: Int, baseColor: Int?): Bitmap? {
    try {
        // Load the specific icon requested (Tank, Heli, etc.)
        val drawable = ContextCompat.getDrawable(context, markerResId) ?: return null

        val width = 150
        val height = 150

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Only draw the blue dot if a color was provided (for teammates)
        if (baseColor != null) {
            val paint = Paint()
            paint.color = baseColor
            paint.style = Paint.Style.FILL
            paint.isAntiAlias = true
            canvas.drawCircle(width / 2f, height - 20f, 20f, paint)
        }

        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)

        return bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}