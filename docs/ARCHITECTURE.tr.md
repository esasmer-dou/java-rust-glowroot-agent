# Mimari ve Production Sınırı

[English](ARCHITECTURE.md) | [Türkçe](ARCHITECTURE.tr.md)

## Karar

Bu proje, tam Glowroot Java agent içinden bazı dependency'leri çıkararak yeni bir JAR üretmez.
Glowroot collector wire kontratına konuşan, framework'e özel ayrı bir telemetri plane oluşturur.

Telemetri plane yalnız application tarafındadır. Mevcut Glowroot Central/collector deployment'ı,
storage ve UI değişmez. Benchmark mock collector yalnız doğrulama altyapısıdır. Runtime bileşeni
veya mevcut collector'ın alternatifi değildir.

Bu bilinçli bir karardır. Upstream agent core; Java instrumentation ve retransform, ASM weaving,
plugin discovery, Guava, Jackson, Logback, protobuf, gRPC ve Netty kullanır. Bu dependency'leri
çıkarıp herhangi bir Java metodu, JDBC, JMX ve profiler desteğini korumak mümkün değildir. Hepsini
tutmak ise `1 MiB` agent-owned bütçe hedefiyle bağdaşmaz.

## Veri Akışı

```mermaid
flowchart LR
    A["Rust Hyper request"] --> B["Önceden atanmış route slotu"]
    D["Native Dubbo çağrısı"] --> M["Sınırlı atomic aggregate"]
    R["Native Redis çağrısı"] --> M
    B --> M
    M --> S["Bir dakikalık snapshot"]
    S --> P["Elle encode edilen protobuf"]
    P --> H["Rust h2 client"]
    H --> C["Mevcut Glowroot Central (değişmez)"]
```

- Java `premain` yalnız konfigürasyonu hazırlar. Transformer kurmaz.
- Route adları startup sırasında bir kez atanır. Request path'i metric adı oluşturmaz.
- Başarılı HTTP request'leri ağırlıklı ve sınırlı örneklenir. HTTP `5xx` tam sayılır.
- Trace yalnız route adı, status ve süre taşır. Request body, query değeri, header, SQL ve business
  payload kopyalanmaz.
- Route slotu, trace kapasitesi, encode request boyutu, h2 window, timeout ve reconnect backoff için
  kesin üst sınırlar vardır.
- Export mevcut Tokio runtime'ını kullanır. Java executor veya ayrı native OS thread oluşturmaz.
- Dakikalık snapshot kilit kullanmaz. Tam rollup sınırında yarışan bir request komşu interval içinde
  sayılabilir. Bu nedenle tek bir dakikanın sınırı yaklaşık kabul edilir. Request path'ine kilit
  eklenmez; dashboard ve alarm kuralları tam sınır toplamı yerine kayan zaman aralığı kullanmalıdır.

## Hata Davranışı

Collector hatası hiçbir application request'ini düşürmemelidir. Exporter; bağlantı ve tüm çağrı için
timeout kullanır. Reconnect sınırlı exponential backoff ile yapılır. Collector kapalıyken bir rollup
süresi dolarsa aggregate ve trace verisi drop edilir. Bu durum diagnostics içinde görünür. Ajan
sınırsız retry backlog oluşturmaz.

Hatalı lokal konfigürasyon farklıdır. Trafik başlamadan startup'ı durdurur. Boş agent id, desteklenmeyen
TLS collector URL'i, ikinin kuvveti olmayan sample rate veya mikro profil sınırını aşan değer buna
örnektir.

## Memory Bütçesi

`+1 MiB`, ayar önerisi değil ajana atfedilen bellek için kesin ürün sınırıdır. Başlangıç kontrolü,
hesaplanan state ve transport rezervi için en fazla `384 KiB` kabul eder. Native özellik sayfaları
ve allocator yerleşimi için `640 KiB` bırakır. Hesaplanan üst sınır aşılırsa uygulama başlamaz.
Sınır property ile artırılamaz. Bu sözleşme JVM, uygulama veya kernel'in ajandan bağımsız belleğini
sınırlamaz.

| Yüzey | Kontrol |
| --- | --- |
| Java yüzeyi | Embedded-native production modunda agent yoktur; isteğe bağlı JAR tek class içerir ve transformer kurmaz |
| Route state | Sabit `max-routes`; atomikler bir kez ayrılır |
| Trace | İsteğe bağlı sabit `trace-capacity`; `0` kuyruk ve yavaş-trace kontrolünü kaldırır |
| Ağ | Startup'ta en fazla 4 IP çözümlenir; yalnız export batch'i boyunca açık kalan, 16 KiB flow-control ve socket-buffer isteği kullanan h2 bağlantısı |
| Encode | En fazla `max-export-bytes`; protobuf-java object graph yok |
| Thread | Framework'ün mevcut Tokio runtime'ı kullanılır |
| Collector kapalı | Sınırlı backoff ve interval drop; biriken backlog yok |

Public `micro` profili en fazla `64` route slotu, `32` trace örneği ve `64 KiB` encode request kabul
eder. Varsayılan trace kapasitesi `0` değeridir. Export snapshot ve protobuf buffer'ları geometrik
büyümez. Hesaplanan son kapasiteyle bir kez ayrılır.

