# تقرير التحليل الكامل لمشروع Mushrea Code

> **نطاق التقرير:** تحليل ثابت (Static) كامل للمستودع `hishamalmushrea-cloud/Mushrea.AI` عند الالتزام `441691a` (الفرع `arena/01a0f442-mushrea-ai`).
> **تاريخ التحليل:** 2026-10-01.
> **منهجية العمل:** جرد كامل للملفات → قراءة الكود والإعدادات والموارد → تتبع المسارات الفعلية → التحقق من الادعاءات بالدليل → تصنيف الحالة.
> **اصطلاحات:** كل بند يُوسم ضمنيًا بأنه **Fact (حقيقة موثّقة بالدليل)** ما لم يُكتب صراحةً **استنتاج** أو **توصية**.
> **لم يُبنَ المشروع ولم يُشغَّل** أثناء هذا التحليل (لا JDK ولا Android SDK ولا محاكي ولا جهاز في بيئة الفحص) — التفاصيل في قسم «حدود التحليل» في نهاية التقرير.

---

## 1. الملخص التنفيذي

**Mushrea Code** تطبيق أندرويد أصلي (Kotlin + Jetpack Compose) يعمل كواجهة رسومية لإدارة تشغيل وكلاء البرمجة بالذكاء الاصطناعي (Coding Agents) على الهاتف نفسه أو على جهاز بعيد.

**ما يفعله فعليًا (مؤكَّد من الكود):**

1. يُنزّل ويُشغّل بيئة Linux مصغّرة (Alpine) داخل التطبيق عبر **PRoot** بلا root، ثم يثبّت داخلها واجهات سطر الأوامر الرسمية: **OpenCode**، **Claude Code**، **Google Antigravity (`agy`)**، **OpenAI Codex**، ويشغّلها ويحادثها من واجهة تعتمد اللمس.
2. يستطيع بدلًا من ذلك الاتصال بخادم **OpenCode** يعمل على حاسوب المستخدم (LAN / Tailscale / HTTPS) وحواره عبر REST + SSE.
3. يضيف فوق ذلك طبقة تحكّم بالجهاز نفسه (**Device Agent**) تُمنح صلاحية Accessibility فتقرأ الشاشة وتنفّذ نقرًا/كتابةً/تمريرًا عبر إطار عمل أدوات MCP: **88 أداة** مربوطة بـ **89 إجراءً** في جدار حماية صلاحيات (57 تلقائي + 32 يتطلب تأكيدًا)، مع وضع «قراءة فقط» افتراضي وسجل تدقيق وزرّ إيقاف طارئ.
4. يشمل منظومة أجهزة واتصالات واسعة: USB/ADB، Fastboot للقراءة فقط، MTP، منافذ تسلسلية، HID، SSH/SFTP/SCP، SMB/FTP/WebDAV، شبكة وأدوات Bluetooth، عرض شاشة هاتف آخر (Mirror) وتحكّم كامل عبر scrcpy، وتحليل ملفات OTA (payload.bin) وحمايتها قبل التفليش.
5. يشمل **وكيل مكالمات** (Call Agent) يردّ/يتصل ويُدير محادثة صوتية بالعربية والإنجليزية مع سياسة أمان صارمة (لا كلمات مرور/OTP إطلاقًا).
6. منظومة جدولة (Cron + مرة واحدة) تعمل عبر AlarmManager وForeground Service وسجل تشغيل، ونظام صوتي كامل (إملاء + قراءة + كلمة تنبيه Vosk + تسجيل كمساعد افتراضي)، وويدجت وهدف مشاركة.

**الحجم والبنية (حقيقة مقيسة):**

| المقياس | القيمة |
|---|---|
| ملفات Kotlin | 553 ملفًا (103,419 سطرًا) |
| كود التطبيق الرئيسي | 358 ملفًا / 76,394 سطرًا |
| ملفات الاختبار | 180 ملفًا / 26,034 سطرًا / **1,323** دالة `@Test` |
| اختبارات الأجهزة (androidTest) | 9 ملفات / 23 اختبارًا |
| نصوص الترجمة | 1,220 نصًا × 8 لغات (بالإنجليزية الافتراضية + 7 مترجمة بالكامل) |
| الإصدار | `1.2.26` / `versionCode 65` |
| سجل Git | التزام واحد فقط (تاريخ مُسطَّح) |

**الحالة العامة:** المشروع **مكتمل وظيفيًا بدرجة عالية** في المسارات الموثّقة (المحادثة، تشغيل البيئة المحلية، الجدولة، الأجهزة، الصوت)، مع ثلاث ملاحظات مهمة:
- توجد **أكواد ميتة مؤكَّدة** (طبقة Room كاملة غير مستخدمة، ملف `ForgeClient` كامل غير مُستدعى، وشاشة/أدوات غير مرتبطة).
- بعض الميزات **جزئية بالإعلان الصريح** في الكود نفسه (رفع MTP، فك HID، التقاط الكاميرا، المرحلة الكتابية لـ Termux).
- **اختبارات الأجهزة موجودة لكنها لا تُنفَّذ في CI** (تُصرَّف فقط)، حسب فحص ملفات سير العمل.

---

## 2. تعريف المشروع

| البند | القيمة | الدليل |
|---|---|---|
| الاسم التجاري | Mushrea Code | `README.md` |
| اسم مشروع Gradle | `MushreaCode` | `settings.gradle.kts` |
| معرّف التطبيق | `com.mushrea.code` (namespace وapplicationId) | `app/build.gradle.kts` |
| المستودع | `hishamalmushrea-cloud/Mushrea.AI` | `README.md`, `core/ProjectLinks.kt` |
| النوع | تطبيق أندرويد أصلي (تطبيق واحد + وحدة benchmark) | `settings.gradle.kts` |
| المنصة المستهدفة | Android فقط، minSdk **26** (8.0)، target/compile **35** | `app/build.gradle.kts` |
| المعماريات المدعومة | `arm64-v8a`, `x86_64` فقط (ABI filter) | `app/build.gradle.kts`، `scripts/prepare_android_runtime_native_libs.py` |
| اللغات | Kotlin 100% (لا Java في `src`) | جرد الملفات |
| إطار الواجهة | Jetpack Compose + Material 3 | `app/build.gradle.kts` |
| نظام البناء | Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21 | `gradle/wrapper/…`, `build.gradle.kts` |
| الترخيص | MIT — **لكود Mushrea Code فقط**، لا يشمل الوكلاء الخارجيين | `LICENSE`, `THIRD_PARTY_NOTICES.md` |
| الأصل | مشتق من مشروع AndCode مفتوح المصدر ثم أُعيدت تسميته وصيانته | `README.md` (قسم Acknowledgements) |
| الحالة | مستودع شغّال بدرجة إنتاجية، مع CI متعدد المسارات وإصدارات موسومة | `.github/workflows/`, `.release-version` |

**ملاحظة تعارض محتمل (Fact):** `README.md` يقدّم المشروع كواجهة «محلية أولًا» للوكلاء، وهو صحيح، لكن المستودع يحتوي أيضًا على منظومة كاملة غير مذكورة في README (USB، Termux، المكالمات، الشبكة، scrcpy…). راجع القسمين 21 و24.

---

## 3. أهداف المشروع

مستخلَصة من `README.md` + `HANDOFF.md` + بنية الكود:

1. **إلغاء الحاجة إلى الحاسوب أو الطرفية** لتشغيل وكيل برمجة على الهاتف (واجهة لمسية كاملة).
2. **تشغيل واجهات CLI الرسمية كما هي** دون إعادة تنفيذ منطق الوكيل ولا تعديل ثنائياته، داخل بيئة PRoot.
3. **دعم أكثر من وكيل** عبر طبقة تجريد واحدة (`RuntimeTarget`/`OpenCodeBackend`) بحيث لا تتغير الواجهة عند إضافة وكيل.
4. **التحكم بالجهاز نفسه** عبر وسائل أندرويد الرسمية (Accessibility/Telecom/USB) مع بوابات أمان قابلة للتدقيق.
5. **الشفافية والأمان**: لا خادم وسيط خاص بالمشروع، لا بيع وصول للنماذج، وثائق خصوصية/ترخيص مدمجة بلا إنترنت.
6. **تسليم تجربة جوال كاملة**: صوت، جدولة، ويدجت، مشاركة، إشعارات موافقات من شاشة القفل.

---

## 4. المنصات والبيئة المستهدفة

| البند | التفصيل | الدليل |
|---|---|---|
| أدنى إصدار | Android 8.0 (API 26) | `app/build.gradle.kts` |
| الإصدار المستهدف | API 35 (Android 15) | `android.suppressUnsupportedCompileSdk=35` |
| ABI | arm64-v8a + x86_64 | `ndk { abiFilters }` |
| صلاحية قانونية على Android 10 | `requestLegacyExternalStorage=true` | `AndroidManifest.xml` |
| بيئة البناء | JDK 17، Android SDK، Python 3.11، شبكة عند أول بناء | `README.md`, `.github/workflows/android.yml` |
| بيئة التشغيل الفعلية | هاتف بمعالج arm64 (الاختبار الموثّق: محاكي API 36 ARM64 + أجهزة قيد الاختبار) | `docs/device-matrix.md` |
| الاعتماديات الخارجية وقت التشغيل | تنزيل Alpine + OpenCode + Debian bookworm + agy + Vosk + حزم الوكلاء | `assets/local-runtime-manifest.json`، `AntigravityManifest.kt` |
| الحد الأدنى للنسخة المدعومة في التقارير | لا Nullability على Native؛ Vosk يتطلب ABI مدعومًا | `VoskModelCatalog.kt` |

**لا يوجد** أي دعم لـ iOS أو Desktop أو Web — المشروع أندرويد خالص (Fact).

---

## 5. التقنيات المستخدمة (نظرة سريعة)

Kotlin 2.0.21 · Jetpack Compose (BOM 2024.12.01) + Material 3 · Navigation-Compose 2.8.5 · Koin 4.0.1 (DI) · Coroutines/Flow 1.9.0 · OkHttp 4.12 (+SSE) · kotlinx.serialization 1.7.3 · Room 2.6.1 (**غير مستخدم**) · EncryptedSharedPreferences (security-crypto alpha06) · Vosk-android 0.3.75 · sshj 0.38 / smbj 0.14 / commons-net 3.11 · usb-serial-for-android 3.7 · XZ 1.9 + eddsa · ZXing 4.3 · Firebase (Analytics/Crashlytics — نسخة github فقط) · detekt 1.23.6 + spotless/ktlint 1.2.1 · Jacoco (مُضاف بلا مهام تغطية) · PRoot (ثنائي مبني من وصفة Termux) · Python 3 (خوادم MCP داخل الضيف). الجدول التفصيلي في القسم 26.

---

## 6. المعمارية (Architecture)

### 6.1 الطبقات الفعلية

```
┌────────────────────────────────────────────────────────────────────┐
│  UI (Compose)                                                      │
│  ui/MushreaCodeApp.kt (NavHost + Drawer)                           │
│  ui/navigation/{SettingsNavGraph, WorkspaceNavGraph} + Routes.kt   │
│  feature/**/…Screen.kt  ←  State via StateFlow.collectAsState      │
│  Activities مستقلة: DeviceAgent, CallAgent, Mirror, QuickInput,    │
│                     ShareReceiver, StopAgent                       │
└───────────────▲────────────────────────────────────┬───────────────┘
                │ UiState (StateFlow)                │ Intent/أحداث
┌───────────────┴────────────────────────────────────▼───────────────┐
│  Presentation logic                                                │
│  ChatViewModel (2767 سطر) · WorkspaceViewModel · SettingsViewModel │
│  ScheduleViewModel · CodeViewerViewModel · TerminalViewModel …     │
└───────────────▲────────────────────────────────────┬───────────────┘
                │                                    │
┌───────────────┴────────────────────────────────────▼───────────────┐
│  Domain-ish services                                               │
│  RuntimeRegistry (اختيار الران‑تايم) · RuntimeCatalogRepository     │
│  RuntimeActivityRepository · PullRequestStatusRepository           │
│  ScheduleManager/Repository · RuntimeWorkTracker (WakeLock leases) │
└───────────────▲────────────────────────────────────┬───────────────┘
                │                                    │
┌───────────────┴────────────────────────────────────▼───────────────┐
│  Runtime layer (Abstraction)                                       │
│  RuntimeTarget : OpenCodeBackend                                   │
│   ├─ LocalRuntimeTarget  → LocalOpenCodeBackend  → HTTP 127.0.0.1  │
│   ├─ RemoteRuntimeTarget → RemoteOpenCodeBackend → HTTP(S) للـPC    │
│   ├─ ClaudeCodeTarget    → ClaudeCodeRuntime (stdin/stdout JSON)   │
│   ├─ AntigravityTarget   → AntigravityRuntime (PTY + stream-json)  │
│   └─ CodexTarget         → CodexRuntime (app-server JSON-RPC)      │
└───────────────▲────────────────────────────────────┬───────────────┘
                │                                    │
┌───────────────┴────────────────────────────────────▼───────────────┐
│  Execution substrate                                               │
│  LocalRuntimeProcessLauncher → PRoot → Alpine/Debian rootfs        │
│  LocalRuntimeInstaller · LocalRuntimeUpdater · Watchdog · Service  │
│  DeviceAgentBridge (ملفات أوامر) + TermuxBridge (Intents)          │
└───────────────▲────────────────────────────────────┬───────────────┘
                │                                    │
┌───────────────┴────────────────────────────────────▼───────────────┐
│  Data / persistence                                                │
│  SecureSettingsRepository (EncryptedSharedPreferences)             │
│  AppPreferencesRepository · DraftRepository · ScheduleRepository   │
│  JSON files (device-agent, catalog-cache, audit, adapter glue)     │
│  Room DB (مُعرّفة — غير مستخدمة)                                   │
└────────────────────────────────────────────────────────────────────┘
```

### 6.2 نمط المعمارية الفعلي

- **MVVM عملي** (ViewModel + StateFlow + Compose) وليس Clean Architecture صارمة: لا توجد طبقة `UseCase` منفصلة، والـViewModels تتحدث مباشرة مع المستودعات/الران‑تايم.
- **Repository Pattern** جزئي: `RuntimeCatalogRepository`، `RuntimeActivityRepository`، `ScheduleRepository`، `PullRequestStatusRepository`، `ProviderCatalogCache`، `AppPreferencesRepository`.
- **Dependency Injection**: Koin بمعاملتين فقط — `di/AppModule.kt` (18 تعريفًا) و`di/ViewModelModule.kt` (4 ViewModels)، بالإضافة إلى بناء يدوي كثيف داخل `MushreaCodeApplication.onCreate()` (التطبيق يملك ~35 خاصية `lateinit` تُبنى بالتسلسل اليدوي)، وهو **تداخل نمطين** (استنتاج: مقصود للسماح للـViewModels بالوصول إلى مكوّنات التطبيق مباشرة).
- **نمط تجريد الران‑تايم**: واجهة `OpenCodeBackend` هي «العقد الموحّد»: 60+ عملية، لكل عملية تنفيذ افتراضي يرفع `UnsupportedOperationException`، وكل ران‑تايم يطبّق ما يدعمه فقط. `RuntimeCapabilities` تُعلن القدرات للواجهة (permissions/questions/toolEvents/resume/editMessages/diffCapable/forcesQueue/abortsBeforeInterrupt).
- **إدارة الحالة**: `StateFlow` في كل مكان + `SharedFlow` للأحداث اللحظية (`events()`)، لا LiveData ولا MVI صريح.
- **الملاحة**: Navigation-Compose بـ3 رسوم بيانية (رئيسي/مساحات عمل/إعدادات) ومسارات نصية ثابتة في `ui/navigation/Routes.kt` (تُشفَّر المعاملات بـBase64 URL-safe).

### 6.3 مسار أمر نموذجي (إرسال رسالة)

```
المستخدم يكتب → ChatComposer → ChatViewModel.send()
  → Backend = RuntimeRegistry.selected → target.sendMessage(sessionId, PromptRequest)
      ├─ محلي  : HTTP POST إلى 127.0.0.1:4097 (OpenCode) → SSE /event
      ├─ بعيد  : HTTP POST إلى خادم المستخدم → SSE /event
      ├─ Claude: كتابة JSON على stdin للعملية داخل PRoot → قراءة سطور stream-json
      ├─ Anti  : تشغيل agy داخل PTY وقراءة stream-json
      └─ Codex : JSON-RPC على app-server
  ← RuntimeActivityRepository يجمع الأحداث (parts/deltas/permissions/questions/idle)
  ← ChatViewModel يبني ChatUiState (رسائل + أدوات + مهام + تشخيص تعطّل)
  ← Compose يعيد الرسم
```

### 6.4 مسار موافقة أداة (Permission)

```
SSE: permission.asked → RuntimeActivityRepository → onPermissionAsked
   → RuntimeNotificationHelper.notifyPermission (3 أزرار: مرة/دائمًا/رفض)
   → PermissionActionReceiver → RuntimeActivityRepository.respondToPermission
   → Backend.respondToPermission(session, id, ONCE|ALWAYS|REJECT)
```
الواجهة أيضًا تعرض نفس الطلب داخل شاشة المحادثة (`PermissionCard`)، والموافقات قابلة للتفعيل التلقائي عبر إعداد `autoAcceptPermissions` (مُعطَّل افتراضيًا).

---

## 7. هيكل المشروع (الخريطة الحقيقية)

```
Mushrea.AI/
├── app/                                   ← التطبيق الوحيد
│   ├── build.gradle.kts                   ← 330 سطرًا: flavors، توقيع، R8، مهام Python
│   ├── proguard-rules.pro                 ← قواعد R8 (serialization, JNA/Vosk, sshj, smbj)
│   ├── google-services.json               ← مُلتزَم في Git (انظر قسم الأمان)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        ← 40+ مكوّنًا/صلاحية
│       │   ├── assets/                    ← manifest الران‑تايم + 3 خوادم MCP + سياق الوكيل
│       │   │   ├── local-runtime-manifest.json
│       │   │   ├── mushrea-code-agent-context.md
│       │   │   ├── scripts/{device,browser,schedule}-mcp.py + هوك صلاحيات Claude
│       │   │   ├── legal/ (الخصوصية/الشروط/التراخيص + النسخة اليابانية)
│       │   │   └── scrcpy/scrcpy-server-4.0 (732KB)
│       │   ├── java/com/mushrea/code/
│       │   │   ├── MushreaCodeApplication.kt, MainActivity.kt, OnboardingGate.kt
│       │   │   ├── core/        (api, diagnostics, lifecycle, locale, notification,
│       │   │   │                 runtime, security, storage, util)
│       │   │   ├── data/        (connection, local[Room], repository, schedule, settings)
│       │   │   ├── device/      (accessibility, call, mirror, network, payload, remote,
│       │   │   │                 ssh, termux, usb, usbhub, bluetooth)
│       │   │   ├── di/          (AppModule, ViewModelModule)
│       │   │   ├── feature/     (activity, assistant, browser, chat, onboarding, schedule,
│       │   │   │                 settings, share, support, wakeword, widget, workspace)
│       │   │   ├── runtime/     (RuntimeTarget/Registry + local/ 60 ملفًا + remote/)
│       │   │   ├── startup/     (auto-start + catalog reconcile)
│       │   │   └── ui/          (App, Drawer, navigation, components, theme)
│       │   └── res/             ← values + 7 لغات + xml + drawable + layout
│       ├── github/  ← Analytics/Crashlytics (Firebase)
│       ├── fdroid/  ← بدائل No-Op بلا Firebase
│       ├── test/        (180 ملفًا / 1323 اختبارًا)
│       └── androidTest/ (9 ملفات / 23 اختبارًا)
├── benchmark/            ← مولّد Baseline Profile (يطابق :app)
├── runtime_tools/        ← termux_assets.py + قفل الحزم + وصفات بناء proot/libandroid-shmem/libtalloc
├── scripts/              ← تجهيز أصول الران‑تايم، لافتة NOTICE، توقيع حزمة كلمة التنبيه، قياس البطارية، worktree
├── config/detekt/        ← detekt.yml + baseline.xml
├── docs/                 ← 13 مستندًا (تصميم، CI، إصدار، ترجمة، وكيل الجهاز، مصفوفة أجهزة…)
├── .github/workflows/    ← 7 سير عمل
├── fastlane/             ← وصف متجر (en-US)
├── brand/, store/        ← أيقونات المصدر
├── THIRD_PARTY_*، PRIVACY.md، TERMS.md، TRADEMARKS.md، LICENSE
└── README.md / README.ja.md / HANDOFF.md / AGENTS.md / CONTRIBUTING.md
```

