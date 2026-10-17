package com.inkcontrol.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display-only catalog. Configuration values, route keys and runtime protocols stay unchanged. */
public final class TextCatalog {
    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        String translation;
    }
    private final Node root = new Node();
    private final Map<String, String> exact = new HashMap<>();

    public TextCatalog(Reader reader) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] pair = line.split("\t", -1);
            if (pair.length != 2) throw new IOException("Invalid translation row");
            String source = unescape(pair[0]), target = unescape(pair[1]);
            if (source.isEmpty() || target.isEmpty() || exact.put(source, target) != null)
                throw new IOException("Empty or duplicate translation");
            Node node = root;
            for (int i = 0; i < source.length(); i++)
                node = node.next.computeIfAbsent(source.charAt(i), key -> new Node());
            node.translation = target;
        }
    }
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else out.append(c);
        }
        return out.toString();
    }

    /** Longest phrase first handles legacy composed labels without changing their backing identifiers. */
    public String translate(String value, boolean english) {
        if (!english || value == null || value.isEmpty()) return value;
        String full = exact.get(value);
        if (full != null) return full;
        // This formatted sentence contains runtime numbers, so match its structure before fragments.
        Matcher battery = Pattern.compile("当前电池温度 (-?[0-9]+(?:\\.[0-9]+)?)°C → 每张中间画面停留 ([0-9]+) 毫秒").matcher(value);
        if (battery.matches()) return "Battery " + battery.group(1) + "°C → hold each intermediate frame for " + battery.group(2) + " ms";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length();) {
            Node node = root;
            String target = null;
            int end = i;
            for (int j = i; j < value.length(); j++) {
                node = node.next.get(value.charAt(j));
                if (node == null) break;
                if (node.translation != null) { target = node.translation; end = j + 1; }
            }
            if (target == null) out.append(value.charAt(i++));
            else { out.append(target); i = end; }
        }
        return out.toString();
    }
}
