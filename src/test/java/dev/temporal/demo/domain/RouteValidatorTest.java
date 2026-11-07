package dev.temporal.demo.domain;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static dev.temporal.demo.domain.Model.*;
class RouteValidatorTest {
    TaskDefinition node(String id,List<String> deps,Condition condition) { return new TaskDefinition(id,id,"REVIEW",Kind.INTERNAL,"service-desk",null,deps,condition,List.of("success"),60); }
    Route route(TaskDefinition... tasks) { return new Route("test",1,"Test","MY_TYPE","",List.of(tasks)); }
    void validate(Route route) { RouteValidator.validate(route,Catalog.directory()); }
    @Test void seedRoutesAreValid() { Catalog.routes().forEach(this::validate); }
    @Test void cycleIsRejected() { assertThatThrownBy(() -> validate(route(node("a",List.of("b"),null),node("b",List.of("a"),null)))).hasMessageContaining("cycle"); }
    @Test void danglingDependencyIsRejected() { assertThatThrownBy(() -> validate(route(node("a",List.of("missing"),null)))).hasMessageContaining("dependency"); }
    @Test void invalidBranchOutcomeIsRejected() { assertThatThrownBy(() -> validate(route(node("a",List.of(),null),node("b",List.of("a"),new Condition("a","wrong"))))).hasMessageContaining("outcome"); }
    @Test void duplicateIdsAreRejected() { assertThatThrownBy(() -> validate(route(node("a",List.of(),null),node("a",List.of(),null)))).hasMessageContaining("Duplicate"); }
    @Test void queryCannotDispatchCommandOperation() {
        assertThatThrownBy(() -> validate(route(new TaskDefinition("a","A","TYPE",Kind.EXTERNAL_QUERY,null,"ISSUE_REFUND",List.of(),null,List.of("success"),0)))).hasMessageContaining("operation");
    }
}
