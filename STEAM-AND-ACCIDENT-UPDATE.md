# Steam, pool drainage and pressure-failure update — alpha.22

## Suppression tank connections

- Run high-pressure steam pipe from a relief valve's **downward outlet** to the outward-facing **Suppression Pool Steam Inlet**. The relief valve's side inlet must connect to the main steam header. The tank assumes an internal submerged steam distributor; no separate quencher is required.
- A formed wall inlet accepts steam even when the tank is empty. Pump in water to condense it; a dry or boiling tank cannot provide the same heat sink. The pool screen reports received steam and temperature.
- Changes to the discharge pipe update relief connections through the cached pipe graph, without waiting for a full tank survey. Source pressure comes from the actual upstream reactor, including distant pipe connections.
- **Suppression Pool Suction** remains the water outlet for **LPCI/RHR**. Its outward face supplies water above the suction floor. Run it through the pump and heat exchanger primary circuit, then back to **Return / Fill**. The exchanger's secondary circuit remains separate: intake → makeup pump → exchanger → discharge.
- New **Suppression Pool Water Drain Outlet** goes in an outward-facing side wall. It starts closed. Right-click to toggle; a redstone change also opens/closes it. Sneak-click opens the pool screen. CC pool peripherals expose `setDrainsOpen(boolean)` to command all dedicated drain outlets on that tank.
- An open drain transfers up to **1,000 kg/s per outlet** to an adjacent water line/receiver, or discharges outside through a clear air/water space. A solid obstruction stops it. It can empty the inventory below the RHR suction floor. Discharge removes water and its heat; it does not create renewable source blocks.
- The old quencher has been removed from the creative catalogue and crafting. Its registry remains only so existing worlds can load their old blocks. Existing old basin connections remain compatible.

## Main steam and underwater piping

The **RPV Steam Outlet is always open**. Right-click reads its status; redstone does not close it. Saved nozzle positions are ignored. Use a powered **MSIV** or downstream steam valve to regulate/isolate the line. Existing CC nozzle readouts remain; obsolete `close()`/non-open `setPosition()` calls explain that the MSIV must be used.

Both high-pressure water and steam pipes can be waterlogged. They preserve surrounding source water and survive underwater neighbor updates. This adds vanilla scheduled water updates, not per-pipe block entities or a fluid-network ticker. Fluid routing still uses cached topology, invalidated when connections change.

Pump suction shares demand across all connected supplies. The GUI shows the connected-source count. Combined intake supply is limited by the pump's rating: one 6,000 kg/s screen already exceeds the 1,500 kg/s makeup pump rating, so three screens cannot increase that pump above 1,500 kg/s. A larger circulation pump can use multiple screens for more supply. More intakes also share the load instead of draining only the first screen.

## Fuel damage and pressure failures

The existing core model already couples uncovered fuel, clad temperature, steam availability, zirconium oxidation, reaction heat and hydrogen generation. These quantities remain in the reactor GUI and saved core state. Lower level alone does not manufacture hydrogen in cold fuel.

The retained pressure-damage model accumulates stress and rolls increasing failure hazard **above 1,250 psig for pipes and above 1,375 psig for the head**, once accumulated component stress reaches its failure gate. It does not guarantee an immediate break at 1,376 psi. The threshold, probability law, exposure weights and failure timing are gameplay calibration, not a prediction of a real plant's rupture pressure.

This update connects that damage to the world:

