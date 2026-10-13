# Devir notu

Bu repo, kurumdaki istek broker'ının dinamik ve gözlemlenebilir hale getirilmesi işinin backend'idir. Tasarım ve frontend sözleşmesi tamamlandı; çekirdek ve dört modül yazıldı, `mvn -B verify` yeşil. Kalan iş "Sıradaki adımlar" ve "Açık noktalar"da. Bu not, işi devralan oturumun bağlamı yeniden kurmadan devam etmesi içindir.

Son güncelleme: 2026-10-13.

## Kaynaklar

| Belge | Repodaki kopya | Canlı sürüm |
| --- | --- | --- |
| Tasarım dokümanı | `docs/design.md` | https://claude.ai/code/artifact/b5c786de-4fe3-430e-b5f4-5a44d6730c0b |
| Frontend rehberi (admin API sözleşmesi) | `docs/frontend-guide.md` | https://claude.ai/code/artifact/77705450-7cee-4350-94a9-accec2378a1a |

`docs/frontend-guide.md` merkezi admin API'sinin sözleşmesidir: yollar, gövdeler, hata kodları ve kurallar oradan birebir uygulanır. Sözleşme değişirse aynı commit'te bu dosya ve sonundaki "Değişiklik kaydı" tablosu güncellenir, commit mesajına "Frontend rehberi değişti" yazılır. Canlı sürüm oradan eşitlenir.

## Verilmiş kararlar

Tasarım dokümanındakilere ek olarak bu oturumda netleşenler:

- **Platform:** Java 25, Spring Boot 4.1.1, Maven çok modüllü proje. Paket kökü `com.acme.broker` (şirket adına göre toplu değiştirilebilir).
- **JSON:** Jackson 3 (`tools.jackson.*`; anotasyonlar `com.fasterxml.jackson.annotation.*`). Ağaç API'si yalnızca `broker-core/.../json/Json.java` içinden kullanılır; Jackson metot adı hatası çıkarsa tek yerde düzeltilir.
- **Veri erişimi:** JPA yok; `JdbcClient` ve tasarımdaki SQL. Şemalar Flyway ile, tasarımdaki DDL'den.
- **Broker'ın ortak hata formatı:** `{"result": false, "status": 403, "outcome": "DENIED", "message": "...", "requestId": "...", "errors": []}`. Durumlar: `X-Token` yok 400 `INVALID_REQUEST`, oturum yok 401 `DENIED`, yetki yok 403 `DENIED`, route yok 404 `UNKNOWN_TASK`, gövde `fields`'a uymuyor 400 `INVALID_REQUEST` (alan bazında `errors`).
- **Log yazılamazsa:** `500` ve `{"result": false}`, outcome `LOG_UNAVAILABLE`; olaylar spool'a yazılır. Profil uygulanmaz.
- **Profilsiz yanıt:** Gövde olduğu gibi döner. RESPONSE olayındaki `client_response` bu durumda yalnızca `{"status": <kod>}` olur, böylece loglanmamış veri `data_response`'a sızmaz (kullanıcıya teyit ettirilecek).
- **Dosya yanıtı** (`BINARY` route ya da servis `Content-Disposition` döndürdü): `client_response` = `{"info": <route'un binaryInfo'su ya da "Dosya yanıtı: <dosya adı>">}`; gövde loglanmaz, onaydan sonra stream edilir.
- **Timeout ve bağlantı hatası, profil yoksa:** ortak formatta 504 `TIMEOUT` ve 502 `TECHNICAL_ERROR`.
- **Oturum:** `X-Token` değeri `login` ya da `logout` ise oturum işlemidir: oturum okunmaz, route anonim olmalıdır, parametre logu ve `broker.gelen.istekler` sayacı dışında kalır. Diğer isteklerde Redis'ten tek okuma yapılır. Anahtar ve format ayarla değiştirilebilir olmalı; varsayım: `broker:session:{token}` → `{"userId": "...", "permissions": [...]}`. Gerçek format bugünkü broker kodundan teyit edilmeli.
- **Anonim route + oturum:** Token geçerliyse kullanıcı yine çözülür (`X-uid`, `perm_list`); geçersizse anonim route yine çalışır.
- **Servise giden header'lar:** `X-uid` (oturumdaki kullanıcı), `X-Request-Id`, `traceparent`; gizli anahtar ayarlıysa HMAC imzalı `X-User-Context`.
- **Test çağrıları** Kafka'ya log yazmaz; merkezi admin bunları kendisi audit eder.
- **Eski sayaç:** `broker.gelen.istekler` tag anahtarları ayarla verilir (eski koddan teyit edilmeli). Bilinmeyenler "Bilinmeyen Proje" / "Bilinmeyen Endpoint".
- **Elastic aylık indeks:** Ay, request ID'deki (UUIDv7) zamandan, ayarlanabilir saat diliminde (varsayılan UTC) hesaplanır; eski kodun saat dilimiyle teyit edilmeli. Parametre dokümanı `_id`'si `UUID.nameUUIDFromBytes(requestId|alan|sıra)`.
- **Admin kimliği:** OAuth2 resource server (JWT); rollerin geldiği claim ve grup→rol eşlemesi ayardan. `local` profilinde HTTP Basic: `viewer`, `editor`, `approver`, `lead` (şifre = kullanıcı adı; `lead` üç role de sahip).
- **Roller:** `VIEWER`, `ROUTE_EDITOR`, `APPROVER`. `ROUTE_EDITOR` DEV'e doğrudan uygular (talep onaysız uygulanır), TEST ve PROD için talep açar.
- **Taşıma kuralları:** Onay gerekir = hedef ortam `requires_approval` **ya da** versiyon anonim erişimi açıyorsa (hangi ortam olursa olsun). Onay gerekenlerde gerekçe zorunlu. `ROLLBACK` yalnızca hedef ortama daha önce `APPLIED` olmuş bir versiyona; `DISABLE`/`ENABLE` hedefteki mevcut versiyonla. Profil önce, route sonra uygulanır. Ön kontroller ve adları `docs/frontend-guide.md`'de.
- **Ortam ayarı:** `PUT /projects/{id}/environments/{env}` ortama hemen uygulanır; ortama ulaşılamazsa merkezde de değişmez (502 `ENV_UNREACHABLE`). Onay isteyen ortamda yalnızca `APPROVER` değiştirir (açık nokta).
- **Arşiv:** Yalnızca hiçbir ortamda `ACTIVE` olmayan subtask arşivlenir (409 `ROUTE_DEPLOYED`).

