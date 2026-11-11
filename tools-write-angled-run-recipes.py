#!/usr/bin/env python3
"""Writes the grid recipes (vanilla and Mekanism twin) of the twelve modular 45 degree run pieces
(CONTROL-PANELS-SPEC section 14). Shapeless, like the fixed corner they replace: a straight desk (or a benchboard and vertical boards)
and iron; the inside and outside piece of a role differ by a nugget so the two recipes never match the same grid. With Mekanism the
usual doubling: an iron ingot is two steel ingots, a nugget one steel nugget. Standard library only; idempotent."""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
RECIPES = os.path.join(ROOT, "mod/src/main/resources/data/bwr/recipe")

DESK = "bwr:control_panel_desk"
BENCH = "bwr:control_panel_benchboard"
VERTICAL = "bwr:control_panel_vertical"
INGOT, NUGGET = "minecraft:iron_ingot", "minecraft:iron_nugget"

# (family, kind, role) -> ingredient list
DESIGN = {
    ("desk", "inside", "bend_a"): [DESK, NUGGET],
    ("desk", "outside", "bend_a"): [DESK, NUGGET, NUGGET],
    ("desk", "inside", "bend_b"): [DESK, INGOT],
    ("desk", "outside", "bend_b"): [DESK, INGOT, NUGGET],
    ("desk", "inside", "unit"): [DESK, DESK, NUGGET],
    ("desk", "outside", "unit"): [DESK, DESK, NUGGET, NUGGET],
    ("tall", "inside", "bend_a"): [BENCH, VERTICAL, NUGGET],
    ("tall", "outside", "bend_a"): [BENCH, VERTICAL, NUGGET, NUGGET],
    ("tall", "inside", "bend_b"): [BENCH, VERTICAL, INGOT],
    ("tall", "outside", "bend_b"): [BENCH, VERTICAL, INGOT, NUGGET],
    ("tall", "inside", "unit"): [BENCH, VERTICAL, VERTICAL, VERTICAL, NUGGET],
    ("tall", "outside", "unit"): [BENCH, VERTICAL, VERTICAL, VERTICAL, NUGGET, NUGGET],
}
MEKANISM = {INGOT: [{"tag": "c:ingots/steel"}, {"tag": "c:ingots/steel"}], NUGGET: [{"tag": "c:nuggets/steel"}]}


def recipe(name, ingredients, mekanism):
    cond = {"type": "neoforge:mod_loaded", "modid": "mekanism"}
    return {
        "type": "minecraft:crafting_shapeless",
        "neoforge:conditions": [cond if mekanism else {"type": "neoforge:not", "value": cond}],
        "category": "misc",
        "ingredients": ingredients,
        "result": {"count": 1, "id": "bwr:" + name},
    }


def write(path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


for (family, kind, role), items in DESIGN.items():
    name = "control_panel_%s_run_%s_%s" % (family, kind, role)
    write(os.path.join(RECIPES, name + ".json"), recipe(name, [{"item": i} for i in items], False))
    steel = []
    for i in items:
        steel += MEKANISM.get(i, [{"item": i}])
    write(os.path.join(RECIPES, name + "_mekanism.json"), recipe(name, steel, True))
    print("wrote", name)
