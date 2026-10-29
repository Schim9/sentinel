# CLAUDE.md

Ce fichier fournit des instructions à Claude Code (claude.ai/code) pour travailler sur ce dépôt.

## Vue d'ensemble du projet

**Sentinel** est un système de supervision de l'infrastructure domestique pour 4 Raspberry Pi. Il se compose de deux éléments :

1. **`sentinel-report.sh`** — script Bash de collecte déployé sur chaque Pi (cron toutes les 30 min, mode `--full` à 06h00), envoyant un rapport JSON par HTTP POST.
2. **`sentinel`** — application Spring Boot 3.x / Java 21 sur le Pi 5, qui reçoit les rapports, vérifie la disponibilité des applications et envoie des alertes via Pushbullet et e-mail.

Package Java de base : `lu.kaminski.sentinel`.

La spécification complète se trouve dans `SPEC-sentinel.md`.

## Build & Run

```bash
# Build (depuis la racine du projet)
mvn clean package -DskipTests

# Lancer en local (nécessite secrets.yml)
mvn spring-boot:run

# Lancer une classe de test
mvn test -Dtest=RuleEngineTest

# Lancer une méthode de test précise
mvn test -Dtest=RuleEngineTest#disk_crit

# ShellCheck sur le script agent
shellcheck scripts/sentinel-report.sh
```

## Architecture

### Modèle de sécurité deux ports
L'application écoute sur deux connecteurs Tomcat :
- **Port 8090** (LAN interne uniquement) : toutes les routes — `/api/**`, `/actuator/**`, `/`, `/ack/**`, `/report`
- **Port 8091** (public via reverse proxy NAS) : **uniquement** `/ack/**`, `/public-assets/**`, `/report` et `/api/status`

Un `OncePerRequestFilter` l'applique : toute requête sur le port 8091 ne correspondant pas à la liste blanche retourne **404 avec corps vide**.

### Packages principaux (application Spring Boot)
- `api` — `StatusController` : `GET /` (page HTML interne), `GET /api/status` (healthcheck JSON), `GET /report` (port interne → rapport direct, port public → formulaire secret), `POST /report` (valide secret via `AccessTokenService.timingSafeEquals()`, déclenche `appCheckJob.run()` via virtual thread, rate-limité), `POST /api/test/push`, `POST /api/test/mail`. `StatusService` lit les valeurs numériques directement depuis `CheckState.lastNumeric` (plus de parsers de chaînes) ; `lastAppCheckAt` utilise `AppCheckRepository.findLatestOverall()` (check le plus récent toutes apps confondues).
- `report` — reçoit `POST /api/reports`, valide le token par hôte via `AccessTokenService.timingSafeEquals()`, persiste en SQLite. `ReportService.process()` appelle aussi `checkStateService.closeRemovedServices()` pour nettoyer les services retirés de l'`agent.conf`.
- `evaluation` — moteur de règles évaluant chaque rapport selon les seuils (§3.5 de la spec) ; machine à états anti-flapping dans la table `check_state`. `RuleResult` porte un champ `numericValue` (Double, nullable) alimenté par `RuleEngine` pour les règles numériques (cpu-temp, disk, mem, swap, load, kernel-io-errors) ; `CheckStateService` persiste cette valeur dans la colonne `last_numeric` de `check_state`. `SilenceMonitorJob` initialise `startedAt` via `@PostConstruct` avec le `Clock` injectable. `ConfigCleanupService` ferme au démarrage (sur `ApplicationReadyEvent`) les incidents et supprime les `check_state` orphelins (apps/hôtes retirés de la config).
- `appcheck` — job planifié (intervalle configurable via `evaluation.app-check-interval`, défaut 30 min) vérifiant la disponibilité des apps en interne et en externe, avec diagnostic croisé et vérification du certificat TLS ; parse le JSON `/status` si `parse-status-body: true`. **Pattern obligatoire deux phases** : (1) checks HTTP en parallèle via virtual threads (`java.net.http.HttpClient`) sans aucun accès DB, (2) persist séquentiel dans le thread appelant après jointure — nécessaire car `maximum-pool-size: 1` sur SQLite.
- `notification` — client Pushbullet (type `link` uniquement, avec retry backoff 2×), plages de silence, fenêtre de regroupement, repli sur e-mail. Pendant les heures calmes, les transitions WARN et "résolu" sont différées dans une map `deferred` (par `subjectKey`) ; `flushDeferred()` (package-private) est planifié à la fin de la plage et envoie un récapitulatif avec statut actif ou résolu. CRIT est toujours envoyé immédiatement. `notifyReboot()` et `notifyStartup()` envoient des notifications informatives. **Limitation connue** : la map `deferred` est en mémoire — les notifications différées sont perdues en cas de redémarrage de l'application pendant une plage de silence.
- `digest` — `DigestJob` contient deux jobs : digest quotidien à 08h00 (lien Pushbullet vers `/ack/{token}`) et rapport hebdomadaire le lundi à 08h00 (e-mail HTML). `DigestService` génère un token SecureRandom 32 octets (stocké en SHA-256). Les deux e-mails sont rendus via `TemplateEngine` Thymeleaf (templates `templates/email/digest-email.html` et `templates/email/weekly-report.html`) — ne pas reconstruire ces corps par concaténation de chaînes (risque XSS).
- `ack` — sert `GET/POST /ack/{token}`, gère l'accusé de lecture ; GET n'acquitte jamais, POST acquitte de manière idempotente (DIGEST uniquement). `RateLimiter` appliqué sur les deux endpoints. `AccessTokenService` expose `timingSafeEquals(String a, String b)` (statique) pour la comparaison en temps constant via SHA-256 — utilisée par `ReportController` et `StatusController`.
- `escalation` — job horaire vérifiant les digests non acquittés, escalade par e-mail après 36h. `findTokenValue()` génère un nouveau token DIGEST valide via `AccessTokenService.generate("DIGEST", snapshotJson)` pour inclure un lien cliquable dans l'e-mail d'escalade.
- `retention` — job nocturne à 03h30 nettoyant les rapports, app_checks, incidents, logs et tokens expirés.

