# Yapi Admin — Frontend Rehberi

Oct 26, 2026 · @Yusuf

Bu rehber, broker yönetim ekranlarını geliştirecek frontend için ekranları, akışları ve backend API sözleşmesini anlatır. Backend değiştikçe güncellenir; değişiklikler en alttaki kayıtta listelenir.

## Genel bakış

Yapi Admin, DevOps'un subtask'leri, yetkileri ve yanıt profillerini kod yazmadan tanımladığı ve DEV → PREPROD → PROD sırasıyla taşıdığı yönetim ekranıdır. Ekran yalnızca merkezi admin backend'iyle (`yapi-admin`) konuşur; ortamlara, broker'lara ve proje servisine backend bağlanır.

- **Ekrandan yapılanlar:** Proje kaydı ve ortam adresleri, projenin yetkilerini görmek, subtask tanımı (servisin spec'inden, yapıştırılan bir istekten ya da elle), yanıt profilleri, test çağrısı, versiyon geçmişi ve geri alma, taşıma talebi ve onay.
- **Ekranın dışında kalanlar:** Yetki açmak ve kullanıcılara atamak Koordinatör'de (yetki projesi) kalır. Uygulamaların broker trafiği ve logları bu ekrandan geçmez.
- **Temel kural:** Bir subtask'i değiştirmek yalnızca yeni bir versiyon üretir. Değişiklik bir ortamda, ancak o versiyon taşınınca çalışır.

| Rol | Ne yapabilir |
| --- | --- |
| İzleyici (`VIEWER`) | Her şeyi görür, hiçbir şeyi değiştiremez |
| Düzenleyen (`ROUTE_EDITOR`) | Proje, subtask ve profil tanımlar; test eder; DEV'e doğrudan uygular, PREPROD ve PROD için taşıma talebi açar; kendi talebini iptal eder |
| Onaylayan (`APPROVER`) | Onay isteyen talepleri (PROD'a her taşıma, hangi ortamda olursa olsun anonim erişimin açılması) onaylar ya da reddeder; kendi açtığı talebi onaylayamaz |

Roller, kullanıcının şirketin giriş uygulamasındaki oturumunda taşıdığı Yapi yetkilerinden gelir: `YAPI_DEVELOPER` İzleyici ve Düzenleyen, `YAPI_ADMIN` üç rolü de verir (eşleme backend ayarındadır; yetki adları değişebilir). Bir kullanıcı birden fazla role sahip olabilir. Ekran, hangi düğmelerin görüneceğine `GET /api/v1/me` yanıtındaki rollere bakarak karar verir; backend aynı kuralı ayrıca uygular.

| Ortam | Taşıma sırası | Onay | Test çağrısı |
| --- | --- | --- | --- |
| DEV | 1 | Yok; talep hemen uygulanır | Açık |
| PREPROD | 2 | Yok; talep hemen uygulanır | Açık |
| PROD | 3 | Zorunlu, dört göz kuralıyla | Kapalı |

Ortamlar, sıraları, onay ve test ayarları sabit kodlanmaz; ekran bunları `GET /api/v1/environments` yanıtından okur.

## Ekran haritası

&#91;embedded content: ekran haritası · 4 bölüm, taşıma akışı vurgulu\]

Vurgulu oklar taşıma akışıdır: talep subtask ya da profil editöründen açılır ve Talepler ekranında onaylanır. Uygulanan değişikliğin broker'lara yayılımı Ortamlar ekranından izlenir.

## Ekranlar ve akışlar

Uygulama dört ana bölümden oluşur: Projeler, Yanıt profilleri, Talepler ve Ortamlar. Subtask editörü proje detayından açılır. Parantez içindeki yollar `/api/v1` altındadır; ayrıntıları Uç noktalar bölümündedir.

### Açılış

1. Kullanıcı şirketin giriş uygulamasından girer. Ekran her istekte o oturumun dört header'ını gönderir: `X-Token`, `X-Uid` (sicil), `X-Clientip` ve `X-User-Agent` (broker'a giden isteklerdeki gibi). `X-Clientip`'i ekranın önündeki proxy koyar.
2. Açılışta üç çağrı yapılır ve oturum boyunca saklanır: `GET /me` (kullanıcı ve roller), `GET /environments` (ortam sırası, onay ve test ayarları), `GET /meta/definition-rules` (enum'lar, sınırlar, okunabilir header'lar).
3. 401 gelirse oturum yok ya da geçersizdir; giriş uygulamasına dönülür. 403 gelirse işlem için rolün olmadığı gösterilir.

### Projeler

- **Liste:** Yapi'ye kayıtlı projeler (`GET /projects`). `UNLINKED` Koordinatör'deki bir projeye bağlanmamış, `MISSING` Koordinatör'de artık olmayan (ya da Yapi projesi olmaktan çıkmış) projedir; ikisi de rozetle gösterilir.
- **Kayıt:** "Proje ekle" Koordinatör'ün Yapi projelerinde (`is_yapi`) arama açar (`GET /catalog/projects?query=`); kayıtlı olanlar işaretli gelir. Seçilen projenin adı forma dolar, DevOps `maintask`'i ve yetki önekini girer (`POST /projects`).
- **Değişmezlik:** Koordinatör proje ID'si, `maintask` ve önek kayıttan sonra değiştirilemez. Form bunu kaydetmeden önce açıkça söylemelidir.
- **Önek:** Büyük harfe çevrilerek girilir. Başka bir önekin başı ya da devamı olan önek (`HR` varken `HR_PORTAL`) 409 `PREFIX_OVERLAP` ile reddedilir.

### Proje detayı

Sekmeler: Genel, Ortamlar, Yetkiler, Subtask'ler ve Spec.

- **Genel:** Görünen ad, takım, açıklama ve varsayılan yanıt profili (`PATCH /projects/{id}`). Varsayılan profil yeni subtask formunda ön seçim olur.
- **Ortamlar:** Ortam başına base URL, spec yolu, açık/kapalı ve alarm route'u (`PUT /projects/{id}/environments/{env}`). Alarm route'u, alarm kontrolü `INHERIT` olan subtask'lerin o ortamda gittiği alarm servisi route'udur; seçim listesi bütün projelerdeki alarm servisi subtask'leridir (`GET /routes?alarmService=true`), boş bırakılırsa o ortamda bu subtask'lere alarm kontrolü yapılmaz. Kayıt o ortama hemen uygulanır; ortama ulaşılamazsa 502 `ENV_UNREACHABLE` döner ve hiçbir şey değişmez. Onay isteyen ortamda (PROD) bu ayarı yalnızca `APPROVER` değiştirebilir.
- **Yetkiler:** Projenin yetkileri seçilen ortamın Koordinatör'ünden listelenir (`GET /projects/{id}/permissions?env=DEV`): Koordinatör'deki projesinin yetki listesi (`projeId` eşleşmesi); Koordinatör'e bağlı olmayan (`UNLINKED`) projede liste boştur. Yetkiler Koordinatör'de açılır; ekranda "yetki ekle" yoktur. Her yetkinin yanında onu kullanan subtask sayısı durur; tıklanınca liste açılır (`GET /permissions/{code}/routes`).
- **Subtask'ler:** Liste (`GET /projects/{id}/routes`) ve her satırda ortam rozetleri: `DEV v4 · PREPROD v4 · PROD v3`. Rozet, güncel versiyonun aktif olduğunu, eski bir versiyonun aktif olduğunu, `DISABLED` olduğunu ya da hiç taşınmadığını ayırt etmelidir.

### Subtask editörü

Yeni ve mevcut subtask aynı formu kullanır (`POST /projects/{id}/routes`, `PUT /routes/{id}`). Bölümler:

1. **Kimlik:** Subtask adı (yalnızca yeni kayıtta girilir, snake\_case), HTTP method ve path şablonu.
2. **Yetki:** Projenin yetkilerinden çoklu seçim. "Başka projeden yetki ekle" önce projeyi, sonra o projenin yetkisini seçtirir; bu yetkiler `foreignPermissions`'a gider ve ayrı renkte gösterilir. "Anonim" anahtarı açılınca bu sürümün her ortama onayla taşınacağı uyarısı çıkar.
3. **Alanlar:** `fields` tablosu: ad (noktalı yol), tip, zorunlu, varsayılan.
4. **Eşleme:** Path şablonundaki her `{değişken}` için bir satır kendiliğinden açılır. Query, header ve body için anahtar-değer satırları vardır; değer alanına `${` yazılınca alanlar ve diğer yer tutucular önerilir. Body modu `NONE`, `PASS_THROUGH`, `TEMPLATE` ya da `MULTIPART`'tır; `TEMPLATE`'te JSON editörü açılır.
5. **Yanıt:** Tip (`JSON`, `TEXT`, `BINARY`) ve yanıt profili; profil boş bırakılabilir.
6. **Alarm:** "Alarm kontrolü" seçimi: projenin ortam ayarındaki alarm route'u (`INHERIT`, varsayılan), kapalı (`OFF`) ya da seçilen bir alarm servisi route'u (`ROUTE`, maintask ve subtask). "Bu bir alarm servisi" anahtarı açılınca subtask alarm servisi olur: alarm listesinin yanıttaki yolu (`resultPath`) girilir, gövde şablonunda `${alarm.values}` önerilir ve alarm kontrolü seçimi gizlenir. Alarm servisi frontend'den çağrılamaz.
7. **Çalışma ve log:** Timeout, retry, istek gövdesinin loglanması, "Yanıt verisi loglansın" seçeneği (`logResponseBody`, varsayılan kapalı), maskelenecek alanlar, en fazla body boyutu ve dosya yanıtları için `binaryInfo` metni. "Yanıt verisi loglansın" açılınca editör, yanıttaki kişisel verilerin maskelenecek alanlara eklenmesini hatırlatır.
8. **Değişiklik notu:** Kayıtla birlikte geçmişe yazılır.

- **Anlık doğrulama:** Form değiştikçe (yaklaşık 500 ms bekleyerek) `POST /routes/validate` çağrılır. Dönen `errors[].field` yolu forma eşlenir, örneğin `definition.request.pathVariables.id`. Aynı doğrulama kayıtta da çalışır.
- **Önizleme:** Örnek bir frontend gövdesiyle servise gidecek isteği (method, URL, header'lar, body) kaydetmeden gösterir (`POST /routes/preview`).
- **Test:** Yalnızca test çağrısına açık ortamlarda (DEV, PREPROD) görünür ve kaydedilmemiş tanımla gerçek istek atar (`POST /projects/{id}/test`). Sonuçta üretilen istek, servisin yanıtı (status, header'lar, body, süre), profil varsa frontend'in alacağı zarflı yanıt ve backend'in yorumu gösterilir.
- **Yoklama:** "Endpoint var mı?" düğmesi OPTIONS yoklaması yapar (`POST /projects/{id}/probe`): `OK`, `WRONG_METHOD`, `PATH_NOT_FOUND` ya da `INCONCLUSIVE`.
- **Çakışma:** Başkası aynı subtask'i kaydettiyse `PUT` 409 `VERSION_CONFLICT` döner. Ekran kullanıcının değişikliklerini kaybetmeden güncel hali yükler ve farkı gösterir.
- **JSON'dan içe aktar:** Yerel broker'ın `yapi-local.json` dosyasındaki bir route forma yüklenir. `permissions` önekle ayrılır: projenin önekiyle başlayanlar proje yetkisine, diğerleri başka projeden yetkiye gider. `responseProfile` adı profil listesinden ID'ye çevrilir.

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
- **Sonuç:** Onay gerekmiyorsa yanıt talebin son durumunu taşır: `APPLIED` ya da `FAILED`. Ortamdan yanıt alınamadıysa talep `APPLYING`'de kalır ve `RETRY` ile tamamlanır. Onay gerekiyorsa talep `PENDING_APPROVAL` olur.
- **Talepler listesi:** Durum, hedef ortam, proje ve "benim taleplerim" filtreleri (`GET /promotions`). Onaylayanın varsayılan görünümü onay bekleyenlerdir.
- **Talep detayı:** Hedef ortamdaki mevcut versiyonla fark, yoklama sonucu ve profilde onu kullanan route'lar. Başka projeden eklenen yetkiler ve anonim erişim ayrı bir uyarı kutusunda gösterilir.
- **Düğmeler:** Yanıttaki `allowedActions` listesine göre gösterilir: `APPROVE`, `REJECT`, `CANCEL`, `RETRY`. Talep eden kendi talebinde onay düğmesini görmez; backend de 403 `FOUR_EYES` döner.
- **Yayılım:** `APPLIED` sonrası ekran ortam durumunu birkaç saniye arayla sorar (`GET /environments/{env}/status`) ve "3/3 instance v43'te" gösterir. Bir dakikayı aşan gecikme uyarı olarak gösterilir.

&#91;embedded content: talep yaşam döngüsü · 7 durum\]

Onay gerekmeyen ortamda talep doğrudan `APPLYING` olur. `APPROVED` yalnızca bir an görünür, çünkü uygulama onaydan hemen sonra başlar. `APPLYING`'de kalan bir talep de `RETRY` ile yeniden uygulanabilir; ortam aynı talebi ikinci kez yazmaz.

### Ortamlar

Ortam başına config versiyonu ve her broker instance'ının yüklediği versiyon gösterilir (`GET /environments/{env}/status`). Geride kalan instance uyarı rengiyle işaretlenir.

## API'nin genel kuralları

Backend REST ve JSON konuşur; bütün yollar `/api/v1` altındadır ve alan adları camelCase'tir. Swagger arayüzü `/swagger-ui.html`, OpenAPI tanımı `/v3/api-docs` adresindedir; ikisi yalnızca `local` ve `dev` profillerinde açıktır. API'yi yerelde örnek veriyle tek komutla açmak için `docs/local-run.md`.

- **Kimlik:** Her istek giriş uygulamasının oturumunu taşır: `X-Token`, `X-Uid`, `X-Clientip`, `X-User-Agent`. Backend oturumu PROD ortamında, broker'ın kuralıyla (token, IP ve tarayıcı girişteki gibi) doğrular ve sonucu 30 saniye tutar. Header'lardan biri yoksa ya da oturum geçersizse 401, oturum geçerli ama Yapi yetkisi yoksa ya da rol yetmiyorsa 403 döner. Oturum doğrulanamazsa (PROD'un Admin API'sine ulaşılamadı) 502 `ENV_UNREACHABLE`. `ROUTE_EDITOR` ve `APPROVER` rolleri okuma iznini de kapsar.
- **Yerel geliştirme:** Backend `local` profiliyle çalışınca oturum yerine HTTP Basic kabul eder. Hazır kullanıcılar `viewer`, `editor`, `approver` ve üç role birden sahip `lead`'dir; şifre kullanıcı adıyla aynıdır. Dört göz kuralı `lead` ile talep açıp `approver` ile onaylayarak denenir.
- **CORS:** İzinli adresler `yapi.admin.cors.allowed-origins` ayarındadır; yerelde varsayılan `http://localhost:5173`'tür. İzinli header'lar `Content-Type`, `Authorization` (yerel) ve oturumun dört header'ı.
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
| `UNAUTHENTICATED` | 401 | Oturum header'ları yok ya da oturum geçersiz |
| `FORBIDDEN` | 403 | Kullanıcının rolü işlem için yetmiyor |
| `FOUR_EYES` | 403 | Talep eden kendi talebini onaylamaya ya da reddetmeye çalıştı |
| `TEST_NOT_ALLOWED` | 403 | Ortam test çağrısına kapalı |
| `NOT_FOUND` | 404 | Kayıt yok |
| `VERSION_CONFLICT` | 409 | Kayıt arada değişti; `currentVersion` döner |
| `DUPLICATE` | 409 | maintask, subtask, profil adı ya da Koordinatör proje ID'si zaten kayıtlı |
| `PREFIX_OVERLAP` | 409 | Yetki öneki başka bir önekle çakışıyor |
| `INVALID_STATE` | 409 | İşlem kaydın durumuna uymuyor, örneğin uygulanmış talebi iptal etmek |
| `ROUTE_DEPLOYED` | 409 | Bir ortamda aktif olan subtask arşivlenmek istendi |
| `PRECHECK_FAILED` | 422 | Taşıma ön kontrolü geçmedi; `checks` listesi döner |
| `INTERNAL_ERROR` | 500 | Beklenmeyen sunucu hatası; ayrıntı backend logundadır |
| `ENV_UNREACHABLE` | 502 | Ortamın Admin API'sine ulaşılamadı |
| `UPSTREAM_ERROR` | 502 | Ortamın Koordinatör'ü ya da servisin spec'i hata verdi |

## Uç noktalar

Tüm yollar `/api/v1` altındadır. Rol sütunu en az gereken roldür; şemaların tamamı Swagger arayüzündedir, aşağıdaki örnekler en çok kullanılan gövdeleri gösterir.

### Oturum ve ortamlar

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/me` | VIEWER | Kullanıcı adı, görünen ad ve roller |
| GET | `/environments` | VIEWER | Ortamlar, sıraları, onay ve test ayarları |
| GET | `/environments/{env}/status` | VIEWER | Config versiyonu ve instance'ların yüklediği versiyonlar |
| GET | `/meta/definition-rules` | VIEWER | Enum'lar, sınırlar, desenler, okunabilir ve yasak header'lar (`forbiddenHeaderExceptions` istisnalarıyla), yer tutucular |

### Projeler ve yetkiler

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/catalog/projects?query=` | VIEWER | Koordinatör'ün Yapi projelerinde ad ya da ID ile arama; kayıtlı olanlarda `registeredProjectId` dolu |
| GET | `/projects?query=&catalogStatus=` | VIEWER | Kayıtlı projeler |
| POST | `/projects` | ROUTE\_EDITOR | Projeyi kaydeder |
| GET | `/projects/{id}` | VIEWER | Proje ve ortam ayarları |
| PATCH | `/projects/{id}` | ROUTE\_EDITOR | Ad, takım, açıklama, varsayılan profil; `UNLINKED` projeyi Koordinatör'deki projesine bağlama |
| PUT | `/projects/{id}/environments/{env}` | ROUTE\_EDITOR (onaylı ortamda APPROVER) | Base URL, spec yolu, açık/kapalı, alarm route'u; ortama hemen uygulanır |
| GET | `/projects/{id}/permissions?env=` | VIEWER | Projenin ortamdaki Koordinatör yetkileri ve kullanan subtask sayısı |
| GET | `/permissions/{code}/routes` | VIEWER | Yetkiyi kullanan subtask'ler, `OWN` ya da `FOREIGN` |

### Subtask'ler

| Method | Yol | Rol | Ne yapar |
| --- | --- | --- | --- |
| GET | `/projects/{id}/routes?status=&query=` | VIEWER | Projenin subtask'leri ve ortam dağılımları |
| GET | `/routes?alarmService=true` | VIEWER | Bütün projelerin alarm servisi subtask'leri (alarm route seçicileri için) |
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

`POST /projects/12/test` gövdesi `environment`, kaydedilmemiş tanım (`httpMethod`, `pathTemplate`, `responseProfileId`, `definition`), örnek frontend gövdesi (`sample`) ve isteğe bağlı `headers`, `userId`, `permissions` ile `foreignPermissions` alır (formdaki yetki listeleri; `${user.permission}` bunlardan ilki olur). `/routes/preview` aynı gövdeyi alır ve yalnızca `request` ile `errors` döner.

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
  "sourceEnv": "PREPROD",
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
    { "check": "SOURCE_ACTIVE", "ok": true, "message": "v4 PREPROD ortamında aktif" },
    { "check": "PERMISSIONS_EXIST", "ok": false, "message": "PROD Koordinatör'ünde yok: ORDER_WRITE" }
  ]
}
```

### Gövde ve yanıt ayrıntıları

Yukarıdaki örneklerde görünmeyen gövde ve yanıt alanları. Alan adları camelCase'tir; zamanlar ISO 8601 UTC'dir.

| Uç nokta | Gövde | Yanıt ve kurallar |
| --- | --- | --- |
| `GET /me` | — | `{"username", "displayName", "roles": [...]}`; `ROUTE_EDITOR` ya da `APPROVER` olan kullanıcıda `VIEWER` da listelenir. Hiç rolü olmayan kullanıcı her uçta 403 alır |
| `GET /environments/{env}/status` | — | `{"environment", "configVersion", "configUpdatedAt", "instanceCount", "upToDateCount", "instances": [{"instanceId", "loadedVersion", "loadedAt", "upToDate", "lagging"}]}`. `lagging`: bir dakikadan uzun süredir geride. Ortama ulaşılamazsa 502 `ENV_UNREACHABLE` |
| `POST /projects` | `{"externalProjectId", "maintask", "permissionPrefix", "name", "ownerTeam", "description", "defaultResponseProfileId"}` | `externalProjectId` Koordinatör'deki `projeId`; `name` boşsa Koordinatör'deki `projeAdi`. `externalProjectId` olmadan kayıt `UNLINKED` olur (geçiş için). 201 ve proje |
| `PATCH /projects/{id}` | Değişecek alanlar: `name`, `ownerTeam`, `description`, `defaultResponseProfileId` (`null` temizler), `externalProjectId` | `externalProjectId` yalnızca `UNLINKED` projeyi bağlar, proje `LINKED` olur. `maintask` ya da `permissionPrefix` farklı gönderilirse 400 |
| `GET /projects`, `GET /projects/{id}` | — | Proje alanları, `defaultResponseProfile: {id, name}` ve `environments: [{environment, baseUrl, specPath, enabled, alarmRoute}]` |
| `PUT /projects/{id}/environments/{env}` | `{"baseUrl", "specPath", "enabled", "alarmRoute"}` | `specPath` varsayılan `/v3/api-docs`, `enabled` varsayılan `true`. `alarmRoute` `{"maintask", "subtask"}` ya da `null` (yok; gönderilmezse de `null`). Route kayıtlı ve güncel versiyonu alarm servisi olmalı, yoksa 400 (`field: alarmRoute`); harf farkı kabul edilir, kayıtlı yazımıyla saklanır. Ortamda aktif olması burada değil, taşımada aranır (`ALARM_ROUTE_ACTIVE`). Yanıt `{"environment", "baseUrl", "specPath", "enabled", "alarmRoute", "configVersion"}` |
| `GET /projects/{id}/permissions?env=` | — | `env` verilmezse ilk ortam. Öğe `{"code", "suffix", "description", "routeCount"}`; `suffix` kodun önekten sonraki kısmı (kod önekle başlamıyorsa `null`), `description` Koordinatör vermediği için `null`; `routeCount` tüm projelerde yetkiyi kullanan, arşivde olmayan subtask sayısı. Ortamın listesi birkaç dakika önbellekte tutulur |
| `GET /projects/{id}/routes`, `GET /routes?alarmService=true` | — | Öğe: `id`, `projectId`, `maintask`, `subtask`, `httpMethod`, `pathTemplate`, `permissions`, `foreignPermissions`, `anonymous`, `responseProfile: {id, name}`, `alarmService`, `status`, `version`, `updatedBy`, `updatedAt` ve `deployments`. `alarmService` güncel versiyonun alarm servisi olup olmadığıdır. `/routes` yalnızca `alarmService=true` ile çağrılır (yoksa 400, `field: alarmService`); arşivdekiler gelmez, sıra maintask ve subtask |
| `POST /projects/{id}/routes`, `PUT /routes/{id}` | Örnekteki gövde ve isteğe bağlı `specOperation` | `specOperation`, spec karşılaştırmasındaki öğenin aynı adlı alanıdır; kaydedilince `specFingerprint` hesaplanır ve `CHANGED` bundan bulunur. Kayıtta her yetki ilk ortamın (DEV) yetkilendirme servisinde aranır; yoksa alan hatası döner |
| `POST /routes/{id}/archive`, `/restore` | İsteğe bağlı `{"version", "comment"}` | Güncel subtask |
| `POST /routes/{id}/rollback`, `POST /response-profiles/{id}/rollback` | `{"restoreVersion": 3, "version": 5, "comment"}` | `restoreVersion` dönülecek versiyon; `version` okunan versiyondur (iyimser kilit), verilmezse güncel versiyon kabul edilir |
| `GET /routes/{id}/history/{version}` | — | `{"version", "action", "changedBy", "changedAt", "comment", "snapshot"}`; `snapshot`: `subtask`, `httpMethod`, `pathTemplate`, `permissions`, `foreignPermissions`, `anonymous`, `responseProfileId`, `definition`, `status`. Profilde `name`, `description`, `definition` |
| `GET /routes/{id}/compare` | — | `{"from", "to", "changes": [{"path", "before", "after"}], "foreignPermissionsAdded", "foreignPermissionsRemoved", "opensAnonymous"}`; profilde yalnızca `from`, `to`, `changes` |
| `POST /routes/validate` | Subtask gövdesi ve `projectId` | Her zaman 200: `{"valid", "errors"}`. Yetkilendirme servisine gitmez |
| `POST /routes/preview` | Test gövdesi ve `projectId` | Tanım hatalıysa 400 `VALIDATION_FAILED`. Örnek gövde uymuyorsa 200 ve `request: null`. Base URL seçilen ortamınkidir (`environment` yoksa ilk ortam) |
| `GET /projects/{id}/spec?env=` | — | `{"environment", "items": [{"group", "httpMethod", "path", "operationId", "summary", "fingerprint", "specOperation", "route", "proposal", "changes"}]}`. `route`: `{id, subtask, version, pathTemplate, specFingerprint}` ya da `null`; `proposal` yalnızca `NEW`'de, `changes` yalnızca `CHANGED`'de dolu. Sıra `MISSING`, `CHANGED`, `NEW`, `SAME`. Eşleşmede path değişkenlerinin adı yok sayılır: `/orders/{id}` ile `/orders/{orderId}` aynıdır |
| `POST /projects/{id}/templates/from-sample` | `{"text", "environment", "operation": {"httpMethod", "path"}}` | `{"maintask", "subtask", "removedHeaders", "maskedSample", "fields", "proposal", "unmatchedFields", "unmatchedServiceParameters"}`. `operation` verilmezse `proposal` boştur. Metin okunamazsa 400 (`errors[].field` = `text`) |
| `POST /projects/{id}/test` | Örnekteki gövde | Profil, ortamda aktif versiyonuyla uygulanır; ortamda yoksa güncel versiyonuyla. `userId` verilmezse oturumdaki kullanıcıdır |
| `POST /projects/{id}/probe` | `{"environment", "httpMethod", "pathTemplate", "pathVariables"}` | `{"result", "status", "allow", "url", "message"}`; değeri verilmeyen path değişkenine `1` yazılır, redirect izlenmez |
| `GET /response-profiles/{id}` | — | Profil alanları, `deployments` ve `usage: [{"environment", "routes": [{"routeId", "maintask", "subtask", "version"}]}]` |
| `POST /response-profiles/preview` | `{"definition", "status", "body"}` | `{"status", "body", "outcome"}`; `status: null` timeout'u temsil eder |
| `POST /promotions` | Örnekteki gövde | `DISABLE` ve `ENABLE`'da `version` verilmeyebilir; hedefte çalışan versiyon kullanılır. Ortamdan yanıt alınamazsa talep `APPLYING`'de kalır ve `failureReason` dolar; `RETRY` ile tamamlanır |
| `GET /promotions` | — | `status` virgülle birden fazla değer alır (`status=APPLIED,FAILED`); `size` en fazla 100 |

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
| `request.headers` | ad → değer | `Authorization`, `X-User-*`, `X-uid`, `Host`, `Content-Length`, `Connection`, `Transfer-Encoding` tanımlanamaz. `X-User-Agent` istisnadır: frontend'in header'ıdır, `${header.X-User-Agent}` ile iletilebilir (giriş servisi oturumu ona bağlar) |
| `request.body.mode` | `NONE`, `PASS_THROUGH`, `TEMPLATE`, `MULTIPART` | GET ve DELETE'te yalnızca `NONE`. `PASS_THROUGH` zarf alanları dışındaki gövdeyi olduğu gibi gönderir |
| `request.body.contentType` | Metin | `TEMPLATE`'te varsayılan `application/json` |
| `request.body.template` | JSON | Yalnızca `TEMPLATE`'te; tek bir yer tutucu da olabilir: `"${request.payload}"` |
| `request.body.parts[]` | `name`, `value`, `file` | Yalnızca `MULTIPART`'ta. `file: true` olan parçanın değeri `{fileName, contentType, base64}` nesnesine bağlanır |
| `response.type` | `JSON`, `TEXT`, `BINARY` | `BINARY` stream edilir; body loglanmaz, profil uygulanmaz |
| `timeoutMs` | 100–60000 | Varsayılan 5000 |
| `retry.maxAttempts` | 1–3 | 1'den büyükse method GET olmalı |
| `logging.logRequestBody`, `logResponseBody` | true, false | Varsayılan sırasıyla true ve false. `logResponseBody` editördeki "Yanıt verisi loglansın" seçeneğidir: açıkken yanıtın `data`'sı maskelenip `maxBodyBytes`'a kadar Elastic'teki `data_response.data`'ya JSON metni olarak, ClickHouse'a gövde olarak yazılır; kapalıyken bugünkü gibi `data` boş string |
| `logging.maskFields` | Alan adları | `password` her zaman maskelenir; listedekiler ona eklenir |
| `logging.maxBodyBytes` | 1–262144 | Varsayılan 65536; yalnızca yanıt body'si için |
| `logging.binaryInfo` | Metin | Dosya yanıtında Elastic'e yazılan `info` mesajı |
| `alarm.check` | `INHERIT`, `OFF`, `ROUTE` | Varsayılan `INHERIT`: projenin ortamdaki `alarmRoute`'u, yoksa kontrol yok. `ROUTE`'ta `alarm.maintask` ve `alarm.subtask` zorunlu, diğerlerinde verilmez. Alarm servisinde `alarm` olmaz |
| `alarmService.resultPath` | Noktalı JSON yolu ya da boş | Varsa subtask alarm servisidir. Yanıtta alarm listesinin yolu (`data.alarms`); boşsa yanıtın kendisi dizi. Liste dolu dönerse istek durdurulur |

### Yer tutucular

Değeri yalnızca bir yer tutucudan oluşan alan değerin tipini korur: `"${request.siparisNo}"` sayı olarak gider. Metnin içindeki yer tutucu metne çevrilir. İfade dili ya da script yoktur.

| Yer tutucu | Nerede | Değer |
| --- | --- | --- |
| `${request.<yol>}` | Tanım | Frontend gövdesindeki alan; `fields`'ta tanımlı olmalı. Zarf alanları (`uid`, `application`, `maintask`, `subtask`) tanımlanmadan okunur; anonim route'ta `uid` doğrulanmamıştır |
| `${header.<ad>}` | Tanım | Frontend isteğinin header'ı; yalnızca `readableHeaders` listesindekiler |
| `${user.id}` | Tanım | Oturum kaydıyla doğrulanan kullanıcı: kayıt gövdedeki `uid` ile bulunur, token, `X-Clientip` ve `X-User-Agent` eşleşmelidir. Oturum yoksa boş |
| `${user.permission}` | Tanım | Kullanıcıda olan ilk route yetkisi: önce `permissions`, sonra `foreignPermissions`, tanımdaki sırayla. Oturum yoksa ya da kullanıcıda bu yetkilerden hiçbiri yoksa değeri yoktur, alan yazılmaz. Önizleme ve testte kullanıcının formdaki yetkilere sahip olduğu varsayılır |
| `${requestId}` | Tanım, profil | İsteğin ID'si |
| `${alarm.values}` | Yalnızca alarm servisi tanımı | Gelen isteğin gövdesindeki bütün yaprak değerler (iç içe dahil), metin olarak ve sırayla, JSON dizisi. Zarf alanları (`definition-rules`'da `alarm.excludedFromValues`), maskelenen alanlar ve `null`'lar hariç. Önizleme ve testte değeri yoktur, alan yazılmaz |
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
| Outcome | `SUCCESS`, `BUSINESS_ERROR`, `TECHNICAL_ERROR`, `TIMEOUT`, `DENIED`, `UNKNOWN_TASK`, `INVALID_REQUEST`, `ALARM`, `LOG_UNAVAILABLE` |
| `alarm.check` | `INHERIT`, `OFF`, `ROUTE` |

### Ön kontroller

| `checks[].check` | Neye bakar |
| --- | --- |
| `SUBJECT_ACTIVE` | Subtask arşivde değil |
| `NEXT_ENVIRONMENT` | Hedef ortam, kaynak ortamın hemen sonrası |
| `SOURCE_ACTIVE` | Versiyon kaynak ortamda aktif; DEV'e taşımada aranmaz |
| `NO_OPEN_REQUEST` | Aynı subtask ya da profil ve hedef ortam için açık talep yok |
| `PERMISSIONS_EXIST` | Yetkiler hedef ortamın Koordinatör'ünde, bir Yapi projesinin yetkileri arasında |
| `PROFILE_ACTIVE` | Subtask'in yanıt profili hedef ortamda aktif |
| `PROJECT_ENABLED` | Proje hedef ortamda tanımlı ve açık |
| `ALARM_ROUTE_ACTIVE` | Subtask'in alarm route'u (`ROUTE`'ta tanımdaki, `INHERIT`'te projenin hedef ortamdaki `alarmRoute`'u) hedef ortamda aktif ve oradaki versiyonu alarm servisi. `INHERIT`'te projenin hedefte alarm route'u yoksa geçer. Alarm servislerinde ve `OFF`'ta çalışmaz |
| `DEPLOYED_IN_TARGET` | `DISABLE` ve `ENABLE`'da subtask hedef ortamda var |
| `PREVIOUSLY_APPLIED` | `ROLLBACK`'te versiyon hedef ortama daha önce uygulanmış |

Aksiyona göre çalışan kontroller: `DEPLOY`'da `SUBJECT_ACTIVE`, `NEXT_ENVIRONMENT`, `SOURCE_ACTIVE` (ilk ortamda yok), `NO_OPEN_REQUEST`, `PERMISSIONS_EXIST`, `PROFILE_ACTIVE`, `PROJECT_ENABLED`, `ALARM_ROUTE_ACTIVE`. `ROLLBACK`'te `NEXT_ENVIRONMENT` ve `SOURCE_ACTIVE` yerine `PREVIOUSLY_APPLIED`. `DISABLE`'da `NO_OPEN_REQUEST` ve `DEPLOYED_IN_TARGET` (hedefte `ACTIVE`). `ENABLE`'da `SUBJECT_ACTIVE`, `NO_OPEN_REQUEST`, `DEPLOYED_IN_TARGET` (hedefte `DISABLED`) ile yetki, profil, proje ve alarm kontrolleri. Profil taleplerinde yalnızca `NEXT_ENVIRONMENT`, `SOURCE_ACTIVE`, `NO_OPEN_REQUEST` ve `PREVIOUSLY_APPLIED`. `422` yanıtı çalışan kontrollerin hepsini `ok` bilgisiyle listeler.

### Broker'ın uygulamalara döndüğü yanıtlar

Test panelindeki `clientResponse` bu kurallarla üretilir; uygulama frontend'leri de aynısını alır. Test çağrısı application ve oturum kontrollerinden geçmez; tablodaki HTTP 200'lü eski gövdeler yalnızca uygulamalara döner.

- **İz:** Her yanıtta `X-Request-Id` header'ı vardır; Elastic'teki ana dokümanın `_id`'si de budur.
- **Log yazılamazsa:** Servis yanıt vermiş olsa bile `500` ve `{"result": false}` döner.
- **Dosya yanıtı:** Servis `Content-Disposition` döndürürse dosya olduğu gibi stream edilir, profil uygulanmaz.
- **Broker hataları:** Servise gitmeden verilen hatalar aşağıdaki ortak formattadır. Eski broker'da karşılığı olan iki durum (`application` ve oturum) tablodaki gibi bugünkü gövdeleriyle HTTP 200 döner.

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

| Durum | HTTP | Gövde ve outcome |
| --- | --- | --- |
| `X-Token` header'ı yok | 400 | Ortak format, `INVALID_REQUEST` |
| `application` yok ya da izinli listede değil | 200 | `{"response": false}`, `DENIED` |
| Oturum geçersiz: `user:session:{uid}` kaydı yok ya da `X-Token`, `X-Clientip`, `X-User-Agent` kayıttakiyle aynı değil | 200 | `{"response": false, "status": 301, "reason": "check X-Token"}`, `DENIED` |
| Kullanıcıda route'un yetkilerinden hiçbiri yok | 403 | Ortak format, `DENIED` |
| maintask/subtask kayıtlı değil (oturum geçersizse yukarıdaki 301 yanıtı döner) | 404 | Ortak format, `UNKNOWN_TASK` |
| Gövde `fields` tanımına uymuyor | 400 | Ortak format, `INVALID_REQUEST`; `errors` alan bazında |
| Oturum okunamadı (Redis) | 503 | Ortak format, `TECHNICAL_ERROR` |
| Alarm kontrolü alarm buldu; servis çağrılmadı | 200 | `{"response": false, "badprocess": true, "status": 500}`, `ALARM`. Eski yanıtlar kapalıysa (`legacy-responses`) 403 ortak format, `ALARM` |
| Alarm servisine ulaşılamadı, 2xx dışı döndü ya da yanıtı okunamadı | 503 | Ortak format, `TECHNICAL_ERROR`; "Alarm kontrolü yapılamadı; istek gönderilmedi." |

Alarm kontrolü oturum, yetki ve alan doğrulamasından sonra, servis çağrısından önce yapılır; test çağrısında yapılmaz. Alarm servisi route'u kendi timeout'uyla, tek denemeyle çağrılır. Alarm olan istek Elastic'te eski `alarm` indeksine de yazılır.

`X-Token: login` ve `logout` istekleri gövdedeki maintask'a bakılmadan giriş servisinin route'larına gider; yanıtları eski broker'la birebir aynıdır (tasarım dokümanında "Giriş ve oturum").

## Değişiklik kaydı

Backend geliştirildikçe bu tablo güncellenir; en yeni kayıt en üsttedir. Bir uç nokta uygulandığında ya da sözleşmesi değiştiğinde burada ayrı bir satır olarak görünür.

| Tarih | Değişiklik |
| --- | --- |
| 2026-10-26 | Alarm route seçicileri: yeni uç `GET /routes?alarmService=true` (bütün projelerin alarm servisi subtask'leri, arşivdekiler hariç); subtask listelerinin öğelerinde yeni alan `alarmService`. |
| 2026-10-26 | Alarm: proje ortam ayarında `alarmRoute` (`{"maintask", "subtask"}` ya da `null`; gövdede, yanıtta ve `environments[]`'de), subtask tanımında `alarm` (`check`: `INHERIT`, `OFF`, `ROUTE`) ve `alarmService` (`resultPath`), yeni yer tutucu `${alarm.values}` (yalnızca alarm servisinde), yeni outcome `ALARM`, yeni ön kontrol `ALARM_ROUTE_ACTIVE`. `definition-rules`'da `placeholders.alarmService` ve `alarm` (`checks`, `default`, `excludedFromValues`). Alarm servisi frontend'den çağrılamaz (404 `UNKNOWN_TASK`). |
| 2026-10-26 | Admin'e giriş: SSO ve `Authorization: Bearer` kalktı. Ekran her istekte giriş uygulamasının oturumunu gönderir (`X-Token`, `X-Uid`, `X-Clientip`, `X-User-Agent`); oturum geçersizse 401, Yapi yetkisi (`YAPI_DEVELOPER`, `YAPI_ADMIN`) yoksa ya da rol yetmezse 403. `GET /me`'de `username` ve `displayName` sicil. `local` profilinde HTTP Basic aynı. |
| 2026-10-26 | Koordinatör'ün gerçek uçları: proje kataloğu (`/catalog/projects`) Koordinatör'ün Yapi projeleri (`is_yapi`), ID `projeId`, ad `projeAdi`; projenin yetkileri ortamın Koordinatör'ündeki projesinin listesi (`UNLINKED` projede boş), `description` `null`, `suffix` önekle başlamayan kodda `null`. Yetki açma kalktı: `POST /projects/{id}/permissions` yok (405), ekranda "yetki ekle" yok; yetkiler Koordinatör'de açılır. Hata ve ön kontrol mesajlarında "yetkilendirme servisi" yerine "Koordinatör". |
| 2026-10-26 | Ortamlar DEV, PREPROD, PROD: TEST'in kodu `PREPROD` oldu (`GET /environments`, `env` parametreleri, taşıma talepleri, rozetler). Ön kontrol mesajı "v4 PREPROD ortamında aktif" biçiminde. |
| 2026-10-26 | Ad değişikliği: proje Yapi, ekranın adı Yapi Admin. Merkezi admin `yapi-admin`, yerel broker'ın dosyası `yapi-local.json`, CORS ayarı `yapi.admin.cors.allowed-origins`. Admin API'nin yolları, gövdeleri ve hata kodları değişmedi. |
| 2026-10-16 | Yeni yer tutucu `${user.permission}`: kullanıcıda olan ilk route yetkisi (önce `permissions`, sonra `foreignPermissions`). Önizleme ve test gövdesi isteğe bağlı `permissions` ve `foreignPermissions` alır; `definition-rules`'daki `placeholders.definition` yeni yer tutucuyu listeler |
| 2026-10-16 | Swagger ve OpenAPI yalnızca `local` ve `dev` profillerinde açık. Yerel çalıştırma `docs/local-run.md`'de: admin API `local` profilinde DEV Oracle'daki yerel bir şemaya bağlanır ve boş şemayı örnek veriyle doldurur |
| 2026-10-16 | Broker'ın uygulamalara döndüğü yanıtlar, `application` kontrolü, oturum kaydı ve zarf alanlarının `${request.*}` ile okunması uygulandı. `X-User-Agent` route header'ı olarak tanımlanabilir; `X-User-*` yasağının tek istisnası. `GET /meta/definition-rules` yanıtına `forbiddenHeaderExceptions` eklendi, `placeholders.definition` zarf yer tutucularını (`${request.uid}`, `${request.application}`, `${request.maintask}`, `${request.subtask}`) da listeler |
| 2026-10-16 | Sözleşme güncellendi; backend'de sıradaki geliştirme turunda uygulanacak. `logResponseBody` editörde "Yanıt verisi loglansın" seçeneği oldu; açıkken yanıt verisi Elastic'e de yazılır. Broker'ın uygulamalara döndüğü yanıtlar eski broker'la aynı: `application` kontrolü ve geçersiz oturum HTTP 200 ile eski gövdeleri döner, login ve logout giriş servisinden gelir. Zarf alanları `${request.*}` ile okunur, `${user.id}` oturum kaydıyla doğrulanır. Projeler ve yetkiler tek bir yetki projesinden gelir |
| 2026-10-13 | Backend uygulandı ve testlerden geçti; henüz bir ortama kurulmadı, yerelde `local` profiliyle çalıştırılabilir. Netleşen gövde ve yanıtlar "Gövde ve yanıt ayrıntıları" bölümünde: yetki açmada `environments`, geri almada `restoreVersion`, spec karşılaştırmasında `items` ve `specOperation`, istekten şablon, yoklama, ortam durumu ve profil önizleme. Talep listesinde öğeler `diff` ve `affectedRoutes` taşımaz, `status` virgülle çoklu değer alır; `DISABLE`/`ENABLE`'da `version` isteğe bağlı. Ön kontrollerin aksiyona göre listesi eklendi. Yeni hata kodu: `INTERNAL_ERROR` (500) |
| 2026-10-13 | İlk sürüm: ekranlar, API sözleşmesi, alan kuralları ve hata kodları |

### Netleşmemiş noktalar

- [x] **Giriş:** SSO yok; admin ekranı şirketin giriş uygulamasının oturumuyla açılır (2026-10-26).
- [x] **Yetki projesi:** Koordinatör'ün gerçek uçlarına bağlandı (2026-10-26): katalog, projenin yetkileri ve yetkinin varlığı her ortamın `projeler-ve-yetkileri` ucundan; ekranın gördüğü yollar değişmedi, yetki açma ucu kalktı.
- [ ] **PROD base URL'i:** Şimdilik yalnızca `APPROVER` değiştirebiliyor; ileride onay akışına alınabilir.
- [ ] **Acil durum:** PROD'da `DISABLE` ve `ROLLBACK` de onay istiyor; tasarım dokümanında açık bir karar.
