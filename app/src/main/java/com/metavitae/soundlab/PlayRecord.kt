package com.metavitae.soundlab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * The one building block every sound test uses: start recording, wait, play a sound, keep recording, save.
 * Saves to Download/SoundLab/:  <id>.wav (what the mic heard), <id>_played.wav (exactly what was played),
 * <id>_motion.csv (gyroscope + accelerometer, if asked) and <id>.json (settings + timing). The .json is
 * written LAST, so CC knows the run is finished when it appears.
 */
class PlayRecord(private val ctx: Context, private val p: Params, private val status: (String) -> Unit) {

    class Params(
        val id: String,
        val spec: String?,        // signal recipe (see Signals), or null
        val file: String?,        // or a 16-bit WAV that CC pushed into the app's files/signals/ folder
        val source: String,       // unprocessed | mic | camcorder | voice_recognition | voice_communication | voice_performance | default
        val channels: Int,        // 1 or 2 recording channels
        val rate: Int,            // samples per second (play and record)
        val micId: Int,           // AudioDeviceInfo id from facts, or -1 = let the phone choose
        val out: String,          // speaker | earpiece | default
        val vol: Int,             // media volume step to set, or -1 = leave as it is
        val preMs: Int,           // record this long before playing (room "just before" sample)
        val postMs: Int,          // keep recording this long after the sound ends (echoes, reverb)
        val delayMs: Int,         // extra wait before playing (two-phone tests)
        val recordMs: Int,        // record-only length when there is no sound to play
        val swap: Boolean,        // swap left and right before playing
        val motion: Boolean,      // log gyroscope + accelerometer during the run
        val effectsOff: Boolean,  // switch off noise suppression / echo cancelling / auto gain if the phone allows
        val armed: Boolean = false, // handshake: report "recording", play only on <id>.go, stop only on <id>.stop
    )

