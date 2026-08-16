# Realistic BWR Physics Mod — v1 Specification (Rough Draft)

**Target:** Minecraft 1.21.1 / NeoForge
**Goal:** Replace Mekanism's fission reactor with a physically-modelled BWR — power emerges from neutronics, not from a commanded burn rate.

---

## 0. Architecture Principle

Hard split between physics and Minecraft:

- **`ReactorCore`** — pure Java, zero Minecraft imports. All state as doubles. Unit-testable, runnable standalone with a `main()` that dumps transients to CSV.
- **`ReactorMultiblockBlockEntity`** — structure validation, NBT persistence, client sync, capability exposure, CC:Tweaked `IPeripheral`. Calls `core.step()` once per tick.

**Do not skip the standalone phase.** Get a believable startup, load change, and SCRAM curve plotting in plain Java before touching NeoForge. Every hour spent here saves three of in-game debugging.

---

## 1. Neutronics

### 1.1 Point Kinetics (per tick)

Six delayed-neutron precursor groups:

```
dn/dt   = ((ρ − β)/Λ)·n + Σᵢ λᵢ·Cᵢ
dCᵢ/dt  = (βᵢ/Λ)·n − λᵢ·Cᵢ
```

- `Λ` ≈ 4e-5 s (prompt neutron lifetime)
- `n` scaled as **fractional power** (~1.0 at rated), never absolute neutron density — avoids float blowup
- Standard U-235 six-group βᵢ/λᵢ set (λ ≈ 0.0124 → 3.01 /s)

**Stiffness warning — the #1 implementation risk.** Λ ≈ 4e-5 s vs a 0.05 s tick means naive forward Euler at tick rate diverges immediately. Required: sub-step inside `step()` (a few hundred sub-steps per tick), or an integrator handling the prompt jump analytically.

### 1.2 Reactivity Balance

```
ρ = ρ_rods + ρ_void + ρ_doppler + ρ_xenon + ρ_pressure + ρ_fuel
```

| Term | Sign | Driver |
|---|---|---|
| `ρ_rods` | −ve | Rod insertion fraction, S-curve worth |
| `ρ_void` | −ve coeff | Void fraction (function of power, flow, pressure) |
| `ρ_doppler` | −ve coeff | Fuel temperature (√T form) |
| `ρ_xenon` | −ve | Xe-135 concentration |
| `ρ_pressure` | +ve on rise | Pressure collapses voids → adds moderator |
| `ρ_fuel` | — | Aggregate k_inf of loaded assemblies |

Negative void and Doppler coefficients are what make it a BWR. Getting the signs right is most of what makes it *feel* like a reactor.

### 1.3 Spatial Flux Shape (Nodal Solve)

**Not** ray tracing / Monte Carlo — too slow, statistically noisy, wrong tool. Use coarse-mesh nodal diffusion:

- Each assembly (optionally × axial nodes) is a cell
- Cell flux depends on own `k_inf` plus leakage to/from neighbours
- A handful of relaxation sweeps until settled

**Timescale separation (improved quasi-static method):**

- **Every tick:** scalar point kinetics, sub-stepped, using current `β_eff` and shape weights
- **Every ~20 ticks (1 Hz):** re-solve spatial shape, update `β_eff` and per-assembly power weights

Flux *shape* changes slowly (rods, burnup, xenon); power *level* changes fast. A 15×15 core × 25 axial nodes ≈ 5600 cells — trivial at 1 Hz, off-thread if scaling to many reactors.

**What this buys:** centre-vs-periphery flux gradient, rod shadowing, loading patterns mattering, local absorption from tritium rods.

### 1.4 Decay Heat

Separate additive term, not part of kinetics. Sum-of-exponentials fit: ~6–7% of rated at shutdown, ~1% at one hour. This is what makes post-SCRAM cooling a real requirement.

### 1.5 Xenon

Two coupled ODEs (I-135 → Xe-135). Time constants are hours, so integrate at coarse timestep. Produces the post-shutdown xenon peak (~8–11 h) and xenon-precluded restart.

---

## 2. Fuel

### 2.1 Data-Driven Registry

Fuel types are **JSON/datapack entries**, not hardcoded classes. `ReactorCore` never knows fuel names — only aggregated constants.

Per-fuel parameters:

| Param | Controls |
|---|---|
| `k_inf` base | Reactivity worth → flux, power ceiling |
| `β` | Delayed neutron fraction — control margin |
| `Λ` | Prompt lifetime — response speed |
| `depletionRate` | Cycle length |
| `dopplerCoeff` | Self-limiting strength |
| `gadContent` | Burnable poison, burns out over cycle |
| `heatPerFission` | Thermal output |

