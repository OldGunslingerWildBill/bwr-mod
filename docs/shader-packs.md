# Sodium, Iris and shader packs

Realistic BWR works with Sodium and Iris, with or without a shader pack. Neither mod is required, bundled or
declared as a dependency: install them yourself if you want them, and leave them out if you do not.

## Tested versions

Minecraft 1.21.1 with NeoForge 21.1.248, the whole control-panel and core-display set, the reactor, turbine and
containment machines:

| Sodium | Iris | Shader pack |
| --- | --- | --- |
| 0.6.13 | 1.8.12 | none, and Complementary Reimagined r5.9.3 |
| 0.8.13 | 1.8.14-beta.1 | Complementary Reimagined r5.9.3 |

Other versions and other packs are expected to work the same way (the mod draws through the game's own render
types and leaves the program choice to Iris), but they have not been looked at.

### Compatibility pass, alpha.50 (the hardware added since the first check)

The same four set-ups (no render mods; Sodium 0.6.13 + Iris 1.8.12; Sodium 0.8.13 + Iris 1.8.14-beta.1; the newer pair with
Complementary Reimagined r5.9.3) were run through a client check for each of: the spent fuel pool (water surface, racks, Cherenkov
glow, ports, panel, uncovered fuel), the refuelling bridge (rails, gantry, mast, canal and chute water, the carried assembly and its
glow, the control screen, the survey meter HUD), the vessel head entity (failing, in flight, landed, held under an obsidian plug), the
shield plug concrete tiers, the plant computer screens (default pages and the SPDS program), the area radiation monitor and the survey
meter HUD, the containment vault doors and airlock (closed, mid swing, open, penetrations, hot and cold corium), and the uranium ore
(vein cut-away, close-up, the ore blocks, the items). 49 captures per set-up, 1920 x 1080 (1920 x 1061 under Iris, which replaces the
supersampled screenshot path), every one looked at, and the logs of all runs searched for exceptions, render-type or shader errors and
mixin warnings that mention bwr: none. Result: no missing or black face, no z-fighting, no wrong translucency order between the pool or
canal water and the glow, the head entity is drawn in every set-up, and the survey meter HUD shows in every one.

One more client session (join a flat world, build the refuelling floor, use the bridge console and the pool controller, place an area
radiation monitor and drive it into alarm, build the plant computer showcase) ran with Essential 1.5.0.1 and Effortless Structure 3.4.0
next to Mekanism, Mekanism Generators and CC:Tweaked, once without render mods and once with Sodium 0.8.13 + Iris 1.8.14-beta.1 +
Complementary: no errors or warnings from bwr or from a conflict with either mod (Essential logs its own failed sign-in of the
development account, which is expected there).

## What you will see

* Lamps, annunciator tiles, segment and LCD displays and the lit parts of the full core display glow under a pack,
  because they are drawn full-bright. Meter needles, dials, legends and engraved text read as they do without a pack.
* A pack's own shadows, bloom and colour grading apply to panels like any other block, so a panel colour and an
  emissive lamp look different from pack to pack.
* The wrench and linker screens are ordinary screens and are not affected.

## Known differences and issues

* A strongly lit surface facing the sun is bleached by Complementary (the ground is), so the top of a landed vessel head and the pool
  rim look very pale at noon. It is the pack's exposure, not a missing texture.
* Machine meshes (reactor vessel, the head, the containment doors, the bridge) are shaded more evenly, and darker, without Sodium than
  with Sodium or Iris: measured on the vessel in the radiation check, the lit side is 63 without and 91 with, the front 43 and 77 (0 to
  255). Nothing is missing either way; the cause was not looked into (it also shows on the older machines, so it is not specific to the new hardware).
* The plant computer and the other panel screens are lit by the pack like any block (their yellow and green tiles are duller under
  Complementary than without it); only lamps, annunciator windows, segment displays and the core display glow full bright.
* Essential 1.5.0.1 asks for consent to update itself when it finds a newer version and waits for the answer: in an unattended test
  run that looks like a hang at "Starting Essential Loader".

## Performance

Panels and machines keep their geometry in GPU buffers, with or without a pack. Measured on one machine
(Ryzen 7 5800X3D, RTX 5080, 1280 x 720) with a fully populated 16 x 8 panel wall of 616 components, mean frame time
over 8 seconds: see `TESTING.md` ("Sodium, Iris and shader packs"). A shader pack still costs frame time of its own, and its
shadow pass draws every panel and machine a second time.

If a pack or another render mod draws the panels wrongly, set `panelGpuCache = false` in `config/bwr-visuals.toml`
(client): the panels are then built every frame, which is slower but goes through the plainest path the game has. Please report
the pack and versions.

## Known limitations

See `TESTING.md` for the exact list. In short: a few of the developer render checks compare pixels or count renderer calls and
cannot hold under Sodium or a pack (a pack changes colours; Sodium calls block entity renderers for everything in a visible
section). Those are differences in the test, not in the picture.
