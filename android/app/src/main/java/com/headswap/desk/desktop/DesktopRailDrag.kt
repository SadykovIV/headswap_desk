package com.headswap.desk.desktop

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.roundToInt

/** A gesture stays inside the HU window, including when the finger leaves its original grid cell. */
internal class DesktopRailDrag(
    private val root: View,
    private val rail: ListView,
    private val onDrop: (DesktopAppCatalog.Entry, Int) -> Unit,
    private val onMenu: (View, DesktopAppCatalog.Entry) -> Unit,
) {
    private data class Drag(val card: View, val entry: DesktopAppCatalog.Entry, val shadow: Drawable,
                            var moved: Boolean = false, var cancelledChild: Boolean = false)

    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop
    private val density = root.resources.displayMetrics.density
    private val railLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var pointerDown = false
    private var downX = 0f
    private var downY = 0f
    private var drag: Drag? = null
    private var scrollBy = 0
    private val scroll = object : Runnable {
        override fun run() {
            if (drag != null && scrollBy != 0 && rail.canScrollVertically(scrollBy)) {
                rail.scrollListBy(scrollBy)
                rail.postOnAnimation(this)
            }
        }
    }

    fun begin(card: View, entry: DesktopAppCatalog.Entry, image: Drawable) {
        // A keyboard or accessibility long click has no touch stream to transfer.
        if (!pointerDown) { onMenu(card, entry); return }
        val background = GradientDrawable().apply {
            setColor(Color.rgb(37, 57, 69))
            cornerRadius = dp(12).toFloat()
        }
        val icon = image.constantState?.newDrawable(root.resources) ?: root.context.packageManager.defaultActivityIcon
        val shadow = LayerDrawable(arrayOf(background, icon)).apply { setLayerInset(1, dp(7), dp(7), dp(7), dp(7)) }
        drag = Drag(card, entry, shadow)
        root.overlay.add(shadow)
        drawShadow(shadow, downX, downY)
        highlight(inside = false)
    }

    fun handle(event: MotionEvent, cancelOriginalTouch: (MotionEvent) -> Unit): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            pointerDown = true
            downX = event.x
            downY = event.y
            return false
        }
        val active = drag
        if (active == null) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pointerDown = false
            return false
        }
        if (!active.cancelledChild) {
            active.cancelledChild = true
            val cancel = MotionEvent.obtain(event)
            try { cancel.action = MotionEvent.ACTION_CANCEL; cancelOriginalTouch(cancel) } finally { cancel.recycle() }
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancel(); return true
        }
        if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) active.moved = true
        rail.getLocationInWindow(railLocation)
        val x = event.x - railLocation[0]
        val y = event.y - railLocation[1]
        val inside = x >= 0 && x < rail.width && y >= 0 && y < rail.height
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                drawShadow(active.shadow, event.x, event.y)
                highlight(inside)
                rail.removeCallbacks(scroll)
                scrollBy = when {
                    !inside -> 0
                    y < dp(28) -> -dp(3)
                    y > rail.height - dp(28) -> dp(3)
                    else -> 0
                }
                if (scrollBy != 0) rail.postOnAnimation(scroll)
            }
            MotionEvent.ACTION_UP -> {
                cancel()
                if (inside) onDrop(active.entry, insertionAt(x, y))
                else if (!active.moved) onMenu(active.card, active.entry)
            }
        }
        return true
    }

    fun cancel() {
        pointerDown = false
        drag?.let {
            root.overlay.remove(it.shadow)
            rail.setBackgroundColor(Color.TRANSPARENT)
        }
        drag = null
        rail.removeCallbacks(scroll)
        scrollBy = 0
    }

    private fun insertionAt(x: Float, y: Float): Int {
        val position = rail.pointToPosition(x.toInt(), y.toInt())
        if (position == AdapterView.INVALID_POSITION) return rail.count
        val row = rail.getChildAt(position - rail.firstVisiblePosition) ?: return rail.count
        return if (y < row.top + row.height / 2f) position else position + 1
    }

    private fun drawShadow(shadow: Drawable, x: Float, y: Float) {
        root.getLocationInWindow(rootLocation)
        val size = dp(52)
        val left = x.roundToInt() - rootLocation[0] - size / 2
        val top = y.roundToInt() - rootLocation[1] - size
        shadow.setBounds(left, top, left + size, top + size)
    }

    private fun highlight(inside: Boolean) {
        rail.setBackgroundColor(if (inside) Color.rgb(34, 92, 74) else Color.rgb(36, 52, 66))
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}
