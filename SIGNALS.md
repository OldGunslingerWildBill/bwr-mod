# Signal layer: devices, signals and commands

`dev.bwr.mod.signal` is a CC-independent view of the plant, built for control panels and any other
in-game consumer. `SignalProviders.at(level, pos)` returns a `SignalProvider` for the device at a position
(any cell of a multiblock resolves to its controller, exactly as the CC peripheral lookup does), or `null`
when nothing is there or the chunk is not loaded. It never loads a chunk and never acts on its own.

* Values are the numbers the CC peripherals report, from the same getters and in the same units. A signal
  with no value (reactor not formed, room with no linked reactor) reads empty; panels show "no signal".
* Booleans read as 0 / 1. `Infinity` passes through as the peripheral reports it (an infinite reactor
  period). Ranges are display hints for gauges, not clamps on the reading.
* Commands go through the same code as the CC setters (`DeviceCommands`), so power requirements,
  interlocks and `computerControlled` behave identically. A refused command returns `false` and changes
  nothing. A command that is NaN, or outside its range, is refused. For BOOLEAN commands a value of 0.5 or
  more means on. A PULSE command fires once whatever value is sent.
* The MSIV `open` command is the exception to "refused changes nothing": it records the demand exactly like
  CC's `open()` (the valve opens when power returns) and returns whether the valve can move now, so an
  unpowered open returns `false` with the demand stored.
* Keys are lowercase snake_case and stable. A LEVEL is a whole-number step (a rod notch, a damage stage).
* Drywell and wetwell pressure signals read separate gas volumes.
  `containment_pressure_nodes` or `pressure_nodes` reads `2`. The older
  `containment_pressure`/`pressure` signal reports the greater current pressure.
* Flow, head and energy ranges for pumps and cooling units differ per design; the table range is an upper
  bound and the device also reports `rated_flow` for scaling.
* A command whose key equals a signal key (for example `open` on a valve) is the writable half of that
  signal; the two lists are separate.

This file is generated from the adapter sources in `dev.bwr.mod.signal.adapters`; keep it in step when a
signal is added. Coverage: reactor controller, MSIV, SRV, turbine valves, turbine steam outlet, RPV steam
outlet, feedwater pump, recirculation pump, ECCS pumps, suppression pool, RHR heat exchanger, condenser,
condensate tank, cooling, power module, containment controller, penetrations, vault door, airlock, and
containment atmosphere equipment. The ADS controller, SLC tank, water discharge and other passive hardware
have CC peripherals but are not in v1 coverage.

## Reactor controller

