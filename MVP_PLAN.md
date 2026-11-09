# MVP planı — səsli köməkçi "Əli"

> İş adı. Tətbiqin adı **Köməkçi**, köməkçinin adı isə tənzimləmələrdən dəyişdirilə bilər (standart: **Əli**).
>
> **Qeyd (2026-11-09):** fırıldaq SMS xəbərdarlığı layihədən çıxarıldı. Aşağıdakı plan ilkin versiyadır; 3-cü ssenari və `/api/scam` artıq yoxdur.

## 1. Məqsəd

Hakatonda münsiflərə 3 dəqiqəlik canlı demoda üç ssenarini qüsursuz göstərmək:

1. **Səslə WhatsApp mesajı.** *"Əli, nəvəmə yaz: salam"* deyəndə Əli kimə yazacağını soruşur, təsdiq alır və mesajı özü göndərir.
2. **Kamera ilə dərman qutusunu oxumaq.** Qutunu kameraya göstərirsən, Əli dərmanın adını, nə üçün olduğunu və son istifadə tarixini səsli deyir.
3. **Fırıldaq SMS-i xəbərdarlığı.** *"Kartınız bloklanıb, kodu göndərin"* kimi mesaj gələn kimi Əli ucadan xəbərdarlıq edir.

## 2. Əhatə dairəsi

| MVP-də var | Sonrakı mərhələyə qalır |
|---|---|
| Səsli əmr (Azure `az-AZ` nitq tanıma, Google ehtiyat kimi) və yazaraq əmr (demo ehtiyatı) | "Əli" oyandırma sözü (fonda daimi dinləmə) |
| Ailə adları ilə kontaktlar (nəvəm, qızım, oğlum) | Ailə paneli (övladlar üçün ayrıca tətbiq) |
| Göndərmədən əvvəl səsli təsdiq və iri **Hə / Yox** düymələri | SOS və yer məlumatı |
| WhatsApp-da avtomatik "Göndər" (Accessibility); WhatsApp yoxdursa SMS | Dərman xatırlatmaları, sağlamlıq qeydləri |
| Səslə adi zəng | Görüntülü zəng, ödəniş və sifarişlər |
| Kamera ilə oxuma (dərman, qəbz, məktub) | Sahibini səsindən tanımaq |
| Fırıldaq mesajı xəbərdarlığı (lokal qaydalar və Gemini) | iOS versiyası |
| Ümumi suallara qısa cavab | Play Store-a buraxılış |

## 3. Arxitektura

