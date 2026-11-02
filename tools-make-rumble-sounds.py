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
                                          and headphones reproduce it. NOT written by a default run: the shipped pool loop is the owner's
                                          recording (below). This synthesised one is the fallback, written by --synth-pool.
  rumble_turbine.ogg  4 s, mono, 48 kHz   a shaft train: 25 Hz (1,500 rpm) with its harmonics, slowly beating, over a 20-60 Hz noise
                                          bed and a 90-300 Hz mechanical texture.

The shipped rumble_pool.ogg (the suppression pool and relief valve rumble) is a recording supplied by the owner, made by

    py -3.13 tools-make-rumble-sounds.py --blender <blender.exe> --pool-from <file> [--pool-to <out.ogg>]

It reads any file Blender's Audaspace can decode (MP3, WAV, FLAC, Ogg...), mixes it to mono, resamples to 48 kHz, removes any DC offset, picks the
steadiest --pool-seconds (7 s) of it (--pool-start forces the start), makes it loop with an equal-power crossfade of --pool-fade seconds (1 s) of
its tail into its head, adds a little soft saturation (--pool-drive: the recording is almost all under 30 Hz, which laptop speakers and
headphones cannot play, so it is the harmonics at 60 to 300 Hz that they reproduce, as for the synthesised loops), low-passes it
(--pool-lowpass, 4th order, zero phase on the circular loop) so nothing is left above RumbleClientCheck's band, normalises the peak to -1 dBFS and
encodes a mono Ogg Vorbis. It prints the source length, the section used, the loop length, the spectrum split before and after the saturation and
low-pass, and the decoded file's loudness and loop step. The original file is never copied into the repository.
"""
import argparse
import json
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
    """Run inside a background Blender. The codec can overshoot a loud loop (a 0.89 peak decoding to over 1.0 would be clipped by the game), so
    if the decoded peak passes 0.98 the source is scaled down to bring it to 0.93 and encoded again."""
    import aud
    import numpy as np
    for attempt in range(6):
        aud.Sound.file(str(wav)).write(str(ogg), SAMPLE_RATE, aud.CHANNELS_MONO, aud.FORMAT_FLOAT32,
                                       aud.CONTAINER_OGG, aud.CODEC_VORBIS, BITRATE, 1024)
        if not Path(ogg).is_file():
            raise RuntimeError("Audaspace wrote no file")
        back = aud.Sound.file(str(ogg))
        pcm = back.data()
        if back.specs != (SAMPLE_RATE, 1) or pcm.shape[0] < SAMPLE_RATE:
            raise RuntimeError(f"unexpected encoded audio {back.specs} {pcm.shape}")
        peak = float(np.max(np.abs(pcm)))
        if peak <= 0.98:
            return
        with wave.open(str(wav), "rb") as w:
            source = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767.0
        write_wav(wav, source * (0.93 / peak))
    raise RuntimeError(f"the decoded peak of {ogg} stays over 0.98")


# ---------------------------------------------------------------------------------------------------- the owner's recording


def lowpass_circular(x, cutoff, order=4):
    """Zero-phase Butterworth-shaped low-pass of a loop, done on the whole loop as one period so that the seam stays seamless."""
    import numpy as np
    freq = np.fft.rfftfreq(len(x), 1.0 / SAMPLE_RATE)
    return np.fft.irfft(np.fft.rfft(x) / np.sqrt(1.0 + (freq / cutoff) ** (2 * order)), len(x))


def split(x):
    """Energy shares (10-70 Hz, 70-400 Hz, 400-1200 Hz, over 1200 Hz) of a whole signal: the bands of RumbleClientCheck, from 10 Hz."""
    import numpy as np
    spectrum = np.abs(np.fft.rfft(x)) ** 2
    freq = np.fft.rfftfreq(len(x), 1.0 / SAMPLE_RATE)
    spectrum = spectrum * (freq >= 10)
    total = spectrum.sum() + 1e-30
    return [float(spectrum[(freq >= lo) & (freq < hi)].sum() / total) for lo, hi in ((10, 70), (70, 400), (400, 1200), (1200, 1e9))]


def a_weighted_rms(x):
    """RMS of the signal after IEC A-weighting, a rough loudness (a 20 Hz rumble weighs 50 dB less than a 1 kHz tone)."""
    import numpy as np
    f = np.maximum(np.fft.rfftfreq(len(x), 1.0 / SAMPLE_RATE), 1e-3)
    ra = (12194.0 ** 2 * f ** 4) / ((f ** 2 + 20.6 ** 2) * np.sqrt((f ** 2 + 107.7 ** 2) * (f ** 2 + 737.9 ** 2)) * (f ** 2 + 12194.0 ** 2))
    y = np.fft.irfft(np.fft.rfft(x) * ra * 1.2589, len(x))
    return float(np.sqrt(np.mean(y ** 2)))


def make_loop(x, seconds, fade_s, start_s=None):
    """A seamless loop of `seconds` cut from x: the signal from the start for seconds + fade_s, whose last fade_s seconds are faded
    (equal power) into the first fade_s, so the sample after the loop's last is the true continuation of it. With start_s None the start
    is searched for over the steady part of x, by the flattest loudness (0.25 s windows) and the smallest bump or dip of loudness at the
    seam. Returns (loop, start_s)."""
    import numpy as np
    n, k = int(round(seconds * SAMPLE_RATE)), int(round(fade_s * SAMPLE_RATE))
    if len(x) < n + k:
        raise RuntimeError(f"the recording ({len(x) / SAMPLE_RATE:.1f} s) is shorter than --pool-seconds + --pool-fade ({(n + k) / SAMPLE_RATE:.1f} s)")
    ramp = np.linspace(0.0, 1.0, k)
    fade_in, fade_out = np.sin(ramp * np.pi / 2), np.cos(ramp * np.pi / 2)

    def build(a):
        seg = x[a:a + n + k]
        out = seg[:n].copy()
        out[:k] = seg[:k] * fade_in + seg[n:n + k] * fade_out
        return out

    def score(out):
        w = SAMPLE_RATE // 4
        r = np.sqrt(np.mean(out[:len(out) // w * w].reshape(-1, w) ** 2, axis=1))
        seam = np.sqrt(np.mean(out[:k] ** 2)) / (np.sqrt(np.mean(out[k:] ** 2)) + 1e-12)
        return float(np.std(r) / (np.mean(r) + 1e-12) + abs(np.log(seam)))

    if start_s is not None:
        a = int(round(start_s * SAMPLE_RATE))
        if a < 0 or a + n + k > len(x):
            raise RuntimeError("--pool-start leaves no room for the loop")
        return build(a), a / SAMPLE_RATE
    # Skip the first and last second (a recording usually fades in and out); score every 50 ms.
    lo, hi = SAMPLE_RATE, len(x) - n - k - SAMPLE_RATE
    if hi <= lo:
        lo, hi = 0, len(x) - n - k
    best = min(range(lo, hi + 1, SAMPLE_RATE // 20), key=lambda a: score(build(a)))
    return build(best), best / SAMPLE_RATE


def blender_pool(src, ogg, seconds, fade_s, start_s, cutoff, drive):
    """Run inside a background Blender: the owner's recording -> a mono 48 kHz seamless loop -> Ogg Vorbis. Prints one JSON line."""
    import aud
    import numpy as np
    sound = aud.Sound.file(str(src))
    rate, channels = sound.specs
    pcm = np.asarray(sound.data(), dtype=np.float64)
    x = pcm.mean(axis=1) if pcm.ndim == 2 else pcm
    source_seconds = len(x) / rate
    if rate != SAMPLE_RATE:  # band-limited (FFT) resampling of the whole clip
        m = int(round(len(x) * SAMPLE_RATE / rate))
        x = np.fft.irfft(np.fft.rfft(x), m) * (m / len(x))
    x = x - np.mean(x)
    if float(np.max(np.abs(x))) <= 1e-6:
        raise RuntimeError("the file is silent")
    loop, used_start = make_loop(x, seconds, fade_s, start_s)
    loop = loop - np.mean(loop)
    loop = loop / np.max(np.abs(loop))
    before = split(loop)
    if drive > 0:  # memoryless, so the seam stays seamless; half clean and half saturated so the deep body keeps its shape
        loop = 0.5 * loop + 0.5 * np.tanh(drive * loop) / np.tanh(drive)
    if cutoff > 0:
        loop = lowpass_circular(loop, cutoff)
    loop = loop - np.mean(loop)
    after = split(loop)
    loop = loop / np.max(np.abs(loop)) * 10 ** (-1 / 20)  # peak -1 dBFS
    ogg = Path(ogg)
    ogg.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bwr-rumble-pool-") as tmp:
        wav = Path(tmp) / "pool.wav"
        write_wav(wav, loop)
        blender_encode(wav, ogg)
    back = aud.Sound.file(str(ogg)).data()
    back = back[:, 0] if back.ndim == 2 else back
    steps = np.abs(np.diff(back))
    print("POOL " + json.dumps({
        "source": str(src), "source_rate": rate, "source_channels": channels, "source_seconds": round(source_seconds, 3),
        "start_s": round(used_start, 3), "loop_seconds": round(len(back) / SAMPLE_RATE, 3), "fade_s": fade_s, "lowpass_hz": cutoff, "drive": drive,
        "split_before": [round(v, 4) for v in before], "split_after": [round(v, 4) for v in split(back)], "split_loop": [round(v, 4) for v in after],
        "rms": round(float(np.sqrt(np.mean(back ** 2))), 4), "peak": round(float(np.max(np.abs(back))), 4),
        "a_weighted_rms": round(a_weighted_rms(back), 5),
        "wrap_step": round(float(abs(back[0] - back[-1])), 5), "largest_step_inside": round(float(steps.max()), 5),
        "bytes": ogg.stat().st_size}))


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


