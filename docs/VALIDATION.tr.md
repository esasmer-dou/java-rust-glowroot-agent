# Doğrulama Kanıtı

[English](VALIDATION.md) | [Türkçe](VALIDATION.tr.md)

## Release Kararı

`0.2.1` sürümünde iki farklı çalışma şekli vardır:

- Rust-Java REST, framework içindeki Rust runtime'ını kullanır. Telemetri yeni işletim sistemi
  thread'i eklemez.
- Spring Boot MVC, standalone Rust exporter'ı yükler. `256 KiB` stack kullanan tek sınırlı thread ve
  tekrar kullanılan tek h2 collector bağlantısı ekler.

Release workflow, tag'in işaret ettiği commit başarılı Production Gate sonucu olmadan yayın yapmaz.
Bu koşunun okunabilir raporu ve JSON özeti GitHub Release asset'lerine eklenir. Başka branch veya
eski commit üzerinde alınmış sonuç bu kontrolü geçemez.

## Doğrulanan Sözleşmeler

| Gate | Sonuç | Sözleşme |
| --- | --- | --- |
| Bootstrap JAR yüzeyi | PASS | Tek application class; runtime dependency, native binary ve transformer yok |
| Spring starter doğruluğu | PASS | Route, mapped status, tam hata, sınırlı route cache ve async completion testleri |
| Upstream Glowroot protokolü | PASS | Init, aggregate, gauge, trace ve HdrHistogram mesajları sabitlenen upstream şema ile okundu |
| Collector kapalı | PASS | Business HTTP çalışır; backlog ve reconnect davranışı sınırlı kalır |
| Embedded agent-owned bütçe | PASS | Hesaplanan `358.531` byte; sert state/rezerv sınırı `384 KiB` |
| Embedded native atfedilen üst sınır | PASS | Kod sayfaları dahil `0,694 MiB`; ek thread `0` |
| Embedded resident maksimum | PASS | smaps RSS maksimumu `+1,817 MiB`; `+3 MiB` sınırının altında |
| Temiz standalone native kaynak | PASS | Windows/Linux binary'leri temiz `a1ed7f0dde4f7903b66589ed5d5a759d6b9c9802` revision'ından üretildi |
| Rust-Java REST performans matrisi | RELEASE ZORUNLULUĞU | Altı eşleştirilmiş koşu, üç endpoint sınıfı, c64/c256, REST `4.4.1` ve native ABI `28` |
| Rust-Java REST protokol ve fail-open | RELEASE ZORUNLULUĞU | Upstream wire şeması, collector kesintisi ve opsiyonel `-javaagent` bootstrap birlikte geçmelidir |
| Spring performans matrisi | RELEASE ZORUNLULUĞU | Altı eşleştirilmiş koşu, üç endpoint sınıfı, c64/c256 ve exact-commit kanıtı |
| Spring steady memory | RELEASE ZORUNLULUĞU | Aynı tam yük ve process yaşı; RSS/cgroup eşleştirilmiş medyan farkı en fazla `+3 MiB` |

Embedded footprint raporu
[`evidence/0.1.0-rc1/footprint-report.md`](evidence/0.1.0-rc1/footprint-report.md) dosyasındadır.
Stable `0.2.1` için geçerli kanıtlar, GitHub Release'e eklenen
`spring-boot-production-gate.md` ve `rust-java-rest-production-gate.md` dosyalarıdır. Release
workflow'u, iki rapor da aynı exact-commit Production Gate koşusundan gelmedikçe etiketi reddeder.

## Spring Gate Nasıl Çalışır?

Gate, starter bulunan tek bir Spring Boot image üretir. Baseline telemetriyi kapatır. Candidate
telemetriyi açar. Uygulama sınıfları, dependency'ler, JVM ayarları, CPU kotası ve bellek limiti aynı
kalır.

Matris şu alanları kapsar:

- küçük dynamic JSON;
- raw veya önceden hazırlanmış JSON;
- dynamic heavy JSON;
- `64` ve `256` concurrency;
- sırası değiştirilen altı baseline/candidate çifti.

Her performans hücresinde başarılı HTTP 200 RPS kaybı en fazla `%2`, p99 artışı en fazla `%10`,
non-2xx artışı sıfır puan ve yeni thread sayısı en fazla bir olmalıdır. Build bittikten sonra Linux
en sakin fiziksel CPU grubunu seçer. Bu grubun bütün SMT kardeşleri uygulamaya ayrılır. Load runner ve
collector başka bir gruba sabitlenir. Bütün steal-time aralıkları `%1` içinde kalmalıdır. Tek mantıksal
CPU elle seçilirse eşleştirilmiş SMT kardeşi aktivite farkı da `%10` içinde kalmalıdır.