Linux release kanıtı iki ayrı sınırı kullanır. Agent-owned state ve ölçülen feature sayfaları,
deterministik `1 MiB` kaynak bütçesinin altında kalmalıdır. Feature açık ve kapalı prosesler arasında
gözlenen her eşleştirilmiş process `VmRSS`, smaps RSS ve cgroup current farkı konservatif `3 MiB`
resident sınırının altında kalmalıdır. Ek thread sayısı `0` olmalıdır. Bağımsız OpenJ9 prosesleri JIT,
GC, allocator ve resident sayfa farkı nedeniyle oynayabilir. Resident gate bu oynaklığı bilinçli
olarak kapsar; kaynak allocation sözleşmesinin yerine geçmez. Her önemli endpoint ve concurrency
hücresi ayrıca en fazla `%2` RPS kaybı ve `%10` p99 artışı sınırını geçmelidir. Kararsız koşu
`INCONCLUSIVE` sayılır.

Release kanıtında son yayınlanan framework ile yeni framework ve açık ajan ayrıca karşılaştırılır.
Bu ikinci karşılaştırma, aynı-image açma/kapatma testinin gösteremediği native kod sayfası büyümesini
de ölçer.

Exact-source embedded-native artifact için atfedilen üst sınır `0,694 MiB`, ek thread sayısı `0`
oldu. Son üç fazlı resident gate de geçti. Maksimum farklar `VmRSS +1,742 MiB`, smaps RSS
`+1,817 MiB` ve cgroup current `+1,754 MiB` oldu. Gate, cache içindeki feature kapalı SO dosyasını
kabul etmeden önce tüm native kaynak girdilerinin fingerprint değerini doğrular. Eski baseline
binary'si reddedilir. İsteğe bağlı kolaylık JAR'ı ayrı raporlanır ve strict resident yoluna dahil
değildir; gözlenen process/smaps maksimumu yaklaşık `3,055 MiB` olmuştur.

Exporter collector DNS kaydını startup sırasında senkron olarak çözümler. En fazla dört farklı IP
adresini değişmez bir listede saklar. Reconnect sırasında yalnız bu liste kullanılır. Böylece runtime
DNS worker'ı veya büyüyen resolver state oluşmaz. Sert bellek profili normal Kubernetes `ClusterIP`
Service veya localhost sidecar gerektirir. Headless ya da çalışma sırasında değişen DNS kaydı için
pod yeniden başlatılmalıdır; bu kullanım profil sözleşmesinin dışındadır.

## Spring Boot Sınırı

Spring Boot Servlet MVC yolu bilinçli olarak iki ayrı artifact kullanır. Bootstrap JAR içinde yalnız
bir premain sınıfı vardır. Sınırlı argümanları property'lere aktarır. Starter ise Spring Boot'un
uygulama classloader'ında kalır, tek MVC Servlet filter ekler ve standalone native binary'yi taşır. Bu
ayrım, Servlet sınıflarının executable Spring Boot JAR ile kullanılan `-javaagent` JAR içine
konulması halinde oluşan parent/child classloader hatasını önler.

Başarılı istekler JNI çağrısından önce Java tarafında örneklenir. Eşleşen MVC handler'larının `5xx`
yanıtları tam sayılır. Filter, normalize edilmiş route kalıbını MVC dispatch tamamlandıktan sonra
yalnız örneklenen, yavaş veya hatalı istekte çözer. Senkron isteklerin süre bilgisi request thread'inin
stack alanında kalır. Sadece gerçek async istek completion listener ayırır. Java executor oluşturmaz
ve classpath taraması yapmaz. Standalone Rust
kütüphanesi, `256 KiB` stack kullanan tek current-thread Tokio exporter çalıştırır. Queue, endpoint
tablosu, trace buffer, mesaj ve DNS adres listesi native engine'in sert sınırlarına uyar.

Adapter; bytecode weaving, Byte Buddy/ASM, Java gRPC/Netty veya genel event bus kullanmaz. `0.2.0`
sürümü Servlet MVC destekler, WebFlux desteklemez. Spring'e özel method, JDBC, JMX veya profiler
instrumentation gerekiyorsa tam Glowroot agent kullanın.

Sertifikalı düşük bellekli Spring yolu, starter'ı property veya ortam değişkeniyle açar. Tek sınıflı
isteğe bağlı `-javaagent` bootstrap, erken startup bilgisi ve operasyon standardı için desteklenir.
Ancak transformer kurmasa bile OpenJ9 instrumentation sistemini başlattığı için bellek sonucu ayrı
ölçülür ve raporlanır.

## Uyumluluk

Test collector, protobuf şemasını read-only upstream Glowroot checkout'tan doğrudan derler. Rust
mesajlarını generated protobuf class'larıyla okur. Referans revision
`622dc6f800228cccc6fa37b0ed9e779446d7c41e` değeridir. Her Glowroot Central güncellemesinde protokol
gate'ini yeniden çalıştırın. Mevcut sürüm collector ile uyumlu eski unary aggregate ve trace
metotlarını kullanır. Deprecated metotların sonsuza kadar kalacağı varsayılmaz.

## Bilinçli Olarak Desteklenmeyen Alanlar

- herhangi bir Java metodu veya library instrumentation;
- JDBC SQL metni ve query trace;
- JMX attribute discovery;
- log event toplama;
- thread veya allocation profiler;
- heap dump;
- canlı bytecode weaving veya remote agent konfigürasyonu;
- v1 içinde doğrudan TLS/mTLS transport.

Bu ihtiyaçlarda tam Glowroot Java agent'ı kullanın. Mikro ajanla TLS/mTLS gerekiyorsa localhost
sidecar veya service mesh kullanın. Native h2 bağlantısını pod içinde tutun.
