package com.example.collisionavoidancesystem

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.collisionavoidancesystem.dashboard.DashboardUI
import com.example.collisionavoidancesystem.laneKeepAssist.LaneAssistScreen
import com.example.collisionavoidancesystem.model.VehicleData
import com.example.collisionavoidancesystem.service.LocationService
import com.example.collisionavoidancesystem.service.PartnerDto
import com.example.collisionavoidancesystem.service.ServerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivityDebug"
    private lateinit var deviceId: String
    private var ringtone: Ringtone? = null
    private var sendJob: Job? = null
    private lateinit var prefs: SharedPreferences

    private val requiredPermissions: Array<String> by lazy {
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { !it }) {
            Toast.makeText(this, "Permissions required for app to work", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Missing required permissions")
        } else ensureBluetoothEnabled()
    }

    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) ensureLocationServicesEnabled()
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isLocationEnabled()) startServices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osm_prefs", MODE_PRIVATE))

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: run {
            val newId = "DEV_" + System.currentTimeMillis().toString().takeLast(6)
            prefs.edit().putString("device_id", newId).apply()
            newId
        }

        Log.d(TAG, "Device ID: $deviceId")

        // Permissions
        if (requiredPermissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            permissionLauncher.launch(requiredPermissions)
        } else ensureBluetoothEnabled()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var myVehicle by remember { mutableStateOf<VehicleData?>(null) }
                var partners by remember { mutableStateOf(listOf<PartnerDto>()) }
                var currentScreen by remember { mutableStateOf("dashboard") }

                // Initialize server connection
                LaunchedEffect(Unit) {
                    ServerService.init(this@MainActivity, "http://192.168.31.189:8000")
                    Log.d(TAG, "Initializing ServerService...")

                    lifecycleScope.launch {
                        ServerService.partnersFlow.collectLatest { list ->
                            partners = list
                            Log.d(TAG, "Received partners: ${list.map { it.id to it.warning }}")

                            if (list.any { it.warning == "COLLISION_RISK" }) playWarningTone()
                            else stopWarningTone()
                        }
                    }
                }

                // Observe location
                LocationService.myVehicle.observe(this@MainActivity) { v ->
                    myVehicle = v
                    Log.d(TAG, "My location update: lat=${v?.latitude}, lon=${v?.longitude}, speed=${v?.speed}")
                    v?.let { startSendingTelemetryPeriodically(it) }
                }

                // --- UI ---
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (myVehicle == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        when (currentScreen) {
                            "dashboard" -> {
                                DashboardUI(
                                    context = this@MainActivity,
                                    myVehicle = myVehicle!!,
                                    partners = partners,
                                    onRefresh = {},
                                    deviceId = deviceId,
                                    onOpenLaneAssist = { currentScreen = "laneAssist" }
                                )
                            }

                            "laneAssist" -> {
                                LaneAssistScreen(
                                    partners = partners,
                                    onNavigateBack = { currentScreen = "dashboard" }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Services and Permissions ---

    private fun startServices() {
        Log.d(TAG, "Starting LocationService...")
        LocationService.start(this, deviceId)
    }

    private fun startSendingTelemetryPeriodically(vehicle: VehicleData) {
        sendJob?.cancel()
        sendJob = lifecycleScope.launch {
            while (true) {
                ServerService.sendMyData(vehicle.copy(id = deviceId, timestamp = System.currentTimeMillis()))
                delay(1000L)
            }
        }
    }

    private fun ensureBluetoothEnabled() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) return

            if (!adapter.isEnabled) {
                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else ensureLocationServicesEnabled()
        } else ensureLocationServicesEnabled()
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun ensureLocationServicesEnabled() {
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services disabled")
            locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else startServices()
    }

    private fun playWarningTone() {
        try {
            if (ringtone == null) {
                val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            }
            if (ringtone?.isPlaying == false) ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopWarningTone() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sendJob?.cancel()
        ServerService.shutdown()
    }
}
