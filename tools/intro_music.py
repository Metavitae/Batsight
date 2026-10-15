"""Kithe intro music (route 2, founder 2026-10-15): tuned notes + a warm bed, all grown from our two pings.

  .venv/bin/python tools/intro_music.py [--demo <dir>]

Makes, from app/src/main/assets/intro/sounds.js (the original Warp rise / Hyperspace V pings):
  assets/intro/music.js holding bed_warp + note0-3_warp (Opus):
  bed_<sound>.ogg    fixed 12.8 s bed: pad chord, slowed-Warp riser under the Batsight pings,
                                  slowed-Hyperspace "whompf" + chord bloom when the eye closes (8.725 s)
  assets/intro/note<k>_<sound>.ogg the bell note each petal rings when its ring finds it (k = discovery order)
The page plays the bed at the start and each note at its petal's echo time, so the pings stay locked to
the petals and the eye. With --demo it also writes full intro mixes (one fixed play) to listen to.

How the tones come from our sound: both pings are pure sweeps. A 1-2 ms slice of a sweep, stretched
down to the wanted pitch and repeated in overlapping grains, becomes a steady tone with a soft chorus.
Bell notes = that tone with bell partials, struck by the ping itself. The riser and whompf are the
whole pings slowed 20-40x.
"""
import base64, io, json, subprocess, sys, tempfile, wave
from pathlib import Path

import numpy as np

SR = 48000
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/intro"
END, EYE_T, BAT_FROM = 12.8, 5.2 + 3.525, 5.6
rng = np.random.default_rng(7)

def hz(note):   # "D4" -> Hz
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    return 440.0 * 2 ** ((names[note[:-1]] + 12 * (int(note[-1]) - 4)) / 12)

# Key of D. Petals ring a rising D major chord; the Batsight part sits on A sus4 (wants to resolve); the eye resolves to D.
PETAL_NOTES = ["D4", "F#4", "A4", "D5"]
PAD_KITHE = ["D3", "A3", "D4", "F#4"]
PAD_BAT = ["A2", "A3", "D4", "E4"]
PAD_EYE = ["D3", "A3", "D4", "F#4", "A4"]


def load_pings():
    s = (ASSETS / "sounds.js").read_text()
    d = json.loads(s[s.index("{"):s.rindex("}") + 1])
    out = {}
    for k, b in d.items():
        w = wave.open(io.BytesIO(base64.b64decode(b)))
        out[k] = np.frombuffer(w.readframes(w.getnframes()), "<i2") / 32768.0
    return out


