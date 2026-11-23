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
    private const val CLIENT_ID = "0v23lidz7pq39lAK2n1Y"

    private fun post(url: String, body: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val txt = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return JSONObject(txt).put("_url", url).put("_code", c.responseCode)
    }

    fun start(act: Activity, onDone: () -> Unit) {
        thread {
            try {
                val d = post("https://github.com/login/device/code",
                    "client_id=$CLIENT_ID&scope=" + URLEncoder.encode("repo workflow", "UTF-8"))
                val code = d.optString("user_code", "")
                if (code.isEmpty()) throw Exception(d.toString())
                val device = d.getString("device_code")
                val uri = d.getString("verification_uri")
                var interval = d.optInt("interval", 5)
                act.runOnUiThread {
                    AlertDialog.Builder(act).setTitle("GitHub लॉगिन")
                        .setMessage("यह कोड याद रखिए और GitHub पेज पर डालिए:\n\n$code")
                        .setPositiveButton("GitHub खोलें") { _, _ ->
                            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                        .setNegativeButton("बंद", null).show()
                }
                val end = System.currentTimeMillis() + 900000L
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(interval * 1000L)
                    val r = post("https://github.com/login/oauth/access_token",
                        "client_id=$CLIENT_ID&device_code=$device&grant_type=urn:ietf:params:oauth:grant-type:device_code")
                    if (r.has("access_token")) {
                        act.getSharedPreferences("antigem", Activity.MODE_PRIVATE)
                            .edit().putString("ghtoken", r.getString("access_token")).apply()
                        act.runOnUiThread { onDone() }
                        return@thread
                    }
                    val err = r.optString("error")
                    if (err == "slow_down") interval += 5
                    else if (err != "authorization_pending") return@thread
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    Toast.makeText(act, "लॉगिन एरर: " + e.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
