#!/usr/bin/env python3
"""Asset integrity audit for the Realistic BWR mod. Verifies by parsing, not by eye.

Run from anywhere:  python tools-audit-assets.py
Exits non-zero if anything is wrong, so it can be wired into CI.

Checks:
  1. every .json under assets/ and data/ parses
  2. every model's texture references resolve to a real .png, and every
     #placeholder used by a face is defined somewhere in the parent chain
  2b. every texture a JAVA source file names resolves to a real .png -- the
     five GUI backgrounds are built in the screen classes as ResourceLocations
     and appear in no .json at all, so check 2 could never see them -- and no
     .png under assets/bwr/textures/ is referenced by nothing
  3. every registered block (read out of BwrBlocks.java) has a blockstate,
     a block model, an item model, a loot table and a recipe
  4. every blockstate covers all BlockState combinations the Java block
     actually defines -- an uncovered variant is a missing-model error in game.
     A multipart blockstate has no variant keys to enumerate, so for those the
     check is that every property the Java block defines is tested by at least
     one "when" clause, i.e. that it changes what renders at all
  5. lang keys exist for every block, item and the creative tab
  6. recipes use the 1.21 "id" result key, have no orphan pattern keys, gate
     foreign-mod ingredients behind neoforge:mod_loaded, and name only
     foreign item ids from the verified FOREIGN_ITEM_IDS table below --
     cross-checked against Mekanism's pinned jar when it is in the Gradle cache
  7. the 1.21 singular folder names (recipe/, loot_table/, advancement/)
  8. loot tables and tags only reference things that are registered

EVERY check above reaches bad() and therefore the exit code. Three of them
used to be computed, printed and then discarded -- per-block coverage,
blockstate variant coverage, and (nonexistent) multipart coverage -- so the
tool could print a defect and still exit 0. A check that cannot fail is worse
than no check, because HANDOFF.md and BUILD-STATUS.md both cite this exit code
as proof. If you add a check, wire it to bad() and negative-test it.
"""
import json, os, re, sys, itertools, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
JAVA = os.path.join(ROOT, "mod/src/main/java/dev/bwr/mod")

problems = []
def bad(cat, msg):
    problems.append((cat, msg))

# ---------- 1. parse every json ----------
jsons = {}
count_json = 0
for base, _, files in os.walk(RES):
    for f in files:
        if not f.endswith(".json"):
            continue
        p = os.path.join(base, f)
        rel = os.path.relpath(p, RES).replace("\\", "/")
        count_json += 1
        try:
            with open(p, encoding="utf-8") as fh:
                jsons[rel] = json.load(fh)
        except Exception as e:
            bad("JSON-PARSE", f"{rel}: {e}")

# ---------- registered blocks / items from java ----------
src = open(os.path.join(JAVA, "registry/BwrBlocks.java"), encoding="utf-8").read()
blocks = re.findall(r'BLOCKS\.register\(\s*"([a-z0-9_]+)"', src)
isrc = open(os.path.join(JAVA, "registry/BwrItems.java"), encoding="utf-8").read()
simple_block_items = re.findall(r'registerSimpleBlockItem\(BwrBlocks\.([A-Z0-9_]+)\)', isrc)
extra_items = re.findall(r'ITEMS\.register(?:SimpleItem)?\(\s*"([a-z0-9_]+)"', isrc)
# Explicitly registered BlockItem subclasses still use block translation keys.
custom_block_items = re.findall(r'DeferredItem<BlockItem>\s+\w+\s*=\s*ITEMS\.register\(\s*"([a-z0-9_]+)"', isrc)
extra_items = [it for it in extra_items if it not in custom_block_items]
# map CONSTANT -> registry name via BwrBlocks
const_to_name = dict(re.findall(
    r'DeferredBlock<[^>]+>\s+([A-Z0-9_]+)\s*=\s*\n?\s*BLOCKS\.register\(\s*"([a-z0-9_]+)"', src))
block_items = [const_to_name.get(c, "??" + c) for c in simple_block_items] + custom_block_items
all_items = block_items + extra_items

# ---------- block state properties, scraped from java ----------
props = {}   # blockname -> {prop: [values]} ; None means multipart/pipe
def javafile(cls):
    for base, _, files in os.walk(JAVA):
        if cls + ".java" in files:
            return open(os.path.join(base, cls + ".java"), encoding="utf-8").read()
    return ""

