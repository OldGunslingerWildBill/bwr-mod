# BWR/6 Reference Data — Grounded Constants

**Purpose:** Supplies real plant numbers for the constants `SPEC.md` describes qualitatively.
Every value here is either cited to a primary source or derived from one, so `ReactorCore`
can be tuned against a real plant instead of guessed.

**Primary source:** *Boiling Water Reactor Systems Manual, BWR/6 Design*, U.S. Nuclear
Regulatory Commission, Technical Training Center (rev. 1083 / 0382). NRC ADAMS accession
**ML20090J537**. Cited below as **[TTC §x.y]** using the manual's own section numbering.

Secondary sources are cited inline where the manual gives a curve (figure) rather than a number.

---

## 0. Correction to SPEC §6.1

> SPEC §6.1 currently reads: *"Operating point: ~1150 psig (BWR/6, ABWR), ~286 °C."*

**The pressure is wrong; the temperature is right.** The manual states rated reactor steam
dome pressure as **1025 psig** in at least three independent places [TTC §2.5.2.2, §2.5.3.2,
§3.1.2.1.1]. The two numbers in the spec are also mutually inconsistent — 1150 psig sits at
295 °C on the saturation curve, not 286 °C.

| | Value |
|---|---|
| Rated dome pressure | **1025 psig** (1039.7 psia) |
| Saturation temperature there | **287.4 °C** (549.4 °F) |

Use 1025 psig. This matters beyond cosmetics: the spec's whole overpressure model (§7) is
scaled off the margin between operating pressure and the 1250 psig design limit. At 1150 psig
that margin is 100 psi; the real margin is **225 psi**, and every SRV setpoint, scram
setpoint, and stress-accumulation rate is calibrated against it.

---

## 1. Pressure Data

**Split by who owns it.** The mod implements the *hardware envelope* — properties of metal and
springs that exist whether or not a computer is running. Protection setpoints are the
**player's** to choose and write in Lua. They are listed below only as a record of what one
real plant picked, and must not ship as mod behaviour.

### Hardware envelope — the mod implements this

| Pressure (psig) | Physical meaning |
|---|---|
| 1025 | Rated operating dome pressure [TTC §2.5.2.2] |
| 1103 / 1113 / 1123 | SRV spring settings — 1 valve / +9 / +9, 19 total [TTC §3.1.3] |
| 1250 | Vessel design pressure [TTC §2.2] |
| 1325 | Must not be exceeded with irradiated fuel present [TTC §2.2] |
| 1375 | ASME 110% × design — code transient limit [TTC §2.5] |

**SRVs are a legitimate exception to SPEC §9's no-backstop rule.** They are spring-set
mechanical valves that lift at a physical pressure with no logic involved. That is metal, not
a safety system. A player who writes no code at all still gets SRV action — and equally,
cannot suppress it. Implementing them is hardware modelling, not a hidden nanny.

**The 1250 / 1375 pair in SPEC §7 is already correct.** The manual confirms 1250 psig design
pressure and 110% × 1250 = 1375 psig as the code limit. Keep those thresholds.

**Pump shutoff head is physics; the injection permissive is not.** A low-pressure pump
physically cannot deliver against vessel pressure above its shutoff head — that belongs in the
model, and SPEC §9.6 already calls for it as a readback. The real plant additionally interlocks
LPCS/RHR injection at 500 psig [TTC §3.1.3], but that is a chosen setpoint, not a physical
limit. Model the head; let the player decide whether to add an interlock and where.

### Protection setpoints — the player implements these

Reference only, from [TTC §3.1.3, §7.3]. Recorded here so the design intent behind the physics
is legible, **not** as values the mod should ship.

| Pressure (psig) | What this plant chose to do |
|---|---|
| 135 | RHR shutdown-cooling isolation |
| 500 | Permissive for LPCS / RHR injection |
| 1040 | High pressure alarm |
| 1065 | High pressure reactor scram |
| 1130 | Recirculation pump trip |

