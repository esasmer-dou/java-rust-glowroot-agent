# Sade Java İşlemleri

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Bu API 0.7.0 için hazırlandı. Henüz yayımlanmadı. [Yayın kontrolünü okuyun](releases/0.7.0.tr.md#yayın-kontrolü).
Spring veya HTTP sunucusu gerektirmez.
Native ABI 6 olarak kalır.
`java-rust-glowroot-spring-runtime` paketini kullanın.
Bu açık API için Spring bağımlılıkları zorunlu olarak uygulamaya taşınmaz.

```java
try (var telemetry = StandaloneTelemetry.startIfEnabled()) {
    var operation = telemetry.operation("Scheduler", "customer-refresh");
    long started = operation.start();
    try {
        boolean published = refreshCustomers();
        operation.complete(started, published);
    } catch (RuntimeException error) {
        operation.failure(started, error);
        throw error;
    }
}
```

Telemetry nesnesini uygulama boyunca açık tutun. Her istekte açıp kapatmayın.
İşlem adlarını başlangıçta bir kez kaydedin.
İsimlere müşteri numarası, SQL değeri veya payload koymayın.
`complete(started, false)` yeni exception üretmeden iş hatasını sayar.

| Ayar | Etkisi |
| --- | --- |
| REACTOR_GLOWROOT_ENABLED=false | Native agent başlamaz |
| REACTOR_GLOWROOT_ENABLED=true | Mevcut Rust exporter başlar |
| REACTOR_GLOWROOT_COLLECTOR_ADDRESS | Collector adresi |
| REACTOR_GLOWROOT_AGENT_ID | Uygulama/replica kimliği |

Cache writer için isteğe bağlı `java-rust-glowroot-cache-adapter` paketini ekleyin.
Bu paket normal HTTP uygulamasına otomatik eklenmez.
`com.reactor.glowroot.agent.cache.ProjectionTelemetry.wrap(telemetry, names, refresher)` kullanın.
Yalnız tanımlı projection'lar ölçülür.
Agent kapalıysa aynı refresher geri döner. Yeni executor açılmaz.
Rastgele metotlar otomatik izlenmez.
Export ve profil kaynakları mevcut izole Rust runtime'ında kalır.
Agent açıkken ek verinin maliyeti vardır. Seçtiğiniz profili ölçün.

## Cache Writer Yaşam Döngüsü

Writer'a `com.reactor:java-rust-glowroot-cache-adapter:0.7.0` ekleyin.
Uygulama `java-rust-cache:0.8.0` bağımlılığını açıkça kullanmaya devam eder.
Normal HTTP uygulaması bu adaptörü kendiliğinden almaz.

```java
import com.reactor.glowroot.agent.cache.ProjectionTelemetry;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;

ProjectionWriterApplication.runCache(
        "rest-sample-cache-writer.properties", "sample.writer",
        ProjectionTelemetry.writer(PostgresCustomerRepository::fromProperties, CustomerCacheMaterializer::new));
```

Başlangıç ve kapanışı mevcut writer yönetir. Projection adları mevcut ayarlardan alınır.
Agent kapalıysa aynı refresher kullanılır. Agent açıksa sabit adlar başlangıçta bir kez kaydedilir.
Snapshot yayını ve normal kilit atlaması başarılı işlem sayılır. Başarısız sonuç veya exception hata sayılır.
Adaptör aynı sonucu döndürür. Aynı exception'ı iletir. Yazmayı yeniden denemez.

`StandaloneTelemetry` sınıfının tam adı `com.reactor.glowroot.agent.runtime.StandaloneTelemetry` şeklindedir.
Plain Java uygulaması `com.reactor:java-rust-glowroot-spring-runtime:0.7.0` paketini kullanır.
Paket adında Spring bulunur. Ancak bu açık API Spring veya HTTP sunucusu başlatmaz.

Filtre zinciri açık normal Apache Dubbo `ServiceConfig` provider'da aynı yaşam döngüsünü açın.
`java-rust-glowroot-spring-dubbo-adapter:0.7.0` paketini ekleyin. SPI adaptörü işlemin tamamlanmasını izler.
Minimal provider sample ise SPI filtrelerini bilinçli olarak atlar. Bu sample için `full-provider,glowroot`
profillerini ve `java-rust-dubbo:0.8.1` bağlantısını kullanın. Yalnız seçilen işlemler başlangıçta izlemeye
alınır. Tüm Apache filtre zinciri açılmaz. İki kullanımda da Spring gerekmez.

İlk ayar olarak `REACTOR_GLOWROOT_PROFILE=micro` kullanın.
Payload, anahtar değeri, SQL değeri veya müşteri bilgisi toplanmaz.
Bu adaptörleri gerekmeyen uygulamalara eklemeyin.
