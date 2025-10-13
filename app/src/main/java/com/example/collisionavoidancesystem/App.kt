package com.example.collisionavoidancesystem

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlin.random.Random
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.collisionavoidancesystem.model.VehicleData
import com.example.collisionavoidancesystem.service.LocationService
import com.example.collisionavoidancesystem.service.ServerService
import com.example.collisionavoidancesystem.service.PartnerDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.*

// OSM Droid Imports (Assuming external dependency setup is complete)
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// ================== MODELS (Unchanged) ==================
data class PositionData(
    val id: String,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float,
    val headingDeg: Float,
    val timestamp: Long
)

enum class WarningType { PROXIMITY, COLLISION, SPEED_LIMIT }

data class Warning(
    val type: WarningType,
    val vehicleId: String,
    val distanceMeters: Float
)

data class LaneDetectionResult(
    val lanesDetected: Boolean,
    val numLines: Int,
    val deviation: Float,
    val confidence: Float
)

// ================== VM (Reworked to use real services) ==================
class AutoNavViewModel : ViewModel() {

    // --- REAL DATA FLOWS ---
    private val _myVehicle = MutableStateFlow<VehicleData?>(null)
    val myVehicle: StateFlow<VehicleData?> = _myVehicle.asStateFlow()
    val partners: StateFlow<List<PartnerDto>> = ServerService.partnersFlow

    // --- DERIVED STATE ---
    private val _overallRiskLevel = MutableStateFlow("SAFE")
    val overallRiskLevel: StateFlow<String> = _overallRiskLevel.asStateFlow()

