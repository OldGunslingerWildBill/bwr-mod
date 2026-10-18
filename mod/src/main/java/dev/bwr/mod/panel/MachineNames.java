package dev.bwr.mod.panel;

import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.registry.BwrAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Machine names (equipment tags such as RHR-1) and the caption template.
 *
 * <p>A name is up to {@link #MAX_NAME} characters of clean text, kept on the block entity that
 * {@code DeviceResolver} returns for the machine (an attachment, saved only when it is not empty), so every cell of a
 * multiblock shares one name. A machine with no name shows its block name and position instead
 * ({@link #display}). The template says how a name becomes the caption of a component that is line-linked to the
 * machine: {@code {name}} is the machine name, {@code {signal}} the label of the bound signal and {@code {unit}} its
 * unit; the default is {@code {name}}.
 *
 * <p>Nothing here changes anything by itself: a name is typed in, and a caption is set only by the player's click on
 * "Name linked components" or "Use machine name".
 */
public final class MachineNames {
    private MachineNames() {
    }

    public static final int MAX_NAME = 24;
    public static final int MAX_TEMPLATE = 40;
    /** Longest caption a component carries. */
    public static final int MAX_CAPTION = 32;

    /** The machine's name, or "" when it has none (an old save, or never named). */
    public static String name(BlockEntity owner) {
        return owner != null && owner.hasData(BwrAttachments.MACHINE_NAME.get())
                ? owner.getData(BwrAttachments.MACHINE_NAME.get()) : "";
    }

    /** The caption template of the machine; {@code {name}} unless set. */
    public static String template(BlockEntity owner) {
        return owner != null && owner.hasData(BwrAttachments.MACHINE_TEMPLATE.get())
                ? owner.getData(BwrAttachments.MACHINE_TEMPLATE.get()) : BwrAttachments.DEFAULT_TEMPLATE;
    }

    /** What a name is cleaned to: printable text, at most {@link #MAX_NAME} characters, no blanks at either end. */
    public static String cleanName(String raw) {
        return ConfigSchema.cleanText(raw == null ? "" : raw, MAX_NAME).strip();
    }

    /** Sets the name (cleaned); an empty name clears it. Returns the stored name. */
    public static String setName(BlockEntity owner, String raw) {
        String name = cleanName(raw);
        if (!name.equals(name(owner))) {
            owner.setData(BwrAttachments.MACHINE_NAME.get(), name);
            owner.setChanged();
        }
        return name;
    }

    /** Sets the caption template (cleaned); an empty one restores the default. Returns the stored template. */
    public static String setTemplate(BlockEntity owner, String raw) {
        String template = ConfigSchema.cleanText(raw == null ? "" : raw, MAX_TEMPLATE).strip();
        if (template.isEmpty()) {
            template = BwrAttachments.DEFAULT_TEMPLATE;
        }
        if (!template.equals(template(owner))) {
            owner.setData(BwrAttachments.MACHINE_TEMPLATE.get(), template);
            owner.setChanged();
        }
        return template;
    }

    /**
     * The caption {@code owner}'s template gives for a component bound to the signal labelled {@code signalLabel}
     * with unit {@code unit}: the placeholders filled in, runs of blanks squeezed, cut to {@link #MAX_CAPTION}. A
     * machine with no name gives an empty caption (there is nothing to push).
     */
    public static String caption(BlockEntity owner, String signalLabel, String unit) {
        String name = name(owner);
        if (name.isEmpty()) {
            return "";
        }
        String text = template(owner).replace("{name}", name).replace("{signal}", signalLabel == null ? "" : signalLabel)
                .replace("{unit}", unit == null ? "" : unit);
        return ConfigSchema.cleanText(text.replaceAll("\\s+", " ").strip(), MAX_CAPTION).strip();
    }

    /** What to call the machine at {@code pos}: its own name once it has one, else its block name and position. */
    public static Component display(Level level, BlockPos pos) {
        BlockEntity owner = dev.bwr.mod.signal.DeviceResolver.resolve(level, pos);
        String name = name(owner);
        if (!name.isEmpty()) {
            return Component.literal(name);
        }
        BlockPos at = owner == null ? pos : owner.getBlockPos();
        return Component.translatable("message.bwr.machine.fallback", level.getBlockState(at).getBlock().getName(),
                at.getX(), at.getY(), at.getZ());
    }
}
