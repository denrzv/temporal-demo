package dev.temporal.demo.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReceiptRepository extends JpaRepository<CommandReceipt, String> {}
