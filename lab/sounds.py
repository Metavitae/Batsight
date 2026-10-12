"""Space-like probe sounds (founder 2026-10-12: "We're floating now! No sonar ping!").

Original sounds in the spirit of sci-fi bridges and scanners, NOT copies of any show's effects.
Every one is a sweep or an uneven warble, because a single steady pitch (the classic sonar 'ping')
blurs echo timing. All stay within 2-15 kHz (both phones can play and hear that) and the founder's
'never hurtful' rule: at most 100 ms each, peak -20 dBFS.
"""
import numpy as np

from . import C, dsp

RATE = 48000
PEAK_DB = -20.0


def _t(ms, rate):
    return np.arange(int(ms / 1000 * rate)) / rate


def _fade(x, rate, ms=4.0):
    n = min(len(x) // 2, int(ms / 1000 * rate))
    if n > 0:
        w = np.sin(np.linspace(0, np.pi / 2, n)) ** 2
        x[:n] *= w
        x[-n:] *= w[::-1]
    return x


def _sweep(f0, f1, ms, rate, log=True):
    t = _t(ms, rate)
    T = ms / 1000
    if log and f0 != f1:
        k = np.log(f1 / f0)
        phase = 2 * np.pi * f0 * T / k * (np.exp(t / T * k) - 1)
    else:
        phase = 2 * np.pi * (f0 * t + (f1 - f0) / (2 * T) * t ** 2)
    return np.sin(phase)


def _from_freq(freq, rate):
    return np.sin(2 * np.pi * np.cumsum(freq) / rate)


def _norm(x, rate):
    x = _fade(np.asarray(x, dtype=np.float64), rate)
    return x / (np.abs(x).max() + 1e-20) * 10 ** (PEAK_DB / 20)


def warp_rise(rate=RATE, shadow=0.2):
    """'Warp rise' (founder's pick): an upward whoosh 2 -> 15 kHz with a faint octave-down shadow.
    STRAIGHT sweep on purpose: with a curved (log) sweep 'an octave lower' equals 'a little earlier',
    so the shadow made a fake echo at -12 dB; straight, the false copies drop to about -35 dB."""
    a = _sweep(2000, 15000, 90, rate, log=False)
    b = _sweep(1000, 7500, 90, rate, log=False)
    env = np.linspace(0.6, 1.0, len(a))
    return _norm(env * (a + shadow * b), rate)


def warp_hyper(rate=RATE, every_ms=250, count=12):
    """Founder's choice: warp rise and hyperspace V, alternating, one every `every_ms`."""
    w, h = warp_rise(rate), hyperspace_v(rate)
    n = int(every_ms / 1000 * rate)
    x = np.zeros(n * count + len(h))
    for i in range(count):
        s = w if i % 2 == 0 else h
        x[i * n:i * n + len(s)] += s
    return x


def blaster_fall(rate=RATE):
    """'Blaster fall': a fast downward zap 15 -> 2 kHz that decays, like a beam fading out."""
    a = _sweep(15000, 2000, 70, rate)
    env = np.exp(-np.linspace(0, 2.2, len(a)))
    return _norm(env * a, rate)


def scanner_warble(rate=RATE, seed=7):
    """'Scanner warble': a sweep 3 -> 14 kHz whose pitch also wobbles UNEVENLY (a random wobble, not a
    regular one: a regular wobble makes false echo copies)."""
    t = _t(100, rate)
    base = 3000 * (14000 / 3000) ** (t / t[-1])
    rng = np.random.default_rng(seed)
    wob = np.convolve(rng.normal(size=len(t)), np.ones(240) / 240, "same")
    wob = wob / (np.abs(wob).max() + 1e-20)
    return _norm(_from_freq(base * (1 + 0.12 * wob), rate), rate)


def hyperspace_v(rate=RATE):
    """'Hyperspace V': down then up, 12 -> 3 -> 15 kHz, a swoosh in and out."""
    return _norm(np.concatenate([_sweep(12000, 3000, 45, rate), _sweep(3000, 15000, 55, rate)]), rate)


def star_sparkle(rate=RATE):
    """'Star sparkle': four tiny rising glints in different bands, at uneven moments."""
    x = np.zeros(int(0.1 * rate))
    for f0, f1, at_ms, ms, g in ((3000, 6000, 0, 18, 0.8), (9000, 14000, 21, 15, 1.0),
                                 (5000, 9000, 47, 17, 0.9), (11000, 15000, 78, 14, 0.7)):
        s = _fade(_sweep(f0, f1, ms, rate), rate, 2.0) * g
        k = int(at_ms / 1000 * rate)
        x[k:k + len(s)] += s[:len(x) - k]
    return _norm(x, rate)


def console_chirp(rate=RATE):
    """'Console chirp': a short double blip, a quick rise then a higher quick rise, like a bridge panel."""
    x = np.zeros(int(0.08 * rate))
    for f0, f1, at_ms, ms in ((2500, 8000, 0, 30), (6000, 15000, 42, 35)):
        s = _fade(_sweep(f0, f1, ms, rate), rate, 2.0)
        k = int(at_ms / 1000 * rate)
        x[k:k + len(s)] += s[:len(x) - k]
    return _norm(x, rate)


def diamond(rate=RATE, center=8000, top=14500, bottom=3000, ms=100, stereo=True):
    """'Diamond' (founder's idea: "coming and going... spreading out and bouncing back to center").
    Swells in, starts at one middle pitch, splits: LEFT speaker rises to `top` and comes back,
    RIGHT speaker falls to `bottom` and comes back; both meet again in the middle; fades out.
    Returns [frames, 2] (left, right) if stereo, else the two summed."""
    h = ms / 2
    up = np.concatenate([_sweep(center, top, h, rate), _sweep(top, center, h, rate)])
    dn = np.concatenate([_sweep(center, bottom, h, rate), _sweep(bottom, center, h, rate)])
    env = np.sin(np.linspace(0, np.pi, len(up))) ** 0.7          # swell in, fade out
    L, R = _norm(env * up, rate), _norm(env * dn, rate)
    return np.stack([L, R], axis=1) if stereo else _norm(L + R, rate)


def cross_db(a, b):
    """How much sound b looks like sound a to a's echo filter (lower = easier to tell apart)."""
    pa = np.abs(dsp.matched(np.concatenate([np.zeros(len(a)), a, np.zeros(len(a))]), a)).max()
    pb = np.abs(dsp.matched(np.concatenate([np.zeros(len(b)), b, np.zeros(len(b))]), a)).max()
    return float(dsp.db(pb / pa))


SOUNDS = {"warp_rise": warp_rise, "blaster_fall": blaster_fall, "scanner_warble": scanner_warble,
          "hyperspace_v": hyperspace_v, "star_sparkle": star_sparkle, "console_chirp": console_chirp}


def sharpness(x, rate=RATE):
    """How good a sound is for echo timing, from its own matched-filter response:
    blur_cm  - width of the main peak at half height, as echo distance (smaller = sharper);
    ghost_db - the strongest false copy outside the main peak (more negative = fewer fake objects);
    band_khz - pitch range actually covered (where the sound's energy is within 20 dB of its best)."""
    m = np.abs(dsp.matched(np.concatenate([np.zeros(len(x)), x, np.zeros(len(x))]), x))
    k = int(np.argmax(m))
    pk = m[k]
    lo = k
    while lo > 0 and m[lo - 1] >= pk / 2:
        lo -= 1
    hi = k
    while hi + 1 < len(m) and m[hi + 1] >= pk / 2:
        hi += 1
    blur_cm = (hi - lo + 1) / rate * C / 2 * 100
    # outside the main lobe: first minima on either side
    a = k
    while a > 0 and m[a - 1] < m[a]:
        a -= 1
    b = k
    while b + 1 < len(m) and m[b + 1] < m[b]:
        b += 1
    side = np.concatenate([m[max(0, k - len(x)):a], m[b + 1:k + len(x)]])
    ghost_db = float(dsp.db(side.max() / pk)) if len(side) else -99.0
    X = np.abs(np.fft.rfft(x, 8192)) ** 2
    f = np.fft.rfftfreq(8192, 1 / rate)
    on = f[X >= X.max() / 100]
    return {"blur_cm": float(blur_cm), "ghost_db": ghost_db,
            "band_khz": (float(on.min() / 1000), float(on.max() / 1000)), "ms": len(x) / rate * 1000}


def train(names, every_ms, count, rate=RATE, lead_ms=0):
    """A rhythm of probe sounds cycling through `names` (e.g. ["warp_rise", "hyperspace_v"]).
    Returns (stereo [frames, 2], {name: [start frames]}, {name: the sound itself})."""
    snd = {n: SOUNDS[n](rate) for n in names}
    n_every = int(every_ms / 1000 * rate)
    lead = int(lead_ms / 1000 * rate)
    longest = max(len(v) for v in snd.values())
    x = np.zeros(lead + n_every * count + longest)
    starts = {n: [] for n in names}
    for i in range(count):
        nm = names[i % len(names)]
        k = lead + i * n_every
        x[k:k + len(snd[nm])] += snd[nm]
        starts[nm].append(k)
    return np.stack([x, x], axis=1), starts, snd


def scanner(cycles=5, cycle_s=1.2, f_lo=2000.0, f_hi=8000.0, shape="cosine", peak_db=-30.0, rate=RATE):
    """'Scanner' (founder's idea, in the spirit of a TV car's sweeping red scanner light; original,
    not a copy of any show's sound): a CONTINUOUS glide up and down in pitch ('wooo... wooo'), swinging
    from the left speaker to the right and back in step. Long sounds are only allowed at -30 dBFS or
    quieter, so it plays quietly; it makes up for that by lasting long (echo strength grows with
    total sound energy). The glide itself is the sonar signal (like gliding-tone radar).
    shape: 'cosine' (smooth, slows at the turns) or 'triangle' (steady glide, sharp turns).
    Returns (stereo [frames, 2], one half-cycle up-glide as the echo template)."""
    T = cycle_s
    t = np.arange(int(cycles * T * rate)) / rate
    ph = (t % T) / T
    if shape == "cosine":
        f = f_lo + (f_hi - f_lo) * (1 - np.cos(2 * np.pi * ph)) / 2
    else:
        f = f_lo + (f_hi - f_lo) * (1 - np.abs(2 * ph - 1))
    x = np.sin(2 * np.pi * np.cumsum(f) / rate)
    pan = (1 - np.cos(2 * np.pi * ph)) / 2           # 0 = left, 1 = right, in step with the pitch
    L, R = x * np.sqrt(1 - pan), x * np.sqrt(pan)
    fade = np.ones(len(x)); n = int(0.2 * rate)
    fade[:n] = np.linspace(0, 1, n); fade[-n:] = np.linspace(1, 0, n)
    st = np.stack([L * fade, R * fade], axis=1)
    st = st / (np.abs(st).max() + 1e-20) * 10 ** (peak_db / 20)
    half = int(T / 2 * rate)
    k = int(T * rate)                                  # the second cycle's up-glide (past the fade-in)
    return st, x[k:k + half] * 10 ** (peak_db / 20)


def whompf(seconds=6.0, cycle_s=1.0, f_lo=300.0, f_hi=1800.0, peak_db=-30.0, rate=RATE):
    """'Whompf-whompf' character layer (founder's held question): a ~1 s back-and-forth low glide,
    swinging left-right, with NO chirp in it. Low/mid pitches only, so the sonar's echo filters
    (which listen above ~3 kHz) ignore it. Original sound in the spirit of a TV car's scanner.
    A soft swell on each pass gives the 'whompf'. Phone speakers can't do deep bass, so on the phone
    the lowest part will sound thinner than here."""
    t = np.arange(int(seconds * rate)) / rate
    ph = (t % cycle_s) / cycle_s
    f = f_lo + (f_hi - f_lo) * (1 - np.cos(2 * np.pi * ph)) / 2
    tone = np.sin(2 * np.pi * np.cumsum(f) / rate)
    tone += 0.5 * np.sin(2 * np.pi * np.cumsum(f * 0.5) / rate)        # a lower body for the 'whompf'
    swell = 0.35 + 0.65 * np.abs(np.sin(np.pi * ph * 2)) ** 1.5          # louder mid-pass, softer at the turns
    pan = (1 - np.cos(2 * np.pi * ph)) / 2
    x = tone * swell
    st = np.stack([x * np.sqrt(1 - pan), x * np.sqrt(pan)], axis=1)
    n = int(0.15 * rate); fade = np.ones(len(x)); fade[:n] = np.linspace(0, 1, n); fade[-n:] = np.linspace(1, 0, n)
    st = st * fade[:, None]
    return st / (np.abs(st).max() + 1e-20) * 10 ** (peak_db / 20)


def layered(whompf_db=-30.0, chirp_db=-30.0, seconds=6.0, every_ms=150, rate=RATE):
    """Founder's idea: the whompf on top, our real Warp/Hyper chirps underneath (kept above 3 kHz)."""
    w = whompf(seconds=seconds, peak_db=whompf_db, rate=rate)
    count = int(seconds * 1000 / every_ms) - 2
    ch, starts, snd = train(["warp_rise", "hyperspace_v"], every_ms, count, rate)
    ch = ch * 10 ** ((chirp_db - PEAK_DB) / 20)
    from . import dsp
    ch = np.stack([dsp.bandpass(ch[:, c], rate, 3000, 15000) for c in range(2)], axis=1)  # keep chirps above the whompf
    n = min(len(w), len(ch))
    return w[:n] + ch[:n], starts, snd
