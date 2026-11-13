<p align="center">
  <img src="docs/icon.png" width="128" alt="T9 KlavyeTR ikonu">
</p>

<h1 align="center">T9 KlavyeTR</h1>

<p align="center">
  <b>Daha az dokun, daha hızlı yaz.</b><br>
  Hızlı, pratik ve modern Türkçe T9 akıllı klavye (Android)
</p>

---

## Özellikler

- ⚡ **Akıllı Türkçe tahmin:** 50.000+ kelimelik konuşma dili sözlüğü ve gelişmiş Türkçe ek motoru.
- 🎯 **Hızlı ve pratik:** Az tuşla çok kelime, tek elle rahat kullanım.
- 📖 **Kullanıcı Sözlüğü:** Özel kelimelerinizi ekleyin, düzenleyin ve dilediğiniz gibi yönetin.
- 🎙️ **Sesle yazma:** Boşluk tuşuna uzun basarak sesle Türkçe mesaj yazın.
- 🎨 **Koyu ve açık tema:** Menüden tek dokunuşla gece/gündüz teması arasında geçiş.
- 📋 **Pano yönetimi:** Pano geçmişi kartları; kart ekleme, düzenleme, kilitleme (silinmesin, hep üstte kalsın) ve silme.
- ⌨️ **İmleç tuşları:** Hassas imleç hareketi için ayrı tuşlar.

## Kurulum

1. Bu depoyu klonlayın:
   ```bash
   git clone https://github.com/hakankaya123/T9_Turkce_klavye.git
   ```
2. Projeyi **Android Studio** ile açın.
3. Gradle senkronizasyonu bitince cihazınızda veya emülatörde çalıştırın (**Run ▶**).

## Klavyeyi Kullanmaya Başlama

Uygulamayı açtığınızda iki adım göreceksiniz:

1. **Klavyeyi Etkinleştir:** Ayarlar'daki klavye listesinde *T9 KlavyeTR* seçeneğini açın.
2. **Varsayılan Klavye Seç:** Açılan seçiciden *T9 KlavyeTR*'yi seçin.

Her adımın yanında durum görünür (✓ Etkin / ✓ Seçildi).

## Proje Yapısı

| Dosya | Görevi |
|---|---|
| `MainActivity.kt` | Açılış ekranı, kurulum adımları ve özellik tanıtımı |
| `T9Service.kt` | Klavye servisi (giriş yöntemi): tuş akışı, durum, T9/sembol/imleç/rakam sayfaları, öneri çubuğu |
| `DictData.kt` | Sözlük verisi: temel kelimeler, kullanıcı kelimeleri, bigram/harf istatistikleri |
| `T9Trie.kt` | Tuş kodundan kelime bulan bellek dostu T9 ağacı |
| `T9Keymap.kt` | Türkçe T9 tuş eşlemesi ve kelime → tuş kodu dönüşümü (saf, test edilebilir) |
| `DefaultDictionary.kt` | Sözlük dosyaları okunamazsa kullanılan yedek kelimeler |
| `BinaryDictionaryReader.kt` | `words_tr.bin` okuyucusu |
| `SuggestionEngine.kt` | Tahmin motoru: T9 aday arama, harf tahmini, ek ve sonraki kelime önerileri (`T9Service` uzantıları) |
| `ClipPage.kt` | Pano sayfası: kartlar, kaydırarak silme, düzenleme |
| `EmojiPage.kt` | Emoji sayfası ve ten rengi seçici |
| `SettingsPages.kt` | Ayarlar ve tema sayfaları |
| `KeyboardData.kt` | Sabit veriler: emoji listesi, Türkçe ek listesi, tarayıcı paket adları |
| `ResizeOverlay.kt` | "Yeniden Boyutlandır" katmanı (yükseklik, yan boşluk, yükseltme) |
| `ClipManager.kt` | Pano dinleme ve hassas içeriği filtreleme |
| `ClipStore.kt` | Pano geçmişinin saklanması |
| `ClipEditActivity.kt` | Pano kartı oluşturma, düzenleme, kilitleme ve silme penceresi |
| `UserDictionaryActivity.kt` / `UserDictionaryStore.kt` | Kullanıcı sözlüğü ekranı ve saklaması |
| `IconView.kt` / `JoystickView.kt` | Çizilen tuş ikonları ve joystick görünümü |

## Otomatik Yedek

Kullanıcı Sözlüğü ekranından açılır. Her gün 23:45'te sözlük `İndirilenler/t9-sozluk-yedek.json`
dosyasına yazılır ve önceki yedeğin üzerine geçer. Cihaz o saatte kapalıysa açılışta kaçırılan yedek
alınır. `AutoBackup.kt` (zamanlama ve yazma) ve `AutoBackupReceiver.kt` (alarm/açılış olayları).

## Sözlüğü Güncelleme

Kaynak liste `tools/words_tr.txt` dosyasındadır (APK'ya girmez). Değiştirdikten sonra proje kökünde
`python convert_to_binary.py` çalıştırın; `app/src/main/assets/words_tr.bin` yeniden üretilir.

## Katkıda Bulunma

Hata bildirimi ve öneriler için **Issues** bölümünü kullanabilirsiniz. Pull request'ler de memnuniyetle karşılanır.

## Lisans

## Lisans

Bu proje [MIT Lisansı](LICENSE) ile yayınlanmıştır.
