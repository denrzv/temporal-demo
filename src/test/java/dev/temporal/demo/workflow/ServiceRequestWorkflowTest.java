package dev.temporal.demo.workflow;

import dev.temporal.demo.domain.Catalog;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.temporal.demo.domain.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ServiceRequestWorkflowTest {
    TestWorkflowEnvironment env;
    ServiceRequestWorkflow workflow;
    FakeSystems systems;
    @BeforeEach void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        var worker = env.newWorker("test");
        worker.registerWorkflowImplementationTypes(ServiceRequestWorkflowImpl.class);
        systems = new FakeSystems(); worker.registerActivitiesImplementations(systems);
        env.start();
        workflow = env.getWorkflowClient().newWorkflowStub(ServiceRequestWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue("test").setWorkflowId("test-" + UUID.randomUUID()).build());
    }
    @AfterEach void tearDown() { env.close(); }
    void start(String route) { start(Catalog.routes().stream().filter(r -> r.id().equals(route)).findFirst().orElseThrow()); }
    void start(Route route) { WorkflowClient.start(workflow::run, new StartRequest("SR-test", "Example", "CUST-1", Map.of(), route, Catalog.directory())); }
    TaskState task(String id) { return workflow.state().tasks.stream().filter(t -> t.definition.id().equals(id)).findFirst().orElseThrow(); }
    void waiting(String id) { await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() -> task(id).status == TaskStatus.WAITING); }
    Agent agent(String id) { return Catalog.directory().agents().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(); }
    TaskAction action(String task, String actor, String outcome) { return new TaskAction(task, agent(actor), outcome, "Checked"); }
    void finish(String task, String actor, String outcome) { waiting(task); workflow.claim(action(task,actor,null)); workflow.complete(action(task,actor,outcome)); }
    RequestState result() { return WorkflowStub.fromTyped(workflow).getResult(RequestState.class); }

    @Test void technicalRequestHandsWorkBetweenGroupsAndExecutesApprovedBranch() {
        start("technical");
        finish("triage", "alex", "success");
        finish("assessment", "sam", "approved");
        RequestState result = result();
        assertThat(result.status).isEqualTo("COMPLETED");
        assertThat(task("provision").status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task("rejection").status).isEqualTo(TaskStatus.SKIPPED);
        assertThat(systems.calls).extracting(ExternalCall::operation).containsExactly("LOOKUP_CUSTOMER","PROVISION_SERVICE");
    }
    @Test void claimWaitsForBothParallelQueriesThenExecutesRejectedBranch() {
        start("claim"); waiting("assessment");
        assertThat(task("customer").status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task("eligibility").status).isEqualTo(TaskStatus.COMPLETED);
        finish("assessment", "jordan", "rejected");
        assertThat(result().status).isEqualTo("COMPLETED");
        assertThat(task("refund").status).isEqualTo(TaskStatus.SKIPPED);
        assertThat(systems.calls).extracting(ExternalCall::operation).containsExactlyInAnyOrder("LOOKUP_CUSTOMER","CHECK_ELIGIBILITY","SEND_NOTIFICATION");
    }
    @Test void wrongGroupCannotClaimAndInvalidOutcomeCannotComplete() {
        start("technical"); waiting("triage");
        assertThatThrownBy(() -> workflow.claim(action("triage","sam",null))).isInstanceOf(WorkflowUpdateException.class);
        assertThatThrownBy(() -> workflow.complete(action("triage","alex","success"))).isInstanceOf(WorkflowUpdateException.class);
        workflow.claim(action("triage","alex",null));
        assertThatThrownBy(() -> workflow.complete(action("triage","alex","approved"))).isInstanceOf(WorkflowUpdateException.class);
        assertThat(task("triage").status).isEqualTo(TaskStatus.WAITING);
    }
    @Test void membershipWithoutTaskCapabilityIsInsufficient() {
        start("claim"); waiting("assessment");
        // Taylor belongs to claims but does not process CLAIM_ASSESSMENT.
        assertThatThrownBy(() -> workflow.claim(action("assessment","taylor",null))).isInstanceOf(WorkflowUpdateException.class);
    }
    @Test void onlyOneAgentCanOwnTaskAndReassignmentReleasesClaim() {
        Route route = new Route("review",1,"Review","CUSTOM","",List.of(
            new TaskDefinition("review","Review","REVIEW",Kind.INTERNAL,"claims",null,List.of(),null,List.of("success"),86400)));
        start(route); waiting("review");
        workflow.claim(action("review","jordan",null));
        workflow.claim(action("review","jordan",null)); // Idempotent claim.
        assertThatThrownBy(() -> workflow.claim(action("review","taylor",null))).isInstanceOf(WorkflowUpdateException.class);
        workflow.reassign(new Assignment("review","service-desk"));
        assertThat(task("review").assignee).isNull();
        assertThatThrownBy(() -> workflow.complete(action("review","jordan","success"))).isInstanceOf(WorkflowUpdateException.class);
        finish("review","alex","success");
        assertThat(result().status).isEqualTo("COMPLETED");
    }
    @Test void incompatibleGroupCannotReceiveTask() {
        start("technical"); waiting("triage");
        assertThatThrownBy(() -> workflow.reassign(new Assignment("triage","engineering"))).isInstanceOf(WorkflowUpdateException.class);
        assertThat(task("triage").groupId).isEqualTo("service-desk");
    }
    @Test void internalSlaUsesDurableTimerAndStillAllowsCompletion() {
        Route route = new Route("sla",1,"SLA","CUSTOM","",List.of(
            new TaskDefinition("review","Review","REVIEW",Kind.INTERNAL,"service-desk",null,List.of(),null,List.of("success"),60)));
        start(route); waiting("review");
        env.sleep(Duration.ofSeconds(61));
        assertThat(task("review").slaBreached).isTrue();
        finish("review","alex","success");
        assertThat(result().status).isEqualTo("COMPLETED");
    }
    @Test void transientExternalFailuresRetryWithTheSameIdempotencyKey() {
        systems.failuresRemaining.set(2);
        start(commandOnly());
        assertThat(result().status).isEqualTo("COMPLETED");
        assertThat(systems.calls).hasSize(3).extracting(ExternalCall::idempotencyKey).containsOnly("SR-test:command");
    }
    @Test void exhaustedRetriesFailRequestAndSkipDependants() {
        systems.failuresRemaining.set(10);
        Route route = new Route("failure",1,"Failure","CUSTOM","",List.of(commandOnly().tasks().getFirst(),
            new TaskDefinition("review","Review","REVIEW",Kind.INTERNAL,"service-desk",null,List.of("command"),null,List.of("success"),60)));
        start(route);
        assertThat(result().status).isEqualTo("FAILED");
        assertThat(systems.calls).hasSize(5);
        assertThat(task("review").status).isEqualTo(TaskStatus.SKIPPED);
    }
    @Test void permanentInputFailuresDoNotRetry() {
        systems.permanent = true; start(commandOnly());
        assertThat(result().status).isEqualTo("FAILED");
        assertThat(systems.calls).hasSize(1);
    }
    @Test void cancellationStopsWaitingAndPendingTasks() {
        start("custom"); waiting("review"); workflow.cancel();
        assertThat(result().status).isEqualTo("CANCELLED");
        assertThat(workflow.state().tasks).allMatch(t -> t.status == TaskStatus.CANCELLED);
        assertThat(systems.calls).isEmpty();
    }
    Route commandOnly() {
        return new Route("command",1,"Command","CUSTOM","",List.of(
            new TaskDefinition("command","Command","PROVISION_SERVICE",Kind.EXTERNAL_COMMAND,null,"PROVISION_SERVICE",List.of(),null,List.of("success"),0)));
    }
    public static class FakeSystems implements SystemActivities {
        final List<ExternalCall> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        boolean permanent;
        @Override public ExternalResult execute(ExternalCall call) {
            calls.add(call);
            if (permanent) throw ApplicationFailure.newNonRetryableFailure("Bad input","INVALID_COMMAND");
            if (failuresRemaining.getAndDecrement() > 0) throw new IllegalStateException("Temporary outage");
            return new ExternalResult(Map.of("status","ok"));
        }
    }
}
