-- Merkezi DB (Oracle 19c): tasarım dokümanındaki veri modeli ("Merkezi veri modeli").
-- Şema, şema sahibi Oracle kullanıcısıdır (YAPI_ADMIN) ve DBA açar; Flyway şema sahibiyle çalışır.
-- Uygulama yapi_admin_app kullanıcısıyla bağlanır, tablolara CURRENT_SCHEMA ile şemasız erişir
-- (spring.datasource.hikari.schema). Trigger'lar şema sahibinin haklarıyla çalışır.
-- PostgreSQL'den farklar: JSON metni CLOB + IS JSON, boolean NUMBER(1), yetki dizileri route_permission tablosunda,
-- `comment` (ayrılmış kelime) kolonları change_note.

CREATE TABLE environment (
    code                VARCHAR2(16 CHAR)  NOT NULL,              -- DEV, PREPROD, PROD
    name                VARCHAR2(64 CHAR)  NOT NULL,
    sort_order          NUMBER(5)          NOT NULL,              -- promote sırası: 1 → 2 → 3
    admin_api_url       VARCHAR2(512 CHAR) NOT NULL,              -- ortamdaki yönetim API'si
    requires_approval   NUMBER(1)          DEFAULT 0 NOT NULL,    -- PROD için 1
    test_calls_allowed  NUMBER(1)          DEFAULT 0 NOT NULL,    -- "test et" yalnızca DEV/PREPROD
    CONSTRAINT pk_environment PRIMARY KEY (code),
    CONSTRAINT uq_environment_order UNIQUE (sort_order),
    CONSTRAINT ck_environment_approval CHECK (requires_approval IN (0, 1)),
    CONSTRAINT ck_environment_test_calls CHECK (test_calls_allowed IN (0, 1))
);

-- Yanıt profilleri: projeye özel response zarfları
CREATE TABLE response_profile (
    id           NUMBER(19) GENERATED ALWAYS AS IDENTITY NOT NULL,
    name         VARCHAR2(100 CHAR) NOT NULL,                 -- ör. order-service; runtime'da referans
    description  CLOB,
    definition   CLOB               NOT NULL,                 -- success ve error şablonları
    version      NUMBER(10)         DEFAULT 1 NOT NULL,       -- optimistic lock; history ile aynı numara
    created_by   VARCHAR2(100 CHAR) NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by   VARCHAR2(100 CHAR) NOT NULL,
    updated_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_response_profile PRIMARY KEY (id),
    CONSTRAINT uq_response_profile_name UNIQUE (name),
    CONSTRAINT ck_response_profile_json CHECK (definition IS JSON)
);

-- Frontend sözleşmesinin parçası olan anahtarlar bir kez yazıldıktan sonra değiştirilemez;
-- boş bir değeri doldurmak serbesttir
CREATE OR REPLACE TRIGGER trg_profile_name_immutable
    BEFORE UPDATE OF name ON response_profile
    FOR EACH ROW
    WHEN (OLD.name IS NOT NULL AND (NEW.name IS NULL OR NEW.name <> OLD.name))
BEGIN
    RAISE_APPLICATION_ERROR(-20002, 'response_profile.name değiştirilemez');
END;
/

CREATE TABLE response_profile_history (
    profile_id   NUMBER(19)         NOT NULL,
    version      NUMBER(10)         NOT NULL,
    action       VARCHAR2(16 CHAR)  NOT NULL,
    snapshot     CLOB               NOT NULL,                 -- response_profile satırının tam hali
    changed_by   VARCHAR2(100 CHAR) NOT NULL,
    changed_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    change_note  CLOB,                                        -- API'de "comment"
    CONSTRAINT pk_response_profile_history PRIMARY KEY (profile_id, version),
    CONSTRAINT fk_profile_history_profile FOREIGN KEY (profile_id) REFERENCES response_profile (id),
    CONSTRAINT ck_profile_history_action CHECK (action IN ('CREATE', 'UPDATE', 'ROLLBACK')),
    CONSTRAINT ck_profile_history_json CHECK (snapshot IS JSON)
);

-- History tabloları yalnızca ekleme kabul eder
CREATE OR REPLACE TRIGGER trg_profile_history_append_only
    BEFORE UPDATE OR DELETE ON response_profile_history
    FOR EACH ROW
BEGIN
    RAISE_APPLICATION_ERROR(-20001, 'response_profile_history append-only: '
        || CASE WHEN UPDATING THEN 'UPDATE' ELSE 'DELETE' END || ' izin verilmez');
END;
/