### 2.2 Fuel Tiers (HBM-style tradeoffs)

| Fuel | β | Character |
|---|---|---|
| **LEU** | ~0.0065 | Cheap, forgiving, low flux/power. Training wheels. |
| **HEU** | ~0.0065 | Expensive, long runtime, high flux. Danger = large excess reactivity held down by rods; withdrawal errors are severe. |
| **Plutonium** | ~0.0021 | Roughly ⅓ the control margin. Transients LEU rides out can go prompt critical. |
| **MOX** | ~0.003–0.004 | Between the above, harder spectrum, twitchier. |
| **Thorium** | ~0.0027 (U-233) | Starts weak (Th-232 not fissile), needs a fissile driver, improves as U-233 breeds in. Rewards long-term planning. |

The danger of Pu/MOX is **emergent from β**, not a hardcoded penalty. This is real physics.

### 2.3 Burnup & Loading

```
k_inf = base(enrichment) − burnupPenalty(burnup) − gadPenalty(gadRemaining)
```

- Burnup lives on the **item** (data components), not the core slot — enables real fuel shuffling between positions, as real plants do
- End of cycle = core can no longer reach criticality with all rods out. This is what makes fuel management matter.
- Mixed cores: flux-weighted average of β and other constants

### 2.4 Tritium Rods

Li-6 + neutron → tritium. Mechanically a **neutron absorber** — eats flux, costs reactivity and power. Real tradeoff: run for power or run for tritium. Production scales with local flux (HEU cores breed faster). Rods deplete as lithium is consumed. Feeds late-game fusion tech.

---

## 3. Control Rods

- **Individual** hydraulic drives — BWR practice, one per rod, **not** PWR-style ganged banks
- Insert from the **bottom** (top of core is steam-voided, rods less effective there)
- Position 0–100%, worth follows an **S-curve** (most effective mid-core where flux peaks)
- SCRAM = full insert in ~2–3 s, reusing the same ramp machinery as pumps
- Absorber variants: B₄C (standard), hafnium (longer-lived)

---

## 4. Flow

### 4.1 Model

```
coreFlow = f(Σ pumpActualSpeed) × Σ(jetPumpEfficiency)
```

**Scalar total core flow only** for v1 — no per-jet-pump asymmetry. Deliberate scope cut: asymmetric flow costs a lot of complexity and overhead for something rarely visible on a panel.

Core flow feeds the void fraction term, closing the power/flow loop — the defining BWR feedback. Flow control lets you change power without moving rods.

### 4.2 Recirculation Pumps

**Block:** tall cylinder, mechanism pipe input + output. No custom model needed for v1 — reskin later.

**Auto-inclusion:** satellite-block pattern, not classic multiblock validation. Pumps announce themselves to the controller via capability/BlockEntity lookup on place/break. Invalid pumps (unpowered, disconnected) contribute 0 and drop out **without invalidating the multiblock** — losing one pump should reduce flow, not SCRAM the plant.

**Energy:** 1 kFE/t minimum, 500 kFE/t maximum.

```
maxAchievableSpeed = energyReceived / 500000
actualTarget = min(targetSpeedFraction, maxAchievableSpeed)
```

Below 1 kFE/t = pump off (0 speed), not negative. Handles brownouts naturally — grid sag slows pumps automatically. **Surface the mismatch in the GUI:** "commanded 80% / limited to 50% (power)".

**Inertia (first-order lag on speed, not on energy):**

```
actualSpeed += (targetSpeed − actualSpeed) × (dt / tau)
```

- `tau` ≈ a few seconds
- **Asymmetric tau:** slower on deceleration than acceleration — flywheel coastdown. One-line change, and pump coastdown is the most recognisable behaviour in a loss-of-power event. Gives survivable flow decay instead of an instant drop.

**Control:**
- 0–100% **slider** in pump GUI (real recirc pumps are continuously variable)
- **CC control toggle** in the same GUI — visually obvious who's in charge
- Both paths write one authoritative `targetSpeedFraction` field
- When CC has control, grey out the slider (avoids the confusing dead-drag case)
- Slider sets a *target* that ramps, same as CC — consistent behaviour from both paths

### 4.3 Jet Pumps

Physical blocks placed in the core:

- **Small:** 2 blocks long — fits confined spaces
- **Large:** 3 blocks long — more effective flow, bigger footprint
- Each contributes a flow multiplier (large ≈ 1.5× small)
- Place as a single item occupying 2–3 blocks (door/bed pattern) rather than requiring manual stacking
- **No jet pumps is valid** — flow comes only from direct pump push, significantly reduced

