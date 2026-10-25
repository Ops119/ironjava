package dev.ironjava.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExtensionGuard {
    private ExtensionGuard() {
    }

    public static String rejection(Path workspace, String pom) {
        if (Files.exists(workspace.resolve(".mvn/extensions.xml"))
                || Files.exists(workspace.resolve(".mvn/maven.config"))) {
            return "MVN_DIRECTORY";
        }
        if (pom != null && pom.replaceAll("\\s+", "").toLowerCase().contains("<extensions>true</extensions>")) {
            return "PLUGIN_EXTENSIONS";
        }
        return null;
    }

    public static String readPom(Path workspace) throws IOException {
        Path pom = workspace.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        return Files.readString(pom);
    }
}
