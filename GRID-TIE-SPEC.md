# Grid tie and load following — Phase M implementation spec

Status: **review R1–R5 applied; implementation pending.** Baseline:
`2ef9943` on `containment-structure`, after accepted TODO-5 Part A.
Scope follows the updated handoff `ASTRA-TODO-4.txt` Phase M and the owner's
2026-10-14 instruction. The older Downloads copy's diesel and larger commit
budget are superseded by `ASTRA-REVIEW-PHASE-M-SPEC.txt` (2026-10-14).
Plan: this review-update documentation commit plus **seven implementation commits**.
The owner authorized implementation without another routine spec stop. Stop early
only for a real scope conflict. The owner authorized both acceptance-contract
updates in §9.1 on 2026-10-14 ("update them").

Electricity remains FE. Add a controllable external demand sink, delivered-load
measurements, a directional inline meter and manual grid synchronization. No
diesel generator, buses, transformers, voltage, reactive power, excitation,
ESF/DC system, per-load breakers or changes to existing pump power inputs.
No new screens or guidebook; new block art is explicitly placeholder art.

Physics and arithmetic belong in `core/`, without Minecraft imports. New devices
use a shared loaded-device service, not block tickers. Production never loads
chunks. Commands are player actions, including player-authored CC/panel logic:
no automatic closing, trips, starts, load shedding, sync permissive or controller.
Existing tests and protection-policy checks retain their assertions.

## 0. Current behavior and review decisions

At the baseline:

| Code | Existing behavior to preserve |
| --- | --- |
| `power/PowerTrain.java` in mod | Finds a loaded coaxial train, at most 32 modules; executes each module once per game tick. Replace buffer-room-gated expansion and the 8-second RPM relaxation with the shaft energy balance in §4.3. Preserve once-per-tick execution and loaded-only discovery. |
| `power/PowerModuleBlockEntity.java` | Each generator stores up to 2,000,000,000 FE. Its terminal supports external extraction and pushes to a neighbor. `electricMW` is production, not delivered power. The common `extractEnergy` boundary is the accounting point. |
| `core/turbine/PowerTurbine.java` | Existing electrical rating 1500 MW and efficiency 0.985; no rotor inertia, electrical torque or synchronization dynamics. |
| `eccs/EccsPower.java` in mod | Existing conversion is 13.4 W per FE/t. Do not change the FE economy. |
| `PowerModulePeripheral`, `signal/adapters/PlantSignals` | Existing RPM, steam, generated MW and FE inventory readouts; no delivered-energy ledger or breaker. |
| `core/poison/Xenon`, `TransientHarness` | Existing iodine/xenon evolution and an operator harness; no new poison model or production load-follow controller is needed. |

Paths in this table are relative to `mod/src/main/java/dev/bwr/mod/` or
`core/src/main/java/dev/bwr/` as indicated. These existing numerical values are
code baselines, not newly asserted manufacturer ratings. References and the
distinction between physical data and game choices are in §10 and
`REFERENCE-DATA.md` §13.

Scope interpretations retained after review:

1. **The proposed breaker isolates the grid-export branch.** It lives in the grid
   tie; it does not disconnect the generator's existing FE terminal or base
   feeders. This is a scoped interpretation of TODO-4's “generator output
   breaker.” Shaft dynamics (formerly TODO-5 B6) now belong to Phase M. A player
   can roll and hold near rated speed with the tie open and a throttled turbine
   control valve; commissioning does not require an auxiliary/base load.
   It therefore does **not** claim that opening the export breaker causes station
   blackout. Global generator isolation is not silently added here.
2. **Base-first allocation is a wiring requirement.** A sink cannot enforce
   priority across arbitrary FE cables. Use a cable's explicit base-first
   priorities/export limiter or a dedicated controlled export branch. A parallel
   grid sink can otherwise compete with base loads. “Takes what's left” describes
   that player-built topology, not an allocation guarantee supplied by this block.
3. **Metered flow is served load.** The proposed MW error combines explicitly
   selected, disjoint base-feeder measurements with requested grid demand. It
   cannot include a foreign machine's unknown unmet demand or discover cable
   topology. An inline meter has zero storage and a stated compatibility boundary
   (§3); universal foreign-cable compatibility is not promised.

These are review-visible limitations, not permission to enlarge Phase M. If a
global breaker or guaranteed arbitrary-network priority is required, revise this
spec before implementation and reassess the commit budget.

## 1. Common time, units and accounting

Use simulation time: 20 loaded game ticks per second, independent of wall-clock
lag. A one-second average is 20 ticks; a one-minute average is 1200 ticks. No
offline catch-up, no extra energy on `/time set`, no advancement of energy or
curve clocks by a telemetry query.

With the existing FE conversion, the following are derived, not new constants:

```text
MW = (FE transferred during N completed ticks / N) * 13.4 / 1,000,000
1 FE = 13.4 / 20 joules = 0.67 joule
delivered MWh = lifetime delivered FE * 0.67 / 3,600,000,000
1500 MW = 111,940,298.507... FE/t
2,000,000,000 FE = 0.372222... MWh
```

The ledger counts only actual, positive, non-simulated transfers, once. Generation,
offers, simulated acceptance, rejected offers and buffer charging are not delivered
energy. Instrument generator extraction at its common terminal boundary, covering
both push and external pull without counting the push twice. Preserve the existing
energy-delivered advancement. Harden the push reservation against a receiver
reentering extraction before the outer transfer commits; return only genuinely
transferred FE. Broken third-party capabilities that lie about acceptance cannot
be made transactional by this API.

