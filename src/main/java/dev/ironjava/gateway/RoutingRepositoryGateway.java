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

@Component
public class RoutingRepositoryGateway implements RepositoryGateway {
    private final GithubAppClient github;

    RoutingRepositoryGateway(GithubAppClient github) {
        this.github = github;
    }

    @Override
    public String resolveBaseSha(ResolvedRepository repository) {
        if (repository.source() == SourceKind.LOCAL) {
            return localSha(repository);
        }
        return github.resolveBaseSha(repository);
    }

    @Override
    public SourceSnapshot fetch(ResolvedRepository repository, String sha) {
        if (repository.source() == SourceKind.LOCAL) {
            String current = localSha(repository);
            if (!current.equals(sha)) {
                throw new StaleBaseException();
            }
            try {
                Path copy = Files.createTempDirectory("ironjava-src-");
                copyTree(repository.localPath(), copy);
                return new SourceSnapshot(sha, copy);
            } catch (IOException exception) {
                throw new RepositoryAccessException("local copy failed");
            }
        }
        return github.fetch(repository, sha);
    }

    @Override
    public PublishResult publish(ResolvedRepository repository, PublishRequest request) {
        if (!publishConfigured(repository)) {
            throw new PublicationNotConfiguredException();
        }
        return github.publish(repository, request);
    }

    @Override
    public boolean publishConfigured(ResolvedRepository repository) {
        return repository.canPublish() && github.configured();
    }

    private String localSha(ResolvedRepository repository) {
        Path path = repository.localPath();
        if (path == null || !Files.isDirectory(path) || !Files.isRegularFile(path.resolve("pom.xml"))) {
            throw new RepositoryAccessException("local repository path is not readable");
        }
        if (Files.isDirectory(path.resolve(".git"))) {
            String branch = repository.allowedBranch();
            if (!branch.matches("[A-Za-z0-9._/\\-]+")) {
                throw new RepositoryAccessException("allowed branch is not a safe ref");
            }
            ProcessCommand.Result result = ProcessCommand.run(
                    List.of("git", "-C", path.toString(), "rev-parse", "refs/heads/" + branch),
                    path, Duration.ofSeconds(15), () -> false, 2000);
            if (result.exitCode() != 0 || result.output().isBlank()) {
                throw new RepositoryAccessException("git rev-parse failed");
            }
            return result.output().trim().split("\\s+")[0];
        }
        try {
            return "local-" + dev.ironjava.orchestrator.Digests.sha256(Files.readString(path.resolve("pom.xml"))).substring(0, 16);
        } catch (IOException exception) {
            throw new RepositoryAccessException("pom unreadable");
        }
    }

    public static void copyTree(Path source, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                if (relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString())) {
                    continue;
                }
                Path target = dest.resolve(relative).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("path escape");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}