One of these is worth understanding as physics even though the trip itself is procedure: the
1130 psig recirc pump trip exists "to insert negative reactivity by means of void formation,
assuming the reactor fails to scram on high pressure" [TTC §3.1.3]. It runs SPEC §6.3's
pressure→void coupling backwards — deliberately killing flow to *make* voids. The mod does not
implement that trip, but if the coupling is correct the behaviour is available for a player to
discover and build. It is also a good end-to-end test that the sign conventions are right.

---

## 2. Saturation Curve (SPEC §6.1 math)

SPEC §6.1 asks for "a fitted correlation over the operating range." Use this one:

```
T_sat(°F)  = 115.1 · P(psia)^0.225
T_sat(°C)  = (115.1 · P(psia)^0.225 − 32) / 1.8
P(psia)    = (T_sat(°F) / 115.1)^(1/0.225)     // inverse, if ever needed
```

Validated against Keenan & Keyes steam tables:

| psia | Table T_sat (°F) | Fit (°F) | Error |
|---|---|---|---|
| 14.7 | 212.00 | 210.71 | −1.29 |
| 500 | 467.01 | 465.95 | −1.06 |
| 800 | 518.23 | 517.93 | −0.30 |
| 1000 | 544.58 | 544.60 | **+0.02** |
| 1100 | 556.28 | 556.40 | +0.12 |
| 1250 | 572.42 | 572.64 | +0.22 |
| 1400 | 587.07 | 587.43 | +0.36 |

**Error is under 0.4 °F across the entire 800–1400 psia band** — i.e. essentially exact
everywhere the reactor actually operates. It degrades to ~3.5 °F near 50–100 psia, which only
matters during deep cooldown, and is still far better than the model needs.

One power call per tick, no steam-table library, no interpolation arrays. This closes SPEC §6.1.

---

## 3. Reactivity Coefficients

The manual establishes the *signs and shapes* rigorously [TTC §1.7.2.1] but presents magnitudes
only as figures (1.7-5, 1.7-6), which don't survive OCR. Signs and behaviours from the manual;
magnitudes from the open literature.

| Coefficient | Value | Source |
|---|---|---|
| Void, α_v | **−7.0×10⁻⁴ Δk/k per % void** (operating BWRs) | literature |
| Doppler, α_D | **−1.6×10⁻⁵ Δk/k per °C** (up to −3.7×10⁻⁵ in newer designs) | literature |
| Moderator temp, α_T | negative; more negative as temperature rises | [TTC §1.7.2.1.1] |
| Boron | **8.3×10⁻⁵ Δk/k per ppm** (derived, §6 below) | [TTC §7.4] |

**Behaviours the manual specifies that the model should reproduce:**

- **Void coefficient is non-linear in void fraction and steepens with void** [TTC §1.7.2.1.2].
  The manual's own worked example: at 10% void, +1% void removes ~1.1% of the water; at 70%
  void, +1% void removes ~3.45% of the water. So `α_v` should scale roughly as `1/(1−α)`
  rather than being a constant — a single linear coefficient will understate feedback at the
  high-void top of the core, which is exactly where BWR behaviour lives.
- **Doppler is the prompt term.** The manual identifies it as "the prompt negative reactivity
  addition which acts to terminate a power increase" [TTC §1.7.2.1.3]. It must respond on the
  fuel-temperature time constant with no delay — this is what stops a prompt excursion, and if
  it's lagged the Pu/MOX tier will behave wrongly.
- **Both coefficients drift with burnup, dominated by control rod withdrawal** [TTC §1.7.2.1.2],
  generally becoming *less* negative through the cycle. A late-cycle core is twitchier than a
  fresh one. Cheap to model as a burnup-dependent scale on `α_v`, and it gives the fuel cycle
  a personality arc.

---

## 4. Delayed Neutron Data (SPEC §1.1)

SPEC §1.1 calls for a "standard U-235 six-group set" and correctly quotes the λ range
(0.0124 → 3.01 /s). Here is that set explicitly — Keepin thermal-fission data, the standard
reference for point kinetics.

