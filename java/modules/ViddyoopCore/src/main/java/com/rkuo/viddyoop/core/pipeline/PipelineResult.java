package com.rkuo.viddyoop.core.pipeline;

/** Result of a completed VideoPipelineWorkflow. */
public class PipelineResult {
    public String finalPath;
    public long durationMs;
    public int width;
    public int height;
    public long encodeDurationMs;
}
