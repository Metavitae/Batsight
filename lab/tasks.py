"""Run the CC brief's tests on the phones and write the results.

    .venv/bin/python -m lab.tasks <task> [--phone POCO|8T] [--other 8T] [--step NAME] [--angle DEG]

Tasks: facts, task1, task2, task3 (--step baseline|hand30|hand100), task4a, task4c,
       task5a, task5b (= task3 steps), task5c, task5d, task5e (--step still|walk), task5f (--angle),
       task5g, task5h, analyze (re-run the analysis of a saved folder: --folder)

Raw recordings go to <root>/Art/Source/<session>/ untouched; everything computed from them (copies,
remixes, pictures, report.md) goes to <root>/Art/Working/<session>/. Default root is the project's
Drive folder. Safety (brief): quiet first, short pings, no animals near, stop at any reaction.
"""
import argparse
import datetime as dt
import json
import sys
import time
from pathlib import Path

import numpy as np

from . import C, analysis, dsp, echo, pair, phone, remix, signals, solver, wavio

DRIVE = Path("/mnt/chromeos/shared/GoogleDrive/MyDrive/One_ring/03_Projects/The Batsight")
RATE = 48000

# Quiet, short, smooth pings (safety rule). 16-20 kHz: 4 kHz wide -> about 4 cm range resolution in theory.
PING = "chirp,f0=16000,f1=20000,ms=5,db=-30"
# Per-phone ping (MEASURED 2026-10-12): the POCO's speaker+mic has a deep notch near 16.7 kHz and
# falls off by 20 kHz; at its volume cap only a 10 ms 17-19 kHz chirp was heard on every ping.
PINGS = {"POCO": "chirp,f0=17000,f1=19000,ms=10,db=-30"}


ECHO_BOOST = ",ms=20,db=-20"


def ping_of(name):
    return PINGS.get(name, PING)


def ping_ms(name):
    return float(signals.parse(ping_of(name))[0][1]["ms"])
LEVEL_CAP = 0.6          # never set the phone's media volume above 60 % of its maximum


class Session:
    def __init__(self, root, name):
        stamp = dt.datetime.now().strftime("%Y-%m-%d_%H%M")
        self.src = Path(root) / "Art" / "Source" / f"{stamp}_{name}"
        self.work = Path(root) / "Art" / "Working" / f"{stamp}_{name}"
        self.src.mkdir(parents=True, exist_ok=True)
        self.work.mkdir(parents=True, exist_ok=True)
        self.lines = []
        self.n = 0

    def say(self, s=""):
        print(s, flush=True)
        self.lines.append(s)

    def rid(self, tag):
        self.n += 1
        return f"{tag}_{self.n:02d}_{dt.datetime.now().strftime('%H%M%S')}"

    def save(self):
        (self.work / "report.md").write_text("\n".join(self.lines) + "\n")
        print(f"\nReport: {self.work / 'report.md'}")


def settings_file(root):
    return Path(root) / "Art" / "Working" / "phone_levels.json"


def load_levels(root):
    p = settings_file(root)
    return json.loads(p.read_text()) if p.exists() else {}


def save_level(root, name, vol):
    d = load_levels(root)
    d[name] = vol
    settings_file(root).write_text(json.dumps(d, indent=1))


def run(s, serial, tag, **opts):
    rid = s.rid(tag)
    return phone.play_record(serial, rid, s.src, **opts)


def direct_snr(r, ref=None):
    """How far the direct pings stand above the recording's quiet parts (dB). Every ping must be
    found at its own spacing; if not all of them stand out, the answer is 0 (not heard)."""
    if np.abs(r.rec).max() == 0:
        return 0.0                      # digital silence (e.g. a source that mutes everything)
    pl = r.played
    col = pl[:, int(np.argmax(np.abs(pl).max(axis=0)))]
    t = analysis.ping_train(r.rec, col, RATE)
    if t is None or t["heard"] < t["count"]:
        return 0.0
    return t["snr_db"]


# --------------------------------------------------------------------------- level

def find_level(s, root, name, serial):
    """Start at the lowest volume and go up one step at a time until the direct ping is clearly
    heard (20 dB above the quiet parts), never above LEVEL_CAP of the maximum."""
    known = load_levels(root).get(name)
    if known is not None:
        s.say(f"- Volume for {name}: step {known} (found earlier, phone_levels.json)")
        return known
    spec = ping_of(name) + ",count=5,every=200"
    ref = signals.reference(spec)
    r = run(s, serial, "level", spec=spec, vol=1)
    vmax = r.meta["volume_max"]
    for v in range(1, int(vmax * LEVEL_CAP) + 1):
        if v > 1:
            r = run(s, serial, "level", spec=spec, vol=v)
        snr = direct_snr(r, ref)
        s.say(f"  volume step {v}/{vmax}: ping {snr:.1f} dB above the quiet parts")
        if snr >= 20:
            save_level(root, name, v)
            s.say(f"- Volume for {name}: step {v} of {vmax} (MEASURED: lowest step that gives a clear ping)")
            return v
    v = int(vmax * LEVEL_CAP)
    s.say(f"- Volume for {name}: capped at step {v} of {vmax}; ping still weak (MEASURED). Results below may be noisy.")
    save_level(root, name, v)
    return v


# --------------------------------------------------------------------------- task 1

