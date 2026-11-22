package org.example.reviewer.config;

/** Порядок важен: ordinal используется как порог строгости (ERROR самый строгий). */
public enum Severity {
    ERROR, WARNING, INFO;

    public static Severity parse(String raw) {
        if (raw == null) {
            return INFO;
        }
        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(raw.trim())) {
                return severity;
            }
        }
        return INFO;
    }
}
