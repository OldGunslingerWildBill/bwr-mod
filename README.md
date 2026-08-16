# Realistic BWR Physics Mod — Project Handoff

## What this is

A Minecraft mod (1.21.1 / NeoForge) that replaces Mekanism's fission reactor with a
physically-modelled boiling water reactor. Power is **emergent** from neutronics —
you set rod position and recirculation flow, and the physics decides what the reactor
does. There is no commanded "burn rate."

This is a design package, not a codebase. No code has been written yet. The spec is
complete enough to start implementing.

## Files

| File | What it is |
|---|---|
| `SPEC.md` | The authoritative specification. Read this first, in full. |
| `spec-v1-draft.md` | Earlier draft, kept for history. **Superseded** — do not implement from it. |

## Why this mod exists

Mekanism's fission reactor is a single first-order heat balance:

```
heat added per tick = burnRate × energyPerFissionFuel   (default 1e6)
temperature         = storedHeat / (casingHeatCapacity × casingCount)
```

That's it. Steady-state temperature has a closed form. There is no neutron flux, no
delayed neutrons, no reactivity, no void feedback, no burnup, no decay heat. Burn rate
is a number the operator types in.

The fundamental mismatch: in a real BWR you do **not** command power. You move rods and
change flow, and power is what the physics gives you. That inversion is the whole point
of this project, and it's why the decision was to replace Mekanism's reactor rather than
wrap it — wrapping means fighting the mod's core assumption every tick.

## Where to start

**Build `ReactorCore` first, standalone, outside Minecraft.**

It is pure Java with zero Minecraft imports. Give it a `main()` that runs a transient and
dumps CSV. Validate that you get a believable startup, load change, and SCRAM curve
*before* writing any NeoForge code. This is the piece with real technical risk and it is
independently testable.

The single biggest implementation hazard is **stiffness**: prompt neutron lifetime is
~4e-5 s against a 0.05 s game tick. Naive forward Euler at tick rate diverges immediately.
Sub-step inside `step()`, or use an integrator that handles the prompt jump analytically.
See SPEC §1.1.

Build order is SPEC §14.

## Architecture in one line

`ReactorCore` (pure physics, doubles, testable) ← ticked by → `ReactorMultiblockBlockEntity`
(structure validation, NBT, client sync, CC:Tweaked peripheral).

Keep NBT calls out of `ReactorCore`. It exposes `toState()` / `fromState()` returning a
plain record; the BlockEntity serialises. That same snapshot serves persistence, client
sync, and the peripheral readout.

## Design principles that shouldn't be quietly overridden

1. **The mod provides hardware and physics. The player provides control logic.**
   ECCS auto-actuation, backup SCRAM logic, and level control scripts are written by the
   player in CC:Tweaked. There is deliberately **no built-in hardwired safety backstop**.
   If you find yourself adding one "for usability," that's a spec change, not an
   implementation detail — flag it.

2. **Realism is the tiebreaker, not convenience.** Where a real BWR does something
   awkward (rods insert from the bottom, one drive per rod, spargers at fixed elevations),
   the mod does it too. Several mechanics that look like balance decisions are actually
   physics — most notably the danger of plutonium/MOX fuel, which comes from its delayed
   neutron fraction being roughly a third of U-235's, not from a hardcoded penalty.

3. **Failures should be traceable to neglect, not dice rolls.** A stuck control rod
   happens because that CRD's accumulator wasn't charged. A pipe break happens to the
   component that accumulated the most overpressure stress.

4. **Scope cuts already made deliberately** — don't quietly re-add them: per-jet-pump
   asymmetric flow (scalar total flow only), natural circulation, physical crane refueling,
   heavy water / sodium coolant, custom pump impeller models.

## Known-good scope boundaries

- **Light water only.** Water *is* the moderator; that's why the void coefficient is
  negative and why this is a BWR. Adding a separate moderator block makes it a different
  reactor type (graphite + water cooling = RBMK, positive void coefficient).
- **Lumped pressure volumes, not per-pipe-segment pressure.** Custom pipes handle
  connectivity and validation. Two lumped volumes: RPV steam dome, main steam header.
- **Nodal flux solve at 1 Hz, point kinetics at 20 Hz.** Flux *shape* changes slowly;
  power *level* changes fast. This timescale separation is the improved quasi-static
  method and it's standard practice, not a hack.
- **Not ray tracing / Monte Carlo** for neutron transport. Too slow, statistically noisy,
  and it would make power output visibly jitter. This was considered and rejected.

## Integration points

- **Steam out:** convert to Mekanism steam at the turbine interface so existing Mekanism
  turbines and generators keep working. Everything upstream of that is ours.
- **Fuel in:** craft assemblies from Mekanism's fissile fuel / enrichment intermediates.
  Reuses the player's existing ore processing. Makes Mekanism a hard dependency, which is
  acceptable for the target deployment.
- **CC:Tweaked:** `IPeripheral` on the controller. Build this **early** — the existing
  control room becomes the physics test harness. The peripheral API is safety-critical
  because actuation logic lives in Lua; design it deliberately rather than letting it
  accrete.

## Context

This targets an existing in-world plant: four reactor units currently running Mekanism
fission reactors, monitored by a Mekanism SCADA system with a ComputerCraft panel layer
on top (water tank levels, plus a BWR-style core cooling display with unit selection,
ECCS branch flows, and wide-range / fuel-zone / TAF level instrumentation). Those panels
are strictly read-only and must not interfere with the underlying SCADA. The new mod
should slot in beneath that existing control room rather than replacing it.

## Open questions

SPEC §17. None of them block starting on `ReactorCore`.
