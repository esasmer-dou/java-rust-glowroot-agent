# Java-Rust Glowroot Agent

[English](README.md) | [Türkçe](README.tr.md)

[![CI](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml)
[![Sürüm](https://img.shields.io/github/v/release/esasmer-dou/java-rust-glowroot-agent)](https://github.com/esasmer-dou/java-rust-glowroot-agent/releases)

Rust-Java REST ve Spring Boot MVC uygulamaları için sınırlı kaynak kullanan Rust tabanlı telemetri
çözümüdür. HTTP toplamlarını, hataları, isteğe bağlı sınırlı trace verisini, process ölçümlerini ve
native Dubbo/Redis sürelerini mevcut Glowroot Central collector'a gönderir.

Controller, handler, service, validation ve veritabanı kodunuz değişmez. Agent bytecode weaving
yapmaz. Byte Buddy, ASM, Java gRPC, Netty veya Java executor eklemez.

## İçindekiler

- [Çalışma Şeklini Seçin](#çalışma-şeklini-seçin)
- [Hangi Verileri Alırsınız?](#hangi-verileri-alırsınız)
- [Rust-Java REST Kurulumu](#rust-java-rest-kurulumu)
- [Spring Boot MVC Kurulumu](#spring-boot-mvc-kurulumu)
- [Kubernetes](#kubernetes)
- [Ayarlar](#ayarlar)
- [Ayar Reçeteleri](#ayar-reçeteleri)
- [Hata Davranışı](#hata-davranışı)
- [Tanılama](#tanılama)
- [Performans Sözleşmesi](#performans-sözleşmesi)
- [Uyumluluk](#uyumluluk)
- [Build](#build)

## Çalışma Şeklini Seçin

| Uygulama | Uygulamaya eklenecek paket | Native çalışma şekli | Ek telemetri thread'i |
| --- | --- | --- | ---: |
| Rust-Java REST `4.4.1` | Starter gerekmez | Framework içindeki `rust_hyper` kütüphanesini kullanır | `0` |
| Spring Boot MVC `3.x` | `java-rust-glowroot-spring-boot-starter:0.2.1` | Küçük standalone agent kütüphanesini yükler | `1` |
| `-javaagent` standardı kullanan iki ortam | Tek sınıflı `java-rust-glowroot-agent:0.2.1` bootstrap | Yukarıdaki çalışma şekli değişmez | Yeni thread eklemez |

Bootstrap JAR yalnızca `-javaagent:key=value` değerlerini property'lere aktarır. İçinde tek sınıf
vardır. Native binary, transformer ve runtime dependency yoktur. Spring starter ayrı JAR olarak
kalır. Böylece Spring Boot executable JAR classloader sınırı bozulmaz.

Mevcut Glowroot collector, kullanıcı arayüzü ve veritabanı değişmez.

## Hangi Verileri Alırsınız?

| Veri | Davranış |
| --- | --- |
| HTTP çağrı sayısı ve süresi | Normalize edilmiş endpoint kalıbına göre sınırlı örnekleme |
| HTTP `5xx` | Başarılı çağrılar örneklense bile tam sayılır |
| Yavaş veya hatalı trace | İsteğe bağlı sınırlı kuyruk; varsayılan olarak kapalıdır |
| Rust-native Dubbo | Çağrı sayısı, süre ve hata toplamı |
| Rust-native Redis | Ayrı okuma/yazma sayısı, süre ve hata toplamı |
| Process ölçümleri | Her gönderim aralığında RSS ve thread sayısı |
| Gönderim sağlığı | Bağlantı, reconnect, hata, drop ve son hata sayaçları |

Request body, query değeri, header, SQL metni ve kişisel veri telemetriye kopyalanmaz.

Bu agent, bütün Glowroot özelliklerinin küçük bir kopyası değildir. Rastgele Java metodu izleme,
JDBC SQL, JMX, profiler, heap dump veya uzaktan ayar gerekiyorsa tam Glowroot agent kullanın.
`0.2.1` sürümü Spring WebFlux desteklemez. Spring adapter Servlet MVC içindir.

## Rust-Java REST Kurulumu

Uyumlu `4.4.1` framework sürümünü kullanın. Bu sürüm Glowroot native ABI `1` içerir. Native dosyanın
kaynak revision ve ABI bilgisi HTTP server başlamadan doğrulanır.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.4.1</version>
</dependency>
```

`rust-spring.properties` dosyasına şu değerleri ekleyin:

```properties
reactor.application.name=catalog-api
reactor.glowroot.enabled=true
reactor.glowroot.profile=micro
reactor.glowroot.collector.address=http://glowroot-collector:8181
reactor.glowroot.agent.id=catalog::local
reactor.glowroot.application.name=catalog-api
reactor.glowroot.http.sample-rate=256
reactor.glowroot.trace.capacity=0
```

Uygulamayı normal şekilde başlatın. Agent JAR gerekmez:

```bash
java -jar catalog-api.jar
```

Platform standardınız `-javaagent` istiyorsa ince bootstrap JAR'ını kullanabilirsiniz. Bu yalnızca
ayarların aynı Rust engine'e nasıl aktarıldığını değiştirir:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.2.1.jar=collector=http://glowroot-collector:8181,agent-id=catalog::pod-1,application=catalog-api \
  -jar catalog-api.jar
```

## Spring Boot MVC Kurulumu

### 1. Starter paketini ekleyin

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.2.1</version>
</dependency>
```

Starter varsayılan olarak kapalıdır. `application.properties` dosyasına şu değerleri ekleyin:

```properties
reactor.glowroot.enabled=true
reactor.glowroot.collector.address=http://127.0.0.1:8181
reactor.glowroot.agent.id=orders::local
reactor.glowroot.application.name=orders-api
reactor.glowroot.http.sample-rate=256
reactor.glowroot.trace.capacity=0
```

Mevcut Spring Boot uygulamasını başlatın:

```bash
java -jar orders-api.jar
```

Spring auto-configuration, MVC çağrısını çevreleyen tek bir Servlet filter ekler. Filter, Spring'in
seçtiği `/orders/{id}` gibi normalize edilmiş endpoint kalıbını handler tamamlandıktan sonra okur.
Uygulama sınıflarını taramaz ve Java worker pool oluşturmaz. Senkron isteklerde süre bilgisi yalnız
metot stack'inde tutulur. Sadece gerçekten async olan isteğe sınırlı bir completion listener eklenir.
Handler'ın ürettiği durum kodları ve yakalanmamış hatalar tam sayılır.

### 2. İsteğe bağlı erken başlangıç bootstrap'ı

Deployment standardınız `-javaagent` bekliyorsa veya process başlangıç bilgisini Spring'den önce
almak istiyorsanız bootstrap paketini de ekleyin:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-agent</artifactId>
  <version>0.2.1</version>
  <scope>runtime</scope>
</dependency>
```

Bootstrap JAR'ını executable Spring Boot JAR'ın dışında tutun. JVM'e dosya yolunu verin:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.2.1.jar=collector=http://glowroot-collector:8181,agent-id=orders::pod-1,application=orders-api,http-sample-rate=256,trace-capacity=0 \
  -jar orders-api.jar
```

Spring sınıflarını bootstrap JAR içine koymayın. Spring Boot, iç dependency'leri alt classloader ile
yükler. İki ayrı artifact kullanılması executable JAR uyumluluğu için gereklidir.

## GitHub Packages

Repository public olsa bile GitHub Packages, Maven indirmelerinde kimlik doğrulaması ister.
`read:packages` yetkili bir token oluşturun. `~/.m2/settings.xml` dosyasına şu server'ı ekleyin:

```xml
<settings>
  <servers>
    <server>
      <id>github-glowroot</id>
      <username>GITHUB_KULLANICI_ADINIZ</username>
      <password>GITHUB_PACKAGES_TOKEN_DEGERINIZ</password>
    </server>
  </servers>
</settings>
```

Uygulama POM'una package repository tanımını ekleyin:

```xml
<repositories>
  <repository>
    <id>github-glowroot</id>
    <url>https://maven.pkg.github.com/esasmer-dou/java-rust-glowroot-agent</url>
  </repository>
</repositories>
```

## Kubernetes

Pod adını agent id'nin son bölümü olarak kullanın. `::` ile biten prefix, Glowroot içinde bir üst
grup oluşturur.

```yaml
env:
  - name: REACTOR_GLOWROOT_ENABLED
    value: "true"
  - name: REACTOR_GLOWROOT_COLLECTOR_ADDRESS
    value: "http://glowroot-collector.observability.svc.cluster.local:8181"
  - name: REACTOR_GLOWROOT_AGENT_ID
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
  - name: REACTOR_GLOWROOT_APPLICATION_NAME
    value: "catalog-api"
  - name: REACTOR_GLOWROOT_HTTP_SAMPLE_RATE
    value: "256"
  - name: REACTOR_GLOWROOT_TRACE_CAPACITY
    value: "0"
```

`catalog::pod-adi` gibi bir hiyerarşi istiyorsanız tam değeri deployment şablonunda oluşturun. Aynı
anda çalışan her pod farklı agent id kullanmalıdır.

Collector için sabit bir `ClusterIP` Service veya localhost sidecar kullanın. DNS yalnız startup
sırasında çözülür. En fazla dört adres tutulur. Collector DNS hedefi değişirse pod'u yeniden
başlatın. Düz collector portunu internete açmayın. TLS veya mTLS gerekiyorsa service mesh ya da
localhost TLS sidecar kullanın.

## Ayarlar

Öncelik sırası şöyledir: JVM `-D` property, `-javaagent` argümanı, environment variable, uygulama
property'si ve varsayılan değer. Environment key için property adını büyük harfe çevirin. Nokta ve
tire yerine alt çizgi kullanın. Örnek: `reactor.glowroot.max-export-bytes`,
`REACTOR_GLOWROOT_MAX_EXPORT_BYTES` olur.

| Property | Varsayılan | Geçerli değer | Görevi |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Sınırlı telemetri runtime'ını açar |
| `reactor.glowroot.profile` | `micro` | `micro` | Sert sınırları olan özellik setini seçer |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | h2 HTTP URL | Glowroot Central adresi |
| `reactor.glowroot.agent.id` | boş | 1-256 byte | Zorunlu ve benzersiz agent/rollup kimliği |
| `reactor.glowroot.application.name` | uygulama adı | 1-128 byte | Glowroot ekranındaki isim |
| `reactor.glowroot.hostname` | `HOSTNAME` | en fazla 255 byte | Host veya pod etiketi |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000; 60000 katı | Toplam veri gönderim aralığı |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 bağlantı timeout'u |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Collector isteğinin toplam timeout'u |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Trace açıksa yavaş çağrı sınırı |
| `reactor.glowroot.http.sample-rate` | `256` | 1-1024 arasında ikinin kuvveti | Başarılı HTTP örnekleme oranı; `5xx` tam sayılır |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Sınırlı trace kuyruğu; `0` kuyruk ayırmaz |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Bellekte tutulacak en fazla endpoint sayısı |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Tek collector mesajının en büyük boyutu |
| `reactor.glowroot.spring.enabled` | `true` | boolean | Starter varsa Spring MVC Servlet filter'ını açar |
| `reactor.glowroot.spring.order` | `-2147483548` | integer | Servlet filter sırası; eski `interceptor-order` ve `filter-order` adları da çalışır |
| `reactor.glowroot.native.extract-dir` | kullanıcı home dizini | dizin | Spring standalone native çıkarma dizini |

Sınır dışındaki değerler uygulamanın başlamasını engeller. Agent bellek sınırını büyüten bir property
yoktur.

## Ayar Reçeteleri

| Senaryo | `sample-rate` | `trace.capacity` | Öneri |
| --- | ---: | ---: | --- |
| Yüksek trafikli production API | `256` | `0` | En düşük sabit ek yük; `5xx` yine tam sayılır |
| Düşük trafikli API | `1` veya `8` | `0` | Trafik az olduğu için daha fazla örnek alınır |
| Staging gecikme incelemesi | `64` | `0` | Histogram daha sık güncellenir; önce p99 A/B testi yapın |
| Tek pod üzerinde kısa olay incelemesi | `8` | `16` | Trace sınırlıdır; inceleme bitince kapatın |

Eksik business metric sorununu bütün yüksek trafikli pod'larda sample rate değerini `1` yaparak
çözmeyin. Sipariş, ödeme veya domain hataları için ayrıca açık business metric üretin.

## Hata Davranışı

- Hatalı yerel ayar startup'ı durdurur.
- Collector kesintisi HTTP, Dubbo, Redis veya iş mantığını durdurmaz.
- Bağlantı ve request timeout değerleri sınırlıdır.
- Reconnect sınırlı exponential backoff kullanır.
- Gönderilemeyen interval süresiz kuyrukta tutulmaz.
- Endpoint, trace, mesaj, DNS adresi ve export boyutları sert sınırlıdır.
- Eski veya uyumsuz native ABI erken ve anlaşılır bir hata verir.

## Tanılama

Rust-Java REST yerleşik tanılama endpoint'leri sunar:

```bash
curl -s http://localhost:8080/diagnostics/glowroot
curl -s http://localhost:8080/metrics | grep reactor_glowroot
```

Spring Boot tarafında yalnız ihtiyacınız varsa `NativeTelemetry` bean'ini güvenli bir tanılama
controller'ına inject edin ve `diagnosticsJson()` sonucunu döndürün. Starter kendiliğinden yönetim
endpoint'i açmaz.

`connected`, `export_failure`, `dropped_intervals`, `dropped_transactions`, `dropped_traces`,
`dropped_routes`, `reconnects` ve `last_error_code` alanlarını izleyin.

## Performans Sözleşmesi

Rust-Java içindeki yol, agent'a ait state ve native özellik sayfaları için deterministik `1 MiB`
sınırı uygular. Ek thread oluşturmaz. Spring standalone yolu ayrı ölçülür. Bu yol küçük native
kütüphaneyi ve `256 KiB` stack kullanan tek current-thread Tokio exporter thread'ini yükler.
Strict Spring gate, starter'ı property veya ortam değişkeniyle açar. İsteğe bağlı `-javaagent`
bootstrap yalnız kurulum kolaylığı sağlar ve ayrı doğrulanır. Transformer kurulmasa bile JVM
instrumentation sistemini başlatmak OpenJ9'a ait ek bellek oluşturur.

Release gate aynı image içinde telemetri kapalı/açık eşlenmiş ve sırası değiştirilmiş koşular yapar.
Her endpoint ve concurrency hücresi şu sınırları geçmelidir:

- başarılı HTTP 200 RPS kaybı en fazla `%2`;
- p99 artışı en fazla `%10`;
- non-2xx artışı `0` yüzde puanı;
- Rust-Java içinde ek thread `0`, Spring standalone için en fazla `1`.

Stable release bu tam matrisi Spring Boot ve Rust-Java REST için ayrı çalıştırır. İki matris de
small JSON, önceden hazırlanmış raw JSON ve dynamic heavy JSON endpoint'lerini c64/c256 seviyesinde
altı dengeli çiftle ölçer. RPS, p99 ve startup için önce her çiftin farkı bulunur, sonra medyan
hesaplanır. Aynı tam yükten sonra eşit process yaşında ölçülen RSS ve cgroup medyan farkları en fazla
`+3 MiB` olabilir. Rust-Java REST yeni thread ekleyemez. Spring yalnız tek sınırlı exporter thread'i
ekleyebilir. REST wire uyumu, collector kapalıyken fail-open ve opsiyonel bootstrap ayrıca zorunlu
olarak test edilir.

[Doğrulama Kanıtı](docs/VALIDATION.tr.md),
[Mimari ve Production Sınırı](docs/ARCHITECTURE.tr.md) ve
[Benchmark Rehberi](benchmark/README.md) belgelerine bakın.

## Uyumluluk

| Bileşen | Sürüm | Sözleşme |
| --- | ---: | --- |
| Java | `21` | Ana test JVM'i Semeru OpenJ9'dur |
| Rust-Java REST | `4.4.1` | REST ABI `28`, Glowroot ABI `1` |
| Agent bootstrap | `0.2.1` | Tek sınıf; iki desteklenen ortamda da çalışır |
| Spring Boot starter | `0.2.1` | Spring Boot `3.x`, Servlet MVC |
| Standalone native kaynak | `rust-spring v4.4.1` | Glowroot ABI `1`; temiz CI DLL/SO |
| Glowroot Central wire contract | upstream `0.14.8-beta.5-SNAPSHOT` checkout | Unary h2/protobuf uyumluluk gate'i |
| Native platform | Windows x64, Linux glibc x64 | Temiz CI build DLL/SO ve SHA-256 provenance |

DLL/SO dosyalarını sürümler arasında elle kopyalamayın. Framework, cache, Dubbo ve agent paketleri
uyumlu native ABI bilgisini startup sırasında doğrular.

## Build

```powershell
$env:JAVA_HOME = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
mvn -B -ntp clean verify
```

Maven reactor şu dosyaları üretir:

- `agent-bootstrap/target/java-rust-glowroot-agent-0.2.1.jar`
- `spring-boot-starter/target/java-rust-glowroot-spring-boot-starter-0.2.1.jar`

Native DLL/SO yalnız `native-provenance.properties` içinde yazan temiz `rust-spring` commit'inden
üretilir. Doğrulanmış CI artifact'lerini `scripts/sync-native-artifacts.ps1` ile alın. Yerel dirty
native build yayınlamayın.

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Embedded REST matrisini çalıştırmak için aynı komuta şu parametreleri ekleyin:

```powershell
-ApplicationKind rust-java-rest `
-RequiredRestVersion "4.4.1" `
-RequiredRestNativeAbi 28 `
-MemoryLimit "128m" `
-AllowedThreadDelta 0
```

Tam kopyala-yapıştır komutları için [Doğrulama Kanıtı](docs/VALIDATION.tr.md) belgesine bakın.

Mock collector yalnız test içindir. Glowroot Central yerine production ortamına kurmayın.
