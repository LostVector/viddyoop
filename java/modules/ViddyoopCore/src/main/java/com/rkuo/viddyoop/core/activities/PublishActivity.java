package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.pipeline.EncodeResult;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Publish stage: verify the encode, move it into the library, write stats,
 * and only then delete the original input. The old system deleted the
 * original right after fire-and-forget job submission; this activity is the
 * first point where deletion is allowed.
 */
@ActivityInterface
public interface PublishActivity {
    @ActivityMethod
    void publish(PipelineInput input, EncodeResult enc);
}