Per-call FE amounts remain nonnegative `int`, as required by [M-FE]. Per-tick
budgets, sums and rolling buckets use `long`; do not narrow a multi-call or
multi-generator sum to `int`. Validate before multiplication/narrowing. Lifetime
whole-FE counters saturate at `Long.MAX_VALUE`, expose `counter_saturated`, and
never wrap; saturation affects reporting only, not power transfer.

`*_mw_now` means the **last completed tick**, not a partially accumulated current
tick. Publish completed windows consistently for generator, tie and meters.
Fixed-denominator averages include idle zeros. Load/reload starts empty rate
history with `sample_ticks = 0`, rising to 1200; `window_ready_1s` and
`window_ready_1min` identify warm-up. Lifetime totals survive. Unloaded devices
are unavailable, not zeros masquerading as current measurements.

Record generated FE, extracted FE and storage delta separately per tick at each
generator. Retain `electric_mw` / `electricMW` as production and add a matching
`generated_mw_1s`. The accounting invariant is generated FE minus delivered FE
equals storage change, subject only to existing fractional-FE precision. Storage
discharge can make delivered MW exceed current production; never cap that readout
at the generator's nameplate rating.

## 2. Grid tie: external FE demand

Add one single-block `bwr:grid_tie`, peripheral `bwr_grid_tie`. It accepts energy
through FE input faces and permanently consumes it as off-site delivery. It has
no FE inventory or output. All faces share the same per-tick allowance. Place it
at an export feeder's end; direct generator adjacency is supported too.

### 2.1 Demand modes

| Mode | Behavior |
| --- | --- |
| `fixed` (0) | Consume up to the player's stored fixed MW setpoint. |
| `daily` (1) | Repeat the configured minimum/maximum curve over loaded simulation ticks. |
| `remote` (2) | Consume up to the last MW setpoint written through CC or a panel signal. A disconnected command source leaves the last value, with no watchdog action. |

Default: fixed, demand zero, breaker open, no generator binding. Proposed server
config `gridTieMaxMW = 1500`, with a configurable range 0–48000 MW. The upper
bound is an engineering bound of 32 existing generator ratings, not a claim about
an actual grid. Each device rejects nonfinite, negative or over-config commands;
lowering server config clamps previously stored demands and reports the effective
value. A zero maximum disables consumption without changing breaker position.

The configurable daily curve is a deterministic game demand profile:

```text
D(t) = minMW + (maxMW - minMW) * (1 - cos(2*pi*elapsedTicks/periodTicks)) / 2
```

Default period is 24000 loaded ticks (one nominal Minecraft day), minimum 1050 MW,
maximum 1500 MW, each capped by server config. Valid period: 20–1,728,000 ticks;
min must be <= max. These durations and defaults are game choices, not a measured
utility load curve. Store phase in loaded ticks; freeze during unload. Changing
mode or curve preserves phase modulo the new period; an explicit player
`reset_curve` command restarts it. Demand settings take effect next tick and
never reset the current tick's already-spent allowance.

For an eligible tick, allocate `floor(D * 1e6 / 13.4 + fractionalCarry)` FE,
retaining the fractional remainder. Advance carry once, never per request.
Unspent whole-FE allowance expires; it cannot accumulate into later bursts.
Open/unavailable ticks allocate zero and do not bank fractional demand. Simulation
computes potential acceptance without changing budget, carry, counters or state.
This must work above `Integer.MAX_VALUE` FE/t using multiple bounded calls.

### 2.2 Binding and live state

The player explicitly binds a tie to one loaded, formed generator controller by
dimension, controller position and persistent device UUID. No cable traversal or
chunk loading. The identity is for RPM, closing damage and accounting; it does
not teleport FE or certify its origin. A shared cable can contain other sources;
document that such a hookup is unsuitable for attributing export to one generator.
Use a dedicated generator/export topology for the synchronizing scenario.

Store the binding **one way, on the tie only**. The generator stores no tie
association. Several ties may bind the same generator; each close is evaluated
independently. A replaced generator's UUID invalidates the association until the
player rebinds. Binding changes and `clearGenerator()` require this tie to be
manually open. They mutate only this tie; the former endpoint need not be loaded.
There is no reciprocal handshake, orphan recovery protocol or generator-side
clear command. An unloaded endpoint is temporarily unavailable; a loaded matching
UUID can become available again without changing the binding.

Intake requires a closed breaker and a live, matching, formed binding. Loss of
binding availability returns zero intake and `binding_valid = false`, without
changing the saved breaker position. On return, the saved closed position resumes
intake; this is connection availability, not an automatic close command. Commands
cannot act on an unloaded remote generator. Metering of a tie's own delivery and
total remains available while that tie is loaded.

## 3. Inline FE meter

Add one single-block `bwr:fe_meter`, peripheral `bwr_fe_meter`, with opposite
input/output faces and a visible direction arrow. Other faces expose no energy
capability. Player options: label (up to 64 characters), direction, display units
`MW` or `FE/t`. Units change presentation only; all telemetry retains explicit
units and lifetime energy stays MWh.

Use a **zero-storage synchronous bridge**, satisfying the no-loss and no-buffer-
beyond-one-tick requirement without a buffer that could strand energy:

- Input `receiveEnergy(n, simulate)` forwards to the output neighbor's receive
  operation and returns exactly its accepted amount.
