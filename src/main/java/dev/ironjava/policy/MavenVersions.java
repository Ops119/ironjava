package dev.ironjava.policy;

import org.apache.maven.artifact.versioning.ComparableVersion;

public final class MavenVersions {
    private MavenVersions() {
    }

    public static int compare(String left, String right) {
        return new ComparableVersion(left).compareTo(new ComparableVersion(right));
    }

    public static String majorToken(String version) {
        String value = version == null ? "" : version.trim();
        int index = 0;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index == 0 ? value : value.substring(0, index);
    }

    public static boolean sameMajor(String left, String right) {
        return majorToken(left).equals(majorToken(right));
    }
}
