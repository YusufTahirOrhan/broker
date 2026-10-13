-- Merkezi DB: tasarım dokümanındaki DDL ("Merkezi veri modeli"). Şemayı Flyway oluşturur
-- (spring.flyway.schemas=broker_admin); tablolar o şemada açılır.
-- Rolleri DBA oluşturur: broker_admin_app (merkezi admin backend'i)


CREATE TABLE environment (
    code                VARCHAR(16)  PRIMARY KEY,                 -- DEV, TEST, PROD
    name                VARCHAR(64)  NOT NULL,
    sort_order          SMALLINT     NOT NULL UNIQUE,             -- promote sırası: 1 → 2 → 3
    admin_api_url       VARCHAR(512) NOT NULL,                    -- ortamdaki yönetim API'si
    requires_approval   BOOLEAN      NOT NULL DEFAULT FALSE,      -- PROD için true
    test_calls_allowed  BOOLEAN      NOT NULL DEFAULT FALSE       -- "test et" yalnızca DEV/TEST
);

-- History tabloları yalnızca ekleme kabul eder
CREATE FUNCTION forbid_history_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% append-only: % izin verilmez', TG_TABLE_NAME, TG_OP;
END;
$$;

-- Frontend sözleşmesinin parçası olan anahtarlar bir kez yazıldıktan sonra değiştirilemez;
-- boş bir değeri doldurmak serbesttir
CREATE FUNCTION forbid_column_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    col TEXT;
BEGIN
    FOREACH col IN ARRAY TG_ARGV LOOP
        IF to_jsonb(OLD) -> col <> 'null'::jsonb
           AND to_jsonb(NEW) -> col IS DISTINCT FROM to_jsonb(OLD) -> col THEN
            RAISE EXCEPTION '%.% değiştirilemez', TG_TABLE_NAME, col;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

-- Yanıt profilleri: projeye özel response zarfları
CREATE TABLE response_profile (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,                -- ör. order-service; runtime'da referans
    description  TEXT,
    definition   JSONB        NOT NULL,                       -- success ve error şablonları
    version      INTEGER      NOT NULL DEFAULT 1,             -- optimistic lock; history ile aynı numara
    created_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(100) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_profile_name_immutable
    BEFORE UPDATE OF name ON response_profile
    FOR EACH ROW EXECUTE FUNCTION forbid_column_change('name');

CREATE TABLE response_profile_history (
    profile_id  BIGINT       NOT NULL REFERENCES response_profile (id),
    version     INTEGER      NOT NULL,
    action      VARCHAR(16)  NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'ROLLBACK')),
    snapshot    JSONB        NOT NULL,                        -- response_profile satırının tam hali
    changed_by  VARCHAR(100) NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    comment     TEXT,
    PRIMARY KEY (profile_id, version)
);

CREATE TRIGGER trg_profile_history_append_only
    BEFORE UPDATE OR DELETE ON response_profile_history
    FOR EACH ROW EXECUTE FUNCTION forbid_history_change();

CREATE TABLE project (
    id                           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_project_id          VARCHAR(64)  UNIQUE,              -- proje servisindeki ID; geçişte boş kalabilir
    maintask                     VARCHAR(100) NOT NULL,            -- frontend'in gönderdiği ad; tekilliği harf duyarsız
    name                         VARCHAR(200) NOT NULL,            -- proje servisinden gelen görünen ad
    permission_prefix            VARCHAR(50)  NOT NULL UNIQUE,     -- yetki adlarının öneki, ör. LOGPROJECT
    catalog_status               VARCHAR(16)  NOT NULL DEFAULT 'LINKED'
                                 CHECK (catalog_status IN ('LINKED', 'MISSING', 'UNLINKED')),
    catalog_synced_at            TIMESTAMPTZ,
    owner_team                   VARCHAR(100),
    description                  TEXT,
    default_response_profile_id  BIGINT REFERENCES response_profile (id),  -- yeni subtask ekranında ön seçim
    created_by                   VARCHAR(100) NOT NULL,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_catalog CHECK ((external_project_id IS NULL) = (catalog_status = 'UNLINKED')),
    CONSTRAINT ck_project_prefix CHECK (permission_prefix ~ '^[A-Z][A-Z0-9_]*[A-Z0-9]$')
);

CREATE TRIGGER trg_project_keys_immutable
    BEFORE UPDATE OF maintask, external_project_id, permission_prefix ON project
    FOR EACH ROW EXECUTE FUNCTION forbid_column_change('maintask', 'external_project_id', 'permission_prefix');

