/*
 * Copyright (c) 2025 Meshtastic LLC
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

package com.geeksville.mesh

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.ui.MainScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.meshtastic.core.datastore.UiPreferencesDataSource
import org.meshtastic.core.navigation.DEEP_LINK_BASE_URI
import org.meshtastic.core.strings.Res
import org.meshtastic.core.strings.channel_invalid
import org.meshtastic.core.strings.contact_invalid
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.core.ui.theme.MODE_DYNAMIC
import org.meshtastic.core.ui.util.showToast
import org.meshtastic.feature.intro.AppIntroductionScreen
import timber.log.Timber
import javax.inject.Inject

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener // NEW IMPORT
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers // NEW IMPORT
import org.meshtastic.core.model.DataPacket

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val model: UIViewModel by viewModels()

    @Inject internal lateinit var meshServiceClient: MeshServiceClient
    @Inject internal lateinit var uiPreferencesDataSource: UiPreferencesDataSource

    // =========================================================================
    // NEW CODE: Live Location Variables
    // =========================================================================
    // 1. Store the latest FRESH location here
    private var currentLocation: Location? = null

    // 2. Define the Listener to update that variable
    private val locationListener = LocationListener { location ->
        currentLocation = location
        // Timber.d("LocationHack: GPS Update Received -> ${location.latitude}, ${location.longitude}")
    }

    private val locationHandler = Handler(Looper.getMainLooper())
    private val locationRunnable = object : Runnable {
        override fun run() {
            // Run in background to avoid blocking UI
            lifecycleScope.launch(Dispatchers.IO) {
                sendCustomLocation()
            }
            // Repeat every 30 seconds
            locationHandler.postDelayed(this, 30000)
        }
    }
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false)
        }

        super.onCreate(savedInstanceState)

        setContent {
            val theme by model.theme.collectAsState()
            val dynamic = theme == MODE_DYNAMIC
            val dark =
                when (theme) {
                    AppCompatDelegate.MODE_NIGHT_YES -> true
                    AppCompatDelegate.MODE_NIGHT_NO -> false
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> isSystemInDarkTheme()
                    else -> isSystemInDarkTheme()
                }

            AppTheme(dynamicColor = dynamic, darkTheme = dark) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect { AppCompatDelegate.setDefaultNightMode(theme) }
                }

                val appIntroCompleted by model.appIntroCompleted.collectAsStateWithLifecycle()
                if (appIntroCompleted) {
                    MainScreen(uIViewModel = model)
                } else {
                    AppIntroductionScreen(onDone = { model.onAppIntroCompleted() })
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // =========================================================================
    // NEW CODE: Lifecycle Methods
    // =========================================================================
    override fun onResume() {
        super.onResume()
        startLocationUpdates() // Start listening for GPS changes
        locationHandler.post(locationRunnable) // Start the 30s sending timer
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates() // Stop listening to save battery
        locationHandler.removeCallbacks(locationRunnable)
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            // Request updates every 5 seconds or 5 meters
            // This forces the GPS chip to wake up and give us fresh data
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, locationListener)

            // Also try Network provider as backup
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, locationListener)
        } catch (e: Exception) {
            Timber.e(e, "LocationHack: Failed to request updates")
        }
    }

    private fun stopLocationUpdates() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.removeUpdates(locationListener)
    }
    // =========================================================================

    private fun handleIntent(intent: Intent) {
        val appLinkAction = intent.action
        val appLinkData: Uri? = intent.data

        when (appLinkAction) {
            Intent.ACTION_VIEW -> {
                appLinkData?.let {
                    Timber.d("App link data: $it")
                    if (it.path?.startsWith("/e/") == true || it.path?.startsWith("/E/") == true) {
                        Timber.d("App link data is a channel set")
                        model.requestChannelUrl(
                            url = it,
                            onFailure = { lifecycleScope.launch { showToast(Res.string.channel_invalid) } },
                        )
                    } else if (it.path?.startsWith("/v/") == true || it.path?.startsWith("/V/") == true) {
                        Timber.d("App link data is a shared contact")
                        model.setSharedContactRequested(
                            url = it,
                            onFailure = { lifecycleScope.launch { showToast(Res.string.contact_invalid) } },
                        )
                    } else {
                        Timber.d("App link data is not a channel set")
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Timber.d("USB device attached")
                showSettingsPage()
            }

            Intent.ACTION_MAIN -> {}

            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text != null) {
                    createShareIntent(text).send()
                }
            }

            else -> {
                Timber.w("Unexpected action $appLinkAction")
            }
        }
    }

    private fun createShareIntent(message: String): PendingIntent {
        val deepLink = "$DEEP_LINK_BASE_URI/share?message=$message"
        val startActivityIntent =
            Intent(Intent.ACTION_VIEW, deepLink.toUri(), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val resultPendingIntent: PendingIntent? =
            TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(startActivityIntent)
                getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
            }
        return resultPendingIntent!!
    }

    private fun createSettingsIntent(): PendingIntent {
        val deepLink = "$DEEP_LINK_BASE_URI/connections"
        val startActivityIntent =
            Intent(Intent.ACTION_VIEW, deepLink.toUri(), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val resultPendingIntent: PendingIntent? =
            TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(startActivityIntent)
                getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
            }
        return resultPendingIntent!!
    }

    private fun showSettingsPage() {
        createSettingsIntent().send()
    }

    // =========================================================================
    // NEW CODE: The Sender Logic (Using Fresh Variable)
    // =========================================================================
    private fun sendCustomLocation() {
        try {
            val isConnected = try {
                meshServiceClient.service?.connectionState() != "DISCONNECTED"
            } catch (e: Exception) { false }

            if (!isConnected) return

            // USE THE LIVE VARIABLE INSTEAD OF getLastKnownLocation
            val loc = currentLocation

            if (loc != null) {
                // Format: "TRK|LAT|LON"
                val payloadStr = "TRK|${"%.5f".format(loc.latitude)}|${"%.5f".format(loc.longitude)}"
                val payloadBytes = payloadStr.toByteArray()

                // Send to "^all" (Broadcast)
                val packet = DataPacket(
                    "^all",
                    payloadBytes,
                    1 // DataType 1 = TEXT_MESSAGE_APP
                )

                meshServiceClient.service?.send(packet)
                Timber.d("LocationHack: Sent -> $payloadStr")
            }
        } catch (e: Exception) {
            Timber.e(e, "LocationHack: Failed to send")
        }
    }
}