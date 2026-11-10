package me.almunther.unsignedchat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads plugins/UnsignedChat/config.properties (created with defaults on first start). */
public final class Settings {

    private static final String DEFAULTS = String.join("\n",
            "# UnsignedChat configuration",
            "#",
            "# Master switch. Set to false to turn the whole fix off (players are then left untouched).",
            "enabled=true",
            "#",
            "# Remove the signature from chat messages sent by 1.19.3+ clients. This is what lets proxy",
            "# plugins cancel or modify chat without the player being kicked / their chat breaking.",
            "strip-chat=true",
            "#",
            "# Same for commands that carry signed arguments (/msg, /say, /me, ...).",
            "strip-commands=true",
            "#",
            "# Do not forward the client's chat-session key to backend servers. Needed so that backends",
            "# accept the now-unsigned messages. Requires enforce-secure-profile=false on 1.19.3+ backends.",
            "drop-chat-session=true",
            "#",
            "# BungeeCord only: BungeeCord ignores ChatEvent#setMessage for 1.19.1+ clients. When true,",
            "# UnsignedChat applies the final message after all plugins have run, like on older versions.",
            "apply-modified-messages=true",
            "#",
            "# Print extra information to the console.",
            "debug=false",
            "");

    public final boolean enabled;
    public final boolean stripChat;
    public final boolean stripCommands;
    public final boolean dropChatSession;
    public final boolean applyModifiedMessages;
    public final boolean debug;

    private Settings(Properties p) {
        this.enabled = bool(p, "enabled", true);
        this.stripChat = bool(p, "strip-chat", true);
        this.stripCommands = bool(p, "strip-commands", true);
        this.dropChatSession = bool(p, "drop-chat-session", true);
        this.applyModifiedMessages = bool(p, "apply-modified-messages", true);
        this.debug = bool(p, "debug", false);
    }

    public static Settings load(Path dir, Sink log) {
        Properties p = new Properties();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("config.properties");
            if (!Files.exists(file)) {
                Files.write(file, DEFAULTS.getBytes(StandardCharsets.UTF_8));
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read config.properties, using defaults: " + e);
        }
        return new Settings(p);
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        return def;
    }
}
