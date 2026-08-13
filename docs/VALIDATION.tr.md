# Doğrulama Kanıtı

[English](VALIDATION.md) | [Türkçe](VALIDATION.tr.md)

## Güncel Karar

Embedded Rust-Java telemetri yolu; doğruluk, Glowroot protokolü, collector kapalıyken fail-open,
kaynak kodla uygulanan `1 MiB` agent-owned bütçe ve bağımsız proseslerde ölçülen `3 MiB` resident
memory sınırını geçti. Yeni bir işletim sistemi thread'i oluşturmadı.

Burada iki ayrı sözleşme vardır:

- `1 MiB`, ajana atfedilen state ve kod sayfaları için deterministik üst sınırdır.
- `3 MiB`, eşleştirilmiş process `VmRSS`, smaps RSS ve cgroup current farkları için konservatif
  maksimumdur. Bağımsız prosesler arasındaki OpenJ9, allocator ve resident sayfa oynaklığını içerir.

İsteğe bağlı `-javaagent` kolaylık JAR'ı, strict resident memory sertifikasının parçası değildir.
Sert bütçeli production yolunda native property veya ortam değişkeni kullanın.

| Gate | Sonuç | Güncel kanıt |
| --- | --- | --- |
| Runtime JAR yüzeyi | PASS | `5,71 KiB`, tek bootstrap class, runtime dependency yok |
| Upstream protokol | PASS | Upstream parser init, aggregate, gauge, trace ve HdrHistogram mesajlarını kabul etti |
| İsteğe bağlı Java bootstrap | PASS | Agent argümanları transformer kurulmadan çevrildi |
| Collector kapalı | PASS | HTTP çalışmaya devam etti ve sınırsız telemetri kuyruğu oluşmadı |
| Kaynak bellek bütçesi | PASS | Hesaplanan değer `358.531` byte; sert state/rezerv sınırı `384 KiB` |
| Embedded-native atfedilen üst sınır | PASS | Ölçülen native özellik sayfaları dahil `0,694 MiB` |
| Ek agent thread'i | PASS | `0`; framework'ün Tokio runtime'ı kullanılır |
| Maksimum process `VmRSS` farkı | PASS | `+1,742 MiB`; ürün sınırı `+3,000 MiB` |
| Maksimum smaps RSS farkı | PASS | `+1,817 MiB`; ürün sınırı `+3,000 MiB` |
| Maksimum cgroup-current farkı | PASS | `+1,754 MiB`; ürün sınırı `+3,000 MiB` |
| Hedefli c256 small-direct performance | PASS | RPS `-%0,17`, p99 `+%6,28`, `503` farkı `0` |
| Tam c64/c256 endpoint matrisi | AÇIK | Son workstation denemesi host gürültüsü preflight kontrolünde reddedildi |

Footprint kanıtı
[`evidence/0.1.0-rc1/footprint-report.md`](evidence/0.1.0-rc1/footprint-report.md) dosyasındadır.
Hedefli performance kanıtı
[`evidence/0.1.0-rc1/focused-performance-report.md`](evidence/0.1.0-rc1/focused-performance-report.md),
yenilenen protokol ve fail-open kanıtı ise
[`evidence/0.1.0-rc1/protocol-report.md`](evidence/0.1.0-rc1/protocol-report.md) dosyasındadır.

## Footprint Sonucu Nasıl Okunur?

Exact-source footprint gate'i üç dengeli CPU-slot fazı kullanır. Her varyant aynı işi, aynı fiziksel
çekirdek slotunda ve sırayla çalıştırır. Prosesler aynı yaşta ölçülür. Script, feature kapalı SO
dosyasını kullanmadan önce tüm native kaynak girdilerinin fingerprint değerini alır.
`-SkipNativeBuild`, fingerprint eksik veya eskiyse testi reddeder. Böylece eski baseline binary'si
sessizce başarılı sonuç üretemez.

Embedded-native medyan ve maksimum farkları şöyledir:

| Ölçüm | Medyan | Maksimum |
| --- | ---: | ---: |
| Process `VmRSS` | `+1,676 MiB` | `+1,742 MiB` |
| smaps RSS | `+1,711 MiB` | `+1,817 MiB` |
| cgroup current | `+0,461 MiB` | `+1,754 MiB` |
| cgroup socket | `0 MiB` | `0 MiB` |
| Thread | `0` | `0` |

