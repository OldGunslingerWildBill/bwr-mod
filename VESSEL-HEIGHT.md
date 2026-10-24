# Vessel height: separators, carryover, chimney, dome and inventory

Status: in the game (branch `height-physics`). Code: `core/.../thermal/SteamSeparation.java`, `VesselBuild.java`,
`ReactorCore#applyVesselBuild`, `PowerTurbine` (moisture work, wear), and in the mod `VesselHeightBuild`. Tests:
`core/src/test/.../VesselHeightTest.java` and GameTests `bwrheight` (`VesselHeightTests`).

Until this change the height of a built vessel changed how many jets and pumps it needed (`RecirculationSizing`, a cube root
of the volume) and what it looked like, and nothing else. In particular the physics did **not** see the vessel's size at all:
`CoreConfig.steamDomeVolumeM3` (180 m3) and `coolantMassKg` (220 t) were never set from the build, so a 20 high and a 60 high
vessel had the same dome and the same water, natural circulation was `0.366 P^(1/3)` in both, and steam left every vessel
perfectly dry. Now it matters, in the ways a real vessel's height does (all but the dome, section 6).

## 1. Height, in the terms the physics needs

The drawn vessel (`VesselProfile`, RPV-REFERENCE.md) fits Columbia's stack from the top of the fuel to the closure flange between
the drawn fuel top and the drawn flange. One vertical scale `sUp` (blocks per inch) is used for everything above the core, and it
is capped at the horizontal scale `sH = 2 x 0.448 W / 251 in`. So a vessel is either **squashed** (`sUp < sH`: every part above the
core is that fraction of its true height) or at **true scale** (`sUp = sH`), and once true scale is reached extra height lengthens
the standpipes between the shroud head (465 in) and the separator barrels (540 in), nothing else.

Measured off the real geometry (`VesselHeightBuild`), per height as the player counts it (envelope height + 1; 17 x 17 x 28 is
the reference):

| Height | separator scale `sUp/sH` | chimney (top of fuel to separator inlet), equivalent in | water column (fuel to normal level), in | steam space, in of cylinder |
| ---: | ---: | ---: | ---: | ---: |
| 20 | 0.208 | 36.8 | 41.6 | 73.4 |
| 28 (reference) | 0.544 | 96.3 | 109.1 | 137.8 |
| 39 | **1.000** | 179.5 | 203.0 | 225.1 |
| 43 | 1.000 | 245.4 | 269.0 | 225.1 |
| 60 | 1.000 | 525.5 | 549.1 | 225.1 |

**True scale for a 17 wide vessel is 39 high, not 43.** The owner's brief said about 43; the v2 geometry reaches
`sUp = sH` at an envelope height of 38 (a 37 high envelope is 0.964 of true scale), because the closure flange stands 3.06
blocks below the roof and the core takes 9 of the first 11. Real Columbia is 179.5 in from the top of the fuel to the
separators' inlet (540 - 363 = 177 in plus the mod's 2.5 in) and 200.55 in to the normal level; the 39 high vessel draws exactly
that. The "equivalent inches" are the drawn lengths divided by the same vessel's `sH`, so a 23 wide vessel drawn in the same
proportions is the same physical vessel (23 x 23 x 41 is still squashed, scale 0.74).

Everything the physics uses from the build is four numbers (`VesselBuild`); the reactor core takes them with
`ReactorCore#applyVesselBuild` and a core without one (every unit test, the standalone harness) is the reference vessel with a
design-quality separator, bit for bit. The controller installs the build on every validation of a formed vessel.

## 2. Separators and dryer vs their scaled height

Sources: Columbia FSAR (Amendment 67, local copy `references/columbia/Columbia FSAR.pdf`):

- s.7.7.1.4 (PDF p 2911): "The optimum reactor vessel water level is determined by the requirements of the steam separators. The
  separators limit water carry-over in the steam going to the turbines and limit steam carry-under in water returning to the core."
- s.15.3.1.2.3.2 (p 4286): the high level trip L8 "is intended to prohibit moisture carryover to the main turbine"; s.15.3.1.2.? (p 4153):
  "Increasing coolant inventory could result in excessive moisture carryover to components such as the main turbine".
