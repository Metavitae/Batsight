# The Batsight - Sound Lab

Test bench for The Batsight (sonar with the phone's own speaker and microphone). Nothing here is the
product. It measures the phones first, as the CC brief "Phone sound facts, both phones" asks.

- `app/`: **Sound Lab**, an Android test app (Kotlin, no libraries). It plays a sound and records at the
  same time, then saves everything to `Download/SoundLab/`. CC drives it over adb. GitHub Actions builds
  the APK, signed with a test-only key so updates install over each other.
- `lab/`: computer-side tools (Python + numpy): signal recipes (the same format as the app), pulse
  compression, the remix toolbox, room baseline and "what changed", three states, averaging,
  motion check, decay time, two-phone distance with no shared clock, and the probable-surface solver.
- `tests/`: every tool checked on FAKE rooms with known answers, plus every task run end to end against
  a pretend phone. Fake rooms prove the plumbing, not the physics.

## Running (CC)

```
python3 -m venv .venv && .venv/bin/pip install numpy pytest
.venv/bin/python -m pytest -q tests                 # about 2 minutes
.venv/bin/python -m lab.tasks task1 --phone POCO --other 8T
.venv/bin/python -m lab.tasks task1 --phone 8T --other POCO
.venv/bin/python -m lab.tasks task2 --phone POCO
.venv/bin/python -m lab.tasks task3 --phone POCO --step baseline   # then --step hand30, --step hand100
```
Optional (founder's go only): task4a, task4c, task5c, task5d, task5e (--step still|walk),
task5f (--angle N, both phones), task5g, task5h. Results go to the project's Drive folder:
raw recordings to `Art/Source/<session>/`, copies, pictures and `report.md` to `Art/Working/<session>/`.

App over adb (what `lab/phone.py` sends):
```
adb shell pm grant com.metavitae.soundlab android.permission.RECORD_AUDIO
adb shell am start -S -n com.metavitae.soundlab/.MainActivity --es mode facts --es id facts_1
adb shell "am start -S -n com.metavitae.soundlab/.MainActivity --es mode play-record --es id run_1 \
  --es spec 'chirp,f0=16000,f1=20000,ms=5,db=-30,count=20,every=100' --ei vol 3 --ei ch 1"
```

## Safety (from the brief)
Quiet first (the runner starts at volume step 1 and never goes above 60 %), short smooth pings, never
continuous. No pets, small animals or sleeping people in the room or next door. Keep the phone away from ears.
