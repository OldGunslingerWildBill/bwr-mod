#!/usr/bin/env python3
"""Writes the premade control boards: mod/src/main/resources/data/bwr/panel_board/*.json.

The JSON files are the source of truth for the game (see BoardTemplateParser for the format); this script only makes
the five boards that ship easier to lay out and to check: it knows the real sizes, the surfaces of each frame and it
refuses a layout that leaves a surface or overlaps (the game checks all of that again when it loads the files).

    python tools-generate-boards.py            # rewrite the five files
    python tools-generate-boards.py --check    # fail when the files on disk differ from what this would write

The boards follow CR-PANELS-BWR-RESEARCH.md (board numbers in the sources). Every board is placed unwired.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "mod/src/main/resources/data/bwr/panel_board")

# ---- frames and surfaces -----------------------------------------------------------------------------------------

DUPLEX, VERTICAL, BENCH, CONSOLE, BACK = "duplex", "vertical", "benchboard", "console", "back_panel"


class Surface:
    def __init__(self, sid, columns, rows):
        self.id, self.w, self.h = sid, columns * 16, rows * 16
        self.taken = {}

    def mark(self, x, y, w, h, what):
        if x < 0 or y < 0 or x + w > self.w or y + h > self.h:
            raise SystemExit("%s: %s %dx%d at %d,%d is off the surface (%dx%d)" % (self.id, what, w, h, x, y, self.w, self.h))
        for i in range(x, x + w):
            for j in range(y, y + h):
                if (i, j) in self.taken:
                    raise SystemExit("%s: %s %dx%d at %d,%d overlaps %s at %d,%d" % (
                        self.id, what, w, h, x, y, self.taken[(i, j)], i, j))
                self.taken[(i, j)] = what


def surfaces_of(frame):
    fid, kind = frame["id"], frame["kind"]
    if kind == DUPLEX:
        return [Surface(fid + ".board", frame["columns"], 2), Surface(fid + ".header", frame["columns"], 1),
                Surface(fid + ".bench", frame["columns"], 1)]
    return [Surface(fid + ".face", frame["columns"], 1 if kind == CONSOLE else frame.get("rows", 1))]


# ---- sizes (cells). The owner found the exact real sizes too small: the boards use about TWICE the real size (the new
# defaults of the gauge-polish branch), and every component gets its footprint written out, so a change of the type's
# default size can never change a board.

LAMP, PB, TOGGLE = (2, 2), (2, 2), (2, 2)
KEY = (2, 4)
ROT, ROT_BIG = (2, 4), (4, 6)
SW, SW_BIG = (2, 4), (4, 6)
KNOB, KNOB_BIG = (2, 2), (4, 4)
DIAL, DIAL_BIG = (4, 4), (6, 6)
EDGE_V, EDGE_H = (2, 6), (6, 2)
BARGAUGE = (2, 6)
RPS = (4, 2)
PUSHTURN = (2, 2)
ANN = (2, 2)
ANN_CTRL = (4, 2)   # the annunciator control module (ACK, RESET, SILENCE, TEST), at its double size
RECORDER = (16, 16)
SEG_W, SEG_H = 5, 8        # a seven segment display: 5 cells per digit, 8 tall (the older sizes, about twice the real ones)
PLATES = {(16, 4), (8, 4), (24, 4), (32, 4), (48, 4), (64, 4), (24, 6), (32, 8), (32, 10), (24, 8), (32, 12)}

T = {k: "bwr:" + k for k in (
    "nameplate lamp pushbutton toggle_switch rotary_selector control_switch key_switch setpoint_knob thumbwheel annunciator "
    "bar_graph dial_gauge edgewise_gauge_vertical edgewise_gauge_horizontal strip_chart seven_segment_display "
    "sixteen_segment_display character_lcd graphic_display mimic_line mimic_symbol full_core_display rod_select_matrix "
    "paperless_recorder bar_gauge rps_trip push_turn_switch annunciator_control".split())}

PLATE_W, INK_B = "ECECE4", "151515"   # white laminate, black engraving (CR-PANELS-BWR-RESEARCH.md 2.6)
PLATE_K, INK_W = "141414", "F0F0F0"   # black demarcation plate with white lettering: system titles
PLATE_R = "A81818"                    # red: critical controls and post-accident monitors (2.1)
PLATE_B = "1E4A9A"                    # blue: things outside the local system (2.1)
RED, GREEN, AMBER, WHITE, BLUE = "E02020", "20C040", "F0A000", "F4F4F4", "3070E0"


ERRORS = []


class Board:
    def __init__(self, name, title, description, source, order, frames):
        self.name, self.title, self.description, self.source, self.order = name, title, description, source, order
        self.frames = frames
        self.surf = {}
        for frame in frames:
            for s in surfaces_of(frame):
                self.surf[s.id] = s
        self.components, self.reserved, self.mimics, self.todo = [], [], [], []
        self.labels = set()

    def add(self, on, kind, x, y, size=None, label=None, caption=None, config=None, repeat=None, names=None):
        if kind not in T:
            raise SystemExit("unknown type " + kind)
        s = self.surf[on]
        if not size:
            raise SystemExit("every component needs its footprint written out: %s at %d,%d" % (kind, x, y))
        w, h = size
        if repeat:
            count = repeat.get("count", repeat.get("cols", 1) * repeat.get("rows", 1))
            cols = repeat.get("cols", repeat.get("count", 1))
            rows = repeat.get("rows", 1)
            step = repeat["step"]
            for n in range(count):
                s.mark(x + (n % cols) * step[0], y + (n // cols) * step[1], w, h, kind)
        else:
            s.mark(x, y, w, h, kind)
        self.check_text(kind, label, caption, config)
        entry = {"on": on, "type": T[kind], "at": [x, y]}
        if size:
            entry["size"] = [w, h]
        if label:
            entry["label"] = label
        if caption:
            entry["caption"] = caption
        if config:
            entry["config"] = config
        if repeat:
            entry["repeat"] = repeat
        if names:
            entry["names"] = names
        self.components.append(entry)

    # the longest texts the fields take (the game refuses a template that goes over)
    LIMITS = {"Text": 32, "Text2": 32, "Text3": 32, "CornerLeft": 16, "CornerRight": 16, "Tag": 8, "Line1": 12, "Line2": 12,
              "CloseText": 8, "NormalText": 8, "OpenText": 8, "RedText": 8, "GreenText": 8, "LegendA": 16, "LegendB": 16,
              "Legend1": 8, "Legend2": 8, "Names": 60, "Units": 12, "KeyCode": 24, "Format": 24}

    def check_text(self, kind, label, caption, config):
        if label:
            if len(label) > 16 or label in self.labels:
                ERRORS.append("%s: label %r is too long or used twice" % (self.name, label))
            self.labels.add(label)
        if caption and len(caption) > 32:
            ERRORS.append("%s: caption %r is longer than 32" % (self.name, caption))
        for key, value in (config or {}).items():
            if isinstance(value, str) and key in self.LIMITS and len(value) > self.LIMITS[key]:
                ERRORS.append("%s: %s %s=%r is longer than %d" % (self.name, kind, key, value, self.LIMITS[key]))

    # ---- helpers for the common parts

    def plate(self, on, x, y, w, h, *lines, style="white", label=None):
        if (w, h) not in PLATES:
            raise SystemExit("no nameplate size %dx%d" % (w, h))
        cfg = {}
        for key, text in zip(("Text", "Text2", "Text3"), lines):
            if text:
                cfg[key] = text
        cfg["Plate"], cfg["Ink"] = {"white": (PLATE_W, INK_B), "black": (PLATE_K, INK_W), "red": (PLATE_R, INK_W),
                                    "blue": (PLATE_B, INK_W)}[style]
        self.add(on, "nameplate", x, y, (w, h), label=label, config=cfg)

    def lamp(self, on, x, y, colour, caption=None, label=None, variant=None):
        cfg = {"Color": colour}
        if variant:
            cfg["Variant"] = variant
        self.add(on, "lamp", x, y, LAMP, label=label, caption=caption, config=cfg)

    def reserve(self, on, x, y, w, h, kind, note):
        self.surf[on].mark(x, y, w, h, "reserved")
        self.reserved.append({"on": on, "at": [x, y], "size": [w, h], "for": kind, "note": note})

    def rect(self, on, x, y, w, h, width=1, colour="101010"):
        """A demarcation line round a system: a closed mimic path (tape) with no arrows."""
        self.mimics.append({"on": on, "points": [[x, y], [x + w, y], [x + w, y + h], [x, y + h], [x, y]],
                            "style": {"Color": colour, "Width": width, "StartEnd": 0, "EndEnd": 0, "FlowEvery": 0}})

    def line(self, on, points, colour="2E7DE0", width=1, flow=16, end=1, start=0):
        self.mimics.append({"on": on, "points": [list(p) for p in points],
                            "style": {"Color": colour, "Width": width, "StartEnd": start, "EndEnd": end, "FlowEvery": flow,
                                      "Route": "elbow"}})

    def json(self):
        return {"format": 1, "title": self.title, "description": self.description, "source": self.source,
                "order": self.order, "todo": self.todo, "frames": self.frames, "components": self.components,
                "reserved": self.reserved, "mimic": self.mimics}

    def write(self, check):
        text = json.dumps(self.json(), indent=1) + "\n"
        path = os.path.join(OUT, self.name + ".json")
        if check:
            if not os.path.exists(path) or open(path, encoding="utf-8").read() != text:
                raise SystemExit("%s is out of date: run python tools-generate-boards.py" % path)
            return
        os.makedirs(OUT, exist_ok=True)
        open(path, "w", encoding="utf-8", newline="\n").write(text)
        print("%s: %d components, %d mimic paths, %d reserved" % (self.name, len(self.components), len(self.mimics),
                                                                  len(self.reserved)))


def duplex(columns, at=(0, 0, 0), fid="main"):
    return {"id": fid, "kind": DUPLEX, "columns": columns, "at": list(at)}


def frame(fid, kind, columns, rows, at):
    return {"id": fid, "kind": kind, "columns": columns, "rows": rows, "at": list(at)}


# ======================================================================================================================
# helpers shared by the boards
# ======================================================================================================================

BAND_RED, BAND_YELLOW = "A80E0E", "C4B02A"


def annunciator_box(b, on, x, y, size, tiles, colours=None, default=AMBER):
    """Annunciator windows (CR-PANELS-BWR-RESEARCH.md 2.3): `tiles` is a list of rows of (line1, line2) or None for a spare
    window; the most serious alarms are in the top row. `colours` gives the colour of each row."""
    w, h = size
    for r, row in enumerate(tiles):
        colour = colours[r] if colours else default
        for c, tile in enumerate(row):
            cfg = {"Color": colour}
            if tile:
                cfg["Line1"], cfg["Line2"] = tile
            b.add(on, "annunciator", x + c * w, y + r * h, size, config=cfg)


def _bands(cfg, bands, count):
    for i in range(count):
        if i < len(bands):
            cfg["Band%dLow" % (i + 1)], cfg["Band%dHigh" % (i + 1)], cfg["Band%dColor" % (i + 1)] = bands[i]
        else:
            cfg["Band%dLow" % (i + 1)], cfg["Band%dHigh" % (i + 1)] = 0, 0


def dial(b, on, x, y, size, lo, hi, units, bands=(), caption=None, label=None, red=False):
    cfg = {"Min": lo, "Max": hi, "Units": units}
    _bands(cfg, bands, 3)
    if red:
        cfg["Variant"] = "red_pointer"
    b.add(on, "dial_gauge", x, y, size, label=label, caption=caption, config=cfg)


def edge(b, on, vertical, x, y, size, lo, hi, units, bands=(), caption=None, label=None, red=False):
    cfg = {"Min": lo, "Max": hi, "Units": units}
    _bands(cfg, bands, 3)
    if red:
        cfg["Variant"] = "red_pointer"
    b.add(on, "edgewise_gauge_vertical" if vertical else "edgewise_gauge_horizontal", x, y, size, label=label, caption=caption,
          config=cfg)


def bar(b, on, x, y, size, lo, hi, units, bands, caption=None, label=None):
    cfg = {"Preset": "custom", "Min": lo, "Max": hi, "Units": units}
    _bands(cfg, bands, 4)
    b.add(on, "bar_gauge", x, y, size, label=label, caption=caption, config=cfg)


def rotary(b, on, x, y, size, names, label=None, caption=None, style=None, variant=None):
    cfg = {"Positions": len(names), "Names": ",".join(names)}
    if style:
        cfg["Style"] = style
    if variant:
        cfg["Variant"] = variant
    b.add(on, "rotary_selector", x, y, size, label=label, caption=caption, config=cfg)


def pump_switch(b, on, x, y, size=SW_BIG, label=None, caption=None):
    # Left STOP, right START (CR-PANELS-BWR-RESEARCH.md 2.4: breaker switches TRIP left, CLOSE right); red = running.
    b.add(on, "control_switch", x, y, size, label=label, caption=caption,
          config={"CloseText": "STOP", "NormalText": "AUTO", "OpenText": "START", "RedText": "RUN", "GreenText": "STOP",
                  "FlagRed": "open"})


def seg(b, on, x, y, digits, fmt, label=None, caption=None, colour=AMBER):
    b.add(on, "seven_segment_display", x, y, (digits * SEG_W, SEG_H), label=label, caption=caption,
          config={"Format": fmt, "Color": colour})


def push(b, on, x, y, caption=None, label=None, colour=None, variant=None):
    cfg = {}
    if colour:
        cfg["Color"] = colour
    if variant:
        cfg["Variant"] = variant
    b.add(on, "pushbutton", x, y, PB, label=label, caption=caption, config=cfg or None)


def unit_valve(b, on, x, y, text, tag):
    """A motor operated valve in the P601 pattern (CR-PANELS-BWR-RESEARCH.md 2.2, 2.4, 2.6): the label plate above, a
    CLOSE/OPEN selector between a green (closed) lamp on its left and a red (open) lamp on its right. 16 x 8 cells."""
    b.plate(on, x, y, 16, 4, text)
    b.lamp(on, x + 3, y + 5, GREEN, label=tag + "-CLS")
    rotary(b, on, x + 7, y + 4, ROT, ["CLOSE", "OPEN"], label=tag)
    b.lamp(on, x + 11, y + 5, RED, label=tag + "-OPN")


def unit_pump(b, on, x, y, text, tag):
    """A pump with a pistol grip switch between a green (stopped) and a red (running) lamp. 16 x 8 cells."""
    b.plate(on, x, y, 16, 4, text)
    b.lamp(on, x + 3, y + 5, GREEN, label=tag + "-STP")
    pump_switch(b, on, x + 7, y + 4, SW, label=tag)
    b.lamp(on, x + 11, y + 5, RED, label=tag + "-RUN")


CONTROLLER_TODO = ("TODO controller station: when the manual/auto controller station part exists, place it here "
                   "(setpoint and measured value indicators, AUTO/MAN buttons with lamps, raise/lower buttons) and "
                   "delete this entry. See CR-PANELS-BWR-RESEARCH.md section 4, gap 1.")
JOYSTICK_TODO = ("TODO joystick bypass switch: the four-way bypass joystick does not exist yet "
                 "(CR-PANELS-BWR-RESEARCH.md section 4, gap 2); place it here and delete this entry.")

# ======================================================================================================================
# Board 3 (research): RPS / scram and the reactor mode switch  -- a GE BWR/4 P603 with the RPS A/B boards P609, P611
# ======================================================================================================================

def rps_board():
    b = Board("reactor_scram_rps", "RPS scram and mode switch",
              "Reactor protection: four manual scram buttons in two guarded assemblies, the four position reactor mode switch "
              "with its key, scram reset, ARI, the white scram group lamps and the scram annunciators of both trip systems.",
              "CR-PANELS-BWR-RESEARCH.md board 3 (GE BWR/4 panels P603, P609, P611)", 3, [duplex(5)])
    H, V, N = "main.header", "main.board", "main.bench"

    # -- header: the scram annunciators of trip system A (left) and B (right), 8 x 6 windows each. Red = reactor protection
    # information after a scram, amber = inoperable, white = off normal (NMP-2 convention).
    def rows(s):
        return [
            [("RPV LEVEL", "LOW L3"), ("RPV PRESS", "HIGH"), ("DW PRESS", "HIGH"), ("MSIV", "CLOSURE"), ("TSV", "CLOSURE"),
             ("TCV FAST", "CLOSURE"), ("SDV LEVEL", "HIGH"), ("MODE SW", "SHUTDOWN")],
            [("APRM", "UPSCALE"), ("APRM", "FLOW BIAS"), ("APRM", "INOP"), ("IRM", "UPSCALE"), ("IRM", "INOP"),
             ("MAN SCRAM", s + "1"), ("AUTO SCRAM", "CHAN " + s + "1"), ("AUTO SCRAM", "CHAN " + s + "2")],
            [("SRM", "INOP"), ("RBM", "ROD BLOCK"), ("TURB", "TRIP"), ("MAIN COND", "VACUUM LOW"), ("RECIRC", "TRIP"),
             ("RPS " + s, "TRIPPED"), ("SCRAM", "DISCH VOL"), ("SCRAM", "AIR HEADER")],
            [("RPS " + s, "GROUP 1"), ("RPS " + s, "GROUP 2"), ("RPS " + s, "GROUP 3"), ("RPS " + s, "GROUP 4"),
             ("ARI", "INITIATED"), ("RPT", "INITIATED"), ("RX SCRAM", "RESET"), ("SCRAM", "RESET REQD")],
            [("RPS " + s, "BYPASSED"), ("IRM " + s, "BYPASSED"), ("APRM " + s, "BYPASSED"), ("SDV", "BYPASSED"),
             ("RPS " + s + " MG", "TROUBLE"), ("RPS " + s, "UNDERVOLT"), ("RPS " + s, "OVERVOLT"), ("SCRAM", "SOLENOID")],
            [("RPS " + s, "POWER ON"), ("MODE SW", "REFUEL"), ("MODE SW", "STARTUP"), ("MODE SW", "RUN"), ("ARI", "ARMED"),
             ("SCRAM", "RESET"), ("BKUP SCRAM", s + "2"), None]]
    colours = [RED, RED, RED, RED, AMBER, WHITE]
    annunciator_box(b, H, 2, 2, ANN, rows("A"), colours)
    annunciator_box(b, H, 62, 2, ANN, rows("B"), colours)
    b.plate(H, 28, 2, 24, 4, "REACTOR SCRAM", style="red")
    b.plate(H, 28, 8, 24, 6, "RPS TRIP SYSTEMS", "A (LEFT)    B (RIGHT)")

    # -- board: scram group lamps (white), the mode plaque, the trip setpoints, the SDV bypass keylock
    b.plate(V, 0, 0, 64, 4, "REACTOR PROTECTION SYSTEM  (RPS)", style="black")
    b.plate(V, 64, 0, 16, 4, "P603 P609 P611", style="black")
    for trip, y in (("A", 6), ("B", 17)):
        b.plate(V, 2, y, 24, 4, "RPS %s SCRAM GROUPS" % trip)
        for g in range(4):
            b.lamp(V, 4 + 6 * g, y + 5, WHITE, caption="%d" % (g + 1), label="SCR-GRP-%s%d" % (trip, g + 1))
    b.plate(V, 0, 27, 32, 4, "WHITE LAMP LIT = GROUP SCRAMMED")
    b.plate(V, 32, 6, 32, 8, "REACTOR MODE SWITCH", "1 SHUTDOWN    2 REFUEL", "3 STARTUP/HS    4 RUN")
    b.plate(V, 32, 15, 32, 8, "RPS SETPOINTS", "L3 +12.5 IN    1043 PSIG", "DW 1.69 PSIG    APRM 118 PCT")
    b.plate(V, 32, 25, 24, 6, "SDV HI LEVEL", "SCRAM BYPASS")
    b.add(V, "key_switch", 58, 24, KEY, label="SDV-BYPASS", caption="KEY")
    b.rect(V, 1, 5, 28, 21)
    b.plate(V, 64, 6, 16, 4, "ANN ACK / RESET")
    b.add(V, "annunciator_control", 70, 11, ANN_CTRL, label="ANN-CTRL")

    # -- bench: manual scram, mode switch and key, scram reset, ARI, the bypass joysticks to come
    # The manual scram is the A1 and B1 trip channels. A2 and B2 are BACKUP channels, kept apart (the far end of the bench) so a
    # fire that destroys A1 / B1 leaves them; each button keeps its own output (a, b), so A1/B1 are wired to the trip and A2/B2 to
    # the backup channels. The legends are the assembly's own, short enough for the part.
    b.plate(N, 0, 0, 16, 4, "MAN SCRAM A1/B1")
    b.add(N, "rps_trip", 6, 5, RPS, label="MAN-SCRAM-A1B1", config={"LegendA": "A1", "LegendB": "B1"})
    b.plate(N, 16, 0, 24, 4, "REACTOR MODE")
    rotary(b, N, 23, 5, ROT_BIG, ["SHUTDOWN", "REFUEL", "STARTUP", "RUN"], label="MODE-SW", style="westinghouse", variant="wl2")
    b.add(N, "key_switch", 31, 6, KEY, label="MODE-KEY", caption="MODE KEY")
    b.plate(N, 40, 0, 16, 4, "SCRAM RESET")
    b.add(N, "control_switch", 46, 5, SW_BIG, label="SCRAM-RESET", caption="RESET A / B",
          config={"Spring": True, "CloseText": "RESET A", "NormalText": "NORMAL", "OpenText": "RESET B", "RedText": "SCRAM",
                  "GreenText": "RESET"})
    b.plate(N, 56, 0, 8, 4, "ARI")
    push(b, N, 56, 6, caption="A", label="ARI-A", colour="C02020", variant="guarded")
    push(b, N, 60, 6, caption="B", label="ARI-B", colour="C02020", variant="guarded")
    b.plate(N, 64, 0, 16, 4, "BACKUP A2/B2", style="red")
    b.add(N, "rps_trip", 70, 5, RPS, label="BKUP-SCRAM-A2B2", config={"LegendA": "BK A2", "LegendB": "BK B2"})
    b.plate(N, 64, 9, 16, 4, "FIRE BACKUP")
    b.plate(V, 64, 21, 16, 4, "IRM/APRM BYPASS")
    b.reserve(V, 64, 26, 16, 5, "bwr:joystick_bypass_switch", JOYSTICK_TODO)
    b.todo += ["The IRM / APRM bypass joysticks of P603 (CR-PANELS-BWR-RESEARCH.md board 3) have no part yet: see the reserved space on main.board.",
               "The manual scram is the A1 / B1 trip channels; the A2 / B2 assembly at the far end of main.bench is the fire BACKUP and "
               "keeps its own outputs. The research report is silent on the physical separation of the backup channels: the owner's rule "
               "(a fire that destroys A1 must not take A2) decides the placement.",
               "Scram group lamps are single white lamps; a scram group lamp matrix part (research gap 4) would replace the two rows on main.board."]
    return b


# ======================================================================================================================
# Board 2 (research): RPV level, pressure and flow monitors
# ======================================================================================================================

def rpv_board():
    b = Board("rpv_level_pressure_flow", "RPV level, pressure and flow",
              "The how-is-the-reactor board: nine level indicators on the five BWR/4 ranges with the trip level bands, three "
              "pressure ranges, core, steam and feed flow, a seven trace recorder and a vessel temperature recorder.",
              "CR-PANELS-BWR-RESEARCH.md board 2 (GE BWR/4: levels 0 to +60 narrow, -150 to +60 wide, 0 to +180 upset, "
              "0 to +400 shutdown, -308 to -108 fuel zone; pressure 850-1050, 0-1200, 0-1500 psig)", 2, [duplex(5)])
    H, V, N = "main.header", "main.board", "main.bench"
    red, yel = BAND_RED, BAND_YELLOW

    annunciator_box(b, H, 2, 2, ANN, [
        [("RPV LEVEL", "HIGH L8"), ("RPV LEVEL", "HIGH L7"), ("RPV LEVEL", "LOW L4"), ("RPV LEVEL", "LOW L3"),
         ("RPV LEVEL", "LOW L2"), ("RPV LEVEL", "LOW L1"), ("RPV PRESS", "HIGH"), ("RPV PRESS", "LOW")],
        [("LEVEL", "DEVIATION"), ("LVL INST", "DISAGREE"), ("FW STARTUP", "LVL LOW"), ("FW CTRL", "TROUBLE"),
         ("SRV", "OPEN"), ("RPV METAL", "TEMP HIGH"), ("RPV HEAD", "TEMP LOW"), ("RPV HEAD", "VENT OPEN")],
        [("RPV PRESS", "1025 PSIG"), ("RPV PRESS", "1043 PSIG"), ("RPV PRESS", "1120 PSIG"), None, None, None, None, None]],
        [RED, AMBER, WHITE])
    annunciator_box(b, H, 62, 2, ANN, [
        [("CORE FLOW", "LOW"), ("CORE FLOW", "HIGH"), ("JET PUMP", "DP HIGH"), ("JET PUMP", "FLOW LOW"),
         ("RECIRC A", "FLOW HIGH"), ("RECIRC B", "FLOW HIGH"), ("STEAM FLOW", "HIGH"), ("FEEDWATER", "FLOW HIGH")],
        [("CORE PLATE", "DP HIGH"), ("CORE PLATE", "DP LOW"), ("FLOW", "MISMATCH"), ("LOOP A", "FLOW LOW"),
         ("LOOP B", "FLOW LOW"), ("STM FLOW", "FW MISMATCH"), None, None]],
        [AMBER, WHITE])
    b.plate(H, 28, 2, 24, 4, "RPV LEVEL / PRESS / FLOW", style="black")
    b.plate(H, 28, 7, 24, 8, "TRIP LEVELS (IN.)", "L8 +56.5    L7 +40    L4 +33.5", "L3 +12.5   L2 -38   L1 -132.5")

    b.plate(V, 0, 0, 64, 4, "RPV LEVEL, PRESSURE AND FLOW", style="black")
    b.plate(V, 64, 0, 16, 4, "P603 / P619", style="black")
    b.plate(V, 0, 5, 48, 4, "LEVEL IN. ABOVE INSTRUMENT ZERO")
    narrow = [(0, 12.5, red), (12.5, 33.5, yel), (40, 56.5, yel), (56.5, 60, red)]
    wide = [(-150, -132.5, red), (-132.5, 12.5, yel), (56.5, 60, red)]
    x = lambda i: 3 + 7 * i
    for i, name in enumerate("ABC"):
        bar(b, V, x(i), 10, BARGAUGE, 0, 60, "INCHES", narrow, caption="N" + name, label="LVL-NAR-" + name)
    for i, name in enumerate("AB"):
        bar(b, V, x(3 + i), 10, BARGAUGE, -150, 60, "INCHES", wide, caption="W" + name, label="LVL-WIDE-" + name)
    edge(b, V, True, x(5), 10, EDGE_V, 0, 180, "INCHES", [(56.5, 180, red)], caption="UP", label="LVL-UPSET")
    edge(b, V, True, x(6), 10, EDGE_V, 0, 400, "INCHES", [], caption="SD", label="LVL-SHUTDOWN")
    for i, name in enumerate("AB"):
        edge(b, V, True, x(7 + i), 10, EDGE_V, -308, -108, "INCHES", [(-308, -158, red)], caption="F" + name,
             label="LVL-FZ-" + name)
    b.rect(V, 1, 4, 62, 15)
    # pressure
    b.plate(V, 0, 20, 32, 4, "RPV PRESSURE  (PSIG)")
    p_yel = (1025, 1043, yel)
    dial(b, V, 3, 25, DIAL, 850, 1050, "PSIG", [p_yel, (1043, 1050, red)], caption="NARR", label="PRESS-NARROW")
    dial(b, V, 13, 25, DIAL, 0, 1200, "PSIG", [p_yel, (1043, 1200, red)], caption="NORM", label="PRESS-NORMAL")
    dial(b, V, 23, 25, DIAL, 0, 1500, "PSIG", [p_yel, (1120, 1500, red)], caption="WIDE", label="PRESS-WIDE")
    b.rect(V, 1, 20, 30, 11)
    # flow
    b.plate(V, 32, 20, 32, 4, "FLOW  (MLB/HR)")
    edge(b, V, False, 35, 26, EDGE_H, 0, 120, "MLB/HR", [(105, 120, yel)], caption="CORE", label="FLOW-CORE")
    edge(b, V, False, 44, 26, EDGE_H, 0, 16, "MLB/HR", [(14, 16, yel)], caption="STEAM", label="FLOW-STEAM")
    edge(b, V, False, 53, 26, EDGE_H, 0, 16, "MLB/HR", [(14, 16, yel)], caption="FEED", label="FLOW-FEED")
    # the seven chart set of the training plants (CR-PANELS-BWR-RESEARCH.md board 2)
    traces = [("APRM PWR", "%", 0, 125), ("RX PRESS", "PSIG", 0, 1200), ("RX STM FLOW", "MLB/HR", 0, 16),
              ("TURB STM FLOW", "MLB/HR", 0, 16), ("FW FLOW", "MLB/HR", 0, 16), ("RX LVL NAR", "IN", 0, 60),
              ("CORE FLOW", "MLB/HR", 0, 120)]
    cfg = {"Channels": 12}   # a DX1012: 12 channels, seven in use
    for c, (tag, unit, lo, hi) in enumerate(traces):
        cfg["Ch%02dTag" % (c + 1)], cfg["Ch%02dUnit" % (c + 1)] = tag, unit
        cfg["Ch%02dLo" % (c + 1)], cfg["Ch%02dHi" % (c + 1)] = lo, hi
    for c in range(len(traces), 12):
        cfg["Ch%02dOn" % (c + 1)] = False
    b.add(V, "paperless_recorder", 64, 16, RECORDER, label="REC-7TRACE", config=cfg)
    seg(b, V, 64, 5, 3, "{0:3.0f}", label="LVL-READOUT", caption="LEVEL")

    # -- bench: the controller stations that do not exist yet, and the vessel temperature recorder
    b.plate(N, 0, 0, 24, 4, "FEEDWATER LEVEL CONTROL")
    b.plate(N, 24, 0, 24, 4, "RECIRC FLOW CONTROL")
    b.reserve(N, 0, 4, 24, 12, "bwr:controller_station",
              "Feedwater master level controller (FWCS): " + CONTROLLER_TODO)
    b.reserve(N, 24, 4, 24, 12, "bwr:controller_station",
              "Recirculation master flow controller and the M/A transfer stations: " + CONTROLLER_TODO)
    b.plate(N, 72, 0, 8, 4, "ANN")
    b.add(N, "annunciator_control", 74, 6, ANN_CTRL, label="ANN-CTRL")
    b.add(N, "strip_chart", 56, 0, (16, 16), label="REC-VESSEL-TEMP", config={"Pens": 2, "Min": 0, "Max": 600, "Span": 600})
    b.todo += ["Feedwater (master level controller, reactor feed pump M/A) and recirculation (master flow controller, M/A "
               "transfer stations) controls are reserved on main.bench for the manual/auto controller station part.",
               "A level scale with printed numbered trip marks (research section 4, gap 12) would replace the bands and the "
               "TRIP LEVELS plate."]
    return b


# ======================================================================================================================
# Board 5 (research): neutron monitoring -- SRM, IRM, APRM, LPRM, RBM
# ======================================================================================================================

def neutron_board():
    b = Board("neutron_monitoring", "Neutron monitoring",
              "Startup to power: eight IRMs on the 125 division scale with their range switches, six APRM bar graphs with the "
              "118 percent scram band, sixteen LPRM meters, four SRM count rates and two two pen recorders.",
              "CR-PANELS-BWR-RESEARCH.md board 10 (GE BWR/4 chapter 5: IRM rod block 108/125, scram 120/125, downscale 5/125; "
              "APRM fixed scram 118 percent, flow biased 0.66W + 51)", 5, [duplex(6)])
    H, V, N = "main.header", "main.board", "main.bench"
    red, yel = BAND_RED, BAND_YELLOW

    annunciator_box(b, H, 2, 2, ANN, [
        [("APRM", "UPSCALE"), ("APRM", "FLOW BIAS"), ("APRM", "INOP"), ("IRM", "UPSCALE"), ("IRM", "INOP"), ("SRM", "INOP"),
         ("RBM", "UPSCALE"), ("LPRM", "UPSCALE")],
        [("APRM", "ROD BLOCK"), ("IRM", "ROD BLOCK"), ("SRM", "ROD BLOCK"), ("RBM", "ROD BLOCK"), ("APRM", "DOWNSCALE"),
         ("IRM", "DOWNSCALE"), ("SRM", "DOWNSCALE"), ("RBM", "DOWNSCALE")],
        [("APRM", "BYPASSED"), ("IRM", "BYPASSED"), ("SRM", "BYPASSED"), ("RBM", "BYPASSED"), ("LPRM", "BYPASSED"),
         ("TIP", "BYPASSED"), None, None]], [RED, AMBER, WHITE])
    annunciator_box(b, H, 78, 2, ANN, [
        [("SRM", "PERIOD SHORT"), ("IRM", "PERIOD SHORT"), ("SRM DET", "NOT FULL IN"), ("IRM DET", "NOT FULL IN"),
         ("LPRM", "DOWNSCALE"), ("LPRM", "HIGH"), ("APRM", "DISAGREE"), ("APRM", "CAL")],
        [("TIP", "MACHINE"), ("TIP", "SHEAR VLV"), ("TIP", "IN CORE"), ("OPRM", "ALARM"), ("OPRM", "TRIP"), None, None, None]],
        [AMBER, WHITE])
    b.plate(H, 34, 2, 24, 4, "NEUTRON MONITORING", style="black")
    b.plate(H, 34, 7, 24, 8, "TRIP SETPOINTS", "APRM SCRAM 118 PCT", "IRM SCRAM 120/125")

    # -- board: IRM (left), APRM (middle), LPRM (right)
    b.plate(V, 0, 0, 64, 4, "SRM / IRM / APRM / LPRM / RBM", style="black")
    b.plate(V, 64, 0, 32, 4, "P603", style="black")
    b.plate(V, 0, 5, 32, 4, "IRM  (125 DIVISIONS)")
    for i, name in enumerate("ABCDEFGH"):
        edge(b, V, True, 3 + 4 * i, 10, EDGE_V, 0, 125, "DIV", [(0, 5, yel), (108, 120, yel), (120, 125, red)],
             caption=name, label="IRM-" + name)
        rotary(b, V, 3 + 4 * i, 19, ROT, ["R1", "R2", "R3", "R4", "R5", "R6"], label="IRM-%s-RNG" % name)
    for i, name in enumerate(("IRM", "APRM", "SRM", "RBM")):
        b.add(V, "control_switch", 3 + 8 * i, 25, SW, caption=name, label=name + "-BYPASS",
              config={"Spring": False, "CloseText": "NORMAL", "NormalText": "", "OpenText": "BYPASS", "RedText": "BYPASS",
                      "GreenText": "NORMAL"})
    b.rect(V, 1, 4, 32, 27)
    b.plate(V, 38, 5, 32, 4, "APRM  (PERCENT POWER)")
    for i, name in enumerate("ABCDEF"):
        bar(b, V, 41 + 5 * i, 10, BARGAUGE, 0, 125, "PCT PWR", [(100, 118, yel), (118, 125, red)], caption=name,
            label="APRM-" + name)
    b.rect(V, 37, 4, 33, 16)
    b.plate(V, 38, 22, 24, 4, "ANN ACK / RESET")
    b.add(V, "annunciator_control", 45, 27, ANN_CTRL, label="ANN-CTRL")
    b.plate(V, 72, 5, 24, 4, "LPRM  (W / CM2)")
    for g, name in enumerate("ABCD"):
        gx, gy = 74 + (g % 2) * 11, 10 + (g // 2) * 11
        for k in range(4):
            dial(b, V, gx + (k % 2) * 4, gy + (k // 2) * 4, DIAL, 0, 125, "PCT", [(100, 115, yel), (115, 125, red)],
                 label="LPRM-%s%d" % (name, k + 1))
    b.rect(V, 73, 4, 22, 27)

    # -- bench: the two pen recorders, SRM counts
    b.add(N, "strip_chart", 0, 0, RECORDER, label="REC-IRM-1", config={"Pens": 2, "Min": 0, "Max": 125, "Span": 600})
    b.add(N, "strip_chart", 16, 0, RECORDER, label="REC-APRM-1", config={"Pens": 2, "Min": 0, "Max": 125, "Span": 600})
    b.plate(N, 32, 0, 32, 4, "SRM  COUNTS PER SECOND")
    for i, name in enumerate("ABCD"):
        seg(b, N, 33 + 16 * i, 6, 3, "{0:3.0f}", caption="SRM " + name, label="SRM-%s-CPS" % name)
    b.todo += ["The IRM range switch has 10 positions on the real plant; the rotary selector part stops at 6, so R1 to R6 stand "
               "in for it (research board 10).",
               "IRM / APRM / RBM bypass joysticks (research gap 2) would sit with the bypass switches on main.board.",
               "LPRM meters are single dials; a grouped LPRM meter bank is a layout choice, not a part."]
    return b


# ======================================================================================================================
# Board 4 (research): the ECCS bench -- RHR / LPCI / core spray divisions I and II and the ADS / SRV section (a P601)
# ======================================================================================================================

def symbol(b, on, x, y, size, kind, caption=None, label=None):
    b.add(on, "mimic_symbol", x, y, (size, size), label=label, caption=caption, config={"Symbol": kind})


def eccs_division(b, x0, loop):
    """One division, 80 cells wide: annunciators, mimic and meters on the board, ten pumps and valves on the bench."""
    H, V, N = "main.header", "main.board", "main.bench"
    roman = "I" if loop == "A" else "II"
    l = loop
    annunciator_box(b, H, x0 + 2, 2, ANN, [
        [("LPCI " + l, "INITIATED"), ("CS " + l, "INITIATED"), ("RHR " + l, "PUMP TRIP"), ("CS " + l, "PUMP TRIP"),
         ("RHR " + l, "MIN FL OPEN"), ("CS " + l, "MIN FL OPEN"), ("RHR " + l, "SUCT P LOW"),
         ("CS " + l, "SUCT P LOW"), ("RHR " + l, "FLOW LOW"), ("CS " + l, "FLOW LOW"), ("CS " + l + " HDR", "DP LOW"),
         ("RHR " + l, "XTIE OPEN"), ("DIV " + roman, "DC LOST"), ("DIV " + roman, "ECCS TRIP")],
        [("RHR " + l, "INJ VLV OPEN"), ("CS " + l, "INJ VLV OPEN"), ("RHR " + l, "SDC ISOL"), ("RHR " + l, "HX BYPASS"),
         ("RHR " + l, "POOL COOLING"), ("RHR " + l, "SPRAY ON"), ("SUPP POOL", "TEMP HIGH"), ("SUPP POOL", "LEVEL LOW"),
         ("SUPP POOL", "LEVEL HIGH"), ("DIV " + roman, "ECCS INOP"), None, None, None, None],
        [("RHR " + l, "RUNNING"), ("CS " + l, "RUNNING"), ("RHR " + l, "SHUTDN COOL"), ("RHR " + l, "TEST"),
         ("CS " + l, "TEST"), None, None, None, None, None, None, None, None, None]],
        [RED, AMBER, WHITE])
    b.plate(H, x0 + 34, 2, 24, 4, "DIVISION " + roman, style="black")
    b.plate(H, x0 + 34, 7, 24, 6, "RHR " + l + "    LPCI", "CORE SPRAY " + l)

    # -- board
    b.plate(V, x0, 0, 64, 4, "DIV %s  RHR %s / CORE SPRAY %s" % (roman, l, l), style="black")
    b.plate(V, x0 + 64, 0, 16, 4, "P601", style="black")
    # RHR path: suppression pool -> pump -> injection valve -> RPV (coloured by mode: LPCI blue)
    symbol(b, V, x0 + 2, 6, 8, "tank", "SUPP POOL", "SP-" + l)
    symbol(b, V, x0 + 18, 6, 8, "pump", "RHR PUMP " + l, "MIM-RHR-" + l)
    symbol(b, V, x0 + 32, 6, 8, "valve", "LPCI INJ", "MIM-INJ-" + l)
    symbol(b, V, x0 + 46, 6, 8, "tank", "RPV", "RPV-" + l)
    b.line(V, [(x0 + 10, 10), (x0 + 18, 10)], colour="2E7DE0")
    b.line(V, [(x0 + 26, 10), (x0 + 32, 10)], colour="2E7DE0")
    b.line(V, [(x0 + 40, 10), (x0 + 46, 10)], colour="2E7DE0")
    # core spray path
    symbol(b, V, x0 + 2, 19, 8, "tank", "SUPP POOL", "SP2-" + l)
    symbol(b, V, x0 + 18, 19, 8, "pump", "CS PUMP " + l, "MIM-CS-" + l)
    symbol(b, V, x0 + 32, 19, 8, "valve", "CS INJ", "MIM-CSINJ-" + l)
    symbol(b, V, x0 + 46, 19, 8, "tank", "SPARGER", "SPARGER-" + l)
    b.line(V, [(x0 + 10, 23), (x0 + 18, 23)], colour="30A050")
    b.line(V, [(x0 + 26, 23), (x0 + 32, 23)], colour="30A050")
    b.line(V, [(x0 + 40, 23), (x0 + 46, 23)], colour="30A050")
    # meters
    dial(b, V, x0 + 57, 6, DIAL, 0, 400, "PSIG", [(238, 400, BAND_RED)], caption="RHR " + l, label="RHR-%s-PRESS" % l)
    dial(b, V, x0 + 69, 6, DIAL, 0, 600, "PSIG", [(500, 600, BAND_RED)], caption="CS " + l, label="CS-%s-PRESS" % l)
    edge(b, V, False, x0 + 57, 16, EDGE_H, 0, 5000, "GPM", [(0, 800, BAND_YELLOW)], caption="RHR " + l,
         label="RHR-%s-FLOW" % l)
    edge(b, V, False, x0 + 69, 16, EDGE_H, 0, 5000, "GPM", [(0, 650, BAND_YELLOW)], caption="CS " + l,
         label="CS-%s-FLOW" % l)
    b.plate(V, x0 + 54, 21, 24, 4, "ECCS MANUAL INIT")
    push(b, V, x0 + 58, 26, caption="ARM", label="ARM-" + l, colour="C02020", variant="guarded")
    push(b, V, x0 + 70, 26, caption="DEPRESS", label="DEPRESS-" + l, colour="C02020", variant="guarded")

    # -- bench: pumps and valves
    unit_pump(b, N, x0, 0, "RHR PUMP " + l, "RHR-P-" + l)
    unit_valve(b, N, x0 + 16, 0, "SUPP POOL SUCT", "SP-SUCT-" + l)
    unit_valve(b, N, x0 + 32, 0, "LPCI INJECTION", "LPCI-INJ-" + l)
    unit_valve(b, N, x0 + 48, 0, "HX BYPASS", "HX-BYP-" + l)
    unit_valve(b, N, x0 + 64, 0, "HX OUTLET", "HX-OUT-" + l)
    unit_pump(b, N, x0, 8, "CS PUMP " + l, "CS-P-" + l)
    unit_valve(b, N, x0 + 16, 8, "CS INJECTION", "CS-INJ-" + l)
    unit_valve(b, N, x0 + 32, 8, "CS MIN FLOW", "CS-MIN-" + l)
    unit_valve(b, N, x0 + 48, 8, "RHR TEST RTN", "RHR-TST-" + l)
    unit_valve(b, N, x0 + 64, 8, "DW SPRAY", "DW-SPRAY-" + l)


def eccs_board():
    b = Board("eccs_p601", "ECCS bench (P601)",
              "The reactor core cooling board: RHR / LPCI and low pressure core spray for divisions I and II with their pump and "
              "valve switches and red/green lamps, flow and pressure meters, the mimic, and the ADS / SRV section.",
              "CR-PANELS-BWR-RESEARCH.md boards 4 and 6 (GE P601 reactor core cooling; ADS switches of the BWR/4 manual)", 4,
              [duplex(13)])
    H, V, N = "main.header", "main.board", "main.bench"
    eccs_division(b, 0, "A")
    eccs_division(b, 80, "B")
    # ---- ADS / SRV, x 160 .. 208
    x0 = 160
    annunciator_box(b, H, x0 + 2, 2, ANN, [
        [("ADS", "INITIATED"), ("ADS", "TIMER ON"), ("SRV", "OPEN"), ("SRV", "LEAKING")],
        [("ADS DIV I", "INHIBITED"), ("ADS DIV II", "INHIBITED"), ("ADS", "LOGIC INOP"), ("ADS", "AIR LOW")],
        [("ADS DIV I", "ARMED"), ("ADS DIV II", "ARMED"), None, None]], [RED, AMBER, WHITE])
    b.plate(H, x0 + 14, 2, 24, 4, "ADS / SRV", style="black")
    b.plate(H, x0 + 14, 7, 24, 6, "ADS: L1 AND L3 AND", "LPCI/CS PUMP, 105 SEC")
    b.plate(V, x0, 0, 48, 4, "ADS / SAFETY RELIEF VALVES", style="black")
    names = ["ADS %s" % c for c in "ABCDEFG"] + ["SRV %s" % c for c in "HJKL"]
    for i, name in enumerate(names):
        row, col = divmod(i, 6)
        ux, uy = x0 + col * 8, 5 + row * 9
        cfg = {"Text": name, "Plate": PLATE_W, "Ink": INK_B}
        if i < 7:
            cfg["Stripe"] = True
        b.add(V, "nameplate", ux, uy, (8, 4), config=cfg)
        b.lamp(V, ux, uy + 5, GREEN, label="SRV-%s-CLS" % name[-1])
        rotary(b, V, ux + 3, uy + 4, ROT, ["CLOSE", "AUTO", "OPEN"], label="SRV-" + name[-1], style="oiltight",
               variant="srv_open_auto_close")
        b.lamp(V, ux + 6, uy + 5, RED, label="SRV-%s-OPN" % name[-1])
    b.plate(V, x0, 23, 32, 8, "RED STRIPE = ADS VALVE", "SET PRESSURES ON", "VALVE PLATES")
    b.plate(V, x0 + 32, 23, 16, 4, "BACKUP XFER")
    b.add(V, "key_switch", x0 + 38, 27, KEY, label="ADS-XFER", caption="KEY")
    b.plate(N, x0 + 32, 10, 16, 4, "ANN ACK / RESET")
    b.add(N, "annunciator_control", x0 + 38, 14, ANN_CTRL, label="ANN-CTRL")
    # bench: inhibit, manual initiation (turn to arm, then push), reset
    for i, roman in enumerate(("I", "II")):
        b.plate(N, x0 + 16 * i, 0, 16, 4, "ADS INHIBIT " + roman)
        b.add(N, "push_turn_switch", x0 + 7 + 16 * i, 5, PUSHTURN, label="ADS-INH-" + roman,
              config={"Spring": False, "Legend1": "AUTO", "Legend2": "INHIBIT"})
        b.plate(N, x0 + 16 * i, 8, 16, 4, "ADS INITIATE " + roman)
        b.add(N, "push_turn_switch", x0 + 7 + 16 * i, 13, PUSHTURN, label="ADS-INI-" + roman,
              config={"Spring": True, "Legend1": "SAFE", "Legend2": "ARM"})
    b.plate(N, x0 + 32, 0, 16, 4, "ADS RESET")
    push(b, N, x0 + 36, 5, caption="DIV I", label="ADS-RST-I", colour="C02020")
    push(b, N, x0 + 42, 5, caption="DIV II", label="ADS-RST-II", colour="C02020")
    b.todo += ["Valve switches are rotary selectors with a green and a red lamp each; a valve symbol with an embedded lamp "
               "(research board 4, Missing) would fold the mimic symbol and the two lamps together.",
               "RHR is shown for the LPCI mode (blue) and core spray (green); the other modes (pool cooling, spray, shutdown "
               "cooling) are valves on the bench but have no mimic path of their own yet."]
    return b


# ======================================================================================================================
# Board 1 (research): rod control -- the rod select matrix, the full core display, rod motion, the RWM / RSCS section
# ======================================================================================================================

def rod_board():
    b = Board("rod_control", "Rod control (P603)",
              "Reactor manual control: the full core display and the rod select matrix side by side on a vertical section, the "
              "four rod motion buttons under the matrix, rod drift test and reset, the four rod display, and the RWM / RSCS "
              "section with its error lamps, selectors and a space for the rod pattern map.",
              "CR-PANELS-BWR-RESEARCH.md board 1 (GE BWR/4 reactor manual control system; NMP-2 RSCS panel)", 1,
              [frame("wall", BACK, 10, 5, (0, 5, -1)), frame("bench", BENCH, 10, 1, (0, 0, 0))])
    W, N = "wall.face", "bench.face"
    b.plate(W, 0, 0, 64, 4, "FULL CORE DISPLAY", style="black")
    b.plate(W, 64, 0, 48, 4, "ROD SELECT", style="black")
    b.plate(W, 112, 0, 48, 4, "RWM / RSCS", style="black")
    b.add(W, "full_core_display", 0, 6, (64, 64), label="FCD",
          config={"FcdStyle": "columbia", "TileCells": 2, "CoordLegends": True})
    b.plate(W, 0, 72, 64, 4, "SELECTED ROD LIT   DRIFT / SCRAM")
    b.add(W, "rod_select_matrix", 64, 6, (48, 48), label="ROD-SELECT", config={"TileCells": 2, "CoordLegends": True})
    b.plate(W, 64, 56, 48, 4, "FOUR ROD DISPLAY  (POSITION)")
    for i, (tag, cap) in enumerate((("ROD-N", "N"), ("ROD-W", "W"), ("ROD-E", "E"), ("ROD-S", "S"))):
        seg(b, W, 66 + 12 * i, 61, 2, "{0:02.0f}", label=tag, caption=cap)
    b.lamp(W, 70, 73, AMBER, caption="ACT CTL DISAGREE", label="ACT-DISAGREE")
    b.lamp(W, 100, 73, RED, caption="ROD DRIFT", label="ROD-DRIFT")
    b.rect(W, 63, 5, 49, 74)
    # RWM / RSCS
    b.add(W, "annunciator_control", 134, 7, ANN_CTRL, label="ANN-CTRL")
    annunciator_box(b, W, 114, 6, ANN, [
        [("ROD DRIFT", ""), ("RWM", "INSERT ERR"), ("RWM", "WITHDR ERR"), ("RWM", "SELECT ERR"), ("RWM", "NOTCH ERR"),
         ("RSCS", "ABOVE LPSP"), ("RWM", "INOP"), ("RWM", "BYPASSED")],
        [("ROD BLOCK", ""), ("RBM", "ROD BLOCK"), ("APRM", "ROD BLOCK"), ("IRM", "ROD BLOCK"), ("SRM", "ROD BLOCK"),
         ("ROD SEL", "DISAGREE"), ("RSCS", "INOP"), ("RSCS", "BYPASSED")]], [AMBER, AMBER])
    for i, (cap, tag) in enumerate((("INSERT ERR", "RWM-INS-ERR"), ("WITHDR ERR", "RWM-WD-ERR"), ("SELECT ERR", "RWM-SEL-ERR"),
                                    ("NOTCH ERR", "RWM-NTC-ERR"), ("ABOVE LPSP", "RSCS-LPSP"), ("INTERLOCKS", "RWM-INTLK"))):
        b.lamp(W, 120 + 16 * (i % 3), 16 + 8 * (i // 3), RED if i < 4 else AMBER, caption=cap, label=tag)
    b.plate(W, 114, 32, 24, 4, "ROD GROUP / SEQUENCE")
    seg(b, W, 117, 37, 4, "{0:4.0f}", label="RWM-GROUP", caption="GROUP")
    b.plate(W, 140, 32, 16, 4, "RWM NORM/BYP")
    b.add(W, "key_switch", 147, 37, KEY, label="RWM-BYPASS", caption="KEY")
    b.plate(W, 114, 50, 32, 4, "RSCS ROD PATTERN")
    b.reserve(W, 114, 55, 44, 22, "bwr:led_rod_pattern_display",
              "RSCS / RWM rod pattern map with an amber and a red LED per rod (ALL RODS / FREE RODS, RODS F.I. / BYPASS): "
              "an LED rod pattern display part does not exist yet (CR-PANELS-BWR-RESEARCH.md section 4, gap 5). Place it here.")
    b.rect(W, 113, 5, 46, 72)

    # -- the bench
    b.plate(N, 0, 0, 16, 4, "FCD TEST")
    push(b, N, 7, 6, caption="LAMP TEST", label="FCD-LAMP-TEST")
    b.plate(N, 20, 0, 24, 4, "ROD DRIFT", style="blue")
    push(b, N, 25, 6, caption="TEST", label="DRIFT-TEST")
    push(b, N, 35, 6, caption="RESET", label="DRIFT-RESET")
    b.plate(N, 64, 0, 48, 4, "ROD MOTION", style="black")
    push(b, N, 70, 6, caption="INSERT", label="ROD-INSERT", colour="D08020", variant="extended")
    push(b, N, 82, 6, caption="WITHDR", label="ROD-WITHDRAW", colour="E8E8E8", variant="extended")
    push(b, N, 94, 6, caption="C INS", label="ROD-CONT-INS", colour="D08020")
    push(b, N, 106, 6, caption="C WD", label="ROD-CONT-WD", colour="E8E8E8")
    for i, (plate, names, tag) in enumerate((("SEQ A / B", ["SEQ A", "SEQ B"], "RWM-SEQ"),
                                             ("INSERT / WITHDRAW", ["INSERT", "WITHDR"], "RWM-DIR"),
                                             ("ALL / FREE RODS", ["ALL", "FREE"], "RWM-ALL"))):
        b.plate(N, 112 + 16 * i, 0, 16, 4, plate)
        rotary(b, N, 119 + 16 * i, 5, ROT, names, label=tag)
    b.plate(N, 44, 0, 16, 4, "RODS F.I. / BYP")
    rotary(b, N, 51, 5, ROT, ["F.I.", "BYPASS"], label="RWM-FI")
    b.todo += ["The RSCS / RWM rod pattern (an amber and a red LED per rod) is reserved on wall.face for the LED rod pattern "
               "display part; until then the full core display shows the pattern.",
               "The fault map (LED core map of a faulty transponder) of research board 1 has no part and no space yet."]
    return b


BOARDS = [rod_board, rpv_board, rps_board, eccs_board, neutron_board]

if __name__ == "__main__":
    check = "--check" in sys.argv
    boards = [build() for build in BOARDS]
    if ERRORS:
        raise SystemExit(chr(10).join(sorted(set(ERRORS))))
    for board in boards:
        board.write(check)
