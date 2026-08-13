# Upstream Glowroot Analizi ve Tasarım Kararı

[English](UPSTREAM_ANALYSIS.md) | [Türkçe](UPSTREAM_ANALYSIS.tr.md)

## Kapsam

Referans olarak kullanılan Glowroot revision değeri
`622dc6f800228cccc6fa37b0ed9e779446d7c41e` değeridir. Bu checkout değiştirilmez. Yalnız mimari
analizde ve benchmark mock collector protobuf üretiminde read-only olarak kullanılır.
`java-rust-glowroot-agent`, upstream repoyu fork etmez veya shade etmez.

Production collector da fork edilmez veya değiştirilmez. Bu proje yalnız application agent'ı ve
onunla uyumlu Rust-Java native runtime'ı sağlar.

Hedefimiz tam Glowroot agent'tan daha dardır:

- Java handler ve iş mantığı değişmeyecek;
- HTTP, native Dubbo, native Redis, RSS, thread ve exporter sağlık verileri Glowroot'ta görülecek;
- Rust-Java REST'e Java request interceptor eklenmeyecek; Spring'de yalnız bir sınırlı MVC interceptor kullanılacak;
- state, queue, payload ve reconnect davranışının tamamı sınırlı olacak;
- agent-owned state ve feature sayfaları `1 MiB` altında kalacak; embedded yol yalnız eşleştirilmiş
  Linux resident-memory farklarının tamamı `+3,00 MiB` altında ve ek thread sayısı `0` ise kabul edilecek.

## Tam Glowroot Agent Neleri Yönetiyor?

Upstream agent genel amaçlı bir APM runtime'dır. Daha geniş maliyet, sunduğu daha geniş özelliklerden
gelir. Tek bir dependency kaldırılarak çözülemez.

| Upstream alan | Kod kanıtı | Runtime etkisi |
| --- | --- | --- |
| Java agent başlangıcı | `agent/core/.../AgentPremain.java`, `MainEntryPoint.java` | `Instrumentation` alır, yüklenmiş class'ları inceler ve agent runtime'ını kurar |
| Bytecode weaving | `agent/core/.../init/AgentModule.java`, `weaving/WeavingClassFileTransformer.java` | Transformer, ASM metadata, advice, class cache ve retransform state'i gerekir |
| Plugin discovery | `agent/core/.../config/PluginCache.java` | Plugin descriptor, aspect class ve konfigürasyon runtime'da tutulur |
| Transaction ve trace modeli | `agent/core/.../impl/Transaction.java`, `TraceCollector.java` | Request başına transaction/entry/query state'i ve ayrı collector döngüsü oluşur |
| JVM ve JMX | `agent/core/.../init/GaugeCollector.java`, `live/LiveJvmServiceImpl.java` | MBean discovery, zamanlanmış collection ve JVM operasyonları eklenir |
| Canlı operasyonlar | `live/LiveWeavingServiceImpl.java`, `central/DownstreamServiceObserver.java` | Çift yönlü kontrol, canlı weaving ve remote config yüzeyi oluşur |
| Collector transport | `agent/core/.../central/CentralConnection.java` | Java gRPC ve Netty channel, buffer, event-loop ve reconnect state'i gerekir |
| Java dependency'leri | `agent/core/pom.xml` | ASM, Guava, Jackson, Logback, protobuf, gRPC ve Netty yüklenir |

Bu parçalar tam APM ürünü için doğrudur. Bunları çıkarıp rastgele Java metodu, SQL, JMX, profiler ve
canlı weaving desteği vermeye devam etmek güvenli değildir. Hepsini tutmak ise mikro ajan bellek
hedefiyle bağdaşmaz.

## Reddedilen Tasarımlar

### ANTI-PATTERN: Tam agent'ı shade edip JAR exclude etmek

Transitive exclude yalnız artifact boyutunu küçültür. Geçerli bir runtime sınırı oluşturmaz.
Glowroot core; weaving, plugin, config, logging, protobuf, gRPC ve Netty tiplerini doğrudan kullanır.
Kör exclude, hatayı build aşamasından startup'a veya nadir bir production akışına taşır.