- Output `extractEnergy(n, simulate)` delegates to the input neighbor's extraction
  operation and returns exactly its extracted amount.
- Real traversal increments this meter's ledger once. Simulation is propagated
  unchanged and records nothing. A rejecting or unloaded destination leaves the
  pushing source unchanged; an unavailable source supplies nothing to a pull.
- Do not run a background pull-then-push job: FE has no general rollback when a
  downstream destination rejects an already-extracted amount.

Expose honest local inventory values: `getEnergyStored()` and
`getMaxEnergyStored()` are both zero; directional `canReceive` / `canExtract`
identify supported operations. This is compatible with callers that use transfer
methods/simulation, including the existing BWR generator push. Some cable mods
require positive storage or capacity before attempting a transfer; those may need
their own transfer interface adjacent to the meter or may be incompatible. Do not
fake stored energy to satisfy them. Test the installed dev cable implementation
and publish its name/version and supported arrangement; failed compatibility is
a reported limit, not a reason to quietly add an unlimited buffer.

Guard every bridge transfer/capability lookup with per-call visited device IDs and
a maximum depth of 64 bridges, cleared in `finally`; simulation obeys the same
guard. Reentrant loops return zero without changing energy or counters. Loaded
neighbor lookup uses the existing live-capability pattern; cached old capabilities
must stop on replacement/unload. No searches beyond actual adjacent connections.

Direction changes are immediate between calls, invalidate affected capabilities
and clear rolling rates; the lifetime total continues as total energy traversed
in each historically selected forward direction. Reversal is not negative energy
and does not subtract from MWh. Labels/direction and meter identities persist.

## 4. Manual export breaker and synchronization

The breaker is part of the tie, with `close_breaker` and `trip_breaker` commands.
Trip means **player-commanded open**. No numeric value causes a trip. Closing
requires a live association for applying its consequence, but never checks angle
or slip as a permissive. Repeating close while already closed is idempotent and
does not repeat damage. Closing occurs at the server command instant; no invented
mechanical delay or prediction of client latency.

### 4.1 Synchronizing indication

Preserve nominal 1500 rpm. Choose a four-pole, 50-Hz reference compatible with
that speed [M-SPEED]. This is the game's reference frequency; FE carries no AC
waveform. While open and bound, integrate electrical phase from the live shaft:

```text
generatorHz = rpm * 4 / 120 = rpm / 30
slipHz = generatorHz - 50
phaseDegrees = wrapToMinus180InclusivePlus180Exclusive(
    phaseDegrees + 360 * slipHz * dtSeconds)
```

Positive slip means generator faster than the reference; positive phase means
generator electrical phase leads grid phase. Initialize an unbound
device's phase at zero; preserve valid open phase through saves/unloads without
offline evolution. `sync_valid` is true only while open with a live RPM source.
While closed, slip/phase synchronizing samples are unavailable; status says
`closed`, and the separate raw `shaft_frequency_hz` and `rpm` remain readable.
This avoids displaying a fictional free-running synchroscope after connection.
Retain last closing angle/slip for diagnosis. Reopening starts phase at zero as
an explicit game approximation of separation from a synchronized grid; do not
alter actual shaft RPM. Do not claim electromechanical locking is modeled.

The source-backed reference window is **|angle| <= 10 electrical degrees and
|slip| <= 0.067 Hz** [M-SYNC]. These are reference synchronizing limits, not a
universal damage-onset curve. They correspond, by derivation, to a 2.01-rpm
speed difference at four poles. Omit voltage checking because voltage is out of
scope. A read-only `within_sync_window` signal is allowed; it never gates close.

Commission with the tie open, using the existing non-stop
`TurbineValveBlockEntity` fractional position to roll the shaft and trim its speed.
The player makes the synchroscope creep toward zero, then explicitly closes.
Prove a reachable synchronization sequence through real steam and valve commands,
without writing RPM or phase in the integration test. No hidden dump load,
phase-set command or automatic governor is added. An auxiliary load is optional.

### 4.2 Consequence of a bad close

Proposed deterministic **game calibration**, not a manufacturer damage law:

```text
r = max(abs(closingAngle) / 10, abs(closingSlip) / 0.067)
damageIncrement = r <= 1 ? 0 : min(1, 0.25 * r)
generatorDamage = min(1, previousDamage + damageIncrement)
```

The 0.25 damage coefficient and failure at normalized damage 1 are explicit game
choices. Damage is evaluated once per open-to-closed event using the server's
sample; close succeeds even when damaging. Partial damage is persistent condition
indication; at damage 1, that generator accepts no further shaft work and creates
no new FE. Its existing FE remains extractable, so failure does not destroy stored
energy. Other generators in the train continue according to their own condition.
Neither damage nor failure opens the breaker, operates a valve, starts backup or
resets itself. Replacement is the M repair mechanism; normal drops retain damage
to prevent break/re-place repair. There is no mod command to clear damage.

### 4.3 Shaft dynamics — TODO-5 B6 is part of Phase M

Use a core shaft model with torque balance expressed as rotational energy, so
startup at zero RPM has no division by zero:

```text
omega = rpm * 2*pi/60
E_rot = 0.5 * I * omega^2
E_rot_next - E_rot = W_steam - W_generator - W_loss
W_generator = generated_FE * 0.67 / 0.985 joules
             = (delivered_FE + buffer_delta_FE) * 0.67 / 0.985
rpm_next = sqrt(2 * E_rot_next / I) * 60/(2*pi)
```

