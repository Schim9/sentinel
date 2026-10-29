# Sentinel

Système de supervision d'infrastructure domestique pour Raspberry Pi. Sentinel collecte des métriques système sur chaque Pi, vérifie la disponibilité des applications, et envoie des alertes uniquement sur changement d'état — sans spam.

## Fonctionnalités

- **Collecte légère** : script Bash sans dépendance externe (pas de `jq`, pas de Python), tournant en utilisateur non-root via cron.
- **Centralisation** : application Spring Boot qui reçoit les rapports, évalue les seuils et détecte les silences.
- **Vérification des apps** : checks HTTP internes et externes (via reverse proxy), avec diagnostic croisé et surveillance des certificats TLS.
- **Alertes intelligentes** : anti-flapping, heures calmes (22h–7h), regroupement des notifications, repli automatique sur e-mail si Pushbullet est indisponible.
- **Digest quotidien** : notification Pushbullet avec accusé de lecture — un clic ouvre le rapport d'état complet. Escalade par e-mail si l'accusé tarde trop.
- **Rapport hebdomadaire** : e-mail HTML avec statistiques de la semaine (incidents, disponibilité, températures).
- **Deux surfaces d'exposition** : port interne (LAN) pour tout, port public (reverse proxy) uniquement pour la page d'accusé de lecture.

## Architecture

```
              Internet (port 443)
                      │
               [Reverse proxy NAS / TLS]
                      │
    sentinel.<votre-domaine> ──► Pi central:8091  (port PUBLIC : /ack/** seulement)

Réseau local
┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────────────┐
│   Pi A      │  │   Pi B      │  │   Pi C      │  │   Pi central                 │
│ cron: agent │  │ cron: agent │  │ cron: agent │  │ cron: agent → localhost       │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘  │                              │
       │    POST /api/reports (*/5 min)   │         │ sentinel                     │
       └──────────────┴───────────────┴──┼────────► │   :8090  port INTERNE        │
                                         │          │   :8091  port PUBLIC         │
  ◄── checks internes (http://ip:port)───┤          │   SQLite                     │
  ◄── checks externes (https://…)────────┘          └──────────┬───────────────────┘
                                                               │
                                                    Pushbullet · SMTP
```

Le Pi central se surveille lui-même : le NAS interroge `/actuator/health` toutes les 15 min et envoie un e-mail DSM si l'endpoint ne répond plus.

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 21, Spring Boot 3.x, Spring Data JPA |
| Base de données | SQLite + Flyway |
| Templates | Thymeleaf (pages et e-mails HTML) |
| Notifications | Pushbullet (`link`), SMTP |
| Tests | JUnit 5, Mockito, WireMock, GreenMail |
| Agent | Bash (ShellCheck) + bats |

## Structure du projet

```
sentinel/
├── scripts/
│   ├── sentinel-report.sh      # agent de collecte (déployé sur chaque Pi)
│   ├── install-agent.sh        # installeur idempotent de l'agent
│   ├── agent.conf.example      # modèle de configuration de l'agent
│   ├── DEPLOY.md               # documentation de déploiement
│   └── tests/                  # tests bats de l'agent
├── src/
│   └── main/java/…/sentinel/
│       ├── api/                # StatusController, StatusService (page d'état, /api/status)
│       ├── report/             # réception des rapports des agents
│       ├── evaluation/         # moteur de règles, machine à états, anti-flapping
│       ├── appcheck/           # vérification HTTP des applications
│       ├── notification/       # Pushbullet, SMTP, heures calmes, regroupement
│       ├── digest/             # digest quotidien, rapport hebdomadaire
│       ├── ack/                # accusé de lecture (/ack/{token})
│       ├── escalation/         # escalade e-mail si digest non acquitté
│       ├── retention/          # nettoyage nocturne des données expirées
│       └── config/             # SentinelProperties, sécurité deux ports
├── docs/
│   └── SPEC-sentinel.md        # spécification fonctionnelle complète
├── application.yml             # configuration versionnée (sans secrets)
└── secrets.example.yml         # modèle de secrets (à copier et adapter)
```

## Prérequis

