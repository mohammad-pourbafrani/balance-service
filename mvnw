#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper (only-script flavour)
#
# Downloads the Maven distribution named in .mvn/wrapper/maven-wrapper.properties
# into ~/.m2/wrapper/dists on first use, then delegates to it.
#
# Environment:
#   MAVEN_USER_HOME  where distributions are cached (default: ~/.m2)
#   MVNW_REPOURL     alternative repository base URL (e.g. an internal mirror)
#   MVNW_VERBOSE     set to "true" to print what the wrapper is doing
# ----------------------------------------------------------------------------
set -eu

verbose() {
  if [ "${MVNW_VERBOSE-}" = "true" ]; then
    printf '%s\n' "$*"
  fi
}

die() {
  printf 'mvnw: %s\n' "$*" >&2
  exit 1
}

# Resolve the project base directory (the directory containing this script).
script_dir=$(cd "$(dirname "$0")" && pwd)
props="$script_dir/.mvn/wrapper/maven-wrapper.properties"
[ -f "$props" ] || die "cannot find $props"

distribution_url=$(sed -n 's/^[[:space:]]*distributionUrl[[:space:]]*=[[:space:]]*//p' "$props" | tr -d '\r')
[ -n "$distribution_url" ] || die "distributionUrl is not set in $props"

if [ -n "${MVNW_REPOURL-}" ]; then
  distribution_url="${MVNW_REPOURL%/}/org/apache/maven/${distribution_url#*/org/apache/maven/}"
fi

archive_name=${distribution_url##*/}
dist_name=${archive_name%-bin.*}
maven_user_home=${MAVEN_USER_HOME:-"$HOME/.m2"}
dist_root="$maven_user_home/wrapper/dists/$dist_name"
maven_home="$dist_root/$dist_name"

if [ ! -x "$maven_home/bin/mvn" ]; then
  verbose "Downloading $distribution_url"
  mkdir -p "$dist_root"
  tmp_dir=$(mktemp -d "$dist_root/download.XXXXXX")
  trap 'rm -rf "$tmp_dir"' EXIT INT TERM

  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$tmp_dir/$archive_name" "$distribution_url" || die "download failed: $distribution_url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$tmp_dir/$archive_name" "$distribution_url" || die "download failed: $distribution_url"
  else
    die "neither curl nor wget is available to download Maven"
  fi

  case "$archive_name" in
    *.tar.gz) tar -xzf "$tmp_dir/$archive_name" -C "$tmp_dir" ;;
    *.zip)
      command -v unzip >/dev/null 2>&1 || die "unzip is required for $archive_name"
      unzip -q "$tmp_dir/$archive_name" -d "$tmp_dir"
      ;;
    *) die "unsupported distribution format: $archive_name" ;;
  esac

  [ -x "$tmp_dir/$dist_name/bin/mvn" ] || die "unexpected archive layout in $archive_name"

  # Move into place atomically so a concurrent or interrupted run never sees a half-extracted Maven.
  if ! mv "$tmp_dir/$dist_name" "$maven_home" 2>/dev/null; then
    [ -x "$maven_home/bin/mvn" ] || die "could not install Maven into $maven_home"
  fi
  rm -rf "$tmp_dir"
  trap - EXIT INT TERM
  verbose "Installed Maven into $maven_home"
fi

MAVEN_PROJECTBASEDIR=${MAVEN_BASEDIR:-"$script_dir"}
export MAVEN_PROJECTBASEDIR
exec "$maven_home/bin/mvn" "$@"
