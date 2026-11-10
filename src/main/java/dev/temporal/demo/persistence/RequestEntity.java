package dev.temporal.demo.persistence;
import jakarta.persistence.*;
@Entity @Table(name = "service_requests")
public class RequestEntity {
    @Id public String id;
    public String title;
    public String customerRef;
    public String routeId;
    public int routeVersion;
    public String requestType;
    public long createdAt;
    public boolean dispatched;
    @Lob @Column(nullable = false) public String workflowInput;
    public RequestEntity() {}
}
