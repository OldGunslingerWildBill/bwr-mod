# Water supply, pump controls, and recirculation

## Condensate storage and pump suction

The **Condensate Storage Tank** holds **2,000,000 mB** of finite water. It now has
a tank-shaped model and a right-click level panel. Fill it with buckets or water
pipes; extraction is available on every face. Existing inventories are preserved.
Stored condensate uses the existing **32°C** temperature; no new storage thermal
simulation is introduced. The mod conversion is **1 mB water = 1 kg**.

Use either return-water arrangement:

- **Mekanism Mechanical Pipe → pump water-suction flange**, directly. A
  high-pressure segment is not required upstream. Configure Mekanism's source
  connection to extract/pull if the source does not push water itself.
- **Mekanism return → Condensate Storage Tank → High-Pressure Water Pipe → pump
  suction**, for a shared feedwater/emergency reserve. BWR pumps draw from the
  connected tank. A Mechanical Pipe can also extract CST water and deliver it to
  a pump's suction buffer.

On ECCS pumps select **Suction: Tank / piped water**. **Suppression pool** is a
separate manual selection. Feedwater pumps always use tank/piped water. Each inlet
buffer holds **2,000 mB**; after disconnecting a source, buffered water remains
usable until exhausted. Neither pipes nor tanks generate free water.

## Pump discharge and controls

Run **High-Pressure Water Pipe** from water discharge to an outward-facing **RPV
Water Injection Port** in the vessel wall. The port now has a full pressure-boundary
cube, fixing the recessed-face gap. Right-click it to check vessel ownership.
Ordinary pipes cannot bypass the pump and inject against vessel pressure.

Right-click any powered pump cell to open its panel:

- Enter **speed 0–100%**, press Apply/Enter, and Start/Stop. Actual speed ramps up;
  power supply and reactor pressure still limit delivery.
- Read target/actual speed, water flow, pressure differential, suction-water
  temperature, and stored FE or steam-drive identification.
- Choose **Control: Panel / Redstone / Computer** (RCP/RIP: Panel/Computer).
  Computer ownership disables manual buttons until control is handed back.
- On ECCS choose tank/piped-water or pool suction. On RHR select reactor injection
  or pool cooling.

Electric pumps require FE and have no steam ports or steam-admission readout.
RCIC, HPCI and turbine feedwater pumps retain their steam admission/exhaust paths.
Existing core-spray rings are still needed for LPCS/HPCS. No new sprayer or
condenser is added.

Speed commands control shaft speed separately from the existing flow throttle.
Lua now exposes `setSpeed(0..1)` and `getTargetSpeed()` for ECCS/feedwater. Older
saves default to 100% speed demand. No automatic start or source switching is
added. RCP/RIP show their core-flow contribution and vessel pressure/temperature;
their external water-loop temperature and pump head are not separately simulated.
Disconnected recirculation pumps show temperature unavailable. Passive jets have
no independent motor or speed panel.

## External recirculation

The DVSS model occupies a 5 × 5 footprint and is 10 blocks tall. Build two separate High-Pressure Water
Pipe headers: **RPV top outlet → blue rear lower suction**, and **teal front
upper discharge → RPV bottom inlet**. Both ports must belong to the same formed
vessel. The top outlet goes in the upper half of a side wall; the bottom inlet
in either lowest side-wall row beside the interior. Both face outward.

New jet assemblies occupy one column. Align them across opposite walls in the
same row and at the same height, with opposite facings. Their bases can be 1 or 2 blocks
above the bottom shell. A loop without matched jets provides weak circulation;
matched jets raise capacity. See [RECIRCULATION.md](RECIRCULATION.md) for the
flow table, placement example and the new reactor INFO tab.

Breaking a header removes its flow contribution. New jets register within one
second; pump associations refresh within two seconds. Recirculation does not
create water or cooling. RIP mounting is unchanged.

## Pipe compatibility

| Application | Pipe |
| --- | --- |
| Condensate / return water → pump suction | Mechanical Pipe or other NeoForge water pipe; BWR water pipe also works |
| Tank or suppression-pool controller → pump suction | High-Pressure Water Pipe; select the matching source |
| Pump discharge → RPV injection port | High-Pressure Water Pipe |
| RHR pool-cooling discharge → selected pool | High-Pressure Water Pipe |
| Vessel recirculation ports ↔ RCP | High-Pressure Water Pipe |
| RPV steam nozzle → main turbine or turbine-driven pump | High-Pressure Steam Pipe |
| RCIC/HPCI exhaust → submerged pool quencher | High-Pressure Steam Pipe |
| Turbine feed-pump exhaust → turbine steam outlet | High-Pressure Steam Pipe |

Steam and water pipes never join. Pump casings do not bridge the headers. BWR
water pipes forward pushed NeoForge water to actual storage; they have no per-pipe
inventory and do not extract from arbitrary external tanks. Surveys stop at 256
loaded blocks and never load chunks.

## Existing worlds

- Existing RCPs retain compact occupancy, orientation and same-height front/back
  connections. New placements build the DVSS model with an elevated discharge.
  Rewire through the vessel recirculation ports and match jets on opposite sides.
- Tank inventory, pump commands and existing block IDs are preserved. Legacy
  compact pumps remain compact until picked up and placed again.
- Legacy RCIC/HPCI cubes remain supported but hidden from recipes/creative.
- Replace steam tubes used for water with water pipes. Modeled pump discharge
  must reach an injection port rather than the reactor controller.
- No automatic conversion moves or deletes equipment.

## Pressure nameplate

The tooltip remains **ASME B31.1-inspired | Design pressure: 2,500 psi (simulation)**.
It is a gameplay nameplate, not certification or a pipe-burst solver; pump curves
and reactor backpressure govern delivery.

## Verification

Runtime tests cover placement, sided ports, actual Mekanism Mechanical Pipe network
delivery into electric pumps, finite tank/buffer accounting, save/load, panel versus
computer control, jet-count limits and broken/reversed recirculation loops. Client
checks bake the models; optional panel QA opens real server-synchronized screens
in a new isolated world. The fixture does not build a complete Mekanism Generators
turbine. See [BUILD-STATUS.md](BUILD-STATUS.md) for results.
