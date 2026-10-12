"""Signal recipes: the SAME text format as the phone app's Signals.kt.

Parts are joined by "+" and mixed; each part is "kind,key=value,...".
  kind  tone | chirp | sweep | click | silence
  f     tone pitch (Hz)            f0,f1 start/end pitch (chirp = straight line, sweep = curved/log)
  ms    length of one sound        db    dB below full scale (default -30 = quiet)
  ch    L | R | LR (default LR)    at    start time in ms
  count repeats                    every ms from one start to the next (default ms + 200)
  gaps  "150;230;170" uneven start-to-start ms, cycled (off-beat); overrides every
  fade  ms of smooth fade in/out (default 10% of ms, at most 5 ms)
"""
import math

import numpy as np


def parse(spec):
    parts = []
    for raw in spec.split("+"):
        bits = [b.strip() for b in raw.strip().split(",")]
        vals = {}
        for b in bits[1:]:
            k, _, v = b.partition("=")
            vals[k.strip().lower()] = v.strip()
        parts.append((bits[0].lower(), vals))
    return parts


def _d(v, k, default):
    return float(v[k]) if k in v and v[k] != "" else default


def one(kind, v, rate):
    """One sound, mono, no placement."""
    ms = _d(v, "ms", 0.3 if kind == "click" else 10.0)
    n = max(1, int(round(ms / 1000 * rate)))
    a = 10 ** (_d(v, "db", -30.0) / 20)
    t = np.arange(n) / rate
    T = n / rate
    if kind == "tone":
        x = a * np.sin(2 * np.pi * _d(v, "f", 1000.0) * t)
    elif kind == "chirp":
        f0, f1 = _d(v, "f0", 17000.0), _d(v, "f1", 19000.0)
        x = a * np.sin(2 * np.pi * (f0 * t + (f1 - f0) * t * t / (2 * T)))
    elif kind == "sweep":
        f0, f1 = _d(v, "f0", 15000.0), _d(v, "f1", 24000.0)
        if f0 == f1:
            x = a * np.sin(2 * np.pi * f0 * t)
        else:
            L = T / math.log(f1 / f0)
            x = a * np.sin(2 * np.pi * f0 * L * (np.exp(t / L) - 1))
    elif kind == "click":
        x = a * 0.5 * (1 - np.cos(2 * np.pi * (np.arange(n) + 0.5) / n))
    elif kind == "silence":
        x = np.zeros(n)
    else:
        raise ValueError(f"unknown sound kind {kind!r}")
    if kind in ("tone", "chirp", "sweep"):
        fade = int(round(_d(v, "fade", min(ms * 0.1, 5.0)) / 1000 * rate))
        fade = max(0, min(fade, n // 2))
        if fade:
            w = 0.5 * (1 - np.cos(np.pi * (np.arange(fade) + 0.5) / fade))
            x[:fade] *= w
            x[n - fade:] *= w[::-1]
    return x.astype(np.float32)


def starts(kind, v, rate):
    ms = _d(v, "ms", 0.3 if kind == "click" else 10.0)
    t = _d(v, "at", 0.0)
    count = int(_d(v, "count", 1))
    gaps = [float(g) for g in v.get("gaps", "").split(";") if g.strip()]
    every = _d(v, "every", ms + 200.0)
    out = []
    for c in range(count):
        out.append(int(round(t / 1000 * rate)))
        t += gaps[c % len(gaps)] if gaps else every
    return out


def build(spec, rate=48000):
    """Returns float32 [frames, 2] (left, right)."""
    placed = []
    for kind, v in parse(spec):
        snd = one(kind, v, rate)
        ch = v.get("ch", "LR").upper()
        for s in starts(kind, v, rate):
            placed.append((s, snd, "L" in ch, "R" in ch))
    total = max((s + len(d) for s, d, _, _ in placed), default=0)
    out = np.zeros((total, 2), dtype=np.float32)
    for s, d, l, r in placed:
        if l:
            out[s:s + len(d), 0] += d
        if r:
            out[s:s + len(d), 1] += d
    return out


def reference(spec, rate=48000, part=0):
    """The single sound of one part, used as the matched-filter template."""
    kind, v = parse(spec)[part]
    return one(kind, v, rate)


def ping_starts(spec, rate=48000, part=0):
    kind, v = parse(spec)[part]
    return starts(kind, v, rate)
