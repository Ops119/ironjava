package dev.ironjava.verification;

import dev.ironjava.config.IronJavaProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

@Component
public class CommandRunner {
    private final IronJavaProperties properties;

    public CommandRunner(IronJavaProperties properties) {
        this.properties = properties;
    }

    public record Execution(int exitCode, String output, long elapsedMillis, boolean timedOut, boolean cancelled,
                            String isolation, List<String> command, boolean runnerUnavailable) {
    }

    public Execution verify(Path workspace, Duration timeout, BooleanSupplier cancelled) {
        return maven(workspace, timeout, cancelled, "verify");
    }

    public Execution resolve(Path workspace, Duration timeout, BooleanSupplier cancelled) {
        return maven(workspace, timeout, cancelled, "dependency:resolve");
    }

    /** Scanner JSON must be kept whole. A tail cut makes the report malformed and the scan inconclusive. */
    private static final int SCAN_OUTPUT_CAP = 16_000_000;

    public Execution scan(Path workspace, Duration timeout, BooleanSupplier cancelled) {
        String root = "container".equalsIgnoreCase(properties.getRunner().getMode())
                ? "/workspace"
                : workspace.toAbsolutePath().toString();
        String binary = "container".equalsIgnoreCase(properties.getRunner().getMode())
                ? "osv-scanner"
                : properties.getRunner().getOsvScannerBinary();
        List<String> command = CommandProfiles.scan(binary, root);
        int cap = Math.max(properties.getLimits().getMaxLogBytes(), SCAN_OUTPUT_CAP);
        if ("container".equalsIgnoreCase(properties.getRunner().getMode())) {
            return container(workspace, command, timeout, cancelled, cap);
        }
        ProcessCommand.Result result = ProcessCommand.run(command, workspace, timeout, cancelled, cap);
        return adapt(result, "process", command);
    }

    private Execution maven(Path workspace, Duration timeout, BooleanSupplier cancelled, String goal) {
        try {
            Path settings = workspace.resolve(".ironjava-settings.xml");
            Files.writeString(settings, """
                    <settings>
                      <interactiveMode>false</interactiveMode>
                      <offline>false</offline>
                    </settings>
                    """);
            String repoLocal = "container".equalsIgnoreCase(properties.getRunner().getMode())
                    ? "/workspace/.m2"
                    : workspace.resolve(".m2").toAbsolutePath().toString();
            String settingsPath = "container".equalsIgnoreCase(properties.getRunner().getMode())
                    ? "/workspace/.ironjava-settings.xml"
                    : settings.toAbsolutePath().toString();
            String binary = "container".equalsIgnoreCase(properties.getRunner().getMode())
                    ? "mvn"
                    : properties.getRunner().getMavenBinary();
            List<String> inner = "verify".equals(goal)
                    ? CommandProfiles.verify(binary, settingsPath, repoLocal)
                    : CommandProfiles.resolve(binary, settingsPath, repoLocal);
            if ("container".equalsIgnoreCase(properties.getRunner().getMode())) {
                return container(workspace, inner, timeout, cancelled, properties.getLimits().getMaxLogBytes());
            }
            ProcessCommand.Result result = ProcessCommand.run(inner, workspace, timeout, cancelled, properties.getLimits().getMaxLogBytes());
            return adapt(result, "process", inner);
        } catch (IOException exception) {
            return new Execution(-1, "workspace settings write failed", 0, false, false, "process", List.of(), true);
        }
    }

    private Execution container(Path workspace, List<String> inner, Duration timeout, BooleanSupplier cancelled, int maxLogBytes) {
        if (!dockerAvailable()) {
            return new Execution(-1, "docker runner is not available", 0, false, false, "container", List.of(), true);
        }
        List<String> command = containerArgs(properties, workspace, inner);
        ProcessCommand.Result result = ProcessCommand.run(command, workspace, timeout.plusSeconds(30), cancelled, maxLogBytes);
        return adapt(result, "container", command);
    }

    static boolean dockerAvailable(String dockerBinary) {
        return ProcessCommand.run(List.of(dockerBinary, "version", "--format", "{{.Server.Version}}"),
                Path.of("."), Duration.ofSeconds(10), () -> false, 2000).exitCode() == 0;
    }

    private boolean dockerAvailable() {
        return dockerAvailable(properties.getRunner().getDockerBinary());
    }

    public static List<String> containerArgs(IronJavaProperties properties, Path workspace, List<String> inner) {
        List<String> command = new ArrayList<>();
        command.add(properties.getRunner().getDockerBinary());
        command.add("run");
        command.add("--rm");
        command.add("--user");
        command.add("65532:65532");
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges:true");
        command.add("--pids-limit");
        command.add("256");
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,nosuid,size=512m");
        command.add("--memory");
        command.add("2g");
        command.add("--cpus");
        command.add("2");
        command.add("--network");
        command.add(properties.getRunner().getNetwork());
        command.add("--add-host");
        command.add("metadata.google.internal:127.0.0.1");
        command.add("--add-host");
        command.add("169.254.169.254:127.0.0.1");
        command.add("-e");
        command.add("HOME=/tmp");
        command.add("-e");
        command.add("XDG_CACHE_HOME=/tmp");
        command.add("-v");
        command.add(workspace.toAbsolutePath() + ":/workspace:rw");
        command.add("-w");
        command.add("/workspace");
        command.add("--entrypoint");
        command.add(inner.get(0));
        command.add(properties.getRunner().getImage());
        command.addAll(inner.subList(1, inner.size()));
        return List.copyOf(command);
    }

    private Execution adapt(ProcessCommand.Result result, String isolation, List<String> command) {
        return new Execution(result.exitCode(), result.output(), result.elapsedMillis(), result.timedOut(),
                result.cancelled(), isolation, command, false);
    }
}
