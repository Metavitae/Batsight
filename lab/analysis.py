"""Task 1 and 2 analyses: highest pitch, left/right speakers, play-to-heard delay."""
import numpy as np

from . import C, dsp


def mono(x):
    x = np.asarray(x, dtype=np.float64)
    return x.mean(axis=1) if x.ndim == 2 else x


def bursts(col, rate, gap_ms=1.0):
    """Start and end frames of each sound in one played channel (silence of gap_ms or more splits them)."""
    nz = np.nonzero(np.abs(np.asarray(col)) > 1e-6)[0]
    if len(nz) == 0:
        return []
    cut = np.nonzero(np.diff(nz) > gap_ms / 1000 * rate)[0]
    st = np.r_[nz[0], nz[cut + 1]]
    en = np.r_[nz[cut], nz[-1]] + 1
    return list(zip(st.tolist(), en.tolist()))


def ping_train(rec, col, rate, lo=0, hi=None):
    """Find the played sounds of one channel in the recording, ALL together.
    The template is ONE sound (never the whole file: a file of repeated pings also lines up with
    itself one ping off, which once gave arrivals 300-600 ms too early on the real 8T). Every sound
    must appear at its own spacing; the best common offset wins.
    lo/hi limit where the FILE START may sit in the recording (frames).
    Returns dict(offset = recording frame where the played file's frame 0 lands, peaks per sound,
    noise, snr_db = typical sound vs noise, heard = how many sounds clearly stand out), or None."""
    b = bursts(col, rate)
    if not b:
        return None
    s0, e0 = b[0]
    ref = np.asarray(col[s0:e0], dtype=np.float64)
    env = np.abs(dsp.matched(mono(rec), ref))
    d = np.array([s for s, _ in b]) - s0
    hi = len(env) - d[-1] - s0 if hi is None else min(hi, len(env) - d[-1] - s0)
    lo = max(0, lo)
    if hi <= lo:
        return None
    ks = np.arange(lo, hi) + s0                       # candidate first-sound arrivals
    comb = np.zeros(len(ks))
    for di in d:
        comb += env[ks + di]
    k = int(ks[int(np.argmax(comb))])
    tol = max(1, int(0.001 * rate))
    peaks, at = [], []
    for di in d:
        a, z = max(0, k + di - tol), min(len(env), k + di + tol + 1)
        j = a + int(np.argmax(env[a:z]))
        peaks.append(float(env[j]))
        at.append(dsp.interp_peak(env, j))
    mask = np.ones(len(env), dtype=bool)
    w = max(len(ref), int(0.02 * rate))
    for di in d:
        mask[max(0, k + di - w):k + di + w] = False
    noise = float(np.sqrt(np.mean(env[mask] ** 2))) + 1e-20 if mask.any() else 1e-20
    peaks = np.array(peaks)
    return {"offset": float(at[0] - s0), "arrivals": at, "peaks": peaks.tolist(), "noise": noise,
            "snr_db": float(dsp.db(np.median(peaks) / noise)), "heard": int((peaks > 4 * noise).sum()),
            "count": len(d)}


def direct_delay(rec, played, rate=48000):
    """Recording frame where the played file's frame 0 lands (direct path), or None."""
    pl = np.asarray(played, dtype=np.float64)
    col = pl[:, int(np.argmax(np.abs(pl).max(axis=0)))] if pl.ndim == 2 else pl
    t = ping_train(rec, col, rate)
    return None if t is None else t["offset"]


def sweep_response(rec, rate, f0, f1, dur_s, start_frame, pre_frames, nfft=2048, hop=256):
    """Follow a curved (log) sweep through the recording. For each moment, compare the level at the
    pitch being played with the room's own level at that pitch before the sweep started.
    Returns rows of (pitch Hz, level dB, room-noise dB, signal-to-noise dB)."""
    x = mono(rec)
    freqs, times, P = dsp.stft_power(x, rate, nfft, hop)
    pre = times < (pre_frames - nfft) / rate
    if pre.sum() < 3:
        raise ValueError("need at least ~0.1 s of recording before the sweep to measure room noise")
    noise = np.median(P[pre], axis=0)
    t0 = start_frame / rate
    binw = rate / nfft
    rows = []
    for ti, t in enumerate(times):
        tau = t - t0
        if tau < 0.02 * dur_s or tau > 0.98 * dur_s:
            continue
        f = f0 * (f1 / f0) ** (tau / dur_s)
        b = int(round(f / binw))
        if b + 2 > len(freqs):
            break
        sig = P[ti, b - 1:b + 2].max()
        nz = noise[b - 1:b + 2].max()
        rows.append((f, 10 * np.log10(sig + 1e-30), 10 * np.log10(nz + 1e-30), 10 * np.log10((sig + 1e-30) / (nz + 1e-30))))
    return np.array(rows)


