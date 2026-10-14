# Part A1 GameTest audit

All 61 source classes containing `@GameTest`/`@GameTestHolder` were audited for item/entity counts behind fixed delays and shared registry/counter baselines. No GameTests were found outside `mod/src/main/java/dev/bwr/mod/devtest`. Delegated runtime helpers and `FcdTestSupport` were included.

| Class | Finding / action |
|---|---|
| Alpha22RegressionTests | Synchronous global graph-build baseline originally safe; now same suction graph identity, exact pump port/face/foreign=true key used by CoolingWaterTransfer. No production changes. |
| Alpha23RegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha24RegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha26RegressionTests | Retained synchronous buildCount before/tick/after: server thread cannot interleave another GameTest inside this body. Comment explains. This specifically checks no graph creation by boundary checks; replacing with an unrelated graph would weaken coverage. |
| Alpha27RegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha30SeverePersistenceTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31AirlockRedstoneTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31AirlockReviewTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31AirlockTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31BypassReviewTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31ContainmentEquipmentTests | Global suppression registry size appears only in diagnostic failure text; assertion checks the fixture containment/pool identity. |
| Alpha31ContainmentPeripheralTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31ContainmentTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31IsolationTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31OpeningCacheTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31OpeningsAcceptanceTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31PenetrationTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| Alpha31VaultDoorTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| AlphaRegressionTests | Synchronous global baseline originally safe; now both SLC suction/discharge graph identities, exact keys used by SlcPlumbing. No production changes. |
| CompactCoreRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| CondenserMakeupRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| CondenserValveRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| CoreAppearanceRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| CoriumMeltTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FcdDisplayTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FcdIndicationTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FcdLabelTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FcdModelTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FcdRodTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FcdSnapshotTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| FuelVarietyRegressionTests | Actual drop conservation assertion; overflow test now waits on probe before any player/inventory/drop action. All original exact totals retained. |
| InternalPumpRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PanelChunkReloadTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PanelDesyncTests | Already probes before setup and again after owner chunk reload. waitingCount(level,this::owns) is already fixture-scoped. Fixed later delays observe propagation after lookup readiness, not readiness substitutes. |
| PanelDisplayTests | Entity lookup is cleanup only; fixed setup delay followed by component/codec/layout assertions, no entity count. |
| PanelFoundationTests | Already probes all relevant wall chunks before setup/drop action; later fixed delay observes propagation. Exact item counts unchanged. |
| PanelInitialChunkSyncTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PanelInputTests | Entity lookup is cleanup only; delayed assertions concern component state/timing, no entity count. |
| PanelLabelTests | Entity lookup cleanup only; delayed assertions concern labels/group membership. |
| PanelModelTests | Entity lookup cleanup only; delayed assertions concern model and component configuration. |
| PanelRecipeTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PanelSlopeTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PanelWiringTests | Entity lookup cleanup only; delays concern sampler/CC wiring cadence. No global registry-count baseline. |
| PhaseJRhrHardwareTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseJSteamCouplingTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseJTwoVolumeReadoutTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseLAdvancementTests | PanelSampler.isTracked(fixture panel) already scopes registry assertions; delayed checks concern sampler tick and advancement conditions, not item counts. |
| PhaseLColdCriticalityTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseLGameplayConfigTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseLSoundTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseLTranslationsTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| PhaseOneRegressionTests | Actual controller-mining fuel drop/exposure assertion now waits before whole synchronous scenario. Other item lookup is cleanup only. |
| PhaseTwoRegressionTests | Route cache rebuild counters are per-machine cache objects, not level/global; registry checks use contains(the fixture reactor), not global counts. |
| ReleaseRegressionTests | Synchronous global baseline originally safe; now queried fixture graph identity before/after, preserving no-rebuild semantics. Existing timer-loop identity assertion unchanged. |
| RvuRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| SignalLayerTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| SpargerRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| SuppressionBasinRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| SuppressionTankRegressionTests | No entity-count timing assertion or asynchronously compared global counter found; fixture state, static catalog/codec, or pure model assertions. |
| TankDriveRegressionTests | Mining readiness formerly only checked entity-loaded/ticking metadata and only newly forced chunks; now actual probes all bounds. Deferred tank count now waits after reload; pending plans and restore scoped by assembly UUID; ticket lifecycle cleanup. Bank caching uses fixture identity. |
| TurbineAssemblyRuntimeCheck | One GameTest delegates all runtime checks; metadata readiness replaced by actual probes. Bounds include rotated x=200 fixtures beyond previous x<=12 chunk hold. All drops/count assertions unchanged. |

The terminal listener also covers failure, timeout and rerun; queued polls stop after release. It releases only newly owned forced chunks. Assertions run once after independent lookup readiness, not in a retry-until-pass loop. Deferred tank restore/completion selects its assembly UUID; a second real tank verifies cross-fixture isolation.

Executed proof, limitations, failure controls and exact commands are recorded in [ASTRA-TODO-5-PART-A-REVIEW.md](ASTRA-TODO-5-PART-A-REVIEW.md). The original synchronous cache-baseline cases were not reproduced concurrency defects.