## Modüller ve durum

### broker-core (yeşil)

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

Testleri `broker-core/src/test` altında; tasarım dokümanındaki örnekleri (add_order_note, log-project profili, eski Elastic dokümanları) doğruluyorlar.

Modüller arası sözleşmeler de burada:

| Paket | İçerik |
| --- | --- |
| `runtime` | `RuntimeSignals`: `broker:routes-changed` kanalı, `broker:loaded:{instanceId}` anahtarı ve değeri `{"version": 43, "loadedAt": "..."}` (60 sn TTL) |
| `testcall` | `TestCallRequest` / `TestCallResult` / `TestInterpretation`: merkez → ortam Admin API → broker'ın iç endpoint'i (`POST /internal/v1/test`, `X-Broker-Test-Secret`) |

Test-jar (`com.acme.broker.core.testing`): `CoreFixtures` (add_order_note, log-project, openapi-sample), `FakeHttpServer` (JDK `HttpServer` ile sahte servis), `JsonHttp` (test HTTP istemcisi) ve `TestCertificates` (keytool ile mTLS sertifikaları); diğer modüllerin testleri kullanır.

### broker-runtime (yeşil)

Paket `com.acme.broker.runtime`. Tek endpoint `POST /broker`; akış `web/BrokerHandler`'da, tasarımdaki sırayla:

