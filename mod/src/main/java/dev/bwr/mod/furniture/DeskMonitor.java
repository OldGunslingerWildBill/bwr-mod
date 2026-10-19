package dev.bwr.mod.furniture;

import net.minecraft.util.StringRepresentable;

/** What stands on a desk: nothing, a flat monitor or a CRT. A bare hand cycles it. */
public enum DeskMonitor implements StringRepresentable {
    NONE("none"), FLAT("flat"), CRT("crt");

    private final String serialized;

    DeskMonitor(String serialized) {
        this.serialized = serialized;
    }

    @Override
    public String getSerializedName() {
        return serialized;
    }

    public DeskMonitor next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
