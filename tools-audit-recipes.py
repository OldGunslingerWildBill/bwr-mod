#!/usr/bin/env python3
"""Recipe coverage audit for the Realistic BWR mod. Verifies by parsing, not by eye.

Run from anywhere:  python tools-audit-recipes.py
Exits non-zero if anything is wrong.

Checks:
  1. every recipe under data/bwr/recipe parses and is well formed for its type
     (a shaped recipe has a rectangular pattern whose every symbol is a key and
     whose every key is used; a shapeless one has ingredients; every result is
     an object with an "id" and a positive count)
  2. every bwr item a recipe names (as ingredient or result) is a registered one
  3. every registered item (a block's item, or an item registered in BwrItems) is
     the result of some recipe, except the items listed in NO_RECIPE below, and
     every item listed there really has none (a stale entry is as wrong as a
     missing recipe)
  4. the same, once per install: with Mekanism loaded and without it. A recipe's
     neoforge:conditions (mod_loaded, not, and, or) are evaluated for each, so an
     item whose only recipe is gated on Mekanism is reported (it would be
     uncraftable without it), and so is a recipe with a condition this script
     cannot evaluate. The fuel fabricator has two recipes, one for each case.

The complement of tools-audit-assets.py, which checks recipes block by block:
this one also covers the plain items and says, for each item with no recipe,
why that is right.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
JAVA = os.path.join(ROOT, "mod/src/main/java/dev/bwr/mod")

# Items a player is not meant to craft, and why. Keep the reason: this table is
# the documentation of the decision.
NO_RECIPE = {
    # Retired hardware kept only so old worlds load (CHANGELOG alpha.11 and alpha.22). The player-facing
    # replacements are rcic_twl, hpci_turbine and the suppression pool steam inlet; tools-audit-assets.py
    # fails any recipe that produces or uses these.
    "rcic_turbine_pump": "retired block, replaced by rcic_twl",
    "hpci_turbine_pump": "retired block, replaced by hpci_turbine",
    "suppression_pool_quencher": "retired block, a tank has an internal steam distributor",
    # Made by the reactor, not at a bench.
    "cobalt_sample": "product of irradiating a cobalt target",
    "tritium_sample": "product of irradiating a lithium target",
    "spent_resin": "what a stainless steel nanofilter element becomes when it is replaced (the resin bed it replaced had this id); radioactive waste, and a recipe for it would make waste out of nothing",
    "doped_silicon": "product of irradiating a silicon target",
    "corium": "made by a core melting through its vessel; a recipe for it would be the bug",
    # Made by the fuel fabricator.
    "depleted_uranium": "byproduct of the fuel fabricator's item route",
    # Found in the ground: the ore blocks are what the uranium veins and ore blobs are made of (worldgen, URANIUM-ORE.md).
    # Their drops (raw uranium, with Fortune and Silk Touch) are in data/bwr/loot_table/blocks.
    "uranium_ore": "natural ore, placed by world generation; mined for raw uranium (silk touch gives the block)",
    "deepslate_uranium_ore": "natural ore in deepslate, placed by world generation; mined for raw uranium (silk touch gives the block)",
    # Found in the ground, as the uranium ores are: thorium ore (monazite and thorite in rock) and monazite sand (a beach and river placer).
    # Their drops are in data/bwr/loot_table/blocks (raw thorium, with Fortune and Silk Touch, and the byproducts); see THORIUM.md.
    "thorium_ore": "natural ore, placed by world generation; mined for raw thorium (silk touch gives the block)",
    "deepslate_thorium_ore": "natural ore in deepslate, placed by world generation; mined for raw thorium (silk touch gives the block)",
    "monazite_sand": "natural placer sand in beaches and rivers, placed by world generation; dug for raw thorium (silk touch gives the block)",
    # A byproduct of mining: monazite carries rare earths. It is used (an iron ingot and one make a flint and steel) but made only by mining.
    "rare_earth_concentrate": "byproduct of mining thorium ore and monazite sand; made by the mining, a recipe for it would make rare earths out of nothing",
    # Radium: a rare byproduct of mining thorium ore, a radioactive source material. This mod gives it no use; other things are built on it.
    "radium": "byproduct of mining thorium ore; made by the mining, a recipe for it would make a radioactive source out of nothing",
    # Made by the reprocessing plant.
    "fission_product_waste": "what burning fuel leaves, the reprocessing plant's byproduct: nothing uses it, and a recipe would make fuel waste out of nothing",
}

# The installs the audit evaluates recipe conditions for. Every craftable item needs a live recipe in each.
INSTALLS = {"with Mekanism": {"mekanism"}, "without Mekanism": set()}

problems = []


def bad(msg):
    problems.append(msg)


def read(rel):
    with open(os.path.join(JAVA, rel), encoding="utf-8") as f:
        return f.read()


blocks_src = read("registry/BwrBlocks.java")
items_src = read("registry/BwrItems.java")
const_to_name = dict(re.findall(
    r'DeferredBlock<[^>]+>\s+([A-Z0-9_]+)\s*=\s*\n?\s*BLOCKS\.register\(\s*"([a-z0-9_]+)"', blocks_src))
block_items = [const_to_name.get(c, "??" + c) for c in re.findall(r'registerSimpleBlockItem\(BwrBlocks\.([A-Z0-9_]+)\)', items_src)]
custom_block_items = re.findall(r'DeferredItem<BlockItem>\s+\w+\s*=\s*ITEMS\.register\(\s*"([a-z0-9_]+)"', items_src)
plain_items = [n for n in re.findall(r'ITEMS\.register(?:SimpleItem)?\(\s*"([a-z0-9_]+)"', items_src)
               if n not in custom_block_items]
registered = set(block_items) | set(custom_block_items) | set(plain_items)
for name in registered:
    if name.startswith("??"):
        bad(f"could not resolve a registered block item: {name}")

recipe_dir = os.path.join(RES, "data/bwr/recipe")
recipes = {}
for name in sorted(os.listdir(recipe_dir)):
    if not name.endswith(".json"):
        continue
    try:
        with open(os.path.join(recipe_dir, name), encoding="utf-8") as f:
            recipes[name] = json.load(f)
    except Exception as e:  # noqa: BLE001 - any parse failure is the finding
        bad(f"{name}: does not parse: {e}")


def ingredient_ids(ing):
    """The item ids and tags an ingredient names."""
    if isinstance(ing, str):
        return [ing]
    if isinstance(ing, list):
        return [x for i in ing for x in ingredient_ids(i)]
    if isinstance(ing, dict):
        return [v for k, v in ing.items() if k in ("item", "tag") and isinstance(v, str)]
    return []


def holds(condition, loaded):
    """Whether one neoforge condition is true on an install with the mods in `loaded`; None if it is not understood."""
    kind = condition.get("type") if isinstance(condition, dict) else None
    if kind == "neoforge:mod_loaded":
        return condition.get("modid") in loaded
    if kind == "neoforge:not":
        inner = holds(condition.get("value"), loaded)
        return None if inner is None else not inner
    if kind in ("neoforge:and", "neoforge:or"):
        parts = [holds(c, loaded) for c in condition.get("values", [])]
        if None in parts or not parts:
            return None
        return all(parts) if kind == "neoforge:and" else any(parts)
    if kind == "neoforge:true":
        return True
    if kind == "neoforge:false":
        return False
    return None


def live(recipe, loaded, name):
    """Whether the recipe is loaded on an install with `loaded` (reports a condition it cannot evaluate)."""
    for c in recipe.get("neoforge:conditions", []):
        value = holds(c, loaded)
        if value is None:
            bad(f"{name}: condition {json.dumps(c)} is not one this audit can evaluate")
            return False
        if not value:
            return False
    return True


produced = set()
produced_in = {mode: set() for mode in INSTALLS}
for name, r in recipes.items():
    kind = r.get("type")
    if not kind:
        bad(f"{name}: no type")
        continue
    if kind == "mekanism:oxidizing":
        continue  # a chemical recipe: no item result
    res = r.get("result")
    if not isinstance(res, dict) or "id" not in res:
        bad(f"{name}: result must be an object with an id")
        continue
    if not isinstance(res.get("count", 1), int) or res.get("count", 1) < 1:
        bad(f"{name}: result count must be a positive integer")
    rid = res["id"]
    if rid.startswith("bwr:"):
        produced.add(rid[4:])
        for mode, loaded in INSTALLS.items():
            if live(r, loaded, name):
                produced_in[mode].add(rid[4:])
        if rid[4:] not in registered:
            bad(f"{name}: result {rid} is not a registered item")
    if kind == "minecraft:crafting_shaped":
        pattern, key = r.get("pattern"), r.get("key")
        if not (isinstance(pattern, list) and pattern and all(isinstance(p, str) and p for p in pattern)):
            bad(f"{name}: shaped recipe without a pattern")
            continue
        if len({len(p) for p in pattern}) != 1 or len(pattern) > 3 or len(pattern[0]) > 3:
            bad(f"{name}: pattern is not a rectangle of at most 3 x 3")
        if not isinstance(key, dict):
            bad(f"{name}: shaped recipe without a key")
            continue
        used = set("".join(pattern)) - {" "}
        for sym in sorted(used - set(key)):
            bad(f"{name}: pattern symbol '{sym}' has no key")
        for sym in sorted(set(key) - used):
            bad(f"{name}: key '{sym}' is not used by the pattern")
        ingredients = [key[k] for k in key]
    elif kind == "minecraft:crafting_shapeless":
        ingredients = r.get("ingredients")
        if not (isinstance(ingredients, list) and ingredients):
            bad(f"{name}: shapeless recipe without ingredients")
            continue
    else:
        ingredients = []
        for field in ("ingredient", "ingredients", "input", "key"):
            if field in r:
                value = r[field]
                ingredients += list(value.values()) if (field == "key" and isinstance(value, dict)) else [value]
    for ing in ingredients:
        for ref in ingredient_ids(ing):
            # A bwr tag is defined by a file under data/bwr/tags/item; anything else must be a registered item.
            if ref.startswith("bwr:") and ref[4:] not in registered                     and not os.path.isfile(os.path.join(RES, "data/bwr/tags/item", ref[4:] + ".json")):
                bad(f"{name}: ingredient {ref} is not a registered item")

# ---- 4. collisions --------------------------------------------------------------------------------------------
def canon_ing(ing):
    """A stable text for one ingredient: its sorted item / tag ids (alternatives are one slot)."""
    return "|".join(sorted(ingredient_ids(ing)))


def condition_key(r):
    return json.dumps(r.get("neoforge:conditions", []), sort_keys=True)


def exclusive(a, b):
    """True when two condition lists can never both hold: one is {not: X} and the other lists X."""
    def negated(conds):
        return {json.dumps(c["value"], sort_keys=True) for c in conds if isinstance(c, dict) and c.get("type") == "neoforge:not"}
    def plain(conds):
        return {json.dumps(c, sort_keys=True) for c in conds if isinstance(c, dict)}
    ca, cb = r_conditions(a), r_conditions(b)
    return bool(negated(ca) & plain(cb)) or bool(negated(cb) & plain(ca))


def r_conditions(r):
    c = r.get("neoforge:conditions", [])
    return c if isinstance(c, list) else []


def pattern_key(r):
    """('shape', grid) for shaped, ('set', multiset) for shapeless; None for other recipe types.
    A shaped recipe is also keyed by its multiset so it meets a shapeless twin."""
    kind = r.get("type")
    if kind == "minecraft:crafting_shaped":
        pattern, key = r.get("pattern"), r.get("key")
        if not (isinstance(pattern, list) and isinstance(key, dict) and pattern):
            return None
        grid = tuple(tuple(canon_ing(key[c]) if c != " " and c in key else "" for c in row) for row in pattern)
        mirror = tuple(tuple(reversed(row)) for row in grid)
        cells = sorted(c for row in grid for c in row if c)
        return ("shape", min(grid, mirror)), ("set", tuple(cells))
    if kind == "minecraft:crafting_shapeless":
        ings = r.get("ingredients")
        if not isinstance(ings, list):
            return None
        return (("set", tuple(sorted(canon_ing(i) for i in ings))),)
    return None


by_pattern = {}
for name, r in recipes.items():
    keys = pattern_key(r)
    if keys:
        for k in keys:
            by_pattern.setdefault(k, []).append(name)
reported = set()
for k, names in sorted(by_pattern.items(), key=lambda kv: str(kv[0])):
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if (a, b) in reported or exclusive(recipes[a], recipes[b]):
                continue
            # Two shaped recipes with the same ingredients in different arrangements do not collide.
            if k[0] == "set" and all(recipes[n].get("type") == "minecraft:crafting_shaped" for n in (a, b)):
                continue
            reported.add((a, b))
            bad(f"recipe collision: {a} and {b} have the same ingredient pattern ({k[0]})")

# ---- 5. circular dependencies among bwr items ----------------------------------------------------------------
deps = {}
single = set()
for name, r in recipes.items():
    res = r.get("result")
    if not (isinstance(res, dict) and str(res.get("id", "")).startswith("bwr:")):
        continue
    out = res["id"][4:]
    if r.get("type") == "minecraft:crafting_shaped" and isinstance(r.get("key"), dict):
        ings = list(r["key"].values())
    elif r.get("type") == "minecraft:crafting_shapeless" and isinstance(r.get("ingredients"), list):
        ings = r["ingredients"]
    else:
        continue
    refs = {ref for ing in ings for ref in ingredient_ids(ing)}
    for ref in refs:
        if ref.startswith("bwr:"):
            deps.setdefault(out, set()).add(ref[4:])
    if len(refs) == 1 and next(iter(refs)).startswith("bwr:"):
        single.add((out, next(iter(refs))[4:]))

# A storage pair (nine nuggets make an ingot, an ingot makes nine nuggets) is a two-way single-ingredient loop, not a circular recipe.
for a, b in single:
    if (b, a) in single:
        deps[a].discard(b)

WHITE, GREY, BLACK = 0, 1, 2
colour = {}
cycles = []


def visit(node, path):
    colour[node] = GREY
    path.append(node)
    for nxt in sorted(deps.get(node, ())):
        if colour.get(nxt, WHITE) == GREY:
            cycles.append(path[path.index(nxt):] + [nxt])
        elif colour.get(nxt, WHITE) == WHITE:
            visit(nxt, path)
    path.pop()
    colour[node] = BLACK


for node in sorted(deps):
    if colour.get(node, WHITE) == WHITE:
        visit(node, [])
for cyc in cycles:
    bad("circular recipe dependency: " + " -> ".join(cyc))

for name in sorted(registered - produced):
    if name not in NO_RECIPE:
        bad(f"item '{name}' has no recipe and is not in NO_RECIPE")
for mode, made in produced_in.items():
    for name in sorted(registered - made):
        if name in produced and name not in NO_RECIPE:
            bad(f"item '{name}' has no recipe that is loaded {mode} (every recipe for it is gated out)")
for name, why in NO_RECIPE.items():
    if name not in registered:
        bad(f"NO_RECIPE lists '{name}', which is not a registered item")
    elif name in produced:
        bad(f"NO_RECIPE lists '{name}' ({why}) but a recipe produces it")

craftable = len(registered) - len(NO_RECIPE)
print(f"Recipe audit: {len(by_pattern)} ingredient patterns compared for collisions, {len(deps)} crafted bwr items checked for cycles")
print(f"Recipe audit: {len(recipes)} recipes parsed, {len(registered)} registered items, "
      f"{craftable} craftable, {len(NO_RECIPE)} deliberately without a recipe")
for name, why in sorted(NO_RECIPE.items()):
    print(f"  no recipe: {name:28s} {why}")
if problems:
    for p in problems:
        print("PROBLEM:", p)
    print(f"PROBLEMS: {len(problems)}")
    sys.exit(1)
print("PROBLEMS: 0")