CREATE TABLE project (
    id                           NUMBER(19) GENERATED ALWAYS AS IDENTITY NOT NULL,
    external_project_id          VARCHAR2(64 CHAR),            -- proje servisindeki ID; geçişte boş kalabilir
    maintask                     VARCHAR2(100 CHAR) NOT NULL,  -- frontend'in gönderdiği ad; tekilliği harf duyarsız
    name                         VARCHAR2(200 CHAR) NOT NULL,  -- proje servisinden gelen görünen ad
    permission_prefix            VARCHAR2(50 CHAR)  NOT NULL,  -- yetki adlarının öneki, ör. LOGPROJECT
    catalog_status               VARCHAR2(16 CHAR)  DEFAULT 'LINKED' NOT NULL,
    catalog_synced_at            TIMESTAMP(6) WITH TIME ZONE,
    owner_team                   VARCHAR2(100 CHAR),
    description                  CLOB,
    default_response_profile_id  NUMBER(19),                   -- yeni subtask ekranında ön seçim
    created_by                   VARCHAR2(100 CHAR) NOT NULL,
    created_at                   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_project PRIMARY KEY (id),
    CONSTRAINT uq_project_external_id UNIQUE (external_project_id),
    CONSTRAINT uq_project_prefix UNIQUE (permission_prefix),
    CONSTRAINT fk_project_default_profile FOREIGN KEY (default_response_profile_id) REFERENCES response_profile (id),
    CONSTRAINT ck_project_catalog_status CHECK (catalog_status IN ('LINKED', 'MISSING', 'UNLINKED')),
    CONSTRAINT ck_project_catalog CHECK (
           (external_project_id IS NULL AND catalog_status = 'UNLINKED')
        OR (external_project_id IS NOT NULL AND catalog_status <> 'UNLINKED')),
    -- harf aralığı yerine harfler tek tek: aralıklar dil sıralamasına (NLS_SORT) göre başka harfleri de kapsayabilir
    CONSTRAINT ck_project_prefix CHECK (REGEXP_LIKE(permission_prefix,
        '^[ABCDEFGHIJKLMNOPQRSTUVWXYZ][ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_]*[ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789]$', 'c'))
);

CREATE OR REPLACE TRIGGER trg_project_keys_immutable
    BEFORE UPDATE OF maintask, external_project_id, permission_prefix ON project
    FOR EACH ROW
BEGIN
    IF :OLD.maintask IS NOT NULL AND (:NEW.maintask IS NULL OR :NEW.maintask <> :OLD.maintask) THEN
        RAISE_APPLICATION_ERROR(-20002, 'project.maintask değiştirilemez');
    END IF;
    IF :OLD.external_project_id IS NOT NULL
       AND (:NEW.external_project_id IS NULL OR :NEW.external_project_id <> :OLD.external_project_id) THEN
        RAISE_APPLICATION_ERROR(-20002, 'project.external_project_id değiştirilemez');
    END IF;
    IF :OLD.permission_prefix IS NOT NULL
       AND (:NEW.permission_prefix IS NULL OR :NEW.permission_prefix <> :OLD.permission_prefix) THEN
        RAISE_APPLICATION_ERROR(-20002, 'project.permission_prefix değiştirilemez');
    END IF;
END;
/

-- Broker maintask'i büyük/küçük harf fark etmeden eşler; LOWER dil ayarından bağımsızdır (NLS_LOWER değil)
CREATE UNIQUE INDEX uq_project_maintask ON project (LOWER(maintask));

CREATE TABLE project_environment (
    project_id   NUMBER(19)         NOT NULL,
    environment  VARCHAR2(16 CHAR)  NOT NULL,
    base_url     VARCHAR2(512 CHAR) NOT NULL,                  -- forward hedefi, ortama özgü
    spec_path    VARCHAR2(256 CHAR) DEFAULT '/v3/api-docs' NOT NULL,
    enabled      NUMBER(1)          DEFAULT 1 NOT NULL,
    CONSTRAINT pk_project_environment PRIMARY KEY (project_id, environment),
    CONSTRAINT fk_project_environment_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_project_environment_env FOREIGN KEY (environment) REFERENCES environment (code),
    CONSTRAINT ck_project_environment_enabled CHECK (enabled IN (0, 1))
);

