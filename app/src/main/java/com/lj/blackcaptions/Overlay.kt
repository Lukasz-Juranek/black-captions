package com.lj.blackcaptions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val WHITE = Color.WHITE
private const val DIM = 0xFF7A7A7A.toInt()
private const val FAINT = 0xFF2A2A2A.toInt()

/**
 * The floating caption window: a draggable, resizable black panel on top of every app.
 * Minimizing turns it into a small "CC" bubble; tapping the bubble brings the panel back.
 * Built from plain Views because Compose needs an Activity-style owner that a service doesn't have.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class Overlay(private val ctx: Context, private val onPause: () -> Unit, private val onClose: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun dp(v: Int) = (v * density).toInt()

    private val panelParams = params(WindowManager.LayoutParams.MATCH_PARENT, dp(160))
    private val bubbleParams = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)

    private val title = TextView(ctx).apply {
        setTextColor(DIM)
        textSize = 12f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val pauseButton = button(R.drawable.ic_pause) { onPause() }
    private val text = TextView(ctx).apply {
        setTextColor(WHITE)
        setPadding(dp(12), 0, dp(12), dp(10))
    }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        addView(text)
    }
    private val background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), FAINT)
    }

    private val panel = FrameLayout(ctx).apply {
        background = this@Overlay.background
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pauseButton)
            addView(button(R.drawable.ic_minimize) { minimize(true) })
            addView(button(R.drawable.ic_close) { onClose() })
        }
        // The bar is the drag handle; its buttons take their own taps first.
        bar.setOnTouchListener(dragger(panelParams, { this }, onTap = null))
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val handle = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_resize)
            setPadding(dp(6), dp(6), dp(4), dp(4))
            setOnTouchListener(resizer())
        }
        addView(handle, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))
    }

    private val bubble = TextView(ctx).apply {
        text = "CC"
        setTextColor(WHITE)
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
            setStroke(dp(1), DIM)
        }
        layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
    }
    private val bubbleFrame = FrameLayout(ctx).apply {
        addView(bubble)
        setOnTouchListener(dragger(bubbleParams, { this }) { minimize(false) })
    }

    private var minimized = false

    fun show() {
        val saved = Prefs.overlay(ctx)
        val screen = ctx.resources.displayMetrics
        panelParams.width = if (saved[2] > 0) saved[2] else screen.widthPixels - dp(32)
        panelParams.height = if (saved[3] > 0) saved[3] else dp(170)
        panelParams.x = if (saved[0] >= 0) saved[0] else dp(16)
        panelParams.y = if (saved[1] >= 0) saved[1] else screen.heightPixels / 2
        applyStyle()
        wm.addView(panel, panelParams)

        scope.launch {
            combine(Captions.lines, Captions.partial, Captions.paused, Captions.status, Captions.title) { lines, partial, paused, status, title ->
                render(lines, partial, paused, status, title)
            }.collect {}
        }
    }

    fun applyStyle() {
        background.setColor(Color.argb(Prefs.shade(ctx).alpha, 0, 0, 0))
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.textSize(ctx).sp)
    }

    fun remove() {
        scope.cancel()
        // A view added to the window manager gets its parent right away, before it's drawn.
        listOf(panel, bubbleFrame).forEach { v -> if (v.parent != null) wm.removeView(v) }
    }

    private fun render(lines: List<Line>, partial: String, paused: Boolean, status: String?, heading: String) {
        title.text = status ?: if (paused) "Paused · $heading" else heading
        pauseButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)

        val sb = SpannableStringBuilder()
        for (line in lines.takeLast(40)) {
            if (sb.isNotEmpty()) sb.append('\n')
            if (line.translation != null) {
                sb.append(line.translation)
                sb.append('\n')
                styled(sb, line.text, DIM, 0.75f)
            } else {
                sb.append(line.text)
            }
        }
        if (partial.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            styled(sb, partial, DIM, 1f)
        }
        if (sb.isEmpty()) styled(sb, if (paused) "Paused" else "Listening…", DIM, 0.85f)
        text.text = sb
        // Follow the newest line.
        scroll.post { scroll.scrollTo(0, text.height) }
    }

    private fun styled(sb: SpannableStringBuilder, s: String, color: Int, size: Float) {
        val start = sb.length
        sb.append(s)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (size != 1f) sb.setSpan(RelativeSizeSpan(size), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun minimize(on: Boolean) {
        if (on == minimized) return
        minimized = on
        if (on) {
            bubbleParams.x = panelParams.x
            bubbleParams.y = panelParams.y
            wm.removeView(panel)
            wm.addView(bubbleFrame, bubbleParams)
        } else {
            panelParams.x = bubbleParams.x
            panelParams.y = bubbleParams.y
            wm.removeView(bubbleFrame)
            wm.addView(panel, panelParams)
            save()
        }
    }

    private fun save() = Prefs.setOverlay(ctx, panelParams.x, panelParams.y, panelParams.width, panelParams.height)

    private fun params(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Not focusable, so the keyboard and Back keep going to the app underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun button(icon: Int, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(icon)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        setOnClickListener { onClick() }
    }

    /** Moves the window with the finger. A touch that doesn't move counts as a tap. */
    private fun dragger(lp: WindowManager.LayoutParams, view: () -> View, onTap: (() -> Unit)?): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = max(0, startX + dx.toInt())
                        lp.y = max(0, startY + dy.toInt())
                        wm.updateViewLayout(view(), lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke() else if (!minimized) save()
                    true
                }
                else -> false
            }
        }
    }

    /** Resizes the panel from its bottom-right corner. */
    private fun resizer(): View.OnTouchListener {
        var startW = 0
        var startH = 0
        var downX = 0f
        var downY = 0f
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = if (panelParams.width > 0) panelParams.width else panel.width
                    startH = panelParams.height
                    downX = e.rawX; downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.width = max(dp(160), startW + (e.rawX - downX).toInt())
                    panelParams.height = max(dp(90), startH + (e.rawY - downY).toInt())
                    wm.updateViewLayout(panel, panelParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    save()
                    true
                }
                else -> false
            }
        }
    }
}