**U-235 (thermal):**

| Group | λᵢ (s⁻¹) | βᵢ |
|---|---|---|
| 1 | 0.0124 | 0.000215 |
| 2 | 0.0305 | 0.001424 |
| 3 | 0.111 | 0.001274 |
| 4 | 0.301 | 0.002568 |
| 5 | 1.140 | 0.000748 |
| 6 | 3.010 | 0.000273 |
| | **Σβ** | **0.006502** |

**Pu-239 (thermal):**

| Group | λᵢ (s⁻¹) | βᵢ |
|---|---|---|
| 1 | 0.0128 | 0.0000724 |
| 2 | 0.0301 | 0.000626 |
| 3 | 0.124 | 0.000443 |
| 4 | 0.325 | 0.000685 |
| 5 | 1.120 | 0.000181 |
| 6 | 2.690 | 0.000092 |
| | **Σβ** | **0.002099** |

**These confirm SPEC §2.2's fuel table is correct.** The spec lists LEU/HEU at ~0.0065,
plutonium at ~0.0021, and U-233 at ~0.0027. Keepin gives 0.006502, 0.002099, and 0.00266
respectively. The Pu danger really is a factor of ~3.1 in control margin, exactly as §2.2
claims — nothing needs adjusting, and the per-fuel β values can be entered as literal
physical constants in the JSON registry rather than balance numbers.

For mixed cores, weight per-group βᵢ by fission rate, not just total β — SPEC §1.3's note on
β_eff already says this, and it matters because the group *spectra* differ between isotopes,
not only the totals.

---

## 5. Plant Configuration (BWR/6)

From Table 1.8-1 [TTC §1.8] and the systems chapters. OCR scrambled the table columns; values
below are the BWR/6 column cross-checked against the systems text.

| Parameter | Value |
|---|---|
| Rated thermal power | 3579 MWt |
| Fuel assemblies | 748 |
| Control rods / CRDs | **177** |
| Scram accumulators | 177 (one per rod) [TTC §2.3] |
| Vessel inside diameter | 238 in |
| Rated core flow | ~104 ×10⁶ lb/hr |
| Rated steam flow | 15.4 ×10⁶ lb/hr |
| Jet pumps | **20**, in 10 assemblies of 2 [TTC §2.1.2.2.6] |
| Recirculation pumps | 2 |
| Safety/relief valves | 19 |
| Containment design pressure | 15 psig |

**748 assemblies ÷ 177 rods = 4.23.** This validates SPEC §3.1's cruciform lattice rule (one
rod per 2×2 bundle group) against the real geometry — the ratio is 4 plus periphery bundles
that have no rod. The spec's "self-enforcing lattice rule, rod count follows core size" is
exactly how a real core is laid out.

**Jet pumps come in pairs sharing a common inlet riser** [TTC §2.1.2.2.6]. SPEC §4.4 already
requires RIP count in steps of 2 for ABWR symmetry; the same pairing is true of jet pumps on
the BWR/6, so the "steps of 2" constraint could apply to both without inventing a rule.

---

## 6. Control Rod Drives (SPEC §3.3)

The manual strongly validates the spec's CRD design. Confirmed points:

- **177 CRDMs, bottom-mounted on the vessel head** [TTC §2.3.2.12] — SPEC §3.3 ✓
- **One accumulator per rod, one scram each** [TTC §2.3] — SPEC §3.3 ✓
- **Drives remain operative with the vessel head removed** [TTC §2.3.2.12] — relevant to
  SPEC §10's refueling state machine
- **Rod motion is hydraulic and needs a pumped water supply** — SPEC §3.3's non-obvious
  "CRDs require both power and water" dependency is real and correctly reasoned

