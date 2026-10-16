"""Turn-and-walk scans (app Field > Scan, or `--es mode scan`): direction + blueprint test (founder 2026-10-15).

  .venv/bin/python -m lab.scan <folder with id.wav, id.json, id_played.wav, id_motion.csv, id_marks.csv, id_clock.csv> <id> [out_dir]

What it measures, per ping (one every 200 ms: short chirp at 0 ms, Warp rise at 30 ms):
- the echo profile (lab echo.profiles) on every mic, and the phone's heading at that moment (game rotation
  vector, lined up with the sound through the audio-clock pairs);
- TURN: a sonar SWEEP picture = echo strength by heading and distance (a first rough blueprint from one spot),
  and for each MARK ("pointing at the thing") the distance of the strongest echo and the heading where that
  echo is strongest -> angle error between "where the founder pointed" and "where the sound says";
- two real mics (8T): arrival-time difference of that echo between the mics vs heading;
- WALK: echogram (distance vs time) with the tape-mark taps, so walls/objects should slide by the walked distance.
Writes <id>_sweep.png, <id>_echogram.png and <id>_report.txt.
"""
import json
import sys
from pathlib import Path

import numpy as np

from . import C, dsp, echo, wavio

RATE = 48000
CYCLE = RATE * 200 // 1000
WARP_AT = RATE * 30 // 1000


def load(folder, sid):
    f = Path(folder)
    meta = json.loads((f / f"{sid}.json").read_text())
    rec, rate = wavio.read(f / f"{sid}.wav")
    played, _ = wavio.read(f / f"{sid}_played.wav")
    played = played[:, 0] if played.ndim > 1 else played
    rec = rec if rec.ndim > 1 else rec[:, None]
    mot = np.genfromtxt(f / f"{sid}_motion.csv", delimiter=",", names=True, dtype=None, encoding=None)
    marks = np.genfromtxt(f / f"{sid}_marks.csv", delimiter=",", names=True, dtype=None, encoding=None, ndmin=1)
    clk = np.loadtxt(f / f"{sid}_clock.csv", delimiter=",", skiprows=1, ndmin=2)
    return meta, rec.astype(np.float64), played.astype(np.float64), mot, marks, clk


def frame_to_ns(clk):
    """Linear fit frame -> boottime ns from the audio-clock pairs (the sensors use the same clock)."""
    a, b = np.polyfit(clk[:, 0], clk[:, 1], 1)
    return lambda fr: a * np.asarray(fr, dtype=np.float64) + b


def heading_series(mot):
    rot = mot[mot["sensor"] == "rot"]
    x, y, z, w = (np.array(rot[k], dtype=np.float64) for k in ("x", "y", "z", "w"))
    # yaw (azimuth) exactly as Android's getOrientation: atan2(R[1], R[4]) of getRotationMatrixFromVector
    yaw = np.degrees(np.arctan2(2 * (x * y - z * w), 1 - 2 * (x * x + z * z)))
    return np.array(rot["t_ns"], dtype=np.float64), np.unwrap(np.radians(yaw)) * 180 / np.pi


def pings(rec, played, n_ch):
    """Profiles per mic for both sounds, lined up on each ping's direct sound."""
    short = played[:240]
    warp = played[WARP_AT:WARP_AT + 4368]
    n = rec.shape[0] // CYCLE - 1
    starts = [k * CYCLE for k in range(n)]
    out = {}
    for c in range(n_ch):
        x = rec[:, c]
        P_w, arr_w, off = echo.profiles(x, warp, [s + WARP_AT for s in starts], RATE, max_m=4.0)
        P_s, arr_s, _ = echo.profiles(x, short, starts, RATE, max_m=1.0)
        out[c] = dict(warp=P_w, warp_at=arr_w, short=P_s, short_at=arr_s)
    return out


