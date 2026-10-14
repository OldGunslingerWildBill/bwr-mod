# Grid tie and load following — Phase M implementation spec

Status: **step 0, proposed for review; no implementation.** Baseline:
`2ef9943` on `containment-structure`, after accepted TODO-5 Part A.
Scope follows the updated handoff `ASTRA-TODO-4.txt` Phase M and the owner's
2026-10-14 instruction. The older Downloads copy's diesel and larger commit
budget are superseded. Plan: this documentation commit plus **five implementation
commits**, six total. Stop after this spec for review.

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
| `power/PowerTrain.java` in mod | Finds a loaded coaxial train, at most 32 modules; executes each module once per game tick. Generator buffer room bounds turbine expansion work. RPM approaches 1500 while work is positive and approaches zero otherwise, with an 8-second relaxation. |
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

Three interpretations need explicit review before implementation:

1. **The proposed breaker isolates the grid-export branch.** It lives in the grid
   tie; it does not disconnect the generator's existing FE terminal or base
   feeders. This is a scoped interpretation of TODO-4's “generator output
   breaker.” A global output breaker with today's RPM rule prevents sustained
   open-breaker spin-up once the internal buffer fills. Resolving that properly
   requires the no-load shaft work deferred to B6. This proposal preserves the
   existing terminal and requires a real auxiliary/base load for commissioning.
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

Allow one tie per generator in M. Store reciprocal UUID bindings; handshakes must
reject duplicates and stale coordinate reuse. Binding changes require the tie to
be manually open, preventing a configuration change from bypassing a close event.
This is configuration integrity, not a synchronization permissive. Rebinding away
from an unloaded endpoint requires the player to clear its old association when
it is next loaded; no hidden cross-chunk edits.
Refuse a new binding until its prior association has been cleared by that procedure.

Define an explicit recovery path for an orphaned reciprocal binding. Generator
`clearGridBinding()` (also panel `clear_grid_binding` and the CC-free command)
resolves the recorded tie location only if loaded. If the matching tie exists,
it must be manually open, and the command clears both endpoints atomically. If
that loaded location is empty or contains a different UUID, clear only the stale
generator record. If the peer location is unloaded, return unavailable without
mutation. The tie's `clearGenerator()` follows the same rule for its loaded peer
and requires its own breaker open. Thus breaking a tie while its generator is
unloaded cannot reserve that generator forever: later load the old tie location
and generator, explicitly clear the orphan, and bind the replacement. Save/load
alone never repairs or removes an association.
Clear a peer record only if it reciprocally identifies the caller; otherwise
clear the caller's stale record alone, without altering the peer's valid binding.

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

Commissioning requires a genuine base/auxiliary FE draw with the tie open and
steam admitted, then an explicit close near the displayed window. Under today's
RPM approximation, sustained positive work approaches rated speed irrespective
of its magnitude; steam interruption can change speed/phase. The implementation
must prove a reachable synchronization sequence through actual steam/load
inputs, without writing RPM or phase in the integration test. No hidden dump load,
phase-set command or turbine governor is added to make that test pass.

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

### 4.3 B6 interaction — deferred, not built by M

Opening at load stops tie acceptance immediately. Other connected loads remain;
a branch opening is full rejection only if it was the generator's only load.
External cable/battery storage and the generator buffer can delay the decrease in
electrical production. The existing internal buffer's empty-to-full headroom at
1500 MW is only **0.8933 seconds**, derived from §1; its actual headroom may be
smaller. In M, buffer-room gating then reduces steam draw and today's RPM rule
decays speed. **M must not assert that this produces overspeed.**

B6 will use tick-level steam work, generated electrical energy, delivered FE,
buffer delta and the manual breaker edge, not a one-second average as torque.
Extraction alone is insufficient: charging storage is electrical load too, while
discharge is not new shaft work. B6 must decouple admitted steam expansion from
the present generator-room work cap and put surplus shaft work into rotational
energy with losses. Merely replacing the RPM interpolation leaves steam work
artificially removed at full storage and cannot create the requested transient.
No energy dump or double-counting of storage as kinetic energy is allowed.

