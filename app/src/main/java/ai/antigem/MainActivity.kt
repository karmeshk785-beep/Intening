// FILE: app/src/main/java/ai/antigem/MainActivity.kt
package ai.antigem

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

private const val TERMUX_PERM = "com.termux.permission.RUN_COMMAND"
private const val ENABLE_CMD = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"
private const val SYSTEM_PROMPT = "You are a coding assistant inside an Android app. Reply in the user's language. " +
    "Every file you output goes in its own code block whose FIRST line is a comment like '// FILE: relative/path' (use '<!-- FILE: x -->' for xml/html, '# FILE: x' for yml/py). " +
    "When asked to build an Android app, output ALL files needed: settings.gradle.kts, build.gradle.kts, gradle.properties, app/build.gradle.kts, app/src/main/AndroidManifest.xml and the Kotlin sources. " +
    "Use Android Gradle Plugin 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 26, Java 17, plain Android Views (no Compose, no AndroidX), and make namespace equal the manifest package. " +
    "The build runs with 'gradle assembleDebug'. Output complete files only."
private val WORKFLOW = """
name: Build APK
on:
  push:
    branches: [main]
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.7
      - run: gradle assembleDebug --no-daemon
      - name: Publish APK
        env:
          GH_TOKEN: DOLLAR{{ secrets.GITHUB_TOKEN }}
        run: |
          gh release delete latest -y --cleanup-tag || true
          gh release create latest app/build/outputs/apk/debug/app-debug.apk --title latest --notes "AntiGem build"
""".trimIndent()

class MainActivity : Activity() {
    private val ORANGE = Color.parseColor("#D97706")
    private var BG = 0; private var FG = 0; private var SUB = 0; private var CARD = 0; private var LINE = 0; private var CHIP = 0
    private val prefs by lazy { getSharedPreferences("antigem", MODE_PRIVATE) }
    private var history = JSONArray()
    private var chatId = System.currentTimeMillis().toString()
    private var workspace = ""
    private var proc: Process? = null
    private var drawer: View? = null
    private var rid = 0
    private var waitInstall = false
    private var envLog: TextView? = null
    private var envCont: View? = null
    private val pending = HashMap<String, (Bundle?) -> Unit>()
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var chatV: View
    private lateinit var editorV: View
    private lateinit var termV: View
    private lateinit var browserV: View
    private lateinit var msgBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var wsPill: TextView
    private lateinit var modelPill: TextView
    private lateinit var emptyV: View
    private lateinit var statusV: TextView
    private lateinit var browserWv: WebView
    private lateinit var termOut: TextView

