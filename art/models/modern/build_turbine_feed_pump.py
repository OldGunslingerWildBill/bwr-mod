"""Turbine-driven reactor feed pump (bwr:turbine_feed_pump, 9 x 3 x 3), rebuilt from plant references.

    blender --background --factory-startup --python art/models/modern/build_turbine_feed_pump.py [-- options]
    python3 art/models/modern/build_turbine_feed_pump.py [-- options]        (bpy installed from PyPI)

Options after "--":
    --preview DIR       also render the new model into DIR (four views, Cycles on the CPU; no GPU needed)
    --before SRC.json   render an older source JSON from the same views into the --preview DIR, then stop
    --no-save           do not rewrite turbine_feed_pump.blend
    --icon              only re-render the inventory icon from the exported inventory.obj (run tools-export-modern.py first)

Writes art/models/modern/turbine_feed_pump.json, which tools-export-modern.py clips into block cells.
What it shows and where each feature comes from is in TURBINE-FEED-PUMP-REMODEL.md.

Coordinates are Minecraft X/Y/Z for a north-facing machine; one unit is one block. West is the pump end.
Footprint, controller cell and the four ports (cell, face, flange centre) are checked against the shipped
manifest before anything is written. Original geometry; generic plates only.

Old saves: the one-block compact model (compact.obj / compact.json) is frozen. Its materials are listed first
(turbine_feed_pump_compact_materials.json) so materials.mtl keeps mat_0.. as compact.obj expects, and
'frozen_compact' makes the exporter write the held item as inventory.obj instead of regenerating compact.*.
"""
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
MODERN = ROOT / 'art/models/modern'
# Primitives, paints and the JSON writer are shared with the Terry skids (art/models/turbine_pumps/build_models.py).
_shared = ROOT / 'art/models/turbine_pumps/build_models.py'
_parts = _shared.read_text(encoding='utf8').split('# ================================================================ reactor feed pump turbine')
assert len(_parts) == 2, 'shared helper marker moved in ' + str(_shared)
exec(compile(_parts[0], str(_shared), 'exec'))

LEGACY = json.loads((MODERN / 'turbine_feed_pump_compact_materials.json').read_text(encoding='utf-8'))
OUT = MODERN / 'turbine_feed_pump.json'
PORTS = [('water_suction', [1, 2, 1], 'up'), ('water_discharge', [3, 2, 1], 'up'),
         ('steam_inlet', [6, 2, 1], 'up'), ('steam_exhaust', [7, 1, 2], 'south')]

CONC = paint('foundation concrete', (.50, .50, .48), 0, .9)
BELLOWS = paint('stainless expansion joint', (.66, .68, .70), .8, .3)
LPBLUE = paint('feed pump pedestal blue', (.24, .32, .40))


# ---------------------------------------------------------------- build
AY, AZ = 1.10, 1.50      # shaft centreline
PAD, TOP = .12, .30      # concrete pad top, bedplate top
PX = 2.50                # pump casing centre


def rounded_rect(axis_y, x0, x1, z0, z1, n=16):
    """n points round a rectangle in a horizontal plane, starting at +x and going the same way as circle()."""
    cx, cz, hx, hz = (x0 + x1) / 2, (z0 + z1) / 2, (x1 - x0) / 2, (z1 - z0) / 2
    out = []
    for i in range(n):
        t = i * math.tau / n
        c, q = math.cos(t), math.sin(t)
        k = 1 / max(abs(c), abs(q))
        out.append((cx + hx * c * k, axis_y, cz + hz * q * k))
    return out


def circle(c, r, n=16, plane='xz'):
    out = []
    for i in range(n):
        t = i * math.tau / n
        if plane == 'xz':
            out.append((c[0] + r * math.cos(t), c[1], c[2] + r * math.sin(t)))
        else:  # 'xy', facing z
            out.append((c[0] + r * math.cos(t), c[1] + r * math.sin(t), c[2]))
    return out


def rect_xy(z, x0, x1, y0, y1, n=16):
    cx, cy, hx, hy = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
    out = []
    for i in range(n):
        t = i * math.tau / n
        c, q = math.cos(t), math.sin(t)
        k = 1 / max(abs(c), abs(q))
        out.append((cx + hx * c * k, cy + hy * q * k, z))
    return out


