package com.rkuo.viddyoop.core.itunes;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import java.time.Duration;
import java.util.List;

public final class ItunesIndexSchedule {
    private ItunesIndexSchedule() {}
    public static void ensure(ScheduleClient client, String id, String queue, Duration interval) {
        if (id.isBlank() || queue.isBlank() || interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Invalid index schedule");
        var action = ScheduleActionStartWorkflow.newBuilder().setWorkflowType(ItunesIndexWorkflow.class)
                .setOptions(WorkflowOptions.newBuilder().setTaskQueue(queue).setWorkflowId(id + "-run").build()).build();
        var spec = ScheduleSpec.newBuilder().setIntervals(List.of(new ScheduleIntervalSpec(interval))).build();
        var policy = SchedulePolicy.newBuilder().setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP).build();
        var schedule = Schedule.newBuilder().setAction(action).setSpec(spec).setPolicy(policy).build();
        try { client.createSchedule(id, schedule, ScheduleOptions.newBuilder().setTriggerImmediately(true).build()); }
        catch (ScheduleAlreadyRunningException e) {
            client.getHandle(id).update(input -> {
                var existing = input.getDescription().getSchedule();
                if (!(existing.getAction() instanceof ScheduleActionStartWorkflow old)
                        || !old.getWorkflowType().equals(action.getWorkflowType())) {
                    throw new IllegalArgumentException("Schedule id belongs to a different workflow");
                }
                // Preserve paused state and operator notes across worker restarts.
                return new ScheduleUpdate(Schedule.newBuilder(existing).setAction(action).setSpec(spec).setPolicy(policy).build());
            });
        }
    }
}
