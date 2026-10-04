package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import com.rkuo.viddyoop.core.pipeline.PreprocessResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Normalization stage: mkvmerge remux, HandBrake scan validation, DTS->AC3
 * conversion, filename normalization. Replaces HBXJobPreprocessor.Execute.
 */
@ActivityInterface
public interface PreprocessActivity {
    @ActivityMethod
    PreprocessResult preprocess(PipelineInput input);
}
