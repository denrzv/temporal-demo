package dev.temporal.demo.service;
import dev.temporal.demo.domain.Catalog;
import dev.temporal.demo.persistence.*;
import dev.temporal.demo.workflow.ServiceRequestWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.*;
import java.util.*;
import static dev.temporal.demo.domain.Model.*;
@Service @EnableScheduling
public class RequestService {
    public record Create(String routeId, String title, String customerRef, Map<String, String> data) {}
    public record View(String id, String title, String customerRef, String requestType, String routeId,
                       int routeVersion, long createdAt, String status, RequestState execution, String error) {}
    private final RequestRepository requests;
    private final RouteService routes;
    private final Json json;
    private final ObjectProvider<WorkflowClient> clients;
    private final String queue;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestService.class);
    public RequestService(RequestRepository requests, RouteService routes, Json json, ObjectProvider<WorkflowClient> clients,
                          @Value("${demo.temporal.task-queue}") String queue) {
        this.requests = requests; this.routes = routes; this.json = json; this.clients = clients; this.queue = queue;
    }
    public RequestEntity create(Create create) {
        if (create.title() == null || create.title().isBlank() || create.title().length() > 160) throw new IllegalArgumentException("Title is required (max 160)");
        if (create.customerRef() == null || !create.customerRef().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Customer reference: use 1–64 letters, numbers, hyphens or underscores");
        if (create.data() != null && (create.data().size() > 20 || create.data().entrySet().stream().anyMatch(e -> e.getKey().length() > 64 || e.getValue() == null || e.getValue().length() > 2000)))
            throw new IllegalArgumentException("Request data is too large");
        Route route = routes.get(create.routeId());
        String id = "SR-" + UUID.randomUUID();
        StartRequest input = new StartRequest(id, create.title().trim(), create.customerRef(), create.data() == null ? Map.of() : create.data(), route, Catalog.directory());
        RequestEntity row = new RequestEntity();
        row.id = id; row.title = input.title(); row.customerRef = input.customerRef(); row.routeId = route.id();
        row.routeVersion = route.version(); row.requestType = route.requestType(); row.createdAt = System.currentTimeMillis(); row.workflowInput = json.write(input);
        return requests.saveAndFlush(row); // Durable start intent; dispatcher retries until Temporal accepts it.
    }
    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void dispatchPending() {
        WorkflowClient client = clients.getIfAvailable();
        if (client == null) return;
        for (RequestEntity row : requests.findTop100ByDispatchedFalseOrderByCreatedAtAsc()) {
            try {
                ServiceRequestWorkflow workflow = client.newWorkflowStub(ServiceRequestWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(row.id).setTaskQueue(queue)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
                try { WorkflowClient.start(workflow::run, json.read(row.workflowInput, StartRequest.class)); }
                catch (WorkflowExecutionAlreadyStarted ignored) { /* Start succeeded before DB acknowledgement. */ }
                row.dispatched = true;
                requests.saveAndFlush(row);
            } catch (Exception e) {
                log.warn("Temporal start pending for {}: {}", row.id, e.getClass().getSimpleName());
                break;
            }
        }
    }
    public List<View> list() { return requests.findTop100ByOrderByCreatedAtDesc().stream().map(this::view).toList(); }
    public View get(String id) { return view(row(id)); }
    private View view(RequestEntity row) {
        RequestState state = null;
        String error = null;
        if (row.dispatched) {
            try { state = workflow(row.id).state(); }
            catch (RuntimeException e) { error = "Workflow state is temporarily unavailable. Check Temporal and worker connectivity."; }
        }
        return new View(row.id, row.title, row.customerRef, row.requestType, row.routeId, row.routeVersion, row.createdAt,
            state != null ? state.status : row.dispatched ? "UNAVAILABLE" : "PENDING", state, error);
    }
    public ServiceRequestWorkflow workflow(String id) {
        row(id);
        WorkflowClient client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("Temporal is disabled");
        return client.newWorkflowStub(ServiceRequestWorkflow.class, id);
    }
    private RequestEntity row(String id) { return requests.findById(id).orElseThrow(() -> new NoSuchElementException("Request not found")); }
    public Agent agent(String id) {
        return Catalog.directory().agents().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown agent"));
    }
}
