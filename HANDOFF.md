# Devir notu

Bu repo, kurumdaki istek broker'ının dinamik ve gözlemlenebilir hale getirilmesi işinin backend'idir. Tasarım ve frontend sözleşmesi tamamlandı; çekirdek ve dört modül yazıldı, `mvn -B verify` yeşil. Bu turun işi "Sıradaki iş" bölümünde: Oracle 19c, yanıt verisinin loglanması, eski broker'la aynı oturum ve yanıtlar, giriş servisi. Bu not, işi devralan oturumun bağlamı yeniden kurmadan devam etmesi içindir.

Son güncelleme: 2026-10-16.

## Kaynaklar

| Belge | Repodaki kopya | Canlı sürüm |
| --- | --- | --- |
| Tasarım dokümanı | `docs/design.md` | https://claude.ai/code/artifact/b5c786de-4fe3-430e-b5f4-5a44d6730c0b |
| Frontend rehberi (admin API sözleşmesi) | `docs/frontend-guide.md` | https://claude.ai/code/artifact/77705450-7cee-4350-94a9-accec2378a1a |

`docs/frontend-guide.md` merkezi admin API'sinin sözleşmesidir: yollar, gövdeler, hata kodları ve kurallar oradan birebir uygulanır. Sözleşme değişirse aynı commit'te bu dosya ve sonundaki "Değişiklik kaydı" tablosu güncellenir, commit mesajına "Frontend rehberi değişti" yazılır. Canlı sürüm oradan eşitlenir.

`docs/design.md` bu repoda değiştirilmez; canlı doküman güncellenip buraya aktarılır. Tasarıma yansıması gereken bir şey çıkarsa "Tasarıma yansıtılacaklar" başlığına yazılır.

## Sıradaki iş (2026-10-16)

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

### 2. Yanıt verisinin loglanması ve küçük log düzeltmeleri

Subtask editöründeki "Yanıt verisi loglansın" seçeneği mevcut `logging.logResponseBody` bayrağıdır (varsayılan kapalı). API'nin biçimi değişmez, anlamı genişler (rehberde güncellendi):

- **Kapalı:** Bugünkü gibi; eski Elastic dokümanında `data_response.data` boş string, ClickHouse'ta yanıt gövdesi yok.
- **Açık:** `data_response.data`, yanıttaki `data` değerinin maskelenmiş (`maskFields` + `password`) JSON metni olur. `maxBodyBytes`'ı aşarsa UTF-8 karakteri bölmeden kırpılır ve `broker.body_truncated: true` yazılır. Alan string kaldığı için Elastic'teki eşleme değişmez. ClickHouse'a bugünkü gibi gövde yazılır.
- **Profilsiz JSON yanıt:** Kapalıyken bugünkü gibi `{"status": <kod>}`. Açıkken maskelenmiş gövdenin kendisi; üst seviyedeki `data` string değilse JSON metnine çevrilir; sınırı aşarsa `{"status": <kod>, "data": "<kırpılmış metin>"}`.
- Değişiklik `EventFactory.clientResponse`'ta (route'un log politikasını alır); `LegacyDocuments` aynı kalır.
- **Elastic aylık indeksi:** Eski kod `requests_yyyy-MM`'i saat dilimi vermeden (`new SimpleDateFormat("yyyy-MM")`), yani sunucunun saat diliminde hesaplıyor. `broker.sink.elastic.index-zone` varsayılanı `Europe/Istanbul` olur; kullanıcı eski sunucunun TSİ'de çalıştığını teyit etti.
- **Eski sayaç:** `broker.gelen.istekler` tag'leri (`maintask`, `subtask`) ve "Bilinmeyen Proje"/"Bilinmeyen Endpoint" eski controller'la teyit edildi; ayardaki "teyit edilmeli" notları kalkar.
- **Test:** core ve runtime birim testleri; `RealTargetsTest`'te 40 KB'lık `data` metni olan doküman varsayılan dinamik eşlemeyle kabul edilir.

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
- **LDAP:** Eski koddaki gibi Spring LDAP ile doğrudan bind: `ldapTemplate.getContextSource().getContext(<principal>, password)`, principal `uid@<alan adı>` (Active Directory UPN; preprod'da `uid@test.com.tr`). Bind başarılıysa true; `uid` ya da şifre boşsa ya da herhangi bir exception olursa false. Arama yok. Ayar: `login.ldap.urls`, `login.ldap.principal-pattern` (ör. `{uid}@test.com.tr`; testlerde gömülü LDAP için `uid={uid},ou=people,dc=test` gibi bir DN), bağlantı 3 sn, okuma 5 sn. Eski broker ortam başına ayrı `Ldap` sınıfı kullanıyor (`LdapPreprod`, `spring.profiles.active=preprod`); yeni serviste fark yalnızca ayardır. `local` profilinde ayardaki sabit kullanıcılarla çalışan bir mod olur (`login.ldap.mode: static`); başka profilde açılırsa uygulama başlamaz.
- **Güvenlik:** Şifre hiçbir yerde loglanmaz. `login.user-context-secret` ayarlıysa broker'ın imzaladığı `X-User-Context` doğrulanır (`issuedAt` 60 sn içinde), imzasız çağrı 403. Servis yalnızca broker'ın ağından erişilebilir olmalı.
- **Örnek tanımlar:** `giris/login` ve `giris/logout` route tanımları modülün README'sinde ve `broker-local.json` örneğinde: POST, `TEMPLATE` gövde `{"uid": "${request.uid}", "password": "${request.password}"}` (logout'ta yalnızca `uid`), header'lar `${header.X-Clientip}` ve `${header.X-User-Agent}`, anonim, profil yok.
- **Test:** gömülü LDAP (UnboundID), Koordinatör için `FakeHttpServer`, Testcontainers Redis; yanıt gövdeleri birebir. Uçtan uca: login → token'la istek geçer → farklı `X-User-Agent` 301 alır → farklı `X-User-Agent`'la logout reddedilir → doğru header'larla logout → aynı istek 301 alır.

