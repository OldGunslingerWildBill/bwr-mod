# Rumble

A running plant is loud in the ground. Steam from the safety relief valves collapsing in the suppression pool shakes the basemat (real
plants call it chugging and quencher loads), and a turbine train that is bowed or out of balance shakes its hall. This update makes
both audible and visible: a **deep rumble** you hear and a **procedural camera shake** you feel, scaled by how much power the
source puts into the ground and how far away you stand. With the whole relief valve bank of a large plant discharging, someone
**8,000 blocks away** is just able to feel it.

It is an effect only. Nothing in the plant reads it, nothing acts on it, and it adds no control logic (`checkNoProtectionLogic` is
unchanged).

## What you get

- **Sound.** Two looping sound events, `bwr:rumble_pool` (condensing steam: a 18-65 Hz body with irregular thumps that fall in pitch,
  the chugging, and a crackle of collapsing bubbles) and `bwr:rumble_turbine` (a steady hum on the 25 Hz shaft frequency
  (1,500 rpm) and its harmonics over a noise bed). Both are soft-saturated so the deep body also puts harmonics at 60 to 300 Hz,
  which headphones and laptop speakers can play. Volume and pitch follow the intensity: a distant rumble is quieter and deeper.
  Close to the source the loop is placed a few blocks toward it (it pans as you turn your head); far away it has no direction.
  It plays in the **Ambient** sound category.
- **Camera shake.** Smooth noise moves the drawn camera's yaw, pitch and roll and bobs its position. It never touches the
  player's real look direction: aiming, picking and movement are unchanged.
