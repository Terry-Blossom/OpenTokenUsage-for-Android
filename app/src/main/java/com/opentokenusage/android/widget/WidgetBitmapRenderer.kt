package com.opentokenusage.android.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Renders tiny hi-dpi ARGB bitmaps for Glance home-screen widgets so widgets display
 * OpenTokenUsage's signature uniform-pace vertical needle and threshold ticks.
 */
object WidgetBitmapRenderer {
    fun renderProgressBarBitmap(
        widthPx: Int = 360,
        heightPx: Int = 22,
        displayedFraction: Float,
        fillColorArgb: Int,
        trackColorArgb: Int,
        markerColorArgb: Int,
        tickColorArgb: Int,
        paceElapsedFraction: Float?,
        isLeftMode: Boolean,
        threshold1Pct: Int,
        threshold2Pct: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val trackHeight = 11f
        val topY = (heightPx - trackHeight) / 2f
        val bottomY = topY + trackHeight
        val radius = trackHeight / 2f
        val rect = RectF(0f, topY, widthPx.toFloat(), bottomY)

        // Track background
        paint.color = trackColorArgb
        canvas.drawRoundRect(rect, radius, radius, paint)

        // Alert threshold notches inside track
        paint.color = tickColorArgb
        paint.strokeWidth = 2f
        listOf(threshold1Pct, threshold2Pct)
            .filter { it in 1..99 }
            .forEach { pct ->
                val raw = pct / 100f
                val pos = if (isLeftMode) 1f - raw else raw
                val x = (widthPx * pos).coerceIn(3f, widthPx - 3f)
                canvas.drawLine(x, topY + 1.5f, x, bottomY - 1.5f, paint)
            }

        // Filled bar
        val clamped = displayedFraction.coerceIn(0f, 1f)
        if (clamped > 0.005f) {
            paint.color = fillColorArgb
            val fillWidth = (widthPx * clamped).coerceAtLeast(trackHeight)
            val fillRect = RectF(0f, topY, fillWidth, bottomY)
            canvas.drawRoundRect(fillRect, radius, radius, paint)
        }

        // Uniform-pace vertical needle
        if (paceElapsedFraction != null && paceElapsedFraction in 0.02f..0.99f) {
            val pos = if (isLeftMode) 1f - paceElapsedFraction else paceElapsedFraction
            val x = (widthPx * pos).coerceIn(3f, widthPx - 3f)
            paint.color = markerColorArgb
            paint.strokeWidth = 3.5f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, 2f, x, heightPx - 2f, paint)
        }

        return bmp
    }
}
