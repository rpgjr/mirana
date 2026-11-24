package com.rpgjr.mirana.model.dto.project;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How a project writes ticket keys: one prefix and a number padded to {@code digits}, like FT-00001. */
public record TicketFormat(int digits, String prefix) {

    public static final TicketFormat DEFAULT = new TicketFormat(5, "FT");

    public String key(int number) {
        return prefix + "-" + ("%0" + digits + "d").formatted(number);
    }

    /** Finds keys in free text such as a branch name, PR title or body. Leading zeros are optional, case is ignored. */
    public List<String> find(String text) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(prefix) + "-(\\d{1,9})\\b", Pattern.CASE_INSENSITIVE);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher m = p.matcher(text == null ? "" : text);
        while (m.find()) {
            keys.add(key(Integer.parseInt(m.group(1))));
        }
        return List.copyOf(keys);
    }

    public static String normalize(String prefix) {
        return prefix.toUpperCase(Locale.ROOT);
    }
}
