# Realistic BWR Physics Mod

A working **Minecraft 1.21.1 / NeoForge** mod that adds a physically modeled
boiling water reactor, modeled pumps, and connected steam and water systems.
Power emerges from neutronics: move the control rods and change recirculation
flow, and the reactor responds. There is no commanded burn rate.

**Current version:** `0.1.0-SNAPSHOT`, in active development.

## What is implemented

- **Reactor physics:** delayed-neutron kinetics, rod worth, spatial flux, void
  and temperature feedback, xenon, fuel burnup, decay heat, vessel inventory,
  pressure, and damage modeling.
- **Continuous rod travel:** power and SRM period respond while rods move
  between notch positions. Mid-travel positions survive saves; outages hold the
  physical position, and reversal and scram start from it.
- **Reactor screens:** operating instruments, fuel/flux information, and an
  **INFO** tab with loaded bundles, jet matching, installed flow capacity, live
  thermal/steam output, and labeled planning estimates.
- **Placeable pump models:** RCIC TWL, HPCI, HPCS, LPCS, RHR/LPCI, electric and
  turbine feedwater, internal recirculation, jet assemblies, and a Blender-built
  DVSS-style external recirculation pump.
- **Pump controls:** right-click a powered pump for numerical 0–100% speed,
  Start/Stop, flow, pressure, and available temperature/supply information.
  Manual, redstone, and computer modes are available where supported.
- **Water supply:** finite 2,000,000 mB condensate storage tanks, ordinary water
  pipe support at pump suction, and pressure-side injection into the vessel.
- **Separate pipe systems:** High-Pressure Water Pipe for water and High-Pressure
  Steam Pipe for steam. Electric pumps use FE and have no steam-drive ports.
- **Integrations:** Mekanism water/steam connections and CC:Tweaked peripherals.
  Both mods are optional and installed separately.

The mod provides hardware and physics. The player provides control logic:
automatic trips, ECCS actuation, and protection sequences are not built in.
Manual controls and the scram actuator remain available.

## Reactor size limits

These dimensions describe the **vessel**, excluding external pumps and piping.

| Dimension | Interior | Outside, including the one-block shell |
| --- | --- | --- |
| Width and depth | 5–21 blocks each | 7–23 blocks each |
| Minimum height | 8 blocks | 10 blocks |

**Maximum footprint: 23 × 23 blocks outside (21 × 21 inside).** Rectangular
footprints are supported within those limits; fuel, rod-drive, and closed-shell
requirements still apply.

Height has no separate configured maximum, but the validator must find the
bottom and top shell within **64 blocks in each direction** from the interior
position beside the controller. With that position centered vertically, this
implies a code-derived upper bound of **127 interior blocks / 129 outside**.
A controller near the bottom allows less height. The tallest configuration has
not been tested in-game; this is a validation bound, not a tested performance
rating. Minecraft build-height limits also apply.

The limits are defined in
[`ReactorStructure.java`](mod/src/main/java/dev/bwr/mod/reactor/ReactorStructure.java).
Larger geometry does not by itself promise proportionally greater thermal power;
the INFO tab distinguishes live output from configuration estimates.

## Water and steam connections

Typical feedwater or emergency injection circuit:

```text
Condensate Storage Tank / Mekanism return water
  → pump water suction → pump water discharge
  → High-Pressure Water Pipe → RPV Water Injection Port
```

Mekanism Mechanical Pipes can feed a pump's suction directly. Use BWR
High-Pressure Water Pipe downstream to inject against vessel pressure. Tank and
suppression-pool suction are separate selections on ECCS pumps.

Steam-powered RCIC, HPCI, and turbine feedwater pumps also need their defined
steam inlet and exhaust circuits. Use High-Pressure Steam Pipe for those ports.
Steam and water networks do not join. Returned Mekanism turbine water can supply
feedwater; no separate condenser block is required.

See [Water plumbing and controls](WATER-PLUMBING.md),
[pump footprints and ports](PUMP-MODELS.md), and
[RCIC/HPCI connections](TURBINE-ASSEMBLIES.md) for placement details.

## External recirculation and jet pumps

The DVSS recirculation pump reserves **5 × 10 × 5 blocks** (width × height ×
depth). Its lower suction elbow connects continuously into the pump casing.

![DVSS recirculation pump model](art/models/dvss/dvss_preview.png)