1. `requestId` UUIDv7, `queryId` rastgele UUID; trace gelen `traceparent`'tan (geçersizse yeni), servise yeni span ID ile gider.
2. Gövde JSON nesnesi değilse 400; `X-Token` yoksa 400 (`INVALID_REQUEST`). Gövde üst sınırı `broker.forward.max-request-bytes`.
3. `RouteCache.find` (bellek, `Names.key`); yoksa 404 `UNKNOWN_TASK`.
4. `login`/`logout`: oturum okunmaz, route anonim değilse 403. Diğerleri: tek Redis GET (`broker.session.*`). Anonim olmayan route'ta oturum yoksa 401, yetki yoksa 403 (any-of). Redis okunamazsa anonim olmayan route 503 `TECHNICAL_ERROR`, anonim route kullanıcısız devam eder.
5. REQUEST olayı; onay yoksa servis çağrılmaz, 500 `{"result": false}`, olaylar spool'a.
6. `RequestBodyValidator` + `BackendRequestBuilder`; servise `X-uid`, `X-Request-Id`, `traceparent`, ayarlıysa `X-User-Context` (`v1.<payload>.<HMAC-SHA256>`, payload `{userId, requestId, issuedAt}`).
7. JDK `HttpClient` (HTTP/1.1, proxy yok, redirect izlenmez). Retry yalnızca GET'te 502/503/504 ve bağlantı hatasında; timeout tekrarlanmaz (route'un bütçesini zaten harcamıştır). Gövde okuma da route timeout'una tabidir.
8. Dosya yanıtı (`BINARY` ya da `Content-Disposition`): RESPONSE olayı onaylanınca stream; `client_response` `{"info": ...}`, `body_size` `Content-Length`'ten. Değilse gövde okunur, outcome gerçek status'tan; profil varsa zarflanır (`client_response`'ta `data` boş string), yoksa gövde olduğu gibi döner ve `client_response` `{"status": <kod>}`.
9. Her yanıtta `X-Request-Id`; hata yollarında REQUEST ve RESPONSE birlikte gönderilip ikisi de beklenir.

- **`status_code`:** Servis yanıt verdiyse servisin status'u; vermediyse (broker hataları, timeout, bağlantı hatası) frontend'e dönen status. ClickHouse kolonu böylece boş kalmaz.
- **Log isteği gövdesi:** `logRequestBody=false` ise REQUEST olayında yalnızca zarf alanları yazılır (eski ana doküman zarfı yine taşır).
- **RouteCache:** `RouteRepository.loadSnapshot()` REPEATABLE READ READ ONLY; bozuk route/profil atlanır (`broker.routes.skipped`). `broker:routes-changed` aboneliği, `broker.cache.verify-interval` (15 sn) kontrolü, her kontrolde `broker:loaded:{instanceId}` (60 sn TTL). İlk yükleme ve Kafka metadata ısınması `ApplicationRunner` (ısınma önce).
- **Spool:** istek başına bir `.jsonl` dosyası (atomik taşıma), `broker.spool.resend-interval` ile eskiden yeniye gönderilir; ilk hatada durur. Gauge `broker.log.spool.files`.
- **Metrikler:** `broker.requests` (maintask, subtask, outcome; bilinmeyen subtask `UNKNOWN`), `broker.gelen.istekler` (tag anahtarları ve "Bilinmeyen ..." değerleri `broker.legacy-counter.*`), `broker.log.ack`, `broker.log.unavailable`, `broker.routes.config.version`.
- **İç test endpoint'i:** `POST /internal/v1/test`, yalnızca `broker.test.enabled=true` iken oluşur; `X-Broker-Test-Secret` sabit zamanlı karşılaştırılır. Kafka'ya yazmaz.
- **`local` profili:** `LocalOverrides` (`broker-local.json`, `baseUrls` + `routes`, dosya değişince 2 sn içinde uygulanır), `log-sink: console`, `broker.local.skip-permission-check-for-overrides`.
- **Flyway kapalı** (`spring.flyway.enabled=false`): broker şemaya hiç yazmaz.
- **Testler:** env-admin'in migration'ı ve `RuntimeStore`'u ile runtime DB, Testcontainers PostgreSQL ve Redis, EmbeddedKafka, `FakeHttpServer`. Log kapısı topic'i olmayan bir Kafka ile denenir (servis çağrılmaz, 500, spool; topic açılınca spool gönderilir).
- **Tracing:** span adı ve attribute'lar OTel Java agent'ına bırakıldı; broker `traceparent`'ı taşır. Span'ı `maintask/subtask` diye adlandırmak için agent'ın API'si eklenmeli (açık).

### broker-env-admin (yeşil)

Paket `com.acme.broker.envadmin`. Uçlar `/admin/v1` altında; hatalar merkezle aynı problem formatında (`code`, `detail`, `errors`).

| Uç | Davranış |
| --- | --- |
| `PUT /routes/{maintask}/{subtask}` | Tasarımdaki tek ifadeyle uygular. Yanıt `{"applied", "configVersion", "alreadyApplied", "signalled"}`. Aynı `promotionId` tekrar gelirse `alreadyApplied: true`; daha yeni talep varsa 409 `STALE_PROMOTION` (`currentPromotionId`); proje ya da profil ortamda yoksa 409 `PROJECT_NOT_FOUND` / `PROFILE_NOT_FOUND`. Tanım `RouteValidator` ile (header okunabilirliği hariç) doğrulanır |
| `PUT /response-profiles/{name}` | Aynı desen; `ResponseProfile.parse` ile doğrulanır |
| `POST /routes/{m}/{s}/disable` | Kill switch, `promotionId` ile; route yoksa 404 `ROUTE_NOT_FOUND`. `ENABLE` ayrı uç değil: hedefteki versiyon `PUT` ile yeniden uygulanır |
| `PUT /projects/{maintask}` | `{"baseUrl", "enabled", "actor"}`; değişiklik varsa config versiyonu artar ve sinyal gider (broker'lar base URL'i snapshot'la okur) |
| `GET /routes` | Projeler, profiller, tüm route'lar (DISABLED dahil) ve config versiyonu |
| `GET /cache-status` | `broker:loaded:*` (SCAN) → instance başına `loadedVersion`, `loadedAt`, `upToDate` |
| `GET /projects/{maintask}/spec?path=` | Base URL runtime DB'den; yol doğrulanır (`..`, `//`, şema, ters bölü reddedilir). Hata: 502 `UPSTREAM_ERROR` (`upstreamStatus`) |
| `POST /probe` | `{"maintask", "httpMethod", "pathTemplate", "pathVariables"}` → `{"result", "status", "allow", "url", "message"}`; değeri verilmeyen path değişkeni `1` olur; redirect izlenmez; ulaşılamazsa `INCONCLUSIVE`, `status: null` |
| `POST /test` | `TestCallRequest`'e kayıtlı base URL'i koyup broker'ın iç endpoint'ine iletir; kapalıysa 403 `TEST_NOT_ALLOWED`, broker'a ulaşılamazsa 502 `BROKER_UNREACHABLE` |

- **Şema:** `db/migration/broker_runtime/V1__runtime_schema.sql` (tasarımdaki DDL; rol yoksa GRANT atlanır). Flyway şema sahibiyle (`BROKER_RUNTIME_DB_OWNER_*`), uygulama `broker_runtime_writer` ile çalışır.
- **mTLS:** `server.ssl.bundle=env-admin`, `client-auth=need`; bundle `mtls` profilinde (`application-mtls.yml`). Profil açılmazsa uygulama başlamaz. `broker.env-admin.allowed-client-subjects` doluysa sertifika konusu da kontrol edilir (403 `CLIENT_NOT_ALLOWED`). Actuator ayrı portta (9090), TLS'siz.
- **Audit:** `broker.audit` logger'ı; `operation`, `actor` (gövdeden ya da `X-Actor`), `promotion_id`, `client` alanları.
- **Paketleme:** çalıştırılabilir jar `-exec` ekiyle; düz jar ve test-jar (`RuntimeContainers`: PostgreSQL + Redis) broker-runtime testlerinde kullanılır.
- **Testler:** Testcontainers PostgreSQL ve Redis, `FakeHttpServer`; mTLS keytool ile üretilen sertifikalarla uçtan uca denenir.

### broker-admin (yeşil)

Paket `com.acme.broker.admin`. Sözleşme `docs/frontend-guide.md`; uygulama sırasında netleşen gövde ve yanıtlar oradaki "Gövde ve yanıt ayrıntıları" tablosunda.

| Paket | İçerik |
| --- | --- |
| `security` | `!local`: OAuth2 resource server (JWT); roller `broker.admin.security.roles-claim` claim'inden, rol adı değilse `group-roles` ile eşlenir; kullanıcı adı `preferred_username` (yoksa `sub`). `local`: HTTP Basic, `viewer`/`editor`/`approver`/`lead`. Rol kontrolleri serviste (`User.require`); rolü olmayan kullanıcı her uçta 403. 401/403 problem formatında |
| `environment` | `EnvironmentRepository` (sıra, onay, test ayarı DB'den), `EnvironmentClient` (Admin API; `ssl-bundle` ile mTLS, redirect yok). Bağlanamama `UnreachableException` (hiçbir şey değişmedi), istek gitti ama yanıt yok `NoAnswerException` (uygulanmış olabilir) |
| `project`, `catalog`, `authz` | Proje kaydı (önek çakışması `PREFIX_OVERLAP`, harf duyarsız `DUPLICATE`), ortam ayarı önce ortama, sonra merkeze. Proje ve yetkilendirme servisleri `mode: http` (varsayılan API'ler sınıf yorumlarında) ya da `memory` |
| `route`, `profile` | Güncel satır + aynı transaction'da history (`to_jsonb`), iyimser kilit (`VERSION_CONFLICT` + `currentVersion`), geri alma, arşiv (`ROUTE_DEPLOYED`), karşılaştırma, doğrulama, önizleme (`BackendRequestBuilder`) |
| `promotion` | Ön kontroller, onay kuralı (hedef `requires_approval` ya da versiyon anonim erişimi açıyorsa), dört göz (`FOUR_EYES` + `ck_four_eyes`), uygulama: yoklama → Admin API (`promotionId` = talep ID'si) → `route_deployment`/`response_profile_deployment`. Yanıt kaybolursa talep `APPLYING`'de kalır, `RETRY` aynı ID ile tamamlar |
| `spec` | Spec karşılaştırma (method + path, değişken adları yok sayılır; `CHANGED` saklanan `spec_fingerprint`'ten), istekten şablon, test (yalnızca `test_calls_allowed`; `broker.audit` logu), yoklama |
| `jobs` | `DriftCheck` (gece; ortamın listesiyle karşılaştırma + her ACTIVE route'a yoklama, `broker.admin.drift` sayacı, `last_probe_status`), `CatalogSync` (ad yenileme, servisten kalkan proje `MISSING`). Birden çok instance'ta aynı anda çalışmaları zararsız (idempotent) |

- **Şema:** `db/migration/broker_admin/V1__central_schema.sql` tasarımdaki DDL'in birebir kopyası (rol yoksa GRANT atlanır); `V2__environments.sql` ortamları `dev/test/prod_admin_api_url` placeholder'larıyla seed eder. Trigger fonksiyonları tabloları şemasız çağırdığı için bağlantının `search_path`'i `broker_admin` olmalı (`spring.datasource.hikari.schema`).
- **Ayar:** SSO issuer'ı `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` ile verilir (boş varsayılan yok; boş issuer her token'ı reddeder). mTLS istemci sertifikası `mtls` profilinde (`application-mtls.yml`, bundle `env-client`).
- **Testler:** Testcontainers PostgreSQL, `local` profili ve bellek içi `FakeEnvironments` (üç ortamın Admin API'si; promotion ID idempotency'si, eski talebin reddi, proje/profil yokluğu, sessiz kalma). SSO testi gerçek imzalı JWT ile; ortam istemcisinin mTLS'i istemci sertifikası isteyen bir `HttpsServer`'a karşı.

### broker-log-sink (yeşil)

Paket `com.acme.broker.sink`. İki `@KafkaListener` (toplu), her biri kendi consumer group'unda; `broker.sink.elastic.enabled` ve `broker.sink.clickhouse.enabled` ile ayrı ayrı açılır (aynı imaj iki deployment olarak da çalışabilir).

- **Elastic (`broker-logs-elastic`):** `LegacyDocuments` → tek `_bulk` NDJSON; ana doküman `update` + `doc_as_upsert` + `retry_on_conflict`, parametre dokümanları `index`. Kimlik `ApiKey` ya da basic. Öğe hatası 429/5xx → batch yeniden denenir; diğer 4xx → o olay DLQ'ya.
- **ClickHouse (`broker-logs-clickhouse`):** `INSERT INTO broker_logs.broker_log FORMAT JSONEachRow`, `date_time_input_format=best_effort`; satırda `null` yok (varsayılanlar), `token`/`perm_list`/`client_response`/`query_id` yazılmaz. 400 → satır satır tekrar, kabul edilmeyen satır DLQ'ya; 401/403/404/5xx/bağlantı → batch yeniden denenir.
- **DLQ:** `broker.logs.v1.dlq`, orijinal key/değer ve `broker.dlq.sink`, `broker.dlq.reason`, `broker.dlq.origin` header'ları; DLQ onayı gelmeden offset commit edilmez.
- **Offset:** `ack-mode: batch`; hata durumunda `DefaultErrorHandler` sınırsız sabit aralıkla (`broker.sink.retry-interval`) aynı batch'i dener, kayıt atlanmaz. Batch: `max-poll-records` 10.000, `fetch-max-wait` 1 sn.
- **Kurulum dosyaları:** `clickhouse/broker_logs.sql` (tasarımdaki DDL) ve `elastic/broker-fields.component-template.json` (mevcut `requests_*` şablonunun `composed_of` listesine eklenir). Sink tablo ya da şablon oluşturmaz.
- **Testler:** EmbeddedKafka; Elastic ve ClickHouse HTTP sahteleri (`update`/`doc_as_upsert` birleştirmesini uygular). REQUEST/RESPONSE sırası, bir hedef düşükken diğerinin ilerlemesi ve düşük hedefin offset'inin commit edilmemesi, bozuk mesajın DLQ'ya gidip partition'ı durdurmaması, hedefin reddettiği olayların DLQ'ya gitmesi denenir. `RealTargetsTest` gerçek ClickHouse 24.8 ve Elasticsearch 8.15 ile: tasarımdaki DDL çalışır ve satırları kabul eder (metrik view'ı, `FINAL` tekilleştirmesi), bileşen şablonu `requests_*` şablonuna eklenince `broker.*` tipleri doğru, eşleme çakışması 400 ile DLQ'ya gider.
- **Bilinen sınır:** Frontend gövdesinde üst seviyede `broker` adlı bir alan varsa eski ana dokümanda `broker` nesnesi onu ezer (tasarım böyle bir alan olmadığını varsayıyor). Elastic'teki son ayların alan adlarıyla teyit edilmeli.

## Spring Boot 4 notları

Derlemede doğrulandı (Boot 4.1.1, Spring Framework 7.0, Jackson 3.1, Kafka 4.2, Testcontainers 2.0).

- Starter'lar: `spring-boot-starter-webmvc`, `-jdbc`, `-flyway` (+ `flyway-database-postgresql`), `-data-redis`, `-kafka`, `-actuator`, `-security`, `-security-oauth2-resource-server`. Test: `spring-boot-starter-test`, `spring-kafka-test`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`; `@LocalServerPort` `org.springframework.boot.test.web.server` paketinde. Yeni `org.testcontainers.postgresql.PostgreSQLContainer` generic değil.
- springdoc `3.0.0` Boot 4.1.1 ile çalışıyor (`/v3/api-docs`, `/swagger-ui.html`).
- `spring-boot-maven-plugin` `repackage` execution'ının id'si `repackage` olmalı; yoksa parent'ın execution'ı da çalışıp düz jar'ı çalıştırılabilir jar'la ezer. Modüllerde çalıştırılabilir jar `-exec` ekiyle.
- JKS SSL bundle'ları açılışta yüklenir; dosyası olmayan bundle `server.ssl.enabled=false` olsa da uygulamayı düşürür. Bundle tanımları bu yüzden `mtls` profilinde.
- Flyway auto-config, test sınıf yolunda Flyway olan her modülde çalışır; broker-runtime'da `spring.flyway.enabled=false`.
- `jwt.issuer-uri` boş string verilirse Boot `iss` doğrulayıcısını boş değerle kurar.

## Sıradaki adımlar

Dört modül ve çekirdek yazıldı; `mvn -B verify` yeşil (131 test). Sıradakiler:

1. "Açık noktalar"daki teyitler: oturum kaydının formatı, eski sayacın tag'leri, Elastic ay hesabının saat dilimi, SSO claim'i, proje ve yetkilendirme servislerinin gerçek API'leri (varsayılan uçlar `HttpProjectCatalog` ve `HttpAuthorizationService` yorumlarında).
2. Dağıtım: dört uygulamanın imajları ve ortam manifestleri (env-admin ve admin `mtls` profiliyle; broker'da kalıcı spool volume'u önerilir). Kafka topic'i ve DLQ tasarımdaki ayarlarla açılmalı; ClickHouse DDL'i ve Elastic bileşen şablonu `broker-log-sink/src/main/resources` altında.
3. Geçiş planı adım 1–2 (tasarım "Geçiş planı"): DEV'de eski broker ile sink'in Elastic dokümanlarını alan alan karşılaştırma; statik subtask'leri `runtime_route`'a ve merkezi history'ye aktaran tek seferlik script.
4. Frontend: sözleşme `docs/frontend-guide.md`'de; canlı sürüm (artifact) bu dosyadan eşitlenmeli.

## Açık noktalar

- Redis'teki oturum kaydının gerçek anahtarı ve formatı (`broker.session.*` ile ayarlanabilir).
- `broker.gelen.istekler` sayacının tag anahtarları (`broker.legacy-counter.*`) ve eski Elastic indeksinin ay hesabındaki saat dilimi (`broker.sink.elastic.index-zone`).
- SSO sağlayıcısı ve rol claim'i; yetkilendirme servisi ile proje servisinin gerçek API'leri.
- Profilsiz yanıtlarda `data_response` içeriği (şimdilik yalnızca status).
- PROD'da base URL değişikliğinin onay akışına alınması; PROD'da `DISABLE`/`ROLLBACK` için acil durum politikası (şimdilik onay istiyor).
- Frontend gövdesinde üst seviyede `broker` adlı alan gönderen istek var mı (eski dokümanda ezilir).
- Broker span'ının `maintask/subtask` diye adlandırılması için OTel agent API'si.