Her application process, endpoint başına tam altı warmup turu tamamlar. Son üç RPS örneğinin yayılımı
en fazla `%8` olduğunda ölçüm başlar. Böylece baseline ve candidate aynı warmup işini yapar. OpenJ9
interpreter/JIT ısınması agent maliyeti gibi raporlanmaz. Ham warmup RPS örneklerinin tamamı release
kanıtlarına eklenir.

Endpoint içindeki anlık RSS maksimumları tanılama amacıyla raporda kalır. OpenJ9 JIT/GC resident
sayfaları bağımsız prosesler arasında iki yönde değişebilir. Bu nedenle bellek kontrollü bir noktada
ölçülür. Her varyant aynı warmup ve tam endpoint yükünü tamamlar. Aynı idle süresini bekler. Eşit
process yaşında beş örnek alınır. Eşleştirilmiş process RSS ve cgroup medyan farklarının ikisi de
`+3 MiB` içinde kalmalıdır. Kaynak koda atfedilen native üst sınır ayrıca ve daha sıkı ölçülür.

## Rust-Java REST Gate Nasıl Çalışır?

REST gate, yayınlanmış `rust-java-rest:4.4.1` source tag'ini kullanır. Native ABI `28` değilse test
başlamaz. Tek bir minimal production image hazırlanır. Telemetri kapalı ve açık varyantlar aynı
fiziksel CPU üzerinde sıralı çalışır. Matris; küçük JSON, raw JSON ve heavy JSON endpoint'lerini
c64/c256 altında ölçer. Embedded telemetri yeni işletim sistemi thread'i ekleyemez.

İkinci gate, mesajları sabitlenen Glowroot wire şemasıyla doğrular. Collector durdurulduğunda
business HTTP'nin çalışmaya devam ettiğini de kanıtlar. Son olarak aynı REST image, opsiyonel
`-javaagent` bootstrap ile başlatılır. İstenen sınırlı native ayarların uygulandığı kontrol edilir.
Stable tag için bu kontrollerin tamamı zorunludur.

## Gate'leri Tekrarlayın

Spring production matrisi:

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 6 `
  -MaxWarmupRpsSpreadPercent 8 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Rust-Java REST production matrisi:

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -ApplicationKind rust-java-rest `
  -RequiredRestVersion "4.4.1" `
  -RequiredRestNativeAbi 28 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 6 `
  -MaxWarmupRpsSpreadPercent 8 `
  -MemoryLimit "128m" `
  -AllowedThreadDelta 0 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Protokol ve collector kapalı fail-open gate'i:

```powershell
.\benchmark\glowroot_gate.ps1 `
  -ProtocolOnly `
  -SkipBuild `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Embedded exact-source footprint gate'i:

```powershell
.\benchmark\feature_artifact_footprint.ps1 `
  -RepeatCount 3 `
  -Concurrency 256 `
  -RequestsPerEndpoint 4096 `
  -FailOnGate
```

Release kanıtında `-SkipHostPreflight` kullanmayın. Host kalitesi nedeniyle reddedilen koşu ürün
regresyonu değildir. Sessiz bir node üzerinde yeniden çalıştırılmalıdır.

## Build Kanıtı

- Full native runtime: `57` test ve warning kabul etmeyen Clippy kontrolü.
- Standalone Glowroot runtime: `28` test ve warning kabul etmeyen Clippy kontrolü.
- Java reactor: `15` test, packaged-native doğrulaması ve OpenJ9 JNI entegrasyonu.
- Executable Spring Boot smoke: starter ve isteğe bağlı tek sınıflı `-javaagent` bootstrap.
- Native build matrisi: full ve standalone runtime için Windows x64 ve Linux glibc x64.
- Native toolchain: Rust `1.91.0`; Java toolchain: Semeru OpenJ9 `21`.
- Glowroot wire referansı: upstream `622dc6f800228cccc6fa37b0ed9e779446d7c41e` revision'ı.

## Deployment Doğrulaması

Release gate'leri yayınlanan CI image üzerinde sınırlı ürün sözleşmesini kanıtlar. Production'a
çıkmadan önce kendi Kubernetes node sınıfınız, collector sürümünüz, CPU/bellek limitiniz, network
policy'niz ve endpoint dağılımınızla kısa smoke ve temsili yük testi çalıştırın. Bu ortam kontrolü
release ABI'sini değiştirmez. Deployment varsayımlarınızın test edilen profile uyduğunu gösterir.

Sert bellek profili collector DNS kaydını startup sırasında çözer ve en fazla dört adres tutar.
Sabit Kubernetes `ClusterIP` Service veya localhost sidecar kullanın. Headless veya çalışma sırasında
değişen collector adresinde pod'u yeniden başlatın. TLS/mTLS için service mesh veya sidecar kullanın.
