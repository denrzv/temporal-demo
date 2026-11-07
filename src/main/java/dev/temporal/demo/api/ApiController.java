package dev.temporal.demo.api;
import dev.temporal.demo.domain.Catalog;
import dev.temporal.demo.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static dev.temporal.demo.domain.Model.*;
@RestController @RequestMapping("/api")
public class ApiController {
    private final RouteService routes;
    private final RequestService requests;
    private final String uiUrl;
    private final String namespace;
    public ApiController(RouteService routes, RequestService requests, @Value("${demo.temporal.ui-url}") String uiUrl,
                         @Value("${demo.temporal.namespace}") String namespace) {
        this.routes = routes; this.requests = requests; this.uiUrl = uiUrl; this.namespace = namespace;
    }
    @GetMapping("/config") public Map<String, String> config() { return Map.of("temporalUiUrl", uiUrl, "namespace", namespace); }
    @GetMapping("/directory") public Directory directory() { return Catalog.directory(); }
    @GetMapping("/routes") public List<Route> routes() { return routes.list(); }
    @PostMapping("/routes") @ResponseStatus(HttpStatus.CREATED) public Route createRoute(@RequestBody Route route) { return routes.save(route, true); }
    @PutMapping("/routes/{id}") public Route updateRoute(@PathVariable String id, @RequestBody Route route) {
        if (!id.equals(route.id())) throw new IllegalArgumentException("Route id cannot change");
        return routes.save(route, false);
    }
    @GetMapping("/requests") public List<RequestService.View> requests() { return requests.list(); }
    @PostMapping("/requests") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String, String> create(@RequestBody RequestService.Create create) {
        return Map.of("id", requests.create(create).id, "status", "PENDING");
    }
    @GetMapping("/requests/{id}") public RequestService.View request(@PathVariable String id) { return requests.get(id); }
    public record Action(String agentId, String outcome, String note) {}
    public record Reassign(String groupId) {}
    @PostMapping("/requests/{id}/tasks/{taskId}/claim") public void claim(@PathVariable String id, @PathVariable String taskId, @RequestBody Action action) {
        requests.workflow(id).claim(new TaskAction(taskId, requests.agent(action.agentId()), null, null));
    }
    @PostMapping("/requests/{id}/tasks/{taskId}/complete") public void complete(@PathVariable String id, @PathVariable String taskId, @RequestBody Action action) {
        requests.workflow(id).complete(new TaskAction(taskId, requests.agent(action.agentId()), action.outcome(), action.note()));
    }
    @PostMapping("/requests/{id}/tasks/{taskId}/reassign") public void reassign(@PathVariable String id, @PathVariable String taskId, @RequestBody Reassign assignment) {
        requests.workflow(id).reassign(new Assignment(taskId, assignment.groupId()));
    }
    @PostMapping("/requests/{id}/cancel") public void cancel(@PathVariable String id) { requests.workflow(id).cancel(); }
}
