# RCIC and HPCI Terry turbine pumps

Added in **0.1.0-alpha.11**, Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21.
Use the same mod version on the client and server; GUI protocol is **10**.

## Models and placement

Both items place a complete turbine, coupling and water-pump skid. There is one
simulation block entity per skid. Empty cells inside its rectangular footprint
are reserved for assembly integrity; allow the full volume when placing it.

| Item | Width × height × depth | Exterior |
| --- | --- | --- |
| RCIC Terry Turbine Pump | 5 × 4 × 5 | Compact GS-inspired casing, governor, oil system, gauges and pump |
| HPCI Terry Turbine Pump | 7 × 5 × 5 | Larger C-frame-inspired insulated turbine, pipework and service platform |

These are original game-scale exterior models informed by published references
and the supplied HPCI photograph, not vendor CAD or dimensionally certified replicas.
Blender sources and reproducible build scripts are in `art/models/terry/`.
No vendor drawings or meshes are redistributed.

## Four separate ports

With the block facing **north**, coordinates below are measured from its root
cell at the north-west bottom corner. The entire footprint and each port rotate
with placement. The new GUI reports the actual world coordinates and outward
face, so it also works for rotated or legacy machines.

| Port | RCIC local cell / face | HPCI local cell / face | Connect to |
| --- | --- | --- | --- |
| Steam admission, amber band | (0, 2, 2), west | (0, 3, 2), west | Reactor steam outlet via BWR steam pipes/valves |
| Steam exhaust, silver band | (1, 1, 0), north | (2, 1, 0), north | Valid suppression-tank steam inlet or submerged pool quencher |
| Water suction, blue band | (4, 1, 2), east | (6, 1, 2), east | Condensate tank or selected pool through water pipes; ordinary Mekanism mechanical pipe can fill the inlet buffer |
| Water discharge, teal band | (3, 1, 4), south | (5, 1, 4), south | RPV water injection port through BWR high-pressure water pipes |

Flange bores are round and centered on their block faces. Steam and water pipes
cannot cross-connect at the machine. The water capability is input-only and
available only on the suction flange's outward face; it rejects non-water fluids.
These turbine drives use steam, not FE motors. The new modeling does not add a
Mekanism chemical-steam input; the steam side uses the existing BWR steam circuit.

The turquoise computer panel is on the north face of local cell (3, 2, 0) for
RCIC and (4, 2, 0) for HPCI. Attach a CC:Tweaked modem there. Existing assembly
peripheral access remains available and resolves to the same controller.

## Operating panel

Right-click any visible assembly part to open the dedicated Terry panel:

- Target and actual shaft speed, numeric 0–100% entry, start and stop.
- Panel/redstone/computer ownership and condensate-tank/pool suction selection.
- Admission/exhaust pressure, actual steam consumption, delivered water flow,
  water pressure differential and inlet water temperature.
- Four flange locations, outward directions and live route/water availability.

`Linked` reports a recognized route, not a promise of flow. A connected reactor
can still have no available steam; an empty source or excessive discharge
pressure can prevent injection. `Water ready` means the selected suction source
or ordinary-water buffer has water available. Steam needs a valid exhaust path.
Commands do not implement automatic protection; computers remain player-programmed.

Readouts come from the server at the standard four snapshots per second.
The screen uses cached simulation results and does not trace or rebuild pipes.
The existing shared steam ledger and finite-water accounting still govern flow.

## Existing worlds

Block IDs `bwr:rcic_twl` and `bwr:hpci_turbine` are unchanged. Old saved blocks
default to `modern=false` and retain their models, footprint, port locations and
stored controls. Their GUI also gains the new readouts. Newly placed items use
`modern=true`. To upgrade a placed skid, break it with the appropriate tool and
place the recovered item in the larger clear footprint; reconnect its new ports.
Do not set individual part states manually to change a built machine's version.

## References

- [Curtiss-Wright Terry steam turbines](https://electro-mechanicalsystems.curtisswright.com/sas/markets-and-products/Steam-Turbines)
  — manufacturer overview of solid-wheel RCIC/HPCI service.
- [RCIC Terry turbine research via OSTI](https://www.osti.gov/pages/servlets/purl/2576354)
  — GS-series RCIC context.
- [EPRI HPCI Turbine Maintenance Guide](https://restservice.epri.com/publicdownload/000000003002001669/0/Product)
  — C-frame terminology and sectional arrangement; referenced, not redistributed.
- [Curtiss-Wright governor systems brochure](https://electro-mechanicalsystems.curtisswright.com/sites/default/files/sas/docs/SAS_Brochure_05-08-20.pdf)
  — external governor/actuator context.
- User-supplied HPCI installation photograph — insulated casing, green skid,
  colored ancillary pipework and service-platform reference.

## Rebuild assets

1. Run Blender in background mode with `art/models/terry/build_models.py`.
2. Run `python tools-export-terry.py` to clip meshes and write layout manifests.
3. Run Blender with `art/models/terry/render_icons.py` for the two inventory icons.
4. Run `python tools-export-terry.py --check` to verify generated assets.

The Blender JSON preserves material colors, UVs and mesh geometry. Export checks
surface area and block bounds. Each in-world OBJ cell stays within its one-block
render bounds. The legacy turbine exporter is for archived models and must not
overwrite current blockstates after this exporter runs.