Future B6 acceptance: with real steam still admitted and the grid branch carrying
the sole load, manually open the breaker, account for storage/residual loads, and
observe acceleration above rated speed; explicitly closing the stop valve must
remove steam torque. With retained base load, expect a partial rejection. B6 must
source its own inertia/loss and overspeed-damage parameters. TODO-5's suggested
110–120% is **not an adopted M threshold**. There will still be no automatic trip.
The global-terminal-breaker/startup question in §0 must be revisited when B6
supplies real no-load shaft behavior.

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
| Generator | `delivered_mw_now`, `delivered_mw_1s`, `delivered_mw_1min`, `delivered_mwh`, `generated_mw_1s`, `generated_fe_tick`, `delivered_fe_tick`, `buffer_delta_fe_tick`, `damage`, `failed`, `counter_saturated`, `sample_ticks`, `window_ready_1s`, `window_ready_1min`, `grid_binding_valid` |
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
| Existing `bwr_generator` | Extend `getStatus()` preserving old fields; add `clearGridBinding()` for the loaded, player-requested recovery in §2.2. |
| `bwr_grid_tie` | `getStatus()`, `setDemandMode(mode)`, `setFixedDemandMW(mw)`, `setRemoteDemandMW(mw)`, `setDailyCurve(minMW,maxMW,periodTicks)`, `resetCurve()`, `closeBreaker()`, `tripBreaker()`, `bindGenerator(x,y,z)`, `clearGenerator()`, `setMeters({{x,y,z},...})`, `getMeters()` |
| `bwr_fe_meter` | `getStatus()`, `setLabel(text)`, `setDisplayUnits("MW" or "FE/t")`, `setDirection(face)` |

Coordinates resolve only in the peripheral's dimension and only if loaded; stored
references include the resolved UUID. Direction is a validated face enum.
Corresponding panel command keys: `demand_mode`, `fixed_mw`, `remote_mw`,
`curve_min_mw`, `curve_max_mw`, `curve_period_ticks`, `reset_curve`,
`close_breaker`, `trip_breaker`; meter `direction` and `display_units` use
documented enum indices. Invalid curve edits reject atomically. Text labels and
association lists use configuration methods, not numeric signal commands.
The generator's `clear_grid_binding` is a pulse with the same recovery validation.

For CC-free setup, add narrow `/bwr grid ...` and `/bwr meter ...` commands aimed
at the player's looked-at device, with tab completion and the same validation and
world-edit permission checks. They expose the same configuration/close/trip
operations; selection references resolve loaded blocks only. They introduce no
screen, automation or remote chunk access. Panel controls then use the ordinary
wiring system. Reject NaN/infinity even in pulse/boolean adapters; validate enum,
length, duplicates and coordinate inputs before mutation. Update `SIGNALS.md`
with exact mappings and CC methods during implementation, not spec step 0.

## 6. Ownership, lifecycle and saves

Pure Java in `core/`: bounded energy conversions/ledgers, demand-curve evaluation,
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
| Generator | UUID, damage, tie binding, lifetime delivered FE and saturation flag. Existing RPM, FE, water and steam inventories remain unchanged. |
| Tie | UUID, binding, selected meter identities, breaker position, mode and setpoints, curve phase/period/min/max, fractional budget carry, lifetime FE, open phase, last-close samples/fault. |
| Meter | UUID, facing, label, units, lifetime FE and saturation flag. No energy inventory. |

Also persist the tie's last budget game-time stamp, allocated/spent FE and carry
state for **same-tick** unload/reload protection. Reuse them only when the loaded
world tick matches; otherwise begin a new budget without spending old allowance
or advancing an unloaded interval. Test this explicitly. Do not serialize rolling
arrays, capability objects, service membership or stale object references.

Old generators without M fields acquire a UUID, zero damage/totals, no binding,
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

