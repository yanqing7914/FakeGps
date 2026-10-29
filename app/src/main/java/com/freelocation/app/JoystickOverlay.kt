package com.freelocation.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 悬浮摇杆：可拖动的悬浮窗，包含速度切换、摇杆和关闭按钮。
 *
 * @param onInput 方位角（0~360，正北为 0）和力度（0~1）
 */
class JoystickOverlay(
    private val context: Context,
    private val onInput: (bearing: Double, magnitude: Double) -> Unit,
    private val onSpeedChange: (SpeedMode) -> Unit,
    private val onClose: () -> Unit,
) {
    enum class SpeedMode(val label: String, val metersPerSecond: Double) {
        WALK("步行", 1.4),
        RUN("跑步", 3.5),
        BIKE("骑行", 6.0),
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var speedMode = SpeedMode.WALK

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        x = 40
        y = 600
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.bg_overlay)
        }

        // 顶部栏：拖动区 + 速度 + 关闭
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speedBtn = smallButton(speedMode.label).apply {
            contentDescription = "切换速度，当前${speedMode.label}"
        }
        speedBtn.setOnClickListener {
            speedMode = SpeedMode.entries[(speedMode.ordinal + 1) % SpeedMode.entries.size]
            speedBtn.text = speedMode.label
            speedBtn.contentDescription = "切换速度，当前${speedMode.label}"
            onSpeedChange(speedMode)
        }
        val closeBtn = smallButton("✕").apply { contentDescription = "关闭摇杆" }
        closeBtn.setOnClickListener { dismiss(); onClose() }
        val dragBtn = smallButton("⠿").apply { contentDescription = "拖动摇杆窗口" }
        attachDrag(dragBtn)

        bar.addView(dragBtn)
        bar.addView(speedBtn)
        bar.addView(closeBtn)
        layout.addView(bar)

        val pad = JoystickPad(context) { br, mag -> onInput(br, mag) }
        layout.addView(pad, LinearLayout.LayoutParams(dp(140), dp(140)).apply { topMargin = dp(6) })

        wm.addView(layout, params)
        root = layout
        onSpeedChange(speedMode)
    }

    fun dismiss() {
        root?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        root = null
        onInput(0.0, 0.0)
    }

    private fun smallButton(text: String) = Button(context).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        minWidth = 0
        minimumWidth = dp(48)
        minHeight = 0
        minimumHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    root?.let { wm.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    /** 圆形摇杆盘。 */
    private class JoystickPad(
        context: Context,
        private val onInput: (bearing: Double, magnitude: Double) -> Unit,
    ) : View(context) {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 119, 255) }
        private var knobX = 0f
        private var knobY = 0f

        init {
            contentDescription = "方向摇杆，按住拖动控制移动方向"
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            knobX = w / 2f; knobY = h / 2f
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(cx, cy)
            canvas.drawCircle(cx, cy, r, basePaint)
            canvas.drawCircle(cx, cy, r - 2, ringPaint)
            canvas.drawCircle(knobX, knobY, r * 0.35f, knobPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val maxR = min(cx, cy) * 0.65f
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    var dx = e.x - cx
                    var dy = e.y - cy
                    val d = hypot(dx, dy)
                    if (d > maxR) { dx *= maxR / d; dy *= maxR / d }
                    knobX = cx + dx; knobY = cy + dy
                    // 屏幕坐标 y 向下；方位角正北为 0、顺时针
                    val bearing = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360
                    onInput(bearing, (min(d, maxR) / maxR).toDouble())
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    knobX = cx; knobY = cy
                    onInput(0.0, 0.0)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }
}
