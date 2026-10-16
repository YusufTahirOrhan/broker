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
| Yerel merkezi admin | `broker_admin_local` (şema sahibi) | DEV'in merkezi şeması değil; boşsa admin ilk açılışta tabloları kurar ve örnek veriyle doldurur |
| Yerel broker | DEV runtime şemasının salt okunur kullanıcısı (`broker_runtime_reader`) | DEV'deki broker'larla aynı route'lar |
| Taşıma akışı (isteğe bağlı) | `broker_runtime_local` (şema sahibi) | Yerel env-admin bu şemaya yazar; DEV'in runtime şemasına asla bağlanmaz |

Şema sahibi bir kullanıcı için gereken yetkiler:

```sql
CREATE USER broker_admin_local IDENTIFIED BY "..." QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE TRIGGER, CREATE SEQUENCE, CREATE VIEW
   TO broker_admin_local;
```

## Çalıştırma

Her uygulama tek komutla açılır; jar yoksa önce derlenir (testsiz). Windows'ta PowerShell betiği aynı işi yapar.

```bash
scripts/local-run.sh admin            # Windows: .\scripts\local-run.ps1 admin
scripts/local-run.sh broker
scripts/local-run.sh env-admin
scripts/local-run.sh login
scripts/local-run.sh admin --build    # kod değiştiyse yeniden derler (PowerShell: -Build)
```

| Uygulama | Port | Neye bağlanır | DEV'e yazar mı |
| --- | --- | --- | --- |
| `admin` (merkezi admin API) | 8080 | DEV Oracle'daki yerel şema (`BROKER_ADMIN_DB_*`); projeler ve yetkiler bellekte; ortamların Admin API'si yerel env-admin (8081) | Hayır |
| `env-admin` (ortam Admin API) | 8081, actuator 9091 | Yerel runtime şeması (`BROKER_RUNTIME_DB_*`); Redis sinyali ayrı kanalda (`broker:routes-changed:local`) | Hayır |
| `broker` | 8083 | DEV runtime DB, salt okunur (`BROKER_DEV_RUNTIME_DB_*`); DEV Redis (oturumlar, route değişikliği sinyali); `broker-local.json` | Hayır; durumunu `broker:loaded-local:*`'a yazar, DEV'in cache durumu ekranında görünmez |
| `login` (giriş servisi) | 8082 | Sabit kullanıcılar ve yetkiler; oturumu DEV Redis'e yazar | Evet: `user:session:{uid}`, DEV'deki giriş servisinin yazdığıyla aynı kayıt (aynı kullanıcının DEV oturumu ezilir) |

## Frontend geliştiricisi: merkezi admin API

```bash
scripts/local-run.sh admin
```

- Kullanıcılar HTTP Basic ile: `viewer`, `editor`, `approver`, `lead`; şifre kullanıcı adıyla aynı. `lead` üç role de sahip.
- Swagger: http://localhost:8080/swagger-ui.html, OpenAPI: http://localhost:8080/v3/api-docs. İkisi yalnızca `local`
  ve `dev` profillerinde açık.
- CORS: `http://localhost:5173` (`BROKER_ADMIN_CORS_ORIGINS` ile değişir).
- Örnek veri: boş şemada bir kez yazılır (`local-seed.json`): `order-service`, `LogProject` ve `giris` projeleri,
  5 subtask, `log-project` profili. Veriyi baştan almak için şemadaki tabloları boşaltın; tohumlamayı kapatmak için
  `BROKER_ADMIN_LOCAL_SEED=false`.
- Taşıma (promotion) ekranları için `env-admin` de açık olmalı; açık değilse ortam işlemleri `ENV_UNREACHABLE` döner.

## Backend geliştiricisi: broker

Yerel broker DEV'in route'larıyla çalışır; kendi projenizi makinenize `broker-local.json` ile yönlendirirsiniz
(çalışma klasöründe, git'e girmez). Örnek: `broker-runtime/broker-local.example.json`. Dosya izlenir, değişiklik
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
  giriş servisini yerelde geliştirirken gerekir (`scripts/local-run.sh login`); yoksa silin.
- `application`: yerel broker sabit listeyle çalışır (`BROKER_LOCAL_APPLICATIONS`, varsayılan `web`). DEV'de giriş
  servisi `broker:applications`'ı yazıyorsa `BROKER_APPLICATIONS_MODE=redis` ile DEV'in listesi kullanılır.

Örnek istekler:

```bash
curl -s localhost:8083/broker -H 'Content-Type: application/json' -H 'X-Token: login' \
     -H 'X-Clientip: 10.1.1.1' -H 'X-User-Agent: curl' \
     -d '{"maintask": "giris", "subtask": "login", "uid": "dev.ali", "password": "...", "application": "web"}'

curl -s localhost:8083/broker -H 'Content-Type: application/json' -H 'X-Token: <login yanıtındaki X-Token>' \
     -H 'X-Clientip: 10.1.1.1' -H 'X-User-Agent: curl' \
     -d '{"maintask": "order-service", "subtask": "get_order", "siparisNo": 1, "uid": "dev.ali", "application": "web"}'
```

Oturum, login'deki `X-Clientip` ve `X-User-Agent` ile aynı header'ları taşıyan isteklerde geçerlidir.

## Giriş servisini yerelde geliştirmek

`scripts/local-run.sh login`: LDAP yerine sabit kullanıcılar (`dev.ali`, `dev.ayse`; şifre kullanıcı adı), Koordinatör
yerine sabit yetkiler. DEV'in LDAP'ı ya da Koordinatör'ü denenecekse `LOGIN_LDAP_MODE=bind`, `LOGIN_LDAP_URLS`,
`LOGIN_LDAP_PRINCIPALPATTERN`; `KOORDINATOR_MODE=http`, `KOORDINATOR_URL`. Broker'ın yerel giriş servisine gitmesi için
`broker-local.json`'a `giris` route'ları ve `"giris": "http://localhost:8082"` eklenir.

## Taşıma akışını yerelde denemek

`admin` + `env-admin` (+ `broker`): admin'den DEV'e taşınan route yerel runtime şemasına yazılır. Yerel broker bu
route'ları görsün diye `BROKER_DEV_RUNTIME_DB_*` da yerel runtime şemasına çevrilir (okuyucu kullanıcı şema
sahibi olabilir; broker değişikliği 2 sn'lik kontrolle görür). Projenin DEV base URL'i önce admin'deki ortam
ayarından (`PUT /projects/{id}/environments/DEV`) yerel env-admin'e yazılmalıdır.

## Testler

Veritabanı, Redis, Elastic ve ClickHouse testleri Testcontainers ile Docker'da çalışır; CI'da hepsi koşar. Docker'ın
olmadığı makinede birim testleri için:

```bash
mvn -B verify -Pno-docker
```
