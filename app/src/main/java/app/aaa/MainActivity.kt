package app.aaa

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AppOpsManager
import android.provider.Settings
import android.os.Bundle
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class Item(
    val pkg: String, val user: Int, val label: String, val icon: ImageBitmap,
    val initial: Char, val used: Long, val disabled: Boolean,
) { val key get() = "$pkg:$user" }

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
    } catch (e: Throwable) { "" }
}

class MainActivity : ComponentActivity() {
    private val tr by lazy { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII") }
    private val prefs by lazy { getSharedPreferences("p", Context.MODE_PRIVATE) }
    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun tryGrantUsageAccess(): Boolean {
        if (hasUsageAccess()) return true
        val userId = android.os.UserHandle.getUserId(android.os.Process.myUid())
        Sh.run("cmd appops set --user $userId $packageName GET_USAGE_STATS allow")
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
                    add(Item(pkg, user, o.getString("label"), bitmap.asImageBitmap(),
                        o.getString("initial").firstOrNull() ?: '#', o.optLong("used", 0L),
                        o.optBoolean("disabled", false)))
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun saveCachedItems(items: List<Item>) {
        try {
            val array = JSONArray()
            for (item in items) {
                val file = iconFile(item.pkg, item.user)
                if (!file.exists()) file.outputStream().use {
                    item.icon.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
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
        val users = Regex("UserInfo\\{(\\d+):").findAll(Sh.run("pm list users")).map { it.groupValues[1].toInt() }.toList()
            .ifEmpty { listOf(0) }
        val usage = if (hasUsageAccess()) {
            (getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryAndAggregateUsageStats(
                    System.currentTimeMillis() - 30L * 86400000,
                    System.currentTimeMillis(),
                ).mapValues { it.value.totalTimeInForeground }
        } else emptyMap()
        val out = ArrayList<Item>()
        for (u in users) {
            val all = Sh.run("pm list packages -3 --user $u").lines().mapNotNull { it.removePrefix("package:").trim().ifEmpty { null } }
            val dis = Sh.run("pm list packages -3 -d --user $u").lines().map { it.removePrefix("package:").trim() }.toSet()
            for (p in all) {
                if (p == packageName) continue
                try {
                    val old = cached["$p:$u"]
                    if (old != null) {
                        out += old.copy(used = usage[p] ?: 0L, disabled = p in dis)
                    } else {
                        val info = packageManager.getApplicationInfo(p, 0)
                        val label = packageManager.getApplicationLabel(info).toString()
                        val icon = packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                        out += Item(p, u, label, icon, initialOf(label), usage[p] ?: 0L, p in dis)
                    }
                } catch (_: Throwable) {}
            }
        }
        return out.sortedByDescending { it.used }
    }

    private fun launch(it: Item): Boolean {
        // Re-enable only for the selected Android user.
        if (it.disabled) {
            Sh.run("pm enable --user ${it.user} ${it.pkg}")
            Thread.sleep(350)
            if (isPackageDisabled(it)) return false
        }

        val user = it.user
        val pkg = it.pkg

        // Resolve the real launcher component first. This handles apps whose launch
        // activity is an alias or cannot be resolved by a package-only MAIN intent.
        val resolved = Sh.run(
            "cmd package resolve-activity --brief --user $user " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-p $pkg"
        )
        val component = resolved.lineSequence()
            .map { line -> line.trim() }
            .firstOrNull { line -> line.startsWith("$pkg/") && !line.contains(" ") }

        if (component != null) {
            val result = Sh.run("am start --user $user -n $component")
            if (!result.contains("Error:", ignoreCase = true) &&
                !result.contains("Exception", ignoreCase = true)) return true
        }

        // Some apps expose CATEGORY_MONKEY rather than a normal launcher entry.
        // ActivityManager supports --user; the Monkey executable itself does not.
        val monkeyCategory = Sh.run(
            "am start --user $user " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.MONKEY " +
                "-p $pkg"
        )
        if (!monkeyCategory.contains("Error:", ignoreCase = true) &&
            !monkeyCategory.contains("No activity found", ignoreCase = true) &&
            !monkeyCategory.contains("Exception", ignoreCase = true)) return true

        // Last fallback: retry standard launcher intent through ActivityManager.
        val launcher = Sh.run(
            "am start --user $user " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-p $pkg"
        )
        return !launcher.contains("Error:", ignoreCase = true) &&
            !launcher.contains("No activity found", ignoreCase = true) &&
            !launcher.contains("Exception", ignoreCase = true)
    }

    private fun disableApp(it: Item) {
        Sh.run("am force-stop --user ${it.user} ${it.pkg}; pm disable-user --user ${it.user} ${it.pkg}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val scope = rememberCoroutineScope()
            var items by remember { mutableStateOf<List<Item>>(emptyList()) }
            val launched = remember { mutableStateMapOf<String, Long>() }
            var white by remember {
                mutableStateOf(
                    prefs.getStringSet("white", emptySet())!!.map { key ->
                        if (':' in key) key else "$key:0"
                    }.toSet()
                )
            }
            LaunchedEffect(Unit) {
                prefs.edit().putStringSet("white", white).apply()
            }
            var filter by remember { mutableStateOf<Char?>(null) }
            val leftState = rememberLazyListState()
            val refreshMutex = remember { kotlinx.coroutines.sync.Mutex() }

            suspend fun refreshAll() {
                refreshMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { loadCachedItems() }
                    if (cached.isNotEmpty()) items = cached

                    // Permission checks and permission prompts happen only after
                    // the user presses Refresh, never during startup or onResume.
                    if (!Shizuku.pingBinder()) {
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@MainActivity, "Shizuku 未运行，请先启动 Shizuku 后再刷新",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@withLock
                    }
                    if (!granted()) {
                        Shizuku.requestPermission(0)
                        return@withLock
                    }
                    if (!tryGrantUsageAccess()) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        return@withLock
                    }

                    val fresh = withContext(Dispatchers.IO) { loadItems(cached) }
                    items = fresh
                    withContext(Dispatchers.IO) { saveCachedItems(fresh) }
                }
            }

            LaunchedEffect(filter) {
                if (filter == null) leftState.scrollToItem(0)
            }

            LaunchedEffect(Unit) {
                // Startup is cache-only. Permission checks and fresh queries are
                // performed exclusively by the Refresh control.
                val cached = withContext(Dispatchers.IO) { loadCachedItems() }
                if (cached.isNotEmpty()) items = cached
            }

            fun toggleWhite(item: Item) {
                val key = item.key
                white = if (key in white) white - key else white + key
                prefs.edit().putStringSet("white", white).apply()
            }
            fun act(item: Item, block: () -> Unit) = scope.launch(Dispatchers.IO) {
                block()
                val disabled = isPackageDisabled(item)
                withContext(Dispatchers.Main) {
                    items = items.map { if (it.key == item.key) it.copy(disabled = disabled) else it }
                }
            }

            val left = items.filter { filter == null || it.initial == filter }
                .let { if (filter == null) it.take(15) else it.take(30) }
            val right = items.filter { it.key in launched && !it.disabled }

            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(48.dp))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AppList(left, white, leftState, Modifier.weight(1f).padding(end = 20.dp), 0.dp,
                            { i -> act(i) {
                                if (launch(i)) {
                                    launched[i.key] = System.currentTimeMillis()
                                } else {
                                    runOnUiThread {
                                        android.widget.Toast.makeText(
                                            this@MainActivity, "启动失败：${i.label}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            } },
                            { i -> act(i) { disableApp(i); launched.remove(i.key) } },
                            { i -> toggleWhite(i) })
                        AppList(right, white, rememberLazyListState(), Modifier.weight(1f).padding(start = 20.dp), 56.dp,
                            { i -> act(i) {
                                if (!launch(i)) runOnUiThread {
                                    android.widget.Toast.makeText(
                                        this@MainActivity, "启动失败：${i.label}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } },
                            { i -> act(i) { disableApp(i); launched.remove(i.key) } },
                            { i -> toggleWhite(i) })
                    }
                }
                Column(
                    Modifier.align(Alignment.Center).fillMaxHeight().width(40.dp).padding(top = 48.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ("ABCDEFGHIJKLMNOPQRSTUVWXYZ").forEach { c ->
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .clickable { filter = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            T(c.toString(), c = if (filter == c) Color.White else Color(0xFF888888), s = 11)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).padding(end = 20.dp).height(56.dp)
                        .clickable {
                            scope.launch {
                                filter = null
                                leftState.scrollToItem(0)
                                refreshAll()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { T("刷新", c = Color.White, s = 16) }
                Box(
                    Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).padding(start = 20.dp).height(56.dp)
                        .pointerInput(right, white) {
                            detectTapGestures(onTap = {
                                val targets = right.filter { it.key !in white }
                                if (targets.isNotEmpty()) scope.launch(Dispatchers.IO) {
                                    targets.forEach { disableApp(it) }
                                    val states = targets.associate { it.key to isPackageDisabled(it) }
                                    withContext(Dispatchers.Main) {
                                        items = items.map { item ->
                                            states[item.key]?.let { item.copy(disabled = it) } ?: item
                                        }
                                        targets.forEach { launched.remove(it.key) }
                                    }
                                }
                            })
                        },
                    contentAlignment = Alignment.Center,
                 ) { T("停用", c = Color.White, s = 16) }
            }
        }
    }
}

@Composable
fun AppList(
    list: List<Item>, white: Set<String>,
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier, bottomPad: androidx.compose.ui.unit.Dp,
    onClick: (Item) -> Unit, onLong: (Item) -> Unit, onSwipe: (Item) -> Unit,
) {
    val baseViewConfiguration = LocalViewConfiguration.current
    val quickLongPressConfiguration = remember(baseViewConfiguration) {
        object : ViewConfiguration by baseViewConfiguration {
            override val longPressTimeoutMillis: Long = 300L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides quickLongPressConfiguration) {
    BoxWithConstraints(modifier) {
    val itemHeight = ((maxHeight - bottomPad).coerceAtLeast(0.dp)) / 15
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = bottomPad)) {
        items(list, key = { it.key }) { it ->
            val cur by rememberUpdatedState(it)
            val disabledNow = it.disabled
            Row(
                Modifier.fillMaxWidth().height(itemHeight).alpha(if (disabledNow) 0.3f else 1f)
                    .pointerInput(cur.key) {
                        detectTapGestures(
                            onTap = { onClick(cur) },
                            onLongPress = { onLong(cur) },
                        )
                    }
                    .pointerInput(cur.key) {
                        var horizontalDistance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDistance = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                horizontalDistance += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                if (kotlin.math.abs(horizontalDistance) >= viewConfiguration.touchSlop) {
                                    onSwipe(cur)
                                }
                            },
                            onDragCancel = { horizontalDistance = 0f },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                T(if (it.key in white) "☆" else "", c = Color.White, s = 14, modifier = Modifier.width(16.dp))
                Box {
                    Image(it.icon, null, Modifier.size(42.dp))
                    if (it.user != 0) T(
                        it.user.toString(), c = Color.Black, s = 8,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xFF00FF00)).padding(horizontal = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                T(it.label, c = Color.White, s = 14, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    }
    }
}

@Composable
fun T(
    t: String, c: Color, s: Int, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip,
) = androidx.compose.foundation.text.BasicText(
    t, modifier, style = androidx.compose.ui.text.TextStyle(color = c, fontSize = s.sp),
    maxLines = maxLines, overflow = overflow,
)
