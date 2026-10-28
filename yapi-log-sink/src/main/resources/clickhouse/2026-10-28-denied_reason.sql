-- yapi_logs.sql'i bu kolondan önce kurmuş tablolar için; sink yeni kolonu yazmadan önce bir kez çalıştırılır.
-- Kolon yoksa ClickHouse satırları reddeder ve olaylar DLQ'ya gider.
ALTER TABLE yapi_logs.yapi_log ADD COLUMN IF NOT EXISTS denied_reason LowCardinality(String) AFTER outcome;
