package com.metavitae.soundlab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.hypot
import kotlin.math.max

/**
 * Screens 1 (Listen) and 3 (Walk): the founder's Warp rise every 200 ms (-20 dBFS), judged ping by ping on
 * the phone. Per ping it reports the nearest trusted echo (within 3 m), whether something answered from
 * beyond 3 m (Chat's green rule needs that, on two passes), and the phone's heading (game rotation vector)
 * so Listen can draw echoes where the phone points. Direction from pointing is a TEST, like everything here.
 * The raw recording and headings are saved for CC (app files/field/<id>.wav + Download/SoundLab/<id>_live.csv).
 */
class LiveEngine(private val ctx: Context, val id: String, private val onPing: (Ping) -> Unit) {
    class Ping(val t: Double, val heading: Double, val nearM: Double?, val far: Boolean, val heard: Boolean)

    private val rate = Sonar.RATE
    private val cycle = rate * 200 / 1000
    private val warp = Sonar.warp(ctx)
    private val nBins = Dsp.bins(4.5, rate)
    private val tail = Dsp.directTail(1.0, warp, nBins)          // the sound's own fade, per unit of direct sound
    @Volatile var running = false; private set
    @Volatile private var heading = 0.0
    private val threads = ArrayList<Thread>()
    private val dir = ctx.getExternalFilesDir("field")!!.also { it.mkdirs() }
    private val log = StringBuilder("t_s,heading_deg,near_m,far,heard,speaker,lvl_db\n")
    private var strongParity = -1; private val firstLevels = DoubleArray(2)
    // Founder 2026-10-22 (option 1): the first two pings try left then right; after that EVERY ping plays from the
    // speaker mic 0 hears loudest (the one beside it), so no ping is wasted. -1 = still trying both.
    @Volatile private var playSide = -1
    private var weakRun = 0; private var flipped = false
    private var ch = 1
    private val keepFrames = rate * 4
    private var buf = ShortArray(0); private var bufStart = 0L; private var bufLen = 0
    private val lock = Object()
    private var volBefore = -1
    private var sensorThread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private val recent = ArrayDeque<Double?>()

