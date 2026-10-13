package com.metavitae.soundlab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LIVE two-phone rhythm (founder's idea: the phones keep a steady "whompf... whompf" between them; a
 * stumble means a problem). Unlike play-record, nothing is pressed "play" with a lag: the speaker gets a
 * continuous stream, and each sound is mixed in at an exact frame of that stream.
 *
 *  lead   - plays its own beat sound every cycleMs on its own clock (sample-exact, so its rhythm is steady).
 *  follow - listens all the time; when it recognises the lead's sound (matched filter, on the phone),
 *           it answers halfMs later. Like a drummer it also listens to ITSELF: the gap between when it
 *           meant its sound to be heard and when its own mic really heard it is fed back into the next
 *           answer, so its own speaker/mic delays don't make the rhythm drift.
 *
 * Both record everything (mono, unprocessed mic) for the computer: <id>.wav, then <id>.json LAST
 * (the done marker) with the schedule, detections, self-timing errors and stream timestamps.
 * Volume: set for the run, the owner's volume restored before the done marker. Sounds come from files
 * the computer safety-checked before pushing them (lab/phone.check_safe_wav).
 */
class LiveMode(private val ctx: Context, private val p: Params, private val status: (String) -> Unit) {

    data class Params(
        val id: String,
        val role: String,          // "lead" or "follow"
        val own: String,           // WAV in files/signals/: this phone's beat sound (long sweep + short chirp)
        val partner: String,       // WAV in files/signals/: the OTHER phone's long sweep (what to listen for)
        val ownLong: String,       // WAV in files/signals/: this phone's own long sweep (to hear itself)
        val cycleMs: Int = 480,
        val cycles: Int = 30,
        val halfMs: Int = 240,
        val vol: Int = -1,
        val thresh: Double = 7.0,
        val rate: Int = 48000,
    )

    @Volatile private var running = true
    private val rate get() = p.rate
    // shared between the speaker, mic and clock threads
    @Volatile private var inFrames = 0L
    @Volatile private var outFrames = 0L
    @Volatile private var tFrame = -1L
    @Volatile private var tNs = 0L
    @Volatile private var rFrame = -1L
    @Volatile private var rNs = 0L
    private var correctionNs = 0L
    private var beatsLate = 0
    // the last 3 s of microphone sound, for listening (the full recording is kept separately for saving)
    private val ring = FloatArray(48000 * 3)

    /** Microphone samples for absolute input frames [from, from + n), from the rolling buffer. */
    private fun recent(from: Long, n: Int): FloatArray? {
        if (from < 0 || from + n > inFrames || from < inFrames - ring.size) return null
        return FloatArray(n) { ring[((from + it) % ring.size).toInt()] }
    }