İsteğe bağlı kolaylık JAR'ının kaynakta atfedilen üst sınırı `0,741 MiB` oldu. Buna rağmen gözlenen
process/smaps maksimumu yaklaşık `3,055 MiB` değerine ulaştı. OpenJ9 instrumentation bootstrap,
transformer olmasa bile ölçülebilir. JAR yalnız argüman çevirme kolaylığı sağlar. Ayrı raporlanır ve
embedded-native sertifikasını devralmaz.

## Performance Kararı

Güncel mikro profil varsayılanı `reactor.glowroot.http.sample-rate=256` değeridir. HTTP `5xx` tam
sayılır. Başarılı istekler ağırlıklı örneklerle temsil edilir. Hedefli c256 small-direct gate'inde:

- başarılı HTTP 200 RPS `-%0,17` değişti;
- p99 `+%6,28` değişti;
- `503` farkı `0` puan oldu;
- process RSS `+2,18 MiB`, container memory `+0,88 MiB` değişti.

Bu hücre; `-%2` RPS, `+%10` p99, `+2` puan `503` ve `+3 MiB` memory sınırlarını geçti. Bu sonuç
bütün endpoint sınıflarını kanıtlamaz. Sonraki c64 koşusunda baseline varyasyonu yüksekti. Bu koşu
regresyon veya başarı değil, `INCONCLUSIVE` olarak tutulur.

Benchmark artık Windows host gürültülüyken yüke başlamadan hata verir. Varsayılan sınırlar; ortalama
CPU için en fazla `%15`, tepe CPU için en fazla `%40` ve boş virtual memory için en az `3072 MiB`
değeridir. Release kanıtında `-SkipHostPreflight` kullanmayın.

## Gate'leri Tekrarlayın

Footprint ve kaynak doğrulama gate'i:

```powershell
.\benchmark\feature_artifact_footprint.ps1 `
  -RepeatCount 3 `
  -Concurrency 256 `
  -RequestsPerEndpoint 4096 `
  -FailOnGate
```

Protokol, isteğe bağlı bootstrap ve collector kapalı fail-open gate'i:

```powershell
.\benchmark\glowroot_gate.ps1 -ProtocolOnly -FailOnGate
```

Sessiz hedef node üzerinde tam endpoint matrisi:

```powershell
.\benchmark\glowroot_gate.ps1 `
  -PairRepeats 4 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json-direct,direct-json-writer,raw-json" `
  -HttpSampleRate 256 `
  -Duration "20s" `
  -Warmup "8s" `
  -FailOnGate
```

Gerekli endpoint ve concurrency hücrelerinin tamamı geçmelidir. `INCONCLUSIVE`, başarı değildir.

## Geçen Build Gate'leri

- Tüm feature'larla `57` Rust testi geçti.
- Tüm feature ve target'larla Rust Clippy geçti.
- Native feature setinden `glowroot` çıkarıldığında `30` Rust testi ve Clippy geçti.
- Windows OpenJ9 JNI smoke testi geçti.
- `rust-java-rest`, ABI ve native provenance kontrolleriyle Maven `clean verify` testini geçti.
- `java-rust-cache`, uyumlu native artifact'lerle Maven `clean verify` testini geçti.
- `java-rust-glowroot-agent` Maven `clean verify` testini geçti.
- Agent runtime dependency ağacı boştur.
- Windows DLL ve Linux SO aynı native kaynak revision'ından üretildi.
- Upstream Glowroot checkout'u `622dc6f800228cccc6fa37b0ed9e779446d7c41e` revision'ında read-only kaldı.

## Kalan Release Kanıtları

- Tam c64/c256 endpoint matrisini hedef Kubernetes node sınıfı ve OpenJ9 image üzerinde çalıştırın.
- Native kod sayfası büyümesini ölçmek için son release ile candidate artifact-upgrade gate'ini çalıştırın.
- Uyumlu Rust-Java REST ABI `28` binary'lerini yayımlayın. ABI `26` ile karıştırmayın.
- Production ortamındaki Glowroot Central sürümüne karşı protokol uyumluluğunu yeniden doğrulayın.
- Plaintext h2 network policy'sini doğrulayın veya TLS/mTLS'i localhost sidecar ya da service mesh ile sonlandırın.
- Ayrı adapter ve Spring image aynı gate'leri geçmeden Spring Boot desteği iddia etmeyin.

Sert bellek profili collector DNS kaydını yalnız startup sırasında çözümler ve en fazla dört farklı
IP adresi saklar. Sabit Kubernetes `ClusterIP` Service veya localhost sidecar kullanın. Headless ya
da çalışma sırasında değişen collector DNS bu profilin dışındadır ve pod restart gerektirir.