These are per-step energies, not rolling-average power readings. Generator
conversion is bounded by its existing rating, buffer room and available shaft
energy. A discharging buffer is not negative shaft torque and must not create
rotation: generated FE remains nonnegative; delivery plus buffer delta equals
that generation. Account separately for conversion losses and mechanical losses.
Surplus steam work becomes rotation; energy already used to charge the FE buffer
must never also accelerate the shaft. No infinite source or unreported energy
sink is allowed. Kinetic energy can supply electrical work during coast-down.

Admitted expansion must be independent of generator buffer room. The existing
steam-path topology, valves, available steam, exhaust space and condenser
backpressure still constrain expansion. Pass a finite physical expansion budget
to the existing core expansion primitive; preserve its explicit budget contract.
An active generator is not required to absorb every joule of admitted work.
The shared train service advances the shaft once per server tick, using the
existing loaded-only train discovery. Inertia and saved speed must not be
multiplied when several modules report the same shaft. Joining/splitting trains
must account for their rotational energy rather than select the largest RPM.

Initial **game calibration**, not a claim about an actual manufacturer's rotor:

- Rated speed stays 1500 rpm and electrical rating stays 1500 MW per generator.
- Inertia constant H = 5 seconds on a 1500 MW base for one nominal train:
  rated rotational energy = 7.5 GJ and I = 2*H*1.5e9/omega_rated^2,
  approximately 607,927 kg m². Allocate that reference inertia across the existing
  nominal 2 HP / 4 LP / 1 generator train: HP 10% each, LP 10% each, generator
  40%. Sum installed module inertias; turbine-only fragments retain their inertia.
  These proportions, H and using a MW base in place of an MVA nameplate are
  explicit game choices. Verify joining/splitting conservation in core tests.
- Windage/friction loss power = 0.005 * baseWatts * (rpm/1500)^2;
  baseWatts is the train's rated rotational energy divided by H. At rated speed
  the reference train loses 7.5 MW. Bound the discrete loss by available energy.
- Overspeed threshold = 110% rated (1650 rpm). Above it, accumulate normalized
  damage at `0.1 * max(0, rpm/1650 - 1)` per simulated second, capped at 1.
  Threshold, coefficient and severity are game calibration, not a trip setting
  or a manufacturer fatigue law. Damage persists and contributes to the same
  generator condition as bad closing. At damage 1 the generator produces no
  further FE. Neither damage nor overspeed moves a valve or opens a breaker.

Use the existing throttling turbine control valve as the player's steam
admission control. Keep fractional position/stroke, CC and panel commands;
add no automatic governor, speed clamp or trip. Phase O/player CC may later
implement a governor. Expose RPM, percent rated speed, overspeed, kinetic energy,
shaft/loss power and damage. Save speed/damage; derive kinetic energy from the
installed inertia. Legacy finite saved RPM becomes the starting physical speed,
including speeds above rated; loading must not silently clamp it to 1500.
The passive generator conversion capacity scales with min(1,(rpm/1500)^2),
explicit game calibration; it is zero at rest and does not regulate speed.
Steam admission uses the existing rated capacities (HP 2200, LP 750 kg/s),
with actual draw limited by valves/supply/backpressure. Remove the old
RPM-squared demand multiplier: it artificially increased admission on overspeed.
For compatibility, an isolated turbine without any attached generator keeps
its steam inventory; its retained rotor energy still coasts down through losses.

Opening at load stops tie intake immediately; other FE loads remain. Internal
buffer headroom (at most 0.8933 seconds at rated production) and external storage
can delay rejection. With steam still admitted, reduced electrical torque then
accelerates the shaft. Demonstrate overspeed on full rejection, and demonstrate
that the player's stop-valve closure removes fresh steam torque; trapped steam
may finish expanding. The breaker remains in its commanded position.

This replaces the old buffer-gating/RPM-decay model. The old fixed-valve
lower-demand/lower-steam acceptance cannot be retained unchanged; see §9.1.

## 5. Load report, commands and panel adapters

### 5.1 Defined MW error

A tie may select at most 32 explicit base-feeder meter UUIDs, by loaded position
and dimension. Deduplicate identities; never discover or sum every placed meter.
Selected meters must be disjoint final feeders from this generator: exclude the
grid feeder, serial/nested copies, other generators and backup-fed branches.
Foreign topology makes overlap/source verification the player's responsibility.

For the same completed 20-tick window:

```text
observedBaseMW = sum(selected meter mw_1s)
requestedTotalMW = observedBaseMW + average(requested grid demand MW)
mwError = requestedTotalMW - bound generator generated_mw_1s
```

Positive error means the displayed requested/observed total exceeds production.
It is not a physical grid-frequency error or a full balance of unmet base demand.
Do not add generator delivered MW to downstream meter totals. The grid term is
requested demand, not delivered export, and remains requested while open; expose
`export_enabled` and `grid_shortfall_mw` to make that visible. A disabled/unbound
tie cannot silently masquerade as a satisfied grid request.
`grid_shortfall_mw = max(0, demand_mw_1s - delivered_mw_1s)` uses that same window.

An empty selected list means an explicitly configured zero observed base load.
A missing, replaced, unloaded or warming-up selected meter/generator makes
`aggregate_valid = false`; derived aggregates/MW error are unavailable, not a
partial sum. Store and expose selected count, valid count and meter labels. A
backup-feeder meter can be displayed independently but must not be counted as
this generator's load when another source is feeding it.

