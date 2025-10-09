package com.example.collisionavoidancesystem.model

import androidx.compose.ui.graphics.Color

enum class WarningLevel(val color: Color, val label: String) {
    SAFE(Color(0xFF4CAF50), "Safe Distance"), // Green
    PROXIMITY(Color(0xFFFF9800), "Proximity Alert"), // Orange
    COLLISION_RISK(Color(0xFFF44336), "COLLISION IMMINENT") // Red
}