### Base de données
SQLite dans `/opt/sentinel/data/sentinel.db` (prod), schéma géré par Flyway. Tables principales : `host`, `report`, `app_check`, `check_state`, `incident`, `access_token`, `notification_log`. La table `check_state` inclut la colonne `last_numeric REAL` (nullable) pour les valeurs brutes des règles numériques. Le fichier `sentinel.db` n'est pas versionné dans les sources.

### Référence infrastructure Pi

Exécuter les slash commands suivants pour charger les infos à jour de chaque Pi :
- `/info-pi-apps` — pi-backend-1 (appsPi, 192.168.0.11)
- `/info-pi-apps2` — pi-backend-2 (appsPi2, 192.168.0.86)
- `/info-pi-domoticz` — pi-domoticz (192.168.0.97)
- `/info-pi-surveillance` — pi-backend-3 / surveillancePi (192.168.0.52)

### Infrastructure connue

| Nom logique  | Hostname       | IP             | OS                              | Apps / services                                                                      |
|--------------|----------------|----------------|---------------------------------|--------------------------------------------------------------------------------------|
| pi-domoticz  | Domoticz       | 192.168.0.97   | Raspberry Pi OS 32-bit (Trixie) | `domoticz.service` (port 8080)                                                       |
| pi-backend-1 | appsPi         | 192.168.0.11   | Ubuntu 24.04 aarch64            | `solarinverter.service` (port 8081), `dmarcdashboard.service` (port 8082)            |
| pi-backend-2 | appsPi2        | 192.168.0.86   | Raspberry Pi OS 32-bit (Trixie) | `mymenuapp.service` (port 8080, ctx `/api`), `gestioncomptes.service` (port 8081)   |
| pi-backend-3 | surveillancePi | 192.168.0.52   | Raspberry Pi OS 64-bit (Trixie) | `surveillanceapp.service`, `sentinel.service` (déployé), choreminder (à déployer)    |

Notes :
- Java sur tous les Pis : 21.0.2-tem via SDKMAN dans `/home/schim/.sdkman/candidates/java/21.0.2-tem/bin/java`
- pi-backend-1 est le seul Pi sous Ubuntu (64-bit) ; les autres tournent sous Raspberry Pi OS
- pi-backend-3 a 4 Go de RAM — les options JVM peuvent être plus généreuses que pour les Pi 3 (1 Go)
- La "future app caméra" de la spec correspond à `surveillanceapp.service`, déjà en cours sur pi-backend-3

Valeurs `SERVICES` pour `agent.conf` :
- pi-domoticz : `domoticz.service`
- pi-backend-1 : `solarinverter.service dmarcdashboard.service`
- pi-backend-2 : `mymenuapp.service gestioncomptes.service`
- pi-backend-3 : `surveillanceapp.service sentinel.service`

