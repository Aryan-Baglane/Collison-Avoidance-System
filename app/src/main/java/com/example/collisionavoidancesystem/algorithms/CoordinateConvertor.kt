package com.example.collisionavoidancesystem.algorithms

object CoordinateConverter {
    // Mocked WGS84 coordinates for the local coordinate system origin (0, 0)
    // Replace with the actual Lat/Lon of the starting point of your vehicle set for real deployment
    private const val ORIGIN_LAT = 28.6139  // Mock: Delhi, India
    private const val ORIGIN_LON = 77.2090

    // Simplistic conversion factors (Meters per degree). Must be refined for production.
    private const val M_PER_DEG_LAT = 110574.0 // Approximate
    private const val M_PER_DEG_LON = 111320.0 * 0.523 // Mocked factor for 28 deg latitude (cos(28))

    /**
     * Converts Local E-N (X, Y in meters) to Global WGS84 (Lat, Lon).
     */
    fun toWGS84(x: Float, y: Float): Pair<Double, Double> {
        val lat = ORIGIN_LAT + (y / M_PER_DEG_LAT)
        val lon = ORIGIN_LON + (x / M_PER_DEG_LON)
        return Pair(lat, lon)
    }

    /**
     * Converts Global WGS84 (Lat, Lon) to Local E-N (X, Y in meters).
     * This is the function that must be called when receiving raw Lat/Lon from GPS or V2V.
     */
    fun toLocal(lat: Double, lon: Double): Pair<Float, Float> {
        val dy = (lat - ORIGIN_LAT) * M_PER_DEG_LAT
        val dx = (lon - ORIGIN_LON) * M_PER_DEG_LON
        return Pair(dx.toFloat(), dy.toFloat())
    }
}