    // Backward compatible flows for the original UI structure
    private val _warnings = MutableStateFlow<List<Warning>>(emptyList())
    val warnings: StateFlow<List<Warning>> = _warnings.asStateFlow()
    val positions: StateFlow<List<PositionData>> = partners.map { partnerList ->
        partnerList.map { p ->
            PositionData(
                id = p.id,
                lat = p.latitude,
                lon = p.longitude,
                speedKmh = p.speed * 3.6f, // Convert m/s to km/h for compatibility
                headingDeg = p.heading,
                timestamp = p.timestamp
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    // --- CONFIGURATION FLOWS (Unchanged) ---
    private val _safeDistanceMeters = MutableStateFlow(10f)
    val safeDistanceMeters: StateFlow<Float> = _safeDistanceMeters.asStateFlow()
    private val _speedLimitEnabled = MutableStateFlow(true)
    val speedLimitEnabled: StateFlow<Boolean> = _speedLimitEnabled.asStateFlow()
    private val _customSpeedEnabled = MutableStateFlow(false)
    val customSpeedEnabled: StateFlow<Boolean> = _customSpeedEnabled.asStateFlow()
    private val _customSpeedKmh = MutableStateFlow(80f)
    val customSpeedKmh: StateFlow<Float> = _customSpeedKmh.asStateFlow()
    private val _sensitivityHigh = MutableStateFlow(false)
    val sensitivityHigh: StateFlow<Boolean> = _sensitivityHigh.asStateFlow()
    private val _backendUrl = MutableStateFlow("http://192.168.31.189:8000")
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()
    private val _laneDetectionEnabled = MutableStateFlow(false)
    val laneDetectionEnabled: StateFlow<Boolean> = _laneDetectionEnabled.asStateFlow()

    fun setup(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }

        ServerService.init(context)
        LocationService.start(context, deviceId)

        // 💡 CRITICAL FIX: LiveData observation MUST start on Dispatchers.Main.immediate
        viewModelScope.launch(Dispatchers.Main.immediate) {
            LocationService.myVehicle.asFlow() // This call is now safe
                .filterNotNull()
                .collect { vehicleData ->
                    _myVehicle.value = vehicleData

                    // Switching to Dispatchers.IO for the network call
                    launch(Dispatchers.IO) {
                        ServerService.sendMyData(vehicleData)
                    }
                }
        }

        // ❌ DELETED: The second, redundant viewModelScope.launch(Dispatchers.IO) block
        // that caused the crash has been removed.

        // 3. Observe partner data from the server and update warnings
        viewModelScope.launch {
            ServerService.partnersFlow.collect { partnerList ->
                val risk = when {
                    partnerList.any { it.warning == "COLLISION_RISK" } -> "DANGER"
                    partnerList.any { it.warning == "PROXIMITY" } -> "CAUTION"
                    else -> "SAFE"
                }
                _overallRiskLevel.value = risk

                _warnings.value = partnerList.mapNotNull { p ->
                    when (p.warning) {
                        "COLLISION_RISK" -> Warning(WarningType.COLLISION, p.id, p.distance_m.toFloat())
                        "PROXIMITY" -> Warning(WarningType.PROXIMITY, p.id, p.distance_m.toFloat())
                        else -> null
                    }
                }
            }
        }
    }

    // --- UTIL FUNCTIONS (Unchanged) ---
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun setSafeDistance(v: Float) { _safeDistanceMeters.value = v }
    fun setSpeedLimitEnabled(v: Boolean) { _speedLimitEnabled.value = v }
    fun setCustomSpeedEnabled(v: Boolean) { _customSpeedEnabled.value = v }
    fun setCustomSpeed(v: Float) { _customSpeedKmh.value = v }
    fun setSensitivityHigh(v: Boolean) { _sensitivityHigh.value = v }
    fun setBackendUrl(url: String) { _backendUrl.value = url }
    fun setLaneDetectionEnabled(enabled: Boolean) { _laneDetectionEnabled.value = enabled }

    suspend fun detectLanesRemote(imageJpeg: ByteArray): ServerService.LaneAssistResponse? {
        return ServerService.detectLanesRemote(imageJpeg)
    }
}

// Extension function to convert LiveData to StateFlow (Unchanged, relies on Main Thread fix above)
fun <T> androidx.lifecycle.LiveData<T>.asFlow(): StateFlow<T?> {
    val flow = MutableStateFlow<T?>(value)
    object : androidx.lifecycle.Observer<T> {
        override fun onChanged(t: T) {
            flow.value = t
        }
    }.also { observeForever(it) }
    return flow.asStateFlow()
}

// ================== ACTIVITY ==================
class AutoNavShareActivity : ComponentActivity() {
    private val requiredPermissions: Array<String> by lazy {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { !it }) {
            // Permissions denied; UI will remain minimal, no location updates
            setContent { AutoNavApp() }
        } else {
            setContent { AutoNavApp() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasAll = requiredPermissions.all { p ->
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasAll) permissionLauncher.launch(requiredPermissions) else setContent { AutoNavApp() }
    }
}

// ================== MAP COMPONENT ==================


// ================== NAV + UI ==================
private sealed class Screen { object Splash: Screen(); object Dashboard: Screen(); object Trip: Screen(); object Settings: Screen(); object Indoor: Screen(); object DataStream: Screen(); object DrivingLimits: Screen(); object LaneAssist: Screen() }

@Composable
private fun AutoNavApp(vm: AutoNavViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var current by remember { mutableStateOf<Screen>(Screen.Splash) }
    var showVehicleId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.setup(context)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0E1116)) {
            when (current) {
                is Screen.Splash -> SplashScreen { current = Screen.Dashboard }
                is Screen.Dashboard -> DashboardScreen(vm, onOpenTrip = { current = Screen.Trip }, onOpenSettings = { current = Screen.Settings }, onOpenIndoor = { current = Screen.Indoor }, onOpenData = { current = Screen.DataStream }, onOpenDrivingLimits = { current = Screen.DrivingLimits }, onOpenLaneAssist = { current = Screen.LaneAssist }, onVehicleSelected = { showVehicleId = it })
                is Screen.Trip -> TripHistoryScreen { current = Screen.Dashboard }
                is Screen.Settings -> SettingsScreen(vm) { current = Screen.Dashboard }
                is Screen.Indoor -> IndoorScreen { current = Screen.Dashboard }
                is Screen.DataStream -> DataStreamScreen(vm) { current = Screen.Dashboard }
                is Screen.DrivingLimits -> DrivingLimitsScreen(vm) { current = Screen.Dashboard }
                is Screen.LaneAssist -> LaneAssistScreen(vm) { current = Screen.Dashboard }
            }

            val warnings by vm.warnings.collectAsState()


            showVehicleId?.let { id ->
                VehicleDetailSheet(vm, id) { showVehicleId = null }
            }
        }
    }
}

