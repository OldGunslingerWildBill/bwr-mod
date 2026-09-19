# Realistic BWR Physics Mod — Project Handoff

## What this is

A Minecraft mod (1.21.1 / NeoForge) that replaces Mekanism's fission reactor with a
physically-modelled boiling water reactor. Power is **emergent** from neutronics —
you set rod position and recirculation flow, and the physics decides what the reactor
does. There is no commanded "burn rate."


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

