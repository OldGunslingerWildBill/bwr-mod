#!/usr/bin/env python3
"""Write the JSON side of the control-room furniture: model files for every OBJ, item models, blockstates, loot tables.

The geometry comes from art/models/room_furniture/build_furniture.py (Blender), which writes the OBJ files this script
wraps. Standard library only. Re-run after changing the block list, the property names or the models; recipes and lang
keys are written by hand.
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")
MODELS = os.path.join(ASSETS, "models/block/furniture")

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def obj_model(name):
    return {
        "loader": "neoforge:obj",
        "model": "bwr:models/block/furniture/%s.obj" % name,
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": True,
        "emissive_ambient": False,
        "ambientocclusion": False,
        "textures": {"particle": "minecraft:block/white_concrete"},
        "render_type": "minecraft:solid",
    }


def ref(name, y=0):
    out = {"model": "bwr:block/furniture/" + name}
    if y:
        out["y"] = y
    return out


def item_model(parent, scale, translation=(0, 0, 0)):
    def tf(k):
        return [round(scale * k, 3)] * 3
    return {
        "parent": "bwr:block/furniture/" + parent,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": list(translation), "scale": tf(1.0)},
            "ground": {"scale": tf(0.67)},
            "fixed": {"scale": tf(0.83)},
        },
    }


def loot(block, conditions=None):
    pool_conditions = []
    if conditions:
        pool_conditions.append({"condition": "minecraft:block_state_property", "block": "bwr:" + block,
                                "properties": conditions})
    pool_conditions.append({"condition": "minecraft:survives_explosion"})
    # The item keeps the block's paint finish (PanelFinish.java) as its minecraft:block_state component, for every finish but
    # seafoam: a seafoam drop has no component, so it still stacks with a crafted item.
    children = [{"type": "minecraft:item", "name": "bwr:" + block,
                 "conditions": [{"condition": "minecraft:block_state_property", "block": "bwr:" + block,
                                 "properties": {"finish": finish}}],
                 "functions": [{"function": "minecraft:set_components",
                                "components": {"minecraft:block_state": {"finish": finish}}}]}
                for finish in ("cream", "tan", "grey", "light_blue")]
    children.append({"type": "minecraft:item", "name": "bwr:" + block})
    return {"type": "minecraft:block", "pools": [{
        "rolls": 1.0, "bonus_rolls": 0.0,
        "entries": [{"type": "minecraft:alternatives", "children": children}],
        "conditions": pool_conditions}]}


def variants_by_facing(model):
    return {"variants": {"facing=" + f: ref(model, y) for f, y in FACINGS}}


def multipart(parts):
    """parts: [(when dict, model name)] expanded over the four facings."""
    out = []
    for when, model in parts:
        for f, y in FACINGS:
            w = dict(when)
            w["facing"] = f
            out.append({"when": w, "apply": ref(model, y)})
    return {"multipart": out}


def main():
    # model files for every OBJ
    for fn in sorted(os.listdir(MODELS)):
        if fn.endswith(".obj"):
            write(os.path.join(MODELS, fn[:-4] + ".json"), obj_model(fn[:-4]))

    bs = os.path.join(ASSETS, "blockstates")
    im = os.path.join(ASSETS, "models/item")
    lt = os.path.join(DATA, "loot_table/blocks")

    # relay cabinets: lower and upper half
    for block, prefix in (("relay_cabinet", "cabinet"), ("relay_cabinet_plain", "cabinet_plain")):
        variants = {}
        for f, y in FACINGS:
            for half in ("lower", "upper"):
                variants["facing=%s,half=%s" % (f, half)] = ref("%s_%s" % (prefix, half), y)
        write(os.path.join(bs, block + ".json"), {"variants": variants})
        write(os.path.join(im, block + ".json"), item_model(prefix + "_item", 0.42))
        write(os.path.join(lt, block + ".json"), loot(block, {"half": "lower"}))

    # desks: a base and a monitor part each
    mon = ("flat", "crt")
    write(os.path.join(bs, "operator_desk.json"), multipart(
        [({}, "desk")] + [({"monitor": m}, "desk_" + m) for m in mon]))
    write(os.path.join(bs, "operator_desk_round.json"), multipart(
        [({"mirrored": "false"}, "desk_round"), ({"mirrored": "true"}, "desk_round_mirrored")]
        + [({"monitor": m, "mirrored": "false"}, "desk_round_" + m) for m in mon]
        + [({"monitor": m, "mirrored": "true"}, "desk_round_%s_mirrored" % m) for m in mon]))
    write(os.path.join(bs, "operator_desk_corner.json"), multipart(
        [({}, "desk_corner")] + [({"monitor": m}, "desk_corner_" + m) for m in mon]))
    write(os.path.join(bs, "supervisor_desk.json"), multipart(
        [({"part": "drawers"}, "supervisor_drawers"), ({"part": "knee"}, "supervisor_knee")]
        + [({"monitor": m}, "desk_" + m) for m in mon]))
    write(os.path.join(im, "operator_desk.json"), item_model("desk", 0.6))
    write(os.path.join(im, "operator_desk_round.json"), item_model("desk_round", 0.6))
    write(os.path.join(im, "operator_desk_corner.json"), item_model("desk_corner", 0.6))
    write(os.path.join(im, "supervisor_desk.json"), item_model("supervisor_item", 0.36))
    for block in ("operator_desk", "operator_desk_round", "operator_desk_corner"):
        write(os.path.join(lt, block + ".json"), loot(block))
    write(os.path.join(lt, "supervisor_desk.json"), loot("supervisor_desk", {"part": "drawers"}))

    # mimic bands
    for finish in ("cream", "black"):
        block = "mimic_band_" + finish
        write(os.path.join(bs, block + ".json"), variants_by_facing(block))
        write(os.path.join(im, block + ".json"), item_model(block, 0.6))
        write(os.path.join(lt, block + ".json"), loot(block))

    # control stand
    write(os.path.join(bs, "control_stand.json"), variants_by_facing("control_stand"))
    write(os.path.join(im, "control_stand.json"), item_model("control_stand", 0.6))
    write(os.path.join(lt, "control_stand.json"), loot("control_stand"))

    # wall displays: one model per block of the frame
    for block, width, scale in (("wall_display", 2, 0.33), ("wall_display_wide", 3, 0.24)):
        variants = {}
        for f, y in FACINGS:
            for col in range(3):
                for row in range(2):
                    used = min(col, width - 1)    # col 2 of the two wide frame never occurs; it shows the last column
                    variants["col=%d,facing=%s,row=%d" % (col, f, row)] = ref("wall_display_%dx2_%d%d" % (width, used, row), y)
        write(os.path.join(bs, block + ".json"), {"variants": variants})
        write(os.path.join(im, block + ".json"), item_model("wall_display_%dx2_item" % width, scale))
        write(os.path.join(lt, block + ".json"), loot(block, {"col": "0", "row": "0"}))
    print("wrote furniture models, blockstates, item models and loot tables")


if __name__ == "__main__":
    main()