def bedplate(s, name, x0, x1, z0, z1, mat, anchors):
    box(s, name, (x0, PAD, z0), (x1, TOP, z1), mat)
    box(s, name + ' drip rim', (x0 - .03, TOP - .05, z0 - .03), (x1 + .03, TOP - .02, z1 + .03), mat)
    for i in range(anchors):
        x = x0 + .14 + (x1 - x0 - .28) * i / (anchors - 1)
        for z in (z0 + .06, z1 - .06):
            hexbolt(s, (x, TOP - .005, z), (x, TOP + .05, z), .032)


def bearing(s, name, x0, x1, r, mat, cap=None):
    """Split journal bearing housing on the shaft line with its cap bolts; optional closed end cap at x = cap."""
    cylinder(s, name, (x0, AY, AZ), (x1, AY, AZ), r, mat, 18)
    box(s, name + ' split', (x0, AY - .015, AZ - r - .03), (x1, AY + .015, AZ + r + .03), BOLTM)
    for z in (AZ - r + .03, AZ + r - .03):
        hexbolt(s, ((x0 + x1) / 2, AY + .015, z), ((x0 + x1) / 2, AY + r * .62, z), .024)
    if cap is not None:
        cylinder(s, name + ' end cap', (min(cap, x0 if cap < x0 else x1), AY, AZ), (max(cap, x0 if cap < x0 else x1), AY, AZ), r * .62, STEEL, 12)


def vertical_motor(s, name, x, z, y0, y1, r, mat):
    cylinder(s, name + ' pump mount', (x, y0, z), (x, y0 + .06, z), r * 1.15, BOLTM, 12)
    cylinder(s, name, (x, y0 + .06, z), (x, y1 - .07, z), r, mat, 14)
    cylinder(s, name + ' fan cowl', (x, y1 - .07, z), (x, y1, z), r * 1.08, mat, 14)
    box(s, name + ' terminal box', (x + r * .7, y0 + .14, z - r * .5), (x + r * 1.5, y1 - .16, z + r * .5), mat)


def lagged(s, name, points, r, bands=()):
    """Small jacketed steam line with a few jacket bands (cheaper than the shared insulated())."""
    pipe(s, name, points, r, JACKET, 8)
    for c, axis in bands:
        c, d = V(c), V(axis).normalized()
        sleeve(s, name + ' band', c - d * .012, c + d * .012, r + .008, r - .01, STRAP, 8)


def small_valve(s, name, c, flow, r, mat):
    c, f = V(c), V(flow).normalized()
    ellipsoid(s, name + ' body', c, r * (1.25 if abs(f.x) > .5 else 1), r * (1.25 if abs(f.y) > .5 else 1), r * (1.25 if abs(f.z) > .5 else 1), mat, 10, 6)
    for sg in (-1, 1):
        q = c + f * sg * r * 1.25
        sleeve(s, name + ' end flange', q - f * .025, q + f * .025, r * 1.25, r * .5, mat, 10)


