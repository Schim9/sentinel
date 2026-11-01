# SPEC — sentinel : monitoring des Pi, des applis, des DNS et des certificats

> Document destiné à un agent de développement. Il décrit **tout** ce qu'il faut implémenter :
> le script de collecte déployé sur chaque Pi et l'application Spring Boot centrale.
> Les valeurs entre `<...>` sont à compléter avant déploiement (voir §11).
> Domaine public : `sentinel.<your-domain>`.

---

## 0. Contexte et objectifs

L'utilisateur héberge plusieurs applications sur 4 Raspberry Pi à domicile :

| Hôte (nom logique) | Modèle | Rôle |
|---|---|---|
| `pi-domoticz` | Pi 3 | Domoticz (<pi-domoticz-ip>) |
| `pi-backend-1` | Pi 3 | Apps Java 21 / Spring Boot 3 (`<à répartir : solarinverter, dmarcdashboard, mymenuapp>`) |
| `pi-backend-2` | Pi 3 | Apps Java 21 / Spring Boot 3 (`<idem>`) |
| `pi5` | Pi 5 | **sentinel** (cette spec), choreminder, future app caméra |

Objectifs :

1. **Collecter** sur chaque Pi des métriques système (température, disque, mémoire, throttling…) via un script shell lancé par cron, **sans rien installer de lourd** (les Pi 3 sont déjà chargés).
2. **Centraliser** ces rapports dans une app Spring Boot sur le Pi 5, qui vérifie aussi elle-même la disponibilité des applications (interne **et** via le chemin public).
3. **Alerter** via Pushbullet uniquement sur **changement d'état** (pas de spam).
4. **Envoyer chaque jour un état général** (notif Pushbullet de type `link`) avec **accusé de lecture** : un clic ouvre le rapport et permet de confirmer la réception. Cet accusé prouve que toute la chaîne fonctionne (Pi → app → Pushbullet → smartphone → Eric).
5. **Escalader par e-mail** (SMTP Strato, canal indépendant) si l'accusé n'est pas donné dans les délais, ou si Pushbullet est en panne.
6. **Envoyer un rapport hebdomadaire par e-mail** (ce qui teste aussi le canal e-mail).

Conséquence attendue : les notifs « tout va bien » de SolarInverter et DmarcDashboard seront supprimées (hors périmètre de cette spec), sentinel devient l'unique heartbeat.

### Hors périmètre (v2, voir §10)
Dashboard Angular avec historique et graphiques, mesure de consommation électrique via prises connectées Domoticz, sonde externe type UptimeRobot.

---

## 1. Architecture

```
            Internet (port 443 uniquement, IP hors Europe bloquées)
                               │
                          [Routeur] ──► [NAS Synology : reverse proxy DSM + TLS Let's Encrypt]
                                                │
                    sentinel.<your-domain> ─────┘──► pi5:8091  (port PUBLIC : /ack/**, /api/status, /report, /public-assets/**)

Réseau local 192.168.x.0/24
┌──────────────┐  ┌──────────────┐  ┌──────────────┐   ┌───────────────────────────────────────┐
│ pi-domoticz  │  │ pi-backend-1 │  │ pi-backend-2 │   │ pi5                                   │
│ cron: script │  │ cron: script │  │ cron: script │   │ cron: script ─► localhost             │
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │                                       │
       │  POST /api/reports (*/5 min)      │           │ sentinel                              │
       └─────────────────┴─────────────────┴──────────►│   :8090 port INTERNE (rapports,       │
                                                       │          actuator, page d'état)       │
  ◄──── GET checks internes (http://ip:port/...) ──────│   :8091 port PUBLIC                   │
  ◄──── GET checks externes (https://...) ─────────────│   SQLite /opt/sentinel/data           │
                                                       └───────┬───────────────┬───────────────┘
                                                               │               │
                                                       Pushbullet API     SMTP Strato
                                                       (alertes, digest)  (escalade, hebdo)

[NAS] tâche planifiée : GET http://pi5:8090/actuator/health toutes les 15 min → e-mail DSM si KO
```

---

## 2. Composant A — script de collecte `sentinel-report.sh`

### 2.1 Contraintes
- **Bash + outils standard uniquement** : coreutils, `curl`, `vcgencmd` (présent sur Raspberry Pi OS), `systemctl`, `timedatectl`, `journalctl`. **Pas de `jq`, pas de Python** (non garanti sur les Pi).
- Temps d'exécution cible **< 2 s**, empreinte négligeable. Aucune commande lourde dans le mode normal.
- Doit passer **ShellCheck** sans avertissement.
- Ne jamais échouer bruyamment : une métrique indisponible est envoyée à `null`, pas d'abandon du rapport.
- Déployé à l'identique sur les 4 Pi (y compris le Pi 5, qui poste vers `localhost`).

