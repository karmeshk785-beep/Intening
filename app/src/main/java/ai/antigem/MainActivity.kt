// FILE: app/src/main/java/ai/antigem/MainActivity.kt
package ai.antigem

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
import kotlin.concurrent.thread

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
    private val prefs by lazy { getSharedPreferences("antigem", MODE_PRIVATE) }
    private var history = JSONArray()
    private var chatId = System.currentTimeMillis().toString()
    private var workspace = ""
    private var proc: Process? = null
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var permStatus: TextView
    private lateinit var contBtn: Button
    private lateinit var chatV: View
    private lateinit var editorV: View
    private lateinit var termV: View
    private lateinit var browserV: View
    private lateinit var msgBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatTitle: TextView

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun btn(t: String, c: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundColor(ORANGE); setOnClickListener { c() }
    }
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun say(t: String) = runOnUiThread { addMsg(t) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F8F9FB")) }
        content = FrameLayout(this)
        nav = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(content, lp(-1, 0, 1f)); root.addView(nav, lp(-1, -2))
        setContentView(root)
        chatV = buildChat(); editorV = buildEditor(); termV = buildTerminal(); browserV = buildBrowser()
        listOf("💬 चैट" to chatV, "<> एडिटर" to editorV, ">_ टर्मिनल" to termV, "🌐 ब्राउज़र" to browserV).forEach { (n, v) ->
            nav.addView(btn(n) { show(v) }, lp(0, -2, 1f))
        }
        showPermissions()
    }

    private fun show(v: View) { content.removeAllViews(); content.addView(v) }

    // ===== 1. परमिशन =====
    private fun card(title: String, tag: String, desc: String, p: String?): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Color.WHITE) }
        l.addView(TextView(this).apply { text = "$title  [$tag]"; textSize = 18f; setTypeface(null, Typeface.BOLD) })
        l.addView(TextView(this).apply { text = desc })
        l.addView(btn("Grant Access") { if (p != null && Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(p), 1) })
        val w = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        w.addView(l, lp(-1, -2)); return w
    }

    private fun showPermissions() {
        nav.visibility = View.GONE
        val sv = ScrollView(this)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(20)) }
        l.addView(TextView(this).apply { text = "Welcome to AntiGem"; textSize = 28f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) })
        l.addView(TextView(this).apply { text = "Configure your permissions to enable background execution, terminal tools, and local project storage."; gravity = Gravity.CENTER })
        l.addView(card("Notifications & Services", "Required", "Persistent background service, live status alerts, updates.", Manifest.permission.POST_NOTIFICATIONS))
        l.addView(card("Storage & /sdcard", "Optional", "Import packages, inspect files, manage code workspaces.", Manifest.permission.READ_EXTERNAL_STORAGE))
        l.addView(card("Microphone & Voice", "Optional", "Voice-to-text dictation and voice commands in chat.", Manifest.permission.RECORD_AUDIO))
        permStatus = TextView(this).apply { gravity = Gravity.CENTER }
        contBtn = btn("") { if (hasNotif()) showEnv() }
        l.addView(permStatus); l.addView(contBtn)
        sv.addView(l); content.removeAllViews(); content.addView(sv); refreshPerm()
    }

    private fun refreshPerm() {
        if (!::contBtn.isInitialized) return
        if (hasNotif()) { permStatus.text = "✓ Permission active"; permStatus.setTextColor(Color.parseColor("#16A34A")); contBtn.text = "Continue to AntiGem 🚀" }
        else { permStatus.text = "👉 Please enable Notifications above to proceed"; permStatus.setTextColor(Color.RED); contBtn.text = "Notifications Required to Proceed" }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(c, p, r); refreshPerm() }

    // ===== 2. Environment =====
    private fun showEnv() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(20)) }
        l.addView(TextView(this).apply { text = "AntiGem Environment"; textSize = 24f; setTypeface(null, Typeface.BOLD) })
        l.addView(TextView(this).apply { text = "Local Linux & Terminal Setup\n\nInitialize Linux Environment"; textSize = 18f })
        l.addView(TextView(this).apply { text = "CPU Arch: ${Build.SUPPORTED_ABIS.firstOrNull()}\nAddress Space: 39-bit (VA39)\nLSE Atomics: Supported\n" })
        val rg = RadioGroup(this)
        listOf("Auto Download from GitHub (Official, ~350MB)", "Select Local ZIP Archive", "Base Minimum Termux (Minimal, ~33MB)").forEachIndexed { i, t ->
            rg.addView(RadioButton(this).apply { text = t; id = 100 + i; isChecked = i == 0 })
        }
        l.addView(rg)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val log = TextView(this).apply { setBackgroundColor(Color.BLACK); setTextColor(Color.GREEN); typeface = Typeface.MONOSPACE; visibility = View.GONE }
        l.addView(btn("Download & Install AntiGem (~350MB)") {
            bar.visibility = View.VISIBLE; log.visibility = View.VISIBLE
            thread {
                listOf("Downloading Bootstrap...", "Extracting & Linking Files...", "Verifying Environment...", "Done ✓").forEachIndexed { i, t ->
                    runOnUiThread { bar.progress = (i + 1) * 25; log.append("$t\n") }; Thread.sleep(700)
                }
                runOnUiThread { showTermSetup() }
            }
        })
        l.addView(bar); l.addView(log)
        l.addView(btn("Skip Setup & Open AntiGem Chat") { showTermSetup() })
        content.removeAllViews(); content.addView(l)
    }

    // ===== 3. Terminal App Setup =====
    private fun showTermSetup() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(20)) }
        l.addView(TextView(this).apply { text = "Terminal App Setup"; textSize = 26f; setTypeface(null, Typeface.BOLD) })
        l.addView(TextView(this).apply { text = "Choose how you want to access the Linux command line on your device." })
        val prev = TextView(this).apply { textSize = 18f; setPadding(0, dp(16), 0, dp(16)) }
        val sw = Switch(this).apply { text = "Standalone Terminal App Icon"; isChecked = true }
        val rg = RadioGroup(this)
        rg.addView(RadioButton(this).apply { text = "AntiTerm – Modern unified icon matching AntiGem"; id = 1; isChecked = true })
        rg.addView(RadioButton(this).apply { text = "Termux – Classic terminal >_ prompt icon"; id = 2 })
        fun upd() { prev.text = "HOME SCREEN PREVIEW\nAntiGem (Chat & IDE)  +  " + (if (rg.checkedRadioButtonId == 1) "AntiTerm" else "Termux") + " (Terminal)" }
        rg.setOnCheckedChangeListener { _, _ -> upd() }; upd()
        l.addView(prev); l.addView(sw); l.addView(rg)
        l.addView(btn("✓ Save & Continue") {
            prefs.edit().putBoolean("standalone", sw.isChecked).putInt("termIcon", rg.checkedRadioButtonId).apply()
            nav.visibility = View.VISIBLE; show(chatV)
            showAgent { showUpdate() }
        })
        l.addView(TextView(this).apply { text = "You can always change or toggle this anytime in Settings." })
        content.removeAllViews(); content.addView(l)
    }

    // ===== Agent / Update / Settings / Workspace / Menu dialogs =====
    private fun showAgent(then: (() -> Unit)? = null) {
        val opts = arrayOf("Groq (free, working)", "Antigravity", "Claude Code", "Both")
        AlertDialog.Builder(this).setTitle("Choose your agent")
            .setSingleChoiceItems(opts, prefs.getInt("agent", 0)) { _, w -> prefs.edit().putInt("agent", w).apply() }
            .setPositiveButton("Continue") { _, _ -> then?.invoke() }.show()
    }

    private fun showUpdate() {
        val open = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Santoshkurmi/antigem/releases"))) }
        AlertDialog.Builder(this).setTitle("New Update Available")
            .setMessage("Release Update\nv1.0.17 → v1.2.1\n\nWHAT'S NEW\n1. Discontinuation of com.termux and shifted to ai.antigem (please download the 2.0.0+ version from github)\n2. It has all newly added features and fixes of 2.0.0")
            .setNeutralButton("GitHub") { _, _ -> open() }.setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> open() }.show()
    }

    private fun field(h: String, k: String, secret: Boolean) = EditText(this).apply {
        hint = h; setText(prefs.getString(k, ""))
        inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
    }

    private fun showSettings() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val g = field("Groq API key (gsk_...)", "groq", true)
        val t = field("GitHub token (repo + workflow)", "ghtoken", true)
        val r = field("GitHub repo (owner/name)", "ghrepo", false)
        if (r.text.isEmpty()) r.setText("karmeshk785-beep/Intening")
        l.addView(g); l.addView(t); l.addView(r); l.addView(btn("Choose agent") { showAgent() })
        AlertDialog.Builder(this).setTitle("Settings").setView(l).setPositiveButton("Save") { _, _ ->
            prefs.edit().putString("groq", g.text.toString().trim()).putString("ghtoken", t.text.toString().trim())
                .putString("ghrepo", r.text.toString().trim()).apply()
        }.show()
    }

    private fun wsDir(): File {
        val d = if (workspace.isEmpty()) File(filesDir, "general") else File(filesDir, "projects/$workspace")
        d.mkdirs(); return d
    }

    private fun showWorkspace() {
        val names = ArrayList<String>(); names.add("General (No Workspace)")
        File(filesDir, "projects").listFiles()?.filter { it.isDirectory }?.forEach { names.add(it.name) }
        AlertDialog.Builder(this).setTitle("Select Workspace")
            .setItems(names.toTypedArray()) { _, w -> workspace = if (w == 0) "" else names[w]; updTitle() }
            .setPositiveButton("＋ New folder") { _, _ ->
                val e = EditText(this)
                AlertDialog.Builder(this).setTitle("Folder name").setView(e).setPositiveButton("Create") { _, _ ->
                    val n = e.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "")
                    if (n.isNotEmpty()) { File(filesDir, "projects/$n").mkdirs(); workspace = n; updTitle() }
                }.show()
            }.show()
    }

    private fun showMenu() {
        val all = loadChats(); val items = ArrayList<String>(); val ids = ArrayList<String>()
        items.add("＋ New Chat"); ids.add("")
        for (i in all.length() - 1 downTo 0) { val o = all.getJSONObject(i); items.add(o.getString("title")); ids.add(o.getString("id")) }
        AlertDialog.Builder(this).setTitle("Chats")
            .setItems(items.toTypedArray()) { _, w -> if (w == 0) newChat() else openChat(ids[w]) }
            .setNeutralButton("📁 Workspace") { _, _ -> showWorkspace() }
            .setPositiveButton("⚙ Settings") { _, _ -> showSettings() }.show()
    }

    // ===== चैट इतिहास =====
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

    private fun newChat() { saveChat(); history = JSONArray(); chatId = System.currentTimeMillis().toString(); msgBox.removeAllViews(); updTitle() }

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

    private fun updTitle() { chatTitle.text = "New Chat · " + (if (workspace.isEmpty()) "General" else workspace) }

    private fun addMsg(t: String) {
        msgBox.addView(TextView(this).apply { text = t; textSize = 15f; setTextIsSelectable(true); setPadding(dp(4), dp(6), dp(4), dp(6)) })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ===== चैट स्क्रीन =====
    private fun buildChat(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chatTitle = TextView(this).apply { textSize = 17f; setTypeface(null, Typeface.BOLD) }
        top.addView(btn("☰") { showMenu() }); top.addView(chatTitle, lp(0, -2, 1f))
        top.addView(btn("🔨 Build") { buildFlow() }); top.addView(btn("⚙") { showSettings() })
        msgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { addView(msgBox) }
        val inp = EditText(this).apply { hint = "Reply to Models...   ($ cmd = टर्मिनल, /build = बिल्ड)" }
        val row = LinearLayout(this)
        row.addView(inp, lp(0, -2, 1f))
        row.addView(btn("Send") { val q = inp.text.toString().trim(); if (q.isNotEmpty()) { inp.setText(""); sendMsg(q) } })
        l.addView(top); l.addView(chatScroll, lp(-1, 0, 1f)); l.addView(row)
        updTitle(); return l
    }

    private fun sendMsg(q: String) {
        addMsg("🧑 $q")
        if (q.startsWith("$ ")) {
            val sb = StringBuilder()
            runShell(q.substring(2), { sb.append(it).append("\n") }, { addMsg(sb.toString().ifBlank { "(no output)" }) }); return
        }
        if (q == "/build") { buildFlow(); return }
        history.put(JSONObject().put("role", "user").put("content", q))
        if (prefs.getInt("agent", 0) in 1..3 && !prefs.getBoolean("agentNote", false)) {
            prefs.edit().putBoolean("agentNote", true).apply()
            addMsg("ℹ️ Claude Code / Antigravity not found. अभी Groq से जवाब आ रहा है।")
        }
        askGroq { r -> addMsg("🤖 $r"); saveFiles(r); saveChat() }
    }

    private fun askGroq(done: (String) -> Unit) {
        val key = prefs.getString("groq", "") ?: ""
        if (key.isEmpty()) { done("⚙ Settings में पहले Groq API key डालिए।"); return }
        thread {
            val r = try {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content",
                    "You are a coding assistant inside an Android app. Reply in the user's language. When you output a file, put it in its own code block whose FIRST line is a comment like '// FILE: app/src/main/...' with the relative path."))
                for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
                val body = JSONObject().put("model", "llama-3.3-70b-versatile").put("messages", msgs)
                val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val txt = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
                if (c.responseCode >= 400) "Error: $txt" else {
                    val t = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    history.put(JSONObject().put("role", "assistant").put("content", t)); t
                }
            } catch (e: Exception) { "Error: ${e.message}" }
            runOnUiThread { done(r) }
        }
    }

    private fun saveFiles(r: String) {
        Regex("```[a-zA-Z]*\\n([\\s\\S]*?)```").findAll(r).forEach { m ->
            val body = m.groupValues[1]
            val f = Regex("FILE:\\s*(\\S+)").find(body.lineSequence().first())?.groupValues?.get(1) ?: return@forEach
            if (f.contains("..") || f.startsWith("/")) return@forEach
            val file = File(wsDir(), f); file.parentFile?.mkdirs(); file.writeText(body); addMsg("📁 Saved: $f")
        }
    }

    // ===== चैट → GitHub → बिल्ड → APK =====
    private fun gh(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer " + prefs.getString("ghtoken", ""))
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-T        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
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

    private fun buildFlow() {
        val repo = prefs.getString("ghrepo", "") ?: ""
        if (prefs.getString("ghtoken", "").isNullOrEmpty() || repo.isEmpty()) { addMsg("⚙ पहले Settings में GitHub token और repo डालिए।"); return }
        addMsg("🔨 बिल्ड शुरू: ${wsDir().name} → $repo")
        thread {
            try {
                val wf = File(wsDir(), ".github/workflows/build.yml")
                if (!wf.exists()) { wf.parentFile?.mkdirs(); wf.writeText(WORKFLOW.replace("DOLLAR", "$")) }
                val prev = latestRun(repo).first
                val dir = wsDir(); var n = 0
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(dir).path
                    val (c, t) = gh("GET", "/repos/$repo/contents/$rel")
                    val sha = if (c == 200) Regex("\"sha\":\\s*\"([0-9a-f]+)\"").find(t)?.groupValues?.get(1) else null
                    val b = JSONObject().put("message", "AntiGem: $rel").put("branch", "main")
                        .put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                    if (sha != null) b.put("sha", sha)
                    val (pc, pt) = gh("PUT", "/repos/$repo/contents/$rel", b.toString())
                    if (pc >= 300) { say("❌ $rel: $pc ${pt.take(120)}"); return@thread }
                    n++; say("⬆ $rel")
                }
                say("✅ $n फ़ाइलें GitHub पर गईं। बिल्ड का इंतज़ार...")
                var shown = ""
                for (i in 1..120) {
                    Thread.sleep(10000)
                    val (id, st, concl) = latestRun(repo)
                    if (id.isEmpty() || id == prev) continue
                    if (st != shown) { shown = st; say("⏳ बिल्ड: $st") }
                    if (st == "completed") {
                        if (concl == "success") runOnUiThread { addMsg("✅ बिल्ड पूरा!"); msgBox.addView(btn("⬇ APK डाउनलोड और इंस्टॉल") { installApk(repo) }) }
                        else say("❌ बिल्ड फ़ेल ($concl)। GitHub → Actions में लॉग देखिए।")
                        return@thread
                    }
                }
                say("⌛ बहुत देर हो गई, GitHub Actions खुद देखिए।")
            } catch (e: Exception) { say("❌ ${e.message}") }
        }
    }

    private fun installApk(repo: String) {
        File(getExternalFilesDir(null), "app-debug.apk").delete()
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse("https://github.com/$repo/releases/download/latest/app-debug.apk"))
            .setTitle("app-debug.apk").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, null, "app-debug.apk")
        val id = dm.enqueue(req); addMsg("⬇ डाउनलोड शुरू...")
        thread {
            while (true) {
                Thread.sleep(1500)
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (!c.moveToFirst()) { c.close(); break }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); c.close()
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    runOnUiThread {
                        val u = dm.getUriForDownloadedFile(id)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }; break
                }
                if (st == DownloadManager.STATUS_FAILED) { say("❌ डाउनलोड फ़ेल"); break }
            }
        }
    }

    // ===== कोड एडिटर =====
    private fun buildEditor(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val name = EditText(this).apply { setText("main.txt"); hint = "file name" }
        val ed = EditText(this).apply {
            gravity = Gravity.TOP; typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val undo = ArrayList<String>()
        val row = LinearLayout(this)
        row.addView(btn("📂") {
            val files = wsDir().walkTopDown().filter { it.isFile }.map { it.relativeTo(wsDir()).path }.toList()
            AlertDialog.Builder(this).setTitle("Files").setItems(files.toTypedArray()) { _, w -> name.setText(files[w]); ed.setText(File(wsDir(), files[w]).readText()) }.show()
        }, lp(0, -2, 1f))
        row.addView(btn("Open") { val f = File(wsDir(), name.text.toString()); ed.setText(if (f.exists()) f.readText() else "") }, lp(0, -2, 1f))
        row.addView(btn("💾 Save") {
            val f = File(wsDir(), name.text.toString()); f.parentFile?.mkdirs(); undo.add(ed.text.toString()); f.writeText(ed.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }, lp(0, -2, 1f))
        row.addView(btn("↶") { if (undo.isNotEmpty()) ed.setText(undo.removeAt(undo.size - 1)) }, lp(0, -2, 1f))
        l.addView(name); l.addView(row); l.addView(ed, lp(-1, 0, 1f))
        return l
    }

    // ===== टर्मिनल =====
    private fun runShell(cmd: String, onLine: (String) -> Unit, onDone: () -> Unit = {}) {
        thread {
            try {
                val p = ProcessBuilder("sh", "-c", cmd).directory(wsDir()).redirectErrorStream(true).start(); proc = p
                p.inputStream.bufferedReader().forEachLine { l -> runOnUiThread { onLine(l) } }; p.waitFor()
            } catch (e: Exception) { runOnUiThread { onLine("error: ${e.message}") } }
            runOnUiThread { onDone() }
        }
    }

    private fun buildTerminal(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val out = TextView(this).apply { setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)); setTextIsSelectable(true); text = "➜ ~\n" }
        val sv = ScrollView(this).apply { addView(out) }
        val inp = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "command..."; typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val row = LinearLayout(this)
        row.addView(inp, lp(0, -2, 1f))
        row.addView(btn("Run") {
            val cmd = inp.text.toString(); if (cmd.isBlank()) return@btn
            inp.setText(""); out.append("➜ ~ $cmd\n")
            runShell(cmd, { out.append(it + "\n"); sv.post { sv.fullScroll(View.FOCUS_DOWN) } })
        })
        row.addView(btn("⏹") { proc?.destroy() })
        l.addView(sv, lp(-1, 0, 1f)); l.addView(row)
        return l
    }

    // ===== ब्राउज़र =====
    private fun buildBrowser(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val url = EditText(this).apply { hint = "Search or type URL"; setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val wv = WebView(this).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient() }
        fun go(t: String) {
            val x = t.trim()
            wv.loadUrl(if (x.startsWith("http")) x else if (x.contains(".") && !x.contains(" ")) "https://$x" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(x, "UTF-8"))
        }
        val row = LinearLayout(this)
        row.addView(url, lp(0, -2, 1f)); row.addView(btn("Go") { go(url.text.toString()) }); row.addView(btn("⟳") { wv.reload() })
        val sc = LinearLayout(this)
        sc.addView(btn("Google") { wv.loadUrl("https://www.google.com") }, lp(0, -2, 1f))
        sc.addView(btn("YouTube") { wv.loadUrl("https://www.youtube.com") }, lp(0, -2, 1f))
        l.addView(row); l.addView(sc); l.addView(wv, lp(-1, 0, 1f))
        wv.loadUrl("https://www.google.com")
        return l
    }
}
