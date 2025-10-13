package com.example.collisionavoidancesystem


import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// NOTE: These imports rely on external files (CameraPreviewView, LaneOverlay, detectLanes, checkLaneDeparture)
import com.example.collisionavoidancesystem.laneKeepAssist.CameraPreviewView
import com.example.collisionavoidancesystem.laneKeepAssist.LaneOverlay
import com.example.collisionavoidancesystem.laneKeepAssist.checkLaneDeparture
import com.example.collisionavoidancesystem.laneKeepAssist.detectLanes
// NOTE: These imports rely on external data models and services
import com.example.collisionavoidancesystem.model.VehicleData
import com.example.collisionavoidancesystem.service.PartnerDto
import com.example.collisionavoidancesystem.service.ServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardUI(
    context: Context,
    myVehicle: VehicleData,
    partners: List<PartnerDto>,
    onRefresh: () -> Unit,
    deviceId: String
) {
    var showMap by remember { mutableStateOf(true) }
    var laneKeepEnabled by remember { mutableStateOf(false) }
    var showCameraMode by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var remoteLaneDetected by remember { mutableStateOf<Boolean?>(null) }
    var remoteLaneLines by remember { mutableStateOf(0) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    var extendedMap by remember { mutableStateOf(false) }
    var collisionProbability by remember { mutableStateOf(0) }
    var showWarningSheet by remember { mutableStateOf(false) }

    val riskLevel = when {
        partners.any { it.warning == "COLLISION_RISK" } -> "DANGER"
        partners.any { it.warning == "PROXIMITY" } -> "CAUTION"
        else -> "SAFE"
    }

    val bgColor by animateColorAsState(
        targetValue = when (riskLevel) {
            "DANGER" -> Color(0xFFD32F2F)
            "CAUTION" -> Color(0xFFFBC02D)
            else -> Color(0xFF388E3C)
        }, label = ""
    )

    val pulseAnim by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = if (riskLevel == "DANGER") 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Collision Avoidance System") },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { showCameraMode = !showCameraMode }) {
                        Text(if (showCameraMode) "Exit Camera" else "Camera Mode", color = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            // NOTE: The R.drawable.map resource is required
            FloatingActionButton(onClick = { showMap = !showMap }) {
                Icon(painterResource(id = R.drawable.map), contentDescription = "Toggle Map")
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFFF5F5F5))
        ) {

            if (showCameraMode) {
                // Camera mode with LKA + collision overlay
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreviewView(modifier = Modifier.fillMaxSize()) { bitmap ->
                        currentFrame = bitmap
                    }

                    if (laneKeepEnabled && currentFrame != null) {
                        val lanes = detectLanes(currentFrame!!)
                        val laneDeparture = checkLaneDeparture(lanes, currentFrame!!.width)
                        LaneOverlay(lanes, laneDeparture, partners)
                    }

                    // Launch/stop remote lane assist uploads when toggled
                    LaunchedEffect(showCameraMode, laneKeepEnabled) {
                        uploadJob?.cancel()
                        if (showCameraMode && laneKeepEnabled) {
                            uploadJob = launch(Dispatchers.IO) {
                                while (isActive) {
                                    val bmp = currentFrame
                                    if (bmp != null) {
                                        val out = ByteArrayOutputStream()
                                        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                        if (ok) {
                                            // Call to ServerService
                                            val resp = ServerService.detectLanesRemote(out.toByteArray())
                                            if (resp != null) {
                                                remoteLaneDetected = resp.lanes_detected
                                                remoteLaneLines = resp.num_lines
                                            }
                                        }
                                    }
                                    delay(800)
                                }
                            }
                        }
                    }

                    // Bottom popup (sheet) for collision warning instead of full-screen overlay
                    LaunchedEffect(partners) {
                        showWarningSheet = partners.any { it.warning == "COLLISION_RISK" }
                    }
                    if (showWarningSheet) {
                        ModalBottomSheet(onDismissRequest = { showWarningSheet = false }) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("⚠️ Collision risk detected", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFFD32F2F))
                                Spacer(Modifier.height(8.dp))
                                Text("Reduce speed and increase following distance.")
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { showWarningSheet = false }) { Text("Dismiss") }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }

                    // My vehicle + remote lane info overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color(0xAA000000), shape = MaterialTheme.shapes.medium)
                            .padding(12.dp)
                    ) {
                        Column {
                            Text("My Vehicle: $deviceId", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            // Assuming myVehicle.speed is in km/h for the UI display
                            Text("Speed: %.1f km/h".format(myVehicle.speed), color = Color.White)
                            Text("Heading: %.1f°".format(myVehicle.heading), color = Color.White)
                            remoteLaneDetected?.let { rd ->
                                Text("Server Lanes: ${if (rd) "Detected" else "None"} (${remoteLaneLines})", color = Color.LightGray)
                            }
                        }
                    }
                }
            } else {
                // Normal dashboard with map & nearby vehicles
                Column {
                    // Alert Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(pulseAnim)
                            .background(bgColor, shape = MaterialTheme.shapes.medium)
                            .padding(vertical = 16.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (riskLevel) {
                                "DANGER" -> "⚠️ COLLISION RISK DETECTED!"
                                "CAUTION" -> "⚠️ Vehicle nearby — drive carefully"
                                else -> "✅ All clear"
                            },
                            color = Color.White
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Lane Keep Assist toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lane Keep Assist", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = laneKeepEnabled, onCheckedChange = { laneKeepEnabled = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Extended map")
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = extendedMap, onCheckedChange = { extendedMap = it })
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (showMap) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (extendedMap) 520.dp else 300.dp)
                                .padding(horizontal = 16.dp),
                            elevation = CardDefaults.cardElevation(8.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            // Call to the Live Map Composable
                            LiveMap(context, myVehicle, partners)
                        }

                        Spacer(Modifier.height(12.dp))

                        // Live collision status bar (above bottom area)
                        // Random probability until backend provides a value
                        LaunchedEffect(partners) {
                            // Update every second
                            while (true) {
                                val risk = when {
                                    partners.any { it.warning == "COLLISION_RISK" } -> 2
                                    partners.any { it.warning == "PROXIMITY" } -> 1
                                    else -> 0
                                }
                                collisionProbability = when (risk) {
                                    2 -> (70..95).random()
                                    1 -> (30..60).random()
                                    else -> 0
                                }
                                delay(1000)
                            }
                        }

                        val statusColor = when {
                            partners.any { it.warning == "COLLISION_RISK" } -> Color(0xFFD32F2F)
                            partners.any { it.warning == "PROXIMITY" } -> Color(0xFFFFA000)
                            else -> Color(0xFF2E7D32)
                        }
                        val statusText = when {
                            partners.any { it.warning == "COLLISION_RISK" } -> "Collision risk"
                            partners.any { it.warning == "PROXIMITY" } -> "Vehicle nearby"
                            else -> "All clear"
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = statusColor),
                            elevation = CardDefaults.cardElevation(3.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(statusText, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("Prob: ${collisionProbability}%", color = Color.White)
                            }
                        }

                        // Bottom popup (sheet) for collision warning in dashboard mode
                        LaunchedEffect(partners) {
                            showWarningSheet = partners.any { it.warning == "COLLISION_RISK" }
                        }
                        if (showWarningSheet) {
                            ModalBottomSheet(onDismissRequest = { showWarningSheet = false }) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text("⚠️ Collision risk detected", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFFD32F2F))
                                    Spacer(Modifier.height(8.dp))
                                    Text("Reduce speed and increase following distance.")
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { showWarningSheet = false }) { Text("Dismiss") }
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }

                    Text(
                        text = "Nearby Vehicles",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                    ) {
                        if (partners.isEmpty()) {
                            item {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No nearby vehicles detected", color = Color.Gray)
                                }
                            }
                        } else {
                            items(partners) { partner ->
                                PartnerCardModern(partner)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PartnerCardModern(partner: PartnerDto) {
    val cardColor = when (partner.warning) {
        "COLLISION_RISK" -> Color(0xFFFF8A80)
        "PROXIMITY" -> Color(0xFFFFF59D)
        else -> Color(0xFFC8E6C9)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(5.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Vehicle ID: ${partner.id}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Distance: %.1f m".format(partner.distance_m))
                // partner.speed is expected to be in m/s from the backend
                Text("Speed: %.1f m/s".format(partner.speed))
            }
            Text(
                text = when (partner.warning) {
                    "COLLISION_RISK" -> "⚠️ COLLISION"
                    "PROXIMITY" -> "🚗 Close"
                    else -> "✅ Safe"
                },
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}