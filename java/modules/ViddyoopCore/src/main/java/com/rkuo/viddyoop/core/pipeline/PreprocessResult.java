package com.rkuo.viddyoop.core.pipeline;

/** Result of the preprocess stage (remux, scan, DTS->AC3, normalize). */
public class PreprocessResult {
    /** Normalized intermediate file the encode stage should consume. */
    public String stagedPath;
    /** Duration of the main video track in milliseconds, from the scan. */
    public long durationMs;
    public int width;
    public int height;
    /** True if a DTS track was converted to AC3 during preprocessing. */
    public boolean audioConverted;
}
