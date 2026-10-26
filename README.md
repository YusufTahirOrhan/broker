# yapi-platform

Yapi: frontend'lerin tek endpoint'e gönderdiği `maintask`/`subtask` isteklerini, DevOps'un merkezi ekrandan yönettiği versiyonlu tanımlarla servislere yönlendiren broker ve yönetim bileşenleri.

| Modül | Durum | Görev |
| --- | --- | --- |
| `yapi-core` | Yazıldı | Route tanımı, şablon motoru, istek üretimi, yanıt profilleri, log olayı, eski Elastic eşlemesi |
| `yapi-runtime` | Yazıldı | `POST /yapi`: route çözümleme, yetki, Kafka onay kapısı, forward, zarflama |
| `yapi-env-admin` | Yazıldı | Ortam Admin API'si: runtime DB'ye uygulama, yoklama, spec, test |
| `yapi-admin` | Yazıldı | Merkezi admin API'si (`docs/frontend-guide.md`) |
| `yapi-log-sink` | Yazıldı | Kafka'dan Elastic (eski yapı) ve ClickHouse'a |
| `yapi-login` | Yazıldı | Giriş servisi: LDAP doğrulaması, token, Redis'teki oturum kaydı, logout (`yapi-login/README.md`) |

Derleme: Java 25 ve `mvn -B verify` (veritabanı testleri Docker ister; Docker'sız makinede `mvn -B verify -Pno-docker`). Yerelde çalıştırma: `docs/local-run.md`. Ayrıntılar `HANDOFF.md`, `docs/design.md` ve `docs/frontend-guide.md`'de.
