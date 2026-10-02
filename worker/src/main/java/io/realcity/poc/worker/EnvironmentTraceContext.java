package io.realcity.poc.worker;

import java.util.Locale;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

/**
 * Continues the trace passed in environment variables, as the OpenTelemetry spec's environment-variable
 * carrier describes: the propagator's field names, upper-cased (TRACEPARENT, TRACESTATE, BAGGAGE).
 * Spring Boot doesn't do this on its own; it only extracts trace context from incoming requests.
 */
@Component
class EnvironmentTraceContext {

    private final Tracer tracer;
    private final Propagator propagator;

    EnvironmentTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** A span builder whose parent is the env var's trace context, or a new root if there is none. */
    Span.Builder spanBuilder() {
        Map<String, String> env = System.getenv();
        if (env.get("TRACEPARENT") == null) {
            return tracer.spanBuilder();
        }
        return propagator.extract(env, (carrier, key) -> carrier.get(key.toUpperCase(Locale.ROOT)));
    }
}