- **Options** (client, `bwr-visuals.toml`): `screenShake` (0.0 to 1.0, default 1.0, 0 = off) and `rumbleVolume` (0.0 to 1.0,
  default 1.0, 0 = no rumble sound; on top of the game's Ambient slider).

## The sources

| Source | Where it comes from | Power `P` |
| --- | --- | --- |
| **Pool** (`Kind.POOL`) | `SuppressionPoolBlockEntity.condense`, every 5th tick | the pool's steam enthalpy inflow, W: mass flow times enthalpy of everything it condenses (relief valve discharge, steam wall inlets, turbine-driven pump exhaust). One open SRV at rated capacity is 42 kg/s x about 2,775 kJ/kg = **117 MW**; all 18 of a large plant are **2.1 GW**. |
| **Turbine** (`Kind.TURBINE`) | `TurbineAuxTrain.step`, every 5th tick | `RumbleLaw.turbinePowerW(vibration, rpm)` from the vibration the auxiliaries already publish (mm/s) and the shaft speed: only the vibration above what a healthy rotor shows (1.5 mm/s at rated speed) counts, `P = 20 MW x [E (v_over / 7.1)^2 + 0.004 s^3]` with `E = 0.4 + 0.6 s^2` the shaft's energy weight and `s = rpm / 1500`. 20 MW is the reference: a rotor 7.1 mm/s over a healthy one at rated speed. |
| **Impact** (`Kind.IMPACT`) | `Rumble.impulse(level, pos, energyJ)` | one-shot energy, J, spread over 2 s (`P = E / 2 s`) and dying away exponentially. 125 MJ (a 100 t body at 50 m/s) is felt about 260 blocks away; 10 GJ about 16 km. |

A source that stops being refreshed lapses after 40 ticks, so a broken block or an unloaded chunk can never leave a rumble running.

## The distance law

```
I(P, r) = A * sqrt(P / Pref) * (r0 / (r + r0))^n * exp(-r / L)
```

`P` is the source power in watts, `r` the distance in blocks (metres). Amplitude goes with the square root of power. The intensity `I` is unit-
less; **0.02 is the perception limit** (below it nothing is felt or heard) and `I` of 0.15 and more is "full". Two profiles:

| Profile | Used by | `Pref` | `r0` | `n` | `L` | `A` | Why |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GROUND` | pool, impacts | 2.1 GW | 16 | 0.5 | 100,000 | 0.485 (from the calibration) | surface waves in the ground spread over a circle, amplitude falls as `r^-0.5`, so 18 times the power is felt 18 times as far |
| `MACHINE` | turbine | 20 MW | 6 | 1.0 | 60 | 0.9 | carried by the building and the air: `1/r`, and dies in tens of blocks |

**Calibration.** `A` of the ground profile is fixed by one condition: the whole relief valve bank of a large (Columbia-class) plant,
18 valves x 42 kg/s at rated pressure, condensing at 2.1 GW, is exactly `I = 0.02` at **8,000 blocks**
(`A = 0.02 * sqrt(8016 / 16) * exp(8000 / 100000)`). The `GameTest` recomputes the bank from the mod's own numbers (valve capacity,
steam enthalpy at rated dome pressure) and requires it to be within 5 % of the limit there; it is within 0.3 %.

| Ground source | Power | Felt out to | `I` at 10 blocks | shake at 10 blocks | `I` at 200 | shake at 200 | `I` at 1,000 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 SRV | 117 MW | **501 blocks** | 0.090 | 0.44 | 0.031 | 0.13 | 0.014 (nothing) |
| 3 SRV | 350 MW | 1,500 | 0.155 | 0.72 | 0.054 | 0.25 | 0.025 |
| 6 SRV | 700 MW | 2,900 | 0.220 | 0.87 | 0.076 | 0.37 | 0.035 |
| 18 SRV (the bank) | 2.1 GW | **7,990** | 0.380 | 0.98 | 0.132 | 0.64 | 0.060 |

| Machine source | Power | Heard / felt out to | shake at 2 / 10 / 20 / 40 blocks |
| --- | --- | --- | --- |
| healthy train, 1,500 rpm (1.5 mm/s) | 0.08 MW | 9 blocks (its own hall) | 0.18 / 0 / 0 / 0 |
| 7.1 mm/s over healthy (the vibration reading's usual limit) | 20 MW | **74 blocks** | 1.0 / 0.94 / 0.70 / 0.29 |
| fully bowed rotor at its critical speed (35 %, 15.5 mm/s) | 42 MW | 87 blocks | 1.0 / 0.99 / 0.86 / 0.43 |

Several sources add as power: the intensity of a kind at a player is the root of the sum of the squares of its sources' intensities.
Only the sources in the dimension the player is in count; other dimensions contribute nothing.

## From intensity to what you see and hear

Both are soft-limited so a huge source cannot move the camera without bound:

```
level  = tanh(I / 0.15)                  0..1, linear for small I, flat above about 0.3
shake  = level ^ 1.3                      fraction of the largest camera motion (a faint rumble stays faint)
volume = min(1, level ^ 0.8) * rumbleVolume
pitch  = 0.70 + 0.30 * level              far = deeper
```

**Camera motion at full strength (`shake = 1`, `screenShake = 1`)** is a hard shake. These are the largest values the noise can reach; in the client check, 20 seconds beside a strong source peaked at 4.2 degrees of roll and 0.13 blocks of bob, and the strongest 1.2 degrees between two 60 Hz frames:

| Axis | Amplitude |
| --- | --- |
| roll | 6 degrees |
| pitch | 5 degrees |
| yaw | 5 degrees |
| up and down | 0.18 blocks |
| sideways (x and z) | 0.09 blocks |

Beside one open SRV (10 blocks, `shake` 0.44) that is a 2.6 degree roll and half a degree more in the other axes; beside three or more it is
more than 4 degrees; right at the bank of a large plant it is the full 6 degrees of roll and 5 of pitch and yaw. At the perception limit (the bank at
8,000 blocks) it is 7 % of that: a **0.43 degree** slow sway, which is what "just able to feel it" means. `screenShake` multiplies all of it.

The motion is **gradient noise of time**, never a random number per frame: two octaves of smooth 1-D noise per axis. A ground source
sways at 0.9 Hz far away to 3.2 Hz beside it, and its strength surges between about 55 % and 100 % every second or two (the chugging
of condensing steam). A machine source moves faster (2.2 to 6 Hz) with a steady 9.5 Hz hum on top. Two sources never add to more than 1.25 of
the full shake. The camera position is moved with a reflective call to `Camera#setPosition`; if the game's mappings do not allow it
the angles alone shake the view (the client check reports which).

## Delivery

`RumbleServer` keeps a small table of sources for each level (at most 512; the weakest are dropped first). Every 5 ticks (4 Hz) and
only while a table is not empty, it works out for each player **of that level** the combined intensity from the sources and the
player's position, and sends the player a 28-byte `RumblePayload` (the ground and machine intensity, the unit direction and the distance to the
strongest source) **only when something changed by more than 8 %, the direction turned, or 40 ticks have passed with something felt**. It never loads or
visits a chunk and does not care whether the player is anywhere near the source, so a player 8,000 blocks away is told. A player
whose sources have stopped or who left the dimension is sent one zero. The client smooths between packets (rising in a fifth of a
second, falling in about a second) and lets the rumble die if the server is silent for 7 seconds. The cost is the number of players of
active levels times the number of sources, a few thousand multiplications; nothing is spent while no source exists.

