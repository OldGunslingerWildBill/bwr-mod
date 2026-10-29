#!/usr/bin/env python3
"""Writes the Mekanism tier of the crafting recipes (MEKANISM-RECIPES.md is the design and the reasons).

Run from anywhere:  python tools-generate-mekanism-recipes.py            writes (idempotent)
                    python tools-generate-mekanism-recipes.py --check    changes nothing, exits non-zero if a file is stale
                    python tools-generate-mekanism-recipes.py --table    prints the recipe table of MEKANISM-RECIPES.md
                    python tools-generate-mekanism-recipes.py --desk-table  prints the cost table of FABRICATION-DESK.md (--check also
                                                                          verifies that the table in that file is this one)

For every recipe in TABLE below this tool
  * writes data/bwr/recipe/<name>_mekanism.json: the same result, made from Mekanism materials, loaded only with
    `neoforge:mod_loaded mekanism`; and
  * adds `neoforge:conditions: [not mod_loaded mekanism]` to the vanilla recipe <name>.json (a text insertion, so the
    file keeps its own layout and nothing else in it moves).
So exactly one recipe makes each item in each install: the vanilla one without Mekanism (unchanged, same id) and the
Mekanism one with it. The fuel fabricator predates this and is the one pair named the other way round
(fuel_fabricator = Mekanism, fuel_fabricator_no_mekanism = vanilla); a test pins those two ids, so they stay.

The vanilla recipe is the source of each Mekanism variant: the variant starts as a copy (same pattern, same counts), DEFAULT_SUB
turns iron into steel and copper into bronze, and the entry's own `sub` / `one` / `pattern` / `key` say what else changes.
Every recipe in the folder must be classified: in TABLE (has a Mekanism variant), in BLOCKED (has one, held back because a test
pins the vanilla recipe; see the reason), or in EXEMPT (none, and why). --check fails on an unclassified recipe.

The other tools-export-* / tools-make-* scripts write some of the vanilla recipes. They write the vanilla form without the
condition: after running one, run this tool again (it only adds what is missing).

The 42 control-room parts (panels, trim, desks, displays, relay cabinets, the control stand, gauges, switches, lamps, annunciators,
segment displays) are not crafted in the grid: they are made at the fabrication desk (FABRICATION-DESK.md). Their recipes are
`bwr:fabrication` files (a list of ingredients with counts), still written as a pair, <name>.json (vanilla, hand-written, this tool
only adds the condition) and <name>_mekanism.json (written here from FAB_NAMES: the same list with DEFAULT_SUB and the entry's own
changes, and every steel count multiplied by STEEL_FACTOR, because the owner wants steel to cost more).

The oil route is not generated here: turbine_oil_can_from_ethene.json (the reaction chamber recipe) and
turbine_oil_drum_mekanism.json are written by hand beside it, and the drum recipe is listed in TABLE as a pair like any other.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
RECIPES = os.path.join(ROOT, "mod/src/main/resources/data/bwr/recipe")

MOD_LOADED = {"type": "neoforge:mod_loaded", "modid": "mekanism"}

# ---------------------------------------------------------------------------------------------------------------------
# The materials ladder. A spec is "namespace:item" for an item and "#namespace:tag" for a tag.
# ---------------------------------------------------------------------------------------------------------------------
STEEL = "#c:ingots/steel"
STEEL_BLOCK = "#c:storage_blocks/steel"
STEEL_NUGGET = "#c:nuggets/steel"
BRONZE = "#c:ingots/bronze"
BRONZE_BLOCK = "#c:storage_blocks/bronze"
OSMIUM = "#c:ingots/osmium"
REF_OBSIDIAN = "#c:ingots/refined_obsidian"
REF_GLOWSTONE = "#c:ingots/refined_glowstone"
C1 = "#c:circuits/basic"
C2 = "#c:circuits/advanced"
C3 = "#c:circuits/elite"
C4 = "#c:circuits/ultimate"
A1 = "#c:alloys/advanced"      # infused alloy
A2 = "#c:alloys/elite"         # reinforced alloy
A3 = "#c:alloys/ultimate"      # atomic alloy
HDPE_PELLET = "mekanism:hdpe_pellet"
HDPE_SHEET = "mekanism:hdpe_sheet"
CASING = "mekanism:steel_casing"
CAN = "bwr:turbine_oil_can"

IRON = "minecraft:iron_ingot"
RS = "minecraft:redstone"
RSB = "minecraft:redstone_block"
CMP = "minecraft:comparator"
RPT = "minecraft:repeater"
GLOWSTONE = "minecraft:glowstone"

# What every variant does to the vanilla recipe before its own changes.
DEFAULT_SUB = {
    "minecraft:iron_ingot": STEEL,
    "#c:ingots/iron": STEEL,
    "minecraft:iron_nugget": STEEL_NUGGET,
    "minecraft:iron_block": STEEL_BLOCK,
    "minecraft:copper_ingot": BRONZE,
    "minecraft:copper_block": BRONZE_BLOCK,
}

TABLE = {}


def e(name, sub=None, one=None, pattern=None, key=None, count=None):
    """sub: spec -> spec everywhere; one: spec -> spec at its first place only; pattern/key: replace the vanilla layout."""
    TABLE[name] = dict(sub=sub or {}, one=one or {}, pattern=pattern, key=key, count=count)


# --- instruments, switches, displays, recorders: steel and the circuit tiers (basic for a signal, advanced for a comparator)
for n in ("annunciator", "bar_gauge", "bar_graph", "dial_gauge", "edgewise_gauge_horizontal", "edgewise_gauge_vertical",
          "led_rod_display", "strip_chart", "synchroscope", "thumbwheel", "toggle_switch", "push_turn_switch", "joystick_switch",
          "control_switch", "rotary_selector", "setpoint_knob", "rod_select_matrix", "controller_station", "relay_cabinet",
          "relay_cabinet_plain", "cobalt_sample_detector", "panel_linker"):
    e(n, sub={RS: C1})
for n in ("area_radiation_monitor", "survey_meter", "fe_meter", "full_core_display", "graphic_display",
          "sixteen_segment_display", "building_crane_console", "refuelling_bridge_console"):
    e(n, sub={CMP: C2})
e("seven_segment_display", one={RS: C1})
e("plant_computer", sub={CMP: C3})
e("grid_tie", sub={CMP: C3})
e("paperless_recorder_dx2000", sub={RS: C2})
e("networking_wrench", sub={RS: C2})
for n in ("annunciator_control", "board_blueprint", "character_lcd", "paperless_recorder", "two_pen_recorder", "pushbutton",
          "rps_trip", "key_switch", "nameplate", "panel_key", "panel_wrench", "mimic_line", "mimic_symbol", "mimic_tape",
          "irradiation_casing"):
    e(n)
e("lamp", sub={IRON: STEEL_NUGGET})
for n in ("hps_flat_panel", "hps_high_bay", "hps_wall_pack"):
    e(n, sub={GLOWSTONE: REF_GLOWSTONE})
for n in ("lps_high_bay", "lps_wall_pack", "mercury_high_bay", "mercury_wall_pack"):
    e(n)
e("xenon_high_bay", sub={RS: C1})
e("xenon_floodlight", one={RS: C1})

# --- control-room furniture: steel, and the panel body carries the plastic and a circuit (blocked by tests, see BLOCKED)
e("control_panel_vertical", pattern=["SSS", "SLS", "PCP"],
  key={"S": STEEL, "L": "minecraft:lime_dye", "P": HDPE_SHEET, "C": C1})
for n in ("control_panel_console", "control_panel_corner_bench_chamfer", "control_panel_corner_bench_outside",
          "control_panel_corner_vertical_chamfer", "control_panel_corner_vertical_outside", "control_panel_desk",
          "control_panel_desk_wide", "control_panel_duplex", "control_panel_end_cap_round", "control_panel_header_canopy",
          "control_panel_header_vent", "control_panel_plinth_apron", "control_panel_plinth_base", "control_panel_plinth_kick",
          "control_stand", "operator_desk", "operator_desk_corner", "operator_desk_round", "supervisor_desk",
          "wall_display", "wall_display_wide"):
    e(n)

# --- fittings: pipe and tube lined with plastic or a bronze seat, valves with the control circuit that drives them
e("pressurised_tube", pattern=["SSS", " Z ", "SSS"], key={"S": STEEL, "Z": BRONZE}, count=8)
e("high_pressure_water_pipe", pattern=["SSS", "PBP", "SSS"],
  key={"S": STEEL, "P": HDPE_PELLET, "B": "minecraft:blue_dye"}, count=8)
e("turbine_control_valve", sub={RPT: A1})
e("steam_stop_valve", sub={RS: C1})
e("bypass_steam_valve", sub={CMP: C2})
e("msiv", sub={RSB: C3})
e("motor_operated_water_valve", sub={RS: C1})
e("ads_relief_valve", sub={RS: C1})
e("containment_vent_valve", sub={RS: C1})
e("containment_spray_header", sub={RS: C1})
e("core_spray_sparger")
e("rpv_bottom_drain_port")
e("rpv_water_injection_port")
e("cavity_drain_port")
e("cavity_fill_port")
e("water_discharge_port")
e("water_outlet_chute")
e("screened_water_intake")
e("demineralizer_resin", pattern=["SpS", "pBp", "SpS"], key={"S": STEEL, "p": HDPE_PELLET, "B": "minecraft:iron_bars"})

# --- the vessel and the machines built of it
e("reactor_vessel", pattern=["SSS", "SKS", "SSS"], key={"S": STEEL, "K": CASING})
e("reactor_controller", sub={CMP: C2, RSB: C3})
e("control_rod_drive", sub={RS: C1})
e("ads_controller", sub={CMP: C2, RSB: A1})
e("containment_airlock", sub={RS: C1})
e("containment_controller", sub={CMP: C3})
e("containment_data_penetration", sub={RPT: C2})
e("containment_nitrogen_supply", sub={CMP: C2})
e("containment_pass_through")
e("containment_power_penetration", sub={RSB: A2})
e("containment_steam_penetration")
e("containment_water_penetration")
e("containment_vault_door", pattern=["III", "IPI", "IRI"],
  key={"I": STEEL_BLOCK, "P": "minecraft:piston", "R": REF_OBSIDIAN})
e("containment_vault_door_4x4")
e("suppression_pool_wall")
e("suppression_pool_controller", sub={CMP: C2, RSB: A1})
e("spent_fuel_pool_wall")
e("spent_fuel_pool_controller", sub={CMP: C2, RSB: A1})
e("spent_fuel_rack")
e("new_fuel_elevator", sub={RS: C1})
e("stud_tensioner", sub={RS: C1})
for n in ("canal_gate", "canal_gate_plug", "casing_laydown_stand", "crane_anchor", "crane_runway_rail", "equipment_pad",
          "gate_laydown_stand", "head_stand", "refuelling_rail", "transfer_canal", "plug_seam_marker"):
    e(n)
e("reprocessing_plant", pattern=["RVR", "HFH", "S2S"],
  key={"R": REF_OBSIDIAN, "V": "bwr:reactor_vessel", "H": "minecraft:hopper", "F": "minecraft:blast_furnace", "S": STEEL, "2": C2})
e("isotope_separator", pattern=["SHS", "ZBZ", "S3S"],
  key={"S": STEEL, "H": "minecraft:hopper", "Z": BRONZE, "B": "minecraft:brewing_stand", "3": C3})
e("rtg", sub={RS: C1})
e("large_rtg")

# --- pumps: a steel casing, a bronze impeller seat, and the motor's control circuit
e("circulating_water_pump", sub={CMP: C2})
e("makeup_water_pump")
e("motor_feed_pump", sub={RSB: C3})
e("turbine_feed_pump")
e("hpcs_pump", sub={RSB: C3})
e("lpcs_pump", sub={RS: C2})
e("rhr_pump", sub={RS: C2})
e("rwcu_pump", sub={RS: C1})
e("slc_pump", sub={"#c:dusts/redstone": C2})
e("slc_boron_tank")
e("recirculation_pump")
e("rip_pump")
e("shaft_oil_pump", sub={RS: C1})
for n in ("rhr_heat_exchanger", "rwcu_filter_demineralizer", "rwcu_nonregenerative_heat_exchanger",
          "rwcu_regenerative_heat_exchanger", "arabelle_condenser", "condensate_storage_tank", "generator_cooling_skid",
          "main_oil_tank", "hydrogen_cylinder", "nitrogen_canister"):
    e(n)
e("mechanical_draft_tower", sub={CMP: C2})
e("natural_draft_tower", sub={CMP: C2})

# --- the turbine train: blocks of steel, a reinforced alloy shaft line, an elite control circuit
e("hp_turbine", pattern=["BrB", "SPS", "B3B"],
  key={"B": STEEL_BLOCK, "r": A2, "S": STEEL, "P": "minecraft:piston", "3": C3})
e("lp_turbine", pattern=["BrB", "SPS", "B3B"],
  key={"B": STEEL_BLOCK, "r": A2, "S": STEEL, "P": "minecraft:cauldron", "3": C3})
e("nuclear_generator", pattern=["BrB", "SPS", "B3B"],
  key={"B": STEEL_BLOCK, "r": A2, "S": STEEL, "P": "minecraft:gold_block", "3": C3})
e("hpci_turbine")
e("rcic_twl")
e("lp_blade_section")
e("lp_blade_stand")
e("lp_turbine_hood")
e("turbine_rebuild_kit")

# --- heavier steel (owner, 2026-10-28): the big machines cost noticeably more steel than the iron they replace. A steel block is 9 ingots;
# these entries put blocks where the vanilla recipe had ingots, or make fewer pieces per craft. Cheap parts (switches, small valves, the
# control rod drive, the sparger, the rack) are not touched. tools-cost-recipe.py prices any item in both installs.
e("reactor_vessel", pattern=["SSS", "SKS", "SSS"], key={"S": STEEL, "K": CASING}, count=2)
e("rpv_bottom_drain_port", pattern=["BIB", "PsP", "BIB"],
  key={"B": STEEL_BLOCK, "I": STEEL, "P": "bwr:high_pressure_water_pipe", "s": "minecraft:iron_bars"})
e("rpv_water_injection_port", pattern=["BIB", "VPV", "BIB"],
  key={"B": STEEL_BLOCK, "I": STEEL, "V": "bwr:reactor_vessel", "P": "bwr:high_pressure_water_pipe"})
e("hp_turbine", pattern=["BrB", "BPB", "B3B"], key={"B": STEEL_BLOCK, "r": A2, "P": "minecraft:piston", "3": C3})
e("lp_turbine", pattern=["BrB", "BKB", "B3B"], key={"B": STEEL_BLOCK, "r": A2, "K": CASING, "3": C3})
e("nuclear_generator", pattern=["BrB", "BPB", "B3B"], key={"B": STEEL_BLOCK, "r": A2, "P": "minecraft:gold_block", "3": C3})
e("recirculation_pump", pattern=["VBV", "PYP", "VBV"],
  key={"V": "bwr:reactor_vessel", "B": STEEL_BLOCK, "P": "minecraft:piston", "Y": BRONZE_BLOCK})
e("turbine_feed_pump", pattern=["BCB", "VPV", "TBT"],
  key={"B": STEEL_BLOCK, "C": BRONZE_BLOCK, "V": "bwr:reactor_vessel", "P": "minecraft:piston", "T": "bwr:pressurised_tube"})
e("msiv", pattern=["VPV", "BRB", "VPV"], key={"V": "bwr:reactor_vessel", "P": "minecraft:piston", "B": STEEL_BLOCK, "R": C3})
e("arabelle_condenser", pattern=["BBB", "TCT", "BBB"],
  key={"B": STEEL_BLOCK, "T": "bwr:high_pressure_water_pipe", "C": "bwr:condensate_storage_tank"})
e("condensate_storage_tank", pattern=["BGB", "GWG", "BGB"],
  key={"B": STEEL_BLOCK, "G": "minecraft:glass", "W": "minecraft:water_bucket"})
e("rhr_heat_exchanger", pattern=["BPB", "PCP", "BPB"],
  key={"B": STEEL_BLOCK, "P": "bwr:high_pressure_water_pipe", "C": BRONZE})
e("containment_pass_through", pattern=["SBS", "BCB", "SBS"], key={"S": "minecraft:smooth_stone", "B": STEEL_BLOCK, "C": "minecraft:chest"})
e("containment_steam_penetration", pattern=["SBS", "BTB", "SBS"],
  key={"S": "minecraft:smooth_stone", "B": STEEL_BLOCK, "T": "bwr:pressurised_tube"})
e("containment_water_penetration", pattern=["SBS", "BWB", "SBS"],
  key={"S": "minecraft:smooth_stone", "B": STEEL_BLOCK, "W": "bwr:high_pressure_water_pipe"})
e("suppression_pool_wall", pattern=["SBS", "ISI", "SBS"], key={"S": "minecraft:smooth_stone", "B": STEEL_BLOCK, "I": STEEL}, count=8)
e("spent_fuel_pool_wall", pattern=["GGG", "BIB", "GGG"], key={"G": "minecraft:gray_concrete", "B": STEEL_BLOCK, "I": STEEL}, count=8)
e("mechanical_draft_tower", pattern=["IPI", "ICI", "BBB"],
  key={"I": "minecraft:smooth_stone", "P": "bwr:high_pressure_water_pipe", "C": C2, "B": STEEL_BLOCK})
e("natural_draft_tower", pattern=["IPI", "ICI", "BBB"],
  key={"I": "minecraft:white_concrete", "P": "bwr:high_pressure_water_pipe", "C": C2, "B": STEEL_BLOCK})
e("crane_runway_rail", pattern=["BBB", " G ", "III"], key={"B": STEEL_BLOCK, "G": "minecraft:gray_concrete", "I": STEEL}, count=4)
e("equipment_pad", pattern=["BBB", "GGG"], key={"B": STEEL_BLOCK, "G": "minecraft:gray_concrete"})
e("head_stand", pattern=["GGG", "I I", "BBB"], key={"G": "minecraft:gray_concrete", "I": STEEL, "B": STEEL_BLOCK})
e("gate_laydown_stand", pattern=["I I", "sss", "BBB"], key={"I": STEEL, "s": "minecraft:iron_bars", "B": STEEL_BLOCK})
e("refuelling_rail", pattern=["B B", "GGG", "I I"], key={"B": STEEL_BLOCK, "G": "minecraft:gray_concrete", "I": STEEL}, count=4)
e("refuelling_bridge_console", pattern=["PPP", "ICI", "BBB"],
  key={"P": "minecraft:glass_pane", "I": STEEL, "C": C2, "B": STEEL_BLOCK})
e("stud_tensioner", pattern=["BBB", "IPI", "IRI"], key={"B": STEEL_BLOCK, "I": STEEL, "P": "minecraft:piston", "R": C1})
e("reprocessing_plant", pattern=["RVR", "HFH", "B2B"],
  key={"R": REF_OBSIDIAN, "V": "bwr:reactor_vessel", "H": "minecraft:hopper", "F": "minecraft:blast_furnace", "B": STEEL_BLOCK, "2": C2})

# --- the turbine oil: the drum is filled from cans a reaction chamber makes (written by hand, see the module doc)
e("turbine_oil_drum", pattern=[" C ", "CSC", " C "], key={"C": CAN, "S": STEEL})

# --- the oil filter: a housing of steel with a control circuit in place of the lever, and a cartridge of steel caps round a plastic element
e("oil_filter_housing", sub={"minecraft:lever": C1})
e("oil_filter_cartridge", sub={"minecraft:white_wool": HDPE_SHEET})

# --- the fabrication desk itself is crafted in the grid: a bench of steel with a crafting table, a circuit in each side of it
e("fabrication_desk", pattern=["SSS", "ETE", "S S"],
  key={"S": STEEL, "E": C1, "T": "minecraft:crafting_table"})

# ---------------------------------------------------------------------------------------------------------------------
# Made at the fabrication desk (FABRICATION-DESK.md), not in the grid: the 42 control-room parts that tests used to pin by their grid
# recipe. Their entries above (sub / one / pattern / key) are the Mekanism design; the recipe is a `bwr:fabrication` file, so
# build_fab() reads the vanilla desk recipe instead of a pattern. `ingredients` (spec -> count, before STEEL_FACTOR) replaces the
# list when a design is not a substitution of the vanilla one.
# ---------------------------------------------------------------------------------------------------------------------
STEEL_FACTOR = 2   # every steel count of a desk recipe is multiplied by this: the owner wants steel to cost more
FAB_NAMES = (
    # Panels and trim
    "control_panel_vertical", "control_panel_console", "control_panel_corner_bench_chamfer", "control_panel_corner_bench_outside",
    "control_panel_corner_vertical_chamfer", "control_panel_corner_vertical_outside", "control_panel_duplex",
    "control_panel_end_cap_round", "control_panel_header_canopy", "control_panel_header_vent", "control_panel_plinth_apron",
    "control_panel_plinth_base", "control_panel_plinth_kick",
    # Desks and furniture
    "control_panel_desk", "control_panel_desk_wide", "control_stand", "operator_desk", "operator_desk_corner", "operator_desk_round",
    "supervisor_desk", "relay_cabinet", "relay_cabinet_plain",
    # Switches
    "control_switch", "key_switch", "rotary_selector", "setpoint_knob", "toggle_switch", "thumbwheel", "pushbutton", "rps_trip",
    "push_turn_switch",
    # Gauges and meters
    "dial_gauge", "edgewise_gauge_horizontal", "edgewise_gauge_vertical", "bar_gauge",
    # Lamps and annunciators
    "lamp", "annunciator",
    # Displays
    "seven_segment_display", "sixteen_segment_display", "bar_graph", "wall_display", "wall_display_wide")
FAB = {n: TABLE.pop(n) for n in FAB_NAMES}
# the vertical panel's design is a different list of materials, not a substitution: 5 steel, 2 sheets of plastic, a circuit and the dye
FAB["control_panel_vertical"]["ingredients"] = {STEEL: 5, "minecraft:lime_dye": 1, HDPE_SHEET: 2, C1: 1}

# ---------------------------------------------------------------------------------------------------------------------
# Held back: the Mekanism variant is designed (its entry stays in TABLE's form, moved to HELD below) but is not written, because an
# existing test loads the recipe by id or pins its iron. `--include-blocked` writes them anyway. Each reason names the test and the
# assertion. Nothing is held back now: the eight tools, the reprocessing plant and the large RTG were released on 2026-10-28 (the tests
# that pinned them look up the `_mekanism` recipe with Mekanism loaded, and only that), and the 42 control-room parts that the other
# tests pinned moved to the fabrication desk (FAB_NAMES above, FABRICATION-DESK.md).
# ---------------------------------------------------------------------------------------------------------------------
BLOCKED = {}

# the designs of the held-back recipes: they stay in TABLE's form until the owner decides, then they are written like the rest
HELD = {n: TABLE.pop(n) for n in BLOCKED}

# ---------------------------------------------------------------------------------------------------------------------
# No Mekanism variant, and why. The reason is the documentation of the decision (MEKANISM-RECIPES.md quotes the groups).
# ---------------------------------------------------------------------------------------------------------------------
EXEMPT = {}


def _exempt(names, reason):
    for n in names:
        EXEMPT[n] = reason


_exempt(["fuel_assembly", "fuel_fabricator", "fuel_fabricator_no_mekanism", "depleted_uranium_assembly",
         "shield_plug_concrete", "heavily_reinforced_shield_plug_concrete", "ultra_reinforced_shield_plug_concrete"],
        "already built on Mekanism steel, uranium or netherite (a pair of recipes exists, or the recipe has no vanilla form to replace)")
_exempt(["tritium_sample_oxidizing"], "a Mekanism chemical recipe, not a crafting recipe")
_exempt(["guide_book"], "a book and a nameplate; the nameplate carries the steel")
_exempt(["control_panel_back", "control_panel_benchboard", "control_panel_corner_bench_inside",
         "control_panel_corner_vertical_inside", "control_panel_end_cap_bench", "control_panel_end_cap_console",
         "control_panel_end_cap_flat", "control_panel_header_strip", "mimic_band_black", "mimic_band_cream",
         "jet_pump", "rpv_steam_outlet", "turbine_steam_outlet", "recirculation_inlet", "recirculation_outlet",
         "spent_fuel_pool_cooling_in", "spent_fuel_pool_cooling_out", "spent_fuel_pool_makeup_port",
         "suppression_pool_drain", "suppression_pool_return", "suppression_pool_suction", "suppression_pool_steam_inlet"],
        "made only of parts that carry the Mekanism price (panel bodies, vessels, pool walls, pipes) plus dye, glass or a hopper; "
        "a price of its own would count the same steel twice")
_exempt(["cobalt_charge", "borate_charge", "lithium_charge", "doped_silicon_redstone", "ferrocerium_flint_and_steel",
         "americium_beryllium_source", "americium_beryllium_source_from_capsule", "americium_beryllium_source_from_radium",
         "antimony_beryllium_source", "bismuth_target", "boron_absorber", "californium_source", "cobalt_target",
         "hafnium_absorber", "neptunium_target", "radium_target", "silicon_target", "thorium_target", "thulium_target",
         "tritium_target", "uranium_target", "tritium_sample_light"],
        "an isotope stand-in, target or source: priced by the isotope and by the irradiation casing it is made with, not by a machine")
_exempt(["damaged_lp_blade_section_scrap", "wrecked_lp_turbine_hood_scrap"],
        "scrap, not a build: it returns iron, and a steel return would turn the repair loop into a steel mine")

_STORAGE_FORMS = ("block", "ingot_from_block", "ingot_from_capsule", "ingot_from_sample", "nugget", "ingot",
                  "from_block", "ingot_from_blasting", "ingot_from_smelting")
_STORAGE_SPECIES = ("actinium_227", "americium_241", "caesium_137", "cobalt_60", "lead", "plutonium_238", "plutonium",
                    "polonium_210", "raw_thorium", "raw_uranium", "strontium_90", "thorium", "thulium_170", "uranium")


def storage_reason(name):
    """True for the one-to-one conversions between a nugget, an ingot and a block (and smelting an ore into an ingot)."""
    for species in _STORAGE_SPECIES:
        for form in _STORAGE_FORMS:
            if name == species + "_" + form or (form == "from_block" and name == species + "_from_block"):
                return True
    return False


STORAGE_REASON = ("a one-to-one conversion between nugget, ingot and block (or smelting an ore): it makes nothing new, so it "
                  "costs nothing and has no Mekanism form")

# ---------------------------------------------------------------------------------------------------------------------

TEST = os.path.join(ROOT, "mod/src/main/java/dev/bwr/mod/devtest/MekanismRecipeTierTests.java")
BEGIN = "    // BEGIN GENERATED EXEMPT LIST"
END = "    // END GENERATED EXEMPT LIST"


def exempt_groups():
    """[(reason, [recipe names])] for everything without a Mekanism variant in the vanilla recipe folder, blocked ones included."""
    groups = {}
    for f in sorted(os.listdir(RECIPES)):
        n = f[:-5]
        if not f.endswith(".json") or n.endswith("_mekanism") or n in TABLE or n in FAB:
            continue
        if n in BLOCKED:
            reason = "HELD BACK, an existing test pins the vanilla recipe: " + BLOCKED[n]
        elif n in EXEMPT:
            reason = EXEMPT[n]
        elif storage_reason(n):
            reason = STORAGE_REASON
        else:
            continue
        groups.setdefault(reason, []).append(n)
    return list(groups.items())


def java_block():
    lines = [BEGIN + " (tools-generate-mekanism-recipes.py writes this block; the reasons are in that tool and in MEKANISM-RECIPES.md)",
             "    /** Crafting recipes (by id path) that have no Mekanism variant, or whose variant is held back. */",
             "    private static final Set<String> EXEMPT = Set.of("]
    groups = exempt_groups()
    for gi, (reason, names) in enumerate(groups):
        lines.append("            // " + reason[:150])
        row = "            "
        for ni, n in enumerate(names):
            last = gi == len(groups) - 1 and ni == len(names) - 1
            piece = '"' + n + '"' + (")" + ";" if last else ", ")
            if len(row) + len(piece) > 130:
                lines.append(row.rstrip())
                row = "            "
            row += piece
        lines.append(row.rstrip())
    lines.append(END)
    return "\n".join(lines) + "\n"


def spec_of(ing):
    if isinstance(ing, dict):
        if "tag" in ing:
            return "#" + ing["tag"]
        return ing["item"]
    raise ValueError("unsupported ingredient " + json.dumps(ing))


def ing_of(spec):
    return {"tag": spec[1:]} if spec.startswith("#") else {"item": spec}


def load(name):
    with open(os.path.join(RECIPES, name + ".json"), encoding="utf-8", newline="") as f:
        text = f.read()
    r = json.loads(text)
    r.pop("neoforge:conditions", None)
    return r, text


def build(name, entry):
    v, _ = load(name)
    kind = v["type"]
    sub = dict(DEFAULT_SUB)
    sub.update(entry["sub"])
    out = {"type": kind, "neoforge:conditions": [MOD_LOADED]}
    if "category" in v:
        out["category"] = v["category"]
    if kind == "minecraft:crafting_shaped":
        pattern = list(entry["pattern"] or v["pattern"])
        if entry["key"] is not None:
            key = dict(entry["key"])
        else:
            key = {k: spec_of(i) for k, i in v["key"].items()}
            for src, new in entry["one"].items():
                letter = next(c for c in "123456789" if c not in key)
                done = False
                for ri, row in enumerate(pattern):
                    for ci, cell in enumerate(row):
                        if not done and cell != " " and key[cell] == src:
                            pattern[ri] = row[:ci] + letter + row[ci + 1:]
                            key[letter] = new
                            done = True
                if not done:
                    raise SystemExit(f"{name}: no {src} in the pattern for 'one'")
            key = {k: sub.get(s, s) for k, s in key.items()}
        used = {c for row in pattern for c in row if c != " "}
        key = {k: s for k, s in key.items() if k in used}
        if used - set(key):
            raise SystemExit(f"{name}: pattern letters without a key: {sorted(used - set(key))}")
        out["key"] = {k: ing_of(s) for k, s in key.items()}
        out["pattern"] = pattern
    elif kind == "minecraft:crafting_shapeless":
        specs = [spec_of(i) for i in v["ingredients"]]
        for src, new in entry["one"].items():
            specs[specs.index(src)] = new
        out["ingredients"] = [ing_of(sub.get(s, s)) for s in specs]
    else:
        raise SystemExit(f"{name}: only shaped and shapeless crafting recipes get a Mekanism variant, not {kind}")
    res = dict(v["result"])
    if entry["count"] is not None:
        res["count"] = entry["count"]
    out["result"] = {"count": res.get("count", 1), "id": res["id"]}
    return out


def build_fab(name, entry):
    """The Mekanism variant of a desk recipe: the vanilla recipe's list, substituted, with every steel count multiplied."""
    v, _ = load(name)
    if v["type"] != "bwr:fabrication":
        raise SystemExit(f"{name}: expected a bwr:fabrication vanilla recipe, not {v['type']}")
    sub = dict(DEFAULT_SUB)
    sub.update(entry["sub"])
    if entry.get("ingredients") is not None:
        rows = [[spec, count] for spec, count in entry["ingredients"].items()]
    else:
        rows = [[spec_of(i), i.get("count", 1)] for i in v["ingredients"]]
        for src, new in entry["one"].items():
            for i, row in enumerate(rows):
                if row[0] == src:
                    row[1] -= 1
                    at = i + 1
                    if row[1] == 0:
                        del rows[i]
                        at = i
                    rows.insert(at, [new, 1])
                    break
            else:
                raise SystemExit(f"{name}: no {src} in the desk recipe for 'one'")
        rows = [[sub.get(spec, spec), count] for spec, count in rows]
    merged = []
    for spec, count in rows:
        if spec in (STEEL, STEEL_NUGGET, STEEL_BLOCK):
            count *= STEEL_FACTOR
        for row in merged:
            if row[0] == spec:
                row[1] += count
                break
        else:
            merged.append([spec, count])
    # steel first, then the rest in the vanilla order
    merged.sort(key=lambda row: 0 if row[0] in (STEEL, STEEL_NUGGET, STEEL_BLOCK) else 1)
    out = {"type": v["type"], "neoforge:conditions": [MOD_LOADED], "category": v["category"],
           "ingredients": [dict(ing_of(spec), count=count) for spec, count in merged],
           "result": {"count": v["result"].get("count", 1), "id": v["result"]["id"]}}
    return out