    // ===== helpers =====
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun round(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun tv(t: String, sz: Float = 15f, c: Int = FG, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun btn(t: String, c: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        background = round(ORANGE, 16); setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { c() }
    }
    private fun outBtn(t: String, c: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(ORANGE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        background = round(CARD, 16, ORANGE); setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { c() }
    }
    private fun gap(h: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(h) }
    private fun say(t: String) = runOnUiThread { addMsg(t) }
    private fun status(t: String?) = runOnUiThread {
        if (::statusV.isInitialized) { statusV.visibility = if (t == null) View.GONE else View.VISIBLE; statusV.text = t ?: "" }
    }
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cmd", t))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
    private fun field(h: String, k: String, secret: Boolean) = EditText(this).apply {
        hint = h; setText(prefs.getString(k, "")); setTextColor(FG); setHintTextColor(SUB)
        inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
    }

    private fun initTheme() {
        val d = prefs.getBoolean("dark", false)
        BG = Color.parseColor(if (d) "#0B0B12" else "#F8F9FB"); FG = Color.parseColor(if (d) "#F1F1F5" else "#111111")
        SUB = Color.parseColor(if (d) "#9A9AB0" else "#6B7280"); CARD = Color.parseColor(if (d) "#161620" else "#FFFFFF")
        LINE = Color.parseColor(if (d) "#2A2A3A" else "#E5E7EB"); CHIP = Color.parseColor(if (d) "#22222E" else "#F4F4F6")
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        initTheme()
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        nav = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE; setBackgroundColor(CARD) }
        col.addView(content, lp(-1, 0, 1f)); col.addView(nav, lp(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val f = IntentFilter("ai.antigem.TERMUX_RESULT")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(termuxRx, f, Context.RECEIVER_EXPORTED) else registerReceiver(termuxRx, f)
        chatV = buildChat(); editorV = buildEditor(); termV = buildTerminal(); browserV = buildBrowser()
        listOf("💬" to chatV, "<>" to editorV, ">_" to termV, "🌐" to browserV).forEach { (n, v) ->
            nav.addView(tv(n, 22f, ORANGE).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { show(v) } }, lp(0, -2, 1f))
        }
        val fromAlias = intent.component?.className?.endsWith("Alias") == true
        if (prefs.getBoolean("setupDone", false) && hasNotif()) { show(chatV); if (fromAlias) show(termV) } else showPermissions()
    }

    override fun onResume() {
        super.onResume()
        val lg = envLog; val ct = envCont
        if (waitInstall && lg != null && ct != null && termuxInstalled()) { waitInstall = false; verifyTermux(lg, ct) }
    }

    override fun onDestroy() { try { unregisterReceiver(termuxRx) } catch (e: Exception) {}; super.onDestroy() }

    override fun onBackPressed() {
        if (drawer != null) toggleDrawer() else if (content.getChildAt(0) !== chatV && prefs.getBoolean("setupDone", false)) show(chatV) else super.onBackPressed()
    }

    private fun show(v: View) { content.removeAllViews(); content.addView(v); nav.visibility = if (v === chatV) View.GONE else View.VISIBLE }

    // ===== 1. परमिशन =====
    private fun permCard(emoji: String, iconBg: Int, title: String, tag: String, tagFg: Int, tagBg: Int, desc: String, p: String, ok: Boolean): View {
        val green = Color.parseColor("#16A34A"); val lightGreen = Color.parseColor("#DCFCE7")
        val card = LinearLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = round(if (ok) Color.parseColor("#F3FBF6") else CARD, 20, if (ok) Color.parseColor("#A7E3BC") else LINE)
        }
        card.addView(tv(if (ok) "✔" else emoji, 22f, if (ok) green else Color.BLACK).apply { gravity = Gravity.CENTER; background = round(if (ok) lightGreen else iconBg, 12) }, lp(dp(48), dp(48)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(title, 18f, if (ok) Color.BLACK else FG, true), lp(0, -2, 1f))
        head.addView(tv(if (ok) "Enabled ✓" else tag, 13f, if (ok) green else tagFg).apply { background = round(if (ok) lightGreen else tagBg, 8); setPadding(dp(10), dp(4), dp(10), dp(4)) })
        col.addView(head)
        col.addView(tv(desc, 15f, Color.GRAY).apply { setPadding(0, dp(6), 0, dp(10)) })
        if (ok) col.addView(tv("✓ Permission active", 15f, green))
        else col.addView(tv("Grant Access", 16f, Color.BLACK).apply {
            background = round(Color.parseColor("#E5E7EB"), 12); setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(p), 1) }
        }, LinearLayout.LayoutParams(-2, -2))
        card.addView(col, lp(0, -2, 1f))
        return card
    }

    private fun showPermissions() {
        nav.visibility = View.GONE
        val storageP = Manifest.permission.READ_EXTERNAL_STORAGE; val micP = Manifest.permission.RECORD_AUDIO
        val storageOk = checkSelfPermission(storageP) == PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("asked_$storageP", false))
        val micOk = checkSelfPermission(micP) == PackageManager.PERMISSION_GRANTED
        val rootL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(16)) }
        l.addView(tv("🛡", 36f).apply { gravity = Gravity.CENTER; background = round(Color.parseColor("#F8E9DD"), 40) },
            LinearLayout.LayoutParams(dp(80), dp(80)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        l.addView(tv("Welcome to AntiGem", 30f, FG, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8)) })
        l.addView(tv("Configure your permissions to enable background execution, terminal tools, and local project storage.", 16f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20)) })
        l.addView(permCard("🔔", Color.parseColor("#FBEBD7"), "Notifications & Services", "Required", Color.parseColor("#D97706"), Color.parseColor("#FBEBD7"),
            "Required for persistent background terminal service, live status alerts, updates, and rootfs package installer progress.", Manifest.permission.POST_NOTIFICATIONS, hasNotif()), gap(0))
        l.addView(permCard("📁", Color.parseColor("#E3EEFF"), "Storage & /sdcard", "Optional", Color.parseColor("#2563EB"), Color.parseColor("#E3EEFF"),
            "Allows AntiGem to access /sdcard to import local bootstrap packages, inspect files, and manage external code workspaces.", storageP, storageOk), gap(14))
        l.addView(permCard("🎤", Color.parseColor("#EDE7FD"), "Microphone & Voice", "Optional", Color.parseColor("#7C3AED"), Color.parseColor("#EDE7FD"),
            "Enables voice-to-text dictation and voice commands directly in chat.", micP, micOk), gap(14))
        val ok = hasNotif()
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(24)) }
        if (!ok) bottom.addView(tv("👉 Please enable Notifications above to proceed", 15f, Color.parseColor("#DC2626")).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12)) })
        bottom.addView(tv(if (ok) "Continue to AntiGem 🚀" else "Notifications Required to Proceed", 18f, if (ok) Color.WHITE else Color.parseColor("#9CA3AF"), true).apply {
            gravity = Gravity.CENTER; background = round(if (ok) ORANGE else Color.parseColor("#E5E7EB"), 24); setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { if (hasNotif()) showEnv() }
        })
        rootL.addView(ScrollView(this).apply { addView(l) }, lp(-1, 0, 1f)); rootL.addView(bottom)
        content.removeAllViews(); content.addView(rootL)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 7) { val lg = envLog; val ct = envCont; if (lg != null && ct != null) verifyTermux(lg, ct); return }
        p.forEach { prefs.edit().putBoolean("asked_$it", true).apply() }
        if (!prefs.getBoolean("setupDone", false)) showPermissions()
    }

    // ===== 2. Environment (असली Termux) =====
    private fun showEnv() {
        nav.visibility = View.GONE
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(tv("AntiGem Environment", 22f, FG, true)); hc.addView(tv("Local Linux & Terminal Setup", 14f, SUB))
        head.addView(hc, lp(0, -2, 1f))
        head.addView(tv("Skip for Now →", 16f, ORANGE, true).apply { setOnClickListener { showTermSetup() } })
        l.addView(head)
        l.addView(tv(">_", 40f, ORANGE, true).apply { gravity = Gravity.CENTER; background = round(Color.parseColor("#F8E9DD"), 40) },
            LinearLayout.LayoutParams(dp(80), dp(80)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20) })
        l.addView(tv("Initialize Linux Environment", 24f, FG, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4)) })
        l.addView(tv("Select how you would like to set up the local Termux environment for AntiGem:", 15f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) })
        val hw = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 20, LINE); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        hw.addView(tv("▣  Device Hardware Capabilities", 16f, FG, true))
        val boxes = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        fun box(a: String, b: String, fg: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = round(CHIP, 12); setPadding(dp(6), dp(12), dp(6), dp(12))
            addView(tv(a, 12f, SUB).apply { gravity = Gravity.CENTER }); addView(tv(b, 14f, fg, true).apply { gravity = Gravity.CENTER })
        }
        val green = Color.parseColor("#16A34A")
        boxes.addView(box("CPU Arch", Build.SUPPORTED_ABIS.firstOrNull() ?: "?", ORANGE), lp(0, -2, 1f))
        boxes.addView(box("Android", Build.VERSION.RELEASE, ORANGE), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        boxes.addView(box("Termux", if (termuxInstalled()) "Installed" else "Missing", if (termuxInstalled()) green else Color.parseColor("#DC2626")), lp(0, -2, 1f))
        hw.addView(boxes); l.addView(hw)
        val primary = btn("") {}
        var sel = if (termuxInstalled()) 1 else 0
        val cards = ArrayList<LinearLayout>()
        fun restyle() {
            cards.forEachIndexed { i, c -> c.background = round(CHIP, 20, if (i == sel) ORANGE else LINE) }
            primary.text = if (sel == 0) "⬇  Download & Install Termux" else "✓  Verify Termux"
        }
        fun opt(i: Int, title: String, tag: String, desc: String) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setOnClickListener { sel = i; restyle() } }
            val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            h.addView(tv(title, 17f, FG, true), lp(0, -2, 1f)); h.addView(tv(tag, 12f, green, true).apply { background = round(Color.parseColor("#DCFCE7"), 8); setPadding(dp(8), dp(3), dp(8), dp(3)) })
            c.addView(h); c.addView(tv(desc, 14f, SUB).apply { setPadding(0, dp(6), 0, 0) })
            cards.add(c); l.addView(c, gap(12))
        }
        opt(0, "Auto Download from GitHub", "Official", "Downloads the official Termux app from github.com/termux and opens the installer.")
        opt(1, "Termux already installed", "Local", "Use the Termux app on this phone and verify that AntiGem can talk to it.")
        val progress = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 20, LINE); setPadding(dp(16), dp(16), dp(16), dp(16)); visibility = View.GONE }
        val pct = tv("0%", 15f, ORANGE, true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val log = tv("", 13f, Color.parseColor("#22C55E")).apply { typeface = Typeface.MONOSPACE; setBackgroundColor(Color.BLACK); setPadding(dp(10), dp(10), dp(10), dp(10)); setTextIsSelectable(true) }
        progress.addView(tv("Termux setup", 17f, FG, true)); progress.addView(pct); progress.addView(bar); progress.addView(log, gap(10))
        l.addView(progress, gap(14))
        val cont = btn("Continue →") { showTermSetup() }.apply { visibility = View.GONE }
        envLog = log; envCont = cont
        primary.setOnClickListener {
            progress.visibility = View.VISIBLE
            if (sel == 0 && !termuxInstalled()) downloadTermux(bar, pct, log) else verifyTermux(log, cont)
        }
        l.addView(primary, gap(16)); l.addView(cont, gap(10))
        l.addView(outBtn("Skip Setup & Open AntiGem Chat") { showTermSetup() }, gap(10))
        restyle()
        content.removeAllViews(); content.addView(ScrollView(this).apply { addView(l) })
    }

    private fun termuxInstalled() = try { packageManager.getPackageInfo("com.termux", 0); true } catch (e: Exception) { false }    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "AntiGem"); c.setRequestProperty("Accept", "application/vnd.github+json")
        return (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    private fun downloadTermux(bar: ProgressBar, pct: TextView, log: TextView) {
        log.append("Latest Termux release खोज रहा हूँ...\n")
        thread {
            try {
                val txt = httpGet("https://api.github.com/repos/termux/termux-app/releases/latest")
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val urls = Regex("\"browser_download_url\":\\s*\"([^\"]+\\.apk)\"").findAll(txt).map { it.groupValues[1] }.toList()
                val url = urls.firstOrNull { it.contains(abi) } ?: urls.firstOrNull { it.contains("universal") } ?: urls.firstOrNull()
                if (url == null) { runOnUiThread { log.append("❌ APK का लिंक नहीं मिला\n") }; return@thread }
                runOnUiThread { log.append("Downloading ${url.substringAfterLast('/')}\n") }
                waitInstall = true
                downloadAndInstall(url, "termux.apk", { p, s -> runOnUiThread { bar.progress = p; pct.text = "$p%   $s" } }) { ok ->
                    runOnUiThread { log.append(if (ok) "✓ Download पूरा। Install करके ऐप में वापस आइए।\n" else "❌ Download नहीं हुआ\n") }
                }
            } catch (e: Exception) { runOnUiThread { log.append("❌ ${e.message}\n") } }
        }
    }

    private fun downloadAndInstall(url: String, name: String, onProgress: (Int, String) -> Unit, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            runOnUiThread {
                Toast.makeText(this, "'Install unknown apps' चालू करके वापस आइए और फिर दबाइए", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            done(false); return
        }
        File(getExternalFilesDir(null), name).delete()
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(url)).setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, null, name))
        thread {
            while (true) {
                Thread.sleep(1000)
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (!c.moveToFirst()) { c.close(); done(false); break }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)); c.close()
                if (tot > 0) onProgress((got * 100 / tot).toInt(), "${got / 1048576} / ${tot / 1048576} MB")
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    runOnUiThread {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(dm.getUriForDownloadedFile(id), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    done(true); break
                }
                if (st == DownloadManager.STATUS_FAILED) { done(false); break }
            }
        }
    }

    private fun verifyTermux(log: TextView, cont: View) {
        log.append("Termux जाँच रहा हूँ...\n")
        if (!termuxInstalled()) { log.append("❌ Termux installed नहीं है\n"); return }
        log.append("✓ Termux installed\n")
        if (checkSelfPermission(TERMUX_PERM) != PackageManager.PERMISSION_GRANTED) {
            log.append("Termux की permission माँग रहा हूँ...\n"); requestPermissions(arrayOf(TERMUX_PERM), 7); return
        }
        log.append("Test command चला रहा हूँ...\n")
        runInTermux("echo AG_OK; uname -m") { b ->
            val out = b?.getString("stdout") ?: ""
            if (out.contains("AG_OK")) { log.append("✓ Termux से जुड़ गया ✔\n"); cont.visibility = View.VISIBLE }
            else {
                copy(ENABLE_CMD)
                log.append("❌ Termux ने जवाब नहीं दिया।\nTermux खोलकर यह एक बार चलाइए (कॉपी हो चुका है), फिर Verify दबाइए:\n$ENABLE_CMD\n")
            }
        }
    }

    // ===== Termux bridge =====
    private val termuxRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val intent = i ?: return
            val id = intent.getStringExtra("rid") ?: return
            val cb = pending.remove(id) ?: return
            cb(intent.getBundleExtra("result"))
        }
    }

    private fun runInTermux(script: String, cb: (Bundle?) -> Unit) {
        val id = "r" + (++rid); pending[id] = cb
        val pi = PendingIntent.getBroadcast(this, rid, Intent("ai.antigem.TERMUX_RESULT").setPackage(packageName).putExtra("rid", id),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) }
        catch (e: Exception) { pending.remove(id); runOnUiThread { cb(null) } }
    }

    private fun termuxReady() = termuxInstalled() && checkSelfPermission(TERMUX_PERM) == PackageManager.PERMISSION_GRANTED
    private fun wsName() = if (workspace.isEmpty()) "general" else workspace

    private fun syncScript(): String {
        val d = wsDir(); val sb = StringBuilder("mkdir -p ~/projects/${wsName()}\ncd ~/projects/${wsName()}\n")
        d.walkTopDown().filter { it.isFile && it.length() < 200000 }.forEach { f ->
            val rel = f.relativeTo(d).path
            sb.append("mkdir -p \"\$(dirname '$rel')\"\nbase64 -d > '$rel' <<'AG_EOF'\n").append(Base64.encodeToString(f.readBytes(), Base64.DEFAULT)).append("\nAG_EOF\n")
        }
        return sb.toString()
    }

    private fun runShell(cmd: String, onLine: (String) -> Unit, onDone: () -> Unit = {}) {
        thread {
            try {
                val p = ProcessBuilder("sh", "-c", cmd).directory(wsDir()).redirectErrorStream(true).start(); proc = p
                p.inputStream.bufferedReader().forEachLine { l -> runOnUiThread { onLine(l) } }; p.waitFor()
            } catch (e: Exception) { runOnUiThread { onLine("error: ${e.message}") } }
            runOnUiThread { onDone() }
        }
    }

    private fun runCmd(cmd: String, sync: Boolean = false, out: (String) -> Unit) {
        if (termuxReady()) {
            status("🖥 Termux में चल रहा है")
            val script = (if (sync) syncScript() else "mkdir -p ~/projects/${wsName()}\n") + "cd ~/projects/${wsName()}\n" + cmd
            runInTermux(script) { b ->
                status(null)
                val o = (b?.getString("stdout") ?: "") + (b?.getString("stderr") ?: "")
                out(if (b == null) "❌ Termux तक नहीं पहुँचा (Settings → Terminal & Shell)" else o.ifBlank { "(no output)" })
            }
        } else {
            val sb = StringBuilder()
            runShell(cmd, { sb.append(it).append("\n") }, { out("(Termux जुड़ा नहीं, ऐप का sh चला)\n" + sb.toString().ifBlank { "(no output)" }) })
        }
    }

    // ===== 3. Terminal App Setup =====
    private fun applyTermAlias(on: Boolean, termux: Boolean) {
        fun set(n: String, e: Boolean) = packageManager.setComponentEnabledSetting(ComponentName(this, "ai.antigem.$n"),
            if (e) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        try { set("AntiTermAlias", on && !termux); set("TermuxAlias", on && termux) } catch (e: Exception) {}
    }

    private fun showTermSetup() {
        nav.visibility = View.GONE
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(24)) }
        l.addView(tv(">_", 30f, ORANGE, true).apply { gravity = Gravity.CENTER; background = round(Color.parseColor("#F8E9DD"), 32) },
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        l.addView(tv("Terminal App Setup", 26f, FG, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
        l.addView(tv("Choose how you want to access the Linux command line on your device.", 15f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) })
        var termux = prefs.getInt("termIcon", 1) == 2
        val prev = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CHIP, 20); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        prev.addView(tv("HOME SCREEN PREVIEW", 13f, ORANGE, true).apply { gravity = Gravity.CENTER })
        val prevRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        prev.addView(prevRow); l.addView(prev)
        fun icon(label: String, dark: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(tv(if (dark) ">_" else "🤖", 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setTextColor(if (dark) Color.WHITE else Color.BLACK); background = round(if (dark) Color.BLACK else Color.WHITE, 20) }, lp(dp(72), dp(72)))
            addView(tv(label, 15f, FG, true).apply { gravity = Gravity.CENTER })
        }
        fun refreshPrev() {
            prevRow.removeAllViews()
            prevRow.addView(icon("AntiGem", false), lp(dp(110), -2)); prevRow.addView(tv("+", 24f, SUB))
            prevRow.addView(icon(if (termux) "Termux" else "AntiTerm", termux), lp(dp(110), -2))
        }
        val sw = Switch(this).apply { text = "Standalone Terminal App Icon"; isChecked = prefs.getBoolean("standalone", true); setTextColor(FG) }
        l.addView(sw, gap(14))
        l.addView(tv("Select Icon & App Name:", 15f, SUB, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        val opts = ArrayList<LinearLayout>()
        fun restyle() { opts.forEachIndexed { i, c -> c.background = round(CHIP, 20, if ((i == 1) == termux) ORANGE else LINE) }; refreshPrev() }
        fun opt(i: Int, title: String, desc: String) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { termux = i == 1; restyle() } }
            c.addView(tv(title, 17f, FG, true)); c.addView(tv(desc, 14f, SUB)); opts.add(c); l.addView(c, gap(10))
        }
        opt(0, "AntiTerm", "Modern unified icon matching AntiGem"); opt(1, "Termux", "Classic terminal >_ prompt icon & familiar name")
        l.addView(btn("✓  Save & Continue") {
            prefs.edit().putBoolean("standalone", sw.isChecked).putInt("termIcon", if (termux) 2 else 1).putBoolean("setupDone", true).apply()
            applyTermAlias(sw.isChecked, termux); show(chatV)
        }, gap(16))
        l.addView(tv("You can always change or toggle this anytime in Settings.", 13f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        restyle()
        content.removeAllViews(); content.addView(ScrollView(this).apply { addView(l) })
    }

    // ===== वर्कस्पेस और चैट इतिहास =====
    private fun wsDir(): File {
        val d = if (workspace.isEmpty()) File(filesDir, "general") else File(filesDir, "projects/$workspace")
        d.mkdirs(); return d
    }

    private fun loadChats(): JSONArray = try { JSONArray(prefs.getString("chats", "[]")) } catch (e: Exception) { JSONArray() }

    private fun saveChat() {
        if (history.length() == 0) return
        val all = loadChats(); val out = JSONArray(); var found = false
        val title = history.getJSONObject(0).getString("content").take(30)
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (o.getString("id") == chatId) { o.put("msgs", history); o.put("title", title); found = true }
            out.put(o)
        }
        if (!found) out.put(JSONObject().put("id", chatId).put("title", title).put("msgs", history))
        prefs.edit().putString("chats", out.toString()).apply()
    }

    private fun deleteChat(id: String) {
        val all = loadChats(); val out = JSONArray()
        for (i in 0 until all.length()) { val o = all.getJSONObject(i); if (o.getString("id") != id) out.put(o) }
        prefs.edit().putString("chats", out.toString()).apply()
    }

    private fun newChat() { saveChat(); history = JSONArray(); chatId = System.currentTimeMillis().toString(); msgBox.removeAllViews(); emptyV.visibility = View.VISIBLE; updTitle() }

    private fun openChat(id: String) {
        saveChat(); val all = loadChats()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (o.getString("id") == id) {
                chatId = id; history = o.getJSONArray("msgs"); msgBox.removeAllViews()
                for (j in 0 until history.length()) {
                    val m = history.getJSONObject(j)
                    addMsg((if (m.getString("role") == "user") "🧑 " else "🤖 ") + m.getString("content"))
                }
                updTitle()
            }
        }
    }

    private fun updTitle() { wsPill.text = "📁  " + (if (workspace.isEmpty()) "Select Project" else workspace) + " ▾" }

    private fun addMsg(t: String) {
        emptyV.visibility = View.GONE
        msgBox.addView(tv(t, 15f).apply { setTextIsSelectable(true); setPadding(dp(4), dp(6), dp(4), dp(6)) })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ===== चैट स्क्रीन =====
    private fun showModel() {
        val ids = arrayOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.1-8b-instant", "Custom...")
        AlertDialog.Builder(this).setTitle("Model").setItems(ids) { _, w ->
            if (w < 3) { prefs.edit().putString("model", ids[w]).apply(); modelPill.text = "✨ " + ids[w] + " ▾" }
            else {
                val e = EditText(this)
                AlertDialog.Builder(this).setTitle("Model id").setView(e).setPositiveButton("OK") { _, _ ->
                    val m = e.text.toString().trim()
                    if (m.isNotEmpty()) { prefs.edit().putString("model", m).apply(); modelPill.text = "✨ $m ▾" }
                }.show()
            }
        }.show()
    }

    private fun showActions() {
        val a = arrayOf("🔨 Build APK", "📁 Workspace", "⚙ Settings")
        AlertDialog.Builder(this).setItems(a) { _, w -> when (w) { 0 -> buildFlow(0); 1 -> showWorkspace(); else -> showSettings() } }.show()
    }

    private fun buildChat(): View {
        val terra = Color.parseColor("#DA7756")
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("☰", 26f).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { toggleDrawer() } })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(tv("New Chat", 20f, FG, true))
        wsPill = tv("", 14f, SUB).apply { background = round(CHIP, 8); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { showWorkspace() } }
        mid.addView(wsPill)
        top.addView(mid, lp(0, -2, 1f))
        top.addView(View(this).apply { background = round(Color.parseColor("#F97316"), 8); setOnClickListener { showSettings() } }, lp(dp(16), dp(16)))
        fun icon(t: String, c: Int, f: () -> Unit) = tv(t, 22f, c).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { f() } }
        top.addView(icon("🌐", FG) { show(browserV) }); top.addView(icon(">_", ORANGE) { show(termV) }); top.addView(icon("<>", ORANGE) { show(editorV) })

        msgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { addView(msgBox) }
        val ev = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        ev.addView(tv("✱", 36f, terra).apply { gravity = Gravity.CENTER; background = round(Color.parseColor("#F5E4DE"), 40) }, lp(dp(80), dp(80)))
        ev.addView(tv("Groq ready", 22f, FG, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) })
        ev.addView(tv("Settings में Groq key डालिए और नीचे से चैट शुरू कीजिए.\n\n(\$ cmd = टर्मिनल,  /build = बिल्ड)", 16f, SUB).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), dp(16)) })
        ev.addView(tv("Set up Groq key", 17f, Color.WHITE).apply { gravity = Gravity.CENTER; background = round(terra, 24); setPadding(dp(32), dp(14), dp(32), dp(14)); setOnClickListener { showGroqDialog() } })
        ev.addView(tv("Check again", 16f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0); setOnClickListener { updTitle() } })
        emptyV = ev
        statusV = tv("", 13f, Color.WHITE).apply { background = round(ORANGE, 16); setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE }
        val body = FrameLayout(this)
        body.addView(chatScroll); body.addView(ev, FrameLayout.LayoutParams(-1, -1))
        body.addView(statusV, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP))

        val chips = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        fun chip(t: String, fg: Int) = tv(t, 15f, fg).apply { background = round(CHIP, 20, fg); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        chips.addView(chip("✦ Auto", Color.parseColor("#7C3AED")))
        chips.addView(chip("💡 Thinking", terra), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 24, LINE); setPadding(dp(16), dp(8), dp(12), dp(12)) }
        val inp = EditText(this).apply { hint = "Reply to Models..."; background = null; minLines = 2; textSize = 17f; setTextColor(FG); setHintTextColor(SUB) }
        val plus = tv("+", 22f).apply { gravity = Gravity.CENTER; background = round(CHIP, 20, LINE); setOnClickListener { showActions() } }
        modelPill = tv("", 15f).apply { background = round(CHIP, 20, LINE); setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { showModel() } }
        modelPill.text = "✨ " + prefs.getString("model", "openai/gpt-oss-20b") + " ▾"
        val send = tv("🎤", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; background = round(Color.BLACK, 28) }
        send.setOnClickListener {
            val q = inp.text.toString().trim()
            if (q.isNotEmpty()) { inp.setText(""); sendMsg(q) } else Toast.makeText(this, "Voice जल्द आएगा", Toast.LENGTH_SHORT).show()
        }
        inp.addTextChangedListener(object : TextWatcher {            override fun afterTextChanged(s: Editable?) { send.text = if (s.isNullOrEmpty()) "🎤" else "➤" }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(plus, lp(dp(40), dp(40)))
        bottom.addView(modelPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        bottom.addView(View(this), lp(0, 1, 1f)); bottom.addView(send, lp(dp(56), dp(56)))
        card.addView(inp); card.addView(bottom)
        l.addView(top); l.addView(body, lp(-1, 0, 1f)); l.addView(chips); l.addView(card)
        updTitle(); return l
    }

    private fun sendMsg(q: String) {
        addMsg("🧑 $q")
        if (q.startsWith("$ ")) { runCmd(q.substring(2)) { addMsg(it) }; return }
        if (q == "/build") { buildFlow(0); return }
        history.put(JSONObject().put("role", "user").put("content", q))
        status("✍ कोड लिखा जा रहा है")
        thread {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
            val r = groq(msgs)
            runOnUiThread {
                if (!r.startsWith("Error:")) history.put(JSONObject().put("role", "assistant").put("content", r))
                addMsg("🤖 $r")
                val saved = saveFilesQuiet(r)
                if (saved.isNotEmpty()) addMsg("📁 Saved (${saved.size}): " + saved.joinToString(", "))
                status(null); saveChat()
            }
        }
    }

    private fun groq(msgs: JSONArray): String = try {
        val key = prefs.getString("groq", "") ?: ""
        if (key.isEmpty()) "Error: Settings → Groq में key डालिए" else {
            val body = JSONObject().put("model", prefs.getString("model", "openai/gpt-oss-20b")).put("messages", msgs)
            val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val txt = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            if (c.responseCode >= 400) "Error: $txt" else JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    } catch (e: Exception) { "Error: ${e.message}" }

    private fun saveFilesQuiet(r: String): List<String> {
        val out = ArrayList<String>()
        Regex("```[a-zA-Z]*\\n([\\s\\S]*?)```").findAll(r).forEach { m ->
            val body = m.groupValues[1]
            val f = Regex("FILE:\\s*(\\S+)").find(body.lineSequence().first())?.groupValues?.get(1) ?: return@forEach
            if (f.contains("..") || f.startsWith("/")) return@forEach
            val file = File(wsDir(), f); file.parentFile?.mkdirs(); file.writeText(body); out.add(f)
        }
        return out
    }

    // ===== साइड मेन्यू =====
    private fun toggleDrawer() {
        val old = drawer
        if (old != null) { root.removeView(old); drawer = null; return }
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#66000000")); setOnClickListener { toggleDrawer() } }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(16), dp(40), dp(16), dp(16)); isClickable = true }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(btn("＋  New Chat") { toggleDrawer(); newChat() }, lp(0, -2, 1f))
        fun sq(t: String, f: () -> Unit) = tv(t, 20f).apply { gravity = Gravity.CENTER; background = round(CHIP, 12, LINE); setOnClickListener { f() } }
        fun sqLp() = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) }
        val search = EditText(this).apply { hint = "Search chats..."; visibility = View.GONE; setTextColor(FG); setHintTextColor(SUB) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill(q: String) {
            list.removeAllViews(); val all = loadChats(); var n = 0
            for (i in all.length() - 1 downTo 0) {
                val o = all.getJSONObject(i); val t = o.getString("title")
                if (q.isNotEmpty() && !t.contains(q, true)) continue
                n++
                list.addView(tv(t, 16f).apply {
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                    setOnClickListener { toggleDrawer(); openChat(o.getString("id")) }
                    setOnLongClickListener { deleteChat(o.getString("id")); fill(q); true }
                })
            }
            if (n == 0) list.addView(tv("No recent chats", 16f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
        }
        top.addView(sq("⏻") { finishAffinity() }, sqLp())
        top.addView(sq("📁") { toggleDrawer(); showWorkspace() }, sqLp())
        top.addView(sq("🔍") { search.visibility = if (search.visibility == View.GONE) View.VISIBLE else View.GONE }, sqLp())
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { fill(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        panel.addView(top); panel.addView(search); panel.addView(ScrollView(this).apply { addView(list) }, lp(-1, 0, 1f))
        fill("")
        scrim.addView(panel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82).toInt(), -1))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1)); drawer = scrim
    }

    // ===== Select Workspace =====
    private fun showWorkspace() {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)) }
        val dlg = AlertDialog.Builder(this).setView(v).create()
        dlg.window?.setBackgroundDrawable(round(CARD, 24))
        val projects = File(filesDir, "projects").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(tv("Select Workspace", 20f, FG, true)); ht.addView(tv("${projects.size + 1} available workspace", 14f, SUB))
        head.addView(ht, lp(0, -2, 1f))
        head.addView(tv("✕", 22f).apply { setPadding(dp(12), 0, dp(4), 0); setOnClickListener { dlg.dismiss() } })
        v.addView(head)
        fun row(title: String, sub: String, active: Boolean, pick: () -> Unit) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CHIP, 16, if (active) ORANGE else LINE); setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { pick(); updTitle(); dlg.dismiss() } }
            c.addView(tv(title + if (active) "   ✓ Active" else "", 17f, FG, true)); c.addView(tv(sub, 13f, SUB))
            v.addView(c, gap(12))
        }
        row("General (No Workspace)", "Chat without attaching to a directory", workspace.isEmpty()) { workspace = "" }
        projects.forEach { p -> row(p.name, p.absolutePath, workspace == p.name) { workspace = p.name } }
        v.addView(outBtn("☁  Use Git Repository...") { dlg.dismiss(); askRepo() }, gap(16))
        v.addView(btn("＋  Create New Folder...") {
            dlg.dismiss()
            val e = EditText(this).apply { hint = "folder name" }
            AlertDialog.Builder(this).setTitle("New Folder").setView(e).setPositiveButton("Create") { _, _ ->
                val n = e.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "")
                if (n.isNotEmpty()) { File(filesDir, "projects/$n").mkdirs(); workspace = n; updTitle() }
            }.show()
        }, gap(10))
        dlg.show()
    }

    private fun askRepo() {
        val e = EditText(this).apply { hint = "owner/repo  या  https://github.com/owner/repo" }
        AlertDialog.Builder(this).setTitle("Use Git Repository").setView(e).setPositiveButton("Clone") { _, _ -> cloneRepo(e.text.toString().trim()) }.show()
    }

    private fun cloneRepo(input: String) {
        val m = Regex("([A-Za-z0-9_.-]+)/([A-Za-z0-9_-]+)(?:\\.git)?/?$").find(input)
        if (m == null) { addMsg("❌ repo का नाम सही नहीं"); return }
        val owner = m.groupValues[1]; val name = m.groupValues[2]
        status("⬇ $owner/$name ला रहा है")
        thread {
            try {
                val c = URL("https://api.github.com/repos/$owner/$name/zipball").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "AntiGem")
                val tk = prefs.getString("ghtoken", "") ?: ""
                if (tk.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $tk")
                val dir = File(filesDir, "projects/$name"); dir.mkdirs()
                ZipInputStream(c.inputStream).use { z ->
                    var en = z.nextEntry
                    while (en != null) {
                        val p = en.name.substringAfter('/')
                        if (p.isNotEmpty() && !p.contains("..")) {
                            val f = File(dir, p)
                            if (en.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> z.copyTo(o) } }
                        }
                        en = z.nextEntry
                    }
                }
                workspace = name
                runOnUiThread { updTitle(); addMsg("✅ $owner/$name workspace में आ गया") }; status(null)
            } catch (e: Exception) { say("❌ ${e.message}"); status(null) }
        }
    }

    // ===== Settings =====
    private fun showGroqDialog() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val k = field("Groq API key (gsk_...)", "groq", true); val m = field("Model", "model", false)
        if (m.text.isEmpty()) m.setText("openai/gpt-oss-20b")
        l.addView(k); l.addView(m)
        AlertDialog.Builder(this).setTitle("Groq").setView(l).setPositiveButton("Save") { _, _ ->
            prefs.edit().putString("groq", k.text.toString().trim()).putString("model", m.text.toString().trim()).apply()
            modelPill.text = "✨ " + prefs.getString("model", "") + " ▾"
        }.show()
    }

    private fun showGitHubDialog() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val t = field("GitHub token (repo + workflow)", "ghtoken", true); val r = field("Repo (owner/name)", "ghrepo", false)
        l.addView(t); l.addView(r)
        AlertDialog.Builder(this).setTitle("GitHub").setView(l).setPositiveButton("Save") { _, _ ->
            prefs.edit().putString("ghtoken", t.text.toString().trim()).putString("ghrepo", r.text.toString().trim()).apply()
        }.show()
    }

    private fun showClaudeDialog() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        l.addView(tv("Claude Code के लिए Claude का पेड प्लान या Anthropic API key चाहिए। अभी यह जुड़ा नहीं है। पैसे का इंतज़ाम होने पर key यहाँ सेव कर लीजिए।", 14f, SUB))
        l.addView(field("Anthropic API key (बाद के लिए)", "claudekey", true).also { claudeField = it })
        AlertDialog.Builder(this).setTitle("Claude Code").setView(l).setPositiveButton("Save") { _, _ ->
            prefs.edit().putString("claudekey", claudeField?.text.toString().trim()).apply()
        }.show()
    }
    private var claudeField: EditText? = null

    private fun showSettings() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        head.addView(tv("←", 26f).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { show(chatV) } })
        head.addView(tv("Settings & Preferences", 20f, FG, true), lp(0, -2, 1f))
        head.addView(tv("Done", 17f, ORANGE, true).apply { setOnClickListener { show(chatV) } })
        l.addView(head)
        fun row(icon: String, title: String, value: String, f: () -> Unit): View = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setOnClickListener { f() }
            addView(tv(icon, 20f).apply { gravity = Gravity.CENTER; background = round(CHIP, 12) }, lp(dp(40), dp(40)))
            addView(tv(title, 17f, FG, true).apply { setPadding(dp(14), 0, 0, 0) }, lp(0, -2, 1f))
            addView(tv("$value  ›", 14f, SUB))
        }
        fun group(vararg rows: View) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 20, LINE) }
            rows.forEach { g.addView(it) }
            l.addView(g, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
        val dark = prefs.getBoolean("dark", false)
        group(
            row("🎨", "Themes", if (dark) "Dark" else "Light") { prefs.edit().putBoolean("dark", !dark).apply(); recreate() },
            row("✨", "Groq", prefs.getString("model", "openai/gpt-oss-20b") ?: "") { showGroqDialog() },
            row("🐙", "GitHub", if ((prefs.getString("ghtoken", "") ?: "").isNotEmpty()) "Signed in" else "Signed out") { showGitHubDialog() }
        )
        group(
            row("🚀", "Antigravity", "Not connected") {
                AlertDialog.Builder(this).setTitle("Antigravity").setMessage("यह अभी जुड़ा नहीं है। इसका असली जुड़ाव Google के अकाउंट और इजाज़त पर टिका है।").setPositiveButton("OK", null).show()
            },
            row("✳", "Claude Code", if ((prefs.getString("claudekey", "") ?: "").isNotEmpty()) "Key saved" else "Not connected") { showClaudeDialog() }
        )
        group(
            row(">_", "Terminal & Shell", if (termuxReady()) "Termux ✓" else "Not set up") { showEnv() },
            row("ℹ", "About", "AntiGem 1.0") {}
        )
        show(ScrollView(this).apply { addView(l) })
    }

    // ===== चैट → GitHub → बिल्ड → APK (अपने आप सुधार के साथ) =====
    private fun gh(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer " + prefs.getString("ghtoken", ""))
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to txt
    }

    private fun latestRun(repo: String): Triple<String, String, String> {
        val (c, t) = gh("GET", "/repos/$repo/actions/runs?per_page=1")
        if (c != 200) return Triple("", "", "")
        fun g(re: String) = Regex(re).find(t)?.groupValues?.get(1) ?: ""
        return Triple(g("\"id\":\\s*(\\d+)"), g("\"status\":\\s*\"(\\w+)\""), g("\"conclusion\":\\s*\"(\\w+)\""))
    }

    private fun failLog(runId: String, repo: String): String {
        return try {
            val (c, t) = gh("GET", "/repos/$repo/actions/runs/$runId/jobs")
            if (c != 200) return ""
            val jobId = Regex("\"id\":\\s*(\\d+)").find(t)?.groupValues?.get(1) ?: return ""
            val c2 = URL("https://api.github.com/repos/$repo/actions/jobs/$jobId/logs").openConnection() as HttpURLConnection
            c2.setRequestProperty("Authorization", "Bearer " + prefs.getString("ghtoken", ""))
            c2.instanceFollowRedirects = true
            c2.inputStream.bufferedReader().readText().lines().filter { it.contains(" e: ") || it.contains("error:") || it.contains("FAILURE") }.takeLast(40).joinToString("\n")
        } catch (e: Exception) { "" }
    }

    private fun pushWorkspace(repo: String, dir: File): Int {
        var n = 0
        dir.walkTopDown().filter { it.isFile && it.length() < 900000 }.forEach { f ->
            val rel = f.relativeTo(dir).path
            val (c, t) = gh("GET", "/repos/$repo/contents/$rel")
            val sha = if (c == 200) Regex("\"sha\":\\s*\"([0-9a-f]+)\"").find(t)?.groupValues?.get(1) else null
            val b = JSONObject().put("message", "AntiGem: $rel").put("branch", "main")
                .put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            if (sha != null) b.put("sha", sha)
            val (pc, pt) = gh("PUT", "/repos/$repo/contents/$rel", b.toString())
            if (pc >= 300) throw Exception("$rel: $pc ${pt.take(120)}")
            n++; status("⬆ $rel")
        }
        return n
    }

    private fun buildFlow(attempt: Int) {
        val repo = prefs.getString("ghrepo", "") ?: ""
        if ((prefs.getString("ghtoken", "") ?: "").isEmpty() || repo.isEmpty()) {
            addMsg("⚙ पहले Settings → GitHub में token और repo डालिए।"); return
        }
        if (attempt == 0) addMsg("🔨 बिल्ड शुरू: ${wsName()} → $repo")
        else addMsg("🔁 सुधार की कोशिश $attempt/3")
        thread {
            try {
                val dir = wsDir()
                val wf = File(dir, ".github/workflows/build.yml")
                if (!wf.exists()) { wf.parentFile?.mkdirs(); wf.writeText(WORKFLOW.replace("DOLLAR", "$")) }
                status("⬆ GitHub पर भेज रहा है")
                val n = pushWorkspace(repo, dir)
                say("✅ $n फ़ाइलें GitHub पर गईं")
                val prev = latestRun(repo).first
                status("🔨 GitHub पर बिल्ड चल रहा है")
                var shown = ""
                for (i in 1..150) {
                    Thread.sleep(8000)
                    val (id, st, concl) = latestRun(repo)
                    if (id.isEmpty() || id == prev) continue
                    if (st != shown) { shown = st; status("�🔨 बिल्ड: $st") }                for (i in 1..150) {
                    Thread.sleep(8000)
                    val (id, st, concl) = latestRun(repo)
                    if (id.isEmpty() || id == prev) continue
                    if (st != shown) { shown = st; status("🔨 बिल्ड: $st") }
                    if (st == "completed") {
                        if (concl == "success") {
                            status(null)
                            runOnUiThread { addMsg("✅ बिल्ड पूरा!"); msgBox.addView(btn("⬇ APK डाउनलोड और इंस्टॉल") { installApk(repo) }, gap(8)) }
                        } else {
                            val log = failLog(id, repo)
                            say("❌ बिल्ड फ़ेल ($concl)")
                            if (attempt < 3 && log.isNotEmpty()) {
                                status("🛠 एरर ठीक किया जा रहा है")
                                fixFromLog(log, attempt + 1)
                            } else { status(null); say("⛔ अपने आप ठीक नहीं हो सका। GitHub → Actions में देखिए।") }
                        }
                        return@thread
                    }
                }
                status(null); say("⌛ बिल्ड बहुत देर चला, GitHub Actions में देखिए।")
            } catch (e: Exception) { status(null); say("❌ ${e.message}") }
        }
    }

    private fun fixFromLog(log: String, nextAttempt: Int) {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        msgs.put(JSONObject().put("role", "user").put("content",
            "बिल्ड फ़ेल हुआ। ये एरर है:\n$log\n\nसिर्फ़ जिन फ़ाइलों में गलती है उन्हें पूरा, सही कोड के साथ '// FILE:' वाले ब्लॉक में दोबारा लिखिए।"))
        val r = groq(msgs)
        runOnUiThread {
            if (r.startsWith("Error:")) { say(r); status(null); return@runOnUiThread }
            history.put(JSONObject().put("role", "user").put("content", "बिल्ड एरर ठीक करो"))
            history.put(JSONObject().put("role", "assistant").put("content", r))
            val saved = saveFilesQuiet(r)
            if (saved.isEmpty()) { say("⛔ सुधार में कोई फ़ाइल नहीं मिली"); status(null); return@runOnUiThread }
            addMsg("📁 सुधरी फ़ाइलें: " + saved.joinToString(", "))
            saveChat()
            buildFlow(nextAttempt)
        }
    }

    private fun installApk(repo: String) {
        downloadAndInstall("https://github.com/$repo/releases/download/latest/app-debug.apk", "app-debug.apk", { p, s -> status("⬇ APK $p%  $s") }) { ok ->
            status(null)
            runOnUiThread { addMsg(if (ok) "✅ APK डाउनलोड हो गया, इंस्टॉल खुल रहा है" else "❌ APK डाउनलोड नहीं हुआ") }
        }
    }

    // ===== कोड एडिटर =====
    private fun buildEditor(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(BG) }
        val name = EditText(this).apply { setText("main.txt"); hint = "file name"; setTextColor(FG); setHintTextColor(SUB) }
        val ed = EditText(this).apply {
            gravity = Gravity.TOP; typeface = Typeface.MONOSPACE; setTextColor(FG); setHintTextColor(SUB)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val undo = ArrayList<String>()
        val row = LinearLayout(this)
        row.addView(outBtn("📂") {
            val files = wsDir().walkTopDown().filter { it.isFile }.map { it.relativeTo(wsDir()).path }.toList()
            if (files.isEmpty()) Toast.makeText(this, "कोई फ़ाइल नहीं", Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(this).setTitle("Files").setItems(files.toTypedArray()) { _, w -> name.setText(files[w]); ed.setText(File(wsDir(), files[w]).readText()) }.show()
        }, lp(0, -2, 1f))
        row.addView(outBtn("Open") { val f = File(wsDir(), name.text.toString()); ed.setText(if (f.exists()) f.readText() else "") }, lp(0, -2, 1f))
        row.addView(btn("💾 Save") {
            val f = File(wsDir(), name.text.toString()); f.parentFile?.mkdirs(); undo.add(ed.text.toString()); f.writeText(ed.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }, lp(0, -2, 1f))
        row.addView(outBtn("↶") { if (undo.isNotEmpty()) ed.setText(undo.removeAt(undo.size - 1)) }, lp(0, -2, 1f))
        row.addView(outBtn("▶ Run") {
            val file = name.text.toString().trim()
            val cmd = when {
                file.endsWith(".py") -> "python3 '$file'"
                file.endsWith(".js") -> "node '$file'"
                file.endsWith(".java") -> "javac '$file' && java '${file.removeSuffix(".java")}'"
                file.endsWith(".c") -> "clang '$file' -o a.out && ./a.out"
                file.endsWith(".html") -> null
                else -> "sh '$file'"
            }
            File(wsDir(), file).apply { parentFile?.mkdirs(); writeText(ed.text.toString()) }
            if (cmd == null) { show(browserV); browserWv.loadUrl("file://" + File(wsDir(), file).absolutePath) }
            else { show(termV); runCmd(cmd) { termOut.append(it + "\n") } }
        }, lp(0, -2, 1f))
        l.addView(name); l.addView(row); l.addView(ed, lp(-1, 0, 1f))
        return l
    }

    // ===== टर्मिनल =====
    private fun buildTerminal(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        termOut = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)); setTextIsSelectable(true)
            text = if (termuxReady()) "➜ Termux से जुड़ा है\n" else "➜ Termux जुड़ा नहीं है, ऐप का sh चलेगा\n"
        }
        val sv = ScrollView(this).apply { addView(termOut) }
        val inp = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "command..."; typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val row = LinearLayout(this)
        row.addView(inp, lp(0, -2, 1f))
        row.addView(btn("Run") {
            val cmd = inp.text.toString(); if (cmd.isBlank()) return@btn
            inp.setText(""); termOut.append("➜ ~ $cmd\n")
            runCmd(cmd) { termOut.append(it + "\n"); sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
        })
        row.addView(outBtn("📋") { copy(termOut.text.toString()) })
        row.addView(outBtn("⏹") { proc?.destroy() })
        l.addView(sv, lp(-1, 0, 1f)); l.addView(row)
        return l
    }

    // ===== ब्राउज़र =====
    private fun buildBrowser(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val url = EditText(this).apply { hint = "Search or type URL"; setSingleLine(); setTextColor(FG); setHintTextColor(SUB); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        browserWv = WebView(this).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient() }
        fun go(t: String) {
            val x = t.trim()
            browserWv.loadUrl(when {
                x.startsWith("http") || x.startsWith("file://") -> x
                x.contains(".") && !x.contains(" ") -> "https://$x"
                else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(x, "UTF-8")
            })
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(url, lp(0, -2, 1f))
        row.addView(outBtn("Go") { go(url.text.toString()) })
        row.addView(outBtn("⟳") { browserWv.reload() })
        val sc = LinearLayout(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        sc.addView(outBtn("Google") { browserWv.loadUrl("https://www.google.com") }, lp(0, -2, 1f))
        sc.addView(outBtn("YouTube") { browserWv.loadUrl("https://www.youtube.com") }, lp(0, -2, 1f))
        l.addView(row); l.addView(sc); l.addView(browserWv, lp(-1, 0, 1f))
        browserWv.loadUrl("https://www.google.com")
        return l
    }
    }
