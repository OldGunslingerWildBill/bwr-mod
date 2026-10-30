package dev.bwr.mod.power;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One turbine blast heard by one player (TURBINE-MAINTENANCE.md, "Sounds"): which blast ({@link BlastSounds.Kind#ordinal()}), where it
 * happened, the final gain of each of its three variants (clear, distant, muffled; the server worked them out from the distance and the
 * walls between), how many client ticks to wait before playing it (the sound's travel time) and the pitch. About 50 bytes, server to
 * client, sent once per player per blast.
 */
public record BlastSoundPayload(int kind, double x, double y, double z, float clear, float distant, float muffled, int delayTicks, float pitch)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlastSoundPayload> TYPE = new CustomPacketPayload.Type<>(BwrMod.id("turbine_blast_sound"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlastSoundPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.clear);
                buf.writeFloat(p.distant);
                buf.writeFloat(p.muffled);
                buf.writeVarInt(p.delayTicks);
                buf.writeFloat(p.pitch);
            },
            buf -> new BlastSoundPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readFloat()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
