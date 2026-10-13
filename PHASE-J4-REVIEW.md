# J-fix4 review: two-volume Mark I containment

The core atmosphere now tracks drywell and wetwell steam, N2, O2, H2, other gas,
gas temperature, wall temperature, pressure, airborne activity, and peak pressure
separately. The default free-volume ratio is 159,000:119,000 ft3 from Browns
Ferry's Mark I containment. Pressure above the wetwell plus the four-foot vent
submergence head sends drywell gas through the downcomers. Steam reaches the
suppression pool; noncondensables enter the wetwell airspace. Vacuum breakers
admit reverse gas flow when the wetwell has higher pressure. Walls and the pool
surface condense steam at finite heat-transfer rates. Snapshot v4 preserves
the v1-v3 load path. `REFERENCE-DATA.md` gives primary sources and distinguishes
the cited geometry from calibrated transfer coefficients.

The mod routes breaks to the drywell, uncondensed pool steam to the wetwell,
and sprays to the airspace containing their header. Reactor/containment readouts,
CC peripherals, and signal adapters expose both pressures. The representative
pressure getter remains the higher current node pressure for old callers.

## Six scenario timelines

Values are from `:core:acceptance --args='ContainmentSteamTest ContainmentHydrogenTest ContainmentStructurePhysicsTest RhrContainmentScenarioTest'`.
The scenario class passed 6/6; the full focused invocation fails in the unrelated
legacy assertion described below.

1. **Design-basis recirculation LOCA.** At 0/30/60/120/300 s, drywell pressure
   was 0/47.9/39.2/28.4/19.7 psig and wetwell pressure was
   0/17.6/22.4/24.0/19.8 psig. Peaks: **48.7 drywell, 24.1 wetwell psig**;
   maximum break 1,005 kg/s; pool 36.2 C. The original DBA acceptance was
   strengthened from the interim J-fix1 `>4` bound to 40-60 psig drywell and
   20-35 psig wetwell, bracketing the cited 49.6/27 psig reference.
2. **24-hour LOCA.** At 0/6/12/18/24 h without RHR, dry/wet psig was
   19.3/17.9, 22.5/22.4, 64.6/64.5, 111.7/111.6, 169.5/169.3;
   pool reached 180.9 C; node peaks were 169.5/169.5 psig. With RHR,
   dry/wet was 19.3/17.9, 10.4/10.4, 10.2/9.1, 9.4/7.6, 8.7/6.9;
   pool ended 63.6 C; peaks 48.7/24.1 psig. Venting from 12 h gave
   29.4/25.1 psig and pool 144.7 C at 24 h.
3. **Station blackout, RCIC/SRV exhaust, restored RHR.** At 0/2/4/6 h,
   dry/wet psig was 0.5/0, 2.3/2.3, 20.3/19.8, 70.3/69.8;
   pool reached 146.1 C. At 7/9 h after RHR return it was 56.2/54.5 and
   38.8/37.1 psig; pool fell to 124.2 C. Peaks: 70.3/70.5 psig.
4. **Shutdown cooling and high-pressure lineup.** Full RHR cooled the
   vessel 178.1 to 100.0 C in 3.89 h, with 27.1 MW peak exchanger duty,
   25.3 C/h peak cooldown, and no thermal-stress damage. Throttled RHR took
   10.00 h, with 22.8 MW peak duty, 9.9 C/h peak rate, and no damage.
   High-pressure lineup: 1,025 psig vessel, 135 psig rating, 1,050 kg/s
   interfacing-systems LOCA. This scenario has no containment pressure
   trajectory; the separate dry/wet model is not exercised in this test.
5. **Steam inerting and spray-induced hydrogen burn.** Before spray: 85.2%
   steam, 2.1% H2, 2.7% O2; dry/wet 151.3/109.7 psig, no burns. After
   133.1 s of spray: 64.7% steam, one burn, peak dry/wet 151.4/117.4 psig.
6. **v2 save migration.** The legacy load started with no steam and 56.9 C
   gas. After 60 s the representative and both node pressures remained
   approximately 0 psig. Separate v3-to-v4 and v4 round-trip unit tests pass.

## Verification and known failure

- `:mod:compileJava` passed.
- `:mod:runTurbineGameTest :mod:checkNoProtectionLogic` passed: **195/195**
  GameTests; protection scan found no protection logic in 529 mod files.
- `py tools-audit-assets.py` found **0 problems**.
- On the later persistent-world run, the new wetwell spray GameTest inherited
  the controller's saved pool water and submerged its header coordinate. Its
  fixture now explicitly drains the pool before adding shallow water; the
  later 196/196 run (including L1) passed. The wetwell assertion was not
  weakened.
- `:core:acceptance` failed: **281/282** in the earlier full compile; after
  compiling all latest tests, the focused run was **36/37**. All six scenarios
  and all 13 steam unit tests passed.
- Existing, unchanged `ContainmentStructurePhysicsTest.test03` fails:
  `hydrogen did not accumulate after the boundary was repaired: expected
  greater than 1.37774, got 0.428661`. With the smaller drywell gas volume,
  its 0.5 kg/s release reaches the existing local 8% ignition point inside
  its 60 s observation window and burns. The test assumes continued
  accumulation from the former single volume. The project owner explicitly
  directed that this test remain unchanged and that the failing suite be
  reported. No production behavior was altered merely to satisfy the old
  assertion.

## RVU baseline

Stage 10's `Missing corner BlockPos{x=149, y=191, z=152}` occurs at `8c5510b`
and already at `f6b3617`, the commit that introduced the stage-10 corner
assertion. Its parent `79994bd` passes its six-stage RVU harness. Thus
`f6b3617` is the first commit with this failing assertion. The assertion was
not edited.

No manual in-game visual playtest was run for J-fix4. The transfer coefficients
and vent time constant are game-model calibrations, not a plant safety analysis.
