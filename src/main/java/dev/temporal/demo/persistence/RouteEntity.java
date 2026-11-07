package dev.temporal.demo.persistence;
import jakarta.persistence.*;
@Entity @Table(name = "route_templates")
public class RouteEntity {
    @Id public String id;
    public int routeVersion;
    @Version public long lockVersion;
    @Lob @Column(nullable = false) public String definition;
    public RouteEntity() {}
}
