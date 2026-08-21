# Java-Rust Glowroot Agent

[English](README.md) | [Türkçe](README.tr.md)

[![CI](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml)
[![Sürüm](https://img.shields.io/github/v/release/esasmer-dou/java-rust-glowroot-agent)](https://github.com/esasmer-dou/java-rust-glowroot-agent/releases)

Rust-Java REST ve Spring Boot uygulamaları için sınırlı kaynak kullanan Rust tabanlı telemetri
çözümüdür. HTTP toplamlarını, hataları, isteğe bağlı sınırlı trace verisini, process/JVM ölçümlerini,
açık SQL sürelerini ve native Dubbo/Redis sürelerini mevcut Glowroot Central collector'a gönderir.
HTTP verisi yalnız uygulamada uygun bir HTTP adaptörü varsa toplanır.

Controller, handler, service, validation ve veritabanı kodunuz değişmez. Agent bytecode weaving
yapmaz. Byte Buddy, ASM, Java gRPC, Netty veya Java executor eklemez.

## İçindekiler

- [Çalışma Şeklini Seçin](#çalışma-şeklini-seçin)
- [Sample Projelerde Kullanım](#sample-projelerde-kullanım)
- [Hangi Verileri Alırsınız?](#hangi-verileri-alırsınız)
- [İş Yükü Nerede Çalışır?](#iş-yükü-nerede-çalışır)
- [Rust-Java REST Kurulumu](#rust-java-rest-kurulumu)
- [Spring Boot Kurulumu](#spring-boot-kurulumu)
- [GitHub Packages](#github-packages)
- [Kubernetes](#kubernetes)
- [Linux Container Uyumluluğu](#linux-container-uyumluluğu)
- [Ayarlar](#ayarlar)
- [Çalışma Profilleri](#çalışma-profilleri)
- [Uygulamayı Yeniden Başlatmadan Profil Değiştirme](#uygulamayı-yeniden-başlatmadan-profil-değiştirme)
- [Ayar Reçeteleri](#ayar-reçeteleri)
- [Hata Davranışı](#hata-davranışı)
- [Tanılama](#tanılama)
- [Performans Sözleşmesi](#performans-sözleşmesi)
- [Uyumluluk](#uyumluluk)
- [Build](#build)

## Çalışma Şeklini Seçin

| Uygulama | Uygulamaya eklenecek paket | Native çalışma şekli | Ek telemetri thread'i |
| --- | --- | --- | ---: |
| Rust-Java REST `4.6.0` | Starter gerekmez | Framework içindeki `rust_hyper` kütüphanesini kullanır | Agent açıkken `1` |
| Spring Boot `3.x`, MVC | `java-rust-glowroot-spring-boot-starter:0.5.1` | Küçük standalone agent kütüphanesini ve uygun isteğe bağlı sunucu adaptörünü yükler | `1` |
| Spring Boot `3.x`, web olmayan | Minimum: `java-rust-glowroot-spring-runtime:0.5.1`; ana starter da çalışır | Minimum kurulumda yalnız web'den bağımsız standalone agent kütüphanesini yükler | `1` |
| Spring Boot `3.x`, WebFlux | `java-rust-glowroot-spring-webflux-adapter:0.5.1` | Aynı standalone native runtime'ı kullanır | `1` |
| `-javaagent` standardı kullanan iki ortam | Tek sınıflı `java-rust-glowroot-agent:0.5.1` bootstrap | Yukarıdaki çalışma şekli değişmez | Aynı tek exporter kullanılır; bootstrap eklemez |

Bootstrap JAR yalnızca `-javaagent:key=value` değerlerini property'lere aktarır. İçinde tek sınıf
vardır. Native binary, transformer ve runtime dependency yoktur. Spring starter ayrı JAR olarak
kalır. Böylece Spring Boot executable JAR classloader sınırı bozulmaz.

Mevcut Glowroot collector, kullanıcı arayüzü ve veritabanı değişmez.

> **Uyumluluk sınırı:** Çalışma sırasında profil değiştirme özelliği REST native ABI `29` ve
> Glowroot ABI `4` gerektirir. Agent `0.5.1` ile Rust-Java REST `4.6.0` kullanın. Eski bir paketten
> DLL/SO kopyalamayın.

`0.5.1` sürümü tam endpoint dashboard düzeltmesini içerir. Bir export aralığı bekledikten sonra
Glowroot **Transactions** ekranını açın ve transaction type olarak **Web** seçin. `/orders/{id}`
gibi route kalıpları `/orders/*` adıyla görünür.

## Sample Projelerde Kullanım

Sample projeler telemetriyi varsayılan olarak açmaz. Böylece normal hızlı başlangıç için Glowroot
Central zorunlu olmaz.

| Sample | Agent yolu | Beklenen davranış |
|---|---|---|
| [`rest-sample-cache-reader`](https://github.com/esasmer-dou/rest-sample-cache-reader) | Rust-Java REST içindeki runtime | Property ile açılır; HTTP ve native Redis read toplamları alınır |
| [`rest-sample-dubbo-consumer`](https://github.com/esasmer-dou/rest-sample-dubbo-consumer) | Rust-Java REST içindeki runtime | Property ile açılır; HTTP ve native Dubbo toplamları alınır |
| [`rest-sample-cache-writer`](https://github.com/esasmer-dou/rest-sample-cache-writer) | Plain Java scheduler | `0.5.1` bağımsız plain-Java runtime sunmaz; bootstrap tek başına yeterli değildir |
| [`rest-sample-dubbo-provider`](https://github.com/esasmer-dou/rest-sample-dubbo-provider) | Plain Java Dubbo/Netty provider | `0.5.1`, resmi Java provider runtime'ını instrument etmez |

Yalnız telemetri almak için plain-Java sample'a Spring Boot veya REST server eklemeyin. Uygulama
gerçek bir iş ihtiyacı nedeniyle zaten Spring Boot kullanıyorsa non-web starter'ı kullanın. Aksi
halde daha küçük runtime'ı koruyun ve bağımsız standalone runtime hazırlanana kadar platform
ölçümlerini kullanın.

Reader ve consumer README dosyalarında lokal ve Kubernetes için kopyalanabilir örnekler vardır. Yeni
production deployment'ında agent `0.5.1` açılmadan önce uygulamayı Rust-Java REST `4.6.0` hattına
hizalayın.

## Hangi Verileri Alırsınız?

| Veri | Davranış |
| --- | --- |
| HTTP çağrı sayısı, süresi, yüzdelik değeri ve throughput | Tamamlanan her çağrı tam sayılır ve normalize edilmiş endpoint kalıbına göre gruplanır |
| HTTP hataları | Durum ve hata toplamları tam sayılır; isteğe bağlı stack ayrıntısı sınırlı kalır |
| Yavaş veya hatalı trace | İsteğe bağlı sınırlı kuyruk; varsayılan olarak kapalıdır |
| Rust-native Dubbo | Çağrı sayısı, süre ve hata toplamı |
| Rust-native Redis | Ayrı okuma/yazma sayısı, süre ve hata toplamı |
| Process ölçümleri | Her gönderim aralığında RSS ve thread sayısı |
| JVM ölçümleri | İsteğe bağlı heap, non-heap, memory pool, GC sayısı ve GC süresi |
| SQL toplamları | İsteğe bağlı ve açıkça işaretlenen sınırlı SQL süreleri; JDBC proxy veya bytecode weaving yoktur |
| Hata stack bilgisi | Hatalı HTTP çağrıları ve açık SQL işlemleri için isteğe bağlı sınırlı stack kaydı |
| İstek üzerine tanılama | Kısa süreli `diagnostic` profilinde thread dump, heap histogram veya heap dump |
| Gönderim sağlığı | Bağlantı, reconnect, hata, drop ve son hata sayaçları |

Request body, query değeri, header, SQL metni ve kişisel veri telemetriye kopyalanmaz.

## İş Yükü Nerede Çalışır?

Agent'ın ağır işleri Rust tarafındadır. Java yalnız Spring veya JVM içinde oluşan bilgiyi Rust'a
ileten sabit maliyetli sınırdır.

| Alan | Sorumlu | Yapılan iş |
| --- | --- | --- |
| Toplama ve gönderim | Rust | Sınırlı route/SQL state'i, örnekleme toplamları, kuyruklar, protobuf encode, collector için HTTP/2 taşıması, reconnect, timeout ve drop politikası; tek düşük öncelikli batch exporter üzerinde çalışır |
| JVM ölçümleri | Rust | İzole exporter, JNI global referanslarını bulur ve sahiplenir; seçilen MXBean metotlarını çağırır, değerleri toplar ve gauge mesajını üretir |
| Hata ayrıntısı | Rust | Hatalı Java isteği yalnız sınırlı ve zayıf bir `Throwable` referansı iletir. Sınıf, mesaj ve stack satırlarını daha sonra izole exporter okur; uygulama request thread'i stack üzerinde dolaşmaz |
| Tanılama | Rust | Komut kuyruğu, JNI çağrısı, sınırlı yürütme, dosya yazma, atomik yayın, hata temizliği ve sayaçlar Rust'ta kalır |
| Profil yaşam döngüsü | Rust | İsteğe bağlı state ayrılır, emekliye alınır, bırakılır ve gerekirse Hyper ile uygulama worker'larından uzakta trim edilir |
| İsteğe bağlı Spring MVC sınırı | Java, yalnız sabit maliyetli geçiş | Uygulamanın mevcut sunucusuna uygun adaptör tamamlanmayı kaydeder: Tomcat Valve, Jetty RequestLog veya Undertow completion listener. Normalize edilmiş route, durum, süre ve gerekirse hata referansı Rust'a iletilir |
| İsteğe bağlı Spring WebFlux sınırı | Java, sınırlı reaktif callback | Ayrı WebFlux modülü yalnız reaktif yaşam döngüsünün gerektirdiği state'i tutar. Yanıt commit edilirken normalize edilmiş route'u okur ve sınırlı olayı Rust'a iletir. Reactor Netty eklemez veya seçmez |
| JVM iç işlemleri | JVM, Rust tarafından çağrılır | Veri JVM içinde olduğu için MXBean ve dump API'leri JVM'de çalışır; Java yardımcı sınıfı, polling thread'i, cache veya direct-buffer callback'i yoktur |

Native yaşam döngüsü Spring Web'e bağlı değildir. Veritabanı worker'ı, Kafka uygulaması, scheduler
veya komut satırı Spring Boot servisi; MVC, Servlet container ya da Java telemetri executor'ı
eklemeden process ve seçilen profile ait JVM/SQL verisini gönderebilir.

Rust-Java REST HTTP telemetrisi doğrudan Rust sunucusunda kaydedilir. Spring Boot Servlet
uygulamaları mevcut sunucunun doğrudan tamamlanma noktasını kullanır. Ana starter yalnız küçük iç
adaptör JAR'larını taşır. Sunucu API'leri `provided` ve `optional` kaldığı için agent Tomcat, Jetty
veya Undertow eklemez ve sunucu seçmez. Glowroot Average, Percentile, Throughput ve hata ekranlarının
doğru kalması için tamamlanan her istek sabit maliyetli tek event sınırından geçer. Trace örneklemesi
bu toplamları değiştirmez. Yavaş, hatalı, asenkron ve bulunamayan istekler bütün desteklenen
motorlarda aynı sınırlı event sözleşmesinden geçer. Hata oluştuğunda Java
`Throwable.getMessage()` veya `Throwable.getStackTrace()` çağırmaz. Rust, zayıf JNI referansını aynı
sert trace kapasitesi içinde kuyruğa alır. Ayrıntıyı izole exporter çözer. Referans GC tarafından
temizlenirse veya kuyruk dolarsa yalnız isteğe bağlı hata ayrıntısı atılır ve drop sayacı artar.
Uygulama isteği bekletilmez ve büyük exception nesne grafiği bellekte tutulmaz. Bu geçiş `micro` ve
`jvm` profillerinde yoktur. Hata trace kapasitesi sıfır olduğunda da çalışmaz.

Bu agent, bütün Glowroot özelliklerinin küçük bir kopyası değildir. Rastgele Java metotlarını
işaretlemez. Her JDBC nesnesini proxy ile sarmaz. Profiler, log toplama ve uzaktan enstrümantasyon
eklemez. Bu özellikler gerekiyorsa tam Glowroot agent kullanın. WebFlux route telemetrisi ayrı ve
isteğe bağlı bir artifact içinde yer alır. Böylece Servlet uygulamasına `spring-webflux` veya Reactor
Netty; WebFlux uygulamasına da agent üzerinden MVC ya da Servlet motoru eklenmez.

## Rust-Java REST Kurulumu

Uyumlu `4.6.0` framework sürümünü kullanın. Bu sürüm Glowroot native ABI `4` içerir. Native dosyanın
kaynak revision ve ABI bilgisi HTTP server başlamadan doğrulanır.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.6.0</version>
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
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.5.1.jar=collector=http://glowroot-collector:8181,agent-id=catalog::pod-1,application=catalog-api \
  -jar catalog-api.jar
```

## Spring Boot Kurulumu

### 1. Starter paketini ekleyin

`-javaagent` tek başına Spring HTTP telemetrisi oluşturmaz. Spring uygulamasında aşağıdaki starter
veya uygun MVC/WebFlux adaptörü bulunmalıdır. Bootstrap JAR ve native kütüphane mevcut olsa bile bu
paket yoksa Servlet endpoint toplamları üretilemez.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.5.1</version>
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

Başlangıçta şu tek seferlik mesajı kontrol edin. Adaptör değeri kullanılan sunucuya göre değişir:

```text
Java-Rust Glowroot HTTP telemetry active: adapter=tomcat-valve, transaction-type=Web
```

Bu mesaj yoksa Glowroot transaction ekranını henüz değerlendirmeyin. Önce starter bağımlılığını,
`reactor.glowroot.enabled=true` ve `reactor.glowroot.spring.enabled=true` ayarlarını kontrol edin.

### 2. Glowroot ekranlarını kontrol edin

Trafik gönderdikten sonra bir export süresi bekleyin. Varsayılan ayarda bu süre en fazla 60 saniyedir.
**Transactions** ekranını açın ve transaction type olarak **Web** seçin. `/orders/{id}` gibi bir
endpoint, `/orders/*` adıyla görünür. HTTP metodu transaction adına eklenmez. Aynı tam istek toplamı
**Average**, **Percentile**, **Throughput** ve **Errors** ekranlarını besler.

`reactor.glowroot.http.sample-rate` bu ekranlardaki istek sayısını azaltmaz. Yalnız isteğe bağlı ve
sınırlı trace ayrıntısına hangi isteklerin girebileceğini belirler. Yalnız toplam telemetriye
ihtiyacınız varsa `trace.capacity=0` değerini koruyun.

Uygulama kendi embedded sunucusunu seçmeye devam eder. Auto-configuration, uygulamada zaten bulunan
sunucuyu algılar ve yalnız ilgili doğrudan adaptörü açar. Her sunucu API'si kendi adaptör modülünde
`provided` ve `optional` kalır. Bu nedenle ana starter sunucu motoru eklemez ve Spring Boot'un sunucu
seçimini değiştirmez.

| Uygulama runtime'ı | Agent HTTP yolu | Agent'ın eklediği server bağımlılığı | Desteklenen akış |
| --- | --- | --- | --- |
| Spring MVC + Tomcat | Doğrudan context Valve (`tomcat-valve`) | Yok | Senkron, asenkron, eşlenen durum, exception, 404, sınırlı route ve yavaş/hata telemetrisi |
| Spring MVC + Jetty | Doğrudan completion RequestLog (`jetty-request-log`) | Yok | Tomcat ile aynı özellik seti |
| Spring MVC + Undertow | Doğrudan exchange completion listener (`undertow-completion-listener`) | Yok | Tomcat ile aynı özellik seti |
| Spring WebFlux | Ayrı ve isteğe bağlı `WebFilter` | Yok | Aynı HTTP yaşam döngüsü; Reactor Netty ile test edildi |

Jetty kullanırken server seçimini uygulama POM'u yapar:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jetty</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.5.1</version>
</dependency>
```

Undertow için aynı Tomcat exclusion'ını koruyun ve Undertow starter'ını seçin:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-undertow</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.5.1</version>
</dependency>
```

Üç Servlet motoru da `/orders/{id}` gibi normalize edilmiş Spring route bilgisini yalnız kayıt
gerektiğinde okur. Doğrudan tamamlanma adaptörleri Java worker pool, uygulama sınıfı taraması veya
request wrapper eklemez. Bilinmeyen bir Servlet motoru için taşınabilir MVC interceptor yalnız
güvenlik fallback'i olarak kalır; Tomcat, Jetty ve Undertow bu fallback'i kullanmaz. Senkron ve
asenkron yanıtlar, eşlenen durum kodları, yakalanmayan hatalar ve bulunamayan route'lar aynı yaşam
döngüsü kontrolünden geçer.

### 2. WebFlux uygulamaları

Reaktif yüzeyi ayrı tutun. Bu adaptörü yalnız WebFlux uygulamasına ekleyin:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-webflux-adapter</artifactId>
  <version>0.5.1</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Adaptör ortak native Spring runtime'ını getirir. Reactor Netty'yi getirmez. Reaktif server seçimini
uygulamanın `spring-boot-starter-webflux` bağımlılığı yapar. Yalnız telemetri almak için
`spring-boot-starter-web` eklemeyin. MVC ile aynı `reactor.glowroot.*` ayarlarını kullanın.

### 3. Web olmayan uygulamalar

En küçük classpath için scheduler, Kafka worker, batch process, komut satırı uygulaması veya yalnız
veritabanı kullanan Spring Boot uygulamasına sadece web'den bağımsız runtime paketini ekleyin:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-runtime</artifactId>
  <version>0.5.1</version>
</dependency>
```

Kurum genelinde bütün Spring Boot servislerinde aynı dependency kullanılmak istenirse
`java-rust-glowroot-spring-boot-starter` paketi de çalışır. Bu pakette küçük adaptör JAR'ları bulunur.
Ancak sunucu API'leri `provided` ve `optional` olarak tanımlıdır. Web olmayan uygulamada hiçbir HTTP
adaptörü açılmaz. En küçük production classpath öncelikliyse yalnız runtime paketini seçin.

Sadece telemetri almak için `spring-webmvc`, WebFlux, Tomcat, Jetty, Undertow, Reactor Netty veya
Servlet API eklemeyin. Runtime auto-configuration web koşulu taşımaz ve web yüzeyi olmadan başlar.

```properties
spring.main.web-application-type=none
reactor.glowroot.enabled=true
reactor.glowroot.profile=jvm
reactor.glowroot.collector.address=http://glowroot-collector:8181
reactor.glowroot.agent.id=invoice-worker::pod-1
reactor.glowroot.application.name=invoice-worker
```

Process veya JVM ölçümleri için uygulama kodu yazmanız gerekmez. Spring application context'i
oluşturduğunda process-scoped tek bir `NativeTelemetry` bean'i paketli DLL/SO dosyasını yükler,
Glowroot ABI `4` değerini doğrular ve izole tek Rust exporter başlatır. Spring context kapatılınca
exporter da durur. `reactor.glowroot.enabled=false` olduğunda bean oluşturulmaz. Native kütüphane
yüklenmez; exporter thread'i, route tablosu, SQL tablosu, trace kuyruğu veya collector bağlantısı
ayrılmaz.

| Profil | Web sunucusu olmadan alınan veri |
| --- | --- |
| `micro` | Process RSS, işletim sistemi thread sayısı, exporter/reconnect/drop durumu |
| `jvm` | `micro` verisine ek olarak heap, non-heap, memory pool, GC sayısı ve GC süresi |
| `sql` | `micro` verisine ek olarak açıkça kaydedilen SQL süre, hata ve satır toplamları |
| `full` | JVM ölçümleri, açık SQL ölçümleri ve sınırlı hata stack bilgisi |
| `diagnostic` | `full` verisine ek olarak yetkili thread dump, heap histogram ve heap dump komutları |

Runtime; Kafka topic, scheduler job, batch step veya rastgele business metodu sınırını tahmin etmez.
Metotlara bytecode weaving uygulamaz. Bu nedenle `0.5.1` sürümünde Kafka, scheduler ve batch işlem
süreleri otomatik toplanmaz. Process ve JVM verisi otomatik alınır. Veritabanı süresini aşağıdaki
tekrar kullanılabilen `SqlStatement` API'si ile açıkça kaydedebilirsiniz. Böylece hot path
öngörülebilir kalır ve framework'e özel ağır bir Java agent katmanı oluşmaz.

| Uygulama türü | Önerilen profil | Otomatik alınan veri | Uygulamada açıkça yapılacak işlem |
| --- | --- | --- | --- |
| Kafka consumer veya producer | Normalde `micro`; olay sırasında geçici `jvm` | Process ve exporter durumu; `jvm` profilinde JVM/GC | Topic veya mesaj işleme süresi otomatik alınmaz |
| Scheduler veya Spring Batch | Normalde `micro`; inceleme sırasında `jvm` veya `full` | Process, exporter durumu ve seçilen JVM/GC ölçümleri | Job ve step süresi otomatik alınmaz |
| Veritabanı worker'ı | Normalde `micro`; inceleme sırasında `sql` veya `full` | Process ve isteğe bağlı JVM/GC | Seçilen repository işlemleri için tekrar kullanılan `SqlStatement` tanımlanır |
| Komut satırı veya arka plan servisi | `micro` | Process ve exporter durumu | HTTP transaction yoktur; yalnız gerekli sınırlı domain metric'leri açıkça eklenir |

`reactor.glowroot.spring.enabled=false`, Spring HTTP telemetrisini kapatır. Process/JVM/SQL
telemetrisi çalışmaya devam eder. Native runtime'ı ve exporter thread'ini tamamen kapatmak için
`reactor.glowroot.enabled=false` kullanın.

### 4. İsteğe bağlı erken başlangıç bootstrap'ı

Deployment standardınız `-javaagent` bekliyorsa veya process başlangıç bilgisini Spring'den önce
almak istiyorsanız bootstrap paketini de ekleyin:

Bootstrap telemetri runtime'ı değildir. Uygulamada `java-rust-glowroot-spring-runtime` veya Spring
Boot starter bulunmaya devam etmelidir. Bootstrap yalnız erken JVM argümanlarını ve process başlangıç
bilgisini aynı runtime'a aktarır. Tek başına native kütüphane yüklemez ve veri göndermez.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-agent</artifactId>
  <version>0.5.1</version>
  <scope>runtime</scope>
</dependency>
```

Bootstrap JAR'ını executable Spring Boot JAR'ın dışında tutun. JVM'e dosya yolunu verin:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.5.1.jar=collector=http://glowroot-collector:8181,agent-id=orders::pod-1,application=orders-api,http-sample-rate=256,trace-capacity=0 \
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

## Linux Container Uyumluluğu

`0.5.1` sürümü, `glibc 2.17` veya daha yeni Linux x64 image'larını destekler. Release build'i sabit
bir GLIBC sembol sınırıyla üretilir. Yayından önce Debian Stretch (`glibc 2.24`) ve Debian Bookworm
(`glibc 2.36`) içinde yüklenir. Bu yalnız build zamanı uyumluluk değişikliğidir. Runtime'a yeni
thread, allocation veya JNI çağrısı eklemez.

Yalnız JRE builder image'ını değil, **son uygulama image'ını** kontrol edin:

```bash
docker run --rm --entrypoint sh YOUR_IMAGE -lc 'getconf GNU_LIBC_VERSION'
docker run --rm --entrypoint sh YOUR_IMAGE -lc \
  'ldd /u01/applications/nmc-store-common/glowroot/librust_glowroot_agent.so'
```

### Özel dependency katmanları ve ortak PVC kullanımı

Image `BOOT-INF/lib` dizinini siliyor ve `BOOT-INF/classpath.idx` dosyasını ortak bir `NEW_BOOT-INF`
dizinine yönlendiriyorsa POM'a starter eklemek tek başına yeterli değildir. Son pod içinde hem native
kütüphane hem de Java runtime JAR'ları bulunmalıdır. Yalnız `librust_glowroot_agent.so` dosyasını
güncellerseniz Spring auto-configuration sınıflarını bulamaz. Servis normal şekilde açılır, ancak
telemetri göndermez.

Jetty kullanan `0.5.1` uygulamasında UI testinden önce çalışan pod'u kontrol edin:

```bash
APP_HOME=/u01/applications/nmc-store-common

grep 'java-rust-glowroot' "$APP_HOME/BOOT-INF/classpath.idx"
find "$APP_HOME" -type f -name 'java-rust-glowroot-*.jar' -print
grep -F 'librust_glowroot_agent.so' /proc/1/maps
```

İlk iki komut `0.5.1` starter, runtime ve Jetty adaptörünü göstermelidir. `/proc/1/maps` satırı yalnız
Java native kütüphaneyi yükledikten sonra görünür. Bu nedenle özel dependency image'ını veya ortak
PVC'yi uygulamanın Maven dependency katmanıyla aynı anda güncelleyin.

Repo, son image için hata durumunda build'i durduran bir kontrol de sunar:

```bash
scripts/verify-container-runtime.sh \
  /u01/applications/nmc-store-common 0.5.1 jetty \
  /u01/applications/nmc-store-common/glowroot/librust_glowroot_agent.so
```

Uygulama açılırken iki satırı da arayın:

```text
Java-Rust Glowroot native telemetry active: abi=4, profile=diagnostic, application=nmc-store-common
Java-Rust Glowroot HTTP telemetry active: adapter=jetty-request-log, transaction-type=Web
```

İki satır da yoksa starter/runtime JAR'ı aktif değildir. Yalnız ilk satır varsa web adaptörünü kontrol
edin. İki satır da var fakat Central boşsa 60 saniyelik ilk gönderim aralığından sonra
`diagnosticsJson()` içindeki `connected`, `export_success`, `export_failure` ve `last_error_code`
alanlarını inceleyin.

Bookworm olarak tanımlanan bir image `GLIBC_2.25 not found` hatası veriyorsa çalışan son image,
gösterilen Bookworm dosya sistemi değildir. Bookworm GLIBC `2.36` sağlar. Deployment'taki image
digest'ini, yeniden kullanılan custom image tag'lerini ve son `FROM` satırını kontrol edin.

Uygulamanın iki aşamasında da aynı ve değiştirilmeyen custom JRE image'ını kullanın:

```dockerfile
ARG JRE_IMAGE=zenia.azurecr.io/example/custom-jre21:1.1.1

FROM ${JRE_IMAGE} AS builder
# Uygulama katmanlarını burada çıkarın.

FROM ${JRE_IMAGE}
ARG APP_HOME=/u01/applications/nmc-store-common
WORKDIR ${APP_HOME}

COPY glowroot/librust_glowroot_agent.so ${APP_HOME}/glowroot/
RUN getconf GNU_LIBC_VERSION \
 && ldd ${APP_HOME}/glowroot/librust_glowroot_agent.so \
 && ! ldd ${APP_HOME}/glowroot/librust_glowroot_agent.so | grep -q 'not found'
```

Builder aşamasında `1.0.0`, final aşamasında `1.1.0` gibi farklı custom JRE tag'leri kullanmayın.
Builder geçse bile production farklı bir libc ile çalışabilir. Registry destekliyorsa değiştirilemez
image digest'i kullanın.

## Ayarlar

Öncelik sırası şöyledir: JVM `-D` property, `-javaagent` argümanı, environment variable, uygulama
property'si ve varsayılan değer. Environment key için property adını büyük harfe çevirin. Nokta ve
tire yerine alt çizgi kullanın. Örnek: `reactor.glowroot.max-export-bytes`,
`REACTOR_GLOWROOT_MAX_EXPORT_BYTES` olur.

| Property | Varsayılan | Geçerli değer | Görevi |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Sınırlı telemetri runtime'ını açar |
| `reactor.glowroot.profile` | `micro` | `micro`, `jvm`, `sql`, `full`, `diagnostic` | Başlangıç profilini seçer; çalışma sırasında değiştirilebilir |
| `reactor.glowroot.profile.release-timeout-ms` | `5000` | 100-60000 | Eski profile ait kaynakların bırakılması için beklenecek en uzun süre |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | düz HTTP URL | Glowroot Central gRPC over HTTP/2 adresi |
| `reactor.glowroot.agent.id` | boş | 1-256 byte | Zorunlu ve benzersiz agent/rollup kimliği |
| `reactor.glowroot.application.name` | `reactor.application.name`, ardından `spring.application.name` | 1-128 byte | Glowroot ekranındaki isim |
| `reactor.glowroot.hostname` | `HOSTNAME` | en fazla 255 byte | Host veya pod etiketi |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000; 60000 katı | Toplam veri gönderim aralığı |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 bağlantı timeout'u |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Collector isteğinin toplam timeout'u |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Trace açıksa yavaş çağrı sınırı |
| `reactor.glowroot.http.sample-rate` | `256` | 1-1024 arasında ikinin kuvveti | İsteğe bağlı HTTP trace ayrıntısını örnekler; endpoint sayısı, süre, yüzdelik değer, throughput ve hatalar tam kalır |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Sınırlı trace kuyruğu; `0` kuyruk ayırmaz |
| `reactor.glowroot.sql.capacity` | `16` | 0-32 | Yalnız `sql`, `full` veya `diagnostic` profilinde ayrılan en fazla SQL slotu |
| `reactor.glowroot.error.trace.capacity` | `8` | 0-16 | Profili açıkken bellekte tutulacak en fazla ayrıntılı hata kaydı |
| `reactor.glowroot.error.max-frames` | `24` | 0-32 | Bir hata için alınacak en fazla stack frame sayısı |
| `reactor.glowroot.error.max-bytes` | `4096` | 256-8192 | Bir hatanın en fazla UTF-8 ayrıntı boyutu |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Toplam sınırlı endpoint slotu; bir slot fazla çeşitliliği `<route-limit-exceeded>` altında toplar ve istekler sessizce kaybolmaz |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Tek collector mesajının en büyük boyutu |
| `reactor.glowroot.spring.enabled` | `true` | boolean | İsteğe bağlı Spring HTTP adaptörünü açar; native çekirdeği `reactor.glowroot.enabled` yönetir |
| `reactor.glowroot.spring.order` | `-2147483548` | integer | Taşınabilir MVC fallback veya isteğe bağlı WebFlux filter sırası; doğrudan Tomcat, Jetty ve Undertow adaptörleri sıralama gerektirmez |
| `reactor.glowroot.native.extract-dir` | kullanıcı home dizini | dizin | Spring standalone native çıkarma dizini |
| `reactor.glowroot.native.path` | boş | mevcut DLL/SO yolu | Geliştirme ve staging override değeri; production'da paketli binary kullanın |

Sınır dışındaki değerler uygulamanın başlamasını engeller. Agent bellek sınırını büyüten bir property
yoktur.

## Çalışma Profilleri

Uygulamayı `micro` ile başlatın. Daha fazla bilgi gerektiğinde yalnız bir pod'un profilini yükseltin.
İnceleme bitince tekrar `micro` profiline dönün.

| Profil | Sürekli process ölçümlerine ve varsa HTTP/Dubbo/Redis toplamlarına eklenen veri | Uygun kullanım |
| --- | --- | --- |
| `micro` | Ek veri yoktur | Normal production trafiği ve en düşük sabit bellek kullanımı |
| `jvm` | Heap, non-heap, memory pool, GC sayısı ve GC süresi | Kısa JVM bellek veya GC incelemesi |
| `sql` | Açıkça işaretlenen sınırlı SQL süreleri ve ayrıntılı hata stack bilgisi | JVM ölçümleri olmadan veritabanı gecikmesi incelemesi |
| `full` | `jvm`, `sql` ve hata stack bilgisi | Tek pod üzerinde kısa olay incelemesi |
| `diagnostic` | `full` ve iki komutluk tanılama kuyruğu | Yetkili thread dump, heap histogram veya heap dump işlemi |

`sql` profili bilinçli olarak açık kullanım ister. Agent `DataSource` veya JDBC nesnelerini proxy ile
sarmaz. Driver sınıflarına bytecode weaving uygulamaz. Statement tanımını bir kez oluşturun ve tekrar
kullanın. Süre ölçümü çağrı başına observation nesnesi oluşturmaz:

```java
private final NativeTelemetry.SqlStatement findCustomer;

CustomerRepository(NativeTelemetry telemetry) {
    this.findCustomer = telemetry.sqlStatement(
            "customer.find",
            "select id, name from customer where id = ?"
    );
}

Customer find(long id) {
    long started = findCustomer.start();
    try {
        Customer customer = queryCustomer(id);
        findCustomer.recordSuccess(started, customer == null ? 0 : 1);
        return customer;
    } catch (RuntimeException error) {
        findCustomer.recordFailure(started, error);
        throw error;
    }
}
```

SQL metni ilk slot kaydında normalize edilir ve sınırlanır. Parametre değerleri gönderilmez.
Statement tanımını singleton service veya repository içinde tutun. Her request için yeniden
oluşturmayın.

Rust-Java REST aynı yaşam döngüsünü Spring starter olmadan kullanır:

```java
private static final GlowrootTelemetry.SqlStatement FIND_CUSTOMER =
        GlowrootTelemetry.sql("customer.find", "select id, name from customer where id = ?");

long started = FIND_CUSTOMER.start();
try {
    Customer customer = repository.find(id);
    FIND_CUSTOMER.recordSuccess(started, customer == null ? 0 : 1);
    return customer;
} catch (RuntimeException error) {
    FIND_CUSTOMER.recordFailure(started, error);
    throw error;
}
```

## Uygulamayı Yeniden Başlatmadan Profil Değiştirme

Rust-Java REST içinde yerleşik kontrol API'sini kullanın:

```java
import com.reactor.rust.telemetry.GlowrootTelemetry;
import com.reactor.rust.telemetry.TelemetryProfile;
import java.time.Duration;

GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(5));
// Sınırlı bir süre olay verisi toplayın.
GlowrootTelemetry.restoreConfiguredProfile();
```

Spring Boot uygulamasında mevcut process-scoped bean'i inject edin:

```java
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryProfile;
import java.time.Duration;

telemetry.updateProfile(TelemetryProfile.JVM, Duration.ofSeconds(5));
// Kısa bir JVM ölçüm aralığı bekleyin.
telemetry.restoreConfiguredProfile(Duration.ofSeconds(5));
```

`configuredProfile()` başlangıçta `reactor.glowroot.profile` ile seçilen değeri döndürür. Böylece
operasyon kodunda `micro` değerini sabitlemeniz gerekmez. Servisin temel profili daha sonra değişse
bile aynı restore çağrısı doğru profile döner.

Profil değiştirme işlemini public endpoint üzerinden açmayın. API'yi kimlik doğrulaması olan bir
operasyon endpoint'inden veya iç kontrol komutundan çağırın. Starter ve REST framework kendiliğinden
yönetim endpoint'i açmaz. Profil geçişi kontrol düzlemi işlemidir. Her request'te veya her health
check örneğinde profil değiştirmeyin.

SQL slot kimliği ayrı ve pozitif bir 32-bit alan kullanır. Nesil bölümü 25 bittir. Eski bir raw slot,
normal process ömründe yeni SQL tanımıyla eşleşmez. `33 milyon` üzerindeki state-shape geçişinde
sessiz wrap yerine kontrollü hata oluşur. Profilleri yine her request'te veya periyodik örnekleyici
gibi değil, olay inceleme kontrolü olarak kullanın.

`switchTo` veya `updateProfile` metodu döndüğünde aşağıdaki adımlar tamamlanmıştır:

1. Eski profil yeni SQL, hata veya tanılama işi kabul etmez.
2. Eski native kuyrukları, SQL slotları ve profile ait export verisini kullanan referans kalmaz.
3. İlgili Rust bellek alanları, devam eden sınırlı collector isteği tamamlandıktan veya zaman aşımına uğradıktan sonra bırakılmıştır.
4. Rust'ın sahip olduğu ve artık gerekmeyen bütün JNI MXBean global referansları bırakılmıştır.
5. Linux glibc ortamında izole agent thread'i `malloc_trim(0)` çağrısını tamamlamıştır.

Trim çağrısı Hyper veya application worker'ı üzerinde çalışmaz; ancak glibc trim işlemi process
genelindedir. Profil değişimini yalnız seyrek bir kontrol düzlemi işlemi olarak kullanın. Profil
değişiminden sonra process RSS değerinin düşmesi, tek başına agent'a ait kazanç olarak yorumlanamaz.
Agent bellek etkisini telemetri kapalı/açık taze process A/B testiyle doğrulayın.

Kaynak bırakma işlemi `reactor.glowroot.profile.release-timeout-ms` süresinde tamamlanmazsa çağrı
hata verir. Transition kimliği tanılama çıktısında kalır. Sonraki kontrol çağrısı aynı işlemin
tamamlanmasını bekler. Eski state beklerken exporter sessizce durdurulmaz. Dump işlemi devam ederken
`diagnostic` profilinden aşağı geçiş reddedilir.

`micro`, temel exporter'ı, endpoint toplamlarını ve collector bağlantısını korur. Çünkü telemetri
hâlâ açıktır. Hiç telemetri state'i istemiyorsanız uygulamayı telemetri kapalı olarak başlatın.

OpenJ9, JVM yönetim API'leri ilk kez kullanıldıktan sonra system classloader metadata'sını veya
sonradan açtığı `Finalizer thread` kaynağını unload edemez. Agent kendi referanslarını, native
kuyruklarını ve buffer'larını bırakır. Buna rağmen küçük ve tek seferlik JVM/JIT ısınma kalıntısı
görülebilir. Bu değer profil döngüleriyle büyümemelidir. Linux allocator sayfaları hemen iade
edebilir. Windows tarafında sahiplik bırakılır; bütün process'in working set'ini zorla boşaltmak
yerine resident sayfaların iadesi OS allocator'a bırakılır.

## Ayar Reçeteleri

| Senaryo | Profil | `sample-rate` | `trace.capacity` | Öneri |
| --- | --- | ---: | ---: | --- |
| Yüksek trafikli production API | `micro` | `256` | `0` | Trace kuyruğu olmadan tam dashboard toplamları |
| Düşük trafikli, yalnız toplam telemetri | `micro` | `256` | `0` | Sayım ve latency zaten tamdır; örnekleme ayarı değişmez |
| Kısa trace incelemesi | `micro` | `8` veya `1` | `8` veya `16` | Bir pod üzerinde daha fazla isteğe bağlı trace toplayın; sonra düşük maliyetli startup ayarına dönün |
| JVM veya GC incelemesi | `jvm` | değişmez | `0` | Bir pod'u yükseltin, birkaç gönderim aralığı bekleyin, sonra `micro`ya dönün |
| SQL gecikmesi incelemesi | `sql` | değişmez | `0` | Yalnız seçilen repository statement'larını işaretleyin |
| Kısa olay incelemesi | `full` | değişmez | varsayılan `0` | JVM, SQL ve hata state'i dinamiktir; tek pod'da kullanın ve sonra geri alın |
| Yetkili dump işlemi | `diagnostic` | değişmez | değişmez | Bir komut çalıştırın, tamamlandığını doğrulayın, sonra `micro`ya dönün |

Eksik endpoint toplamını sample rate değerini `1` yaparak çözmeye çalışmayın. Endpoint toplamları
zaten tamdır. Sipariş, ödeme veya domain hataları için ayrıca açık business metric üretin.

`http.sample-rate` ve `trace.capacity` startup ayarıdır. Profil geçişi bu değerlerin kapasitesini
değiştirmez. Uygulamayı `trace.capacity=16` ile başlatırsanız sınırlı HTTP trace kuyruğu `micro`
profilinde de ayrılmış kalır. Profil düşürülürken en sıkı bellek iadesini istiyorsanız bu değeri `0`
tutun. Profile ait SQL, hata, JVM ve tanılama state'i yine dinamik olarak ayrılır ve bırakılır.

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
`pending_error_captures`, `queued_error_traces`, `dropped_error_traces`, `dropped_routes`,
`reconnects` ve `last_error_code` alanlarını izleyin. `pending_error_captures`, izole Rust exporter'ı
bekleyen hata sayısıdır. Bu sayı tanımlı sınırı aşmamalı ve trafik sıçramasından sonra sıfıra
dönmelidir. Profil geçişinde ayrıca
`active_profile`, `active_profile_memory_ceiling_bytes`, `retired_profile_memory_ceiling_bytes`,
`jvm_probe_registered`, `jvm_probe_owned_global_refs`, `profile_release_pending`, `profile_released_transition`,
`profile_release_timeouts`, `profile_last_release_micros`, `profile_max_release_micros` ve
`profile_trim_succeeded` alanlarını kontrol edin.

JVM ölçümü veya tanılama içermeyen profile döndüğünüzde hem `jvm_probe_registered=false` hem de
`jvm_probe_owned_global_refs=0` görülmelidir. Rust, tanılama çıktısını hedef klasörde geçici dosyaya
yazar ve yalnız başarıdan sonra yayınlar. Heap histogram ve heap dump yine JVM içinde tek seferlik
büyük bellek kullanabilir. Bu işlemleri trafik yoğunken veya periyodik job olarak değil, tek pod
üzerinde kontrollü biçimde çalıştırın.

## Performans Sözleşmesi

`micro` ayarı, agent'a ait state ve native özellik sayfaları için deterministik `1 MiB` sınırı
uygular. Rust-Java REST ve Spring, telemetri açıkken `256 KiB` stack kullanan tek ve izole
current-thread Tokio exporter çalıştırır. Hyper worker'larını, uygulama executor'larını veya Spring
request thread'lerini kullanmaz. Embedded REST ayrıca request capture durumunu tek bir 32-bit
değerde tutar. Endpoint toplamları tam kalır; kullanıcının ayarladığı sampling oranı yalnız isteğe
bağlı trace ayrıntısını kontrol eder.
Strict Spring gate, starter'ı property veya ortam değişkeniyle açar. İsteğe bağlı `-javaagent`
bootstrap yalnız kurulum kolaylığı sağlar ve ayrı doğrulanır. Transformer kurulmasa bile JVM
instrumentation sistemini başlatmak OpenJ9'a ait ek bellek oluşturur.

Release gate aynı image içinde telemetri kapalı/açık eşlenmiş ve sırası değiştirilmiş koşular yapar.
Her endpoint ve concurrency hücresi şu sınırları geçmelidir:

- başarılı HTTP 200 RPS kaybı en fazla `%2`;
- p99 artışı en fazla `%10`;
- stable release hücrelerinin tamamında non-2xx artışı `0` yüzde puanı olmalıdır. Baseline ve
  candidate toplam ve en yüksek hata oranları `%0,05` altında kalmalıdır;
- İki çalışma şeklinde de agent'a ait ek thread sayısı en fazla `1`.

Stable release aynı request başına telemetri matrisini Spring Boot ve Rust-Java REST için ayrı
çalıştırır. İki matris de küçük JSON ve önceden hazırlanmış raw JSON endpoint'lerini c64/c256
seviyesinde ölçer. Bu yollar yüksek istek hızına ulaştığı için agent'ın sabit maliyetini serializer
ağırlıklı bir endpoint'ten daha net gösterir. Dynamic heavy JSON functional route smoke içinde yine
çağrılır. İsteğe bağlı `extended` workflow'u heavy JSON c64/c128 ölçümünü ekler ve altı çiftin
tamamını çalıştırır. Bu sonuç stress kanıtıdır; stable package yayınını onaylayan gate değildir.
Release üç bağımsız çiftle başlar. Daha sıkı erken PASS sınırı sağlanmazsa otomatik olarak altı
çifte devam eder. RPS, p99 ve startup için önce her çiftin farkı bulunur, sonra medyan hesaplanır.
Non-2xx kararı eşleştirilmiş medyanı, istek sayısıyla ağırlıklandırılmış toplamı, en
yüksek hata oranını ve mutlak `%0,05` sınırını birlikte kullanır. Normal release matrisindeki bütün
hücrelerde sıfır artış kuralı uygulanır. `0,02` yüzde puanlık sınırlı marj yalnız heavy JSON manuel
olarak embedded REST c256+ doygunluk bölgesinde ölçülürse geçerlidir. Doygun bir koşudaki tek fark raporda görünür; genel hata
kararının yerine geçmez. Aynı tam
yükten sonra eşit process yaşında ölçülen RSS ve cgroup medyan farkları en fazla
`micro` için `+3 MiB` olabilir. İki runtime da yalnız tek sınırlı exporter thread'i ekleyebilir.
`jvm`, `sql`, `full` ve `diagnostic` profilleri ayrı ve geçici profil gate'leriyle ölçülür. OpenJ9
yönetim ve hata sınıfları tek seferlik JVM ısınma state'i oluşturabilir. REST wire uyumu, collector
kapalıyken fail-open ve opsiyonel bootstrap ayrıca zorunlu
olarak test edilir.

[Mimari ve Production Sınırı](docs/ARCHITECTURE.tr.md) belgesine bakın. Kullanıcıya açık değişiklikler
ve uyumluluk ayrıntıları için [0.5.1 sürüm notlarını](docs/releases/0.5.1.tr.md) okuyun. İç benchmark
araçları ve ham kanıt dosyaları bilinçli olarak public repoda tutulmaz.

## Uyumluluk

| Bileşen | Sürüm | Sözleşme |
| --- | ---: | --- |
| Java | `21` | Ana test JVM'i Semeru OpenJ9'dur |
| Rust-Java REST | `4.6.0` | REST ABI `29`, Glowroot ABI `4` |
| Agent bootstrap | `0.5.1` | Tek sınıf; iki desteklenen ortamda da çalışır |
| Spring Boot starter | `0.5.1` | Spring Boot `3.x`; web'den bağımsız çekirdek ve Tomcat, Jetty, Undertow için doğrudan tam yaşam döngüsü adaptörleri; sunucu motoru bağımlılığı yoktur |
| Spring WebFlux adaptörü | `0.5.1` | Ayrı ve isteğe bağlı `WebFilter`; Reactor Netty veya Servlet motoru bağımlılığı yoktur |
| Standalone native kaynak | `rust-spring v4.6.0` | Glowroot ABI `4`; temiz CI DLL/SO |
| Glowroot Central wire contract | upstream `0.14.8-beta.5-SNAPSHOT` checkout | Unary h2/protobuf uyumluluk gate'i |
| Native platform | Windows x64, Linux glibc x64 | Temiz CI build DLL/SO ve SHA-256 provenance |

Çalışma sırasında profil değiştirmek için yukarıdaki uyumlu REST ABI `29` ve Glowroot ABI `4`
ikilisi gerekir. Startup provenance kontrolü eski veya elle kopyalanmış native binary'yi reddeder.

DLL/SO dosyalarını sürümler arasında elle kopyalamayın. Framework, cache, Dubbo ve agent paketleri
uyumlu native ABI bilgisini startup sırasında doğrular.

## Build

```powershell
$env:JAVA_HOME = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
mvn -B -ntp clean verify
```

Maven reactor şu dosyaları üretir:

- `agent-bootstrap/target/java-rust-glowroot-agent-0.5.1.jar`
- `spring-runtime-core/target/java-rust-glowroot-spring-runtime-0.5.1.jar`
- `spring-mvc-adapter/target/java-rust-glowroot-spring-mvc-adapter-0.5.1.jar`
- `spring-tomcat-adapter/target/java-rust-glowroot-spring-tomcat-adapter-0.5.1.jar`
- `spring-jetty-adapter/target/java-rust-glowroot-spring-jetty-adapter-0.5.1.jar`
- `spring-undertow-adapter/target/java-rust-glowroot-spring-undertow-adapter-0.5.1.jar`
- `spring-boot-starter/target/java-rust-glowroot-spring-boot-starter-0.5.1.jar`
- `spring-webflux-adapter/target/java-rust-glowroot-spring-webflux-adapter-0.5.1.jar`

Native DLL/SO yalnız `native-provenance.properties` içinde yazan temiz `rust-spring` commit'inden
üretilir. `scripts/sync-native-artifacts.ps1`, tekrarlanabilir release build'inin parçası olduğu için
repoda kalır. İç yük üreticileri, ham benchmark kanıtları ve lokal runner ayarları public repo dışında
tutulur.