Block entity `ReactorControllerBlockEntity`; CC peripheral `bwr_reactor`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `formed` | Reactor formed |  | 0 to 1 | BOOLEAN |
| `power` | Total power | fraction of rated | 0 to 1.2 | NUMBER |
| `neutron_power` | Neutron power | fraction of rated | 0 to 1.5 | NUMBER |
| `decay_heat` | Decay heat | fraction of rated | 0 to 0.1 | NUMBER |
| `thermal_mw` | Thermal power | MW | 0 to 4295 | NUMBER |
| `period` | Reactor period | s | -100 to 100 | NUMBER |
| `aprm_percent` | Average power range | % | 0 to 125 | NUMBER |
| `reactivity_dollars` | Reactivity | $ | -5 to 1 | NUMBER |
| `pressure` | Dome pressure | psig | 0 to 1400 | NUMBER |
| `level` | Indicated water level | in | -200 to 100 | NUMBER |
| `core_flow` | Core flow | fraction of rated | 0 to 1.2 | NUMBER |
| `void_fraction` | Core void fraction | fraction | 0 to 1 | NUMBER |
| `natural_circulation` | Natural circulation flow | fraction of rated | 0 to 1 | NUMBER |
| `recirc_flow_demand` | Recirculation flow demand | fraction of rated | 0 to 1.2 | NUMBER |
| `recirc_capacity` | Recirculation capacity | fraction of rated | 0 to 1.2 | NUMBER |
| `steam_generation` | Steam generation | kg/s | 0 to 2500 | NUMBER |
| `steam_outlet_flow` | Steam outlet flow | kg/s | 0 to 2500 | NUMBER |
| `feedwater_flow` | Feedwater flow demand | kg/s | 0 to 2500 | NUMBER |
| `feedwater_delivered` | Feedwater flow delivered | kg/s | 0 to 2500 | NUMBER |
| `injection_flow` | Injection flow | kg/s | 0 to 2500 | NUMBER |
| `break_steam_flow` | Break steam flow | kg/s | 0 to 2500 | NUMBER |
| `break_liquid_flow` | Break liquid flow | kg/s | 0 to 5000 | NUMBER |
| `shutdown_cooling_mw` | RHR shutdown cooling | MW | 0 to 500 | NUMBER |
| `vessel_metal_temp_c` | Vessel steel temperature | degC | 0 to 600 | NUMBER |
| `cooldown_rate_c_per_hour` | Vessel cooldown rate | degC/h | 0 to 500 | NUMBER |
| `cooldown_thermal_stress` | Cooldown thermal-stress life used | fraction | 0 to 5 | NUMBER |
| `interfacing_systems_loca` | Interfacing systems LOCA |  | 0 to 1 | BOOLEAN |
| `coolant_temp_c` | Coolant temperature | degC | 0 to 400 | NUMBER |
| `fuel_temp_c` | Fuel temperature | degC | 0 to 3000 | NUMBER |
| `clad_temp_c` | Cladding temperature | degC | 0 to 2500 | NUMBER |
| `peak_clad_temp_c` | Peak cladding temperature | degC | 0 to 2500 | NUMBER |
| `oxidation` | Cladding oxidation | fraction | 0 to 1 | NUMBER |
| `hydrogen_kg` | Hydrogen generated | kg | 0 to 5000 | NUMBER |
| `boron_ppm` | Boron concentration | ppm | 0 to 1000 | NUMBER |
| `core_damage_stage` | Core damage stage | stage | 0 to 8 | LEVEL |
| `containment_h2_percent` | Containment hydrogen | % | 0 to 100 | NUMBER |
| `containment_o2_percent` | Containment oxygen | % | 0 to 25 | NUMBER |
| `containment_pressure` | Containment pressure | psig | -5 to 150 | NUMBER |
| `containment_steam_percent` | Containment steam | % | 0 to 100 | NUMBER |
| `containment_gas_temp_c` | Containment gas temperature | degC | 0 to 400 | NUMBER |
| `containment_drywell_pressure` | Drywell pressure | psig | -5 to 150 | NUMBER |
| `containment_wetwell_pressure` | Wetwell pressure | psig | -5 to 150 | NUMBER |
| `containment_pressure_nodes` | Containment pressure nodes | nodes | 0 to 2 | NUMBER |
| `containment_spray_flow` | Containment spray | kg/s | 0 to 2500 | NUMBER |
| `containment_inerted` | Containment inerted |  | 0 to 1 | BOOLEAN |
| `containment_vent_open` | Containment vent open |  | 0 to 1 | BOOLEAN |
| `containment_failed` | Containment failed |  | 0 to 1 | BOOLEAN |
| `scram` | Scram active |  | 0 to 1 | BOOLEAN |
| `end_of_cycle` | End of cycle |  | 0 to 1 | BOOLEAN |
| `crd_powered` | Control rod drive powered |  | 0 to 1 | BOOLEAN |
| `crd_water_supplied` | Control rod drive water supplied |  | 0 to 1 | BOOLEAN |
| `average_burnup` | Average burnup | MWd/t | 0 to 60000 | NUMBER |
| `rod_count` | Control rod count | rods | 0 to 300 | NUMBER |
| `charged_accumulators` | Charged accumulators | rods | 0 to 300 | NUMBER |
| `rods_not_at_demand` | Rods not at demand | rods | 0 to 300 | NUMBER |
| `vessel_state` | Vessel state |  | text | TEXT |
| `core_damage_stage_name` | Core damage stage name |  | text | TEXT |
| `rod_<n>_position` | Rod n position (n from 1) | notch | 0 to 48 | LEVEL |
| `rod_<n>_demand` | Rod n demand | notch | 0 to 48 | LEVEL |
| `rod_<n>_accumulator` | Rod n accumulator charge | fraction | 0 to 1 | NUMBER |
| `selected_rod` | Selected rod (0 = none; 1-based like n) | rod | 0 to 400 | LEVEL |
| `rod_<n>_drift` | Rod n away from its demand with nothing driving it | | 0 to 1 | BOOLEAN |
| `rod_<n>_scram` | Rod n: scram latched and the rod not yet fully in | | 0 to 1 | BOOLEAN |
| `rod_<n>_full_in` | Rod n at notch 00 | | 0 to 1 | BOOLEAN |
| `rod_<n>_full_out` | Rod n at notch 48 | | 0 to 1 | BOOLEAN |
| `rod_<n>_accum_trouble` | Rod n accumulator below 0.9 of a full charge | | 0 to 1 | BOOLEAN |
| `rod_<n>_lattice_x` | Rod n core x (GE XX of XX-YY) | | 0 to 99 | LEVEL |
| `rod_<n>_lattice_y` | Rod n core y (GE YY of XX-YY) | | 0 to 99 | LEVEL |
| `rod_<n>_label` | Rod n coordinate, `XX-YY` | | text | TEXT |
| `lprm_count` | LPRM strings | strings | 0 to 100 | LEVEL |
| `lprm_<s>_label` | LPRM string s coordinate, `XX-YY` | | text | TEXT |
| `lprm_<s>_<a-d>_percent` | LPRM string s, level a to d, indication | % | 0 to 125 | NUMBER |
| `lprm_<s>_<a-d>_bypassed` / `_failed` / `_downscale` / `_upscale` | LPRM level flags | | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `scram` | Scram | PULSE |  |
| `reset_scram` | Reset scram | PULSE |  |
| `recirc_flow` | Recirculation flow demand | NUMBER | 0 to 1.2 |
| `boron_injection` | Boron injection rate | NUMBER | 0 to 100 |
| `containment_inerting` | Containment inerting | BOOLEAN | 0 to 1 |
| `containment_vent` | Containment vent | BOOLEAN | 0 to 1 |
| `rod_<n>_position` | Rod n position (even notch labels only) | LEVEL | 0 to 48 |
| `select_rod` | Select rod n (0 clears the selection) | LEVEL | 0 to 400 |
| `selected_rod_insert` | Selected rod: insert one notch | PULSE |  |
| `selected_rod_withdraw` | Selected rod: withdraw one notch | PULSE |  |
| `selected_rod_insert_continuous` | Selected rod: insert continuously (1 holds, 0 releases) | BOOLEAN | 0 to 1 |
| `selected_rod_withdraw_continuous` | Selected rod: withdraw continuously (1 holds, 0 releases) | BOOLEAN | 0 to 1 |

### Per-rod indications and LPRM strings (full core display)

These are plain facts for the full core display and for any program; none of them acts on the plant. They read no
signal unless the reactor is formed. Rods and strings are numbered from 1.