### 5.2 Signal and CC contract

Register adapters through `dev.bwr.mod.signal.adapters.SignalAdapters`. Numeric,
boolean and pulse commands share server-thread `DeviceCommands` implementations
with CC. Missing numeric samples use the existing no-signal semantics; CC returns
validity flags and omits unavailable values. Every new public reading/configuration
has a signal; labels and state explanations use TEXT. Keep all old keys/meanings.

| Device | New signal keys (canonical units) |
| --- | --- |
| Generator | `delivered_mw_now`, `delivered_mw_1s`, `delivered_mw_1min`, `delivered_mwh`, `generated_mw_1s`, `generated_fe_tick`, `delivered_fe_tick`, `buffer_delta_fe_tick`, `damage`, `failed`, `counter_saturated`, `sample_ticks`, `window_ready_1s`, `window_ready_1min`, `speed_percent`, `overspeed`, `kinetic_energy_mj`, `shaft_loss_mw` |
| Tie demand | `demand_mode`, `fixed_mw`, `remote_mw`, `demand_mw`, `demand_mw_1s`, `curve_min_mw`, `curve_max_mw`, `curve_period_ticks`, `curve_phase_ticks`, `maximum_mw` |
| Tie energy/report | `delivered_mw_now`, `delivered_mw_1s`, `delivered_mw_1min`, `delivered_mwh`, `grid_shortfall_mw`, `observed_base_mw`, `requested_total_mw`, `mw_error`, `aggregate_valid`, `selected_meter_count`, `valid_meter_count`, common sampling/saturation fields |
| Tie breaker/source | `breaker_closed`, `export_enabled`, `binding_valid`, `bound_generator` (TEXT), `rpm`, `shaft_frequency_hz`, `slip_hz`, `phase_degrees`, `sync_valid`, `within_sync_window`, `last_close_angle_degrees`, `last_close_slip_hz`, `last_close_fault`, `status` (TEXT) |
| Meter | `mw_now`, `mw_1s`, `mw_1min`, corresponding `fe_per_tick_now/1s/1min`, `mwh`, `label` (TEXT), `display_units` (TEXT), `direction` (TEXT), common sampling/saturation fields |
| Selected meters | Dynamic `meter.<uuid>.label`, `.valid`, `.mw_1s`, `.mwh`; panel may also bind directly to any meter's complete adapter. |

MW/FE rates are nonnegative except signed buffer delta, slip and MW error. Angle
range is [-180,180); damage [0,1]; booleans 0/1. Descriptor maxima for delivery
must allow buffer discharge above rated production. `last_close_*` is unavailable
before any close, and window flags explicitly govern average validity.

Proposed CC API (all status fields mirror the signal contract in camelCase):

| Peripheral | Methods |
| --- | --- |
| Existing `bwr_generator` | Extend `getStatus()` preserving old fields; no tie-binding state or recovery method. |
| `bwr_grid_tie` | `getStatus()`, `setDemandMode(mode)`, `setFixedDemandMW(mw)`, `setRemoteDemandMW(mw)`, `setDailyCurve(minMW,maxMW,periodTicks)`, `resetCurve()`, `closeBreaker()`, `tripBreaker()`, `bindGenerator(x,y,z)`, `clearGenerator()`, `setMeters({{x,y,z},...})`, `getMeters()` |
| `bwr_fe_meter` | `getStatus()`, `setLabel(text)`, `setDisplayUnits("MW" or "FE/t")`, `setDirection(face)` |

Coordinates resolve only in the peripheral's dimension and only if loaded; stored
references include the resolved UUID. Direction is a validated face enum.
Corresponding panel command keys: `demand_mode`, `fixed_mw`, `remote_mw`,
`curve_min_mw`, `curve_max_mw`, `curve_period_ticks`, `reset_curve`,
`close_breaker`, `trip_breaker`; meter `direction` and `display_units` use
documented enum indices. Invalid curve edits reject atomically. Text labels and
association lists use configuration methods, not numeric signal commands.

For CC-free setup, extend the existing panel linker: select a loaded generator,
then apply it to an open tie to bind; select a loaded meter, then apply it to a tie
to toggle that meter in its selected list. Distinguish the pending target's device
type and show translated feedback. Preserve existing panel wiring behavior and
world-edit permission checks. No `/bwr grid` or `/bwr meter` commands are added.
Demand, curve settings, breaker commands, meter direction and display units use
normal panel commands or CC. Labels can use the existing linker/configuration
conventions where supported and CC; new screens belong to the owner's GUI work.
Reject NaN/infinity even in pulse/boolean adapters; validate enum, length,
duplicates and coordinate inputs before mutation. Update `SIGNALS.md` with exact
linker gestures, mappings and CC methods during implementation. Verify startup
and the linker/panel workflow with CC absent.

## 6. Ownership, lifecycle and saves

Pure Java in `core/`: shaft dynamics/losses/overspeed damage, bounded energy
conversions/ledgers, demand-curve evaluation,
phase/slip and bad-close damage arithmetic, plus acceptance tests. FE capability
types, world time, identities, wiring and NBT stay in mod. Share the existing
conversion through a core constant with `EccsPower` delegating unchanged if needed;
do not duplicate divergent mappings or import mod into core.

