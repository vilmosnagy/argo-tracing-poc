package io.realcity.poc.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A run-once app, as a workflow step's main container. It continues the trace Argo hands over
 * in the TRACEPARENT env var, does ~20 seconds of work and exits.
 */
@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        // exit() closes the context, which flushes the span exporter before the pod ends
        System.exit(SpringApplication.exit(SpringApplication.run(WorkerApplication.class, args)));
    }
}
