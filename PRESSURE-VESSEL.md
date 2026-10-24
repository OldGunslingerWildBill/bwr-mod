# Pressure vessel appearance

A formed reactor displays a model of a BWR/5 pressure vessel (v2, alpha.53), drawn from the Columbia FSAR and other public
sources ([RPV-REFERENCE.md](RPV-REFERENCE.md)):
- **Outside:** a support skirt with its base ring and anchor nuts, a dished bottom head, and the cylinder with Columbia's course
  seams. Then the closure flange with its studs and nuts, the top head with its crown nozzles (head spray, vent, spare) and four
  lifting lugs, a forging and stainless safe end on every port you build, and small instrument nozzles.
- **Inside, drawn only when the inside can be seen:** the core shroud stepping out to the upper shroud at the top guide, the
  shroud support plate, core plate and top-guide rim, six feedwater spargers, the core-spray lines, LPCI couplings, guide rods,
  the shroud head with up to 225 steam separators, and the steam dryer.

The head, the dryer and the separator assembly lift out with the reactor building crane, after a stud tensioner has detensioned the closure studs ([BUILDING-CRANE.md](BUILDING-CRANE.md), [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#the-steam-dryer-and-the-separator-assembly-alpha53-lifted-by-the-crane)).
Its dimensions follow the existing reactor envelope: 7 to 23 blocks across, including rectangular builds; a rectangular footprint
gives elliptical sections. This is game artwork drawn to real proportions, not a manufacturer CAD model.

## Building and connecting

Build the same rectangular shell, floor, roof and physical control-rod drives
as before. Formation changes the appearance automatically. The controller,
water ports, recirculation ports and steam outlets stay where you placed them;
Blender-authored spools bridge from the curved surface to those interfaces.

**This is an appearance pass. The rectangular construction, collision and
selection boundary remains in place.** Hold a Reactor Pressure Vessel block
to show its blue bounding outline. The rounded surface does not make the corner
space available for another machine. Block costs, capacities, core layouts and
pipe connectivity still come from the constructed reactor.

An incomplete vessel shows its individual shell blocks again, so gaps can be
located and repaired. Removing the controller also restores the block view.
The existing head-open/refuelling state removes the rendered dome; it does not
install new visible fuel models or change the refuelling controls.

## Rendering and source

- Unit parts: `art/models/reactor_vessel_v2/build_parts.py` (headless Blender), exported by `python tools-export-reactor-vessel.py`
  (`--check` verifies them); `python tools-check-vessel-geometry.py` checks that every part is a closed, outward-facing solid and
  that the shell joints overlap at every size. The approved concept and its renders are `art/models/reactor_vessel_v2/build_concept.py`
  and `concept/`.
- **One shape:** `VesselProfile` works out the heads, flange, internals' elevations and the water's inner wall for every
  envelope. The renderer, the CRD necks, the water surface, the port spools, the failed head's steam and the thrown head all read it.
- **What it keeps on purpose:** the dome height of the head (the thrown head, the open-head cells and the flange lip `rim` that
  the cavity seal and the well use hang on it), the shell radii, the core, the jet pumps' seat and the spray rings' construction rows.
- **Studs:** the real pitch, 74 at every size. They stay in the flange with the head off, as at a real plant; the nuts and
  washers come off first (the stud tensioner) and are not on the head the crane lifts. The vessel holds pressure only with every stud
  at load.
- **Drawing:** one controller renderer draws the assembly from cached meshes (`MachineMeshCache`). The outside is one mesh. The
  internals are three: the fixed internals, the separator assembly and the dryer, so lifting one rebuilds nothing else. Shell
  blocks gain no block entities or tickers. A client appearance index hides the cube models only while their assembled
  controller is present.
- **Visible water:** true to the drawn internals' scale up to +30 in (the operating level sits on the separators and the dryer
  skirt dips into it). The physics keeps the lip at +60 in (`PressureVessel.FLANGE_LEVEL_IN`), so +30 to +60 in is stretched up
  to the lip.
- **Sync:** shape and port data are synchronized on the existing controller channel. Changed geometry rebuilds affected render
  sections; routine readout packets do not rebuild them. Resource reload clears cached component meshes.

## Pipe updates

Ordinary BWR high-pressure steam and water pipes do **not** tick individually.
They have no per-segment block entity, fluid inventory or pressure solver.
Connection arms update when neighbors change. Machines and actuated valves
still tick, and routing still reads pipe blocks.

ECCS/feedwater route caches discover new branches within five ticks (0.25 s at
20 TPS). Existing route changes and valve motion are checked immediately.
Other network consumers also perform bounded surveys; this is not a claim
that all pipe-network traversal has been removed. Mekanism owns the behavior
of its own pipes.

## Client verification

`./gradlew :mod:runTurbineModelCheck -PbwrVesselModelCheck` creates and closes a
disposable world. It checks material resolution, minimum/reference/maximum and
rectangular vessels, head opening, shell break/repair, controller removal, and
the cube model's visibility. Screenshots are written to
`mod/run/turbineModelCheck/vessel-check/` for visual inspection.

The original artwork uses a conventional BWR vessel silhouette; the
[NRC Reactor Concepts Manual](https://www.nrc.gov/cdn/legacy/reading-rm/basic-ref/students/for-educators/03.pdf)
is a further BWR/6 reference. No third-party mesh or document is bundled.
