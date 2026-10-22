# Containment structure — alpha.31 implementation spec

Status: **implemented in alpha.31, except the dedicated controller GUI.** This
document retains the original handoff order for historical context. It turns the lumped
containment atmosphere from alpha.30 (`dev.bwr.core.accident.ContainmentAtmosphere`)
into something players build in the world.

The controller GUI is deferred to a later visual overhaul. The in-world status
readout and `bwr_containment` CC methods provide its information meanwhile.

## 0. Before starting

Alpha.30 (branch `accident-progression`, PR #20) has **never been compiled on the
mod side**. Do these first:

1. `gradlew check build`, then fix any compile errors in the alpha.30 mod files:
   `SevereAccidentNbt`, `SevereAccidentReadout`, `ReactorSevereAccidentEffects`,
   the `ReactorPeripheral` additions and the `ReactorControllerBlockEntity` edits.
2. Run the GameTests, then add one that saves and reloads a reactor with core
   damage and containment hydrogen, and checks the `SevereAccident` key round-trips.
3. Merge PR #19 (alpha.22–29) and PR #20 (alpha.30) into `main`. Branch from there.

House rules, from the existing codebase: physics stays in `core/` with no
Minecraft imports. No per-block tickers. Cache structure and invalidate it on
change. Never force-load chunks. Every saved state round-trips. Nothing acts
automatically: players automate through CC:Tweaked.

## 1. Design summary

- Players build containment walls from **any full, solid block**, vanilla or
  modded (stone, iron blocks, IE concrete, IE reinforced concrete, and so on).
- A **containment controller** placed inside finds the enclosed room by flood fill.
- **Openings must be mod blocks**: vault door, personnel airlock, and steam,
  water, power and data penetrations, plus a general pass-through for other
  mods' pipes. Penetrations carry ASME-inspired process nameplates and
  isolation valves (§5.3, §5.4). Vanilla and modded doors, slabs, panes and pipes
  are not full cubes, so they leak.
- **Wall strength comes from layering.** Each spot's pressure rating is the sum
  of contributions from the continuous solid blocks behind it. Containment fails
  at its weakest spot, and only that column breaks.
- The free volume measured in the world replaces the fixed 8,000 m³.

## 2. Containment controller

New block `bwr:containment_controller`, with a block entity and no ticker beyond
a slow revalidation timer, the same pattern as `ReactorControllerBlockEntity`.

### 2.1 Flood fill

- Breadth-first search from the controller through **passable cells**: any block
  that is not a wall block (§3), not a sealed opening (§5), and inside the size
  limits.
- Size limits (config): maximum interior volume, default 200,000 cells, and a
  maximum span on each axis, default 128.
- Results:
  - **Sealed:** the search finished inside the limits. Store the interior cell
    count (= free volume in m³), the boundary faces (each interior cell face that
    touches a wall or opening), and the bounding box.
  - **Leaking:** the search hit the limits or reached an unloaded chunk. Record
    the first escape path, meaning the cell where the fill left the plausible
    boundary, so the player gets a "leak at x, y, z" readout and a client
    highlight particle line.
- **Unloaded chunks:** if the search would enter an unloaded chunk, stop and
  **keep the last good result**, flagged stale. Never force-load.
- Run the search spread over several ticks, with a cell budget per tick (config,
  default and maximum 4,000, about 5 ms). Don't block one tick on 200,000 cells.

### 2.2 Invalidation

- Revalidate on a block change inside the bounding box inflated by the scan depth
  (§4), using a NeoForge block place/break event plus neighbor updates on
  openings, and on a slow timer (default every 30 s).
- Keep a small per-dimension index from chunk to containment controller, so the
  block event finds the controllers it affects in O(1).

### 2.3 Linking

- On a sealed result, link every formed reactor whose controller position is
  interior. Use `FormedReactorRegistry` to find reactors in the bounding box.
- Link the suppression pools whose controller is interior. One linked pool is
  the **wetwell**: the vent (§6) discharges through it.
- A reactor that is not linked to a sealed containment is **uncontained** (§7).

## 3. Wall blocks

A block state is a wall block if:

1. it is in tag `bwr:containment_wall` (forced in), **or**
2. its collision shape at that position is exactly a full cube
   (`Shapes.block()`), it is not air or a fluid, it is not replaceable, **and**
   it is not in tag `bwr:containment_leaky`.

Default `bwr:containment_leaky`: `#minecraft:leaves`, scaffolding, powder snow,
cobwebs and honey blocks, plus any obviously gas-permeable full cube found in
testing. Glass counts as a wall, because glass is airtight.

Doors, trapdoors, slabs, stairs, fences, panes and every pipe or cable from any
mod fail the full-cube test and leak. That is intended.

## 4. Layered wall strength

### 4.1 Per-block contribution

```
psi(block) = clamp(K * sqrt(blastResistance), 0, PER_BLOCK_CAP)
```

Defaults: `K = 5.8`, `PER_BLOCK_CAP = 250`. Use `BlockState.getExplosionResistance`
(with the level and position overload) so modded blocks report their own values.

Calibration targets these must hit, checked by unit tests in core:

| Wall | Target rating |
| --- | --- |
| 3 × wood planks (resistance 3) | ≈ 30 psig (≈ 10 per block) |
| 1 × stone or iron block (resistance 6) | ≈ 14 psig |
| 2 × very high-resistance block (obsidian, IE reinforced concrete) | well above 140 psig |

A datapack override `data/<ns>/bwr_containment/strength.json` maps block IDs or
tags to an exact psi per block, and wins over the formula.

### 4.2 Rating a spot

For each boundary face, march **outward along the face normal** from the wall
block, summing `psi(block)` over **continuous** wall blocks:

- Stop at the first non-wall cell. Air gaps end the count, so a decorative outer
  shell doesn't add strength.
- Stop after `SCAN_DEPTH` blocks (config, default 16). A column that reaches the
  depth limit is rated at its sum so far, flagged `deep`. A reactor inside a
  mountain therefore rates very high and costs at most 16 reads per face.
- Cache each face's rating. Recompute only the faces whose column contains a
  changed block.

**Containment failure pressure** = the minimum rating over all boundary faces
and all openings (§5). Store the weakest spot's position, material, layer count
and rating, and show it in the controller GUI and over CC **before** anything goes
wrong, so players can find and reinforce it.

### 4.3 Failure in the world

When `ContainmentAtmosphere` reports overpressure or detonation failure:

- If the weakest spot is a wall column, break that column's counted blocks with
  `destroyBlock(pos, true)` so they drop. That leaves a real hole. Leave every
  other block alone.
- If the weakest spot is an opening (§5), put that opening into its damaged
  state: the door blown open and jammed, or the penetration ruptured.
- Fire the existing failure effects (`ReactorSevereAccidentEffects`) at that
  position instead of at the reactor controller.
- The next flood fill then finds the hole, so containment stays unsealed until
  the player repairs it, which happens naturally by placing blocks.

## 5. Openings (mod blocks)

Each opening is a wall-equivalent while sealed and passable while open. Each
has a fixed pressure rating (config).

### 5.1 Vault door — `bwr:containment_vault_door`

- Placed from one item as a multiblock door, 3×3 by default with an optional
  4×4 variant, in a wall plane with a frame. Use a placement preview, like the
  condenser's green/red preview.
- States: CLOSED_SEALED, OPENING, OPEN, CLOSING, DAMAGED_JAMMED.
- Swing time about 10 s when powered (FE buffer, like the MSIV). A handwheel
  right-click works without power, slowly (about 60 s). Redstone and CC control
  are available.
- Sealed only in CLOSED_SEALED. Any other state is a large leak path.
- Rating: 200 psig. It's the strongest opening, but still weaker than a deep
  reinforced-concrete wall, so in a mountain it becomes the weakest point.
- Model: Blender-built like the other machines (`art/models/containment`): a
  thick frame with keeper notches, a hinge groove and a status lamp column; a
  ribbed leaf; latch dogs and a hand-wheel as their own meshes. A stroke first
  turns the wheel and slides the dogs out of their keepers (the first 28 %),
  then swings the leaf 82 degrees into the room about its hinge pin. The
  animation is drawn on the client from the block entity's stroke times.
- Collision follows the drawing: the seal breaks at the start of an opening
  stroke, but the cells stay solid (`held`) until the leaf is half open, and a
  closing leaf makes them solid again when it is half shut.

### 5.2 Personnel airlock — `bwr:containment_airlock`

- A two-door unit, one block wide and two high, with an inner and outer door
  and a short chamber between them.
- Interlocked: one door can't open while the other is open. Walking through
  never unseals.
- Each plain redstone pulse advances the interlocked passage: open inner,
  close inner, open outer, close outer. A CC `setOverride(true)` is the only
  command that forces both doors open. Releasing it does not close either leaf;
  the medium leak persists until at least one leaf closes.
- Rating: 120 psig.
- Model: two D-shaped hatches in bulkhead plates, each with a hinge, a hand-wheel
  on both faces and a lamp column (red open, amber moving, green shut and
  latched); a pressure-equalising line with a valve wheel (decoration).
- A leaf moves in 12 ticks: the wheel turns for 3, the leaf swings for 9. The
  interlock is decided at once and is unchanged; what is drawn and what is
  walkable follow the motion (`inner_move` / `outer_move` hold a leaf's cells
  solid until it is half open, or open until it is half shut). The second
  leaf's drawn motion waits for the first to finish shutting.

### 5.3 Penetrations

Each is a full-cube wall block that also carries one connection straight through.
Every penetration has **two separate ratings**, as real ones do:

- **Process side:** the pressure of the fluid inside the line. Penetrations are
  ASME-inspired nameplates set at or above the pipe they carry, so a penetration
  is never the weak link on the process side.
- **Containment side:** the pressure difference across the containment wall
  that breaks the penetration sleeve. That number is what goes into the
  weakest-spot calculation (§4.2).

| Block | Carries | Process-side nameplate | Basis | Containment side |
| --- | --- | --- | --- | --- |
| `bwr:containment_steam_penetration` | High-Pressure Steam Pipe (`pressurised_tube`) | **1,250 psig at 575 °F** design, 1,375 psig code limit | ASME Section III-inspired; same as vessel and main steam line design (`PhysicalConstants.VESSEL_DESIGN_PRESSURE_PSIG`, `VESSEL_CODE_LIMIT_PSIG`) | 150 psig |
| `bwr:containment_water_penetration` | High-Pressure Water Pipe | **2,500 psi** design | ASME Section III-inspired; matches the existing High-Pressure Water Pipe nameplate (B31.1-inspired, 2,500 psi) | 150 psig |
| `bwr:containment_pass_through` | Other mods' fluids, Mekanism chemicals, items | **1,480 psi** design | ASME Class 600, carbon steel at ambient temperature | 150 psig |
| `bwr:containment_power_penetration` | FE | Electrical, no process pressure; FE throughput cap (config) | IEEE 317-inspired (electrical penetration assemblies) | 150 psig |
| `bwr:containment_data_penetration` | CC wired modem cable, redstone | None | IEEE 317-inspired | 150 psig |

Rules:

- The steam penetration joins `SteamLineNetwork` on both faces (check
  `SteamLineNetwork.acceptsLineOn`). The water penetration joins
  `WaterLineNetwork`. Neither accepts the other's pipe, and neither accepts the
  pass-through's traffic.
- The **pass-through** forwards the NeoForge fluid, item and energy capabilities
  and the Mekanism chemical capability (soft dependency) from one face to the
  other, with no inventory of its own. It is how players run Mekanism, IE or any
  other mod's pipes through a sealed wall. It does not connect to the mod's own
  steam or water pipes, so it can't be used to carry reactor coolant.
- **No new pipe-burst solver.** Process-side overpressure on the reactor lines is
  already modelled by `BoundaryStress` (main steam and feedwater line failure
  above 1,250 psig). The nameplates are shown in the tooltip, the controller
  GUI and over CC, in the same style as the water pipe:
  `ASME Section III-inspired | Process design: 2,500 psi | Containment: 150 psig (simulation)`.
  Like the water pipe, it is a gameplay nameplate, not certification.
- All process and containment ratings are config values, defaulting to the
  table.
- Containment-side failure turns the block into its **ruptured** state. A
  ruptured penetration counts as an opening (a leak) whatever its isolation
  valve is doing, because the sleeve itself has failed. It stops passing traffic
  until repaired (break and replace).

### 5.4 Containment isolation

Real plants put an isolation valve on every line that crosses containment. The
steam, water and pass-through penetrations each have an **integral isolation
valve**:

- States OPEN and CLOSED. Closed passes nothing.
- Stroke time about 4 s, powered from an FE buffer, controlled by redstone and
  CC, the same as the MSIV. It **fails closed** (spring) when the buffer runs out,
  like the MSIV. That is passive hardware behavior, not automatic protection:
  nothing commands it closed on a signal.
- On a steam line, an existing MSIV anywhere on the same line counts as an
  isolation valve too. The line is isolated if **either** the penetration valve
  or an MSIV on it is closed, which mirrors the real inboard and outboard pair.
- Power and data penetrations have no valve.

**Why isolation matters: containment bypass.** After cladding failure (core
damage stage 4 or higher), reactor steam and water carry fission products. A
steam or water line that is open through a penetration to equipment outside
containment carries them straight out, unscrubbed, bypassing containment. This is
why real MSIVs close on high radiation. Closing the line stops it. See §7 for the
physics.

The pass-through never carries reactor coolant, so it never causes a bypass.
Its valve exists so a player can isolate a line for maintenance or a leak test.

## 6. Containment equipment

- **Nitrogen supply** `bwr:containment_nitrogen_supply`: an interior wall block
  that drives `setInertingCommanded(true)` while powered and supplied. Feeding it
  a Mekanism nitrogen chemical when Mekanism is present is optional. Without a
  supply block, `setContainmentInerting` over CC does nothing and returns false.
- **Wetwell vent valve** `bwr:containment_vent_valve`: mounts on the linked
  suppression pool. It drives `setVentOpen` and needs an outlet to open air or
  a stack. It is powered, with redstone and CC control. Without one,
  `setContainmentVent` returns false.
- Igniters and recombiners are optional and later.

## 7. Physics changes (core)

Add to `ContainmentAtmosphere`, keeping all existing behavior and tests:

- `setFreeVolumeM3(double v)`: change the volume while keeping each species'
  **mole fraction and the pressure**, as if the room were rebuilt at constant
  conditions. Clamp to at least 50 m³.
- `setFailurePressurePsig(double p)`: replaces the constant 140 psig check. The
  default stays 140 for standalone cores and tests.
- `setLeakArea(double fraction)`, 0 to 1: an unsealed containment, with a
  forced-open airlock, an open vault door or a missing wall. It behaves like
  `FAILED` for gas exchange and releases, scaled by the fraction, **without**
  setting a failure mode. Restoring the seal (fraction 0) stops it.
- **Uncontained reactors** (no linked sealed containment): run with leak area
  1.0. Hydrogen disperses rather than accumulating, and releases go unscrubbed to
  the environment, with no burns indoors.
- **Containment bypass** (§5.4), in `ReactorCore`:
  `setContainmentBypassFlowFraction(double f)`, where `f` is the steam and water
  flow leaving containment through open penetrations, as a fraction of rated
  steam flow. The mod sets it each tick from the linked controller. While fission
  products are being released from the fuel, a share
  `min(1, BYPASS_CARRYOVER × f)` of that release goes straight to the
  environmental release instead of into the containment airborne inventory, with
  no scrubbing. `BYPASS_CARRYOVER` is a named game-calibration constant (start at
  0.5). Zero before cladding failure, zero when every line is isolated, and zero
  for an uncontained reactor (everything already goes out).
- Extend the snapshot with a version bump for the new fields, and keep reading
  old 22-length snapshots.

Unit tests to add in `ContainmentHydrogenTest` or a new class:

- Halving the volume doubles the hydrogen percentage for the same release.
- A failure pressure of 30 psig fails on a burn that 140 survives.
- Leak area 1.0 never accumulates past the flammability limit under a steady
  release. Leak area 0 after a leak period resumes accumulation.
- The snapshot round-trips, and a legacy snapshot loads.
- Bypass: with `f = 0` the environmental release is unchanged; with lines open
  after cladding failure it rises and the containment airborne share falls by
  the same amount; before cladding failure an open line releases nothing.
- The calibration formula and targets in §4.1 (pure functions, so they live in core).

## 8. Readouts and CC

New peripheral `bwr_containment` on the controller:

- `isSealed()`, `getLeak()` (position or nil), `getFreeVolume()`,
  `getFailurePressure()`, `getWeakestSpot()` (pos, material, layers, rating,
  deep), `getOpenings()` (list with type, pos, state, containment rating, and
  for penetrations the process nameplate, carried service and isolation state),
  `getLinkedReactors()`.
- `setIsolation(x, y, z, closed)` strokes a penetration's isolation valve and
  returns false if there is no valve there or no power; `isIsolated()` is true
  when every steam and water line through the wall is closed;
  `getBypassFlow()` returns the bypass flow fraction.
- It mirrors the atmosphere getters from `ReactorPeripheral.getContainment()`.
- Controller GUI: sealed or leaking, volume, weakest spot, openings list, and
  hydrogen, oxygen and pressure.

## 9. Saves

The controller saves: the last sealed result (volume, bounding box, weakest
spot, opening list), a stale flag and linked positions. **The atmosphere stays
saved by the reactor** under the existing `SevereAccident` key, so the gas isn't
lost if the controller is broken and replaced. Breaking the controller makes
linked reactors uncontained until a new one validates.

## 10. GameTests

- A sealed stone box: correct volume, sealed, linked reactor.
- One block removed: leak reported at that position. Replacing it reseals.
- A vanilla door in the wall leaks. A closed vault door seals and an open one leaks.
- Airlock interlock: the second door refuses to open.
- A wall of 3 × oak planks rates about 30. 2 × obsidian rates above 140.
  Stone 20 deep reports `deep` with a capped scan.
- Overpressure breaks exactly the weakest column and nothing else, and the
  next fill reports the leak there.
- A steam penetration carries steam through and keeps the seal. A water pipe
  does not connect to it, and a steam pipe does not connect to the water one.
- The pass-through moves a Mekanism or vanilla-capability fluid through a sealed
  wall (skip the Mekanism part when it is absent) and won't connect to a mod
  steam or water pipe.
- Tooltips and CC report the nameplates: steam 1,250 psig, water 2,500 psi,
  pass-through 1,480 psi, containment side 150 psig.
- A closed isolation valve passes nothing; an open MSIV with a closed
  penetration valve counts as isolated; cutting power fails the valve closed.
- A ruptured penetration leaks even with its valve closed.
- Save and reload keep sealed state and weakest spot. An unloaded boundary
  chunk keeps the last result, flagged stale.
- Without Mekanism and CC: the controller, doors and penetrations still work.

## 11. Suggested build order

1. Core physics changes (§7) with unit tests.
2. Controller, flood fill and leak readout (§2, §3).
3. Layered ratings, weakest spot and failure breakage (§4).
4. Penetrations with their nameplates (§5.3), then isolation valves and bypass
   (§5.4, §7). Without penetrations no real plant can seal.
5. Vault door (§5.1), then the airlock (§5.2).
6. Nitrogen supply and vent valve (§6), then the CC peripheral, GUI and
   documentation.

## 12. Known limitations

- **Some block changes wait for the 30 s timer.** Only player placement and
  breaking (`BlockEvent.EntityPlaceEvent` and `BlockEvent.BreakEvent`) trigger
  an immediate resurvey or column re-rating. Pistons, explosions, fluid spread,
  `/setblock`, `/fill` and other mods' block placers are not seen until the
  revalidation timer (§2.2, default every 30 s) runs the next survey. That is
  the timer's job as the backstop. A global `NeighborNotifyEvent` hook is
  deliberately not used: it fires far too often to filter on every block
  update.