def top_frequency(rows, snr_db=10.0, within_db=20.0, gap_hz=300.0):
    """Two answers, both walking upward and stopping at the first gap wider than gap_hz:
    heard_hz   highest pitch still at least snr_db above the room's own noise at that pitch;
    usable_hz  highest pitch no more than within_db weaker than the strongest part of the sweep.
    The first is 'does anything get through', the second is 'is it strong enough to work with'."""
    if len(rows) == 0:
        return {"heard_hz": None, "usable_hz": None}
    f, level, snr = rows[:, 0], rows[:, 1], rows[:, 3]
    k = 5
    sm = lambda v: np.convolve(v, np.ones(k) / k, mode="same")
    snr_s, lvl_s = sm(snr), sm(level)

    def walk(ok):
        top, last = None, None
        for fi, o in zip(f, ok):
            if o:
                top = last = fi
            elif last is not None and fi - last > gap_hz:
                break
        return float(top) if top is not None else None
    heard = snr_s >= snr_db
    usable = heard & (lvl_s >= np.max(lvl_s[k:-k] if len(lvl_s) > 2 * k else lvl_s) - within_db)
    return {"heard_hz": walk(heard), "usable_hz": walk(usable)}


BANDS = [(2000, 4000), (4000, 8000), (8000, 12000), (12000, 16000), (16000, 20000), (20000, 23500)]


def segment_levels(rec, rate, at_frame, length, bands=BANDS):
    """Level (dB) per band of each recorded channel in a window."""
    x = np.asarray(rec, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    seg = x[max(0, at_frame):at_frame + length]
    return [[dsp.band_level_db(seg[:, c], rate, (lo + hi) / 2, (hi - lo) / 2) for lo, hi in bands] for c in range(x.shape[1])]


def left_right(rec, played, rate):
    """Task 1 item 4. The played file has left-only chirps first, then right-only chirps.
    Finds each group's direct arrival and compares levels and arrival times. arrival_frame = where
    the played file's frame 0 lands, judged by that side's chirps (equal if both paths are equal)."""
    rec = np.asarray(rec, dtype=np.float64)
    out = {}
    bl, br = bursts(played[:, 0], rate), bursts(played[:, 1], rate)
    if not bl or not br:
        return out
    # left chirps must not be mistaken for right ones (same chirp): limit each search
    L = ping_train(rec, played[:, 0], rate, hi=br[0][0] - bl[0][0])
    if L is None:
        return out
    win = int(0.05 * rate)
    R = ping_train(rec, played[:, 1], rate, lo=int(L["offset"]) - win, hi=int(L["offset"]) + win)
    for name, t, b in (("left", L, bl), ("right", R, br)):
        if t is None:
            continue
        lv = segment_levels(rec, rate, int(t["offset"]) + b[0][0], int(0.05 * rate))
        out[name] = {"arrival_frame": t["offset"], "peak": float(np.median(t["peaks"])), "levels_db": lv,
                     "snr_db": t["snr_db"], "heard": t["heard"], "count": t["count"]}
    if "left" in out and "right" in out:
        a, b = out["left"], out["right"]
        out["peak_difference_db"] = float(20 * np.log10((a["peak"] + 1e-20) / (b["peak"] + 1e-20)))
    return out


def latency_report(meta, arrival_frame):
    """Task 1 item 7. Two views of 'play to heard':
    software: from the moment the app pressed play to when the mic heard it;
    timestamps: from when the phone says the first sample left the speaker to when the mic heard it
    (this is close to the acoustic path itself if the phone's timestamps are honest)."""
    rate = meta["rate"]
    rep = {}
    if arrival_frame is None:
        return rep
    rep["software_ms"] = (arrival_frame - meta["frames_at_play_call"]) / rate * 1000
    if meta.get("track_ts_ok") and meta.get("rec_ts_ok"):
        t_play0 = meta["track_ts_ns"] - meta["track_ts_frame"] / rate * 1e9
        rec_frame_at = meta["rec_ts_frame"] + (t_play0 - meta["rec_ts_ns"]) * rate / 1e9
        rep["timestamp_ms"] = (arrival_frame - rec_frame_at) / rate * 1000
        rep["timestamp_as_distance_m"] = rep["timestamp_ms"] / 1000 * C
    return rep
