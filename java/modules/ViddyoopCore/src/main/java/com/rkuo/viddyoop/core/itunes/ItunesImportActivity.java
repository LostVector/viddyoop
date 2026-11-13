package com.rkuo.viddyoop.core.itunes;

import io.temporal.activity.*;

@ActivityInterface
public interface ItunesImportActivity {
    @ActivityMethod ItunesDeliveryCatalog.Delivery process(String submissionId);
}
