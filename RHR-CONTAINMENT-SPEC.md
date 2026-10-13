# RHR and pressure suppression — implementation spec (planned alpha.33)

Status: **design, not yet built.** Connects the reactor, the suppression pool
and the containment atmosphere so that a BWR containment works the way it
really does: it suppresses pressure by condensing steam in the pool, and RHR
removes the heat. Then it proves it with whole-plant scenario tests.

Implementation note after J-fix4: containment has **separate drywell and
wetwell gas nodes**, pressure-driven downcomers, passive vacuum breakers and
finite-rate condensation. The DBA test requires distinct pressure peaks in
the cited **49.6-psig drywell** and **27-psig wetwell** neighborhoods.

House rules: physics stays in `core/` with no Minecraft imports. No per-block
tickers. Never force-load chunks. Every saved state round-trips, and old saves
load. **Nothing acts automatically and there is no protection logic**: the
`checkNoProtectionLogic` Gradle task must stay green. Nothing trips, isolates
or starts by itself; players do that themselves or through CC. NEVER edit an
existing test to make it pass.

## 0. The gap today

- `ContainmentAtmosphere` (alpha.30/31) tracks N2, O2, H2 and other
  noncondensables at a **fixed gas temperature, with no steam**.
- The suppression pool (`core/pool/SuppressionPool`, a mod multiblock)
  condenses steam and reports `getUncondensedSteamKgPerS()`, but that steam
  goes nowhere. Pool temperature has no effect on containment pressure.
- Break steam from `BoundaryStress` openings never pressurizes containment.
- RHR (`RhrHeatExchanger`) cools the pool, which today has no effect on
  containment.
- There appears to be no RHR **shutdown cooling** mode. Confirm this first; if
  it exists, document it and skip §3.2.

## 1. Steam and temperature in the containment atmosphere (core)

Extend `ContainmentAtmosphere`, keeping all existing behavior and tests green:

- Add a **steam inventory** (kg) and a **gas temperature** (K), replacing the
  fixed temperature. Total pressure is the sum of the partial pressures. Steam
  partial pressure is capped at saturation at the coldest condensing surface;
  the excess condenses.
- **Heat sinks:** a lumped wall/structure heat-sink capacity that absorbs heat
  and condenses steam (time constant and capacity as named calibration
  constants, scaled with free volume), plus a constant small loss to ambient.
- **Steam inerting:** hydrogen is not flammable while steam exceeds about 55
  vol% (Shapiro-diagram behavior). A spray or cooldown that condenses the steam
  can make a stored hydrogen mixture flammable again. This is real and
  important; add a unit test for it.
- **Inputs per step** (new API, all optional, all zero by default so
  standalone cores and old tests are unchanged):
  - steam to the drywell, kg/s, with enthalpy (break steam);
  - steam to the wetwell airspace, kg/s (pool uncondensed steam);
  - pool surface temperature (sets a saturation floor for the wetwell
    airspace);
  - spray flow, kg/s, and spray temperature.
- **Spray:** condenses steam toward saturation at spray temperature, cools the
  gas, and washes airborne activity out (a named spray decontamination
  constant, applied to the airborne fraction).
- **Snapshot:** add a version and keep loading v1/v2 snapshots (missing fields
  mean no steam, at the old fixed temperature).

## 2. Plant coupling (mod)

Per reactor tick, through the existing linked containment controller and its
linked pools (one pool is the wetwell, spec CONTAINMENT-SPEC §2.3):

- Break steam from the core's boundary openings goes to the **drywell** when
  contained. When uncontained it goes to the environment, as hydrogen already
  does.
- Drywell steam vents to the wetwell pool (the downcomers). Route it into the
  linked pool's existing condensation model. Steam the pool fails to condense
  (`getUncondensedSteamKgPerS`) goes to the wetwell airspace in the
  atmosphere. Pool surface temperature feeds the saturation floor.
- SRV and RCIC/HPCI exhaust already discharge to the pool. Make sure their
  uncondensed steam reaches the atmosphere through the same path.
- No linked pool: drywell steam stays in the atmosphere and only the heat
  sinks condense it, so an unsuppressed containment pressurizes fast. That's
  intended, and it's why the pool matters.
- Expose the new state through the existing readouts, the CC
  `getContainment()` and `bwr_containment` methods, and the signal adapters
  (`dev.bwr.mod.signal.adapters.ContainmentSignals`, plus a `SIGNALS.md`
  update): steam %, gas temperature, drywell/wetwell pressure (one node is
  fine), and spray flow.

## 3. RHR modes

### 3.1 Suppression pool cooling (exists)
Verify that the existing RHR heat exchanger cooling the pool now visibly
bounds containment pressure through §2.

### 3.2 Shutdown cooling (add if missing)
Reactor water → RHR pump → RHR heat exchanger → back to the vessel, for
cooldown to cold shutdown after a scram. Reuse the existing water-line,
pump and heat-exchanger blocks and plumbing patterns rather than new blocks
if possible.
- **No interlock** (no protection logic). The physics consequence instead:
  RHR piping is low-pressure. Lining up shutdown cooling while the vessel is
  above the low-pressure piping's rating (a named constant, about
  135–450 psig, cite the basis) overpressures the low-pressure side. That's an
  **interfacing-systems LOCA**: a break outside containment through
  `BoundaryStress`, a containment bypass. Real and dangerous, and the
  player's responsibility.

### 3.3 Containment spray
A spray header block placed inside containment, fed by an RHR (or other) pump
water line. It drives the spray input of §1 with the measured flow and water
temperature. Wetwell spray and drywell spray can be the same block placed in
either volume. Expose it through CC and the signal adapters.

## 4. Scenario acceptance tests (the point of this phase)

Pure-Java core acceptance tests where possible (register them in
`AcceptanceTests`). Use GameTests only for the mod coupling. Each scenario
prints its timeline the way `SevereAccidentIntegrationTest` does. Calibrate to
cited references, adding them to `REFERENCE-DATA.md` with sources (for
example Mark I/II/III DBA peak pressures and pool temperature limits from the
FSARs already used in the project).

1. **Design-basis LOCA** (large recirculation break), sealed containment,
   subcooled pool, ECCS injecting: peak containment pressure in the cited
   range and below the failure pressure; pool temperature rises but stays
   subcooled.
2. **Same LOCA without RHR pool cooling for 24 h:** the pool heats toward
   saturation and pressure climbs. With RHR pool cooling, pressure and pool
   temperature are bounded. With venting, pressure drops.
3. **Station blackout on RCIC:** RCIC and SRV discharge heat the pool over
   hours and pressure climbs (Fukushima-like). Restoring power and RHR turns
   it around.
4. **Scram to cold shutdown:** with shutdown cooling the vessel reaches
   < 100 °C within a cited number of hours. Lining it up at high pressure
   causes the interfacing-systems LOCA instead.
5. **Steam inerting:** stored hydrogen with high steam does not burn; spraying
   it down makes it burn.
6. **Old saves:** a v2 snapshot loads with no steam and the old behavior.

## 5. Out of scope

GUIs (a GUI overhaul is planned separately), sounds, new models beyond
placeholders, automatic actions of any kind.