// 1. Dashboard
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    vm: AutoNavViewModel,
    onOpenTrip: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIndoor: () -> Unit,
    onOpenData: () -> Unit,
    onOpenDrivingLimits: () -> Unit,
    onOpenLaneAssist: () -> Unit,
    onVehicleSelected: (String) -> Unit
) {
    val myVehicle by vm.myVehicle.collectAsState()
    val partners by vm.partners.collectAsState()
    val warnings by vm.warnings.collectAsState()
    val me = myVehicle

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoNavShare", fontWeight = FontWeight.Bold) },
                actions = {
                    Text("WARNINGS: ${warnings.size}", color = if (warnings.any { it.type == WarningType.COLLISION }) Color.Red else Color(0xFF00E5FF), modifier = Modifier.padding(end = 12.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF141A22)) {
                NavigationBarItem(selected = true, onClick = { }, icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Dash") })
                NavigationBarItem(selected = false, onClick = onOpenTrip, icon = { Icon(Icons.Default.List, contentDescription = null) }, label = { Text("Trips") })
                NavigationBarItem(selected = false, onClick = onOpenIndoor, icon = { Icon(Icons.Default.LocationOn, contentDescription = null) }, label = { Text("Indoor") })
                NavigationBarItem(selected = false, onClick = onOpenData, icon = { Icon(Icons.Default.Info, contentDescription = null) }, label = { Text("Data") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Map Component
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(horizontal = 16.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                shape = MaterialTheme.shapes.large
            ) {
                LiveMap(
                    context = context,
                    myVehicle = myVehicle,
                    partners = partners,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("User ID: ${me?.id ?: "Loading..."}", color = Color(0xFF9ECFFF))
                Text("Speed: ${me?.speed?.toInt() ?: 0} m/s", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.height(8.dp))

            // Lane Keep Assist Button
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { onOpenLaneAssist() },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Lane Keep Assist", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Camera-based lane detection", color = Color(0xFF9ECFFF), fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Build, contentDescription = null, tint = Color.White)
                }
            }

            // Nearby Vehicles List
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (partners.isEmpty()) {
                    item {
                        Text("No nearby vehicles detected", modifier = Modifier.padding(12.dp), color = Color.Gray)
                    }
                } else {
                    items(partners) { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onVehicleSelected(p.id) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF16202C))
                        ) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("${p.id}", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("${p.speed.format1()} m/s", color = Color.Gray)
                                }
                                Text("${p.distance_m.toInt()} m", color = Color(0xFF9ECFFF))
                            }
                        }
                    }
                }
            }
        }
    }
}

    // 2 & 3. Warning Overlay -> Stable status bar


// 4. Settings (Unchanged)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: AutoNavViewModel, onBack: () -> Unit) {
    val speedLimit by vm.speedLimitEnabled.collectAsState()
    val customEnabled by vm.customSpeedEnabled.collectAsState()
    val customSpeed by vm.customSpeedKmh.collectAsState()
    val safeDist by vm.safeDistanceMeters.collectAsState()
    val sensitive by vm.sensitivityHigh.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()
    val laneDetectionEnabled by vm.laneDetectionEnabled.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp)) {
            SettingSwitch("Share Raw GPS Data", true) {}
            SettingSwitch("Anonymous Data Collection", true) {}
            SettingSwitch("Sound Alerts", true) {}
            SettingSwitch("Vibration Feedback", true) {}
            SettingSwitch("Heads-up Notifications", true) {}
            Divider(Modifier.padding(vertical = 12.dp))

            SettingSwitch("Speed Limit Alerts", speedLimit) { vm.setSpeedLimitEnabled(it) }
            SettingSwitch("Custom Speed Warning", customEnabled) { vm.setCustomSpeedEnabled(it) }
            if (customEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom: ${customSpeed.toInt()} km/h", Modifier.weight(1f))
                    Slider(value = customSpeed, onValueChange = { vm.setCustomSpeed(it) }, valueRange = 30f..140f, modifier = Modifier.weight(2f))
                }
            }
            Divider(Modifier.padding(vertical = 12.dp))
            Text("Safe Distance: ${safeDist.toInt()} m")
            Slider(value = safeDist, onValueChange = { vm.setSafeDistance(it) }, valueRange = 5f..50f)
            SettingSwitch("Warning Sensitivity (longer horizon)", sensitive) { vm.setSensitivityHigh(it) }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("Backend Settings", fontWeight = FontWeight.Bold, color = Color.White)
            SettingSwitch("Enable Backend Lane Detection", laneDetectionEnabled) { vm.setLaneDetectionEnabled(it) }
            if (laneDetectionEnabled) {
                Text("Backend URL: $backendUrl", color = Color(0xFF9ECFFF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// 5. Vehicle Detail Sheet (Unchanged)
@Composable
private fun VehicleDetailSheet(vm: AutoNavViewModel, vehicleId: String, onDismiss: () -> Unit) {
    val myVehicle by vm.myVehicle.collectAsState()
    val target = vm.partners.collectAsState().value.firstOrNull { it.id == vehicleId }

    if (target == null || myVehicle == null) return
    val dist = vm.run { distanceMeters(myVehicle!!.latitude, myVehicle!!.longitude, target.latitude, target.longitude) }.toFloat()

    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF16202C))) {
        Column(Modifier.padding(16.dp)) {
            Text("Vehicle ID: ${target.id}", fontWeight = FontWeight.Bold, color = Color.White)
            Text("Status: ${if (target.speed > 0.5f) "Moving" else "Stopped"}", color = Color.Gray)
            Text("Speed: ${target.speed.format1()} m/s", color = Color.Gray)
            Text("Relative Distance: ${dist.format1()} m", color = Color(0xFF9ECFFF))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Close") } }
        }
    }
}

