package me.almunther.unsignedchat.bungee;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.almunther.unsignedchat.ChatSessionPacket;
import me.almunther.unsignedchat.Settings;
import me.almunther.unsignedchat.Sink;
import me.almunther.unsignedchat.Stats;

/**
 * Sits in a player's Netty pipeline right in front of BungeeCord's {@code inbound-boss} handler.
 *
 * <p>For chat and commands of 1.19.3+ clients it replaces the packet BungeeCord is about to process with an
 * equivalent unsigned one, so that cancelling the ChatEvent no longer desynchronises the signature chain with the
 * backend server. Optionally it also remembers the original text so that a message changed by another plugin
 * can be applied afterwards (BungeeCord itself ignores such changes for 1.19.1+ clients).
 *
 * <p>Failure policy: never throw, never block. If anything goes wrong the packet continues untouched.
 */
final class BungeeUnsigner extends ChannelInboundHandlerAdapter {

    static final String NAME = "unsignedchat";

    /** The chat packet currently travelling through BungeeCord's handler on this thread (if any). */
    static final ThreadLocal<Pending> CURRENT = new ThreadLocal<>();

    /** State needed to apply a modified message after the ChatEvent. */
    static final class Pending {
        final Object player;
        final String original;
        private final BungeeHooks hooks;
        private final Object packet;
        private final Object wrapper;
        private final ByteBuf buf;
        private final byte[] idBytes;
        private final int version;

        Pending(Object player, String original, BungeeHooks hooks, Object packet, Object wrapper, ByteBuf buf,
                byte[] idBytes, int version) {
            this.player = player;
            this.original = original;
            this.hooks = hooks;
            this.packet = packet;
            this.wrapper = wrapper;
            this.buf = buf;
            this.idBytes = idBytes;
            this.version = version;
        }

        boolean isCommand() {
            return original.startsWith("/");
        }

        /** Rewrites the packet that will be forwarded to the backend so that it carries {@code text}. */
        void apply(String text) throws ReflectiveOperationException {
            hooks.setText(packet, text, version);
            buf.clear();
            hooks.encode(packet, wrapper, buf, idBytes, version);
        }
    }

    private final Object player;
    private final String name;
    private final int version;
    private final BungeeHooks hooks;
    private final Settings settings;
    private final Stats stats;
    private final Sink log;

    BungeeUnsigner(Object player, String name, int version, BungeeHooks hooks, Settings settings, Stats stats,
            Sink log) {
        this.player = player;
        this.name = name;
        this.version = version;
        this.hooks = hooks;
        this.settings = settings;
        this.stats = stats;
        this.log = log;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Object out = msg;
        Pending pending = null;
        try {
            if (hooks.wrapper.isInstance(msg)) {
                Object packet = hooks.packetOf(msg);
                if (packet == null) {
                    // A packet BungeeCord does not decode. We only care about the chat session update.
                    ByteBuf raw = hooks.bufOf(msg);
                    if (settings.dropChatSession && ChatSessionPacket.matches(raw::getByte, raw.readerIndex(), raw.writerIndex())) {
                        hooks.release(msg);
                        stats.sessions.incrementAndGet();
                        log.debug("Dropped chat session key of " + name + " (not forwarded to the backend)");
                        return;
                    }
                } else {
                    boolean isChat = hooks.chat.isInstance(packet);
                    boolean isCommand = hooks.command.isInstance(packet);
                    boolean isUnsignedCommand = hooks.supportsUnsignedCommand() && hooks.unsignedCommand.isInstance(packet);
                    if (isChat || isCommand || isUnsignedCommand) {
                        boolean strip = isChat
                                ? settings.stripChat && hooks.isChatSigned(packet)
                                : isCommand && settings.stripCommands && hooks.isCommandSigned(packet);
                        if (strip || settings.applyModifiedMessages) {
                            String original = hooks.textOf(packet);
                            ByteBuf oldBuf = hooks.bufOf(msg);
                            byte[] id = idBytes(oldBuf);
                            if (strip) {
                                if (isChat) {
                                    hooks.clearChatSignature(packet);
                                } else {
                                    hooks.clearCommandSignatures(packet);
                                }
                            }
                            // A private buffer we are allowed to grow and rewrite later.
                            ByteBuf fresh = ctx.alloc().buffer(oldBuf.readableBytes());
                            Object replacement;
                            try {
                                hooks.encode(packet, msg, fresh, id, version);
                                replacement = hooks.newWrapper(packet, fresh, msg);
                            } catch (Throwable t) {
                                fresh.release();
                                throw t;
                            }
                            hooks.release(msg);
                            out = replacement;
                            if (strip) {
                                (isChat ? stats.chat : stats.commands).incrementAndGet();
                                log.debug("Removed " + (isChat ? "chat" : "command") + " signature of " + name);
                            }
                            if (settings.applyModifiedMessages) {
                                pending = new Pending(player, original, hooks, packet, replacement, fresh, id, version);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            stats.error(t);
            log.debug("Could not process a packet of " + name + ": " + t);
            // 'out' still refers to a valid wrapper: either the untouched original or the finished replacement.
            pending = null;
        }

        if (pending != null) {
            CURRENT.set(pending);
        }
        try {
            ctx.fireChannelRead(out);
        } finally {
            if (pending != null) {
                CURRENT.remove();
            }
        }
    }

    /** Copies the leading packet id VarInt of a packet buffer. */
    private static byte[] idBytes(ByteBuf buf) {
        int start = buf.readerIndex();
        int len = 0;
        while (len < 5 && start + len < buf.writerIndex() && (buf.getByte(start + len) & 0x80) != 0) {
            len++;
        }
        len++;
        byte[] id = new byte[len];
        buf.getBytes(start, id);
        return id;
    }
}
