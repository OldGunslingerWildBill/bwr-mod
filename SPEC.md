# Realistic BWR Physics Mod — Full Specification (v2)

**Target:** Minecraft 1.21.1 / NeoForge
**Goal:** Replace Mekanism's fission reactor with a physically-modelled BWR — power emerges from neutronics, not from a commanded burn rate.
**Design philosophy:** The mod provides hardware and physics. The player provides control logic.

---

## 0. Architecture Principle

Hard split between physics and Minecraft:

- **`ReactorCore`** — pure Java, zero Minecraft imports. All state as doubles. Unit-testable, runnable standalone with a `main()` that dumps transients to CSV.
- **`ReactorMultiblockBlockEntity`** — structure validation, NBT persistence, client sync, capability exposure, CC:Tweaked `IPeripheral`. Calls `core.step()` once per tick.

**Do not skip the standalone phase.** Get a believable startup, load change, and SCRAM curve plotting in plain Java before touching NeoForge. Every hour spent here saves three of in-game debugging.

`ReactorCore` exposes `toState()` / `fromState()` returning a plain data record. The BlockEntity handles NBT. The same snapshot serves persistence, client sync, and the CC peripheral readout.

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
ρ = ρ_rods + ρ_void + ρ_doppler + ρ_xenon + ρ_pressure + ρ_fuel + ρ_boron
```

| Term | Sign | Driver |
|---|---|---|
| `ρ_rods` | −ve | Individual rod insertion, S-curve worth |
| `ρ_void` | −ve coeff | Void fraction — f(power, flow, pressure) |
| `ρ_doppler` | −ve coeff | Fuel temperature (√T form) |
| `ρ_xenon` | −ve | Xe-135 concentration |
| `ρ_pressure` | +ve on rise | Pressure collapses voids → adds moderator |
| `ρ_fuel` | — | Aggregate k_inf of loaded assemblies |
| `ρ_boron` | −ve | SLC injection (§9.3) |

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

**What this buys:** centre-vs-periphery flux gradient, rod shadowing, loading patterns mattering, local absorption from tritium rods, visible hot spots around stuck rods.

**Note on β:** β is a nuclear property of the fissioning isotope, not a geometric quantity. What varies spatially is *flux*. Effective core β is the fission-rate-weighted average of each assembly's β — it falls out of the nodal solve as a weighted sum.

### 1.4 Decay Heat

Separate additive term, not part of kinetics. Sum-of-exponentials fit: ~6–7% of rated at shutdown, ~1% at one hour. **This is the driver of every severe accident** — it cannot be switched off, and SCRAM does not save you. Only sustained cooling does.

### 1.5 Xenon

Two coupled ODEs (I-135 → Xe-135). Time constants are hours, so integrate at coarse timestep. Produces the post-shutdown xenon peak (~8–11 h) and xenon-precluded restart.

---

## 2. Fuel

### 2.1 Data-Driven Registry

Fuel types are **JSON/datapack entries**, not hardcoded classes. `ReactorCore` never knows fuel names — only aggregated constants.

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

- Burnup lives on the **item** (data components), not the core slot — enables real fuel shuffling between positions
- End of cycle = core can no longer reach criticality with all rods out
- Mixed cores: flux-weighted average of β and other constants

### 2.4 Own Assembly Model — Mekanism's Won't Work

Mekanism's fuel is a **chemical** fed continuously into a pooled tank; their assemblies are structural blocks with **no per-assembly state** and are bound to their own multiblock validation. Incompatible with item-side burnup, enrichment, and shuffling. Build our own assembly block and fuel item.

**Integration point:** craft assemblies **from Mekanism's fissile fuel / enrichment intermediates**. Players reuse existing ore processing and enrichment infrastructure; LEU vs HEU falls out of how much enrichment they feed in. Avoids writing an entire ore-to-fuel pipeline. Makes Mekanism a hard dependency — acceptable given the target plant.

### 2.5 Tritium Rods

Li-6 + neutron → tritium. Mechanically a **neutron absorber** — eats flux, costs reactivity and power. Real tradeoff: run for power or run for tritium. Production scales with local flux (HEU cores breed faster). Rods deplete as lithium is consumed. Feeds late-game fusion tech.

---

## 3. Control Rods & Drives

### 3.1 Rods

- **Individual** control — BWR practice, one drive per rod, **not** PWR-style ganged banks
- **Cruciform**, sitting in the gap between four fuel assemblies — one rod per 2×2 bundle group. Self-enforcing lattice rule; rod count follows core size.
- Insert from the **bottom** (top of core is steam-voided, rods less effective there)
- Position 0–100%, worth follows an **S-curve** (most effective mid-core where flux peaks)
- Absorber variants: B₄C (standard), hafnium (longer-lived)
- Individual positions feed the nodal solve → **rod shadowing** is visible on the flux map

### 3.2 Rendering

**Rods are not rendered during operation** — the vessel is closed, nobody can see in, and it would be frame time spent on invisible geometry. Rod position exists purely as data (nodal solve + control room display).

Rendering activates **only in refueling state** (§10).

### 3.3 Control Rod Drives (CRD)

**One CRD block per control rod**, placed at the vessel bottom directly beneath its rod. Missing one = multiblock does not form, with an error naming the coordinate.

Each CRD tracks:

| State | Meaning |
|---|---|
| `accumulatorCharge` (0–100%) | Stored hydraulic pressure; a full charge buys one scram without power |
| `powered` | Required for normal withdrawal/insertion and for recharging |
| `waterSupplied` | Also required — see below |
| `health` | Failed drives don't insert regardless |

**CRDs require both power and a water source.** Real BWR CRD hydraulic systems run on demineralised water pumped to pressure, which also serves as continuous purge flow into the vessel. This creates a genuine dependency chain: lose water supply and accumulators cannot recharge, so **scram capability decays even with power intact**. A non-obvious failure mode worth surfacing on the panel.

**Fail-safe design (real BWR philosophy).** Normal rod motion needs power. SCRAM fires from pre-charged accumulators on *loss* of signal — so total loss of power drives rods **in**, not out. The failure mode of the control system is the safe state.

Consequences:
- Station blackout = automatic shutdown, then a decay heat fight with RCIC (which needs no AC)
- A plant sitting unpowered can scram **once** from stored pressure, then cannot move rods until power returns
- A CRD with a depleted accumulator and no power is a **stuck rod** — partial scram arising from neglect, not a dice roll
- Stuck rods produce a visible local hot spot in the flux map, and are exactly what SLC boron (§9.3) answers

**Recharge rate is the main balance knob:** slow enough that repeated scram-restart cycles cost real time, fast enough that normal operation never gates on it.

**Required panel readout:** count of CRDs with charged accumulators — i.e. "how many rods will actually insert if I scram right now."

**Power scale:** cheap individually, but ~56 of them adds up. If they share a bus with recirc pumps, a brownout slows rod motion — a subtle, optional failure mode.

---

## 4. Flow

### 4.1 Model

```
coreFlow = f(Σ pumpActualSpeed) × Σ(jetPumpEfficiency)
```

**Scalar total core flow only** for v1 — no per-jet-pump asymmetry. Deliberate scope cut.

Core flow feeds the void fraction term, closing the power/flow loop — the defining BWR feedback. Flow control lets you change power without moving rods.

Structure the calculation as **"driving head × jet pump efficiency"** so natural circulation (§4.5) can later slot in as an alternate driving head with no restructuring.

### 4.2 Recirculation Pumps

**Block:** tall cylinder, mechanism pipe input + output. No custom model needed for v1 — reskin later.

**Auto-inclusion:** satellite-block pattern. Pumps announce themselves to the controller via capability/BlockEntity lookup on place/break. Invalid pumps (unpowered, disconnected) contribute 0 and drop out **without invalidating the multiblock** — losing one pump reduces flow, it does not SCRAM the plant.

**Energy:** 1 kFE/t minimum, 500 kFE/t maximum.

```
maxAchievableSpeed = energyReceived / 500000
actualTarget       = min(targetSpeedFraction, maxAchievableSpeed)
```

Below 1 kFE/t = pump off. Handles brownouts naturally. **Surface the mismatch in the GUI:** "commanded 80% / limited to 50% (power)".

**Inertia (first-order lag on speed, not energy):**

```
actualSpeed += (targetSpeed − actualSpeed) × (dt / tau)
```

- `tau` ≈ a few seconds
- **Asymmetric tau:** slower on deceleration — flywheel coastdown. One-line change; pump coastdown is the most recognisable behaviour in a loss-of-power event, and gives survivable flow decay instead of an instant drop.

**Control:**
- 0–100% **slider** in pump GUI
- **CC control toggle** in the same GUI — visually obvious who's in charge
- Both paths write one authoritative `targetSpeedFraction`
- Slider greyed out while CC has control
- Slider sets a *target* that ramps, same as CC

### 4.3 Jet Pumps

Physical blocks placed in the core (downcomer region):

- **Small:** 2 blocks long — fits confined spaces
- **Large:** 3 blocks long — more effective flow, bigger footprint (≈1.5× small)
- Place as a single item occupying 2–3 blocks (door/bed pattern)
- **No jet pumps is valid** — flow comes only from direct pump push, significantly reduced

### 4.4 RIP (Reactor Internal Pumps)

- Replace both external pumps and jet pumps entirely
- Count configurable **in steps of 2** (symmetric pairs, ABWR practice), limit ~10
- Flow scales with RIP count × speed
- **Major safety advantage:** no large external recirculation piping → no recirc-line LOCA (§7). A genuine mechanical advantage from geometry — makes RIP a meaningful late-game upgrade, not just "more pumps."

### 4.5 Natural Circulation — DEFERRED

The mode for a BWR with no recirculation pumps at all; lower power density (buoyancy can't match pumped flow — cf. ESBWR).

Deferred because flow becomes an *output* not an input: buoyancy depends on void, which depends on power, which depends on flow. Genuine algebraic loop with known instability regions in real plants.

---

## 5. Coolant & Moderator

**Light water only.** No heavy water, no sodium, no separate moderator block.

This is deliberate: water *is* the moderator, which is exactly why the void coefficient is negative. Fixing it keeps the void coefficient unambiguously negative and makes this a BWR mod rather than a general reactor mod. One fewer axis to balance.

---

## 6. Pressure & Steam

### 6.1 Saturation Coupling

The vessel is saturated — pressure and temperature are **locked by the steam saturation curve**. Track pressure, read temperature off the curve. A fitted correlation over the operating range suffices; no steam tables library.

**Operating point:** ~1150 psig (BWR/6, ABWR), ~286 °C.

### 6.2 Pressure Balance

```
dP/dt ∝ (steam generated) − (steam removed)
```

Generated from core power. Removed via turbine control valves, bypass valves, relief valves. Vessel steam volume sets response sharpness.

### 6.3 Pressure → Reactivity (the payoff)

```
pressure ↑ → voids collapse ↓ → more moderator → reactivity ↑ → power ↑
```

Void fraction becomes `f(power, flow, pressure)`. Makes the classic pressurisation transient **emergent**: MSIV closure → pressure spike → void collapse → power surge. Exactly why real BWRs SCRAM on MSIV closure rather than waiting for a flux trip.

**Numerical caution:** fast positive-feedback-capable loop. Let sub-stepped kinetics see pressure as **constant within a tick**; update pressure once per tick.

### 6.4 Steam Line Pressure — Lumped Volumes, Not Per-Segment

Mekanism pipes move gas by amount with no pressure concept, so custom pressurised tubes are needed. **But do not simulate pressure per pipe segment** — heavy, oscillation-prone, little visible fidelity gain.

- RPV steam dome = one lumped volume; main steam header = a second
- Custom pipes provide **connectivity and validation** (are SRVs on a valid line? is the turbine connected? is discharge underwater?), not pressure-solving nodes
- Convert to Mekanism steam only at the **turbine interface**, so downstream turbines keep working

### 6.5 Pressure Control Equipment

Build in this order — the plant stays non-exploding at each step:

1. Pressure balance + saturation link (readouts work)
2. Pressure → void reactivity coupling (physics payoff)
3. SRVs (safety backstop)
4. Pressure regulator / turbine bypass (normal operation)

- **Turbine control valves / pressure regulator** — PID loop holding pressure at setpoint
- **Turbine bypass valves** — dump steam to condenser when the turbine can't take it. Without these, a turbine trip is immediate overpressure.
- **SRVs** — spring-set, staggered setpoints, discharge to suppression pool. Loud and obvious in-game.

### 6.6 Relief Valve Placement

- Mount on pressurised tubes off the reactor outlet
- **Must discharge underwater** for steam suppression — a validation requirement, not decoration

---

## 7. Overpressure Damage & Failure

Stress accumulation, not hard thresholds.

| Pressure | Behaviour |
|---|---|
| < 1250 psi | No damage |
| 1250–1375 | Stress accumulates, rate scales with overpressure |
| > design limit | Per-tick failure probability, weighted to most-stressed component |

**Stress distribution depends on configuration:**

- **External-loop plants:** recirculation piping, feedwater lines, steam lines
- **RIP plants:** only feedwater and steam lines exposed — survive longer, fail differently

Failures are non-deterministic but not arbitrary: the abused component is the one that goes.

| Break | Consequence |
|---|---|
| **Steam line** | Rapid depressurisation → voids *form* → negative reactivity, power drops — but violent level swell/drop |
| **Recirculation line** | The nasty one: lose core flow *and* inventory simultaneously. The design-basis LOCA that sizes ECCS on jet pump plants. |
| **Feedwater line** | Slower — lose makeup, level declines, ECCS timers start |
| **RPV head failure** | Terminal. Sustained gross overpressure only. |

---

## 8. Severe Accident / Meltdown

**A progression with recovery points, not a binary.** This is the path that makes ECCS matter — without it, every emergency system is decorative.

### 8.1 Escalation Chain

| Stage | State | Recoverable? |
|---|---|---|
| 1 | **Core uncovery** — level below top of active fuel; steam cooling only | Yes, easily |
| 2 | **Cladding heatup** — fuel temp climbing on decay heat | Yes, restore injection |
| 3 | **Zr-water reaction** (>~1200 °C) — exothermic, autocatalytic, generates H₂ | Conditionally — see §8.2 |
| 4 | **Cladding failure** — gap activity release; coolant becomes highly radioactive | Damage is permanent |
| 5 | **Fuel melt** (~2800 °C UO₂) — relocation to lower plenum, corium forms | No |
| 6 | **RPV lower head failure** — corium onto drywell floor | No |
| 7 | **Molten core-concrete interaction** — basemat attack, non-condensible gas, containment pressurisation | No |

**Stage 3 is the threshold.** The Zr reaction generates its own heat faster than decay heat, so the ramp accelerates on its own. This is why severe accidents have a cliff rather than being gradual — and it is the physics behind Fukushima.

### 8.2 Reflood Branching — The Quench Spike

Restoring core spray in stage 3 usually terminates the accident, **but the outcome depends on when**:

- **Early (peak clad ~1200–1500 °C, low oxidation fraction):** clean recovery. Water removes heat far faster than the reaction produces it. This is the design basis and why ECCS response time matters.
- **Late (above ~1800–2000 K):** **quench spike** — documented in the QUENCH experiment series and observed at TMI-2. Thermal shock cracks the brittle oxide layer exposing fresh zirconium, and the flood itself generates a steam surge (the reactant). Result: a *transient escalation* in oxidation and hydrogen production before cooldown. At TMI-2 this produced a containment hydrogen burn.
- **Very late (post-relocation):** you are cooling corium, not fuel.

Reflood is still always the right action — doing nothing is worse. But it is not free.

**Required state to track separately:**
- Peak cladding temperature
- Cumulative oxidation fraction

These two drive the branching, and they are what the control room needs instrumentation for.

### 8.3 Hydrogen

The star mechanic. Zr oxidation produces H₂, which accumulates in containment; above ~4% concentration it is explosive. Gives the Fukushima failure mode: the reactor doesn't destroy the building — the hydrogen does.

Justifies **containment inerting** (real BWR Mark I/II containments are nitrogen-inerted precisely for this) as a build requirement players can neglect at their peril.

Second hydrogen source: the quench spike (§8.2).

### 8.4 Endpoint

Recommended: **contaminated and wrecked plant requiring cleanup**, not a crater. Given suppression pool and containment are modelled anyway, a mess you have to remediate is more interesting than deletion.

---

## 9. Emergency Systems

**All ECCS are separate craftable machines.** Auto-actuation is the player's job via CC:Tweaked — **no built-in hardwired backstop.** Players design their own backup SCRAM and injection logic; redundancy is built, not granted. (Real defence-in-depth is diverse redundant systems, so player-built redundancy is arguably *more* realistic than one hardcoded nanny.)

### 9.1 Injection Systems

| System | Drive | Pressure | Role |
|---|---|---|---|
| **RCIC** | Steam turbine, **no AC needed** | High | Low flow — maintains level against decay heat boiloff. The station blackout workhorse. Exhausts to suppression pool. |
| **HPCI** | Steam turbine, **no AC needed** | High | Much higher flow than RCIC. Consumes steam → affects pressure balance, so use isn't free. Injects into core shroud. |
| **HPCS** | Motor-driven, **needs large power** | High | Higher capacity, simpler control. Dead in a station blackout unless emergency power is built. |
| **LPCS / LPCI (RHR)** | Motor-driven | Low | High volume. Only works **after depressurisation** — which is what makes ADS a real decision. RHR also does suppression pool cooling and shutdown cooling. |

**Emergent design space:** a player with only RCIC survives a blackout but can't handle a large break. A player with HPCS handles the break but only if their emergency power works.

**ADS (Automatic Depressurisation System)** — deliberately opens SRVs to drop pressure so low-pressure systems can inject. The handoff is the interesting mechanic: you must blow the vessel down to let the big pumps work, and that costs you (pool heating, level swell).

### 9.2 Suction Sources

Drawn from **water tanks** or the **suppression pool multiblock**. CC-switchable, consistent with the rest of the control philosophy.

The tradeoff is real:
- **Pool suction = closed loop.** Inject pool water → boils → SRVs discharge back to pool → pool heats up. Unlimited but self-heating.
- **Tank suction = open.** Cooler, doesn't heat the pool, but finite.

This ties the pool temperature limit directly into ECCS strategy. "Swap to pool suction when the tank runs low" is a script someone has to write.

### 9.3 Standby Liquid Control (SLC)

Boron injection — the ATWS answer when rods fail to insert. Strong negative reactivity term. Slow, and contaminates the coolant (requires cleanup afterward).

### 9.4 Core Spray Spargers

**Spray cools uncovered fuel directly; injection has to refill the vessel first.** This is the key mechanical distinction — spray buys time in an uncovered core, flooding recovers it. They must behave differently in the damage model.

Sparger blocks are part of the multiblock, with placement validation:

| Sparger | Elevation |
|---|---|
| **HPCS** | 4 blocks above top of active fuel |
| **LPCS** | 1 block above top of active fuel |

Also required: inside the shroud, and a valid custom-pipe connection to its machine. Clear error messages on misplacement — they teach the geometry.

**Spargers are assembled rings**, built segment by segment around the core perimeter during construction — not single blocks. Two payoffs: they look correct when the head is off (now a rendering moment that matters, §10), and **spray capacity scales with ring completeness**. An incomplete ring should be *degraded*, not invalid — partial coverage is a far more interesting failure mode than binary valid/invalid, and it means damage to a ring segment has graded consequences.

### 9.5 Isolation & Support

- **MSIVs** — isolate the vessel; closure causes the pressurisation transient (§6.3)
- **Emergency diesel generators** — AC restoration after loss of offsite power; failure to start = station blackout, RCIC only
- **Station blackout** falls out naturally: no AC → CRDs scram on loss of power → pumps coast down → decay heat vs RCIC

### 9.6 Peripheral API Is Safety-Critical

Since actuation lives in Lua, every ECCS machine needs deliberate `IPeripheral` design — `start()`, `stop()`, `setFlow()`, plus readbacks for status, suction source, available flow, and whether conditions permit injection (e.g. LPCI refusing above its shutoff head). Design this API up front rather than letting it accrete; the whole plant depends on it.

---

## 10. Refueling

**Physical crane refueling is out of scope** — a whole mod's worth of animation and pathing with no physics payoff.

**GUI-based from inventory**, gated on vessel head removed. Head-off should require the real sequence: subcritical, below temperature and pressure limits. GUI refuses to open otherwise and says why.

**Rendering:** with the head off, the vessel renders **water filled to the top** with the placed rod and assembly models visible below. This is the one occasion anyone looks inside, so it's where the model work pays off. *Optional polish:* faint blue emissive Cherenkov tint on the water when recent fission product decay is present.

**The GUI is the core map.** It's a lattice grid already, so build one grid widget and reuse it:
- Refueling overlay: assembly identity + burnup per slot (burnup visibility is what makes shuffling meaningful)
- Operating overlay: flux map

**Head as tracked state.** "Head off" is a multiblock state affecting whether the vessel can hold pressure at all. Natural state machine: operating / shutdown / refueling.

---

## 11. Persistence & Chunk Behaviour

**Freeze and resume.** On unload, the multiblock saves the status of all connected systems; on reload it resumes exactly where it left off. Deterministic, no chunk ticket overhead.

Must serialise:
- `ReactorCore`: neutron density, all six precursor concentrations, fuel/coolant temps, xenon and iodine, pressure, void fraction, decay heat state, peak clad temp, oxidation fraction
- Per-assembly: burnup, gadolinia remaining (handled by item data components)
- Per-rod: individual positions; per-CRD: accumulator charge, health
- Satellite machines: pump actual speed (mid-coastdown matters), jet pump config, ECCS states, suppression pool temperature

**Consequence, accepted:** a reactor mid-transient resumes mid-transient. Walking away suspends an accident but does not cancel it — the player returns to exactly the same situation, minus all situational awareness and with no ECCS staged in the meantime. Often worse for them.

**Chunk loaders are real safety equipment.** The reactor's state is preserved, but a control computer in an unloaded chunk isn't watching anything.

---

## 12. Suppression Pool

Not a decorative water block. Full model:

- Pool water mass and temperature
- Heat input from SRV discharge and blowdown
- RHR cooling to remove that heat
- **Pool temperature limits** — real plants have heat capacity temperature limits above which continued SRV discharge is unacceptable. The real constraint: an extended transient heats the pool until you're forced into cooldown.

**v1 scope:** SRVs must discharge into a designated water volume to be valid; pool tracks temperature; exceeding a limit reduces suppression effectiveness or alarms. Full RHR-integrated heat sink is phase two.

---

## 13. Integration

- **Steam boundary:** output Mekanism steam at the turbine interface so existing turbines/generators keep working. Everything upstream is ours.
- **Fuel boundary:** assemblies crafted from Mekanism enrichment products (§2.4).
- **CC:Tweaked:** `IPeripheral` on the controller — flux, period, rod positions, charged-accumulator count, level, pressure, flow, pump states, clad temp, oxidation fraction, SCRAM. Build **early**: the existing control room becomes the physics test harness.
- **Existing SCADA:** read-only panels keep working alongside.

---

## 14. Build Order

1. **`ReactorCore` standalone** — kinetics + void/Doppler + decay heat, `coreFlow` hardcoded. Validate startup and SCRAM transients in CSV before anything else.
2. **Nodal flux solve** at 1 Hz, feeding `β_eff` and power weights back.
3. **Multiblock wrapper** — BlockEntity, structure validation on neighbour-change, server-authoritative physics, ~4 Hz client sync (not 20).
4. **Fuel registry + assemblies** — JSON-driven, item-side burnup.
5. **Control rods + CRDs** — individual drives, S-curve worth, fail-safe accumulators, SCRAM.
6. **Pumps + jet pumps** — energy limiting, inertia, GUI slider, CC toggle.
7. **Pressure + saturation + SRVs.**
8. **`IPeripheral`** exposure.
9. **Overpressure stress/failure model.**
10. **Refueling GUI + head state + refuel rendering.**
11. **Severe accident model** — uncovery, Zr reaction, quench branching, hydrogen.
12. **Suppression pool, ECCS machines, spargers, SLC, ADS.**

**Deferred:** natural circulation, per-jet-pump asymmetric flow, custom pump/impeller models, full RHR heat sink, containment detail.

---

## 15. Feedwater

Feedwater is the **normal** level control path — the thing whose loss starts most level transients. Distinct from ECCS, which is the abnormal path.

Two buildable options, mirroring the ECCS tradeoff so the choice feels consistent rather than arbitrary:

| Option | Drive | Character |
|---|---|---|
| **Electric feed pumps** | Motor, large AC draw | Simple, controllable, independent of reactor state. Dead in a blackout. |
| **Reactor feed pump turbines** | Main steam | No electrical bus load — real plants use these because feed pumping is a huge parasitic load. But they need the reactor to be *making steam*. |

**The coupling worth building:** turbine-driven feedwater ties level control to steam production. A pressure transient disturbs feed flow → disturbs level → disturbs power. That coupled loop is where a lot of plant "personality" comes from, and it means the two feedwater options genuinely play differently rather than being a cosmetic choice.

**Level control is the player's, not the mod's.** An earlier draft of this section said level control "should be a PID-style three-element scheme (level + steam flow + feed flow) if you want realism, or simple level-error control for v1." That line predates section 0's rule and directly contradicts it: a three-element controller is exactly the judgement the mod does not make. It is corrected here rather than deleted, because it is the sort of eminently reasonable thing a fresh reader will propose again. The mod ships the pumps; the player writes the loop in Lua, against `bwr_motor_feed_pump` and `bwr_turbine_feed_pump`.

Note that level *swell and shrink* on pressure transients — a pressure drop flashes water to steam and level rises even as inventory falls — which is a classic operator trap worth reproducing.

### 15.1 As built

Both pumps exist, as `bwr:motor_feed_pump` and `bwr:turbine_feed_pump`. They are two registered blocks sharing one block class and one block entity, carrying different `FeedwaterDesign` nameplates — the same pattern the six ECCS machines use, and for the same reason: everything separating them is a number.

- **They run on the shared pump model.** `EccsPump`, `PumpCurve` and `SteamTurbineDrive` were written for emergency cooling and named for it, but nothing in them is about emergency cooling. `EccsPump` now takes a `PumpDesign` interface that both `EccsDesign` and `FeedwaterDesign` implement. There is one pump model in this codebase and it is meant to stay one.
- **Half capacity each,** 15,400 gpm, so two make a plant and losing one halves feedwater rather than ending it. Shutoff head 1400 psi against a 1025 psig dome, so a feed pump genuinely stops delivering into an over-pressurised vessel with nothing refusing anything.
- **The motor is 13 MW** — derived from the duty point, not chosen — which is a little under 1 MFE/t per pump and makes feedwater the largest electrical load in the mod. That is correct: it is the largest load in a real plant, and it is the entire reason the turbine-driven alternative exists.
- **The turbine takes 24 kg/s of admission steam,** about 1.2% of rated steam flow, and fades to nothing as the vessel depressurises because the wheel stops making enough work to overcome its own windage. No cutoff pressure is written anywhere.

**There is no condenser block and there is not going to be one.** The player's Mekanism turbine is the condenser: it already receives this plant's steam through the turbine steam outlet, and the water it hands back is the condensate. A feed pump exposes a plain NeoForge fluid tank on every face, so anything that moves water can fill it, and falls back to a condensate storage tank within 24 blocks — which is also how a real plant starts up, on CST suction before there is any steam to condense. The turbine-driven pump's drive steam leaves the vessel through the same channel and condenses in the same place; unlike RCIC and HPCI it is deliberately **not** dumped into the suppression pool, because those run for minutes into a heat sink sized for it and a feed pump turbine runs continuously at power.

**Feedwater heating is modelled, not built.** There is no heater block, so `FeedwaterHeating` interpolates final feedwater temperature between the condensate temperature at no flow and 215.6 °C at rated flow, on the grounds that extraction steam scales with turbine load and therefore with feedwater flow. This is load-bearing rather than cosmetic: feeding 32 °C condensate at rated flow costs roughly a quarter of rated thermal power in heating duty alone, so a plant with no heater string cannot reach rated power — which is why every real plant has one. It also produces **loss of feedwater heating** for free, where cutting feedwater flow cuts feedwater temperature, adds subcooling, and takes power *up*. A heater block would make this explicit hardware later.

---

## 16. Still Unsketched

- **Turbine / generator interface** — the Mekanism steam handoff, turbine trip as a pressurisation event
- **Containment** — drywell/wetwell pressure and temperature, high-drywell-pressure signal, inerting

## 17. Open Questions

- Does assembly **position** weight reactivity? The nodal solve makes positional natural — recommend enabling it.
- Linear vs. cubic pump power law (real centrifugal: flow ∝ speed, power ∝ speed³ — makes 100% flow feel expensive).
- CRD build tedium: ~56 blocks for a 15×15 core. If excessive in practice, a CRD unit covering a 2×2 rod group cuts it fourfold without losing the stuck-rod mechanic.
- Three-element feedwater control vs. simple level error for v1.
