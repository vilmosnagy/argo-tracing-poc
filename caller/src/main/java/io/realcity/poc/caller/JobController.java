package io.realcity.poc.caller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final WorkflowSubmitter submitter;

    JobController(WorkflowSubmitter submitter) {
        this.submitter = submitter;
    }

    /** Starts a job: submits the workflow inside the trace of this request. */
    @PostMapping("/jobs/{job}")
    Map<String, String> start(@PathVariable String job) {
        log.info("Job {} requested", job);
        String workflow = submitter.submit(job);
        return Map.of("job", job, "workflow", workflow);
    }

    /** Called back by the last step of the workflow. */
    @PostMapping("/callbacks/finished")
    void finished(@RequestParam String workflow, @RequestParam String job) {
        log.info("Workflow {} finished job {}", workflow, job);
    }
}