# resolve per-block: find "name" -> class on the same register line
for m in re.finditer(r'BLOCKS\.register\(\s*"([a-z0-9_]+)",\s*\(\)\s*->\s*new ([A-Za-z0-9_.]+)\(', src):
    name, cls = m.group(1), m.group(2).split('.')[-1]
    body = javafile(cls)
    # Registry classes can inherit blockstate properties (e.g. paired jet pumps).
    seen_classes = {cls}
    ancestry = body
    while True:
        parent = re.search(r'\bclass\s+\w+\s+extends\s+([\w.]+)', ancestry)
        if not parent:
            break
        parent_name = parent.group(1).split('.')[-1]
        if parent_name in seen_classes:
            break
        seen_classes.add(parent_name)
        ancestry = javafile(parent_name)
        if not ancestry:
            break
        body += '\n' + ancestry
    pr = {}
    for pm in re.finditer(r'BooleanProperty\s+([A-Z_]+)\s*=\s*BooleanProperty\.create\("([a-z_]+)"\)', body):
        pr[pm.group(2)] = ["false", "true"]
    for pm in re.finditer(r'EnumProperty<(\w+)>\s+([A-Z_]+)\s*=\s*EnumProperty\.create\("([a-z_]+)",\s*(\w+)\.class\)', body):
        ename, pname = pm.group(1), pm.group(3)
        em = re.search(r'enum\s+' + ename + r'\s*implements[^{]*\{(.*?)\;', body, re.S) \
             or re.search(r'enum\s+' + ename + r'\s*\{(.*?)\;', body, re.S)
        if not em:
            enum_body = javafile(ename)
            em = re.search(r'enum\s+' + ename + r'\s*implements[^{]*\{(.*?)\;', enum_body, re.S)
        vals = []
        if em:
            for c in re.finditer(r'\b([A-Z][A-Z0-9_]*)\s*\(', em.group(1)):
                vals.append(c.group(1).lower())
            if not vals:
                vals = [v.strip().lower() for v in em.group(1).split(",") if v.strip()]
        pr[pname] = vals
    for pm in re.finditer(r'IntegerProperty\s+[A-Z_]+\s*=\s*IntegerProperty\.create\("([a-z_]+)",\s*(\d+),\s*(\d+)\)', body):
        pr[pm.group(1)] = [str(v) for v in range(int(pm.group(2)), int(pm.group(3)) + 1)]
    if "BlockStateProperties.HORIZONTAL_FACING" in body:
        pr["facing"] = ["north", "east", "south", "west"]
    if re.search(r"BlockStateProperties\.FACING\b", body):
        pr["facing"] = ["north", "east", "south", "west", "up", "down"]
    if "extends PipeBlock" in body:
        pr.update({d: ["false", "true"] for d in
              ["north", "east", "south", "west", "up", "down"]})
    props[name] = pr

# ---------- 2. model texture resolution ----------
def model_path(ref):
    """bwr:block/foo -> assets/bwr/models/block/foo.json"""
    ns, _, path = ref.partition(":")
    if not _:
        ns, path = "minecraft", ns
    return ns, f"assets/{ns}/models/{path}.json"

def tex_split(ref):
    """'bwr:block/foo' -> ('bwr', 'block/foo'); a bare path is minecraft's."""
    ns, sep, path = ref.partition(":")
    if not sep:
        ns, path = "minecraft", ns
    return ns, path

def tex_file(path):
    return os.path.join(RES, f"assets/bwr/textures/{path}.png")

def tex_exists(ref):
    ns, path = tex_split(ref)
    if ns != "bwr":
        return True  # vanilla textures assumed present
    return os.path.isfile(tex_file(path))

# Every bwr texture path anything in this mod actually asks for, from models
# (below) and from Java source (check 2b). Feeds the orphan check.
referenced_textures = set()

models = {k: v for k, v in jsons.items() if k.startswith("assets/bwr/models/")}
tex_refs = 0
for rel, m in models.items():
    if m.get("loader") == "neoforge:obj":
        # Mesh models have resource dependencies outside the usual JSON tree.
        obj_ref = m.get("model", "")
        obj_ns, _, obj_path = obj_ref.partition(":")
        obj_file = os.path.join(RES, "assets", obj_ns, obj_path)
        if not obj_path or not os.path.isfile(obj_file):
            bad("MISSING-OBJ", f"{rel}: missing mesh {obj_ref}")
        else:
            mesh = open(obj_file, encoding="utf-8").read()
            libraries = re.findall(r'^mtllib\s+(.+)$', mesh, re.M)
            if m.get("mtl_override"):
                libraries = [m["mtl_override"]]
            for library in libraries:
                if ":" in library:
                    mat_ns, mat_path = library.strip().split(":", 1)
                    material = os.path.join(RES, "assets", mat_ns, mat_path)
                else:
                    material = os.path.join(os.path.dirname(obj_file), library.strip())
                if not os.path.isfile(material):
                    bad("MISSING-MTL", f"{rel}: missing material {library}")
                    continue
                for texture in re.findall(r'^map_Kd\s+(.+)$', open(material, encoding="utf-8").read(), re.M):
                    texture = texture.strip()
                    ns_t, path_t = tex_split(texture)
                    if ns_t == "bwr":
                        referenced_textures.add(path_t)
                    if not texture.startswith("#") and not tex_exists(texture):
                        bad("MISSING-TEXTURE", f"{rel}: material texture {texture} is missing")
    for k, v in (m.get("textures") or {}).items():
        if not isinstance(v, str):
            bad("MODEL", f"{rel}: texture '{k}' is not a string")
            continue
        if v.startswith("#"):
            continue
        tex_refs += 1
        ns_t, path_t = tex_split(v)
        if ns_t == "bwr":
            referenced_textures.add(path_t)
        if not tex_exists(v):
            bad("MISSING-TEXTURE", f"{rel}: texture '{k}' -> '{v}' has no .png")
    par = m.get("parent")
    if par:
        ns, pp = model_path(par)
        if ns == "bwr" and pp not in jsons:
            bad("MISSING-PARENT", f"{rel}: parent '{par}' -> {pp} does not exist")
    # every #ref used in elements must be defined somewhere in the chain
    defined = set()
    cur, depth = m, 0
    while cur is not None and depth < 10:
        defined |= set((cur.get("textures") or {}).keys())
        p = cur.get("parent")
        if not p:
            break
        ns, pp = model_path(p)
        cur = jsons.get(pp) if ns == "bwr" else None
        depth += 1
    used = set()
    for el in m.get("elements", []):
        for fk, fv in (el.get("faces") or {}).items():
            t = fv.get("texture", "")
            if t.startswith("#"):
                used.add(t[1:])
    for u in used - defined:
        if par and not par.startswith("bwr:"):
            continue  # vanilla parent may define it
        bad("MODEL", f"{rel}: face uses #{u} but no texture key '{u}' in the chain")