* **Coordinates.** `rod_<n>_label` is the rod's coordinate in the GE `XX-YY` convention and `rod_<n>_lattice_x/_y` are its
  two numbers. Columns run 02, 06 ... 58 from west to east and rows 03, 07 ... 59 from south to north (so the number
  rises up the core, as on the Browns Ferry display); a core with fewer than 15 rods across is centred in that grid
  (its middle rod is `30-31`), a wider one starts at 02 and runs on. The rod number n is the drive's number, as for
  `rod_<n>_position`.
* **full_in / full_out**: the rod is at notch 00 or 48. **scram**: the scram signal is latched and the rod is not fully
  in (the core has no per-rod scram state). **accum_trouble**: the accumulator holds less than 0.9 of a full charge; the
  core has no notion of low charge (only charged or empty), so 0.9 is a gameplay default and a display component has a
  setting for it.
* **drift**: the core models no spontaneous rod motion, so the real "moving without a command" cannot happen. The
  signal is the mirror image the model does have: the rod is not at its demanded notch, no scram is forcing it and its
  drive cannot index (no power, no water, seized or missing). A rod travelling toward its demand under power does not
  drift. It is what `rods_not_at_demand` counts while the rod stays put, per rod.
* **LPRM strings.** A string stands in the gap between four rods; the mod places one wherever the GE grid has a point
  (`x` a multiple of 8, `y` one more than a multiple of 8, as in `08-49`) with a rod on all four sides: 39 strings in the
  185-rod core, fewer in a smaller one. They are numbered in reading order (top row first, west to east). `lprm_<s>_label`
  is the coordinate. Each string has four levels, a (bottom) to d (top).
* Each level now samples the nodal flux at its string's lattice location and axial height (A-D at
  1/8, 3/8, 5/8, 7/8 of active height, using the containing axial node). Six APRMs receive spatially
  interleaved inputs: strings are ordered by radius and distributed back and forth across six channels,
  with the assignment rotated for each axial level. Channel names preserve the first string's
  A-D rack addresses (channels 1-4, input 1). Every chamber has a unique
  input index counted within that channel. Each channel samples all four axial levels across the core.
  There is **no modulo-144 wrap**: the 39-string formed core has 156 separate detectors. The standalone
  177-rod default has 37 strings / 148 detectors. Each amplifier averages its assigned inputs; bypass
  removes a detector from numerator and divisor, failure contributes zero while remaining in the divisor.
  Initialization commissions fixed channel reference gains; later rod/void changes do not recalibrate them.
  Wiring, calibration, bypass and failure survive saves; old saves acquire healthy physical strings.
  `_downscale` below 5% and `_upscale` above 110% remain gameplay defaults for working, unbypassed chambers.
  See REFERENCE-DATA.md for the GE-style assignment basis and the explicit layout approximations.

### Rod selection (rod select matrix and rod motion pushbuttons)

The reactor keeps one **selected rod** (saved with the controller; a world from before the feature loads with none;
a rod renumbered by a drive layout change is deselected). `select_rod` sets it and moves nothing; a value that is not
a rod of the formed core, not a whole number, or any command while the reactor is not formed is refused and changes
nothing. The step commands act on the selected rod only (refused when none is selected), and do not use any path of
their own: they change that rod's one standing demand in the control rod drive network by one notch (two on the 00
to 48 label scale), exactly as `rod_<n>_position` does, so the drive carries it out only while it has power and water.
A drive that cannot move keeps the demand standing, and the display then shows DRIFT for the rod (below).
`selected_rod_insert` and `selected_rod_withdraw` are PULSE commands: a panel button wired to one acts on press.

The continuous commands are held controls: `1` starts stepping the selected rod, `0` stops. While held, the rod is stepped
one more notch each time it has reached its demand, which is the normal rod speed; a drive that cannot move leads its
demand by one notch at most. Both held at once cancel. Selecting another rod, a scram, or the reactor unforming releases
a held control, and a reload never resumes one. Wire them in *follow* mode so release sends `0` (the linker
suggests that for these two commands); a panel push button also lets go by itself 1.5 s after the last press repeat.
CC (`bwr_reactor`): `selectRod(n)`, `getSelectedRod()`, `stepSelectedRodIn()`, `stepSelectedRodOut()`,
`setSelectedRodInsertHeld(bool)`, `setSelectedRodWithdrawHeld(bool)`.

The mod adds no rod block, no sequence control and no worth minimiser: RWM, RSCS and RBM are protection logic, and
building them from these signals is the player's job.

## Main steam isolation valve

Block entity `MainSteamIsolationValveBlockEntity`; CC peripheral `bwr_msiv`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `demand_open` | Demanded open |  | 0 to 1 | BOOLEAN |
| `position` | Valve position | fraction open | 0 to 1 | NUMBER |
| `powered` | Actuator powered |  | 0 to 1 | BOOLEAN |
| `energy_fe` | Actuator energy | FE | 0 to 20000 | NUMBER |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `open` | Open / close | BOOLEAN | 0 to 1 |

## Safety relief valve

Block entity `SafetyReliefValveBlockEntity`; CC peripheral `bwr_safety_relief_valve`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `open` | Open |  | 0 to 1 | BOOLEAN |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |
| `discharge_submerged` | Discharge submerged |  | 0 to 1 | BOOLEAN |
| `flow` | Relief flow | kg/s | 0 to 42 | NUMBER |
| `division` | ADS division | division | 0 to 4 | LEVEL |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `open` | Open / close | BOOLEAN | 0 to 1 |
| `division` | ADS division | LEVEL | 0 to 4 |

## Turbine stop, control and bypass valves

