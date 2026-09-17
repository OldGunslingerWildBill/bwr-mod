# Steam and water plumbing

## Build the return-water loop

1. Configure the Mekanism turbine's water output, or an attached Mechanical Pipe,
   to push water into **High-Pressure Water Pipe** (blue bands).
2. Run that pipe to the feed pump's **water suction** flange. Incoming water fills
   its finite 2,000 mB suction buffer. A condensate storage tank is also a valid
   suction source when connected by water pipe.
3. Run a separate water pipe from **water discharge** to an **RPV Water Injection
   Port** installed in the reactor vessel wall. Point the blue flange outward;
   only that face accepts the pipe. Right-click the port to check vessel ownership.
4. Supply the motor pump with FE, or supply the turbine feed pump with steam and
   a working steam-exhaust path. Start and control the pump using its existing
   manual/redstone/CC:Tweaked controls.

There is no additional condenser. A pipe moves incoming condensate into actual
storage; it does not create water, remove reactor backpressure, or replace a pump.
The injection port receives the pump's modeled water delivery. It does not expose
a passive fluid tank that would let an ordinary pipe inject against vessel pressure.
The existing mod conversion remains **1 mB of water = 1 kg**.

## Which pipe connects where

| Connection | Pipe |
| --- | --- |
| RPV steam nozzle → main turbine steam outlet | High-Pressure Steam Pipe |
| RPV steam nozzle → RCIC / HPCI / turbine feed-pump steam inlet | High-Pressure Steam Pipe |
| RCIC/HPCI exhaust → submerged pool quencher | High-Pressure Steam Pipe |
| Turbine feed-pump exhaust → turbine steam outlet | High-Pressure Steam Pipe |
| Condensate tank / pool → pump water suction | High-Pressure Water Pipe |
| Mekanism return water → tank or pump water suction | High-Pressure Water Pipe |
| Feedwater / RCIC / HPCI / LPCI-RHR / LPCS / HPCS discharge → RPV injection port | High-Pressure Water Pipe |
| RHR pool-cooling discharge → its selected pool controller | High-Pressure Water Pipe |
| External recirculation pump → jet-pump drive inlet | High-Pressure Water Pipe |

Steam and water pipes never join, and steam valves never carry water. Pump casings
do not bridge networks internally. Keep water suction and discharge headers separate.
Use existing spray rings for LPCS/HPCS; their future models are not part of this update.

Water piping accepts only water through NeoForge's fluid interface. It forwards
incoming water to attached suction buffers or condensate tanks without storing
extra fluid in each segment. The source must push water; the pipe does not extract
from an arbitrary external tank. Network walks use loaded chunks and stop at 256
blocks. Disconnecting a pipe is checked again on the next transfer. Water already
in a pump's buffer can still be used until that finite buffer empties.

ECCS pumps use incoming buffer water when **condensate-tank suction** is selected.
Pool suction remains a separate operator-selected source. No control commands or
automatic switching have been added.

## Existing worlds

- Keep the RCP cube for now. Its recipe and creative entry remain available, and
  external jet-pump flow still depends on connected pump speed and installed jets.
- The old `rcic_turbine_pump` and `hpci_turbine_pump` cubes are marked Legacy and
  removed from crafting and the creative tab. Their IDs and saved state remain
  supported; already-placed blocks are not deleted or automatically expanded.
- Replace old pressurised tubes used for **water** with the new water pipe. The
  `pressurised_tube` ID now displays as **High-Pressure Steam Pipe** and is steam-only.
- Reroute modeled-pump discharge from reactor controllers to the new wall ports.
- The modeled HPCI assembly now has two blue water adapters for its associated
  pump and requires physical suction/discharge piping. Its steam ports remain.
- Existing compact pump blocks preserve their legacy behavior until replaced
  with full models. No automatic world conversion moves or deletes plant blocks.

## Pressure label

Tooltip: **ASME B31.1-inspired | Design pressure: 2,500 psi (simulation)**.
The value is a gameplay nameplate, not an ASME certification or an additional
pipe-burst solver. Actual pump curves and reactor backpressure still limit flow.
[ASME B31.1](https://www.asme.org/codes-standards/find-codes-standards/power-piping)
specifies requirements for power piping; it does not assign all pipes a single
pressure rating.

## Validation

The isolated Minecraft GameTest exercises real pipe blocks, formed vessels,
capability transfers, sided ports, wrong-fluid rejection, simulated versus actual
fills, finite buffers, fractional persistence, disconnections, and RCIC/HPCI/
feedwater/ECCS/recirculation delivery. The client model check bakes all 64 water
pipe states, all six injection-port orientations, both new item models, and the
existing modeled pumps. A complete live Mekanism Generators turbine is not built
by this fixture; condensate input is tested through the same NeoForge interface.