### 2.2 Modes d'exécution
| Mode | Déclenchement | Contenu |
|---|---|---|
| normal | cron `*/5 * * * *` | métriques rapides (§2.3) |
| `--full` | cron `0 6 * * *` | métriques rapides + métriques quotidiennes (§2.4) |
| `--dry-run` | manuel | affiche le JSON sur stdout sans l'envoyer (debug) |

### 2.3 Métriques rapides (à chaque exécution)
| Clé JSON | Source | Notes |
|---|---|---|
| `cpuTempC` | `/sys/class/thermal/thermal_zone0/temp` ÷ 1000 | 1 décimale |
| `throttledRaw` | `vcgencmd get_throttled` | valeur hex brute, ex. `"0x50000"` ; l'interprétation des bits est faite côté app |
| `load1`, `load5`, `load15` | `/proc/loadavg` | |
| `cpuCores` | `nproc` | |
| `uptimeSec` | `/proc/uptime` | entier |
| `bootTime` | `uptime -s` | ISO-8601 local ; sert à détecter les reboots |
| `memTotalKb`, `memAvailableKb` | `/proc/meminfo` | |
| `swapTotalKb`, `swapFreeKb` | `/proc/meminfo` | |
| `disks[]` | `df -P -k` sur les points de montage listés dans la conf (défaut `/`) | `{mount, totalKb, usedKb, usedPct}` |
| `rootReadOnly` | options de `/` dans `/proc/mounts` | `true` si `ro` → symptôme typique de carte SD mourante |
| `services[]` | `systemctl is-active <unit>` pour chaque unité de la conf | `{name, state}` (`active`, `inactive`, `failed`…) |
| `failedUnits[]` | `systemctl --failed --no-legend --plain` | noms des unités en échec |
| `ntpSynced` | `timedatectl show -p NTPSynchronized --value` | booléen |
| `topProcesses[]` | `ps -eo comm,rss --sort=-rss` (3 premiers) | `{name, rssKb}` — aide au diagnostic mémoire |
| `powerW` | Pi 5 uniquement : somme des `*_V` × `*_A` de `vcgencmd pmic_read_adc` | `null` sur Pi 3 ; estimation, pas une mesure murale |

### 2.4 Métriques quotidiennes (mode `--full`)
| Clé JSON | Source | Notes |
|---|---|---|
| `aptUpgradable` | `apt-get -s upgrade` → nombre de lignes `^Inst` | **ne pas** lancer `apt-get update` (lourd, nécessite root) ; se base sur le cache existant |
| `kernelIoErrors24h` | `journalctl -k --since "-24h"` filtré sur `I/O error` ou `mmc.*error` | `null` si accès refusé |
| `osRelease` | `PRETTY_NAME` de `/etc/os-release` | |
| `kernel` | `uname -r` | |

### 2.5 Format du rapport (JSON)
Construit avec `printf` (pas de `jq`). Échapper `"` et `\` dans les chaînes libres (noms de process, d'unités).

```json
{
  "schemaVersion": 1,
  "host": "pi-backend-1",
  "mode": "normal",
  "collectedAt": "2026-10-18T16:40:00+02:00",
  "cpuTempC": 56.4,
  "throttledRaw": "0x0",
  "load1": 0.42, "load5": 0.51, "load15": 0.48, "cpuCores": 4,
  "uptimeSec": 864000, "bootTime": "2026-10-08T16:40:00",
  "memTotalKb": 948280, "memAvailableKb": 210000,
  "swapTotalKb": 102396, "swapFreeKb": 80000,
  "disks": [ { "mount": "/", "totalKb": 30000000, "usedKb": 12000000, "usedPct": 40 } ],
  "rootReadOnly": false,
  "services": [ { "name": "solarinverter.service", "state": "active" } ],
  "failedUnits": [],
  "ntpSynced": true,
  "topProcesses": [ { "name": "java", "rssKb": 310000 } ],
  "powerW": null,
  "daily": null
}
```
En mode `--full`, `daily` = `{ "aptUpgradable": 12, "kernelIoErrors24h": 0, "osRelease": "...", "kernel": "..." }`.

### 2.6 Envoi
```
curl -fsS --max-time 10 -X POST \
  -H "Content-Type: application/json" \
  -H "X-Monitor-Token: $API_TOKEN" \
  --data-binary @- "$MONITOR_URL/api/reports"