- Java 21
- Maven 3.9+
- ShellCheck (optionnel, pour valider le script agent)
- bats (optionnel, pour les tests de l'agent)

## Démarrage rapide

```bash
# Build
mvn clean package -DskipTests

# Lancer (nécessite secrets.yml)
mvn spring-boot:run

# Tests
mvn test
mvn test -Dtest=RuleEngineTest            # classe précise
mvn test -Dtest=RuleEngineTest#disk_crit  # méthode précise
```

## Configuration

### `application.yml` (versionné)

Contient tous les seuils, la définition des hôtes et des apps, les paramètres d'évaluation. Pas de secret.

Paramètres clés :

```yaml
sentinel:
  public-port: 8091
  public-base-url: https://sentinel.<votre-domaine>

  hosts:
    - name: pi-domoticz
      model: pi3
      ip: 192.168.x.x

  apps:
    - name: mon-app
      host: pi-backend-1
      internal-url: http://192.168.x.x:8080/api/status
      external-url: https://mon-app.<votre-domaine>/api/status
      parse-status-body: true   # lit les champs "status" et "anomalies" de la réponse

  thresholds:
    cpu-temp-warn: 70.0
    cpu-temp-crit: 80.0
    disk-warn-pct: 80
    disk-crit-pct: 90

  digest:
    cron: "0 0 8 * * *"     # heure du digest quotidien
    ack-timeout: PT36H       # délai avant escalade e-mail
```

### `secrets.yml` (hors dépôt, `chmod 600`)

Copier `secrets.example.yml` et renseigner :

```yaml
sentinel:
  host-tokens:
    pi-domoticz: <secret-par-pi>
    pi-backend-1: <secret-par-pi>
  pushbullet:
    access-token: <token>
  mail:
    from: <expediteur>
    to: <destinataire>
  refresh-token: <secret-formulaire>
spring:
  mail:
    host: <smtp-host>
    port: 465
    username: <user>
    password: <password>
```

L'application **refuse de démarrer** si un secret obligatoire est absent.

## Déploiement de l'agent

Sur chaque Pi à surveiller :

```bash
# Copier et adapter la configuration
cp scripts/agent.conf.example /opt/app/sentinel-agent/agent.conf
# Éditer agent.conf : HOST_NAME, MONITOR_URL, API_TOKEN, DISK_MOUNTS, SERVICES

# Installer (crée l'utilisateur système, le cron, etc.)
sudo bash scripts/install-agent.sh
```

Le cron installe deux entrées :
- `*/5 * * * *` — rapport normal (métriques rapides)
- `0 6 * * *` — rapport complet (`--full`, ajoute apt, erreurs I/O noyau, version OS)

Test sans envoi :

```bash
./scripts/sentinel-report.sh --dry-run
shellcheck scripts/sentinel-report.sh
bats scripts/tests/sentinel-report.bats
```

## Déploiement de l'application (Pi central)

```bash
# Build
mvn clean package -DskipTests

# Copier le jar
scp target/sentinel-*.jar user@pi-central:/opt/sentinel/app/sentinel.jar

# Ou utiliser le script fourni
bash scripts/deploy.sh user@pi-central
```

L'unité systemd `scripts/sentinel.service` configure le service avec :
- JVM : `-Xms64m -Xmx256m -XX:+UseSerialGC` (adapté Pi 3 — augmenter pour Pi 4/5)
- Durcissement : `NoNewPrivileges`, `ProtectSystem=strict`, `PrivateTmp`
- Redémarrage automatique en cas d'échec

### Health-check NAS

Pour que le NAS surveille Sentinel lui-même, configurer une tâche planifiée (toutes les 15 min) :

```bash
curl -fsS --max-time 10 http://<ip-pi-central>:8090/actuator/health \
  | grep -q '"status":"UP"' || { echo "Sentinel KO"; exit 1; }
```

Activer l'envoi d'e-mail uniquement en cas d'échec.

## Modèle de sécurité

- **Port 8091 (public)** : filtre `OncePerRequestFilter` en tête de chaîne — tout chemin hors liste blanche retourne `404` sans corps. Aucune information interne n'est exposée.
- **Tokens d'accès** : 32 octets aléatoires (`SecureRandom`), stockés uniquement en SHA-256, validité configurable.
- **Comparaison en temps constant** : `AccessTokenService.timingSafeEquals()` via `MessageDigest.isEqual()` — résistant aux attaques temporelles.
- **Secrets** : jamais loggés, jamais dans le dépôt, validés au démarrage.
- **Page `/ack`** : `Cache-Control: no-store`, CSP stricte, `X-Robots-Tag: noindex`, aucune IP ni URL interne affichée.

## Spécification

La spécification fonctionnelle complète (règles d'évaluation, politique de notification, schéma de base de données) se trouve dans [`docs/SPEC-sentinel.md`](docs/SPEC-sentinel.md).
