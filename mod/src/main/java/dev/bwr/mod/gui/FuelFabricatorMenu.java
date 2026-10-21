package dev.bwr.mod.gui;

import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelFabrication;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
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
 * The fuel fabricator's screen: the four slots of the item route (uranium and
 * steel in, assembly and depleted uranium out), the selector that picks the
 * enrichment of the item route's assembly, how far along each route's current
 * bundle is, and what the chemical batch would come out as if it finished now.
 *
 * <p>The chemical route has no slot and no dial. Heavy metal arrives as Mekanism
 * chemicals through the {@code FuelFeed}, which is the whole point of SPEC 2.4 —
 * the player's existing enrichment plumbing is the enrichment control. Its
 * readout is a measurement of the pile, not a target: a player who wants HEU
 * feeds the machine more fissile material. The selector is only for the item
 * route, where ingots carry no enrichment of their own.
 *
 * <p>The target travels as a menu command carrying an index into
 * {@link FuelItemRoute#TARGETS}; the server checks the index and the block
 * entity checks the name, so a forged value changes nothing.
 */
public class FuelFabricatorMenu extends BwrMenu {

    /** Command: select the item route's assembly, argument a = index into {@link FuelItemRoute#TARGETS}. */
    public static final int CMD_TARGET = 1;

    /**
     * The four machine slots, in the order they were added to this menu (which
     * is not the order a pipe sees: {@link FabricatorItemView}).
     */
    public static final int SLOT_ASSEMBLY = 0;
    public static final int SLOT_TAILS = 1;
    public static final int SLOT_URANIUM = 2;
    public static final int SLOT_STEEL = 3;

    /** Slot positions, relative to the panel; the screen's captions are laid out to sit over them. */
    public static final int SLOT_Y = 100;
    public static final int URANIUM_X = 26;
    public static final int STEEL_X = 63;
    public static final int ASSEMBLY_X = 100;
    public static final int TAILS_X = 137;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 142;
    private static final int HOTBAR_Y = 200;

    private static final int OUTPUT_SLOT_COUNT = 4;
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

    /** Item route: the selected target (an index into {@link FuelItemRoute#TARGETS}), its cost, and its progress. */
    public int targetIndex = TARGET_DEFAULT_INDEX;
    public boolean hasCost;
    public int costIngots;
    public int costSteel;
    public int costTails;
    public int costWorkTicks;
    public int uraniumHeldUnits;
    public int workTicks;
    public int tailsHeld;

    private static final int TARGET_DEFAULT_INDEX = Math.max(0, FuelItemRoute.TARGETS.indexOf(FuelItemRoute.DEFAULT_TARGET));

    /** Client constructor. */
    public FuelFabricatorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public FuelFabricatorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.FUEL_FABRICATOR.get(), containerId, inventory, pos,
                BwrBlocks.FUEL_FABRICATOR.get());

        FuelFabricatorBlockEntity be = blockEntity(FuelFabricatorBlockEntity.class);
        IItemHandler output = be != null ? be.output() : new ItemStackHandler(1);
        IItemHandler tails = be != null ? be.tailsOut() : new ItemStackHandler(1);
        IItemHandler uranium = be != null ? be.uraniumIn() : new ItemStackHandler(1);
        IItemHandler steel = be != null ? be.steelIn() : new ItemStackHandler(1);
        addSlot(new SlotItemHandler(output, 0, ASSEMBLY_X, SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false; // bundles appear here; nothing goes in
            }
        });
        addSlot(new SlotItemHandler(tails, 0, TAILS_X, SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false; // depleted uranium appears here; nothing goes in
            }
        });
        // The input slots take only what the machine would use: a uranium ingot, ore or cake, and steel.
        addSlot(new SlotItemHandler(uranium, 0, URANIUM_X, SLOT_Y));
        addSlot(new SlotItemHandler(steel, 0, STEEL_X, SLOT_Y));
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

        buf.writeVarInt(Math.max(0, FuelItemRoute.TARGETS.indexOf(be.target())));
        FuelItemRoute.Cost cost = be.itemCost();
        buf.writeBoolean(cost != null);
        if (cost != null) {
            buf.writeVarInt(cost.ingots());
            buf.writeVarInt(cost.steel());
            buf.writeVarInt(cost.tails());
            buf.writeVarInt(cost.workTicks());
        }
        buf.writeVarInt(be.uraniumHeldUnits());
        buf.writeVarInt(be.itemWorkTicks());
        buf.writeVarInt(be.tailsHeld());

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

        targetIndex = Math.max(0, Math.min(FuelItemRoute.TARGETS.size() - 1, buf.readVarInt()));
        hasCost = buf.readBoolean();
        if (hasCost) {
            costIngots = buf.readVarInt();
            costSteel = buf.readVarInt();
            costTails = buf.readVarInt();
            costWorkTicks = buf.readVarInt();
        } else {
            costIngots = 0;
            costSteel = 0;
            costTails = 0;
            costWorkTicks = 0;
        }
        uraniumHeldUnits = buf.readVarInt();
        workTicks = buf.readVarInt();
        tailsHeld = buf.readVarInt();

        int count = buf.readVarInt();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf(200));
        }
        feedLines = lines;
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        // One command: pick the item route's assembly. There is no mode and no
        // start button; the machine runs when it has feed and power.
        if (command != CMD_TARGET || a < 0 || a >= FuelItemRoute.TARGETS.size()) {
            return;
        }
        FuelFabricatorBlockEntity be = fabricator();
        if (be != null && be.setTarget(FuelItemRoute.TARGETS.get(a))) {
            markSnapshotDirty();
        }
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
            // Nothing may be shift-clicked into the output slots. Uranium and
            // steel go to their own input slot; anything else only moves
            // between hotbar and main.
            boolean moved;
            if (FuelItemRoute.uraniumUnits(stack) > 0) {
                moved = moveItemStackTo(stack, SLOT_URANIUM, SLOT_URANIUM + 1, false);
            } else if (FuelItemRoute.isSteel(stack)) {
                moved = moveItemStackTo(stack, SLOT_STEEL, SLOT_STEEL + 1, false);
            } else {
                boolean fromHotbar = index >= OUTPUT_SLOT_COUNT + 27;
                moved = fromHotbar
                        ? moveItemStackTo(stack, OUTPUT_SLOT_COUNT, OUTPUT_SLOT_COUNT + 27, false)
                        : moveItemStackTo(stack, OUTPUT_SLOT_COUNT + 27,
                                OUTPUT_SLOT_COUNT + PLAYER_SLOT_COUNT, false);
            }
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
