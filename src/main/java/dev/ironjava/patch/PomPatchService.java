package dev.ironjava.patch;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Replaces one explicit dependency version in the root POM.
 * External entities and DOCTYPE declarations are rejected. Only the approved version text is rewritten.
 */
public final class PomPatchService {
    public record Patch(String pom, int versionStart, int versionEnd, String previousVersion) {
    }

    public Patch apply(String pom, String groupId, String artifactId, String newVersion) {
        Objects.requireNonNull(newVersion, "newVersion");
        if (newVersion.isBlank() || newVersion.contains("<") || newVersion.contains(">") || newVersion.contains("&")) {
            throw new PatchRejectedException("INVALID_VERSION_TEXT");
        }
        rejectDoctype(pom);
        Document document = parse(pom);
        Element project = document.getDocumentElement();
        if (project == null || !"project".equals(localName(project))) {
            throw new PatchRejectedException("NOT_A_PROJECT");
        }
        Element dependencies = directChild(project, "dependencies");
        if (dependencies == null) {
            throw new PatchRejectedException("NO_ROOT_DEPENDENCIES");
        }
        Element match = null;
        int matches = 0;
        for (Element dependency : directChildren(dependencies, "dependency")) {
            if (groupId.equals(text(directChild(dependency, "groupId")))
                    && artifactId.equals(text(directChild(dependency, "artifactId")))) {
                matches++;
                match = dependency;
            }
        }
        if (matches != 1 || match == null) {
            throw new PatchRejectedException(matches == 0 ? "DEPENDENCY_NOT_FOUND" : "UNCERTAIN_VERSION_OWNERSHIP");
        }
        Element version = directChild(match, "version");
        if (version == null) {
            throw new PatchRejectedException("BOM_OR_PARENT_MANAGED");
        }
        String current = text(version);
        if (current == null || current.isBlank()) {
            throw new PatchRejectedException("BOM_OR_PARENT_MANAGED");
        }
        if (current.startsWith("${") || current.contains("${")) {
            throw new PatchRejectedException("UNSUPPORTED_VERSION_SOURCE");
        }
        int[] span = locateVersionText(pom, groupId, artifactId);
        String updated = pom.substring(0, span[0]) + newVersion + pom.substring(span[1]);
        if (!pom.equals(updated.substring(0, span[0]) + current + updated.substring(span[0] + newVersion.length()))) {
            throw new PatchRejectedException("REPLACEMENT_MISMATCH");
        }
        assertSemanticChange(pom, updated, groupId, artifactId, current, newVersion);
        return new Patch(updated, span[0], span[0] + newVersion.length(), current);
    }

    private void assertSemanticChange(String before, String after, String groupId, String artifactId,
                                      String previous, String next) {
        Model left = model(parse(before));
        Model right = model(parse(after));
        if (!left.parent.equals(right.parent) || !left.properties.equals(right.properties)
                || !left.repositories.equals(right.repositories) || !left.plugins.equals(right.plugins)
                || !left.profiles.equals(right.profiles) || !left.managed.equals(right.managed)) {
            throw new PatchRejectedException("UNEXPECTED_XML_CHANGE");
        }
        if (left.dependencies.size() != right.dependencies.size()) {
            throw new PatchRejectedException("UNEXPECTED_XML_CHANGE");
        }
        boolean changed = false;
        for (int i = 0; i < left.dependencies.size(); i++) {
            Dep a = left.dependencies.get(i);
            Dep b = right.dependencies.get(i);
            if (!a.groupId.equals(b.groupId) || !a.artifactId.equals(b.artifactId) || !a.scope.equals(b.scope)) {
                throw new PatchRejectedException("UNEXPECTED_XML_CHANGE");
            }
            if (!a.version.equals(b.version)) {
                if (changed || !a.groupId.equals(groupId) || !a.artifactId.equals(artifactId)
                        || !a.version.equals(previous) || !b.version.equals(next)) {
                    throw new PatchRejectedException("UNEXPECTED_XML_CHANGE");
                }
                changed = true;
            }
        }
        if (!changed) {
            throw new PatchRejectedException("VERSION_UNCHANGED");
        }
    }

