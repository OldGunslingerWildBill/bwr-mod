package dev.bwr.mod.panel;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Linking the other way round with the Panel Linker: the panel component first, then the plant device.
 *
 * <p>Sneak-right-clicking a component selects it for the player ({@link #select}); right-clicking a plant device
 * with the linker then stores that device on the linker, exactly as sneak-clicking it would, and opens the same
 * binding screen for the selected component ({@link #linkDevice}). The component stays selected until another is
 * selected, the player sneak-right-clicks the air, changes dimension or leaves.
 *
 * <p>The selection is server state, one per player. While it stands it also lets that player open the binding screen
 * of, and bind, that one component from beyond arm's reach of the panel ({@link #holds}), since the player is then
 * standing at the device: it was made within reach of the panel, with the linker, by a player allowed to edit it. Every
 * other rule of a binding is unchanged (the linker in hand, permissions at the panel and the device, same dimension,
 * the link range between panel and device, both loaded).
 */
public final class LinkSelection {
    /** A selected component: its owner block and id, in a dimension. */
    public record Selected(ResourceKey<Level> dimension, BlockPos owner, int id) {
    }

    private static final Map<UUID, Selected> SELECTED = new ConcurrentHashMap<>();

    private LinkSelection() {
    }

    /**
     * Selects the component {@code id} of the panel block at {@code ownerPos} for {@code player}, who must hold the
     * Panel Linker, be within reach of the panel and be allowed to edit it. Says which in the action bar.
     */
    public static boolean select(ServerPlayer player, BlockPos ownerPos, int id) {
        ServerLevel level = player.serverLevel();
        if (id < 0 || !AssemblyAccess.mayEdit(player) || PanelLinkerItem.held(player).isEmpty()
                || !PanelOps.inReach(player, ownerPos) || !level.isLoaded(ownerPos)
                || !AssemblyAccess.permitted(level, player, ownerPos)
                || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return false;
        }
        PanelComponent component = owner.component(id);
        if (component == null || component.type() == null) {
            return false;
        }
        SELECTED.put(player.getUUID(), new Selected(level.dimension(), ownerPos.immutable(), id));
        player.displayClientMessage(Component.translatable("message.bwr.panel_linker.component", component.label()), true);
        return true;
    }

    /** The player's selection, or null. One made in another dimension is dropped. */
    public static Selected of(ServerPlayer player) {
        Selected selected = SELECTED.get(player.getUUID());
        if (selected != null && !selected.dimension().equals(player.serverLevel().dimension())) {
            SELECTED.remove(player.getUUID());
            return null;
        }
        return selected;
    }

    /** Whether the player's selection is exactly this component (which then needs no reach to be wired). */
    public static boolean holds(ServerPlayer player, BlockPos ownerPos, int id) {
        Selected selected = of(player);
        return selected != null && selected.id() == id && selected.owner().equals(ownerPos);
    }

    /** Forgets a player's selection; returns whether there was one. */
    public static boolean forget(UUID player) {
        return SELECTED.remove(player) != null;
    }

    /**
     * The second step: {@code player} right-clicked the block at {@code devicePos} with the Panel Linker
     * {@code linker}, not sneaking. With a component selected and a plant device there, the device is stored on the
     * linker and the binding screen of the selected component opens.
     *
     * @return whether the click was taken (false leaves it to the block, as without a selection)
     */
    public static boolean linkDevice(ServerPlayer player, ItemStack linker, BlockPos devicePos) {
        Selected selected = of(player);
        ServerLevel level = player.serverLevel();
        if (selected == null || !(linker.getItem() instanceof PanelLinkerItem) || LinkTools.held(player) != linker
                || !AssemblyAccess.mayEdit(player) || !level.isLoaded(devicePos)
                || SignalProviders.at(level, devicePos) == null) {
            return false;
        }
        linker.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), devicePos.immutable()));
        if (!WiringOps.openScreen(player, selected.owner(), selected.id())) {
            // the panel is gone, unloaded or no longer the player's to edit: the selection means nothing now
            forget(player.getUUID());
            player.displayClientMessage(Component.translatable("message.bwr.panel_linker.component_lost"), true);
        }
        return true;
    }
}