These are **planned tests, not results from step 0**. Register new core classes in
`AcceptanceTests` and pass `verifyTestRegistration`; a filtered unregistered test
is not evidence. Add new assertions without weakening existing ones. Every commit
below includes a focused regression that fails without its implementation.

### 7.1 Pure arithmetic and conservation

- FE/MW/MWh dimensions; known exact totals; fractional demand over many ticks;
  more than signed-int FE in an aggregate; caps and counter saturation.
- One shared budget across repeated faces/calls, next-tick settings, expired
  allowance, simulation purity including calls at a tick boundary, zero/negative
  and malformed values, idle windows, reload warm-up and same-tick reload.
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
steps or altered half-lives. Measure runtime and report it.

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
3. Bind an actual formed train, supply actual steam and a real auxiliary FE load,
   and synchronize through normal controls. No test writes RPM/phase. Bad closing
   uses the actual command path, damages the bound generator, remains closed,
   and fails to generate only at the defined damage endpoint. Repeated close is inert.
4. With stable steam supply/condenser and fixed valve positions, reduce a closed
   tie's demand, wait for measured buffer/rolling-window settling, and prove lower
   generator delivery and turbine steam draw; restore demand and prove recovery.
   Choose targets within that fixture's measured sustainable output, log MW/FE,
   buffer and steam before/after, and declare numeric bands before implementation.
   Do not make the steam source follow the requested grid demand directly.
5. Opening the export breaker stops tie intake while a separately wired base load
   can still draw from the existing generator terminal. Retained branch loads and
   buffer changes explain the difference from full rejection. No overspeed assertion
   belongs in M. A cable with configured priority must demonstrate base supply;
   arbitrary equal-priority cables are not claimed to do so.
6. Duplicate/missing meter selection, serial meters deliberately not selected,
   requested vs delivered grid load, time-aligned MW error, stale identities,
   unloading and replacing each endpoint. No production chunk ticket appears.
7. Round-trip generator/tie/meter and item state, legacy generators, same-tick
   reload, frozen-unload curves/phase, retained closed position and no duplicate FE.
   Break a tie while its generator is unloaded, then load both locations, clear
   the orphan through the real command, and successfully bind a replacement.
8. Run real CC methods and panel commands against live devices, asserting status
   and signal parity and invalid-input rejection. Repeat registration/startup with
   CC absent. Verify the CC-free setup commands without a GUI.

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

Step 0 is this spec and the reference appendix only. **Stop for review.** After
approval, use the following five implementation commits; do not start any now.

| Commit | Deliverable and failing-without-it evidence |
| --- | --- |
| M1 | Core budget/curve/ledger/sync primitives and generator extraction accounting; registration, exact conservation, simulations, overflow/window tests and real-terminal push/pull regression. Preserve old FE/peripheral behavior. |
| M2 | Grid tie, loaded service, config, binding, manual export breaker, close damage/save migration; recipes/assets alongside the block. Tests for multi-face budgets, manual close/trip, stale binding, actual startup/synchronization, damage and legacy generator behavior. |
| M3 | Directional zero-storage FE meter, state/items/assets; push/pull conservation, loop/backpressure/reload tests and installed-cable compatibility evidence. |
| M4 | Explicit meter selection and MW-error report; all signal/CC/configuration commands, `SIGNALS.md` and player wiring documentation. Real peripheral/panel parity and CC-free setup/optional-dependency checks. |
| M5 | Registered long core load-follow acceptance, end-to-end demand/steam response, migration/unload/priority coverage and final report. Include measured runtime and supported cable limits. |

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

Budget excludes B6 and all TODO-5 Part B/C work. If implementation needs more
than these five commits or the specified compatibility cannot be met, report the
concrete scope conflict for review rather than quietly extending the phase.

## 10. Reference index and numeric provenance

Physical reference claims are limited to the cited material below. FE conversion,
generator rating/efficiency/buffer/RPM relaxation are preserved code values (§0).
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
