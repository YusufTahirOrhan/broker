# Devir notu

Bu repo, kurumdaki istek broker'ının dinamik ve gözlemlenebilir hale getirilmesi işinin backend'idir. Tasarım ve frontend sözleşmesi tamamlandı; çekirdek ve beş modül (giriş servisi dahil) yazıldı, `mvn -B verify` yeşil. 2026-10-16 turunda "Sıradaki iş"in 1–6. maddeleri yapıldı: Oracle 19c, yanıt verisinin loglanması, eski broker'la aynı oturum ve yanıtlar, giriş servisi, DEV'e bağlı yerel çalıştırma, `${user.permission}`; alarm (7) sonraki tur. Hepsi PR #1 ile, inceleme düzeltmeleriyle birlikte main'e merge edildi (2026-10-16). Düzeltmeler: yerel giriş servisi DEV'in ortak Redis anahtarlarına yazmaz (madde 5), giriş servisinin imza kontrolü `/actuator/` ve `/internal/` dışındaki her yolda ve imza anahtarı `local` dışında zorunlu, boşluklu şifre LDAP'a gitmez, yetki listesiz Koordinatör cevabı hata (madde 4). `mvn -B verify`: 203 test. Bu not, işi devralan oturumun bağlamı yeniden kurmadan devam etmesi içindir.

Son güncelleme: 2026-10-26.

## Kaynaklar

| Belge | Repodaki kopya | Canlı sürüm |
| --- | --- | --- |
| Tasarım dokümanı | `docs/design.md` | https://claude.ai/code/artifact/b5c786de-4fe3-430e-b5f4-5a44d6730c0b |
| Frontend rehberi (admin API sözleşmesi) | `docs/frontend-guide.md` | https://claude.ai/code/artifact/77705450-7cee-4350-94a9-accec2378a1a |

`docs/frontend-guide.md` merkezi admin API'sinin sözleşmesidir: yollar, gövdeler, hata kodları ve kurallar oradan birebir uygulanır. Sözleşme değişirse aynı commit'te bu dosya ve sonundaki "Değişiklik kaydı" tablosu güncellenir, commit mesajına "Frontend rehberi değişti" yazılır. Canlı sürüm oradan eşitlenir.

`docs/design.md` bu repoda değiştirilmez; canlı doküman güncellenip buraya aktarılır. Tasarıma yansıması gereken bir şey çıkarsa "Tasarıma yansıtılacaklar" başlığına yazılır.

## Sıradaki iş (2026-10-26)

**Durum:** Yapılacak. Kararlar kullanıcıyla 2026-10-21 ve 2026-10-26'da netleşti ("Eski broker'dan teyit edilenler", "Verilmiş kararlar").

Maddeler sırayla yapılır; her biri kendi commit'leriyle biter, her push'tan önce `mvn -B verify` yeşildir. API sözleşmesi değişen her maddede `docs/frontend-guide.md` ve değişiklik kaydı aynı commit'te güncellenir. `docs/design.md`'ye dokunulmaz: tasarım dokümanını tur bitince Claude günceller, uygulanırken netleşenler "Tasarıma yansıtılacaklar"a yazılır. Eski broker'dan gelen gövdeler, anahtarlar ve alan adları korunur.

### 1. Ad değişikliği: broker → yapi

Projenin adı Yapi. Henüz hiçbir ortamda kurulum yok; migration'lar dahil her şey yerinde değiştirilir.

- **Modüller ve kod:** `yapi-core`, `yapi-runtime`, `yapi-env-admin`, `yapi-admin`, `yapi-log-sink`, `yapi-login`; artifact ve uygulama adları; paket kökü `com.acme.yapi`.
- **Ayarlar:** önek `yapi.*`, ortam değişkenleri `YAPI_*`; betikler, `local.env.example`, `docs/local-run.md`, README'ler, CLAUDE.md, CI.
- **Bizim koyduğumuz adlar:** Redis `yapi:applications`, `yapi:loaded:*`, `yapi:loaded-local:*`, `yapi:routes-changed` (yerel kanal dahil); Kafka `yapi.logs.v1` ve DLQ'su; ClickHouse `yapi_logs.yapi_log`; Oracle şema ve kullanıcıları (`YAPI_ADMIN`, `YAPI_RUNTIME`, `yapi_runtime_reader` …); eski Elastic dokümanlarına eklenen bilgi nesnesi `broker` → `yapi`; yeni metrikler.
- **Eski sözleşmeden değişenler (kullanıcı kararı):** Uç `POST /broker` → `POST /yapi` (taşınan projelerin frontend'leri adresle birlikte yolu da değiştirir); sayaç `broker.gelen.istekler` → `yapi.gelen.istekler` (tag'ler aynı).
- **Değişmeyenler:** Eski Elastic indeksleri ve alanları (`requests_*`, `alarm`), `user:*` Redis anahtarları, eski gövdeler. Belgelerde istek yolundaki bileşen "broker" diye anılmaya devam edebilir.
- **Repo:** GitHub'daki adı kullanıcı `yapi` yapacak (eski adres yönlendirilir); kodda ve belgelerde repo adı geçiyorsa `yapi` olur.
- **Yapıldı (2026-10-26):** Tek commit. Modül klasörleri ve artifact'ler `yapi-*` (üst pom `yapi-platform`, groupId ve paket kökü `com.acme.yapi`); ayarlar `yapi.*`, ortam değişkenleri `YAPI_*`; uç `POST /yapi`, sayaç `yapi.gelen.istekler`; eski Elastic dokümanlarındaki bilgi nesnesi `yapi`. Değişmeyenler testlerle sabit: `requests_*` indeksi ve alanları, `user:session:{uid}` (`SharedNamesTest`), eski gövdeler; eski yol `POST /broker` 404 (`CommonFormatTest`). Uygulanırken listede olmayan adlar da aynı kuralla değişti:
  - **Sınıflar:** `YapiApplication`, `YapiController`, `YapiHandler`, `YapiErrors`, `YapiProperties`, `YapiMetrics` (testte `YapiFlowTest`).
  - **Header ve kod:** env-admin → runtime test çağrısında `X-Yapi-Test-Secret`; env-admin'in iç hata kodu `YAPI_UNREACHABLE` (admin bunu frontend'e yine `ENV_UNREACHABLE` diye verir).
  - **Log hattı:** olay alanı `yapi_instance` (Kafka, ClickHouse kolonu, Elastic'te `yapi.yapi_instance`); consumer group'lar `yapi-logs-elastic`, `yapi-logs-clickhouse`; DLQ header'ları `yapi.dlq.sink|reason|origin`; ClickHouse `yapi_logs.yapi_log`, `yapi_logs.yapi_metrics_1m(_mv)`, DDL dosyası `clickhouse/yapi_logs.sql`; Elastic bileşen şablonu `yapi-fields`.
  - **Oracle:** şemalar `YAPI_ADMIN`, `YAPI_RUNTIME`; kullanıcılar `yapi_admin_app`, `yapi_runtime_reader`, `yapi_runtime_writer`; yerelde `yapi_admin_local`, `yapi_runtime_local`; migration klasörleri `db/migration/yapi_admin`, `yapi_runtime`.
  - **Diğer:** audit logger'ı `yapi.audit`; instance ID öneki `yapi-`; dosya yolları `/etc/yapi/...` (keystore'lar), `/var/lib/yapi/log-spool`; yerel override dosyası `yapi-local.json`; `scripts/local-run.sh` uygulama adı `runtime` (eskiden `broker`); ekranın adı "Yapi Admin" (rehber ve 403 mesajı).
  - **Ortam değişkenleri (tamamlama, ayrı commit):** Giriş servisinin ve Koordinatör'ün değişkenleri de `YAPI_` önekli: `YAPI_LOGIN_PORT`, `YAPI_LOGIN_LDAP_MODE`, `YAPI_LOGIN_LDAP_URLS`, `YAPI_LOGIN_LDAP_PRINCIPAL_PATTERN` (eskiden relaxed binding'le `LOGIN_LDAP_PRINCIPALPATTERN`; artık açık placeholder, `SettingsFileTest`), `YAPI_LOGIN_KOORDINATOR_SECRET`, `YAPI_KOORDINATOR_URL`, `YAPI_KOORDINATOR_MODE`, `YAPI_KOORDINATOR_AUTH_HEADER`, `YAPI_KOORDINATOR_AUTH_VALUE`, admin'de `YAPI_KOORDINATOR_DEV_URL`, `YAPI_KOORDINATOR_PREPROD_URL`, `YAPI_KOORDINATOR_PROD_URL`.
  - **Kalanlar:** İstek yolundaki bileşen yorumlarda, belgelerde ve kullanıcı mesajlarında "broker" diye geçmeye devam eder ("Çağrı broker'ın imzasını taşımıyor." gibi). Kafka'nın kendi adları (`EmbeddedKafkaBroker`) değişmez.

### 2. Ortam adları

