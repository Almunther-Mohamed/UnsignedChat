package me.almunther.unsignedchat.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import me.almunther.unsignedchat.ChatSessionPacket;
import me.almunther.unsignedchat.Settings;
import me.almunther.unsignedchat.Sink;
import me.almunther.unsignedchat.Stats;

/**
 * Sits in a player's Netty pipeline right in front of Velocity's own packet handler. Packets Velocity has already
 * decoded are changed in place, so that by the time Velocity (and every plugin listening to PlayerChatEvent /
 * CommandExecuteEvent) sees them, they are plain unsigned messages that may be freely cancelled or modified.
 *
 * <p>Failure policy: never throw, never block. If anything goes wrong the packet continues untouched.
 */
final class VelocityUnsigner extends ChannelInboundHandlerAdapter {

    static final String NAME = "unsignedchat";

    private final String player;
    private final VelocityHooks hooks;
    private final Settings settings;
    private final Stats stats;
    private final Sink log;

    VelocityUnsigner(String player, VelocityHooks hooks, Settings settings, Stats stats, Sink log) {
        this.player = player;
        this.hooks = hooks;
        this.settings = settings;
        this.stats = stats;
        this.log = log;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof ByteBuf) {
                // A packet Velocity does not know. We only care about the chat session update.
                if (settings.dropChatSession && matchesSession((ByteBuf) msg)) {
                    ReferenceCountUtil.release(msg);
                    stats.sessions.incrementAndGet();
                    log.debug("Dropped chat session key of " + player + " (not forwarded to the backend)");
                    return;
                }
            } else if (settings.stripChat && hooks.chat != null && hooks.chat.isInstance(msg)) {
                if (hooks.unsignChat(msg)) {
                    stats.chat.incrementAndGet();
                    log.debug("Removed chat signature of " + player);
                }
            } else if (settings.stripCommands && hooks.command != null && hooks.command.isInstance(msg)) {
                if (hooks.unsignCommand(msg)) {
                    stats.commands.incrementAndGet();
                    log.debug("Removed command signatures of " + player);
                }
            }
        } catch (Throwable t) {
            stats.error(t);
            log.debug("Could not process a packet of " + player + ": " + t);
        }
        ctx.fireChannelRead(msg);
    }

    private static boolean matchesSession(ByteBuf buf) {
        return ChatSessionPacket.matches(buf::getByte, buf.readerIndex(), buf.writerIndex());
    }
}
