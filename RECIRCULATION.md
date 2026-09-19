# DVSS recirculation and reactor information

## Build the loop

The **DVSS Recirculation Pump** replaces the RCP cube for new placements. Reserve
a **5 × 5 footprint and 10 blocks of height**. Place the item at the centre of its
base. It faces the player; the DVSS nameplate and discharge flange are at the front.

1. Put an **RPV Recirculation Top Outlet** in the upper half of a vessel side wall,
   facing outward. This is separate from the steam outlet and water injection port.
2. Run **High-Pressure Water Pipe** from that outlet to the pump's **blue rear
   suction flange**, at the lowest block level.
3. Run a separate water header from the **teal front discharge flange**, two blocks
   above the base, to an **RPV Recirculation Bottom Inlet**. Put that inlet in one
   of the lowest two side-wall rows beside the vessel interior, facing outward.
4. Supply FE and right-click any pump part to set speed and start. CC:Tweaked can
   control the same pump from any part when computer control is selected.

Both headers must reach the **same formed vessel**. Steam pipes, ordinary water
injection ports and a shortcut between the headers cannot complete this loop.
Each header supports up to 256 loaded positions. The loop does not load chunks.

This is a closed BWR recirculation abstraction: it moves water already in the
vessel and changes the core-flow demand. It does not create/extract inventory,
cool the water, or require visible pipes inside the vessel. Existing feedwater
mixing and core-inlet subcooling remain in the thermal model. Pump readouts report
vessel pressure and water temperature, not a separately simulated pipe temperature.

## Match the jet assemblies

Each placed model contains two adjacent jet pumps; the following counts refer
to **placed assemblies**. New assemblies occupy **1 × 6 × 1 blocks**. The supplied
geometry is narrowed horizontally by 15%, retaining its height, depth and atlas.

- Put the base **1 or 2 blocks above the bottom shell**: directly on the interior
  floor, or one block higher. Its entire 1 × 6 × 1 footprint must fit inside the
  vessel and stay within the outer two columns of the interior perimeter.
- Install another assembly at the same height on the opposite side. Rotate it
  180 degrees. Across the west/east walls, keep the same Z row and use east/west
  facings. Across the north/south walls, keep the same X row and use north/south
  facings. Use the midpoint between the relevant pair of walls for rectangular
  vessels. The previous diagonal 180-degree arrangement also remains supported.
- Two assemblies on the same side do not match. An incomplete, too-high or
  unmatched assembly gives no jet bonus. New installations register within a
  second; breaking an existing partner withdraws its bonus immediately.

Example: for interior X/Z coordinates 0 through 10, an east-facing assembly
at `(0, 2)` pairs with a west-facing assembly at `(10, 2)` at the same height.
Both are one column wide. The row does not have to pass through the vessel centre.

Saved two-column jets retain their original model and footprint via `narrow=false`.
Pick up and replace them to get the one-column model. Existing correctly matched
diagonal installations keep working; no conversion changes neighboring blocks.

### Flow balance

These are game balance values, not a manufacturer's pump curve.

| Complete hardware at full speed | Maximum rated core flow |
| --- | ---: |
| One RCP, no matched jets | 5% |
| One RCP, one opposing set (2 normal jet assemblies) | 20% |
| One RCP, two opposing sets | 40% |
| One RCP, three or more opposing sets | 50% |
| Two RCPs, five opposing sets (10 assemblies) | 100% |
| Each correctly mounted reactor internal pump (RIP) | +10%, capped at 100% overall |

Normal matched assemblies contribute 10% capacity each. Saved `large` jet
assemblies retain their 1.5 rating multiplier; a mixed-size opposing set uses
the smaller rating for both sides. Internal pump mounting is unchanged.

Drive contributions increase with actual speed and are capped by the shared
manifold. One RCP driving one opposing set at 50% speed gives 10% core flow.
A stopped parallel RCP does not dilute a running pump. Shared headers and
multiple connected pumps do not duplicate the jet capacity. Existing spin-up,
power limits and coastdown still apply. No automatic reactor control is added.

## Reactor INFO tab

Open the reactor controller and select **INFO**. It shows loaded bundles, matched
and unmatched jet assemblies, connected external/internal pumps, flow capacity,
water temperature, inlet subcooling and live thermal/steam output. Hover the fuel
count for a breakdown by fuel type; the **FLUX** tab still shows each bundle's
power, enrichment and burnup.

The starred numbers are **planning estimates**, clearly separated from live output:

- Fuel-loaded rating = existing reactor reference MW × loaded-slot fraction.
- Flow-supported power = reference MW × the smaller of fuel fraction and installed
  maximum forced-flow fraction.
- Steam equivalent = that estimated thermal power divided by the enthalpy rise
  from current feedwater temperature to steam at current vessel pressure.

They are not a prediction of criticality, a safe operating limit, or a cap applied
to the solver. Rods, fuel condition, water supply and the transient physics still
determine actual output. The existing reactor reference rating is unchanged;
size-dependent reactor ratings remain future work.

## Existing saves

The registry id is still `bwr:recirculation_pump`. Saved RCPs retain speed, energy,
computer mode, orientation and one-cell occupancy, now with a compact DVSS model.
Their original front discharge / rear suction remain at the same height. Pick
one up and place it again to build the full model; reconnect the elevated discharge.
The earlier full-sized 3 × 6 × 3 DVSS also keeps its footprint, controller and
flange positions through the saved `enlarged=false` state. Pick it up and replace
it to use the new 5 × 10 × 5 model, then reconnect both headers.
No conversion overwrites neighbouring blocks. Existing unmatched jets must be
rearranged into opposing sets to regain their full flow capacity.

## Model source

The original Blender model is in [art/models/dvss/dvss_recirculation_pump.blend](art/models/dvss/dvss_recirculation_pump.blend).
It was built through the connected Blender instance, using the
[Flowserve DVSS product page and cutaway](https://www.flowserve.com/products/products-catalog/pumps/nuclear-products/nuclear-pumps/flowserve-dvss-nuclear-pump/)
as the visual reference for its broad casing, bottom inlet and tapered shaft
support. The motor, skid, grid elbow, dimensions and colours are artistic choices.
It is an approximate game exterior, not manufacturer CAD or certified equipment.
No manufacturer artwork is redistributed in the mod.

`build_dvss.py` constructs the editable scene and exports `dvss_mesh.json`;
`preview_dvss.py` adds a separate studio rig. The original pump-gallery `.blend`
was not overwritten. `python tools-export-dvss.py` clips the exported triangles
into 250 block cells (plus the 54-cell legacy layout), preserves surface area and validates OBJ normals/bounds.
`--check` compares all 620 generated assets without writing. Runtime rendering
uses the existing NeoForge OBJ loader and adds no mod dependencies.

The enlarged model has a single continuous suction-elbow mesh, with tangent
straight sections and no capped-cylinder joint under the casing. Its suction
axis is 0.5 blocks above the floor; discharge is at 2.5 blocks. The assembly is
uniformly enlarged by 5/3, keeping the casing and motor proportions.
