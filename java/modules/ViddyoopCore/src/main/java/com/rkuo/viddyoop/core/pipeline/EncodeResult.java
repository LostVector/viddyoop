package com.rkuo.viddyoop.core.pipeline;

/** Result of the encode stage. */
public class EncodeResult {
    /** Local path of the finished encode, awaiting publish. */
    public String encodedPath;
    public String checksum;
    public long sourceDurationMs;
    public int sourceWidth;
    public int sourceHeight;
    public long durationMs;
    public int width;
    public int height;
    /** Wall-clock encoding time in milliseconds. */
    public long encodeDurationMs;
    /** Host that performed the encode (single machine today, kept for stats). */
    public String machineName;
}
