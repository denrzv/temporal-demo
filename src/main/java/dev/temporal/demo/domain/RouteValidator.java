package dev.temporal.demo.domain;

import java.util.*;
import static dev.temporal.demo.domain.Model.*;

public final class RouteValidator {
    private RouteValidator() {}
    public static void validate(Route route, Directory directory) {
        require(route != null, "Route is required");
        require(validId(route.id()), "Route id must contain lowercase letters, numbers or hyphens (max 64)");
        require(text(route.name()) && route.name().length() <= 160, "Route name is required (max 160)");
        require(text(route.requestType()) && route.requestType().length() <= 64, "Request type is required (max 64)");
        require(route.tasks() != null && !route.tasks().isEmpty() && route.tasks().size() <= 50, "Use 1 to 50 tasks");
        Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
        for (TaskDefinition t : route.tasks()) {
            require(t != null && validId(t.id()), "Each task needs a valid id");
            require(tasks.putIfAbsent(t.id(), t) == null, "Duplicate task: " + t.id());
            require(text(t.name()) && t.name().length() <= 160 && text(t.type()), "Task name and type are required");
            require(t.kind() != null && t.dependsOn() != null, "Task kind and dependencies are required");
            require(new HashSet<>(t.dependsOn()).size() == t.dependsOn().size(), "Duplicate dependency: " + t.id());
            require(t.outcomes() != null && !t.outcomes().isEmpty() && t.outcomes().stream().allMatch(RouteValidator::text)
                && new HashSet<>(t.outcomes()).size() == t.outcomes().size(), "Unique, nonempty outcomes are required");
            if (t.kind() == Kind.INTERNAL) {
                require(directory.groups().stream().anyMatch(g -> g.id().equals(t.groupId()) && g.taskTypes().contains(t.type())), "Group cannot handle task type: " + t.id());
                require(t.slaSeconds() >= 1 && t.slaSeconds() <= 31536000, "Internal SLA must be 1 second to 1 year");
            } else {
                require((t.kind() == Kind.EXTERNAL_QUERY ? Catalog.QUERY_OPERATIONS : Catalog.COMMAND_OPERATIONS).contains(t.operation()), "Unknown operation for task kind: " + t.id());
                require(t.outcomes().equals(List.of("success")), "External tasks have a success outcome");
            }
        }
        for (TaskDefinition t : route.tasks()) {
            for (String dep : t.dependsOn()) require(tasks.containsKey(dep) && !dep.equals(t.id()), "Missing or self dependency: " + t.id());
            if (t.when() != null) {
                require(t.dependsOn().contains(t.when().taskId()), "Condition must reference a direct dependency: " + t.id());
                require(tasks.get(t.when().taskId()).outcomes().contains(t.when().outcome()), "Unknown condition outcome: " + t.id());
            }
        }
        Set<String> visited = new HashSet<>();
        while (visited.size() < tasks.size()) {
            int before = visited.size();
            for (TaskDefinition t : route.tasks()) if (visited.containsAll(t.dependsOn())) visited.add(t.id());
            require(visited.size() > before, "Route contains a dependency cycle");
        }
    }
    private static boolean validId(String s) { return s != null && s.matches("[a-z0-9][a-z0-9-]{0,63}"); }
    public static boolean text(String s) { return s != null && !s.isBlank(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
