# Broker Admin — Frontend Rehberi

Oct 13, 2026 · @Yusuf

Bu rehber, broker yönetim ekranlarını geliştirecek frontend için ekranları, akışları ve backend API sözleşmesini anlatır. Backend değiştikçe güncellenir; değişiklikler en alttaki kayıtta listelenir.

## Genel bakış

Broker Admin, DevOps'un subtask'leri, yetkileri ve yanıt profillerini kod yazmadan tanımladığı ve DEV → TEST → PROD sırasıyla taşıdığı yönetim ekranıdır. Ekran yalnızca merkezi admin backend'iyle (`broker-admin`) konuşur; ortamlara, broker'lara ve proje servisine backend bağlanır.

- **Ekrandan yapılanlar:** Proje kaydı ve ortam adresleri, proje yetkileri, subtask tanımı (servisin spec'inden, yapıştırılan bir istekten ya da elle), yanıt profilleri, test çağrısı, versiyon geçmişi ve geri alma, taşıma talebi ve onay.
- **Ekranın dışında kalanlar:** Kullanıcılara yetki atamak yetkilendirme servisinde kalır. Uygulamaların broker trafiği ve logları bu ekrandan geçmez.
- **Temel kural:** Bir subtask'i değiştirmek yalnızca yeni bir versiyon üretir. Değişiklik bir ortamda, ancak o versiyon taşınınca çalışır.

| Rol | Ne yapabilir |
| --- | --- |
| İzleyici (`VIEWER`) | Her şeyi görür, hiçbir şeyi değiştiremez |
| Düzenleyen (`ROUTE_EDITOR`) | Proje, subtask ve profil tanımlar; test eder; DEV'e doğrudan uygular, TEST ve PROD için taşıma talebi açar; kendi talebini iptal eder |
| Onaylayan (`APPROVER`) | Onay isteyen talepleri (PROD'a her taşıma, hangi ortamda olursa olsun anonim erişimin açılması) onaylar ya da reddeder; kendi açtığı talebi onaylayamaz |

Roller şirketin SSO'sundaki ya da yetkilendirme servisindeki gruplardan gelir; bir kullanıcı birden fazla role sahip olabilir. Ekran, hangi düğmelerin görüneceğine `GET /api/v1/me` yanıtındaki rollere bakarak karar verir; backend aynı kuralı ayrıca uygular.

| Ortam | Taşıma sırası | Onay | Test çağrısı |
| --- | --- | --- | --- |
| DEV | 1 | Yok; talep hemen uygulanır | Açık |
| TEST (PREPROD) | 2 | Yok; talep hemen uygulanır | Açık |
| PROD | 3 | Zorunlu, dört göz kuralıyla | Kapalı |

Ortamlar, sıraları, onay ve test ayarları sabit kodlanmaz; ekran bunları `GET /api/v1/environments` yanıtından okur.

## Ekran haritası

&#91;embedded content: ekran haritası · 4 bölüm, taşıma akışı vurgulu\]

Vurgulu oklar taşıma akışıdır: talep subtask ya da profil editöründen açılır ve Talepler ekranında onaylanır. Uygulanan değişikliğin broker'lara yayılımı Ortamlar ekranından izlenir.

## Ekranlar ve akışlar

Uygulama dört ana bölümden oluşur: Projeler, Yanıt profilleri, Talepler ve Ortamlar. Subtask editörü proje detayından açılır. Parantez içindeki yollar `/api/v1` altındadır; ayrıntıları Uç noktalar bölümündedir.

### Açılış

1. Kullanıcı şirketin SSO'suyla giriş yapar. Ekran aldığı access token'ı her istekte `Authorization: Bearer` header'ıyla gönderir.
2. Açılışta üç çağrı yapılır ve oturum boyunca saklanır: `GET /me` (kullanıcı ve roller), `GET /environments` (ortam sırası, onay ve test ayarları), `GET /meta/definition-rules` (enum'lar, sınırlar, okunabilir header'lar).
3. 401 gelirse token yenilenir ya da girişe dönülür. 403 gelirse işlem için rolün olmadığı gösterilir.

### Projeler

- **Liste:** Broker'a kayıtlı projeler (`GET /projects`). `UNLINKED` proje servisine bağlanmamış, `MISSING` proje serviste artık olmayan projedir; ikisi de rozetle gösterilir.
- **Kayıt:** "Proje ekle" proje servisinde arama açar (`GET /catalog/projects?query=`); kayıtlı olanlar işaretli gelir. Seçilen projenin adı forma dolar, DevOps `maintask`'i ve yetki önekini girer (`POST /projects`).
- **Değişmezlik:** Proje servisi ID'si, `maintask` ve önek kayıttan sonra değiştirilemez. Form bunu kaydetmeden önce açıkça söylemelidir.
- **Önek:** Büyük harfe çevrilerek girilir. Başka bir önekin başı ya da devamı olan önek (`HR` varken `HR_PORTAL`) 409 `PREFIX_OVERLAP` ile reddedilir.

### Proje detayı

Sekmeler: Genel, Ortamlar, Yetkiler, Subtask'ler ve Spec.

- **Genel:** Görünen ad, takım, açıklama ve varsayılan yanıt profili (`PATCH /projects/{id}`). Varsayılan profil yeni subtask formunda ön seçim olur.
- **Ortamlar:** Ortam başına base URL, spec yolu ve açık/kapalı (`PUT /projects/{id}/environments/{env}`). Kayıt o ortama hemen uygulanır; ortama ulaşılamazsa 502 `ENV_UNREACHABLE` döner ve hiçbir şey değişmez. Onay isteyen ortamda (PROD) bu ayarı yalnızca `APPROVER` değiştirebilir.
- **Yetkiler:** Projenin yetkileri seçilen ortamın yetkilendirme servisinden listelenir (`GET /projects/{id}/permissions?env=DEV`). "Yetki ekle" yalnızca son eki sorar, önek sabit gösterilir: `LOGPROJECT_` + `MODERATOR` (`POST /projects/{id}/permissions`). Her yetkinin yanında onu kullanan subtask sayısı durur; tıklanınca liste açılır (`GET /permissions/{code}/routes`).
- **Subtask'ler:** Liste (`GET /projects/{id}/routes`) ve her satırda ortam rozetleri: `DEV v4 · TEST v4 · PROD v3`. Rozet, güncel versiyonun aktif olduğunu, eski bir versiyonun aktif olduğunu, `DISABLED` olduğunu ya da hiç taşınmadığını ayırt etmelidir.

### Subtask editörü

Yeni ve mevcut subtask aynı formu kullanır (`POST /projects/{id}/routes`, `PUT /routes/{id}`). Bölümler:

1. **Kimlik:** Subtask adı (yalnızca yeni kayıtta girilir, snake\_case), HTTP method ve path şablonu.
2. **Yetki:** Projenin yetkilerinden çoklu seçim. "Başka projeden yetki ekle" önce projeyi, sonra o projenin yetkisini seçtirir; bu yetkiler `foreignPermissions`'a gider ve ayrı renkte gösterilir. "Anonim" anahtarı açılınca bu sürümün her ortama onayla taşınacağı uyarısı çıkar.
3. **Alanlar:** `fields` tablosu: ad (noktalı yol), tip, zorunlu, varsayılan.
4. **Eşleme:** Path şablonundaki her `{değişken}` için bir satır kendiliğinden açılır. Query, header ve body için anahtar-değer satırları vardır; değer alanına `${` yazılınca alanlar ve diğer yer tutucular önerilir. Body modu `NONE`, `PASS_THROUGH`, `TEMPLATE` ya da `MULTIPART`'tır; `TEMPLATE`'te JSON editörü açılır.
5. **Yanıt:** Tip (`JSON`, `TEXT`, `BINARY`) ve yanıt profili; profil boş bırakılabilir.
6. **Çalışma ve log:** Timeout, retry, log bayrakları, maskelenecek alanlar, en fazla body boyutu ve dosya yanıtları için `binaryInfo` metni.
7. **Değişiklik notu:** Kayıtla birlikte geçmişe yazılır.

- **Anlık doğrulama:** Form değiştikçe (yaklaşık 500 ms bekleyerek) `POST /routes/validate` çağrılır. Dönen `errors[].field` yolu forma eşlenir, örneğin `definition.request.pathVariables.id`. Aynı doğrulama kayıtta da çalışır.
- **Önizleme:** Örnek bir frontend gövdesiyle servise gidecek isteği (method, URL, header'lar, body) kaydetmeden gösterir (`POST /routes/preview`).
- **Test:** Yalnızca test çağrısına açık ortamlarda (DEV, TEST) görünür ve kaydedilmemiş tanımla gerçek istek atar (`POST /projects/{id}/test`). Sonuçta üretilen istek, servisin yanıtı (status, header'lar, body, süre), profil varsa frontend'in alacağı zarflı yanıt ve backend'in yorumu gösterilir.
- **Yoklama:** "Endpoint var mı?" düğmesi OPTIONS yoklaması yapar (`POST /projects/{id}/probe`): `OK`, `WRONG_METHOD`, `PATH_NOT_FOUND` ya da `INCONCLUSIVE`.
- **Çakışma:** Başkası aynı subtask'i kaydettiyse `PUT` 409 `VERSION_CONFLICT` döner. Ekran kullanıcının değişikliklerini kaybetmeden güncel hali yükler ve farkı gösterir.
- **JSON'dan içe aktar:** Yerel broker'ın `broker-local.json` dosyasındaki bir route forma yüklenir. `permissions` önekle ayrılır: projenin önekiyle başlayanlar proje yetkisine, diğerleri başka projeden yetkiye gider. `responseProfile` adı profil listesinden ID'ye çevrilir.

### İstekten şablon

1. DevOps tarayıcıdan kopyaladığı istek gövdesini ya da "Copy as cURL" çıktısını yapıştırır.
2. `POST /projects/{id}/templates/from-sample` maintask'i, subtask'i, alanları ve maskelenmiş örneği döner. Tip değerden çıkar, iç içe nesneler noktalı yola açılır; `X-Token`, `Cookie` ve `Authorization` atılır ve atılanlar listelenir.
3. Hedef endpoint spec'ten seçilince aynı çağrı `operation` ile tekrarlanır. Alanlar path, query ve body'ye adla eşlenir; eşleşmeyen alanlar ve karşılığı olmayan zorunlu servis parametreleri işaretli gelir.
4. "Forma aktar" taslağı editöre doldurur ve test panelini yapıştırılan örnekle açar.

Ham metin backend'de saklanmaz; ekran da onu tarayıcı deposuna (localStorage) yazmamalıdır.

### Spec karşılaştırma

`GET /projects/{id}/spec?env=DEV` servisin spec'ini çeker ve route'larla method + path üzerinden eşler.

| Grup | Ekranda |
| --- | --- |
| `NEW` | "Subtask olarak aç": editör spec'ten dolu (`proposal`) ve adı boş açılır |
| `CHANGED` | Kayıtlı ve güncel operasyonun farkı; "Tanımı güncelle" editörü açar |
| `MISSING` | Öne çıkarılır; bu route'lar o ortamda hata verecektir |
| `SAME` | Sade listede kalır |

### Geçmiş ve geri alma

- **Versiyonlar:** Versiyon, aksiyon, kim, ne zaman, not ve hangi ortamlarda aktif olduğu (`GET /routes/{id}/history`).
- **Karşılaştırma:** İki versiyonun farkı alan yolu bazında gelir (`GET /routes/{id}/compare?from=3&to=4`); başka projeden eklenen yetkiler ayrıca işaretlidir.
- **Geri alma:** "Bu versiyona dön" eski snapshot'ı yeni bir versiyon olarak yazar (`POST /routes/{id}/rollback`). Ortamlar etkilenmez; yeni versiyon normal akışla taşınır.
- **Arşiv:** Yalnızca hiçbir ortamda aktif olmayan subtask arşivlenir (`archive`) ve geri açılabilir (`restore`).

### Yanıt profilleri

- **Desen:** Liste, editör, geçmiş ve karşılaştırma subtask'lerle aynıdır (`/response-profiles`).
- **Editör:** `success` ve `error` için birer HTTP status ve JSON şablonu alır; `${backend.status}`, `${backend.body}`, `${outcome}` ve `${requestId}` önerilir.
- **Önizleme:** Örnek bir servis yanıtıyla (status ve body) frontend'in alacağı yanıtı gösterir (`POST /response-profiles/preview`).
- **Etki:** Detayda profili kullanan subtask'ler ortam bazında listelenir. Profil değişikliği taşındığı ortamda hepsini etkiler.

### Taşıma ve onay

- **Talep:** Subtask ya da profil ekranındaki "Taşı" penceresi hedef ortamı (yalnızca bir sonraki ortam), versiyonu (kaynak ortamda aktif olan) ve aksiyonu sorar (`POST /promotions`). Subtask'te `DEPLOY`, `DISABLE`, `ENABLE`, `ROLLBACK`; profilde `DEPLOY` ve `ROLLBACK` vardır. Onay isteyen taleplerde gerekçe ya da değişiklik kaydı numarası zorunludur.
- **Ön kontrol:** Başarısız olursa talep açılmaz. 422 `PRECHECK_FAILED` yanıtındaki `checks` listesi madde madde gösterilir, örneğin "yetki hedef ortamda yok".
- **Sonuç:** Onay gerekmiyorsa yanıt talebin son durumunu taşır: `APPLIED` ya da `FAILED`. Onay gerekiyorsa talep `PENDING_APPROVAL` olur.
- **Talepler listesi:** Durum, hedef ortam, proje ve "benim taleplerim" filtreleri (`GET /promotions`). Onaylayanın varsayılan görünümü onay bekleyenlerdir.
- **Talep detayı:** Hedef ortamdaki mevcut versiyonla fark, yoklama sonucu ve profilde onu kullanan route'lar. Başka projeden eklenen yetkiler ve anonim erişim ayrı bir uyarı kutusunda gösterilir.
- **Düğmeler:** Yanıttaki `allowedActions` listesine göre gösterilir: `APPROVE`, `REJECT`, `CANCEL`, `RETRY`. Talep eden kendi talebinde onay düğmesini görmez; backend de 403 `FOUR_EYES` döner.
- **Yayılım:** `APPLIED` sonrası ekran ortam durumunu birkaç saniye arayla sorar (`GET /environments/{env}/status`) ve "3/3 instance v43'te" gösterir. Bir dakikayı aşan gecikme uyarı olarak gösterilir.

&#91;embedded content: talep yaşam döngüsü · 7 durum\]

Onay gerekmeyen ortamda talep doğrudan `APPLYING` olur. `APPROVED` yalnızca bir an görünür, çünkü uygulama onaydan hemen sonra başlar. `APPLYING`'de kalan bir talep de `RETRY` ile yeniden uygulanabilir; ortam aynı talebi ikinci kez yazmaz.

### Ortamlar

Ortam başına config versiyonu ve her broker instance'ının yüklediği versiyon gösterilir (`GET /environments/{env}/status`). Geride kalan instance uyarı rengiyle işaretlenir.

## API'nin genel kuralları

Backend REST ve JSON konuşur; bütün yollar `/api/v1` altındadır ve alan adları camelCase'tir. Swagger arayüzü `/swagger-ui.html`, OpenAPI tanımı `/v3/api-docs` adresindedir.

- **Kimlik:** Her istek `Authorization: Bearer <SSO access token>` taşır. Token yoksa ya da geçersizse 401, rol yetmiyorsa 403 döner. `ROUTE_EDITOR` ve `APPROVER` rolleri okuma iznini de kapsar.
- **Yerel geliştirme:** Backend `local` profiliyle çalışınca SSO yerine HTTP Basic kabul eder. Hazır kullanıcılar `viewer`, `editor`, `approver` ve üç role birden sahip `lead`'dir; şifre kullanıcı adıyla aynıdır. Dört göz kuralı `lead` ile talep açıp `approver` ile onaylayarak denenir.
- **CORS:** İzinli adresler `broker.admin.cors.allowed-origins` ayarındadır; yerelde varsayılan `http://localhost:5173`'tür.
- **Zaman:** ISO 8601 ve UTC: `2026-10-13T13:04:00Z`.
- **Kimlikler:** Sayısal ID'ler JSON number olarak gelir.
- **Büyük/küçük harf:** maintask ve subtask tekliliği harf duyarsızdır; `LogProject` varken `logproject` 409 `DUPLICATE` alır. Ekran her zaman kayıtlı yazımı gösterir.
- **İyimser kilit:** Subtask ve profil kayıtları `version` taşır. Güncelleme isteği okuduğu `version`'ı geri gönderir; arada başkası kaydettiyse 409 `VERSION_CONFLICT` ve `currentVersion` döner.
- **Sayfalama:** Yalnızca talepler listesi sayfalıdır: `?page=0&size=20` → `{"items": [...], "page": 0, "size": 20, "totalItems": 134}`. Diğer listeler düz dizi döner.
- **Uzun süren çağrılar:** Test, yoklama, spec ve taşıma çağrıları ortama gider ve birkaç saniye sürebilir. Ekran bekleme göstergesi gösterir ve bu çağrılar için 30 saniye bekler.

### Hata formatı

Hatalar RFC 9457 problem detail olarak (`application/problem+json`) döner. `code` ekranın karar vermesi içindir, `detail` kullanıcıya gösterilebilecek Türkçe metindir; alan hataları `errors` listesindedir.

```json
{
  "type": "about:blank",
  "title": "Doğrulama hatası",
  "status": 400,
  "detail": "Tanım kaydedilemedi: 2 alan hatalı.",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "definition.request.pathVariables.id", "message": "Path şablonundaki {id} eşlenmedi" },
    { "field": "subtask", "message": "snake_case olmalı, örneğin get_order" }
  ]
}
```

| code | HTTP | Ne zaman |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Gövde ya da tanım kurallara uymuyor; `errors` alan bazında gelir |
| `UNAUTHENTICATED` | 401 | Token yok ya da geçersiz |
| `FORBIDDEN` | 403 | Kullanıcının rolü işlem için yetmiyor |
| `FOUR_EYES` | 403 | Talep eden kendi talebini onaylamaya ya da reddetmeye çalıştı |
| `TEST_NOT_ALLOWED` | 403 | Ortam test çağrısına kapalı |
| `NOT_FOUND` | 404 | Kayıt yok |
| `VERSION_CONFLICT` | 409 | Kayıt arada değişti; `currentVersion` döner |
| `DUPLICATE` | 409 | maintask, subtask, profil adı ya da proje servisi ID'si zaten kayıtlı |
| `PREFIX_OVERLAP` | 409 | Yetki öneki başka bir önekle çakışıyor |
| `INVALID_STATE` | 409 | İşlem kaydın durumuna uymuyor, örneğin uygulanmış talebi iptal etmek |
| `ROUTE_DEPLOYED` | 409 | Bir ortamda aktif olan subtask arşivlenmek istendi |
| `PRECHECK_FAILED` | 422 | Taşıma ön kontrolü geçmedi; `checks` listesi döner |
| `ENV_UNREACHABLE` | 502 | Ortamın Admin API'sine ulaşılamadı |
| `UPSTREAM_ERROR` | 502 | Proje servisi, yetkilendirme servisi ya da servisin spec'i hata verdi |

## Uç noktalar

Tüm yollar `/api/v1` altındadır. Rol sütunu en az gereken roldür; şemaların tamamı Swagger arayüzündedir, aşağıdaki örnekler en çok kullanılan gövdeleri gösterir.

### Oturum ve ortamlar

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/me` | VIEWER | Kullanıcı adı, görünen ad ve roller |
| GET | `/environments` | VIEWER | Ortamlar, sıraları, onay ve test ayarları |
| GET | `/environments/{env}/status` | VIEWER | Config versiyonu ve instance'ların yüklediği versiyonlar |
| GET | `/meta/definition-rules` | VIEWER | Enum'lar, sınırlar, desenler, okunabilir ve yasak header'lar |

### Projeler ve yetkiler

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/catalog/projects?query=` | VIEWER | Proje servisinde arama; kayıtlı olanlarda `registeredProjectId` dolu |
| GET | `/projects?query=&catalogStatus=` | VIEWER | Kayıtlı projeler |
| POST | `/projects` | ROUTE\_EDITOR | Projeyi kaydeder |
| GET | `/projects/{id}` | VIEWER | Proje ve ortam ayarları |
| PATCH | `/projects/{id}` | ROUTE\_EDITOR | Ad, takım, açıklama, varsayılan profil; `UNLINKED` projeyi proje servisine bağlama |
| PUT | `/projects/{id}/environments/{env}` | ROUTE\_EDITOR (onaylı ortamda APPROVER) | Base URL, spec yolu, açık/kapalı; ortama hemen uygulanır |
| GET | `/projects/{id}/permissions?env=` | VIEWER | Projenin yetkileri ve kullanan subtask sayısı |
| POST | `/projects/{id}/permissions` | ROUTE\_EDITOR | Son ekle yeni yetki açar |
| GET | `/permissions/{code}/routes` | VIEWER | Yetkiyi kullanan subtask'ler, `OWN` ya da `FOREIGN` |

### Subtask'ler

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/projects/{id}/routes?status=&query=` | VIEWER | Projenin subtask'leri ve ortam dağılımları |
| POST | `/projects/{id}/routes` | ROUTE\_EDITOR | Yeni subtask, versiyon 1 |
| GET | `/routes/{id}` | VIEWER | Tam tanım ve ortam dağılımları |
| PUT | `/routes/{id}` | ROUTE\_EDITOR | Yeni versiyon; gövdede okunan `version` |
| POST | `/routes/{id}/archive`, `/restore` | ROUTE\_EDITOR | Arşivler ya da geri açar |
| POST | `/routes/{id}/rollback` | ROUTE\_EDITOR | Eski versiyonu yeni versiyon olarak yazar |
| GET | `/routes/{id}/history` | VIEWER | Versiyon listesi |
| GET | `/routes/{id}/history/{version}` | VIEWER | Versiyonun tam snapshot'ı |
| GET | `/routes/{id}/compare?from=&to=` | VIEWER | İki versiyonun farkı |
| POST | `/routes/validate` | VIEWER | Kaydetmeden doğrular |
| POST | `/routes/preview` | VIEWER | Örnek gövdeyle servise gidecek isteği üretir |

### Spec, şablon, test ve yoklama

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/projects/{id}/spec?env=` | VIEWER | Spec operasyonları ve route'larla farkı |
| POST | `/projects/{id}/templates/from-sample` | ROUTE\_EDITOR | Yapıştırılan istekten taslak tanım |
| POST | `/projects/{id}/test` | ROUTE\_EDITOR | Kaydedilmemiş tanımla gerçek istek; yalnızca test açık ortamda |
| POST | `/projects/{id}/probe` | ROUTE\_EDITOR | OPTIONS yoklaması |

### Yanıt profilleri

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/response-profiles` | VIEWER | Profiller ve ortam dağılımları |
| POST | `/response-profiles` | ROUTE\_EDITOR | Yeni profil |
| GET | `/response-profiles/{id}` | VIEWER | Tam tanım ve profili kullanan subtask'ler |
| PUT | `/response-profiles/{id}` | ROUTE\_EDITOR | Yeni versiyon; gövdede okunan `version` |
| POST | `/response-profiles/{id}/rollback` | ROUTE\_EDITOR | Eski versiyonu yeni versiyon olarak yazar |
| GET | `/response-profiles/{id}/history`, `/history/{version}`, `/compare` | VIEWER | Subtask'lerle aynı |
| POST | `/response-profiles/preview` | VIEWER | Örnek servis yanıtıyla zarflanmış yanıt |

### Taşıma

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| POST | `/promotions` | ROUTE\_EDITOR | Talep açar; onay gerekmiyorsa hemen uygular |
| GET | `/promotions?status=&targetEnv=&subjectType=&projectId=&mine=&page=&size=` | VIEWER | Talepler, sayfalı |
| GET | `/promotions/{id}` | VIEWER | Detay, fark ve `allowedActions` |
| POST | `/promotions/{id}/approve` | APPROVER | Onaylar ve uygular |
| POST | `/promotions/{id}/reject` | APPROVER | Reddeder; `note` zorunlu |
| POST | `/promotions/{id}/cancel` | ROUTE\_EDITOR | Talep eden, onay beklerken iptal eder |
| POST | `/promotions/{id}/retry` | ROUTE\_EDITOR | `FAILED` ya da `APPLYING`'de kalan talebi yeniden uygular |

### Örnek: subtask kaydı

`POST /projects/12/routes` gövdesi. `PUT /routes/{id}` aynı gövdeyi `subtask` olmadan, okunan `version` ile alır.

```json
{
  "subtask": "add_order_note",
  "httpMethod": "POST",
  "pathTemplate": "/orders/{id}/notes",
  "permissions": ["ORDER_WRITE"],
  "foreignPermissions": ["CRM_AGENT"],
  "anonymous": false,
  "responseProfileId": 3,
  "definition": {
    "request": {
      "fields": [
        { "name": "siparisNo", "type": "integer", "required": true },
        { "name": "notMetni", "type": "string", "required": true },
        { "name": "bildir", "type": "boolean", "default": false }
      ],
      "pathVariables": { "id": "${request.siparisNo}" },
      "queryParams": { "notify": "${request.bildir}" },
      "headers": { "X-Channel": "WEB" },
      "body": {
        "mode": "TEMPLATE",
        "contentType": "application/json",
        "template": { "text": "${request.notMetni}", "authorId": "${user.id}" }
      }
    },
    "response": { "type": "JSON" },
    "timeoutMs": 5000,
    "retry": { "maxAttempts": 1 },
    "logging": { "logRequestBody": true, "logResponseBody": false, "maskFields": ["password"] }
  },
  "comment": "Siparişe not ekleme"
}
```

Yanıt aynı alanların yanında `id`, `projectId`, `maintask`, `version`, `status`, `responseProfile: {id, name}`, oluşturan ve güncelleyen bilgisi ile `deployments` listesini taşır: `[{"environment": "DEV", "version": 4, "status": "ACTIVE", "activatedBy": "devops.ali", "activatedAt": "…"}]`.

### Örnek: test sonucu

`POST /projects/12/test` gövdesi `environment`, kaydedilmemiş tanım (`httpMethod`, `pathTemplate`, `responseProfileId`, `definition`), örnek frontend gövdesi (`sample`) ve isteğe bağlı `headers` ile `userId` alır. `/routes/preview` aynı gövdeyi alır ve yalnızca `request` ile `errors` döner.

```json
{
  "request": {
    "method": "POST",
    "url": "http://order-service.dev.svc:8080/orders/981/notes?notify=false",
    "headers": { "X-Channel": "WEB", "X-uid": "devops.ali", "Content-Type": "application/json" },
    "body": { "text": "Kapıya bırakın", "authorId": "devops.ali" }
  },
  "response": { "status": 201, "headers": { "Content-Type": ["application/json"] }, "body": { "noteId": 55 }, "durationMs": 87 },
  "clientResponse": { "status": 200, "body": { "BACKEND_STATUS": 201, "status": 200, "data": { "noteId": 55 } } },
  "outcome": "SUCCESS",
  "interpretation": "Çalışıyor",
  "errors": []
}
```

Gövde `fields` tanımına uymazsa istek gönderilmez: `outcome` `INVALID_REQUEST` olur, `request` ve `response` boş gelir, `errors` alan bazında dolar. Tanımın kendisi hatalıysa çağrı 400 `VALIDATION_FAILED` döner.

### Örnek: taşıma talebi

`POST /promotions` gövdesi; profil için `subjectType` `PROFILE`'dır:

```json
{ "subjectType": "ROUTE", "subjectId": 17, "version": 4, "targetEnv": "PROD", "action": "DEPLOY", "reason": "CHG-1234" }
```

`GET /promotions/{id}` yanıtı (liste öğeleri `diff` ve `affectedRoutes` olmadan aynı alanları taşır):

```json
{
  "id": 1042,
  "subjectType": "ROUTE",
  "subjectId": 17,
  "subjectName": "order-service / get_order",
  "version": 4,
  "sourceEnv": "TEST",
  "targetEnv": "PROD",
  "action": "DEPLOY",
  "status": "PENDING_APPROVAL",
  "requiresApproval": true,
  "requestedBy": "devops.ali",
  "requestedAt": "2026-10-13T12:00:00Z",
  "reason": "CHG-1234",
  "decidedBy": null,
  "decidedAt": null,
  "decisionNote": null,
  "probeStatus": null,
  "configVersion": null,
  "appliedAt": null,
  "failureReason": null,
  "diff": {
    "deployedVersion": 3,
    "changes": [ { "path": "definition.timeoutMs", "before": 5000, "after": 8000 } ],
    "foreignPermissionsAdded": ["CRM_AGENT"],
    "opensAnonymous": false
  },
  "affectedRoutes": [],
  "allowedActions": ["APPROVE", "REJECT"]
}
```

Ön kontrol geçmezse talep açılmaz ve 422 döner:

```json
{
  "status": 422,
  "code": "PRECHECK_FAILED",
  "detail": "Talep açılamadı: 1 kontrol geçmedi.",
  "checks": [
    { "check": "SOURCE_ACTIVE", "ok": true, "message": "v4 TEST'te aktif" },
    { "check": "PERMISSIONS_EXIST", "ok": false, "message": "PROD yetkilendirme servisinde yok: ORDER_WRITE" }
  ]
}
```

## Alan kuralları ve sabit değerler

Ekran bu kuralları `GET /meta/definition-rules` yanıtından okur ve kendi içinde sabit kodlamaz; backend aynı kuralları kayıtta yeniden uygular.

### Adlar

| Alan | Kural | Örnek |
| --- | --- | --- |
| maintask | 1–100 karakter; harf ya da rakamla başlar, harf, rakam, `_`, `.` ve `-` içerir | `logProject`, `order-service` |
| subtask | snake\_case, en fazla 150 karakter: `^[a-z][a-z0-9]*(_[a-z0-9]+)*$` | `get_order` |
| permissionPrefix | En fazla 50 karakter: `^[A-Z][A-Z0-9_]*[A-Z0-9]$` | `LOGPROJECT` |
| Yetki son eki | `^[A-Z0-9]+(_[A-Z0-9]+)*$`; yetki = önek + `_` + son ek, en fazla 100 karakter | `MODERATOR` → `LOGPROJECT_MODERATOR` |
| Profil adı | maintask ile aynı kural | `log-project` |
| pathTemplate | `/` ile başlar, `{ad}` değişkenleri içerebilir, en fazla 512 karakter | `/orders/{id}/notes` |

### Tanım (`definition`)

| Alan | Değerler | Kural |
| --- | --- | --- |
| `request.fields[]` | `name`, `type`, `required`, `default` | `name` noktalı yoldur (`adres.il`). `type`: string, integer, number, boolean, object, array. Zarf alanları (`maintask`, `subtask`, `application`, `uid`) tanımlanmaz |
| `request.pathVariables` | ad → değer | Şablondaki her `{ad}` tam bir kez eşlenir; değer zorunlu ya da varsayılanı olan alana bağlanır |
| `request.queryParams` | ad → değer | Değeri olmayan parametre gönderilmez |
| `request.headers` | ad → değer | `Authorization`, `X-User-*`, `X-uid`, `Host`, `Content-Length`, `Connection`, `Transfer-Encoding` tanımlanamaz |
| `request.body.mode` | `NONE`, `PASS_THROUGH`, `TEMPLATE`, `MULTIPART` | GET ve DELETE'te yalnızca `NONE`. `PASS_THROUGH` zarf alanları dışındaki gövdeyi olduğu gibi gönderir |
| `request.body.contentType` | Metin | `TEMPLATE`'te varsayılan `application/json` |
| `request.body.template` | JSON | Yalnızca `TEMPLATE`'te; tek bir yer tutucu da olabilir: `"${request.payload}"` |
| `request.body.parts[]` | `name`, `value`, `file` | Yalnızca `MULTIPART`'ta. `file: true` olan parçanın değeri `{fileName, contentType, base64}` nesnesine bağlanır |
| `response.type` | `JSON`, `TEXT`, `BINARY` | `BINARY` stream edilir; body loglanmaz, profil uygulanmaz |
| `timeoutMs` | 100–60000 | Varsayılan 5000 |
| `retry.maxAttempts` | 1–3 | 1'den büyükse method GET olmalı |
| `logging.logRequestBody`, `logResponseBody` | true, false | Varsayılan sırasıyla true ve false |
| `logging.maskFields` | Alan adları | `password` her zaman maskelenir; listedekiler ona eklenir |
| `logging.maxBodyBytes` | 1–262144 | Varsayılan 65536; yalnızca yanıt body'si için |
| `logging.binaryInfo` | Metin | Dosya yanıtında Elastic'e yazılan `info` mesajı |

### Yer tutucular

Değeri yalnızca bir yer tutucudan oluşan alan değerin tipini korur: `"${request.siparisNo}"` sayı olarak gider. Metnin içindeki yer tutucu metne çevrilir. İfade dili ya da script yoktur.

| Yer tutucu | Nerede | Değer |
| --- | --- | --- |
| `${request.<yol>}` | Tanım | Frontend gövdesindeki alan; `fields`'ta tanımlı olmalı |
| `${header.<ad>}` | Tanım | Frontend isteğinin header'ı; yalnızca `readableHeaders` listesindekiler |
| `${user.id}` | Tanım | Broker'ın oturumdan doğruladığı kullanıcı; gövdedeki `uid` değil |
| `${requestId}` | Tanım, profil | İsteğin ID'si |
| `${backend.status}` | Profil | Servisin HTTP status kodu; timeout'ta null |
| `${backend.body}` | Profil | Servisin yanıt gövdesi; `error` şablonunda yalnızca 4xx'te dolu |
| `${outcome}` | Profil | `SUCCESS`, `BUSINESS_ERROR`, `TECHNICAL_ERROR`, `TIMEOUT` |

### Durumlar ve aksiyonlar

| Alan | Değerler |
| --- | --- |
| Subtask durumu | `ACTIVE`, `ARCHIVED` |
| Ortamdaki durum (`deployments[].status`) | `ACTIVE`, `DISABLED` |
| `catalogStatus` | `LINKED`, `MISSING`, `UNLINKED` |
| Subtask geçmişi aksiyonu | `CREATE`, `UPDATE`, `ARCHIVE`, `RESTORE`, `ROLLBACK` |
| Profil geçmişi aksiyonu | `CREATE`, `UPDATE`, `ROLLBACK` |
| Talep aksiyonu | `DEPLOY`, `DISABLE`, `ENABLE`, `ROLLBACK`; profilde yalnızca `DEPLOY` ve `ROLLBACK` |
| Talep durumu | `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `APPLYING`, `APPLIED`, `FAILED`, `CANCELLED` |
| `allowedActions` | `APPROVE`, `REJECT`, `CANCEL`, `RETRY` |
| Yoklama sonucu | `OK`, `WRONG_METHOD`, `PATH_NOT_FOUND`, `INCONCLUSIVE` |
| Outcome | `SUCCESS`, `BUSINESS_ERROR`, `TECHNICAL_ERROR`, `TIMEOUT`, `DENIED`, `UNKNOWN_TASK`, `INVALID_REQUEST`, `LOG_UNAVAILABLE` |

### Ön kontroller

| `checks[].check` | Neye bakar |
| --- | --- |
| `SUBJECT_ACTIVE` | Subtask arşivde değil |
| `NEXT_ENVIRONMENT` | Hedef ortam, kaynak ortamın hemen sonrası |
| `SOURCE_ACTIVE` | Versiyon kaynak ortamda aktif; DEV'e taşımada aranmaz |
| `NO_OPEN_REQUEST` | Aynı subtask ya da profil ve hedef ortam için açık talep yok |
| `PERMISSIONS_EXIST` | Yetkiler hedef ortamın yetkilendirme servisinde tanımlı |
| `PROFILE_ACTIVE` | Subtask'in yanıt profili hedef ortamda aktif |
| `PROJECT_ENABLED` | Proje hedef ortamda tanımlı ve açık |
| `DEPLOYED_IN_TARGET` | `DISABLE` ve `ENABLE`'da subtask hedef ortamda var |
| `PREVIOUSLY_APPLIED` | `ROLLBACK`'te versiyon hedef ortama daha önce uygulanmış |

### Broker'ın uygulamalara döndüğü yanıtlar

Test panelindeki `clientResponse` bu kurallarla üretilir; uygulama frontend'leri de aynısını alır.

- **İz:** Her yanıtta `X-Request-Id` header'ı vardır; Elastic'teki ana dokümanın `_id`'si de budur.
- **Log yazılamazsa:** Servis yanıt vermiş olsa bile `500` ve `{"result": false}` döner.
- **Dosya yanıtı:** Servis `Content-Disposition` döndürürse dosya olduğu gibi stream edilir, profil uygulanmaz.
- **Broker hataları:** Servise gitmeden verilen hatalar ortak formattadır:

```json
{
  "result": false,
  "status": 403,
  "outcome": "DENIED",
  "message": "Bu işlem için yetkiniz yok.",
  "requestId": "0192f1c4-7a3b-7c1e-9d2f-5b8e4a1c3d20",
  "errors": []
}
```

| Durum | HTTP | outcome |
| --- | --- | --- |
| `X-Token` header'ı yok | 400 | `INVALID_REQUEST` |
| Oturum bulunamadı | 401 | `DENIED` |
| Kullanıcıda route'un yetkilerinden hiçbiri yok | 403 | `DENIED` |
| maintask/subtask kayıtlı değil | 404 | `UNKNOWN_TASK` |
| Gövde `fields` tanımına uymuyor | 400 | `INVALID_REQUEST`; `errors` alan bazında |

## Değişiklik kaydı

Backend geliştirildikçe bu tablo güncellenir; en yeni kayıt en üsttedir. Bir uç nokta uygulandığında ya da sözleşmesi değiştiğinde burada ayrı bir satır olarak görünür.

| Tarih | Değişiklik |
| --- | --- |
| 2026-10-13 | İlk sürüm: ekranlar, API sözleşmesi, alan kuralları ve hata kodları. Backend geliştirme aşamasında; uç noktalar henüz çalışan bir sunucuda yok |

### Netleşmemiş noktalar

- [ ] **SSO:** Hangi OIDC sağlayıcısı kullanılıyor ve roller token'ın hangi claim'inde geliyor?
- [ ] **Yetkilendirme servisi:** Yetki listeleme, açma ve varlık kontrolü için gerçek uçlar netleşince `Yetkiler` sekmesi ona göre doğrulanacak.
- [ ] **PROD base URL'i:** Şimdilik yalnızca `APPROVER` değiştirebiliyor; ileride onay akışına alınabilir.
- [ ] **Acil durum:** PROD'da `DISABLE` ve `ROLLBACK` de onay istiyor; tasarım dokümanında açık bir karar.