// 6. Indoor Screen (Unchanged)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndoorScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Indoor Mode") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { p ->
        Box(Modifier.fillMaxSize().padding(p)) {
            Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                // Stylized floor plan
                drawRect(Color(0xFF102030))
                drawRect(Color(0xFF1B2A3A), size = size * 0.8f, topLeft = Offset(size.width * 0.1f, size.height * 0.1f))
                // Base stations
                val bs = listOf("BS-1" to Offset(size.width * 0.2f, size.height * 0.2f), "BS-2" to Offset(size.width * 0.8f, size.height * 0.25f), "BS-3" to Offset(size.width * 0.5f, size.height * 0.75f))
                bs.forEach { (_, pt) ->
                    drawCircle(Color(0xFF00E676), radius = 12f, center = pt)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Accuracy: 1.2 meters", color = Color.White)
            }
        }
    }
}

// 7. Trip History (Unchanged)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripHistoryScreen(onBack: () -> Unit) {
    val items = listOf(
        Triple("Today, 10:30 AM", "12.4 km", 2),
        Triple("Today, 8:10 AM", "5.1 km", 0),
        Triple("Yesterday, 5:00 PM", "18.3 km", 1),
        Triple("Wed, 3:45 PM", "7.0 km", 0)
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Trip History") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp)) {
            items(items) { (t, dist, warns) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF16202C))) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(t, color = Color.White, fontWeight = FontWeight.Bold); Text("$dist", color = Color.Gray) }
                        Text("Warnings: $warns", color = Color(0xFF9ECFFF))
                    }
                }
            }
        }
    }
}

// 8. Driving Limits (Unchanged)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivingLimitsScreen(vm: AutoNavViewModel, onBack: () -> Unit) {
    val safe by vm.safeDistanceMeters.collectAsState()
    val sens by vm.sensitivityHigh.collectAsState()
    val custom by vm.customSpeedEnabled.collectAsState()
    val customVal by vm.customSpeedKmh.collectAsState()
    val speedAlerts by vm.speedLimitEnabled.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Driving Limits") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp)) {
            Text("Safe Distance: ${safe.toInt()} m")
            Slider(value = safe, onValueChange = { vm.setSafeDistance(it) }, valueRange = 5f..50f)
            SettingSwitch("Speed Limit Alerts", speedAlerts) { vm.setSpeedLimitEnabled(it) }
            SettingSwitch("Custom Speed Warning", custom) { vm.setCustomSpeedEnabled(it) }
            if (custom) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom: ${customVal.toInt()} km/h", Modifier.weight(1f))
                    Slider(value = customVal, onValueChange = { vm.setCustomSpeed(it) }, valueRange = 30f..160f, modifier = Modifier.weight(2f))
                }
            }
            SettingSwitch("Warning Sensitivity (longer horizon)", sens) { vm.setSensitivityHigh(it) }
        }
    }
}

