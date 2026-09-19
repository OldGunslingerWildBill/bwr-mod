package dev.bwr.mod.reactor;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;

/** Two named vessel penetrations; the internal jet manifold is part of the multiblock. */
public class RecirculationPortBlock extends RpvWaterInjectionPortBlock {
    private final boolean outlet;
    public static final MapCodec<RecirculationPortBlock> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
            propertiesCodec(),Codec.BOOL.fieldOf("outlet").forGetter(b->b.outlet)).apply(i,RecirculationPortBlock::new));
    public RecirculationPortBlock(Properties properties,boolean outlet){super(properties);this.outlet=outlet;}
    public boolean isOutlet(){return outlet;}
    @Override protected MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec(){return CODEC;}
    @Override public void appendHoverText(ItemStack stack,Item.TooltipContext context,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable(outlet?"tooltip.bwr.recirculation_outlet":"tooltip.bwr.recirculation_inlet"));
    }
}