Mod: new `grid/` devices, one server service registered from `BwrMod`, loaded-set
membership on load/unload/removal, sided live capabilities, registries, server
config, command glue, optional CC, adapters and assets. Keep existing train ticking;
the service never re-ticks a turbine. Publish tick buckets and advance curve/phase
once, with lazy current-tick budget preparation if a capability call precedes the
service hook. Simulation remains read-only. Clear loaded registries on level/server
stop; no static reference leaks or scans of unloaded block entities.

Use versioned additional NBT, preserving all existing power-module tags:

| Device | Durable state |
| --- | --- |
| Generator | UUID, damage, lifetime delivered FE and saturation flag. Existing RPM, FE, water and steam inventories remain unchanged. |
| Tie | UUID, binding, selected meter identities, breaker position, mode and setpoints, curve phase/period/min/max, fractional budget carry, lifetime FE, open phase, last-close samples/fault. |
| Meter | UUID, facing, label, units, lifetime FE and saturation flag. No energy inventory. |

Per-tick allocated/spent budgets and tick stamps are transient. Reload discards
them and starts fresh rate windows; no same-tick budget persistence. At most one
extra tick of demand allowance on reload consumes real FE and duplicates no
energy. Persist fractional carry and lifetime totals, but never serialize rolling
arrays, capability objects, service membership or stale object references.

Old generators without M fields acquire a UUID, zero damage/totals,
and unchanged terminal behavior. New ties start open with zero fixed demand;
there are no legacy ties to auto-bind. Sanitize malformed/nonfinite data, enum
values and oversized lists, normalize phases, and bound counters without overflow.
Invalid identities give unavailable readings. No repeated generation/close damage
or transfer is replayed by loading a save.

Chunk unload/reload preserves device identity; breaking/replacement creates a new
identity and invalidates old bindings. Normal drops preserve durable condition
and cumulative totals in item data (including generator damage) but clear world
bindings, breaker/curve operational state and per-tick budget reservations. Placement
restores those retained values once, starts ties open, and never restores transient
rates. Creative cloning is outside survival energy accounting. All item-data and
NBT paths require round-trip coverage; no repair-by-replacement-of-the-same-item.

## 7. Load-following and integration acceptance

These are **planned tests, not results from step 0**. Register new normal core classes in
`AcceptanceTests` and pass `verifyTestRegistration`; register the long runner
explicitly as well if used. A filtered unregistered test
is not evidence. Add new assertions without weakening existing ones. Every commit
below includes a focused regression that fails without its implementation.

### 7.1 Pure arithmetic and conservation

- FE/MW/MWh dimensions; known exact totals; fractional demand over many ticks;
  more than signed-int FE in an aggregate; caps and counter saturation.
- One shared budget across repeated faces/calls, next-tick settings, expired
  allowance, simulation purity including calls at a tick boundary, zero/negative
  and malformed values, idle windows and reload warm-up.
- Daily extrema/period/freeze; signed slip, phase wrapping, exact limit boundaries
  and either-limit violation. Damage only on a new close event, no protective
  rejection, and persistence/failure behavior.

### 7.2 Coupled core 100 → 70 → 100% scenario

Use one `TransientHarness.ratedCore()` initialized once at equilibrium. Keep its
iodine/xenon, burnup, delayed-neutron and thermal histories continuous. The
existing harness's operator may manage pressure/level and individual rods as
explicit test-side player actions; nothing is wired into a production controller.
Power must emerge from recirculation flow and rod motion, never a direct power
assignment or a poison reset.

Proposed deterministic test schedule: 10-minute rated baseline, 1-hour ramp down,
24-hour reduced-power hold, 2-hour ramp up, 24-hour recovery hold. These are test
choices informed by the hour-scale behavior in [M-XE], not operating limits or a
claim that all poison states equilibrate by the deadline. Use the current core
integration step; run simulation faster than wall clock, not with hour-sized
steps or altered half-lives. Measure runtime and report it. If this adds more
than about three minutes to normal acceptance, register `:core:longAcceptance`
with its own runner and registration coverage, run it in M7 (the former M5 final
scenario item), and report it separately. Keep the normal suite fast.

Recirculation must initiate both load changes with rods initially held; subsequent
explicit rod moves compensate the slow poison change. Demonstrate that both
handles contributed, rather than passing with rods alone. Reduced-power target
is **thermal** power 0.70 of the baseline rating, not a fixed 70% flow setting.
Baseline/recovery target is 1.00. Electrical demand percentages in GameTests are
separate; efficiencies and auxiliaries prevent equating them.

Predeclared acceptance: final 10-minute mean power within ±0.05 of rated fraction
of each target; finite nonnegative iodine/xenon, no unintended shutdown/fuel
failure, rods remain in physical range, and return to the rated band. Require an
initial xenon rise after the reduction followed by a later declining trend;
after returning power, require an initial xenon fall and subsequent recovery
trend. Log hourly power, flow, rod pattern summary, I/Xe, pressure and steam draw,
plus turning-point times. An independent prescribed-power Xenon test may provide
a reference trend, but cannot replace this coupled scenario. If these criteria
fail, report the model/test discrepancy; do not tune away the transient or weaken
old acceptance tests. [M-LF] supports the recirculation mechanism; [M-XE] supports
the delayed compensation, not these numeric tolerances.

### 7.3 Mod GameTests and compatibility

1. Actual generator terminal push and external pull count once; production into a
   full/empty buffer does not masquerade as delivery. A reentrant receiver cannot
   extract the same FE twice. A detached cached capability cannot serve a replacement.
