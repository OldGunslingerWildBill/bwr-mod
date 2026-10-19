package dev.bwr.mod.furniture;

import net.minecraft.util.StringRepresentable;

/** The two halves of a relay cabinet, placed and broken together like a door. */
public enum FurnitureHalf implements StringRepresentable {
    LOWER("lower"), UPPER("upper");

    private final String serialized;

    FurnitureHalf(String serialized) {
        this.serialized = serialized;
    }

    @Override
    public String getSerializedName() {
        return serialized;
    }
}
