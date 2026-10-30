-- Eklentiler: subtask'lere istek ve yanıt anında alan ekleyen, versiyonlanan ve ortamlara taşınan tanımlar
-- (yanıt profilleriyle aynı desen). Atama (kapsam ve projeler) eklentinin kendi tanımındadır.
CREATE TABLE plugin (
    id           NUMBER(19) GENERATED ALWAYS AS IDENTITY NOT NULL,
    name         VARCHAR2(100 CHAR) NOT NULL,                 -- subtask tanımındaki plugins/disabledPlugins'te referans
    description  CLOB,
    definition   CLOB               NOT NULL,                 -- scope, projects, request, response
    version      NUMBER(10)         DEFAULT 1 NOT NULL,       -- optimistic lock; history ile aynı numara
    created_by   VARCHAR2(100 CHAR) NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by   VARCHAR2(100 CHAR) NOT NULL,
    updated_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_plugin PRIMARY KEY (id),
    CONSTRAINT uq_plugin_name UNIQUE (name),
    CONSTRAINT ck_plugin_json CHECK (definition IS JSON)
);

CREATE OR REPLACE TRIGGER trg_plugin_name_immutable
    BEFORE UPDATE OF name ON plugin
    FOR EACH ROW
    WHEN (OLD.name IS NOT NULL AND (NEW.name IS NULL OR NEW.name <> OLD.name))
BEGIN
    RAISE_APPLICATION_ERROR(-20002, 'plugin.name değiştirilemez');
END;
/

CREATE TABLE plugin_history (
    plugin_id    NUMBER(19)         NOT NULL,
    version      NUMBER(10)         NOT NULL,
    action       VARCHAR2(16 CHAR)  NOT NULL,
    snapshot     CLOB               NOT NULL,                 -- plugin satırının tam hali
    changed_by   VARCHAR2(100 CHAR) NOT NULL,
    changed_at   TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    change_note  CLOB,
    CONSTRAINT pk_plugin_history PRIMARY KEY (plugin_id, version),
    CONSTRAINT fk_plugin_history_plugin FOREIGN KEY (plugin_id) REFERENCES plugin (id),
    CONSTRAINT ck_plugin_history_action CHECK (action IN ('CREATE', 'UPDATE', 'ROLLBACK')),
    CONSTRAINT ck_plugin_history_json CHECK (snapshot IS JSON)
);

CREATE OR REPLACE TRIGGER trg_plugin_history_append_only
    BEFORE UPDATE OR DELETE ON plugin_history
    FOR EACH ROW
BEGIN
    RAISE_APPLICATION_ERROR(-20001, 'plugin_history append-only: '
        || CASE WHEN UPDATING THEN 'UPDATE' ELSE 'DELETE' END || ' izin verilmez');
END;
/

CREATE TABLE plugin_deployment (
    plugin_id     NUMBER(19)         NOT NULL,
    environment   VARCHAR2(16 CHAR)  NOT NULL,
    version       NUMBER(10)         NOT NULL,
    activated_by  VARCHAR2(100 CHAR) NOT NULL,
    activated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_plugin_deployment PRIMARY KEY (plugin_id, environment),
    CONSTRAINT fk_plugin_deployment_env FOREIGN KEY (environment) REFERENCES environment (code),
    CONSTRAINT fk_plugin_deployment_version FOREIGN KEY (plugin_id, version)
        REFERENCES plugin_history (plugin_id, version)
);

-- Taşıma talebinin üçüncü türü: bir eklenti versiyonu. Talep tam olarak bir türü taşır.
ALTER TABLE promotion_request ADD (
    plugin_id       NUMBER(19),
    plugin_version  NUMBER(10)
);
ALTER TABLE promotion_request DROP CONSTRAINT ck_promotion_subject;
ALTER TABLE promotion_request DROP CONSTRAINT ck_profile_action;
ALTER TABLE promotion_request ADD (
    CONSTRAINT fk_promotion_plugin FOREIGN KEY (plugin_id, plugin_version)
        REFERENCES plugin_history (plugin_id, version),
    CONSTRAINT ck_promotion_subject CHECK (
           (definition_id IS NOT NULL AND version IS NOT NULL
            AND profile_id IS NULL AND profile_version IS NULL AND plugin_id IS NULL AND plugin_version IS NULL)
        OR (definition_id IS NULL AND version IS NULL
            AND profile_id IS NOT NULL AND profile_version IS NOT NULL AND plugin_id IS NULL AND plugin_version IS NULL)
        OR (definition_id IS NULL AND version IS NULL
            AND profile_id IS NULL AND profile_version IS NULL AND plugin_id IS NOT NULL AND plugin_version IS NOT NULL)),
    CONSTRAINT ck_profile_action CHECK (profile_id IS NULL OR action IN ('DEPLOY', 'ROLLBACK')),
    CONSTRAINT ck_plugin_action CHECK (plugin_id IS NULL OR action IN ('DEPLOY', 'ROLLBACK'))
);

CREATE UNIQUE INDEX uq_promotion_open_plugin ON promotion_request (
    CASE WHEN plugin_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
         THEN plugin_id END,
    CASE WHEN plugin_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
         THEN target_env END);

CREATE INDEX ix_promotion_plugin ON promotion_request (plugin_id, plugin_version);

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
    grant_if_present('GRANT SELECT, INSERT, UPDATE ON plugin TO yapi_admin_app');
    grant_if_present('GRANT SELECT, INSERT, UPDATE ON plugin_deployment TO yapi_admin_app');
    grant_if_present('GRANT SELECT, INSERT ON plugin_history TO yapi_admin_app');
END;
/