Block entity `TurbineValveBlockEntity`; CC peripheral `bwr_steam_stop_valve / bwr_turbine_control_valve / bwr_bypass_steam_valve`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `target` | Demanded opening | fraction open | 0 to 1 | NUMBER |
| `position` | Valve position | fraction open | 0 to 1 | NUMBER |
| `flow` | Steam flow | kg/s | 0 to 2500 | NUMBER |
| `stop_valve` | Is a stop valve |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `position` | Opening demand | NUMBER | 0 to 1 |

## Turbine steam outlet

Block entity `TurbineSteamOutletBlockEntity`; CC peripheral `bwr_turbine_steam_outlet`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `attached` | Attached |  | 0 to 1 | BOOLEAN |
| `commanded_flow` | Commanded flow | kg/s | 0 to 1940 | NUMBER |
| `actual_flow` | Delivered flow | kg/s | 0 to 1940 | NUMBER |
| `steam_production` | Steam production | kg/s | 0 to 1940 | NUMBER |
| `buffer_fill` | Steam buffer fill | fraction | 0 to 1 | NUMBER |
| `buffered_mb` | Buffered steam | mB | 0 to 1e9 | NUMBER |
| `nozzle_fed` | Nozzle fed |  | 0 to 1 | BOOLEAN |
| `feed_nozzles` | Feed nozzle count | nozzles | 0 to 64 | NUMBER |
| `nozzle_flow` | Nozzle flow | kg/s | 0 to 1940 | NUMBER |
| `nozzle_supply` | Nozzle supply | kg/s | 0 to 1940 | NUMBER |
| `isolation_valve_open` | Isolation valve open fraction | fraction open | 0 to 1 | NUMBER |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `flow` | Steam flow demand | NUMBER | 0 to 1940 |
| `flow_fraction` | Steam flow demand (fraction of rated) | NUMBER | 0 to 1 |

## RPV steam outlet

Block entity `RpvSteamOutletBlockEntity`; CC peripheral `bwr_rpv_steam_outlet`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `position` | Nozzle position | fraction open | 0 to 1 | NUMBER |
| `flow` | Steam flow | kg/s | 0 to 485 | NUMBER |
| `steam_line_attached` | Steam line attached |  | 0 to 1 | BOOLEAN |
| `part_of_formed_reactor` | Part of formed reactor |  | 0 to 1 | BOOLEAN |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |

No commands (read only).

## ECCS pump (LPCS, HPCS, RHR, SLC, RCIC, HPCI)

Block entity `EccsPumpBlockEntity`; CC peripheral `bwr_ plus the design id`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `running` | Running |  | 0 to 1 | BOOLEAN |
| `speed` | Speed | fraction | 0 to 1 | NUMBER |
| `speed_demand` | Speed demand | fraction | 0 to 1 | NUMBER |
| `flow` | Delivered flow | kg/s | 0 to 1500 | NUMBER |
| `demanded_flow` | Demanded flow | kg/s | 0 to 1500 | NUMBER |
| `available_flow` | Available flow | kg/s | 0 to 1500 | NUMBER |
| `rated_flow` | Rated flow | kg/s | 0 to 1500 | NUMBER |
| `differential_pressure` | Differential pressure | psi | 0 to 1500 | NUMBER |
| `developed_head` | Developed head | psi | 0 to 1500 | NUMBER |
| `above_shutoff_head` | Above shutoff head |  | 0 to 1 | BOOLEAN |
| `drive_limited` | Drive limited |  | 0 to 1 | BOOLEAN |
| `suction_temperature` | Suction temperature | degC | 0 to 300 | NUMBER |
| `suction_shortfall` | Suction shortfall | kg/s | 0 to 1500 | NUMBER |
| `steam_consumption` | Drive steam consumption | kg/s | 0 to 100 | NUMBER |
| `energy_fe` | Stored energy | FE | 0 to 1e6 | NUMBER |
| `energy_capacity_fe` | Energy capacity | FE | 0 to 1e6 | NUMBER |
| `boron_rate` | Boron injection rate | ppm/min | 0 to 20 | NUMBER |
| `pool_cooling_mode` | Lined up for pool cooling |  | 0 to 1 | BOOLEAN |
| `shutdown_cooling_mode` | Lined up for shutdown cooling |  | 0 to 1 | BOOLEAN |
| `mode` | Lineup mode |  | text | TEXT |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `run` | Start / stop | BOOLEAN | 0 to 1 |
| `speed` | Speed demand | NUMBER | 0 to 1 |
| `flow_demand` | Flow demand | NUMBER | 0 to 1 |
| `pool_cooling` | Pool cooling lineup | BOOLEAN | 0 to 1 |
| `shutdown_cooling` | Shutdown cooling lineup | BOOLEAN | 0 to 1 |

## Feedwater pump (motor and turbine driven)

Block entity `FeedwaterPumpBlockEntity`; CC peripheral `bwr_ plus the design id`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `running` | Running |  | 0 to 1 | BOOLEAN |
| `speed` | Speed | fraction | 0 to 1 | NUMBER |
| `speed_demand` | Speed demand | fraction | 0 to 1 | NUMBER |
| `achievable_speed` | Achievable speed | fraction | 0 to 1 | NUMBER |
| `flow_demand` | Flow demand | fraction | 0 to 1 | NUMBER |
| `flow` | Delivered flow | kg/s | 0 to 1500 | NUMBER |
| `rated_flow` | Rated flow | kg/s | 0 to 1500 | NUMBER |
| `differential_pressure` | Differential pressure | psi | 0 to 1500 | NUMBER |
| `developed_head` | Developed head | psi | 0 to 1500 | NUMBER |
| `above_shutoff_head` | Above shutoff head |  | 0 to 1 | BOOLEAN |
| `suction_shortfall` | Suction shortfall | kg/s | 0 to 1500 | NUMBER |
| `suction_buffer` | Suction buffer | kg | 0 to 1e5 | NUMBER |
| `drive_steam` | Drive steam | kg/s | 0 to 100 | NUMBER |
| `power_demand` | Electrical demand | W | 0 to 1e7 | NUMBER |
| `energy_fe` | Stored energy | FE | 0 to 1e6 | NUMBER |
| `energy_capacity_fe` | Energy capacity | FE | 0 to 1e6 | NUMBER |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |
| `attached` | Attached |  | 0 to 1 | BOOLEAN |
| `turbine_driven` | Turbine driven |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `run` | Start / stop | BOOLEAN | 0 to 1 |
| `speed` | Speed demand | NUMBER | 0 to 1 |
| `flow_demand` | Flow demand | NUMBER | 0 to 1 |