    companion object {
        val SOURCES = mapOf(
            "default" to MediaRecorder.AudioSource.DEFAULT,
            "mic" to MediaRecorder.AudioSource.MIC,
            "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
            "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
            "voice_performance" to MediaRecorder.AudioSource.VOICE_PERFORMANCE,
        )
        fun typeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call audio)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (music)"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
            else -> "type $t"
        }
        fun dev(d: AudioDeviceInfo?) = if (d == null) "none" else "${typeName(d.type)} id=${d.id} '${d.address}'"
    }

    @Volatile private var recording = true

    /** Volume as the owner had it; put back after every run (the computer can't: Android ignores
     *  'cmd media_session volume --set' on both test phones, MEASURED 2026-10-12). */
    var volBefore = -1

    fun restoreVolume() {
        if (volBefore >= 0) (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0)
    }

    fun run(): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notes = ArrayList<String>()

        // ---- the sound to play
        var played: Stereo? = when {
            p.file != null -> {
                val (s, r) = Wav.read(File(ctx.getExternalFilesDir("signals"), p.file))
                require(r == p.rate) { "signal file is $r Hz but the run is ${p.rate} Hz" }
                s
            }
            !p.spec.isNullOrBlank() -> Signals.build(p.spec, p.rate)
            else -> null
        }
        if (played != null && p.swap) played = played.swapped()

        // ---- volume
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (p.vol >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, p.vol.coerceAtMost(maxVol), 0)
        val volNow = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        // ---- recorder
        val src = SOURCES[p.source] ?: error("unknown source ${p.source}")
        val chMask = if (p.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(p.rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { "phone refuses to record at ${p.rate} Hz with ${p.channels} channel(s)" }
        val rec = AudioRecord.Builder()
            .setAudioSource(src)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(p.rate).setChannelMask(chMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf * 4, p.rate * p.channels * 2 / 2))
            .build()
        require(rec.state == AudioRecord.STATE_INITIALIZED) { "recorder did not start (source ${p.source})" }
        if (p.micId >= 0) {
            val d = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == p.micId }
            if (d == null) notes.add("mic id ${p.micId} not found, phone chose") else rec.setPreferredDevice(d)
        }
        val effects = Json()
        val keep = ArrayList<AudioEffect>()
        fun fx(name: String, available: Boolean, make: () -> AudioEffect?) {
            if (!available) { effects.put(name, "not available"); return }
            val e = try { make() } catch (t: Throwable) { null }
            if (e == null) { effects.put(name, "could not attach"); return }
            val wasOn = e.enabled
            if (p.effectsOff && wasOn) e.setEnabled(false)
            effects.put(name, "on by default=$wasOn, now=${e.enabled}")
            keep.add(e)
        }
        fx("noise_suppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(rec.audioSessionId) }
        fx("echo_canceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(rec.audioSessionId) }
        fx("auto_gain", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(rec.audioSessionId) }

        // ---- player
        var track: AudioTrack? = null
        if (played != null) {
            val usage = if (p.out == "earpiece") AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            val pcm = played.interleaved16()
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(p.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            val want = when (p.out) {
                "speaker" -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                "earpiece" -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                else -> null
            }
            if (want != null) {
                val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == want }
                if (d == null) notes.add("output '${p.out}' not found") else track.setPreferredDevice(d)
            }
        }

        // ---- motion log
        val motion = StringBuilder("sensor,t_ns,x,y,z\n")
        var sensorThread: HandlerThread? = null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) "gyro" else "accel"
                synchronized(motion) { motion.append("$n,${e.timestamp},${e.values[0]},${e.values[1]},${e.values[2]}\n") }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (p.motion) {
            sensorThread = HandlerThread("motion").also { it.start() }
            val h = Handler(sensorThread.looper)
            for (t in listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER))
                sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        }

        // ---- run
        val data = GrowingShorts()
        val recTs = AudioTimestamp()
        var recTsOk = false
        val reader = Thread {
            val buf = ShortArray(p.rate * p.channels / 50)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) data.add(buf, n)
                if (!recTsOk && data.count > p.rate * p.channels / 10)
                    recTsOk = rec.getTimestamp(recTs, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            }
        }
        status("Recording...")
        rec.startRecording()
        reader.start()
        Thread.sleep((p.preMs + p.delayMs).toLong())
        // Handshake (founder's idea, 2026-10-12): no guessing by the clock. Say "I'm recording" only
        // once sound is really arriving, then wait for the computer's go (a file it drops in our own
        // files/signals/ folder). Max 60 s, then give up.
        val trig = ctx.getExternalFilesDir("signals")!!
        fun waitFor(name: String): Boolean {
            val f = File(trig, "${p.id}.$name")
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end) {
                if (f.exists()) { f.delete(); return true }
                Thread.sleep(2)
            }
            return false
        }
        if (p.armed) {
            while (data.count == 0L) Thread.sleep(2)
            Results.saveText(ctx, "${p.id}_recording.txt", "recording")
            status("Recording, waiting for go...")
            if (!waitFor("go")) notes.add("no go signal within 60 s")
        }

        var playCallNs = 0L
        var framesAtPlayCall = 0L
        val trackTs = AudioTimestamp()
        var trackTsOk = false
        if (track != null && played != null) {
            status("Playing...")
            framesAtPlayCall = data.count / p.channels
            playCallNs = System.nanoTime()
            track.play()
            val endAt = System.currentTimeMillis() + played.frames * 1000L / p.rate + 300
            while (System.currentTimeMillis() < endAt) {
                if (!trackTsOk && track.getTimestamp(trackTs) && trackTs.framePosition > 0) trackTsOk = true
                Thread.sleep(5)
            }
            if (p.armed) {
                Results.saveText(ctx, "${p.id}_played.txt", "played")
                status("Played, recording until stop...")
                if (!waitFor("stop")) notes.add("no stop signal within 60 s")
                Thread.sleep(p.postMs.toLong())
            } else Thread.sleep(p.postMs.toLong())
        } else if (p.armed) {
            if (!waitFor("stop")) notes.add("no stop signal within 60 s")
        } else Thread.sleep(p.recordMs.toLong())

        recording = false
        reader.join()
        rec.stop()
        val routedIn = rec.routedDevice
        val routedOut = track?.routedDevice
        val realRate = rec.sampleRate
        val realCh = rec.channelCount
        track?.stop(); track?.release()
        restoreVolume()   // the owner's own volume back BEFORE the done-marker is written
        rec.release()
        keep.forEach { it.release() }
        if (p.motion) { sm.unregisterListener(listener); sensorThread?.quitSafely() }

        // ---- save
        status("Saving...")
        val samples = data.toShorts()
        val saved = ArrayList<String>()
        saved.add(Results.save(ctx, "${p.id}.wav", "audio/x-wav", Wav.bytes(samples, realRate, realCh)))
        if (played != null) saved.add(Results.save(ctx, "${p.id}_played.wav", "audio/x-wav", Wav.stereoBytes(played, p.rate)))
        if (p.motion) saved.add(Results.save(ctx, "${p.id}_motion.csv", "text/csv", synchronized(motion) { motion.toString() }.toByteArray()))

        val meta = Json()
            .put("id", p.id).put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("spec", p.spec).put("file", p.file).put("swap", p.swap)
            .put("source", p.source).put("rate_asked", p.rate).put("rate", realRate)
            .put("channels_asked", p.channels).put("channels", realCh)
            .put("mic_asked", p.micId).put("mic_routed", dev(routedIn))
            .put("out_asked", p.out).put("out_routed", dev(routedOut))
            .put("volume", volNow).put("volume_max", maxVol)
            .put("pre_ms", p.preMs).put("post_ms", p.postMs).put("delay_ms", p.delayMs)
            .put("frames_recorded", samples.size / maxOf(realCh, 1))
            .put("played_frames", played?.frames ?: 0)
            .put("frames_at_play_call", framesAtPlayCall).put("play_call_ns", playCallNs)
            .put("track_ts_ok", trackTsOk).put("track_ts_frame", trackTs.framePosition).put("track_ts_ns", trackTs.nanoTime)
            .put("rec_ts_ok", recTsOk).put("rec_ts_frame", recTs.framePosition).put("rec_ts_ns", recTs.nanoTime)
            // sensor times use the elapsed-realtime clock, audio times the monotonic clock: keep one pair to convert
            .put("clock_mono_ns", System.nanoTime()).put("clock_elapsed_ns", android.os.SystemClock.elapsedRealtimeNanos())
            .raw("effects", effects.toString())
            .raw("notes", Json.list(notes.map { Json.quote(it) }))
            .raw("saved", Json.list(saved.map { Json.quote(it) }))
        val last = Results.save(ctx, "${p.id}.json", "application/json", meta.toString().toByteArray())
        return (saved + last).joinToString("\n") + if (notes.isEmpty()) "" else "\nNotes: " + notes.joinToString("; ")
    }
}
