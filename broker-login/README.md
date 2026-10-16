# broker-login

Giriş servisi: eski broker'ın login ve logout'unu birebir aynı gövdelerle yapar. Kullanıcıyı LDAP'ta bind ile
doğrular, yetkilerini Koordinatör'den alır, oturum kaydını Redis'e yazar (`user:session:{uid}`, 1 gün). Yalnızca
broker çağırır; merkezi admin'de `giris` projesi olarak kaydedilir, `login` ve `logout` onun anonim route'larıdır.
Broker `X-Token: login` ve `X-Token: logout` isteklerini gövdedeki maintask'a bakmadan bu route'lara gönderir
(`broker.session.routes`).

Çalıştırma: `java -jar broker-login-<sürüm>-exec.jar`; yerelde `--spring.profiles.active=local` (bkz.
`docs/local-run.md`).

## Uçlar

| Uç | Gövde ve header'lar | Yanıt (her zaman HTTP 200) |
| --- | --- | --- |
| `POST /login` | `{"uid", "password"}`, `X-Clientip`, `X-User-Agent` | Başarılı: `{"response": true, "status": 200, "LDAP": true, "login": true, "X-Token": "<uuid>"}`. LDAP ya da yetki çağrısı başarısız: `{"response": false, "status": 401, "Ldap": <ldap>, "login": false}`. Kayıt Redis'e yazılamadı: `{"status": 401, "Ldap": false, "login": false, "X-Token": "404"}` |
| `POST /logout` | `{"uid"}`, `X-Clientip`, `X-User-Agent` | Kayıt var ve istemci aynı: `{"response": true, "status": 200, "message": "logged out"}`; değilse `{"response": false, "status": 301, "message": "something went wrong with logging out."}` |
| `POST /internal/v1/sessions/{uid}/logout` | `X-Koordinator-Secret` | Koordinatör'ün zorla çıkışı, broker'dan geçmez: 204 kapatıldı, 404 oturum yok, 403 gizli anahtar yanlış ya da ayarlı değil |

- Boş `uid` ya da şifre her zaman başarısızdır (anonim bind'e düşmez). LDAP başarısızsa Koordinatör'e sorulmaz.
- Logout kaydı yalnızca onu açan istemci kapatabilir (`login.logout.require-client-match`, varsayılan true).
- `/actuator/` ve `/internal/` ile başlayanlar dışındaki her uç (yeni uçlar dahil) yalnızca broker'ın imzaladığı, en
  fazla 60 sn önce verilmiş `X-User-Context` ile kabul edilir; değilse 403. Yol, container'ın çözdüğü ve normalize
  ettiği haliyle karşılaştırılır (`/login;x=1`, `/%6Cogin` de `/login` sayılır). Anahtar `login.user-context-secret`
  (broker'ın `broker.forward.user-context-secret`'ı); boşken kontrol kapalıdır ve bu yalnızca `local` profilinde
  olabilir, başka profilde servis başlamaz.
- Şifre hiçbir yerde loglanmaz.
- 5 dakikada bir Koordinatör'ün proje listesindeki `code`'lar broker'ın application listesine
  (`broker:applications`, süresiz JSON dizisi) yazılır; liste boşsa ya da alınamazsa eski değer kalır
  (`login.applications.sync.failed` sayacı).

## Ayarlar

| Ayar | Varsayılan | Not |
| --- | --- | --- |
| `login.ldap.mode` | `bind` | `static` yalnızca `local` profilinde (`login.ldap.users`); başka profilde servis başlamaz |
| `login.ldap.urls` | — | `LOGIN_LDAP_URLS`, virgülle birden çok adres |
| `login.ldap.principal-pattern` | `{uid}@test.com.tr` | Active Directory UPN ya da DN, ör. `uid={uid},ou=people,dc=test` |
| `login.ldap.connect-timeout`, `read-timeout` | 3 sn, 5 sn | |
| `login.session.key-pattern`, `ttl` | `user:session:{uid}`, 86400 sn | Alan adları da ayarlanabilir; broker'ınkilerle aynı olmalı. `local` profilinde `local:user:session:{uid}` (DEV'in oturumlarına yazmaz) |
| `login.applications.key`, `refresh-interval` | `broker:applications`, 5 dk | `local` profilinde `broker:applications:local` |
| `login.user-context-secret` | — | `BROKER_USER_CONTEXT_SECRET`; `local` dışında zorunlu |
| `login.koordinator-secret` | — | `LOGIN_KOORDINATOR_SECRET` |
| `koordinator.url`, `auth-header`, `auth-value` | — | Tek base URL ve sabit kimlik header'ı; admin de aynılarını kullanır |
| `koordinator.permissions-path`, `permissions-field` | `/users/{uid}/permissions`, `yetkiler` | Gerçek API gelince değişir |
| `koordinator.projects-path`, `project-code-field` | `/projects`, `code` | |

## Route tanımları

Merkezi admin'de `giris` projesinin iki anonim route'u, yanıt profili yok. Aynı tanımlar yerel broker için
`broker-runtime/broker-local.example.json`'da.

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
