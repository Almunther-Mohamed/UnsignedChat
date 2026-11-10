package me.almunther.unsignedchat.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Method;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import me.almunther.unsignedchat.Settings;
import me.almunther.unsignedchat.Sink;
import me.almunther.unsignedchat.Stats;
import org.slf4j.Logger;

/**
 * UnsignedChat for Velocity. Lets proxy plugins cancel and modify chat and commands from 1.19.3+ clients
 * without {@code "A plugin tried to cancel a signed chat message"} disconnects.
 */
@Plugin(
        id = "unsignedchat",
        name = "UnsignedChat",
        version = "1.0.0",
        description = "Lets proxy plugins cancel and modify chat and commands of 1.19.3+ clients without kicks.",
        authors = {"UnsignedChat"})
public final class UnsignedChatVelocity {

    /** First protocol version with session based chat signing (1.19.3). Older clients are left alone. */
    private static final int FIRST_SESSION_CHAT_PROTOCOL = 761;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Stats stats = new Stats();
    private final Sink log;

    private Settings settings;
    private VelocityHooks hooks;
    private Method getConnection;
    private Method getChannel;
    private boolean active;

    @Inject
    public UnsignedChatVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.log = new Sink() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void debug(String message) {
                if (settings != null && settings.debug) {
                    logger.info("[debug] " + message);
                }
            }
        };
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        settings = Settings.load(dataDirectory, log);
        registerCommand();
        if (!settings.enabled) {
            logger.info("UnsignedChat is disabled in config.properties.");
            return;
        }
        try {
            ClassLoader proxyLoader = proxy.getClass().getClassLoader();
            hooks = VelocityHooks.resolve(proxyLoader, log);
            if (!hooks.chatAvailable() && !hooks.commandAvailable()) {
                logger.warn("None of Velocity's chat packets could be hooked, UnsignedChat stays inactive.");
                return;
            }
            active = true;
            logger.info("UnsignedChat active: chat={}, commands={}, drop-chat-session={}",
                    hooks.chatAvailable() && settings.stripChat,
                    hooks.commandAvailable() && settings.stripCommands,
                    settings.dropChatSession);
        } catch (Throwable t) {
            stats.error(t);
            logger.warn("UnsignedChat could not start and stays inactive: {}", t.toString());
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getPlayer();
        try {
            if (player.getProtocolVersion().getProtocol() < FIRST_SESSION_CHAT_PROTOCOL) {
                return;
            }
            Channel channel = channelOf(player);
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(VelocityUnsigner.NAME) != null) {
                return;
            }
            VelocityUnsigner handler = new VelocityUnsigner(player.getUsername(), hooks, settings, stats, log);
            if (pipeline.get("handler") != null) {
                pipeline.addBefore("handler", VelocityUnsigner.NAME, handler);
            } else {
                pipeline.addLast(VelocityUnsigner.NAME, handler);
            }
            stats.players.incrementAndGet();
            log.debug("Hooked " + player.getUsername() + " (protocol " + player.getProtocolVersion().getProtocol() + ")");
        } catch (Throwable t) {
            stats.error(t);
            logger.warn("UnsignedChat could not hook {}: {}", player.getUsername(), t.toString());
        }
    }

    private Channel channelOf(Player player) throws ReflectiveOperationException {
        if (getConnection == null) {
            getConnection = player.getClass().getMethod("getConnection");
        }
        Object connection = getConnection.invoke(player);
        if (getChannel == null) {
            getChannel = connection.getClass().getMethod("getChannel");
        }
        return (Channel) getChannel.invoke(connection);
    }

    private void registerCommand() {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("unsignedchat").aliases("uchat").plugin(this).build();
        proxy.getCommandManager().register(meta, new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                invocation.source().sendMessage(Component.text("UnsignedChat (Velocity) " + status()));
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                return invocation.source().hasPermission("unsignedchat.admin");
            }
        });
    }

    private String status() {
        if (settings == null || !settings.enabled) {
            return "is disabled.";
        }
        return (active ? "is active" : "is NOT active (see console)") + " | players hooked: " + stats.players
                + " | chat unsigned: " + stats.chat + " | commands unsigned: " + stats.commands
                + " | chat sessions dropped: " + stats.sessions + " | errors: " + stats.errors
                + " (last: " + stats.lastError + ")";
    }
}
