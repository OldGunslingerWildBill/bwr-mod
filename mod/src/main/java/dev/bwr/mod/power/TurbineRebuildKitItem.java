package dev.bwr.mod.power;

import dev.bwr.mod.text.BwrText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A Turbine Rebuild Kit (TURBINE-MAINTENANCE.md): new bearing shells, coupling bolts, gland segments and casing plate for one turbine
 * section. Used on a stopped, cold section (any cell) it rebuilds its casing, bearings, couplings and glands: the wreck is cleared and the
 * shaft's condition is new. An LP is rebuilt open, with both blade sections lifted out; the blades are the crane's to replace. The kit
 * is used up.
 */
public class TurbineRebuildKitItem extends Item {
    public TurbineRebuildKitItem(Properties properties) {
        super(properties);
    }

    /** The player used a kit on the section: rebuilt and one kit taken, or told why not. Server side. */
    static void use(PowerModuleBlockEntity module, Player player, ItemStack stack) {
        Component why = module.rebuildProblem();
        if (why != null) {
            player.displayClientMessage(why, true);
            return;
        }
        boolean wrecked = module.wreck() != PowerModuleBlockEntity.WRECK_NONE;
        module.rebuild();
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        module.getLevel().playSound(null, module.getBlockPos(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 0.8f);
        player.displayClientMessage(wrecked
                ? BwrText.tr("status.bwr.turbine_rebuild.done_wreck", "Rebuilt: the casing, bearings, couplings and glands are new and the section is coupled to its train again.")
                : BwrText.tr("status.bwr.turbine_rebuild.done", "Rebuilt: the bearings, couplings and glands are new; the shaft's condition is as new."), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bwr.turbine_rebuild_kit").withStyle(ChatFormatting.GRAY));
    }
}
