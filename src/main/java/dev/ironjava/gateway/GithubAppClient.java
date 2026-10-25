package dev.ironjava.gateway;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ironjava.config.IronJavaProperties;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class GithubAppClient {
    private final IronJavaProperties properties;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile CachedToken token;

    @Autowired
    public GithubAppClient(IronJavaProperties properties) {
        this(properties, new HttpTransport(properties.getGithub().getApiBase()));
    }

    GithubAppClient(IronJavaProperties properties, Transport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    public boolean configured() {
        return appId() != null && !appId().isBlank() && privateKeyPem() != null && !privateKeyPem().isBlank();
    }

    public String resolveBaseSha(ResolvedRepository repository) {
        requireConfigured();
        String bearer = installationToken(repository.installationId());
        JsonNode branch = get("/repos/" + repository.slug() + "/branches/" + repository.allowedBranch(), bearer);
        JsonNode sha = branch.path("commit").path("sha");
        if (!sha.isTextual()) {
            throw new RepositoryAccessException("branch sha missing");
        }
        return sha.asText();
    }

    public SourceSnapshot fetch(ResolvedRepository repository, String sha) {
        requireConfigured();
        String bearer = installationToken(repository.installationId());
        byte[] archive = transport.bytes("GET", "/repos/" + repository.slug() + "/tarball/" + sha, bearer);
        try {
            Path dest = Files.createTempDirectory("ironjava-gh-");
            TarExtractor.extractStrippingTopDirectory(archive, dest);
            return new SourceSnapshot(sha, dest);
        } catch (IOException exception) {
            throw new RepositoryAccessException("archive extract failed");
        }
    }

    public PublishResult publish(ResolvedRepository repository, PublishRequest request) {
        requireConfigured();
        String bearer = installationToken(repository.installationId());
        try {
            return publishOnce(repository, request, bearer, true);
        } catch (AmbiguousTimeoutException timeout) {
            PublishResult existing = reconcile(repository, request, bearer);
            if (existing != null) {
                return existing;
            }
            return publishOnce(repository, request, bearer, false);
        }
    }

    private PublishResult publishOnce(ResolvedRepository repository, PublishRequest request, String bearer, boolean allowAmbiguous) {
        String current = resolveBaseSha(repository);
        if (!current.equals(request.baseSha())) {
            throw new StaleBaseException();
        }
        JsonNode baseCommit = get("/repos/" + repository.slug() + "/git/commits/" + request.baseSha(), bearer);
        String baseTree = baseCommit.path("tree").path("sha").asText();
        String blobSha = post(repository, "/git/blobs", bearer, blob(request.pomContents()), allowAmbiguous).path("sha").asText();
        String treeSha = post(repository, "/git/trees", bearer, tree(baseTree, request.pomPath(), blobSha), allowAmbiguous).path("sha").asText();
        String commitSha = post(repository, "/git/commits", bearer, commit(treeSha, request.baseSha(), request.title()), allowAmbiguous).path("sha").asText();
        int refStatus = transport.send("POST", "/repos/" + repository.slug() + "/git/refs", bearer, ref(request.branch(), commitSha), allowAmbiguous).status();
        if (refStatus == 422) {
            if (!pomMatches(repository, request, bearer)) {
                throw new PublishReconcileException("branch exists with different content");
            }
        } else if (refStatus >= 300) {
            throw new RepositoryAccessException("ref create failed");
        }
        return openDraft(repository, request, bearer, allowAmbiguous);
    }

    private PublishResult reconcile(ResolvedRepository repository, PublishRequest request, String bearer) {
        JsonNode pulls = get("/repos/" + repository.slug() + "/pulls?head=" + repository.githubOwner() + ":" + request.branch() + "&state=all", bearer);
        if (pulls.isArray() && !pulls.isEmpty()) {
            JsonNode pull = pulls.get(0);
            return new PublishResult(pull.path("html_url").asText(), pull.path("number").asInt(), request.branch(), true);
        }
        int ref = transport.send("GET", "/repos/" + repository.slug() + "/git/ref/heads/" + request.branch(), bearer, null, false).status();
        if (ref == 200) {
            if (!pomMatches(repository, request, bearer)) {
                throw new PublishReconcileException("existing branch does not match the verified patch");
            }
            return openDraft(repository, request, bearer, false);
        }
        return null;
    }

    private boolean pomMatches(ResolvedRepository repository, PublishRequest request, String bearer) {
        Transport.HttpResult result = transport.send("GET",
                "/repos/" + repository.slug() + "/contents/" + request.pomPath() + "?ref=" + request.branch(),
                bearer, null, false);
        if (result.status() != 200) {
            return false;
        }
        try {
            JsonNode node = mapper.readTree(result.body());
            if (!"base64".equals(node.path("encoding").asText())) {
                return false;
            }
            String content = new String(Base64.getMimeDecoder().decode(node.path("content").asText())).replace("\r\n", "\n");
            return content.equals(request.pomContents().replace("\r\n", "\n"));
        } catch (Exception exception) {
            return false;
        }
    }

    private PublishResult openDraft(ResolvedRepository repository, PublishRequest request, String bearer, boolean allowAmbiguous) {
        JsonNode pulls = get("/repos/" + repository.slug() + "/pulls?head=" + repository.githubOwner() + ":" + request.branch() + "&state=open", bearer);
        if (pulls.isArray() && !pulls.isEmpty()) {
            JsonNode pull = pulls.get(0);
            return new PublishResult(pull.path("html_url").asText(), pull.path("number").asInt(), request.branch(), true);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("title", request.title());
        body.put("head", request.branch());
        body.put("base", repository.allowedBranch());
        body.put("body", request.body());
        body.put("draft", true);
        JsonNode created = post(repository, "/pulls", bearer, body, allowAmbiguous);
        return new PublishResult(created.path("html_url").asText(), created.path("number").asInt(), request.branch(), false);
    }

    private JsonNode post(ResolvedRepository repository, String suffix, String bearer, JsonNode body, boolean allowAmbiguous) {
        Transport.HttpResult result = transport.send("POST", "/repos/" + repository.slug() + suffix, bearer, body.toString(), allowAmbiguous);
        if (result.status() >= 300) {
            throw new RepositoryAccessException("github write failed");
        }
        try {
            return mapper.readTree(result.body());
        } catch (IOException exception) {
            throw new RepositoryAccessException("github response unreadable");
        }
    }

    private JsonNode get(String path, String bearer) {
        Transport.HttpResult result = transport.send("GET", path, bearer, null, false);
        if (result.status() == 404) {
            throw new RepositoryAccessException("github resource not found");
        }
        if (result.status() >= 300) {
            throw new RepositoryAccessException("github read failed");
        }
        try {
            return mapper.readTree(result.body());
        } catch (IOException exception) {
            throw new RepositoryAccessException("github response unreadable");
        }
    }

    private ObjectNode blob(String contents) {
        ObjectNode node = mapper.createObjectNode();
        node.put("content", Base64.getEncoder().encodeToString(contents.getBytes()));
        node.put("encoding", "base64");
        return node;
    }

    private ObjectNode tree(String baseTree, String path, String blobSha) {
        ObjectNode node = mapper.createObjectNode();
        node.put("base_tree", baseTree);
        ArrayNode tree = node.putArray("tree");
        ObjectNode file = tree.addObject();
        file.put("path", path);
        file.put("mode", "100644");
        file.put("type", "blob");
        file.put("sha", blobSha);
        return node;
    }

    private ObjectNode commit(String tree, String parent, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("message", message);
        node.put("tree", tree);
        node.putArray("parents").add(parent);
        return node;
    }

    private String ref(String branch, String sha) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ref", "refs/heads/" + branch);
        node.put("sha", sha);
        return node.toString();
    }

    private synchronized String installationToken(long installationId) {
        if (token != null && token.installationId == installationId && token.expiresAt.isAfter(Instant.now().plusSeconds(60))) {
            return token.value;
        }
        String jwt = appJwt();
        Transport.HttpResult result = transport.send("POST", "/app/installations/" + installationId + "/access_tokens", jwt, "{}", false);
        if (result.status() >= 300) {
            throw new RepositoryAccessException("installation token request failed");
        }
        try {
            JsonNode node = mapper.readTree(result.body());
            token = new CachedToken(installationId, node.path("token").asText(), Instant.parse(node.path("expires_at").asText()));
            return token.value;
        } catch (Exception exception) {
            throw new RepositoryAccessException("installation token unreadable");
        }
    }

    private String appJwt() {
        try {
            RSAPrivateKey key = readKey(privateKeyPem());
            Algorithm algorithm = Algorithm.RSA256(null, key);
            Instant now = Instant.now();
            return JWT.create()
                    .withIssuer(appId())
                    .withIssuedAt(Date.from(now.minusSeconds(30)))
                    .withExpiresAt(Date.from(now.plusSeconds(8 * 60)))
                    .sign(algorithm);
        } catch (RepositoryAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RepositoryAccessException("app jwt failed");
        }
    }

    static RSAPrivateKey readKey(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof PEMKeyPair pair) {
                return (RSAPrivateKey) converter.getPrivateKey(pair.getPrivateKeyInfo());
            }
            if (object instanceof PrivateKeyInfo info) {
                return (RSAPrivateKey) converter.getPrivateKey(info);
            }
            throw new RepositoryAccessException("unsupported private key");
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new PublicationNotConfiguredException();
        }
    }

    private String appId() {
        return properties.getGithub().getAppId();
    }

    private String privateKeyPem() {
        String inline = properties.getGithub().getPrivateKey();
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String path = properties.getGithub().getPrivateKeyPath();
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(path));
        } catch (IOException exception) {
            throw new RepositoryAccessException("private key file unreadable");
        }
    }

    private record CachedToken(long installationId, String value, Instant expiresAt) {
    }

    interface Transport {
        HttpResult send(String method, String path, String bearer, String body, boolean ambiguousOnTimeout);

        default byte[] bytes(String method, String path, String bearer) {
            throw new RepositoryAccessException("binary download is not available");
        }

        record HttpResult(int status, String body) {
        }
    }

    static final class AmbiguousTimeoutException extends RuntimeException {
        AmbiguousTimeoutException() {
            super("ambiguous github timeout");
        }
    }

    static final class HttpTransport implements Transport {
        private final String apiBase;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpTransport(String apiBase) {
            this.apiBase = apiBase == null || apiBase.isBlank() ? "https://api.github.com" : apiBase.replaceAll("/$", "");
        }

        @Override
        public HttpResult send(String method, String path, String bearer, String body, boolean ambiguousOnTimeout) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-GitHub-Api-Version", "2022-11-28");
                if ("GET".equals(method)) {
                    builder.GET();
                } else {
                    builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                }
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return new HttpResult(response.statusCode(), response.body());
            } catch (IOException exception) {
                if (ambiguousOnTimeout) {
                    throw new AmbiguousTimeoutException();
                }
                throw new RepositoryAccessException("github transport failed");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RepositoryAccessException("github transport interrupted");
            }
        }

        @Override
        public byte[] bytes(String method, String path, String bearer) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 300) {
                    throw new RepositoryAccessException("tarball download failed");
                }
                return response.body();
            } catch (IOException exception) {
                throw new RepositoryAccessException("tarball download failed");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RepositoryAccessException("tarball download interrupted");
            }
        }
    }
}
