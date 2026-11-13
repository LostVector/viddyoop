package com.rkuo.viddyoop.core.ingest;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.HexFormat;

/** Copies stable input into owned storage; never renames, modifies, or deletes inbox media. */
public final class SourceStager {
    public record Snapshot(Path original, Path staged, String fingerprint, String generation) {}
    private final Path root;
    private final SourceFilesystem.Reader filesystem;

    public SourceStager(Path work) throws IOException {
        this(work, SourceFilesystem::inspect);
    }
    SourceStager(Path work, SourceFilesystem.Reader filesystem) throws IOException {
        this.filesystem = filesystem;
        root = work.toAbsolutePath().normalize().resolve("ingest");
        Files.createDirectories(root);
    }

    public Snapshot stage(Path source) throws IOException {
        Path original = source.toAbsolutePath().normalize();
        BasicFileAttributes before = attributes(original);
        var epoch = filesystem.read(original);
        Path temporary = Files.createTempFile(root, ".copy-", ".tmp");
        try {
            MessageDigest digest = digest();
            try (InputStream in = Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS);
                 OutputStream out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            BasicFileAttributes after = attributes(original);
            if (!same(before, after) || !epoch.equals(filesystem.read(original)) || Files.size(temporary) != before.size()) {
                throw new IOException("Source changed during staging: " + original);
            }
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            String generation = generation(original, before, fingerprint, epoch.epoch());
            // Retain the extension so media tools can infer the container if necessary.
            String name = original.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot);
            Path staged = root.resolve(generation + extension);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Same-generation copies have identical bytes. Atomic replacement never exposes
            // partial data to a reader, even if two discovery polls stage concurrently.
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            }
            return new Snapshot(original, staged, fingerprint, generation);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Compare an isolated file using its original inbox identity, including replacement metadata. */
    public static boolean matches(Path actual, Path original, String fingerprint, String generation, Runnable heartbeat)
            throws IOException {
        return matches(actual, original, fingerprint, generation, heartbeat, SourceFilesystem::inspect);
    }
    static boolean matches(Path actual, Path original, String fingerprint, String generation, Runnable heartbeat,
                           SourceFilesystem.Reader filesystem) throws IOException {
        BasicFileAttributes before = attributes(actual);
        var epoch = filesystem.read(actual);
        String actualFingerprint = com.rkuo.viddyoop.core.media.MediaFiles.checksum(actual, heartbeat);
        BasicFileAttributes after = attributes(actual);
        return before.fileKey() != null && same(before, after) && epoch.equals(filesystem.read(actual)) && fingerprint.equals(actualFingerprint)
                && generation.equals(generation(original.toAbsolutePath().normalize(), before, actualFingerprint, epoch.epoch()));
    }

    private static String generation(Path original, BasicFileAttributes attributes, String fingerprint, String epoch) {
        return hash(original + "\n" + attributes.fileKey() + "\n" + attributes.creationTime()
                + "\n" + attributes.lastModifiedTime() + "\n" + attributes.size() + "\n" + fingerprint
                + (epoch.isEmpty() ? "" : "\nshared-boot:" + epoch));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Expected regular media file: " + path);
        return attrs;
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return java.util.Objects.equals(a.fileKey(), b.fileKey()) && a.size() == b.size()
                && a.lastModifiedTime().equals(b.lastModifiedTime()) && a.creationTime().equals(b.creationTime());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
