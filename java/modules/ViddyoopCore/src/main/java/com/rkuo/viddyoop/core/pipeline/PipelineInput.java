package com.rkuo.viddyoop.core.pipeline;

/**
 * Input to a VideoPipelineWorkflow. Plain public fields are serialized to
 * JSON by the Temporal SDK across activity boundaries.
 */
public class PipelineInput {
    /** Original video file as staged by the ingest watcher. */
    public String sourcePath;
    /** Desired final library path for the finished encode. */
    public String finalPath;
    /**
     * Temporal task queue priority key (higher runs first when the queue is
     * backed up; 0 uses the default). Replaces the old "-p1" filename
     * convention.
     */
    public int precedence;

    public PipelineInput() {
    }

    public PipelineInput(String sourcePath, String finalPath, int precedence) {
        this.sourcePath = sourcePath;
        this.finalPath = finalPath;
        this.precedence = precedence;
    }
}
