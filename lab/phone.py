"""Drive the Sound Lab app on the phones over adb."""
import re
import json
import shlex
import subprocess
import time
from pathlib import Path

from . import wavio

PKG = "com.metavitae.soundlab"
REMOTE = "/sdcard/Download/SoundLab"
SIGNALS = f"/sdcard/Android/data/{PKG}/files/signals"
NAMES = {"2312FPCA6G": "POCO", "KB2007": "8T"}


def adb(serial, *args, timeout=60, check=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def devices():
    """{'POCO': serial, '8T': serial} for connected phones."""
    out = {}
    for line in adb(None, "devices").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            model = adb(parts[0], "shell", "getprop", "ro.product.model").strip()
            name = NAMES.get(model, model)
            # Same phone twice (wireless-debugging link + classic :5555)? Keep :5555; the other drops.
            if name not in out or parts[0].endswith(":5555"):
                out[name] = parts[0]
    return out


def serial_of(name):
    d = devices()
    if name not in d:
        raise SystemExit(f"Phone '{name}' is not connected. Connected: {', '.join(d) or 'none'}")
    return d[name]


def get_volume(serial):
    """Media volume step right now (None if the phone won't say)."""
    out = adb(serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--get", check=False)
    m = re.search(r"volume is (\d+)", out or "")
    return int(m.group(1)) if m else None


def max_volume(serial):
    out = adb(serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--get", check=False)
    m = re.search(r"range \[0\.\.(\d+)\]", out or "")
    return int(m.group(1)) if m else None


def set_volume(serial, step):
    """NOTE: both test phones IGNORE this (MEASURED 2026-10-12); the app restores the volume itself."""
    adb(serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--set", str(step), check=False)


def install(serial, apk):
    """Normal install; the POCO blocks adb installs, so there the APK is pushed and the phone's own
    installer is opened (founder taps Install, or CC taps it)."""
    r = subprocess.run(["adb", "-s", serial, "install", "-r", str(apk)], capture_output=True, text=True, timeout=180)
    if r.returncode == 0 and "Success" in r.stdout:
        return "installed"
    if "INSTALL_FAILED_USER_RESTRICTED" in (r.stdout + r.stderr):
        dst = "/sdcard/Download/SoundLab.apk"
        adb(serial, "push", str(apk), dst, timeout=180)
        adb(serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-t",
            "application/vnd.android.package-archive", "-d", f"file://{dst}", check=False)
        return "installer opened on the phone (tap Install)"
    raise RuntimeError(r.stdout + r.stderr)


def grant(serial):
    adb(serial, "shell", "pm", "grant", PKG, "android.permission.RECORD_AUDIO", check=False)


# Founder's rule 2026-10-12: "audible is fine as long as it isn't hurtful".
MAX_DB = -20.0        # no sound louder than this (dBFS)
MAX_MS = 100.0        # no sound longer than this ...
LONG_MAX_DB = -30.0   # ... unless it is at most this loud (e.g. Task 1's slow pitch sweep)


def check_safe(spec):
    """Refuse a sound that breaks the founder's 'never hurtful' rule. Checked on EVERY start."""
    from . import signals
    for kind, v in signals.parse(spec):
        if kind == "silence":
            continue
        db = float(v.get("db", -30)); ms = float(v.get("ms", 0.3 if kind == "click" else 10))
        if db > MAX_DB or (ms > MAX_MS and db > LONG_MAX_DB):
            raise SystemExit(f"refused by the safety rule: '{kind}' {db} dB for {ms} ms "
                             f"(max {MAX_DB} dB; longer than {MAX_MS} ms only at {LONG_MAX_DB} dB or quieter)")


def start(serial, mode, run_id, **extras):
    if extras.get("spec"):
        check_safe(extras["spec"])
    parts = ["am", "start", "-S", "-n", f"{PKG}/.MainActivity", "--es", "mode", mode, "--es", "id", run_id]
    for k, v in extras.items():
        if v is None:
            continue
        flag = "--ez" if isinstance(v, bool) else "--ei" if isinstance(v, int) else "--es"
        parts += [flag, k, str(v).lower() if isinstance(v, bool) else str(v)]
    adb(serial, "shell", " ".join(shlex.quote(p) for p in parts))


def start_together(jobs):
    """Start several phones at the same moment (threads). jobs = [(serial, mode, run_id, extras)].
    One-after-the-other starts were too fragile: on the real 8T one start took over 4 s and the two
    recordings missed each other."""
    import threading
    th = [threading.Thread(target=start, args=(ser, mode, rid), kwargs=ex) for ser, mode, rid, ex in jobs]
    for t in th:
        t.start()
    for t in th:
        t.join()


def trigger(serial, run_id, what):
    """Handshake signal for an 'armed' run: drop <id>.go or <id>.stop into the app's own folder."""
    adb(serial, "shell", f"mkdir -p {SIGNALS} && touch {SIGNALS}/{run_id}.{what}")


def wait_for(serial, name, timeout=120, every=1.0):
    end = time.time() + timeout
    while time.time() < end:
        # Android may save "x.json" as "x.json.txt" (seen on the 8T, app 0.1); accept either.
        if adb(serial, "shell", f"ls {REMOTE}/{name} {REMOTE}/{name}.txt 2>/dev/null", check=False).strip():
            time.sleep(min(0.5, every))   # let the file finish writing
            return True
        time.sleep(every)
    raise TimeoutError(f"{name} did not appear on the phone within {timeout} s")


def pull(serial, run_id, dest, delete=True):
    """Pull every file of a run into dest; then delete them from the phone (development only)."""
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    names = [n for n in adb(serial, "shell", f"ls {REMOTE}", check=False).split() if n.startswith(run_id)]
    for n in names:
        local = n[:-4] if n.endswith((".json.txt", ".csv.txt")) else n
        adb(serial, "pull", f"{REMOTE}/{n}", str(dest / local), timeout=180)
        if delete:
            adb(serial, "shell", "rm", f"{REMOTE}/{n}", check=False)
    return [dest / (n[:-4] if n.endswith((".json.txt", ".csv.txt")) else n) for n in names]


def push_signal(serial, wav_path):
    adb(serial, "shell", "mkdir", "-p", SIGNALS, check=False)
    adb(serial, "push", str(wav_path), f"{SIGNALS}/{Path(wav_path).name}", timeout=120)


class Run:
    """One finished play-record run, loaded from the pulled files."""
    def __init__(self, folder, run_id):
        folder = Path(folder)
        self.id = run_id
        self.meta = json.loads((folder / f"{run_id}.json").read_text())
        if "error" in self.meta:
            raise RuntimeError(f"phone reported: {self.meta['error']}")
        self.rec, self.rate = wavio.read(folder / f"{run_id}.wav")
        p = folder / f"{run_id}_played.wav"
        self.played = wavio.read(p)[0] if p.exists() else None
        m = folder / f"{run_id}_motion.csv"
        self.motion = m.read_text() if m.exists() else None


def play_record(serial, run_id, dest, timeout=120, **opts):
    start(serial, "play-record", run_id, **opts)
    wait_for(serial, f"{run_id}.json", timeout)
    pull(serial, run_id, dest)
    return Run(dest, run_id)


def facts(serial, run_id, dest, timeout=90):
    start(serial, "facts", run_id)
    wait_for(serial, f"{run_id}.txt", timeout)
    pull(serial, run_id, dest)
    return (Path(dest) / f"{run_id}.txt").read_text()
