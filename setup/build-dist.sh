#!/usr/bin/env bash

#
# Script to create self-contained packages with an embedded Java runtime (jlink).
#

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

usage () {
  echo "Usage: $0 [PLATFORM...]"
  echo "Build Traccar packages with an embedded Java runtime."
  echo
  echo "Without PLATFORM provided, builds packages for all platforms."
  echo
  echo "Available platforms:"
  echo " * linux-x64"
  echo " * linux-arm64"
  echo " * windows-x64"
  echo
  echo "Environment variables:"
  echo " VERSION      package version (default: Implementation-Version from build.gradle)"
  echo " JDK_VERSION  Java feature version of the embedded runtime (default: 21)"
  echo " JLINK        jlink binary, must match JDK_VERSION (default: jlink from PATH)"
  echo " MODULES      modules added to the runtime (default: $DEFAULT_MODULES)"
  echo " CACHE_DIR    where downloaded JDKs are kept (default: setup/jdk-cache)"
  echo " DIST_DIR     output directory (default: dist)"
  echo " SKIP_BUILD   set to true to skip ./gradlew assemble"
  exit 1
}

info () {
  echo -e "[\033[1;34mINFO\033[0m] "$1
}

ok () {
  echo -e "[\033[1;32m OK \033[0m] "$1
}

warn () {
  echo -e "[\033[1;31mWARN\033[0m] "$1
}

fail () {
  warn "$1" >&2
  exit 1
}

DEFAULT_MODULES="java.se,jdk.charsets,jdk.crypto.ec,jdk.unsupported"
ALL_PLATFORMS="linux-x64 linux-arm64 windows-x64"

VERSION=${VERSION:-$(sed -n 's/.*"Implementation-Version": "\([^"]*\)".*/\1/p' build.gradle)}
JDK_VERSION=${JDK_VERSION:-21}
JLINK=${JLINK:-jlink}
MODULES=${MODULES:-$DEFAULT_MODULES}
CACHE_DIR=${CACHE_DIR:-$ROOT/setup/jdk-cache}
DIST_DIR=${DIST_DIR:-$ROOT/dist}
SKIP_BUILD=${SKIP_BUILD:-false}

if [[ $# -eq 1 && ( $1 = "-h" || $1 = "--help" ) ]]; then
  usage
fi

PLATFORMS=${*:-$ALL_PLATFORMS}
for PLATFORM in $PLATFORMS; do
  case $PLATFORM in
    linux-x64|linux-arm64|windows-x64) ;;
    *) warn "Unknown platform: $PLATFORM"; usage ;;
  esac
done

check_requirements () {
  info "Checking build requirements"
  for BINARY in curl tar unzip zip sha256sum "$JLINK"; do
    which "$BINARY" &>/dev/null || fail "Missing $BINARY binary"
  done
  JLINK_VERSION=$("$JLINK" --version)
  if [[ ${JLINK_VERSION%%.*} != "$JDK_VERSION" ]]; then
    fail "jlink $JLINK_VERSION does not match JDK_VERSION=$JDK_VERSION (set JLINK to a JDK $JDK_VERSION jlink)"
  fi
  ok "jlink $JLINK_VERSION"
}

build_server () {
  if [[ $SKIP_BUILD = true ]]; then
    info "Skipping Gradle build"
  else
    info "Building server"
    ./gradlew assemble -q
  fi
  [[ -f target/tracker-server.jar ]] || fail "Missing target/tracker-server.jar"
  [[ -d target/lib ]] || fail "Missing target/lib"
  ok "Server archive ready"
}

# Prints the cached JDK archive for the given os and arch, downloading it when missing.
fetch_jdk () {
  local OS=$1 ARCH=$2 EXT=$3
  local ARCHIVE
  ARCHIVE=$(ls "$CACHE_DIR"/OpenJDK${JDK_VERSION}U-jdk_${ARCH}_${OS}_hotspot_*.$EXT 2>/dev/null | sort | tail -n 1 || true)
  if [[ -n $ARCHIVE ]]; then
    info "Using cached $(basename "$ARCHIVE")" >&2
    echo "$ARCHIVE"
    return
  fi

  local API="https://api.adoptium.net/v3/binary/latest/$JDK_VERSION/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  local LINK
  LINK=$(curl -fsS -o /dev/null -w '%{redirect_url}' "$API")
  [[ -n $LINK ]] || fail "Could not resolve JDK download for $OS/$ARCH"
  ARCHIVE="$CACHE_DIR/$(basename "$LINK")"

  info "Downloading $(basename "$ARCHIVE")" >&2
  mkdir -p "$CACHE_DIR"
  curl -fL --progress-bar -o "$ARCHIVE.part" "$LINK"
  local EXPECTED ACTUAL
  EXPECTED=$(curl -fsSL "$LINK.sha256.txt" | cut -d ' ' -f 1)
  ACTUAL=$(sha256sum "$ARCHIVE.part" | cut -d ' ' -f 1)
  if [[ $EXPECTED != "$ACTUAL" ]]; then
    rm -f "$ARCHIVE.part"
    fail "Checksum mismatch for $(basename "$ARCHIVE")"
  fi
  mv "$ARCHIVE.part" "$ARCHIVE"
  ok "Checksum verified" >&2
  echo "$ARCHIVE"
}