```
┌──────────────────────── Android tətbiqi (Kotlin + Jetpack Compose) ────────────────────────┐
│                                                                                             │
│  🎤 Mikrofon ─► /api/stt (Azure) və ya Google ─► Lokal NLU (qaydalar) ─► Dialoq meneceri      │
│                                                  │ başa düşmədi            │                 │
│                                                  ▼                         ├─► WhatsApp linki │
│                                           /api/intent                      │   + Accessibility│
│                                                                            │     "Göndər"     │
│  📷 CameraX ─► şəkil (JPEG) ──────────────► /api/vision                    ├─► Zəng           │
│                                                                            └─► 🔊 Səsləndirmə │
│  🔔 Bildiriş dinləyicisi ─► Lokal fırıldaq qaydaları ─(şübhəli)─► /api/scam                   │
│                                                                                             │
└───────────────────────────────────────────┬─────────────────────────────────────────────────┘
                                            │ HTTP (USB ilə `adb reverse` və ya Wi-Fi)
┌───────────────────────────────────────────▼─────────────────────────────────────────────────┐
│  Backend (Node.js + TypeScript + Express)                                                   │
│   /api/intent  → Gemini Flash — əmri strukturlaşdırılmış JSON-a çevirir                     │
│   /api/vision  → Gemini Flash — şəkildəki yazını oxuyur, izah edir                          │
│   /api/scam    → Gemini Flash — mesajın fırıldaq olub-olmadığını deyir                      │
│   /api/stt     → Gemini (və ya Azure az-AZ) — telefonun tanıyıcısı Azərbaycan dilini bilmir │
│   /api/tts     → Azure Speech (az-AZ neyron səs)  — istəyə bağlı                             │
│   API açarları yalnız serverdə saxlanılır (Claude da AI_PROVIDER=claude ilə qoşula bilər)  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Niyə belə?

- **Native Android (Kotlin).** Tətbiqin əsas imkanları (Accessibility, bildiriş dinləyicisi, nitq tanıma, TTS) native API-lərdir. Flutter və ya React Native seçsəydik də, bunlar üçün ayrıca native kod yazmalı olacaqdıq.
- **Ayrıca backend.** API açarı APK-nın içində olsa, onu çıxarmaq asandır. Serverdə saxlayanda həm açar təhlükəsiz olur, həm də prompt-ları tətbiqi yenidən yığmadan dəyişmək olur.
- **Əvvəl lokal qaydalar, sonra süni intellekt.** Demonun əsas əmrləri (mesaj, zəng, kamera) telefonda dərhal başa düşülür. Gemini yalnız qaydalar başa düşməyəndə çağırılır, ona görə gecikmə və xərc azalır.
- **Təhlükəsizlik dizaynı.** Accessibility xidməti yalnız WhatsApp-da işləyir və yalnız istifadəçinin səslə təsdiq etdiyi mesajı 20 saniyə ərzində göndərir. Mətn qutusundakı yazı təsdiq olunan mətnlə üst-üstə düşməsə, heç nə basmır.
- **Səs və nitq tanıma.** Android-in öz TTS mühərrikində Azərbaycan dili adətən olmur. Google-un telefon daxilindəki nitq tanıyıcısında da Azərbaycan modeli yoxdur (emulyatorda təsdiqləndi). Ona görə hər ikisi serverdə Azure Speech ilə həll olunur: `az-AZ` neyron səsi (Banu/Babək) və `az-AZ` nitq tanıma. Azure açarı yoxdursa, tətbiq türk səsi ilə yaxın tələffüzə və telefonun tanıyıcısına keçir.

## 4. Demo ssenarisi (3 dəqiqə)

| Vaxt | Nə edirik | Nə görünür |
|---|---|---|
| 0:00–0:30 | Problemi deyirik, Siri və ya Google köməkçisinə Azərbaycan dilində əmr veririk | Başa düşmür |
| 0:30–1:15 | *"Əli, nəvəmə yaz: salam"* → *"Murada, yoxsa Aysuya?"* → *"Murada"* → *"Murada «Salam» yazım?"* → *"Hə"* | WhatsApp açılır, mesaj öz-özünə gedir, komanda üzvünün telefonuna çatır |
| 1:15–2:00 | *"Əli, bu dərmanın üstündə nə yazılıb?"* → qutunu kameraya göstəririk | Ekranda iri yazı, Əli adını, təyinatını, son istifadə tarixini deyir |
| 2:00–2:30 | Komanda üzvü telefona fırıldaq SMS-i göndərir | Qırmızı xəbərdarlıq ekranı və səsli xəbərdarlıq |
| 2:30–3:00 | Biznes modeli: ailə abunəliyi, operator və bank tərəfdaşlığı | Slayd |

## 5. Komandanın görəcəyi işlər

Kod hazırdır (bax: `README.md`). Qalan işlər:

| Kim | İş | Nəticə |
|---|---|---|
| Android | Real telefonda (WhatsApp ilə) bütün axını yoxlamaq, demo kontaktlarını yazmaq | 10 dəfədən 10-u alınır |
| Backend / AI | Gemini (və istəsəniz Azure) açarını qoşmaq, nitq tanımanı yaşlı insanların səsi ilə sınamaq, prompt-ları real dərman qutuları ilə yoxlamaq | Cavab 5 saniyədən tez gəlir, uydurma yoxdur |
| Demo / Pitch | Ssenarini məşq etmək, ehtiyat video çəkmək, slaydlar, münsif suallarına cavablar | 3 dəqiqəyə sığır |

**Təklif olunan vaxt planı (48 saat):**

- **0–4 saat:** quraşdırma, telefonda icazələr, real kontaktlar, backend-in işə salınması.
- **4–16 saat:** real telefonda test; nitq tanımanın səhv etdiyi ifadələri lokal qaydalara əlavə etmək.
- **16–28 saat:** dərman qutuları ilə vision testləri, Azure səsi, UI incələmələri.
- **28–40 saat:** demo məşqi (ən azı 10 dəfə), ehtiyat video, slaydlar.
- **40–48 saat:** dondurma: yeni funksiya yoxdur, yalnız səhv düzəlişi.

## 6. Risklər və ehtiyat planı

| Risk | Ehtiyat planı |
|---|---|
| Telefonun nitq tanıyıcısı Azərbaycan dilini bilmir | Azure `az-AZ` nitq tanıma serverdə; tətbiq avtomatik ora keçir |
| Zalda səs-küy, nitq tanıma səhv edir | Qulaqlıq mikrofonu; ekrandakı "Yazaraq" sahəsi; diakritiksiz yazı da başa düşülür (`neveme yaz salam`) |
| İnternet zəifdir | Mobil hotspot. Lokal qaydalar mesaj, zəng və fırıldaq üçün serversiz işləyir (nitq tanıma isə internet istəyir) |
| WhatsApp interfeysi dəyişib, "Göndər" tapılmır | Tətbiq "Göndər düyməsini basın" deyir, mesaj artıq yazılmış olur |
| Android 13+ "Məhdud parametrlər" Accessibility-ni bağlayır | Tətbiq haqqında → ⋮ → "Məhdud parametrlərə icazə ver" (README-də addım-addım) |
| Süni intellekt cavabı gecikir | Flash modeli; demodan əvvəl bir sorğu ilə serveri "isitmək" |
| Səhv adama mesaj | Təsdiq məcburidir; demoda yalnız komanda üzvlərinin nömrələri |
| Canlı demo tam alınmır | Əvvəlcədən çəkilmiş ehtiyat video |

## 7. Hazırlıq meyarları (Definition of Done)

- [ ] Real telefonda 3 ssenari ardıcıl 10 dəfə alınır.
- [ ] Mesaj təsdiqsiz heç vaxt getmir ("yox" və "göndərmə" deyəndə ləğv olunur).
- [ ] Dərman qutusunda oxunmayan yerləri Əli uydurmur, "yaxına tutun" deyir.
- [ ] Fırıldaq SMS-i gələndə 3 saniyə ərzində səsli xəbərdarlıq olur.
- [ ] API açarları APK-da yoxdur, yalnız `backend/.env`-dədir.
- [ ] Ehtiyat video və slaydlar hazırdır.
