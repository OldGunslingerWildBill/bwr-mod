#!/usr/bin/env python3
"""Negative-test the acceptance gate in an isolated copy of compiled test classes.

Run after :core:testClasses with JDK 21 on PATH or JAVA_HOME set. Neither project
sources nor normal class output is modified. Standard library only.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
TESTS = ROOT / "core/build/classes/java/test"
MAIN = ROOT / "core/build/classes/java/main"
JAVA_BIN = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None


def jdk_tool(name):
    return str(JAVA_BIN / (name + (".exe" if os.name == "nt" else ""))) if JAVA_BIN else name


def invoke(classes, *args):
    result = subprocess.run([jdk_tool("java"), "-cp", os.pathsep.join(map(str, (classes, MAIN))),
                             "dev.bwr.core.AcceptanceTests", *args], capture_output=True, text=True)
    return result.returncode, result.stdout + result.stderr


def main():
    if not TESTS.is_dir():
        raise SystemExit("Compile core tests first: gradlew :core:testClasses")
    code, output = invoke(TESTS, "--verify-registration")
    if code:
        raise SystemExit(output)
    print(output.strip())
    scratch_parent = ROOT / "tmp"
    scratch_parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="test-registration-", dir=scratch_parent) as name:
        scratch = Path(name).resolve()
        assert scratch.parent == scratch_parent.resolve()
        classes = scratch / "classes"
        shutil.copytree(TESTS, classes)
        canary = scratch / "UnregisteredCanary.java"
        canary.write_text("package audit.canary; public final class UnregisteredCanary {\n"
                          " public static void testForgotten() { throw new AssertionError(\"must not run\"); }\n"
                          "}\n", encoding="utf-8")
        subprocess.run([jdk_tool("javac"), "-d", str(classes), str(canary)], check=True)
        for arguments in (("--verify-registration",), ("no_matching_filter",)):
            code, output = invoke(classes, *arguments)
            if code == 0 or "Add to TEST_CLASSES: [audit.canary.UnregisteredCanary]" not in output:
                raise AssertionError("Unregistered test was not rejected with its name:\n" + output)
        print("PASS: unregistered test fails verification and filtered runs; class named in the error.")


if __name__ == "__main__":
    main()
