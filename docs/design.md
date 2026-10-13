# Broker Modernizasyonu — Tasarım Dokümanı

Oct 8, 2026 · @Yusuf

## Özet

Broker, subtask'leri koddan değil, DevOps'un merkezi bir ekrandan yönettiği versiyonlu tanımlardan okuyacak. Yeni bir endpoint için artık broker deploy'u ve kodda yetki tanımı gerekmeyecek.

Bugün her yeni endpoint broker'a kodla ekleniyor ve deploy gerektiriyor. Loglar istek yolunda, senkron olarak Elasticsearch'e yazılıyor; Elastic yavaşlarsa bütün istekler yavaşlıyor.

- **Dinamik tanım:** Subtask'ler servisin OpenAPI spec'inden içe aktarılır; her değişiklik versiyonlanır ve audit edilir.
- **Proje yanıt formatları:** Projeye özel response sınıfları, aynı şekilde versiyonlanan yanıt profillerine dönüşür; yeni bir format da deploy gerektirmez.
- **Kontrollü taşıma:** Tanımlar DEV → TEST → PROD sırasıyla promote edilir; PROD onay ve OPTIONS yoklaması ister.
- **Düşük gecikme:** Broker route'ları bellekte tutar; değişiklik Redis sinyaliyle tüm instance'lara yayılır.
- **Güvenilir loglama:** İstek ve yanıt Kafka'ya kalıcı olarak yazılmadan servis çağrılmaz ve frontend'e yanıt dönmez; Elastic'teki doküman yapısı korunur.
- **İzlenebilirlik:** Her istek trace, metrik ve log olayıyla uçtan uca izlenir.

Kapsam dışı: frontend sözleşmesinin (maintask/subtask zarfı) değişmesi ve servis içindeki veri seviyesinde yetki.

## Mimari genel bakış

Trafik hiçbir zaman merkezden geçmez: broker'lar kendi ortamlarının runtime DB'sinden ve Redis'inden okur. Merkezi admin ortamlara yalnızca Admin API üzerinden, mTLS ile bağlanır.

&#91;embedded content: Bileşenler ve çağrı yönleri · 3 bölge\]

Oklar çağrıyı başlatanı gösterir. Runtime DB ve Redis, Admin API'nin yazdığı ve broker'ların okuduğu buluşma noktalarıdır.

| Bileşen | Nerede | Sorumluluk |
| --- | --- | --- |
| Merkezi Admin | Yönetim bölgesi | Ekranlar, tanım ve versiyonlar, onay ve promote; projeleri proje servisinden listeler, projenin yetkilerini yetkilendirme servisinde açar |
| Merkezi DB | Yönetim bölgesi | Route ve profil tanımları, history, ortam durumu, taşıma talepleri |
| Ortam Admin API | Her ortam | Runtime DB'ye yazar, Redis'e sinyal verir; spec çeker, yoklar, test eder |
| Runtime DB | Her ortam | O ortamda aktif route'lar, yanıt profilleri ve config versiyonu |
| Redis | Her ortam | Kullanıcı yetkileri, değişiklik sinyalleri |
| Broker | Her ortam | Route'u bellekten çözer, yetkiyi kontrol eder, logu Kafka'ya yazıp onayını bekler, isteği iletir, yanıtı profile göre zarflar |
| Kafka, Elastic, ClickHouse | Log hattı | Log olaylarını taşır; aranabilir ve analiz edilebilir şekilde saklar |
| Yetkilendirme servisi | Mevcut | Projeye özel yetkiler ve kullanıcılara atanmaları; kullanıcı yetkilerini Redis'e yazar |
| Proje servisi | Mevcut | Projelerin adı ve ID'si; admin ekranındaki proje listesi buradan gelir |

## Broker'da istek işleme

Broker, route'u bellekten bulur ve yetkiyi Redis'ten kontrol eder; istek yolunda veritabanına hiç gitmez.

1. **Karşılama:** Frontend `POST /broker` ile düz bir JSON gönderir: `maintask`, `subtask`, `application` ve `uid` zarf alanlarıdır, geri kalan alanlar subtask'in verisidir. Oturum token'ı `X-Token`, istemci bilgileri `X-Clientip` ve `X-User-Agent` header'larındadır. Broker bir request ID (UUIDv7) üretir ve trace'i başlatır.
2. **Route çözümleme:** `(maintask, subtask)` bellekteki snapshot'ta büyük/küçük harf fark etmeden aranır. Bulunamazsa `404` döner, outcome `UNKNOWN_TASK` olur.
3. **Yetki:** Kullanıcının yetkileri Redis'ten okunur. Route anonim değilse ve kullanıcıda route'un yetkilerinden hiçbiri yoksa `403` döner. Log için `perm_list` de bu okumadan çıkar.
4. **REQUEST olayı:** Body route'un log politikasına göre maskelenir, olay Kafka'ya yazılır ve broker onayı bekler. Onay gelmezse servis çağrılmaz; `500` ve `{"result": false}` döner, outcome `LOG_UNAVAILABLE` olur.
5. **Doğrulama ve istek üretimi:** Gövde, route'un `fields` tanımına göre denetlenir; eksik zorunlu alan ya da yanlış tip `400` döner, outcome `INVALID_REQUEST` olur. Ardından path variable, query param, header ve body tanımdaki yer tutucularla üretilir. Host her zaman ortamın kayıtlı base URL'idir.
6. **Forward:** İstek route'un timeout'uyla gönderilir; `traceparent`, imzalı kullanıcı bağlamı ve servislerin bugün beklediği `X-uid` header'ı oturumdaki kullanıcıyla eklenir. Dosya yanıtları belleğe alınmadan stream edilir: route'ta `BINARY` işaretli olanlar ve servisin `Content-Disposition` header'ı döndürdüğü her yanıt.
7. **RESPONSE olayı:** Servisin status'u, outcome, süre, maskelenmiş body ve yanıt profili Kafka'ya yazılır; broker onayı bekler. Onay gelmezse servisin yanıtı dönmez, `500` ve `{"result": false}` döner, outcome `LOG_UNAVAILABLE` olur. Diğer durumlarda outcome, zarftan önce servisin gerçek status'una göre hesaplanır. Dosya yanıtında olay status ve header'lar gelince yazılır, gövde loglanmaz; dosya onaydan sonra stream edilir.
8. **Yanıt:** Route'un yanıt profili varsa servisin yanıtı ya da hatası profilin şablonuyla zarflanır; profil yoksa ya da yanıt dosyaysa olduğu gibi döner. Broker'ın 2, 3 ve 5. adımlarda verdiği hatalar ortak formatta döner; bunlar da log olayları onaylanmadan dönmez. Her yanıta `X-Request-Id` header'ı eklenir.

`X-Token` değeri `login` ya da `logout` olan istekler oturum işlemleridir ve bugünkü davranışlarıyla taşınır: parametre logu ve `broker.gelen.istekler` sayacı dışında tutulur, şifre maskelenir. `X-Token` hiç yoksa istek `400` ile reddedilir; bugün bu durumda controller NullPointerException ile 500 dönüyor.

&#91;embedded content: Bir isteğin broker'dan geçişi · 8 adım\]

Numaralar yukarıdaki adımlarla eşleşir; kesik çizgiler dönüşleri gösterir. İstek yolunda tek dış okuma Redis'tendir. Kafka onayı 4. ve 7. adımlarda beklenir; hiçbir yanıt logu yazılmadan dönmez.

Outcome değeri her yerde aynıdır: metrik tag'i, log olayı ve ClickHouse kolonu.

| Outcome | Ne zaman |
| --- | --- |
| SUCCESS | Servis 2xx döndü |
| BUSINESS\_ERROR | Servis 4xx döndü |
| TECHNICAL\_ERROR | Servis 5xx döndü ya da bağlantı kurulamadı |
| TIMEOUT | Route timeout'u aşıldı |
| DENIED | Broker oturum ya da yetki nedeniyle reddetti |
| UNKNOWN\_TASK | maintask/subtask route tablosunda yok |
| INVALID\_REQUEST | Gövde, route'un alan tanımına uymuyor (eksik zorunlu alan ya da yanlış tip) |
| LOG\_UNAVAILABLE | REQUEST ya da RESPONSE olayı Kafka'ya yazılamadı; frontend'e 500 döndü |

&#91;embedded content: Broker'ın iç bileşenleri · UML sınıf diyagramı\]

Controller durum tutmaz, yalnızca sırayı yönetir. Route tablosu ve derlenmiş yanıt profilleri bellektedir ve değişiklik sinyalleriyle yenilenir. Kullanıcının yetkileri her istekte Redis'ten okunur; iki log olayı da onay beklenerek yazılır.

## Proje kaydı

Projelerin listesi ve kimliği proje servisinden gelir; broker yalnızca yönlendirme için gereken ayarları tutar.

1. **Listeleme:** Admin ekranı projeleri ad ve ID'leriyle proje servisinden çeker. Broker'a kayıtlı olanlar işaretli görünür.
2. **Kayıt:** Kayıtlı olmayan bir proje seçilince `project` satırı açılır: proje servisindeki ID, frontend'in göndereceği `maintask`, görünen ad ve yetki öneki (`LOGPROJECT` gibi). ID, `maintask` ve önek kayıttan sonra değiştirilemez.
3. **Ayarlar:** Ortam başına base URL ve spec yolu, varsayılan yanıt profili ve projenin yetkileri aynı ekrandan yönetilir.

- **Yetkiler:** Proje ekranı projenin yetkilerini yetkilendirme servisinden listeler ve yenisini o servisin API'siyle açar; yeni yetkinin yalnızca son eki girilir. Kullanıcıya atama yetkilendirme servisinde kalır. Kurallar Yetkilendirme bölümündedir.
- **Senkronizasyon:** Görünen ad proje servisinden periyodik olarak yenilenir; ad değişse de `maintask` değişmez. Proje serviste artık yoksa broker'daki kayıt silinmez, `MISSING` olarak işaretlenir.
- **Geçiş:** Mevcut projeler proje servisindeki ID'leriyle eşleştirilir. Eşleşmeyenler `UNLINKED` olarak kalır ve ekranda ayrıca gösterilir; sonradan bağlanabilir.

## Route tanımı

Bir subtask'in nasıl çağrılacağı tek bir JSON belgesinde durur; broker bu belgeyi yorumlar, kod çalıştırmaz. HTTP method, path şablonu, yetkiler, anonim bayrağı ve yanıt profili sorgulanabilsin diye ayrı kolonlardadır; geri kalan her şey `definition` alanındadır.

Örnek: `add_order_note` subtask'i `POST /orders/{id}/notes` endpoint'ine gider. Frontend `siparisNo`, `notMetni` ve `bildir` alanlarını gönderir; servis ise path'te `id`, query'de `notify`, body'de `text`, `authorId` ve `source` bekler:

```json
{
  "request": {
    "fields": [
      { "name": "siparisNo", "type": "integer", "required": true },
      { "name": "notMetni", "type": "string", "required": true },
      { "name": "bildir", "type": "boolean", "default": false }
    ],
    "pathVariables": { "id": "${request.siparisNo}" },
    "queryParams": { "notify": "${request.bildir}" },
    "headers": {
      "X-Channel": "WEB",
      "Accept-Language": "${header.Accept-Language}"
    },
    "body": {
      "mode": "TEMPLATE",
      "contentType": "application/json",
      "template": {
        "text": "${request.notMetni}",
        "authorId": "${user.id}",
        "source": "WEB"
      }
    }
  },
  "response": { "type": "JSON" },
  "timeoutMs": 5000,
  "retry": { "maxAttempts": 1 },
  "logging": {
    "logRequestBody": true,
    "logResponseBody": false,
    "maskFields": ["password", "cardNo", "tckn"],
    "maxBodyBytes": 65536
  }
}
```

Broker bu tanımla aşağıdaki dönüşümü yapar; `bildir` gelmediği için varsayılan değerini alır:

```text
Frontend → broker   (X-Token: <oturum token'ı>)
{ "maintask": "order-service", "subtask": "add_order_note",
  "application": "web", "uid": "u12345",
  "siparisNo": 981, "notMetni": "Kapıya bırakın" }

Broker → servis
POST /orders/981/notes?notify=false
X-Channel: WEB
X-uid: u12345
Content-Type: application/json

{ "text": "Kapıya bırakın", "authorId": "u12345", "source": "WEB" }
```

