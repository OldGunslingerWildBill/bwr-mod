# Grid export and feeder measurements

Place a **Grid Tie** at the end of the export feeder. It starts open, unbound and
at zero demand. Place **FE Meters** inline with their raised arrow pointing from
source to load. The two arrow-axis faces are input/output; other faces do not
transfer FE. A meter stores no energy.

## Bind and configure

1. With the panel linker, sneak-right-click a formed generator, then right-click
   the open tie. Several ties can bind the same generator. Breaking/replacing
   the generator requires rebinding; unloading it only makes it unavailable.
2. Wire panel controls to the tie's `fixed_mw` and `demand_mode`, or use CC's
   `setFixedDemandMW(mw)` and `setDemandMode(0)`. Mode 1 uses the daily curve;
   mode 2 uses `remote_mw`. Changes apply next tick. No source watchdog operates
   the breaker or valve.
3. With the tie open, roll and trim the turbine with its control valve. Read
   `rpm`, `slip_hz`, `phase_degrees` and `within_sync_window`. The reference is
   1500 rpm, angle within ±10 degrees and slip within ±0.067 Hz.
4. Issue `close_breaker`. A bad close still closes and damages the generator.
   `trip_breaker` is a player action opening this export branch. Other terminal
   loads can continue. No automatic governor or overspeed trip is supplied.
5. On a load reduction, throttle steam explicitly. With the valve held, reduced
   electrical load eventually fills the FE buffer and accelerates the shaft.
   Above 1650 rpm damage accumulates. At full damage new generation stops;
   retained FE can still be extracted. Dropping/replacing the same item does
   not repair its damage.

## Select base meters

Sneak-right-click each base-feeder meter, then right-click the tie to toggle its
selection. Use disjoint final feeders supplied by this generator. Do not select
the grid-export meter, serial duplicates or feeders supplied by another source.
The tie cannot infer foreign cable topology. CC can replace the list atomically
with `setMeters({{x=...,y=...,z=...}, ...})`, up to 32 unique meters.

After one second of aligned loaded history:

`mw_error = selected base-meter MW + requested grid MW - generated MW`.

The grid term remains requested demand even with the breaker open. Check
`export_enabled` and `grid_shortfall_mw`. A selected endpoint that is missing,
replaced or warming up makes `aggregate_valid` false and omits derived values.
Reload clears rate history; lifetime MWh survives. One-minute averages need
1200 completed loaded ticks. Display units change presentation, not energy.

## Cable and priority limits

Verified with **Mekanism 10.7.19**: generator → basic universal cable → FE meter
→ fuel fabricator transferred 10000 FE exactly. Direct generator → meter → tie
is also covered by an integration test. A real parallel-cable fixture delivered
5000 FE each to a metered export tie and a fuel-fabricator base branch; after
opening export, another 10000 FE went entirely to the base branch. This checks
branch availability and conservation, not configurable cable priority. Other
cable mods may require positive storage/capacity and may not support a
zero-storage bridge.

Use explicit cable priority where available or a separately controlled export
branch to serve base loads first. Parallel equal-priority cables can let export
compete with base loads. The tie itself does not discover or prioritize them.
No voltage, reactive power, excitation or electromechanical grid locking is modeled.

Exact signal keys, enum values and CC methods are in [SIGNALS.md](../SIGNALS.md).

### Mekanism cable priority

Mekanism **basic universal cables split power evenly and have no consumer priority**. Putting the base
and a Grid Tie export on the same cable network does not guarantee base-first supply. For base-first
feeding, use a cable/network with explicit output priorities, or a separate export line whose feed
can be limited independently. The Grid Tie cannot impose a priority on another mod's cable network.
