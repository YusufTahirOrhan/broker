-- Ortamlar ve Admin API adresleri. Adresler Flyway placeholder'larıyla gelir
-- (spring.flyway.placeholders.*); sonradan değişiklik DBA'nın işidir.
INSERT INTO environment (code, name, sort_order, admin_api_url, requires_approval, test_calls_allowed) VALUES
    ('DEV',  'Geliştirme',     1, '${dev_admin_api_url}',  FALSE, TRUE),
    ('TEST', 'Test (PREPROD)', 2, '${test_admin_api_url}', FALSE, TRUE),
    ('PROD', 'Üretim',         3, '${prod_admin_api_url}', TRUE,  FALSE)
ON CONFLICT (code) DO NOTHING;
