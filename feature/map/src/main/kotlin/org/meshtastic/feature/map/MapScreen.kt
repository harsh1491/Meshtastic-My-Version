package org.meshtastic.feature.map

import android.Manifest
//import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.strings.Res
import org.meshtastic.core.strings.map
import org.jetbrains.compose.resources.stringResource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon
import java.util.UUID

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

@Composable
fun MapScreen(
    onClickNodeChip: (Int) -> Unit,
    navigateToNodeDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mapViewModel: MapViewModel = hiltViewModel(),
) {
    val ourNodeInfo by mapViewModel.ourNodeInfo.collectAsStateWithLifecycle()
    val isConnected by mapViewModel.isConnected.collectAsStateWithLifecycle()
    // --- NEW: Get the list of friends ---
    val nodes by mapViewModel.nodesWithPosition.collectAsStateWithLifecycle()

    var isDrawingMode by remember { mutableStateOf(false) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var triggerCenterLocation by remember { mutableStateOf(0) }
    var hasLocationPermission by remember { mutableStateOf(false) }

    val zones = remember { mutableStateListOf<MapZone>() }
    var showColorDialog by remember { mutableStateOf(false) }
    var tempCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var tempRadius by remember { mutableStateOf(0f) }
    var showDeleteConfirmDialog by remember { mutableStateOf<MapZone?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Suppress("ViewModelForwarding")
    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.map),
                ourNode = ourNodeInfo,
                showNodeChip = ourNodeInfo != null && isConnected,
                canNavigateUp = false,
                onNavigateUp = {},
                actions = {},
                onClickChip = { onClickNodeChip(it.num) },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = {
                        if (hasLocationPermission) triggerCenterLocation++
                        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) { Icon(Icons.Default.MyLocation, contentDescription = "My Location") }

                FloatingActionButton(
                    onClick = {
                        isDeleteMode = !isDeleteMode
                        if(isDeleteMode) isDrawingMode = false
                    },
                    containerColor = if (isDeleteMode) androidx.compose.ui.graphics.Color.Red else ButtonDefaults.buttonColors().containerColor,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) { Icon(Icons.Default.Delete, contentDescription = "Delete Zones") }

                FloatingActionButton(
                    onClick = {
                        isDrawingMode = !isDrawingMode
                        if(isDrawingMode) isDeleteMode = false
                    }
                ) {
                    if (isDrawingMode) Icon(Icons.Default.Close, contentDescription = "Stop Drawing")
                    else Icon(Icons.Default.Edit, contentDescription = "Start Drawing")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            MapView(
                isDrawingMode = isDrawingMode,
                isDeleteMode = isDeleteMode,
                zones = zones,
                nodes = nodes, // <--- PASS THE NODES HERE
                triggerCenterLocation = triggerCenterLocation,
                hasLocationPermission = hasLocationPermission,
                onCircleFinished = { center, radius ->
                    tempCenter = center
                    tempRadius = radius
                    showColorDialog = true
                },
                onZoneClick = { zoneClicked ->
                    showDeleteConfirmDialog = zoneClicked
                }
            )

            // --- PASTE THIS DEBUG BOX BELOW MapView ---
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.White.copy(alpha = 0.8f))
                    .padding(4.dp)
            ) {
                Text("Total Nodes: ${nodes.size}", color = Color.Black)

                // Show info for the first 3 nodes found
                nodes.take(3).forEach { node ->
                    // Try to read position from both possible places
                    val posObj = node.position
                    val lat = posObj?.latitudeI ?: 0
                    val lon = posObj?.longitudeI ?: 0

                    Text(
                        text = "${node.user?.shortName}: $lat / $lon",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = if (lat != 0) Color.Blue else Color.Red
                    )
                }
            }
            // ------------------------------------------
        }
    }

    // ... (Keep the Color Dialog and Delete Dialog code same as before) ...
    if (showColorDialog && tempCenter != null) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("Select Zone Type") },
            text = {
                Column {
                    val addZone = { color: Int ->
                        val circlePoints = Polygon.pointsAsCircle(tempCenter, tempRadius.toDouble())
                        zones.add(MapZone(UUID.randomUUID().toString(), tempCenter!!, tempRadius, color, circlePoints))
                        showColorDialog = false
                        isDrawingMode = false
                    }
                    // FIX: Use 'android.graphics.Color.argb' explicitly here
                    Button(onClick = { addZone(android.graphics.Color.argb(100, 255, 0, 0)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.padding(vertical = 4.dp)) { Text("Red (Danger)") }

                    Button(onClick = { addZone(android.graphics.Color.argb(100, 255, 255, 0)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow),
                        modifier = Modifier.padding(vertical = 4.dp)) { Text("Yellow (Caution)", color = Color.Black) }

                    Button(onClick = { addZone(android.graphics.Color.argb(100, 0, 255, 0)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green),
                        modifier = Modifier.padding(vertical = 4.dp)) { Text("Green (Safe)", color = Color.Black) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showColorDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Zone") },
            text = { Text("Are you sure you want to delete this zone?") },
            confirmButton = {
                Button(onClick = {
                    zones.remove(showDeleteConfirmDialog)
                    showDeleteConfirmDialog = null
                }) { Text("Yes, Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmDialog = null }) { Text("No") } }
        )
    }
}