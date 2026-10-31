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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private var usageSettingsOpened = false

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    override fun onResume() {
        super.onResume()
        if (hasUsageAccess()) {
            usageSettingsOpened = false
        } else if (!usageSettingsOpened) {
            usageSettingsOpened = true
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
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

    private fun runningKeys(): Set<String> {
        val s = HashSet<String>()
        Sh.run("ps -A -o USER,NAME").lineSequence().drop(1).forEach { l ->
            val t = l.trim().split(Regex("\\s+"))
            if (t.size < 2) return@forEach
            val m = Regex("^u(\\d+)_").find(t[0]) ?: return@forEach
            s += "${t[1].substringBefore(':')}:${m.groupValues[1]}"
        }
        return s
    }

    private fun launch(it: Item) {
        if (it.disabled) {
            Sh.run("pm enable --user ${it.user} ${it.pkg}")
            Thread.sleep(350)
        }
        val comp = packageManager.getLaunchIntentForPackage(it.pkg)?.component?.flattenToShortString()
        if (comp != null) {
            Sh.run("am start --user ${it.user} -n $comp")
        } else {
            Sh.run("monkey --user ${it.user} -p ${it.pkg} 1")
        }
    }

    private fun freeze(it: Item) {
        Sh.run("am force-stop --user ${it.user} ${it.pkg}; pm disable-user --user ${it.user} ${it.pkg}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener { _, _ -> }
        if (Shizuku.pingBinder() && !granted()) Shizuku.requestPermission(0)

        setContent {
            val scope = rememberCoroutineScope()
            var items by remember { mutableStateOf<List<Item>>(emptyList()) }
            var running by remember { mutableStateOf<Set<String>>(emptySet()) }
            val launched = remember { mutableStateMapOf<String, Long>() }
            var white by remember { mutableStateOf(prefs.getStringSet("white", emptySet())!!.toSet()) }
            var filter by remember { mutableStateOf<Char?>(null) }
            val leftState = rememberLazyListState()
            var needReload by remember { mutableStateOf(true) }
            val disabledOverrides = remember { mutableStateMapOf<String, Boolean>() }
            val refreshMutex = remember { kotlinx.coroutines.sync.Mutex() }

            suspend fun refreshAll() {
                refreshMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { loadCachedItems() }
                    if (cached.isNotEmpty()) {
                        items = cached
                        cached.forEach { disabledOverrides[it.key] = it.disabled }
                    }
                    if (granted()) {
                        val fresh = withContext(Dispatchers.IO) { loadItems(cached) }
                        items = fresh
                        disabledOverrides.clear()
                        fresh.forEach { disabledOverrides[it.key] = it.disabled }
                        withContext(Dispatchers.IO) { saveCachedItems(fresh) }
                        needReload = false
                    }
                    running = withContext(Dispatchers.IO) { runningKeys() }
                }
            }

            LaunchedEffect(filter) {
                if (filter == null) leftState.scrollToItem(0)
            }

            LaunchedEffect(Unit) {
                refreshAll()
                var previousUsageAccess = hasUsageAccess()
                while (true) {
                    if (granted()) {
                        val usageAccess = hasUsageAccess()
                        if (usageAccess && !previousUsageAccess) needReload = true
                        previousUsageAccess = usageAccess
                        if (needReload) refreshAll()
                        running = withContext(Dispatchers.IO) { runningKeys() }
                    } else if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
                    val now = System.currentTimeMillis()
                    launched.entries.removeAll { it.key !in running && now - it.value > 6000 }
                    delay(2000)
                }
            }

            fun toggleWhite(p: String) {
                white = if (p in white) white - p else white + p
                prefs.edit().putStringSet("white", white).apply()
            }
            fun act(block: () -> Unit) = scope.launch(Dispatchers.IO) { block(); needReload = true }
            fun act(item: Item, block: () -> Unit) = scope.launch(Dispatchers.IO) {
                block()
                val disabled = isPackageDisabled(item)
                withContext(Dispatchers.Main) {
                    disabledOverrides[item.key] = disabled
                    items = items.map { if (it.key == item.key) it.copy(disabled = disabled) else it }
                    needReload = true
                }
            }

            val left = items.filter { filter == null || it.initial == filter }
            val right = items.filter { it.key in launched && it.key in running }

            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(48.dp))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AppList(left, white, disabledOverrides, leftState, Modifier.weight(1f).padding(end = 14.dp), 0.dp,
                            { i -> act(i) { launch(i); launched[i.key] = System.currentTimeMillis() } },
                            { i -> toggleWhite(i.pkg) })
                        AppList(right, white, disabledOverrides, rememberLazyListState(), Modifier.weight(1f).padding(start = 14.dp), 56.dp,
                            { i -> act(i) { launch(i) } },
                            { i -> act(i) { freeze(i) } })
                    }
                }
                Column(
                    Modifier.align(Alignment.Center).fillMaxHeight().width(28.dp).padding(top = 48.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ("ABCDEFGHIJKLMNOPQRSTUVWXYZ").forEach { c ->
                        Box(
                            Modifier.weight(1f).fillMaxWidth().pointerInput(c) {
                                detectTapGestures(
                                    onTap = { filter = c },
                                    onLongPress = {
                                        scope.launch { refreshAll() }
                                    },
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            T(c.toString(), c = if (filter == c) Color.White else Color(0xFF888888), s = 11)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).height(56.dp)
                        .pointerInput(right, white) {
                            detectTapGestures(onTap = {
                                act {
                                    val targets = right.filter { it.pkg !in white }
                                    val command = targets.joinToString("; ") { item ->
                                        "am force-stop --user ${item.user} ${item.pkg} >/dev/null 2>&1; " +
                                            "pm disable-user --user ${item.user} ${item.pkg} >/dev/null 2>&1"
                                    }
                                    if (command.isNotBlank()) Sh.run(command)
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
    list: List<Item>, white: Set<String>, disabledOverrides: Map<String, Boolean>,
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier, bottomPad: androidx.compose.ui.unit.Dp,
    onClick: (Item) -> Unit, onLong: (Item) -> Unit,
) {
    val baseViewConfiguration = LocalViewConfiguration.current
    val quickLongPressConfiguration = remember(baseViewConfiguration) {
        object : ViewConfiguration by baseViewConfiguration {
            override val longPressTimeoutMillis: Long = 300L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides quickLongPressConfiguration) {
    LazyColumn(modifier, state, contentPadding = PaddingValues(bottom = bottomPad)) {
        items(list, key = { it.key }) { it ->
            val cur by rememberUpdatedState(it)
            val disabledNow = disabledOverrides[it.key] ?: it.disabled
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (disabledNow) 0.3f else 1f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onClick(cur) },
                            onLongPress = { onLong(cur) },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                T(if (it.pkg in white) "☆" else "", c = Color.White, s = 12, modifier = Modifier.width(14.dp))
                Box {
                    Image(it.icon, null, Modifier.size(36.dp))
                    if (it.user != 0) T(
                        it.user.toString(), c = Color.Black, s = 8,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xFF00FF00)).padding(horizontal = 2.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                T(it.label, c = Color.White, s = 12, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
