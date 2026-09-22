# Third-party and historical licensing notices

The [project license](LICENSE) applies only to material the project owner owns
or has authority to license. It does not relicense third-party dependencies,
tools, references or assets. Preserve any notices that accompany those works.

## Dependencies and build tools

- **Minecraft and NeoForge:** installed separately. Their code and assets
  remain governed by their own terms; the project license grants no rights to
  them. Consult their distributions for the applicable notices.
- **Mekanism:** optional, installed separately and not bundled. Its own MIT
  license and notices remain applicable to Mekanism independently of this
  project's license.
- **CC:Tweaked:** optional, installed separately and not bundled. Parts of its
  API use LicenseRef-CCPL. Do not copy, shade or vendor those API classes into
  this project's release JAR. Consult CC:Tweaked's own license files for its
  different components.
- **Gradle wrapper:** its Apache-2.0 notices and any notices in the wrapper JAR
  are retained. The wrapper is build tooling, not original Realistic BWR code.

`tools-check-jar.py` verifies that the release does not bundle Mekanism or
CC:Tweaked classes and includes the project's license and attribution files.

## References, branding and models

Manufacturer names and referenced drawings identify the inspiration or source
material for game models; they do not imply endorsement. Their copyrights and
trademarks remain with their respective owners. The custom project license
does not claim ownership of third-party photographs, drawings or other source
assets merely because they were supplied to or used in this project.

The project logo was supplied by the project owner. Its inclusion does not
grant independent rights to any underlying third-party imagery. Any separately
licensed asset remains governed by its own applicable terms.

Reference data in REFERENCE-DATA.md cites the NRC BWR/6 manual by accession
ML20090J537. The reference document is not included in this repository. The
project license does not restrict reuse of uncopyrightable physical facts.

The condenser and MSIV meshes in `art/models/condenser_msiv` are original Blender
geometry inspired by the owner's supplied reference photographs. The condenser
uses publicly listed Arabelle reference dimensions; source links and approximate
dimensions are documented in CONDENSER-AND-MSIV.md. No downloaded third-party mesh
is embedded in these assets. Referenced photographs and manufacturer trademarks
remain outside the project's ownership claim.

The connectable condenser adaptation and bypass-valve meshes in
`art/models/condenser_ports` are also original Blender geometry. The upper
front reference locations are repurposed as game bypass ports at the owner's
request; this does not represent a manufacturer-verified port arrangement.

Cooling-system meshes in `art/models/cooling` are original Blender geometry.
The September 2026 tower revision also references photographs in SPIG's
cooling-tower brochure, Black & Veatch's Columbia Generating Station article,
and the USGS natural-draft tower photograph. These are linked in COOLING-WATER.md;
no photograph or third-party mesh was copied into the game textures or meshes.
Flowserve VCT/VTP pumps, Columbia's circular induced-draft towers and SPX
natural-draft references informed their appearance. No third-party mesh was
imported. The model dimensions, flow and electric ratings are game design
assumptions. Reference links are in COOLING-WATER.md; no manufacturer endorsement
or ownership of manufacturer marks is implied.

The scalable condensate tank components in `art/models/condensate_tank` are
original Blender-authored geometry. No third-party tank mesh is included.

The pressure-vessel components in `art/models/reactor_vessel` are original
Blender-authored geometry. No third-party vessel mesh is included. Their
dimensions follow the game's construction envelope.

## Previously published versions

Through commit [`19a7424`](https://github.com/OldGunslingerWildBill/bwr-mod/tree/19a742408e2cd7ef8cee42a26157e8662daa340b),
the core was offered under MIT and the mod under MPL-2.0. Previously granted
rights remain available for that material; this change does not prevent people
from using or redistributing earlier code under those licenses.

The exact previous files are retained here:

- [Earlier core MIT license](licenses/legacy/core-MIT.txt)
- [Earlier mod MPL-2.0 license](licenses/legacy/mod-MPL-2.0.txt)
- [Earlier project licensing overview](licenses/legacy/PREVIOUS-LICENSING.txt)

The earlier overview is a historical record, not current dependency guidance.
Both Mekanism and CC:Tweaked are optional in the current build.
