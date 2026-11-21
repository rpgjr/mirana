package com.rpgjr.mirana.enums;

public enum TicketType {
    FEATURE("FT"), HOTFIX("HF"), BUGFIX("BUG");

    private final String prefix;

    TicketType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
