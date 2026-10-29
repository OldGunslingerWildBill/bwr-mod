package dev.bwr.mod.fabrication;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * The tabs of the fabrication desk. A recipe names its category by {@link #getSerializedName key}; an unknown key fails the
 * recipe at load, so a typo cannot hide a part. The labels are language keys {@code gui.bwr.fabrication.category.<key>}.
 */
public enum FabricationCategory implements StringRepresentable {
    PANELS("panels"),
    FURNITURE("furniture"),
    SWITCHES("switches"),
    GAUGES("gauges"),
    LAMPS("lamps"),
    DISPLAYS("displays");

    public static final Codec<FabricationCategory> CODEC = StringRepresentable.fromEnum(FabricationCategory::values);
    public static final StreamCodec<RegistryFriendlyByteBuf, FabricationCategory> STREAM_CODEC =
            ByteBufCodecs.idMapper(i -> values()[i], FabricationCategory::ordinal).cast();

    private final String key;

    FabricationCategory(String key) {
        this.key = key;
    }

    @Override
    public String getSerializedName() {
        return key;
    }

    /** The language key of the tab label. */
    public String labelKey() {
        return "gui.bwr.fabrication.category." + key;
    }
}
