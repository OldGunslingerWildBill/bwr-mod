#!/usr/bin/env python3
"""Generates the assets and data of the reactor-bred isotopes (BREEDING-TARGETS.md).

Run from the repository root:  python tools-make-breeding-assets.py
Standard library plus Pillow. Safe to run again: it rewrites the same files and adds missing lang, tag and data-map lines only.

Makes, for the four heat-source isotopes (polonium-210, plutonium-238, actinium-227, thulium-170) an ingot and a block of nine
(textures, models, blockstate, loot table, two recipes, the pickaxe tags, the two RTG fuel lines), for uranium-233 and
neptunium-237 an ingot (no RTG entry: one is fuel, the other an intermediate), for bismuth a nugget, and for the six target
rods an icon, an item model and the rod_variant overrides. The recolours start from the mod's own thorium ingot, thorium block,
lead nugget and cobalt target pictures, so the new items look like family.
"""
import colorsys
import json
import os
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
ASSETS = os.path.join(RES, "assets/bwr")
DATA = os.path.join(RES, "data/bwr")
TEX_ITEM = os.path.join(ASSETS, "textures/item/fuel")
TEX_BLOCK = os.path.join(ASSETS, "textures/block")

# id, English name, accent colour, grams in one ingot, W/g, half-life y, photon W/g, wall transmission, tooltip label
HEAT = [
    ("polonium_210", "Polonium-210", (150, 80, 205), 0.082, 140.0, 138.376 / 365.25, 0.0002, 1.0, "Po-210"),
    ("plutonium_238", "Plutonium-238", (205, 55, 55), 0.28, 0.5674, 87.7, 0.0002, 0.05, "Pu-238"),
    ("actinium_227", "Actinium-227", (60, 150, 235), 0.00103, 14.6, 21.772, 0.13, 1.0, "Ac-227 with daughters"),
    ("thulium_170", "Thulium-170", (232, 196, 70), 19.0, 11.5, 128.6 / 365.25, 0.08, 1.0, "Tm-170"),
]
PLAIN_INGOTS = [("uranium_233", "Uranium-233", (110, 195, 70)), ("neptunium_237", "Neptunium-237", (60, 175, 170))]
RODS = [("bismuth_target", "Bismuth Irradiation Rod", (200, 150, 170)), ("thorium_target", "Thorium Irradiation Rod", (110, 195, 70)),
        ("uranium_target", "Depleted Uranium Irradiation Rod", (200, 190, 60)), ("neptunium_target", "Neptunium Irradiation Rod", (60, 175, 170)),
        ("radium_target", "Radium Irradiation Rod", (90, 220, 120)), ("thulium_target", "Thulium Irradiation Rod", (232, 196, 70))]
ROD_VARIANT_FIRST = 8   # ordinal of BISMUTH_TARGET in CoreInsert.Kind


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def recolour_accent(img, colour, test):
    """Replace the pixels the test selects by the accent colour, keeping their light and dark."""
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0 and test(r, g, b):
                h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
                ch, cs, cv = colorsys.rgb_to_hsv(*[c / 255 for c in colour])
                nr, ng, nb = colorsys.hsv_to_rgb(ch, cs, min(1.0, cv * (v / 0.75)))
                px[x, y] = (int(nr * 255), int(ng * 255), int(nb * 255), a)
    return img


def ingot_texture(colour):
    img = Image.open(os.path.join(TEX_ITEM, "thorium_ingot.png")).convert("RGBA")
    return recolour_accent(img, colour, lambda r, g, b: r > b + 50 and r > g)   # the orange stripe


def rod_texture(colour):
    img = Image.open(os.path.join(TEX_ITEM, "cobalt_target.png")).convert("RGBA")
    return recolour_accent(img, colour, lambda r, g, b: b > r + 25 and b > g + 10)   # the blue bands


def nugget_texture():
    img = Image.open(os.path.join(TEX_ITEM, "lead_nugget.png")).convert("RGBA")
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 200:   # bismuth is silvery with a pink cast
                px[x, y] = (min(255, int(r * 1.45 + 38)), min(255, int(g * 1.2 + 20)), min(255, int(b * 1.2 + 18)), a)
    return img


def block_texture(colour):
    img = Image.open(os.path.join(TEX_BLOCK, "thorium_block.png")).convert("RGBA")
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            t = 0.32 if (x % 8 in (0, 7) or y % 8 in (0, 7)) else 0.22   # a tint, a little stronger on the plate seams
            px[x, y] = tuple(int(c * (1 - t) + k * t) for c, k in zip((r, g, b), colour)) + (a,)
    return img


def write_item_model(name, texture):
    dump(os.path.join(ASSETS, "models/item", name + ".json"),
         {"parent": "minecraft:item/generated", "textures": {"layer0": texture}})


def block_files(iso, name):
    dump(os.path.join(ASSETS, "blockstates", iso + "_block.json"), {"variants": {"": {"model": "bwr:block/%s_block" % iso}}})
    dump(os.path.join(ASSETS, "models/block", iso + "_block.json"),
         {"parent": "minecraft:block/cube_all", "textures": {"all": "bwr:block/%s_block" % iso}})
    dump(os.path.join(ASSETS, "models/item", iso + "_block.json"), {"parent": "bwr:block/%s_block" % iso})
    dump(os.path.join(DATA, "loot_table/blocks", iso + "_block.json"), {
        "type": "minecraft:block",
        "pools": [{"bonus_rolls": 0.0, "conditions": [{"condition": "minecraft:survives_explosion"}],
                   "entries": [{"type": "minecraft:item", "name": "bwr:%s_block" % iso}], "rolls": 1.0}],
        "random_sequence": "bwr:blocks/%s_block" % iso})
    dump(os.path.join(DATA, "recipe", iso + "_block.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"#": {"item": "bwr:%s_ingot" % iso}}, "pattern": ["###", "###", "###"],
        "result": {"count": 1, "id": "bwr:%s_block" % iso}})
    dump(os.path.join(DATA, "recipe", iso + "_ingot_from_block.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": "bwr:%s_block" % iso}], "result": {"count": 9, "id": "bwr:%s_ingot" % iso}})


