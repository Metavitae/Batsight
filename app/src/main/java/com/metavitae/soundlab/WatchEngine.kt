package com.metavitae.soundlab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Screen 4 engine: the founder's Warp rise every 300 ms (-20 dBFS), recorded on every mic, judged ping by
 * ping with the lab's watch rules (lab/echo.py watch: MOVING / CHANGED / ADOPTED / still, learned
 * background wobble, margin 4, 20 distances). On a phone with two real mics (8T) it also estimates the
 * direction of the change from the arrival-time difference between the mics (a TEST: the 8T's mic
 * spacing is taken as 15 cm and which side is "left" is unknown until CC checks tonight's files).
 * Saves the whole recording (app files/field/<id>.wav) and one line per ping (<id>.csv) for CC.
 */
class WatchEngine(private val ctx: Context, private val id: String, private val onEvent: (Event) -> Unit) {
    class Event(val t: Double, val state: String, val whereM: Double?, val angleDeg: Double?, val text: String)

    private val rate = Sonar.RATE
    private val cycle = rate * 300 / 1000
    private val warp = Sonar.warp(ctx)
    private val nBins = Dsp.bins(4.0, rate)
    private val spacingM = 0.10   // MEASURED 2026-10-15 from the 8T's right-speaker chirps: mic 1 hears it 14 samples (~10 cm) after mic 0
    @Volatile private var running = false
    private var adoptNow = false
    private val threads = ArrayList<Thread>()
    private val dir = ctx.getExternalFilesDir("field")!!.also { it.mkdirs() }
    private val wavFile = File(dir, "$id.wav")
    private val csv = StringBuilder("t_s,state,where_m,n,angle_deg,delay_samples,channels,speaker,lvl_mic0_db,lvl_mic1_db\n")
    // Speakers take turns (founder 2026-10-15: "or alternate"): playing both at once mixed two sources and scrambled
    // the mic-to-mic timing. The speaker the main mic hears loudest drives the watch; the other is logged for CC.
    private var strongParity = -1
    private val firstLevels = DoubleArray(2)
    private var curSpk = 0; private var curLv = DoubleArray(2)

    // rolling buffer of the last few seconds (interleaved), with the absolute frame number of its start
    private val keepFrames = rate * 4
    private var ch = 1
    private var buf = ShortArray(0)
    private var bufStart = 0L
    private var bufLen = 0
    private val lock = Object()

    private var volBefore = -1

    fun adopt() { synchronized(this) { adoptNow = true } }

    fun start() {
        running = true
        val am = ctx.getSystemService(AudioManager::class.java)
        volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6).toInt(), 0)

        val rec = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(rate * 2 * 2).build()
        ch = rec.channelCount
        buf = ShortArray(keepFrames * ch)
        listOf(NoiseSuppressor.isAvailable() to { NoiseSuppressor.create(rec.audioSessionId) as android.media.audiofx.AudioEffect? },
            AcousticEchoCanceler.isAvailable() to { AcousticEchoCanceler.create(rec.audioSessionId) },
            AutomaticGainControl.isAvailable() to { AutomaticGainControl.create(rec.audioSessionId) }).forEach { (ok, make) ->
            if (ok) try { make()?.enabled = false } catch (_: Throwable) {}
        }
        val one = ShortArray(cycle * 2 * 2)            // two cycles: left speaker, then right speaker
        for (k in warp.indices) { val v = (warp[k] * 32767).toInt().toShort(); one[2 * k] = v; one[2 * (cycle + k) + 1] = v }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(cycle * 2 * 2 * 2).build()

        val out = RandomAccessFile(wavFile, "rw"); out.setLength(0); out.write(ByteArray(44))
        rec.startRecording()
        val t0 = System.currentTimeMillis()
        threads += Thread {
            val b = ShortArray(rate / 50 * ch)
            val bytes = ByteArray(b.size * 2)
            while (running) {
                val n = rec.read(b, 0, b.size)
                if (n <= 0) continue
                for (i in 0 until n) { bytes[2 * i] = (b[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (b[i].toInt() shr 8).toByte() }
                out.write(bytes, 0, n * 2)
                synchronized(lock) {
                    val frames = n / ch
                    if (bufLen + frames > keepFrames) {
                        val drop = bufLen + frames - keepFrames
                        System.arraycopy(buf, drop * ch, buf, 0, (bufLen - drop) * ch); bufLen -= drop; bufStart += drop
                    }
                    System.arraycopy(b, 0, buf, bufLen * ch, n); bufLen += frames
                }
                if (System.currentTimeMillis() - t0 > 30 * 60_000) running = false   // 30 minutes at most
            }
            rec.stop(); rec.release()
            val len = out.length() - 44
            out.seek(0); out.write(Wav.bytes(ShortArray(0), rate, ch).copyOf(44).also { h -> fun put(at: Int, v: Long) { for (k in 0 until 4) h[at + k] = (v shr (8 * k)).toByte() }; put(4, 36 + len); put(40, len) })
            out.close()
        }
        threads += Thread {
            track.play()
            while (running) track.write(one, 0, one.size)
            track.stop(); track.release()
        }
        threads += Thread { process() }
        threads.forEach { it.start() }
        onEvent(Event(0.0, "learning", null, null, "Learning the room... keep still for 10 seconds."))
    }

    fun stop() {
        running = false
        threads.forEach { it.join(3000) }
        if (volBefore >= 0) ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0)
        File(dir, "$id.csv").writeText(csv.toString())
        Results.save(ctx, "$id.csv", "text/csv", csv.toString().toByteArray())
        Results.save(ctx, "${id}_info.json", "application/json", Json().put("id", id).put("phone", Sonar.phoneName).put("channels", ch)
            .put("every_ms", 300).put("speakers", "alternate L,R; watch uses the one mic 0 hears loudest").put("spacing_m_assumed", spacingM).put("wav", wavFile.absolutePath).toString().toByteArray())
    }

    private fun window(startAbs: Long, frames: Int): Array<DoubleArray>? = synchronized(lock) {
        val off = (startAbs - bufStart).toInt()
        if (off < 0 || off + frames > bufLen) return null
        Array(ch) { c -> DoubleArray(frames) { buf[(off + it) * ch + c] / 32768.0 } }
    }

    private fun available(): Long = synchronized(lock) { bufStart + bufLen }

    // ---------------------------------------------------------------- per-ping profiles + the lab's watch rules

    private fun process() {
        // 1. find the first ping's direct sound in the first 1.5 s
        while (running && available() < rate * 3 / 2) Thread.sleep(50)
        if (!running) return
        val firstAt = available() - rate * 3 / 2
        val first = window(firstAt, rate * 3 / 2) ?: return
        val (r0, i0) = Dsp.matched(first[0], warp)
        val a0 = Dsp.firstArrival(Dsp.env(r0, i0), 0, cycle * 3) ?: return
        var next = firstAt + a0
        val pre = 300; val span = warp.size + nBins + 2 * pre
        val profsR = ArrayList<Array<DoubleArray>>(); val profsI = ArrayList<Array<DoubleArray>>(); val arrivals = ArrayList<DoubleArray>()
        val r = DoubleArray(nBins) { Dsp.rangeM(it, rate) }
        val lo = r.indexOfFirst { it >= 0.3 }; val hi = r.indexOfLast { it <= 4.0 }
        val m = hi - lo + 1
        val fastN = 4; val stillN = 16; val z = 4.0; val minBins = 20; val margin = 4.0
        val every = 0.6; val alpha = every / 15.0; val settleN = 5; val learnN = 50   // one speaker's pings: every 600 ms
        var slowR = DoubleArray(0); var slowI = DoubleArray(0); var unit = DoubleArray(0)
        val histMv = ArrayDeque<DoubleArray>(); val histCh = ArrayDeque<DoubleArray>()
        var steady = 0
        var i = 0
        var pingNo = 0
        while (running) {
            while (running && available() < next - pre + span) Thread.sleep(20)
            if (!running) break
            val w = window(next - pre, span) ?: run { next += cycle; null } ?: continue
            val pr = Array(ch) { DoubleArray(nBins) }; val pi = Array(ch) { DoubleArray(nBins) }; val arr = DoubleArray(ch)
            val base = next - pre
            val lv = DoubleArray(2)
            val strong = strongParity < 0 || pingNo % 2 == strongParity
            for (c in 0 until ch) {
                val (mr, mi) = Dsp.matched(w[c], warp)
                val e = Dsp.env(mr, mi)
                var k = pre - 48
                for (q in pre - 48..pre + 48) if (e[q] > e[k]) k = q
                val dr = mr[k]; val di = mi[k]; val dm = hypot(dr, di) + 1e-20
                val cr = dr / dm; val ci = -di / dm
                for (b in 0 until nBins) { pr[c][b] = mr[k + b] * cr - mi[k + b] * ci; pi[c][b] = mr[k + b] * ci + mi[k + b] * cr }
                arr[c] = base + Dsp.interpPeak(e, k)
                if (c < 2) lv[c] = 20 * kotlin.math.log10(dm)
                if (c == 0 && strong) next = base + k              // follow the clocks' slow drift on the loud speaker only
            }
            next += cycle
            val parity = pingNo % 2; pingNo++
            if (strongParity < 0) {                                  // the first two pings decide which speaker mic 0 hears best
                firstLevels[parity] = lv[0]
                if (pingNo == 2) strongParity = if (firstLevels[0] >= firstLevels[1]) 0 else 1
                continue
            }
            if (parity != strongParity) { log(pingNo * 0.3, "other_speaker", null, 0, null, null, parity, lv); continue }
            curSpk = parity; curLv = lv
            profsR.add(pr); profsI.add(pi); arrivals.add(arr)
            if (profsR.size > 2 * fastN + 1) { profsR.removeAt(0); profsI.removeAt(0); arrivals.removeAt(0) }
            val t = i * every
            i++

            // learning: the first stillN pings make "the room as usual" and its ping-to-ping noise
            if (i <= stillN) {
                if (slowR.isEmpty()) { slowR = DoubleArray(nBins); slowI = DoubleArray(nBins); unit = DoubleArray(nBins) }
                for (b in 0 until nBins) { slowR[b] += pr[0][b] / stillN; slowI[b] += pi[0][b] / stillN }
                learnBuf.add(Pair(pr[0].copyOf(), pi[0].copyOf()))
                if (i == stillN) {
                    for (b in 0 until nBins) {
                        var s = 0.0
                        for ((a, bb) in learnBuf) { val d1 = a[b] - slowR[b]; val d2 = bb[b] - slowI[b]; s += d1 * d1 + d2 * d2 }
                        unit[b] = sqrt(s / stillN) + 1e-20
                    }
                    learnBuf.clear()
                }
                log(t, "learning", null, 0, null, null, curSpk, curLv)
                continue
            }
            if (profsR.size < 2 * fastN) continue
            val n = profsR.size
            fun avg(from: Int, to: Int, c: Int): Pair<DoubleArray, DoubleArray> {
                val ar = DoubleArray(nBins); val ai = DoubleArray(nBins)
                for (j in from until to) for (b in 0 until nBins) { ar[b] += profsR[j][c][b]; ai[b] += profsI[j][c][b] }
                val k = (to - from).toDouble(); for (b in 0 until nBins) { ar[b] /= k; ai[b] /= k }
                return Pair(ar, ai)
            }
            val (fr, fi) = avg(n - fastN, n, 0); val (qr, qi) = avg(n - 2 * fastN, n - fastN, 0)
            val rawMv = DoubleArray(m) { hypot(fr[lo + it] - qr[lo + it], fi[lo + it] - qi[lo + it]) }
            val rawCh = DoubleArray(m) { hypot(fr[lo + it] - slowR[lo + it], fi[lo + it] - slowI[lo + it]) }
            histMv.addLast(rawMv); histCh.addLast(rawCh)
            while (histMv.size > learnN) { histMv.removeFirst(); histCh.removeFirst() }
            val bgMv = DoubleArray(m); val bgCh = DoubleArray(m)
            if (histMv.size >= 10) {
                val col = DoubleArray(histMv.size)
                for (b in 0 until m) {
                    var j = 0; for (h in histMv) col[j++] = h[b]; col.sort(); bgMv[b] = col[col.size / 2]
                    j = 0; for (h in histCh) col[j++] = h[b]; col.sort(); bgCh[b] = col[col.size / 2]
                }
            }
            var nMv = 0; var nCh = 0; var bMv = 0; var bCh = 0; var vMv = 0.0; var vCh = 0.0
            for (b in 0 until m) {
                val u = unit[lo + b] / sqrt(fastN.toDouble())
                val mv = rawMv[b] / max(u * sqrt(2.0), margin * bgMv[b] / z)
                val chg = rawCh[b] / max(u * sqrt(1 + fastN.toDouble() / stillN), margin * bgCh[b] / z)
                if (mv > z) nMv++; if (chg > z) nCh++
                if (mv > vMv) { vMv = mv; bMv = b }; if (chg > vCh) { vCh = chg; bCh = b }
            }
            var state: String; var where: Double? = null; var nn = 0; var bin = -1
            synchronized(this) {
                if (adoptNow) { adoptNow = false; slowR = fr.copyOf(); slowI = fi.copyOf(); steady = 0 }
            }
            if (nMv >= minBins) { state = "MOVING"; bin = lo + bMv; where = r[bin]; nn = nMv; steady = 0 }
            else if (nCh >= minBins) {
                steady++; state = "CHANGED"; bin = lo + bCh; where = r[bin]; nn = nCh
                if (steady >= settleN * 3) { slowR = fr.copyOf(); slowI = fi.copyOf(); state = "ADOPTED"; steady = 0 }   // never answered: becomes the usual after ~9 s
            } else {
                state = "still"; steady = 0
                for (b in 0 until nBins) { slowR[b] = (1 - alpha) * slowR[b] + alpha * pr[0][b]; slowI[b] = (1 - alpha) * slowI[b] + alpha * pi[0][b] }
            }
            // direction (two real mics only): arrival-time difference of the CHANGE between the mics, by
            // cross-correlating the change around its distance, lag limited to the mic spacing (+/-14 samples, 8T)
            var angle: Double? = null; var delay: Double? = null
            if (bin >= 0 && ch == 2) {
                val dif = Array(2) { c ->
                    val (ar, ai) = avg(n - fastN, n, c)
                    val (br, bi) = if (state == "MOVING") avg(n - 2 * fastN, n - fastN, c) else Pair(slowR, slowI)
                    DoubleArray(nBins) { hypot(ar[it] - br[it], ai[it] - bi[it]) }
                }
                val maxLag = kotlin.math.ceil(spacingM / Dsp.C * rate).toInt() + 2
                val lo = max(maxLag, bin - 30); val hi = min(nBins - maxLag - 1, bin + 30)
                var best = 0.0; var bestL = 0
                val cc = DoubleArray(2 * maxLag + 1)
                for (L in -maxLag..maxLag) { var sum = 0.0; for (q in lo..hi) sum += dif[0][q] * dif[1][q + L]; cc[L + maxLag] = sum; if (sum > best) { best = sum; bestL = L } }
                if (best > 0) {
                    val j = bestL + maxLag
                    val frac = if (j in 1 until cc.size - 1) { val d = cc[j - 1] - 2 * cc[j] + cc[j + 1]; if (d != 0.0) 0.5 * (cc[j - 1] - cc[j + 1]) / d else 0.0 } else 0.0
                    val last = arrivals.last()
                    delay = -(bestL + frac) + (last[0] - last[1])      // + the direct-sound offset between the mics (each profile starts at its own mic's direct sound)
                    angle = Math.toDegrees(asin((delay * Dsp.C / rate / spacingM).coerceIn(-1.0, 1.0)))
                }
            }
            log(t, state, where, nn, angle, delay, curSpk, curLv)
            val side = when { angle == null -> ""; angle < -20 -> ", to the left"; angle > 20 -> ", to the right"; else -> ", straight ahead" }
            val text = when (state) {
                "MOVING" -> "Something moved, about ${f2(where!!)} m$side" + if (angle != null) " (left/right is a test)" else ""
                "CHANGED" -> "Something changed at ${f2(where!!)} m$side. Keep as normal?"
                "ADOPTED" -> "The change stayed, so it's now the usual room."
                else -> if (Sonar.is8T || ch == 2) "Still" else "Still. (This phone can't tell which way.)"
            }
            onEvent(Event(t, state, where, angle, text))
        }
    }

    private val learnBuf = ArrayList<Pair<DoubleArray, DoubleArray>>()

    private fun log(t: Double, state: String, where: Double?, n: Int, angle: Double?, delay: Double?, spk: Int, lv: DoubleArray) {
        csv.append(String.format(java.util.Locale.US, "%.2f,%s,%s,%d,%s,%s,%d,%s,%.1f,%.1f\n", t, state, where?.let { f2(it) } ?: "", n,
            angle?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "", delay?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "", ch,
            if (spk == 0) "L" else "R", lv[0], lv[1]))
    }
}