# ---------- 2b. textures named from Java, and orphans ----------
#
# Check 2 walks assets/bwr/models/ and nothing else, so it can only see textures
# a model names. The five GUI backgrounds are named by nothing else: each screen
# class under mod/gui/client builds its own ResourceLocation
# (BwrMod.id("textures/gui/reactor_panel.png") and so on) and hands it to
# BwrScreen, which blits it. No .json anywhere mentions them. The result was that
# assets/bwr/textures/gui/*.png were the only five PNGs in the mod that this tool
# had never once looked at -- rename one, or typo one path while replacing the
# placeholder art, and the audit still printed PROBLEMS: 0 while the screen
# rendered as the black-and-magenta missing texture.
#
# Comments are stripped before scanning, the same way :mod:checkNoProtectionLogic
# does it and for the same reason: this codebase writes long javadoc, and a
# {@code textures/gui/foo.png} in a doc comment is documentation, not a
# reference. Verified above that no string literal in mod/ contains "//".
#
# The orphan half is the other direction, and it earns its place now rather than
# later: the placeholder art is about to be replaced wholesale, and the failure
# mode of that job is a file renamed on disk but not in the source that names it.
# That leaves an orphan and a missing texture together, and the orphan is the one
# that says which file to look at.
#
# \s* everywhere, because Java wraps. The first version of this required
# "ResourceLocation." to sit immediately against ".fromNamespaceAndPath", and a
# perfectly ordinary line break between them was enough for a broken GUI path to
# walk straight through the new check during its own negative test.
RL_PATTERNS = [
    # The mod's own helper. Everything it builds is in the bwr namespace by
    # construction -- see BwrMod.id.
    (re.compile(r'BwrMod\s*\.\s*id\s*\(\s*"([^"]*)"\s*\)'),
     lambda m: ("bwr", m.group(1))),
    (re.compile(r'ResourceLocation\s*\.\s*fromNamespaceAndPath\s*\(\s*"([^"]*)"\s*,'
                r'\s*"([^"]*)"\s*\)'),
     lambda m: (m.group(1), m.group(2))),
    (re.compile(r'ResourceLocation\s*\.\s*parse\s*\(\s*"([^"]*)"\s*\)'),
     lambda m: tex_split(m.group(1))),
    (re.compile(r'ResourceLocation\s*\.\s*withDefaultNamespace\s*\(\s*"([^"]*)"\s*\)'),
     lambda m: ("minecraft", m.group(1))),
]

# Anything shaped like a texture path, so that a reference written in a form the
# patterns above do not know about is reported rather than silently skipped. That
# is the difference between a check and a decoration: without this, adding one
# more way to build a ResourceLocation quietly shrinks the audit's coverage and
# nothing says so.
TEX_LITERAL = re.compile(r'"((?:[a-z0-9_.-]+:)?textures/[^"]*)"')

def strip_java_comments(text):
    return re.sub(r'//[^\n]*', '', re.sub(r'/\*.*?\*/', '', text, flags=re.S))

java_tex_refs = {}   # 'gui/reactor_panel' -> [source files that name it]
for base, _, files in os.walk(JAVA):
    for f in files:
        if not f.endswith(".java"):
            continue
        p = os.path.join(base, f)
        rel = os.path.relpath(p, JAVA).replace("\\", "/")
        code = strip_java_comments(open(p, encoding="utf-8").read())
        claimed = set()
        for pattern, split in RL_PATTERNS:
            for m in pattern.finditer(code):
                ns, path = split(m)
                # Both spellings, so the literal-sweep below recognises this as
                # accounted for whichever form the source used.
                claimed.add(path)
                claimed.add(f"{ns}:{path}")
                if ns != "bwr" or not path.startswith("textures/"):
                    continue
                if not path.endswith(".png"):
                    bad("JAVA-TEXTURE", f"{rel}: names texture '{path}' with no .png "
                                        f"extension; a texture ResourceLocation in 1.21 is the "
                                        f"full path under assets/<ns>/ and must include it")
                    continue
                # 'textures/gui/x.png' -> 'gui/x', the same key the model refs use
                key = path[len("textures/"):-len(".png")]
                java_tex_refs.setdefault(key, []).append(rel)
                referenced_textures.add(key)
                if not os.path.isfile(tex_file(key)):
                    bad("MISSING-TEXTURE", f"{rel}: names 'bwr:{path}' which has no .png on "
                                           f"disk; the screen renders as the missing texture")
        for m in TEX_LITERAL.finditer(code):
            lit = m.group(1)
            if lit in claimed:
                continue
            bad("JAVA-TEXTURE", f"{rel}: the string \"{lit}\" is shaped like a texture path but "
                                f"is not built into a ResourceLocation in any form this audit "
                                f"recognises, so the audit cannot tell whether it resolves. Use "
                                f"BwrMod.id(...) with a literal, or teach RL_PATTERNS in this "
                                f"script the form you used")

