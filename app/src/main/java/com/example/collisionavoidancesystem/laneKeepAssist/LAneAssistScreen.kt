package com.example.collisionavoidancesystem.laneKeepAssist



import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaneAssistScreen(
    context: Context = LocalContext.current,
    onBackClick: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var laneDeviation by remember { mutableStateOf(0f) }
    var isLaneDetected by remember { mutableStateOf(true) }

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    // Animate lane deviation indicator
    val animatedDeviation by animateFloatAsState(
        targetValue = laneDeviation,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing)
    )

    // Start fake lane tracking simulation
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            isLaneDetected = true
            laneDeviation = (-1..1).random().toFloat() * 0.5f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lane Assist", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E2F)
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // CAMERA PREVIEW
            AndroidView(
                factory = {
                    val previewView = PreviewView(it).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(it)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        try {
                            if (ContextCompat.checkSelfPermission(
                                    it, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview
                                )
                            } else {
                                Log.e("LaneAssist", "Camera permission not granted")
                            }
                        } catch (e: Exception) {
                            Log.e("LaneAssist", "Camera binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(it))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // LANE OVERLAY SIMULATION
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                val centerY = size.height * 0.75f

                // Lane lines
                drawLine(
                    color = Color.Green.copy(alpha = 0.8f),
                    start = Offset(centerX - 150f - animatedDeviation * 200, size.height),
                    end = Offset(centerX - 50f - animatedDeviation * 100, centerY),
                    strokeWidth = 10f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.Green.copy(alpha = 0.8f),
                    start = Offset(centerX + 150f - animatedDeviation * 200, size.height),
                    end = Offset(centerX + 50f - animatedDeviation * 100, centerY),
                    strokeWidth = 10f,
                    cap = StrokeCap.Round
                )

                // Car center indicator
                drawCircle(
                    color = if (isLaneDetected) Color.Cyan else Color.Red,
                    radius = 20f,
                    center = Offset(centerX - animatedDeviation * 250, centerY)
                )
            }

            // STATUS HUD
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

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(Color(0xFF2196F3), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (animatedDeviation > 0.2f) "→" else if (animatedDeviation < -0.2f) "←" else "•",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }
    }
}
