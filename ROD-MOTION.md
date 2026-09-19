# Rod travel and power response

Rod commands still use the 00–48 notch labels in steps of two. The physical
absorber now travels continuously between those positions at the existing drive
speed. A normal 24→28 move takes about 4.17 seconds in the game.

Previously, a timer held the absorber at one notch and then advanced it instantly
to the next. The reactivity balance therefore received a whole-notch step.
`ReactorCore` now advances `rodPositionNotches` each simulation tick and evaluates
the integral-worth curve at that fractional position. Every original integral
worth at an exact notch is preserved. The nodal solve also receives fractional
positions on its existing refresh schedule.

The panel's notch label shows the last notch crossed. Power and period respond
to actual travel during the interval between labels. Point kinetics and the
existing instrument filters still determine the response; no display-only power
filter or automatic rod control was added.

An individual drive outage or loss of the drive bus holds the current physical
position. Reversal starts there. Scram also starts there, with continuous travel
limited by accumulator charge and the existing pressure-assist model.

`ReactorState.rodPositionsNotches` and the matching NBT key preserve in-flight
positions. Older saves without that field start from their recorded notches.
The mod continues to own and restore operator demand separately. Until a demand
is restored, the core holds its physical position rather than reversing toward
the last latch. The record retains one canonical constructor, so all serialization
call sites must explicitly handle new fields.
