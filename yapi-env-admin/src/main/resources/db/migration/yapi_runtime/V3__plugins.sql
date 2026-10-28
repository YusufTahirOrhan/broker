-- Eklentiler: subtask'lere istek ve yanıt anında alan ekleyen, merkezde versiyonlanan tanımlar (yanıt profilleri gibi)
CREATE TABLE runtime_plugin (
    name          VARCHAR2(100 CHAR) NOT NULL,
    plugin_id     NUMBER(19)         NOT NULL,                -- merkezi eklentinin ID'si
    version       NUMBER(10)         NOT NULL,                -- merkezi versiyon; loglara yazılır
    definition    CLOB               NOT NULL,                -- kapsam, projeler, istek ve yanıt işlemleri
    applied_by    VARCHAR2(100 CHAR) NOT NULL,
    promotion_id  NUMBER(19)         NOT NULL,                -- merkezi promotion_request.id
    applied_at    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_runtime_plugin PRIMARY KEY (name),
    CONSTRAINT ck_runtime_plugin_json CHECK (definition IS JSON)
);

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
    grant_if_present('GRANT SELECT ON runtime_plugin TO yapi_runtime_reader');
    grant_if_present('GRANT SELECT, INSERT, UPDATE ON runtime_plugin TO yapi_runtime_writer');
END;
/
