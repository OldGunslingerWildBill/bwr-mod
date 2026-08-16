package dev.bwr.mod.reactor;

import net.minecraft.world.level.block.Block;

/**
 * Reactor pressure vessel shell.
 *
 * <p>Structural only — it carries no block entity and no state. The shell's job
 * is to be present in the right places when the controller validates, and to be
 * the thing that fails when the overpressure model picks a component to break.
 */
public class ReactorVesselBlock extends Block {

    public ReactorVesselBlock(Properties properties) {
        super(properties);
    }
}
