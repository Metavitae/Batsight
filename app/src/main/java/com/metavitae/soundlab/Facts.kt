package com.metavitae.soundlab

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MicrophoneInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Task 1, the parts the phone can REPORT about itself (no sound is played).
 * Every line is labeled PHONE-REPORTED or MEASURED. Things only an acoustic test can show
 * (highest pitch, left/right speakers, play-to-heard delay) are done with PlayRecord runs from the computer.
 */
class Facts(private val ctx: Context, private val status: (String) -> Unit) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sb = StringBuilder()
    private fun line(s: String = "") { sb.append(s).append('\n') }

    fun run(): String {
        sb.append(Results.header("Phone sound facts"))
        status("Audio properties..."); properties()
        status("Speakers and mics..."); devices()
        status("Microphone details..."); microphones()
        status("Recording sources..."); sources()
        status("Sample rates..."); rates()
        status("Sound effects..."); effects()
        status("Motion sensors (about 3 seconds)..."); sensors()
        line("Done.")
        return sb.toString()
    }

    private fun properties() {
        line("== Audio properties (PHONE-REPORTED) ==")
        fun prop(label: String, key: String) = line("$label: ${am.getProperty(key) ?: "not reported"}")
        prop("Native output sample rate", AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        prop("Native output buffer (frames)", AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        prop("Mic near-ultrasound support", AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)
        prop("Speaker near-ultrasound support", AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)
        prop("Unprocessed recording source support", AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        val pm = ctx.packageManager
        for (f in listOf(PackageManager.FEATURE_AUDIO_LOW_LATENCY, PackageManager.FEATURE_AUDIO_PRO,
                PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_SENSOR_GYROSCOPE,
                PackageManager.FEATURE_SENSOR_BAROMETER, PackageManager.FEATURE_SENSOR_ACCELEROMETER))
            line("Feature $f: ${pm.hasSystemFeature(f)}")
        line("Media volume now: ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        line()
    }

    private fun devices() {
        line("== Speakers and mics the phone lists (PHONE-REPORTED) ==")
        for ((label, flag) in listOf("OUTPUT" to AudioManager.GET_DEVICES_OUTPUTS, "INPUT" to AudioManager.GET_DEVICES_INPUTS)) {
            for (d in am.getDevices(flag)) {
                line("$label ${PlayRecord.typeName(d.type)}: id=${d.id} address='${d.address}' name='${d.productName}' " +
                    "channels=${d.channelCounts.joinToString("/").ifEmpty { "any" }} rates=${d.sampleRates.joinToString("/").ifEmpty { "any" }}")
            }
        }
        line("(Use the INPUT id with --ei mic <id> to pick a microphone in play-record runs.)")
        line()
    }

    private fun microphones() {
        line("== Microphone details (PHONE-REPORTED) ==")
        val mics: List<MicrophoneInfo> = try { am.microphones } catch (e: Exception) { line("not available: ${e.message}"); line(); return }
        if (mics.isEmpty()) line("phone lists no microphone details")
        for (m in mics) {
            val loc = when (m.location) {
                MicrophoneInfo.LOCATION_MAINBODY -> "main body"
                MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "main body (movable)"
                MicrophoneInfo.LOCATION_PERIPHERAL -> "peripheral"
                else -> "unknown"
            }
            val dir = when (m.directionality) {
                MicrophoneInfo.DIRECTIONALITY_OMNI -> "all directions"
                MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
                MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "two-way"
                else -> "unknown"
            }
            val pos = m.position
            val posTxt = if (pos == MicrophoneInfo.POSITION_UNKNOWN) "unknown" else "x=${pos.x} y=${pos.y} z=${pos.z} m"
            line("Mic '${m.description}' id=${m.id} type=${PlayRecord.typeName(m.type)} address='${m.address}' location=$loc " +
                "group=${m.group}/${m.indexInTheGroup} position=$posTxt directionality=$dir sensitivity=${m.sensitivity} dBFS/Pa")
            val fr = m.frequencyResponse
            if (fr.isNotEmpty()) line("   frequency response (Hz:dB): " + fr.joinToString(" ") { "${it.first.toInt()}:${f2(it.second.toDouble())}" })
            else line("   frequency response: not reported")
        }
        line()
    }

    /** Opens every recording source in mono and stereo for half a second. A "stereo" recording whose two
     *  channels are identical is really one mic copied twice. */
    private fun sources() {
        line("== Recording sources (MEASURED, 0.5 s of room sound each) ==")
        for ((name, src) in PlayRecord.SOURCES) for (ch in listOf(1, 2)) {
            val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val res = try {
                val minBuf = AudioRecord.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) "refused" else {
                    val r = AudioRecord.Builder().setAudioSource(src)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(minBuf * 4).build()
                    if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); "did not start" } else {
                        val buf = ShortArray(48000 * ch / 2)
                        r.startRecording()
                        var got = 0
                        val until = System.currentTimeMillis() + 1500
                        while (got < buf.size && System.currentTimeMillis() < until) {
                            val n = r.read(buf, got, buf.size - got); if (n > 0) got += n else break
                        }
                        r.stop()
                        val fx = listOf(
                            "NS" to (if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId) else null),
                            "AEC" to (if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId) else null),
                            "AGC" to (if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(r.audioSessionId) else null))
                        val fxTxt = fx.joinToString(" ") { (n, e) -> "$n=${e?.enabled ?: "-"}" }
                        fx.forEach { it.second?.release() }
                        val out = StringBuilder("ok, got ${r.channelCount} ch at ${r.sampleRate} Hz via ${PlayRecord.dev(r.routedDevice)}; level ")
                        val c = r.channelCount
                        for (k in 0 until c) out.append("ch$k=${f2(dbfs(rms(buf.copyOf(got), c, k)))} dBFS ")
                        if (c == 2) out.append("| left vs right: ${stereoVerdict(buf, got)} ")
                        out.append("| effects on by default: $fxTxt")
                        r.release()
                        out.toString()
                    }
                }
            } catch (e: Exception) { "error: ${e.javaClass.simpleName} ${e.message}" }
            line("$name, ${if (ch == 2) "stereo" else "mono"}: $res")
        }
        line("(NS = noise suppressor, AEC = echo canceler, AGC = automatic gain. 'true' means the phone switches it on by itself for that source.)")
        line()
    }

    private fun stereoVerdict(b: ShortArray, n: Int): String {
        var same = true; var sl = 0.0; var sr = 0.0; var slr = 0.0
        var i = 0
        while (i + 1 < n) {
            val l = b[i].toDouble(); val r = b[i + 1].toDouble()
            if (b[i] != b[i + 1]) same = false
            sl += l * l; sr += r * r; slr += l * r
            i += 2
        }
        if (same) return "IDENTICAL (one mic copied twice)"
        val corr = if (sl == 0.0 || sr == 0.0) 0.0 else slr / sqrt(sl * sr)
        return "different (correlation ${f2(corr)}; near 1.00 = nearly the same sound, lower = two separate mics likely)"
    }

    private fun rates() {
        line("== Sample rates (MEASURED: does the phone accept them; it may still convert inside) ==")
        for (r in listOf(44100, 48000, 96000, 192000)) {
            val recMin = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recOk = recMin > 0 && try {
                val a = AudioRecord.Builder().setAudioSource(PlayRecord.SOURCES.getValue("mic"))
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(recMin * 2).build()
                val ok = a.state == AudioRecord.STATE_INITIALIZED; a.release(); ok
            } catch (e: Exception) { false }
            val playMin = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            line("$r Hz: record=${if (recOk) "accepted" else "refused"} play=${if (playMin > 0) "accepted" else "refused"}")
        }
        line()
    }

    private fun effects() {
        line("== Sound effects the phone offers (PHONE-REPORTED) ==")
        line("Noise suppressor available: ${NoiseSuppressor.isAvailable()}")
        line("Echo canceler available: ${AcousticEchoCanceler.isAvailable()}")
        line("Automatic gain available: ${AutomaticGainControl.isAvailable()}")
        for (d in AudioEffect.queryEffects() ?: emptyArray()) line("effect: ${d.name} (${d.implementor})")
        line()
    }

    private fun sensors() {
        line("== Motion sensors ==")
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val want = listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_PRESSURE,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_PROXIMITY)
        val times = HashMap<Int, ArrayList<Long>>()
        val th = HandlerThread("facts-sensors").also { it.start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { synchronized(times) { times.getOrPut(e.sensor.type) { ArrayList() }.add(e.timestamp) } }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        for (t in want) {
            val s = sm.getDefaultSensor(t)
            if (s == null) { line("Sensor type $t: NOT PRESENT (PHONE-REPORTED)"); continue }
            line("${s.stringType}: '${s.name}' by ${s.vendor}; fastest promised ${if (s.minDelay > 0) "${1_000_000 / s.minDelay} per second" else "on change only"} (PHONE-REPORTED)")
            sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST, Handler(th.looper))
        }
        Thread.sleep(3000)
        sm.unregisterListener(listener)
        th.quitSafely()
        synchronized(times) {
            for (t in want) {
                val ts = times[t] ?: continue
                if (ts.size < 3) { line("type $t: only ${ts.size} readings in 3 s (MEASURED)"); continue }
                val gaps = ts.zipWithNext { a, b -> (b - a) / 1e6 }
                val mean = gaps.average()
                val jitter = gaps.map { abs(it - mean) }.average()
                line("${sm.getDefaultSensor(t)?.stringType}: ${f2(1000 / mean)} readings per second, gap ${f2(mean)} ms ± ${f2(jitter)} ms (MEASURED)")
            }
        }
        line()
    }
}
