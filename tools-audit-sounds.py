#!/usr/bin/env python3
"""Audit L4 sound resources without third-party packages or repository writes.

Run: py -3.13 tools-audit-sounds.py --self-test
Ogg structure/header checks complement SoundClientCheck's actual Minecraft decoder.
"""
import argparse
import copy
import json
from pathlib import Path
import struct
import sys

EXPECTED = frozenset((
    "annunciator_horn", "alarm_acknowledge", "relay_click", "contactor_click",
    "control_switch_detent", "breaker_close", "breaker_trip", "pump_motor",
    "turbine_whine", "steam_flow", "srv_lift", "msiv_slam", "scram_buzzer",
    "edg_start", "edg_run", "geiger_click",
))
# Real (not silent) looping sounds, synthesised by tools-make-rumble-sounds.py (RUMBLE.md). They are audited apart from the silent
# placeholders: the event, the subtitle and the file must exist, and the file must be a well-formed mono 48 kHz Vorbis stream.
SYNTHESISED = frozenset(("rumble_pool", "rumble_turbine"))
SAMPLE_RATE = 48_000
PCM_FRAMES = 12_000


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object)


def ogg_crc(data):
    crc = 0
    for value in data:
        crc ^= value << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
    return crc


def inspect_ogg(data):
    """Reject malformed/truncated pages and validate a single mono Vorbis stream.

    This is deliberately not a Vorbis audio decoder. JOrbisAudioStream supplies
    the independent, real decode-and-silence check in the opt-in client test.
    """
    packets, partial = [], bytearray()
    offset = sequence = 0
    serial = None
    eos = False
    final_granule = None
    while offset < len(data):
        if eos:
            raise ValueError("bytes after end-of-stream page")
        if len(data) - offset < 27 or data[offset:offset + 4] != b"OggS":
            raise ValueError("missing or truncated Ogg page header")
        if data[offset + 4] != 0:
            raise ValueError("unsupported Ogg version")
        flags = data[offset + 5]
        if flags & ~7 or bool(flags & 1) != bool(partial):
            raise ValueError("invalid Ogg flags/packet continuation")
        if bool(flags & 2) != (sequence == 0):
            raise ValueError("invalid beginning-of-stream page")
        page_serial, page_sequence, checksum = struct.unpack_from("<III", data, offset + 14)
        if serial is None:
            serial = page_serial
        if page_serial != serial or page_sequence != sequence:
            raise ValueError("multiple streams or discontinuous page sequence")
        count = data[offset + 26]
        header_end = offset + 27 + count
        if header_end > len(data):
            raise ValueError("truncated Ogg segment table")
        segments = data[offset + 27:header_end]
        page_end = header_end + sum(segments)
        if page_end > len(data):
            raise ValueError("truncated Ogg page body")
        page = bytearray(data[offset:page_end])
        page[22:26] = b"\0" * 4
        if ogg_crc(page) != checksum:
            raise ValueError("Ogg page checksum mismatch")
        cursor = header_end
        for size in segments:
            partial.extend(data[cursor:cursor + size])
            cursor += size
            if size < 255:
                packets.append(bytes(partial))
                partial.clear()
        eos = bool(flags & 4)
        if eos:
            final_granule = struct.unpack_from("<Q", data, offset + 6)[0]
        offset, sequence = page_end, sequence + 1
    if not eos or partial or len(packets) < 4:
        raise ValueError("missing EOS, unfinished packet, or missing audio data")
    identification, comment, setup = packets[:3]
    if len(identification) != 30 or identification[:7] != b"\x01vorbis":
        raise ValueError("invalid Vorbis identification header")
    if struct.unpack_from("<I", identification, 7)[0] != 0:
        raise ValueError("unsupported Vorbis version")
    channels = identification[11]
    sample_rate = struct.unpack_from("<I", identification, 12)[0]
    small, large = identification[28] & 15, identification[28] >> 4
    if channels != 1:
        raise ValueError(f"placeholder must be mono, got {channels} channels")
    if not 8000 <= sample_rate <= 192000 or not 6 <= small <= large <= 13 or identification[29] != 1:
        raise ValueError("invalid Vorbis rate, block sizes, or framing")
    if comment[:7] != b"\x03vorbis" or len(comment) < 16:
        raise ValueError("missing Vorbis comment header")
    cursor = 7

    def sized_string():
        nonlocal cursor
        if cursor + 4 > len(comment):
            raise ValueError("truncated Vorbis comment length")
        size = struct.unpack_from("<I", comment, cursor)[0]
        cursor += 4
        if cursor + size > len(comment):
            raise ValueError("truncated Vorbis comment text")
        result = comment[cursor:cursor + size]
        cursor += size
        return result

    vendor = sized_string().decode("utf-8", errors="strict")
    if cursor + 4 > len(comment):
        raise ValueError("missing Vorbis comment count")
    count = struct.unpack_from("<I", comment, cursor)[0]
    cursor += 4
    if count > len(comment) // 4:
        raise ValueError("unbounded Vorbis comment count")
    for _ in range(count):
        sized_string().decode("utf-8", errors="strict")
    if comment[cursor:] != b"\x01":
        raise ValueError("invalid Vorbis comment framing")
    if len(setup) < 8 or setup[:7] != b"\x05vorbis":
        raise ValueError("missing Vorbis setup header")
    if not final_granule or final_granule == (1 << 64) - 1:
        raise ValueError("no finite positive decoded frame count")
    return {"channels": channels, "sample_rate": sample_rate, "frames": final_granule,
            "seconds": final_granule / sample_rate, "pages": sequence, "vendor": vendor}


