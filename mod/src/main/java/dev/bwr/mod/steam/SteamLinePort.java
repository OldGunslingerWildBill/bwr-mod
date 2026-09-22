package dev.bwr.mod.steam;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marks a block as a nozzle on the main steam system, so that a
 * {@link PressurisedTubeBlock} placed against it grows an arm towards it
 * instead of ending in mid-air.
 *
 * <h2>Why an interface and not an if-chain</h2>
 * The tube's whole reason to exist is structural: {@code PressurisedTubeBlock}
 * answers "is this SRV on a valid steam line, is the turbine actually
 * connected". Answering that requires the tube to know what counts as steam
 * hardware, and the naive way to encode that — a chain of
 * {@code state.is(BwrBlocks.X.get())} tests inside the tube — makes the tube the
 * one place that has to be edited every time a new piece of steam hardware is
 * added anywhere in the mod. That is exactly backwards: the tube is generic and
 * the hardware is specific, so the hardware should be the thing that speaks up.
 *
 * <p>So a block joins the steam line by implementing this interface. The method
 * has a default, which means the entire opt-in for a block that connects on all
 * six faces is the two words {@code implements SteamLinePort} on the class
 * declaration — no registration call, no static initialiser ordering, no
 * resource file, and the compiler checks it.
 *
 * <p>The second, data-driven route is the {@code #bwr:steam_line} block tag,
 * which {@link PressurisedTubeBlock#connectsTo} consults for any block that does
 * not implement this interface. That route exists for blocks whose class we
 * cannot touch — another mod's pipe, a pack author's decorative header — and it
 * cannot express per-face behaviour, which is precisely why it is the fallback
 * rather than the primary.
 *
 * <h2>This is not a permissive</h2>
 * A port answers a question about geometry: does a pipe physically land on this
 * face. It says nothing about whether steam should flow, whether the plant is in
 * a fit state to flow it, or whether anything ought to be opened or shut. Those
 * are the player's judgements and the mod does not make them.
 */
public interface SteamLinePort {

    /**
     * Whether a pressurised tube laid against {@code face} of this block should
     * read as landing on a nozzle.
     *
     * <p>Defaults to accepting every face for legacy unoriented hardware.
     * Modeled valves and machine assemblies override this to expose only their
     * actual flanges. An MSIV actuator or bonnet is not a steam connection.
     *
     * @param state this block's own state, so an override can read its
     *              {@code FACING} or any other property it wants to
     * @param face  the face of <i>this</i> block that the tube is offered on;
     *              the tube is at {@code pos.relative(face)}
     */
    default boolean acceptsSteamLineOn(BlockState state, Direction face) {
        return true;
    }
}
