# RCIC TWL and HPCI turbine assemblies

**Legacy layout reference:** since alpha.11, newly placed items use the rebuilt
[Terry turbine skids and new port layouts](TERRY-TURBINES.md). This page documents
existing `modern=false` assemblies, which remain supported in saved worlds.

Old RCIC/HPCI cube IDs remain loadable for existing worlds but have no recipe
or creative-tab entry. External recirculation now uses the DVSS model on the
existing RCP id; see [RECIRCULATION.md](RECIRCULATION.md).

| Item | Reserved space (width × height × depth) | Process ports |
| --- | --- | --- |
| `bwr:rcic_twl` | 3 × 3 × 2 blocks | Steam inlet, exhaust, water suction, water discharge |
| `bwr:hpci_turbine` | 4 × 5 × 3 blocks | Steam inlet, exhaust, associated-pump water suction and discharge |

One item places the entire assembly. All reserved cells must be loaded and clear,
including the space above the skid. Rotation follows the player's facing when
placing. Mine any part with a suitable pickaxe to dismantle the machine and recover
one item. The cells share one controller, one pump state, and one steam draw.

## Build and connect

Find both items in the BWR creative tab. Both can be crafted directly from iron
blocks, vessel blocks, steam pipe and water pipe, with a piston for RCIC or a
diamond for HPCI. No retired cube is required.

Right-click any assembly cell to open the pump panel for numeric speed,
Start/Stop, suction selection and operating data. Exterior CAD fittings beyond
the defined adapters are not additional interchangeable connections.

- **Steam inlet:** High-Pressure Steam Pipe to a formed reactor's RPV steam nozzle.
  Open the nozzle and any MSIVs manually or through your control program.
- **Steam exhaust:** a separate steam pipe to a submerged suppression-pool quencher.
- **Water suction:** High-Pressure Water Pipe to the selected condensate tank or
  pool controller. Incoming NeoForge/Mekanism water can also fill the suction
  buffer; select condensate-tank suction to use it.
- **Water discharge:** High-Pressure Water Pipe to an outward-facing RPV Water
  Injection Port in the recipient vessel wall. A reactor controller is not a port.

Keep suction and discharge separate. Water pipes and steam pipes do not join.
MSIVs are steam-only. Each physical route is bounded to 256 loaded blocks; none
forces chunks to load. Multiple injection ports on one vessel may share a header,
but connections to multiple recipient vessels are ambiguous and deliver nothing.

The HPCI source STEP contains only turbine geometry. Two blue gameplay adapters
now expose its associated pump's water circuit on the east edge of the skid:
suction at (3,0,0) east and discharge at (3,0,2) east, relative to a north-facing
placement origin. These are added game fittings, not claims about the source CAD.
HPCI now needs explicit water piping; it no longer takes water from a nearby tank
or injects automatically into whichever reactor supplies its drive steam.

See [WATER-PLUMBING.md](WATER-PLUMBING.md) for existing-world upgrade instructions.

The controller is the placement-origin cell at the skid corner. Redstone at that
cell commands running when **Redstone** control is selected. Panel control
ignores redstone, and computer ownership disables the manual buttons. CC:Tweaked exposes the
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
