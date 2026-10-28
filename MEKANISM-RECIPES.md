# Mekanism recipe tier

With Mekanism installed, the plant's crafting recipes ask for Mekanism's materials instead of iron, redstone and comparators, and
turbine oil comes out of a Mekanism machine. Without Mekanism nothing changes: every recipe is the one it always was, with the same id.
Mekanism stays **optional**: it is never bundled, the mod loads and plays without it, and nothing in Java touches it for this
(it is all data and conditions, apart from one extra item, the Turbine Oil Can).

## The rule

Every item with a Mekanism variant has two recipe files in `data/bwr/recipe`:

| File | Loaded | Is |
| --- | --- | --- |
| `<name>.json` | only **without** Mekanism (`neoforge:not` of `neoforge:mod_loaded mekanism`) | the vanilla recipe, unchanged but for the condition; same id as before |
| `<name>_mekanism.json` | only **with** Mekanism (`neoforge:mod_loaded mekanism`) | the Mekanism recipe, same result |

So exactly one recipe makes each item in each install, and the ids a world, an advancement or a recipe viewer already knows are the
ids of the vanilla recipes. The fuel fabricator is the one pair named the other way round (`fuel_fabricator` is the Mekanism recipe,
`fuel_fabricator_no_mekanism` the vanilla one): it came first, and `FuelFabricatorItemRouteTests` pins both ids. Its Mekanism recipe
(steel, advanced control circuit, infused alloy, steel casing, a vessel) already is the tier and is left as it is.

The variants are **written by a tool**, `python tools-generate-mekanism-recipes.py` (`--check` verifies, `--table` prints what it would
write). Its table is the single list of what each recipe becomes: it starts from the vanilla recipe (same pattern and counts), turns
iron into steel and copper into bronze, and applies the entry's own substitutions. It also adds the condition to the vanilla file by
inserting one line of text, so the file keeps its layout. Every recipe in the folder must be classified in the tool: a Mekanism
variant, held back (below) or exempt with a reason, and `--check` fails on one that is not. **The `tools-export-*` scripts that go through
`tools-export-modern.py`, `tools-make-turbine-aux-assets.py` and `tools-make-turbine-hall-assets.py` write the condition themselves
(their `--check` stays green); the other `tools-make-*` / `tools-export-*` scripts that write a recipe (crane, refuelling bridge, shield
plug, spent fuel pool, breeding, nanofilter, lamps) do not: after running one, run this tool again** (it only adds what is missing). `MekanismRecipeTierTests` fails if a vanilla recipe is left live with Mekanism.

**The 42 control-room parts made at the fabrication desk** (panels and trim, desks, relay cabinets, the control stand, wall displays,
gauges, switches, lamp, pushbutton, RPS trip, annunciator and segment displays) follow the same rule, but their recipes are
`bwr:fabrication` files (a list of ingredients with counts, FABRICATION-DESK.md) instead of grid recipes: the same two files, the same
ids, the same conditions, and the generator writes the Mekanism file from the same table, **with every steel count multiplied by
`STEEL_FACTOR` (2)** because the owner wants steel to cost more. Their costs in both installs are the table in FABRICATION-DESK.md.
The desk itself (`fabrication_desk`) is a grid recipe in a pair like any other.

## The materials ladder

Plant parts are priced by what the real part is. Steel is the floor; each step up is a Mekanism machine more.