    fun start() {
        running = true
        val am = ctx.getSystemService(AudioManager::class.java)
        volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6).toInt(), 0)
        val sm = ctx.getSystemService(SensorManager::class.java)
        sensorThread = HandlerThread("live-heading").also { it.start() }
        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { heading = ScanEngine.yawOf(e.values) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, Handler(sensorThread!!.looper)) }

        val rec = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(rate * 2 * 2).build()
        ch = rec.channelCount
        buf = ShortArray(keepFrames * ch)
        for ((ok, make) in listOf(
            NoiseSuppressor.isAvailable() to { NoiseSuppressor.create(rec.audioSessionId) as android.media.audiofx.AudioEffect? },
            AcousticEchoCanceler.isAvailable() to { AcousticEchoCanceler.create(rec.audioSessionId) },
            AutomaticGainControl.isAvailable() to { AutomaticGainControl.create(rec.audioSessionId) })) if (ok) try { make()?.enabled = false } catch (_: Throwable) {}
        val side = Array(2) { ShortArray(cycle * 2) }  // one ping from the left speaker, one from the right
        for (k in warp.indices) { val v = (warp[k] * 32767).toInt().toShort(); side[0][2 * k] = v; side[1][2 * k + 1] = v }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(cycle * 2 * 2 * 2).build()
        val out = RandomAccessFile(File(dir, "$id.wav"), "rw"); out.setLength(0); out.write(ByteArray(44))
        rec.startRecording()
        val t0 = System.currentTimeMillis()
        threads += Thread {
            val b = ShortArray(rate / 50 * ch); val bytes = ByteArray(b.size * 2)
            while (running) {
                val n = rec.read(b, 0, b.size); if (n <= 0) continue
                for (i in 0 until n) { bytes[2 * i] = (b[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (b[i].toInt() shr 8).toByte() }
                out.write(bytes, 0, n * 2)
                synchronized(lock) {
                    val frames = n / ch
                    if (bufLen + frames > keepFrames) { val drop = bufLen + frames - keepFrames; System.arraycopy(buf, drop * ch, buf, 0, (bufLen - drop) * ch); bufLen -= drop; bufStart += drop }
                    System.arraycopy(b, 0, buf, bufLen * ch, n); bufLen += frames
                }
                if (System.currentTimeMillis() - t0 > 20 * 60_000) running = false
            }
            rec.stop(); rec.release()
            val len = out.length() - 44
            val hdr = Wav.bytes(ShortArray(0), rate, ch).copyOf(44)
            fun put(at: Int, v: Long) { for (k in 0 until 4) hdr[at + k] = (v shr (8 * k)).toByte() }
            put(4, 36 + len); put(40, len); out.seek(0); out.write(hdr); out.close()
        }
        threads += Thread {
            track.play(); var c = 0
            while (running) { val s = playSide; track.write(side[if (s < 0) c % 2 else s], 0, cycle * 2); c++ }
            track.stop(); track.release()
        }
        threads += Thread { process() }
        threads.forEach { it.start() }
    }

    fun stop() {
        running = false
        threads.forEach { it.join(3000) }
        listener?.let { ctx.getSystemService(SensorManager::class.java).unregisterListener(it) }
        sensorThread?.quitSafely()
        if (volBefore >= 0) ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0)
        Results.save(ctx, "${id}_live.csv", "text/csv", log.toString().toByteArray())
    }

    private fun available(): Long = synchronized(lock) { bufStart + bufLen }
    private fun window(startAbs: Long, frames: Int): DoubleArray? = synchronized(lock) {
        val off = (startAbs - bufStart).toInt()
        if (off < 0 || off + frames > bufLen) return null
        DoubleArray(frames) { buf[(off + it) * ch] / 32768.0 }
    }

    private fun process() {
        while (running && available() < rate * 3 / 2) Thread.sleep(50)
        if (!running) return
        val firstAt = available() - rate * 3 / 2
        val first = window(firstAt, rate * 3 / 2) ?: return
        val (r0, i0) = Dsp.matched(first, warp)
        val a0 = Dsp.firstArrival(Dsp.env(r0, i0), 0, cycle * 3) ?: return
        var next = firstAt + a0
        val pre = 300; val span = warp.size + nBins + 2 * pre
        val minBin = Dsp.bins(Sonar.minM, rate); val b3 = Dsp.bins(3.0, rate)
        var i = 0
        while (running) {
            while (running && available() < next - pre + span) Thread.sleep(15)
            if (!running) break
            val w = window(next - pre, span)
            if (w == null) { next += cycle; continue }
            val (mr, mi) = Dsp.matched(w, warp)
            val e = Dsp.env(mr, mi)
            var k = pre - 48
            for (q in pre - 48..pre + 48) if (e[q] > e[k]) k = q
            val base = next - pre
            val parity = i % 2
            val prof = DoubleArray(nBins) { hypot(mr[k + it], mi[k + it]) }
            val direct = prof[0]
            val lvDb = 20 * kotlin.math.log10(direct + 1e-20)
            // settling (first ~3 s): a ping counts only within 10 dB of the loud speaker's first ping (the other speaker
            // is ~20 dB below), which skips the pings still queued from the left/right try. After that every ping counts,
            // so a hand over the mic is measured, not thrown away.
            val strong = strongParity < 0 || i >= 14 || lvDb >= firstLevels[strongParity] - 10
            next = if (strong) base + k + cycle else next + cycle   // follow the clocks' slow drift on the loud speaker only
            if (strongParity < 0) {
                firstLevels[parity] = lvDb
                if (i == 1) { strongParity = if (firstLevels[0] >= firstLevels[1]) 0 else 1; playSide = strongParity }
                i++; continue
            }
            val spk = if (playSide == 0) "L" else "R"
            if (!strong) {
                synchronized(log) { log.append(String.format(java.util.Locale.US, "%.2f,%.1f,,,,%s,%.1f\n", i * 0.2, heading, spk + "?", lvDb)) }
                // three quiet pings in a row while settling: the guess of which speaker is which was wrong, swap once
                if (++weakRun >= 3 && !flipped) { playSide = 1 - playSide; flipped = true; weakRun = 0 }
                i++; continue
            }
            weakRun = 0
            val sorted = prof.copyOfRange(minBin, nBins).sorted()
            val noise = sorted[sorted.size / 2] + 1e-12
            val heard = direct > 1e-4
            var near: Double? = null; var far = false
            for (b in max(1, minBin) until nBins - 1) {
                val fl = max(6 * noise, tail[b] * direct * 3.16)   // 6x the noise, or the sound's own fade + 10 dB
                if (prof[b] > fl && prof[b] >= prof[b - 1] && prof[b] > prof[b + 1]) {
                    if (b < b3) { if (near == null) near = Dsp.rangeM(b, rate) } else far = true
                }
            }
            // a nearest echo counts when 2 of the last 3 pings agree within 10 cm (one ping alone can be a fluke)
            recent.addLast(near); while (recent.size > 3) recent.removeFirst()
            val agreed = near?.let { d -> if (recent.count { it != null && kotlin.math.abs(it - d) < 0.10 } >= 2) d else null }
            val t = i * 0.2; i++
            synchronized(log) { log.append(String.format(java.util.Locale.US, "%.2f,%.1f,%s,%d,%d,%s,%.1f\n", t, heading, agreed?.let { f2(it) } ?: "", if (far) 1 else 0, if (heard) 1 else 0, spk, lvDb)) }
            onPing(Ping(t, heading, agreed, far, heard))
        }
    }
}