**ملفات محورية بالحجم:** `ChatViewModel.kt` (2767)، `ChatHomeScreen.kt` (2235)، `ChatViewModelTest.kt` (2061)، `MushreaCodeApp.kt` (1695)، `AndroidSetupScreen.kt` (1427)، `VoiceSettingsScreen.kt` (1094).

---

## 8. جميع الميزات (نظرة شاملة)

| المجموعة | الميزات |
|---|---|
| **المحادثة** | محادثة متدفقة (SSE)، أدوات قابلة للطيّ، استدلال/تفكير، مهام Todo، موافقات أدوات، أسئلة تفاعلية، فرق مراجعة (diff)، بطاقات Pull Request، صور مرفقة، إعادة إرسال/تحرير آخر رسالة، أوامر Slash، قائمة انتظار عند انقطاع الاتصال، تشخيص التعطّل، مسودات |
| **الران‑تايم المحلي** | تثبيت Alpine+PRoot، SHA-256 لكل تنزيل، تبديل ذرّي (staging → swap)، تحديث/تراجع بمذكرات، حذف، فحص صحة، watchdog، إيقاف تلقائي عند الخمول، WakeLock ذكي، سجلّات وتشخيصات |
| **الوكلاء** | OpenCode (خادم HTTP محلي)، Claude Code (عملية stream-json + هوك صلاحيات)، Antigravity (Debian+PTY+OAuth)، Codex (app-server JSON-RPC + تسجيل ChatGPT/مفتاح API) |
| **الاتصال البعيد** | خادم OpenCode على PC عبر LAN/Tailscale، اكتشاف mDNS/NSD، إدخال QR، تثبيت شهادة (Pin)، بوابة منع HTTP مكشوف |
| **مساحات العمل** | مجلدات المشاريع، استيراد SAF، وصول كامل للملفات (all-files)، متصفح ملفات، بحث نصي، تبويبات متعددة، عارض كود بتلوين، طرفية مدمجة، Git (فرع/فرق/التزام) |
| **الجدولة** | جدولة Cron (5 حقول) أو مرة واحدة، تشغيل الآن، تمكين/تعطيل، سجل تنفيذ، إعادة محاولات، إشعارات فشل، جدولة عبر MCP |
| **وكيل الجهاز** | فتح تطبيقات/قراءة شاشة/نقر/كتابة/تمرير/بحث ملفات/مشاركة/حذف/نقل، سياق (تطبيق/مهمة/ملف)، جدار حماية، وضع قراءة فقط، سجل نشاط وتدقيق، إيقاف طارئ، إعدادات قابلة للفتح من الإشعار |
| **الأجهزة** | ADB عبر USB، shell/list/pull/push/install/logcat/screenshot، ADB لاسلكي، منافذ تسلسلية، HID قراءة، تخزين قابل للإزالة، كاميرات، مشغّل USB Hub، Fastboot getvar، MTP تنزيل |
| **الاتصالات** | SSH/SFTP/SCP (TOFU)، SMB، FTP، WebDAV، mDNS browse، أدوات شبكة (wifi/dns/ping/port/http/websocket)، Bluetooth/BLE |
| **الشاشة والمكالمات** | مرآة عرض فقط (screenrecord)، تحكم كامل scrcpy، وكيل مكالمات (اتصال/رد/محادثة/تلخيص/تصعيد) |
| **الصوت** | إملاء صوتي، بث فوري للنص، قراءة الردود (Android/OpenAI/ElevenLabs)، كلمة تنبيه Vosk، مقاطعة القراءة (barge-in)، تسجيل كمساعد افتراضي |
| **التكامل** | GitHub OAuth Device Flow، شارات PR، MCP لكل وكيل، مزوّدو نماذج مخصّصون، مطالبات النظام (System Prompts)، ويدجت، هدف مشاركة، اختصارات شاشة رئيسية |
| **المنتج** | 7 سِمات، 8 لغات، مستندات قانونية بلا إنترنت، تحليلات اختيارية (opt-in)، سجل تعطّل محلي، شاشة تشخيص |
| **ميت/غير مستخدم** | طبقة Room، `ForgeClient` (GitLab/Gitea)، تخطيط التابلت، مساعد السحب والإفلات، كاشف نشاط صوتي، KeepAwakeHelper |

---

## 9. تحليل كل ميزة (تفصيلي)

> لكل ميزة: الوصف · الموقع · طريقة العمل · المدخلات · المعالجة · المخرجات · الاعتماديات · الصلاحيات · الإنترنت · الحالة · الأدلة.

### 9.1 تشغيل الوكلاء على الجهاز (PRoot) — 🟢 مكتملة (غير مُختبرة في هذه البيئة)

- **الوصف:** تنزيل Alpine minirootfs + ثنائي OpenCode + (عند اختياره) Debian bookworm وagy، ثم تشغيل البيئة وتشغيل الخادم المحلي.
- **الموقع:** `runtime/local/LocalRuntimeInstaller.kt` (786)، `LocalRuntimeProcessLauncher.kt`، `LocalRuntimeService.kt` (778)، `LocalRuntimeManager.kt`، `LocalRuntimeUpdater.kt`، `LocalRuntimeWatchdog.kt`، `VerifiedRuntimeDownloader.kt`، `EmbeddedCommandSuite.kt`، `runtime_tools/*`، `scripts/prepare_android_runtime_assets.py` و`…native_libs.py`.
- **طريقة العمل:** مهمة Gradle `prepareOpenCodeRuntimeAssets` تبني حزم Termux من وصفات `runtime_tools/termux-packaging-recipes/`، ثم `prepareOpenCodeRuntimeNativeLibs` تضع `proot` و`libandroid-shmem`/`libtalloc` في `jniLibs` → عند التثبيت: التحقق من ABI → تنزيل الأصول بالتحقق من SHA-256 → استخراج إلى دليل staging → تثبيت حزم Alpine (`apk add`) داخل chroot عبر proot → كتابة `metadata.json` → تبديل ذرّي للدليل الفعّال → تشغيل الخادم على `127.0.0.1:4097` وربطه بـ`/dev`, `/proc`, `/sys`, `/system`, `/workspace` + تخزين الجهاز عند منح الصلاحية.
- **المدخلات:** اختيار الوكلاء + منح صلاحية التخزين (اختياري) + شبكة.
- **المخرجات:** `LocalRuntimeStatus.{NotInstalled→Installing→Stopped/Starting→Ready|Broken|UnsupportedAbi}`.
- **الاعتماديات:** PRoot، Alpine 3.24.1، OpenCode 1.18.5، Debian bookworm slim (طبقات OCI)، agy 1.1.7، Vosk (لاحقًا للصوت)، OkHttp، kotlinx.serialization.
- **الصلاحيات:** INTERNET (تنزيل)، POST_NOTIFICATIONS، FOREGROUND_SERVICE(+SPECIAL_USE)، WAKE_LOCK، MANAGE_EXTERNAL_STORAGE (اختيارية للملفات المشتركة).
- **الإنترنت:** نعم (تنزيل فقط؛ التشغيل نفسه محلي، والوكيل يتصل بمزوّد النموذج المختار).
- **الحالة:** 🟢 مكتملة (تنزيل قابل للاستئناف، مجلة تحديث/تراجع، حذف كامل)، **لم تُختبر عمليًا هنا**.
- **الأدلة:** `assets/local-runtime-manifest.json` (نسخ + SHA-256)، `AntigravityManifest.kt` (1.1.7 + تجزئات)، `DebianRootfsManifest.kt`، `LocalRuntimeInstaller.kt:61-235`، `LocalRuntimeService.kt:709-720` (أوامر الخدمة).

### 9.2 الاتصال بـ OpenCode البعيد — 🟢 مكتملة

- **الوصف:** إضافة/تعديل/حذف اتصالات خادم OpenCode على حاسوب المستخدم، والحوار عبر REST+SSE.
- **الموقع:** `core/api/OpenCodeApiClient.kt` (798)، `runtime/remote/RemoteOpenCodeBackend.kt`، `feature/workspace/{ConnectionDialog,ConnectionFormState,LanDiscovery,RemoteConnectionScreen}.kt`، `core/security/{OpenCodeUrl,ConnectionQrPayload}.kt`، `data/connection/*`.
- **العمل:** التحقق من الرابط (يرفض HTTP إلا للعناوين المحلية/RFC1918/CGNAT/‏`.local` أو IPv6 محلية) → تخزين الملف مشفّرًا → `GET global/health` → جلسات/مزوّدون/وكلاء → SSE.
- **المدخلات:** اسم، URL/IP، مستخدم/كلمة مرور، خيار HTTPS pinning.
- **المخرجات:** `RuntimeState.Connected(version)` + قائمة جلسات موحّدة.
- **الاعتماديات:** OkHttp (+SSE)، kotlinx.serialization، NsdManager، ZXing (QR).
- **الصلاحيات:** INTERNET، ACCESS_NETWORK_STATE، ACCESS_WIFI_STATE، CHANGE_WIFI_MULTICAST_STATE (لاستكشاف mDNS).
- **الإنترنت:** نعم (شبكة محلية عادة).
- **الحالة:** 🟢 مكتملة، مع تحقق اختبارات وحدات كبير (`OpenCodeApiClientTest` 658 سطرًا، MockWebServer).
- **الأدلة:** `OpenCodeUrl.kt:38-77`، `OpenCodeApiClientTest.kt`، `ConnectionQrPayloadTest.kt`.

### 9.3 Claude Code محليًا — 🟢 مكتملة (تجريبي بحسب README)

- **الوصف:** تشغيل `claude` داخل نفس بيئة PRoot ومحادثته عبر تدفّق JSON على الأنابيب، مع جسر موافقات عبر ملفات.
- **الموقع:** `runtime/local/ClaudeCodeRuntime.kt` (772)، `ClaudeCodeTarget.kt` (674)، `ClaudeCodeInstaller.kt`، `ClaudeAuthCoordinator.kt`، `ClaudePermissionBridge.kt`/`ClaudePermissionHooks.kt`، `ClaudeStreamJsonParser.kt`، `ClaudeWorkspace{Files,Git}.kt`، وهوك `assets/scripts/mushrea-code-claude-permission-hook.sh`.
- **العمل:** تسجيل الدخول يبدأ عبر `claude auth login`؛ التطبيق يلتقط رابط التفويض ويعرضه للخروج إلى المتصفح، ويكتب الكود العائد؛ أثناء التشغيل يرسل CLI أحداث الموافقة إلى الهوك الذي يكتب في `~/.mushreacode/claude-bridge/pending` وينتظر `responses/` (مع مهلة 300 ثانية للموافقات و3540 ثانية للأسئلة).
- **الصلاحيات:** لا صلاحيات أندرويد إضافية (كل العمل داخل الملفات الخاصة + INTERNET للوكيل).
- **الحالة:** 🟢 مكتملة؛ **الحكم النهائي الفعلي غير مُتحقَّق منه هنا** (اختبارات وحدات موجودة لـParser/Bridge/Installer).

### 9.4 Google Antigravity — 🟢 مكتملة (يحتاج Debian + glibc)

- **الموقع:** `runtime/local/Antigravity*.kt` (16 ملفًا)، `DebianRootfsInstaller.kt`، `AntigravitySandboxLauncher.kt`، `AntigravityAuthCoordinator.kt`.
- **العمل:** تنزيل طبقات Debian bookworm slim من Docker Registry + `bsdutils` (لتوفير `script` للـPTY) → تشغيل `agy` داخل PTY بمقاس محدّد → مصادقة OAuth (URL+كود) → قراءة `stream-json` → إدارة `~/.gemini/config/mcp_config.json`.
- **الحالة:** 🟢 مكتملة، وتُصرَّح قدراتها كـ`forcesQueue=true` (كل دورة عملية جديدة → لا «مقاطعة»)، `diffCapable=false`، `resume` مدعوم عبر سجل جلسات محلي.
- **الأدلة:** `RuntimeCapabilities.kt`، `AntigravityRuntime.kt:20-40`، `AntigravityManifestTest.kt`.

### 9.5 OpenAI Codex — 🟢 مكتملة

- **الموقع:** `runtime/local/Codex*.kt` (13 ملفًا)، `CodexJsonRpcClient.kt`، `CodexKeepAliveService.kt`، `feature/settings/CodexSignInViewModel.kt`.
- **العمل:** تشغيل `codex app-server` ثم JSON-RPC ثنائي الاتجاه (خيوط/رسائل/موافقات/توليد صور)، وخدمة أمامية قصيرة العمر تُبقي العملية حيّة أثناء الخروج للتسجيل، وتوليد الصور يُعرض داخل المحادثة.
- **الحالة:** 🟢 مكتملة (اختبارات: `CodexItemParserTest`, `CodexJsonRpcClientTest`, `CodexLoginTrackerTest`, `CodexMcpTest`…).

### 9.6 المحادثة المتدفقة والواجهة الزمنية — 🟢 مكتملة

- **الموقع:** `core/api/OpenCodeEventParser.kt`، `data/repository/RuntimeActivityRepository.kt` (730)، `feature/chat/*` (21 ملفًا).
- **العمل:** اشتراك واحد في `events()` لكل ران‑تايم محدَّد؛ الأحداث: `server.connected, message.updated, message.part.updated, message.part.delta, permission.asked/replied, question.asked, session.idle/created/updated/status/error`؛ تُدمج الأجزاء بحسب المعرّف وتُبنى قائمة رسائل مرتبة زمنيًا مع أدوات ومهام وفرق.
- **المدخلات:** نص المستخدم + مرفقات + (مزوّد/نموذج/وكيل/مساحة عمل/وكيل فرعي).
- **المعالجة:** إرسال `POST session/{id}/prompt_async` (OpenCode) أو ما يكافئه؛ ثم متابعة SSE + **استقصاء احتياطي كل 3 ثوانٍ بمهلة 120 ثانية**؛ كشف التعطّل بعد 150 ثانية بدون تقدّم مع تشخيص (`SessionStallDiagnosis`).
- **المخرجات:** `ChatUiState` كامل (رسائل/أجزاء/حالة/أخطاء/تشخيص).
- **الاعتماديات:** Coroutines/Flow، OkHttp-SSE، MarkdownLite، Bitmap مضمّن للصور.
- **الإنترنت:** نعم للران‑تايم البعيد/مزوّد النموذج.
- **الحالة:** 🟢 مكتملة وواسعة الاختبار (أكبر ملف اختبار 2061 سطرًا).

### 9.7 موافقات الأدوات والأسئلة — 🟢 مكتملة

- **الموقع:** `ChatViewModel` (permissions/pendingQuestions)، `QuestionCard.kt`، `RuntimeNotificationHelper.kt`، `core/notification/PermissionActionReceiver.kt`.
- **العمل:** إشعار بثلاثة أزرار (مرة/دائمًا/رفض) + بطاقة داخل الشاشة؛ الأسئلة تُعرض كبطاقات بخيارات متعددة وتُرسل عبر `answerQuestion`/`rejectQuestion`.
- **الأمان:** لا موافقة تلقائية افتراضيًا (`autoAcceptPermissions=false`)؛ الإغلاق الآمن عند رفض الإذن من النظام (`canPostNotifications`).
- **الحالة:** 🟢 مكتملة.

### 9.8 مساحات العمل ومتصفح الملفات وGit — 🟢 مكتملة جزئيًا (تبويب الطرفية داخل المستكشف 🔵)

- **الموقع:** `feature/workspace/{WorkspacesScreen,WorkspaceExplorerScreen,CodeViewerScreen,TerminalScreen,WorkspaceTabManager,RuntimeFolderBrowser,LocalRuntimeManagementScreen}.kt` (1017 و917 سطرًا للشاشتين الأولى).
- **الميزات:** قائمة ملفات، بحث نصي (`searchText`)، تبويب تغييرات Git + الالتزامات، تبويب PR، مبدّل فروع، عارض فرق موحّد/مقسّم، عارض كود بتلوين (SyntaxThemes).
- **الحالة:** 🟢 للوظائف الأساسية؛ 🔵 **تبويب الطرفية داخل `WorkspaceExplorerScreen` واجهة فقط** (`TerminalTabPlaceholder` = حقل نص لا ينفّذ شيئًا) بينما الطرفية الحقيقية متاحة من زر منفصل يقود إلى `ROUTE_TERMINAL` → `TerminalScreen` + `TerminalViewModel` (تنفيذ فعلي عبر `LocalRuntimeCommandRunner` مع قفل الإيقاف). كما أن شرائح `WorkspaceDeckRow` بلا فعل (`onClick = {}`) و`tab_content_placeholder` نص بديل.

### 9.9 الطرفية المدمجة — 🟢 مكتملة

- **الموقع:** `feature/workspace/TerminalViewModel.kt` + `TerminalScreen.kt`؛ التنفيذ داخل البيئة عبر `LocalRuntimeCommandRunner` (قفل قراءة/كتابة مع عمليات التثبيت/التحديث، مهل زمنية، إيقاف العملية).
- **الحالة:** 🟢 مع قيد: أوامر محدودة المدة (ليست طرفية تفاعلية كاملة/PTY للمستخدم النهائي).

### 9.10 جدولة المهام — 🟢 مكتملة