2. Source → meter → tie and source → real dev cable arrangement → meter → tie
   transfer exactly accounted FE. Check both push and pull, rejecting destinations,
   reverse/wrong faces, meter chains/cycles/depth limit and no meter-held inventory.
   Report any unsupported foreign-cable arrangement honestly.
3. Bind an actual formed train, supply actual steam through a throttling valve,
   and synchronize through normal controls. No test writes RPM/phase. Bad closing
   uses the actual command path, damages the bound generator, remains closed,
   and fails to generate only at the defined damage endpoint. Repeated close is inert.
4. With stable steam supply/condenser and fixed valve positions, reduce a closed
   tie's demand and prove lower delivery plus shaft acceleration once buffer
   headroom is exhausted. Then explicitly throttle the existing control valve
   through test-side player commands, demonstrating reduced steam draw and a
   controlled return to rated speed. Reverse through explicit valve/demand actions
   for recovery. Predeclare numeric bands from the fixture's sustainable output;
   log delivery, steam, buffer, RPM and losses. Keep the steam source independent
   of requested grid demand. No production governor is added.
5. Opening the export breaker stops tie intake while a separately wired base load
   can still draw from the existing generator terminal. Retained branch loads and
   buffer changes explain the difference from full rejection. Prove acceleration
   and overspeed after full rejection, then removal of fresh steam torque after
   manual stop-valve closure. A cable with configured priority must demonstrate base supply;
   arbitrary equal-priority cables are not claimed to do so.
6. Duplicate/missing meter selection, serial meters deliberately not selected,
   requested vs delivered grid load, time-aligned MW error, stale identities,
   unloading and replacing each endpoint. No production chunk ticket appears.
7. Round-trip generator/tie/meter and item state, legacy generators,
   frozen-unload curves/phase, retained closed position and no duplicate FE.
   Several ties may bind one generator. Break/replace or unload each endpoint;
   invalid UUIDs disable intake until explicit rebind, with no reciprocal cleanup.
8. Run real CC methods and panel commands against live devices, asserting status
   and signal parity and invalid-input rejection. Repeat registration/startup with
   CC absent. Verify the CC-free panel linker setup without a new GUI.

Allocate fresh nonoverlapping fixture origins only after inventorying current
devtests; the previously listed 44900–56900 origins and containment fixtures are
already occupied. Tests may own/release forced chunks; production may not. Reuse
Part A's loaded-readiness discipline, reset persistent-world fixtures, and clean
owned entities/chunks on success, failure and timeout. Do not merely sleep longer.

## 8. Player documentation and assets

Two new blocks, each with item, recipe, loot, translations and simple placeholder
model/texture; meter direction must be visible. No Blender model work in M.
Document exact FE scale, rate windows/warm-up, topology/priority limits, bound
identity versus real FE wiring, synchronization commands/consequences, supported
meter cable layouts and the observed/requested MW-error definition. Do not claim
these measurements discover an entire third-party electrical network [M-FE].

No diesel block, fuel, recipes, EDG peripheral or automatic backup. Backup FE may
come from other mods, including the owner's Create-engine-to-FE arrangement.
Document an inline meter on that feeder and a separate panel readout. Losing a
generator's actual supply eventually starves FE-fed pumps after all connected
storage drains; merely opening this proposed export breaker leaves base feeders
connected. The player's wiring determines blackout and restoration. No new
station-blackout code or automatic transfer switch is added.

## 9. Build order and completion evidence

The R1–R5 documentation update is followed by **seven implementation commits**;
shaft dynamics precedes the breaker. Each has a regression that fails without its
implementation. Do not amend old acceptance assertions without owner authorization.

| Commit | Deliverable and failing-without-it evidence |
| --- | --- |
| M1 | Core shaft inertia, energy/torque balance, losses and overspeed damage; mod steam-expansion coupling and existing fractional valve control. Core conservation/startup/coast-down/join-split tests; real load-rejection and stop-valve test. Report existing turbine behavior changes. |
| M2 | Core energy budget, curve, ledger and sync primitives. Exact units, conservation, fractional carry, multi-call long budgets, simulation purity, overflow, windows and sync/damage boundaries. |
| M3 | Generator extraction/production/storage accounting, live capability safety, UUID/damage and retained drop condition. Real-terminal push/pull/reentrancy, conservation, persistence and legacy regressions. |
| M4 | Grid tie, loaded service, config, one-way binding and manual export breaker; recipes/assets. Multi-face budgets, multiple ties per generator, stale binding, bad close, and actual synchronization by throttling steam without RPM/phase writes. |
| M5 | Directional zero-storage FE meter, state/items/assets. Push/pull conservation, loops/depth/backpressure/reload and installed-cable compatibility evidence. |
| M6 | Explicit meter selection, MW-error report, all signal/CC/panel linker paths, `SIGNALS.md` and wiring docs. Real peripheral/panel parity, linker setup, CC-absent startup and invalid-input tests. |
| M7 | Registered coupled long load-follow acceptance, end-to-end grid/steam scenarios, migration/unload/priority coverage and final report. Run/report `:core:longAcceptance` when the runtime criterion in §7.2 applies. |

### 9.1 Authorized acceptance-contract updates

The owner authorized the following two updates on 2026-10-14 after review of
the concrete proposals. They resolve conflicts between inertia/losses and the
former acceptance contracts:

