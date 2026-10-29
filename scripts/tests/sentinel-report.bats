#!/usr/bin/env bats
# Tests du script sentinel-report.sh
# Exécuter avec : bats scripts/tests/sentinel-report.bats
# Prérequis : bats-core installé (https://github.com/bats-core/bats-core)

SCRIPT="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)/sentinel-report.sh"
FIXTURES="$(cd "$(dirname "$BATS_TEST_FILENAME")/fixtures" && pwd)"

setup() {
  export PROC_ROOT="${FIXTURES}/proc"
  export SYS_ROOT="${FIXTURES}/sys"
  export HOST_NAME="test-pi"
  export CONF_FILE="/dev/null"
  export SERVICES=""
  export DISK_MOUNTS="/"
}

# ─── Santé générale ──────────────────────────────────────────────────────────

@test "dry-run produit une sortie non vide" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [ -n "$output" ]
}

@test "dry-run produit du JSON valide (accolades)" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == "{"* ]]
  [[ "$output" == *"}" ]]
}

@test "schemaVersion vaut 1" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"schemaVersion": 1'* ]]
}

@test "host correspond à HOST_NAME" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"host": "test-pi"'* ]]
}

# ─── Température CPU ─────────────────────────────────────────────────────────

@test "cpuTempC est 52.0 avec fixture 52000" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"cpuTempC": 52.0'* ]]
}

@test "cpuTempC est null quand le fichier thermal est absent" {
  local tmp_sys
  tmp_sys=$(mktemp -d)
  SYS_ROOT="$tmp_sys" run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"cpuTempC": null'* ]]
  rm -rf "$tmp_sys"
}

# ─── Load average ────────────────────────────────────────────────────────────

@test "load1 / load5 / load15 lus depuis PROC_ROOT/loadavg" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"load1": 0.42'* ]]
  [[ "$output" == *'"load5": 0.55'* ]]
  [[ "$output" == *'"load15": 0.61'* ]]
}

@test "load est null quand PROC_ROOT/loadavg est absent" {
  local tmp_proc
  tmp_proc=$(mktemp -d)
  PROC_ROOT="$tmp_proc" run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"load1": null'* ]]
  rm -rf "$tmp_proc"
}

# ─── Uptime ──────────────────────────────────────────────────────────────────

@test "uptimeSec vaut 3600 avec fixture 3600.12" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"uptimeSec": 3600'* ]]
}

# ─── Mémoire ─────────────────────────────────────────────────────────────────

@test "memTotalKb lu depuis PROC_ROOT/meminfo" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"memTotalKb": 3884032'* ]]
}

@test "memAvailableKb lu depuis PROC_ROOT/meminfo" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"memAvailableKb": 2048000'* ]]
}

@test "swapTotalKb lu depuis PROC_ROOT/meminfo" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"swapTotalKb": 524288'* ]]
}

# ─── Racine en lecture seule ─────────────────────────────────────────────────

@test "rootReadOnly est false avec un montage rw" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"rootReadOnly": false'* ]]
}

@test "rootReadOnly est true avec un montage ro" {
  local tmp_proc
  tmp_proc=$(mktemp -d)
  mkdir -p "$tmp_proc"
  cp "${FIXTURES}/proc/loadavg" "$tmp_proc/"
  cp "${FIXTURES}/proc/uptime"  "$tmp_proc/"
  cp "${FIXTURES}/proc/meminfo" "$tmp_proc/"
  printf 'sysfs /sys sysfs rw 0 0\n/dev/mmcblk0p2 / ext4 ro,relatime 0 0\n' > "$tmp_proc/mounts"
  PROC_ROOT="$tmp_proc" run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"rootReadOnly": true'* ]]
  rm -rf "$tmp_proc"
}

# ─── Mode --full ─────────────────────────────────────────────────────────────

@test "mode normal n'inclut pas daily" {
  run bash "$SCRIPT" --dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *'"daily": null'* ]]
}

@test "mode --full inclut un objet daily non null" {
  run bash "$SCRIPT" --dry-run --full
  [ "$status" -eq 0 ]
  [[ "$output" != *'"daily": null'* ]]
  [[ "$output" == *'"daily": {'* ]]
}

@test "mode --full contient kernelIoErrors24h" {
  run bash "$SCRIPT" --dry-run --full
  [ "$status" -eq 0 ]
  [[ "$output" == *'"kernelIoErrors24h"'* ]]
}
