# broker-platform

Kurum içi istek broker'ının yeni backend'i: subtask'leri koddan değil versiyonlu tanımlardan çalıştıran broker, ortam Admin API'si, merkezi admin ve log sink'leri. İşe başlamadan önce `HANDOFF.md`'yi oku; durum, kararlar ve sıradaki adımlar orada.

## Belgeler

- `docs/design.md`: tasarım dokümanı (mimari, istek akışı, şemalar, log hattı, eski Elastic uyumu).
- `docs/frontend-guide.md`: merkezi admin API sözleşmesi. Yollar, gövdeler ve hata kodları buna birebir uyar.

## Derleme

- Java 25, Spring Boot 4.1.1, Maven: `mvn -B verify`.
- Bulut oturumunda JDK 25'i `scripts/cloud-setup.sh` kurar (SessionStart hook'u otomatik çalıştırır). `java -version` 25 değilse betiği elle çalıştır.
- Testcontainers için Docker gerekir; `docker info` hata verirse `dockerd > /tmp/dockerd.log 2>&1 &` ile başlat.

## Kurallar

- Push'tan önce `mvn -B verify` yeşil olmalı. Yeni modül, yazıldığında kök `pom.xml`'e eklenir.
- Harf duyarsız karşılaştırma her zaman `Names.key` / `Locale.ROOT` ile; varsayılan locale'li `toLowerCase()` kullanılmaz.
- Jackson ağaç API'si yalnızca `Json` yardımcıları üzerinden kullanılır.
- Eski Elastic dokümanlarının alanları ve değerleri değişmez; yeni bilgi yalnızca `broker` nesnesine eklenir.
- Kullanıcıya dönen mesajlar Türkçe; kod, isimler ve yorumlar İngilizce.
- Admin API sözleşmesi değişirse `docs/frontend-guide.md` ve sonundaki "Değişiklik kaydı" aynı commit'te güncellenir; commit mesajına "Frontend rehberi değişti" yazılır.