- Fig 5.3-3 (p 2157): L3 low level scram 540.5 in, L4 559.0, normal 563.55, L7 568.0, L8 trip 582.0, steam nozzle 648.0.
- Steam moisture: s.5.4 (p 2172) "saturated steam ... with a moisture content of approximately 0.25 %" at the main steam valves;
  s.10.1 (p 3529) "Steam enters the turbine at 999 psia with a 0.30 % moisture content". These include line allowance. The
  design figure used here is the owner's **0.1 %** for a dryer outlet; Columbia's own numbers are 0.25 to 0.30 %. If the owner
  prefers Columbia's, change `SteamSeparation.DESIGN_MOISTURE` and `PowerTurbine.DESIGN_MOISTURE` together (the rated turbine work is
  defined as the work at that moisture, so nothing else moves).
- Not found in any public source we could read: a figure for Columbia's separator-outlet moisture (about 10 % is the usual
  textbook value) or a carryunder figure. **Estimates**: carryunder design 0.25 % of the return flow; the inlet moisture
  0.852 is `1 - 0.148` (the model's rated exit quality). Patents on BWR separators (US 5106573, US 4182277) say what carryunder
  does: it thins the downcomer water and impairs the subcooling the feedwater provides.

Model (`SteamSeparation`):

- Separation is removal along the swirl and vane path, so moisture falls off exponentially with its length:
  `w = w_in (w_design / w_in)^s`, `w_in = 0.852`, `w_design = 0.001`, and `s = 0.3 + 0.7 r` is the effective length for a vessel drawn
  at `r = sUp/sH` of true scale. The 0.3 is the part that does not shorten when the stack is squashed (swirl vanes, pickoff rings,
  the dryer's vane plates). At `r = 1`: exactly 0.1 %. At `r = 0`: 11 %.
- Carryunder is the same idea the other way: `cu = 0.25 % x 6^(1 - s)`: 0.25 % at true scale, 1.5 % with no swirl run.
- The turbine gets steam of moisture `w` (a new `moisture` on `SteamInventory.Packet`, carried through the HP exhaust to the LP).
  Only the excess over the design 0.1 % acts. Work per kilogram is multiplied by `1 - 0.77 (w - 0.001)`: the lost work is the
  water's share of the latent heat weighted by the cycle's Carnot factor, `1500 kJ/kg x (1 - 313 K / 558 K) / 850 kJ/kg = 0.77`
  (HP 280 plus LP 570 kJ/kg in the model; Baumann's rule of thumb, 1 % efficiency per 1 % moisture, is the same order).
  The steam's enthalpy is lowered by the excess moisture times `h_fg`.
- **Blade wear is the turbine's existing damage** (`PowerModuleBlockEntity.damage`, the counter overspeed adds to, saved as `ShaftDamage`;
  1.0 is failure): each kilogram of steam adds `(w - 0.001) / 8e6`, i.e. 8,000 tonnes of liquid over design destroys the blades.
  Game calibration: about 150 hours of rated flow at the reference's 0.76 % excess, 30 hours at a 20 high vessel. Real erosion is years.
  No new saved key. The turbine shows the moisture it was fed (`inletMoisturePercent`, CC `PowerModulePeripheral`).
- Nothing acts automatically on any of it.

## 3. The water level band

The band is the real one: **L3 = 540.5 in to L8 = 582 in** (vessel inches; the instrument zero is 530 in, so +10.5 to +52 on the
model's scale, +30 normal). It is in real inches of level and does not depend on the build, because the drawn level is anchored
the same way at any height (VesselWaterGeometry). The level used is the physical two-phase level.

- Inside the band: the values above, flat.
- Above L8: moisture multiplies by `exp((L - L8) / 6 in)`, capped at the inlet moisture. 12 in over L8 is 7 times; 24 in over is
  a true-scale vessel at 5.5 % (the separator tops are 50 in over L8, the steam nozzle 66).
- Below L3: carryunder rises toward 10 % with a 12 in e-fold: `cu = cu_band + (0.10 - cu_band)(1 - exp(-(L3 - L)/12 in))`.
- Readouts: INFO tab "Level in band" ("54% of band", "Low, 7% under", "High, 12% over"), CC `getSteamSeparation().levelBand`.

## 4. Carryunder in the reactor: subcooling and natural circulation

Steam in the downcomer water arrives at the core inlet as enthalpy the feedwater must first condense: it comes off the inlet
subcooling by `x_cu x h_fg` (`VoidModel.setSubcoolingAdjustmentKJPerKg`), and as a homogeneous void
`alpha = x / (x + (1 - x) rho_g / rho_f)` it lowers the downcomer density and so the natural circulation head by
`alpha / (1 - 0.5)` (flow goes as its square root). Both are scaled by the separators' loading (power), because carryunder is
a share of the steam made.

The reactor side is calibrated with the reference vessel (the rated subcooling and the 23 % two-pump-trip point), so only the
**difference from the reference's carryunder** acts on it: exactly nothing at the reference at any level in the band, a loss for
a more squashed vessel or a low level, a gain for a vessel at true scale.

## 5. Natural circulation vs the chimney

The driving head is the density difference over the whole two-phase column, `H`: the chimney above the core **and the core's own
150 in of boiling fuel** (about half as buoyant per inch, a mean void near 0.4 against the chimney's 0.7: worth 75 in of chimney).
So `x = (chimney + 75 in) / (reference chimney + 75 in)`, and a vessel with almost no chimney still has its core. The resistance is a
fixed part (core, inlet orifices, downcomer) plus a part proportional to `H` (friction and form loss along the riser and
standpipes). Flow goes as the square root of their ratio:

`f(x) = sqrt( x / (a + (1 - a) x) )`, `a = 0.5` (game calibration).

`f(1) = 1` exactly, and the cap is `1 / sqrt(1 - a) = 1.41` however tall. Per height: 0.89 / 1.00 / 1.09 / 1.14 / 1.25 at
20 / 28 / 39 / 43 / 60. The calibrated line `0.366 P^(1/3)` is the reference vessel's; **at the reference build it is unchanged to the
bit** (`VesselHeightTest.test07`). Carryunder takes head off it as well (section 4).

## 6. Steam dome and water inventory

- `PressureVessel` already takes the dome and the water into its pressure capacity
  (`V_dome drho_g/dP + M_liquid dh_f/dP / h_fg`, 7.3 and 52 kg/psi at rated), so a small dome swings more, but the dome is only
  **13 %** of the capacity at the reference. The water is the rest. (Test: a 60 / 180 / 400 m3 dome swings 90 / 83 / 72 psi in
  two seconds of full-power steam with no outlet, `test10`.) The mod never set the dome from the build, so in the game every
  vessel has had the same 180 m3.
- **The dome does not follow the build, on purpose, pending the owner.** `VesselBuild.domeRatio()` and
  `ReactorCore#setDomeFollowsBuild(true)` do it (96 / 180 / 294 m3 at 20 / 28 / 39 and up, from the steam space between the normal
  water level and the flange plus two thirds of the head's rise; tested, `test11`) but the controller leaves it off. Turned on it
  moves the head missile's energy, which reads the vessel's dome (`ReactorBoundaryEffects.headFailureEnergyJ`, clamped 50 to 600
  m3) and which `HeadMissileTests.ultraReinforcedShieldPlugConcreteHoldsTheHeadInOneRow` was calibrated on at 180 m3 in a small
  fixture vessel: with the dome at about 55 m3 the heavily reinforced tier holds the head in one row, which that test says it must
  not ("1 row(s) of block.bwr.heavily_reinforced_shield_plug_concrete held the head"). Existing assertions are not edited; the
  owner decides whether to move that calibration.
- **Water** = 220 t x `(1 + (water column - 109.1 in) / 538 in)`: the stretched or squashed part of the column above the fuel holds
  water, and the level does not move (the level area, the two-phase region and the fill scale together,
  `PressureVessel#rescaleInventory`; the metal heat capacity with them, as more shell). Bounds 0.5 to 3 times. Above true scale
  extra height is standpipe and downcomer, so it adds water and no steam space. A vessel **resized while it runs keeps its
  water** (none is made; `PumpAssemblyRuntimeCheck` asserts exactly that), so the level moves instead
  (`ReactorCore#resizeVesselBuild`, `test11b`); a vessel forming or loading a save takes the build first and keeps its level.
- **Heat-up** (cold to 1000 psig at 100 MW into a closed vessel): about proportional to the water, 36.6 min at 20 high, 41.7 at the
  reference, 74.9 at 60 (`test12`). It was always proportional to the water in the vessel; it was not tied to the build.
- **Stored energy**: the blowdown volume (`getOccupiedVolumeM3`, dome plus water) comes from the vessel and now sees the build's
  water (190 GJ of sensible energy in the water at the reference, 345 GJ at 60 high, `test14`); containment pressure follows the
  blowdown. With the dome held the head missile's energy does not change with height; with it released it would (above). Nothing is
  scaled twice anywhere.
- **Hydrostatic head** at the foot of the core: `rho g H` over the water above the fuel, 6.8 psi at the reference (0.44 K of
  saturation temperature), 18.4 psi at 60 high (1.2 K). Read-out only (INFO "Core foot sat. rise", CC `coreInletSaturationRiseK`): the rated
  subcooling the model reproduces already contains the real column.
- More blocks to build: unchanged, a natural cost.

## 7. The table (17 x 17 footprint, from `VesselHeightTest.test16`)

| Height | separator scale | steam moisture | carryunder | flow, 4 min after a two-pump trip at rated | water above the fuel, tonnes | heat-up, 100 MW, min | rated output of the 1,941 kg/s train, MWe (model) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 0.208 | 4.22 % | 0.68 % | 19.3 % | 63 | 36.6 | 1,574 |
| 28 (reference) | 0.544 | 0.86 % | 0.44 % | 23.4 % | 72 | 41.7 | 1,616 |
| 39 (true scale) | 1.000 | 0.10 % | 0.25 % | 27.3 % | 84 | 48.8 | 1,625 |
| 43 | 1.000 | 0.10 % | 0.25 % | 29.1 % | 93 | 53.8 | 1,625 |
| 60 | 1.000 | 0.10 % | 0.25 % | 33.2 % | 130 | 74.9 | 1,625 |

"Water above the fuel" is the water between the normal level and the top of active fuel (what must be lost before uncovery).
The rated output is the model's own turbine (`PowerTurbine`, 850 kJ/kg x 0.985, which is a generous game number, not Columbia's
1,190 MWe); the point is the relative loss: 0.6 % at the reference, 3.1 % at 20 high.

Other numbers per height: water 192 / 220 / 258 / 285 / 400 t; chimney factor 0.89 / 1.00 / 1.09 / 1.14 / 1.25; steam dome
if it followed the build 96 / 180 / 294 / 294 / 294 m3 (held at 180 for now, section 6).

## 8. What it does to the 17 x 17 x 28 default

Reactor side: nothing (water, natural circulation, subcooling are the calibrated values). Turbine side: the steam is 0.86 % wet,
so the rated turbine work is 0.6 % lower and the blades wear about 0.7 % of their life per rated hour (150 hours to failure).
That is the intended price of the squashed default; a vessel 39 or more high pays nothing.

## 9. Read-outs

INFO tab "Steam quality and vessel height": steam moisture, carryunder (amber from 1 %, red from 5 % / 3 %), separator height
(% of true), chimney factor, level in band, core foot saturation rise. CC: `reactor.getSteamSeparation()` (all of these plus the band
limits, the dome and the water factor) and `steamMoisturePercent`, `carryunderPercent`, `levelBandPosition` in `getStatus()`; the
turbine peripheral reports `inletMoisturePercent` and its existing `damage`.

## 10. Saves

No saved key for moisture, carryunder or the build (all derived). The reactor save holds the level, not the mass; the mass is
solved from it against the inventory the build gives, so an older save keeps its level in any height of vessel. The one absolute
mass in a save is the water over the flange of a flooded well (`WellInventoryKg`); it now carries `WellInventoryScale`, written only
when not 1, so an older save's well water is rescaled to the vessel it lands in. Turbine steam inventories save `Moisture` only when
there is some.
