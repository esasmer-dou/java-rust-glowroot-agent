# Dedicated Performans Runner

[English](README.md) | [Türkçe](README.tr.md)

Production performans matrisi, bu işe ayrılmış bir Linux sunucuda çalışır. Local Docker ve WSL hızlı
geliştirme araçlarıdır. Bu ortamlardan alınan sonuçlar release onayı vermez.

## Sunucu Gereksinimleri

Her sunucuda yalnızca bir runner servisi çalıştırın.

| Kaynak | En düşük değer |
| --- | --- |
| İşletim sistemi | Native Ubuntu, Debian, RHEL/CentOS, Rocky veya AlmaLinux x64; WSL ve container kabul edilmez |
| CPU | 8 mantıksal CPU ve en az 4 fiziksel CPU grubu |
| Bellek | Sunucu ve Docker için en az 12 GiB |
| Disk | `/` üzerinde en az 20 GiB boş alan |
| Araçlar | Docker Engine, PowerShell, Maven ve Git |
| Java | Her job için `actions/setup-java` ile kurulan Semeru OpenJ9 21 |

Spring ve Rust-Java REST testlerini paralel çalıştırmak istiyorsanız iki ayrı fiziksel sunucu kullanın.
Aynı sunucuya iki runner servisi kurmayın. İki job aynı CPU cache, bellek bant genişliği ve Docker
kaynakları için yarışır. Böyle bir ölçüm latency ve RSS kanıtını geçersiz hale getirir.

## CPU Rollerini Ölçün

Boş görünen bir CPU, sanal veya paylaşımlı bir sunucuda her zaman kararlı değildir. Release ölçümünden
önce fiziksel CPU gruplarını ölçün:

```bash
pwsh ./scripts/performance-runner/calibrate-cpu-roles.ps1 \
  -OutputPath /tmp/reactor-cpu-calibration.json
```

Script, her fiziksel CPU grubunda dönüşümlü olarak 18 kısa SHA-256 testi çalıştırır. Uygulama için tam
bir SMT grubu seçer. Yük üretici için ikinci, collector için üçüncü bir fiziksel grup önerir. JSON
dosyasını sunucu kanıtıyla birlikte saklayın. Üç kararlı grup bulunamazsa gate eşiklerini gevşetmeyin.
Başka bir sunucu kullanın. Başarılı gruplar önce dağılım ve eğilime göre sıralanır. Çok küçük tepe
throughput farkı, kararlılığın önüne geçmez.

## Kurulum

Yönetici bilgisayarında kısa ömürlü kayıt token'ı üretin. Ekrana yazılan token'ı onaylı güvenli
kanalınız üzerinden Linux sunucuya aktarın:

```bash
gh api -X POST \
  repos/esasmer-dou/java-rust-glowroot-agent/actions/runners/registration-token \
  --jq .token
```

Dedicated Linux sunucuda repoyu indirin. Token'ın shell geçmişine yazılmaması için değeri gizli
olarak okuyun. Ardından kurulum komutunu çalıştırın:

```bash
read -rsp "Runner registration token: " RUNNER_TOKEN && echo
sudo ./scripts/performance-runner/install-native-linux.sh \
  --repository https://github.com/esasmer-dou/java-rust-glowroot-agent \
  --token "$RUNNER_TOKEN" \
  --name perf-linux-01 \
  --application-cpus 4,5 \
  --runner-cpu 0 \
  --collector-cpu 2
unset RUNNER_TOKEN
```

CPU değerlerini kalibrasyon script'inin çıktısından alın. Yukarıdaki sayılar yalnızca örnektir.
Runner daha önce kurulduysa yeniden kurmadan ölçülen değerleri uygulayın:

```bash
sudo ./scripts/performance-runner/configure-cpu-roles.sh \
  --application-cpus 4,5 \
  --runner-cpu 0 \
  --collector-cpu 2
```

Kurulum script'i GitHub runner dosyasının SHA-256 değerini doğrular. Maven `3.9.9` kurar. Debian ve
RHEL paket ailelerini destekler. Tek bir systemd servisi kurar. Servis
`reactor-performance-native-linux` etiketini taşır. Docker önceden kurulmuş ve çalışıyor olmalıdır.

## Doğrulama

Preflight kontrolünü doğrudan çalıştırabilirsiniz:

```bash
pwsh ./benchmark/performance_runner_preflight.ps1 \
  -RunnerClass reactor-performance-native-linux \
  -EvidencePath /tmp/reactor-runner-preflight.json
```

Daha sonra GitHub Actions üzerinden **Production Gate** workflow'unu başlatın. GitHub; orkestrasyonu,
exact-commit kontrolünü, artifact'leri, package yayınını ve release kararını yönetmeye devam eder.
Yalnız Spring ve REST performans job'ları self-hosted runner havuzunda çalışır.

Tam gate'ten önce **Production Runner Preflight** workflow'unu bir kez çalıştırın. Sunucu boyutu, CPU
politikası, swap, Docker, Java veya runner kaydı hatalıysa birkaç dakika içinde durur.

Release workflow'u hem job etiketlerini hem de preflight JSON dosyasını kontrol eder. Hosted runner,
WSL, container içinde çalışan runner, yetersiz sunucu, kullanılan swap veya ikinci runner listener ile
üretilen ölçüm release kanıtı sayılmaz.

Tam matris 16 sabit OpenJ9 ısınma turu kullanır. JVM hâlâ hızlanıyorsa en fazla sekiz ek ve dönüşümlü
doğrulama turu çalışır. RPS eğilimi ve dağılım eşikleri gevşetilmez. Bu sınırlı uzatma, JIT henüz
tamamlanmadan erken ret verilmesini önler. Kararsız bir uygulamanın gate'i geçmesini sağlamaz.

## Hızlı Local Kontrol

Push işleminden önce local Docker gate'ini çalıştırın:

```powershell
./benchmark/local_docker_quick_gate.ps1 -ApplicationKind rust-java-rest
```

Spring için `-ApplicationKind spring-boot` kullanın. İki uygulama için `-ApplicationKind all` verin.
Bu kısa gate; c64, small/raw JSON ve üç kısa eşleştirme kullanır. Büyük regresyonları hızlı bulur.
Sonuç `development-only` olarak işaretlenir ve tam release matrisinin yerine geçmez.
