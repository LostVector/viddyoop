package com.rkuo.viddyoop.core.worker;

import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class PipelineHeartbeatTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void readinessExpiresAndRejectsInvalidSignals() throws Exception {
        Path work = temp.getRoot().toPath();
        Assert.assertFalse(PipelineHeartbeat.fresh(work, System.currentTimeMillis()));
        PipelineHeartbeat.write(work);
        Assert.assertTrue(PipelineHeartbeat.fresh(work, System.currentTimeMillis()));
        Path signal = work.resolve(".pipeline-heartbeat");
        Files.writeString(signal, "1000");
        Assert.assertFalse(PipelineHeartbeat.fresh(work, 31001));
        Assert.assertFalse(PipelineHeartbeat.fresh(work, 999));
        Files.writeString(signal, Long.toString(Long.MIN_VALUE));
        Assert.assertFalse(PipelineHeartbeat.fresh(work, 1000));
        Files.writeString(signal, "partial");
        Assert.assertFalse(PipelineHeartbeat.fresh(work, 1000));
        PipelineHeartbeat.clear(work);
        Assert.assertFalse(Files.exists(signal));
    }
    @Test public void healthRequiresHeartbeatAndReachableTemporal() throws Exception {
        var health = new Viddyoop.Health();
        health.work = temp.getRoot().toPath(); health.target = "127.0.0.1:1";
        Assert.assertEquals(Integer.valueOf(1), health.call());
        PipelineHeartbeat.write(health.work);
        Assert.assertEquals(Integer.valueOf(1), health.call());
    }
}
