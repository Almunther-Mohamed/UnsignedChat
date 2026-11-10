package me.almunther.unsignedchat.bungee;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Method;
import java.util.logging.Level;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import me.almunther.unsignedchat.Settings;
import me.almunther.unsignedchat.Sink;
import me.almunther.unsignedchat.Stats;

/**
 * UnsignedChat for BungeeCord (and forks such as Waterfall). Makes cancelling chat and commands of 1.19.3+ clients
 * safe, and lets ChatEvent#setMessage work for them again.
 */
public final class UnsignedChatBungee extends Plugin implements Listener {

    /** First protocol version with session based chat signing (1.19.3). Older clients are left alone. */
    private static final int FIRST_SESSION_CHAT_PROTOCOL = 761;

    private final Stats stats = new Stats();
    private final Sink log = new Sink() {
        @Override
        public void info(String message) {
            getLogger().info(message);
        }

        @Override
        public void warn(String message) {
            getLogger().warning(message);
        }

        @Override
        public void debug(String message) {
            if (settings != null && settings.debug) {
                getLogger().info("[debug] " + message);
            }
        }
    };

    private Settings settings;
    private BungeeHooks hooks;
    private Method getCh;
    private Method getHandle;
    private boolean active;

    @Override
    public void onEnable() {
        settings = Settings.load(getDataFolder().toPath(), log);
        getProxy().getPluginManager().registerCommand(this, new Command("unsignedchat", "unsignedchat.admin", "uchat") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                sender.sendMessage("UnsignedChat (BungeeCord) " + status());
            }
        });
        if (!settings.enabled) {
            log.info("UnsignedChat is disabled in config.properties.");
            return;
        }
        try {
            hooks = BungeeHooks.resolve(getProxy().getClass().getClassLoader(), log);
            if (!hooks.available()) {
                return;
            }
            getProxy().getPluginManager().registerListener(this, this);
            active = true;
            log.info("UnsignedChat active: chat=" + settings.stripChat + ", commands=" + settings.stripCommands
                    + ", drop-chat-session=" + settings.dropChatSession
                    + ", apply-modified-messages=" + settings.applyModifiedMessages);
        } catch (Throwable t) {
            stats.error(t);
            getLogger().log(Level.WARNING, "UnsignedChat could not start and stays inactive", t);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPostLogin(PostLoginEvent event) {
        if (!active) {
            return;
        }
        ProxiedPlayer player = event.getPlayer();
        try {
            int version = player.getPendingConnection().getVersion();
            if (version < FIRST_SESSION_CHAT_PROTOCOL) {
                return;
            }
            Channel channel = channelOf(player);
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(BungeeUnsigner.NAME) != null) {
                return;
            }
            BungeeUnsigner handler = new BungeeUnsigner(player, player.getName(), version, hooks, settings, stats, log);
            if (pipeline.get("inbound-boss") != null) {
                pipeline.addBefore("inbound-boss", BungeeUnsigner.NAME, handler);
            } else {
                pipeline.addLast(BungeeUnsigner.NAME, handler);
            }
            stats.players.incrementAndGet();
            log.debug("Hooked " + player.getName() + " (protocol " + version + ")");
        } catch (Throwable t) {
            stats.error(t);
            getLogger().log(Level.WARNING, "UnsignedChat could not hook " + player.getName(), t);
        }
    }

    /**
     * Runs after all other plugins at the same priority have been registered before us; applies the final message
     * when another plugin changed it. BungeeCord itself ignores such changes for 1.19.1+ clients.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(ChatEvent event) {
        BungeeUnsigner.Pending pending = BungeeUnsigner.CURRENT.get();
        if (pending == null || event.isCancelled() || event.getSender() != pending.player) {
            return;
        }
        String now = event.getMessage();
        if (now == null || now.equals(pending.original)) {
            return;
        }
        if (now.startsWith("/") != pending.isCommand()) {
            log.debug("Ignored a change that turns a chat message into a command or the other way round");
            return;
        }
        try {
            pending.apply(now);
            stats.modified.incrementAndGet();
            log.debug("Applied modified message");
        } catch (Throwable t) {
            stats.error(t);
            log.debug("Could not apply modified message: " + t);
        }
    }

    private Channel channelOf(ProxiedPlayer player) throws ReflectiveOperationException {
        if (getCh == null) {
            getCh = player.getClass().getMethod("getCh");
        }
        Object wrapper = getCh.invoke(player);
        if (getHandle == null) {
            getHandle = wrapper.getClass().getMethod("getHandle");
        }
        return (Channel) getHandle.invoke(wrapper);
    }

    private String status() {
        if (settings == null || !settings.enabled) {
            return "is disabled.";
        }
        return (active ? "is active" : "is NOT active (see console)") + " | players hooked: " + stats.players
                + " | chat unsigned: " + stats.chat + " | commands unsigned: " + stats.commands
                + " | chat sessions dropped: " + stats.sessions + " | messages modified: " + stats.modified
                + " | errors: " + stats.errors + " (last: " + stats.lastError + ")";
    }
}