TEST, PREPROD olur; ortamlar DEV, PREPROD, PROD (migration'daki tohum, ayarlar, testler, rehberdeki örnekler).

- **Yapıldı (2026-10-26):** `V2__environments.sql`'de ikinci ortam `PREPROD` (görünen ad `Preprod`), Flyway placeholder'ı `preprod_admin_api_url` (`YAPI_PREPROD_ADMIN_API_URL`); admin'in yetki adresi `yapi.admin.authorization.urls.PREPROD` (`YAPI_PREPROD_AUTHORIZATION_URL`). Testler, yorumlar ve rehberin örnekleri PREPROD. Kurulum olmadığı için migration yerinde değişti. Uygulanırken netleşen: `SOURCE_ACTIVE` ön kontrol mesajı ortam koduna Türkçe ek yapıştırıyordu ("TEST'te", PREPROD'la yanlış olurdu); artık "v4 PREPROD ortamında aktif" (`PromotionFlowTest`). Test çağrısıyla ilgili adlar (`TEST_NOT_ALLOWED`, `YAPI_TEST_SECRET`, `test_calls_allowed`) ortam adı olmadığı için aynı.

### 3. Koordinatör'ün gerçek uçları

Biçimler "Eski broker'dan teyit edilenler"de. Yollar, alan adları (`is_yapi` dahil) ve isteğe bağlı kimlik header'ı ayarda; şimdilik header gerekmediği varsayıldı.

- **Login yetkileri:** `GET /api/koordinator/personel-yetki/aktif-yetkiler/{uid}` (`uid` = sicil). Map'teki bütün listelerin `yetkiIsim`'leri, sırası korunup tekrarları atılarak oturumdaki `permissions` olur (string listesi). Bunun eski login'in yazdığıyla aynı olduğu varsayıldı; pilot projede eski ve yeni broker'ın birbirinin oturumunu okuduğu teyit edilecek. 200 dışı ya da beklenmeyen biçim Koordinatör hatasıdır (giriş reddi, bugünkü gibi). İki uç da yalnızca aktif proje ve yetkileri döner; yine de `askidaMi` true (ya da 1) olan yetki aktif değildir ve listeye girmez. `durum`, `projeDurum` ve tarihler süzülmez.
- **Application listesi:** `GET /api/koordinator/projeler-ve-yetkileri`'nden `is_yapi` true olanların `projeAdi`'leri.
- **Admin:** Proje kataloğu, projenin yetkileri ve yetkinin hedef ortamda varlığı aynı uçtan, ortam başına adresle; yalnızca `is_yapi` projeler. Eşleşme `projeId`, yetki kodu `yetkiList[].yetki`. Liste ortam başına birkaç dakika önbellekte tutulur.
- **Yetki açma kalkar:** `POST /projects/{id}/permissions` ve ekrandaki "yetki ekle". Yetkiler yalnızca Koordinatör'den açılır.
- **Yapıldı (2026-10-26):** Uygulanırken netleşenler ve varsayımlar:
  - **Giriş servisi:** `HttpKoordinatorClient` (`koordinator.permissions-path`, `permission-field`, `suspended-field`; `projects-path`, `application-field`, `yapi-field`; varsayılanlar gerçek uçlar). Boş map yetkisiz giriş; map değil, değeri liste değil ya da `yetkiIsim`'i olmayan öğe Koordinatör hatası (giriş reddi, uyarı logu). `KoordinatorClient.projectCodes()` → `applications()`: `is_yapi` olanların `projeAdi`'si, tekrarsız; `projeAdi`'si olmayan proje atlanır.
  - **Evet/hayır alanları** (`is_yapi`, `askidaMi`): `true`, `1`, `"true"`, `"1"` evet; diğer her şey (yok, null, false, 0) hayır (`Json.isTrue`).
  - **Admin:** `KoordinatorProjects` her ortamın `projeler-ve-yetkileri` listesini (yalnızca `is_yapi`) `koordinator.cache-ttl` (varsayılan 5 dk) boyunca tutar; başarısız çağrı önbelleğe girmez, 502 `UPSTREAM_ERROR`. Adresler `koordinator.urls.<ENV>` (`YAPI_KOORDINATOR_DEV_URL`, `YAPI_KOORDINATOR_PREPROD_URL`, `YAPI_KOORDINATOR_PROD_URL`); `yapi.admin.catalog.url` ve `yapi.admin.authorization.urls` kalktı.
  - **Katalog hangi ortamdan:** Proje kataloğu (arama, ID ile okuma, gece ad yenilemesi) tek bir ortamın Koordinatör'ünden: `yapi.admin.catalog.environment`, varsayılan `PROD` (varsayım; projeler PROD'da en eksiksiz). Arama `projeAdi` ya da `projeId` içinde, harf duyarsız.
  - **Projenin yetkileri:** Koordinatör'deki projesinin (`projeId` = `externalProjectId`) `yetkiList[].yetki`'leri; `UNLINKED` projede liste boş. `description` Koordinatör vermediği için `null`; `suffix` kod önekle başlamıyorsa `null`; `routeCount` kodlara göre sayılır (önek aranmaz). Yetkinin ortamda varlığı (kayıt doğrulaması ve `PERMISSIONS_EXIST`): o ortamdaki herhangi bir Yapi projesinin listesinde olması.
  - **Yetki açma:** `POST /projects/{id}/permissions` kaldırıldı (405 `METHOD_NOT_ALLOWED`), `AuthorizationService.create` de. Mesajlarda "yetkilendirme servisi" ve "proje servisi" yerine "Koordinatör".
  - **Kimlik header'ı:** Gerekmediği varsayıldı ("Bilgi bekleyenler" 1); `koordinator.auth-header`/`auth-value` ile eklenebilir.
  - **Testler:** `HttpKoordinatorClientTest` (login: map'in sırası, tekrar, `askidaMi`, boş map, beklenmeyen biçimler, `is_yapi`), `KoordinatorClientsTest` (admin: katalog, ortam başına yetkiler, varlık, önbellek süresi, hatalar), `JsonTest`, `SessionAndProjectsTest` (salt okunur yetkiler, POST 405), `LoginApiTest` ve `LoginEndToEndTest` gerçek biçimlerle.

### 4. Giriş servisi: DEV'de magic key

Yeni LDAP modu `magic-key`, yalnızca `dev` profilinde (başka profilde servis başlamaz): `uid` boş değilse ve şifre ayardaki anahtara (`YAPI_LOGIN_MAGIC_KEY`) sabit zamanlı karşılaştırmayla eşitse başarılı. Her giriş `info` loglanır (uid; şifre yok). Yetkiler yine Koordinatör'den gelir. PREPROD ve PROD `bind` modunda; adres ve principal deseni ayardan.

### 5. Admin'e giriş: giriş uygulamasının oturumu

SSO ve JWT kalkar. Kullanıcılar şirketin giriş uygulamasından girer; Yapi yetkisi olan (`YAPI_DEVELOPER`, `YAPI_ADMIN`) admin ekranını görür.

- **İstek:** Admin ekranı her istekte `X-Token`, `X-Uid`, `X-Clientip`, `X-User-Agent` gönderir.
- **Doğrulama:** Admin API oturumu, oturum ortamının (`yapi.admin.session.environment`, varsayılan `PROD`) env-admin'ine sorar; merkez ortama yalnızca env-admin'den girer. env-admin'e yeni uç: `POST /admin/v1/sessions/verify` `{"uid", "token", "clientIp", "userAgent"}` → `{"valid", "permissions"}`; kaydı core'daki codec'le okur ve broker'ın kuralıyla eşleştirir. Admin API sonucu kısa süre (varsayılan 30 sn) önbellekte tutar.
- **Roller (ayardan):** `VIEWER` ← `YAPI_DEVELOPER` ya da `YAPI_ADMIN`; `ROUTE_EDITOR` ← `YAPI_DEVELOPER`, `YAPI_ADMIN`; `APPROVER` ← `YAPI_ADMIN`. Yetki adları değişebilir. Dört göz kuralı aynen.
- **Hatalar:** Oturum geçersizse 401, rol yetmezse 403. `local` profilinde HTTP Basic kalır.
- Rehberin "Kimlik" bölümü ve değişiklik kaydı. DevOps notu: admin API'ye gelen isteklere `X-Clientip`'i broker'dakiyle aynı proxy koymalı.

### 6. Giriş servisi: kullanıcı ayarları

Eski broker dashboard yerleşimini ve hızlı bağlantıları kendi içinde Redis'te tutuyordu; yeni yerleri giriş servisi.

- `GET` ve `PUT /users/me/settings/{name}`; `name` ayardaki listeden: `layout` → `user:layout:{uid}`, `quick_link` → `user:quick_link:{uid}`.
- Kullanıcı, imzalı `X-User-Context`'teki uid'dir (gövdeden alınmaz); route'lar oturumlu tanımlanır.
- `PUT` gövdedeki JSON'u olduğu gibi ve süresiz yazar; eski `RedisTemplate` + `JacksonJsonRedisSerializer` ile aynı metin olmalı. `GET` kayıt yoksa 200 ve `null` döner.
- Route'ları kullanıcı eski subtask adlarıyla tanımlar, yanıt biçimini profille eskisine benzetir. `harici_uygulamalar` da kullanıcının tanımlayacağı sıradan bir route (Koordinatör'de `GET /api/koordinator/projeler-external`). `refresh_permissions`'ın karşılığı giriş servisindeki iç uç; Koordinatör ekibi doğrudan ona geçecek, kod gerekmez.

### 7. Alarm

Eski akış ve kararlar "Eski broker'dan teyit edilenler" ve "Verilmiş kararlar"da.

