package com.example.collisionavoidancesystem

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.collisionavoidancesystem.model.VehicleData
import com.example.collisionavoidancesystem.service.PartnerDto
import com.tomtom.sdk.common.Context
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.map.display.MapOptions
import com.tomtom.sdk.map.display.TomTomMap
import com.tomtom.sdk.map.display.camera.CameraOptionsFactory
import com.tomtom.sdk.map.display.ui.MapView
import com.tomtom.sdk.map.display.marker.MarkerOptions
import com.tomtom.sdk.map.display.image.ImageFactory

@Composable
fun LiveMap(
    context: Context,
    myVehicle: VehicleData?,
    partners: List<PartnerDto>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // State to hold the TomTomMap once it's initialized
    var tomTomMap by remember { mutableStateOf<TomTomMap?>(null) }

    // Remember the MapView instance
    val mapView = remember {
        MapView(
            context,
            MapOptions(
                mapKey = "ca0NsFjXX0JdpzG4BAPB2HUmwmBBHqwJ" // Your API Key
            )
        )
    }

    // 1. Manually manage the MapView lifecycle (onStop, onResume, onDestroy)
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                mapView.onResume()
            }
            override fun onPause(owner: LifecycleOwner) {
                mapView.onPause()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                mapView.onDestroy()
                tomTomMap = null
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 2. Safely call getMapAsync in a LaunchedEffect
    // This is where the crash was occurring (Map.kt:74)
    // It's still the correct place, but sometimes the view needs a moment longer.
    LaunchedEffect(mapView) {
        // Attempting a slight delay might resolve the internal TomTom SDK race condition
        // if the previous crash was due to a very quick getMapAsync call.
        // However, we will assume the correct context is enough for now.
        mapView.getMapAsync { map ->
            tomTomMap = map

            // Initial camera move after map is ready
            myVehicle?.let { vehicle ->
                val cameraOptions = CameraOptionsFactory.lookAt(
                    GeoPoint(vehicle.latitude, vehicle.longitude),
                    zoom = 16.0
                )
                map.moveCamera(cameraOptions)
            }
        }
    }

    // 3. AndroidView Integration
    AndroidView(
        modifier = modifier,
        factory = {
            // Only return the previously instantiated MapView instance.
            mapView
        },
        update = {
            // This runs on recomposition, ONLY when tomTomMap is initialized.
            tomTomMap?.let { map ->
                map.clear() // Clear old markers

                // My vehicle marker
                myVehicle?.let { vehicle ->
                    val myMarker = MarkerOptions(
                        coordinate = GeoPoint(vehicle.latitude, vehicle.longitude),
                        pinImage = ImageFactory.fromResource(R.drawable.car_blue)
                    )
                    map.addMarker(myMarker)
                }

                // Partner markers
                partners.forEach { partner ->
                    val pinResource = when (partner.warning) {
                        "COLLISION_RISK" -> R.drawable.car_red
                        "PROXIMITY" -> R.drawable.car_green
                        else -> R.drawable.car_blue
                    }

                    val partnerMarker = MarkerOptions(
                        coordinate = GeoPoint(partner.latitude, partner.longitude),
                        pinImage = ImageFactory.fromResource(pinResource)
                    )
                    map.addMarker(partnerMarker)
                }
            }
        }
    )
}