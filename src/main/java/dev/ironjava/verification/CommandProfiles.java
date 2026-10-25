package dev.ironjava.verification;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed argv profiles. Nothing here is concatenated into a shell string.
 */
public final class CommandProfiles {
    public static final String RESOLUTION_MODE =
            "osv-scanner 2.7.0 scan source --recursive with the default deps.dev resolver. "
                    + "A repeat with --data-source=native is not required for the pilot comparison.";
    public static final String EXCLUSIONS =
            "Packages whose dependency_groups include test or dev are manual review, even when the scanner reports them. "
                    + "BOM-managed coordinates and source type unknown are manual review. "
                    +             "mvn verify runs the module's unit tests and does not add an integration-test profile. "
                    + "The scan excludes the workspace .m2 cache and target output so they are not treated as project source. "
                    + "OSV-Scanner coverage is not a complete security assessment.";

    private CommandProfiles() {
    }

    public static List<String> verify(String mavenBinary, String settingsPath, String repoLocal) {
        return maven(mavenBinary, settingsPath, repoLocal, "verify");
    }

    public static List<String> resolve(String mavenBinary, String settingsPath, String repoLocal) {
        return maven(mavenBinary, settingsPath, repoLocal, "dependency:resolve");
    }

    public static List<String> scan(String scannerBinary, String workspace) {
        return List.of(
                scannerBinary,
                "scan", "source",
                "--recursive",
                "--format", "json",
                "--verbosity", "error",
                "--x-exclude", ".m2",
                "--x-exclude", "target",
                workspace);
    }

    private static List<String> maven(String mavenBinary, String settingsPath, String repoLocal, String goal) {
        List<String> command = new ArrayList<>();
        command.add(mavenBinary);
        command.add("-B");
        command.add("-ntp");
        command.add("-s");
        command.add(settingsPath);
        command.add("-Dmaven.repo.local=" + repoLocal);
        command.add(goal);
        rejectSkipFlags(command);
        return List.copyOf(command);
    }

    public static void rejectSkipFlags(List<String> command) {
        for (String argument : command) {
            String lower = argument.toLowerCase();
            if (lower.contains("skiptests") || lower.contains("maven.test.skip") || lower.contains("skipits")) {
                throw new IllegalArgumentException("skip flags are not permitted");
            }
        }
    }
}
