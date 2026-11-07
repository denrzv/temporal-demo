package dev.temporal.demo.persistence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RequestRepository extends JpaRepository<RequestEntity, String> {
    List<RequestEntity> findTop100ByOrderByCreatedAtDesc();
    List<RequestEntity> findTop100ByDispatchedFalseOrderByCreatedAtAsc();
}
