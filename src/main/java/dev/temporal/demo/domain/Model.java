package dev.temporal.demo.domain;

import java.util.*;

/** These plain data contracts are also serialized into Temporal history. */
public final class Model {
    private Model() {}
    public enum Kind { INTERNAL, EXTERNAL_QUERY, EXTERNAL_COMMAND }
    public enum TaskStatus { PENDING, RUNNING, WAITING, COMPLETED, SKIPPED, FAILED, CANCELLED }
    public record Condition(String taskId, String outcome) {}
    public record TaskDefinition(String id, String name, String type, Kind kind, String groupId,
                                 String operation, List<String> dependsOn, Condition when,
                                 List<String> outcomes, long slaSeconds) {}
    public record Route(String id, int version, String name, String requestType,
                        String description, List<TaskDefinition> tasks) {}
    public record Agent(String id, String name, List<String> groupIds, List<String> taskTypes) {}
    public record Group(String id, String name, List<String> taskTypes) {}
    public record Directory(List<Group> groups, List<Agent> agents) {}
    public record StartRequest(String id, String title, String customerRef, Map<String, String> data,
                               Route route, Directory directory) {}
    public record TaskAction(String taskId, Agent actor, String outcome, String note) {}
    public record Assignment(String taskId, String groupId) {}
    public record Event(long timestamp, String taskId, String message) {}
    public record ExternalCall(String requestId, String taskId, String customerRef,
                               String operation, Kind kind, Map<String, String> data,
                               Map<String, Map<String, String>> dependencyResults, String idempotencyKey) {}
    public record ExternalResult(Map<String, String> data) {}

    public static class TaskState {
        public TaskDefinition definition;
        public TaskStatus status = TaskStatus.PENDING;
        public String groupId;
        public String assignee;
        public String outcome;
        public String note;
        public boolean slaBreached;
        public Long startedAt;
        public Long completedAt;
        public Map<String, String> result = new LinkedHashMap<>();
        public TaskState() {}
        public TaskState(TaskDefinition definition) {
            this.definition = definition;
            this.groupId = definition.groupId();
        }
    }
    public static class RequestState {
        public String id;
        public String title;
        public String customerRef;
        public Route route;
        public String status = "RUNNING";
        public long createdAt;
        public Long completedAt;
        public List<TaskState> tasks = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public RequestState() {}
    }
}
