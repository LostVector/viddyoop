package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.pipeline.EncodeResult;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import com.rkuo.viddyoop.core.pipeline.PreprocessResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The HandBrake encode. Long-running: implementations must call
 * Activity.getExecutionContext().heartbeat() from the HandBrake progress
 * callback (HBXExeHelper already parses "Encoding: ... %" lines) so the
 * server can detect dead workers via the heartbeat timeout instead of
 * killing healthy long encodes.
 */
@ActivityInterface
public interface EncodeActivity {
    @ActivityMethod
    EncodeResult encode(PipelineInput input, PreprocessResult pre);
}
