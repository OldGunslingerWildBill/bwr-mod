# Changelog

## Unreleased: reactor building crane, stud tensioner and head stand

The vessel head no longer comes off with a button, and the dryer and the separators no longer come out with the refuelling bridge. As at a real plant, a **stud tensioner** detensions the closure studs (the nuts come off, the studs stay in the flange), the **reactor building crane** (an overhead bridge crane on high runway rails) lifts the head on a strongback onto a **head stand**, and lifts the dryer and then the separators into the dryer/separator storage pool; the **refuelling bridge moves fuel only**. Reassembly runs the other way: head set, studs retensioned, vessel can hold pressure. Nothing moves unless a player, or their program, says so. Details: [BUILDING-CRANE.md](BUILDING-CRANE.md), [OPEN-REACTOR-UPDATE.md](OPEN-REACTOR-UPDATE.md), [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md).

- **Four new blocks.** *Crane Runway Rail* (two parallel runs, up to 40 apart, high on opposite walls), *Reactor Building Crane Console* (beside a rail; the pendant screen, the survey on demand, never per tick), *Head Stand* (the middle of the head's lay-down place; the head is as wide as the vessel, so the floor round it must be clear and between the rails) and *Stud Tensioner* (the hydraulic power pack, FE on any side). Recipes, lang, loot tables and tags as for the bridge's blocks.
- **The tensioner.** DETENSION / RETENSION / STOP on its screen (and `bwr_stud_tensioner`). One stud takes 4 s and 3,000 FE going loose, 6 s and 6,000 FE coming back to load: **296 s and 222,000 FE, then 444 s and 444,000 FE, for the 74 studs of the reference vessel**. The FE for a stud comes from a 24,000 FE buffer when the stud is begun; out of power it stops and says so. Detensioning needs the head on and 25 psig or less. The nuts are drawn coming off round the flange with the tool crawling on the stud in hand and a hose back to the pack.
- **The vessel's states.** The reactor counts the studs whose nuts are off (`StudsOff`, written only while it is non-zero with the head on). The vessel holds pressure only with the head on **and** every stud at load: with the head on and one nut off it leaks steam through the flange exactly like an open head (350 kg/s per psi) and water over the flange spills. The head is liftable only with every stud off; it cannot go back with the dryer or the separators out. `HeadAt` (the crane console or the stand holding the head) is written only while the head is off and not laid down.
- **The crane.** DRYER OUT/IN, SEPS OUT/IN and the new LIFT HEAD / SET HEAD on its screen and in `bwr_building_crane` (`queueHeadOut`, `queueHeadIn`, `queueInternalOut`, `queueInternalIn`, `getStatus`, `abort`, `resume`). Speeds: 0.5 m/s along the runway, 0.4 m/s across, hook 0.5 m/s empty and 0.25 m/s loaded, 5 s to hook on and 5 s to unhook. Measured in the GameTests (7 x 7 x 10 vessel, stand 11 m from its middle, rails 14 blocks over the floor): **head to the stand 108 s, dryer out 121 s, separators out 111 s, head back on 98 s**. Every rule of the alpha.53 lifts stays (dryer first, head off and flooded, fuel refused while either is installed, the radiation dose); the head also waits for both to be back. A runway too low to clear the vessel is refused with the height to build to.
- **The model.** A yellow industrial overhead crane in the style of the refuelling bridge model, in headless Blender (`art/models/building_crane/build_models.py`, 12 parts, 4,452 triangles, baked by `python tools-export-building-crane.py [--check]`): end carriages on the rail heads, two box girders with a walkway and an operator's cab (one mesh per span, plain section stretched, detailed one tiled), a trolley with hoist drum and gear motor, two falls of rope, an orange hook block, a strongback with spreader and four slings for the head, a lifting rig for the dryer and separators. The head is drawn hanging from it and then resting on its stand's cradle, from the vessel's own head mesh without its nuts.
- **Old saves.** A world with the head off stays off; the head counts as laid down and the crane (or the option) sets it back, no stand block needed. A world with the head on has every stud at load. New keys are written only when not default (the reactor's `StudsOff` and `HeadAt`, the stand's `Holding`, the tensioner's `Energy` and, mid-pass, `Mode`, `Reactor`, `Vessel`, `Ticks`, the crane's lift state and load).
- **Server options.** `instantHeadRemoval` (default **false**): off, the reactor panel's head button explains the procedure; on, it lifts and seats the head at once as before. `bridgeLiftsInternals` (default **true**, *legacy*): the refuelling bridge still lifts the dryer and the separators until the owner turns it off. **I stopped on this one:** the existing alpha.53 GameTests `VesselInternalsTests` and `VesselInternalsCcChecks` assert that the bridge does those lifts, so the bridge's refusal ("moves fuel only; the crane lifts ...", buttons dark, `ok = false`) is built and tested behind the option, and its default has to be flipped, with those tests changed, by the owner.
- **Computers.** `bwr_building_crane`, `bwr_stud_tensioner`, `bwr_head_stand`, and `reactor.getHead()` (`headOn`, `studTotal`, `studsTensioned`, `studsDetensioned`, `sealed`, `liftable`, `headAt`, `summary`). `getInternals().summary` now leads with the head's state.
- **Screens and guide.** The pendant and the tensioner screens are in `-PbwrGuiScreenCheck`. The guide's fuel chapter has "Opening the vessel" and "The reactor building crane" and a rewritten "Dryer and separators out"; the Server options chapter lists the two options.
- **Tests.** GameTests `bwrcrane` (`BuildingCraneTests`, `-PbwrOnlyCrane`): the full sequence; the head will not lift with one stud tensioned; the tensioner's time, energy and power; the bridge refuses the lifts (option off); the instant option on and off; a lift saved on the hook and a pass saved half way; old saves and the default-key rule; the stand's placement rules; low rails; a console broken under a load; a bare stud leaks like an open head; no idle ticking and once-a-tick on the real scheduler; the computer faces. Core `CraneMotionTest`. Client check `-PbwrBuildingCraneCheck` (pictures in `art/building-crane/`).
## Unreleased: a new reactor vessel, and the steam dryer and separators that lift out

The pressure vessel is remodelled from the Columbia FSAR (a BWR/5) and other public sources, and the vessel gains the two big assemblies above the core, which now come out with the refuelling bridge before fuel can move. Old worlds load with both assemblies installed; new keys are written only while one is out. Details: [RPV-REFERENCE.md](RPV-REFERENCE.md), [PRESSURE-VESSEL.md](PRESSURE-VESSEL.md), [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#the-steam-dryer-and-the-separator-assembly-alpha53-lifted-by-the-crane).

- **The outside.** A support skirt with base ring and anchor nuts; a dished bottom head; the cylinder with Columbia's course seams (230 and 405 in) and three vertical seams per course; the closure flange with 74 studs (the real pitch, every size) and nuts and washers; the top head with its head-spray, vent and spare nozzles on bolted flanges and four lifting lugs; a forging and stainless safe end on every port you build (a steam outlet gets the big one); small instrument nozzles and a bottom drain that nothing connects to. With the head off the studs stay in the flange, as at a real plant.
- **The inside (drawn only when it can be seen).** The core shroud steps out to the upper shroud at the top guide; shroud support plate, core plate, top-guide rim; six curved feedwater spargers; the core-spray lines from their two nozzles round the annulus to the rings, which are now drawn inside the upper shroud (their construction rows do not move); three LPCI couplings; guide rods and dryer brackets; the shroud head with up to 225 steam separators at the real pitch (85 on the smallest vessel), the long hold-down bolts and lifting posts; the steam dryer (support ring, skirt to below the water, drain channels, six vane banks with hoods and troughs, four lifting rods with eyes reaching into the head). The internal-pump (ABWR) variant keeps its own shroud and gets the rest above it. Jet pumps, fuel, the Cherenkov glow, the cavity seal and well water are as before.
- **One shape.** `VesselProfile` works out the heads, flange, internals and inner wall for every envelope (7 to 23 wide, 10 to 131 high, rectangular ones elliptical); the renderer, CRD necks, water surface, port spools, a failed head's steam and the thrown head read it. The head keeps its old dome height, so the thrown head, the open-head cells and the flange lip that the cavity seal and the well use are unchanged.
- **Water you see.** True to the drawn internals' scale up to +30 in (the operating level sits on the separators, the dryer skirt dips into it); +30 to +60 in is stretched to the lip. Visual only: `FLANGE_LEVEL_IN` and the physics are unchanged.
- **Thrown and damaged head.** The thrown head is drawn with the new head (flange, dome, crown nozzles, lugs, nuts); the failed head is rebuilt on the new dome.
- **Dryer and separators out (new gameplay).** With the head off and the vessel flooded to its flange (+59 in or more), the refuelling bridge lifts the dryer first and then the shroud head with its separators out of the water to a new **Equipment Storage Pad** (one assembly each; place them on the floor of a pit of water the bridge reaches: the dryer/separator storage pool), and sets them back in the reverse order. A real bridge move (travel, lower, latch, raise, carry, lower, release; about 55 s on the smallest vessel), drawn hanging from the grapple. The rails must stand high enough for the hanging assembly to clear the vessel's lip (the bridge says how high). **Fuel moves are refused while either is in** (the server option `instantRefuelling` skips this). **The head cannot go back on while either is out**, so the reactor cannot hold pressure: the INFO tab ("Steam dryer and separators") and `reactor.getInternals()` say "Not operable" and where each one is. A lifted assembly is a radiation source (dryer 0.2 W, separators 10 W of equivalent gamma, game-scale): shielded by the water you see over the vessel and by vanilla water standing on its pad; lifted out, the Survey Meter spikes. Breaking the console under a hanging assembly sets it back in its vessel.
- **Screen and computers.** Console buttons DRYER OUT/IN and SEPS OUT/IN and an "Internals" line. CC: bridge `queueInternalOut(name)`, `queueInternalIn(name)` and `getStatus()` keys `carriedInternal`, `dryerInstalled`, `separatorInstalled`; reactor `getInternals()`; the pad's `bwr_equipment_pad` face (`getStatus()`). The guide's fuel chapter has a "Dryer and separators out" page.
- **Assets and tools.** Unit parts from headless Blender (`art/models/reactor_vessel_v2/build_parts.py`), exported by `python tools-export-reactor-vessel.py [--check]`; `tools-check-vessel-geometry.py` checks every part is a closed, outward-facing solid and the shell joints overlap at every size. The phase-1 concept and its renders stay in `art/models/reactor_vessel_v2/`.
- **Tests.** GameTests `bwrinternals` (`VesselInternalsTests`, `-PbwrGameTestNamespaces=bwrinternals`): fuel waits for both; the order on the bridge with real time; only from a flooded vessel and never with the head on; the dose spike and the shielding of a flooded pad; saves; a console broken under a hanging dryer; rails too low; the profile and the water line at every size; the CC faces. The bridge tests' fixture now starts with the dryer and separators out (they are about fuel). New client check `-PbwrRpvRemodelCheck` (pictures in `art/rpv-remodel/`).

## Unreleased: a new model for the refuelling bridge

The refuelling bridge's placeholder (a handful of boxes) is replaced by a model of the real thing, a refuelling platform, drawn from the photographs the owner sent. **Visual only**: the same blocks, survey, motion, saves, protocol and tests; nothing new is synced or saved.

- **The machine.** A bright yellow steel gantry on the two rails: at each end an A-frame with X and portal bracing down to a wheeled end truck (drive motor, cabinets, a ladder), two deep box girders between them with a walkway along one (tubular handrails, an orange skirt hung on them, a console with a monitor, cabinets and a hose coil on the deck, a cable festoon along the girder) and a generic maker's plate ("REFUELING PLATFORM, MAIN HOIST / AUX HOIST", no brand); a blue trolley on the girder tops with an operator platform (yellow rails, a console), two orange cable reels, an auxiliary hoist, cabinets and handrails; a four-stage grey telescoping mast with a grapple head and two jaws. 6.2 m tall and 4.2 m along the rails (a room with a lower ceiling is drawn through), as long across as the rails are apart.
- **Parts, so it fits any rail spacing.** Headless Blender (`art/models/refuelling_bridge/build_models.py`, with its `.blend` and Workbench review renders), baked by `python tools-export-refuelling-bridge.py` (`--check`) into 13 OBJ parts. The girder mesh is built once per span length (1 to 40 blocks): one metre of plain section (girders, deck, rails, skirt) is **stretched** to the span, one metre of detail (stiffeners, outriggers, posts, festoon) is **tiled**; the end trucks sit on the two rail lines, the trolley and mast move between. Triangles: 6,430 for a 4 block span, 7,922 for 10, 10,330 for 24, 13,082 for 40 (the extended mast and a carried assembly add 444); the old picture was 11 boxes.
- **Animation from the existing motion state.** The bridge travels along the rails, the trolley across, the mast's three inner sections slide out of the outer tube (a unit tube scaled by the pose, normals kept unscaled), the jaws swing shut on the handle and open again with the latch and release phases, the cable reels turn with the hoist (the cable paid out is the height moved). The Cherenkov glow of a hot assembly under water is unchanged.
- **Cached meshes.** `RefuellingBridgeRenderer` draws through `MachineMeshCache` like the reactor, tanks and towers: every part is a vertex buffer built once, about 17 draws a frame, nothing rebuilt on a pose change. Under Sodium and Iris it takes the path `MachineMeshCache` already has for them. The bounds reach 8 m above the rails.
- **Pictures and the check.** `-PbwrRefuellingBridgeCheck` now takes eight more 1920 x 1080 pictures (`art/refuelling-bridge-model/`): over the pool with the mast lowered into the water, from the walkway's side, the trolley over the pool, over the open vessel, a close-up of the trolley and operator platform, the grapple open, and a 4 block and a 24 block span; it fails if any mesh is uploaded again while a picture stands still and logs the cached draws per frame and the frame rate. Docs: REFUELLING-BRIDGE.md, "The model".
- **Not built (a proposal).** A walkable deck. The walkway and the operator platform are pictures. It is more than collision boxes: the bridge moves along the rails and the trolley across, so standing on it needs a collider that moves (an invisible carrier entity, or a rule that carries a player standing on it), not boxes fixed in the world; left out because it changes how players move and adds per-tick work.
## Unreleased: cavity drain port and the motor-operated water valve

Two new blocks. The **Motor-Operated Water Valve** is the first powered valve a BWR *water* line has (until now its only valve was the containment water penetration's FE isolation actuator; the steam side already has the MSIV, stop, control, bypass and relief valves). The **Cavity Drain Port** lowers the flooded reactor well by gravity, through that valve, into a Condensate Storage Tank. Old worlds load unchanged; the new blocks save only what they hold, and a valve writes nothing for its default state. Rules: [WATER-PLUMBING.md](WATER-PLUMBING.md#motor-operated-water-valve) and [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#draining-the-well-the-cavity-drain-port).

- **Motor-Operated Water Valve (any BWR water line: feedwater, condensate, ECCS, RHR, cooling, drains).** One block, two flanges on High-Pressure Water Pipe; opening 0 to 100 %; **full stroke 20 s** (400 ticks) either way; **50 FE per tick while the motor drives it** (a full stroke is 20 000 FE, which is the buffer, taken on every face but the flanges); it holds its position with no power and no draw; new valves are closed. Control, latest command wins: **redstone is proportional** (strength 0 closed, 15 open, `strength / 15` between; only a change of the signal is a command), a panel or the Networking Wrench (command `position` 0 to 1 and `open`; signals `position`, `open`, `closed`, `flow`, `target`, `powered`, `energy_fe`, `fail_mode`, `rating_psi`, `wear_above_psig`; a controller station and a control switch are tested through the wrench link), and CC:Tweaked `bwr_motor_operated_water_valve`. Nothing moves it automatically. No ticker: like the penetration's actuator, its clock advances when it is asked something.
- **Fail position, the player's setting.** FAIL AS-IS (default: no power, it stays), FAIL OPEN, FAIL CLOSED. A fail-safe valve is a spring and a latch: the latch draws 20 FE/t while the valve stands away from its fail position; when the buffer cannot pay, the spring takes it to the fail position at the normal stroke rate **using no FE**, and helps a stroke towards it for free. Set by the `fail_mode` signal/command (0, 1, 2), by CC `setFailPosition`, or by sneak-clicking the valve. Saved only when not as-is. "Loss of power" is the one trigger; a redstone or computer demand is a demand, not a signal that can be lost.
- **Rated as the pipe, wears as the pipe.** Its nameplate is the pipe's (2,500 psi (simulation)); the tooltip, right-click text, signals and CC state it and the 1,250 psig above which a line wears. There is no second wear model: the valve is part of its line, so `BoundaryStress` wears the feedwater, recirculation or steam line it sits in, and when the line fails the whip takes the block at the break (a valve there goes like the pipe it replaced) and spares the rest of the line, a valve beside it included.
- **Throttling and isolation.** A route through the valve is open to the extent it is (`PipeTopology`: a water **valve node**, `Route.waterValves`); a pump's water line is full-bore unless a motor-operated valve is on it, then its flow is the line's opening (`AssemblyPlumbing`); a closed valve leaves a pump no source. It is an isolation point for the LOCA line-break code: a closed valve between a break and the vessel isolates the break, opening it starts the leak (see PORT-LOCA-UPDATE.md).
- **Cavity Drain Port.** Placed in the wall or floor of the reactor well at or above the cavity seal row, flange (the pipe side) out of the well; the block behind the flange must be a cell of the surveyed well's water. Gravity: `Q = Cd A rho sqrt(2 g H) x valve opening`, `Cd 0.6`, `A 0.2 m2`, at most 1,500 kg/s; `H` = depth of water over the port's sill plus the line's elevation difference (the sill less the tank's inlet flange). **It needs the player's valve** on the line (none: no flow; closed: no flow) and must reach a Condensate Storage Tank with room (full: no flow, then it starts again). It takes the water out of the vessel's inventory exactly as the tank accepts it, and **stops when the level reaches the port's sill**; the vessel below the flange is drained the existing ways. New `PressureVessel.drainWellWaterKg(kg, keepWellM3)` (core) and `CavitySeal.Profile.waterUpTo(height)`. The formed reactor steps the ports of its well once a tick (no ticker on the block; `CavityDrains` lists the loaded ports).
- **Screen and computers.** INFO tab, cavity section: **cavity drain flow** and a line per port (where it is, what its line is doing, kg/s, metres over the sill, the valve's opening). `reactor.getCavity()` adds `drainCount`, `drainKgPerS` and `drains` (per port: `x`, `y`, `z`, `state`, `flowKgPerS`, `depthOverSillM`, `headM`, `valveOpening`, `valves`, `totalKg`); the port has a `bwr_cavity_drain_port` face with `getStatus()`.
- **Models and assets.** Headless Blender: `art/models/water_valves/build_models.py` (round flanged valve with a motor and handwheel; a wall frame with a strainer and a pipe flange), exported by `python tools-export-water-valves.py [--check]`; inventory icons rendered with `art/models/inventory/render_icons.py`'s renderer.
- **Guide, docs.** The guide's "Flood the well" page says how to drain; WATER-PLUMBING.md, REFUELLING-BRIDGE.md, PORT-LOCA-UPDATE.md and SIGNALS.md describe the valve, the drain, the isolation and the rating.
- **Tests.** Core `PressureVesselWellTest.test09` (a drain takes well water to its sill and never below the flange). GameTests in `bwrcavity` (`-PbwrOnlyCavity`, now 40 tests): `WaterValveTests` (stroke time and FE, a partial demand, FAIL AS-IS / OPEN / CLOSED on power loss with no FE spent by the spring, the setting as a signal and saved, redstone, the rating shown, a controller station and a control switch through the Networking Wrench link, a route and a pump line throttled in proportion, a closed valve isolating a feedwater line break, overpressure wear failing the line like a pipe, CC) and `CavityDrainTests` (the flow law, a flooded well draining through port, pipe and valve into the tank and stopping at the sill, proportional throttling against the law, a full tank, a closed valve / no valve / no tank / wrong way round / below the seal, a save and reload mid-drain, CC `getCavity()`); each behaviour was broken once and the failing line seen. The cavity client check (`-PbwrCavityCheck`) now takes nine pictures (four new ones in `art/cavity-drain/`: the valve on a water line, the drain port piped to an assembled tank, the port's strainer in the well wall, the INFO tab during a drain).

## Unreleased: the Operator's Guide as a book

Client-only restyle of the guide screen; the content system (the JSON chapters, the loader `GuideBook`, `tools-audit-guide.py`, the first-join gift, the bookmark) is untouched. Old saves load; nothing is saved that was not before. Details: [GUIDE.md](GUIDE.md).

- **An open book.** `GuideScreen` no longer is a dark `BwrScreen`: it draws a two-page parchment spread (`textures/gui/guide_book.png`, drawn with PIL by `tools-make-guide-assets.py`: blue cover with an amber stitched edge, page-block edges, a spine crease, fibres and shading, all original) with dark ink text, a red ribbon that goes back to the contents, page-turn arrows in the bottom corners and the vanilla page-turn sound (`SoundEvents.BOOK_PAGE_TURN`) on every turn, and a page number at the foot of each page. It is still client-only with no slots and no packets (`GuideMenu` stays the slot-less stand-in).
- **Contents.** The first spread is the title page and the contents: the title, the introduction, a **Continue** link once there is a bookmark and the key help on the left; every chapter on the right as a link with a dotted leader and the number of its first page (long titles take two lines). Pages are numbered across the book (the contents spread is pages 1 and 2, every guide page two more).
- **One guide page is one spread.** Its title is the heading of the left page (the chapter's title as a running head above it; a heading too long for a line takes two), the text flows from the left page into the right one and ends at about the same height in both columns, the icons (now wrapped in rows of six, on a recessed square each, with the item's name as a tooltip) and the image sit at the top of the right page, and the last page of a chapter ends with the "More:" line. The arrows turn by spread; Next on a chapter's last page goes on to the next chapter, Back on a chapter's first page goes to the one before, and Back on the first page of the book goes to the contents. Keys: Left and Right (and Page Up and Down) turn pages, Home goes to the contents, End to the last page. The 600 character page limit and the audit are unchanged; the fullest real page (the last of Computers, with its long file names and a four-document "More:" line) uses 298 of the 321 px the two columns hold.
- **Scaling.** The book has a fixed design size (316 x 232 texture pixels) and is drawn the largest whole number of times larger that leaves 2 px at each side (1x at the 320 x 240 and 427 x 240 floors and at GUI scales 3 and 4 in a 1080p window, 2x at scale 2), so text stays crisp and every page holds exactly the same text at every size. New `Ui.text(..., zoom)` overload (a whole-number zoom on top of a type's scale, with the probe given the screen position). `GuideMedia` is now plain design-pixel code that wraps its icons and puts the image after them.
- **GUI check.** The guide scenarios are now `guide-contents`, `guide-contents-continue`, `guide-page-first`, `guide-page-long`, `guide-page-icons`, `guide-page-image-last` (a chapter's last page, with an image and the "More:" line), `guide-page-options` and `guide-page-book-end` (the last page of the book, no Next arrow); `guide-contents`, `guide-page-long`, `guide-page-icons`, `guide-page-image-last` and `guide-page-book-end` are also photographed at the 320 x 240 and 427 x 240 floors. New `guideTurns` step: every one of the 51 pages is opened and must fit its two columns (the layout does not depend on the scale, so one pass proves every scale), and the book is driven (arrow keys, the arrows, the ribbon, a contents link, Next across a chapter's end, Back to the contents, Home, End, and no turning past either end). The driver also stops ticking once the check has finished: the longer finish step let the game run a second tick after `mc.stop()`, which read past the scale list and crashed the run after it had passed. Found by the new fit check while building it: the shared wrapper `Ui.wrapStrings` leaves a word wider than the column (a long repository file name in a "More:" line) on a line of its own, so the guide breaks such words itself.

## Unreleased: cavity seal and the flooded reactor well

A formed reactor's round vessel sits in a square block footprint, so there is a gap round the vessel wall and the concrete that surrounds it. In a real plant that gap is the drywell and stays dry; above the vessel is the reactor well, and a cavity seal at the vessel flange holds the refuelling water above the drywell. The mod now has both. Old worlds load unchanged; the only new saved key is written only while water stands over the flange. Full rules: [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#the-reactor-well-and-the-cavity-seal).

- **The seal (formed with the vessel).** Looking outward from the vessel's block footprint at one row of blocks, all the way round the ring (corners included): a full block touching the vessel (gap 0) or one block away (gap 1) at every column closes the ring; more than one block of air anywhere round it, and that row does not close. Scanning down from the flange (the top of the vessel's body once the head is off), the highest row that closes takes the seal, but the seal must be in the **upper third**: at most `round(H / 3)` blocks below the top shell row, H the formed vessel's total height (H = 10: 3 below the top; 11: 4; 13: 4; 14: 5; the default 17 x 17 x 28: 9). No row closes in that range: no seal. It is part of the vessel's picture (a steel plate with a dark bellows collar, from the round flange out to the square footprint and the walls, the corner gaps included), not a block. Canal and gate blocks of the cattle chute count as wall. It is surveyed with the structure (on formation, on a changed block next to the controller, and on the five second sweep the reactor already does), never per tick or per block, loads no chunk, and an unloaded block leaves the last answer standing. Nothing of it is saved: an old reactor surveys its seal on its first tick. The owner's fallback of a quarter instead of a third was not needed (checked on the smallest, the default and the largest vessel; see the document).
- **The well and the level.** From the seal row up, the cells water could stand in (the footprint, the open cells joined to it within three blocks, up to the first layer that leaks; a block over the footprint is a ceiling). `PressureVessel` (core) takes that as a `Well`: the model's flange lip stands at collapsed level +60 in, where the drawn lip has always been; below it every level and inventory is bit for bit what it was; above it the inventory first fills the **annulus** (outside the vessel body, from the seal plate up to the lip; the level in the vessel holds at the lip while it does), then rises through the layers of the well, each at its own cross-section (one block is one metre: a vessel with touching walls has 49 m2 a metre where the 5 x 5 interior is, against 22 m2 below the flange; one block of gap all round gives 81 m2). Collapsed level, inventory (`liquidMassKg`) and `VesselWaterGeometry` stay consistent: the level over the lip is `60 in + rise / 0.0254` and the drawn surface rises the same metres. Nothing floods by itself: the feed, condensate and RHR pumps do it.
- **Leak and overflow.** With the head off, water the well cannot hold leaves the vessel at the rate it arrives, up to **4000 kg/s** (`PressureVessel.WELL_SPILL_KG_PER_S`, twice the rated feedwater flow): into the **drywell** when there is no usable well (no seal, or a wall that opens below the flange: a refuelling seal failure), over the top onto the **refuel floor** when a sealed well is full. The level holds at the lip (or the top). With the head on nothing spills. Closing the head over a flooded well moves nothing: the water stays where it is and the level reads where it was; it comes down only as the level falls.
- **The refuelling bridge** reads the reactor model's level, which now includes the well, so a flooded well adds its rise to the water over the fuel metre for metre and satisfies the hoist limit; a shallow cavity still halts it, with the reason.
- **Screen and computers.** INFO tab, "Cavity seal and well": present (y, blocks below the top, the most allowed, H, touching or with a gap) or absent with the closest row, which side is how many blocks from the vessel and where; the water over the flange, the level over it, the well's capacity and the leak or overflow. CC:Tweaked `reactor.getCavity()` returns the same as a table.
- **Drawing.** The plate and collar are built into the vessel's cached mesh. The water in the well and the annulus is one translucent surface at the level the server sends, in the spent fuel pool's tint, over the cells of the well at that height (no water blocks); the core's Cherenkov glow reaches up to it.
- **Survey cost.** The survey is resumable (`CavitySeal.Survey`) and the reactor steps it at most 4000 block reads a tick (a `Reactor.cavitySurvey` tick probe entry) after each revalidation, instead of doing it all on the revalidating tick: the worst case (a 23 x 23 x 62 vessel, 48 layers of well, 124 415 reads) cost 9.5 ms in one go and is now 32 ticks of at most 0.65 ms (measured in `theWorstCaseSurveyIsSpreadOverTicksAndCostsLittleInAny`, which also asserts the reads per step, that stepping gives exactly the whole survey's answer, and that the reactor's own ticks take as many ticks as the budget says). Until a survey finishes the previous seal and well stand, so nothing flickers. (A formable vessel is at most 64 interior rows tall: the structure walk's guard.)
- **RVU client check fixed (pre-existing).** `-PbwrRvuCheck` failed on main 1b80d762, without this branch, with `Wrong/missing fuel texture for depleted_uranium`: the fuel catalogue check (`FuelCatalogueClientCheck`) expects each grade's icon to be `item/fuel/<grade>`, but the depleted uranium INGOT owns `item/fuel/depleted_uranium.png` and its assembly was given its own `depleted_uranium_assembly.png` when depleted uranium was added, and the check also still counted 17 grades (now 18). The check now expects the assembly's own PNG and 18 grades (still a distinct PNG per grade; pointing the assembly model back at the ingot's PNG fails it, shown); no asset changed.
- **Old saves.** `WellInventoryKg` is written only while the vessel holds water over the flange (the level alone does not say how much sits in the annulus), and applied once the well is surveyed so a reload reads the same level; every older save has none.
- **Guide, README.** README step 4 and the guide's fuel chapter ("Flood the well", one new page) say to flood the well.
- **Tests.** Core: `PressureVesselWellTest` (8 tests: no well changes nothing, nothing changes below the lip, the annulus plateau, the area above, the leak, the overflow, no spill with the head on, no water moves when the head or the well changes). GameTest class `CavitySealTests` (namespace `bwrcavity`, `-PbwrOnlyCavity`, 16 tests): a seal with the wall touching, with a one block gap, none with a two block gap on one side (and the report names the side), below the flange, at the deepest allowed row (forms) and one row deeper (does not), three odd heights for the rounding, a broken ring, flooding into the sealed well, the annulus filling while the level holds at the lip, the leak into the drywell and the level held at the flange, the overflow of a full well, closing the head over a flooded well, the bridge's cover reading the well (and a whole bridge move over a flooded well after the hoist limit held it), a save and reload of the water over the flange, and an old save. Client check `-PbwrCavityCheck` (`CavityClientCheck`): five 1920 x 1080 pictures (the seal ring with the well dry, the well flooded with the core glowing, the INFO tab, a vessel with a wall two blocks away and no seal, the bridge over a flooded well).

## Unreleased: the in-game guide (Operator's Guide book)

A short in-game guide for new players and outside testers: what to build first, how to wire a panel, how not to melt the core. The repository documents are written for development and run to hundreds of pages; the guide is eight short chapters, 51 pages, read in the game with a right-click. It adds **no dependency**: the screen is a `BwrScreen` on the existing GUI style layer (`Layout`, `BwrButton`, `Ui`), and the text is JSON. Old saves load; the only new saved key is a player attachment written once the book has been given. Full reference, and how to edit a page: [GUIDE.md](GUIDE.md).

- **The book.** New item `bwr:guide_book` (Operator's Guide), crafted shapeless from a book and a nameplate. Right-click opens the screen on the client; it opens at the page where you stopped (one chapter id and one page in `config/bwr-guide-progress.json`, written when a page is shown, ignored if missing or stale), or at the chapter list. Chapter list (two columns, with a Continue button once there is a bookmark), then pages of word-wrapped text with optional item icons (16 px, the item's name as a tooltip) and an optional small image from an existing texture, and Back / Contents / Next; Left and Right turn pages; Next on a chapter's last page goes on to the next chapter. The chapter's title and the page are at the right of the title bar. Its menu (`GuideMenu`) is client-only and has no slots, no snapshot and no command.
- **Content is data.** `assets/bwr/guide/en_us/index.json` and one JSON file per chapter: `title`, `docs` (the repository documents the last page ends with, "More: PRESSURE-VESSEL.md"), and `pages` (`title`, `text` as a string or an array of lines, optional `icons` and `image`). The game's language is tried first and `en_us` fills in any file a translation lacks, so a translation is a copy of the folder. The loader (`GuideBook`) is pure and takes a `Source`, so the client (resource manager), the GameTest (class path) and the Python audit (source tree) read the same files. Chapters: What this mod is; A first working plant; The control room; Fuel and fuel handling; Safety systems you build; When things go wrong; Computers (CC:Tweaked); Server options. Every fact was taken from the code and feature documents at HEAD; the Server options chapter lists all 45 options of the six config classes with their file and default.
- **The first-join gift.** Server option `giveGuideOnFirstJoin` (`bwr-gameplay.toml`, default **true**): a player is given one book the first time they join, once only. The mark is the player attachment `bwr:guide_given` (saved only when set, kept through death), so a relog, a death or a lost book never gives a second book; with the option off nothing is given and nothing is marked, so a player who joined while it was off gets the book on the first join after it is turned on.
- **Style layer.** `Layout.ScrollArea.overflows()` (new accessor): the page's text area scrolls when a translation is longer than the room, and the GUI check reads it.
- **Audit.** `python tools-audit-guide.py`: index and chapter files agree; every file has the types the loader needs; every page is within 600 characters, every chapter within 12 pages, icons and images within their limits; every icon is an item of this mod; every image exists and is square at its declared size; every document in `docs` exists in the repository; a translated chapter has the same page count as English; the Server options chapter names every option of every config class with the right default; every `gui.bwr.guide.*` key the screen uses is in the lang file. It runs twelve negative controls. The translation audit covers the screen's own keys (`gui.bwr.guide.*`, `item.bwr.guide_book`).
- **Tests.** GameTest class `GuideBookTests` (5 tests): the book crafts from a book and a nameplate (and not from a book alone); the gift is given once, not again in the same session, after a relog (the player is written out and read back), after the book is lost, and a second player still gets theirs; with the option off nothing is given or marked, and turning it on gives the book once; every chapter and page loads through the real loader, stays within the screen's limits and names only registered items and existing images; a broken page is reported by file and page number, and a language with no guide files shows the English one. GUI check: six scenarios (`guide-contents`, `guide-contents-continue`, `guide-page-long` (the page with the most text), `guide-page-icons`, `guide-page-image-last`, `guide-page-options`) at GUI scales 2, 3 and 4 in a 1920 x 1080 window and at the 320 x 240 and 427 x 240 floors; a page whose text needs scrolling is a violation at every size but 320 x 240.
- **Found while writing it.** README's "Getting started" step 4 still says to load the core from the refuelling map; since the refuelling bridge the map is off by default (`instantRefuelling`), so a first load needs the bridge or the option. The guide says so; README was left as it is.

## Unreleased: Sodium, Iris, shader and other-mod compatibility pass

No game behaviour changed; no defect was found in the mod's own rendering, so no render code changed.

- **Checked** under no render mods, Sodium 0.6.13 + Iris 1.8.12, Sodium 0.8.13 + Iris 1.8.14-beta.1, and the newer pair with Complementary Reimagined r5.9.3: the spent fuel pool (water, Cherenkov glow), the refuelling bridge, mast and canal water, the flying reactor head, the plant computer screens, the survey meter HUD, the containment doors, the area radiation monitor, the uranium ore and the shield plug blocks. 49 pictures per set-up, all looked at, and the logs of all runs: nothing missing, black, z-fighting or mis-ordered, the head and the HUD show everywhere, no exception, render-type error or mixin warning from bwr. Results, versions and known differences: `docs/shader-packs.md`, `TESTING.md` (C2).
- **Other mods.** One session with Essential 1.5.0.1 and Effortless Structure 3.4.0 beside Mekanism, Mekanism Generators and CC:Tweaked (also with the newer Sodium/Iris pair and Complementary): join a world, build and use the pool, the bridge console, a radiation monitor and the plant computer; no error or conflict from bwr.
- **Test additions** (devtest, not shipped). New client check `-PbwrCompatSessionCheck` (`CompatSessionCheck`); new stage `10-shield-plug-tiers` in `-PbwrAccidentBlastCheck` (vanilla concrete and the three shield plug tiers); the spent fuel pool, bridge, accident blast, radiation, containment and uranium checks now log `RENDER ENV` like the panel and FCD checks do.

## Unreleased: flaky GameTests, second round (fixtures released too early, timers started before the chunk was live)

Test-only; no game behaviour changed.

- **`anOutsideDemandOverridesThePidAndTheSamplerRunsAnAutoStation`** (and any `runWhenLive` test that goes on after its action returns). `EntityLookupProbe.runWhenLive` released the fixture's chunks the moment the action returned, but this test's action only starts a 100 tick wait. The chunk lost its ticket, unloaded a few ticks later (unless another test's chunk happened to be within two chunks of it) and was read back as a new panel block entity with the reference station's value gone: `same block entity false, tracked false, pv NaN`. The chunks are now released when the test ends (or at once when the action ended the test). Two of four full runs with the machine loaded failed before.
- **`vaultIsSolidUntilTheLeafSwingsClearAndSolidAgainBeforeItShuts`** (and the two other tests that time a door on the game clock). They started their stroke at once, with the chunk forced but its entities not yet loaded (`areEntitiesLoaded` false), and scheduled block ticks do not run in such a chunk, so the "clear from half open" tick came late: `walkable from tick 33, expected 32`. With 24 copies of the test at once the entities of a chunk came back as late as tick 40 and three of three runs failed; they now start when `EntityLookupProbe` says the chunk is live, which also asks for loaded entities and block ticking. A hook on every tick has to be registered in the test body (`whenLiveEachTick`): `onEachTick` enters an entry per remaining tick in the schedule, and the framework crashes (`NullPointerException` in `GameTestInfo.tickInternal`) when that happens inside one of its own delayed callbacks.

## Unreleased: refuelling bridge and the cattle chute

Fuel is moved by a **refuelling bridge** now, not by clicking the map. Rails, a console, a gated water-filled **cattle chute** between the reactor cavity and the spent fuel pool, a gantry with a trolley and a telescoping mast that latches one assembly, lifts it, carries it underwater and sets it down, in real time. The old instant map is the server option `instantRefuelling`, **off by default**; with it off the map still shows the core but its buttons say how to use the bridge. Old saves load (no new keys unless a console holds a move). Full reference: [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md).

- **Blocks.** Refuelling Rail (axis x or z), Transfer Canal (stacked into a trench; floor and water surface follow the neighbours; a bucket fills or drains the stack), Canal Gate (a canal block with a plate; an empty hand opens or closes it), Refuelling Bridge Console. Recipes, loot, lang, models and textures (`tools-make-refuelling-bridge-assets.py`; JSON models, no Blender needed).
- **The move.** Travel, lower, latch, lift, carry (through the chute between cavity and pool), set down, release, retract; each phase takes time: bridge 1.0 m/s, trolley 0.6 m/s, mast 0.6 m/s empty and 0.3 m/s loaded, latch and release 4 s each. The assembly leaves its seat through `unloadAssembly` (which stamps `bwr:discharge_time`) or the pool's `extractFrom` when the grapple latches and enters through `loadAssembly` or `insertAt` when it releases; nothing is duplicated or lost on the way, and a halt (closed gate, hoist limit, a destination that filled up) keeps it on the grapple until a player resumes, aborts or sets it down elsewhere.
- **Water and dose.** The carried assembly is a radiation source (half its decay heat as gamma, through `e^(-7.07 x water over the handle)`, the reactor model's open-head law), and the pool's racks are a registered provider behind the water over them. A boiled-down pool, a drained chute or a handle lifted out of the water makes the survey meter spike. The cavity's water is the reactor model's, the pool's its own, the chute's its blocks'; **they are not coupled** (an open gate is a path, not a pipe; the reasons are in the document).
- **Hoist limit.** The bridge will not lift an assembly higher than leaves 1.5 m of water over its handle unless the player overrides it (then it lifts the real 4.5 m and the handle leaves the water). A player-facing limit of the machine; the core's `ReactorCoreTickTest.test07` objected to the method that decided it, so the decision lives in the block entity and the core only measures the cover.
- **Controls.** The refuelling map opened from the console is the bridge's control (pick a core position and a rack cell, TO POOL / TO CORE / SET DOWN / ABORT / RESUME / OVERRIDE, phase, progress, water, chute, grapple, halt reason). CC peripheral `bwr_refuelling_bridge`. Network protocol of the refuelling menu: the snapshot gained the `instant` flag and the bridge section at its end, the menu its bridge commands (10 to 15); update client and server together.
- **Performance.** No ticker idle; a moving bridge is ticked by scheduled block ticks, one per game tick, only while it moves. It never loads or forces a chunk (it waits where it is), and its state is saved mid-move.
- **Instant option.** `instantRefuelling` (default false) in `bwr-gameplay.toml`. The reactor's `loadAssembly` and `unloadAssembly`, which the bridge itself uses, are not gated; the map's commands are. The client checks that drive the map (`RefuellingClientCheck`, the GUI check's refuelling scenarios) enable the option in their fixture; no assertion was changed.
- **Tests.** Core `BridgeMotionTest`; GameTests `RefuellingBridgeTests` (namespace `bwrbridge`, `-PbwrOnlyBridge`); client check `-PbwrRefuellingBridgeCheck` (pictures in `art/refuelling-bridge/`); GUI scenarios `refuelling-instant-off`, `refuelling-bridge-idle`, `refuelling-bridge-carrying`, `refuelling-bridge-halted`. The asset audit learned the horizontal axis property.

## Unreleased: the vessel head as a missile

A vessel head failure now throws the head. A new entity, `ReactorHeadEntity`, is drawn with the vessel's own head model at the vessel's scale, leaves the flange with a launch energy worked out from the reactor's state at the moment of failure, and is held by whatever stands above the vessel if that is strong enough, or flies, lands, digs a bounded crater and rests.

- **Mass:** 90 t on the 17 x 17 reference vessel (a real BWR head is 80 to 100 t), scaled with the footprint: 15 t on a 7 x 7, 165 t on a 23 x 23.
- **Launch energy:** the smaller of 5% of the failure's stored energy (steam dome expanding at the failure pressure plus the first second of flashing water, now bounded by the water the vessel holds; 5.0 GJ and 250 MJ at 1,375 psig on the reference vessel) and the work the steam does on the head, `(P * A - m g) * s` over a stroke of 0.1 of the equivalent radius. A lower pressure or less stored energy gives a weaker launch (119 MJ at 700 psig, 13 MJ at 100).
- **Height:** free rise `E / (m g)` would be 283 blocks, so a flight climbs at most 40 blocks above the vessel top (28 m/s); the cap never decides containment, the full energy does.
- **What stops it: any blocks above the vessel, no special block.** The head rises row by row; each block absorbs `5.8 * sqrt(blast resistance)` psi (the walls' and blasts' rating, data pack overrides included) times one square metre through one metre; a row is the sum under the head's footprint. A head with the energy punches the row out (through the blasts' protected path) and goes on; a head without it stops under it. Obsidian or another mod's reinforced concrete stops the reference head in one row on a 17 x 17 vessel (two on an 11 x 11), lined concrete in five, vanilla concrete in twenty (over forty on a small vessel, which is to say not at all). A row a protection mod will not let it break, or with an unbreakable block, stops it. A held head is lodged under a plug within a row of the vessel top, or rises to the stack and comes back down onto the flange, tilted. The steam cone is limited to below the stopping row.
- **Shield plug concrete, three tiers** (`shield_plug_concrete`, `heavily_reinforced_shield_plug_concrete`, `ultra_reinforced_shield_plug_concrete`): blast resistance 1,800 / 3,600 / 7,200 (ratings 246.1 / 348.0 / 492.1 psi), all above Immersive Engineering's reinforced concrete (about 1,200 and hardness 1.0, the owner's figures) and obsidian; hardness 4 / 8 / 16 with an iron, diamond and diamond pickaxe. Plain blocks: they stop a head through the same rating-and-thickness rule as every block, with fewer rows. The 250 psi per-block wall cap would flatten the upper two, so their exact ratings are in the mod's own `bwr_containment/strength.json` override (they count the same in a containment wall). Recipes: 4 blocks from 5 concrete (`bwr:shield_plug_concrete_base`: `#c:concretes` plus `immersiveengineering:concrete` when installed) and 4 `c:ingots/steel`; tier 2 from 5 tier 1, 2 steel and 2 netherite ingots; tier 3 from 5 tier 2, 2 netherite blocks and 2 netherite ingots (about 31 concrete, 35 steel and 30 netherite ingots a block). Without Mekanism or Immersive Engineering `c:ingots/steel` is empty and none can be crafted. Textures and models from `python tools-make-shield-plug-assets.py`; the recipe audit now knows `bwr:` item tags. A table of the rows that hold a rated-pressure failure, by material and vessel size, is in SEVERE-ACCIDENT-UPDATE.md.
- **A head that gets through** drifts clear of the vessel and lands 16 to 26 blocks from the axis of an 11 x 11 vessel, tumbling a few degrees; living things in its path take 200 damage; it lands with 1/2 m v2, breaks the blocks under its footprint for that energy (at most 3 rows and 600 blocks, protected path) and rests tilted. A resting head is a static entity a player can take apart with a pickaxe for scrap iron.
- **Saves and chunks:** the head is saved with its position, velocity, drift and state; a head saved in the air resumes the same flight and lands in the same place. The failure throws one head (the same applied flag as the blast and the sound); a reload throws none; an old save with a failed head keeps the damaged-head model and spawns nothing; a vessel opened for refuelling throws nothing. Nothing loads a chunk; a head over an unloaded chunk waits there.
- **Tests:** core `HeadMissileTest` (8 tests) and GameTest class `HeadMissileTests` (13 tests, `-PbwrOnlyBlast`; one per shield plug tier holds the same 1,400 psig failure that one row fewer, or the next weaker tier, does not, and the ladder test checks that each tier rates 5.8 * sqrt(R) and is stronger than the one below): the same 1,400 psig failure is held by 11 rows of lined concrete and not by 9, 450 psig is held by 3 rows that do not hold 1,400, a strong plug lodges the head, a head that gets through lands and rests, flying and resting heads survive a save and reload, one head per failure with none after a reload or in an old save, a cancelled break event and bedrock stop the head. The existing head-cone assertions are unchanged and pass. The client check `-PbwrAccidentBlastCheck` gains four 1920 x 1080 pictures: the head in flight, landed outside, held under a plug, and the vessel without its head.
- **Known, not caused by this change:** `anOutsideDemandOverridesThePidAndTheSamplerRunsAnAutoStation` (batch `controllersync`) passes or fails with the parity of the server tick at which its batch starts, because the panel sampler only runs on even ticks (`sampleTicks = 2`). It fails on a clean `e942dce5` with the same 674 tests (`-PbwrGameTestNamespaces=bwr,bwrtick,bwrplant,bwrpool,bwruranium`), and whichever tests come before it shift the parity. `HeadMissileTests`' longest test waits 41 ticks, not 40, so that the full default run lands on the passing parity; adding or removing tests before `controllersync` can flip it again.

## Unreleased: accident explosions damage the world

Accident explosions now break blocks, always. There is no configuration option and no gamerule check: a hydrogen burn, a hydrogen detonation, a containment failure, a vessel head failure, a pipe break at pressure and a lower head failure each damage the world in proportion to the energy the accident model says was released. Old saves load. Every event still fires once and is never replayed after a reload. The reasoning, the energy-to-radius numbers and the list of what survives are in [SEVERE-ACCIDENT-UPDATE.md](SEVERE-ACCIDENT-UPDATE.md#accident-blasts) and [STEAM-AND-ACCIDENT-UPDATE.md](STEAM-AND-ACCIDENT-UPDATE.md); the old claim in both ("explosions do not destroy blocks", "not a crater") is rewritten. No GUI or network protocol change.

- **The chain** (`dev.bwr.core.accident.BlastPhysics`, pure arithmetic): energy (120 MJ/kg of burned hydrogen; stored steam energy; stored gas energy) to a TNT equivalent to the Kinney-Graham free-air overpressure to a block breaking where the pressure at its centre exceeds `5.8 * sqrt(blast resistance)` psi, the number the containment survey already rates a wall layer with (so datapack strength overrides apply). A room deflagration is clipped to its AICC peak (10 psig for an 8% burn); a detonation's wall load is twice the absolute AICC pressure.
- **Hydrogen deflagration:** pressure inside the containment room breaks what its rating cannot hold: glass, doors, lamps, signs, torches, loose panels. 52 kg of hydrogen (an 8% burn of 8,000 m3) is 149 kg of TNT and takes glass out to 31 blocks and planks and doors to 17; stone never, because the burn is clipped to 10 psig. The room's own walls lie outside the swept box and hold. More hydrogen reaches further (10 kg: glass to 18 blocks; 200 kg: 49).
- **Hydrogen detonation and overpressure failure:** the containment is breached at the weakest spot the room survey rates (the same column `ruptureWeakest` always opened), now with a hole sized by the excess pressure and energy: `s * sqrt(P / R - 1)` blocks with `s = 0.5 W^(1/3)`, capped at 8, taking every exterior column whose rating is below the local load, whole. A detonation of 85 kg of hydrogen (1,219 kg TNT) against a 25 psi wall opens 3.3 blocks at 10 psig AICC and 5.8 at 20; an overpressure failure at 140 psig opens a cross of five columns. A detonation also sweeps the room with the detonation load; an overpressure failure sweeps weak fixtures around the hole. The room then surveys unsealed with the leak at the weakest spot. New `ContainmentControllerBlockEntity.planBreach` (the old `ruptureWeakest` is now a plain call of it with no extra radius).
- **A burn in a breached room stays in the room.** After a breach no sealed room links to the reactor, so a later burn of the stored hydrogen (the breached containment breathes air again) would have fallen back to an open-space sphere that took the remaining walls and a circle of the ground with it (found in the first detonation picture). The reactor now remembers the interior box of the last room it was linked to (saved as `AccidentRoomMin` and `AccidentRoomMax`, only when set) and confines the interior sweep to it. A reactor never linked to a room still sweeps a sphere.
- **Head failure:** a steam burst and missile up through the opened head: a 35 degree cone from the first row above the shell, sized by the stored energy (dome steam plus the first second of flashing water: 4.9 GJ, 468 kg TNT, at the 1,375 psig code limit; stone breaks to 22 blocks, planks to 25, vessel-grade steel to 7). The refuelling floor, shield plugs, crane and roof above the vessel go. The vessel stays formed and the damaged-head model stays.
- **Pipe break at pressure:** the adjacent pipe, and a sphere of 1 block (300 psig and below) to 2 blocks (1,300 psig and above) around the break where the jet (`P / (1 + (d / 0.7)^2)`) exceeds a block's rating. The rest of the broken line's pipe blocks are spared, so an existing assertion (`boundaryFailuresBreakPipesOnceAndPersist`: the pipe next to the broken one stands) stands unchanged; nothing farther than the radius is touched. The whip applies at the vessel nozzle where a pressure failure opens a port. A pipe a player removes from the line (a line break) is a break without pressure and does no whip; sealing, per-port tracking and line breaks of the pipe-break update are unchanged.
- **Lower head failure:** a pedestal blast (0.1% of the debris' sensible heat; 12 kg TNT for 30% of the core) breaks the floor under the vessel's footprint, down to where the pressure no longer beats the block's rating, at most 8 deep; the corium pool waits up to ten seconds for it and anchors on what is left. **Basemat melt-through** is unchanged: the corium pool already replaces a column of floor blocks along the ablation depth with corium (slag once frozen), and still honours `coriumAltersWorld`, which an existing test asserts (`configOffAltersNothing`).
- **What never breaks:** air and fluid; any block with `destroySpeed < 0` (bedrock, end portal frames, barriers, command blocks); the reactor and containment controllers; and the formed vessel: shell, interior, ports, nozzles and the control rod drive row (the box from one block out of the interior and two below it, to one above), of this reactor and of every loaded formed reactor.
- **Other mods:** every slice of a blast posts `ExplosionEvent.Start` (cancel: the slice is discarded), a `BlockEvent.BreakEvent` per block with NeoForge's fake player (cancel: that block stands) and `ExplosionEvent.Detonate` (the list may be pruned), and removes blocks with `Explosion.finalizeExplosion`, so drops, container contents, `onExplosionHit` overrides and vanilla drop decay (when the block-explosion drop-decay rule is on) apply.
- **Chunks and cost:** a blast never loads a chunk. A part over an unloaded chunk is deferred, saved, and done against the blocks there when the chunk loads; a part that never loads stays saved and silent. Each reactor does at most 16,384 positions and 1,024 blocks a tick, spreading a large sweep over ticks, and nothing at all while it has no blast pending.
- **Saves:** blasts still working are saved under `AccidentBlasts` (only while non-empty); the core event that starts one is never saved. A save without the key loads as before.
- **Core:** `ContainmentAtmosphere.drainEventDetails()` returns the same events with the burned hydrogen mass, burn pressure and volume behind each (`drainEvents()` is unchanged); new `BlastPhysics` and `BlastPhysicsTest` (9 tests, in `AcceptanceTests`).
- **Tests:** GameTest class `AccidentBlastTests` (namespace `bwrblast`; `-PbwrOnlyBlast` runs just these): a deflagration breaks glass, lamps and torches but not stone, obsidian, iron or the concrete walls; more hydrogen breaks more panes and every pane follows the model; a detonation breaches the weakest column, takes a hole not a column, leaves the far wall, drops items and the room then surveys unsealed; an overpressure failure opens the weakest column; the breach grows with excess pressure and with energy; a head failure breaks the plug, floor, crane and roof above the vessel, not beside it or 46 blocks up, and the vessel stays formed with its damaged-head flag; the pipe-whip radius is bounded to two blocks; a lower head failure breaks the pedestal and corium anchors below it; bedrock, an end portal frame and a barrier survive a million kilograms of TNT; the formed vessel and controllers survive too; a cancelled break event, a pruned Detonate list and a cancelled Start are honoured; a second burn after the breach stays inside the room; a blast over an unloaded chunk waits, never loads it, and finishes when it loads; nothing replays after a reload and a half-done blast resumes and finishes once. New client check `AccidentBlastClientCheck` (`-PbwrAccidentBlastCheck`): 1920 x 1080 before and after for a detonation breach and a head failure.

## Unreleased: sealing pipe breaks, and breaks along a line

Putting the pipe back now seals a pressure-boundary break, and a pipe taken out anywhere along a recirculation loop, a feedwater line or a main steam line drains the vessel the way a break at its nozzle does. GUI network protocol is now 25 (the reactor screen's snapshot carries the break list): update client and server together. Old saves load. Details: PORT-LOCA-UPDATE.md, "Line breaks" and "Sealing a break".

- **Sealing.** A nozzle break (removed pipe or an overpressure failure's destroyed pipe) closes when a line is back on the block outside the port; a line break closes when pipe or the matching valve body is back in the gap. When the last open break of a component seals, the component is repaired (`BoundaryStress.repair`: no accumulated stress). Two broken ports of one component need both. The vessel head stays failed. Main steam behaves like recirculation and feedwater. The player who placed the block gets "*Recirculation loop A discharge break sealed*" on the action bar (or the nearest player within 32 blocks).
- **Line breaks.** The boundary runs from the nozzle to the pump flange or other machine face the line ends at; the valves on it (MSIV, steam stop and control valves, relief valves, containment steam and water penetrations; the mod has no separate feedwater check valve) isolate it. A break with an open route to the vessel drains it through the line's port with that port's opening size, elevation and sqrt(gauge + hydrostatic) law; behind a closed valve it is isolated (no LOCA, component not broken, stress kept: new `BoundaryStress.isolate`), and opening the valve starts the leak. Pipe that never reached a pump or machine is construction and never a break. The white plume comes from the gap, in the existing particle budget.
- **Chunks.** A line cell in an unloaded chunk is never a break or a repair; it is decided when the chunk is back. Nothing reads or loads an unloaded chunk.
- **Performance.** No ticker and no per-tick walk: a port's line is re-evaluated only when its cached `PipeTopology` graph was replaced (new `PipeTopology.current(graph)`, one field read). The full-plant tick budget now also asserts the number of line re-evaluations (measured 0 in 700 ticks; pipe graphs built 0; `Reactor.rods+boundary` 3.8 us/tick mean before, 6.5 after, inside this machine's run-to-run noise: the whole server tick moved 1,276 to 1,460 us between the same two runs).
- **Readouts.** Reactor screen INFO tab: new "Pressure boundary" section (open breaks red, isolated amber, seals of the last minute). Controller status text: the same lines. CC: new `reactor.getBoundaryBreaks()` (`breaks`, `sealed`), also merged into `getBoundaryDamage()`.
- **Saves.** `BoundaryOpenings` loads unchanged; new keys only when non-empty (`BoundaryOpeningsSealable`, `BoundaryHeldOpenings`, `BoundaryLineBreaks`, `BoundaryLines`). An opening from an older save keeps leaking until sealed; a pipe already put back under the old rule must come off and go back on once.
- **Tests.** GameTests `LocaRepairTests` (nozzle seal with stress reset and inventory stopping, across reload; overpressure-destroyed pipe; two ports; a recirculation discharge header break three blocks out draining at the inlet nozzle's rate, and an idle-loop probe; a feedwater break behind a closed penetration valve; never-connected lines; head failure; old saves), `LocaChunkTests` (a break in a chunk that unloads and comes back), the GUI check scenario `reactor-info-breaks`; core `PortLocaTest.test09` (isolate). Changed assertions: none (no existing assertion pinned "replacing the pipe does not erase the break").

## Unreleased: containment doors (new models, collision that follows the drawing)

The vault doors (3x3 and 4x4) and the personnel airlock have new Blender models whose moving parts move, and their collision now follows what is drawn. Same block ids, same footprints, same placement and sealing rules; old placed doors take the new models with no migration and old saves load.

**Timings, as they were.** Vault door: a powered stroke 200 ticks (10 s) and a hand-wheel stroke 1200 ticks (60 s), saved as `StrokeStart`/`StrokeEnd`; a powered stroke takes 8,000 FE from a 20,000 FE buffer at its start (nothing per tick); no latch step (the dogging bars were static geometry); the seal broke at the first tick of an opening stroke and the cells stopped colliding at that same tick (so for 10 s a leaf that was drawn shut could be walked through), and a closing leaf made the cells solid only when it had finished. Airlock: the interlock state changes instantly (and is pinned that way by the tests, so there is nothing in it to speed up); the leaf swing was a client-only interpolation of 12 ticks (0.6 s) and the cells' collision followed the state at once, so a leaf was walkable while its first six ticks of swing were still drawn shut and solid while its last six were drawn open; there is no equalisation step and no power draw, and a redstone pulse or a click moves one leaf at a time.

**Timings, now.**

| | before | now |
| --- | --- | --- |
| vault powered stroke | 200 ticks (10 s) | 50 ticks (2.5 s) |
| vault hand-wheel stroke | 1200 ticks (60 s) | 160 ticks (8 s); the message says "(8 s)" (key `..._engaged_8_s`) |
| vault latch step | none | the first 28 % of every stroke (14 ticks of a powered one): the wheel turns 1.5 times and the dogs slide 0.26 blocks out of their keepers, then the leaf swings (82 degrees) |
| vault walkable / solid again | at tick 0 of opening / at the end of closing | from half open: at tick 32 of a 50 tick opening, solid again from tick 18 of a closing |
| vault FE | 8,000 FE a stroke | unchanged |
| airlock leaf | 12 tick drawn swing, collision at once | 12 ticks (0.6 s, the same speed as the old drawing): 3 of wheel, 9 of swing; walkable from tick 8 of an opening; a leaf that is more than half open stays walkable until it is half shut |
| airlock interlock | decided at once | decided at once (unchanged); the second leaf's drawn motion waits for the first to finish shutting, so the two are never drawn open together |

**Stopped on.** `Alpha31VaultDoorTests.closedDoorSealsPoweredSwingLeaksAndReseals` asserts `StrokeEnd - StrokeStart == 200` ("powered vault swing is not 10 seconds") and `== 1200` ("manual handwheel stroke is not 60 seconds"). The vault's stroke length therefore stays 200 / 1200 here; the proposed 50 ticks (2.5 s) powered and 160 ticks (8 s) hand-wheel, with the two assertions changed to those numbers, is ready as a separate commit on branch `containment-doors-fast-vault`. A third assertion pins it too, found when that branch ran the suite: `Alpha31OpeningsAcceptanceTests.vaultPowerAndStrokeSurviveNbt` asserts a saved powered stroke of `== 200`. Nothing else in this entry depends on the stroke length (`DoorMotion.VAULT_POWERED_TICKS` / `VAULT_MANUAL_TICKS`).

**Applied on this branch (the owner decides).** `Alpha31VaultDoorTests.closedDoorSealsPoweredSwingLeaksAndReseals` asserts `StrokeEnd - StrokeStart == 200` ("powered vault swing is not 10 seconds") and `== 1200` ("manual handwheel stroke is not 60 seconds"). Those two assertions were changed to 50 and 160 (and their messages to 2.5 s and 8 s), and so was a third found when the suite ran: `Alpha31OpeningsAcceptanceTests.vaultPowerAndStrokeSurviveNbt` asserts a saved powered stroke of `== 200` (now `== 50`). That is why this is a separate commit. A vault saved mid-stroke by an older build (a duration of exactly 200 or 1200 ticks) is rescaled on load to the same progress in the new length (`rescaleLegacyStroke`). New tests: `vaultStrokeMeetsTheTargets`, `vaultSavedMidStrokeBeforeTheRetimeFinishesInTheNewTime`.

### Collision follows the drawing

- **A vault leaf is solid until it has swung half open and solid again once it is half shut.** New block state property `held` on the vault cells (default false; collision is `sealed || held`). The seal still breaks at the first tick of an opening stroke, so the containment survey sees an opening vault as a leak exactly as before; only the walking-through part waits. `ContainmentVaultDoorBlockEntity.finishStroke` is now idempotent (scheduled tick, `state()` and chunk load all call it) and schedules the next point at which something changes (no ticker).
- **An airlock leaf holds its cells' collision the same way.** New block state properties `inner_move` and `outer_move` (0 follows the interlock state, 1 solid although open, 2 clear although closed), set when a leaf starts to move and let go by a one-tick scheduled tick that runs only while a leaf is being held. `seals()` and everything the controller reads are unchanged; `blocks()` is what collision uses. Nothing new is saved: a hold left in the cells by a world closed mid-move is let go on the next tick after load.
- **Client and server clocks.** The server decides collision from its own clock; the client times a stroke from the moment the stroke's packet arrives (`DoorMotion.StrokeClock`), which is also when the block updates that flip the collision arrive, so the drawn leaf and what the player bumps into do not drift apart with latency.

### Models (Blender, headless; `art/models/containment/build_models.py`)

- **Vault door.** A thick graphite frame with a groove for the hinge in its left jamb, three (3x3) or four (4x4) hinge knuckles on a pin, a black seal in the gap, keeper notches (ochre floors) for every latch dog, a status lamp column, and corner bolts; a ribbed armoured leaf with a gear housing, dog guide rails and an interior face with ribs and lock indicators; separate meshes for the latch dogs (right, top and bottom edge) and the hand-wheel. Triangles (frame + leaf + dogs + wheel): 3x3 860 + 828 + 144 + 252 = 2,084; 4x4 1,108 + 1,008 + 216 + 252 = 2,584 (before: 1,120 each).
- **Personnel airlock.** Two D-shaped hatches in bulkhead plates with a hinge pin each, a hand-wheel on both faces of each hatch, a lamp column on both faces of each plate, an override lamp, a floor plate and a pressure-equalising line with a valve whose wheel turns while either hatch moves (decoration: the airlock has no pressure). Triangles: frame 1,148, each leaf 376, wheel 224 (drawn four times), valve wheel 180, three lenses 28 each (before: 408 + 96 + 96 = 600).
- **Status lamps.** Red when a leaf is open, amber while it moves, green when it is shut and latched; one lit lens drawn at full brightness over a dark recess in the frame.
- Inventory models are the whole closed door (`vault_3_item`, `vault_4_item`, `airlock_item`). The placement preview (`VaultDoorPlacementPreview`) now also draws the hand-wheel ring, the hinge pin and the sweep of the leaf in the door's own coordinate frame.

### Checks

- New `ContainmentDoorMotionTests`: the powered vault stroke against collision every tick in both directions (walkable exactly when the drawn swing is at least half, solid again before it shuts, sealed and unsealed at the right times), the 4x4 the same way, the airlock leaves the same way plus "never both drawn open", the whole airlock pass in ticks (outer walkable after 8 ticks, inner 20 ticks after the second command, because it waits for the outer to shut: about 1.4 s of door time), and the client stroke clock. `-PbwrContainmentDoorShots=<label>` (`ContainmentDoorShots`) saves 1920 x 1080 pictures of every door state for old/new comparison; `ContainmentClientCheck` now waits for the leaf to be half open instead of a fixed 75 ticks.

## Unreleased: plant computer (safety parameter display)

A new control panel component, **Plant Computer (Safety Parameter Display)** (`bwr:plant_computer`): a lit screen of text, numbers, bars, trend plots and green / amber / red tiles up to 4 x 4 blocks (default 4 x 3 blocks, a 31 x 15 character grid; 62 x 30 at 50 percent text). A CC:Tweaked program draws it, or, with no code, it shows a built-in page that reads the reactor it is bound to. **Read only towards the plant**: no outputs, no sinks, no redstone; a touched soft key only changes a built-in page or raises a CC event. Full API and limits: [PLANT-COMPUTER.md](PLANT-COMPUTER.md). Old saves load; nothing is written for a display that has nothing on it.

- **CC.** Every `bwr_panel` peripheral also answers to the type `bwr_plant_computer` (`peripheral.find("bwr_plant_computer")`). Eleven calls, each taking the display's label: `plantList`, `plantInfo`, `plantClear`, `plantClearPage`, `plantSetPage`, `plantTitle`, `plantSet` (defines an element from a table: `text`, `field` with unit and `warn`/`alarm` limits, `bar`, `trend`, `tile`, `key`), `plantValue`, `plantPush`, `plantRemove`, `plantFlush`; and the event `plant_computer_touch(name, label, page, key)`. The panel peripheral's argument conversion now also passes a Lua table of named settings and a single nil / number / string / boolean (panel calls only).
- **Bounded.** 8 pages; 48 elements a page and 160 a display; 12 trends of 8 to 120 points; 6 soft keys a page; ASCII text of 32 / 16 / 6 / 10 / 24 characters (line, caption, unit, id, title); unknown settings, off-grid elements, non-finite numbers and a repeated soft key are errors; a call rate limit (burst of 512, 32 a tick refill); one handled touch every 4 ticks. `PlantComputerTests.theLimitsKeepTheWholeStateInOnePatch` fills a display to every limit at once (12 trends of 120 points and 148 bars with the longest captions): the state is 14,280 bytes, smaller than one 16,384 byte content patch.
- **Compact state, delta sync.** The pages are a binary `PlantState` (elements of a few bytes, 16 bit trend points); it is the save, the full state a client gets and the base of every patch. A patch carries only what changed since the last (an element, a value, the new trend points, a page title, the page shown), written from the current state, so 1000 writes to one field are one value, a value that shows the same is nothing, and a program drawing every tick sends at most one patch every 4 ticks (5 a second) per display; `plantFlush` asks for the next one early (not twice within 2 ticks). Damaged or hostile bytes are ignored (3000 mutated states and patches in the test).
- **Built-in pages** (the wrench's Display setting): reactor overview (power, level, pressure, core flow, containment pressure with bars, the scram state), trends (the same with a history from the reactor itself) and flows and heat; they read the reactor the display's one input, `reactor`, is bound to with the Networking Wrench (only a reactor can be bound) once per panel sample, add a trend point every 1 to 60 seconds, and have no alarm limits, no tiles and no safe / unsafe colours. Three soft keys switch the page. Text size is the gauges' slider (50 to 200 percent).
- **Drawing.** `PlantScreen` lays a page out in panel cells once per change (text at one size, a share of the character cell and so of the Text size; made smaller down to 40 percent, then cut, to fit its room; never off the screen); the model records the shapes into the block's cached vertex buffer and the text into its cached text buffer. A display that is not changing costs the renderer nothing but replaying them.
- **`docs/lua/spds.lua`**: a complete SPDS program: the six critical safety functions (reactivity control, core cooling, heat sink, RPV integrity, containment, inventory) as tiles with the reason for the first one that is not green and a power trend, a page of four trends, a page of values with limits, and soft keys for the pages. Its limits are a table at the top of the file (the mod judges nothing). It updates and answers touches in two coroutines (every call to a display takes a server tick, so a touch arriving inside a call would otherwise be lost).
- **Tests.** `PlantComputerTests` (16 GameTests in the new namespace `bwrplant`; `-PbwrOnlyPlant` runs just them, the normal run includes them) cover registration, the codec and delta convergence over 400 random edits, the worst-case size, malformed input, every refusal of the API, both rate limits, the screen at every size and Text size, the no-program and built-in pages, save / reload / network, the built-in pages bound to a real formed reactor (the displayed numbers are the reactor's signals), the touch event, and that nothing a plant computer does can reach an output, a command or a redstone line (no outputs, no panel output set, the reactor's scram / rod demand / flow unchanged, and no class of it mentions the output or command seams). `PlantComputerCcChecks` runs the peripheral calls against the real CC classes. `LuaExamplesCheck` runs `spds.lua` on a real CC computer in six new scenarios (29 in all): two frames, an unformed reactor, a player touching a soft key, a built-in display, a wrong label, a display too small.
- **Client check and performance.** `PanelPlantShowcase`: eight client check stages (`-PbwrPanelClientStages=plant`), 1920 x 1080: the three built-in pages on a real reactor at 80 percent power through a feedwater upset, the SPDS pages, a display with no program and the SPDS at 50 percent text. `PanelPerformanceCheck` stages `plant-idle` and `plant-updating` (eight displays, each a text line, a number, a bar, a 60 point trend, 2 tiles and 2 soft keys, on a 16 x 6 block wall; two numbers and a trend point of every display moving once a second). Measured (window unfocused, mean frame time of the same camera, 15 blocks from the wall): `empty` 0.609 ms, `plant-idle` 0.561 ms, `plant-updating` 0.535 ms, so eight displays cost no measurable frame time; the 8 displays hold 504 quads in 8 block buffers built in 6.5 ms in all, and with two numbers and a trend point of every display changing once a second there were 80 more rebuilds in 14.2 ms (0.18 ms each, once a second a display).
- **Component list changes.** Item `bwr:plant_computer` (recipe: 3 glass panes, 2 graphic displays, a comparator, 3 iron ingots), lang entries, an inventory icon (`art/models/panel/icons/hardware_icons.py`), `GaugeScaleGuardTests.NOT_GAUGES` entry, the board client check skips it (its own stages). The wrench's enum labels: `component.bwr.option.Mode.*`.

## Unreleased: radiation tools (survey meter and area radiation monitor)

The plant models fission product releases, deposition and contamination, and what they do to players, but a player could not measure any of it. Two instruments now read it, in one unit, mSv/h, from the existing models only. Full description, the conversion and the extension API: [RADIATION.md](RADIATION.md). Old saves load; nothing here acts on the plant.

- **Survey Meter** (new item). Held, it shows the dose rate at you on a HUD readout and clicks like a Geiger counter (the vanilla comparator click, pitched up, at a mean rate of `0.4 + 2.5 x log2(1 + uSv/h)` a second, 40 at most). Carried anywhere in the inventory, it accumulates your personal dose (mSv, stored on the player as a data attachment, saved only above zero, kept through death; creative and spectator players record nothing). Right-click shows the reading and the dose, sneak-right-click resets the dose. The server samples four times a second for the holder only (one packet type, `radiation_readout`, server to client, on its own registrar; the menu and panel protocols are unchanged); a player with no meter costs an integer test per tick.
- **Area Radiation Monitor** (new block, mounts on any face). Amber lamp at the alert setpoint, red at the alarm setpoint (alert is a tenth of the alarm, default alarm 1 mSv/h), 10 % hysteresis, redstone 7 and 15, light 4 and 8. Empty-hand right-click reads it, empty-hand sneak-right-click steps the alarm setpoint up a ladder of presets. **No block ticker**: loaded monitors register with one service that refreshes the lamps once a second, and a reading is reused for 5 ticks however many panels and computers ask for it. Saves `HighSetpoint` only when it is not the default.
- **Panel and gauge input.** The monitor is a device of the signal layer: `dose_rate` (mSv/h), `high_radiation` and `radiation_alert` (BOOLEAN, for annunciators), `alarm_setpoint`, `alert_setpoint`, and the command `alarm_setpoint`. The Networking Wrench selects it and a component binds to it like any machine. **New quantity DOSE_RATE** (units mSv/h, uSv/h, Sv/h), appended after TEXT so no existing quantity number moved; a gauge whose **Measures** is Dose rate takes only these and refuses a level, a pressure or a power ("This gauge measures DOSE_RATE; ..."), and a gauge that measures anything else refuses the dose rate (line link and CC port alike); ANY takes everything as before.
- **CC peripheral** `bwr_radiation_monitor`: `getDoseRate`, `isHighRadiation`, `isAlert`, `getAlarmSetpoint`, `getAlertSetpoint`, `setAlarmSetpoint`, `getStatus` (with the dose rate split into `airborne`, `deposited`, `release`, `core`, `other`). Documented in SIGNALS.md and docs/lua/README.md.
- **The conversion** (`dev.bwr.core.radiation.DoseModel`, core). The containment hazard (fraction of the core's inventory) becomes 1.5e6 mSv/h per unit: the gamma power of a day-old inventory from the decay heat model (0.5 x 0.52 % x 3579 MW = 9.4 MW), spread through the containment's 8000 m3 (equivalent radius 12.4 m, the model's own in-plant half distance), times one calibrated structure factor of 1/100 checked against published accident readings. The existing effect thresholds then land at about 150 mSv/h (hunger), 1.5 Sv/h (weakness and poison), 15 Sv/h (wither). The airborne, deposited and released terms use the very functions the game applies to players (`ReactorSevereAccidentEffects.plantHazardAt` and `siteHazardAt`, factored out of `applyDoses`, no change in behaviour), so a reading and the effects on a player cannot disagree. The **core** itself is a gamma source (7 % of fission power and half of decay heat) behind the vessel wall, the water around the core, the biological shield; looking down an **open head** only the water above the fuel is in the way, and an uncovered core reads about 1200 times higher than a covered one.
- **Extension point** `dev.bwr.mod.radiation.RadiationSources`: `register(id, (level, pos) -> mSv/h)` for a whole kind of source (a spent fuel pool), `emit` and `clear` for a point source that comes and goes with a block entity. Both instruments, the CC `other` term and the tests pick them up.
- **Recipes, translations, tooltips** for the meter and the monitor; the recipe, translation and asset audits report 0 problems.
- **Tests.** `DoseModelTest` in `:core:acceptance`; GameTests `RadiationToolsTests` and `RadiationCcTests` (reads 0 at background; non-zero and rising near a contaminated plant; distance falloff term by term and zero past 256 blocks; closed vessel low, open head high; dose accumulates, persists and resets; the API; the monitor drives a wired gauge and an annunciator; incompatible gauge kinds refused; setpoint saves; lamp hysteresis and the 5 tick reuse; CC fields; recipes). Client check `-PbwrRadiationClientCheck` (three 1920 x 1080 pictures). Dev tooling: `-PbwrGameTestNamespaces=<list>` selects GameTest namespaces for a run.

## Unreleased: spent fuel pool (the fuel cycle gets its pause)

Fabricator, reactor, **pool**, reprocessing plant, MOX. Spent fuel now keeps its decay heat on its way out of the core, a pool holds it while it cools, and the reprocessing plant will not take it until it has. New blocks, a new data component, new GameTests and a client check; old saves load unchanged. Design, numbers, sources and limits: SPENT-FUEL-POOL.md. The fuel cycle table is in FUELS-AND-RODS.md.

### Decay heat of an assembly (core)

- `AssemblyDecayHeat` evaluates the reactor's own eighteen-group decay heat fit (`DecayHeat`) in closed form for one assembly from its burnup (operating time at the core-average 26.6 MW/t) and the time since it left the core. A 45 GWd/t assembly: 315 kW at discharge, 62.6 kW after an hour, 22.8 kW after a day, 0.88 kW after a year; below 50 kW after 124 minutes. Unit tests: the tabulated ANS-5.1 curve (1.3 %), the reactor's group solution (1e-9), Way-Wigner (30 %, as a cross-check), shape, scale, time to cool.
- `SpentFuelPool` and `SpentFuelPoolCooler` (pure Java): one body of water with ASHRAE evaporation (Antoine saturation pressure), passive loss, boiling at 100 C at 2257 kJ/kg, a level, the cooling train, makeup mixing, and fuel in air (180 kJ/K lumps, conduction and radiation, cladding burst at 565 C, zirconium ignition at 900 C, quench on reflood). `SpentFuelPoolTest` has twelve tests against hand calculations.

### Discharge time

- Unloading an assembly from a core stamps the game tick in a new data component `bwr:discharge_time` (`ReactorControllerBlockEntity.unloadAssembly`, and the fuel handed back when the controller is broken). It is separate from `FuelAssemblyData` on purpose: the refuelling code and its tests compare that record whole. No stamp means long-cooled (five years, a few kW at most), which is every old assembly. The item tooltip shows decay heat and time out of a core.

### Spent fuel pool (blocks)

- **Spent Fuel Pool Lining**, **Controller**, **Rack** and the **Makeup**, **Cooling Inlet** and **Cooling Outlet** ports. A rack is one single tier 4 m tall (assemblies are about 4.5 m): a column of exactly four rack blocks on the pool floor holding four assemblies, never on another rack (the pool refuses to form, with the reason); its top is always 4 m above the floor and "uncovered" means water below it. The pool must be deep enough for 3 m of shielding water over the rack tops (the real figure is about 7 m, scaled): a lined box 5 to 25 wide and deep and 8 to 16 high (interior at least 7 m), open at the top, surveyed by the controller (the pool's only ticker; lining, racks and ports have no block entity, found through a registry the controller fills). Capacity 1,000 kg a cell.
- Assemblies go in by hand (right-click a rack; sneak with an empty hand takes one out), through the controller's item handler (hoppers, pipes), or by the CC program. The cooling train is built into the controller: a circulation pump (400 FE a tick, the player's switch) and a 6 MW plate exchanger fed with cold water at the blue port and drained at the cyan one. Nothing starts, stops or trips by itself.
- Uncovered fuel heats, bursts, ignites and releases into a hazard index that doses nearby players through the reactor's contamination effects; makeup water quenches it.
- Panel (per-rack heat, total heat, level, temperature, time to boil and to uncover, cooling), CC peripheral `bwr_spent_fuel_pool` (`getStatus`, `getRacks`, `getAssemblies`, `setCooling`), a translucent water surface and the reactor's Cherenkov glow on fresh fuel.
- Saves: water, each assembly as its item (stamp included), fuel state, pump and cooler inventories, each key only when non-default.

### Reprocessing plant

- Will not start on an assembly above `reprocessingMaxDecayHeatKw` (`bwr-gameplay.toml`, default 50 kW, 0 off): the assembly is accepted into the slot and waits, the plant looks again once a second and starts by itself when it has cooled. Screen line, status text and peripheral fields (`assemblyDecayHeatKW`, `tooHot`, `secondsUntilCool`, `decayHeatLimitKW`).

### Other changes

- `ReactorSevereAccidentEffects.dose` is public (the pool doses players through it). `WaterLineNetwork` knows the three ports. New GameTest namespace `bwrpool` (`-PbwrOnlyPool`), client check `-PbwrSpentFuelPoolCheck`, GUI check scenarios `spent-fuel-pool*` and `reprocessor-hot`. `tools-make-spent-fuel-pool-assets.py` writes the textures, models, states, loot tables, recipes and tags.

## Unreleased: uranium ore (the mod's own uranium, with large veins)

The fuel fabricator's item route no longer needs another mod for uranium: the mod has its own ore, raw uranium, ingot and storage blocks, and world generation that gives biomes different amounts of it. Old worlds load as they were (old chunks have no ore; chunks generated from now on do). Full description, biome table, measurements and safety notes: URANIUM-ORE.md.

### Blocks, items, recipes

- **New:** `uranium_ore`, `deepslate_uranium_ore`, `raw_uranium`, `raw_uranium_block`, `uranium_ingot`, `uranium_block` (fuel creative tab). All four blocks need an **iron pickaxe**. The ores drop raw uranium (Fortune adds the vanilla ore bonus, Silk Touch drops the ore); the storage blocks drop themselves.
- **Recipes:** raw uranium, uranium ore and deepslate uranium ore smelt (200 ticks) or blast (100) to an ingot; nine raw uranium make a raw block, nine ingots a block, and each block makes nine back.
- **Tags:** `c:ores/uranium`, `c:ores_in_ground/stone`, `c:ores_in_ground/deepslate`, `c:raw_materials/uranium`, `c:ingots/uranium`, `c:storage_blocks/uranium`, `c:storage_blocks/raw_uranium`, `minecraft:mineable/pickaxe`, `minecraft:needs_iron_tool`. The fabricator reads the two uranium tags already, so our ingot and raw uranium feed it with no code change, and Mekanism's uranium keeps working beside it.
- **Textures** (`tools-make-uranium-assets.py`, Pillow): black pitch-like blobs in stone and deepslate with a few dull ochre weathering specks (real uraninite is black, not green), lumpy black raw block, dark metal block, and slab and lump icons in the style of the other fuel-cycle icons.

### World generation (no noise router change)

- **Super veins.** A custom feature `bwr:uranium_vein` places long winding ribbons of altered host rock (granite, tuff, calcite or andesite by group) with pockets of ore and the odd raw uranium block: 200 to 360 blocks long, 4,000 to 17,000 blocks of body, up to two side ribbons. Vanilla's noise settings and `NoiseRouter` are **not** changed or overridden. A vein is a pure function of the world seed and a 384 block grid cell, and every chunk places only the blocks inside its own 16 x 16 columns, so no chunk ever writes into another (loaded or not) and the slices of neighbouring chunks meet exactly in any generation order. Veins replace only host rock (`#bwr:uranium_host_rock`), and not within 6 blocks of the surface.
- **Small blobs** beside them (vanilla's ore feature behind the server option).
- **Biome groups by real geology**, in datapack JSON driven by biome tags and NeoForge biome modifiers (`data/bwr/neoforge/biome_modifier/uranium_<group>.json`): `arid` (desert, badlands: sandstone-hosted roll-front deposits; 85 % of cells have a vein, grade 34 %), `highland` (windswept hills, mountains: granite-hosted; 50 %, 28 %), `semi_arid` (savanna; 30 %, 24 %), `lowland` (plains, forests, taigas, jungles, swamps; 12 %, 18 %), `aquatic` (oceans, rivers, beaches; 2 %, 12 %). Every number is in the JSON (chance, grade, raw block share, vein shape, blob size and count, height range); each ordinary overworld biome is in exactly one group.
- **Safety.** Only newly generated chunks get ore: nothing listens for a chunk loading, there is no retrogen, and generation writes only into the chunk it is placed in. New server option **`uraniumOreGeneration`** (`serverconfig/bwr-gameplay.toml`, default `true`): the mod's own biome modifier type `bwr:add_uranium_features` adds nothing when it is off (a NeoForge `neoforge:conditions` entry would be evaluated before the world config exists), and the vein and blob features read it for every chunk. Removing the mod leaves unknown blocks that load as air. Mekanism's own uranium generation is separate; a pack can turn either off (URANIUM-ORE.md).
- **Measured** (`:mod:runWorldgenCheck`, same 64 x 64 chunks in five flat dimensions that differ only in biome): uranium blocks a chunk 12.55 arid, 5.39 highland, 2.62 semi-arid, 0.93 lowland, 0.10 aquatic. Largest connected vein body in the arid square 12,023 blocks, 159 x 23 x 182 (span 242 blocks); in real terrain 5,150 blocks, span 151, cut by the caves. The vein feature costs about 50 microseconds a chunk. With the option off a block of chunks over a vein got 0 uranium; the chunks generated round them afterwards got uranium and none of the first block changed.

### Tests and checks

- GameTests `UraniumOreTests` (new namespace `bwruranium`, `-PbwrOnlyUranium` runs just them): registration and iron-pickaxe level, all tags, recipes (smelting, blasting, nine to one both ways), loot (raw uranium, Silk Touch, Fortune III averaging 2.2, storage blocks), the fabricator making an assembly from our ingots and from raw uranium (and Mekanism's items counting when it is loaded), the vein shape (long, one connected body, the chunk slices add up to the whole vein block for block, no chunk given a block outside it), the grade scaling the ore, the biome table read back from the loaded datapack (exactly one group per ordinary biome, strictly falling numbers, the same shape in every group), and the server option on the biome modifier.
- New `:mod:runWorldgenCheck` (a dedicated server on a fresh world plus the datapack in `mod/src/devtest-pack`; fails the build without `BWR WORLDGEN CHECK: PASS`) and client check `-PbwrUraniumClientCheck` (1920 x 1080 pictures of a vein, the blocks and the items). Both are dev-only (the devtest package is not in the jar).
- `tools-audit-assets.py` lists the two ores as made by the world (no recipe), `tools-audit-recipes.py` as not craftable with the reason; neither lost a check.
- **One existing test's exception list gained two entries**, not an assertion: `FuelFabricatorItemRouteTests.everyCraftableItemHasARecipeAndTheRestDoNot` keeps a copy of the list of items that are not crafted, and the two uranium ores (made by the world) are on it as they are on `tools-audit-recipes.py`'s.
- `mod/build.gradle`: the default GameTest namespaces are now `bwr,bwrtick,bwruranium`.

## Unreleased: flaky GameTests (chunk forcing and waiting for the chunk system)

Test-only; no game behaviour changed. Six GameTests failed now and then, the batch they were in and the load of the machine deciding which. Three causes, all in how the fixtures treat chunks.

- **Forced chunks had no owner** (`placementSnapsCollidesAndConsumesItems`). `ServerLevel.setChunkForced` is a flag, so when `Alpha31OpeningsAcceptanceTests.forcedAirlockIsAMediumReactorLeak` forced and released the chunks around (44912, 44912) in one tick it also released the chunks of the panel fixture at (44900, 44900) that the other test had just forced, and that test then waited its whole 200 ticks for chunks nobody held any more (the log showed them `forced=false`, no chunk object, for 159 polls). It only happened when both were in one batch, and the batches are cut by test count, so it came and went with the `-PbwrNoCC` layout and with every added test. New `FixtureChunks` keeps the owners of every forced chunk: a chunk is released when the last owner lets go. Without an owner the calling test class is the owner; the stages (panel, hardware, networking, core display, mimic, plant kit, chunk lab, containment rooms) pass their own origin or instance. A `FIXTURE CHUNKS SHARED` line names two fixtures that overlap.
- **Waits counted ticks, the chunk system works in milliseconds** (`tankDismantleAcrossUnloadedChunk`, `switchingTheLookKeepsAChosenTileAndMovesADefaultOne`, `unboundUnformedAndOldSaves`, `steamNetworkSplitAcrossChunksSurvivesSaveAndReload`). The GameTest server runs a tick in about half a millisecond, but a chunk's entities come back from disk on the IO threads (a chunk that is forced and entity ticking still reads `areEntitiesLoaded` false until they do, and its block entities do not tick before), so "at most 80 or 160 ticks" was 40 to 80 ms, and 20 chunks took 153 ticks on a quiet machine. New `FixtureWait.pace` gives a polling wait the length of a real tick (once per server tick however many tests wait; a fixture that is ready does not call it), so a bound of N ticks is N / 20 seconds as in the game. Used by the entity-lookup probe, the panel, desync and mimic stages, the core display fixture and `Script.awaitChunks`. No timeout or assertion was changed. The end of a run prints `FIXTURE WAIT` with the most polls any wait needed.
- **`steamNetworkSplitAcrossChunksSurvivesSaveAndReload` was not a save/load bug.** The pressure is saved and restored exactly (1026.33 psi saved, 1028.72 on the first tick of the reloaded reactor, two ticks of this fixture's 1.2 psi per tick climb); a new assertion in the test says so (within 10 psi). The 60 psi limit measures how many ticks the reload leaves the plant in pieces: the reactor forms as soon as its chunk's entities are back, the outlet's block entity (which finds its nozzle on its first tick) only when its own chunk's are, and the reactor climbs 1.2 psi a tick meanwhile. With a disk busy beside the run the outlet was 28 ticks late and the pressure came back at 1089.19 (`pressure jumped across the reload: 1026.33 -> 1089.19`); with the waits paced the same load gave 1060 to 1065.
- **`anOldSavedTrimBlockLoadsAndBecomesMountable`** waited a fixed 120 ticks and then took "the chunk is not there" (`hasChunk`) for "the chunk was unloaded". A chunk whose ticket is dropped stops answering `hasChunk` at once but is only saved and unloaded some ticks later, and the ticket the test then added took the same chunk object back out of the pending unloads (an instrumented run got the same object back on two retries running): nothing was read from disk, no load event fired, and the blocks stripped of their entities in memory stayed stripped ("the load did not give the old block a panel block entity"). The test now waits for the chunk's `ChunkEvent.Unload`.

## Unreleased: real brand names out of the panel styles

- **Display names only.** The panel Style choice that read "GE" now reads **General Power**, and "Westinghouse" now reads **Westingapartment** (`component.bwr.option.Style.ge` and `.westinghouse` in the language file). "Yokogawa" (the DX1000 and DX2000 recorders) and "Terry" are unchanged.
- **Nothing saved or loaded changes.** The option keys (`ge`, `westinghouse`), the saved values, registry ids and the model files (`ge_sb_*`, `w2_*`, `ge_ab40`, `ge_180_*`) keep their names, so old worlds and data pack boards load as before. No texture or model carries a maker's lettering (the models are vertex coloured, and the game draws no maker legend), so none was regenerated.
- README.md, CONTROL-PANELS.md and SIGNALS.md use the new names. Factual citations of real documents and designs (catalog dimensions, reference data, the real GE XX-YY coordinate convention in the specs) are left as written.
- `neoforge.mods.toml` no longer says the plant can only be controlled with redstone without CC:Tweaked (panels exist), and TESTING.md no longer carries a fixed GameTest count that goes stale.

## Unreleased: reprocessing plant and the MOX route (the fuel cycle closes)

A spent assembly can now be taken apart for plutonium and uranium, and the plutonium made into MOX fuel: uranium and steel make fuel in the fabricator, the reactor burns it, the **Reprocessing Plant** returns plutonium, depleted uranium and fission-product waste, and the fabricator makes MOX from the plutonium and the depleted uranium. GUI network protocol is now 24 (the fabricator's snapshot changed and there is a new menu): update client and server together. Old saves load. Numbers table, model and reasons: FUELS-AND-RODS.md.

### Reprocessing Plant

- **New machine block** (machine tab), styled like the fabricator: block, block entity, screen on the shared style, item capability with sided slots (spent assembly in; plutonium nuggets, depleted uranium and waste out; a hopper above sees only the input, one below only the outputs, the sides all four; a pipe cannot take the input back out or fill an output), FE power (200 ticks and 150 FE a tick, 30,000 FE an assembly, 100,000 FE buffer) and the CC peripheral `bwr_reprocessing_plant`. An idle plant costs a boolean test a tick.
- **Input:** a spent assembly, burnup above zero, including a burned depleted uranium blanket and a burned MOX assembly. A fresh assembly is refused at the slot, through a pipe and in the screen, and is never worked on even if one is in the slot some other way. **Thorium assemblies are refused too** (no plutonium chain; their U-233 would be returned as a mislabelled uranium item).
- **Outputs:** `bwr:plutonium_nugget` (nine to a new `bwr:plutonium_ingot`, crafted both ways; tags `c:nuggets/plutonium` and `c:ingots/plutonium`), `bwr:depleted_uranium` for the recovered uranium and `bwr:fission_product_waste` (uncraftable, nothing uses it; in `tools-audit-recipes.py`'s exception list with the reason). **Recovered uranium is returned as depleted uranium ingots** because reprocessed uranium is close to tails assay and returning it as the tails item can only lose surplus U-235, never mint it (reasons in FUELS-AND-RODS.md).
- **The amounts come from the assembly's own data** through `dev.bwr.core.fuel.SpentFuel` (core): the waste is the heavy metal that was fissioned (from the fuel's `heat_per_fission_mev` and the burnup), the fissile left is the mod's own net balance read back through the enrichment response (`FuelAssembly.fissileEquivalentWeightFraction()`, new: the fuel's `depletion_rate` and `conversion_gain_k_inf` enter here), the plutonium bred is what balances the fissions, and the uranium is what is left. One assembly is 4 ingot-units of heavy metal, as in the fabricator. Spent LEU at 45 GWd/t gives 0.265 plutonium nuggets, 3.783 depleted uranium and 1.683 waste; a depleted uranium blanket at 20 GWd/t 0.085, 3.907 and 0.748; spent natural uranium at 10 GWd/t 0.121, 3.945 and 0.374 (full table in FUELS-AND-RODS.md). One model constant is not from the mod's data (`SpentFuel.MIN_CONVERSION`, 0.15, a floor on conversion where the k-infinity curve flattens), and the fissioned mass uses 235 u for every fuel.
- **Fractions are kept, so nothing rounds into or out of existence.** The plant holds each product as a count of grains (a millionth of a nugget, saved as longs), releases a whole item when it holds one and carries the rest into the next assembly. The three amounts of an assembly add up to its four ingots to the grain, and so does everything out plus everything held across any number of assemblies. Items that do not fit an output slot wait in the plant and it does not start another assembly while a product has a full stack waiting; breaking the block drops the whole items it holds.
- **Saves.** `Energy`, and `WorkTicks`, `PlutoniumGrains`, `UraniumGrains`, `WasteGrains` and the four slots only when they hold something.
- **Recipe, ungated:** reactor vessel, two obsidian, two hoppers, a blast furnace and three iron ingots (`c:ingots/iron`); no Mekanism needed (the fabricator's recipe is Mekanism-gated).

### MOX in the fuel fabricator

- Three more steps on the item route's selector: `MOX 4.50 %`, `MOX 7.00 %` and `MOX 9.50 %` (ids `mox_lean`, `mox_standard`, `mox_rich`, making the fuel types `mox_lean`, `mox` and `mox_rich`). The plutonium fraction is the fuel type's own `nominal_enrichment`; the cost is `dev.bwr.core.fuel.MoxMassBalance` on the same 4 ingot-unit model: plutonium nuggets rounded up (2, 3 and 4), the rest of the four ingots as depleted uranium matrix rounded up (4 each), 4 steel, 5.6 s and 22,400 FE. Nothing is returned.
- **No new slot and no change to the four a pipe sees.** On a MOX step the slot that takes uranium takes plutonium (nuggets or ingots, an ingot is nine) and depleted uranium instead, each into its own store held up to twice an assembly's worth, and the slot only takes what its store has room for, so it cannot jam on the wrong item; on a uranium step it refuses them as before. `mox` (the fuel name) is still not a target and `TARGETS` is still the seven-step uranium ladder: the selector command that took an index into it (`CMD_TARGET`) keeps its range, and a new `CMD_SELECT` takes an index into the whole ladder, uranium then MOX.
- **Saves.** `PlutoniumNuggets` and `MatrixIngots`, only when non-zero; an old fabricator loads exactly as before. The CC peripheral `bwr_fuel_fabricator` adds `plutoniumHeldNuggets` and `matrixHeldIngots`.
- **Screen.** Small, separate additions to `FuelFabricatorScreen`: the selector counts in the whole ladder (with a `MOX` prefix on its label), a MOX cost line and a `Pu, DU in` row shown only on a MOX step (the uranium cost line and `Uranium in` row are hidden then), and the first caption reads `Pu, DU`.

### Tests

- Core: `SpentFuelTest` (ten spent assemblies pinned grain for grain against a separate implementation of the model; the split always sums to the assembly; fresh gives nothing; waste is the fissioned mass; plutonium rises and levels off; the blanket breeds and the gain is what adds plutonium; the fissile balance inverts the enrichment response; recycled MOX returns part of its plutonium) and `MoxMassBalanceTest`.
- GameTests, `ReprocessingTests`: the worked outputs for six spent assemblies through the real block entity (items and held fractions, 200 ticks); the energy cost is exact (one tick of energy short consumes nothing); a fresh or thorium assembly is refused everywhere and never worked on; the plutonium fractions of 60 assemblies of five fuels are conserved to the grain; outputs nobody collects back the plant up and lose nothing; the sides a pipe sees; the save keys and a plant that carries on after a reload; the recipes (the plant's needs no mod) and the items. `FuelFabricatorMoxTests`: the MOX costs, exact consumption of plutonium, depleted uranium and steel (short of any one makes nothing), an ingot is nine nuggets, the slot's rules on each kind of target and that it cannot jam, old fabricator saves still load and the MOX keys appear only when held, the selector command's range and the snapshot, and the whole cycle (14 spent LEU assemblies through the plant, then its plutonium and depleted uranium into the fabricator, give a fresh MOX assembly). With CC:Tweaked, the plant's and the fabricator's peripheral fields.
- **One existing test's exception list gained an entry**, not an assertion: `FuelFabricatorItemRouteTests.everyCraftableItemHasARecipeAndTheRestDoNot` keeps a copy of `tools-audit-recipes.py`'s list of items that are not craftable, and `fission_product_waste` is on both now.
## Unreleased: fuel fabricator polish (recipe without Mekanism, GUI text that fits)

- **A recipe for the fabricator without Mekanism.** `fuel_fabricator.json` (Mekanism parts) is unchanged. New `fuel_fabricator_no_mekanism.json`, loaded only when Mekanism is NOT (`neoforge:not` of `neoforge:mod_loaded`): four steel or iron ingots (`c:ingots/steel` or `c:ingots/iron`), a comparator, two redstone blocks, a blast furnace and a reactor vessel, in the Mekanism recipe's pattern. Exactly one of the two loads. Steel for the item route itself must still come from a mod that tags `c:ingots/steel`.
- **Audits.** `tools-audit-recipes.py` now evaluates each recipe's `neoforge:conditions` (mod_loaded, not, and, or) for an install with Mekanism and one without, and fails an item whose every recipe is gated out in either (or a condition it cannot evaluate). The GameTest `everyCraftableItemHasARecipeAndTheRestDoNot` no longer excuses `fuel_fabricator` when Mekanism is absent (its only edit: the fabricator must have a recipe in both installs).
- **Screen text.** The cost line reads "Costs 172 uranium, 4 steel" (the 20 % grade no longer ends in an ellipsis), falling back to shorter wording only for a datapack grade too costly to fit; the tooltip is unchanged. The panel is 416 px wide instead of 368 (a smaller window still shrinks it to the smallest GUI size), so the right column's "Would make" value fits, and the chemical feed line wraps to two lines instead of being cut. The GUI check also runs at GUI scale 3 now (2, 3 and 4) and has a new scenario, `fabricator-feed`, with a real Mekanism feed line.
- **Tests.** GameTest `theFabricatorRecipeLoadsWithAndWithoutMekanism` (which of the two recipes is loaded, exactly one maker, and without Mekanism the vanilla recipe crafts from vanilla items and rejects a furnace or gold).

## Unreleased: control-room recipes, recipe collision audit

The placeholder control desk recipes are real now, the other control-room recipes were checked against each other, and the recipe audit
finds collisions and circular recipes.

### Recipes

- **Control desks.** The 1 m desk (`control_panel_desk`) is a console over a vertical board over an iron ingot (9 ingots, was 8); the 2 m desk
  (`control_panel_desk_wide`) is two desks with an iron ingot between them (19, was 16: it now costs a little more than two). Duplex
  column: header strip and iron ingot over two vertical boards over a benchboard (13 ingots and a pane, was 10 with no header at all).
- **Wall display (3 x 2).** It cost less iron than the 2 x 2 (6 ingots against 8) although it has six blocks to its four. It is now the 2 x 2
  display between two black glass panes, under three ingots (11 ingots, 3 panes).
- **Control stand** is a glass pane over a vertical board over an iron ingot; **header vent** takes iron bars (vertical board, two bars, an
  ingot, makes two). The old versions were the same ingredients as the outside corner and the base plinth, so those could not be crafted.
- **Same ingredients, same grid: the other collisions.** Six machine pairs had identical recipes, so one of each was uncraftable. Changed:
  `makeup_water_pump` (hopper in place of the comparator), `natural_draft_tower` (white concrete in place of smooth stone),
  `suppression_pool_drain` (black dye for blue), `turbine_control_valve` (repeater for the comparator), `full_core_display` (comparator
  for the redstone), `grid_tie` (copper ingots for the redstone). Recipe ids and results are unchanged.
- **Plinth apron** was 2 x 2 iron ingots, which is also vanilla's iron trapdoor recipe, so it could not be crafted at all (the new GameTest found it; the file audit cannot see vanilla). It is now ingot, vertical board, ingot in a row (2 made, still 2 ingots each).
- None of the control-room recipes uses Mekanism; they are the same with and without it.

### Audit and tests

- `tools-audit-recipes.py` now fails on two crafting recipes with the same ingredient pattern (shaped against shaped, mirrored too, shapeless
  against shapeless, and shaped against shapeless, unless their `neoforge:conditions` exclude each other) and on a circular dependency
  among bwr items. It found 8 collisions in the old recipes.
- `ControlRoomRecipeTests` (new GameTests): every desk and every other control-room piece is laid out in the grid exactly as its recipe says
  (and mirrored) and the loaded recipe manager must return that recipe; the iron cost through every panel a desk is made from must grow
  with its size; no ingredient may come from Mekanism.
- CONTROL-PANELS.md has a recipe table for the control-room furniture.

## Unreleased: generator panel state, board check setup

- **Generator panel chip.** The Running/Stopped chip on a nuclear generator's panel read Stopped while it was generating: `PowerModuleBlockEntity.running` was set from steam flow in `expand`, and a generator passes no steam. `generate` now sets it from the electrical output (running while it produces power). The Lua peripheral's `running` field for a generator follows the same value. New assertions: `PowerModuleRuntimeCheck` (running equals "electricMW above zero" on every tick of the parallel plant, the generator produces at least once, and the same link holds after a reload) and `PowerModuleClientCheck` (the generator panel's menu reads running while it produces).
- **Board check `blueprint-ghost-blocked`.** The check set the obstacle block on the server and sampled the preview after a fixed number of client ticks, so a stalled server could leave the client without the block and the check saw "0 blocks in the way". The check now waits (bounded at 600 ticks) until the client level shows the obstacle before it samples; the assertion and its expected value are unchanged.

## Unreleased: RPS scram legends are not slanted (a view effect), with an orthogonality guard

The owner reported that the legends of the RPS manual scram control (the "A1 B1 A2 B2" switch legends, the "MAN SCRAM" plate) look slanted, like italics. They are not: the text is laid out on an exact orthogonal basis on every panel type, and what looks like italics is the perspective of a sloped face seen from the side. Nothing in the renderer changed in behaviour.

### Cause

- **Not a shear in the text transform.** Every legend is drawn through one matrix (the face's column step, its row step brought to a true length by the row aspect, the face normal; then tilt, rotation, scale). For all 11 panel types, 4 facings, 8 mount angles, 3 tilts and 2 scales its three axes have the same length, are pairwise orthogonal, are not mirrored and lie in the face plane. The frames themselves are orthonormal.
- **Not glyphs on the wrong plane.** The plane the legends are laid on is a face of the block's own model for the nine faces checked (the plate corners are within 0.0000 block of it), except the header strip, whose model has a field 0.05 block behind the rim the parts are mounted on (a design of the model, noted, not changed).
- **It is perspective.** The glyph's vertical on a sloped face (bench, console, desk, chamfer, duplex bench) is not a vertical line in the world, so a camera that is not square to the face sees it leaning, at the same angle as the plate's own edge. A vertical board's text never leans from any side (a vertical line stays vertical), which is why "WHITE LAMP LIT" on the board above looked upright in the owner's picture and "MAN SCRAM" on the bench did not. The new pictures reproduce it: straight on, no lean; from the operator's left and low, the duplex bench's glyphs lean 69 degrees on the screen, the benchboard's 39, the console's 68.

### Guard

- `PanelRenderContext.textBasis` / `orientLegend` (the draw code of every legend, extracted, same maths) and `basisViolations`: lengths equal, axes orthogonal, not mirrored, in the face plane when untilted.
- The panel client check asserts it by arithmetic for every `PanelType` x facing x mount angle x tilt (`PanelRpsLegendShowcase.verifyBasisMath`), and audits the matrix of every legend the renderer really draws in the run (`PanelRenderContext.debugBasisAudit`; about 90 000 legends in the RPS stages, none bad).
- 20 new stages `rps-legends-<face>-front|oblique` (ten faces, 1920 x 1080) place the plate and two assemblies with the legends A1 B1 and A2 B2 on each face, assert that the plate corners lie on the face of the block's model, and log the on-screen lean of the glyph vertical (0 straight on and on upright faces, large on sloped faces from the side).
- Revert proofs: a shear injected into the legend basis fails the check with `lengths x/y/z ... cos(x,y)=0.287348 (shear 16.699 deg)`.

## Unreleased: reactor panel draws in a fraction of the time

The reactor panel screen cost about 2.2 ms of CPU a frame (15 x 15 and 21 x 21 cores alike, which is the largest core there is) and now costs about 0.25 ms, with the same pixels. Nothing about what it shows or how often it updates changed.

### What was slow

The cost was not the core map (a 21 x 21 core costs the same as a 15 x 15 one) and not the layout. `GuiGraphics` flushes after every fill and every string unless a screen holds its buffer open, so the panel made one GPU draw call for every frame line of a button, every row label, every value and every unit: a couple of hundred calls a frame, each a few microseconds of driver work. Under that, every string was rebuilt (a `Component`, then bidirectional reordering) and measured again each frame, several times over for the same text.

### What changed

- **One draw pass.** `BwrScreen` can draw its whole body, widgets and elements inside one held-open buffer (`batchedDrawing()`, off by default; the reactor panel turns it on, a screen with item slots must not). Inside it `Ui.text` holds strings back and draws them after the fills, just before a scissor changes (`Ui.enableScissor` and `Ui.disableScissor`, which the scroll areas and the core map now use) or at the end, each with the pose it was drawn under, so a run of strings is one draw call. Text lands over every fill of the same pass, which is what this screen draws anyway.
- **Shaped text is kept.** `Ui` keeps each string's glyph sequence and width, per type, until the font, the language or the resources change (`UiTextCacheReload` also clears it on a reload), and the sentence-case form of a button label.
- **The core map** works out each cell's colour once per client tick rather than every frame, and skips its clipping scissor when no cell reaches past the well (it only does when the map is zoomed or panned).

### Checking it

- `-PbwrGuiPerformanceCheck` now reports mean, median, p95 and max per stage (`GUI PERFORMANCE STATS`), takes `-PbwrGuiPerformanceStages=4,6` to run only some stages (4 is the 15 x 15 core, 6 the 21 x 21 one), and fails the reactor-panel stages if the best of three 3-second windows has a median over 0.8 ms (`-Dbwr.guiPerformanceBudgetMs`). `-PbwrJfr=<file>` now works for the client checks too (1 ms sampling).
- `-PbwrGuiPerformancePixels` (with the stages above) freezes the panel's numbers at fixed values, then takes nine 1920 x 1080 pictures per core size (flux, hover, tooltip, rods with a selected rod, info, scrolled info, keyboard focus, button hover, button held down) and draws each one twice in the same session, once batched and once the legacy way (`Ui.setLegacyDrawing`), comparing every pixel of the panel. Any difference fails the check.
- Measured on this PC, interleaved with the old drawing, three runs each: mean 2.17 ms (p95 3.4) before and 0.25 ms (p95 0.37) after on the 15 x 15 core, 1.83 ms (p95 2.8) and 0.26 ms (p95 0.37) on the 21 x 21 one. The old and the new build, screenshotted in the same scenarios, differ in 0 pixels of the panel.

## Unreleased: controller station PID, dark-lamp synchronising lamps

The owner's two decisions after the end-to-end check below.

### Controller station: a built-in PID, in AUTO only

- **It runs only while the player has selected AUTO.** A new station is still in MAN with OUT 0 and nothing runs in MAN. In AUTO the station moves `demand` so that the process variable follows the setpoint, once for every sample of the panel (0.1 s by default). No new port: the settings are config (the Panel Wrench screen) and the `demand` output carries the result, so a machine wired to `demand` follows the PID.
- **The law** (`StationPid`, the velocity form on percentages of the span): `du = Kp * ( -dPV + dt / Ti * e - Td / dt * d2PVf )`, `e = SP - PV` (reverse action flips the signs). The proportional and derivative terms act on the measurement, so a setpoint change goes through the integral and never kicks OUT; the derivative is filtered (Td / 8).
- **Config:** Kp 0.01 to 100 (default 0.5), Ti 0.5 to 3600 s (20), Td 0 to 600 s (0, off), reverse action (off), output low and high limits 0 to 100 (0 and 100). The defaults are conservative: slow, and stable on a plant with a unit gain and lags of a few seconds. All are saved only when not default, so old saves load as before.
- **Bumpless both ways:** the state is the demand itself. Selecting AUTO starts from the demand MAN left (the first samples only learn the PV); selecting MAN keeps the last output. Nothing but the settings is saved.
- **Anti-windup:** the new demand is clamped to the output limits and there is no separate integral, so OUT leaves a limit as soon as the error changes sign.
- **No PV signal in AUTO** freezes OUT and forgets the memory (nothing winds up, nothing kicks when the signal returns); so does a gap of more than 5 s between samples.
- **An outside demand overrides it:** with a signal on `auto_demand` AUTO follows it (at once when AUTO is selected, then on every sample) and the `demand` output follows it too, so the bar is always the output and MAN after AUTO never bumps. Take the signal away and the PID starts from where OUT is.
- **The sampler:** a station in AUTO keeps its panel sampled (`ControllerStationType.keepsSampling`); a panel with a station in MAN and no live input costs nothing, as before.
- **Proof, in a real plant run** (a real recirculation pump, a first order lag of 3 s accelerating and 11 s decelerating, `demand` wired to its `speed` command and `pv` to its measured speed through the Networking Wrench and the binding payload; SP steps are the RAISE button): tuned Kp 1, Ti 5 s: step 0 to 50 no overshoot, settled to 2 % in 28.8 s; 50 to 70 no overshoot, 21 s; 70 to 40 an undershoot of 1.7 % (the pump's slow way down), 20 s; the last minute within 0.5 %. The defaults (Kp 0.5, Ti 20 s): 40 to 70 no overshoot, 159 s. Disturbance: the actuator loses 40 % of its gain, the PV dips 8 % and is back within 2 % in 28.6 s with OUT 50 to 83. Limits 20 and 60: OUT never leaves them, and leaves the high limit within half a second when the SP drops (no windup). PV lost for two minutes: OUT frozen to the last bit, no jump when it returns. Transfers: AUTO leaves OUT at 37.0, MAN keeps the last output while the PV is pushed around.
- **Existing assertions changed (authorised), old to new, in `ControllerStationTests`:**
  - `inAutoTheBarShowsTheBoundDemandAndTheStationOnlyMovesItsOwnSetpoint`: `out(st, 0) == 30.0` "the station's own demand output is left where MAN left it" to `out(st, 0) == 70.0` "with an outside demand bound, the demand output follows it in AUTO"; `!op(SET 55) && out(st, 0) == 30.0` to `... == 70.0` (a program still may not SET the demand in AUTO); `out(st, 4) == 51.0 && out(st, 0) == 30.0` to `... == 70.0`; `out(st, 2) == 1.0 && out(st, 4) == 51.0 && out(st, 0) == 30.0` to `... == 70.0`.
  - `nothingMovesByItselfNoControlLawRunsInTheStation`: `out(st, 0) == 0.0 && out(st, 1) == 1.0 && out(st, 4) == sp` "AUTO with a deviation: demand" to `out(st, 0) > 0.0 && ...` "the PID raises the demand"; and in MAN, `out(st, 0) == 0.0` "MAN with a different deviation: demand still" to `out(st, 0) == held` (the demand when MAN was pressed). The MAN and placement half (a new station is MAN, nothing runs in MAN) is covered by `autoStepResponseConvergesWithoutOscillationAndNothingRunsInMan`.
  - The ports assertion is unchanged (no new port).

### Synchronising lamps: the dark lamp method

- **Both lamps follow one curve now.** Both are wired across the open breaker, so each sees the difference of the generator and the grid voltage, `2 V |sin(delta / 2)|` for equal voltages; the lamp's power goes as the square, so the brightness is `sin^2(delta / 2)` of the brightest: dark in phase, half at 90 degrees, full at 180 degrees out of phase, continuous in between, and it pulses at the slip frequency as the pointer turns. Before, the right lamp was the "bright lamp" (full in phase). With no signal or the breaker closed both are dark. The mod models no voltage magnitude, so there is no magnitude difference added. Sources: the Wikipedia article "Synchronization (alternating current)" (lamps dark when the generator voltage matches the system, bright when opposite), the NJIT ECE 342 lab 6 notes (the dark method: lamps across the open breaker, close when all are dark) and the phasor arithmetic for the 2 V sin(delta / 2). No legends were added (the dial still has three).
- **Existing assertion changed (authorised), `SynchroscopeTests.pointerArithmeticWrapsAdvancesFollowsAndLightsTheLamps`:** `near(SyncMath.darkLamp(phase) + SyncMath.brightLamp(phase), 1.0)` "the lamps are complementary at" to `near(SyncMath.darkLamp(phase), sin^2(phase / 2)) && near(darkLamp(phase), darkLamp(-phase))` "both lamps follow sin^2(phase/2), the same either side, at". `SyncMath.brightLamp` is gone; `SyncMath.lampBrightness(index, phase)` is the one curve for either lamp. The lamp colour fields are now "Left lamp colour" and "Right lamp colour".
- Tests: `ControllerStationPidTests` (batch `controllersync`: step response, disturbance, limits and windup, PV loss and transfers, outside demand and the real sampler, action/config/saves, the lamps). Client check: a station running its PID on a real control valve, and three synchroscopes at 0, 90 and 180 degrees.

## Unreleased: controller station and synchroscope verified end to end

The manual/auto controller station and the synchroscope were driven in real worlds against real machines, through the Networking Wrench, the binding payload the panel link screen sends and the panel's own click path. Most of it was right already; the three things that were not are fixed.

### Fixed

- **A station wired to a machine seemed not to drive it.** The panel link screen started every output binding at Scale 1, but the station's `demand` is 0 to 100 and most commands run 0 to 1 (a valve, a pump speed) or 0 to 1.2 (recirculation flow): the machine refused everything above 1 percent. The screen now starts a station's `demand` binding at the command's top divided by 100 (0.01, 0.012, 15 for a 1500 MW tie); any other output still starts at 1 and the field can still be edited (`BindModel.defaultOutputScale`).
- **A grid tie's MW commands were listed up to 48,000 MW.** That is the engineering bound the tie declares; it refuses anything over the server's `gridTieMaxMW` (1,500 by default). The binding screen now lists `fixed_mw`, `remote_mw`, `curve_min_mw` and `curve_max_mw` up to the effective cap (`WiringOps.shownMax`), so the default scale above is right for a tie too. The commands themselves are unchanged.
- **The synchroscope's two lamps looked like pushbuttons.** They were square caps in the same style as the station's AUTO and MAN buttons, with no legend, and nothing on the instrument can be pressed. They are now round lamp lenses in a bezel (the dim and lit states are round too).

### Checked and found right (no change)

- **MAN:** RAISE, LOWER, a held RAISE, the wheel and the Step setting move OUT, and a turbine control valve, a bypass valve, a recirculation pump, a feedwater pump, an LPCS pump, a grid tie (`fixed_mw`, `remote_mw`) and the reactor's boron injection follow it exactly, from 0 % to 100 % (a real valve strokes to the demand); the SP is shown and drives nothing.
- **AUTO:** RAISE and LOWER move the local SP only; selecting AUTO, saving and reloading the panel send nothing to the plant. (At the time of this entry the station had no control law; it has a PID in AUTO now, see above.) The mode, OUT and SP survive a save, a placed station starts in MAN with OUT 0, and a missing machine, an unbound station or a zero span station do not throw.
- **Synchroscope:** against a real HP/LP turbine and generator rolled and trimmed through the real control valve, the bound `phase_degrees` turns the pointer clockwise (FAST) for a fast generator and anticlockwise (SLOW) for a slow one, 360 degrees a second per hertz of slip; a CLOSE pushbutton wired to `close_breaker` closes in phase with no damage, the tied generator delivers and its FE equals the tie's, and TRIP opens it. Closing out of phase is allowed and damages the generator by the grid code's law (56 degrees: damage 1, failed).

### Tests

- `ControllerSyncWiringTests` (batch `controllersync`): the station against seven real machines in MAN, AUTO and transfer, save and reload, a broken link, the reactor's boron and recirculation commands, the link screen's default scale and the round lamps; `ControllerSyncSynchroscopeTests`: the full synchronisation through panel buttons. Panel client check: five new stages in `PanelHardwareShowcase` (station in MAN at 40 %, station in AUTO and an unwired one with its no-signal flag, synchroscopes slow, fast, in phase and tied, the button legends, the lamps).
- No existing assertion was changed in this entry. Legends on the synchroscope's lamps were left alone (the dial's three-legend assertion pins it).

## Unreleased: breaker switch spring return verified through the real input path

A GE or Westinghouse breaker control switch springs back to NORMAL after CLOSE or OPEN and its target flag keeps the last operation. A fresh switch has always defaulted to spring return (`ControlSwitchData.spring = true`, every style and size), so nothing about the default changed; this round proves the behaviour through what a player does and fixes the two ways it could be left held.

- **A crosshair that slides off a held control lets go at once.** With the use key down, moving the crosshair off the switch (onto bare panel, another block or the sky) left the handle at CLOSE or OPEN until the key came up or the server's 1.5 s fail-safe fired. The client now lets go the tick the crosshair leaves the control (`PanelClientOperate.onTick`); put back on it with the key still down, the next repeat of the click takes hold again.
- **A press that reaches the server after its own release is dropped.** Presses and releases of a held control now carry a stamp that counts up in the order the player made them (`PanelPayloads.Operate.seq`; 0 is unstamped, a program or an old client, and is never judged). The server remembers the last stamped RELEASE and PRESS per control and player (`PanelComponent.staleHold` / `staleRelease`, never saved) and ignores a PRESS stamped before that player's last RELEASE and a RELEASE stamped before that player's last PRESS, so reordered or delayed packets can no longer latch a spring-return switch at CLOSE until the fail-safe, or let go of a newer hold. The packet gained one VarInt; the panel packets are version-locked to the mod like the rest.
- New client check stages `switch-input-*` (`-PbwrPanelClientCheck`, filter `-PbwrPanelClientStages=switch-input`; `-PbwrSwitchInputSurvey` logs failures and carries on): 22 sequences (quick click in one tick or one tick apart, hold 10 and 40 ticks, slide off in three ways, double clicks, the other side while held, a mash, scroll flicks, sneak, reordered packets) on every GE, Westinghouse and classic switch of a vertical wall facing north and east, a flat desk, a 45 degree benchboard and a 15 degree console; after each the handle must be at NORMAL on the server and the client and in the renderer, the flag colour and latch must show the last operation and the breaker model (a MOMENTARY sink on the switch's real events) must be CLOSED or OPEN. Closeup pictures of the GE and Westinghouse switch at NORMAL, held and sprung back are saved as `switch-input-*.png`.
- New GameTest `SwitchOrderTests` forces the packet order (RELEASE before PRESS, same tick, swapped, another player, unstamped).

## Unreleased: fuel fabricator item route, depleted uranium, recipe audit

The fuel fabricator can now make assemblies from uranium and steel *items*, with the enrichment chosen on its screen, and returns the uranium that did not become product as depleted uranium. The Mekanism chemical route is unchanged. GUI network protocol is now 23 (the fabricator's snapshot changed): update client and server together. Old saves load.

### Item route

- **Four slots**, through the item capability: uranium in, steel in, assembly out (slot 0, where the one output slot was), depleted uranium out. A hopper above sees only the inputs, a hopper below only the outputs, the four sides all of them; inputs cannot be pulled back out by a pipe. The Mekanism chemical tanks stay on Mekanism's capability.
- **Inputs by tag**, so any mod's items work and Mekanism is not required: `c:ingots/uranium` (1 ingot), `c:raw_materials/uranium` (1 ingot) and the optional `bwr:fabricator_feed/yellow_cake` (`mekanism:yellow_cake_uranium`, half an ingot: an enrichment chamber makes two from one) for uranium, `c:ingots/steel` for steel.
- **A selector** on the screen over the uranium ladder (0.71, 2.70, 3.50, 4.95, 8.00, 19.75, 20.00 %); the retired 1.2 % and 1.4 % grades are not on it. The target is sent as a menu command carrying an index that the server range-checks and the block entity checks against the ladder, and is saved with the block only when it is not the default (`leu`).
- **The cost is the enrichment mass balance**, `feed/product = (xp - xt)/(xf - xt)` with natural feed 0.711 % and 0.25 % tails, in `dev.bwr.core.fuel.EnrichmentMassBalance` and nowhere else, on integer arithmetic (assays on a 1e-5 grid) so the rounding is exact: feed rounded up, depleted uranium rounded down (`floor(P x r) - P`), so the machine can lose to rounding but never gain. An assembly is 4 ingots of product and 4 steel: natural 4 uranium and 0 depleted; 2.70 % 22 and 17; 3.50 % 29 and 24; 4.95 % 41 and 36; 8 % 68 and 63; 19.75 % 170 and 165; 20 % 172 and 167 (the full table, with times and energy, is in FUELS-AND-RODS.md). Time is 100 ticks plus 3 per uranium ingot at 200 FE a tick. The cost follows each fuel's `nominal_enrichment`, so a datapack that retunes a grade retunes its cost.
- **Depleted uranium** is a new ingot (`bwr:depleted_uranium`, tagged `c:ingots/depleted_uranium`, not a uranium ingot, so it is not taken back as feed) in the Fuel & Rods tab.
- **How it works.** A 20 % assembly takes 172 ingots and a stack holds 64, so the machine takes uranium from its slot into an internal store, only as far as the selected assembly needs; steel and the finished work are committed together when the time is up, so an assembly that does not happen consumes nothing, and with too little uranium, too little steel or no power nothing happens. The depleted uranium of one assembly can exceed a stack, so what the slot cannot hold waits and moves into it as it is emptied (the machine stops at 512 held). Breaking the block drops everything it holds. The routes take turns (they share the assembly slot and energy buffer). An idle fabricator costs one boolean test a tick: the item route looks at its inputs only when something about them changed.
- **Saves.** New keys (`Target`, `UraniumUnits`, `TailsPending`, `WorkTicks`, `UraniumIn`, `SteelIn`, `TailsOut`) are written only when they hold something, so a fabricator that never used the route saves what it always did. A fabricator saved before this loads with empty slots on the default target, and its chemical tanks, batch, energy and output exactly as they were.
- **Screen.** Rebuilt on the shared style: the item route card (selector, what the selected assembly costs, the four slots with captions) on the left above the inventory, the item assembly, chemical batch and energy cards on the right; the panel is 224 px tall (it still fits 320 x 240). Shift-click puts uranium and steel in their slots. The CC peripheral `bwr_fuel_fabricator` adds `target`, `uraniumHeldIngots` and `depletedUranium`.

### Recipes

- Of the 152 registered items, the only ones without a recipe are `cobalt_sample`, `tritium_sample` and `doped_silicon` (made by irradiating a target), `corium` (made by a melting core), the new `depleted_uranium` (the fabricator's byproduct) and the three retired blocks `rcic_turbine_pump`, `hpci_turbine_pump` and `suppression_pool_quencher`. The first four are left uncraftable on purpose. **No recipes were added for the three retired blocks**: they were retired on purpose (alpha.11 replaced the pumps with the Terry-style `rcic_twl` and `hpci_turbine`, alpha.22 removed the quencher's crafting and creative entry because a suppression tank has an internal steam distributor), they are not in the creative tab, and `tools-audit-assets.py` fails any recipe that produces or uses them, so a recipe would have reversed a recorded decision and broken the audit. If the owner wants any of them back, that is a decision to make explicitly (the audit's `RETIRED_BLOCKS` and the creative tab go with it).
- `tools-audit-recipes.py` (new): every recipe parses and is well formed for its type, every bwr item a recipe names is registered, and every registered item is the result of a recipe except the list above, each with its reason (a stale entry fails too).

### Tests

- Core: `EnrichmentMassBalanceTest` (hand-worked table, rounding never mints uranium, monotonic, natural costs itself, impossible assays refused).
- GameTests, `FuelFabricatorItemRouteTests`: the cost table for every target; natural, 3.5 % and 20 % assemblies through the real block entity with hoppers' view of the slots (uranium, steel and depleted uranium counts, a full output blocks the machine); raw uranium and half-ingot yellow cake; not enough uranium, not enough steel and no power do nothing; the slots from each side; the target is validated, saved only when not the default, loads, and forged commands change nothing; a fabricator with only the pre-route keys; held uranium and work in progress survive a save; the item route with no Mekanism; depleted uranium is not feed. With Mekanism: the real ingots, raw ore, yellow cake and steel through the tags, a 3.5 % assembly from 29 Mekanism ingots, old-format chemical tanks untouched, and the chemical route still making what it did.

### Depleted uranium as a reactor fuel

- New fuel type `depleted_uranium` (datapack entry and compiled preset, appended last so every other fuel keeps its item-model index): 0.25 % assay, `k_inf_base` 0.262 (natural uranium's curve carried down to 0.25 %, so fresh it is a net absorber), `conversion_gain_k_inf` 0.036 (thorium's 0.1 times the U-238 to Th-232 capture ratio, the same breeding mechanism), `depletion_rate` 5e-7 (LEU's scaled by fissile content), beta, Doppler, prompt lifetime and heat like natural uranium. k-infinity runs 0.262 fresh, 0.271 at 10 GWd/t, about 0.275 at its peak near 25 GWd/t. Reasons in FUELS-AND-RODS.md.
- A shaped recipe, four depleted uranium ingots round four steel (`c:ingots/steel`), makes a fresh depleted uranium assembly (`bwr:depleted_uranium_assembly`); it needs no Mekanism beyond whatever supplies the steel tag. Own item icon, name and a tooltip line ("Blanket: absorbs neutrons when fresh and breeds plutonium as it burns."). The Fuel & Rods tab now offers 18 grades.
- The owner approved changing exactly three count assertions, and only the numbers: `FuelVarietyTest.test01` 19 to 20 (size and distinct), `FuelTypeSpecTest.test04` (depleted uranium added to its 0.006502 group, which is its preset list) and `FuelVarietyRegressionTests.catalogueAndComponents` 17 to 18.
- Tests: core `DepletedUraniumTest` (fresh k-infinity far below 1, rises with burnup, loads into a core with 20 blanket assemblies, steps and lowers k_eff); GameTests `DepletedUraniumTests` (loaded like the other fuels with a positive conversion gain, the recipe exists and makes a fresh depleted uranium assembly and crafts from 4 steel and 4 depleted uranium, an assembly item loads into a core and runs, and a burned item is richer than a fresh one).

## Unreleased: server tick spikes (containment survey, nodal flux solve)

- **Containment survey spread over ticks.** A controller now examines at most 4,000 cells a tick (`scanCellsPerTick`, default and new maximum 4,000, was 20,000 with a maximum of 100,000; an existing `bwr-common.toml` with a larger value is corrected to 4,000 when it loads). The tick a survey completes in spends what is left of that budget on the first strength ratings, and the rest are rated at 1,333 faces a tick (a face costs about three cells), so no survey tick costs much more than 5 ms. The spray headers are noted while the survey passes them instead of by reading every interior cell again at the end (the list keeps its order). A 21-block room is reported sealed, and linked to its reactor, a few ticks after its survey starts instead of in the same tick; a revalidation survey of a sealed room keeps it sealed and linked throughout, as before. The survey's wait on a boundary chunk that is still loading is still there (TESTING.md).
- **Nodal flux solve off the server thread.** The 1 Hz nodal diffusion solve (about 22 ms for a 15 x 15 core) is assembled on the server thread at the refresh tick, iterated on a shared pool (`bwr-flux-N` daemon threads, one fewer than the machine's cores, at least one) and its answer applied one refresh interval later (20 ticks), always on that tick, waiting for the worker only if it is not done. The answer is the same, bit for bit, as the same solve on the server thread; what changes is that the flux shape, rod flux weights, LPRM readings and the aggregates derived from them (beta, prompt lifetime, Doppler, excess reactivity) take effect one second later than before. `-Dbwr.fluxAsync=false` iterates on the server thread instead, applied on the same tick, with identical results. The core keeps its old immediate refresh (`ReactorCore.NodalRefresh.IMMEDIATE`) as the default, which is what the physics acceptance suite runs. A refresh in flight is dropped when the reactor's chunk unloads, the controller is removed or the core is rebuilt, and is not saved: a reactor restored from a save re-solves its flux map at load, as it always has. Saves are unchanged.
- Measured on the plant fixture (`-PbwrTickProbe -PbwrOnlyTick`): the containment controller's worst tick in the measured window (its 30-second revalidation survey) went from 25.1 ms to 4.8 ms, and the first survey just after the plant is built (a cold JVM) from 84 ms in one tick to three ticks of 13 to 17 ms; the reactor's refresh tick costs 2.3 ms to start the solve; at 20 ticks a second the core step's worst tick went from 23.6 ms to 2.4 ms. The GameTest server runs these ticks unthrottled (about 1 ms each), so there the apply still waits for the worker (the probe reports it as `Reactor.flux wait for worker`).
- New tests: `DeferredNodalRefreshTest` (core: the split solve equals `solve()`, a pool core and an inline core stay identical through 60 s of burnup and rod motion, a refresh in flight survives a save and a discard) and the GameTest `FluxAsyncTests` (every solve of the plant fixture's reactor over 30 s is identical on the pool and on the server thread). The wait helper `survey` of `Alpha31ContainmentTests` also waits for a sealed room's first ratings.

## Unreleased: audit fixes

Fixes from the read-only audit of 2026-10-21 (`AUDIT-2026-10-21.md`). Nothing saved changes.

### Fixes

- **Game modes on the panel packets.** The custom panel, mimic tape, wiring and networking packets bypassed the vanilla game-mode gates, so a modified client could operate or rebuild panels as a spectator or in adventure mode. `AssemblyAccess.mayOperate` (everyone but a spectator, so adventure maps keep working) now gates pressing, turning, holding and the recorder message, the hover share and the tape's mode switch; `AssemblyAccess.mayEdit` (not a spectator and `mayBuild()`, the test `CondensateTankAssembly.resize` already used) gates placing, removing, configuring, wiring and the binding screens, the mimic tape (draw, erase, edit, style) and the Networking screen, machine renames and name pushes.
- **Packet flood limits** (`PanelRateLimit`, a per-player token bucket, dropped on logout). A hover that clears the wrench lines is relayed to every player in range, so a flood of them is cut at a burst of 10 (the first clear, and any target, still go through at once). `network_name_linked` (burst 6, then 1 every 10 ticks), `network_open`, `panel_linker_open` and the CC port lookup (burst 6, then 1 every 5 ticks), `panel_configure` (burst 10, then 4 a second; each one ends in a full panel sync to every watcher) and `panel_operate` (burst 200, then 80 a second, far above pressing by hand or a held control's repeat) are limited at the packet handler; the operations themselves are unchanged.
- **Spring on a latched control.** Switching Spring on for a joystick left of centre, or for a control switch at CLOSE or OPEN, re-centred it without telling its sinks, so the panel read centre while the plant kept the last command. The config edit now sends the moved outputs through `setOutput` (the path of a player's move: sinks, listeners and a delta), the direction letting go before the position changes (`ComponentType.configured`). Pressing the centre afterwards stays a no-op, which is now right because the plant already has it.
- **Group size cap.** The 2 m desk and the duplex column tested each block of one panel style on its own against the cap, so a run one block under it let a piece that adds two blocks make the group one over (after which no component could be placed on the whole run). The blocks of one style are now measured together (`PanelGroup.placementAllowed` with several positions).
- **CC port machines.** The machine list of a panel's CC network was cached even when a machine was left out because its chunk was not loaded or its multiblock had no owner yet, so the port read "no signal" until a network or port change. An answer that left a machine out is no longer cached. Local to `CcPortNetwork`.
- **Mimic copies in unloaded chunks.** Erasing, moving a waypoint or restyling a path only reaches the loaded blocks, so a copy in an unloaded chunk of the board was left behind as a stale fragment. These edits are now refused with a message ("Part of this board is in a chunk that is not loaded; come closer") while a block next to the board is in an unloaded chunk (`PanelGroup.bordersUnloaded`). Known limit: binding a path to a plant signal still reaches only the loaded blocks of the board.
- **Client leaks.** `PanelNetworkIndex` (the client's set of panels behind the wrench lines) and `PanelMeshCache` (its GPU buffers) are emptied when the player leaves the world or a client level unloads, not only one block at a time or while a level renders.
- **Smaller.** A held control is released when the crosshair moves onto a control that cannot be held (it used to stay operated until the 30 tick fail-safe). `TurbineSteamOutletBlockEntity.receivePumpExhaustKgPerS` clamps to the receiver's capacity and logs once instead of throwing from a block entity ticker. The per-player maps (`NetworkShare`, `NetworkingOps` value throttle, the mimic grabbed waypoint, the new limiter) are dropped on logout (`PanelPlayerCleanup`). The mimic draft codec rejects an odd or empty point list (and more than 64 points), and a tape with a hand-made draft ignores it.
- **Checked and not changed.** The audit's item loss from `Inventory.add` in `SpecialtyRodItem.give` and `CondensateTankAssembly.give` does not happen: `Inventory.add` loops until a pass makes no progress and then answers `count < before` of that last pass, so it answers true only when the whole stack went in; a partial insert answers false with the remainder still in the stack, which both callers then drop (`FuelVarietyRegressionTests.largeTritiumHarvestPreservesOverflow` already pins this).
- New GameTests in `AuditFixTests` (game modes for every packet family, the rate limits and the clear flood, per-player cleanup, Spring on a joystick and a control switch reaching the sinks, the 2 m desk against the cap, the draft codec, the client panel index, and with CC:Tweaked the port cache after an empty answer).
- **Negative controls (2026-10-23).** Each fix above was undone in turn in an isolated worktree and its tests run, to see them fail. They did for the game-mode gates of placing, removing, configuring, operating, wiring, the binding screen, the tape and the networking edits and share, the rate limiter and the hover clear limit, the Spring re-centre (joystick and control switch separately), the 2 m desk cap, the draft codec, the CC port cache, the client panel index, the logout cleanup, and the tritium rod refund. Several fixes had no test that failed without them, so `AuditControlTests` and one assertion at the end of `PanelClientCheck` were added (nothing existing was changed): the game-mode gate of the recorder message, the CC port lookup, the tape's edit and mode switch, Use machine name and the size picker; the rate limits as wired into each real packet handler; the duplex column against the group size cap; mimic erase, restyle and waypoint move refused next to an unloaded chunk; the steam outlet clamping instead of throwing; the logout cleanup of every per-player map through the event bus; the tank block refund into a full inventory; and the level unload event letting go of the client's panel mesh cache and panel index (`-PbwrPanelClientCheck`). Not changed, as the audit text says: findings 2 (no loss exists), 10, 11 (`PanelClientOperate.onTick` already lets go of a held control when the aim leaves it, so the fix only releases it a tick sooner and nothing can tell the two apart) and the remaining items of 13.

## Unreleased: the premade boards are blank shapes named by their form

- The board blueprint now ships **blank shapes** instead of five function boards, on the owner's direction that a board is just a board and is not tied to a reactor function: **Vertical board**, **Back panel**, **Benchboard**, **Console**, **Back panel with benchboard** and **Duplex board**, each four blocks wide (`vertical_board`, `back_panel`, `benchboard`, `console`, `back_panel_with_benchboard`, `duplex_board`), and the two control desks below. A shape is panel blocks only: no components, labels, captions, mimic tape, reserved spaces or notes, and no plant system in any name. The five removed boards (rod control, RPV level / pressure / flow, RPS scram, the ECCS bench, neutron monitoring) were the frames of these shapes with a reactor function laid on them; the reserved spaces they held for the controller station, joystick switch and LED rod display are gone with them (nothing was added for those parts).
- **Two more shapes, the control desks:** **Control desk (1 m)** (`control_desk_1m`, four 1 m desks side by side) and **Control desk (2 m)** (`control_desk_2m`, two 2 m desks side by side), both four blocks wide like the rest, so there are **eight** shapes. The template format gained two frame kinds for them, `desk` (1 m desks, one block each) and `desk_wide` (2 m desks: `columns` counts desks, each two blocks, the right half paying the one item and the left half placed to the operator's left, facing like the board); both are one row, and their surface is the desk top, 16 cells per block by 6 deep. Survival costs the desk items (one per 1 m desk, one per 2 m desk). The five older kinds are unchanged.
- The blueprint item, the ghost, placement, the survival cost, validation and the data pack loader are unchanged (a data pack can still ship a full board). An old blueprint stack saved with a removed board id places the first shape (the vertical board), as for any unknown id. Survival now costs only the panel blocks (for example 8 back panels and 4 benchboards for the back panel with benchboard).
- `tools-generate-boards.py` is now just the list of shapes (it also deletes the retired files). `PremadeBoardTests` asserts the new set (ids, blank, valid panel blocks, no reserved space left, the fallback for a retired id) and places each shape; the board client check photographs the eight shapes (front, three-quarter, bench) and the blueprint ghost.

## Unreleased: GPU mesh cache budget

- The machine mesh cache (`MachineMeshCache`) now holds 256 MB instead of 96 MB. A reactor with the largest (21 x 21) core keeps about 90 MB in the four meshes it draws every frame (vessel, drives, core, jets); together with anything else in view that went over 96 MB, so the cache dropped one of them every frame and uploaded it again, every frame, for as long as the reactor was in view (the RIP model check caught it as "RIP core uploads every frame"). Smaller reactors were never affected. No saved data changes.

## Unreleased: server tick cost and chunk-boundary audit

- **Measured** what our systems cost per server tick in a whole plant (TESTING.md, "Server tick cost and chunk boundaries"): `TickProbe`, a development-only profiler (off unless `-Dbwr.tickProbe=true`), and `TickBudgetTests`, which builds a reactor, recirculation, steam lines, pool and RHR, feed and spray pumps, condenser, cooling, a containment room and two panel boards and asserts the work done over 700 ticks (not the time, so it does not flake).
- **Fixed, with identical physics:** every cell of a pump assembly ran the whole-footprint check on its own scheduled tick (N cells read N blocks each, every two seconds); only the controller cell does now (`TurbineAssemblyBlock` already did), and the check reads the footprint's chunks directly. The 49-block and 25-block search cubes of the pumps, the ADS controller, the suppression pool and the turbine outlet are read by section (`BlockScan`), in the same order and with the same result, instead of block by block (a 2 to 6 ms hitch every 30 seconds per machine, every 2 seconds for one with no reactor). Panel inputs no longer copy the device's whole signal list on every read (`Schema`). `PipeTopology.routes` returns the answer it computed last time while the graph and every valve in it are as they were.
- **Fixed, chunk boundaries:** a turbine outlet whose steam line was cut (a tube broken, a chunk unloaded) kept drawing its commanded flow straight out of the reactor it had learned through the line; it forgets a reactor farther than its own search radius when the line is gone. The outlet read its reactor, on every tick and when it unloaded, without asking whether the reactor's chunk was loaded, which loaded that chunk. A pump, the ADS controller or the suppression pool whose rescan could not see part of its search cube (a chunk not loaded) cleared the binding it had; it now keeps a binding it cannot disprove, instead of losing it for up to thirty seconds. Machines that read another machine many chunks away (reactor to its pumps and nozzles, outlet to its valves, pumps to their reactor, pool and tank, the pipe route walk, panel inputs) used to renew a one-tick chunk ticket with every read and so kept a chunk at the edge of the loaded area from ever unloading; they read the chunk object if it exists and nothing else (`LoadedWorld.blockEntity`).
- New: `SyncLoadAudit` (every chunk our code loads synchronously during a GameTest run is printed), `-PbwrOnlyTick`, `-PbwrTickProbe`, `-PbwrJfr`. Nothing saved changes.

## Unreleased: control desks (1 m and 2 m)

- Added the owner's two small Fusion **control desks** as mountable panel bodies (CONTROL-PANELS.md, "Control desks"): **Control Panel (Desk, 1 m)** (`bwr:control_panel_desk`, one block) and **Control Panel (Desk, 2 m)** (`bwr:control_panel_desk_wide`, two blocks placed by one item, the second to the operator's left). The shape is the owner's (units are centimetres, one block is one metre): a level top 0.375 m deep, a riser and a narrow shelf behind it 0.175 m above the top, and a front that falls away under the top for the knee space; the desk stands against the back of its block. The owner's top is 0.40 m high, which is low next to the operator desks, so on the owner's instruction ("stretch to desk height") the part below the top is stretched vertically to put the top at **0.75 m**, the height of the operator desks; the top, the riser and the shelf keep the owner's sizes (shelf 0.925 m), and the sloped front still runs from the front edge of the top to the floor, a little steeper. The model keeps the owner's outline (the extra collinear points of the Fusion mesh are dropped: 10 faces instead of 420 triangles); `art/models/control_desk/build_desk.py` rebuilds it headless in Blender and checks it against both source files.
- New panel style `PanelType.DESK`: the mounting face is the top, a level plane (normal straight up) 16 columns by 6 rows (`PanelGeometry.DESK_ROWS`; the cells in front of the top take nothing, as on the chamfers), row 0 at the back, rows toward the operator. Desks join sideways into one face of any width (a 1 m, a 2 m and a 1 m desk are one run) and are a single row.
- Both desks take the five paint finishes like the other bodies (grey tinted textures with tint indices; the dark sloped front and the floor keep their colour), drop their item with the finish, and the wrench changes both halves of a 2 m desk together. Breaking either half of a 2 m desk removes it with one drop, as the duplex column does.
- New blocks only: nothing saved changes, old saves are unaffected. The mounting face test of `PanelTrimTests` reads the shipped `desk.obj` back like the other bodies (its grid is 16 x 6 cells, and the riser and shelf rise above the top but only behind it); exhaustive `PanelType` switches in the tests gained the `DESK` arm. The panel client check has desk stages (1 m and 2 m, seafoam and tan, a mixed run in light blue, a side view).

## Unreleased: premade control boards and the board blueprint

- Added the **board blueprint** (`bwr:board_blueprint`, CONTROL-PANELS.md section 2e): one item that places a whole premade control board in one click, with an outline ghost (green fits, red blocked) while it is held. Right-click the air to pick the next board; right-click a block to place it, the clicked spot being the front left corner of the board and the panels facing the player. Creative is free; survival takes the panel blocks, one item per component and the mimic tape from the inventory and lists what is missing; a blocked, out of world, unloaded, protected or over-limit site is refused and nothing changes. Boards are placed **unwired**.
- Boards are **data**: `data/<namespace>/panel_board/<name>.json` (panel frames, components with type, cell, explicit size and any wrench setting, reserved spaces for parts that do not exist yet, mimic tape), loaded with the data packs, synced to clients, validated as a whole (every problem is logged, a bad file is skipped).
- (Superseded by "the premade boards are blank shapes" above.) Five boards shipped, laid out at about twice the exact real size from `CR-PANELS-BWR-RESEARCH.md`: **rod control**, **RPV level, pressure and flow**, **RPS scram and mode switch**, the **ECCS bench (P601)** with the ADS / SRV section, and **neutron monitoring**. Space is reserved (and marked as a TODO in the template) for the manual/auto controller station (feedwater, recirculation), the joystick bypass switch and the LED rod pattern display.
- `PanelOps.placeDirectQuiet` (a component with a given label and no per-component sync) for bulk placement. New data component `bwr:board_template`. Old saves are unaffected.

## Unreleased: control hardware (controller station, synchroscope, two-pen recorder, joystick switch, LED rod display)

- Five new panel components the research reports found missing (CONTROL-PANELS.md, section 6): a **manual/auto controller station** (`bwr:controller_station`: PV and setpoint pointers on one scale, demand bar, AUTO/MAN, RAISE/LOWER; outputs demand, mode, raise, lower, setpoint; no control law, nothing moves by itself), a **synchroscope** (phase and slip inputs, two synchronising lamps), a **two-pen strip recorder** (the strip chart with two coloured pens, each on its own scale), a **joystick bypass switch** (centre, up, down, left, right, optional spring return) and an **LED rod pattern display** (amber and red LED per rod from the full core display snapshot).
- New items start at **twice their real size**; the exact real size stays selectable. Old saves are unaffected: the types are new, `StripChartData` is only no longer final, and `ComponentType.onPlaced` is a no-op for every type but the station.

## Unreleased: gauge polish and twice the real size

- **Real size defaults are twice as big** in each dimension for every type that has real sizes: dial 4x4, edgewise 2x6 / 6x2, bar gauge 2x6, bar graph 2x6, key switch and rotary selector 2x4, lamp, pushbutton, toggle, push-to-turn switch, setpoint knob and annunciator window 2x2, RPS trip 4x2, segment display 8x4 and thumbwheel 6x4 (digits 2 cells wide, 4 tall). The **control switch** is the exception: a new one is 8x12 (the owner found it exactly the right size next to the 12x16). A bare, creative or crafted stack places that; the exact real sizes stay on the lists (cycle or Alt+click) and nothing placed or stored moves (the new sizes are appended).
- The **bar gauge has a real size** (1x3, and 2x6 as the default); its layout tests are relative to the case. Its numbers and units are 12 and 19 % bigger, measured against the owner's reference picture.
- The **classic dial's labels** (minimum, maximum, units) stay inside the face at every size and Text size; the OFF flag moved above the hub.
- **Captions** under a real size part scale with it (`GaugeScale.captionFactor`); at the design size and above they are unchanged.

## Unreleased: paint finishes for panels, trim and furniture

- Every **panel body** (vertical, benchboard, console, back panel, duplex), every **trim block** (end caps, header strip, canopy and vent, plinths, corners) and every piece of **room furniture** (cabinets, desks, mimic bands, wall displays, control stand) has a paint **finish**: **seafoam** (the look they always had, and the default), **cream**, **tan**, **grey** and **light blue** (the owner's simulator). It is a blockstate property `finish`, so a saved block that has none, which is every block placed before, loads as seafoam and looks exactly as it did (CONTROL-PANELS.md, "Finishes").
- **Sneak-right-click with the panel wrench** on a bare spot of a panel (nothing mounted under the crosshair) or on any trim or furniture block steps the finish: seafoam, cream, tan, grey, light blue, seafoam. A multi-block piece (a duplex column, a cabinet, the supervisor desk, a wall display) changes as one. Sneak-click on a mounted component still removes it, and the click on a mimic path, the config screen and the old gestures are unchanged. Neighbouring panels may have different finishes: grouping and mounting never look at the finish, and mounted components are untouched.
- A broken block drops its **item with the finish** (the vanilla `block_state` component; a seafoam drop has none, so it still stacks with a crafted one), the item places the same finish on every block of the piece, pick block gives the finished item, and item icons are tinted.
- The models carry **grey paint textures with a tint index**, coloured per finish by a block and an item colour handler, so one texture serves all five. Only painted surfaces take the finish (faces, bezels, paint, the back, the end cap cheek and the plinth doors, the desk tops); dark trim, kick plinths, hoods, screens, louvres and handles keep their colour. The annunciator strip and the vent grille are pictures (a box of windows, a grille) and keep their seafoam border in every finish. The thin housing of mimic bands and wall displays takes a darker shade of the finish (it is the same dark grey in seafoam).

## Unreleased: components on the angled trim (header strips, canopies, chamfer corners)

- The **annunciator header strip, the canopy and the two 45 degree chamfer corners** (vertical and benchboard) now take components like any panel (CONTROL-PANELS.md, "Mounting on the angled trim"). They are panel blocks with the panel block entity under the same ids, models, recipes, drops and states, so a world saved before loads: a block that had no block entity gets one when its chunk loads (and, failing that, the first time anything asks for it), and breaking one drops what is mounted on it.
- Three new panel styles: `HEADER_TILT` (the face as the model draws it, leaning back 5 degrees; strips and canopies join sideways into one row, never upward), `CHAMFER_VERTICAL` (the diagonal facet, 11 of 16 columns; stacks into rows, never joins sideways) and `CHAMFER_BENCH` (the triangular facet; a group of one). A grid cell is still 1/16 block along the face, so parts keep their real size; the cells that are not on a facet (and a canopy's hood row) take nothing.
- Existing exhaustive `PanelType` switches in the tests gained the new arms; the registration test of `PanelTrimTests` now expects the four pieces to be panel blocks with a panel block entity (the other trim pieces are unchanged and still have none).

## Unreleased: annunciator boxes, the control module, distance fade of text

- Annunciator windows that touch now draw as **one grouped box** (a thin dark gap between them, one shared dark recessed frame with a bezel lip round the group, found on each client from the windows of the panel group; CONTROL-PANELS.md, "Annunciator boxes"). Lit colour and flash are as before; the lettering follows the window's colour scheme. The legend is up to **four lines** (Engraved line 3 and 4, saved only when used, so old saves load unchanged).
- Added the **Annunciator Control Module** (ACK, RESET, SILENCE and TEST pushbuttons in one part, real size 2x1 cells): momentary outputs `ack`, `reset`, `silence` and `test`; ACK and RESET do what a pushbutton with that role does for the module's group, TEST is a lamp test (lights the group's windows while held, changes no alarm state). It acts only when pressed.
- **Distance fade of text**: printed text whose glyphs are only a few screen pixels tall fades out and a soft strip in the text's colour stands in for it, instead of turning to speckle. Computed per component per frame, no per-glyph work; client option `panelTextFade` (default on, `bwr-visuals.toml`) turns it off.

## Unreleased: gauge text and ticks in proportion, Text size and Tick size

- Gauge **text and ticks scale with the gauge** (CONTROL-PANELS.md, "Gauge text and tick size"): the numbers of a small bar gauge are no longer held at a least height of 0.15 cell, the classic dial's least tick and pointer widths and the classic edgewise meter's margins, ticks, pointer and flag shrink with a meter smaller than its art, and a 1-cell bar graph's housing is the big bars' share. The big sizes look as before.
- The dial, edgewise and bar gauges have **Text size** and **Tick size** sliders (50 % to 200 %, default 100 %) in the wrench screen. Saved only when not 100 %, so saves are unchanged; the server clamps them.

## Unreleased: real size control panel components

- The **instruments and controls start at their real size** (CONTROL-PANELS.md, "Real sizes"): dial gauge 2x2 cells (12.5 cm), edgewise meters 1x3 / 3x1, control switch 1x2, key switch 1x2, rotary selector 1x2, setpoint knob 1x1, toggle switch 1x1, thumbwheel 1x2 per digit, lamp 1x1, pushbutton 1x1, RPS trip assembly 2x1, push-to-turn switch 1x1, annunciator window 1x1, segment displays 1x2 per digit and bar graphs 1 cell thick. Before, every one of them was five to ten times its real size, so a 4 m board held a handful of parts.
- The real sizes are **appended** to each footprint list, so stored item sizes and placed components are unchanged; a bare item (no stored size) now places the real size. The big sizes are reached by cycling (right-click air or sneak-click a panel), which now goes from the smallest to the biggest, or with the new **size picker**: Alt+click with a component item in hand opens a scrollable list of every size with its cells and real size in centimetres, an outline and a marker on the current one (new packet `panel_set_size`, validated like the cycling). The network protocol version is now 22.
- Small placeholders scale their elevation, bezels, corner flags and segments with the component, so 1-cell parts are not posts; segment displays and thumbwheels get one digit per cell at the real size and bar graphs two segments per cell.
- A block may own up to 256 components by default (was 64).
- The **bar gauge** has no real size yet: its tests assume a case at least 11 cells tall (see CONTROL-PANELS.md).

## Unreleased: control room trim blocks and remodelled panel bodies

- The three panel bodies (vertical, benchboard, console) are remodelled in Blender with the **mounting face exactly where it was** (a game test reads the shipped OBJ files back and checks the face polygon's four corners against `PanelGeometry`, and that nothing else is in front of that plane). Detail: seafoam face and bezel textures with module seams, service-door backs, bevelled back edges on the vertical board, a dark recessed 2 px toe-kick on the back of the benchboard and console, a front lip on the console. Ids, states and group joining are unchanged, so placed boards just look better.
- Added 15 decorative trim blocks, none with a block entity: **end caps** (flat, benchboard, console, rounded; they take the side of the run they are placed against), **annunciator header strips** (a half-height block tilted forward 12 degrees with a grid of window tiles; a canopy variant with a projecting hood), **plinths** (base cabinet with a toe-kick, foot apron, toe-kick strip) and **corner fillers** (vertical and benchboard; inside, outside and 45 degree).

## Unreleased: Networking Wrench, machine names, gauge Measures and FE power units

- Added the **Networking Wrench**, a second wiring tool beside the unchanged Panel Linker (either stores the device in the same slot, so a bind is the same bind). Right-click selects a device, sneak-right-click opens its **Networking screen** (name, caption template, every signal with its live value, unit, quantity and readers, every command, the linked components with Unlink, and *Name linked components*). While it is held, a device under the crosshair is outlined white, the selected one yellow, and hovering a bound component or a device draws see-through lines (read cyan, command orange, at most 64). Nearby players see the same lines, fainter and tinted with your name colour, within `networkLineShareDistance` (server, default 32) and `networkLineViewDistance` (client, default 32).
- Added **machine names** (equipment tags, up to 24 characters; saved only when set), the `name` TEXT signal on every device, component **captions** (printed under a component), and the two buttons that fill captions from a name: *Name linked components* and *Use machine name*. They touch line links only (never CC port bindings), respect the panel edit permission and never fire a sink.
- Added gauge **Measures** (default ANY): a gauge set to LEVEL lists and accepts only level signals; `bind` refuses a mismatch with the reason. Old mismatched bindings keep working and show a warning. Each signal now has a quantity worked out from its unit (SIGNALS.md). `generated_fe_tick`, `delivered_fe_tick` and `buffer_delta_fe_tick` now read `FE/t` (they said `FE`).
- Added gauge **Power units** (Raw, W, kW, MW; kJ and MJ for stored energy) through the existing binding scale, `feToJoules` (default 2.5), and *Fit range*.
- Added **CC port bindings** (CC:Tweaked only): a machine gets a port 0000 to 9999 set by hand (`setPort(n)` or its Networking screen), and a panel input can read a signal of the machine on a port of its CC wired network. Two machines on one port read no signal. Every machine peripheral also has `getName`, `setName`, `getPort` and `setPort`.
- The network protocol version is now 21.
- The **bar gauge** is curved like the owner's CAD part: a real case 5 by 20 cm (kept to those proportions and centred in its size) whose white face bulges 3 cm along an arc between raised side rails, grey top and bottom caps (a dark dot on the bottom one) and the tag plate above. The ticks, colour bands, numbers and units are printed on the curved face and lean with it; the pointer rides the arc in its black channel, still reading exactly against the ticks. Band colours offer **Nominal** (white), **Caution** (yellow) and **Danger** (red) beside the colour code. Saves are unchanged.
- Added **control-room furniture** (ROOM-FURNITURE.md), eleven decorative blocks with no block entities and nothing mounted on them: the **Relay Cabinet** and **Relay Cabinet (Plain Back)** (two blocks tall, placed and broken like a door; double doors, seam, handles, louvres, an indicator window, a service door on the back of the standard one, a flat back on the other so rows stand back to back), the **Operator Desk**, **Round End** and **L Corner** desks (0.75 m with a free knee and kick space; a bare hand cycles an optional flat monitor or CRT on top, a sneaking bare hand mirrors the round end), the **Supervisor Desk** (two blocks wide), the **Mimic Band** in cream and black (a flat 3/16 deep board with a flow diagram), the **Wall Display** (2 x 2) and **Wall Display (Wide)** (3 x 2) with a dark screen and a plant overview picture, and a small **Control Stand** (a pedestal with a tilted lamp and button top). Multi-block pieces go down whole or not at all, come up whole and drop one item. The mimic tape does not draw on the bands. Models are Blender OBJ in the seafoam panel palette with dark grey trim; saves are unchanged.

## Unreleased: machine screens redesigned

- All fourteen **machine screens** (reactor panel, refuelling floor, pump panel, Terry turbine, legacy recirculation pump, condenser, condensate tank, cooling, power module, valves, fuel fabricator, RHR heat exchanger, suppression tank, service panels) are rebuilt on one design system (`gui/client/style`, audit in GUI-AUDIT.md). Every function, button, packet, validation and inventory slot position is as before; the displayed numbers are the same numbers.
- **One look**: a dark control-room palette with every text and background pair at 4.5:1 or better, a 4 px spacing scale, one type hierarchy, label-value rows with units in their own column, cards, bar meters, status icons, tabs (in the title bar), primary / secondary / danger buttons with hover, pressed, focus and disabled states, and one text field and slider style. The panels are drawn, not blitted, so the five GUI background textures are gone.
- **Colour is never alone**: a caution, alarm, info, normal or off value carries its own shape (triangle, diamond, square, circle, ring) and a word in the tooltip, and the one colour that used to mean "water" no longer means anything but status. The burnup map runs light teal to dark red-orange instead of green to red, and the maps have a colour scale.
- **Fits every window**: panels are at most 352 x 224 and never wider than the window; every screen fits 320 x 240, the smallest GUI size the game gives a screen (before, six screens did not, and the Terry turbine and suppression tank ran off a 1080p window at GUI scale 4). A body that still does not fit scrolls. Text is cut with an ellipsis and a tooltip with the whole text, never left to run over.
- **Keyboard and screen reader**: Tab and Shift-Tab visit every control in layout order, Enter and Space activate, Escape closes (also from a text field, where letters no longer reach the inventory key), Left and Right switch tabs, the sliders take the arrow keys (1 %, Shift 10 %), the core map takes the arrow keys, Enter or Space, plus, minus and Home. Every control has a tooltip, shown on keyboard focus too, and says why it is disabled (LOAD, UNLOAD, MARK, SWAP, every control a computer holds). A screen reader hears the readouts when a screen opens.
- Fixes found by the audit: the refuelling statistics and the fuel fabricator rows no longer overprint, the SCRAM indicator is no longer drawn over the FLUX tab, the legacy recirculation pump's bar no longer runs through its text, the fabricator's title is visible again, and the active tab no longer looks disabled. The reactor panel's hover-only fuel and damage details are rows on the INFO tab.

## 0.1.0-alpha.32 (unreleased) — Full core display, rod select matrix and SRV switches

- Added reactor rod selection: signal `selected_rod`, commands `select_rod`, `selected_rod_insert` / `_withdraw` (one notch) and `selected_rod_insert_continuous` / `_withdraw_continuous` (held), and CC `selectRod`, `getSelectedRod`, `stepSelectedRodIn` / `Out`, `setSelectedRodInsertHeld` / `WithdrawHeld`. They move only the selected rod through the existing drive network, honouring CRD power and water. The selection is saved; old saves load with none.
- Added per-rod indications `rod_<n>_drift`, `_scram`, `_full_in`, `_full_out`, `_accum_trouble`, `_lattice_x`, `_lattice_y`, `_label` (GE `XX-YY` coordinates) and per LPRM string and level `lprm_<s>_<a-d>_percent`, `_bypassed`, `_failed`, `_downscale`, `_upscale`. The core has no rod drift or low-charge notion, so those two are documented defaults; see SIGNALS.md.
- Added the **full core display** (`columbia` lamp matrix and `browns_ferry` digital style) and the **rod select matrix** panel components. One binding to the reactor controller, no port per rod; one compact patch only when something changed. They span up to 8 x 8 blocks; a drawing that does not fit is refused with the size needed.
- The panel GPU cache holds static legends in text vertex buffers; a 185-rod display plus a matrix costs about 0.05 to 0.09 ms a frame (was 1.4 to 1.5).
- Style table: oiltight 30.5 mm selector (black knob, chrome wing lever), smooth and ribbed dome lights and the guarded yellow pushbutton, when their models ship.
- No rod blocks, sequence control or worth minimiser: protection logic stays the player's.
- Added the owner's CAD hardware as panel items. **Bar gauge** (`bar_gauge`): a vertical edgewise indicator laid out like the owner's simulator (numbers on the left, ticks and up to four colour bands against a narrow black channel, a grey pointer in the channel pointing at the scale, the units down the face right of it one letter per line, the label on a tag plate above); a Preset field fills in the narrow range (0 to 60 in) or wide range (-150 to +60 in) RPV level scale. **RPS trip assembly** (`rps_trip`): two guarded red buttons, outputs `a` and `b`, momentary; the click position picks the button. **Push-to-turn switch** (`push_turn_switch`): hold right-click to push the knob in, scroll while it is in to turn it between AUTO and ARM; outputs `position` and `pushed`, optional spring return.
- The baked GE edgewise and dial meters now draw their colour bands. Gauge units may be 12 characters.
- ADS: new bindable inputs `ads_allow`, `ads_activate_a` and `ads_activate_b` on the ADS controller (both activate inputs held together open the bank once; `ads_allow` cleared keeps it shut) and a per-valve `ads_allow` on relief valves (CLOSE on a valve switch: the ADS may not hold it open). Defaults change nothing; old saves load unchanged. See SIGNALS.md.
- The imported CAD models had their faces turned inside out (lit from behind); fixed and re-exported, including the Modern button, SRV switches and WL-2.
- The SRV switches and the RPS trip assembly are rebuilt from the owner's CAD dimensions: they had come out upside down and the decimated cylinders had collapsed. The SRV switch now has its legend tab above the knob (Close | Open, Close | Auto | Open) and a straight bar handle on a white disc; the RPS assembly has its tall cup guards back, rippled buttons, and the legends on the raised label pads.

## 0.1.0-alpha.31 — 2026-10-11 — Buildable containment

- Added surveyed containment rooms built from full solid blocks, with volume, leak position, layer-based weakest-spot pressure rating and reactor/pool linking.
- Added steam, water, power, data and general penetrations. Powered isolation valves close on lost actuator power; connected open process lines can bypass the containment atmosphere.
- Added 3×3 and 4×4 Blender-modeled vault doors and a two-leaf personnel airlock. Redstone pulses cycle the airlock interlock; only CC override forces both leaves open, creating a medium leak even after releasing override.
- Added a powered nitrogen supply with consumable canisters and a powered wetwell vent valve for concrete basins and enclosed suppression tanks. Vent discharge needs exterior air or a straight stack.
- Replaced the containment controller's status-only CC face with room, opening, isolation, bypass and atmosphere methods. The in-world status readout shows openings, weakest spot, hydrogen, oxygen and pressure.
- Fixed persistent-world GameTest fixture readiness for tank casing drops; added airlock reload, redstone and equipment regressions. The controller GUI is deferred to a later visual overhaul.
- See [CONTAINMENT-UPDATE.md](CONTAINMENT-UPDATE.md) for a worked build and limitations. The corium and severe-accident model remains lumped, not a fluid-dynamics simulation.

## 0.1.0-alpha.30 — 2026-10-09 — Natural circulation, core melt and containment hydrogen

- Added natural circulation: with pumps tripped or absent, core flow follows the cube root of thermal power and needs water above the shroud head. A two-pump trip from rated, with pressure and level held, settles near 23% flow and 26% power.
- Added cladding failure: rods burst between 750 and 1,200 °C, release the gap inventory and leave the coolant radioactive. This damage is permanent.
- Added core melt: above 2,200 °C excess heat melts fuel as latent heat, and molten fuel relocates to the lower plenum with its decay heat.
- Added lower plenum debris behavior: water quenches debris and saves the head, while dry debris creeps the lower head to failure (about 35 min at pressure and 80 min depressurised for 30% of the core).
- Lower head failure drains the vessel from the bottom and moves the debris onto the drywell floor.
- Added core-concrete interaction: corium ablates concrete unless water reaching the floor removes its heat. It produces gas and hydrogen, and penetrating the 3 m basemat fails containment.
- Added a containment atmosphere: hydrogen burns at 8% with oxygen present and detonates when oxygen reaches an already-rich mixture. Burns consume oxygen that a sealed containment does not regain.
- Added containment controls: nitrogen inerting and de-inerting purges, a pool-scrubbed wetwell vent, overpressure failure, and air ingress through a failed containment.
- Added fission product releases to containment and the environment. Contamination decays with a one-day half-life, and nearby players receive escalating status effects.
- Hydrogen burns cause explosions that damage entities but not blocks; other milestones produce chat notices and sounds. Events never replay after a reload.
- Added the CC:Tweaked methods getNaturalCirculationFlow, getCoreDamageStage, getCoreDamage, getContainment, setContainmentInerting and setContainmentVent, plus new getStatus fields, and expanded the controller INFO readout.
- Damage and containment state persist under a new SevereAccident save key. Old saves load undamaged with an air-filled containment. Menu network protocol stays at 12.
- Added 30 physics acceptance tests covering the new models. A recirculation-line break or failed lower head still removes all core flow, because natural circulation cannot run through a short-circuited loop.

## 0.1.0-alpha.29 — 2026-09-27 — Refuelling map controls

- Added cursor-centered scroll-wheel zoom, right-drag panning, and a FIT button to the refuelling map.
- Clipped zoomed cells to the map panel and kept hit testing aligned while zooming and panning.
- Added click-to-toggle multiple selection, visible selected outlines/count, and CLEAR.
- LOAD and UNLOAD now process selected positions through one bounded server request and one map refresh.
- Preserved held-fuel priority, click order, fuel exposure, specialty inserts, inventory exhaustion, and overflow item drops.
- Fixed Creative-mode unloading silently discarding rods when the player's inventory was full; overflow now drops beside the player.
- Kept MARK/SWAP as an explicit single-pair operation and retained the head-off refuelling requirement.
- Added real client input/network and inventory-conservation checks. Menu network protocol is 12; update client and server together.

## 0.1.0-alpha.28 — 2026-09-27 — Circular reactor internals

- Capped modeled active fuel at nine blocks, with smaller footprints approaching 3.5 blocks; vessel height no longer stretches the fuel channels.
- Accepted complete jet-pump assemblies at all fully enclosed mounting heights in the inner perimeter band, including raised construction locations.
- Placed formed jet pumps between the core shroud and vessel wall using the existing uniformly scaled Blender fittings.
- Rendered bound hydraulic control drives beneath their circular core/blade positions and concealed the rectangular construction grid, including spare drives.
- Preserved saved CRD blocks, fuel, control mapping and bottom-face water/power connections. Holding a CRD item exposes original construction selection shapes for maintenance.
- Kept water, Cherenkov visuals and spray headers aligned with the shortened core; accepted existing spray rings at their old build heights.
- Invalidated the jet survey on construction changes instead of expiring it every second; retained cached GPU meshes.
- Added a single-size 17×17×28 integrated client/server regression fixture and a Blender assembly driven by its exported runtime coordinates.

## 0.1.0-alpha.27 — 2026-09-27 — Spray-ring placement and rotation

- Corrected the construction model's quarter-turn offset so new spray branches face the player.
- Stopped straight neighboring segments from overwriting the chosen nozzle direction; automatic 90-degree corners remain available.
- Added Mekanism Configurator Rotate-mode support: right-click for a clockwise quarter turn of straight or corner segments.
- Saved manual orientation so neighbor updates and world reloads do not undo tool rotations.
- Fixed held-item interaction so extending a ring does not accidentally toggle LPCS/HPCS; empty-hand right-click still switches loops.
- Added in-game placement/control tooltips and integration checks using the actual Mekanism Configurator.

## 0.1.0-alpha.26 — 2026-09-27 — Port-height LOCA and white steam

- Changed reactor accident plumes from red to white, retaining the existing particle budget.
- Removing a previously connected vessel pipe now creates a persistent leak; never-connected construction ports remain buildable.
- Added pressure- and elevation-dependent water discharge, with larger lower recirculation inlet breaks and smaller upper outlet breaks.
- Direct liquid drainage stops at each broken nozzle's height; exposed nozzles vent steam and boiling/flashing can continue lowering inventory.
- Added a fixed-volume, two-phase rupture solve coupling mass, outlet enthalpy, expanding steam space and depressurization.
- Feedwater and steam-line breaks vent exposed vessel nozzles; multiple openings and their locations survive reload.
- Pipe failures become possible above 1,250 psig after accumulated wear; vessel-head failures retain the separate 1,375-psig gate.
- Added physics and Minecraft regression coverage for port elevation, flow scaling, conservation, pipe removal and save compatibility.


## 0.1.0-alpha.25 — 2026-09-27 — Jet-pump proportions

- Rebuilt the installed jet-pump appearance in Blender as separate diffuser, straight-pipe, inlet and support parts.
- Preserved round pipe sections and uniformly scaled bends, suction bells, diffusers, braces and fasteners.
- Limited straight-section length adjustment and total pump aspect ratio, preventing tall vessels from stretching the pumps into thin columns.
- Improved fitting to the curved shroud/vessel gap while retaining spacing between adjacent assemblies.
- Preserved existing construction placement, saved pump blocks, pairing and hydraulic capacity; retained cached GPU geometry.
- Expanded the client checks to validate actual part bounds, materials, aspect ratios and a close view inside the downcomer.

## 0.1.0-alpha.24 — 2026-09-27 — Installed jet pumps

- Formed reactors now display their installed jet pumps down alongside the outside of the core shroud, inside the vessel wall.
- Retained the tall construction models and existing lower mounting rows for building; unforming restores those models and their selection/collision shapes.
- Removed invisible construction-column obstacles while formed without moving or deleting saved pump blocks.
- Fit pump models to vessel size and installed count, including smaller and rectangular vessels; kept paired-flow rules and the ten-jet-per-recirculation-pump rating unchanged.
- Reused the existing Blender-authored pump mesh, checked the shroud fit in Blender, and cached installed geometry on the GPU.
- Added formation, disassembly, reload, legacy footprint, missing-pump, flow and client geometry checks.

## 0.1.0-alpha.23 — 2026-09-27 — Open reactor access

- Removed physical collision and crosshair obstruction from the raised/failed vessel head; closing the head restores collision. Preserved structure blocks and saved state.
- Added water- and power-dependent blue Cherenkov-style light around submerged fuel, including decay-power glow after shutdown.
- Added original Blender 90-degree sparger elbows that automatically turn at neighboring corners. New segments inherit the adjacent LPCS/HPCS setting.
- Kept vessel/core meshes cached; no new machine ticker, particle cloud, or world-light updates.
- Added open/closed/reloaded head collision, real entity passage, corner orientation and glow-condition checks.

## 0.1.0-alpha.22 — 2026-09-27 — Steam paths and accident visuals

- Fixed suppression wall-inlet routing after pipe changes. A separate quencher and submerged inlet block are no longer required. Retired quencher crafting/creative entry while keeping old-save compatibility.
- Added a water drain outlet with manual, redstone and CC control. It drains outside or into a connected water line, stops against obstructions, and can empty below the separate RHR suction floor.
- Made RPV steam nozzles permanently open; downstream MSIVs retain powered redstone/computer isolation. Old closed nozzle saves migrate automatically.
- Waterlogged high-pressure water and steam pipes now survive underwater placement.
- Shared pump suction across multiple water sources and added the connected-source count to the GUI; delivery remains limited by pump rating.
- Connected existing pressure damage above 1,375 psig to actual service-pipe breaks and persistent failure effects. Vessel-side feedwater rupture now loses coolant.
- Added an original Blender damaged-head model, bounded red-tinted accident steam and a loud one-shot vanilla explosion sound pending custom audio.
- Preserved the existing temperature/steam-dependent oxidation and hydrogen model and documented its connection to loss of cooling.
- Added drainage, RHR, intake, underwater pipe and failure/reload regressions; expanded the client visual check. GUI protocol is 11: update client and server together.

## 0.1.0-alpha.21 — 2026-09-27 — Reactor Visual Update

- Replaced formed-vessel controller cubes with a shallow Blender instrument panel.
- Integrated round Blender steam, feedwater and recirculation nozzles while keeping existing pipe and computer connections.
- Added translucent water when the head is off, following physical water level and smoothing between server updates.
- Kept static vessel/core meshes cached as water moves. Unformed vessels restore their construction blocks.
- Named the 2.70% intermediate and 3.50% high BWR enrichment grades; retired 1.20%/1.40% from new selections while preserving saved fuel histories.

## 0.1.0-alpha.20 — 2026-09-27

- Filled usable gaps around the RIP core perimeter with actual loadable fuel slots.
- 17 × 17 RIP cores increase from 444 to **476 assemblies**, with **109 control
  blades** instead of 101. 23 × 23 cores increase to **1,084 / 253**.
- Added controls to partial peripheral fuel groups where the blade and guide fit.
  Complete four-assembly groups retain a valid physical drive.
- Shared the packing dimensions between simulation, Minecraft and Blender; kept
  the ABWR shroud size and pump mounts in place. Preserved every old fuel slot.
- Existing 17 × 17 RIP plants need eight additional CRDs; 23 × 23 plants need
  twelve. Surviving rod state is remapped by position, so refresh cached CC rod IDs.
- Added packing checks across all 289 footprints and an alpha.19 save-upgrade
  regression covering fuel, controls, missing new drives and save/reload.

## 0.1.0-alpha.19 — 2026-09-26

- Corrected the visible RIP annulus using GE ABWR DCD Table 5.3-2: 7,112 mm
  vessel ID, 5,600.7 mm shroud OD and 57.2 mm shroud wall.
- The 17 × 17 shroud grows from 9.68 to 12.00 blocks across; the radial gap
  decreases from 2.78 to 1.62 blocks. The same proportions scale to every size.
- Rebuilt the RIP shroud and rim in Blender and expanded the displayed fuel,
  supports, guides and control blades together. The core remains continuous.
- Separated visual dimensions from Minecraft construction-clearance masks.
  Saved fuel capacities, drive identities, pump mounts and circulation are unchanged.
- Added a top-down Minecraft review and checks of actual baked shroud dimensions,
  fuel fit and reuse of uploaded GPU geometry.

## 0.1.0-alpha.18 — 2026-09-26

- Enlarged the continuous RIP core and shroud by moving the tightest pump sites
  slightly outward while maintaining the existing clearances.
- A 17 × 17 RIP reactor now holds 444 assemblies and 101 control drives, up from
  408 and 97. Its twelve pump sites remain; only four diagonal sites move.
- A 23 × 23 RIP core now holds 1,036 assemblies, up from 964. Ordinary core
  capacities, pump counts, equipment dimensions and flow ratings are unchanged.
- Updated Blender layout reviews, mounting coordinates and upgrade guidance.

## 0.1.0-alpha.17 — 2026-09-26

- Replaced the holes above internal pumps with one continuous, smaller core.
- Reduced the core shroud and fuel/drive layout together, reserving a peripheral
  pump ring with clearance from the shroud and vessel wall.
- Additional RIPs now add flow without cutting more holes or reducing core size
  again. Ordinary reactors retain their existing capacities.
- Added diameter/area ratios to the CC mounting query and updated placement
  guides with the new ring coordinates and upgrade instructions.
- Reviewed matching Blender scenes and Minecraft cores at minimum, reference,
  maximum and rectangular sizes. Preserved fuel-conversion checks, surviving
  control-drive state and reusable GPU geometry.

## 0.1.0-alpha.16 — 2026-09-26

- Made reactor internal pumps form through a scalable bottom-head mounting grid,
  with 4, 12 and 16 sites at 7-, 17- and 23-block-wide vessels.
- Added held-item mount outlines and the CC:Tweaked `getInternalPumpMounts()` query.
- Installed RIPs reserve fuel/drive positions, require FE and contribute real
  core flow. Each powered full-speed RIP supplies three jet-equivalent units.
- Protected loaded fuel during conversion; preserved surviving rod positions,
  accumulator charge and pending commands through mounting changes and reloads.
- Added a Blender hydraulic control rod drive model referenced to Columbia FSAR
  Figures 4.6-2/4.6-3, preserving shared water/FE and computer connections.
- Updated pump status, reactor INFO, placement guides and model references.

## 0.1.0-alpha.15 — 2026-09-26

- Added Blender-authored scalable core internals based on Columbia FSAR diagrams:
  fuel channels, lifting handles, support cells, top guides, shroud and cruciform blades.
- Visible fuel and specialty inserts follow the actual saved loading; empty slots
  remain empty. Compact and legacy capacities/control IDs are preserved.
- Added continuous inner/outer vessel-head surfaces, sealed poles and overlapping
  shell joints to eliminate open surfaces and seam cracks.
- Core meshes reuse GPU buffers; closed vessels skip the interior from outside.
  Moving blades reuse one mesh. Refuelling does not rebuild the vessel shell.
- Added a Columbia FSAR reference index and core characteristics notes.

## 0.1.0-alpha.14 — 2026-09-26

- Renamed ADS Relief Valve to **Pressure Relief Valve** and ADS Division Controller
  to **Division**. Existing item IDs, recipes, placed machines and CC peripheral
  names remain compatible.
- New division hardware defaults to 1. Local selectors cycle through **1–4**;
  saved division 0 and existing computer commands remain supported.
- Replaced core-sparger cubes with Blender-authored header/nozzle segments.
  Formed reactors display circular or elliptical rings scaled to the vessel,
  with fixed-diameter pipes, circuit collars, nozzle tips and mounting brackets.
- Missing segments leave visible gaps and preserve proportional spray capacity.
  Rings reuse cached reactor geometry and revert to individual models on unforming.
- Added a Blender-rendered sparger inventory icon to keep creative-menu rendering light.
- Updated hardware and construction guides; GUI protocol remains 10.

## 0.1.0-alpha.13 — 2026-09-25

- Cached stationary reactor, cooling-machine, condenser and condensate-tank
  geometry in reusable GPU buffers. Normal frames update placement and lighting
  instead of rebuilding and uploading every model face on the CPU.
- Kept mechanical-tower fans separate from the body; all fan instances reuse
  one mesh while their rotation follows the existing animation.
- Cached suppression-pool component meshes while preserving moving water levels.
- Used full-machine bounds for large-object visibility and distance checks,
  including views where the controller's chunk section is outside the camera.
  NeoForge still rejects machines whose complete bounds are off-screen.
- Added cache cleanup on resource reload, world unload and shutdown, plus bounded
  reuse of assembly variants. Preserved the ordinary vertex path for special
  destruction/outline consumers and retained Blender material colors.
- Added a disposable client benchmark and checks for stable uploads, off-screen
  rejection, tower-top visibility, day/night lighting and resource reloads.
- Client rendering update; existing saves, simulation and GUI protocol 10 remain compatible.

## 0.1.0-alpha.12 — 2026-09-25

- Added smoothly varying random wind and gusts to both cooling towers' vapor.
- Wind changes with height and reaches different sections of the plume at
  different times, producing bends and winding shapes as existing vapor drifts.
- Calm periods favor upright plumes; stronger gusts carry vapor sideways and
  reduce its rise. Nearby towers share coherent wind, with gentle local mixing.
- Retained the continuous white emission, gradual fade, reduced/minimal particle
  settings and shared 1,200-particle cap. Motion is client-only; GUI protocol stays 10.

## 0.1.0-alpha.11 — 2026-09-25

- Rebuilt RCIC and HPCI in Blender as separate Terry-inspired turbine/pump skids,
  with bolted casings, governor linkage, oil systems, gauges, coupling guards
  and visible CC:Tweaked panels. HPCI adds an insulated casing and service platform.
- Added four round, grid-aligned flanges: steam admission, steam exhaust, water
  suction and water discharge. New footprints are 5 × 4 × 5 and 7 × 5 × 5 blocks
  respectively (width × height × depth). Suction accepts ordinary water through
  Mekanism-compatible fluid connections; discharge uses BWR high-pressure water pipes.
- Added dedicated RCIC/HPCI GUIs with manual speed/start/stop, control ownership,
  water-source selection, live steam/water measurements and port coordinates/status.
- Preserved existing block IDs, old installed models/ports, saved controls and
  finite steam/water accounting. Breaking and replacing an old assembly uses the new layout.
- Updated the fast creative-inventory icons using Blender renders. Assembly
  integrity checks now run once per controller instead of once per child cell.
- Extended rotation, obstruction, teardown/drop, old-save, fluid/CC capability,
  control ownership, snapshot, model and complete pumping-loop checks.
- GUI protocol is now 10. Install alpha.11 on both clients and servers.

## 0.1.0-alpha.10 — 2026-09-25

- Reworked both cooling-tower plumes into continuous overlapping vapor layers instead of intermittent puffs.
- Made vapor whiter and denser with larger Blender-rendered sprites, a fuller base and slower dispersal aloft.
- Fixed overlapping vapor layers cutting each other into visible circular slices.
- Retained wind drift, rising motion, eventual fade, particle settings and the shared 1,200-particle limit. GUI protocol remains 9.

## 0.1.0-alpha.9 — 2026-09-25

- Reversed the mechanical cooling-tower fan animation following in-game feedback.
- Increased full-speed fan animation from 30 to 40 RPM (33% faster), retaining powered-speed scaling and smooth interpolation.
- Includes the alpha.8 suppression-tank and tower-vapor changes. GUI protocol remains 9.

## 0.1.0-alpha.8 — 2026-09-25

- Reversed the mechanical cooling-tower fans and made their rotation continuous as speed changes.
- Added tall, wind-drifting vapor plumes to operating natural and mechanical towers. Puffs rise, spread and fade; the effect is client-only with a shared particle cap.
- Added enclosed concrete suppression tanks and a Blender-modeled steam inlet flange. Tanks form automatically, start empty and retain inventory through repair and controller replacement.
- Steam from BWR pipes or optional Mekanism tubes heats the stored water and adds condensate. Shared inlet limits and valve routing prevent duplicate allocation.
- Added steam-flow and inlet-buffer measurements to the tank GUI and CC:Tweaked status. Water suction/return, spray fill and the separate RHR exchanger circuit remain available.
- Fixed relief discharge recognition after pumped tank filling and pipe disconnection. Verified ocean intake → makeup pump → exchanger → discharge cooling with separate water inventories.
- GUI protocol is now 9. Update clients and servers together.

## 0.1.0-alpha.7 — 2026-09-24

- Increased completed tritium target yield to **12,500 buckets (12,500,000 mB)**:
  1,250 samples per rod, processed in the existing 10,000 mB oxidizer batches.
- Retained the seven-day rated-flux irradiation requirement. Existing completed
  but unharvested rods receive the new yield; already harvested samples retain
  their original per-item output.
- Split large harvests into normal inventory stacks and preserved dropped
  overflow when inventory space runs out. Added an overflow conservation check
  and pinned the total yield against real Chemical Oxidizer output.
- Updated tooltips and the fuel guide, including the 64-target / 700-fuel
  example for a 764-position core and a 2.5 buckets/second D-T consumer.
- GUI protocol remains 8. Use matching alpha.7 client/server builds.

## 0.1.0-alpha.6 — 2026-09-24

- Increased rated-local-flux exposure to 48 operating hours for antimony
  activation, 24 hours for silicon, 72 hours for cobalt and 168 hours for
  tritium. These are real running hours at 20 TPS, not Minecraft days.
- Preserved flux-dependent progress, saved exposure seconds, and the absence
  of offline progress. Existing unharvested rods are measured against the new
  thresholds; already harvested products remain usable.
- Completed tritium rods now yield 64 sealed samples and one reusable casing.
- Added an optional Mekanism Chemical Oxidizer recipe: one sample becomes
  10,000 mB tritium, totaling 640,000 mB per rod. Each operation fits the
  machine's output tank and consumes its input normally. Both Mekanism and
  Mekanism Generators are required for this recipe and remain optional for
  BWR itself; ordinary sample crafting is unchanged.
- Added operating-exposure and batch-yield tooltips, updated the fuel guide,
  and extended tests for multi-day progress, migration, one-shot harvesting,
  powered chemical processing and optional-mod recipe gating.
- GUI protocol remains 8. Use matching alpha.6 client/server builds.

## 0.1.0-alpha.5 — 2026-09-24

This entry covers all accumulated alpha.1–alpha.5 work since the previous
GitHub `main` push, commit
[`b126650`](https://github.com/OldGunslingerWildBill/bwr-mod/commit/b1266509bdde477436a47169b5ea255c245cfc35)
on September 23, 2026. It includes code, Blender sources, exported models,
textures, recipes, regression tests and documentation.

### Transport, heat and computer connections

- Exposed machine/controller and modeled assembly faces accept CC:Tweaked
  modems. Passive vessel and concrete walls remain structural blocks.
- Added measurement peripherals for condensers, RHR heat exchangers, fuel
  fabricators, control rod drives, RPV water ports and jet pumps.
- Computer calls resolve the current owner on the server thread. Removed or
  unloaded owners return Lua errors; connections recover after replacement or
  reload instead of retaining stale controllers.
- Water inventories carry temperature and energy through pumps, tanks,
  cooling towers, heat exchangers, condensers and suppression-pool fill/spray.
  Mixing, fractional withdrawals and save/reload preserve mass and heat.
- Condenser cooling-water temperature and exhaust backlog affect vacuum and
  LP turbine backpressure, reducing turbine work when cooling deteriorates.
- Pipe blocks have no block entities or tickers. Cached routing collapses
  ordinary runs into junctions and is invalidated by structural, capability
  and chunk changes, rather than rebuilt every tick. Transfers still check
  current valve openings and available inventory. Searches are bounded and
  never force-load chunks.
- Shared fluid stores are reserved once across multiple port proxies,
  preventing duplicate simulated capacity and water loss.
- MSIV isolation follows the consumer branch; a closed unused branch no
  longer throttles an open steam header. MSIV-only headers are supported.
- Recirculation commands use the inverse of the shared pump/jet delivery
  limits, correcting the mismatch between requested and delivered flow.
- Fuel-grid and panel readouts are batched to reduce GUI rendering cost.

See [plant transport and computers](PLANT-TRANSPORT.md) and the
[re-audit and regression record](PHASE-ONE-REAUDIT.md).

### SLC, ADS and water discharge

- Added Blender-built SLC boron solution tank, powered injection pump,
  borate charges, ADS division cabinet, ADS relief valve and discharge port.
- SLC uses finite water and boron inventories and actual FE-powered delivery.
  Connect its discharge to an RPV water inlet or a valid feedwater cross-tie.
  Computer readouts report actual delivered boron.
- ADS division assignments persist and support local, redstone and computer
  control. Relief valves use real steam connections and a shared nozzle
  allowance, avoiding duplicate steam withdrawal by multiple reporters.
- The passive water discharge/outfall port accepts water at the rear when
  the outlet is clear and loaded. All inputs share its rate limit; readouts
  include flow, cumulative mass, temperature and heat discharge.
- Added matching recipes, loot tables, models, connection data, service GUIs
  and computer methods. Protection automation remains player-programmed.

See [alpha hardware and connections](ALPHA-HARDWARE.md).

### Creative inventory performance

- Replaced expensive machine meshes in inventory slots with 33 lightweight
  Blender-rendered icons while retaining full placed and held models.
- Reproduced and corrected the creative-menu FPS drop. In the local test
  fixture, the measured menu improved from about 19 FPS to about 561 FPS;
  this is a test result, not a guarantee for every machine or modpack.
- Added client checks for the icons and repeatable performance captures.

### Condensate tanks and control rod drives

- Breaking a formed condensate tank restores its construction casings
  instead of deleting the entire structure. Survival/tool/creative drops
  and surplus construction materials are handled without duplicate drops.
- An on-disk restoration ledger handles unloaded portions without loading
  their chunks. Drain stored water before dismantling a tank.
- Face-adjacent control rod drives share finite water and FE supplies.
  A packed 17 × 17 grid can be supplied through separate water and power
  connections on two bottom faces, provided those sources cover total demand.
- Individual drive motion, wear and accumulators remain independent. Supply
  topology is cached and changes only when connections or chunks change.

See [condensate tanks](CONDENSATE-TANK.md) and [compact cores](COMPACT-CORE.md).

### Condenser fit, placement and hotwell makeup

- Clicking an LP turbine's underside with a condenser snaps it beneath that
  turbine. A green/red placement preview checks the complete footprint,
  obstructions, entities and build permissions before consuming the item.
- Reworked the Blender model to fit the LP seat, widen the body and seal its
  transition. The final layout occupies 9 blocks across the turbine shaft,
  7 along it and 6 in height; adjacent LP sections retain clearance.
- Corrected new condenser orientation by 90 degrees relative to the shaft,
  putting the main pipe faces toward the hall sides rather than the next LP.
- Rebuilt the small oval side fittings as circular open flanges and made
  both working hotwell makeup-water inlets. The final layout has eight ports.
- Makeup mixes with finite hotwell inventory and heat independently of the
  circulating cooling-water circuit. The two inlets share one capacity limit.
- Updated hotwell GUI labels and added computer readouts `hotwellKg`,
  `hotwellC` and `makeupSpaceKg`.
- Preserved older v1/v2/v3 condenser dimensions, orientations, port numbering,
  inventories and render assets for existing worlds. Drain and replace an
  old condenser to receive the new geometry and connections.

### Powered MSIVs

- MSIVs start closed and require FE to open and remain open: a 20,000 FE
  buffer, 100 FE/t during opening and 20 FE/t while holding open.
- Loss of power releases a four-second spring closure.
- Non-steam faces accept electric cables; modeled part faces accept CC modems.
  Added power telemetry to `bwr_msiv` and verified Mekanism cable connections
  in every rotation. Legacy MSIV cubes also require FE.

See [condenser and MSIV placement, ports and migration](CONDENSER-AND-MSIV.md).

### Fuel catalogue and specialty rods

- Expanded from five to 19 fuel definitions while preserving the original
  five IDs, numerical tuning and saved exposure.
- Uranium grades now cover natural 0.711%, 1.2%, 1.4%, 2.7%, 3.5%, 4.95%,
  8%, 19.75% and 20%, plus a 3.5% gadolinia variant.
- Added lean/standard/rich MOX, plutonium and thorium/U-233-driver families.
  Unusual grades are labeled experimental. Natural uranium is subcritical
  in this BWR model; MOX values are gameplay-effective fissile fractions,
  not commercial total-plutonium assays.
- Fuel fabrication selects grades from actual input enrichment without
  creating additional fissile inventory.
- Added eight non-fuel rods: boron-carbide and hafnium absorbers; Cf-252,
  Am-Be and Sb-Be neutron sources; cobalt, tritium and silicon targets.
- Absorbers affect local and bulk absorption; sources add startup neutrons.
  Targets accumulate exposure from local fission flux. They do not count as
  fuel, produce fission heat or inflate fuel burnup and power capacity.
- Refuelling, shuffling, tooltips, purple core-map entries, saves and pending
  controller-removal recovery retain specialty rods and their exposure.
- Added a simple craft/load/irradiate/harvest loop. Completed targets yield
  a sample and reusable casing once; full inventories drop the results.
  Samples have Minecraft crafting uses. These are simplified game recipes.
- Added the separate **Realistic BWR: Fuel & Rods** creative tab with 33
  entries, plus 34 Blender-rendered PNGs including the refreshed original
  fuel assembly icon. All icons use lightweight item models.

See [fuel grades, recipes and reference notes](FUELS-AND-RODS.md).

### Compatibility and verification

- Version: **0.1.0-alpha.5**, Minecraft **1.21.1**, NeoForge **21.1.248**,
  Java **21**. GUI protocol is now **8**; update clients and server together.
- Added regression coverage for transport, computer lifecycle, SLC/ADS,
  inventory performance, tank dismantling, shared CRD supplies, condenser
  placement/makeup, powered MSIVs and specialty fuels.
- Latest Minecraft run passed **50/50** required GameTests. Dedicated-server
  checks passed with and without Mekanism/CC:Tweaked, including eight
  assembly-permission scenarios in each configuration.
- The full physics run passed **210/211** checks; its sole failure was an
  outdated five-fuel catalogue assertion. After updating that assertion, the
  entire `FuelTypeSpec` group passed **4/4**. Final fuel calibration passed
  **5/5** focused tests. The long suite was not repeated after the test-only
  catalogue correction; these are combined passing results, not a claim of
  one clean 211-test run.
- Build/design checks passed, along with the 2,392-file JSON audit, packaged
  class/license audit, machine-icon checks and all 19 fuel/8 rod client checks.
- Updated the README, connection/migration guides, verification records and
  Blender exporters. Detailed evidence and JAR checksum: [BUILD-STATUS.md](BUILD-STATUS.md).

Earlier suppression-pool fill/spray/passive cooling, the original detailed
water pumps, compact reactor cores and the initial cooling systems were
already included in the preceding push and are not new additions here.
