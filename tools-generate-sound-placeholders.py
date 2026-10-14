#!/usr/bin/env python3
"""Generate L4's silent Ogg Vorbis placeholders; real synthesized sounds belong to Phase Q.

Uses only Python's standard library and an existing Blender installation. No samples,
recordings, downloads, dependencies or encoder binaries are included. See SOUND-CREDITS.md.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import wave


EVENTS = (
    "annunciator_horn", "alarm_acknowledge", "relay_click", "contactor_click",
    "control_switch_detent", "breaker_close", "breaker_trip", "pump_motor",
    "turbine_whine", "steam_flow", "srv_lift", "msiv_slam", "scram_buzzer",
    "edg_start", "edg_run", "geiger_click",
)
SAMPLE_RATE = 48_000
PCM_FRAMES = 12_000
CHANNELS = 1
SAMPLE_BYTES = 2
BLENDER_VERSION = (5, 2, 2)
BLENDER_BUILD_HASH = b"d13f752e3b9c"


def ogg_crc(data: bytes | bytearray) -> int:
    """Ogg's non-reflected CRC-32, polynomial 0x04c11db7, initial value zero."""
    crc = 0
    for value in data:
        crc ^= value << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
    return crc


def normalise_serial(encoded: bytes, event: str) -> bytes:
    """Replace the encoder's random stream serial; recalculate every affected page CRC.

    Audio packets, Vorbis headers, granule positions and timestamps are unchanged.
    Each event receives its own stable serial derived from the complete resource ID.
    """
    output = bytearray(encoded)
    serial = hashlib.sha256(f"bwr:{event}".encode("ascii")).digest()[:4]
    offset = 0
    while offset < len(output):
        if output[offset:offset + 4] != b"OggS" or offset + 27 > len(output):
            raise ValueError("Encoder did not produce a complete Ogg page")
        segments = output[offset + 26]
        header_end = offset + 27 + segments
        if header_end > len(output):
            raise ValueError("Truncated Ogg lacing table")
        end = header_end + sum(output[offset + 27:header_end])
        if end > len(output):
            raise ValueError("Truncated Ogg payload")
        output[offset + 14:offset + 18] = serial
        output[offset + 22:offset + 26] = b"\0" * 4
        output[offset + 22:offset + 26] = ogg_crc(output[offset:end]).to_bytes(4, "little")
        offset = end
    if not output:
        raise ValueError("Encoder produced no audio")
    return bytes(output)


def blender_encode(wav_path: Path, ogg_path: Path) -> None:
    """Executed only by a separate background Blender, never the user's open scene."""
    import aud
    import bpy

    if bpy.app.version != BLENDER_VERSION or bpy.app.build_hash != BLENDER_BUILD_HASH:
        raise RuntimeError(
            "Byte-identical regeneration requires Blender 5.2.2 LTS, build d13f752e3b9c; "
            f"found {bpy.app.version_string}, build {bpy.app.build_hash!r}"
        )
    # Audaspace writes synchronously in background mode; no scene, editor, device or playback.
    aud.Sound.file(str(wav_path)).write(
        str(ogg_path), SAMPLE_RATE, aud.CHANNELS_MONO, aud.FORMAT_FLOAT32,
        aud.CONTAINER_OGG, aud.CODEC_VORBIS, 96_000, 1024,
    )
    if not ogg_path.is_file():
        raise RuntimeError("Blender's Audaspace writer did not produce an Ogg file")
    decoded = aud.Sound.file(str(ogg_path))
    pcm = decoded.data()
    # Vorbis decoders can expose different priming/end padding. The source and container
    # duration remain 12,000 frames; verify the decoded signal, format and nonempty length here.
    if decoded.specs != (SAMPLE_RATE, CHANNELS) or pcm.shape[1] != CHANNELS or pcm.shape[0] <= 0 or pcm.any():
        raise RuntimeError(f"Encoded placeholder is not exact mono silence: {decoded.specs}, {pcm.shape}")


def generate(blender: Path, output_dir: Path, check: bool) -> int:
    blender = blender.resolve(strict=True)
    script = Path(__file__).resolve()
    with tempfile.TemporaryDirectory(prefix="bwr-silent-sounds-") as temporary:
        work = Path(temporary)
        wav_path, ogg_path = work / "zero-pcm.wav", work / "encoded.ogg"
        # The entire source signal is generated here: mono signed-16-bit samples equal to zero.
        with wave.open(str(wav_path), "wb") as source:
            source.setnchannels(CHANNELS)
            source.setsampwidth(SAMPLE_BYTES)
            source.setframerate(SAMPLE_RATE)
            source.writeframes(bytes(PCM_FRAMES * CHANNELS * SAMPLE_BYTES))
        command = [str(blender), "--background", "--factory-startup", "--python-exit-code", "1",
                   "--python", str(script), "--", "--encode", str(wav_path), str(ogg_path)]
        completed = subprocess.run(command, capture_output=True, text=True,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if completed.returncode:
            raise RuntimeError(f"Blender encoding failed:\n{completed.stdout}\n{completed.stderr}")
        encoded = ogg_path.read_bytes()
        if not check:
            output_dir.mkdir(parents=True, exist_ok=True)
        failures = []
        for event in EVENTS:
            path = output_dir / f"{event}.ogg"
            generated = normalise_serial(encoded, event)
            if check:
                if not path.is_file() or path.read_bytes() != generated:
                    failures.append(path.name)
            else:
                path.write_bytes(generated)
            print(f"{path.name}: {len(generated)} bytes, sha256={hashlib.sha256(generated).hexdigest()}")
        if failures:
            print("NOT REPRODUCIBLE: " + ", ".join(failures), file=sys.stderr)
            return 1
        action = "verified byte-identical" if check else "generated"
        print(f"{len(EVENTS)} {action} silent mono Vorbis placeholders; "
              f"{SAMPLE_RATE} Hz, {PCM_FRAMES} zero PCM frames, 0.25 s per file")
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--blender", type=Path, required=True, help="Installed Blender 5.2.2 LTS executable")
    parser.add_argument("--output-dir", type=Path,
                        default=Path(__file__).resolve().parent / "mod/src/main/resources/assets/bwr/sounds")
    parser.add_argument("--check", action="store_true", help="Regenerate to temporary storage and compare; write no assets")
    args = parser.parse_args()
    return generate(args.blender, args.output_dir, args.check)


if __name__ == "__main__":
    if "--" in sys.argv and sys.argv[sys.argv.index("--") + 1:][:1] == ["--encode"]:
        _, wav_arg, ogg_arg = sys.argv[sys.argv.index("--") + 1:]
        blender_encode(Path(wav_arg), Path(ogg_arg))
    else:
        raise SystemExit(main())
