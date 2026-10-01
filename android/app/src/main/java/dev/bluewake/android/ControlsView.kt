package dev.bluewake.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The on-screen GameCube controller: two sticks, the face buttons, L, R, Z,
 * Start, a D-pad and the menu button, drawn and handled in this one view so
 * every finger is tracked by its own pointer id (a thumb can slide from A to B
 * while the other one holds the stick).
 *
 * The view is the controls' *area*: the strip under the picture on a nearly
 * square screen (the Find N3's inner display), the area below the hinge in Flex
 * mode, or the whole window when the controls sit over the picture. Controls are
 * laid out in a 960 x 260 reference space, spread across the area's width and
 * anchored to its bottom, and each can be dragged elsewhere in the layout
 * editor; a dragged control's position is saved per layout (see [layoutKey]) as
 * fractions of the area, so a phone-shaped area and a tablet-shaped one keep
 * independent arrangements.
 *
 * What the fingers do goes to the host through [NativeBridge.nativePublishPad].
 */
class ControlsView(context: Context, private val prefs: Prefs) : View(context) {
    interface Host {
        fun onMenuRequested()
        fun onLayoutEditingFinished()
    }

    var host: Host? = null

    /** Which arrangement this area is for: "band", "over" or "flex". */
    var layoutKey: String = "over"
        set(value) {
            if (field != value) {
                field = value
                relayout()
            }
        }

    /** False hides every control but the menu button (a controller is in use). */
    var controlsVisible = true
        set(value) {
            if (field != value) {
                field = value
                releaseAll()
                invalidate()
            }
        }

    var editing = false
        set(value) {
            if (field != value) {
                field = value
                releaseAll()
                invalidate()
            }
        }

    private enum class Kind { STICK, DPAD, ROUND, PILL, MENU }

    private class Control(
        val id: String, val kind: Kind, val mask: Int, val label: String,
        val refX: Float, val refY: Float, val refW: Float, val refH: Float, val color: Int,
    ) {
        var cx = 0f
        var cy = 0f
        var w = 0f
        var h = 0f
        var stickX = 0f   // -1..1
        var stickY = 0f   // -1..1, +y up
        var dpad = 0      // DPAD_* bits held
        var held = false  // buttons
        val hitRadius get() = max(w, h) / 2f
    }

    // Reference space, in dp at unit scale. The GameCube's own colors: A green,
    // B red, X and Y gray, Z purple.
    private val controls = listOf(
        Control("stick", Kind.STICK, 0, "", 150f, 150f, 150f, 150f, Color.WHITE),
        Control("dpad", Kind.DPAD, 0, "", 330f, 172f, 100f, 100f, Color.WHITE),
        Control("l", Kind.PILL, NativeBridge.L, "L", 62f, 40f, 104f, 46f, Color.WHITE),
        Control("start", Kind.ROUND, NativeBridge.START, "START", 330f, 62f, 58f, 58f, Color.WHITE),
        Control("menu", Kind.MENU, 0, "⋯", 480f, 28f, 46f, 46f, Color.WHITE),
        Control("cstick", Kind.STICK, 0, "C", 610f, 168f, 110f, 110f, Color.rgb(0xFF, 0xD6, 0x0A)),
        Control("z", Kind.PILL, NativeBridge.Z, "Z", 700f, 40f, 104f, 46f, Color.rgb(0xBF, 0x5A, 0xF2)),
        Control("r", Kind.PILL, NativeBridge.R, "R", 898f, 40f, 104f, 46f, Color.WHITE),
        Control("y", Kind.ROUND, NativeBridge.Y, "Y", 836f, 72f, 64f, 64f, Color.rgb(0xAE, 0xAE, 0xB2)),
        Control("x", Kind.ROUND, NativeBridge.X, "X", 920f, 122f, 64f, 64f, Color.rgb(0xAE, 0xAE, 0xB2)),
        Control("a", Kind.ROUND, NativeBridge.A, "A", 840f, 156f, 100f, 100f, Color.rgb(0x30, 0xD1, 0x58)),
        Control("b", Kind.ROUND, NativeBridge.B, "B", 754f, 204f, 70f, 70f, Color.rgb(0xFF, 0x45, 0x3A)),
    )

    private val byId = controls.associateBy { it.id }
    private val owner = HashMap<Int, Control>()   // pointer id -> the control it holds
    private val dragOffset = HashMap<Int, Pair<Float, Float>>()
    private var lastPublished = Int.MIN_VALUE

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val dash = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    private val rect = RectF()
    private val path = Path()

    private var unit = 1f        // px per reference unit
    private val doneButton = RectF()
    private val resetButton = RectF()

    private val density get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    /** Recomputes every control's place; call after the size, the layout or the size setting changes. */
    fun relayout() {
        val areaW = width.toFloat()
        val areaH = height.toFloat()
        if (areaW <= 0f || areaH <= 0f) return
        val d = density
        // The reference layout is 960 x 260 dp. Fit it into the area, never
        // larger than 1.15x and never smaller than 0.55x, then apply the size
        // setting.
        val fit = min(areaH / d / REF_H, areaW / d / REF_W)
        unit = fit.coerceIn(0.55f, 1.15f) * prefs.controlSize * d
        for (c in controls) {
            c.w = c.refW * unit
            c.h = c.refH * unit
            val saved = prefs.controlCenter(layoutKey, c.id)
            if (saved != null) {
                c.cx = saved.first * areaW
                c.cy = saved.second * areaH
            } else {
                c.cx = c.refX / REF_W * areaW
                c.cy = areaH - (REF_H - c.refY) * unit
            }
            clamp(c)
        }
        val bw = 120f * d
        val bh = 44f * d
        doneButton.set(areaW / 2f - bw - 8f * d, 8f * d, areaW / 2f - 8f * d, 8f * d + bh)
        resetButton.set(areaW / 2f + 8f * d, 8f * d, areaW / 2f + 8f * d + bw, 8f * d + bh)
        invalidate()
    }

    private fun clamp(c: Control) {
        c.cx = c.cx.coerceIn(c.w / 2f, max(c.w / 2f, width - c.w / 2f))
        c.cy = c.cy.coerceIn(c.h / 2f, max(c.h / 2f, height - c.h / 2f))
    }

    fun resetLayout() {
        prefs.resetControlLayout()
        relayout()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val opacity = prefs.controlOpacity.coerceIn(0.1f, 1f)
        val d = density
        stroke.strokeWidth = 2f * d
        for (c in controls) {
            if (!controlsVisible && c.kind != Kind.MENU && !editing) continue
            when (c.kind) {
                Kind.STICK -> drawStick(canvas, c, opacity)
                Kind.DPAD -> drawDpad(canvas, c, opacity)
                Kind.ROUND, Kind.MENU -> drawRound(canvas, c, opacity)
                Kind.PILL -> drawPill(canvas, c, opacity)
            }
            if (editing) {
                stroke.pathEffect = dash
                stroke.color = Color.argb(220, 255, 214, 10)
                rect.set(c.cx - c.w / 2f, c.cy - c.h / 2f, c.cx + c.w / 2f, c.cy + c.h / 2f)
                canvas.drawRect(rect, stroke)
                stroke.pathEffect = null
            }
        }
        if (editing) {
            drawEditButton(canvas, doneButton, "Done")
            drawEditButton(canvas, resetButton, "Reset layout")
            labelPaint.color = Color.WHITE
            labelPaint.textSize = 13f * d
            canvas.drawText("Drag a control to move it", width / 2f, doneButton.bottom + 20f * d, labelPaint)
        }
    }

    private fun base(c: Control, opacity: Float, pressed: Boolean) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(((if (pressed) 0.55f else 0.22f) * opacity * 255).roundToInt(),
            Color.red(c.color), Color.green(c.color), Color.blue(c.color))
        stroke.color = Color.argb((opacity * 255).roundToInt(), Color.red(c.color), Color.green(c.color), Color.blue(c.color))
    }

    private fun drawLabel(canvas: Canvas, c: Control, opacity: Float, size: Float) {
        labelPaint.color = Color.argb((min(1f, opacity + 0.25f) * 255).roundToInt(), 255, 255, 255)
        labelPaint.textSize = size
        canvas.drawText(c.label, c.cx, c.cy + size * 0.35f, labelPaint)
    }

    private fun drawRound(canvas: Canvas, c: Control, opacity: Float) {
        base(c, opacity, c.held)
        val r = c.w / 2f
        canvas.drawCircle(c.cx, c.cy, r, fill)
        canvas.drawCircle(c.cx, c.cy, r, stroke)
        drawLabel(canvas, c, opacity, if (c.label.length > 1) r * 0.42f else r * 0.9f)
    }

    private fun drawPill(canvas: Canvas, c: Control, opacity: Float) {
        base(c, opacity, c.held)
        rect.set(c.cx - c.w / 2f, c.cy - c.h / 2f, c.cx + c.w / 2f, c.cy + c.h / 2f)
        val radius = c.h / 2f
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        drawLabel(canvas, c, opacity, c.h * 0.55f)
    }

    private fun drawStick(canvas: Canvas, c: Control, opacity: Float) {
        val active = c.stickX != 0f || c.stickY != 0f
        base(c, opacity, false)
        val r = c.w / 2f
        canvas.drawCircle(c.cx, c.cy, r, fill)
        canvas.drawCircle(c.cx, c.cy, r, stroke)
        // The thumb, travelling up to 0.55 of the base's radius.
        val tx = c.cx + c.stickX * r * 0.55f
        val ty = c.cy - c.stickY * r * 0.55f
        fill.color = Color.argb(((if (active) 0.6f else 0.38f) * opacity * 255).roundToInt(),
            Color.red(c.color), Color.green(c.color), Color.blue(c.color))
        canvas.drawCircle(tx, ty, r * 0.42f, fill)
        canvas.drawCircle(tx, ty, r * 0.42f, stroke)
        if (c.label.isNotEmpty()) {
            labelPaint.color = Color.argb((min(1f, opacity + 0.25f) * 255).roundToInt(), 255, 255, 255)
            labelPaint.textSize = r * 0.4f
            canvas.drawText(c.label, tx, ty + r * 0.14f, labelPaint)
        }
    }

    private fun drawDpad(canvas: Canvas, c: Control, opacity: Float) {
        base(c, opacity, false)
        val arm = c.w * 0.34f   // arm width
        val half = c.w / 2f
        path.reset()
        path.moveTo(c.cx - arm / 2f, c.cy - half)
        path.lineTo(c.cx + arm / 2f, c.cy - half)
        path.lineTo(c.cx + arm / 2f, c.cy - arm / 2f)
        path.lineTo(c.cx + half, c.cy - arm / 2f)
        path.lineTo(c.cx + half, c.cy + arm / 2f)
        path.lineTo(c.cx + arm / 2f, c.cy + arm / 2f)
        path.lineTo(c.cx + arm / 2f, c.cy + half)
        path.lineTo(c.cx - arm / 2f, c.cy + half)
        path.lineTo(c.cx - arm / 2f, c.cy + arm / 2f)
        path.lineTo(c.cx - half, c.cy + arm / 2f)
        path.lineTo(c.cx - half, c.cy - arm / 2f)
        path.lineTo(c.cx - arm / 2f, c.cy - arm / 2f)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        // The held directions light up.
        fill.color = Color.argb((0.6f * opacity * 255).roundToInt(), 255, 255, 255)
        if (c.dpad and NativeBridge.DPAD_UP != 0)
            canvas.drawRect(c.cx - arm / 2f, c.cy - half, c.cx + arm / 2f, c.cy - arm / 2f, fill)
        if (c.dpad and NativeBridge.DPAD_DOWN != 0)
            canvas.drawRect(c.cx - arm / 2f, c.cy + arm / 2f, c.cx + arm / 2f, c.cy + half, fill)
        if (c.dpad and NativeBridge.DPAD_LEFT != 0)
            canvas.drawRect(c.cx - half, c.cy - arm / 2f, c.cx - arm / 2f, c.cy + arm / 2f, fill)
        if (c.dpad and NativeBridge.DPAD_RIGHT != 0)
            canvas.drawRect(c.cx + arm / 2f, c.cy - arm / 2f, c.cx + half, c.cy + arm / 2f, fill)
    }

    private fun drawEditButton(canvas: Canvas, r: RectF, label: String) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(200, 20, 30, 50)
        stroke.color = Color.WHITE
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, stroke)
        labelPaint.color = Color.WHITE
        labelPaint.textSize = 16f * density
        canvas.drawText(label, r.centerX(), r.centerY() + 6f * density, labelPaint)
    }

    // ---------------------------------------------------------------- touch

    private fun hit(x: Float, y: Float): Control? {
        val slop = 10f * density
        // Later controls draw on top, and the small round buttons sit near the big A: test the
        // face buttons first, then the rest.
        for (c in controls.asReversed()) {
            if (!controlsVisible && c.kind != Kind.MENU && !editing) continue
            val inside = when (c.kind) {
                Kind.PILL -> abs(x - c.cx) <= c.w / 2f + slop && abs(y - c.cy) <= c.h / 2f + slop
                else -> hypot(x - c.cx, y - c.cy) <= c.hitRadius + slop
            }
            if (inside) return c
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        publish()
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (editing) {
            when {
                doneButton.contains(x, y) -> { host?.onLayoutEditingFinished(); return }
                resetButton.contains(x, y) -> { resetLayout(); return }
            }
            val c = hit(x, y) ?: return
            owner[id] = c
            dragOffset[id] = (c.cx - x) to (c.cy - y)
            return
        }
        val c = hit(x, y) ?: return
        owner[id] = c
        press(c, x, y)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val c = owner[id]
        if (editing) {
            if (c != null) {
                val off = dragOffset[id] ?: (0f to 0f)
                c.cx = x + off.first
                c.cy = y + off.second
                clamp(c)
            }
            return
        }
        if (c == null) return
        when (c.kind) {
            Kind.STICK, Kind.DPAD -> press(c, x, y)
            Kind.ROUND, Kind.PILL -> {
                // Sliding from one face button onto another passes the press along.
                val now = hit(x, y)
                if (now !== c) {
                    c.held = false
                    if (now != null && (now.kind == Kind.ROUND || now.kind == Kind.PILL)) {
                        owner[id] = now
                        now.held = true
                    } else {
                        owner.remove(id)
                    }
                }
            }
            Kind.MENU -> if (hit(x, y) !== c) owner.remove(id)
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val c = owner.remove(id)
        dragOffset.remove(id)
        if (c == null) return
        if (editing) {
            if (width > 0 && height > 0) prefs.setControlCenter(layoutKey, c.id, c.cx / width, c.cy / height)
            return
        }
        when (c.kind) {
            Kind.STICK -> { c.stickX = 0f; c.stickY = 0f }
            Kind.DPAD -> c.dpad = 0
            Kind.ROUND, Kind.PILL -> c.held = false
            Kind.MENU -> if (hit(x, y) === c) host?.onMenuRequested()
        }
    }

    private fun press(c: Control, x: Float, y: Float) {
        when (c.kind) {
            Kind.STICK -> {
                val r = c.w / 2f
                var dx = (x - c.cx) / r
                var dy = -(y - c.cy) / r
                val len = hypot(dx, dy)
                if (len > 1f) { dx /= len; dy /= len }
                // A small dead zone, so a resting thumb is not a nudge.
                if (len < 0.08f) { dx = 0f; dy = 0f }
                c.stickX = dx
                c.stickY = dy
            }
            Kind.DPAD -> {
                val dx = x - c.cx
                val dy = y - c.cy
                if (hypot(dx, dy) < c.w * 0.12f) { c.dpad = 0; return }
                // Eight directions: a diagonal where the angle is within 22 degrees of 45.
                val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))  // 0 east, 90 north
                var mask = 0
                if (angle > 22.5 && angle < 157.5) mask = mask or NativeBridge.DPAD_UP
                if (angle < -22.5 && angle > -157.5) mask = mask or NativeBridge.DPAD_DOWN
                if (abs(angle) < 67.5) mask = mask or NativeBridge.DPAD_RIGHT
                if (abs(angle) > 112.5) mask = mask or NativeBridge.DPAD_LEFT
                c.dpad = mask
            }
            Kind.ROUND, Kind.PILL -> c.held = true
            Kind.MENU -> {}
        }
    }

    /** Lets go of every control (the app is being paused or the layout changes). */
    fun releaseTouches() = releaseAll()

    private fun releaseAll() {
        owner.clear()
        dragOffset.clear()
        for (c in controls) {
            c.stickX = 0f; c.stickY = 0f; c.dpad = 0; c.held = false
        }
        publish()
    }

    private fun publish() {
        if (editing) {
            send(0, 0, 0, 0, 0)
            return
        }
        var buttons = 0
        for (c in controls) {
            if (c.held) buttons = buttons or c.mask
            buttons = buttons or c.dpad
        }
        val s = byId.getValue("stick")
        val k = byId.getValue("cstick")
        send(buttons, (s.stickX * 127f).roundToInt(), (s.stickY * 127f).roundToInt(),
            (k.stickX * 127f).roundToInt(), (k.stickY * 127f).roundToInt())
    }

    private fun send(buttons: Int, sx: Int, sy: Int, cx: Int, cy: Int) {
        val key = buttons * 31 * 31 * 31 * 31 + (sx + 127) * 31 * 31 * 31 + (sy + 127) * 31 * 31 + (cx + 127) * 31 + (cy + 127)
        if (key == lastPublished) return
        lastPublished = key
        try {
            NativeBridge.nativePublishPad(buttons, sx, sy, cx, cy)
        } catch (_: UnsatisfiedLinkError) {
            // No native libraries in this build: there is no game to control.
        }
    }

    companion object {
        private const val REF_W = 960f
        private const val REF_H = 260f
    }
}
