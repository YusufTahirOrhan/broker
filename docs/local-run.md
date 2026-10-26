# Yerel çalıştırma

Uygulamalar geliştiricinin makinesinde `local` profiliyle çalışır ve DEV'e bağlanır: DEV Oracle, DEV Redis ve DEV'deki
servisler. Docker gerekmez (intranette Docker yok). Kafka, Elastic ve ClickHouse da gerekmez: yerel broker logları
konsola yazar.

## Hazırlık

- JDK 25 ve Maven 3.9.
- DEV Oracle ve DEV Redis'e erişim (adresler, kullanıcılar ve şifreler DBA'dan ve DEV ortam ayarlarından).
- `local.env`: `cp local.env.example local.env` ve değerleri doldurun. Dosya git'e girmez; betikler onu okur.

DBA'dan istenecek şemalar (DEV Oracle'da):

| Ne için | Kullanıcı | Not |
| --- | --- | --- |
| Yerel merkezi admin | `yapi_admin_local` (şema sahibi) | DEV'in merkezi şeması değil; boşsa admin ilk açılışta tabloları kurar ve örnek veriyle doldurur |
| Yerel broker | DEV runtime şemasının salt okunur kullanıcısı (`yapi_runtime_reader`) | DEV'deki broker'larla aynı route'lar |
| Taşıma akışı (isteğe bağlı) | `yapi_runtime_local` (şema sahibi) | Yerel env-admin bu şemaya yazar; DEV'in runtime şemasına asla bağlanmaz |

Şema sahibi bir kullanıcı için gereken yetkiler:

```sql
CREATE USER yapi_admin_local IDENTIFIED BY "..." QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE TRIGGER, CREATE SEQUENCE, CREATE VIEW
   TO yapi_admin_local;
```

## Çalıştırma

Her uygulama tek komutla açılır; jar yoksa önce derlenir (testsiz). Windows'ta PowerShell betiği aynı işi yapar.

```bash
scripts/local-run.sh admin            # Windows: .\scripts\local-run.ps1 admin
scripts/local-run.sh runtime
scripts/local-run.sh env-admin
scripts/local-run.sh login
scripts/local-run.sh admin --build    # kod değiştiyse yeniden derler (PowerShell: -Build)
```

| Uygulama | Port | Neye bağlanır | DEV'e yazar mı |
| --- | --- | --- | --- |
| `admin` (merkezi admin API) | 8080 | DEV Oracle'daki yerel şema (`YAPI_ADMIN_DB_*`); projeler ve yetkiler bellekte; ortamların Admin API'si yerel env-admin (8081) | Hayır |
| `env-admin` (ortam Admin API) | 8081, actuator 9091 | Yerel runtime şeması (`YAPI_RUNTIME_DB_*`); Redis sinyali ayrı kanalda (`yapi:routes-changed:local`) | Hayır |
| `runtime` (broker) | 8083 | DEV runtime DB, salt okunur (`YAPI_DEV_RUNTIME_DB_*`); DEV Redis (oturumlar, route değişikliği sinyali); `yapi-local.json` | Hayır; durumunu `yapi:loaded-local:*`'a yazar, DEV'in cache durumu ekranında görünmez |
| `login` (giriş servisi) | 8082 | Sabit kullanıcılar ve yetkiler; DEV Redis | Hayır; oturumları `local:user:session:{uid}`'e, application listesini `yapi:applications:local`'a yazar, DEV'in `user:session:*` ve `yapi:applications` anahtarlarına dokunmaz |

## Frontend geliştiricisi: merkezi admin API

```bash
scripts/local-run.sh admin
```

- Kullanıcılar HTTP Basic ile: `viewer`, `editor`, `approver`, `lead`; şifre kullanıcı adıyla aynı. `lead` üç role de sahip.
- Swagger: http://localhost:8080/swagger-ui.html, OpenAPI: http://localhost:8080/v3/api-docs. İkisi yalnızca `local`
  ve `dev` profillerinde açık.
- CORS: `http://localhost:5173` (`YAPI_ADMIN_CORS_ORIGINS` ile değişir).
- Örnek veri: boş şemada bir kez yazılır (`local-seed.json`): `order-service`, `LogProject` ve `giris` projeleri,
  5 subtask, `log-project` profili. Veriyi baştan almak için şemadaki tabloları boşaltın; tohumlamayı kapatmak için
  `YAPI_ADMIN_LOCAL_SEED=false`.
- Taşıma (promotion) ekranları için `env-admin` de açık olmalı; açık değilse ortam işlemleri `ENV_UNREACHABLE` döner.

## Backend geliştiricisi: broker (`runtime`)

