# The Batsight - Sound Lab

Test bench for The Batsight: sonar with a phone's own speaker and microphone. Nothing here is the
product. It measures what two real phones (OnePlus 8T, POCO M6 Pro) can do, test by test.

- `app/`: **Sound Lab**, an Android test app (Kotlin, no libraries), driven by the computer over adb.
  Modes: `facts` (what the phone reports about its audio and sensors), `play-record` (play a sound and
  record at the same time; `armed` = handshake: report "recording", wait for go/stop), `live` (two-phone
  rhythm: a lead plays a steady beat, a follower recognises it on the phone and answers, correcting its
  own delay). Results go to `Download/SoundLab/`; the `.json` is written last. The app sets a test
  volume and puts the owner's volume back itself. GitHub Actions builds the APK (test-only signing key).
- `lab/`: computer side (Python + numpy): sound recipes (same format as the app), space-style probe
  sounds, pulse compression, echo lists with the phone's measured own-sound fade, room baseline and
  "what changed", watch mode (moving / changed / adopted, learns the room's normal wobble), near/far
  mode, two-phone distance with no shared clock, the handshake, ping-pong, two-phone positions.
- `tests/`: every tool on FAKE rooms with known answers, every task end to end against a pretend phone.
  Fake rooms prove the plumbing, not the physics.

## Running (CC)

```
python3 -m venv .venv && .venv/bin/pip install numpy pytest
.venv/bin/python -m pytest -q tests                       # about 2 minutes
.venv/bin/python -m lab.tasks <task> --phone 8T [--other POCO] [--wait 20] [--no-chime]
```
Tasks: `facts`, `task1`-`task5h` (the original brief), `look` (narrow vs wide pitch range),
`watch --sound chirp|warp_hyper [--dry]`, `nearfar [--step calib]`, `pingpong`, `live [--dry]`.
`--wait 20` gives the founder time to step away; every task ends with a ding-dong on the 8T.
Results go to the project's Drive folder: raw recordings to `Art/Source/<session>/`, copies and
`report.md` to `Art/Working/<session>/`.

## Safety (founder's rules, 2026-10-12)
- Audible is fine, never hurtful: no sound louder than -20 dBFS; no single sound longer than 100 ms
  unless at -30 dBFS or quieter. Checked in code on EVERY sound (recipes and files) before it plays.
- The volume starts low and climbs one step at a time; 60 % of maximum is a comfort line that may be
  passed only when calibration needs it.
- No pets or sleeping people in the room. Keep phones away from ears.
- Photos of the test room are private: they live in Drive only, never in this repo (blocked in `.gitignore`).
