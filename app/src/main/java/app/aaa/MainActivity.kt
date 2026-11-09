package app.aaa

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AppOpsManager
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

private const val BLACK = Color.BLACK
private const val WHITE = Color.WHITE
private const val GREY = 0xFF888888.toInt()

data class Item(
    val pkg: String, val user: Int, val label: String, val icon: Bitmap,
    val initial: Char, val used: Long, val disabled: Boolean,
) { val key get() = appIdentityKey(pkg, user) }

object Sh {
    private val m by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }
    fun run(cmd: String): String = try {
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); out
    } catch (_: Throwable) { "" }
}

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val tr by lazy { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII") }
    private val prefs by lazy { getSharedPreferences("p", Context.MODE_PRIVATE) }
    private val items = ArrayList<Item>()
    private val launched = LinkedHashMap<String, Long>()
    private val white = LinkedHashSet<String>()
    private var filter: Char? = null
    private var refreshing = false
    private lateinit var root: FrameLayout
    private lateinit var leftScroll: ScrollView
    private lateinit var rightScroll: ScrollView
    private lateinit var leftRows: LinearLayout
    private lateinit var rightRows: LinearLayout
    private lateinit var leftColumn: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var refreshButton: TextView
    private lateinit var bulkDisableButton: TextView
    private var rowHeight = 48

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, size: Float, color: Int = WHITE): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
        }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun tryGrantUsageAccess(): Boolean {
        if (hasUsageAccess()) return true
        Sh.run("cmd appops set $packageName GET_USAGE_STATS allow")
        return hasUsageAccess()
    }

    private fun initialOf(s: String): Char {
        val first = s.trim().firstOrNull() ?: return '#'
        val digits = mapOf('0' to 'L', '1' to 'Y', '2' to 'E', '3' to 'S', '4' to 'S', '5' to 'W', '6' to 'L', '7' to 'Q', '8' to 'B', '9' to 'J')
        if (first.isDigit()) return digits[first] ?: '#'
        val c = tr.transliterate(first.toString()).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun granted() = Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun iconFile(pkg: String, user: Int) =
        File(File(cacheDir, "app-icons").apply { mkdirs() }, "${user}-${pkg}.png")

    private fun drawableBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        return bitmap
    }

    private fun loadCachedItems(): List<Item> {
        val raw = prefs.getString("items_cache", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val pkg = o.getString("pkg")
                    val user = o.getInt("user")
                    val bitmap = BitmapFactory.decodeFile(iconFile(pkg, user).absolutePath) ?: continue
                    add(Item(pkg, user, o.getString("label"), bitmap,
                        o.getString("initial").firstOrNull() ?: '#', o.optLong("used", 0L),
                        o.optBoolean("disabled", false)))
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun saveCachedItems(source: List<Item>) {
        try {
            val array = JSONArray()
            for (item in source) {
                val file = iconFile(item.pkg, item.user)
                if (!file.exists()) file.outputStream().use {
                    item.icon.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                array.put(JSONObject().apply {
                    put("pkg", item.pkg); put("user", item.user); put("label", item.label)
                    put("initial", item.initial.toString()); put("used", item.used); put("disabled", item.disabled)
                })
            }
            prefs.edit().putString("items_cache", array.toString()).apply()
        } catch (_: Throwable) {}
    }

    private fun isPackageDisabled(item: Item): Boolean =
        Sh.run("pm list packages -d --user ${item.user} ${item.pkg}")
            .lineSequence().any { it.trim() == "package:${item.pkg}" }

    private fun loadItems(cachedItems: List<Item> = loadCachedItems()): List<Item> {
        val cached = cachedItems.associateBy { it.key }
        val users = Regex("UserInfo\\{(\\d+):").findAll(Sh.run("pm list users"))
            .map { it.groupValues[1].toInt() }.toList().ifEmpty { listOf(0) }
        val usage = if (hasUsageAccess()) {
            (getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryAndAggregateUsageStats(System.currentTimeMillis() - 30L * 86400000, System.currentTimeMillis())
                .mapValues { it.value.totalTimeInForeground }
        } else emptyMap()
        val out = ArrayList<Item>()
        for (u in users) {
            val all = Sh.run("pm list packages -3 --user $u").lines()
                .mapNotNull { it.removePrefix("package:").trim().ifEmpty { null } }
            val dis = Sh.run("pm list packages -3 -d --user $u").lines()
                .map { it.removePrefix("package:").trim() }.toSet()
            for (p in all) {
                if (p == packageName) continue
                try {
                    val old = cached["$p:$u"]
                    if (old != null) out += old.copy(used = usage[p] ?: 0L, disabled = p in dis)
                    else {
                        val info = packageManager.getApplicationInfo(p, 0)
                        val label = packageManager.getApplicationLabel(info).toString()
                        val icon = drawableBitmap(packageManager.getApplicationIcon(info))
                        out += Item(p, u, label, icon, initialOf(label), usage[p] ?: 0L, p in dis)
                    }
                } catch (_: Throwable) {}
            }
        }
        val cachedOrder = cachedItems.mapIndexed { index, item -> item.key to index }.toMap()
        return out.sortedWith(compareBy<Item> { cachedOrder[it.key] ?: Int.MAX_VALUE }.thenByDescending { it.used })
    }

    private fun launch(item: Item): Boolean {
        if (item.disabled) {
            Sh.run("pm enable --user ${item.user} ${item.pkg}")
            Thread.sleep(350)
            if (isPackageDisabled(item)) return false
        }
        val user = item.user
        val pkg = item.pkg
        val resolved = Sh.run(
            "cmd package resolve-activity --brief --user $user " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
        )
        val component = resolved.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("$pkg/") && !it.contains(" ") }
        if (component != null) {
            val result = Sh.run("am start --user $user -n $component")
            if (!result.contains("Error:", true) && !result.contains("Exception", true)) return true
        }
        val monkeyCategory = Sh.run(
            "am start --user $user -a android.intent.action.MAIN -c android.intent.category.MONKEY -p $pkg"
        )
        if (!monkeyCategory.contains("Error:", true) &&
            !monkeyCategory.contains("No activity found", true) &&
            !monkeyCategory.contains("Exception", true)) return true
        val launcher = Sh.run(
            "am start --user $user -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
        )
        return !launcher.contains("Error:", true) &&
            !launcher.contains("No activity found", true) && !launcher.contains("Exception", true)
    }

    private fun disableApp(item: Item) {
        Sh.run("am force-stop --user ${item.user} ${item.pkg}; pm disable-user --user ${item.user} ${item.pkg}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BLACK
        window.navigationBarColor = BLACK
        window.decorView.systemUiVisibility = 0
        white.addAll(prefs.getStringSet("white", emptySet())!!.map { key ->
            if (':' in key) key else "$key:0"
        })
        buildScreen()
        root.post {
            rowHeight = (root.height - dp(48) - dp(56)).coerceAtLeast(dp(15)) / 15
            renderLists()
        }
        // Startup is cache-only: no permission checks, prompts, or package scan.
        worker.execute {
            val cached = loadCachedItems()
            runOnUiThread {
                if (items.isEmpty() && cached.isNotEmpty()) {
                    items.clear(); items.addAll(cached); renderLists()
                }
            }
        }
    }

    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(BLACK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BLACK)
        }
        content.addView(View(this), LinearLayout.LayoutParams(-1, dp(48)))
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftColumn = makeAppColumn(true)
        rightColumn = makeAppColumn(false)
        columns.addView(leftColumn, LinearLayout.LayoutParams(0, -1, 1f))
        columns.addView(rightColumn, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val letters = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZ").forEach { ch ->
            val letter = text(ch.toString(), 11f, GREY)
            letters.addView(letter, LinearLayout.LayoutParams(-1, 0, 1f))
            letter.setOnClickListener {
                filter = ch
                renderLeft()
                letters.childrenTextColor(filter)
            }
        }
        root.addView(letters, FrameLayout.LayoutParams(dp(40), -1, android.view.Gravity.CENTER).apply {
            topMargin = dp(48); bottomMargin = dp(8)
        })

        refreshButton = text("刷新", 16f).apply {
            setOnClickListener { refresh() }
        }
        bulkDisableButton = text("停用", 16f).apply {
            setOnClickListener { bulkDisable() }
        }
        root.addView(refreshButton, FrameLayout.LayoutParams(0, dp(56), android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
            width = resources.displayMetrics.widthPixels / 2 - dp(20)
            marginEnd = dp(20)
        })
        root.addView(bulkDisableButton, FrameLayout.LayoutParams(0, dp(56), android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
            width = resources.displayMetrics.widthPixels / 2 - dp(20)
            marginStart = dp(20)
        })
        setContentView(root)
    }

    private fun LinearLayout.childrenTextColor(selected: Char?) {
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TextView ?: continue
            child.setTextColor(if (child.text.firstOrNull() == selected) WHITE else GREY)
        }
    }

    private fun makeAppColumn(left: Boolean): LinearLayout {
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(BLACK)
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        if (left) {
            leftScroll = scroll; leftRows = rows
        } else {
            rightScroll = scroll; rightRows = rows
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.setPadding(if (left) 0 else dp(20), 0, if (left) dp(20) else 0, 0)
        return column
    }

    private fun renderLists() {
        renderLeft()
        renderRight()
    }

    private fun renderLeft() {
        if (!::leftRows.isInitialized) return
        val y = leftScroll.scrollY
        leftRows.removeAllViews()
        items.asSequence().distinctBy { it.key }
            .filter { filter == null || it.initial == filter }
            .forEach { item -> leftRows.addView(makeItemRow(item)) }
        leftRows.addView(View(this), LinearLayout.LayoutParams(1, dp(56)))
        leftScroll.post { leftScroll.scrollTo(0, y) }
    }

    private fun renderRight() {
        if (!::rightRows.isInitialized) return
        val y = rightScroll.scrollY
        rightRows.removeAllViews()
        items.filter { it.key in launched && !it.disabled }
            .forEach { item -> rightRows.addView(makeItemRow(item)) }
        rightRows.addView(View(this), LinearLayout.LayoutParams(1, dp(56)))
        rightScroll.post { rightScroll.scrollTo(0, y) }
    }

    private fun makeItemRow(item: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            alpha = if (item.disabled) 0.3f else 1f
            setPadding(dp(1), 0, dp(1), 0)
        }
        val star = text(if (item.key in white) "☆" else "", 14f)
        row.addView(star, LinearLayout.LayoutParams(dp(16), -2))
        val iconFrame = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageBitmap(item.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconFrame.addView(icon, FrameLayout.LayoutParams(dp(42), dp(42)))
        if (item.user != 0) {
            val badge = text(item.user.toString(), 8f, BLACK).apply { setBackgroundColor(0xFF00FF00.toInt()) }
            iconFrame.addView(badge, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END))
        }
        row.addView(iconFrame, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        val label = text(item.label, 14f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.layoutParams = LinearLayout.LayoutParams(-1, rowHeight.coerceAtLeast(dp(36)))

        var gestureConsumed = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!gestureConsumed) onItemClick(item)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                gestureConsumed = true
                onItemLongPress(item)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 != null && kotlin.math.abs(e2.x - e1.x) > ViewConfiguration.get(this@MainActivity).scaledTouchSlop &&
                    kotlin.math.abs(e2.x - e1.x) > kotlin.math.abs(e2.y - e1.y)) {
                    if (!gestureConsumed) {
                        gestureConsumed = true
                        row.parent?.requestDisallowInterceptTouchEvent(true)
                        toggleWhite(item, star)
                    }
                    return true
                }
                return false
            }
        })
        row.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureConsumed = false
            val handled = detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                row.parent?.requestDisallowInterceptTouchEvent(false)
                gestureConsumed = false
            }
            handled
        }
        return row
    }

    private fun toggleWhite(item: Item, star: TextView? = null) {
        if (!white.add(item.key)) white.remove(item.key)
        prefs.edit().putStringSet("white", white).apply()
        star?.text = if (item.key in white) "☆" else ""
        star?.invalidate()
    }

    private fun onItemClick(item: Item) {
        worker.execute {
            val success = launch(item)
            val disabled = isPackageDisabled(item)
            runOnUiThread {
                val index = items.indexOfFirst { it.key == item.key }
                if (index >= 0) items[index] = items[index].copy(disabled = disabled)
                if (success) launched[item.key] = System.currentTimeMillis()
                else Toast.makeText(this, "启动失败：${item.label}", Toast.LENGTH_SHORT).show()
                renderLists()
            }
        }
    }

    private fun onItemLongPress(item: Item) {
        worker.execute {
            disableApp(item)
            val disabled = isPackageDisabled(item)
            runOnUiThread {
                val index = items.indexOfFirst { it.key == item.key }
                if (index >= 0) items[index] = items[index].copy(disabled = disabled)
                launched.remove(item.key)
                renderLists()
            }
        }
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshButton.alpha = 0.5f
        filter = null
        leftColumn.visibility = View.INVISIBLE
        worker.execute {
            val cached = loadCachedItems()
            runOnUiThread {
                items.clear(); items.addAll(cached)
                renderLists()
                leftColumn.visibility = View.VISIBLE
                refreshAll()
            }
        }
    }

    private fun refreshAll() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行，请先启动 Shizuku 后再刷新", Toast.LENGTH_SHORT).show()
            finishRefresh()
            return
        }
        if (!granted()) {
            Shizuku.requestPermission(0)
            finishRefresh()
            return
        }
        worker.execute {
            val usageGranted = tryGrantUsageAccess()
            runOnUiThread {
                if (!usageGranted) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    finishRefresh()
                } else {
                    worker.execute {
                        val fresh = loadItems(items.toList())
                        saveCachedItems(fresh)
                        runOnUiThread {
                            items.clear(); items.addAll(fresh)
                            renderLists()
                            finishRefresh()
                        }
                    }
                }
            }
        }
    }

    private fun finishRefresh() {
        refreshing = false
        refreshButton.alpha = 1f
    }

    private fun bulkDisable() {
        val targets = items.filter { it.key in launched && it.key !in white && !it.disabled }
        if (targets.isEmpty()) return
        worker.execute {
            targets.forEach { disableApp(it) }
            val states = targets.associate { it.key to isPackageDisabled(it) }
            runOnUiThread {
                items.indices.forEach { i ->
                    val state = states[items[i].key]
                    if (state != null) items[i] = items[i].copy(disabled = state)
                }
                targets.forEach { launched.remove(it.key) }
                renderRight()
                renderLeft()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
