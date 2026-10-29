#!/usr/bin/env bash
# sentinel-report.sh — collecte de métriques système pour Sentinel
# Déployé sur chaque Pi. Doit passer ShellCheck sans avertissement.
# shellcheck shell=bash

# ─── Chemins surchageables pour les tests ────────────────────────────────────
PROC_ROOT="${PROC_ROOT:-/proc}"
SYS_ROOT="${SYS_ROOT:-/sys}"

# ─── Configuration ───────────────────────────────────────────────────────────
CONF_FILE="${CONF_FILE:-/opt/app/sentinel-agent/agent.conf}"
# shellcheck source=/dev/null
[ -f "$CONF_FILE" ] && source "$CONF_FILE"

HOST_NAME="${HOST_NAME:-$(hostname)}"
MONITOR_URL="${MONITOR_URL:-}"
API_TOKEN="${API_TOKEN:-}"
DISK_MOUNTS="${DISK_MOUNTS:-/}"
SERVICES="${SERVICES:-}"

# ─── Options ─────────────────────────────────────────────────────────────────
MODE="normal"
DRY_RUN=false

for arg in "$@"; do
  case "$arg" in
    --full)    MODE="full"  ;;
    --dry-run) DRY_RUN=true ;;
  esac
done

# ─── Fonctions utilitaires ────────────────────────────────────────────────────

json_escape() {
  # Échappe \ et " dans une chaîne JSON
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# ─── Métriques rapides ────────────────────────────────────────────────────────

# Température CPU
cpu_temp_c="null"
if [ -r "${SYS_ROOT}/class/thermal/thermal_zone0/temp" ]; then
  raw_temp=$(cat "${SYS_ROOT}/class/thermal/thermal_zone0/temp")
  if [ -n "$raw_temp" ] && [ "$raw_temp" -gt 0 ] 2>/dev/null; then
    cpu_temp_c=$(awk "BEGIN {printf \"%.1f\", $raw_temp / 1000}")
  fi
fi

# Throttling
throttled_json="null"
throttled_raw=$(vcgencmd get_throttled 2>/dev/null) || throttled_raw=""
if [ -n "$throttled_raw" ]; then
  val=$(printf '%s' "$throttled_raw" | cut -d= -f2)
  [ -n "$val" ] && throttled_json="\"$(json_escape "$val")\""
fi

# Load average
load1="null"; load5="null"; load15="null"
if [ -r "${PROC_ROOT}/loadavg" ]; then
  load_line=$(cat "${PROC_ROOT}/loadavg")
  load1=$(printf '%s' "$load_line" | awk '{print $1}')
  load5=$(printf '%s' "$load_line" | awk '{print $2}')
  load15=$(printf '%s' "$load_line" | awk '{print $3}')
fi

# CPU cores
cpu_cores="null"
cpu_cores=$(nproc 2>/dev/null) || cpu_cores="null"

# Uptime
uptime_sec="null"
if [ -r "${PROC_ROOT}/uptime" ]; then
  uptime_sec=$(awk '{print int($1)}' "${PROC_ROOT}/uptime")
fi

# Boot time
boot_time_json="null"
boot_time_raw=$(uptime -s 2>/dev/null) || boot_time_raw=""
if [ -n "$boot_time_raw" ]; then
  boot_time=$(printf '%s' "$boot_time_raw" | tr ' ' 'T')
  boot_time_json="\"$(json_escape "$boot_time")\""
fi

# Mémoire
mem_total_kb="null"; mem_available_kb="null"
swap_total_kb="null"; swap_free_kb="null"
if [ -r "${PROC_ROOT}/meminfo" ]; then
  mem_total_kb=$(awk '/^MemTotal:/ {print $2}' "${PROC_ROOT}/meminfo")
  mem_available_kb=$(awk '/^MemAvailable:/ {print $2}' "${PROC_ROOT}/meminfo")
  swap_total_kb=$(awk '/^SwapTotal:/ {print $2}' "${PROC_ROOT}/meminfo")
  swap_free_kb=$(awk '/^SwapFree:/ {print $2}' "${PROC_ROOT}/meminfo")
  [ -z "$mem_total_kb" ]     && mem_total_kb="null"
  [ -z "$mem_available_kb" ] && mem_available_kb="null"
  [ -z "$swap_total_kb" ]    && swap_total_kb="null"
  [ -z "$swap_free_kb" ]     && swap_free_kb="null"
fi

# Racine en lecture seule
root_ro="false"
if awk '$2 == "/" { split($4, opts, ","); for (o in opts) if (opts[o] == "ro") { exit 0 } exit 1 }' \
     "${PROC_ROOT}/mounts" 2>/dev/null; then
  root_ro="true"
fi

# NTP
ntp_synced="null"
ntp_raw=$(timedatectl show -p NTPSynchronized --value 2>/dev/null) || ntp_raw=""
case "$ntp_raw" in
  yes) ntp_synced="true"  ;;
  no)  ntp_synced="false" ;;
