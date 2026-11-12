#!/usr/bin/env python3
"""The synthesised annunciator horn, bwr:annunciator_horn_synth: the "Synthesised horn" an annunciator window can sound instead of the
panel alarm (CONTROL-PANELS.md, "Alarm sound").

    py -3.13 tools-make-annunciator-sounds.py --blender "C:/Program Files (x86)/Steam/steamapps/common/Blender/blender.exe"
    python3 tools-make-annunciator-sounds.py          (a Python that has Blender's own modules, pip's "bpy": no --blender needed)

Everything is generated here from sine waves with numpy: no recording, sample, noise or downloaded audio is used, and no random
number, so the signal is the same on every run. Like the rumbles (tools-make-rumble-sounds.py) the file is encoded to Ogg Vorbis
(mono, 48 kHz, 96 kbit/s) by Blender's Audaspace writer: in a background Blender given with --blender, or in this Python when it can
import Audaspace itself. SOUND-CREDITS.md records what was used for the shipped file.

The sound: a two-tone warble, a horn that swings between 600 Hz and 800 Hz (a fourth apart) two and a half times a second, with
four overtones that make it brassy. It is 1.2 s long, exactly three swings, and it is a loop: the pitch is one continuous phase that
advances a whole number of cycles over the file (840 of the fundamental), so the end meets the start in value and in slope, with no
click. The game loops it at a panel while a window there is audible (AnnunciatorHornClient).

The checks: this tool prints the level, the loop step and where the energy is, and after encoding decodes the file again to see that
it is mono 48 kHz, of the right length and not clipped; tools-audit-sounds.py audits the shipped file (mono 48 kHz Vorbis, 1 to
1.5 s, not silent) and RumbleClientCheck decodes it with Minecraft's own decoder.
"""
import argparse
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

SAMPLE_RATE = 48_000
BITRATE = 96_000
NAME = "annunciator_horn_synth"
SECONDS = 1.2
#: The two tones, Hz, and how often the horn swings from one to the other and back, Hz.
LOW_HZ = 600.0
HIGH_HZ = 800.0
SWING_HZ = 2.5
#: How sharply the pitch moves between the tones: 1 would be a siren's slow sweep, a large number a hard switch.
SWING_EDGE = 6.0
#: The overtones: the amplitude of the fundamental and of its 2nd to 5th harmonic.
HARMONICS = (1.0, 0.5, 0.3, 0.2, 0.1)
#: The peak of the signal. Not the 0.89 of the other tools: this stands in for the panel alarm, whose level is about -15 dBFS rms,
#: and a horn that loops beside it should be about as loud (this gives -14 dBFS rms; RumbleClientCheck wants a peak over 0.3).
PEAK = 0.36


def horn():
    """The signal, -1 to 1, SECONDS long. The pitch is the middle of the two tones moved up and down by a smoothed square wave; its
    phase is the running sum of the pitch, corrected by a hair so that it ends on a whole cycle exactly."""
    import numpy as np
    n = int(round(SECONDS * SAMPLE_RATE))
    t = np.arange(n) / SAMPLE_RATE
    swing = np.tanh(SWING_EDGE * np.sin(2.0 * np.pi * SWING_HZ * t)) / np.tanh(SWING_EDGE)
    hz = (HIGH_HZ + LOW_HZ) / 2.0 + (HIGH_HZ - LOW_HZ) / 2.0 * swing
    cycles = np.concatenate(([0.0], np.cumsum(hz) / SAMPLE_RATE))
    whole = round(cycles[-1])
    cycles = cycles + (whole - cycles[-1]) * np.arange(n + 1) / n
    phase = 2.0 * np.pi * cycles[:-1]
    x = np.zeros(n)
    for k, amplitude in enumerate(HARMONICS, start=1):
        x += amplitude * np.sin(k * phase)
    return finish(x)


def finish(x):
    import numpy as np
    x = x - np.mean(x)
    x = x / np.max(np.abs(x)) * PEAK
    return x


def write_wav(path, x):
    import numpy as np
    data = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(data)


def audaspace():
    """Blender's audio library: there inside Blender, and in a Python with Blender's modules once bpy is loaded. None elsewhere."""
    try:
        import aud
        return aud
    except ImportError:
        pass
    try:
        import bpy  # noqa: F401  (loading it makes Blender's own modules importable)
        import aud
        return aud
    except ImportError:
        return None


