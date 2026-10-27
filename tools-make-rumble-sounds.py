#!/usr/bin/env python3
"""Synthesise the two looping rumble sounds of the plant (RUMBLE.md) and encode them to Ogg Vorbis.

    py -3.13 tools-make-rumble-sounds.py --blender "C:/Program Files (x86)/Steam/steamapps/common/Blender/blender.exe"

Everything is generated here from seeded noise and sine waves with numpy: no recording, sample or downloaded audio is used. The
waveforms are exactly periodic (noise is shaped in the frequency domain and transformed back, thumps are added modulo the
loop, sines have a whole number of cycles), so the loop seam carries no step; a gentle dip in loudness at the seam hides the codec's
start-up in the first Vorbis block. Encoding is done by a separate headless Blender through Audaspace (Blender ships FFmpeg); the
script never touches a scene or a running Blender.

  rumble_pool.ogg     6 s, mono, 48 kHz   condensing steam: a 18-65 Hz body, irregular thumps that fall from 55 to 32 Hz (chugging)
                                          and a 70-220 Hz crackle of collapsing bubbles; soft saturation adds harmonics so small speakers
                                          and headphones reproduce it.
  rumble_turbine.ogg  4 s, mono, 48 kHz   a shaft train: 25 Hz (1,500 rpm) with its harmonics, slowly beating, over a 20-60 Hz noise
                                          bed and a 90-300 Hz mechanical texture.
"""
import argparse
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

SAMPLE_RATE = 48_000
BITRATE = 96_000


def rng(seed):
    import numpy as np
    return np.random.default_rng(seed)


def shaped_noise(n, seed, curve):
    """Periodic noise whose amplitude spectrum follows curve(freq_hz); unit RMS."""
    import numpy as np
    spectrum = np.fft.rfft(rng(seed).standard_normal(n))
    freq = np.fft.rfftfreq(n, 1.0 / SAMPLE_RATE)
    spectrum *= curve(freq)
    spectrum[0] = 0
    out = np.fft.irfft(spectrum, n)
    return out / (np.sqrt(np.mean(out ** 2)) + 1e-12)


def band(lo, hi, slope_lo=2, slope_hi=4):
    import numpy as np

    def curve(f):
        f = np.maximum(f, 1e-3)
        return (1.0 / np.sqrt(1.0 + (lo / f) ** (2 * slope_lo))) * (1.0 / np.sqrt(1.0 + (f / hi) ** (2 * slope_hi)))
    return curve


def smooth_envelope(n, seed, cutoff_hz, depth):
    """Periodic slow random gain in [1 - depth, 1 + depth]."""
    import numpy as np
    x = shaped_noise(n, seed, lambda f: 1.0 / np.sqrt(1.0 + (f / cutoff_hz) ** 4))
    return 1.0 + depth * np.clip(x / 2.5, -1, 1)


def seam_dip(n, depth=0.4, width_s=0.15):
    import numpy as np
    t = np.arange(n) / SAMPLE_RATE
    length = n / SAMPLE_RATE
    d = np.minimum(t, length - t)
    return 1.0 - depth * np.exp(-0.5 * (d / width_s) ** 2)


def add_circular(buf, start, chunk):
    import numpy as np
    idx = (start + np.arange(len(chunk))) % len(buf)
    buf[idx] += chunk