# Orphans, both directions now accounted for.
textures_root = os.path.join(RES, "assets/bwr/textures")
textures_on_disk = set()
for base, _, files in os.walk(textures_root):
    for f in files:
        if f.endswith(".png"):
            rel = os.path.relpath(os.path.join(base, f), textures_root).replace("\\", "/")
            textures_on_disk.add(rel[:-len(".png")])
for orphan in sorted(textures_on_disk - referenced_textures):
    bad("ORPHAN-TEXTURE", f"assets/bwr/textures/{orphan}.png is referenced by no model and "
                          f"no Java source; either something that should point at it does not "
                          f"(look for a MISSING-TEXTURE above naming a similar path) or it is "
                          f"dead weight in the jar")

# ---------- 3. per-block coverage ----------
missing = {"blockstate": [], "blockmodel": [], "itemmodel": [], "loot": [], "recipe": []}

# What each per-block gap actually costs in game. These strings go into the
# bad() message so the report says why it matters and not just that a file is
# absent -- a missing loot table on a requiresCorrectToolForDrops block means
# the block cannot be recovered at all, which is not obvious from "no json".
MISSING_MEANS = {
    "blockstate": "renders as the missing model everywhere it is placed",
    "blockmodel": "no variant resolves to a model that exists",
    "itemmodel": "the item form renders as the missing model in inventories",
    "loot": "drops nothing when mined (every block here is "
            "requiresCorrectToolForDrops, so there is no other way to get it back)",
    "recipe": "unobtainable in survival",
}

bs_variant_gaps = []   # (block, n_gaps, sample)   -- 'variants' blockstates
bs_multipart = []      # (block, n_parts, [props never tested])  -- 'multipart'
for b in blocks:
    bs = f"assets/bwr/blockstates/{b}.json"
    if bs not in jsons:
        missing["blockstate"].append(b)
    else:
        d = jsons[bs]
        refs = []
        if "variants" in d:
            for key, val in d["variants"].items():
                vl = val if isinstance(val, list) else [val]
                for v in vl:
                    refs.append(v.get("model"))
            # coverage against java properties
            pr = props.get(b, {})
            if pr:
                keys = sorted(pr)
                combos = ["".join(x) for x in []]
                allcombos = set()
                for tup in itertools.product(*[pr[k] for k in keys]):
                    allcombos.add(",".join(f"{k}={v}" for k, v in zip(keys, tup)))
                have = set()
                for key in d["variants"]:
                    if key == "":
                        have = allcombos
                        break
                    parts = dict(p.split("=") for p in key.split(",") if "=" in p)
                    # expand unspecified properties
                    freek = [k for k in keys if k not in parts]
                    for tup in itertools.product(*[pr[k] for k in freek]):
                        full = dict(parts)
                        full.update(dict(zip(freek, tup)))
                        have.add(",".join(f"{k}={full[k]}" for k in keys))
                gaps = allcombos - have
                if gaps:
                    bs_variant_gaps.append((b, len(gaps), sorted(gaps)[:4]))
                    # This is check 4's whole point and it has to reach the
                    # exit code. An uncovered BlockState is not cosmetic: the
                    # block renders as the missing model in exactly those
                    # states and nowhere else, which is the hardest kind of
                    # asset bug to notice by playing.
                    bad("BLOCKSTATE", f"{b}: {len(gaps)} of {len(allcombos)} state(s) have no "
                                      f"variant and will render as the missing model, "
                                      f"e.g. {sorted(gaps)[:4]}")
                unknown = set()
                for key in d["variants"]:
                    for p in key.split(","):
                        if "=" in p and p.split("=")[0] not in pr:
                            unknown.add(p.split("=")[0])
                for u in unknown:
                    bad("BLOCKSTATE", f"{b}: variant key uses property '{u}' the block does not define")
        elif "multipart" in d:
            for part in d["multipart"]:
                a = part.get("apply")
                al = a if isinstance(a, list) else [a]
                for v in al:
                    refs.append(v.get("model"))
            pr = props.get(b, {})
            used = set()
            for part in d["multipart"]:
                w = part.get("when", {})
                for wk in w:
                    if wk in ("OR", "AND"):
                        for sub in w[wk]:
                            used |= set(sub.keys())
                    else:
                        used.add(wk)
            for u in used - set(pr):
                bad("BLOCKSTATE", f"{b}: multipart condition on unknown property '{u}'")
            # Coverage, for a blockstate that has no variant keys to enumerate.
            #
            # "Every combination has a model" is trivially true for a multipart
            # with an unconditional part -- pressurised_tube has one, so that
            # test would pass even with every arm deleted, and the report used
            # to print "fully covered" for exactly that reason without ever
            # computing anything. The useful invariant instead is that every
            # property the Java block defines has to be tested by at least one
            # "when": these properties exist *only* to add geometry (one arm per
            # connected side), so a property no clause mentions is a property
            # whose true and false states render identically. Deleting the
            # down-arm clause looks precisely like that, and in game it is a
            # hole in every downward pipe run.
            #
            # If a future block ever gains a genuinely non-rendering property on
            # a multipart blockstate, this will fail and want a decision -- that
            # is the intended direction to fail in.
            untested = sorted(set(pr) - used)
            bs_multipart.append((b, len(d["multipart"]), untested))
            for u in untested:
                bad("BLOCKSTATE", f"{b}: multipart has no 'when' clause testing property "
                                  f"'{u}', so its states render identically "
                                  f"(a deleted 'when' clause looks exactly like this)")
        else:
            bad("BLOCKSTATE", f"{b}: neither 'variants' nor 'multipart'")
        got_model = False
        for r in refs:
            if r is None:
                bad("BLOCKSTATE", f"{b}: variant with no 'model' key")
                continue
            ns, mp = model_path(r)
            if ns == "bwr" and mp not in jsons:
                bad("MISSING-MODEL", f"{bs}: references '{r}' -> {mp} which does not exist")
            else:
                got_model = True
        if not got_model:
            missing["blockmodel"].append(b)
    if f"assets/bwr/models/item/{b}.json" not in jsons:
        missing["itemmodel"].append(b)
    if f"data/bwr/loot_table/blocks/{b}.json" not in jsons:
        missing["loot"].append(b)
    if b not in {"rcic_turbine_pump", "hpci_turbine_pump"} and f"data/bwr/recipe/{b}.json" not in jsons:
        missing["recipe"].append(b)