```
- 1 nouvelle tentative après 10 s en cas d'échec.
- En cas d'échec définitif : `logger -t sentinel-report "envoi KO: <raison>"` (journald) et code de sortie ≠ 0. **Pas** de file d'attente locale : l'absence de rapport est justement ce que l'app doit détecter.

### 2.7 Configuration `/etc/sentinel/agent.conf` (sourcé par le script, `chmod 640`)
```bash
HOST_NAME="pi-backend-1"                     # doit correspondre à monitoring.hosts[].name côté app
MONITOR_URL="http://<ip-pi5>:8090"           # sur le Pi 5 : http://localhost:8090
API_TOKEN="<secret propre à cet hôte>"
DISK_MOUNTS="/"                              # liste séparée par des espaces
SERVICES="solarinverter.service mymenuapp.service"   # unités systemd attendues actives
```

### 2.8 Installation
- Script : `/usr/local/bin/sentinel-report.sh` (`755`).
- Utilisateur système dédié `sentinel-agent`, membre des groupes `video` (pour `vcgencmd`) et `systemd-journal` (pour `journalctl`). Le script ne tourne **pas** en root.
- Cron : `/etc/cron.d/sentinel`
  ```
  */5 * * * * sentinel-agent /usr/local/bin/sentinel-report.sh
  0 6 * * *   sentinel-agent /usr/local/bin/sentinel-report.sh --full
  ```
- Fournir un script `install-agent.sh` idempotent qui crée l'utilisateur, copie le script, crée `agent.conf` depuis un modèle s'il n'existe pas, installe le cron.

---

## 3. Composant B — application `sentinel`

### 3.1 Stack
- Java 21, Spring Boot 3.x (dernière version stable), Maven.
- Dépendances : `spring-boot-starter-web`, `-validation`, `-actuator`, `-thymeleaf`, `-mail`, `-data-jpa`, SQLite (`org.xerial:sqlite-jdbc` + `org.hibernate.orm:hibernate-community-dialects`), Flyway pour le schéma.
- Client HTTP : `RestClient` (JDK HttpClient sous-jacent), timeouts explicites.
- Toutes les tâches planifiées en zone `Europe/Paris`.
- Pas de front séparé en v1 : pages rendues côté serveur avec Thymeleaf, **responsive mobile-first**, CSS inline minimal, aucune dépendance JS externe.

### 3.2 Deux ports, deux surfaces d'exposition (exigence de sécurité)
L'app écoute sur **deux connecteurs Tomcat** :

| Port | Nom | Accessible depuis | Routes autorisées |
|---|---|---|---|
| `8090` | interne | réseau local uniquement | tout : `/api/**`, `/actuator/**`, `/` (page d'état), `/ack/**` |
| `8091` | public | Internet via reverse proxy NAS | **uniquement** `GET/POST /ack/{token}`, `/public-assets/**`, `GET /report` et `POST /report`, `GET /api/status` |

Implémentation :
- Connecteur additionnel via `WebServerFactoryCustomizer<TomcatServletWebServerFactory>`.
- Un `OncePerRequestFilter` en tête de chaîne : si `request.getLocalPort() == publicPort` et que le chemin ne correspond pas à la liste blanche → **404** avec corps vide (ne rien révéler).
- Actuator : `management.server.port` = port interne (ou même port interne), jamais exposé sur 8091.
- Activer `server.forward-headers-strategy=native` pour exploiter `X-Forwarded-For` / `X-Forwarded-Proto` envoyés par le NAS (logs avec IP réelle).
- Tests obligatoires : chaque route non autorisée renvoie 404 sur 8091 (cf. §8).

### 3.3 API interne
| Méthode | Chemin | Description |
|---|---|---|
| `POST` | `/api/reports` | Réception d'un rapport. Header `X-Monitor-Token` obligatoire, comparé (temps constant) au token configuré pour `host`. 401 si invalide, 400 si JSON invalide, 202 si OK. Hôte inconnu → 404. |
| `GET` | `/api/status` | État courant complet en JSON (hôtes, apps, incidents ouverts). |
| `GET` | `/` | Page d'état HTML interne (même gabarit que la page de rapport, sans bouton d'accusé). |
| `GET` | `/actuator/health` | Santé de sentinel (utilisée par le NAS). Inclure un indicateur custom : dernier cycle d'évaluation < 10 min. |
| `POST` | `/api/test/push` | Envoie une notif Pushbullet de test. |
| `POST` | `/api/test/mail` | Envoie un e-mail de test. |
| `POST` | `/api/digest/now` | Force l'envoi d'un digest (debug). |

### 3.4 Modèle de données (SQLite, migrations Flyway)
- `host` : `name` (PK), `model`, `last_report_at`, `last_boot_time`, `state` (OK/WARN/CRIT/SILENT).
- `report` : `id`, `host`, `received_at`, `collected_at`, `mode`, `payload_json` (brut), colonnes extraites pour requêtes : `cpu_temp_c`, `mem_available_pct`, `swap_used_pct`, `max_disk_pct`, `load1`, `throttled_raw`.
- `app_check` : `id`, `app`, `kind` (INTERNAL/EXTERNAL), `checked_at`, `ok`, `http_status`, `latency_ms`, `error`.
- `check_state` : état courant de chaque « sujet » surveillé — `subject_key` (PK, ex. `host:pi-backend-1:disk:/`, `app:menu-back:external`), `state` (OK/WARN/CRIT), `consecutive_breaches`, `consecutive_ok`, `since`, `last_value`, `last_notified_at`.
- `incident` : `id`, `subject_key`, `severity`, `message`, `opened_at`, `closed_at`.
- `access_token` : `id`, `type` (DIGEST/VIEW), `token_hash` (SHA-256, jamais le token en clair), `created_at`, `expires_at`, `snapshot_json`, `acked_at`, `ack_ip`, `escalation_count`, `last_escalation_at`.
- `notification_log` : `id`, `channel` (PUSH/MAIL), `kind`, `sent_at`, `success`, `error`.
- `host_daily_stats` (préparation v2) : `host`, `day`, `temp_min/avg/max`, `max_disk_pct`, `max_mem_used_pct`, `reboots`, `reports_received`, `reports_expected`.

Rétention (job nocturne 03:30) : `report` et `app_check` 30 jours ; `incident`, `notification_log`, `host_daily_stats` 1 an ; `access_token` 30 jours après expiration. Agrégation de `host_daily_stats` faite par le même job pour la veille.

### 3.5 Moteur de règles (évaluation des rapports)
Évalué à chaque rapport reçu. Chaque règle produit OK / WARN / CRIT pour un sujet. Seuils **configurables** (§6), valeurs par défaut :

| Sujet | WARN | CRIT | Remarques |
|---|---|---|---|
| Température CPU | ≥ 70 °C | ≥ 80 °C | seuils surchargeables par hôte (Pi 5 : 75 / 85) |
| Throttling **actuel** (bits 0x1, 0x2, 0x4, 0x8) | — | bit 0x1 (sous-tension) ou 0x4 (throttling) actif | 0x2/0x8 → WARN |
| Throttling **passé** (bits 0x10000, 0x20000, 0x40000, 0x80000) | présent | — | info « depuis le boot » ; affichée dans le digest, pas d'alerte push répétée |
| Disque (par point de montage) | ≥ 80 % | ≥ 90 % | |
| `/` en lecture seule | — | toujours | |
| Mémoire disponible | < 15 % | < 5 % | `memAvailable / memTotal` |
| Swap utilisé | ≥ 50 % | ≥ 85 % | ignoré si `swapTotal = 0` |
| Load1 | > 2 × cœurs | > 4 × cœurs | |
| Service attendu non `active` | — | toujours | |
| Unités systemd en échec | ≥ 1 | — | |
| NTP non synchronisé | toujours | — | |
| Reboot détecté (`bootTime` a changé) | événement | — | notif informative unique « pi-xxx a redémarré à HH:MM » |
| Mises à jour apt | — | — | info uniquement (digest + hebdo) |
| Erreurs I/O noyau 24 h | ≥ 1 | ≥ 20 | rapport `--full` uniquement |
| **Silence** : pas de rapport depuis > `silence-threshold` (15 min) | — | toujours | évalué par un job toutes les minutes ; délai de grâce de 15 min après démarrage de sentinel |

**Anti-flapping** : un sujet passe en WARN/CRIT après `breach-count` évaluations consécutives en dépassement (défaut 2 pour les métriques, 1 pour silence, lecture seule, service inactif). Retour à OK après `recovery-count` évaluations OK consécutives (défaut 2).

### 3.6 Vérification des applications
- Job toutes les `app-check-interval` (défaut 2 min), checks exécutés **en parallèle** (virtual threads), timeout 5 s par appel.
- Pour chaque app : check **interne** (`internal-url`) et, si renseigné, check **externe** (`external-url`, passe par DNS + routeur (NAT loopback) + reverse proxy NAS + TLS).
- Succès = statut HTTP attendu (défaut 200) et, si `expect-body-contains` est défini, présence de la chaîne dans le corps.
- Support d'en-têtes optionnels par app (ex. Basic auth pour l'API Domoticz).
- Anti-flapping : `breach-count` = 3 pour les apps.
- **Diagnostic croisé** (dans le message d'alerte) :

| Interne | Externe | Message |
|---|---|---|
| OK | OK | — |
| OK | KO | « `<app>` tourne mais est injoignable via le proxy (NAS, certificat, DNS ou routeur) » |
| KO | KO | « `<app>` ne répond plus » |
| KO | OK | « incohérence : check interne KO, externe OK (vérifier internal-url) » |

- **Corrélation avec l'hôte** : si l'hôte de l'app est SILENT, ne pas émettre d'alerte séparée pour ses apps ; une seule alerte « `<host>` injoignable (apps concernées : …) ».
- **Certificats** : pour chaque `external-url`, lire la date d'expiration du certificat serveur lors du check (au plus une fois par jour). WARN à ≤ 14 jours, CRIT à ≤ 3 jours.

### 3.7 Politique de notification Pushbullet
- API : `POST https://api.pushbullet.com/v2/pushes`, header `Access-Token`, JSON. `device_iden` optionnel (config) pour cibler uniquement le smartphone.
- **Toutes les notifs sont de type `link`** pour permettre d'ouvrir le détail :
  - alertes et digest : URL `https://sentinel.<your-domain>/ack/{token}`.
  - alertes → token de type VIEW (consultation seule, pas d'accusé requis) ; digest → token DIGEST.
- On notifie **uniquement sur transition** : OK→WARN, OK→CRIT, WARN→CRIT, *→OK (« résolu »), plus les événements (reboot, démarrage de sentinel).
- **Regroupement** : toutes les transitions détectées pendant un même cycle (fenêtre de 30 s) sont envoyées dans **une seule** notif.
- **Rappel** : si un sujet reste CRIT, nouvelle notif toutes les `crit-reminder-interval` (défaut 24 h).
- **Heures calmes** (configurable, défaut 22:00–07:00) : les WARN et « résolu » sont différés à la fin de la plage ; les CRIT partent immédiatement.
- Format :
  - titre : `⚠️ Monitoring : 2 problèmes` / `🔴 Monitoring : pi-backend-1 injoignable` / `✅ Monitoring : résolu`
  - corps : une ligne par sujet, ex. `pi-backend-1 · disque / 91 %`, `menu-back · injoignable via le proxy`.
- **Échec Pushbullet** (réseau, 5xx, 429, 401) : 2 tentatives avec backoff (5 s, 30 s), puis **envoi immédiat du même contenu par e-mail**, et incident `pushbullet` ouvert (il sera signalé dans le digest suivant).
- À chaque démarrage de sentinel : notif `ℹ️ sentinel démarré` (permet de repérer un reboot du Pi 5).
- Compter les pushes du mois (`notification_log`) et l'afficher dans le digest et le rapport hebdo.

### 3.8 Digest quotidien et accusé de lecture
**Envoi** : tous les jours à `digest-time` (défaut 08:00).
- Générer un token aléatoire de 32 octets (`SecureRandom`, Base64 URL-safe sans padding). Stocker **uniquement son SHA-256**. Validité `token-validity` (défaut 7 jours).
- Enregistrer un **snapshot** JSON de l'état global au moment de l'envoi (`snapshot_json`).
- Push `link` :
  - tout va bien : titre `✅ Monitoring : tout va bien`, corps ex. `4 Pi · 6 apps OK · max 58 °C · 3 mises à jour dispo`.
  - sinon : titre `⚠️ Monitoring : N problèmes`, corps = liste courte des sujets non OK.
  - URL : `https://sentinel.<your-domain>/ack/{token}`.

**Page `GET /ack/{token}`** (servie sur les deux ports) :
- Token inconnu ou expiré → 404 générique (même réponse dans les deux cas).
- Contenu, mobile-first :
  1. Bandeau d'état global (vert / orange / rouge).
  2. Section « État au moment de l'envoi » (snapshot) puis « État actuel » (si différent).
  3. Une carte par Pi : température, disque(s), RAM, swap, load, uptime, throttling (actuel + depuis le boot), services, mises à jour apt.
  4. Une ligne par app : interne / externe, latence, expiration du certificat.
  5. Incidents des dernières 24 h (ouverts et résolus, avec durées).
  6. Pied : nombre de pushes ce mois-ci, version de sentinel.
  7. Pour un token DIGEST non acquitté : bouton **« J'ai pris connaissance »** (formulaire `POST` vers la même URL). Si déjà acquitté : « Confirmé le JJ/MM à HH:MM ».
- **Ne jamais afficher** d'IP internes, de ports, d'URL internes ni de noms de fichiers de conf : uniquement les noms logiques.
- En-têtes : `Cache-Control: no-store`, `X-Robots-Tag: noindex, nofollow`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy` stricte (pas de JS nécessaire).

**`POST /ack/{token}`** :
- Valide uniquement pour un token DIGEST non expiré. Idempotent : un second POST n'altère pas `acked_at`.
- Enregistre `acked_at` et `ack_ip` (IP réelle via `X-Forwarded-For`), puis redirige (303) vers la page en GET.
- Un accusé sur un digest **acquitte aussi les digests plus anciens** encore ouverts.
- **Pourquoi un POST** : les aperçus de liens et certains antivirus mobiles préchargent les URL en GET ; un GET ne doit jamais valider l'accusé.

**Rate limiting** sur le port public : 30 requêtes / minute / IP (bucket en mémoire suffisant), au-delà 429.

### 3.9 Escalade par e-mail
Job toutes les heures :
- Si le dernier digest n'est pas acquitté et que `now − sent_at > ack-timeout` (défaut **36 h**) → e-mail d'escalade.
- Puis relance toutes les 24 h tant qu'aucun accusé n'est donné (`escalation_count`, `last_escalation_at`).
- Contenu : « Aucun accusé de lecture depuis le JJ/MM HH:MM. Causes possibles : application Pushbullet inactive sur le smartphone, compte Pushbullet, notification manquée. » + résumé de l'état + lien vers le rapport.
- Autres cas d'envoi d'e-mail : échec Pushbullet (§3.7), rapport hebdomadaire (§3.10), endpoint de test.
- SMTP Strato : `<smtp-host>`, port 465 SSL (ou 587 STARTTLS, à valider), authentification avec la boîte <your-domain>. Timeout de connexion et de lecture : 10 s.
- E-mails en HTML (gabarits Thymeleaf partagés avec la page de rapport) + alternative texte.

### 3.10 Rapport hebdomadaire (e-mail)
Lundi 08:00. Contenu :
- Incidents de la semaine (nombre, durée cumulée par sujet).
- Par Pi : température min/moy/max, disque max et tendance (+x % sur 7 jours), mémoire max, reboots, disponibilité (rapports reçus / attendus).
- Par app : disponibilité interne et externe en %.
- Mises à jour apt disponibles, expirations de certificats à venir.
- Accusés de lecture de la semaine (7/7 ?) et nombre de pushes du mois.

Ce mail sert aussi de **heartbeat du canal e-mail** : pas de mail le lundi = problème SMTP ou Pi 5.

### 3.11 Logs et observabilité
- Logs sur stdout → journald (pas de fichier). Niveau INFO par défaut.
- Ne jamais logger les tokens d'accès, le token Pushbullet, les identifiants SMTP ni les `X-Monitor-Token`.
- Indicateur actuator custom `evaluation` : DOWN si aucun cycle d'évaluation depuis 10 min.

---

## 4. Supervision de sentinel lui-même (à faire sur le NAS)
sentinel ne peut pas signaler sa propre panne. Sur le NAS (DSM → Panneau de configuration → Planificateur de tâches → Script défini par l'utilisateur), toutes les 15 min :
```bash
#!/bin/sh
curl -fsS --max-time 10 http://<ip-pi5>:8090/actuator/health | grep -q '"status":"UP"' \
  || { echo "sentinel KO sur le Pi 5"; exit 1; }
```
Cocher « Envoyer les détails d'exécution par e-mail uniquement si le script se termine anormalement ». L'agent fournit ce script dans `deploy/nas/check-sentinel.sh`.

---

## 5. Exposition et réseau (à faire par Eric)
- DNS : `sentinel.<your-domain>` → IP publique fixe.
- Certificat Let's Encrypt DSM couvrant `sentinel.<your-domain>`, associé à l'entrée du reverse proxy.
- Reverse proxy DSM : `https://sentinel.<your-domain>:443` → `http://<ip-pi5>:8091` (**port public uniquement**), en-têtes `X-Forwarded-For` / `X-Forwarded-Proto` activés.
- Vérifier le NAT loopback : `curl https://solar-back.<your-domain>/api/status` depuis un Pi doit répondre (sinon les checks externes échoueront tous).

---

## 6. Configuration

### 6.1 `application.yml` (versionné, sans secret)
```yaml
server:
  port: 8090                       # port interne
sentinel:
  public-port: 8091
  public-base-url: https://sentinel.<your-domain>
  timezone: Europe/Paris

  hosts:
    - name: pi-domoticz
      model: pi3
      ip: <pi-domoticz-ip>             # utilisé seulement en interne, jamais affiché publiquement
    - name: pi-backend-1
      model: pi3
      ip: <ip>
    - name: pi-backend-2
      model: pi3
      ip: <ip>
    - name: pi5
      model: pi5
      ip: <ip>
      thresholds:                  # surcharge par hôte
        cpu-temp-warn: 75
        cpu-temp-crit: 85

  apps:
    - name: domoticz
      host: pi-domoticz
      internal-url: http://<pi-domoticz-ip>:8080/json.htm?type=command&param=getversion
      external-url: https://domotique.<your-domain>/json.htm?type=command&param=getversion
      expect-body-contains: '"status" : "OK"'
    - name: solar-back
      host: <pi-backend-x>
      internal-url: http://<ip>:<port>/api/status
      external-url: https://solar-back.<your-domain>/api/status
    - name: menu-back
      host: <pi-backend-x>
      internal-url: http://<ip>:<port>/api/status
      external-url: https://menu-back.<your-domain>/api/status
    - name: dmarc-dashboard
      host: <pi-backend-x>
      internal-url: http://<ip>:<port>/<chemin>
      external-url: <optionnel>
    - name: choreminder
      host: pi5
      internal-url: http://localhost:<port>/<chemin>
      external-url: https://choreminder.<your-domain>/<chemin>

  thresholds:                      # valeurs par défaut (§3.5)
    cpu-temp-warn: 70
    cpu-temp-crit: 80
    disk-warn-pct: 80
    disk-crit-pct: 90
    mem-available-warn-pct: 15
    mem-available-crit-pct: 5
    swap-used-warn-pct: 50
    swap-used-crit-pct: 85
    load-warn-per-core: 2
    load-crit-per-core: 4
    cert-warn-days: 14
    cert-crit-days: 3

  evaluation:
    report-interval: 5m
    silence-threshold: 15m
    startup-grace: 15m
    breach-count: 2
    recovery-count: 2
    app-check-interval: 2m
    app-check-timeout: 5s
    app-breach-count: 3

  notifications:
    crit-reminder-interval: 24h
    quiet-hours: { enabled: true, start: "22:00", end: "07:00" }
    grouping-window: 30s

  digest:
    time: "08:00"
    token-validity: 7d
    ack-timeout: 36h
    escalation-interval: 24h

  weekly-report:
    cron: "0 0 8 * * MON"

  retention:
    raw: 30d
    history: 365d

spring:
  config:
    import: "optional:file:/opt/sentinel/config/secrets.yml"
  datasource:
    url: jdbc:sqlite:/opt/sentinel/data/sentinel.db
```

### 6.2 `/opt/sentinel/config/secrets.yml` (hors dépôt, `chmod 600`, propriétaire `sentinel`)
```yaml
sentinel:
  host-tokens:                     # un secret différent par Pi (= API_TOKEN de agent.conf)
    pi-domoticz: <secret>
    pi-backend-1: <secret>
    pi-backend-2: <secret>
    pi5: <secret>
  pushbullet:
    access-token: <token>
    device-iden: <optionnel>
  mail:
    from: <adresse>@<your-domain>
    to: <adresse de réception>
  apps-auth:                       # en-têtes optionnels par app
    domoticz: { basic-user: <user>, basic-password: <pwd> }
spring:
  mail:
    host: <smtp-host>
    port: 465
    username: <adresse>@<your-domain>
    password: <mot de passe>
    properties:
      mail.smtp.auth: true
      mail.smtp.ssl.enable: true
      mail.smtp.connectiontimeout: 10000
      mail.smtp.timeout: 10000
```
Fournir un `secrets.example.yml` dans le dépôt et ajouter `secrets.yml` au `.gitignore`. Au démarrage, **échouer explicitement** si un secret obligatoire manque (validation `@ConfigurationProperties` + `@Validated`).

---

## 7. Déploiement sur le Pi 5
- Utilisateur système `sentinel`, répertoires `/opt/sentinel/{app,config,data}`.
- Service systemd `sentinel.service` :
  - `ExecStart=/usr/bin/java -Xms64m -Xmx256m -XX:+UseSerialGC -jar /opt/sentinel/app/sentinel.jar`
  - `Restart=on-failure`, `RestartSec=10`, `User=sentinel`, `WorkingDirectory=/opt/sentinel`.
  - Durcissement : `NoNewPrivileges=true`, `ProtectSystem=strict`, `ReadWritePaths=/opt/sentinel/data`, `PrivateTmp=true`.
- Fournir dans `deploy/` : l'unité systemd, un script `deploy.sh` (copie du jar + redémarrage), le script NAS (§4), le script d'installation de l'agent (§2.8).
- `README.md` : installation pas à pas (agent sur chaque Pi, app sur le Pi 5, règle reverse proxy NAS, tâche NAS, tests de bout en bout).

---

## 8. Tests attendus
**Script** :
- ShellCheck sans avertissement.
- Mode `--dry-run` : sortie JSON valide (vérifiée par un test qui la passe à un parseur JSON, sur la machine de dev).
- Tests (bats ou script de test) avec fichiers `/proc` et `/sys` simulés via variables d'environnement surchargeables (`PROC_ROOT`, `SYS_ROOT`) : valeurs nominales, `vcgencmd` absent, `/` en lecture seule, service inactif.

**Application** :
- Unitaires : chaque règle du §3.5 (seuils, surcharge par hôte, bits de throttling), machine d'état anti-flapping, regroupement des notifs, heures calmes, diagnostic croisé interne/externe, corrélation hôte silencieux, génération et hachage des tokens, expiration.
- Intégration (`@SpringBootTest`, ports aléatoires) :
  - **Port public** : toutes les routes hors `/ack/**`, `/public-assets/**`, `/report` et `/api/status` → 404 sur le port public ; elles répondent normalement sur le port interne.
  - `POST /api/reports` : token valide / invalide / hôte inconnu / JSON invalide.
  - Pushbullet simulé (WireMock) : succès, 5xx avec bascule e-mail, 429.
  - SMTP simulé (GreenMail) : escalade après `ack-timeout`, relance après `escalation-interval`, arrêt après accusé.
  - Accusé : GET n'acquitte pas, POST acquitte, double POST idempotent, accusé d'un digest récent acquitte les anciens, token expiré → 404.
  - Silence d'un hôte → une seule alerte, pas d'alertes d'apps de cet hôte.
- Utiliser une horloge injectable (`java.time.Clock`) pour tester les délais sans attendre.

## 9. Critères d'acceptation (bout en bout)
1. Les 4 Pi envoient un rapport toutes les 5 min ; la page `/` interne les affiche.
2. Arrêter le service d'une app → notif « ne répond plus » sous ~6 min ; le relancer → notif « résolu ».
3. Couper un Pi → une seule notif « injoignable » sous ~15 min.
4. Désactiver la règle reverse proxy d'une app → notif « injoignable via le proxy ».
5. Digest reçu à 08:00 ; le lien s'ouvre en 4G hors du domicile ; le bouton acquitte ; `https://sentinel.<your-domain>/api/status` renvoie le healthcheck JSON depuis l'extérieur (exposé intentionnellement sur le port public) ; `/actuator/health` renvoie 404 depuis l'extérieur.
6. Sans accusé pendant 36 h → e-mail reçu sur la boîte <your-domain>.
7. Token Pushbullet volontairement invalide → e-mail de bascule reçu.
8. Arrêter sentinel → e-mail DSM du NAS sous 15 min.
9. Rapport hebdomadaire reçu le lundi.

## 10. Évolutions v2 (ne pas implémenter, mais ne pas bloquer)
- Dashboard Angular sur le port interne : historique et graphiques (température 30 jours, tendance disque, disponibilité), à partir de `report` et `host_daily_stats`. Prévoir dès v1 une API JSON propre (`/api/status`) réutilisable.
- Consommation électrique réelle via prises connectées remontées par Domoticz (API JSON).
- Sonde externe réelle (UptimeRobot ou équivalent) pour détecter une coupure de la connexion Internet.
- Module Pushbullet + accusé de lecture extrait en bibliothèque partagée avec choreminder.

## 11. Points à compléter avant déploiement
- [ ] IP du Pi 5, de pi-backend-1 et de pi-backend-2.
- [ ] Répartition des apps (solarinverter, mymenuapp, dmarcdashboard) entre pi-backend-1 et pi-backend-2, leurs ports et unités systemd.
- [ ] URL de statut de chaque app (interne et externe), dont dmarcdashboard et choreminder.
- [ ] Ports 8090 / 8091 libres sur le Pi 5 (pas de conflit avec choreminder ou l'app caméra).
- [ ] Authentification de l'API Domoticz (réseaux locaux autorisés ou identifiants).
- [ ] Token Pushbullet, `device_iden` du smartphone (optionnel).
- [ ] Adresse e-mail <your-domain> d'envoi et de réception, port SMTP Strato (465 ou 587).
- [ ] DNS `sentinel.<your-domain>`, certificat, règle reverse proxy, test NAT loopback.