def build():
    s = new_scene('BWR | turbine feed pump')
    # ---- foundation: one concrete pad, separate bedplates for the pump and the turbine
    box(s, 'concrete foundation pad', (.05, 0, .10), (8.95, PAD, 2.90), CONC)
    bedplate(s, 'pump bedplate', 1.22, 4.44, .55, 2.45, BASEP, 4)
    bedplate(s, 'turbine bedplate', 4.52, 8.93, .42, 2.58, BASEP, 5)

    # ================= feed pump: horizontal, single-stage, double-suction, radially split, centreline mounted
    R = .68
    cylinder(s, 'pump volute casing', (PX - .27, AY, AZ), (PX + .27, AY, AZ), R, FPUMP, 28)
    for sg in (-1, 1):
        frustum(s, 'suction chamber', (PX + sg * .27, AY, AZ), (PX + sg * .56, AY, AZ), .60, .47, FPUMP, 28)
        x = PX + sg * .56
        cylinder(s, 'casing end cover', (x, AY, AZ), (x + sg * .07, AY, AZ), .51, LPBLUE, 28)
        bolt_circle(s, (x + sg * .07, AY, AZ), (sg, 0, 0), .44, 10, .05, .032)
        cylinder(s, 'shaft seal housing', (x + sg * .07, AY, AZ), (x + sg * .27, AY, AZ), .21, FPUMP, 16)
        cylinder(s, 'seal gland plate', (x + sg * .27, AY, AZ), (x + sg * .30, AY, AZ), .17, STEEL, 12)
        for z in (AZ - .26, AZ + .26):
            obox(s, 'bearing bracket arm', (x + sg * .07, AY, z), (x + sg * .44, AY, z), .05, .16, (0, 1, 0), LPBLUE)
        centreline_feet(s, PX + sg * .42, AY, AZ, .55, TOP, LPBLUE, .26)
    cylinder(s, 'pump shaft', (1.40, AY, AZ), (3.74, AY, AZ), .07, SHAFT, 10)
    bearing(s, 'pump thrust bearing housing', 1.30, 1.66, .25, FPUMP, cap=1.24)
    bearing(s, 'pump inboard bearing housing', 3.34, 3.62, .23, FPUMP)
    # Twin top nozzles side by side across the shaft: suction (south) splits to both sides of the impeller, discharge (north).
    ZS, ZD, NY = AZ + .40, AZ - .40, 1.92
    loft(s, 'suction nozzle and branch', rounded_rect(AY + .30, PX - .55, PX + .55, AZ + .16, AZ + .60), circle((PX, NY, ZS), .27), FPUMP)
    frustum(s, 'discharge nozzle', (PX, AY + .42, ZD + .02), (PX, NY, ZD), .30, .24, FPUMP, 16)
    for z, role, px in ((ZS, 'water_suction', 1.5), (ZD, 'water_discharge', 3.5)):
        flange_ring(s, 'pump nozzle flange', (PX, NY + .03, z), (0, 1, 0), .345, .2, .06, FPUMP, 8, .30)
        sleeve(s, 'spool flange', (PX, NY + .06, z), (PX, NY + .12, z), .345, .2, WPIPE, 14)
        pipe(s, role + ' spool', [(PX, NY + .12, z), (PX, NY + .26, z), (px, 2.50, AZ), (px, 2.66, AZ)], .235, WPIPE, 12)
        modern_port(s, role, [int(px), 2, 1], 'up', (px, 2.64, AZ))
    gauge(s, (1.5, 2.56, AZ + .34), (0, 0, 1), .07, stem=(1.5, 2.56, AZ + .2))
    gauge(s, (3.5, 2.56, AZ - .34), (0, 0, -1), .07, stem=(3.5, 2.56, AZ - .2))
    cylinder(s, 'casing vent', (PX, AY + R - .02, AZ), (PX, AY + R + .12, AZ), .025, STEEL, 8)
    cylinder(s, 'casing vent valve', (PX, AY + R + .12, AZ), (PX, AY + R + .17, AZ), .045, HAND, 8)
    plate(s, 'feed pump nameplate', (PX, AY - .05, AZ - R - .016), (0, 0, -1), (0, 1, 0), .30, .15, PLATE['feedpump'])
    # Minimum-flow recirculation: a small branch off the discharge spool with its valve, down through the floor.
    pipe(s, 'minimum flow line', [(3.24, 2.34, AZ - .18), (3.24, 2.34, .78), (3.24, 1.30, .78)], .06, WPIPE, 10)
    small_valve(s, 'minimum flow valve', (3.24, 1.20, .78), (0, 1, 0), .10, VALVE)
    cylinder(s, 'minimum flow valve operator', (3.24, 1.22, .68), (3.24, 1.22, .50), .075, ACT, 12)
    pipe(s, 'minimum flow line to condenser', [(3.24, 1.10, .78), (3.24, PAD, .78)], .06, WPIPE, 10)
    # Seal water injection to both shaft seals.
    for x in (PX - .72, PX + .72):
        pipe(s, 'seal injection tubing', [(x, AY + .20, AZ - .05), (x, AY + .34, AZ - .05), (x, AY + .34, .70), (x, TOP, .70)], .016, STEEL, 4)

    # ================= coupling
    coupling(s, 3.70, 4.52, AY, AZ, .30, TOP)

    # ================= turbine: multistage condensing, steam flows west to east, exhaust hood at the east end
    # front standard (thrust bearing, overspeed trip, speed pickup) between the coupling and the inlet end
    box(s, 'front standard', (4.56, TOP - .02, AZ - .40), (5.00, AY + .04, AZ + .40), TURBD)
    prism(s, 'front standard cover', 'x', 4.56, 5.00, arc(AZ, AY + .04, .40, 0, 180, 12), TURBD)
    box(s, 'front standard joint', (4.54, AY + .02, AZ - .43), (5.02, AY + .07, AZ + .43), TURBD)
    cylinder(s, 'hand trip knob', (4.70, AY - .22, AZ - .40), (4.70, AY - .22, AZ - .455), .045, HAND, 10)
    cylinder(s, 'speed pickup', (4.68, AY + .40, AZ - .16), (4.68, AY + .50, AZ - .16), .03, ACT, 8)
    gauge(s, (4.88, AY - .22, AZ - .405), (0, 0, -1), .055)
    # inlet (high-pressure) end, horizontally split, then the flare to the last stages
    frustum(s, 'inlet end head', (5.00, AY, AZ), (5.26, AY, AZ), .33, .58, TURB, 24)
    cylinder(s, 'inlet casing', (5.26, AY, AZ), (6.46, AY, AZ), .58, TURB, 28)
    frustum(s, 'casing flare', (6.46, AY, AZ), (6.86, AY, AZ), .58, .86, TURB, 28)
    box(s, 'casing horizontal joint flange', (5.22, AY - .04, AZ - .70), (6.50, AY + .04, AZ + .70), TURBD)
    for z in (AZ - .66, AZ + .66):
        joint_studs(s, 5.30, 6.42, AY + .04, z, .28)
    for x in (5.40, 6.30):
        for z in (AZ - .60, AZ + .60):
            box(s, 'casing support foot', (x - .10, TOP, min(z, z + (.12 if z > AZ else -.12))), (x + .10, AY - .04, max(z, z + (.12 if z > AZ else -.12))), TURBD)
    # exhaust hood
    HW = .86
    box(s, 'exhaust hood lower casing', (6.86, TOP - .02, AZ - HW), (8.26, AY, AZ + HW), TURB)
    prism(s, 'exhaust hood upper casing', 'x', 6.86, 8.26, arc(AZ, AY, .88, 0, 180, 24), TURB)
    for x0, x1 in ((6.83, 6.89), (8.23, 8.29)):
        prism(s, 'hood end rim', 'x', x0, x1, arc(AZ, AY, .91, 0, 180, 24), TURBD)
    box(s, 'hood horizontal joint flange', (6.82, AY - .04, AZ - .98), (8.30, AY + .04, AZ + .98), TURBD)
    for z in (AZ - .94, AZ + .94):
        joint_studs(s, 6.92, 8.20, AY + .04, z, .26)
    for x in (7.10, 7.56, 8.02):
        box(s, 'hood stiffener rib', (x - .03, TOP, AZ - HW - .05), (x + .03, AY - .05, AZ - HW), TURBD)
    for x in (7.0, 8.1):
        lifting_lug(s, (x, AY + .88, AZ), 'x')
    plate(s, 'turbine nameplate', (7.33, .74, AZ - HW - .003), (0, 0, -1), (0, 1, 0), .36, .18, PLATE['rfpt'])
    # atmospheric relief diaphragm on the hood
    cylinder(s, 'relief diaphragm stub', (7.72, AY + .80, AZ), (7.72, AY + 1.0, AZ), .20, TURB, 16)
    flange_ring(s, 'relief diaphragm holder', (7.72, AY + 1.02, AZ), (0, 1, 0), .25, .0, .04, TURBD, 8, .215)
    # exhaust end bearing and the motor-driven turning gear on the free end
    cylinder(s, 'exhaust end gland housing', (8.26, AY, AZ), (8.34, AY, AZ), .20, STEEL, 16)
    box(s, 'exhaust end bearing pedestal', (8.34, TOP - .02, AZ - .36), (8.64, AY - .18, AZ + .36), TURBD)
    bearing(s, 'exhaust end bearing housing', 8.34, 8.64, .27, TURBD)
    box(s, 'turning gear case', (8.64, AY - .30, AZ - .30), (8.90, AY + .30, AZ + .30), TURBD)
    box(s, 'turning gear case base', (8.64, TOP - .02, AZ - .22), (8.90, AY - .30, AZ + .22), TURBD)
    vertical_motor(s, 'turning gear motor', 8.77, AZ - .12, AY + .30, AY + .86, .105, VALVE)
    cylinder(s, 'turning gear engagement lever', (8.82, AY + .30, AZ + .2), (8.82, AY + .56, AZ + .2), .018, HAND, 6)
    cylinder(s, 'lever knob', (8.82, AY + .56, AZ + .2), (8.82, AY + .62, AZ + .2), .035, HAND, 8)

    # ================= steam admission (one game steam port feeds both trains)
    # low-pressure train: stop valve under the inlet flange, control valve chest on the casing, bar lift gear
    CY = 2.02
    modern_port(s, 'steam_inlet', [6, 2, 1], 'up', (6.5, CY + .33, AZ))
    flange_ring(s, 'stop valve inlet flange', (6.5, CY + .30, AZ), (0, 1, 0), .33, .2, .06, VALVE, 8, .285)
    ellipsoid(s, 'low pressure stop valve body', (6.5, CY, AZ), .30, .28, .28, VALVE, 16, 8)
    cylinder(s, 'stop valve actuator yoke', (6.5, CY, AZ - .24), (6.5, CY, AZ - .44), .09, VALVE, 12)
    cylinder(s, 'stop valve hydraulic actuator', (6.5, CY, AZ - .44), (6.5, CY, AZ - .74), .12, HYD, 16)
    cylinder(s, 'stop valve spring can', (6.5, CY, AZ - .74), (6.5, CY, AZ - 1.06), .15, ACT, 16)
    box(s, 'stop valve limit switch box', (6.60, CY + .06, AZ - .62), (6.72, CY + .18, AZ - .46), ACT)
    cylinder(s, 'control valve steam chest', (5.26, CY, AZ), (6.24, CY, AZ), .21, TURBD, 20)
    flange_ring(s, 'steam chest end cover', (5.26, CY, AZ), (1, 0, 0), .25, .0, .04, TURBD, 8, .2)
    flange_ring(s, 'steam chest inlet flange', (6.22, CY, AZ), (1, 0, 0), .27, .1, .05, VALVE, 8, .23)
    for x in (5.42, 5.64, 5.86, 6.08):
        cylinder(s, 'steam lead to nozzle box', (x, AY + .55, AZ), (x, CY - .15, AZ), .085, JACKET, 12)
        cylinder(s, 'control valve stem', (x, CY + .2, AZ), (x, 2.62, AZ), .02, SHAFT, 6)
    for x in (5.31, 6.19):
        cylinder(s, 'bar lift post', (x, CY + .18, AZ), (x, 2.66, AZ), .028, SHAFT, 6)
    box(s, 'bar lift crossbar', (5.26, 2.60, AZ - .05), (6.24, 2.67, AZ + .05), STEEL)
    # servomotor on the front standard, lever to the lift bar
    cylinder(s, 'control valve servomotor', (4.78, AY + .44, AZ), (4.78, 2.16, AZ), .10, HYD, 14)
    cylinder(s, 'servomotor head', (4.78, 2.16, AZ), (4.78, 2.22, AZ), .12, ACT, 14)
    cylinder(s, 'servomotor rod', (4.78, 2.22, AZ), (4.78, 2.50, AZ), .024, SHAFT, 6)
    obox(s, 'valve gear lever', (4.74, 2.50, AZ), (5.32, 2.635, AZ), .04, .05, (0, 1, 0), STEEL)
    box(s, 'lever fulcrum bracket', (5.02, AY + .50, AZ - .03), (5.08, 2.56, AZ + .03), TURBD)
    # high-pressure train: branch off the inlet spool, small stop valve, single control valve, into its own nozzle arc
    HZ = 2.40
    lagged(s, 'high pressure steam branch', [(6.5, 2.46, AZ + .2), (6.5, 2.46, HZ), (6.5, 2.21, HZ)], .075, [((6.5, 2.46, AZ + .55), (0, 0, 1))])
    small_valve(s, 'high pressure stop valve', (6.5, 2.06, HZ), (0, 1, 0), .12, VALVE)
    cylinder(s, 'high pressure stop valve actuator', (6.5, 2.06, HZ + .10), (6.5, 2.06, HZ + .34), .085, HYD, 12)
    cylinder(s, 'high pressure stop valve spring can', (6.5, 2.06, HZ + .34), (6.5, 2.06, HZ + .50), .10, ACT, 12)
    lagged(s, 'high pressure steam lead', [(6.5, 1.91, HZ), (6.5, 1.50, HZ), (6.14, 1.50, HZ)], .075, [((6.5, 1.74, HZ), (0, 1, 0))])
    ellipsoid(s, 'high pressure control valve body', (5.96, 1.50, HZ), .15, .13, .13, VALVE, 12, 6)
    cylinder(s, 'high pressure control valve bonnet', (5.96, 1.60, HZ), (5.96, 1.76, HZ), .07, VALVE, 10)
    cylinder(s, 'high pressure control valve servomotor', (5.96, 1.76, HZ), (5.96, 2.08, HZ), .085, HYD, 12)
    cylinder(s, 'servomotor cap', (5.96, 2.08, HZ), (5.96, 2.13, HZ), .10, ACT, 12)
    lagged(s, 'high pressure nozzle lead', [(5.80, 1.50, HZ), (5.62, 1.50, HZ), (5.62, 1.50, AZ + .50)], .075)
    sleeve(s, 'high pressure nozzle boss', (5.62, 1.50, AZ + .40), (5.62, 1.50, AZ + .54), .12, .07, TURB, 12)

    # ================= exhaust to the condenser: rectangular hood outlet, transition, expansion joint, flange
    box(s, 'exhaust trunk', (7.04, 1.04, AZ + .70), (7.96, 1.96, 2.46), TURB)
    box(s, 'exhaust trunk flange', (7.00, 1.00, 2.42), (8.00, 2.00, 2.47), TURBD)
    loft(s, 'exhaust transition', rect_xy(2.47, 7.06, 7.94, 1.06, 1.94), circle((7.5, 1.5, 2.60), .31, 16, 'xy'), TURB)
    for z in (2.615, 2.665):
        sleeve(s, 'expansion joint convolution', (7.5, 1.5, z - .018), (7.5, 1.5, z + .018), .355, .25, BELLOWS, 12)
    modern_port(s, 'steam_exhaust', [7, 1, 2], 'south', (7.5, 1.5, 2.60))

    # ================= lube oil console at the pump end: reservoir, two AC pumps and a DC emergency pump, filters, cooler
    box(s, 'lube oil reservoir', (.16, PAD, .58), (1.04, .80, 2.42), TURBD)
    box(s, 'reservoir cover', (.14, .80, .56), (1.06, .83, 2.44), BASEP)
    vertical_motor(s, 'main oil pump motor', .40, .86, .83, 1.36, .115, TURBD)
    vertical_motor(s, 'auxiliary oil pump motor', .40, 1.26, .83, 1.36, .115, TURBD)
    vertical_motor(s, 'emergency oil pump motor', .40, 1.66, .83, 1.26, .095, ACT)
    for z in (2.06, 2.24):
        cylinder(s, 'duplex oil filter', (.40, .83, z), (.40, 1.18, z), .07, STEEL, 12)
        cylinder(s, 'filter cap', (.40, 1.18, z), (.40, 1.22, z), .08, BOLTM, 12)
    obox(s, 'filter transfer handle', (.40, 1.26, 2.06), (.40, 1.26, 2.24), .02, .02, (0, 1, 0), HAND)
    cylinder(s, 'lube oil cooler shell', (.80, .98, .78), (.80, .98, 2.22), .115, STEEL, 14)
    for z in (.78, 2.22):
        flange_ring(s, 'cooler bonnet', (.80, .98, z), (0, 0, 1), .145, .0, .04, STEEL, 6, .125)
    for z in (1.05, 1.95):
        box(s, 'cooler saddle', (.70, .83, z - .04), (.90, .90, z + .04), BASEP)
    gauge(s, (.60, .56, .575), (0, 0, -1), .06)
    box(s, 'reservoir level glass', (.86, .34, .565), (.90, .70, .58), SHAFT)
    cylinder(s, 'reservoir vent', (.80, .83, 2.34), (.80, 1.0, 2.34), .04, BOLTM, 8)
    # supply header along the north side with risers to the four bearings
    pipe(s, 'oil supply header', [(.60, .83, .70), (.60, .95, .70), (.60, .95, .40), (.60, .42, .40), (8.49, .42, .40)], .028, OIL, 6)
    for x in (1.48, 3.48, 4.78, 8.49):
        pipe(s, 'bearing oil riser', [(x, .42, .40), (x, AY - .10, .40), (x, AY - .10, AZ - .2)], .022, OIL, 4)
    # local gauge board beside the coupling
    box(s, 'gauge board post', (4.07, PAD, .30), (4.13, 1.22, .36), TURBD)
    box(s, 'gauge board', (3.74, 1.20, .28), (4.46, 1.60, .36), TURBD)
    for x in (3.88, 4.10, 4.32):
        gauge(s, (x, 1.42, .276), (0, 0, -1), .075)
        pipe(s, 'gauge impulse tubing', [(x, 1.20, .32), (x, PAD, .32)], .008, STEEL, 4)
    return s


