"""Founder's at-home / at-work tries -> one report (founder 2026-10-15: test the app away from CC, like LightComm).

  .venv/bin/python -m lab.night pull <out_dir>          # copy every app_* / field_* file from both phones (keeps them on the phones)
  .venv/bin/python -m lab.night report <out_dir>        # write <out_dir>/report.md + pictures, per screen

Phones: 8T 192.168.1.74:5555, POCO 192.168.1.75:5555 (IPs change: adb devices).
"""
import csv
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

PHONES = {"8T": "192.168.1.74:5555", "POCO": "192.168.1.73:5555"}
DL = "/sdcard/Download/SoundLab"
FIELD = "/sdcard/Android/data/com.metavitae.soundlab/files/field"


def adb(serial, *a):
    return subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True, timeout=300).stdout


def pull(out):
    out = Path(out)
    for name, serial in PHONES.items():
        dst = out / name
        dst.mkdir(parents=True, exist_ok=True)
        for folder in (DL, FIELD):
            files = [f.strip() for f in adb(serial, "shell", f"ls {folder} 2>/dev/null").splitlines() if f.strip()]
            for f in files:
                if f.startswith(("app_", "field_")) and not (dst / f).exists():
                    adb(serial, "pull", f"{folder}/{f}", str(dst / f))
        print(name, len(list(dst.iterdir())), "files")


def _json(p):
    try:
        return json.loads(Path(p).read_text())
    except Exception:
        return None


def _csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def report(out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    out = Path(out)
    L = ["# Batsight - the founder's tries", ""]
    for phone in ("8T", "POCO"):
        d = out / phone
        if not d.exists():
            continue
        L.append(f"## {phone}")
        # ---- Focus (Screen 2)
        res = sorted(d.glob("app_focus_*_result.json")) + sorted(d.glob("field_focus_*_result.json"))
        if res:
            L += ["", "### Focus", "| time | shown | answer | tape | camera focus asked -> lens |", "|---|---|---|---|---|"]
            for r in res:
                j = _json(r) or {}
                base = r.name.replace("_result.json", "")
                a = _json(d / f"{base}_answer.json") or {}
                best = j.get("best") or {}
                shown = {"echo": f"{best.get('m', 0):.2f} m", "two": "two things", "none": "can't tell", "close": "too close"}.get(j.get("state"), j.get("state"))
                ft = a.get("focus_test") or {}
                lens = ft.get("lens_reports", ft.get("lens_reports_m"))
                cam = f"{ft.get('asked_m', 0):.2f} -> {lens if lens is None else round(lens, 3)}" if ft else ""
                ans = a.get("answer") or a.get("right") or "(none)"      # app screens save "answer", the old Field screens "right"
                L.append(f"| {base[-11:]} | {shown} | {ans}{' (picked ' + format(a['picked_m'], '.2f') + ' m)' if a.get('picked_m') else ''} | {a.get('tape_m') or ''} | {cam} |")
        # ---- Watch (Screen 4)
        for w in sorted(d.glob("app_watch_*.csv")) + sorted(d.glob("field_watch_*.csv")):
            rows = _csv(w)
            if not rows:
                continue
            st = [r.get("state") for r in rows]
            ev = [r for r in rows if r.get("state") in ("MOVING", "CHANGED", "ADOPTED")]
            L += ["", f"### Watch {w.stem}", f"{len(rows)} lines; " + ", ".join(f"{k} {st.count(k)}" for k in ("learning", "still", "MOVING", "CHANGED", "ADOPTED", "other_speaker") if st.count(k))]
            if ev:
                L.append("Events: " + "; ".join(f"{e['t_s']} s {e['state']} at {e['where_m']} m" + (f", {e['angle_deg']} deg" if e.get('angle_deg') else "") for e in ev[:40]))
        # ---- Listen / Walk (Screens 1, 3)
        for lv in sorted(d.glob("app_listen_*_live.csv")) + sorted(d.glob("app_walk_*_live.csv")):
            rows = [r for r in _csv(lv) if r.get("near_m") is not None]
            near = [(float(r["heading_deg"]), float(r["near_m"])) for r in rows if r.get("near_m")]
            heard = sum(1 for r in rows if r.get("heard") == "1")
            L += ["", f"### {lv.stem}", f"{len(rows)} pings, {heard} with own sound heard, {len(near)} with a nearest echo"]
            if near:
                ds = np.array([n[1] for n in near])
                L.append(f"Nearest echo: median {np.median(ds):.2f} m, range {ds.min():.2f}-{ds.max():.2f} m")
                if "listen" in lv.name:
                    h = np.radians([n[0] for n in near]); fig = plt.figure(figsize=(6, 6), dpi=100)
                    ax = fig.add_subplot(111, projection="polar"); ax.scatter(h, ds, s=8, c="#8FE8F2")
                    ax.set_theta_zero_location("N"); ax.set_theta_direction(-1); ax.set_rmax(3.2); ax.set_title(lv.stem, fontsize=9)
                    pic = out / f"{lv.stem}.png"; fig.savefig(pic, bbox_inches="tight"); plt.close(fig)
                    L.append(f"Picture: {pic.name} (each dot = nearest echo at the heading the phone pointed)")
        # ---- TEST button steps (app_test_*.json): which recording each step made, matched by start time
        tests = sorted(d.glob("app_test_*.json"), key=lambda p: (_json(p) or {}).get("tap_ms") or 0)
        if tests:
            import datetime as dt
            runs = {}
            for r in list(d.glob("app_watch_*.csv")) + list(d.glob("app_listen_*_live.csv")):
                stamp = r.stem.replace("_live", "").split("_")[-1]
                try:
                    runs[r] = dt.datetime.strptime(f"{dt.date.today().year}{stamp}", "%Y%m%d-%H%M%S").timestamp()
                except ValueError:
                    pass
            L += ["", "### TEST steps", "| code | step | result | ran (s) | recording |", "|---|---|---|---|---|"]
            for t in tests:
                j = _json(t) or {}
                rec, ran = "", ""
                if j.get("start_ms"):
                    s0 = j["start_ms"] / 1000
                    near = min(runs, key=lambda r: abs(runs[r] - s0), default=None)
                    if near is not None and abs(runs[near] - s0) < 5:
                        rec = near.name
                    ran = f"{(j['end_ms'] - (j.get('go_ms') or j['start_ms'])) / 1000:.0f}"
                L.append(f"| {j.get('code')} | {j.get('title') or j.get('text')} | {j.get('result')} | {ran} | {rec} |")
        # ---- Hive handshakes
        hs = sorted(d.glob("app_handshake_*_result_*.json"))
        if hs:
            L += ["", "### Handshakes (distance between the phones)"]
            for h in hs:
                j = _json(h) or {}
                L.append(f"- {h.stem[-11:]}: {j.get('me')} to {j.get('other')} = {j.get('distance_m', 0):.3f} m")
        L.append("")
    rep = "\n".join(L) + "\n"
    (out / "report.md").write_text(rep)
    print(rep)


if __name__ == "__main__":
    {"pull": pull, "report": report}[sys.argv[1]](sys.argv[2])