## Recirculation pump

Block entity `RecirculationPumpBlockEntity`; CC peripheral `bwr_recirculation_pump`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `speed_demand` | Speed demand | fraction | 0 to 1 | NUMBER |
| `speed` | Actual speed | fraction | 0 to 1 | NUMBER |
| `achievable_speed` | Achievable speed | fraction | 0 to 1 | NUMBER |
| `power_limited` | Power limited |  | 0 to 1 | BOOLEAN |
| `energy_fe` | Stored energy | FE | 0 to 1e6 | NUMBER |
| `energy_capacity_fe` | Energy capacity | FE | 0 to 1e6 | NUMBER |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |
| `attached` | Attached to reactor |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `speed` | Speed demand | NUMBER | 0 to 1 |

## Suppression pool

Block entity `SuppressionPoolBlockEntity`; CC peripheral `bwr_suppression_pool`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `formed` | Pool formed |  | 0 to 1 | BOOLEAN |
| `temperature` | Pool temperature | degC | 0 to 200 | NUMBER |
| `saturation_temperature` | Saturation temperature | degC | 0 to 200 | NUMBER |
| `subcooling` | Subcooling | degC | 0 to 100 | NUMBER |
| `condensation_effectiveness` | Condensation effectiveness | fraction | 0 to 1 | NUMBER |
| `uncondensed_steam` | Uncondensed steam | kg/s | 0 to 2500 | NUMBER |
| `boiling` | Boiling |  | 0 to 1 | BOOLEAN |
| `remaining_heat_capacity` | Remaining heat capacity | MJ | 0 to 1e6 | NUMBER |
| `mass` | Pool mass | kg | 0 to 1e7 | NUMBER |
| `level` | Pool level | fraction | 0 to 1 | NUMBER |
| `rhr_duty` | RHR duty demand | fraction | 0 to 1 | NUMBER |
| `rhr_duty_effective` | RHR duty effective | fraction | 0 to 1 | NUMBER |
| `discharging_valves` | Discharging relief valves | valves | 0 to 64 | NUMBER |
| `steam_in` | Steam inflow | kg/s | 0 to 2500 | NUMBER |
| `passive_cooling` | Passive cooling | MW | 0 to 100 | NUMBER |
| `spray_mode` | Spray mode |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `rhr_duty` | RHR duty demand | NUMBER | 0 to 1 |
| `drains_open` | Drain valves open | BOOLEAN | 0 to 1 |

## RHR heat exchanger

Block entity `RhrHeatExchangerBlockEntity`; CC peripheral `bwr_rhr_heat_exchanger`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `pool_connected` | Pool connected |  | 0 to 1 | BOOLEAN |
| `cold_water` | Cold side water | kg | 0 to 1e6 | NUMBER |
| `hot_water` | Hot side water | kg | 0 to 1e6 | NUMBER |
| `heat_rejected` | Heat rejected | MW | 0 to 500 | NUMBER |
| `hot_water_temp` | Hot side temperature | degC | 0 to 200 | NUMBER |
| `cold_water_temp` | Cold side temperature | degC | 0 to 200 | NUMBER |
| `primary_flow` | Primary flow | kg/s | 0 to 2000 | NUMBER |
| `secondary_flow` | Secondary flow | kg/s | 0 to 2000 | NUMBER |
| `primary_outlet_temp` | Primary outlet temperature | degC | 0 to 200 | NUMBER |

No commands (read only).

## Condenser

Block entity `CondenserBlockEntity`; CC peripheral `bwr_condenser`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `ready` | Ready |  | 0 to 1 | BOOLEAN |
| `cold_water` | Cold water | kg | 0 to 1000000 | NUMBER |
| `hot_water` | Hot water | kg | 0 to 1000000 | NUMBER |
| `condensate` | Hotwell condensate | kg | 0 to 200000 | NUMBER |
| `steam` | Steam in condenser | kg | 0 to 1e6 | NUMBER |
| `hotwell_temp` | Hotwell temperature | degC | 0 to 150 | NUMBER |
| `makeup_space` | Hotwell makeup space | kg | 0 to 200000 | NUMBER |
| `steam_rate` | Steam inflow | kg/s | 0 to 2500 | NUMBER |
| `cooling_rate` | Cooling water flow | kg/s | 0 to 20000 | NUMBER |
| `heat_rejected` | Heat rejected | MW | 0 to 4000 | NUMBER |
| `cold_water_temp` | Cold water temperature | degC | 0 to 100 | NUMBER |
| `hot_water_temp` | Hot water temperature | degC | 0 to 100 | NUMBER |
| `backpressure` | Backpressure | psia | 0 to 30 | NUMBER |
| `vacuum` | Vacuum | inHg | 0 to 30 | NUMBER |

No commands (read only).

## Condensate storage tank

