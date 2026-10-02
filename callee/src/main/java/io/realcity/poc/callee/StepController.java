package io.realcity.poc.callee;

import java.util.List;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StepController {

    private static final Logger log = LoggerFactory.getLogger(StepController.class);

    private final PrimeCalculation calculation;
    private final ObservationRegistry observations;

    StepController(PrimeCalculation calculation, ObservationRegistry observations) {
        this.calculation = calculation;
        this.observations = observations;
    }

    /** One processing step of the workflow; the incoming traceparent is continued automatically. */
    @PostMapping("/steps/{step}")
    Map<String, Object> step(@PathVariable String step, @RequestParam(defaultValue = "20") int count,
                             @RequestParam String workflow, @RequestParam String job,
                             @RequestHeader(name = "traceparent", required = false) String traceparent) {
        log.info("Step {} of workflow {} for job {}: {} primes (incoming traceparent {})",
                step, workflow, job, count, traceparent);
        // the current observation is the HTTP request's; makes it searchable in Tempo, e.g. { span.argo.step = "B" }
        observations.getCurrentObservation()
                .lowCardinalityKeyValue("argo.step", step) // few values: also a tag on the http.server.requests metric
                .highCardinalityKeyValue("argo.workflow", workflow)
                .highCardinalityKeyValue("job", job);
        List<Long> primes = calculation.primes(count); // one second and one child span per prime
        calculation.summary(primes);
        return Map.of("step", step, "status", "done", "primes", primes);
    }
}
