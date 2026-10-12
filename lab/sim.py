"""Fake rooms for testing the tools before any phone is connected. NOT a claim about real phones."""
import numpy as np

from . import C


def _nfft(n):
    return 1 << int(np.ceil(np.log2(max(n, 1))))


def room(played, rate, echoes=(), mic_dist_m=0.10, spk_extra_m=(0.0, 0.0), spk_gain=(1.0, 1.0),
         direct_gain=1.0, lowpass_hz=None, lowpass_order=40, noise_db=-70.0, pre_frames=0, post_frames=None, seed=0):
    """One-mic recording of a stereo `played` [frames, 2] in a fake room.
    echoes: (distance m, strength) pairs, round trip 2*d. spk_extra_m: how much farther each speaker
    is from the mic than mic_dist_m (two speakers in different places arrive at different times).
    lowpass_hz: the phone can't play/hear above this. Returns [frames] float64."""
    played = np.asarray(played, dtype=np.float64)
    if played.ndim == 1:
        played = np.stack([played, played * 0], axis=1)
    rng = np.random.default_rng(seed)
    if post_frames is None:
        post_frames = int(0.1 * rate) + int(max([2 * d for d, _ in echoes] + [0.0]) / C * rate)
    n = pre_frames + len(played) + post_frames
    N = _nfft(n)
    f = np.fft.rfftfreq(N, 1 / rate)
    Y = np.zeros(len(f), dtype=complex)
    for j in range(2):
        X = np.fft.rfft(np.pad(played[:, j], (pre_frames, 0)), N)
        H = np.zeros(len(f), dtype=complex)
        base = mic_dist_m + spk_extra_m[j]
        H += direct_gain * np.exp(-2j * np.pi * f * base / C)
        for d, g in echoes:
            H += g * np.exp(-2j * np.pi * f * (2 * d + base) / C)
        Y += spk_gain[j] * X * H
    if lowpass_hz:
        Y *= 1 / (1 + (f / lowpass_hz) ** lowpass_order)
    y = np.fft.irfft(Y, N)[:n]
    return y + rng.normal(0, 10 ** (noise_db / 20), n)


def gaussian_psf(n, center, width):
    k = np.arange(n)
    return np.exp(-0.5 * ((k - center) / width) ** 2)


def scan_profiles(scatterers, angles, rate, n_bins, beam_deg=40.0, bandwidth=2000.0, noise=0.01, seed=0):
    """Fake echo envelopes for a phone at (0,0) pointed at each angle (radians, 0 = straight ahead +y).
    scatterers: (x, y, strength). Returns [len(angles), n_bins]."""
    rng = np.random.default_rng(seed)
    width = rate / bandwidth / 2.355 * 1.0
    out = np.zeros((len(angles), n_bins))
    for i, th in enumerate(angles):
        for x, y, g in scatterers:
            r = np.hypot(x, y)
            ang = np.arctan2(x, y)
            w = np.exp(-0.5 * (np.degrees(ang - th) / (beam_deg / 2.355)) ** 2)
            k = 2 * r / C * rate
            out[i] += g * w / max(r, 0.1) ** 2 * gaussian_psf(n_bins, k, width)
        out[i] += np.abs(rng.normal(0, noise, n_bins))
    return out
