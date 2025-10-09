package com.example.collisionavoidancesystem.model

data class RelativeMetrics(
    val partnerId: String,
    val distance: Float = Float.MAX_VALUE, // meters
    val closingSpeed: Float = 0f,         // m/s
    val ttc: Float = Float.MAX_VALUE,     // Time-to-Collision (seconds)
    val warningLevel: WarningLevel = WarningLevel.SAFE
)
