package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelTimers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;

/**
 * Common behaviour of the operable controls (spec 2.1 inputs): they are
 * interactive, a bare-handed right-click that reaches the server without the
 * client hook (the vanilla fallback) acts like a click at the centre of the
 * cell that was hit, and a timer-driven release lets go of momentary controls
 * ({@link #holdable()}) when nobody is holding them any more.
 *
 * <p>Latched state lives in the type's {@link ComponentData}; when an operation
 * changes it, call {@link #markSaved} so the chunk is written.
 */
public abstract class PanelInputType extends ComponentType implements PanelTimers.Timed {
    /** How long a vanilla-path click holds a momentary control. */
    public static final int PULSE_TICKS = 6;
    /** A held control lets go by itself this long after the last PRESS (the client repeats it while held). */
    public static final int HOLD_FAILSAFE_TICKS = 30;

    protected PanelInputType(ResourceLocation id) {
        super(id);
    }

    @Override
    public boolean isInteractive() {
        return true;
    }

    @Override
    public InteractionResult use(UseContext context) {
        OperateContext operate = new OperateContext(context.level(), context.player(), context.owner(), context.component());
        boolean handled = operate(operate, OperateAction.PRESS,
                clickValue(context.component(), context.cellX(), context.cellY()));
        if (handled && holdable()) {
            PanelTimers.schedule(context.level(), context.owner().getBlockPos(), context.component().id(), PULSE_TICKS);
        }
        return InteractionResult.CONSUME;
    }

    /** Lets go of a held control. */
    @Override
    public void timerFired(ServerLevel level, PanelBlockEntity owner, PanelComponent component) {
        operate(new OperateContext(level, null, owner, component), OperateAction.RELEASE, 0.0);
    }

    /** Arms the hold fail-safe; call on every PRESS of a {@link #holdable()} control. */
    protected static void armFailsafe(OperateContext context) {
        PanelTimers.schedule(context.level(), context.owner().getBlockPos(), context.component().id(), HOLD_FAILSAFE_TICKS);
    }

    protected static void disarm(OperateContext context) {
        PanelTimers.cancel(context.level(), context.owner().getBlockPos(), context.component().id());
    }

    /** Latched data changed: have the chunk saved (the value delta itself is sent by setOutput). */
    protected static void markSaved(OperateContext context) {
        context.owner().setChanged();
    }

    protected static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    protected static int clampInt(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    /** The sign of a click or scroll value as a step: -1, 0 or +1. */
    protected static int step(double value) {
        return value < 0 ? -1 : value > 0 ? 1 : 0;
    }
}
