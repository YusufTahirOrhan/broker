-- Ortam runtime veritabanı (Oracle 19c): her ortamda ayrı (tasarım dokümanı, "Ortam runtime DB'si ve cache senkronizasyonu").
-- Broker'lar okur (broker_runtime_reader), ortam Admin API yazar (broker_runtime_writer).
-- Şema, şema sahibi Oracle kullanıcısıdır ve DBA açar; Flyway şema sahibiyle çalışır. Uygulamalar kendi
-- kullanıcılarıyla bağlanır ve tablolara CURRENT_SCHEMA ile şemasız erişir (spring.datasource.hikari.schema).

CREATE TABLE runtime_project (
    maintask    VARCHAR2(100 CHAR) NOT NULL,
    base_url    VARCHAR2(512 CHAR) NOT NULL,
    enabled     NUMBER(1)          DEFAULT 1 NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_runtime_project PRIMARY KEY (maintask),
    CONSTRAINT ck_runtime_project_enabled CHECK (enabled IN (0, 1))
);

CREATE TABLE runtime_response_profile (
    name          VARCHAR2(100 CHAR) NOT NULL,
    profile_id    NUMBER(19)         NOT NULL,                -- merkezi profilin ID'si
    version       NUMBER(10)         NOT NULL,                -- merkezi versiyon; loglara yazılır
    definition    CLOB               NOT NULL,
    applied_by    VARCHAR2(100 CHAR) NOT NULL,
    promotion_id  NUMBER(19)         NOT NULL,                -- merkezi promotion_request.id
    applied_at    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_runtime_response_profile PRIMARY KEY (name),
    CONSTRAINT ck_runtime_profile_json CHECK (definition IS JSON)
);

CREATE TABLE runtime_route (
    maintask          VARCHAR2(100 CHAR)  NOT NULL,
    subtask           VARCHAR2(150 CHAR)  NOT NULL,
    definition_id     NUMBER(19)          NOT NULL,           -- merkezi tanımın ID'si
    version           NUMBER(10)          NOT NULL,           -- merkezi versiyon; loglara route_version olarak yazılır
    http_method       VARCHAR2(8 CHAR)    NOT NULL,
    path_template     VARCHAR2(512 CHAR)  NOT NULL,
    permissions       VARCHAR2(4000 CHAR) DEFAULT '[]' NOT NULL,  -- JSON dizisi: proje ve başka proje yetkileri; biri yeterli
    anonymous         NUMBER(1)           DEFAULT 0 NOT NULL,
    response_profile  VARCHAR2(100 CHAR),                     -- ortamda olmayan profile referans verilemez
    definition        CLOB                NOT NULL,
    status            VARCHAR2(16 CHAR)   NOT NULL,
    applied_by        VARCHAR2(100 CHAR)  NOT NULL,
    promotion_id      NUMBER(19)          NOT NULL,           -- merkezi promotion_request.id
    applied_at        TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_runtime_route PRIMARY KEY (maintask, subtask),
    CONSTRAINT fk_runtime_route_project FOREIGN KEY (maintask) REFERENCES runtime_project (maintask),
    CONSTRAINT fk_runtime_route_profile FOREIGN KEY (response_profile) REFERENCES runtime_response_profile (name),
    CONSTRAINT ck_runtime_route_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_runtime_route_anonymous CHECK (anonymous IN (0, 1)),
    CONSTRAINT ck_runtime_route_permissions_json CHECK (permissions IS JSON),
    CONSTRAINT ck_runtime_route_definition_json CHECK (definition IS JSON),
    CONSTRAINT ck_runtime_permission CHECK (anonymous = 1 OR JSON_EXISTS(permissions, '$[0]'))
);

CREATE INDEX ix_runtime_route_profile ON runtime_route (response_profile);

-- Cache senkronizasyonu için tek satırlık global sayaç
CREATE TABLE runtime_config_version (
    id          NUMBER(1)  NOT NULL,
    version     NUMBER(19) NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_runtime_config_version PRIMARY KEY (id),
    CONSTRAINT ck_runtime_config_version_id CHECK (id = 1)
);

INSERT INTO runtime_config_version (id, version) VALUES (1, 0);

-- Kullanıcıları DBA oluşturur; olmayan kullanıcıya GRANT atlanır (ORA-01917; yerel geliştirme ve testler)
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
    FOR t IN (SELECT column_value AS name
                FROM TABLE(sys.odcivarchar2list('RUNTIME_PROJECT', 'RUNTIME_RESPONSE_PROFILE', 'RUNTIME_ROUTE',
                                                'RUNTIME_CONFIG_VERSION'))) LOOP
        grant_if_present('GRANT SELECT ON ' || t.name || ' TO broker_runtime_reader');
        grant_if_present('GRANT SELECT, INSERT, UPDATE ON ' || t.name || ' TO broker_runtime_writer');
    END LOOP;
END;
/
