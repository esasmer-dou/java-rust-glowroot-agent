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
- [İş Yükü Nerede Çalışır?](#iş-yükü-nerede-çalışır)
- [Rust-Java REST Kurulumu](#rust-java-rest-kurulumu)
- [Spring Boot MVC Kurulumu](#spring-boot-mvc-kurulumu)
- [Kubernetes](#kubernetes)
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
| Rust-Java REST `4.5.0` | Starter gerekmez | Framework içindeki `rust_hyper` kütüphanesini kullanır | Agent açıkken `1` |
| Spring Boot MVC `3.x` | `java-rust-glowroot-spring-boot-starter:0.3.0` | Küçük standalone agent kütüphanesini yükler | `1` |
| `-javaagent` standardı kullanan iki ortam | Tek sınıflı `java-rust-glowroot-agent:0.3.0` bootstrap | Yukarıdaki çalışma şekli değişmez | Aynı tek exporter kullanılır; bootstrap eklemez |

Bootstrap JAR yalnızca `-javaagent:key=value` değerlerini property'lere aktarır. İçinde tek sınıf
vardır. Native binary, transformer ve runtime dependency yoktur. Spring starter ayrı JAR olarak
kalır. Böylece Spring Boot executable JAR classloader sınırı bozulmaz.

Mevcut Glowroot collector, kullanıcı arayüzü ve veritabanı değişmez.

> **Uyumluluk sınırı:** Çalışma sırasında profil değiştirme özelliği REST native ABI `29` ve
> Glowroot ABI `3` gerektirir. Agent `0.3.0` ile Rust-Java REST `4.5.0` kullanın. Eski bir paketten
> DLL/SO kopyalamayın.

## Hangi Verileri Alırsınız?

| Veri | Davranış |
| --- | --- |
| HTTP çağrı sayısı ve süresi | Normalize edilmiş endpoint kalıbına göre sınırlı örnekleme |
| HTTP `5xx` | Başarılı çağrılar örneklense bile tam sayılır |
| Yavaş veya hatalı trace | İsteğe bağlı sınırlı kuyruk; varsayılan olarak kapalıdır |
| Rust-native Dubbo | Çağrı sayısı, süre ve hata toplamı |
| Rust-native Redis | Ayrı okuma/yazma sayısı, süre ve hata toplamı |
| Process ölçümleri | Her gönderim aralığında RSS ve thread sayısı |
| JVM ölçümleri | İsteğe bağlı heap, non-heap, memory pool, GC sayısı ve GC süresi |
| SQL toplamları | İsteğe bağlı ve açıkça işaretlenen sınırlı SQL süreleri; JDBC proxy veya bytecode weaving yoktur |
| Hata stack bilgisi | Hatalı Spring MVC ve Rust-Java REST çağrıları için isteğe bağlı sınırlı stack kaydı |
| İstek üzerine tanılama | Kısa süreli `diagnostic` profilinde thread dump, heap histogram veya heap dump |
| Gönderim sağlığı | Bağlantı, reconnect, hata, drop ve son hata sayaçları |

Request body, query değeri, header, SQL metni ve kişisel veri telemetriye kopyalanmaz.

## İş Yükü Nerede Çalışır?

Agent'ın ağır işleri Rust tarafındadır. Java yalnız Spring veya JVM içinde oluşan bilgiyi Rust'a
ileten sabit maliyetli sınırdır.

| Alan | Sorumlu | Yapılan iş |
| --- | --- | --- |
| Toplama ve gönderim | Rust | Sınırlı route/SQL state'i, örnekleme toplamları, kuyruklar, protobuf encode, h2, reconnect, timeout ve drop politikası |
| JVM ölçümleri | Rust | İzole exporter, JNI global referanslarını bulur ve sahiplenir; seçilen MXBean metotlarını çağırır, değerleri toplar ve gauge mesajını üretir |
| Tanılama | Rust | Komut kuyruğu, JNI çağrısı, sınırlı yürütme, dosya yazma, atomik yayın, hata temizliği ve sayaçlar Rust'ta kalır |
| Profil yaşam döngüsü | Rust | İsteğe bağlı state ayrılır, emekliye alınır, bırakılır ve gerekirse Hyper ile uygulama worker'larından uzakta trim edilir |
| Spring MVC sınırı | Java, yalnız sabit maliyetli geçiş | Eşleşen route, HTTP status, async tamamlanma, zaman ve gerekirse `Throwable` referansını Rust'a verir |
| JVM iç işlemleri | JVM, Rust tarafından çağrılır | Veri JVM içinde olduğu için MXBean ve dump API'leri JVM'de çalışır; Java yardımcı sınıfı, polling thread'i, cache veya direct-buffer callback'i yoktur |

Rust-Java REST HTTP telemetrisi doğrudan Rust server içinde kaydedilir. Spring MVC'de son eşleşen
controller route'u socket katmanından öğrenilemez. Küçük Spring adaptörünü de Rust'a taşımak, request
başında ek JNI çağrısı veya genel JVMTI/bytecode weaving gerektirir. İki seçenek de request maliyetini
artırır. Bu nedenle performans sözleşmesini korumak için bilinçli olarak kullanılmaz.

Bu agent, bütün Glowroot özelliklerinin küçük bir kopyası değildir. Rastgele Java metotlarını
işaretlemez. Her JDBC nesnesini proxy ile sarmaz. Profiler, log toplama ve uzaktan enstrümantasyon
eklemez. Bu özellikler gerekiyorsa tam Glowroot agent kullanın. `0.3.0` sürümü Spring WebFlux
desteklemez. Spring adaptörü Servlet MVC içindir.

## Rust-Java REST Kurulumu

Uyumlu `4.5.0` framework sürümünü kullanın. Bu sürüm Glowroot native ABI `3` içerir. Native dosyanın
kaynak revision ve ABI bilgisi HTTP server başlamadan doğrulanır.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.5.0</version>
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
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.3.0.jar=collector=http://glowroot-collector:8181,agent-id=catalog::pod-1,application=catalog-api \
  -jar catalog-api.jar
```

## Spring Boot MVC Kurulumu

### 1. Starter paketini ekleyin

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.3.0</version>
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

Spring auto-configuration tek bir MVC interceptor ekler. Spring'in seçtiği `/orders/{id}` gibi
normalize edilmiş endpoint kalıbını handler tamamlandıktan sonra okur. Servlet filter eklemez.
Uygulama sınıflarını taramaz ve Java worker pool oluşturmaz. Örneklenmeyen normal bir başarılı istek
için agent request nesnesi ayırmaz. Örneklenen, yavaş, hatalı ve async isteklerde Spring MVC'nin
completion akışını kullanır. Handler'ın ürettiği durum kodları ve yakalanmamış hatalar tam sayılır.

### 2. İsteğe bağlı erken başlangıç bootstrap'ı

Deployment standardınız `-javaagent` bekliyorsa veya process başlangıç bilgisini Spring'den önce
almak istiyorsanız bootstrap paketini de ekleyin:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-agent</artifactId>
  <version>0.3.0</version>
  <scope>runtime</scope>
</dependency>
```

Bootstrap JAR'ını executable Spring Boot JAR'ın dışında tutun. JVM'e dosya yolunu verin:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.3.0.jar=collector=http://glowroot-collector:8181,agent-id=orders::pod-1,application=orders-api,http-sample-rate=256,trace-capacity=0 \
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
| `reactor.glowroot.profile` | `micro` | `micro`, `jvm`, `sql`, `full`, `diagnostic` | Başlangıç profilini seçer; çalışma sırasında değiştirilebilir |
| `reactor.glowroot.profile.release-timeout-ms` | `5000` | 100-60000 | Eski profile ait kaynakların bırakılması için beklenecek en uzun süre |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | düz HTTP URL | Glowroot Central gRPC over HTTP/2 adresi |
| `reactor.glowroot.agent.id` | boş | 1-256 byte | Zorunlu ve benzersiz agent/rollup kimliği |
| `reactor.glowroot.application.name` | uygulama adı | 1-128 byte | Glowroot ekranındaki isim |
| `reactor.glowroot.hostname` | `HOSTNAME` | en fazla 255 byte | Host veya pod etiketi |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000; 60000 katı | Toplam veri gönderim aralığı |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 bağlantı timeout'u |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Collector isteğinin toplam timeout'u |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Trace açıksa yavaş çağrı sınırı |
| `reactor.glowroot.http.sample-rate` | `256` | 1-1024 arasında ikinin kuvveti | Başarılı HTTP örnekleme oranı; `5xx` tam sayılır |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Sınırlı trace kuyruğu; `0` kuyruk ayırmaz |
| `reactor.glowroot.sql.capacity` | `16` | 0-32 | Yalnız `sql`, `full` veya `diagnostic` profilinde ayrılan en fazla SQL slotu |
| `reactor.glowroot.error.trace.capacity` | `8` | 0-16 | Profili açıkken bellekte tutulacak en fazla ayrıntılı hata kaydı |
| `reactor.glowroot.error.max-frames` | `24` | 0-32 | Bir hata için alınacak en fazla stack frame sayısı |
| `reactor.glowroot.error.max-bytes` | `4096` | 256-8192 | Bir hatanın en fazla UTF-8 ayrıntı boyutu |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Bellekte tutulacak en fazla endpoint sayısı |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Tek collector mesajının en büyük boyutu |
| `reactor.glowroot.spring.enabled` | `true` | boolean | Starter varsa Spring MVC interceptor'ını açar |
| `reactor.glowroot.spring.order` | `-2147483548` | integer | MVC interceptor sırası; eski `interceptor-order` ve `filter-order` adları da çalışır |
| `reactor.glowroot.native.extract-dir` | kullanıcı home dizini | dizin | Spring standalone native çıkarma dizini |
| `reactor.glowroot.native.path` | boş | mevcut DLL/SO yolu | Geliştirme ve staging override değeri; production'da paketli binary kullanın |

Sınır dışındaki değerler uygulamanın başlamasını engeller. Agent bellek sınırını büyüten bir property
yoktur.

## Çalışma Profilleri

Uygulamayı `micro` ile başlatın. Daha fazla bilgi gerektiğinde yalnız bir pod'un profilini yükseltin.
İnceleme bitince tekrar `micro` profiline dönün.

| Profil | Sürekli açık HTTP/Dubbo/Redis toplamlarına eklenen veri | Uygun kullanım |
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
| Yüksek trafikli production API | `micro` | `256` | `0` | En düşük sabit ek yük; `5xx` yine tam sayılır |
| Düşük trafikli API | `micro` | `1` veya `8` | `0` | Trafik az olduğu için daha fazla örnek alınır |
| JVM veya GC incelemesi | `jvm` | değişmez | `0` | Bir pod'u yükseltin, birkaç gönderim aralığı bekleyin, sonra `micro`ya dönün |
| SQL gecikmesi incelemesi | `sql` | değişmez | `0` | Yalnız seçilen repository statement'larını işaretleyin |
| Kısa olay incelemesi | `full` | değişmez | varsayılan `0` | JVM, SQL ve hata state'i dinamiktir; tek pod'da kullanın ve sonra geri alın |
| Yetkili dump işlemi | `diagnostic` | değişmez | değişmez | Bir komut çalıştırın, tamamlandığını doğrulayın, sonra `micro`ya dönün |

Eksik business metric sorununu bütün yüksek trafikli pod'larda sample rate değerini `1` yaparak
çözmeyin. Sipariş, ödeme veya domain hataları için ayrıca açık business metric üretin.

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
`dropped_routes`, `reconnects` ve `last_error_code` alanlarını izleyin. Profil geçişinde ayrıca
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
request thread'lerini kullanmaz.
Strict Spring gate, starter'ı property veya ortam değişkeniyle açar. İsteğe bağlı `-javaagent`
bootstrap yalnız kurulum kolaylığı sağlar ve ayrı doğrulanır. Transformer kurulmasa bile JVM
instrumentation sistemini başlatmak OpenJ9'a ait ek bellek oluşturur.

Release gate aynı image içinde telemetri kapalı/açık eşlenmiş ve sırası değiştirilmiş koşular yapar.
Her endpoint ve concurrency hücresi şu sınırları geçmelidir:

- başarılı HTTP 200 RPS kaybı en fazla `%2`;
- p99 artışı en fazla `%10`;
- eşleştirilmiş medyanda ve istek sayısıyla ağırlıklandırılmış toplamda non-2xx artışı `0` yüzde puanı;
  adayın en yüksek hata oranı baseline'ın en yüksek oranını aşamaz;
- İki çalışma şeklinde de agent'a ait ek thread sayısı en fazla `1`.

Stable release bu tam matrisi Spring Boot ve Rust-Java REST için ayrı çalıştırır. İki matris de
small JSON, önceden hazırlanmış raw JSON ve dynamic heavy JSON endpoint'lerini c64/c256 seviyesinde
altı dengeli çiftle ölçer. RPS, p99 ve startup için önce her çiftin farkı bulunur, sonra medyan
hesaplanır. Non-2xx kararı eşleştirilmiş medyanı, istek sayısıyla ağırlıklandırılmış toplamı ve en
yüksek hata oranını birlikte kullanır. Doygun bir koşudaki tek fark raporda görünür; genel hata
kararının yerine geçmez. Aynı tam
yükten sonra eşit process yaşında ölçülen RSS ve cgroup medyan farkları en fazla
`micro` için `+3 MiB` olabilir. İki runtime da yalnız tek sınırlı exporter thread'i ekleyebilir.
`jvm`, `sql`, `full` ve `diagnostic` profilleri ayrı ve geçici profil gate'leriyle ölçülür. OpenJ9
yönetim ve hata sınıfları tek seferlik JVM ısınma state'i oluşturabilir. REST wire uyumu, collector
kapalıyken fail-open ve opsiyonel bootstrap ayrıca zorunlu
olarak test edilir.

[Doğrulama Kanıtı](docs/VALIDATION.tr.md),
[Mimari ve Production Sınırı](docs/ARCHITECTURE.tr.md) ve
[Benchmark Rehberi](benchmark/README.md) belgelerine bakın. Kullanıcıya açık değişiklikler ve
uyumluluk ayrıntıları için [0.3.0 sürüm notlarını](docs/releases/0.3.0.tr.md) okuyun.

## Uyumluluk

| Bileşen | Sürüm | Sözleşme |
| --- | ---: | --- |
| Java | `21` | Ana test JVM'i Semeru OpenJ9'dur |
| Rust-Java REST | `4.5.0` | REST ABI `29`, Glowroot ABI `3` |
| Agent bootstrap | `0.3.0` | Tek sınıf; iki desteklenen ortamda da çalışır |
| Spring Boot starter | `0.3.0` | Spring Boot `3.x`, Servlet MVC |
| Standalone native kaynak | `rust-spring v4.5.0` | Glowroot ABI `3`; temiz CI DLL/SO |
| Glowroot Central wire contract | upstream `0.14.8-beta.5-SNAPSHOT` checkout | Unary h2/protobuf uyumluluk gate'i |
| Native platform | Windows x64, Linux glibc x64 | Temiz CI build DLL/SO ve SHA-256 provenance |

Çalışma sırasında profil değiştirmek için yukarıdaki uyumlu REST ABI `29` ve Glowroot ABI `3`
ikilisi gerekir. Startup provenance kontrolü eski veya elle kopyalanmış native binary'yi reddeder.

DLL/SO dosyalarını sürümler arasında elle kopyalamayın. Framework, cache, Dubbo ve agent paketleri
uyumlu native ABI bilgisini startup sırasında doğrular.

## Build

```powershell
$env:JAVA_HOME = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
mvn -B -ntp clean verify
```

Maven reactor şu dosyaları üretir:

- `agent-bootstrap/target/java-rust-glowroot-agent-0.3.0.jar`
- `spring-boot-starter/target/java-rust-glowroot-spring-boot-starter-0.3.0.jar`

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
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 8 `
  -MaxWarmupRpsSpreadPercent 8 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Embedded REST matrisini çalıştırmak için aynı komuta şu parametreleri ekleyin:

```powershell
-ApplicationKind rust-java-rest `
-RequiredRestVersion "4.5.0" `
-RequiredRestNativeAbi 29 `
-MemoryLimit "128m" `
-AllowedThreadDelta 1
```

Tam kopyala-yapıştır komutları için [Doğrulama Kanıtı](docs/VALIDATION.tr.md) belgesine bakın.

Mock collector yalnız test içindir. Glowroot Central yerine production ortamına kurmayın.
