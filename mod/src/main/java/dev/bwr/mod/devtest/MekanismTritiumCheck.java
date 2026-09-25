package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrItems;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

/** Reached only after the optional-mod check; exercises an actual powered Mekanism oxidizer. */
final class MekanismTritiumCheck {
    static void run(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_oxidizer"));
        h.assertTrue(block != Blocks.AIR, "Missing actual Chemical Oxidizer");
        // Apply the same default components as item placement, including Mekanism's port configuration.
        level.setBlock(pos, block.defaultBlockState(), 3);
        level.getBlockEntity(pos).applyComponentsFromItemStack(new ItemStack(block));
        // Register per-tick work from the test body, not from a scheduled callback that mutates its own queue.
        connect(h, pos);
    }

    private static void connect(GameTestHelper h, net.minecraft.core.BlockPos pos) {
        var level = h.getLevel();
        var recipe = (mekanism.api.recipes.ItemStackToChemicalRecipe) level.getRecipeManager().byKey(
                ResourceLocation.fromNamespaceAndPath("bwr", "tritium_sample_oxidizing")).orElseThrow().value();
        h.assertTrue(recipe.test(new ItemStack(BwrItems.TRITIUM_SAMPLE.get())) && !recipe.isIncomplete(), "Loaded recipe does not match a tritium sample");
        var chemicalCap = BlockCapability.createSided(ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);
        IItemHandler items = null;
        IEnergyStorage energy = null;
        IChemicalHandler chemicals = null;
        int inputSlot = -1;
        for (Direction side : new Direction[]{null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
            var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (input != null && items == null) for (int i=0; i<input.getSlots(); i++) {
                if (input.insertItem(i, new ItemStack(BwrItems.TRITIUM_SAMPLE.get(), 2), true).isEmpty()) {
                    items = input; inputSlot = i; break;
                }
            }
            var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (power != null && power.canReceive()) energy = power;
            var output = level.getCapability(chemicalCap, pos, side);
            if (output != null && output.getChemicalTanks() > 0) chemicals = output;
        }
        h.assertTrue(items != null && energy != null && chemicals != null,
                "Oxidizer connections missing: input=" + (items != null) + ", FE=" + (energy != null) + ", chemical=" + (chemicals != null));
        final var input = items;
        final var power = energy;
        final var output = chemicals;
        final int slot = inputSlot;
        h.assertTrue(input.insertItem(slot, new ItemStack(BwrItems.TRITIUM_SAMPLE.get(), 2), false).isEmpty(), "Oxidizer rejected tritium samples");
        h.assertTrue(input.getStackInSlot(slot).getCount() == 2, "Input insert did not store two samples: " + input.getStackInSlot(slot));
        h.assertTrue(output.getChemicalTankCapacity(0) >= 10000, "Recipe exceeds machine capacity");
        int[] ticks = {0}, completed = {0};
        h.onEachTick(() -> {
            if (++ticks[0] <= 20) {
                h.assertTrue(output.getChemicalInTank(0).isEmpty() && input.getStackInSlot(slot).getCount() == 2,
                        "Unpowered state: input=" + input.getStackInSlot(slot) + ", output=" + output.getChemicalInTank(0) + ", FE=" + power.getEnergyStored());
                return;
            }
            power.receiveEnergy(Integer.MAX_VALUE, false);
            var stack = output.getChemicalInTank(0);
            if (stack.isEmpty()) return;
            h.assertTrue(stack.getAmount() == 10000, "Incorrect tritium yield");
            h.assertTrue(dev.bwr.mod.fuel.SpecialtyRodItem.TRITIUM_SAMPLES_PER_ROD * stack.getAmount() == 12_500_000L,
                    "Completed rod must produce exactly 12,500 buckets through the actual recipe");
            h.assertTrue(ResourceLocation.fromNamespaceAndPath("mekanismgenerators", "tritium").equals(MekanismAPI.CHEMICAL_REGISTRY.getKey(stack.getChemical())), "Produced a different chemical");
            h.assertTrue(input.getStackInSlot(slot).getCount() == 1-completed[0], "Sample consumption differs from output");
            h.assertTrue(output.extractChemical(0, 10000, Action.SIMULATE).getAmount() == 10000, "Cannot pipe tritium out");
            h.assertTrue(output.getChemicalInTank(0).getAmount() == 10000, "Simulated extraction consumed output");
            h.assertTrue(output.extractChemical(0, 10000, Action.EXECUTE).getAmount() == 10000, "Actual extraction lost tritium");
            if (++completed[0] == 2) {
                h.assertTrue(output.getChemicalInTank(0).isEmpty(), "Output duplicated after extraction");
                level.removeBlock(pos, false);
                h.succeed();
            }
        });
    }
}