// 9. Data Stream (Unchanged)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataStreamScreen(vm: AutoNavViewModel, onBack: () -> Unit) {
    val me by vm.myVehicle.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Data Stream") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp)) {
            Text("Device ID: ${me?.id ?: "Loading..."}", color = Color.White)
            Text("Lat: ${me?.latitude?.format6()}")
            Text("Lon: ${me?.longitude?.format6()}")
            Text("Speed: ${me?.speed?.format1() ?: "0.0"} m/s")
            Text("Satellites: 10 (mock)")
            Text("HDOP: 0.9 (mock)")
            Text("NMEA: \$GPRMC,092751.000,A,5321.6802,N,00630.3372,W,0.06,31.66,280511,,,A*43 (mock)")
        }
    }
}

// 10. Splash (Unchanged)
@Composable
private fun SplashScreen(onContinue: () -> Unit) {
    LaunchedEffect(Unit) { delay(1200); onContinue() }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1116)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Home, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(72.dp))
            Text("AutoNavShare", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Smarter, Safer, Together.", color = Color(0xFF9ECFFF), textAlign = TextAlign.Center)
        }
    }
}

// Lane Assist Screen (Fixed random number generation)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaneAssistScreen(vm: AutoNavViewModel, onBack: () -> Unit) {
    var isLaneDetected by remember { mutableStateOf(true) }
    var laneDeviation by remember { mutableStateOf(0f) }
    var showWarning by remember { mutableStateOf(false) }
    var warningText by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf(0.8f) }
    var numLines by remember { mutableStateOf(2) }

    val backendEnabled by vm.laneDetectionEnabled.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()

    // Constants for the mock calculation
    val minConfidence = 0.7f
    val maxConfidence = 0.95f
    val confidenceRange = maxConfidence - minConfidence
    val laneDevRange = 1.0f // range from -1.0 to 1.0

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)

            if (!backendEnabled) {
                isLaneDetected = true
                // FIX: Use Random.nextFloat() for reliable float generation
                // Formula: Random.nextFloat() * (max - min) + min
                laneDeviation = (Random.nextFloat() * (laneDevRange * 2) - laneDevRange) * 0.5f

                // FIX: Use Random.nextFloat() scaled to the [0.7f, 0.95f] range
                confidence = Random.nextFloat() * confidenceRange + minConfidence

                // FIX: Use Random.nextInt(min, max_exclusive) for integer ranges [2..5]
                numLines = Random.nextInt(2, 6)
            }

            if (abs(laneDeviation) > 0.3f) {
                showWarning = true
                warningText = if (laneDeviation > 0) "⚠️ RIGHT LANE DEPARTURE!" else "⚠️ LEFT LANE DEPARTURE!"
            } else {
                showWarning = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lane Keep Assist", color = Color.White) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E2F))
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera Preview Placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0A))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerX = size.width / 2
                    val centerY = size.height * 0.75f

                    drawLine(
                        color = Color.Green.copy(alpha = 0.8f),
                        start = Offset(centerX - 150f - laneDeviation * 200, size.height),
                        end = Offset(centerX - 50f - laneDeviation * 100, centerY),
                        strokeWidth = 10f
                    )
                    drawLine(
                        color = Color.Green.copy(alpha = 0.8f),
                        start = Offset(centerX + 150f - laneDeviation * 200, size.height),
                        end = Offset(centerX + 50f - laneDeviation * 100, centerY),
                        strokeWidth = 10f
                    )

                    drawCircle(
                        color = if (isLaneDetected) Color.Cyan else Color.Red,
                        radius = 20f,
                        center = Offset(centerX - laneDeviation * 250, centerY)
                    )
                }
            }

            // Warning overlay
            if (showWarning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xAAFF0000))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = warningText,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Status HUD
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isLaneDetected) "Lane Detected" else "No Lane Detected",
                    color = if (isLaneDetected) Color.Green else Color.Red,
                    style = MaterialTheme.typography.titleMedium
                )

                if (backendEnabled) {
                    Text(
                        text = "Backend: $backendUrl",
                        color = Color(0xFF9ECFFF),
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Lines: $numLines | Confidence: ${(confidence * 100).toInt()}%",
                        color = Color(0xFF9ECFFF),
                        fontSize = 10.sp
                    )
                }

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(Color(0xFF2196F3), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (laneDeviation > 0.2f) "→" else if (laneDeviation < -0.2f) "←" else "•",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }
    }
}

// ================== UTIL ==================
private fun Double.format6(): String = String.format("%.6f", this)
private fun Float.format1(): String = String.format("%.1f", this)