DESK_DOC = os.path.join(ROOT, "FABRICATION-DESK.md")
DESK_BEGIN = "<!-- BEGIN DESK TABLE (tools-generate-mekanism-recipes.py --desk-table writes this) -->"
DESK_END = "<!-- END DESK TABLE -->"
_TAG_LABELS = {"c:ingots/steel": "steel ingot", "c:nuggets/steel": "steel nugget", "c:storage_blocks/steel": "steel block",
               "c:circuits/basic": "basic circuit", "c:circuits/advanced": "advanced circuit", "mekanism:hdpe_sheet": "HDPE sheet"}
_TAB_LABELS = {"panels": "Panels and trim", "furniture": "Desks and furniture", "switches": "Switches",
               "gauges": "Gauges and meters", "lamps": "Lamps and annunciators", "displays": "Displays"}


def _label(ing):
    spec = spec_of(ing)
    if spec.lstrip("#") in _TAG_LABELS:
        return _TAG_LABELS[spec.lstrip("#")]
    return spec.split(":", 1)[1].replace("_", " ")


def _cost(ings):
    return ", ".join(f"{i.get('count', 1)} {_label(i)}" for i in ings)


def desk_table():
    """The cost table of FABRICATION-DESK.md: every desk part by tab, what it makes, and its materials in both installs."""
    rows = {}
    for name, entry in FAB.items():
        v, _ = load(name)
        m = build_fab(name, entry)
        rows.setdefault(v["category"], []).append((name, v["result"].get("count", 1), _cost(v["ingredients"]), _cost(m["ingredients"])))
    lines = []
    for cat in _TAB_LABELS:
        lines.append(f"**{_TAB_LABELS[cat]}**")
        lines.append("")
        lines.append("| Part | Makes | Without Mekanism | With Mekanism |")
        lines.append("| --- | --- | --- | --- |")
        for name, count, plain, mek in sorted(rows.get(cat, [])):
            lines.append(f"| `{name}` | {count} | {plain} | {mek} |")
        lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def render(obj):
    return json.dumps(obj, indent=2) + "\n"


