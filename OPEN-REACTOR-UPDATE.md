# Open reactor access and sparger corners — alpha.23

## Entering an open vessel

Click **HEAD OFF** in the reactor GUI to enter the existing **Refuelling** vessel
state after depressurising to 25 psig or less. The roof and upper construction cells belonging
to the removed head become passable and stop intercepting your crosshair, on both
client and server. You can enter through the top. Closing the head restores its
collision; a pressure-blown head is also open. The lower vessel walls stay solid.

The saved construction blocks remain in place for structure validation and
reassembly. No blocks are deleted or dropped by moving the head. Chunk reloads
restore access from the saved vessel state. Unforming the vessel restores normal
construction-block collision. Other equipment or pipes placed over the opening
still have their own collision.

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
