"""A made-up turn scan: the phone turns once in 30 s; an object at 1.20 m sits at heading 90 deg and a wall at
2.50 m at 200 deg. lab.scan must find the object where it was 'marked' and draw both."""
import wave

import numpy as np

from lab import scan, wavio

R = 48000
C = 343.0


def _chirp(f0, f1, ms, db):
    n = round(ms / 1000 * R); a = 10 ** (db / 20); T = n / R; t = np.arange(n) / R
    x = a * np.sin(2 * np.pi * (f0 * t + (f1 - f0) * t * t / (2 * T)))
    fade = round(min(ms * 0.1, 5) / 1000 * R); w = 0.5 * (1 - np.cos(np.pi * (np.arange(fade) + 0.5) / fade))
    x[:fade] *= w; x[n - fade:] *= w[::-1]
    return x


def _cycle():
    with wave.open("app/src/main/assets/sonar/warp.wav") as w:
        warp = np.frombuffer(w.readframes(w.getnframes()), "<i2") / 32768
    c = np.zeros(scan.CYCLE)
    c[:240] += _chirp(2000, 20000, 5, -20)
    c[scan.WARP_AT:scan.WARP_AT + len(warp)] += warp
    return c


def test_turn_scan_finds_the_marked_object(tmp_path):
    sid = "fake_turn"
    secs = 30
    cyc = _cycle()
    n = secs * 5
    lat = 9000
    rec = np.zeros(lat + n * scan.CYCLE + R)
    rng = np.random.default_rng(3)
    t_ping = np.arange(n) * 0.2
    head = (t_ping / secs) * 360.0                       # one full turn
    for k in range(n):
        i = lat + k * scan.CYCLE
        rec[i:i + len(cyc)] += 0.5 * cyc                 # direct sound
        for d, at_h, g in ((1.20, 90.0, 0.03), (2.50, 200.0, 0.02)):
            off = ((head[k] - at_h + 180) % 360) - 180
            gain = g * np.exp(-(off / 25.0) ** 2)
            j = i + int(round(2 * d / C * R))
            rec[j:j + len(cyc)] += gain * cyc
    rec += rng.normal(0, 2e-4, len(rec))
    wavio.write(tmp_path / f"{sid}.wav", np.stack([rec, rec], 1), R)
    wavio.write(tmp_path / f"{sid}_played.wav", np.stack([cyc, cyc], 1), R)
    t0 = 1_000_000_000_000
    ns = lambda fr: t0 + fr / R * 1e9
    (tmp_path / f"{sid}_clock.csv").write_text("frame,t_ns_boottime\n" + "".join(f"{f},{int(ns(f))}\n" for f in range(0, len(rec), R // 2)))
    rows = ["sensor,t_ns,x,y,z,w"]
    for f in range(0, len(rec), R // 100):               # heading 100 times a second, as a rotation vector about the vertical
        tt = max(0.0, (f - lat) / R)
        yaw = np.radians(min(tt, secs) / secs * 360.0)
        # Android azimuth = atan2(2(xy - zw), 1 - 2(x^2 + z^2)); for a turn about z: x = y = 0 -> atan2(-2zw, 1 - 2z^2) = -angle
        rows.append(f"rot,{int(ns(f))},0,0,{np.sin(-yaw / 2)},{np.cos(-yaw / 2)}")
    (tmp_path / f"{sid}_motion.csv").write_text("\n".join(rows) + "\n")
    t_mark = int(ns(lat + (90.0 / 360.0 * secs) * R))
    (tmp_path / f"{sid}_marks.csv").write_text(f"t_ns,label\n{t_mark},ahead\n")
    (tmp_path / f"{sid}.json").write_text('{"phone":"fake","seconds":30}')
    rep = scan.analyse(tmp_path, sid)
    print(rep)
    line = [l for l in rep.splitlines() if l.startswith("| 9")][0]
    cells = [c.strip() for c in line.strip("|").split("|")]
    assert abs(float(cells[1].split()[0]) - 1.20) < 0.03          # echo distance at the mark
    assert abs(float(cells[3].split()[0].replace("+", ""))) <= 6  # strongest heading within 6 deg of the mark
    assert (tmp_path / f"{sid}_sweep.png").exists() and (tmp_path / f"{sid}_echogram.png").exists()
