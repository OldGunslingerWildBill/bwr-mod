# Alpha hardware: SLC, ADS and water discharge

For `0.1.0-alpha.7`, Minecraft 1.21.1, NeoForge 21.1.248 and Java 21.
Install the same build on clients and server; the GUI protocol is **8**.

## Standby liquid control

The SLC system now consumes a finite supply of borated water. The pump needs
Forge Energy and a connected source and recipient. It cannot inject boron from
an ordinary water tank or from an invisible inventory.

| Equipment | Footprint, width × height × depth | Connections in its default north-facing orientation |
| --- | --- | --- |
| SLC Boron Solution Tank | 3 × 4 × 3 | Water fill at the top; solution outlet on the lower front |
| SLC Injection Pump | 3 × 2 × 3 | Suction on the left; discharge on the right; power/computer on assembly faces |

1. Place the tank and pump with room for their complete footprints.
2. Fill the tank's top flange with ordinary water using a Mekanism fluid pipe or
   another compatible fluid handler. Capacity is **15,000 kg of solution**.
3. Use **Borate Charge (25 kg)** items on the tank. Each adds its own mass, so
   leave some capacity for the charges. The tank accepts up to **13% dissolved
   borate by mass**. Its panel reports the resulting elemental-boron equivalent.
4. Run BWR **High-Pressure Water Pipe** from the tank's solution outlet to pump
   suction. Connect pump discharge to a normal **RPV Water Injection Port**, or
   tee it into a feedwater delivery line serving that port.
5. Supply FE, then start and set pump speed in its panel, with redstone, or CC.

One delivery network must resolve to **one formed reactor**. Recirculation
ports are not SLC injection ports. A feedwater pump's discharge may share the
header. Broken, unloaded or ambiguous routes stop delivery. The tank can feed
multiple SLC pumps, which share its finite inventory. The source solution and
its dissolved boron are withdrawn together; filling with plain water dilutes
what remains. The tank exposes ordinary-water filling to other mods, but does
not export its chemical solution as an ordinary-water fluid stack.

The existing SLC duty remains 43 US gpm (about 2.7 kg/s), with a 40 kW motor
rating and a 1,400 psi pressure limit. In-game FE conversion is shared with
the other electric pumps. Actual boron injection follows delivered solution
mass and reactor liquid inventory, rather than a fixed ppm/min bonus.

The borate item recipe and the 0.183 elemental-boron equivalent are gameplay
calibrations. There is no water-chemistry, solubility, tank-heater or enrichment
simulation in this alpha. The recipe does not represent real chemical manufacture.

## ADS division controller and relief valve

The **ADS Division Controller** replaces the old controller's cube model and
keeps its registry ID. The new **ADS Relief Valve** has a side steam inlet and
a downward relief outlet. For the default north-facing valve, the inlet is
the west flange. It is a branch valve, not a straight-through header segment.

- Pipe the inlet to an open RPV steam nozzle with BWR steam pipe. Inlet routing
  respects intervening valve positions and requires one live reactor source.
- Pipe the downward outlet to a submerged suppression-pool quencher. The
  existing open-water discharge fallback also remains available.
- Set controller and valves to the same **division, 0–4**, in their panels or
  through CC. Zero is the legacy default. The controller discovers valves
  within 24 blocks along each axis; different divisions remain independent.
- Command Open/Close locally, through redstone, or with a computer. Controller
  nitrogen charge and powered compressor behavior remain in place.
- An open valve draws from the nozzle's shared steam allowance. The nozzle
  already debits the vessel, so reporting the ADS/pool flow does not debit it
  again. Other turbines and steam consumers share that same finite allowance.

There are **no automatic pressure/level trips or plant control programs**.
Players choose the commands and write their own automation. A relief valve
must be metered by an ADS controller or a suppression-pool controller.

## Water discharge port

This is a **lake/river outfall**, with water input at its rear and an open mouth
at the front. Connect a delivery pipe to the rear flange. The front must face
air or unobstructed water; a solid block or unloaded outlet prevents discharge.

The rate limit is **60,000 kg/s** shared across all input calls in the same
tick. The panel reports flow, cumulative mass and outlet temperature; CC also
reports discharged heat. Water and its carried heat leave the plant inventory.
It does not place new Minecraft water-source blocks or simulate river heating.
The port is passive and needs no motor or block ticker.

## Computer connections

All six faces of the machine/controller blocks are usable modem faces; modeled
assembly parts resolve to their current controller. Use an exposed face that
does not occupy a required fluid connection. Passive structural walls are not
standalone computers.

| Equipment | Peripheral type | Main methods |
| --- | --- | --- |
| Boron tank | `bwr_slc_tank` | `getStatus()` |
| SLC pump | `bwr_slc` | `start()`, `stop()`, `setSpeed(0..1)`, `getStatus()`; status includes actual boron delivery |
| ADS controller | `bwr_ads` | `setDivision(0..4)`, `getDivision()`, `start()`, `stop()`, `setFlow(0..1)`, `getStatus()` |
| ADS relief valve | `bwr_safety_relief_valve` | `setDivision(0..4)`, `getDivision()`, `open()`, `close()`, `setOpen(boolean)`, `getStatus()` |
| Water outfall | `bwr_water_discharge` | `setEnabled(boolean)`, `getStatus()` |

`releaseControl()` hands supported actuators back to redstone. Panel buttons
also offer **Use Redstone**. Optional integrations are installed separately.

## Existing worlds and models

- Replace old single-cube **SLC pumps** to construct the modeled pump and its
  physical ports. Their old implicit boron source is retired.
- Old ADS controller/valve saves default to division zero. Legacy safety-relief
  blocks remain available; the modeled ADS valve is a separate item.
- The complex machine models remain full 3D when placed or held. Inventories
  use Blender-rendered icons, avoiding tens of thousands of quads per slot.

Original Blender source and generation scripts are in `art/models/alpha/`.
`tools-export-alpha.py` exports the block resources and checks rotated port
geometry; `--check` verifies without writing. Inventory icons are generated by
`art/models/inventory/render_icons.py` from the shipped meshes. They are rendered
assets, not screenshots of a user world. No downloaded vendor mesh is included.

Reference background: the [NRC BWR systems overview](https://www.nrc.gov/cdn/legacy/reading-rm/basic-ref/students/for-educators/03.pdf)
and [Columbia license-renewal application, section 2.3.3.43](https://www.nrc.gov/reactors/operating/licensing/renewal/applications/columbia/columbia-lra.pdf)
describe standby liquid control hardware. The game models are illustrative,
not dimensionally exact equipment replicas.
