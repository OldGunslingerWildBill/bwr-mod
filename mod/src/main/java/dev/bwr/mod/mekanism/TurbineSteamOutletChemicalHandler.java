package dev.bwr.mod.mekanism;

import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Holder;

/**
 * The Mekanism face of a {@link TurbineSteamOutletBlockEntity}: a single,
 * output-only chemical tank full of steam.
 *
 * <p>This is the only class in the mod that turns our kilograms into Mekanism's
 * millibuckets, and it does so with the constant documented on
 * {@link TurbineSteamOutletBlockEntity#MILLIBUCKETS_PER_KILOGRAM}. The block
 * entity has already done the conversion by the time steam reaches this buffer,
 * so nothing here scales anything — it hands over exactly the millibuckets the
 * physics paid for, and mass is conserved across the boundary to the last one.
 *
 * <p>Extraction only. Steam offered <i>to</i> the outlet is refused: the reactor
 * is upstream of this point and there is no path back into a pressure vessel
 * through a turbine stop valve.
 *
 * <p>This class is therefore only half of the boundary, and for a long time it
 * was mistaken for all of it. Extraction is a path something else has to walk
 * down, and most Mekanism hardware never does: a turbine valve expects to be fed
 * and a newly placed tube only pulls on a face the player has configured to
 * pull. {@link TurbineSteamOutletPusher} is the other half, offering the same
 * buffer to the neighbours once a tick. Both drain through
 * {@link TurbineSteamOutletBlockEntity#drainMilliBuckets}, so a millibucket
 * given away by one is gone from the other.
 *
 * <p>This class references Mekanism types directly and must never be loaded
 * without Mekanism installed. {@link BwrMekanismSupport} is the guard.
 */
public class TurbineSteamOutletChemicalHandler implements IChemicalHandler {

    private final TurbineSteamOutletBlockEntity be;

    public TurbineSteamOutletChemicalHandler(TurbineSteamOutletBlockEntity be) {
        this.be = be;
    }

    @Override
    public int getChemicalTanks() {
        return 1;
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {
        if (tank != 0) {
            return ChemicalStack.EMPTY;
        }
        long mb = be.getBufferedMilliBuckets();
        Holder<Chemical> steam = MekanismSteam.steam();
        if (mb <= 0L || steam == null) {
            return ChemicalStack.EMPTY;
        }
        return new ChemicalStack(steam, mb);
    }

    /**
     * Ignored. The contents of this tank are produced by the reactor's physics
     * and consumed by whatever is piped to it; there is no meaning to setting
     * them from outside, and honouring it would let a machine conjure steam the
     * vessel never boiled.
     */
    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {
        // Intentionally empty.
    }

    @Override
    public long getChemicalTankCapacity(int tank) {
        return tank == 0 ? be.getBufferCapacityMilliBuckets() : 0L;
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {
        if (tank != 0 || stack.isEmpty()) {
            return false;
        }
        Holder<Chemical> steam = MekanismSteam.steam();
        return steam != null && stack.is(steam);
    }

    /** Output only — whatever was offered is handed straight back. */
    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
        return stack;
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {
        if (tank != 0 || amount <= 0L) {
            return ChemicalStack.EMPTY;
        }
        Holder<Chemical> steam = MekanismSteam.steam();
        if (steam == null) {
            return ChemicalStack.EMPTY;
        }
        long drained = be.drainMilliBuckets(amount, action.simulate());
        return drained <= 0L ? ChemicalStack.EMPTY : new ChemicalStack(steam, drained);
    }
}
