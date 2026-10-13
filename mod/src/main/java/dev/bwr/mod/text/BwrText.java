package dev.bwr.mod.text;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;
import java.util.Locale;

/** Player messages retain keys and arguments until the receiving client chooses its language. */
public final class BwrText {
    private BwrText() { }

    /** English fallback also makes dedicated-server diagnostics useful without a client language pack. */
    public static MutableComponent tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    /** Stable English compatibility view for existing diagnostics and computer APIs. */
    public static List<String> english(List<Component> lines) {
        return lines.stream().map(BwrText::english).toList();
    }

    public static String english(Component component) {
        String text;
        if (component.getContents() instanceof TranslatableContents tr && tr.getFallback() != null) {
            Object[] args = tr.getArgs().clone();
            for (int i = 0; i < args.length; i++) if (args[i] instanceof Component child) args[i] = english(child);
            text = String.format(Locale.ROOT, tr.getFallback(), args);
        } else {
            text = component.plainCopy().getString();
        }
        StringBuilder result = new StringBuilder(text);
        for (Component sibling : component.getSiblings()) result.append(english(sibling));
        return result.toString();
    }
}
