package io.realcity.poc.worker;

import java.math.BigInteger;
import java.util.List;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class WorkerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    private final Tracer tracer;
    private final EnvironmentTraceContext traceContext;
    private final FibonacciCalculation fibonacci;

    WorkerRunner(Tracer tracer, EnvironmentTraceContext traceContext, FibonacciCalculation fibonacci) {
        this.tracer = tracer;
        this.traceContext = traceContext;
        this.fibonacci = fibonacci;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("TRACEPARENT={} (no trace context in MDC yet)", System.getenv("TRACEPARENT"));
        // --count=N, set by the workflow step; one second and one child span per number
        int count = args.containsOption("count") ? Integer.parseInt(args.getOptionValues("count").getFirst()) : 20;
        Span span = traceContext.spanBuilder().name("worker-run").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            log.info("continuing the workflow's trace: {} Fibonacci numbers", count);
            List<BigInteger> numbers = fibonacci.fibonacci(count);
            fibonacci.summary(numbers);
        } finally {
            span.end();
        }
    }
}
