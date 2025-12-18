package org.meshtastic.feature.map.overlays

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.pow
import kotlin.math.sqrt

class CircleDrawingOverlay(
    private val onCircleFinished: (GeoPoint, Float) -> Unit // Callback: Center, Radius(m)
) : Overlay() {

    private var isDrawing = false
    private var circleCenter: GeoPoint? = null
    private val circleCenterPoint = Point()
    private val currentPoint = Point()

    // Preview Styles
    private val fillPaint = Paint().apply {
        color = Color.argb(80, 0, 100, 255) // Semi-transparent Blue
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || !isDrawing || canvas == null || mapView == null || circleCenter == null) return

        val proj = mapView.projection
        // Convert GeoPoint to Screen Pixels
        proj.toPixels(circleCenter, circleCenterPoint)

        // Calculate Pixel Radius (Pythagoras)
        val radiusPixels = sqrt(
            (circleCenterPoint.x - currentPoint.x).toDouble().pow(2.0) +
                    (circleCenterPoint.y - currentPoint.y).toDouble().pow(2.0)
        ).toFloat()

        // Draw the preview
        canvas.drawCircle(circleCenterPoint.x.toFloat(), circleCenterPoint.y.toFloat(), radiusPixels, fillPaint)
        canvas.drawCircle(circleCenterPoint.x.toFloat(), circleCenterPoint.y.toFloat(), radiusPixels, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent?, mapView: MapView?): Boolean {
        if (event == null || mapView == null) return false
        val proj = mapView.projection

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                // Capture Center
                circleCenter = proj.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                circleCenterPoint.set(event.x.toInt(), event.y.toInt())
                currentPoint.set(event.x.toInt(), event.y.toInt())
                mapView.invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    // Update Radius
                    currentPoint.set(event.x.toInt(), event.y.toInt())
                    mapView.invalidate()
                    true
                } else false
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    isDrawing = false

                    // Calculate final radius in Meters
                    val edgeGeo = proj.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                    val radiusInMeters = circleCenter!!.distanceToAsDouble(edgeGeo).toFloat()

                    // Send data back to screen
                    if (radiusInMeters > 0) {
                        onCircleFinished(circleCenter!!, radiusInMeters)
                    }

                    mapView.invalidate()
                    true
                } else false
            }
            else -> false
        }
    }
}