- **Alarm servisi route'u:** Tanımında `alarmService: {"resultPath": "<JSON yolu>"}` olan sıradan bir route (`resultPath` boşsa yanıtın kendisi dizi). Gövde şablonunda yeni yer tutucu `${alarm.values}` (gelen istekteki değerlerin JSON dizisi) ve mevcut yer tutucular kullanılır. Frontend'den doğrudan çağrılamaz (404 `UNKNOWN_TASK`); kendisinde alarm kontrolü olmaz.
- **Seçim:** Projenin ortam ayarında `alarmRoute` (`{"maintask", "subtask"}` ya da null; runtime'da `runtime_project`'e yazılır). Subtask tanımında `alarm: {"check": "INHERIT" | "OFF" | "ROUTE", "maintask", "subtask"}`, varsayılan `INHERIT`. Seçilen route alarm servisi route'u olmalı; taşıma ön kontrolü route'un hedef ortamda aktif olduğuna bakar.
- **Akış:** Oturum, yetki ve alan doğrulamasından sonra, forward'dan önce. Değerler: gövdedeki bütün yaprak değerler (iç içe dahil), metin olarak ve sırayla; zarf alanları (`maintask`, `subtask`, `application`, `uid`) ve maskelenen alanlar hariç. Alarm route'u kendi timeout'uyla çağrılır.
- **Alarm varsa:** Servis çağrılmaz; yeni outcome `ALARM`. `legacy-responses` açıkken HTTP 200 `{"response": false, "badprocess": true, "status": 500}`, kapalıyken 403 ortak format. RESPONSE olayına `alarm` (route ve alarm listesi) yazılır.
- **Alarm servisi hatası** (bağlantı, timeout, 2xx dışı, okunamayan yanıt): istek reddedilir; ortak formatta 503 `TECHNICAL_ERROR`, mesaj "Alarm kontrolü yapılamadı; istek gönderilmedi."; olaya yazılır.
- **Elastic:** Log sink, alarm olan olaylardan `alarm` indeksine doküman yazar: `_id` = `query_id`; gövde eski `alarm_log` gibi isteğin parametreleri olduğu gibi; yeni bilgi `yapi` nesnesinde (request_id, uid, client_ip, alarm listesi, alarm route'u, zaman).
- **`alarm_list`:** Alarm yoksa servise giden gövdeye eklenmez (açık nokta).
- Rehber: proje ortam ayarı, subtask formu, `definition-rules`, yeni outcome, değişiklik kaydı.

## Tamamlanan iş (2026-10-16)

**Durum:** 1–6 bitti, PR #1 ile merge edildi. 7 (alarm) "Sıradaki iş (2026-10-26)" 7'ye taşındı.

Bu bölüm 2026-10-16'daki adlarla yazıldı (`broker-*` modüller, `broker.*` ayarlar, `broker:*` anahtarlar); güncel adlar "Modüller ve durum"da ("Sıradaki iş (2026-10-26)" 1).

Maddeler sırayla yapılır; her biri kendi commit'leriyle biter ve her push'tan önce `mvn -B verify` yeşildir. 7. madde bu turda yapılmaz. "Bilgi bekleyenler"deki konular işi durdurmaz: o parçalar arayüz arkasında, ayara bağlı ve aşağıdaki varsayılanlarla yazılır; bilgi gelince yalnızca o parça değişir. Eski broker'dan alınan gövdeler, anahtarlar ve alan adları birebir korunur ve testlerle sabitlenir. Bir madde bitince bu nottaki modül bölümleri de güncellenir.

### 1. Oracle 19c

İki veritabanı da (merkezi DB ve her ortamın runtime DB'si) Oracle 19c olacak. PostgreSQL bağımlılıkları, migration'ları ve testleri tamamen kalkar.

- **Testler:** 19c'nin serbest imajı yok; Testcontainers'ın Oracle XE modülüyle `gvenzl/oracle-xe:21-slim-faststart`. 19c'de olmayan özellikler kullanılmaz: `JSON` veri tipi, SQL `BOOLEAN`, `IF [NOT] EXISTS`, `JSON_TRANSFORM`. İmaj büyük; modül başına tek container kullanılır ve CI süresi izlenir.
- **Sürücü ve migration:** Boot'un yönettiği ojdbc; Flyway için `flyway-database-oracle` (Flyway 10'dan beri 19c için lisans gerekmiyor). PL/SQL bloklarından sonra `/` gelir.
- **Tipler:** `JSONB` → `CLOB` + `CHECK (x IS JSON)`; `BOOLEAN` → `NUMBER(1)` + `CHECK (x IN (0, 1))`; `TIMESTAMPTZ` → `TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP`; `TEXT` → `VARCHAR2(4000 CHAR)` ya da `CLOB`. `GENERATED ALWAYS AS IDENTITY` aynı kalır.
- **Diziler (`TEXT[]`):** Merkezde `permissions` ve `foreign_permissions` yetki koduna indeksli ayrı bir tabloya taşınır (`GET /permissions/{code}/routes` ve önek trigger'ı bunu kullanır). Runtime'da `permissions` JSON dizisi olur; snapshot zaten bütün okunur.
- **SQL karşılıkları:** `ON CONFLICT` → `MERGE` (eşzamanlı eklemede ORA-00001 gelirse bir kez tekrar); `RETURNING` → kolon adıyla `KeyHolder`; `ANY(?)`, `unnest`, `jsonb_array_elements_text` → `IN (:liste)` ya da `JSON_TABLE`; `to_jsonb(satır)` ile yazılan history → snapshot Java'da `Json` ile kurulur; `ILIKE` → `LOWER(..) LIKE .. ESCAPE`; `LIMIT/OFFSET` → `OFFSET .. ROWS FETCH NEXT .. ROWS ONLY`.
- **Kısıtlar ve indeksler:** Kısmi unique index'ler (`uq_promotion_open*`) → `CASE` ile fonksiyon tabanlı unique index (kapalı taleplerde bütün anahtarlar NULL); `lower(x COLLATE "C")` → `LOWER(x)`; `~` → `REGEXP_LIKE`; `(a IS NULL) = (b = 'X')` gibi boolean eşitlikler açık yazılır.
- **Trigger'lar:** `forbid_history_change`, `forbid_column_change` ve `check_route_permissions` tablo başına PL/SQL trigger olur (`RAISE_APPLICATION_ERROR`); `TG_ARGV` ve `to_jsonb` yerine kolonlar açıkça karşılaştırılır.
- **Adlar ve boş string:** `comment` Oracle'da ayrılmış kelime; kolon yeniden adlandırılır, API alan adları değişmez. Diğer kolonlar `V$RESERVED_WORDS`'e karşı kontrol edilir. Oracle boş string'i NULL sayar; NOT NULL metin kolonlarına ve `''` ile karşılaştıran koda bakılır.
- **Şema ve yetkiler:** Şema = Oracle kullanıcısı, DBA açar (`create-schemas=false`). Flyway şema sahibiyle çalışır; uygulamalar kendi kullanıcılarıyla bağlanır, `search_path`'in yerine Hikari `schema` ile `CURRENT_SCHEMA` verilir. `pg_roles` bloğu → rol yoksa ORA-01917'yi yutan `EXECUTE IMMEDIATE 'GRANT ...'` bloğu.
- **Snapshot okuması:** Oracle `REPEATABLE READ` desteklemez (yalnızca READ COMMITTED ve SERIALIZABLE). `RouteRepository.loadSnapshot()` `SET TRANSACTION READ ONLY` ile okur; Oracle'da bu, işlem boyunca tutarlı okuma verir. Örneğin `DataSourceTransactionManager#setEnforceReadOnly(true)` ve izolasyon seviyesi vermeden `readOnly = true`.
- **CLOB:** 32 KB'tan büyük bir tanımla yazma ve okuma test edilir.
- **Bitti sayılır:** Bütün DB testleri Oracle XE'de geçer; `local` profili ve CI Oracle ile çalışır; bu nottaki PostgreSQL anlatımları güncellenir.
- **Yapıldı (2026-10-16):** Üç modül Oracle'da; PostgreSQL bağımlılığı kalmadı. Uygulanırken verilen kararlar:
  - **Test imajı:** `ghcr.io/gvenzl/oracle-xe:21-slim-faststart` (Docker Hub anonim çekişte 429 veriyor; aynı imaj GitHub registry'sinde). Ortak yardımcı core test-jar'da `OracleDatabase`: şema sahibi container'ın uygulama kullanıcısı, diğer kullanıcılar yalnızca `CREATE SESSION` ile açılır; yetkileri migration'daki GRANT bloğundan gelir. Testler böylece gerçek kullanıcı ayrımıyla çalışır: env-admin `broker_runtime_writer`, broker `broker_runtime_reader`, admin `broker_admin_app`; Flyway ve test temizliği şema sahibiyle. CI'da imaj ayrı bir adımda çekilir.
  - **Merkezde yetkiler:** `route_permission (definition_id, kind OWN/FOREIGN, sort_order, code)`, `code` indeksli; kayıtta liste silinip sırasıyla yeniden yazılır (uygulama kullanıcısının tek DELETE yetkisi burada). Okumada `JSON_ARRAYAGG`. `trg_route_permissions` bu tablonun satırlarında çalışır. `ck_route_permission` (anonim değilse en az bir yetki) tablo dışına çıktığı için yalnızca uygulamada (`RouteService`) kalır.
  - **History snapshot'ı** Java'da kurulur (`RouteSnapshots.stored`, `ProfileRepository.history`); anahtarlar `to_jsonb`'nin verdiği snake_case adlar, zaman damgaları ISO metni.
  - **`comment` kolonları** `change_note` oldu; API alanı `comment` kaldı. Diğer adlar `V$RESERVED_WORDS`'e karşı testle denetlenir (`OracleDatabase.reservedNames`).
  - **Metinler:** Serbest metinler (açıklama, not, gerekçe, hata nedeni) `CLOB`; JSON'lar `CLOB` + `IS JSON` ve CLOB olarak bağlanır (`SqlParameterValue(Types.CLOB)`). Runtime `permissions` `VARCHAR2(4000 CHAR)` JSON dizisi, `ck_runtime_permission` `JSON_EXISTS(permissions, '$[0]')` ile.
  - **Arama** `LOWER(..) LIKE .. ESCAPE '\'`; `%`, `_` ve `\` metin olarak aranır. `p.maintask || ' / ' || r.subtask` Oracle'da NULL vermediği için talep adı `CASE` ile seçilir.
  - **Runtime yazımı:** `MERGE` (eşleşirse yalnızca `promotion_id` daha yeniyse günceller) + aynı transaction'da versiyon artışı; ORA-00001'de MERGE bir kez tekrarlanır. Eşzamanlı ilk yazma testi var.
  - **Snapshot okuması:** `RouteRepository` kendi `DataSourceTransactionManager`'ını `enforceReadOnly` ile kurar (`SET TRANSACTION READ ONLY`); arada commit edilen yazmayı görmediği testle doğrulandı.
  - **32 KB'tan büyük tanım** (Türkçe karakterli 40 KB'lık şablon) env-admin, broker ve merkezde yazılıp okunur.
  - `RealTargetsTest`'in Elastic container'ında disk eşiği kapalı: derleme makinesinin diski %90'ın üstündeyse (Oracle imajı ~6 GB) Elasticsearch yeni indekse shard ayırmıyor ve yazmalar zaman aşımına düşüyordu.

### 2. Yanıt verisinin loglanması ve küçük log düzeltmeleri

Subtask editöründeki "Yanıt verisi loglansın" seçeneği mevcut `logging.logResponseBody` bayrağıdır (varsayılan kapalı). API'nin biçimi değişmez, anlamı genişler (rehberde güncellendi):

- **Kapalı:** Bugünkü gibi; eski Elastic dokümanında `data_response.data` boş string, ClickHouse'ta yanıt gövdesi yok.
- **Açık:** `data_response.data`, yanıttaki `data` değerinin maskelenmiş (`maskFields` + `password`) JSON metni olur. `maxBodyBytes`'ı aşarsa UTF-8 karakteri bölmeden kırpılır ve `broker.body_truncated: true` yazılır. Alan string kaldığı için Elastic'teki eşleme değişmez. ClickHouse'a bugünkü gibi gövde yazılır.
- **Profilsiz JSON yanıt:** Kapalıyken bugünkü gibi `{"status": <kod>}`. Açıkken maskelenmiş gövdenin kendisi; üst seviyedeki `data` string değilse JSON metnine çevrilir; sınırı aşarsa `{"status": <kod>, "data": "<kırpılmış metin>"}`.
- Değişiklik `EventFactory.clientResponse`'ta (route'un log politikasını alır); `LegacyDocuments` aynı kalır.
- **Elastic aylık indeksi:** Eski kod `requests_yyyy-MM`'i saat dilimi vermeden (`new SimpleDateFormat("yyyy-MM")`), yani sunucunun saat diliminde hesaplıyor. `broker.sink.elastic.index-zone` varsayılanı `Europe/Istanbul` olur; kullanıcı eski sunucunun TSİ'de çalıştığını teyit etti.
- **Eski sayaç:** `broker.gelen.istekler` tag'leri (`maintask`, `subtask`) ve "Bilinmeyen Proje"/"Bilinmeyen Endpoint" eski controller'la teyit edildi; ayardaki "teyit edilmeli" notları kalkar.
- **Test:** core ve runtime birim testleri; `RealTargetsTest`'te 40 KB'lık `data` metni olan doküman varsayılan dinamik eşlemeyle kabul edilir.
- **Yapıldı (2026-10-16):** `EventFactory.clientResponse(body, logging)` (profil zarfı) ve `EventFactory.passedThrough(status, body, logging)` (profilsiz), ikisi de `ClientLog(value, truncated)` döner; kırpma olayın `body_truncated`'ına eklenir. Metne çevirme ve UTF-8'i bölmeden kırpma core'da `Masker.text`. Uygulanırken netleşenler:
  - Açıkken profil zarfının tamamı maskelenir (yalnızca `data` değil); `data` string ise olduğu gibi kalır, değilse JSON metni olur. `data`'sı olmayan zarf olduğu gibi yazılır.
  - Profilsiz yanıt açıkken JSON nesnesi değilse (dizi, sayı, metin) `{"status": <kod>, "data": "<metin>"}` yazılır; `data_response` Elastic'te nesne kalır. Sınır, `data`'sı metne çevrilmiş maskeli gövdenin JSON metnine uygulanır; aşarsa o metin kırpılıp `data`'ya konur.
  - Elastic indeks ayı varsayılanı `Europe/Istanbul` (`SinkProperties`, `BROKER_ELASTIC_INDEX_ZONE`); ay sınırındaki olay testle sabitlendi. "Teyit edilmeli" notları kalktı.

### 3. Broker: eski broker'la aynı oturum, application kontrolü ve yanıtlar

Mevcut frontend'ler bunlara bağlı.

**Oturum kaydı.** Redis anahtarı `user:session:{uid}` (`uid` gövdeden); değer `{"uid", "X-Token", "X-Clientip", "X-User-Agent", "permissions": [...]}`; süre 86400 sn, login'de verilir, broker uzatmaz. Kullanıcı başına tek oturum vardır, yeni login eskisini ezer.

- Oturum geçerli = kayıt var **ve** `X-Token`, `X-User-Agent`, `X-Clientip` header'ları kayıttakiyle birebir aynı (büyük/küçük harf duyarlı; eksik header eşleşmez).
- `${user.id}` ve `X-uid` = kayıttaki `uid` (doğrulanmış gövde `uid`'i). `perm_list` = kayıttaki `permissions`, sırasıyla.
- Kayıt broker-core'da `SessionRecord` + `SessionRecordCodec`; broker ve giriş servisi aynısını kullanır. Biçim eski broker'ın `RedisTemplate`'inden teyit edildi: anahtar `StringRedisSerializer` (düz string), değer `JacksonJsonRedisSerializer<>(Object.class)`, yani tip bilgisi (`@class`) olmayan UTF-8 JSON nesnesi. Boş alan JSON `null` yazılır. Böylece eski ve yeni broker aynı kaydı okuyup yazabilir.
- Anahtar deseni ve alan adları ayardan; varsayılanlar yukarıdakiler (`broker.session.key-pattern: "user:session:{uid}"`).

**Application kontrolü.** Gövdedeki `application` izinli listede olmalı (harf duyarsız, `Names.key`); değilse ya da yoksa HTTP 200 `{"response": false}`, outcome `DENIED`. Login, logout ve anonim route'lar dahil her istekte uygulanır.

- Liste Redis'te `broker:applications` anahtarında bir JSON dizisidir; giriş servisi yazar (madde 4). Broker açılışta ve 30 sn'de bir okur, son başarılı listeyi bellekte tutar; liste hiç yüklenmediyse readiness'te hazır görünmez.
- `broker.applications.mode`: `redis` (varsayılan), `static` (ayardaki liste; local ve testler), `off`.

**Sıra** (`broker.compat.legacy-responses: true`, varsayılan):

1. Gövde büyük ya da JSON nesnesi değil → 400 `INVALID_REQUEST` (ortak format).
2. `X-Token` yok → 400 `INVALID_REQUEST` (eski broker'da controller NPE ile 500 dönüyor; karar değişmedi).
3. `application` izinli değil → 200 `{"response": false}`.
4. Gövdedeki maintask `broker.session.public-maintasks` listesindeyse (varsayılan `refresh_permissions`, `giris_public`; eski broker bunlara login'den önce bakıyordu) normal route çözümlemesine geçilir; bu route'lar anonimdir. Bu iki maintask'in özel bir kodu yok: subtask'leri eski broker'da proje servislerine sabit yazılmıştı, diğer subtask'ler gibi route olarak tanımlanır. Değilse ve `X-Token` `login` ya da `logout` ise gövdedeki maintask/subtask'e bakılmadan ayardaki route'a gidilir (`broker.session.routes.login: giris/login`, `logout: giris/logout`). Route yoksa ya da anonim değilse 503 `TECHNICAL_ERROR` ve hata logu (yapılandırma hatası). Eski dokümana gövdedeki maintask ve subtask yazılır.
5. Route aranır:
   - Yoksa önce oturum kontrol edilir: geçersizse 301 yanıtı, geçerliyse 404 `UNKNOWN_TASK`. Eski broker da önce token'a bakıyordu; böylece oturumsuz biri hangi route'ların var olduğunu göremez.
   - Anonimse bugünkü gibi: oturum geçerliyse kullanıcı çözülür, değilse kullanıcısız devam edilir.
   - Anonim değilse: oturum geçersiz → 200 `{"response": false, "status": 301, "reason": "check X-Token"}` (outcome `DENIED`); Redis okunamadı → 503 `TECHNICAL_ERROR`; yetki yok → 403 `DENIED` (ortak format).

- `legacy-responses: false` iken bugünkü 400 ve 401'li ortak format kalır.
- **Loglama:** Eski broker her yanıtı `data: ""` ekleyerek logluyordu (`log_response.putAll(body); put("data", "")`). Broker'ın eski biçimli yanıtları (`{"response": false}`, 301) ve oturum işlemlerinin yanıtları `client_response`'a `"data": ""` eklenerek yazılır. Oturum işlemlerinde log bayrağına bakılmaz; login yanıtındaki `X-Token` bugünkü gibi açık yazılır.
- **Zarf alanları yer tutucuda:** `${request.uid}`, `${request.application}`, `${request.maintask}`, `${request.subtask}` `fields`'ta tanımlanmadan okunur (doğrulayıcı, önizleme, `DefinitionRules`). Login route'u `uid`'i böyle iletir. Anonim route'ta `uid` doğrulanmamıştır.
- **İmzalı bağlam:** `user-context-secret` ayarlıysa oturum işlemlerinde de `X-User-Context` imzalanır (`userId` null); giriş servisi imzasız çağrıyı reddeder.
- **Rehber:** `docs/frontend-guide.md` bu hedefe göre güncellendi ("Broker'ın uygulamalara döndüğü yanıtlar", `${request.*}` ve `${user.id}` satırları, 2026-10-16 kaydı). Uygulama farklı çıkarsa rehber ve "Değişiklik kaydı" aynı commit'te düzeltilir.
- **Test:** yanıt gövdelerinin anahtar kümesi ve tipleri; header eşleşme matrisi; application listesi kaynakları ve readiness; public maintask'ler; bilinmeyen route + geçersiz oturum → 301; login ve logout route eşlemesi.
- **Yapıldı (2026-10-16):** `SessionRecord` + `SessionRecordCodec` core'da (`com.acme.broker.core.session`); broker `SessionStore.find(uid, token, clientIp, userAgent)`, `ApplicationList` (+ `ApplicationsHealthIndicator`) ve `BrokerHandler`'daki yeni sıra. Uygulanırken netleşenler:
  - **Sayaç:** `broker.gelen.istekler`, eski controller'daki gibi `X-Token` kontrolünden sonra ve application kontrolünden önce artar (`X-Token` login/logout değilse).
  - **Yer tutucu `${user.permission}`:** Tanımlardaki yer tutucu tablosuna eklenir: kullanıcıda olan ilk route yetkisi (önce proje, sonra başka proje yetkileri, tanımdaki sırayla); oturum ya da eşleşen yetki yoksa alan yazılmaz.
- **Yerel geliştirme bölümü:** Yerel uygulamalar DEV'e bağlanır, Docker kullanılmaz (`docs/local-run.md`). Yerel broker 8083'te, durumunu `broker:loaded-local:*`'a yazar; yerel env-admin ve admin DEV Oracle'daki ayrı şemalarla çalışır. Admin `local` profilinde boş şemaya örnek veri yazar. OpenAPI ve Swagger yalnızca `local` ve `dev` profillerinde.
- **`legacy-responses: false`:** application izinli değilse 403 `DENIED`, oturum geçersizse (route yoksa da) 401 `DENIED`, ortak formatta.
  - **Route yok + Redis okunamadı:** 503 `TECHNICAL_ERROR` (oturum bilinmeden 301 ya da 404 verilmez).
  - **Oturum işlemleri:** Olaya gövdedeki maintask/subtask yazılır (route'unki değil); REQUEST gövdesi bayraktan bağımsız maskeli yazılır; `client_response` = giriş servisinin gövdesi + `"data": ""` (X-Token açık). Servisin gövdesi RESPONSE olayına (ClickHouse) yalnızca route'un `logResponseBody`'si açıksa yazılır; böylece token ClickHouse'a girmez. `X-User-Context` `userId: null` ile imzalanır. Public maintask'lerde `X-Token` login olsa da istek oturum işlemi sayılmaz.
  - **Application listesi:** Redis'teki değer JSON dizisi değilse, boşsa ya da anahtar yoksa eski liste kalır; `broker.applications.refresh.failed` sayacı ve log (liste hiç yüklenmediyse hata, sonra uyarı). Readiness grubu `readinessState,applications`. `local` profilinde `static`, liste `BROKER_LOCAL_APPLICATIONS` (varsayılan `web`).
  - **`X-User-Agent` route header'ı olabilir:** Doğrulayıcı `X-User-*` yasağında `X-User-Agent`'ı istisna tutar (giriş servisinin route'u onu `${header.X-User-Agent}` ile iletir). `definition-rules`'a `forbiddenHeaderExceptions` ve zarf yer tutucuları eklendi; rehber ve değişiklik kaydı güncellendi.
  - **Önizleme:** Zarf alanları örnek gövdeden okunur; ek bir doldurma yapılmaz.
  - **Testler:** `BrokerFlowTest` (eski gövdelerin anahtar kümesi ve tipleri, header eşleşme matrisi, başkasının uid'i, application kaynakları, public maintask, login/logout eşlemesi, yapılandırma hatası, anonim route'ta kullanıcı), `CommonFormatTest` (Redis'ten liste, readiness, son iyi liste, ortak format), `SessionRecordTest` (biçim, alan sırası, null, ayardan adlar).

### 4. Giriş servisi (`broker-login`)

Yeni modül, kök `pom.xml`'e eklenir; paket `com.acme.broker.login`, çalıştırılabilir jar `-exec` ekiyle. Yalnızca broker çağırır: admin'de `giris` projesi olarak kaydedilir, `login` ve `logout` onun anonim route'larıdır.

- **`POST /login`**, gövde `{"uid", "password"}`, header'lar `X-Clientip` ve `X-User-Agent`. Her zaman HTTP 200:
  1. LDAP'ta bind ile doğrular → `ldap` (true/false). Boş şifre her zaman false; anonim bind'e düşmesin.
  2. LDAP başarılıysa kullanıcı yetkilerini Koordinatör'den alır (aşağıda); `ok` = HTTP 200. LDAP başarısızsa bu çağrı yapılmaz, yanıt yine 4. adımdakidir (eski kod iki çağrıyı da yapıyordu; dışarıdan fark görünmez).
  3. `ldap && ok` ise token = `UUID.randomUUID()`; kayıt `SET user:session:{uid}` 86400 sn ile yazılır, `EXISTS` ile doğrulanır.
     - Yazıldı: `{"response": true, "status": 200, "LDAP": true, "login": true, "X-Token": "<uuid>"}`
     - Yazılamadı: `{"status": 401, "Ldap": false, "login": false, "X-Token": "404"}`. `response` anahtarı yok; eski kod `Ldap`'ı burada false yapıyor.
  4. Değilse: `{"response": false, "status": 401, "Ldap": <ldap>, "login": false}`
  - Başarıda `LDAP`, hatada `Ldap` yazımı eskisi gibi farklı kalır.
- **`POST /logout`**, gövde `{"uid"}`, header'lar `X-Clientip` ve `X-User-Agent`: kayıt varsa ve kayıttaki `X-Clientip` ile `X-User-Agent` istektekilerle aynıysa silinir → `{"response": true, "status": 200, "message": "logged out"}`; değilse ya da hata olursa `{"response": false, "status": 301, "message": "something went wrong with logging out."}`. Eski broker yalnızca `uid`'e bakıyordu, herkes herkesin oturumunu kapatabiliyordu. Header eşleşmesi frontend değişmeden çalışır (`login.logout.require-client-match`, varsayılan true); kullanıcı onayladı.
- **Koordinatör (yetki projesi):** Kullanıcı yetkileri, proje listesi ve zorla çıkış onunla. Gerçek API'ler sonra gelecek; şimdilik aşağıdaki varsayılan uçlar yazılır, yollar ve alan adları ayardan değişir. Tek base URL (`koordinator.url`), kimlik için ayardan sabit bir header.
  - `GET /users/{uid}/permissions` → `{"yetkiler": ["LOGPROJECT_TEMEL", ...]}` (anahtar eski yanıttaki gibi `yetkiler`).
  - `GET /projects` → `[{"id", "code", "name"}]`; application listesi `code` değerleri.
  - Admin'in mevcut varsayımları (`HttpProjectCatalog`, `HttpAuthorizationService`) aynı base URL'e taşınır.
- **Koordinatör çıkışı:** Koordinatör bir kullanıcının oturumunu kapatabilir (eski `expire_user_triggered_from_koordinator`). Giriş servisinde `POST /internal/v1/sessions/{uid}/logout`, header `X-Koordinator-Secret` (`login.koordinator-secret` ile sabit zamanlı karşılaştırma): silindiyse 204, oturum yoksa 404, gizli anahtar yanlışsa 403. Broker'dan geçmez; Koordinatör doğrudan çağırır.
- **Application listesi:** 5 dakikada bir Koordinatör'ün proje listesini alır ve boş değilse `broker:applications`'a süresiz yazar. Hata olursa eski değer kalır; metrik ve log.
- **LDAP:** Eski koddaki gibi Spring LDAP ile doğrudan bind: `ldapTemplate.getContextSource().getContext(<principal>, password)`, principal `uid@<alan adı>` (Active Directory UPN; preprod'da `uid@test.com.tr`). Bind başarılıysa true; `uid` ya da şifre boş ya da yalnızca boşluksa (`isBlank`, eski kod gibi; LDAP'a gidilmez) ya da herhangi bir exception olursa false. Arama yok. Ayar: `login.ldap.urls`, `login.ldap.principal-pattern` (ör. `{uid}@test.com.tr`; testlerde gömülü LDAP için `uid={uid},ou=people,dc=test` gibi bir DN), bağlantı 3 sn, okuma 5 sn. Eski broker ortam başına ayrı `Ldap` sınıfı kullanıyor (`LdapPreprod`, `spring.profiles.active=preprod`); yeni serviste fark yalnızca ayardır. `local` profilinde ayardaki sabit kullanıcılarla çalışan bir mod olur (`login.ldap.mode: static`); başka profilde açılırsa uygulama başlamaz.
- **Güvenlik:** Şifre hiçbir yerde loglanmaz. Broker'ın imzaladığı `X-User-Context` doğrulanır (`issuedAt` 60 sn içinde), imzasız çağrı 403; `/actuator/` ve `/internal/` dışındaki her yolda. `login.user-context-secret` yalnızca `local` profilinde boş olabilir. Servis yalnızca broker'ın ağından erişilebilir olmalı.
- **Örnek tanımlar:** `giris/login` ve `giris/logout` route tanımları modülün README'sinde ve `broker-local.json` örneğinde: POST, `TEMPLATE` gövde `{"uid": "${request.uid}", "password": "${request.password}"}` (logout'ta yalnızca `uid`), header'lar `${header.X-Clientip}` ve `${header.X-User-Agent}`, anonim, profil yok.
- **Test:** gömülü LDAP (UnboundID), Koordinatör için `FakeHttpServer`, Testcontainers Redis; yanıt gövdeleri birebir. Uçtan uca: login → token'la istek geçer → farklı `X-User-Agent` 301 alır → farklı `X-User-Agent`'la logout reddedilir → doğru header'larla logout → aynı istek 301 alır.
- **Yapıldı (2026-10-16):** Modül `broker-login` (README'de uçlar, ayarlar ve route tanımları; örnek `broker-runtime/broker-local.example.json`). Uygulanırken netleşenler:
  - **İmza:** `X-User-Context`'in biçimi core'a taşındı (`UserContextToken.sign/verify`); broker imzalar, giriş servisi doğrular. İmzasız ya da eski çağrıya 403 `{"response": false, "status": 403, "message": "..."}`. Koordinatör'ün iç ucu imzaya tabi değil, kendi gizli anahtarıyla.
  - **Düzeltme (PR #1 incelemesi), imza kuralı:** `UserContextFilter` ilk sürümde yalnızca ham `getRequestURI()` `/login` ya da `/logout` iken imza istiyordu; Spring MVC `;` parametrelerini atıp yüzde kodlamasını çözdüğü için `POST /login;x=1` ve `POST /%6Cogin` imzasız olarak handler'a ulaşıyordu. Artık `/actuator/` ve `/internal/` ile başlayanlar dışındaki her yol imza ister (yeni uçlar da). Yol, servlet container'ın eşlemede kullandığı çözülmüş ve normalize edilmiş yoldur (`getServletPath()` + `getPathInfo()`, path parametresiz): `/internal/../login` `/login` sayılır, istisnaya giremez. `login.user-context-secret` boşken kontrol kapalı; bu yalnızca `local` profilinde olabilir, başka profilde servis başlamaz (`IllegalStateException`). Testler: `LoginApiTest` (`/login;x=1`, `/%6Cogin`, `/logout;x=1`, `/;x=1/login`, `/internal/../login`, `/actuator/../login`, `/nope` imzasız 403; imzalı `/login`, imzasız `/actuator/health` ve Koordinatör'ün iç ucu çalışır), `UserContextSecretTest` (secret'sız servis `dev` ve varsayılan profilde başlamaz, `local`'de başlar).
  - **Koordinatör:** `KoordinatorClient` (giriş servisi) ve admin'de `KoordinatorProperties`: `koordinator.url`, `auth-header` (varsayılan `Authorization`), `auth-value` (boşsa header yok). Admin'in `HttpProjectCatalog` ve `HttpAuthorizationService`'i bu adrese gider; `broker.admin.catalog.url` ve `broker.admin.authorization.urls.<ENV>` doluysa onları kullanır. Yetki cevabı 200 ama `yetkiler` yoksa ya da dizi değilse Koordinatör hatası sayılır (giriş reddedilir, uyarı logu); boş dizi geçerli cevaptır (giriş başarılı, yetkisiz).
  - **Redis:** Kayıt `SET` + süre, ardından `EXISTS`; herhangi bir Redis hatası "yazılamadı" gövdesini verir. Logout'ta Redis hatası da reddetme gövdesidir.
  - **LDAP:** `LdapContextSource` havuzsuz, `com.sun.jndi.ldap.connect/read.timeout` ile; başarısız bind `info` loglanır (uid ve hata türü, şifre yok). `static` mod `local` dışında açılırsa servis başlamaz (`LoginConfiguration`).
  - **Düzeltmeler (PR #1 incelemesi), küçükler:** `BindLdapAuthenticator` şifreyi eski koddaki gibi `isBlank()` ile kontrol eder; ilk sürüm `isEmpty()` kullanıyordu ve yalnızca boşluktan oluşan şifreyle LDAP'a gidiyordu (AD bunu hatalı deneme sayar). `HttpKoordinatorClient.permissions` 200 gelip `yetkiler` yoksa ya da dizi değilse ilk sürümde boş liste dönüyordu (yetkisiz ama başarılı giriş); artık `Optional.empty()` ve uyarı logu, giriş reddedilir. Testler: `BindLdapAuthenticatorTest` (gömülü LDAP'ın bind sayacıyla: boşluklu şifreler dizine hiç gitmez), `HttpKoordinatorClientTest` (alan yok, dizi değil, JSON değil → boş; boş dizi ve karışık dizi geçerli), `LoginApiTest` (alan yoksa giriş reddi, boş diziyle yetkisiz giriş).
  - **Local profili:** sabit kullanıcılar `dev.ali`, `dev.ayse` (şifre = kullanıcı adı), sabit yetkiler (`koordinator.mode: static`), `login.koordinator-secret: local-koordinator-secret`. DEV Redis'te yalnızca yerel anahtarlara yazar: `local:user:session:{uid}` ve `broker:applications:local` (madde 5).
  - **Uçtan uca test** broker-runtime'da (`LoginEndToEndTest`): giriş servisi aynı JVM'de ikinci bir Spring uygulaması olarak (`local` profili, kendi yapılandırma klasörü `login-e2e/`, broker'ın `application.yml`'ı ona karışmaz). broker-runtime'ın test bağımlılığı `broker-login`.

### 5. Yerel çalıştırma

- `docker compose` (Oracle XE, Redis, Kafka; Elastic ve ClickHouse ayrı bir profilde) ve `docs/local-run.md`. Frontend geliştiricisi admin API'yi `local` profili ve tohum verisiyle tek komutla ayağa kaldırabilmeli; broker ve giriş servisi de aynı compose'la denenebilmeli.
- OpenAPI ve Swagger yalnızca `local` ve `dev` profillerinde açık; diğerlerinde `springdoc.api-docs.enabled=false`.
- **Kullanıcı kararı (2026-10-16), compose yerine:** Intranette Docker çalışmıyor; local'den DEV projelerine ve DB'lerine erişim var. `docker compose` yazılmadı; `local` profilleri DEV'e bağlanır.
- **Yapıldı (2026-10-16):** `docs/local-run.md`, `scripts/local-run.sh` ve `scripts/local-run.ps1` (`admin|broker|env-admin|login`, jar yoksa derler), `local.env.example` (`local.env` git'e girmez). Uygulanırken netleşenler:
  - **Portlar:** admin 8080, env-admin 8081 (actuator 9091), giriş servisi 8082, broker 8083 (admin'le çakışmasın diye; env-admin'in yerel test çağrısı da 8083'e).
  - **DEV'i kirletmeme:** Yerel broker `broker:loaded-local:` önekine yazar (DEV env-admin'in `broker:loaded:*` taraması görmez), Kafka'ya yazmaz. Yerel env-admin DEV Oracle'da ayrı bir runtime şemasına yazar ve sinyali `broker:routes-changed:local` kanalına yollar. Yerel admin ayrı bir merkezi şema kullanır. Yerel giriş servisi DEV Redis'e bağlanır ama oturumları `local:user:session:{uid}`'e, application listesini `broker:applications:local`'a yazar; DEV'in `user:session:*` ve `broker:applications` anahtarlarına dokunmaz. Yerel broker varsayılanda DEV'in `user:session:{uid}`'ini okur; giriş servisi yerelde geliştirilirken `BROKER_SESSION_KEY_PREFIX=local:` ile yerel kayıtları okur.
  - **Düzeltme (PR #1 incelemesi):** İlk sürümde yerel giriş servisi DEV'in ortak anahtarlarına yazıyordu: `ApplicationSync` `broker:applications`'ı sabit `["web","mobil"]` ile eziyordu, oturumlar DEV'in `user:session:{uid}`'ine gidiyordu (sabit kullanıcıların oturumu DEV'de geçerli oluyor, bind modunda gerçek kullanıcının DEV oturumu eziliyordu). `broker-login`'in `application-local.yml`'ı artık yukarıdaki yerel anahtarları kullanır. Testler: `LocalProfileTest` (`local` profilli servis yalnızca yerel anahtarlara yazar, DEV'in kayıtları aynı kalır), `LocalProfileSettingsTest` (yerel broker'ın anahtar deseni, değişkenli ve değişkensiz).
  - **Redis şifresi:** `BROKER_REDIS_PASSWORD` (broker, env-admin, giriş servisi).
  - **Tohum verisi:** admin'de `LocalSeed` (`local` profili, `broker.admin.local.seed`, varsayılan açık): boş merkezi şemaya `local-seed.json`'daki 3 proje, 5 subtask ve 1 profil, API'nin servisleri üzerinden (history'ler gerçek); ortamlara çağrı yapılmaz.
  - **Giriş servisi local'de** sabit kullanıcılar ve sabit yetkilerle (`koordinator.mode: static`, yalnızca `local`); `KoordinatorClient` arayüz oldu (`HttpKoordinatorClient`, `StaticKoordinatorClient`).
  - **Springdoc:** admin'de `springdoc.api-docs.enabled` ve `swagger-ui.enabled` varsayılan kapalı; `application-local.yml` ve yeni `application-dev.yml` açar. Diğer modüllerde springdoc yok.
  - **Docker'sız derleme:** Testcontainers kullanan test sınıfları `@Tag("docker")`; `mvn -B verify -Pno-docker` yalnızca birim testlerini çalıştırır (106 test, container yok). CI hepsini çalıştırır.
  - **Doğrulama:** Betiklerle dört uygulama, DEV'i taklit eden bir Oracle ve şifreli Redis'e karşı açıldı: admin'den `giris` route'ları DEV'e (yerel env-admin) taşındı, broker üzerinden login, eski gövdeler, başka `X-User-Agent`'la logout reddi, logout ve 301 denendi.

### 6. `${user.permission}` yer tutucusu

Kullanıcıda olan ilk route yetkisi: önce `permissions`, sonra `foreignPermissions`, tanımdaki sırayla. Oturum yoksa değeri yoktur, alan yazılmaz. Eski broker `sorgu` isteklerinde `perm`'i böyle seçiyordu (önce `SORGU_TEMEL`, sonra `SORGU_ILERI`); `sorgu` route'ları bu sırayla tanımlanınca aynı davranış elde edilir. Doğrulayıcı, önizleme, rehberin yer tutucu tablosu ve "Değişiklik kaydı" güncellenir.

- **Yapıldı (2026-10-16):** `RequestContext.permission` ve `RequestContext.firstPermission(routePermissions, userPermissions)` core'da; doğrulayıcı `${user.permission}`'ı kabul eder, `definition-rules` listeler. Broker'da route yetkileri artık sıralı (`Route.permissions` `List`; runtime DB'deki dizi zaten önce proje, sonra başka proje yetkilerini sırayla taşıyordu). Önizleme ve test çağrısında kullanıcının formdaki yetkilere sahip olduğu varsayılır: `Trial` ve `TestCallRequest` isteğe bağlı `permissions`/`foreignPermissions` (test çağrısında `permission`) taşır; rehber ve değişiklik kaydı güncellendi. Testler: core (sıra, yokken alanın yazılmaması), broker (`sorgu` örneği, anonim route), admin önizlemesi.

### 7. Alarm (2026-10-26 turuna taşındı)

Her şey bittikten sonra yapılacak. Eski akış: `maintask = sorgu` ve `reason` 5 karakterden uzunsa `alarmService.checkAlarm(params, client_ip, query_id)`; `isAlarmQuery` true ise HTTP 200 `{"response": false, "badprocess": true, "status": 500}`, değilse gövdeye `alarm_list` eklenir. Alarm logu Elastic'te `alarm` indeksine yazılıyor. Ayrıntılar `alarmService` koduyla gelecek.

## Eski broker'dan teyit edilenler

Kullanıcının paylaştığı eski kod ekran görüntülerinden (2026-10-12 ve 2026-10-16):

- **Controller:** `X-Token` `login`/`logout` değilse `broker.gelen.istekler` (tag `maintask`, `subtask`; yoksa "Bilinmeyen Proje"/"Bilinmeyen Endpoint") artar. `X-Token` yoksa `equals` çağrısı NPE ile 500 verir.
- **İstek hazırlığı (`task_check`):** `X-Token`, `X-Clientip` ve `X-User-Agent` header'ları okunur. Gövdedeki `uid` `X-uid` olur; `password` logda `xxxx`. Login ve logout dışında parametre dokümanları yazılır.
- **`do_process` sırası:** application kontrolü → `refresh_permissions` → `giris_public` → login → logout → token kontrolü → `sorgu` alarmı → proje servisleri. Yanıt `{"response": false}` ile başlar, hata yanıtları onun üstüne kurulur.
- **`token_check`:** Kayıt yoksa ya da `X-Token`, `X-User-Agent`, `X-Clientip` değerlerinden biri `equals` değilse geçersiz.
- **Yanıt logu:** `log_response` = Map gövdenin tamamı + `"data": ""`. Resource ise `info: "Fotoğraf başarıyla çekilmiştir."`, byte[] ise `info: "excel başarıyla oluşturuldu."`. Gövde null ya da log yazılamazsa 500 `{"result": false}`.
- **Elastic:** İndeks `"requests_" + new SimpleDateFormat("yyyy-MM").format(new Date())`, saat dilimi verilmemiş; `request_date` GMT.
- **RedisService:** `user:session:{uid}` (86400 sn, login'de), `user:layout:{sicil}` (dashboard yerleşimi, süresiz), `user:quick_link:{sicil}` (hızlı bağlantılar, süresiz). `expire_user(uid)` kayıt varsa siler; `expire_user_triggered_from_koordinator(uid, secret)` `koordinator.secret` eşitse aynısını yapar. Değerler ham `RedisTemplate` ile yazılıyor. Kayıt anonim bir `HashMap` alt sınıfı olduğu için JDK serileştirmesiyle yazılamaz; serializer JSON tabanlı olmalı (teyit bekleniyor).
- **Login, logout ve alarm yanıtları:** "Sıradaki iş" 3, 4 ve 7'deki gövdeler eski koddan birebir alındı.
- **`sorgu` yetkisi:** Kullanıcıda `SORGU_TEMEL` varsa `perm = "SORGU_TEMEL"`, yoksa `SORGU_ILERI` varsa o; ardından `sorguService.execute(params)`.
- **Bilinmeyen subtask ve yetkisiz kullanıcı:** Proje sınıfları özel exception atıyordu; ortak bir gövde yok, yeni ortak format kalır.
- **Application listesi:** Eski broker'da sabit kodlu; yeni düzende yetki projesinden gelir (projeler = application'lar).
- **Koordinatör:** Yetki projesinin adı; her ortamda ayrı çalışır (DEV, PREPROD, PROD). Projeleri ve yetkileri yönetir, `koordinator.secret` ile kullanıcı oturumu kapatabilir; giriş servisindeki yeni ucu (`POST /internal/v1/sessions/{uid}/logout`) ve gizli anahtarı Koordinatör ekibine kullanıcı iletecek. Gerçek uçlar (2026-10-21):
  - `GET /api/koordinator/projeler-ve-yetkileri` → `[{"projeId", "projeAdi", "yetkiList": [{"id", "yetki", "ozelYetki", "durum"}], "projeDurum"}]`. Projeleri ya da bir projenin yetkilerini ayrı veren uç yok; gerekirse Koordinatör ekibi ekleyebilir.
  - `GET /api/koordinator/personel-yetki/aktif-yetkiler/{sicil}` → bir map: `{"<anahtar>": [{"id", "projeId", "projeIsim", "yetkiIsim", "yetkiAciklama", "baslangicTarihi", "bitisTarihi", "askidaMi", "yetkiTalepDurum", "yetkiId"}]}` (Swagger anahtarları `additionalProp1…` diye gösteriyor).
  - Giriş servisi ve admin Koordinatör'e ayardaki ortam adresiyle doğrudan gider, broker route'u üzerinden değil. Koordinatör ayrıca frontend'ler için broker'a sıradan bir proje olarak eklenir.
- **`PublicApisService`:** Broker'daki bütün proje servislerine referans tutuyor; `refresh_permissions` ve `giris_public` subtask'leri bu servislere sabit yazılmış. Yeni düzende özel bir karşılığı yok.
- **Saat dilimi:** Eski broker sunucusu TSİ'de çalışıyor (kullanıcı teyidi).
- **`RedisTemplate<String, Object>` bean'i:** `setKeySerializer(new StringRedisSerializer())`, `setValueSerializer(new JacksonJsonRedisSerializer<>(Object.class))`, hash anahtar ve değerleri de `JacksonJsonRedisSerializer`, `setEnableTransactionSupport(true)`. Değerler tip bilgisi olmayan JSON.
- **LDAP (`LdapPreprod.check_user`):** `uid`, şifre boş değilse `ldapTemplate.getContextSource().getContext(uid + "@test.com.tr", password)`; başarılıysa true, exception olursa false. Sınıf `@ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")` ile yalnızca preprod'da yükleniyor. PROD sınıfı bununla aynı, yalnızca profil koşulu `prod`; alan adını kullanıcı ayarda verir (fotoğraftaki `@test.com.tr` değiştirilmişti). DEV'de giriş belirlenmiş bir magic key ile yapılıyor: herkes herkesin hesabına girebiliyor (2026-10-21).
- **Alarm (kullanıcı, 2026-10-21):** Şu an çalışmıyor. Akış: isteğin bütün parametre değerleri liste yapılıp başka bir projeye (alarm servisi) gönderilir; yanıtta alarm olan değerlerin listesi döner; alarm varsa Elastic'teki `alarm` indeksine yazılır ve servise gitmeden 500 döner. İndeks kullanılmış; istek doğrudan basıldığı için mapping çok geniş.
- **`alarm_log`:** İndeks `alarm`, `_id` = `query_id`, doküman = çağıranın verdiği parametre map'i (`elasticsearchClient.index(...)`; sonuç `created` ise true).
- **Public subtask'ler (2026-10-26):** `harici_uygulamalar` Koordinatör'ün `/api/koordinator/projeler-external` listesini döner. `refresh_permissions` → `redisService.expire_user_tirggered_from_koordinator(params.uid, params.koordinator_secret_key)`: secret `koordinatorSecret`'e eşit değilse "logout işlemi sadece koordinator projesi tarafından yapılabilir." hatası; kayıt varsa `user:session:{uid}` silinir ve true, yoksa false.
- **Redis önekleri:** `user:layout:`, `user:session:`, `user:quick_link:`.
- **Koordinatör (2026-10-26):** `projeler-ve-yetkileri` her projede `is_yapi` taşıyor; Yapi yalnızca bunları alır. `application` değeri `projeAdi`. `uid` sicildir.
- **Java sürümü:** Eski kod `catch (Exception _)` kullanıyor (Java 22+) ve spring-data-redis 4.0.3 ile çalışıyor (Boot 4.0); JSON aynı Jackson 3 ile yazılıyor.

## Bilgi bekleyenler

Kullanıcıdan istendi; gelince ilgili madde güncellenir:

1. Koordinatör: servislerimizin çağrısında kimlik bilgisi gerekiyor mu (şimdilik gerekmediği varsayıldı).
2. Paket kökü için şirket alan adı (şimdilik `com.acme.yapi`).

## Verilmiş kararlar

Tasarım dokümanındakilere ek olarak netleşenler:

- **Platform:** Java 25, Spring Boot 4.1.1, Maven çok modüllü proje. Paket kökü `com.acme.yapi` (şirket adına göre toplu değiştirilebilir).
- **JSON:** Jackson 3 (`tools.jackson.*`; anotasyonlar `com.fasterxml.jackson.annotation.*`). Ağaç API'si yalnızca `yapi-core/.../json/Json.java` içinden kullanılır; Jackson metot adı hatası çıkarsa tek yerde düzeltilir.
- **Veri erişimi:** JPA yok; `JdbcClient` ve SQL, şemalar Flyway ile. Veritabanı Oracle 19c (merkezi ve runtime); şema = şema sahibi kullanıcı (`YAPI_ADMIN`, `YAPI_RUNTIME`), uygulamalar kendi kullanıcılarıyla `CURRENT_SCHEMA` (Hikari `schema`) üzerinden şemasız SQL yazar. Testler Oracle XE 21 ile; 19c'de olmayan özellik kullanılmaz.
- **Broker'ın ortak hata formatı:** `{"result": false, "status": 403, "outcome": "DENIED", "message": "...", "requestId": "...", "errors": []}`. Durumlar: `X-Token` yok 400 `INVALID_REQUEST`, yetki yok 403 `DENIED`, route yok 404 `UNKNOWN_TASK`, gövde `fields`'a uymuyor 400 `INVALID_REQUEST` (alan bazında `errors`), oturum okunamadı 503 `TECHNICAL_ERROR`. Application ve oturum eski broker'ın HTTP 200'lü gövdeleriyle döner (`yapi.compat.legacy-responses`, varsayılan açık; kapalıyken 403 ve 401 ortak formatta); login ve logout giriş servisinin gövdeleri.
- **Log yazılamazsa:** `500` ve `{"result": false}`, outcome `LOG_UNAVAILABLE`; olaylar spool'a yazılır. Profil uygulanmaz.
- **Logdaki oturum bilgisi:** `token`, `client_ip`, `user_agent` ve login yanıtındaki `X-Token` eskisi gibi olduğu gibi loglanır. Kullanıcı kararı: istek olduğu gibi loglanmalı; broker'ın Elasticsearch'ü ayrı, erişim çok sınırlı ve oturumlar kayıt altında.
- **Profilsiz yanıt:** Gövde olduğu gibi döner. RESPONSE olayındaki `client_response` bu durumda yalnızca `{"status": <kod>}` olur, böylece loglanmamış veri `data_response`'a sızmaz; `logResponseBody` açıksa "Sıradaki iş" 2'deki kural geçerlidir.
- **Dosya yanıtı** (`BINARY` route ya da servis `Content-Disposition` döndürdü): `client_response` = `{"info": <route'un binaryInfo'su ya da "Dosya yanıtı: <dosya adı>">}`; gövde loglanmaz, onaydan sonra stream edilir.
- **Timeout ve bağlantı hatası, profil yoksa:** ortak formatta 504 `TIMEOUT` ve 502 `TECHNICAL_ERROR`.
- **Oturum:** Eski broker'dan teyit edildi: kayıt `user:session:{uid}`, geçerlilik token, `X-Clientip` ve `X-User-Agent` eşleşmesiyle; login ve logout ayrı bir giriş servisinde. Oturum işlemleri parametre logu ve `yapi.gelen.istekler` sayacı dışında kalır. Broker tarafı ("Sıradaki iş" 3) ve giriş servisi (`yapi-login`, 4) uygulandı.
- **Anonim route + oturum:** Token geçerliyse kullanıcı yine çözülür (`X-uid`, `perm_list`); geçersizse anonim route yine çalışır.
- **Servise giden header'lar:** `X-uid` (oturumdaki kullanıcı), `X-Request-Id`, `traceparent`; gizli anahtar ayarlıysa HMAC imzalı `X-User-Context`.
- **Test çağrıları** Kafka'ya log yazmaz; merkezi admin bunları kendisi audit eder.
- **Eski sayaç:** Eski broker'ın `broker.gelen.istekler`'i Yapi'de `yapi.gelen.istekler` (2026-10-26 kararı). Tag anahtarları ayarla verilir; eski controller'la teyit edildi (`maintask`, `subtask`). Bilinmeyenler "Bilinmeyen Proje" / "Bilinmeyen Endpoint".
- **Elastic aylık indeks:** Ay, request ID'deki (UUIDv7) zamandan, ayarlanabilir saat diliminde hesaplanır. Eski kod sunucunun saat dilimini (TSİ) kullanıyor; varsayılan `Europe/Istanbul`. Parametre dokümanı `_id`'si `UUID.nameUUIDFromBytes(requestId|alan|sıra)`.
- **Admin kimliği:** OAuth2 resource server (JWT); rollerin geldiği claim ve grup→rol eşlemesi ayardan. `local` profilinde HTTP Basic: `viewer`, `editor`, `approver`, `lead` (şifre = kullanıcı adı; `lead` üç role de sahip).
- **Roller:** `VIEWER`, `ROUTE_EDITOR`, `APPROVER`. `ROUTE_EDITOR` DEV'e doğrudan uygular (talep onaysız uygulanır), PREPROD ve PROD için talep açar.
- **Taşıma kuralları:** Onay gerekir = hedef ortam `requires_approval` **ya da** versiyon anonim erişimi açıyorsa (hangi ortam olursa olsun). Onay gerekenlerde gerekçe zorunlu. `ROLLBACK` yalnızca hedef ortama daha önce `APPLIED` olmuş bir versiyona; `DISABLE`/`ENABLE` hedefteki mevcut versiyonla. Profil önce, route sonra uygulanır. Ön kontroller ve adları `docs/frontend-guide.md`'de.
- **Ortam ayarı:** `PUT /projects/{id}/environments/{env}` ortama hemen uygulanır; ortama ulaşılamazsa merkezde de değişmez (502 `ENV_UNREACHABLE`). Onay isteyen ortamda yalnızca `APPROVER` değiştirir (açık nokta).
- **Arşiv:** Yalnızca hiçbir ortamda `ACTIVE` olmayan subtask arşivlenir (409 `ROUTE_DEPLOYED`).
- **Dağıtım (kullanıcı kararı, 2026-10-16):** DevOps yapar; Kubernetes ya da VM kararı onlarda. Her ortamda broker, giriş servisi, env-admin ve log sink çalışır, merkezde admin. Log sink o ortamın Kafka'sını okur ve o ortamın Elastic'ine yazar (eski broker'ın Elastic'i ortam başına; `requests_` indeksinde ortam yok); ClickHouse ortak da olabilir, satırlar `environment` taşır.
- **Alarm (kullanıcı kararı, 2026-10-21):** İsteğe bağlı; proje ve subtask düzeyinde açılır, hangi subtask'lerin kontrol edileceği kodda yazılmaz. Gövdedeki bütün değerler gönderilir. Alarm servisi yanıt vermezse istek reddedilir ve Kafka'ya loglanır.
- **DEV'de giriş:** Eski DEV sınıfı gibi magic key; giriş servisinde yalnızca `dev` profilinde açılan bir mod olacak, anahtar ayardan.
- **Ad (2026-10-26):** Projenin adı Yapi; kod ve ayar adlarında broker yerine yapi. `POST /broker` ve `broker.gelen.istekler` de `yapi` olur; eski Elastic indeksleri, `user:*` anahtarları ve eski gövdeler aynı kalır ("Sıradaki iş" 1).
- **Koordinatör'de aktiflik:** Uçlar yalnızca aktif proje ve yetkileri döner; `askidaMi` true olan yetki aktif değildir.
- **Ortamlar:** DEV, PREPROD, PROD.
- **Koordinatör:** Yalnızca `is_yapi` projeler; application = `projeAdi`; uid = sicil. Yetkiler yalnızca Koordinatör'den açılır. Yapi'nin yetkileri `YAPI_DEVELOPER`, `YAPI_ADMIN` (adlar değişebilir, ayarda).
- **Admin'e giriş:** Şirketin giriş uygulamasının oturumu ve Yapi yetkileri; SSO yok.
- **Geçiş:** Önce tek bir proje pilot, sonra kalanlar. Eski subtask'leri (`harici_uygulamalar` gibi Koordinatör'e gidenler dahil) kullanıcı admin ekranından tanımlar.

## Modüller ve durum

Bu bölüm kodun güncel halini anlatır; "Sıradaki iş"teki maddeler bittikçe güncellenir. "Sıradaki iş"in 1–6. maddeleri bitti.

### yapi-core (yeşil)

| Paket | İçerik |
| --- | --- |
| `json` | `Json`: Jackson 3 ağaç API'sinin tek kullanıldığı yer |
| `naming` | `Names`: `Locale.ROOT` ile harf duyarsız anahtar, maintask/subtask/önek/son ek desenleri, önek çakışması |
| `route` | Tanım modeli (`RouteDefinition`, `RequestSpec`, `FieldSpec`, `BodySpec`...), `RouteDefinitionParser` (yapısal hatalar), `RouteValidator` (kurallar, form yollarıyla hatalar), `PathTemplate` |
| `template` | `${...}` motoru: `TemplateString`, `JsonTemplate`; tek yer tutucu tipini korur, değeri olmayan alan yazılmaz |
| `request` | `RequestBodyValidator` (zorunlu/tip/varsayılan), `BackendRequestBuilder` (path, query, header, NONE/PASS_THROUGH/TEMPLATE/MULTIPART body), `RequestContext` |
| `response` | `Outcome`, `ResponseProfile` (profil doğrulama ve zarflama; 5xx gövdesi şablona verilmez), `ClientResponse` |
| `logging` | `LogEvent` + `EventCodec` (Kafka'daki snake_case şema), `Masker` (her derinlikte, harf duyarsız; kırpma) |
| `legacy` | `LegacyDocuments`: olaylardan eski Elastic ana ve parametre dokümanları (`ElasticOperation`) |
| `ids` | UUIDv7, trace/span ID, parametre dokümanı ID'si |
| `spec` | `OpenApiSpec` ($ref çözümü, fingerprint), `SpecProposal` (operasyondan tanım önerisi) |
| `sample` | `SampleParser` (JSON ya da bash/cmd "Copy as cURL"; kimlik header'larını atar), `SampleTemplate` (alan çıkarımı ve operasyona ad eşleme) |
| `diff` | `JsonDiff`: alan yolu bazında fark |
| `rules` | `DefinitionRules`: `GET /meta/definition-rules` yanıtı |

Testleri `yapi-core/src/test` altında; tasarım dokümanındaki örnekleri (add_order_note, log-project profili, eski Elastic dokümanları) doğruluyorlar.

Modüller arası sözleşmeler de burada:

| Paket | İçerik |
| --- | --- |
| `runtime` | `RuntimeSignals`: `yapi:routes-changed` kanalı, `yapi:loaded:{instanceId}` anahtarı ve değeri `{"version": 43, "loadedAt": "..."}` (60 sn TTL) |
| `testcall` | `TestCallRequest` / `TestCallResult` / `TestInterpretation`: merkez → ortam Admin API → broker'ın iç endpoint'i (`POST /internal/v1/test`, `X-Yapi-Test-Secret`) |

Test-jar (`com.acme.yapi.core.testing`): `CoreFixtures` (add_order_note, log-project, openapi-sample), `FakeHttpServer` (JDK `HttpServer` ile sahte servis), `JsonHttp` (test HTTP istemcisi) ve `TestCertificates` (keytool ile mTLS sertifikaları); diğer modüllerin testleri kullanır.

### yapi-runtime (yeşil)

Paket `com.acme.yapi.runtime`. Tek endpoint `POST /yapi`; akış `web/YapiHandler`'da, tasarımdaki sırayla:

1. `requestId` UUIDv7, `queryId` rastgele UUID; trace gelen `traceparent`'tan (geçersizse yeni), servise yeni span ID ile gider.
2. Gövde JSON nesnesi değilse 400; `X-Token` yoksa 400 (`INVALID_REQUEST`). Gövde üst sınırı `yapi.forward.max-request-bytes`. `X-Token` login/logout değilse `yapi.gelen.istekler` artar.
3. `application` izinli değilse HTTP 200 `{"response": false}` (`ApplicationList`: `yapi.applications.mode` redis/static/off, Redis'te `yapi:applications`, 30 sn'de bir; liste okunmadan readiness hazır değil).
4. Gövdedeki maintask `yapi.session.public-maintasks`'ta değilse ve `X-Token` login/logout ise `yapi.session.routes`'taki route (`giris/login`, `giris/logout`); yoksa ya da anonim değilse 503 ve hata logu. Oturum okunmaz.
5. `RouteCache.find` (bellek, `Names.key`). Oturum: gövdedeki `uid` ile `user:session:{uid}`, tek Redis GET (`SessionStore`, `SessionRecordCodec`); geçerli = token, `X-Clientip`, `X-User-Agent` birebir aynı. Route yoksa geçersiz oturum 301 gövdesi, geçerliyse 404 `UNKNOWN_TASK`. Anonim olmayan route'ta geçersiz oturum HTTP 200 `{"response": false, "status": 301, "reason": "check X-Token"}`, yetki yoksa 403 (any-of), Redis okunamazsa 503 `TECHNICAL_ERROR`; anonim route oturum geçersizse ya da okunamazsa kullanıcısız devam eder. Eski gövdeler `client_response`'a `"data": ""` eklenerek yazılır.
5. REQUEST olayı; onay yoksa servis çağrılmaz, 500 `{"result": false}`, olaylar spool'a.
6. `RequestBodyValidator` + `BackendRequestBuilder`; servise `X-uid`, `X-Request-Id`, `traceparent`, ayarlıysa `X-User-Context` (`v1.<payload>.<HMAC-SHA256>`, payload `{userId, requestId, issuedAt}`).
7. JDK `HttpClient` (HTTP/1.1, proxy yok, redirect izlenmez). Retry yalnızca GET'te 502/503/504 ve bağlantı hatasında; timeout tekrarlanmaz (route'un bütçesini zaten harcamıştır). Gövde okuma da route timeout'una tabidir.
8. Dosya yanıtı (`BINARY` ya da `Content-Disposition`): RESPONSE olayı onaylanınca stream; `client_response` `{"info": ...}`, `body_size` `Content-Length`'ten. Değilse gövde okunur, outcome gerçek status'tan; profil varsa zarflanır, yoksa gövde olduğu gibi döner. `client_response`: `logResponseBody` kapalıyken profilde `data` boş string, profilsizde `{"status": <kod>}`; açıkken maskeli zarf ya da gövde, `data` JSON metni, `maxBodyBytes`'ta kırpılır (`EventFactory`).
9. Her yanıtta `X-Request-Id`; hata yollarında REQUEST ve RESPONSE birlikte gönderilip ikisi de beklenir.

- **`status_code`:** Servis yanıt verdiyse servisin status'u; vermediyse (broker hataları, timeout, bağlantı hatası) frontend'e dönen status. ClickHouse kolonu böylece boş kalmaz.
- **Log isteği gövdesi:** `logRequestBody=false` ise REQUEST olayında yalnızca zarf alanları yazılır (eski ana doküman zarfı yine taşır).
- **RouteCache:** `RouteRepository.loadSnapshot()` tek bir `SET TRANSACTION READ ONLY` transaction'ında okur (Oracle'da REPEATABLE READ yok); bozuk route/profil atlanır (`yapi.routes.skipped`). `yapi:routes-changed` aboneliği, `yapi.cache.verify-interval` (15 sn) kontrolü, her kontrolde `yapi:loaded:{instanceId}` (60 sn TTL). İlk yükleme ve Kafka metadata ısınması `ApplicationRunner` (ısınma önce).
- **Spool:** istek başına bir `.jsonl` dosyası (atomik taşıma), `yapi.spool.resend-interval` ile eskiden yeniye gönderilir; ilk hatada durur. Gauge `yapi.log.spool.files`.
- **Metrikler:** `yapi.requests` (maintask, subtask, outcome; bilinmeyen subtask `UNKNOWN`), `yapi.gelen.istekler` (tag anahtarları ve "Bilinmeyen ..." değerleri `yapi.legacy-counter.*`), `yapi.log.ack`, `yapi.log.unavailable`, `yapi.routes.config.version`, `yapi.applications.refresh.failed`.
- **İç test endpoint'i:** `POST /internal/v1/test`, yalnızca `yapi.test.enabled=true` iken oluşur; `X-Yapi-Test-Secret` sabit zamanlı karşılaştırılır. Kafka'ya yazmaz.
- **`local` profili:** `LocalOverrides` (`yapi-local.json`, `baseUrls` + `routes`, dosya değişince 2 sn içinde uygulanır), `log-sink: console`, `yapi.local.skip-permission-check-for-overrides`.
- **Flyway kapalı** (`spring.flyway.enabled=false`): broker şemaya hiç yazmaz.
- **Veritabanı:** `yapi_runtime_reader` kullanıcısıyla, `spring.datasource.hikari.schema` (varsayılan `YAPI_RUNTIME`) ile şemasız okur; Hikari havuzu `read-only`.
- **Testler:** env-admin'in migration'ı ve `RuntimeStore`'u ile runtime DB, Testcontainers Oracle XE (broker okuyucu kullanıcıyla bağlanır) ve Redis, EmbeddedKafka, `FakeHttpServer`. Testlerin istekleri oturum kaydının uid'ini, `application: web`'i ve login'deki istemci header'larını taşır (`YapiFlowTest.broker`). `CommonFormatTest` Kafka'sız (`log-sink: console`). Log kapısı topic'i olmayan bir Kafka ile denenir (servis çağrılmaz, 500, spool; topic açılınca spool gönderilir).
- **Tracing:** span adı ve attribute'lar OTel Java agent'ına bırakıldı; broker `traceparent`'ı taşır. Span'ı `maintask/subtask` diye adlandırmak için agent'ın API'si eklenmeli (açık).

### yapi-env-admin (yeşil)

Paket `com.acme.yapi.envadmin`. Uçlar `/admin/v1` altında; hatalar merkezle aynı problem formatında (`code`, `detail`, `errors`).

| Uç | Davranış |
| --- | --- |
| `PUT /routes/{maintask}/{subtask}` | Tasarımdaki tek ifadeyle uygular. Yanıt `{"applied", "configVersion", "alreadyApplied", "signalled"}`. Aynı `promotionId` tekrar gelirse `alreadyApplied: true`; daha yeni talep varsa 409 `STALE_PROMOTION` (`currentPromotionId`); proje ya da profil ortamda yoksa 409 `PROJECT_NOT_FOUND` / `PROFILE_NOT_FOUND`. Tanım `RouteValidator` ile (header okunabilirliği hariç) doğrulanır |
| `PUT /response-profiles/{name}` | Aynı desen; `ResponseProfile.parse` ile doğrulanır |
| `POST /routes/{m}/{s}/disable` | Kill switch, `promotionId` ile; route yoksa 404 `ROUTE_NOT_FOUND`. `ENABLE` ayrı uç değil: hedefteki versiyon `PUT` ile yeniden uygulanır |
| `PUT /projects/{maintask}` | `{"baseUrl", "enabled", "actor"}`; değişiklik varsa config versiyonu artar ve sinyal gider (broker'lar base URL'i snapshot'la okur) |
| `GET /routes` | Projeler, profiller, tüm route'lar (DISABLED dahil) ve config versiyonu |
| `GET /cache-status` | `yapi:loaded:*` (SCAN) → instance başına `loadedVersion`, `loadedAt`, `upToDate` |
| `GET /projects/{maintask}/spec?path=` | Base URL runtime DB'den; yol doğrulanır (`..`, `//`, şema, ters bölü reddedilir). Hata: 502 `UPSTREAM_ERROR` (`upstreamStatus`) |
| `POST /probe` | `{"maintask", "httpMethod", "pathTemplate", "pathVariables"}` → `{"result", "status", "allow", "url", "message"}`; değeri verilmeyen path değişkeni `1` olur; redirect izlenmez; ulaşılamazsa `INCONCLUSIVE`, `status: null` |
| `POST /test` | `TestCallRequest`'e kayıtlı base URL'i koyup broker'ın iç endpoint'ine iletir; kapalıysa 403 `TEST_NOT_ALLOWED`, broker'a ulaşılamazsa 502 `YAPI_UNREACHABLE` |

- **Şema:** `db/migration/yapi_runtime/V1__runtime_schema.sql`, Oracle 19c (tasarımdaki modelin Oracle karşılığı; kullanıcı yoksa GRANT atlanır, ORA-01917). Flyway şema sahibiyle (`YAPI_RUNTIME_DB_OWNER_*`, `create-schemas: false`), uygulama `yapi_runtime_writer` ile `YAPI_RUNTIME_DB_SCHEMA` (varsayılan `YAPI_RUNTIME`) üzerinden çalışır. Yazmalar `MERGE` + versiyon artışı, tek transaction.
- **mTLS:** `server.ssl.bundle=env-admin`, `client-auth=need`; bundle `mtls` profilinde (`application-mtls.yml`). Profil açılmazsa uygulama başlamaz. `yapi.env-admin.allowed-client-subjects` doluysa sertifika konusu da kontrol edilir (403 `CLIENT_NOT_ALLOWED`). Actuator ayrı portta (9090), TLS'siz.
- **Audit:** `yapi.audit` logger'ı; `operation`, `actor` (gövdeden ya da `X-Actor`), `promotion_id`, `client` alanları.
- **Paketleme:** çalıştırılabilir jar `-exec` ekiyle; düz jar ve test-jar (`RuntimeContainers`: Oracle XE + Redis, okuyucu ve yazıcı kullanıcılar, şema sahibiyle temizlik) yapi-runtime testlerinde kullanılır.
- **Testler:** Testcontainers Oracle XE ve Redis, `FakeHttpServer`; mTLS keytool ile üretilen sertifikalarla uçtan uca denenir. 32 KB'tan büyük tanım, eşzamanlı ilk yazma ve ayrılmış kelime kontrolü dahil.

### yapi-admin (yeşil)

Paket `com.acme.yapi.admin`. Sözleşme `docs/frontend-guide.md`; uygulama sırasında netleşen gövde ve yanıtlar oradaki "Gövde ve yanıt ayrıntıları" tablosunda.

| Paket | İçerik |
| --- | --- |
| `security` | `!local`: OAuth2 resource server (JWT); roller `yapi.admin.security.roles-claim` claim'inden, rol adı değilse `group-roles` ile eşlenir; kullanıcı adı `preferred_username` (yoksa `sub`). `local`: HTTP Basic, `viewer`/`editor`/`approver`/`lead`. Rol kontrolleri serviste (`User.require`); rolü olmayan kullanıcı her uçta 403. 401/403 problem formatında |
| `environment` | `EnvironmentRepository` (sıra, onay, test ayarı DB'den), `EnvironmentClient` (Admin API; `ssl-bundle` ile mTLS, redirect yok). Bağlanamama `UnreachableException` (hiçbir şey değişmedi), istek gitti ama yanıt yok `NoAnswerException` (uygulanmış olabilir) |
| `project`, `catalog`, `authz` | Proje kaydı (önek çakışması `PREFIX_OVERLAP`, harf duyarsız `DUPLICATE`), ortam ayarı önce ortama, sonra merkeze. Katalog ve yetkiler `mode: http` (her ortamın Koordinatör'ü, `koordinator/KoordinatorProjects`, ortam başına önbellek) ya da `memory`; yetkiler salt okunur |
| `route`, `profile` | Güncel satır + aynı transaction'da history (snapshot Java'da kurulur, snake_case anahtarlar), iyimser kilit (`VERSION_CONFLICT` + `currentVersion`), geri alma, arşiv (`ROUTE_DEPLOYED`), karşılaştırma, doğrulama, önizleme (`BackendRequestBuilder`) |
| `promotion` | Ön kontroller, onay kuralı (hedef `requires_approval` ya da versiyon anonim erişimi açıyorsa), dört göz (`FOUR_EYES` + `ck_four_eyes`), uygulama: yoklama → Admin API (`promotionId` = talep ID'si) → `route_deployment`/`response_profile_deployment`. Yanıt kaybolursa talep `APPLYING`'de kalır, `RETRY` aynı ID ile tamamlar |
| `spec` | Spec karşılaştırma (method + path, değişken adları yok sayılır; `CHANGED` saklanan `spec_fingerprint`'ten), istekten şablon, test (yalnızca `test_calls_allowed`; `yapi.audit` logu), yoklama |
| `jobs` | `DriftCheck` (gece; ortamın listesiyle karşılaştırma + her ACTIVE route'a yoklama, `yapi.admin.drift` sayacı, `last_probe_status`), `CatalogSync` (ad yenileme, servisten kalkan proje `MISSING`). Birden çok instance'ta aynı anda çalışmaları zararsız (idempotent) |

- **Şema:** `db/migration/yapi_admin/V1__central_schema.sql`, tasarımdaki modelin Oracle 19c karşılığı (yukarıda "Sıradaki iş" 1'deki kararlar: `route_permission`, tablo başına PL/SQL trigger'lar, `CASE`'li açık talep index'leri, `change_note`; kullanıcı yoksa GRANT atlanır). `V2__environments.sql` ortamları `dev/preprod/prod_admin_api_url` placeholder'larıyla `MERGE` ile seed eder. Uygulama `yapi_admin_app` ile `YAPI_ADMIN_DB_SCHEMA` (varsayılan `YAPI_ADMIN`) üzerinden şemasız çalışır.
- **Ayar:** SSO issuer'ı `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` ile verilir (boş varsayılan yok; boş issuer her token'ı reddeder). mTLS istemci sertifikası `mtls` profilinde (`application-mtls.yml`, bundle `env-client`).
- **Testler:** Testcontainers Oracle XE (`CentralDatabase`: uygulama `yapi_admin_app` ile, temizlik şema sahibiyle; history trigger'ları temizlik sırasında kapatılır), `local` profili ve bellek içi `FakeEnvironments` (üç ortamın Admin API'si; promotion ID idempotency'si, eski talebin reddi, proje/profil yokluğu, sessiz kalma). SSO testi gerçek imzalı JWT ile; ortam istemcisinin mTLS'i istemci sertifikası isteyen bir `HttpsServer`'a karşı.

### yapi-login (yeşil)

Paket `com.acme.yapi.login`; çalıştırılabilir jar `-exec` ekiyle. Ayrıntılar `yapi-login/README.md`'de.

| Paket | İçerik |
| --- | --- |
| `web` | `LoginController` (`/login`, `/logout`, `/internal/v1/sessions/{uid}/logout`), `LegacyBodies` (eski gövdeler birebir), `UserContextFilter` |
| `session` | `LoginService` (LDAP → Koordinatör → kayıt), `SessionStore` (core'daki `SessionRecordCodec` ile), `ApplicationSync` (5 dk'da bir `yapi:applications`) |
| `ldap` | `BindLdapAuthenticator` (Spring LDAP doğrudan bind), `StaticLdapAuthenticator` (yalnızca `local`) |
| `koordinator` | `KoordinatorClient`: kullanıcının aktif yetkileri (`aktif-yetkiler/{uid}`) ve application listesi (`projeler-ve-yetkileri`, `is_yapi`) |

- **Testler:** `LoginApiTest` (UnboundID gömülü LDAP, Testcontainers Redis, sahte Koordinatör; gövdelerin anahtar sırası ve tipleri, kayıt biçimi ve süresi, şifrenin loglarda olmaması, logout matrisi, Koordinatör çıkışı, imza, application senkronu), `LoginUnitTest` (yazılamayan kayıt), `LoginConfigurationTest` (static mod yalnızca local), `LocalProfileTest` (`local` profili DEV'in ortak anahtarlarına yazmaz), `UserContextSecretTest` (imza anahtarı `local` dışında zorunlu), `BindLdapAuthenticatorTest` (boşluklu şifre dizine gitmez), `HttpKoordinatorClientTest` (yetki cevabının biçimi). Redis container'ı modülde tek (`support/TestRedis`); her test sınıfı ayrı Redis veritabanı kullanır. Uçtan uca senaryo yapi-runtime'da.

### yapi-log-sink (yeşil)

Paket `com.acme.yapi.sink`. İki `@KafkaListener` (toplu), her biri kendi consumer group'unda; `yapi.sink.elastic.enabled` ve `yapi.sink.clickhouse.enabled` ile ayrı ayrı açılır (aynı imaj iki deployment olarak da çalışabilir).

- **Elastic (`yapi-logs-elastic`):** `LegacyDocuments` → tek `_bulk` NDJSON; indeks ayı request ID'nin zamanından, varsayılan `Europe/Istanbul`; ana doküman `update` + `doc_as_upsert` + `retry_on_conflict`, parametre dokümanları `index`. Kimlik `ApiKey` ya da basic. Öğe hatası 429/5xx → batch yeniden denenir; diğer 4xx → o olay DLQ'ya.
- **ClickHouse (`yapi-logs-clickhouse`):** `INSERT INTO yapi_logs.yapi_log FORMAT JSONEachRow`, `date_time_input_format=best_effort`; satırda `null` yok (varsayılanlar), `token`/`perm_list`/`client_response`/`query_id` yazılmaz. 400 → satır satır tekrar, kabul edilmeyen satır DLQ'ya; 401/403/404/5xx/bağlantı → batch yeniden denenir.
- **DLQ:** `yapi.logs.v1.dlq`, orijinal key/değer ve `yapi.dlq.sink`, `yapi.dlq.reason`, `yapi.dlq.origin` header'ları; DLQ onayı gelmeden offset commit edilmez.
- **Offset:** `ack-mode: batch`; hata durumunda `DefaultErrorHandler` sınırsız sabit aralıkla (`yapi.sink.retry-interval`) aynı batch'i dener, kayıt atlanmaz. Batch: `max-poll-records` 10.000, `fetch-max-wait` 1 sn.
- **Kurulum dosyaları:** `clickhouse/yapi_logs.sql` (tasarımdaki DDL) ve `elastic/yapi-fields.component-template.json` (mevcut `requests_*` şablonunun `composed_of` listesine eklenir). Sink tablo ya da şablon oluşturmaz.
- **Testler:** EmbeddedKafka; Elastic ve ClickHouse HTTP sahteleri (`update`/`doc_as_upsert` birleştirmesini uygular). REQUEST/RESPONSE sırası, bir hedef düşükken diğerinin ilerlemesi ve düşük hedefin offset'inin commit edilmemesi, bozuk mesajın DLQ'ya gidip partition'ı durdurmaması, hedefin reddettiği olayların DLQ'ya gitmesi denenir. `RealTargetsTest` gerçek ClickHouse 24.8 ve Elasticsearch 8.15 ile: tasarımdaki DDL çalışır ve satırları kabul eder (metrik view'ı, `FINAL` tekilleştirmesi), bileşen şablonu `requests_*` şablonuna eklenince `yapi.*` tipleri doğru, eşleme çakışması 400 ile DLQ'ya gider.
- **Bilinen sınır:** Frontend gövdesinde üst seviyede `yapi` adlı bir alan varsa eski ana dokümanda `yapi` nesnesi onu ezer (tasarım böyle bir alan olmadığını varsayıyor). Elastic'teki son ayların alan adlarıyla teyit edilmeli.

## Spring Boot 4 notları

Derlemede doğrulandı (Boot 4.1.1, Spring Framework 7.0, Jackson 3.1, Kafka 4.2, Testcontainers 2.0).

- Starter'lar: `spring-boot-starter-webmvc`, `-jdbc`, `-flyway` (+ `flyway-database-oracle`), `-data-redis`, `-kafka`, `-actuator`, `-security`, `-security-oauth2-resource-server`; sürücü `ojdbc17` (Boot'un yönettiği 23.x, 19c'yi destekler). Test: `spring-boot-starter-test`, `spring-kafka-test`, `testcontainers-oracle-xe` (sınıfı hâlâ `org.testcontainers.containers.OracleContainer`), `testcontainers-junit-jupiter`; `@LocalServerPort` `org.springframework.boot.test.web.server` paketinde.
- Oracle ile Spring: `JdbcClient.update(keyHolder, "id")` identity kolonunu döner (`Number`); 4000 karakterden uzun metin `SqlParameterValue(Types.CLOB, ...)` ile bağlanır; `BadSqlGrammarException`'ın mesajında ORA kodu yok, kökteki istisnada var. Oracle `NUMBER` kolonları `getObject`'te `BigDecimal` döner.
- springdoc `3.0.0` Boot 4.1.1 ile çalışıyor (`/v3/api-docs`, `/swagger-ui.html`).
- `spring-boot-maven-plugin` `repackage` execution'ının id'si `repackage` olmalı; yoksa parent'ın execution'ı da çalışıp düz jar'ı çalıştırılabilir jar'la ezer. Modüllerde çalıştırılabilir jar `-exec` ekiyle.
- JKS SSL bundle'ları açılışta yüklenir; dosyası olmayan bundle `server.ssl.enabled=false` olsa da uygulamayı düşürür. Bundle tanımları bu yüzden `mtls` profilinde.
- Flyway auto-config, test sınıf yolunda Flyway olan her modülde çalışır; yapi-runtime'da `spring.flyway.enabled=false`.
- `jwt.issuer-uri` boş string verilirse Boot `iss` doğrulayıcısını boş değerle kurar.
- Jackson 3'te `MissingNode.booleanValue()` (ve diğer `xxxValue()`) istisna atar; olmayabilecek alan `has(...)` ile kontrol edilir.
- Boot 4'te sağlık göstergeleri `org.springframework.boot.health.contributor` paketinde (`spring-boot-health`).
- Bir Spring Boot uygulaması başka birinin test sınıf yolunda açılırken (`SpringApplicationBuilder`) `spring.config.location` verilmezse classpath'teki ilk `application.yml`'ı (öbür uygulamanınkini) okur.

## Sonraki turlar

1. Dağıtımı DevOps yapar ("Verilmiş kararlar"); imaj ve manifest bu repoda yazılmaz. Gerekirse onlar için bir devir notu: uygulamaların yerleri, portlar, ayarlar ve gizli anahtarlar, DB kullanıcıları; env-admin ve admin `mtls` profiliyle, broker'da kalıcı spool volume'u; Kafka topic'i ve DLQ tasarımdaki ayarlarla; ClickHouse DDL'i ve Elastic bileşen şablonu `yapi-log-sink/src/main/resources` altında.
2. Geçiş planı adım 1 (tasarım "Geçiş planı"): DEV'de eski broker ile sink'in Elastic dokümanlarını alan alan karşılaştırma. Eski sabit subtask'leri ve projeye özel response sınıflarını kullanıcı admin ekranından kendisi tanımlar; aktarım script'i yazılmaz (2026-10-21).
3. Frontend: sözleşme `docs/frontend-guide.md`'de; canlı sürüm (artifact) bu dosyadan eşitlenir.

## Tasarıma yansıtılacaklar

Bu turda tasarım dokümanında değişmesi gereken bir şey çıkarsa buraya yazılır; canlı doküman buradan güncellenir.

- 2026-10-16 turunun maddeleri canlı dokümana işlendi (rev 180).
- **Koordinatör (3):** Admin'in proje kataloğu tek ortamın Koordinatör'ünden (`yapi.admin.catalog.environment`, varsayılan PROD); projenin yetkileri ve yetkinin varlığı her ortamın kendi Koordinatör'ünden, ortam başına 5 dk önbellek. Yetkinin ortamda "var" sayılması: o ortamdaki herhangi bir Yapi projesinin `yetkiList`'inde olması. `UNLINKED` projenin yetki listesi boş. Evet/hayır alanları `true`/`1`/`"true"`/`"1"`. Yetki listesinde `description` yok (`null`).
- **Ortamlar (2):** Ön kontrol mesajları ortam kodunu "X ortamında ..." diye yazar, koda ek yapıştırılmaz ("v4 PREPROD ortamında aktif").
- **Ad (1):** Listede olmayan, kuralla değişen adlar: olay alanı ve ClickHouse kolonu `broker_instance` → `yapi_instance`; ClickHouse `yapi_logs.yapi_metrics_1m` ve view'ı; consumer group'lar `yapi-logs-elastic`, `yapi-logs-clickhouse`; DLQ header'ları `yapi.dlq.*`; Elastic bileşen şablonu `yapi-fields`; test çağrısı header'ı `X-Yapi-Test-Secret`; audit logger'ı `yapi.audit`; yerel override dosyası `yapi-local.json`. 2026-10-26 turunun kararlarını (ad, ortamlar, Koordinatör, magic key, admin girişi, kullanıcı ayarları, alarm) Claude tur bitince tasarım dokümanına işler; buraya yalnızca uygulanırken netleşenler yazılır.

## Açık noktalar

- **Oturum kaydındaki `permissions`:** Eski login'in Koordinatör cevabını nasıl çevirdiği görülmedi; `yetkiIsim` listesi varsayıldı. Pilot projede eski ve yeni broker'ın birbirinin oturumunu okuduğu teyit edilecek.
- **`refresh_permissions`:** Koordinatör ekibi giriş servisinin iç ucuna geçecek (kullanıcı iletecek). Geçişte ortak Redis'te eski yol da aynı kaydı siler.
- **`alarm_list`:** Alarm yoksa eski kod servise giden gövdeye `alarm_list` ekliyordu. Alarm şu an kullanılmadığı için eklenmiyor; kullanıcı ileride eklenebileceğini not ettirdi.- **`X-Clientip`:** Kullanıcı bu header'ı frontend'in değil, önündeki proxy'nin koyduğunu düşünüyor; teyit edilmedi. İstemci koyuyorsa IP bağlaması güvenlik sağlamaz; o durumda eski broker kapandıktan sonra IP bağlantıdan ya da güvenilir proxy'nin header'ından alınmalı.
- **Hata yanıtlarında HTTP 200 (ileride):** Mevcut frontend'ler için korunuyor. Frontend'ler güncellenince `yapi.compat.legacy-responses: false` ile ortak formata ve gerçek HTTP status'lara geçilebilir; kullanıcı açık kalmasını istedi.
- Kullanıcı başına tek oturum ve 1 günlük sabit süre (eski davranış): yeni login eskisini ezer, kullanılsa da oturum 1 günde biter.
- PROD'da base URL değişikliğinin onay akışına alınması; PROD'da `DISABLE`/`ROLLBACK` için acil durum politikası (şimdilik onay istiyor).
- Frontend gövdesinde üst seviyede `yapi` adlı alan gönderen istek var mı (eski dokümanda ezilir).
- Broker span'ının `maintask/subtask` diye adlandırılması için OTel agent API'si.
