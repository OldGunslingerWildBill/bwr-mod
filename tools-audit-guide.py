#!/usr/bin/env python3
"""Audit of the in-game guide (mod/src/main/resources/assets/bwr/guide/<language>/*.json).

Run from anywhere:  python tools-audit-guide.py
Exits non-zero if anything is wrong. Does not modify anything. Standard library only.

Checks, per language folder:
  1. index.json lists the chapters; every listed chapter file exists and every chapter file is listed
  2. every file parses and has the fields the loader (GuideBook.java) needs, with the same types
  3. every page's text is within the limit the screen is laid out for (GuideBook.MAX_PAGE_CHARS), chapters have at most
     MAX_PAGES_PER_CHAPTER pages, icons and images are within their limits
  4. every item icon id is a registered item of this mod (a lang entry item.bwr.<id> or block.bwr.<id>; the GameTest
     GuideBookTests checks the real registry as well)
  5. every image texture exists as a PNG under assets/ and is square at the declared textureSize
  6. every referenced document ("More: ...") exists in the repository
  7. a translation that has a chapter file has the same number of pages as the default (en_us) chapter
  8. the "Server options" chapter names every option of every config class, with the right default where the default
     is a literal in the Java source
  9. every gui.bwr.guide.* key the screen uses is in the English lang file

The checks are written as one function over in-memory files so the negative controls at the bottom can break a copy and
require the audit to notice (a check that cannot fail is worse than none).
"""
from pathlib import Path
import copy
import json
import re
import struct
import sys

ROOT = Path(__file__).resolve().parent
RES = ROOT / "mod/src/main/resources"
GUIDE = RES / "assets/bwr/guide"
LANG = RES / "assets/bwr/lang/en_us.json"
JAVA = ROOT / "mod/src/main/java/dev/bwr/mod"
DEFAULT_LANG = "en_us"
SERVER_CHAPTER = "server-options"
MAX_PAGE_CHARS = 600
MAX_PAGES_PER_CHAPTER = 12
MAX_ICONS = 12
MAX_IMAGE_SIZE = 32

CONFIG_CLASSES = ["config/GameplayConfig.java", "grid/GridConfig.java", "containment/ContainmentConfig.java",
                  "accident/CoriumConfig.java", "panel/PanelConfig.java", "client/VisualEffectsConfig.java"]


