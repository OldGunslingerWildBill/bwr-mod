package dev.bwr.mod.gui.net;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** One bounded command and one subsequent snapshot for a selected set of core positions. */
public record RefuellingBatchPayload(int containerId, boolean load, int[] slots) implements CustomPacketPayload {
    public static final int MAX_SLOTS = 1764;

    public RefuellingBatchPayload {
        if (slots.length > MAX_SLOTS) throw new IllegalArgumentException("Too many refuelling positions");
        slots = slots.clone();
    }

    public static final Type<RefuellingBatchPayload> TYPE = new Type<>(BwrMod.id("refuelling_batch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RefuellingBatchPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.containerId());
                buf.writeBoolean(p.load());
                buf.writeVarIntArray(p.slots());
            },
            buf -> new RefuellingBatchPayload(buf.readVarInt(), buf.readBoolean(), buf.readVarIntArray(MAX_SLOTS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
