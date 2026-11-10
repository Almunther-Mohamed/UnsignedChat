package me.almunther.unsignedchat;

/**
 * Recognises the serverbound "chat session update" packet (1.19.3+) without needing its packet id.
 *
 * <p>Neither Velocity nor BungeeCord decodes this packet, they just forward the raw bytes to the backend.
 * Its id changes in almost every Minecraft release, but its body never has: {@code UUID session id (16)},
 * {@code long expiry (8)}, {@code byte[] RSA public key (X.509, starts with an ASN.1 SEQUENCE and contains the
 * RSA OID)} and {@code byte[] key signature}, each array prefixed by a VarInt length. Checking that structure
 * (including that the lengths add up to the exact packet size) is far more reliable than keeping a table of ids.
 */
public final class ChatSessionPacket {

    /** DER encoding of the rsaEncryption OID 1.2.840.113549.1.1.1 (without tag and length). */
    private static final byte[] RSA_OID = {
            0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01
    };

    private ChatSessionPacket() {
    }

    /** Random access to the bytes of one packet, e.g. {@code buf::getByte} for a Netty ByteBuf. */
    public interface Bytes {
        byte at(int index);
    }

    /** @param bytes a whole packet, packet id first, occupying the indexes {@code start} until {@code end}. */
    public static boolean matches(Bytes buf, int start, int end) {
        final int size = end - start;
        // 1 id + 16 uuid + 8 expiry + 1..2 len + >=90 key + 1..2 len + signature  /  generous upper bound
        if (size < 130 || size > 5600) {
            return false;
        }

        long r = readVarInt(buf, start, end);          // packet id
        if (r < 0) {
            return false;
        }
        int pos = start + (int) (r >>> 32) + 16 + 8;   // skip id, session uuid, expiry
        if (pos >= end) {
            return false;
        }

        r = readVarInt(buf, pos, end);                 // public key length
        if (r < 0) {
            return false;
        }
        int keyLen = (int) r;
        pos += (int) (r >>> 32);
        if (keyLen < 90 || keyLen > 1200 || pos + keyLen >= end) {
            return false;
        }
        if (buf.at(pos) != 0x30 || !containsOid(buf, pos, pos + Math.min(keyLen, 32))) {
            return false;
        }
        pos += keyLen;

        r = readVarInt(buf, pos, end);                 // key signature length
        if (r < 0) {
            return false;
        }
        int sigLen = (int) r;
        pos += (int) (r >>> 32);
        return sigLen >= 0 && sigLen <= 4096 && pos + sigLen == end;
    }

    private static boolean containsOid(Bytes buf, int from, int to) {
        outer:
        for (int i = from; i + RSA_OID.length <= to; i++) {
            for (int j = 0; j < RSA_OID.length; j++) {
                if (buf.at(i + j) != RSA_OID[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** @return (bytesRead &lt;&lt; 32) | value, or -1 if there is no complete VarInt at pos. */
    static long readVarInt(Bytes buf, int pos, int end) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (pos + i >= end) {
                return -1;
            }
            byte b = buf.at(pos + i);
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                if (value < 0) {
                    return -1;
                }
                return ((long) (i + 1) << 32) | (value & 0xFFFFFFFFL);
            }
        }
        return -1;
    }
}
