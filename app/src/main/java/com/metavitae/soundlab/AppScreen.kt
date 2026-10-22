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
    private var hive: Hive? = null
    @Volatile private var hsMine: Double? = null; @Volatile private var hsPeer: Double? = null; @Volatile private var hsPeerTag = "?"

    /** Sound handshake (lab task 4c recipe: 8-15 kHz, 20 ms, -20 dBFS). Each phone chirps at its own slot and records
     *  both chirps; "other minus own" arrival on each phone, added together, gives twice the distance / C
     *  (two-way ranging: no clock sync needed). SELF = own speaker-to-mic ~2 cm (lab, matched to tape). */
    private fun handshake(act: Activity, tag: String) {
        val hs = Signals.one(Signals.parse("chirp,f0=8000,f1=15000,ms=20,db=-20")[0], Sonar.RATE)
        val slot = ((tag[0] - 'A').coerceIn(0, 2)) * 800 + 300
        val total = Sonar.RATE * 2600 / 1000
        val l = FloatArray(total); val at = slot * Sonar.RATE / 1000
        for (k in hs.indices) l[at + k] = hs[k]
        val am = act.getSystemService(android.media.AudioManager::class.java)
        val vol = (am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * 0.6).toInt()
        val id = "app_handshake_${Sonar.phoneName}_${tag}_${Field.day()}"
        val job = PlayRecord(act, PlayRecord.Params(id, null, null, "unprocessed", 1, Sonar.RATE, -1, "speaker", vol, 200, 600, 0, 0, false, false, true, stereo = Stereo(l, l.copyOf()))) {}
        try { job.run() } catch (t: Throwable) { job.restoreVolume(); js(act, "window.batHive && batHive({type:'hserr'})"); return }
        val x = job.lastSamples ?: return
        val ch = job.lastChannels
        val mono = DoubleArray(x.size / ch) { x[it * ch] / 32768.0 }
        val (mr, mi) = Dsp.matched(mono, hs.map { it.toDouble() }.toDoubleArray())
        val e = Dsp.env(mr, mi)
        var own = 0; for (i in e.indices) if (e[i] > e[own]) own = i
        val guard = Sonar.RATE * 30 / 1000
        var oth = -1; for (i in e.indices) if (kotlin.math.abs(i - own) > guard && (oth < 0 || e[i] > e[oth])) oth = i
        val ok = oth >= 0 && e[oth] > 20 * medianOf(e)
        val diff = if (ok) Dsp.interpPeak(e, oth) - Dsp.interpPeak(e, own) else Double.NaN
        Results.save(act, "${id}_hs.json", "application/json", Json().put("tag", tag).put("own", own).put("other", oth).put("diff_samples", diff)
            .put("own_level", e[own]).put("other_level", if (oth >= 0) e[oth] else 0.0).put("ok", ok).toString().toByteArray())
        if (!ok) { js(act, "window.batHive && batHive({type:'hserr'})"); hive?.send(org.json.JSONObject().put("t", "hsres").put("ok", false)); return }
        hsMine = diff
        hive?.send(org.json.JSONObject().put("t", "hsres").put("ok", true).put("diff", diff))
        hsFinish(act)
    }

    private fun medianOf(e: DoubleArray): Double { val s = e.sorted(); return s[s.size / 2] + 1e-12 }

    @Synchronized private fun hsFinish(act: Activity) {
        val a = hsMine ?: return; val b = hsPeer ?: return
        val d = Dsp.C / 2 * (a + b) / Sonar.RATE + 0.02
        hsMine = null; hsPeer = null
        val me = hive?.myTag() ?: "A"
        Results.save(act, "app_handshake_${Sonar.phoneName}_result_${Field.day()}.json", "application/json",
            Json().put("me", me).put("other", hsPeerTag).put("distance_m", d).put("mine", a).put("peer", b).toString().toByteArray())
        js(act, "window.batHive && batHive({type:'hs',d:$d,me:'$me',other:'$hsPeerTag'})")
    }

    private fun js(act: Activity, code: String) = act.runOnUiThread { web?.evaluateJavascript(code, null) }

    /** Called from MainActivity.onPause / onResume: nothing keeps pinging or holds the camera in the background. */
    fun pause() { stopAll(); stopHive(); web?.evaluateJavascript("window.batPause && batPause()", null) }
    fun resume() { web?.evaluateJavascript("window.batResume && batResume()", null) }

    fun stopHive() { hive?.stop(); hive = null }

    fun stopAll() {
        watch?.let { Thread { it.stop() }.start() }; watch = null
        live?.let { Thread { it.stop() }.start() }; live = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(act: MainActivity, openLab: () -> Unit) {
        val w = WebView(act)
        web?.destroy()
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

            // ---- Hive: join (nearby link) + sound handshake
            @JavascriptInterface fun hiveJoin() = act.runOnUiThread {
                act.withNearby {
                    if (hive == null) {
                        val h = Hive(act) { kind, j ->
                            when (kind) {
                                "peers", "error" -> js(act, "window.batHive && batHive(" + j.put("type", kind).toString() + ")")
                                "msg" -> when (j.optString("t")) {
                                    "hs" -> { hsPeerTag = j.optString("from"); val me = hive?.myTag() ?: "B"; Thread { Thread.sleep(j.optLong("in", 600)); handshake(act, me) }.start()
                                              js(act, "window.batHive && batHive({type:'hsgo'})") }
                                    "w", "watch", "test" -> js(act, "window.batHive && batHive(" + j.put("type", "msg").toString() + ")")
                                    "hsres" -> { if (j.optBoolean("ok")) { hsPeer = j.optDouble("diff"); hsPeerTag = j.optString("from"); hsFinish(act) } else js(act, "window.batHive && batHive({type:'hserr'})") }
                                }
                            }
                        }
                        hive = h; h.start()
                    }
                    js(act, "window.batHive && batHive(" + hive!!.status().put("type", "peers").toString() + ")")
                }
            }
            @JavascriptInterface fun hiveHandshake() {
                val h = hive ?: return
                stopAll(); hsMine = null; hsPeer = null
                h.send(org.json.JSONObject().put("t", "hs").put("in", 700))
                val me = h.myTag()
                Thread { Thread.sleep(700); handshake(act, me) }.start()
            }

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
                val h = hive
                val inHive = h != null && h.peerCount() > 0
                val me = h?.myTag() ?: "A"
                val e = WatchEngine(act, "app_watch_${Sonar.phoneName}_${Field.day()}", yieldTurn = inHive && me != "A") { ev ->
                    val j = Json().put("state", ev.state).put("d", ev.whereM).put("t", ev.t).put("text", ev.text).toString()
                    js(act, "window.batWatch && batWatch($j)")
                    // hive: share every non-quiet reading, and 'still' about once a second (the other phone draws the merged map)
                    if (inHive && (ev.state != "still" || (ev.t * 10).toInt() % 10 < 6)) h?.send(org.json.JSONObject(j).put("t", "w").put("tag", me))
                }
                watch = e; e.start()
            }
            /** Hive: tell the other phone(s) something (start/stop Watch together, etc.). */
            @JavascriptInterface fun hiveSend(json: String) { try { hive?.send(org.json.JSONObject(json)) } catch (_: Throwable) {} }
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