- **الموقع:** `feature/schedule/*` (14 ملفًا)، `data/schedule/{ScheduleRepository,ScheduleModels}.kt`، `assets/scripts/mushreacode-schedule-mcp.py`.
- **العمل:** محلّل Cron خاص (يدعم `*`, `a-b`, `*/n`, قوائم، ومعالجة `0/7=الأحد`، بحث حتى 5 سنوات) → `AlarmManager.setExactAndAllowWhileIdle` (أو `setAndAllowWhileIdle` إن مُنعت المنبّهات الدقيقة، مع بطاقة إرشاد للمستخدم) → `ScheduleExecutionService` (خدمة أمامية) تشغّل البيئة المحلية عند الحاجة، تنشئ جلسة، ترسل الطلب تراقب SSE حتى النهاية → تسجيل النتيجة + إعادة تسليح المنبّه + سياسة إعادة محاولة (`ScheduleRetryPolicy`) + حارس زمني (`ScheduleRunWatchdog`) + إعادة الجدولة عند `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`/`TIMEZONE_CHANGED`.
- **التخزين:** مُشفَّر (EncryptedSharedPreferences) لأن الطلب قد يحوي أسرارًا؛ ترحيل من تخزين قديم غير مشفّر؛ `reconcileStaleRuns()` عند الإقلاع.
- **الحالة:** 🟢 مكتملة (اختبارات كثيفة: Bridge/Coordinator/RetryPolicy/Watchdog/Models).

### 9.11 وكيل الجهاز (Device Agent) — 🟢 مكتملة

- **الموقع:** `device/*` (17 ملفًا أساسيًا) + `MushreaCodeAccessibilityService`، `DeviceAgentActivity`، `DeviceAgentBridge.kt` (833).
- **العمل:**
  - الوكيل (الذي يعمل داخل PRoot) لا يستطيع رؤية USB؛ لذا يستخدم **جسر ملفات**: يكتب `.mushrea-code/device-command.json` في مساحة العمل → خدمة Accessibility في التطبيق تستهلك الأمر → تنفّذه → تكتب `device-result.json`.
  - كل إجراء يمرّ بـ`DeviceActionFirewall` أولًا: **89 إجراءً = 57 AUTO + 32 CONFIRM** (عدد محسوب من الكود). حساسية النقر تُرفع إلى CONFIRM عند وجود كلمات مثل الدفع/الحذف/الإرسال، ولا يمكن تحويل إجراءات STRONG إلى تلقائي.
  - **وضع القراءة فقط افتراضي** بقائمة صريحة من 39 إجراءً قرائيًا؛ أي إجراء غير مدرج يُرفض برسالة.
  - سجل نشاط محدود + **تصدير تدقيق** (`audit_export`) يحوي الإجراء/النتيجة/الملخص/الوقت فقط دون المعاملات أو محتوى الملفات أو رمز الفتح (مُثبَّت باختبار).
  - إيقاف طارئ: `StopAgentReceiver` + اختصار على الشاشة الرئيسية + `StopAgentActivity` + كلمات إيقاف (`StopPhrases`).
- **المدخلات:** أوامر من 88 أداة MCP.
- **الصلاحيات:** Accessibility (منح يدوي)، POST_NOTIFICATIONS (للتأكيدات)، وصول الملفات للعمليات الملفية.
- **الإنترنت:** لا (محلي بالكامل).
- **الحالة:** 🟢 مكتملة على مستوى الكود؛ **غير مُختبرة على جهاز حقيقي هنا**.
- **الأدلة:** `DeviceActionFirewall.kt:168-262` (المجموعات)، `DeviceAgentBridge.kt:300-380` (خريطة التنفيذ)، `DeviceCommandCodecTest`/`DeviceActionFirewallTest`، `docs/DEVICE_AGENT.md`.

### 9.12 USB (ADB / Fastboot / MTP / تسلسلي / HID) — 🟢 مع أجزاء 🟡 و🔵

| الميزة | الحالة | الدليل |
|---|---|---|
| ADB عبر USB (بروتوكول مُنفَّذ داخليًا) | 🟢 | `device/usb/{AdbClient,AdbProtocol,AdbSync,UsbTransport,AdbKeys}.kt` + اختبارات |
| shell/list/pull/push/install/logcat/screenshot | 🟢 | `UsbExecutor.kt` (721) |
| ADB لاسلكي (tcpip + إعادة اتصال تلقائي) | 🟢 | `TcpTransport.kt`، `runtime/local/AdbConnectionManager.kt` |
| منافذ تسلسلية (CH340/FTDI/CP210x…) | 🟢 | `UsbSerialAgent/UsbSerialExecutor` + مكتبة `usb-serial-for-android` |
| Fastboot `getvar` (قراءة فقط) | 🟢 | `FastbootAgent.kt` + `TermuxCommandPolicy` يرفض flash/erase/oem |
| MTP: عرض + تنزيل | 🟢 | `usbhub/MtpAgent.kt` |
| MTP: رفع (upload) | 🟡 غير موجود | لا أداة `mtp_upload` في خادم MCP |
| HID: قراءة خام | 🟡 (لا فك ترميز مفاتيح) | أداة `hid_read` فقط |
| الكاميرات: عرض القائمة فقط | 🟡 (لا التقاط) | أداة `camera_list` فقط |
| مشغّل USB Hub + تخزين قابل للإزالة | 🟢 | `usbhub/{HubExecutor,UsbHub}.kt` |
| Termux bridge (fastboot المصحّح + أداة miunlock) | 🟢 للقراءة، 🔴 للمرحلة الكتابية (مقصود) | `device/termux/*`، `docs/ON_DEVICE_AGENT.md` |

### 9.13 المرآة والتحكم بهاتف آخر — 🟢 مكتملة

- **الموقع:** `device/mirror/{ScreenMirrorSession,MirrorDecoder,MirrorActivity,H264AccessUnitSplitter}.kt` (مرآة عرض فقط عبر `screenrecord --output-format=h264` مع إعادة فتح كل ~3 دقائق)، و`{ScrcpySession,ScrcpyControlMessages,RemoteControlActivity}.kt` (تحكم كامل: يدفع `scrcpy-server-4.0` إلى `/data/local/tmp` ويشغله عبر `app_process` مع ثلاث قنوات ADB: فيديو/تحكم/…).
- **الصلاحيات:** USB host + ADB debugging على الجهاز الآخر.
- **الحالة:** 🟢 مكتملة (اختبارات: `H264AccessUnitSplitterTest`, `ScrcpyControlMessagesTest`, `ScreenMirrorCommandTest`)؛ **غير مُختبرة على جهاز ثانٍ حقيقي من قِبَلي**.

### 9.14 SSH / SMB / FTP / WebDAV / mDNS — 🟢 (غير مُختبرة على عتاد حقيقي)

- **الموقع:** `device/ssh/{SshAgent,SshExecutor}.kt` (sshj + SFTP + SCP بتثبيت TOFU في `filesDir/remote/scp_known_hosts.json`)، `device/remote/{RemoteExecutor,RemotePaths,WebDavListing}.kt` (smbj + FTP + WebDAV + mDNS `net_browse` بـ7 أنواع خدمات).
- **الملاحظة الموثّقة بصدق في `HANDOFF.md`:** «لم يُختبر ضد عتاد Windows/NAS حقيقي — توقّع تقارير أخطاء من شبكات حقيقية». هذه **استنتاج من وثيقة داخلية، وليست حقيقة تحققتُ منها**.
- **الحالة:** 🟢 على مستوى الكود، 🟡 على مستوى التحقق الميداني. لا اختبارات وحدة لهذه الملفات (لا يوجد `SshAgentTest`/`RemoteExecutorTest`).

### 9.15 الشبكة وBluetooth — 🟢

- `device/network/NetworkExecutor.kt`: `wifi_info, dns_lookup, net_ping, port_check, http_request, websocket` (الأخير ببثّ ردود محدود المدة).
- `device/bluetooth/BluetoothExecutor.kt`: `bt_info, bt_devices, bt_scan, ble_scan` مع تعامل صريح مع صلاحيات Android 12+ وإخفاء SSID بلا إذن موقع.
- **لا اختبارات وحدة** لهذين الملفين.

### 9.16 وكيل المكالمات (Call Agent) — 🟢 مكتملة وغير موثّقة في README

- **الموقع:** `device/call/*` (14 ملفًا): `CallAgentService` (خدمة أمامية `microphone|phoneCall`)، `ConversationEngine` (حلقة محادثة خالصة قابلة للاختبار)، `CallPolicy` (سلم أمان)، `AgentCallBrain` + `OpenCodeCallChannel` (يستعين بالنموذج عند الحاجة)، `PhoneCallController` (Telecom/AudioManager حصريًا)، `IncomingCallReceiver` + `CallStateMachine` + `CallSummary` + `CallAgentStore` + `CallAgentActivity`.
- **المدخلات:** أمر طبيعي بالعربية/الإنجليزية (مثال من أدوات الوكيل: «اتصل بأحمد واسأله أين هو»).
- **المعالجة:** يقدّم نفسه كمساعد آلي، يعمل على أهداف مرتّبة، لا يخترع معلومات، يصعّد الطلبات الحساسة، ويصمت بصدق بعد دورين بلا رد.
- **الأمان:** كلمات OTP/كلمات المرور **لا تُنطق إطلاقًا**؛ لا تسجيل صوتي للاتصال (لا يوجد تنفيذ للتسجيل إطلاقًا)؛ التركيز/الإجابة الواردة تتطلبان دور الاتصال الافتراضي (`ROLE_DIALER`) مع تدهور صريح عند غيابه.
- **الصلاحيات:** CALL_PHONE، ANSWER_PHONE_CALLS، READ_PHONE_STATE، READ_CONTACTS، READ_CALL_LOG، MANAGE_OWN_CALLS، FOREGROUND_SERVICE_MICROPHONE/PHONE_CALL، RECORD_AUDIO.
- **الحالة:** 🟢 مكتملة على مستوى الكود؛ اختبارات: `AgentCallBrainTest`, `CallAgentCoreTest`؛ **غير مُختبرة بمكالمة حقيقية هنا**.

### 9.17 الصوت: إملاء + قراءة + كلمة تنبيه — 🟢

- **الإملاء والقراءة:** `feature/assistant/{SpeechRecognizerManager,TTSManager,AndroidTTSProvider,CloudTTSProvider,TTSProvider,TtsTuning,TtsPreview}.kt`؛ ثلاثة مزوّدين: Android TTS، OpenAI TTS (`gpt-4o-mini-tts`)، ElevenLabs (`eleven_multilingual_v2`) مع مفاتيح محفوظة في التخزين المشفّر.
- **المساعد الافتراضي:** `MushreaCodeVoiceInteractionService` + `MushreaCodeSessionService` + `MushreaCodeVoiceSession` (واجهة Compose داخل جلسة الصوت) + `MushreaCodeRecognitionService` (خدمة تعرّف).
- **كلمة التنبيه:** Vosk (`VoskWakeWordDetector`, `VoskModelInstaller`, `VoskModelStore`, `WakeWordService` خدمة أمامية بميكروفون، + `BargeInPolicy` لمقاطعة القراءة). النماذج تُنزّل عند الطلب (~40MB EN / ~48MB JA) من `alphacephei.com` عبر HTTPS.
- **الحالة:** 🟢 مكتملة، **لكن مع ثغرة تحقق**: لا يوجد تحقق SHA-256 أو توقيع لنموذج Vosk المنزّل (بخلاف أصول الران‑تايم). سكربت `scripts/sign_wakeword_pack.py` موجود لكنه **يتيم** — يذكر `WakeWordPackManager` غير الموجود في المشروع.
- **الصلاحيات:** RECORD_AUDIO، FOREGROUND_SERVICE_MICROPHONE، POST_NOTIFICATIONS، WAKE_LOCK.

### 9.18 الويدجت والمشاركة والاختصارات — 🟢

- `feature/widget/{QuickInputWidgetProvider,QuickInputActivity}.kt` (layout XML `widget_quick_input.xml` + `quick_input_widget_info.xml`) لكتابة/إملاء طلب مباشرة من الشاشة الرئيسية وإنشائه عبر جلسة جديدة.
- `feature/share/ShareReceiverActivity.kt`: هدف مشاركة نص/ملف/عرض محتوى → ينسخ الملفات إلى `Download/Mushrea-imports` ويبني طلبًا جاهزًا.
- `xml/device_shortcuts.xml`: اختصار «إيقاف الوكيل».

### 9.19 المتصفح الضيف + خادم MCP للمتصفح — 🟢

- `feature/browser/{GuestBrowserScreen,BrowserCommandWatcher}.kt`: WebView داخل التطبيق يراقب `.mushrea-code/browser-command.json`، و`assets/scripts/mushreacode-browser-mcp.py` (428 سطرًا) يتصل بـ`webview_devtools_remote_<pid>` عبر CDP لتنفيذ `browser_show/navigate/click/type/screenshot/info/status` (7 أدوات).

### 9.20 GitHub والتكامل — 🟢

- **OAuth Device Flow:** `feature/settings/GitHubAuthRepository.kt` (طلب جهاز → استقصاء)، مع إعادة محاولة عند `UnknownHostException`.
- **API:** `core/api/GitHubApiClient.kt` (PRs + بحث مشكلات) مع Token من التخزين المشفّر، و`PullRequestStatusRepository` لمزامنة حالة PRs (draft/open/conflict/merged/closed) وعرضها أعلى المحرّر.
- **مطالبات التقييم (Star prompt):** `feature/support/{GitHubStarSupport,GitHubStarUi}.kt` + منسّق في التطبيق (ظهور أول + مطالبة ثانية بعد جلسة مكتملة).
- **ملاحظة:** `GitHubForgeClient`/`GitLabForgeClient`/`GiteaForgeClient` في `core/api/ForgeClient.kt` **غير مستخدمة إطلاقًا**، إذ يعتمد الكود على `GitHubApiClient` مباشرة — كود ميت (انظر 22).

### 9.21 الطلبات إلى الران‑تايم: MCP لكل وكيل — 🟢

- ثلاثة خوادم MCP مكتوبة بايثون داخل الضيف (بدون اعتماديات)، تُثبَّت في `/usr/local/bin`:
  - `mushreacode-device-mcp.py` (1495 سطرًا، **88 أداة، 88 معالجًا** — تحقّقتُ برمجيًا من التطابق التام).
  - `mushreacode-browser-mcp.py` (428 سطرًا، 7 أدوات).
  - `mushreacode-schedule-mcp.py` (290 سطرًا، 8 أدوات) عبر جسر `pending/`↔`responses/`.
- تُسجَّل تلقائيًا في إعدادات كل وكيل: OpenCode (`~/.config/opencode/opencode.json` → مفتاح `mcp`)، Claude (`~/.claude.json` → `mcpServers`)، Antigravity (`~/.gemini/config/mcp_config.json`)، أما Codex فتُدار عبر `codex mcp`.
- كما تُكتب **تعليمات سياق الوكيل** `mushrea-code-agent-context.md` في `CLAUDE.md`/`GEMINI.md`/opencode instructions مع حماية ذكية ضد الكتابة فوق تعديلات المستخدم (بصمة SHA-256 + منع متابعة الروابط الرمزية خارج الـrootfs).

### 9.22 تحليل OTA وحمايتها (payload) — 🟢 (تحليل فقط، بلا تفليش)

- `device/payload/{PayloadArchive,PayloadExecutor,PayloadGuard}.kt`: قراءة وفك ضغط XZ، التحقق من سلامة ZIP/TGZ، استخراج `payload.bin`، و«حاجز الروم» يقارن اسم الجهاز المُعلن (`pre-device`, `updater-script`, اسم مجلد fastboot) مع الجهاز المتصل، ويضع `blocking` عند عدم التطابق، مع `safety_preflight` يفصل «التذكيرات» عن «الفحوص القابلة للتحقق»، و`XiaomiUnlock` يوجّه للمسار الرسمي فقط.

### 9.23 التهيئة الأولى (Onboarding) — 🟢

- `OnboardingGate.kt` + `feature/onboarding/{OnboardingChoiceScreen,AndroidSetupScreen}.kt` (1427 سطرًا): اختيار (جهاز هذا الأندرويد / اتصال بحاسوب) → خطوات: ABI → تنزيل البيئة → اختيار الوكلاء → أدوات التطوير → مزوّدو النماذج (تسجيل دخول/OAuth/مفتاح API) → GitHub (اختياري) → جاهز. مع معاينات Compose واختبارات.

### 9.24 الإعدادات (شاشاتها) — 🟢

- الصوت/المساعد، المظهر (7 سِمات، أحجام خطوط، ثيم الأكواد)، المحادثة (تفصيل الأدوات، سلوك الإرسال، Enter للإرسال)، الجلسات (أرشفة تلقائية بالعمر/العدد)، النظام (الوكلاء، المزوّدون، MCP، معلومات الخادم، الران‑تايم المحلي، المتصفح الضيف، اتصال بعيد، مساحة العمل، وكيل الجهاز، الإشعارات، التحليلات (opt-in)، الإيقاف عند الخمول، التشخيص، دعم المشروع، المستندات القانونية).

---

## 10. تحليل جميع الشاشات

