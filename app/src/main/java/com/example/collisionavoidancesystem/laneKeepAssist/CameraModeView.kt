package com.example.collisionavoidancesystem.laneKeepAssist



import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.collisionavoidancesystem.service.PartnerDto
import com.example.collisionavoidancesystem.model.VehicleData

@Composable
fun CameraModeView(
    myVehicle: VehicleData,
    partners: List<PartnerDto>,
    laneKeepEnabled: Boolean,
    deviceId: String
) {
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1️⃣ Camera Preview
        CameraPreviewView(modifier = Modifier.fillMaxSize()) { bitmap ->
            currentFrame = bitmap
        }

        // 2️⃣ Lane Keep Assist overlay
        if (laneKeepEnabled && currentFrame != null) {
            val lanes = detectLanes(currentFrame!!)
            val laneDeparture = checkLaneDeparture(lanes, currentFrame!!.width)
            LaneOverlay(lanes, laneDeparture, partners)
        }

        // 3️⃣ Collision alert overlay
        partners.firstOrNull { it.warning == "COLLISION_RISK" }?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xAAFF0000))
                    .padding(12.dp)
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "⚠️ COLLISION RISK!",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }

        // 4️⃣ My vehicle info overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color(0xAA000000), shape = MaterialTheme.shapes.medium)
                .padding(12.dp)
        ) {
            Column {
                Text("My Vehicle: $deviceId", color = Color.White)
                Text("Speed: %.1f km/h".format(myVehicle.speed), color = Color.White)
                Text("Heading: %.1f°".format(myVehicle.heading), color = Color.White)
                if (laneKeepEnabled) Text("Lane Keep Assist: ENABLED", color = Color(0xFF1976D2))
            }
        }
    }
}