Frontend gövdesindeki alanlar `fields` ile tanımlanır; zarf alanları (`maintask`, `subtask`, `application`, `uid`) her istekte bulunduğu için tanıma eklenmez. Path, query, header ve body bu alanlara yer tutucularla bağlanır. Yanıt profillerindeki kural burada da geçerlidir: değeri yalnızca bir yer tutucudan oluşan alan değerin tipini korur, ifade dili ya da script yoktur. Spec'ten içe aktarılan endpoint'lerde alanlar ve eşlemeler servisin parametre ve body şemasından bire bir doldurulur; DevOps yalnızca frontend'in kullandığı adları ve varsayılanları değiştirir.

| Yer tutucu | Değer |
| --- | --- |
| `${request.<yol>}` | Frontend'in gönderdiği gövdedeki alan; yol, yapıştırılan JSON'daki yoldur (`${request.adres.il}`) |
| `${header.<ad>}` | Frontend isteğinin header'ı; yalnızca izin listesindekiler okunabilir |
| `${user.id}` | Broker'ın oturumdan doğruladığı kullanıcı; gövdedeki `uid` değil |
| `${requestId}` | İsteğin ID'si |

- **Doğrulama:** Broker gövdeyi `fields`'a göre denetler. Eksik zorunlu alan ya da yanlış tip `400` döner, outcome `INVALID_REQUEST` olur ve yanıt ortak formatta, alan bazında hatalarla gelir. Tanımda olmayan alanlar hata sayılmaz.
- **Sınır:** Bu kontrol yalnızca sözleşmeyi yakalar; kullanıcıya gösterilecek iş doğrulamaları servisin işidir.
- **Varsayılan:** Gelmeyen alan `default` değerini alır. Değeri olmayan isteğe bağlı bir alan query'ye, header'a ve body'ye hiç yazılmaz.
- **Kimlik:** Servis kullanıcı kimliğini body'de ya da header'da bekliyorsa değer `${user.id}`'den gelmelidir. Gövdedeki `uid`'den ya da frontend'in gönderdiği başka bir alandan alınırsa kullanıcı başkası adına işlem yapabilir.

Kurallar kayıt anında doğrulanır; tanımsız bir alana ya da bilinmeyen bir yer tutucuya referans veren tanım kaydedilemez. Sayısal sınırlar ilk sürüm için önerilen değerlerdir.

| Alan | Değerler | Kural |
| --- | --- | --- |
| request.fields | name, type, required, default | name, gövdedeki noktalı yoldur; type: string, integer, number, boolean, object, array |
| request.pathVariables | ad → değer | Path şablonundaki her `{değişken}` tam bir kez eşlenir; yalnızca zorunlu ya da varsayılanı olan alana bağlanır |
| request.queryParams | ad → değer | Değeri olmayan parametre gönderilmez |
| request.headers | ad → değer | `Authorization`, `X-User-*`, `X-uid`, `Host`, `Content-Length`, `Connection`, `Transfer-Encoding` tanımlanamaz |
| request.body.mode | NONE, PASS\_THROUGH, TEMPLATE, MULTIPART | GET ve DELETE'te yalnızca NONE; PASS\_THROUGH zarf alanları dışındaki gövdeyi olduğu gibi gönderir |
| request.body.template | JSON şablonu | Yalnızca TEMPLATE'te; tek bir yer tutucu da olabilir (`"${request.payload}"`) |
| response.type | JSON, TEXT, BINARY | BINARY stream edilir, body'si loglanmaz ve yanıt profili uygulanmaz. Servis `Content-Disposition` header'ı döndürürse route'un yanıt tipi ne olursa olsun aynısı yapılır |
| timeoutMs | 100–60000 | Varsayılan 5000 |
| retry.maxAttempts | 1–3 | 1'den büyükse method GET olmalı |
| logging.logRequestBody, logResponseBody | true, false | Varsayılan istekte true, yanıtta false; bugünkü gibi yanıtın `data` alanı loglanmaz |
| logging.binaryInfo | Metin | Binary yanıtta Elastic'e yazılan `info` mesajı; bugün fotoğraf ve excel için sabit metinler |
| logging.maskFields | Alan adları | Her derinlikte `xxxx` ile maskelenir; varsayılan liste bugünkü gibi yalnızca `password`, route bazında alan eklenir |
| logging.maxBodyBytes | En fazla 262144 | Yanıt body'si için; aşan kısım kırpılır, `body_truncated = true` olur. İstek body'si eski Elastic yapısı için kırpılmadan taşınır |

### Subtask adı

Subtask adını DevOps verir; OpenAPI spec yalnızca method, path, parametre ve body bilgisini doldurur. Ad, frontend'in çağırdığı sözleşmedir.

- **Teklik:** Ad proje içinde büyük/küçük harf fark etmeden tekildir (`uq_route_definition`); iki proje aynı subtask adını kullanabilir.
- **Harf duyarsız eşleşme:** Broker maintask'i ve subtask'i büyük/küçük harf fark etmeden eşler; `logProject` ile `logproject` aynı projeye gider. Ekranda ve metriklerde kayıtlı yazım görünür, eski log dokümanına frontend'in gönderdiği yazım yazılır.
- **Dilden bağımsız küçültme:** Karşılaştırma Java'da `toLowerCase(Locale.ROOT)`, DB'de `lower(... COLLATE "C")` ile yapılır. Türkçe ayarlı bir JVM'de ya da DB'de `GET_ID` küçültülünce `get_ıd` olur ve eşleşme bozulur.
- **Format:** Yeni subtask adları snake\_case yazılır (`^[a-z][a-z0-9]*(_[a-z0-9]+)*$`, ör. `get_order`). Kural ekranda uygulanır; geçişte aktarılan mevcut adlar olduğu gibi kalır.
- **Değişmezlik:** Ad kaydedildikten sonra değiştirilemez; DB'de bir trigger da bunu korur. Yeniden adlandırmak yerine yeni adla yeni bir subtask açılır, eskisi kullanımı sıfırlanınca kapatılır. Aynı kural maintask ve profil adları için de geçerlidir.

## Yanıt profilleri

Projeye özel response sınıfları broker kodundan çıkar ve versiyonlanan yanıt şablonlarına dönüşür. Bu sınıflar durum tutmadığı ve yalnızca servisin yanıtından bir zarf ürettiği için aynı davranış veriyle ifade edilebilir; yeni bir format da broker deploy'u gerektirmez.

Bugünkü `LogProjectResponse` sınıfının karşılığı, `log-project` profilinin `definition` alanı:

```json
{
  "success": {
    "httpStatus": 200,
    "body": {
      "BACKEND_STATUS": "${backend.status}",
      "status": 200,
      "data": "${backend.body}"
    }
  },
  "error": {
    "httpStatus": 400,
    "body": {
      "status": 400,
      "reason": "LogProject Hata!"
    }
  }
}
```

Değeri yalnızca bir yer tutucudan oluşan alan, değerin tipini korur: `${backend.status}` sayı, `${backend.body}` nesne olarak girer. İfade dili ya da script yoktur; bilinmeyen bir yer tutucu içeren profil kaydedilmez.