| # | الشاشة | الوصول | الغرض | العناصر الرئيسية | مصدر البيانات | الحالة |
|---|---|---|---|---|---|---|
| 1 | Onboarding | أول تشغيل (`onboardingCompleted=false`) | اختيار مسار الإعداد | بطاقتان (جهاز/حاسوب) | `SecureSettingsRepository` | 🟢 |
| 2 | Android Setup | من الـOnboarding أو من مساحة العمل | تثبيت البيئة + الوكلاء + تسجيل الدخول + GitHub | 6 خطوات، أزرار بدء/إلغاء، أشرطة تقدم، قائمة وكلاء، منتقي مزوّد | `LocalRuntimeManager`, `RuntimeCatalogRepository`, `GitHubAuthRepository` | 🟢 |
| 3 | Remote Connection | من مساحة العمل/الإعدادات | إضافة اتصال PC | حقول URL/مستخدم/كلمة، بحث LAN، QR، خيار عدم الأمان | `LanDiscovery`, `ConnectionProfile` | 🟢 |
| 4 | **Chat** (الرئيسية) | المسار الافتراضي | المحادثة | رأس (ران‑تايم/نموذج/وكيل)، Drawer، قائمة جلسات، رسائل، بطاقات أدوات/استدلال، شريط مهام، شريط PRs، مرفقات، محرّر مع أوامر Slash ومؤشر السياق، بطاقات صلاحيات/أسئلة، بطاقات خطأ/تعطّل | `ChatViewModel` + `RuntimeActivityRepository` | 🟢 |
| 5 | Workspaces | Drawer | إدارة الران‑تايم والمجلدات | بطاقات الران‑تايم المحلي لكل وكيل، اتصالات PC، إضافة مشروع (SAF/مجلد)، إدارة التخزين | `WorkspaceViewModel` | 🟢 |
| 6 | Local Runtime Management | من Workspaces | صحة/تشخيص/سجلات/تخزين/أدوات + ADB | بطاقات حالة، مقاييس، ذيل السجلات، أزرار فصل ADB | `LocalRuntimeDiagnosticsCollector` | 🟢 |
| 7 | Workspace Explorer | من بطاقة مساحة العمل | تصفح/بحث/تغييرات/PR + فتح طرفية | تبويبات (Files/Search/Changes/PR)، شرائح، بطاقات تغيير، فرق موحد/مقسّم | `OpenCodeBackend` (files/vcs) | 🟢 (تبويب الطرفية الداخلي 🔵) |
| 8 | Code Viewer | من متصفح الملفات | عرض كود بتلوين | LazyColumn + تمييز سطور | `readFile` | 🟢 |
| 9 | Terminal | من مستكشف مساحة العمل (زر) | تنفيذ أوامر في البيئة | سجل، إدخال، مفاتيح سريعة، إيقاف | `LocalRuntimeCommandRunner` | 🟢 |
| 10 | Guest Browser | من الإعدادات أو أمر الوكيل | عرض صفحة محلية (dev server) والتفاعل | شريط عنوان + WebView + أزرار رجوع | WebView + CDP | 🟢 |
| 11 | Schedules | Drawer | إدارة الجداول | قائمة، تشغيل الآن، تمكين، منبّه دقة، قائمة اختيارات | `ScheduleViewModel` | 🟢 |
| 12 | Schedule Detail | من القائمة | تفاصيل + سجل التشغيل | بطاقات تشغيل، أخطاء، إعادة تشغيل | `ScheduleRepository` | 🟢 |
| 13 | Schedule Editor | من القائمة/التفاصيل | إنشاء/تعديل | اسم، مساحة، وكيل/مزوّد/نموذج، Cron أو وقت، منبّهات | `ScheduleRepository` | 🟢 |
| 14 | Settings (V2) | Drawer | مركز الإعدادات | 6 أقسام بـ20+ صفًا | `SettingsViewModel` | 🟢 |
| 15 | Voice Settings | من الإعدادات | الصوت/المساعد/كلمة التنبيه | اختيار مزوّد TTS ومفاتيح، معدّلات، هدف المساعد، مساحة/نموذج، قسم Vosk | `SecureSettingsRepository`, `VoskModelStore` | 🟢 |
| 16 | Agents Settings + صفحة لكل وكيل (OpenCode/Claude/Antigravity/Codex) | الإعدادات | تشغيل/تحديث/تسجيل دخول/مقاييس | أزرار تشغيل/إيقاف/إعادة، بطاقة تحديث، إصدار، حالة تسجيل | `*Controller`, `*Runtime` | 🟢 |
| 17 | Providers | الإعدادات | مزوّدو النماذج | قائمة مزوّدين، OAuth/API key، مزوّد مخصّص (حوار) | `RuntimeCatalogRepository` | 🟢 |
| 18 | MCP (+3 شاشات لكل وكيل) | الإعدادات | إدارة خوادم MCP | بطاقات، إضافة/إزالة/توصيل، حوار كود تفويض | `McpViewModel` | 🟢 |
| 19 | Model Visibility | الإعدادات | إخفاء/تثبيت نماذج | قوائم قابلة للتبديل | `AppPreferencesRepository` | 🟢 |
| 20 | System Prompt | الإعدادات | إعدادات تعليمات مسبقة | قائمة إعدادات + حوار تحرير | `SystemPromptStore` | 🟢 |
| 21 | Server Info | الإعدادات | معلومات الخادم | تبويبات (Providers/Commands/Skills/Config) | `OpenCodeBackend` | 🟢 |
| 22 | GitHub | الإعدادات | ربط الحساب | Device code + حالة الحساب | `GitHubAuthRepository` | 🟢 |
| 23 | Legal + Legal Document | الإعدادات | مستندات قانونية بلا إنترنت | قائمة + عارض Markdown/أسطر | `assets/legal/*` | 🟢 |
| 24 | Diagnostics (Sheet) | الإعدادات | تشخيص الران‑تايم/النسخ | مقاييس، سجلات، أزرار نسخ/تصدير | `LocalRuntimeDiagnosticsCollector` | 🟢 |
| 25 | **Device Agent** (Activity مستقلة) | من الإعدادات أو أيقونة التطبيق الثانية | تحكّم وكيل الجهاز | حالة Accessibility، فتح إعدادات، جدار حماية، سجل نشاط، إيقاف طارئ، بطاقة USB، تشخيصات، وضع قراءة فقط، إقرار مخاطر | `DeviceAgentStore` | 🟢 |
| 26 | **Call Agent** (Activity) | من شاشة وكيل الجهاز / إشعار المكالمة | إدارة المكالمة | الحالة، الأهداف، الردود، TAKE OVER/END/STOP، قواعد الوارد، الخصوصية، الهوية | `CallAgentStore` | 🟢 |
| 27 | Mirror (Activity) | من أداة mirror | مرآة عرض فقط | SurfaceView + حالة | `ScreenMirrorSession` | 🟢 |
| 28 | Remote Control (Activity) | من أداة scrcpy | تحكم كامل | SurfaceView + إدخال لمس + شريط أزرار | `ScrcpySession` | 🟢 |
| 29 | Quick Input (Activity شفافة) | ويدجت | طلب سريع + إملاء | EditText + زر ميكروفون | `PromptRequest` مباشر | 🟢 |
| 30 | Share Receiver (Activity شفافة) | مشاركة من أي تطبيق | تحويل المشاركة إلى طلب | تحليل Intent | `AttachmentImporter` | 🟢 |

**شاشات/واجهات موجودة في الكود وغير مرتبطة بالتنقل:**
- `TerminalTabPlaceholder` (داخل مستكشف مساحة العمل) — واجهة فقط.
- `TabletSettingsLayout` — **غير مُستدعاة إطلاقًا** (لا تخطيط للتابلت فعليًا).
- `TerminalTabPlaceholder` و`BranchSwitcherSheet` مرتبطان؛ أما `DragDropAttachHelper` و`VoiceActivityDetector` و`KeepAwakeHelper` فغير مرتبطة بأي شاشة.

---

## 11. تدفق البيانات

### 11.1 التخزين كمصدر حقيقة

| البيانات | المكان | الشكل |
|---|---|---|
| اتصالات OpenCode + المفتاح/كلمة المرور/التثبيت | EncryptedSharedPreferences (`opencode_android_secure_settings`) | JSON مشفّر |
| مفاتيح مزوّدي النماذج، TTS، GitHub، تفضيلات، مسودات، مسارات SAF | نفس الملف المشفّر | مفاتيح/JSON |
| سجل الجلسات المعروض | **خادم الران‑تايم** (OpenCode/CLI)، لا قاعدة بيانات محلية | حالة في الذاكرة (`RuntimeCatalogRepository`) مع كاش كتالوج مزوّدين على القرص (`filesDir/catalog-cache`) |
| الجداول وسجل تشغيلها | EncryptedSharedPreferences منفصلة | JSON |
| حالة وكيل الجهاز | `filesDir/device-agent/*.json` (مساحة العمل النشطة، الإعدادات، سياق، سجل، علم الإيقاف) | JSON |
| كلمة التنبيه | `filesDir/vosk/<model>` | ملفات نموذج |
| الران‑تايم نفسه | `filesDir/runtime/{rootfs,antigravity-rootfs,workspace,logs,proot-tmp}` | نظام ملفات |
| تصدير التدقيق | مساحة خاصة + مشاركة عند الطلب | JSON |
| سجل آخر تعطل | ملف داخلي (`CrashLog`) | نص |

### 11.2 تدفق الرسائل (موسّع)

```
User Input → ChatComposer (ChatHomeScreen.kt)
 → ChatViewModel.send(): التحقق من الاتصال/الران‑تايم + معالجة أوامر Slash + مرفقات + مسودة
 → Backend.sendMessage()  [5 تنفيذات مختلفة]
 → الأحداث: SSE (محلي/بعيد) أو أسطر stream-json (Claude/Antigravity) أو JSON-RPC (Codex)
 → OpenCodeEventParser → OpenCodeEvent (12 نوعًا) → RuntimeActivityRepository (تجميع + كشف تعطّل)
 → ChatViewModel.reduce(...) → ChatUiState (رسائل/أدوات/مهام/صلاحيات/أسئلة/أخطاء/تشخيص)
 → Compose (ChatHomeScreen + ChatMessageComponents + AssistantActivityRow)
 → إشعارات (نهاية/خطأ/سؤال/موافقة) عبر RuntimeNotificationHelper
```

### 11.3 تدفق أمر جهاز

```
الوكيل → أداة MCP (device_tap مثلاً) → كتابة device-command.json
 → DeviceAgentBridge (استقصاء كل ~0.3s داخل مساحة العمل النشطة)
 → DeviceActionFirewall.levelFor  →  AUTO: تنفيذ مباشر | CONFIRM: إشعار/حوار ثم DeviceConfirmReceiver
 → التحقق من وضع القراءة فقط + علم الإيقاف الطارئ
 → Executor المناسب (DeviceNavigator/DeviceFileAgent/UsbExecutor/…)
 → كتابة device-result.json + تحديث السياق + سجل النشاط
 → الأداة تُعيد ملخّصًا للوكيل
```

### 11.4 تدفق التثبيت

```
Workspaces → "تثبيت" → LocalRuntimeService.ACTION_INSTALL_AND_START (ForegroundService)
 → LocalRuntimeInstaller.install(): ABI → تنزيل (تحقق SHA-256) → staging
 → proot: apk add <حزم> → تثبيت الوكلاء المختارين → كتابة metadata.json
 → swap ذرّي → تشغيل الخادم (127.0.0.1:4097) → RuntimeAutoStart → RuntimeRegistry.select
 → التقدّم يُبثّ عبر LocalRuntimeStatus.Installing(progress, step, agent) للواجهة
```

---

## 12. قاعدة البيانات والتخزين

### 12.1 Room — **مُعرَّفة لكن غير مستخدمة (كود ميت مؤكَّد)**

- `data/local/SessionDatabase.kt` (الإصدار 1، `exportSchema=false`) بجدولين:
  - `sessions(id PK, title, directory, createdAt, updatedAt, providerId, modelId)`
  - `messages(id PK, sessionId FK→sessions.id ON DELETE CASCADE, role, text, createdAt)` + فهرس على `sessionId`
- `SessionDao` بثماني عمليات، و`SessionCacheRepository` يغلّف DAO.
- **الدليل على أنها ميتة:** `grep -R "SessionDatabase|SessionCacheRepository("` لا يُظهر أي `Room.databaseBuilder` ولا أي استخدام خارج دائرة `data/local` نفسها؛ والمستودعات الفعلية (`RuntimeCatalogRepository`, `ChatViewModel`) تقرأ الجلسات من الران‑تايم مباشرة. كما أن ملفات `data/local` هي الخمسة الوحيدة التي تشير إلى بعضها.
- **الأثر:** ثلاث تبعيات غير ضرورية (`room-runtime`, `room-ktx`, وKSP `room-compiler`) تزيد زمن البناء وحجم الـAPK قليلًا؛ ولا هجرات (Migrations) ولا فهارس إنتاجية.
- **توصية:** إما إكمال مسار الكاش المحلي (إن كان مقصودًا للاستخدام بلا شبكة)، أو حذف الطبقة والتبعيات.

### 12.2 EncryptedSharedPreferences

- مفتاح رئيسي AES-256-GCM عبر `MasterKey`، وترميز مفاتيح AES-256-SIV/قيم AES-256-GCM.
- يحتوي الحساس: كلمات مرور الاتصال، رموز GitHub، مفاتيح مزوّدي النماذج، مفاتيح TTS، مفتاح OpenAI/ElevenLabs، أوامر الجداول، معرّف مساحة العمل، الجلسات غير المقروءة.
- `data_extraction_rules.xml` يستثني `sharedpref` و`database` من النسخ السحابي والنقل، و`allowBackup=false`.
- ملاحظة: `androidx.security:security-crypto:1.1.0-alpha06` — إصدار ألفا (انظر المخاطر).

### 12.3 ملفات على القرص

`filesDir/runtime/…` (rootfs, logs محدودة بـ1MB لكل ملف تشغيل، workspace, proot-tmp)، `filesDir/device-agent/…`، `filesDir/vosk/…`، `filesDir/catalog-cache/`، `filesDir/remote/scp_known_hosts.json`، مساحة العمل المشتركة `Download/Mushrea-imports` (من هدف المشاركة) و`Download/Mushrea-payload` (استخراج الأقسام).

### 12.4 دورة حياة البيانات

- لا نسخ احتياطي سحابي (مقصود).
- حذف الران‑تايم (`ACTION_DELETE`) يمسح `runtime/`؛ الحذف الجزئي لكل وكيل مدعوم (`LocalRuntimeMetadata.without`).
- حذف الجلسات يتم على الران‑تايم (`DELETE session/{id}`) وليس محليًا.

---

## 13. الشبكة وواجهات API

### 13.1 OpenCode REST (المحلي والبعيد)

`core/api/OpenCodeApiClient.kt` (الأساس: URL المستخدم + بادئة `…/`)، وأهم النقاط (كما في الكود):

| المجموعة | النقاط |
|---|---|
| صحة | `GET global/health` |
| جلسات | `GET/POST session`, `GET/PATCH/DELETE session/{id}`, `DELETE session/{id}/message/{messageId}`, `POST session/{id}/abort`, `POST session/{id}/summarize` |
| رسائل/أوامر | `POST session/{id}/prompt_async`, `GET session/{id}/message`, `GET session/{id}/todo`, `GET session/{id}/diff`, `GET command`, `GET skill` |
| مزوّدون | `GET provider`, `GET/POST provider/auth`, `POST auth/{id}`, `DELETE auth/{id}` |
| مشروع/ملفات | `GET project/current`, `GET path`, `GET file`, `POST file/…`, `GET find`, `GET vcs`, `POST vcs/diff` |
| MCP | `GET/POST mcp`, `POST mcp/{name}/connect|disconnect|auth`, `POST mcp/{name}/auth/callback`, `DELETE mcp/{name}/auth` |
| إعدادات | `GET/PATCH config` |
| أحداث | `GET event` (SSE) — يدعم أيضًا مسارًا بديلًا للأحداث العامة (GLOBAL_EVENT_PATH) مع إعادة اتصال وbackoff |

- **المصادقة:** Basic Auth (`username`/`password`) عبر `Credentials.basic`، ولا تُرسل بيانات الاعتماد إلا للطلبات غير العامة (تُستثنى مسارات `auth/…` و`provider/auth` و`oauth/…`).
- **الأمان:** `CertificatePinner` عند تحديد `pinSha256` مع HTTPS.
- **الأخطاء:** استثناءات مع رسائل منسّقة؛ لا Retry تلقائي على مستوى الطلب عدا SSE (إعادة اتصال متدرجة حتى سقف).

### 13.2 GitHub

- `POST https://github.com/login/device/code` + `POST https://github.com/login/oauth/access_token` (Device Flow) بمعرّف عميل من `BuildConfig.GITHUB_CLIENT_ID` (متغيّر بيئة `GITHUB_CLIENT_ID` أو خاصية Gradle عند البناء — **غير موجود داخل المستودع**).
- `GET https://api.github.com/…` لقائمة PRs/حالة PR/بحث مشكلات (Token اختياري من التخزين المشفّر).

### 13.3 خدمات خارجية أخرى

| الوجهة | الغرض | ملاحظة |
|---|---|---|
| `dl-cdn.alpinelinux.org` | Alpine minirootfs | SHA-256 مثبَّت |
| `github.com/anomalyco/opencode/releases` | ثنائي OpenCode | SHA-256 مثبَّت |
| `registry-1.docker.io` + `auth.docker.io` | طبقات Debian bookworm | SHA-256 مثبَّت |
| `deb.debian.org` | حزمة bsdutils | SHA-256 مثبَّت |
| `github.com/google-antigravity/antigravity-cli` | ثنائي agy | SHA-256 مثبَّت |
| `github.com/openai/codex` (عبر CodexReleaseClient) | ثنائي Codex | يوجد عميل إصدارات وحزمة اختبارات |
| `alphacephei.com/vosk/models` | نموذج كلمة التنبيه | **HTTPS فقط بلا تحقق تجزئة** |
| مزوّدو النماذج (Anthropic/OpenAI/Google/…) | عبر الوكلاء أنفسهم | لا يمرّ عبر خادم Mushrea |
| OpenAI TTS / ElevenLabs | قراءة الردود | مفاتيح من المستخدم |
| Firebase Analytics/Crashlytics | نسخة `github` فقط | مجموعة التحليلات معطّلة افتراضيًا |

**لا يوجد خادم خلفي (backend) خاص بالمشروع** — موثّق في README وPRIVACY و`docs/AUTHENTICATION_AND_DATA_FLOW.md` ويتوافق مع الكود.

### 13.4 بناء الجسور المحلية (بلا شبكة)

- جسر ملفات (`/workspace/.mushrea-code/*.json`) لأدوات الجهاز والمتصفح، وجسر مجلدات (`schedule-bridge/pending|responses`) للجداول، وجسر ملفات هوك Claude (`~/.mushreacode/claude-bridge`).
- Intents صريحة لجسر Termux (`com.termux.RUN_COMMAND`) مع `PendingIntent` خاص + `TermuxResultReceiver` غير مُصدَّر.

### 13.5 المراقبة والجودة

- `ConnectionQualityMonitor`: يقيس الاستجابة/الرموز في الثانية ويعطّل نفسه عند الخروج من المقدمة.
- `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE` مستخدمتان؛ لا مكتبة مراقبة شبكة ثالثة.

### 13.6 الأسرار المكتشفة (بلا كشف قيم)

| العنصر | المكان | ملاحظة آمنة |
|---|---|---|
| `app/google-services.json` | **مُلتزَم في Git** | يحتوي `project_id` + `project_number` + مفتاح API خاص بأندرويد (إعداد عميل Firebase، ليس سرًّا بالمعنى التقليدي لكنه معرّف عام للمشروع). لا يوجد فيه `oauth_client` ولا مفاتيح خوادم. |
| `GITHUB_CLIENT_ID` | يُمرَّر عند البناء من env/خاصية | غير مخزّن في المستودع ✅ |
| مفاتيح التوقيع | 4 أسرار GitHub (`RELEASE_KEYSTORE_*`) + env `AND_CODE_*` | غير مخزّنة في المستودع ✅ |
| كلمات مرور الاتصال/الرموز/مفاتيح المزوّدين | EncryptedSharedPreferences وقت التشغيل | غير مخزّنة في Git ✅ |

لا توجد أي مفاتيح سرّية فعلية مكتوبة في الكود المصدري وفق فحص الأنماط (`client_secret`/`api_key="…"` لم تُنتج أي نتيجة خارج الثوابت النصية لأسماء المفاتيح).

---

## 14. الصلاحيات وتحليلها