def write(s):
    meta = {'id': 'turbine_feed_pump', 'size': [9, 3, 3], 'controller': [4, 0, 1], 'scale': 1.0,
            'ports': [{'role': r.upper(), 'cell': c, 'face': f} for r, c, f in PORTS], 'frozen_compact': True}
    check_wired('turbine_feed_pump_modern', PORTS, meta['size'], meta['controller'])
    export(s, OUT, meta)
    data = json.loads(OUT.read_text(encoding='utf-8'))
    for key, info in LEGACY.items():
        assert key not in data['materials'] or data['materials'][key] == info, ('compact material changed', key)
    data['materials'] = dict(LEGACY, **{k: v for k, v in data['materials'].items() if k not in LEGACY})
    OUT.write_text(json.dumps(data, separators=(',', ':')), encoding='utf-8')
    parts = {}
    for f in data['faces']:
        parts[f['part']] = parts.get(f['part'], 0) + 1
    return len(data['faces']), parts


# ---------------------------------------------------------------- previews (from the exported JSON, so before/after match)
def lin_(c):
    return c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4


def render_json(source, out_dir, tag):
    data = json.loads(Path(source).read_text(encoding='utf-8'))
    scene = bpy.data.scenes.new('Preview ' + tag)
    keys = list(data['materials'])
    mats = []
    for key in keys:
        info = data['materials'][key]
        m = bpy.data.materials.new('preview ' + tag + ' ' + key)
        m.use_nodes = True
        bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
        bsdf.inputs['Roughness'].default_value = .65
        tex = info['texture']
        if tex.startswith('bwr:block/modern/'):
            node = m.node_tree.nodes.new('ShaderNodeTexImage')
            node.image = bpy.data.images.load(str(MODERN / 'textures' / (tex.split('/')[-1] + '.png')), check_existing=True)
            node.interpolation = 'Closest'
            m.node_tree.links.new(node.outputs['Color'], bsdf.inputs['Base Color'])
        else:
            bsdf.inputs['Base Color'].default_value = (*[lin_(c) for c in info['color']], 1)
        mats.append(m)
    verts, faces, uvs, index = [], [], [], []
    for f in data['faces']:
        base = len(verts)
        for p in f['vertices']:
            verts.append((p[0], -p[2], p[1]))
            uvs.append((p[3], p[4]))
        faces.append((base, base + 1, base + 2))
        index.append(keys.index(f['material']))
    mesh = bpy.data.meshes.new('preview ' + tag)
    mesh.from_pydata(verts, [], faces)
    layer = mesh.uv_layers.new(name='UV')
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            layer.data[li].uv = uvs[mesh.loops[li].vertex_index]
    for m in mats:
        mesh.materials.append(m)
    mesh.polygons.foreach_set('material_index', index)
    mesh.update()
    scene.collection.objects.link(bpy.data.objects.new(mesh.name, mesh))
    w, h, d = data['size']
    floor = bpy.data.meshes.new('floor ' + tag)
    floor.from_pydata([(-8, 8, 0), (w + 8, 8, 0), (w + 8, -d - 8, 0), (-8, -d - 8, 0)], [], [(0, 1, 2, 3)])
    fm = bpy.data.materials.new('floor ' + tag)
    fm.use_nodes = True
    next(n for n in fm.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled').inputs['Base Color'].default_value = (.30, .31, .31, 1)
    floor.materials.append(fm)
    scene.collection.objects.link(bpy.data.objects.new(floor.name, floor))
    sun = bpy.data.lights.new('sun ' + tag, 'SUN')
    sun.energy = 3.0
    sun.angle = math.radians(8)
    so = bpy.data.objects.new(sun.name, sun)
    so.rotation_euler = (math.radians(42), math.radians(8), math.radians(30))
    scene.collection.objects.link(so)
    world = bpy.data.worlds.new('world ' + tag)
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.60, .66, .72, 1)
    bg.inputs['Strength'].default_value = 1.0
    scene.world = world
    cam = bpy.data.cameras.new('cam ' + tag)
    cam.lens = 50
    co = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(co)
    scene.camera = co
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.samples = 24
    scene.cycles.use_denoising = False
    scene.cycles.max_bounces = 3
    scene.render.resolution_x, scene.render.resolution_y = 1600, 900
    scene.render.image_settings.file_format = 'PNG'
    scene.view_settings.view_transform = 'Standard'
    centre = Vector((w / 2, -d / 2, h / 2.6))
    span = max(w, h, d)
    # name: (direction from the machine in Minecraft x, z, height; distance factor). North is -z.
    views = {'nw': ((-.75, -1.0, .62), 1.75), 'se': ((.85, 1.0, .5), 1.75), 'n': ((.1, -1.0, .22), 1.6),
             's': ((-.1, 1.0, .3), 1.6), 'far': ((.6, -1.0, .45), 3.6)}
    for name, (direction, dist) in views.items():
        dir_b = Vector((direction[0], -direction[1], direction[2])).normalized()
        co.location = centre + dir_b * span * dist
        co.rotation_euler = (centre - co.location).to_track_quat('-Z', 'Y').to_euler()
        scene.render.filepath = str(Path(out_dir) / f'turbine_feed_pump-{tag}-{name}.png')
        bpy.ops.render.render(write_still=True, scene=scene.name)
        print('PREVIEW', scene.render.filepath, flush=True)
    bpy.data.scenes.remove(scene)