Block entity `CondensateStorageTankBlockEntity`; CC peripheral `bwr_condensate_tank`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `stored` | Stored water | kg | 0 to 1e7 | NUMBER |
| `capacity` | Capacity | kg | 0 to 1e7 | NUMBER |
| `level` | Level | fraction | 0 to 1 | NUMBER |
| `temperature` | Temperature | degC | 0 to 150 | NUMBER |
| `ready` | Ready |  | 0 to 1 | BOOLEAN |

No commands (read only).

## Cooling unit

Block entity `CoolingBlockEntity`; CC peripheral `bwr_ plus the design id`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `ready` | Ready |  | 0 to 1 | BOOLEAN |
| `speed_demand` | Speed demand | fraction | 0 to 1 | NUMBER |
| `speed` | Actual speed | fraction | 0 to 1 | NUMBER |
| `input` | Inlet water | kg | 0 to 1e6 | NUMBER |
| `output` | Outlet water | kg | 0 to 1e6 | NUMBER |
| `flow` | Water flow | kg/s | 0 to 20000 | NUMBER |
| `rated_flow` | Rated flow | kg/s | 0 to 20000 | NUMBER |
| `makeup` | Makeup rate | kg/s | 0 to 1000 | NUMBER |
| `heat_rejected` | Heat rejected | MW | 0 to 4000 | NUMBER |
| `energy_fe` | Stored energy | FE | 0 to 1e7 | NUMBER |
| `draw_fe_per_tick` | Electrical draw | FE/t | 0 to 1e5 | NUMBER |
| `inlet_temp` | Inlet temperature | degC | 0 to 100 | NUMBER |
| `outlet_temp` | Outlet temperature | degC | 0 to 100 | NUMBER |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `speed` | Speed demand | NUMBER | 0 to 1 |

## Power module (HP turbine, LP turbine, generator)

Block entity `PowerModuleBlockEntity`; CC peripheral `bwr_hp_turbine / bwr_lp_turbine / bwr_generator`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `running` | Running |  | 0 to 1 | BOOLEAN |
| `rpm` | Shaft speed | rpm | 0 to 4000 | NUMBER |
| `speed_percent` | Rated shaft speed | % | 0 to 300 | NUMBER |
| `overspeed` | Shaft overspeed | | 0 to 1 | BOOLEAN |
| `kinetic_energy_mj` | Module rotor energy | MJ | 0 to 1000000 | NUMBER |
| `shaft_loss_mw` | Train mechanical losses | MW | 0 to 100000 | NUMBER |
| `damage` | Shaft damage | fraction | 0 to 1 | NUMBER |
| `failed` | Generator failed | | 0 to 1 | BOOLEAN |
| `flow` | Steam flow | kg/s | 0 to 2500 | NUMBER |
| `shaft_mw` | Shaft power | MW | 0 to 1500 | NUMBER |
| `electric_mw` | Electrical output | MW | 0 to 1500 | NUMBER |
| `train_mw` | Train power | MW | 0 to 1500 | NUMBER |
| `inlet_pressure` | Inlet pressure | psia | 0 to 1200 | NUMBER |
| `outlet_pressure` | Outlet pressure | psia | 0 to 1200 | NUMBER |
| `inlet_temp` | Inlet temperature | degC | 0 to 350 | NUMBER |
| `outlet_temp` | Outlet temperature | degC | 0 to 350 | NUMBER |
| `water` | Water stored | kg | 0 to 20000 | NUMBER |
| `stored_fe` | Stored energy | FE | 0 to 2000000000 | NUMBER |
| `delivered_mw_now` | Delivered last completed tick | MW | nonnegative | NUMBER |
| `delivered_mw_1s` | Delivered over 20 ticks | MW | nonnegative | NUMBER |
| `delivered_mw_1min` | Delivered over 1200 ticks | MW | nonnegative | NUMBER |
| `delivered_mwh` | Lifetime delivered energy | MWh | nonnegative | NUMBER |
| `generated_mw_1s` | Generated over 20 ticks | MW | nonnegative | NUMBER |
| `generated_fe_tick` | Generated last completed tick | FE | nonnegative | NUMBER |
| `delivered_fe_tick` | Delivered last completed tick | FE | nonnegative | NUMBER |
| `buffer_delta_fe_tick` | Storage change last completed tick | FE | signed | NUMBER |
| `sample_ticks` | Completed samples since load | ticks | 0 to 1200 | NUMBER |
| `window_ready_1s` | One-second history ready | | 0 to 1 | BOOLEAN |
| `window_ready_1min` | One-minute history ready | | 0 to 1 | BOOLEAN |
| `counter_saturated` | Lifetime counter saturated | | 0 to 1 | BOOLEAN |
| `status` | Status |  | text | TEXT |

No commands (read only).

Shaft additions in `getStatus()` are `speedPercent`, `overspeed`,
`kineticEnergyMJ`, `shaftLossMW`, `damage`, and `failed`. Kinetic energy belongs
to this module; sum modules once for total rotor energy. Loss power is the shared
train's last-step mechanical/coupling loss, so do not sum it across modules.
Overspeed above 110% accumulates damage; neither signal operates a valve or trips
a breaker. At damage 1 a generator produces no new FE; stored FE remains usable.
The calibration is documented in `GRID-TIE-SPEC.md` §4.3.

Delivery counts actual terminal extraction, including generator push, exactly once.
Simulation and rejected transfers count zero. These rates use completed ticks,
include idle zeros, and restart at load; lifetime delivery and damage survive normal
item drops. A new placement has a new world UUID. Cached capabilities reconnect to
the same saved machine but cannot extract from its replacement. Generated energy
minus delivered energy equals buffer change, including fractional FE precision.
Buffer discharge can exceed current generation; delivered MW is not rating-capped.