| الصلاحية | الغرض في المشروع | مستوى الخطورة | ملاحظة |
|---|---|---|---|
| INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE | الوكلاء والاتصال البعيد والتنزيلات | طبيعي | — |
| CHANGE_WIFI_MULTICAST_STATE | استكشاف mDNS/NSD | منخفض | — |
| RECORD_AUDIO | الإملاء + كلمة التنبيه + وكيل المكالمات | حساس | يتطلب موافقة صريحة، وإشعار دائم عند الاستخدام المستمر |
| POST_NOTIFICATIONS | إشعارات الموافقات/النهاية/الجدولة | منخفض (حساس على 13+) | يُفحص قبل الإرسال |
| MANAGE_EXTERNAL_STORAGE | لكي يرى الوكيل ملفات المستخدم كمسارات حقيقية | **مرتفع جدًا** | مبرَّر بتعليق مطوّل في Manifest؛ يمنع سياسات متجر Play العادية |
| READ/WRITE_EXTERNAL_STORAGE (بحدود SDK) | توافق Android 10 وما قبله | منخفض | `maxSdkVersion` مضبوط |
| FOREGROUND_SERVICE (+SPECIAL_USE/MICROPHONE/PHONE_CALL) | خدمات الران‑تايم/الجدولة/الصوت/المكالمة | متوسط | أنواع صحيحة مع `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` موثّق لكل خدمة |
| WAKE_LOCK | إبقاء الوكيل أثناء العمل (Leases فقط) | متوسط | يُدار مركزيًا في `RuntimeWorkTracker` |
| RECEIVE_BOOT_COMPLETED | إعادة تسليح الجداول/الران‑تايم | منخفض | مشروط بإعدادات المستخدم |
| SCHEDULE_EXACT_ALARM | منبّهات دقيقة للجداول | منخفض | مع مسار تدهور + بطاقة إرشاد |
| BLUETOOTH_SCAN/CONNECT + القديمة + الموقع (≤30) | أدوات Bluetooth | متوسط | `neverForLocation` مطبَّقة |
| CAMERA | مسح QR للاتصال | متوسط | — |
| CALL_PHONE / ANSWER_PHONE_CALLS / READ_PHONE_STATE / READ_CALL_LOG / READ_CONTACTS / MANAGE_OWN_CALLS | وكيل الاتصال | **مرتفع** | صلاحيات مقيّدة في Play؛ القدرات الناقصة (الرد/الإنهاء) تُكتشف ولا تُلتفّ |
| com.termux.permission.RUN_COMMAND | جسر Termux | متوسط | منح يدوي من Termux + فحص `allow-external-apps` |
| BIND_ACCESSIBILITY_SERVICE | محرّك وكيل الجهاز | **مرتفع جدًا** | منح يدوي من إعدادات النظام + وضع قراءة فقط + سجل تدقيق + إيقاف طارئ |
| BIND_VOICE_INTERACTION / BIND_RECOGNITION_SERVICE | المساعد الافتراضي وخدمة التعرف | متوسط | يتطلب من المستخدم اختياره مساعدًا |
| USB Host (`uses-feature`, غير إلزامية) | ADB/تسلسلي/مرآة | مرتفع عمليًا | إذن USB لكل جهاز يُوصل |

**تقييم تصميم الأمان:**

- **جيد:** بوابة منع HTTP المكشوف على مستوى التطبيق (`OpenCodeUrl`) + NSC يحيل إليها كمرجع؛ تثبيت شهادة اختياري؛ تشفير كل الأسرار في `EncryptedSharedPreferences`؛ منع النسخ الاحتياطي؛ عدم إرسال بيانات الاعتماد لمسارات التفويض؛ بوابة Termux «رفض افتراضي»؛ جدار حماية وكيل الجهاز مع تصعيد النقر الحسّاس، ومطابقة 89/57/32 محسومة باختبارات.
- **يحتاج تحسين:** NSC يسمح بـ`cleartextTrafficPermitted="true"` على مستوى `base-config` (الاعتماد كليًا على الفلتر التطبيقي)؛ لا تحقق تجزئة/توقيع لنموذج Vosk المنزّل؛ تبعية `security-crypto` بإصدار alpha.
- **خطر محتمل:** الصلاحيات المرتفعة (all-files + Accessibility + call-log) تعني أن أي خطأ في الجسور قد يكون خطيرًا؛ ومع ذلك توجد طبقات حماية متعددة (قراءة فقط، تأكيدات، سجل، إيقاف، سكربت طرفية مقيّد).
- **غير واضح:** أثر التوقيع/الإصدارات على سياسات النشر في Google Play (الكود يوحي بأن التوزيع عبر GitHub/F-Droid أساسًا).

---

## 15. الخدمات والعمليات الخلفية

| المكوّن | النوع | متى يبدأ | ماذا يفعل | متى يتوقف | بعد إغلاق التطبيق؟ | بعد إعادة التشغيل؟ |
|---|---|---|---|---|---|---|
| `LocalRuntimeService` | Foreground (specialUse) | أمر مستخدم (تثبيت/تشغيل/تحديث/تراجع/حذف)، إعادة تشغيل، أو استئناف عند العودة للمقدمة | يشغّل/يوقف/يحدّث الران‑تايم، Watchdog، إيقاف عند الخمول (15 دقيقة)، تحرير WakeLock | عند الفشل أو عند إيقاف صريح/خمول | نعم بإشعار دائم | نعم بشرط (`RuntimeAutoStartPolicy`: مسجّل onboarding + عدم تفعيل idle-stop)
| `CodexKeepAliveService` | Foreground (specialUse) | عند حاجة Codex للمقدمة (`needsForeground`) | يبقي العملية حيّة أثناء التسجيل/الدور | بعد 2 ثانية من زوال الحاجة | محدود | لا |
| `ScheduleExecutionService` | Foreground (specialUse) | منبّه دقيق أو «شغّل الآن» | تنفيذ جدول كامل ومتابعته وتسجيل النتيجة | بنهاية التنفيذ | ينتهي | يُعاد تسليح المنبّهات عند الإقلاع |
| `WakeWordService` | Foreground (microphone) | عند تفعيل كلمة التنبيه | يستمع ويلتقط الكلمة، ويستأنف الميكروفون أثناء القراءة (barge-in) | عند التعطيل/الخطأ | نعم بإشعار | عبر `VoiceInteractionService.onReady` |
| `CallAgentService` | Foreground (microphone\|phoneCall) | أمر المكالمة أو مكالمة واردة | محادثة صوتية كاملة + إشعار بأزرار TAKE OVER/END/STOP | بنهاية المكالمة | محدود | لا |
| `MushreaCodeAccessibilityService` | خدمة نظام | عند تمكين المستخدم لها | قراءة الشاشة/تنفيذ الإيماءات لجسر الجهاز | عند التعطيل | نعم (نظام) | نعم حسب إعدادات النظام |
| `MushreaCodeVoiceInteractionService` + `SessionService` | خدمة نظام | عند اختياره مساعدًا افتراضيًا | جلسة الصوت/الواجهة، وتفعيل كلمة التنبيه عند الإقلاع | نظام | نعم | نعم |
| `MushreaCodeRecognitionService` | خدمة نظام | عند استدعاء التعرّف | تعرّف صوتي متوافق مع النظام | نظام | — | — |
| `TermuxResultReceiver` | BroadcastReceiver (غير مُصدَّر) | نتيجة أمر Termux | إكمال الـCompletableDeferred | فورًا | — | — |
| `PermissionActionReceiver` | Receiver (غير مُصدَّر) | ضغط زر في إشعار الموافقة | يرسل ONCE/ALWAYS/REJECT للران‑تايم | فورًا | — | — |
| `ScheduleAlarmReceiver` | Receiver | `RUN_SCHEDULE` / BOOT / تحديث الحزمة / تغيّر المنطقة الزمنية | بدء خدمة الجدولة + إعادة التسليح | فورًا | — | نعم |
| `RuntimeAutoStartReceiver` | Receiver | BOOT / MY_PACKAGE_REPLACED | استئناف الران‑تايم المحلي وفق السياسة | فورًا | — | نعم |
| `DeviceConfirmReceiver` / `StopAgentReceiver` | Receiver (غير مُصدَّر) | تأكيد/إيقاف طارئ | كتابة القرار/العلم للمخزن | فورًا | — | — |
| `QuickInputWidgetProvider` | AppWidget | تحديث الويدجت | فتح `QuickInputActivity` | — | — | — |
| `IncomingCallReceiver` | Receiver مُصدَّر | `PHONE_STATE` | قواعد المكالمات الواردة (سماح/رسالة/عدم رد) | فورًا | — | — |

**ملاحظة أداء/بطارية (Fact موثّق بالكود):** هناك حلقات استقصاء دورية: جسر الجهاز (~0.3s أثناء وجود مساحة عمل)، مراقبة الجودة (30 ثانية، تتوقف خارج المقدمة)، فحص التعطّل (30 ثانية)، إعادة اتصال ADB (30 ثانية، داخل Lease مقيّد)، وWatchdog الران‑تايم (5 ثوانٍ عند النشاط / 60 عند الخمول).

---

## 16. المكتبات والاعتماديات

| المكتبة | الإصدار | الاستخدام | أين | ضرورية؟ | ملاحظات/مخاطر |
|---|---|---|---|---|---|
| AGP | 8.5.2 | بناء أندرويد | `build.gradle.kts` | نعم | بدون version catalog |
| Kotlin (+Compose plugin, serialization) | 2.0.21 | اللغة/Compose/JSON | الجذر | نعم | — |
| KSP | 2.0.21-1.0.28 | معالج Room (غير مستخدم فعليًا) | `app` | لا حاليًا | يمكن إزالته مع Room |
| Gradle | 8.9 | البناء | wrapper | نعم | — |
| AndroidX core-ktx | 1.15.0 | أساسيات | متعدد | نعم | — |
| lifecycle (runtime/viewmodel/process) 2.8.7 + savedstate 1.2.1 | — | دورة الحياة | متعدد | نعم | — |
| activity-compose 1.9.3 | — | أنشطة Compose | الواجهات | نعم | — |
| Compose BOM 2024.12.01 (ui/material3/icons-extended) | — | كل الواجهة | `feature/*`, `ui/*` | نعم | أيقونات موسّعة تزيد الحجم |
| navigation-compose 2.8.5 | — | الملاحة | `ui/navigation` | نعم | — |
| startup-runtime 1.2.0 + profileinstaller 1.4.1 | — | تهيئة مؤجلة + Baseline Profiles | التطبيق/البناء | نعم | Initializers تُشغَّل يدويًا (تم تعطيل التلقائي بقرار موثّق) |
| OkHttp + okhttp-sse | 4.12.0 | `بروتوكول HTTP/SSE` | الشبكة | نعم | — |
| kotlinx-serialization-json | 1.7.3 | نماذج/تخزين | واسع | نعم | قواعد R8 مخصّصة |
| coroutines-android | 1.9.0 | التزامن | واسع | نعم | — |
| Koin (android + compose) | 4.0.1 | DI | `di/*` | نعم | استخدام جزئي فقط |
| Room (runtime/ktx/compiler) | 2.6.1 | **غير مستخدم** | `data/local` | لا | مرشّح للإزالة |
| security-crypto | 1.1.0-alpha06 | تشفير التفضيلات | `SecureSettingsRepository` | نعم | **إصدار ألفا** |
| documentfile | 1.0.1 | SAF | `SafWorkspaceImporter` | نعم | — |
| zxing-android-embedded | 4.3.0 | QR | شاشة الاتصال | نعم | — |
| usb-serial-for-android | 3.7.0 (JitPack) | تسلسلي | `device/usb` | نعم لميزة USB | مصدر JitPack يضيف مخاطرة توفّر |
| sshj | 0.38.0 | SSH/SFTP/SCP | `device/ssh` | نعم | `dontwarn` لمسارات JDK غير موجودة |
| smbj | 0.14.0 | SMB | `device/remote` | نعم | يجلب MBassador/JGSS (dontwarn) |
| commons-net | 3.11.1 | FTP | `device/remote` | نعم | — |
| xz | 1.9 | فك `payload.bin` | `device/payload` | نعم | مُعلن مرتين في build (سطر مكرر) |
| eddsa (net.i2p.crypto) | 0.3.0 | تحقّق AVB/توقيع في تحليل OTA | `device/payload` | نعم | — |
| slf4j-nop | 2.0.13 | كتم سجلات sshj/smbj | الشبكة | نعم | — |
| Vosk-android | 0.3.75 | كلمة التنبيه | `feature/wakeword` | نعم للميزة | يحتاج قواعد إبقاء JNA مع R8 |
| firebase-bom (analytics+crashlytics) | 33.6.0 | تحليلات/تعطّل | نسخة `github` فقط | لا (اختيارية) | مثبّتة على 33.6 لتوافق Kotlin 2.0 (تعليق صريح) |
| junit / mockwebserver / coroutines-test / org.json | 4.13.2 / 4.12.0 / 1.9.0 / 20231013 | اختبارات | `test` | نعم | — |
| androidx.test + espresso + compose-ui-test | 1.2.1 / 3.6.1 / BOM | اختبارات أجهزة | `androidTest` | نعم | لا تُنفَّذ في CI |
| benchmark-macro-junit4 + uiautomator | 1.3.3 / 2.3.0 | Baseline Profile | `benchmark` | نعم | التوليد غير مُدار في CI |
| detekt + spotless/ktlint | 1.23.6 / 6.25.0 / 1.2.1 | تحليل ثابت/تنسيق | الجذر | نعم | قاعدتان معطّلتان عمدًا في `.editorconfig` |
| jacoco | (مدمج) | — | الجذر | **لا** | لا مهام تغطية مُعرّفة → لا قياس تغطية فعلي |

---

## 17. الاختبارات

### 17.1 الأرقام الفعلية

| النوع | العدد | التنفيذ في CI |
|---|---|---|
| اختبارات وحدات (JVM) | 180 ملفًا / **1,323** `@Test` | ✅ تُنفَّذ (`:app:testGithubDebugUnitTest`) في عمل main وفي الإصدار وفي PR مع وسم |
| اختبارات أجهزة (Compose/Espresso) | 9 ملفات / **23** `@Test` | ❌ تُصرَّف فقط (`assembleGithubDebugAndroidTest`) — **لا `connectedAndroidTest` في أي سير عمل** |
| Baseline Profile (benchmark) | 2 مولّدات | ❌ لا يُشغَّل تلقائيًا (`managedDevices += "pixel6Api34"`) |
| تغطية (Coverage) | غير مقيسة | لا مهام Jacoco (`jacoco` مُطبَّق بلا استخدام) |

### 17.2 تغطية الميزات

**مختبرة جيدًا (أمثلة):** محلّل أحداث SSE، عميل OpenCode (MockWebServer)، حالة ChatViewModel (4 ملفات اختبار بحجم كبير: 2061+615+430+... سطر)، الجدولة (Bridge/Retry/Watchdog/Models)، الران‑تايم المحلي (Installer/Updater/ProcessLauncher/Watchdog/Manifest/Release)، الوكلاء الثلاثة (Antigravity 20+ ملفًا، Claude 12، Codex 11)، وكيل الجهاز (Firewall/Codec/Context/ScreenSearch/Snapshot/StopPhrases/XiaomiUnlock)، USB (AdbClient/Protocol/Sync/Classification)، المرآة (H264/Scrcpy messages)، Wake word (Grammar/Matcher/Detector/ModelInstaller/SettingsPolicy)، TTS (Tuning/Preview/Request factory)، الاتصال (QR/URL/Profile)، الأمان (SecretRedaction/CrashSanitizer)، الالتزام القانوني (LegalDisclosureCompliance/LegalStringResources)، مزامنة الإصدار (ReleaseMetadataTest).

**بلا أي اختبار وحدات (فجوات مؤكدة):**

| المكوّن | نوع المخاطرة |
|---|---|
| `device/ssh/SshAgent.kt` + `SshExecutor.kt` | منطق شبكة/توثيق غير مُختبَر |
| `device/remote/RemoteExecutor.kt` (SMB/FTP/WebDAV/mDNS) | ملف ضخم بمنطق معقّد بلا اختبارات |
| `device/network/NetworkExecutor.kt` و`device/bluetooth/BluetoothExecutor.kt` | تفاعل أذونات/أجهزة بلا اختبارات |
| `device/usb/UsbExecutor.kt` (721 سطرًا) و`UsbSerialExecutor.kt` | ضخم بلا اختبارات مباشرة |
| `device/mirror/ScreenMirrorSession.kt` / `ScrcpySession.kt` | جلسات فعلية بلا اختبارات للدورة الكاملة |
| `feature/widget/*`, `feature/share/ShareReceiverActivity.kt` | مسارات Manifest بلا اختبارات |
| `runtime/local/ClaudeCodeRuntime.kt` (772) و`AntigravityRuntime.kt` (599) و`CodexRuntime.kt` (679) | المُحلّلات مختبرة، لكن الأصناف الكاملة (تشغيل عملية/جلسات) لا |
| `LocalRuntimeInstaller.kt` (786) | التثبيت الحقيقي (تنزيل/استخراج) لا يُختبر في وحدات |

**اختبارات لا تختبر السلوك الحقيقي (Fact):** `ChatFlowE2ETest` و`DrawerGestureInstrumentedTest` تحقّقان من وجود/عرض عناصر الواجهة (لا تدفّق محادثة فعلي مقابل ران‑تايم)، وبعض اختبارات الواجهة تتحقق من الحالات الفارغة/الثابتة فقط. لا توجد أي اختبارات تكامل حقيقية مع OpenCode أو PRoot في CI.

---

## 18. البناء و CI/CD

### 18.1 ما يحتاجه المطوّر للبناء من الصفر

| المتطلب | ملاحظة |
|---|---|
| JDK 17 | مثبَّت بواسطة `actions/setup-java` (temurin) |
| Android SDK (API 35 + build-tools) | تسطّبه صورة ubuntu-latest في CI؛ محليًا يجب ضبط `local.properties` |
| Python 3 (3.11 في CI) | إلزامي: مهمتا Gradle `prepareOpenCodeRuntimeAssets` و`prepareOpenCodeRuntimeNativeLibs` تستدعيان سكربتات Python قبل كل بناء (`preBuild` يعتمد عليهما) |
| شبكة | أول بناء فقط (تنزيل حزم Termux) |
| `google-services.json` | **مُلتزَم** — مطلوب لنسخة `github` |
| `GITHUB_CLIENT_ID` | اختياري للبناء (يصبح فارغًا → لا تسجيل GitHub) |
| 4 أسرار توقيع | فقط لإصدار موقَّع |
| NDK | غير مطلوب (لا كود C/C++) — لكن يوجد `ndk { abiFilters }` فقط |

**أوامر البناء الأساسية (من README):**
```bash
./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest \
          :app:assembleGithubDebug :app:assembleGithubRelease
```

### 18.2 نكهات البناء والتوقيع

- **github**: يشمل Firebase (Analytics + Crashlytics).
- **fdroid**: بلا Firebase إطلاقًا؛ تُبنى بـ`-Pmushreacode.fdroidBuild=true` وتُعطّل إضافات Google-services، وتُنتج أصلًا قابلاً للتحقق مقابل بناء F-Droid (`dependenciesInfo` معطّل ليتوافق مع كاشف F-Droid).
- **R8:** `minifyEnabled` و`shrinkResources` مع قواعد مفصّلة (serialization, JNA/Vosk, Firebase, `dontwarn` لمسارات JDK غير الموجودة على أندرويد).
- **التوقيع:** يُبنى `signingConfigs.release` فقط إذا وُجدت المتغيرات الأربعة (`AND_CODE_STORE_FILE/PASSWORD`, `AND_CODE_KEY_ALIAS/PASSWORD`)؛ يُمكِّن v1+v2+v3 (v1 لبعض متاجر الطرف الثالث).
- **`lint`**: تعطيل `EnsureInitializerMetadata` مع تعليل صريح (Initializers تُشغَّل يدويًا).

