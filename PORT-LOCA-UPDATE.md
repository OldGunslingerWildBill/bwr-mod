# Port-height LOCA update — alpha.26

## What happens when a pipe breaks

A pipe removed immediately outside a previously connected reactor nozzle creates a vessel-side opening. This also works across save/reload. Never-connected ports are left available for building. Existing overpressure failures still destroy only an adjacent BWR pipe, and never replay that destruction after reload. Replacing the pipe alone does not erase recorded pressure-boundary damage.

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
