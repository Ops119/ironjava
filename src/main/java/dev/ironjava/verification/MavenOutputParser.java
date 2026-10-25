package dev.ironjava.verification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MavenOutputParser {
    private static final Pattern TESTS = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    public record TestCounts(int run, int failures, int errors, int skipped) {
        public boolean executed() {
            return run > 0;
        }
    }

    private MavenOutputParser() {
    }

    public static TestCounts parse(String log) {
        if (log == null) {
            return null;
        }
        Matcher matcher = TESTS.matcher(log);
        TestCounts last = null;
        while (matcher.find()) {
            last = new TestCounts(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)));
        }
        return last;
    }

    public static boolean skipFlags(String pom) {
        if (pom == null) {
            return false;
        }
        String compact = pom.replaceAll("\\s+", "").toLowerCase();
        return compact.contains("<skiptests>true</skiptests>")
                || compact.contains("<maven.test.skip>true</maven.test.skip>")
                || compact.contains("<skip>true</skip>");
    }
}
