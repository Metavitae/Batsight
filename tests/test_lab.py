"""Checks the computer-side tools on FAKE rooms with known answers (no phone needed)."""
import numpy as np
import pytest

from lab import C, analysis, dsp, echo, pair, remix, signals, sim, solver, wavio

RATE = 48000


def test_signal_recipe_parts_and_placement():
    x = signals.build("chirp,f0=17000,f1=19000,ms=10,ch=L,count=3,every=100+tone,f=1000,ms=5,ch=R,at=50", RATE)
    assert x.shape[1] == 2
    assert len(x) == int(0.2 * RATE) + 480           # last chirp starts at 200 ms
    assert np.abs(x[:, 1][:int(0.049 * RATE)]).max() == 0   # right is silent before 50 ms
    assert np.abs(x[:, 0]).max() == pytest.approx(10 ** (-30 / 20), rel=0.02)
    assert signals.ping_starts("chirp,count=3,gaps=100;250", RATE) == [0, 4800, 16800]


def test_smooth_edges_no_click():
    x = signals.one("chirp", {"f0": "17000", "f1": "19000", "ms": "10"}, RATE)
    assert abs(x[0]) < 1e-3 and abs(x[-1]) < 1e-3


def test_wav_roundtrip(tmp_path):
    x = signals.build("chirp,ms=20", RATE)
    wavio.write(tmp_path / "a.wav", x, RATE)
    y, r = wavio.read(tmp_path / "a.wav")
    assert r == RATE and y.shape == x.shape
    assert np.abs(y - x).max() < 1e-4


def test_matched_filter_finds_echo_distance():
    spec = "chirp,f0=17000,f1=19000,ms=10,db=-20"
    played = signals.build(spec, RATE)
    rec = sim.room(played, RATE, echoes=[(1.0, 0.3)], mic_dist_m=0.1, pre_frames=4800, noise_db=-80)
    env = np.abs(dsp.matched(rec, signals.reference(spec, RATE)))
    direct = dsp.first_arrival(env)
    assert direct == pytest.approx(4800 + 0.1 / C * RATE, abs=1.0)
    blank = echo.blank_bins(RATE, 0.3)
    k = int(direct) + blank + int(np.argmax(env[int(direct) + blank:int(direct) + 1000]))
    dist = (dsp.interp_peak(env, k) - direct) / RATE * C / 2
    assert dist == pytest.approx(1.0, abs=0.03)