- Main-steam, feedwater and recirculation failures break the connected BWR pipe immediately outside the relevant vessel port, and, since the accident blast update, whatever within one to two blocks of that break cannot take the jet (pipe whip and jet impingement, below). They still do not destroy unrelated nearby buildings. The failure stays recorded until the pipe is put back, which seals it (see [Sealing a break](PORT-LOCA-UPDATE.md#sealing-a-break)); the vessel head stays failed.
- As of alpha.26, actual port elevations govern direct liquid loss; exposed ports vent steam. See [port-height LOCA behavior](PORT-LOCA-UPDATE.md).
- Head failure replaces the intact crown with an original Blender model of a torn rim, peeled steel and sheared studs. The core becomes visible. Toggling refuelling cannot repair a failed head. Since the accident blast update it is also a steam burst and missile that breaks the world above the vessel (below).
- Pressure-dependent jets use bounded client-side particles and fade out as pressure falls. As of alpha.26 the plumes are white.
- A loud vanilla explosion sound plays once on head failure as a placeholder for the user's future audio. Failure effects are persisted so reloading does not repeat pipe destruction, the blast or the explosion sound.
- Static vessel meshes rebuild on a damage-state transition, not each frame. Particle count and lifetime are bounded. No automatic scram, valve actuation or pump-start logic has been added.

## Pipe break and head failure damage the world

Both are explosions that break blocks, always (no configuration switch), through the standard explosion and
block-break events, never loading a chunk, and never breaking the formed vessel, the controllers, bedrock or any
other unbreakable block. The mechanism, the energy chain and the protection rules are in
[SEVERE-ACCIDENT-UPDATE.md](SEVERE-ACCIDENT-UPDATE.md#accident-blasts); this is what each does.

### Pipe whip and jet impingement

A pipe that breaks at pressure destroys the pipe at the break and whips a small bounded sphere around it:

| Pressure | Radius | Jet pressure at 1 block | at 2 blocks |
| --- | --- | --- | --- |
| 300 psig and below | 1.0 block | 99 psi | 33 psi |
| 800 psig | 1.5 | 263 | 87 |
| 1,025 psig (rated) | 1.7 | 337 | 112 |
| 1,300 psig and above | 2.0 | 428 | 142 |

The radius runs from 1 block at 300 psig to 2 at 1,300. Within it a block breaks when the jet's stagnation
pressure, `P / (1 + (d / 0.7)^2)` for distance `d` from the break, exceeds its rating (stone 14.2 psi, machine
blocks 44.9, vessel steel 200.9): at 1,500 psig stone, glass, planks and machinery go out to 2 blocks, and heavy
steel and obsidian go only at 1 block or closer. It never cascades: nothing farther than the radius is touched,
the other pipe blocks of the broken line are spared (it is the same line, and destroying the run would make one
break into a plumbing failure of the whole plant), and the vessel shell and its ports are never broken. A pipe
that a player removes from an established connection is a break without pressure and does no whip, as before.

### Head failure: steam burst and missile

The stored energy at the pressure the head fails at is the steam dome expanding to atmosphere plus the first
second of flashing water (30% of rated core flow, 0.3 MJ/kg at 1,400 psig, scaled with pressure). 40% of it is
blast.

| Failure pressure | Stored energy | Blast (TNT) | Breaks, measured from the top of the vessel: planks | concrete | stone | machine blocks | vessel-grade steel |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1,025 psig | 3.6 GJ | 340 kg | 23 blocks | 26 | 19 | 12 | 6 |
| 1,375 psig (code limit) | 4.9 GJ | 468 kg | 25 | 28 | 22 | 13 | 7 |
| 1,700 psig | 6.2 GJ | 589 kg | 27 | 31 | 23 | 14 | 7 |

(Glass goes at the 40 block cap.) The blast is a **cone** opening upward from the first row above the vessel
shell, with a half angle of 35 degrees from the radius of the vessel, so a refuelling floor, shield plugs, a crane
and a roof over the vessel go, and a building beside it, a wall against its side or anything below its top does
not. The vessel multiblock stays formed and the damaged-head model stays: the blast starts above the shell and
the shell, interior, ports and control rod drive row are protected by name. The sweep is spread over ticks
(16,384 positions and 1,024 blocks per tick) and any part over an unloaded chunk waits until it loads.


## References and scope

High-temperature zirconium/steam oxidation can generate both hydrogen and additional heat; loss of cooling can expose fuel to these conditions. See DOE's [Advanced LWR Nuclear Fuel Cladding System Development Trade-off Study](https://www.energy.gov/ne/articles/advanced-lwr-nuclear-fuel-cladding-system-development-trade-study) and the NRC's [NUREG/CR-6967 cladding embrittlement experiments](https://www.nrc.gov/regulations-legislation/nureg-series-publications/publications-prepared-by-nrc-contractors/cr6967). These support the modeled mechanisms, not the game's numerical failure odds or damaged-head artwork.

Install alpha.22 on **both client and server**. The cooling-menu source count changes GUI protocol from 10 to **11**. No new dependencies are required.
