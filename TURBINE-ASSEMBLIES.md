# RCIC TWL and HPCI turbine assemblies

These are additional placeable machines. Existing RCIC/HPCI cube blocks and the
feedwater pumps retain their block IDs and behavior.

| Item | Reserved space (width × height × depth) | Process ports |
| --- | --- | --- |
| `bwr:rcic_twl` | 3 × 3 × 2 blocks | Steam inlet, exhaust, water suction, water discharge |
| `bwr:hpci_turbine` | 4 × 5 × 3 blocks | Steam inlet and exhaust |

One item places the entire assembly. All reserved cells must be loaded and clear,
including the space above the skid. Rotation follows the player's facing when
placing. Mine any part with a suitable pickaxe to dismantle the machine and recover
one item. The cells share one controller, one pump state, and one steam draw.

## Build and connect

Find both items in the BWR creative tab. In survival, craft the assembly from its
existing RCIC or HPCI pump, reactor vessel blocks, iron blocks, and a pressurised
tube. The recipe is available through the game's recipe system.

Use BWR pressurised tubes at the visible adapters. Right-click a port face to see
its role. Tube arms connect only at those faces and rotate with the machine.
The other exterior fittings are the parts represented by the supplied CAD model;
they do not create interchangeable steam or water connections.

- **Steam inlet:** connect to a formed reactor's RPV steam outlet. Open the nozzle
  and any isolation valves manually or with your control program.
- **Steam exhaust:** run a separate line to a suppression pool quencher.
  The quencher must have source water above it in the basin measured by a formed
  suppression pool controller. Exhaust-valve opening limits admission capacity.
- **RCIC water suction:** pipe to the selected condensate tank or suppression pool.
- **RCIC water discharge:** pipe to the reactor controller.

Keep these four circuits separate. Connecting steam and water branches into one
pipe network does not turn them into a valid circuit.
The two RCIC upward adapters have one empty grid column between them so their
first tube blocks stay separate. Use tubes and player-operated MSIVs to route
water to the tank/pool controller and reactor controller; these endpoints accept
the water tube on any face. Each route is limited to 256 loaded blocks.
Use one receiver and one selected source per water line, and one pool per exhaust
line. Multiple nozzles on the same reactor can share the steam supply. Ambiguous
connections to different reactors, tanks, or pools provide no usable route.

The supplied HPCI file models the turbine exterior, with no labeled pump-water
flanges. Its water side uses the existing HPCI pump association and suction-source
selection. Its associated pump injects into the reactor supplying its steam,
draws from the connected exhaust pool or a condensate tank within 24 blocks,
and does not gain water ports on the turbine casing.

The controller is the placement-origin cell at the skid corner. Redstone at that
cell commands running when computer control is disabled. CC:Tweaked exposes the
existing `bwr_rcic` or `bwr_hpci` peripheral there, with the existing start, stop,
demand, and suction-selection methods. The player supplies all control logic.

## Model provenance and approximations

The exteriors come from `RCIC_TWL_Exterior.step` and
`HPCI_Turbine_Exterior.step`. Both STEP product names explicitly identify their
dimensions as **estimated**, with 3000 mm and 4000 mm skids respectively. The
conversion uses 1000 mm per Minecraft block and retains the supplied part shapes
and colors. The removable HPCI exhaust blanking cover is omitted to expose its
connected exhaust. Visible adapters align the source flanges with the block grid.
Collision shapes use the occupied envelope of each cell rather than every bolt.

`ClydeUnion Pumps_TWL.pdf`, especially its component illustration on page 4,
describes the TWL unit. The HPCI photos provide exterior context; they are not
a dimensioned HPCI component specification. The brochure's descriptions of
governors and protective mechanisms are reference material, not instructions to
add automatic control logic to this mod.

The original STEP files and brochure are not needed at game runtime. The converter
and generated manifests record source hashes, part names, scale and port mapping.

## Rebuild the geometry

```powershell
python tools-import-turbines.py --source-dir C:/Users/14238/Downloads
python tools-import-turbines.py --source-dir C:/Users/14238/Downloads --check
python tools-render-turbines.py
```

Game meshes are split at block boundaries before baking. This avoids an oversized
single-block model disappearing when its origin leaves the visible chunk.
The preview renderer needs Pillow and NumPy and writes `tmp/rcic-hpci/preview.png`.
The STEP importer itself uses only Python's standard library.

## Verification commands

```powershell
./gradlew.bat build
python tools-audit-assets.py
python tools-check-jar.py
./gradlew.bat :mod:runTurbineGameTest
./gradlew.bat :mod:runTurbineModelCheck
```

The GameTest task checks behavior in a temporary game world. The model task starts
a test client, checks all orientations and both inventory models, and closes it.
These development checks are excluded from the distributed mod jar.

The process fixture uses a real formed reactor, tank, pool and pipe layout, with
controlled nozzle polling and clock advancement to test pump flow and shared steam
claims. It checks tank mass, disconnection, separation of media, and bus cleanup.
It is not a long-running player-world or chunk-unload soak test.