Yerel broker DEV'in route'larıyla çalışır; kendi projenizi makinenize `yapi-local.json` ile yönlendirirsiniz
(çalışma klasöründe, git'e girmez). Örnek: `yapi-runtime/yapi-local.example.json`. Dosya izlenir, değişiklik
restart gerektirmez.

```json
{
  "baseUrls": { "order-service": "http://localhost:9001" },
  "routes": []
}
```

- `baseUrls`: listelenen projeye giden istekler makinenize, diğerleri DEV'deki servislere gider.
- `routes`: DEV'de henüz olmayan bir subtask'i yerelde tanımlar; biçim merkezi tanımla aynıdır.
- Login: `X-Token: login` istekleri DEV runtime DB'deki `giris/login` route'una, yani DEV'deki giriş servisine gider;
  yerel broker oturumu DEV Redis'ten okur. Örnek dosyadaki `giris` route'ları ve `baseUrls`'teki `giris` yalnızca
  giriş servisini yerelde geliştirirken gerekir (`scripts/local-run.sh login`, broker'da
  `YAPI_SESSION_KEY_PREFIX=local:`; aşağıda); yoksa silin.
- `application`: yerel broker sabit listeyle çalışır (`YAPI_LOCAL_APPLICATIONS`, varsayılan `web`). DEV'de giriş
  servisi `yapi:applications`'ı yazıyorsa `YAPI_APPLICATIONS_MODE=redis` ile DEV'in listesi kullanılır.

Örnek istekler:

```bash
curl -s localhost:8083/yapi -H 'Content-Type: application/json' -H 'X-Token: login' \
     -H 'X-Clientip: 10.1.1.1' -H 'X-User-Agent: curl' \
     -d '{"maintask": "giris", "subtask": "login", "uid": "dev.ali", "password": "...", "application": "web"}'

curl -s localhost:8083/yapi -H 'Content-Type: application/json' -H 'X-Token: <login yanıtındaki X-Token>' \
     -H 'X-Clientip: 10.1.1.1' -H 'X-User-Agent: curl' \
     -d '{"maintask": "order-service", "subtask": "get_order", "siparisNo": 1, "uid": "dev.ali", "application": "web"}'
```

Oturum, login'deki `X-Clientip` ve `X-User-Agent` ile aynı header'ları taşıyan isteklerde geçerlidir.

## Giriş servisini yerelde geliştirmek

`scripts/local-run.sh login`: LDAP yerine sabit kullanıcılar (`dev.ali`, `dev.ayse`; şifre kullanıcı adı), Koordinatör
yerine sabit yetkiler. DEV'in LDAP'ı ya da Koordinatör'ü denenecekse `YAPI_LOGIN_LDAP_MODE=bind`, `YAPI_LOGIN_LDAP_URLS`,
`YAPI_LOGIN_LDAP_PRINCIPAL_PATTERN`; `YAPI_KOORDINATOR_MODE=http`, `YAPI_KOORDINATOR_URL`.

Yerel giriş servisi DEV Redis'e bağlanır ama DEV'in ortak anahtarlarına yazmaz: oturumlar `local:user:session:{uid}`,
application listesi `yapi:applications:local`. Bind modunda da gerçek kullanıcının DEV oturumu ezilmez; yerelde
açılan oturum DEV'deki broker'larda geçmez.

Broker'ın yerel giriş servisini kullanması için:

- `yapi-local.json`'a `giris` route'ları ve `"giris": "http://localhost:8082"` eklenir;
- broker `YAPI_SESSION_KEY_PREFIX=local:` ile açılır (`local.env`'de ya da komut satırında); oturumları
  `local:user:session:{uid}`'den okur. Değişken yokken DEV'deki giriş servisinin yazdığı `user:session:{uid}` okunur.

## Taşıma akışını yerelde denemek

`admin` + `env-admin` (+ `runtime`): admin'den DEV'e taşınan route yerel runtime şemasına yazılır. Yerel broker bu
route'ları görsün diye `YAPI_DEV_RUNTIME_DB_*` da yerel runtime şemasına çevrilir (okuyucu kullanıcı şema
sahibi olabilir; broker değişikliği 2 sn'lik kontrolle görür). Projenin DEV base URL'i önce admin'deki ortam
ayarından (`PUT /projects/{id}/environments/DEV`) yerel env-admin'e yazılmalıdır.

## Testler

Veritabanı, Redis, Elastic ve ClickHouse testleri Testcontainers ile Docker'da çalışır; CI'da hepsi koşar. Docker'ın
olmadığı makinede birim testleri için:

```bash
mvn -B verify -Pno-docker
```
