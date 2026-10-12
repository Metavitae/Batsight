"""Read and write 16-bit PCM WAV files with numpy (no extra libraries)."""
import wave

import numpy as np


def read(path):
    """Returns (samples float32 shaped [frames, channels] in -1..1, sample rate)."""
    with wave.open(str(path), "rb") as w:
        ch, width, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        if width != 2:
            raise ValueError(f"{path}: only 16-bit WAV is supported")
        raw = np.frombuffer(w.readframes(n), dtype="<i2")
    return raw.reshape(-1, ch).astype(np.float32) / 32768.0, rate


def write(path, samples, rate):
    """samples: [frames] or [frames, channels] floats in -1..1."""
    x = np.asarray(samples, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    pcm = np.round(np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(x.shape[1])
        w.setsampwidth(2)
        w.setframerate(int(rate))
        w.writeframes(pcm.tobytes())
