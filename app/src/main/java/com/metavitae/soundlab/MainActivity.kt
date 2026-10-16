package com.metavitae.soundlab

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var pendingMic: (() -> Unit)? = null
    @Volatile private var busy = false

    /** What the phone's Back button does on the current screen (null = leave the app, as on the menu).
     *  Founder 2026-10-15: after "Phone sound facts" on the 8T he could not get back anywhere. */
    var backHook: (() -> Unit)? = null

    override fun onPause() { super.onPause(); AppScreen.pause() }
    override fun onResume() { super.onResume(); AppScreen.resume() }

    @Deprecated("still the simplest way on SDK 34")
    override fun onBackPressed() {
        if (busy) return                     // a test is running: finish it first
        val h = backHook
        if (h != null) h() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // adb shortcuts for CC (CC grants the mic permission first with `pm grant`):
        //   --es mode facts --es id <name>
        //   --es mode play-record --es id <name> [--es spec "<recipe>" | --es file <wav in files/signals/>]
        //        [--es source unprocessed] [--ei ch 1] [--ei rate 48000] [--ei mic -1] [--es out speaker]
        //        [--ei vol -1] [--ei pre 500] [--ei post 500] [--ei delay 0] [--ei record 3000]
        //        [--ez swap false] [--ez motion false] [--ez fxoff true]
        //   --es mode live --es id <name> --es role lead|follow --es own <wav> --es partner <wav> --es ownlong <wav>
        //        [--ei cycle 480] [--ei cycles 30] [--ei half 240] [--ei vol -1] [--es thresh 8]
        //   --es mode intro --es id <name>   (plays the Kithe intro, saves frame rate / skip / sound facts)
        val i = intent
        when (i.getStringExtra("mode")) {
            "facts" -> withMic { showFacts(i.getStringExtra("id") ?: "facts_${stamp()}") }
            "play-record" -> withMic {
                showRun(PlayRecord.Params(
                    id = i.getStringExtra("id") ?: "run_${stamp()}",
                    spec = i.getStringExtra("spec"),
                    file = i.getStringExtra("file"),
                    source = i.getStringExtra("source") ?: "unprocessed",
                    channels = i.getIntExtra("ch", 1),
                    rate = i.getIntExtra("rate", 48000),
                    micId = i.getIntExtra("mic", -1),
                    out = i.getStringExtra("out") ?: "speaker",
                    vol = i.getIntExtra("vol", -1),
                    preMs = i.getIntExtra("pre", 500),
                    postMs = i.getIntExtra("post", 500),
                    delayMs = i.getIntExtra("delay", 0),
                    recordMs = i.getIntExtra("record", 3000),
                    swap = i.getBooleanExtra("swap", false),
                    motion = i.getBooleanExtra("motion", false),
                    effectsOff = i.getBooleanExtra("fxoff", true),
                    armed = i.getBooleanExtra("armed", false),
                ))
            }
            "live" -> withMic {
                showLive(LiveMode.Params(
                    id = i.getStringExtra("id") ?: "live_${stamp()}",
                    role = i.getStringExtra("role") ?: "lead",
                    own = i.getStringExtra("own") ?: "",
                    partner = i.getStringExtra("partner") ?: "",
                    ownLong = i.getStringExtra("ownlong") ?: "",
                    cycleMs = i.getIntExtra("cycle", 480),
                    cycles = i.getIntExtra("cycles", 30),
                    halfMs = i.getIntExtra("half", 240),
                    vol = i.getIntExtra("vol", -1),
                    thresh = (i.getStringExtra("thresh") ?: "7").toDouble(),
                ))
            }
            "field-replay" -> scrollPage { text(Sonar.replay(this@MainActivity, i.getStringExtra("id") ?: "replay", i.getStringExtra("file") ?: ""), 12f) }   // silent maths check
            "scan" -> withMic {   // CC runs the turn/walk test from the computer: --es id X --ei secs 30 [--ez dry true]
                val e = ScanEngine(this, i.getStringExtra("id") ?: "scan_${stamp()}", i.getBooleanExtra("dry", false), i.getIntExtra("secs", 30))
                busy = true
                scrollPage { text("Scan running (CC)", 24f, bold = true); text("● Mic on", 16f, bold = true, color = Color.RED); text("Turn or walk as CC says. Tap MARK at the thing / at each tape mark.", 15f)
                    button("MARK") { e.mark("ahead") } }
                e.start()
                Thread { while (e.running) Thread.sleep(200); e.stop(); busy = false; runOnUiThread { scrollPage { text("Scan saved.", 20f); button("‹ Back to menu") { showMenu() } } } }.start()
            }
            "app" -> showApp()   // CC: open Chat's screens directly (no intro sound)
            null -> if (b == null) Intro.show(this, null) { showApp() } else showApp()   // from the app icon: Kithe intro, then Chat's screens (tour on first open)
            "intro" -> Intro.show(this, i.getStringExtra("id") ?: "intro_${stamp()}") { showSafety() }   // test: writes <id>.json
            else -> showMenu()
        }
    }

    private fun scrollPage(build: LinearLayout.() -> Unit) {
        val col = column()
        col.build()
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
    }

    private fun withMic(f: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else { pendingMic = f; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
    }

    private fun withMicAndCamera(f: () -> Unit) {
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) f() else { pendingMic = f; requestPermissions(need.toTypedArray(), 1) }
    }

    /** Hive mode needs the nearby-devices permissions (Bluetooth / Wi-Fi peers; Android 13+ also "nearby Wi-Fi"). */
    fun withNearby(f: () -> Unit) {
        val want = ArrayList<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) want += listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT")
        if (android.os.Build.VERSION.SDK_INT >= 33) want += "android.permission.NEARBY_WIFI_DEVICES"
        want += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val need = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) f() else { pendingNearby = f; requestPermissions(need.toTypedArray(), 2) }
    }
    private var pendingNearby: (() -> Unit)? = null

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 2) { val f = pendingNearby; pendingNearby = null; f?.invoke(); return }   // hive: go on even if some were refused (the link reports what fails)
        val f = pendingMic
        pendingMic = null
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) f?.invoke() else showMenu()
    }

    // ---------------------------------------------------------------- safety

    private fun showSafety() {
        scrollPage {
            text("Sound Lab", 28f, bold = true)
            text("The Batsight test app", 15f, color = Color.LTGRAY)
            text("BEFORE TESTING", 20f, bold = true, color = Color.rgb(255, 200, 0))
            text("This app plays very high, quiet sounds. People often can't hear them, but dogs, cats and other animals can.")
            text("• No pets, small animals or sleeping people in the room or next door.")
            text("• Keep the phone away from your ears.")
            text("• If you hear a high whine, turn the volume down.")
            text("• Stop at once if an animal reacts.")
            text("• The mic is on only while a test runs. The red 'Mic is on' line shows when.")
            button("I understand. Continue") { showMenu() }
        }
    }

    // ------------------------------------------------------------------ menu

    /** The real app: Chat's screens in a WebView (AppScreen). "Lab" opens the old test menu; Back there returns here. */
    fun showApp() {
        withMicAndCamera { AppScreen.show(this) { showMenu(); backHook = { showApp() } } }
    }

    private fun showMenu() {
        backHook = null
        scrollPage {
            text("Sound Lab", 28f, bold = true)
            text("Results are saved in Download/SoundLab (open Files by Google). Most tests are started by CC from the computer.", 14f, color = Color.LTGRAY)
            button("FIELD TEST (tonight at work): Focus, Watch, Record kit") { withMicAndCamera { Field.menu(this@MainActivity) { showMenu() } } }
            button("1. Phone sound facts (automatic, about 15 seconds, silent)") { withMic { showFacts("facts_${stamp()}") } }
            button("2. One quiet test chirp (high pitch, 10 ms)") {
                withMic {
                    showRun(PlayRecord.Params("quick_${stamp()}", "chirp,f0=17000,f1=19000,ms=10,db=-30", null, "unprocessed",
                        1, 48000, -1, "speaker", -1, 300, 300, 0, 0, false, false, true))
                }
            }
        }
    }

    private fun showFacts(id: String) {
        if (busy) return
        busy = true
        var status: TextView? = null
        var out: TextView? = null
        var mic: TextView? = null
        var back: android.widget.Button? = null
        backHook = { showMenu() }
        scrollPage {
            back = button("‹ Back to menu") { if (!busy) showMenu() }.also { it.visibility = android.view.View.GONE }
            text("Phone sound facts", 24f, bold = true)
            mic = text("● Mic is on", 16f, bold = true, color = Color.RED)
            text("Lay the phone face up on a table and don't touch it. Nothing is played.", 15f)
            status = text("Starting...", 16f, color = Color.rgb(255, 200, 0))
            out = text("", 12f)
        }
        Thread {
            val report = try { Facts(this) { s -> runOnUiThread { status?.text = s } }.run() }
                         catch (e: Throwable) { "FAILED: ${e.javaClass.simpleName} ${e.message}" }
            val path = Results.saveText(this, "$id.txt", report)
            busy = false
            runOnUiThread { status?.text = "Done. Saved: $path"; out?.text = report; mic?.text = "Mic is off"; mic?.setTextColor(Color.LTGRAY); back?.visibility = android.view.View.VISIBLE }
        }.start()
    }

    private fun showLive(p: LiveMode.Params) {
        if (busy) return
        busy = true
        var status: TextView? = null
        var mic: TextView? = null
        var back: android.widget.Button? = null
        backHook = { showMenu() }
        scrollPage {
            back = button("‹ Back to menu") { if (!busy) showMenu() }.also { it.visibility = android.view.View.GONE }
            text("Live rhythm", 24f, bold = true)
            mic = text("● Mic is on", 16f, bold = true, color = Color.RED)
            text("Run: ${p.id} (${p.role})", 13f, color = Color.LTGRAY)
            status = text("Starting...", 16f, color = Color.rgb(255, 200, 0))
        }
        Thread {
            val res = try { LiveMode(this, p) { s -> runOnUiThread { status?.text = s } }.run() }
                      catch (e: Throwable) {
                          val msg = "FAILED: ${e.javaClass.simpleName} ${e.message}"
                          Results.save(this, "${p.id}.json", "application/json", Json().put("id", p.id).put("error", msg).toString().toByteArray())
                          msg
                      }
            busy = false
            runOnUiThread { mic?.text = "Mic is off"; mic?.setTextColor(Color.LTGRAY); status?.text = "Done.\n$res"; back?.visibility = android.view.View.VISIBLE }
        }.start()
    }

    private fun showRun(p: PlayRecord.Params) {
        if (busy) return
        busy = true
        var status: TextView? = null
        var mic: TextView? = null
        var back: android.widget.Button? = null
        backHook = { showMenu() }
        scrollPage {
            back = button("‹ Back to menu") { if (!busy) showMenu() }.also { it.visibility = android.view.View.GONE }
            text("Play and record", 24f, bold = true)
            mic = text("● Mic is on", 16f, bold = true, color = Color.RED)
            text("Run: ${p.id}", 13f, color = Color.LTGRAY)
            text("Sound: ${p.spec ?: p.file ?: "none (listen only)"}", 13f, color = Color.LTGRAY)
            status = text("Starting...", 16f, color = Color.rgb(255, 200, 0))
        }
        Thread {
            val job = PlayRecord(this, p) { s -> runOnUiThread { status?.text = s } }
            val res = try { job.run() }
                      catch (e: Throwable) {
                          job.restoreVolume()
                          val msg = "FAILED: ${e.javaClass.simpleName} ${e.message}"
                          Results.save(this, "${p.id}.json", "application/json", Json().put("id", p.id).put("error", msg).toString().toByteArray())
                          msg
                      }
            busy = false
            runOnUiThread { mic?.text = "Mic is off"; mic?.setTextColor(Color.LTGRAY); status?.text = "Done.\n$res"; back?.visibility = android.view.View.VISIBLE }
        }.start()
    }
}
