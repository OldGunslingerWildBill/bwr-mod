package dev.bwr.mod.registry;

import com.mojang.serialization.Codec;
import dev.bwr.mod.BwrMod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Data attachments. A machine's name (its equipment tag, such as RHR-1) and its name template live on the block
 * entity that {@code DeviceResolver} returns for the machine, as attachments, so one helper serves every device block
 * entity and none of them needs its own save code. Both are saved only when they differ from the default, so a
 * machine that was never named saves exactly as before and an old save loads with no name.
 */
public final class BwrAttachments {
    private BwrAttachments() {
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, BwrMod.MOD_ID);

    /** The default of {@link #MACHINE_TEMPLATE}: just the machine name. */
    public static final String DEFAULT_TEMPLATE = "{name}";

    public static final Supplier<AttachmentType<String>> MACHINE_NAME = ATTACHMENTS.register("machine_name",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING, name -> !name.isEmpty()).build());

    /** The CC port of a machine (0 to 9999), saved only when set; -1 is "no port". */
    public static final Supplier<AttachmentType<Integer>> MACHINE_PORT = ATTACHMENTS.register("machine_port",
            () -> AttachmentType.builder(() -> -1).serialize(Codec.INT, port -> port >= 0).build());

    public static final Supplier<AttachmentType<String>> MACHINE_TEMPLATE = ATTACHMENTS.register("machine_template",
            () -> AttachmentType.builder(() -> DEFAULT_TEMPLATE)
                    .serialize(Codec.STRING, template -> !DEFAULT_TEMPLATE.equals(template)).build());
}
