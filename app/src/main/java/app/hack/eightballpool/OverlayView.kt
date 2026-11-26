package app.hack.eightballpool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class OverlayView(context: Context) : View(context) {

    var tableBounds: TableBounds? = null
    var shotData: ShotRenderData? = null
    var showCalibration: Boolean = false
    var isHiddenMode: Boolean = false
    var isCaptureBlank: Boolean = false
    var tapPromptText: String? = null
    var lineAlpha: Int = 245
    var nudgeTick: Int = 0

    private val winLoc = IntArray(2)
    private val nudgePaint = Paint()

    private val calOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val calBouncePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val tapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dashedCuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    private val cueEdgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
    }

    private val ghostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ghostRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }

    private val pocketGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Invisible 1x1 pixel toggle guarantees SurfaceFlinger emits fresh frame on static screens
        nudgePaint.color = Color.argb(1 + (nudgeTick and 1), 0, 0, 0)
        canvas.drawRect(0f, 0f, 2f, 2f, nudgePaint)

        if (isCaptureBlank || isHiddenMode) return
        val table = tableBounds ?: return

        getLocationOnScreen(winLoc)
        canvas.save()
        canvas.translate(-winLoc[0].toFloat(), -winLoc[1].toFloat())

        try {
            // 1. Calibration Frame
            if (showCalibration) {
                canvas.drawRect(table.left, table.top, table.right, table.bottom, calOuterPaint)
                canvas.drawRect(table.bounceLeft, table.bounceTop, table.bounceRight, table.bounceBottom, calBouncePaint)
                for (p in table.pockets) {
                    val pr = if (p.isCorner) table.cornerPotRadius else table.sidePotHalfWidth
                    canvas.drawCircle(p.x, p.y, pr, calOuterPaint)
                }
                drawTopBadge(
                    canvas,
                    table,
                    "DRAG ORANGE TL & BR TO CUSHION CORNERS • TAP SAVE",
                    Color.parseColor("#D97706")
                )
                return
            }

            // 2. Active Tap-To-Aim glow border (only shown while user is tapping/sliding)
            val prompt = tapPromptText
            if (prompt != null) {
                canvas.drawRect(table.left, table.top, table.right, table.bottom, tapBorderPaint)
            }

            val data = shotData
            if (data == null) {
                if (prompt != null) {
                    drawTopBadge(canvas, table, prompt, Color.parseColor("#0284C7"))
                }
                return
            }

            // 3. Draw Exact Edge-to-Edge White Cue Ball Lock Ring
            cueEdgeRingPaint.color = if (data.cueAutoDetected) Color.parseColor("#22C55E") else Color.WHITE
            cueEdgeRingPaint.alpha = lineAlpha
            canvas.drawCircle(data.cueX, data.cueY, data.cueRadius, cueEdgeRingPaint)
            dotFillPaint.color = cueEdgeRingPaint.color
            dotFillPaint.alpha = lineAlpha
            canvas.drawCircle(data.cueX, data.cueY, 3.5f, dotFillPaint)

            when (data.mode) {
                AimMode.WALL -> drawWallBounceMode(canvas, table, data, prompt)
                AimMode.CUT -> drawCutMode(canvas, table, data, prompt)
            }
        } finally {
            canvas.restore()
        }
    }

    private fun drawWallBounceMode(canvas: Canvas, table: TableBounds, data: ShotRenderData, prompt: String?) {
        val ray = data.cueRay ?: return
        val isPot = ray.pottedPocket != null
        val mainColor = if (isPot) Color.parseColor("#22C55E") else Color.parseColor("#00F5FF")

        // Draw primary segment (White Cue -> 1st Wall Hit) in crisp bright white so you align your cue straight on it
        if (ray.points.size >= 2) {
            val p0 = ray.points[0]
            val p1 = ray.points[1]
            outlinePaint.strokeWidth = 8.0f
            outlinePaint.alpha = (lineAlpha * 0.85f).toInt()
            canvas.drawLine(p0.first, p0.second, p1.first, p1.second, outlinePaint)

            rayPaint.color = Color.WHITE
            rayPaint.strokeWidth = 4.6f
            rayPaint.alpha = lineAlpha
            canvas.drawLine(p0.first, p0.second, p1.first, p1.second, rayPaint)
        }

        // Draw all bounce segments (1st Wall -> 2nd Wall -> 3rd Wall -> Pocket) in bright Neon Cyan / Green
        if (ray.points.size >= 3) {
            drawBounceRay(canvas, ray, startIdx = 1, color = mainColor, strokeW = 5.4f, dotR = table.ballR * 0.36f)
        } else if (ray.points.size == 2) {
            val pt = ray.points[1]
            dotFillPaint.color = mainColor
            dotFillPaint.alpha = lineAlpha
            canvas.drawCircle(pt.first, pt.second, table.ballR * 0.36f, dotFillPaint)
            canvas.drawCircle(pt.first, pt.second, table.ballR * 0.36f, ringWhitePaint)
        }

        // Exact Ball-Size Ghost Circle at 1st Wall Impact Point (align game's white circle inside this ring!)
        if (data.ghostX != null && data.ghostY != null) {
            ghostFillPaint.color = Color.argb(45, 0, 245, 255)
            canvas.drawCircle(data.ghostX, data.ghostY, table.ballR, ghostFillPaint)
            ghostRingPaint.color = mainColor
            ghostRingPaint.alpha = lineAlpha
            canvas.drawCircle(data.ghostX, data.ghostY, table.ballR, ghostRingPaint)
        }

        if (isPot) {
            highlightPocket(canvas, table, ray.pottedPocket!!, Color.parseColor("#22C55E"))
        }

        val badgeText = prompt ?: when {
            isPot -> "BOUNCE POT ${ray.pottedPocket} (${ray.bounceCount}B) • GAME TOUCH FREE"
            data.cueAutoDetected -> "WHITE LOCKED • ${ray.bounceCount}x BOUNCE • GAME TOUCH FREE"
            else -> "${ray.bounceCount}x WALL BOUNCE • GAME TOUCH FREE"
        }
        val badgeColor = when {
            prompt != null -> Color.parseColor("#0284C7")
            isPot -> Color.parseColor("#16A34A")
            else -> Color.parseColor("#0F766E")
        }
        drawTopBadge(canvas, table, badgeText, badgeColor)
    }

    private fun drawCutMode(canvas: Canvas, table: TableBounds, data: ShotRenderData, prompt: String?) {
        val gx = data.ghostX
        val gy = data.ghostY
        val objRay = data.objectRay
        val cueRay = data.cueRay
        val objPot = objRay?.pottedPocket
        val cuePot = cueRay?.pottedPocket

        // White Cue -> Ghost Ball
        if (gx != null && gy != null) {
            outlinePaint.strokeWidth = 7.5f
            outlinePaint.alpha = (lineAlpha * 0.85f).toInt()
            canvas.drawLine(data.cueX, data.cueY, gx, gy, outlinePaint)

            dashedCuePaint.color = if (data.isImpossibleCut) Color.parseColor("#EF4444") else Color.WHITE
            dashedCuePaint.alpha = lineAlpha
            canvas.drawLine(data.cueX, data.cueY, gx, gy, dashedCuePaint)
        }

        // White 90-deg Deflection (Gold, Red on Scratch)
        if (cueRay != null && cueRay.points.size >= 2) {
            val cueColor = if (cuePot != null) Color.parseColor("#EF4444") else Color.parseColor("#FACC15")
            drawBounceRay(canvas, cueRay, startIdx = 0, color = cueColor, strokeW = 4.0f, dotR = table.ballR * 0.30f)
            if (cuePot != null) highlightPocket(canvas, table, cuePot, Color.parseColor("#EF4444"))
        }

        // Target Ball Extended Path + Bounces (Green on Pot, Cyan otherwise)
        if (objRay != null && objRay.points.size >= 2) {
            val objColor = when {
                data.isImpossibleCut -> Color.parseColor("#EF4444")
                objPot != null -> Color.parseColor("#22C55E")
                else -> Color.parseColor("#00F5FF")
            }
            drawBounceRay(canvas, objRay, startIdx = 0, color = objColor, strokeW = 5.6f, dotR = table.ballR * 0.38f)
            if (objPot != null) highlightPocket(canvas, table, objPot, Color.parseColor("#22C55E"))
        }

        // Ghost Ball Ring
        if (gx != null && gy != null) {
            val gColor = if (data.isImpossibleCut) Color.parseColor("#EF4444") else Color.parseColor("#22D3EE")
            ghostFillPaint.color = if (data.isImpossibleCut) Color.argb(60, 239, 68, 68) else Color.argb(45, 34, 211, 238)
            canvas.drawCircle(gx, gy, table.ballR, ghostFillPaint)
            ghostRingPaint.color = gColor
            ghostRingPaint.alpha = lineAlpha
            canvas.drawCircle(gx, gy, table.ballR, ghostRingPaint)
        }

        val cutStr = data.cutAngleDeg?.let { "${it.toInt()}°" } ?: ""
        val badgeText = prompt ?: when {
            data.isImpossibleCut -> "IMPOSSIBLE CUT ($cutStr)"
            cuePot != null -> "WHITE SCRATCH ($cuePot)! • $cutStr"
            objPot != null -> "POT $objPot • $cutStr • WHITE SAFE"
            else -> "CUT EXTEND • $cutStr • WHITE SAFE"
        }
        val badgeColor = when {
            prompt != null -> Color.parseColor("#0284C7")
            data.isImpossibleCut || cuePot != null -> Color.parseColor("#DC2626")
            objPot != null -> Color.parseColor("#16A34A")
            else -> Color.parseColor("#0F766E")
        }
        drawTopBadge(canvas, table, badgeText, badgeColor)
    }

    private fun drawBounceRay(
        canvas: Canvas,
        ray: BounceRay,
        startIdx: Int,
        color: Int,
        strokeW: Float,
        dotR: Float
    ) {
        if (ray.points.size <= startIdx + 1) return
        val path = Path()
        path.moveTo(ray.points[startIdx].first, ray.points[startIdx].second)
        for (i in (startIdx + 1) until ray.points.size) {
            path.lineTo(ray.points[i].first, ray.points[i].second)
        }

        outlinePaint.strokeWidth = strokeW + 3.4f
        outlinePaint.alpha = (lineAlpha * 0.85f).toInt()
        canvas.drawPath(path, outlinePaint)

        rayPaint.color = color
        rayPaint.strokeWidth = strokeW
        rayPaint.alpha = lineAlpha
        canvas.drawPath(path, rayPaint)

        dotFillPaint.color = color
        dotFillPaint.alpha = lineAlpha
        ringWhitePaint.alpha = lineAlpha
        for (i in (startIdx + 1) until ray.points.size) {
            val pt = ray.points[i]
            canvas.drawCircle(pt.first, pt.second, dotR, dotFillPaint)
            canvas.drawCircle(pt.first, pt.second, dotR, ringWhitePaint)
        }
    }

    private fun highlightPocket(canvas: Canvas, table: TableBounds, pocketId: String, color: Int) {
        val p = table.pockets.firstOrNull { it.id == pocketId } ?: return
        pocketGlowPaint.color = color
        pocketGlowPaint.alpha = lineAlpha
        canvas.drawCircle(p.x, p.y, table.ballR * 1.98f, pocketGlowPaint)
    }

    private fun drawTopBadge(canvas: Canvas, table: TableBounds, text: String, bgColor: Int) {
        val left = table.left + table.width * 0.12f
        val right = table.left + table.width * 0.88f
        val top = (table.top - 48f).coerceAtLeast(6f)
        val bottom = top + 36f
        badgeBgPaint.color = bgColor
        badgeBgPaint.alpha = (lineAlpha * 0.92f).toInt()
        canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, badgeBgPaint)
        canvas.drawText(text, (left + right) * 0.5f, bottom - 10f, badgeTextPaint)
    }
}
