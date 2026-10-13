# Phase J fixes and Phase K review (2026-10-13)

The numbers below are printed by the full `:core:acceptance` run after the
steam-cap and RPV-metal fixes (`279/279` passed). Containment pressure is one
shared node. The drywell and wetwell methods expose that same pressure; no
separate Mark I drywell pressure peak is simulated. The `<50 psig` DBA bound
does not validate the cited 49.6-psig drywell peak or reproduce the 27-psig
wetwell peak.

## J1 — design-basis recirculation LOCA

| Time (s) | Vessel (psig) | Break (kg/s) | Pool (°C) | Shared containment (psig) |
|---:|---:|---:|---:|---:|
| 0 | 1025.0 | — | 32.0 | 0.0 |
| 30 | 496.7 | 490 | 33.0 | 5.2 |
| 60 | 232.6 | 239 | 33.4 | 5.3 |
| 120 | 66.8 | 67 | 33.8 | 5.3 |
| 300 | 0.9 | 0 | 33.9 | 5.3 |

Peak shared pressure **5.3 psig**; peak break **1,005 kg/s**; final pool
subcooling **73.8 °C**.

## J2 — 24-hour LOCA with RHR and venting

| Time (h) | No RHR: pool °C / psig | RHR: pool °C / psig | Vent at 12 h: pool °C / psig |
|---:|---:|---:|---:|
| 0 | 34.1 / 5.3 | 34.1 / 5.3 | 34.1 / 5.3 |
| 6 | 114.5 / 21.9 | 81.1 / 4.6 | 114.5 / 21.9 |
| 12 | 147.5 / 72.4 | 76.7 / 4.5 | 147.5 / 72.4 |
| 18 | 166.6 / 118.9 | 68.7 / 4.4 | 147.6 / 8.4 |
| 24 | 182.1 / 170.2 | 63.4 / 4.2 | 147.6 / 8.1 |

Peak no-RHR shared pressure **170.2 psig** and pool **182.1 °C**. Peak
RHR shared pressure **5.3 psig** and final pool **63.4 °C**. Venting reduces
pressure to **8.1 psig** by 24 hours while losing suppression function.

## J3 — station blackout, RCIC/SRV exhaust, then RHR restoration

| Time (h) | Condition | Pool (°C) | Shared containment (psig) |
|---:|---|---:|---:|
| 0 | RCIC/SRV | 32.2 | 0.0 |
| 2 | RCIC/SRV | 76.1 | 0.0 |
| 4 | RCIC/SRV | 113.5 | 19.7 |
| 6 | RCIC/SRV | 146.6 | 70.1 |
| 7 | RHR restored | 137.7 | 56.7 |
| 9 | RHR restored | 124.5 | 41.0 |

Peak shared pressure **70.1 psig** at six hours. It falls to **56.7** one
hour and **41.0 psig** three hours after RHR restoration. This specifically
checks that the colder pool, not the hotter wall, limits steam saturation.

## J4 — shutdown cooling and high-pressure lineup

| Time (h) | Full: vessel / metal (°C) | Full HX (MW) | Throttled: vessel / metal (°C) | Throttled HX (MW) |
|---:|---:|---:|---:|---:|
| 0 | 178.1 / 178.1 | — | 178.1 / 178.1 | — |
| 1 | 158.2 / 159.2 | 27.1 | 170.3 / 170.7 | 22.8 |
| 2 | 138.3 / 139.3 | 27.1 | 162.7 / 163.1 | 22.8 |
| 3 | 118.1 / 119.1 | 27.1 | 155.0 / 155.3 | 22.8 |
| 4 | — | — | 147.2 / 147.6 | 22.8 |
| 5 | — | — | 139.4 / 139.8 | 22.8 |
| 6 | — | — | 131.6 / 132.0 | 22.8 |
| 7 | — | — | 123.8 / 124.2 | 22.8 |
| 8 | — | — | 115.9 / 116.3 | 22.8 |
| 9 | — | — | 108.0 / 108.4 | 22.8 |
| 10 | — | — | 100.0 / 100.4 | 22.8 |

Full RHR reaches **100 °C in 3.89 h**, peak cooldown **25.3 °C/h**,
cooldown damage **0**. Throttled reaches **100 °C in 10.00 h**, peak
**9.9 °C/h**, damage **0**. A separate unit test drives the vessel beyond the
100 °F/h (**55.6 °C/h**) limit and verifies accumulated boundary damage.
With the vessel still at **1,025 psig**, opening the **135-psig** low-pressure
lineup causes a **1,050 kg/s outside-containment break**; there is no interlock.

## J5 — steam inerting and spray

Before spray: steam **81.0%**, hydrogen **2.7%**, oxygen **3.4%**, **0** burns.
After **129.1 s** of spray: steam **50.4%**, **1** burn; peak shared pressure
**29.8 psig**.

## J6 — version-2 save migration

Legacy load starts with **0 kg** steam and **56.9 °C** gas. After **60 s**,
shared pressure stays approximately **0 psig** (0.000 to -0.000 psig). The
version-3 steam snapshot round trip has a separate unit test.

## Phase K — GPU client results

The two working towers were measured with the same camera and particle
setting after a 650-tick warmup in each mode. With visible plumes, classic
rendered **1,029 puffs** at **1.376 ms mean / 1.922 ms p95** over 7,265
frames. GPU rendered **1,031 puffs** at **1.452 ms mean / 2.215 ms p95** over
6,885 frames. GPU was **5.5% slower** on mean and **15.2% slower** at p95,
so `AUTO` retains classic vapor. `SHADER` exposes the optional instanced path.

The GPU-only vessel portion of the disposable RVU client check passed forced
SHADER and CLASSIC modes, intentional shader failure and log-once fallback,
simulated shader pack, resource reload, and dry-core no-glow checks. The full
RVU harness then failed its existing stage-10 sparger-corner assertion
(`Missing corner BlockPos{x=149, y=191, z=152}`), which was also seen in a
pre-GPU-check run. That assertion was not edited. The two-tower cooling client
benchmark passed and produced `cooling-check/vapor-classic.png` and
`cooling-check/vapor-gpu.png` for visual inspection.

The repeat visible run gave classic **1.440 ms mean / 2.122 ms p95** with
**1,024 puffs**, and GPU **1.520 ms mean / 2.443 ms p95** with **1,016 puffs**.
The final dedicated-server run passed **193/193 required GameTests** and the
protection-logic gate. The alpha.32 jar contains the production shaders and
excludes the deliberately broken shader used by the client check.
