# Compact fuel and control-rod layout

New reactors use a logical fuel lattice with **one physical control rod drive
block per blade** below the vessel floor. Fuel remains individually loaded,
burned, shuffled and recovered through the refuelling screen. No fuel-model
blocks or special edge-block items are needed inside the vessel.

## Capacity

Dimensions below include the one-block vessel shell. Height does not multiply
fuel assembly count; it still affects the separate recirculation sizing model.

| Outside footprint | Fuel assemblies | Control blades / CRD blocks |
| --- | ---: | ---: |
| 7 × 7 | 88 | 21 |
| 11 × 11 | 284 | 69 |
| 13 × 13 | 408 | 97 |
| 15 × 15 | 568 | 137 |
| **17 × 17** | **764** | **185** |
| 19 × 19 | 972 | 241 |
| 21 × 21 | 1,200 | 293 |
| 23 × 23 | 1,476 | 357 |

Even dimensions and rectangular footprints are supported within the existing
7–23 block outside limits. The masks stretch with width and depth and retain
reflection symmetry. Capacity follows the discrete mask, not a linear
assemblies-per-block multiplier. Thermal ratings are unchanged by this patch;
these capacities are not a claim of proportional generating output.

The 17 × 17 reference matches the requested Columbia **counts**. Its rounded
game geometry is not a verified Columbia loading diagram. Its 185 blades each
shadow four nearby fuel positions (740 total); another 24 peripheral fuel
positions participate in the diffusion calculation without a dedicated blade.
All 764 positions retain their own fuel identity and exposure.

## Building the reference drive layer

Build the 17 × 17 vessel shell, with at least eight interior blocks of height.
Immediately below the bottom shell, place the following 15 × 15 CRD pattern,
aligned with the interior footprint. `D` is a drive; `.` is an unused position.
North is up and east is right. The shell extends one block beyond this grid.

```text
....DDDDDDD....
...DDDDDDDDD...
..DDDDDDDDDDD..
.DDDDDDDDDDDDD.
DDDDDDDDDDDDDDD
DDDDDDDDDDDDDDD
DDDDDDDDDDDDDDD
DDDDDDDDDDDDDDD
DDDDDDDDDDDDDDD
DDDDDDDDDDDDDDD
DDDDDDDDDDDDDDD
.DDDDDDDDDDDDD.
..DDDDDDDDDDD..
...DDDDDDDDD...
....DDDDDDD....
```

The controller reports coordinates of missing drives. Drive IDs scan west to
east, then north to south within each column; GUI labels and CC commands both
start at 1. The RODS tab draws the actual sparse physical pattern.

CRDs touching on any of their six faces share water and FE. Connect one water
pipe and one power cable to two separate exposed faces, such as the bottom of
two drives. Either connection can reach the combined storage of the entire
connected group, including a full 17 × 17 grid; no extra connections are needed
to distribute supplies across it. The source and cables/pipes must still meet
the combined consumption of all drives.

This works before reactor formation and for both compact and legacy layouts.
Diagonals and gaps do not connect. Each drive retains its saved buffer and its
own consumption, wear, accumulator and rod movement. Breaking a connecting
drive splits the group; unloaded drives cannot receive supplies. Reconnecting
or reloading restores sharing without generating water or FE. The connection
map is cached until blocks, capabilities or chunk availability change; it is
not rebuilt every tick. The bounded group limit is 4,096 drives.

Internal recirculation pumps need the unused corner spaces: placing one through
a required CRD column prevents formation. A north-facing RIP can straddle the
northwest shell corner, with its mounting plane on the bottom shell and its wet
end inside the corner. External pumps and jet pumps retain their existing roles.

## Control and persistence

Commands still use notches 00–48. The existing continuous blade travel, hydraulic
availability and SCRAM actuator run independently for every blade. GUI and
physics use the same explicit fuel/rod mapping. CC:Tweaked adds:

```lua
local layout = reactor.getCoreLayout()
print(layout.version, layout.assemblies, layout.latticeWidth)
local drive = layout.drives[1]
print(drive.x, drive.y, drive.z)
reactor.setRodPosition(1, 28)
```

`drives` uses the same 1-based IDs as rod commands. Each drive has a `fuelSlots`
table; `layout.fuelSlots` lists all legal positions. Table keys are 1-based,
but stored lattice indices are zero-based (`z * latticeWidth + x`).

Existing saves remain on **legacy layout 1**, preserving fuel slots, drive
requirements and saved rod demands, including temporarily broken reactors.
New controllers use **compact layout 2**. To convert a legacy plant, shut down,
cool and defuel, replace its controller, then rebuild the drive layer. Reloading
or repairing the shell does not silently convert it.

A formed compact controller retains its footprint so blade IDs cannot be
reassigned by resizing around live fuel or a saved transient. To change its
footprint, shut down, cool, defuel and replace the controller. Rebuilding the
original footprint restores the retained core.

Client and server must both use the current update (GUI protocol 7). The GUI
carries exact fuel indices and sparse drive positions. The 42 × 42 logical storage grid supports
the largest mask without the old 961-position storage ceiling.
