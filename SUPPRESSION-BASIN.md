# Concrete suppression basin and RHR cooling

The suppression pool can now be built as an open concrete tub. Its wall panels,
coping, water flanges and four-port RHR heat exchanger were authored in Blender.
Existing dug pools remain supported; dedicated water ports opt a pool into the
new concrete-shell rules.

![Concrete basin and connected RHR pump in Minecraft](art/models/suppression/in-game.png)

## Build the basin

1. Build a complete rectangular floor and four walls from **Suppression Pool
   Concrete**. Leave the top open. Outside width and depth may each be 5–25
   blocks; outside height may be 4–16 blocks.
2. Replace one shell block with a **Suppression Pool Controller**. Use exactly
   one controller. Keep adjacent basins' concrete shells separate.
3. Replace side-wall blocks with **Suppression Pool Suction Port** and
   **Suppression Pool Return Port**, with their round flanges facing outside.
   Ports go above the floor and below the rim. Multiple ports are allowed and
   share the same water inventory.
4. Fill the interior with source water. The basin needs at least **64 interior
   spaces below the rim and 64 actual source-water blocks**. Not every combination
   of the minimum dimensions has enough volume. Formation is automatic; allow
   up to five seconds after filling. Sneak-click the controller for
   validation details.
5. Route steam over the rim to submerged suppression-pool quenchers as before.
   A concrete water flange is not a steam inlet.

A **9 wide × 5 high × 7 deep** basin has 7 × 3 × 5 water spaces below its rim:
**105,000 kg** design capacity. Each interior space adds 1,000 kg of capacity;
initial water comes from the source blocks actually placed. Geometry reserves
space for submerged quenchers and steam pipes. No roof is required.

Breaking a wall disables the basin's water ports until repaired. Required chunks
must be loaded. Water and thermal inventory survive repair, controller replacement
and revalidation: resizing does not refill or silently discard stored water.
Existing condensed water above design capacity is retained; further water filling
waits until there is room. The visible Minecraft water surface is not a dynamic
level display; use the pool measurements for the metered inventory.

## Direct LPCI/RHR connection

Connect the **blue suction flange** to the modeled **LPCI/RHR pump's suction**,
either touching it directly or using High-Pressure Water Pipe.

- For reactor injection, connect the pump discharge to an RPV Water Injection
  Port and use the pump's vessel-injection mode.
- For direct pool circulation, connect discharge to the **amber basin return**,
  choose suppression-pool suction and pool-cooling mode, supply FE, then start
  the pump. **Direct circulation alone removes no heat from a concrete basin.**
- The basin return accepts ordinary water; the suction port only allows
  extraction above the pool's existing suction floor. Both expose directional
  NeoForge water capabilities, including for Mekanism Mechanical Pipes.

## Four-port heat exchanger

One block has **four total horizontal flanges**, two for each independent circuit:

| Identification band | Circuit | Connection |
| --- | --- | --- |
| Red | Pool water IN | RHR pump discharge |
| Amber | Pool water OUT | Return to the same suppression basin |
| Blue | Cooling water IN | Cooling-water pump / cold supply |
| Cyan | Cooling water OUT | Heated water back to cooling tower |

When its red flange faces north, amber faces south, blue west and cyan east.
Placement rotates all four together. Top and bottom are not water connections.

```text
pool suction -> powered RHR pump -> RED [heat exchanger] AMBER -> pool return
                                            || heat
cold water / cooling pump -------> BLUE [separate circuit] CYAN -> cooling tower
```

Use **BWR High-Pressure Water Pipe for the complete primary pool loop**. The
exchanger recognizes a running modeled RHR pump and a return to that same basin;
its primary circuit circulates pool water without creating another copy of the
pool inventory. Primary exchanger flanges do not expose generic fluid tanks.
Use one RHR pump per exchanger; parallel cooling trains can share the basin.

The secondary flanges accept BWR water pipes or NeoForge-compatible fluid pipes,
including Mekanism. Supply cold water to blue and drain heated water at cyan.
The exchanger pushes its hot outlet into an adjacent accepting pipe or tank;
it does not pull water from an unpumped remote source. Keep the two circuits
separate when routing pipes. Crossed or incomplete primary plumbing stops RHR
delivery. No water crosses between the circuits.

The exchanger is passive and needs no FE of its own. RHR pumping and any
cooling-water pump/fan still require their usual energy and player commands.
Right-click the exchanger for live flow, transferred heat, inventories and
temperature estimates. Right-click a basin water port to open its pool panel.
The concrete pool panel displays physical exchanger cooling instead of the old
abstract cooling-duty slider. Existing pump local and CC controls still apply.

![Live exchanger panel](art/models/suppression/exchanger-panel.png)

## Current thermal model

- Maximum exchanger duty: **100 MW**, limited by actual primary flow, pool
  temperature and secondary-water availability.
- Separate cold and hot buffers: **12,000 kg each**; secondary flow up to
  **2,400 kg/s**. These are gameplay ratings, not a vendor-certified design.
- Secondary inlet assumes **13 °C**, with a **24 °C** design outlet ceiling.
  Ordinary Minecraft water pipes do not carry temperature yet. Connect a
  cooling tower for the intended plant layout; full temperature propagation
  and enforcement of cooling-tower heat rejection remain future work.
- Dry secondary supply, a full hot buffer, a stopped pump or a broken primary
  return stops heat transfer. The physical model removes heat from the pool
  and adds that heat to the secondary water before it leaves the exchanger.
- There is no built-in automatic RHR start, trip or emergency control logic.

## Assets and verification

Editable source: `art/models/suppression/suppression_pool_and_heat_exchanger.blend`.
`build_models.py` runs in Blender; `tools-export-suppression.py` converts its
exported meshes to the NeoForge OBJ resources. `--check` verifies generated files.
Walls and ports use baked block models, not moving entities; neither has a ticker.
The basin controller and exchanger handle physical updates. Pipe segments retain
their existing machine-driven network behavior.

Regression coverage includes formation, unique ownership, break/repair, cached
fluid handlers after reload, all four exchanger orientations, separate water
inventories, finite cooling, fractional transfer accounting, and preservation of
condensed water during revalidation.
