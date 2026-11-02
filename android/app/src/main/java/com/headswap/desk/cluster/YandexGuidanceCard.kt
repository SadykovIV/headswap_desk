package com.headswap.desk.cluster

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.headswap.desk.aa.AaMeta

/** Reads the two manoeuvre labels and primary arrow of a known Yandex navigation notification. */
internal class YandexGuidanceCard(private val context: Context) {
    private val cached = HashMap<String, Pair<Long, Map<List<Int>, Set<Int>>>>()

    fun parse(pkg: String, note: Notification): ClusterNavigationHint? {
        if (pkg !in PACKAGES) return null
        val app = context.createPackageContext(pkg, Context.CONTEXT_RESTRICTED)
        val icon = note.smallIcon?.takeIf { it.type == Icon.TYPE_RESOURCE && (it.resPackage.isNullOrEmpty() || it.resPackage == pkg) }
        val smallTurn = icon?.let { nameToTurn[entry(app, it.resId)] }
        // Different Yandex versions use custom compact, expanded or heads-up RemoteViews.
        @Suppress("DEPRECATION")
        val cards = listOfNotNull(note.bigContentView, note.contentView, note.headsUpContentView)
        val guidance = cards.filter { it.`package` == pkg && knownLayout(entry(app, it.layoutId)) }
        for (card in guidance) {
            val parsed = runCatching { custom(app, card, smallTurn) }.getOrNull()
            if (parsed != null) return parsed
        }
        if (guidance.isEmpty() && note.category != Notification.CATEGORY_NAVIGATION &&
            !note.channelId.orEmpty().contains("guidance", ignoreCase = true)) return null
        val title = note.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        for (key in listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)) {
            val detail = note.extras.getCharSequence(key)?.toString().orEmpty()
            NavigationHintParser.parse(title, detail, smallTurn, guidanceLayout = false)?.let { return it }
        }
        return null
    }

    private fun custom(app: Context, card: RemoteViews, smallTurn: Int?): ClusterNavigationHint? {
        // Standard RemoteViews inflater, with restricted package resources and no foreign code.
        // Never attach the result, click its actions or execute any PendingIntent.
        val root = card.apply(app, FrameLayout(app))
        fun named(name: String): View? {
            val id = app.resources.getIdentifier(name, "id", app.packageName)
            return if (id == 0) null else root.findViewById(id)
        }
        fun label(name: String) = (named(name) as? TextView)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString().orEmpty()
        val title = label("titleView")
        val detail = label("descriptionView")
        val turn = listOf("primaryIconTinted", "primaryIcon").firstNotNullOfOrNull { name ->
            val image = (named(name) as? ImageView)?.takeIf { it.visibility == View.VISIBLE }
            image?.drawable?.let { arrow(app, it) }
        } ?: smallTurn
        return NavigationHintParser.parse(title, detail, turn, guidanceLayout = true)
    }

    private fun arrow(app: Context, drawable: Drawable): Int? {
        val updated = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        val table = cached[app.packageName]?.takeIf { it.first == updated }?.second ?: run {
            val table = mutableMapOf<List<Int>, MutableSet<Int>>()
            for ((name, value) in nameToTurn) {
                val id = app.resources.getIdentifier(name, "drawable", app.packageName)
                if (id == 0) continue
                val mask = app.getDrawable(id)?.let(::mask) ?: continue
                table.getOrPut(mask) { mutableSetOf() }.add(value)
            }
            cached[app.packageName] = updated to table
            table
        }
        return mask(drawable)?.let { table[it]?.singleOrNull() }
    }

    private fun mask(drawable: Drawable): List<Int>? {
        val side = 24
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val old = Rect(drawable.bounds)
        val pixels = IntArray(side * side)
        try {
            drawable.setBounds(0, 0, side, side)
            drawable.draw(Canvas(bitmap))
            bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        } finally { drawable.bounds = old; bitmap.recycle() }
        val alpha = pixels.maxOf { it ushr 24 }
        if (alpha == 0) return null
        val rows = List(side) { y -> (0 until side).fold(0) { row, x ->
            if ((pixels[y * side + x] ushr 24) > alpha / 2) row or (1 shl x) else row
        } }
        return rows.takeUnless { it.all { row -> row == (1 shl side) - 1 } }
    }

    companion object {
        val PACKAGES = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps")
        private fun entry(c: Context, id: Int) = runCatching { c.resources.getResourceEntryName(id) }.getOrNull()
        private fun knownLayout(name: String?) = name != null && (name.startsWith("navi_layout_bg_guidance_notification") ||
            name.startsWith("redesign_bg_guidance_notification"))
        private val nameToTurn = buildMap {
            val icons = mapOf("straight" to ("forward" to AaMeta.T_FRONT),
                "left" to ("turn_left" to AaMeta.T_LEFT), "right" to ("turn_right" to AaMeta.T_RIGHT),
                "slight_left" to ("take_left" to AaMeta.T_LEFT_FRONT), "slight_right" to ("take_right" to AaMeta.T_RIGHT_FRONT),
                "hard_left" to ("hard_turn_left" to AaMeta.T_LEFT_BACK), "hard_right" to ("hard_turn_right" to AaMeta.T_RIGHT_BACK),
                "uturn_left" to ("turn_back_left" to AaMeta.T_BACK), "uturn_right" to ("turn_back_right" to AaMeta.T_BACK),
                "enter_roundabout" to ("in_circular_movement" to AaMeta.T_RING), "leave_roundabout" to ("out_circular_movement" to AaMeta.T_RING_OUT),
                "exit_left" to ("exit_left" to AaMeta.T_LEFT_SIDE), "exit_right" to ("exit_right" to AaMeta.T_RIGHT_SIDE),
                "fork_left" to ("fork_left" to AaMeta.T_BRANCH_LEFT), "fork_right" to ("fork_right" to AaMeta.T_BRANCH_RIGHT),
                "board_ferry" to ("boardferry" to AaMeta.T_IN_FERRY), "finish" to ("finish" to AaMeta.T_DEST))
            for ((sdl, pair) in icons) {
                val (contextName, turn) = pair
                put("notification_${sdl}_sdl", turn)
                put("context_ra_$contextName", turn)
                put("mapkit_styling_automotive_context_ra_$contextName", turn)
            }
        }
    }
}
