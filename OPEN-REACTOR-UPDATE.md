# Open reactor access and sparger corners — alpha.23

## Opening the vessel: studs, then the crane

The head no longer comes off with a button. As at a real plant it takes two machines, in this order
([BUILDING-CRANE.md](BUILDING-CRANE.md) has the full rules and numbers):

1. **Depressurise** to 25 psig or less.
2. **Detension the studs.** A **Stud Tensioner** on the refuel floor beside the well, powered with FE, goes round the closure flange one
   stud at a time (4 s and 3,000 FE each: 296 s and 222,000 FE for the 74 studs of the reference vessel). The nuts and washers come off;
   the studs stay in the flange. The vessel cannot hold pressure from the first stud on.
3. **Lift the head.** The **reactor building crane** lifts the head on its strongback onto a **Head Stand** on the refuel floor. Only now is the
   vessel in the existing **Refuelling** state: the roof and upper construction cells belonging to the removed head become passable and
   stop intercepting your crosshair, on both client and server, and you can enter through the top.
4. **Reverse** it after refuelling: the crane sets the head back on the flange (the dryer and the separators must be in), then the tensioner
   brings the studs to load (6 s and 6,000 FE each) and the vessel can hold pressure again.

The reactor panel's head button now explains this procedure instead of lifting the head. The server option **`instantHeadRemoval`**
(`bwr-gameplay.toml`, default **off**) gives the old button back for casual play: on, it lifts and seats the head at once, with every
stud bare or at load, as before.

**Old worlds.** A world whose head is already off stays off. The head counts as laid down clear of the vessel (no stand block needed) and
the crane, or the option, puts it back. A world with the head on has every stud at load. The new saved keys (`StudsOff`, `HeadAt`) are written only
while they are not the default.

The saved construction blocks remain in place for structure validation and
reassembly. No blocks are deleted or dropped by moving the head. Chunk reloads
restore access from the saved vessel state. Unforming the vessel restores normal
construction-block collision. Other equipment or pipes placed over the opening
still have their own collision. A pressure-blown head is also open; the lower vessel walls stay solid.

## Cherenkov-style light

Submerged fuel has a blue emissive glow whose brightness follows the simulated
total fission and decay power. It fades as the power and water coverage fall.
An empty lattice, dry core, or cold unused fuel does not glow. The effect is
visible through the open head and from inside the vessel; it stays hidden behind
the closed steel head when viewed from outside.

This is a bounded visual approximation, not a radiation transport or player-dose
model. It uses a few translucent surfaces rather than particles or changing world
light blocks, and does not invalidate the cached steel or fuel meshes. The vessel
water remains the existing visual representation of simulated inventory; it does
not add vanilla water blocks or swimming physics.

Reference: [Oak Ridge National Laboratory's HFIR refuelling photo and explanation](https://sns.gov/content/hfir-refueling)
show blue Cherenkov light from submerged irradiated fuel, including after shutdown.
The brightness mapping used here is an artistic game-scale approximation.

## Flooding the well (cavity seal)

With the head off, water can be raised out of the vessel over the flange and up the **reactor well**, the shaft the player builds above the vessel. A **cavity seal**,
drawn as part of the vessel (a steel plate and a dark collar from the round flange to the square footprint and the walls), forms where a wall of full blocks touches the
vessel or stands one block away all the way round, at the highest such row from the flange down to at most `round(H / 3)` blocks below the top of the vessel (the upper third). The seal holds the
water above the drywell; the INFO tab and `getCavity()` say whether it is present, and if not, which side is how many blocks too far. Without a seal the water leaks into the drywell
(4000 kg/s at most) and the level holds at the flange. The water over the flange is part of the vessel's inventory and level (above the +60 in lip the well's cross-section replaces
the vessel's), is drawn as one translucent surface in the well and the annulus, and is what the refuelling bridge reads as the water over the fuel. Nothing floods by itself: your feed,
condensate and residual heat removal pumps do it. Full rules, volumes and tests: [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#the-reactor-well-and-the-cavity-seal).

## Placing spargers

Use the same **Core Spray Sparger** item. Two perpendicular neighboring segments
automatically select the new Blender 90-degree elbow; opposite neighbors select
a straight segment. All four corner orientations work. Removing a neighbor updates
the piece. A newly placed segment inherits an adjacent segment's LPCS/HPCS setting;
empty-hand right-click still switches that setting.

Build along the vessel's inside perimeter, including corners. LPCS belongs one
block above the top of active fuel, and HPCS four blocks above it. Those positions
are five and two blocks below the original top construction layer, respectively.
Forming the vessel still displays the completed ring as a circular header.

Both client and server should use alpha.23. No new dependency is required.

## Verification

- All 73 NeoForge integration tests passed, including real entity entry, closed
  head collision, save/reload, smallest vessel, all four corner orientations,
  inherited HPCS placement and wet/dry/power conditions.
- In-game captures verified the daylight glow, nighttime interior, dry core and
  both corner colors. Static mesh upload counts stayed unchanged as glow and water
  changed. The asset audit and final JAR packaging checks passed.
