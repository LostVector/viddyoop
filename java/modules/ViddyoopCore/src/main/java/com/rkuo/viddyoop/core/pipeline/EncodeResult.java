package com.rkuo.viddyoop.core.pipeline;

/** Result of the encode stage. */
public class EncodeResult {
    /** Local path of the finished encode, awaiting publish. */
    public String encodedPath;
    /** Wall-clock encoding time in milliseconds. */
    public long encodeDurationMs;
    /** Host that performed the encode (single machine today, kept for stats). */
    public String machineName;
}
