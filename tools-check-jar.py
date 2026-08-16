#!/usr/bin/env python3
"""Prove the published mod jar bundles nothing it is not allowed to bundle.

Run after `gradle build`:

    python tools-check-jar.py

Exits non-zero on any violation. Three things are checked.

1. ZERO CC:Tweaked classes.  CC:Tweaked's API is partly LicenseRef-CCPL, which
   permits redistribution only "unmodified and in full", so it must never be
   shipped inside our jar -- not shaded, not vendored, not jar-in-jar'd. It is
   a compileOnly dependency, and since the dev runtime now also puts the *full*
   CC jar on the runtime classpath (so the peripheral actually gets constructed
   in a real game) this check is the thing that keeps that convenience from
   silently leaking into a release.

2. ZERO Mekanism classes, for the same packaging reason (though Mekanism is MIT
   and the concern is purely that it is a soft dependency players install).

3. The dev-only harness package `dev.bwr.mod.devtest` is stripped. It exists to
   exercise the peripheral at runtime and has no business in a player's jar --
   and it is the one place outside `peripheral/` that names a CC type.

The scan is at the *bytecode* level, not by filename: every class in the jar has
its constant pool read, so a reference survives even if the class that makes it
is named something innocuous. Nested jarJar jars are opened and scanned too.
"""

import io
import struct
import sys
import zipfile

MOD_JAR = "mod/build/libs/mod-0.1.0-SNAPSHOT.jar"

FORBIDDEN_PREFIXES = {
    "dan200/computercraft": "CC:Tweaked (LicenseRef-CCPL -- MUST NOT be bundled)",
    "mekanism/": "Mekanism (soft dependency -- MUST NOT be bundled)",
}
FORBIDDEN_PACKAGES = {
    "dev/bwr/mod/devtest/": "dev-only peripheral harness (must be stripped from the jar)",
}


def constant_pool_strings(data):
    """Yield every CONSTANT_Utf8 in a class file. Enough to catch any type
    reference, since class and method references all resolve through Utf8."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return
    count = struct.unpack(">H", data[8:10])[0]
    i = 10
    n = 1
    while n < count:
        tag = data[i]
        i += 1
        if tag == 1:  # Utf8
            length = struct.unpack(">H", data[i:i + 2])[0]
            i += 2
            yield data[i:i + length].decode("utf-8", "replace")
            i += length
        elif tag in (7, 8, 16, 19, 20):
            i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):  # long / double take two pool slots
            i += 8
            n += 1
        else:
            return  # unknown tag; give up rather than mis-parse
        n += 1


def scan(jar_bytes, origin, problems, counters):
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as z:
        for name in z.namelist():
            for prefix, why in FORBIDDEN_PACKAGES.items():
                if name.startswith(prefix):
                    problems.append(f"{origin}: {name} -- {why}")

            if name.endswith(".jar"):
                scan(z.read(name), f"{origin}!{name}", problems, counters)
                continue
            if not name.endswith(".class"):
                continue

            counters["classes"] += 1
            for prefix, why in FORBIDDEN_PREFIXES.items():
                if name.startswith(prefix):
                    problems.append(f"{origin}: bundled class {name} -- {why}")

            refs = set()
            for s in constant_pool_strings(z.read(name)):
                for prefix in FORBIDDEN_PREFIXES:
                    # A real reference is either a bare internal name or one
                    # inside a type descriptor. Plain substring matching would
                    # count dev/bwr/mod/mekanism/... as a Mekanism reference,
                    # which is exactly backwards -- that package is ours.
                    if s.startswith(prefix) or ("L" + prefix) in s:
                        refs.add(prefix)
            for prefix in refs:
                counters.setdefault("refs", {}).setdefault(prefix, []).append(
                    f"{origin}:{name}")


def main():
    try:
        with open(MOD_JAR, "rb") as f:
            data = f.read()
    except FileNotFoundError:
        print(f"FAIL: {MOD_JAR} not found. Run `gradle build` first.")
        return 1

    problems = []
    counters = {"classes": 0}
    scan(data, MOD_JAR, problems, counters)

    print(f"scanned {counters['classes']} class files in {MOD_JAR} "
          f"(including nested jarJar jars)")
    for prefix, why in FORBIDDEN_PREFIXES.items():
        holders = counters.get("refs", {}).get(prefix, [])
        bundled = sum(1 for p in problems if f"bundled class {prefix}" in p)
        print(f"  bundled {prefix!r} classes: {bundled}")
        print(f"  our classes that merely *reference* {prefix!r}: {len(holders)}")
        for h in sorted(holders):
            print(f"      {h.split(':')[-1]}")
    for prefix in FORBIDDEN_PACKAGES:
        print(f"  entries under {prefix!r}: "
              f"{sum(1 for p in problems if prefix in p)}")

    if problems:
        print("\nFAIL:")
        for p in problems:
            print("  " + p)
        return 1
    print("\nOK: nothing forbidden is bundled.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
