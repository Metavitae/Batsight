"""Echo tools: ping profiles, room baseline, 'what changed', three states, averaging, motion, decay.

A "profile" is the pulse-compressed recording just after one ping, lined up so index 0 is the moment the
direct sound (speaker straight to mic) arrived. Index k then means "echo from about k / rate * C / 2 m away".
Profiles are complex so that a still room cancels EXACTLY when the baseline is subtracted.
"""
import numpy as np

from . import C, dsp

ECHO, PROBABLE, UNKNOWN, CLEAR = "echo", "probable", "unknown", "clear"


def range_axis(n, rate):
    return np.arange(n) / rate * C / 2


def profiles(rec, ref, starts, rate, max_m=5.0, search_ms=60.0):
    """Cut one profile per ping. `starts` are the ping start frames IN THE PLAYED FILE; the first
    ping's direct arrival is searched in the whole recording, the rest follow by the same offset
    (re-checked per ping within +/- 1 ms, because phone clocks drift a little)."""
    m = dsp.matched(rec.astype(np.float64), ref.astype(np.float64))
    env = np.abs(m)
    n = int(np.ceil(2 * max_m / C * rate))
    first = dsp.first_arrival(env, start=0, stop=int(starts[0] + search_ms / 1000 * rate + rate * 2))
    if first is None:
        raise ValueError("no ping found in the recording")
    offset = int(round(first)) - starts[0]
    out, arrivals = [], []
    tol = int(0.001 * rate)
    for s in starts:
        guess = s + offset
        lo, hi = max(0, guess - tol), min(len(env), guess + tol + 1)
        if lo >= hi:
            break
        k = lo + int(np.argmax(env[lo:hi]))
        if k + n > len(m):
            break
        arrivals.append(k)
        # Line pings up by PHASE too: cutting at whole samples leaves each ping turned by up to half
        # a sample (at 18 kHz that is a third of a wave), which on the real phones made the
        # still-room wobble as big as the room's echoes. Turn each ping so its direct sound has phase 0.
        seg = m[k:k + n]
        out.append(seg * np.conj(seg[0]) / (np.abs(seg[0]) + 1e-20))
    return np.array(out), np.array(arrivals), offset


def blank_bins(rate, blank_m):
    """Bins too close to trust: the speaker sits next to the mic, so the first instant is the phone
    ringing itself, not the room."""
    return int(np.ceil(2 * blank_m / C * rate))


def blind_end(env, noise, rate, z=6.0, blank_m=0.15):
    """End of the blind zone: at least blank_m, and on until the direct sound (speaker straight into
    the mic, at index 0) and its ripples have dropped below the noise. Nothing in there can be judged."""
    b = blank_bins(rate, blank_m)
    k = 0
    while k < len(env) and env[k] > z * noise[k]:
        k += 1
    return max(b, k)


def direct_tail(env, ref, rate):
    """Expected faint 'ripple tail' of the phone's own direct sound at every distance: the played sound's
    own filter response (same smooth weighting), scaled to the measured direct peak. After processing,
    the direct sound leaves ripples as long as the sound itself (a 20 ms chirp: ~3.4 m), and small
    objects' echoes can be just as faint. Real run (8T, evening): the laptop, ashtray and mug echoed
    without a gap from 16 to 50 cm, and the old 'blind until the first quiet spot' rule hid them all."""
    ref = np.asarray(ref, dtype=np.float64)
    a = np.abs(dsp.matched(np.concatenate([ref, np.zeros(len(env))]), ref))
    k = int(np.argmax(a))
    tail = a[k:k + len(env)] / (a[k] + 1e-20)
    tail = np.pad(tail, (0, max(0, len(env) - len(tail))))
    tail = np.maximum.accumulate(tail[::-1])[::-1]       # ripples count as 'still fading'
    return env[0] * tail


def baseline(profs):
    """Room's echo signature: the average of many pings in a still room (complex average)."""
    return profs.mean(axis=0)


def residual(profs, base):
    """What changed: each ping minus the room signature, done in SOFTWARE (never by playing anti-sound)."""
    return profs - base[None, :]


def noise_level(profs):
    """Per-bin spread between pings of a still room: how big 'nothing changed' looks."""
    dev = np.abs(profs - profs.mean(axis=0)[None, :])
    n = np.sqrt((dev ** 2).mean(axis=0))
    return np.maximum(n, 0.5 * np.median(n)) + 1e-20


def _runs(hit):
    k = 0
    while k < len(hit):
        if hit[k]:
            j = k
            while j < len(hit) and hit[j]:
                j += 1
            yield k, j
            k = j
        else:
            k += 1


def real_peaks(env, hit, span_bins, sidelobe_db=20.0, min_run=2):
    """Peaks of the runs above threshold, minus ripples: a peak within one chirp-length (span_bins) of a
    peak more than sidelobe_db stronger is treated as that echo's ripple. Honest cost: a weak object
    right next to a strong one is hidden too (it stays UNKNOWN, never CLEAR).
    Returns (real peak bins, ripple mask)."""
    cands = [k + int(np.argmax(env[k:j])) for k, j in _runs(hit) if j - k >= min_run]
    cands.sort(key=lambda i: -env[i])
    real = []
    ripple = np.zeros(len(env), dtype=bool)
    ratio = 10 ** (sidelobe_db / 20)
    for i in cands:
        if any(abs(i - r) <= span_bins and env[r] > ratio * env[i] for r in real):
            continue
        real.append(i)
    for k, j in _runs(hit):
        if not any(k <= r < j for r in real):
            ripple[k:j] = True
    return sorted(real), ripple