### 18.3 سير العمل (7)

| السير | المُشغِّل | المهام | ملاحظات |
|---|---|---|---|
| `android.yml` | push إلى `main` أو `arena/**`، PR، يدوي | `static-analysis` (detekt+spotless)، `lint`، `test-and-build` (اختبارات وحدات + APK debug + compile androidTest + R8 release على main)، `auto-format` (على الفروع: spotlessApply + التزام آلي) | المهام الثقيلة **فقط على main**؛ الفروع تحصل على تنسيق تلقائي. لا تشغيل لاختبارات الأجهزة |
| `release.yml` | push يتغيّر فيه `.release-version` أو يدويًا بوسم | فك تشفير الـkeystore، بناء موقّع + بناء fdroid، إنشاء Release ورفع الأصول | يتطلب 4 أسرار + متغيّر `AND_CODE_GITHUB_CLIENT_ID`، وإلا يفشل عمدًا |
| `pr-apk-build.yml` | PR بوسم `build-apk` أو يدويًا | يبني APK debug ويرفق رابطًا في PR | — |
| `i18n-check.yml` | تغييرات في `strings.xml` | يتحقق من اكتمال كل اللغات (مع احترام `translatable="false"`) | ✅ متوافق مع الوضع الحالي (كل اللغات مكتملة) |
| `commitlint.yml` | PR | يفرض `type(scope): …` | — |
| `ocr-review.yml` | PR | مراجعة/تعليقات آلية | (لم يُفحص محتواه بالتفصيل) |
| `fdroid-repository.yml` | (يدوي/مجدول) | ينشر مستودع F-Droid ثنائيًا على GitHub Pages من إصدارات GitHub | ليس تقديمًا رسميًا لـF-Droid |

---

## 19. الأمان (تقييم تقني مفصّل)

| الجانب | التقييم | السبب/الدليل |
|---|---|---|
| تشفير الأسرار الساكنة | **جيد** | `EncryptedSharedPreferences` بمفتاح من Android Keystore، لا نسخ احتياطي (`allowBackup=false` + data-extraction-rules) |
| منع النص الصريح على الشبكة العامة | **جيد (بآلية تطبيقية)** | `OpenCodeUrl` يرفض HTTP إلا للعناوين المحلية/CGNAT/`.local`؛ لكن NSC يسمح بالـcleartext على مستوى `base-config` — الحماية تعتمد على أن كل مسار شبكة يستخدم هذا الفلتر |
| التحقق من التنزيلات | **جيد للران‑تايم، يحتاج تحسينًا لنموذج Vosk** | SHA-256 مثبّت لـAlpine/OpenCode/Debian/agy/bsdutils؛ **لا** تحقق لنموذج Vosk (HTTPS فقط) |
| بوابة أذونات وكيل الجهاز | **جيد** | 89 إجراءً مصنَّفًا، 57 تلقائي/32 تأكيدي، تصعيد النقر الحسّاس، قائمة قراءة فقط افتراضية، رفض افتراضي |
| قابلية التدقيق | **جيد** | سجل نشاط + تصدير تدقيق لا يحوي معاملات ولا محتوى ولا رموزًا، ومُثبَّت باختبارات |
| إيقاف الطوارئ | **جيد** | علم إيقاف يُفحص قبل كل أمر + اختصار شاشة + كيان مستقل |
| Termux/التفليش | **جيد (بمحاذير)** | `TermuxCommandPolicy` يرفض flash/erase/oem/stage/lock/unlock، ويرفض الربط/إعادة التوجيه؛ مرحلة الكتابة غير مبنية عمدًا |
| إدارة أسرار المزوّدين | **جيد** | تُخزَّن مشفّرة وتُزامَن إلى `auth.json` داخل الجذر المعزول فقط، ولا تُنسخ بين الوكلاء (موثّق ومؤكَّد في الكود: `LocalProviderCredentialStore`) |
| تحليل/تعطّل | **جيد** | Crashlytics معطّل في debug، وAnalytics opt-in (`analyticsEnabled=false` افتراضيًا + `firebase_analytics_collection_enabled=false` في Manifest)، مع تنقية نصوص (`CrashReportSanitizer`/`SecretRedaction`) |
| النموذج الأمني لبيئة PRoot | **غير واضح/بمحاذير** | PRoot توافقية لا صناديق عزل حقيقي؛ README يصرّح بذلك، والوكيل بوضع «صلاحيات كاملة» يستطيع الكتابة في مساحة العمل والملفات الممنوحة |
| الصلاحيات المرتفعة | **خطر محتمل** | all-files + Accessibility + قراءة سجل المكالمات: مجموعة صلاحيات تجعل التطبيق غير قابل للنشر في Play بسياسة عادية، وتزيد أثر أي خلل |
| `google-services.json` في Git | **يحتاج تحسينًا** | إعداد عميل Firebase معرّف عام (لا oauth_client ولا أسرار خادم)، لكن الأفضل توليده في CI وعدم التزامه |
| تبعية ألفا (`security-crypto`) | **يحتاج تحسينًا** | `1.1.0-alpha06` — مخاطرة تحديث/توقف مستقبلية |
| رموز OTP/المكالمات | **جيد** | `CallPolicy` يمنع نطق الأكواد/كلمات المرور ويصعّد، ولا تسجيل صوتي للاتصال إطلاقًا |

---

## 20. الأداء

> لا توجد قياسات فعلية في المستودع (`docs/device-matrix.md` يترك جدول البطارية فارغًا)، و`scripts/battery_benchmark.sh` موجود لكن لم تُسجَّل نتائج. ما يلي **مخاطر مستندة إلى الكود** لا إلى قياس.

| المحدّد | الدليل | التقييم |
|---|---|---|
| كلفة PRoot/الرملة | تشغيل نسخة Alpine كاملة عبر proot، تحميل OpenCode، ثم وكيل HTTP داخلي | استهلاك ذاكرة/CPU مهيكل بطبيعته؛ لا أرقام موثّقة |
| حلقات الاستقصاء | جسر الجهاز ~0.3s، مراقبة الجودة 30s، فحص التعطّل 30s، ADB 30s، Watchdog 5s/60s | مضبوطة نسبيًا؛ أثقل ما فيها استقصاء الجسر (مقيّد بوجود مساحة عمل نشطة) |
| WakeLock | `RuntimeWorkTracker` بLeases محسوبة (جلسات، جدولة، طرفية، ADB، عمليات ران‑تايم) + مهلة أقصى 10 دقائق لخدمة الران‑تايم | تصميم جيد مقارنةً بWakeLock دائم، لكن أي تسرّب في lease = استنزاف |
| الصور في حالة الـUI | `ChatMessage` يحمل `List<Bitmap>` و`ChatUiState` يُحتفظ به في StateFlow | خطر ذاكرة على محادثات طويلة بصور كبيرة (لا يوجد تحميل كسول/تحجيم مُلزَم في السطح الظاهر) |
| تقليم مخرجات الأدوات | `MAX_TOOL_OUTPUT_CHARS = 4000` | جيد |
| سجلّ الأحداث/الرسائل | `RuntimeActivityRepository` يحتفظ بسجل محدود (`RuntimeEventLog`/قوائم) | جيد، لكن إعادة بناء الترانزكربت تُقرأ من الران‑تايم عند الفتح |
| `assembleRelease` (R8) | موثّق بأنه أغلى مهمة | يقود زمن CI |
| نماذج Vosk | 40–48MB تنزيل + تفريغ حتى 250MB | عند الطلب فقط (لا تُشحن في APK) |
| Debian rootfs لإضافية Antigravity | ~28MB طبقات + حزم | تُنزَّل مرة واحدة عند اختيار الوكيل |
| SCROLL/إعادة الرسم في المحادثة الطويلة | قائمة Compose بلا مفاتيح مستقرة لكل رسالة (صور/أجزاء متغيّرة) | خطر إعادة تركيب (recomposition) غير ضروري — **استنتاج** لم أقِسه |

**نقاط قوة أداء:** الجودة/التشخيص مُدمجة (تتبع التعطّل، والفحص الصحي، وسجل آخر خروج/إعادة تشغيل)، وإيقاف المحادثة خارج المقدمة، وإيقاف الران‑تايم عند الخمول، وقياس الأداء عبر Baseline Profile (المولّد موجود).

---

## 21. الميزات المخفية / غير الموثّقة

هذه ميزات **موجودة فعليًا في الكود** ولا تذكرها `README.md` (أو تذكرها بشكل عابر فقط):

1. **وكيل المكالمات بالكامل** — لا يرد في README ولا في `docs/` (فقط في `mushrea-code-agent-context.md` الذي يُقرأ للوكيل نفسه). أدواته: `find_contact, call_agent, call_state, call_stop, call_log, call_summaries`.
2. **مشغّل USB Hub** (`usb_hub_list`, `hid_read`, `storage_volumes`, `camera_list`) — غير مذكور في README.
3. **أدوات الشبكة وBluetooth** (6+4 أدوات) — غير مذكورة.
4. **SSH/SFTP/SCP + SMB/FTP/WebDAV + mDNS browse** — غير مذكورة في README رغم أنها موثّقة داخليًا في HANDOFF.
5. **تحليل OTA (`payload_info`/`payload_extract`) وحاجز الروم (`payload_guard`)، و`fastboot_getvar_full` مع إخفاء الرمز، و`safety_preflight`، و`audit_export`** — موثّقة جزئيًا في `docs/ON_DEVICE_AGENT.md` فقط.
6. **جسر Termux** (`termux_status`, `termux_run`, `termux_fastboot_run`, `mitool_wrapper`) — كما أعلاه.
7. **مرآة/تحكم scrcpy** — لا ذكر في README، والثنائي `scrcpy-server-4.0` مُضمَّن في الأصول.
8. **خادم MCP للجدولة داخل الضيف** (`schedule_list/create/update/delete/set_enabled/run_now/runs/get`) — يسمح للوكيل بإنشاء جدولة بنفسه.
9. **المتصفح الضيف عبر CDP** (`browser_show/navigate/click/type/screenshot/info/status`).
10. **مطالبات الدعم (Star prompts)** وشاشة دعم المشروع (`GitHubStarCoordinator`/`GitHubSupportSheet`).
11. **سجل التعطّل المحلي + حوار عرضه** (`CrashLog` + `CrashReportDialog`) — مفيد لبيئة بلا Logcat.
12. **اختصارات الشاشة الرئيسية** (`device_shortcuts.xml`) و**هدف العرض VIEW** لفتح ملفات مباشرة في التطبيق.
13. **ADB لاسلكي مبني داخل التطبيق** (`AdbConnectionManager` + `AdbKeys` + `TcpTransport`) مع إعادة اتصال تلقائية.
14. **مزامنة مفاتيح المزوّدين إلى داخل الرملة + Git credential helper** (`GitCredentialHelper`) — لا تُذكر في README.
15. **سياق الوكيل المزروع تلقائيًا** مع حماية من الكتابة فوق تعديلات المستخدم (`MushreaCodeAgentContext`).
16. **`Arbitrary` ميزات تطوير**: مراجعة pre-PR بوكيل داخلي في `.opencode/` (`repo-reviewer` + مهارة `pre-pr-review`) — أدوات تطويرية للمستودع، لا للمنتج.

---

## 22. الكود غير المستخدم (Dead Code)

> لم أحذف شيئًا — هذا توصيف فقط.

| العنصر | الملف | دليل عدم الاستخدام |
|---|---|---|
| طبقة Room كاملة: `SessionDatabase`, `SessionDao`, `SessionEntity`, `MessageEntity`, `SessionCacheRepository` | `data/local/*` | لا `Room.databaseBuilder` في المشروع كله؛ الإشارات محصورة داخل المجلد نفسه |
| `ForgeClient` + `GitHubForgeClient` + `GitLabForgeClient` + `GiteaForgeClient` + `ForgeReference` + `ForgeType` | `core/api/ForgeClient.kt` | لا استدعاء خارج الملف (و`GitHubApiClient` يؤدي الغرض فعليًا) |
| `TabletSettingsLayout` | `feature/settings/TabletSettingsLayout.kt` | لا إشارة في أي ملف آخر |
| `KeepAwakeHelper` | `feature/assistant/KeepAwakeHelper.kt` | غير مُستدعى (WakeLock يُدار عبر `RuntimeWorkTracker`) |
| `VoiceActivityDetector` | `feature/wakeword/VoiceActivityDetector.kt` | غير مُستدعى |
| `DragDropAttachHelper` | `feature/workspace/DragDropAttachHelper.kt` | غير مُستدعى |
| `AssistantProfile` + `AssistantProfileResolver` | `feature/assistant/AssistantProfile.kt` | يُستخدم في اختبار فقط |
| `OpenCodeConfig`, `OpenCodeCacheTokens`, `OpenCodeModelLimit`, `OpenCodeFilePatch`, `OpenCodePatchHunk`, `OpenCodeSessionShare`, `OpenCodeSessionTokens`, `ProviderAuthOption` | `core/api/*Models.kt` | لا استهلاك خارج ملفات النماذج |
| `scripts/sign_wakeword_pack.py` | `scripts/` | يتحدث عن `WakeWordPackManager` غير الموجود في المشروع (بقايا المشروع الأم) |
| `TerminalTabPlaceholder` | `feature/workspace/WorkspaceExplorerScreen.kt` | واجهة فقط بلا سلوك (حقل نص لا يفعل شيئًا) |
| شرائح `WorkspaceDeckRow` | نفس الملف | `onClick = {}` |
| إضافة `jacoco` | `build.gradle.kts` | مُطبَّق بلا أي مهمة تغطية |
| تكرار `org.tukaani:xz:1.9` | `app/build.gradle.kts` | مُعلن مرتين |
| `adbd`/`aicp` غير موجودة | — | — |
| لا TODO/FIXME | كل المصدر | `grep` = صفر نتيجة في `app/src/main` |

---

## 23. المشاكل والأخطاء

| # | المشكلة | الخطورة | الموقع | السبب | الأثر | الحل المقترح |
|---|---|---|---|---|---|---|
| 1 | اختبارات الأجهزة (23) لا تُنفَّذ في CI | مرتفع | `.github/workflows/android.yml` (يصرّف androidTest فقط) + `docs/device-matrix.md` يدّعي «CI green — connectedDebugAndroidTest» | لا مهمة `connectedAndroidTest` ولا إدارة محاكي/جهاز | مسارات Compose/الحواف غير مُتحقق منها آلًيا؛ التوثيق مبالغ فيه | إضافة مهمة اختبارات متصلة (managed device) أو تصحيح التوثيق صراحةً |
| 2 | Room + تبعياتها ميتة | متوسط | `data/local/*` + `app/build.gradle.kts` | بقايا تصميم سابق | زمن بناء/حجم APK + ارتباك معماري | إكمال الكاش المحلي أو الحذف |
| 3 | NSC يسمح بالنص الصريح على مستوى `base-config` | متوسط | `res/xml/network_security_config.xml` | لا يمكن التعبير عن نطاقات IP | إن سقط فلتر `OpenCodeUrl` في مسار جديد → HTTP مكشوف مسموح | تقييد NSC بـ`domain-config` حيث يمكن، وإضافة اختبار يمنع أي طلب غير مُصفّى |
| 4 | لا تحقق تجاوز/تجزئة لنموذج Vosk | متوسط | `feature/wakeword/VoskModelInstaller.kt`, `VoskModelCatalog.kt` | لم يُنفَّذ (مذكور كغير مُنفَّذ في قائمة التحقق) | نموذج مُخترق من المصدر = تنفيذ بيانات صوتية غير موثوقة | تثبيت SHA-256 لكل نموذج كما في أصول الران‑تايم |
| 5 | سكربت توقيع حزمة كلمة التنبيه يتيم | منخفض | `scripts/sign_wakeword_pack.py` | بقايا المشروع الأم (`WakeWordPackManager` غير موجود) | التباس للمطوّر؛ لا أثر وظيفي | حذف أو إعادة ربطه بمنظومة جديدة |
| 6 | `docs/RELEASE.md` يستخدم أسماء متغيرات خاطئة | متوسط | `docs/RELEASE.md` | لم يُحدَّث بعد تغيير البادئة في `app/build.gradle.kts` (`AND_CODE_*`) | إصدار محلي موقَّع يفشل بصمت (يُبنى unsigned) | تصحيح الأسماء + تحديث «CHANGELOG.md» غير الموجود |
| 7 | `docs/CI.md` قديم | منخفض | `docs/CI.md` | لا يُحدَّث تلقائيًا | معلومات مضللة عن إعادة تشغيل البناء | مزامنته مع سير العمل |
| 8 | `docs/COMPLETION_CHECKLIST.md` قديم (ياباني) | منخفض | `docs/` | يعود لتصميم 2026-07-18 | يذكر شاشة Home/Activity غير موجودة، وبنودًا [ ] أُنجزت فعلًا (المرفقات، حزمة كلمة التنبيه) | أرشفته أو تحديثه |
| 9 | `HANDOFF.md`/`AGENTS.md` يصفان حالة فرع مختلف ومسار `/workspace/Mushrea.AI` | منخفض | الجذر | وثائق لوكيل سابق | إرباك عند فتح جلسة جديدة | تحديث الحالة الحالية بعد الدمج |
| 10 | `google-services.json` مُلتزَم | منخفض | `app/google-services.json` | تسهيل بناء github | تسرّب معرّفات مشروع Firebase إلى مستودع عام | توليده في CI أو توثيق قبوله صراحةً |
| 11 | تبعية ألفا `security-crypto:1.1.0-alpha06` | متوسط | `app/build.gradle.kts` | لا إصدار مستقر متاح من نفس المكتبة | مخاطرة استقرار/توقف صيانة | متابعة البديل الرسمي (DataStore + Keystore مخصص) |
| 12 | حشوة/عمليات متكرّرة في `build.gradle.kts` | منخفض | `app/build.gradle.kts` | تاريخ تطوّر | صيانة أصعب، لا version catalog | توحيد في `gradle/libs.versions.toml` |
| 13 | `ChatUiState` يحمل `Bitmap` | متوسط | `feature/chat/ChatViewModel.kt` | عرض معاينات الصور داخل الحالة | ذاكرة متزايدة في محادثات طويلة/صور كبيرة | تخزين معرّفات + تحميل كسول/تحجيم |
| 14 | ملفات ضخمة جدًا (2767/2235/1695 سطرًا) | متوسط | ChatViewModel/ChatHomeScreen/MushreaCodeApp | تراكم ميزات | مخاطرة صيانة ودمج، صعوبة اختبار | تقسيم إلى وحدات (send/stream/ui-state/permissions) |
| 15 | اعتماد جوهري على نمط «ملفات أوامر» مع استقصاء | متوسط | `DeviceAgentBridge`, `BrowserCommandWatcher`, `ScheduleBridge` | الضيف لا يرى واجهات التطبيق | حساسية لزمن الاستقصاء (تأخير/مهل)، وسلوك غامض عند تعطيل Accessibility | إضافة قناة أسرع (Socket/ContentProvider) وقياس زمن الاستجابة |
| 16 | لا اختبارات لطبقة الشبكة اليدوية (SSH/SMB/FTP/WebDAV/BT) | مرتفع | `device/remote`, `device/ssh`, `device/network`, `device/bluetooth` | لم تُكتب | أخطاء حقيقية مرجّحة على أجهزة/شبكات فعلية (الوثيقة الداخلية تعترف بذلك) | محاكيات خدمة + اختبارات تكامل مخففة |
| 17 | لا قياس تغطية | منخفض | الجذر | `jacoco` بلا مهام | لا مؤشر جودة كمي | إضافة `jacocoTestReport` + عتبة |
| 18 | لا اختبار يُشغّل التثبيت الحقيقي (تنزيل/استخراج/proroot) | متوسط | `runtime/local/LocalRuntimeInstaller.kt` | يحتاج جهازًا | أول فشل حقيقي يظهر عند المستخدم | اختبار جهاز (instrumentation) + شاشة تحقق |
| 19 | Portal صارم لـAccessibility: عند تعطيل الخدمة يفشل كل شيء بصمت نسبي | منخفض | `DeviceAgentBridge` | الجسر لا يعمل بلا الخدمة | رسالة المهلة موثّقة جيدًا في سكربت MCP (جيد) | إضافة إشعار «الوكيل غير مفعّل» |
| 20 | `DeviceAgentActivity` مُصدَّرة مع LAUNCHER → أيقونة ثانية في المشغّل | منخفض | `AndroidManifest.xml` | تصميم مقصود للوصول السريع | ارتباك مستخدم محتمل | توضيح/إخفاء الأيقونة أو توحيد نقطة الدخول |

