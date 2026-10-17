package sc.fidelis.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal RFC 4180 reader (quoted fields, doubled quotes, embedded newlines) for the catalogue CSVs. */
final class Csv {
    private Csv() {}

    /** Rows as header-name to value maps. */
    static List<Map<String, String>> readResource(String resource) {
        try (InputStream in = Csv.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    static List<Map<String, String>> parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                if (any || field.length() > 0) {
                    row.add(field.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> header = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = new LinkedHashMap<>();
            List<String> r = rows.get(i);
            for (int j = 0; j < header.size(); j++) m.put(header.get(j), j < r.size() ? r.get(j) : "");
            out.add(m);
        }
        return out;
    }
}
