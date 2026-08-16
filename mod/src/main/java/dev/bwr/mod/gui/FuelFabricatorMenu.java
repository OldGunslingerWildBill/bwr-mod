package dev.bwr.mod.gui;

import dev.bwr.mod.fuel.FuelFabrication;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * The fuel fabricator's screen: the output slot, how far along the current
 * bundle is, and what the batch would come out as if it finished now.
 *
 * <p>The "input" side of this machine is not an item slot. Heavy metal arrives
 * as Mekanism chemicals through the {@code FuelFeed}, which is the whole point
 * of SPEC 2.4 — the player's existing enrichment plumbing is the enrichment
 * control. So the input is shown as feed status lines and a running fissile
 * fraction rather than as a stack, and the only slot is the one the finished
 * bundle appears in.
 *
 * <p>The enrichment readout is a measurement of the pile, not a target. There
 * is no dial: a player who wants HEU feeds the machine more fissile material.
 */
public class FuelFabricatorMenu extends BwrMenu {

    /** Output slot position, matching the well drawn in the panel texture. */
    private static final int OUTPUT_X = 116;
    private static final int OUTPUT_Y = 35;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    private static final int OUTPUT_SLOT_COUNT = 1;
    private static final int PLAYER_SLOT_COUNT = 36;

    /**
     * The prefix {@code FuelFabricatorBlockEntity.statusLines()} puts in front
     * of the would-be product name. See {@link #wouldFabricate}.
     */
    private static final String WOULD_FABRICATE_PREFIX = "Would fabricate: ";

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public double batchHeavyMetalKg;
    public double batchTargetKg;
    public double batchEnrichmentWeightFraction;
    public int energyStored;
    public int energyCapacity;
    public String wouldFabricate = "";
    public List<String> feedLines = new ArrayList<>();

    /** Client constructor. */
    public FuelFabricatorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public FuelFabricatorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.FUEL_FABRICATOR.get(), containerId, inventory, pos,
                BwrBlocks.FUEL_FABRICATOR.get());

        FuelFabricatorBlockEntity be = blockEntity(FuelFabricatorBlockEntity.class);
        IItemHandler output = be != null ? be.output() : new ItemStackHandler(1);
        addSlot(new SlotItemHandler(output, 0, OUTPUT_X, OUTPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false; // bundles appear here; nothing goes in
            }
        });
        addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
    }

    public static void open(ServerPlayer player, FuelFabricatorBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new FuelFabricatorMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.fuel_fabricator")),
                be.getBlockPos());
    }

    private FuelFabricatorBlockEntity fabricator() {
        return blockEntity(FuelFabricatorBlockEntity.class);
    }

    /** Batch completion, 0..1, for the progress bar. */
    public double progress() {
        return batchTargetKg > 0.0
                ? clampFraction(batchHeavyMetalKg / batchTargetKg)
                : 0.0;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        FuelFabricatorBlockEntity be = fabricator();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        buf.writeDouble(be.batchHeavyMetalKg());
        buf.writeDouble(FuelFabrication.batchHeavyMetalKg());
        buf.writeDouble(be.batchEnrichmentWeightFraction());
        buf.writeVarInt(be.energy().getEnergyStored());
        buf.writeVarInt(be.energy().getMaxEnergyStored());

        buf.writeUtf(wouldFabricate(be), 64);

        List<String> lines = be.feed().statusLines();
        int count = Math.min(lines.size(), 6);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(lines.get(i), 200);
        }
    }

    /**
     * What the current batch would come out as, taken from the block entity's
     * own readout rather than recomputed here.
     *
     * <p>This used to call {@code FuelFabrication.fabricate(hm, fissile, 0.0)}.
     * {@code fabricate} classifies a batch with
     * {@code familyFor(plutoniumKg / heavyMetalKg)}, so a hardcoded zero always
     * came back {@code Family.URANIUM} and the screen could only ever say
     * {@code leu} or {@code heu} — including for a batch that was half
     * plutonium and about to drop a MOX bundle out of the output slot. The
     * player sized a core loading off a screen that said beta 0.006502 and got
     * a bundle with beta 0.003500. The machine itself was always right;
     * {@code finishBundle()} and {@code statusLines()} both pass the real
     * {@code batchPlutoniumKg}, which is private with no accessor, so this menu
     * had no way to see it.
     *
     * <p>Going through {@code statusLines()} puts the GUI and the right-click
     * readout on one calculation instead of two that can drift. If a
     * {@code batchPlutoniumKg()} accessor — or better, a
     * {@code previewProduct()} — is ever added to the block entity, read it
     * directly and delete this. Until then a prefix that does not match yields
     * an empty string and the screen shows "-", which is honest; the old code
     * showed a confident wrong answer.
     */
    private static String wouldFabricate(FuelFabricatorBlockEntity be) {
        if (!(be.batchHeavyMetalKg() > 0.0)) {
            return "";
        }
        for (String line : be.statusLines()) {
            if (line.startsWith(WOULD_FABRICATE_PREFIX)) {
                return line.substring(WOULD_FABRICATE_PREFIX.length());
            }
        }
        return "";
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        batchHeavyMetalKg = buf.readDouble();
        batchTargetKg = buf.readDouble();
        batchEnrichmentWeightFraction = buf.readDouble();
        energyStored = buf.readVarInt();
        energyCapacity = buf.readVarInt();
        wouldFabricate = buf.readUtf(64);

        int count = buf.readVarInt();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf(200));
        }
        feedLines = lines;
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        // Nothing to command. The machine has no mode, no recipe selection and
        // no start button: it runs when it has feed and power.
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        var slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < OUTPUT_SLOT_COUNT) {
            if (!moveItemStackTo(stack, OUTPUT_SLOT_COUNT,
                    OUTPUT_SLOT_COUNT + PLAYER_SLOT_COUNT, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else {
            // Nothing may be shift-clicked into the output slot, so a stack in
            // the player's inventory only moves between hotbar and main.
            boolean fromHotbar = index >= OUTPUT_SLOT_COUNT + 27;
            boolean moved = fromHotbar
                    ? moveItemStackTo(stack, OUTPUT_SLOT_COUNT, OUTPUT_SLOT_COUNT + 27, false)
                    : moveItemStackTo(stack, OUTPUT_SLOT_COUNT + 27,
                            OUTPUT_SLOT_COUNT + PLAYER_SLOT_COUNT, false);
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
