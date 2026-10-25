package dev.ironjava.orchestrator;

import java.util.Objects;

public final class UnifiedDiff {
    private UnifiedDiff() {
    }

    public static String of(String path, String before, String after) {
        String[] left = before.split("\n", -1);
        String[] right = after.split("\n", -1);
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(path).append('\n');
        diff.append("+++ b/").append(path).append('\n');
        int limit = Math.max(left.length, right.length);
        int first = -1;
        int last = -1;
        for (int i = 0; i < limit; i++) {
            if (!Objects.equals(line(left, i), line(right, i))) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return diff.toString();
        }
        int start = Math.max(0, first - 3);
        int end = Math.min(limit - 1, last + 3);
        diff.append("@@ -").append(start + 1).append(',').append(end - start + 1)
                .append(" +").append(start + 1).append(',').append(end - start + 1).append(" @@\n");
        for (int i = start; i <= end; i++) {
            String from = line(left, i);
            String to = line(right, i);
            if (Objects.equals(from, to)) {
                diff.append(' ').append(from == null ? "" : from).append('\n');
            } else {
                if (from != null) {
                    diff.append('-').append(from).append('\n');
                }
                if (to != null) {
                    diff.append('+').append(to).append('\n');
                }
            }
        }
        return diff.toString();
    }

    private static String line(String[] lines, int index) {
        return index < lines.length ? lines[index] : null;
    }
}