| Parameter | Value | Source |
|---|---|---|
| CRD pump discharge (charging header) | 1750–1800 psig nominal | [TTC §2.3.2] |
| CRD pump capacity | 220 gpm at 1475 psig | [TTC §2.3.2] |
| Accumulator volume | 48 gal | [TTC §2.3.2] |
| Normal rod drive speed | **3 in/sec nominal** | [TTC §2.3.2] |
| Notches per full stroke | 25 (positions 00–48, even) | [TTC §2.3.2.12] |
| Scram discharge volume back pressure limit | ≤ 65 psig, to keep scram times in spec | [TTC §2.3.2.10] |

**Rod position should be quantised to notch positions 00–48, not continuous 0–100%.**
SPEC §3.1 specifies "position 0–100%." Real BWR rods move in discrete notches: the index tube
has 25 notches [TTC §2.3.2.12], giving **25 discrete positions numbered 00 to 48 in steps of
two** — 00 fully inserted, 48 fully withdrawn. This is what the Full Core Display reads out in
a real control room, and it is why rods are described as "at 48" rather than "at 100%."

Adopt the real numbering rather than a percentage. Three reasons: it is what an operator
actually sees; it makes the differential-rod-worth-per-notch curve [TTC §1.7.2.2] the natural
way to express S-curve worth, since the manual defines rod worth *per notch* and builds the
integral worth curve by summing notches; and integer notch positions are cheaper and less
error-prone to sync, serialise, and display on a CC panel than a float percentage.

**The stuck-rod design criterion is real:** the core must be able to go subcritical "with the
control rod of the highest worth fully withdrawn" [TTC §2.2-16]. That is the N−1 stuck-rod
criterion, and it's the design rule SPEC §3.3's stuck-rod mechanic should be balanced against —
a single stuck rod must be survivable, two should not be guaranteed.

---

## 7. Standby Liquid Control (SPEC §9.3)

The manual quantifies SLC precisely [TTC §7.4], which turns SPEC §9.3's "strong negative
reactivity term, slow" into real numbers:

| Parameter | Value |
|---|---|
| Boron for required shutdown | **600 ppm → 0.05 Δk/k** shutdown margin |
| Design concentration | 750 ppm (600 + 25% for imperfect mixing and leakage) |
| Injection rate | **8–20 ppm per minute** |
| Solution | sodium pentaborate |

**Derived boron worth: 0.05 / 600 = 8.3×10⁻⁵ Δk/k per ppm.**

**Derived injection time: 750 ppm ÷ ~14 ppm/min ≈ 54 minutes to full design concentration.**
That is what "slow" means in SPEC §9.3 — SLC is an hour-scale system, not a minute-scale one.
Reaching just the 600 ppm shutdown concentration takes ~43 min at the mid rate. For an ATWS
with stuck rods, the player is committing to nearly an hour of degraded operation while boron
builds, which is a far more interesting decision than an instant reactivity button.

The 25% mixing margin is worth modelling as-is: boron worth should ramp slightly behind
injected mass, because the manual's own margin exists to cover imperfect core mixing.

---

## 8. Water Level (SPEC §15)

Level trip setpoints, referenced to instrument zero at 530 in above vessel zero
[TTC §3.1.2.1]. Top of active fuel (TAF) is at **363 in above vessel zero**, i.e. **−167 in**
on the instrument-zero scale.

| Level | Elevation (in, rel. instrument zero) | Typical function |
|---|---|---|
| Level 8 | +55 | Trips feedwater / RCIC / HPCI turbines (high level) |
| Level 7 | +40 | |
| Level 5 | ≈ +36 | |
| Level 4 | +32 | |
| Level 3 | **+10** | **Reactor SCRAM**, ADS permissive confirm |
| Level 2 | **−36** | RCIC / HPCS initiation, recirc pump trip |
| Level 1 | **−148** | ADS / low-pressure ECCS initiation |
| TAF | −167 | Core uncovery begins (SPEC §8.1 stage 1) |

Normal narrow-range indication runs 0 to +60 in. Note the ordering that makes the accident
sequence legible: **scram at +10, injection at −36, ADS at −148, uncovery at −167.** The gap
between Level 1 and TAF is only 19 inches — once you reach ADS initiation you are very close
to uncovering the core, which is why the spec's §8 escalation chain should feel tight rather
than leisurely.

