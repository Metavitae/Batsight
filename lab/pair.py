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


def _first(rec, ref, start=0, stop=None):
    env = np.abs(dsp.matched(rec, ref))
    start = max(0, int(start))
    stop = None if stop is None else max(start + 1, int(stop))
    return dsp.first_arrival(env, start=start, stop=stop)


def distance(recA, recB, refA, refB, rate, self_A=0.0, self_B=0.0, gap_s=None):
    """gap_s: B is known to play at least gap_s after A. Then B's sound is only searched for after A's
    own sound in A's recording, and A's sound only before B's own in B's recording, so a phone's own
    loud sound can't be mistaken for the other's quieter one (the real phones are 20-30 dB apart)."""
    recA = np.asarray(recA, dtype=np.float64)
    recB = np.asarray(recB, dtype=np.float64)
    tAA = _first(recA, refA)
    tBB = _first(recB, refB)
    if gap_s is None or tAA is None or tBB is None:
        tAB = _first(recA, refB)
        tBA = _first(recB, refA)
    else:
        g = int(gap_s * rate)
        tAB = _first(recA, refB, start=tAA + g)
        tBA = _first(recB, refA, stop=tBB - g)
    if None in (tAA, tBB, tAB, tBA):
        return None, dict(tAA=tAA, tAB=tAB, tBA=tBA, tBB=tBB)
    d = C / 2 * ((tAB - tAA) - (tBB - tBA)) / rate + (self_A + self_B) / 2
    return float(d), dict(tAA=tAA, tAB=tAB, tBA=tBA, tBB=tBB)