# Check 3 reaches the exit code. It used to accumulate into `missing`, print a
# "20/21 MISSING: ['msiv']" line and then exit 0, which meant the tool could
# report a block with no loot table and still pass -- while HANDOFF.md and
# BUILD-STATUS.md both cited that pass as verification.
for _kind, _blocks in missing.items():
    for _b in _blocks:
        bad("COVERAGE", f"block '{_b}' has no {_kind}: {MISSING_MEANS[_kind]}")

for it in extra_items:
    if f"assets/bwr/models/item/{it}.json" not in jsons:
        bad("MISSING-ITEMMODEL", f"non-block item '{it}' has no item model")

# ---------- 4. lang ----------
lang = jsons.get("assets/bwr/lang/en_us.json", {})
for b in blocks:
    if f"block.bwr.{b}" not in lang:
        bad("LANG", f"missing key block.bwr.{b}")
for it in extra_items:
    if f"item.bwr.{it}" not in lang:
        bad("LANG", f"missing key item.bwr.{it}")
if "itemGroup.bwr" not in lang:
    bad("LANG", "missing key itemGroup.bwr")

# ---------- 5. recipes ----------
#
# Every foreign item id any recipe is allowed to name, and where each one was
# checked. An id outside this table fails the audit.
#
# Why a table rather than a rule: this script has no registry, so it cannot
# resolve another mod's ids by itself, and up to now it did not try -- check 6
# verified only that the *namespace* was gated behind neoforge:mod_loaded and
# never that the id existed. `mekanism:pellet_fissile_fuel` therefore sat in
# fuel_assembly.json through every green run of this tool and every green
# `runData` (forgedatadev never constructs a RecipeManager), and only a real
# server boot found it: RecipeManager threw JsonParseException on the unknown
# registry key and dropped the recipe, so the mod's central consumable had no
# crafting route in any install -- gated out without Mekanism, broken with it.
#
# Adding a line here is the moment you are expected to open the other mod's jar
# and look. The block after the recipe loop does exactly that automatically for
# every mekanism: id below whenever the pinned jar is in the Gradle cache, so
# the table cannot rot silently when mekanismVersion moves.
FOREIGN_ITEM_IDS = {
    # Enrichment Chamber output (mekanism:enriching, c:ingots/uranium -> 2).
    # This is the item form of Mekanism's enrichment chain and therefore the
    # SPEC 2.4 fuel boundary; everything past it in that chain is a Chemical,
    # not an item, which is what "fissile fuel" is and why no pellet of it
    # exists to craft with.
    "mekanism:yellow_cake_uranium": "item.mekanism.yellow_cake_uranium",
    "mekanism:advanced_control_circuit": "item.mekanism.advanced_control_circuit",
    "mekanism:alloy_infused": "item.mekanism.alloy_infused",
    "mekanism:steel_casing": "block.mekanism.steel_casing",
}

valid_ids = {"bwr:" + n for n in blocks} | {"bwr:" + n for n in extra_items}
recipes = {k: v for k, v in jsons.items() if k.startswith("data/bwr/recipe")}
foreign_ids_used = set()
RETIRED_BLOCKS = {"rcic_turbine_pump", "hpci_turbine_pump"}
creative_source = open(os.path.join(ROOT, "mod/src/main/java/dev/bwr/mod/registry/BwrItems.java"), encoding="utf-8").read()
for retired in RETIRED_BLOCKS:
    if re.search(r"output\.accept\(" + retired.upper() + r"\.get\(\)\)", creative_source):
        bad("RETIRED", f"{retired}: still in the creative tab")