def gate_vanilla(text):
    """The vanilla recipe's text with `neoforge:conditions: [not mod_loaded mekanism]` after its type; unchanged if it has it."""
    if '"neoforge:conditions"' in text:
        return text
    m = re.match(r'(\{\s*"type"\s*:\s*"[^"]+"\s*,)', text)
    if not m:
        raise SystemExit("cannot find the type of a vanilla recipe to put the condition after")
    head = m.group(1)
    if text.startswith("{\n"):
        block = ('\n  "neoforge:conditions": [\n    {\n      "type": "neoforge:not",\n      "value": {\n'
                 '        "type": "neoforge:mod_loaded",\n        "modid": "mekanism"\n      }\n    }\n  ],')
        # the next token starts on its own line already: head ends at the comma, the newline after it stays
        return head + block + text[len(head):]
    if '"type": ' in head:
        block = (' "neoforge:conditions": [{"type": "neoforge:not", "value": {"type": "neoforge:mod_loaded", '
                 '"modid": "mekanism"}}],')
        return head + block + text[len(head):]
    block = ('"neoforge:conditions":[{"type":"neoforge:not","value":{"type":"neoforge:mod_loaded","modid":"mekanism"}}],')
    return head + block + text[len(head):]


def classified():
    """name -> 'table' | 'blocked' | 'exempt' for every recipe file; unclassified ones are returned separately."""
    result, unknown = {}, []
    for f in sorted(os.listdir(RECIPES)):
        if not f.endswith(".json"):
            continue
        n = f[:-5]
        if n.endswith("_mekanism"):
            continue
        if n in TABLE:
            result[n] = "table"
        elif n in FAB:
            result[n] = "fab"
        elif n in BLOCKED:
            result[n] = "blocked"
        elif n in EXEMPT or storage_reason(n):
            result[n] = "exempt"
        elif n in ("turbine_oil_can_from_ethene",):
            result[n] = "exempt"
        else:
            unknown.append(n)
    return result, unknown


