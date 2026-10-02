#!/usr/bin/env bash
# Starts a job on the caller app, waits for its workflow and prints the evidence.
set -euo pipefail

CTX=kind-argo-poc
JOB=${1:-job-$(date +%H%M%S)}
k() { kubectl --context "$CTX" -n argo "$@"; }

# Right after up.sh the port-forward can land on a terminating caller pod, so retry until it answers
pf=
trap '[ -z "$pf" ] || kill "$pf" 2>/dev/null || true' EXIT
for _ in $(seq 10); do
  k port-forward svc/caller 18080:8080 >/dev/null 2>&1 &
  pf=$!
  sleep 2
  curl -sf http://localhost:18080/actuator/health >/dev/null && break
  kill "$pf" 2>/dev/null || true # it may have exited on its own already
  pf=
  sleep 1
done
[ -n "$pf" ] || { echo "caller app not reachable" >&2; exit 1; }

echo "--- POST /jobs/$JOB"
response=$(curl -sSf -X POST "http://localhost:18080/jobs/$JOB")
echo "$response"
wf=$(echo "$response" | jq -r .workflow)

k wait --for=jsonpath='{.status.phase}'=Succeeded "workflow/$wf" --timeout=300s
sleep 6 # let the batch span exporters flush
trace=$(k get workflow "$wf" -o jsonpath='{.metadata.annotations.workflows\.argoproj\.io/trace-id}')

echo
echo "--- workflow $wf annotations"
k get workflow "$wf" -o json | jq '.metadata.annotations'
echo
echo "--- TRACEPARENT each step pod received"
for pod in $(k get pods -l "workflows.argoproj.io/workflow=$wf" -o name); do
  k logs "$pod" -c main | grep -o "[a-zA-Z: ]*TRACEPARENT=[0-9a-f-]*"
done
echo
echo "--- worker log (step C)"
k logs "$(k get pods -l "workflows.argoproj.io/workflow=$wf" -o name | grep -- '-calculate-')" -c main | grep "poc.worker"
echo
echo "--- caller log"
k logs deploy/caller | grep -E "$JOB|$wf"
echo
echo "--- callee log (steps A and B)"
k logs deploy/callee | grep "$trace"
echo
echo "Trace $trace in Grafana: kubectl --context $CTX -n argo port-forward svc/grafana 3000:3000"
echo "  then http://localhost:3000/explore -> Tempo -> TraceQL: $trace"
