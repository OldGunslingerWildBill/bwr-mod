# Realistic BWR

<p align="center"><img src="mod/src/main/resources/logo.png" alt="Realistic BWR" width="420"></p>

**By [OldGunslingerWildBill](https://github.com/OldGunslingerWildBill)**

A working **Minecraft 1.21.1 / NeoForge** mod that adds a physically modeled
boiling water reactor, modeled pumps, and connected steam and water systems.
Power emerges from neutronics: move the control rods and change recirculation
flow, and the reactor responds. There is no commanded burn rate.

**Current version:** `0.1.0-SNAPSHOT` — development build, updated September 21, 2026.

| Platform | Required version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 |
| Java | 21 |
| Optional integrations | Mekanism and CC:Tweaked, installed separately |

The current update adds modular HP/LP turbine trains, generators, physical
steam stop/control valves, modern pump connections, dyeable pipes and
volume-based recirculation. **Turbine admission is now controlled at the
upstream valve**, rather than separately in each turbine's panel.

Source is publicly readable under the custom
[Realistic BWR Source-Available License](LICENSE). Modified redistribution
and copying code/assets into another project require written approval, subject
to the preserved rights for earlier MIT/MPL releases. Permitted redistribution
must credit **OldGunslingerWildBill**. See [license and credit](#license-and-credit).

## What is implemented

- **Steam valve control:** placeable Blender-built stop and fine control valves
  regulate a common header feeding multiple HP turbines. HP exhaust supplies
  the LP sections. Valve panels provide Open/Close and 0.1% adjustments; turbine
  panels report operating data. See [steam valves and migration](STEAM-VALVES.md).
- **Modular main turbine trains:** Blender-built TX-10-style HP and LP sections
  and a TX-NLCH-style generator. Aligned end shafts join; steam is piped between
  sections independently. LP water outlets accept Mekanism mechanical pipes.
  Turbine panels and CC readouts report flow, pressure, temperature estimates,
  shaft speed and power. Each generator supports up to 1,500 MW electrical.
  See [the turbine build guide](MODULAR-TURBINES.md) for connections, dimensions
  and the temporary condensation model.
- **Reactor physics:** delayed-neutron kinetics, rod worth, spatial flux, void
  and temperature feedback, xenon, fuel burnup, decay heat, vessel inventory,
  pressure, and damage modeling.
- **Continuous rod travel:** power and SRM period respond while rods move
  between notch positions. Mid-travel positions survive saves; outages hold the
  physical position, and reversal and scram start from it.
- **Reactor screens:** operating instruments, fuel/flux information, and an
  **INFO** tab with loaded bundles, jet matching, installed flow capacity, live
  thermal/steam output, and labeled planning estimates.
- **Volume-based recirculation:** width, depth and height determine the flow
  target. Each external recirculation pump supports ten normal jet assemblies;
  additional jets stop increasing flow at the drive limit. INFO shows the targets.
- **Placeable pump models:** RCIC TWL, HPCI, HPCS, LPCS, RHR/LPCI, electric and
  turbine feedwater, internal recirculation, jet assemblies, and a Blender-built
  DVSS-style external recirculation pump.
- **Modern pump connections:** centered round flanges on HPCS, LPCS, RHR/LPCI,
  and both feedwater pumps, with odd-width footprints and larger RHR/feedwater
  bodies. These models and the new pipe fittings are authored in Blender.
- **Pump controls:** right-click a powered pump for numerical 0–100% speed,
  Start/Stop, flow, pressure, and available temperature/supply information.
  Manual, redstone, and computer modes are available where supported.
- **Water supply:** finite 2,000,000 mB condensate storage tanks, ordinary water
  pipe support at pump suction, and pressure-side injection into the vessel.
- **Separate pipe systems:** High-Pressure Water Pipe for water and High-Pressure
  Steam Pipe for steam. Electric pumps use FE and have no steam-drive ports.
- **Dyeable round pipes:** swept elbows, junctions and coupling rings. Right-click
  with any of the 16 vanilla dyes to color the identification bands. Color is
  cosmetic; water and steam stay separate.
- **Integrations:** Mekanism water/steam connections and CC:Tweaked peripherals.
  Both mods are optional and installed separately.

The mod provides hardware and physics. The player provides control logic:
automatic trips, ECCS actuation, and protection sequences are not built in.
Manual controls and the scram actuator remain available.

## Build a working steam plant

```text
RPV steam outlet
  -> Steam Stop Valve -> Turbine Steam Control Valve -> HP inlet header
                                                          |       |
                                                         HP      HP
                                                          |       |
                                                          +---+---+
                                                       crossover header
                                                         | | | |
                                                        LP LP LP LP
                                                         | | | |
                                                    condensate return
                                                          |
                                        storage tank -> feedwater pump
                                                          |
                                                RPV Water Injection Port
```

1. Place turbine sections and a generator with **touching, aligned end shafts
   at the same height**. Each module is one large placeable object. Steam pipes
   connect the sections separately from the shafts.
2. Feed the HP top inlets through a common steam header. Place the stop and
   control valves before the branch if one valve should control all HP sections.
   Use **High-Pressure Steam Pipe** at steam ports.
3. Pipe HP side exhausts into LP top inlets. Route LP water outlets to storage
   or feedwater suction using **Mekanism Mechanical Pipes** or the supported
   water network. Return pressure-side water through BWR High-Pressure Water
   Pipe and an RPV Water Injection Port.
4. Connect an energy consumer or storage to the generator's **copper terminal**.
   A full energy buffer, full condensate buffer or blocked exhaust limits flow.
5. Right-click the valves: **new valves start closed**. Open the stop valve,
   then adjust the control valve in **0.1% steps**. HP/LP panels report operation;
   they no longer have local admission settings or Start/Stop buttons.

| Module | Width × height × length | Game capacity |
| --- | --- | --- |
| TX-10 Style HP Turbine | 5 × 5 × 9 | Up to 2,200 kg/s |
| TX-10 Style LP Turbine | 7 × 5 × 7 | Up to 750 kg/s |
| TX-NLCH Style Nuclear Generator | 5 × 5 × 9 | Up to 1,500 MW electrical |

Valve opening controls steam supply, not a fixed electrical power percentage.
HP sections share that supply, and LP sections consume their actual exhaust.
Stored downstream steam can continue expanding briefly after the stop closes.
The stop valve takes 0.5 seconds for a full stroke; the control valve takes two.

LP sections currently provide **temporary built-in condensation**, returning
water at a displayed 40 °C. A separate condenser and moisture separator/reheater
are future work. Steam temperatures are saturation estimates; full plant-wide
water-temperature transport is not implemented. These are game-scale models,
not manufacturer performance simulations.

See [steam valves and their CC API](STEAM-VALVES.md) and
[modular turbines](MODULAR-TURBINES.md) for the full connection and operating guide.

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

The target uses whole interior volume: **12 × cube_root(volume / 200)**,
rounded up to opposing pairs, with a minimum of 12 normal placed assemblies.
Each full-speed external pump supports **ten normal assemblies' worth of flow**.

| Outside footprint and height | Matched assemblies | Full-speed external pumps |
| --- | ---: | ---: |
| 7 × 7 footprint, 10 tall | 12 | 2 |
| 7 × 7 footprint, 18 tall | 16 | 2 |
| 23 × 23 footprint, 10 tall | 32 | 4 |
| 23 × 23 footprint, 23 tall | 44 | 5 |

For the 32-assembly target, two pumps cap at 62.5%, three at 93.75%, and four reach
100%. Adding more jets cannot exceed pump capacity. A complete loop retains weak
flow without jets. These are game balance values; actual delivery also depends
on speed, power and complete piping. See [Recirculation](RECIRCULATION.md) for
the volume rule, current INFO/CC readouts and upgrade details.

## Existing worlds

- **Turbine control migration:** older HP/LP admission and running settings are
  ignored. Existing directly fed sections now use available steam automatically.
  Install upstream stop/control valves before operating the updated plant.
  Stored steam, water and energy survive the update. CC scripts must replace
  turbine `setAdmission` / `setRunning` calls with valve `setPosition`, `open`
  and `close` calls. [Migration details](STEAM-VALVES.md#computercraft).
- Saved compact pumps and older DVSS assemblies retain their original footprint
  and controls. Pick up and replace them to use the current full-size models,
  then reconnect their defined ports.
- Saved two-column jet assemblies stay two columns wide until picked up and
  replaced. Existing matched pairs remain supported.
- Previously assembled HPCS, LPCS, RHR/LPCI and feedwater pumps keep their old
  models and connections. Pick up and replace them to use the modern flanges and
  larger footprints. [Dimensions and upgrade notes](MODERN-PUMPS-AND-PIPES.md).
- Old RCIC/HPCI cube IDs remain loadable but are hidden from crafting and the
  creative tab.
- Replace steam tubes previously used for water with High-Pressure Water Pipe.
  Pump discharge must reach an RPV Water Injection Port.
- Older rod saves start from their recorded notches; new saves preserve
  fractional travel. See [Rod motion](ROD-MOTION.md).
- Existing reactors now use volume-based recirculation targets. Check INFO for
  required jets and drives; larger vessels may need more hardware. Save migration
  preserves physical flow and water rather than multiplying them on reload.

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
- `art/models/modern/` — five revised pump models and dyeable pipe fittings in Blender.
- `art/models/power_turbines/` — editable HP, LP and generator Blender scenes.
- `art/models/steam_valves/` — editable stop/control valves and in-game previews.
- `tools-export-dvss.py` / `tools-narrow-jet.py` / `tools-export-modern.py` — reproducible game assets;
  pass `--check` to verify generated output.
- `tools-export-power-turbines.py` / `tools-export-steam-valves.py` — turbine and valve exporters;
  both also support `--check`.

Useful checks:

The Python audit/export commands below require Python 3.11 or newer.

```powershell
.\gradlew.bat :core:check
.\gradlew.bat :mod:runTurbineGameTest
.\gradlew.bat :mod:runTurbineModelCheck -PbwrPumpPanelCheck
.\gradlew.bat :mod:runTurbineModelCheck -PbwrPowerPanelCheck
python tools-audit-assets.py
python tools-check-jar.py
python tools-export-steam-valves.py --check
```

The steam-valve gameplay update passed **12 valve scenarios**, **15 main-turbine
scenarios**, **73 pump scenarios**, **96 RCIC/HPCI assembly scenarios**, two
earlier plumbing scenarios and five focused turbine-physics tests. Actual
client checks covered the models, connected train, readout panels and valve
commands, including a 65.3% setting and zero flow after stop-valve closure.

The README/license update changes documentation, attribution, metadata and
packaged notices. Its build and JAR checks are separate from those gameplay
tests. The full long-running core acceptance suite was not rerun for this
documentation update. See [BUILD-STATUS.md](BUILD-STATUS.md) for exact scope,
older verification results and artifact hashes.

## Further reading

- [Steam stop/control valves, shared admission, and CC migration](STEAM-VALVES.md)
- [Modular main turbines, generator, and temporary condensate return](MODULAR-TURBINES.md)
- [Water plumbing, pump controls, and upgrade notes](WATER-PLUMBING.md)
- [Pump model dimensions and port coordinates](PUMP-MODELS.md)
- [Modern pump flanges, round pipes, and dye colors](MODERN-PUMPS-AND-PIPES.md)
- [RCIC TWL and HPCI turbine assemblies](TURBINE-ASSEMBLIES.md)
- [Recirculation, jet matching, and reactor INFO](RECIRCULATION.md)
- [Continuous rod travel and persistence](ROD-MOTION.md)
- [Design specification](SPEC.md) and [developer handoff](HANDOFF.md)

## License and credit

**Realistic BWR by OldGunslingerWildBill**

Original project: <https://github.com/OldGunslingerWildBill/bwr-mod>

Current original material uses the custom
**[Realistic BWR Source-Available License 1.0](LICENSE)**. These approval
restrictions mean it is **source-available**, not open source under the
[Open Source Initiative definition](https://opensource.org/osd).

| Use under the new license | Permission |
| --- | --- |
| Read, download, build, run, or edit privately | Allowed |
| Share an unchanged project/release, including an unchanged JAR in a modpack | Allowed with credit and all required notices |
| Publish modified source, modified forks/patches, or modified JARs | Owner's prior written approval required |
| Copy or adapt project code/assets into another distributed project | Owner's prior written approval required |
| Remove attribution or claim you created the original project | Not permitted |

Every permitted redistribution must include [LICENSE](LICENSE) and
[NOTICE](NOTICE), visibly credit **OldGunslingerWildBill**, and link to this
repository. Approved changes must be identified separately. Credit is not a
substitute for permission. Request written approval through the
[GitHub issue tracker](https://github.com/OldGunslingerWildBill/bwr-mod/issues).

**Earlier releases retain their rights.** Through commit
[`19a7424`](https://github.com/OldGunslingerWildBill/bwr-mod/tree/19a742408e2cd7ef8cee42a26157e8662daa340b),
the core was MIT and the mod was MPL-2.0. This change does not revoke those
grants or prevent reuse of that previously released material under its earlier
terms. The new restrictions cannot override an independent earlier license
covering the same material. See [LICENSE §6](LICENSE) and
[historical and third-party notices](THIRD-PARTY-NOTICES.md).

Independent player-written Lua programs and unrelated mods remain their
authors' work. Third-party components keep their own licenses. This summary
does not replace the full license.
