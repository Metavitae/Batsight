package com.metavitae.soundlab

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One "Listen" for the field-test screens: 6 rounds of [short 5 ms chirp, then the founder's Warp rise],
 * both at -20 dBFS (sonar sound rule), recorded on every mic the phone gives; then the lab's near/far idea
 * on the phone: the short sound judges near things (own fade gone by ~0.3 m), the Warp rise far things,
 * and between 0.6 and 2 m both must agree or it is only "probable". Everything is saved for CC.
 */
object Sonar {
    const val RATE = 48000
    const val ROUNDS = 6
    const val ROUND_MS = 300
    const val WARP_AT_MS = 120
    const val NEAR_MAX_M = 0.6
    const val FAR_MIN_M = 2.0
    const val MAX_M = 4.0

    val is8T get() = Build.MODEL.startsWith("KB2") || Build.MANUFACTURER.equals("OnePlus", true)
    val phoneName get() = if (is8T) "8T" else if (Build.MODEL.contains("2312FPCA6G")) "POCO" else Build.MODEL
    /** Wide pitch range per phone (lab WIDE / SHORT): the POCO is deaf above ~16 kHz. */
    val shortTop get() = if (is8T) 20000 else 15000
    /** Nearest distance each phone can be trusted at (lab, 2026-10-12: 8T found a book from ~25 cm,
     *  the POCO is blind closer than ~40-60 cm). First live try (8T flat on a table) showed "echoes" at
     *  0.15-0.25 m that are the phone ringing itself, so nothing closer than this counts. */
    val minM get() = if (is8T) 0.25 else 0.45

    fun warp(ctx: Context): DoubleArray {
        val b = ctx.assets.open("sonar/warp.wav").use { it.readBytes() }
        val n = (b.size - 44) / 2
        return DoubleArray(n) { val lo = b[44 + 2 * it].toInt() and 0xff; val hi = b[45 + 2 * it].toInt(); ((hi shl 8) or lo).toShort() / 32768.0 }
    }

    fun short(): DoubleArray = Signals.one(Signals.parse("chirp,f0=2000,f1=$shortTop,ms=5,db=-20")[0], RATE).map { it.toDouble() }.toDoubleArray()

    class Sequence(val sound: Stereo, val shortStarts: List<Int>, val warpStarts: List<Int>, val shortRef: DoubleArray, val warpRef: DoubleArray)

    /** speaker: "both" | "L" | "R" (Record kit: one speaker at a time, for Chat's one-mic direction ideas). */
    fun sequence(ctx: Context, speaker: String = "both"): Sequence {
        val w = warp(ctx); val s = short()
        val ss = (0 until ROUNDS).map { it * ROUND_MS * RATE / 1000 }
        val ws = ss.map { it + WARP_AT_MS * RATE / 1000 }
        val total = ws.last() + w.size
        val mono = FloatArray(total)
        for (a in ss) for (k in s.indices) mono[a + k] += s[k].toFloat()
        for (a in ws) for (k in w.indices) mono[a + k] += w[k].toFloat()
        val l = if (speaker == "R") FloatArray(total) else mono.copyOf()
        val r = if (speaker == "L") FloatArray(total) else mono.copyOf()
        return Sequence(Stereo(l, r), ss, ws, s, w)
    }

    class Candidate(val m: Double, val strength: Double, val sure: Boolean)
    class ChannelResult(val ch: Int, val candidates: List<Candidate>, val blindM: Double, val directDb: Double, val error: String?)
    class Result(
        val id: String, val state: String,            // "echo" | "two" | "none" | "close"
        val best: Candidate?, val second: Candidate?, val channels: List<ChannelResult>, val stereoReal: Boolean,
        val volume: Int, val volumeMax: Int, val saved: String,
    )

    /** Plays, records, analyses. Blocking: call from a worker thread. */
    fun listen(ctx: Context, id: String, speaker: String = "both", status: (String) -> Unit = {}): Result {
        val seq = sequence(ctx, speaker)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = (maxVol * 0.6).toInt()                 // the founder's 60% comfort line, the same for every try
        val job = PlayRecord(ctx, PlayRecord.Params(
            id = id, spec = null, file = null, source = "unprocessed", channels = 2, rate = RATE, micId = -1,
            out = "speaker", vol = vol, preMs = 250, postMs = 250, delayMs = 0, recordMs = 0,
            swap = false, motion = true, effectsOff = true, stereo = seq.sound,
        ), status)
        val saved = try { job.run() } catch (t: Throwable) { job.restoreVolume(); throw t }
        val x = job.lastSamples ?: error("no recording")
        val nch = job.lastChannels
        val chans = (0 until nch).map { c -> DoubleArray(x.size / nch) { x[it * nch + c] / 32768.0 } }
        val stereoReal = nch == 2 && !nearlySame(chans[0], chans[1])
        val use = if (stereoReal) chans.indices.toList() else listOf(0)
        val results = use.map { c -> analyse(chans[c], c, seq) }
        val ok = results.filter { it.error == null }
        val bestCh = ok.maxByOrNull { it.candidates.firstOrNull { c -> c.sure }?.strength ?: 0.0 }
        val sure = bestCh?.candidates?.filter { it.sure } ?: emptyList()
        val best = sure.getOrNull(0); val second = sure.getOrNull(1)
        val state = when {
            best == null -> if ((bestCh?.blindM ?: 0.0) > 0.3) "close" else "none"
            second != null && second.strength >= 0.8 * best.strength && abs(second.m - best.m) > 0.1 -> "two"
            else -> "echo"
        }
        val r = Result(id, state, best, second, results, stereoReal, vol, maxVol, saved)
        Results.save(ctx, "${id}_result.json", "application/json", toJson(r).toByteArray())
        return r
    }

    /** Silent check (adb mode field-replay): analyse a WAV that CC pushed into files/signals/ as if it
     *  were a Listen recording. Proves the phone's maths without playing anything. */
    fun replay(ctx: Context, id: String, file: String): String {
        val (st, _) = Wav.read(java.io.File(ctx.getExternalFilesDir("signals"), file))
        val seq = sequence(ctx)
        val chans = listOf(st.left, st.right).map { a -> DoubleArray(a.size) { a[it].toDouble() } }
        val res = chans.indices.map { analyse(chans[it], it, seq) }
        val r = Result(id, "replay", res.firstOrNull()?.candidates?.firstOrNull(), null, res, true, 0, 0, file)
        val js = toJson(r)
        Results.save(ctx, "${id}_result.json", "application/json", js.toByteArray())
        return js
    }

    private fun nearlySame(a: DoubleArray, b: DoubleArray): Boolean {
        var d = 0.0; var e = 0.0
        for (i in a.indices) { d += (a[i] - b[i]) * (a[i] - b[i]); e += a[i] * a[i] }
        return d < 1e-4 * e
    }

    fun analyse(x: DoubleArray, ch: Int, seq: Sequence): ChannelResult {
        return try {
            val nBins = Dsp.bins(5.0, RATE)
            val (wr, wi) = Dsp.matched(x, seq.warpRef)
            val we = Dsp.env(wr, wi)
            val first = Dsp.firstArrival(we, 0, we.size) ?: return ChannelResult(ch, emptyList(), 0.0, -999.0, "no ping heard")
            val offset = first - seq.warpStarts[0]
            val tol = RATE / 1000
            val wp = Dsp.profiles(wr, wi, seq.warpStarts, offset, nBins, tol)
            val (sr, si) = Dsp.matched(x, seq.shortRef)
            val sp = Dsp.profiles(sr, si, seq.shortStarts, offset, nBins, tol)
            if (wp.re.size < 3 || sp.re.size < 3) return ChannelResult(ch, emptyList(), 0.0, -999.0, "too few pings lined up")
            val (we2, wfl) = Dsp.floor(wp, seq.warpRef)
            val (se, sfl) = Dsp.floor(sp, seq.shortRef)
            val sNoise6 = Dsp.noiseOfMean(sp).map { 6 * it }.toDoubleArray()
            val blind = Dsp.blindEndM(se, sNoise6, RATE)
            val from = max(minM, blind)                       // inside the blind zone nothing can be judged
            val near = if (from < NEAR_MAX_M) Dsp.peaks(se, sfl, RATE, from, NEAR_MAX_M) else emptyList()
            val far = Dsp.peaks(we2, wfl, RATE, max(NEAR_MAX_M, from), MAX_M)
            val cands = ArrayList<Candidate>()
            near.forEach { cands.add(Candidate(it.m, it.strength, true)) }
            for (p in far) {
                val both = if (p.m >= FAR_MIN_M) true else {
                    var hit = false
                    for (k in max(0, p.bin - 3)..min(se.size - 1, p.bin + 3)) if (se[k] > sfl[k]) hit = true
                    hit
                }
                cands.add(Candidate(p.m, p.strength, both))      // between 0.6 and 2 m one view alone = probable only
            }
            cands.sortByDescending { it.strength }
            ChannelResult(ch, cands, blind, 20 * kotlin.math.log10(we2[0] + 1e-20), null)
        } catch (t: Throwable) {
            ChannelResult(ch, emptyList(), 0.0, -999.0, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun toJson(r: Result): String {
        fun c(x: Candidate?) = if (x == null) "null" else Json().put("m", x.m).put("strength", x.strength).put("sure", x.sure).toString()
        return Json().put("id", r.id).put("phone", phoneName).put("state", r.state)
            .raw("best", c(r.best)).raw("second", c(r.second)).put("stereo_real", r.stereoReal)
            .put("volume", r.volume).put("volume_max", r.volumeMax)
            .raw("channels", Json.list(r.channels.map { ch ->
                Json().put("ch", ch.ch).put("blind_m", ch.blindM).put("direct_db", ch.directDb).put("error", ch.error)
                    .raw("candidates", Json.list(ch.candidates.map { c(it) })).toString()
            })).toString()
    }
}
