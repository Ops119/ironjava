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
class MavenCentralMetadataClient implements MavenMetadataClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public Result list(String groupId, String artifactId) {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        URI uri = URI.create("https://repo1.maven.org/maven2/" + path);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Result(false, List.of());
            }
            Document document = PomPatchService.parse(response.body());
            NodeList nodes = document.getElementsByTagName("version");
            List<String> versions = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element element && "version".equals(element.getTagName())) {
                    String parent = element.getParentNode() == null ? "" : element.getParentNode().getNodeName();
                    if ("versions".equals(parent)) {
                        versions.add(element.getTextContent().trim());
                    }
                }
            }
            return new Result(true, versions);
        } catch (Exception exception) {
            return new Result(false, List.of());
        }
    }
}
