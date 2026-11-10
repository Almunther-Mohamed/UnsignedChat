package me.almunther.unsignedchat.bungee;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import me.almunther.unsignedchat.Reflect;
import me.almunther.unsignedchat.Sink;

/**
 * Looks up BungeeCord's protocol classes by name (they are internal API) and wraps the few operations we need.
 * Any member that cannot be found switches off the feature that needs it and says so in the log.
 */
final class BungeeHooks {

    private static final String PROTOCOL = "net.md_5.bungee.protocol.";

    final Class<?> wrapper;
    final Class<?> chat;
    final Class<?> command;
    final Class<?> unsignedCommand;

    private final Field wrapperPacket;
    private final Field wrapperBuf;
    private final Field wrapperProtocol;
    private final Constructor<?> wrapperCtor;
    private final Method release;
    private final Method write;
    private final Object toServer;

    private final Method chatGetSignature;
    private final Method chatSetSignature;
    private final Method chatGetMessage;
    private final Method chatSetMessage;
    private final Method commandGetSignatures;
    private final Method commandSetSignatures;
    private final Method commandGetCommand;
    private final Method commandSetCommand;
    private final Method unsignedGetCommand;
    private final Method unsignedSetCommand;

    private BungeeHooks(ClassLoader cl, Sink log) {
        wrapper = Reflect.firstClass(cl, PROTOCOL + "PacketWrapper");
        Class<?> defined = Reflect.firstClass(cl, PROTOCOL + "DefinedPacket");
        Class<?> protocol = Reflect.firstClass(cl, PROTOCOL + "Protocol");
        Class<?> direction = Reflect.firstClass(cl, PROTOCOL + "ProtocolConstants$Direction");
        chat = Reflect.firstClass(cl, PROTOCOL + "packet.ClientChat");
        command = Reflect.firstClass(cl, PROTOCOL + "packet.ClientCommand");
        unsignedCommand = Reflect.firstClass(cl, PROTOCOL + "packet.UnsignedClientCommand");

        Field fPacket = null;
        Field fBuf = null;
        Field fProtocol = null;
        Constructor<?> ctor = null;
        Method rel = null;
        Method wr = null;
        Object to = null;
        if (wrapper != null && defined != null && protocol != null && direction != null) {
            fPacket = Reflect.field(wrapper, "packet");
            fBuf = Reflect.field(wrapper, "buf");
            fProtocol = Reflect.field(wrapper, "protocol");
            rel = Reflect.method(wrapper, "trySingleRelease");
            wr = Reflect.method(defined, "write", ByteBuf.class, protocol, direction, int.class);
            try {
                ctor = wrapper.getConstructor(defined, ByteBuf.class, protocol);
                for (Object c : direction.getEnumConstants()) {
                    if (c.toString().equals("TO_SERVER")) {
                        to = c;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                ctor = null;
            }
        }
        wrapperPacket = fPacket;
        wrapperBuf = fBuf;
        wrapperProtocol = fProtocol;
        wrapperCtor = ctor;
        release = rel;
        write = wr;
        toServer = to;

        chatGetSignature = chat == null ? null : Reflect.method(chat, "getSignature");
        chatSetSignature = chat == null ? null : Reflect.method(chat, "setSignature", byte[].class);
        chatGetMessage = chat == null ? null : Reflect.method(chat, "getMessage");
        chatSetMessage = chat == null ? null : Reflect.method(chat, "setMessage", String.class);
        commandGetSignatures = command == null ? null : Reflect.method(command, "getSignatures");
        commandSetSignatures = command == null ? null : Reflect.method(command, "setSignatures", Map.class);
        commandGetCommand = command == null ? null : Reflect.method(command, "getCommand");
        commandSetCommand = command == null ? null : Reflect.method(command, "setCommand", String.class);
        unsignedGetCommand = unsignedCommand == null ? null : Reflect.method(unsignedCommand, "getCommand");
        unsignedSetCommand = unsignedCommand == null ? null : Reflect.method(unsignedCommand, "setCommand", String.class);

        if (!available()) {
            log.warn("BungeeCord's packet classes were not recognised, UnsignedChat stays inactive.");
        }
    }

    static BungeeHooks resolve(ClassLoader proxyLoader, Sink log) {
        return new BungeeHooks(proxyLoader, log);
    }

    /** True when everything needed to rewrite chat and commands was found. */
    boolean available() {
        return wrapperPacket != null && wrapperBuf != null && wrapperProtocol != null && wrapperCtor != null
                && release != null && write != null && toServer != null
                && chat != null && chatGetSignature != null && chatSetSignature != null
                && chatGetMessage != null && chatSetMessage != null
                && command != null && commandGetSignatures != null && commandSetSignatures != null
                && commandGetCommand != null && commandSetCommand != null;
    }

    Object packetOf(Object wrapperObject) throws ReflectiveOperationException {
        return wrapperPacket.get(wrapperObject);
    }

    ByteBuf bufOf(Object wrapperObject) throws ReflectiveOperationException {
        return (ByteBuf) wrapperBuf.get(wrapperObject);
    }

    void release(Object wrapperObject) throws ReflectiveOperationException {
        release.invoke(wrapperObject);
    }

    boolean isChatSigned(Object packet) throws ReflectiveOperationException {
        byte[] signature = (byte[]) chatGetSignature.invoke(packet);
        return signature != null;
    }

    void clearChatSignature(Object packet) throws ReflectiveOperationException {
        chatSetSignature.invoke(packet, (Object) null);
    }

    boolean isCommandSigned(Object packet) throws ReflectiveOperationException {
        Map<?, ?> signatures = (Map<?, ?>) commandGetSignatures.invoke(packet);
        return signatures != null && !signatures.isEmpty();
    }

    void clearCommandSignatures(Object packet) throws ReflectiveOperationException {
        commandSetSignatures.invoke(packet, new java.util.HashMap<String, byte[]>());
    }

    /** Reads the text the player typed: the chat message, or the command including its leading slash. */
    String textOf(Object packet) throws ReflectiveOperationException {
        if (chat.isInstance(packet)) {
            return (String) chatGetMessage.invoke(packet);
        }
        if (command.isInstance(packet)) {
            return "/" + commandGetCommand.invoke(packet);
        }
        return "/" + unsignedGetCommand.invoke(packet);
    }

    /** Stores new text into the packet (inverse of {@link #textOf}). */
    void setText(Object packet, String text, int protocolVersion) throws ReflectiveOperationException {
        if (chat.isInstance(packet)) {
            chatSetMessage.invoke(packet, limit(text, 256));
        } else {
            String cmd = limit(text.startsWith("/") ? text.substring(1) : text, protocolVersion >= 766 ? 32767 : 256);
            if (command.isInstance(packet)) {
                commandSetCommand.invoke(packet, cmd);
            } else {
                unsignedSetCommand.invoke(packet, cmd);
            }
        }
    }

    boolean supportsUnsignedCommand() {
        return unsignedCommand != null && unsignedGetCommand != null && unsignedSetCommand != null;
    }

    /**
     * Encodes {@code packet} into {@code target}, writing the packet id exactly like the original did.
     *
     * @param idBytes the packet id (VarInt) exactly as found at the start of the original packet.
     */
    void encode(Object packet, Object wrapperObject, ByteBuf target, byte[] idBytes, int protocolVersion)
            throws ReflectiveOperationException {
        target.writeBytes(idBytes);
        write.invoke(packet, target, wrapperProtocol.get(wrapperObject), toServer, protocolVersion);
    }

    Object newWrapper(Object packet, ByteBuf buf, Object oldWrapper) throws ReflectiveOperationException {
        return wrapperCtor.newInstance(packet, buf, wrapperProtocol.get(oldWrapper));
    }

    private static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