esac

# Disques JSON array
disks_json="["
first_disk=true
for mount in $DISK_MOUNTS; do
  df_line=$(df -P -k "$mount" 2>/dev/null | tail -1) || continue
  if [ -n "$df_line" ]; then
    total_kb=$(printf '%s' "$df_line" | awk '{print $2}')
    used_kb=$(printf '%s' "$df_line" | awk '{print $3}')
    used_pct=$(printf '%s' "$df_line" | awk '{print $5}' | tr -d '%')
    if [ "$first_disk" = "true" ]; then
      first_disk=false
    else
      disks_json="${disks_json},"
    fi
    disks_json="${disks_json}{\"mount\":\"$(json_escape "$mount")\",\"totalKb\":${total_kb},\"usedKb\":${used_kb},\"usedPct\":${used_pct}}"
  fi
done
disks_json="${disks_json}]"

# Services JSON array
services_json="["
first_svc=true
for svc in $SERVICES; do
  state=$(systemctl is-active "$svc" 2>/dev/null) || state="unknown"
  if [ "$first_svc" = "true" ]; then
    first_svc=false
  else
    services_json="${services_json},"
  fi
  services_json="${services_json}{\"name\":\"$(json_escape "$svc")\",\"state\":\"$(json_escape "$state")\"}"
done
services_json="${services_json}]"

# Unités en échec
failed_units_json="["
first_failed=true
while IFS= read -r unit_line; do
  [ -z "$unit_line" ] && continue
  unit_name=$(printf '%s' "$unit_line" | awk '{print $1}')
  [ -z "$unit_name" ] && continue
  if [ "$first_failed" = "true" ]; then
    first_failed=false
  else
    failed_units_json="${failed_units_json},"
  fi
  failed_units_json="${failed_units_json}\"$(json_escape "$unit_name")\""
done < <(systemctl --failed --no-legend --plain 2>/dev/null || true)
failed_units_json="${failed_units_json}]"

# Top processus (3 plus gourmands en RAM)
top_procs_json="["
first_proc=true
while IFS= read -r proc_line; do
  [ -z "$proc_line" ] && continue
  proc_name=$(printf '%s' "$proc_line" | awk '{print $1}')
  proc_rss=$(printf '%s' "$proc_line" | awk '{print $2}')
  [ -z "$proc_name" ] || [ -z "$proc_rss" ] && continue
  if [ "$first_proc" = "true" ]; then
    first_proc=false
  else
    top_procs_json="${top_procs_json},"
  fi
  top_procs_json="${top_procs_json}{\"name\":\"$(json_escape "$proc_name")\",\"rssKb\":${proc_rss}}"
done < <(ps -eo comm,rss --sort=-rss 2>/dev/null | tail -n +2 | head -3 || true)
top_procs_json="${top_procs_json}]"

