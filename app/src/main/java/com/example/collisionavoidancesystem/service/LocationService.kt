package com.example.collisionavoidancesystem.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.collisionavoidancesystem.model.VehicleData
import com.google.android.gms.location.*

object LocationService {
    private lateinit var fusedLocationProvider: FusedLocationProviderClient
    private val _myVehicle = MutableLiveData<VehicleData>()
    val myVehicle: LiveData<VehicleData> = _myVehicle

    private var deviceId: String = ""

    @SuppressLint("MissingPermission")
    fun start(context: Context, id: String) {
        deviceId = id
        fusedLocationProvider = LocationServices.getFusedLocationProviderClient(context)

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).build()

        fusedLocationProvider.requestLocationUpdates(
            request,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc: Location = result.lastLocation ?: return
                    _myVehicle.postValue(
                        VehicleData(id = deviceId, latitude = loc.latitude, longitude = loc.longitude)
                    )
                }
            },
            Looper.getMainLooper()
        )
    }
}
