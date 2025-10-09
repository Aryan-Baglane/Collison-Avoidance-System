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
import com.example.collisionavoidancesystem.laneKeepAssist.CameraPreviewView
import com.example.collisionavoidancesystem.laneKeepAssist.LaneOverlay
import com.example.collisionavoidancesystem.laneKeepAssist.checkLaneDeparture
import com.example.collisionavoidancesystem.laneKeepAssist.detectLanes
import com.example.collisionavoidancesystem.model.VehicleData
import com.example.collisionavoidancesystem.service.PartnerDto

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
            FloatingActionButton(onClick = { showMap = !showMap }) {
                Icon(   painterResource(R.drawable.map), contentDescription = "Toggle Map")
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

                    // Collision alert overlay
                    partners.firstOrNull { it.warning == "COLLISION_RISK" }?.let {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xAAFF0000))
                                .padding(12.dp)
                                .align(Alignment.TopCenter),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚠️ COLLISION RISK!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }

                    // My vehicle info overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color(0xAA000000), shape = MaterialTheme.shapes.medium)
                            .padding(12.dp)
                    ) {
                        Column {
                            Text("My Vehicle: $deviceId", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("Speed: %.1f km/h".format(myVehicle.speed), color = Color.White)
                            Text("Heading: %.1f°".format(myVehicle.heading), color = Color.White)
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
                        Text("Lane Keep Assist", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Switch(checked = laneKeepEnabled, onCheckedChange = { laneKeepEnabled = it })
                    }

                    Spacer(Modifier.height(6.dp))

                    if (showMap) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(horizontal = 16.dp),
                            elevation = CardDefaults.cardElevation(8.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            LiveMapOSM(context, myVehicle, partners)
                        }

                        Spacer(Modifier.height(12.dp))
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
