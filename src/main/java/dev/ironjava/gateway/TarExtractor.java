package dev.ironjava.gateway;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TarExtractor {
    private TarExtractor() {
    }

    public static void extractStrippingTopDirectory(byte[] gzipTar, Path dest) throws IOException {
        try (InputStream input = new ByteArrayInputStream(gzipTar);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                String relative = slash >= 0 ? name.substring(slash + 1) : name;
                if (relative.isBlank()) {
                    continue;
                }
                Path target = dest.resolve(relative).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("tar path escapes destination");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new IOException("tar links are refused");
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target);
                }
            }
        }
    }
}
