#!/usr/bin/env bash

function absPath() {
  perl -MCwd -le 'print Cwd::abs_path(shift)' "$1"
}

cwd="$(dirname "$(absPath "$0")")"

if [ ! -f "${cwd}/dist/ldap-test-server-1.0.0.jar" ]; then
  "${cwd}/gradlew" build
fi

java -cp "${cwd}"'/dist/*' io.snappydata.ldap.LdapTestServer "$@"
