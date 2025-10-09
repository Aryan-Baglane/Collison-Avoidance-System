package com.example.collisionavoidancesystem.model

data class VehicleData(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val speed: Float = 0f,           // m/s
    val heading: Float = 0f,         // degrees
    val role: VehicleRole = VehicleRole.MOVING,
    val isBaseStation: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
