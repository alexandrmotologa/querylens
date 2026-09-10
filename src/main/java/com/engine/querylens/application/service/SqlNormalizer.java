package com.engine.querylens.application.service;

import com.engine.querylens.domain.model.QueryFingerprint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes SQL statements by stripping literals and values,
 * tokenizing query types, extracting table references, and generating 64-bit fingerprint hashes.
 */
public class SqlNormalizer {

    // Regex for single-quoted string literals including escaped quotes ('hello', 'it''s')
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']|'')*'");

    // Regex for hex literals (0x1234 or X'1234')
    private static final Pattern HEX_LITERAL = Pattern.compile("(?i)(0x[0-9a-f]+|x'[0-9a-f]+')");

    // Regex for UUID literals ('e0d9...-....') already covered by STRING_LITERAL,
    // but unquoted numeric literals: integers, decimals, scientific notation
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("(?<=\\b|[-+*/%=<>!,(])\\d+(\\.\\d+)?([eE][-+]?\\d+)?(?=\\b|[-+*/%=<>!),;])");

    // Regex for multiple parameter placeholders inside IN clause: IN (?, ?, ?) -> IN (?)
    private static final Pattern IN_CLAUSE_MULTIPLE_PARAMS = Pattern.compile("(?i)\\bIN\\s*\\(\\s*\\?\\s*(?:,\\s*\\?\\s*)+\\)");

    // Regex for multiple whitespace characters
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Regex for identifying primary SQL operation keyword
    private static final Pattern QUERY_TYPE = Pattern.compile("^\\s*([A-Za-z]+)", Pattern.CASE_INSENSITIVE);

    // Regex for extracting table names in standard clauses
    private static final Pattern TABLE_EXTRACTOR = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)?)"
    );

    public QueryFingerprint normalize(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return new QueryFingerprint(0L, "", "EMPTY", List.of());
        }

        String sql = rawSql.trim();

        // 1. Extract query type keyword (e.g. SELECT, INSERT, UPDATE, DELETE, BEGIN, COMMIT)
        String queryType = extractQueryType(sql);

        // 2. Replace hex literals with placeholder
        sql = HEX_LITERAL.matcher(sql).replaceAll("?");

        // 3. Replace string literals with placeholder
        sql = STRING_LITERAL.matcher(sql).replaceAll("?");

        // 4. Replace numeric literals with placeholder
        sql = NUMERIC_LITERAL.matcher(sql).replaceAll("?");

        // 5. Consolidate IN (?, ?, ...) -> IN (?)
        sql = IN_CLAUSE_MULTIPLE_PARAMS.matcher(sql).replaceAll("IN (?)");

        // 6. Normalize whitespace
        sql = WHITESPACE.matcher(sql).replaceAll(" ").trim();

        // 7. Extract referenced tables
        List<String> tables = extractTables(sql);

        // 8. Compute 64-bit MurmurHash3 hash
        long hash = murmurHash3(sql);

        return new QueryFingerprint(hash, sql, queryType, tables);
    }

    private String extractQueryType(String sql) {
        Matcher matcher = QUERY_TYPE.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return "UNKNOWN";
    }

    private List<String> extractTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = TABLE_EXTRACTOR.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (!tables.contains(table)) {
                tables.add(table);
            }
        }
        return Collections.unmodifiableList(tables);
    }

    /**
     * 64-bit MurmurHash3 implementation for high-speed, uniform distribution SQL fingerprinting.
     */
    public static long murmurHash3(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int length = data.length;
        long seed = 0x9747b28cL;
        long c1 = 0x87c37b91114253d5L;
        long c2 = 0x4cf5ad432745937fL;

        long h1 = seed;
        long h2 = seed;

        int nblocks = length / 16;
        for (int i = 0; i < nblocks; i++) {
            int index = i * 16;
            long k1 = getLong(data, index);
            long k2 = getLong(data, index + 8);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729L;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5L;
        }

        int tail = nblocks * 16;
        long k1 = 0;
        long k2 = 0;

        switch (length & 15) {
            case 15: k2 ^= (long) (data[tail + 14] & 0xFF) << 48;
            case 14: k2 ^= (long) (data[tail + 13] & 0xFF) << 40;
            case 13: k2 ^= (long) (data[tail + 12] & 0xFF) << 32;
            case 12: k2 ^= (long) (data[tail + 11] & 0xFF) << 24;
            case 11: k2 ^= (long) (data[tail + 10] & 0xFF) << 16;
            case 10: k2 ^= (long) (data[tail + 9] & 0xFF) << 8;
            case 9:  k2 ^= (long) (data[tail + 8] & 0xFF);
                k2 *= c2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;
            case 8:  k1 ^= (long) (data[tail + 7] & 0xFF) << 56;
            case 7:  k1 ^= (long) (data[tail + 6] & 0xFF) << 48;
            case 6:  k1 ^= (long) (data[tail + 5] & 0xFF) << 40;
            case 5:  k1 ^= (long) (data[tail + 4] & 0xFF) << 32;
            case 4:  k1 ^= (long) (data[tail + 3] & 0xFF) << 24;
            case 3:  k1 ^= (long) (data[tail + 2] & 0xFF) << 16;
            case 2:  k1 ^= (long) (data[tail + 1] & 0xFF) << 8;
            case 1:  k1 ^= (long) (data[tail] & 0xFF);
                k1 *= c1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= c2;
                h1 ^= k1;
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        return h1;
    }

    private static long getLong(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24)
                | ((long) (data[offset + 4] & 0xFF) << 32)
                | ((long) (data[offset + 5] & 0xFF) << 40)
                | ((long) (data[offset + 6] & 0xFF) << 48)
                | ((long) (data[offset + 7] & 0xFF) << 56);
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
