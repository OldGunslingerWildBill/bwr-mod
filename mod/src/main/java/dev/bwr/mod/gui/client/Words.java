package dev.bwr.mod.gui.client;

import java.util.Map;

/**
 * Plain-sentence versions of the fixed status phrases the server sends to a screen ("Water discharge connected"). A phrase
 * this class does not know is shown as the server wrote it.
 */
final class Words {
    private Words() {
    }

    private static final Map<String, String> SENTENCES = Map.ofEntries(
            Map.entry("Waiting for pump", "Waiting for the pump to report"),
            Map.entry("Waiting for server", "Waiting for the server to report"),
            Map.entry("Water discharge connected", "The discharge is connected to the reactor"),
            Map.entry("Water discharge disconnected", "The discharge is not connected to the reactor"),
            Map.entry("Pool connected", "The pump is connected to the suppression pool"),
            Map.entry("Pool disconnected", "The pump is not connected to the suppression pool"),
            Map.entry("Recirculation loop connected", "The recirculation loop is connected"),
            Map.entry("Recirculation loop disconnected", "The recirculation loop is not connected"),
            Map.entry("Mounted inside reactor vessel", "Mounted inside the reactor vessel"),
            Map.entry("RIP mount invalid / incomplete", "The internal pump mount is invalid or incomplete"),
            Map.entry("Awaiting pipe survey", "Checking the pipe connections"),
            Map.entry("Assembly incomplete or a required chunk is unloaded",
                    "The assembly is incomplete, or part of it is in an unloaded chunk"));

    /** The phrase as a sentence. */
    static String sentence(String serverText) {
        return SENTENCES.getOrDefault(serverText, serverText);
    }
}
