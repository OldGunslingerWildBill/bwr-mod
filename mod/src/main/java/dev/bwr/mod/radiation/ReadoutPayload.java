package dev.bwr.mod.radiation;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What the server tells the player holding a survey meter, four times a second: the dose rate at the player (mSv/h)
 * and the personal dose (mSv). Server to client only; the client draws the readout and clicks from it.
 */
public record ReadoutPayload(double doseRateMsvPerHour, double personalDoseMsv) implements CustomPacketPayload {
    public static final Type<ReadoutPayload> TYPE = new Type<>(BwrMod.id("radiation_readout"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReadoutPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, ReadoutPayload::doseRateMsvPerHour,
            ByteBufCodecs.DOUBLE, ReadoutPayload::personalDoseMsv,
            ReadoutPayload::new);

    @Override
    public Type<ReadoutPayload> type() {
        return TYPE;
    }
}
