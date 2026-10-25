package dev.ironjava.verification;

import dev.ironjava.audit.LogRedactor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ProcessCommand {
    public record Result(int exitCode, String output, long elapsedMillis, boolean timedOut, boolean cancelled) {
    }

    private ProcessCommand() {
    }

    public static Result run(List<String> command, Path directory, Duration timeout, BooleanSupplier cancelled,
                             int maxLogBytes) {
        if (command == null || command.isEmpty() || command.stream().anyMatch(part -> part == null || part.contains("\n"))) {
            throw new IllegalArgumentException("command must be a non-empty argv array");
        }
        Instant started = Instant.now();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        scrub(builder.environment());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), captured, maxLogBytes));
            boolean timedOut = false;
            boolean wasCancelled = false;
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    wasCancelled = true;
                    destroyTree(process);
                    break;
                }
                if (Duration.between(started, Instant.now()).compareTo(timeout) > 0) {
                    timedOut = true;
                    destroyTree(process);
                    break;
                }
                if (!process.isAlive()) {
                    break;
                }
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
            process.waitFor(5, TimeUnit.SECONDS);
            reader.join(2_000);
            int exit = process.isAlive() ? -1 : process.exitValue();
            String output = LogRedactor.truncate(captured.toString(StandardCharsets.UTF_8), maxLogBytes);
            long elapsed = Duration.between(started, Instant.now()).toMillis();
            return new Result(exit, output, elapsed, timedOut, wasCancelled);
        } catch (Exception exception) {
            return new Result(-1, LogRedactor.redact(exception.getClass().getSimpleName()), 
                    Duration.between(started, Instant.now()).toMillis(), false, false);
        }
    }

    public static void scrub(Map<String, String> environment) {
        environment.keySet().removeIf(ProcessCommand::sensitive);
    }

    static boolean sensitive(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN")
                || upper.contains("SECRET")
                || upper.contains("PASSWORD")
                || upper.contains("API_KEY")
                || upper.contains("PRIVATE_KEY")
                || upper.contains("AUTHORIZATION")
                || upper.startsWith("IRONJAVA_GITHUB")
                || upper.startsWith("IRONJAVA_MODEL")
                || upper.startsWith("IRONJAVA_AUTH");
    }

    /**
     * Keeps the trailing {@code max} bytes. Maven and the scanner print the result at the end,
     * and a prefix-only cap drops the test summary once dependency download logs grow.
     */
    private static void drain(InputStream input, ByteArrayOutputStream captured, int max) {
        if (max <= 0) {
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            byte[] window = new byte[max];
            int filled = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read >= max) {
                    System.arraycopy(buffer, read - max, window, 0, max);
                    filled = max;
                    continue;
                }
                if (filled + read <= max) {
                    System.arraycopy(buffer, 0, window, filled, read);
                    filled += read;
                    continue;
                }
                int overflow = filled + read - max;
                System.arraycopy(window, overflow, window, 0, filled - overflow);
                filled -= overflow;
                System.arraycopy(buffer, 0, window, filled, read);
                filled += read;
            }
            captured.write(window, 0, filled);
        } catch (Exception ignored) {
            // The process is being torn down.
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(handle -> handle.destroy());
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
