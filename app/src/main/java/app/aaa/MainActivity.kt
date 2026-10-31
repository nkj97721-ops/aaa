package app.aaa

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.input.pointer.pointerInput
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

    private fun initialOf(s: String): Char {
        val first = s.trim().firstOrNull() ?: return '#'
        val digits = mapOf('0' to 'L', '1' to 'Y', '2' to 'E', '3' to 'S', '4' to 'S', '5' to 'W', '6' to 'L', '7' to 'Q', '8' to 'B', '9' to 'J')
        if (first.isDigit()) return digits[first] ?: '#'
        val c = tr.transliterate(first.toString()).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun granted() = Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun loadItems(): List<Item> {
        val users = Regex("UserInfo\\{(\\d+):").findAll(Sh.run("pm list users")).map { it.groupValues[1].toInt() }.toList()
            .ifEmpty { listOf(0) }
        val usage = (getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            .queryUsageStats(UsageStatsManager.INTERVAL_BEST, System.currentTimeMillis() - 30L * 86400000, System.currentTimeMillis())
            .groupBy { it.packageName }.mapValues { e -> e.value.sumOf { it.totalTimeInForeground } }
        val out = ArrayList<Item>()
        for (u in users) {
            val all = Sh.run("pm list packages -3 --user $u").lines().mapNotNull { it.removePrefix("package:").trim().ifEmpty { null } }
            val dis = Sh.run("pm list packages -3 -d --user $u").lines().map { it.removePrefix("package:").trim() }.toSet()
            for (p in all) {
                if (p == packageName) continue
                try {
                    val info = packageManager.getApplicationInfo(p, 0)
                    val label = packageManager.getApplicationLabel(info).toString()
                    val icon = packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                    out += Item(p, u, label, icon, initialOf(label), usage[p] ?: 0L, p in dis)
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
        val comp = packageManager.getLaunchIntentForPackage(it.pkg)?.component?.flattenToShortString() ?: return
        if (it.disabled) Sh.run("pm enable --user ${it.user} ${it.pkg}")
        Sh.run("am start --user ${it.user} -n $comp")
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
            var top by remember { mutableStateOf<String?>(null) }
            val leftState = rememberLazyListState()
            var needReload by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    if (granted()) Sh.run("appops set $packageName GET_USAGE_STATS allow")
                }
                while (true) {
                    withContext(Dispatchers.IO) {
                        if (granted()) {
                            if (needReload) { items = loadItems(); needReload = false }
                            running = runningKeys()
                        } else if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
                    }
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

            val left = items.filter { filter == null || it.initial == filter }
            val right = items.filter { it.key in launched && it.key in running }

            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(48.dp))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AppList(left, white, leftState, Modifier.weight(1f).padding(end = 14.dp), 0.dp,
                            { top = it?.let { i -> "${i.pkg}" } },
                            { i -> act { launch(i); launched[i.key] = System.currentTimeMillis() } },
                            { i -> toggleWhite(i.pkg) })
                        AppList(right, white, rememberLazyListState(), Modifier.weight(1f).padding(start = 14.dp), 56.dp,
                            { top = it?.let { i -> "${i.pkg}  u${i.user}" } },
                            { i -> act { launch(i) } },
                            { i -> act { freeze(i) } })
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
                                    onLongPress = { filter = null; scope.launch { leftState.scrollToItem(0) } },
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
                                act { right.filter { it.pkg !in white }.forEach { freeze(it) } }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { T("停用", c = Color.White, s = 16) }
                top?.let {
                    T(it, c = Color(0xFFAAAAAA), s = 8, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

@Composable
fun AppList(
    list: List<Item>, white: Set<String>, state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier, bottomPad: androidx.compose.ui.unit.Dp,
    onTouch: (Item?) -> Unit, onClick: (Item) -> Unit, onLong: (Item) -> Unit,
) {
    LazyColumn(modifier, state, contentPadding = PaddingValues(bottom = bottomPad)) {
        items(list, key = { it.key }) { it ->
            val cur by rememberUpdatedState(it)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (it.disabled) 0.3f else 1f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { onTouch(cur); tryAwaitRelease(); onTouch(null) },
                            onTap = { onClick(cur) },
                            onLongPress = { onLong(cur) },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(if (it.pkg in white) "☆" else "", c = Color.White, s = 12, modifier = Modifier.width(14.dp))
                Box {
                    Image(it.icon, null, Modifier.size(36.dp))
                    if (it.user != 0) T(
                        it.user.toString(), c = Color.Black, s = 8,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xFF39D353)).padding(horizontal = 2.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                T(it.label, c = Color.White, s = 12, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
