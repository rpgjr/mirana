package com.rpgjr.mirana.model.dto.project;

import com.rpgjr.mirana.enums.TicketType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How a project writes ticket keys: one prefix per ticket type and a number padded to {@code digits}, like FT-00001. */
public record TicketFormat(int digits, String featurePrefix, String hotfixPrefix, String bugfixPrefix) {

    public static final TicketFormat DEFAULT = new TicketFormat(5, "FT", "HF", "BUG");

    public String prefix(TicketType type) {
        return switch (type) {
            case FEATURE -> featurePrefix;
            case HOTFIX -> hotfixPrefix;
            case BUGFIX -> bugfixPrefix;
        };
    }

    public String key(TicketType type, int number) {
        return prefix(type) + "-" + ("%0" + digits + "d").formatted(number);
    }

    /** Finds keys in free text such as a branch name, PR title or body. Leading zeros are optional, case is ignored. */
    public List<String> find(String text) {
        Pattern p = Pattern.compile("\\b(" + Pattern.quote(featurePrefix) + "|" + Pattern.quote(hotfixPrefix) + "|"
                + Pattern.quote(bugfixPrefix) + ")-(\\d{1,9})\\b", Pattern.CASE_INSENSITIVE);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher m = p.matcher(text == null ? "" : text);
        while (m.find()) {
            String found = m.group(1).toUpperCase(Locale.ROOT);
            TicketType type = found.equals(featurePrefix) ? TicketType.FEATURE : found.equals(hotfixPrefix) ? TicketType.HOTFIX : TicketType.BUGFIX;
            keys.add(key(type, Integer.parseInt(m.group(2))));
        }
        return List.copyOf(keys);
    }
}