| Yer tutucu | Değer | Kullanılabildiği yer |
| --- | --- | --- |
| `${backend.status}` | Servisin HTTP status kodu | success, error (timeout'ta null) |
| `${backend.body}` | Servisin yanıt gövdesi | success; error'da yalnızca 4xx |
| `${outcome}` | SUCCESS, BUSINESS\_ERROR, TECHNICAL\_ERROR, TIMEOUT | success, error |
| `${requestId}` | İsteğin ID'si | success, error |

- **Seçim:** Profil tanım anında belirlenir. Projenin varsayılan profili (`project.default_response_profile_id`) yeni subtask ekranında ön seçim olarak gelir; route kendi profilini kaydeder (`route_definition.response_profile_id`). Profil boşsa yanıt olduğu gibi döner; byte array dönen subtask'ler böyledir.
- **Hangi şablon:** Servis 2xx döndüğünde success, servis çağrısı başarısız olduğunda (4xx, 5xx, timeout, bağlantı hatası) error şablonu uygulanır. Broker'ın isteği servise iletmeden verdiği hatalar (oturum, yetki, bilinmeyen subtask, geçersiz istek) ortak formatta kalır; frontend'in login'e yönlendirmesi genellikle bu status'lara bağlıdır. Log yazılamadığında yanıt bugünkü gibi `500` ve `{"result": false}`'tur; profil uygulanmaz.
- **Dosya yanıtları:** Servis `Content-Disposition` header'ıyla yanıt döndürürse yanıt dosya sayılır. Route'ta profil olsa da şablon uygulanmaz, gövde loglanmaz; Elastic'e bugünkü gibi yalnızca `info` mesajı yazılır.
- **5xx gövdesi:** Şablona verilmez; servisin stack trace'i frontend'e sızmaz.
- **Gözlemlenebilirlik:** Outcome, metrikler ve log olayı zarftan önce, servisin gerçek status'una göre hesaplanır. Her başarının 200'e sarılması ölçümleri bozmaz; log olayı profilin adını ve versiyonunu da taşır.
- **Ad:** Profil adı runtime'da referans olduğu için değiştirilemez; çoğu zaman projenin maintask'ıyla aynıdır.

### Audit ve taşıma

Profiller route'larla birebir aynı audit desenini kullanır. Güncel hal `response_profile`'da durur. Her değişiklik aynı transaction'da `response_profile_history`'ye tam snapshot olarak yazılır ve bu tabloya yalnızca ekleme yapılabilir.

Hibernate Envers yerine bu desen seçildi, çünkü history satırı ortamlara taşınan şeyin kendisidir: deployment'lar ve talepler FK ile belirli bir versiyona bağlanır. Envers'in revizyon numarası tüm entity'ler için ortaktır ve tablolarını kendisi yönetir; bu FK'leri ve append-only kuralını onun üstüne kurmak zorlama olur.

- **İçerik değişikliği:** Yalnızca profil taşınır; o ortamda profili kullanan tüm route'lar yeni zarfa geçer. Bu, bugün sınıfı değiştirip deploy etmenin karşılığıdır.
- **Onay:** Profil talepleri route'larla aynı kuyruktan ve onay akışından geçer, yalnızca DEPLOY ve ROLLBACK alır. Profil bir endpoint olmadığı için yoklama adımı yoktur; onaylayan, farkın yanında profili hedef ortamda kullanan route'ları görür.
- **Bağımlılık:** Bir route, profili hedef ortamda aktif değilse taşınamaz. Merkez bunu ön kontrolde durdurur, runtime'daki FK da aynı kuralı DB seviyesinde korur. Bu yüzden yeni bir proje için önce profil, sonra route'lar taşınır.

&#91;embedded content: Yanıt profili tabloları · ER diyagramı\]

Oklar FK'den referans verilen tabloya gider. Profil talepleri de route talepleri gibi history'deki belirli bir versiyona bağlanır.

Mevcut response sınıflarının her biri bir profile çevrilir. Koşullu mantık içeren sınıflar varsa o mantık servise taşınır. Taşınamayanlar için birkaç kod tabanlı formatter istisna olarak kalabilir, ama her değişiklikleri broker deploy'u gerektirdiği için sayıları az tutulmalıdır.

## Yetkilendirme

Yetkiler projeye özeldir: her yetki projenin önekiyle başlar (`LOGPROJECT_TEMEL`, `LOGPROJECT_TUM_LOGLAR`). Bir subtask'e bir ya da birkaç yetki verilir; kullanıcıda bunlardan biri varsa istek geçer. Yeni bir subtask mevcut bir yetkiyi kullanıyorsa kimseye yeni yetki atanması gerekmez.

- **Önek:** Projenin yetki öneki kayıtta belirlenir (`project.permission_prefix`) ve değiştirilemez. Proje ekranında yeni yetkinin yalnızca son eki girilir: `MODERATOR` yazılınca `LOGPROJECT_MODERATOR` açılır. Bir önek başka bir önekin başı olamaz (`HR` ile `HR_PORTAL` gibi); olursa iki projenin yetkileri karışır.
- **Proje yetkileri:** Subtask formu varsayılan olarak yalnızca o projenin yetkilerini listeler (`permissions`). Bu listeye başka projenin yetkisi giremez; DB'de bir trigger da korur.
- **Başka projeden yetki:** Bazı subtask'lere başka projelerin yetkileriyle de erişilir. Formdaki "Başka projeden yetki ekle" seçeneği önce projeyi, sonra o projenin yetkisini seçtirir; bu yetkiler ayrı bir alanda durur (`foreign_permissions`). Ekranda ve onay farkında ayrıca işaretlenir, böylece bir subtask'in başka projenin kullanıcılarına açıldığı gözden kaçmaz. Seçilen proje broker'a kayıtlı olmalıdır, çünkü öneki oradan bilinir.
- **Katalog:** Yetkilerin sahibi yetkilendirme servisidir; yetki onun API'siyle açılır, kullanıcıya da orada atanır. Kayıtta ve promote öncesinde her yetkinin hedef ortamda tanımlı olduğu kontrol edilir.
- **Varsayılan olarak reddet:** Kimseye atanmamış bir yetki, kimsenin çağıramadığı bir subtask demektir. Yeni subtask'ler böylece kapalı doğar.
- **Anonim erişim:** `anonymous = true` yalnızca onaylı bir değişiklikle açılır. Anonim olmayan her route için en az bir yetki DB seviyesinde zorunludur (`ck_route_permission`).
- **perm\_list:** Bugün broker'daki projeye özel sınıflar subtask yetkisini bu listeyle kontrol ediyor. Yeni düzende kontrolü route'taki yetkilerle genel motor yapar; projeye ya da subtask'e özel kod kalmaz. Liste servise iletilmez, log dokümanına bugünkü gibi kullanıcının tüm yetkileriyle yazılır.
- **Kontrolün yeri:** Endpoint seviyesindeki kontrol broker'da, veri seviyesindeki kontrol servistedir. Broker kullanıcı bağlamını imzalı bir header ile iletir; servisler dışarıdan doğrudan erişilemez.

Broker kullanıcının yetkilerini bugünkü gibi Redis'ten, login'de kullanıcı kimliğinin karşısına yazılan kayıttan okur; istek başına tek bir Redis okumasıdır. Route kontrolü de loga yazılan `perm_list` de bu okumadan çıkar.

## Merkezi veri modeli

Merkezi DB; tanımı, geçmişini, ortam durumunu ve onay akışını tutar. Ortamlardaki broker'lar bu DB'yi hiç okumaz.

| Tablo | Ne tutar | Yazan |
| --- | --- | --- |
| environment | Ortamlar (DEV, TEST, PROD; TEST, PREPROD olarak da anılır), promote sırası, onay ve test izni | DBA (migration) |
| response\_profile | Yanıt profilinin güncel hali | Merkezi admin |
| response\_profile\_history | Her profil versiyonunun tam snapshot'ı | Merkezi admin, yalnızca INSERT |
| project | Proje servisindeki ID, maintask, görünen ad, yetki öneki ve varsayılan yanıt profili | Merkezi admin |
| project\_environment | Ortam başına base URL ve spec yolu | Merkezi admin |
| route\_definition | Subtask'in güncel hali, subtask başına tek satır | Merkezi admin |
| route\_definition\_history | Her route versiyonunun tam snapshot'ı | Merkezi admin, yalnızca INSERT |
| route\_deployment | Ortam başına aktif route versiyonu | Merkezi admin |
| response\_profile\_deployment | Ortam başına aktif profil versiyonu | Merkezi admin |
| promotion\_request | Route ve profil taşıma talepleri, onaylar, yoklama sonucu | Merkezi admin |

&#91;embedded content: Merkezi DB · ER diyagramı, 7 tablo\]

Oklar FK'den referans verilen tabloya gider. Deployment ve talepler tanımın kendisine değil, history'deki bir versiyona bağlanır; history'de olmayan bir versiyon hiçbir ortama çıkamaz. Profil tabloları aynı deseni izler; diyagramları Yanıt profilleri bölümündedir.

Kısıtlar, kuralları uygulama hatalarına karşı DB seviyesinde de korur:

- **`ck_route_permission`:** Anonim olmayan bir route en az bir yetki olmadan kaydedilemez.
- **`trg_route_permissions`:** `permissions` listesindeki her yetki projenin önekiyle, `foreign_permissions` listesindekiler kayıtlı başka bir projenin önekiyle başlar.
- **`uq_project_maintask`, `uq_route_definition`:** maintask ve proje içindeki subtask adı büyük/küçük harf fark etmeden tekildir.
- **`ck_project_prefix`:** Yetki öneki büyük harf, rakam ve alt çizgiyle yazılır.
- **`trg_route_history_append_only`, `trg_profile_history_append_only` + yalnızca INSERT yetkisi:** Geçmiş, tablo sahibi tarafından bile değiştirilemez.
- **Ad değişmezliği (`forbid_column_change`):** maintask, subtask, profil adı, projenin yetki öneki ve proje servisindeki ID'si bir kez yazıldıktan sonra değiştirilemez; boş bir değeri doldurmak serbesttir. Bir route başka projeye de taşınamaz. Proje servisi ID'si olmayan bir proje yalnızca UNLINKED durumunda tutulabilir.
- **Deployment ve talep FK'leri:** History'de olmayan bir route ya da profil versiyonu hiçbir ortama çıkamaz.
- **`ck_promotion_subject`:** Bir talep ya bir route ya da bir profil versiyonu taşır, ikisini birden taşıyamaz. Profil talepleri yalnızca DEPLOY ve ROLLBACK alır (`ck_profile_action`).
- **`uq_promotion_open`, `uq_promotion_open_profile`:** Aynı route ya da profil ve hedef ortam için aynı anda tek açık talep olabilir.
- **`ck_four_eyes`:** Talep eden kendi talebini onaylayamaz.

DDL PostgreSQL 16'da çalıştırıldı; bu kısıtlar ve trigger'lar örnek verilerle denendi.

```sql
-- Merkezi yönetim veritabanı (control plane), PostgreSQL 14+
-- Rolleri DBA oluşturur: broker_admin_app (merkezi admin backend'i)

CREATE SCHEMA IF NOT EXISTS broker_admin;
SET search_path TO broker_admin;

CREATE TABLE environment (
    code                VARCHAR(16)  PRIMARY KEY,                 -- DEV, TEST, PROD
    name                VARCHAR(64)  NOT NULL,
    sort_order          SMALLINT     NOT NULL UNIQUE,             -- promote sırası: 1 → 2 → 3
    admin_api_url       VARCHAR(512) NOT NULL,                    -- ortamdaki yönetim API'si
    requires_approval   BOOLEAN      NOT NULL DEFAULT FALSE,      -- PROD için true
    test_calls_allowed  BOOLEAN      NOT NULL DEFAULT FALSE       -- "test et" yalnızca DEV/TEST
);

-- History tabloları yalnızca ekleme kabul eder
CREATE FUNCTION forbid_history_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% append-only: % izin verilmez', TG_TABLE_NAME, TG_OP;
END;
$$;

-- Frontend sözleşmesinin parçası olan anahtarlar bir kez yazıldıktan sonra değiştirilemez;
-- boş bir değeri doldurmak serbesttir
CREATE FUNCTION forbid_column_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    col TEXT;
BEGIN
    FOREACH col IN ARRAY TG_ARGV LOOP
        IF to_jsonb(OLD) -> col <> 'null'::jsonb
           AND to_jsonb(NEW) -> col IS DISTINCT FROM to_jsonb(OLD) -> col THEN
            RAISE EXCEPTION '%.% değiştirilemez', TG_TABLE_NAME, col;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

-- Yanıt profilleri: projeye özel response zarfları
CREATE TABLE response_profile (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,                -- ör. order-service; runtime'da referans
    description  TEXT,
    definition   JSONB        NOT NULL,                       -- success ve error şablonları
    version      INTEGER      NOT NULL DEFAULT 1,             -- optimistic lock; history ile aynı numara
    created_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(100) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_profile_name_immutable
    BEFORE UPDATE OF name ON response_profile
    FOR EACH ROW EXECUTE FUNCTION forbid_column_change('name');

CREATE TABLE response_profile_history (
    profile_id  BIGINT       NOT NULL REFERENCES response_profile (id),
    version     INTEGER      NOT NULL,
    action      VARCHAR(16)  NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'ROLLBACK')),
    snapshot    JSONB        NOT NULL,                        -- response_profile satırının tam hali
    changed_by  VARCHAR(100) NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    comment     TEXT,
    PRIMARY KEY (profile_id, version)
);

CREATE TRIGGER trg_profile_history_append_only
    BEFORE UPDATE OR DELETE ON response_profile_history
    FOR EACH ROW EXECUTE FUNCTION forbid_history_change();

CREATE TABLE project (
    id                           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_project_id          VARCHAR(64)  UNIQUE,              -- proje servisindeki ID; geçişte boş kalabilir
    maintask                     VARCHAR(100) NOT NULL,            -- frontend'in gönderdiği ad; tekilliği harf duyarsız
    name                         VARCHAR(200) NOT NULL,            -- proje servisinden gelen görünen ad
    permission_prefix            VARCHAR(50)  NOT NULL UNIQUE,     -- yetki adlarının öneki, ör. LOGPROJECT
    catalog_status               VARCHAR(16)  NOT NULL DEFAULT 'LINKED'
                                 CHECK (catalog_status IN ('LINKED', 'MISSING', 'UNLINKED')),
    catalog_synced_at            TIMESTAMPTZ,
    owner_team                   VARCHAR(100),
    description                  TEXT,
    default_response_profile_id  BIGINT REFERENCES response_profile (id),  -- yeni subtask ekranında ön seçim
    created_by                   VARCHAR(100) NOT NULL,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_catalog CHECK ((external_project_id IS NULL) = (catalog_status = 'UNLINKED')),
    CONSTRAINT ck_project_prefix CHECK (permission_prefix ~ '^[A-Z][A-Z0-9_]*[A-Z0-9]$')
);

CREATE TRIGGER trg_project_keys_immutable
    BEFORE UPDATE OF maintask, external_project_id, permission_prefix ON project
    FOR EACH ROW EXECUTE FUNCTION forbid_column_change('maintask', 'external_project_id', 'permission_prefix');

-- Broker maintask'i büyük/küçük harf fark etmeden eşler; COLLATE "C" küçültmeyi dil ayarından bağımsız yapar
CREATE UNIQUE INDEX uq_project_maintask ON project (lower(maintask COLLATE "C"));

CREATE TABLE project_environment (
    project_id   BIGINT       NOT NULL REFERENCES project (id),
    environment  VARCHAR(16)  NOT NULL REFERENCES environment (code),
    base_url     VARCHAR(512) NOT NULL,                           -- forward hedefi, ortama özgü
    spec_path    VARCHAR(256) NOT NULL DEFAULT '/v3/api-docs',
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_id, environment)
);

-- Güncel hal: subtask başına tek satır
CREATE TABLE route_definition (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT       NOT NULL REFERENCES project (id),
    subtask              VARCHAR(150) NOT NULL,
    http_method          VARCHAR(8)   NOT NULL
                         CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    path_template        VARCHAR(512) NOT NULL,                   -- ör. /orders/{id}
    permissions          TEXT[]       NOT NULL DEFAULT '{}',      -- projenin kendi yetkileri; biri yeterli
    foreign_permissions  TEXT[]       NOT NULL DEFAULT '{}',      -- başka projelerden eklenen yetkiler
    anonymous            BOOLEAN      NOT NULL DEFAULT FALSE,
    response_profile_id  BIGINT       REFERENCES response_profile (id),  -- NULL: yanıt olduğu gibi döner
    definition           JSONB        NOT NULL,                   -- eşleme, header, response, timeout, log politikası
    spec_operation       JSONB,                                   -- tanım anındaki operasyon, $ref'ler çözülmüş
    spec_fingerprint     CHAR(64),                                -- spec_operation'ın SHA-256'sı
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version              INTEGER      NOT NULL DEFAULT 1,         -- optimistic lock; history ile aynı numara
    created_by           VARCHAR(100) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           VARCHAR(100) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_route_permission CHECK (anonymous OR cardinality(permissions) + cardinality(foreign_permissions) > 0)
);

CREATE TRIGGER trg_route_name_immutable
    BEFORE UPDATE OF project_id, subtask ON route_definition
    FOR EACH ROW EXECUTE FUNCTION forbid_column_change('project_id', 'subtask');

-- Projenin kendi yetkileri onun önekiyle başlar; başka projeden eklenen yetki,
-- kayıtlı başka bir projenin önekini taşır
CREATE FUNCTION check_route_permissions() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    own   TEXT;
    code  TEXT;
BEGIN
    SELECT permission_prefix || '_' INTO own FROM project WHERE id = NEW.project_id;
    FOREACH code IN ARRAY coalesce(NEW.permissions, '{}') LOOP
        IF NOT starts_with(code, own) OR length(code) = length(own) THEN
            RAISE EXCEPTION 'proje yetkisi projenin önekiyle başlamalı: % (önek %)', code, own;
        END IF;
    END LOOP;
    FOREACH code IN ARRAY coalesce(NEW.foreign_permissions, '{}') LOOP
        IF starts_with(code, own) OR NOT EXISTS (
               SELECT 1 FROM project p
                WHERE p.id <> NEW.project_id
                  AND starts_with(code, p.permission_prefix || '_')
                  AND length(code) > length(p.permission_prefix) + 1) THEN
            RAISE EXCEPTION 'başka projeden yetki kayıtlı başka bir projenin önekiyle başlamalı: %', code;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_route_permissions
    BEFORE INSERT OR UPDATE OF permissions, foreign_permissions ON route_definition
    FOR EACH ROW EXECUTE FUNCTION check_route_permissions();

-- Subtask adı proje içinde büyük/küçük harf fark etmeden tekildir
CREATE UNIQUE INDEX uq_route_definition ON route_definition (project_id, lower(subtask COLLATE "C"));

CREATE INDEX ix_route_definition_permissions ON route_definition USING GIN (permissions, foreign_permissions);
CREATE INDEX ix_route_definition_endpoint    ON route_definition (project_id, http_method, path_template);
CREATE INDEX ix_route_definition_profile     ON route_definition (response_profile_id);

-- Geçmiş: append-only, her değişiklikte tam snapshot
CREATE TABLE route_definition_history (
    definition_id  BIGINT       NOT NULL REFERENCES route_definition (id),
    version        INTEGER      NOT NULL,
    action         VARCHAR(16)  NOT NULL
                   CHECK (action IN ('CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE', 'ROLLBACK')),
    snapshot       JSONB        NOT NULL,                         -- route_definition satırının tam hali
    changed_by     VARCHAR(100) NOT NULL,
    changed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    comment        TEXT,
    PRIMARY KEY (definition_id, version)
);

CREATE TRIGGER trg_route_history_append_only
    BEFORE UPDATE OR DELETE ON route_definition_history
    FOR EACH ROW EXECUTE FUNCTION forbid_history_change();

-- Hangi ortamda hangi route ve profil versiyonu aktif
CREATE TABLE route_deployment (
    definition_id      BIGINT       NOT NULL,
    environment        VARCHAR(16)  NOT NULL REFERENCES environment (code),
    version            INTEGER      NOT NULL,
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    activated_by       VARCHAR(100) NOT NULL,
    activated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_probe_status  VARCHAR(24),
    last_probe_at      TIMESTAMPTZ,
    PRIMARY KEY (definition_id, environment),
    FOREIGN KEY (definition_id, version)
        REFERENCES route_definition_history (definition_id, version)
);

CREATE TABLE response_profile_deployment (
    profile_id    BIGINT       NOT NULL,
    environment   VARCHAR(16)  NOT NULL REFERENCES environment (code),
    version       INTEGER      NOT NULL,
    activated_by  VARCHAR(100) NOT NULL,
    activated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, environment),
    FOREIGN KEY (profile_id, version)
        REFERENCES response_profile_history (profile_id, version)
);

-- Ortamlar arası taşıma ve onay akışı; aynı zamanda deployment audit'i
-- Bir talep ya bir route versiyonunu ya da bir profil versiyonunu taşır
CREATE TABLE promotion_request (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    definition_id    BIGINT,                                       -- route talebi
    version          INTEGER,
    profile_id       BIGINT,                                       -- profil talebi
    profile_version  INTEGER,
    source_env       VARCHAR(16)  REFERENCES environment (code),   -- DEV'e ilk kurulumda NULL
    target_env       VARCHAR(16)  NOT NULL REFERENCES environment (code),
    action           VARCHAR(16)  NOT NULL DEFAULT 'DEPLOY'
                     CHECK (action IN ('DEPLOY', 'DISABLE', 'ENABLE', 'ROLLBACK')),
    status           VARCHAR(20)  NOT NULL
                     CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'APPLYING',
                                       'APPLIED', 'FAILED', 'CANCELLED')),
    requested_by     VARCHAR(100) NOT NULL,
    requested_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason           TEXT,
    decided_by       VARCHAR(100),
    decided_at       TIMESTAMPTZ,
    decision_note    TEXT,
    probe_status     VARCHAR(24),                                  -- profil taleplerinde boş
    probe_detail     JSONB,
    config_version   BIGINT,                                       -- ortamda oluşan runtime versiyonu
    applied_at       TIMESTAMPTZ,
    failure_reason   TEXT,
    FOREIGN KEY (definition_id, version)
        REFERENCES route_definition_history (definition_id, version),
    FOREIGN KEY (profile_id, profile_version)
        REFERENCES response_profile_history (profile_id, version),
    CONSTRAINT ck_promotion_subject CHECK (
           (definition_id IS NOT NULL AND version IS NOT NULL
            AND profile_id IS NULL AND profile_version IS NULL)
        OR (definition_id IS NULL AND version IS NULL
            AND profile_id IS NOT NULL AND profile_version IS NOT NULL)),
    CONSTRAINT ck_profile_action CHECK (profile_id IS NULL OR action IN ('DEPLOY', 'ROLLBACK')),
    CONSTRAINT ck_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

-- Aynı route ya da profil ve hedef ortam için aynı anda tek açık talep
CREATE UNIQUE INDEX uq_promotion_open
    ON promotion_request (definition_id, target_env)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING');

CREATE UNIQUE INDEX uq_promotion_open_profile
    ON promotion_request (profile_id, target_env)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING');

CREATE INDEX ix_promotion_status ON promotion_request (status, requested_at);

-- Uygulama kullanıcısı: history'lerde yalnızca okuma ve ekleme, hiçbir tabloda silme yok
GRANT USAGE ON SCHEMA broker_admin TO broker_admin_app;
GRANT SELECT ON environment TO broker_admin_app;
GRANT SELECT, INSERT, UPDATE ON response_profile, project, project_environment, route_definition,
                                route_deployment, response_profile_deployment, promotion_request
      TO broker_admin_app;
GRANT SELECT, INSERT ON route_definition_history, response_profile_history TO broker_admin_app;
```

Bir route tanımını ya da profili değiştirmek, güncel satırı ve history kaydını tek transaction'da yazar. Snapshot, satırın kendisinden üretilir:

```sql
BEGIN;
UPDATE route_definition
   SET definition = :definition, permissions = :permissions, foreign_permissions = :foreign_permissions,
       version = version + 1, updated_by = :user, updated_at = now()
 WHERE id = :id AND version = :expected_version     -- 0 satır: başkası değiştirmiş, ekranda göster
RETURNING version;

INSERT INTO route_definition_history (definition_id, version, action, snapshot, changed_by, comment)
SELECT id, version, 'UPDATE', to_jsonb(rd), updated_by, :comment
  FROM route_definition rd
 WHERE id = :id;
COMMIT;
```

## Ortam runtime DB'si ve cache senkronizasyonu

Her ortamın kendi runtime DB'si vardır. Bu DB'yi broker'lar yalnızca okur, yalnızca ortamın Admin API'si yazar. Merkez çökse bile routing çalışmaya devam eder, yalnızca değişiklik yapılamaz.

```sql
-- Ortam runtime veritabanı: her ortamda ayrı, PostgreSQL 14+
-- Broker'lar okur (broker_runtime_reader), ortam Admin API yazar (broker_runtime_writer)

CREATE SCHEMA IF NOT EXISTS broker_runtime;
SET search_path TO broker_runtime;

CREATE TABLE runtime_project (
    maintask    VARCHAR(100) PRIMARY KEY,
    base_url    VARCHAR(512) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE runtime_response_profile (
    name          VARCHAR(100) PRIMARY KEY,
    profile_id    BIGINT       NOT NULL,                      -- merkezi profilin ID'si
    version       INTEGER      NOT NULL,                      -- merkezi versiyon; loglara yazılır
    definition    JSONB        NOT NULL,
    applied_by    VARCHAR(100) NOT NULL,
    promotion_id  BIGINT       NOT NULL,                      -- merkezi promotion_request.id
    applied_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE runtime_route (
    maintask          VARCHAR(100) NOT NULL REFERENCES runtime_project (maintask),
    subtask           VARCHAR(150) NOT NULL,
    definition_id     BIGINT       NOT NULL,                  -- merkezi tanımın ID'si
    version           INTEGER      NOT NULL,                  -- merkezi versiyon; loglara route_version olarak yazılır
    http_method       VARCHAR(8)   NOT NULL,
    path_template     VARCHAR(512) NOT NULL,
    permissions       TEXT[]       NOT NULL DEFAULT '{}',     -- proje ve başka proje yetkileri; biri yeterli
    anonymous         BOOLEAN      NOT NULL DEFAULT FALSE,
    response_profile  VARCHAR(100) REFERENCES runtime_response_profile (name),  -- ortamda olmayan profile referans verilemez
    definition        JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    applied_by        VARCHAR(100) NOT NULL,
    promotion_id      BIGINT       NOT NULL,                  -- merkezi promotion_request.id
    applied_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (maintask, subtask),
    CONSTRAINT ck_runtime_permission CHECK (anonymous OR cardinality(permissions) > 0)
);

-- Cache senkronizasyonu için tek satırlık global sayaç
CREATE TABLE runtime_config_version (
    id          SMALLINT    PRIMARY KEY CHECK (id = 1),
    version     BIGINT      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO runtime_config_version (id, version) VALUES (1, 0);

GRANT USAGE ON SCHEMA broker_runtime TO broker_runtime_reader, broker_runtime_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA broker_runtime TO broker_runtime_reader;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA broker_runtime TO broker_runtime_writer;
```

Admin API bir snapshot'ı tek ifadeyle uygular. Daha eski ya da tekrar gelen bir talep yazamaz, çünkü `promotion_id` sırayla artar. Hiçbir şey değişmezse versiyon da artmaz ve ifade satır döndürmez.

```sql
WITH applied AS (
    INSERT INTO runtime_route AS r (maintask, subtask, definition_id, version, http_method, path_template,
                                    permissions, anonymous, response_profile, definition, status,
                                    applied_by, promotion_id)
    VALUES (:maintask, :subtask, :definition_id, :version, :method, :path,
            :permissions, :anonymous, :response_profile, :definition, 'ACTIVE', :user, :promotion_id)
    ON CONFLICT (maintask, subtask) DO UPDATE
       SET definition_id = EXCLUDED.definition_id, version = EXCLUDED.version,
           http_method = EXCLUDED.http_method, path_template = EXCLUDED.path_template,
           permissions = EXCLUDED.permissions, anonymous = EXCLUDED.anonymous,
           response_profile = EXCLUDED.response_profile, definition = EXCLUDED.definition,
           status = EXCLUDED.status, applied_by = EXCLUDED.applied_by,
           promotion_id = EXCLUDED.promotion_id, applied_at = now()
     WHERE EXCLUDED.promotion_id > r.promotion_id          -- eski ya da tekrar gelen talep yazamaz
    RETURNING 1
)
UPDATE runtime_config_version
   SET version = version + 1, updated_at = now()
 WHERE id = 1 AND EXISTS (SELECT 1 FROM applied)
RETURNING version;
-- Satır dönerse commit sonrası: PUBLISH broker:routes-changed <version>
```

Profiller de aynı desenle uygulanır. `runtime_route`'taki FK ortamda olmayan bir profile referans verilmesini engeller; bu yüzden profil, onu kullanan route'lardan önce uygulanır.

```sql
WITH applied AS (
    INSERT INTO runtime_response_profile AS p (name, profile_id, version, definition, applied_by, promotion_id)
    VALUES (:name, :profile_id, :version, :definition, :user, :promotion_id)
    ON CONFLICT (name) DO UPDATE
       SET profile_id = EXCLUDED.profile_id, version = EXCLUDED.version,
           definition = EXCLUDED.definition, applied_by = EXCLUDED.applied_by,
           promotion_id = EXCLUDED.promotion_id, applied_at = now()
     WHERE EXCLUDED.promotion_id > p.promotion_id
    RETURNING 1
)
UPDATE runtime_config_version
   SET version = version + 1, updated_at = now()
 WHERE id = 1 AND EXISTS (SELECT 1 FROM applied)
RETURNING version;
-- Satır dönerse commit sonrası: PUBLISH broker:routes-changed <version>
```

Kill switch aynı deseni kullanır: `status = 'DISABLED'` yazar ve versiyonu artırır. Broker'lar versiyonu, profilleri ve route'ları tek bir tutarlı snapshot'tan okur:

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT version FROM runtime_config_version WHERE id = 1;
SELECT name, version, definition FROM runtime_response_profile;
SELECT r.maintask, r.subtask, r.version, r.http_method, r.path_template, r.permissions,
       r.anonymous, r.response_profile, r.definition, p.base_url
  FROM runtime_route r
  JOIN runtime_project p ON p.maintask = r.maintask
 WHERE r.status = 'ACTIVE' AND p.enabled;
COMMIT;
```

Broker tarafında istek yolu yalnızca belleğe dokunur; yenileme iki tetikleyiciyle olur:

```java
private volatile RouteSnapshot snapshot = RouteSnapshot.EMPTY; // değişmez nesne

public Optional<Route> find(String maintask, String subtask) {  // istek yolu: yalnızca bellek
    return snapshot.find(maintask.toLowerCase(Locale.ROOT),   // harf duyarsız; ROOT olmazsa
                         subtask.toLowerCase(Locale.ROOT));   // Türkçe JVM'de "I" → "ı" olur
}

public void onRoutesChanged(long version) {                    // Redis pub/sub dinleyicisi
    if (version > snapshot.version()) reload();
}

@Scheduled(fixedDelay = 15_000)                                // kaçırılan mesajlar için güvenlik ağı
public void verify() {
    if (repository.currentVersion() > snapshot.version()) reload();
}

private synchronized void reload() {
    RouteSnapshot fresh = repository.loadSnapshot();           // yukarıdaki REPEATABLE READ okuması
    if (fresh.version() > snapshot.version()) snapshot = fresh; // atomik referans değişimi
}
```

- **Açılış:** Yükleme `ApplicationRunner` içinde yapılır; Spring Boot, runner bitene kadar instance'ı trafiğe hazır saymaz.
- **Profiller:** Şablonlar yüklemede bir kez derlenir; istek yolunda yalnızca yer tutucular doldurulur.
- **Bozuk kayıt:** Ayrıştırılamayan bir route ya da profil atlanır ve alarm üretir; bozuk bir profili kullanan route'lar da onunla birlikte atlanır. Diğer kayıtların yüklenmesi engellenmez.
- **Tutarlılık penceresi:** Pub/sub mesajı kaçan bir instance en geç 15 saniye içinde yetişir.
- **Yayılım kontrolü:** Her instance yüklediği versiyonu `broker:loaded:{instanceId}` anahtarına 60 saniye TTL ile yazar. Merkezi admin böylece "3/3 instance v43'te" bilgisini gösterebilir.

## Spec karşılaştırma, yoklama ve test

DevOps endpoint'leri elle yazmaz. Servisin OpenAPI spec'i ortamın Admin API'si üzerinden çekilir ve route tablosuyla karşılaştırılır.

### Spec karşılaştırma

- **Eşleştirme anahtarı:** method + path. operationId kullanılmaz; springdoc varsayılan olarak metot adını alır, aynı isimli metotlara `_1`, `_2` ekler ve bu numaralar yeni controller'larla kayabilir.
- **Fingerprint:** Operasyonun routing'i ilgilendiren kısmı (parametreler, request body şeması, content type'lar) `$ref`'leri çözülerek ve anahtarları sıralanarak tek bir JSON'a indirgenir; SHA-256'sı `spec_fingerprint` olarak saklanır. `$ref`'ler çözülmezse DTO'ya alan eklendiğinde fark görünmez.

| Grup | Koşul | Ekranda |
| --- | --- | --- |
| Yeni | Spec'te var, route yok | "Subtask olarak aç"; form spec'ten dolu gelir |
| Değişen | İkisinde de var, fingerprint farklı | Eski ve yeni operasyonun farkı; tanımın güncellenmesi önerilir |
| Kaybolan | Route var, spec'te yok | Öne çıkarılır; ilgili ortamda hata verecek route'lar bunlar |
| Aynı | Fingerprint eşit | Sade listede kalır |

### Örnek istekten şablon

Mevcut subtask'leri hızlı tanımlamak için DevOps, frontend'in attığı isteği yapıştırır; admin bundan taslak bir tanım çıkarır.

1. **Yapıştır:** Tarayıcının geliştirici araçlarından kopyalanan istek gövdesi ya da "Copy as cURL" çıktısı yapıştırılır. `maintask` ve `subtask` gövdeden okunur; mevcut subtask adı olduğu gibi korunur.
2. **Alanlar:** Zarf alanları (`maintask`, `subtask`, `application`, `uid`) dışındaki her alan `fields`'a eklenir. Tip değerden çıkarılır, iç içe nesneler noktalı yollara açılır, hepsi zorunlu işaretlenir; DevOps gerekenleri düzeltir.
3. **Eşleme:** Hedef endpoint spec'ten seçilir. Alanlar path variable, query param ve body şemasıyla ad üzerinden eşlenir; büyük-küçük harf ve snake\_case/camelCase farkı yok sayılır. Eşleşmeyen alanlar ve karşılığı olmayan zorunlu servis parametreleri ekranda işaretlenir.
4. **Test:** Taslak, yapıştırılan örnekle test ekranında açılır; DEV'de denendikten sonra kaydedilir.

- **Güvenlik:** "Copy as cURL" çıktısı oturum token'ını da içerir. `X-Token`, `Cookie` ve `Authorization` header'ları yapıştırıldığı anda atılır, ham metin hiçbir yerde saklanmaz ve örnek istek maskelenerek tutulur.
- **Elastic'ten örnek:** Mevcut subtask'lerde yapıştırmaya da gerek kalmayabilir. Elastic'te her subtask'in gerçek istekleri maskelenmiş olarak duruyor; admin, seçilen subtask'in son isteğini oradan çekip aynı çıkarımı yapabilir.

### OPTIONS yoklaması

Spring MVC her `@RequestMapping` için OPTIONS'ı kendiliğinden destekler. Path'i eşleşen bir metot varsa controller çalışmadan 200 ve `Allow` header'ı döner; eşleşen path yoksa 404 döner. Yan etkisi yoktur, bu yüzden prod'da da çalışır.

```java
URI uri = UriComponentsBuilder.fromUriString(project.baseUrl()) // host formdan değil, kayıtlı base URL'den
        .path(route.path())                                    // ör. /orders/{id}
        .encode()
        .buildAndExpand(sample.pathVariables())                // {id} → 123 (regex kısıtı varsa ona uygun)
        .toUri();

try {
    Set<HttpMethod> allowed = restTemplate
            .exchange(uri, HttpMethod.OPTIONS, new HttpEntity<>(internalAuthHeaders()), Void.class)
            .getHeaders().getAllow();
    return allowed.contains(route.method()) ? ProbeResult.OK            // path ve method doğru
                                            : ProbeResult.WRONG_METHOD; // path var, method yanlış
} catch (HttpClientErrorException.NotFound e) {
    return ProbeResult.PATH_NOT_FOUND;
} catch (HttpClientErrorException e) {
    return ProbeResult.INCONCLUSIVE;                                    // 401/403: kimlik sorunu, "yok" değil
}
```

Yoklama üç yerde çalışır: kayıt anında, her promote'tan önce hedef ortamda ve her gece tüm aktif route'lar için drift kontrolünde. Prod'da `/v3/api-docs` kapalı olsa da prod'a giriş yoklamayla doğrulanır. İlk kurulumda var olduğu bilinen bir endpoint ve uydurma bir path ile kalibre edilmelidir; arada OPTIONS'ı kendisi cevaplayan bir proxy varsa burada ortaya çıkar.

### Test isteği

"Test et" yalnızca `test_calls_allowed = true` olan ortamlarda (DEV, TEST) açıktır; prod'da POST, PUT ve DELETE gerçek kayıt oluşturur. İstek, broker'ın yalnızca dev/test profillerinde açık olan bir iç endpoint'inden ve gerçek isteklerle aynı `RequestBuilder`'dan geçer. Ayrı bir kodla test etmek broker'ı değil o kodu test eder. Örnek gövde, route'un `fields` tanımından üretilen bir formla ya da yapıştırılan bir istekle girilir; doğrulama hataları da gerçek istekteki gibi gösterilir.

Ekran önce üretilen isteği (method, tam URL, header'lar, body), sonra servisin yanıtını (status, header'lar, body, süre), profil varsa frontend'in alacağı zarflı yanıtı ve yorumu gösterir:

| Sonuç | Anlamı |
| --- | --- |
| 2xx | Çalışıyor |
| 400 | Endpoint bulundu ama parametre/body bağlanamadı: isim, tip ya da zorunlu alan sorunu |
| 404 | Path yok ya da controller "kayıt bulunamadı" dedi; yoklama geçtiyse ikincisi |
| 405 | Path var, HTTP method yanlış |
| 415 / 406 | Body formatı ya da seçilen response tipi servisle uyuşmuyor |
| 401 / 403 | Servisin güvenliği devrede; test isteğinin kimliği eksik |
| 5xx | Endpoint'e ulaşıldı, hata iş mantığında (çoğu zaman örnek veriden) |

Servislerin dev profilinde `server.error.include-message=always` açılırsa 400 yanıtı eksik ya da hatalı parametreyi adıyla söyler. Prod'da açılmamalıdır; iç detay sızdırır.

## Promote akışı

Bir versiyon yalnızca bir önceki ortamda aktifken bir sonrakine taşınır. PROD'a geçiş onay ve hedef ortamda başarılı bir yoklama ister.

1. **Talep:** DevOps, kaynak ortamda aktif olan versiyonu seçip hedef ortam için talep açar; gerekçeyi ya da değişiklik kaydı numarasını girer.
2. **Ön kontroller (merkez):**
   - Versiyon kaynak ortamda ACTIVE mi, hedef ortam kaynak ortamın hemen sonrası mı (`sort_order + 1`)?
   - Aynı tanım ve hedef ortam için açık talep var mı (`uq_promotion_open`)?
   - Route'un yetkileri hedef ortamın yetkilendirme servisinde tanımlı mı?
   - Route'un yanıt profili hedef ortamda aktif mi (`response_profile_deployment`)?
   - Proje hedef ortamda tanımlı ve açık mı (`project_environment.enabled`)?
3. **Onay:** Hedef ortamda `requires_approval = true` ise talep `PENDING_APPROVAL` olur. Onaylayan, hedef ortamdaki mevcut versiyonla taşınan versiyonun farkını görür (başka projeden eklenen yetkiler ayrıca işaretlenir) ve talep edenden farklı biri olmak zorundadır (`ck_four_eyes`). Onay gerekmeyen ortamlarda talep doğrudan `APPLYING` olur.
4. **Yoklama:** Merkez, hedef ortamın Admin API'sinden OPTIONS yoklaması ister. Sonuç OK değilse talep `FAILED` olur, örneğin backend henüz o ortama deploy edilmemiştir. Hedef ortamda hiçbir şey değişmez.
5. **Uygulama:** Merkez snapshot'ı `promotion_id` ile Admin API'ye gönderir. Admin API runtime tablosunu ve config versiyonunu tek ifadeyle yazar, commit sonrası Redis'e sinyal verir.
6. **Yayılım:** Broker'lar yeni snapshot'ı yükler. Merkez instance'ların yüklediği versiyonu izler ve yetişmeyen instance'ı uyarı olarak gösterir.
7. **Kayıt:** `route_deployment` (profilde `response_profile_deployment`) hedef ortam için yeni versiyona güncellenir. Talep `APPLIED` olur ve oluşan `config_version` yazılır; talep eden ve onaylayan bilgilendirilir.

Yanıt profilleri de aynı akıştan geçer; yalnızca 4. adım (yoklama) yoktur. Onaylayan, farkın yanında profili hedef ortamda kullanan route'ları da görür.

&#91;embedded content: TEST'ten PROD'a promote · 7 adım, 2 kapı\]

Numaralar yukarıdaki adımlarla eşleşir. Yoklama başarısız olursa akış 4. adımda durur ve PROD'da hiçbir şey değişmez.

&#91;embedded content: promotion\_request yaşam döngüsü · 7 durum\]

Durumlar `promotion_request.status` kolonundaki değerlerdir; açık durumların tekliğini `uq_promotion_open` index'i sağlar.

### Hata durumları

| Durum | Talebin sonucu | Ne yapılır |
| --- | --- | --- |
| Yoklama başarısız | FAILED, hedef ortam değişmez | Backend deploy'u beklenir, talep yeniden denenir |
| Admin API'ye ulaşılamıyor | FAILED, hedef ortam değişmez | Yeniden denenir |
| Commit oldu ama yanıt kayboldu | APPLYING'de kalır | Yeniden denenir; uygulama ifadesi satır döndürmez, Admin API route'taki `promotion_id` aynıysa başarılı sayar |
| Redis sinyali gönderilemedi | APPLIED | Broker'lar en geç 15 saniyede versiyon kontrolüyle yetişir |
| Eski bir talep gecikmeli ulaştı | Yazılmaz | `promotion_id` karşılaştırması daha yeni versiyonu korur |
| Route'un profili hedef ortamda yok | Ön kontrolde durur, hedef ortam değişmez | Önce profil, sonra route taşınır |

### Geri alma

- **Ortamı geri almak:** Hedef ortama önceki versiyonu taşıyan bir `ROLLBACK` talebi açılır ve aynı akıştan geçer. Acil durumda `DISABLE` talebi route'u kill switch ile hemen kapatır.
- **Tanımı geri almak:** Eski bir snapshot güncel hale yazılır ve history'ye `ROLLBACK` aksiyonlu yeni bir versiyon olarak eklenir. Bu versiyon sonra normal akışla taşınır.

## Ortam Admin API sözleşmesi

Merkez ortamlara yalnızca bu API üzerinden erişir. DB şifreleri, Redis ve servis ağına erişim ortamın içinde kalır; firewall'da ortam başına tek bir kural yeter.

| Method | Path | Ne yapar | Ortam |
| --- | --- | --- | --- |
| GET | /admin/v1/projects/{maintask}/spec | Servisin OpenAPI spec'ini çeker | Spec'in açık olduğu ortamlar |
| POST | /admin/v1/probe | OPTIONS yoklaması yapar | Hepsi |
| POST | /admin/v1/test | Kaydedilmemiş tanımla gerçek istek atar | Yalnızca DEV, TEST |
| PUT | /admin/v1/routes/{maintask}/{subtask} | Snapshot'ı uygular | Hepsi |
| PUT | /admin/v1/response-profiles/{name} | Profil snapshot'ını uygular | Hepsi |
| POST | /admin/v1/routes/{maintask}/{subtask}/disable | Kill switch | Hepsi |
| GET | /admin/v1/routes | Ortamdaki route'ları, profilleri ve config versiyonunu döner (drift kontrolü) | Hepsi |
| PUT | /admin/v1/projects/{maintask} | Base URL'i ya da proje durumunu yazar | Hepsi |
| GET | /admin/v1/cache-status | Instance'ların yüklediği versiyonları döner | Hepsi |

- **Kimlik:** mTLS kullanılır, yalnızca merkezin sertifikası kabul edilir. Her çağrı işlemi yapan kullanıcıyı ve `promotion_id`'yi taşır; Admin API bunları kendi loguna da yazar.
- **İdempotency:** `PUT /routes` aynı `promotion_id` ile tekrar çağrılabilir; ikinci çağrı değişiklik yapmadan mevcut versiyonu döner.
- **Yanıtlar:** Apply `{"applied": true, "configVersion": 43}`, probe `{"result": "OK", "status": 200, "allow": ["GET", "POST"]}` döner. Probe sonucu `OK`, `WRONG_METHOD`, `PATH_NOT_FOUND` ya da `INCONCLUSIVE` olabilir.

`PUT /admin/v1/routes/order-service/get_order` gövdesi:

```json
{
  "promotionId": 1042,
  "definitionId": 17,
  "version": 3,
  "httpMethod": "GET",
  "pathTemplate": "/orders/{id}",
  "permissions": ["ORDER_READ"],
  "anonymous": false,
  "responseProfile": "order-service",
  "definition": {
    "request": {
      "fields": [ { "name": "orderId", "type": "integer", "required": true } ],
      "pathVariables": { "id": "${request.orderId}" }
    },
    "response": { "type": "JSON" },
    "timeoutMs": 5000
  },
  "actor": "sec.zeynep"
}
```

## Loglama hattı

Bir isteğin logu Kafka'ya kalıcı olarak yazılmadan servis çağrılmaz ve frontend'e yanıt dönmez. Elastic ve ClickHouse olayları ayrı consumer group'larla okur; onların yavaşlığı istek yolunu etkilemez.

&#91;embedded content: Log hattı · broker'dan Elastic ve ClickHouse'a\]

Kesik çizgiler yalnızca hata yollarıdır. Elastic ve ClickHouse akışın tamamını bağımsız okur; biri yavaşlarsa diğerini etkilemez.

Her istek iki olay üretir ve broker ikisinin de Kafka onayını bekler:

| Olay | Ne zaman yazılır | Onay gelmezse |
| --- | --- | --- |
| REQUEST | Servis çağrılmadan önce | Servis çağrılmaz; `500` ve `{"result": false}` döner |
| RESPONSE | Yanıt frontend'e dönmeden önce | Servisin yanıtı yerine `500` ve `{"result": false}` döner |

Onay, olayın Kafka'da en az iki replikaya yazıldığı anlamına gelir. Elastic ve ClickHouse'a yazma bundan sonradır; başarısız olursa retention süresince yeniden denenir. Log Elastic'te kısa bir gecikmeyle görünür ama kaybolmaz.

Bugünkü "yanıt logu yazılamazsa yanıt dönmez" kuralı böylece korunur ve istek loguna da genişler. RESPONSE yazılamadığında servis işlemi yapmış olabilir; kullanıcı yine 500 alır ve kayıt spool'dan tamamlanır. Cevapsız kalan REQUEST'ler timeout'ları ve broker çökmelerini gösterir.

### Olay şeması

```json
{
  "request_id": "0192f1c4-7a3b-7c1e-9d2f-5b8e4a1c3d20",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "event_type": "RESPONSE",
  "event_time": "2026-10-08T11:28:41.512Z",
  "environment": "PROD",
  "maintask": "order-service",
  "subtask": "create_order",
  "route_version": 3,
  "response_profile": "order-service",
  "response_profile_version": 2,
  "application": "web",
  "uid": "u12345",
  "user_id": "u12345",
  "query_id": "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d",
  "perm_list": ["ORDER_READ", "ORDER_WRITE"],
  "token": "3f2b8c1e-9a4d-4e7b-b6c2-5d1e8f0a9c47",
  "session_operation": null,
  "client_ip": "10.20.30.40",
  "user_agent": "3f1c9a7b2d4e8f60a1b2c3d4e5f60718",
  "http_method": "POST",
  "target_path": "/orders",
  "status_code": 201,
  "outcome": "SUCCESS",
  "duration_ms": 87,
  "body": null,
  "body_size": 412,
  "body_truncated": false,
  "client_response": { "BACKEND_STATUS": 201, "status": 200, "data": "" },
  "error_message": null,
  "broker_instance": "broker-7f9c-2"
}
```

- **request\_id:** UUIDv7; zaman sıralı olduğu için Elastic ve ClickHouse'ta verimlidir. Frontend'e `X-Request-Id` header'ıyla döner; Elastic'teki ana dokümanın `_id`'si de budur.
- **maintask, subtask:** Route'un kayıtlı yazımıdır; metrikler ve ClickHouse bunu kullanır. Frontend'in gönderdiği yazım `body`'de kalır ve eski dokümana o yazılır.
- **route\_version, response\_profile, response\_profile\_version:** İsteği işleyen tanım ve zarf. Bir promote'tan sonra davranış değiştiyse hangi versiyonların çalıştığı buradan görülür. Profil yoksa iki alan boş kalır.
- **uid, user\_id:** `uid` frontend'in gövdede gönderdiği, `user_id` oturumdaki kullanıcıdır. Eski yapıda yalnızca `uid` vardı; ikisinin farklı olması kimlik taklidi denemesine işaret eder.
- **query\_id, perm\_list, token:** Eski dokümanlara bugünkü gibi yazılır. `query_id` broker'ın her istekte ürettiği rastgele UUID'dir ve yalnızca parametre dokümanlarına gider; `perm_list` kullanıcının tüm yetkileridir; `token`, `X-Token`'ın kendisidir.
- **body:** REQUEST olayında frontend'in gövdesi, maskelenmiş ve kırpılmamış olarak; eski Elastic dokümanları bundan üretilir. RESPONSE olayında yalnızca `logResponseBody` açıksa servisin yanıtı.
- **client\_response:** Frontend'e dönen gövde; `data` alanı bugünkü gibi boş string'e çevrilir. Elastic'teki `data_response` bundan yazılır; log yazılamadıysa `data_response` bugünkü gibi yazılmaz.
- **Maskeleme:** Broker'da, Kafka'dan önce yapılır. Şifre ve route'ta tanımlanan alanlar `xxxx` olur; dosya yanıtlarında gövde yerine yalnızca boyut yazılır. `X-Token` eski loglarla aynı kalsın diye maskelenmez.

### Kafka

```properties
# Topic: broker.logs.v1
partitions=12
replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false   # onaylanmış olay lider değişiminde kaybolmaz
retention.ms=604800000                 # 7 gün: sink'ler için tekrar oynatma tamponu

# Broker (producer)
acks=all                               # onay = olay en az iki replikada
enable.idempotence=true
compression.type=zstd
linger.ms=2                            # her istek kendi onayını beklediği için kısa
batch.size=131072
buffer.memory=67108864
max.block.ms=50                        # metadata ya da tampon yoksa beklemeden hata ver
request.timeout.ms=1500
delivery.timeout.ms=2000               # bir olayın onay bütçesi; aşılırsa istek 500 alır
```

- **Key:** Mesaj key'i `request_id`'dir; REQUEST ve RESPONSE aynı partition'a doğru sırayla düşer.
- **Onay bütçesi:** Bir istek iki olayın onayını bekler, her biri en fazla `delivery.timeout.ms` sürer. Lider değişimi gibi kısa kesintilerde bu sürede onay alamayan istekler 500 alır; değerler DEV'de ölçülen onay süresine göre ayarlanır.
- **Açılış:** Broker açılışta `partitionsFor("broker.logs.v1")` ile metadata'yı ısıtır ve bu bitmeden trafiğe hazır sayılmaz; aksi halde ilk istekler 50 ms sınırına takılıp 500 alabilir.
- **Partition sayısı:** Consumer paralelliğinin üst sınırıdır. 12, trafik ölçülene kadar başlangıç değeridir.

| Consumer group | Hedef | Yazma şekli | Tekrar gelen olay |
| --- | --- | --- | --- |
| broker-logs-elastic | Elastic `requests_yyyy-MM` (eski yapı) | Bulk API, `update` + `doc_as_upsert` | Aynı `_id`'ye aynı içerik yazılır, sonuç değişmez |
| broker-logs-clickhouse | ClickHouse `broker_log` | Toplu insert (1 sn ya da 10.000 satır) | ReplacingMergeTree birleştirmede tekilleştirir |

Offset yalnızca yazma başarılı olunca commit edilir. Sink düzelince kaldığı yerden devam eder; retention süresince veri kaybolmaz. İşlenemeyen mesaj `broker.logs.v1.dlq`'ya yazılır ve partition'ı durdurmaz. ClickHouse için özel consumer yerine ClickHouse'un Kafka table engine'i ya da resmi Kafka Connect sink'i de kullanılabilir.

### Spool

Spool artık bir yanıtın dönmesi için yeterli sayılmaz. Yalnızca log yazılamadığı için 500 dönen isteklerin kaydını sonradan tamamlar.

- Böyle bir istekte broker olayları `outcome = LOG_UNAVAILABLE` ve frontend'e dönen `{"result": false}` ile yerel diske JSON satırları olarak yazar (`/var/lib/broker/log-spool/`).
- Kafka düzelince bir arka plan görevi bunları sırayla gönderir. Böylece "servis işlemi yaptı ama kullanıcı 500 aldı" durumu Elastic'te `broker` alanlarında görünür; `data_response` bugünkü gibi yazılmaz.
- Süresi dolan bir gönderim Kafka'ya yine de yazılmış olabilir; sink'ler tekrar gelen olayı zaten tekilleştirir.
- Spool kaybolursa garanti bozulmaz, yalnızca bu tamamlama eksik kalır. Kalıcı bir volume önerilir ama zorunlu değildir; spool'da veri olması alarm üretir.

### ClickHouse

TTL'deki 180 gün ve 400 gün örnek değerlerdir; saklama süreleri açık bir karardır. Bu DDL ClickHouse 24.8'de çalıştırıldı; sink'in yazdığı satırlar, dakikalık metrik view'ı ve `FINAL` ile tekilleştirme `broker-log-sink`'in testinde gerçek sunucuyla denenir. JSON yazılırken ISO 8601 zaman damgaları için `date_time_input_format = 'best_effort'` ayarı gerekir.

```sql
CREATE DATABASE IF NOT EXISTS broker_logs;

CREATE TABLE broker_logs.broker_log
(
    event_time       DateTime64(3, 'UTC'),
    request_id       UUID,
    trace_id         String,
    event_type       Enum8('REQUEST' = 1, 'RESPONSE' = 2),
    environment      LowCardinality(String),
    maintask         LowCardinality(String),
    subtask          LowCardinality(String),
    route_version    UInt32,
    response_profile LowCardinality(String),            -- profil yoksa boş
    response_profile_version UInt32,
    user_id          String,
    client_ip        String,
    user_agent       String,
    application      LowCardinality(String),
    uid              String,                            -- frontend'in gönderdiği
    http_method      LowCardinality(String),
    target_path      String,
    status_code      UInt16,                            -- yalnızca RESPONSE
    outcome          LowCardinality(String),            -- yalnızca RESPONSE
    duration_ms      UInt32,                            -- yalnızca RESPONSE
    body             String CODEC(ZSTD(3)),             -- maskelenmiş; yanıtta kırpılmış olabilir
    body_size        UInt32,
    body_truncated   Bool,
    error_message    String,
    broker_instance  LowCardinality(String),
    INDEX idx_request_id request_id TYPE bloom_filter GRANULARITY 4,
    INDEX idx_trace_id   trace_id   TYPE bloom_filter GRANULARITY 4,
    INDEX idx_user_id    user_id    TYPE bloom_filter GRANULARITY 4
)
ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (maintask, subtask, event_time, request_id, event_type)
TTL toDateTime(event_time) + INTERVAL 180 DAY;

-- Dakikalık metrikler: dashboard'lar ana tabloyu taramaz
CREATE TABLE broker_logs.broker_metrics_1m
(
    minute       DateTime('UTC'),
    environment  LowCardinality(String),
    maintask     LowCardinality(String),
    subtask      LowCardinality(String),
    outcome      LowCardinality(String),
    requests     AggregateFunction(count),
    duration_q   AggregateFunction(quantiles(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(minute)
ORDER BY (environment, maintask, subtask, outcome, minute)
TTL minute + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW broker_logs.broker_metrics_1m_mv
TO broker_logs.broker_metrics_1m
AS
SELECT
    toStartOfMinute(toDateTime(event_time, 'UTC'))   AS minute,
    environment,
    maintask,
    subtask,
    outcome,
    countState()                                     AS requests,
    quantilesState(0.5, 0.95, 0.99)(duration_ms)     AS duration_q
FROM broker_logs.broker_log
WHERE event_type = 'RESPONSE'
GROUP BY minute, environment, maintask, subtask, outcome;
```

Materialized view insert anında çalışır; Kafka'dan nadiren tekrar gelen bir olay metrikte iki kez sayılabilir. Dashboard için bu kabul edilebilir; kesin sayım ana tablodan `FINAL` ile alınır.

```sql
-- Son 1 saat, PROD: subtask başına istek sayısı ve p50/p95/p99 gecikme
SELECT
    maintask,
    subtask,
    countMerge(requests)                          AS requests,
    quantilesMerge(0.5, 0.95, 0.99)(duration_q)   AS p50_p95_p99_ms
FROM broker_logs.broker_metrics_1m
WHERE environment = 'PROD' AND minute >= now() - INTERVAL 1 HOUR
GROUP BY maintask, subtask
ORDER BY requests DESC;

-- Cevapsız istekler: 5 dakikadan eski, RESPONSE'u olmayan REQUEST'ler
SELECT
    request_id,
    any(maintask)                                 AS maintask,
    any(subtask)                                  AS subtask,
    minIf(event_time, event_type = 'REQUEST')     AS received_at
FROM broker_logs.broker_log
WHERE event_time >= now() - INTERVAL 1 HOUR
GROUP BY request_id
HAVING countIf(event_type = 'REQUEST') > 0
   AND countIf(event_type = 'RESPONSE') = 0
   AND received_at < now() - INTERVAL 5 MINUTE;
```

### Elastic: eski yapıyla uyumluluk

Elastic'teki dokümanlar alanları ve değerleriyle bugünkü gibi kalır, çünkü eski loglar da bu yapıda ve bu loglardan beslenen projeler var. Değişen yalnızca dokümanı kuran yer: bugün broker istek yolunda kuruyor, yeni düzende Elastic sink Kafka olaylarından kuruyor. Yeni bilgi yalnızca `broker` nesnesinin altına eklenir; böylece eski alanlarla ve frontend'in gönderdiği alan adlarıyla çakışmaz.

| Doküman | Ne zaman | İçerik |
| --- | --- | --- |
| Ana doküman | Her istek | `_id` = `request_id`. Frontend gövdesi olduğu gibi (`password` değeri `xxxx`), `log_request` alanları, `perm_list` ve yanıt gelince `data_response` |
| Parametre dokümanı | Login ve logout dışında, Map ya da Map listesi olan her üst seviye alan için | Zarf alanları, `log_request` alanları, `query_id` ve `<alan>: {parameter_name, parameter_value}`; parametre başına bir doküman |
| `data_response` | RESPONSE olayıyla | Frontend'e dönen gövde, `data` alanı boş string: `{"BACKEND_STATUS": 200, "status": 200, "data": ""}`. Binary yanıtta `info` mesajı; log yazılamadıysa bugünkü gibi yazılmaz |

`log_request`'in eklediği alanlar ve örnek dokümanlarda görülenler:

| Alan | Bugün | Yeni düzende |
| --- | --- | --- |
| `request_id` | Her `log_request` çağrısında yeni bir UUID; ana doküman ve her parametre dokümanı ayrı ID alır, `_id` de budur | Ana dokümanda isteğin ID'si; parametre dokümanında ondan türetilen ayrı bir UUID |
| `query_id` | Yalnızca parametre dokümanında; her istekte rastgele üretilir | Aynı; broker üretir, olayla taşınır |
| `request_date` | GMT, `yyyy-MM-dd'T'HH:mm:ss`, milisaniyesiz | Aynı biçim; REQUEST olayının zamanı |
| `client_ip`, `user_agent` | `X-Clientip` ve `X-User-Agent` header'ları olduğu gibi | Aynı |
| `token` | `X-Token` olduğu gibi | Aynı |
| `perm_list` | Kullanıcının tüm yetkileri; yalnızca ana dokümanda | Aynı |

- **İndeks:** `requests_yyyy-MM`. Ay, istek anına göre ve eski kodla aynı saat diliminde hesaplanır. request\_id (UUIDv7) zamanı taşıdığı için yanıt da ay dönümünde aynı indekse yazılır.
- **Yazma:** Bugün yanıt, aynı ID ile `index` çağrısı yapıp dokümanın tamamını yeniden yazıyor. Sink bunun yerine `update` ve `doc_as_upsert` kullanır; REQUEST ve RESPONSE hangi sırayla gelirse gelsin son doküman aynıdır, tekrar gelen olay hiçbir şeyi bozmaz.
- **Parametre değerleri:** `parameter_value` bugünkü gibi Java'nın `toString` biçimiyle yazılır (`{a=1}`, `[1, 2]`); değeri null olan parametrede bu alan yazılmaz. Parametre dokümanının `_id`'si ve `request_id`'si, ana `request_id`, alan adı ve sıradan türetilen ad tabanlı bir UUID'dir; tekrar gelen olay aynı dokümanı yazar.
- **Eşleme:** Elastic, eşleşen index şablonlarından yalnızca en yüksek öncelikliyi uygular. Bu yüzden yeni bir şablon açılmaz; aşağıdaki bileşen mevcut `requests_*` şablonunun `composed_of` listesine eklenir.

```json
PUT _component_template/broker-fields
{
  "template": {
    "mappings": {
      "properties": {
        "broker": {
          "properties": {
            "received_at":              { "type": "date" },
            "responded_at":             { "type": "date" },
            "trace_id":                 { "type": "keyword" },
            "environment":              { "type": "keyword" },
            "route_version":            { "type": "integer" },
            "response_profile":         { "type": "keyword" },
            "response_profile_version": { "type": "integer" },
            "user_id":                  { "type": "keyword" },
            "status_code":              { "type": "short" },
            "outcome":                  { "type": "keyword" },
            "duration_ms":              { "type": "integer" },
            "body_truncated":           { "type": "boolean" },
            "broker_instance":          { "type": "keyword" }
          }
        }
      }
    }
  }
}
```

Log yazılamadığında servisin yanıtı yerine `500` ve `{"result": false}` dönmesi bugünkü gibi korunur ve istek loguna da genişler (Loglama hattı). Bugünkü koddan bilinçli olarak taşınmayan davranışlar:

- Servisin yanıt gövdesi boşsa frontend'e 500 dönülüyor. Yeni düzende boş gövde tek başına hata sayılmaz, profile göre zarflanır; log yazılamazsa diğer yanıtlar gibi 500 döner.
- Map dalında dış döngü kullanılmadığı için N alanlı bir nesnede her parametre N kez loglanıyor; sink her parametreyi bir kez yazar.
- Liste dalında değeri null olan bir parametre `toString()`'de hata veriyor ve istek düşüyor; sink bu parametrede `parameter_value` yazmaz.
- `uid` gövdeden okunup `X-uid` header'ı olarak servise gidiyor; yeni düzende `X-uid` oturumdaki kullanıcıyla doldurulur.

Alan listesi `log_request` koduna ve iki örnek dokümana göre çıkarıldı: bir ana doküman ve bir parametre dokümanı. `alarm` indeksinin örneği yok; kullanılıyorsa o da aynı şekilde taşınır.

## Güvenlik ve yönetişim

Merkezi admin her ortama erişim verebildiği için sistemin en hassas yüzeyidir. Bu yüzden yetkiler ortam bazında ve dört göz ilkesiyle verilir.

| Rol | DEV | TEST | PROD |
| --- | --- | --- | --- |
| VIEWER | Okur | Okur | Okur |
| ROUTE\_EDITOR | Tanımlar, test eder, uygular | Taşıma talebi açar | Taşıma talebi açar |
| APPROVER | Okur | Okur | Talepleri onaylar ya da reddeder |

Roller şirketin SSO'su ya da yetkilendirme servisindeki gruplardan gelir.

- **Dört göz:** PROD'a her taşıma ve anonim erişimin açılması, talep edenden farklı bir onaylayıcı ister. Kural DB'de `ck_four_eyes` ile de korunur.
- **Audit:** Tanım değişiklikleri `route_definition_history`'de, taşımalar `promotion_request`'te durur. Uygulama kullanıcısının hiçbir tabloda silme yetkisi yoktur; history'yi bir trigger tablo sahibine karşı da korur.
- **SSRF:** Test ve yoklama istekleri yalnızca kayıtlı projelerin base URL'lerine gider. URL `UriComponentsBuilder` ile kurulur ve redirect izlenmez.
- **Header kısıtları:** Kimlik taşıyan header'ları yalnızca broker doldurur (Route tanımı bölümündeki liste).
- **Servislere erişim:** Servisler yalnızca broker'dan ve ortam Admin API'sinden gelen isteği kabul eder (network policy). Broker kullanıcı bağlamını imzalı bir header ile iletir.
- **Ağ:** Merkezi admin, CI/CD araçlarının durduğu yönetim bölgesinde çalışır. Ortamlara yalnızca Admin API'ler üzerinden mTLS ile bağlanır.
- **Log verisi:** Maskeleme Kafka'dan önce broker'da yapılır. X-Token eski loglardaki gibi düz yazıldığından Kafka topic'ine ve Kibana'ya erişim kısıtlı tutulur; ClickHouse token'ı saklamaz. ClickHouse'ta TTL, Elastic'te ILM saklama süresini sınırlar.

## Gözlemlenebilirlik ve alarmlar

Tek POST endpoint'i yüzünden kaybolan maintask/subtask bilgisi her trace'e, metriğe ve log olayına geri eklenir.

- **Tracing:** Broker, ortam Admin API'leri ve tüm servislerde OpenTelemetry Java agent'ı çalışır; trace'ler OTLP ile Elastic APM'e gider. Broker'daki istek span'ının adı `maintask/subtask`'tir, ikisi attribute olarak da eklenir.
- **Metrik:** Broker'da `broker.requests` adlı bir Micrometer timer'ı `maintask`, `subtask` ve `outcome` tag'lerini taşır. Route tablosunda olmayan subtask `UNKNOWN` olarak sayılır; kullanıcı ID'si metriğe değil, trace ve loga yazılır.
- **Mevcut sayaç:** Bugünkü `broker.gelen.istekler` sayacı aynı ad ve tag'lerle üretilmeye devam eder, mevcut dashboard'lar bozulmaz. Login ve logout bugünkü gibi sayılmaz; route tablosunda olmayan değerler "Bilinmeyen Proje" ve "Bilinmeyen Endpoint" olarak sayılır.
- **Korelasyon:** Uygulama loglarında trace\_id bulunur (agent MDC'ye koyar), log olaylarında trace\_id ve request\_id bulunur. Kibana'da logdan trace'e geçilebilir.

Eşikler öneridir; ilk haftaların verisiyle ayarlanır.

| Alarm | Koşul | Ne anlama gelir |
| --- | --- | --- |
| Hata oranı | Bir subtask'te TECHNICAL\_ERROR + TIMEOUT oranı 5 dakikada %5'i aşar | Servis ya da route sorunu |
| Gecikme | p99, önceki 7 günün aynı saatinin 2 katını aşar | Yavaşlayan servis |
| Spool | Spool'da en az bir olay var | Log yazılamadığı için 500 dönen istekler var; kayıtları Kafka'yı bekliyor |
| LOG\_UNAVAILABLE | Herhangi bir istek | Kullanıcılar log yazılamadığı için 500 alıyor; Kafka'ya bakılmalı |
| Log onay süresi | Kafka onayının p99'u, önceki 7 günün aynı saatinin 2 katını aşar | Kafka zorlanıyor; her istek bu kadar yavaşlıyor |
| Consumer lag | Bir sink 5 dakikadan fazla geride | Elastic ya da ClickHouse yazamıyor |
| DLQ | DLQ'ya mesaj düştü | Olay şeması ya da mapping sorunu |
| Cevapsız istek | Cevapsız REQUEST sayısı artıyor | Broker çökmesi ya da asılı kalan istek |
| Drift | Gece yoklamasında PATH\_NOT\_FOUND ya da WRONG\_METHOD | Route, servisteki endpoint'le uyuşmuyor |
| Yayılım | Bir instance 1 dakikadan uzun süre eski versiyonda | Pub/sub ya da DB erişim sorunu |
| UNKNOWN\_TASK | Ani artış | Eksik promote ya da frontend hatası |
| Sözleşme ihlali | Bir subtask'te INVALID\_REQUEST oranı ani artar | Frontend ile route tanımı uyuşmuyor; çoğu zaman yeni bir deploy sonrası |

## Yerel geliştirme

Backend geliştirici bugünkü gibi broker'ı kendi makinesinde çalıştırıp kendi projesini localhost'a yönlendirir. Fark, IP'nin koddaki statik tabloda değil, yerel bir override dosyasında değişmesidir.

Broker'a yalnızca `local` Spring profilinde açılan bir override katmanı eklenir. Yerel broker route'ları ve profilleri DEV'in runtime DB'sinden salt okunur bir kullanıcıyla okur, dosyayı da üstüne uygular:

```yaml
# application-local.yml
broker:
  route-source: db                         # DEV runtime DB, salt okunur kullanıcı
  local-overrides: file:./broker-local.json
  log-sink: console                        # Kafka'ya yazmaz, DEV loglarını kirletmez
```

```json
{
  "baseUrls": {
    "order-service": "http://localhost:8080"
  },
  "routes": [
    {
      "maintask": "order-service",
      "subtask": "cancel_order",
      "httpMethod": "POST",
      "pathTemplate": "/orders/{id}/cancel",
      "permissions": ["ORDER_WRITE"],
      "responseProfile": "order-service",
      "definition": {
        "request": {
          "fields": [ { "name": "orderId", "type": "integer", "required": true } ],
          "pathVariables": { "id": "${request.orderId}" },
          "body": { "mode": "NONE" }
        },
        "response": { "type": "JSON" }
      }
    }
  ]
}
```

- **`baseUrls`:** Eski "dev IP'sini localhost yap" adımının karşılığı. Listelenen projeye giden istekler geliştiricinin makinesine, diğerleri DEV'e gider. Dosya izlendiği için değişiklik restart gerektirmez.
- **`routes`:** DEV'de henüz olmayan bir subtask'i yerelde tanımlar ya da mevcut birini geçersiz kılar. Format merkezi tanımla aynıdır; çalışan JSON, merkezi admindeki "JSON'dan içe aktar" ile ekrana yüklenir ve kimse tanımı yeniden yazmaz.
- **Oturum ve yetki:** Login ve yetkiler DEV'in Redis'i ve yetkilendirme servisi üzerinden çalışır. Henüz kimseye atanmamış yeni bir yetki için, local profilde yalnızca override route'larında yetki kontrolü atlanabilir.
- **Güvenlik:** Override katmanı `@Profile("local")` ile tanımlandığı için DEV, TEST ve PROD'da hiç oluşmaz. Runtime DB'ye salt okunur bağlanıldığından yerel broker DEV'deki tanımları değiştiremez.

Broker'ı yerelde çalıştırmak istemeyenler için iki seçenek daha var:

- **DEV broker'ında istek bazında yönlendirme:** Geliştirici isteğe `X-Route-Target: order-service=http://10.x.x.x:8080` gibi bir header ekler, DEV broker yalnızca o isteği onun makinesine gönderir. DEV'den geliştirici makinesine ağ erişimi gerekir; VPN ve NAT genelde bunu engeller. SSRF'e karşı yalnızca DEV'de, yalnızca iç IP aralıklarına ve kimliği doğrulanmış geliştiricilere açılmalıdır.
- **Kubernetes'te Telepresence ya da mirrord:** DEV'deki bir servisin trafiğini geliştiricinin makinesine yönlendirir; broker'a dokunmadan aynı sonucu verir.

Override dosyası bugünkü alışkanlığın birebir karşılığı ve riski en düşük seçenek olduğu için başlangıç olarak önerilir.

## Geçiş planı

Geçiş altı adımda yapılır. Her adım tek başına değer üretir ve frontend'i etkilemez.

1. **Log hattı:** Kafka topic'i, broker producer'ı, maskeleme ve Elastic sink kurulur. Broker olayların onayını ilk günden bekler; "log yazılamazsa yanıt dönmez" kuralı geçişin hiçbir adımında gevşemez. Elastic sink eski doküman yapısını üretir; DEV'de aynı istekler için eski broker'ın ve sink'in yazdığı dokümanlar alan alan karşılaştırılır. Fark kalmayınca senkron Elastic yazımı kaldırılır; Kibana'daki mevcut kullanım ve loglardan beslenen projeler aynı alanlarla sürer. ClickHouse sink paralel eklenir ve Kafka'daki geçmişle doldurulur.
2. **Runtime DB ve broker cache:** Broker'daki statik subtask'ler tek seferlik bir script'le `runtime_route`'a aktarılır; projeye özel sınıflardaki yetki kontrolleri de route'ların yetki listelerine taşınır. Önce Elastic'teki son ayların maintask ve subtask değerleri gruplanır; artık çağrılmayan subtask'ler ve yalnızca harf farkıyla ayrışan adlar böylece görülür. Broker route'ları koddan değil tablodan okur; yeni bir subtask artık bir tablo kaydıdır. Projeye özel response sınıfları yanıt profillerine çevrilir; DEV'de aynı istekleri eski ve yeni broker'a gönderip yanıtları karşılaştıran bir test seti farkları yakalar.
3. **Merkezi admin, salt okunur:** DEV'de Admin API ve merkezi admin kurulur; projeler proje servisindeki ID'leriyle eşleştirilir ve yetki önekleri girilir. Mevcut route'lar ilk versiyon olarak history'ye ve `route_deployment`'a alınır. Yetkiler öneklerine göre ayrılır: projenin kendi yetkileri `permissions`'a, başka projelerinkiler `foreign_permissions`'a gider; hiçbir kayıtlı projeye uymayanlar raporlanır. Spec karşılaştırması görüntülenir.
4. **Tanımlama ve DEV:** Yeni subtask'ler ekrandan tanımlanır, test edilir ve DEV'e uygulanır. Mevcut subtask'lerin alan tanımları yapıştırılan ya da Elastic'ten çekilen örnek isteklerle tamamlanır.
5. **TEST ve PROD:** TEST ve PROD Admin API'leri kurulur; promote, onay ve yoklama akışı açılır.
6. **Sıkılaştırma:** Gece drift kontrolü ve alarmlar açılır; log onay süresi ve spool alarmlarının eşikleri canlı trafikle ayarlanır.

Path tabanlı bir sözleşme (`POST /api/{maintask}/{subtask}`) ileride hazır gateway'lerin önünü açar; bu dokümanın kapsamında değildir.

## Açık kararlar ve varsayımlar

- [ ] **İlişkisel DB:** DDL'ler PostgreSQL varsayımıyla yazıldı. Oracle ya da MSSQL kullanılıyorsa JSONB, kısmi unique index ve trigger karşılıkları değişir.
- [ ] **Kafka:** Kurumda hazır bir Kafka cluster'ı var mı? Yoksa işletim yükü (en az 3 broker, izleme) plana eklenmeli. Kafka artık her isteğin yolunda; erişilebilirliği broker'ınkiyle aynı seviyede olmalı.
- [ ] **Senkron Elastic yazımı (askıda):** Kafka onayı logun kalıcı yazıldığını garanti ediyor; senkron yazım buna yalnızca dokümanın Elastic'te anında görünmesini ekler. Karşılığında Elastic'in gecikmesi ve kesintisi yine istek yolunu etkiler. Gerekirse broker, sink'in ürettiği dokümanları istek yolunda kendisi yazar; olay şeması ve doküman yapısı değişmez.
- [ ] **Saklama süreleri:** ClickHouse TTL'i, Elastic ILM ve Kafka retention'ı KVKK'ya ve iç politikaya göre belirlenmeli.
- [ ] **Elastic ve ClickHouse'un rolü:** Uzun vadede Elastic yalnızca arama için mi kalacak, yoksa tek depo ClickHouse mu olacak?
- [ ] **Acil durum politikası:** PROD'da DISABLE ve ROLLBACK önceden onay mı istesin, yoksa sonradan gözden geçirme mi yeterli?
- [ ] **Çalışma ortamı:** Kubernetes mi, VM mi? Servis keşfi ve spool'un diski buna göre seçilir.
- [x] **ClickHouse DDL'i:** ClickHouse 24.8'de çalıştırıldı ve sink'in satırlarıyla doğrulandı (`broker-log-sink`, `RealTargetsTest`).
- [x] **Spring Boot sürümü:** Java 25 ve Spring Boot 4.1.1. ECS formatında loglama Spring Boot'un yapılandırılmış loglamasıyla (`logging.structured.format.console=ecs`) gelir; OTel Java agent'ı bundan bağımsız eklenir.
- [x] **`perm_list`:** Bugünkü gibi kullanıcının tüm yetkileri yazılır. Yalnızca erişimi sağlayan yetkilerle süzmek şimdilik yapılmayacak; ileride eklenebilir.
- [ ] **`alarm` indeksi:** Örneği yok; kullanılıyorsa bir örnek dokümanla aynı şekilde taşınacak.
- [ ] **Yetkilendirme servisi:** Yetki açmak için bir API'si olduğu ve her ortamda ayrı çalıştığı varsayıldı; promote ön kontrolü yetkiyi hedef ortamda arar.