for rel, r in recipes.items():
    for retired in RETIRED_BLOCKS:
        if '"bwr:' + retired + '"' in json.dumps(r):
            bad("RETIRED", f"{rel}: uses or produces retired block {retired}")
    if "type" not in r:
        bad("RECIPE", f"{rel}: no 'type'")
    if r.get("type") == "mekanism:oxidizing":
        conditions = r.get("neoforge:conditions", [])
        for mod in ("mekanism", "mekanismgenerators"):
            if not any(c.get("type") == "neoforge:mod_loaded" and c.get("modid") == mod for c in conditions):
                bad("RECIPE", f"{rel}: optional {mod} processing is not mod-gated")
        ingredient, output = r.get("input", {}), r.get("output", {})
        if ingredient.get("item") not in valid_ids or ingredient.get("count") != 1:
            bad("RECIPE", f"{rel}: invalid chemical-processing input")
        if output.get("id") != "mekanismgenerators:tritium" or not isinstance(output.get("amount"), int) or not 0 < output["amount"] <= 10000:
            bad("RECIPE", f"{rel}: unknown chemical or output exceeds pinned oxidizer capacity")
        continue
    res = r.get("result")
    if res is None:
        bad("RECIPE", f"{rel}: no 'result'")
        continue
    if isinstance(res, dict):
        if "item" in res and "id" not in res:
            bad("RECIPE", f"{rel}: result uses 1.20 'item' key; 1.21 requires 'id'")
        rid = res.get("id") or res.get("item")
    else:
        rid = res
        bad("RECIPE", f"{rel}: result is a bare string, 1.21 wants an object")
    if rid and rid.startswith("bwr:") and rid not in valid_ids:
        bad("RECIPE", f"{rel}: result '{rid}' is not a registered bwr item")
    # ingredient sanity
    def check_ing(v, where):
        if isinstance(v, dict):
            for kk in ("item", "tag"):
                if kk in v and isinstance(v[kk], str):
                    if v[kk].startswith("bwr:") and kk == "item" and v[kk] not in valid_ids:
                        bad("RECIPE", f"{rel}: {where} item '{v[kk]}' not registered")
        elif isinstance(v, list):
            for x in v:
                check_ing(x, where)
        elif isinstance(v, str):
            if v.startswith("bwr:") and v not in valid_ids:
                bad("RECIPE", f"{rel}: {where} '{v}' not registered")
    for k, v in (r.get("key") or {}).items():
        check_ing(v, f"key '{k}'")
    for v in (r.get("ingredients") or []):
        check_ing(v, "ingredient")
    # foreign-mod ingredients must be gated behind a mod_loaded condition,
    # otherwise the recipe errors on an install without that mod
    txt = json.dumps(r)
    foreign = set()
    for m in re.finditer(r'"(?:item|id)":\s*"([a-z0-9_.-]+):([a-z0-9_./-]+)"', txt):
        ns, path = m.group(1), m.group(2)
        if ns in ("minecraft", "bwr", "c"):
            continue
        foreign.add(ns)
        fid = ns + ":" + path
        foreign_ids_used.add(fid)
        if fid not in FOREIGN_ITEM_IDS:
            # An id this script has never confirmed exists. That is a
            # datapack-load error waiting to happen, not a style point: an
            # unknown item id makes RecipeManager throw and drop the entire
            # recipe, silently, on the one install the recipe was written for.
            bad("RECIPE", f"{rel}: foreign item id '{fid}' is not in FOREIGN_ITEM_IDS. "
                          f"Confirm it exists in that mod's jar (lang key, item model "
                          f"or blockstate) and add it to the table in this script -- "
                          f"an id that does not exist makes RecipeManager drop the "
                          f"whole recipe at datapack load")
    gated = {c.get("modid") for c in (r.get("neoforge:conditions") or [])
             if c.get("type") == "neoforge:mod_loaded"}
    for ns in foreign - gated:
        bad("RECIPE", f"{rel}: uses '{ns}:' items but has no "
                      f"neoforge:mod_loaded condition for '{ns}' "
                      f"(will error on installs without it)")
    if r.get("type") == "minecraft:crafting_shaped":
        pat = r.get("pattern") or []
        keys = set((r.get("key") or {}).keys())
        used = {c for row in pat for c in row if c != " "}
        for u in used - keys:
            bad("RECIPE", f"{rel}: pattern uses '{u}' with no key entry")
        for u in keys - used:
            bad("RECIPE", f"{rel}: key '{u}' defined but unused in pattern")
        if len({len(r_) for r_ in pat}) > 1:
            bad("RECIPE", f"{rel}: pattern rows have unequal length")

# ---------- 5a. cross-check FOREIGN_ITEM_IDS against Mekanism's pinned jar ----------
# The table above is only as good as whoever last edited it, so verify it
# against the actual artifact this build pins whenever that artifact is on
# disk. This is the check that catches the pellet_fissile_fuel class of defect
# from cold, and it also catches the table going stale across a Mekanism bump.
#
# Absence of the jar is reported, not tolerated silently -- but it does not
# fail the run, because the table check above still cannot be bypassed and
# failing on a cold Gradle cache would just teach people to ignore this tool.

def gradle_properties(key):
    path = os.path.join(ROOT, "gradle.properties")
    if not os.path.isfile(path):
        return None
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return None

def find_pinned_jar(group, name, version):
    """The jar ModDevGradle resolved, wherever the Gradle cache lives."""
    if not version:
        return None
    home = os.environ.get("GRADLE_USER_HOME") \
        or os.path.join(os.path.expanduser("~"), ".gradle")
    base = os.path.join(home, "caches", "modules-2", "files-2.1", group, name, version)
    if not os.path.isdir(base):
        return None
    for d, _, fs in os.walk(base):
        for f in sorted(fs):
            # API and Generators classifiers share this cache directory; inspect the base mod.
            if f == f"{name}-{version}.jar":
                return os.path.join(d, f)
    return None

