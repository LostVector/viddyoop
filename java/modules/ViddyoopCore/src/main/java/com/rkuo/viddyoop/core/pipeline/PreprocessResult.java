package com.rkuo.viddyoop.core.pipeline;

/** Result of the preprocess stage (remux, scan, DTS->AC3, normalize). */
public class PreprocessResult {
    /** Normalized intermediate file the encode stage should consume. */
    public String stagedPath;
    public String checksum;
    public java.util.List<Subtitle> subtitles = new java.util.ArrayList<>();

    public static class Subtitle {
        public String path;
        public String checksum;
        public String language;
        public boolean defaultTrack;
    }
    /** Duration of the main video track in milliseconds, from the scan. */
    public long durationMs;
    public int width;
    public int height;
    /** True if a DTS track was converted to AC3 during preprocessing. */
    public boolean audioConverted;
}
