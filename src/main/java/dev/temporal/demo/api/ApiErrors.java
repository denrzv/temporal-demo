package dev.temporal.demo.api;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> badRequest(IllegalArgumentException e) { return error(400, e.getMessage()); }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing(NoSuchElementException e) { return error(404, e.getMessage()); }
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class}) public ResponseEntity<?> conflict(Exception e) { return error(409, "Data changed or id already exists. Reload and try again."); }
    @ExceptionHandler(WorkflowUpdateException.class) public ResponseEntity<?> update(WorkflowUpdateException e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        return error(409, cause instanceof ApplicationFailure a ? a.getOriginalMessage() : "Workflow rejected the action. Refresh its state.");
    }
    @ExceptionHandler(WorkflowNotFoundException.class) public ResponseEntity<?> unavailable(WorkflowNotFoundException e) { return error(409, "Workflow is not running or has not started yet"); }
    @ExceptionHandler({WorkflowServiceException.class, IllegalStateException.class}) public ResponseEntity<?> service(Exception e) { return error(503, "Temporal is unavailable. Check the server and worker, then retry."); }
    private ResponseEntity<?> error(int status, String message) { return ResponseEntity.status(status).body(Map.of("message", message)); }
}
