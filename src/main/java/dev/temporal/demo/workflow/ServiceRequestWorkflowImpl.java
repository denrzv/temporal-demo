package dev.temporal.demo.workflow;

import java.time.Duration;
import java.util.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.*;
import io.temporal.workflow.*;
import static dev.temporal.demo.domain.Model.*;

/** Workflow code uses only deterministic state and Temporal primitives. All I/O is in activities. */
public class ServiceRequestWorkflowImpl implements ServiceRequestWorkflow {
    private final SystemActivities systems = Workflow.newActivityStub(SystemActivities.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(15))
            .setScheduleToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofSeconds(10)).setMaximumAttempts(5)
                .setDoNotRetry("INVALID_COMMAND").build()).build());
    private RequestState state;
    private StartRequest input;
    private CancellationScope execution;

    @Override public RequestState run(StartRequest input) {
        this.input = input;
        state = new RequestState();
        state.id = input.id(); state.title = input.title(); state.customerRef = input.customerRef();
        state.route = input.route(); state.createdAt = Workflow.currentTimeMillis();
        input.route().tasks().forEach(t -> state.tasks.add(new TaskState(t)));
        event(null, "Request started · " + input.route().name() + " v" + input.route().version());
        execution = Workflow.newCancellationScope(this::executePlan);
        try {
            execution.run();
            state.status = state.tasks.stream().anyMatch(t -> t.status == TaskStatus.FAILED) ? "FAILED" : "COMPLETED";
        } catch (CanceledFailure e) {
            state.status = "CANCELLED";
            state.tasks.stream().filter(t -> !terminal(t)).forEach(t -> t.status = TaskStatus.CANCELLED);
        }
        state.completedAt = Workflow.currentTimeMillis();
        event(null, "Request " + state.status.toLowerCase(Locale.ROOT));
        return state;
    }
    private void executePlan() {
        while (state.tasks.stream().anyMatch(t -> !terminal(t))) {
            for (TaskState task : state.tasks) {
                if (!ready(task)) continue;
                boolean blocked = task.definition.dependsOn().stream().map(this::task)
                    .anyMatch(t -> t.status != TaskStatus.COMPLETED);
                Condition when = task.definition.when();
                if (blocked || (when != null && !when.outcome().equals(task(when.taskId()).outcome))) {
                    task.status = TaskStatus.SKIPPED;
                    event(task.definition.id(), blocked ? "Skipped: dependency did not complete" : "Skipped: branch condition did not match");
                } else {
                    task.status = TaskStatus.RUNNING;
                    task.startedAt = Workflow.currentTimeMillis();
                    Async.procedure(() -> executeTask(task));
                }
            }
            Workflow.await(() -> state.tasks.stream().allMatch(this::terminal) || state.tasks.stream().anyMatch(this::ready));
        }
    }
    private void executeTask(TaskState task) {
        String id = task.definition.id();
        if (task.definition.kind() == Kind.INTERNAL) {
            task.status = TaskStatus.WAITING;
            event(id, "Waiting for agent · " + task.groupId);
            boolean done = Workflow.await(Duration.ofSeconds(task.definition.slaSeconds()), () -> terminal(task));
            if (!done) {
                task.slaBreached = true;
                event(id, "SLA exceeded · task remains available to the assigned group");
                Workflow.await(() -> terminal(task));
            }
        } else {
            event(id, "Calling " + task.definition.operation());
            try {
                // Stable across activity retries and worker restarts; remote command handlers must deduplicate it.
                ExternalResult result = systems.execute(new ExternalCall(state.id, id, input.customerRef(),
                    task.definition.operation(), task.definition.kind(), input.data(), dependencyResults(task), state.id + ":" + id));
                task.result = result.data();
                task.outcome = "success";
                task.status = TaskStatus.COMPLETED;
                task.completedAt = Workflow.currentTimeMillis();
                event(id, "External " + (task.definition.kind() == Kind.EXTERNAL_QUERY ? "query" : "command") + " completed");
            } catch (ActivityFailure e) {
                task.status = TaskStatus.FAILED;
                task.note = "External system call failed after its retry policy was exhausted";
                event(id, task.note);
            }
        }
    }
    @Override public RequestState state() { return state; }

    @Override public void claim(TaskAction action) {
        TaskState task = available(action.taskId());
        authorize(task, action.actor());
        require(task.assignee == null || task.assignee.equals(action.actor().id()), "Task is claimed by another agent");
        if (task.assignee == null) {
            task.assignee = action.actor().id();
            event(action.taskId(), "Claimed by " + action.actor().name());
        }
    }
    @UpdateValidatorMethod(updateName = "claim") public void validateClaim(TaskAction action) {
        TaskState task = available(action.taskId()); authorize(task, action.actor());
        require(task.assignee == null || task.assignee.equals(action.actor().id()), "Task is claimed by another agent");
    }
    @Override public void complete(TaskAction action) {
        validateComplete(action);
        TaskState task = task(action.taskId());
        task.outcome = action.outcome(); task.note = action.note();
        task.result = Map.of("outcome", action.outcome(), "completedBy", action.actor().id(), "note", action.note() == null ? "" : action.note());
        task.status = TaskStatus.COMPLETED; task.completedAt = Workflow.currentTimeMillis();
        event(action.taskId(), "Completed by " + action.actor().name() + " · " + action.outcome());
    }
    @UpdateValidatorMethod(updateName = "complete") public void validateComplete(TaskAction action) {
        TaskState task = available(action.taskId()); authorize(task, action.actor());
        require(action.actor().id().equals(task.assignee), "Claim the task before completing it");
        require(task.definition.outcomes().contains(action.outcome()), "Outcome is not allowed by this route");
        require(action.note() == null || action.note().length() <= 2000, "Note is too long");
    }
    @Override public void reassign(Assignment assignment) {
        validateReassign(assignment);
        TaskState task = task(assignment.taskId());
        task.groupId = assignment.groupId(); task.assignee = null;
        event(assignment.taskId(), "Routed to " + assignment.groupId() + " · previous claim released");
    }
    @UpdateValidatorMethod(updateName = "reassign") public void validateReassign(Assignment assignment) {
        TaskState task = available(assignment.taskId());
        require(input.directory().groups().stream().anyMatch(g -> g.id().equals(assignment.groupId()) && g.taskTypes().contains(task.definition.type())),
            "Group cannot process this task type");
    }
    @Override public void cancel() {
        validateCancel();
        event(null, "Cancellation requested");
        execution.cancel();
    }
    @UpdateValidatorMethod(updateName = "cancel") public void validateCancel() {
        require(state != null && "RUNNING".equals(state.status), "Request is not running");
    }
    private Map<String, Map<String, String>> dependencyResults(TaskState task) {
        Map<String, Map<String, String>> context = new LinkedHashMap<>();
        collectDependencies(task, context);
        return context;
    }
    private void collectDependencies(TaskState task, Map<String, Map<String, String>> context) {
        for (String id : task.definition.dependsOn()) {
            TaskState dep = task(id);
            if (context.containsKey(id)) continue;
            collectDependencies(dep, context);
            context.put(id, dep.result);
        }
    }
    private TaskState available(String id) {
        require(state != null && "RUNNING".equals(state.status), "Request is not running");
        TaskState task = task(id);
        require(task.definition.kind() == Kind.INTERNAL && task.status == TaskStatus.WAITING, "Task is not waiting for an agent");
        return task;
    }
    private void authorize(TaskState task, Agent actor) {
        require(actor != null && actor.groupIds().contains(task.groupId) && actor.taskTypes().contains(task.definition.type()), "Agent group or task type is not eligible");
    }
    private TaskState task(String id) {
        return state.tasks.stream().filter(t -> t.definition.id().equals(id)).findFirst()
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure("Unknown task: " + id, "INVALID_ACTION"));
    }
    private boolean terminal(TaskState t) { return switch(t.status) { case COMPLETED, SKIPPED, FAILED, CANCELLED -> true; default -> false; }; }
    private boolean ready(TaskState t) {
        return t.status == TaskStatus.PENDING && t.definition.dependsOn().stream().map(this::task).allMatch(this::terminal);
    }
    private void event(String id, String message) { state.events.add(new Event(Workflow.currentTimeMillis(), id, message)); }
    private void require(boolean condition, String message) {
        if (!condition) throw ApplicationFailure.newNonRetryableFailure(message, "INVALID_ACTION");
    }
}
