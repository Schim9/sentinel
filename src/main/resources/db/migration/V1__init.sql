-- Sentinel — schéma initial

CREATE TABLE IF NOT EXISTS host (
    name            TEXT PRIMARY KEY,
    model           TEXT,
    last_report_at  TEXT,
    last_boot_time  TEXT,
    state           TEXT NOT NULL DEFAULT 'OK'  -- OK | WARN | CRIT | SILENT
);

CREATE TABLE IF NOT EXISTS report (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    host                TEXT    NOT NULL REFERENCES host(name),
    received_at         TEXT    NOT NULL,
    collected_at        TEXT    NOT NULL,
    mode                TEXT    NOT NULL,  -- normal | full
    payload_json        TEXT    NOT NULL,
    -- colonnes extraites pour requêtes et agrégations
    cpu_temp_c          REAL,
    mem_available_pct   REAL,
    swap_used_pct       REAL,
    max_disk_pct        REAL,
    load1               REAL,
    throttled_raw       TEXT
);

CREATE INDEX IF NOT EXISTS idx_report_host_received ON report(host, received_at);

CREATE TABLE IF NOT EXISTS app_check (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    app         TEXT    NOT NULL,
    kind        TEXT    NOT NULL,  -- INTERNAL | EXTERNAL
    checked_at  TEXT    NOT NULL,
    ok          INTEGER NOT NULL,  -- 0 | 1
    http_status INTEGER,
    latency_ms  INTEGER,
    error       TEXT
);

CREATE INDEX IF NOT EXISTS idx_app_check_app_kind ON app_check(app, kind, checked_at);

CREATE TABLE IF NOT EXISTS check_state (
    subject_key         TEXT PRIMARY KEY,
    state               TEXT    NOT NULL DEFAULT 'OK',  -- OK | WARN | CRIT
    consecutive_breaches INTEGER NOT NULL DEFAULT 0,
    consecutive_ok       INTEGER NOT NULL DEFAULT 0,
    since               TEXT    NOT NULL,
    last_value          TEXT,
    last_numeric        REAL,
    last_notified_at    TEXT
);

CREATE TABLE IF NOT EXISTS incident (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    subject_key TEXT    NOT NULL,
    severity    TEXT    NOT NULL,  -- WARN | CRIT
    message     TEXT    NOT NULL,
    opened_at   TEXT    NOT NULL,
    closed_at   TEXT
);

CREATE INDEX IF NOT EXISTS idx_incident_subject ON incident(subject_key, closed_at);

CREATE TABLE IF NOT EXISTS access_token (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    type                TEXT    NOT NULL,  -- DIGEST | VIEW
    token_hash          TEXT    NOT NULL UNIQUE,
    created_at          TEXT    NOT NULL,
    expires_at          TEXT    NOT NULL,
    snapshot_json       TEXT,
    acked_at            TEXT,
    ack_ip              TEXT,
    escalation_count    INTEGER NOT NULL DEFAULT 0,
    last_escalation_at  TEXT
);

CREATE INDEX IF NOT EXISTS idx_access_token_expires ON access_token(expires_at);

CREATE TABLE IF NOT EXISTS notification_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    channel     TEXT    NOT NULL,  -- PUSH | MAIL
    kind        TEXT    NOT NULL,
    sent_at     TEXT    NOT NULL,
    success     INTEGER NOT NULL,  -- 0 | 1
    error       TEXT
);

CREATE INDEX IF NOT EXISTS idx_notif_log_sent ON notification_log(sent_at);