def read_png_size(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", data[16:24])


def page_text(page):
    text = page.get("text")
    if isinstance(text, list):
        return "\n".join(text)
    return text


def load_books():
    """{language: {file name: parsed JSON or the string 'INVALID: ...'}}"""
    books = {}
    for folder in sorted(p for p in GUIDE.iterdir() if p.is_dir()):
        files = {}
        for f in sorted(folder.glob("*.json")):
            try:
                files[f.name] = json.loads(f.read_text(encoding="utf-8"))
            except ValueError as e:
                files[f.name] = "INVALID: " + str(e)
        books[folder.name] = files
    return books


def item_ids():
    lang = json.loads(LANG.read_text(encoding="utf-8"))
    ids = set()
    for key in lang:
        m = re.fullmatch(r"(?:item|block)\.bwr\.([a-z0-9_]+)", key)
        if m:
            ids.add(m.group(1))
    return ids


def config_options():
    """{option name: Java default as a Python value, or None when the default is not a literal}"""
    out = {}
    pattern = re.compile(r'\.define(?:InRange|Enum)?\(\s*"(\w+)"\s*,\s*([^,)\n]+)')
    for rel in CONFIG_CLASSES:
        source = (JAVA / rel).read_text(encoding="utf-8")
        for name, default in pattern.findall(source):
            default = default.strip()
            value = None
            if default in ("true", "false"):
                value = default == "true"
            elif re.fullmatch(r"-?[0-9][0-9_]*(\.[0-9_]+)?", default):
                value = float(default.replace("_", ""))
            elif re.fullmatch(r"Mode\.(\w+)", default):
                value = default.split(".")[1]
            out[name] = value
    return out


def parse_default(text):
    text = text.strip().rstrip(".,;")
    if text in ("true", "false"):
        return text == "true"
    if text.startswith('"') and text.endswith('"'):
        return text.strip('"')
    try:
        return float(text.replace(",", ""))
    except ValueError:
        return text


def audit(books, ids, options, repo_has_doc, texture_info, lang_keys, screen_keys):
    errors = []

    def bad(msg):
        errors.append(msg)

    for key in sorted(screen_keys):
        if key not in lang_keys:
            bad(f"the guide screen uses {key}, which is not in en_us.json")

    default = books.get(DEFAULT_LANG)
    if default is None:
        bad(f"guide/{DEFAULT_LANG}/ does not exist")
        return errors

    pages_total = 0
    for lang, files in books.items():
        index = files.get("index.json")
        if not isinstance(index, dict) or not isinstance(index.get("chapters"), list) or not index["chapters"]:
            bad(f"{lang}/index.json: needs a non-empty \"chapters\" list")
            continue
        listed = index["chapters"]
        if len(set(listed)) != len(listed):
            bad(f"{lang}/index.json: a chapter is listed twice")
        for cid in listed:
            if not isinstance(cid, str) or not cid:
                bad(f"{lang}/index.json: chapter ids must be non-empty strings")
        if lang == DEFAULT_LANG:
            for name in files:
                if name != "index.json" and name[:-5] not in listed:
                    bad(f"{lang}/{name}: not listed in index.json")
        for cid in listed:
            fname = cid + ".json"
            chapter = files.get(fname)
            if chapter is None:
                if lang == DEFAULT_LANG:
                    bad(f"{lang}/{fname}: listed in index.json but missing")
                continue
            if isinstance(chapter, str):
                bad(f"{lang}/{fname}: {chapter}")
                continue
            where = f"{lang}/{fname}"
            if not isinstance(chapter.get("title"), str) or not chapter["title"].strip():
                bad(f"{where}: \"title\" must be a non-empty string")
            docs = chapter.get("docs")
            if not isinstance(docs, list) or not docs:
                bad(f"{where}: \"docs\" must name at least one repository document (the chapter ends with More: ...)")
                docs = []
            for doc in docs:
                if not isinstance(doc, str) or not doc.endswith(".md") or ".." in doc or doc.startswith("/") or "\\" in doc:
                    bad(f"{where}: document {doc!r} must be a path inside the repository ending in .md")
                elif not repo_has_doc(doc):
                    bad(f"{where}: document {doc} does not exist in the repository")
            pages = chapter.get("pages")
            if not isinstance(pages, list) or not pages:
                bad(f"{where}: \"pages\" must be a non-empty list")
                continue
            if len(pages) > MAX_PAGES_PER_CHAPTER:
                bad(f"{where}: {len(pages)} pages (at most {MAX_PAGES_PER_CHAPTER})")
            if lang != DEFAULT_LANG:
                base = default.get(fname)
                if isinstance(base, dict) and isinstance(base.get("pages"), list) and len(base["pages"]) != len(pages):
                    bad(f"{where}: {len(pages)} pages but the {DEFAULT_LANG} chapter has {len(base['pages'])}")
            for n, page in enumerate(pages, 1):
                here = f"{where} page {n}"
                pages_total += 1
                if not isinstance(page, dict):
                    bad(f"{here}: must be an object")
                    continue
                if not isinstance(page.get("title"), str) or not page["title"].strip():
                    bad(f"{here}: \"title\" must be a non-empty string")
                text = page_text(page)
                if not isinstance(text, str) or not text.strip():
                    bad(f"{here}: \"text\" must be a non-empty string or list of strings")
                    continue
                if isinstance(page.get("text"), list) and not all(isinstance(t, str) for t in page["text"]):
                    bad(f"{here}: every line of \"text\" must be a string")
                if len(text) > MAX_PAGE_CHARS:
                    bad(f"{here} ({page.get('title')}): {len(text)} characters of text (at most {MAX_PAGE_CHARS})")
                icons = page.get("icons", [])
                if not isinstance(icons, list) or not all(isinstance(i, str) for i in icons):
                    bad(f"{here}: \"icons\" must be a list of item ids")
                    icons = []
                if len(icons) > MAX_ICONS:
                    bad(f"{here}: {len(icons)} icons (at most {MAX_ICONS})")
                for icon in icons:
                    ns, _, path = icon.partition(":")
                    if not path:
                        bad(f"{here}: icon {icon!r} needs a namespace (bwr:name)")
                    elif ns != "bwr":
                        bad(f"{here}: icon {icon!r}: only items of this mod can be checked; use a bwr: id")
                    elif path not in ids:
                        bad(f"{here}: icon {icon} is not an item of this mod (no lang entry item.bwr.{path} or block.bwr.{path})")
                image = page.get("image")
                if image is not None:
                    if not isinstance(image, dict) or not isinstance(image.get("texture"), str):
                        bad(f"{here}: \"image\" needs a \"texture\"")
                        continue
                    size, tex = image.get("size"), image.get("textureSize")
                    if not isinstance(size, int) or not isinstance(tex, int) or size <= 0 or tex <= 0:
                        bad(f"{here}: image \"size\" and \"textureSize\" must be whole numbers above zero")
                        continue
                    if size > MAX_IMAGE_SIZE:
                        bad(f"{here}: image drawn {size} px (at most {MAX_IMAGE_SIZE})")
                    info = texture_info(image["texture"])
                    if info is None:
                        bad(f"{here}: image texture {image['texture']} does not exist as a PNG under assets/")
                    elif info != (tex, tex):
                        bad(f"{here}: image texture {image['texture']} is {info[0]}x{info[1]}, declared {tex}x{tex}")

    # the server options chapter
    chapter = default.get(SERVER_CHAPTER + ".json")
    if not isinstance(chapter, dict):
        bad(f"{DEFAULT_LANG}/{SERVER_CHAPTER}.json is missing")
    else:
        text = "\n".join(page_text(p) or "" for p in chapter.get("pages", []) if isinstance(p, dict))
        for name, value in options.items():
            m = re.search(r"(?<![A-Za-z])" + re.escape(name) + r"\s*=\s*([^\s,()]+)", text)
            if not m:
                bad(f"{SERVER_CHAPTER}: option {name} is not listed with its default (\"{name} = ...\")")
                continue
            if value is None:
                continue
            shown = parse_default(m.group(1))
            if isinstance(value, float) and isinstance(shown, float):
                same = abs(value - shown) < 1e-9
            else:
                same = value == shown
            if not same:
                bad(f"{SERVER_CHAPTER}: {name} is listed as {m.group(1)} but its default in Java is {value}")
    return errors


def screen_keys_used():
    keys = set()
    for path in (JAVA / "guide").rglob("*.java"):
        source = path.read_text(encoding="utf-8")
        keys.update(re.findall(r'"(gui\.bwr\.guide\.[a-z_]+)"', source))
    return keys


def real_checks():
    lang_keys = set(json.loads(LANG.read_text(encoding="utf-8")))

    def repo_has_doc(doc):
        return (ROOT / doc).is_file()

    def texture_info(texture):
        ns, _, path = texture.partition(":")
        file = RES / "assets" / ns / path
        return read_png_size(file) if file.is_file() else None

    return load_books(), item_ids(), config_options(), repo_has_doc, texture_info, lang_keys, screen_keys_used()


def main():
    books, ids, options, repo_has_doc, texture_info, lang_keys, screen_keys = real_checks()
    errors = audit(books, ids, options, repo_has_doc, texture_info, lang_keys, screen_keys)
    pages = sum(len(c.get("pages", [])) for c in books.get(DEFAULT_LANG, {}).values()
                if isinstance(c, dict) and isinstance(c.get("pages"), list))
    chapters = len(books.get(DEFAULT_LANG, {}).get("index.json", {}).get("chapters", []))
    print(f"Guide audit: {len(books)} language(s), {chapters} chapters, {pages} pages, {len(options)} config options, "
          f"{len(errors)} problems")
    for e in errors:
        print(e)

    # negative controls: break a copy in memory and require the audit to notice
    failures = 0
    controls = 0

    def control(label, mutate, expect):
        nonlocal failures, controls
        controls += 1
        broken = copy.deepcopy(books)
        extra = {"ids": ids, "doc": repo_has_doc, "tex": texture_info, "options": options}
        mutate(broken, extra)
        found = audit(broken, extra["ids"], extra["options"], extra["doc"], extra["tex"], lang_keys, screen_keys)
        if not any(expect in e for e in found):
            failures += 1
            print(f"NEGATIVE CONTROL FAILED: {label}: no problem containing {expect!r} was reported")

    d = DEFAULT_LANG
    first = lambda b: b[d]["first-plant.json"]
    control("a missing chapter file", lambda b, x: b[d].pop("first-plant.json"), "listed in index.json but missing")
    control("an unlisted chapter file", lambda b, x: b[d].update({"stray.json": {"title": "x"}}), "not listed in index.json")
    control("an invalid chapter file", lambda b, x: b[d].update({"fuel.json": "INVALID: boom"}), "INVALID: boom")
    control("a page that is too long", lambda b, x: first(b)["pages"][0].update({"text": "x" * (MAX_PAGE_CHARS + 1)}), "characters of text")
    control("an unknown item icon", lambda b, x: first(b)["pages"][0].update({"icons": ["bwr:no_such_item"]}), "is not an item of this mod")
    control("a missing document", lambda b, x: first(b).update({"docs": ["NO-SUCH-DOC.md"]}), "does not exist in the repository")
    control("a chapter with no document", lambda b, x: first(b).update({"docs": []}), "must name at least one repository document")
    control("an image that does not exist",
            lambda b, x: first(b)["pages"][0].update({"image": {"texture": "bwr:textures/item/no_such.png", "textureSize": 16, "size": 16}}),
            "does not exist as a PNG")
    control("an image of the wrong size",
            lambda b, x: first(b)["pages"][0].update({"image": {"texture": "bwr:textures/item/survey_meter.png", "textureSize": 32, "size": 16}}),
            "declared 32x32")
    control("an option missing from the server chapter",
            lambda b, x: x["options"].update({"someNewOption": True}), "option someNewOption is not listed")
    control("a wrong default in the server chapter",
            lambda b, x: x["options"].update({"gridTieMaxMW": 999.0}), "gridTieMaxMW is listed as")
    control("a translation with a different page count",
            lambda b, x: b.update({"xx_xx": {"index.json": {"chapters": ["first-plant"]},
                                             "first-plant.json": {"title": "t", "docs": ["README.md"],
                                                                  "pages": [{"title": "a", "text": "b"}]}}}),
            "pages but the en_us chapter has")
    if failures:
        errors.append("negative controls failed")
    else:
        print(f"Guide audit negative controls: {controls} deliberate faults, all reported")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
