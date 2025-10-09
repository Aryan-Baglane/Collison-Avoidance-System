package com.example.collisionavoidancesystem.service



data class PartnerDto(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val heading: Float,
    val role: String,
    val isBaseStation: Boolean,
    val timestamp: Long,
    val distance_m: Double = 0.0,
    val closing_speed_mps: Double = 0.0,
    val ttc_s: Double = 0.0,
    val warning: String = "SAFE" // <- this must exist
)