| Tier | Material (tag) | Where it comes from in Mekanism | Used for |
| --- | --- | --- | --- |
| 0 | Steel ingot, nugget, block (`c:ingots/steel`, `c:nuggets/steel`, `c:storage_blocks/steel`) | enrichment chamber (iron to enriched iron) + metallurgic infuser (carbon) to steel dust, smelted | every plate, frame, casing, pipe and pressure part that was iron |
| 0 | Bronze ingot, block (`c:ingots/bronze`, `c:storage_blocks/bronze`) | 3 copper ingots + tin infusing, 4 bronze | what was copper: impeller seats, windings, heat-exchanger tubes, RTG thermocouples |
| 1 | Basic control circuit (`c:circuits/basic`) | osmium ingot + 20 mB redstone infusing | what was a redstone dust: the signal path of every switch, gauge and small valve |
| 2 | Advanced control circuit (`c:circuits/advanced`), infused alloy (`c:alloys/advanced`) | basic circuit + 2 infused alloys (alloy: copper + redstone infusing) | what was a comparator: measuring and deciding parts, displays, big valves |
| 3 | Elite control circuit (`c:circuits/elite`), reinforced alloy (`c:alloys/elite`) | advanced circuit + 2 reinforced alloys (alloy: infused alloy + diamond infusing) | what was a redstone block in a machine: the turbine train, big pumps, the reactor controller, the MSIV, the containment controller |
| plastic | HDPE pellet, sheet (`mekanism:hdpe_pellet`, `mekanism:hdpe_sheet`) | reaction chamber: substrate + ethene + oxygen; 3 pellets to a sheet in an enrichment chamber | panel bodies, the high-pressure pipe's lining, the resin beads |
| casing | Steel casing (`mekanism:steel_casing`) | 4 steel, 4 glass, 1 osmium | the heart of a reactor vessel |
| armour | Refined obsidian ingot (`c:ingots/refined_obsidian`) | refined obsidian dust + osmium, compressed | vault doors, the reprocessing plant |
| light | Refined glowstone ingot (`c:ingots/refined_glowstone`) | glowstone dust + osmium, compressed | the high-pressure sodium lamps (they were glowstone) |

**Not used on purpose.** Elite is the top of the ladder: atomic alloy, the ultimate control circuit, the tier installers and every
Mekanism machine and cube are not ingredients (a recipe that needs a machine in the grid is a tech-tree puzzle, not a price).
Nothing here needs **Mekanism Generators**, a separate optional jar: every id and tag below is in the base Mekanism jar, which
`tools-audit-assets.py` opens to confirm it (and every tag too, and the chemical and fluid of the oil recipe). Tritium processing was
already the one place that wants Generators and is untouched.

Ingredients are `c:` tags wherever one exists (any steel works), hard `mekanism:` ids only where there is none (the casing, the
plastic). Lead stays `bwr:lead_ingot` where a recipe names lead (it is tagged `c:ingots/lead` and a mod that adds lead fits too).

## What each group becomes

**The rows give the design of every recipe, the 10 held back ones included (see Held back: those are still vanilla in both installs until the owner decides). The 42 parts of the panel and furniture, instrument, display, switch and lamp rows that the fabrication desk makes are written, with every steel count doubled (FABRICATION-DESK.md has their costs).** Counts are those of the vanilla recipe unless stated; S is a steel ingot, B a steel block, N a steel nugget, Z bronze, 1/2/3 the basic,
advanced and elite circuit, a/r the infused and reinforced alloy.

