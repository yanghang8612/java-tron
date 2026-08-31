#!/usr/bin/env bash

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(dirname -- "$script_dir")
toolkit_jar="$repo_dir/plugins/build/libs/Toolkit.jar"
scanner_class='org/tron/plugins/DbScanCreateSmartContractHashes.class'

if [[ ! -f "$toolkit_jar" ]] || ! jar tf "$toolkit_jar" | grep -q "$scanner_class"; then
  "$repo_dir/gradlew" -p "$repo_dir" :plugins:buildToolkitJar
fi

exec java -jar "$toolkit_jar" db scan-create-contract-hashes "$@"
