#!/usr/bin/env python3
"""Synthesise the turbine accident sounds of the plant (TURBINE-MAINTENANCE.md, "Sounds") and encode them to Ogg Vorbis.

    py -3.13 tools-make-turbine-sounds.py --blender "C:/Program Files (x86)/Steam/steamapps/common/Blender/blender.exe"

Everything is generated here from seeded noise and sine waves with numpy: no recording, sample or downloaded audio is used. Encoding is
done by a separate headless Blender through Audaspace (Blender ships FFmpeg), exactly as tools-make-rumble-sounds.py does; the script never
touches a scene or a running Blender.

One-shots (played once, from the server, at a place):

  turbine_missile_bang.ogg      1.9 s  a blade or disc thrown out through the casing: a gunshot-sharp metallic report (a 0.6 ms crack, a
                                       body and a low boom), a ringing steel tail (a plate's inharmonic modes), hall echoes and a brief
                                       debris clatter.
  turbine_missile_impact.ogg    1.4 s  the same piece striking where it stops: a thud, a short clang, scattered debris.
  turbine_excursion.ogg         9.6 s  the shaft excursion: a rotor half tearing out of the casing (a grinding steel screech and scraping),
                                       a big low boom, a rumble and scraping that slow as it rolls to rest, a final thud and settling.
                                       NOT written by a default run: the shipped excursion sound is the owner's recording, made by
                                       --excursion-from below. This synthesised one is the fallback, written by --synth-excursion.

The bang and the excursion also have two low-passed variants each, <name>_distant.ogg and <name>_muffled.ogg: the game plays the clear one
near the source in the open, the muffled one (a deep thud, the highs gone) behind walls and the distant one far away, and crossfades them
(TURBINE-MAINTENANCE.md, "Sounds"). The low-pass is a zero-phase Butterworth-shaped filter of 2nd or 4th order: bang distant 1.1 kHz,
bang muffled 420 Hz; excursion distant 2.4 kHz (gentler: a far excursion keeps some of its character out to 8 km), excursion muffled 380 Hz.

Pseudo-loops (the server plays one every second, each overlapping the last by 0.6 s; both ends fade with equal-power curves, and the
material is noise, so two copies add to a steady level instead of pulsing):

  turbine_overspeed_whine.ogg   1.6 s  the blade-passing whine of the last stage (25 Hz shaft x 60 blades = 1.5 kHz) and its harmonics, as
                                       narrow bands of noise round a weak pure tone. The game raises its pitch with the shaft speed.
  turbine_fire_oil_a/b.ogg      1.6 s  a deep oil-fire roar: a 60-600 Hz body that flutters, low whoomphs and a crackle (two variants).
  turbine_fire_hydrogen_a/b.ogg 1.6 s  a hydrogen fire's hiss-roar: a 700-7000 Hz jet, a 80-300 Hz roar under it, little crackle (two variants).

The converter step (a supplied recording, never committed with the mod unless its owner allows it):

    py -3.13 tools-make-turbine-sounds.py --blender <blender.exe> --excursion-from <file> [--excursion-to <out.ogg>]

reads any file Blender's Audaspace can decode (MP3, WAV, FLAC, Ogg...), mixes it to mono, resamples to 48 kHz, strips silence from both
ends (below --trim-db), cuts it to --max-seconds with a fade-out, removes any DC offset, normalises the peak to -1 dBFS, and encodes it as a
mono Ogg Vorbis, then makes its two low-passed variants. It writes <out dir>/turbine_excursion.ogg, turbine_excursion_distant.ogg and
turbine_excursion_muffled.ogg (or, with --excursion-to, that file and its _distant and _muffled neighbours) and prints each one's duration
and peak. With --excursion-from, nothing is synthesised.
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
#: Pseudo-loops: the length of each play and of its fade at each end; the server repeats every PERIOD_S (TurbineSounds.PERIOD_TICKS).
LOOP_S, FADE_S, PERIOD_S = 1.6, 0.6, 1.0
#: The decoded file must not pass this (the game would clip it); a file that does is scaled to PEAK_TARGET and encoded again.
PEAK_LIMIT, PEAK_TARGET = 0.98, 0.93


def rng(seed):
    import numpy as np
    return np.random.default_rng(seed)


def shaped_noise(n, seed, curve):
    """Noise whose amplitude spectrum follows curve(freq_hz); unit RMS."""
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


def narrow(centre, width):
    """A bell of noise round a frequency (a formant): -3 dB at +-width/2."""
    import numpy as np

    def curve(f):
        return 1.0 / np.sqrt(1.0 + ((f - centre) / (width / 2)) ** 4)
    return curve


def smooth_envelope(n, seed, cutoff_hz, depth):
    """Slow random gain in [1 - depth, 1 + depth]."""
    import numpy as np
    x = shaped_noise(n, seed, lambda f: 1.0 / np.sqrt(1.0 + (f / cutoff_hz) ** 4))
    return 1.0 + depth * np.clip(x / 2.5, -1, 1)


def place(buf, start_s, chunk):
    """Add chunk to buf at start_s seconds; whatever falls off the end is dropped."""
    s = int(round(start_s * SAMPLE_RATE))
    if s >= len(buf) or s < 0:
        return
    m = min(len(chunk), len(buf) - s)
    buf[s:s + m] += chunk[:m]


def tt(length_s):
    import numpy as np
    return np.arange(int(round(length_s * SAMPLE_RATE))) / SAMPLE_RATE


def ring(length_s, freqs, decays, amps, seed):
    """Damped sines at the given frequencies (a struck steel plate's modes, which are not harmonic), a 0.3 ms attack."""
    import numpy as np
    t = tt(length_s)
    r = rng(seed)
    out = np.zeros(len(t))
    for f, d, a in zip(freqs, decays, amps):
        out += a * np.sin(2 * np.pi * f * t + r.uniform(0, 6.283)) * np.exp(-t / d)
    return out * (1 - np.exp(-t / 0.0003))


def noise_burst(length_s, seed, lo, hi, tau, attack=0.0003):
    import numpy as np
    t = tt(length_s)
    return shaped_noise(len(t), seed, band(lo, hi, 2, 3)) * np.exp(-t / tau) * (1 - np.exp(-t / attack))


def thump(length_s, f_hi, f_lo, sweep_tau, tau):
    """A falling sine: the low report of a heavy blow."""
    import numpy as np
    t = tt(length_s)
    freq = f_lo + (f_hi - f_lo) * np.exp(-t / sweep_tau)
    phase = 2 * np.pi * np.cumsum(freq) / SAMPLE_RATE
    return np.sin(phase) * np.exp(-t / tau) * (1 - np.exp(-t / 0.002))


def soft_clip(x, drive):
    import numpy as np
    return np.tanh(drive * x) / np.tanh(drive)


#: The low-pass of the two variants of a long-range sound: (cutoff Hz, order). "distant" is air absorbing the highs; "muffled" is a wall.
LOWPASS = {
    "turbine_missile_bang": {"distant": (1100.0, 2), "muffled": (420.0, 4)},
    "turbine_excursion": {"distant": (2400.0, 2), "muffled": (380.0, 4)},
}


def lowpass(x, cutoff, order):
    """Zero-phase low-pass: the magnitude of a Butterworth filter applied in the frequency domain (no ringing before the attack)."""
    import numpy as np
    n = len(x)
    pad = SAMPLE_RATE // 2  # room for the filter's tail
    spectrum = np.fft.rfft(np.concatenate([x, np.zeros(pad)]))
    freq = np.fft.rfftfreq(n + pad, 1.0 / SAMPLE_RATE)
    spectrum *= 1.0 / np.sqrt(1.0 + (freq / cutoff) ** (2 * order))
    return np.fft.irfft(spectrum, n + pad)[:n]


def variant(x, cutoff, order):
    """The low-passed version of x: its loudness is brought back to 85 % of the original's RMS (a dull sound is not a quiet one; the game
    lowers the gain of an occluded or distant sound itself), without passing 0.95."""
    import numpy as np
    y = lowpass(x, cutoff, order)
    k = int(0.12 * SAMPLE_RATE)
    y[-k:] *= np.cos(np.linspace(0, np.pi / 2, k)) ** 2
    scale = min(0.85 * np.sqrt(np.mean(x ** 2)) / (np.sqrt(np.mean(y ** 2)) + 1e-12), 0.95 / (np.max(np.abs(y)) + 1e-12))
    return y * scale


def variants(name, x):
    return [(f"{name}_{kind}", variant(x, *LOWPASS[name][kind])) for kind in ("distant", "muffled")]


def finish(x, peak=0.93, tail_s=0.12):
    """No DC, a short fade at the very end (the tail is a decay already), the peak at `peak`."""
    import numpy as np
    x = x - np.mean(x)
    k = int(tail_s * SAMPLE_RATE)
    x[-k:] *= np.cos(np.linspace(0, np.pi / 2, k)) ** 2
    return x / np.max(np.abs(x)) * peak


def equal_power(x, fade_s=FADE_S):
    """Equal-power fade in and out: two overlapping copies of incoherent material add to a constant power."""
    import numpy as np
    k = int(round(fade_s * SAMPLE_RATE))
    ramp = np.linspace(0.0, 1.0, k)
    x = x.copy()
    x[:k] *= np.sin(ramp * np.pi / 2)
    x[-k:] *= np.cos(ramp * np.pi / 2)
    return x


# ---------------------------------------------------------------------------------------------------- one-shots


def missile_bang(seconds=1.9):
    import numpy as np
    n = int(seconds * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    mix = np.zeros(n)
    # The crack: a fraction of a millisecond of broadband noise, the transient that makes it a gunshot.
    crack = shaped_noise(n, 101, band(1500, 16000, 2, 1)) * np.exp(-t / 0.0006) * (1 - np.exp(-t / 0.00006))
    mix += 2.2 * crack
    # The report's body and the low boom behind it.
    mix += 1.1 * noise_burst(seconds, 102, 220, 4200, 0.012, 0.0002)
    mix += 1.0 * thump(seconds, 125, 46, 0.03, 0.10)
    # The steel ringing: a plate's inharmonic modes with their own decays, a hollow low pair under them.
    modes = [910, 1447, 1947, 2093, 2412, 2657, 3380, 4112, 5230, 6840]
    decays = [0.50, 0.40, 0.30, 0.27, 0.22, 0.19, 0.16, 0.12, 0.09, 0.06]
    r = rng(103)
    amps = [0.50 * (0.82 ** i) * r.uniform(0.7, 1.2) for i in range(len(modes))]
    mix += ring(seconds, modes, decays, amps, 104)
    mix += 0.35 * ring(seconds, [372, 618], [0.32, 0.24], [1.0, 0.7], 105)
    # The hall: a diffuse tail and three slapback echoes of the crack, each duller than the last.
    mix += 0.17 * np.concatenate([np.zeros(int(0.025 * SAMPLE_RATE)),
                                  shaped_noise(n - int(0.025 * SAMPLE_RATE), 106, band(150, 2400)) * np.exp(-t[:n - int(0.025 * SAMPLE_RATE)] / 0.26)])
    for k, (delay, amp, hi) in enumerate(((0.105, 0.30, 4200), (0.19, 0.20, 3000), (0.33, 0.13, 2200))):
        place(mix, delay, amp * noise_burst(0.12, 110 + k, 300, hi, 0.018, 0.0005))
    # The debris clatter: small pieces of steel and stone falling, spacing slowly widening, each quieter than the last.
    gap, when = 0.035, 0.20
    for k in range(15):
        f = r.uniform(1800, 7200)
        a = 0.34 * (0.90 ** k) * r.uniform(0.5, 1.0)
        piece = ring(0.12, [f, f * r.uniform(1.4, 1.8)], [r.uniform(0.012, 0.05)] * 2, [1.0, 0.6], int(r.integers(1, 10 ** 6)))
        place(mix, when, a * piece)
        place(mix, when, 0.5 * a * noise_burst(0.03, int(r.integers(1, 10 ** 6)), 800, 6000, 0.004, 0.0002))
        when += gap
        gap *= 1.13 * r.uniform(0.8, 1.25)
        if when > 1.2:
            break
    # Drive: the tanh squares off the crack into a bang and adds the harmonics small speakers need for the boom.
    return finish(soft_clip(mix * 0.6, 2.2), 0.95, 0.2)


def missile_impact(seconds=1.4):
    import numpy as np
    n = int(seconds * SAMPLE_RATE)
    mix = np.zeros(n)
    mix += 1.0 * thump(seconds, 150, 66, 0.025, 0.07)
    mix += 1.2 * noise_burst(seconds, 121, 280, 3000, 0.010, 0.0003)
    mix += 0.9 * noise_burst(seconds, 122, 1500, 9000, 0.0015, 0.00008)
    modes = [640, 1010, 1380, 1860, 2640, 3500]
    mix += ring(seconds, modes, [0.34, 0.30, 0.24, 0.19, 0.14, 0.10], [0.55, 0.45, 0.35, 0.28, 0.2, 0.12], 123)
    r = rng(124)
    when, gap = 0.13, 0.05
    for k in range(9):
        f = r.uniform(1500, 6000)
        piece = ring(0.1, [f, f * 1.5], [r.uniform(0.01, 0.04)] * 2, [1.0, 0.5], int(r.integers(1, 10 ** 6)))
        place(mix, when, 0.26 * (0.86 ** k) * r.uniform(0.5, 1.0) * piece)
        when += gap
        gap *= 1.15 * r.uniform(0.8, 1.25)
    return finish(soft_clip(mix * 0.6, 1.8), 0.92, 0.18)


def excursion(seconds=9.6):
    import numpy as np
    n = int(seconds * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    mix = np.zeros(n)

    def smoothstep(x):
        x = np.clip(x, 0, 1)
        return x * x * (3 - 2 * x)

    # 1. The tear-out: a grinding steel screech, steel on steel, with stick-slip (the gate) and a rough 110 Hz chatter, 0 to 2.8 s.
    f0 = 600 + 560 * smoothstep(t / 2.4)
    f0 = f0 * (1 + 0.018 * np.sin(2 * np.pi * 6.3 * t + 4 * smooth_envelope(n, 201, 3, 1.0)))
    phase = 2 * np.pi * np.cumsum(f0) / SAMPLE_RATE
    tone = sum(a * np.sin(d * phase + p) for d, a, p in ((1.0, 1.0, 0.0), (1.013, 0.8, 1.1), (1.504, 0.5, 2.0), (2.01, 0.35, 0.5), (2.71, 0.25, 4.0)))
    stick = np.clip(0.55 + 0.9 * shaped_noise(n, 202, lambda f: 1.0 / np.sqrt(1.0 + (f / 22.0) ** 4)) / 2.2, 0, 1) ** 0.8
    chatter = 0.55 + 0.45 * np.sign(np.sin(2 * np.pi * 110 * t + 2 * smooth_envelope(n, 203, 8, 1.0)))
    screech_env = (1 - np.exp(-t / 0.10)) * np.clip((2.9 - t) / 1.3, 0, 1)
    mix += 0.30 * tone * stick * chatter * screech_env
    grind_env = (1 - np.exp(-t / 0.05)) * np.clip((6.8 - t) / 2.5, 0, 1) * (0.35 + 0.65 * np.exp(-t / 2.2))
    rough = 0.6 + 0.4 * np.sin(2 * np.pi * 55 * t + 3 * np.sin(2 * np.pi * 3 * t))
    mix += 0.55 * shaped_noise(n, 204, band(180, 1800)) * rough * grind_env
    scrape_gate = np.clip(0.4 + shaped_noise(n, 205, lambda f: 1.0 / np.sqrt(1.0 + (f / 14.0) ** 4)) / 2.5, 0, 1)
    mix += 0.20 * shaped_noise(n, 206, band(2200, 7500, 2, 3)) * scrape_gate * grind_env * np.clip(t / 0.4, 0, 1)

    # 2. The big low boom as the end of the casing gives way, at 0.55 s: a crack, a sweep down through 80 Hz, a deep tail.
    place(mix, 0.55, 0.9 * noise_burst(0.5, 207, 150, 5000, 0.012, 0.0003))
    place(mix, 0.55, 1.7 * thump(2.6, 85, 33, 0.35, 0.60))
    place(mix, 0.55, 1.0 * noise_burst(2.4, 208, 25, 170, 0.62, 0.006))
    place(mix, 0.55, 0.7 * noise_burst(1.0, 209, 250, 1400, 0.16, 0.002))

    # 3. The roll, 1.0 to 7.4 s: a deep rumble that pulses once a turn, the turns slowing from 3.2 to 0.5 a second, with the floor scraping.
    rate = 0.5 + 2.7 * np.exp(-(t - 1.0).clip(0) / 2.6)
    turn = 2 * np.pi * np.cumsum(rate) / SAMPLE_RATE
    roll_env = smoothstep((t - 1.0) / 0.7) * np.exp(-(t - 1.0).clip(0) / 3.6) * np.clip((7.6 - t) / 0.9, 0, 1)
    rumble = shaped_noise(n, 210, band(22, 115)) * (0.62 + 0.38 * np.sin(turn))
    rumble += 0.5 * np.sin(2 * np.pi * 31 * t + 0.4 * np.sin(turn)) + 0.3 * np.sin(2 * np.pi * 47 * t + 0.6 * np.sin(turn + 1))
    mix += 0.95 * rumble * roll_env
    rub = np.clip(0.5 + 0.5 * np.sin(turn - 0.8), 0, 1) ** 2
    mix += 0.30 * shaped_noise(n, 211, band(260, 2600)) * (0.25 + 0.75 * rub) * roll_env * np.clip(1.0 - (t - 1.0) / 8.0, 0, 1)

    # 4. What it knocks through on the way: walls and machinery, heavy blows with steel ringing and stone crumbling, getting fewer.
    r = rng(212)
    for when, amp in ((0.95, 0.80), (1.55, 0.60), (2.35, 0.55), (3.30, 0.40), (4.60, 0.28)):
        base = r.uniform(170, 330)
        place(mix, when, amp * thump(0.5, 140, 55, 0.02, 0.07))
        place(mix, when, amp * ring(1.6, [base, base * 1.58, base * 2.2, base * 3.1], [0.7, 0.5, 0.35, 0.25], [0.5, 0.4, 0.3, 0.2], int(r.integers(1, 10 ** 6))))
        place(mix, when, amp * 0.8 * noise_burst(0.7, int(r.integers(1, 10 ** 6)), 100, 1800, 0.18, 0.001))

    # 5. The rotor comes to rest: a final heavy thud at 7.55 s, a low clang, and the debris settling to 9.3 s.
    place(mix, 7.55, 1.5 * thump(1.4, 78, 36, 0.12, 0.20))
    place(mix, 7.55, 0.9 * noise_burst(1.0, 213, 30, 320, 0.28, 0.002))
    place(mix, 7.55, 0.30 * ring(1.8, [210, 330, 540, 820], [0.9, 0.7, 0.5, 0.35], [1.0, 0.7, 0.5, 0.3], 214))
    when, gap = 7.85, 0.07
    for k in range(12):
        f = r.uniform(900, 4800)
        piece = ring(0.12, [f, f * 1.45], [r.uniform(0.012, 0.06)] * 2, [1.0, 0.5], int(r.integers(1, 10 ** 6)))
        place(mix, when, 0.30 * (0.84 ** k) * r.uniform(0.5, 1.0) * piece)
        when += gap
        gap *= 1.17 * r.uniform(0.8, 1.2)

    return finish(soft_clip(mix * 0.5, 1.6), 0.93, 0.25)


# ---------------------------------------------------------------------------------------------------- pseudo-loops


def crackle(n, seed, rate_hz, lo, hi, amp, tau_range=(0.003, 0.010)):
    """Sparse random clicks, each a short burst of band-limited noise."""
    import numpy as np
    r = rng(seed)
    out = np.zeros(n)
    count = int(rate_hz * n / SAMPLE_RATE)
    for _ in range(count):
        s = int(r.integers(0, n - 800))
        c = noise_burst(0.02, int(r.integers(1, 10 ** 6)), lo * r.uniform(0.7, 1.4), hi * r.uniform(0.7, 1.2), r.uniform(*tau_range), 0.0002)
        out[s:s + len(c)] += amp * r.uniform(0.25, 1.0) * c[:min(len(c), n - s)]
    return out


def overspeed_whine():
    import numpy as np
    n = int(LOOP_S * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    mix = np.zeros(n)
    # The blade-passing whine at rated speed: 25 Hz x 60 blades = 1.5 kHz, its harmonics, and 25 Hz sidebands. Narrow bands of noise
    # carry it (two copies cross-fading must not beat), a weak pure tone gives it the pitch.
    for k, (mult, width, amp) in enumerate(((1.0, 38, 1.0), (2.0, 55, 0.55), (3.0, 80, 0.30), (0.5, 30, 0.35), (4.0, 110, 0.14))):
        mix += amp * shaped_noise(n, 301 + k, narrow(1500.0 * mult, width)) * smooth_envelope(n, 311 + k, 3.0, 0.35)
    mix += 0.20 * np.sin(2 * np.pi * 1500.0 * t + 0.6 * np.sin(2 * np.pi * 25 * t)) * (1 + 0.1 * np.sin(2 * np.pi * 25 * t))
    mix += 0.10 * np.sin(2 * np.pi * 3000.0 * t + 0.8 * np.sin(2 * np.pi * 25 * t))
    # A little steam hiss under it, and the 25 Hz shaft rate.
    mix += 0.10 * shaped_noise(n, 320, band(1800, 7000, 2, 2))
    mix += 0.10 * np.sin(2 * np.pi * 25 * t + 1.0)
    return equal_power(finish(mix, 0.85, 0.01))


def fire_oil(seed):
    import numpy as np
    n = int(LOOP_S * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    flutter = smooth_envelope(n, seed + 1, 7.0, 0.5) * smooth_envelope(n, seed + 2, 1.6, 0.3)
    roar = shaped_noise(n, seed + 3, band(55, 620, 2, 2)) * flutter
    # Whoomphs: slow swells of deep noise, a few a second.
    swells = np.clip(shaped_noise(n, seed + 4, band(2, 9, 2, 3)) / 2.0, -0.2, 1.0) + 0.35
    whoomph = shaped_noise(n, seed + 5, band(28, 130)) * swells
    body = 0.6 * roar + 0.8 * whoomph
    mid = 0.22 * shaped_noise(n, seed + 6, band(600, 2800, 2, 2)) * smooth_envelope(n, seed + 7, 9.0, 0.6)
    crack = crackle(n, seed + 8, 16, 1200, 5200, 0.55) + crackle(n, seed + 9, 3, 250, 900, 0.9, (0.012, 0.03))
    mix = soft_clip(body * 0.7, 1.5) + mid + 0.45 * crack
    return equal_power(finish(mix, 0.86, 0.01))


def fire_hydrogen(seed):
    import numpy as np
    n = int(LOOP_S * SAMPLE_RATE)
    jet = shaped_noise(n, seed + 1, band(700, 7000, 2, 1)) * smooth_envelope(n, seed + 2, 11.0, 0.22)
    jet *= smooth_envelope(n, seed + 3, 1.2, 0.18)
    roar = shaped_noise(n, seed + 4, band(80, 320, 2, 2)) * smooth_envelope(n, seed + 5, 5.0, 0.4)
    blow = shaped_noise(n, seed + 6, narrow(210.0, 40)) * smooth_envelope(n, seed + 7, 4.0, 0.5)
    crack = crackle(n, seed + 8, 3, 1500, 6000, 0.35)
    mix = 0.75 * jet + 0.85 * roar + 0.35 * blow + crack
    return equal_power(finish(soft_clip(mix * 0.5, 1.4), 0.86, 0.01))


def sounds(synth_excursion=False):
    """(name, samples) of everything the default run writes. The excursion is only synthesised on request (the fallback)."""
    out = []
    bang = missile_bang()
    out += [("turbine_missile_bang", bang)] + variants("turbine_missile_bang", bang)
    out += [("turbine_missile_impact", missile_impact())]
    if synth_excursion:
        e = excursion()
        out += [("turbine_excursion", e)] + variants("turbine_excursion", e)
    out += [("turbine_overspeed_whine", overspeed_whine()),
            ("turbine_fire_oil_a", fire_oil(400)), ("turbine_fire_oil_b", fire_oil(500)),
            ("turbine_fire_hydrogen_a", fire_hydrogen(600)), ("turbine_fire_hydrogen_b", fire_hydrogen(700))]
    return out


# ---------------------------------------------------------------------------------------------------- files


def write_wav(path, x):
    import numpy as np
    data = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(data)


def blender_encode(wav, ogg):
    """Run inside a background Blender. Returns the decoded samples. The codec overshoots a sharp attack (a 0.95 peak can decode to 1.2, which
    the game would clip), so if the decoded peak passes PEAK_LIMIT the source is scaled down to bring it to PEAK_TARGET and encoded again."""
    import aud
    import numpy as np
    for attempt in range(6):
        aud.Sound.file(str(wav)).write(str(ogg), SAMPLE_RATE, aud.CHANNELS_MONO, aud.FORMAT_FLOAT32,
                                       aud.CONTAINER_OGG, aud.CODEC_VORBIS, BITRATE, 1024)
        if not Path(ogg).is_file():
            raise RuntimeError("Audaspace wrote no file")
        back = aud.Sound.file(str(ogg))
        pcm = back.data()
        if back.specs != (SAMPLE_RATE, 1) or pcm.shape[0] < SAMPLE_RATE // 2:
            raise RuntimeError(f"unexpected encoded audio {back.specs} {pcm.shape}")
        decoded = pcm[:, 0] if pcm.ndim == 2 else pcm
        peak = float(np.max(np.abs(decoded)))
        if peak <= PEAK_LIMIT:
            return decoded
        with wave.open(str(wav), "rb") as w:
            source = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767.0
        write_wav(wav, source * (PEAK_TARGET / peak))
    raise RuntimeError(f"the decoded peak of {ogg} stays over {PEAK_LIMIT}")


def blender_convert(src, ogg, trim_db, max_seconds):
    """Run inside a background Blender: any file Audaspace reads -> mono 48 kHz, trimmed, normalised, Ogg Vorbis. Prints one JSON line."""
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
    peak = float(np.max(np.abs(x))) if len(x) else 0.0
    if peak <= 1e-6:
        raise RuntimeError("the file is silent")
    # Strip silence at both ends: the first and last 10 ms windows whose RMS is above trim_db relative to full scale.
    win = SAMPLE_RATE // 100
    k = len(x) // win
    rms = np.sqrt(np.mean((x[:k * win] / peak).reshape(k, win) ** 2, axis=1))
    loud = np.nonzero(rms > 10 ** (trim_db / 20))[0]
    if len(loud):
        x = x[max(0, (loud[0] - 1) * win): min(len(x), (loud[-1] + 2) * win)]
    limit = int(max_seconds * SAMPLE_RATE)
    cut = len(x) > limit
    x = x[:limit]
    a, z = int(0.003 * SAMPLE_RATE), int((0.25 if cut else 0.05) * SAMPLE_RATE)
    x[:a] *= np.linspace(0, 1, a)
    x[-z:] *= np.cos(np.linspace(0, np.pi / 2, z)) ** 2
    x = x / np.max(np.abs(x)) * 10 ** (-1 / 20)  # peak -1 dBFS
    ogg = Path(ogg)
    results = []
    with tempfile.TemporaryDirectory(prefix="bwr-turbine-conv-") as tmp:
        for kind, signal in (("clear", x), ("distant", variant(x, *LOWPASS["turbine_excursion"]["distant"])),
                             ("muffled", variant(x, *LOWPASS["turbine_excursion"]["muffled"]))):
            target = ogg if kind == "clear" else ogg.with_name(ogg.stem + "_" + kind + ".ogg")
            wav = Path(tmp) / (kind + ".wav")
            write_wav(wav, signal)
            back = blender_encode(wav, target)
            results.append({"kind": kind, "file": str(target), "seconds": round(len(back) / SAMPLE_RATE, 3), "peak": round(float(np.max(np.abs(back))), 4),
                            "peak_dbfs": round(20 * float(np.log10(np.max(np.abs(back)))), 2),
                            "rms_dbfs": round(20 * float(np.log10(np.sqrt(np.mean(back ** 2)))), 2), "bytes": target.stat().st_size})
    print("CONVERT " + json.dumps({"source": str(src), "source_rate": rate, "source_channels": channels, "source_seconds": round(source_seconds, 3),
                                   "trimmed_to_max": bool(cut), "files": results}))


def report(name, x):
    import numpy as np
    spectrum = np.abs(np.fft.rfft(x)) ** 2
    freq = np.fft.rfftfreq(len(x), 1.0 / SAMPLE_RATE)
    total = spectrum.sum()

    def share(lo, hi):
        return spectrum[(freq >= lo) & (freq < hi)].sum() / total
    a = np.abs(x)
    peak_at = int(np.argmax(a)) / SAMPLE_RATE
    print(f"{name}: {len(x) / SAMPLE_RATE:.2f} s rms {np.sqrt(np.mean(x ** 2)):.3f} peak {a.max():.3f} at {peak_at * 1000:.1f} ms; "
          f"energy <120 Hz {share(0, 120):.2f}, 120-1200 Hz {share(120, 1200):.2f}, 1.2-6 kHz {share(1200, 6000):.2f}, >6 kHz {share(6000, 1e9):.3f}")


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
    parser.add_argument("--excursion-from", type=Path, metavar="FILE", help="convert this recording to turbine_excursion.ogg instead of synthesising anything")
    parser.add_argument("--excursion-to", type=Path, metavar="OGG", help="where --excursion-from writes (default: <out>/turbine_excursion.ogg; the _distant and _muffled variants go beside it)")
    parser.add_argument("--synth-excursion", action="store_true", help="also write the synthesised excursion and its variants (the fallback; the shipped one is the converted recording)")
    parser.add_argument("--trim-db", type=float, default=-60.0, help="--excursion-from: strip leading and trailing audio quieter than this (dB re the clip's peak)")
    parser.add_argument("--max-seconds", type=float, default=12.0, help="--excursion-from: cut the clip to this length, with a fade-out")
    parser.add_argument("--encode", nargs=2, metavar=("WAV", "OGG"), help="(internal) encode inside Blender")
    parser.add_argument("--convert", nargs=2, metavar=("SRC", "OGG"), help="(internal) convert inside Blender")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:])
    if args.encode:
        blender_encode(*map(Path, args.encode))
        return 0
    if args.convert:
        blender_convert(Path(args.convert[0]), Path(args.convert[1]), args.trim_db, args.max_seconds)
        return 0
    if not args.blender:
        parser.error("--blender is required")
    if args.excursion_from:
        if not args.excursion_from.is_file():
            parser.error(f"{args.excursion_from} is not a file")
        target = args.excursion_to or args.out / "turbine_excursion.ogg"
        target.parent.mkdir(parents=True, exist_ok=True)
        out = run_blender(args.blender, "--convert", args.excursion_from, target, "--trim-db", args.trim_db, "--max-seconds", args.max_seconds)
        line = next((l for l in out.splitlines() if l.startswith("CONVERT ")), None)
        if line is None:
            raise SystemExit("the converter printed no result:\n" + out)
        info = json.loads(line[len("CONVERT "):])
        for f in info["files"]:
            print(f"{f['file']}: {f['seconds']} s, peak {f['peak']} ({f['peak_dbfs']} dBFS), rms {f['rms_dbfs']} dBFS, {f['bytes']} bytes")
        print(f"(source {info['source_seconds']} s, {info['source_rate']} Hz, {info['source_channels']} ch{'; cut to --max-seconds' if info['trimmed_to_max'] else ''})")
        return 0
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bwr-turbine-") as tmp:
        for name, signal in sounds(args.synth_excursion):
            report(name, signal)
            wav = Path(tmp) / (name + ".wav")
            write_wav(wav, signal)
            target = args.out / (name + ".ogg")
            run_blender(args.blender, "--encode", wav, target)
            print(f"{target.name}: {target.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
