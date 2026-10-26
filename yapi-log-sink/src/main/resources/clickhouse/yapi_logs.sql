-- ClickHouse şeması: tasarım dokümanı, "Loglama hattı / ClickHouse".
-- Sink tabloyu oluşturmaz; ilk kurulumda bu dosya bir kez çalıştırılır (clickhouse-client --multiquery < ...).
-- TTL'deki 180 ve 400 gün örnek değerlerdir; saklama süreleri açık bir karardır.

CREATE DATABASE IF NOT EXISTS yapi_logs;

CREATE TABLE yapi_logs.yapi_log
(
    event_time       DateTime64(3, 'UTC'),
    request_id       UUID,
    trace_id         String,
    event_type       Enum8('REQUEST' = 1, 'RESPONSE' = 2),
    environment      LowCardinality(String),
    maintask         LowCardinality(String),
    subtask          LowCardinality(String),
    route_version    UInt32,
    response_profile LowCardinality(String),            -- profil yoksa boş
    response_profile_version UInt32,
    user_id          String,
    client_ip        String,
    user_agent       String,
    application      LowCardinality(String),
    uid              String,                            -- frontend'in gönderdiği
    http_method      LowCardinality(String),
    target_path      String,
    status_code      UInt16,                            -- yalnızca RESPONSE
    outcome          LowCardinality(String),            -- yalnızca RESPONSE
    duration_ms      UInt32,                            -- yalnızca RESPONSE
    body             String CODEC(ZSTD(3)),             -- maskelenmiş; yanıtta kırpılmış olabilir
    body_size        UInt32,
    body_truncated   Bool,
    error_message    String,
    yapi_instance  LowCardinality(String),
    INDEX idx_request_id request_id TYPE bloom_filter GRANULARITY 4,
    INDEX idx_trace_id   trace_id   TYPE bloom_filter GRANULARITY 4,
    INDEX idx_user_id    user_id    TYPE bloom_filter GRANULARITY 4
)
ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (maintask, subtask, event_time, request_id, event_type)
TTL toDateTime(event_time) + INTERVAL 180 DAY;

-- Dakikalık metrikler: dashboard'lar ana tabloyu taramaz
CREATE TABLE yapi_logs.yapi_metrics_1m
(
    minute       DateTime('UTC'),
    environment  LowCardinality(String),
    maintask     LowCardinality(String),
    subtask      LowCardinality(String),
    outcome      LowCardinality(String),
    requests     AggregateFunction(count),
    duration_q   AggregateFunction(quantiles(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(minute)
ORDER BY (environment, maintask, subtask, outcome, minute)
TTL minute + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW yapi_logs.yapi_metrics_1m_mv
TO yapi_logs.yapi_metrics_1m
AS
SELECT
    toStartOfMinute(toDateTime(event_time, 'UTC'))   AS minute,
    environment,
    maintask,
    subtask,
    outcome,
    countState()                                     AS requests,
    quantilesState(0.5, 0.95, 0.99)(duration_ms)     AS duration_q
FROM yapi_logs.yapi_log
WHERE event_type = 'RESPONSE'
GROUP BY minute, environment, maintask, subtask, outcome;
