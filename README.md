# Volkan Tarayıcı

Android tablet için hazırlanmış yalın GeckoView tarayıcısı ve tarayıcıya özel WireGuard VPN istemcisi.

## İlk sürüm

- Tek aktif sayfa ile düşük bellek kullanımı
- GeckoView 155 web motoru
- Geri, ileri, yenileme ve adres/arama alanı
- Standart WireGuard `.conf` profilini cihazdan içe aktarma
- VPN'i yalnızca `com.volkan.volkantarayici` trafiğine uygulama
- VPN açık/kapalı kontrolü
- Haricî GPT, API, sunucu veya ücretli uygulama bağımlılığı yok

VPN bağlantı profili gizlidir. Depoya yüklenmemeli, mesajla paylaşılmamalı ve yalnızca cihazdaki uygulama içine aktarılmalıdır.

## APK

`main` dalına gönderilen her değişiklik GitHub Actions üzerinden test APK'sı üretir. Çıktı, ilgili Actions çalışmasının `Artifacts` bölümünde `VolkanTarayici-test` adıyla görünür.