def test_sweep_top_frequency():
    spec = "sweep,f0=15000,f1=23500,ms=4000,db=-20"
    played = signals.build(spec, RATE)
    rec = sim.room(played, RATE, lowpass_hz=20000, lowpass_order=200, pre_frames=RATE // 2, noise_db=-75)
    start = analysis.direct_delay(rec, played)
    rows = analysis.sweep_response(rec, RATE, 15000, 23500, 4.0, start, RATE // 2)
    top = analysis.top_frequency(rows)
    assert 19500 < top["usable_hz"] < 21000
    assert 19500 < top["heard_hz"] < 21500


def test_left_right_two_speakers_in_different_places():
    spec = "chirp,f0=2000,f1=20000,ms=50,ch=L,count=2,every=300+chirp,f0=2000,f1=20000,ms=50,ch=R,at=1000,count=2,every=300"
    played = signals.build(spec, RATE)
    rec = sim.room(played, RATE, spk_extra_m=(0.0, 0.12), spk_gain=(1.0, 0.5), pre_frames=4800, noise_db=-80)
    lr = analysis.left_right(rec, played, RATE)
    assert lr["peak_difference_db"] == pytest.approx(6.0, abs=1.0)
    # the right speaker is 12 cm farther: its sound arrives ~17 samples later than the left one would
    late = (lr["right"]["arrival_frame"] - lr["left"]["arrival_frame"])
    assert late == pytest.approx(0.12 / C * RATE, abs=1.5)


def test_latency_from_timestamps():
    meta = dict(rate=RATE, frames_at_play_call=24000, track_ts_ok=True, rec_ts_ok=True,
                track_ts_frame=4800, track_ts_ns=2_000_000_000, rec_ts_frame=48000, rec_ts_ns=2_000_000_000)
    # first sample left the speaker at t = 2 s - 0.1 s = 1.9 s -> record frame 48000 - 4800 = 43200
    rep = analysis.latency_report(meta, 43200 + 14)
    assert rep["timestamp_ms"] == pytest.approx(14 / RATE * 1000)
    assert rep["software_ms"] == pytest.approx((43214 - 24000) / RATE * 1000)


def _ping_train(spec, echoes, seed, noise_db=-70):
    played = signals.build(spec, RATE)
    rec = sim.room(played, RATE, echoes=echoes, pre_frames=4800, noise_db=noise_db, seed=seed)
    ref = signals.reference(spec, RATE)
    return echo.profiles(rec, ref, signals.ping_starts(spec, RATE), RATE, max_m=3.0)[0]


SPEC = "chirp,f0=16000,f1=20000,ms=10,db=-20,count=20,every=60"
WALLS = [(1.8, 0.2), (2.5, 0.15)]


def test_baseline_subtraction_walls_vanish_hand_shows():
    base_profs = _ping_train(SPEC, WALLS, seed=1)
    base = echo.baseline(base_profs)
    noise = echo.noise_level(base_profs)
    # same room, nothing changed: no change reported
    still = _ping_train(SPEC, WALLS, seed=2)
    res = np.abs(echo.residual(still, base).mean(axis=0))
    assert echo.changed(res, noise / np.sqrt(len(still)) * 3, RATE) == []
    # a hand at 30 cm appears: it shows up, the walls do not
    hand = _ping_train(SPEC, WALLS + [(0.3, 0.05)], seed=3)
    res = np.abs(echo.residual(hand, base).mean(axis=0))
    found = echo.changed(res, noise / np.sqrt(len(hand)) * 3, RATE)
    assert len(found) >= 1
    assert found[0][0] == pytest.approx(0.3, abs=0.05)
    assert all(abs(d - 1.8) > 0.1 and abs(d - 2.5) > 0.1 for d, _ in found)


def test_three_states_silence_never_clear():
    spec = "chirp,f0=16000,f1=20000,ms=3,db=-20,count=20,every=60"
    profs = _ping_train(spec, [(1.0, 0.2)], seed=4)
    env = np.abs(profs.mean(axis=0))
    noise = np.full(len(env), np.median(env[echo.blank_bins(RATE, 2.0):]) * 3 + 1e-9)
    st = echo.states_1d(env, noise, RATE, chirp_ms=3)
    r = echo.range_axis(len(env), RATE)
    assert st[np.argmin(np.abs(r - 1.0))] == echo.ECHO
    assert st[np.argmin(np.abs(r - 0.5))] == echo.CLEAR
    assert st[np.argmin(np.abs(r - 0.2))] == echo.UNKNOWN     # blind zone: direct sound still ringing
    # anything above the noise that is not the echo itself (ripple) is never CLEAR
    assert not ((env > 6 * noise) & (st == echo.CLEAR)).any()
    assert st[np.argmin(np.abs(r - 2.0))] == echo.UNKNOWN      # beyond the wall: silence -> unknown
    empty = echo.states_1d(noise * 0.5, noise, RATE)
    assert echo.CLEAR not in set(empty)                         # no echo at all -> no green anywhere


def test_averaging_gets_sharper():
    spec = "chirp,f0=16000,f1=20000,ms=10,db=-40,count=100,every=40"
    profs = _ping_train(spec, [(1.0, 0.05)], seed=5, noise_db=-55)
    r = echo.range_axis(profs.shape[1], RATE)
    tb = int(np.argmin(np.abs(r - 1.0)))
    nb = np.arange(tb + 150, tb + 450)
    curve = echo.averaging_curve(profs, tb, nb)
    assert curve[100] > curve[1] + 10   # 100 pings ~ +20 dB in theory


def test_motion_detected_and_average_restarts():
    still = _ping_train(SPEC, WALLS, seed=6)
    noise = echo.noise_level(still)
    moving = np.concatenate([still[:10], _ping_train(SPEC, WALLS + [(0.9, 0.1)], seed=7)[:10]])
    flags = echo.motion_flags(moving, noise, blank=echo.blank_bins(RATE, 0.15))
    assert not flags[:10].any()
    assert flags[10]
    _, used = echo.average_with_restart(moving, flags)
    assert used <= 10


def test_decay_time_soft_vs_hard_room():
    rng = np.random.default_rng(0)
    t = np.arange(RATE) / RATE

    def tail(rt60):
        return rng.normal(0, 1, len(t)) * 10 ** (-3 * t / rt60)
    hard = echo.decay_time(tail(0.8), RATE)
    soft = echo.decay_time(tail(0.3), RATE)
    assert hard == pytest.approx(0.8, rel=0.2)
    assert soft == pytest.approx(0.3, rel=0.2)


def test_two_phone_distance_no_shared_clock():
    a_spec, b_spec = "chirp,f0=16000,f1=18000,ms=20,db=-20", "chirp,f0=20000,f1=18000,ms=20,db=-20"
    refA, refB = signals.reference(a_spec, RATE), signals.reference(b_spec, RATE)
    d, self_m = 2.0, 0.1
    a_play, b_play = 0.3, 0.9          # seconds, each in its own unknown clock
    n = RATE * 2
    recA, recB = np.zeros(n), np.zeros(n)
    def put(rec, sig, t, g):
        k = int(round(t * RATE)); rec[k:k + len(sig)] += g * sig
    put(recA, refA, a_play + self_m / C, 1.0)
    put(recA, refB, b_play + d / C, 0.2)
    put(recB, refA, a_play + d / C, 0.2)
    put(recB, refB, b_play + self_m / C, 1.0)
    recB = np.roll(recB, 12345)        # B started recording at a different moment
    est, _ = pair.distance(recA, recB, refA, refB, RATE, self_m, self_m)
    assert est == pytest.approx(d, abs=0.02)


def test_remix_moves():
    x = np.stack([np.arange(10.0), -np.arange(10.0)], axis=1)
    assert (remix.swap(x)[:, 0] == x[:, 1]).all()
    assert (remix.subtract(x) == np.arange(10.0)).all()
    assert (remix.reverse(x)[0] == x[-1]).all()
    assert remix.slow_rate(48000, 8) == 6000
    y = remix.delay_mix(np.r_[1.0, np.zeros(9)], 1000, 3, gain=1.0)
    assert y[3] == pytest.approx(0.5)
    shared, diff = remix.calibration_split(x, remix.swap(x))
    assert np.allclose(shared[:, 0], shared[:, 1])


def test_solver_finds_wall_and_never_greens_silence():
    grid = solver.Grid(width_m=2.4, depth_m=2.4, cell_m=0.12)
    angles = np.radians(np.linspace(-40, 40, 17))
    wall = [(x, 1.8, 1.0) for x in np.arange(-1.0, 1.01, 0.1)]
    n_bins = int(2 * 2.6 / C * RATE)
    profs = sim.scan_profiles(wall, angles, RATE, n_bins, noise=0.002)
    img = solver.solve(profs, angles, RATE, grid)
    states, votes = solver.classify(img, profs, angles, RATE, grid, noise=0.002 * 3)
    ys = grid.ys
    wall_row = int(np.argmin(np.abs(ys - 1.8)))
    mid = len(grid.xs) // 2
    assert states[wall_row - 1:wall_row + 2, mid].tolist().count(echo.ECHO) >= 1
    assert states[int(np.argmin(np.abs(ys - 0.9))), mid] == echo.CLEAR
    # nothing at all in the room: no cell may be green
    empty = sim.scan_profiles([], angles, RATE, n_bins, noise=0.002)
    img0 = solver.solve(empty, angles, RATE, grid)
    st0, _ = solver.classify(img0, empty, angles, RATE, grid, noise=0.002 * 3)
    assert (st0 != echo.CLEAR).all()


def test_averaging_gain_counts_only_ping_to_ping_noise():
    # a fixed room echo plus random noise: averaging N pings should gain ~10*log10(N) dB,
    # and the room's own echoes must NOT count as noise
    rng = np.random.default_rng(1)
    room = np.zeros(400, complex)
    room[50], room[200], room[300] = 1.0, 0.8, 0.6
    profs = room[None, :] + 0.3 * (rng.normal(size=(100, 400)) + 1j * rng.normal(size=(100, 400)))
    g = echo.averaging_gain(profs, 0, 400)
    assert g[50] - g[1] == pytest.approx(17.0, abs=2.0)


def test_decay_time_ignores_noise_floor():
    rate = 48000
    t = np.arange(int(0.6 * rate)) / rate
    rng = np.random.default_rng(2)
    env = np.exp(-6.9 * t / 0.4) * rng.normal(size=len(t)) + 10 ** (-40 / 20) * rng.normal(size=len(t))
    assert echo.decay_time(env, rate) == pytest.approx(0.4, abs=0.08)


def test_pair_distance_rejects_stray_sound():
    from lab import pair
    rng = np.random.default_rng(3)
    A = signals.reference("chirp,f0=12000,f1=15000,ms=100,db=-10")
    B = signals.reference("chirp,f0=15000,f1=12000,ms=100,db=-10")
    def put(x, ref, at, g):
        x[at:at + len(ref)] += g * ref
    n = 5 * RATE
    d_frames = int(0.3 / C * RATE)                       # phones 30 cm apart
    recA = rng.normal(0, 1e-4, n); recB = rng.normal(0, 1e-4, n)
    put(recA, A, 30000, 1.0); put(recB, A, 20000 + d_frames, 0.3)      # A plays, both hear
    put(recB, B, 150000, 1.0); put(recA, B, 160000 + d_frames, 0.3)    # B plays 2.7 s later
    d, _ = pair.distance(recA, recB, A, B, RATE, gap_s=0.4)
    assert d == pytest.approx(0.3, abs=0.02)
    recA2 = recA.copy(); recA2[160000:] = rng.normal(0, 1e-4, n - 160000)
    put(recA2, B, 60000, 0.3)                                         # a stray "B" sound, wrong time
    d2, info = pair.distance(recA2, recB, A, B, RATE, gap_s=0.4)
    assert d2 is None and "gaps disagree" in info["not_heard"][0]


def test_safety_rule_refuses_loud_or_long_sounds():
    from lab import phone
    phone.check_safe("chirp,f0=8000,f1=15000,ms=100,db=-20")            # the handshake: allowed
    phone.check_safe("sweep,f0=12000,f1=23900,ms=8000,db=-30")          # Task 1's slow sweep: allowed
    for bad in ("chirp,f0=8000,f1=15000,ms=100,db=-10", "tone,f=1000,ms=500,db=-20"):
        with pytest.raises(SystemExit, match="safety rule"):
            phone.check_safe(bad)


def test_watch_flags_a_change_then_settles():
    rng = np.random.default_rng(4)
    room = np.zeros(300, complex); room[100] = 1.0; room[200] = 0.7
    profs = room[None, :] + 0.05 * (rng.normal(size=(200, 300)) + 1j * rng.normal(size=(200, 300)))
    t = np.arange(30)
    profs[80:110, 140:160] += 0.8 * np.exp(1j * 0.5 * t)[:, None]   # someone walking: keeps changing
    profs[140:, 220] += 0.8                                          # a door opened at chirp 140, then stays
    res = echo.watch(profs, RATE, 0.15, lo_m=0.0, hi_m=10.0, min_bins=1, settle_s=3.0)
    st = [x[0] for x in res]
    assert set(st[25:75]) == {"still"}
    assert st[90:105].count("MOVING") >= 10
    assert "ADOPTED" in st[140:175] and set(st[180:]) == {"still"}      # the open door becomes the usual


def test_space_sounds_are_safe_and_a_single_tone_is_blurry():
    from lab import sounds
    for name, fn in sounds.SOUNDS.items():
        x = fn()
        assert len(x) / RATE <= 0.1 and 20 * np.log10(np.abs(x).max()) <= -19.9, name
    tone = np.sin(2 * np.pi * 8000 * np.arange(int(0.05 * RATE)) / RATE) * 0.1   # the classic 'ping'
    assert sounds.sharpness(tone)["blur_cm"] > 5 * sounds.sharpness(sounds.blaster_fall())["blur_cm"]