def changed(res_env, noise, rate, z=6.0, blank_m=0.15, chirp_ms=10.0, sidelobe_db=20.0):
    """Distances (m) where the leftover clearly beats the still-room noise, with the strength ratio.
    A big leftover only means 'something changed here'; it never says 'clear'."""
    b = blank_bins(rate, blank_m)
    hit = res_env > z * noise
    hit[:b] = False
    span = int(chirp_ms / 1000 * rate)
    real, _ = real_peaks(res_env, hit, span, sidelobe_db)
    r = range_axis(len(res_env), rate)
    return [(float(r[p]), float(res_env[p] / noise[p])) for p in real]


def states_1d(env, noise, rate, z=6.0, blank_m=0.15, chirp_ms=10.0, sidelobe_db=20.0):
    """Three states along one beam, from one (averaged) profile:
    ECHO at real echoes; CLEAR only for distances IN FRONT of the first real echo where nothing at all
    rose above the noise (sound went through that space and came back from farther);
    UNKNOWN everywhere else: beyond the last echo, ripples, and the blind zone next to the phone.
    Silence alone never earns CLEAR."""
    b = blind_end(env, noise, rate, z, blank_m)
    st = np.array([UNKNOWN] * len(env), dtype=object)
    hit = env > z * noise
    hit[:b] = False
    real, ripple = real_peaks(env, hit, int(chirp_ms / 1000 * rate), sidelobe_db)
    ratio = 10 ** (sidelobe_db / 20)
    for r in real:                      # the echo itself = its main lobe (within sidelobe_db of the peak)
        lo = r
        while lo - 1 >= b and env[lo - 1] * ratio >= env[r]:
            lo -= 1
        hi = r
        while hi + 1 < len(env) and env[hi + 1] * ratio >= env[r]:
            hi += 1
        st[lo:hi + 1] = ECHO
    if real:
        first = min(k for k, j in _runs(hit) if any(k <= r < j for r in real))
        front = np.arange(b, max(b, first - 2))
        st[front[~hit[front]]] = CLEAR
    return st


def averaging_curve(profs, target_bin, noise_bins, counts=(1, 5, 20, 100)):
    """Task 5e: how much sharper does the reading get after 1, 5, 20, 100 pings (coherent average)?
    Returns {N: signal-to-noise in dB}."""
    out = {}
    for n in counts:
        if n > len(profs):
            break
        avg = profs[:n].mean(axis=0)
        sig = np.abs(avg[target_bin])
        noise = np.sqrt(np.mean(np.abs(avg[noise_bins]) ** 2)) + 1e-20
        out[n] = float(20 * np.log10(sig / noise + 1e-20))
    return out


def averaging_gain(profs, lo_bin, hi_bin, counts=(1, 5, 20, 50)):
    """Task 5e, honest version (real phones): noise = how much two INDEPENDENT averages of N pings
    disagree (a room's real echoes are the same in both, so they don't count as noise).
    signal = the strongest echo of the full average in lo..hi. Returns {N: dB}. 100 pings can't be
    split into two independent 100-averages, so the last value (N=50) is the honest maximum."""
    full = np.abs(profs.mean(axis=0))[lo_bin:hi_bin]
    sig = full.max()
    out = {}
    for n in counts:
        if 2 * n > len(profs):
            break
        a, b = profs[:n].mean(axis=0), profs[n:2 * n].mean(axis=0)
        noise = np.sqrt(np.mean(np.abs(a - b)[lo_bin:hi_bin] ** 2) / 2) + 1e-20
        out[n] = float(20 * np.log10(sig / noise))
    return out


def motion_flags(profs, still_noise, z=6.0, blank=0):
    """Task 5e: does something move? Compare each ping with the previous one; a change far above
    the still-room noise means 'moving' -> restart the average instead of drawing a false picture."""
    flags = [False]
    for i in range(1, len(profs)):
        d = np.abs(profs[i, blank:] - profs[i - 1, blank:])
        flags.append(bool((d > z * still_noise[blank:]).sum() >= 3))
    return np.array(flags)


def average_with_restart(profs, flags):
    """Average pings, starting over whenever motion is flagged. Returns (average, pings used)."""
    start = 0
    for i, f in enumerate(flags):
        if f:
            start = i
    sel = profs[start:]
    return sel.mean(axis=0), len(sel)