def validate(definitions, language, files, credits):
    problems, metadata = [], {}
    if not isinstance(definitions, dict):
        return ["sounds.json must be an object"], metadata
    if set(definitions) != EXPECTED:
        problems.append(f"event set differs: missing={sorted(EXPECTED-set(definitions))}, extra={sorted(set(definitions)-EXPECTED)}")
    expected_files = {name + ".ogg" for name in EXPECTED}
    if set(files) != expected_files:
        problems.append(f"sound files differ: missing={sorted(expected_files-set(files))}, extra={sorted(set(files)-expected_files)}")
    for name in sorted(EXPECTED):
        entry = definitions.get(name)
        if not isinstance(entry, dict):
            problems.append(f"{name}: missing/object event definition required")
            continue
        subtitle = "subtitle.bwr." + name
        if entry.get("subtitle") != subtitle or not isinstance(language.get(subtitle), str) or not language[subtitle].strip():
            problems.append(f"{name}: missing/incorrect subtitle or empty English translation")
        sounds = entry.get("sounds")
        if not isinstance(sounds, list) or len(sounds) != 1:
            problems.append(f"{name}: exactly one file placeholder required")
        else:
            sound = sounds[0]
            path = sound if isinstance(sound, str) else sound.get("name") if isinstance(sound, dict) else None
            kind = sound.get("type", "file") if isinstance(sound, dict) else "file"
            if path != "bwr:" + name or kind != "file":
                problems.append(f"{name}: expected distinct file bwr:{name}, not event alias")
        if name + ".ogg" in files:
            try:
                metadata[name] = inspect_ogg(files[name + ".ogg"])
                if metadata[name]["sample_rate"] != SAMPLE_RATE or metadata[name]["frames"] != PCM_FRAMES:
                    problems.append(f"{name}.ogg: expected {SAMPLE_RATE} Hz / {PCM_FRAMES} EOS frames")
            except (ValueError, struct.error, UnicodeError) as error:
                problems.append(f"{name}.ogg: {error}")
        if name + ".ogg" not in credits:
            problems.append(f"{name}: missing per-file SOUND-CREDITS.md attribution")
    lower = credits.lower()
    if not all(word in lower for word in ("silent", "placeholder", "license")):
        problems.append("SOUND-CREDITS.md must identify silent placeholder status and license")
    return problems, metadata


def fix_checksums(data):
    """Used only by adversarial fixtures to isolate header checks from CRC checks."""
    changed = bytearray(data)
    offset = 0
    while offset < len(changed):
        table_end = offset + 27 + changed[offset + 26]
        end = table_end + sum(changed[offset + 27:table_end])
        changed[offset + 22:offset + 26] = b"\0" * 4
        struct.pack_into("<I", changed, offset + 22, ogg_crc(changed[offset:end]))
        offset = end
    return bytes(changed)