    // ---------------------------------------------------------------- tiny FFT (radix 2, in place)
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val d = n.toDouble(); for (i in 0 until n) { re[i] = re[i] / d; im[i] = im[i] / d } }
    }

    /** Matched filter of `x` against `tmpl`: returns (index of best match = sound start in x, peak / rms). */
    private class Matcher(tmpl: FloatArray, private val n: Int) {
        val tr = DoubleArray(n); val ti = DoubleArray(n)
        val len = tmpl.size
        init {
            // smooth weighting of the template (same idea as the computer's matched filter)
            for (k in tmpl.indices) tr[k] = tmpl[k] * (0.5 - 0.5 * cos(2 * PI * k / (tmpl.size - 1)))
        }
        fun prepare(fft: (DoubleArray, DoubleArray, Boolean) -> Unit) { fft(tr, ti, false) }
        fun match(x: FloatArray, fft: (DoubleArray, DoubleArray, Boolean) -> Unit): Pair<Int, Double> {
            val xr = DoubleArray(n); val xi = DoubleArray(n)
            for (k in 0 until minOf(n, x.size)) xr[k] = x[k].toDouble()
            fft(xr, xi, false)
            for (k in 0 until n) {      // X * conj(T)
                val a = xr[k] * tr[k] + xi[k] * ti[k]
                val b = xi[k] * tr[k] - xr[k] * ti[k]
                xr[k] = a; xi[k] = b
            }
            fft(xr, xi, true)
            val valid = maxOf(1, x.size - len)
            var best = 0; var bestV = 0.0; var sum = 0.0
            for (k in 0 until valid) {
                val v = abs(xr[k]); sum += v * v
                if (v > bestV) { bestV = v; best = k }
            }
            val rms = sqrt(sum / valid) + 1e-20
            return Pair(best, bestV / rms)
        }
    }

    private fun mono(s: Stereo) = FloatArray(s.frames) { (s.left[it] + s.right[it]) / 2f }

    fun run(): String {
        val dir = ctx.getExternalFilesDir("signals")!!
        val own = Wav.read(File(dir, p.own)).first
        val partner = mono(Wav.read(File(dir, p.partner)).first)
        val ownLong = mono(Wav.read(File(dir, p.ownLong)).first)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (p.vol >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, p.vol.coerceAtMost(maxVol), 0)
        val notes = mutableListOf<String>()

        // ---- streams
        val inMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(inMin * 4, rate / 5 * 2))
            .build()
        val outMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(outMin * 2, 960 * 4))
            .build()
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { track.setPreferredDevice(it) }

        // ---- shared state
        val input = GrowingShorts()
        val schedule = java.util.concurrent.CopyOnWriteArrayList<Long>()      // output frames where own sound starts
        val intendedNs = java.util.concurrent.ConcurrentHashMap<Long, Long>()  // start frame -> when it should be HEARD
        val detections = StringBuilder()      // in_frame,score,time_ns
        val selfErr = StringBuilder()         // start_frame,error_ms
        val stamps = StringBuilder()          // kind,frame,ns
        val tsT = AudioTimestamp(); val tsR = AudioTimestamp()

        fun outFrameToNs(f: Long) = if (tFrame < 0) -1L else tNs + (f - tFrame) * 1_000_000_000L / rate
        fun nsToOutFrame(t: Long) = if (tFrame < 0) -1L else tFrame + (t - tNs) * rate / 1_000_000_000L
        fun inFrameToNs(f: Long) = if (rFrame < 0) -1L else rNs + (f - rFrame) * 1_000_000_000L / rate

        val chunk = 240
        val writer = Thread {
            val buf = ShortArray(chunk * 2)
            val L = own.left; val R = own.right
            while (running) {
                java.util.Arrays.fill(buf, 0)
                val a = outFrames; val b = a + chunk
                for (s in schedule) {
                    if (s + own.frames <= a || s >= b) continue
                    for (f in maxOf(a, s) until minOf(b, s + own.frames)) {
                        val k = (f - s).toInt(); val o = ((f - a).toInt()) * 2
                        buf[o] = (L[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        buf[o + 1] = (R[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                }
                track.write(buf, 0, buf.size)
                outFrames = b
            }
        }
        val reader = Thread {
            val buf = ShortArray(rate / 100)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    input.add(buf, n)
                    val base = inFrames
                    for (k in 0 until n) ring[((base + k) % ring.size).toInt()] = buf[k] / 32768f
                    inFrames = base + n
                }
            }
        }
        val clock = Thread {     // stream timestamps, every 100 ms (map frames <-> time on both streams)
            while (running) {
                if (track.getTimestamp(tsT)) { tFrame = tsT.framePosition; tNs = tsT.nanoTime
                    synchronized(stamps) { stamps.append("out,$tFrame,$tNs\n") } }
                if (rec.getTimestamp(tsR, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    rFrame = tsR.framePosition; rNs = tsR.nanoTime
                    synchronized(stamps) { stamps.append("in,$rFrame,$rNs\n") } }
                Thread.sleep(100)
            }
        }

        // ---- start
        status("Live: ${p.role}, starting...")
        rec.startRecording(); track.play()
        reader.start(); writer.start(); clock.start()
        Thread.sleep(600)                                   // streams settle, first timestamps arrive
        val totalMs = p.cycles.toLong() * p.cycleMs + 3000

        val startNs = System.nanoTime()
        if (p.role == "lead") {
            status("Live: LEAD, playing ${p.cycles} beats")
            val first = outFrames + rate / 2
            val step = p.cycleMs.toLong() * rate / 1000
            for (k in 0 until p.cycles) schedule.add(first + k * step)
        }

        // ---- listening loop (both roles: lead only checks itself; follow also answers)
        // look back 250 ms (+ template) each time: on the real POCO a check sometimes ran late and a beat
        // slipped between two checks of the last 100 ms (2 of 30 missed). Same FFT size, so no extra cost.
        val win = rate / 4
        fun nfft(m: Int): Int { var x = 1; while (x < m) x = x shl 1; return x }
        val mPartner = Matcher(partner, nfft(win + partner.size)).also { it.prepare(::fft) }
        val mSelf = Matcher(ownLong, nfft(win + ownLong.size)).also { it.prepare(::fft) }
        var lastDet = -1_000_000L
        val heardChecked = HashSet<Long>()
        while (System.nanoTime() - startNs < totalMs * 1_000_000L) {
            Thread.sleep(20)
            val now = inFrames
            // ---- recognise the partner (follow only), never inside our own loud sound
            if (p.role == "follow") {
                val len = win + partner.size
                val x = recent(now - len, len)
                if (x != null) {
                    val (k, score) = mPartner.match(x, ::fft)
                    val arr = now - len + k
                    val ownNear = schedule.any { s ->
                        val heard = outFrameToNs(s)
                        val inAt = if (heard < 0 || rFrame < 0) -1L else rFrame + (heard - rNs) * rate / 1_000_000_000L
                        inAt >= 0 && abs(inAt - arr) < rate * 15 / 100
                    }
                    if (score >= p.thresh && arr - lastDet > p.cycleMs.toLong() * rate / 2000 && !ownNear && k < win) {
                        lastDet = arr
                        val tArr = inFrameToNs(arr)
                        detections.append("$arr,${f2(score)},$tArr\n")
                        if (tArr > 0 && tFrame >= 0) {
                            // when our sound should be HEARD: half a beat after the lead's. If our speaker's
                            // queue is longer than that (real POCO: every half-beat answer came too late),
                            // answer a whole beat later: still exactly between the lead's beats.
                            var want = tArr + p.halfMs * 1_000_000L
                            var f = nsToOutFrame(want - correctionNs)
                            var extra = 0
                            while (f <= outFrames + chunk * 2 && extra < 4) {
                                want += p.cycleMs * 1_000_000L; f = nsToOutFrame(want - correctionNs); extra++
                            }
                            if (f > outFrames + chunk * 2) {
                                schedule.add(f); intendedNs[f] = want
                                if (extra > 0 && beatsLate != extra) { beatsLate = extra; notes.add("answering $extra beat(s) later (speaker queue)") }
                            } else notes.add("late answer skipped at in-frame $arr")
                        }
                    }
                }
            }
            // ---- hear ourselves: how far off was each own sound from when it was meant to be heard?
            for (s in schedule) {
                if (s in heardChecked) continue
                val t = outFrameToNs(s); if (t < 0) continue
                val inAt = if (rFrame < 0) continue else rFrame + (t - rNs) * rate / 1_000_000_000L
                val lo = inAt - rate / 5; val hi = inAt + rate / 5 + ownLong.size
                if (hi > now) continue
                heardChecked.add(s)
                val x = recent(lo, (hi - lo).toInt()) ?: continue
                val m = Matcher(ownLong, nfft(x.size)).also { it.prepare(::fft) }
                val (k, score) = m.match(x, ::fft)
                if (score < p.thresh) continue
                val heardNs = inFrameToNs(lo + k)
                val meant = intendedNs[s] ?: t
                val err = heardNs - meant
                selfErr.append("$s,${f2(err / 1e6)}\n")
                if (p.role == "follow") correctionNs += (err * 0.5).toLong()   // drummer: half-correct each time
            }
            status("Live: ${p.role}, heard partner ${detections.lines().size - 1} times, own sounds ${schedule.size}")
        }

        // ---- stop and save (volume back BEFORE the done marker)
        running = false
        writer.join(); reader.join(); clock.join()
        track.stop(); track.release(); rec.stop(); rec.release()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0)
        val wav = Results.save(ctx, "${p.id}.wav", "audio/x-wav", Wav.bytes(input.toShorts(), rate, 1))
        val meta = Json().put("id", p.id).put("mode", "live").put("role", p.role).put("rate", rate)
            .put("cycle_ms", p.cycleMs).put("half_ms", p.halfMs).put("cycles", p.cycles).put("volume_max", maxVol)
            .put("own", p.own).put("partner", p.partner).put("own_frames", own.frames)
            .put("schedule", schedule.joinToString(","))
            .put("intended_ns", intendedNs.entries.joinToString(",") { "${it.key}:${it.value}" })
            .put("detections", detections.toString()).put("self_error_ms", selfErr.toString())
            .put("timestamps", synchronized(stamps) { stamps.toString() })
            .put("beats_late", beatsLate).put("in_frames", inFrames).put("out_frames", outFrames).put("final_correction_ms", correctionNs / 1e6)
            .raw("notes", Json.list(notes.map { Json.quote(it) })).put("saved", wav)
        Results.save(ctx, "${p.id}.json", "application/json", meta.toString().toByteArray())
        return "live ${p.role}: ${schedule.size} own sounds, partner heard ${detections.lines().size - 1} times"
    }
}