def selected(include_blocked):
    entries = dict(TABLE)
    if include_blocked:
        entries.update(HELD)
    return entries


def main(argv):
    check = "--check" in argv
    include_blocked = "--include-blocked" in argv
    problems = []
    cls, unknown = classified()
    for n in unknown:
        problems.append(f"{n}.json is not classified: add it to TABLE (a Mekanism variant), BLOCKED or EXEMPT with a reason")
    for n in list(TABLE) + list(HELD) + list(FAB):
        if not os.path.isfile(os.path.join(RECIPES, n + ".json")):
            problems.append(f"{n}: listed in TABLE/BLOCKED but there is no vanilla recipe {n}.json")
    both = (set(TABLE) | set(FAB)) & set(BLOCKED) | (set(TABLE) & set(FAB))
    for n in sorted(both):
        problems.append(f"{n}: in both TABLE and BLOCKED")
    for n in EXEMPT:
        if n in TABLE or n in BLOCKED:
            problems.append(f"{n}: exempt and also given a variant")
    writes = {}
    if "--desk-table" in argv:
        print(desk_table(), end="")
        return 0
    if "--table" in argv:
        for n, entry in sorted(FAB.items()):
            v = build_fab(n, entry)
            print(n, "x%d" % v["result"]["count"], json.dumps([((i.get("item") or "#" + i["tag"]), i["count"]) for i in v["ingredients"]]))
        for n, entry in sorted(selected(True).items()):
            v = build(n, entry)
            print(n, json.dumps(v.get("pattern")), json.dumps({k: (i.get("item") or "#" + i["tag"]) for k, i in v.get("key", {}).items()})
                  if "key" in v else json.dumps([(i.get("item") or "#" + i["tag"]) for i in v["ingredients"]]))
        return 0
    for n, entry in sorted(FAB.items()):
        if problems:
            break
        writes[os.path.join(RECIPES, n + "_mekanism.json")] = render(build_fab(n, entry))
        with open(os.path.join(RECIPES, n + ".json"), encoding="utf-8", newline="") as f:
            writes[os.path.join(RECIPES, n + ".json")] = gate_vanilla(f.read())
    for n, entry in sorted(selected(include_blocked).items()):
        if problems:
            break
        variant = os.path.join(RECIPES, n + "_mekanism.json")
        writes[variant] = render(build(n, entry))
        _, text = load(n)
        with open(os.path.join(RECIPES, n + ".json"), encoding="utf-8", newline="") as f:
            raw = f.read()
        writes[os.path.join(RECIPES, n + ".json")] = gate_vanilla(raw)
    if not include_blocked:
        # a blocked item's variant must not exist
        for n in BLOCKED:
            stale = os.path.join(RECIPES, n + "_mekanism.json")
            if os.path.exists(stale):
                problems.append(f"{n}: blocked but {n}_mekanism.json exists")
    if check and os.path.exists(DESK_DOC):
        with open(DESK_DOC, encoding="utf-8", newline="") as f:
            doc = f.read()
        if DESK_BEGIN not in doc or DESK_END not in doc:
            problems.append("FABRICATION-DESK.md has no desk table markers")
        elif doc[doc.index(DESK_BEGIN) + len(DESK_BEGIN):doc.index(DESK_END)].strip("\n") != desk_table().strip("\n"):
            problems.append("stale: the cost table in FABRICATION-DESK.md (run python tools-generate-mekanism-recipes.py --desk-table)")
    with open(TEST, encoding="utf-8", newline="") as f:
        java = f.read()
    start = java.index(BEGIN)
    stop = java.index(END) + len(END) + 1
    writes[TEST] = java[:start] + java_block() + java[stop:]
    for path, data in writes.items():
        have = open(path, encoding="utf-8", newline="").read() if os.path.exists(path) else None
        if have != data:
            if check:
                problems.append(f"stale: {os.path.relpath(path, ROOT)} (run python tools-generate-mekanism-recipes.py)")
            else:
                with open(path, "w", encoding="utf-8", newline="") as f:
                    f.write(data)
    for p in problems:
        print("PROBLEM:", p)
    if not problems:
        print(f"{len(selected(include_blocked)) + len(FAB)} Mekanism variants ({len(FAB)} of them desk recipes) {'verified' if check else 'written'}; "
              f"{len(BLOCKED)} blocked, {sum(1 for v in cls.values() if v == 'exempt')} exempt")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