## Grid tie

Peripheral `bwr_grid_tie`. All rates use completed ticks.

| Signal | CC status key | Units / kind |
| --- | --- | --- |
| `demand_mode` | `demandMode` | NUMBER |
| `fixed_mw` | `fixedMW` | MW |
| `remote_mw` | `remoteMW` | MW |
| `demand_mw` | `demandMW` | MW |
| `demand_mw_1s` | `demandMW1s` | MW |
| `curve_min_mw` | `curveMinMW` | MW |
| `curve_max_mw` | `curveMaxMW` | MW |
| `curve_period_ticks` | `curvePeriodTicks` | ticks |
| `curve_phase_ticks` | `curvePhaseTicks` | ticks |
| `maximum_mw` | `maximumMW` | MW |
| `delivered_mw_now` | `deliveredMWNow` | MW |
| `delivered_mw_1s` | `deliveredMW1s` | MW |
| `delivered_mw_1min` | `deliveredMW1min` | MW |
| `delivered_mwh` | `deliveredMWh` | MWh |
| `grid_shortfall_mw` | `gridShortfallMW` | MW |
| `observed_base_mw` | `observedBaseMW` | MW |
| `requested_total_mw` | `requestedTotalMW` | MW |
| `mw_error` | `mwError` | MW |
| `aggregate_valid` | `aggregateValid` | BOOLEAN |
| `selected_meter_count` | `selectedMeterCount` | NUMBER |
| `valid_meter_count` | `validMeterCount` | NUMBER |
| `breaker_closed` | `breakerClosed` | BOOLEAN |
| `export_enabled` | `exportEnabled` | BOOLEAN |
| `binding_valid` | `bindingValid` | BOOLEAN |
| `bound_generator` | `boundGenerator` | TEXT |
| `rpm` | `rpm` | rpm |
| `shaft_frequency_hz` | `shaftFrequencyHz` | Hz |
| `slip_hz` | `slipHz` | Hz |
| `phase_degrees` | `phaseDegrees` | degrees |
| `sync_valid` | `syncValid` | BOOLEAN |
| `within_sync_window` | `withinSyncWindow` | BOOLEAN |
| `last_close_angle_degrees` | `lastCloseAngleDegrees` | degrees |
| `last_close_slip_hz` | `lastCloseSlipHz` | Hz |
| `last_close_fault` | `lastCloseFault` | BOOLEAN |
| `status` | `status` | TEXT |
| `sample_ticks` | `sampleTicks` | ticks |
| `window_ready_1s` | `windowReady1s` | BOOLEAN |
| `window_ready_1min` | `windowReady1min` | BOOLEAN |
| `counter_saturated` | `counterSaturated` | BOOLEAN |

## FE meter

Peripheral `bwr_fe_meter`. All rates use completed ticks.

| Signal | CC status key | Units / kind |
| --- | --- | --- |
| `mw_now` | `mwNow` | MW |
| `mw_1s` | `mw1s` | MW |
| `mw_1min` | `mw1min` | MW |
| `fe_per_tick_now` | `fePerTickNow` | FE/t |
| `fe_per_tick_1s` | `fePerTick1s` | FE/t |
| `fe_per_tick_1min` | `fePerTick1min` | FE/t |
| `mwh` | `mwh` | MWh |
| `label` | `label` | TEXT |
| `display_units` | `displayUnits` | TEXT |
| `direction` | `direction` | TEXT |
| `sample_ticks` | `sampleTicks` | ticks |
| `window_ready_1s` | `windowReady1s` | BOOLEAN |
| `window_ready_1min` | `windowReady1min` | BOOLEAN |
| `counter_saturated` | `counterSaturated` | BOOLEAN |

Tie numeric commands: `demand_mode` (0 fixed, 1 daily, 2 remote), `fixed_mw`,
`remote_mw`, `curve_min_mw`, `curve_max_mw`, `curve_period_ticks` (20–1728000).
Pulse commands: `reset_curve`, `close_breaker`, `trip_breaker`. Finite pulse values
0–1 are accepted; values >=0.5 issue the action. Close is never gated on sync angle
or slip. Meter commands: `direction` (0 down, 1 up, 2 north, 3 south, 4 west,
5 east) and `display_units` (0 MW, 1 FE/t). Invalid values reject atomically.

CC methods: tie `getStatus`, `setDemandMode`, `setFixedDemandMW`,
`setRemoteDemandMW`, `setDailyCurve(min,max,periodTicks)`, `resetCurve`,
`closeBreaker`, `tripBreaker`, `bindGenerator(x,y,z)`, `clearGenerator`,
`setMeters({{x=...,y=...,z=...},...})`, `getMeters`. Meter `getStatus`,
`setLabel` (<=64 characters), `setDisplayUnits("MW"|"FE/t")`,
`setDirection("north"|"south"|"east"|"west"|"up"|"down")`.

Selected readings have keys `meter.<uuid>.label`, `.valid`, `.mw_1s`, `.mwh`.
A warming, unloaded or replaced endpoint makes numeric aggregates unavailable,
not zero. `aggregate_valid` requires the generator, tie and all selected meters to
have 20 completed ticks ending at the same game tick. Empty selection explicitly
means zero observed base load. Select disjoint base feeders only; do not select
an export meter or serial copies. MW error is observed base plus requested export
minus generated power; it does not know unmet demand inside foreign machines.

Linker gestures (also work without CC): sneak-right-click the generator, then
right-click the open tie to bind it; sneak-right-click a meter, then right-click
the tie to toggle its selection. Selection uses the existing configured panel
link range, same-dimension loaded endpoints and world-edit permissions. Normal
panel linking is unchanged. See [grid wiring](docs/grid-wiring.md) for a setup.

