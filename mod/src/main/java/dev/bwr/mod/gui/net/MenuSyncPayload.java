package dev.bwr.mod.gui.net;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One server-to-client snapshot for whatever menu the receiving player has
 * open. The body is an opaque blob written and read by the menu class itself,
 * so a new screen needs no new packet type.
 *
 * <p>The blob is deliberately not a structured codec. The reactor panel sends a
 * few hundred lattice cells as packed bytes and shorts, and a codec that
 * described that shape would have to be rewritten every time a readout is
 * added. The menu owns both ends of the encoding, and the container id in the
 * envelope is what keeps a stale packet from being decoded against a different
 * screen.
 */
public record MenuSyncPayload(int containerId, byte[] data) implements CustomPacketPayload {

    /**
     * Hard cap on one snapshot. The largest real payload is the refuelling core
     * map at eight bytes per lattice position for a 21x21 vessel, about 3.5 kB.
     */
    public static final int MAX_BYTES = 64 * 1024;

    public static final CustomPacketPayload.Type<MenuSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(BwrMod.id("menu_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MenuSyncPayload::containerId,
                    ByteBufCodecs.byteArray(MAX_BYTES), MenuSyncPayload::data,
                    MenuSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
