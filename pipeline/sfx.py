"""GTA III sound effects: read samples from audio\\sfx.SDT + sfx.RAW, and turn them into small phone WAVs.

sfx.SDT holds 20-byte entries (offset, size, sample rate, loop start, loop end) into sfx.RAW, which is
16-bit signed mono PCM. The sample numbers used by assets.SFX follow the game's own sample bank order.
"""
import os, io, struct, wave
import numpy as np


def read_sample(audio_dir, index):
    """-> (float32 samples in -1..1, sample rate)"""
    def ci(n):
        m = [f for f in os.listdir(audio_dir) if f.lower() == n]
        if not m: raise FileNotFoundError(os.path.join(audio_dir, n))
        return os.path.join(audio_dir, m[0])
    sdt = open(ci('sfx.sdt'), 'rb').read()
    off, size, rate, _, _ = struct.unpack_from('<IIIii', sdt, index * 20)
    with open(ci('sfx.raw'), 'rb') as f:
        f.seek(off); x = np.frombuffer(f.read(size), '<i2').astype(np.float32) / 32768.0
    return x, rate


def write_wav16(path, x, rate):
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())


def read_wav16(path):
    with wave.open(path, 'rb') as w:
        return np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768.0, w.getframerate()


def phone_wav(x, rate, out_rate=11025, max_s=1.0, fade_s=0.08, peak=0.9):
    """trim the silence, cut to max_s with a fade-out, low-pass + resample, normalise, 8-bit unsigned mono WAV bytes"""
    a = np.abs(x); thr = a.max() * 0.02 if a.max() > 0 else 0
    nz = np.nonzero(a > thr)[0]
    if len(nz): x = x[max(0, nz[0] - int(0.002 * rate)):nz[-1] + 1]
    x = x[:int(max_s * rate)].copy()
    nf = min(len(x), int(fade_s * rate))
    if nf > 1: x[-nf:] *= np.linspace(1, 0, nf) ** 2
    if rate > out_rate:                                   # windowed-sinc low-pass at 0.45 x the new rate, then resample
        fc = 0.45 * out_rate / rate; k = np.arange(-32, 33)
        h = np.sinc(2 * fc * k) * np.hamming(len(k)); h /= h.sum()
        x = np.convolve(x, h, mode='same')
    n_out = max(1, int(round(len(x) * out_rate / rate)))
    y = np.interp(np.linspace(0, len(x) - 1, n_out), np.arange(len(x)), x)
    m = np.abs(y).max()
    if m > 0: y = y / m * peak
    rng = np.random.default_rng(1)
    y8 = np.clip(np.round(y * 127 + 128 + rng.uniform(-0.5, 0.5, len(y)) + rng.uniform(-0.5, 0.5, len(y))), 0, 255).astype(np.uint8)
    b = io.BytesIO()
    with wave.open(b, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(1); w.setframerate(out_rate); w.writeframes(y8.tobytes())
    return b.getvalue()
