package com.engine.querylens.infrastructure.wire;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Low-level utility functions for parsing PostgreSQL v3.0 wire frames from Netty ByteBufs.
 */
public final class PostgresWireUtils {
    private PostgresWireUtils() {}

    /**
     * Reads a null-terminated UTF-8 string from the buffer.
     * Returns null if no terminating null byte is found within readable bytes.
     */
    public static String readNullTerminatedString(ByteBuf buf) {
        int zeroIndex = buf.indexOf(buf.readerIndex(), buf.writerIndex(), (byte) 0);
        if (zeroIndex < 0) {
            return null;
        }
        int length = zeroIndex - buf.readerIndex();
        String str = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.readerIndex(zeroIndex + 1); // Skip string plus terminating null byte
        return str;
    }

    /**
     * Parses the affected row count from a PostgreSQL CommandComplete tag string.
     * Examples:
     * - "SELECT 15" -> 15
     * - "INSERT 0 1" -> 1
     * - "UPDATE 4" -> 4
     * - "DELETE 2" -> 2
     * - "COMMIT" -> 0
     */
    public static long parseAffectedRows(String commandTag) {
        if (commandTag == null || commandTag.isBlank()) {
            return 0L;
        }
        String[] parts = commandTag.trim().split("\\s+");
        if (parts.length > 0) {
            String last = parts[parts.length - 1];
            try {
                return Long.parseLong(last);
            } catch (NumberFormatException ignored) {
                // Not a numeric count (e.g. BEGIN, COMMIT, CREATE TABLE)
                return 0L;
            }
        }
        return 0L;
    }
}
