package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Patch encoding for small text-like content: the patch is the component data's
 * whole saved compound. Used by the nameplate and the segment/LCD displays,
 * whose content is a few dozen bytes; the graphic display has its own
 * region-based patch format.
 */
public final class ContentPatches {
    private ContentPatches() {
    }

    /** {@code tag} as patch bytes, or null if it would exceed {@link ComponentData#MAX_PATCH}. */
    public static byte[] encode(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.size() <= ComponentData.MAX_PATCH ? bytes.toByteArray() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The compound in {@code patch}, or null when it is malformed. */
    public static CompoundTag decode(byte[] patch) {
        if (patch == null || patch.length == 0 || patch.length > ComponentData.MAX_PATCH) {
            return null;
        }
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(patch)),
                    NbtAccounter.create(ComponentData.MAX_PATCH * 4L));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