## Containment controller

Block entity `ContainmentControllerBlockEntity`; CC peripheral `bwr_containment`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `sealed` | Room sealed |  | 0 to 1 | BOOLEAN |
| `free_volume` | Free volume | m3 | 0 to 1e6 | NUMBER |
| `failure_pressure` | Failure pressure | psig | 0 to 300 | NUMBER |
| `isolated` | Steam and water isolated |  | 0 to 1 | BOOLEAN |
| `openings` | Openings | openings | 0 to 256 | NUMBER |
| `bypass_flow` | Bypass flow | fraction | 0 to 1 | NUMBER |
| `h2_percent` | Hydrogen | % | 0 to 100 | NUMBER |
| `o2_percent` | Oxygen | % | 0 to 25 | NUMBER |
| `pressure` | Containment pressure | psig | -5 to 150 | NUMBER |
| `steam_percent` | Steam | % | 0 to 100 | NUMBER |
| `gas_temperature` | Gas temperature | degC | 0 to 400 | NUMBER |
| `drywell_pressure` | Drywell pressure | psig | -5 to 150 | NUMBER |
| `wetwell_pressure` | Wetwell pressure | psig | -5 to 150 | NUMBER |
| `pressure_nodes` | Pressure nodes | nodes | 0 to 2 | NUMBER |
| `spray_flow` | Containment spray | kg/s | 0 to 2500 | NUMBER |

Drywell and wetwell pressures are separate. Downcomers carry drywell steam
through the suppression pool and noncondensables into wetwell airspace; passive
vacuum breakers carry gas back when wetwell pressure exceeds drywell pressure.

No commands (read only).

## Containment spray header

Block entity `ContainmentSprayHeaderBlockEntity`; CC peripheral `bwr_containment_spray_header`.
The CC `getStatus()` table reports `waterKg`, `waterTemperatureC`, `flowKgPerS`, and `capacityKg`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `water` | Spray header water | kg | 0 to 1000 | NUMBER |
| `water_temperature` | Spray water temperature | degC | 0 to 300 | NUMBER |
| `flow` | Spray flow | kg/s | 0 to 500 | NUMBER |

The header meters its inventory once per containment tick. Its inlet accepts water from a pump line. No commands (read only).

## Containment penetration

Block entity `ContainmentPenetrationBlockEntity`; CC peripheral `bwr_containment_penetration`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `position` | Isolation valve position | fraction open | 0 to 1 | NUMBER |
| `isolated` | Isolated |  | 0 to 1 | BOOLEAN |
| `demand_open` | Demanded open |  | 0 to 1 | BOOLEAN |
| `ruptured` | Ruptured |  | 0 to 1 | BOOLEAN |
| `energy_fe` | Stored energy | FE | 0 to 1e5 | NUMBER |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |
| `service` | Service |  | text | TEXT |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `open` | Open / close | BOOLEAN | 0 to 1 |

## Containment vault door

Block entity `ContainmentVaultDoorBlockEntity`; CC peripheral `bwr_containment_vault_door`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `open_fraction` | Door opening | fraction open | 0 to 1 | NUMBER |
| `sealed` | Sealed |  | 0 to 1 | BOOLEAN |
| `energy_fe` | Stored energy | FE | 0 to 1e6 | NUMBER |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |
| `state` | State |  | text | TEXT |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `open` | Open / close | BOOLEAN | 0 to 1 |

## Containment airlock

Block entity `ContainmentAirlockBlockEntity`; CC peripheral `bwr_containment_airlock`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `inner_open` | Inner door open |  | 0 to 1 | BOOLEAN |
| `outer_open` | Outer door open |  | 0 to 1 | BOOLEAN |
| `forced_override` | Interlock override |  | 0 to 1 | BOOLEAN |
| `sealed` | Sealed |  | 0 to 1 | BOOLEAN |
| `damaged` | Damaged |  | 0 to 1 | BOOLEAN |
| `computer_controlled` | Computer controlled |  | 0 to 1 | BOOLEAN |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `inner` | Inner door open / close | BOOLEAN | 0 to 1 |
| `outer` | Outer door open / close | BOOLEAN | 0 to 1 |
| `override` | Interlock override | BOOLEAN | 0 to 1 |

## Containment vent and nitrogen equipment

Block entity `ContainmentEquipmentBlockEntity`; CC peripheral `bwr_containment_vent / bwr_containment_nitrogen`.

| Signal key | Label | Unit | Range | Kind |
| --- | --- | --- | --- | --- |
| `demand` | Demanded active |  | 0 to 1 | BOOLEAN |
| `supplied` | Powered and supplied |  | 0 to 1 | BOOLEAN |
| `energy_fe` | Stored energy | FE | 0 to 1e6 | NUMBER |
| `nitrogen_ticks` | Nitrogen supply | ticks | 0 to 1e6 | NUMBER |

| Command key | Label | Kind | Range |
| --- | --- | --- | --- |
| `active` | Start / stop | BOOLEAN | 0 to 1 |


### Startup detector locations

`srm_<1-4>_lattice_x`, `_lattice_y`, `_lprm_string` and
`irm_<1-8>_lattice_x`, `_lattice_y`, `_lprm_string` expose typical GE coordinates and the adjacent
LPRM string number. CC `getDetectorLocations()` also includes the fuel-lattice position and marks
placement as `typical`. Browns Ferry FCD strips carry the matching SRM-A through D / IRM-A through H
legends above them. These are representative placements, not an as-built plant map.