mek_version = gradle_properties("mekanismVersion")
mek_jar = find_pinned_jar("mekanism", "Mekanism", mek_version)
mek_checked = 0
if mek_jar:
    try:
        with zipfile.ZipFile(mek_jar) as z:
            entries = set(z.namelist())
            mek_lang = {}
            if "assets/mekanism/lang/en_us.json" in entries:
                mek_lang = json.loads(z.read("assets/mekanism/lang/en_us.json").decode("utf-8"))
    except Exception as e:
        bad("MEKANISM", f"could not read {os.path.basename(mek_jar)}: {e}")
        mek_jar = None
if mek_jar:
    for fid in sorted(FOREIGN_ITEM_IDS):
        ns, _, path = fid.partition(":")
        if ns != "mekanism":
            continue
        mek_checked += 1
        # Any one of these three is proof the id is registered. Three sources
        # rather than one so a legitimately untranslated or custom-modelled
        # item is not reported as missing.
        if ("item.mekanism." + path) in mek_lang \
                or ("block.mekanism." + path) in mek_lang \
                or f"assets/mekanism/models/item/{path}.json" in entries \
                or f"assets/mekanism/blockstates/{path}.json" in entries:
            continue
        used_by = sorted(rel for rel, r in recipes.items()
                         if f'"{fid}"' in json.dumps(r)) or ["(table entry only)"]
        bad("MEKANISM", f"'{fid}' does not exist in Mekanism {mek_version} "
                        f"(no lang key, item model or blockstate in "
                        f"{os.path.basename(mek_jar)}); used by {used_by}. "
                        f"RecipeManager will drop those recipes at datapack load")

# ---------- 5b. fuel type entries (SPEC 2.1) ----------
# The shipped data/bwr/fuel_type/*.json ARE the fuel registry. They are the one
# kind of data file in this mod that carries physics rather than presentation, so
# they are checked against the field contract in the core module rather than by
# eye. The field names and the required/optional split are scraped out of
# FuelTypeSpec.java so this cannot drift away from the Java.
SPEC_JAVA = os.path.join(ROOT, "core/src/main/java/dev/bwr/core/fuel/FuelTypeSpec.java")
fuel_required, fuel_optional, fuel_fields = [], [], []
if os.path.isfile(SPEC_JAVA):
    sp = open(SPEC_JAVA, encoding="utf-8").read()
    const = dict(re.findall(r'String\s+(FIELD_[A-Z0-9_]+)\s*=\s*"([a-z0-9_]+)"', sp))
    def const_list(name):
        m = re.search(name + r'\s*=\s*List\.of\((.*?)\);', sp, re.S)
        if not m:
            return []
        return [const[c] for c in re.findall(r'\b(FIELD_[A-Z0-9_]+)\b', m.group(1)) if c in const]
    fuel_required = const_list("REQUIRED_FIELDS")
    fuel_optional = const_list("OPTIONAL_FIELDS")
    fuel_fields = fuel_required + fuel_optional
    if len(fuel_required) != 7:
        bad("FUEL", f"FuelTypeSpec declares {len(fuel_required)} required fields; "
                    f"SPEC 2.1 lists 7")
else:
    bad("FUEL", "core/.../FuelTypeSpec.java not found; cannot check fuel type entries")

fuel_entries = {k: v for k, v in jsons.items() if "/fuel_type/" in k}
for rel, fe in sorted(fuel_entries.items()):
    if not isinstance(fe, dict):
        bad("FUEL", f"{rel}: a fuel entry must be a JSON object")
        continue
    if not fuel_fields:
        continue
    for key in fuel_required:
        if key not in fe:
            bad("FUEL", f"{rel}: missing required field '{key}'")
    for key, val in fe.items():
        if key not in fuel_fields:
            bad("FUEL", f"{rel}: unknown field '{key}' (valid: {fuel_fields})")
        elif not isinstance(val, (int, float)) or isinstance(val, bool):
            bad("FUEL", f"{rel}: field '{key}' must be a number, got {val!r}")
    # The handful of ranges FuelType itself rejects, checked here too so a bad
    # shipped entry is caught by the audit and not only at datapack load.
    b = fe.get("beta")
    if isinstance(b, (int, float)) and not (0.0 < b < 1.0):
        bad("FUEL", f"{rel}: beta {b} is not in (0,1)")
    d = fe.get("doppler_coeff_per_c")
    if isinstance(d, (int, float)) and not d < 0.0:
        bad("FUEL", f"{rel}: doppler_coeff_per_c {d} must be negative")
    for key in ("k_inf_base", "prompt_lifetime_seconds", "heat_per_fission_mev"):
        v = fe.get(key)
        if isinstance(v, (int, float)) and not v > 0.0:
            bad("FUEL", f"{rel}: {key} {v} must be positive")

# ---------- 6. folder-name traps ----------
for wrong, right in [("data/bwr/recipes", "data/bwr/recipe"),
                     ("data/bwr/loot_tables", "data/bwr/loot_table"),
                     ("data/bwr/advancements", "data/bwr/advancement")]:
    if os.path.isdir(os.path.join(RES, wrong)):
        bad("FOLDER", f"'{wrong}' is the 1.20 name; 1.21 wants '{right}' (silently loads nothing)")