Build two separate High-Pressure Water Pipe headers:

```text
RPV Recirculation Top Outlet → DVSS rear lower suction
DVSS front discharge → RPV Recirculation Bottom Inlet
```

Both vessel ports must reach the same formed reactor. Supply FE, then use the
pump panel or computer control to start it and set speed. This recirculates
existing vessel water; it does not create inventory or cooling.

New jet assemblies occupy **1 × 6 × 1 blocks**. Place their bases one or two
blocks above the bottom shell, near the interior perimeter. Align assemblies
across opposite walls in the **same row and at the same height**, facing each
other. Off-center rows work. The previous diagonal arrangement remains supported.
Each placed assembly contains two modeled jets; the INFO tab counts assemblies.

At full speed, using normal-size assemblies:

| Connected hardware | Maximum rated core flow |
| --- | ---: |
| One external pump, no matched jets | 5% |
| One external pump, one opposing set (2 assemblies) | 20% |
| One external pump, three opposing sets | 50% |
| Two external pumps, five opposing sets (10 assemblies) | 100% |

These are game balance values. Actual delivery also depends on pump speed,
power, and complete piping. See [Recirculation](RECIRCULATION.md) for the full
flow table, port placement, and matching rules.

## Existing worlds

- Saved compact pumps and older DVSS assemblies retain their original footprint
  and controls. Pick up and replace them to use the current full-size models,
  then reconnect their defined ports.
- Saved two-column jet assemblies stay two columns wide until picked up and
  replaced. Existing matched pairs remain supported.
- Old RCIC/HPCI cube IDs remain loadable but are hidden from crafting and the
  creative tab.
- Replace steam tubes previously used for water with High-Pressure Water Pipe.
  Pump discharge must reach an RPV Water Injection Port.
- Older rod saves start from their recorded notches; new saves preserve
  fractional travel. See [Rod motion](ROD-MOTION.md).

## Build and install

Use **JDK 21**. The project pins Minecraft **1.21.1** and NeoForge **21.1.248**.
The development integrations use Mekanism **10.7.19.85** and CC:Tweaked **1.120.2**
for Minecraft 1.21.1; neither is bundled in the mod JAR.

From the repository root on Windows:

```powershell
.\gradlew.bat build
```

On Linux/macOS, use `./gradlew build`. The build runs the physics acceptance
suite, including longer reactor transients, so allow several minutes.

Install **`mod/build/libs/mod-0.1.0-SNAPSHOT.jar`** in the Minecraft instance's
`mods` folder with NeoForge. The physics core is already packaged inside it.
Install compatible Mekanism and CC:Tweaked versions separately to use their
integrations. Sources and generated models are tracked here; build outputs are
not committed.

## Development and verification

- `core/` — pure Java physics, independent of Minecraft.
- `mod/` — NeoForge blocks, screens, networking, and integrations.
- `art/models/dvss/` — editable Blender models, source meshes, and previews.
- `tools-export-dvss.py` / `tools-narrow-jet.py` — reproducible game assets;
  pass `--check` to verify generated output.

Useful checks:

```powershell
.\gradlew.bat :core:check
.\gradlew.bat :mod:runTurbineGameTest
.\gradlew.bat :mod:runTurbineModelCheck -PbwrPumpPanelCheck
python tools-audit-assets.py
python tools-check-jar.py
```

The September 19 update passed **160 core acceptance tests**, **50 pump runtime
scenarios**, all 96 RCIC/HPCI assembly scenarios, both turbine-plumbing scenarios,
client model checks, and asset/JAR audits. The final rod-restore refinement also
passed all 12 targeted motion/save tests. Exact scope and artifact hashes are in
[BUILD-STATUS.md](BUILD-STATUS.md).

## Further reading

- [Water plumbing, pump controls, and upgrade notes](WATER-PLUMBING.md)
- [Pump model dimensions and port coordinates](PUMP-MODELS.md)
- [RCIC TWL and HPCI turbine assemblies](TURBINE-ASSEMBLIES.md)
- [Recirculation, jet matching, and reactor INFO](RECIRCULATION.md)
- [Continuous rod travel and persistence](ROD-MOTION.md)
- [Design specification](SPEC.md) and [developer handoff](HANDOFF.md)

License: [MPL-2.0 for the mod](mod/LICENSE) and [MIT for the physics core](core/LICENSE).
