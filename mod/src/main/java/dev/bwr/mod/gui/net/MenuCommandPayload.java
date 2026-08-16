package dev.bwr.mod.gui.net;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One client-to-server actuator command from an open menu: move this rod,
 * command that pump to 60%, take this bundle out of position 41.
 *
 * <p>Every command in this mod is an <b>actuator</b>. There is no command that
 * says "do the right thing" — a scram command scrams unconditionally and
 * immediately, a speed command sets a speed, and nothing on the server side
 * examines plant conditions to decide whether the request was wise. That is the
 * whole design rule, and the GUI is not permitted to be the place it leaks in.
 *
 * @param containerId which open menu this is for; a command for a container the
 *                    player no longer has open is dropped
 * @param command     menu-specific command id
 * @param a           first argument, meaning is command-specific
 * @param b           second argument, meaning is command-specific
 */
public record MenuCommandPayload(int containerId, int command, int a, int b)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MenuCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(BwrMod.id("menu_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuCommandPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MenuCommandPayload::containerId,
                    ByteBufCodecs.VAR_INT, MenuCommandPayload::command,
                    ByteBufCodecs.VAR_INT, MenuCommandPayload::a,
                    ByteBufCodecs.VAR_INT, MenuCommandPayload::b,
                    MenuCommandPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