def render_icon():
    """Inventory icon through art/models/inventory/render_icons.py, forced onto Cycles/CPU so it also runs without a GPU."""
    import importlib.util
    spec = importlib.util.spec_from_file_location('bwr_icons', ROOT / 'art/models/inventory/render_icons.py')
    icons = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(icons)

    class Wrap:
        def __init__(self, target, **over):
            self._t, self._o = target, over

        def __getattr__(self, k):
            return self._o[k] if k in self._o else getattr(self._t, k)

    def render(**kw):
        scene = bpy.data.scenes[kw['scene']]
        scene.render.engine = 'CYCLES'
        scene.cycles.device = 'CPU'
        scene.cycles.samples = 64
        return bpy.ops.render.render(**kw)

    icons.bpy = Wrap(bpy, ops=Wrap(bpy.ops, render=Wrap(bpy.ops.render, render=render)))
    icons.render('turbine_feed_pump', icons.ASSETS / 'models/block/pumps/turbine_feed_pump_modern/inventory.obj')


def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    if '--icon' in argv:
        render_icon()
        return
    out = argv[argv.index('--preview') + 1] if '--preview' in argv else None
    if out:
        Path(out).mkdir(parents=True, exist_ok=True)
    if '--before' in argv:
        render_json(argv[argv.index('--before') + 1], out, 'before')
        return
    s = build()
    count, parts = write(s)
    if '--parts' in argv:
        for name, n in sorted(parts.items(), key=lambda kv: -kv[1])[:40]:
            print(f'{n:6d}  {name}')
    if out:
        render_json(OUT, out, 'after')
    if '--no-save' not in argv:
        s['description'] = 'Reference-based model; one Blender unit is one Minecraft block. Ports unchanged.'
        bpy.context.preferences.filepaths.save_version = 0
        bpy.data.libraries.write(str(MODERN / 'turbine_feed_pump.blend'), {s}, fake_user=True, compress=True)
    print('TURBINE FEED PUMP BUILT', count, 'triangles')


if __name__ == '__main__':
    main()
