-- Ortam runtime veritabanı: her ortamda ayrı (tasarım dokümanı, "Ortam runtime DB'si ve cache senkronizasyonu").
-- Broker'lar okur (broker_runtime_reader), ortam Admin API yazar (broker_runtime_writer).
-- Şemayı Flyway oluşturur (spring.flyway.schemas); tablolar o şemada açılır.

CREATE TABLE runtime_project (
    maintask    VARCHAR(100) PRIMARY KEY,
    base_url    VARCHAR(512) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE runtime_response_profile (
    name          VARCHAR(100) PRIMARY KEY,
    profile_id    BIGINT       NOT NULL,                      -- merkezi profilin ID'si
    version       INTEGER      NOT NULL,                      -- merkezi versiyon; loglara yazılır
    definition    JSONB        NOT NULL,
    applied_by    VARCHAR(100) NOT NULL,
    promotion_id  BIGINT       NOT NULL,                      -- merkezi promotion_request.id
    applied_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE runtime_route (
    maintask          VARCHAR(100) NOT NULL REFERENCES runtime_project (maintask),
    subtask           VARCHAR(150) NOT NULL,
    definition_id     BIGINT       NOT NULL,                  -- merkezi tanımın ID'si
    version           INTEGER      NOT NULL,                  -- merkezi versiyon; loglara route_version olarak yazılır
    http_method       VARCHAR(8)   NOT NULL,
    path_template     VARCHAR(512) NOT NULL,
    permissions       TEXT[]       NOT NULL DEFAULT '{}',     -- proje ve başka proje yetkileri; biri yeterli
    anonymous         BOOLEAN      NOT NULL DEFAULT FALSE,
    response_profile  VARCHAR(100) REFERENCES runtime_response_profile (name),  -- ortamda olmayan profile referans verilemez
    definition        JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    applied_by        VARCHAR(100) NOT NULL,
    promotion_id      BIGINT       NOT NULL,                  -- merkezi promotion_request.id
    applied_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (maintask, subtask),
    CONSTRAINT ck_runtime_permission CHECK (anonymous OR cardinality(permissions) > 0)
);

-- Cache senkronizasyonu için tek satırlık global sayaç
CREATE TABLE runtime_config_version (
    id          SMALLINT    PRIMARY KEY CHECK (id = 1),
    version     BIGINT      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO runtime_config_version (id, version) VALUES (1, 0);

-- Rolleri DBA oluşturur; olmayan rol için GRANT atlanır (yerel geliştirme ve testler)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'broker_runtime_reader') THEN
        GRANT USAGE ON SCHEMA broker_runtime TO broker_runtime_reader;
        GRANT SELECT ON ALL TABLES IN SCHEMA broker_runtime TO broker_runtime_reader;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'broker_runtime_writer') THEN
        GRANT USAGE ON SCHEMA broker_runtime TO broker_runtime_writer;
        GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA broker_runtime TO broker_runtime_writer;
    END IF;
END
$$;