def negative_checks(definitions, language, files, credits):
    cases = []
    def check(label, definitions=definitions, language=language, files=files, credits=credits):
        if not validate(definitions, language, files, credits)[0]:
            raise AssertionError("audit self-test missed " + label)
        cases.append(label)
    changed = copy.deepcopy(definitions)
    changed.pop("pump_motor")
    check("missing event", definitions=changed)
    changed = copy.deepcopy(definitions)
    changed["pump_motor"] = {"sounds": "bwr:pump_motor"}
    check("malformed definition", definitions=changed)
    changed = copy.deepcopy(definitions)
    changed["pump_motor"]["sounds"] = [{"name": "bwr:relay_click", "type": "event"}]
    check("event alias/shared file", definitions=changed)
    check("missing subtitle", language={k: v for k, v in language.items() if k != "subtitle.bwr.pump_motor"})
    check("missing resource", files={k: v for k, v in files.items() if k != "pump_motor.ogg"})
    check("missing credits", credits="")
    valid = files["pump_motor.ogg"]
    for label, malformed in (("truncated file", valid[:-1]), ("not Ogg", b"not an Ogg stream")):
        changed = dict(files, **{"pump_motor.ogg": malformed})
        check(label, files=changed)
    changed = bytearray(valid)
    changed[-1] ^= 1
    check("corrupt CRC", files=dict(files, **{"pump_motor.ogg": bytes(changed)}))
    first_packet = 27 + valid[26]
    changed = bytearray(valid)
    changed[first_packet + 11] = 2
    check("stereo header with valid CRC", files=dict(files, **{"pump_motor.ogg": fix_checksums(changed)}))
    changed = bytearray(valid)
    changed[first_packet + 12:first_packet + 16] = b"\0" * 4
    check("zero sample rate with valid CRC", files=dict(files, **{"pump_motor.ogg": fix_checksums(changed)}))
    changed = bytearray(valid)
    struct.pack_into("<I", changed, first_packet + 12, 44100)
    check("wrong valid sample rate", files=dict(files, **{"pump_motor.ogg": fix_checksums(changed)}))
    changed = bytearray(valid)
    offset = 0
    while not changed[offset + 5] & 4:
        table_end = offset + 27 + changed[offset + 26]
        offset = table_end + sum(changed[offset + 27:table_end])
    struct.pack_into("<Q", changed, offset + 6, PCM_FRAMES + 1)
    check("wrong duration with valid CRC", files=dict(files, **{"pump_motor.ogg": fix_checksums(changed)}))
    try:
        json.loads('{"pump_motor": {}, "pump_motor": {}}', object_pairs_hook=unique_object)
    except ValueError:
        cases.append("duplicate JSON key")
    else:
        raise AssertionError("audit self-test missed duplicate JSON key")
    return len(cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--resources", type=Path, help="Optional staged resource root")
    parser.add_argument("--lang-additions", type=Path)
    parser.add_argument("--credits", type=Path, help="Optional staged SOUND-CREDITS.md")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        base = args.repo.resolve() / "mod/src/main/resources"
        resources = args.resources.resolve() if args.resources else base
        assets = resources / "assets/bwr"
        definitions = read_json(assets / "sounds.json")
        language = read_json(base / "assets/bwr/lang/en_us.json")
        if args.lang_additions:
            language.update(read_json(args.lang_additions))
        files = {p.relative_to(assets / "sounds").as_posix(): p.read_bytes()
                 for p in (assets / "sounds").rglob("*.ogg")}
        credits = (args.credits or args.repo / "SOUND-CREDITS.md").read_text(encoding="utf-8-sig")
        synthesised = []
        for name in sorted(SYNTHESISED):
            entry = definitions.pop(name, None)
            data = files.pop(name + ".ogg", None)
            if not isinstance(entry, dict) or entry.get("subtitle") != "subtitle.bwr." + name or not language.get("subtitle.bwr." + name):
                synthesised.append(f"{name}: missing event or subtitle")
            elif data is None:
                synthesised.append(f"{name}: missing {name}.ogg")
            else:
                try:
                    info = inspect_ogg(data)
                    if info["channels"] != 1 or info["sample_rate"] != SAMPLE_RATE or not 3 * SAMPLE_RATE <= info["frames"] <= 8 * SAMPLE_RATE or len(data) > 150_000:
                        synthesised.append(f"{name}.ogg: expected a mono 48 kHz loop of 3 to 8 s under 150 kB, got {info}, {len(data)} bytes")
                except (ValueError, struct.error, UnicodeError) as error:
                    synthesised.append(f"{name}.ogg: {error}")
        problems, metadata = validate(definitions, language, files, credits)
        problems += synthesised
        if args.self_test and not problems:
            print(f"Audit negative checks: {negative_checks(definitions, language, files, credits)} corruptions detected")
        for problem in problems:
            print("ERROR:", problem)
        for name, info in metadata.items():
            print(f"{name}: mono Vorbis, {info['sample_rate']} Hz, {info['frames']} frames, {info['seconds']:.3f} s")
        print(f"Sound audit: {len(definitions)} definitions, {len(files)} files, {len(problems)} errors")
        print("PCM silence is verified separately by SoundClientCheck using Minecraft's JOrbisAudioStream.")
        return 1 if problems else 0
    except (OSError, ValueError, TypeError, AttributeError, AssertionError) as error:
        print("ERROR:", error)
        return 1


if __name__ == "__main__":
    sys.exit(main())
