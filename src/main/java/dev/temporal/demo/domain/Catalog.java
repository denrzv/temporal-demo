package dev.temporal.demo.domain;

import java.util.*;
import static dev.temporal.demo.domain.Model.*;

public final class Catalog {
    private Catalog() {}
    public static final Set<String> QUERY_OPERATIONS = Set.of("LOOKUP_CUSTOMER", "CHECK_ELIGIBILITY");
    public static final Set<String> COMMAND_OPERATIONS = Set.of("PROVISION_SERVICE", "ISSUE_REFUND", "SEND_NOTIFICATION");
    public static Directory directory() {
        return new Directory(List.of(
            new Group("service-desk", "Service desk", List.of("TRIAGE", "REVIEW")),
            new Group("engineering", "Engineering", List.of("TECHNICAL_ASSESSMENT", "REVIEW")),
            new Group("claims", "Claims team", List.of("CLAIM_ASSESSMENT", "APPROVAL", "REVIEW")),
            new Group("operations", "Operations", List.of("FULFILLMENT", "APPROVAL", "REVIEW"))
        ), List.of(
            new Agent("alex", "Alex Morgan", List.of("service-desk"), List.of("TRIAGE", "REVIEW")),
            new Agent("sam", "Sam Chen", List.of("engineering"), List.of("TECHNICAL_ASSESSMENT", "REVIEW")),
            new Agent("jordan", "Jordan Lee", List.of("claims"), List.of("CLAIM_ASSESSMENT", "APPROVAL", "REVIEW")),
            new Agent("taylor", "Taylor Reed", List.of("operations", "claims"), List.of("FULFILLMENT", "APPROVAL", "REVIEW"))
        ));
    }
    public static List<Route> routes() {
        return List.of(
            new Route("technical", 1, "Technical service request", "TECH_REQUEST", "Verify a customer, triage, assess, then provision the service.", List.of(
                external("customer", "Look up customer", Kind.EXTERNAL_QUERY, "LOOKUP_CUSTOMER", List.of(), null),
                internal("triage", "Triage request", "TRIAGE", "service-desk", List.of("customer"), List.of("success")),
                internal("assessment", "Technical assessment", "TECHNICAL_ASSESSMENT", "engineering", List.of("triage"), List.of("approved", "rejected")),
                external("provision", "Provision service", Kind.EXTERNAL_COMMAND, "PROVISION_SERVICE", List.of("assessment"), new Condition("assessment", "approved")),
                external("rejection", "Send rejection notice", Kind.EXTERNAL_COMMAND, "SEND_NOTIFICATION", List.of("assessment"), new Condition("assessment", "rejected"))
            )),
            new Route("claim", 1, "Customer claim", "CLAIM", "Run customer and eligibility checks in parallel before a claims decision.", List.of(
                external("customer", "Look up customer", Kind.EXTERNAL_QUERY, "LOOKUP_CUSTOMER", List.of(), null),
                external("eligibility", "Check eligibility", Kind.EXTERNAL_QUERY, "CHECK_ELIGIBILITY", List.of(), null),
                internal("assessment", "Assess claim", "CLAIM_ASSESSMENT", "claims", List.of("customer", "eligibility"), List.of("approved", "rejected")),
                external("refund", "Issue refund", Kind.EXTERNAL_COMMAND, "ISSUE_REFUND", List.of("assessment"), new Condition("assessment", "approved")),
                external("rejection", "Send rejection notice", Kind.EXTERNAL_COMMAND, "SEND_NOTIFICATION", List.of("assessment"), new Condition("assessment", "rejected"))
            )),
            new Route("custom", 1, "General service request", "CUSTOM", "A starting point for your own request and task types.", List.of(
                internal("review", "Review request", "REVIEW", "service-desk", List.of(), List.of("success")),
                internal("fulfill", "Fulfill request", "FULFILLMENT", "operations", List.of("review"), List.of("success")),
                external("notify", "Notify customer", Kind.EXTERNAL_COMMAND, "SEND_NOTIFICATION", List.of("fulfill"), null)
            ))
        );
    }
    private static TaskDefinition internal(String id, String name, String type, String group, List<String> deps, List<String> outcomes) {
        return new TaskDefinition(id, name, type, Kind.INTERNAL, group, null, deps, null, outcomes, 86400);
    }
    private static TaskDefinition external(String id, String name, Kind kind, String operation, List<String> deps, Condition when) {
        return new TaskDefinition(id, name, operation, kind, null, operation, deps, when, List.of("success"), 0);
    }
}
