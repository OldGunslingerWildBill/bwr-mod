package dev.bwr.mod.furniture;

import net.minecraft.util.StringRepresentable;

/**
 * The two blocks of a supervisor desk. DRAWERS is the block that carries the drawer pedestal (the one placed first, on
 * the right as the operator sees the desk); KNEE is the open knee space with the end panel, one block further left.
 */
public enum DeskPart implements StringRepresentable {
    DRAWERS("drawers"), KNEE("knee");

    private final String serialized;

    DeskPart(String serialized) {
        this.serialized = serialized;
    }

    @Override
    public String getSerializedName() {
        return serialized;
    }
}