-- Güncel hal: subtask başına tek satır. Yetkiler route_permission'da.
CREATE TABLE route_definition (
    id                   NUMBER(19) GENERATED ALWAYS AS IDENTITY NOT NULL,
    project_id           NUMBER(19)         NOT NULL,
    subtask              VARCHAR2(150 CHAR) NOT NULL,
    http_method          VARCHAR2(8 CHAR)   NOT NULL,
    path_template        VARCHAR2(512 CHAR) NOT NULL,          -- ör. /orders/{id}
    anonymous            NUMBER(1)          DEFAULT 0 NOT NULL,
    response_profile_id  NUMBER(19),                           -- NULL: yanıt olduğu gibi döner
    definition           CLOB               NOT NULL,          -- eşleme, header, response, timeout, log politikası
    spec_operation       CLOB,                                 -- tanım anındaki operasyon, $ref'ler çözülmüş
    spec_fingerprint     VARCHAR2(64 CHAR),                    -- spec_operation'ın SHA-256'sı
    status               VARCHAR2(16 CHAR)  DEFAULT 'ACTIVE' NOT NULL,
    version              NUMBER(10)         DEFAULT 1 NOT NULL, -- optimistic lock; history ile aynı numara
    created_by           VARCHAR2(100 CHAR) NOT NULL,
    created_at           TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by           VARCHAR2(100 CHAR) NOT NULL,
    updated_at           TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_route_definition PRIMARY KEY (id),
    CONSTRAINT fk_route_definition_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_route_definition_profile FOREIGN KEY (response_profile_id) REFERENCES response_profile (id),
    CONSTRAINT ck_route_method CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT ck_route_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_route_anonymous CHECK (anonymous IN (0, 1)),
    CONSTRAINT ck_route_definition_json CHECK (definition IS JSON),
    CONSTRAINT ck_route_spec_json CHECK (spec_operation IS JSON)
);

CREATE OR REPLACE TRIGGER trg_route_name_immutable
    BEFORE UPDATE OF project_id, subtask ON route_definition
    FOR EACH ROW
BEGIN
    IF :NEW.project_id <> :OLD.project_id THEN
        RAISE_APPLICATION_ERROR(-20002, 'route_definition.project_id değiştirilemez');
    END IF;
    IF :NEW.subtask <> :OLD.subtask THEN
        RAISE_APPLICATION_ERROR(-20002, 'route_definition.subtask değiştirilemez');
    END IF;
END;
/

-- Subtask adı proje içinde büyük/küçük harf fark etmeden tekildir
CREATE UNIQUE INDEX uq_route_definition ON route_definition (project_id, LOWER(subtask));

CREATE INDEX ix_route_definition_endpoint ON route_definition (project_id, http_method, path_template);
CREATE INDEX ix_route_definition_profile  ON route_definition (response_profile_id);

-- Route yetkileri: OWN projenin kendi yetkileri, FOREIGN başka projelerden eklenenler; biri yeterli.
-- Sıra tanımdaki sıradır. Kayıtta yetki listesi bütünüyle yeniden yazılır (silinip eklenir).
-- "Anonim olmayan route en az bir yetki taşır" kuralı tablo dışına çıktığı için uygulamada (RouteService).
CREATE TABLE route_permission (
    definition_id  NUMBER(19)         NOT NULL,
    kind           VARCHAR2(8 CHAR)   NOT NULL,
    sort_order     NUMBER(5)          NOT NULL,
    code           VARCHAR2(100 CHAR) NOT NULL,
    CONSTRAINT pk_route_permission PRIMARY KEY (definition_id, kind, sort_order),
    CONSTRAINT uq_route_permission_code UNIQUE (definition_id, code),
    CONSTRAINT fk_route_permission_route FOREIGN KEY (definition_id) REFERENCES route_definition (id),
    CONSTRAINT ck_route_permission_kind CHECK (kind IN ('OWN', 'FOREIGN'))
);

-- GET /permissions/{code}/routes ve yetki kullanım sayıları
CREATE INDEX ix_route_permission_code ON route_permission (code);

-- Projenin kendi yetkileri onun önekiyle başlar; başka projeden eklenen yetki,
-- kayıtlı başka bir projenin önekini taşır
CREATE OR REPLACE TRIGGER trg_route_permissions
    BEFORE INSERT OR UPDATE ON route_permission
    FOR EACH ROW
DECLARE
    own_prefix   VARCHAR2(60 CHAR);
    owner_id     NUMBER(19);
    owner_count  NUMBER;
