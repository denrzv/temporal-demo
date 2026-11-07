package dev.temporal.demo.workflow;

import dev.temporal.demo.domain.Catalog;
import io.temporal.failure.ApplicationFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import static dev.temporal.demo.domain.Model.*;

@Component
public class HttpSystemActivities implements SystemActivities {
    private final RestClient http;
    public HttpSystemActivities(@Value("${demo.systems-url}") String baseUrl) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    @Override public ExternalResult execute(ExternalCall call) {
        try {
            ExternalResult result;
            if (call.kind() == Kind.EXTERNAL_QUERY && Catalog.QUERY_OPERATIONS.contains(call.operation())) {
                result = http.get().uri("/queries/{operation}?customerRef={customerRef}", call.operation(), call.customerRef())
                    .retrieve().body(ExternalResult.class);
            } else if (call.kind() == Kind.EXTERNAL_COMMAND && Catalog.COMMAND_OPERATIONS.contains(call.operation())) {
                result = http.post().uri("/commands/{operation}", call.operation())
                    .header("Idempotency-Key", call.idempotencyKey()).body(call).retrieve().body(ExternalResult.class);
            } else throw ApplicationFailure.newNonRetryableFailure("Unknown operation", "INVALID_COMMAND");
            if (result == null || result.data() == null) throw new IllegalStateException("Empty external response");
            return result;
        } catch (HttpClientErrorException e) {
            // Rate limiting and request timeouts are retryable; permanent input errors are not.
            if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 408) throw e;
            throw ApplicationFailure.newNonRetryableFailure("External system rejected the request: " + e.getStatusCode(), "INVALID_COMMAND");
        }
    }
}