def audaspace_encode(wav, ogg):
    """Needs Audaspace (inside a background Blender, or a Python with Blender's modules). The codec can overshoot a loud loop (a 0.89
    peak decoding to over 1.0 would be clipped by the game), so if the decoded peak passes 0.98 the source is scaled down to bring it
    to 0.93 and encoded again. Returns what the encoded file decodes to: frames and peak."""
    import numpy as np
    aud = audaspace()
    if aud is None:
        raise RuntimeError("no Audaspace in this Python")
    for attempt in range(6):
        aud.Sound.file(str(wav)).write(str(ogg), SAMPLE_RATE, aud.CHANNELS_MONO, aud.FORMAT_FLOAT32,
                                       aud.CONTAINER_OGG, aud.CODEC_VORBIS, BITRATE, 1024)
        if not Path(ogg).is_file():
            raise RuntimeError("Audaspace wrote no file")
        back = aud.Sound.file(str(ogg))
        pcm = back.data()
        if back.specs != (SAMPLE_RATE, 1) or abs(pcm.shape[0] - SECONDS * SAMPLE_RATE) > 0.05 * SAMPLE_RATE:
            raise RuntimeError(f"unexpected encoded audio {back.specs} {pcm.shape}")
        peak = float(np.max(np.abs(pcm)))
        if peak <= 0.98:
            return pcm.shape[0], peak
        with wave.open(str(wav), "rb") as w:
            source = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767.0
        write_wav(wav, source * (0.93 / peak))
    raise RuntimeError(f"the decoded peak of {ogg} stays over 0.98")


def report(name, x):
    """The level, the loop, and where the energy is: nearly all of it must sit on the two tones and their overtones."""
    import numpy as np
    spectrum = np.abs(np.fft.rfft(x)) ** 2
    freq = np.fft.rfftfreq(len(x), 1.0 / SAMPLE_RATE)
    total = spectrum.sum()

    def share(lo, hi):
        return spectrum[(freq >= lo) & (freq < hi)].sum() / total
    step = abs(x[0] - x[-1])
    inside = float(np.max(np.abs(np.diff(x))))
    print(f"{name}: {len(x) / SAMPLE_RATE:.3f} s, rms {np.sqrt(np.mean(x ** 2)):.3f} peak {np.max(np.abs(x)):.3f}; "
          f"energy {LOW_HZ - 60:.0f}-{HIGH_HZ + 60:.0f} Hz {share(LOW_HZ - 60, HIGH_HZ + 60):.2f}, "
          f"overtones up to {5 * HIGH_HZ + 300:.0f} Hz {share(HIGH_HZ + 60, 5 * HIGH_HZ + 300):.2f}, above {share(5 * HIGH_HZ + 300, 1e9):.4f}; "
          f"loop step {step:.4f} (largest step inside {inside:.4f})")
    if step > inside * 1.05:
        raise SystemExit(f"{name}: the loop has a step at its seam ({step:.4f} against {inside:.4f} inside)")
    if share(LOW_HZ - 60, 5 * HIGH_HZ + 300) < 0.99:
        raise SystemExit(f"{name}: the energy is not on the two tones and their overtones")


def run_blender(blender, *extra):
    done = subprocess.run([str(blender), "--background", "--factory-startup", "--python-exit-code", "1",
                           "--python", str(Path(__file__).resolve()), "--", *map(str, extra)],
                          capture_output=True, text=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if done.returncode:
        raise SystemExit(f"Blender failed:\n{done.stdout}\n{done.stderr}")
    return done.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--blender", type=Path, help="blender.exe, for the Ogg Vorbis encoding (not needed where this Python has Audaspace)")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "mod/src/main/resources/assets/bwr/sounds")
    parser.add_argument("--encode", nargs=2, metavar=("WAV", "OGG"), help="(internal) encode inside Blender")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else None)
    if args.encode:
        frames, peak = audaspace_encode(*map(Path, args.encode))
        print(f"decoded again: {frames} frames, peak {peak:.3f}")
        return 0
    if not args.blender and audaspace() is None:
        parser.error("--blender is required: this Python has no Audaspace of its own")
    signal = horn()
    report(NAME, signal)
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bwr-annunciator-") as tmp:
        wav = Path(tmp) / (NAME + ".wav")
        write_wav(wav, signal)
        target = args.out / (NAME + ".ogg")
        if args.blender:
            print(run_blender(args.blender, "--encode", wav, target).strip().splitlines()[-1])
        else:
            frames, peak = audaspace_encode(wav, target)
            print(f"decoded again: {frames} frames, peak {peak:.3f}")
        print(f"{target.name}: {target.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
