package me.almunther.unsignedchat.velocity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.almunther.unsignedchat.Reflect;
import me.almunther.unsignedchat.Sink;

/**
 * Looks up Velocity's (internal) chat packet classes once at startup and strips signatures from them.
 *
 * <p>Everything is resolved by name, so a Velocity version that renames something simply disables that one hook
 * (with a log line) instead of breaking the proxy.
 */
final class VelocityHooks {

    private static final String PKG = "com.velocitypowered.proxy.protocol.packet.chat.session.";

    final Class<?> chat;
    final Class<?> command;
    private final Field chatSigned;
    private final Field chatSignature;
    private final Field chatSalt;
    private final Method chatIsSigned;
    private final Field commandSignatures;
    private final Field commandSalt;
    private final Method commandIsSigned;
    private final Constructor<?> emptySignatures;

    private VelocityHooks(Class<?> chat, Class<?> command, Sink log) {
        this.chat = chat;
        this.command = command;

        Field signed = null;
        Field signature = null;
        Field salt = null;
        Method isSigned = null;
        if (chat != null) {
            signed = Reflect.field(chat, "signed");
            signature = Reflect.field(chat, "signature");
            salt = Reflect.field(chat, "salt");
            isSigned = Reflect.method(chat, "isSigned");
            if (signed == null || signature == null || isSigned == null) {
                log.warn("Velocity chat packet layout not recognised, chat hook disabled.");
                signed = null;
            }
        }
        this.chatSigned = signed;
        this.chatSignature = signature;
        this.chatSalt = salt;
        this.chatIsSigned = isSigned;

        Field sigs = null;
        Field cmdSalt = null;
        Method cmdIsSigned = null;
        Constructor<?> ctor = null;
        if (command != null) {
            sigs = Reflect.field(command, "argumentSignatures");
            cmdSalt = Reflect.field(command, "salt");
            cmdIsSigned = Reflect.method(command, "isSigned");
            try {
                if (sigs != null) {
                    ctor = sigs.getType().getDeclaredConstructor();
                    ctor.setAccessible(true);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                ctor = null;
            }
            if (sigs == null || cmdIsSigned == null || ctor == null) {
                log.warn("Velocity command packet layout not recognised, command hook disabled.");
                sigs = null;
            }
        }
        this.commandSignatures = sigs;
        this.commandSalt = cmdSalt;
        this.commandIsSigned = cmdIsSigned;
        this.emptySignatures = ctor;
    }

    static VelocityHooks resolve(ClassLoader loader, Sink log) {
        return resolve(loader, log,
                new String[] {PKG + "SessionPlayerChatPacket", PKG + "SessionPlayerChat"},
                new String[] {PKG + "SessionPlayerCommandPacket", PKG + "SessionPlayerCommand"});
    }

    /** Same as {@link #resolve(ClassLoader, Sink)} with explicit candidate class names (used by the tests). */
    static VelocityHooks resolve(ClassLoader loader, Sink log, String[] chatNames, String[] commandNames) {
        Class<?> chat = Reflect.firstClass(loader, chatNames);
        Class<?> command = Reflect.firstClass(loader, commandNames);
        if (chat == null) {
            log.warn("Could not find Velocity's session chat packet, chat signatures will not be stripped.");
        }
        if (command == null) {
            log.warn("Could not find Velocity's session command packet, command signatures will not be stripped.");
        }
        return new VelocityHooks(chat, command, log);
    }

    boolean chatAvailable() {
        return chatSigned != null;
    }

    boolean commandAvailable() {
        return commandSignatures != null;
    }

    /** @return true if the packet was a signed chat message and has been turned into an unsigned one. */
    boolean unsignChat(Object packet) throws ReflectiveOperationException {
        if (!chatAvailable() || !chat.isInstance(packet) || !(Boolean) chatIsSigned.invoke(packet)) {
            return false;
        }
        // Exactly what Velocity itself produces for an unsigned message (see SessionChatBuilder#toServer).
        chatSigned.setBoolean(packet, false);
        chatSignature.set(packet, new byte[0]);
        if (chatSalt != null) {
            chatSalt.setLong(packet, 0L);
        }
        return true;
    }

    /** @return true if the packet was a command with signed arguments and now has none. */
    boolean unsignCommand(Object packet) throws ReflectiveOperationException {
        if (!commandAvailable() || !command.isInstance(packet) || !(Boolean) commandIsSigned.invoke(packet)) {
            return false;
        }
        commandSignatures.set(packet, emptySignatures.newInstance());
        if (commandSalt != null) {
            commandSalt.setLong(packet, 0L);
        }
        return true;
    }
}
