package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bwr.mod.BwrMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Dynamic textures behind the LCD and graphic displays. A texture belongs to
 * the display's data object and is made the first time it is drawn; it is
 * released when it has not been drawn for {@link #IDLE_TICKS} ticks (the panel
 * went out of range or was removed), or when the world is left. Texture names
 * are pooled so a release does not leave a render type behind for every texture
 * ever made.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class DisplayTextures {
    private DisplayTextures() {
    }

    private static final int IDLE_TICKS = 200;
    private static final int SWEEP_INTERVAL = 100;

    /** One live texture. {@code version} is the owner's content version last painted; {@code -1} = never painted. */
    static final class Entry {
        final ResourceLocation id;
        final DynamicTexture texture;
        final RenderType type;
        final int width;
        final int height;
        int version = -1;
        private long lastUsed;

        private Entry(ResourceLocation id, DynamicTexture texture, RenderType type, int width, int height) {
            this.id = id;
            this.texture = texture;
            this.type = type;
            this.width = width;
            this.height = height;
        }

        NativeImage image() {
            return texture.getPixels();
        }
    }

    private static final Map<Object, Entry> ENTRIES = new IdentityHashMap<>();
    private static final ArrayDeque<ResourceLocation> FREE = new ArrayDeque<>();
    private static int nextId;
    private static long tick;

    /** The texture of {@code owner} at exactly {@code width x height} pixels, made or resized as needed. */
    static Entry acquire(Object owner, int width, int height) {
        Entry entry = ENTRIES.get(owner);
        if (entry != null && (entry.width != width || entry.height != height)) {
            ENTRIES.remove(owner);
            free(entry);
            entry = null;
        }
        if (entry == null) {
            ResourceLocation id = FREE.isEmpty() ? BwrMod.id("panel_display/" + nextId++) : FREE.pop();
            DynamicTexture texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, width, height, true));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            entry = new Entry(id, texture, RenderType.entityCutoutNoCull(id), width, height);
            ENTRIES.put(owner, entry);
        }
        entry.lastUsed = tick;
        return entry;
    }

    private static void free(Entry entry) {
        Minecraft.getInstance().getTextureManager().release(entry.id);
        FREE.push(entry.id);
    }

    /** Number of live textures (tests, diagnostics). */
    public static int liveCount() {
        return ENTRIES.size();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tick++;
        if (ENTRIES.isEmpty() || tick % SWEEP_INTERVAL != 0) {
            return;
        }
        boolean worldGone = Minecraft.getInstance().level == null;
        for (Iterator<Entry> it = ENTRIES.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (worldGone || tick - entry.lastUsed > IDLE_TICKS) {
                it.remove();
                free(entry);
            }
        }
    }

    /** Releases everything now (resource reload, tests). */
    public static void releaseAll() {
        for (Entry entry : new ArrayList<>(ENTRIES.values())) {
            free(entry);
        }
        ENTRIES.clear();
    }
}