The manual is explicit that narrow-range instruments are calibrated for saturated conditions
at 1025 psig and 135 °F drywell temperature [TTC §3.1.2.1.1]. That calibration dependency is
the physical basis for the level swell/shrink error SPEC §15 wants to reproduce — the
instrument reads wrong during pressure transients because its reference leg assumes a density
that no longer holds.

---

## 9. Flow and Capacity

| System | Capacity | Source |
|---|---|---|
| Recirculation pump | 35,400 gpm at 865 ft head, each | [TTC §2.4] |
| Recirc pump speeds | two-speed: 60 Hz normal, **15 Hz (25% speed)** for startup | [TTC §2.4] |
| RCIC | **700 gpm** (flow controller setpoint) | [TTC §2.7] |
| RCIC min-flow bypass | opens below 150 gpm | [TTC §2.7] |
| LPCS min-flow | 1200 gpm | [TTC §6.x] |
| RWCU cleanup flow | ~400 gpm | [TTC §2.9] |

**The 25% low-speed startup mode is worth having.** Recirc pumps run on a 15 Hz motor-generator
set at low power specifically to avoid cavitating the pumps, jet pumps, and flow control valves
[TTC §2.4]. This gives SPEC §4.2's pump model a natural two-regime structure and a real reason
why you can't just run pumps at any speed during startup — a constraint that generates
operator procedure rather than arbitrary limits.

---

## 10. Summary — What the Manual Confirms

The physics in `SPEC.md` holds up well against the primary source. Confirmed without change:

- Negative void and Doppler coefficients as the defining BWR characteristics (§1.2)
- Doppler as the prompt-acting excursion terminator (§1.2)
- Per-fuel β values, including the Pu factor-of-3 control margin loss (§2.2)
- Bottom-entry cruciform rods, one drive per rod, S-curve worth peaking mid-core (§3.1)
- Individual CRD accumulators, one scram each, dual power+water dependency (§3.3)
- Rod worth higher at core centre than periphery — the payoff of the nodal solve (§1.3)
- 1250 psig damage threshold and 1375 psig code limit (§7)
- Low-pressure ECCS gated behind depressurisation (§9.1)
- Stuck-rod-survivable design criterion (§3.3)

**One correction:** operating pressure is 1025 psig, not 1150 (§6.1).

**Three refinements worth adopting:**

1. Quantise rod position to notch positions 00–48 (25 steps of two) rather than 0–100% (§3.1)
2. Make `α_v` void-fraction-dependent rather than a constant (§1.2)
3. Implement SRV spring settings and pump shutoff head as hardware; leave every trip setpoint
   to the player (§1, §6.5, §9.6)

---

## 11. Phase J: pressure suppression and RHR scenario bases

The Phase J tests compare a deliberately lumped, two-airspace game model with
published BWR behavior. These sources support the **direction and approximate
scale** of the transients; they do not validate a plant-specific safety analysis.

