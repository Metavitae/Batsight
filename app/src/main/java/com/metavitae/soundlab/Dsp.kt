package com.metavitae.soundlab

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-phone copy of the lab's echo maths (lab/dsp.py, lab/echo.py) so the field test works without the
 * computer. Same ideas, same numbers: pulse compression with a tapered template, profiles lined up on the
 * direct sound (phase too), echo floor = max(6x ping-to-ping noise, the sound's own fading tail + 10 dB).
 */
object Dsp {
    const val C = 343.0

    /** In-place complex FFT (radix 2). inverse=true also divides by n. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val inv = 1.0 / n; for (i in 0 until n) { re[i] = re[i] * inv; im[i] = im[i] * inv } }
    }

    fun nfft(n: Int): Int { var k = 1; while (k < n) k = k shl 1; return k }

    /** Complex (analytic) matched filter of x with the Hann-tapered template, like lab dsp.matched.
     *  Index k = "the sound started k samples into x". Returns (re, im) of length x.size. */
    fun matched(x: DoubleArray, ref: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val r = DoubleArray(ref.size) { ref[it] * 0.5 * (1 - cos(2 * PI * it / max(1, ref.size - 1))) }
        val energy = r.sumOf { it * it } + 1e-20
        val n = nfft(x.size + r.size)
        val xr = DoubleArray(n); val xi = DoubleArray(n)
        System.arraycopy(x, 0, xr, 0, x.size)
        val rr = DoubleArray(n); val ri = DoubleArray(n)
        System.arraycopy(r, 0, rr, 0, r.size)
        fft(xr, xi); fft(rr, ri)
        // X * conj(R), then keep positive frequencies x2 (analytic signal) in the same pass
        for (k in 0 until n) {
            val a = xr[k] * rr[k] + xi[k] * ri[k]
            val b = xi[k] * rr[k] - xr[k] * ri[k]
            val h = when { k == 0 || k == n / 2 -> 1.0; k < n / 2 -> 2.0; else -> 0.0 }
            xr[k] = a * h / energy; xi[k] = b * h / energy
        }
        fft(xr, xi, inverse = true)
        return Pair(xr.copyOf(x.size), xi.copyOf(x.size))
    }

    fun env(re: DoubleArray, im: DoubleArray) = DoubleArray(re.size) { hypot(re[it], im[it]) }

    fun interpPeak(y: DoubleArray, i: Int): Double {
        if (i <= 0 || i >= y.size - 1) return i.toDouble()
        val a = y[i - 1]; val b = y[i]; val c = y[i + 1]; val d = a - 2 * b + c
        return if (d != 0.0) i + 0.5 * (a - c) / d else i.toDouble()
    }

    /** First peak reaching rel x the highest one in [start, stop): the direct sound arrives first. */
    fun firstArrival(env: DoubleArray, start: Int, stop: Int, rel: Double = 0.5): Int? {
        val lo = start.coerceIn(0, env.size); val hi = stop.coerceIn(lo, env.size)
        if (hi <= lo) return null
        var mx = 0.0
        for (i in lo until hi) mx = max(mx, env[i])
        if (mx <= 0) return null
        var i = lo
        while (i < hi && env[i] < rel * mx) i++
        if (i >= hi) return null
        while (i + 1 < hi && env[i + 1] >= env[i]) i++
        return i
    }

    fun rangeM(bin: Int, rate: Int) = bin.toDouble() / rate * C / 2
    fun bins(m: Double, rate: Int) = kotlin.math.ceil(2 * m / C * rate).toInt()

    /** Complex profiles, one per ping, each starting at that ping's direct arrival and turned so the
     *  direct sound has phase 0 (lab echo.profiles). starts = ping starts in the PLAYED sound;
     *  offset = arrival - starts[0] found once. */
    class Profiles(val re: Array<DoubleArray>, val im: Array<DoubleArray>, val arrivals: IntArray)

    fun profiles(mre: DoubleArray, mim: DoubleArray, starts: List<Int>, offset: Int, nBins: Int, tolBins: Int): Profiles {
        val e = env(mre, mim)
        val outR = ArrayList<DoubleArray>(); val outI = ArrayList<DoubleArray>(); val arr = ArrayList<Int>()
        for (s in starts) {
            val g = s + offset
            val lo = max(0, g - tolBins); val hi = min(e.size, g + tolBins + 1)
            if (lo >= hi) break
            var k = lo
            for (i in lo until hi) if (e[i] > e[k]) k = i
            if (k + nBins > mre.size) break
            val pr = mre[k]; val pi = mim[k]; val pm = hypot(pr, pi) + 1e-20
            val cr = pr / pm; val ci = -pi / pm             // multiply by conj(direct)/|direct|
            outR.add(DoubleArray(nBins) { mre[k + it] * cr - mim[k + it] * ci })
            outI.add(DoubleArray(nBins) { mre[k + it] * ci + mim[k + it] * cr })
            arr.add(k)
        }
        return Profiles(outR.toTypedArray(), outI.toTypedArray(), arr.toIntArray())
    }

    /** |complex mean| per bin. */
    fun meanEnv(p: Profiles): DoubleArray {
        val n = p.re.size; val b = p.re[0].size
        return DoubleArray(b) { k ->
            var sr = 0.0; var si = 0.0
            for (j in 0 until n) { sr += p.re[j][k]; si += p.im[j][k] }
            hypot(sr / n, si / n)
        }
    }

    /** Ping-to-ping spread per bin divided by sqrt(n) (lab noise_level / sqrt(len)). */
    fun noiseOfMean(p: Profiles): DoubleArray {
        val n = p.re.size; val b = p.re[0].size
        val out = DoubleArray(b) { k ->
            var mr = 0.0; var mi = 0.0
            for (j in 0 until n) { mr += p.re[j][k]; mi += p.im[j][k] }
            mr /= n; mi /= n
            var s = 0.0
            for (j in 0 until n) { val dr = p.re[j][k] - mr; val di = p.im[j][k] - mi; s += dr * dr + di * di }
            sqrt(s / n)
        }
        val med = out.sorted()[b / 2]
        for (k in 0 until b) out[k] = max(out[k], 0.5 * med) / sqrt(n.toDouble()) + 1e-20
        return out
    }

    /** The played sound's own fading tail after pulse compression, scaled to the direct peak (lab direct_tail). */
    fun directTail(direct: Double, ref: DoubleArray, nBins: Int): DoubleArray {
        val x = DoubleArray(ref.size + nBins); System.arraycopy(ref, 0, x, 0, ref.size)
        val (re, im) = matched(x, ref)
        val a = env(re, im)
        var k = 0
        for (i in a.indices) if (a[i] > a[k]) k = i
        val tail = DoubleArray(nBins) { if (k + it < a.size) a[k + it] / (a[k] + 1e-20) else 0.0 }
        for (i in nBins - 2 downTo 0) tail[i] = max(tail[i], tail[i + 1])
        return DoubleArray(nBins) { direct * tail[it] }
    }

    /** Level an echo must beat at each distance (lab echo_floor without a calibration file). */
    fun floor(p: Profiles, ref: DoubleArray, marginDb: Double = 10.0): Pair<DoubleArray, DoubleArray> {
        val e = meanEnv(p)
        val noise = noiseOfMean(p)
        val tail = directTail(e[0], ref, e.size)
        val m = Math.pow(10.0, marginDb / 20)
        return Pair(e, DoubleArray(e.size) { max(6 * noise[it], tail[it] * m) })
    }

    class Peak(val m: Double, val strength: Double, val bin: Int)

    /** Echo peaks above the floor between lo and hi metres, strongest first (lab peaks_list). */
    fun peaks(e: DoubleArray, fl: DoubleArray, rate: Int, loM: Double, hiM: Double, minSepM: Double = 0.05, top: Int = 6): List<Peak> {
        val c = ArrayList<Peak>()
        for (i in 1 until e.size - 1) {
            val r = rangeM(i, rate)
            if (r < loM || r > hiM) continue
            if (e[i] >= e[i - 1] && e[i] > e[i + 1] && e[i] > fl[i]) c.add(Peak(rangeM(1, rate) * interpPeak(e, i), e[i] / fl[i], i))
        }
        c.sortByDescending { it.strength }
        val keep = ArrayList<Peak>()
        for (p in c) if (keep.all { kotlin.math.abs(it.m - p.m) > minSepM }) keep.add(p)
        return keep.take(top)
    }

    /** End of the blind zone: from the direct sound until the envelope stays under the floor (at least blankM). */
    fun blindEndM(e: DoubleArray, noise6: DoubleArray, rate: Int, blankM: Double = 0.15): Double {
        var k = 0
        while (k < e.size && e[k] > noise6[k]) k++
        return max(blankM, rangeM(k, rate))
    }
}
