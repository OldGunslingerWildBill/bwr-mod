#!/usr/bin/env python3
"""Writes the premade control boards: mod/src/main/resources/data/bwr/panel_board/*.json.

The JSON files are the source of truth for the game (see BoardTemplateParser for the format); this script only writes the
shapes that ship. A shipped board is a BLANK SHAPE named by its form (a vertical board, a benchboard, a duplex board ...):
panel blocks placed the way they are meant to be run together, with no components, no mimic tape, no reserved spaces and
no notes. What goes on a board is the player's business (a board is not tied to a reactor function), so nothing here names
a system. A data pack can still ship a full board with components: the format has not changed.

    python tools-generate-boards.py            # rewrite the board files (and delete the retired ones)
    python tools-generate-boards.py --check    # fail when the files on disk differ from what this would write

Every board is placed unwired (there is nothing to wire). Frames follow BoardTemplate.Kind: at is [right, up, forward] in
blocks from the board's origin (the top left block for a run, the bench of the left column for a duplex).
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "mod/src/main/resources/data/bwr/panel_board")

COLUMNS = 4   # blocks (metres) wide: wide enough to be a board, small enough to place anywhere


def run(kind, rows, at):
    return {"id": "main", "kind": kind, "columns": COLUMNS, "rows": rows, "at": list(at)}


# (file name, title, description, frames). The order is the blueprint's cycling order.
BOARDS = [
    ("vertical_board", "Vertical board",
     "Four vertical panel blocks wide and two high, a flat board standing on the floor. Blank: mount what you like on it.",
     [run("vertical", 2, (0, 1, 0))]),
    ("back_panel", "Back panel",
     "Four full-depth back panel blocks wide and two high, an upright board. Blank: mount what you like on it.",
     [run("back_panel", 2, (0, 1, 0))]),
    ("benchboard", "Benchboard",
     "A run of four benchboard blocks, the 45 degree desk. Blank: mount what you like on it.",
     [run("benchboard", 1, (0, 0, 0))]),
    ("console", "Console",
     "A run of four console blocks, the 15 degree desk. Blank: mount what you like on it.",
     [run("console", 1, (0, 0, 0))]),
    ("back_panel_with_benchboard", "Back panel with benchboard",
     "A back panel two high with a benchboard in front of it, four blocks wide. Blank: mount what you like on it.",
     [dict(run("back_panel", 2, (0, 2, -1)), id="wall"), dict(run("benchboard", 1, (0, 0, 0)), id="bench")]),
    ("duplex_board", "Duplex board",
     "Four duplex columns: the board two blocks high, the tilted header above it and the bench in front. Blank: mount what you like on it.",
     [run("duplex", 1, (0, 0, 0))]),
]


def board_json(order, title, description, frames):
    # a blank shape: only the format, the names and the panel blocks (the rest of the format is left out, so nothing
    # can be mistaken for content)
    return {"format": 1, "title": title, "description": description, "order": order, "frames": frames}


def main():
    check = "--check" in sys.argv
    wanted = {}
    for order, (name, title, description, frames) in enumerate(BOARDS, start=1):
        wanted[name + ".json"] = json.dumps(board_json(order, title, description, frames), indent=1) + "\n"
    existing = sorted(f for f in os.listdir(OUT) if f.endswith(".json")) if os.path.isdir(OUT) else []
    if check:
        for name, text in wanted.items():
            path = os.path.join(OUT, name)
            if not os.path.exists(path) or open(path, encoding="utf-8").read() != text:
                raise SystemExit("%s is out of date: run python tools-generate-boards.py" % path)
        stale = [f for f in existing if f not in wanted]
        if stale:
            raise SystemExit("retired board files still on disk: %s" % ", ".join(stale))
        return
    os.makedirs(OUT, exist_ok=True)
    for old in existing:
        if old not in wanted:
            os.remove(os.path.join(OUT, old))
            print("removed " + old)
    for name, text in wanted.items():
        open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n").write(text)
        print("%s: %d frame(s)" % (name, text.count('"kind"')))


if __name__ == "__main__":
    main()
