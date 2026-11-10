package dev.temporal.demo.workflow;

import io.temporal.activity.*;
import static dev.temporal.demo.domain.Model.*;

@ActivityInterface
public interface SystemActivities {
    @ActivityMethod ExternalResult execute(ExternalCall call);
}