### 4.4 RIP (Reactor Internal Pumps)

- Replace both external pumps and jet pumps entirely
- Count configurable **in steps of 2** (symmetric pairs, ABWR practice), limit ~10
- Flow scales with RIP count × speed
- **Major safety advantage:** no large external recirculation piping → no recirc-line LOCA (see §6)

### 4.5 Natural Circulation — DEFERRED

The mode for a BWR with no recirculation pumps at all; lower power density (buoyancy can't match pumped flow — cf. ESBWR).

Deferred because flow becomes an *output* not an input: buoyancy depends on void, which depends on power, which depends on flow. Genuine algebraic loop with known instability regions in real plants. Would need iterative solve or lagged approximation.

**Structure the flow calc as "driving head × jet pump efficiency" now** and buoyancy slots in later as an alternate driving head with no restructuring.

---

## 5. Pressure & Steam

### 5.1 Saturation Coupling

The vessel is saturated — pressure and temperature are **locked by the steam saturation curve**. Track pressure, read temperature off the curve. A fitted correlation over the operating range is sufficient; no steam tables library needed.

**Operating point:** ~1150 psig (BWR/6, ABWR), ~286 °C.

### 5.2 Pressure Balance

```
dP/dt ∝ (steam generated) − (steam removed)
```

- Generated from core power
- Removed via turbine control valves, bypass valves, relief valves
- Vessel steam volume sets response sharpness

### 5.3 Pressure → Reactivity (the payoff)

```
pressure ↑ → voids collapse ↓ → more moderator → reactivity ↑ → power ↑
```

Void fraction becomes `f(power, flow, pressure)`. This makes the classic pressurisation transient **emergent**: MSIV closure → pressure spike → void collapse → power surge. Exactly why real BWRs SCRAM on MSIV closure rather than waiting for a flux trip.

**Numerical caution:** this is a fast positive-feedback-capable loop. Let the sub-stepped kinetics see pressure as **constant within a tick**; update pressure once per tick. That timescale separation keeps it stable.

### 5.4 Modelling Steam Line Pressure — Design Recommendation

Mekanism pipes move gas by amount and have no pressure concept, so custom pressurised tubes are needed. **But do not simulate pressure per pipe segment** — a per-block pressure network is heavy, oscillation-prone, and adds little visible fidelity.

**Recommended: lumped volumes.**

- Treat the RPV steam dome as one volume, the main steam header as a second
- Custom pipes serve as **connectivity and validation** (are the SRVs on a valid line? is the turbine connected?), not as pressure-solving nodes
- Convert to Mekanism steam only at the turbine interface, so downstream turbines keep working

This gives real pressure behaviour where it matters (vessel, main steam line, relief setpoints) at a fraction of the cost, and keeps the existing plant intact.

### 5.5 Pressure Control Equipment

Build in this order — the plant stays non-exploding at each step:

1. **Pressure balance + saturation link** (readouts work)
2. **Pressure → void reactivity coupling** (physics payoff)
3. **SRVs** (safety backstop)
4. **Pressure regulator / turbine bypass** (normal operation)

- **Turbine control valves / pressure regulator** — PID loop holding pressure at setpoint. Makes the plant feel alive during load changes.
- **Turbine bypass valves** — dump steam to condenser when turbine can't take it. Without these, a turbine trip is immediate overpressure.
- **SRVs** — spring-set, staggered setpoints, discharge to suppression pool. Must be loud and obvious in-game.

### 5.6 Relief Valve Placement

- Mount on pressurised tubes off the reactor outlet
- **Must discharge underwater** for steam suppression — validation requirement, not decoration

---

## 6. Overpressure Damage & Failure

Stress accumulation, not hard thresholds.

| Pressure | Behaviour |
|---|---|
| < 1250 psi | No damage |
| 1250–1375 | Stress accumulates, rate scales with overpressure |
| > design limit | Per-tick failure probability, weighted to the most-stressed component |

**Stress distribution depends on plant configuration:**

- **External-loop plants** (recirc pumps + jet pumps): stress across recirculation piping, feedwater lines, steam lines
- **RIP plants:** only feedwater and steam lines exposed — survive longer, fail differently

Failures are non-deterministic but not arbitrary: the component that's been abused is the one that goes.

**Break types behave differently:**

| Break | Consequence |
|---|---|
| **Steam line** | Rapid depressurisation → voids *form* → negative reactivity, power drops — but violent level swell/drop |
| **Recirculation line** | The nasty one: lose core flow *and* inventory simultaneously. This is the design-basis LOCA that sizes ECCS on jet pump plants. |
| **Feedwater line** | Slower — lose makeup, level declines, ECCS timers start |
| **RPV head failure** | Terminal case. Sustained gross overpressure only. |

RIP immunity to recirculation-line LOCA is a **genuine mechanical advantage from geometry** — makes RIP a meaningful late-game upgrade, not just "more pumps".

---

## 7. Suppression Pool — Own Subsystem

Not a decorative water block. Full model needs:

- Pool water mass and temperature
- Heat input from SRV discharge and blowdown
- RHR cooling to remove that heat
- **Pool temperature limits** — real plants have heat capacity temperature limits above which continued SRV discharge is unacceptable. This is the real constraint: an extended transient heats the pool until you're forced into cooldown.

**v1 scope:** SRVs must discharge into a designated water volume to be valid; pool tracks temperature; exceeding a limit triggers reduced suppression effectiveness or an alarm. Full RHR-integrated heat sink is phase two.

---

## 8. Emergency Subsystems

*(Sketch — needs its own design pass)*

### 8.1 Reactor Protection System (RPS)

Automatic SCRAM on: high flux, short period, high pressure, low level, MSIV closure, high drywell pressure, loss of power. Should be a table of trip setpoints, each individually inspectable/bypassable — bypass logic is where interesting operator decisions live.

### 8.2 ECCS

Real BWR layering, high pressure → low pressure:

| System | Role |
|---|---|
| **RCIC** | Steam-turbine-driven, needs no AC power — the station blackout workhorse |
| **HPCI / HPCS** | High-pressure injection, works without depressurising |
| **ADS** | Automatic Depressurisation — deliberately opens SRVs to drop pressure so low-pressure systems can inject |
| **LPCS / LPCI (RHR)** | High-volume, low-pressure — only works after depressurisation |

The **ADS handoff is the interesting mechanic**: you must deliberately blow down the vessel to let the big pumps work, and that decision has consequences (pool heating, level swell).

### 8.3 Standby Liquid Control (SLC)

Boron injection — the ATWS answer when rods fail to insert. Adds a strong negative reactivity term. Slow, and contaminates the coolant (should require cleanup afterward).

### 8.4 Isolation & Support

- **MSIVs** — isolate the vessel; closure causes the pressurisation transient in §5.3
- **Emergency diesel generators** — AC restoration after loss of offsite power; failure to start = station blackout, RCIC only
- **Station blackout** scenario falls out naturally: no AC → no pumps → coastdown → natural circulation → decay heat vs RCIC

---

## 9. Integration

- **Steam boundary:** output Mekanism steam at the turbine interface so existing turbines/generators keep working. Everything upstream is ours.
- **CC:Tweaked:** `IPeripheral` on the controller — flux, period, rod positions, level, pressure, flow, pump states, SCRAM. Build this **early**: the existing control room becomes the physics test harness.
- **Existing SCADA:** read-only panels must keep working alongside.

---

## 10. Build Order

1. **`ReactorCore` standalone** — kinetics + void/Doppler + decay heat, `coreFlow` as a hardcoded input. Validate startup and SCRAM transients in CSV before anything else.
2. **Nodal flux solve** at 1 Hz, feeding `β_eff` and power weights back.
3. **Multiblock wrapper** — BlockEntity, structure validation on neighbour-change, server-authoritative physics, ~4 Hz client sync (not 20).
4. **Fuel registry + assemblies** — JSON-driven, item-side burnup.
5. **Control rods** — individual drives, S-curve worth, SCRAM.
6. **Pumps + jet pumps** — energy limiting, inertia, GUI slider, CC toggle.
7. **Pressure + saturation + SRVs.**
8. **`IPeripheral`** exposure.
9. **Failure/stress model.**
10. **Suppression pool, ECCS, RPS** — phase two.

Deferred: natural circulation, per-jet-pump asymmetric flow, custom pump models, full RHR heat sink.

---

## Open Questions

- Does assembly **position** weight reactivity (loading patterns as a puzzle), or flat sum? Nodal solve makes positional natural — recommend enabling it.
- Moderator: fixed as water (keeps it a BWR), or selectable? Graphite + water cooling = RBMK, positive void coefficient. Defensible, but a different reactor archetype.
- Coolant types: light water / heavy water / sodium as JSON constants (heat capacity, boiling point, neutronic effect)?
- CC vs manual: last-write-wins, or local/remote lockout? Lockout is more realistic and cheap (one boolean).
- Linear vs cubic pump power law (real centrifugal pumps: flow ∝ speed, power ∝ speed³ — makes 100% flow feel expensive).
