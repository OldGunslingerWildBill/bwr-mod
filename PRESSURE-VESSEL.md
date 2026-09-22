# Pressure vessel appearance

A formed reactor displays a Blender-built steel pressure vessel with a curved
barrel, dished lower head, support skirt, circumferential welds, bolted flange
and removable domed head. Its dimensions follow the existing reactor envelope:
7–23 blocks across, including rectangular builds. A rectangular footprint gives
an elliptical barrel. This is game artwork, not a manufacturer CAD model.

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

- Editable Blender source: `art/models/reactor_vessel/reactor_vessel.blend`.
- Reproducible Blender script: `art/models/reactor_vessel/build_models.py`.
- Export: `python tools-export-reactor-vessel.py`; verify with `--check`.
- One controller renderer draws the assembly. Shell blocks gain no block
  entities or tickers. A client appearance index hides the cube models only
  while their assembled controller is present.
- Shape and port data are synchronized on the existing controller channel.
  Changed geometry rebuilds affected render sections; routine readout packets
  do not rebuild them. Resource reload clears cached component meshes.
- Chunk arrival and live update packets use the same client-state handler.
  Controller unload/removal clears the appearance entry.

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
