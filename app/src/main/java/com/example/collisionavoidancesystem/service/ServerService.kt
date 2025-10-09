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
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

object ServerService {
    private const val TAG = "ServerService"
    private const val DEFAULT_SERVER = "http://192.168.31.189:8000"
    private const val WS_ENDPOINT = "/ws"
    private const val UPDATE_ENDPOINT = "/update"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long connection for WebSocket
        .build()

    private var ws: WebSocket? = null
    private val _partners = MutableStateFlow<List<PartnerDto>>(emptyList())
    val partnersFlow: StateFlow<List<PartnerDto>> = _partners

    private var serverBase = DEFAULT_SERVER
    private var deviceId: String = ""

    fun init(context: Context, serverBaseUrl: String = DEFAULT_SERVER) {
        serverBase = serverBaseUrl.trimEnd('/')

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { newId ->
            prefs.edit().putString("device_id", newId).apply()
        }

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
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000)
                        connectWebSocket() // auto-reconnect
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket closed: $reason")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connect error", e)
        }
    }

    fun sendMyData(vehicle: VehicleData) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
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

                val jsonBody = gson.toJson(payload)
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$serverBase$UPDATE_ENDPOINT")
                    .post(body)
                    .build()

                val resp = client.newCall(req).execute()
                resp.close()
                Log.d(TAG, "📤 Vehicle data sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "sendMyData error: ${e.message}")
            }
        }
    }
}