# ---------- 7. loot tables ----------
for rel, lt in jsons.items():
    if not rel.startswith("data/bwr/loot_table"):
        continue
    if lt.get("type") not in ("minecraft:block", None):
        bad("LOOT", f"{rel}: type is {lt.get('type')!r}")
    txt = json.dumps(lt)
    for m in re.finditer(r'"name":\s*"(bwr:[a-z0-9_/]+)"', txt):
        if m.group(1) not in valid_ids:
            bad("LOOT", f"{rel}: drops '{m.group(1)}' which is not registered")

# ---------- 8. tags ----------
tag_files = {k for k in jsons if "/tags/" in k}
def tag_exists(ref):
    ns, _, path = ref.partition(":")
    if not _:
        ns, path = "minecraft", ns
    if ns != "bwr":
        return True  # vanilla / other-mod tags assumed present
    # a bwr block tag lives at data/bwr/tags/block/<path>.json
    return f"data/bwr/tags/block/{path}.json" in tag_files

n_tag_refs = 0
for rel, tg in jsons.items():
    if "/tags/" not in rel:
        continue
    for v in tg.get("values", []):
        s = v if isinstance(v, str) else v.get("id", "")
        n_tag_refs += 1
        if s.startswith("#"):
            # a reference to another tag, not to a block
            if not tag_exists(s[1:]):
                bad("TAG", f"{rel}: references tag '{s}' which has no definition")
        elif s.startswith("bwr:") and s not in valid_ids:
            bad("TAG", f"{rel}: references block '{s}' which is not registered")

# ---------- report ----------
print("=" * 72)
print("BWR ASSET AUDIT")
print("=" * 72)
print(f"JSON files parsed          : {count_json}  ({count_json - len([p for p in problems if p[0]=='JSON-PARSE'])} ok)")
print(f"Registered blocks          : {len(blocks)}")
print(f"Registered items           : {len(all_items)}  ({len(block_items)} block items + {len(extra_items)} standalone)")
print(f"Block models               : {len([k for k in models if '/models/block/' in k])}")
print(f"Item models                : {len([k for k in models if '/models/item/' in k])}")
print(f"Textures on disk           : block={len(os.listdir(os.path.join(RES,'assets/bwr/textures/block')))} item={len(os.listdir(os.path.join(RES,'assets/bwr/textures/item')))} gui={len(os.listdir(os.path.join(RES,'assets/bwr/textures/gui')))}"
      f"  ({len(textures_on_disk)} .png total)")
print(f"Concrete texture refs      : {tex_refs} from models, {len(java_tex_refs)} from Java "
      f"({sum(len(v) for v in java_tex_refs.values())} call site(s))")
print(f"Textures referenced by none: {len(textures_on_disk - referenced_textures)}")
print(f"Recipes                    : {len(recipes)}")
print(f"Loot tables                : {len([k for k in jsons if k.startswith('data/bwr/loot_table')])}")
print(f"Lang keys                  : {len(lang)}")
print(f"Fuel type entries          : {len(fuel_entries)}  "
      f"({len(fuel_required)} required + {len(fuel_optional)} optional fields each)")
print(f"Tag files / tag refs       : {len(tag_files)} / {n_tag_refs}")
print(f"Foreign item ids in recipes: {len(foreign_ids_used)} used, "
      f"{len(FOREIGN_ITEM_IDS)} in the verified table")
if mek_jar:
    print(f"  cross-checked            : {mek_checked} mekanism: id(s) against "
          f"{os.path.basename(mek_jar)}")
else:
    print(f"  cross-checked            : NOT DONE -- Mekanism {mek_version} is not in "
          f"the Gradle cache; the table above is unverified this run")
print()
print("Per-block coverage (blockstate / block model / item model / loot / recipe):")
for k, v in missing.items():
    print(f"  {k:<11}: {len(blocks)-len(v)}/{len(blocks)}" + (f"   MISSING: {v}" if v else ""))
print()
print("Blockstate coverage vs Java property definitions:")
for b in blocks:
    pr = props.get(b, {})
    if not pr:
        continue
    total = 1
    for v in pr.values():
        total *= len(v)
    mp = [x for x in bs_multipart if x[0] == b]
    if mp:
        # A multipart block. "fully covered" used to be printed here
        # unconditionally, for a check that did not exist; say what was
        # actually verified instead.
        _, nparts, untested = mp[0]
        print(f"  {b:<28} {len(pr)} prop(s), {total} state(s), {nparts} multipart part(s)"
              + (f"  -- {len(untested)} PROPERTY(S) NEVER TESTED: {untested}" if untested
                 else "  -- every property drives a part"))
    else:
        g = [x for x in bs_variant_gaps if x[0] == b]
        print(f"  {b:<28} {len(pr)} prop(s), {total} state(s)"
              + (f"  -- {g[0][1]} UNCOVERED e.g. {g[0][2]}" if g else "  -- fully covered"))
print()
if problems:
    print(f"PROBLEMS: {len(problems)}")
    for cat, msg in problems:
        print(f"  [{cat}] {msg}")
else:
    print("PROBLEMS: 0")
sys.exit(1 if problems else 0)