def decay_time(env, rate, start_bin=0, smooth_ms=5.0):
    """Task 5d: how fast the room's sound dies away. Schroeder backward integration of the squared
    pulse-compressed response, straight-line fit from -5 dB to -25 dB, extended to -60 dB (RT60).
    Returns seconds, or None if the response is too short or too noisy."""
    e = np.abs(env[start_bin:]) ** 2
    if len(e) < 10:
        return None
    w = max(1, int(smooth_ms / 1000 * rate))
    es = np.convolve(e, np.ones(w) / w, "same")
    # stop where the room's sound has sunk into the background noise (measured from the last 30%);
    # counting that noise as room sound made real-phone answers jump from 0.2 s to 1.4 s
    noise = np.median(es[int(0.7 * len(es)):])
    below = np.nonzero(es[w:] < 2 * noise)[0]
    cut = w + int(below[0]) if len(below) else len(es)
    e = np.maximum(e[:cut] - noise, 0)
    if len(e) < 10 or e.sum() <= 0:
        return None
    sch = np.cumsum(e[::-1])[::-1]
    sch_db = 10 * np.log10(sch / sch[0] + 1e-30)
    i5 = np.nonzero(sch_db <= -5)[0]
    i25 = np.nonzero(sch_db <= -25)[0]
    if len(i25) == 0:                       # quiet room sound: fit -5..-15 dB instead (T10 style)
        i25 = np.nonzero(sch_db <= -15)[0]
    if len(i5) == 0 or len(i25) == 0 or i25[0] <= i5[0] + 2:
        return None
    k = np.arange(i5[0], i25[0])
    slope = np.polyfit(k / rate, sch_db[k], 1)[0]
    return float(-60 / slope) if slope < 0 else None



def watch(profs, rate, every_s, lo_m=0.3, hi_m=4.0, fast_n=4, slow_s=15.0, z=4.0, min_bins=20, still_n=20,
          settle_s=3.0, learn_s=30.0, margin=4.0):
    """Rhythmic 'watch' mode (founder's idea). Three states per chirp:
    MOVING  - the 'now' picture keeps changing from moment to moment (someone walking);
    CHANGED - different from 'the room as usual' but steady (a door left open, a chair moved);
              after settle_s of steady change it is ADOPTED as the new usual (first real run: a door
              opened at ~2.8 m stayed 'moving' for 45 s because the old design never accepted it);
    still   - nothing differs; 'the room as usual' keeps updating slowly.
    Returns per chirp: (state, distance of biggest difference in m, number of changed distances)."""
    r = range_axis(profs.shape[1], rate)
    m = (r >= lo_m) & (r <= hi_m)
    slow = profs[:still_n].mean(axis=0)
    noise = np.sqrt((np.abs(profs[:still_n] - slow) ** 2).mean(axis=0)) + 1e-20
    alpha = every_s / slow_s
    settle_n = int(round(settle_s / every_s))
    unit = noise[m] / np.sqrt(fast_n)
    # The room's normal background wobble, learned spot by spot and kept up to date (real run: with
    # the AC on and a door ajar, the spots near the door wobbled all the time and raised ~20 false
    # alarms a minute). A running MEDIAN over ~learn_s, so a person walking for a few seconds doesn't
    # get learned as 'normal', but a fan or a draught does.
    learn_n = max(10, int(round(learn_s / every_s)))
    hist_mv, hist_ch = [], []
    out, steady = [], 0
    for i in range(len(profs)):
        if i < 2 * fast_n:
            out.append(("still", None, 0))
            continue
        fast = profs[i - fast_n + 1:i + 1].mean(axis=0)
        prev = profs[i - 2 * fast_n + 1:i - fast_n + 1].mean(axis=0)
        raw_mv = np.abs(fast - prev)[m]
        raw_ch = np.abs(fast - slow)[m]
        hist_mv.append(raw_mv); hist_ch.append(raw_ch)
        hist_mv, hist_ch = hist_mv[-learn_n:], hist_ch[-learn_n:]
        if len(hist_mv) >= 10:
            bg_mv = np.median(np.array(hist_mv), axis=0)
            bg_ch = np.median(np.array(hist_ch), axis=0)
        else:
            bg_mv = bg_ch = np.zeros(m.sum())
        # normal wobble = the larger of the still-start noise and the learned background x margin/z.
        # margin 4, min_bins 20: tuned on 3 real runs (AC on, door ajar): empty room 100% still, the
        # founder walking in and out still caught at the same moments. Tuned on few runs: re-check.
        mv = raw_mv / np.maximum(unit * np.sqrt(2), margin * bg_mv / z)
        ch = raw_ch / np.maximum(unit * np.sqrt(1 + fast_n / still_n), margin * bg_ch / z)
        n_mv, n_ch = int((mv > z).sum()), int((ch > z).sum())
        if n_mv >= min_bins:
            state, where, n, steady = "MOVING", float(r[m][int(np.argmax(mv))]), n_mv, 0
        elif n_ch >= min_bins:
            steady += 1
            state, where, n = "CHANGED", float(r[m][int(np.argmax(ch))]), n_ch
            if steady >= settle_n:                      # steady long enough: this is the new usual
                slow = profs[i - settle_n + 1:i + 1].mean(axis=0)
                state, steady = "ADOPTED", 0
        else:
            state, where, n, steady = "still", None, 0, 0
            if i >= still_n:
                slow = (1 - alpha) * slow + alpha * profs[i]
        out.append((state, where, n))
    return out
