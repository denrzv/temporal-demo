package dev.temporal.demo.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RouteRepository extends JpaRepository<RouteEntity, String> {}