    private int[] locateVersionText(String pom, String groupId, String artifactId) {
        List<Tag> tags = scan(pom);
        int depth = 0;
        boolean inDependencies = false;
        int dependenciesDepth = -1;
        boolean inDependency = false;
        int dependencyDepth = -1;
        StringBuilder text = new StringBuilder();
        String seenGroup = null;
        String seenArtifact = null;
        int versionStart = -1;
        int versionEnd = -1;
        boolean capturing = false;
        String capturingName = null;
        int foundStart = -1;
        int foundEnd = -1;
        for (Tag tag : tags) {
            if (tag.kind == Kind.START) {
                depth++;
                if ("dependencies".equals(tag.name) && depth == 2) {
                    inDependencies = true;
                    dependenciesDepth = depth;
                } else if (inDependencies && "dependency".equals(tag.name) && depth == dependenciesDepth + 1) {
                    inDependency = true;
                    dependencyDepth = depth;
                    seenGroup = null;
                    seenArtifact = null;
                    versionStart = -1;
                    versionEnd = -1;
                } else if (inDependency && depth == dependencyDepth + 1
                        && ("groupId".equals(tag.name) || "artifactId".equals(tag.name) || "version".equals(tag.name))) {
                    capturing = true;
                    capturingName = tag.name;
                    text.setLength(0);
                    if ("version".equals(tag.name)) {
                        versionStart = tag.textStart;
                    }
                } else {
                    capturing = false;
                }
            } else if (tag.kind == Kind.END) {
                if (capturing && tag.name.equals(capturingName)) {
                    String value = text.toString().trim();
                    if ("groupId".equals(capturingName)) {
                        seenGroup = value;
                    } else if ("artifactId".equals(capturingName)) {
                        seenArtifact = value;
                    } else if ("version".equals(capturingName)) {
                        versionEnd = tag.textStart;
                    }
                    capturing = false;
                }
                if (inDependency && "dependency".equals(tag.name) && depth == dependencyDepth) {
                    if (groupId.equals(seenGroup) && artifactId.equals(seenArtifact)) {
                        if (versionStart < 0 || versionEnd < versionStart) {
                            throw new PatchRejectedException("BOM_OR_PARENT_MANAGED");
                        }
                        if (foundStart >= 0) {
                            throw new PatchRejectedException("UNCERTAIN_VERSION_OWNERSHIP");
                        }
                        String raw = pom.substring(versionStart, versionEnd);
                        String trimmed = raw.trim();
                        int lead = raw.indexOf(trimmed);
                        if (lead < 0 || trimmed.isBlank()) {
                            throw new PatchRejectedException("BOM_OR_PARENT_MANAGED");
                        }
                        foundStart = versionStart + lead;
                        foundEnd = foundStart + trimmed.length();
                    }
                    inDependency = false;
                }
                if (inDependencies && "dependencies".equals(tag.name) && depth == dependenciesDepth) {
                    inDependencies = false;
                }
                depth--;
            } else if (capturing && tag.kind == Kind.TEXT) {
                text.append(pom, tag.textStart, tag.textEnd);
            }
        }
        if (foundStart < 0) {
            throw new PatchRejectedException("DEPENDENCY_NOT_FOUND");
        }
        return new int[]{foundStart, foundEnd};
    }

    private enum Kind {START, END, TEXT}

    private record Tag(Kind kind, String name, int textStart, int textEnd) {
    }

    private List<Tag> scan(String xml) {
        List<Tag> tags = new ArrayList<>();
        int i = 0;
        int length = xml.length();
        while (i < length) {
            if (xml.charAt(i) == '<') {
                if (xml.startsWith("<!--", i)) {
                    int end = xml.indexOf("-->", i + 4);
                    if (end < 0) {
                        throw new PatchRejectedException("MALFORMED_XML");
                    }
                    i = end + 3;
                    continue;
                }
                if (xml.startsWith("<![CDATA[", i)) {
                    int end = xml.indexOf("]]>", i + 9);
                    if (end < 0) {
                        throw new PatchRejectedException("MALFORMED_XML");
                    }
                    tags.add(new Tag(Kind.TEXT, null, i + 9, end));
                    i = end + 3;
                    continue;
                }
                if (xml.startsWith("<?", i)) {
                    int end = xml.indexOf("?>", i + 2);
                    if (end < 0) {
                        throw new PatchRejectedException("MALFORMED_XML");
                    }
                    i = end + 2;
                    continue;
                }
                int end = indexOfTagEnd(xml, i + 1);
                if (end < 0) {
                    throw new PatchRejectedException("MALFORMED_XML");
                }
                String raw = xml.substring(i + 1, end).trim();
                boolean close = raw.startsWith("/");
                boolean self = raw.endsWith("/");
                String body = raw;
                if (close) {
                    body = raw.substring(1).trim();
                }
                if (self && body.endsWith("/")) {
                    body = body.substring(0, body.length() - 1).trim();
                }
                String name = elementName(body);
                if (close) {
                    tags.add(new Tag(Kind.END, name, i, end));
                } else if (!self) {
                    int textStart = end + 1;
                    tags.add(new Tag(Kind.START, name, textStart, textStart));
                }
                i = end + 1;
            } else {
                int next = xml.indexOf('<', i);
                if (next < 0) {
                    next = length;
                }
                tags.add(new Tag(Kind.TEXT, null, i, next));
                i = next;
            }
        }
        return tags;
    }

