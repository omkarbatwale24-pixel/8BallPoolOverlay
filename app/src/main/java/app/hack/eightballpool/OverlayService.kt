package app.hack.eightballpool

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "pool_fast_tap_channel"
        private const val NOTIF_ID = 1099
        private const val PREFS_NAME = "omkar_pool_fast_tap_prefs"
        private const val KEY_LEFT = "left_ratio"
        private const val KEY_RIGHT = "right_ratio"
        private const val KEY_TOP = "top_ratio"
        private const val KEY_BOTTOM = "bottom_ratio"

        @Volatile
        var isRunning: Boolean = false
    }

    private enum class TapAction {
        NONE,
        TAP_AIM_WALL,
        TAP_PLACE_CUE,
        TAP_PLACE_TARGET
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private lateinit var tapCatcherView: View
    private lateinit var hudLayout: LinearLayout
    private lateinit var hudParams: WindowManager.LayoutParams

    private lateinit var tapBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var bounceBtn: Button
    private lateinit var cueBtn: Button
    private lateinit var calBtn: Button
    private lateinit var hideBtn: Button

    // Small optional drag handles (only their tiny circles capture touch; game table stays 100% FREE!)
    private lateinit var aimDotHandle: FloatingHandle
    private lateinit var targetDotHandle: FloatingHandle
    private lateinit var calTLHandle: FloatingHandle
    private lateinit var calBRHandle: FloatingHandle
    private val allHandles = mutableListOf<FloatingHandle>()

    // Single-permission persistent MediaProjection (for Auto White Ball Edge-to-Edge Detection)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureW = 0
    private var captureH = 0
    private var captureDpi = 320

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureThread = HandlerThread("CueCaptureThread").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private val requestSeq = AtomicLong(0L)
    @Volatile
    private var pendingSeq = 0L
    @Volatile
    private var captureAfterMs = 0L
    @Volatile
    private var pendingCallback: ((Bitmap) -> Unit)? = null

    private var tableBounds: TableBounds = TableBounds(2400f, 1080f)
    private var currentMode: AimMode = AimMode.WALL
    private var activeTapAction: TapAction = TapAction.NONE

    private var cueX = 750f
    private var cueY = 540f
    private var cueRadius = 21f
    private var cueAutoDetected = false

    private var targetX = 1400f
    private var targetY = 480f
    private var aimX = 1650f
    private var aimY = 260f

    private val bounceOptions = intArrayOf(1, 2, 3, 4)
    private var bounceIndex = 1 // Default: 2B

    private var isCalibrating = false
    private var isHidden = false
    private val alphaLevels = floatArrayOf(1.0f, 0.72f, 0.42f)
    private var alphaIndex = 0

    // Continuous smooth fine-rotate when holding ◄ or ►
    private var holdDeltaDeg = 0f
    private val holdRotateRunnable = object : Runnable {
        override fun run() {
            if (holdDeltaDeg == 0f) return
            nudgeAimAngle(holdDeltaDeg)
            mainHandler.postDelayed(this, 45L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadSavedTableBounds()
        initDefaultPoints()
        setupAllWindows()
        recomputeAndDraw()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            startForegroundForProjection()
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                    }
                }, mainHandler)
            }
            syncVirtualDisplayWithoutRecreate()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadSavedTableBounds()
        initDefaultPoints()
        syncHandlesToPoints()
        recomputeAndDraw()
    }

    private fun startForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Pool Fast-Tap Overlay", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("8-Ball Fast-Tap Overlay Active")
            .setContentText("Tap TAP in game -> auto white ball + 1-tap wall bounce")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun getRealScreenSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun loadSavedTableBounds() {
        val (sw, sh, _) = getRealScreenSize()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val l = prefs.getFloat(KEY_LEFT, 0.1945f).coerceIn(0.05f, 0.45f)
        val r = prefs.getFloat(KEY_RIGHT, 0.8055f).coerceIn(0.55f, 0.96f)
        val t = prefs.getFloat(KEY_TOP, 0.2355f).coerceIn(0.05f, 0.45f)
        val b = prefs.getFloat(KEY_BOTTOM, 0.9125f).coerceIn(0.55f, 0.98f)
        tableBounds = TableBounds(sw.toFloat(), sh.toFloat(), l, r, t, b)
    }

    private fun saveCurrentTableBounds() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_LEFT, tableBounds.leftRatio)
            .putFloat(KEY_RIGHT, tableBounds.rightRatio)
            .putFloat(KEY_TOP, tableBounds.topRatio)
            .putFloat(KEY_BOTTOM, tableBounds.bottomRatio)
            .apply()
    }

    private fun initDefaultPoints() {
        val tb = tableBounds
        cueX = tb.left + tb.width * 0.28f
        cueY = tb.top + tb.height * 0.50f
        cueRadius = tb.ballR
        cueAutoDetected = false
        targetX = tb.left + tb.width * 0.58f
        targetY = tb.top + tb.height * 0.45f
        aimX = tb.left + tb.width * 0.72f
        aimY = tb.top + tb.ballR
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun applyCutoutAlways(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAllWindows() {
        val type = overlayWindowType()

        // WINDOW 1: Full-Screen 100% NON-TOUCHABLE Drawing Canvas (NEVER blocks game touch!)
        overlayView = OverlayView(this)
        overlayView.tableBounds = tableBounds
        val drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { applyCutoutAlways(it) }
        windowManager.addView(overlayView, drawParams)

        // WINDOW 2: Temporary 1-Shot Tap-and-Slide Sheet (GONE during gameplay -> 0% touch interference!)
        tapCatcherView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        val tapParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { applyCutoutAlways(it) }

        tapCatcherView.setOnTouchListener { _, event ->
            val rx = event.rawX
            val ry = event.rawY
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    handleTapSheetPoint(rx, ry, isRelease = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleTapSheetPoint(rx, ry, isRelease = true)
                    true
                }
                else -> false
            }
        }
        windowManager.addView(tapCatcherView, tapParams)

        // WINDOW 3: Tiny Precision Drag Handles (40dp open-center crosshairs)
        val dotSize = dpToPx(40f)
        aimDotHandle = createHandle("P", Color.parseColor("#22D3EE"), dotSize, isCorner = false) { cx, cy, isUp ->
            aimX = cx
            aimY = cy
            if (isUp && currentMode == AimMode.CUT) {
                val p = PoolGeometryEngine.findNearestPocket(cx, cy, tableBounds, tableBounds.ballR * 1.95f)
                if (p != null) {
                    aimX = p.x
                    aimY = p.y
                    aimDotHandle.moveCenterTo(p.x, p.y)
                }
            }
            recomputeAndDraw()
        }
        targetDotHandle = createHandle("T", Color.parseColor("#22C55E"), dotSize, isCorner = false) { cx, cy, _ ->
            targetX = cx
            targetY = cy
            recomputeAndDraw()
        }
        calTLHandle = createHandle("TL", Color.parseColor("#F97316"), dotSize, isCorner = true) { cx, cy, _ ->
            updateBoundsFromCalHandles(cx, cy, calBRHandle.centerX, calBRHandle.centerY)
        }
        calBRHandle = createHandle("BR", Color.parseColor("#F97316"), dotSize, isCorner = true) { cx, cy, _ ->
            updateBoundsFromCalHandles(calTLHandle.centerX, calTLHandle.centerY, cx, cy)
        }
        allHandles.addAll(listOf(aimDotHandle, targetDotHandle, calTLHandle, calBRHandle))
        syncHandlesToPoints()
        updateHandleVisibility()

        // WINDOW 4: Movable Translucent HUD Bar
        hudLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 15, 23, 42))
                cornerRadius = 26f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
        }

        val dragHandle = TextView(this).apply {
            text = "✥"
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(11, 5, 11, 5)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 30, 41, 59))
                cornerRadius = 14f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(3, 0, 4, 0)
            layoutParams = lp
        }

        // MAIN BUTTON: Auto-detects White Ball edge-to-edge -> 1-Tap & Slide anywhere on wall/table -> auto unlocks game touch!
        tapBtn = makeHudButton("⚡TAP", "#16A34A") {
            if (isCalibrating) toggleCalibration()
            if (activeTapAction != TapAction.NONE) {
                endTapCaptureMode()
            } else {
                startFastTapFlow()
            }
        }

        // Fine micro-adjust buttons (press or hold to move aim line up/down smoothly without touching table!)
        val leftFineBtn = makeHoldButton("◄", "#0284C7", -0.25f)
        val rightFineBtn = makeHoldButton("►", "#0284C7", +0.25f)

        bounceBtn = makeHudButton("${bounceOptions[bounceIndex]}B", "#7C3AED") {
            bounceIndex = (bounceIndex + 1) % bounceOptions.size
            bounceBtn.text = "${bounceOptions[bounceIndex]}B"
            recomputeAndDraw()
        }

        modeBtn = makeHudButton(currentMode.label, "#0891B2") {
            if (isCalibrating) toggleCalibration()
            endTapCaptureMode()
            currentMode = currentMode.next()
            modeBtn.text = currentMode.label
            updateHandleVisibility()
            recomputeAndDraw()
        }

        // Manual White Ball quick-place (in case hand covers cue ball)
        cueBtn = makeHudButton("C", "#4F46E5") {
            if (isCalibrating) toggleCalibration()
            beginTapCaptureMode(TapAction.TAP_PLACE_CUE, "TAP WHITE CUE BALL CENTER")
        }

        calBtn = makeHudButton("CAL", "#D97706") {
            toggleCalibration()
        }

        val alphaBtn = makeHudButton("α", "#0F766E") {
            alphaIndex = (alphaIndex + 1) % alphaLevels.size
            val a = alphaLevels[alphaIndex]
            hudLayout.alpha = a
            overlayView.lineAlpha = (a * 255).toInt().coerceIn(85, 255)
            allHandles.forEach { it.view.alpha = a }
            overlayView.invalidate()
        }

        hideBtn = makeHudButton("HIDE", "#475569") {
            isHidden = !isHidden
            hideBtn.text = if (isHidden) "SHOW" else "HIDE"
            if (isHidden) endTapCaptureMode()
            overlayView.isHiddenMode = isHidden
            updateHandleVisibility()
            overlayView.invalidate()
        }

        val closeBtn = makeHudButton("X", "#DC2626") {
            stopSelf()
        }

        hudLayout.addView(dragHandle)
        hudLayout.addView(tapBtn)
        hudLayout.addView(leftFineBtn)
        hudLayout.addView(rightFineBtn)
        hudLayout.addView(bounceBtn)
        hudLayout.addView(modeBtn)
        hudLayout.addView(cueBtn)
        hudLayout.addView(calBtn)
        hudLayout.addView(alphaBtn)
        hudLayout.addView(hideBtn)
        hudLayout.addView(closeBtn)

        val (sw, sh, _) = getRealScreenSize()
        hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw * 0.16f).toInt()
            y = (sh - 120).coerceAtLeast(0)
            applyCutoutAlways(this)
        }

        var initX = 0
        var initY = 0
        var initTouchX = 0f
        var initTouchY = 0f
        val hudDrag = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = hudParams.x
                    initY = hudParams.y
                    initTouchX = event.rawX
                    initTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (curW, curH, _) = getRealScreenSize()
                    val maxX = (curW - hudLayout.width).coerceAtLeast(0)
                    val maxY = (curH - hudLayout.height).coerceAtLeast(0)
                    hudParams.x = (initX + (event.rawX - initTouchX).toInt()).coerceIn(0, maxX)
                    hudParams.y = (initY + (event.rawY - initTouchY).toInt()).coerceIn(0, maxY)
                    try {
                        windowManager.updateViewLayout(hudLayout, hudParams)
                    } catch (_: Exception) { }
                    true
                }
                else -> false
            }
        }
        dragHandle.setOnTouchListener(hudDrag)
        hudLayout.setOnTouchListener(hudDrag)

        windowManager.addView(hudLayout, hudParams)

        hudLayout.post {
            val (curW, curH, _) = getRealScreenSize()
            hudParams.x = ((curW - hudLayout.width) / 2).coerceAtLeast(0)
            hudParams.y = (curH - hudLayout.height - 8).coerceAtLeast(0)
            try {
                windowManager.updateViewLayout(hudLayout, hudParams)
            } catch (_: Exception) { }
        }
    }

    /**
     * Step 1: Instantly auto-detects White Cue Ball edge-to-edge (if permission active).
     * Step 2: Opens 1-shot Tap & Slide sheet so user taps anywhere on wall/table, slides up/down,
     *         and lifts finger -> game touch immediately becomes 100% free!
     */
    private fun startFastTapFlow() {
        val nextAction = if (currentMode == AimMode.WALL) TapAction.TAP_AIM_WALL else TapAction.TAP_PLACE_TARGET
        val prompt = if (currentMode == AimMode.WALL) {
            "TAP & SLIDE ON WALL OR TABLE • LIFT FINGER TO PLAY"
        } else {
            "TAP TARGET BALL FIRST"
        }

        if (mediaProjection == null) {
            beginTapCaptureMode(nextAction, prompt)
            return
        }

        tapBtn.text = "..."
        requestFreshCleanFrame { seq, bmp ->
            worker.execute {
                val detected = try {
                    val tb = TableBounds(
                        bmp.width.toFloat(),
                        bmp.height.toFloat(),
                        tableBounds.leftRatio,
                        tableBounds.rightRatio,
                        tableBounds.topRatio,
                        tableBounds.bottomRatio
                    )
                    WhiteBallDetector.detect(bmp, tb)
                } catch (_: Exception) {
                    null
                } finally {
                    bmp.recycle()
                }

                if (seq != requestSeq.get()) return@execute
                mainHandler.post {
                    if (detected != null) {
                        cueX = detected.x
                        cueY = detected.y
                        cueRadius = detected.radius
                        cueAutoDetected = true
                    } else {
                        cueAutoDetected = false
                    }
                    recomputeAndDraw()
                    beginTapCaptureMode(
                        nextAction,
                        if (detected != null) prompt else "CUE NOT FOUND — TAP C OR TAP WALL"
                    )
                }
            }
        }
    }

    private fun beginTapCaptureMode(action: TapAction, prompt: String) {
        activeTapAction = action
        isHidden = false
        hideBtn.text = "HIDE"
        overlayView.isHiddenMode = false
        overlayView.tapPromptText = prompt
        tapCatcherView.visibility = View.VISIBLE
        tapBtn.text = "CANCEL"
        setButtonColor(tapBtn, "#E11D48")
        overlayView.invalidate()
    }

    private fun endTapCaptureMode() {
        activeTapAction = TapAction.NONE
        tapCatcherView.visibility = View.GONE
        overlayView.tapPromptText = null
        tapBtn.text = "⚡TAP"
        setButtonColor(tapBtn, "#16A34A")
        overlayView.invalidate()
    }

    private fun handleTapSheetPoint(rawX: Float, rawY: Float, isRelease: Boolean) {
        val x = rawX.coerceIn(tableBounds.left, tableBounds.right)
        val y = rawY.coerceIn(tableBounds.top, tableBounds.bottom)

        when (activeTapAction) {
            TapAction.TAP_AIM_WALL -> {
                aimX = x
                aimY = y
                aimDotHandle.moveCenterTo(x, y)
                recomputeAndDraw()
                if (isRelease) {
                    // Instant pass-through: game table is immediately 100% touchable again!
                    endTapCaptureMode()
                }
            }
            TapAction.TAP_PLACE_CUE -> {
                cueX = x
                cueY = y
                cueRadius = tableBounds.ballR
                cueAutoDetected = false
                recomputeAndDraw()
                if (isRelease) {
                    // Jump straight to aiming wall/table for ultra-fast workflow
                    beginTapCaptureMode(TapAction.TAP_AIM_WALL, "NOW TAP & SLIDE ON WALL • LIFT TO PLAY")
                }
            }
            TapAction.TAP_PLACE_TARGET -> {
                targetX = x
                targetY = y
                targetDotHandle.moveCenterTo(x, y)
                recomputeAndDraw()
                if (isRelease) {
                    beginTapCaptureMode(TapAction.TAP_AIM_WALL, "NOW TAP POCKET OR DIRECTION • LIFT TO PLAY")
                }
            }
            TapAction.NONE -> endTapCaptureMode()
        }
    }

    private fun nudgeAimAngle(deltaDeg: Float) {
        val pivotX = if (currentMode == AimMode.CUT) targetX else cueX
        val pivotY = if (currentMode == AimMode.CUT) targetY else cueY
        val (nx, ny) = PoolGeometryEngine.rotatePointAround(pivotX, pivotY, aimX, aimY, deltaDeg, tableBounds)
        aimX = nx
        aimY = ny
        aimDotHandle.moveCenterTo(nx, ny)
        recomputeAndDraw()
    }

    private fun toggleCalibration() {
        isCalibrating = !isCalibrating
        endTapCaptureMode()
        if (!isCalibrating) {
            saveCurrentTableBounds()
        } else {
            calTLHandle.moveCenterTo(tableBounds.left, tableBounds.top)
            calBRHandle.moveCenterTo(tableBounds.right, tableBounds.bottom)
        }
        calBtn.text = if (isCalibrating) "SAVE" else "CAL"
        setButtonColor(calBtn, if (isCalibrating) "#16A34A" else "#D97706")
        overlayView.showCalibration = isCalibrating
        updateHandleVisibility()
        recomputeAndDraw()
    }

    private fun updateBoundsFromCalHandles(tlX: Float, tlY: Float, brX: Float, brY: Float) {
        val (sw, sh, _) = getRealScreenSize()
        val l = (tlX / sw).coerceIn(0.05f, 0.45f)
        val r = (brX / sw).coerceIn(l + 0.30f, 0.97f)
        val t = (tlY / sh).coerceIn(0.05f, 0.45f)
        val b = (brY / sh).coerceIn(t + 0.30f, 0.98f)
        tableBounds = TableBounds(sw.toFloat(), sh.toFloat(), l, r, t, b)
        cueRadius = tableBounds.ballR
        overlayView.tableBounds = tableBounds
        overlayView.invalidate()
    }

    private fun syncHandlesToPoints() {
        aimDotHandle.moveCenterTo(aimX, aimY)
        targetDotHandle.moveCenterTo(targetX, targetY)
        calTLHandle.moveCenterTo(tableBounds.left, tableBounds.top)
        calBRHandle.moveCenterTo(tableBounds.right, tableBounds.bottom)
    }

    private fun updateHandleVisibility() {
        val showAim = !isHidden && !isCalibrating
        aimDotHandle.view.visibility = if (showAim) View.VISIBLE else View.GONE
        targetDotHandle.view.visibility = if (showAim && currentMode == AimMode.CUT) View.VISIBLE else View.GONE

        val showCal = !isHidden && isCalibrating
        calTLHandle.view.visibility = if (showCal) View.VISIBLE else View.GONE
        calBRHandle.view.visibility = if (showCal) View.VISIBLE else View.GONE
    }

    private fun recomputeAndDraw() {
        overlayView.tableBounds = tableBounds
        if (isCalibrating) {
            overlayView.invalidate()
            return
        }
        val bounces = bounceOptions[bounceIndex]
        overlayView.shotData = when (currentMode) {
            AimMode.WALL -> PoolGeometryEngine.solveWallBounceShot(
                cueX, cueY, cueRadius, cueAutoDetected, aimX, aimY, tableBounds, bounces
            )
            AimMode.CUT -> PoolGeometryEngine.solveCutShot(
                cueX, cueY, cueRadius, cueAutoDetected, targetX, targetY, aimX, aimY, tableBounds, bounces
            )
        }
        overlayView.invalidate()
    }

    // --- Single-Permission Persistent MediaProjection Capture ---

    private fun createReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            val reqId = pendingSeq
            val now = SystemClock.uptimeMillis()
            if (reqId != 0L && now >= captureAfterMs) {
                pendingSeq = 0L
                val bmp = imageToBitmap(img)
                img.close()
                val cb = pendingCallback
                pendingCallback = null
                if (bmp != null && cb != null) {
                    cb(bmp)
                } else {
                    bmp?.recycle()
                }
                return@setOnImageAvailableListener
            }
            img.close()
        }, captureHandler)
        return reader
    }

    private fun syncVirtualDisplayWithoutRecreate() {
        val mp = mediaProjection ?: return
        val (w, h, dpi) = getRealScreenSize()
        if (w == captureW && h == captureH && virtualDisplay != null && imageReader != null) return

        val oldReader = imageReader
        val newReader = createReader(w, h)
        captureW = w
        captureH = h
        captureDpi = dpi
        imageReader = newReader

        val existingVd = virtualDisplay
        if (existingVd == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "CueDetectDisplay",
                w,
                h,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                captureHandler
            )
        } else {
            existingVd.resize(w, h, dpi)
            existingVd.surface = newReader.surface
            oldReader?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val fullBmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                fullBmp
            } else {
                val cropped = Bitmap.createBitmap(fullBmp, 0, 0, image.width, image.height)
                fullBmp.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun requestFreshCleanFrame(onBitmap: (Long, Bitmap) -> Unit) {
        syncVirtualDisplayWithoutRecreate()
        val seq = requestSeq.incrementAndGet()

        // Blank overlay & dots for ~30ms so raw game cue ball is 100% unobstructed
        overlayView.isCaptureBlank = true
        overlayView.nudgeTick++
        overlayView.invalidate()
        allHandles.forEach { it.view.visibility = View.INVISIBLE }

        pendingCallback = { bmp ->
            mainHandler.post {
                overlayView.isCaptureBlank = false
                updateHandleVisibility()
                overlayView.invalidate()
            }
            onBitmap(seq, bmp)
        }
        captureAfterMs = SystemClock.uptimeMillis() + 26L
        pendingSeq = seq

        mainHandler.postDelayed({
            if (pendingSeq == seq) {
                overlayView.nudgeTick++
                overlayView.invalidate()
            }
        }, 45L)

        // Fallback after 260ms so TAP mode always opens cleanly even on a 100% static frame
        mainHandler.postDelayed({
            if (pendingSeq == seq) {
                pendingSeq = 0L
                pendingCallback = null
                overlayView.isCaptureBlank = false
                updateHandleVisibility()
                val nextAction = if (currentMode == AimMode.WALL) TapAction.TAP_AIM_WALL else TapAction.TAP_PLACE_TARGET
                beginTapCaptureMode(nextAction, "TAP & SLIDE ON WALL OR TABLE • LIFT TO PLAY")
            }
        }, 260L)
    }

    // --- UI Helpers ---

    private fun setButtonColor(btn: Button, hex: String) {
        btn.background = GradientDrawable().apply {
            setColor(Color.parseColor(hex))
            cornerRadius = 15f
        }
    }

    private fun makeHudButton(label: String, hexBg: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.WHITE)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(13, 8, 13, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexBg))
                cornerRadius = 15f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(3, 0, 3, 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeHoldButton(label: String, hexBg: String, deltaDeg: Float): Button {
        return makeHudButton(label, hexBg) {}.apply {
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        nudgeAimAngle(deltaDeg)
                        holdDeltaDeg = deltaDeg
                        mainHandler.removeCallbacks(holdRotateRunnable)
                        mainHandler.postDelayed(holdRotateRunnable, 220L)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        holdDeltaDeg = 0f
                        mainHandler.removeCallbacks(holdRotateRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHandle(
        tag: String,
        color: Int,
        sizePx: Int,
        isCorner: Boolean,
        onMoved: (Float, Float, Boolean) -> Unit
    ): FloatingHandle {
        val handleView = CrosshairHandleView(this, tag, color, isCorner)
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyCutoutAlways(this)
        }

        val handle = FloatingHandle(handleView, params, sizePx, windowManager)

        var startWinX = 0
        var startWinY = 0
        var startCenterX = 0f
        var startCenterY = 0f
        var downRawX = 0f
        var downRawY = 0f

        handleView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    startWinX = params.x
                    startWinY = params.y
                    startCenterX = loc[0] + sizePx * 0.5f
                    startCenterY = loc[1] + sizePx * 0.5f
                    downRawX = event.rawX
                    downRawY = event.rawY
                    handle.centerX = startCenterX
                    handle.centerY = startCenterY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    params.x = startWinX + dx.toInt()
                    params.y = startWinY + dy.toInt()
                    handle.centerX = startCenterX + dx
                    handle.centerY = startCenterY + dy
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) { }
                    onMoved(handle.centerX, handle.centerY, false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    handle.centerX = loc[0] + sizePx * 0.5f
                    handle.centerY = loc[1] + sizePx * 0.5f
                    onMoved(handle.centerX, handle.centerY, true)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(handleView, params)
        return handle
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        holdDeltaDeg = 0f
        pendingSeq = 0L
        pendingCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        try { windowManager.removeView(overlayView) } catch (_: Exception) { }
        try { windowManager.removeView(tapCatcherView) } catch (_: Exception) { }
        try { windowManager.removeView(hudLayout) } catch (_: Exception) { }
        for (h in allHandles) {
            try { windowManager.removeView(h.view) } catch (_: Exception) { }
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread.quitSafely()
        worker.shutdownNow()
    }

    private class FloatingHandle(
        val view: View,
        val params: WindowManager.LayoutParams,
        val sizePx: Int,
        private val wm: WindowManager
    ) {
        var centerX: Float = 0f
        var centerY: Float = 0f

        fun moveCenterTo(screenX: Float, screenY: Float) {
            centerX = screenX
            centerY = screenY
            params.x = (screenX - sizePx * 0.5f).toInt()
            params.y = (screenY - sizePx * 0.5f).toInt()
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) { }
            view.post {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val actualCx = loc[0] + sizePx * 0.5f
                val actualCy = loc[1] + sizePx * 0.5f
                val errX = (screenX - actualCx).toInt()
                val errY = (screenY - actualCy).toInt()
                if (abs(errX) in 1..200 || abs(errY) in 1..200) {
                    params.x += errX
                    params.y += errY
                    try {
                        wm.updateViewLayout(view, params)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private class CrosshairHandleView(
        context: Context,
        private val tagText: String,
        private val ringColor: Int,
        private val isCorner: Boolean
    ) : View(context) {

        private val bgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 15, 23, 42)
            style = Paint.Style.FILL
        }

        private val darkOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 5.5f
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            style = Paint.Style.STROKE
            strokeWidth = 3.0f
        }

        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            textSize = 20f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.5f
            val cy = height * 0.5f
            val r = width * 0.34f

            canvas.drawCircle(cx, cy, r, bgFill)
            canvas.drawCircle(cx, cy, r, darkOutline)
            canvas.drawCircle(cx, cy, r, ringPaint)

            val gap = r * 0.34f
            val outer = r * 1.18f
            canvas.drawLine(cx - outer, cy, cx - gap, cy, crossPaint)
            canvas.drawLine(cx + gap, cy, cx + outer, cy, crossPaint)
            canvas.drawLine(cx, cy - outer, cx, cy - gap, crossPaint)
            canvas.drawLine(cx, cy + gap, cx, cy + outer, crossPaint)

            if (isCorner) {
                canvas.drawCircle(cx, cy, 4f, ringPaint)
            }
            canvas.drawText(tagText, cx + r * 0.72f, cy - r * 0.52f, textPaint)
        }
    }
}