-- Broker maintask'i büyük/küçük harf fark etmeden eşler; COLLATE "C" küçültmeyi dil ayarından bağımsız yapar
CREATE UNIQUE INDEX uq_project_maintask ON project (lower(maintask COLLATE "C"));

CREATE TABLE project_environment (
    project_id   BIGINT       NOT NULL REFERENCES project (id),
    environment  VARCHAR(16)  NOT NULL REFERENCES environment (code),
    base_url     VARCHAR(512) NOT NULL,                           -- forward hedefi, ortama özgü
    spec_path    VARCHAR(256) NOT NULL DEFAULT '/v3/api-docs',
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_id, environment)
);

-- Güncel hal: subtask başına tek satır
CREATE TABLE route_definition (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT       NOT NULL REFERENCES project (id),
    subtask              VARCHAR(150) NOT NULL,
    http_method          VARCHAR(8)   NOT NULL
                         CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    path_template        VARCHAR(512) NOT NULL,                   -- ör. /orders/{id}
    permissions          TEXT[]       NOT NULL DEFAULT '{}',      -- projenin kendi yetkileri; biri yeterli
    foreign_permissions  TEXT[]       NOT NULL DEFAULT '{}',      -- başka projelerden eklenen yetkiler
    anonymous            BOOLEAN      NOT NULL DEFAULT FALSE,
    response_profile_id  BIGINT       REFERENCES response_profile (id),  -- NULL: yanıt olduğu gibi döner
    definition           JSONB        NOT NULL,                   -- eşleme, header, response, timeout, log politikası
    spec_operation       JSONB,                                   -- tanım anındaki operasyon, $ref'ler çözülmüş
    spec_fingerprint     CHAR(64),                                -- spec_operation'ın SHA-256'sı
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version              INTEGER      NOT NULL DEFAULT 1,         -- optimistic lock; history ile aynı numara
    created_by           VARCHAR(100) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           VARCHAR(100) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_route_permission CHECK (anonymous OR cardinality(permissions) + cardinality(foreign_permissions) > 0)
);

CREATE TRIGGER trg_route_name_immutable
    BEFORE UPDATE OF project_id, subtask ON route_definition
    FOR EACH ROW EXECUTE FUNCTION forbid_column_change('project_id', 'subtask');

-- Projenin kendi yetkileri onun önekiyle başlar; başka projeden eklenen yetki,
-- kayıtlı başka bir projenin önekini taşır
CREATE FUNCTION check_route_permissions() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    own   TEXT;
    code  TEXT;
BEGIN
    SELECT permission_prefix || '_' INTO own FROM project WHERE id = NEW.project_id;
    FOREACH code IN ARRAY coalesce(NEW.permissions, '{}') LOOP
        IF NOT starts_with(code, own) OR length(code) = length(own) THEN
            RAISE EXCEPTION 'proje yetkisi projenin önekiyle başlamalı: % (önek %)', code, own;
        END IF;
    END LOOP;
    FOREACH code IN ARRAY coalesce(NEW.foreign_permissions, '{}') LOOP
        IF starts_with(code, own) OR NOT EXISTS (
               SELECT 1 FROM project p
                WHERE p.id <> NEW.project_id
                  AND starts_with(code, p.permission_prefix || '_')
                  AND length(code) > length(p.permission_prefix) + 1) THEN
            RAISE EXCEPTION 'başka projeden yetki kayıtlı başka bir projenin önekiyle başlamalı: %', code;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_route_permissions
    BEFORE INSERT OR UPDATE OF permissions, foreign_permissions ON route_definition
    FOR EACH ROW EXECUTE FUNCTION check_route_permissions();

-- Subtask adı proje içinde büyük/küçük harf fark etmeden tekildir
CREATE UNIQUE INDEX uq_route_definition ON route_definition (project_id, lower(subtask COLLATE "C"));

CREATE INDEX ix_route_definition_permissions ON route_definition USING GIN (permissions, foreign_permissions);
CREATE INDEX ix_route_definition_endpoint    ON route_definition (project_id, http_method, path_template);
CREATE INDEX ix_route_definition_profile     ON route_definition (response_profile_id);

-- Geçmiş: append-only, her değişiklikte tam snapshot
CREATE TABLE route_definition_history (
    definition_id  BIGINT       NOT NULL REFERENCES route_definition (id),
    version        INTEGER      NOT NULL,
    action         VARCHAR(16)  NOT NULL
                   CHECK (action IN ('CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE', 'ROLLBACK')),
    snapshot       JSONB        NOT NULL,                         -- route_definition satırının tam hali
    changed_by     VARCHAR(100) NOT NULL,
    changed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    comment        TEXT,
    PRIMARY KEY (definition_id, version)
);