### ANTI-PATTERN: Framework annotation'ları için küçük transformer yazmak

Framework'te build-time route index ve immutable Rust route tablosu zaten vardır. Transformer, bilinen
metadata'yı tekrar üretir. `java.instrument` ve class state'i ekler. Request telemetrisine yeni bir
değer katmaz.

### ACCEPTABLE: Tanılama podları için tam Glowroot agent

Metot bazlı trace, JDBC SQL, JMX, stack trace veya profiler gerekiyorsa upstream Glowroot kullanın.
Bu pod için ayrı memory bütçesi ölçün. Bu kullanım mikro ajan içinde çalışan otomatik fallback değildir.

### BEST: Framework'e özel Rust telemetri düzlemi

Build-time route bilgisini ve native veri düzlemi hook'larını kullanın. Java JAR dosyasını tek class
konfigürasyon bootstrap'ı olarak tutun. Yalnız gerekli public protobuf alanlarını encode edin. Veriyi
tek ve sınırlı Rust h2 bağlantısıyla gönderin.

## Uygulanan Runtime

```mermaid
flowchart LR
    A["Build-time route index"] --> B["Önceden atanmış u16 route slotu"]
    H["Rust Hyper"] --> B
    D["Native Dubbo"] --> C["Üç sabit native slot"]
    R["Native Redis"] --> C
    B --> M["Sınırlı atomic aggregate"]
    C --> M
    M --> S["Bir dakikalık snapshot"]
    S --> P["Elle encode edilen protobuf"]
    P --> X["Tek Rust h2 bağlantısı"]
    X --> G["Glowroot Central"]
```

Request akışı önceden atanmış `u16` slot kullanır. Transaction adı, map key, protobuf object, trace
object veya Java callback üretmez. Başarılı HTTP request'leri ağırlıklı ve deterministik olarak
örneklenir. `5xx` hataları tam sayılır. `trace.capacity=0` ise trace kuyruğu ayrılmaz ve yavaş trace
akışı değerlendirilmez.

Exporter, framework'ün Tokio runtime'ını paylaşır. Ayrı işletim sistemi thread'i açmaz. Route tablosu,
trace kuyruğu, h2 window, response body, encode request, timeout ve reconnect gecikmesi için kesin üst
sınırlar vardır. Collector kapalıyken retry backlog oluşmaz. Süresi biten interval drop edilir ve
diagnostics sayacında görünür.

## Collector Kontratı

Mikro ajan şu mevcut unary mesajları gönderir:

| Glowroot metodu | Gönderilen veri |
| --- | --- |
| `collectInit` | Agent id, host/process/Java bilgisi ve read-only config |
| `collectAggregates` | HTTP, Dubbo ve Redis count, hata, süre ve HdrHistogram bilgisi |
| `collectGaugeValues` | Process RSS, thread sayısı ve exporter sağlık bilgisi |
| `collectTrace` | İsteğe bağlı sınırlı HTTP yavaş/hatalı request örneği |

Unary aggregate ve trace metotları mevcut şemada deprecated olarak işaretlidir. Buna rağmen test
edilen kontratta hâlâ vardır. Her Glowroot Central yükseltmesinde generated-protobuf protokol gate'i
yeniden çalıştırılmalıdır. Streaming metotlara geçiş gelecekteki uyumluluk işidir. v1 içinde gizli bir
varsayım değildir.

## Production Sınırı

Mikro ajan; rastgele Java metodu, JDBC sorgusu, JMX discovery, log, profiler, heap dump, stack trace,
canlı weaving veya remote agent config sağlamaz. v1 transport düz h2 kullanır. Şifreleme veya workload
identity gerekiyorsa localhost mTLS sidecar ya da service mesh kullanın.

Bu sınır footprint'i koruyan ana özelliktir. Dışarıda bırakılan bir yüzey eklenecekse ayrı mimari ve
yeni RSS/RPS/p99 gate gerekir. Mikro profile sessizce eklenmemelidir.
