package dev.bwr.mod.panel;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * The item of a piece of a modular 45 degree run ({@link AngledRunBlock}): placing it snaps the piece to the open end of the
 * run it is aimed at (whichever block of that run was clicked), or to the end of a straight desk or bench, so a run grows
 * one piece at a time in line. See {@link AngledRunBlock#resolve}.
 */
public final class AngledRunItem extends BlockItem {
    public AngledRunItem(AngledRunBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public BlockPlaceContext updatePlacementContext(BlockPlaceContext context) {
        AngledRunBlock.Placement placement = ((AngledRunBlock) getBlock()).resolve(context);
        if (placement == null || placement.origin().equals(context.getClickedPos())) {
            return context;
        }
        return BlockPlaceContext.at(context, placement.origin(), context.getClickedFace());
    }
}