    private int indexOfTagEnd(String xml, int from) {
        boolean quote = false;
        char quoteChar = 0;
        for (int i = from; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote) {
                if (c == quoteChar) {
                    quote = false;
                }
            } else if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private String elementName(String body) {
        String trimmed = body.trim();
        int space = 0;
        while (space < trimmed.length() && !Character.isWhitespace(trimmed.charAt(space))) {
            space++;
        }
        String token = trimmed.substring(0, space);
        int colon = token.indexOf(':');
        return colon >= 0 ? token.substring(colon + 1) : token;
    }

    static void rejectDoctype(String xml) {
        String lower = xml.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw new PatchRejectedException("DOCTYPE_OR_ENTITY");
        }
    }

    public static Document parse(String xml) {
        rejectDoctype(xml);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("external entity refused");
            });
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (PatchRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PatchRejectedException("XML_PARSE_FAILED");
        }
    }

    private Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private List<Element> directChildren(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(localName(element))) {
                found.add(element);
            }
        }
        return found;
    }

    private String localName(Element element) {
        String name = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String text(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }

    private record Dep(String groupId, String artifactId, String version, String scope) {
    }

    private record Model(String parent, List<String> properties, List<String> repositories, List<String> plugins,
                         List<String> profiles, List<Dep> managed, List<Dep> dependencies) {
    }

    private Model model(Document document) {
        Element project = document.getDocumentElement();
        Element parent = directChild(project, "parent");
        String parentId = parent == null ? "" : text(directChild(parent, "groupId")) + ":"
                + text(directChild(parent, "artifactId")) + ":" + text(directChild(parent, "version"));
        List<String> properties = new ArrayList<>();
        Element propertiesElement = directChild(project, "properties");
        if (propertiesElement != null) {
            NodeList nodes = propertiesElement.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element element) {
                    properties.add(localName(element) + "=" + element.getTextContent().trim());
                }
            }
        }
        return new Model(parentId, properties, section(project, "repositories", "repository", "url"),
                plugins(project), profiles(project), managed(project), dependencies(project));
    }

    private List<String> section(Element project, String container, String child, String field) {
        List<String> values = new ArrayList<>();
        Element holder = directChild(project, container);
        if (holder == null) {
            return values;
        }
        for (Element element : directChildren(holder, child)) {
            values.add(text(directChild(element, field)));
        }
        return values;
    }

    private List<String> plugins(Element project) {
        List<String> values = new ArrayList<>();
        Element build = directChild(project, "build");
        if (build == null) {
            return values;
        }
        Element plugins = directChild(build, "plugins");
        if (plugins == null) {
            return values;
        }
        for (Element plugin : directChildren(plugins, "plugin")) {
            values.add(text(directChild(plugin, "groupId")) + ":" + text(directChild(plugin, "artifactId")) + ":"
                    + text(directChild(plugin, "version")) + ":" + text(directChild(plugin, "extensions")));
        }
        return values;
    }

    private List<String> profiles(Element project) {
        List<String> values = new ArrayList<>();
        Element profiles = directChild(project, "profiles");
        if (profiles == null) {
            return values;
        }
        for (Element profile : directChildren(profiles, "profile")) {
            values.add(profile.getTextContent().replaceAll("\\s+", " ").trim());
        }
        return values;
    }

    private List<Dep> managed(Element project) {
        Element management = directChild(project, "dependencyManagement");
        if (management == null) {
            return List.of();
        }
        Element dependencies = directChild(management, "dependencies");
        return dependencies == null ? List.of() : deps(dependencies);
    }

    private List<Dep> dependencies(Element project) {
        Element dependencies = directChild(project, "dependencies");
        return dependencies == null ? List.of() : deps(dependencies);
    }

    private List<Dep> deps(Element dependencies) {
        List<Dep> values = new ArrayList<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            String scope = text(directChild(dependency, "scope"));
            values.add(new Dep(
                    text(directChild(dependency, "groupId")),
                    text(directChild(dependency, "artifactId")),
                    text(directChild(dependency, "version")) == null ? "" : text(directChild(dependency, "version")),
                    scope == null ? "" : scope));
        }
        return values;
    }
}