---

## 24. مقارنة التوثيق مع التنفيذ الحقيقي

| الوثيقة | صحيح | قديم/ناقص | غير مطابق |
|---|---|---|---|
| `README.md` | الميزات الأساسية والبناء والنسخ المثبّتة (Alpine/OpenCode/agy) وحذف الحاجة للحاسوب | لا يذكر: وكيل المكالمات، USB/ADB، scrcpy، SSH/SMB، أدوات الشبكة/Bluetooth، خوادم MCP الثلاثة، ADB اللاسلكي | لا شيء مُثبت كاذبًا في المسارات المذكورة |
| `README.ja.md` | ترجمة يابانية متوافقة بالمحتوى | نفس النقص أعلاه | — |
| `docs/LOCAL_RUNTIME.md`, `ANTIGRAVITY.md`, `CODEX.md`, `CLAUDE_CODE.md` | تصميم مفصّل مطابق للكود عمومًا | قد لا يشمل أحدث التعديلات (تحقّق جزئي) | — |
| `docs/ON_DEVICE_AGENT.md` | دقيق جدًا (عدادات 88/89 مطابقة لما تحققتُ منه) | — | — |
| `docs/device-matrix.md` | يصرّح بأن اختبارات الأجهزة يدوية ومعلّقة | صفوف CI تزعم تشغيل `connectedDebugAndroidTest` | **مخالف للواقع** (لا تشغيل في CI) |
| `docs/COMPLETION_CHECKLIST.md` | يوثّق ما هو ناقص بصدق في وقته | يعود لتاريخ 2026-07-18 ويذكر شاشات/بنودًا تجاوزها التنفيذ | يذكر «5 شاشات: Home/Chat/Workspaces/Activity/Settings» غير الموجودة بهذا الشكل |
| `docs/RELEASE.md` | الخطوات العامة والتشغيل اليدوي صحيحة | أسماء متغيرات old (`ANDROID_CODE_*`) و`CHANGELOG.md` غير موجود | **مخالف للواقع في أسماء المتغيرات** |
| `docs/CI.md` | وجود سير العمل بوسم APK | «لا يعاد التشغيل آليًا» مخالف لسلوك `synchronize` في السير | مخالف جزئيًا |
| `PRIVACY.md` / `TERMS.md` / `THIRD_PARTY_*` | مطابقة للموجود: Firebase اختياري، لا خادم وسيط، تراخيص مسرودة، نسخ يابانية | — | — |
| `HANDOFF.md` | يعطي سياقًا فنيًا دقيقًا (88 أداة، 89/57/32) | يصف حالة فرع/دمج مختلفة عن هذه الشجرة | لا شيء يُثبت كاذبًا، لكن الحالة تغيّرت |
| `AGENTS.md` | قواعد عمل الوكيل | مسار `/workspace/Mushrea.AI` غير مطابق لهذه البيئة | — |

**ميزات موثّقة وغير موجودة:** لم أجد ادعاءً موثَّقًا بلا تنفيذ في الكود. أبرز «ادعاء» مبالغ فيه هو تشغيل اختبارات الأجهزة في CI، ووجود CHANGELOG، وواجهة طرفية داخل تبويب المستكشف (الشاشة موجودة في مكان آخر).

---

## 25. جدول حالة جميع الميزات

الرموز: 🟢 مكتملة · 🟡 جزئية · 🟠 نموذج أولي · 🔵 واجهة فقط · 🔴 لا تعمل · ⚪ غير مستخدمة · ❓ غير قابل للتحقق

| # | الميزة | الوصف | الحالة | الملفات الرئيسية | يحتاج إنترنت | يحتاج صلاحيات | الاختبارات |
|---|---|---|---|---|---|---|---|
| 1 | محادثة متدفقة | SSE + استقصاء احتياطي | 🟢 | `ChatViewModel`, `OpenCodeApiClient`, `RuntimeActivityRepository` | نعم (للنماذج) | — | ✅ كثيفة |
| 2 | إدارة الجلسات | إنشاء/استئناف/إعادة تسمية/حذف/أرشفة | 🟢 | `ChatViewModel`, `OpenCodeBackend` | نعم | — | ✅ |
| 3 | واجهة زمنية منظمة | أدوات/استدلال/مهام/مخرجات | 🟢 | `AssistantActivityRow`, `ChatMessageComponents` | — | — | ✅ جزئيًا |
| 4 | موافقات الأدوات | 3 خيارات + إشعار | 🟢 | `RuntimeNotificationHelper`, `PermissionActionReceiver` | — | POST_NOTIFICATIONS | ✅ |
| 5 | أسئلة تفاعلية | بطاقات اختيار | 🟢 | `QuestionCard`, `ChatViewModel.submitQuestion` | — | — | ✅ |
| 6 | فرق المراجعة | توحيد/تقسيم | 🟢 | `ChatDiffDialog`, `WorkspaceExplorerScreen` | — | — | 🟡 |
| 7 | شارات Pull Request | حالة + حجم | 🟢 | `PullRequestLinkBar`, `PullRequestStatusRepository` | نعم | — | ✅ |
| 8 | صور مرفقة | إرسال/معاينة/مكبّر | 🟢 | `ChatImageViewer`, `PromptAttachment` | — | — | ✅ |
| 9 | مرفقات ملفات | استيراد/لصق | 🟢 | `AttachmentImporter`, `ChatViewModel.addAttachment` | — | — | ✅ |
| 10 | سحب وإفلات | على الشاشة | ⚪ | `DragDropAttachHelper` | — | — | ❌ |
| 11 | إملاء صوتي | تعرّف أندرويد | 🟢 | `SpeechRecognizerManager`, `LiveTranscriptOverlay` | — | RECORD_AUDIO | ✅ |
| 12 | قراءة الردود (TTS) | 3 مزوّدين | 🟢 | `TTSManager`, `AndroidTTSProvider`, `CloudTTSProvider` | اختياري | — | ✅ |
| 13 | كلمة التنبيه Vosk | تنزيل نموذج + كشف | 🟢 | `WakeWordService`, `VoskWakeWordDetector`, `VoskModelStore` | للمرة الأولى | RECORD_AUDIO + FGS | ✅ (بلا تحقق تجزئة) |
| 14 | المساعد الافتراضي | VoiceInteraction | 🟢 | `MushreaCodeVoiceInteractionService`, `…SessionService` | — | اختيار مساعد | 🟡 |
| 15 | مقاطعة القراءة (barge-in) | استرجاع الميكروفون | 🟢 | `BargeInPolicy`, `WakeWordService` | — | RECORD_AUDIO | ✅ |
| 16 | ويدجت | طلب سريع | 🟢 | `QuickInputWidgetProvider`, `QuickInputActivity` | — | RECORD_AUDIO للإملاء | ❌ |
| 17 | هدف المشاركة | نص/ملف/عرض | 🟢 | `ShareReceiverActivity` | — | — | ❌ |
| 18 | أوامر Slash | أوامر تطبيق + أوامر الوكيل | 🟢 | `SlashCommandRegistry` | — | — | ✅ جزئيًا |
| 19 | تسليم المحادثة (Handoff) | ملخّص بين ران‑تايم | 🟢 | `SessionHandoff`, `ChatViewModel` | — | — | ✅ |
| 20 | قائمة الانتظار بلا اتصال | إرسال مؤجَّل | 🟢 | `ChatViewModel.offlineQueue` | — | — | ✅ |
| 21 | تشخيص التعطّل | كشف 150ث + تفسير | 🟢 | `SessionStallDiagnosis`, `ChatStallCard` | — | — | ✅ |
| 22 | ران‑تايم محلي (PRoot) | Alpine + أدوات | 🟢 | `runtime/local/*` | نعم للتنزيل | FGS | ✅ جزئيًا |
| 23 | تحديث/تراجع ذرّي | مجلات + تحقق | 🟢 | `LocalRuntimeUpdater` | نعم | FGS | ✅ |
| 24 | تشخيصات ومقاييس وسجلات | عرض + تصدير | 🟢 | `LocalRuntimeDiagnostics`, `DiagnosticsSheet` | — | — | ✅ |
| 25 | إيقاف عند الخمول | 15 دقيقة | 🟢 | `LocalRuntimeService.checkIdleStop` | — | FGS/WAKE_LOCK | ✅ |
| 26 | OpenCode محلي | خادم 127.0.0.1:4097 | 🟢 | `LocalOpenCodeBackend`, `LocalRuntimeTarget` | نعم | — | ✅ |
| 27 | OpenCode بعيد | LAN/Tailscale/HTTPS | 🟢 | `RemoteOpenCodeBackend`, `OpenCodeApiClient` | نعم | INTERNET | ✅ |
| 28 | Claude Code محلي | stream-json + هوك | 🟢 | `ClaudeCode*.kt` | نعم | — | ✅ جزئيًا |
| 29 | Antigravity محلي | Debian + PTY | 🟢 | `Antigravity*.kt` | نعم | — | ✅ جزئيًا |
| 30 | Codex محلي | app-server JSON-RPC | 🟢 | `Codex*.kt` | نعم | — | ✅ جزئيًا |
| 31 | تسجيل دخول المزوّدين | OAuth/API key | 🟢 | `ProviderAuthDialog`, `*AuthCoordinator` | نعم | — | ✅ |
| 32 | مزوّدون مخصّصون | تعريف يدوي | 🟢 | `CustomProviderStore`, `CustomProviderDialog` | نعم | — | ✅ |
| 33 | MCP لكل وكيل | عرض/إضافة/إزالة/توصيل | 🟢 | `McpScreen`, `McpViewModel`, `*Mcp.kt` | — | — | ✅ |
| 34 | مساحات العمل | قائمة/إضافة/إزالة | 🟢 | `WorkspacesScreen`, `WorkspaceViewModel` | — | MANAGE_EXTERNAL_STORAGE (اختياري) | ✅ |
| 35 | استيراد SAF | نسخ إلى /workspace | 🟢 | `SafWorkspaceImporter` | — | SAF | ✅ جزئيًا |
| 36 | متصفح الملفات/البحث | عرض/بحث | 🟢 | `WorkspaceExplorerScreen` | — | — | ✅ |
| 37 | عارض الكود | تلوين سطور | 🟢 | `CodeViewerScreen` | — | — | ✅ |
| 38 | Git (فرع/فرق/التزام) | من الران‑تايم | 🟢 | `WorkspaceExplorerScreen`, `ClaudeWorkspaceGit` | — | — | 🟡 |
| 39 | الطرفية المدمجة | تنفيذ أوامر | 🟢 | `TerminalScreen`, `TerminalViewModel` | — | — | 🟡 |
| 40 | تبويب طرفية داخل المستكشف | — | 🔵 | `TerminalTabPlaceholder` | — | — | ❌ |
| 41 | جدولة Cron/مرة واحدة | تنفيذ + سجل | 🟢 | `feature/schedule/*` | نعم عند التنفيذ | SCHEDULE_EXACT_ALARM, FGS | ✅ |
| 42 | وكيل الجهاز | تحكم بالشاشة | 🟢 | `device/*`, `DeviceAgentBridge` | لا | Accessibility | ✅ |
| 43 | جدار حماية الأذونات | 89 إجراءً | 🟢 | `DeviceActionFirewall`, `DeviceAgentStore` | لا | — | ✅ |
| 44 | وضع القراءة فقط | 39 إجراءً | 🟢 | `DeviceActionFirewall.READ_ONLY_ACTIONS` | لا | — | ✅ |
| 45 | سجل وتدقيق | محدود + تصدير | 🟢 | `DeviceAuditLog`, `DeviceAgentStore` | لا | — | ✅ |
| 46 | إيقاف طارئ | علم + اختصار | 🟢 | `StopAgentReceiver`, `StopAgentActivity` | لا | — | ✅ (كلمات) |
| 47 | ADB عبر USB | بروتوكول داخلي | 🟢 | `usb/Adb*.kt`, `UsbExecutor` | لا | USB | ✅ جزئيًا |
| 48 | ADB لاسلكي | tcpip + إعادة اتصال | 🟢 | `AdbConnectionManager`, `TcpTransport` | شبكة محلية | — | ✅ |
| 49 | منافذ تسلسلية | قراءة/كتابة | 🟢 | `UsbSerial*` | لا | USB | ❌ |
| 50 | Fastboot (قراءة) | getvar | 🟢 | `FastbootAgent` | لا | USB | ✅ جزئيًا |
| 51 | MTP تنزيل | عرض/تنزيل | 🟢 | `usbhub/MtpAgent` | لا | USB | ❌ |
| 52 | HID قراءة | خام | 🟡 | `HubExecutor` | لا | USB | ❌ |
| 53 | الكاميرات | عرض القائمة | 🟡 | `HubExecutor` | لا | USB | ❌ |
| 54 | مرآة العرض | screenrecord | 🟢 | `ScreenMirrorSession`, `MirrorActivity` | لا | USB | ✅ جزئيًا |
| 55 | تحكم scrcpy | لمس/مفاتيح | 🟢 | `ScrcpySession`, `RemoteControlActivity` | لا | USB | ✅ جزئيًا |
| 56 | SSH/SFTP/SCP | إدارة خوادم | 🟢 | `ssh/SshAgent`, `SshExecutor` | نعم | — | ❌ |
| 57 | SMB/FTP/WebDAV | استعراض/تنزيل | 🟢 | `remote/RemoteExecutor` | نعم | — | ❌ |
| 58 | استكشاف mDNS | 7 أنواع | 🟢 | `RemoteExecutor` | شبكة | — | ❌ |
| 59 | أدوات الشبكة | wifi/dns/ping/port/http/ws | 🟢 | `network/NetworkExecutor` | نعم | ACCESS_WIFI_STATE | ❌ |
| 60 | Bluetooth/BLE | معلومات/مسح | 🟢 | `bluetooth/BluetoothExecutor` | لا | BLUETOOTH_* | ❌ |
| 61 | Termux bridge | قراءة/تنفيذ مقيّد | 🟢 | `termux/*`, `TermuxCommandPolicy` | لا | RUN_COMMAND + منح Termux | ✅ (السياسة) |
| 62 | حاجز الروم | مطابقة الاسم | 🟢 | `payload/PayloadGuard` | لا | — | ✅ |
| 63 | تحليل payload | فك XZ/استخراج | 🟢 | `payload/PayloadArchive`, `PayloadExecutor` | لا | — | ✅ جزئيًا |
| 64 | تحقق ما قبل السلامة | تذكيرات/فحوص | 🟢 | `DeviceSafetyPreflight`, `XiaomiUnlock` | لا | — | ✅ |
| 65 | وكيل المكالمات | اتصال/رد/محادثة | 🟢 | `device/call/*` | نعم للنموذج | CALL/READ_CALL_LOG/… | ✅ جزئيًا |
| 66 | المتصفح الضيف | WebView + CDP | 🟢 | `GuestBrowserScreen`, `browser-mcp.py` | شبكة محلية | INTERNET | ❌ |
| 67 | GitHub OAuth | Device Flow | 🟢 | `GitHubAuthRepository` | نعم | — | ✅ |
| 68 | مطالبات الدعم | Star prompts | 🟢 | `GitHubStar*` | نعم | — | ✅ |
| 69 | وثائق قانونية بلا إنترنت | عرض داخل التطبيق | 🟢 | `LegalScreen`, `assets/legal/*` | لا | — | ✅ |
| 70 | 8 لغات | ترجمة كاملة | 🟢 | `values-*` | لا | — | ✅ (فحص i18n في CI) |
| 71 | 7 سِمات | مظهر | 🟢 | `ui/theme/*` | لا | — | 🟡 |
| 72 | تعطّل/تحليلات | CrashLog + Firebase اختياري | 🟢 | `core/diagnostics/*` | اختياري | — | ✅ |
| 73 | Baseline Profile | تحسين الإقلاع | 🟡 | `benchmark/*` | — | — | ❌ (لا يُشغَّل) |
| 74 | تخطيط التابلت | — | ⚪ | `TabletSettingsLayout` | — | — | ❌ |
| 75 | كاش جلسات محلي (Room) | — | ⚪ | `data/local/*` | — | — | ❌ |
| 76 | عملاء Forge (GitLab/Gitea) | — | ⚪ | `core/api/ForgeClient.kt` | — | — | ❌ |

---

## 26. جدول التقنيات