def rod_recipe(kind, ingredients):
    dump(os.path.join(DATA, "recipe", kind + ".json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": "bwr:irradiation_casing"}] + ingredients,
        "result": {"id": "bwr:specialty_rod", "count": 1,
                   "components": {"bwr:core_insert": {"kind": kind, "exposure_seconds": 0}}}})


def main():
    lang_path = os.path.join(ASSETS, "lang/en_us.json")
    lang = load(lang_path)
    added = {}

    for iso, name, colour, grams, spw, half, gamma, trans, label in HEAT:
        ingot_texture(colour).save(os.path.join(TEX_ITEM, iso + "_ingot.png"))
        block_texture(colour).save(os.path.join(TEX_BLOCK, iso + "_block.png"))
        write_item_model(iso + "_ingot", "bwr:item/fuel/" + iso + "_ingot")
        block_files(iso, name)
        added["item.bwr.%s_ingot" % iso] = "%s Ingot" % name
        added["block.bwr.%s_block" % iso] = "Block of %s" % name
    for iso, name, colour in PLAIN_INGOTS:
        ingot_texture(colour).save(os.path.join(TEX_ITEM, iso + "_ingot.png"))
        write_item_model(iso + "_ingot", "bwr:item/fuel/" + iso + "_ingot")
        added["item.bwr.%s_ingot" % iso] = "%s Ingot" % name
    nugget_texture().save(os.path.join(TEX_ITEM, "bismuth_nugget.png"))
    write_item_model("bismuth_nugget", "bwr:item/fuel/bismuth_nugget")
    added["item.bwr.bismuth_nugget"] = "Bismuth Nugget"

    for kind, name, colour in RODS:
        rod_texture(colour).save(os.path.join(TEX_ITEM, kind + ".png"))
        dump(os.path.join(ASSETS, "models/item/fuel", kind + ".json"),
             {"parent": "minecraft:item/generated", "textures": {"layer0": "bwr:item/fuel/" + kind}})
        added["insert.bwr." + kind] = name

    # the specialty rod's picture follows the kind's ordinal
    path = os.path.join(ASSETS, "models/item/specialty_rod.json")
    model = load(path)
    have = {o["predicate"]["bwr:rod_variant"] for o in model["overrides"]}
    for i, (kind, _, _) in enumerate(RODS):
        if ROD_VARIANT_FIRST + i not in have:
            model["overrides"].append({"predicate": {"bwr:rod_variant": ROD_VARIANT_FIRST + i}, "model": "bwr:item/fuel/" + kind})
    dump(path, model)

    # recipes of the rods: a casing and the ore element (no charge item: the element is the charge)
    tag = lambda t: {"tag": t}
    item = lambda i: {"item": i}
    rod_recipe("bismuth_target", [item("bwr:bismuth_nugget")] * 2)
    rod_recipe("thorium_target", [tag("c:ingots/thorium")])
    rod_recipe("uranium_target", [tag("c:ingots/depleted_uranium")])
    rod_recipe("neptunium_target", [item("bwr:neptunium_237_ingot")] * 8)
    rod_recipe("radium_target", [item("bwr:radium")] * 8)
    rod_recipe("thulium_target", [item("bwr:rare_earth_concentrate")] * 8)

    # the RTG fuel list: an entry for the ingot and nine times that for the block
    path = os.path.join(DATA, "data_maps/item/rtg_fuel.json")
    fuel = load(path)
    for iso, name, colour, grams, spw, half, gamma, trans, label in HEAT:
        for suffix, mult in (("ingot", 1), ("block", 9)):
            fuel["values"]["bwr:%s_%s" % (iso, suffix)] = {
                "isotope": label, "specific_power_w_per_g": spw, "half_life_years": round(half, 4),
                "grams_per_item": round(grams * mult, 6), "gamma_w_per_g": gamma, "capsule_transmission": trans}
    dump(path, fuel)

    # mining tags: the blocks are metal blocks like the thorium block
    for tagfile in ("data/minecraft/tags/block/mineable/pickaxe.json", "data/minecraft/tags/block/needs_iron_tool.json"):
        path = os.path.join(RES, tagfile)
        t = load(path)
        for iso, *_ in HEAT:
            if "bwr:%s_block" % iso not in t["values"]:
                t["values"].append("bwr:%s_block" % iso)
        dump(path, t)

    # lang: lines appended before the closing brace, so the rest of the file (its escapes and order) is untouched
    with open(lang_path, encoding="utf-8", newline="") as f:
        text = f.read()
    nl = chr(13) + chr(10) if chr(13) in text else chr(10)
    changed = 0
    lines = ""
    for k, v in added.items():
        if k not in lang:
            lines += ',' + nl + '  ' + json.dumps(k) + ': ' + json.dumps(v, ensure_ascii=False)
            changed += 1
    if lines:
        end = text.rindex("}")
        body = text[:end].rstrip()
        text = body + lines + nl + "}" + nl
        with open(lang_path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    print("lang keys added:", changed)


if __name__ == "__main__":
    main()