BEGIN
    SELECT p.permission_prefix || '_', p.id INTO own_prefix, owner_id
      FROM route_definition r JOIN project p ON p.id = r.project_id
     WHERE r.id = :NEW.definition_id;
    IF :NEW.kind = 'OWN' THEN
        IF SUBSTR(:NEW.code, 1, LENGTH(own_prefix)) <> own_prefix OR LENGTH(:NEW.code) = LENGTH(own_prefix) THEN
            RAISE_APPLICATION_ERROR(-20003,
                'proje yetkisi projenin önekiyle başlamalı: ' || :NEW.code || ' (önek ' || own_prefix || ')');
        END IF;
    ELSE
        SELECT COUNT(*) INTO owner_count
          FROM project p
         WHERE p.id <> owner_id
           AND SUBSTR(:NEW.code, 1, LENGTH(p.permission_prefix) + 1) = p.permission_prefix || '_'
           AND LENGTH(:NEW.code) > LENGTH(p.permission_prefix) + 1;
        IF SUBSTR(:NEW.code, 1, LENGTH(own_prefix)) = own_prefix OR owner_count = 0 THEN
            RAISE_APPLICATION_ERROR(-20003,
                'başka projeden yetki kayıtlı başka bir projenin önekiyle başlamalı: ' || :NEW.code);
        END IF;
    END IF;
END;
/

-- Geçmiş: append-only, her değişiklikte tam snapshot (uygulama route_definition satırından ve yetkilerinden kurar)
CREATE TABLE route_definition_history (
    definition_id  NUMBER(19)         NOT NULL,
    version        NUMBER(10)         NOT NULL,
    action         VARCHAR2(16 CHAR)  NOT NULL,
    snapshot       CLOB               NOT NULL,                -- route_definition satırının tam hali
    changed_by     VARCHAR2(100 CHAR) NOT NULL,
    changed_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    change_note    CLOB,                                       -- API'de "comment"
    CONSTRAINT pk_route_definition_history PRIMARY KEY (definition_id, version),
    CONSTRAINT fk_route_history_route FOREIGN KEY (definition_id) REFERENCES route_definition (id),
    CONSTRAINT ck_route_history_action CHECK (action IN ('CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE', 'ROLLBACK')),
    CONSTRAINT ck_route_history_json CHECK (snapshot IS JSON)
);

CREATE OR REPLACE TRIGGER trg_route_history_append_only
    BEFORE UPDATE OR DELETE ON route_definition_history
    FOR EACH ROW
BEGIN
    RAISE_APPLICATION_ERROR(-20001, 'route_definition_history append-only: '
        || CASE WHEN UPDATING THEN 'UPDATE' ELSE 'DELETE' END || ' izin verilmez');
END;
/

