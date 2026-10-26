-- Ortamlar ve Admin API adresleri. Adresler Flyway placeholder'larıyla gelir
-- (spring.flyway.placeholders.*); sonradan değişiklik DBA'nın işidir.
MERGE INTO environment e
USING (SELECT 'DEV' AS code, 'Geliştirme' AS name, 1 AS sort_order, '${dev_admin_api_url}' AS admin_api_url,
              0 AS requires_approval, 1 AS test_calls_allowed FROM dual
       UNION ALL
       SELECT 'TEST', 'Test (PREPROD)', 2, '${test_admin_api_url}', 0, 1 FROM dual
       UNION ALL
       SELECT 'PROD', 'Üretim', 3, '${prod_admin_api_url}', 1, 0 FROM dual) s
   ON (e.code = s.code)
 WHEN NOT MATCHED THEN
      INSERT (code, name, sort_order, admin_api_url, requires_approval, test_calls_allowed)
      VALUES (s.code, s.name, s.sort_order, s.admin_api_url, s.requires_approval, s.test_calls_allowed);
