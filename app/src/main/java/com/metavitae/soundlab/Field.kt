package com.metavitae.soundlab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Colours from Chat's screen drawings (:root of the drawing sources, 2026-10-15). */
object Ui {
    val BG = Color.parseColor("#0B1526"); val INK = Color.parseColor("#E6EEFF"); val MUTED = Color.parseColor("#9AAED0")
    val ACCENT = Color.parseColor("#4D8DFF"); val OK = Color.parseColor("#3DDC97"); val WARN = Color.parseColor("#F2B84B")
    val UNKNOWN = Color.parseColor("#7F8DA8"); val MIC = Color.parseColor("#FF5A5A")
}

/** Field test: tonight's at-work build (founder 2026-10-15). Every try is saved in Download/SoundLab for CC. */
object Field {
    fun day(): String = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())

    fun Context.tv(s: String, size: Float = 16f, color: Int = Ui.INK, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    fun Context.btn(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 16f; setTextColor(Ui.INK)
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.parseColor("#3b5380")); setColor(Color.parseColor("#13213A")) }
        setOnClickListener { onClick() }
    }

    /** Sensors and audio parts once per phone: Chat's one-mic note asks what each phone has (method A, B). */
    fun saveFacts(ctx: Context) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensors = sm.getSensorList(Sensor.TYPE_ALL).map {
            Json().put("name", it.name).put("type", it.stringType).put("max_per_s", if (it.minDelay > 0) 1_000_000.0 / it.minDelay else 0.0).toString()
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        fun devs(flag: Int) = am.getDevices(flag).map {
            Json().put("type", PlayRecord.typeName(it.type)).put("id", it.id).put("address", it.address)
                .put("channels", it.channelCounts.joinToString(";")).put("product", it.productName.toString()).toString()
        }
        Results.save(ctx, "field_facts_${Sonar.phoneName}.json", "application/json", Json().put("model", Build.MODEL)
            .raw("sensors", Json.list(sensors)).raw("inputs", Json.list(devs(AudioManager.GET_DEVICES_INPUTS)))
            .raw("outputs", Json.list(devs(AudioManager.GET_DEVICES_OUTPUTS))).toString().toByteArray())
    }

    // ------------------------------------------------------------------ menu

    fun menu(act: Activity, back: () -> Unit) {
        Thread { try { saveFacts(act) } catch (_: Throwable) {} }.start()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(28), act.dp(20), act.dp(28)) }
        col.addView(act.tv("Batsight field test", 26f, bold = true))
        col.addView(act.tv("Tonight's test at work. Every try is saved on the phone; CC measures them tomorrow. Sounds are quiet (-20 dB) and short.", 14f, Ui.MUTED))
        fun item(title: String, sub: String, go: () -> Unit) {
            col.addView(act.btn(title, go).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(16) } })
            col.addView(act.tv(sub, 13f, Ui.MUTED))
        }
        item("Focus in the dark (Screen 2)", "Aim at one thing, tap Listen. Then tell it if it picked the right thing.") { focus(act) { menu(act, back) } }
        item("Watch (Screen 4)", "Lay the phone still. It shows where something moved.") { watch(act) { menu(act, back) } }
        item("Record kit", "Tagged tries: glass, mirror, wall, open space, person; one speaker at a time.") { kit(act) { menu(act, back) } }
        col.addView(act.btn("‹ Back", back).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(28) } })
        act.setContentView(ScrollView(act).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    // ------------------------------------------------------------------ Screen 2: Focus in the dark

    class FrameView(ctx: Context) : View(ctx) {
        var mode = "idle"   // idle | listen | echo | none | two | close
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h) * 0.55f
            val r = RectF((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
            p.strokeWidth = context.dp(3).toFloat()
            p.pathEffect = if (mode == "none" || mode == "close" || mode == "two") DashPathEffect(floatArrayOf(24f, 16f), 0f) else null
            p.color = when (mode) { "echo" -> Ui.OK; "none", "close" -> Ui.UNKNOWN; "two" -> Ui.WARN; "listen" -> Ui.ACCENT; else -> Ui.INK }
            c.drawRoundRect(r, 28f, 28f, p)
            if (mode == "none" || mode == "close") {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.UNKNOWN; textSize = s * 0.35f; textAlign = Paint.Align.CENTER }
                c.drawText("?", w / 2, h / 2 + s * 0.12f, t)
            }
        }
    }

    /** Camera with Camera2: preview, plus test C (can the app set focus from a distance?). */
    class Cam(private val act: Activity, private val tv: TextureView) {
        private val th = HandlerThread("cam").apply { start() }
        private val h = Handler(th.looper)
        private var dev: CameraDevice? = null
        private var ses: CameraCaptureSession? = null
        private var b: CaptureRequest.Builder? = null
        var chars: CameraCharacteristics? = null
        @Volatile var lensDiopters: Float? = null
        @Volatile var lensState: Int? = null

        @SuppressLint("MissingPermission")
        fun open() {
            val cm = act.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: return
            chars = cm.getCameraCharacteristics(id)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { dev = d; start() }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { d.close() }
            }, h)
        }

        @Suppress("DEPRECATION")
        private fun start() {
            val st = tv.surfaceTexture ?: return
            st.setDefaultBufferSize(1440, 1080)
            val s = Surface(st)
            val d = dev ?: return
            b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(s); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            d.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(x: CameraCaptureSession) { ses = x; repeat() }
                override fun onConfigureFailed(x: CameraCaptureSession) {}
            }, h)
        }

        private fun repeat() {
            val bb = b ?: return
            try {
                ses?.setRepeatingRequest(bb.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        lensDiopters = res.get(CaptureResult.LENS_FOCUS_DISTANCE); lensState = res.get(CaptureResult.LENS_STATE)
                    }
                }, h)
            } catch (_: Throwable) {}
        }

        fun focusAt(m: Double) { b?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); b?.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1.0 / m).toFloat()); repeat() }
        fun autoFocus() { b?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); repeat() }
        fun close() { try { ses?.close(); dev?.close() } catch (_: Throwable) {}; th.quitSafely() }
    }

    fun focus(act: Activity, back: () -> Unit) {
        val root = FrameLayout(act).apply { setBackgroundColor(Ui.BG) }
        val texture = TextureView(act)
        val w = act.resources.displayMetrics.widthPixels
        root.addView(texture, FrameLayout.LayoutParams(w, w * 4 / 3, Gravity.TOP))
        val frame = FrameView(act)
        root.addView(frame, FrameLayout.LayoutParams(w, w * 4 / 3, Gravity.TOP))
        val mic = act.tv("● Mic on", 15f, Ui.MIC, bold = true).apply { visibility = View.INVISIBLE }
        root.addView(mic, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(12), act.dp(12), 0) })
        val bigNum = act.tv("", 44f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }
        bigNum.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM; bigNum.setPadding(0, 0, 0, act.dp(36))
        root.addView(bigNum, FrameLayout.LayoutParams(w, w * 4 / 3, Gravity.TOP))

        val panel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(12)); setBackgroundColor(Ui.BG) }
        val status = act.tv("Aim at one thing and tap Listen.", 17f)
        val line = act.tv("", 13f, Ui.MUTED)
        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        panel.addView(status); panel.addView(line); panel.addView(row1); panel.addView(row2)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val cam = Cam(act, texture)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, a: Int, b: Int) { try { cam.open() } catch (_: Throwable) {} }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, a: Int, b: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        var busy = false
        var n = 0
        lateinit var showIdle: () -> Unit

        fun answer(id: String, what: Json) = Thread { Results.save(act, "${id}_answer.json", "application/json", what.toString().toByteArray()) }.start()

        fun feedback(id: String, r: Sonar.Result, focusJson: String) {
            row1.removeAllViews(); row2.removeAllViews()
            val tape = EditText(act).apply { hint = "tape m"; setHintTextColor(Ui.UNKNOWN); setTextColor(Ui.INK); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; width = act.dp(90) }
            fun send(right: String, picked: Double?) {
                answer(id, Json().put("right", right).put("picked_m", picked).put("tape_m", tape.text.toString().toDoubleOrNull()).raw("focus_test", focusJson))
                line.text = "Saved. Thanks."; row1.removeAllViews(); row2.removeAllViews()
                row1.addView(act.btn("Listen again") { showIdle(); }, LinearLayout.LayoutParams(0, -2, 1f))
                row1.addView(act.btn("‹ Menu") { cam.close(); back() }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            when (r.state) {
                "two" -> {
                    line.text = "Which one did you mean? (Tap it, or 'neither'.)"
                    row1.addView(act.btn(f2(r.best!!.m) + " m") { send("picked", r.best!!.m) }, LinearLayout.LayoutParams(0, -2, 1f))
                    row1.addView(act.btn(f2(r.second!!.m) + " m") { send("picked", r.second!!.m) }, LinearLayout.LayoutParams(0, -2, 1f))
                    row1.addView(act.btn("neither") { send("no", null) }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                "echo" -> {
                    line.text = "Is that the thing you aimed at?"
                    row1.addView(act.btn("Yes") { send("yes", r.best!!.m) }, LinearLayout.LayoutParams(0, -2, 1f))
                    row1.addView(act.btn("No") { send("no", r.best!!.m) }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                else -> {
                    row1.addView(act.btn("OK") { send("cant_tell", null) }, LinearLayout.LayoutParams(0, -2, 1f))
                    row1.addView(act.btn("It was glass/mirror") { send("glass", null) }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
            row2.addView(act.tv("Real distance (if you have a tape):", 13f, Ui.MUTED)); row2.addView(tape)
        }

        fun listen() {
            if (busy) return
            busy = true; n++
            val id = "field_focus_${Sonar.phoneName}_${day()}"
            frame.mode = "listen"; frame.invalidate(); mic.visibility = View.VISIBLE
            bigNum.text = ""; status.text = "Listening..."; line.text = ""; row1.removeAllViews(); row2.removeAllViews()
            cam.autoFocus()
            Thread {
                val r = try { Sonar.listen(act, id) } catch (t: Throwable) { null }
                // Test C: ask the lens to focus at the echo distance and read back what it reports.
                var focusJson = "null"
                if (r?.best != null && (r.state == "echo" || r.state == "two")) {
                    val ch = cam.chars
                    val minD = ch?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    val calib = ch?.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                    cam.focusAt(r.best.m)
                    Thread.sleep(900)
                    val got = cam.lensDiopters
                    focusJson = Json().put("asked_m", r.best.m).put("asked_diopters", 1.0 / r.best.m)
                        .put("min_focus_diopters", minD?.toDouble()).put("calibration", when (calib) { 0 -> "uncalibrated"; 1 -> "approximate"; 2 -> "calibrated"; else -> null })
                        .put("lens_reports_diopters", got?.toDouble()).put("lens_reports_m", if (got != null && got > 0) 1.0 / got else null)
                        .put("lens_state", cam.lensState).toString()
                }
                act.runOnUiThread {
                    mic.visibility = View.INVISIBLE; busy = false
                    if (r == null) { status.text = "Something went wrong. Try again."; frame.mode = "idle"; frame.invalidate(); return@runOnUiThread }
                    frame.mode = r.state; frame.invalidate()
                    when (r.state) {
                        "echo" -> { bigNum.text = f2(r.best!!.m) + " m"; status.text = "Focus locked"; status.setTextColor(Ui.OK) }
                        "two" -> { bigNum.text = f2(r.best!!.m) + " / " + f2(r.second!!.m); status.text = "Two things found"; status.setTextColor(Ui.WARN) }
                        "close" -> { bigNum.text = ""; status.text = "Too close? Step back a little."; status.setTextColor(Ui.UNKNOWN) }
                        else -> { bigNum.text = ""; status.text = "Can't tell"; status.setTextColor(Ui.UNKNOWN) }
                    }
                    if (r.state == "none" || r.state == "close") line.text = "Silence doesn't mean empty. You can focus by hand."
                    feedback(id, r, focusJson)
                }
            }.start()
        }

        showIdle = {
            frame.mode = "idle"; frame.invalidate(); bigNum.text = ""
            status.text = "Aim at one thing and tap Listen."; status.setTextColor(Ui.INK); line.text = ""
            row1.removeAllViews(); row2.removeAllViews(); cam.autoFocus()
            row1.addView(act.btn("Listen") { listen() }, LinearLayout.LayoutParams(0, -2, 2f))
            row1.addView(act.btn("‹ Menu") { cam.close(); back() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        showIdle()
        act.setContentView(root)
    }

    // ------------------------------------------------------------------ Record kit

    fun kit(act: Activity, back: () -> Unit) {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(18), act.dp(24), act.dp(18), act.dp(24)) }
        col.addView(act.tv("Record kit", 24f, bold = true))
        col.addView(act.tv("Point the phone, pick what it faces, tap Record. Fill the numbers only if you know them.", 13f, Ui.MUTED))
        var tag = "object"; var speaker = "both"
        val tagRow = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val tags = listOf("glass", "mirror", "wall", "open space", "person", "object", "curtain", "other")
        val tagBtns = ArrayList<Button>()
        fun paint() { tagBtns.forEach { it.setTextColor(if (it.text.toString() == tag) Ui.OK else Ui.INK) } }
        tags.chunked(4).forEach { ch ->
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            ch.forEach { t -> val b = act.btn(t) { tag = t; paint() }; tagBtns.add(b); r.addView(b, LinearLayout.LayoutParams(0, -2, 1f)) }
            tagRow.addView(r)
        }
        col.addView(tagRow); paint()
        col.addView(act.tv("Speaker", 14f, Ui.MUTED))
        val spRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val spBtns = ArrayList<Button>()
        fun paintSp() { spBtns.forEach { it.setTextColor(if (it.tag == speaker) Ui.OK else Ui.INK) } }
        listOf("both" to "Both", "L" to "Left only", "R" to "Right only").forEach { (k, label) ->
            val b = act.btn(label) { speaker = k; paintSp() }.apply { this.tag = k }; spBtns.add(b); spRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(spRow); paintSp()
        fun num(h: String) = EditText(act).apply { hint = h; setHintTextColor(Ui.UNKNOWN); setTextColor(Ui.INK); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val dist = num("distance in m (optional)"); val ang = num("angle in degrees, left = minus (optional)")
        val note = EditText(act).apply { hint = "note (optional)"; setHintTextColor(Ui.UNKNOWN); setTextColor(Ui.INK) }
        col.addView(dist); col.addView(ang); col.addView(note)
        val mic = act.tv("", 15f, Ui.MIC, bold = true)
        val out = act.tv("", 15f)
        var busy = false
        col.addView(act.btn("Record") {
            if (busy) return@btn
            busy = true; mic.text = "● Mic on"; out.text = "Listening..."
            val id = "field_kit_${Sonar.phoneName}_${tag.replace(' ', '-')}_${speaker}_${day()}"
            val d = dist.text.toString().toDoubleOrNull(); val a = ang.text.toString().toDoubleOrNull(); val nt = note.text.toString()
            Thread {
                val r = try { Sonar.listen(act, id, speaker) } catch (t: Throwable) { null }
                Results.save(act, "${id}_answer.json", "application/json", Json().put("tag", tag).put("speaker", speaker)
                    .put("distance_m", d).put("angle_deg", a).put("note", nt).toString().toByteArray())
                act.runOnUiThread {
                    busy = false; mic.text = ""
                    out.text = if (r == null) "Something went wrong." else {
                        val c = r.channels.flatMap { it.candidates }.sortedByDescending { it.strength }.take(4)
                        "Saved (${tag}, speaker ${speaker}). Echoes seen: " + (if (c.isEmpty()) "none" else c.joinToString(", ") { f2(it.m) + " m" + if (it.sure) "" else "?" })
                    }
                }
            }.start()
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(12) } })
        col.addView(mic); col.addView(out)
        col.addView(act.btn("‹ Menu", back).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(20) } })
        act.setContentView(ScrollView(act).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    // ------------------------------------------------------------------ Screen 4: Watch

    fun watch(act: Activity, back: () -> Unit) {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(16)) }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(act.tv("Watch", 24f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val mic = act.tv("", 15f, Ui.MIC, bold = true); top.addView(mic)
        root.addView(top)
        val map = MapView(act)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        val status = act.tv(if (Sonar.is8T) "Lay the phone flat with its LONG side facing the room (its two mics then point left and right). Keep still, tap Start." else "Lay the phone flat facing the room, keep still, tap Start. This phone has one mic: it can't tell which way.", 15f)
        root.addView(status)
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        var eng: WatchEngine? = null
        val keep = act.btn("Keep as normal") { eng?.adopt() }.apply { visibility = View.GONE }
        val start = act.btn("Start") {}
        start.setOnClickListener {
            if (eng == null) {
                val id = "field_watch_${Sonar.phoneName}_${day()}"
                eng = WatchEngine(act, id) { ev -> act.runOnUiThread {
                    map.add(ev); status.text = ev.text
                    keep.visibility = if (ev.state == "CHANGED") View.VISIBLE else View.GONE
                } }
                mic.text = "● Mic on"; start.text = "Stop"; map.directional = Sonar.is8T
                eng!!.start()
            } else {
                eng!!.stop(); eng = null; mic.text = ""; start.text = "Start"; status.text = "Stopped and saved."; keep.visibility = View.GONE
            }
        }
        row.addView(start, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(keep, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(act.btn("‹ Menu") { eng?.stop(); eng = null; back() }, LinearLayout.LayoutParams(0, -2, 1f))
        act.setContentView(root)
    }

    /** Top-down map from Chat's Watch drawing: phone at the bottom, rings at 1/2/3 m, amber marks. */
    class MapView(ctx: Context) : View(ctx) {
        var directional = false
        private val marks = ArrayList<WatchEngine.Event>()
        fun add(e: WatchEngine.Event) { if (e.whereM != null) { marks.add(e); if (marks.size > 10) marks.removeAt(0) }; if (e.state == "still") marks.clear(); invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val ox = w / 2; val oy = h - context.dp(18)
            val pxPerM = minOf(h - context.dp(30), w / 2) / 3.4f
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.parseColor("#22355A"); p.pathEffect = null
            p.textSize = context.dp(11).toFloat()
            for (m in 1..3) {
                c.drawArc(RectF(ox - m * pxPerM, oy - m * pxPerM, ox + m * pxPerM, oy + m * pxPerM), 180f, 180f, false, p)
                p.style = Paint.Style.FILL; p.color = Ui.UNKNOWN; c.drawText("$m m", ox + 6, oy - m * pxPerM + context.dp(12), p)
                p.style = Paint.Style.STROKE; p.color = Color.parseColor("#22355A")
            }
            p.style = Paint.Style.FILL; p.color = Ui.ACCENT
            c.drawRoundRect(RectF(ox - context.dp(22), oy - context.dp(6), ox + context.dp(22), oy + context.dp(6)), 8f, 8f, p)
            marks.forEachIndexed { i, e ->
                val age = (marks.size - 1 - i).toFloat() / 10f
                val alpha = ((1 - age) * 255).toInt().coerceIn(40, 255)
                val r = (e.whereM ?: return@forEachIndexed).toFloat() * pxPerM
                val solid = e.state == "CHANGED" || e.state == "ADOPTED"
                if (directional && e.angleDeg != null) {
                    val a = Math.toRadians(e.angleDeg)
                    val x = ox + (r * Math.sin(a)).toFloat(); val y = oy - (r * Math.cos(a)).toFloat()
                    p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(6).toFloat(); p.color = Ui.WARN; p.alpha = alpha / 3
                    val deg = Math.toDegrees(a).toFloat()
                    c.drawArc(RectF(ox - r, oy - r, ox + r, oy + r), 270f + deg - 24f, 48f, false, p)   // "somewhere in this arc": +/-24 = Chat's guess
                    p.style = Paint.Style.FILL; p.alpha = alpha; c.drawCircle(x, y, context.dp(if (solid) 9 else 6).toFloat(), p)
                } else {
                    p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(if (solid) 4 else 2).toFloat(); p.color = Ui.WARN; p.alpha = alpha
                    p.pathEffect = if (solid) null else DashPathEffect(floatArrayOf(14f, 10f), 0f)
                    c.drawArc(RectF(ox - r, oy - r, ox + r, oy + r), 180f, 180f, false, p); p.pathEffect = null
                }
            }
            p.alpha = 255
        }
    }
}