def run_blender(blender, *extra):
    done = subprocess.run([str(blender), "--background", "--factory-startup", "--python-exit-code", "1",
                           "--python", str(Path(__file__).resolve()), "--", *map(str, extra)],
                          capture_output=True, text=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if done.returncode:
        raise SystemExit(f"Blender failed:\n{done.stdout}\n{done.stderr}")
    return done.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--blender", type=Path, help="blender.exe, for the Ogg Vorbis encoding")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "mod/src/main/resources/assets/bwr/sounds")
    parser.add_argument("--pool-from", type=Path, metavar="FILE", help="convert this recording to rumble_pool.ogg (the shipped pool loop) instead of synthesising it")
    parser.add_argument("--pool-to", type=Path, metavar="OGG", help="where --pool-from writes (default: <out>/rumble_pool.ogg)")
    parser.add_argument("--pool-seconds", type=float, default=7.0, help="--pool-from: length of the loop, seconds (the game's check wants 3 to 8)")
    parser.add_argument("--pool-fade", type=float, default=1.0, help="--pool-from: seconds of the tail crossfaded (equal power) into the head")
    parser.add_argument("--pool-start", type=float, default=None, help="--pool-from: start of the section, seconds into the recording (default: the steadiest)")
    parser.add_argument("--pool-lowpass", type=float, default=600.0, help="--pool-from: low-pass cutoff, Hz (0 = none); RumbleClientCheck wants nothing above 1.2 kHz")
    parser.add_argument("--pool-drive", type=float, default=5.0, help="--pool-from: soft saturation drive (0 = none), which puts harmonics at 60-300 Hz")
    parser.add_argument("--synth-pool", action="store_true", help="also write the synthesised pool loop (the fallback; the shipped one is the converted recording)")
    parser.add_argument("--encode", nargs=2, metavar=("WAV", "OGG"), help="(internal) encode inside Blender")
    parser.add_argument("--pool-convert", nargs=2, metavar=("SRC", "OGG"), help="(internal) convert inside Blender")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:])
    if args.encode:
        blender_encode(*map(Path, args.encode))
        return 0
    if args.pool_convert:
        blender_pool(Path(args.pool_convert[0]), Path(args.pool_convert[1]), args.pool_seconds, args.pool_fade, args.pool_start, args.pool_lowpass, args.pool_drive)
        return 0
    if not args.blender:
        parser.error("--blender is required")
    if args.pool_from:
        if not args.pool_from.is_file():
            parser.error(f"{args.pool_from} is not a file")
        target = args.pool_to or args.out / "rumble_pool.ogg"
        out = run_blender(args.blender, "--pool-convert", args.pool_from, target, "--pool-seconds", args.pool_seconds, "--pool-fade", args.pool_fade,
                          *(("--pool-start", args.pool_start) if args.pool_start is not None else ()),
                          "--pool-lowpass", args.pool_lowpass, "--pool-drive", args.pool_drive)
        line = next((l for l in out.splitlines() if l.startswith("POOL ")), None)
        if line is None:
            raise SystemExit("the converter printed no result:\n" + out)
        info = json.loads(line[len("POOL "):])
        names = ("10-70 Hz", "70-400 Hz", "400-1200 Hz", ">1200 Hz")
        print(f"{target}: {info['loop_seconds']} s, {info['bytes']} bytes, peak {info['peak']}, rms {info['rms']}, A-weighted rms {info['a_weighted_rms']}, "
              f"wrap step {info['wrap_step']} (largest step inside {info['largest_step_inside']})")
        print(f"(source {info['source_seconds']} s, {info['source_rate']} Hz, {info['source_channels']} ch; section from {info['start_s']} s, "
              f"{info['fade_s']} s equal-power crossfade; saturation drive {info['drive']}, low-pass {info['lowpass_hz']} Hz)")
        for label, key in (("before saturation and low-pass", "split_before"), ("after, as encoded", "split_after")):
            print(f"energy {label}: " + ", ".join(f"{n} {v:.3f}" for n, v in zip(names, info[key])))
        return 0
    todo = (("rumble_turbine", turbine()),) + ((("rumble_pool", pool()),) if args.synth_pool else ())
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bwr-rumble-") as tmp:
        for name, signal in todo:
            report(name, signal)
            wav = Path(tmp) / (name + ".wav")
            write_wav(wav, signal)
            target = args.out / (name + ".ogg")
            run_blender(args.blender, "--encode", wav, target)
            print(f"{target.name}: {target.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
