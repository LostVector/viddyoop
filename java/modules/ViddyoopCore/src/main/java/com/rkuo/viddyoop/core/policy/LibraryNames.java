package com.rkuo.viddyoop.core.policy;

import java.nio.file.Path;

/** Presentation policy only; names never carry workflow identity or scheduler state. */
public final class LibraryNames {
    private LibraryNames() {}
    public static String outputName(Path source) {
        String name = source.getFileName().toString();
        int extension = name.lastIndexOf('.');
        if (extension <= 0) throw new IllegalArgumentException("Media filename needs an extension");
        String stem = name.substring(0, extension);
        if (stem.endsWith(".r")) stem = stem.substring(0, stem.length() - 2);
        if (stem.endsWith("-p1-AC3")) stem = stem.substring(0, stem.length() - 7) + "-AC3-p1";
        if (stem.endsWith("-p1") && !stem.startsWith("AAA-")) stem = "AAA-" + stem;
        return stem + ".mp4";
    }
}
