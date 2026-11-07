package dev.temporal.demo.workflow;

import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "demo.temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfiguration {
    @Bean(destroyMethod = "shutdown") WorkflowServiceStubs temporalService(@Value("${demo.temporal.address}") String address) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
    }
    @Bean WorkflowClient workflowClient(WorkflowServiceStubs service, @Value("${demo.temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }
    @Bean(initMethod = "start", destroyMethod = "shutdown") WorkerFactory workerFactory(WorkflowClient client,
        HttpSystemActivities activities, @Value("${demo.temporal.task-queue}") String queue) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(queue);
        worker.registerWorkflowImplementationTypes(ServiceRequestWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return factory;
    }
}
