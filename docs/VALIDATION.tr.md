# Doğrulama Kanıtı

[English](VALIDATION.md) | [Türkçe](VALIDATION.tr.md)

## Release Kararı

`0.3.0` sürümünde iki farklı çalışma şekli vardır:

- Rust-Java REST, framework içindeki Rust runtime'ını kullanır. Telemetri yeni işletim sistemi
  thread'i eklemez.
- Spring Boot MVC, standalone Rust exporter'ı yükler. `256 KiB` stack kullanan tek sınırlı thread ve
  tekrar kullanılan tek h2 collector bağlantısı ekler.

Release workflow, tag'in işaret ettiği commit başarılı Production Gate sonucu olmadan yayın yapmaz.
Bu koşunun okunabilir raporu ve JSON özeti GitHub Release asset'lerine eklenir. Başka branch veya
eski commit üzerinde alınmış sonuç bu kontrolü geçemez.

Bu bölüm yayınlanmış `0.3.0` sözleşmesini anlatır. Spring ve Rust-Java REST için tek izole exporter
thread'i kullanılır. Glowroot ABI `3` ve REST ABI `29` gerekir.

## Güncel Kaynak Kod Profil Gate'i

Profil yaşam döngüsü exact-source Linux standalone `.so`, Semeru OpenJ9 21, bir CPU, `256 MiB`
container limiti, `-Xms16m -Xmx64m -Xss256k` ve erişilemeyen collector ile test edildi. Her profil,
üç ayrı process içinde `100` yükseltme ve düşürme döngüsü tamamladı.

| Hedef profil | Başlangıç `micro` RSS medyanı | İlk aktif RSS farkı medyanı | 100 döngü sonrası son `micro` RSS farkı medyanı |
| --- | ---: | ---: | ---: |
| `jvm` | `61.868 KiB` | `+4.380 KiB` | `+800 KiB` |
| `sql` | `61.724 KiB` | `+4.052 KiB` | `+464 KiB` |
| `full` | `61.900 KiB` | `+4.512 KiB` | `+812 KiB` |

Her son durumda `active_profile_memory_ceiling_bytes=0`,
`retired_profile_memory_ceiling_bytes=0`, `profile_release_pending=false` ve
`jvm_probe_registered=false` ve `jvm_probe_owned_global_refs=0` görüldü. `100` logical release işleminin tamamı bitti. Döngüden döngüye
büyüme görülmedi. Konservatif hesaplanan güncel `full` native state üst sınırı yaklaşık `79,3 KiB`
değerindedir. İlk
kullanımdaki RSS farkının büyük bölümü OpenJ9 class, JIT, JMX ve hata sınıflarının tek seferlik ısınma
alanıdır. Native profil kuyruklarının bellekte kalması değildir.

Temsili bir `full -> micro` koşusunda kaynak bırakma süresi p50 `181 us`, p95 `294 us` ve p99
`350 us` ölçüldü. HTTP native kayıt yolu, sıralı `micro` ve `full` turlarında çağrı başına yaklaşık
`30-32 ns` kaldı. Tekrarlanabilir profil kaynaklı regresyon görülmedi. OpenJ9, yönetim API'lerinin ilk
kullanımından sonra JVM'e ait bir `Finalizer thread` oluşturdu. Agent exporter native olduğu için
Java thread listesinde görünmez.

Export nesli, devam eden hata yakalama sahipliği, yeniden başlatmaya dayanıklı bırakma kontrolü ve
sabit Java hata kimliği eklendikten sonra güncel exact-source binary ile `full -> başlangıçtaki micro`
testi üç yeni, tek CPU ve `128 MiB` limitli container içinde tekrar koşuldu. Başlangıç RSS medyanı
`61.608 KiB`, ilk `full` farkı medyanı `+4.412 KiB`, `100` döngü sonundaki fark medyanı `+828 KiB`
oldu. Profil düşürme ve kaynak bırakma sürelerinin medyanları p50 `200 us`, p95 `269 us` ve p99
`299 us` olarak ölçüldü. Scheduler/JIT gürültüsü altında tek bir kaynak bırakma maksimumu `41,7 ms`
değerine çıktı. İlk JVM/JIT ısınmasında `50-100 ms` aralığında uç değerler görüldü. Buna
rağmen bekleyen release, tutulmuş profil byte'ı, JNI probe veya döngüsel büyüme kalmadı. Sırası
dengelenmiş üç hot-path sürecinde `micro` `27,22-28,78 ns`, `full` `27,20-28,38 ns` aralığında
ölçüldü. Bu host üzerinde tekrarlanabilir bir profil regresyonu kanıtlanmadı.

JVM bean discovery, ölçüm, tanılama ve tanılama dosya işlemleri Java yardımcılarından Rust'a
taşındıktan sonra ABI `3` exact-source binary, odaklı OpenJ9 gate'inden geçti. Aktif `full` veya
`diagnostic` profilinde Rust `11` JNI global referansına sahipti. Her `micro` dönüşünde bu sayı `0`
oldu. `100` döngülük `full` koşusu `62.112 KiB` başlangıç RSS değerinden `62.808 KiB` son RSS
değerine geldi. Diagnostic koşusu gerçek thread dump üretti. Son durumda
`diagnostic_completed=1`, `diagnostic_failed=0` görüldü. Profile ait byte veya referans kalmadı.
Koordineli REST ABI `29` probe'u da `100` döngüyü tamamladı ve sahip olunan bütün state'i sıfırladı.

