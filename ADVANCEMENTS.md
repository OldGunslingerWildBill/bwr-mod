# Plant advancements

L3 adds a Realistic BWR advancement tab with eight plant milestones. They record
what the player builds and operates. They award no items, recipes, experience,
or functions and never operate plant equipment.

The tab opens when a player obtains a reactor controller. The runtime also grants
the tab root when granting any plant milestone, so an existing player need not
craft another controller to see it. The IDs below use `bwr:milestones/` and the
runtime criterion is `achieved`.

## What earns each milestone

| ID | Display name | Trigger |
|---|---|---|
| `first_criticality` | First Criticality | A formed, fueled reactor has finite nonnegative reactivity and positive finite neutron power after a physics step. |
| `power_to_grid` | Power to the Grid | A nuclear generator actually delivers positive FE to an external consumer, by adjacency push or external extraction. Producing energy into its own buffer or a simulated transfer does not count. |
| `survive_scram` | Shutdown, Not Disaster | A new scram begins from at least 1% rated neutron power. All rods subsequently reach the inserted position and neutron power stays at or below 0.1% for 10 simulated seconds, with covered fuel, no failed cladding or melting, no broken pressure-boundary component, and no containment failure. Uncovering, damage, clearing the scram, or losing the formed reactor interrupts the attempt. |
| `refuel` | A Fresh Cycle | During refueling, remove a real fuel assembly and insert a fuel assembly into that emptied slot. Initial loading, an empty-slot removal, and replacing a specialty irradiation rod do not count. |
| `seal_containment` | Under Containment | Complete a containment survey that reports a sealed, nonstale enclosure with positive free volume and no scan still running. This records geometric sealing, not qualification for any accident pressure. |
| `build_control_room` | Eyes on the Plant | A connected panel group contains at least four panels and four real components, has component input and output capabilities, and has at least one finite live input. Constant-only wiring does not count. Existing panel group and wiring rules still apply. |
| `lower_head_failure` | The Vessel Gives Way | Observe a reactor's modeled lower-head state change from intact to failed. |
| `melt_through` | Nothing Left to Hold It | Observe the severe-accident model's basemat-failure flag change from intact to failed. Ablation depth alone does not grant the milestone. |

The vanilla tab uses three short branches across four columns, making the full
nine-node tree wider than tall and keeping it inside the standard viewport without
scrolling. The branches are first criticality → power to grid → refuel; build a
control room → survive a scram; and seal containment → lower-head failure →
melt-through. These parent links arrange the display only; the runtime records
each milestone's actual event independently. All nine nodes, including the root,
use distinct item icons with different silhouettes or surfaces: a steam-valve
handwheel, fuel bundle, generator, rod drive, fuel fabricator, vault door, control
panel, vessel casing, and corium. The root's reactor-controller inventory trigger
is unchanged. The two accident milestones remain hidden until earned, do not
announce in chat, and have no additional rewards.

The control-room check runs on component placement/removal, configuration or
wiring edits, an owner's permitted claim/interaction, and group formation/join
(including loaded panels). It uses the inputs already sampled by those events.
Periodic live sampling does not evaluate or award this milestone. If a live
source becomes available later, another qualifying edit or owner interaction
may be needed. A qualifying group credits every owned member in the same pass;
reader refreshes within one edit are batched by group.

The scram percentages, hold time, and panel/component counts are gameplay
achievement definitions, not real plant operating limits or safety criteria.
Advancement checks do not introduce a scram, interlock, startup, shutdown, load
shedding, or equipment protection. A player or their CC/panel logic must still
issue every operating command.

## Attribution and existing saves

Participating block entities carry a cosmetic `milestoneOwner` UUID. New real-player
placement assigns the first owner. Automated fake players do not take ownership;
equipment they place remains unclaimed until a real player's first permitted
interaction. An older entity with no owner is attributed the same way. An existing
valid owner is retained.
This attribution is separate from panel claims and all actual access checks; it
does not confer control permission, lock a machine, or transfer someone else's
ownership. Actions or continued simulation on attributed equipment credit that
equipment's milestone owner.

State is stored in the block entity's ordinary NeoForge persistent data under
`BwrMilestones`: ownership, earned and pending milestone masks, reactor observation
state, an unfinished scram's progress, and emptied refueling slots. Player
advancement completion uses Minecraft's normal player advancement storage. An
old save without these fields remains valid. Tracked refueling slots are cleared
when the reactor becomes unavailable or leaves refueling, preventing an old
removal from crediting a later initial loading. Before the first resumed reactor
step, existing accident flags establish a baseline, so loading an already failed
reactor does not itself create a new lower-head or melt-through event.

If the owner is offline when a milestone occurs, the equipment retains a pending
award. Login retries awards on participating entities already loaded; unloaded
equipment waits until its block entity loads with its owner online. There is no
world scan or forced chunk load. Pending delivery depends on the saved equipment
still existing. Missing advancement resources leave the pending bit in place
for a later retry. Awards are idempotent and have no recurring reward payload.

## Relationship to Phase M

The name “Power to the Grid” refers here to the existing FE output path. It means
energy reached an external FE consumer. L3 does not add a grid-tie block, demand
curve, output breaker, synchronizing, or an emergency diesel. Those remain
separate Phase M work subject to its prerequisite and specification review.

## Resource layout

Definitions are in `mod/src/main/resources/data/bwr/advancement/milestones/`.
The root uses the vanilla inventory-change trigger; eight plant milestones use
`minecraft:impossible` so only observed server events grant them. Titles and
descriptions use `advancements.bwr.milestones.*` translation keys. Icons are
existing registered BWR items; the background is the vanilla stone texture.
The asset audit rejects duplicate icon item IDs. The opt-in advancement client
check verifies full-tree vanilla layout bounds (including hidden nodes) at every
capture stage, checks all nine distinct icons when complete, and captures the
whole completed tree without panning or scrolling.
The format follows the [NeoForge 1.21.1 advancement documentation](https://docs.neoforged.net/docs/1.21.1/resources/server/advancements/).
