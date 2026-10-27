package dev.bwr.mod.rumble;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What one player feels right now, from the server: the combined intensity of the ground-borne sources ({@code pool}: pool
 * condensation and impacts) and of the machine sources ({@code turbine}), and the unit direction and the distance, in blocks, to
 * the strongest source. 28 bytes. Zero in both means nothing is felt. The client smooths between packets, so they need not be
 * frequent: the server sends at most four a second per player, and only when something changed.
 */
public record RumblePayload(float pool, float turbine, float dx, float dy, float dz, float distance) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RumblePayload> TYPE = new CustomPacketPayload.Type<>(BwrMod.id("rumble"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RumblePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, RumblePayload::pool,
            ByteBufCodecs.FLOAT, RumblePayload::turbine,
            ByteBufCodecs.FLOAT, RumblePayload::dx,
            ByteBufCodecs.FLOAT, RumblePayload::dy,
            ByteBufCodecs.FLOAT, RumblePayload::dz,
            ByteBufCodecs.FLOAT, RumblePayload::distance,
            RumblePayload::new);

    public static final RumblePayload NONE = new RumblePayload(0, 0, 0, 0, 0, 0);

    public boolean isNone() { return pool <= 0 && turbine <= 0; }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
