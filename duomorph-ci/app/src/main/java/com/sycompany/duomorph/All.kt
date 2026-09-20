package com.sycompany.duomorph

import android.animation.ValueAnimator
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: Drawable
)

data class DeviceProfile(
    val name: String,
    val coverColumns: Int,
    val innerColumns: Int,
    val iconScale: Float,
    val transitionMs: Long,
    val coverPanelFraction: Float,
    val blurFactor: Float
) {
    companion object {
        fun current(): DeviceProfile {
            val model = Build.MODEL.uppercase()
            return when {
                model.startsWith("SM-F976") -> DeviceProfile(
                    "Galaxy Z Fold8 Ultra", 4, 7, 1.00f, 700L, 0.43f, 0.017f
                )
                model.startsWith("SM-F971") -> DeviceProfile(
                    "Galaxy Z Fold8", 4, 6, 0.96f, 660L, 0.44f, 0.018f
                )
                else -> DeviceProfile(
                    Build.MODEL, 4, 6, 0.96f, 680L, 0.44f, 0.018f
                )
            }
        }

        fun isInner(width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val shortSide = min(width, height).toFloat()
            val longSide = max(width, height).toFloat()
            return shortSide / longSide >= 0.70f
        }
    }
}

class HingeController(
    context: Context,
    private val durationMs: Long,
    private val onProgress: (Float, DebugState) -> Unit
) : SensorEventListener {

    data class DebugState(
        val rawAngle: Float?,
        val filteredAngle: Float?,
        val source: String,
        val distinctAngles: Int,
        val continuous: Boolean,
        val velocityDegPerSec: Float
    )

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hinge: Sensor? = manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private var started = false
    private var progress = 0f
    private var filtered = 0f
    private var hasAngle = false
    private var rawLast: Float? = null
    private var rawTime = 0L
    private var velocity = 0f
    private var animator: ValueAnimator? = null
    private val seen = LinkedHashSet<Int>()
    private var sampleStart = 0L
    private var continuous = false

    fun start() {
        if (started) return
        started = true
        sampleStart = SystemClock.elapsedRealtime()
        hinge?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        emit("idle")
    }

    fun stop() {
        if (!started) return
        started = false
        manager.unregisterListener(this)
        animator?.cancel()
    }

    fun snapToInner(inner: Boolean) {
        if (continuous && hasAngle) return
        animateTo(if (inner) 1f else 0f, "layout-morph")
    }

    private fun animateTo(target: Float, source: String) {
        if (abs(target - progress) < 0.003f) {
            progress = target
            emit(source)
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = max(240L, (durationMs * abs(target - progress)).toLong())
            interpolator = PathInterpolator(0.16f, 0f, 0.12f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                emit(source)
            }
            start()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.sensor.type != Sensor.TYPE_HINGE_ANGLE || event.values.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val raw = event.values[0].coerceIn(0f, 180f)

        rawLast?.let { old ->
            val dt = (now - rawTime).coerceAtLeast(1L) / 1000f
            val instant = (raw - old) / dt
            velocity = velocity * 0.72f + instant * 0.28f
        }
        rawLast = raw
        rawTime = now

        seen.add(raw.toInt())
        while (seen.size > 96) {
            val first = seen.firstOrNull() ?: break
            seen.remove(first)
        }

        if (!continuous && now - sampleStart > 220L) {
            val nonCardinal = seen.count { it != 0 && it != 90 && it != 180 }
            val minAngle = seen.minOrNull() ?: 0
            val maxAngle = seen.maxOrNull() ?: 0
            continuous = seen.size >= 7 && nonCardinal >= 4 && maxAngle - minAngle >= 12
        }

        if (continuous) {
            animator?.cancel()
            val alpha = when {
                abs(velocity) > 160f -> 0.42f
                abs(velocity) > 70f -> 0.34f
                else -> 0.25f
            }
            filtered = if (!hasAngle) raw else filtered * (1f - alpha) + raw * alpha
            hasAngle = true
            progress = (filtered / 180f).coerceIn(0f, 1f)
            emit("hinge-continuous")
        } else {
            when {
                raw >= 150f -> animateTo(1f, "hinge-trigger")
                raw <= 25f -> animateTo(0f, "hinge-trigger")
                raw in 70f..110f && (progress < 0.2f || progress > 0.8f) ->
                    animateTo(0.5f, "half-open-trigger")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun emit(source: String) {
        onProgress(
            progress,
            DebugState(
                rawLast,
                if (hasAngle) filtered else null,
                if (hinge == null) "no-hinge-sensor/" + source else source,
                seen.size,
                continuous,
                velocity
            )
        )
    }
}

class DuoLauncherView(
    context: Context,
    private val apps: List<AppEntry>,
    private val profile: DeviceProfile
) : View(context) {

    private data class Cell(val x: Float, val y: Float, val size: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setShadowLayer(8f, 0f, 3f, Color.argb(140, 0, 0, 0))
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }

    private var progress = 0f
    private var debug = HingeController.DebugState(null, null, "idle", 0, false, 0f)
    private var diagnostics = false
    private var hapticMidpointDone = false
    private var lastFrameNs = 0L
    private var fps = 60f

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M월 d일 E요일", Locale.KOREAN)

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            diagnostics = !diagnostics
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            diagnostics = !diagnostics
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean = launchAt(e.x, e.y)
    })

    fun setFoldProgress(value: Float, state: HingeController.DebugState) {
        progress = value.coerceIn(0f, 1f)
        debug = state

        val mid = progress in 0.47f..0.58f
        if (mid && !hapticMidpointDone) {
            hapticMidpointDone = true
            tickHaptic()
        }
        if (progress < 0.30f || progress > 0.72f) hapticMidpointDone = false

        if (Build.VERSION.SDK_INT >= 31 && width > 0 && height > 0) {
            val blur = foldStrength(progress) * min(width, height) * profile.blurFactor
            renderEffect = if (blur > 0.8f) {
                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            } else {
                null
            }
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gesture.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        updateFps()

        val inner = DeviceProfile.isInner(width, height)
        drawBackground(canvas, inner)
        drawClock(canvas, inner)

        val hingeX = if (inner) width * 0.50f else width * 0.06f
        val fold = foldStrength(progress)
        canvas.save()
        val matrix = Matrix().apply {
            postTranslate(-hingeX, -height / 2f)
            postScale(1f - 0.055f * fold, 1f)
            postSkew((if (inner) -1f else 1f) * 0.020f * fold, 0f)
            postTranslate(
                hingeX + (if (inner) -1f else 1f) * width * 0.018f * fold,
                height / 2f
            )
        }
        canvas.concat(matrix)
        drawApps(canvas, inner)
        drawDock(canvas, inner)
        canvas.restore()

        drawHingeEdge(canvas, inner)
        drawFoldGlass(canvas, inner)
        if (diagnostics) drawDiagnostics(canvas, inner)
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dt = (now - lastFrameNs) / 1_000_000_000f
            if (dt > 0f) fps = fps * 0.88f + min(240f, 1f / dt) * 0.12f
        }
        lastFrameNs = now
    }

    private fun drawBackground(canvas: Canvas, inner: Boolean) {
        val drift = (progress - 0.5f) * width * 0.05f
        paint.shader = LinearGradient(
            drift, 0f, width + drift, height.toFloat(),
            intArrayOf(Color.rgb(14, 78, 156), Color.rgb(9, 42, 92), Color.rgb(3, 18, 39)),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.color = Color.argb(38, 255, 255, 255)
        canvas.drawOval(
            RectF(width * 0.04f + drift, height * 0.13f, width * 0.90f + drift, height * 0.30f),
            paint
        )

        val horizon = if (inner) height * 0.71f else height * 0.73f
        paint.color = Color.argb(78, 3, 18, 31)
        val path = Path().apply {
            moveTo(0f, horizon)
            cubicTo(width * 0.18f, horizon - height * 0.10f, width * 0.33f, horizon + height * 0.04f, width * 0.53f, horizon - height * 0.06f)
            cubicTo(width * 0.72f, horizon - height * 0.13f, width * 0.86f, horizon + height * 0.03f, width.toFloat(), horizon - height * 0.08f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(path, paint)

        paint.shader = RadialGradient(
            width * 0.5f,
            height * 0.40f,
            max(width, height) * 0.86f,
            intArrayOf(Color.TRANSPARENT, Color.argb(100, 0, 5, 16)),
            floatArrayOf(0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawClock(canvas: Canvas, inner: Boolean) {
        val now = Date()
        val x = if (inner) width * 0.06f else width * 0.08f
        val y = height * 0.085f
        clockPaint.textSize = min(width, height) * if (inner) 0.050f else 0.068f
        canvas.drawText(timeFormat.format(now), x, y, clockPaint)
        clockPaint.textSize *= 0.34f
        clockPaint.alpha = 220
        canvas.drawText(dateFormat.format(now), x, y + clockPaint.textSize * 1.35f, clockPaint)
        clockPaint.alpha = 255
    }

    private fun coverCell(index: Int, onInner: Boolean): Cell {
        val columns = profile.coverColumns
        val rows = max(6, ceil(min(apps.size, 24) / columns.toDouble()).toInt())
        val panelW = if (onInner) width * profile.coverPanelFraction else width.toFloat()
        val left = if (onInner) width - panelW - width * 0.03f else 0f
        val top = height * 0.18f
        val cellW = panelW / columns
        val cellH = height * 0.60f / rows
        val c = index % columns
        val r = index / columns
        val size = min(cellW, cellH) * 0.57f * profile.iconScale
        return Cell(left + cellW * (c + 0.5f), top + cellH * (r + 0.5f), size)
    }

    private fun innerCell(index: Int): Cell {
        val columns = profile.innerColumns
        val rows = max(5, ceil(min(apps.size, columns * 5) / columns.toDouble()).toInt())
        val left = width * 0.05f
        val rightDock = width * 0.13f
        val top = height * 0.17f
        val cellW = (width - left - rightDock) / columns
        val cellH = height * 0.62f / rows
        val c = index % columns
        val r = index / columns
        val size = min(cellW, cellH) * 0.55f * profile.iconScale
        return Cell(left + cellW * (c + 0.5f), top + cellH * (r + 0.5f), size)
    }

    private fun drawApps(canvas: Canvas, inner: Boolean) {
        val count = min(apps.size, if (inner) profile.innerColumns * 5 else 24)
        val morph = if (inner) ease(progress) else 0f
        for (i in 0 until count) {
            val start = coverCell(i, inner)
            val end = if (inner) innerCell(i) else coverCell(i, false)
            drawApp(
                canvas,
                apps[i],
                lerp(start.x, end.x, morph),
                lerp(start.y, end.y, morph),
                lerp(start.size, end.size, morph)
            )
        }
    }

    private fun drawApp(canvas: Canvas, app: AppEntry, cx: Float, cy: Float, size: Float) {
        paint.color = Color.argb(38, 255, 255, 255)
        val r = size * 0.27f
        canvas.drawRoundRect(
            RectF(cx - size / 2 - 5, cy - size / 2 - 5, cx + size / 2 + 5, cy + size / 2 + 5),
            r,
            r,
            paint
        )
        app.icon.setBounds(
            (cx - size / 2).toInt(),
            (cy - size / 2).toInt(),
            (cx + size / 2).toInt(),
            (cy + size / 2).toInt()
        )
        app.icon.draw(canvas)
        textPaint.textSize = size * 0.21f
        textPaint.setShadowLayer(4f, 0f, 2f, Color.BLACK)
        val label = if (app.label.length > 11) app.label.take(10) + "…" else app.label
        canvas.drawText(label, cx, cy + size * 0.76f, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun dockRect(inner: Boolean): RectF {
        return if (inner) {
            val right = width * 0.965f
            val dockW = width * 0.09f
            RectF(right - dockW, height * 0.24f, right, height * 0.76f)
        } else {
            val dockH = height * 0.105f
            RectF(width * 0.09f, height - dockH - height * 0.035f, width * 0.91f, height - height * 0.035f)
        }
    }

    private fun dockCenter(index: Int, count: Int, inner: Boolean): PointF {
        val r = dockRect(inner)
        val f = index / max(1f, (count - 1).toFloat())
        return if (inner) {
            PointF(r.centerX(), lerp(r.top + r.height() * 0.14f, r.bottom - r.height() * 0.14f, f))
        } else {
            PointF(lerp(r.left + r.width() * 0.13f, r.right - r.width() * 0.13f, f), r.centerY())
        }
    }

    private fun drawDock(canvas: Canvas, inner: Boolean) {
        if (apps.isEmpty()) return
        val dockApps = apps.take(5)
        val r = dockRect(inner)
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawRoundRect(r, min(r.width(), r.height()) / 2f, min(r.width(), r.height()) / 2f, paint)
        dockApps.forEachIndexed { i, app ->
            val c = dockCenter(i, dockApps.size, inner)
            val s = if (inner) r.width() * 0.58f else r.height() * 0.58f
            app.icon.setBounds(
                (c.x - s / 2).toInt(),
                (c.y - s / 2).toInt(),
                (c.x + s / 2).toInt(),
                (c.y + s / 2).toInt()
            )
            app.icon.draw(canvas)
        }
    }

    private fun drawHingeEdge(canvas: Canvas, inner: Boolean) {
        val fold = foldStrength(progress)
        if (fold <= 0.002f) return
        val hx = if (inner) width * 0.50f else width * 0.06f
        val ew = max(2f, width * (0.0025f + 0.005f * fold))
        paint.shader = LinearGradient(
            hx - ew * 3,
            0f,
            hx + ew * 3,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((120 * fold).toInt(), 150, 193, 235),
                Color.argb((220 * fold).toInt(), 255, 255, 255),
                Color.argb((70 * fold).toInt(), 75, 119, 166),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(hx - ew * 3, 0f, hx + ew * 3, height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawFoldGlass(canvas: Canvas, inner: Boolean) {
        val strength = foldStrength(progress)
        if (strength <= 0.005f) return

        paint.color = Color.argb((72f * strength).toInt(), 0, 7, 20)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val hx = if (inner) width * 0.50f else width * 0.06f
        val radius = width * (0.07f + 0.17f * strength)
        paint.shader = LinearGradient(
            hx - radius,
            0f,
            hx + radius,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((74f * strength).toInt(), 123, 180, 235),
                Color.argb((148f * strength).toInt(), 235, 247, 255),
                Color.argb((54f * strength).toInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 0.47f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(max(0f, hx - radius), 0f, min(width.toFloat(), hx + radius), height.toFloat(), paint)
        paint.shader = null

        val sweepX = hx + (progress - 0.5f) * width * 0.10f
        paint.shader = LinearGradient(
            sweepX - width * 0.035f,
            0f,
            sweepX + width * 0.035f,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((62f * strength).toInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(sweepX - width * 0.04f, 0f, sweepX + width * 0.04f, height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawDiagnostics(canvas: Canvas, inner: Boolean) {
        val boxRight = min(width - 18f, if (inner) 980f else width - 18f)
        paint.color = Color.argb(205, 0, 0, 0)
        canvas.drawRoundRect(RectF(18f, 24f, boxRight, 304f), 24f, 24f, paint)
        val raw = debug.rawAngle?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
        val filtered = debug.filteredAngle?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
        val lines = arrayOf(
            "DuoMorph Home 1.0.0",
            "device=" + profile.name,
            "window=" + width + "x" + height + " inner=" + inner + " p=" + String.format(Locale.US, "%.3f", progress) + " fps=" + String.format(Locale.US, "%.0f", fps),
            "raw=" + raw + "deg filtered=" + filtered + "deg",
            "source=" + debug.source,
            "distinct=" + debug.distinctAngles + " continuous=" + debug.continuous + " vel=" + String.format(Locale.US, "%.0f", debug.velocityDegPerSec) + "deg/s",
            "long-press / double-tap: hide diagnostics"
        )
        var y = 60f
        lines.forEach {
            canvas.drawText(it, 40f, y, debugPaint)
            y += 36f
        }
    }

    private fun launchAt(x: Float, y: Float): Boolean {
        val inner = DeviceProfile.isInner(width, height)
        val dockApps = apps.take(5)
        if (dockRect(inner).contains(x, y) && dockApps.isNotEmpty()) {
            var best = 0
            var bestD = Float.MAX_VALUE
            dockApps.indices.forEach { i ->
                val c = dockCenter(i, dockApps.size, inner)
                val d = hypot(x - c.x, y - c.y)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            launchApp(dockApps[best])
            return true
        }

        val count = min(apps.size, if (inner) profile.innerColumns * 5 else 24)
        val t = if (inner) ease(progress) else 0f
        for (i in 0 until count) {
            val start = coverCell(i, inner)
            val end = if (inner) innerCell(i) else coverCell(i, false)
            val cx = lerp(start.x, end.x, t)
            val cy = lerp(start.y, end.y, t)
            val s = lerp(start.size, end.size, t)
            if (hypot(x - cx, y - cy) <= s * 0.72f) {
                launchApp(apps[i])
                return true
            }
        }
        return true
    }

    private fun launchApp(app: AppEntry) {
        try {
            context.startActivity(
                Intent()
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (_: Throwable) {
        }
    }

    private fun tickHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)
                    ?.defaultVibrator
                    ?.vibrate(VibrationEffect.createOneShot(10, 64))
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(10)
            }
        } catch (_: Throwable) {
        }
    }

    private fun foldStrength(p: Float): Float =
        sin(Math.PI * p.toDouble()).toFloat().coerceIn(0f, 1f)

    private fun ease(v: Float): Float {
        val t = v.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

class MainActivity : Activity() {

    private lateinit var profile: DeviceProfile
    private lateinit var homeView: DuoLauncherView
    private lateinit var hinge: HingeController
    private var lastInner: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = DeviceProfile.current()

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        homeView = DuoLauncherView(this, loadApps(), profile)
        setContentView(homeView)

        hinge = HingeController(this, profile.transitionMs) { p, state ->
            runOnUiThread { homeView.setFoldProgress(p, state) }
        }

        homeView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val inner = DeviceProfile.isInner(r - l, b - t)
            if (lastInner == null || lastInner != inner) {
                lastInner = inner
                hinge.snapToInner(inner)
            }
        }

        maybeRequestHomeRole()
    }

    override fun onResume() {
        super.onResume()
        hinge.start()
        homeView.post {
            val inner = DeviceProfile.isInner(homeView.width, homeView.height)
            lastInner = inner
            hinge.snapToInner(inner)
        }
    }

    override fun onPause() {
        hinge.stop()
        super.onPause()
    }

    private fun maybeRequestHomeRole() {
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 42)
            } catch (_: Throwable) {
            }
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val results: List<ResolveInfo> = pm.queryIntentActivities(query, 0)
        return results
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .map {
                AppEntry(
                    it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName,
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
