package io.realcity.poc.caller;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

/**
 * Submits the diamond workflow through the Argo Server REST API, passing the current
 * trace context to it as {@code opentelemetry.io/<field>} annotations.
 */
@Component
class WorkflowSubmitter {

    /** The annotation prefix the patched workflow-controller reads the trace context from. */
    static final String OTEL_ANNOTATION_PREFIX = "opentelemetry.io/";

    private static final Logger log = LoggerFactory.getLogger(WorkflowSubmitter.class);

    private final RestClient argo;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Resource workflowTemplate;
    private final String namespace;

    WorkflowSubmitter(RestClient.Builder builder, Tracer tracer, Propagator propagator,
                      @Value("${argo.server-url}") String argoServerUrl,
                      @Value("${argo.namespace}") String namespace,
                      @Value("classpath:diamond.yaml") Resource workflowTemplate) {
        this.argo = builder.baseUrl(argoServerUrl).build();
        this.tracer = tracer;
        this.propagator = propagator;
        this.namespace = namespace;
        this.workflowTemplate = workflowTemplate;
    }

    /** @return the name of the submitted workflow */
    String submit(String job) {
        Span span = tracer.nextSpan().name("submit-workflow").tag("job", job).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Map<String, Object> workflow = loadWorkflow();
            Map<String, String> annotations = annotations(workflow);
            propagator.inject(span.context(), annotations,
                    (carrier, key, value) -> carrier.put(OTEL_ANNOTATION_PREFIX + key, value));
            setParameter(workflow, "job", job);
            log.info("Submitting workflow for job {} with annotations {}", job, annotations);

            Map<?, ?> created = argo.post()
                    .uri("/api/v1/workflows/{namespace}", namespace)
                    .body(Map.of("workflow", workflow))
                    .retrieve()
                    .body(Map.class);
            String name = (String) ((Map<?, ?>) created.get("metadata")).get("name");
            log.info("Submitted workflow {} for job {}", name, job);
            return name;
        } finally {
            span.end();
        }
    }

    private Map<String, Object> loadWorkflow() {
        try (InputStream in = workflowTemplate.getInputStream()) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read workflow template", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> annotations(Map<String, Object> workflow) {
        Map<String, Object> metadata = (Map<String, Object>) workflow.get("metadata");
        return (Map<String, String>) metadata.computeIfAbsent("annotations", k -> new HashMap<String, String>());
    }

    @SuppressWarnings("unchecked")
    private static void setParameter(Map<String, Object> workflow, String name, String value) {
        Map<String, Object> spec = (Map<String, Object>) workflow.get("spec");
        Map<String, Object> arguments = (Map<String, Object>) spec.get("arguments");
        for (Map<String, Object> parameter : (List<Map<String, Object>>) arguments.get("parameters")) {
            if (name.equals(parameter.get("name"))) {
                parameter.put("value", value);
            }
        }
    }
}
