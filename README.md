# XNotify 3.3

**An LSPosed module that shows your Xiaomi phone's notifications on your Xiaomi tablet, for every app, on the HyperOS 4 China ROM.**

[Türkçe aşağıda](#türkçe)

## Features
- ✅ **Every app:** HyperOS only syncs a handful of Chinese apps (WeChat, QQ…). XNotify lets the tablet pick any app installed on the phone, including Telegram, WhatsApp, Instagram, SMS and calls.
- ✅ **Receive screen on the tablet:** The CN tablet ROM hides it; XNotify opens it from Settings → Interconnectivity → Notifications.
- ✅ **App picker with icons:** The phone sends its app list and icons to the tablet. Use "Refresh from phone" after installing new apps.
- ✅ **Clear notifications:** Big app icon, with the source device and app name in the title.
- ✅ **Works while the phone is in use**, not only when it is locked.
- ✅ **Non-China Xiaomi accounts** (EU, US, UA…) on CN ROMs are supported.
- ✅ **No duplicates:** Apps installed on the tablet too are skipped.
- UI languages: English, Türkçe, Русский, Español, 简体中文

## Requirements
- Xiaomi phone and tablet on the HyperOS 4 China ROM (Android 17), signed in to the same Xiaomi account, Wi-Fi and Bluetooth on
- Root (KernelSU / SukiSU / Magisk) and LSPosed (API 102) on **both** devices
- Tested on Xiaomi 15 Ultra + Xiaomi Pad 8 Pro, and REDMI K90 Pro Max + Pad

## Install
1. Install XNotify on **both** the phone and the tablet and enable it in LSPosed (scope: `com.milink.service`, Interconnectivity).
2. Reboot both devices, or force-stop `com.milink.service`.
3. On the tablet: Settings → Interconnectivity → Notifications → Receive → your phone, then turn on the apps you want.

XNotify has no app icon or settings screen; the tablet's own notification screen controls everything.

## Source code and support
- Source: https://github.com/Leekin2/XNotify (GPL-3.0)
- Telegram channel: https://t.me/xioamifetiscisi
- Feedback and suggestions: [@emcleek](https://t.me/emcleek)

---

## Türkçe

**Xiaomi telefonunuzdaki bildirimleri, her uygulama için Xiaomi tabletinizde gösteren LSPosed modülü (HyperOS 4 Çin ROM).**

### Özellikler
- ✅ **Tüm uygulamalar:** HyperOS sadece birkaç Çin uygulamasını (WeChat, QQ…) aktarır. XNotify ile tablet, telefondaki her uygulamayı seçebilir: Telegram, WhatsApp, Instagram, SMS ve aramalar dahil.
- ✅ **Tablette Alma ekranı:** Çin tablet ROM'u bunu gizler; XNotify Ayarlar → Bağlantı → Bildirimler'den açar.
- ✅ **İkonlu uygulama seçici:** Telefon, uygulama listesini ve ikonlarını tablete gönderir. Yeni uygulama kurunca "Telefondan yenile"ye dokunun.
- ✅ **Net bildirimler:** Büyük uygulama ikonu, başlıkta kaynak cihaz ve uygulama adı.
- ✅ **Telefon kullanılırken de çalışır**, sadece kilitliyken değil.
- ✅ **Çin dışı Xiaomi hesapları** (AB, ABD, UA…) Çin ROM'unda desteklenir.
- ✅ **Çift bildirim yok:** Tablette de kurulu olan uygulamalar atlanır.

### Gereksinimler
- Aynı Xiaomi hesabına giriş yapılmış, HyperOS 4 Çin ROM'lu (Android 17) Xiaomi telefon ve tablet; Wi-Fi ve Bluetooth açık
- **İki cihazda da** root (KernelSU / SukiSU / Magisk) ve LSPosed (API 102)

### Kurulum
1. XNotify'ı telefona **ve** tablete kurun, LSPosed'da etkinleştirin (kapsam: `com.milink.service`).
2. İki cihazı yeniden başlatın.
3. Tablette: Ayarlar → Bağlantı → Bildirimler → Al → telefonunuz, istediğiniz uygulamaları açın.

Destek: Telegram kanalı https://t.me/xioamifetiscisi · Öneri ve geri bildirim [@emcleek](https://t.me/emcleek)
