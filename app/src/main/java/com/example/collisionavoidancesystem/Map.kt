package com.example.collisionavoidancesystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.collisionavoidancesystem.model.VehicleData
import com.example.collisionavoidancesystem.service.PartnerDto
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// Extension function to scale drawable
fun Drawable.toScaledBitmap(width: Int, height: Int): Bitmap {
    val bitmap = (this as BitmapDrawable).bitmap
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

@Composable
fun LiveMapOSM(
    context: Context,
    myVehicle: VehicleData,
    partners: List<PartnerDto>
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
                controller.setCenter(GeoPoint(myVehicle.latitude, myVehicle.longitude))
            }
        },
        update = { mapView ->
            mapView.overlays.clear()

            // My vehicle marker (blue)
            Marker(mapView).apply {
                position = GeoPoint(myVehicle.latitude, myVehicle.longitude)
                title = "Me (${myVehicle.id})"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                // scale icon to 64x64 pixels
                icon = context.getDrawable(R.drawable.car)?.toScaledBitmap(64, 64)?.let {
                    BitmapDrawable(context.resources, it)
                }

                mapView.overlays.add(this)
            }

            // Partners markers
            partners.forEach { p ->
                val marker = Marker(mapView).apply {
                    position = GeoPoint(p.latitude, p.longitude)
                    title = "Partner ${p.id} (${p.warning})"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }

                // choose icon
                val drawable: Drawable? = when (p.warning) {
                    "COLLISION_RISK" -> context.getDrawable(R.drawable.car)
                    "PROXIMITY" -> context.getDrawable(R.drawable.car)
                    else -> if (p.isBaseStation) context.getDrawable(R.drawable.antenna) else context.getDrawable(R.drawable.car)
                }

                marker.icon = drawable?.toScaledBitmap(64, 64)?.let { BitmapDrawable(context.resources, it) }

                mapView.overlays.add(marker)
            }

            mapView.invalidate()
        }
    )
}
