#!/usr/bin/env bash
# install-agent.sh — installation idempotente de l'agent sentinel sur un Pi
# Usage : sudo bash install-agent.sh
# shellcheck shell=bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_SCRIPT="${SCRIPT_DIR}/sentinel-report.sh"
AGENT_DIR="/opt/app/sentinel-agent"
CRON_FILE="/etc/cron.d/sentinel"
AGENT_USER="sentinel-agent"

echo "=== Installation de l'agent sentinel ==="

# ── Utilisateur système ───────────────────────────────────────────────────────
if ! id "$AGENT_USER" &>/dev/null; then
  echo "Création de l'utilisateur $AGENT_USER"
  useradd --system --no-create-home --shell /usr/sbin/nologin \
    --groups video,systemd-journal "$AGENT_USER"
else
  echo "Utilisateur $AGENT_USER déjà existant"
  # S'assurer qu'il est dans les bons groupes
  usermod -aG video,systemd-journal "$AGENT_USER" 2>/dev/null || true
fi

# ── Dossier d'installation ────────────────────────────────────────────────────
mkdir -p "$AGENT_DIR"

# ── Script ───────────────────────────────────────────────────────────────────
echo "Copie du script vers ${AGENT_DIR}/sentinel-report.sh"
install -m 755 "$AGENT_SCRIPT" "${AGENT_DIR}/sentinel-report.sh"

# ── Configuration ─────────────────────────────────────────────────────────────
if [ ! -f "${AGENT_DIR}/agent.conf" ]; then
  echo "Création de ${AGENT_DIR}/agent.conf depuis le modèle"
  install -m 640 -o root -g "$AGENT_USER" \
    "${SCRIPT_DIR}/agent.conf.example" "${AGENT_DIR}/agent.conf"
  echo ""
  echo "IMPORTANT : éditez ${AGENT_DIR}/agent.conf avant le premier lancement."
  echo "  HOST_NAME doit correspondre au nom configuré dans sentinel (application.yml)."
  echo "  API_TOKEN doit correspondre au secret configuré dans secrets.yml."
else
  echo "${AGENT_DIR}/agent.conf déjà présent — non écrasé"
fi
chmod 640 "${AGENT_DIR}/agent.conf"
chown root:"$AGENT_USER" "${AGENT_DIR}/agent.conf"

# ── Cron ──────────────────────────────────────────────────────────────────────
# Lire la fréquence depuis agent.conf (valeurs par défaut si absent)
REPORT_INTERVAL_MIN=30
FULL_REPORT_HOUR=6
# shellcheck source=/dev/null
[ -f "${AGENT_DIR}/agent.conf" ] && source "${AGENT_DIR}/agent.conf"

echo "Installation du cron dans $CRON_FILE (intervalle: ${REPORT_INTERVAL_MIN}min, full: ${FULL_REPORT_HOUR}h)"
cat > "$CRON_FILE" << EOF
# sentinel-agent — collecte des métriques système
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin

*/${REPORT_INTERVAL_MIN} * * * * ${AGENT_USER} ${AGENT_DIR}/sentinel-report.sh
0 ${FULL_REPORT_HOUR}  * * *  ${AGENT_USER} ${AGENT_DIR}/sentinel-report.sh --full
EOF
chmod 644 "$CRON_FILE"

echo ""
echo "=== Installation terminée ==="
echo "Vérifier : sudo -u $AGENT_USER ${AGENT_DIR}/sentinel-report.sh --dry-run"
