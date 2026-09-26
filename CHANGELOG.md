# Changelog

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