| Group | Recipes | Mekanism ingredients | Why |
| --- | --- | --- | --- |
| Vessel | `reactor_vessel` (4) | 8 S around a steel casing | heavy steel forgings with a machined core; every vessel block costs about 3 steel and a quarter osmium |
| Steam and water line | `pressurised_tube` (8), `high_pressure_water_pipe` (8) | tube: 6 S + 1 Z; pipe: 6 S + 2 HDPE pellets + dye | a bronze seat in the steam line, a polymer lining in the water pipe |
| Ports and plumbing | `rpv_bottom_drain_port`, `rpv_water_injection_port`, `cavity_drain_port`, `cavity_fill_port`, `water_discharge_port`, `water_outlet_chute`, `screened_water_intake`, `core_spray_sparger`, `main_oil_tank`, `condensate_storage_tank`, `slc_boron_tank`, `hydrogen_cylinder`, `nitrogen_canister` | S (B for the intake and the SLC tank, N for the sparger) in place of iron | tanks, nozzles and flanges are plate and forging; nothing in them thinks |
| Resin | `demineralizer_resin` | 4 S, 4 HDPE pellets, iron bars | the bed is polymer beads in a steel cage |
| Valves | `steam_stop_valve` (1), `turbine_control_valve` (a), `bypass_steam_valve` (2), `msiv` (3), `motor_operated_water_valve` (1 + Z), `ads_relief_valve` (1), `containment_vent_valve` (1), `containment_spray_header` (1) | S and the circuit or alloy named: a stop valve takes a signal, a control valve a servo, a bypass a decision, an MSIV a protection-grade circuit | the circuit tier is the intelligence of the valve |
| Pumps | `recirculation_pump`, `rip_pump` (Z blocks), `circulating_water_pump` (2), `makeup_water_pump`, `motor_feed_pump` (3), `turbine_feed_pump` (Z block), `hpcs_pump` (3), `lpcs_pump` (2), `rhr_pump` (2), `rwcu_pump` (1 + Z), `slc_pump` (2), `shaft_oil_pump` (1 + Z) | steel blocks for the casing, a bronze impeller seat, the motor's control circuit | pump = casing + impeller + drive; the big feed pumps are the ones with an elite circuit |
| Heat exchange | `rhr_heat_exchanger`, `rwcu_filter_demineralizer`, `rwcu_nonregenerative_heat_exchanger`, `rwcu_regenerative_heat_exchanger`, `arabelle_condenser` (B), `generator_cooling_skid`, `mechanical_draft_tower` (2), `natural_draft_tower` (2) | S and Z tubes; the towers a circuit | bronze tube bundles; the towers have a control |
| Turbine train | `hp_turbine`, `lp_turbine`, `nuclear_generator` | 4 B, 4 S, 1 r, 1 elite circuit, and the piston / cauldron / gold block it had | the shaft line is reinforced alloy and the governor an elite circuit; this is the most expensive thing in the plant |
| Turbine hall | `hpci_turbine`, `rcic_twl`, `lp_blade_section`, `lp_blade_stand`, `lp_turbine_hood`, `turbine_rebuild_kit` | B and S (and Z in the kit) in place of iron and copper | forgings |
| Reactor controls | `reactor_controller` (2 + 3), `control_rod_drive` (1 for four drives) | circuits in place of comparator and redstone block | the controller is the brain of the plant |
| Containment | `containment_airlock` (1), `containment_controller` (3), `containment_data_penetration` (2), `containment_nitrogen_supply` (2), `containment_pass_through`, `containment_power_penetration` (r), `containment_steam_penetration`, `containment_water_penetration`, `containment_vault_door` (B and a refined obsidian ingot), `containment_vault_door_4x4`, `ads_controller` (2 + a) | steel blocks for doors, circuits and alloys for the control, bronze for the power conductor | blast armour for the door, a conductor for the power penetration, logic for the controller |
| Pools | `suppression_pool_wall`, `spent_fuel_pool_wall`, `spent_fuel_rack`, `suppression_pool_controller` (2 + a), `spent_fuel_pool_controller` (2 + a), `new_fuel_elevator` (1) | S in place of iron; circuits and an infused alloy for the controllers | stainless liner and racks; the control is a small computer |
| Fuel handling, crane | `building_crane_console`, `refuelling_bridge_console` (2), `canal_gate`, `canal_gate_plug`, `casing_laydown_stand`, `gate_laydown_stand`, `head_stand`, `equipment_pad`, `crane_anchor`, `crane_runway_rail`, `refuelling_rail`, `transfer_canal`, `stud_tensioner` (1), `plug_seam_marker` | S (B for the stands) | a crane is rail and plate; its console has a computer |
| Isotopes, radiation | `rtg` (1 + Z), `large_rtg`, `isotope_separator` (3 + Z, a hopper), `reprocessing_plant` (refined obsidian, 2), `irradiation_casing`, `survey_meter` (2 + Z), `area_radiation_monitor` (2 + Z) | S, Z thermocouples, circuits for the readout, refined obsidian shielding for the plant | the reprocessing plant is a hot cell: obsidian walls |
| Instruments | `bar_gauge`, `bar_graph`, `dial_gauge`, `edgewise_gauge_horizontal`, `edgewise_gauge_vertical`, `led_rod_display`, `strip_chart`, `synchroscope`, `annunciator` (1) | S in place of iron, a basic circuit in place of the redstone | a gauge is a case and a signal path |
| Displays, recorders | `character_lcd`, `seven_segment_display` (1 + redstone), `sixteen_segment_display` (2), `graphic_display` (2), `full_core_display` (2), `fe_meter` (2), `plant_computer` (3), `grid_tie` (3), `paperless_recorder`, `paperless_recorder_dx2000` (2), `two_pen_recorder` | S, and the circuit tier of what they show | a computer takes the elite circuit; a segment display does not |
| Switches, small parts | `pushbutton`, `rps_trip`, `annunciator_control`, `key_switch`, `toggle_switch`, `control_switch`, `push_turn_switch`, `joystick_switch`, `rotary_selector`, `setpoint_knob`, `thumbwheel`, `rod_select_matrix`, `controller_station`, `relay_cabinet`, `relay_cabinet_plain`, `cobalt_sample_detector`, `panel_linker` (1), `networking_wrench` (2 + Z), `panel_key`, `panel_wrench`, `nameplate`, `board_blueprint`, `mimic_line`, `mimic_symbol`, `mimic_tape` | S or N, and a basic circuit where it had a redstone | cheap things stay cheap: a nameplate is one steel ingot for four |
| Lamps | `lamp` (N), `hps_flat_panel`, `hps_high_bay`, `hps_wall_pack` (refined glowstone), `lps_*`, `mercury_*` (S), `xenon_floodlight`, `xenon_high_bay` (1) | S; refined glowstone for the sodium lamps; a basic circuit for the xenon ballast | a lamp is a steel nugget; a decoration never costs a circuit |
| Panels and furniture | `control_panel_vertical` (4), `control_panel_console`, the desks, `operator_desk*`, `supervisor_desk`, `control_stand`, `wall_display*`, plinths, headers, corners, `relay_cabinet*` | the vertical panel: 5 S + 2 HDPE sheets + 1 basic circuit (+ the dye) before the doubling, 10 S written, so a panel is 2.5 steel, half a sheet and a quarter circuit; the rest S and N in place of iron (doubled too) | the body is a circuit card in a plastic housing; the furniture is steel trim on that |
| Turbine oil | `turbine_oil_drum` | 4 Turbine Oil Cans around 1 S (below) | |

