# Port-height LOCA update — alpha.26

## What happens when a pipe breaks

A pipe removed immediately outside a previously connected reactor nozzle creates a vessel-side opening. This also works across save/reload. Never-connected ports are left available for building. Existing overpressure failures still destroy only an adjacent BWR pipe, and never replay that destruction after reload. Since the line-break update below, putting the pipe back seals the opening (see [Sealing a break](#sealing-a-break)); the vessel head stays failed.

## Line breaks (recirculation, feedwater and main steam lines)

A recirculation loop, a feedwater line or a main steam line is part of the pressure boundary from the vessel nozzle out to where the line ends. Taking a pipe (or a valve body) out anywhere along that stretch drains the vessel **through the port that line connects to**, exactly as a break at that nozzle would: the same opening size (upper recirculation outlet 7.5 %, lower inlet 20 %, feedwater 2 % of rated core flow; the same steam ratings), the port's own elevation, and the same square root of gauge pressure plus hydrostatic head.

Where the boundary ends, from what the mod has on those lines:

| Line | Where the pressure boundary stops |
| --- | --- |
| Recirculation suction (top outlet to pump) | The DVSS pump's suction flange |
| Recirculation discharge (pump to bottom inlet) | The DVSS pump's discharge flange: the pump is the isolation point |
| Feedwater / injection line | The pump's discharge flange (motor or turbine feed pump, ECCS pump), or any other machine face the line ends at (RHR exchanger, tank, CRD) |
| Main steam line | The machine faces the steam reaches: turbine steam outlets, RCIC/HPCI steam ports, suppression pool steam inlets |

Valves along the line do not end it; they **isolate** it. The mod has no separate feedwater check or isolation valve block, so the valves are: on steam lines the MSIV, the steam stop valve, the turbine control valve, safety/relief valves and the containment steam penetration; on water lines the containment water penetration. A break counts as **open** while some route from the vessel to the pipe beside the gap is open, each valve on it at more than 0 %. With every route through a closed valve the break is **isolated**: no water or steam leaves the vessel, and the component is not broken. Opening the valve later starts the leak; closing it again isolates it again. An isolated break keeps the line's accumulated stress (no pipe was replaced).

Main steam behaves like the water lines, in both respects: a steam line break past an open MSIV blows the vessel down through its steam nozzle until the MSIV (or another valve between) is closed. Nothing closes it automatically.

Pipe that never reached a pump or machine is **construction, not pressure boundary**: a stub that ends in air, or a line that was taken apart before the reactor ever saw it whole, can be taken down without a break. A pump or machine removed from the end of a line turns the line into a stub as well. A ruptured containment penetration is not a line break here; containment models its leak.

The white plume of an open line break comes from the gap itself, within the same three-sources-per-tick, 600-particle budget as the nozzle plumes. An isolated break has no plume.

### Chunks and performance

A break, a seal and an isolation are decided only from loaded chunks. A line cell in an unloaded chunk is neither a break nor a repair: it is remembered and decided when its chunk is back, and an open break whose valve or gap is unloaded keeps its last state.

Nothing walks a pipe network on a steady tick. Each port's line is re-evaluated only when the port's cached `PipeTopology` graph was replaced (an edit at a watched position, a chunk availability change, cache eviction); the reactor reads one field per port per tick to know. While a line break exists, that port's valves are re-read each tick through the memoized route walk, which costs a few block-entity reads. `TickBudgetTests` asserts at most 40 line re-evaluations over its 700-tick window of the full plant (it measured 0).

## Sealing a break

**Putting the pipe back seals the break** ("getting into containment is hard in the first place"). This applies to a nozzle break from removal and from an overpressure failure, and to a line break:

- A **nozzle break** seals when a line that attaches to the port is back on the block outside it.
- A **line break** seals when pipe (or the matching valve body) is back in the gap and joins the line on the vessel side.
- If **several breaks of one component** are open (two recirculation ports, say), the component is repaired only when every one of them is sealed. When the last one seals, the component is repaired: its accumulated stress goes to zero, because it is new pipe.
- The **vessel head** is not a pipe. A head failure stays until the core is re-initialised.
- The player who put the block down sees "*Recirculation loop A discharge break sealed*" (or the nearest player within 32 blocks, if the block was placed some other way). Loops and lines are lettered A, B, ... per kind of port, ordered by position; a recirculation outlet is the loop's suction side, an inlet its discharge side.
- **Old saves:** an opening saved before this update loads and keeps leaking until it is sealed. If the player had already put the pipe back under the old rule, that pipe must be taken off and put back once more.

The reactor screen's **INFO** tab has a **Pressure boundary** section listing each open break (red), each isolated break (amber) and each break sealed in the last minute. The controller's right-click status text lists the same lines.

### ComputerCraft

`reactor.getBoundaryBreaks()` returns `{breaks = {...}, sealed = {...}}`. Each entry of `breaks` has `component` (`recirculation_line`, `feedwater_line`, `main_steam_line`), `label` ("Recirculation loop A discharge"), `kind` (`"nozzle"` or `"line"`), `open` (false while isolated), the break's `x`, `y`, `z` and its port's `portX`, `portY`, `portZ`. Each entry of `sealed` (breaks sealed in the last 60 seconds) has `component`, `label`, `x`, `y`, `z` and `secondsAgo`. `getBoundaryDamage()` carries the same two lists under the same keys, beside its existing `broken` table.

### Saves

Existing `BoundaryOpenings` load unchanged. New keys are written only when they hold something: `BoundaryOpeningsSealable` (with any opening), `BoundaryHeldOpenings`, `BoundaryLineBreaks` and `BoundaryLines` (the remembered cells of each established line, so a pipe taken out while the reactor's chunk was unloaded is still found).

| Opening | Modeled effect |
| --- | --- |
| Upper recirculation outlet | Medium opening: liquid discharge while submerged, then steam blowdown as it uncovers |
| Lower recirculation inlet | Larger opening: faster loss of inventory and circulation, with a lower retained-water elevation |
| Feedwater | Loses feed supply and discharges liquid or steam according to the nozzle's elevation and water level |
| Main steam | Rapid steam discharge from an exposed nozzle |
| Vessel head | Separate large steam opening and existing damaged-head appearance/sound |

The upper/lower recirculation liquid ratings are 7.5%/20% of rated core flow at rated pressure. The actual flow uses the square root of **gauge pressure plus hydrostatic head**. A low-pressure break is therefore slower than the same break at operating pressure. Steam discharge tapers to zero as internal/external pressure equalizes.

Nozzle height is mapped through the same geometry as the vessel's displayed water surface. Each opening can remove only liquid above that height. This is a limit on **direct drainage**, not a hard minimum water level: continuing power and depressurization-induced flash boiling can reduce inventory further. Collapsed level, void swell and instrument indication remain distinct.

The rupture solver holds vessel volume fixed, tracks the space freed by escaping liquid, and balances total mass and internal energy using inlet/outlet enthalpy. It performs a bounded number of substeps in the existing reactor tick. It does not add pipe tickers or rebuild pipe networks; connection edits invalidate a small nozzle connection cache.

## Pressure damage and visuals

Pipe failure hazard is eligible **above 1,250 psig**, once accumulated wear has passed its existing gate. Crossing 1,250 is not an instant scripted explosion. Head failure keeps its separate **1,375-psig** gate and slower wear rate. These are game settings, not asserted real-world rupture pressures.

Leak plumes are neutral white, grow/fade within the existing 600-particle shared budget, and retain pressure-dependent motion. No automatic scram, relief actuation or ECCS pump-start logic is added.

## Sources and model scope

- NRC [Issue 12: BWR Jet Pump Integrity](https://www.nrc.gov/sr0933/section-3-new-generic-issues/issue-12-bwr-jet-pump-integrity) explains that real jet-pump internals affect coolant retention and reflooding. The game's requested port-height rule does not separately simulate downcomer/core inventories or jet-pump hydraulic seals.
- NRC [Issue 193: BWR ECCS Suction Concerns](https://www.nrc.gov/sr0933/section-3-new-generic-issues/issue-193-bwr-eccs-suction-concerns) describes recirculation/steam-line blowdown and plant-specific emergency-cooling paths. It supports the mechanisms, not the mod's chosen break sizes or timing.

The solver is a lumped saturated-water/steam model, not a validated plant accident code. It does not model containment backpressure, air ingress, detailed two-phase critical-flow geometry, or superheated dry gas; numerical pressure bounds and dryout bound its thermodynamic domain. No world water source blocks are spawned.

Install `mod-0.1.0-alpha.26.jar` on client and server (Minecraft 1.21.1, NeoForge 21.1.248, Java 21). Existing boundary snapshots are migrated without losing recorded failures. GUI protocol remains 11.

## Verification

- 48 targeted physics tests passed across the boundary, LOCA, severe-accident, quench, damage-accounting and state-roundtrip suites. Eight are new LOCA tests.
- All 78 required Minecraft GameTests passed, including three new regression tests for pipe removal, simultaneous breaks, reload and visual/physical elevation mapping.
- Actual Minecraft client capture confirmed white pipe/head plumes and stable GPU mesh uploads.
- Asset audit: zero problems. Packaged JAR passed dependency/license checks; compiled production classes match the JAR, including nested core classes.

Representative no-heat test: after 120 seconds, the upper-port case retained 130,837 kg versus 70,024 kg for the lower-port case. Direct drainage had stopped below the upper nozzle, while flashing continued to reduce liquid inventory. These are game-model test results, not plant accident predictions.
