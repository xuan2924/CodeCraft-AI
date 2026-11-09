#!/bin/sh
# Trusted integration-test fixture only. The socket grants Docker administrator access.
# This command adapter is NOT a security boundary or a user-code execution facility.
set -eu
case "${1:-}" in
  pause|unpause)
    [ "$#" = 2 ] && [ "$2" = "$SANDBOX_TEST_CONTAINER" ]
    exec curl --fail --silent --show-error --max-time 25 --unix-socket /var/run/docker.sock \
      -X POST "http://localhost/v1.48/containers/$2/$1"
    ;;
  cp)
    [ "$#" = 3 ]
    prefix="$SANDBOX_TEST_CONTAINER:/var/lib/codecraft/workspaces/"
    destination=${3#"$prefix"}
    [ "$destination" != "$3" ]
    printf '%s' "$destination" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    directory=$(mktemp -d)
    trap 'rm -rf "$directory"' EXIT HUP INT TERM
    cp -- "$2" "$directory/$destination"
    tar -C "$directory" -cf "$directory/payload.tar" "$destination"
    curl --fail --silent --show-error --max-time 25 --unix-socket /var/run/docker.sock \
      -X PUT -H 'Content-Type: application/x-tar' --data-binary "@$directory/payload.tar" \
      "http://localhost/v1.48/containers/$SANDBOX_TEST_CONTAINER/archive?path=/var/lib/codecraft/workspaces"
    ;;
  *) exit 2 ;;
esac
