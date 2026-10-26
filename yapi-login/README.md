# yapi-login

Giriş servisi: eski broker'ın login ve logout'unu birebir aynı gövdelerle yapar. Kullanıcıyı LDAP'ta bind ile
doğrular, yetkilerini Koordinatör'den alır, oturum kaydını Redis'e yazar (`user:session:{uid}`, 1 gün). Yalnızca
broker çağırır; merkezi admin'de `giris` projesi olarak kaydedilir, `login` ve `logout` onun anonim route'larıdır.
Broker `X-Token: login` ve `X-Token: logout` isteklerini gövdedeki maintask'a bakmadan bu route'lara gönderir
(`yapi.session.routes`).

Çalıştırma: `java -jar yapi-login-<sürüm>-exec.jar`; yerelde `--spring.profiles.active=local` (bkz.
`docs/local-run.md`).

## Uçlar

| Uç | Gövde ve header'lar | Yanıt (her zaman HTTP 200) |
| --- | --- | --- |
| `POST /login` | `{"uid", "password"}`, `X-Clientip`, `X-User-Agent` | Başarılı: `{"response": true, "status": 200, "LDAP": true, "login": true, "X-Token": "<uuid>"}`. LDAP ya da yetki çağrısı başarısız: `{"response": false, "status": 401, "Ldap": <ldap>, "login": false}`. Kayıt Redis'e yazılamadı: `{"status": 401, "Ldap": false, "login": false, "X-Token": "404"}` |
| `POST /logout` | `{"uid"}`, `X-Clientip`, `X-User-Agent` | Kayıt var ve istemci aynı: `{"response": true, "status": 200, "message": "logged out"}`; değilse `{"response": false, "status": 301, "message": "something went wrong with logging out."}` |
| `POST /internal/v1/sessions/{uid}/logout` | `X-Koordinator-Secret` | Koordinatör'ün zorla çıkışı, broker'dan geçmez: 204 kapatıldı, 404 oturum yok, 403 gizli anahtar yanlış ya da ayarlı değil |
| `GET /users/me/settings/{name}` | `X-User-Context` (imzalı kullanıcı) | Kayıt varsa saklanan JSON metni olduğu gibi, yoksa `null` (200) |
| `PUT /users/me/settings/{name}` | JSON gövde, `X-User-Context` | Gövdedeki JSON süresiz ve sıkışık metin olarak yazılır (eski `RedisTemplate` + `JacksonJsonRedisSerializer`'ın yazdığıyla aynı); yanıt yazılan JSON (200) |

- DEV'de (`dev` profili) giriş eski DEV sınıfı gibi magic key iledir: boş olmayan her `uid`, şifre `YAPI_LOGIN_MAGIC_KEY`'e (sabit zamanlı karşılaştırma) eşitse girer; herkes herkesin hesabına girebilir. Her deneme `info` loglanır (uid ve sonuç; şifre yok). Yetkiler yine Koordinatör'den gelir.
- Boş ya da yalnızca boşluktan oluşan `uid` veya şifre LDAP'a gitmeden başarısızdır (anonim bind'e düşmez, Active
  Directory'de hatalı deneme sayılmaz). LDAP başarısızsa Koordinatör'e sorulmaz.
- Yetkiler Koordinatör'ün `GET /api/koordinator/personel-yetki/aktif-yetkiler/{uid}` (`uid` = sicil) cevabından:
  map'teki bütün listelerin `yetkiIsim`'leri, sırası korunup tekrarları atılarak; `askidaMi` true (ya da 1) olan yetki
  aktif değildir ve listeye girmez. Boş map yetkisiz giriştir. 200 dışı ya da başka biçimdeki cevap (map değil, liste
  değil, isimsiz öğe) Koordinatör hatasıdır: giriş reddedilir, uyarı loglanır.
- Logout kaydı yalnızca onu açan istemci kapatabilir (`login.logout.require-client-match`, varsayılan true).
- `/actuator/` ve `/internal/` ile başlayanlar dışındaki her uç (yeni uçlar dahil) yalnızca broker'ın imzaladığı, en
  fazla 60 sn önce verilmiş `X-User-Context` ile kabul edilir; değilse 403. Yol, container'ın çözdüğü ve normalize
  ettiği haliyle karşılaştırılır (`/login;x=1`, `/%6Cogin` de `/login` sayılır). Anahtar `login.user-context-secret`
  (broker'ın `yapi.forward.user-context-secret`'ı); boşken kontrol kapalıdır ve bu yalnızca `local` profilinde
  olabilir, başka profilde servis başlamaz.
- Şifre hiçbir yerde loglanmaz.
- 5 dakikada bir Koordinatör'ün `GET /api/koordinator/projeler-ve-yetkileri` listesinde `is_yapi` true olan
  projelerin `projeAdi`'leri broker'ın application listesine (`yapi:applications`, süresiz JSON dizisi) yazılır; liste
  boşsa ya da alınamazsa eski değer kalır (`login.applications.sync.failed` sayacı).

## Kullanıcı ayarları

Eski broker dashboard yerleşimini ve hızlı bağlantıları Redis'te tutuyordu; aynı anahtarlar giriş servisinde:
`layout` → `user:layout:{uid}`, `quick_link` → `user:quick_link:{uid}` (`login.user-settings.keys`; listede olmayan ad
404). Kullanıcı, broker'ın imzaladığı `X-User-Context`'teki uid'dir; gövdedeki `uid` ya da `X-uid` dikkate alınmaz.
İmzada kullanıcı yoksa (anonim route) 403. Gövde JSON değilse 400, Redis'e ulaşılamazsa 503; hata gövdeleri
`{"response": false, "status": <kod>, "message": "..."}`. `local` profilinde imza yoktur, kullanıcı broker'ın `X-uid`'i;
anahtarlar `local:` önekli.

Route'ları kullanıcı eski subtask adlarıyla, oturumlu (yetkili) tanımlar ve yanıt biçimini profille eskisine benzetir.
Örnek, `giris/save_layout`, `PUT /users/me/settings/layout`:

```json
{
  "request": {
    "fields": [{ "name": "layout", "type": "object", "required": true }],
    "body": { "mode": "TEMPLATE", "contentType": "application/json", "template": "${request.layout}" }
  },
  "response": { "type": "JSON" }
}
```

Okuma (`giris/get_layout`) aynı yolun `GET`'i, gövdesiz. `harici_uygulamalar` Koordinatör'ün
`GET /api/koordinator/projeler-external` ucuna giden sıradan bir route'tur; `refresh_permissions`'ın karşılığı
`POST /internal/v1/sessions/{uid}/logout`'tur (Koordinatör ekibi doğrudan çağırır).

## Ayarlar

| Ayar | Varsayılan | Not |
| --- | --- | --- |
| `login.ldap.mode` | `bind` (`dev` profilinde `magic-key`) | `YAPI_LOGIN_LDAP_MODE`. `bind`: PREPROD ve PROD. `magic-key` yalnızca `dev` profilinde, `static` yalnızca `local` profilinde (`login.ldap.users`); başka profilde servis başlamaz |
| `login.ldap.magic-key` | — | `YAPI_LOGIN_MAGIC_KEY`; `magic-key` modunda zorunlu, boşsa servis başlamaz |
| `login.ldap.urls` | — | `YAPI_LOGIN_LDAP_URLS`, virgülle birden çok adres |
| `login.ldap.principal-pattern` | `{uid}@test.com.tr` | Active Directory UPN ya da DN, ör. `uid={uid},ou=people,dc=test` |
| `login.ldap.connect-timeout`, `read-timeout` | 3 sn, 5 sn | |
| `login.session.key-pattern`, `ttl` | `user:session:{uid}`, 86400 sn | Alan adları da ayarlanabilir; broker'ınkilerle aynı olmalı. `local` profilinde `local:user:session:{uid}` (DEV'in oturumlarına yazmaz) |
| `login.applications.key`, `refresh-interval` | `yapi:applications`, 5 dk | `local` profilinde `yapi:applications:local` |
| `login.user-context-secret` | — | `YAPI_USER_CONTEXT_SECRET`; `local` dışında zorunlu |
| `login.koordinator-secret` | — | `YAPI_LOGIN_KOORDINATOR_SECRET` |
| `login.user-settings.keys` | `layout`, `quick_link` | Ad → Redis anahtarı (`{uid}`); `local` profilinde `local:` önekli |
| `koordinator.url`, `auth-header`, `auth-value` | — | Bu ortamın Koordinatör'ü; kimlik header'ı şimdilik gerekmiyor (`auth-value` boşken gönderilmez). Admin ortam başına `koordinator.urls.<ENV>` kullanır |
| `koordinator.permissions-path`, `permission-field`, `suspended-field` | `/api/koordinator/personel-yetki/aktif-yetkiler/{uid}`, `yetkiIsim`, `askidaMi` | |
| `koordinator.projects-path`, `application-field`, `yapi-field` | `/api/koordinator/projeler-ve-yetkileri`, `projeAdi`, `is_yapi` | |

## Route tanımları

Merkezi admin'de `giris` projesinin iki anonim route'u, yanıt profili yok. Aynı tanımlar yerel broker için
`yapi-runtime/yapi-local.example.json`'da.

`giris/login`, `POST /login`:

```json
{
  "request": {
    "fields": [{ "name": "password", "type": "string", "required": true }],
    "headers": { "X-Clientip": "${header.X-Clientip}", "X-User-Agent": "${header.X-User-Agent}" },
    "body": {
      "mode": "TEMPLATE",
      "contentType": "application/json",
      "template": { "uid": "${request.uid}", "password": "${request.password}" }
    }
  },
  "response": { "type": "JSON" }
}
```

`giris/logout`, `POST /logout`:

```json
{
  "request": {
    "headers": { "X-Clientip": "${header.X-Clientip}", "X-User-Agent": "${header.X-User-Agent}" },
    "body": {
      "mode": "TEMPLATE",
      "contentType": "application/json",
      "template": { "uid": "${request.uid}" }
    }
  },
  "response": { "type": "JSON" }
}
```

`${request.uid}` zarf alanıdır, `fields`'ta tanımlanmadan okunur. `X-User-Agent`, `X-User-*` yasağının tek
istisnasıdır.
