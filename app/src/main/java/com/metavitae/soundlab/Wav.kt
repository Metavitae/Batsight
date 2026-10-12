package com.metavitae.soundlab

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV, interleaved. */
object Wav {
    fun bytes(samples: ShortArray, rate: Int, channels: Int): ByteArray {
        val data = samples.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(channels.toShort())
        bb.putInt(rate); bb.putInt(rate * channels * 2); bb.putShort((channels * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    fun stereoBytes(s: Stereo, rate: Int): ByteArray = bytes(s.interleaved16(), rate, 2)

    /** Reads a 16-bit PCM WAV (mono or stereo) into a Stereo signal. Returns (signal, sample rate). */
    fun read(f: File): Pair<Stereo, Int> {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(ByteArray(4).also { bb.get(it) }) == "RIFF") { "not a WAV file" }
        bb.getInt(); bb.get(ByteArray(4))
        var ch = 0; var rate = 0; var bits = 0
        while (bb.remaining() >= 8) {
            val id = String(ByteArray(4).also { bb.get(it) })
            val len = bb.getInt()
            if (id == "fmt ") {
                val fmt = bb.getShort().toInt(); ch = bb.getShort().toInt(); rate = bb.getInt()
                bb.getInt(); bb.getShort(); bits = bb.getShort().toInt()
                require(fmt == 1 && bits == 16) { "only 16-bit PCM WAV is supported" }
                bb.position(bb.position() + len - 16)
            } else if (id == "data") {
                val n = minOf(len, bb.remaining()) / (2 * ch)
                val l = FloatArray(n); val r = FloatArray(n)
                for (i in 0 until n) {
                    l[i] = bb.getShort() / 32768f
                    r[i] = if (ch == 2) bb.getShort() / 32768f else l[i]
                    for (extra in 2 until ch) bb.getShort()
                }
                return Pair(Stereo(l, r), rate)
            } else bb.position(bb.position() + len + (len and 1))
        }
        error("WAV has no data")
    }
}

class GrowingShorts {
    private val out = ByteArrayOutputStream()
    private val tmp = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
    var count = 0L; private set
    @Synchronized fun add(a: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            tmp.clear()
            val m = minOf(n - i, 8192)
            for (k in 0 until m) tmp.putShort(a[i + k])
            out.write(tmp.array(), 0, m * 2)
            i += m
        }
        count += n
    }
    @Synchronized fun toShorts(): ShortArray {
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(b.remaining() / 2) { b.getShort() }
    }
}
