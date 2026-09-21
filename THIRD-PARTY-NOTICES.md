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