def inst_freq(x):
    X = np.fft.fft(x); h = np.zeros(len(x)); h[0] = 1; h[1:(len(x) + 1) // 2] = 2
    if len(x) % 2 == 0: h[len(x) // 2] = 1
    ph = np.unwrap(np.angle(np.fft.ifft(X * h)))
    return np.gradient(ph) * SR / (2 * np.pi)


def resample(x, rate):
    t = np.arange(0, len(x) - 1, rate)
    return np.interp(t, np.arange(len(x)), x)


def analytic_phase(x):
    X = np.fft.fft(x); h = np.zeros(len(x)); h[0] = 1; h[1:(len(x) + 1) // 2] = 2
    if len(x) % 2 == 0: h[len(x) // 2] = 1
    return np.unwrap(np.angle(np.fft.ifft(X * h)))


def grain_voice(ping, f, n, fi, ph, calm):
    """One steady voice at f Hz: grains from the steadiest parts of the sweep, each picked so its wave
    lines up with the voice's running phase (grains add up instead of cancelling = in tune)."""
    out = np.zeros(n + SR)
    g = int(0.06 * SR); hop = g // 4; win = np.hanning(g)
    for start in range(0, n, hop):
        want = (2 * np.pi * f * (start + g / 2) / SR) % (2 * np.pi)
        err = np.abs(((ph[calm] - want) + np.pi) % (2 * np.pi) - np.pi)
        best = calm[np.argsort(err)[:12]]
        c = rng.choice(best)
        r = f / fi[c]
        half = g * r / 2
        lo = c - half
        if lo < 0 or c + half + 2 >= len(ping): continue
        t = lo + np.arange(g) * r
        seg = np.interp(t, np.arange(len(ping)), ping)
        out[start:start + g] += seg * win
    return out[:n]


def grain_tone(ping, f, dur, partials=((1, 1.0), (2, 0.45), (3, 0.22), (4, 0.1))):
    """Steady tone at f Hz grown from the ping, with a few harmonics and a gentle two-voice detune (+-4 cents)."""
    fi = np.convolve(inst_freq(ping), np.ones(31) / 31, mode="same")
    ph = analytic_phase(ping)
    edge = int(0.08 * len(ping))
    pos = np.arange(edge, len(ping) - edge)
    smear = np.abs(np.gradient(fi))[pos] / fi[pos] ** 2
    calm = pos[smear <= np.quantile(smear, 0.35)]
    n = int(dur * SR); out = np.zeros(n)
    for mult, amp in partials:
        for cents in (-4, 4):
            out += grain_voice(ping, f * mult * 2 ** (cents / 1200), n, fi, ph, calm) * amp
    return out / (np.max(np.abs(out)) + 1e-9)


def env(n, pts):
    """Piecewise-linear envelope over n samples from [(t_s, level), ...]."""
    t = np.arange(n) / SR
    return np.interp(t, [p[0] for p in pts], [p[1] for p in pts])


def lowpass(x, fc):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(X / np.sqrt(1 + (f / fc) ** 4), len(x))


def reverb(x, secs=2.6, mix=0.35, seed=0):
    r = np.random.default_rng(seed)
    n = int(secs * SR); t = np.arange(n) / SR
    ir = r.standard_normal(n) * np.exp(-6.9 * t / secs)
    ir = lowpass(ir, 5000); ir[:int(0.012 * SR)] = 0; ir /= np.sqrt(np.sum(ir ** 2))
    L = len(x) + n
    wet = np.fft.irfft(np.fft.rfft(x, L) * np.fft.rfft(ir, L), L)[:len(x)]
    return (1 - mix) * x + mix * wet * 3.0


def chord(ping, notes, dur, levels=None):
    out = np.zeros(int(dur * SR))
    for i, nt in enumerate(notes):
        out += grain_tone(ping, hz(nt), dur) * (levels[i] if levels else 1.0)
    return out / len(notes)


def bell(ping, f, dur=2.2):
    """Bell note struck by our ping: grain-tone partials with bell ratios, each with its own decay."""
    n = int(dur * SR); t = np.arange(n) / SR
    out = np.zeros(n)
    for ratio, amp, dec in ((1, 1.0, 1.1), (2.0, 0.35, 0.6), (3.0, 0.18, 0.35), (4.07, 0.08, 0.2)):
        tone = grain_tone(ping, f * ratio, dur, partials=((1, 1.0),))
        out += tone * amp * np.exp(-t / dec)
    out *= np.minimum(1, t / 0.004)
    strike = resample(ping, 0.5)[: int(0.15 * SR)] * 0.6        # our click, an octave down, as the mallet
    out[:len(strike)] += strike
    return out / np.max(np.abs(out))


def make_bed(name, ping):
    n = int(END * SR)
    bed = np.zeros(n + SR * 3)
    # 1. pad: Kithe chord swells in, breathes at the melt
    k = chord(ping, PAD_KITHE, 6.2, [1.0, 0.8, 0.7, 0.5])
    k = lowpass(k, 2200) * env(len(k), [(0, 0), (1.6, 0.55), (4.1, 0.75), (4.9, 0.6), (5.9, 0.0), (6.2, 0)])
    bed[:len(k)] += k
    # 2. Batsight: A sus4 pad, brighter and louder as the pings speed up
    b = chord(ping, PAD_BAT, EYE_T - 5.0 + 0.3, [1.0, 0.8, 0.7, 0.6])
    b = lowpass(b, 3500) * env(len(b), [(0, 0), (0.5, 0.7), (EYE_T - 5.0 - 0.15, 1.0), (EYE_T - 5.0, 0.0), (10, 0)])
    i = int(5.0 * SR); bed[i:i + len(b)] += b * 1.7
    # 3. riser: the Warp rise slowed 20x (about 1.8 s), placed to end on the eye; for Hyperspace V too, it is the family riser
    warp = load_pings()["warp"]
    rise = resample(warp, 1 / 22.0)
    rise = lowpass(rise, 1800) / np.max(np.abs(rise)) * env(len(rise), [(0, 0), (len(rise) / SR * 0.6, 0.35), (len(rise) / SR, 0.6)])
    i = int(EYE_T * SR) - len(rise); bed[i:i + len(rise)] += rise
    # 4. eye closes: whompf (Hyperspace V slowed 32x: dives and comes back) + the D chord blooms and fades by the end
    hyper = load_pings()["hyper"]
    wh = resample(hyper, 1 / 32.0)
    wh = lowpass(wh, 900) / np.max(np.abs(wh))
    wh *= env(len(wh), [(0, 0), (0.03, 1.0), (len(wh) / SR, 0)])
    i = int(EYE_T * SR); bed[i:i + len(wh)] += wh * 0.9
    e = chord(ping, PAD_EYE, END - EYE_T + 0.2, [1.0, 0.8, 0.8, 0.6, 0.45])
    e = lowpass(e, 3000) * env(len(e), [(0, 0), (0.05, 1.0), (1.2, 0.8), (END - EYE_T - 1.6, 0.45), (END - EYE_T, 0)])
    bed[i:i + len(e)] += e
    bed[i:i + int(2.2 * SR)] += bell(ping, hz("D5")) * 0.5
    bed = reverb(bed, seed=1)[:n]
    bed *= env(n, [(0, 1), (END - 0.25, 1), (END, 0)])
    return bed / np.max(np.abs(bed))


def to_ogg(x, path, peak_db):
    x = x / np.max(np.abs(x)) * 10 ** (peak_db / 20)
    with tempfile.NamedTemporaryFile(suffix=".wav") as f:
        write_wav(x, f.name)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f.name, "-c:a", "libopus", "-b:a", "64k", str(path)], check=True)


def write_wav(x, path):
    w = wave.open(str(path), "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(np.round(np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()); w.close()


BED_DB, NOTE_DB, PING_DB = -6.0, -9.0, -12.0   # intro levels (founder 2026-10-15: loudness rule relaxed for the intro)


def main():
    pings = load_pings()
    demo = sys.argv[sys.argv.index("--demo") + 1] if "--demo" in sys.argv else None
    for name, ping in pings.items():
        if name != "warp" and not demo:
            continue                       # founder 2026-10-15: "The warp one sounds better" -> the app ships Warp only
        bed = make_bed(name, ping)
        notes = [bell(ping, hz(nt)) for nt in PETAL_NOTES]
        if name == "warp":
            to_ogg(bed, ASSETS / f"bed_{name}.ogg", BED_DB)
            for k, nt in enumerate(notes):
                to_ogg(nt, ASSETS / f"note{k}_{name}.ogg", NOTE_DB)
        if demo:
            # one fixed play: shapes found at 1.08 + 0.83 k s, pings 0.55 s before
            mix = np.zeros(int((END + 0.5) * SR))
            mix[:int(END * SR)] += bed * 10 ** (BED_DB / 20)
            pk = ping / np.max(np.abs(ping)) * 10 ** (PING_DB / 20)
            for k in range(4):
                d = 1.08 + 0.83 * k
                for at, x in ((d - 0.55, pk), (d, resample(pk, [0.84, 0.89, 0.94, 1.0][k]) * 0.28), (d, notes[k] * 10 ** (NOTE_DB / 20))):
                    i = int(at * SR); mix[i:i + len(x)] += x[:len(mix) - i]
            out, t, g = [], EYE_T, 0.13
            while t >= BAT_FROM: out.insert(0, t); t -= g; g *= 1.22
            for j, t in enumerate(out):
                gain = 0.35 + 0.65 * (j / (len(out) - 1)) ** 1.3
                i = int(t * SR); mix[i:i + len(pk)] += pk * gain
            Path(demo).mkdir(parents=True, exist_ok=True)
            p = Path(demo) / f"Intro music demo - {'Warp rise' if name == 'warp' else 'Hyperspace V'}.wav"
            print(f"{name}: mix peak {20 * np.log10(np.max(np.abs(mix))):.1f} dBFS -> {p.name}")
            write_wav(mix / max(1.0, np.max(np.abs(mix)) / 0.89), p)
    music = {f.stem: base64.b64encode(f.read_bytes()).decode() for f in sorted(ASSETS.glob("*_warp.ogg"))}
    (ASSETS / "music.js").write_text("// Generated by tools/intro_music.py (Opus in Ogg, grown from our Warp rise ping).\nwindow.INTRO_MUSIC=" + json.dumps(music) + ";\n")
    for f in ASSETS.glob("*.ogg"):
        f.unlink()                         # only the bundled copy in music.js ships
    print("music.js", (ASSETS / "music.js").stat().st_size)


if __name__ == "__main__":
    main()
