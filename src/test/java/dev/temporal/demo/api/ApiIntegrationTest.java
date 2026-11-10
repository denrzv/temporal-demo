package dev.temporal.demo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.temporal.demo.domain.Catalog;
import dev.temporal.demo.persistence.RequestRepository;
import dev.temporal.demo.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static dev.temporal.demo.domain.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"demo.temporal.enabled=false","spring.datasource.url=jdbc:h2:mem:api;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RouteService routes;
    @Autowired RequestService requests;
    @Autowired RequestRepository repository;
    @Autowired Json json;
    @Test void servesUiAndSeededRoutes() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Service operations")));
        mvc.perform(get("/api/routes")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }
    @Test void savesNewVersionWhileExistingRequestKeepsItsSnapshot() {
        Route original = routes.get("custom");
        String id = requests.create(new RequestService.Create("custom","Original snapshot","CUST-1",Map.of())).id;
        Route changed = new Route(original.id(),original.version(),"Changed route",original.requestType(),original.description(),original.tasks());
        Route saved = routes.save(changed,false);
        assertThat(saved.version()).isEqualTo(original.version()+1);
        StartRequest input = json.read(repository.findById(id).orElseThrow().workflowInput,StartRequest.class);
        assertThat(input.route().name()).isEqualTo(original.name());
        assertThat(input.route().version()).isEqualTo(original.version());
        assertThat(repository.findById(id).orElseThrow().dispatched).isFalse();
        assertThatThrownBy(() -> routes.save(changed,false)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }
    @Test void rejectsBadRequestAndUnknownRoute() throws Exception {
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("{\"routeId\":\"custom\",\"title\":\"\",\"customerRef\":\"C-1\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/requests/missing")).andExpect(status().isNotFound());
    }
    @Test void commandsReturnSameReceiptOnRedelivery() throws Exception {
        ExternalCall call = new ExternalCall("SR-1","refund","C-1","ISSUE_REFUND",Kind.EXTERNAL_COMMAND,Map.of(),Map.of(),"SR-1:refund");
        String first = mvc.perform(post("/mock/systems/commands/ISSUE_REFUND").header("Idempotency-Key",call.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/mock/systems/commands/ISSUE_REFUND").header("Idempotency-Key",call.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(second)).isEqualTo(mapper.readTree(first));
    }
    @Test void concurrentCommandRedeliveriesShareOneReceipt() throws Exception {
        ExternalCall call = new ExternalCall("SR-concurrent","refund","C-1","ISSUE_REFUND",Kind.EXTERNAL_COMMAND,Map.of(),Map.of(),"SR-concurrent:refund");
        String body = mapper.writeValueAsString(call);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i=0;i<8;i++) futures.add(executor.submit(() -> {
                start.await();
                return mvc.perform(post("/mock/systems/commands/ISSUE_REFUND").header("Idempotency-Key",call.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            }));
            start.countDown();
            var references = new java.util.HashSet<String>();
            for (var future : futures) references.add(mapper.readTree(future.get()).get("data").get("reference").asText());
            assertThat(references).hasSize(1);
        }
    }
    @Test void mockQueriesAndSimulatedOutageCrossHttpContracts() throws Exception {
        mvc.perform(get("/mock/systems/queries/LOOKUP_CUSTOMER").param("customerRef","C-42"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerRef").value("C-42"));
        ExternalCall call = new ExternalCall("SR-2","provision","C-1","PROVISION_SERVICE",Kind.EXTERNAL_COMMAND,Map.of("simulateFailure","true"),Map.of(),"SR-2:provision");
        mvc.perform(post("/mock/systems/commands/PROVISION_SERVICE").header("Idempotency-Key",call.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call))).andExpect(status().isServiceUnavailable());
    }
}
