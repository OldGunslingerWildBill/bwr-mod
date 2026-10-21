Old-save fixtures for :mod:runSaveCompatCheck (see TESTING.md, "Old saves").

alpha40/   A world written by the alpha.40 build (commit dc619dc): the persistent GameTest world after a full
           :mod:runTurbineGameTest there (reactors, vessels, control rod drives, pumps, containment, suppression
           pools, jet pumps, ...) plus SaveFixtureBuilder's output: 26 of the 27 panel component types on panels with
           plant, component and CC-port bindings, a machine named RHR-1 on CC port 7, and every bwr block (default state
           and each facing). Reduced with tools-trim-save-fixture.py to the 326 chunks that hold a bwr block or block
           entity (7947 block entities, 72209 bwr blocks, 91 block ids, 89 panel block entities).

Regenerating (for example from a later alpha, to keep a newer format covered):
  1. git worktree add ../bwr-save-fixture <commit>
  2. copy SaveFixtureBuilder.java.txt and SaveStage.java.txt into mod/src/main/java/dev/bwr/mod/devtest/ without the .txt
     (they compile against the alpha.40 API; adjust what changed)
  3. run :mod:runTurbineGameTest there through run-mc.ps1; the world is mod/run/turbineGameTest/world
  4. python tools-trim-save-fixture.py <that world> mod/src/test-worlds/<name>
The check reads the fixture, never writes it: Gradle copies it to mod/run/saveCompatCheck/world first.