prepare () {
  local OUT=$1
  rm -rf "$OUT"
  mkdir -p "$OUT"/{conf,data,lib,logs,web,schema,templates}

  cp target/tracker-server.jar "$OUT"
  cp target/lib/* "$OUT/lib"
  cp schema/* "$OUT/schema"
  cp -r templates/* "$OUT/templates"
  cp setup/traccar.xml "$OUT/conf"

  # traccar-web is gps-tracker-front (Vite), built into traccar-web/dist with npm run build
  if [[ -d traccar-web/dist ]]; then
    cp -r traccar-web/dist/* "$OUT/web"
  else
    warn "Missing traccar-web/dist, package will not include the web interface"
  fi
  # Notification translations came from upstream traccar-web, the new front does not ship them
  if [[ -d traccar-web/src/resources/l10n ]]; then
    cp -r traccar-web/src/resources/l10n "$OUT/templates/translations"
  else
    warn "Missing translations, notification texts will lack translated labels"
  fi
}

build_jre () {
  local ARCHIVE=$1 OUT=$2
  local WORK
  WORK=$(mktemp -d)
  case $ARCHIVE in
    *.zip) unzip -q "$ARCHIVE" -d "$WORK" ;;
    *) tar -xzf "$ARCHIVE" -C "$WORK" ;;
  esac
  local JMODS
  JMODS=$(find "$WORK" -maxdepth 3 -type d -name jmods | head -n 1)
  [[ -n $JMODS ]] || { rm -rf "$WORK"; fail "No jmods directory in $(basename "$ARCHIVE")"; }

  info "Linking runtime with modules $MODULES"
  "$JLINK" --module-path "$JMODS" --add-modules "$MODULES" \
    --strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-6 \
    --output "$OUT/jre"
  rm -rf "$WORK"
}

package () {
  local PLATFORM=$1 OS ARCH EXT
  case $PLATFORM in
    linux-x64) OS=linux; ARCH=x64; EXT=tar.gz ;;
    linux-arm64) OS=linux; ARCH=aarch64; EXT=tar.gz ;;
    windows-x64) OS=windows; ARCH=x64; EXT=zip ;;
  esac

  info "Building $PLATFORM package"
  local NAME="traccar-$VERSION-$PLATFORM"
  local OUT="$DIST_DIR/$NAME"
  local ARCHIVE
  ARCHIVE=$(fetch_jdk $OS $ARCH $EXT)

  prepare "$OUT"
  build_jre "$ARCHIVE" "$OUT"

  if [[ $OS = windows ]]; then
    printf '@echo off\r\ncd /d "%%~dp0"\r\njre\\bin\\java.exe -jar tracker-server.jar conf\\traccar.xml\r\n' > "$OUT/traccar.bat"
    rm -f "$DIST_DIR/$NAME.zip"
    (cd "$DIST_DIR" && zip -q -r "$NAME.zip" "$NAME")
  else
    printf '#!/bin/sh\ncd "$(dirname "$0")"\nexec ./jre/bin/java -jar tracker-server.jar conf/traccar.xml\n' > "$OUT/traccar.sh"
    chmod +x "$OUT/traccar.sh"
    cp setup/traccar.service "$OUT"
    tar -czf "$DIST_DIR/$NAME.tar.gz" -C "$DIST_DIR" "$NAME"
  fi
  ok "Created $DIST_DIR/$NAME.$EXT"
}

check_requirements
build_server
mkdir -p "$DIST_DIR"
for PLATFORM in $PLATFORMS; do
  package $PLATFORM
done
ok "Done"
