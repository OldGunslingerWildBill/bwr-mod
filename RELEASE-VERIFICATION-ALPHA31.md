# Alpha.31 verification record

Branch: `containment-structure`

| Check | Result |
| --- | --- |
| `./gradlew.bat :mod:runTurbineGameTest --console=plain` | 114/114 GameTests passed on the persistent world |
| `./gradlew.bat check build --console=plain` | Passed in 15m 51s |
| Core acceptance suite within `check build` | 260/260 passed; 39 registered classes |
| `py tools-audit-assets.py` | 0 problems; 64/64 block recipes and loot tables |
| `py tools-check-alpha31-docs.py` | Passed |
| `py tools-check-jar.py` | Passed; no bundled CC:Tweaked, Mekanism, or devtest classes |

Built JAR: `mod/build/libs/mod-0.1.0-alpha.31.jar` (25,428,276 bytes)

SHA-256: `ADE4F3672C24CCF3F8505CD61737197953A86BCFEFD271C14AE3D80D87130B60`

The F0c persistent-world fixture audit included two consecutive uncleaned
GameTest runs before the entity-readiness change and two after it, all passing
at 110/110. The earlier 25/26 tank-drop failure did not recur in those four
runs, so fixture timing is a strong hypothesis rather than a proven cause.
Subsequent same-world runs found and fixed a separate nitrogen-equipment
fixture reset issue. No existing test assertions were changed.

The dedicated containment controller GUI (F3) was skipped by request. Its
in-world status readout and `bwr_containment` CC methods are available. New
nitrogen and vent blocks use simple machine-casing placeholder models; the
vault door and airlock retain their Phase E Blender models.
