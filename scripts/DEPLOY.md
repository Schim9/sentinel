# Déploiement de l'agent sentinel

## Structure des dossiers

| Dossier | Contenu | Destination |
|---|---|---|
| `scripts/` | `sentinel-report.sh`, `install-agent.sh`, `agent.conf.example` | Les **4 Pi** — agent de collecte des métriques |

Ce document couvre le déploiement de l'**agent** sur les Pi.

---

## Prérequis

Avant de déployer, sentinel doit tourner sur un PI du réseau.
Chaque Pi doit avoir un secret unique configuré dans `/opt/sentinel/config/secrets.yml` :

```yaml
sentinel:
  host-tokens:
    pi-domoticz: <secret-unique-1>
    pi-backend-1: <secret-unique-2>
    pi-backend-2: <secret-unique-3>
    pi5: <secret-unique-4>
```

---

## Déploiement sur les 4 Pi

La procédure est identique sur les 4 Pi. Seul `agent.conf` diffère.

### Étape 1 — Copier les fichiers

```bash
# Depuis ton PC Windows (adapter le chemin)
scp scripts/sentinel-report.sh  <USER>@<IP_DU_PI>:/tmp/
scp scripts/install-agent.sh    <USER>@<IP_DU_PI>:/tmp/
scp scripts/agent.conf.example  <USER>@<IP_DU_PI>:/tmp/
```

### Étape 2 — Lancer l'installation

```bash
ssh <USER>@<IP_DU_PI>
sudo bash /tmp/install-agent.sh
```

Le script installe :
- `/opt/app/sentinel-agent/sentinel-report.sh` (mode 755)
- L'utilisateur système `sentinel-agent` (groupes `video` + `systemd-journal`)
- `/opt/app/sentinel-agent/agent.conf` depuis le modèle (si absent)
- `/etc/cron.d/sentinel` (rapport toutes les 30 min + mode `--full` à 06:00)

### Étape 3 — Configurer agent.conf

```bash
sudo nano /opt/app/sentinel-agent/agent.conf
```

Voir les valeurs pour chaque Pi ci-dessous.

> **Modifier `agent.conf` après installation :**
> - `HOST_NAME`, `MONITOR_URL`, `API_TOKEN`, `DISK_MOUNTS`, `SERVICES` — lus à chaque exécution du script. Modifier `agent.conf` suffit, effet immédiat au prochain cron.
> - `REPORT_INTERVAL_MIN` et `FULL_REPORT_HOUR` — définissent la ligne cron dans `/etc/cron.d/sentinel`. Modifier `agent.conf` ne suffit pas : relancer `sudo bash install-agent.sh` pour régénérer le fichier cron.

### Étape 4 — Tester avant le premier rapport

```bash
sudo -u sentinel-agent /opt/app/sentinel-agent/sentinel-report.sh --dry-run
```

Le JSON doit s'afficher sans erreur. Vérifier que `cpuTempC` n'est pas `null`.

---


## Peut-on déployer l'agent sur le NAS Synology ?

**Oui, partiellement.** Le NAS joue déjà un rôle d'infrastructure (reverse proxy, vérification de santé), mais il est possible d'y déployer l'agent pour surveiller ses ressources.

### Ce qui fonctionnera

| Métrique | Disponible sur Synology DSM |
|---|---|
| Disques (`df`) | ✅ Oui |
| Mémoire (`/proc/meminfo`) | ✅ Oui |
| Load average (`/proc/loadavg`) | ✅ Oui |
| Uptime (`/proc/uptime`) | ✅ Oui |
| NTP (`timedatectl`) | ✅ Si DSM 7+ (systemd) |
| Services (`systemctl`) | ✅ Si DSM 7+ |

### Ce qui ne fonctionnera pas

| Métrique | Raison |
|---|---|
| `cpuTempC` | Pas de capteur `/sys/class/thermal/thermal_zone0/` standard |
| `throttledRaw` | `vcgencmd` est spécifique aux Raspberry Pi |
| `powerW` | Idem |

Ces métriques apparaîtront comme `null` — ce n'est pas bloquant (le script gère ça proprement).

### Prérequis NAS

1. Activer SSH dans DSM : **Panneau de configuration → Terminal & SNMP → Activer le service SSH**
2. DSM 7+ requis (systemd). Sur DSM 6, `systemctl` et `timedatectl` ne sont pas disponibles.
3. `curl` est disponible nativement sur DSM.

