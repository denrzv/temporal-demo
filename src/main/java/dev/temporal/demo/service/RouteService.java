package dev.temporal.demo.service;
import dev.temporal.demo.domain.*;
import dev.temporal.demo.persistence.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static dev.temporal.demo.domain.Model.*;
@Service
public class RouteService implements ApplicationRunner {
    private final RouteRepository routes;
    private final Json json;
    public RouteService(RouteRepository routes, Json json) { this.routes = routes; this.json = json; }
    @Override @Transactional public void run(ApplicationArguments args) {
        for (Route route : Catalog.routes()) if (!routes.existsById(route.id())) save(route, true);
    }
    public List<Route> list() {
        return routes.findAll().stream().map(r -> json.read(r.definition, Route.class)).sorted(Comparator.comparing(Route::id)).toList();
    }
    public Route get(String id) { return json.read(routes.findById(id).orElseThrow(() -> new NoSuchElementException("Route not found")).definition, Route.class); }
    @Transactional public Route save(Route route, boolean create) {
        RouteValidator.validate(route, Catalog.directory());
        RouteEntity entity;
        if (create) {
            if (routes.existsById(route.id())) throw new IllegalArgumentException("Route id already exists");
            entity = new RouteEntity(); entity.id = route.id(); entity.routeVersion = 1;
        } else {
            entity = routes.findById(route.id()).orElseThrow(() -> new NoSuchElementException("Route not found"));
            if (route.version() != entity.routeVersion) throw new org.springframework.dao.OptimisticLockingFailureException("Route changed; reload before saving");
            entity.routeVersion++;
        }
        Route saved = new Route(route.id(), entity.routeVersion, route.name(), route.requestType(), route.description(), List.copyOf(route.tasks()));
        entity.definition = json.write(saved);
        routes.saveAndFlush(entity);
        return saved;
    }
}