CREATE TRIGGER trg_route_history_append_only
    BEFORE UPDATE OR DELETE ON route_definition_history
    FOR EACH ROW EXECUTE FUNCTION forbid_history_change();

-- Hangi ortamda hangi route ve profil versiyonu aktif
CREATE TABLE route_deployment (
    definition_id      BIGINT       NOT NULL,
    environment        VARCHAR(16)  NOT NULL REFERENCES environment (code),
    version            INTEGER      NOT NULL,
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    activated_by       VARCHAR(100) NOT NULL,
    activated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_probe_status  VARCHAR(24),
    last_probe_at      TIMESTAMPTZ,
    PRIMARY KEY (definition_id, environment),
    FOREIGN KEY (definition_id, version)
        REFERENCES route_definition_history (definition_id, version)
);

CREATE TABLE response_profile_deployment (
    profile_id    BIGINT       NOT NULL,
    environment   VARCHAR(16)  NOT NULL REFERENCES environment (code),
    version       INTEGER      NOT NULL,
    activated_by  VARCHAR(100) NOT NULL,
    activated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, environment),
    FOREIGN KEY (profile_id, version)
        REFERENCES response_profile_history (profile_id, version)
);

-- Ortamlar arası taşıma ve onay akışı; aynı zamanda deployment audit'i
-- Bir talep ya bir route versiyonunu ya da bir profil versiyonunu taşır
CREATE TABLE promotion_request (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    definition_id    BIGINT,                                       -- route talebi
    version          INTEGER,
    profile_id       BIGINT,                                       -- profil talebi
    profile_version  INTEGER,
    source_env       VARCHAR(16)  REFERENCES environment (code),   -- DEV'e ilk kurulumda NULL
    target_env       VARCHAR(16)  NOT NULL REFERENCES environment (code),
    action           VARCHAR(16)  NOT NULL DEFAULT 'DEPLOY'
                     CHECK (action IN ('DEPLOY', 'DISABLE', 'ENABLE', 'ROLLBACK')),
    status           VARCHAR(20)  NOT NULL
                     CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'APPLYING',
                                       'APPLIED', 'FAILED', 'CANCELLED')),
    requested_by     VARCHAR(100) NOT NULL,
    requested_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason           TEXT,
    decided_by       VARCHAR(100),
    decided_at       TIMESTAMPTZ,
    decision_note    TEXT,
    probe_status     VARCHAR(24),                                  -- profil taleplerinde boş
    probe_detail     JSONB,
    config_version   BIGINT,                                       -- ortamda oluşan runtime versiyonu
    applied_at       TIMESTAMPTZ,
    failure_reason   TEXT,
    FOREIGN KEY (definition_id, version)
        REFERENCES route_definition_history (definition_id, version),
    FOREIGN KEY (profile_id, profile_version)
        REFERENCES response_profile_history (profile_id, version),
    CONSTRAINT ck_promotion_subject CHECK (
           (definition_id IS NOT NULL AND version IS NOT NULL
            AND profile_id IS NULL AND profile_version IS NULL)
        OR (definition_id IS NULL AND version IS NULL
            AND profile_id IS NOT NULL AND profile_version IS NOT NULL)),
    CONSTRAINT ck_profile_action CHECK (profile_id IS NULL OR action IN ('DEPLOY', 'ROLLBACK')),
    CONSTRAINT ck_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

-- Aynı route ya da profil ve hedef ortam için aynı anda tek açık talep
CREATE UNIQUE INDEX uq_promotion_open
    ON promotion_request (definition_id, target_env)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING');

CREATE UNIQUE INDEX uq_promotion_open_profile
    ON promotion_request (profile_id, target_env)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING');

CREATE INDEX ix_promotion_status ON promotion_request (status, requested_at);

-- Uygulama kullanıcısı: history'lerde yalnızca okuma ve ekleme, hiçbir tabloda silme yok.
-- Rolü DBA oluşturur; rol yoksa (yerel geliştirme, testler) GRANT atlanır.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'broker_admin_app') THEN
        GRANT USAGE ON SCHEMA broker_admin TO broker_admin_app;
        GRANT SELECT ON environment TO broker_admin_app;
        GRANT SELECT, INSERT, UPDATE ON response_profile, project, project_environment, route_definition,
                                        route_deployment, response_profile_deployment, promotion_request
              TO broker_admin_app;
        GRANT SELECT, INSERT ON route_definition_history, response_profile_history TO broker_admin_app;
    END IF;
END
$$;