| Basis | Source and application |
|---|---|
| Browns Ferry Mark I calculated LOCA peaks: drywell **49.6 psig**, suppression chamber **27 psig**; design internal pressure **56 psig** | NRC/GE [Advanced Technology Manual, Table 6.2-1](https://adamswebsearch2.nrc.gov/webSearch2/main.jsp?AccessionNumber=ML14140A181). The two-volume §4.1 DBA compares separate drywell and wetwell peaks with honest ranges of **40–60** and **20–35 psig**. This remains a simplified game transient, not plant validation. |
| Browns Ferry Mark I free air volumes: drywell **159,000 ft³**, suppression chamber **119,000 ft³** | Same NRC/GE [Table 6.2-1](https://adamswebsearch2.nrc.gov/webSearch2/main.jsp?AccessionNumber=ML14140A181). Their **159:119** ratio splits the controller-measured or standalone **8,000 m³** total into drywell and wetwell nodes. |
| Mark I pressure-suppression vents discharge about **four feet** below the pool surface | NRC [Generic Issue 193](https://www.nrc.gov/sr0933/section-3-new-generic-issues/issue-193-bwr-eccs-suction-concerns) describes eight large vents and downcomer outlets about four feet submerged. The game uses the hydrostatic head **11.96 kPa ≈ 1.74 psi** before drywell-to-pool flow, with a **12 s** effective vent transit calibration. That time constant and wall/pool heat-transfer coefficients are model calibrations, not measured plant coefficients. |
| Subcooled pool condenses SRV and blowdown steam; sustained heating can degrade stable condensation | NRC [NUREG-0783](https://www.nrc.gov/reading-rm/doc-collections/nuregs/staff/sr0783/index) and [Issue 108](https://www.nrc.gov/sr0933/section-3-new-generic-issues/issue-108-bwr-suppression-pool-temperature-limits). §§4.1–4.3 assert rising pool temperature, reduced condensation and rising containment pressure without heat removal. These publications do not give a universal pool-temperature limit; this model therefore exposes subcooling rather than claiming one. |
| RHR modes include suppression pool cooling, containment spray, LPCI and shutdown cooling | NRC [Issue C-9](https://www.nrc.gov/sr0933/section-2-task-action-plan-items/item-c-9-rhr-heat-exchanger-tube-failures). §§4.2–4.4 use the existing RHR exchanger for pool and vessel heat removal. |
| BWR shutdown-cooling isolation at **135 psig**; overpressure of low-pressure interfacing lines can rupture piping outside containment | NRC [inspection attachment](https://www.nrc.gov/reading-rm/doc-collections/insp-manual/manual-chapter/mc0308g-att3.pdf) and [Issue 105](https://www.nrc.gov/sr0933/section-3-new-generic-issues/issue-105-interfacing-systems-loca-lwrs). The 135 psig figure is a representative lineup rating. The game deliberately omits an automatic interlock; exceeding it creates an outside break. |
| A BWR shutdown case brought the unit to cold shutdown within **24 h** | NRC [Generic Letter 80-27 / IEB 80-07](https://www.nrc.gov/reading-rm/doc-collections/gen-comm/gen-letters/1980/gl80027). §4.4 asserts less than 100 °C within 24 h after a safe 130-psig RHR lineup. The specific model cooldown time is not a universal BWR requirement. |
| RCIC and SRV discharge heat into the suppression pool during blackout | NRC [NUREG-0783](https://www.nrc.gov/reading-rm/doc-collections/nuregs/staff/sr0783/index) and [NUREG/IA-0440](https://www.nrc.gov/regulations-legislation/nureg-series-publications/publications-resulting-from-international-agreements/ia0440). The §4.3 steam history is a scenario input, not a reconstructed Fukushima unit history. |
| Steam can inert a hydrogen/air mixture; condensing it with sprays can expose a flammable mixture | Sandia/NRC [NUREG/CR-5586](https://www.osti.gov/servlets/purl/6343479) discusses steam near a **55%** inerting limit, and Sandia's [Containment Performance training](https://www.osti.gov/servlets/purl/1312666) shows spray condensation moving an inerted mixture into the flammable region. The core uses a single **55 vol%** threshold, a simplification of the composition-dependent Shapiro diagram. |

The model uses a 30-second long-term step and a 0.25-second initial blowdown
step in these tests. The comparison runs with a raised structural-failure
threshold to show the **potential** pressure rise over 24 h without aborting
at containment failure; this is a diagnostic counterfactual, not a prediction
that an intact Mark I would hold that pressure.

## 12. Vessel steel and shutdown cooling correction

| Model value | Primary basis and limitation |
|---|---|
| Vessel shell **500,000 kg** | The Swedish government [1974 BWR reference design report](https://lagen.nu/sou/1974:56) lists an approximately **500-tonne** BWR reactor vessel. This is a representative order of magnitude, not the mass of the BWR/6 training-manual vessel. |
| Wetted internals **100,000 kg** | NRC [NUREG-0612, p. 3-3](https://www.govinfo.gov/content/pkg/GOVPUB-Y3_N88-PURL-gpo80911/pdf/GOVPUB-Y3_N88-PURL-gpo80911.pdf) gives **20–40 tons** for BWR steam dryers and **20–75 tons** for moisture separators. The 100-tonne node also stands in for shroud and supports; it is an explicit lumped estimate. |
| Steel specific heat **500 J/(kg K)** | NIST's [austenitic stainless-steel properties table](https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=913306) reports **500 J/(kg K)**. The shell is low-alloy steel rather than this exact alloy, so one constant is a thermal-capacity approximation. The **180-s** metal-to-water time constant is a model calibration, not a sourced material property. |
| RHR exchanger **27.1 MW** per modeled unit | EPRI's [BWR RHR exchanger thermal-performance example](https://restservice.epri.com/publicdownload/000000000001012129/0/Product) lists **92.5 million Btu/h** required heat transfer, or **27.1 MW**. This replaces the uncited 100-MW single-exchanger cap. A plant-specific comparison: [Fermi 2 UFSAR §5.5.7, NRC ADAMS ML16165A456](https://adamswebsearch2.nrc.gov/webSearch2/main.jsp?AccessionNumber=ML16165A456) gives **41.6 million Btu/h**, or **12.2 MW**, for shutdown-cooling duty and says torus cooling is the more limiting duty. The 27.1-MW game block is therefore a representative bound, not Fermi's installed rating. |
| Fast cooldown threshold **100 °F/h = 55.56 °C/h** | NRC [Fermi-related Part 21 report 1995-033](https://www.nrc.gov/regulations-legislation/reports-associated-with-events/part-21-reports/1995/1995033) identifies an exceeded **100 °F/h** Technical Specification cooldown limit and evaluates vessel/nozzle fatigue usage. The game adds accumulated thermal-stress life to the existing pressure-boundary bookkeeping above this rate. It does not install a permissive or isolation. The damage rate is a named game calibration, not a structural fracture analysis. |

## 13. Phase M: grid tie, metering and synchronization (spec only)

Added for `GRID-TIE-SPEC.md`, step 0, checked 2026-10-14. These are design
bases, not implemented features or executed Phase M acceptance results.

| ID / value or behavior | Primary source and application |
| --- | --- |
| **M-SYNC**: reference closing window **±10 electrical degrees**, **±0.067 Hz** slip | SEL, Thompson and Ravikumar, [New Developments in Generator Synchronizing Systems](https://cdn.selinc.com/assets/Literature/Publications/Technical%20Papers/6479_NewDevelopments_MT_20110210_Web.pdf), 2011, §II.A printed p.1 (PDF p.2). SEL attributes these limits to IEEE C50.12/C50.13 and describes possible mechanical and winding damage from faulty synchronization. The IEEE texts were not independently inspected. This is not a universal damage-onset/severity law. Phase M's damage coefficient and normalized condition scale are explicitly game calibration; no sync-check interlock or voltage model is adopted. |
| **M-SPEED**: **rpm = 120 × Hz / poles** | STAMFORD, [Industrial Ratings Book, Edition 2](https://www.stamford-avk.com/sites/stamfordavk/files/2020-05/STAMFORD-Industrial-Ratings-Book-2.pdf), useful formulae p.5. Choosing **four poles / 50 Hz** preserves the mod's existing **1500 rpm**. Derived slip is `rpm/30 - 50`; derived phase rate is `360 × slip` electrical degrees per second. The slip-window speed difference is **2.01 rpm**. This is a consistent game reference, not a claim about a specific BWR generator's electrical design. |
| **M-LF**: recirculation changes void/reactivity and hence BWR power; representative **60–100%** thermal-power range | IAEA NP-T-3.23, [Non-baseload Operation in Nuclear Power Plants](https://pub.iaea.org/MTCD/Publications/PDF/P1756_web.pdf), 2018, §5.4.1.2 printed p.74 (PDF p.86). Its German BWR example supports that range at fixed rod positions, with blades extending the range. It supports the requested **100 → 70 → 100%** test mechanism, not a universal ramp rate or test tolerance. |
| **M-XE**: hours-long iodine/xenon response and operator compensation | NRC, [BWR Generic Fundamentals: Fission Product Poisons](https://www.nrc.gov/sites/default/files/doc_library/cdn/legacy/reactors/operator-licensing/history-rulemaking-activities/generic-fundamentals-examinations/bwr/bwr-files/292006.pdf), November 2020, B2559 p.19 and B2361/B2964 p.51, including answer keys. The reduced-power example still has xenon falling toward equilibrium after **20 h at 70%**, following a **1 h** reduction from equilibrium full power. A separate **70 → 100%** example over **2 h** calls for initial rod insertion then withdrawal during the following **12 h** to compensate xenon. These educational cases establish the delayed behavior, not plant limits or the game's hold deadlines. |
| **M-FE**: integer FE transfers with simulation; no topology/demand-priority query | NeoForge [IEnergyStorage on the 1.21.1 branch](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/energy/IEnergyStorage.java), matching the project's API generation. Metered throughput is observable; unseen unmet demand and base-first allocation on arbitrary foreign cable networks are not. This is an API-derived limitation, not a real electrical-network claim. |

Preserved code parameters, independently distinguished from the physical sources:

| Value | Repository basis / derived use |
| --- | --- |
| **13.4 W per FE/t** | `mod/.../eccs/EccsPower.java`: existing 6.7 MW / 500,000 FE/t mapping. Phase M preserves it. At 20 simulation ticks/s, **1 FE = 0.67 J**. FE is a game unit, not a physical measurement standard. |
| **1500 MW**, **0.985 efficiency** | `core/.../turbine/PowerTurbine.java`, existing rating and efficiency; not recalibrated by Phase M. Rated delivery is approximately **111,940,299 FE/t**. |
| **2,000,000,000 FE** generator buffer | `mod/.../power/PowerModuleBlockEntity.java`. Derived stored energy **0.372222 MWh**; empty-to-full charging headroom at rated production **0.893333 s**. Actual headroom depends on charge and other loads. |
| **1500 rpm**, former **8 s** RPM relaxation | `mod/.../power/PowerTrain.java`, baseline work-dependent speed approximation. Review R1 replaces the relaxation with rotor dynamics while preserving rated speed. The old approximation does not model load-rejection overspeed. |
| **H = 5 s**, nominal **7.5 GJ**, **I ≈ 607,927 kg m²** | Explicit **game calibration**, not sourced manufacturer data. Derive inertia with `I = 2*H*1.5e9/(1500*2*pi/60)^2`. The nominal 2 HP / 4 LP / 1 generator train allocates 10% to each HP and LP module and 40% to the generator; sum installed inertias. The allocation and use of a MW base are game choices. |
| Rated mechanical loss **0.5%** of the shaft base, proportional to speed squared | Explicit **game calibration**: nominal 7.5 MW at 1500 rpm. Loss energy is bounded by available energy. Conversion efficiency remains the separate existing 0.985 factor. |
| Overspeed damage above **110%** speed; accumulation `0.1*max(0,rpm/1650-1)` per second | Explicit **game calibration**, including threshold and severity, not a manufacturer trip or fatigue curve. Damage caps at 1 and never operates a valve/breaker. No real overspeed-protection design claim is made. |

`GRID-TIE-SPEC.md` labels demand-curve defaults, configurable maximum, list/depth
limits, meter time windows, bad-close damage severity and acceptance durations/
tolerances as **game or engineering choices**, not sourced plant settings. Its
FE/MWh equations are dimensional conversions from the preserved mapping.

Review R1 moves TODO-5 B6 into Phase M, before synchronization. The torque/inertia
model must distinguish generated electricity, delivered FE and buffer changes
when the export breaker opens under load. The calibration above is declared in
advance for implementation/testing; it is not an executed validation result.
See `GRID-TIE-SPEC.md` §9.1 for the identified acceptance-contract conflicts.
