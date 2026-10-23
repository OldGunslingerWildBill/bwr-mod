package dev.bwr.mod.guide.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.guide.GuideBook;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Opens the guide: loads the book in the player's language through the resource manager and shows the screen. */
public final class GuideClient {
    private GuideClient() {
    }

    /** The book's files from the client's resource manager (resource packs may replace or add a language). */
    public static GuideBook.Source source(ResourceManager manager) {
        return (lang, file) -> {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "guide/" + lang + "/" + file);
            Optional<Resource> resource = manager.getResource(id);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().open()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        };
    }

    /** The book in the language the game is set to, or a one-page book that says why it could not be loaded. */
    public static GuideBook.Book loadBook() {
        Minecraft mc = Minecraft.getInstance();
        String lang = mc.getLanguageManager().getSelected();
        try {
            return GuideBook.load(source(mc.getResourceManager()), lang);
        } catch (GuideBook.GuideException e) {
            GuideBook.Page page = new GuideBook.Page("The guide could not be loaded", e.getMessage(), List.of(), null);
            return new GuideBook.Book(lang, List.of(new GuideBook.Chapter("error", "Guide error", List.of("GUIDE.md"), List.of(page))));
        }
    }

    /** Opens at the page the reader stopped at, or at the chapter list the first time. */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        GuideBook.Book book = loadBook();
        GuideProgress.Place place = GuideProgress.load(book);
        int chapter = place == null ? -1 : book.indexOf(place.chapter());
        mc.setScreen(new GuideScreen(book, mc.player.getInventory(), chapter, place == null ? 0 : place.page()));
    }
}
