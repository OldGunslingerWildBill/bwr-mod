# Changelog

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
