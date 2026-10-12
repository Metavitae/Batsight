package com.metavitae.soundlab

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Stereo signal, values -1..1. */
class Stereo(val left: FloatArray, val right: FloatArray) {
    val frames get() = left.size
    fun swapped() = Stereo(right, left)
    fun interleaved16(): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = to16(left[i]); out[2 * i + 1] = to16(right[i])
        }
        return out
    }
    private fun to16(v: Float) = (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
}

/**
 * Signal recipes. The SAME text format is read by lab/signals.py on the computer, so a recipe means the
 * same sound on both sides. Parts are joined by "+" and mixed together; each part is "kind,key=value,...".
 *
 *   kind  tone | chirp | sweep | click | silence
 *   f     tone pitch (Hz)            f0,f1  start/end pitch for chirp (straight) and sweep (curved, log)
 *   ms    length of one sound        db     loudness, dB below full scale (default -30 = quiet)
 *   ch    L | R | LR (default LR)    at     start time in ms (default 0)
 *   count how many times             every  ms from one start to the next (default ms + 200)
 *   gaps  "150;230;170" uneven ms from start to start, cycled (off-beat); overrides every
 *   fade  ms of smooth fade in and out (default 10% of ms, at most 5 ms; clicks are always fully smoothed)
 *
 * Example: "chirp,f0=17000,f1=19000,ms=10,ch=L,count=5,every=300+chirp,f0=21000,f1=19000,ms=10,ch=R,at=150,count=5,every=300"
 */
object Signals {
    class Part(val kind: String, val v: Map<String, String>) {
        fun d(k: String, def: Double) = v[k]?.toDouble() ?: def
        fun i(k: String, def: Int) = v[k]?.toInt() ?: def
        fun s(k: String, def: String) = v[k] ?: def
    }

    fun parse(spec: String): List<Part> = spec.split("+").map { raw ->
        val bits = raw.trim().split(",")
        Part(bits[0].trim().lowercase(), bits.drop(1).associate {
            val kv = it.split("=", limit = 2); kv[0].trim().lowercase() to kv.getOrElse(1) { "" }.trim()
        })
    }

    /** One sound (no placement), mono. */
    fun one(p: Part, rate: Int): FloatArray {
        val ms = p.d("ms", if (p.kind == "click") 0.3 else 10.0)
        val n = (ms / 1000.0 * rate).roundToInt().coerceAtLeast(1)
        val a = 10.0.pow(p.d("db", -30.0) / 20.0)
        val out = FloatArray(n)
        val T = n.toDouble() / rate
        when (p.kind) {
            "tone" -> { val f = p.d("f", 1000.0); for (k in 0 until n) out[k] = (a * sin(2 * PI * f * k / rate)).toFloat() }
            "chirp" -> {
                val f0 = p.d("f0", 17000.0); val f1 = p.d("f1", 19000.0)
                for (k in 0 until n) { val t = k.toDouble() / rate; out[k] = (a * sin(2 * PI * (f0 * t + (f1 - f0) * t * t / (2 * T)))).toFloat() }
            }
            "sweep" -> {
                val f0 = p.d("f0", 15000.0); val f1 = p.d("f1", 24000.0)
                if (f0 == f1) for (k in 0 until n) out[k] = (a * sin(2 * PI * f0 * k / rate)).toFloat()
                else {
                    val L = T / ln(f1 / f0)
                    for (k in 0 until n) { val t = k.toDouble() / rate; out[k] = (a * sin(2 * PI * f0 * L * (exp(t / L) - 1))).toFloat() }
                }
            }
            "click" -> for (k in 0 until n) out[k] = (a * 0.5 * (1 - cos(2 * PI * (k + 0.5) / n))).toFloat()
            "silence" -> {}
            else -> error("unknown sound kind '${p.kind}'")
        }
        if (p.kind == "tone" || p.kind == "chirp" || p.kind == "sweep") {
            val fade = (p.d("fade", min(ms * 0.1, 5.0)) / 1000.0 * rate).roundToInt().coerceIn(0, n / 2)
            for (k in 0 until fade) {
                val w = (0.5 * (1 - cos(PI * (k + 0.5) / fade))).toFloat()
                out[k] *= w; out[n - 1 - k] *= w
            }
        }
        return out
    }

    /** Start frames of each repeat of a part. */
    fun starts(p: Part, rate: Int): List<Int> {
        val ms = p.d("ms", if (p.kind == "click") 0.3 else 10.0)
        val at = p.d("at", 0.0)
        val count = p.i("count", 1)
        val gaps = p.v["gaps"]?.split(";")?.filter { it.isNotBlank() }?.map { it.toDouble() }
        val every = p.d("every", ms + 200.0)
        val res = ArrayList<Int>()
        var t = at
        for (c in 0 until count) {
            res.add((t / 1000.0 * rate).roundToInt())
            t += if (gaps != null && gaps.isNotEmpty()) gaps[c % gaps.size] else every
        }
        return res
    }

    fun build(spec: String, rate: Int): Stereo {
        val parts = parse(spec)
        data class Placed(val start: Int, val data: FloatArray, val l: Boolean, val r: Boolean)
        val placed = ArrayList<Placed>()
        for (p in parts) {
            val snd = one(p, rate)
            val ch = p.s("ch", "LR").uppercase()
            for (s in starts(p, rate)) placed.add(Placed(s, snd, 'L' in ch, 'R' in ch))
        }
        val total = placed.maxOfOrNull { it.start + it.data.size } ?: 0
        val l = FloatArray(total); val r = FloatArray(total)
        for (p in placed) for (k in p.data.indices) {
            if (p.l) l[p.start + k] += p.data[k]
            if (p.r) r[p.start + k] += p.data[k]
        }
        return Stereo(l, r)
    }
}