-- Hangi ortamda hangi route ve profil versiyonu aktif
CREATE TABLE route_deployment (
    definition_id      NUMBER(19)         NOT NULL,
    environment        VARCHAR2(16 CHAR)  NOT NULL,
    version            NUMBER(10)         NOT NULL,
    status             VARCHAR2(16 CHAR)  NOT NULL,
    activated_by       VARCHAR2(100 CHAR) NOT NULL,
    activated_at       TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    last_probe_status  VARCHAR2(24 CHAR),
    last_probe_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_route_deployment PRIMARY KEY (definition_id, environment),
    CONSTRAINT fk_route_deployment_env FOREIGN KEY (environment) REFERENCES environment (code),
    CONSTRAINT fk_route_deployment_version FOREIGN KEY (definition_id, version)
        REFERENCES route_definition_history (definition_id, version),
    CONSTRAINT ck_route_deployment_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE response_profile_deployment (
    profile_id    NUMBER(19)         NOT NULL,
    environment   VARCHAR2(16 CHAR)  NOT NULL,
    version       NUMBER(10)         NOT NULL,
    activated_by  VARCHAR2(100 CHAR) NOT NULL,
    activated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_response_profile_deployment PRIMARY KEY (profile_id, environment),
    CONSTRAINT fk_profile_deployment_env FOREIGN KEY (environment) REFERENCES environment (code),
    CONSTRAINT fk_profile_deployment_version FOREIGN KEY (profile_id, version)
        REFERENCES response_profile_history (profile_id, version)
);

-- Ortamlar arası taşıma ve onay akışı; aynı zamanda deployment audit'i
-- Bir talep ya bir route versiyonunu ya da bir profil versiyonunu taşır
CREATE TABLE promotion_request (
    id               NUMBER(19) GENERATED ALWAYS AS IDENTITY NOT NULL,
    definition_id    NUMBER(19),                                -- route talebi
    version          NUMBER(10),
    profile_id       NUMBER(19),                                -- profil talebi
    profile_version  NUMBER(10),
    source_env       VARCHAR2(16 CHAR),                         -- DEV'e ilk kurulumda NULL
    target_env       VARCHAR2(16 CHAR)  NOT NULL,
    action           VARCHAR2(16 CHAR)  DEFAULT 'DEPLOY' NOT NULL,
    status           VARCHAR2(20 CHAR)  NOT NULL,
    requested_by     VARCHAR2(100 CHAR) NOT NULL,
    requested_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    reason           CLOB,
    decided_by       VARCHAR2(100 CHAR),
    decided_at       TIMESTAMP(6) WITH TIME ZONE,
    decision_note    CLOB,
    probe_status     VARCHAR2(24 CHAR),                         -- profil taleplerinde boş
    probe_detail     CLOB,
    config_version   NUMBER(19),                                -- ortamda oluşan runtime versiyonu
    applied_at       TIMESTAMP(6) WITH TIME ZONE,
    failure_reason   CLOB,
    CONSTRAINT pk_promotion_request PRIMARY KEY (id),
    CONSTRAINT fk_promotion_source FOREIGN KEY (source_env) REFERENCES environment (code),
    CONSTRAINT fk_promotion_target FOREIGN KEY (target_env) REFERENCES environment (code),
    CONSTRAINT fk_promotion_route FOREIGN KEY (definition_id, version)
        REFERENCES route_definition_history (definition_id, version),
    CONSTRAINT fk_promotion_profile FOREIGN KEY (profile_id, profile_version)
        REFERENCES response_profile_history (profile_id, version),
    CONSTRAINT ck_promotion_action CHECK (action IN ('DEPLOY', 'DISABLE', 'ENABLE', 'ROLLBACK')),
    CONSTRAINT ck_promotion_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'APPLYING',
                                                     'APPLIED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_promotion_probe_json CHECK (probe_detail IS JSON),
    CONSTRAINT ck_promotion_subject CHECK (
           (definition_id IS NOT NULL AND version IS NOT NULL
            AND profile_id IS NULL AND profile_version IS NULL)
        OR (definition_id IS NULL AND version IS NULL
            AND profile_id IS NOT NULL AND profile_version IS NOT NULL)),
    CONSTRAINT ck_profile_action CHECK (profile_id IS NULL OR action IN ('DEPLOY', 'ROLLBACK')),
    CONSTRAINT ck_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

-- Aynı route ya da profil ve hedef ortam için aynı anda tek açık talep. Kısmi index yerine fonksiyon tabanlı:
-- kapalı taleplerde ve öbür türün taleplerinde bütün anahtarlar NULL olur, NULL'lardan oluşan anahtar indekse girmez.
CREATE UNIQUE INDEX uq_promotion_open ON promotion_request (
    CASE WHEN definition_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
         THEN definition_id END,
    CASE WHEN definition_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
         THEN target_env END);

CREATE UNIQUE INDEX uq_promotion_open_profile ON promotion_request (
    CASE WHEN profile_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
         THEN profile_id END,
    CASE WHEN profile_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
         THEN target_env END);

CREATE INDEX ix_promotion_status ON promotion_request (status, requested_at);
CREATE INDEX ix_promotion_route ON promotion_request (definition_id, version);
CREATE INDEX ix_promotion_profile ON promotion_request (profile_id, profile_version);

-- Uygulama kullanıcısı: history'lerde yalnızca okuma ve ekleme; silme yalnızca route_permission'da (liste yeniden
-- yazılır). Kullanıcıyı DBA oluşturur; yoksa (yerel geliştirme, testler) GRANT atlanır (ORA-01917).
DECLARE
    PROCEDURE grant_if_present(ddl VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE ddl;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -1917 THEN
                RAISE;
            END IF;
    END;
BEGIN
    grant_if_present('GRANT SELECT ON environment TO yapi_admin_app');
    FOR t IN (SELECT column_value AS name
                FROM TABLE(sys.odcivarchar2list('RESPONSE_PROFILE', 'PROJECT', 'PROJECT_ENVIRONMENT',
                                                'ROUTE_DEFINITION', 'ROUTE_DEPLOYMENT',
                                                'RESPONSE_PROFILE_DEPLOYMENT', 'PROMOTION_REQUEST'))) LOOP
        grant_if_present('GRANT SELECT, INSERT, UPDATE ON ' || t.name || ' TO yapi_admin_app');
    END LOOP;
    grant_if_present('GRANT SELECT, INSERT, DELETE ON route_permission TO yapi_admin_app');
    grant_if_present('GRANT SELECT, INSERT ON route_definition_history TO yapi_admin_app');
    grant_if_present('GRANT SELECT, INSERT ON response_profile_history TO yapi_admin_app');
END;
/
