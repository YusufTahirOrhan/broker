# broker-platform

Frontend'lerin tek endpoint'e gönderdiği `maintask`/`subtask` isteklerini, DevOps'un merkezi ekrandan yönettiği versiyonlu tanımlarla servislere yönlendiren broker ve yönetim bileşenleri.

| Modül | Durum | Görev |
| --- | --- | --- |
| `broker-core` | Yazıldı | Route tanımı, şablon motoru, istek üretimi, yanıt profilleri, log olayı, eski Elastic eşlemesi |
| `broker-runtime` | Planlandı | `POST /broker`: route çözümleme, yetki, Kafka onay kapısı, forward, zarflama |
| `broker-env-admin` | Yazıldı | Ortam Admin API'si: runtime DB'ye uygulama, yoklama, spec, test |
| `broker-admin` | Planlandı | Merkezi admin API'si (`docs/frontend-guide.md`) |
| `broker-log-sink` | Planlandı | Kafka'dan Elastic (eski yapı) ve ClickHouse'a |

Derleme: Java 25 ve `mvn -B verify`. Ayrıntılar `HANDOFF.md`, `docs/design.md` ve `docs/frontend-guide.md`'de.
