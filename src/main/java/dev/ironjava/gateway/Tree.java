package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;
import dev.ironjava.patch.PomPatchService;
import dev.ironjava.verification.ProcessCommand;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class Tree {
    private Tree() {
    }

    public static void delete(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Cleanup is best-effort; retention sweeper retries.
                }
            });
        } catch (IOException ignored) {
            // Same as above.
        }
    }
}
