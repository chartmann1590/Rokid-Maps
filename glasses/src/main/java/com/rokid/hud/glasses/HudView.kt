package com.rokid.hud.glasses

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*

class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TILE_SIZE = 256
        private const val MAP_ZOOM = 16
    }

    private val hudGreen = Color.parseColor("#00FF00")
    private val hudDimGreen = Color.parseColor("#008800")
    private val hudDarkGreen = Color.parseColor("#004400")

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.6f, 0.1f, 0.05f, 0f, 0f,
            0.15f, 1.0f, 0.15f, 0f, 10f,
            0.05f, 0.1f, 0.6f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE; strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600FF88"); style = Paint.Style.STROKE; strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 20f
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 16f
    }
    private val notifTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 15f
    }
    private val notifBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 13f
    }
    private val separatorPaint = Paint().apply {
        color = hudDarkGreen; strokeWidth = 1f
    }
    private val bgPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }
    private val mapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.FILL
    }

    var state: HudState = HudState()
        set(value) { field = value; postInvalidate() }

    var tileManager: TileManager? = null
    var onLayoutToggle: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onLayoutToggle?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        when (state.layoutMode) {
            MapLayoutMode.FULL_SCREEN -> drawFullScreenLayout(canvas, w, h)
            MapLayoutMode.SMALL_CORNER -> drawSmallCornerLayout(canvas, w, h)
            MapLayoutMode.MINI_BOTTOM -> drawMiniBottomLayout(canvas, w, h)
            MapLayoutMode.MINI_SPLIT -> drawMiniSplitLayout(canvas, w, h)
        }
        drawStatusBar(canvas, w)
        drawModeIndicator(canvas, w)
    }

    // ── Full-screen: map top 72%, text bottom 28% ─────────────────────────

    private fun drawFullScreenLayout(canvas: Canvas, w: Float, h: Float) {
        val mapH = h * 0.72f
        val pad = 8f
        drawLiveMap(canvas, pad, pad, w - 2 * pad, mapH - 2 * pad)
        canvas.drawRect(pad, pad, w - pad, mapH - pad, mapBorderPaint)
        drawCompass(canvas, w - 50f, 50f, 36f)

        val textTop = mapH + 4f
        drawDirections(canvas, pad, textTop, w - 2 * pad)
        val dirH = 44f
        drawInfoArea(canvas, pad, textTop + dirH, w - 2 * pad, h - textTop - dirH - pad)
    }

    // ── Mini bottom (phone toggle): map 25% at bottom, direction+distance at bottom, no notifications ─

    private fun drawMiniBottomLayout(canvas: Canvas, w: Float, h: Float) {
        val dirStripH = 32f
        val mapHeight = h * 0.25f - dirStripH
        val mapTop = h - h * 0.25f
        val pad = 6f
        drawLiveMap(canvas, pad, mapTop + pad, w - 2 * pad, mapHeight - pad)
        canvas.drawRect(pad, mapTop + pad, w - pad, mapTop + mapHeight, mapBorderPaint)
        drawCompass(canvas, w - 32f, mapTop + 20f, 20f)
        val dirTop = h - dirStripH - 4f
        drawDirections(canvas, pad, dirTop, w - 2 * pad)
    }

    // ── Mini split: bottom 25% — map left, directions right ───────────────

    private fun drawMiniSplitLayout(canvas: Canvas, w: Float, h: Float) {
        val stripH = h * 0.25f
        val stripTop = h - stripH
        val pad = 6f
        val halfW = w / 2f

        // Left half: map
        drawLiveMap(canvas, pad, stripTop + pad, halfW - 2 * pad, stripH - 2 * pad)
        canvas.drawRect(pad, stripTop + pad, halfW - pad, stripTop + stripH - pad, mapBorderPaint)
        drawCompass(canvas, halfW - pad - 20f, stripTop + pad + 20f, 16f)

        // Right half: directions
        val textLeft = halfW + pad
        val textW = halfW - 2 * pad
        val centerY = stripTop + stripH / 2f

        if (state.instruction.isBlank()) {
            val p = Paint(textPaint).apply { textSize = 16f }
            canvas.drawText("Waiting for nav...", textLeft, centerY + 6f, p)
        } else {
            val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0
            if (isArrived) {
                val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 22f
                }
                val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 16f
                }
                canvas.drawText("\u2713", textLeft, centerY - 4f, checkPaint)
                canvas.drawText("Arrived!", textLeft + 28f, centerY - 4f, arrivedPaint)
            } else {
                val sym = maneuverToArrow(state.maneuver)
                val dist = formatDistance(state.stepDistance)

                // Maneuver arrow + distance
                val arrowPaintL = Paint(textPaint).apply { textSize = 28f }
                val distPaintL = Paint(textPaint).apply { textSize = 20f }
                canvas.drawText(sym, textLeft, centerY - 6f, arrowPaintL)
                canvas.drawText(dist, textLeft + 36f, centerY - 6f, distPaintL)

                // Instruction text (wrapped to fit)
                val instrPaint = Paint(smallTextPaint).apply { textSize = 14f; color = Color.parseColor("#AAFFAA") }
                val instrTrunc = truncateText(state.instruction, instrPaint, textW)
                canvas.drawText(instrTrunc, textLeft, centerY + 16f, instrPaint)
            }
        }
    }

    // ── Small-corner: text left 62%, map bottom-right 38% ─────────────────

    private fun drawSmallCornerLayout(canvas: Canvas, w: Float, h: Float) {
        val mapW = w * 0.38f
        val mapH = h * 0.42f
        val mapLeft = w - mapW - 6f
        val mapTop = h - mapH - 6f
        val pad = 8f

        drawLiveMap(canvas, mapLeft, mapTop, mapW, mapH)
        canvas.drawRect(mapLeft, mapTop, mapLeft + mapW, mapTop + mapH, mapBorderPaint)
        drawCompass(canvas, mapLeft + mapW - 30f, mapTop - 36f, 28f)

        val textW = w - mapW - 20f
        drawDirections(canvas, pad, pad, textW)
        val dirH = 44f
        drawInfoArea(canvas, pad, pad + dirH, textW, h - dirH - 2 * pad)
    }

    // ── Live tile map with bearing rotation ───────────────────────────────

    private fun drawLiveMap(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        canvas.save()
        canvas.clipRect(left, top, left + w, top + h)

        val cx = left + w / 2
        val cy = top + h / 2

        if (state.latitude == 0.0 && state.longitude == 0.0) {
            val p = Paint(smallTextPaint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("Waiting for GPS...", cx, cy, p)
            canvas.restore()
            return
        }

        val n = (1 shl MAP_ZOOM).toDouble()
        val fracX = (state.longitude + 180.0) / 360.0 * n
        val latRad = Math.toRadians(state.latitude)
        val fracY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        val gpxX = fracX * TILE_SIZE
        val gpxY = fracY * TILE_SIZE

        // Rotate map so heading points up
        canvas.save()
        canvas.rotate(-state.bearing, cx, cy)

        drawTiles(canvas, cx, cy, gpxX, gpxY, w, h, n)

        if (state.waypoints.size >= 2) {
            drawRouteOnTiles(canvas, cx, cy, gpxX, gpxY, n)
        }

        canvas.restore() // undo rotation

        // Player arrow always points up (direction of travel)
        drawPlayerArrow(canvas, cx, cy)

        canvas.restore() // undo clip
    }

    private fun drawTiles(
        canvas: Canvas, cx: Float, cy: Float,
        gpxX: Double, gpxY: Double, viewW: Float, viewH: Float, n: Double
    ) {
        val diag = sqrt(viewW * viewW + viewH * viewH)
        val margin = (diag / 2 + TILE_SIZE).toInt()
        val tilesMargin = margin / TILE_SIZE + 1
        val centerTileX = floor(gpxX / TILE_SIZE).toInt()
        val centerTileY = floor(gpxY / TILE_SIZE).toInt()
        val maxTile = n.toInt()

        for (dy in -tilesMargin..tilesMargin) {
            for (dx in -tilesMargin..tilesMargin) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                if (ty < 0 || ty >= maxTile) continue
                val wrappedTx = ((tx % maxTile) + maxTile) % maxTile

                val screenX = (tx * TILE_SIZE - gpxX + cx).toFloat()
                val screenY = (ty * TILE_SIZE - gpxY + cy).toFloat()

                val bmp = tileManager?.getTile(MAP_ZOOM, wrappedTx, ty)
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, screenX, screenY, tilePaint)
                }
            }
        }
    }

    private fun drawRouteOnTiles(
        canvas: Canvas, cx: Float, cy: Float,
        gpxX: Double, gpxY: Double, n: Double
    ) {
        val path = Path()
        var first = true
        for (wp in state.waypoints) {
            val wpFracX = (wp.longitude + 180.0) / 360.0 * n
            val wpLatRad = Math.toRadians(wp.latitude)
            val wpFracY = (1.0 - ln(tan(wpLatRad) + 1.0 / cos(wpLatRad)) / Math.PI) / 2.0 * n
            val sx = (wpFracX * TILE_SIZE - gpxX + cx).toFloat()
            val sy = (wpFracY * TILE_SIZE - gpxY + cy).toFloat()
            if (first) { path.moveTo(sx, sy); first = false } else { path.lineTo(sx, sy) }
        }
        canvas.drawPath(path, routeGlowPaint)
        canvas.drawPath(path, routePaint)
    }

    private fun drawPlayerArrow(canvas: Canvas, cx: Float, cy: Float) {
        val arrowPath = Path().apply {
            moveTo(cx, cy - 16f)
            lineTo(cx - 10f, cy + 12f)
            lineTo(cx, cy + 5f)
            lineTo(cx + 10f, cy + 12f)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.drawPath(arrowPath, arrowOutlinePaint)
    }

    // ── Compass ───────────────────────────────────────────────────────────

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, compassPaint)
        canvas.save()
        canvas.rotate(-state.bearing, cx, cy)
        val nPaint = Paint(textPaint).apply { textSize = 14f; textAlign = Paint.Align.CENTER; color = hudGreen }
        canvas.drawText("N", cx, cy - radius + 14f, nPaint)
        canvas.drawLine(cx, cy, cx, cy - radius + 4f, dotPaint)
        canvas.restore()
        val degPaint = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.CENTER }
        canvas.drawText("${state.bearing.toInt()}°", cx, cy + radius + 14f, degPaint)
    }

    // ── Directions ────────────────────────────────────────────────────────

    private fun drawDirections(canvas: Canvas, left: Float, top: Float, maxWidth: Float) {
        if (state.instruction.isBlank()) {
            val p = Paint(textPaint).apply { textSize = 18f }
            canvas.drawText("Waiting for navigation...", left, top + 20f, p)
            return
        }

        val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0

        if (isArrived) {
            val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            val cx = left + maxWidth / 2
            canvas.drawText("\u2713", cx, top + 24f, checkPaint)
            canvas.drawText("You have arrived!", cx, top + 52f, arrivedPaint)
            return
        }

        val sym = maneuverToArrow(state.maneuver)
        val dist = formatDistance(state.stepDistance)
        val instrPaint = Paint(textPaint).apply { textSize = 20f }
        val distPaint = Paint(textPaint).apply { textSize = 18f; textAlign = Paint.Align.RIGHT }
        val instrTrunc = truncateText("$sym ${state.instruction}", instrPaint, maxWidth - 80f)
        canvas.drawText(instrTrunc, left, top + 22f, instrPaint)
        canvas.drawText(dist, left + maxWidth, top + 22f, distPaint)
        canvas.drawLine(left, top + 34f, left + maxWidth, top + 34f, separatorPaint)
    }

    // ── Notifications / upcoming steps area ──────────────────────────────

    private fun drawInfoArea(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        if (!state.streamNotifications && state.showUpcomingSteps && state.allSteps.isNotEmpty()) {
            drawUpcomingSteps(canvas, left, top, maxWidth, maxHeight)
        } else if (state.streamNotifications) {
            drawNotifications(canvas, left, top, maxWidth, maxHeight)
        }
        // If streamNotifications is off and showUpcomingSteps is off, draw nothing
    }

    private fun drawNotifications(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        if (state.notifications.isEmpty()) return
        var y = top + 6f
        val lineHeight = 30f
        for (notif in state.notifications) {
            if (y + lineHeight > top + maxHeight) break
            val title = notif.title ?: "Notification"
            val body = notif.text ?: ""
            canvas.drawText(truncateText(title, notifTitlePaint, maxWidth - 10f), left + 2f, y + 14f, notifTitlePaint)
            if (body.isNotBlank()) {
                canvas.drawText(truncateText(body, notifBodyPaint, maxWidth - 10f), left + 2f, y + 28f, notifBodyPaint)
            }
            y += lineHeight
            canvas.drawLine(left, y, left + maxWidth, y, separatorPaint)
            y += 4f
        }
    }

    private fun drawUpcomingSteps(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        // Show steps after the current one (current step is already in the directions bar)
        val startIdx = state.currentStepIndex + 1
        if (startIdx >= state.allSteps.size) return

        val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 14f
        }
        val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 13f; textAlign = Paint.Align.RIGHT
        }

        var y = top + 6f
        val lineHeight = 24f
        for (i in startIdx until state.allSteps.size) {
            if (y + lineHeight > top + maxHeight) break
            val step = state.allSteps[i]
            val sym = maneuverToArrow(step.maneuver)
            val dist = formatDistance(step.distance)
            val instr = truncateText("$sym ${step.instruction}", stepPaint, maxWidth - 70f)
            canvas.drawText(instr, left + 2f, y + 14f, stepPaint)
            canvas.drawText(dist, left + maxWidth - 2f, y + 14f, distPaint)
            y += lineHeight
            canvas.drawLine(left, y, left + maxWidth, y, separatorPaint)
            y += 3f
        }
    }

    // ── Status bar (BT + WiFi) ────────────────────────────────────────────

    private fun drawStatusBar(canvas: Canvas, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; textAlign = Paint.Align.LEFT
        }
        val y = 14f
        var x = 8f

        p.color = if (state.btConnected) hudGreen else Color.parseColor("#FF4444")
        val btLabel = if (state.btConnected) "BT:ON" else "BT:--"
        canvas.drawText(btLabel, x, y, p)
        x += p.measureText(btLabel) + 10f

        p.color = if (state.wifiConnected) hudGreen else hudDimGreen
        val wifiLabel = if (state.wifiConnected) "WiFi:ON" else "WiFi:--"
        canvas.drawText(wifiLabel, x, y, p)
        x += p.measureText(wifiLabel) + 10f

        if (state.batteryLevel >= 0) {
            p.color = when {
                state.batteryLevel <= 15 -> Color.parseColor("#FF4444")
                state.batteryLevel <= 30 -> Color.parseColor("#FFB300")
                else -> hudGreen
            }
            val batLabel = "BAT:${state.batteryLevel}%"
            canvas.drawText(batLabel, x, y, p)
        }
    }

    // ── Mode indicator ────────────────────────────────────────────────────

    private fun drawModeIndicator(canvas: Canvas, w: Float) {
        val label = when (state.layoutMode) {
            MapLayoutMode.FULL_SCREEN -> "[ FULL ]"
            MapLayoutMode.SMALL_CORNER -> "[ CORNER ]"
            MapLayoutMode.MINI_BOTTOM -> "[ STRIP ]"
            MapLayoutMode.MINI_SPLIT -> "[ SPLIT ]"
        }
        val p = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.RIGHT }
        canvas.drawText(label, w - 8f, 14f, p)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun truncateText(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("...") > maxW) end--
        return text.substring(0, end) + "..."
    }

    private fun formatDistance(meters: Double): String {
        if (meters < 1) return ""
        return if (state.useImperial) {
            val feet = meters * 3.28084
            val miles = meters / 1609.344
            when {
                miles >= 0.1 -> String.format("%.1f mi", miles)
                else -> String.format("%.0f ft", feet)
            }
        } else {
            when {
                meters >= 1000 -> String.format("%.1f km", meters / 1000)
                else -> String.format("%.0f m", meters)
            }
        }
    }

    private fun maneuverToArrow(maneuver: String): String = when {
        maneuver.contains("left", true) -> "←"
        maneuver.contains("right", true) -> "→"
        maneuver.contains("uturn", true) -> "↩"
        maneuver.contains("straight", true) -> "↑"
        maneuver.contains("arrive", true) -> "●"
        maneuver.contains("depart", true) -> "▶"
        maneuver.contains("merge", true) -> "⤵"
        maneuver.contains("ramp", true) -> "↗"
        maneuver.contains("fork", true) -> "⑂"
        else -> "↑"
    }
}
