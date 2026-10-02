#!/usr/bin/env bash
# Builds everything and deploys it into a local kind cluster.
# Every kubectl call names the context explicitly, so other clusters in your kubeconfig are never touched.
#
# Environment:
#   ARGO_SRC        an existing argo-workflows checkout to build from; if unset, ARGO_REPO@ARGO_REF is
#                   cloned into a temp dir derived from this directory's path (same path -> same dir)
#   ARGO_REPO       git URL to clone           (default: the fork carrying the trace-context change)
#   ARGO_REF        branch or tag to check out (default: feat/otel-trace-context-annotations)
#   CONTAINER_TOOL  podman or docker           (default: podman if installed, else docker)
set -euo pipefail

POC=$(cd "$(dirname "$0")" && pwd)
ARGO_REPO=${ARGO_REPO:-https://github.com/vilmosnagy/argo.git}
ARGO_REF=${ARGO_REF:-feat/otel-trace-context-annotations}
CONTAINER_TOOL=${CONTAINER_TOOL:-$(command -v podman >/dev/null && echo podman || echo docker)}
CLUSTER=argo-poc
CTX=kind-$CLUSTER
[ "$CONTAINER_TOOL" = podman ] && export KIND_EXPERIMENTAL_PROVIDER=podman
k() { kubectl --context "$CTX" -n argo "$@"; }

sha256() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; }

if [ -z "${ARGO_SRC:-}" ]; then
  ARGO_SRC="${TMPDIR:-/tmp}"
  ARGO_SRC="${ARGO_SRC%/}/argo-tracing-poc-$(printf %s "$POC" | sha256 | cut -c1-12)/argo-workflows"
  if [ -d "$ARGO_SRC/.git" ]; then
    echo "--- updating $ARGO_SRC to $ARGO_REPO@$ARGO_REF"
    git -C "$ARGO_SRC" fetch --quiet --depth 1 "$ARGO_REPO" "$ARGO_REF"
    git -C "$ARGO_SRC" checkout --quiet --detach FETCH_HEAD
  else
    echo "--- cloning $ARGO_REPO@$ARGO_REF into $ARGO_SRC"
    git clone --quiet --depth 1 --branch "$ARGO_REF" "$ARGO_REPO" "$ARGO_SRC"
  fi
fi
echo "--- argo source: $ARGO_SRC ($(git -C "$ARGO_SRC" log -1 --format='%h %s'))"

load() { # go through an archive: works the same for docker and podman
  local archive
  archive=$(mktemp "${TMPDIR:-/tmp}/argo-poc-image.XXXXXX")
  if [ "$CONTAINER_TOOL" = podman ]; then podman save --quiet -o "$archive" "$1"; else docker save -o "$archive" "$1"; fi
  kind load image-archive "$archive" --name "$CLUSTER"
  rm -f "$archive"
}

kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s

echo "--- building patched argo binaries"
# kind nodes run linux on the host's architecture
(cd "$ARGO_SRC" && export CGO_ENABLED=0 GOOS=linux GOARCH="$(go env GOARCH)" \
  && go build -o dist/workflow-controller ./cmd/workflow-controller \
  && go build -o dist/argoexec ./cmd/argoexec)
"$CONTAINER_TOOL" build -q -t localhost/argo-poc/workflow-controller:dev -f "$POC/argo/Containerfile.workflow-controller" "$ARGO_SRC"
"$CONTAINER_TOOL" build -q -t localhost/argo-poc/argoexec:dev -f "$POC/argo/Containerfile.argoexec" "$ARGO_SRC"

echo "--- building java apps"
(cd "$POC" && mvn -q -B package -DskipTests)
"$CONTAINER_TOOL" build -q -t localhost/argo-poc/caller:dev -f "$POC/Containerfile.app" "$POC/caller"
"$CONTAINER_TOOL" build -q -t localhost/argo-poc/callee:dev -f "$POC/Containerfile.app" "$POC/callee"
"$CONTAINER_TOOL" build -q -t localhost/argo-poc/worker:dev -f "$POC/Containerfile.app" "$POC/worker"

for image in workflow-controller argoexec caller callee worker; do load localhost/argo-poc/$image:dev; done

echo "--- deploying argo (telemetry quick-start: otel-collector, tempo, grafana) and the apps"
kubectl --context "$CTX" create namespace argo --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -
# --force-conflicts: on re-runs, take back the fields patched below; they are patched again right after
k apply --server-side --force-conflicts -f "$ARGO_SRC/manifests/quick-start-telemetry.yaml"
k apply -f "$POC/k8s/apps.yaml"

# Our controller and executor images; plain-HTTP argo-server so the caller needs no TLS setup
k patch deployment workflow-controller --type=json -p='[
  {"op":"replace","path":"/spec/template/spec/containers/0/image","value":"localhost/argo-poc/workflow-controller:dev"},
  {"op":"add","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"Never"},
  {"op":"replace","path":"/spec/template/spec/containers/0/args","value":["--executor-image","localhost/argo-poc/argoexec:dev","--executor-image-pull-policy","Never"]}]'
k patch deployment argo-server --type=json -p='[
  {"op":"replace","path":"/spec/template/spec/containers/0/args","value":["server","--auth-mode","server","--secure=false"]},
  {"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/scheme","value":"HTTP"}]'
# pick up freshly loaded :dev images on re-runs
k rollout restart deployment workflow-controller caller callee

k rollout status deployment --timeout=300s
echo
echo "Ready. Next: ./run.sh"
