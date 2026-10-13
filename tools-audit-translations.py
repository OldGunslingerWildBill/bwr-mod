#!/usr/bin/env python3
"""Check static BWR translation keys, English fallbacks and placeholder arity.

Run from anywhere. Does not modify source or resources. Dynamic component/item
keys retain the asset audit and GameTest coverage; player labels and program
text are data, not translation keys.
"""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parent
JAVA = ROOT / "mod/src/main/java/dev/bwr/mod"
LANG = ROOT / "mod/src/main/resources/assets/bwr/lang/en_us.json"
STRING = re.compile(r'"(?:[^"\\]|\\.)*"', re.S)
CALL = re.compile(r'\b(BwrText\.tr|Component\.translatable(?:WithFallback)?)\(')


def arguments(source, start):
    """Read one Java call, ignoring commas/parens inside strings or nested calls."""
    depth, begin, quote, escape = 1, start, None, False
    result = []
    for i in range(start, len(source)):
        char = source[i]
        if quote:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                quote = None
            continue
        if char in ('"', "'"):
            quote = char
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                result.append(source[begin:i].strip())
                return result
        elif char == "," and depth == 1:
            result.append(source[begin:i].strip())
            begin = i + 1
    raise ValueError("unclosed translation call")


def literal(expression):
    return json.loads(expression) if STRING.fullmatch(expression) else None


def placeholders(template):
    required, sequential, end = 0, 0, 0
    for match in re.finditer(r"%%|%(?:(\d+)\$)?s", template):
        if "%" in template[end:match.start()]:
            raise ValueError("unsupported format specifier")
        end = match.end()
        if match[0] == "%%":
            continue
        if match[1]:
            required = max(required, int(match[1]))
        else:
            sequential += 1
            required = max(required, sequential)
    if "%" in template[end:]:
        raise ValueError("unescaped percent")
    return required


def check_call(args, fallback, catalog):
    key = literal(args[0])
    if key is None or not (".bwr." in key or key.startswith("bwr.")):
        return None
    if key not in catalog:
        return f"missing key {key}"
    offset = 2 if fallback else 1
    if fallback and literal(args[1]) != catalog[key]:
        return f"English fallback differs for {key}"
    try:
        count = placeholders(catalog[key])
    except ValueError as error:
        return f"{key}: {error}"
    if count != len(args) - offset:
        return f"{key}: needs {count} arguments, got {len(args) - offset}"
    return None


def audit():
    errors, checked = [], 0
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                errors.append(f"duplicate language key {key}")
            result[key] = value
        return result
    catalog = json.loads(LANG.read_text(encoding="utf-8"), object_pairs_hook=unique)
    for path in JAVA.rglob("*.java"):
        if "devtest" in path.parts:
            continue
        source = path.read_text(encoding="utf-8")
        for match in CALL.finditer(source):
            args = arguments(source, match.end())
            key = literal(args[0])
            if key is None:
                continue
            checked += 1
            error = check_call(args, match[1] != "Component.translatable", catalog)
            if error:
                line = source.count("\n", 0, match.start()) + 1
                errors.append(f"{path.relative_to(ROOT)}:{line}: {error}")
    print(f"Translation audit: {checked} static calls, {len(catalog)} English keys, {len(errors)} problems")
    for error in errors:
        print(error)
    return len(errors)


if __name__ == "__main__":
    # Negative controls ensure the audit itself rejects a missing key, fallback
    # drift, lost arguments and an unsupported Minecraft format specifier.
    fixture = {'status.bwr.example': '%s / %s'}
    assert check_call(['"status.bwr.missing"'], False, fixture)
    assert check_call(['"status.bwr.example"', '"wrong"', '1', '2'], True, fixture)
    assert check_call(['"status.bwr.example"', '1'], False, fixture)
    assert check_call(['"status.bwr.example"', '1'], False, {'status.bwr.example': '%d'})
    assert check_call(['"status.bwr.example"', '1', '2'], False, fixture) is None
    assert placeholders('%2$s / %1$s (100%%)') == 2
    sys.exit(1 if audit() else 0)
