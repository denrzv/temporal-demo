package dev.temporal.demo.workflow;

import io.temporal.workflow.*;
import static dev.temporal.demo.domain.Model.*;

@WorkflowInterface
public interface ServiceRequestWorkflow {
    @WorkflowMethod RequestState run(StartRequest request);
    @QueryMethod RequestState state();
    @UpdateMethod void claim(TaskAction action);
    @UpdateMethod void complete(TaskAction action);
    @UpdateMethod void reassign(Assignment assignment);
    @UpdateMethod void cancel();
}
