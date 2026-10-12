"""A pretend phone for testing lab/tasks.py end to end: it 'plays' through lab.sim and writes the same
files the real app writes. Everything here is invented; it proves the plumbing, not the physics."""
import json
from pathlib import Path

import numpy as np

from lab import C, phone, signals, sim, wavio

RATE = 48000


class FakeWorld:
    def __init__(self, tmp, echoes=((1.5, 0.1),), lowpass=21000, pair_distance=2.0):
        self.dir = Path(tmp) / "phones"
        self.echoes = list(echoes)
        self.lowpass = lowpass
        self.pair_distance = pair_distance
        self.pair_gain = 1.0   # 0 = the phones can't hear each other
        self.pending = {}        # serial -> (run_id, opts)
        self.n = 0

    def install(self, monkeypatch):
        monkeypatch.setattr(phone, "serial_of", lambda name: name)
        monkeypatch.setattr(phone, "grant", lambda serial: None)
        monkeypatch.setattr(phone, "start", self.start)
        monkeypatch.setattr(phone, "wait_for", self.wait_for)
        monkeypatch.setattr(phone, "pull", self.pull)
        monkeypatch.setattr(phone, "max_volume", lambda serial: 10)

    def start(self, serial, mode, run_id, **opts):
        self.pending[run_id] = (serial, mode, opts)

    def wait_for(self, serial, name, timeout=0):
        # 'run' everything that is pending at once (two phones at the same time)
        todo = {k: v for k, v in self.pending.items()}
        self.pending.clear()
        players = [(rid, o) for rid, (ser, m, o) in todo.items() if o.get("spec")]
        for rid, (ser, mode, opts) in todo.items():
            self._run(ser, mode, rid, opts, players)

    def _run(self, serial, mode, rid, opts, players):
        d = self.dir / serial
        d.mkdir(parents=True, exist_ok=True)
        if mode == "facts":
            (d / f"{rid}.txt").write_text("Sound Lab - Phone sound facts\n(fake phone)\n")
            return
        self.n += 1
        pre = opts.get("pre", 500)
        rec_len = int((pre + opts.get("delay", 0) + opts.get("post", 500)) / 1000 * RATE)
        played = signals.build(opts["spec"], RATE) if opts.get("spec") else None
        if played is not None and opts.get("swap"):
            played = played[:, ::-1]
        ch = opts.get("ch", 1)
        if played is not None:
            vol_gain = 0.1 * opts.get("vol", 5) if opts.get("vol", -1) >= 0 else 0.5
            rec_len += len(played)
            y = sim.room(played * vol_gain, RATE, echoes=self.echoes, spk_extra_m=(0.0, 0.1), spk_gain=(1.0, 0.7),
                         lowpass_hz=self.lowpass, lowpass_order=120, pre_frames=int(pre / 1000 * RATE),
                         post_frames=rec_len, noise_db=-75, seed=self.n)[:rec_len]
        else:
            rec_len = int(opts.get("record", 3000) / 1000 * RATE)
            y = np.random.default_rng(self.n).normal(0, 10 ** (-75 / 20), rec_len)
        # partner phones playing at the same time are heard across the pair distance (pre decides who's first)
        for prid, popts in players:
            if prid == rid:
                continue
            p = signals.build(popts["spec"], RATE) * 0.3 * self.pair_gain * (0.1 * popts["vol"] if popts.get("vol", -1) >= 0 else 0.5)
            k = int(popts.get("pre", 500) / 1000 * RATE + self.pair_distance / C * RATE)
            seg = p[:, 0] + p[:, 1]
            end = min(len(y), k + len(seg))
            if k < len(y):
                y[k:end] += seg[:end - k]
        rec = np.stack([y, y * 0.8], axis=1) if ch == 2 else y
        wavio.write(d / f"{rid}.wav", rec, RATE)
        if played is not None:
            wavio.write(d / f"{rid}_played.wav", played, RATE)
        motion = None
        if opts.get("motion"):
            t = np.arange(0, len(y) / RATE, 0.005)
            yaw_rate = np.radians(40) * 2 * np.pi / 5 * np.cos(2 * np.pi * t / 5)
            lines = ["sensor,t_ns,x,y,z"] + [f"gyro,{int(tt*1e9)},0,0,{w}" for tt, w in zip(t, yaw_rate)]
            (d / f"{rid}_motion.csv").write_text("\n".join(lines) + "\n")
        frames_at_play = int(pre / 1000 * RATE)
        meta = dict(id=rid, model="FAKE", rate=RATE, channels=ch, source=opts.get("source", "unprocessed"),
                    mic_routed="fake", out_routed="fake", effects={}, volume=opts.get("vol", 5), volume_max=15,
                    pre_ms=pre, post_ms=opts.get("post", 500), frames_at_play_call=frames_at_play,
                    track_ts_ok=True, track_ts_frame=0, track_ts_ns=int(pre / 1000 * 1e9),
                    rec_ts_ok=True, rec_ts_frame=0, rec_ts_ns=0, clock_mono_ns=0, clock_elapsed_ns=0)
        (d / f"{rid}.json").write_text(json.dumps(meta))

    def pull(self, serial, run_id, dest, delete=True):
        dest = Path(dest)
        dest.mkdir(parents=True, exist_ok=True)
        out = []
        for f in (self.dir / serial).glob(f"{run_id}*"):
            (dest / f.name).write_bytes(f.read_bytes())
            out.append(dest / f.name)
        return out
