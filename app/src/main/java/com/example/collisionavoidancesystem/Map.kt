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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

private data class RouteResp(
    @SerializedName("routes") val routes: List<RouteItem>
)
private data class RouteItem(
    @SerializedName("summary") val summary: RouteSummary
)
private data class RouteSummary(
    @SerializedName("lengthInMeters") val lengthInMeters: Double
)
// (no additional imports here; imports must remain at top)

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
    // Track last camera center to avoid repeated moves
    var lastCenteredLatitude by remember { mutableStateOf<Double?>(null) }
    var lastCenteredLongitude by remember { mutableStateOf<Double?>(null) }
    // Cache of route distances per partner id (string like "1.2 km")
    var routeDistances by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // Remember the MapView instance and create it with its lifecycle
    val mapView = remember {
        MapView(
            context,
            MapOptions(
                mapKey = "ca0NsFjXX0JdpzG4BAPB2HUmwmBBHqwJ" // Your API Key
            )
        ).apply {
            // Ensure TomTom internal controller is created before getMapAsync
            onCreate(null)
        }
    }

    // 1. Manually manage the MapView lifecycle (onStop, onResume, onDestroy)
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                mapView.onStart()
            }
            override fun onResume(owner: LifecycleOwner) {
                mapView.onResume()
            }
            override fun onPause(owner: LifecycleOwner) {
                mapView.onPause()
            }
            override fun onStop(owner: LifecycleOwner) {
                mapView.onStop()
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

    // Fetch route distances for up to N nearest partners to reduce clutter
    LaunchedEffect(myVehicle, partners) {
        val me = myVehicle ?: return@LaunchedEffect
        if (partners.isEmpty()) {
            routeDistances = emptyMap()
            return@LaunchedEffect
        }
        val nearest = partners
            .sortedBy { p -> haversineMeters(me.latitude, me.longitude, p.latitude, p.longitude) }
            .take(3)
        val client = OkHttpClient()
        val gson = Gson()
        val apiKey = "ca0NsFjXX0JdpzG4BAPB2HUmwmBBHqwJ"
        val results = mutableMapOf<String, String>()
        for (p in nearest) {
            val url = "https://api.tomtom.com/routing/1/calculateRoute/${me.latitude},${me.longitude}:${p.latitude},${p.longitude}/json?key=$apiKey"
            try {
                val req = Request.Builder().url(url).get().build()
                val body = withContext(Dispatchers.IO) {
                    val resp = client.newCall(req).execute()
                    try { resp.body?.string() } finally { resp.close() }
                }
                if (!body.isNullOrBlank()) {
                    val resp = gson.fromJson(body, RouteResp::class.java)
                    val dist = resp.routes.firstOrNull()?.summary?.lengthInMeters ?: 0.0
                    val label = if (dist >= 1000.0) String.format("%.1f km", dist / 1000.0) else String.format("%d m", dist.toInt())
                    results[p.id] = label
                }
            } catch (_: Exception) {}
        }
        routeDistances = results
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

                fun scaledPin(resourceId: Int, scale: Float = 0.45f): com.tomtom.sdk.map.display.image.Image {
                    val res = context.resources
                    val bmp = android.graphics.BitmapFactory.decodeResource(res, resourceId)
                    val w = max(1, (bmp.width * scale).toInt())
                    val h = max(1, (bmp.height * scale).toInt())
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                    return ImageFactory.fromBitmap(scaled)
                }

                fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
                    val R = 6371000.0
                    val dLat = Math.toRadians(lat2 - lat1)
                    val dLon = Math.toRadians(lon2 - lon1)
                    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
                    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
                }

                fun distanceLabelBitmap(text: String): android.graphics.Bitmap {
                    val padding = 8
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 28f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    val bounds = android.graphics.Rect()
                    paint.getTextBounds(text, 0, text.length, bounds)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        bounds.width() + padding * 2,
                        bounds.height() + padding * 2,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    val bgPaint = android.graphics.Paint().apply { color = 0xAA000000.toInt() }
                    canvas.drawRoundRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), 12f, 12f, bgPaint)
                    canvas.drawText(text, padding.toFloat(), padding + bounds.height().toFloat(), paint)
                    return bmp
                }

                // My vehicle marker
                myVehicle?.let { vehicle ->
                    val myMarker = MarkerOptions(
                        coordinate = GeoPoint(vehicle.latitude, vehicle.longitude),
                        pinImage = scaledPin(R.drawable.car_blue, scale = 0.1f)
                    )
                    map.addMarker(myMarker)

                    // Center/track camera on the latest device location if it changed
                    val currentLatitude = vehicle.latitude
                    val currentLongitude = vehicle.longitude
                    if (lastCenteredLatitude != currentLatitude || lastCenteredLongitude != currentLongitude) {
                        val cameraOptions = CameraOptionsFactory.lookAt(
                            GeoPoint(currentLatitude, currentLongitude),
                            zoom = 16.0
                        )
                        map.moveCamera(cameraOptions)
                        lastCenteredLatitude = currentLatitude
                        lastCenteredLongitude = currentLongitude
                    }
                }

                // Partner markers + dotted straight-line and distance labels (route distance when available)
                partners.forEach { partner ->
                    val pinResource = when (partner.warning) {
                        "COLLISION_RISK" -> (R.drawable.car_red)
                        "PROXIMITY" -> R.drawable.car_green
                        else -> R.drawable.car_blue
                    }

                    val partnerMarker = MarkerOptions(
                        coordinate = GeoPoint(partner.latitude, partner.longitude),
                        pinImage = scaledPin(pinResource, scale = 0.1f)
                    )
                    map.addMarker(partnerMarker)

                    // Dotted straight line between points (placeholder for route polyline)
                    myVehicle?.let { me ->
                        val steps = 20
                        val dot = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).apply {
                            eraseColor(0xFF00FFFFFF.toInt())
                        }
                        val colorBmp = when (partner.warning) {
                            "COLLISION_RISK" -> android.graphics.Color.RED
                            "PROXIMITY" -> android.graphics.Color.YELLOW
                            else -> android.graphics.Color.GREEN
                        }
                        // tint dot
                        val canvas = android.graphics.Canvas(dot)
                        val paint = android.graphics.Paint().apply { color = colorBmp }
                        canvas.drawCircle(4f, 4f, 3f, paint)
                        val img = ImageFactory.fromBitmap(dot)
                        for (i in 1 until steps) {
                            val t = i / steps.toDouble()
                            val lat = me.latitude + (partner.latitude - me.latitude) * t
                            val lon = me.longitude + (partner.longitude - me.longitude) * t
                            val m = MarkerOptions(coordinate = GeoPoint(lat, lon), pinImage = img)
                            map.addMarker(m)
                        }
                    }

                    // Distance label at midpoint (uses route distance if available)
                    myVehicle?.let { me ->
                        val fallback = haversineMeters(me.latitude, me.longitude, partner.latitude, partner.longitude)
                        val fallbackText = if (fallback >= 1000.0) String.format("%.1f km", fallback / 1000.0) else String.format("%d m", fallback.toInt())
                        val labelText = routeDistances[partner.id] ?: fallbackText
                        val midLat = (me.latitude + partner.latitude) / 2.0
                        val midLon = (me.longitude + partner.longitude) / 2.0
                        val labelBmp = distanceLabelBitmap(labelText)
                        val labelMarker = MarkerOptions(
                            coordinate = GeoPoint(midLat, midLon),
                            pinImage = ImageFactory.fromBitmap(labelBmp)
                        )
                        map.addMarker(labelMarker)
                    }
                }
            }
        }
    )
}