1. Former §7.3 item 4 required lower steam draw after lowering grid demand at
   **fixed valve positions**. Once buffer-room gating is removed, reduced
   electrical torque creates acceleration, not a steam-control action. The
   current admission capacity actually rises with RPM (`0.1 + 0.9*(rpm/1500)^2`).
   Authorized replacement: first assert load-rejection acceleration at fixed valve
   position; then issue explicit test-side player throttle commands and assert
   lower steam draw/delivery and a controlled return to rated speed. Reverse the
   sequence for recovery. No governor in production. The accepted sequence is now in §7.3 item 4.
2. `PowerModuleRuntimeCheck.modularPlant` asserts exactly
   `400*850*1000*0.985/0.67` FE from its seeded steam after 300 ticks, despite
   seeding 1500 rpm and ignoring rotor energy and mechanical losses. It then
   asserts zero electric MW after reload merely because steam was consumed.
   With a physical shaft, stored rotational energy may supply electrical work
   and mechanical losses must enter the balance. Authorized replacement: retain steam/mass/share and duplicate-tick
   checks; assert steam work plus initial kinetic energy equals electrical work
   before conversion losses plus mechanical losses plus final kinetic energy;
   assert save/load preserves that balance and introduces no steam or energy.
   Keep the existing numerical conservation tolerance; do not loosen it.

The prior stop report describes source-level conflicts, not observed failing
runs. Only these two contract changes are authorized; retain all other existing
assertions. Implementation continues through M1–M7 without another spec stop.

Final planned commands from the repository (Java 21 environment already used by
the project):

```powershell
.\gradlew.bat :core:verifyTestRegistration :core:acceptance
.\gradlew.bat :mod:runTurbineGameTest
.\gradlew.bat :mod:runLuaExamplesCheck
.\gradlew.bat :mod:runTurbineGameTest -PbwrNoCC
.\gradlew.bat :mod:checkNoProtectionLogic :mod:build
py -3.13 tools-audit-assets.py
py -3.13 tools-audit-translations.py
```

These run names match the baseline's `mod/build.gradle`. Add a focused M client
check only if needed to exercise new block placement/arrow/panel samples, without
editing the owner's screens. Inspect its captures if one is added. Existing L5
scripts must still run against real peripheral names. Record exact commands,
counts, runtime, failures and unverified claims; do not substitute only a focused
test for the full acceptance/GameTest gates. No push is part of this phase.

Budget includes B6; skip it in subsequent TODO-5 Part B. All other Part B/C work
is separate. If implementation needs more than these seven commits or the
specified compatibility cannot be met, report the
concrete scope conflict for review rather than quietly extending the phase.

## 10. Reference index and numeric provenance

Physical reference claims are limited to the cited material below. FE conversion,
generator rating/efficiency/buffer are preserved code values (§0). The old RPM
relaxation is replaced by the explicitly game-calibrated shaft model in §4.3.
Curve periods, configured caps, tick windows, identity/list/depth/label limits,
damage severity and acceptance schedule/tolerances are explicitly engineering or
game choices. Unit conversions and phase integration are derived equations.

- **[M-SYNC]** Thompson and Ravikumar, SEL, [New Developments in Generator
  Synchronizing Systems](https://cdn.selinc.com/assets/Literature/Publications/Technical%20Papers/6479_NewDevelopments_MT_20110210_Web.pdf),
  2011, §II.A printed p.1 (PDF p.2). Inspected manufacturer source for angle/slip
  limits and possible mechanical/electrical damage. IEEE standards cited by SEL
  were not independently inspected. No automatic synchronizer or voltage model
  is imported from this paper.
- **[M-SPEED]** STAMFORD, [Industrial Ratings Book, Edition 2](https://www.stamford-avk.com/sites/stamfordavk/files/2020-05/STAMFORD-Industrial-Ratings-Book-2.pdf),
  useful formulae, p.5: synchronous speed/frequency/pole relation. Four poles and
  50 Hz are a game reference choice consistent with the existing 1500 rpm, not a
  claim that the BWR reference plant uses this alternator.
- **[M-LF]** IAEA NP-T-3.23, [Non-baseload Operation in Nuclear Power Plants](https://pub.iaea.org/MTCD/Publications/PDF/P1756_web.pdf),
  2018, §5.4.1.2 printed p.74 (PDF p.86). Recirculation controls BWR reactivity;
  its German example covers approximately 60–100% rated thermal power at fixed
  rods. It does not establish a universal ramp rate or this model's acceptance band.
- **[M-XE]** NRC, [BWR Generic Fundamentals: Fission Product Poisons](https://www.nrc.gov/sites/default/files/doc_library/cdn/legacy/reactors/operator-licensing/history-rulemaking-activities/generic-fundamentals-examinations/bwr/bwr-files/292006.pdf),
  November 2020, B2559 p.19 and B2361/B2964 p.51. Educational reduced-power and
  recovery cases support hour-scale xenon changes and rod/recirculation compensation;
  they are not plant operating limits.
- **[M-FE]** NeoForge, [IEnergyStorage, 1.21.1 branch](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/energy/IEnergyStorage.java).
  The interface defines integer transfer/storage methods and simulation, without
  a cable-topology, load-priority or aggregate unmet-demand query. Network limits
  in this spec are engineering deductions from that API.

References checked 2026-10-14. Step 0 validates the design against source and
references only; it provides no executed Phase M gameplay, compatibility, damage
or long-transient test results.
