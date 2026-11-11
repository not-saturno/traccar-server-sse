#!/usr/bin/env bash

#
# Builds the traccar-sse Docker image, tags it with the current version and latest, and exports it to a file that
# can be loaded on a server and run with docker/compose/traccar-sse-image.yaml. See docs/imagem-docker.md.
#

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

info () {
  echo -e "[\033[1;34mINFO\033[0m] $1"
}

ok () {
  echo -e "[\033[1;32m OK \033[0m] $1"
}

warn () {
  echo -e "[\033[1;31mWARN\033[0m] $1"
}

fail () {
  warn "$1" >&2
  exit 1
}

usage () {
  echo "Usage: $0"
  echo "Build the traccar-sse image, tag it as <version> and latest, and export it to OUTPUT_DIR."
  echo
  echo "Environment variables:"
  echo " VERSION     image tag (default: <build.gradle version>-<git commit>, plus -dirty with uncommitted changes)"
  echo " IMAGE       image name (default: traccar-sse)"
  echo " OUTPUT_DIR  output directory (default: dist/docker)"
  echo " PLATFORM    target platform, e.g. linux/arm64 for an ARM server (default: this machine)"
  echo " SKIP_BUILD  set to true to export the existing IMAGE:latest instead of building"
  exit 1
}

if [[ $# -gt 0 ]]; then
  usage
fi

IMAGE=${IMAGE:-traccar-sse}
OUTPUT_DIR=${OUTPUT_DIR:-$ROOT/dist/docker}
PLATFORM=${PLATFORM:-}
SKIP_BUILD=${SKIP_BUILD:-false}

default_version () {
  local base revision dirty=""
  base=$(sed -n 's/.*"Implementation-Version": "\([^"]*\)".*/\1/p' build.gradle)
  revision=$(git rev-parse --short HEAD 2>/dev/null || echo "nogit")
  # tracked changes only (including the traccar-web submodule); untracked files do not go into the image
  if [[ -n $(git status --porcelain --untracked-files=no 2>/dev/null) ]]; then
    dirty="-dirty"
  fi
  echo "${base}-${revision}${dirty}"
}

VERSION=${VERSION:-$(default_version)}
REVISION=$(git rev-parse HEAD 2>/dev/null || echo "unknown")

# Docker tag rules: letters, digits, _ . -, up to 128 characters, not starting with . or -
[[ $VERSION =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || fail "Invalid VERSION for a Docker tag: $VERSION"
[[ $VERSION != "latest" ]] || fail "VERSION cannot be latest (latest is always added)"

info "Image $IMAGE, version $VERSION"

which docker &>/dev/null || fail "Missing docker binary"
docker info &>/dev/null || fail "Docker is not running (start Docker Desktop / the docker service)"
which gzip &>/dev/null || fail "Missing gzip binary"

if [[ $SKIP_BUILD = true ]]; then
  docker image inspect "$IMAGE:latest" &>/dev/null || fail "SKIP_BUILD=true but $IMAGE:latest does not exist"
  warn "Exporting the existing $IMAGE:latest; its version label is whatever it was built with"
  docker tag "$IMAGE:latest" "$IMAGE:$VERSION"
else
  [[ -f traccar-web/package.json ]] || fail "Missing traccar-web (run: git submodule update --init traccar-web)"
  info "Building image (first build takes a few minutes)"
  BUILD_ARGS=(-f docker/Dockerfile.sse
    --build-arg "VERSION=$VERSION" --build-arg "REVISION=$REVISION"
    -t "$IMAGE:$VERSION" -t "$IMAGE:latest")
  if [[ -n $PLATFORM ]]; then
    BUILD_ARGS+=(--platform "$PLATFORM")
  fi
  docker build "${BUILD_ARGS[@]}" .
fi
ok "Tagged $IMAGE:$VERSION and $IMAGE:latest"

# Same layout as the repository (compose/ next to caddy/), so ../caddy/Caddyfile in the compose file works
IMAGES_DIR="$OUTPUT_DIR/images"
mkdir -p "$IMAGES_DIR" "$OUTPUT_DIR/compose" "$OUTPUT_DIR/caddy"
ARCHIVE="$IMAGES_DIR/$IMAGE-$VERSION.tar.gz"
LATEST="$IMAGES_DIR/$IMAGE-latest.tar.gz"

info "Exporting to $ARCHIVE"
# both tags in one file: docker load restores $VERSION and moves latest to this image
docker save "$IMAGE:$VERSION" "$IMAGE:latest" | gzip > "$ARCHIVE.part"
mv "$ARCHIVE.part" "$ARCHIVE"
(cd "$IMAGES_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")

# latest file is replaced by every export (hard link: no extra disk space; copy if links are not supported)
rm -f "$LATEST" "$LATEST.sha256"
ln "$ARCHIVE" "$LATEST" 2>/dev/null || cp "$ARCHIVE" "$LATEST"
(cd "$IMAGES_DIR" && sha256sum "$(basename "$LATEST")" > "$(basename "$LATEST").sha256")

# compose bundle for the server (Traccar behind Caddy)
cp docker/compose/traccar-sse-image.yaml "$OUTPUT_DIR/compose/"
cp docker/compose/traccar-sse.xml "$OUTPUT_DIR/compose/traccar-sse.xml"
cp docker/caddy/Caddyfile "$OUTPUT_DIR/caddy/Caddyfile"
cat > "$OUTPUT_DIR/compose/.env.example" <<EOF
# Copy to .env next to traccar-sse-image.yaml
# Domain, or the server public IP while there is no domain
SERVER_NAME=
# Contact for certificate expiry notices
ACME_EMAIL=
# acme = domain | ip = public IP without domain (Let's Encrypt, ~6-day certificate) | internal = private IP
TLS_MODE=acme
# Image tag to run: latest (last exported) or a fixed version
TRACCAR_SSE_TAG=latest
EOF

ok "Exported $(du -h "$ARCHIVE" | cut -f1) to $OUTPUT_DIR"
echo
echo "On the server, copy $OUTPUT_DIR, create compose/.env from compose/.env.example and run:"
echo "  docker load -i images/$IMAGE-$VERSION.tar.gz"
echo "  docker compose -f compose/traccar-sse-image.yaml up -d"
