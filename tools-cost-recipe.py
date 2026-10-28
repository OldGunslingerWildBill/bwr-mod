#!/usr/bin/env python3
"""What an item costs in raw materials, through every bwr recipe it is made of, in one install: with Mekanism or without.

Run from anywhere:  python tools-cost-recipe.py [--vanilla] <item> [<item> ...]      one item's raw cost (default: with Mekanism)
                    python tools-cost-recipe.py [--vanilla] --plant                  a rough minimal plant (PLANT below)

The recipes are read as the game loads them: a recipe whose neoforge:conditions do not hold in the chosen install is ignored, and the
one that remains makes the item (crafting_shaped and crafting_shapeless only). A bwr item with a recipe is expanded; anything else
(vanilla items, tags, Mekanism items) is a raw material. Steel, bronze and iron are counted in ingots (a block is 9, a nugget 1/9), a
Mekanism steel casing as its own recipe (4 steel, 4 glass, 1 osmium). It is a price list for balancing (MEKANISM-RECIPES.md), not a test.
"""
import json
import os
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.abspath(__file__))
RECIPES = os.path.join(ROOT, "mod/src/main/resources/data/bwr/recipe")

EQUIV = {  # raw material -> (unit, ingots per piece)
    "minecraft:iron_ingot": ("iron", 1), "minecraft:iron_block": ("iron", 9), "minecraft:iron_nugget": ("iron", 1 / 9),
    "#c:ingots/iron": ("iron", 1),
    "#c:ingots/steel": ("steel", 1), "#c:storage_blocks/steel": ("steel", 9), "#c:nuggets/steel": ("steel", 1 / 9),
    "#c:ingots/bronze": ("bronze", 1), "#c:storage_blocks/bronze": ("bronze", 9),
    "minecraft:copper_ingot": ("copper", 1), "minecraft:copper_block": ("copper", 9),
}
CASING = {"steel": 4, "glass": 4, "osmium": 1}

# A rough minimal plant: item -> how many. The vessel shell is 7 x 7 x 10 around a 5 x 5 x 8 interior (290 blocks, the controller and
# the ports replace a few of them); the rest is a count of what a small working plant has once, and is an estimate, not a rule.
PLANT = {
    "reactor_vessel": 290, "reactor_controller": 1, "control_rod_drive": 24, "jet_pump": 10, "recirculation_pump": 2, "recirculation_outlet": 2,
    "recirculation_inlet": 2, "rpv_steam_outlet": 2, "rpv_water_injection_port": 1,
    "hp_turbine": 1, "lp_turbine": 1, "nuclear_generator": 1, "lp_blade_section": 4, "lp_turbine_hood": 1, "arabelle_condenser": 1,
    "main_oil_tank": 1, "shaft_oil_pump": 1, "generator_cooling_skid": 1, "msiv": 2, "steam_stop_valve": 2, "turbine_control_valve": 2,
    "bypass_steam_valve": 1, "turbine_feed_pump": 2, "circulating_water_pump": 1, "makeup_water_pump": 1, "condensate_storage_tank": 1,
    "hpcs_pump": 1, "lpcs_pump": 1, "rhr_pump": 1, "rhr_heat_exchanger": 1, "pressurised_tube": 40, "high_pressure_water_pipe": 80,
    "mechanical_draft_tower": 1, "screened_water_intake": 1, "water_discharge_port": 1, "fuel_fabricator": 1,
    "containment_airlock": 1, "containment_steam_penetration": 1, "containment_water_penetration": 1, "suppression_pool_wall": 80,
    "building_crane_console": 1, "crane_runway_rail": 12, "refuelling_bridge_console": 1, "refuelling_rail": 12, "spent_fuel_pool_wall": 60,
}


def holds(condition, loaded):
    kind = condition.get("type")
    if kind == "neoforge:mod_loaded":
        return condition["modid"] in loaded
    if kind == "neoforge:not":
        return not holds(condition["value"], loaded)
    if kind in ("neoforge:and", "neoforge:or"):
        parts = [holds(c, loaded) for c in condition.get("values", [])]
        return all(parts) if kind == "neoforge:and" else any(parts)
    return True


def load(loaded):
    by_result = {}
    for f in sorted(os.listdir(RECIPES)):
        if not f.endswith(".json"):
            continue
        r = json.load(open(os.path.join(RECIPES, f), encoding="utf-8"))
        if r.get("type") not in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
            continue
        if not all(holds(c, loaded) for c in r.get("neoforge:conditions", [])):
            continue
        res = r["result"]
        if res["id"].startswith("bwr:"):
            by_result.setdefault(res["id"][4:], []).append((f[:-5], r))
    return by_result


def spec(ing):
    if isinstance(ing, list):
        ing = ing[0]
    return "#" + ing["tag"] if "tag" in ing else ing["item"]


def ingredients(r):
    if r["type"] == "minecraft:crafting_shaped":
        counts = Counter(c for row in r["pattern"] for c in row if c != " ")
        return [(spec(r["key"][k]), n) for k, n in counts.items()]
    return [(spec(i), 1) for i in r["ingredients"]]


def cost(item, by_result, memo=None, stack=()):
    memo = {} if memo is None else memo
    if item in memo:
        return memo[item]
    recipes = by_result.get(item)
    total = Counter()
    if not recipes or item in stack:  # no recipe, or a storage pair going round (nugget, ingot, block): a raw material
        total[item] = 1
        memo[item] = total
        return total
    # prefer the recipe named like the item (or its _mekanism twin) over a conversion such as <name>_from_block
    name, r = sorted(recipes, key=lambda nr: (not (nr[0] == item or nr[0] == item + "_mekanism"), nr[0]))[0]
    out = r["result"].get("count", 1)
    for s, n in ingredients(r):
        if s.startswith("bwr:"):
            for k, v in cost(s[4:], by_result, memo, stack + (item,)).items():
                total[k] += v * n
        elif s == "mekanism:steel_casing":
            for k, v in CASING.items():
                total[k] += v * n
        elif s in EQUIV:
            unit, per = EQUIV[s]
            total[unit] += per * n
        else:
            total[s.replace("minecraft:", "").replace("mekanism:", "mek:").replace("#c:", "#")] += n
    scaled = Counter({k: v / out for k, v in total.items()})
    memo[item] = scaled
    return scaled


def show(counter):
    return ", ".join(f"{v:g} {k}" if abs(v - round(v)) < 1e-9 else f"{v:.2f} {k}" for k, v in sorted(counter.items(), key=lambda kv: -kv[1]))


def main(argv):
    loaded = set() if "--vanilla" in argv else {"mekanism"}
    names = [a for a in argv if not a.startswith("--")]
    by_result = load(loaded)
    memo = {}
    print("install:", "with Mekanism" if loaded else "without Mekanism")
    if "--plant" in argv:
        total = Counter()
        for item, n in PLANT.items():
            for k, v in cost(item, by_result, memo).items():
                total[k] += v * n
        print(f"minimal plant ({sum(PLANT.values())} pieces): {show(total)}")
        return 0
    for n in names:
        print(f"{n}: {show(cost(n, by_result, memo))}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
