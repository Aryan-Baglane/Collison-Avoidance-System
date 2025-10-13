package com.example.collisionavoidancesystem.service

import android.content.Context
import android.util.Log
import com.example.collisionavoidancesystem.model.VehicleData
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import java.util.*
import java.util.concurrent.TimeUnit

object ServerService {
    private const val TAG = "ServerService"
    private const val DEFAULT_SERVER = "http://192.168.31.189:8000"
    private val FALLBACK_SERVERS = listOf(

        "http://192.168.31.189:8000"
    )
    private const val WS_ENDPOINT = "/ws"
    private const val UPDATE_ENDPOINT = "/update"
    private const val LANE_ENDPOINT = "/lane-assist"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long connection for WebSocket
        .build()

    private var ws: WebSocket? = null
    @Volatile private var isWsOpen: Boolean = false
    private val _partners = MutableStateFlow<List<PartnerDto>>(emptyList())
    val partnersFlow: StateFlow<List<PartnerDto>> = _partners

    private var serverBase = DEFAULT_SERVER
    private var serverRotation: List<String> = FALLBACK_SERVERS
    private var serverIndex = 0
    private var deviceId: String = ""
    private lateinit var appContext: Context

    fun init(context: Context, serverBaseUrl: String = DEFAULT_SERVER) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        // Build rotation list preferring provided URL, then fallbacks
        val preferred = serverBaseUrl.trimEnd('/')
        serverRotation = listOf(preferred) + FALLBACK_SERVERS.filter { it.trimEnd('/') != preferred }
        // Use last successful server if present
        val last = prefs.getString("server_base", null)?.trimEnd('/')
        serverBase = (last ?: preferred).trimEnd('/')

        deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { newId ->
            prefs.edit().putString("device_id", newId).apply()
        }
        Log.d(TAG, "Using deviceId=$deviceId for server=$serverBase")
        connectWebSocket()
    }

    fun shutdown() {
        ws?.close(1000, "Client shutdown")
        ws = null
    }

    private fun connectWebSocket() {
        val wsUrl = serverBase.replaceFirst("http", "ws") + "$WS_ENDPOINT/$deviceId"
        try {
            val req = Request.Builder().url(wsUrl).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ WebSocket connected to $wsUrl")
                    isWsOpen = true
                    // Immediately announce presence with deviceId for servers expecting an init message
                    val hello = gson.toJson(mapOf("type" to "hello", "id" to deviceId))
                    webSocket.send(hello)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = gson.fromJson(text, Map::class.java)
                        val type = json["type"] as? String
                        if (type == "partners_update") {
                            val partnersAny = json["partners"] as? List<*>
                            partnersAny?.let { list ->
                                val parsed = list.mapNotNull { item ->
                                    try {
                                        val map = item as Map<*, *>
                                        PartnerDto(
                                            id = map["id"] as String,
                                            latitude = (map["latitude"] as Number).toDouble(),
                                            longitude = (map["longitude"] as Number).toDouble(),
                                            speed = (map["speed"] as Number).toFloat(),
                                            heading = (map["heading"] as Number).toFloat(),
                                            role = map["role"] as String,
                                            isBaseStation = map["isBaseStation"] as Boolean,
                                            timestamp = (map["timestamp"] as Number).toLong(),
                                            distance_m = (map["distance_m"] as? Number)?.toDouble() ?: 0.0,
                                            closing_speed_mps = (map["closing_speed_mps"] as? Number)?.toDouble() ?: 0.0,
                                            ttc_s = (map["ttc_s"] as? Number)?.toDouble() ?: 0.0,
                                            warning = map["warning"] as? String ?: "none"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Parse error: ${e.message}")
                                        null
                                    }
                                }
                                CoroutineScope(Dispatchers.Main).launch {
                                    _partners.value = parsed
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSocket message error", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket failed: ${t.message}")
                    isWsOpen = false
                    CoroutineScope(Dispatchers.IO).launch {
                        // Rotate to next server and persist choice
                        serverIndex = (serverIndex + 1) % serverRotation.size
                        serverBase = serverRotation[serverIndex]
                        Log.w(TAG, "Retrying with server=$serverBase")
                        try {
                            val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putString("server_base", serverBase).apply()
                        } catch (_: Exception) {}
                        delay(2000)
                        connectWebSocket() // auto-reconnect
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket closed: $reason")
                    isWsOpen = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connect error", e)
        }
    }

    fun sendMyData(vehicle: VehicleData) {
        CoroutineScope(Dispatchers.IO).launch {
            val payload = mapOf(
                "id" to vehicle.id,
                "latitude" to vehicle.latitude,
                "longitude" to vehicle.longitude,
                "speed" to vehicle.speed,
                "heading" to vehicle.heading,
                "role" to vehicle.role.name,
                "isBaseStation" to vehicle.isBaseStation,
                "timestamp" to System.currentTimeMillis()
            )

            // Prefer websocket if connected (send over WS), but also POST to backend for state
            val socket = ws
            if (isWsOpen && socket != null) {
                try {
                    val msg = gson.toJson(mapOf("type" to "update", "data" to payload))
                    socket.send(msg)
                    Log.d(TAG, "📡 WS update sent")
                } catch (e: Exception) {
                    Log.w(TAG, "WS send failed, falling back to HTTP: ${e.message}")
                }
            }

            // Always POST to keep backend devices dict updated
            try {
                val jsonBody = gson.toJson(payload)
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$serverBase$UPDATE_ENDPOINT")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { /* close */ }
                Log.d(TAG, "📤 HTTP update sent")
            } catch (e: Exception) {
                Log.e(TAG, "sendMyData error: ${e.message}")
            }
        }
    }

    data class LaneAssistResponse(
        val lanes_detected: Boolean,
        val num_lines: Int
    )

    /**
     * Sends an image (JPEG bytes) to the backend lane assist endpoint and parses a response.
     * Returns null on failure.
     */
    suspend fun detectLanesRemote(imageJpeg: ByteArray): LaneAssistResponse? = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "image",
                    filename = "frame.jpg",
                    body = imageJpeg.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val req = Request.Builder()
                .url("$serverBase$LANE_ENDPOINT")
                .post(requestBody)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                return@withContext try {
                    gson.fromJson(bodyStr, LaneAssistResponse::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "LaneAssist parse error: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectLanesRemote error: ${e.message}")
            null
        }
    }
}
