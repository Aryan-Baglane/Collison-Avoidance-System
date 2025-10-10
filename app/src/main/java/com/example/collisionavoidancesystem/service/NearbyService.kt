package com.example.collisionavoidancesystem.service

import android.content.Context
import android.util.Log
import com.example.collisionavoidancesystem.model.VehicleData
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.nearby.connection.ConnectionsClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

object NearbyService {
    private const val SERVICE_ID = "com.example.collisionavoidancesystem"
    private lateinit var connectionsClient: ConnectionsClient
    private var deviceId: String = ""

    // Keep track of connected endpoints manually
    private val connectedEndpoints = mutableSetOf<String>()

    private val _partners = MutableStateFlow<List<VehicleData>>(emptyList())
    val partners: StateFlow<List<VehicleData>> = _partners

    fun init(context: Context) {
        connectionsClient = Nearby.getConnectionsClient(context)
    }

    fun startAdvertising(id: String) {
        deviceId = id
        connectionsClient.startAdvertising(
            id,
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnSuccessListener {
            Log.d("Nearby", "Advertising started")
        }.addOnFailureListener {
            Log.e("Nearby", "Advertising failed: ${it.message}")
        }
    }

    fun startDiscovery() {
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnSuccessListener {
            Log.d("Nearby", "Discovery started")
        }.addOnFailureListener {
            Log.e("Nearby", "Discovery failed: ${it.message}")
        }
    }

    fun sendMyData(vehicle: VehicleData) {
        val json = JSONObject().apply {
            put("id", vehicle.id)
            put("lat", vehicle.latitude)
            put("lon", vehicle.longitude)
        }
        val payload = Payload.fromBytes(json.toString().toByteArray())

        // Send to all connected endpoints
        connectedEndpoints.forEach { endpoint ->
            connectionsClient.sendPayload(endpoint, payload)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto accept connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            Log.d("Nearby", "Connection initiated with $endpointId")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d("Nearby", "Connected: $endpointId")
                connectedEndpoints.add(endpointId) // ✅ track endpoint
            } else {
                Log.e("Nearby", "Connection failed: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("Nearby", "Disconnected: $endpointId")
            connectedEndpoints.remove(endpointId) // ✅ remove when disconnected
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d("Nearby", "Endpoint found: $endpointId (${info.endpointName})")
            connectionsClient.requestConnection(deviceId, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("Nearby", "Endpoint lost: $endpointId")
            connectedEndpoints.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val msg = JSONObject(String(it))
                val data = VehicleData(
                    id = msg.getString("id"),
                    latitude = msg.getDouble("lat"),
                    longitude = msg.getDouble("lon")
                )
                // Replace existing data for same id
                _partners.value = _partners.value.filterNot { it.id == data.id } + data
                Log.d("Nearby", "Received data from ${data.id}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can be used to track progress if sending large files
        }
    }
}
