# Changelog

## Unreleased (alpha.59): wrecked turbines, the shaft excursion and turbine fires

- **Turbine oil filtration: the Oil Filter Housing and its cartridge** (TURBINE-AUX.md). A pumped Main Oil Tank lost about 0.9 L/s, so a
  1,000 L Turbine Oil Drum lasted 18 minutes of running. Real lube oil is not used up, it is degraded, and a full-flow filter with a
  conditioner keeps it in service for years. The new **Oil Filter Housing** (a duplex filter modelled in headless Blender, with an inlet
  face and an outlet face) bolts straight against a Main Oil Tank and has **one slot for an Oil Filter Cartridge**. A clean cartridge
  lets **a tenth** of the oil loss through: a drum lasts **3.1 hours** and a full tank **62 hours** at rated speed. The cartridge
  lasts **6 hours** (faster with hot oil and pressure), its pressure drop climbs from 3 to 20 psid (a change is due at 12), and when it is
  full, or the slot is empty, the **bypass opens** and the oil is lost at today's rate. Taking it out gives back a used cartridge that
  remembers how full it is. Hoppers and pipes may fill an empty slot and take nothing out. Shown on the housing's screen, the oil tank's
  screen and the turbine card; signals `oil_filter_life`, `oil_filter_dp`, `oil_filter_bypass`, `oil_filter_alarm` (and the housing's and
  tank's own); CC `bwr_oil_filter` and `filterLife`, `filterDpPsi`, `filterBypass` on the oil tank. Recipes in both installs (iron, paper,
  wool; with Mekanism steel, paper and an HDPE sheet). A tank with no housing behaves exactly as before, and a housing saves a key only
  when it holds a cartridge. Core `TurbineAuxTest` 13 and 14; GameTests `OilFilterTests`.

- **Xenon floodlight: an aiming screen, one-degree aim and a reach of up to 128 blocks** (LIGHTING.md). Right-click the floodlight with an
  empty hand to open a small screen: **heading** 0 to 359 degrees, **tilt** -75 to +30 degrees and **reach** 8 to 128 blocks, each with a
  minus and a plus button (one degree or one block a click, Shift for 10 degrees or 8 blocks). The head turns to the exact angle and the
  light follows within a fifth of a second; a burst of clicks makes one re-plan. The server checks every command (in reach, not a
  spectator, may build, rate limited, in range). Sneak-right-click is still the quick 15-degree tilt (the old 45-degree turn on a plain
  click is replaced by the screen). The usual reach stays 32, so a floodlight nobody changes lights exactly what it did, and a save from
  before loads to the same aim (the aim is saved as `YawDeg`, `PitchDeg` and `Reach`, and also as the old `Yaw` and `Pitch` steps while it
  is on the old grid). At reach 128 a wall 123 blocks away is lit within the 256 lights (a re-plan costs about 9 ms, against 0.2 ms at 32;
  a long-reach floodlight's 5-second refresh is staggered by position); a ray still never loads a chunk, and lights placed far away are
  cleaned up exactly, through the queue when their chunk is out. CC `bwr_beam_lamp`: `setAim(yawDeg, pitchDeg)` and `setReach(blocks)`
  on the floodlight only (the high-bay and the panel refuse), and `yawDeg`, `pitchDeg`, `reach` in `getStatus`. GameTests
  `FloodlightAimTests`.

What a turbine failure leaves behind, how bad it can get, and how it is put right (TURBINE-MAINTENANCE.md).

- **Wrecked casings.** A turbine section has a wreck level the screens and computers read: **holed** (a thrown blade tore out through
  the casing, drawn as a ragged hole with its plate petalled out where it left; the LP still runs), **burst** (an LP's disc burst, or the
  train came apart: the hood or an HP's upper half torn open, the rotor stripped and bent; **the casing holds no steam and does no work** until
  it is rebuilt) and **shaft out** (below). The parts are Blender models (`art/models/power_turbines/build_wreck.py`, headless;
  `tools-export-turbine-wreck.py`); a burst HP's upper cells now open like an LP's hood cells.
- **The shaft excursion, the worst case.** A rotor whose disc burst carries the lost third's unbalance (68 MN at rated, 153 MN at 150 %).
  Driven on to **180 % (2,700 rpm, 220 MN)** its coupling shears and one flow's 62 t rotor half breaks out of the casing and rolls across the
  floor perpendicular to its axis (6 MJ, about 16 m of open floor), breaking blocks through the accident blasts' protected path, stopped by
  unbreakable or protected blocks, other casings and unloaded chunks (never loaded), hurting what it meets. It comes to rest as a **Turbine
  Shaft Wreck** block (the crane lifts it with BLADES OUT, a pickaxe breaks it for a Damaged LP Blade Section). The LP is coupled to
  nothing; the rest of the train is wrecked and grinds to a stop. `TurbineEvents.Excursion`. A close out of phase tops out at 5.7 pu, so a
  close alone cannot go far past the 5.5 pu burst; an overspeed past the burst is what drives a rotor loose.
- **Turbine fires.** A bearing run dry at speed passes 300 C in about a minute and lights the oil in its housing (200 L out of the tanks);
  a disc fragment, a blade thrown down through the lower casing or the rotor half cuts an oil line (1,000 L); an excursion lets the
  generator's hydrogen out to burn at its seals. The pool burns 2 L/s, running oil pumps spray 6 L/s more into it, a burning section loses
  0.4 % a second; real fire is kept lit beside the bearings (a place event per block) with smoke; water puts it out. A train with no oil
  has nothing to burn. `TurbineEvents.Fire`. Core `TurbineFire`.
- **Repair.** COVER OFF of a holed or burst LP gives a **Wrecked LP Turbine Hood** (scrap: 3 iron blocks) that never goes back on; COVER ON
  takes only a whole hood and patches a holed casing. A burst casing takes no blade section and no hood until rebuilt with a **Turbine
  Rebuild Kit** (4 iron blocks, 2 copper ingots, 2 iron ingots, an anvil) used on the stopped, cold section, an LP with its hood off and
  both sections out. The kit also renews a worn shaft (wiped bearings, a bad close). An HP or generator is rebuilt by hand.
- **Readouts.** BLADES tab rows Casing and Fire; CC `getStatus` `wreck`, `wrecked`, `fire`, `fireKind`, `fireFuel`; signals `fire`,
  `fire_fuel`, `wrecked`, `wreck`; `queueBladesOut` lifts a shaft wreck.
- **Saves.** New keys only when set (`Wreck`, `DiscBurst`, `Holes`, `BrokenFlow`, `Fire`, `FireFuel`; the wreck block's `Source`,
  `Flow`). Old worlds load with every section sound and nothing burning.
- **Tests.** Core `TurbineWreckTest` (3); GameTests `TurbineWreckTests` (`bwrturbine`, 9) and `TurbineBearingFireTests` (`bwraux`, 2);
  `-PbwrTurbineHallCheck` adds pictures of a burst LP, the rotor half on the floor, the fires, the wrecked LP's screen and the train rebuilt
  (`art/turbine-hall/`).
- **The high-bay and the HPS flat panel reach far, and their light no longer stops short.** Both lamps lit only a pool on the floor
  under them: a high-bay 12 up was dark past 28 blocks, 20 up past 24, and 30 up (a turbine hall) lit nothing at all, because the
  range was 24 blocks and the cone ended hard (outside it only the vanilla falloff, one level a block, reached). The high-bay
  now reaches **48 blocks** and the HPS panel **40** (a hall 30 up is lit to 37 blocks from under the high-bay), with a **spill zone**
  out to 160 degrees: the main beam stays at 15 and the spill lights are four fifths of
  it (12; the HPS warm-up 4, 8, 12, 15 gives spill 3, 6, 10, 12), so the beam still reads as the bright centre and there is no hard
  dark edge. The planner gives the far floor its share of the 256 lights by growing the lattice box with the distance (2 up to 6)
  and spreading the 1024 rays thicker towards the edge of the cone. High-bay 12 up, block light of the floor, lowest/mean: 20 blocks
  out 4/7.6 before and 6/9.1 now, 28 blocks 0/0.2 and 6/9.0, 40 blocks 0/0.0 and 4/8.7. Cost of a re-plan: 1.1 ms against
  0.4 to 0.7 on a flat floor, about 2 ms a lamp against 1.3 in a hall of 40 (79 ms against 53 ms for one pass over all 40). The
  floodlight plans exactly as before; the cap (256), the cleanup, the chunk rules and the 5 s refresh are unchanged; the save is still
  `Lights` and `LightKinds` (a lamp from an older save is re-levelled in place). LIGHTING.md has the numbers. New
  `FloodlightReachTests` (6 tests, namespace `bwrfloodlight`): reach and spill levels over a 141 x 141 floor at 12 and 30 up for both
  lamps and from a wall, the HPS warm-up of the spill, reload and an old save, a hall of 40, and the planner on its own across
  an unloaded edge. The client check has a second hall, 80 x 24 x 56 (`art/floodlight-reach/`, before and after).
- **Mekanism recipe tier (MEKANISM-RECIPES.md).** With Mekanism installed, **130 crafting recipes** ask for Mekanism's materials instead
  of iron, copper and redstone; without it every recipe is the one it was, under the same id, and Mekanism is still never bundled or
  required (it is all data and conditions). Each such item has two files: `<name>.json`, the vanilla recipe, now loaded only without
  Mekanism, and `<name>_mekanism.json`, loaded only with it, so exactly one recipe makes it in each install. The ladder: **steel** for
  every plate, frame and pressure part (the reactor vessel is 8 steel around a **steel casing** and makes two blocks, not four), **bronze** for impellers, windings
  and tube bundles, a **basic control circuit** where a part had a redstone, an **advanced** one where it had a comparator, an
  **elite** one and a **reinforced alloy** where it had a redstone block (the turbine train, the big pumps, the reactor controller,
  the MSIV, the containment controller), **HDPE pellets** in the high-pressure pipe and the resin, **refined obsidian** in the vault
  doors, **refined glowstone** in the sodium lamps. Cheap parts stay cheap (a lamp is a steel nugget; a nameplate one ingot for
  four); nothing needs Mekanism Generators or a Mekanism machine in the grid. The variants are written by
  `python tools-generate-mekanism-recipes.py` from one table (`--check`, `--table`); it adds the condition to the vanilla file by a
  one-line text insertion. The fuel fabricator keeps its older pair (`fuel_fabricator` is the Mekanism recipe).
- **Turbine oil is a Mekanism production chain.** New item **Turbine Oil Can** (250 L, not crafted, made by a Pressurized Reaction
  Chamber; the recipe `turbine_oil_can_from_ethene` is type `mekanism:reaction`): 1 substrate + 200 mB liquid ethene + 100 mB hydrogen
  make 4 cans; **4 cans around a steel ingot craft a Turbine Oil Drum** (1,000 L). Upstream is Mekanism's own: bio fuel and water in
  the reaction chamber make substrate and ethene, a rotary condensentrator liquefies the ethene, an electrolytic separator makes the
  hydrogen. One drum is 16 bio fuel and about 1.2 B of hydrogen and leaves 4 spare substrate (the ethene run makes 8, the cans use 4);
  a pumped tank burns a drum in about 18 minutes. Without Mekanism the drum is four coal around an iron ingot, as before.
- **Heavier steel for the big machines.** Steel blocks where the vanilla recipe had ingots, or fewer pieces per craft, for the vessel and
  its ports, the turbine train and generator, the big pumps, the MSIV, condenser, heat exchanger and condensate tank, the containment
  penetrations, the pool walls, the towers, the crane and refuelling bridge parts and the reprocessing plant; switches, small valves
  and the control rod drive are untouched. A vessel block is 6 steel and half an osmium (first Mekanism tier 3 steel, vanilla 2 iron), an LP turbine
  58 steel (38, vanilla 39 iron) plus its elite circuit and reinforced alloy, a recirculation pump 42 steel (12, vanilla 8 iron and 9 copper); a minimal
  plant (7 x 7 x 10 shell of 290 vessel blocks, one train, pumps, valves, a tower, containment, pool and crane parts, estimated) is about 4,100 steel and
  180 osmium (first tier 2,100 steel, vanilla 1,750 iron). New `python tools-cost-recipe.py [--vanilla] <item> | --plant` prices any item in either
  install. `tools-audit-recipes.py` now compares a variant's result item with its twin's, not its count.
- **Released: the eight tools, the reprocessing plant and the large RTG have Mekanism recipes** (`mimic_tape`, `panel_key`, `paperless_recorder`,
  `survey_meter`, `area_radiation_monitor`, `rtg`, `isotope_separator`, `cobalt_sample_detector`, `reprocessing_plant`, `large_rtg`), with
  the owner's OK to change the tests that pinned them, only as far as: with Mekanism loaded they look up `bwr:<name>_mekanism`; without
  Mekanism every check is unchanged. Edited assertions (old, then new):
  `MimicTapeTests.tapeIsRegisteredWithItsRecipeComponentsAndDefaults` `byKey(...("bwr", "mimic_tape"))` -> `byKey(...("bwr", "mimic_tape" + (ModList.get().isLoaded("mekanism") ? "_mekanism" : "")))`;
  `PanelRecipeTests.panelKeyRecipeMakesOneUnstackableKey` `("bwr", "panel_key")` -> `("bwr", "panel_key" + (...isLoaded("mekanism") ? "_mekanism" : ""))`;
  `PaperlessRecorderTests.theRecorderHasAnItemARecipeAndNamesForEveryPort` `BwrMod.id("paperless_recorder")` -> `BwrMod.id("paperless_recorder" + (...))`;
  `RadiationToolsTests.theRecipesExist` `byKey(("bwr", id))` -> `byKey(("bwr", id + (...)))` for `survey_meter` and `area_radiation_monitor`;
  `RtgTests.recipesCraftTheBlocksAndTheAmBeRodFromACapsule` `byKey(("bwr", id))` -> the `_mekanism` id for `rtg` and `isotope_separator` only;
  `LargeRtgTests.ingotAndBlockRecipesConserveMassAndTheDataAgrees` `byKey("cobalt_sample_detector")` -> `"cobalt_sample_detector" + (...)`, `byKey("large_rtg")` ->
  `"large_rtg" + (...)`, and the two structural blocks of the Large RTG grid `new ItemStack(Items.IRON_BLOCK)` -> `new ItemStack(frame)` where `frame` is `mekanism:block_steel` with Mekanism and `Items.IRON_BLOCK` without;
  `ReprocessingTests.theItemsAndTheRecipesAreRegisteredAndNeedNoOtherMod` with Mekanism: `byKey("reprocessing_plant")` -> `reprocessing_plant_mekanism`, message
  "no recipe bwr:reprocessing_plant (it must not depend on a mod)" -> "no recipe bwr:reprocessing_plant_mekanism", the grid obsidian/iron ingots -> `mekanism:ingot_refined_obsidian`,
  `mekanism:block_steel` and an `mekanism:advanced_control_circuit` in the middle of the bottom row, "obsidian, a vessel, two hoppers, a blast furnace and iron did not craft it" ->
  "refined obsidian, a vessel, two hoppers, a blast furnace, steel blocks and an advanced circuit did not craft it", "two iron ingots crafted it" -> "a steel block and a circuit crafted it";
  without Mekanism that test is the same as before.
- **The 42 control-room parts are not held back any more:** the owner moved them from the crafting grid to the fabrication desk (the
  fabrication desk bullet below), and the tests that pinned them (`ControlRoomRecipeTests`, `ControlDeskTests`, `RoomFurnitureTests`,
  `RealScaleTests`, `PanelTrimTests`) look the desk recipe up by what it makes. Nothing is held back now.
- **Checks.** New `MekanismRecipeTierTests` (4 tests, namespace `bwrmektier`, run with and without Mekanism, `MekanismOilRouteCheck` behind the
  `isLoaded("mekanism")` guard; its own namespace because added to `bwr` it made `LprmSpatialIntegrationTests.reloadReconcilesOldDetectorGeometryWithCurrentVessel` fail three runs in three ("fixture chunks never started ticking"); it passes without the new tests and with them in their own namespace, so the shared FCD fixture chunks look timing-sensitive): exactly one live recipe per tiered item and the right one live in each install, every live recipe of
  a bwr item names a Mekanism item or tag (a list of exemptions, written by the tool), every variant crafts from its own ingredients
  mirrored and unshadowed by vanilla's, Mekanism's or ours, and the oil route end to end (the chamber recipe's inputs, amounts and
  four-can output, the drum, the old recipe gone, Mekanism's substrate, ethene, rotary and electrolysis recipes loaded, substrate
  balanced). `tools-audit-recipes.py` now compares collisions inside each install's live recipes and against Mekanism's own recipes in
  its pinned jar, and checks every variant has its vanilla twin; `tools-audit-assets.py` confirms the new `mekanism:` ids, every tag
  and the oil recipe's fluid and chemical against the jar. `tools-export-steam-valves.py` writes the repeater of the turbine control valve (its `--check` was stale since 3ce1dfef); `tools-export-modern.py` (and with it the condenser, power-turbine, RWCU,
  valve, turbine-aux and water-valve exporters) and `tools-make-turbine-aux-assets.py` write the condition on a vanilla recipe that
  has a variant, so their `--check` stays green. `turbine_oil_can` is in the "not crafted" lists of
  `FuelFabricatorItemRouteTests` and `tools-audit-recipes.py`. Guide: three sentences say the recipes use Mekanism's materials when it is
  installed.
- **The fabrication desk (FABRICATION-DESK.md).** The control-room parts are no longer crafted in the grid: a new **Fabrication Desk**
  (`bwr:fabrication_desk`, a workshop bench; crafted in the grid, vanilla and Mekanism recipes in a pair; no block entity, no ticker,
  no computer face needed) makes them out of materials in your inventory. The screen follows TaCZ's gun smith table: tabs (Panels and
  trim, Desks and furniture, Switches, Gauges and meters, Lamps and annunciators, Displays), a scrollable list, a preview of the part, its
  materials with *have / need* (green with a circle, red with a diamond) and a Craft button that is off while a material is short.
  **Shift-click makes as many crafts as the materials cover, up to 8 and one stack of the result.** **42 parts** moved: the panel
  bodies and trim (vertical, console, corners, end cap, header canopy and vent, plinths, duplex), the desks, relay cabinets, control stand
  and wall displays, the control and key switches, rotary selector, setpoint knob, toggle switch, thumbwheel, pushbutton, RPS trip,
  push-to-turn switch, dial, edgewise and bar gauges, indicator lamp, annunciator window, bar graph and the two segment displays. Their
  recipes keep their ids and are now type `bwr:fabrication` (a result with a count, a category, item or tag ingredients with counts),
  in the pairs the Mekanism tier uses (`<name>.json` without Mekanism, `<name>_mekanism.json` with it), so exactly one recipe makes
  each part in each install and no grid recipe does. **Without Mekanism each part costs what its grid recipe cost. With Mekanism the
  held-back designs are written with every steel count doubled** (`STEEL_FACTOR`; a panel is 2.5 steel, a relay cabinet 14 to 16, a
  wall display 16 and a lamp two nuggets for two). Recipes that use these parts as ingredients are unchanged. The server decides every
  craft: not a spectator (adventure mode may craft, like pressing a panel button), a loaded desk, the player in reach (the container
  screen's range), a loaded recipe, the materials counted from the server's own inventory (tags across stacks and kinds, the off hand
  but never armour), then it takes them and gives the part in one go, **dropping what does not fit rather than deleting it** (a creative
  player pays like anyone else and also gets the drop), behind a rate limit of 10 requests a second (burst 10). JEI and EMI are not
  dependencies, so there is no viewer category. The model is built in headless Blender (`art/models/fabrication_desk/`,
  `tools-export-fabrication-desk.py`). Guide: a new "The fabrication desk" page in chapter 3 and a line on the first page.
- **Fabrication desk checks.** New `FabricationDeskTests` (12 tests, namespace `bwrfab`, run with and without Mekanism): the right
  recipe set is live, every one of the 42 has exactly one live desk recipe in its tab and no grid recipe, the data of every recipe
  (at most five ingredients, counts, tags that name items, no overlap, distinct wire keys), exact items taken and given, a refusal
  when short, from too far, for a spectator and at no desk, tags across stacks, a full inventory (survival and creative), a burst of
  requests and the rate limit, the menu snapshot, the block and its assets. The tests that pinned the grid recipes now check the same
  through the desk recipe (`RealScaleTests.craftedStacksStartAtRealSize`, `ControlRoomRecipeTests`, `ControlDeskTests`,
  `RoomFurnitureTests`, `PanelTrimTests`; `everyCraftableItemHasARecipeAndTheRestDoNot` counts a desk recipe as the item's route in both modes).
  `tools-audit-recipes.py` and `tools-audit-assets.py` read `bwr:fabrication` (shape, categories, counts, one maker per item and
  install, no Mekanism ingredient in a vanilla recipe, every `mekanism:` id and tag against the jar); `tools-generate-mekanism-recipes.py`
  writes the Mekanism side and prints and verifies the cost table in FABRICATION-DESK.md. The GUI screen check has three pictures of it.

## Unreleased: held panel controls are let go when they are removed, and a relaunched player's controls work

Two bugs in the control-room panels, both found by review.

- **A held control was never RELEASEd when its component was removed (safety).** A player holding a pushbutton wired to `selected_rod_withdraw_continuous` while the panel block was broken, or while another player wrenched the button off, left the rods withdrawing for good: every removal path took the component out of the panel first, and `releaseHeldControls` (which runs on chunk unload and `setRemoved`) only lets go of what is still on the panel. `PanelBlockEntity.removeComponent` now calls the new `releaseHeld(component)` first (cancels the fail-safe timer and sends RELEASE if the control is holdable and an output is non-zero), which covers a broken block (`releaseEverything`), the wrench, `verifyConsistency` repairs and a straddling component lost with a neighbour. Rewiring a held output with bind kind `NONE` or `REMOVE_SINK` lets go before the sinks are removed, so the RELEASE still reaches the plant. New tests in `PanelHeldControlTests`: panel broken, component wrenched off, sink removed and output cleared while held.
- **Press and release stamps were never reset, so a control went dead for a player after a client restart.** The client counts its PRESS/RELEASE stamps from 1 on each game launch, but the panel remembered the player's last stamp for as long as its chunk stayed loaded, so after a relaunch every new press (stamp 1) looked like a late copy of an old click and was ignored until the new counter passed the old one. Stamps are now stored with the player's login session (`PanelPlayerSessions`, a number that is new at every login and dropped at logout, handled in `PanelPlayerCleanup`); stamps of another session never compare, and the anti-reorder rule within a session is unchanged. New test `stampsOfAnEarlierLoginSessionDoNotBlockANewPress`.

## Unreleased: a running accident keeps going when the vessel is broken; stale control rod drives

- **A severe accident no longer pauses when the vessel structure is lost.** With a core but no formed structure the reactor
  controller returned before stepping the core and before the accident effects, so after a melt, mining one shell block (or a chunk
  border unforming the vessel at the next revalidation) froze contamination doses, queued blasts and the corium pool until the vessel
  was re-formed. Now, while any core damage, a failed lower head, a queued blast or contamination exists, the controller keeps stepping
  the core and running `ReactorSevereAccidentEffects` on an unformed vessel. The structure-derived inputs (cavity survey, pump and
  steam nozzle sweeps, cavity ports) are skipped and keep their last value; rods, the boundary and the containment room are read as
  usual and every world read is loaded-only. The vessel box is remembered (saved as `AccidentVesselMin`/`AccidentVesselMax` only when
  set; old saves load unchanged) so the pedestal blast, corium anchoring and blast protection still work. A healthy unformed reactor is
  held as before. See SEVERE-ACCIDENT-UPDATE.md.
- **Control rod drives left out of a structure no longer throw on right-click.** After a vessel shrank (say 177 to 150 rods) a drive
  outside the new structure kept `rodIndex=160`, and right-click (and the CC `bwr_control_rod_drive` peripheral) called
  `core.getRodNotchIndex(160)`, which threw; a stale index inside the new range read another rod. `bindDrives` now detaches drives it
  bound last time and no longer binds, and a drive checks its binding when read (a rod of the core, and while the structure is formed the
  slot the structure gives this very position), reading notch 0 otherwise.

## Unreleased: every tool-gated block can be mined for its drop, and a Large RTG hit at a part cell still drops

Two things that cost a player an item without any message.

- **16 blocks dropped nothing when mined in survival** (fixed in a23d0459): the containment airlock, controller, vault doors (both sizes), vent valve, spray header and the penetrations (data, nitrogen supply, pass-through, power, steam, water), the suppression pool quencher, and the xenon floodlight, xenon high-bay and HPS flat panel were registered with `requiresCorrectToolForDrops()` but were in no `minecraft:mineable/*` tag, so no tool was ever the correct one and the loot table never ran. They are now in `mineable/pickaxe` and `needs_iron_tool`. New `MiningDropTests` (`bwrmining`, `-PbwrGameTestNamespaces=bwrmining`) reads the registry and fails with the ids if any `bwr` block that requires the correct tool is in no mineable tag, if the tool of the tier its `needs_*_tool` tag asks for is not correct for it, or if a tool one tier lower still is; and it checks that every block with no `noLootTable()` and no `getDrops` override has its loot table in the loaded data. No exemptions were needed.
- **Large RTG lost its item when an explosion hit only part cells.** Only the controller cell's loot table gives the Large RTG item. An explosion (or a command, or another mod) that removed a part cell (cells 1 to 11) but not the controller dropped the isotope contents, then removed the controller without loot, so the unit itself vanished. Removing any part cell by anything other than a player now drops the item once (`LargeRtgBlock.onRemove`); a player mining a part cell still gets exactly one in survival and none in creative, the controller's own loot is unchanged, and an explosion that hits the controller as well still gives one. `LargeRtgTests.exactlyOneUnitDropsHoweverItIsRemoved` covers destroy, remove, setBlock, explosions in every order, and survival and creative players.

## Unreleased: computer, crane and turbine fixes (a freezing Lua call, chunk loads, blades, valves)

- **`bwr_spent_fuel_pool.getAssemblies(2147483647)` froze the server.** The rack range `first, count` was clamped at the start but not at the end, so a `first` past the last rack looped about two billion times on the server thread (a watchdog crash from one Lua line), and `first - 1` overflowed for the smallest int. Both ends are now clamped to the racks there are, in long arithmetic; `getRacks` had the same range.
- **A coordinate from a program no longer makes the server load or generate chunks.** `queuePlugOff(x, y, z)`, `queueCoverOff/On` and `queueBladesOut/In` read the block at the position they are given with a plain `getBlockState`, so `crane.queuePlugOff(2.5e7, 64, 2.5e7)` generated terrain synchronously. They now answer "not loaded" for a position in a chunk that is not loaded (the way `queueGateOut` always did), and the LP lookup reads only chunks that are there.
- **PLUG ON no longer forgets a laid-down section when part of it is in an unloaded chunk.** A section that reached into a chunk that was not loaded could not be surveyed, and that was taken for "the section is gone": its record was cleared. The record is now kept and the crane says the chunk is not loaded, so the player can press PLUG ON again. The canal gate (GATE IN) had the same fault and is fixed the same way.
- **A computer can close an unpowered containment valve.** `bwr_containment.setIsolation(x, y, z, true)` needed power like opening does, so with the power lost the close was refused and the spring-return valve opened again when power came back. Power is needed only to open, as for the other devices.
- **`getStatus().failed` of an LP turbine now reports a failed blade section.** It looked at the shaft counter only; it is the worse of the shaft and the blades, as `damage` already was and as TURBINE-MAINTENANCE.md says.
- **An LP disc burst once per rotor, not 20 times a second.** With one blade section lifted out (cover off) and steam admitted past 150 % speed, the burst marked only the section that was in, the "all thrown" test never held, and a 2,500 kg disc was launched every tick. A rotor with nothing left to burst no longer bursts again; a new section set in is a new disc.
- Placing a turbine module no longer reads the 13 x 8 x 13 box round it with a block read that could load a chunk near the edge of the loaded world.
- Tests added for all of these.

## Unreleased: fuel assemblies stack for bulk moving

Fuel assemblies and specialty rods stack to 64 wherever items are ordinarily kept and moved: a pocket, a chest, a hopper,
a pipe or a logistical transporter, a dropped stack. Only identical ones merge (vanilla merges stacks whose components are
equal), so two assemblies with different burnup, enrichment or discharge time stay apart as they always did; what stacks
is fresh fuel of one grade, and spent fuel that left the core together with the same exposure. Nothing about the physics
changed: burnup still lives on the item.

- **One assembly per position, from any stack.** A core position (the refuelling map's load, its batch load and its
  unload), a rack cell (right-click, the pool's item handler, the bridge, the elevator), the grapple, the elevator's hatch
  and trip, and the reprocessing plant's input slot each take exactly one assembly from a stack and leave the rest where it
  was. The reprocessing input and the fabricator output say they hold one (`getSlotLimit` 1), so a pipe or a shift-click
  of a stack leaves one in the slot; before, the plant's input would have taken a whole stack and `commit` emptied the
  slot after splitting one assembly, which would have deleted the others. It now extracts the one it split.
- **Dose and heat count every assembly in a stack.** `SpentFuelHeat.decayWatts(stack)` is the power of all the assemblies
  in the stack (`decayWattsEach` is one of them; a rack cell, the grapple and the plant's slot hold one, so their numbers
  are unchanged). New radiation source `bwr:carried_fuel` (`fuel/CarriedFuel.java`): the assemblies a player carries are a
  point source at the player and a dropped stack is one where it lies, with the gamma share of the decay heat of every
  assembly in the stack. A stack of 64 fresh discharges in a pocket is 64 times the source of one, which is the point:
  the deterrent to carrying spent fuel by hand is the dosimeter, not a stack limit. Fresh fuel reads nothing. Chests and
  other inventories still read nothing (there was no stored-item source before either).
- **Tooltip.** A stack of spent assemblies shows its total decay heat beside the per-assembly figure, and any spent
  assembly above 100 W of decay heat carries a red line that it is lethal to carry in the open and that a stack gives off
  the dose of every assembly in it.
- Saves: an assembly saved as a stack of one loads as before; nothing is rewritten.
- Tests (`FuelStackTests`, `bwrpool`): fresh assemblies merge in a player inventory and a chest and a hopper moves the
  stack chest to chest; two identical hot spent assemblies merge and the carried and dropped dose of the stack is twice
  that of one, as is the decay heat; every one-assembly position fed a stack of several takes exactly one with the total
  count unchanged (core map load, shift-click and batch load, rack cell by handler and by right-click, elevator hatch by
  handler and right-click and its trip, bridge grapple, reprocessing by pipe and by shift-click, fabricator output); and
  an unload into a full inventory drops the assembly rather than losing it.

## Unreleased: flaky GameTests round 3 (the level clock, and a release angle)

Test-only; no game behaviour changed. Two tests failed now and then.

- **`PanelDesyncTests.staleOwnerComponentIsRemovedOnVerify`** ("air: exactly one item dropped, got 0", "Index 0 out of bounds for length 0", also "stone/console/missing claim: one item"). A GameTest's clock is the level's game time (`GameTestInfo.tickCount = level.getGameTime() - startTick`), and `TurbineMaintenanceTests.run`, `TurbineAuxTests.run` and `RumbleTests.aBowedRotorMakesItsTrainRumbleAndAStraightOneOnlyHums` read the game time **before** the wait for their chunks (`EntityLookupProbe.runWhenLive`) and put it back when the body was done. The body is synchronous and moves the clock freely, but the wait had gone by on the real clock, so every such test turned the level clock back by the length of its wait (tens to hundreds of ticks). The other tests of the batch stood still on their own clock for that long while the world went on: the nameplate the panel test counts two or three steps after it dropped (8 ticks a step on its clock) kept falling on the real ticks, out of the fixture box, and the count read 0. Not a bug in the panels. The three tests now read the time when the body starts, so the clock is put back to where it is, not to where it was. With the old read, 42 of 50 runs of the panel test failed beside five turbine tests in one batch; with the new one, none of 50.
- **`TurbineMaintenanceTests.aDiscBurstsAt150PercentSpeedAndWrecksTheSection`** ("a disc fragment goes through more of the shell than a blade: 1"). The burst picks its release angle from the position and the game time (`TurbineMissile.pickAngle`), and the game time a test starts at is wherever the server has got to. The test's stone shell is 1.5 blocks thick on a block grid, so a fragment flying along some diagonals threads a single block of it (8 of 40 runs, about one angle in five). The test now moves its own clock on, never back, to the first tick at which the burst picks an angle along an axis of the wheel (where the shell is two blocks deep whatever the grid), then runs the same assertions. The game picks its angle as before.

## Unreleased: the HP and LP turbines remodelled

The TX-10 Style HP and LP Turbines were a plain cylinder with rings on a slab. They now read as the casings of a large nuclear
tandem-compound unit (a 1,100-1,350 MWe BWR train: one double-flow HP and double-flow LPs on their condensers). Generic and brand-free;
names, footprints, cells, ports, shaft line and blockstates are unchanged, and the generator is untouched. Write-up:
[MODULAR-TURBINES.md](MODULAR-TURBINES.md), "Models and references"; pictures in `art/turbine-remodel/` (before, after, side by side).

- **HP turbine** (5 x 5 x 9): the double-flow outer casing, its inlet belt in the middle and exhaust cones at both ends, split at the shaft
  by a heavy bolted horizontal joint flange that follows the casing; the main steam inlet chamber on top under the port, four steam leads
  into the upper half and four lower leads up through the foundation; an exhaust nozzle at each end into a crossaround header that leaves on
  the left port face; bearing pedestals with split bearing caps at both ends, the shaft and couplings showing, casing paws, lagging bands,
  drains, on a foundation table with sole plates.
- **LP turbine** (7 x 5 x 7): a fabricated box exhaust hood (vertical walls, sloped shoulders, flat roof) over a lower exhaust casing that
  opens down to the condenser; the crossover inlet on the roof between two atmospheric relief diaphragms; the bolted horizontal joint flange
  at exactly y = 3 (everything above is the crane-lifted hood, everything below stays); stiffened end walls, ribs and walers, manways in the
  hood sides and end walls, outboard bearings, lifting lugs where the crane's slings go, and the condensate return on the left face.
- **Hood off**: the lower half is a real open casing: the inner casing's lower half the blade sections sit in (it clears their first two
  stages; the last stage runs in the open exhaust), its joint and support paws, the inlet chamber between the flows, gland saddles in the
  end walls, a dark exhaust opening to the condenser. The hood carries the inner casing's upper half and is seen from below on the hook
  and on the laydown stand. The drawn last-stage wheels moved from 2.55 to 2.5 blocks from the LP's middle, where turbine missiles leave.
- **Couplings and shaft** are the generator's own geometry (shaft at y 2.5 on the module's middle, couplings 0.04 to 0.28 from each end), so
  every module meets face to face. Compact models (item icons, old saves) are scaled miniatures of the new ones. The LP Turbine Hood item
  icon is the new box hood.
- **Tooling**: `art/models/power_turbines/build_models.py` runs headless (`blender --background --factory-startup --python ...`) and is
  the only source; `tools-export-power-turbines.py` now writes the LP's `open` blockstate variants itself (`--check` passes on the
  committed assets); `render_previews.py` draws review pictures without the game; `-PbwrMainTurbineShots=<label>` (`MainTurbineShots`)
  takes nine in-game pictures of the train, the LP open, with pipes on every port (it fails if a pipe does not join its flange).
- **Triangles** (source / all cells / largest cell): HP 4,964 / 8,972 / 812 before, 7,872 / 18,028 / 1,028 now; LP 2,288 / 6,656 / 848
  before, 7,400 / 15,788 / 838 now (the generator: 1,684 / 6,536 / 474).
- Saves: nothing new is saved; the block states and cell numbering are the same, so old worlds load as they were.

## Unreleased: canal gates the crane lifts, and flooding the well made plain

Two requests from the owner: "the gate should open as one big block that can be pulled out with the crane like they do in real life", and "the reactor cavity
wasn't flooding with water when I was trying to flood it. Have the flooding be easier with checks showing why it won't flood." Write-ups:
[BUILDING-CRANE.md](BUILDING-CRANE.md), "Pulling the canal gates"; [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md), "Flooding the well" and "The canal gate plug"; the
guide's Refuelling chapter (Flood the well, The cavity fill port, Pulling a canal gate).

- **Canal Gate Plug.** A stack of Canal Gate Plug blocks in one canal column, from the rim down, is one steel gate with two lifting eyes on its top. It is a
  canal block for every survey (the pool's slot, the seal, the well, the bridge), so a plug in the slot keeps the pool formed, and it closes the canal while it
  stands in it. No hand, redstone or program opens it.
- **GATE OUT / GATE IN** on the crane console (and `queueGateOut([x, y, z])`, `queueGateIn()`, `getGates()`, `getStatus().gateNext` on the crane's computer face):
  the crane hooks the gate's eyes, lifts it out whole (an entity on a two-leg sling while it hangs, the shield plug's entity), and lays it on the nearest free
  **Gate Laydown Stand** or the clear floor nearest it; the column it leaves is open canal and the pool and the well are one water. GATE IN sets it back exactly.
  0.6 t a block (a whole gate is a few tonnes, within the 125 t rating). Refused, with the reason and nothing moved: out of reach, no gate, its eyes covered,
  over the rating, **more than 0.3 m of water across it** (checked again as the hook closes), no laydown, rails too low, its place in the canal built over.
  ABORT and RESUME, saves mid-lift, a broken console (the gate goes back in its canal) all lose and duplicate nothing. A lift kind of its own (`CanalGateLift`);
  the head, internals and plug lifts are unchanged.
- The old hand-worked **Canal Gate** keeps working for old builds and saves; its tooltip points to the plug and the crane.
- **Flood checklist.** The reactor's INFO tab has a "Flooding the well" section and `reactor.getFloodReadiness()` the same: head off, studs bare, the cavity seal
  (or which side is how far off, and where), the well reaching the flange (or the row and block where its wall opens), water going in now by source, water
  leaving and where to (drywell, over the top, the pool, the drains, RWCU blowdown, boil-off, a break), the level against the flange and the well's top with
  the time to each at the net flow, and the depth over the fuel. PASS, FAIL or INFO, in plain words. It reads only.
- **Cavity Fill Port.** The drain port's mirror: in the well wall at or above the seal, flange out. A feed, condensate, RHR or core spray pump's discharge piped to
  it delivers straight into the well; or, through a Motor-Operated Water Valve, a higher Condensate Storage Tank fills the well by gravity (the drain port's law,
  at most 1,500 kg/s), the mass exact. Nothing automatic: the valve moves only when the player moves it.
- **Fix: a well wider than the vessel would not hold water.** The well survey let the water spread only three blocks from the vessel; a well a few blocks wider,
  or a shelf beside it, counted as open, so with the seal at the flange the well ended at the flange and the water went over the "top" as fast as it came in.
  It now spreads up to eight blocks (an open refuel floor still ends the well). Regression test `aWellWiderThanTheVesselAndAShelfBesideItStillHoldWater`.
- Tests: `CanalGateLiftTests` (5, `bwrplug`), `CavityFloodTests` (5, `bwrcavity`), CC faces in `CavityFloodCcChecks`; pictures in `art/cavity-flood/`.

## Unreleased: the cattle chute as a slot

The cattle chute used to be a free-standing trench between the reactor and the pool with water of its own, the pool's lining and the well's wall
solid, and the bridge carried the assembly through them as if they were not there. It can now be built as a real plant's transfer canal: a gated
slot cut through the pool wall and the well wall, and with the gate open the pool, the canal and the flooded well are one body of water. Write-up:
[REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md), "The cattle chute as a slot"; [SPENT-FUEL-POOL.md](SPENT-FUEL-POOL.md), "The cattle chute's slot"; the
guide's Refuelling chapter has a page (The cattle chute).

- **The slot.** Transfer Canal and Canal Gate stacks may stand in a pool's side wall in place of lining: one straight stack from the rim down, turned
  through the wall, not at a corner, never in the floor, its lowest block at least one block over the top of the racks. The pool forms with it and
  reports it; a bad slot is refused with the reason. Canal blocks placed or broken ask the pools nearby to survey again.
- **The well.** The canal row runs on through the well's wall to the vessel's footprint. Canal blocks have always been wall for the cavity seal and
  the well survey, so neither changes; the slot's floor must be at or above the seal row.
- **One body of water.** With every gate in the row open, the pool's controller passes the water over the slot's floor from the higher surface to the
  lower once a tick: `Q = 0.6 x A x 1000 x sqrt(2 g H)`, `A` = 0.75 m x the slot's height, capped at 20,000 kg/s (1.5 m of head through a 1.875 m slot:
  4,577 kg/s). Whole kilograms leave the pool's water and enter the vessel's `liquidMassKg` (or the other way): the sum is exact. It stops level,
  never moves water under the sill, never overfills the pool or the well. A closed gate holds the levels apart. Nothing opens or closes a gate but a
  player or a program.
- **The canal shows the level.** The canal blocks' new `fill` state (0 to 16; 0, the default, is the old look) draws the water at the connected level;
  a column is drained only when the side it is open to is under its floor, and a bucket-drained column refills from a side over it. The canal's block
  states are now multipart (trough, gate plate and water as parts; `tools-make-refuelling-bridge-assets.py --canal`).
- **The bridge through the slot.** The slot column is part of the bridge's chute: its gates, its floor (the too-shallow check) and the connected level
  as the chute's surface. Every refusal stays. Old chutes (the row between solid walls) are unchanged and couple no water.
- **Saves.** Nothing new is saved; old worlds load with every canal block at `fill=0`, as before.
- **Tests.** `ChuteSlotTests` (namespace `bwrbridge`, 11 tests: the law, the slot rules, levelling both ways with exact mass, a closed gate, the sill,
  a drained canal refilling, a slot below the seal, save and reload mid-exchange, the bridge through the slot, an old chute unchanged). Client check
  `-PbwrChuteSlotCheck` (`ChuteSlotClientCheck`): four pictures in `art/refuelling-bridge/`.

## Unreleased: rumble

The plant is now loud in the ground. Steam from open safety relief valves collapsing in the suppression pool, and a turbine train shaking on its bearings, make a **deep rumble** you hear and a **procedural camera shake** you feel, by how much power the source puts in and how far away you stand. The whole relief valve bank of a large plant (18 valves, 2.1 GW into the pool) is just at the limit of perception **8,000 blocks** away; one open valve is felt out to about 500 blocks and shakes the view hard beside the pool; a bowed rotor at its critical speed shakes its hall and is heard some 85 blocks away. An effect only: nothing reads it and nothing acts on it. Full description, formula and calibration: RUMBLE.md.

- **Sound.** Two looping events, `bwr:rumble_pool` (an 18 to 65 Hz body with irregular falling thumps, the chugging, and a crackle of collapsing bubbles) and `bwr:rumble_turbine` (the 25 Hz shaft frequency and harmonics over a noise bed), synthesised with numpy and encoded by headless Blender (`tools-make-rumble-sounds.py`; 33 kB and 23 kB, no recorded or downloaded audio; SOUND-CREDITS.md). Soft-saturated so headphones and laptop speakers reproduce the deep body. Volume and pitch follow the intensity (distant = quieter and deeper); near the source the loop pans toward it, far away it has no direction. Ambient category.
- **Camera shake.** Smooth two-octave gradient noise on the drawn camera's yaw, pitch, roll and position, never a random number per frame, through `ViewportEvent.ComputeCameraAngles`; at full strength 6 degrees of roll, 5 of pitch and yaw, 0.18 blocks of bob. Your aim is never moved.
- **Options (client, `bwr-visuals.toml`).** `screenShake` 0.0 to 1.0 (default 1.0; 0 = off) and `rumbleVolume` 0.0 to 1.0 (default 1.0; 0 = no sound). Guide: Safety systems, "Rumble and shaking"; Server options lists both.
- **Distance law (core `RumbleLaw`).** `I = A sqrt(P / Pref) (r0 / (r + r0))^n exp(-r / L)`, with a ground profile (pool, impacts: `n` = 0.5, as surface waves spread, calibrated to the 8,000 blocks) and a machine profile (turbine: `1/r`, tens of blocks) and a perception limit of 0.02.
- **Sources.** The suppression pool (from the steam enthalpy it condenses each tick: relief valves, steam inlets, pump exhaust), the turbine auxiliaries (from the vibration they already publish and the shaft speed), and `Rumble.impulse(level, pos, energyJ)` for one-shots (a turbine missile, a blast). Public API in `dev.bwr.mod.rumble.Rumble`: `sustain`, `release`, `emit`, `impulse`, `intensityAt`.
- **Delivery.** The server tells every player of the level, however far and whatever chunks are loaded, at most four times a second and only when the state changed by more than 8 % (a 28-byte `RumblePayload`, plus a repeat every two seconds while something is felt); one zero when it stops or the player leaves the dimension. Nothing is spent while no source exists. The network protocol version rose to 26.
- **Saves and rules.** No save format change; old worlds load. No ticker added (the pool and the turbine auxiliaries already tick). No control logic: `checkNoProtectionLogic` unchanged.
- **Tests.** Core `RumbleLawTest` (6). GameTests `bwrrumble` (`RumbleTests`, 11; `-PbwrGameTestNamespaces=bwrrumble`): the law, the bank within 5 % of the limit at 8,000 blocks and delivered there, one valve's range, zero on stop, other dimensions nothing, bounded packet rate and server cost, impulses, the pool hook (also through a real open relief valve) and the turbine hook. Client check `:mod:runRumbleClientCheck`: the loops decode and are deep, camera offsets bounded and smooth, `screenShake` 0 is nothing, the loops start, follow and stop through the real sound engine. `tools-audit-sounds.py` now audits the two loops apart from the silent placeholders.

## Unreleased: turbine blades, missiles and the turbine hall crane

LP turbine blades now wear from four real causes, a failed one thrown at speed becomes a turbine missile, and the building crane (built
over the turbines) opens an LP casing and changes its blade sections. Full write-up: [TURBINE-MAINTENANCE.md](TURBINE-MAINTENANCE.md);
the Operator's Guide has a new chapter, "The turbine hall" (6 pages).

- **Two blade sections per LP** (one per flow, A and B), each 0 to 100 % worn. **Wet steam** erodes them by the existing law (unchanged;
  HP sections keep their one damage counter). **Out-of-phase closing**: the transient `2 sin(delta/2) / 0.35` times rated torque (5.0 at
  120 degrees) damages every LP's blades (`0.12 (T-2)^1.5`) and every turbine's shaft (`0.06 (T-2)`) above 2 pu; past 5.5 pu an LP disc
  bursts. The generator's own damage law is unchanged. **Reverse power**: a closed generator within 1 Hz of synchronous speed is held in step;
  with less steam work than the train's windage the grid motors it (reverse power shown, no FE), the LP exhaust hood heads for 150 K over its
  base and over 120 C the blades lose condition (fail in about 9 minutes at rated speed). **Overspeed**: the shaft's law also on the blades.
- **Turbine missiles**: a failed section with the shaft over 750 rpm throws a last-stage blade (45 kg, 408 m/s, 3.75 MJ at rated); a burst
  throws a 2.5 t disc fragment (84 MJ at 150 %). It flies in the plane of the wheel, breaks blocks while its energy lasts (6 x the head
  missile's crush energy a block, about 0.6 MJ for stone, the blasts' protected path, always on), hurts entities, and stops at unbreakable
  blocks, another casing or a chunk that is not loaded (never loads one). The train then shakes (45 mm/s per thrown blade at rated speed).
- **The turbine hall crane**: COVER OFF / ON (an LP's 110 t hood onto a **Casing Laydown Stand**) and BLADES OUT / IN (a 62 t blade section
  to and from an **LP Blade Storage Stand**) on the building crane's pendant and computer. Refused with reasons: shaft turning or turning gear
  on, steam in the casing, casing over 100 C, cover on, not under the crane, rails too low, over the 125 t rating. ABORT/RESUME, drawn
  hanging from the hook, saved mid-lift, never lost or duplicated. An open casing vents admitted steam and does no work.
- **New items and blocks**: LP Turbine Blade Section (iron blocks, iron ingots, a diamond), Damaged LP Blade Section (scraps to 2 iron
  blocks), LP Turbine Hood, LP Blade Storage Stand (an item handler for hoppers and pipes, two sections drawn on its saddles), Casing Laydown
  Stand. Server options `turbineHoodMassT` (110) and `lpBladeSectionMassT` (62).
- **Readouts**: the turbine screen's new BLADES tab (sections, cover, exhaust hood, reverse power, last breaker close angle and torque); CC
  `getStatus()` adds `shaftDamage`, `reversePowerMW`, `motoring`, `lastCloseAngleDegrees`, `lastCloseTorquePu`, and on an LP `coverOff`,
  `hoodTempC`, `bladeDamageA/B`, `bladeSections`. The crane peripheral gains `queueCoverOff/On`, `queueBladesOut/In`, `getTurbineSections`.
- **Hooks**: server-side NeoForge events `TurbineEvents.Missile` (position, direction, energy) and `TurbineEvents.Vibration` (once a second per
  turning train, mm/s), for sound and screen shake to subscribe to.
- **Saves**: an old LP's damage becomes its blade sections' wear (at most 0.95, so nothing is thrown on load); new keys only when not the
  default (an LP always writes `Blades`). LP cells gain the block state `open` (false by default).
- **Tests**: core `TurbineBladesTest` (6); GameTests `bwrturbine` (`TurbineMaintenanceTests`, 14); client check `-PbwrTurbineHallCheck`
  (pictures in `art/turbine-hall/`); the GUI check gains the BLADES tab and the crane's turbine hall card.

## Unreleased: spent fuel pools of plant size

A real plant's pool holds thousands of assemblies. A pool now holds **up to 2,048 racks (8,192 assemblies)**, and the shell may be **5 to 64 blocks wide and deep and 8 to 24 high** outside (was 48 racks in 5 to 25 by 8 to 16). A 2,048-rack pool needs 2,048 m2 of floor (48 x 48 outside will do); the largest, 64 x 64, has room for 3,844 rack positions. Write-up: [SPENT-FUEL-POOL.md](SPENT-FUEL-POOL.md), "Building one" and "Scale"; the guide's pool page and the size message give the new limits.

- **Physics and ratings unchanged.** The water is still the interior volume (the largest pool holds 88,412 t), the cooling train keeps its 6 MW rating (a large pool wants several trains' worth of cooling from the player), nothing was clamped to the old size in the core `SpentFuelPool`.
- **The once-a-second step does not do 8,192 decay curves at once.** Each stored assembly keeps its decay curve with the history part worked out once (core `AssemblyDecayHeat.Curve`, mod `SpentFuelHeat.Curve`: the same number to the last bit as the plain formula, `AssemblyDecayHeatTest.test07`), and the decay power every assembly will have at the next step is worked out a twentieth of the racks per tick ahead of it. A rack changed since is worked out again at the step, so the step's numbers are exactly those of computing everything then (a GameTest checks all 8,192 cells bit for bit). Measured on a full 2,048-rack pool with cooling on: **0.10 ms a tick on average, 0.6 ms for the step tick**, 0.07 ms between steps.
- **Survey.** The flood fill and the sweep read the chunk sections directly (a packed-long set, no chunk lookup per block, never loading a chunk; outside the build height still counts as not loaded): about **5 ms for the largest pool** (9,892 shell blocks, 98,304 positions), and it still runs only when a block of a pool changes near the controller. A pool that is not formed retries every five seconds as before, but first looks at the one block that stopped the last survey (a gap in the shell, junk in the water) and surveys only once that block has changed.
- **Client picture.** The racks go to the client as a bitmap of the floor and the glow as four bits a cell: **4.7 kB for a full 2,048-rack pool** (positions and bytes would be 24 kB), sent only when the picture changed; the glow is kept per cell instead of hashing every cell on every insertion.
- **Rendering.** A glowing cell more than 24 m from the eye is drawn as its cap alone (one quad, not five); the water surface is drawn in squares of at most 8 blocks so a 62 m pool does not stretch one water texture across the hall.
- **Panel.** The rack list is **paged**: eight racks at a time with `<<`, `<`, `>`, `>>` and "1017-1024 of 2048"; the snapshot carries only that page (**469 bytes** against about 74 kB for a line a rack, over the 64 kB a snapshot may carry). The bridge console's pool cells drop the positions and send decay heat in tenths of a kW (13.5 kB for a full pool; the old reader stopped at 64 racks).
- **Radiation.** Up to 64 racks holding fuel a dose point sees every rack, exactly as before. A larger pool is a quadtree over its floor: near racks on their own, far regions lumped at their heat-weighted centre once the region is under 0.3 of its distance. Over a full 2,048-rack pool a reading uses 8 to 270 sources and is **within 1.1 %** of the rack-by-rack sum; a sample costs **0.47 ms cold, 0.1 ms warm**.
- **Computers.** `getRacks([first, count])` and `getAssemblies([first, count])` take an optional rack range; without arguments they return everything as before. `getStatus().racks` no longer builds every rack line; `getAssemblies` reads the kept curves (its reprocessing time is searched once per assembly, not once per call). The machine-peripheral wrapper accepts optional whole-number and number arguments.
- **Elsewhere.** `insertAnywhere`, the elevator's coolest-cell pick (ranked by the heat the pool refreshes each second) and the bridge's rack check no longer scan or re-evaluate every cell; the registry's reach follows the largest pool (65 blocks); saving keeps each assembly's saved form, written a few per tick, so a full pool saves in 2.5 ms instead of 80 ms.
- **Saves.** The save format is unchanged; old pools load and re-form as before. The client picture's NBT keys changed (`VisRackMap`, `VisRackGlow`); they are never saved.
- **Tests.** `SpentFuelPoolScaleTests` (namespace `bwrpool`, batch `poolscale`, 3 tests): the 64 x 64 x 24 pool with 2,048 racks forms, the 2,049th rack and a 65th block of width or 25th of height are refused, a blocked retry does not survey; a full pool takes 8,192 assemblies through its item handler, cools, saves and reloads, with tick, packet, save and dose costs bounded and printed as `POOL SCALE` lines; the survey of the largest pool with a chunk column unloaded waits, reads and loads nothing, and forms again when the chunks are back. `SpentFuelPoolCcChecks` checks the rack range. `-PbwrGuiScreenCheck` gains `spent-fuel-pool-2048-racks`. The pool tests' "8 to 16 high" message check reads "8 to 24 high".

## Unreleased: shield plug lifting

The first step of a refuelling outage is lifting the reactor well shield plugs off with the reactor building crane. Until now the mod had no such step: players mined plug blocks by hand. The crane now takes a plug off section by section, on command. Full write-up: [BUILDING-CRANE.md](BUILDING-CRANE.md), "Lifting the shield plugs"; the Operator's Guide has a page (Refuelling, "Lifting the shield plugs").

- **Crane Anchor (`bwr:crane_anchor`).** A steel lifting eye (a headless-Blender model, `art/models/crane_anchor/build_models.py`, baked by `tools-export-crane-anchor.py`, 500 triangles) that stands only on the top face of a shield plug concrete block (any tier) and breaks with it. No block entity, no ticker. Two iron ingots make four.
- **Seams split a plug (`bwr:plug_seam_marker`, the Plug Seam Marker; an iron ingot on a stick).** Right-click a plug block near the edge where the cut goes: the joint between it and the plug block beyond that edge becomes a **seam**, drawn as a dark groove round the four faces of the joint; click again to undo, sneak-click clears a block. A seam is one boolean block state (`seam_up`, `seam_north`, ...) on each of the two blocks, false by default: it saves with the world, lifts and lands exactly, changes nothing about blast resistance, shielding or mining, and old worlds are unchanged. A **section** is the plug blocks flood-filled from an anchor's block, stopping at seams and at anything that is not plug concrete.
- **PLUG OFF / PLUG ON** on the crane pendant, and `queuePlugOff([x, y, z])` / `queuePlugOn()` on the computer peripheral. PLUG OFF takes the section nearest the middle of the well that has **exactly four anchors** and sets it on the **nearest clear spot of the refuel floor**: a whole number of blocks away, every cell air (a block is never replaced), the lowest layer on solid floor, inside the crane's reach, off the well and off the square round every head stand. PLUG ON sets the last section set down back exactly where it came from. Nothing acts by itself; ABORT and RESUME work as for the other lifts.
- **Capacity, with the masses as data.** A section weighs the sum of its blocks plus its anchors, from the new block data map **`bwr:plug_mass`** (tonnes per block, tunable by a datapack): 2.4 t for shield plug concrete (ordinary reinforced concrete), **3.2 t** heavily reinforced (heavyweight aggregate), **4.0 t** ultra-reinforced (the top of the 3.5 to 4.0 t/m3 heavyweight shielding concrete range; a game value) and 0.05 t a crane anchor. The crane is rated **125 t** (no figure existed; core `PlugLift`): 52, 39 or 31 whole blocks of a tier, so heavier tiers need more seams. Over it, or without four anchors ("needs 4 anchors, has N"), the lift is refused with the reason and a seam as the cure. **The mass is shown before the lift**: the pendant's *Next plug* row ("28 blocks, 112.2 t of 125 t, 4 anchors") and, for computers, `getStatus().plugNext` and `getPlugSections()`.
- **LIFT HEAD and SET HEAD are refused while any shield plug block stands over the reactor well** (the vessel's footprint and the well the cavity survey found, from the first row above the vessel to the rails).
- **A lifted section is an entity (`bwr:plug_section`).** When the hook closes, the section's blocks leave the world, every state held by one entity that hangs from the hook on four slings and moves with the crane; set down, it places the blocks back state for state and removes itself. A hanging section stops no head missile and shields nothing (its blocks are out of the well); put back, they do both again. The entity is saved with its chunk and never despawns or duplicates, cannot be hurt, pushed, picked or summoned, never loads a chunk (the crane waits while its chunk is not loaded), and never replaces a block. If the console is broken, or the runway is broken, or the crane does not hold it, the section goes **back where it was taken from** if that is still clear, otherwise **every block drops as an item**; `/kill` does the same. New NBT only while a lift or a laydown exists: the crane's `Plug`, `PlugEntity` and `Laid`.
- **Rendering.** The section is drawn block by block under the hook from the crane's own hook position, and four slings run from the hook ring to the four anchors (`BuildingCraneRenderer`); a laid section is ordinary blocks.
- **Tests.** Core `PlugLiftTest` (3). GameTests `bwrplug` (`PlugLiftTests`, batch `plugs`, `-PbwrOnlyPlug` or `-PbwrGameTestNamespaces=bwrplug`; 15 tests): anchors on plug tops only; a seam splits a plug into two sections and the marker toggles and clears it; PLUG OFF lifts exactly one section, the blocks leave the well and land exactly at the laydown, the entity hangs under the hook; PLUG ON restores state for state; the capacity refusal; a tier 3 section over the rating refused where the same size in tier 1 is taken; the same size weighing differently by tier, with the mass shown before the lift; the four-anchor rule; LIFT HEAD refused with a plug over the well and allowed after; a laydown that is not clear (none, blocked before the lift, blocked while carrying) never replaces a block; save and reload mid-lift with one entity and every block; a console broken under a hanging section (put back / dropped as items), a broken runway and `/kill`; the head missile and the shielding with the plug off and on; an old crane with no plug data. Client check `-PbwrBuildingCraneCheck` gains four pictures; `-PbwrGuiScreenCheck` two pendant scenarios.

## Unreleased: display units option

A client option picks the units the machine screens print: `displayUnits` in `bwr-visuals.toml` (client only), with four values. **METRIC** is the default and shows exactly what the screens have always shown. **SI** is the metric system for the same readouts: pressure in kPa(g) and kPa(a) (MPa above 1,000 kPa), degrees C with the degree sign, metres, kg/s, m3/h for water. **IMPERIAL** shows degrees F, lb/s for steam and feed flow, gpm for water flow, feet, gallons and pounds. **PLANT** is the US control-room convention: degrees F, Mlb/hr for steam and feed flow, gpm for pumps, inches for water level, MWt and MWe. The guide's "Server options" chapter lists it.

- **Display only.** Values stay as they are inside the mod: the physics, saved worlds, network packets, signals and computer (CC) outputs never change. A readout is converted at the last moment from the unit the mod stores.
- **One conversion.** `dev.bwr.core.display.DisplayUnits` holds the factors (exact: 1 lb = 0.45359237 kg, 1 ft = 0.3048 m, 1 US gal = 3.785411784 L, F = C x 9/5 + 32, 1 psi = 6.894757 kPa, 1 inch = 0.0254 m) and the labels for each mode. Tested in the acceptance suite, `DisplayUnitsTest`.
- **SI pressure.** Pressure is shown as kPa and, above 1,000 kPa, as MPa, labelled by its kind: gauge pressure kPa(g), absolute kPa(a), a pump's pressure rise kPa. The pump's vessel pressure row, which has always said "psi" in METRIC, reads kPa(g) in SI; METRIC's text is unchanged.
- **Converted screens.** The reactor panel's readouts and INFO tab (pressure, dP/dt, temperatures, steam and cleanup flows, level, cavity volumes and heights, hydrogen, the power labels), the pump screen (water flow, water temperature, inlet buffer, pressure rise and vessel pressure), and the turbine and generator screen (steam pressures, steam flow, temperatures, legacy water, oil and hydrogen pressures, electrical output).
- **Not converted, in every mode.** Dose, FE, neutron flux, reactivity and burnup keep their units. Panel gauges keep their own scales: their numbers are saved in the component's units and wired to setpoints, so relabelling them would make the printed scale disagree with the wiring. Server-written status text (containment, severe accident, boundary and suppression pool lines) keeps its units for now.
- **Why METRIC keeps psig and inches.** The screens already print gauge pressure in psig and the reactor level in inches. Converting those to kPa or metres in the default would change what existing players read, so the default keeps them. SI is the option for kPa and metres.
- **Saves.** Nothing new is saved. Old worlds load unchanged.

## Unreleased: block radiation shielding

Blocks a player places between a radiation source and a person now shield it. Write-up, the half-value layer table and its sources: [RADIATION.md](RADIATION.md), "Shielding".

- **Line of sight per point source.** The line from each point source (the RTGs, the pool's racks, assemblies on the bridge, crane, elevator and pads, coolant crud, the RWCU demineraliser, carried capsules, resin and radium) to the dose point is marched through the block grid (3D DDA) with the true path length through each block; `T = product of 2^(-path_cm / HVL)`. The survey meter, the area monitor, the CC `other` term and the swimming dose read the shielded value.
- **Half-value layers at about 1 MeV** (broad beam): lead 1 cm, steel 2.5 cm, concrete 6 cm, water 9.8 cm (the dose model's own), earth 9 cm, wood 23 cm, glass 5.5 cm counted as a 1 cm panel per block. One energy for every source; the choice and the narrow-beam NIST figures are in RADIATION.md.
- **Data-driven.** New block data map `bwr:radiation_shielding` (`hvl_cm`, optional `fill`) over family tags `bwr:radiation_shielding/lead|steel|concrete|leaded_concrete|earth|wood|glass`: stone, deepslate and ores count as concrete, iron and metal blocks as steel; the three shield plug concrete tiers 5 / 3.5 / 2.5 cm; Immersive Engineering concrete and leaded concrete as optional tag entries only. Slabs count by their collision shape; waterlogged blocks add their water. The floodlights' `minecraft:light` blocks are air (a waterlogged one is the water it stands in).
- **Block of Lead (`bwr:lead_block`).** Nine lead ingots, and back; stone pickaxe; `c:storage_blocks/lead`.
- **No double counting.** Blocks of formed structures (`bwr:radiation_shielding_exempt`: the reactor and its machines, the spent fuel pool, the elevator, the RTG and the Large RTG) never count; the pool's racks skip the pool's own box and the Large RTG its footprint; sources whose transmission already holds the water on the line do not count water blocks again; the reactor core term is not marched (its calibrated biological shield and building factor already holds it). Every existing reading is unchanged.
- **Cost.** No ticker, no scan: marched only when an instrument samples, only through loaded chunks (a chunk that is not loaded counts as unshielded and is never loaded), capped at the dose model's 256 m; cached per source and dose-point block, dropped on a block update in a crossed chunk or after 40 ticks.
- **Tests.** `ShieldingTest` in `:core:acceptance`; GameTests `ShieldingTests` (namespace `bwrshield`, batch `shielding`); client check `-PbwrShieldingClientCheck` (a lead cask around an RTG, the meter inside and outside). Guide page "Shielding" in "When things go wrong".
- **Saves.** Nothing new is saved; old worlds load unchanged.

## Unreleased: xenon floodlights and HPS flat panel

Three redstone lamps that light an area: the **Xenon Floodlight** (`bwr:xenon_floodlight`), an aimable fixture with a 32-block, 30-degree beam; the **Xenon High-Bay** (`bwr:xenon_high_bay`), a ceiling fixture with a 24-block, 110-degree beam for a large room; and the **High-Pressure Sodium Flat Panel** (`bwr:hps_flat_panel`), a flat golden-white (#FFCE80) ceiling or wall panel with a 24-block, 120-degree beam. The xenon lamps are cold white (about 6000 K) and come on at once.

- **How the light is made.** A fan of rays spread over the lamp's cone walks the grid from the lens to the first opaque block; the last free cell in front of it is where that ray lands, and a vanilla light block goes there. So the light is a pool on the floor, wall or pool bottom the beam actually reaches, and nothing is placed in open air or behind a wall. Landing cells are thinned to one per 2-block lattice box, widening (up to 6) to keep at most 256 lights per lamp.
- **What may be replaced.** Only plain air, cave air or a still water source (the light is waterlogged, so the water stays). Flowing water, any other block and a player's own light blocks are never used or touched.
- **Exact cleanup.** The lamp's block entity keeps the list of cells it changed (and whether each was air, cave air or water), saved with it. Switched off, aimed away, or broken by any path (a player, an explosion, a command: all go through the block's removal), each listed cell is put back only while it still holds a light. A cell whose chunk is not loaded is queued in a level save file and put back when that chunk is loaded again; the queue never loads a chunk and runs once per cell.
- **Chunks and shutdown.** Nothing is loaded: the planner stops at an unloaded chunk, power is read only when the chunks two blocks around the lamp are loaded, and every write skips neighbour shape updates (which would read, and so load, a neighbour at the edge of the loaded area). Nothing runs on a chunk unload, a save or a shutdown.
- **Timing.** No block ticker and no global service: a redstone change re-plans at once, and a lit lamp re-plans every 5 s on its own scheduled block tick (an unlit lamp schedules nothing).
- **HPS warm-up.** As the HPS lamps: it strikes at light 4 and steps to 8, 12 and 15 over about 20 s, and its projected light steps with it. No hot-restrike wait.
- **Computers.** A read-only CC:Tweaked face, `bwr_beam_lamp` (`getStatus()`: lit, light blocks placed, yaw and pitch); redstone is the only switch.
- **Aim.** Right-click (empty hand) turns the floodlight 45 degrees; sneak-right-click steps its pitch from -75 to +30 degrees in 15-degree steps. The head (headless Blender, `art/models/xenon_floodlight/build_head.py`) turns to the aim, and the aim now reaches the client.
- **Visuals.** The lit lenses are full bright in vanilla (NeoForge face light data with `shade: false` on the lens elements of the block models, so a downward lens is not halved by the face shading; the floodlight's lit lens is drawn by its renderer into the terrain cutout layer, whose program has no diffuse shading, and through the machine mesh path under a shader pack) and emissive under shader packs through labPBR `_s` maps (alpha 254 on the lit lenses; 255 means none in labPBR), written by `tools-make-xenon-textures.py`. The asset audit accepts a `_s` or `_n` map whose colour texture is used (the same edit as the industrial lamps).
- **Tests.** `FloodlightTests` (namespace `bwrfloodlight`), 16 tests: exact cleanup on power loss, re-aim, breaking and explosions; a player's lights surviving every cleanup; walls, flowing and still water; save and reload; a real chunk unload and load of a lit lamp; the queued removal in an unloaded chunk running exactly once and loading nothing; the HPS warm-up. Every fixture waits for its forced chunks before building and lets the light engine settle before releasing them (see the fixed shutdown hang below).
- **Fixed: GameTest server hanging on shutdown.** The first version's fixtures forced their chunks, built into them at once (a synchronous load with the neighbours half generated), placed up to 256 level-15 lights and released the chunks, all in one tick. The light updates queued behind the half-generated neighbours' light steps; with the tickets gone those generation tasks never finished, their chunks kept a generation reference, and `MinecraftServer.stopServer` waited for them to unload for ever.
- **Pictures.** `-PbwrFloodlightClientCheck` (optionally `-PbwrRenderMods=newest -PbwrShaderPack=ComplementaryReimagined_r5.9.3.zip`): a dark 40 x 12 x 40 hall lit only by four floodlights, four high-bays and four HPS panels, from three places, close-ups, each type alone and all off. Under Iris the picture is the window's 1920 x 1061 (the 2x offscreen path is vanilla only), as for the industrial lamps.
- **Not in this change.** No FE draw (redstone only); no hot restrike for the HPS panel; the floodlight item shows only the base.
## Unreleased: industrial lamps

Six redstone-controlled plant lamps: three lamp types (low-pressure sodium, high-pressure sodium, mercury vapour), each as a high-bay pendant and a wall pack. Lit, they warm through light 4, 8, 12 and 15 over 20 to 40 seconds of game time; the high-pressure and mercury lamps cool for about 15 seconds before a restrike.

- **Blocks (`bwr:lps_high_bay`, `bwr:lps_wall_pack`, `bwr:hps_high_bay`, `bwr:hps_wall_pack`, `bwr:mercury_high_bay`, `bwr:mercury_wall_pack`).** Light level 15 when full. A high bay hangs under a solid block; a wall pack mounts on a wall. Each drops itself and breaks with a pickaxe.
- **Warm-up and restrike on scheduled ticks.** No per-block ticker. Old saves load: the lamps are new blocks.
- **Shaders.** Each lit lens ships a labPBR `_s` specular map with emission in its alpha channel, so a labPBR shader pack (Complementary) reads the lens as lit. The shader path was not run in this change; see LIGHTING.md.
- **Models and textures.** Headless Blender (`art/models/industrial_lamps/build_lamps.py`), `tools-export-lamps.py`, `tools-make-lamp-textures.py`.
- **Recipes.** Distinct per type: orange stained glass, glowstone or light blue stained glass as the lens, with iron and redstone.
- **Guide.** "Industrial lamps" page in the control-room chapter. Design and the shader notes in LIGHTING.md.

## Unreleased: pool shader look

The spent fuel pool's water and Cherenkov glow now look like water and light under Iris with Complementary Reimagined. Vanilla is unchanged in what it draws.

- **Pool water under a shader pack.** The surface is replayed after the translucent terrain (new `client/DeferredLiquids`, after `AFTER_TRANSLUCENT_BLOCKS`), not in the block entity pass. Iris gives translucent terrain its water program, which is where a pack does reflections, ripples and water tint; the block entity pass only reached the block translucent program. The water uses `DeferredLiquids.WATER`: the translucent shader, blend and texture of vanilla translucent terrain, but colour-only writes, so the glow beneath the surface is not hidden. Vanilla draws the same quads in the same order.
- **Pool glow under a shader pack.** The glow is written straight, not premultiplied, when a pack is active (`DeferredLiquids.glowStraight()`, read once per replay through `VisualEffectsMode.shaderPackInUse()`). The pack's glow program blends with the alpha, which squared the premultiplied colour and left the glow almost invisible. Vanilla keeps the premultiplied colour.
- **Client check.** `-PbwrSpentFuelPoolCheck` saves two more pictures, `pool-underwater` and `pool-night-glow`, and asserts that the replay ran (the pool stage fails with "the pool water and glow were not replayed after the translucent terrain" if it did not).
- **Not changed.** The flooded well and the open vessel's water still draw in the block entity pass. Moving them to the replay removed the core glow under Complementary, so they were put back; this is open. No pool lamps were added: the lamps and floodlights branches own underwater and pool light.
- **Optional mods.** Nothing new is required. Iris is still only asked through the existing reflection in `VisualEffectsMode`.

## Unreleased: large RTG and RTG heat

A 2 x 2 Large RTG for up to 1,000 kg of isotope material, RTGs as heat sources (Cold Sweat, a heat-only switch), ingots and blocks as the form the RTGs take, and cobalt-60 as the reactor-bred bulk fuel. Write-up, numbers and the pattern for new isotopes: [RTG.md](RTG.md).

- **Large RTG (`bwr:large_rtg`).** A 2 x 3 x 2 multiblock (three high: a shielded two-block body under a one-block radiator stack; two high had room for the wall or the fins, not both), one item, placed like the pumps (footprint check, one controller, breaking any part removes it and returns the unit and everything loaded). One server ticker on the controller, none per cell, no scheduled ticks. Stores up to **1,000 kg** as a mass (a count per item kind, not 4,000 slots), loaded by right-click, by the screen's Load my fuel button, or by hopper and pipe (insert-only item capability on every cell); a full unit refuses. The small RTG's physics: thermal = mass x W/g x decay, electric 5 %, FE/t = W / 13.4 x `rtgOutputMultiplier`. 1,000 kg of Sr-90 is 920 kW thermal, 46 kW electric, 3,432.8 FE/t; of Co-60 18.9 MW, 945 kW, 70,522 FE/t. FE buffer 1,000,000, pushed to what touches the bottom layer.
- **Shielding and dose.** 40 cm of lead-equivalent housing: each isotope's photons pass `2^(-40 cm / lead half-value layer)` (new optional `lead_hvl_cm` in the fuel list). The screen shows **Dose at 1 m** (through the housing, never rounded to nothing) and the bare core's for scale; the block is exactly that point source. It stops caesium, strontium and americium; **1,000 kg of cobalt-60 still reads 1.23 mSv/h at 1 m** (bare 13.3 MSv/h). The small RTG's flat 1e-4 housing is unchanged.
- **Screen, signals, computers, guide.** Stored mass by isotope, thermal, electric, FE/t, dose, buffer, heat-only switch, Load my fuel. Signals `thermal_power`, `electric_power`, `dose_rate`, `mass`, `buffer`, `fuelled`, `heat_only` and the command `set_heat_only`; computer face `bwr_large_rtg` (`getStatus`, `setHeatOnly`). Guide pages "Ingots, blocks and cobalt-60", "RTG heat" and "The Large RTG" in the fuel chapter.
- **Model.** Headless Blender (`art/models/rtg/build_large.py`, `tools-export-large-rtg.py`): eight-sided shield jacket on a plinth, fourteen radiator fins on four posts, lifting cap, copper FE terminals, hinged loading hatch with intake funnel, hazard placards, a blank nameplate, a service ladder.
- **RTG heat.** A **heat-only** switch on both RTGs (screen button, `heat_only` signal, `setHeatOnly(boolean)` on the computer face; the Large RTG's signal also takes a command): it stops the FE and nothing else, because the thermocouples convert only about 5 % and the body always makes the heat. The `HeatOnly` save key is written only when on, so every old RTG saves and loads as before. The small RTG's signal list still has no command (its test pins that), so there the signal is read-only.
- **Cold Sweat (optional).** Each RTG cell is a Cold Sweat `BlockTemp` (API of 2.4.3 and later, against 2.4.4): 0.15 Cold Sweat units (3.75 C) per kW of thermal power at the block, faded by Cold Sweat with distance, capped at 1.5 units; an empty RTG adds nothing; the Large RTG's twelve cells share its watts. `cold_sweat` is `optional` in `neoforge.mods.toml`, only `dev.bwr.mod.compat.coldsweat` names its types, `BwrMod` loads it only when Cold Sweat is present, it is `compileOnly` (never bundled, GPL-3) from Modrinth's maven and is on the dev runtime only with `-PbwrColdSweat`. Mekanism heat is **not** done (the capability is not in Mekanism's API jar and its model is temperature, not watts; RTG.md has the reasons).
- **Ingots and blocks.** `<isotope>_ingot` and `<isotope>_block` (nine ingots) for strontium-90, caesium-137, americium-241 and cobalt-60; the small RTG takes either in a well, the Large RTG takes both. The separator's capsules (250 g, to the grain as before) and `bwr:cobalt_sample` keep their ids and craft one to one into the ingot of the same mass, so no save, inventory or pinned test changes; the capsules and the sample leave the creative tab, the ingots and blocks join it. Carried, ingots and blocks are gamma sources through the existing data-map path; placed blocks are inert storage. RTG.md documents the item naming, data-map lines and three recipe shapes so the next isotope follows the pattern.
- **One pattern with the reactor-bred isotopes.** The ingots and blocks of strontium, caesium, americium and cobalt use the bred isotopes' `BredIsotopeItem`, `BredIsotopeBlockItem` and `BredIsotopeTooltip` (no third pattern); the separator capsules and the cobalt sample print the same tooltip, which also fixes the old `%.0f` grams that showed milligram ingots as 0 g. The tooltip no longer says "bred in a reactor". The Large RTG takes every fuel in the list, polonium-210, plutonium-238, actinium-227 and thulium-170 included (a test loads one of each kind and checks its grams and heat).
- **Cobalt-60.** `bwr:cobalt_sample` (what a cobalt target makes) is RTG fuel: 18.9 W/g (4.19e13 Bq/g x 2.823 MeV), half-life 5.2714 y, photons 16.8 W/g, lead HVL 1.2 cm, **474 g per sample** (the breeding targets' calibration: a finished cobalt target is 4.7 % of 10 kg of cobalt-59, BREEDING-TARGETS.md; retune the data-map line). Its comparator recipe is unchanged and tested. **Carrying one reads about 6,300 Sv/h at 1 m** (8.96 kW of heat each), because half a megacurie is a real source.
- **Supply, honestly.** Sr-90, Cs-137 and Am-241 only come from reprocessing waste (0.11 kg of Sr-90 a waste item: 1,000 kg is about 9,100 items); Co-60 comes from irradiating cobalt targets. The separator's yields are unchanged.
- **Edited, shared files:** the build.gradle namespace list (`bwrlargertg`) and its test-template exclusion, `TurbineModelCheck`'s stop line (`bwr.largeRtgClientCheck`), `GuiScreenCheck` (four scenarios), `DeviceResolver` (the Large RTG resolves to its controller), `AcceptanceTests` (the new class), the `fuel.json` guide, `RTG.md`, `lang`, tags and recipes. No existing assertion was edited.
- **Not done, and why.** Block to capsule or to cobalt sample: an unpack recipe would make those pinned "not craftable" items craftable (`everyCraftableItemHasARecipeAndTheRestDoNot`, `NO_RECIPE`), so the conversion is one way, capsule to ingot; ingot and block are fully two-way.
- **Checks.** `:core:acceptance` `LargeRtgStoreTest` (7 tests); GameTests `bwrlargertg` (`LargeRtgTests`, 15 tests, with and without CC:Tweaked; with `-PbwrColdSweat` they run the real Cold Sweat `BlockTempModifier`); `-PbwrLargeRtgClientCheck` (pictures in `run/turbineModelCheck/large-rtg-check`) and the existing `-PbwrRtgClientCheck`; GUI check scenarios `large-rtg`, `large-rtg-heat-only`, `large-rtg-empty`, `large-rtg-absent`.

## Unreleased: reactor-bred isotopes

Six new irradiation targets breed the isotopes of the elements that lie with uranium and thorium ore, from neutron cross sections, with a place for each in the RTG and the thorium fuel route. Write-up,
numbers and references: [BREEDING-TARGETS.md](BREEDING-TARGETS.md).

- **New target rods** (new `CoreInsert.Kind`s, appended so the item picture and saves keep their ordinals): bismuth (96 h), thorium (168 h), depleted uranium (240 h), neptunium (120 h), radium (168 h) and thulium (48 h), made
  from a casing and the ore element (no charge item). They are used in the hand like the cobalt, lithium and silicon rods and give one product and the casing.
- **Products.** Polonium-210, plutonium-238, actinium-227 and thulium-170 as `<isotope>_ingot` and `<isotope>_block` (nine to one), each with entries for the ingot and the block in the RTG fuel data map `bwr:rtg_fuel`;
  `uranium_233_ingot` (fuel) and `neptunium_237_ingot` (the plutonium-238 route's feed) without. Carried, each is a gamma source by its own photon power: polonium hardly at all, actinium-227's daughters and thulium-170's
  bremsstrahlung strongly.
- **Physics.** `dev.bwr.core.fuel.BreedingTargets` gives the existing 72-hour cobalt target a meaning (one 18-month cycle, so a game hour is 182.5 hours at 3e13 n/cm2/s) and works every product's mass from the capture
  and burn-up chain with a matrix exponential. Each ingot is one finished rod's yield, kept within 3 % by a core test and a GameTest.
- **Thorium fuel.** A bred U-233 ingot can stand in for the enriched-uranium driver in the fuel fabricator's thorium targets (8, 12 or 19 ingots and 4 thorium for the 2, 3 and 5 % grades, no depleted uranium back), as an
  alternative inside the existing three steps; the existing costs, selector and saves are unchanged. The U-233 store is saved as `U233Ingots` only when it holds some.
- **Ore.** Uranium ore and deepslate uranium ore drop a bismuth nugget (5 / 6.5 / 8 / 10 %, one or two, skipped by Silk Touch), the Bi-Co-Ni-Ag-U vein companion. A polonium trace in the ore was left out and why is in the write-up.
- **Guide.** A new chapter, **Breeding and decay**, shows the path from what you put in to what you get, with half-lives and item icons, for every isotope route in the mod and the uranium and thorium decay series.
- **Data lists extended** (new non-craftable items): `NO_RECIPE` in tools-audit-recipes.py (`uranium_233_ingot`, `neptunium_237_ingot`, `bismuth_nugget`); `UraniumOreTests.onlyListedByproducts` (the bismuth nugget).
- **Checks.** Core `BreedingTargetsTest`; GameTests `bwrbreeding` (`BreedingTargetTests`, batch `breeding`).

## Unreleased: uranium ore byproducts

Mining uranium ore now gives the byproducts real uranium ore comes with, the way thorium ore does ([THORIUM.md](THORIUM.md#byproducts-of-mining-uranium-ore), owner-approved).

- **Drops.** On `bwr:uranium_ore` and `bwr:deepslate_uranium_ore`, each its own chance for Fortune 0 / I / II / III, skipped with Silk Touch: Lead Nugget (1 to 2; 10 / 13 / 16 / 20 %), Radium (1 / 1.5 / 2 / 2.5 %), raw copper (4 / 5 / 6 / 8 %) and any item a mod tags `c:raw_materials/vanadium` (4 / 5 / 6 / 8 %; nothing drops while no mod has one). The main drop (one raw uranium, the vanilla Fortune bonus) and Silk Touch giving the block alone are unchanged.
- **Test changed, with the owner's approval.** In `UraniumOreTests.oresDropRawUraniumWithFortuneAndThemselvesWithSilkTouch` the two single-drop assertions now say the raw uranium is there with the same counts and that any other drop is one of the listed byproducts; Silk Touch is still exactly the ore block. New: `uraniumByproductsAreChanceBasedFortuneHelpsAndSilkTouchSkipsThem` (6,000 mines for each chance, with and without Fortune III, and with Silk Touch).
- **Radium** is now a byproduct of both ores (the RTG and the Ra-Be source use it, from the isotope work).

## Unreleased: main steam valve remodel, one relief valve

The MSIV and the relief valve have new models, drawn from Columbia's FSAR figures. There is now one relief valve. Write-up and references: [MAIN-STEAM-VALVES.md](MAIN-STEAM-VALVES.md).

- **MSIV (`bwr:msiv`).** The model follows FSAR Fig 5.4-9: a Y-pattern globe body, a bolted bonnet and packing, four spring guide shafts with closing springs, a hydraulic dashpot with speed control valves, an air cylinder with the accumulator on top, a solenoid pilot control unit, two position switches and an OPEN/SHUT stem scale. **The model now shows the valve's position:** open (or stroking), the stem, spring seat member and pointer are raised and the springs compressed. Same footprint, ports, flanges and behaviour. It has fewer triangles than before. One-block legacy MSIVs keep their cube models.
- **One relief valve.** `bwr:ads_relief_valve` is now named **Safety Relief Valve** and is the only relief valve: the piped one, with a side steam inlet, a downward discharge, divisions, redstone and CC. Its ID, saved data, ports, `bwr_safety_relief_valve` CC type and steam flow are unchanged. Its new model follows FSAR Fig 5.2-8, Columbia's direct-acting spring safety relief valve: a body with an outlet angled down to the tailpipe, a spring bonnet, a set pressure head and LVDT, a lever and pneumatic actuator, solenoid valves A, B and C, and an accumulator. **Open, its lever and actuator rod are raised.** The inlet flange is now centred on the face where the pipe meets it; the old one sat 0.15 block low.
- **The old cube is retired.** `bwr:safety_relief_valve` is renamed **Safety Relief Valve (old)**. Its recipe and creative-tab entry are removed (owner, 2026-10-25). Placed cubes and stored items still load and work, and its models are untouched. The two recipes that used the cube now use its ingredients: the Safety Relief Valve takes a piston and a reactor vessel, and the Division cabinet takes two pistons.
- **Wording.** The guide page "Relief valves and ADS" is now "Safety relief valves", and it says plainly that opening relief valves is how you depressurise. ALPHA-HARDWARE.md and the README were updated to match. The Division cabinet, the `bwr_ads` CC type and the registry IDs are unchanged.
- **Data lists changed for the retired cube (owner-approved):** `NO_RECIPE` in tools-audit-recipes.py, `RETIRED_BLOCKS` in tools-audit-assets.py, and the "not crafted" list of `FuelFabricatorItemRouteTests.everyCraftableItemHasARecipeAndTheRestDoNot`.
- **Checks.** New GameTests in `ReliefValveMergeTests`:
  - `oldCubeReliefValveStillLoadsAndWorks`: saved state, six steam faces, redstone, discharge, and no recipe;
  - `safetyReliefValveStillRelievesToThePool`: reactor steam through the side inlet into a suppression tank;
  - `msivUpperCellsShowTheStroke`.

  New in-game pictures with the pipes attached: `-PbwrSteamValveShots=<label>` (SteamValveShots). The inventory icons are re-rendered.

## Unreleased: condensate tank and recirc nozzle remodel

A visual remodel of the condensate storage tank and of the vessel's recirculation outlet and inlet nozzles. Block ids, box sizes and scaling (3 to 15 across, 3 to 24 high), cells, collision, controller, capacity, port cells, faces and flange centres are unchanged, so pipes join exactly where they did and old saves load as before. Write-ups and references: CONDENSATE-TANK.md ("Remodel") and RECIRC-REMODEL.md ("The vessel's recirculation nozzles").

- **Condensate storage tank.** Drawn after an outdoor welded steel CST (Columbia: two 400,000 gal carbon-steel tanks with heaters, inside a concrete dike on a reinforced mat, FSAR 9.2.6 and 3.8.5.1.5). The model has:
  - a concrete dike mat and curb, and a ringwall;
  - shell courses with girth and staggered vertical seams, and a top angle;
  - a cone roof with radial laps, a gooseneck vent and a roof manway;
  - a perimeter handrail, and a caged ladder with a grating roof landing;
  - weld-neck nozzles on reinforcing pads, a bolted shell manway, an immersion heater, a float-and-tape gauge board and an overflow downcomer;
  - anchor bolt chairs, the stencilled name, a tank plate and a radiological posting.
  Parts that should keep their real size stay full size on any tank. The tank uses one texture atlas (`make_cst_atlas.py`) and headless Blender (`build_cst.py`). The largest tank is 25,970 triangles (was 12,854), one cached mesh per size as before. The plain casing block that old single-block tanks use is unchanged.
- **Recirculation nozzles on the formed v2 vessel.** The outlet is a broad set-in forging with a big blend radius (FSAR Fig 5.3-5). The inlet has the Fig 5.3-6 fillet and taper. Each has an Inconel butter band, a stainless safe end tapering to the pipe, and the safe-end weld, with a structural weld overlay on the outlet. The pipe bends from the nozzle's radial line onto the port face. The flange face is where the old water nozzle's was, banded blue (outlet, to the pump suction) or teal (inlet, from the discharge). An inlet's thermal sleeve stands in from the inner wall. The vessel now tells the client which ports are recirculation ports: two kinds are added to the end of `VesselAppearance.PortKind`, so the older codes keep their numbers. Wrongly faced ports still show as plain blocks.
- **Unformed port blocks.** `recirculation_outlet` and `recirculation_inlet` are now the vessel shell cube with the nozzle set flush into its face: forging, butter, safe end, a bolted flange and the port colour band. They replace the flat decal.
- **Checks.**
  - New GameTest `recirculationportsaretypedonthewire` (RecircNozzleTests).
  - New picture check `-PbwrTankNozzleShots=<label>` (TankNozzleShots): the 3 x 3, 5 x 6 and 15 x 24 tanks with piped flanges, a formed 17 x 17 x 28 vessel with piped recirculation nozzles, and a loose wall of the port blocks.
  - Blender previews: `art/models/condensate_tank/preview_cst.py` and `art/models/reactor_vessel_v2/preview_recirc_nozzles.py`.

## Unreleased: thorium ore and thorium fuel route

The mod has its own thorium now, mined the way its uranium is, and a survival route from it to the thorium fuel that was creative-only. Write-up, numbers and references: [THORIUM.md](THORIUM.md).

- **Blocks and items.** Thorium Ore and Deepslate Thorium Ore (iron pickaxe, hardness as iron ore), Monazite Sand (a falling placer block, any shovel), Block of Raw Thorium, Block of Thorium; Raw Thorium, Thorium Ingot, Thorium Nugget. Smelt or blast the raw metal or either ore; nine to one both ways for ingots, nuggets and both blocks. Tags `c:ores/thorium`, `c:raw_materials/thorium`, `c:ingots/thorium`, `c:nuggets/thorium`, `c:storage_blocks/thorium`, `c:storage_blocks/raw_thorium`. Textures are made by `tools-make-thorium-assets.py`.
- **World generation, the uranium way.** A second, independent set of long winding veins (the feature `bwr:thorium_vein` shares the vein geometry of `bwr:uranium_vein`, with its own salt), small blobs, and monazite sand placers in beach and river sand. Only in newly generated chunks, writes only inside its own chunk, never loads or force-loads a chunk, changes no noise setting. Server option `thoriumOreGeneration` (default true, `bwr-gameplay.toml`), separate from `uraniumOreGeneration`. Four biome groups: highland (granite mountains, the richest), placer (beaches and rivers), lowland (everything else on land) and ocean; every overworld biome is in exactly one. Veins are rarer than uranium's (30 % of cells in the highlands against 50 %); thorium is more abundant in the crust, so its blobs are a little more common.
- **Byproducts.** Mining thorium ore (not with Silk Touch; Fortune raises every chance) also gives Rare Earth Concentrate (30 %; with an iron ingot it makes a flint and steel), bone meal (monazite is a phosphate), lead nuggets (the end of the decay series), a trace of raw uranium (2 %) and, rarely, Radium (1 %). Monazite sand gives rare earths and iron nuggets (the ilmenite that lies with it). New items: `bwr:rare_earth_concentrate`, `bwr:lead_nugget` and `bwr:lead_ingot` (tagged `c:nuggets/lead` and `c:ingots/lead`, so Mekanism, Immersive Engineering and others take them through their tags), `bwr:radium` (a sealed capsule of about ten milligrams of radium: a gamma source when carried, read by the survey meter, the way spent nanofilter elements are; this mod gives it no use).
- **The fuel route.** Three more steps on the fuel fabricator's selector, after the MOX grades: Th 2, 3 and 5 % (`thorium_lean`, `thorium_standard` making `thorium`, `thorium_rich`). Thorium (ingots or raw), natural uranium and 4 steel in; the machine enriches the fissile driver (19.75 %) itself with the uranium route's mass balance (`ThoriumMassBalance` in the core), and the depleted uranium comes back: 4 thorium and 18 uranium, 4 and 26, 3 and 43. Fuel physics unchanged: the grades and their 2, 3 and 5 % driver fractions are the existing fuel types. The input slot takes uranium and thorium into separate stores, as the MOX route does with plutonium and depleted uranium.
- **The selector.** `FuelItemRoute.LADDER` is still the uranium ladder and the MOX grades (10 steps, same indexes, and `CMD_SELECT` still refuses index 10 and above, as the existing test asserts); the screen counts in the new `FuelItemRoute.SELECTOR`, the ladder followed by the three thorium grades, and picks a thorium step with a new menu command `CMD_SELECT_THORIUM` (an index into `THORIUM_TARGETS`, range-checked). If the owner prefers the thorium steps inside `LADDER`, two existing assertions in `FuelFabricatorMoxTests` (ladder size 10, and index 10 refused) would have to change.
- **Saves.** One new fabricator key, `ThoriumIngots`, written only when it holds something; an old fabricator loads exactly as it did. No new chunk data: the ore blocks are ordinary blocks. Removing the mod leaves unknown blocks that load as air.
- **Radiation.** Thorium items are weakly radioactive in life, but the mod gives uranium items no dose, so thorium items have none either; radium is the one new radioactive item.
- **Checks.** Core `ThoriumMassBalanceTest`; GameTests `bwrthorium` (`ThoriumOreTests`, `-PbwrOnlyThorium`); `:mod:runWorldgenCheck` now also generates the thorium dimensions (`bwr_check:flat_th_*`) and proves old chunks are untouched with the option off and on; client pictures `-PbwrThoriumClientCheck` (`ThoriumClientCheck`) and the GUI check's `fabricator-thorium` scenario.
- **Data lists edited.** `tools-audit-recipes.py` NO_RECIPE (`thorium_ore`, `deepslate_thorium_ore`, `monazite_sand`, `rare_earth_concentrate`, `radium`), `tools-audit-assets.py` WORLD_MADE_BLOCKS (the three blocks) and the `notCrafted` list of `FuelFabricatorItemRouteTests.everyCraftableItemHasARecipeAndTheRestDoNot` (the same five items), for the same reasons as the uranium ores. No existing assertion was changed or weakened.
- **Uranium-ore byproducts** were first held back by an existing single-drop assertion and then added with the owner's approval: see "Unreleased: uranium ore byproducts".

## Unreleased: RTG and isotope extraction

Fission-product waste is no longer a dead end. An **Isotope Separator** makes sealed strontium-90, caesium-137 and americium-241 capsules from it, and an **RTG** turns capsules into a small steady supply of FE. Write-up, the numbers, the sources and the radium hook: [RTG.md](RTG.md).

- **Isotope Separator** (`bwr:isotope_separator`). A machine of its own, so the reprocessing plant, its slots, screen, peripheral and saves are untouched. One waste item (5 kg) in 5 seconds and 10,000 FE; out come 109.7 g of Sr-90, 181.3 g of Cs-137 and 75 g of Am-241 (as fixed-point grains, released as 250 g capsules) and the rest as `bwr:stripped_waste`, which it refuses to take again. Mass is conserved to the grain.
- **RTG** (`bwr:rtg`). Six wells; thermal power is the sum of grams x specific power x 2^(-t / half-life), electricity is 5 % of that, FE per tick is watts over 13.4 (the mod's one conversion) times `rtgOutputMultiplier` (default 1: no hidden boost). Six strontium capsules make 69 W, 5.15 FE/t. Decay is real and negligible in game time (0.027 % per 100 hours for Sr-90). Pushes to loaded neighbours every 5 ticks and answers a pull; nothing is produced for unloaded time.
- **The fuel list is data.** The item data map `bwr:rtg_fuel` (`data/bwr/data_maps/item/rtg_fuel.json`) holds each item's specific power, half-life, grams, photon power and wall; an item is RTG fuel exactly when it has an entry. RTG.md has the one line that adds radium when the thorium branch is merged.
- **Dose.** A carried capsule is a gamma source at the player (Cs-137 about 54 Sv/h at 1 m, Sr-90 1.3 Sv/h, Am-241 7 mSv/h); the RTG is a point source behind its housing (1e-4).
- **Items and recipes.** Four new items (`strontium_90_capsule`, `caesium_137_capsule`, `americium_241_capsule`, `stripped_waste`), recipes for both blocks, and a second route to the Am-Be startup rod: an irradiation casing and an Am-241 capsule (the echo shard recipe stays). `tools-audit-recipes.py`'s no-recipe list gains the four items and its reason for `fission_product_waste` no longer says nothing uses it.
- **Readouts.** A screen for each machine, Networking Wrench / panel signals for the RTG (`thermal_power`, `electric_power`, `dose_rate`, `capsules`, `buffer`, `fuelled`), CC peripherals `bwr_rtg` and `bwr_isotope_separator`, a guide page, a new server option `rtgOutputMultiplier`.
- **Not added:** Pu-238 (it needs neptunium-237 from reprocessing and a target rod; RTG.md says why) and a radium-beryllium rod (documented, not built).
- **Saves.** New blocks only; the plant is unchanged and loads as before. New NBT keys are written only when non-empty.
- **Checks.** `:core:acceptance` (`RadioisotopePowerTest`, `IsotopeExtractionTest`); GameTests `bwrrtg` (`RtgTests`); client pictures `-PbwrRtgClientCheck` (`RtgClientCheck`).

## Unreleased: new fuel elevator and swimming dose

Three things for the spent fuel pool and the flooded reactor well, at the owner's request. Write-ups: [SPENT-FUEL-POOL.md](SPENT-FUEL-POOL.md#the-new-fuel-elevator) and [RADIATION.md](RADIATION.md#water-between-you-and-the-source-and-swimming).

- **New Fuel Elevator** (`bwr:new_fuel_elevator`). A real plant lowers new fuel into the pool with a new fuel elevator (NRC, U.S. EPR FSAR Chapter 9 fuel handling, ML14209A587 and ML13220A776; the APR1400 fuel handling paper, KNS 17S-060). Stand it on top of the rim of a formed pool wall (a straight run, not a corner); a guide frame hangs down the inside of the wall under the water. Put an assembly in its hatch by right-click, hopper or pipe (four wait) and the carriage takes it down the guide, over the racks and sets it into the first free cell. The pool is found when it is asked, never scanned for. It **refuses with the reason** when it is not on a formed pool, every cell is taken (counting those promised to waiting assemblies) or the water is under 1.5 m over the handle at the height the assembly crosses the racks (the bridge's hoist limit). **RAISE COOLEST** (screen, `raise()`, `raiseCell(n)`) brings the assembly that has cooled longest back to the hatch on command; an assembly out of the water is a radiation source exactly as the bridge's grapple is, computed from the trip's clock when an instrument asks. No ticker: a trip is two scheduled block ticks (the assembly changes hands with the rack; the carriage is home), and the client draws the carriage from the trip's start tick and legs. Fuel is never lost: a cell taken meanwhile sends the assembly to the next free one, then the landing slot, then the ground.
- **Model, screen, computers.** Headless Blender (`art/models/new_fuel_elevator/build_models.py`, 1,376 triangles in four parts): a yellow stand with a deck, hatch and handrail, a gantry with hoist motor and cable drum, a control cabinet, a guide frame tiled down the wall, a blue carriage on a rope with jaws round the handle. A screen (pool, free cells, water over the handle against the 1.5 m needed, carriage state and time left, waiting assemblies, the assembly at the hatch, the coolest in the pool, the last refusal; buttons RAISE COOLEST, TRY AGAIN, TAKE FROM HATCH), CC peripheral `bwr_new_fuel_elevator`, an item handler on every face (hatch slots 0 to 3, landing slot 4), a guide page (and "Swimming in the pool" in the radiation chapter), language entries, recipe and loot table.
- **Water shielding along the line.** The water in front of a source is the water on the line between the dose point and the source. A point **above the water** reads exactly as before (the water straight over the rack top, or over the top of the fuel of an open vessel). A point **in the water** gets only the water between it and the source, with no credit for the water above it: the same function (`DoseModel.waterPathM`) for the pool's racks and for the open reactor with a flooded well, whose shaft cells now read as looking down the open head. A diver 1 m over a rack of four just-discharged assemblies reads about 470 Sv/h (the old rule gave 0.3 mSv/h); a swimmer at the surface over 3 m of water about 1.8 mSv/h; in a flooded well a metre over the fuel of a just-scrammed core reads about 6.9 kSv/h against nothing at the surface.
- **Swimming hurts.** A player in the water of a formed pool or of an open flooded vessel is sampled every 10 ticks (staggered, no ticker, no scan: a player not in such water costs one integer test a tick). The sample is the survey meter's own reading, adds the **personal dose with no meter carried** (a carried meter records its own, so it is not counted twice), applies the contamination effects at their thresholds (hunger from about 150 mSv/h, weakness and poison from 1.5 Sv/h, wither from 15 Sv/h, wither III from 150 Sv/h) and announces the first high dose ("You feel a burning warmth on your skin ...", with the reading and the dose so far). Creative and spectator players are exempt. The effects are keyed to the rate, as the contamination effects are; nothing is done for the total dose.
- **Old worlds.** Right-clicking a rack or the controller with an assembly, hoppers and pipes into the controller, and every pool save are untouched; the new keys (`Hatch`, `Landing`, `Trip`, `Inward`, `FloorY`) are written only when non-empty.
- **Tests.** Core `DoseModelTest` 09 to 12 and `FuelElevatorMotionTest`; GameTests `NewFuelElevatorTests` (12) and `ImmersionDoseTests` (5) in `bwrpool`; client pictures in `spent-fuel-pool-check/` with `-PbwrSpentFuelPoolCheck` and six elevator screens in `-PbwrGuiScreenCheck`.

## Unreleased: RWCU stainless steel nanofilter

The RWCU filter-demineralizer is now the **RWCU Stainless Steel Nanofilter**, and it works at full reactor temperature. Write-up, the numbers and the references: [RWCU.md](RWCU.md).

- **One unit, no temperature trap.** Same block id (`bwr:rwcu_filter_demineralizer`), so worlds load. The resin's 140 F limit, its permanent damage and the release of what it held are removed from the core, the block, the readouts, the docs, the guide and the language file. The element takes **both crud and dissolved ions** at any water temperature (100 g of ions, 200 g of crud, 95% and 90%). That is a game simplification: in real plants dissolved ions need ion exchange, and a "nanometal particle" BWR technology is not something the research found. What is real is sintered stainless steel (RWCU septa rated to 1000 F, Graver and Porvair) and a titanium-oxide adsorber for cobalt at 285 C (Hitachi, 1984); see RWCU.md REFERENCES.
- **The exchangers are optional.** The regenerative and non-regenerative exchangers may be left out of the loop, in this order: regenerator, cooler, nanofilter. With neither, the loop runs at reactor temperature and the vessel loses **no heat** (4.96 MW with both at full cooling); a cooler alone takes its duty, a regenerator alone recovers nothing. A regenerator used on its hot side must be used on its cold side. The blowdown tee may hang off any hop ahead of the nanofilter, and the blowdown now arrives at the temperature the water has at the tee (the tank does not flash it).
- **Pressure drop.** The crud on the fibres costs pressure: 5 psid new to 100 psid full of crud at 60 kg/s, quadratic in flow, added to the loop's system curve. A full element costs the loop about a fifth of its flow (49 of 60 kg/s). Ions add none.
- **Items.** The element is the old resin item (`bwr:demineralizer_resin`, now "Stainless Steel Nanofilter Element": new texture, new recipe of iron ingots, iron nuggets and iron bars); the spent one is `bwr:spent_resin`, now "Spent Nanofilter Element", still radioactive with the activity of the crud it caught. Ids kept so recipes, inventories and the "not crafted" list of the recipe test need no change.
- **New model.** Headless Blender: a vertical stainless filter housing with a bolted top closure (twelve studs, gasket, dished cover, lifting eye), inlet and outlet flanges, a differential-pressure gauge with impulse lines, a nameplate and a capped drain. The readout screen shows element used, crud and ions held, pressure drop, flow, temperature, efficiency, activity and gamma.
- **Readouts.** Networking Wrench / panel signals for the nanofilter (`element_used`, `crud_held`, `ions_held`, `pressure_drop`, `flow`, `water_temperature`, `activity`, `gamma_power`, `element_present`, `spent`, `filtering`), CC peripheral fields `elementUsed`, `pressureDropPsid` and more (the resin's `resinUsed`, `heatDamage` and `overTemperature` are gone), the pump's `filterPressureDropPsid`, a guide page, and RWCU.md.
- **Saves.** The `Bed` key and its five numbers are unchanged: a saved resin bed loads as an element with the same ions, crud and activity (the old heat damage is dropped). A new element writes nothing.
- **Tests that changed with the resin.** The existing tests of resin damage were rewritten, with the owner's approval, to say the opposite (core `ReactorWaterCleanupTest` test04 and test07, GameTest `RwcuTests` hot demineraliser test, now `aNanofilterOnHotWaterWorksAndNothingDamagesIt`). New: core `NanofilterTest`, and GameTests for the hot loop without exchangers, each exchanger alone, hot blowdown, pressure drop and flow, the spent element and an alpha.54 bed.

## Unreleased: multiblock RHR heat exchanger

The RHR heat exchanger is now a full-size machine. One `bwr:rhr_heat_exchanger` item places a vertical shell-and-tube unit 3 wide, 8 high and 3 deep (controller at the centre of the base), one model and one block entity, the way the pumps work. Write-up, port table and references: SUPPRESSION-BASIN.md, "Full-size exchanger".

- **Real form.** Columbia's RHR exchangers are vertical inverted U-tube units (FSAR Table 6.2-2), 7,641 ft2 of tubes each in a 30 ft bay. The model: a 1.5 m lagged shell on four lugs and a braced steel frame, the channel head and bonnet at the bottom with a bolted tubesheet joint (32 studs), an ellipsoidal top head with a vent valve and lifting padeyes, a relief valve on the shell, drain valves, thermometers and pressure gauges on every nozzle, a generic nameplate. Headless Blender (`art/models/suppression/build_hx_multiblock.py`), cell-split by `tools-export-hx-multiblock.py` with the pump exporter's clipping.
- **Ports.** The same colours and faces as before (red tube inlet on the front, amber behind, blue shell inlet to the left, cyan shell outlet to the right), now on four flange cells: red and amber on the channel head one block up, blue low on the west cell, cyan six blocks up on the east cell. Right-click, CC and the Networking Wrench work on any cell and address the controller. Placement refuses a blocked footprint; breaking any cell removes the unit and returns one item.
- **Saves.** Exchangers already placed stay working single blocks: same id, model, flange faces and saved data, never expanded into neighbours. Pick one up and place it again to build the full-size unit.
- **Physics unchanged.** Same model, readouts and 27.1 MW rating for both forms; Columbia's FSAR figures (24.4 MW per loop for containment cooling, 12.2 MW shutdown cooling at 20 h) give no reason to raise it. The river loop and the Water Outlet Chute work through the full-size unit.
- **Checks.** GameTests `bwrhx` (HxMultiblockTests, 4 tests); client pictures `-PbwrHxMultiblockCheck` (HxMultiblockClientCheck). The inventory icon is re-rendered.

## Unreleased: cooling-tower remodel (hyperbolic tower and Columbia-style round tower)

A visual remodel of both cooling towers. Same blocks, sizes, footprints, ports, rotor pivots, physics and saves; the collision cells are the alpha.53 cells (the exported layout agrees with the old one to within 5e-6 block, and the same ports and 4,670 / 1,824 cells), so towers placed in any earlier version load and fit exactly.

- **Columbia's towers, checked against photographs.** Six round, precast-concrete, mechanical-draft towers with **six fans each** (36 cells), and they are **induced draft** (fans on the deck pulling air up through louvered drums), not forced draft as the brief said. The 19-fan layout of alpha.9 to alpha.53 did not match the photographs and is replaced by a ring of six at the old alpha.8 pivots. References and what each shows: COOLING-TOWERS-REFERENCE.md.
- **Round induced-draft tower** (17 x 8 x 17, 23,506 triangles, was 48,200): four louver tiers between slim precast columns over a dark backing, beam rings, a pale fan deck with six flared velocity-recovery stacks, a dark hot-water distribution ring, gearboxes, drive shafts through guard collars and motors on the deck, a stair tower with a walkway bridge and handrails. New fan rotor (eight twisted blades on a conical hub, 224 triangles); the rotation direction and speed rule are unchanged, the blades now pitch air upward.
- **Natural-draft tower** (25 x 36 x 25, 35,604 triangles, was 60,140): the same outside curve, now 28 pour lifts with stepped construction joints and five concrete tones, a separate inner skin, a thick ring beam on 80 diagonal X legs on pedestals, fill packs, spray mains and drift eliminators visible between the legs, a cold-water basin with a guard rail, a caged ladder with three landings, a rim handrail and red aviation lamps (rim and mid height).
- **LOD for the hyperbolic shell.** Beyond 80 blocks from the tower's bounds the renderer draws a 5,264-triangle stand-in (`natural_draft_tower_lod`, same silhouette and colours). Both meshes are cached once; switching is a per-frame distance test, no rebuild. The vapor and plume code is untouched.
- **Authoring.** Headless Blender: `blender --background --python art/models/cooling/build_towers_v2.py -- all` (kit: `tower_kit.py`), then `python tools-export-cooling.py` (now also exports the LOD mesh and no longer rewrites the recipes of existing towers).
- **Checks.** `CoolingModelCheck` also asserts the LOD mesh (present, under a sixth of the full quads, inside the bounds) and when it is chosen. New client check `-PbwrCoolingTowerShots` (CoolingTowerShotsCheck): 1920 x 1080 pictures of both towers with plume, the fans, the louvers, the legs, the rim and the distant LOD, asserting the LOD is drawn only far away.
- **Rectangular cell tower:** not added (the existing tower was already round; a rectangular one needs a new footprint).

## Unreleased: turbine-driven pump remodel

The feed pump turbine (`bwr:turbine_feed_pump`), RCIC (`bwr:rcic_twl`) and HPCI (`bwr:hpci_turbine`) have new models. They were built in Blender from reference photographs of real equipment, and replace the earlier models. Only the appearance changed:

- **Unchanged.** Block IDs, footprints (9 x 3 x 3, 5 x 4 x 5 and 7 x 5 x 5), controller cells, and the steam and water port cells, faces and flange centres. Pipes connect exactly where they did. Old-save layouts (`modern=false` Terry cells, the non-modern and compact feed-pump models, the retired single blocks) are untouched.
- **Feed pump turbine.** A condensing turbine with a bolted horizontal joint and an exhaust hood with a side exhaust nozzle. The steam inlet is insulated and feeds a main stop valve with a hydraulic actuator, then bar-lift control valves with a servomotor. There is a front standard with a hand trip. A coupling guard leads to a barrel feed pump with a studded end cover, centreline feet and conical top nozzles. The skid also has a lube oil console, oil risers to every bearing, a gauge board and nameplates.
- **RCIC.** A Terry turbine with a split drum casing and bolted side covers. Steam passes a trip and throttle valve with a handwheel and trip latch, then a spring-opened governor valve linked to the hydraulic actuator, with a mechanical overspeed trip. The exhaust has a check valve and a rupture-diaphragm branch. A multistage ring-section pump has a tangential discharge, a minimum-flow bypass and an oil cooler. The skid also has a barometric condenser with vacuum and condensate pumps, and a local panel where the CC modem goes.
- **HPCI.** An insulated Terry turbine with a hydraulic stop valve, a main pump with a booster-style suction casing, a gland seal condenser with an exhauster, an oil console, and a valve platform with handrails and a ladder.
- **Textures.** Generic gauge, nameplate, panel and warning textures (`tdp_*`), with no brand names. The inventory icons were re-rendered.
- **Pictures.** `:mod:runTurbineModelCheck -PbwrTurbinePumpShots=<label>` builds all three machines with pipes on every port and saves nine pictures. It fails if a pipe does not join its flange.
- **More.** Details, sizes and the REFERENCES are in [TURBINE-DRIVEN-PUMPS.md](TURBINE-DRIVEN-PUMPS.md).

## Unreleased: motor-driven pump remodel

The LPCS, HPCS, RHR/LPCI, motor feedwater and SLC pumps and the SLC boron tank have new Blender models that look like the real equipment. Same block IDs, footprints, controller cells, port cells, faces and flange centres: placed pipes still meet the flanges. No behaviour change. Write-up and references: [MOTOR-PUMP-REMODEL.md](MOTOR-PUMP-REMODEL.md).

- **ECCS pumps (LPCS, RHR, HPCS).** Vertical can pumps as at Columbia: a weather-protected vertical motor (finned frame, louvred top hat, drip cap, lifting eyes, terminal box and conduit, nameplate) on a guarded motor stand (shaft, spacer coupling, seal) and a cast discharge head on a sole plate, the can implied below the floor. RHR and HPCS drop their suction to a low can nozzle and send the discharge through a swing check valve up to the top flange; seal cooler, instrument rack, gauges, and an HPCS minimum-flow valve.
- **Motor feedwater pump.** Horizontal barrel pump with a bolted discharge cover, centreline feet and top nozzles on bolted spools; coupling guards, a variable-speed fluid coupling with oil cooler, and a ribbed motor with fan cowl.
- **SLC.** A triplex plunger pump (fluid end, three plungers, power frame, gear reducer, motor, Y-strainer, pulsation dampener, relief valve) and a heated tank with mixer, sparger, heater, level gauge and placard.
- **Old saves.** The one-block compact models and the older assembled cell sets are untouched; the held item and inventory icons show the new models.

## Unreleased: recirculation remodel

The external recirculation pump, the paired jet pump and the reactor internal pump have new models, built in headless Blender from the Columbia FSAR (BWR/5) and the NRC recirculation manual. They replace the earlier "DVSS" exterior and the supplied pack meshes. **Only the look changes:** same block IDs, footprints (5 x 10 x 5, 1 x 6 x 1, 2 x 5 x 2), controller cells, port cells, faces and flange centres, so existing pipes still meet the flanges. Sources, pipeline and budgets: [RECIRC-REMODEL.md](RECIRC-REMODEL.md).

- **Recirculation pump.** The model has these parts:
  - a long-radius suction elbow with flow taps, and a cast stainless casing on lugs, columns and two snubbers;
  - a 20-stud bolted cover with the two-stage seal cartridge housing, its stage pressure tubing and two local gauge racks;
  - an open motor stand with yellow expanded-metal coupling guards; the shaft and three-piece spacer coupling show through the holes;
  - an air-water-cooled vertical motor with cooling ribs, louvered coolers with cooling-water nozzles, a terminal box with conduit, an upper bearing housing, lifting lugs, a vibration probe, an oil sight glass and a rotation arrow;
  - generic nameplates and the blue suction and teal discharge bands.
- **Jet pump pair.** It follows the BWR/5 jet pump in FSAR Fig 3.9-4. The riser rises from a thermal-sleeve elbow toward the vessel wall into the transition piece, and two elbows run down into the drive nozzles. The two inlet mixers have suction bells, slip-joint clamps and tapered diffusers. A hold-down beam with bolt and lifting eyes, restrainer brackets, a riser brace and sensing lines complete it, all in bare stainless. The formed-reactor installed jets are unchanged.
- **RIP.** A diffuser wet end with impeller vanes, the stretch-tube stub, a 16-bolt mounting flange at the bottom-shell level, the hanging wet-motor casing with its bolted cover, cable entry, cooling-water nozzles and purge line.
- **Textures and budget.** One procedural 256 x 256 atlas, `textures/block/pumps/recirc_atlas.png`. The models are smaller than the ones they replace: 8,199 / 1,648 / 1,933 baked faces against 13,612 / 14,242 / 4,120. The recirculation pump cells render as cutout for the guard mesh.
- **Old saves.** The legacy 3 x 6 x 3 DVSS, the two-column jets and every one-cell compact model are untouched. Items show the new models.
- **Tools.** `art/models/recirc/` (`make_atlas.py`, `build_recirc.py`, `preview_cells.py`, `.blend` and source JSON) and `python tools-export-recirc.py [--check]`. `tools-export-dvss.py` and `tools-narrow-jet.py` now generate only the legacy and compact assets. The new client pictures are `:mod:runRecircRemodelCheck` (`RecircRemodelClientCheck`, 10 pictures at 1920 x 1080).


## Unreleased: reactor water cleanup (RWCU) and coolant chemistry

The reactor coolant now has chemistry, and there is a cleanup skid to take it out and to drain the vessel in a controlled way (see [RWCU.md](RWCU.md)). Nothing acts by itself: the pump starts when told, nothing isolates anything, and an overheated resin bed is a consequence with alarms on the readouts, not a trip.

- **Chemistry in the core.** Conductivity (uS/cm; a new plant or an old save starts at 0.10) and a crud inventory that picks up activity in the flux. Sources: feedwater (2 ppb ionic, 0.2 ppb iron), a constant small condenser in-leakage (the mod has no river-condenser tube-leak hook), and corrosion. An hour at rated power with no cleanup raises conductivity 0.10 to 0.20 uS/cm; RWCU running holds about 0.2. Above 1 uS/cm a slow structural wear is added to the pressure-boundary stress and above 0.3 a cladding wear counter runs; activated crud is a gamma source around the coolant lines (`RadiationSources`). INFO tab *Water chemistry* section and `reactor.getCoolantChemistry()`.
- **Skid.** RWCU Pump (FE, 60 kg/s at 130 psid, 120 kW, pump curve), Regenerative Heat Exchanger (effectiveness 0.92), Non-Regenerative Heat Exchanger (8 MW, needs cooling water piped to its side flanges), Filter-Demineralizer (100 g ionic and 200 g crud capacity, bed consumed in proportion to what it removes; Demineralizer Resin item to change it, the old bed is radioactive Spent Demineralizer Resin that the survey meter reads), and the RPV Bottom Head Drain Port (second suction, lowest two wall rows). The pump finds its circuit through the cached pipe survey; machines may stand against each other or be piped; valves in a hop throttle it; a crossed line moves nothing.
- **Physics on one enthalpy balance.** Draw and return are not flows of the vessel; the blowdown (a tee off the cooler outlet through a Motor-Operated Water Valve to a Condensate Storage Tank or condenser, split by the valve openings) is a liquid sink at the vessel's own enthalpy, kept off the drywell floor; the heat charged is `(F - B)(h_v - h_ret)`, which at 285 C and 60 kg/s is 4.96 MW with the regenerator and 62 MW without, all of it going to the cooling water. Lost cooling raises the resin water temperature; above 140 F the resin loses capacity and releases what it held. A suction port draws nothing once the water has fallen to its elevation, so the bottom drain drains the vessel to its port.
- **Pressure boundary.** The skid's suction (a recirculation outlet) and return (a water injection port) are line-break boundary through the existing port code: a break counts as a LOCA up to the nearest closed Motor-Operated Water Valve. The bottom drain port is a small-bore feedwater-class line (a quarter of a feedwater nozzle's opening, "RWCU bottom drain line").
- **Saves.** `Chemistry` is written only when not clean; old saves load clean; the demineraliser and cooler write only when not at their defaults; no existing block, id or key changed.
- Guide: a new chapter *Reactor water cleanup*. Tests: `ReactorWaterCleanupTest` (core) and `RwcuTests` (GameTests), client check `-PbwrRwcuClientCheck`. Models: `art/models/rwcu` and `tools-export-rwcu.py`.

## Unreleased: shell-and-tube RHR exchanger and river cooling water

The RHR heat exchanger is now a proper shell-and-tube exchanger, and its cooling water can come from a river or lake and go back to it.

- **New look, same block.** One full block, drawn in Blender (`art/models/suppression/build_shell_hx.py`, headless): a cladded horizontal shell on two saddles and a skid, a bolted channel head at each end, a flanged nozzle on every circuit with a red / amber (tube side) and blue / cyan (shell side) identification band, a vent with a small gate valve on top, a drain with a blind flange underneath, lifting lugs and a plain nameplate. It is still `bwr:rhr_heat_exchanger` with the same four flange faces: tube side (the RHR pool or vessel water) on the front and back, shell side (cooling water) left and right. A multiblock would have read longer but cannot take over exchangers that are already placed, so the single block it is; the design and the port mapping are in SUPPRESSION-BASIN.md.
- **Old worlds.** Nothing about the block state or its saved data changed: placed exchangers take the new model on load and every pipe stays connected. The ports table is pinned by a GameTest that writes the old layout out literally and loads a block entity as the plate exchanger saved it.
- **Water Outlet Chute** (`bwr:water_outlet_chute`). A concrete outfall (back flange, headwall, trough with baffle blocks, lip, guard rail), waterloggable, with a recipe, loot table and the usual tool tags. It is the outfall port with a stricter mouth: it discharges only while the cell in front of the lip (or up to four blocks below it, so it can drop onto the surface) is water with at least four source blocks within one block of it. A puddle, flowing water alone, lava and a solid block all fail; a chute that fails accepts no water, so the exchanger's hot buffer fills and cooling stops. The panel says why, and placing one that does not face water warns on the action bar. Returned water, flow, temperature, rise over the river and heat returned (MW) are on its panel, in `bwr_water_outlet_chute` for CC and in the Networking Wrench signals. Falling-water, splash and bubble particles go out on the fills that carry the water; there is no ticker. It makes and removes no source blocks.
- **River cooling.** Screened Water Intake, Makeup Water Pump, pipe to the blue flange, chute on the cyan flange: the shell flow is what the pump delivers (up to 1,500 kg/s), at the river temperature the intake already uses (13 C). The exchanger's effectiveness, UA and 27.1 MW rating are unchanged: at 1,500 kg/s the cooling water leaves about 4.3 C warmer. The intake counts the water it draws (`riverDrawnKg`, written only when it has drawn some) so that the water balance is checkable: what the intake drew is in the plant or went back through the chute.
- **Readouts.** The exchanger panel now shows tube-side and shell-side inlet and outlet temperatures, both flows, the duty in MW, the water held on each side and a warning when the shell outlet backs up. CC `getStatus()` adds `primaryInletC`, `secondaryInletC`, `secondaryOutletC`, `dutyMW`, `shellOutletBackedUp`; Networking Wrench signals `primary_inlet_temp`, `secondary_inlet_temp`, `secondary_outlet_temp`, `shell_outlet_backed_up`. Core: `RhrHeatExchanger.primaryInC()`, `secondaryInC()`, `hotFill()`.
- **Tests.** GameTests `ShellExchangerTests` (namespace `bwrshell`): `riverLoopCoolsRhrThroughShell` (real basin, RHR pump and exchanger; intake in one lake, chute into another; no shell flow means no heat; with flow the pool cools at the rated 27.1 MW, the river-side heat equals the pool-side heat, the water balances, no source block changes, then save and reload both ends mid-loop), `outletChuteRefusesWithoutOpenWater` (dry, blocked, puddle, flowing-only, lava, disabled, then open water over a drop, then the water goes away), `placedExchangersKeepTheirConnections`; core test `RhrHeatExchangerTest.test06`; GUI check scenarios `rhr-hx-river`, `rhr-hx-outlet-backed-up`, `service-outlet-chute`, `service-outlet-chute-blocked`; client check `-PbwrShellExchangerCheck` (pictures in `art/models/suppression/shell-exchanger/`). A new guide page ("River-cooled RHR exchanger") explains the loop. Changed assertions: none.

## Unreleased: turbine auxiliaries (lube oil, turning gear, generator cooling)

A turbine-generator now has the machines a real one has to have, and a train placed new does not run long without them (full design: [TURBINE-AUX.md](TURBINE-AUX.md)). Nothing acts on its own: pumps, the gear and the cooling run when you switch them on, and nothing trips or sheds load when an alarm reads.

- **Lube oil.** *Turbine Main Oil Tank* (20,000 L, AC auxiliary pump 75 kW, emergency DC pump 30 kW wired to a battery on the top face, an oil cooler on cooling water through two flanges) and the *Turbine Shaft-Driven Main Oil Pump* on the generator's free shaft end (no oil at standstill, full at 90 % speed). Oil pressure is the sum of what the running pumps deliver; the bearings need `0.4 + 0.6 x speed` of the rated flow. No oil while the shaft rolls above about 15 % speed: bearing metal passes 115 C in seconds and the shaft takes bearing damage in its existing damage counter. Hot oil (cooler without water) raises it slowly instead. The oil leaks and oxidises (doubling per 15 K over 50 C); refill with the new craftable *Turbine Oil Drum* (1,000 L).
- **Turning gear, built into the generator.** A switch on the generator screen, the `turning_gear` signal (Networking Wrench) and `setTurningGear` over CC. Draws 15 kW from the AC bus of the Main Oil Tank, rolls the shaft at 3 rpm, cannot be engaged above 10 rpm. A rotor above 120 C stopped under 1 rpm bows (full in 15 min at 285 C); the gear straightens it in 30 min; a bowed rotor shakes through the critical speed band and the roll-up damages it. Nothing engages it.
- **Generator cooling.** *Generator Cooling Skid*: stator water pumps (90 kW FE), a hydrogen casing (20 kg, craftable *Hydrogen Cylinder* 5 kg) and coolers on cooling water. Winding temperature follows the load squared; at rated load fully cooled it is about 102 C. Without power, water or hydrogen it rises: above 130 C the insulation takes damage (the generator's damage counter). You hold it down by cutting the load.
- **Readouts.** Power module screen card, skid screens with the pump switches, signals on every power module and skid, a new `VIBRATION` quantity (mm/s) for gauges, CC fields and `bwr_main_oil_tank`, `bwr_shaft_oil_pump`, `bwr_generator_cooling_skid`.
- **Old trains.** A train runs as it always did until it chooses a system: an oil tank or shaft pump brings in oil and bearings, a generator's gear switch brings in the bow, a cooling skid brings in the winding. A section a player places new from a fresh item is marked as needing all three (server option `turbineAuxRequired`, default **true**); sections already placed, and sections picked up and set down again, are not. No new key is written to a save unless it is not default.
- **No tickers.** The skids have none; the shaft train steps them once a tick and an unattached skid does nothing.
- **Models.** Three headless Blender models in the style of the water valve (3,892 / 5,628 / 2,056 triangles), two item textures, recipes and an inventory icon each.
- **Docs and guide.** TURBINE-AUX.md, SIGNALS.md, MODULAR-TURBINES.md, the first-plant guide chapter (page 4b) and the server options page.
- **Tests.** Core `TurbineAuxTest` (12 tests); GameTests `bwraux` (`TurbineAuxTests`, `TurbineAuxCcChecks`), each revert-proofed.

## Unreleased: vessel height matters (separators, carryover, chimney, inventory)

Until now the physics did not see the height of a built vessel at all: the steam dome (180 m3) and the water (220 t) were never set from the build, natural circulation was `0.366 P^(1/3)` in every vessel, and every vessel made perfectly dry steam. Now the height of the drawn vessel (`VesselProfile`) sets how well the separators dry the steam, how strong natural circulation is, and how much steam space and water the vessel holds. The 17 x 17 x 28 default keeps its calibrated reactor behaviour exactly; its steam is 0.86 % wet. Nothing acts automatically. Full write-up, sources and the table: [VESSEL-HEIGHT.md](VESSEL-HEIGHT.md).

- **Separators and dryer.** Steam moisture follows the separators' drawn height over true scale: exactly the design 0.1 % at true scale (a 17 wide vessel 39 high, not 43 as the brief guessed), 0.86 % at 28, 4.2 % at 20. Carryunder (steam in the downcomer water) rises the same way, 0.25 % to 0.44 % to 0.68 %. Columbia FSAR figures: moisture 0.25 % (s.5.4) and 0.30 % (s.10.1); the 0.1 % is the owner's dryer figure; the separator-outlet moisture and the carryunder design value are estimates, marked so.
- **Water level band.** The band is the real L3 540.5 in to L8 582 in. Above L8 carryover multiplies by `exp((L - L8) / 6 in)`; below L3 carryunder climbs toward 10 %. INFO tab "Level in band"; CC `getSteamSeparation()`.
- **Wet steam at the turbine.** Steam packets carry moisture. Only the excess over 0.1 % acts: work per kilogram falls by 0.77 x the excess (the water's Carnot-weighted share of the latent heat) and the blades wear. Wear is the turbine's existing damage counter (`ShaftDamage`; failure at 1.0), 8,000 tonnes of liquid over design to destroy: about 150 hours of rated flow at the reference. No new saved key.
- **Carryunder in the reactor.** It takes `x h_fg` off the core inlet subcooling and thins the downcomer, cutting natural circulation (`alpha / (1 - 0.5)` of the head). Relative to the reference build, so the reference is exactly as calibrated.
- **Natural circulation vs chimney.** `f(x) = sqrt(x / (0.5 + 0.5 x))` of the two-phase column (chimney plus 75 in for the core's own boiling column) over the reference's: 1 at the reference (bit for bit), 0.89 at 20 high, 1.09 at true scale, capped at 1.41. Two-pump-trip flow after 4 min: 19.3 / 23.4 / 27.3 / 29.1 / 33.2 % at 20 / 28 / 39 / 43 / 60.
- **Dome and inventory.** The water (192 / 220 / 258 / 285 / 400 t at 20 / 28 / 39 / 43 / 60) and the steam dome (96 / 180 / 294 / 294 / 294 m3) come from the build; the water at the same level, the metal heat capacity with it; a vessel resized while running keeps its water. Heat-up (100 MW, cold to 1000 psig) 36.6 / 41.7 / 48.8 / 53.8 / 74.9 min. The pressure capacity is only 13 % dome at the reference, so a small dome swings more but the water is most of it. The head missile's energy follows the dome: `HeadMissileTests` were re-baselined by the owner's decision (the ultra tier's test now uses a 16 high interior, 249 MJ, as the 9 x 9 x 12 fixture throws 180 MJ, not 250) and the shield-plug table in SEVERE-ACCIDENT-UPDATE.md. Blowdown volume reads the vessel's dome and water and is not scaled twice.
- **Readouts.** INFO tab "Steam quality and vessel height" (steam moisture, carryunder, separator height, chimney factor, level in band, core foot saturation rise); CC `reactor.getSteamSeparation()` and `steamMoisturePercent`, `carryunderPercent`, `levelBandPosition` in `getStatus()`; the turbine's `inletMoisturePercent`. Guide: "Taller and shorter vessels" page in the first-plant chapter.
- **Saves.** Nothing new except `WellInventoryScale` beside `WellInventoryKg` when the scale is not 1. The reactor save holds the level, so older saves keep their level in a vessel of any height.
- **APRM test margin.** `AprmCalibrationTests.miscalibratedAprms...`: "APRM 5 reads well high" was `error > 0.2 x heat balance` (3.014 against 3.108 at the squashed fixture's 15.4 % natural-circulation power, 21.4 % before); now `error > (baseline error) + 0.5 x 0.45 x baseline indication`, the gain's own shift, which follows the plant's power.
- **Tests.** Core `VesselHeightTest` (17 tests: moisture and carryunder vs scale, the band, wet steam work and wear, natural circulation at the reference bit for bit and vs chimney, subcooling, dome swing, inventory, heat-up, uncovery inventory, hydrostatic head, the table); GameTests `bwrheight` (`VesselHeightTests`): the builds match the core, a formed vessel takes its build, saved levels in any height, wet steam through a real HP turbine and from a vessel at a high level.


## Unreleased: reactor building crane, stud tensioner and head stand

The vessel head no longer comes off with a button, and the dryer and the separators no longer come out with the refuelling bridge. As at a real plant, a **stud tensioner** detensions the closure studs (the nuts come off, the studs stay in the flange), the **reactor building crane** (an overhead bridge crane on high runway rails) lifts the head on a strongback onto a **head stand**, and lifts the dryer and then the separators into the dryer/separator storage pool; the **refuelling bridge moves fuel only**. Reassembly runs the other way: head set, studs retensioned, vessel can hold pressure. Nothing moves unless a player, or their program, says so. Details: [BUILDING-CRANE.md](BUILDING-CRANE.md), [OPEN-REACTOR-UPDATE.md](OPEN-REACTOR-UPDATE.md), [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md).

- **Four new blocks.** *Crane Runway Rail* (two parallel runs, up to 40 apart, high on opposite walls), *Reactor Building Crane Console* (beside a rail; the pendant screen, the survey on demand, never per tick), *Head Stand* (the middle of the head's lay-down place; the head is as wide as the vessel, so the floor round it must be clear and between the rails) and *Stud Tensioner* (the hydraulic power pack, FE on any side). Recipes, lang, loot tables and tags as for the bridge's blocks.
- **The tensioner.** DETENSION / RETENSION / STOP on its screen (and `bwr_stud_tensioner`). One stud takes 4 s and 3,000 FE going loose, 6 s and 6,000 FE coming back to load: **296 s and 222,000 FE, then 444 s and 444,000 FE, for the 74 studs of the reference vessel**. The FE for a stud comes from a 24,000 FE buffer when the stud is begun; out of power it stops and says so. Detensioning needs the head on and 25 psig or less. The nuts are drawn coming off round the flange with the tool crawling on the stud in hand and a hose back to the pack.
- **The vessel's states.** The reactor counts the studs whose nuts are off (`StudsOff`, written only while it is non-zero with the head on). The vessel holds pressure only with the head on **and** every stud at load: with the head on and one nut off it leaks steam through the flange exactly like an open head (350 kg/s per psi) and water over the flange spills. The head is liftable only with every stud off; it cannot go back with the dryer or the separators out. `HeadAt` (the crane console or the stand holding the head) is written only while the head is off and not laid down.
- **The crane.** DRYER OUT/IN, SEPS OUT/IN and the new LIFT HEAD / SET HEAD on its screen and in `bwr_building_crane` (`queueHeadOut`, `queueHeadIn`, `queueInternalOut`, `queueInternalIn`, `getStatus`, `abort`, `resume`). Speeds: 0.5 m/s along the runway, 0.4 m/s across, hook 0.5 m/s empty and 0.25 m/s loaded, 5 s to hook on and 5 s to unhook. Measured in the GameTests (7 x 7 x 10 vessel, stand 11 m from its middle, rails 14 blocks over the floor): **head to the stand 108 s, dryer out 121 s, separators out 111 s, head back on 98 s**. Every rule of the alpha.53 lifts stays (dryer first, head off and flooded, fuel refused while either is installed, the radiation dose); the head also waits for both to be back. A runway too low to clear the vessel is refused with the height to build to.
- **The model.** A yellow industrial overhead crane in the style of the refuelling bridge model, in headless Blender (`art/models/building_crane/build_models.py`, 12 parts, 4,452 triangles, baked by `python tools-export-building-crane.py [--check]`): end carriages on the rail heads, two box girders with a walkway and an operator's cab (one mesh per span, plain section stretched, detailed one tiled), a trolley with hoist drum and gear motor, two falls of rope, an orange hook block, a strongback with spreader and four slings for the head, a lifting rig for the dryer and separators. The head is drawn hanging from it and then resting on its stand's cradle, from the vessel's own head mesh without its nuts.
- **Old saves.** A world with the head off stays off; the head counts as laid down and the crane (or the option) sets it back, no stand block needed. A world with the head on has every stud at load. New keys are written only when not default (the reactor's `StudsOff` and `HeadAt`, the stand's `Holding`, the tensioner's `Energy` and, mid-pass, `Mode`, `Reactor`, `Vessel`, `Ticks`, the crane's lift state and load).
- **Server option.** `instantHeadRemoval` (default **false**): off, the reactor panel's head button explains the procedure; on, it lifts and seats the head at once as before.
- **The bridge moves fuel only (owner decision: crane only).** The refuelling bridge refuses the dryer and separator lifts ("moves fuel only; the crane lifts ..."), from its console, the peripheral and a raw move. There is no option for the old behaviour (the lifts were never released). A load already on the bridge's hoist, from a world saved mid-lift in alpha.53, can still be set down on a pad or back into the vessel (the console's DRYER DOWN / SEPS DOWN buttons, lit only while the hoist holds that assembly, and the peripheral's calls), at any rail height; a lift in flight finishes. The alpha.53 tests of the bridge's lift (`VesselInternalsTests`, `VesselInternalsCcChecks`) now do the same lifts with the crane, and assert that the bridge refuses; a new test sets a load down from the bridge's hoist.
- **Computers.** `bwr_building_crane`, `bwr_stud_tensioner`, `bwr_head_stand`, and `reactor.getHead()` (`headOn`, `studTotal`, `studsTensioned`, `studsDetensioned`, `sealed`, `liftable`, `headAt`, `summary`). `getInternals().summary` now leads with the head's state.
- **Screens and guide.** The pendant and the tensioner screens are in `-PbwrGuiScreenCheck`. The guide's fuel chapter has "Opening the vessel" and "The reactor building crane" and a rewritten "Dryer and separators out"; the Server options chapter lists the two options.
- **Tests.** GameTests `bwrcrane` (`BuildingCraneTests`, `-PbwrOnlyCrane`): the full sequence; the head will not lift with one stud tensioned; the tensioner's time, energy and power; the bridge refuses the lifts; the instant option on and off; a lift saved on the hook and a pass saved half way; old saves and the default-key rule; the stand's placement rules; low rails; a console broken under a load; a bare stud leaks like an open head; no idle ticking and once-a-tick on the real scheduler; the computer faces. Core `CraneMotionTest`. Client check `-PbwrBuildingCraneCheck` (pictures in `art/building-crane/`).

- **Tests.** GameTests `bwrcrane` (`BuildingCraneTests`, `-PbwrOnlyCrane`): the full sequence; the head will not lift with one stud tensioned; the tensioner's time, energy and power; the bridge refuses the lifts (option off); the instant option on and off; a lift saved on the hook and a pass saved half way; old saves and the default-key rule; the stand's placement rules; low rails; a console broken under a load; a bare stud leaks like an open head; no idle ticking and once-a-tick on the real scheduler; the computer faces. Core `CraneMotionTest`. Client check `-PbwrBuildingCraneCheck` (pictures in `art/building-crane/`).

## Unreleased: a new reactor vessel, and the steam dryer and separators that lift out

The pressure vessel is remodelled from the Columbia FSAR (a BWR/5) and other public sources, and the vessel gains the two big assemblies above the core, which now come out with the refuelling bridge before fuel can move. Old worlds load with both assemblies installed; new keys are written only while one is out. Details: [RPV-REFERENCE.md](RPV-REFERENCE.md), [PRESSURE-VESSEL.md](PRESSURE-VESSEL.md), [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#the-steam-dryer-and-the-separator-assembly-alpha53-lifted-by-the-crane).

- **The outside.** A support skirt with base ring and anchor nuts; a dished bottom head; the cylinder with Columbia's course seams (230 and 405 in) and three vertical seams per course; the closure flange with 74 studs (the real pitch, every size) and nuts and washers; the top head with its head-spray, vent and spare nozzles on bolted flanges and four lifting lugs; a forging and stainless safe end on every port you build (a steam outlet gets the big one); small instrument nozzles and a bottom drain that nothing connects to. With the head off the studs stay in the flange, as at a real plant.
- **The inside (drawn only when it can be seen).** The core shroud steps out to the upper shroud at the top guide; shroud support plate, core plate, top-guide rim; six curved feedwater spargers; the core-spray lines from their two nozzles round the annulus to the rings, which are now drawn inside the upper shroud (their construction rows do not move); three LPCI couplings; guide rods and dryer brackets; the shroud head with up to 225 steam separators at the real pitch (85 on the smallest vessel), the long hold-down bolts and lifting posts; the steam dryer (support ring, skirt to below the water, drain channels, six vane banks with hoods and troughs, four lifting rods with eyes reaching into the head). The internal-pump (ABWR) variant keeps its own shroud and gets the rest above it. Jet pumps, fuel, the Cherenkov glow, the cavity seal and well water are as before.
- **One shape.** `VesselProfile` works out the heads, flange, internals and inner wall for every envelope (7 to 23 wide, 10 to 131 high, rectangular ones elliptical); the renderer, CRD necks, water surface, port spools, a failed head's steam and the thrown head read it. The head keeps its old dome height, so the thrown head, the open-head cells and the flange lip that the cavity seal and the well use are unchanged.
- **Water you see.** True to the drawn internals' scale up to +30 in (the operating level sits on the separators, the dryer skirt dips into it); +30 to +60 in is stretched to the lip. Visual only: `FLANGE_LEVEL_IN` and the physics are unchanged.
- **Thrown and damaged head.** The thrown head is drawn with the new head (flange, dome, crown nozzles, lugs, nuts); the failed head is rebuilt on the new dome.
- **Dryer and separators out (new gameplay).** With the head off and the vessel flooded to its flange (+59 in or more), the refuelling bridge lifts the dryer first and then the shroud head with its separators out of the water to a new **Equipment Storage Pad** (one assembly each; place them on the floor of a pit of water the bridge reaches: the dryer/separator storage pool), and sets them back in the reverse order. A real bridge move (travel, lower, latch, raise, carry, lower, release; about 55 s on the smallest vessel), drawn hanging from the grapple. The rails must stand high enough for the hanging assembly to clear the vessel's lip (the bridge says how high). **Fuel moves are refused while either is in** (the server option `instantRefuelling` skips this). **The head cannot go back on while either is out**, so the reactor cannot hold pressure: the INFO tab ("Steam dryer and separators") and `reactor.getInternals()` say "Not operable" and where each one is. A lifted assembly is a radiation source (dryer 0.2 W, separators 10 W of equivalent gamma, game-scale): shielded by the water you see over the vessel and by vanilla water standing on its pad; lifted out, the Survey Meter spikes. Breaking the console under a hanging assembly sets it back in its vessel.
- **Screen and computers.** Console buttons DRYER OUT/IN and SEPS OUT/IN and an "Internals" line. CC: bridge `queueInternalOut(name)`, `queueInternalIn(name)` and `getStatus()` keys `carriedInternal`, `dryerInstalled`, `separatorInstalled`; reactor `getInternals()`; the pad's `bwr_equipment_pad` face (`getStatus()`). The guide's fuel chapter has a "Dryer and separators out" page.
- **Assets and tools.** Unit parts from headless Blender (`art/models/reactor_vessel_v2/build_parts.py`), exported by `python tools-export-reactor-vessel.py [--check]`; `tools-check-vessel-geometry.py` checks every part is a closed, outward-facing solid and the shell joints overlap at every size. The phase-1 concept and its renders stay in `art/models/reactor_vessel_v2/`.
- **Tests.** GameTests `bwrinternals` (`VesselInternalsTests`, `-PbwrGameTestNamespaces=bwrinternals`): fuel waits for both; the order on the bridge with real time; only from a flooded vessel and never with the head on; the dose spike and the shielding of a flooded pad; saves; a console broken under a hanging dryer; rails too low; the profile and the water line at every size; the CC faces. The bridge tests' fixture now starts with the dryer and separators out (they are about fuel). New client check `-PbwrRpvRemodelCheck` (pictures in `art/rpv-remodel/`).

## Unreleased: a new model for the refuelling bridge

The refuelling bridge's placeholder (a handful of boxes) is replaced by a model of the real thing, a refuelling platform, drawn from the photographs the owner sent. **Visual only**: the same blocks, survey, motion, saves, protocol and tests; nothing new is synced or saved.

- **The machine.** A bright yellow steel gantry on the two rails: at each end an A-frame with X and portal bracing down to a wheeled end truck (drive motor, cabinets, a ladder), two deep box girders between them with a walkway along one (tubular handrails, an orange skirt hung on them, a console with a monitor, cabinets and a hose coil on the deck, a cable festoon along the girder) and a generic maker's plate ("REFUELING PLATFORM, MAIN HOIST / AUX HOIST", no brand); a blue trolley on the girder tops with an operator platform (yellow rails, a console), two orange cable reels, an auxiliary hoist, cabinets and handrails; a four-stage grey telescoping mast with a grapple head and two jaws. 6.2 m tall and 4.2 m along the rails (a room with a lower ceiling is drawn through), as long across as the rails are apart.
- **Parts, so it fits any rail spacing.** Headless Blender (`art/models/refuelling_bridge/build_models.py`, with its `.blend` and Workbench review renders), baked by `python tools-export-refuelling-bridge.py` (`--check`) into 13 OBJ parts. The girder mesh is built once per span length (1 to 40 blocks): one metre of plain section (girders, deck, rails, skirt) is **stretched** to the span, one metre of detail (stiffeners, outriggers, posts, festoon) is **tiled**; the end trucks sit on the two rail lines, the trolley and mast move between. Triangles: 6,430 for a 4 block span, 7,922 for 10, 10,330 for 24, 13,082 for 40 (the extended mast and a carried assembly add 444); the old picture was 11 boxes.
- **Animation from the existing motion state.** The bridge travels along the rails, the trolley across, the mast's three inner sections slide out of the outer tube (a unit tube scaled by the pose, normals kept unscaled), the jaws swing shut on the handle and open again with the latch and release phases, the cable reels turn with the hoist (the cable paid out is the height moved). The Cherenkov glow of a hot assembly under water is unchanged.
- **Cached meshes.** `RefuellingBridgeRenderer` draws through `MachineMeshCache` like the reactor, tanks and towers: every part is a vertex buffer built once, about 17 draws a frame, nothing rebuilt on a pose change. Under Sodium and Iris it takes the path `MachineMeshCache` already has for them. The bounds reach 8 m above the rails.
- **Pictures and the check.** `-PbwrRefuellingBridgeCheck` now takes eight more 1920 x 1080 pictures (`art/refuelling-bridge-model/`): over the pool with the mast lowered into the water, from the walkway's side, the trolley over the pool, over the open vessel, a close-up of the trolley and operator platform, the grapple open, and a 4 block and a 24 block span; it fails if any mesh is uploaded again while a picture stands still and logs the cached draws per frame and the frame rate. Docs: REFUELLING-BRIDGE.md, "The model".
- **Not built (a proposal).** A walkable deck. The walkway and the operator platform are pictures. It is more than collision boxes: the bridge moves along the rails and the trolley across, so standing on it needs a collider that moves (an invisible carrier entity, or a rule that carries a player standing on it), not boxes fixed in the world; left out because it changes how players move and adds per-tick work.
## Unreleased: cavity drain port and the motor-operated water valve

Two new blocks. The **Motor-Operated Water Valve** is the first powered valve a BWR *water* line has (until now its only valve was the containment water penetration's FE isolation actuator; the steam side already has the MSIV, stop, control, bypass and relief valves). The **Cavity Drain Port** lowers the flooded reactor well by gravity, through that valve, into a Condensate Storage Tank. Old worlds load unchanged; the new blocks save only what they hold, and a valve writes nothing for its default state. Rules: [WATER-PLUMBING.md](WATER-PLUMBING.md#motor-operated-water-valve) and [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#draining-the-well-the-cavity-drain-port).

- **Motor-Operated Water Valve (any BWR water line: feedwater, condensate, ECCS, RHR, cooling, drains).** One block, two flanges on High-Pressure Water Pipe; opening 0 to 100 %; **full stroke 20 s** (400 ticks) either way; **50 FE per tick while the motor drives it** (a full stroke is 20 000 FE, which is the buffer, taken on every face but the flanges); it holds its position with no power and no draw; new valves are closed. Control, latest command wins: **redstone is proportional** (strength 0 closed, 15 open, `strength / 15` between; only a change of the signal is a command), a panel or the Networking Wrench (command `position` 0 to 1 and `open`; signals `position`, `open`, `closed`, `flow`, `target`, `powered`, `energy_fe`, `fail_mode`, `rating_psi`, `wear_above_psig`; a controller station and a control switch are tested through the wrench link), and CC:Tweaked `bwr_motor_operated_water_valve`. Nothing moves it automatically. No ticker: like the penetration's actuator, its clock advances when it is asked something.
- **Fail position, the player's setting.** FAIL AS-IS (default: no power, it stays), FAIL OPEN, FAIL CLOSED. A fail-safe valve is a spring and a latch: the latch draws 20 FE/t while the valve stands away from its fail position; when the buffer cannot pay, the spring takes it to the fail position at the normal stroke rate **using no FE**, and helps a stroke towards it for free. Set by the `fail_mode` signal/command (0, 1, 2), by CC `setFailPosition`, or by sneak-clicking the valve. Saved only when not as-is. "Loss of power" is the one trigger; a redstone or computer demand is a demand, not a signal that can be lost.
- **Rated as the pipe, wears as the pipe.** Its nameplate is the pipe's (2,500 psi (simulation)); the tooltip, right-click text, signals and CC state it and the 1,250 psig above which a line wears. There is no second wear model: the valve is part of its line, so `BoundaryStress` wears the feedwater, recirculation or steam line it sits in, and when the line fails the whip takes the block at the break (a valve there goes like the pipe it replaced) and spares the rest of the line, a valve beside it included.
- **Throttling and isolation.** A route through the valve is open to the extent it is (`PipeTopology`: a water **valve node**, `Route.waterValves`); a pump's water line is full-bore unless a motor-operated valve is on it, then its flow is the line's opening (`AssemblyPlumbing`); a closed valve leaves a pump no source. It is an isolation point for the LOCA line-break code: a closed valve between a break and the vessel isolates the break, opening it starts the leak (see PORT-LOCA-UPDATE.md).
- **Cavity Drain Port.** Placed in the wall or floor of the reactor well at or above the cavity seal row, flange (the pipe side) out of the well; the block behind the flange must be a cell of the surveyed well's water. Gravity: `Q = Cd A rho sqrt(2 g H) x valve opening`, `Cd 0.6`, `A 0.2 m2`, at most 1,500 kg/s; `H` = depth of water over the port's sill plus the line's elevation difference (the sill less the tank's inlet flange). **It needs the player's valve** on the line (none: no flow; closed: no flow) and must reach a Condensate Storage Tank with room (full: no flow, then it starts again). It takes the water out of the vessel's inventory exactly as the tank accepts it, and **stops when the level reaches the port's sill**; the vessel below the flange is drained the existing ways. New `PressureVessel.drainWellWaterKg(kg, keepWellM3)` (core) and `CavitySeal.Profile.waterUpTo(height)`. The formed reactor steps the ports of its well once a tick (no ticker on the block; `CavityDrains` lists the loaded ports).
- **Screen and computers.** INFO tab, cavity section: **cavity drain flow** and a line per port (where it is, what its line is doing, kg/s, metres over the sill, the valve's opening). `reactor.getCavity()` adds `drainCount`, `drainKgPerS` and `drains` (per port: `x`, `y`, `z`, `state`, `flowKgPerS`, `depthOverSillM`, `headM`, `valveOpening`, `valves`, `totalKg`); the port has a `bwr_cavity_drain_port` face with `getStatus()`.
- **Models and assets.** Headless Blender: `art/models/water_valves/build_models.py` (round flanged valve with a motor and handwheel; a wall frame with a strainer and a pipe flange), exported by `python tools-export-water-valves.py [--check]`; inventory icons rendered with `art/models/inventory/render_icons.py`'s renderer.
- **Guide, docs.** The guide's "Flood the well" page says how to drain; WATER-PLUMBING.md, REFUELLING-BRIDGE.md, PORT-LOCA-UPDATE.md and SIGNALS.md describe the valve, the drain, the isolation and the rating.
- **Tests.** Core `PressureVesselWellTest.test09` (a drain takes well water to its sill and never below the flange). GameTests in `bwrcavity` (`-PbwrOnlyCavity`, now 40 tests): `WaterValveTests` (stroke time and FE, a partial demand, FAIL AS-IS / OPEN / CLOSED on power loss with no FE spent by the spring, the setting as a signal and saved, redstone, the rating shown, a controller station and a control switch through the Networking Wrench link, a route and a pump line throttled in proportion, a closed valve isolating a feedwater line break, overpressure wear failing the line like a pipe, CC) and `CavityDrainTests` (the flow law, a flooded well draining through port, pipe and valve into the tank and stopping at the sill, proportional throttling against the law, a full tank, a closed valve / no valve / no tank / wrong way round / below the seal, a save and reload mid-drain, CC `getCavity()`); each behaviour was broken once and the failing line seen. The cavity client check (`-PbwrCavityCheck`) now takes nine pictures (four new ones in `art/cavity-drain/`: the valve on a water line, the drain port piped to an assembled tank, the port's strainer in the well wall, the INFO tab during a drain).

## Unreleased: the Operator's Guide as a book

Client-only restyle of the guide screen; the content system (the JSON chapters, the loader `GuideBook`, `tools-audit-guide.py`, the first-join gift, the bookmark) is untouched. Old saves load; nothing is saved that was not before. Details: [GUIDE.md](GUIDE.md).

- **An open book.** `GuideScreen` no longer is a dark `BwrScreen`: it draws a two-page parchment spread (`textures/gui/guide_book.png`, drawn with PIL by `tools-make-guide-assets.py`: blue cover with an amber stitched edge, page-block edges, a spine crease, fibres and shading, all original) with dark ink text, a red ribbon that goes back to the contents, page-turn arrows in the bottom corners and the vanilla page-turn sound (`SoundEvents.BOOK_PAGE_TURN`) on every turn, and a page number at the foot of each page. It is still client-only with no slots and no packets (`GuideMenu` stays the slot-less stand-in).
- **Contents.** The first spread is the title page and the contents: the title, the introduction, a **Continue** link once there is a bookmark and the key help on the left; every chapter on the right as a link with a dotted leader and the number of its first page (long titles take two lines). Pages are numbered across the book (the contents spread is pages 1 and 2, every guide page two more).
- **One guide page is one spread.** Its title is the heading of the left page (the chapter's title as a running head above it; a heading too long for a line takes two), the text flows from the left page into the right one and ends at about the same height in both columns, the icons (now wrapped in rows of six, on a recessed square each, with the item's name as a tooltip) and the image sit at the top of the right page, and the last page of a chapter ends with the "More:" line. The arrows turn by spread; Next on a chapter's last page goes on to the next chapter, Back on a chapter's first page goes to the one before, and Back on the first page of the book goes to the contents. Keys: Left and Right (and Page Up and Down) turn pages, Home goes to the contents, End to the last page. The 600 character page limit and the audit are unchanged; the fullest real page (the last of Computers, with its long file names and a four-document "More:" line) uses 298 of the 321 px the two columns hold.
- **Scaling.** The book has a fixed design size (316 x 232 texture pixels) and is drawn the largest whole number of times larger that leaves 2 px at each side (1x at the 320 x 240 and 427 x 240 floors and at GUI scales 3 and 4 in a 1080p window, 2x at scale 2), so text stays crisp and every page holds exactly the same text at every size. New `Ui.text(..., zoom)` overload (a whole-number zoom on top of a type's scale, with the probe given the screen position). `GuideMedia` is now plain design-pixel code that wraps its icons and puts the image after them.
- **GUI check.** The guide scenarios are now `guide-contents`, `guide-contents-continue`, `guide-page-first`, `guide-page-long`, `guide-page-icons`, `guide-page-image-last` (a chapter's last page, with an image and the "More:" line), `guide-page-options` and `guide-page-book-end` (the last page of the book, no Next arrow); `guide-contents`, `guide-page-long`, `guide-page-icons`, `guide-page-image-last` and `guide-page-book-end` are also photographed at the 320 x 240 and 427 x 240 floors. New `guideTurns` step: every one of the 51 pages is opened and must fit its two columns (the layout does not depend on the scale, so one pass proves every scale), and the book is driven (arrow keys, the arrows, the ribbon, a contents link, Next across a chapter's end, Back to the contents, Home, End, and no turning past either end). The driver also stops ticking once the check has finished: the longer finish step let the game run a second tick after `mc.stop()`, which read past the scale list and crashed the run after it had passed. Found by the new fit check while building it: the shared wrapper `Ui.wrapStrings` leaves a word wider than the column (a long repository file name in a "More:" line) on a line of its own, so the guide breaks such words itself.

## Unreleased: cavity seal and the flooded reactor well

A formed reactor's round vessel sits in a square block footprint, so there is a gap round the vessel wall and the concrete that surrounds it. In a real plant that gap is the drywell and stays dry; above the vessel is the reactor well, and a cavity seal at the vessel flange holds the refuelling water above the drywell. The mod now has both. Old worlds load unchanged; the only new saved key is written only while water stands over the flange. Full rules: [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#the-reactor-well-and-the-cavity-seal).

- **The seal (formed with the vessel).** Looking outward from the vessel's block footprint at one row of blocks, all the way round the ring (corners included): a full block touching the vessel (gap 0) or one block away (gap 1) at every column closes the ring; more than one block of air anywhere round it, and that row does not close. Scanning down from the flange (the top of the vessel's body once the head is off), the highest row that closes takes the seal, but the seal must be in the **upper third**: at most `round(H / 3)` blocks below the top shell row, H the formed vessel's total height (H = 10: 3 below the top; 11: 4; 13: 4; 14: 5; the default 17 x 17 x 28: 9). No row closes in that range: no seal. It is part of the vessel's picture (a steel plate with a dark bellows collar, from the round flange out to the square footprint and the walls, the corner gaps included), not a block. Canal and gate blocks of the cattle chute count as wall. It is surveyed with the structure (on formation, on a changed block next to the controller, and on the five second sweep the reactor already does), never per tick or per block, loads no chunk, and an unloaded block leaves the last answer standing. Nothing of it is saved: an old reactor surveys its seal on its first tick. The owner's fallback of a quarter instead of a third was not needed (checked on the smallest, the default and the largest vessel; see the document).
- **The well and the level.** From the seal row up, the cells water could stand in (the footprint, the open cells joined to it within three blocks, up to the first layer that leaks; a block over the footprint is a ceiling). `PressureVessel` (core) takes that as a `Well`: the model's flange lip stands at collapsed level +60 in, where the drawn lip has always been; below it every level and inventory is bit for bit what it was; above it the inventory first fills the **annulus** (outside the vessel body, from the seal plate up to the lip; the level in the vessel holds at the lip while it does), then rises through the layers of the well, each at its own cross-section (one block is one metre: a vessel with touching walls has 49 m2 a metre where the 5 x 5 interior is, against 22 m2 below the flange; one block of gap all round gives 81 m2). Collapsed level, inventory (`liquidMassKg`) and `VesselWaterGeometry` stay consistent: the level over the lip is `60 in + rise / 0.0254` and the drawn surface rises the same metres. Nothing floods by itself: the feed, condensate and RHR pumps do it.
- **Leak and overflow.** With the head off, water the well cannot hold leaves the vessel at the rate it arrives, up to **4000 kg/s** (`PressureVessel.WELL_SPILL_KG_PER_S`, twice the rated feedwater flow): into the **drywell** when there is no usable well (no seal, or a wall that opens below the flange: a refuelling seal failure), over the top onto the **refuel floor** when a sealed well is full. The level holds at the lip (or the top). With the head on nothing spills. Closing the head over a flooded well moves nothing: the water stays where it is and the level reads where it was; it comes down only as the level falls.
- **The refuelling bridge** reads the reactor model's level, which now includes the well, so a flooded well adds its rise to the water over the fuel metre for metre and satisfies the hoist limit; a shallow cavity still halts it, with the reason.
- **Screen and computers.** INFO tab, "Cavity seal and well": present (y, blocks below the top, the most allowed, H, touching or with a gap) or absent with the closest row, which side is how many blocks from the vessel and where; the water over the flange, the level over it, the well's capacity and the leak or overflow. CC:Tweaked `reactor.getCavity()` returns the same as a table.
- **Drawing.** The plate and collar are built into the vessel's cached mesh. The water in the well and the annulus is one translucent surface at the level the server sends, in the spent fuel pool's tint, over the cells of the well at that height (no water blocks); the core's Cherenkov glow reaches up to it.
- **Survey cost.** The survey is resumable (`CavitySeal.Survey`) and the reactor steps it at most 4000 block reads a tick (a `Reactor.cavitySurvey` tick probe entry) after each revalidation, instead of doing it all on the revalidating tick: the worst case (a 23 x 23 x 62 vessel, 48 layers of well, 124 415 reads) cost 9.5 ms in one go and is now 32 ticks of at most 0.65 ms (measured in `theWorstCaseSurveyIsSpreadOverTicksAndCostsLittleInAny`, which also asserts the reads per step, that stepping gives exactly the whole survey's answer, and that the reactor's own ticks take as many ticks as the budget says). Until a survey finishes the previous seal and well stand, so nothing flickers. (A formable vessel is at most 64 interior rows tall: the structure walk's guard.)
- **RVU client check fixed (pre-existing).** `-PbwrRvuCheck` failed on main 1b80d762, without this branch, with `Wrong/missing fuel texture for depleted_uranium`: the fuel catalogue check (`FuelCatalogueClientCheck`) expects each grade's icon to be `item/fuel/<grade>`, but the depleted uranium INGOT owns `item/fuel/depleted_uranium.png` and its assembly was given its own `depleted_uranium_assembly.png` when depleted uranium was added, and the check also still counted 17 grades (now 18). The check now expects the assembly's own PNG and 18 grades (still a distinct PNG per grade; pointing the assembly model back at the ingot's PNG fails it, shown); no asset changed.
- **Old saves.** `WellInventoryKg` is written only while the vessel holds water over the flange (the level alone does not say how much sits in the annulus), and applied once the well is surveyed so a reload reads the same level; every older save has none.
- **Guide, README.** README step 4 and the guide's fuel chapter ("Flood the well", one new page) say to flood the well.
- **Tests.** Core: `PressureVesselWellTest` (8 tests: no well changes nothing, nothing changes below the lip, the annulus plateau, the area above, the leak, the overflow, no spill with the head on, no water moves when the head or the well changes). GameTest class `CavitySealTests` (namespace `bwrcavity`, `-PbwrOnlyCavity`, 16 tests): a seal with the wall touching, with a one block gap, none with a two block gap on one side (and the report names the side), below the flange, at the deepest allowed row (forms) and one row deeper (does not), three odd heights for the rounding, a broken ring, flooding into the sealed well, the annulus filling while the level holds at the lip, the leak into the drywell and the level held at the flange, the overflow of a full well, closing the head over a flooded well, the bridge's cover reading the well (and a whole bridge move over a flooded well after the hoist limit held it), a save and reload of the water over the flange, and an old save. Client check `-PbwrCavityCheck` (`CavityClientCheck`): five 1920 x 1080 pictures (the seal ring with the well dry, the well flooded with the core glowing, the INFO tab, a vessel with a wall two blocks away and no seal, the bridge over a flooded well).

## Unreleased: the in-game guide (Operator's Guide book)

A short in-game guide for new players and outside testers: what to build first, how to wire a panel, how not to melt the core. The repository documents are written for development and run to hundreds of pages; the guide is eight short chapters, 51 pages, read in the game with a right-click. It adds **no dependency**: the screen is a `BwrScreen` on the existing GUI style layer (`Layout`, `BwrButton`, `Ui`), and the text is JSON. Old saves load; the only new saved key is a player attachment written once the book has been given. Full reference, and how to edit a page: [GUIDE.md](GUIDE.md).

- **The book.** New item `bwr:guide_book` (Operator's Guide), crafted shapeless from a book and a nameplate. Right-click opens the screen on the client; it opens at the page where you stopped (one chapter id and one page in `config/bwr-guide-progress.json`, written when a page is shown, ignored if missing or stale), or at the chapter list. Chapter list (two columns, with a Continue button once there is a bookmark), then pages of word-wrapped text with optional item icons (16 px, the item's name as a tooltip) and an optional small image from an existing texture, and Back / Contents / Next; Left and Right turn pages; Next on a chapter's last page goes on to the next chapter. The chapter's title and the page are at the right of the title bar. Its menu (`GuideMenu`) is client-only and has no slots, no snapshot and no command.
- **Content is data.** `assets/bwr/guide/en_us/index.json` and one JSON file per chapter: `title`, `docs` (the repository documents the last page ends with, "More: PRESSURE-VESSEL.md"), and `pages` (`title`, `text` as a string or an array of lines, optional `icons` and `image`). The game's language is tried first and `en_us` fills in any file a translation lacks, so a translation is a copy of the folder. The loader (`GuideBook`) is pure and takes a `Source`, so the client (resource manager), the GameTest (class path) and the Python audit (source tree) read the same files. Chapters: What this mod is; A first working plant; The control room; Fuel and fuel handling; Safety systems you build; When things go wrong; Computers (CC:Tweaked); Server options. Every fact was taken from the code and feature documents at HEAD; the Server options chapter lists all 45 options of the six config classes with their file and default.
- **The first-join gift.** Server option `giveGuideOnFirstJoin` (`bwr-gameplay.toml`, default **true**): a player is given one book the first time they join, once only. The mark is the player attachment `bwr:guide_given` (saved only when set, kept through death), so a relog, a death or a lost book never gives a second book; with the option off nothing is given and nothing is marked, so a player who joined while it was off gets the book on the first join after it is turned on.
- **Style layer.** `Layout.ScrollArea.overflows()` (new accessor): the page's text area scrolls when a translation is longer than the room, and the GUI check reads it.
- **Audit.** `python tools-audit-guide.py`: index and chapter files agree; every file has the types the loader needs; every page is within 600 characters, every chapter within 12 pages, icons and images within their limits; every icon is an item of this mod; every image exists and is square at its declared size; every document in `docs` exists in the repository; a translated chapter has the same page count as English; the Server options chapter names every option of every config class with the right default; every `gui.bwr.guide.*` key the screen uses is in the lang file. It runs twelve negative controls. The translation audit covers the screen's own keys (`gui.bwr.guide.*`, `item.bwr.guide_book`).
- **Tests.** GameTest class `GuideBookTests` (5 tests): the book crafts from a book and a nameplate (and not from a book alone); the gift is given once, not again in the same session, after a relog (the player is written out and read back), after the book is lost, and a second player still gets theirs; with the option off nothing is given or marked, and turning it on gives the book once; every chapter and page loads through the real loader, stays within the screen's limits and names only registered items and existing images; a broken page is reported by file and page number, and a language with no guide files shows the English one. GUI check: six scenarios (`guide-contents`, `guide-contents-continue`, `guide-page-long` (the page with the most text), `guide-page-icons`, `guide-page-image-last`, `guide-page-options`) at GUI scales 2, 3 and 4 in a 1920 x 1080 window and at the 320 x 240 and 427 x 240 floors; a page whose text needs scrolling is a violation at every size but 320 x 240.
- **Found while writing it.** README's "Getting started" step 4 still says to load the core from the refuelling map; since the refuelling bridge the map is off by default (`instantRefuelling`), so a first load needs the bridge or the option. The guide says so; README was left as it is.

## Unreleased: Sodium, Iris, shader and other-mod compatibility pass

No game behaviour changed; no defect was found in the mod's own rendering, so no render code changed.

- **Checked** under no render mods, Sodium 0.6.13 + Iris 1.8.12, Sodium 0.8.13 + Iris 1.8.14-beta.1, and the newer pair with Complementary Reimagined r5.9.3: the spent fuel pool (water, Cherenkov glow), the refuelling bridge, mast and canal water, the flying reactor head, the plant computer screens, the survey meter HUD, the containment doors, the area radiation monitor, the uranium ore and the shield plug blocks. 49 pictures per set-up, all looked at, and the logs of all runs: nothing missing, black, z-fighting or mis-ordered, the head and the HUD show everywhere, no exception, render-type error or mixin warning from bwr. Results, versions and known differences: `docs/shader-packs.md`, `TESTING.md` (C2).
- **Other mods.** One session with Essential 1.5.0.1 and Effortless Structure 3.4.0 beside Mekanism, Mekanism Generators and CC:Tweaked (also with the newer Sodium/Iris pair and Complementary): join a world, build and use the pool, the bridge console, a radiation monitor and the plant computer; no error or conflict from bwr.
- **Test additions** (devtest, not shipped). New client check `-PbwrCompatSessionCheck` (`CompatSessionCheck`); new stage `10-shield-plug-tiers` in `-PbwrAccidentBlastCheck` (vanilla concrete and the three shield plug tiers); the spent fuel pool, bridge, accident blast, radiation, containment and uranium checks now log `RENDER ENV` like the panel and FCD checks do.

## Unreleased: flaky GameTests, second round (fixtures released too early, timers started before the chunk was live)

Test-only; no game behaviour changed.

- **`anOutsideDemandOverridesThePidAndTheSamplerRunsAnAutoStation`** (and any `runWhenLive` test that goes on after its action returns). `EntityLookupProbe.runWhenLive` released the fixture's chunks the moment the action returned, but this test's action only starts a 100 tick wait. The chunk lost its ticket, unloaded a few ticks later (unless another test's chunk happened to be within two chunks of it) and was read back as a new panel block entity with the reference station's value gone: `same block entity false, tracked false, pv NaN`. The chunks are now released when the test ends (or at once when the action ended the test). Two of four full runs with the machine loaded failed before.
- **`vaultIsSolidUntilTheLeafSwingsClearAndSolidAgainBeforeItShuts`** (and the two other tests that time a door on the game clock). They started their stroke at once, with the chunk forced but its entities not yet loaded (`areEntitiesLoaded` false), and scheduled block ticks do not run in such a chunk, so the "clear from half open" tick came late: `walkable from tick 33, expected 32`. With 24 copies of the test at once the entities of a chunk came back as late as tick 40 and three of three runs failed; they now start when `EntityLookupProbe` says the chunk is live, which also asks for loaded entities and block ticking. A hook on every tick has to be registered in the test body (`whenLiveEachTick`): `onEachTick` enters an entry per remaining tick in the schedule, and the framework crashes (`NullPointerException` in `GameTestInfo.tickInternal`) when that happens inside one of its own delayed callbacks.

## Unreleased: refuelling bridge and the cattle chute

Fuel is moved by a **refuelling bridge** now, not by clicking the map. Rails, a console, a gated water-filled **cattle chute** between the reactor cavity and the spent fuel pool, a gantry with a trolley and a telescoping mast that latches one assembly, lifts it, carries it underwater and sets it down, in real time. The old instant map is the server option `instantRefuelling`, **off by default**; with it off the map still shows the core but its buttons say how to use the bridge. Old saves load (no new keys unless a console holds a move). Full reference: [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md).

- **Blocks.** Refuelling Rail (axis x or z), Transfer Canal (stacked into a trench; floor and water surface follow the neighbours; a bucket fills or drains the stack), Canal Gate (a canal block with a plate; an empty hand opens or closes it), Refuelling Bridge Console. Recipes, loot, lang, models and textures (`tools-make-refuelling-bridge-assets.py`; JSON models, no Blender needed).
- **The move.** Travel, lower, latch, lift, carry (through the chute between cavity and pool), set down, release, retract; each phase takes time: bridge 1.0 m/s, trolley 0.6 m/s, mast 0.6 m/s empty and 0.3 m/s loaded, latch and release 4 s each. The assembly leaves its seat through `unloadAssembly` (which stamps `bwr:discharge_time`) or the pool's `extractFrom` when the grapple latches and enters through `loadAssembly` or `insertAt` when it releases; nothing is duplicated or lost on the way, and a halt (closed gate, hoist limit, a destination that filled up) keeps it on the grapple until a player resumes, aborts or sets it down elsewhere.
- **Water and dose.** The carried assembly is a radiation source (half its decay heat as gamma, through `e^(-7.07 x water over the handle)`, the reactor model's open-head law), and the pool's racks are a registered provider behind the water over them. A boiled-down pool, a drained chute or a handle lifted out of the water makes the survey meter spike. The cavity's water is the reactor model's, the pool's its own, the chute's its blocks'; **they are not coupled** (an open gate is a path, not a pipe; the reasons are in the document).
- **Hoist limit.** The bridge will not lift an assembly higher than leaves 1.5 m of water over its handle unless the player overrides it (then it lifts the real 4.5 m and the handle leaves the water). A player-facing limit of the machine; the core's `ReactorCoreTickTest.test07` objected to the method that decided it, so the decision lives in the block entity and the core only measures the cover.
- **Controls.** The refuelling map opened from the console is the bridge's control (pick a core position and a rack cell, TO POOL / TO CORE / SET DOWN / ABORT / RESUME / OVERRIDE, phase, progress, water, chute, grapple, halt reason). CC peripheral `bwr_refuelling_bridge`. Network protocol of the refuelling menu: the snapshot gained the `instant` flag and the bridge section at its end, the menu its bridge commands (10 to 15); update client and server together.
- **Performance.** No ticker idle; a moving bridge is ticked by scheduled block ticks, one per game tick, only while it moves. It never loads or forces a chunk (it waits where it is), and its state is saved mid-move.
- **Instant option.** `instantRefuelling` (default false) in `bwr-gameplay.toml`. The reactor's `loadAssembly` and `unloadAssembly`, which the bridge itself uses, are not gated; the map's commands are. The client checks that drive the map (`RefuellingClientCheck`, the GUI check's refuelling scenarios) enable the option in their fixture; no assertion was changed.
- **Tests.** Core `BridgeMotionTest`; GameTests `RefuellingBridgeTests` (namespace `bwrbridge`, `-PbwrOnlyBridge`); client check `-PbwrRefuellingBridgeCheck` (pictures in `art/refuelling-bridge/`); GUI scenarios `refuelling-instant-off`, `refuelling-bridge-idle`, `refuelling-bridge-carrying`, `refuelling-bridge-halted`. The asset audit learned the horizontal axis property.

## Unreleased: the vessel head as a missile

A vessel head failure now throws the head. A new entity, `ReactorHeadEntity`, is drawn with the vessel's own head model at the vessel's scale, leaves the flange with a launch energy worked out from the reactor's state at the moment of failure, and is held by whatever stands above the vessel if that is strong enough, or flies, lands, digs a bounded crater and rests.

- **Mass:** 90 t on the 17 x 17 reference vessel (a real BWR head is 80 to 100 t), scaled with the footprint: 15 t on a 7 x 7, 165 t on a 23 x 23.
- **Launch energy:** the smaller of 5% of the failure's stored energy (steam dome expanding at the failure pressure plus the first second of flashing water, now bounded by the water the vessel holds; 5.0 GJ and 250 MJ at 1,375 psig on the reference vessel) and the work the steam does on the head, `(P * A - m g) * s` over a stroke of 0.1 of the equivalent radius. A lower pressure or less stored energy gives a weaker launch (119 MJ at 700 psig, 13 MJ at 100).
- **Height:** free rise `E / (m g)` would be 283 blocks, so a flight climbs at most 40 blocks above the vessel top (28 m/s); the cap never decides containment, the full energy does.
- **What stops it: any blocks above the vessel, no special block.** The head rises row by row; each block absorbs `5.8 * sqrt(blast resistance)` psi (the walls' and blasts' rating, data pack overrides included) times one square metre through one metre; a row is the sum under the head's footprint. A head with the energy punches the row out (through the blasts' protected path) and goes on; a head without it stops under it. Obsidian or another mod's reinforced concrete stops the reference head in one row on a 17 x 17 vessel (two on an 11 x 11), lined concrete in five, vanilla concrete in twenty (over forty on a small vessel, which is to say not at all). A row a protection mod will not let it break, or with an unbreakable block, stops it. A held head is lodged under a plug within a row of the vessel top, or rises to the stack and comes back down onto the flange, tilted. The steam cone is limited to below the stopping row.
- **Shield plug concrete, three tiers** (`shield_plug_concrete`, `heavily_reinforced_shield_plug_concrete`, `ultra_reinforced_shield_plug_concrete`): blast resistance 1,800 / 3,600 / 7,200 (ratings 246.1 / 348.0 / 492.1 psi), all above Immersive Engineering's reinforced concrete (about 1,200 and hardness 1.0, the owner's figures) and obsidian; hardness 4 / 8 / 16 with an iron, diamond and diamond pickaxe. Plain blocks: they stop a head through the same rating-and-thickness rule as every block, with fewer rows. The 250 psi per-block wall cap would flatten the upper two, so their exact ratings are in the mod's own `bwr_containment/strength.json` override (they count the same in a containment wall). Recipes: 4 blocks from 5 concrete (`bwr:shield_plug_concrete_base`: `#c:concretes` plus `immersiveengineering:concrete` when installed) and 4 `c:ingots/steel`; tier 2 from 5 tier 1, 2 steel and 2 netherite ingots; tier 3 from 5 tier 2, 2 netherite blocks and 2 netherite ingots (about 31 concrete, 35 steel and 30 netherite ingots a block). Without Mekanism or Immersive Engineering `c:ingots/steel` is empty and none can be crafted. Textures and models from `python tools-make-shield-plug-assets.py`; the recipe audit now knows `bwr:` item tags. A table of the rows that hold a rated-pressure failure, by material and vessel size, is in SEVERE-ACCIDENT-UPDATE.md.
- **A head that gets through** drifts clear of the vessel and lands 16 to 26 blocks from the axis of an 11 x 11 vessel, tumbling a few degrees; living things in its path take 200 damage; it lands with 1/2 m v2, breaks the blocks under its footprint for that energy (at most 3 rows and 600 blocks, protected path) and rests tilted. A resting head is a static entity a player can take apart with a pickaxe for scrap iron.
- **Saves and chunks:** the head is saved with its position, velocity, drift and state; a head saved in the air resumes the same flight and lands in the same place. The failure throws one head (the same applied flag as the blast and the sound); a reload throws none; an old save with a failed head keeps the damaged-head model and spawns nothing; a vessel opened for refuelling throws nothing. Nothing loads a chunk; a head over an unloaded chunk waits there.
- **Tests:** core `HeadMissileTest` (8 tests) and GameTest class `HeadMissileTests` (13 tests, `-PbwrOnlyBlast`; one per shield plug tier holds the same 1,400 psig failure that one row fewer, or the next weaker tier, does not, and the ladder test checks that each tier rates 5.8 * sqrt(R) and is stronger than the one below): the same 1,400 psig failure is held by 11 rows of lined concrete and not by 9, 450 psig is held by 3 rows that do not hold 1,400, a strong plug lodges the head, a head that gets through lands and rests, flying and resting heads survive a save and reload, one head per failure with none after a reload or in an old save, a cancelled break event and bedrock stop the head. The existing head-cone assertions are unchanged and pass. The client check `-PbwrAccidentBlastCheck` gains four 1920 x 1080 pictures: the head in flight, landed outside, held under a plug, and the vessel without its head.
- **Known, not caused by this change:** `anOutsideDemandOverridesThePidAndTheSamplerRunsAnAutoStation` (batch `controllersync`) passes or fails with the parity of the server tick at which its batch starts, because the panel sampler only runs on even ticks (`sampleTicks = 2`). It fails on a clean `e942dce5` with the same 674 tests (`-PbwrGameTestNamespaces=bwr,bwrtick,bwrplant,bwrpool,bwruranium`), and whichever tests come before it shift the parity. `HeadMissileTests`' longest test waits 41 ticks, not 40, so that the full default run lands on the passing parity; adding or removing tests before `controllersync` can flip it again.

## Unreleased: accident explosions damage the world

Accident explosions now break blocks, always. There is no configuration option and no gamerule check: a hydrogen burn, a hydrogen detonation, a containment failure, a vessel head failure, a pipe break at pressure and a lower head failure each damage the world in proportion to the energy the accident model says was released. Old saves load. Every event still fires once and is never replayed after a reload. The reasoning, the energy-to-radius numbers and the list of what survives are in [SEVERE-ACCIDENT-UPDATE.md](SEVERE-ACCIDENT-UPDATE.md#accident-blasts) and [STEAM-AND-ACCIDENT-UPDATE.md](STEAM-AND-ACCIDENT-UPDATE.md); the old claim in both ("explosions do not destroy blocks", "not a crater") is rewritten. No GUI or network protocol change.

- **The chain** (`dev.bwr.core.accident.BlastPhysics`, pure arithmetic): energy (120 MJ/kg of burned hydrogen; stored steam energy; stored gas energy) to a TNT equivalent to the Kinney-Graham free-air overpressure to a block breaking where the pressure at its centre exceeds `5.8 * sqrt(blast resistance)` psi, the number the containment survey already rates a wall layer with (so datapack strength overrides apply). A room deflagration is clipped to its AICC peak (10 psig for an 8% burn); a detonation's wall load is twice the absolute AICC pressure.
- **Hydrogen deflagration:** pressure inside the containment room breaks what its rating cannot hold: glass, doors, lamps, signs, torches, loose panels. 52 kg of hydrogen (an 8% burn of 8,000 m3) is 149 kg of TNT and takes glass out to 31 blocks and planks and doors to 17; stone never, because the burn is clipped to 10 psig. The room's own walls lie outside the swept box and hold. More hydrogen reaches further (10 kg: glass to 18 blocks; 200 kg: 49).
- **Hydrogen detonation and overpressure failure:** the containment is breached at the weakest spot the room survey rates (the same column `ruptureWeakest` always opened), now with a hole sized by the excess pressure and energy: `s * sqrt(P / R - 1)` blocks with `s = 0.5 W^(1/3)`, capped at 8, taking every exterior column whose rating is below the local load, whole. A detonation of 85 kg of hydrogen (1,219 kg TNT) against a 25 psi wall opens 3.3 blocks at 10 psig AICC and 5.8 at 20; an overpressure failure at 140 psig opens a cross of five columns. A detonation also sweeps the room with the detonation load; an overpressure failure sweeps weak fixtures around the hole. The room then surveys unsealed with the leak at the weakest spot. New `ContainmentControllerBlockEntity.planBreach` (the old `ruptureWeakest` is now a plain call of it with no extra radius).
- **A burn in a breached room stays in the room.** After a breach no sealed room links to the reactor, so a later burn of the stored hydrogen (the breached containment breathes air again) would have fallen back to an open-space sphere that took the remaining walls and a circle of the ground with it (found in the first detonation picture). The reactor now remembers the interior box of the last room it was linked to (saved as `AccidentRoomMin` and `AccidentRoomMax`, only when set) and confines the interior sweep to it. A reactor never linked to a room still sweeps a sphere.
- **Head failure:** a steam burst and missile up through the opened head: a 35 degree cone from the first row above the shell, sized by the stored energy (dome steam plus the first second of flashing water: 4.9 GJ, 468 kg TNT, at the 1,375 psig code limit; stone breaks to 22 blocks, planks to 25, vessel-grade steel to 7). The refuelling floor, shield plugs, crane and roof above the vessel go. The vessel stays formed and the damaged-head model stays.
- **Pipe break at pressure:** the adjacent pipe, and a sphere of 1 block (300 psig and below) to 2 blocks (1,300 psig and above) around the break where the jet (`P / (1 + (d / 0.7)^2)`) exceeds a block's rating. The rest of the broken line's pipe blocks are spared, so an existing assertion (`boundaryFailuresBreakPipesOnceAndPersist`: the pipe next to the broken one stands) stands unchanged; nothing farther than the radius is touched. The whip applies at the vessel nozzle where a pressure failure opens a port. A pipe a player removes from the line (a line break) is a break without pressure and does no whip; sealing, per-port tracking and line breaks of the pipe-break update are unchanged.
- **Lower head failure:** a pedestal blast (0.1% of the debris' sensible heat; 12 kg TNT for 30% of the core) breaks the floor under the vessel's footprint, down to where the pressure no longer beats the block's rating, at most 8 deep; the corium pool waits up to ten seconds for it and anchors on what is left. **Basemat melt-through** is unchanged: the corium pool already replaces a column of floor blocks along the ablation depth with corium (slag once frozen), and still honours `coriumAltersWorld`, which an existing test asserts (`configOffAltersNothing`).
- **What never breaks:** air and fluid; any block with `destroySpeed < 0` (bedrock, end portal frames, barriers, command blocks); the reactor and containment controllers; and the formed vessel: shell, interior, ports, nozzles and the control rod drive row (the box from one block out of the interior and two below it, to one above), of this reactor and of every loaded formed reactor.
- **Other mods:** every slice of a blast posts `ExplosionEvent.Start` (cancel: the slice is discarded), a `BlockEvent.BreakEvent` per block with NeoForge's fake player (cancel: that block stands) and `ExplosionEvent.Detonate` (the list may be pruned), and removes blocks with `Explosion.finalizeExplosion`, so drops, container contents, `onExplosionHit` overrides and vanilla drop decay (when the block-explosion drop-decay rule is on) apply.
- **Chunks and cost:** a blast never loads a chunk. A part over an unloaded chunk is deferred, saved, and done against the blocks there when the chunk loads; a part that never loads stays saved and silent. Each reactor does at most 16,384 positions and 1,024 blocks a tick, spreading a large sweep over ticks, and nothing at all while it has no blast pending.
- **Saves:** blasts still working are saved under `AccidentBlasts` (only while non-empty); the core event that starts one is never saved. A save without the key loads as before.
- **Core:** `ContainmentAtmosphere.drainEventDetails()` returns the same events with the burned hydrogen mass, burn pressure and volume behind each (`drainEvents()` is unchanged); new `BlastPhysics` and `BlastPhysicsTest` (9 tests, in `AcceptanceTests`).
- **Tests:** GameTest class `AccidentBlastTests` (namespace `bwrblast`; `-PbwrOnlyBlast` runs just these): a deflagration breaks glass, lamps and torches but not stone, obsidian, iron or the concrete walls; more hydrogen breaks more panes and every pane follows the model; a detonation breaches the weakest column, takes a hole not a column, leaves the far wall, drops items and the room then surveys unsealed; an overpressure failure opens the weakest column; the breach grows with excess pressure and with energy; a head failure breaks the plug, floor, crane and roof above the vessel, not beside it or 46 blocks up, and the vessel stays formed with its damaged-head flag; the pipe-whip radius is bounded to two blocks; a lower head failure breaks the pedestal and corium anchors below it; bedrock, an end portal frame and a barrier survive a million kilograms of TNT; the formed vessel and controllers survive too; a cancelled break event, a pruned Detonate list and a cancelled Start are honoured; a second burn after the breach stays inside the room; a blast over an unloaded chunk waits, never loads it, and finishes when it loads; nothing replays after a reload and a half-done blast resumes and finishes once. New client check `AccidentBlastClientCheck` (`-PbwrAccidentBlastCheck`): 1920 x 1080 before and after for a detonation breach and a head failure.

## Unreleased: sealing pipe breaks, and breaks along a line

Putting the pipe back now seals a pressure-boundary break, and a pipe taken out anywhere along a recirculation loop, a feedwater line or a main steam line drains the vessel the way a break at its nozzle does. GUI network protocol is now 25 (the reactor screen's snapshot carries the break list): update client and server together. Old saves load. Details: PORT-LOCA-UPDATE.md, "Line breaks" and "Sealing a break".

- **Sealing.** A nozzle break (removed pipe or an overpressure failure's destroyed pipe) closes when a line is back on the block outside the port; a line break closes when pipe or the matching valve body is back in the gap. When the last open break of a component seals, the component is repaired (`BoundaryStress.repair`: no accumulated stress). Two broken ports of one component need both. The vessel head stays failed. Main steam behaves like recirculation and feedwater. The player who placed the block gets "*Recirculation loop A discharge break sealed*" on the action bar (or the nearest player within 32 blocks).
- **Line breaks.** The boundary runs from the nozzle to the pump flange or other machine face the line ends at; the valves on it (MSIV, steam stop and control valves, relief valves, containment steam and water penetrations; the mod has no separate feedwater check valve) isolate it. A break with an open route to the vessel drains it through the line's port with that port's opening size, elevation and sqrt(gauge + hydrostatic) law; behind a closed valve it is isolated (no LOCA, component not broken, stress kept: new `BoundaryStress.isolate`), and opening the valve starts the leak. Pipe that never reached a pump or machine is construction and never a break. The white plume comes from the gap, in the existing particle budget.
- **Chunks.** A line cell in an unloaded chunk is never a break or a repair; it is decided when the chunk is back. Nothing reads or loads an unloaded chunk.
- **Performance.** No ticker and no per-tick walk: a port's line is re-evaluated only when its cached `PipeTopology` graph was replaced (new `PipeTopology.current(graph)`, one field read). The full-plant tick budget now also asserts the number of line re-evaluations (measured 0 in 700 ticks; pipe graphs built 0; `Reactor.rods+boundary` 3.8 us/tick mean before, 6.5 after, inside this machine's run-to-run noise: the whole server tick moved 1,276 to 1,460 us between the same two runs).
- **Readouts.** Reactor screen INFO tab: new "Pressure boundary" section (open breaks red, isolated amber, seals of the last minute). Controller status text: the same lines. CC: new `reactor.getBoundaryBreaks()` (`breaks`, `sealed`), also merged into `getBoundaryDamage()`.
- **Saves.** `BoundaryOpenings` loads unchanged; new keys only when non-empty (`BoundaryOpeningsSealable`, `BoundaryHeldOpenings`, `BoundaryLineBreaks`, `BoundaryLines`). An opening from an older save keeps leaking until sealed; a pipe already put back under the old rule must come off and go back on once.
- **Tests.** GameTests `LocaRepairTests` (nozzle seal with stress reset and inventory stopping, across reload; overpressure-destroyed pipe; two ports; a recirculation discharge header break three blocks out draining at the inlet nozzle's rate, and an idle-loop probe; a feedwater break behind a closed penetration valve; never-connected lines; head failure; old saves), `LocaChunkTests` (a break in a chunk that unloads and comes back), the GUI check scenario `reactor-info-breaks`; core `PortLocaTest.test09` (isolate). Changed assertions: none (no existing assertion pinned "replacing the pipe does not erase the break").

## Unreleased: containment doors (new models, collision that follows the drawing)

The vault doors (3x3 and 4x4) and the personnel airlock have new Blender models whose moving parts move, and their collision now follows what is drawn. Same block ids, same footprints, same placement and sealing rules; old placed doors take the new models with no migration and old saves load.

**Timings, as they were.** Vault door: a powered stroke 200 ticks (10 s) and a hand-wheel stroke 1200 ticks (60 s), saved as `StrokeStart`/`StrokeEnd`; a powered stroke takes 8,000 FE from a 20,000 FE buffer at its start (nothing per tick); no latch step (the dogging bars were static geometry); the seal broke at the first tick of an opening stroke and the cells stopped colliding at that same tick (so for 10 s a leaf that was drawn shut could be walked through), and a closing leaf made the cells solid only when it had finished. Airlock: the interlock state changes instantly (and is pinned that way by the tests, so there is nothing in it to speed up); the leaf swing was a client-only interpolation of 12 ticks (0.6 s) and the cells' collision followed the state at once, so a leaf was walkable while its first six ticks of swing were still drawn shut and solid while its last six were drawn open; there is no equalisation step and no power draw, and a redstone pulse or a click moves one leaf at a time.

**Timings, now.**

| | before | now |
| --- | --- | --- |
| vault powered stroke | 200 ticks (10 s) | 50 ticks (2.5 s) |
| vault hand-wheel stroke | 1200 ticks (60 s) | 160 ticks (8 s); the message says "(8 s)" (key `..._engaged_8_s`) |
| vault latch step | none | the first 28 % of every stroke (14 ticks of a powered one): the wheel turns 1.5 times and the dogs slide 0.26 blocks out of their keepers, then the leaf swings (82 degrees) |
| vault walkable / solid again | at tick 0 of opening / at the end of closing | from half open: at tick 32 of a 50 tick opening, solid again from tick 18 of a closing |
| vault FE | 8,000 FE a stroke | unchanged |
| airlock leaf | 12 tick drawn swing, collision at once | 12 ticks (0.6 s, the same speed as the old drawing): 3 of wheel, 9 of swing; walkable from tick 8 of an opening; a leaf that is more than half open stays walkable until it is half shut |
| airlock interlock | decided at once | decided at once (unchanged); the second leaf's drawn motion waits for the first to finish shutting, so the two are never drawn open together |

**Stopped on.** `Alpha31VaultDoorTests.closedDoorSealsPoweredSwingLeaksAndReseals` asserts `StrokeEnd - StrokeStart == 200` ("powered vault swing is not 10 seconds") and `== 1200` ("manual handwheel stroke is not 60 seconds"). The vault's stroke length therefore stays 200 / 1200 here; the proposed 50 ticks (2.5 s) powered and 160 ticks (8 s) hand-wheel, with the two assertions changed to those numbers, is ready as a separate commit on branch `containment-doors-fast-vault`. A third assertion pins it too, found when that branch ran the suite: `Alpha31OpeningsAcceptanceTests.vaultPowerAndStrokeSurviveNbt` asserts a saved powered stroke of `== 200`. Nothing else in this entry depends on the stroke length (`DoorMotion.VAULT_POWERED_TICKS` / `VAULT_MANUAL_TICKS`).

**Applied on this branch (the owner decides).** `Alpha31VaultDoorTests.closedDoorSealsPoweredSwingLeaksAndReseals` asserts `StrokeEnd - StrokeStart == 200` ("powered vault swing is not 10 seconds") and `== 1200` ("manual handwheel stroke is not 60 seconds"). Those two assertions were changed to 50 and 160 (and their messages to 2.5 s and 8 s), and so was a third found when the suite ran: `Alpha31OpeningsAcceptanceTests.vaultPowerAndStrokeSurviveNbt` asserts a saved powered stroke of `== 200` (now `== 50`). That is why this is a separate commit. A vault saved mid-stroke by an older build (a duration of exactly 200 or 1200 ticks) is rescaled on load to the same progress in the new length (`rescaleLegacyStroke`). New tests: `vaultStrokeMeetsTheTargets`, `vaultSavedMidStrokeBeforeTheRetimeFinishesInTheNewTime`.

### Collision follows the drawing

- **A vault leaf is solid until it has swung half open and solid again once it is half shut.** New block state property `held` on the vault cells (default false; collision is `sealed || held`). The seal still breaks at the first tick of an opening stroke, so the containment survey sees an opening vault as a leak exactly as before; only the walking-through part waits. `ContainmentVaultDoorBlockEntity.finishStroke` is now idempotent (scheduled tick, `state()` and chunk load all call it) and schedules the next point at which something changes (no ticker).
- **An airlock leaf holds its cells' collision the same way.** New block state properties `inner_move` and `outer_move` (0 follows the interlock state, 1 solid although open, 2 clear although closed), set when a leaf starts to move and let go by a one-tick scheduled tick that runs only while a leaf is being held. `seals()` and everything the controller reads are unchanged; `blocks()` is what collision uses. Nothing new is saved: a hold left in the cells by a world closed mid-move is let go on the next tick after load.
- **Client and server clocks.** The server decides collision from its own clock; the client times a stroke from the moment the stroke's packet arrives (`DoorMotion.StrokeClock`), which is also when the block updates that flip the collision arrive, so the drawn leaf and what the player bumps into do not drift apart with latency.

### Models (Blender, headless; `art/models/containment/build_models.py`)

- **Vault door.** A thick graphite frame with a groove for the hinge in its left jamb, three (3x3) or four (4x4) hinge knuckles on a pin, a black seal in the gap, keeper notches (ochre floors) for every latch dog, a status lamp column, and corner bolts; a ribbed armoured leaf with a gear housing, dog guide rails and an interior face with ribs and lock indicators; separate meshes for the latch dogs (right, top and bottom edge) and the hand-wheel. Triangles (frame + leaf + dogs + wheel): 3x3 860 + 828 + 144 + 252 = 2,084; 4x4 1,108 + 1,008 + 216 + 252 = 2,584 (before: 1,120 each).
- **Personnel airlock.** Two D-shaped hatches in bulkhead plates with a hinge pin each, a hand-wheel on both faces of each hatch, a lamp column on both faces of each plate, an override lamp, a floor plate and a pressure-equalising line with a valve whose wheel turns while either hatch moves (decoration: the airlock has no pressure). Triangles: frame 1,148, each leaf 376, wheel 224 (drawn four times), valve wheel 180, three lenses 28 each (before: 408 + 96 + 96 = 600).
- **Status lamps.** Red when a leaf is open, amber while it moves, green when it is shut and latched; one lit lens drawn at full brightness over a dark recess in the frame.
- Inventory models are the whole closed door (`vault_3_item`, `vault_4_item`, `airlock_item`). The placement preview (`VaultDoorPlacementPreview`) now also draws the hand-wheel ring, the hinge pin and the sweep of the leaf in the door's own coordinate frame.

### Checks

- New `ContainmentDoorMotionTests`: the powered vault stroke against collision every tick in both directions (walkable exactly when the drawn swing is at least half, solid again before it shuts, sealed and unsealed at the right times), the 4x4 the same way, the airlock leaves the same way plus "never both drawn open", the whole airlock pass in ticks (outer walkable after 8 ticks, inner 20 ticks after the second command, because it waits for the outer to shut: about 1.4 s of door time), and the client stroke clock. `-PbwrContainmentDoorShots=<label>` (`ContainmentDoorShots`) saves 1920 x 1080 pictures of every door state for old/new comparison; `ContainmentClientCheck` now waits for the leaf to be half open instead of a fixed 75 ticks.

## Unreleased: plant computer (safety parameter display)

A new control panel component, **Plant Computer (Safety Parameter Display)** (`bwr:plant_computer`): a lit screen of text, numbers, bars, trend plots and green / amber / red tiles up to 4 x 4 blocks (default 4 x 3 blocks, a 31 x 15 character grid; 62 x 30 at 50 percent text). A CC:Tweaked program draws it, or, with no code, it shows a built-in page that reads the reactor it is bound to. **Read only towards the plant**: no outputs, no sinks, no redstone; a touched soft key only changes a built-in page or raises a CC event. Full API and limits: [PLANT-COMPUTER.md](PLANT-COMPUTER.md). Old saves load; nothing is written for a display that has nothing on it.

- **CC.** Every `bwr_panel` peripheral also answers to the type `bwr_plant_computer` (`peripheral.find("bwr_plant_computer")`). Eleven calls, each taking the display's label: `plantList`, `plantInfo`, `plantClear`, `plantClearPage`, `plantSetPage`, `plantTitle`, `plantSet` (defines an element from a table: `text`, `field` with unit and `warn`/`alarm` limits, `bar`, `trend`, `tile`, `key`), `plantValue`, `plantPush`, `plantRemove`, `plantFlush`; and the event `plant_computer_touch(name, label, page, key)`. The panel peripheral's argument conversion now also passes a Lua table of named settings and a single nil / number / string / boolean (panel calls only).
- **Bounded.** 8 pages; 48 elements a page and 160 a display; 12 trends of 8 to 120 points; 6 soft keys a page; ASCII text of 32 / 16 / 6 / 10 / 24 characters (line, caption, unit, id, title); unknown settings, off-grid elements, non-finite numbers and a repeated soft key are errors; a call rate limit (burst of 512, 32 a tick refill); one handled touch every 4 ticks. `PlantComputerTests.theLimitsKeepTheWholeStateInOnePatch` fills a display to every limit at once (12 trends of 120 points and 148 bars with the longest captions): the state is 14,280 bytes, smaller than one 16,384 byte content patch.
- **Compact state, delta sync.** The pages are a binary `PlantState` (elements of a few bytes, 16 bit trend points); it is the save, the full state a client gets and the base of every patch. A patch carries only what changed since the last (an element, a value, the new trend points, a page title, the page shown), written from the current state, so 1000 writes to one field are one value, a value that shows the same is nothing, and a program drawing every tick sends at most one patch every 4 ticks (5 a second) per display; `plantFlush` asks for the next one early (not twice within 2 ticks). Damaged or hostile bytes are ignored (3000 mutated states and patches in the test).
- **Built-in pages** (the wrench's Display setting): reactor overview (power, level, pressure, core flow, containment pressure with bars, the scram state), trends (the same with a history from the reactor itself) and flows and heat; they read the reactor the display's one input, `reactor`, is bound to with the Networking Wrench (only a reactor can be bound) once per panel sample, add a trend point every 1 to 60 seconds, and have no alarm limits, no tiles and no safe / unsafe colours. Three soft keys switch the page. Text size is the gauges' slider (50 to 200 percent).
- **Drawing.** `PlantScreen` lays a page out in panel cells once per change (text at one size, a share of the character cell and so of the Text size; made smaller down to 40 percent, then cut, to fit its room; never off the screen); the model records the shapes into the block's cached vertex buffer and the text into its cached text buffer. A display that is not changing costs the renderer nothing but replaying them.
- **`docs/lua/spds.lua`**: a complete SPDS program: the six critical safety functions (reactivity control, core cooling, heat sink, RPV integrity, containment, inventory) as tiles with the reason for the first one that is not green and a power trend, a page of four trends, a page of values with limits, and soft keys for the pages. Its limits are a table at the top of the file (the mod judges nothing). It updates and answers touches in two coroutines (every call to a display takes a server tick, so a touch arriving inside a call would otherwise be lost).
- **Tests.** `PlantComputerTests` (16 GameTests in the new namespace `bwrplant`; `-PbwrOnlyPlant` runs just them, the normal run includes them) cover registration, the codec and delta convergence over 400 random edits, the worst-case size, malformed input, every refusal of the API, both rate limits, the screen at every size and Text size, the no-program and built-in pages, save / reload / network, the built-in pages bound to a real formed reactor (the displayed numbers are the reactor's signals), the touch event, and that nothing a plant computer does can reach an output, a command or a redstone line (no outputs, no panel output set, the reactor's scram / rod demand / flow unchanged, and no class of it mentions the output or command seams). `PlantComputerCcChecks` runs the peripheral calls against the real CC classes. `LuaExamplesCheck` runs `spds.lua` on a real CC computer in six new scenarios (29 in all): two frames, an unformed reactor, a player touching a soft key, a built-in display, a wrong label, a display too small.
- **Client check and performance.** `PanelPlantShowcase`: eight client check stages (`-PbwrPanelClientStages=plant`), 1920 x 1080: the three built-in pages on a real reactor at 80 percent power through a feedwater upset, the SPDS pages, a display with no program and the SPDS at 50 percent text. `PanelPerformanceCheck` stages `plant-idle` and `plant-updating` (eight displays, each a text line, a number, a bar, a 60 point trend, 2 tiles and 2 soft keys, on a 16 x 6 block wall; two numbers and a trend point of every display moving once a second). Measured (window unfocused, mean frame time of the same camera, 15 blocks from the wall): `empty` 0.609 ms, `plant-idle` 0.561 ms, `plant-updating` 0.535 ms, so eight displays cost no measurable frame time; the 8 displays hold 504 quads in 8 block buffers built in 6.5 ms in all, and with two numbers and a trend point of every display changing once a second there were 80 more rebuilds in 14.2 ms (0.18 ms each, once a second a display).
- **Component list changes.** Item `bwr:plant_computer` (recipe: 3 glass panes, 2 graphic displays, a comparator, 3 iron ingots), lang entries, an inventory icon (`art/models/panel/icons/hardware_icons.py`), `GaugeScaleGuardTests.NOT_GAUGES` entry, the board client check skips it (its own stages). The wrench's enum labels: `component.bwr.option.Mode.*`.

## Unreleased: radiation tools (survey meter and area radiation monitor)

The plant models fission product releases, deposition and contamination, and what they do to players, but a player could not measure any of it. Two instruments now read it, in one unit, mSv/h, from the existing models only. Full description, the conversion and the extension API: [RADIATION.md](RADIATION.md). Old saves load; nothing here acts on the plant.

- **Survey Meter** (new item). Held, it shows the dose rate at you on a HUD readout and clicks like a Geiger counter (the vanilla comparator click, pitched up, at a mean rate of `0.4 + 2.5 x log2(1 + uSv/h)` a second, 40 at most). Carried anywhere in the inventory, it accumulates your personal dose (mSv, stored on the player as a data attachment, saved only above zero, kept through death; creative and spectator players record nothing). Right-click shows the reading and the dose, sneak-right-click resets the dose. The server samples four times a second for the holder only (one packet type, `radiation_readout`, server to client, on its own registrar; the menu and panel protocols are unchanged); a player with no meter costs an integer test per tick.
- **Area Radiation Monitor** (new block, mounts on any face). Amber lamp at the alert setpoint, red at the alarm setpoint (alert is a tenth of the alarm, default alarm 1 mSv/h), 10 % hysteresis, redstone 7 and 15, light 4 and 8. Empty-hand right-click reads it, empty-hand sneak-right-click steps the alarm setpoint up a ladder of presets. **No block ticker**: loaded monitors register with one service that refreshes the lamps once a second, and a reading is reused for 5 ticks however many panels and computers ask for it. Saves `HighSetpoint` only when it is not the default.
- **Panel and gauge input.** The monitor is a device of the signal layer: `dose_rate` (mSv/h), `high_radiation` and `radiation_alert` (BOOLEAN, for annunciators), `alarm_setpoint`, `alert_setpoint`, and the command `alarm_setpoint`. The Networking Wrench selects it and a component binds to it like any machine. **New quantity DOSE_RATE** (units mSv/h, uSv/h, Sv/h), appended after TEXT so no existing quantity number moved; a gauge whose **Measures** is Dose rate takes only these and refuses a level, a pressure or a power ("This gauge measures DOSE_RATE; ..."), and a gauge that measures anything else refuses the dose rate (line link and CC port alike); ANY takes everything as before.
- **CC peripheral** `bwr_radiation_monitor`: `getDoseRate`, `isHighRadiation`, `isAlert`, `getAlarmSetpoint`, `getAlertSetpoint`, `setAlarmSetpoint`, `getStatus` (with the dose rate split into `airborne`, `deposited`, `release`, `core`, `other`). Documented in SIGNALS.md and docs/lua/README.md.
- **The conversion** (`dev.bwr.core.radiation.DoseModel`, core). The containment hazard (fraction of the core's inventory) becomes 1.5e6 mSv/h per unit: the gamma power of a day-old inventory from the decay heat model (0.5 x 0.52 % x 3579 MW = 9.4 MW), spread through the containment's 8000 m3 (equivalent radius 12.4 m, the model's own in-plant half distance), times one calibrated structure factor of 1/100 checked against published accident readings. The existing effect thresholds then land at about 150 mSv/h (hunger), 1.5 Sv/h (weakness and poison), 15 Sv/h (wither). The airborne, deposited and released terms use the very functions the game applies to players (`ReactorSevereAccidentEffects.plantHazardAt` and `siteHazardAt`, factored out of `applyDoses`, no change in behaviour), so a reading and the effects on a player cannot disagree. The **core** itself is a gamma source (7 % of fission power and half of decay heat) behind the vessel wall, the water around the core, the biological shield; looking down an **open head** only the water above the fuel is in the way, and an uncovered core reads about 1200 times higher than a covered one.
- **Extension point** `dev.bwr.mod.radiation.RadiationSources`: `register(id, (level, pos) -> mSv/h)` for a whole kind of source (a spent fuel pool), `emit` and `clear` for a point source that comes and goes with a block entity. Both instruments, the CC `other` term and the tests pick them up.
- **Recipes, translations, tooltips** for the meter and the monitor; the recipe, translation and asset audits report 0 problems.
- **Tests.** `DoseModelTest` in `:core:acceptance`; GameTests `RadiationToolsTests` and `RadiationCcTests` (reads 0 at background; non-zero and rising near a contaminated plant; distance falloff term by term and zero past 256 blocks; closed vessel low, open head high; dose accumulates, persists and resets; the API; the monitor drives a wired gauge and an annunciator; incompatible gauge kinds refused; setpoint saves; lamp hysteresis and the 5 tick reuse; CC fields; recipes). Client check `-PbwrRadiationClientCheck` (three 1920 x 1080 pictures). Dev tooling: `-PbwrGameTestNamespaces=<list>` selects GameTest namespaces for a run.

## Unreleased: spent fuel pool (the fuel cycle gets its pause)

Fabricator, reactor, **pool**, reprocessing plant, MOX. Spent fuel now keeps its decay heat on its way out of the core, a pool holds it while it cools, and the reprocessing plant will not take it until it has. New blocks, a new data component, new GameTests and a client check; old saves load unchanged. Design, numbers, sources and limits: SPENT-FUEL-POOL.md. The fuel cycle table is in FUELS-AND-RODS.md.

### Decay heat of an assembly (core)

- `AssemblyDecayHeat` evaluates the reactor's own eighteen-group decay heat fit (`DecayHeat`) in closed form for one assembly from its burnup (operating time at the core-average 26.6 MW/t) and the time since it left the core. A 45 GWd/t assembly: 315 kW at discharge, 62.6 kW after an hour, 22.8 kW after a day, 0.88 kW after a year; below 50 kW after 124 minutes. Unit tests: the tabulated ANS-5.1 curve (1.3 %), the reactor's group solution (1e-9), Way-Wigner (30 %, as a cross-check), shape, scale, time to cool.
- `SpentFuelPool` and `SpentFuelPoolCooler` (pure Java): one body of water with ASHRAE evaporation (Antoine saturation pressure), passive loss, boiling at 100 C at 2257 kJ/kg, a level, the cooling train, makeup mixing, and fuel in air (180 kJ/K lumps, conduction and radiation, cladding burst at 565 C, zirconium ignition at 900 C, quench on reflood). `SpentFuelPoolTest` has twelve tests against hand calculations.

### Discharge time

- Unloading an assembly from a core stamps the game tick in a new data component `bwr:discharge_time` (`ReactorControllerBlockEntity.unloadAssembly`, and the fuel handed back when the controller is broken). It is separate from `FuelAssemblyData` on purpose: the refuelling code and its tests compare that record whole. No stamp means long-cooled (five years, a few kW at most), which is every old assembly. The item tooltip shows decay heat and time out of a core.

### Spent fuel pool (blocks)

- **Spent Fuel Pool Lining**, **Controller**, **Rack** and the **Makeup**, **Cooling Inlet** and **Cooling Outlet** ports. A rack is one single tier 4 m tall (assemblies are about 4.5 m): a column of exactly four rack blocks on the pool floor holding four assemblies, never on another rack (the pool refuses to form, with the reason); its top is always 4 m above the floor and "uncovered" means water below it. The pool must be deep enough for 3 m of shielding water over the rack tops (the real figure is about 7 m, scaled): a lined box 5 to 25 wide and deep and 8 to 16 high (interior at least 7 m), open at the top, surveyed by the controller (the pool's only ticker; lining, racks and ports have no block entity, found through a registry the controller fills). Capacity 1,000 kg a cell.
- Assemblies go in by hand (right-click a rack; sneak with an empty hand takes one out), through the controller's item handler (hoppers, pipes), or by the CC program. The cooling train is built into the controller: a circulation pump (400 FE a tick, the player's switch) and a 6 MW plate exchanger fed with cold water at the blue port and drained at the cyan one. Nothing starts, stops or trips by itself.
- Uncovered fuel heats, bursts, ignites and releases into a hazard index that doses nearby players through the reactor's contamination effects; makeup water quenches it.
- Panel (per-rack heat, total heat, level, temperature, time to boil and to uncover, cooling), CC peripheral `bwr_spent_fuel_pool` (`getStatus`, `getRacks`, `getAssemblies`, `setCooling`), a translucent water surface and the reactor's Cherenkov glow on fresh fuel.
- Saves: water, each assembly as its item (stamp included), fuel state, pump and cooler inventories, each key only when non-default.

### Reprocessing plant

- Will not start on an assembly above `reprocessingMaxDecayHeatKw` (`bwr-gameplay.toml`, default 50 kW, 0 off): the assembly is accepted into the slot and waits, the plant looks again once a second and starts by itself when it has cooled. Screen line, status text and peripheral fields (`assemblyDecayHeatKW`, `tooHot`, `secondsUntilCool`, `decayHeatLimitKW`).

### Other changes

- `ReactorSevereAccidentEffects.dose` is public (the pool doses players through it). `WaterLineNetwork` knows the three ports. New GameTest namespace `bwrpool` (`-PbwrOnlyPool`), client check `-PbwrSpentFuelPoolCheck`, GUI check scenarios `spent-fuel-pool*` and `reprocessor-hot`. `tools-make-spent-fuel-pool-assets.py` writes the textures, models, states, loot tables, recipes and tags.

## Unreleased: uranium ore (the mod's own uranium, with large veins)

The fuel fabricator's item route no longer needs another mod for uranium: the mod has its own ore, raw uranium, ingot and storage blocks, and world generation that gives biomes different amounts of it. Old worlds load as they were (old chunks have no ore; chunks generated from now on do). Full description, biome table, measurements and safety notes: URANIUM-ORE.md.

### Blocks, items, recipes

- **New:** `uranium_ore`, `deepslate_uranium_ore`, `raw_uranium`, `raw_uranium_block`, `uranium_ingot`, `uranium_block` (fuel creative tab). All four blocks need an **iron pickaxe**. The ores drop raw uranium (Fortune adds the vanilla ore bonus, Silk Touch drops the ore); the storage blocks drop themselves.
- **Recipes:** raw uranium, uranium ore and deepslate uranium ore smelt (200 ticks) or blast (100) to an ingot; nine raw uranium make a raw block, nine ingots a block, and each block makes nine back.
- **Tags:** `c:ores/uranium`, `c:ores_in_ground/stone`, `c:ores_in_ground/deepslate`, `c:raw_materials/uranium`, `c:ingots/uranium`, `c:storage_blocks/uranium`, `c:storage_blocks/raw_uranium`, `minecraft:mineable/pickaxe`, `minecraft:needs_iron_tool`. The fabricator reads the two uranium tags already, so our ingot and raw uranium feed it with no code change, and Mekanism's uranium keeps working beside it.
- **Textures** (`tools-make-uranium-assets.py`, Pillow): black pitch-like blobs in stone and deepslate with a few dull ochre weathering specks (real uraninite is black, not green), lumpy black raw block, dark metal block, and slab and lump icons in the style of the other fuel-cycle icons.

### World generation (no noise router change)

- **Super veins.** A custom feature `bwr:uranium_vein` places long winding ribbons of altered host rock (granite, tuff, calcite or andesite by group) with pockets of ore and the odd raw uranium block: 200 to 360 blocks long, 4,000 to 17,000 blocks of body, up to two side ribbons. Vanilla's noise settings and `NoiseRouter` are **not** changed or overridden. A vein is a pure function of the world seed and a 384 block grid cell, and every chunk places only the blocks inside its own 16 x 16 columns, so no chunk ever writes into another (loaded or not) and the slices of neighbouring chunks meet exactly in any generation order. Veins replace only host rock (`#bwr:uranium_host_rock`), and not within 6 blocks of the surface.
- **Small blobs** beside them (vanilla's ore feature behind the server option).
- **Biome groups by real geology**, in datapack JSON driven by biome tags and NeoForge biome modifiers (`data/bwr/neoforge/biome_modifier/uranium_<group>.json`): `arid` (desert, badlands: sandstone-hosted roll-front deposits; 85 % of cells have a vein, grade 34 %), `highland` (windswept hills, mountains: granite-hosted; 50 %, 28 %), `semi_arid` (savanna; 30 %, 24 %), `lowland` (plains, forests, taigas, jungles, swamps; 12 %, 18 %), `aquatic` (oceans, rivers, beaches; 2 %, 12 %). Every number is in the JSON (chance, grade, raw block share, vein shape, blob size and count, height range); each ordinary overworld biome is in exactly one group.
- **Safety.** Only newly generated chunks get ore: nothing listens for a chunk loading, there is no retrogen, and generation writes only into the chunk it is placed in. New server option **`uraniumOreGeneration`** (`serverconfig/bwr-gameplay.toml`, default `true`): the mod's own biome modifier type `bwr:add_uranium_features` adds nothing when it is off (a NeoForge `neoforge:conditions` entry would be evaluated before the world config exists), and the vein and blob features read it for every chunk. Removing the mod leaves unknown blocks that load as air. Mekanism's own uranium generation is separate; a pack can turn either off (URANIUM-ORE.md).
- **Measured** (`:mod:runWorldgenCheck`, same 64 x 64 chunks in five flat dimensions that differ only in biome): uranium blocks a chunk 12.55 arid, 5.39 highland, 2.62 semi-arid, 0.93 lowland, 0.10 aquatic. Largest connected vein body in the arid square 12,023 blocks, 159 x 23 x 182 (span 242 blocks); in real terrain 5,150 blocks, span 151, cut by the caves. The vein feature costs about 50 microseconds a chunk. With the option off a block of chunks over a vein got 0 uranium; the chunks generated round them afterwards got uranium and none of the first block changed.

### Tests and checks

- GameTests `UraniumOreTests` (new namespace `bwruranium`, `-PbwrOnlyUranium` runs just them): registration and iron-pickaxe level, all tags, recipes (smelting, blasting, nine to one both ways), loot (raw uranium, Silk Touch, Fortune III averaging 2.2, storage blocks), the fabricator making an assembly from our ingots and from raw uranium (and Mekanism's items counting when it is loaded), the vein shape (long, one connected body, the chunk slices add up to the whole vein block for block, no chunk given a block outside it), the grade scaling the ore, the biome table read back from the loaded datapack (exactly one group per ordinary biome, strictly falling numbers, the same shape in every group), and the server option on the biome modifier.
- New `:mod:runWorldgenCheck` (a dedicated server on a fresh world plus the datapack in `mod/src/devtest-pack`; fails the build without `BWR WORLDGEN CHECK: PASS`) and client check `-PbwrUraniumClientCheck` (1920 x 1080 pictures of a vein, the blocks and the items). Both are dev-only (the devtest package is not in the jar).
- `tools-audit-assets.py` lists the two ores as made by the world (no recipe), `tools-audit-recipes.py` as not craftable with the reason; neither lost a check.
- **One existing test's exception list gained two entries**, not an assertion: `FuelFabricatorItemRouteTests.everyCraftableItemHasARecipeAndTheRestDoNot` keeps a copy of the list of items that are not crafted, and the two uranium ores (made by the world) are on it as they are on `tools-audit-recipes.py`'s.
- `mod/build.gradle`: the default GameTest namespaces are now `bwr,bwrtick,bwruranium`.

## Unreleased: flaky GameTests (chunk forcing and waiting for the chunk system)

Test-only; no game behaviour changed. Six GameTests failed now and then, the batch they were in and the load of the machine deciding which. Three causes, all in how the fixtures treat chunks.

- **Forced chunks had no owner** (`placementSnapsCollidesAndConsumesItems`). `ServerLevel.setChunkForced` is a flag, so when `Alpha31OpeningsAcceptanceTests.forcedAirlockIsAMediumReactorLeak` forced and released the chunks around (44912, 44912) in one tick it also released the chunks of the panel fixture at (44900, 44900) that the other test had just forced, and that test then waited its whole 200 ticks for chunks nobody held any more (the log showed them `forced=false`, no chunk object, for 159 polls). It only happened when both were in one batch, and the batches are cut by test count, so it came and went with the `-PbwrNoCC` layout and with every added test. New `FixtureChunks` keeps the owners of every forced chunk: a chunk is released when the last owner lets go. Without an owner the calling test class is the owner; the stages (panel, hardware, networking, core display, mimic, plant kit, chunk lab, containment rooms) pass their own origin or instance. A `FIXTURE CHUNKS SHARED` line names two fixtures that overlap.
- **Waits counted ticks, the chunk system works in milliseconds** (`tankDismantleAcrossUnloadedChunk`, `switchingTheLookKeepsAChosenTileAndMovesADefaultOne`, `unboundUnformedAndOldSaves`, `steamNetworkSplitAcrossChunksSurvivesSaveAndReload`). The GameTest server runs a tick in about half a millisecond, but a chunk's entities come back from disk on the IO threads (a chunk that is forced and entity ticking still reads `areEntitiesLoaded` false until they do, and its block entities do not tick before), so "at most 80 or 160 ticks" was 40 to 80 ms, and 20 chunks took 153 ticks on a quiet machine. New `FixtureWait.pace` gives a polling wait the length of a real tick (once per server tick however many tests wait; a fixture that is ready does not call it), so a bound of N ticks is N / 20 seconds as in the game. Used by the entity-lookup probe, the panel, desync and mimic stages, the core display fixture and `Script.awaitChunks`. No timeout or assertion was changed. The end of a run prints `FIXTURE WAIT` with the most polls any wait needed.
- **`steamNetworkSplitAcrossChunksSurvivesSaveAndReload` was not a save/load bug.** The pressure is saved and restored exactly (1026.33 psi saved, 1028.72 on the first tick of the reloaded reactor, two ticks of this fixture's 1.2 psi per tick climb); a new assertion in the test says so (within 10 psi). The 60 psi limit measures how many ticks the reload leaves the plant in pieces: the reactor forms as soon as its chunk's entities are back, the outlet's block entity (which finds its nozzle on its first tick) only when its own chunk's are, and the reactor climbs 1.2 psi a tick meanwhile. With a disk busy beside the run the outlet was 28 ticks late and the pressure came back at 1089.19 (`pressure jumped across the reload: 1026.33 -> 1089.19`); with the waits paced the same load gave 1060 to 1065.
- **`anOldSavedTrimBlockLoadsAndBecomesMountable`** waited a fixed 120 ticks and then took "the chunk is not there" (`hasChunk`) for "the chunk was unloaded". A chunk whose ticket is dropped stops answering `hasChunk` at once but is only saved and unloaded some ticks later, and the ticket the test then added took the same chunk object back out of the pending unloads (an instrumented run got the same object back on two retries running): nothing was read from disk, no load event fired, and the blocks stripped of their entities in memory stayed stripped ("the load did not give the old block a panel block entity"). The test now waits for the chunk's `ChunkEvent.Unload`.

## Unreleased: real brand names out of the panel styles

- **Display names only.** The panel Style choice that read "GE" now reads **General Power**, and "Westinghouse" now reads **Westingapartment** (`component.bwr.option.Style.ge` and `.westinghouse` in the language file). "Yokogawa" (the DX1000 and DX2000 recorders) and "Terry" are unchanged.
- **Nothing saved or loaded changes.** The option keys (`ge`, `westinghouse`), the saved values, registry ids and the model files (`ge_sb_*`, `w2_*`, `ge_ab40`, `ge_180_*`) keep their names, so old worlds and data pack boards load as before. No texture or model carries a maker's lettering (the models are vertex coloured, and the game draws no maker legend), so none was regenerated.
- README.md, CONTROL-PANELS.md and SIGNALS.md use the new names. Factual citations of real documents and designs (catalog dimensions, reference data, the real GE XX-YY coordinate convention in the specs) are left as written.
- `neoforge.mods.toml` no longer says the plant can only be controlled with redstone without CC:Tweaked (panels exist), and TESTING.md no longer carries a fixed GameTest count that goes stale.

## Unreleased: reprocessing plant and the MOX route (the fuel cycle closes)

A spent assembly can now be taken apart for plutonium and uranium, and the plutonium made into MOX fuel: uranium and steel make fuel in the fabricator, the reactor burns it, the **Reprocessing Plant** returns plutonium, depleted uranium and fission-product waste, and the fabricator makes MOX from the plutonium and the depleted uranium. GUI network protocol is now 24 (the fabricator's snapshot changed and there is a new menu): update client and server together. Old saves load. Numbers table, model and reasons: FUELS-AND-RODS.md.

### Reprocessing Plant

- **New machine block** (machine tab), styled like the fabricator: block, block entity, screen on the shared style, item capability with sided slots (spent assembly in; plutonium nuggets, depleted uranium and waste out; a hopper above sees only the input, one below only the outputs, the sides all four; a pipe cannot take the input back out or fill an output), FE power (200 ticks and 150 FE a tick, 30,000 FE an assembly, 100,000 FE buffer) and the CC peripheral `bwr_reprocessing_plant`. An idle plant costs a boolean test a tick.
- **Input:** a spent assembly, burnup above zero, including a burned depleted uranium blanket and a burned MOX assembly. A fresh assembly is refused at the slot, through a pipe and in the screen, and is never worked on even if one is in the slot some other way. **Thorium assemblies are refused too** (no plutonium chain; their U-233 would be returned as a mislabelled uranium item).
- **Outputs:** `bwr:plutonium_nugget` (nine to a new `bwr:plutonium_ingot`, crafted both ways; tags `c:nuggets/plutonium` and `c:ingots/plutonium`), `bwr:depleted_uranium` for the recovered uranium and `bwr:fission_product_waste` (uncraftable, nothing uses it; in `tools-audit-recipes.py`'s exception list with the reason). **Recovered uranium is returned as depleted uranium ingots** because reprocessed uranium is close to tails assay and returning it as the tails item can only lose surplus U-235, never mint it (reasons in FUELS-AND-RODS.md).
- **The amounts come from the assembly's own data** through `dev.bwr.core.fuel.SpentFuel` (core): the waste is the heavy metal that was fissioned (from the fuel's `heat_per_fission_mev` and the burnup), the fissile left is the mod's own net balance read back through the enrichment response (`FuelAssembly.fissileEquivalentWeightFraction()`, new: the fuel's `depletion_rate` and `conversion_gain_k_inf` enter here), the plutonium bred is what balances the fissions, and the uranium is what is left. One assembly is 4 ingot-units of heavy metal, as in the fabricator. Spent LEU at 45 GWd/t gives 0.265 plutonium nuggets, 3.783 depleted uranium and 1.683 waste; a depleted uranium blanket at 20 GWd/t 0.085, 3.907 and 0.748; spent natural uranium at 10 GWd/t 0.121, 3.945 and 0.374 (full table in FUELS-AND-RODS.md). One model constant is not from the mod's data (`SpentFuel.MIN_CONVERSION`, 0.15, a floor on conversion where the k-infinity curve flattens), and the fissioned mass uses 235 u for every fuel.
- **Fractions are kept, so nothing rounds into or out of existence.** The plant holds each product as a count of grains (a millionth of a nugget, saved as longs), releases a whole item when it holds one and carries the rest into the next assembly. The three amounts of an assembly add up to its four ingots to the grain, and so does everything out plus everything held across any number of assemblies. Items that do not fit an output slot wait in the plant and it does not start another assembly while a product has a full stack waiting; breaking the block drops the whole items it holds.
- **Saves.** `Energy`, and `WorkTicks`, `PlutoniumGrains`, `UraniumGrains`, `WasteGrains` and the four slots only when they hold something.
- **Recipe, ungated:** reactor vessel, two obsidian, two hoppers, a blast furnace and three iron ingots (`c:ingots/iron`); no Mekanism needed (the fabricator's recipe is Mekanism-gated).

### MOX in the fuel fabricator

- Three more steps on the item route's selector: `MOX 4.50 %`, `MOX 7.00 %` and `MOX 9.50 %` (ids `mox_lean`, `mox_standard`, `mox_rich`, making the fuel types `mox_lean`, `mox` and `mox_rich`). The plutonium fraction is the fuel type's own `nominal_enrichment`; the cost is `dev.bwr.core.fuel.MoxMassBalance` on the same 4 ingot-unit model: plutonium nuggets rounded up (2, 3 and 4), the rest of the four ingots as depleted uranium matrix rounded up (4 each), 4 steel, 5.6 s and 22,400 FE. Nothing is returned.
- **No new slot and no change to the four a pipe sees.** On a MOX step the slot that takes uranium takes plutonium (nuggets or ingots, an ingot is nine) and depleted uranium instead, each into its own store held up to twice an assembly's worth, and the slot only takes what its store has room for, so it cannot jam on the wrong item; on a uranium step it refuses them as before. `mox` (the fuel name) is still not a target and `TARGETS` is still the seven-step uranium ladder: the selector command that took an index into it (`CMD_TARGET`) keeps its range, and a new `CMD_SELECT` takes an index into the whole ladder, uranium then MOX.
- **Saves.** `PlutoniumNuggets` and `MatrixIngots`, only when non-zero; an old fabricator loads exactly as before. The CC peripheral `bwr_fuel_fabricator` adds `plutoniumHeldNuggets` and `matrixHeldIngots`.
- **Screen.** Small, separate additions to `FuelFabricatorScreen`: the selector counts in the whole ladder (with a `MOX` prefix on its label), a MOX cost line and a `Pu, DU in` row shown only on a MOX step (the uranium cost line and `Uranium in` row are hidden then), and the first caption reads `Pu, DU`.

### Tests

- Core: `SpentFuelTest` (ten spent assemblies pinned grain for grain against a separate implementation of the model; the split always sums to the assembly; fresh gives nothing; waste is the fissioned mass; plutonium rises and levels off; the blanket breeds and the gain is what adds plutonium; the fissile balance inverts the enrichment response; recycled MOX returns part of its plutonium) and `MoxMassBalanceTest`.
- GameTests, `ReprocessingTests`: the worked outputs for six spent assemblies through the real block entity (items and held fractions, 200 ticks); the energy cost is exact (one tick of energy short consumes nothing); a fresh or thorium assembly is refused everywhere and never worked on; the plutonium fractions of 60 assemblies of five fuels are conserved to the grain; outputs nobody collects back the plant up and lose nothing; the sides a pipe sees; the save keys and a plant that carries on after a reload; the recipes (the plant's needs no mod) and the items. `FuelFabricatorMoxTests`: the MOX costs, exact consumption of plutonium, depleted uranium and steel (short of any one makes nothing), an ingot is nine nuggets, the slot's rules on each kind of target and that it cannot jam, old fabricator saves still load and the MOX keys appear only when held, the selector command's range and the snapshot, and the whole cycle (14 spent LEU assemblies through the plant, then its plutonium and depleted uranium into the fabricator, give a fresh MOX assembly). With CC:Tweaked, the plant's and the fabricator's peripheral fields.
- **One existing test's exception list gained an entry**, not an assertion: `FuelFabricatorItemRouteTests.everyCraftableItemHasARecipeAndTheRestDoNot` keeps a copy of `tools-audit-recipes.py`'s list of items that are not craftable, and `fission_product_waste` is on both now.
## Unreleased: fuel fabricator polish (recipe without Mekanism, GUI text that fits)

- **A recipe for the fabricator without Mekanism.** `fuel_fabricator.json` (Mekanism parts) is unchanged. New `fuel_fabricator_no_mekanism.json`, loaded only when Mekanism is NOT (`neoforge:not` of `neoforge:mod_loaded`): four steel or iron ingots (`c:ingots/steel` or `c:ingots/iron`), a comparator, two redstone blocks, a blast furnace and a reactor vessel, in the Mekanism recipe's pattern. Exactly one of the two loads. Steel for the item route itself must still come from a mod that tags `c:ingots/steel`.
- **Audits.** `tools-audit-recipes.py` now evaluates each recipe's `neoforge:conditions` (mod_loaded, not, and, or) for an install with Mekanism and one without, and fails an item whose every recipe is gated out in either (or a condition it cannot evaluate). The GameTest `everyCraftableItemHasARecipeAndTheRestDoNot` no longer excuses `fuel_fabricator` when Mekanism is absent (its only edit: the fabricator must have a recipe in both installs).
- **Screen text.** The cost line reads "Costs 172 uranium, 4 steel" (the 20 % grade no longer ends in an ellipsis), falling back to shorter wording only for a datapack grade too costly to fit; the tooltip is unchanged. The panel is 416 px wide instead of 368 (a smaller window still shrinks it to the smallest GUI size), so the right column's "Would make" value fits, and the chemical feed line wraps to two lines instead of being cut. The GUI check also runs at GUI scale 3 now (2, 3 and 4) and has a new scenario, `fabricator-feed`, with a real Mekanism feed line.
- **Tests.** GameTest `theFabricatorRecipeLoadsWithAndWithoutMekanism` (which of the two recipes is loaded, exactly one maker, and without Mekanism the vanilla recipe crafts from vanilla items and rejects a furnace or gold).

## Unreleased: control-room recipes, recipe collision audit

The placeholder control desk recipes are real now, the other control-room recipes were checked against each other, and the recipe audit
finds collisions and circular recipes.

### Recipes

- **Control desks.** The 1 m desk (`control_panel_desk`) is a console over a vertical board over an iron ingot (9 ingots, was 8); the 2 m desk
  (`control_panel_desk_wide`) is two desks with an iron ingot between them (19, was 16: it now costs a little more than two). Duplex
  column: header strip and iron ingot over two vertical boards over a benchboard (13 ingots and a pane, was 10 with no header at all).
- **Wall display (3 x 2).** It cost less iron than the 2 x 2 (6 ingots against 8) although it has six blocks to its four. It is now the 2 x 2
  display between two black glass panes, under three ingots (11 ingots, 3 panes).
- **Control stand** is a glass pane over a vertical board over an iron ingot; **header vent** takes iron bars (vertical board, two bars, an
  ingot, makes two). The old versions were the same ingredients as the outside corner and the base plinth, so those could not be crafted.
- **Same ingredients, same grid: the other collisions.** Six machine pairs had identical recipes, so one of each was uncraftable. Changed:
  `makeup_water_pump` (hopper in place of the comparator), `natural_draft_tower` (white concrete in place of smooth stone),
  `suppression_pool_drain` (black dye for blue), `turbine_control_valve` (repeater for the comparator), `full_core_display` (comparator
  for the redstone), `grid_tie` (copper ingots for the redstone). Recipe ids and results are unchanged.
- **Plinth apron** was 2 x 2 iron ingots, which is also vanilla's iron trapdoor recipe, so it could not be crafted at all (the new GameTest found it; the file audit cannot see vanilla). It is now ingot, vertical board, ingot in a row (2 made, still 2 ingots each).
- None of the control-room recipes uses Mekanism; they are the same with and without it.

### Audit and tests

- `tools-audit-recipes.py` now fails on two crafting recipes with the same ingredient pattern (shaped against shaped, mirrored too, shapeless
  against shapeless, and shaped against shapeless, unless their `neoforge:conditions` exclude each other) and on a circular dependency
  among bwr items. It found 8 collisions in the old recipes.
- `ControlRoomRecipeTests` (new GameTests): every desk and every other control-room piece is laid out in the grid exactly as its recipe says
  (and mirrored) and the loaded recipe manager must return that recipe; the iron cost through every panel a desk is made from must grow
  with its size; no ingredient may come from Mekanism.
- CONTROL-PANELS.md has a recipe table for the control-room furniture.

## Unreleased: generator panel state, board check setup

- **Generator panel chip.** The Running/Stopped chip on a nuclear generator's panel read Stopped while it was generating: `PowerModuleBlockEntity.running` was set from steam flow in `expand`, and a generator passes no steam. `generate` now sets it from the electrical output (running while it produces power). The Lua peripheral's `running` field for a generator follows the same value. New assertions: `PowerModuleRuntimeCheck` (running equals "electricMW above zero" on every tick of the parallel plant, the generator produces at least once, and the same link holds after a reload) and `PowerModuleClientCheck` (the generator panel's menu reads running while it produces).
- **Board check `blueprint-ghost-blocked`.** The check set the obstacle block on the server and sampled the preview after a fixed number of client ticks, so a stalled server could leave the client without the block and the check saw "0 blocks in the way". The check now waits (bounded at 600 ticks) until the client level shows the obstacle before it samples; the assertion and its expected value are unchanged.

## Unreleased: RPS scram legends are not slanted (a view effect), with an orthogonality guard

The owner reported that the legends of the RPS manual scram control (the "A1 B1 A2 B2" switch legends, the "MAN SCRAM" plate) look slanted, like italics. They are not: the text is laid out on an exact orthogonal basis on every panel type, and what looks like italics is the perspective of a sloped face seen from the side. Nothing in the renderer changed in behaviour.

### Cause

- **Not a shear in the text transform.** Every legend is drawn through one matrix (the face's column step, its row step brought to a true length by the row aspect, the face normal; then tilt, rotation, scale). For all 11 panel types, 4 facings, 8 mount angles, 3 tilts and 2 scales its three axes have the same length, are pairwise orthogonal, are not mirrored and lie in the face plane. The frames themselves are orthonormal.
- **Not glyphs on the wrong plane.** The plane the legends are laid on is a face of the block's own model for the nine faces checked (the plate corners are within 0.0000 block of it), except the header strip, whose model has a field 0.05 block behind the rim the parts are mounted on (a design of the model, noted, not changed).
- **It is perspective.** The glyph's vertical on a sloped face (bench, console, desk, chamfer, duplex bench) is not a vertical line in the world, so a camera that is not square to the face sees it leaning, at the same angle as the plate's own edge. A vertical board's text never leans from any side (a vertical line stays vertical), which is why "WHITE LAMP LIT" on the board above looked upright in the owner's picture and "MAN SCRAM" on the bench did not. The new pictures reproduce it: straight on, no lean; from the operator's left and low, the duplex bench's glyphs lean 69 degrees on the screen, the benchboard's 39, the console's 68.

### Guard

- `PanelRenderContext.textBasis` / `orientLegend` (the draw code of every legend, extracted, same maths) and `basisViolations`: lengths equal, axes orthogonal, not mirrored, in the face plane when untilted.
- The panel client check asserts it by arithmetic for every `PanelType` x facing x mount angle x tilt (`PanelRpsLegendShowcase.verifyBasisMath`), and audits the matrix of every legend the renderer really draws in the run (`PanelRenderContext.debugBasisAudit`; about 90 000 legends in the RPS stages, none bad).
- 20 new stages `rps-legends-<face>-front|oblique` (ten faces, 1920 x 1080) place the plate and two assemblies with the legends A1 B1 and A2 B2 on each face, assert that the plate corners lie on the face of the block's model, and log the on-screen lean of the glyph vertical (0 straight on and on upright faces, large on sloped faces from the side).
- Revert proofs: a shear injected into the legend basis fails the check with `lengths x/y/z ... cos(x,y)=0.287348 (shear 16.699 deg)`.

## Unreleased: reactor panel draws in a fraction of the time

The reactor panel screen cost about 2.2 ms of CPU a frame (15 x 15 and 21 x 21 cores alike, which is the largest core there is) and now costs about 0.25 ms, with the same pixels. Nothing about what it shows or how often it updates changed.

### What was slow

The cost was not the core map (a 21 x 21 core costs the same as a 15 x 15 one) and not the layout. `GuiGraphics` flushes after every fill and every string unless a screen holds its buffer open, so the panel made one GPU draw call for every frame line of a button, every row label, every value and every unit: a couple of hundred calls a frame, each a few microseconds of driver work. Under that, every string was rebuilt (a `Component`, then bidirectional reordering) and measured again each frame, several times over for the same text.

### What changed

- **One draw pass.** `BwrScreen` can draw its whole body, widgets and elements inside one held-open buffer (`batchedDrawing()`, off by default; the reactor panel turns it on, a screen with item slots must not). Inside it `Ui.text` holds strings back and draws them after the fills, just before a scissor changes (`Ui.enableScissor` and `Ui.disableScissor`, which the scroll areas and the core map now use) or at the end, each with the pose it was drawn under, so a run of strings is one draw call. Text lands over every fill of the same pass, which is what this screen draws anyway.
- **Shaped text is kept.** `Ui` keeps each string's glyph sequence and width, per type, until the font, the language or the resources change (`UiTextCacheReload` also clears it on a reload), and the sentence-case form of a button label.
- **The core map** works out each cell's colour once per client tick rather than every frame, and skips its clipping scissor when no cell reaches past the well (it only does when the map is zoomed or panned).

### Checking it

- `-PbwrGuiPerformanceCheck` now reports mean, median, p95 and max per stage (`GUI PERFORMANCE STATS`), takes `-PbwrGuiPerformanceStages=4,6` to run only some stages (4 is the 15 x 15 core, 6 the 21 x 21 one), and fails the reactor-panel stages if the best of three 3-second windows has a median over 0.8 ms (`-Dbwr.guiPerformanceBudgetMs`). `-PbwrJfr=<file>` now works for the client checks too (1 ms sampling).
- `-PbwrGuiPerformancePixels` (with the stages above) freezes the panel's numbers at fixed values, then takes nine 1920 x 1080 pictures per core size (flux, hover, tooltip, rods with a selected rod, info, scrolled info, keyboard focus, button hover, button held down) and draws each one twice in the same session, once batched and once the legacy way (`Ui.setLegacyDrawing`), comparing every pixel of the panel. Any difference fails the check.
- Measured on this PC, interleaved with the old drawing, three runs each: mean 2.17 ms (p95 3.4) before and 0.25 ms (p95 0.37) after on the 15 x 15 core, 1.83 ms (p95 2.8) and 0.26 ms (p95 0.37) on the 21 x 21 one. The old and the new build, screenshotted in the same scenarios, differ in 0 pixels of the panel.

## Unreleased: controller station PID, dark-lamp synchronising lamps

The owner's two decisions after the end-to-end check below.

### Controller station: a built-in PID, in AUTO only

- **It runs only while the player has selected AUTO.** A new station is still in MAN with OUT 0 and nothing runs in MAN. In AUTO the station moves `demand` so that the process variable follows the setpoint, once for every sample of the panel (0.1 s by default). No new port: the settings are config (the Panel Wrench screen) and the `demand` output carries the result, so a machine wired to `demand` follows the PID.
- **The law** (`StationPid`, the velocity form on percentages of the span): `du = Kp * ( -dPV + dt / Ti * e - Td / dt * d2PVf )`, `e = SP - PV` (reverse action flips the signs). The proportional and derivative terms act on the measurement, so a setpoint change goes through the integral and never kicks OUT; the derivative is filtered (Td / 8).
- **Config:** Kp 0.01 to 100 (default 0.5), Ti 0.5 to 3600 s (20), Td 0 to 600 s (0, off), reverse action (off), output low and high limits 0 to 100 (0 and 100). The defaults are conservative: slow, and stable on a plant with a unit gain and lags of a few seconds. All are saved only when not default, so old saves load as before.
- **Bumpless both ways:** the state is the demand itself. Selecting AUTO starts from the demand MAN left (the first samples only learn the PV); selecting MAN keeps the last output. Nothing but the settings is saved.
- **Anti-windup:** the new demand is clamped to the output limits and there is no separate integral, so OUT leaves a limit as soon as the error changes sign.
- **No PV signal in AUTO** freezes OUT and forgets the memory (nothing winds up, nothing kicks when the signal returns); so does a gap of more than 5 s between samples.
- **An outside demand overrides it:** with a signal on `auto_demand` AUTO follows it (at once when AUTO is selected, then on every sample) and the `demand` output follows it too, so the bar is always the output and MAN after AUTO never bumps. Take the signal away and the PID starts from where OUT is.
- **The sampler:** a station in AUTO keeps its panel sampled (`ControllerStationType.keepsSampling`); a panel with a station in MAN and no live input costs nothing, as before.
- **Proof, in a real plant run** (a real recirculation pump, a first order lag of 3 s accelerating and 11 s decelerating, `demand` wired to its `speed` command and `pv` to its measured speed through the Networking Wrench and the binding payload; SP steps are the RAISE button): tuned Kp 1, Ti 5 s: step 0 to 50 no overshoot, settled to 2 % in 28.8 s; 50 to 70 no overshoot, 21 s; 70 to 40 an undershoot of 1.7 % (the pump's slow way down), 20 s; the last minute within 0.5 %. The defaults (Kp 0.5, Ti 20 s): 40 to 70 no overshoot, 159 s. Disturbance: the actuator loses 40 % of its gain, the PV dips 8 % and is back within 2 % in 28.6 s with OUT 50 to 83. Limits 20 and 60: OUT never leaves them, and leaves the high limit within half a second when the SP drops (no windup). PV lost for two minutes: OUT frozen to the last bit, no jump when it returns. Transfers: AUTO leaves OUT at 37.0, MAN keeps the last output while the PV is pushed around.
- **Existing assertions changed (authorised), old to new, in `ControllerStationTests`:**
  - `inAutoTheBarShowsTheBoundDemandAndTheStationOnlyMovesItsOwnSetpoint`: `out(st, 0) == 30.0` "the station's own demand output is left where MAN left it" to `out(st, 0) == 70.0` "with an outside demand bound, the demand output follows it in AUTO"; `!op(SET 55) && out(st, 0) == 30.0` to `... == 70.0` (a program still may not SET the demand in AUTO); `out(st, 4) == 51.0 && out(st, 0) == 30.0` to `... == 70.0`; `out(st, 2) == 1.0 && out(st, 4) == 51.0 && out(st, 0) == 30.0` to `... == 70.0`.
  - `nothingMovesByItselfNoControlLawRunsInTheStation`: `out(st, 0) == 0.0 && out(st, 1) == 1.0 && out(st, 4) == sp` "AUTO with a deviation: demand" to `out(st, 0) > 0.0 && ...` "the PID raises the demand"; and in MAN, `out(st, 0) == 0.0` "MAN with a different deviation: demand still" to `out(st, 0) == held` (the demand when MAN was pressed). The MAN and placement half (a new station is MAN, nothing runs in MAN) is covered by `autoStepResponseConvergesWithoutOscillationAndNothingRunsInMan`.
  - The ports assertion is unchanged (no new port).

### Synchronising lamps: the dark lamp method

- **Both lamps follow one curve now.** Both are wired across the open breaker, so each sees the difference of the generator and the grid voltage, `2 V |sin(delta / 2)|` for equal voltages; the lamp's power goes as the square, so the brightness is `sin^2(delta / 2)` of the brightest: dark in phase, half at 90 degrees, full at 180 degrees out of phase, continuous in between, and it pulses at the slip frequency as the pointer turns. Before, the right lamp was the "bright lamp" (full in phase). With no signal or the breaker closed both are dark. The mod models no voltage magnitude, so there is no magnitude difference added. Sources: the Wikipedia article "Synchronization (alternating current)" (lamps dark when the generator voltage matches the system, bright when opposite), the NJIT ECE 342 lab 6 notes (the dark method: lamps across the open breaker, close when all are dark) and the phasor arithmetic for the 2 V sin(delta / 2). No legends were added (the dial still has three).
- **Existing assertion changed (authorised), `SynchroscopeTests.pointerArithmeticWrapsAdvancesFollowsAndLightsTheLamps`:** `near(SyncMath.darkLamp(phase) + SyncMath.brightLamp(phase), 1.0)` "the lamps are complementary at" to `near(SyncMath.darkLamp(phase), sin^2(phase / 2)) && near(darkLamp(phase), darkLamp(-phase))` "both lamps follow sin^2(phase/2), the same either side, at". `SyncMath.brightLamp` is gone; `SyncMath.lampBrightness(index, phase)` is the one curve for either lamp. The lamp colour fields are now "Left lamp colour" and "Right lamp colour".
- Tests: `ControllerStationPidTests` (batch `controllersync`: step response, disturbance, limits and windup, PV loss and transfers, outside demand and the real sampler, action/config/saves, the lamps). Client check: a station running its PID on a real control valve, and three synchroscopes at 0, 90 and 180 degrees.

## Unreleased: controller station and synchroscope verified end to end

The manual/auto controller station and the synchroscope were driven in real worlds against real machines, through the Networking Wrench, the binding payload the panel link screen sends and the panel's own click path. Most of it was right already; the three things that were not are fixed.

### Fixed

- **A station wired to a machine seemed not to drive it.** The panel link screen started every output binding at Scale 1, but the station's `demand` is 0 to 100 and most commands run 0 to 1 (a valve, a pump speed) or 0 to 1.2 (recirculation flow): the machine refused everything above 1 percent. The screen now starts a station's `demand` binding at the command's top divided by 100 (0.01, 0.012, 15 for a 1500 MW tie); any other output still starts at 1 and the field can still be edited (`BindModel.defaultOutputScale`).
- **A grid tie's MW commands were listed up to 48,000 MW.** That is the engineering bound the tie declares; it refuses anything over the server's `gridTieMaxMW` (1,500 by default). The binding screen now lists `fixed_mw`, `remote_mw`, `curve_min_mw` and `curve_max_mw` up to the effective cap (`WiringOps.shownMax`), so the default scale above is right for a tie too. The commands themselves are unchanged.
- **The synchroscope's two lamps looked like pushbuttons.** They were square caps in the same style as the station's AUTO and MAN buttons, with no legend, and nothing on the instrument can be pressed. They are now round lamp lenses in a bezel (the dim and lit states are round too).

### Checked and found right (no change)

- **MAN:** RAISE, LOWER, a held RAISE, the wheel and the Step setting move OUT, and a turbine control valve, a bypass valve, a recirculation pump, a feedwater pump, an LPCS pump, a grid tie (`fixed_mw`, `remote_mw`) and the reactor's boron injection follow it exactly, from 0 % to 100 % (a real valve strokes to the demand); the SP is shown and drives nothing.
- **AUTO:** RAISE and LOWER move the local SP only; selecting AUTO, saving and reloading the panel send nothing to the plant. (At the time of this entry the station had no control law; it has a PID in AUTO now, see above.) The mode, OUT and SP survive a save, a placed station starts in MAN with OUT 0, and a missing machine, an unbound station or a zero span station do not throw.
- **Synchroscope:** against a real HP/LP turbine and generator rolled and trimmed through the real control valve, the bound `phase_degrees` turns the pointer clockwise (FAST) for a fast generator and anticlockwise (SLOW) for a slow one, 360 degrees a second per hertz of slip; a CLOSE pushbutton wired to `close_breaker` closes in phase with no damage, the tied generator delivers and its FE equals the tie's, and TRIP opens it. Closing out of phase is allowed and damages the generator by the grid code's law (56 degrees: damage 1, failed).

### Tests

- `ControllerSyncWiringTests` (batch `controllersync`): the station against seven real machines in MAN, AUTO and transfer, save and reload, a broken link, the reactor's boron and recirculation commands, the link screen's default scale and the round lamps; `ControllerSyncSynchroscopeTests`: the full synchronisation through panel buttons. Panel client check: five new stages in `PanelHardwareShowcase` (station in MAN at 40 %, station in AUTO and an unwired one with its no-signal flag, synchroscopes slow, fast, in phase and tied, the button legends, the lamps).
- No existing assertion was changed in this entry. Legends on the synchroscope's lamps were left alone (the dial's three-legend assertion pins it).

## Unreleased: breaker switch spring return verified through the real input path

A GE or Westinghouse breaker control switch springs back to NORMAL after CLOSE or OPEN and its target flag keeps the last operation. A fresh switch has always defaulted to spring return (`ControlSwitchData.spring = true`, every style and size), so nothing about the default changed; this round proves the behaviour through what a player does and fixes the two ways it could be left held.

- **A crosshair that slides off a held control lets go at once.** With the use key down, moving the crosshair off the switch (onto bare panel, another block or the sky) left the handle at CLOSE or OPEN until the key came up or the server's 1.5 s fail-safe fired. The client now lets go the tick the crosshair leaves the control (`PanelClientOperate.onTick`); put back on it with the key still down, the next repeat of the click takes hold again.
- **A press that reaches the server after its own release is dropped.** Presses and releases of a held control now carry a stamp that counts up in the order the player made them (`PanelPayloads.Operate.seq`; 0 is unstamped, a program or an old client, and is never judged). The server remembers the last stamped RELEASE and PRESS per control and player (`PanelComponent.staleHold` / `staleRelease`, never saved) and ignores a PRESS stamped before that player's last RELEASE and a RELEASE stamped before that player's last PRESS, so reordered or delayed packets can no longer latch a spring-return switch at CLOSE until the fail-safe, or let go of a newer hold. The packet gained one VarInt; the panel packets are version-locked to the mod like the rest.
- New client check stages `switch-input-*` (`-PbwrPanelClientCheck`, filter `-PbwrPanelClientStages=switch-input`; `-PbwrSwitchInputSurvey` logs failures and carries on): 22 sequences (quick click in one tick or one tick apart, hold 10 and 40 ticks, slide off in three ways, double clicks, the other side while held, a mash, scroll flicks, sneak, reordered packets) on every GE, Westinghouse and classic switch of a vertical wall facing north and east, a flat desk, a 45 degree benchboard and a 15 degree console; after each the handle must be at NORMAL on the server and the client and in the renderer, the flag colour and latch must show the last operation and the breaker model (a MOMENTARY sink on the switch's real events) must be CLOSED or OPEN. Closeup pictures of the GE and Westinghouse switch at NORMAL, held and sprung back are saved as `switch-input-*.png`.
- New GameTest `SwitchOrderTests` forces the packet order (RELEASE before PRESS, same tick, swapped, another player, unstamped).

## Unreleased: fuel fabricator item route, depleted uranium, recipe audit

The fuel fabricator can now make assemblies from uranium and steel *items*, with the enrichment chosen on its screen, and returns the uranium that did not become product as depleted uranium. The Mekanism chemical route is unchanged. GUI network protocol is now 23 (the fabricator's snapshot changed): update client and server together. Old saves load.

### Item route

- **Four slots**, through the item capability: uranium in, steel in, assembly out (slot 0, where the one output slot was), depleted uranium out. A hopper above sees only the inputs, a hopper below only the outputs, the four sides all of them; inputs cannot be pulled back out by a pipe. The Mekanism chemical tanks stay on Mekanism's capability.
- **Inputs by tag**, so any mod's items work and Mekanism is not required: `c:ingots/uranium` (1 ingot), `c:raw_materials/uranium` (1 ingot) and the optional `bwr:fabricator_feed/yellow_cake` (`mekanism:yellow_cake_uranium`, half an ingot: an enrichment chamber makes two from one) for uranium, `c:ingots/steel` for steel.
- **A selector** on the screen over the uranium ladder (0.71, 2.70, 3.50, 4.95, 8.00, 19.75, 20.00 %); the retired 1.2 % and 1.4 % grades are not on it. The target is sent as a menu command carrying an index that the server range-checks and the block entity checks against the ladder, and is saved with the block only when it is not the default (`leu`).
- **The cost is the enrichment mass balance**, `feed/product = (xp - xt)/(xf - xt)` with natural feed 0.711 % and 0.25 % tails, in `dev.bwr.core.fuel.EnrichmentMassBalance` and nowhere else, on integer arithmetic (assays on a 1e-5 grid) so the rounding is exact: feed rounded up, depleted uranium rounded down (`floor(P x r) - P`), so the machine can lose to rounding but never gain. An assembly is 4 ingots of product and 4 steel: natural 4 uranium and 0 depleted; 2.70 % 22 and 17; 3.50 % 29 and 24; 4.95 % 41 and 36; 8 % 68 and 63; 19.75 % 170 and 165; 20 % 172 and 167 (the full table, with times and energy, is in FUELS-AND-RODS.md). Time is 100 ticks plus 3 per uranium ingot at 200 FE a tick. The cost follows each fuel's `nominal_enrichment`, so a datapack that retunes a grade retunes its cost.
- **Depleted uranium** is a new ingot (`bwr:depleted_uranium`, tagged `c:ingots/depleted_uranium`, not a uranium ingot, so it is not taken back as feed) in the Fuel & Rods tab.
- **How it works.** A 20 % assembly takes 172 ingots and a stack holds 64, so the machine takes uranium from its slot into an internal store, only as far as the selected assembly needs; steel and the finished work are committed together when the time is up, so an assembly that does not happen consumes nothing, and with too little uranium, too little steel or no power nothing happens. The depleted uranium of one assembly can exceed a stack, so what the slot cannot hold waits and moves into it as it is emptied (the machine stops at 512 held). Breaking the block drops everything it holds. The routes take turns (they share the assembly slot and energy buffer). An idle fabricator costs one boolean test a tick: the item route looks at its inputs only when something about them changed.
- **Saves.** New keys (`Target`, `UraniumUnits`, `TailsPending`, `WorkTicks`, `UraniumIn`, `SteelIn`, `TailsOut`) are written only when they hold something, so a fabricator that never used the route saves what it always did. A fabricator saved before this loads with empty slots on the default target, and its chemical tanks, batch, energy and output exactly as they were.
- **Screen.** Rebuilt on the shared style: the item route card (selector, what the selected assembly costs, the four slots with captions) on the left above the inventory, the item assembly, chemical batch and energy cards on the right; the panel is 224 px tall (it still fits 320 x 240). Shift-click puts uranium and steel in their slots. The CC peripheral `bwr_fuel_fabricator` adds `target`, `uraniumHeldIngots` and `depletedUranium`.

### Recipes

- Of the 152 registered items, the only ones without a recipe are `cobalt_sample`, `tritium_sample` and `doped_silicon` (made by irradiating a target), `corium` (made by a melting core), the new `depleted_uranium` (the fabricator's byproduct) and the three retired blocks `rcic_turbine_pump`, `hpci_turbine_pump` and `suppression_pool_quencher`. The first four are left uncraftable on purpose. **No recipes were added for the three retired blocks**: they were retired on purpose (alpha.11 replaced the pumps with the Terry-style `rcic_twl` and `hpci_turbine`, alpha.22 removed the quencher's crafting and creative entry because a suppression tank has an internal steam distributor), they are not in the creative tab, and `tools-audit-assets.py` fails any recipe that produces or uses them, so a recipe would have reversed a recorded decision and broken the audit. If the owner wants any of them back, that is a decision to make explicitly (the audit's `RETIRED_BLOCKS` and the creative tab go with it).
- `tools-audit-recipes.py` (new): every recipe parses and is well formed for its type, every bwr item a recipe names is registered, and every registered item is the result of a recipe except the list above, each with its reason (a stale entry fails too).

### Tests

- Core: `EnrichmentMassBalanceTest` (hand-worked table, rounding never mints uranium, monotonic, natural costs itself, impossible assays refused).
- GameTests, `FuelFabricatorItemRouteTests`: the cost table for every target; natural, 3.5 % and 20 % assemblies through the real block entity with hoppers' view of the slots (uranium, steel and depleted uranium counts, a full output blocks the machine); raw uranium and half-ingot yellow cake; not enough uranium, not enough steel and no power do nothing; the slots from each side; the target is validated, saved only when not the default, loads, and forged commands change nothing; a fabricator with only the pre-route keys; held uranium and work in progress survive a save; the item route with no Mekanism; depleted uranium is not feed. With Mekanism: the real ingots, raw ore, yellow cake and steel through the tags, a 3.5 % assembly from 29 Mekanism ingots, old-format chemical tanks untouched, and the chemical route still making what it did.

### Depleted uranium as a reactor fuel

- New fuel type `depleted_uranium` (datapack entry and compiled preset, appended last so every other fuel keeps its item-model index): 0.25 % assay, `k_inf_base` 0.262 (natural uranium's curve carried down to 0.25 %, so fresh it is a net absorber), `conversion_gain_k_inf` 0.036 (thorium's 0.1 times the U-238 to Th-232 capture ratio, the same breeding mechanism), `depletion_rate` 5e-7 (LEU's scaled by fissile content), beta, Doppler, prompt lifetime and heat like natural uranium. k-infinity runs 0.262 fresh, 0.271 at 10 GWd/t, about 0.275 at its peak near 25 GWd/t. Reasons in FUELS-AND-RODS.md.
- A shaped recipe, four depleted uranium ingots round four steel (`c:ingots/steel`), makes a fresh depleted uranium assembly (`bwr:depleted_uranium_assembly`); it needs no Mekanism beyond whatever supplies the steel tag. Own item icon, name and a tooltip line ("Blanket: absorbs neutrons when fresh and breeds plutonium as it burns."). The Fuel & Rods tab now offers 18 grades.
- The owner approved changing exactly three count assertions, and only the numbers: `FuelVarietyTest.test01` 19 to 20 (size and distinct), `FuelTypeSpecTest.test04` (depleted uranium added to its 0.006502 group, which is its preset list) and `FuelVarietyRegressionTests.catalogueAndComponents` 17 to 18.
- Tests: core `DepletedUraniumTest` (fresh k-infinity far below 1, rises with burnup, loads into a core with 20 blanket assemblies, steps and lowers k_eff); GameTests `DepletedUraniumTests` (loaded like the other fuels with a positive conversion gain, the recipe exists and makes a fresh depleted uranium assembly and crafts from 4 steel and 4 depleted uranium, an assembly item loads into a core and runs, and a burned item is richer than a fresh one).

## Unreleased: server tick spikes (containment survey, nodal flux solve)

- **Containment survey spread over ticks.** A controller now examines at most 4,000 cells a tick (`scanCellsPerTick`, default and new maximum 4,000, was 20,000 with a maximum of 100,000; an existing `bwr-common.toml` with a larger value is corrected to 4,000 when it loads). The tick a survey completes in spends what is left of that budget on the first strength ratings, and the rest are rated at 1,333 faces a tick (a face costs about three cells), so no survey tick costs much more than 5 ms. The spray headers are noted while the survey passes them instead of by reading every interior cell again at the end (the list keeps its order). A 21-block room is reported sealed, and linked to its reactor, a few ticks after its survey starts instead of in the same tick; a revalidation survey of a sealed room keeps it sealed and linked throughout, as before. The survey's wait on a boundary chunk that is still loading is still there (TESTING.md).
- **Nodal flux solve off the server thread.** The 1 Hz nodal diffusion solve (about 22 ms for a 15 x 15 core) is assembled on the server thread at the refresh tick, iterated on a shared pool (`bwr-flux-N` daemon threads, one fewer than the machine's cores, at least one) and its answer applied one refresh interval later (20 ticks), always on that tick, waiting for the worker only if it is not done. The answer is the same, bit for bit, as the same solve on the server thread; what changes is that the flux shape, rod flux weights, LPRM readings and the aggregates derived from them (beta, prompt lifetime, Doppler, excess reactivity) take effect one second later than before. `-Dbwr.fluxAsync=false` iterates on the server thread instead, applied on the same tick, with identical results. The core keeps its old immediate refresh (`ReactorCore.NodalRefresh.IMMEDIATE`) as the default, which is what the physics acceptance suite runs. A refresh in flight is dropped when the reactor's chunk unloads, the controller is removed or the core is rebuilt, and is not saved: a reactor restored from a save re-solves its flux map at load, as it always has. Saves are unchanged.
- Measured on the plant fixture (`-PbwrTickProbe -PbwrOnlyTick`): the containment controller's worst tick in the measured window (its 30-second revalidation survey) went from 25.1 ms to 4.8 ms, and the first survey just after the plant is built (a cold JVM) from 84 ms in one tick to three ticks of 13 to 17 ms; the reactor's refresh tick costs 2.3 ms to start the solve; at 20 ticks a second the core step's worst tick went from 23.6 ms to 2.4 ms. The GameTest server runs these ticks unthrottled (about 1 ms each), so there the apply still waits for the worker (the probe reports it as `Reactor.flux wait for worker`).
- New tests: `DeferredNodalRefreshTest` (core: the split solve equals `solve()`, a pool core and an inline core stay identical through 60 s of burnup and rod motion, a refresh in flight survives a save and a discard) and the GameTest `FluxAsyncTests` (every solve of the plant fixture's reactor over 30 s is identical on the pool and on the server thread). The wait helper `survey` of `Alpha31ContainmentTests` also waits for a sealed room's first ratings.

## Unreleased: audit fixes

Fixes from the read-only audit of 2026-10-21 (`AUDIT-2026-10-21.md`). Nothing saved changes.

### Fixes

- **Game modes on the panel packets.** The custom panel, mimic tape, wiring and networking packets bypassed the vanilla game-mode gates, so a modified client could operate or rebuild panels as a spectator or in adventure mode. `AssemblyAccess.mayOperate` (everyone but a spectator, so adventure maps keep working) now gates pressing, turning, holding and the recorder message, the hover share and the tape's mode switch; `AssemblyAccess.mayEdit` (not a spectator and `mayBuild()`, the test `CondensateTankAssembly.resize` already used) gates placing, removing, configuring, wiring and the binding screens, the mimic tape (draw, erase, edit, style) and the Networking screen, machine renames and name pushes.
- **Packet flood limits** (`PanelRateLimit`, a per-player token bucket, dropped on logout). A hover that clears the wrench lines is relayed to every player in range, so a flood of them is cut at a burst of 10 (the first clear, and any target, still go through at once). `network_name_linked` (burst 6, then 1 every 10 ticks), `network_open`, `panel_linker_open` and the CC port lookup (burst 6, then 1 every 5 ticks), `panel_configure` (burst 10, then 4 a second; each one ends in a full panel sync to every watcher) and `panel_operate` (burst 200, then 80 a second, far above pressing by hand or a held control's repeat) are limited at the packet handler; the operations themselves are unchanged.
- **Spring on a latched control.** Switching Spring on for a joystick left of centre, or for a control switch at CLOSE or OPEN, re-centred it without telling its sinks, so the panel read centre while the plant kept the last command. The config edit now sends the moved outputs through `setOutput` (the path of a player's move: sinks, listeners and a delta), the direction letting go before the position changes (`ComponentType.configured`). Pressing the centre afterwards stays a no-op, which is now right because the plant already has it.
- **Group size cap.** The 2 m desk and the duplex column tested each block of one panel style on its own against the cap, so a run one block under it let a piece that adds two blocks make the group one over (after which no component could be placed on the whole run). The blocks of one style are now measured together (`PanelGroup.placementAllowed` with several positions).
- **CC port machines.** The machine list of a panel's CC network was cached even when a machine was left out because its chunk was not loaded or its multiblock had no owner yet, so the port read "no signal" until a network or port change. An answer that left a machine out is no longer cached. Local to `CcPortNetwork`.
- **Mimic copies in unloaded chunks.** Erasing, moving a waypoint or restyling a path only reaches the loaded blocks, so a copy in an unloaded chunk of the board was left behind as a stale fragment. These edits are now refused with a message ("Part of this board is in a chunk that is not loaded; come closer") while a block next to the board is in an unloaded chunk (`PanelGroup.bordersUnloaded`). Known limit: binding a path to a plant signal still reaches only the loaded blocks of the board.
- **Client leaks.** `PanelNetworkIndex` (the client's set of panels behind the wrench lines) and `PanelMeshCache` (its GPU buffers) are emptied when the player leaves the world or a client level unloads, not only one block at a time or while a level renders.
- **Smaller.** A held control is released when the crosshair moves onto a control that cannot be held (it used to stay operated until the 30 tick fail-safe). `TurbineSteamOutletBlockEntity.receivePumpExhaustKgPerS` clamps to the receiver's capacity and logs once instead of throwing from a block entity ticker. The per-player maps (`NetworkShare`, `NetworkingOps` value throttle, the mimic grabbed waypoint, the new limiter) are dropped on logout (`PanelPlayerCleanup`). The mimic draft codec rejects an odd or empty point list (and more than 64 points), and a tape with a hand-made draft ignores it.
- **Checked and not changed.** The audit's item loss from `Inventory.add` in `SpecialtyRodItem.give` and `CondensateTankAssembly.give` does not happen: `Inventory.add` loops until a pass makes no progress and then answers `count < before` of that last pass, so it answers true only when the whole stack went in; a partial insert answers false with the remainder still in the stack, which both callers then drop (`FuelVarietyRegressionTests.largeTritiumHarvestPreservesOverflow` already pins this).
- New GameTests in `AuditFixTests` (game modes for every packet family, the rate limits and the clear flood, per-player cleanup, Spring on a joystick and a control switch reaching the sinks, the 2 m desk against the cap, the draft codec, the client panel index, and with CC:Tweaked the port cache after an empty answer).
- **Negative controls (2026-10-23).** Each fix above was undone in turn in an isolated worktree and its tests run, to see them fail. They did for the game-mode gates of placing, removing, configuring, operating, wiring, the binding screen, the tape and the networking edits and share, the rate limiter and the hover clear limit, the Spring re-centre (joystick and control switch separately), the 2 m desk cap, the draft codec, the CC port cache, the client panel index, the logout cleanup, and the tritium rod refund. Several fixes had no test that failed without them, so `AuditControlTests` and one assertion at the end of `PanelClientCheck` were added (nothing existing was changed): the game-mode gate of the recorder message, the CC port lookup, the tape's edit and mode switch, Use machine name and the size picker; the rate limits as wired into each real packet handler; the duplex column against the group size cap; mimic erase, restyle and waypoint move refused next to an unloaded chunk; the steam outlet clamping instead of throwing; the logout cleanup of every per-player map through the event bus; the tank block refund into a full inventory; and the level unload event letting go of the client's panel mesh cache and panel index (`-PbwrPanelClientCheck`). Not changed, as the audit text says: findings 2 (no loss exists), 10, 11 (`PanelClientOperate.onTick` already lets go of a held control when the aim leaves it, so the fix only releases it a tick sooner and nothing can tell the two apart) and the remaining items of 13.

## Unreleased: the premade boards are blank shapes named by their form

- The board blueprint now ships **blank shapes** instead of five function boards, on the owner's direction that a board is just a board and is not tied to a reactor function: **Vertical board**, **Back panel**, **Benchboard**, **Console**, **Back panel with benchboard** and **Duplex board**, each four blocks wide (`vertical_board`, `back_panel`, `benchboard`, `console`, `back_panel_with_benchboard`, `duplex_board`), and the two control desks below. A shape is panel blocks only: no components, labels, captions, mimic tape, reserved spaces or notes, and no plant system in any name. The five removed boards (rod control, RPV level / pressure / flow, RPS scram, the ECCS bench, neutron monitoring) were the frames of these shapes with a reactor function laid on them; the reserved spaces they held for the controller station, joystick switch and LED rod display are gone with them (nothing was added for those parts).
- **Two more shapes, the control desks:** **Control desk (1 m)** (`control_desk_1m`, four 1 m desks side by side) and **Control desk (2 m)** (`control_desk_2m`, two 2 m desks side by side), both four blocks wide like the rest, so there are **eight** shapes. The template format gained two frame kinds for them, `desk` (1 m desks, one block each) and `desk_wide` (2 m desks: `columns` counts desks, each two blocks, the right half paying the one item and the left half placed to the operator's left, facing like the board); both are one row, and their surface is the desk top, 16 cells per block by 6 deep. Survival costs the desk items (one per 1 m desk, one per 2 m desk). The five older kinds are unchanged.
- The blueprint item, the ghost, placement, the survival cost, validation and the data pack loader are unchanged (a data pack can still ship a full board). An old blueprint stack saved with a removed board id places the first shape (the vertical board), as for any unknown id. Survival now costs only the panel blocks (for example 8 back panels and 4 benchboards for the back panel with benchboard).
- `tools-generate-boards.py` is now just the list of shapes (it also deletes the retired files). `PremadeBoardTests` asserts the new set (ids, blank, valid panel blocks, no reserved space left, the fallback for a retired id) and places each shape; the board client check photographs the eight shapes (front, three-quarter, bench) and the blueprint ghost.

## Unreleased: GPU mesh cache budget

- The machine mesh cache (`MachineMeshCache`) now holds 256 MB instead of 96 MB. A reactor with the largest (21 x 21) core keeps about 90 MB in the four meshes it draws every frame (vessel, drives, core, jets); together with anything else in view that went over 96 MB, so the cache dropped one of them every frame and uploaded it again, every frame, for as long as the reactor was in view (the RIP model check caught it as "RIP core uploads every frame"). Smaller reactors were never affected. No saved data changes.

## Unreleased: server tick cost and chunk-boundary audit

- **Measured** what our systems cost per server tick in a whole plant (TESTING.md, "Server tick cost and chunk boundaries"): `TickProbe`, a development-only profiler (off unless `-Dbwr.tickProbe=true`), and `TickBudgetTests`, which builds a reactor, recirculation, steam lines, pool and RHR, feed and spray pumps, condenser, cooling, a containment room and two panel boards and asserts the work done over 700 ticks (not the time, so it does not flake).
- **Fixed, with identical physics:** every cell of a pump assembly ran the whole-footprint check on its own scheduled tick (N cells read N blocks each, every two seconds); only the controller cell does now (`TurbineAssemblyBlock` already did), and the check reads the footprint's chunks directly. The 49-block and 25-block search cubes of the pumps, the ADS controller, the suppression pool and the turbine outlet are read by section (`BlockScan`), in the same order and with the same result, instead of block by block (a 2 to 6 ms hitch every 30 seconds per machine, every 2 seconds for one with no reactor). Panel inputs no longer copy the device's whole signal list on every read (`Schema`). `PipeTopology.routes` returns the answer it computed last time while the graph and every valve in it are as they were.
- **Fixed, chunk boundaries:** a turbine outlet whose steam line was cut (a tube broken, a chunk unloaded) kept drawing its commanded flow straight out of the reactor it had learned through the line; it forgets a reactor farther than its own search radius when the line is gone. The outlet read its reactor, on every tick and when it unloaded, without asking whether the reactor's chunk was loaded, which loaded that chunk. A pump, the ADS controller or the suppression pool whose rescan could not see part of its search cube (a chunk not loaded) cleared the binding it had; it now keeps a binding it cannot disprove, instead of losing it for up to thirty seconds. Machines that read another machine many chunks away (reactor to its pumps and nozzles, outlet to its valves, pumps to their reactor, pool and tank, the pipe route walk, panel inputs) used to renew a one-tick chunk ticket with every read and so kept a chunk at the edge of the loaded area from ever unloading; they read the chunk object if it exists and nothing else (`LoadedWorld.blockEntity`).
- New: `SyncLoadAudit` (every chunk our code loads synchronously during a GameTest run is printed), `-PbwrOnlyTick`, `-PbwrTickProbe`, `-PbwrJfr`. Nothing saved changes.

## Unreleased: control desks (1 m and 2 m)

- Added the owner's two small Fusion **control desks** as mountable panel bodies (CONTROL-PANELS.md, "Control desks"): **Control Panel (Desk, 1 m)** (`bwr:control_panel_desk`, one block) and **Control Panel (Desk, 2 m)** (`bwr:control_panel_desk_wide`, two blocks placed by one item, the second to the operator's left). The shape is the owner's (units are centimetres, one block is one metre): a level top 0.375 m deep, a riser and a narrow shelf behind it 0.175 m above the top, and a front that falls away under the top for the knee space; the desk stands against the back of its block. The owner's top is 0.40 m high, which is low next to the operator desks, so on the owner's instruction ("stretch to desk height") the part below the top is stretched vertically to put the top at **0.75 m**, the height of the operator desks; the top, the riser and the shelf keep the owner's sizes (shelf 0.925 m), and the sloped front still runs from the front edge of the top to the floor, a little steeper. The model keeps the owner's outline (the extra collinear points of the Fusion mesh are dropped: 10 faces instead of 420 triangles); `art/models/control_desk/build_desk.py` rebuilds it headless in Blender and checks it against both source files.
- New panel style `PanelType.DESK`: the mounting face is the top, a level plane (normal straight up) 16 columns by 6 rows (`PanelGeometry.DESK_ROWS`; the cells in front of the top take nothing, as on the chamfers), row 0 at the back, rows toward the operator. Desks join sideways into one face of any width (a 1 m, a 2 m and a 1 m desk are one run) and are a single row.
- Both desks take the five paint finishes like the other bodies (grey tinted textures with tint indices; the dark sloped front and the floor keep their colour), drop their item with the finish, and the wrench changes both halves of a 2 m desk together. Breaking either half of a 2 m desk removes it with one drop, as the duplex column does.
- New blocks only: nothing saved changes, old saves are unaffected. The mounting face test of `PanelTrimTests` reads the shipped `desk.obj` back like the other bodies (its grid is 16 x 6 cells, and the riser and shelf rise above the top but only behind it); exhaustive `PanelType` switches in the tests gained the `DESK` arm. The panel client check has desk stages (1 m and 2 m, seafoam and tan, a mixed run in light blue, a side view).

## Unreleased: premade control boards and the board blueprint

- Added the **board blueprint** (`bwr:board_blueprint`, CONTROL-PANELS.md section 2e): one item that places a whole premade control board in one click, with an outline ghost (green fits, red blocked) while it is held. Right-click the air to pick the next board; right-click a block to place it, the clicked spot being the front left corner of the board and the panels facing the player. Creative is free; survival takes the panel blocks, one item per component and the mimic tape from the inventory and lists what is missing; a blocked, out of world, unloaded, protected or over-limit site is refused and nothing changes. Boards are placed **unwired**.
- Boards are **data**: `data/<namespace>/panel_board/<name>.json` (panel frames, components with type, cell, explicit size and any wrench setting, reserved spaces for parts that do not exist yet, mimic tape), loaded with the data packs, synced to clients, validated as a whole (every problem is logged, a bad file is skipped).
- (Superseded by "the premade boards are blank shapes" above.) Five boards shipped, laid out at about twice the exact real size from `CR-PANELS-BWR-RESEARCH.md`: **rod control**, **RPV level, pressure and flow**, **RPS scram and mode switch**, the **ECCS bench (P601)** with the ADS / SRV section, and **neutron monitoring**. Space is reserved (and marked as a TODO in the template) for the manual/auto controller station (feedwater, recirculation), the joystick bypass switch and the LED rod pattern display.
- `PanelOps.placeDirectQuiet` (a component with a given label and no per-component sync) for bulk placement. New data component `bwr:board_template`. Old saves are unaffected.

## Unreleased: control hardware (controller station, synchroscope, two-pen recorder, joystick switch, LED rod display)

- Five new panel components the research reports found missing (CONTROL-PANELS.md, section 6): a **manual/auto controller station** (`bwr:controller_station`: PV and setpoint pointers on one scale, demand bar, AUTO/MAN, RAISE/LOWER; outputs demand, mode, raise, lower, setpoint; no control law, nothing moves by itself), a **synchroscope** (phase and slip inputs, two synchronising lamps), a **two-pen strip recorder** (the strip chart with two coloured pens, each on its own scale), a **joystick bypass switch** (centre, up, down, left, right, optional spring return) and an **LED rod pattern display** (amber and red LED per rod from the full core display snapshot).
- New items start at **twice their real size**; the exact real size stays selectable. Old saves are unaffected: the types are new, `StripChartData` is only no longer final, and `ComponentType.onPlaced` is a no-op for every type but the station.

## Unreleased: gauge polish and twice the real size

- **Real size defaults are twice as big** in each dimension for every type that has real sizes: dial 4x4, edgewise 2x6 / 6x2, bar gauge 2x6, bar graph 2x6, key switch and rotary selector 2x4, lamp, pushbutton, toggle, push-to-turn switch, setpoint knob and annunciator window 2x2, RPS trip 4x2, segment display 8x4 and thumbwheel 6x4 (digits 2 cells wide, 4 tall). The **control switch** is the exception: a new one is 8x12 (the owner found it exactly the right size next to the 12x16). A bare, creative or crafted stack places that; the exact real sizes stay on the lists (cycle or Alt+click) and nothing placed or stored moves (the new sizes are appended).
- The **bar gauge has a real size** (1x3, and 2x6 as the default); its layout tests are relative to the case. Its numbers and units are 12 and 19 % bigger, measured against the owner's reference picture.
- The **classic dial's labels** (minimum, maximum, units) stay inside the face at every size and Text size; the OFF flag moved above the hub.
- **Captions** under a real size part scale with it (`GaugeScale.captionFactor`); at the design size and above they are unchanged.

## Unreleased: paint finishes for panels, trim and furniture

- Every **panel body** (vertical, benchboard, console, back panel, duplex), every **trim block** (end caps, header strip, canopy and vent, plinths, corners) and every piece of **room furniture** (cabinets, desks, mimic bands, wall displays, control stand) has a paint **finish**: **seafoam** (the look they always had, and the default), **cream**, **tan**, **grey** and **light blue** (the owner's simulator). It is a blockstate property `finish`, so a saved block that has none, which is every block placed before, loads as seafoam and looks exactly as it did (CONTROL-PANELS.md, "Finishes").
- **Sneak-right-click with the panel wrench** on a bare spot of a panel (nothing mounted under the crosshair) or on any trim or furniture block steps the finish: seafoam, cream, tan, grey, light blue, seafoam. A multi-block piece (a duplex column, a cabinet, the supervisor desk, a wall display) changes as one. Sneak-click on a mounted component still removes it, and the click on a mimic path, the config screen and the old gestures are unchanged. Neighbouring panels may have different finishes: grouping and mounting never look at the finish, and mounted components are untouched.
- A broken block drops its **item with the finish** (the vanilla `block_state` component; a seafoam drop has none, so it still stacks with a crafted one), the item places the same finish on every block of the piece, pick block gives the finished item, and item icons are tinted.
- The models carry **grey paint textures with a tint index**, coloured per finish by a block and an item colour handler, so one texture serves all five. Only painted surfaces take the finish (faces, bezels, paint, the back, the end cap cheek and the plinth doors, the desk tops); dark trim, kick plinths, hoods, screens, louvres and handles keep their colour. The annunciator strip and the vent grille are pictures (a box of windows, a grille) and keep their seafoam border in every finish. The thin housing of mimic bands and wall displays takes a darker shade of the finish (it is the same dark grey in seafoam).

## Unreleased: components on the angled trim (header strips, canopies, chamfer corners)

- The **annunciator header strip, the canopy and the two 45 degree chamfer corners** (vertical and benchboard) now take components like any panel (CONTROL-PANELS.md, "Mounting on the angled trim"). They are panel blocks with the panel block entity under the same ids, models, recipes, drops and states, so a world saved before loads: a block that had no block entity gets one when its chunk loads (and, failing that, the first time anything asks for it), and breaking one drops what is mounted on it.
- Three new panel styles: `HEADER_TILT` (the face as the model draws it, leaning back 5 degrees; strips and canopies join sideways into one row, never upward), `CHAMFER_VERTICAL` (the diagonal facet, 11 of 16 columns; stacks into rows, never joins sideways) and `CHAMFER_BENCH` (the triangular facet; a group of one). A grid cell is still 1/16 block along the face, so parts keep their real size; the cells that are not on a facet (and a canopy's hood row) take nothing.
- Existing exhaustive `PanelType` switches in the tests gained the new arms; the registration test of `PanelTrimTests` now expects the four pieces to be panel blocks with a panel block entity (the other trim pieces are unchanged and still have none).

## Unreleased: annunciator boxes, the control module, distance fade of text

- Annunciator windows that touch now draw as **one grouped box** (a thin dark gap between them, one shared dark recessed frame with a bezel lip round the group, found on each client from the windows of the panel group; CONTROL-PANELS.md, "Annunciator boxes"). Lit colour and flash are as before; the lettering follows the window's colour scheme. The legend is up to **four lines** (Engraved line 3 and 4, saved only when used, so old saves load unchanged).
- Added the **Annunciator Control Module** (ACK, RESET, SILENCE and TEST pushbuttons in one part, real size 2x1 cells): momentary outputs `ack`, `reset`, `silence` and `test`; ACK and RESET do what a pushbutton with that role does for the module's group, TEST is a lamp test (lights the group's windows while held, changes no alarm state). It acts only when pressed.
- **Distance fade of text**: printed text whose glyphs are only a few screen pixels tall fades out and a soft strip in the text's colour stands in for it, instead of turning to speckle. Computed per component per frame, no per-glyph work; client option `panelTextFade` (default on, `bwr-visuals.toml`) turns it off.

## Unreleased: gauge text and ticks in proportion, Text size and Tick size

- Gauge **text and ticks scale with the gauge** (CONTROL-PANELS.md, "Gauge text and tick size"): the numbers of a small bar gauge are no longer held at a least height of 0.15 cell, the classic dial's least tick and pointer widths and the classic edgewise meter's margins, ticks, pointer and flag shrink with a meter smaller than its art, and a 1-cell bar graph's housing is the big bars' share. The big sizes look as before.
- The dial, edgewise and bar gauges have **Text size** and **Tick size** sliders (50 % to 200 %, default 100 %) in the wrench screen. Saved only when not 100 %, so saves are unchanged; the server clamps them.

## Unreleased: real size control panel components

- The **instruments and controls start at their real size** (CONTROL-PANELS.md, "Real sizes"): dial gauge 2x2 cells (12.5 cm), edgewise meters 1x3 / 3x1, control switch 1x2, key switch 1x2, rotary selector 1x2, setpoint knob 1x1, toggle switch 1x1, thumbwheel 1x2 per digit, lamp 1x1, pushbutton 1x1, RPS trip assembly 2x1, push-to-turn switch 1x1, annunciator window 1x1, segment displays 1x2 per digit and bar graphs 1 cell thick. Before, every one of them was five to ten times its real size, so a 4 m board held a handful of parts.
- The real sizes are **appended** to each footprint list, so stored item sizes and placed components are unchanged; a bare item (no stored size) now places the real size. The big sizes are reached by cycling (right-click air or sneak-click a panel), which now goes from the smallest to the biggest, or with the new **size picker**: Alt+click with a component item in hand opens a scrollable list of every size with its cells and real size in centimetres, an outline and a marker on the current one (new packet `panel_set_size`, validated like the cycling). The network protocol version is now 22.
- Small placeholders scale their elevation, bezels, corner flags and segments with the component, so 1-cell parts are not posts; segment displays and thumbwheels get one digit per cell at the real size and bar graphs two segments per cell.
- A block may own up to 256 components by default (was 64).
- The **bar gauge** has no real size yet: its tests assume a case at least 11 cells tall (see CONTROL-PANELS.md).

## Unreleased: control room trim blocks and remodelled panel bodies

- The three panel bodies (vertical, benchboard, console) are remodelled in Blender with the **mounting face exactly where it was** (a game test reads the shipped OBJ files back and checks the face polygon's four corners against `PanelGeometry`, and that nothing else is in front of that plane). Detail: seafoam face and bezel textures with module seams, service-door backs, bevelled back edges on the vertical board, a dark recessed 2 px toe-kick on the back of the benchboard and console, a front lip on the console. Ids, states and group joining are unchanged, so placed boards just look better.
- Added 15 decorative trim blocks, none with a block entity: **end caps** (flat, benchboard, console, rounded; they take the side of the run they are placed against), **annunciator header strips** (a half-height block tilted forward 12 degrees with a grid of window tiles; a canopy variant with a projecting hood), **plinths** (base cabinet with a toe-kick, foot apron, toe-kick strip) and **corner fillers** (vertical and benchboard; inside, outside and 45 degree).

## Unreleased: Networking Wrench, machine names, gauge Measures and FE power units

- Added the **Networking Wrench**, a second wiring tool beside the unchanged Panel Linker (either stores the device in the same slot, so a bind is the same bind). Right-click selects a device, sneak-right-click opens its **Networking screen** (name, caption template, every signal with its live value, unit, quantity and readers, every command, the linked components with Unlink, and *Name linked components*). While it is held, a device under the crosshair is outlined white, the selected one yellow, and hovering a bound component or a device draws see-through lines (read cyan, command orange, at most 64). Nearby players see the same lines, fainter and tinted with your name colour, within `networkLineShareDistance` (server, default 32) and `networkLineViewDistance` (client, default 32).
- Added **machine names** (equipment tags, up to 24 characters; saved only when set), the `name` TEXT signal on every device, component **captions** (printed under a component), and the two buttons that fill captions from a name: *Name linked components* and *Use machine name*. They touch line links only (never CC port bindings), respect the panel edit permission and never fire a sink.
- Added gauge **Measures** (default ANY): a gauge set to LEVEL lists and accepts only level signals; `bind` refuses a mismatch with the reason. Old mismatched bindings keep working and show a warning. Each signal now has a quantity worked out from its unit (SIGNALS.md). `generated_fe_tick`, `delivered_fe_tick` and `buffer_delta_fe_tick` now read `FE/t` (they said `FE`).
- Added gauge **Power units** (Raw, W, kW, MW; kJ and MJ for stored energy) through the existing binding scale, `feToJoules` (default 2.5), and *Fit range*.
- Added **CC port bindings** (CC:Tweaked only): a machine gets a port 0000 to 9999 set by hand (`setPort(n)` or its Networking screen), and a panel input can read a signal of the machine on a port of its CC wired network. Two machines on one port read no signal. Every machine peripheral also has `getName`, `setName`, `getPort` and `setPort`.
- The network protocol version is now 21.
- The **bar gauge** is curved like the owner's CAD part: a real case 5 by 20 cm (kept to those proportions and centred in its size) whose white face bulges 3 cm along an arc between raised side rails, grey top and bottom caps (a dark dot on the bottom one) and the tag plate above. The ticks, colour bands, numbers and units are printed on the curved face and lean with it; the pointer rides the arc in its black channel, still reading exactly against the ticks. Band colours offer **Nominal** (white), **Caution** (yellow) and **Danger** (red) beside the colour code. Saves are unchanged.
- Added **control-room furniture** (ROOM-FURNITURE.md), eleven decorative blocks with no block entities and nothing mounted on them: the **Relay Cabinet** and **Relay Cabinet (Plain Back)** (two blocks tall, placed and broken like a door; double doors, seam, handles, louvres, an indicator window, a service door on the back of the standard one, a flat back on the other so rows stand back to back), the **Operator Desk**, **Round End** and **L Corner** desks (0.75 m with a free knee and kick space; a bare hand cycles an optional flat monitor or CRT on top, a sneaking bare hand mirrors the round end), the **Supervisor Desk** (two blocks wide), the **Mimic Band** in cream and black (a flat 3/16 deep board with a flow diagram), the **Wall Display** (2 x 2) and **Wall Display (Wide)** (3 x 2) with a dark screen and a plant overview picture, and a small **Control Stand** (a pedestal with a tilted lamp and button top). Multi-block pieces go down whole or not at all, come up whole and drop one item. The mimic tape does not draw on the bands. Models are Blender OBJ in the seafoam panel palette with dark grey trim; saves are unchanged.

## Unreleased: machine screens redesigned

- All fourteen **machine screens** (reactor panel, refuelling floor, pump panel, Terry turbine, legacy recirculation pump, condenser, condensate tank, cooling, power module, valves, fuel fabricator, RHR heat exchanger, suppression tank, service panels) are rebuilt on one design system (`gui/client/style`, audit in GUI-AUDIT.md). Every function, button, packet, validation and inventory slot position is as before; the displayed numbers are the same numbers.
- **One look**: a dark control-room palette with every text and background pair at 4.5:1 or better, a 4 px spacing scale, one type hierarchy, label-value rows with units in their own column, cards, bar meters, status icons, tabs (in the title bar), primary / secondary / danger buttons with hover, pressed, focus and disabled states, and one text field and slider style. The panels are drawn, not blitted, so the five GUI background textures are gone.
- **Colour is never alone**: a caution, alarm, info, normal or off value carries its own shape (triangle, diamond, square, circle, ring) and a word in the tooltip, and the one colour that used to mean "water" no longer means anything but status. The burnup map runs light teal to dark red-orange instead of green to red, and the maps have a colour scale.
- **Fits every window**: panels are at most 352 x 224 and never wider than the window; every screen fits 320 x 240, the smallest GUI size the game gives a screen (before, six screens did not, and the Terry turbine and suppression tank ran off a 1080p window at GUI scale 4). A body that still does not fit scrolls. Text is cut with an ellipsis and a tooltip with the whole text, never left to run over.
- **Keyboard and screen reader**: Tab and Shift-Tab visit every control in layout order, Enter and Space activate, Escape closes (also from a text field, where letters no longer reach the inventory key), Left and Right switch tabs, the sliders take the arrow keys (1 %, Shift 10 %), the core map takes the arrow keys, Enter or Space, plus, minus and Home. Every control has a tooltip, shown on keyboard focus too, and says why it is disabled (LOAD, UNLOAD, MARK, SWAP, every control a computer holds). A screen reader hears the readouts when a screen opens.
- Fixes found by the audit: the refuelling statistics and the fuel fabricator rows no longer overprint, the SCRAM indicator is no longer drawn over the FLUX tab, the legacy recirculation pump's bar no longer runs through its text, the fabricator's title is visible again, and the active tab no longer looks disabled. The reactor panel's hover-only fuel and damage details are rows on the INFO tab.

## 0.1.0-alpha.32 (unreleased) — Full core display, rod select matrix and SRV switches

- Added reactor rod selection: signal `selected_rod`, commands `select_rod`, `selected_rod_insert` / `_withdraw` (one notch) and `selected_rod_insert_continuous` / `_withdraw_continuous` (held), and CC `selectRod`, `getSelectedRod`, `stepSelectedRodIn` / `Out`, `setSelectedRodInsertHeld` / `WithdrawHeld`. They move only the selected rod through the existing drive network, honouring CRD power and water. The selection is saved; old saves load with none.
- Added per-rod indications `rod_<n>_drift`, `_scram`, `_full_in`, `_full_out`, `_accum_trouble`, `_lattice_x`, `_lattice_y`, `_label` (GE `XX-YY` coordinates) and per LPRM string and level `lprm_<s>_<a-d>_percent`, `_bypassed`, `_failed`, `_downscale`, `_upscale`. The core has no rod drift or low-charge notion, so those two are documented defaults; see SIGNALS.md.
- Added the **full core display** (`columbia` lamp matrix and `browns_ferry` digital style) and the **rod select matrix** panel components. One binding to the reactor controller, no port per rod; one compact patch only when something changed. They span up to 8 x 8 blocks; a drawing that does not fit is refused with the size needed.
- The panel GPU cache holds static legends in text vertex buffers; a 185-rod display plus a matrix costs about 0.05 to 0.09 ms a frame (was 1.4 to 1.5).
- Style table: oiltight 30.5 mm selector (black knob, chrome wing lever), smooth and ribbed dome lights and the guarded yellow pushbutton, when their models ship.
- No rod blocks, sequence control or worth minimiser: protection logic stays the player's.
- Added the owner's CAD hardware as panel items. **Bar gauge** (`bar_gauge`): a vertical edgewise indicator laid out like the owner's simulator (numbers on the left, ticks and up to four colour bands against a narrow black channel, a grey pointer in the channel pointing at the scale, the units down the face right of it one letter per line, the label on a tag plate above); a Preset field fills in the narrow range (0 to 60 in) or wide range (-150 to +60 in) RPV level scale. **RPS trip assembly** (`rps_trip`): two guarded red buttons, outputs `a` and `b`, momentary; the click position picks the button. **Push-to-turn switch** (`push_turn_switch`): hold right-click to push the knob in, scroll while it is in to turn it between AUTO and ARM; outputs `position` and `pushed`, optional spring return.
- The baked GE edgewise and dial meters now draw their colour bands. Gauge units may be 12 characters.
- ADS: new bindable inputs `ads_allow`, `ads_activate_a` and `ads_activate_b` on the ADS controller (both activate inputs held together open the bank once; `ads_allow` cleared keeps it shut) and a per-valve `ads_allow` on relief valves (CLOSE on a valve switch: the ADS may not hold it open). Defaults change nothing; old saves load unchanged. See SIGNALS.md.
- The imported CAD models had their faces turned inside out (lit from behind); fixed and re-exported, including the Modern button, SRV switches and WL-2.
- The SRV switches and the RPS trip assembly are rebuilt from the owner's CAD dimensions: they had come out upside down and the decimated cylinders had collapsed. The SRV switch now has its legend tab above the knob (Close | Open, Close | Auto | Open) and a straight bar handle on a white disc; the RPS assembly has its tall cup guards back, rippled buttons, and the legends on the raised label pads.

## 0.1.0-alpha.31 — 2026-10-11 — Buildable containment

- Added surveyed containment rooms built from full solid blocks, with volume, leak position, layer-based weakest-spot pressure rating and reactor/pool linking.
- Added steam, water, power, data and general penetrations. Powered isolation valves close on lost actuator power; connected open process lines can bypass the containment atmosphere.
- Added 3×3 and 4×4 Blender-modeled vault doors and a two-leaf personnel airlock. Redstone pulses cycle the airlock interlock; only CC override forces both leaves open, creating a medium leak even after releasing override.
- Added a powered nitrogen supply with consumable canisters and a powered wetwell vent valve for concrete basins and enclosed suppression tanks. Vent discharge needs exterior air or a straight stack.
- Replaced the containment controller's status-only CC face with room, opening, isolation, bypass and atmosphere methods. The in-world status readout shows openings, weakest spot, hydrogen, oxygen and pressure.
- Fixed persistent-world GameTest fixture readiness for tank casing drops; added airlock reload, redstone and equipment regressions. The controller GUI is deferred to a later visual overhaul.
- See [CONTAINMENT-UPDATE.md](CONTAINMENT-UPDATE.md) for a worked build and limitations. The corium and severe-accident model remains lumped, not a fluid-dynamics simulation.

## 0.1.0-alpha.30 — 2026-10-09 — Natural circulation, core melt and containment hydrogen

- Added natural circulation: with pumps tripped or absent, core flow follows the cube root of thermal power and needs water above the shroud head. A two-pump trip from rated, with pressure and level held, settles near 23% flow and 26% power.
- Added cladding failure: rods burst between 750 and 1,200 °C, release the gap inventory and leave the coolant radioactive. This damage is permanent.
- Added core melt: above 2,200 °C excess heat melts fuel as latent heat, and molten fuel relocates to the lower plenum with its decay heat.
- Added lower plenum debris behavior: water quenches debris and saves the head, while dry debris creeps the lower head to failure (about 35 min at pressure and 80 min depressurised for 30% of the core).
- Lower head failure drains the vessel from the bottom and moves the debris onto the drywell floor.
- Added core-concrete interaction: corium ablates concrete unless water reaching the floor removes its heat. It produces gas and hydrogen, and penetrating the 3 m basemat fails containment.
- Added a containment atmosphere: hydrogen burns at 8% with oxygen present and detonates when oxygen reaches an already-rich mixture. Burns consume oxygen that a sealed containment does not regain.
- Added containment controls: nitrogen inerting and de-inerting purges, a pool-scrubbed wetwell vent, overpressure failure, and air ingress through a failed containment.
- Added fission product releases to containment and the environment. Contamination decays with a one-day half-life, and nearby players receive escalating status effects.
- Hydrogen burns cause explosions that damage entities but not blocks; other milestones produce chat notices and sounds. Events never replay after a reload.
- Added the CC:Tweaked methods getNaturalCirculationFlow, getCoreDamageStage, getCoreDamage, getContainment, setContainmentInerting and setContainmentVent, plus new getStatus fields, and expanded the controller INFO readout.
- Damage and containment state persist under a new SevereAccident save key. Old saves load undamaged with an air-filled containment. Menu network protocol stays at 12.
- Added 30 physics acceptance tests covering the new models. A recirculation-line break or failed lower head still removes all core flow, because natural circulation cannot run through a short-circuited loop.

## 0.1.0-alpha.29 — 2026-09-27 — Refuelling map controls

- Added cursor-centered scroll-wheel zoom, right-drag panning, and a FIT button to the refuelling map.
- Clipped zoomed cells to the map panel and kept hit testing aligned while zooming and panning.
- Added click-to-toggle multiple selection, visible selected outlines/count, and CLEAR.
- LOAD and UNLOAD now process selected positions through one bounded server request and one map refresh.
- Preserved held-fuel priority, click order, fuel exposure, specialty inserts, inventory exhaustion, and overflow item drops.
- Fixed Creative-mode unloading silently discarding rods when the player's inventory was full; overflow now drops beside the player.
- Kept MARK/SWAP as an explicit single-pair operation and retained the head-off refuelling requirement.
- Added real client input/network and inventory-conservation checks. Menu network protocol is 12; update client and server together.

## 0.1.0-alpha.28 — 2026-09-27 — Circular reactor internals

- Capped modeled active fuel at nine blocks, with smaller footprints approaching 3.5 blocks; vessel height no longer stretches the fuel channels.
- Accepted complete jet-pump assemblies at all fully enclosed mounting heights in the inner perimeter band, including raised construction locations.
- Placed formed jet pumps between the core shroud and vessel wall using the existing uniformly scaled Blender fittings.
- Rendered bound hydraulic control drives beneath their circular core/blade positions and concealed the rectangular construction grid, including spare drives.
- Preserved saved CRD blocks, fuel, control mapping and bottom-face water/power connections. Holding a CRD item exposes original construction selection shapes for maintenance.
- Kept water, Cherenkov visuals and spray headers aligned with the shortened core; accepted existing spray rings at their old build heights.
- Invalidated the jet survey on construction changes instead of expiring it every second; retained cached GPU meshes.
- Added a single-size 17×17×28 integrated client/server regression fixture and a Blender assembly driven by its exported runtime coordinates.

## 0.1.0-alpha.27 — 2026-09-27 — Spray-ring placement and rotation

- Corrected the construction model's quarter-turn offset so new spray branches face the player.
- Stopped straight neighboring segments from overwriting the chosen nozzle direction; automatic 90-degree corners remain available.
- Added Mekanism Configurator Rotate-mode support: right-click for a clockwise quarter turn of straight or corner segments.
- Saved manual orientation so neighbor updates and world reloads do not undo tool rotations.
- Fixed held-item interaction so extending a ring does not accidentally toggle LPCS/HPCS; empty-hand right-click still switches loops.
- Added in-game placement/control tooltips and integration checks using the actual Mekanism Configurator.

## 0.1.0-alpha.26 — 2026-09-27 — Port-height LOCA and white steam

- Changed reactor accident plumes from red to white, retaining the existing particle budget.
- Removing a previously connected vessel pipe now creates a persistent leak; never-connected construction ports remain buildable.
- Added pressure- and elevation-dependent water discharge, with larger lower recirculation inlet breaks and smaller upper outlet breaks.
- Direct liquid drainage stops at each broken nozzle's height; exposed nozzles vent steam and boiling/flashing can continue lowering inventory.
- Added a fixed-volume, two-phase rupture solve coupling mass, outlet enthalpy, expanding steam space and depressurization.
- Feedwater and steam-line breaks vent exposed vessel nozzles; multiple openings and their locations survive reload.
- Pipe failures become possible above 1,250 psig after accumulated wear; vessel-head failures retain the separate 1,375-psig gate.
- Added physics and Minecraft regression coverage for port elevation, flow scaling, conservation, pipe removal and save compatibility.


## 0.1.0-alpha.25 — 2026-09-27 — Jet-pump proportions

- Rebuilt the installed jet-pump appearance in Blender as separate diffuser, straight-pipe, inlet and support parts.
- Preserved round pipe sections and uniformly scaled bends, suction bells, diffusers, braces and fasteners.
- Limited straight-section length adjustment and total pump aspect ratio, preventing tall vessels from stretching the pumps into thin columns.
- Improved fitting to the curved shroud/vessel gap while retaining spacing between adjacent assemblies.
- Preserved existing construction placement, saved pump blocks, pairing and hydraulic capacity; retained cached GPU geometry.
- Expanded the client checks to validate actual part bounds, materials, aspect ratios and a close view inside the downcomer.

## 0.1.0-alpha.24 — 2026-09-27 — Installed jet pumps

- Formed reactors now display their installed jet pumps down alongside the outside of the core shroud, inside the vessel wall.
- Retained the tall construction models and existing lower mounting rows for building; unforming restores those models and their selection/collision shapes.
- Removed invisible construction-column obstacles while formed without moving or deleting saved pump blocks.
- Fit pump models to vessel size and installed count, including smaller and rectangular vessels; kept paired-flow rules and the ten-jet-per-recirculation-pump rating unchanged.
- Reused the existing Blender-authored pump mesh, checked the shroud fit in Blender, and cached installed geometry on the GPU.
- Added formation, disassembly, reload, legacy footprint, missing-pump, flow and client geometry checks.

## 0.1.0-alpha.23 — 2026-09-27 — Open reactor access

- Removed physical collision and crosshair obstruction from the raised/failed vessel head; closing the head restores collision. Preserved structure blocks and saved state.
- Added water- and power-dependent blue Cherenkov-style light around submerged fuel, including decay-power glow after shutdown.
- Added original Blender 90-degree sparger elbows that automatically turn at neighboring corners. New segments inherit the adjacent LPCS/HPCS setting.
- Kept vessel/core meshes cached; no new machine ticker, particle cloud, or world-light updates.
- Added open/closed/reloaded head collision, real entity passage, corner orientation and glow-condition checks.

## 0.1.0-alpha.22 — 2026-09-27 — Steam paths and accident visuals

- Fixed suppression wall-inlet routing after pipe changes. A separate quencher and submerged inlet block are no longer required. Retired quencher crafting/creative entry while keeping old-save compatibility.
- Added a water drain outlet with manual, redstone and CC control. It drains outside or into a connected water line, stops against obstructions, and can empty below the separate RHR suction floor.
- Made RPV steam nozzles permanently open; downstream MSIVs retain powered redstone/computer isolation. Old closed nozzle saves migrate automatically.
- Waterlogged high-pressure water and steam pipes now survive underwater placement.
- Shared pump suction across multiple water sources and added the connected-source count to the GUI; delivery remains limited by pump rating.
- Connected existing pressure damage above 1,375 psig to actual service-pipe breaks and persistent failure effects. Vessel-side feedwater rupture now loses coolant.
- Added an original Blender damaged-head model, bounded red-tinted accident steam and a loud one-shot vanilla explosion sound pending custom audio.
- Preserved the existing temperature/steam-dependent oxidation and hydrogen model and documented its connection to loss of cooling.
- Added drainage, RHR, intake, underwater pipe and failure/reload regressions; expanded the client visual check. GUI protocol is 11: update client and server together.

## 0.1.0-alpha.21 — 2026-09-27 — Reactor Visual Update

- Replaced formed-vessel controller cubes with a shallow Blender instrument panel.
- Integrated round Blender steam, feedwater and recirculation nozzles while keeping existing pipe and computer connections.
- Added translucent water when the head is off, following physical water level and smoothing between server updates.
- Kept static vessel/core meshes cached as water moves. Unformed vessels restore their construction blocks.
- Named the 2.70% intermediate and 3.50% high BWR enrichment grades; retired 1.20%/1.40% from new selections while preserving saved fuel histories.

## 0.1.0-alpha.20 — 2026-09-27

- Filled usable gaps around the RIP core perimeter with actual loadable fuel slots.
- 17 × 17 RIP cores increase from 444 to **476 assemblies**, with **109 control
  blades** instead of 101. 23 × 23 cores increase to **1,084 / 253**.
- Added controls to partial peripheral fuel groups where the blade and guide fit.
  Complete four-assembly groups retain a valid physical drive.
- Shared the packing dimensions between simulation, Minecraft and Blender; kept
  the ABWR shroud size and pump mounts in place. Preserved every old fuel slot.
- Existing 17 × 17 RIP plants need eight additional CRDs; 23 × 23 plants need
  twelve. Surviving rod state is remapped by position, so refresh cached CC rod IDs.
- Added packing checks across all 289 footprints and an alpha.19 save-upgrade
  regression covering fuel, controls, missing new drives and save/reload.

## 0.1.0-alpha.19 — 2026-09-26

- Corrected the visible RIP annulus using GE ABWR DCD Table 5.3-2: 7,112 mm
  vessel ID, 5,600.7 mm shroud OD and 57.2 mm shroud wall.
- The 17 × 17 shroud grows from 9.68 to 12.00 blocks across; the radial gap
  decreases from 2.78 to 1.62 blocks. The same proportions scale to every size.
- Rebuilt the RIP shroud and rim in Blender and expanded the displayed fuel,
  supports, guides and control blades together. The core remains continuous.
- Separated visual dimensions from Minecraft construction-clearance masks.
  Saved fuel capacities, drive identities, pump mounts and circulation are unchanged.
- Added a top-down Minecraft review and checks of actual baked shroud dimensions,
  fuel fit and reuse of uploaded GPU geometry.

## 0.1.0-alpha.18 — 2026-09-26

- Enlarged the continuous RIP core and shroud by moving the tightest pump sites
  slightly outward while maintaining the existing clearances.
- A 17 × 17 RIP reactor now holds 444 assemblies and 101 control drives, up from
  408 and 97. Its twelve pump sites remain; only four diagonal sites move.
- A 23 × 23 RIP core now holds 1,036 assemblies, up from 964. Ordinary core
  capacities, pump counts, equipment dimensions and flow ratings are unchanged.
- Updated Blender layout reviews, mounting coordinates and upgrade guidance.

## 0.1.0-alpha.17 — 2026-09-26

- Replaced the holes above internal pumps with one continuous, smaller core.
- Reduced the core shroud and fuel/drive layout together, reserving a peripheral
  pump ring with clearance from the shroud and vessel wall.
- Additional RIPs now add flow without cutting more holes or reducing core size
  again. Ordinary reactors retain their existing capacities.
- Added diameter/area ratios to the CC mounting query and updated placement
  guides with the new ring coordinates and upgrade instructions.
- Reviewed matching Blender scenes and Minecraft cores at minimum, reference,
  maximum and rectangular sizes. Preserved fuel-conversion checks, surviving
  control-drive state and reusable GPU geometry.

## 0.1.0-alpha.16 — 2026-09-26

- Made reactor internal pumps form through a scalable bottom-head mounting grid,
  with 4, 12 and 16 sites at 7-, 17- and 23-block-wide vessels.
- Added held-item mount outlines and the CC:Tweaked `getInternalPumpMounts()` query.
- Installed RIPs reserve fuel/drive positions, require FE and contribute real
  core flow. Each powered full-speed RIP supplies three jet-equivalent units.
- Protected loaded fuel during conversion; preserved surviving rod positions,
  accumulator charge and pending commands through mounting changes and reloads.
- Added a Blender hydraulic control rod drive model referenced to Columbia FSAR
  Figures 4.6-2/4.6-3, preserving shared water/FE and computer connections.
- Updated pump status, reactor INFO, placement guides and model references.

## 0.1.0-alpha.15 — 2026-09-26

- Added Blender-authored scalable core internals based on Columbia FSAR diagrams:
  fuel channels, lifting handles, support cells, top guides, shroud and cruciform blades.
- Visible fuel and specialty inserts follow the actual saved loading; empty slots
  remain empty. Compact and legacy capacities/control IDs are preserved.
- Added continuous inner/outer vessel-head surfaces, sealed poles and overlapping
  shell joints to eliminate open surfaces and seam cracks.
- Core meshes reuse GPU buffers; closed vessels skip the interior from outside.
  Moving blades reuse one mesh. Refuelling does not rebuild the vessel shell.
- Added a Columbia FSAR reference index and core characteristics notes.

## 0.1.0-alpha.14 — 2026-09-26

- Renamed ADS Relief Valve to **Pressure Relief Valve** and ADS Division Controller
  to **Division**. Existing item IDs, recipes, placed machines and CC peripheral
  names remain compatible.
- New division hardware defaults to 1. Local selectors cycle through **1–4**;
  saved division 0 and existing computer commands remain supported.
- Replaced core-sparger cubes with Blender-authored header/nozzle segments.
  Formed reactors display circular or elliptical rings scaled to the vessel,
  with fixed-diameter pipes, circuit collars, nozzle tips and mounting brackets.
- Missing segments leave visible gaps and preserve proportional spray capacity.
  Rings reuse cached reactor geometry and revert to individual models on unforming.
- Added a Blender-rendered sparger inventory icon to keep creative-menu rendering light.
- Updated hardware and construction guides; GUI protocol remains 10.

## 0.1.0-alpha.13 — 2026-09-25

- Cached stationary reactor, cooling-machine, condenser and condensate-tank
  geometry in reusable GPU buffers. Normal frames update placement and lighting
  instead of rebuilding and uploading every model face on the CPU.
- Kept mechanical-tower fans separate from the body; all fan instances reuse
  one mesh while their rotation follows the existing animation.
- Cached suppression-pool component meshes while preserving moving water levels.
- Used full-machine bounds for large-object visibility and distance checks,
  including views where the controller's chunk section is outside the camera.
  NeoForge still rejects machines whose complete bounds are off-screen.
- Added cache cleanup on resource reload, world unload and shutdown, plus bounded
  reuse of assembly variants. Preserved the ordinary vertex path for special
  destruction/outline consumers and retained Blender material colors.
- Added a disposable client benchmark and checks for stable uploads, off-screen
  rejection, tower-top visibility, day/night lighting and resource reloads.
- Client rendering update; existing saves, simulation and GUI protocol 10 remain compatible.

## 0.1.0-alpha.12 — 2026-09-25

- Added smoothly varying random wind and gusts to both cooling towers' vapor.
- Wind changes with height and reaches different sections of the plume at
  different times, producing bends and winding shapes as existing vapor drifts.
- Calm periods favor upright plumes; stronger gusts carry vapor sideways and
  reduce its rise. Nearby towers share coherent wind, with gentle local mixing.
- Retained the continuous white emission, gradual fade, reduced/minimal particle
  settings and shared 1,200-particle cap. Motion is client-only; GUI protocol stays 10.

## 0.1.0-alpha.11 — 2026-09-25

- Rebuilt RCIC and HPCI in Blender as separate Terry-inspired turbine/pump skids,
  with bolted casings, governor linkage, oil systems, gauges, coupling guards
  and visible CC:Tweaked panels. HPCI adds an insulated casing and service platform.
- Added four round, grid-aligned flanges: steam admission, steam exhaust, water
  suction and water discharge. New footprints are 5 × 4 × 5 and 7 × 5 × 5 blocks
  respectively (width × height × depth). Suction accepts ordinary water through
  Mekanism-compatible fluid connections; discharge uses BWR high-pressure water pipes.
- Added dedicated RCIC/HPCI GUIs with manual speed/start/stop, control ownership,
  water-source selection, live steam/water measurements and port coordinates/status.
- Preserved existing block IDs, old installed models/ports, saved controls and
  finite steam/water accounting. Breaking and replacing an old assembly uses the new layout.
- Updated the fast creative-inventory icons using Blender renders. Assembly
  integrity checks now run once per controller instead of once per child cell.
- Extended rotation, obstruction, teardown/drop, old-save, fluid/CC capability,
  control ownership, snapshot, model and complete pumping-loop checks.
- GUI protocol is now 10. Install alpha.11 on both clients and servers.

## 0.1.0-alpha.10 — 2026-09-25

- Reworked both cooling-tower plumes into continuous overlapping vapor layers instead of intermittent puffs.
- Made vapor whiter and denser with larger Blender-rendered sprites, a fuller base and slower dispersal aloft.
- Fixed overlapping vapor layers cutting each other into visible circular slices.
- Retained wind drift, rising motion, eventual fade, particle settings and the shared 1,200-particle limit. GUI protocol remains 9.

## 0.1.0-alpha.9 — 2026-09-25

- Reversed the mechanical cooling-tower fan animation following in-game feedback.
- Increased full-speed fan animation from 30 to 40 RPM (33% faster), retaining powered-speed scaling and smooth interpolation.
- Includes the alpha.8 suppression-tank and tower-vapor changes. GUI protocol remains 9.

## 0.1.0-alpha.8 — 2026-09-25

- Reversed the mechanical cooling-tower fans and made their rotation continuous as speed changes.
- Added tall, wind-drifting vapor plumes to operating natural and mechanical towers. Puffs rise, spread and fade; the effect is client-only with a shared particle cap.
- Added enclosed concrete suppression tanks and a Blender-modeled steam inlet flange. Tanks form automatically, start empty and retain inventory through repair and controller replacement.
- Steam from BWR pipes or optional Mekanism tubes heats the stored water and adds condensate. Shared inlet limits and valve routing prevent duplicate allocation.
- Added steam-flow and inlet-buffer measurements to the tank GUI and CC:Tweaked status. Water suction/return, spray fill and the separate RHR exchanger circuit remain available.
- Fixed relief discharge recognition after pumped tank filling and pipe disconnection. Verified ocean intake → makeup pump → exchanger → discharge cooling with separate water inventories.
- GUI protocol is now 9. Update clients and servers together.

## 0.1.0-alpha.7 — 2026-09-24

- Increased completed tritium target yield to **12,500 buckets (12,500,000 mB)**:
  1,250 samples per rod, processed in the existing 10,000 mB oxidizer batches.
- Retained the seven-day rated-flux irradiation requirement. Existing completed
  but unharvested rods receive the new yield; already harvested samples retain
  their original per-item output.
- Split large harvests into normal inventory stacks and preserved dropped
  overflow when inventory space runs out. Added an overflow conservation check
  and pinned the total yield against real Chemical Oxidizer output.
- Updated tooltips and the fuel guide, including the 64-target / 700-fuel
  example for a 764-position core and a 2.5 buckets/second D-T consumer.
- GUI protocol remains 8. Use matching alpha.7 client/server builds.

## 0.1.0-alpha.6 — 2026-09-24

- Increased rated-local-flux exposure to 48 operating hours for antimony
  activation, 24 hours for silicon, 72 hours for cobalt and 168 hours for
  tritium. These are real running hours at 20 TPS, not Minecraft days.
- Preserved flux-dependent progress, saved exposure seconds, and the absence
  of offline progress. Existing unharvested rods are measured against the new
  thresholds; already harvested products remain usable.
- Completed tritium rods now yield 64 sealed samples and one reusable casing.
- Added an optional Mekanism Chemical Oxidizer recipe: one sample becomes
  10,000 mB tritium, totaling 640,000 mB per rod. Each operation fits the
  machine's output tank and consumes its input normally. Both Mekanism and
  Mekanism Generators are required for this recipe and remain optional for
  BWR itself; ordinary sample crafting is unchanged.
- Added operating-exposure and batch-yield tooltips, updated the fuel guide,
  and extended tests for multi-day progress, migration, one-shot harvesting,
  powered chemical processing and optional-mod recipe gating.
- GUI protocol remains 8. Use matching alpha.6 client/server builds.

## 0.1.0-alpha.5 — 2026-09-24

This entry covers all accumulated alpha.1–alpha.5 work since the previous
GitHub `main` push, commit
[`b126650`](https://github.com/OldGunslingerWildBill/bwr-mod/commit/b1266509bdde477436a47169b5ea255c245cfc35)
on September 23, 2026. It includes code, Blender sources, exported models,
textures, recipes, regression tests and documentation.

### Transport, heat and computer connections

- Exposed machine/controller and modeled assembly faces accept CC:Tweaked
  modems. Passive vessel and concrete walls remain structural blocks.
- Added measurement peripherals for condensers, RHR heat exchangers, fuel
  fabricators, control rod drives, RPV water ports and jet pumps.
- Computer calls resolve the current owner on the server thread. Removed or
  unloaded owners return Lua errors; connections recover after replacement or
  reload instead of retaining stale controllers.
- Water inventories carry temperature and energy through pumps, tanks,
  cooling towers, heat exchangers, condensers and suppression-pool fill/spray.
  Mixing, fractional withdrawals and save/reload preserve mass and heat.
- Condenser cooling-water temperature and exhaust backlog affect vacuum and
  LP turbine backpressure, reducing turbine work when cooling deteriorates.
- Pipe blocks have no block entities or tickers. Cached routing collapses
  ordinary runs into junctions and is invalidated by structural, capability
  and chunk changes, rather than rebuilt every tick. Transfers still check
  current valve openings and available inventory. Searches are bounded and
  never force-load chunks.
- Shared fluid stores are reserved once across multiple port proxies,
  preventing duplicate simulated capacity and water loss.
- MSIV isolation follows the consumer branch; a closed unused branch no
  longer throttles an open steam header. MSIV-only headers are supported.
- Recirculation commands use the inverse of the shared pump/jet delivery
  limits, correcting the mismatch between requested and delivered flow.
- Fuel-grid and panel readouts are batched to reduce GUI rendering cost.

See [plant transport and computers](PLANT-TRANSPORT.md) and the
[re-audit and regression record](PHASE-ONE-REAUDIT.md).

### SLC, ADS and water discharge

- Added Blender-built SLC boron solution tank, powered injection pump,
  borate charges, ADS division cabinet, ADS relief valve and discharge port.
- SLC uses finite water and boron inventories and actual FE-powered delivery.
  Connect its discharge to an RPV water inlet or a valid feedwater cross-tie.
  Computer readouts report actual delivered boron.
- ADS division assignments persist and support local, redstone and computer
  control. Relief valves use real steam connections and a shared nozzle
  allowance, avoiding duplicate steam withdrawal by multiple reporters.
- The passive water discharge/outfall port accepts water at the rear when
  the outlet is clear and loaded. All inputs share its rate limit; readouts
  include flow, cumulative mass, temperature and heat discharge.
- Added matching recipes, loot tables, models, connection data, service GUIs
  and computer methods. Protection automation remains player-programmed.

See [alpha hardware and connections](ALPHA-HARDWARE.md).

### Creative inventory performance

- Replaced expensive machine meshes in inventory slots with 33 lightweight
  Blender-rendered icons while retaining full placed and held models.
- Reproduced and corrected the creative-menu FPS drop. In the local test
  fixture, the measured menu improved from about 19 FPS to about 561 FPS;
  this is a test result, not a guarantee for every machine or modpack.
- Added client checks for the icons and repeatable performance captures.

### Condensate tanks and control rod drives

- Breaking a formed condensate tank restores its construction casings
  instead of deleting the entire structure. Survival/tool/creative drops
  and surplus construction materials are handled without duplicate drops.
- An on-disk restoration ledger handles unloaded portions without loading
  their chunks. Drain stored water before dismantling a tank.
- Face-adjacent control rod drives share finite water and FE supplies.
  A packed 17 × 17 grid can be supplied through separate water and power
  connections on two bottom faces, provided those sources cover total demand.
- Individual drive motion, wear and accumulators remain independent. Supply
  topology is cached and changes only when connections or chunks change.

See [condensate tanks](CONDENSATE-TANK.md) and [compact cores](COMPACT-CORE.md).

### Condenser fit, placement and hotwell makeup

- Clicking an LP turbine's underside with a condenser snaps it beneath that
  turbine. A green/red placement preview checks the complete footprint,
  obstructions, entities and build permissions before consuming the item.
- Reworked the Blender model to fit the LP seat, widen the body and seal its
  transition. The final layout occupies 9 blocks across the turbine shaft,
  7 along it and 6 in height; adjacent LP sections retain clearance.
- Corrected new condenser orientation by 90 degrees relative to the shaft,
  putting the main pipe faces toward the hall sides rather than the next LP.
- Rebuilt the small oval side fittings as circular open flanges and made
  both working hotwell makeup-water inlets. The final layout has eight ports.
- Makeup mixes with finite hotwell inventory and heat independently of the
  circulating cooling-water circuit. The two inlets share one capacity limit.
- Updated hotwell GUI labels and added computer readouts `hotwellKg`,
  `hotwellC` and `makeupSpaceKg`.
- Preserved older v1/v2/v3 condenser dimensions, orientations, port numbering,
  inventories and render assets for existing worlds. Drain and replace an
  old condenser to receive the new geometry and connections.

### Powered MSIVs

- MSIVs start closed and require FE to open and remain open: a 20,000 FE
  buffer, 100 FE/t during opening and 20 FE/t while holding open.
- Loss of power releases a four-second spring closure.
- Non-steam faces accept electric cables; modeled part faces accept CC modems.
  Added power telemetry to `bwr_msiv` and verified Mekanism cable connections
  in every rotation. Legacy MSIV cubes also require FE.

See [condenser and MSIV placement, ports and migration](CONDENSER-AND-MSIV.md).

### Fuel catalogue and specialty rods

- Expanded from five to 19 fuel definitions while preserving the original
  five IDs, numerical tuning and saved exposure.
- Uranium grades now cover natural 0.711%, 1.2%, 1.4%, 2.7%, 3.5%, 4.95%,
  8%, 19.75% and 20%, plus a 3.5% gadolinia variant.
- Added lean/standard/rich MOX, plutonium and thorium/U-233-driver families.
  Unusual grades are labeled experimental. Natural uranium is subcritical
  in this BWR model; MOX values are gameplay-effective fissile fractions,
  not commercial total-plutonium assays.
- Fuel fabrication selects grades from actual input enrichment without
  creating additional fissile inventory.
- Added eight non-fuel rods: boron-carbide and hafnium absorbers; Cf-252,
  Am-Be and Sb-Be neutron sources; cobalt, tritium and silicon targets.
- Absorbers affect local and bulk absorption; sources add startup neutrons.
  Targets accumulate exposure from local fission flux. They do not count as
  fuel, produce fission heat or inflate fuel burnup and power capacity.
- Refuelling, shuffling, tooltips, purple core-map entries, saves and pending
  controller-removal recovery retain specialty rods and their exposure.
- Added a simple craft/load/irradiate/harvest loop. Completed targets yield
  a sample and reusable casing once; full inventories drop the results.
  Samples have Minecraft crafting uses. These are simplified game recipes.
- Added the separate **Realistic BWR: Fuel & Rods** creative tab with 33
  entries, plus 34 Blender-rendered PNGs including the refreshed original
  fuel assembly icon. All icons use lightweight item models.

See [fuel grades, recipes and reference notes](FUELS-AND-RODS.md).

### Compatibility and verification

- Version: **0.1.0-alpha.5**, Minecraft **1.21.1**, NeoForge **21.1.248**,
  Java **21**. GUI protocol is now **8**; update clients and server together.
- Added regression coverage for transport, computer lifecycle, SLC/ADS,
  inventory performance, tank dismantling, shared CRD supplies, condenser
  placement/makeup, powered MSIVs and specialty fuels.
- Latest Minecraft run passed **50/50** required GameTests. Dedicated-server
  checks passed with and without Mekanism/CC:Tweaked, including eight
  assembly-permission scenarios in each configuration.
- The full physics run passed **210/211** checks; its sole failure was an
  outdated five-fuel catalogue assertion. After updating that assertion, the
  entire `FuelTypeSpec` group passed **4/4**. Final fuel calibration passed
  **5/5** focused tests. The long suite was not repeated after the test-only
  catalogue correction; these are combined passing results, not a claim of
  one clean 211-test run.
- Build/design checks passed, along with the 2,392-file JSON audit, packaged
  class/license audit, machine-icon checks and all 19 fuel/8 rod client checks.
- Updated the README, connection/migration guides, verification records and
  Blender exporters. Detailed evidence and JAR checksum: [BUILD-STATUS.md](BUILD-STATUS.md).

Earlier suppression-pool fill/spray/passive cooling, the original detailed
water pumps, compact reactor cores and the initial cooling systems were
already included in the preceding push and are not new additions here.