def pool(seconds=6.0):
    import numpy as np
    n = int(seconds * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    body = shaped_noise(n, 11, band(18, 65)) * 0.55 * smooth_envelope(n, 12, 1.2, 0.35)
    thumps = np.zeros(n)
    crackle = np.zeros(n)
    r = rng(13)
    position = 0.15
    while position < seconds - 0.2:
        a = r.uniform(0.45, 1.0)
        length = int(0.9 * SAMPLE_RATE)
        tt = np.arange(length) / SAMPLE_RATE
        freq = 32 + 23 * np.exp(-tt / 0.07)
        phase = 2 * np.pi * np.cumsum(freq) / SAMPLE_RATE
        env = (1 - np.exp(-tt / 0.008)) * np.exp(-tt / 0.17)
        add_circular(thumps, int(position * SAMPLE_RATE), a * np.sin(phase) * env)
        burst = shaped_noise(int(0.4 * SAMPLE_RATE), int(r.integers(1, 10 ** 6)), band(70, 220, 2, 3))
        tb = np.arange(len(burst)) / SAMPLE_RATE
        add_circular(crackle, int(position * SAMPLE_RATE), 0.22 * a * burst * np.exp(-tb / 0.07) * (1 - np.exp(-tb / 0.004)))
        position += r.uniform(0.35, 1.05)
    texture = shaped_noise(n, 14, band(70, 220, 2, 3)) * 0.16 * smooth_envelope(n, 15, 2.5, 0.6)
    bass = body + 1.1 * thumps
    # Soft saturation: the 2nd and 3rd harmonics of a 30 Hz thump sit at 60 and 90 Hz, which laptop speakers and headphones can play.
    mix = 0.55 * np.tanh(1.7 * bass) + 0.45 * bass + crackle + texture
    return finish(mix * seam_dip(n))


def turbine(seconds=4.0):
    import numpy as np
    n = int(seconds * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    beat = 1.0 + 0.15 * np.sin(2 * np.pi * 0.5 * t + 0.7)
    hum = np.zeros(n)
    for k, (mult, amp, phase) in enumerate(((1, 0.85, 0.0), (2, 0.60, 1.3), (3, 0.38, 2.1), (4, 0.22, 0.4), (6, 0.10, 3.0))):
        hum += amp * np.sin(2 * np.pi * 25.0 * mult * t + phase + 0.25 * np.sin(2 * np.pi * 0.25 * t + k))
    hum *= beat
    bed = shaped_noise(n, 21, band(20, 60)) * 0.45 * smooth_envelope(n, 22, 0.8, 0.25)
    texture = shaped_noise(n, 23, band(90, 300, 2, 3)) * 0.11 * smooth_envelope(n, 24, 1.5, 0.5)
    bass = hum + bed
    mix = 0.5 * np.tanh(1.4 * bass) + 0.5 * bass + texture
    return finish(mix * seam_dip(n, 0.3, 0.12))


def finish(x):
    import numpy as np
    x = x - np.mean(x)
    x = x / np.max(np.abs(x)) * 0.89
    return x


def write_wav(path, x):
    import numpy as np
    data = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(data)


def blender_encode(wav, ogg):
    """Run inside a background Blender."""
    import aud
    aud.Sound.file(str(wav)).write(str(ogg), SAMPLE_RATE, aud.CHANNELS_MONO, aud.FORMAT_FLOAT32,
                                   aud.CONTAINER_OGG, aud.CODEC_VORBIS, BITRATE, 1024)
    if not Path(ogg).is_file():
        raise RuntimeError("Audaspace wrote no file")
    back = aud.Sound.file(str(ogg))
    pcm = back.data()
    if back.specs != (SAMPLE_RATE, 1) or pcm.shape[0] < SAMPLE_RATE:
        raise RuntimeError(f"unexpected encoded audio {back.specs} {pcm.shape}")


def report(name, x):
    import numpy as np
    spectrum = np.abs(np.fft.rfft(x)) ** 2
    freq = np.fft.rfftfreq(len(x), 1.0 / SAMPLE_RATE)
    total = spectrum.sum()
    def share(lo, hi):
        return spectrum[(freq >= lo) & (freq < hi)].sum() / total
    print(f"{name}: rms {np.sqrt(np.mean(x ** 2)):.3f} peak {np.max(np.abs(x)):.3f} "
          f"energy <70 Hz {share(0, 70):.2f}, 70-400 Hz {share(70, 400):.2f}, >400 Hz {share(400, 1e9):.3f}; "
          f"loop step {abs(x[0] - x[-1]):.4f}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--blender", type=Path, help="blender.exe, for the Ogg Vorbis encoding")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "mod/src/main/resources/assets/bwr/sounds")
    parser.add_argument("--encode", nargs=2, metavar=("WAV", "OGG"), help="(internal) encode inside Blender")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:])
    if args.encode:
        blender_encode(*map(Path, args.encode))
        return 0
    if not args.blender:
        parser.error("--blender is required")
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bwr-rumble-") as tmp:
        for name, signal in (("rumble_pool", pool()), ("rumble_turbine", turbine())):
            report(name, signal)
            wav = Path(tmp) / (name + ".wav")
            write_wav(wav, signal)
            target = args.out / (name + ".ogg")
            done = subprocess.run([str(args.blender), "--background", "--factory-startup", "--python-exit-code", "1",
                                   "--python", str(Path(__file__).resolve()), "--", "--encode", str(wav), str(target)],
                                  capture_output=True, text=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            if done.returncode:
                raise SystemExit(f"Blender failed:\n{done.stdout}\n{done.stderr}")
            print(f"{target.name}: {target.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
