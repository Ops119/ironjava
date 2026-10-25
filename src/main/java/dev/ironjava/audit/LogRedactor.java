package dev.ironjava.audit;

import java.util.regex.Pattern;

public final class LogRedactor {
    private static final Pattern[] PATTERNS = new Pattern[]{
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*"),
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]+"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]+"),
            Pattern.compile("\\bsk-[A-Za-z0-9\\-_]{10,}"),
            Pattern.compile("(?i)(api[_-]?key|token|password|secret|authorization)\\s*[=:]\\s*[^\\s,;]+")
    };

    private LogRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = value;
        for (Pattern pattern : PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }

    public static String truncate(String value, int maxBytes) {
        String redacted = redact(value);
        if (redacted == null || redacted.length() <= maxBytes) {
            return redacted;
        }
        String marker = "…[truncated]\n";
        int keep = Math.max(0, maxBytes - marker.length());
        return marker + redacted.substring(redacted.length() - keep);
    }
}