def task1(args):
    s = Session(args.root, f"task1_{args.phone}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    s.say(f"# The Batsight - Task 1 - Sound facts - {args.phone}")
    s.say(f"Date: {dt.datetime.now():%Y-%m-%d %H:%M}. Labels: MEASURED / PHONE-REPORTED / UNCONFIRMED.\n")

    s.say("## Items 3, 5, 6 - what the phone reports about itself")
    facts = phone.facts(serial, s.rid("facts"), s.src)
    s.say("```\n" + facts.strip() + "\n```\n")

    s.say("## Volume (safety: lowest first)")
    vol = find_level(s, args.root, args.phone, serial)

    s.say("\n## Item 1 - highest pitch the speaker really plays (heard by its own mic)")
    spec = "sweep,f0=12000,f1=23900,ms=8000,db=-30"
    for ch in (1, 2):
        r = run(s, serial, f"sweep_ch{ch}", spec=spec, vol=vol, ch=ch, pre=1000, post=500)
        start = analysis.direct_delay(r.rec, r.played)
        for c in range(r.rec.shape[1]):
            rows = analysis.sweep_response(r.rec[:, c], RATE, 12000, 23900, 8.0, start, int(r.meta["pre_ms"] / 1000 * RATE))
            top = analysis.top_frequency(rows)
            np.savetxt(s.work / f"{r.id}_mic{c}_response.csv", rows, delimiter=",", header="pitch_hz,level_db,noise_db,snr_db", fmt="%.1f")
            s.say(f"- {ch}-channel recording, channel {c}: heard up to {fmt_hz(top['heard_hz'])}, "
                  f"strong (within 20 dB of best) up to {fmt_hz(top['usable_hz'])} (MEASURED; speaker AND mic together)")
        s.say(f"  source={r.meta['source']}, routed mic: {r.meta['mic_routed']}, effects: {r.meta['effects']}")

    s.say("\n## Item 4 - left and right: different speakers?")
    spec = ("chirp,f0=2000,f1=20000,ms=50,db=-30,ch=L,count=3,every=300"
            "+chirp,f0=2000,f1=20000,ms=50,db=-30,ch=R,at=1000,count=3,every=300")
    r = run(s, serial, "leftright", spec=spec, vol=vol, ch=2)
    lr = analysis.left_right(r.rec, r.played, RATE)
    lr_verdict(s, lr)

    s.say("\n## Item 7 - delay between 'play' and 'heard'")
    spec = ping_of(args.phone) + ",count=5,every=300"
    r = run(s, serial, "latency", spec=spec, vol=vol)
    arr = analysis.direct_delay(r.rec, r.played)
    lat = analysis.latency_report(r.meta, arr)
    for k, v in lat.items():
        s.say(f"- {k}: {v:.2f} (MEASURED)")
    s.say("  software_ms = from the app pressing play to the mic hearing it (what an app must allow for).")
    s.say("  timestamp_ms = from the phone's own 'first sample out' time to heard; near 0.3 ms if the phone's clocks are honest.")

    s.say("\n## Item 5 - does each recording source change the sound?")
    for src in ("unprocessed", "mic", "voice_recognition", "camcorder", "voice_communication"):
        try:
            r = run(s, serial, f"src_{src}", spec=spec, vol=vol, source=src)
            if np.abs(r.rec).max() == 0:
                s.say(f"- {src}: recording is pure digital silence (the phone mutes it); effects: {r.meta['effects']} (MEASURED)")
            else:
                snr = direct_snr(r)
                heard = f"ping {snr:.1f} dB above quiet parts" if snr > 0 else "pings NOT all heard"
                s.say(f"- {src}: {heard}; effects: {r.meta['effects']} (MEASURED)")
        except Exception as e:  # a source the phone refuses is a result too
            s.say(f"- {src}: could not record ({e}) (MEASURED)")

    if args.other:
        s.say(f"\n## Item 2 - highest pitch the {args.phone} mic hears, with the {args.other} playing")
        mic_top(s, args.root, serial, phone.serial_of(args.other), args.other)
    else:
        s.say("\n## Item 2 - mic with the other phone playing: NOT RUN (add --other 8T when both phones are connected)")
    s.save()


def fmt_hz(f):
    return "nothing above the noise" if f is None else f"{f/1000:.1f} kHz"


def lr_verdict(s, lr):
    if "left" not in lr or "right" not in lr:
        s.say("- could not find both groups of chirps (MEASURED)")
        return
    late = (lr["right"]["arrival_frame"] - lr["left"]["arrival_frame"]) / RATE
    s.say(f"- loudness difference left vs right: {lr['peak_difference_db']:+.1f} dB (MEASURED)")
    s.say(f"- right arrives {late*1000:+.3f} ms vs left = {late*C*100:+.1f} cm of extra path (MEASURED)")
    for side in ("left", "right"):
        lv = lr[side]["levels_db"]
        if lv:
            s.say(f"- {side}-only chirps, level per band 2-4/4-8/8-12/12-16/16-20/20-23.5 kHz, per mic channel: "
                  + " | ".join(" ".join(f"{x:.0f}" for x in ch) for ch in lv))
    if abs(lr["peak_difference_db"]) < 1 and abs(late * C) < 0.01:
        s.say("- Reading: left and right sound the same and arrive together -> likely the phone mixes them "
              "into the same speaker(s) (UNCONFIRMED until checked by ear / with the other phone)")
    else:
        s.say("- Reading: left and right differ -> they most likely come out of different speakers (MEASURED difference; which is where is UNCONFIRMED)")


def mic_top(s, root, rec_serial, play_serial, play_name):
    vol = load_levels(root).get(play_name, 3)
    spec = "sweep,f0=12000,f1=23900,ms=8000,db=-30"
    rid_rec, rid_play = s.rid("mictop_rec"), s.rid("mictop_play")
    phone.start(rec_serial, "play-record", rid_rec, record=13000)
    phone.start(play_serial, "play-record", rid_play, spec=spec, vol=vol, pre=2500, post=200)
    for ser, rid in ((rec_serial, rid_rec), (play_serial, rid_play)):
        phone.wait_for(ser, f"{rid}.json", 60)
        phone.pull(ser, rid, s.src)
    r = phone.Run(s.src, rid_rec)
    played = phone.Run(s.src, rid_play).played
    start = analysis.direct_delay(r.rec, played)
    rows = analysis.sweep_response(r.rec, RATE, 12000, 23900, 8.0, start, int(start) - 2048)
    top = analysis.top_frequency(rows)
    s.say(f"- heard up to {fmt_hz(top['heard_hz'])}, strong up to {fmt_hz(top['usable_hz'])} "
          f"(MEASURED; includes the {play_name}'s speaker limit, so the mic may hear more)")


# --------------------------------------------------------------------------- task 2

def task2(args):
    s = Session(args.root, f"task2_{args.phone}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    vol = find_level(s, args.root, args.phone, serial)
    s.say(f"# The Batsight - Task 2 - one file, two channels, scratch and remix - {args.phone}\n")
    spec = ("chirp,f0=15000,f1=17000,ms=20,db=-30,ch=L,count=5,every=400"
            "+chirp,f0=19000,f1=21000,ms=20,db=-30,ch=R,at=200,count=5,every=400")
    s.say(f"Sound A (left) 15-17 kHz, sound B (right) 19-21 kHz, 5 each, interleaved. Recipe: `{spec}`")
    ch = 2
    rn = run(s, serial, "ab_normal", spec=spec, vol=vol, ch=ch)
    rs = run(s, serial, "ab_swapped", spec=spec, vol=vol, ch=ch, swap=True)
    remix_report(s, rn, rs, spec)
    s.save()


def remix_report(s, rn, rs, spec):
    refA, refB = signals.reference(spec, RATE, 0), signals.reference(spec, RATE, 1)
    two = False
    if rn.rec.shape[1] == 2:
        l, r = rn.rec[:, 0].astype(np.float64), rn.rec[:, 1].astype(np.float64)
        corr = float(np.dot(l, r) / (np.sqrt(np.dot(l, l) * np.dot(r, r)) + 1e-20))
        two = corr < 0.99           # 0.99+ = one mic copied (the POCO's unprocessed 'stereo' is 1.00)
    s.say(f"- Recording has {rn.rec.shape[1]} channel(s); {'two different mics' if two else 'one mic (channels identical)'} (MEASURED)")

    def peak(rec, ref):
        return float(np.abs(dsp.matched(analysis.mono(rec), ref)).max())
    s.say("\n### Step B - normal vs swapped (free calibration)")
    for nm, ref in (("A", refA), ("B", refB)):
        a, b = peak(rn.rec, ref), peak(rs.rec, ref)
        s.say(f"- sound {nm}: normal {dsp.db(a):.1f} dB, swapped {dsp.db(b):.1f} dB -> the speaker change alone "
              f"moves it {dsp.db(b) - dsp.db(a):+.1f} dB (MEASURED; same pitch, same room, only the speaker changed)")
    shared, diff = remix.calibration_split(analysis.mono(rn.rec), analysis.mono(rs.rec))
    s.say(f"- shared part (room) vs changing part (speakers): {dsp.db(dsp.rms(shared)):.1f} dB vs {dsp.db(dsp.rms(diff)):.1f} dB "
          "(rough: the two runs are not sample-aligned, so the changing part also holds timing differences)")

    s.say("\n### Step C - remix moves, on copies in Art/Working")
    w = s.work
    wavio.write(w / f"{rn.id}_copy.wav", rn.rec, RATE)
    if rn.rec.shape[1] == 2:
        wavio.write(w / f"{rn.id}_swap.wav", remix.swap(rn.rec), RATE)
        sub = remix.subtract(rn.rec)
        wavio.write(w / f"{rn.id}_left_minus_right.wav", sub, RATE)
        quiet = slice(0, int(0.4 * RATE))
        before = dsp.rms(analysis.mono(rn.rec)[quiet])
        after = dsp.rms(sub[quiet])
        if not two:
            s.say("- swap: written. Subtract left-right: NOT MEANINGFUL here - both channels are one mic, so "
                  "subtracting erases everything, the pings too (MEASURED)")
        else:
            pa0, pa1 = peak(analysis.mono(rn.rec), refA), peak(sub, refA)
            gain = (dsp.db(pa1) - dsp.db(after)) - (dsp.db(pa0) - dsp.db(before))
            s.say(f"- swap: written. Subtract left-right: steady room noise {dsp.db(before):.1f} -> {dsp.db(after):.1f} dB, "
                  f"ping A {dsp.db(pa0):.1f} -> {dsp.db(pa1):.1f} dB; ping-over-noise changes by {gain:+.1f} dB "
                  f"({'helps' if gain > 3 else 'hurts' if gain < -3 else 'little effect'}) (MEASURED)")
    else:
        s.say("- swap / subtract: not possible, one recording channel")
    wavio.write(w / f"{rn.id}_reversed.wav", remix.reverse(rn.rec), RATE)
    s.say("- reverse: written. (Matching with a time-reversed chirp is what every echo timing here already uses.)")
    for k in (8, 16):
        wavio.write(w / f"{rn.id}_slow{k}x.wav", rn.rec, remix.slow_rate(RATE, k))
    s.say("- slow down 8x and 16x: written (19 kHz becomes 2.4 / 1.2 kHz, listen to them on the phone)")
    dm = remix.delay_mix(analysis.mono(rn.rec), RATE, 200)
    wavio.write(w / f"{rn.id}_delaymix200ms.wav", dm, RATE)
    s.say("- delay mix (200 ms): written. Expected weak: the pulse-compressed echo profile already shows repeats more directly.")


# --------------------------------------------------------------------------- task 3 / 5a / 5b

def task3(args):
    step = args.step or "baseline"
    s = Session(args.root, f"task3_{args.phone}_{step}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    vol = find_level(s, args.root, args.phone, serial)
    s.say(f"# The Batsight - Task 3 - echo - {args.phone} - step '{step}'\n")
    # Founder's call 2026-10-12, after the first real run found nothing at 30 cm: echo pings
    # 10 dB louder (-20 dBFS, volume cap unchanged), 20 ms long, 100 per step.
    TRAIN = ping_of(args.phone) + ECHO_BOOST + ",count=100,every=100"
    cms = 20.0
    s.say(f"Ping: `{TRAIN}` (100 pings, 10 s).")
    s.say({"baseline": "Empty room, phone still on a table, nothing within about 2 m in front of it.",
           "hand30": "A hand (or a book) held still 30 cm in front of the phone's speaker/mic.",
           "hand100": "The same hand or book held still 1 m in front."}.get(step, step))
    r = run(s, serial, f"echo_{step}", spec=TRAIN, vol=vol, pre=500, post=300)
    ref = signals.reference(TRAIN)
    profs, arrivals, _ = echo.profiles(analysis.mono(r.rec), ref, signals.ping_starts(TRAIN), RATE, max_m=4.0)
    if len(profs) < 0.8 * 100:
        s.say(f"- WARNING: only {len(profs)} of 100 pings found in the recording (MEASURED)")
    np.save(s.work / f"echo_{step}_profiles.npy", profs)
    base_file = Path(args.root) / "Art" / "Working" / f"baseline_{args.phone}.npz"
    if step == "baseline":
        np.savez(base_file, base=echo.baseline(profs), noise=echo.noise_level(profs))
        env = np.abs(profs.mean(axis=0))
        noise = echo.noise_level(profs) / np.sqrt(len(profs))
        st = echo.states_1d(env, np.maximum(noise, np.median(env) * 0.5), RATE, chirp_ms=cms)
        describe_states(s, st, env)
        s.say(f"- Room signature saved ({len(profs)} pings). Next: --step hand30, then --step hand100.")
    else:
        if not base_file.exists():
            raise SystemExit("Record the baseline first (--step baseline).")
        b = np.load(base_file)
        res = np.abs(echo.residual(profs, b["base"]).mean(axis=0))
        found = echo.changed(res, b["noise"] / np.sqrt(len(profs)) * 3, RATE, chirp_ms=cms)
        if found:
            for d, ratio in found:
                s.say(f"- something changed at {d*100:.0f} cm (leftover {ratio:.0f}x the still-room noise) (MEASURED)")
        else:
            s.say("- no clear change found (MEASURED). This is 'unknown', not 'clear'.")
    s.save()


def describe_states(s, st, env):
    r = echo.range_axis(len(st), RATE)
    runs, cur = [], None
    for i, v in enumerate(st):
        if cur is None or v != cur[0]:
            cur = [v, r[i], r[i]]
            runs.append(cur)
        cur[2] = r[i]
    s.say("- Along the beam (averaged): " + "; ".join(f"{a*100:.0f}-{b*100:.0f} cm {v}" for v, a, b in runs if b - a > 0.02))


# --------------------------------------------------------------------------- task 4

def task4a(args):
    s = Session(args.root, f"task4a_{args.phone}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    s.say(f"# The Batsight - Task 4a - your own clicks - {args.phone} - step '{args.step or 'wall'}'\n")
    s.say("Nothing is played. Snap or tongue-click 3-5 times, about 1 s apart, standing next to the phone (15 s recording).")
    r = run(s, serial, f"clicks_{args.step or 'wall'}", record=15000)
    x = analysis.mono(r.rec)
    env = np.abs(dsp.analytic(dsp.bandpass(x, RATE, 1000, 12000)))
    thr = 10 * np.median(env)
    k, found = 0, []
    while k < len(env):
        if env[k] > thr:
            seg = x[k:k + int(0.06 * RATE)]
            ac = np.correlate(seg, seg, "full")[len(seg) - 1:]
            lo = int(2 * 0.3 / C * RATE)
            j = lo + int(np.argmax(ac[lo:]))
            found.append((k / RATE, j / RATE * C / 2, ac[j] / (ac[0] + 1e-20)))
            k += int(0.5 * RATE)
        else:
            k += 1
    for t, d, q in found:
        s.say(f"- click at {t:.2f} s: strongest repeat at {d*100:.0f} cm (strength {q:.2f} of the click) (MEASURED)")
    if not found:
        s.say("- no click found (MEASURED)")
    s.say("- WARNING: this per-click readout also 'finds' echoes in a take with no clicks at all (seen on the real "
          "phones). A hand click rings 20-90 ms, longer than a 1-3 m echo takes to return. Don't trust these lines alone.")
    s.save()


def link_words(info, A, B):
    q = info["quality"]
    name = {"A_hears_A": f"{A} hears itself", "A_hears_B": f"{A} hears {B}",
            "B_hears_B": f"{B} hears itself", "B_hears_A": f"{B} hears {A}"}
    out = ", ".join(f"{name[k]} {v:.0f}x{'' if v >= pair.HEARD else ' (TOO WEAK)'}" for k, v in q.items())
    extra = [x for x in info.get("not_heard", []) if x.startswith("gaps")]
    return out + ("; REJECTED: " + extra[0] if extra else "")


def pair_round(s, a, b, specA, specB, va, vb):
    """One round with a HANDSHAKE (founder's idea): no guessing by the clock.
    1. both phones start recording and each reports 'recording'; 2. only then A plays;
    3. A reports 'played', then B plays; 4. B reports 'played', then both are told to stop."""
    ia, ib = s.rid("pairA"), s.rid("pairB")
    phone.start_together([(b, "play-record", ib, dict(spec=specB, vol=vb, pre=300, post=300, armed=True, role="second")),
                          (a, "play-record", ia, dict(spec=specA, vol=va, pre=300, post=300, armed=True, role="first"))])
    for ser, rid in ((a, ia), (b, ib)):
        phone.wait_for(ser, f"{rid}_recording.txt", 60, every=0.1)
    phone.trigger(a, ia, "go")
    phone.wait_for(a, f"{ia}_played.txt", 60, every=0.1)
    phone.trigger(b, ib, "go")
    phone.wait_for(b, f"{ib}_played.txt", 60, every=0.1)
    for ser, rid in ((a, ia), (b, ib)):
        phone.trigger(ser, rid, "stop")
    for ser, rid in ((a, ia), (b, ib)):
        phone.wait_for(ser, f"{rid}.json", 60)
        phone.pull(ser, rid, s.src)
    ra, rb = phone.Run(s.src, ia), phone.Run(s.src, ib)
    return pair.distance(analysis.mono(ra.rec), analysis.mono(rb.rec), signals.reference(specA),
                         signals.reference(specB), RATE, 0.1, 0.1, gap_s=0.2)


def link_check(s, a, b, A, B, specA, specB, va, vb):
    """Confirmation check before any two-phone test: each phone must clearly hear the other.
    If one direction is weak, the SENDER of that direction goes up one volume step at a time, never
    above LEVEL_CAP of its maximum (same safety rule as the single-phone ladder). If it still fails at
    the cap, stop and say which direction failed. Returns the volumes to use."""
    caps = {}
    for ser, v in ((a, va), (b, vb)):
        mx = phone.max_volume(ser)
        caps[ser] = int(mx * LEVEL_CAP) if mx else v
    while True:
        d, info = pair_round(s, a, b, specA, specB, va, vb)
        q = info["quality"]
        s.say(f"- Link check at volume steps {A} {va}, {B} {vb}: {link_words(info, A, B)} (MEASURED; needs {pair.HEARD:.0f}x)")
        if d is not None:
            s.say("- Link check passed.")
            return va, vb
        up = False
        if q["B_hears_A"] < pair.HEARD and va < caps[a]:
            va += 1; up = True
        if q["A_hears_B"] < pair.HEARD and vb < caps[b]:
            vb += 1; up = True
        if not up:
            s.say("- LINK CHECK FAILED at the volume cap: the phones don't clearly hear each other. "
                  "Test stopped; no distances reported.")
            s.save()
            raise SystemExit("link check failed: " + link_words(info, A, B))


def task4c(args):
    s = Session(args.root, "task4c_pair")
    a, b = phone.serial_of(args.phone), phone.serial_of(args.other)
    for ser in (a, b):
        phone.grant(ser)
    va, vb = load_levels(args.root).get(args.phone, 3), load_levels(args.root).get(args.other, 3)
    s.say(f"# The Batsight - Task 4c - two-phone round trip, no shared clock - {args.phone} and {args.other}\n")
    s.say("Measure the distance between the two phones with a tape first and write it here: ____ cm")
    # 12-15 kHz: the POCO's mic stops near 15.5 kHz (MEASURED 2026-10-12), so 16-20 kHz can't link
    # the two phones. Up-sweep vs down-sweep tells the two sounds apart. 100 ms (founder 2026-10-12): 20 ms was too weak for the POCO to hear the 8T.
    specA, specB = "chirp,f0=12000,f1=15000,ms=100,db=-30", "chirp,f0=15000,f1=12000,ms=100,db=-30"
    va, vb = link_check(s, a, b, args.phone, args.other, specA, specB, va, vb)
    results = []
    for rnd in range(5):
        d, t = pair_round(s, a, b, specA, specB, va, vb)
        results.append(d)
        s.say(f"- round {rnd+1}: " + (f"{d*100:.1f} cm (MEASURED; self speaker-mic assumed 10 cm each, UNCONFIRMED); "
              f"{args.other} answered {(t['tAB'] - t['tAA']) / RATE * 1000:.0f} ms after {args.phone}'s sound" if d is not None
              else "NO DISTANCE - " + link_words(t, args.phone, args.other)))
    ok = [d for d in results if d is not None]
    if ok:
        s.say(f"- spread over {len(ok)} rounds: {np.std(ok)*100:.1f} cm")
    s.save()


# --------------------------------------------------------------------------- task 5

def task5c(args):
    s = Session(args.root, f"task5c_{args.phone}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    vol = find_level(s, args.root, args.phone, serial)
    s.say(f"# The Batsight - Task 5c - two sweeps at once (up left, down right) - {args.phone}\n")
    up, down = "chirp,f0=16000,f1=20000,ms=10,db=-33,ch=L", "chirp,f0=20000,f1=16000,ms=10,db=-33,ch=R"
    timings = {"together": (",count=10,every=200", ",count=10,every=200"),
               "canon": (",count=10,every=200", ",at=100,count=10,every=200"),
               "off-beat": (",count=10,gaps=170;230;190;210", ",at=60,count=10,gaps=230;170;210;190")}
    # Separation is judged against each sweep played ALONE (real phones: with both starting at the
    # same instant, comparing positions inside one recording measured a spot against itself).
    # Number = how much the OTHER sweep raises the floor where this sweep's echoes are read
    # (0.3-3 m after each of its pings), versus that sweep alone. Lower = better separated.
    def floors(spec, part, rec, played):
        x = analysis.mono(rec)
        e = np.abs(dsp.matched(x, signals.reference(spec, RATE, part)))
        col = played[:, 0] if part == 0 else played[:, 1]
        off = analysis.direct_delay(rec, played)
        if off is None:
            return None
        lo, hi = int(2 * 0.3 / C * RATE), int(2 * 3.0 / C * RATE)
        st = signals.ping_starts(spec, RATE, part)
        return float(np.sqrt(np.mean(np.concatenate([e[int(off) + k + lo:int(off) + k + hi] for k in st]) ** 2)))
    solo = {}
    for nm, sp, part in (("up", up + ",count=10,every=200", 0), ("down", down + ",count=10,every=200", 0)):
        r = run(s, serial, f"updown_solo_{nm}", spec=sp, vol=vol)
        solo[nm] = floors(sp, part, r.rec, r.played)
    for name, (tu, td) in timings.items():
        spec = up + tu + "+" + down + td
        r = run(s, serial, f"updown_{name.replace('-', '')}", spec=spec, vol=vol)
        fu, fd = floors(spec, 0, r.rec, r.played), floors(spec, 1, r.rec, r.played)
        if None in (fu, fd, solo["up"], solo["down"]):
            s.say(f"- {name}: sweeps not found in the recording (MEASURED)")
            continue
        s.say(f"- {name}: the other sweep raises the up-sweep's echo floor by {dsp.db(fu / solo['up']):+.1f} dB, "
              f"the down-sweep's by {dsp.db(fd / solo['down']):+.1f} dB (MEASURED; 0 = no harm, lower = better separated)")
    s.save()


def task5d(args):
    s = Session(args.root, f"task5d_{args.phone}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    vol = find_level(s, args.root, args.phone, serial)
    s.say(f"# The Batsight - Task 5d - how fast the room's sound dies away - {args.phone}\n")
    spec = "chirp,f0=4000,f1=20000,ms=20,db=-30,count=5,every=800"
    r = run(s, serial, "decay", spec=spec, vol=vol)
    profs, _, _ = echo.profiles(analysis.mono(r.rec), signals.reference(spec), signals.ping_starts(spec), RATE, max_m=60.0)
    times = []
    for i, p in enumerate(profs):
        t = echo.decay_time(p, RATE, start_bin=int(0.002 * RATE))
        times.append(t)
        s.say(f"- ping {i+1}: decay to -60 dB in {('%.2f s' % t) if t else 'not measurable'} (MEASURED)")
    avg = np.sqrt(np.mean(np.abs(profs) ** 2, axis=0))
    t = echo.decay_time(avg, RATE, start_bin=int(0.002 * RATE))
    ok = [x for x in times if x]
    s.say(f"- all {len(profs)} pings together: {('%.2f s' % t) if t else 'not measurable'}"
          + (f"; single pings ranged {min(ok):.2f}-{max(ok):.2f} s" if ok else "") + " (MEASURED)")
    s.say("- Added reverb check: a copy with a fake 0.5 s reverb is in Art/Working; its echo peaks blur (see tests).")
    x = analysis.mono(r.rec)
    tail = np.random.default_rng(0).normal(0, 1, int(0.5 * RATE)) * 10 ** (-3 * np.arange(int(0.5 * RATE)) / RATE / 0.5)
    wavio.write(s.work / f"{r.id}_with_fake_reverb.wav", np.convolve(x, tail / np.abs(tail).sum())[:len(x)] * 20, RATE)
    s.save()


def task5e(args):
    step = args.step or "still"
    s = Session(args.root, f"task5e_{args.phone}_{step}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    vol = find_level(s, args.root, args.phone, serial)
    s.say(f"# The Batsight - Task 5e - averaging while still / noticing movement - {args.phone} - '{step}'\n")
    # walk: the stronger echo ping (founder-approved for echo tests); the brief's quiet ping barely
    # showed a walking person on the real 8T (MEASURED 2026-10-12)
    spec = ping_of(args.phone) + (",count=100,every=60" if step == "still" else ECHO_BOOST + ",count=80,every=100")
    s.say("Phone still on the table, nobody moving." if step == "still"
          else "Phone still. After about 2 seconds, walk slowly across the room 1-2 m in front of it.")
    r = run(s, serial, f"avg_{step}", spec=spec, vol=vol)
    profs, _, _ = echo.profiles(analysis.mono(r.rec), signals.reference(spec), signals.ping_starts(spec), RATE, max_m=3.0)
    rr = echo.range_axis(profs.shape[1], RATE)
    if step == "still":
        lo, hi = int(np.searchsorted(rr, 0.3)), int(np.searchsorted(rr, 3.0))
        for n, v in echo.averaging_gain(profs, lo, hi).items():
            s.say(f"- after {n} pings: strongest echo (0.3-3 m) {v:.1f} dB above the ping-to-ping noise (MEASURED)")
        s.say("  (noise = how much two separate groups of N pings disagree; 100 pings make two groups of 50, "
              "so 50 is the last honest step)")
    else:
        rr_m = (rr > 0.3) & (rr < 3.0)
        base = profs[:10].mean(axis=0)
        n1 = np.sqrt((np.abs(profs[:10] - base) ** 2).mean(axis=0)) + 1e-20
        moving = []
        for i in range(0, len(profs) - 4, 5):
            # groups of 5 pings vs the still start (a single ping is noisier than the room's echoes)
            ch = np.abs(profs[i:i + 5].mean(axis=0) - base)[rr_m] / (n1[rr_m] / np.sqrt(5) * np.sqrt(1 + 5 / 10))
            big = int((ch > 4).sum())
            where = rr[rr_m][int(np.argmax(ch))]
            flag = big >= 8
            moving.append(flag)
            s.say(f"- {i/10:.1f}-{(i+4)/10:.1f} s: {big} distances changed >4x" + (f", most at {where*100:.0f} cm -> MOVING" if flag else ""))
        first = next((i for i, f in enumerate(moving) if f), None)
        s.say(f"- first 'moving' group: {first*0.5:.1f} s (MEASURED)" if first is not None else "- no movement flagged (MEASURED)")
        s.say("  The average restarts at every 'moving' group instead of drawing a false picture.")
    s.save()


def task5f(args):
    s = Session(args.root, f"task5f_{args.phone}_angle{args.angle}")
    a, b = phone.serial_of(args.phone), phone.serial_of(args.other)
    va = load_levels(args.root).get(args.phone, 3)
    s.say(f"# The Batsight - Task 5f - two speakers together, angle {args.angle} deg - {args.phone} plays, {args.other} listens\n")
    s.say("The listening phone stays at 1 m; turn the playing phone to the angle (0 = speakers facing it).")
    spec = "tone,f=18000,ms=1500,db=-30+tone,f=17000,ms=1500,db=-30,at=2000+tone,f=19000,ms=1500,db=-30,at=4000"
    ia, ib = s.rid("deadzone_play"), s.rid("deadzone_rec")
    phone.start(b, "play-record", ib, record=8000)
    phone.start(a, "play-record", ia, spec=spec, vol=va, pre=1000)
    for ser, rid in ((a, ia), (b, ib)):
        phone.wait_for(ser, f"{rid}.json", 60)
        phone.pull(ser, rid, s.src)
    x = analysis.mono(phone.Run(s.src, ib).rec)
    lv = {str(f): dsp.band_level_db(x, RATE, f, 100) for f in (17000, 18000, 19000)}
    log = Path(args.root) / "Art" / "Working" / f"deadzones_{args.phone}.json"
    d = json.loads(log.read_text()) if log.exists() else {}
    d[str(args.angle)] = lv
    log.write_text(json.dumps(d, indent=1))
    for f, v in lv.items():
        s.say(f"- {int(f)/1000:.0f} kHz: {v:.1f} dB (MEASURED)")
    if len(d) >= 4:
        s.say("\n### All angles so far (a drop of 15 dB or more below the median = possible DEAD ZONE)")
        for f in ("17000", "18000", "19000"):
            vals = {int(k): v[f] for k, v in d.items()}
            med = np.median(list(vals.values()))
            dead = [k for k, v in sorted(vals.items()) if v < med - 15]
            s.say(f"- {int(f)/1000:.0f} kHz: " + (f"possible dead zones at {dead} deg" if dead else "no dead zone found at these angles"))
    s.save()


def yaw_per_ping(motion_csv, meta, ping_frames, rate):
    """Angle of the phone at each ping, from the gyroscope axis that turned the most."""
    rows = [l.split(",") for l in motion_csv.strip().splitlines()[1:]]
    g = np.array([[float(t), float(x), float(y), float(z)] for n, t, x, y, z in rows if n == "gyro"])
    if len(g) < 10:
        raise ValueError("no gyroscope data")
    axis = 1 + int(np.argmax(g[:, 1:].std(axis=0)))
    t = g[:, 0]
    ang = np.concatenate([[0], np.cumsum(0.5 * (g[1:, axis] + g[:-1, axis]) * np.diff(t) / 1e9)])
    # audio frame -> monotonic ns -> elapsed-realtime ns (the sensors' clock)
    shift = meta["clock_elapsed_ns"] - meta["clock_mono_ns"]
    ns = meta["rec_ts_ns"] + (np.asarray(ping_frames) - meta["rec_ts_frame"]) / rate * 1e9 + shift
    return np.interp(ns, t, ang) - np.interp(ns, t, ang).mean()


def task5g(args):
    s = Session(args.root, f"task5g_{args.phone}")
    serial = phone.serial_of(args.phone)
    phone.grant(serial)
    vol = find_level(s, args.root, args.phone, serial)
    s.say(f"# The Batsight - Task 5g - probable-surface picture from a cane sweep - {args.phone}\n")
    s.say("Simple tape-measured room with one chair. Hold the phone level at chest height, screen up, top edge "
          "pointing forward; sweep slowly left-right-left over about 10 s. Note the chair's distance and side.")
    spec = ping_of(args.phone) + ",count=100,every=100"
    r = run(s, serial, "cane", spec=spec, vol=vol, motion=True)
    profs, arrivals, _ = echo.profiles(analysis.mono(r.rec), signals.reference(spec), signals.ping_starts(spec), RATE, max_m=4.0)
    angles = yaw_per_ping(r.motion, r.meta, arrivals, RATE)
    env = np.abs(profs)
    b = echo.blank_bins(RATE, 0.3)
    env[:, :b] = 0
    noise = float(np.median(env[:, b:]))
    grid = solver.Grid(4.0, 4.0, 0.15)   # 15 cm cells: expect a blurry outline anyway
    img = solver.solve(env, angles, RATE, grid, bandwidth=4000)
    st, votes = solver.classify(img, env, angles, RATE, grid, noise * 4)
    solver.picture(st, s.work / f"{r.id}_map.png")
    s.say(f"- sweep covered {np.degrees(angles.min()):.0f} to {np.degrees(angles.max()):.0f} deg (MEASURED, gyroscope)")
    counts = {k: int((st == k).sum()) for k in (echo.ECHO, echo.PROBABLE, echo.UNKNOWN, echo.CLEAR)}
    s.say(f"- map cells: {counts} -> picture {r.id}_map.png (red echo, orange probable, grey unknown, green clear)")
    s.say("- Compare with the tape measure and write the difference here: ____ (UNCONFIRMED until compared)")
    s.save()


def task5h(args):
    s = Session(args.root, "task5h_pair_loop")
    s.say("# The Batsight - Task 5h - two phones standing still, listening loop\n")
    s.say("Runs Task 4c rounds; each phone keeps its own signature; the estimate is averaged until it stops changing.")
    a, b = phone.serial_of(args.phone), phone.serial_of(args.other)
    va, vb = load_levels(args.root).get(args.phone, 3), load_levels(args.root).get(args.other, 3)
    # 12-15 kHz: the POCO's mic stops near 15.5 kHz (MEASURED 2026-10-12), so 16-20 kHz can't link
    # the two phones. Up-sweep vs down-sweep tells the two sounds apart. 100 ms (founder 2026-10-12): 20 ms was too weak for the POCO to hear the 8T.
    specA, specB = "chirp,f0=12000,f1=15000,ms=100,db=-30", "chirp,f0=15000,f1=12000,ms=100,db=-30"
    va, vb = link_check(s, a, b, args.phone, args.other, specA, specB, va, vb)
    est, prev = [], None
    for rnd in range(12):
        # swap roles every round
        if rnd % 2 == 0:
            d, info = pair_round(s, a, b, specA, specB, va, vb)
            first = args.phone
        else:
            d, info = pair_round(s, b, a, specB, specA, vb, va)
            first = args.other
        if d is not None:
            est.append(d)
        cur = float(np.median(est)) if est else None
        s.say(f"- round {rnd+1} ({first} first): {('%.1f cm' % (d*100)) if d is not None else 'NO DISTANCE - ' + link_words(info, 'first', 'second')}; "
              f"running estimate {('%.1f cm' % (cur*100)) if cur else '-'}")
        if prev is not None and cur is not None and len(est) >= 4 and rnd >= 3 and abs(cur - prev) < 0.005:
            s.say("- estimate stopped changing (under 0.5 cm): done ('listening... clearer...')")
            break
        prev = cur
    s.save()


CHIME = "tone,f=880,ms=250,db=-15+tone,f=660,ms=400,at=320,db=-15"


def chime():
    """'Done' ding-dong on the 8T (founder steps away during tests). Audible on purpose; the app puts
    the owner's volume back afterwards."""
    try:
        d = phone.devices()
        ser = d.get("8T") or next(iter(d.values()), None)
        if ser:
            rid = "chime_" + dt.datetime.now().strftime("%H%M%S")
            phone.start(ser, "play-record", rid, spec=CHIME, vol=12, pre=100, post=100)
            phone.wait_for(ser, f"{rid}.json", 30)
            phone.adb(ser, "shell", f"rm -f {phone.REMOTE}/{rid}*", check=False)
    except Exception:
        pass


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("task")
    ap.add_argument("--phone", default="POCO")
    ap.add_argument("--other", default=None)
    ap.add_argument("--step", default=None)
    ap.add_argument("--angle", type=int, default=0)
    ap.add_argument("--root", default=str(DRIVE))
    ap.add_argument("--wait", type=int, default=0, help="seconds to wait first, so the founder can step away")
    ap.add_argument("--no-chime", action="store_true", help="don't play the 'done' ding-dong on the 8T")
    args = ap.parse_args(argv)
    if args.task in ("task4c", "task5f", "task5h") and not args.other:
        args.other = "8T" if args.phone == "POCO" else "POCO"
    tasks = {"task1": task1, "task2": task2, "task3": task3, "task5a": task3, "task5b": task3, "task4a": task4a,
             "task4c": task4c, "task5c": task5c, "task5d": task5d, "task5e": task5e, "task5f": task5f,
             "task5g": task5g, "task5h": task5h}
    if args.task == "facts":
        s = Session(args.root, f"facts_{args.phone}")
        print(phone.facts(phone.serial_of(args.phone), s.rid("facts"), s.src))
        return
    if args.task not in tasks:
        sys.exit(f"unknown task {args.task}; choose from facts, {', '.join(tasks)}")
    # The app sets a quiet test volume; put the founder's own volume back afterwards.
    serials = [phone.serial_of(n) for n in (args.phone, args.other) if n and n in phone.devices()]
    before = {sr: phone.get_volume(sr) for sr in serials}
    if args.wait:
        time.sleep(args.wait)
    try:
        tasks[args.task](args)
    finally:
        if not args.no_chime:
            chime()
        for sr, v in before.items():
            if v is not None:
                phone.set_volume(sr, v)


if __name__ == "__main__":
    main()