# Consommation électrique (Pi 5 uniquement via PMIC)
power_json="null"
if vcgencmd pmic_read_adc >/dev/null 2>&1; then
  power_json=$(vcgencmd pmic_read_adc 2>/dev/null | awk '
    /_V/ { split($NF, v, "="); val = v[2]; gsub(/[^0-9.]/, "", val); name = substr($1, 1, length($1)-2); volt[name] = val * 1.0 }
    /_A/ { split($NF, v, "="); val = v[2]; gsub(/[^0-9.]/, "", val); name = substr($1, 1, length($1)-2); amp[name] = val * 1.0 }
    END {
      total = 0
      for (k in volt) if (k in amp) total += volt[k] * amp[k]
      if (total > 0) printf "%.2f", total
      else print "null"
    }') || power_json="null"
fi

# Date de collecte
collected_at=$(date -Iseconds 2>/dev/null) || collected_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# ─── Métriques quotidiennes (mode --full) ─────────────────────────────────────
daily_json="null"
if [ "$MODE" = "full" ]; then
  apt_upgradable="null"
  apt_count=$(apt-get -s upgrade 2>/dev/null | grep -c '^Inst') || apt_count=""
  [ -n "$apt_count" ] && apt_upgradable="$apt_count"

  kernel_io_errors="null"
  io_count=$(journalctl -k --since "-24h" 2>/dev/null | grep -cE 'I/O error|mmc.*error') || io_count=""
  [ -n "$io_count" ] && kernel_io_errors="$io_count"

  os_release_json="null"
  # shellcheck source=/dev/null
  os_release_val=$(. /etc/os-release 2>/dev/null && printf '%s' "$PRETTY_NAME") || os_release_val=""
  [ -n "$os_release_val" ] && os_release_json="\"$(json_escape "$os_release_val")\""

  kernel_json="null"
  kernel_val=$(uname -r 2>/dev/null) || kernel_val=""
  [ -n "$kernel_val" ] && kernel_json="\"$(json_escape "$kernel_val")\""

  daily_json="{\"aptUpgradable\":${apt_upgradable},\"kernelIoErrors24h\":${kernel_io_errors},\"osRelease\":${os_release_json},\"kernel\":${kernel_json}}"
fi

# ─── Assemblage du JSON ───────────────────────────────────────────────────────
payload=$(printf '{
  "schemaVersion": 1,
  "host": "%s",
  "mode": "%s",
  "collectedAt": "%s",
  "cpuTempC": %s,
  "throttledRaw": %s,
  "load1": %s, "load5": %s, "load15": %s, "cpuCores": %s,
  "uptimeSec": %s, "bootTime": %s,
  "memTotalKb": %s, "memAvailableKb": %s,
  "swapTotalKb": %s, "swapFreeKb": %s,
  "disks": %s,
  "rootReadOnly": %s,
  "services": %s,
  "failedUnits": %s,
  "ntpSynced": %s,
  "topProcesses": %s,
  "powerW": %s,
  "daily": %s
}' \
  "$(json_escape "$HOST_NAME")" \
  "$MODE" \
  "$(json_escape "$collected_at")" \
  "$cpu_temp_c" \
  "$throttled_json" \
  "$load1" "$load5" "$load15" "$cpu_cores" \
  "$uptime_sec" "$boot_time_json" \
  "$mem_total_kb" "$mem_available_kb" \
  "$swap_total_kb" "$swap_free_kb" \
  "$disks_json" \
  "$root_ro" \
  "$services_json" \
  "$failed_units_json" \
  "$ntp_synced" \
  "$top_procs_json" \
  "$power_json" \
  "$daily_json")

# ─── Envoi ou affichage ───────────────────────────────────────────────────────
if [ "$DRY_RUN" = "true" ]; then
  printf '%s\n' "$payload"
  exit 0
fi

send_report() {
  printf '%s' "$payload" | curl -fsS --max-time 10 \
    -X POST \
    -H "Content-Type: application/json" \
    -H "X-Monitor-Token: ${API_TOKEN}" \
    --data-binary @- \
    "${MONITOR_URL}/api/reports"
}

if ! send_report; then
  sleep 10
  if ! send_report; then
    logger -t sentinel-report "envoi KO pour ${HOST_NAME} vers ${MONITOR_URL}"
    exit 1
  fi
fi