## API for other code (for example turbine missiles)

```java
// A source that goes on (refresh at least every 40 ticks; stop calling and it fades out):
Rumble.sustain(serverLevel, ownerPos, Rumble.Kind.POOL, powerWatts);
Rumble.sustain(serverLevel, key, new Vec3(x, y, z), Rumble.Kind.TURBINE, powerWatts);   // own key and place
Rumble.release(serverLevel, ownerPos, Rumble.Kind.POOL);                                // stop at once
// A burst of about a second:
Rumble.emit(serverLevel, new Vec3(x, y, z), Rumble.Kind.IMPACT, powerWatts);
// A one-shot (a missile landing, an explosion), energy in joules, dies away over a few seconds:
Rumble.impulse(serverLevel, new Vec3(x, y, z), energyJoules);
// Ask what a source would be felt as:
Rumble.intensityAt(Rumble.Kind.POOL, powerWatts, distanceBlocks);
```

The calls are safe from any server-thread code and cost a map write. The law and the maps are in the core class `RumbleLaw` (and
`RumbleShake` for the camera) so tests use the same numbers.

## How the sound was made

`tools-make-rumble-sounds.py` (numpy, seeded, no recordings or downloaded audio) generates both loops as 48 kHz mono and has a headless Blender
encode them to Ogg Vorbis through Audaspace (`blender.exe -b --factory-startup`, never a scene of the running Blender). The waveforms are
exactly periodic: noise is shaped in the frequency domain and transformed back, thumps are added modulo the loop, sines have a whole
number of cycles. A dip in loudness at the seam hides the codec's start-up. `rumble_pool.ogg` is 6 s and 33 kB, `rumble_turbine.ogg` is 4 s
and 23 kB. Both are about 85 % energy under 70 Hz, 15 % between 70 and 400 Hz, nothing above.

## Tests

- Core `RumbleLawTest` (6): the bank at 8,000 blocks, one valve's range, monotonic in power and distance, the turbine numbers, the maps, the
  camera motion bounded and smooth.
- GameTests `bwrrumble` (`RumbleTests`, 11; `-PbwrGameTestNamespaces=bwrrumble`): the law; the real bank within 5 % of the limit at 8,000 blocks and delivered to a player there;
  one valve's range; zero when the sources stop (exactly one zero packet); other dimensions get nothing; packet rate (a steady source sends at
  most one a 40 ticks, a wobbling one at most four a second); cost with 64 players and 512 sources; impulses; the pool hook against the enthalpy it condenses, and
  through a real open relief valve; the turbine hook with a bowed rotor through its critical speed.
- Client check `:mod:runRumbleClientCheck` (`RumbleClientCheck`, through `run-mc.ps1`): the two loops decode, are deep and loop without a step; the camera event offsets within the limits and smoothly;
  `screenShake` 0 gives exactly nothing, 0.5 about half; the loops start through the sound engine, follow the volume, are not started at `rumbleVolume` 0 and stop.
