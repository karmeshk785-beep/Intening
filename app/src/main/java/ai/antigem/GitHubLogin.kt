// FILE: app/src/main/java/ai/antigem/GitHubLogin.kt
package ai.antigem

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

object GitHubLogin {
    private const val CLIENT_ID = "Ov23lidz7pq39lAK2n1Y"

    private fun request(url: String, body: String?, token: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "AntiGem")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        } else {
            c.requestMethod = "GET"
        }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to txt
    }

    private fun show(act: Activity, title: String, msg: String) {
        act.runOnUiThread {
            AlertDialog.Builder(act).setTitle(title).setMessage(msg)
                .setPositiveButton("ठीक", null).show()
        }
    }

    fun start(act: Activity, onDone: () -> Unit) {
        thread {
            try {
                val (c1, t1) = request(
                    "https://github.com/login/device/code",
                    "client_id=$CLIENT_ID&scope=" + URLEncoder.encode("repo workflow", "UTF-8")
                )
                val d = JSONObject(t1)
                val code = d.optString("user_code", "")
                if (c1 >= 400 || code.isEmpty()) {
                    show(act, "लॉगिन एरर", "GitHub ने कोड नहीं दिया:\n$t1")
                    return@thread
                }
                val device = d.getString("device_code")
                val uri = d.getString("verification_uri")
                var interval = d.optInt("interval", 5)

                act.runOnUiThread {
                    AlertDialog.Builder(act).setTitle("GitHub लॉगिन")
                        .setMessage("यह कोड GitHub पेज पर डालिए:\n\n$code\n\nडालने के बाद ऐप में वापस आइए और 30 सेकंड रुकिए।")
                        .setPositiveButton("GitHub खोलें") { _, _ ->
                            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                        .setNegativeButton("बंद", null).show()
                }

                val end = System.currentTimeMillis() + 900000L
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(interval * 1000L)
                    val (_, t2) = request(
                        "https://github.com/login/oauth/access_token",
                        "client_id=$CLIENT_ID&device_code=$device&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    )
                    val r = JSONObject(t2)
                    if (r.has("access_token")) {
                        val token = r.getString("access_token")
                        act.getSharedPreferences("antigem", Activity.MODE_PRIVATE)
                            .edit().putString("ghtoken", token).commit()

                        val (c3, t3) = request("https://api.github.com/user", null, token)
                        val login = if (c3 < 400) JSONObject(t3).optString("login", "") else ""
                        act.runOnUiThread {
                            Toast.makeText(act, "GitHub लॉगिन हो गया ✅ ${if (login.isNotEmpty()) "($login)" else ""}", Toast.LENGTH_LONG).show()
                            onDone()
                        }
                        return@thread
                    }
                    val err = r.optString("error")
                    when (err) {
                        "authorization_pending" -> {}
                        "slow_down" -> interval += 5
                        else -> {
                            show(act, "लॉगिन रुका", "GitHub ने बताया: $t2")
                            return@thread
                        }
                    }
                }
                show(act, "लॉगिन समय खत्म", "कोड की समय सीमा खत्म हो गई। फिर से लॉगिन दबाइए।")
            } catch (e: Exception) {
                show(act, "लॉगिन एरर", e.toString())
            }
        }
    }
}
