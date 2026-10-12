"""Two phones, no shared clock (Task 4c): each phone plays its own sound and records both.

Phone A hears its own sound at tAA and B's at tAB (in A's own clock); phone B likewise.
Distance = C/2 * ((tAB - tAA) - (tBB - tBA)) + (self_A + self_B)/2, where self_X is phone X's own
speaker-to-mic distance. The unknown start times cancel out, so no shared clock is needed.
"""
import numpy as np

from . import C, dsp


def _arrival(rec, ref, exclude=None, guard=0):
    env = np.abs(dsp.matched(rec, ref))
    if exclude is not None:
        lo, hi = max(0, int(exclude) - guard), int(exclude) + guard
        env[lo:hi] = 0
    return dsp.first_arrival(env), env


def _first(rec, ref, start=0, stop=None, rate=48000):
    """First arrival of ref in rec[start:stop] and how clearly it stands out (peak / background)."""
    env = np.abs(dsp.matched(rec, ref))
    start = max(0, int(start))
    stop = len(env) if stop is None else max(start + 1, min(len(env), int(stop)))
    t = dsp.first_arrival(env, start=start, stop=stop)
    if t is None:
        return None, 0.0
    seg = env[start:stop].copy()
    k = int(round(t)) - start
    w = int(0.02 * rate)
    peak = float(env[int(round(t))])
    seg[max(0, k - w):k + w] = 0
    bg = float(np.sqrt(np.mean(seg ** 2))) + 1e-20
    return t, peak / bg


HEARD = 6.0   # a partner sound counts as heard only if it stands 6x above the background


MAX_M = 10.0  # sanity limit: phones farther apart than this can't be told from a stray sound


def distance(recA, recB, refA, refB, rate, self_A=0.0, self_B=0.0, gap_s=None):
    """gap_s: B is known to play at least gap_s after A. Then B's sound is only searched for after A's
    own sound in A's recording, and A's sound only before B's own in B's recording, so a phone's own
    loud sound can't be mistaken for the other's quieter one (the real phones are 20-30 dB apart)."""
    recA = np.asarray(recA, dtype=np.float64)
    recB = np.asarray(recB, dtype=np.float64)
    (tAA, qAA), (tBB, qBB) = _first(recA, refA, rate=rate), _first(recB, refB, rate=rate)
    if gap_s is None or tAA is None or tBB is None:
        (tAB, qAB), (tBA, qBA) = _first(recA, refB, rate=rate), _first(recB, refA, rate=rate)
    else:
        g = int(gap_s * rate)
        tAB, qAB = _first(recA, refB, start=tAA + g, rate=rate)
        tBA, qBA = _first(recB, refA, stop=tBB - g, rate=rate)
    q = dict(A_hears_A=qAA, A_hears_B=qAB, B_hears_B=qBB, B_hears_A=qBA)
    info = dict(tAA=tAA, tAB=tAB, tBA=tBA, tBB=tBB, quality=q)
    # Confirmation check: no distance unless all four sounds were clearly heard.
    if None in (tAA, tBB, tAB, tBA) or min(q.values()) < HEARD:
        info["not_heard"] = [k for k, v in q.items() if v < HEARD]
        return None, info
    # Sanity check (no clocks needed): the gap between the two sounds must look the same to both
    # phones, give or take the travel time there and back. A stray sound (seen on the real phones:
    # three 12-15 kHz pulses neither app played) breaks this and is rejected.
    mismatch = abs((tAB - tAA) - (tBB - tBA)) / rate
    if mismatch > 2 * MAX_M / C:
        info["not_heard"] = [f"gaps disagree by {mismatch*1000:.0f} ms (a stray sound?)"]
        return None, info
    d = C / 2 * ((tAB - tAA) - (tBB - tBA)) / rate + (self_A + self_B) / 2
    return float(d), info
