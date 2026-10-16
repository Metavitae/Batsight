package com.metavitae.soundlab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * The real app (founder 2026-10-15: "The UI doesn't look like chat wants... it's lost on me what I'm doing"):
 * Chat's own screen designs (Art/Working "... source ... .html": Onboarding, Screen 2, Screens 1/3/4) run in a
 * WebView (assets/app/app.html) and this bridge feeds them real measurements instead of made-up numbers.
 * Lab tools stay behind the small "Lab" link.
 */
object AppScreen {
    private var web: WebView? = null
    private var watch: WatchEngine? = null
    private var live: LiveEngine? = null

    private fun js(act: Activity, code: String) = act.runOnUiThread { web?.evaluateJavascript(code, null) }

    fun stopAll() {
        watch?.let { Thread { it.stop() }.start() }; watch = null
        live?.let { Thread { it.stop() }.start() }; live = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(act: MainActivity, openLab: () -> Unit) {
        val w = WebView(act)
        web = w
        w.setBackgroundColor(Color.parseColor("#0B1526"))
        w.settings.javaScriptEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = object : WebChromeClient() {
            // the camera picture inside Chat's viewfinder (getUserMedia); the app already holds the camera permission
            override fun onPermissionRequest(r: PermissionRequest) { act.runOnUiThread { r.grant(r.resources) } }
        }
        val prefs = act.getSharedPreferences("app", Context.MODE_PRIVATE)
        w.addJavascriptInterface(object {
            @JavascriptInterface fun phone() = if (Sonar.is8T) "8t" else "poco"
            @JavascriptInterface fun tourSeen() = prefs.getBoolean("tour", false)
            @JavascriptInterface fun tourDone() { prefs.edit().putBoolean("tour", true).apply() }
            @JavascriptInterface fun lab() = act.runOnUiThread { stopAll(); openLab() }
            @JavascriptInterface fun buzz(ms: Int) {
                try { act.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(ms.toLong().coerceIn(10, 300), 180)) } catch (_: Exception) {}
            }
            @JavascriptInterface fun save(name: String, json: String) { Results.save(act, name, "application/json", json.toByteArray()) }

            // ---- Screen 2: one Listen, answer as JSON
            @JavascriptInterface fun focusListen() {
                stopAll()
                val id = "app_focus_${Sonar.phoneName}_${Field.day()}"
                Thread {
                    val r = try { Sonar.listen(act, id) } catch (t: Throwable) { null }
                    val res = if (r == null) "{\"state\":\"error\"}" else {
                        // up to 3 sure echoes: the 3 strongest, shown nearest first (Chat's "Two things found")
                        val sure = r.channels.flatMap { it.candidates }.filter { it.sure }.sortedByDescending { it.strength }
                        val picks = ArrayList<Sonar.Candidate>()
                        for (c in sure) if (picks.all { kotlin.math.abs(it.m - c.m) > 0.1 }) picks.add(c)
                        val p3 = picks.take(3).sortedBy { it.m }
                        Json().put("id", id).put("state", r.state).put("d", r.best?.m)
                            .raw("picks", Json.list(p3.map { f2(it.m) })).put("more", picks.size > 3).toString()
                    }
                    js(act, "window.batFocus && batFocus($res)")
                }.start()
            }

            // ---- Screen 4: Watch
            @JavascriptInterface fun watchStart() {
                stopAll()
                val e = WatchEngine(act, "app_watch_${Sonar.phoneName}_${Field.day()}") { ev ->
                    js(act, "window.batWatch && batWatch(" + Json().put("state", ev.state).put("d", ev.whereM).put("t", ev.t).toString() + ")")
                }
                watch = e; e.start()
            }
            @JavascriptInterface fun watchKeep() { watch?.adopt() }
            @JavascriptInterface fun stop() { stopAll() }

            // ---- Screens 1 and 3: live pings with heading
            @JavascriptInterface fun liveStart(mode: String) {
                stopAll()
                val e = LiveEngine(act, "app_${mode}_${Sonar.phoneName}_${Field.day()}") { p ->
                    js(act, "window.batLive && batLive(" + Json().put("h", p.heading).put("d", p.nearM).put("far", p.far).put("heard", p.heard).toString() + ")")
                }
                live = e; e.start()
            }
        }, "Bat")
        act.backHook = { w.evaluateJavascript("window.batBack ? batBack() : false") { r -> if (r != "true") { stopAll(); act.finish() } } }
        act.setContentView(w)
        w.loadUrl("file:///android_asset/app/app.html")
    }
}
