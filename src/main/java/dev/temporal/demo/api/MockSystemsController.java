package dev.temporal.demo.api;
import dev.temporal.demo.domain.Catalog;
import dev.temporal.demo.persistence.*;
import dev.temporal.demo.service.Json;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static dev.temporal.demo.domain.Model.*;
/** Local stand-in for CRM/billing/provisioning. Activities cross a real HTTP boundary. */
@RestController @RequestMapping("/mock/systems")
public class MockSystemsController {
    private final ReceiptRepository receipts;
    private final Json json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public MockSystemsController(ReceiptRepository receipts, Json json, org.springframework.jdbc.core.JdbcTemplate jdbc) { this.receipts = receipts; this.json = json; this.jdbc = jdbc; }
    @GetMapping("/queries/{operation}") public ExternalResult query(@PathVariable String operation, @RequestParam String customerRef) {
        if (!Catalog.QUERY_OPERATIONS.contains(operation)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new ExternalResult(operation.equals("LOOKUP_CUSTOMER")
            ? Map.of("customerRef", customerRef, "name", "Demo Customer", "accountStatus", "ACTIVE")
            : Map.of("customerRef", customerRef, "eligible", "true", "policy", "standard"));
    }
    @PostMapping("/commands/{operation}") public ExternalResult command(@PathVariable String operation,
        @RequestHeader("Idempotency-Key") String key, @RequestBody ExternalCall call) {
        if (!Catalog.COMMAND_OPERATIONS.contains(operation) || !operation.equals(call.operation()) || !key.equals(call.idempotencyKey()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Optional<CommandReceipt> existing = receipts.findById(key);
        if (existing.isPresent()) return json.read(existing.get().response, ExternalResult.class);
        if ("true".equals(call.data().get("simulateFailure"))) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated system outage");
        ExternalResult result = new ExternalResult(Map.of("operation", operation, "reference", UUID.randomUUID().toString(), "status", "ACCEPTED"));
        CommandReceipt receipt = new CommandReceipt(); receipt.id = key; receipt.response = json.write(result);
        try { jdbc.update("INSERT INTO command_receipts (id, response) VALUES (?, ?)", receipt.id, receipt.response); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { return json.read(receipts.findById(key).orElseThrow().response, ExternalResult.class); }
        return result;
    }
}