### Procédure d'installation sur le NAS

```bash
# Depuis ton PC
scp scripts/sentinel-report.sh  <USER>@<IP_NAS>:/tmp/
scp scripts/agent.conf.example  <USER>@<IP_NAS>:/tmp/

ssh <USER>@<IP_NAS>

# Créer le dossier d'installation
sudo mkdir -p /opt/app/sentinel-agent
sudo cp /tmp/sentinel-report.sh /opt/app/sentinel-agent/
sudo chmod 755 /opt/app/sentinel-agent/sentinel-report.sh
sudo cp /tmp/agent.conf.example /opt/app/sentinel-agent/agent.conf
sudo chmod 640 /opt/app/sentinel-agent/agent.conf
sudo nano /opt/app/sentinel-agent/agent.conf
```

Configurer `agent.conf` :

```bash
HOST_NAME="nas"
MONITOR_URL="http://<IP_SENTINEL>:8090"
API_TOKEN="<secret-unique-nas>"
DISK_MOUNTS="/volume1 /volume2"   # adapter à tes volumes
SERVICES=""                        # laisser vide ou lister les services DSM critiques
```

> Ajouter le NAS dans `application.yml` de sentinel et dans `secrets.yml` avant de déployer.

**Cron sur DSM** : utiliser le Planificateur de tâches DSM plutôt que `/etc/cron.d/` :
*Panneau de configuration → Planificateur de tâches → Créer → Script défini par l'utilisateur*
- Répétition : toutes les 30 minutes
- Commande : `/opt/app/sentinel-agent/sentinel-report.sh`

---

## Données surveillées

### Métriques en temps réel (toutes les 5 min)

| Donnée | Seuil WARN | Seuil CRIT |
|---|---|---|
| Température CPU | ≥ 70°C (Pi5 : 75°C) | ≥ 80°C (Pi5 : 85°C) |
| Throttling actif (sous-tension, throttle CPU) | bits 0x2 / 0x8 actifs | bits 0x1 / 0x4 actifs |
| Utilisation disque (par point de montage) | ≥ 80% | ≥ 90% |
| Disque racine en lecture seule | — | immédiat |
| Mémoire disponible | < 15% | < 5% |
| Swap utilisé | ≥ 50% | ≥ 85% |
| Load1 / nbre de cœurs | > 2× | > 4× |
| Service systemd attendu non `active` | — | immédiat |
| Unités systemd en échec (`systemctl --failed`) | ≥ 1 unité | — |
| NTP non synchronisé | immédiat | — |
| Silence (pas de rapport depuis > 15 min) | — | immédiat |
| Reboot détecté | notif informative unique | — |

> L'anti-flapping évite les fausses alertes : 2 évaluations consécutives en dépassement sont requises avant transition (sauf silence, lecture seule et service inactif : transition immédiate).

### Métriques quotidiennes (une fois par jour à 06:00 via `--full`)

| Donnée | Seuil WARN | Seuil CRIT |
|---|---|---|
| Erreurs I/O noyau dans les 24h | ≥ 1 | ≥ 20 |
| Mises à jour apt disponibles | info uniquement (digest + hebdo) | — |
| Version OS + version noyau | info uniquement | — |

### Disponibilité des applications

| Donnée | Seuil | Notes |
|---|---|---|
| Check HTTP interne | CRIT si KO (3 fois consécutives) | Toutes les 2 min |
| Check HTTP externe (via NAS + DNS) | CRIT si KO | Détecte les problèmes de proxy / DNS / cert |
| Expiration certificat TLS | WARN ≤ 14 jours, CRIT ≤ 3 jours | Vérifié à chaque check externe |
| Diagnostic croisé interne OK / externe KO | WARN | Problème NAS / DNS / routeur |

### Informations collectées mais non alertées

| Donnée | Où apparaît-elle |
|---|---|
| Throttling passé (depuis le boot) | Digest quotidien + rapport hebdo |
| Consommation électrique Pi 5 (PMIC) | Digest quotidien |
| Top 3 processus par RAM | Rapport hebdo + page `/ack/` |
| Nombre de pushes Pushbullet ce mois | Digest + rapport hebdo |
