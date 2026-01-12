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
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import org.meshtastic.core.model.DataPacket // Crucial for sending

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val model: UIViewModel by viewModels()

    // This is aware of the Activity lifecycle and handles binding to the mesh service.
    @Inject internal lateinit var meshServiceClient: MeshServiceClient

    @Inject internal lateinit var uiPreferencesDataSource: UiPreferencesDataSource


    // =========================================================================
    // NEW CODE: Location Hack Variables
    // =========================================================================
    private val locationHandler = Handler(Looper.getMainLooper())
    private val locationRunnable = object : Runnable {
        override fun run() {
            // FIX: Launch in a background thread (IO) so the UI doesn't freeze
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
            // Disable three-button navbar scrim on pre-Q devices
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Disable three-button navbar scrim
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
    // NEW CODE: Lifecycle Methods to Start/Stop the Loop
    // =========================================================================
    override fun onResume() {
        super.onResume()
        // Start sending location when app opens
        locationHandler.post(locationRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Stop sending when app is minimized to save battery/network
        locationHandler.removeCallbacks(locationRunnable)
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
    // NEW CODE: The Location Sender Logic
    // =========================================================================
    private fun sendCustomLocation() {
        try {
            // 1. SAFETY: Check if radio is actually connected
            val isConnected = try {
                meshServiceClient.service?.connectionState() != "DISCONNECTED"
            } catch (e: Exception) { false }

            if (!isConnected) return

            // 2. PERMISSION: Check if we are allowed to read GPS
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // If no permission, we simply do nothing. The Map Screen handles requesting it.
                return
            }

            // 3. GET LOCATION: Try GPS first, fall back to Network
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (loc != null) {
                // 4. FORMAT: "TRK|LAT|LON"
                val payloadStr = "TRK|${"%.5f".format(loc.latitude)}|${"%.5f".format(loc.longitude)}"
                val payloadBytes = payloadStr.toByteArray()

                // 5. SEND: Create Packet (DataType 1 = Text)
                // Using the constructor: DataPacket(to, bytes, dataType) OR DataPacket(to, channel, bytes, dataType)
                // We use Channel 0 (Primary)
                val packet = DataPacket(
                    "^all", // Send to everyone
                    payloadBytes,
                    1 // DataType 1 = CLEAR_TEXT / TEXT_MESSAGE_APP
                )

                meshServiceClient.service?.send(packet)
                Timber.d("LocationHack: Sent -> $payloadStr")
            }
        } catch (e: Exception) {
            Timber.e(e, "LocationHack: Failed to send")
        }
    }


}