URLs de check des apps (internes, ports confirmés) :
- domoticz : `http://192.168.0.97:8080/json.htm?type=command&param=getversion` (attend `"status" : "OK"`)
- menu-back (MyMenuApp) : `http://192.168.0.86:8080/api/status`
- solar-back (SolarInverter) : `http://192.168.0.11:8081/api/status`
- dmarc-dashboard : `http://192.168.0.11:8082/api/status`
- comptes-famille (GestionComptes) : `http://192.168.0.86:8081/api/status`
- choreminder : port interne à confirmer

### Utilitaires de présentation
- `config.DateFormatter` — bean Spring (`@Component`) accessible dans tous les templates Thymeleaf via `@dateFormatter`. Deux méthodes : `format(String iso)` → "dd/MM/yyyy à HH:mm" (Europe/Paris), `formatShort(String iso)` → "dd/MM à HH:mm". Accepte les deux formats produits par l'agent : ISO-8601 UTC (suffixe `Z`) et LocalDateTime sans fuseau (`lastBootTime`).

### Configuration
- `application.yml` — versionné, sans secrets. Tous les seuils et définitions d'hôtes/apps s'y trouvent.
- `/opt/sentinel/config/secrets.yml` — hors dépôt (`chmod 600`). Importé via `spring.config.import: optional:file:...`. L'app doit échouer au démarrage si des secrets requis sont manquants (`@ConfigurationProperties` + `@Validated`).
- `secrets.example.yml` — template commité dans le dépôt.

### Script agent (`scripts/sentinel-report.sh`)
- Bash uniquement : pas de `jq`, pas de Python. JSON construit avec `printf`.
- Doit passer ShellCheck sans avertissement.
- Configuré via `/opt/app/sentinel-agent/agent.conf` (sourcé, `chmod 640`).
- Supporte `--dry-run` (stdout uniquement) et `--full` (ajoute les métriques quotidiennes).
- Utilise les variables d'env `PROC_ROOT` / `SYS_ROOT` pour substituer `/proc` et `/sys` en test.

### Artefacts de déploiement (`scripts/`)
- `sentinel-report.sh` — script agent de collecte déployé sur chaque Pi
- `install-agent.sh` — installeur idempotent de l'agent sur chaque Pi (crée l'utilisateur `sentinel-agent`, installe le cron `/etc/cron.d/sentinel`)
- `agent.conf.example` — modèle de configuration de l'agent
- `DEPLOY.md` — documentation de déploiement de l'agent et compatibilité NAS Synology

## Stratégie de tests

- **Tests unitaires** : moteur de règles (chaque seuil + surcharges par hôte), machine à états anti-flapping (`CheckStateServiceTest` — 10 tests), regroupement des notifications/plages de silence (`NotificationServiceTest` — 7 tests, clock mutable via `AtomicReference`), génération/hachage/expiration des tokens + `timingSafeEquals` (`AccessTokenServiceTest` — 10 tests), logique de diagnostic croisé.
- **Tests d'intégration** (`@SpringBootTest`, ports aléatoires) : isolation des ports (toutes les routes non whitelistées → 404 sur le port public), scénarios d'auth `/api/reports`, Pushbullet avec WireMock, SMTP avec GreenMail (`EscalationJobTest` — 3 tests), cycle de vie des acquittements.
- Utiliser un `java.time.Clock` injectable partout où le temps intervient — ne jamais appeler `Instant.now()` directement.
- Tests agent bats dans `scripts/tests/sentinel-report.bats` avec fixtures dans `scripts/tests/fixtures/` (sous-répertoires `proc/` et `sys/` simulant `PROC_ROOT`/`SYS_ROOT`). Lancer avec `bats scripts/tests/sentinel-report.bats`.

## Contraintes importantes

- Toutes les tâches planifiées utilisent le fuseau horaire `Europe/Paris`.
- Ne jamais logger les tokens (access tokens, token Pushbullet, credentials SMTP, headers `X-Monitor-Token`).
- La page `/ack/{token}` ne doit jamais afficher d'IPs internes, ports, URLs internes ou chemins de fichiers de config.
- `Cache-Control: no-store` + CSP stricte + `X-Robots-Tag: noindex,nofollow` sur toutes les réponses `/ack/**`.
- Options JVM Pi 3 : `-Xms64m -Xmx256m -XX:+UseSerialGC` (ressources limitées).
- Client HTTP : `java.net.http.HttpClient` (JDK natif) avec timeouts explicites (5s par check d'app, 10s pour Pushbullet). PushbulletClient effectue 3 tentatives avec backoff (5s, 30s). Ne pas utiliser `RestClient` Spring pour les appels sortants.
- Threads virtuels pour les checks d'apps en parallèle (phase HTTP uniquement — voir pattern deux phases dans `appcheck`).
