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

- Main-steam, feedwater and recirculation failures break a connected BWR pipe immediately outside the relevant vessel port. They do not destroy unrelated nearby buildings. The failure stays recorded even if the pipe is replaced.
- As of alpha.26, actual port elevations govern direct liquid loss; exposed ports vent steam. See [port-height LOCA behavior](PORT-LOCA-UPDATE.md).
- Head failure replaces the intact crown with an original Blender model of a torn rim, peeled steel and sheared studs. The core becomes visible. Toggling refuelling cannot repair a failed head.
- Pressure-dependent jets use bounded client-side particles and fade out as pressure falls. As of alpha.26 the plumes are white.
- A loud vanilla explosion sound plays once on head failure as a placeholder for the user's future audio. Failure effects are persisted so reloading does not repeat pipe destruction or the explosion sound.
- Static vessel meshes rebuild on a damage-state transition, not each frame. Particle count and lifetime are bounded. No automatic scram, valve actuation or pump-start logic has been added.

## References and scope

High-temperature zirconium/steam oxidation can generate both hydrogen and additional heat; loss of cooling can expose fuel to these conditions. See DOE's [Advanced LWR Nuclear Fuel Cladding System Development Trade-off Study](https://www.energy.gov/ne/articles/advanced-lwr-nuclear-fuel-cladding-system-development-trade-study) and the NRC's [NUREG/CR-6967 cladding embrittlement experiments](https://www.nrc.gov/regulations-legislation/nureg-series-publications/publications-prepared-by-nrc-contractors/cr6967). These support the modeled mechanisms, not the game's numerical failure odds or damaged-head artwork.

Install alpha.22 on **both client and server**. The cooling-menu source count changes GUI protocol from 10 to **11**. No new dependencies are required.