The vanilla column is every recipe file without `_mekanism`: it is not repeated here.

## Turbine oil with Mekanism

Mekanism has no crude oil, so the oil is a **synthetic**, made the way real synthetic lube base stock is: from ethene, hydrotreated
with hydrogen (polyalphaolefin oils are oligomerised ethene). The chain, every step a Mekanism machine:

1. **Bio fuel.** Crops, saplings, bread and the like in a Crusher (Mekanism's `crushing/biofuel/*`: an apple is 2, bread 4).
2. **Hydrogen.** Water in an Electrolytic Separator (`separator/water`: 2 mB water to 2 mB hydrogen and 1 mB oxygen).
3. **Ethene and substrate.** A Pressurized Reaction Chamber, Mekanism's own recipe `reaction/substrate/water_hydrogen`:
   2 bio fuel, 10 mB water and 100 mB hydrogen make **1 substrate and 100 mB of ethene** (100 ticks).
4. **Liquid ethene.** The ethene leaves the chamber as a gas; a Rotary Condensentrator (`rotary/ethene`) turns it into the fluid
   the next recipe takes.
5. **Oil cans**, this mod's reaction chamber recipe `turbine_oil_can_from_ethene` (type `mekanism:reaction`): **1 substrate +
   200 mB liquid ethene + 100 mB hydrogen make 4 Turbine Oil Cans** (200 ticks, 1,000 FE on top of the chamber's draw). A can is 250 L
   of oil.
6. **The drum.** Crafting: **4 cans around a steel ingot** (the vanilla plus layout, with cans for the coal and steel for the iron)
   make a Turbine Oil Drum, 1,000 L. A Main Oil Tank is poured from drums as before.

One drum is four runs of step 5: 4 substrate, 800 mB ethene and 400 mB hydrogen, and one steel ingot. The ethene is eight runs of
step 3, which also make **8 substrate**, so the chain needs none from outside and leaves 4 spare (they go to plastic). Counted in raw
feed that is **16 bio fuel and about 1.2 B of hydrogen per drum**, a few tens of crops. A pumped tank at 25 psig and 50 C burns 0.9 L/s
(TURBINE-AUX.md), a drum every 18 minutes and a full 20,000 L tank six hours: a farm and two chambers keep a train supplied, and a
turbine that runs hot or leaks burns it faster. The plastic of the panels draws on the same substrate and ethene, so the oil and the
control room compete for the same plant, as they would.

Why a can, and not the drum from the machine: a chamber turns out items, and `FuelFabricatorItemRouteTests` requires every bwr item
that is not in its "not crafted" list to have a *crafting* recipe in both installs, which a drum made in a chamber would not. So the
chamber makes the oil (the can: a new item, `bwr:turbine_oil_can`, with no crafting recipe, in that test's list and in
`NO_RECIPE` of `tools-audit-recipes.py`) and a bench fills the drum. A tank takes drums only; cans are an ingredient and nothing
else, and a world without Mekanism never meets one.

Without Mekanism the drum is four coal around an iron ingot, as before, and the chamber recipe is not loaded.

## Held back

**10 recipes have a designed Mekanism variant that is not written**, because writing it fails an existing test assertion, and those
assertions are not this work's to change. (There were 52: the 24 control-room recipes and 18 panel components that the first run
below lists are no longer held back, because the owner moved them from the grid to the fabrication desk and the tests that pinned
them were changed to check the desk recipe; see FABRICATION-DESK.md.) Their designs are in `HELD` in `tools-generate-mekanism-recipes.py` (the patterns in the
table above include them); until the owner decides, they stay vanilla in both installs, and the generator and
`MekanismRecipeTierTests` list them as held back with the test that pins each.

The list comes from the first full GameTest run with all 172 variants written: 15 required tests failed, one the new oil can's own
entry in the "not crafted" list (added: it genuinely is not crafted), the other 14 these (the first two rows are the ones that moved to the desk). Each loads a recipe by its **id** with
Mekanism loaded, or checks its **ingredients**, and with a variant the vanilla recipe at that id is not loaded:

| Held back | Test and assertion |
| --- | --- |
| ~~24 control-room recipes and 18 panel components~~ | done: made at the fabrication desk, and `ControlRoomRecipeTests`, `ControlDeskTests`, `RoomFurnitureTests` and `RealScaleTests.craftedStacksStartAtRealSize` look the desk recipe up by what it makes |
| `mimic_tape`, `panel_key`, `paperless_recorder`, `survey_meter`, `area_radiation_monitor`, `rtg`, `isotope_separator`, `cobalt_sample_detector` | `MimicTapeTests`, `PanelRecipeTests`, `PaperlessRecorderTests`, `RadiationToolsTests.theRecipesExist`, `RtgTests.recipesCraftTheBlocksAndTheAmBeRodFromACapsule`, `LargeRtgTests.ingotAndBlockRecipesConserveMassAndTheDataAgrees`: each loads `bwr:<name>` by id |
| `reprocessing_plant`, `large_rtg` | `ReprocessingTests` ("it must not depend on a mod", crafted from obsidian, hoppers, a blast furnace and iron) and `LargeRtgTests` (crafted from iron blocks and lead): both pin the vanilla ingredients |

Two ways out, for the owner. **(a)** Change those tests to look the recipe up by what it makes (the manager's recipe for the item) instead of
by id, and let the control-room test count Mekanism steel as it counts iron; then remove the entries from `BLOCKED` and run the tool.
**(b)** Ship the Mekanism recipes in a small built-in data pack that overrides the same paths and is added only when Mekanism is loaded
(`AddPackFindersEvent`, a few lines of Java behind the `isLoaded("mekanism")` guard): `bwr:<name>` is then live in both installs, the
by-id tests pass unchanged in both, and the vanilla files need no condition at all. The namespace and iron assertions of
`ControlRoomRecipeTests`, the vanilla ingredients of `ReprocessingTests` and the iron blocks of `LargeRtgTests` need an edit either way.
This work follows the brief's `_mekanism` and condition scheme, so (b) is not built.

## No Mekanism variant, and why

| Recipes | Why |
| --- | --- |
| Nugget, ingot and block of a bwr metal (`lead_*`, `thorium_*`, `uranium_*`, `plutonium_*`, the isotope blocks and capsule conversions, `raw_*_block`), smelting and blasting | one-to-one conversions: they make nothing new, so they cost nothing |
| `control_panel_back`, `_benchboard`, `_corner_*_inside`, `_end_cap_bench`, `_end_cap_console`, `_end_cap_flat`, `_header_strip`, `mimic_band_*`, `jet_pump`, `rpv_steam_outlet`, `turbine_steam_outlet`, `recirculation_inlet`, `recirculation_outlet`, the spent fuel pool ports, the suppression pool drain, return, suction and steam inlet | made only of parts that already carry the price (panel bodies, vessels, pool walls, pipes), with dye, glass or a hopper: a price of its own would count the steel twice |
| Charges, targets and sources (`cobalt_charge`, `borate_charge`, `lithium_charge`, `doped_silicon_redstone`, `ferrocerium_flint_and_steel`, the `*_target`, `*_source` and `boron_absorber`, `hafnium_absorber`, `tritium_sample_light`) | an isotope stand-in or source, priced by the isotope and by the irradiation casing |
| `damaged_lp_blade_section_scrap`, `wrecked_lp_turbine_hood_scrap` | scrap returns iron; a steel return would turn the repair loop into a steel mine |
| `guide_book` | a book and a nameplate; the nameplate carries the steel |
| `fuel_assembly`, `fuel_fabricator`, `depleted_uranium_assembly`, `shield_plug_concrete`, `heavily_reinforced_shield_plug_concrete`, `ultra_reinforced_shield_plug_concrete` | already built on Mekanism steel, yellow cake or netherite |
| `tritium_sample_oxidizing` | a Mekanism chemical recipe, not a crafting recipe |

## Checks

- `MekanismRecipeTierTests` (GameTests, namespace `bwrmektier`, run with and without Mekanism): with Mekanism every item with a variant has exactly one live
  crafting recipe, the variant, the vanilla twin is not loaded, every live recipe of a bwr item names a Mekanism item or tag unless it is
  exempt, and every variant, laid out in the grid and mirrored, is the recipe the manager hands back (nothing of vanilla's, Mekanism's
  or ours shadows it); without Mekanism the reverse. The oil route is checked end to end (the chamber recipe's inputs, amounts and
  four-can output, the drum, the old recipe gone, the upstream Mekanism recipes loaded, substrate balanced). The exemption list in the
  test is written by the generator, so it cannot drift from it.
- `FabricationDeskTests` (namespace `bwrfab`): the desk's recipe set is the right one in each install, and every one of the 42 parts has
  exactly one live desk recipe and no grid recipe (`MekanismRecipeTierTests` skips the desk recipes in its pairs).
- `tools-audit-recipes.py`: collisions are compared inside each install's live recipes and with Mekanism's own crafting recipes in its
  jar; every variant has a vanilla twin live only without Mekanism; a variant names no Mekanism item or tag is reported.
- `tools-audit-assets.py`: every `mekanism:` item id, every tag, and the oil recipe's fluid tag and chemical are confirmed in the pinned
  Mekanism jar (an unknown one makes the game drop the recipe at load, silently).
- `python tools-generate-mekanism-recipes.py --check`: every recipe is classified and every file is what the table says.

## Adding a recipe

Add the item's vanilla recipe as always; add it to `TABLE` (or `EXEMPT` with a reason) in `tools-generate-mekanism-recipes.py`; run the
tool; run `tools-audit-recipes.py` and `tools-audit-assets.py`. If it names a `mekanism:` id not already in `FOREIGN_ITEM_IDS`, open
the Mekanism jar and look first.
