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

## What you will see

* Lamps, annunciator tiles, segment and LCD displays and the lit parts of the full core display glow under a pack,
  because they are drawn full-bright. Meter needles, dials, legends and engraved text read as they do without a pack.
* A pack's own shadows, bloom and colour grading apply to panels like any other block, so a panel colour and an
  emissive lamp look different from pack to pack.
* The wrench and linker screens are ordinary screens and are not affected.

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
