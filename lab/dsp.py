"""Basic signal tools built on numpy FFTs."""
import numpy as np


def _nfft(n):
    return 1 << int(np.ceil(np.log2(max(n, 1))))


def analytic(x):
    """Analytic signal (complex); its magnitude is the smooth envelope."""
    n = len(x)
    N = _nfft(n)
    X = np.fft.fft(x, N)
    h = np.zeros(N)
    h[0] = 1
    h[1:N // 2] = 2
    h[N // 2] = 1
    return np.fft.ifft(X * h)[:n]


def matched(x, ref, taper=True):
    """Pulse compression: correlate the recording with the played sound (same as convolving with a
    time-reversed copy). Output index k = 'the sound started k samples into x'. Complex (analytic).
    taper: weight the template with a smooth window. A plain chirp leaves ripples ('sidelobes') up to
    one chirp-length before and after every echo, which look like fake objects; the taper trades a
    slightly wider peak for much lower ripples (standard radar practice)."""
    ref = np.asarray(ref, dtype=np.float64)
    if taper:
        ref = ref * np.hanning(len(ref))
    n = len(x) + len(ref)
    N = _nfft(n)
    c = np.fft.ifft(np.fft.fft(x, N) * np.conj(np.fft.fft(ref, N)))
    c = c[:len(x)].real
    return analytic(c) / (np.sum(ref.astype(np.float64) ** 2) + 1e-20)


def bandpass(x, rate, lo, hi, edge=200.0):
    """Keep lo..hi Hz with soft edges (FFT mask)."""
    n = len(x)
    N = _nfft(n)
    X = np.fft.rfft(x, N)
    f = np.fft.rfftfreq(N, 1 / rate)
    m = np.clip((f - (lo - edge)) / edge, 0, 1) * np.clip(((hi + edge) - f) / edge, 0, 1)
    return np.fft.irfft(X * m, N)[:n]


def interp_peak(y, i):
    """Sub-sample peak position by fitting a parabola to the 3 points around i."""
    if 0 < i < len(y) - 1:
        a, b, c = y[i - 1], y[i], y[i + 1]
        d = a - 2 * b + c
        if d != 0:
            return i + 0.5 * (a - c) / d
    return float(i)


def first_arrival(env, rel=0.5, start=0, stop=None):
    """First peak whose height reaches rel * the highest peak. The direct sound arrives first, so this
    is safer than 'the loudest peak' (a strong wall echo can be louder than the direct path)."""
    seg = env[start:stop]
    if len(seg) == 0:
        return None
    thr = rel * seg.max()
    above = np.nonzero(seg >= thr)[0]
    if len(above) == 0:
        return None
    i = above[0]
    while i + 1 < len(seg) and seg[i + 1] >= seg[i]:
        i += 1
    return start + interp_peak(seg, i)


def db(x, floor=1e-12):
    return 20 * np.log10(np.maximum(np.abs(x), floor))


def rms(x):
    x = np.asarray(x, dtype=np.float64)
    return float(np.sqrt(np.mean(x * x))) if len(x) else 0.0


def stft_power(x, rate, nfft=2048, hop=512):
    """Returns (freqs, times, power[time, freq])."""
    w = np.hanning(nfft)
    frames = max(0, 1 + (len(x) - nfft) // hop)
    if frames == 0:
        return np.fft.rfftfreq(nfft, 1 / rate), np.zeros(0), np.zeros((0, nfft // 2 + 1))
    idx = np.arange(nfft)[None, :] + hop * np.arange(frames)[:, None]
    P = np.abs(np.fft.rfft(x[idx] * w, axis=1)) ** 2
    return np.fft.rfftfreq(nfft, 1 / rate), (np.arange(frames) * hop + nfft / 2) / rate, P


def band_level_db(x, rate, f, half_width=150.0):
    """Average level (dB) of x within f +/- half_width Hz."""
    N = _nfft(len(x))
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)), N)) ** 2
    fr = np.fft.rfftfreq(N, 1 / rate)
    m = (fr >= f - half_width) & (fr <= f + half_width)
    return float(10 * np.log10(X[m].mean() + 1e-30))
