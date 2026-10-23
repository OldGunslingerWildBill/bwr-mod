package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.guide.GuideBook;
import dev.bwr.mod.guide.GuideGift;
import dev.bwr.mod.registry.BwrAttachments;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The in-game guide: the book crafts, the first-join gift is given once (and not again after a relog, a lost book or a
 * death) and not at all with the option off, and the guide's JSON loads, stays within the limits the screen is laid out
 * for and names only registered items and existing images. {@code tools-audit-guide.py} checks the same files from the
 * source tree and adds what a jar cannot see (the repository documents each chapter ends with).
 *
 * <p>Nothing here starts a fixture: the tests act on mock players and read resources from the class path.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GuideBookTests {
    private GuideBookTests() {
    }

    /**
     * A new player object that has not joined anything. (The game test helper's mock player is placed through the player
     * list, which fires the login and sends sync payloads to a connection that cannot take them.)
     */
    private static ServerPlayer newPlayer(GameTestHelper h) {
        return new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "guide-test"));
    }

    private static boolean joins(ServerPlayer player) {
        int before = books(player);
        GuideGift.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
        return books(player) > before;
    }

    private static int books(ServerPlayer player) {
        int n = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(BwrItems.GUIDE_BOOK.get())) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static boolean saved(ServerPlayer player) {
        return player.saveWithoutId(new CompoundTag()).toString().contains("guide_given");
    }

    // ---------------------------------------------------------------
    // Crafting
    // ---------------------------------------------------------------

    @GameTest(template = "empty")
    public static void theGuideBookCraftsFromABookAndANameplate(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();
        ItemStack book = new ItemStack(Items.BOOK);
        ItemStack plate = new ItemStack(BwrItems.NAMEPLATE.get());
        for (CraftingInput input : List.of(CraftingInput.of(2, 1, List.of(book, plate)), CraftingInput.of(2, 1, List.of(plate, book)),
                CraftingInput.of(3, 3, List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, book, ItemStack.EMPTY,
                        ItemStack.EMPTY, ItemStack.EMPTY, plate)))) {
            Optional<RecipeHolder<CraftingRecipe>> found = manager.getRecipeFor(RecipeType.CRAFTING, input, level);
            h.assertTrue(found.isPresent(), "a book and a nameplate craft nothing");
            h.assertTrue(found.get().id().equals(ResourceLocation.fromNamespaceAndPath("bwr", "guide_book")),
                    "a book and a nameplate craft " + found.get().id() + " instead of the guide book");
            ItemStack made = found.get().value().assemble(input, level.registryAccess());
            h.assertTrue(made.is(BwrItems.GUIDE_BOOK.get()) && made.getCount() == 1, "the recipe yields " + made);
        }
        h.assertTrue(manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(1, 1, List.of(book)), level)
                .map(r -> !r.id().equals(ResourceLocation.fromNamespaceAndPath("bwr", "guide_book"))).orElse(true),
                "a book alone must not craft the guide");
        h.succeed();
    }

    // ---------------------------------------------------------------
    // The first-join gift
    // ---------------------------------------------------------------

    @GameTest(template = "empty")
    public static void theFirstJoinGiftIsGivenOnceAndNotAgainAfterARelog(GameTestHelper h) {
        boolean was = GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.get();
        try {
            GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(true);
            ServerPlayer first = newPlayer(h);
            h.assertTrue(!saved(first), "a player who was never given the guide already saves the mark");
            h.assertTrue(joins(first), "the first join gave nothing");
            h.assertTrue(books(first) == 1, "the first join gave " + books(first) + " books, expected 1");
            h.assertTrue(saved(first), "the mark was not saved with the player");

            // joining again in the same session
            h.assertTrue(!joins(first), "a second join gave another book");
            h.assertTrue(books(first) == 1, "a second join left " + books(first) + " books");

            // a relog: the player is written out and read back into a new object
            CompoundTag file = first.saveWithoutId(new CompoundTag());
            ServerPlayer again = newPlayer(h);
            again.load(file);
            h.assertTrue(books(again) == 1, "the saved player did not keep the book (" + books(again) + ")");
            h.assertTrue(!joins(again), "a relog gave another book");
            h.assertTrue(books(again) == 1, "a relog left " + books(again) + " books");

            // a lost book is not replaced for free, and neither after a death
            again.getInventory().clearContent();
            h.assertTrue(!joins(again), "a player who lost the book was given another");
            h.assertTrue(books(again) == 0, "a player who lost the book has " + books(again));

            // a different player still gets theirs
            ServerPlayer other = newPlayer(h);
            h.assertTrue(joins(other) && books(other) == 1, "another player was not given a book");
            h.succeed();
        } finally {
            GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(was);
        }
    }

    @GameTest(template = "empty")
    public static void withTheOptionOffNobodyIsGivenTheGuideUntilItIsTurnedOn(GameTestHelper h) {
        boolean was = GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.get();
        try {
            h.assertTrue(GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.getDefault(), "giveGuideOnFirstJoin must default to true");
            GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(false);
            ServerPlayer player = newPlayer(h);
            h.assertTrue(!joins(player), "the option is off and a book was given");
            h.assertTrue(books(player) == 0, "the option is off and the player holds " + books(player));
            h.assertTrue(!player.hasData(BwrAttachments.GUIDE_GIVEN.get()) && !saved(player),
                    "the option is off and the player was marked as given");

            // turned on again, the player who joined while it was off gets the book, once
            GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(true);
            h.assertTrue(joins(player) && books(player) == 1, "turning the option on gave nothing");
            h.assertTrue(!joins(player) && books(player) == 1, "the second join after turning it on gave another");
            h.succeed();
        } finally {
            GameplayConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(was);
        }
    }

    // ---------------------------------------------------------------
    // The guide's data
    // ---------------------------------------------------------------

    /** The guide files from the class path (the mod's own resources), the way the Python audit reads them from the tree. */
    private static GuideBook.Source classPath() {
        return (lang, file) -> {
            try (InputStream in = GuideBookTests.class.getResourceAsStream("/assets/bwr/guide/" + lang + "/" + file)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw e;
            }
        };
    }

    @GameTest(template = "empty")
    public static void everyGuideChapterAndPageLoadsAndNamesOnlyRealItemsAndImages(GameTestHelper h) {
        GuideBook.Book book = GuideBook.load(classPath(), GuideBook.DEFAULT_LANG);
        h.assertTrue(book.chapters().size() >= 8, "the guide has only " + book.chapters().size() + " chapters");
        h.assertTrue(GuideBook.problems(book).isEmpty(), "the guide is outside the screen's limits: " + GuideBook.problems(book));
        List<String> faults = new ArrayList<>();
        int pages = 0;
        for (GuideBook.Chapter chapter : book.chapters()) {
            if (chapter.title().isBlank() || chapter.pages().isEmpty() || chapter.docs().isEmpty()) {
                faults.add(chapter.id() + " has no title, pages or document");
            }
            for (GuideBook.Page page : chapter.pages()) {
                pages++;
                for (String icon : page.icons()) {
                    ResourceLocation id = ResourceLocation.tryParse(icon);
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                        faults.add(chapter.id() + " / " + page.title() + ": icon " + icon + " is not a registered item");
                    }
                }
                if (page.image() != null) {
                    ResourceLocation texture = ResourceLocation.tryParse(page.image().texture());
                    String path = texture == null ? null : "/assets/" + texture.getNamespace() + "/" + texture.getPath();
                    if (path == null || GuideBookTests.class.getResource(path) == null) {
                        faults.add(chapter.id() + " / " + page.title() + ": image " + page.image().texture() + " does not exist");
                    }
                }
            }
        }
        h.assertTrue(faults.isEmpty(), "guide faults: " + faults);
        h.assertTrue(pages == book.pageCount() && pages >= 40, "the guide has " + pages + " pages");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aBrokenGuideFileIsReportedByNameAndAMissingLanguageFallsBackToEnglish(GameTestHelper h) {
        GuideBook.Source broken = (lang, file) -> file.equals("index.json") ? "{\"chapters\": [\"x\"]}" : "{\"title\": \"T\", \"docs\": [\"README.md\"], \"pages\": [{\"title\": \"P\"}]}";
        boolean threw = false;
        try {
            GuideBook.load(broken, "en_us");
        } catch (GuideBook.GuideException e) {
            threw = e.getMessage().contains("x.json page 1") && e.getMessage().contains("text");
        }
        h.assertTrue(threw, "a page with no text was not reported with its file and page number");

        GuideBook.Book english = GuideBook.load(classPath(), "en_us");
        GuideBook.Book german = GuideBook.load(classPath(), "de_de");
        h.assertTrue(german.chapters().equals(english.chapters()), "a language with no guide files must show the English guide");

        GuideBook.Source arrayText = (lang, file) -> file.equals("index.json") ? "{\"chapters\": [\"x\"]}"
                : "{\"title\": \"T\", \"docs\": [\"README.md\"], \"pages\": [{\"title\": \"P\", \"text\": [\"one\", \"\", \"two\"]}]}";
        GuideBook.Book lines = GuideBook.load(arrayText, "en_us");
        h.assertTrue(lines.chapters().get(0).pages().get(0).text().equals("one\n\ntwo"), "an array of lines must join with newlines");
        h.succeed();
    }
}
