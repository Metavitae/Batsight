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


def distance(recA, recB, refA, refB, rate, self_A=0.0, self_B=0.0):
    recA = np.asarray(recA, dtype=np.float64)
    recB = np.asarray(recB, dtype=np.float64)
    tAA, _ = _arrival(recA, refA)
    tBB, _ = _arrival(recB, refB)
    tAB, _ = _arrival(recA, refB)
    tBA, _ = _arrival(recB, refA)
    if None in (tAA, tBB, tAB, tBA):
        return None, dict(tAA=tAA, tAB=tAB, tBA=tBA, tBB=tBB)
    d = C / 2 * ((tAB - tAA) - (tBB - tBA)) / rate + (self_A + self_B) / 2
    return float(d), dict(tAA=tAA, tAB=tAB, tBA=tBA, tBB=tBB)