Gerçek mock collector wire testinde bir init ve `20` değer içeren bir gauge mesajı alındı. Bu
değerlerin onu, Rust tarafından toplanan heap, non-heap, memory-pool ve GC ölçümleriydi. Üç taze
hot-path process'inde native kayıt çağrısı `micro` için `27,95-29,96 ns`, `full` için
`28,45-31,99 ns` ölçüldü. Aynı process içindeki fark `%1,79-%6,88` aralığında kaldı. Bunlar odaklı
sahiplik ve protokol kontrolleridir. Randomize tam release matrisi değildir.

REST ABI `29` kullanan koordineli Linux binary de `100` kez `full -> başlangıçtaki micro` döngüsünü
`active/retired=0`, bekleyen release olmadan ve JNI JVM probe kapalı şekilde tamamladı. Son RSS,
başlangıç RSS değerinden daha düşüktü. Bunun nedeni glibc `malloc_trim(0)` çağrısının process içinde
agent dışındaki boş allocator sayfalarını da işletim sistemine iade edebilmesidir. Bu sonuç yaşam
döngüsünün tamamlandığını kanıtlar; agent'a ait RSS kazancını tek başına kanıtlamaz. Resident bellek
yalnız telemetri kapalı/açık taze process A/B gate'i ile agent'a atfedilmelidir.

Standalone ve koordineli REST probe'ları, `getStackTrace()` metodu hata veren özel bir `Throwable`
ile de geçti. JNI, bekleyen probe exception'ını temizledi, sınırlı drop sayacını artırdı ve business
hata akışını değiştirmedi.

Bu testler sınırlı kaynak sahipliğini ve tekrarlanabilir bırakma davranışını kanıtlar. Yeni stable
release kanıtı değildir. ABI `3` yayınlanmadan önce exact-source randomize tam HTTP RPS/p99/RSS
matrisi ve temiz Windows/Linux native paketleri tamamlanmalıdır.

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
| Rust-Java REST performans matrisi | RELEASE ZORUNLULUĞU | Altı eşleştirilmiş koşu, üç endpoint sınıfı, c64/c256, REST `4.5.0` ve native ABI `29` |
| Rust-Java REST protokol ve fail-open | RELEASE ZORUNLULUĞU | Upstream wire şeması, collector kesintisi ve opsiyonel `-javaagent` bootstrap birlikte geçmelidir |
| Spring performans matrisi | RELEASE ZORUNLULUĞU | Altı eşleştirilmiş koşu, üç endpoint sınıfı, c64/c256 ve exact-commit kanıtı |
| Spring steady memory | RELEASE ZORUNLULUĞU | Aynı tam yük ve process yaşı; RSS/cgroup eşleştirilmiş medyan farkı en fazla `+3 MiB` |

Embedded footprint raporu
[`evidence/0.1.0-rc1/footprint-report.md`](evidence/0.1.0-rc1/footprint-report.md) dosyasındadır.
Stable `0.3.0` için geçerli kanıtlar, GitHub Release'e eklenen
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

Her performans hücresinde başarılı HTTP 200 RPS kaybı en fazla `%2`, p99 artışı en fazla `%10` ve
yeni thread sayısı en fazla bir olmalıdır. Non-2xx oranı eşleştirilmiş medyanda ve altı tekrarın istek
sayısıyla ağırlıklandırılmış toplamında artmamalıdır. Adayın en yüksek hata oranı da baseline'ın en
yüksek oranını aşmamalıdır. Tek bir eşleşmedeki fark tanı amacıyla raporda kalır; bu genel kararların
yerine geçmez. Build bittikten sonra Linux
en sakin fiziksel CPU grubunu seçer. Bu grubun bütün SMT kardeşleri uygulamaya ayrılır. Load runner ve
collector başka bir gruba sabitlenir. Bütün steal-time aralıkları `%1` içinde kalmalıdır. Tek mantıksal
CPU elle seçilirse eşleştirilmiş SMT kardeşi aktivite farkı da `%10` içinde kalmalıdır.

Her application process, endpoint başına tam sekiz warmup turu tamamlar. Son üç RPS örneğinin yayılımı
en fazla `%8` olduğunda ölçüm başlar. Böylece baseline ve candidate aynı warmup işini yapar. OpenJ9
interpreter/JIT ısınması agent maliyeti gibi raporlanmaz. Ham warmup RPS örneklerinin tamamı release
kanıtlarına eklenir.

Endpoint içindeki anlık RSS maksimumları tanılama amacıyla raporda kalır. OpenJ9 JIT/GC resident
sayfaları bağımsız prosesler arasında iki yönde değişebilir. Bu nedenle bellek kontrollü bir noktada
ölçülür. Her varyant aynı warmup ve tam endpoint yükünü tamamlar. Aynı idle süresini bekler. Eşit
process yaşında beş örnek alınır. Eşleştirilmiş process RSS ve cgroup medyan farklarının ikisi de
`+3 MiB` içinde kalmalıdır. Kaynak koda atfedilen native üst sınır ayrıca ve daha sıkı ölçülür.

## Rust-Java REST Gate Nasıl Çalışır?

REST gate, yayınlanmış `rust-java-rest:4.5.0` source tag'ini kullanır. Native ABI `29` değilse test
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
  -MaxWarmupRounds 8 `
  -MaxWarmupRpsSpreadPercent 8 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Rust-Java REST production matrisi:

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -ApplicationKind rust-java-rest `
  -RequiredRestVersion "4.5.0" `
  -RequiredRestNativeAbi 29 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 8 `
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
