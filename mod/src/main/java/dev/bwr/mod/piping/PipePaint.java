package dev.bwr.mod.piping;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;

/** Cosmetic marking only: a paint change never alters the pipe's process service. */
public enum PipePaint implements StringRepresentable {
    NONE(null), WHITE(DyeColor.WHITE), ORANGE(DyeColor.ORANGE), MAGENTA(DyeColor.MAGENTA),
    LIGHT_BLUE(DyeColor.LIGHT_BLUE), YELLOW(DyeColor.YELLOW), LIME(DyeColor.LIME),
    PINK(DyeColor.PINK), GRAY(DyeColor.GRAY), LIGHT_GRAY(DyeColor.LIGHT_GRAY), CYAN(DyeColor.CYAN),
    PURPLE(DyeColor.PURPLE), BLUE(DyeColor.BLUE), BROWN(DyeColor.BROWN), GREEN(DyeColor.GREEN),
    RED(DyeColor.RED), BLACK(DyeColor.BLACK);

    private final DyeColor dye;
    PipePaint(DyeColor dye) { this.dye = dye; }
    @Override public String getSerializedName() { return dye == null ? "none" : dye.getName(); }
    public int color(int serviceDefault) { return dye == null ? serviceDefault : dye.getTextureDiffuseColor(); }
    public static PipePaint of(DyeColor dye) { return valueOf(dye.name()); }
}
