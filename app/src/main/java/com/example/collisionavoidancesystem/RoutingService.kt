package com.example.collisionavoidancesystem



import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object RoutingService {
    private val client = OkHttpClient()

    suspend fun getRoadDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/$lon1,$lat1;$lon2,$lat2?overview=false"

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return@withContext Double.POSITIVE_INFINITY
        val json = JSONObject(response.body?.string() ?: return@withContext Double.POSITIVE_INFINITY)

        return@withContext try {
            json.getJSONArray("routes")
                .getJSONObject(0)
                .getDouble("distance") // meters
        } catch (e: Exception) {
            Double.POSITIVE_INFINITY
        }
    }
}
