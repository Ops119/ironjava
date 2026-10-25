package dev.ironjava.policy;

import dev.ironjava.domain.Domain.VersionSource;
import dev.ironjava.patch.PomPatchService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

public final class PomInspector {
    public record DirectDependency(
            String groupId,
            String artifactId,
            String version,
            String scope,
            VersionSource versionSource
    ) {
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    public List<DirectDependency> directDependencies(String pom) {
        Document document = PomPatchService.parse(pom);
        Element project = document.getDocumentElement();
        Element dependencies = child(project, "dependencies");
        if (dependencies == null) {
            return List.of();
        }
        List<DirectDependency> found = new ArrayList<>();
        for (Element dependency : children(dependencies, "dependency")) {
            String groupId = text(child(dependency, "groupId"));
            String artifactId = text(child(dependency, "artifactId"));
            Element version = child(dependency, "version");
            String scope = text(child(dependency, "scope"));
            if (scope == null || scope.isBlank()) {
                scope = "compile";
            }
            VersionSource source;
            String versionText = null;
            if (version == null || version.getTextContent().isBlank()) {
                source = VersionSource.MANAGED;
            } else {
                versionText = version.getTextContent().trim();
                source = versionText.contains("${") ? VersionSource.PROPERTY : VersionSource.EXPLICIT;
            }
            if (groupId != null && artifactId != null) {
                found.add(new DirectDependency(groupId, artifactId, versionText, scope, source));
            }
        }
        return found;
    }

    public String dependencyExcerpt(String pom, String groupId, String artifactId) {
        int start = pom.indexOf("<dependency>");
        while (start >= 0) {
            int end = pom.indexOf("</dependency>", start);
            if (end < 0) {
                break;
            }
            String block = pom.substring(start, end + "</dependency>".length());
            if (block.contains("<groupId>" + groupId + "</groupId>")
                    && block.contains("<artifactId>" + artifactId + "</artifactId>")) {
                return block;
            }
            start = pom.indexOf("<dependency>", end);
        }
        return "";
    }

    private Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                return element;
            }
        }
        return null;
    }

    private List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                found.add(element);
            }
        }
        return found;
    }

    private String text(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }
}
