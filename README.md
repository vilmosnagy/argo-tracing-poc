# Argo Workflows: continuing a caller's trace

This repo demonstrates a proposed Argo Workflows change that lets a submitted Workflow continue an existing
OpenTelemetry trace. The Java/Spring apps in it are throwaway demo consumers: any language or framework with an
OpenTelemetry SDK would do, and nothing about them is part of the proposal.

The Argo change lives on
[`vilmosnagy/argo@feat/otel-trace-context-annotations`](https://github.com/vilmosnagy/argo/tree/feat/otel-trace-context-annotations)
(on top of `main`), proposed upstream in [argoproj/argo-workflows#17118](https://github.com/argoproj/argo-workflows/pull/17118)
for issue [#17117](https://github.com/argoproj/argo-workflows/issues/17117).

## The problem

Argo v4 already traces workflows: the controller creates a `workflow` span with `node` spans under it, and gives every
pod a `TRACEPARENT` env var, so `argoexec` and the user's own container join the workflow's trace. But
`RecordStartWorkflow` always starts a **new root trace**. A service that submits a Workflow can't connect it to its own
trace, so the caller → workflow → whatever-the-steps-call chain ends up split into separate traces.

## The change

The submitter can set W3C trace context fields as annotations, one annotation per field, prefixed with
`opentelemetry.io/`:

```yaml
metadata:
  annotations:
    opentelemetry.io/traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
```

When the controller starts the workflow span, it reads that annotation:

| Annotation | Workflow span |
|---|---|
| valid `opentelemetry.io/traceparent` | child of the caller's span: same trace ID, parent = the caller's span ID |
| missing or malformed | new root with a deterministic trace ID, exactly as today |

Everything below the workflow span follows automatically: node spans, `createWorkflowPod`, the pods' `TRACEPARENT` env
vars and `argoexec`'s spans. The `workflows.argoproj.io/trace-id` annotation that Argo writes back on the Workflow and its
pods now holds the caller's trace ID. No change was needed in the executor.

### Code

| File | Change |
|---|---|
| `util/telemetry/annotation_carrier.go` | **New.** `AnnotationCarrier`, a `propagation.TextMapCarrier` over an annotation map with a key prefix: `Get("traceparent")` reads `opentelemetry.io/traceparent`. It doesn't depend on the propagator, so `tracestate`/`baggage` can use it later. |
| `workflow/tracing/workflow.go` | `RecordStartWorkflow(ctx, name, namespace, annotations)` extracts the parent with the stock `propagation.TraceContext{}`. With a valid parent it passes a zero trace ID to `StartWorkflow`, so the SDK keeps the caller's. The span ID stays deterministic, and the existing `workflow=<ns>/<name>` tracestate entry is merged into the caller's tracestate. |
| `workflow/controller/operator.go` | Passes `woc.wf.Annotations` to `RecordStartWorkflow`. |
| `workflow/common/common.go` | `AnnotationKeyPrefixOTelContext = "opentelemetry.io/"`. |
| `*_test.go` | Carrier tests. Start-workflow cases: valid, missing, malformed, wrong prefix, unsampled parent. A controller test that checks the pod's `TRACEPARENT` and the `trace-id` annotation carry the caller's trace ID. |

### Behaviour worth knowing

- **Sampling follows the caller.** The controller's sampler is parent-based. If the caller's `traceparent` says "not
  sampled" (flags `00`), the whole workflow isn't recorded, and neither are the pods' spans. That's correct OTel
  behaviour, but users may not expect it.
- **Nothing goes into `status`.** The annotation stays on the object, and the controller reads it when it creates the
  workflow span (the first reconcile of a new workflow).
- **Not decided yet:** what `argo retry`, `argo resubmit`, CronWorkflows and workflow-of-workflows should do with the
  annotation, and whether `tracestate`/`baggage` should also be forwarded to pods. Today only `TraceContext` is
  injected into pods (`workflow/controller/workflowpod.go`).

## How a workflow step joins the trace

Argo already gives every container a `TRACEPARENT` env var. In the main container it belongs to that step's
`runMainContainer` span, so every step has a different parent span. A step can use it in two ways:

- **Calling another service:** forward it as an HTTP header, e.g. `curl -H "traceparent: $TRACEPARENT" ...`. The
  receiving service's normal OTel instrumentation continues the trace.
- **Doing the work in-process:** read it from the environment as the parent of the step's own spans. This is the
  OpenTelemetry [environment-variable carrier](https://opentelemetry.io/docs/specs/otel/context/env-carriers/)
  convention (the propagator's field names, upper-cased). Most SDKs don't do it automatically, so it usually takes a
  few lines of code.

On the submitting side, the caller injects its current span context with its SDK's propagator into a map, prefixes
the keys with `opentelemetry.io/`, and puts them in `metadata.annotations`. It works the same through argo-server's
REST/gRPC API or `kubectl create`, because the controller only looks at the Workflow object.

## The demo

The workflow is Argo's [`dag-diamond`](https://github.com/argoproj/argo-workflows/blob/main/examples/dag-diamond.yaml)
example, with steps that call services instead of `echo`
(`caller/src/main/resources/diamond.yaml`):

| Step | Template | What it does |
|---|---|---|
| A | `curl` container, `count: 20` | HTTP call to `callee`, forwarding `TRACEPARENT` and `?count=`; the request finds the first `count` primes, one per second (~20 s) |
| B, C | in parallel after A | B: the same as A, with `count: 10` (~10 s). C: runs the `worker` image as the main container with `--count=20`; it continues `TRACEPARENT` in-process and computes 20 Fibonacci numbers, one per second (~20 s) |
| D | `curl` container, after B and C | HTTP callback to `caller` ("workflow finished") |

A `caller` service starts it: it opens a span, writes the span's context into `opentelemetry.io/traceparent`, and
submits the Workflow through argo-server.

The result in Tempo is one trace. Span names are Argo's; app spans are in brackets:

```
[caller] POST /jobs/{job}
  └ [caller] submit-workflow                      ← its span ID is in the opentelemetry.io/traceparent annotation
      └ workflow                                  ← workflow-controller, now a child of the caller's span
          ├ workflowPhase, reconcileWorkflow, persistUpdates, ...
          ├ node (A) → createWorkflowPod
          │              ├ runInitContainer, runWaitContainer        ← argoexec
          │              └ runMainContainer → [callee] POST /steps/A → its own child spans (~20 s)
          ├ node (B) → … → runMainContainer → [callee] POST /steps/B → its own child spans (~10 s)
          ├ node (C) → … → runMainContainer → [worker] worker-run → its own child spans (~20 s)
          └ node (D) → … → runMainContainer → [caller] POST /callbacks/finished
```

## Running it

Prerequisites: `git`, `go`, `kind`, `kubectl`, `curl`, `jq`, `podman` or `docker`, plus Java 25 and Maven to build the
demo apps.

```bash
./up.sh            # ~5 min the first time
./run.sh           # or ./run.sh my-job
kubectl --context kind-argo-poc -n argo port-forward svc/grafana 3000:3000
# http://localhost:3000/explore → Tempo → paste the trace ID printed by run.sh
```

Both scripts only use the `kind-argo-poc` kubectl context. `kind create cluster` makes it your current context.
Tear down: `kind delete cluster --name argo-poc` (with `KIND_EXPERIMENTAL_PROVIDER=podman` when using podman).

### How Argo is built and deployed

`up.sh`:

1. Gets the Argo source (see the table below) and builds `cmd/workflow-controller` and `cmd/argoexec` as static
   linux binaries for the host's architecture. They're wrapped in small images (`argo/Containerfile.*`) instead of the
   repo's Dockerfile, so the UI doesn't have to be built.
2. Builds the demo app images and loads everything into kind (`imagePullPolicy: Never`).
3. Applies the Argo checkout's own `manifests/quick-start-telemetry.yaml`: controller, argo-server, MinIO, plus
   OTel collector → Tempo → Grafana (the same stack as `make start PROFILE=telemetry`).
4. Patches it:
   - `workflow-controller` uses the locally built image, with
     `--executor-image localhost/argo-poc/argoexec:dev --executor-image-pull-policy Never`.
   - `argo-server` runs with `--auth-mode server --secure=false`, so the caller needs no token or TLS. PoC only.
   - argo-server stays the stock `quay.io/argoproj/argocli:latest`: only the controller changed.

| Variable | Default | |
|---|---|---|
| `ARGO_SRC` | *(unset)* | Build from this existing argo-workflows checkout, as is. |
| `ARGO_REPO` | `https://github.com/vilmosnagy/argo.git` | Cloned when `ARGO_SRC` is unset. |
| `ARGO_REF` | `feat/otel-trace-context-annotations` | Branch or tag to check out. |
| `CONTAINER_TOOL` | `podman` if installed, else `docker` | |

Without `ARGO_SRC`, the script makes a shallow clone into `$TMPDIR/argo-tracing-poc-<hash>/argo-workflows`, where
`<hash>` comes from this directory's absolute path. Reruns from the same directory reuse it, and each run updates it to
the latest `ARGO_REF`. To compare against stock Argo, try
`ARGO_REPO=https://github.com/argoproj/argo-workflows.git ARGO_REF=main` (not tested here): the workflow should then
become a separate root trace.

## What to look for

`run.sh` prints the evidence:

- **Workflow annotations:** `opentelemetry.io/traceparent` as submitted, and `workflows.argoproj.io/trace-id` equal to
  the trace ID inside it.
- **Each step pod's `TRACEPARENT`:** the same trace ID, a different span ID per step.
- **Every log line** of the caller, callee and worker has that same trace ID.
- **Grafana/Tempo:** one trace with a single root span, as in the tree above. Before the change, the `workflow` span
  is a root of its own trace.
- **Searching without the trace ID:** the demo apps put the workflow name, step and job on their spans, so TraceQL
  finds the trace from the Argo side, e.g. `{ span.argo.workflow = "diamond-abcde" }` or
  `{ span.argo.step = "B" && span.job = "my-job" }`.

## Layout

| Path | What |
|---|---|
| `up.sh`, `run.sh` | Build and deploy everything; run one job and print the evidence. |
| `argo/` | Containerfiles for the locally built `workflow-controller` and `argoexec`. |
| `caller/src/main/resources/diamond.yaml` | The workflow. |
| `caller/`, `callee/`, `worker/` | The demo apps (throwaway consumers, see the top of this file). |
| `k8s/apps.yaml` | Deployments and services for `caller` and `callee`. The worker only runs as a workflow step. |
