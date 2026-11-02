package com.headswap.desk.desktop

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ImageButton
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.headswap.desk.AppLog
import com.headswap.desk.R
import com.headswap.desk.dex.DexState
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.util.concurrent.Executors

/** Own launcher for the session's second display. It never becomes the phone's HOME app. */
class AndroidDesktopActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var layout: LinearLayout
    private lateinit var menu: LinearLayout
    private lateinit var pane: DesktopAppPane
    private lateinit var rail: ListView
    private lateinit var railDrag: DesktopRailDrag
    private var pinMenu: PopupMenu? = null
    private val pins by lazy { DesktopPins(this) }
    private var pinnedEntries: List<DesktopAppCatalog.Entry> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "headswap-app-catalog").apply { isDaemon = true }
    }
    private var entries: List<DesktopAppCatalog.Entry> = emptyList()
    private var icons: Map<String, Drawable> = emptyMap()
    private var loading = false
    private var visible = false
    private var ownershipDeadline = 0L
    private val adapter = AppsAdapter()
    private val railAdapter = RailAdapter()

    private data class LoadedApps(val entries: List<DesktopAppCatalog.Entry>, val icons: Map<String, Drawable>)

    private val awaitOwnership = object : Runnable {
        override fun run() {
            if (!visible || isDestroyed) return
            when {
                ownsDisplay() -> { layout.visibility = View.VISIBLE; pane.refreshBuffer(); loadApps() }
                currentDisplayId() <= 0 || SystemClock.elapsedRealtime() >= ownershipDeadline -> finish()
                else -> main.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shell may launch this Activity before the connector receives start()'s display ID.
        // Render a neutral first frame; reveal the catalog only after the client confirms ownership.
        if (currentDisplayId() <= 0) { finish(); return }
        AppLog.init(this)
        buildView()
    }

    override fun onResume() {
        super.onResume()
        if (!::grid.isInitialized) return
        if (currentDisplayId() <= 0) { finish(); return }
        visible = true
        if (!ownsDisplay()) {
            layout.visibility = View.INVISIBLE
            status.text = "Подключение рабочего стола…"
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ownershipDeadline = SystemClock.elapsedRealtime() + 10_000L
        main.removeCallbacks(awaitOwnership)
        main.post(awaitOwnership)
    }

    override fun onPause() {
        visible = false
        if (::railDrag.isInitialized) railDrag.cancel()
        pinMenu?.dismiss()
        main.removeCallbacks(awaitOwnership)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::pane.isInitialized && ownsDisplay()) pane.home()
    }

    @Deprecated("The desktop remains open until its CarLife session ends")
    override fun onBackPressed() {
        if (::pane.isInitialized && ownsDisplay()) pane.back()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::railDrag.isInitialized && railDrag.handle(event) { super.dispatchTouchEvent(it) }) return true
        return super.dispatchTouchEvent(event)
    }

    private fun buildView() {
        window.setDecorFitsSystemWindows(false)
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR // The rail stays on the physical left in every locale.
            visibility = View.INVISIBLE
            setBackgroundColor(Color.BLACK)
        }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val railWidth = dp(72).coerceAtMost(displayWidth / 5)
        val buttonHeight = dp(60).coerceAtMost(displayHeight / 4)
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(23, 30, 39))
        }
        rail = ListView(this).apply {
            adapter = railAdapter
            divider = null
            setSelector(android.R.color.transparent)
            contentDescription = "Панель приложений"
            setOnItemClickListener { _, _, position, _ -> pinnedEntries.getOrNull(position)?.let(::launchApp) }
            setOnItemLongClickListener { _, row, position, _ ->
                pinnedEntries.getOrNull(position)?.let { showPinMenu(row, it) }
                true
            }
        }
        railDrag = DesktopRailDrag(layout, rail, ::dropOnRail, ::showPinMenu)
        sidebar.addView(rail, LinearLayout.LayoutParams(-1, 0, 1f))
        sidebar.addView(navButton("Назад", com.headswap.desk.R.drawable.desktop_back) {
            if (ownsDisplay()) pane.back()
        }, LinearLayout.LayoutParams(-1, buttonHeight))
        sidebar.addView(navButton("Домой", com.headswap.desk.R.drawable.desktop_home) {
            if (ownsDisplay()) pane.home()
        }, LinearLayout.LayoutParams(-1, buttonHeight))
        layout.addView(sidebar, LinearLayout.LayoutParams(railWidth, -1))
        val workspace = FrameLayout(this)
        layout.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))
        pane = DesktopAppPane(this, onOpened = {
            setChildInputFocus(true)
            menu.visibility = View.GONE
        }, onHome = {
            setChildInputFocus(false)
            menu.visibility = View.VISIBLE
            status.setTextColor(Color.rgb(180, 190, 203))
            status.text = when {
                entries.isNotEmpty() -> CATALOG_HINT
                loading -> "Загружаю приложения…"
                else -> "Приложения не найдены"
            }
            grid.smoothScrollToPosition(0)
        }, onError = ::showError)
        // Keep the app display alive behind the opaque catalog, preserving tasks across Home.
        workspace.addView(pane.view, FrameLayout.LayoutParams(-1, -1))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(18), dp(24), dp(12))
        }
        body.addView(TextView(this).apply {
            text = "Android Desktop"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Подключение рабочего стола…"
            textSize = 15f
            setTextColor(Color.rgb(180, 190, 203))
            setPadding(dp(8), 0, dp(8), dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(156)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(dp(8), dp(4), dp(8), dp(12))
            clipToPadding = false
            setSelector(android.R.color.transparent)
            adapter = this@AndroidDesktopActivity.adapter
            setOnItemClickListener { _, _, position, _ -> entries.getOrNull(position)?.let(::launchApp) }
            setOnItemLongClickListener { _, card, position, _ ->
                val entry = entries.getOrNull(position)
                if (entry != null && ownsDisplay()) {
                    val icon = icons[entry.key] ?: packageManager.defaultActivityIcon
                    railDrag.begin(card, entry, icon)
                }
                true
            }
        }
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        menu = body
        workspace.addView(menu, FrameLayout.LayoutParams(-1, -1))
        setContentView(layout)
    }

    private fun setChildInputFocus(open: Boolean) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            // Android 13 has one focused display. The rail stays touchable without
            // taking keyboard focus from the editor during a forwarded gesture.
            val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            window.setFlags(if (open) flag else 0, flag)
        }
    }

    private fun navButton(label: String, icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = label
        val background = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
        setBackgroundResource(background.resourceId)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = minOf(dp(16), resources.displayMetrics.heightPixels / 18, resources.displayMetrics.widthPixels / 24)
        setPadding(inset, inset, inset, inset)
        setOnClickListener { action() }
    }

    private fun loadApps() {
        if (loading || !ownsDisplay()) return
        loading = true
        if (entries.isEmpty()) status.text = "Загружаю приложения…"
        loader.execute {
            val result = runCatching {
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val candidates = packageManager.queryIntentActivities(query, 0).mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    DesktopAppCatalog.Entry(activity.packageName, activity.name,
                        runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(activity.packageName),
                        enabled = activity.enabled && activity.applicationInfo.enabled, exported = activity.exported)
                }
                val selected = DesktopAppCatalog.select(candidates, packageName)
                // OEM and Google app icons can be slow on a cold boot. The launcher is usable
                // as soon as its entries are known, while the worker fetches the actual icons.
                main.post { if (!isDestroyed && visible && ownsDisplay()) showEntries(selected) }
                LoadedApps(selected, selected.associate { entry ->
                    entry.key to runCatching {
                        packageManager.getActivityIcon(ComponentName(entry.packageName, entry.className))
                    }.getOrElse { packageManager.defaultActivityIcon }
                })
            }
            main.post {
                loading = false
                if (isDestroyed || !visible || !ownsDisplay()) return@post
                result.fold(onSuccess = {
                    icons = it.icons
                    if (entries != it.entries) showEntries(it.entries)
                    updateVisibleIcons()
                }, onFailure = {
                    showError("Не удалось получить список приложений. Вернитесь на рабочий стол ещё раз.")
                    AppLog.i("Android Desktop catalog: ${it.javaClass.simpleName}", false)
                })
            }
        }
    }

    private fun showEntries(selected: List<DesktopAppCatalog.Entry>) {
        val changed = entries != selected
        entries = selected
        if (changed) adapter.notifyDataSetChanged()
        refreshPins()
        grid.visibility = View.VISIBLE
        if (status.text == "Загружаю приложения…" || status.text == "Подключение рабочего стола…") {
            status.text = if (entries.isEmpty()) "Приложения не найдены" else CATALOG_HINT
            status.setTextColor(Color.rgb(180, 190, 203))
        }
    }

    private fun refreshPins() {
        val selected = pins.entries(entries)
        if (pinnedEntries != selected) {
            pinnedEntries = selected
            railAdapter.notifyDataSetChanged()
        }
    }

    // A ListView/GridView adapter refresh cancels a long press already in progress.
    // When only icons arrive, leave rows and their touch targets in place.
    private fun updateVisibleIcons() {
        for (i in 0 until rail.childCount) {
            val entry = pinnedEntries.getOrNull(rail.firstVisiblePosition + i) ?: continue
            (rail.getChildAt(i) as? ImageView)?.let { setAppIcon(it, entry) }
        }
        for (i in 0 until grid.childCount) {
            val entry = entries.getOrNull(grid.firstVisiblePosition + i) ?: continue
            val card = grid.getChildAt(i) as? LinearLayout ?: continue
            (card.getChildAt(0) as? ImageView)?.let { setAppIcon(it, entry) }
        }
    }

    private fun setAppIcon(view: ImageView, entry: DesktopAppCatalog.Entry) {
        val image = icons[entry.key] ?: packageManager.defaultActivityIcon
        view.setImageDrawable(image.constantState?.newDrawable(resources) ?: image)
    }

    private fun dropOnRail(entry: DesktopAppCatalog.Entry, insertion: Int) {
        if (!ownsDisplay()) return
        val oldIndex = pinnedEntries.indexOfFirst { it.key == entry.key }
        val index = if (oldIndex in 0 until insertion) insertion - 1 else insertion
        if (pins.pin(entries, entry.key, index)) refreshPins()
    }

    private fun showPinMenu(anchor: View, entry: DesktopAppCatalog.Entry) {
        if (!ownsDisplay() || !anchor.isAttachedToWindow) return
        pinMenu?.dismiss()
        val pinned = pinnedEntries.any { it.key == entry.key }
        val popup = PopupMenu(this, anchor)
        popup.setForceShowIcon(true)
        popup.menu.add(if (pinned) "Убрать с панели" else "Закрепить на панели").setIcon(
            if (pinned) R.drawable.desktop_unpin else R.drawable.desktop_pin)
        popup.setOnMenuItemClickListener {
            if (ownsDisplay()) {
                if (pinned) pins.remove(entries, entry.key) else pins.pin(entries, entry.key)
                refreshPins()
            }
            true
        }
        popup.setOnDismissListener { if (pinMenu === popup) pinMenu = null }
        pinMenu = popup
        popup.show()
    }

    private fun launchApp(entry: DesktopAppCatalog.Entry) {
        if (!ownsDisplay()) {
            showError("Подключение рабочего стола ещё не готово или уже завершено")
            return
        }
        status.text = "Открывается ${entry.label}…"
        status.setTextColor(Color.rgb(180, 190, 203))
        pane.open(entry)
    }

    private fun currentDisplayId(): Int = display?.displayId ?: -1
    private fun ownsDisplay(): Boolean = DesktopAppCatalog.ownsDisplay(currentDisplayId(), DexState.displayId) &&
        EmbeddedSessionRegistry.isAndroidDesktop(currentDisplayId())

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(255, 180, 160))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class RailAdapter : BaseAdapter() {
        override fun getCount(): Int = pinnedEntries.size
        override fun getItem(position: Int): DesktopAppCatalog.Entry = pinnedEntries[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val icon = (convertView as? ImageView) ?: ImageView(this@AndroidDesktopActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val height = minOf(dp(62), resources.displayMetrics.heightPixels / 4)
                val inset = minOf(dp(12), height / 5)
                setPadding(inset, inset, inset, inset)
                layoutParams = android.widget.AbsListView.LayoutParams(-1, height)
            }
            val entry = getItem(position)
            setAppIcon(icon, entry)
            icon.contentDescription = entry.label
            // The ListView handles taps and scrolls; a tooltip would consume the row touch.
            return icon
        }
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): DesktopAppCatalog.Entry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@AndroidDesktopActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(10))
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(144))
                fun tile(color: Int) = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(14).toFloat()
                }
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), tile(Color.rgb(49, 66, 88)))
                    addState(intArrayOf(android.R.attr.state_selected), tile(Color.rgb(49, 66, 88)))
                    addState(intArrayOf(), tile(Color.rgb(22, 27, 35)))
                }
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(TextView(context).apply {
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(10), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            val entry = getItem(position)
            setAppIcon(card.getChildAt(0) as ImageView, entry)
            (card.getChildAt(1) as TextView).text = entry.label
            card.contentDescription = entry.label
            return card
        }
    }

    private companion object {
        const val CATALOG_HINT = "Удерживайте приложение и перетащите его на панель слева"
    }
}