def analyse(folder, sid, out_dir=None):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    out_dir = Path(out_dir or folder)
    meta, rec, played, mot, marks, clk = load(folder, sid)
    n_ch = rec.shape[1] if not (rec.shape[1] == 2 and np.allclose(rec[:, 0], rec[:, 1])) else 1
    to_ns = frame_to_ns(clk)
    P = pings(rec, played, n_ch)
    w0 = P[0]["warp"]
    d0 = np.abs(w0[:, 0])
    ok = d0 > 0.3 * np.median(d0)                    # pings with no real direct sound (after Stop, dropouts) are left out
    w0 = w0[ok]; t_ping = to_ns(P[0]["warp_at"][ok])
    for c in P:
        k = min(len(ok), len(P[c]["warp"]))
        P[c]["warp"] = P[c]["warp"][:k][ok[:k]]; P[c]["warp_at"] = P[c]["warp_at"][:k][ok[:k]]
    ht, hd = heading_series(mot)
    head = np.interp(t_ping, ht, hd)
    r = echo.range_axis(w0.shape[1], RATE)
    env = np.abs(w0)                                 # per ping (no averaging: the phone moves)
    db = 20 * np.log10(env / env[:, :1] + 1e-12)
    lines = [f"# Scan {sid} ({meta['phone']}, {meta['seconds']:.0f} s, {len(t_ping)} pings, {n_ch} real mic(s))"]
    lines.append(f"Heading covered: {hd.min():.0f} to {hd.max():.0f} degrees (unwrapped).")

    # ---- sweep picture: heading x distance, ping by ping
    fig = plt.figure(figsize=(8, 8), dpi=110)
    ax = fig.add_subplot(111, projection="polar")
    m = (r >= 0.3) & (r <= 4.0)
    th = np.radians(head)
    R_, T_ = np.meshgrid(r[m], th)
    v = np.clip(db[:, m], -70, -20)
    ax.pcolormesh(T_, R_, v, shading="auto", cmap="magma")
    ax.set_theta_zero_location("N"); ax.set_theta_direction(-1); ax.set_rmax(4.0)
    ax.set_title(f"Sonar sweep {sid}\n(echo strength by heading and distance; marks = founder pointing)", fontsize=10)

    # ---- marks: where the founder pointed vs where the sound says
    mk = [] if marks.size == 0 else [(float(t), str(l)) for t, l in zip(np.atleast_1d(marks["t_ns"]), np.atleast_1d(marks["label"]))]
    turn_rows = []
    for t, lab in mk:
        h_mark = float(np.interp(t, ht, hd))
        ax.plot([np.radians(h_mark)] * 2, [0.3, 4.0], color="#3DDC97", lw=1)
        if lab != "ahead":
            continue
        k = int(np.argmin(np.abs(t_ping - t)))
        win = slice(max(0, k - 2), k + 3)
        prof = env[win].mean(0)
        mm = (r >= 0.35) & (r <= 4.0)
        b = np.flatnonzero(mm)[int(np.argmax(prof[mm] / prof[0]))]
        # heading where this distance (+/- 4 cm) echoes strongest over the whole scan
        band = (r >= r[b] - 0.04) & (r <= r[b] + 0.04)
        s = env[:, band].max(1) / env[:, 0]
        s = np.convolve(s, np.ones(5) / 5, mode="same")
        h_best = float(head[int(np.argmax(s))])
        turn_rows.append((h_mark, r[b], h_best, ((h_best - h_mark + 180) % 360) - 180))
    if turn_rows:
        lines.append("\n## TURN: pointed vs strongest echo")
        lines.append("| mark heading | echo at | strongest at heading | angle error |")
        lines.append("|---|---|---|---|")
        for h_mark, d, h_best, err in turn_rows:
            lines.append(f"| {h_mark:.0f} deg | {d:.2f} m | {h_best:.0f} deg | {err:+.0f} deg |")
        errs = np.abs([x[3] for x in turn_rows])
        lines.append(f"Median angle error {np.median(errs):.0f} deg; 90% within {np.percentile(errs, 90):.0f} deg ({len(errs)} marks).")
    fig.savefig(out_dir / f"{sid}_sweep.png", bbox_inches="tight"); plt.close(fig)

    # ---- two mics: arrival difference of the marked echo vs heading
    if n_ch == 2 and turn_rows:
        lines.append("\n## Two mics (8T): arrival-time difference at the marked distance")
        w1 = P[1]["warp"]; n = min(len(w0), len(w1))
        a0 = P[0]["warp_at"][:n]; a1 = P[1]["warp_at"][:n]
        for h_mark, d, _, _ in turn_rows:
            b = int(np.argmin(np.abs(r - d)))
            lo, hi = max(1, b - 30), min(w0.shape[1] - 2, b + 30)
            dl = []
            for j in range(n):
                p0 = lo + int(np.argmax(np.abs(w0[j, lo:hi]))); p1 = lo + int(np.argmax(np.abs(w1[j, lo:hi])))
                dl.append((a0[j] + dsp.interp_peak(np.abs(w0[j]), p0)) - (a1[j] + dsp.interp_peak(np.abs(w1[j]), p1)))
            dl = np.array(dl)
            near = np.abs(((head[:n] - h_mark + 180) % 360) - 180) < 10
            side = np.abs(((head[:n] - h_mark + 180) % 360) - 180)
            lines.append(f"- echo {d:.2f} m: delay when pointing at it {np.median(dl[near]):+.1f} samples; "
                         f"at 30-60 deg off {np.median(dl[(side > 30) & (side < 60)]) if ((side > 30) & (side < 60)).any() else float('nan'):+.1f} samples")

    # ---- echogram (walk): distance vs time, tape-mark taps
    fig, ax = plt.subplots(figsize=(12, 5), dpi=110)
    t_s = (t_ping - t_ping[0]) / 1e9
    ax.pcolormesh(t_s, r[m], np.clip(db[:, m], -70, -20).T, shading="auto", cmap="magma")
    for t, lab in mk:
        ax.axvline((t - t_ping[0]) / 1e9, color="#3DDC97", lw=1); ax.text((t - t_ping[0]) / 1e9, 3.9, lab, color="#3DDC97", fontsize=8)
    ax2 = ax.twinx(); ax2.plot(t_s, head, color="#4D8DFF", lw=1); ax2.set_ylabel("heading (deg)", color="#4D8DFF")
    ax.set_xlabel("time (s)"); ax.set_ylabel("distance (m)"); ax.set_title(f"Echogram {sid} (green = MARK taps, blue = heading)")
    fig.savefig(out_dir / f"{sid}_echogram.png", bbox_inches="tight"); plt.close(fig)

    rep = "\n".join(lines) + "\n"
    (out_dir / f"{sid}_report.txt").write_text(rep)
    return rep


if __name__ == "__main__":
    print(analyse(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None))