| التقنية | الإصدار | الاستخدام | الحالة | ملاحظات |
|---|---|---|---|---|
| Kotlin | 2.0.21 | لغة التطبيق | ✅ فعّال | بلا Java؛ Compose compiler plugin منفصل |
| Jetpack Compose (BOM) | 2024.12.01 | كل الواجهة | ✅ فعّال | Material 3 + أيقونات موسّعة |
| Navigation Compose | 2.8.5 | الملاحة | ✅ فعّال | 3 رسوم بيانية + 39 مسارًا ثابتًا |
| Koin | 4.0.1 | DI | 🟡 جزئي | مع بناء يدوي كثيف في Application |
| Coroutines / Flow | 1.9.0 | التزامن والحالة | ✅ فعّال | StateFlow/SharedFlow في كل الطبقات |
| OkHttp (+SSE) | 4.12.0 | HTTP/SSE | ✅ فعّال | + MockWebServer للاختبارات |
| kotlinx.serialization | 1.7.3 | JSON | ✅ فعّال | قواعد R8 مخصّصة |
| Room | 2.6.1 | كاش محلي | ⚪ غير مستخدم | KSP مضاف بلا فائدة حالية |
| EncryptedSharedPreferences (security-crypto) | 1.1.0-alpha06 | أسرار | ✅ فعّال | إصدار ألفا |
| Vosk-android | 0.3.75 | كلمة التنبيه | ✅ فعّال | يحتاج JNA keep rules؛ نموذج ينزّل عند الطلب |
| sshj / smbj / commons-net | 0.38.0 / 0.14.0 / 3.11.1 | SSH/SMB/FTP | ✅ فعّال | dontwarn لمسارات JDK |
| usb-serial-for-android | 3.7.0 | منافذ USB | ✅ فعّال | من JitPack |
| XZ / eddsa | 1.9 / 0.3.0 | تحليل OTA | ✅ فعّال | — |
| ZXing-embedded | 4.3.0 | QR | ✅ فعّال | — |
| Firebase (Analytics+Crashlytics) | BOM 33.6.0 | تشخيصات | 🟡 نسخة github فقط | معطّل في debug، Analytics opt-in |
| PRoot (Termux) | مبني من وصفة | تشغيل Linux | ✅ فعّال | لا root |
| Alpine / OpenCode | 3.24.1 / 1.18.5 | بيئة/وكيل | ✅ مثبّت بالتحقق | من manifest داخلي |
| Debian bookworm slim / agy | طبقات OCI / 1.1.7 | Antigravity | ✅ مثبّت بالتحقق | — |
| Python (خوادم MCP) | 3 (stdlib فقط) | 88+7+8 أدوات | ✅ فعّال | لا اعتماديات |
| detekt / spotless-ktlint | 1.23.6 / 1.2.1 | جودة/تنسيق | ✅ في CI | قاعدتان معطّلتان عمدًا |
| JaCoCo | مدمج | تغطية | ⚪ غير مفعّل | لا مهام |
| Gradle / AGP | 8.9 / 8.5.2 | البناء | ✅ فعّال | JDK 17، ABIs محدودة |

---

## 27. جدول المشاكل (مرتّب بالأولوية)

| المشكلة | الخطورة | الموقع | السبب | التأثير | الحل المقترح |
|---|---|---|---|---|---|
| اختبارات الأجهزة لا تُنفَّذ في CI | مرتفع | `.github/workflows/android.yml` | لا مهمة `connectedAndroidTest` | انحدارات UI لا تُكتشف | إضافة managed device/جهاز فعلي |
| لا اختبارات لطبقة SSH/SMB/FTP/WebDAV/شبكة/Bluetooth | مرتفع | `device/{ssh,remote,network,bluetooth}` | لم تُكتب | أخطاء ميدانية مؤكدة | اختبارات + خدمات وهمية |
| تثبيت الران‑تايم غير مُختبَر آليًا | متوسط | `LocalRuntimeInstaller` | يحتاج جهاز/شبكة | فشل عند المستخدم | instrumentation + تحقق SHA مسبق |
| Room + تبعيات ميتة | متوسط | `data/local`, `app/build.gradle.kts` | بقايا تصميم | حجم/زمن/التباس | حذف أو إكمال |
| NSC يسمح cleartext عالميًا | متوسط | `network_security_config.xml` | قيود الصيغة | HTTP مكشوف ممكن | تشديد + اختبار حماية |
| لا تحقق تجزئة لنموذج Vosk | متوسط | `VoskModelInstaller` | لم يُنفَّذ | نموذج غير موثوق | تثبيت SHA-256 |
| تبعية ألفا لـsecurity-crypto | متوسط | `app/build.gradle.kts` | لا نسخة مستقرة | استقرار مستقبلي | خطة ترحيل |
| Bitmap في حالة الـUI | متوسط | `ChatViewModel` | معاينات الصور | استهلاك ذاكرة | معرّفات + تحميل كسول |
| ملفات عملاقة (2767 سطرًا) | متوسط | `ChatViewModel`/`ChatHomeScreen` | تراكم | صيانة/دمج | تقسيم وحدات |
| توثيق إصدار/CI خاطئ | متوسط | `docs/RELEASE.md`, `docs/CI.md` | عدم مزامنة | فشل بناء موقَّع محليًا | تحديث الوثائق |
| `google-services.json` في Git | منخفض | `app/` | تسهيل بناء | معرّفات عامة | توليد في CI |
| سكربت توقيع Vosk يتيم | منخفض | `scripts/sign_wakeword_pack.py` | بقايا المشروع الأم | التباس | حذف/ربط |
| لا قياس تغطية | منخفض | الجذر | jacoco بلا مهام | لا مؤشر جودة | تفعيل التقرير |
| أيقونة ثانية في المشغّل | منخفض | Manifest | DeviceAgentActivity | ارتباك | دمج/إخفاء |
| `docs/COMPLETION_CHECKLIST.md` قديم | منخفض | `docs/` | تاريخ | تضليل | أرشفة/تحديث |

---

## 28. نقاط القوة التقنية

1. **تجريد ران‑تايم ناضج:** واجهة واحدة (`OpenCodeBackend`) لخمسة تنفيذات مختلفة جذريًا (HTTP، CLI JSON، PTY، JSON-RPC)، مع أعلام قدرات تُخفي ما لا يُدعم بدل تزييفه.
2. **أمان قابل للتدقيق ومُختبَر:** جدار حماية بثلاث طبقات (AUTO/CONFIRM/STRONG) + وضع قراءة فقط + سجل تدقيق مُنقّى + إيقاف طارئ + سياسات مكتوبة (`CallPolicy`, `TermuxCommandPolicy`, `VoiceDictationPolicy`).
3. **إخلاص هندسي نادر:** تعليقات مطوّلة تشرح «لماذا» (مثال: سبب تعطيل Initializers، سبب إبقاء v1 signing، سبب تجنّب `--limit 1` في تحليل CI)، ولا توجد TODO/FIXME في الكود الرئيسي.
4. **تحقق من التنزيلات بـSHA-256** لكل أصول التشغيل مع تحديث ذرّي وتراجع (Rollback journal).
5. **شبكة اختبار وحدات كبيرة (1,323 اختبارًا)** تغطي المنطق الحسّاس، مع أنماط جيدة (بذور ثابتة، محاكيات، اختبارات قانون/التزام).
6. **جودة/CI متعددة المسارات:** detekt + spotless + lint + R8 + i18n-check + commitlint، مع تجميد ذكي للمهام الثقيلة على main.
7. **حماية المستخدم قبل نفسه في الميزات الخطِرة:** لا flash/erase، لا توقيع رموز، توجيه رسمي لمسار Mi Unlock، رفض نطق OTP، وتذكيرات منفصلة عن الفحوص في `safety_preflight`.
8. **تعدد لغوي كامل (8) ووثائق قانونية داخل التطبيق بلا إنترنت.**
9. **إدارة موارد دقيقة:** WakeLock عبر Leases + إيقاف عند الخمول + توقّف المراقبة في الخلفية.
10. **توثيق داخلي غني**: 13 مستندًا + HANDOFF مهندسي + ملف سياق للوكيل نفسه.

---

## 29. القيود الحالية

**قيود معمارية/تقنية:**
- لا طبقة Domain/UseCases منفصلة، ومنطق ضخم داخل ViewModels وApplication (صعوبة اختبار جزئية).
- DI مزدوج (Koin + بناء يدوي) يزيد كلفة الفهم.
- لا قاعدة بيانات محلية فعّالة → لا عمل بلا شبكة بعد إغلاق الران‑تايم (لا كاش للجلسات/الرسائل).
- الجسور بين الضيف والتطبيق قائمة على الملفات والاستقصاء (زمن استجابة ومهل).

**قيود وظيفية معلنة:**
- MTP: استعراض/تنزيل فقط (لا رفع).
- HID: قراءة خام (لا فك مفاتيح).
- الكاميرا: سرد فقط (لا التقاط).
- Termux: المرحلة الكتابية (flash/unlock/erase) **غير مبنية عمدًا**.
- مرآة `screenrecord` بلا لمس (اللمس فقط عبر مسار scrcpy).
- لا دعم ABI آخر (armeabi/x86/32-bit) ولا أجهزة قديمة جدًا.

**قيود بيئة/توزيع:**
- FGS من النوع specialUse ومجموعة صلاحيات مرتفعة → ليست متوافقة مع نُظم متاجر صارمة.
- الاعتماد على مصادر خارجية (GitHub Releases لأربعة وكلاء، Docker Hub لطبقات Debian، موقع Vosk) — أي انقطاع/تغيير وسم يُعطّل التثبيت (رغم وجود قيم مثبّتة).
- PRoot بيئة توافقية لا حاوية أمنية.

**قيود تحقق:**
- لا اختبارات جهاز في CI؛ مصفوفة الأجهزة الفعلية معلّقة (Xiaomi API 36، Pixel 8، جهاز API 26).
- لا نتائج قياس بطارية/ذاكرة.

---

## 30. فرص التحسين

| # | الفرصة | الأثر المتوقع | الكلفة |
|---|---|---|---|
| 1 | تفعيل اختبارات الأجهزة في CI (managed devices) | اكتشاف انحدارات UI/تكامل | متوسطة |
| 2 | كتابة اختبارات لطبقة الشبكة اليدوية (خدمات وهمية: SSH/SMB/FTP/WebDAV/DNS) | تقليل أعطال ميدانية | متوسطة |
| 3 | تنظيف الكود الميت (Room/Forge/Helpers) وإزالة التبعيات المقابلة | حجم أسرع بناء وأوضح معمارًا | منخفضة |
| 4 | تثبيت SHA-256 لنماذج Vosk + حذف السكربت اليتيم | سد ثغرة سلسلة توريد | منخفضة |
| 5 | قياس أداء فعلي (تطبيق `battery_benchmark.sh` وتعبئة مصفوفة الأجهزة) | قرارات مبنية على بيانات | متوسطة |
| 6 | تقسيم `ChatViewModel`/`ChatHomeScreen` إلى وحدات | صيانة واختبار أفضل | متوسطة |
| 7 | استبدال الجسور الملفية بقناة أسرع (LocalSocket/ContentProvider/WebSocket محلي) | زمن استجابة أقل وموثوقية أعلى | مرتفعة |
| 8 | تخزين محلي فعّال (DataStore/Room مُستخدَم) لجلسات/مسودات/مفضلات | تجربة أفضل بلا شبكة | متوسطة |
| 9 | تحديث الوثائق (RELEASE/CI/CHECKLIST) وإضافة CHANGELOG آلي | تقليل أخطاء التحرير والإصدار | منخفضة |
| 10 | مراجعة NSC لتقييد cleartext إلى نطاقات محددة حيث أمكن + اختبار يمنع أي طلب غير مُصفّى | تقوية الأمان | منخفضة |
| 11 | قياس التغطية وعتبة دنيا (Jacoco) | رؤية جودة قابلة للمقارنة | منخفضة |
| 12 | توحيد الاعتماديات في `libs.versions.toml` وإزالة التكرار | بناء أنظف | منخفضة |

---

## 31. اقتراحات التطوير المستقبلية

> هذه **توصيات**، وأقربها للتنفيذ ما كان مبنيًا على بنية قائمة فعلية.

1. **إكمال مسارات الأجهزة الناقصة** بنفس نمط الأمان الحالي:
   - MTP upload (نظير `mtp_download` مع بوابة تأكيد).
   - فك مفاتيح HID إلى KeyEvent منطقي.
   - التقاط صورة من `camera_list` عبر `Camera2`/`MediaStore` مع تأكيد.
2. **المرحلة الكتابية للتفليش** وفق الشروط التي تفرضها سياسة الفريق نفسها: تأكيد مُكتوب باسم الجهاز، dry-run إلزامي، سجل تدقيق غير قابل للتعديل، ثم السماح بـ`stage/unlock/flash` في `TermuxCommandPolicy`.
3. **اقتران لاسلكي SPAKE2** لإزالة الحاجة إلى كابل USB في مسار ADB (مذكور في قائمة الطريق بـ`HANDOFF.md`).
4. **توسيع تكامل الرسائل (SMS)** وأوامر صوتية للمكالمات (بنود مذكورة في الطريق نفسه).
5. **محرّك تشخيص ذاتي** يجمع: حالة الجسور + صلاحية التخزين + حالة Accessibility + حالة FGS في تقرير واحد مشترك (المكوّنات موجودة متفرقة: `DiagnosticsSheet`, `audit_export`, `safety_preflight`).
6. **إتاحة الران‑تايم البعيد لبقية الوكلاء** (Claude/Codex عبر نفس البروتوكول) لتقليل تكرار المنطق.
7. **دعم نماذج Vosk للعربية** ليتناسب مع المستخدم الأساسي (حاليًا EN/JA فقط).
8. **طبقة ترجمة للرسائل الصلبة** المكتوبة بالإنجليزية داخل الشيفرة والاختبارات (`stringResource` مفقود في بعض المسارات القديمة).
9. **إصدار مستقر لكاش الجلسات** مع مرآة محلية للترانزكربت والصور (استفادة مباشرة من طبقة Room الحالية أو استبدالها بـDataStore).
10. **مراجعة أمنية خارجية** (المستودع يدعو إليها صراحةً في قائمة التحقق) قبل أي إصدار مستقر واسع.

---

## 32. الخلاصة

Mushrea Code في هذه الشجرة ليس «واجهة تجريبية» ولا مشروعًا مبدئيًا: هو تطبيق أندرويد ناضج (~103 آلاف سطر Kotlin) يقدّم طبقة GUI كاملة عبر أربعة وكلاء برمجة يعملون داخل بيئة Linux محلية أو عبر اتصال بخادم OpenCode، مضافًا إليها منظومة تحكم بالجهاز ذات أمان مصمّم بعناية (جدار حماية، قراءة فقط، تدقيق، إيقاف طارئ)، وميزات جوال متكاملة (صوت، جدولة، ويدجت، مشاركة، إشعارات موافقات من شاشة القفل).

القيمة الهندسية الحقيقية تكمن في:
1. **تجريد الران‑تايم** الذي يجعل «الوكيل» قابلًا للاستبدال دون لمس الواجهة.
2. **سياسة الأمان القابلة للتحقق** (89 إجراءً مصنَّفًا، رفض افتراضي، تسجيل تدقيق مُنقّى، ومنع التفليش).
3. **التحقق من الأصول** بـSHA-256 وتحديثات ذرّية قابلة للتراجع.
4. **شبكة اختبارات وحدات ضخمة** (1,323 اختبارًا) وCI متعدد المسارات.

أهم ما ينبغي معالجته قبل أي إصدار مستقر واسع:
- **لا اختبارات جهاز في CI** (والوثيقة تدّعي عكس ذلك)، ولا قياس أداء فعلي.
- **كود ميت** لم يُنظَّف (Room، ForgeClient، أدوات مساعدة) وتوثيق إصدار/CI غير محدَّث.
- **بنود أمنية صغيرة** لكنها حقيقية (نموذج Vosk بلا تحقق، NSC متساهل، تبعية ألفا)، إضافة إلى صلاحيات مرتفعة تفرض قنوات توزيع غير متاجر التطبيقات الصارمة.

التصنيف النهائي للحالة: **إنتاجي-تجريبي (Production-capable, Beta-grade)** — كل الأنظمة الرئيسية مُنفَّذة ومترابطة فعليًا في الكود، لكن التحقق الميداني (أجهزة حقيقية، شبكات حقيقية، عتاد USB/NAS، مكالمات فعلية) ما زال الفجوة الأساسية.

---

## حدود التحليل (صراحة كاملة)

### 1) ما لم أتمكن من فحصه/تشغيله فعليًا

- **لم أبنِ المشروع ولم أشغّله.** بيئة الفحص هذه لا تحتوي **JDK** (`java: command not found`)، ولا **Android SDK** (`ANDROID_HOME` فارغ)، ولا محاكي، ولا جهاز متصل، ولا `local.properties`؛ لذلك **كل** ما يتعلق بالبناء الفعلي والاختبارات والتشغيل (APK، Logcat، الاختبارات الـ1323، اختبارات الأجهزة الـ23، تثبيت الران‑تايم، مكالمة، USB، مرآة، مسح QR، OAuth حقيقي) **لم يُختبر عمليًا**، وتقييمه مبني على قراءة الكود فقط.
- **ما تم التحقق منه عمليًا بالتنفيذ:** ترجمة سكربتات Python المذكورة في CI (`py_compile` نجح)، والتحقق من ملف قفل حزم Termux (بنيته وarchitectures)، **وعدّ/match** أدوات خادم MCP للجهاز برمجيًا (88 أداة = 88 معالجًا)، وعدّ إجراءات جدار الحماية من الكود (89 = 57 + 32)، وعدّ الاختبارات والملفات والأسطر، ومقارنة اللغات في الموارد.
- **لم أُشغّل أي خادم أو عملية شبكية** (لا OpenCode ولا خوادم MCP فعليًا)؛ لا توجد مفاتيح/نماذج للاتصال بمزوّد.
- **ملفات لم أقرأها سطرًا بسطر** (بسبب الحجم): قرأتُ بالمجمل/بالمقتطفات ملفات كبيرة مثل `ChatViewModelTest.kt` (2061)، وبعض ملفات `Antigravity*`/`ClaudeCode*`/`Codex*` الداخلية، و`UsbExecutor.kt` (721)، و`PayloadGuard.kt` (702)، و`SecureSettingsRepository.kt` (610، فُحص هيكليًا وثوابته)، وبعض ملفات `res/values-*` (فُحصت بالعدّ الآلي لا بالقراءة الكاملة). لا أزعم أنني راجعت كل سطر.
- **سير عمل `ocr-review.yml`** لم أفحص محتواه بالتفصيل.

### 2) أدوات لم تكن متاحة

JDK 17، Android SDK/ADB، محاكي/جهاز، `gradlew` (يفشل بلا JDK)، `gh` لفحص تشغيلات CI الحقيقية، وشبكة حقيقية لاختبار SSH/SMB/FTP/WebDAV/mDNS.

### 3) استنتاجات غير مؤكَّدة (Inferences) صرّحتُ بها في مكانها

- أن ازدواج Koin + البناء اليدوي «مقصود» — استنتاج لا نصّ.
- أن إعادة الرسم في المحادثة الطويلة مشكلة أداء — استنتاج بلا قياس.
- أن ازدواج المجموعات في جدار الحماية/الخادم مُتزامن دائمًا — تحقّقتُ من التطابق العددي، لا من كل خريطة منطقية يدويًا.
- أن مسارات الأجهزة (USB/مرآة/مكالمات) تعمل على عتاد حقيقي — الأدلة: كود + اختبارات وحدات لمكوّنات جزئية، لا تشغيل فعلي.

### 4) أجزاء تعتمد على بيئة خارجية (لا يمكن التحقق منها داخل المستودع)

خدمات GitHub (OAuth/API)، Docker Hub و`deb.debian.org`، `dl-cdn.alpinelinux.org`، `alphacephei.com`، إصدارات الوكلاء الأربعة، سلوك FGS على HyperOS/MIUI، وأدوار Telecom/Role Dialer على أجهزة مختلفة.

### 5) ملاحظة أمانة

لم أحذف أو أعدّل أي ملف من الكود؛ التغيير الوحيد في هذه الجلسة هو إضافة هذا التقرير إلى `docs/`.
