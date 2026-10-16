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
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile

/**
 * Direction + walking test (founder 2026-10-15: "see under dark or smoky conditions ... measure things up ...
 * a reliable blueprint"; "walking around it's also needed"). Plays a 200 ms cycle: short 5 ms chirp at 0 ms
 * (near view) and the founder's Warp rise at 30 ms (far view), both -20 dBFS, for as long as it runs.
 * Records every mic, the motion sensors (gyroscope, accelerometer, game rotation = heading) and audio
 * clock pairs so CC can line sound and motion up exactly. "Mark" taps = ground truth (object straight
 * ahead / passing a tape mark). No analysis on the phone: CC measures the files (lab/scan.py).
 * Files: app files/field/<id>.wav (sound) + Download/SoundLab/<id>_motion.csv, <id>_marks.csv,
 * <id>_clock.csv, <id>_played.wav (one cycle), <id>.json (written last).
 */
class ScanEngine(private val ctx: Context, val id: String, private val dry: Boolean, private val maxSecs: Int = 180) {
    private val rate = Sonar.RATE
    private val cycle = rate * 200 / 1000
    @Volatile var running = false; private set
    @Volatile var headingDeg = 0.0; private set
    @Volatile var seconds = 0.0; private set
    private val threads = ArrayList<Thread>()
    private val motion = StringBuilder("sensor,t_ns,x,y,z,w\n")
    private val marks = StringBuilder("t_ns,label\n")
    private val clock = StringBuilder("frame,t_ns_boottime\n")
    private var sensorThread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private var volBefore = -1
    private var ch = 1
    private var framesRec = 0L
    private val dir = ctx.getExternalFilesDir("field")!!.also { it.mkdirs() }
    private val wavFile = File(dir, "$id.wav")
    private var startNs = 0L

    fun mark(label: String) { synchronized(marks) { marks.append("${SystemClock.elapsedRealtimeNanos()},$label\n") } }

    fun oneCycle(): Stereo {
        val s = Sonar.short(); val w = Sonar.warp(ctx)
        val l = FloatArray(cycle)
        for (k in s.indices) l[k] += s[k].toFloat()
        val at = rate * 30 / 1000
        for (k in w.indices) l[at + k] += w[k].toFloat()
        return Stereo(l, l.copyOf())
    }

    fun start() {
        running = true
        startNs = SystemClock.elapsedRealtimeNanos()
        val am = ctx.getSystemService(AudioManager::class.java)
        volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, if (dry) 0 else (maxV * 0.6).toInt(), 0)

        // motion: gyroscope, accelerometer, game rotation vector (heading without the compass), as fast as the phone allows
        val sm = ctx.getSystemService(SensorManager::class.java)
        sensorThread = HandlerThread("scan-motion").also { it.start() }
        val h = Handler(sensorThread!!.looper)
        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = when (e.sensor.type) { Sensor.TYPE_GYROSCOPE -> "gyro"; Sensor.TYPE_ACCELEROMETER -> "accel"; else -> "rot" }
                val w = if (e.values.size > 3) e.values[3] else 0f
                if (n == "rot") headingDeg = yawOf(e.values)
                synchronized(motion) { motion.append("$n,${e.timestamp},${e.values[0]},${e.values[1]},${e.values[2]},$w\n") }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        for (t in listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GAME_ROTATION_VECTOR))
            sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }

        val rec = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(rate * 2 * 2).build()
        ch = rec.channelCount
        for ((ok, make) in listOf(
            NoiseSuppressor.isAvailable() to { NoiseSuppressor.create(rec.audioSessionId) as android.media.audiofx.AudioEffect? },
            AcousticEchoCanceler.isAvailable() to { AcousticEchoCanceler.create(rec.audioSessionId) },
            AutomaticGainControl.isAvailable() to { AutomaticGainControl.create(rec.audioSessionId) })) if (ok) try { make()?.enabled = false } catch (_: Throwable) {}

        val one = oneCycle().interleaved16()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(one.size * 2 * 2).build()

        val out = RandomAccessFile(wavFile, "rw"); out.setLength(0); out.write(ByteArray(44))
        rec.startRecording()
        threads += Thread {
            val b = ShortArray(rate / 50 * ch); val bytes = ByteArray(b.size * 2)
            val ts = AudioTimestamp(); var lastTs = 0L
            while (running) {
                val n = rec.read(b, 0, b.size)
                if (n <= 0) continue
                for (i in 0 until n) { bytes[2 * i] = (b[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (b[i].toInt() shr 8).toByte() }
                out.write(bytes, 0, n * 2)
                framesRec += n / ch
                seconds = framesRec.toDouble() / rate
                // audio clock pairs (boottime = the sensors' clock) every ~0.5 s
                if (framesRec - lastTs > rate / 2 && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    lastTs = framesRec; synchronized(clock) { clock.append("${ts.framePosition},${ts.nanoTime}\n") }
                }
                if (seconds > maxSecs) running = false
            }
            rec.stop(); rec.release()
            val len = out.length() - 44
            val hdr = Wav.bytes(ShortArray(0), rate, ch).copyOf(44)
            fun put(at: Int, v: Long) { for (k in 0 until 4) hdr[at + k] = (v shr (8 * k)).toByte() }
            put(4, 36 + len); put(40, len)
            out.seek(0); out.write(hdr); out.close()
        }
        threads += Thread {
            track.play()
            while (running) track.write(one, 0, one.size)
            track.stop(); track.release()
        }
        threads.forEach { it.start() }
    }

    /** Stops and saves. Blocking (a second or two). Returns where things went. */
    fun stop(): String {
        running = false
        threads.forEach { it.join(4000) }
        listener?.let { ctx.getSystemService(SensorManager::class.java).unregisterListener(it) }
        sensorThread?.quitSafely()
        if (volBefore >= 0) ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0)
        Results.save(ctx, "${id}_motion.csv", "text/csv", synchronized(motion) { motion.toString() }.toByteArray())
        Results.save(ctx, "${id}_marks.csv", "text/csv", synchronized(marks) { marks.toString() }.toByteArray())
        Results.save(ctx, "${id}_clock.csv", "text/csv", synchronized(clock) { clock.toString() }.toByteArray())
        Results.save(ctx, "${id}_played.wav", "audio/x-wav", Wav.stereoBytes(oneCycle(), rate))
        Results.save(ctx, "$id.json", "application/json", Json().put("id", id).put("phone", Sonar.phoneName).put("channels", ch)
            .put("rate", rate).put("cycle_ms", 200).put("short_at_ms", 0).put("warp_at_ms", 30).put("short_top_hz", Sonar.shortTop)
            .put("dry", dry).put("seconds", seconds).put("start_boottime_ns", startNs).put("wav", wavFile.absolutePath).toString().toByteArray())
        return wavFile.absolutePath
    }

    companion object {
        /** Heading (yaw, degrees) from a rotation-vector reading. */
        fun yawOf(v: FloatArray): Double {
            val r = FloatArray(9); val o = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(r, v); SensorManager.getOrientation(r, o)
            return Math.toDegrees(o[0].toDouble())
        }
    }
}
