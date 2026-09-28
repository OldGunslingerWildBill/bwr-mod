# Alpha.28 reactor fit

## What changes

The formed reactor has one common layout for fuel height, water level, spray
headers, jet pumps and control-drive housings. The old renderer stretched fuel
channels with vessel height, left CRD models on the construction grid, and only
recognized jet-pump roots in the bottom two interior rows.

Active fuel height now depends on the smaller horizontal vessel dimension:
`clamp(3.5 + 0.55 × (width - 7), 3.5, 9)` blocks, further limited by head clearance
in short vessels. The active fuel bottom stays two blocks above the outer bottom
of the vessel. Taller vessels add space above the core. Fuel capacity and rod
mapping are unchanged: the ordinary 17×17 reactor has 764 bundles and 185 blades.

Complete jet pumps can occupy either of the inner perimeter's two construction
rows at any height where all six blocks fit inside. Opposite pairing still uses
their actual saved positions and facing; unmatched pumps do not gain flow.
Formed models fit inside the downcomer. Their bends and fittings use uniform
scaling; only the plain straight sections adjust length within their limits.

The formed CRD housings align with the circular blade layout. All original CRD
blocks stay saved, including spare drives placed outside that mask. Water, power
and computer connections continue to address those original construction cells;
adjacent-drive supply sharing is unchanged. Hold a CRD item to select an original
drive cell for maintenance. Unforming restores construction models and collision.

New spray rings use the controller's capped top-of-active-fuel coordinate, with
LPCS one block above and HPCS four above. Short vessels retain enough construction
headroom for both loops. Old correctly placed rings remain accepted at their
former height and render at the updated header height. Duplicate old/new segments
cannot double spray capacity.

## Verification scope

The dedicated `:mod:runReactorFitCheck` uses **one size only**, as requested:

- 17×17 outer construction footprint; 25-block interior height.
- 27-block vessel including bottom and cap, plus the CRD layer: **28 total**.
- 764 loaded assemblies, 185 bound drives, 225 saved construction-drive blocks.
- Twelve jet assemblies across three construction elevations, including above-core mounts.

Checks cover server/client formation, actual baked-vertex containment, hidden
construction meshes and collision, bottom-face drive I/O, fuel preservation,
old/new spray rings, disassembly/reassembly, controller save/load, resource reload,
water-height alignment and stable GPU uploads. Captures and the exported geometry
are in `mod/run/reactorFitCheck/reactor-fit-check/` after a successful run.

`art/models/reactor_vessel/review_fit_17x17x28.py` builds the editable Blender review
from that runtime export and the original mesh sources. Its cutaway is for inspection;
the closed in-game vessel remains enclosed.

This verification establishes the requested fixture's behavior. The formulas are
shared across sizes, but this run does not claim to test other reactor dimensions.