### 5. Yerel çalıştırma

- `docker compose` (Oracle XE, Redis, Kafka; Elastic ve ClickHouse ayrı bir profilde) ve `docs/local-run.md`. Frontend geliştiricisi admin API'yi `local` profili ve tohum verisiyle tek komutla ayağa kaldırabilmeli; broker ve giriş servisi de aynı compose'la denenebilmeli.
- OpenAPI ve Swagger yalnızca `local` ve `dev` profillerinde açık; diğerlerinde `springdoc.api-docs.enabled=false`.

### 6. `${user.permission}` yer tutucusu

Kullanıcıda olan ilk route yetkisi: önce `permissions`, sonra `foreignPermissions`, tanımdaki sırayla. Oturum yoksa değeri yoktur, alan yazılmaz. Eski broker `sorgu` isteklerinde `perm`'i böyle seçiyordu (önce `SORGU_TEMEL`, sonra `SORGU_ILERI`); `sorgu` route'ları bu sırayla tanımlanınca aynı davranış elde edilir. Doğrulayıcı, önizleme, rehberin yer tutucu tablosu ve "Değişiklik kaydı" güncellenir.

### 7. Alarm (bu turda yok)

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
- **Koordinatör:** Yetki projesinin adı. Projeleri ve yetkileri yönetir, `koordinator.secret` ile kullanıcı oturumu kapatabilir. API'leri sonra gelecek.
- **`PublicApisService`:** Broker'daki bütün proje servislerine referans tutuyor; `refresh_permissions` ve `giris_public` subtask'leri bu servislere sabit yazılmış. Yeni düzende özel bir karşılığı yok.
- **Saat dilimi:** Eski broker sunucusu TSİ'de çalışıyor (kullanıcı teyidi).
- **`RedisTemplate<String, Object>` bean'i:** `setKeySerializer(new StringRedisSerializer())`, `setValueSerializer(new JacksonJsonRedisSerializer<>(Object.class))`, hash anahtar ve değerleri de `JacksonJsonRedisSerializer`, `setEnableTransactionSupport(true)`. Değerler tip bilgisi olmayan JSON.
- **LDAP (`LdapPreprod.check_user`):** `uid`, şifre boş değilse `ldapTemplate.getContextSource().getContext(uid + "@test.com.tr", password)`; başarılıysa true, exception olursa false. Sınıf `@ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")` ile yalnızca preprod'da yükleniyor; diğer ortamların sınıfları görülmedi.
- **Java sürümü:** Eski kod `catch (Exception _)` kullanıyor (Java 22+) ve spring-data-redis 4.0.3 ile çalışıyor (Boot 4.0); JSON aynı Jackson 3 ile yazılıyor.

## Bilgi bekleyenler

Kullanıcıdan istendi; gelince ilgili madde güncellenir:

1. Koordinatör'ün gerçek uçları; şimdilik madde 4'teki varsayılanlar.
2. Diğer ortamların `Ldap` sınıfları (dev ve test'te farklı bir alan adı ya da her şifreyi kabul eden bir sınıf olabilir). Şimdilik her ortamda gerçek bind, fark yalnızca ayarda.
3. Dashboard yerleşimini ve hızlı bağlantıları kullanan subtask'ler (maintask/subtask ve gövdeler) → yeni yerleri (sonraki tur).
4. Alarm: `alarmService.checkAlarm` ve `alarm_log` kodu, `alarm` indeksinden örnek bir doküman (madde 7).

## Verilmiş kararlar

Tasarım dokümanındakilere ek olarak netleşenler:

- **Platform:** Java 25, Spring Boot 4.1.1, Maven çok modüllü proje. Paket kökü `com.acme.broker` (şirket adına göre toplu değiştirilebilir).
- **JSON:** Jackson 3 (`tools.jackson.*`; anotasyonlar `com.fasterxml.jackson.annotation.*`). Ağaç API'si yalnızca `broker-core/.../json/Json.java` içinden kullanılır; Jackson metot adı hatası çıkarsa tek yerde düzeltilir.
- **Veri erişimi:** JPA yok; `JdbcClient` ve SQL, şemalar Flyway ile. Veritabanı Oracle 19c (merkezi ve runtime); kod şu an PostgreSQL'de, geçiş "Sıradaki iş" 1.
- **Broker'ın ortak hata formatı:** `{"result": false, "status": 403, "outcome": "DENIED", "message": "...", "requestId": "...", "errors": []}`. Durumlar: `X-Token` yok 400 `INVALID_REQUEST`, oturum yok 401 `DENIED`, yetki yok 403 `DENIED`, route yok 404 `UNKNOWN_TASK`, gövde `fields`'a uymuyor 400 `INVALID_REQUEST` (alan bazında `errors`). Eski broker'ın gövdeleriyle dönecek durumlar (application, oturum, login, logout) "Sıradaki iş" 3 ve 4'te.
- **Log yazılamazsa:** `500` ve `{"result": false}`, outcome `LOG_UNAVAILABLE`; olaylar spool'a yazılır. Profil uygulanmaz.
- **Logdaki oturum bilgisi:** `token`, `client_ip`, `user_agent` ve login yanıtındaki `X-Token` eskisi gibi olduğu gibi loglanır. Kullanıcı kararı: istek olduğu gibi loglanmalı; broker'ın Elasticsearch'ü ayrı, erişim çok sınırlı ve oturumlar kayıt altında.
- **Profilsiz yanıt:** Gövde olduğu gibi döner. RESPONSE olayındaki `client_response` bu durumda yalnızca `{"status": <kod>}` olur, böylece loglanmamış veri `data_response`'a sızmaz; `logResponseBody` açıksa "Sıradaki iş" 2'deki kural geçerlidir.
- **Dosya yanıtı** (`BINARY` route ya da servis `Content-Disposition` döndürdü): `client_response` = `{"info": <route'un binaryInfo'su ya da "Dosya yanıtı: <dosya adı>">}`; gövde loglanmaz, onaydan sonra stream edilir.
- **Timeout ve bağlantı hatası, profil yoksa:** ortak formatta 504 `TIMEOUT` ve 502 `TECHNICAL_ERROR`.
- **Oturum:** Eski broker'dan teyit edildi: kayıt `user:session:{uid}`, geçerlilik token, `X-Clientip` ve `X-User-Agent` eşleşmesiyle; login ve logout ayrı bir giriş servisinde. Oturum işlemleri parametre logu ve `broker.gelen.istekler` sayacı dışında kalır. Kod henüz eski varsayımla çalışıyor (`broker:session:{token}`, oturum işleminde gövdedeki route anonim olmalı); geçiş "Sıradaki iş" 3 ve 4.
- **Anonim route + oturum:** Token geçerliyse kullanıcı yine çözülür (`X-uid`, `perm_list`); geçersizse anonim route yine çalışır.
- **Servise giden header'lar:** `X-uid` (oturumdaki kullanıcı), `X-Request-Id`, `traceparent`; gizli anahtar ayarlıysa HMAC imzalı `X-User-Context`.
- **Test çağrıları** Kafka'ya log yazmaz; merkezi admin bunları kendisi audit eder.
- **Eski sayaç:** `broker.gelen.istekler` tag anahtarları ayarla verilir; eski controller'la teyit edildi (`maintask`, `subtask`). Bilinmeyenler "Bilinmeyen Proje" / "Bilinmeyen Endpoint".
- **Elastic aylık indeks:** Ay, request ID'deki (UUIDv7) zamandan, ayarlanabilir saat diliminde hesaplanır. Eski kod sunucunun saat dilimini kullanıyor; varsayılan "Sıradaki iş" 2'de `Europe/Istanbul` olacak. Parametre dokümanı `_id`'si `UUID.nameUUIDFromBytes(requestId|alan|sıra)`.
- **Admin kimliği:** OAuth2 resource server (JWT); rollerin geldiği claim ve grup→rol eşlemesi ayardan. `local` profilinde HTTP Basic: `viewer`, `editor`, `approver`, `lead` (şifre = kullanıcı adı; `lead` üç role de sahip).
- **Roller:** `VIEWER`, `ROUTE_EDITOR`, `APPROVER`. `ROUTE_EDITOR` DEV'e doğrudan uygular (talep onaysız uygulanır), TEST ve PROD için talep açar.
- **Taşıma kuralları:** Onay gerekir = hedef ortam `requires_approval` **ya da** versiyon anonim erişimi açıyorsa (hangi ortam olursa olsun). Onay gerekenlerde gerekçe zorunlu. `ROLLBACK` yalnızca hedef ortama daha önce `APPLIED` olmuş bir versiyona; `DISABLE`/`ENABLE` hedefteki mevcut versiyonla. Profil önce, route sonra uygulanır. Ön kontroller ve adları `docs/frontend-guide.md`'de.
- **Ortam ayarı:** `PUT /projects/{id}/environments/{env}` ortama hemen uygulanır; ortama ulaşılamazsa merkezde de değişmez (502 `ENV_UNREACHABLE`). Onay isteyen ortamda yalnızca `APPROVER` değiştirir (açık nokta).
- **Arşiv:** Yalnızca hiçbir ortamda `ACTIVE` olmayan subtask arşivlenir (409 `ROUTE_DEPLOYED`).

## Modüller ve durum

Bu bölüm kodun 2026-10-13'teki halini anlatır (PostgreSQL, token anahtarlı oturum); "Sıradaki iş"teki maddeler bittikçe güncellenir.

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

## Sonraki turlar

1. Dağıtım: dört uygulamanın ve giriş servisinin imajları ve ortam manifestleri (env-admin ve admin `mtls` profiliyle; broker'da kalıcı spool volume'u önerilir). Kafka topic'i ve DLQ tasarımdaki ayarlarla açılmalı; ClickHouse DDL'i ve Elastic bileşen şablonu `broker-log-sink/src/main/resources` altında.
2. Geçiş planı adım 1–2 (tasarım "Geçiş planı"): DEV'de eski broker ile sink'in Elastic dokümanlarını alan alan karşılaştırma; statik subtask'leri `runtime_route`'a ve merkezi history'ye aktaran tek seferlik script.
3. Eski broker'ın Redis'te tuttuğu kullanıcı verileri (dashboard yerleşimi `user:layout:{sicil}`, hızlı bağlantılar `user:quick_link:{sicil}`), "Bilgi bekleyenler" 3 gelince. Değerleri de tip bilgisi olmayan JSON; anahtar ve biçim korunursa veri taşımadan yeni servise geçilir.
4. Alarm ("Sıradaki iş" 7).
5. Frontend: sözleşme `docs/frontend-guide.md`'de; canlı sürüm (artifact) bu dosyadan eşitlenir.

## Tasarıma yansıtılacaklar

Bu turda tasarım dokümanında değişmesi gereken bir şey çıkarsa buraya yazılır; canlı doküman buradan güncellenir.

- Henüz yok.

## Açık noktalar

- SSO sağlayıcısı ve rol claim'i.
- **`X-Clientip`:** Kullanıcı bu header'ı frontend'in değil, önündeki proxy'nin koyduğunu düşünüyor; teyit edilmedi. İstemci koyuyorsa IP bağlaması güvenlik sağlamaz; o durumda eski broker kapandıktan sonra IP bağlantıdan ya da güvenilir proxy'nin header'ından alınmalı.
- **Hata yanıtlarında HTTP 200 (ileride):** Mevcut frontend'ler için korunuyor. Frontend'ler güncellenince `broker.compat.legacy-responses: false` ile ortak formata ve gerçek HTTP status'lara geçilebilir; kullanıcı açık kalmasını istedi.
- Kullanıcı başına tek oturum ve 1 günlük sabit süre (eski davranış): yeni login eskisini ezer, kullanılsa da oturum 1 günde biter.
- PROD'da base URL değişikliğinin onay akışına alınması; PROD'da `DISABLE`/`ROLLBACK` için acil durum politikası (şimdilik onay istiyor).
- Frontend gövdesinde üst seviyede `broker` adlı alan gönderen istek var mı (eski dokümanda ezilir).
- Broker span'ının `maintask/subtask` diye adlandırılması için OTel agent API'si.
