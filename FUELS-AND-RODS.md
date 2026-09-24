# Fuel and specialty rods — alpha.5

Open **Realistic BWR: Fuel & Rods** in the creative inventory. The machine tab
now contains plant hardware; this tab contains 19 fuel grades, eight specialty
rod cassettes, empty irradiation rods, target charges and samples. All icons
are small PNGs rendered from the Blender studio in `art/models/fuel_icons/`.
Items still represent one logical core position, not a placeable fuel block.

## Fuel catalogue

| Family | Available grades | Meaning |
|---|---|---|
| Uranium | 0.711%, 1.20%, 1.40%, 2.70%, 3.50%, 4.95%, 8.00%, 19.75%, 20.00% | U-235 weight fraction in the simplified fuel model |
| Gadolinia uranium | 3.50% | Additional burnable absorption compared with the standard 3.50% assembly |
| Mixed oxide | Lean 4.5%, standard 7%, rich 9.5% | Effective fissile fractions for gameplay, **not total plutonium percentages** |
| Plutonium | Lean 4%, standard 6%, rich 8.5% | Existing Pu-dominated kinetics family, extended with experimental grades |
| Thorium / U-233 driver | Lean 2%, standard 3%, rich 5% | Fissile driver fraction; thorium itself is fertile |

The original `leu`, `heu`, `mox`, `plutonium` and `thorium` definitions retain
their numerical tuning and item components. New grades use the existing
enrichment-utilisation curve and family constants. This is an extension of
the mod's calibrated model, not a library of validated commercial fuel designs
or an isotope-by-isotope depletion calculation. Natural uranium is a weak,
subcritical experimental loading in this BWR model.

The fuel fabricator selects uranium, MOX and plutonium grades from the actual
incoming enrichment, without granting additional fissile inventory. The
gadolinia variant and thorium grades are available from the creative tab or
datapack recipes; there is no new thorium processing chain. Existing fuel
crafting remains compatible. Every fuel definition remains datapack editable
under `data/bwr/fuel_type/`.

## Specialty rods

Remove the vessel head and open refuelling. Hold the desired item, select an
empty position and press **LOAD**. **UNLOAD** returns that exact cassette and
its irradiation history. **MARK / SWAP** moves it alongside fuel bundles.
Specialty positions appear purple in both core maps and show their progress.
The loaded fuel count excludes them; they contribute no fission heat, heavy
metal inventory or fuel burnup.

| Rod | Effect |
|---|---|
| Boron carbide absorber | Fixed neutron absorption |
| Hafnium absorber | A second fixed absorber option |
| Californium-252 source | Adds a small startup neutron source |
| Americium-beryllium source | Adds a weaker startup neutron source |
| Antimony-beryllium source | Builds up source strength while irradiated |
| Cobalt target | Produces one sealed cobalt-60 sample |
| Tritium target | Produces one sealed tritium sample; experimental BWR option |
| Silicon target | Produces one neutron-doped silicon item |

Absorbers add local absorption to the spatial solve and a geometry-weighted
fixed absorption term to the whole-core balance. Source rods add to the
existing background source; they do not replace it. These first-version
sources retain their strength while stored; radioactive decay is not simulated.
Fixed absorbers do not replace the movable control blades.

## Simple irradiation loop

1. Craft an empty irradiation rod from three iron ingots and one glass block.
2. Combine it with a cobalt target charge, lithium target charge or quartz to
   make a cobalt, tritium or silicon target. Cobalt charges use iron + lapis;
   lithium charges use clay + redstone. These are fictional Minecraft recipes.
3. Load the target into the reactor. Progress follows the local spatial flux
   and fission power. Decay heat does not process targets; unloaded items and
   unloaded reactors gain no offline progress.
4. Remove a completed target and **use it in your hand**. It yields one sample
   and one empty rod, consuming the completed cassette. A full inventory drops
   the result instead of deleting it.

At local flux equal to the rated core average, silicon takes 15 minutes,
cobalt 20 minutes, and tritium 30 minutes of **gameplay simulation time**.
The secondary source reaches its modeled strength after 10 equivalent minutes.
These accelerated thresholds are not real irradiation schedules. Position and
reactor power change the rate. Exposure survives shuffling, save/reload and
controller removal, and completed targets stop accumulating progress.

Sample uses: silicon + iron makes a repeater; cobalt sample + redstone + quartz
makes a comparator; tritium sample + glass makes a sea lantern. Samples are
sealed inventory items, with no radiochemistry or Mekanism chemical conversion.

Other rod recipes combine an empty casing with: borate charge (boron),
netherite scrap (hafnium), nether star (californium), echo shard (Am-Be), or
glowstone dust (Sb-Be). These recipes represent gameplay costs only.

## Reference basis

- The NRC describes natural uranium at about 0.7% U-235 and traditional power
  reactor fuel at roughly 3–5%. Consequently 3.5% is still **LEU**, even when
  it is a relatively rich BWR loading. [NRC uranium enrichment](https://www.nrc.gov/facilities-safety/fuel-cycle-facilities/uranium-enrichment).
- HALEU is above 5% and below 20%; 20% is the HEU boundary. The high-end
  options are labeled experimental. [NRC HALEU](https://www.nrc.gov/materials/new-fuels/haleu).
- The IAEA's comparison distinguishes uranium enrichment, total Pu content
  of MOX, and burnable absorbers. Our simplified effective fissile value must
  not be interpreted as a commercial MOX assay. [IAEA fuel information sheet](https://www.iaea.org/sites/default/files/24/11/evt2304628_information_sheet.pdf).
- Thorium-based fuel has historical BWR test experience, including Elk River
  and Lingen. Our three variants retain the existing U-233-driver model.
  [IAEA thorium fuel experience](https://conferences.iaea.org/event/146/contributions/5192/).
- Cf-252 primary sources and Sb-Be secondary sources are real reactor source
  categories. [QSA Global source catalogue](https://www.qsa-global.com/nuclear-neutron-sources).
- GE reported cobalt-60 production at Clinton in 2011, providing a BWR basis
  for the cobalt target idea. [GE Hitachi announcement](https://www.ge.com/news/press-releases/ge-hitachi-nuclear-energy-and-exelon-announce-potential-production-critical-medical).
- The documented TPBAR design is for PWRs, not ordinary BWR fuel. Our tritium
  target is explicitly a gameplay adaptation. [PNNL TPBAR description](https://www.pnnl.gov/publications/description-tritium-producing-burnable-absorber-rod-commercial-light-water-reactor).

Client and server must both use alpha.5: GUI protocol 8 carries tagged
specialty entries and a variable-length name index. Saved original fuel items
and core inventories remain compatible.
