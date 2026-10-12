"""The founder's "scratch and remix" toolbox (Task 2, step C). Every move works on COPIES.

Each function takes samples [frames, channels] (or [frames]) and returns new samples.
"""
import numpy as np


def swap(x):
    """Swap left and right. Needs a two-channel recording."""
    if x.ndim == 1 or x.shape[1] < 2:
        raise ValueError("swap needs two channels")
    return x[:, ::-1].copy()


def subtract(x):
    """Left minus right. Cancels what both mics hear equally (steady room noise), keeps the
    differences (echoes arriving at slightly different times, direction)."""
    if x.ndim == 1 or x.shape[1] < 2:
        raise ValueError("subtract needs two channels")
    return (x[:, 0] - x[:, 1]) * 0.5


def reverse(x):
    """Play it backwards. Correlating with a time-reversed chirp is how echoes are timed (see dsp.matched)."""
    return x[::-1].copy()


def slow_rate(rate, factor):
    """Slow down by `factor`: keep the samples, write them with a lower sample rate. Pitch drops by the
    same factor (19 kHz slowed 8x = 2.4 kHz, easy to hear). Returns the new sample rate for wavio.write."""
    return int(round(rate / factor))


def delay_mix(x, rate, ms, gain=0.7):
    """Mix with a delayed copy of itself. A repeating pattern (like an echo) lines up with its own copy."""
    d = int(round(ms / 1000 * rate))
    y = x.astype(np.float64).copy()
    if d < len(x):
        y[d:] += gain * x[:len(x) - d]
    return y / (1 + gain)


def calibration_split(rec_normal, rec_swapped):
    """Task 2 step B. Play the file, then the same file with left/right swapped.
    Returns (shared, differing): what stays the same belongs to the room and the sound,
    what changes belongs to the phone's own two speakers (where they sit, how loud each is)."""
    n = min(len(rec_normal), len(rec_swapped))
    a = rec_normal[:n].astype(np.float64)
    b = rec_swapped[:n].astype(np.float64)
    return (a + b) / 2, (a - b) / 2
