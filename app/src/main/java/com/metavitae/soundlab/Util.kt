package com.metavitae.soundlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
}

fun LinearLayout.text(s: String, size: Float = 16f, bold: Boolean = false, color: Int = Color.WHITE): TextView {
    val t = TextView(context)
    t.text = s
    t.textSize = size
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
    t.setPadding(0, context.dp(4), 0, context.dp(4))
    addView(t)
    return t
}

fun LinearLayout.button(s: String, onClick: () -> Unit): Button {
    val b = Button(context)
    b.text = s
    b.isAllCaps = false
    b.textSize = 17f
    b.setOnClickListener { onClick() }
    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(6)
    })
    return b
}

fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

fun f2(d: Double) = String.format(Locale.US, "%.2f", d)

fun dbfs(rms: Double) = if (rms <= 0.0) -999.0 else 20 * log10(rms)

fun rms(x: ShortArray, ch: Int, which: Int): Double {
    var s = 0.0
    var n = 0
    var i = which
    while (i < x.size) { val v = x[i] / 32768.0; s += v * v; n++; i += ch }
    return if (n == 0) 0.0 else sqrt(s / n)
}

object Results {
    const val DIR = "SoundLab"

    /** Saves to Download/SoundLab/ so it shows up in Files by Google and CC can adb-pull it. Returns the path. */
    fun save(ctx: Context, name: String, mime: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            "Download/$DIR/$name"
        } catch (e: Exception) {
            "NOT SAVED: ${e.message}"
        }
    }

    fun saveText(ctx: Context, name: String, body: String) = save(ctx, name, "text/plain", body.toByteArray())

    fun header(title: String): String {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Sound Lab - $title\nPhone: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\nTime: $t\n\n"
    }
}

/** Tiny JSON writer: enough for flat-ish metadata without a library. */
class Json {
    private val sb = StringBuilder("{")
    private var first = true
    private fun key(k: String) { if (!first) sb.append(','); first = false; sb.append('"').append(k).append("\":") }
    fun put(k: String, v: String?): Json { key(k); if (v == null) sb.append("null") else sb.append(quote(v)); return this }
    fun put(k: String, v: Number?): Json { key(k); sb.append(v?.toString() ?: "null"); return this }
    fun put(k: String, v: Boolean?): Json { key(k); sb.append(v?.toString() ?: "null"); return this }
    fun raw(k: String, json: String): Json { key(k); sb.append(json); return this }
    override fun toString() = "$sb}"

    companion object {
        fun quote(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when (c) {
                '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '\n' -> b.append("\\n")
                '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
            return b.append('"').toString()
        }
        fun list(items: List<String>) = items.joinToString(",", "[", "]")
    }
}
