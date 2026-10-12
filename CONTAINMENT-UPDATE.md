# Buildable containment — alpha.31

The containment controller surveys a closed room, measures its gas volume,
rates the weakest wall column, and links formed reactors and suppression pools
inside it. A leak leaves the reactor uncontained until the boundary is repaired.
The vessel's severe-accident atmosphere remains saved on the reactor.

## Worked example

1. Build a room around one formed 17×17 reactor and one suppression pool. Leave
   ample space around the 28-block-high vessel; an interior around 40×32×34
   blocks is a practical starting point. Make floor, walls and roof from full,
   solid cubes. Multiple continuous layers of strong material increase the
   weakest-spot pressure rating. A pipe or vanilla door in the boundary leaks.
2. Put a **Containment Controller** inside the room. Read its status to confirm
   *sealed*, free volume, weakest spot, openings, and links to the reactor and
   pool. If it reports a leak, fix the named coordinate and let it resurvey.
   Keep the boundary chunks loaded during operation; the controller never
   force-loads them.
3. Use **containment steam/water penetrations** wherever a line crosses the
   wall. Their service is fixed when crafted; orient the straight-through axis
   along the pipe. Power their isolation actuators with FE. The valve fails
   closed when power runs out. Use power/data penetrations for cables and a
   general pass-through for compatible foreign services. A downstream MSIV is
   still a separate steam-line stop valve.
4. Install a 3×3 (or 4×4) **vault door** and a **personnel airlock** at access
   points. Each replaces the full wall opening. Power the vault for its fast
   swing, or use its slower handwheel. A redstone pulse cycles the airlock's
   leaves: inner open, inner close, outer open, outer close. CC `setOverride`
   alone forces both leaves open, which makes a medium leak.
5. Place a **Containment Nitrogen Supply** on an interior wall, connect FE,
   and right-click it with a **Nitrogen Canister**. One canister supplies up to
   72,000 running ticks; purging consumes 4 FE/t. Use CC on the reactor to call
   `setContainmentInerting(true)`, or `setActive(true)` on the supply. Without
   charged, powered equipment the reactor command returns `false`.
6. Build either a **concrete basin** or **enclosed suppression tank** and form
   it before mounting the **Wetwell Vent Valve** beside its shell. Point the
   valve's front toward exterior open air, or through a straight run of up to
   32 high-pressure steam-pipe blocks ending in exterior open air. Cross the
   containment wall with a powered, open steam penetration; a bare pipe would
   leak. Supply FE;
   use redstone, `setActive(true)` on the valve, or reactor CC
   `setContainmentVent(true)`. The reactor command returns `false` without a
   powered, connected valve and a clear discharge. Do not route the outlet
   back into the containment room.

## Computer control and readouts

The controller exposes a `bwr_containment` CC:Tweaked peripheral. Methods:
`isSealed`, `getLeak`, `getFreeVolume`, `getFailurePressure`,
`getWeakestSpot`, `getOpenings`, `getLinkedReactors`,
`setIsolation(x,y,z,closed)`, `isIsolated`, `getBypassFlow`,
`getContainment`, `getHydrogenPercent`, `getOxygenPercent`, and
`getPressurePsig`. `getOpenings` includes each penetration's service,
nameplate, containment rating and isolation state. `getContainment` mirrors
the reactor atmosphere table. The controller status readout covers seal/leak,
volume, weakest spot, openings, and hydrogen/oxygen/pressure. A dedicated
controller GUI is coming in a later GUI overhaul.

Example CC program after attaching a wired modem to the controller:

```lua
local room = peripheral.find("bwr_containment")
assert(room, "Connect the containment controller")
print("sealed", room.isSealed(), "volume m3", room.getFreeVolume())
local spot = room.getWeakestSpot()
if spot then print("weakest psig", spot.ratingPsig) end
for _, opening in ipairs(room.getOpenings()) do
  if opening.service == "steam" then
    room.setIsolation(opening.pos.x, opening.pos.y, opening.pos.z, true)
  end
end
print("hydrogen %", room.getHydrogenPercent())
```

## Known limits

- The room solver uses cubic gas-cell volume and a single atmosphere; it does
  not simulate local gas eddies, stratification, or detailed radiation transport.
- Corium heats and ablates the basemat through the alpha.30 lumped accident
  model. It can fail containment, but has no free-flowing fluid mesh.
- Valve and wall ratings are gameplay approximations inspired by engineering
  standards, not safety certifications.
- The vent stack is straight and at most 32 blocks from the valve. Branched
  or bent stacks are not supported in alpha.31.
- The controller GUI is deferred. Use its in-world readout or CC methods.
