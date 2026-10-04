# BASELINE.md — خط الأساس الحالي قبل التطوير المرحلي

> **المرحلة 0 — Baseline.** هذا الملف **توثيقي فقط**: لم يُعدَّل أي كود إنتاجي، ولم يُحذف أي ملف، ولم تُضَف أي ميزة.
> الغرض: تثبيت نقطة مرجعية دقيقة (Git / Build / Tests / المقاسات / الاعتماديات / الميزات / المشاكل المعروفة / قيود البيئة) تُقاس عليها كل مرحلة تطوير قادمة.
> **تاريخ التسجيل:** 2026-10-02 · **الفرع العامل:** `arena/01a0f971-mushrea-ai` · **HEAD:** `1f551b4`.

> **تنبيه على النسخة السابقة:** كان هذا الملف (بتاريخ 2026-10-01) يصف خط أساسٍ **سابق** عند `f63697b`، أي *قبل* تنفيذ المراحل 1–5. أُعيد بناء الملف على الحالة الفعلية اليوم، والأرقام السابقة محفوظة في [الملحق أ](#ملحق-أ-خط-الأساس-السابق-محفوظ-للتتبع) لمنع فقدان التاريخ. ما تغيّر بين الخطين مسجَّل صراحةً في §7.

---

## 1. الحالة في Git

| البند | القيمة |
|---|---|
| الفرع العامل (الجلسة) | `arena/01a0f971-mushrea-ai` |
| HEAD المحلي | `1f551b40e867b29cbec8213e0c7948e465c6d434` — «docs(development): record the Phase 5 CI evidence» |
| شجرة العمل | **نظيفة** — لا تعديلات ولا ملفات غير متتبَّعة |
| `origin/main` | `441691aebd8eb03959ec9b979773d282a79d58c2` — «Update merging guidelines in AGENTS.md» (مُتحقَّق بـ`ls-remote` بعد `fetch`) |
| علاقة HEAD بـ`origin/main` | **لا يوجد Merge Base محلي** — النسخة الرملية **shallow/grafted** (`.git/shallow` يحوي `1f551b4` و`441691a`)؛ سجل ما قبل `1f551b4` غير موجود محليًا، لذلك `git log` يُظهر التزامًا واحدًا و`git rev-list --count` يعطي 1. |
| الفرع المرفوع المطابق | `origin/arena/01a0f442-mushrea-ai` = نفس الـHEAD (فرع الجلسة السابقة) |
| PR المرتبط | **#10** — «Phase 1 + 2 + 3 + 4 + 5 — Architecture · Runtime · Agent · Tools · Permissions» · `OPEN` + **`DRAFT`** · `MERGEABLE` / `CLEAN` · 131 ملفًا · ‎+7,602 / ‎−513 · `headRefOid = 1f551b4` |
| فروع arena أخرى على origin | `arena/01a0e502-…` · `arena/01a0f2f9-…` · `arena/01a0f370-…` (فروع جلسات سابقة — لا تُلمس) |

### 1.1 حالة `main` مقابل حالة فرعنا (مهم)

`main` يحتوي الدمجات #1–#9 (وكيل الجهاز، جسر Termux، صفحة فتح Xiaomi)، **ولا يحتوي أيًّا من عمل المراحل 1–5**:

| المخرَج | على `main`؟ |
|---|---|
| `app/src/main/java/com/mushrea/code/device/termux/` (PR #8) | ✅ موجود |
| `app/src/main/java/com/mushrea/code/device/tool/DeviceToolCatalog.kt` | ❌ غير موجود |
| `scripts/check_architecture.py` · `check_tool_catalog.py` · `check_permission_hook.py` | ❌ غير موجودة |
| `docs/architecture/ARCHITECTURE.md` · `docs/runtime/RUNTIME_MANAGER.md` · `docs/agents/AGENT_MANAGER.md` · `docs/tools/TOOL_REGISTRY.md` | ❌ غير موجودة |

**الأثر:** كل عمل هذه الجلسة يبنى فوق شجرة **غير مدموجة** في `main`، وهي موجودة في PR #10 كـ**مسودة**. يجب حسم مصير PR #10 (دمج/تحديث) قبل أي دمج لاحق، وإلا صار كل PR جديد يحمل شجرة المراحل 1–5 من جديد. (القرار للمالك — لا يُتخذ تلقائيًا.)

### 1.2 قيود التحقق في Git

- النسخة **shallow**: أي عملية تحتاج سجلًا كاملًا (rebase، `merge-base`، عدد الالتزامات خلف main) تفشل محليًا حتى يُجلب السجل (`git fetch --unshallow` أو `--depth` أعمق).
- آخر `fetch` نُفِّذ بنجاح وأكّد أن `origin/main` لم يتحرك منذ الاستنساخ.

---

## 2. حالة البناء (Build State)

### 2.1 إعدادات البناء الحالية (ثابتة كمرجع)

| البند | القيمة |
|---|---|
| نظام البناء | Gradle **8.9** (wrapper: `gradle-8.9-bin.zip`) · Android Gradle Plugin **8.5.2** |
| Kotlin | **2.0.21** + Compose Compiler plugin + KSP `2.0.21-1.0.28` + kotlinx.serialization |
| JDK المطلوب | **17** (CI: Temurin 17) |
| `compileSdk` / `targetSdk` / `minSdk` | 35 / 35 / **26** |
| النكهات | بُعد `distribution`: `github` (بـFirebase) · `fdroid` (بلا Google/Firebase عبر `-Pmushreacode.fdroidBuild=true`) |
| النسخ | `debug` (بلا تصغير) · `release` (**R8** + `isShrinkResources` + `proguard-rules.pro`) |
| ABI | `arm64-v8a` + `x86_64` فقط |
| الوحدات | `:app` · `:benchmark` (`com.android.test`، Baseline Profiles) |
| ملفات Gradle | 4 ملفات `.gradle.kts` (لا يوجد `libs.versions.toml` — الإصدارات مكتوبة مباشرة) |
| مهام بناء مخصصة | `preBuild` → `prepareOpenCodeRuntimeNativeLibs` → `prepareOpenCodeRuntimeAssets` (سكربتات بايثون) |
| النسخة | `1.2.26` / `versionCode 65` (`.release-version` + `build.gradle.kts` + `ReleaseMetadataTest`) |

### 2.2 البناء في هذه البيئة: **مستحيل — قيد بيئي**

| الفحص | النتيجة |
|---|---|
| `java` | ❌ غير موجود |
| `gradle` | ❌ غير موجود · `~/.gradle` غير موجود |
| Android SDK | ❌ غير موجود (`ANDROID_HOME`/`ANDROID_SDK_ROOT` فارغان) |
| `adb` | ❌ غير موجود |
| Python | ✅ 3.11.2 (لذلك سكربتات الفحص الثلاثة تعمل) |

### 2.3 دليل البناء الحقيقي: CI على **نفس هذا الـHEAD**

| التشغيل | الحدث | النتيجة | ما نُفِّذ فعليًا |
|---|---|---|---|
| **Android CI `36801349852`** | `pull_request` على `1f551b4` | ✅ success | **test-and-build**: `Validate runtime build scripts` + `:app:testGithubDebugUnitTest` + `:app:assembleGithubDebug` + `:app:assembleGithubDebugAndroidTest` (الخطوة 8 نجحت) · **static-analysis**: `Architecture layer rules` + `Tool catalog rules` + `Permission hook behaviour` + `detekt + spotlessCheck` (كلها نجحت) · **lint**: `:app:lintGithubDebug` ✅ |
| Commit Lint `36801349854` | `pull_request` | ✅ success | رسائل الالتزامات مطابقة |
| Android CI `36801345216` | `push` إلى فرعنا | ✅ success | `auto-format` فقط (بقية المهام مشروطة بـ`main` أو PR) |
| PR APK Build | `pull_request` | ⏭️ skipped | يحتاج وسم `build-apk` |

- **مخزون البناء:** `mushrea-code-debug` = **41,286,079 بايت** (غير منتهي) — موجود في التشغيل أعلاه.
- **دليل اختبارات الوحدات:** المهمة `:app:testGithubDebugUnitTest` نجحت داخل `test-and-build` على هذا الـHEAD (عدد الاختبارات المُنفَّذة فرديًّا غير قابل للاستخراج من هذه البيئة؛ القياس المتاح هو عدّ التعريفات في §3).

### 2.4 ثغرة تحقق يجب تسجيلها (جديدة في خط الأساس هذا)

**بناء الإصدار (R8/التصغير) غير مُتحقَّق منه على هذه الشجرة.** الخطوة `Build release APK` مشروطة في `android.yml` بـ`if: github.event_name == 'push'`، والدفع إلى `arena/**` لا يمرّ على `main`؛ لذلك:
- PR #10 لا يُنتج `app-github-release-unsigned.apk`، ولا يوجد مخزون إصدار لهذا الـSHA.
- آخر تحقق R8 موثَّق يعود إلى عمل سابق على `main` (قبل المراحل 1–5).

**المطلوب للتحقق:** دفع إلى `main` (بعد دمج) يُشغِّل `assembleGithubRelease`، أو إضافة مسار `workflow_dispatch` يبني الإصدار على الفرع (تغيير مقترح، لا يُنفَّذ الآن).

---

## 3. حالة الاختبارات (Test State)

| الحزمة | الملفات | تعريفات `@Test` | تُشغَّل أين؟ | الحالة |
|---|---|---|---|---|
| `app/src/test` (JUnit4) | **185** | **1,383** | CI: `:app:testGithubDebugUnitTest` على PR/`main` | ✅ نجحت على HEAD (`36801349852`) |
| `app/src/androidTest` | **9** | **23** | ❌ **لا تُنفَّذ في أي مكان** — تُصرَّف فقط (`assembleGithubDebugAndroidTest`) | ⛔ لا تشغيل |
| `benchmark/` | 2 | 0 (مولّدات ملفات تعريف) | ❌ تحتاج جهازًا/محاكيًا | ⛔ لا تشغيل |

**فحوص عقدية (بايثون) تُشغَّل محليًا وفي CI — أُعيد تشغيلها الآن على هذه الشجرة:**

| الفحص | النتيجة اليوم |
|---|---|
| `python3 scripts/check_architecture.py` | ✅ OK — 376 مصدرًا مفحوصًا · 5 استثناءات معمارية مُثبَّتة بمراحل إزالة (12/13) |
| `python3 scripts/check_tool_catalog.py` | ✅ OK — 90 مدخلًا · 88 أداة وكيل · 89 إجراءً · 32 تأكيد / 57 تلقائي / 39 قراءة فقط |
| `python3 scripts/check_permission_hook.py` | ✅ OK — 20 سلوكًا (سماح/رفض/مهلة/قواعد دائمة/بدون jq) |

- **لا قياس تغطية**: `jacoco` مُعلَن في جذر البناء ولا مهام تقارير مُعرَّفة (لا `jacocoTestReport`).
- **لا قياس أداء/بطارية مسجَّل**: `scripts/battery_benchmark.sh` بلا نتائج في `docs/device-matrix.md`.
- **قياس التوزيع:** `runtime/local` 68 ملف اختبار · `feature/chat` 19 · `device` 9 · `feature/assistant` 8 · `runtime` 7 · `feature/workspace` 7 · `feature/settings` 7.

---

## 4. المقاسات والبنية (Sizes & Structure)

### 4.1 الأسطر والملفات (Kotlin)

| النطاق | الملفات | الأسطر |
|---|---|---|
| `app/src/main` | **376** | **78,471** |
| `app/src/test` | 185 | 27,160 |
| `app/src/androidTest` | 9 | 759 |
| `benchmark/src` | 2 | 76 |
| `app/src/{github,fdroid}` (نكهات) | 4 | 156 |
| **الإجمالي** | **576** | **106,622** |

إجمالي ملفات المستودع (بلا `.git`): **769**.

### 4.2 توزيع الطبقات (المصدر الحقيقي للمعمارية)

| الطبقة | الملفات | الأسطر | الدور |
|---|---|---|---|
| `core` | 41 | 4,374 | عقود ونماذج وأمان (`api`, `permission`, `security`, `storage`, `diagnostics`, `lifecycle`, `voice`, `workspace`, `connection`, `runtime`, `locale`, `notification`, `util`, `agent`) |
| `data` | 14 | 1,919 | تخزين وإعدادات (`SecureSettingsRepository`, `AppPreferencesRepository`, `ScheduleRepository`, `DraftRepository`) + `local/` (Room — غير مستخدم) |
| `runtime` | 100 | 19,781 | الران‑تايم والوكلاء (`RuntimeRegistry`, `RuntimeTarget`, `local/*`, `remote/*`, `agent/*`, `lifecycle/*`) |
| `device` | 73 | 14,790 | وكيل الجهاز والعتاد (accessibility، firewall، usb، usbhub، mirror، ssh، remote، network، bluetooth، call، payload، termux، tool، permission) |
| `feature` | 118 | 31,497 | الشاشات والمنطق: chat (8,763) · settings (7,243) · workspace (6,112) · schedule (3,014) · assistant (2,040) · wakeword (1,332) · onboarding (1,657) · widget · share · support · browser · activity |
| `ui` | 21 | 4,628 | `MushreaCodeApp` (NavHost + الدرج) · الثيم · المكوّنات المشتركة |
| `di` · `startup` · الجذر | 9 | 1,482 | التركيب والإقلاع (`MushreaCodeApplication`, `AppModule`, `ViewModelModule`, `startup/*`) |

ترتيب الطبقات المفروض آليًا: `core < data < runtime < device < feature < ui` (و`di`/`startup`/الجذر مستثناة).

### 4.3 الواجهة

| البند | القيمة |
|---|---|
| ملفات `*Screen.kt` | 23 |
| ملفات `*ViewModel.kt` | 13 |
| ثوابت `ROUTE_*` | 29 |
| مواضع `composable(...)` | 31 |
| اللغات | 7 (`ar`, `es`, `fr`, `ja`, `pt-rBR`, `ru`, `zh-rCN`) + الافتراضي |
| السلاسل النصية | 1,220 في `values/strings.xml` |

### 4.4 البيان (Manifest)

| المكوّن | العدد | مُصدَّر (`exported=true`) |
|---|---|---|
| Activities | 8 | منها `MainActivity`, `DeviceAgentActivity`, `StopAgentActivity`, `ShareReceiverActivity` |
| Services | 9 | منها خدمتان محميتان بـ`BIND_*` (VoiceInteraction، Recognition)، وAccessibility، و3 خدمات أمامية `specialUse` + `microphone` + `microphone\|phoneCall` |
| Receivers | 8 | منها `IncomingCallReceiver` (PHONE_STATE) |
| Providers | 2 | `InitializationProvider` (بإزالة `meta-data`) · `FileProvider` (ملفات الجهاز) |

| البند | القيمة |
|---|---|
| الصلاحيات المعلنة | **30** (منها `MANAGE_EXTERNAL_STORAGE`، مجموعة الهاتف/المكالمات، `BLUETOOTH_*`، `com.termux.permission.RUN_COMMAND`، `SCHEDULE_EXACT_ALARM`) |
| مواضع `exported="true"` | 9 |
| `uses-feature` | 3 (`telephony`, `usb.host`, `camera` — كلها `required=false`) |
| كتل `queries` | 1 (RecognitionService · TTS_SERVICE · LAUNCHER · حزم Termux) |
| أنواع الخدمات الأمامية | `specialUse` ×3 · `microphone` · `microphone\|phoneCall` |

### 4.5 الأصول والأدوات

| البند | التفصيل |
|---|---|
| أصول التطبيق (28 ملفًا، 1.2MB) | 3 خوادم MCP بايثون (`device` 88 أداة · `schedule` 8 · `browser` 7) · خطاف صلاحيات Claude · 4 سكربتات مساعدة (`android-{app,instrument,screenshot,vision}.sh`) · `scrcpy-server-4.0` (732KB) · `local-runtime-manifest.json` · `mushrea-code-agent-context.md` · `legal/` (17 ملفًا) |
| سكربتات المستودع | 11 في `scripts/` منها 3 فحوص عقدية + `prepare_android_runtime_*.py` + `commit-msg-hook.sh` |
| سير عمل CI | 7 (`android`, `release`, `pr-apk-build`, `i18n-check`, `commitlint`, `fdroid-repository`, `ocr-review` [معطّل]) |
| التوثيق | 21 ملف `.md` في `docs/` |

### 4.6 الاعتماديات في الـManifest vs الكود (نقاط انتباه)

- `app/google-services.json` **مُلتزَم** بقيم Placeholder (`project_number: 000000000000`, `api_key: PLACEHOLDER_API_KEY_REPLACE_ME`) — لا سر حقيقي.
- لا ملفات مفاتيح/توقيع متتبَّعة (`*.jks`, `*.keystore`, `*.pem` = صفر في `git ls-files`)، و`.gitignore` يغطي `*.keystore` و`*.jks` و`.env` و`*.apk` و`*.aab`.
- أنماط الأسرار التي ظهرت في مسح أوّلي كانت في **ملف اختبار التنقيح** (`SecretRedactionTest.kt`) وفي **أمثلة نصية** داخل `docs/PROJECT_VERIFICATION_REPORT.md` — لا أسرار حقيقية.

---

## 5. أهم الاعتماديات (Key Dependencies)

| المجموعة | الاعتماديات |
|---|---|
| واجهة | Compose BOM `2024.12.01` + Material3 + icons-extended · navigation-compose `2.8.5` · activity-compose `1.9.3` |
| أساسيات | core-ktx `1.15.0` · lifecycle `2.8.7` (runtime/viewmodel/process) · savedstate `1.2.1` · startup `1.2.0` · profileinstaller `1.4.1` |
| شبكة | OkHttp `4.12.0` + okhttp-sse · kotlinx-serialization-json `1.7.3` |
| تخزين | security-crypto `1.1.0-alpha06` · documentfile `1.0.1` · **Room `2.6.1` + KSP** |
| حقن التبعيات | **Koin `4.0.1`** (+ koin-androidx-compose) |
| اتصال بعيد/ملفات | sshj `0.38.0` · smbj `0.14.0` · commons-net `3.11.1` · slf4j-nop `2.0.13` |
| ضغط/تحليل | commons-compress `1.27.1` · xz `1.9` ×2 · **eddsa `0.3.0`** |
| USB/عتاد | usb-serial-for-android `3.7.0` |
| صوت | vosk-android `0.3.75` (النموذج يُنزَّل عند الطلب) |
| QR | zxing-android-embedded `4.3.0` |
| تشخيص (نكهة github فقط) | Firebase BOM `33.6.0` + analytics + crashlytics (opt-in، معطّل في debug) |
| جودة | detekt `1.23.6` (maxIssues=0، baseline فارغ) · spotless + ktlint `1.2.1` · jacoco (بلا مهام) |

**ملاحظات جرد مؤكَّدة بالبحث في هذه المرحلة:** `net.i2p.crypto:eddsa` بلا أي استخدام · `slf4j-nop` بلا أي استخدام · `org.tukaani:xz` مُعلَن **مرتين** (`app/build.gradle.kts:255` و`:291`) · Room بلا مستهلك · Koin مُشغَّل بلا أي طلب حلّ.

---

## 6. أهم الميزات — باختصار مرجعي

المصفوفة التفصيلية ستكون في `docs/development/FEATURE_MATRIX.md` (تُنشأ بعد استقرار الأساس). الملخّص بمفردات الحالة المطلوبة:

| المجموعة | الحالة | ملاحظة التحقق |
|---|---|---|
| محادثة/جلسات/موافقات/أسئلة (OpenCode محلي وبعيد) | **Partially Verified** | بث SSE + 1,383 اختبار وحدة + CI أخضر؛ التشغيل الفعلي على جهاز غير متحقق |
| وكلاء Claude Code / Antigravity / Codex | **Code Present** (Beta) | بنية وحدة لكل وكيل + اختبارات؛ التشغيل الحقيقي يحتاج جهازًا + حسابًا |
| تثبيت الران‑تايم (Alpine/PRoot + Debian) | **Partially Verified** | مسار كامل مع تحقق SHA‑256؛ يحتاج جهازًا |
| وكيل الجهاز (Accessibility + firewall + MCP 88 أداة) | **Partially Verified** | طبقة العميل (MCP) مُنفَّذة ومُختبرة سابقًا؛ التنفيذ داخل التطبيق **Cannot Verify** بلا جهاز |
| سجل الأدوات (90 مدخلًا) + مركز القرار | **Verified** (ساكنًا) | `check_tool_catalog.py` + `check_permission_hook.py` أخضران، واختبارات `ToolPermissionPolicyTest`/`DeviceToolCatalogTest` موجودة |
| USB/ADB/Fastboot/Serial/MTP/HID + هب | **Code Present** | نواقص معلنة: رفع MTP، فك HID، التقاط الكاميرا = **Unsupported/Mؤجّل** |
| المرآة scrcpy + التحكم | **Code Present** | يحتاج جهازًا ثانويًا |
| SSH/SFTP/SMB/FTP/WebDAV/mDNS | **Code Present** | لم تُختبر على أجهزة/NAS حقيقية (إقرار سابق صريح) |
| شبكة/بلوتوث | **Code Present** | — |
| الجدولة (مرة/متكررة + سجل) | **Partially Verified** | اختبارات وحدة للسياسات؛ التشغيل الحقيقي يحتاج جهازًا |
| الصوت (إملاء/TTS/Barge-in/كلمة تنبيه) | **Partially Verified** | كلمة التنبيه تحتاج ميكروفون + تنزيل نموذج |
| الطرفية | **Partially Verified** | تنفيذ حقيقي عبر PRoot؛ الواجهة تحتوي أيضًا `TerminalTabPlaceholder` في تبويب آخر |
| المتصفح (MCP 7 أدوات) | **Partially Verified** | `show` محلي، والباقي يحتاج CDP حيًّا |
| GitHub (device flow + شارات PR) | **Code Present** | يحتاج شبكة/حساب |
| Room · ForgeClient · KeepAwakeHelper · VoiceActivityDetector · DragDropAttachHelper · TerminalTabPlaceholder | **Dead/Unused** | لا مستهلك في الإنتاج ولا الاختبارات |
| بناء + اختبارات الوحدات + lint + detekt | **Verified** (على CI لهذا الـHEAD) | بناء الإصدار R8 **غير متحقق** على هذه الشجرة (§2.4) |

---

## 7. ما تغيّر عن خط الأساس السابق (`f63697b` → `1f551b4`)

هذه المراحل 1–5 (الموجودة في PR #10) نُفِّذت بين الخطين:

| المقياس | عند `f63697b` | اليوم | الفرق |
|---|---|---|---|
| ملفات `app/src/main` | 358 | **376** | ‎+18 |
| أسطر `app/src/main` | 76,394 | **78,471** | ‎+2,077 |
| ملفات `app/src/test` | 180 | **185** | ‎+5 |
| اختبارات الوحدة (`@Test`) | 1,323 | **1,383** | ‎+60 |
| الاستثناءات المعمارية | 19 | **5** | ‎−14 |

**مشاكل خط الأساس السابق — حالتها اليوم (مُتحقَّق منها في الكود):**

| # | المشكلة القديمة | الحالة اليوم | الدليل |
|---|---|---|---|
| 1 | 13 إجراءً حسّاسًا تسقط إلى `AUTO` عبر فرع `else` في الجدار الناري | ✅ **مُصلَحة عند طبقة القرار** | `ToolPermissionPolicy` ترفض المعرّف المجهول أولًا، والتصنيف يأتي من `DeviceToolCatalog` (32 CONFIRM)؛ يبقى `DeviceActionFirewall.levelFor` يعيد AUTO للمعرّف غير المعروف (سلوك مُعلَن، لا مسار مستدعٍ حاليًا) |
| 2 | خطأ jq في قاعدة «السماح الدائم» + `isAlwaysAllowed` بلا مستدعٍ | ✅ **مُصلَحة** | الخطاف يستخدم `.commandPrefix as $p`، و`ClaudeCodeRuntime:249` ينادي `permissionBridge.shouldAutoAllow`، و`check_permission_hook.py` يمرّ بـ20 سلوكًا |
| 3 | اختبارات الأجهزة لا تُنفَّذ + `device-matrix.md` تدّعي عكس ذلك | ❌ **قائمة** | لا `connectedDebugAndroidTest` في أي سير عمل، والملف لا يزال يقول «CI green» (سطران 8–9) |
| 4 | تماسك معماري (لا مصدر واحد للحقيقة) | ✅ **مُعالَجة جزئيًا** | قواعد طبقات مفروضة آليًا + فك دورة `data⇄runtime` + `RuntimeLifecycle` + `AgentManager` + `DeviceToolCatalog` + مركز الصلاحيات؛ يبقى 5 استثناءات بمراحل 12/13 |
| 5 | كود ميت (Room/ForgeClient/KeepAwake/VAD/DragDrop/eddsa) | ❌ **قائمة** | أُعيد التحقق: لا مستهلك لأيٍّ منها |
| 6 | نموذج Vosk بلا تحقق تجزئة · `google-services.json` · NSC cleartext | 🟡 **يحتاج فحصًا في المرحلة 1** | `google-services.json` بقيم Placeholder (لا سر)؛ cleartext مسموح في base-config مع بوابة في الكود `OpenCodeUrl.isTrustedCleartextHost` |
| 7 | انحراف وثائق (`ANDROID_CODE_*` مقابل `AND_CODE_*`) | ❌ **قائمة** | `docs/RELEASE.md:21-24` يستخدم `ANDROID_CODE_*` والكود يقرأ `AND_CODE_*` |
| 8 | لا تغطية ولا قياس أداء | ❌ **قائمة** | `jacoco` بلا مهام · لا نتائج بطارية |

**مشاكل جديدة ظهرت في هذا الخط الأساسي:**

| # | المشكلة | الموقع | الأثر |
|---|---|---|---|
| 9 | **بناء الإصدار (R8) غير متحقق على هذه الشجرة** | `.github/workflows/android.yml` (`if: push` فقط) | لا دليل أن شجرة المراحل 1–5 تُصغَّر وتُبنى كإصدار |
| 10 | PR #10 **مسودة** ويحمل المراحل 1–5؛ `main` لا يحويها | GitHub | كل عمل هذه الجلسة مُكدَّس فوق عمل غير مدموج |
| 11 | **Koin مُشغَّل بلا مستهلك** + جذرا تركيب متوازيان (`MushreaCodeApplication` و`AppModule`) | `MushreaCodeApplication.kt` (716 سطرًا) · `di/*` | انحراف فعلي: نسخة `AppModule` من `RuntimeActivityRepository` تفتقد إشعارات الجلسات و`unreadStore` |
| 12 | **14 موضعًا** ينشئ `OkHttpClient()` مستقلًا | `core/api`, `runtime/local`, `feature/*`, `di` | لا ضبط مشترك (مهلات/اعتراضات/تجمّع اتصالات) |
| 13 | `xz` مُعلَن مرتين · `eddsa`/`slf4j-nop` غير مستخدمين | `app/build.gradle.kts` | نظافة اعتماديات |
| 14 | **نسخة shallow** بلا سجل ما قبل `1f551b4` | `.git/shallow` | أي عملية تعتمد السجل الكامل تفشل حتى يُجلب |
| 15 | `security-crypto 1.1.0-alpha06` (إصدار alpha) يحمل كل الأسرار | `app/build.gradle.kts` | مخاطرة مكتبة غير مستقرة |

---

## 8. اتفاقيات المستودع الملزمة (مرجع سريع)

| الاتفاقية | المصدر |
|---|---|
| Conventional Commits: `<type>(<scope>): <description>` | `scripts/commit-msg-hook.sh` + `.github/workflows/commitlint.yml` |
| تقرير عربي صادق لكل مرحلة (يعمل / منفَّذ بلا اختبار / مؤجَّل) | `HANDOFF.md` |
| لا دمج/إغلاق/حذف PR أو فرع أو إصدار دون أمر صريح | `AGENTS.md` + تعليمات الجلسة |
| لا وسوم/إصدارات تلقائية | تعليمات الجلسة |
| الميزة تُوصَل من الطرف للطرف (firewall + bridge + MCP + agent-context + tests) | `HANDOFF.md` |
| «inventory قبل الاقتراح» — جرد بالبحث قبل أي إضافة | `HANDOFF.md` |
| لا تجاوز للصلاحيات/قفل الشاشة — تدهور صادق | تعليمات الجلسة |
| كل أداة جهاز تُوصَف في أربعة مواضع متناسقة (كتالوج الكوتلن · سكربت MCP · فرع التنفيذ · الجدار) | `scripts/check_tool_catalog.py` |
| أي نص واجهة جديد ⇒ إضافته لكل اللغات السبع | `.github/workflows/i18n-check.yml` |

---

## 9. قيود البيئة الحالية (Environment Limitations)

| القيد | التفصيل | ما يحتاجه التحقق الحقيقي |
|---|---|---|
| لا بناء محلي | لا JDK ولا Gradle ولا SDK ولا مستودعات Maven | شبكة تسمح بـ`services.gradle.org` + `dl.google.com` + `repo1.maven.org`، أو كاش/مرآة |
| لا تشغيل ولا اختبار حقيقي | لا جهاز، لا محاكي، لا `/dev/kvm`، لا `adb` | جهاز Android فعلي أو محاكي مع KVM |
| لا عتاد | لا USB/OTG، لا ميكروفون، لا شريحة، لا Bluetooth | عتاد مطابق + أجهزة ثانوية |
| لا خدمات خارجية | SSH/SMB/FTP/WebDAV/OpenCode البعيد/مزوّدو النماذج/تنزيلات الران‑تايم | خوادم اختبار + شبكة مفتوحة |
| سجلات CI ومخزوناته | النطاق `*.blob.core.windows.net` محجوب | — (يكفي نجاح المهام كدليل) |
| نسخة Git shallow | سجل ما قبل `1f551b4` غير موجود محليًا | `git fetch --unshallow` |

**البديل المعتمد:** تطوير + فحص ساكن + اختبارات وحدة تُشغَّل في CI، والاستدلال على النتيجة من سير عمل GitHub الفعلي. وكل ما لا يمكن التحقق منه يُكتب صراحةً **`Cannot Verify — Environment Limitation`**.

---

## 10. مدخلات المرحلة التالية (Phase 1 — Foundation Audit)

المرحلة 1 المطلوبة **تدقيق بلا تعديل** للمناطق الأساسية (Runtime · Agents · Tools · Security · التركيب/DI · التخزين · الشبكة) مع قرار لكل منطقة: **يُبقى / يُوحَّد / يُقسَّم / يُحذف لاحقًا / لا يُلمس**، ثم خطة تنفيذ صغيرة.

**المدخلات التي يجب أن يبدأ منها التدقيق (مشتقة من هذا الخط الأساسي):**

1. **مصير PR #10** — قرار مالك مطلوب قبل أي عمل جديد (دمج المراحل 1–5 أولًا أم لا).
2. **فجوة تحقق الإصدار (R8)** — إغلاقها بوسيلة لا تعتمد على الدمج.
3. **جذور التركيب المتوازية وKoin** — توحيد أو إزالة، مع إثبات عدم الاستخدام أولًا.
4. **العقود الناقصة في مسار أدوات الجهاز** — التحقق بعد التنفيذ (موجود لـ`open_app` فقط)، والتدقيق الموحَّد، و`paramsDigest`/`verifyOutcome`.
5. **الأنظمة المتوازية الصغيرة** — 14 عميل OkHttp، طبقة Room الميتة، `ForgeClient`، و`TerminalTabPlaceholder` مقابل `TerminalScreen` الحقيقي.
6. **انحراف الوثائق** — `RELEASE.md` و`device-matrix.md`.
7. **الفجوة القائمة: 23 اختبار جهاز لا تُنفَّذ** — قرار: تشغيلها في CI (managed device) أو تصنيفها صراحةً كغير منفَّذة وتصحيح الوثيقة.

---

## ملحق: أوامر إعادة إنتاج أرقام هذا الملف

```bash
# Git
git rev-parse HEAD && git status --porcelain | wc -l
git ls-remote origin | grep refs/heads/main          # main = 441691a
cat .git/shallow                                      # نسخة shallow
gh pr view 10 --json state,isDraft,mergeable,mergeStateStatus,headRefOid

# الحجم والمقاسات
find app/src/main -name '*.kt' | wc -l                # 376
find app/src/main -name '*.kt' -exec cat {} + | wc -l # 78471
grep -rho '@Test' app/src/test | wc -l                # 1383
grep -rho '@Test' app/src/androidTest | wc -l         # 23
find . -path ./.git -prune -o -name '*.kt' -print | wc -l  # 576

# الفحوص العقدية
python3 scripts/check_architecture.py
python3 scripts/check_tool_catalog.py
python3 scripts/check_permission_hook.py

# أدلة CI (يجب اختيار التشغيل بالـSHA لا بالحد الأقصى)
gh api "repos/hishamalmushrea-cloud/Mushrea.AI/actions/runs?head_sha=1f551b40e867b29cbec8213e0c7948e465c6d434&per_page=20" \
  --jq '.workflow_runs[] | "\(.id) \(.name) \(.event) \(.conclusion)"'

# جرد عام
grep -rn "OkHttpClient()" --include='*.kt' app/src/main | wc -l          # 14
grep -c "org.tukaani:xz" app/build.gradle.kts                            # 2
grep -rn "org.koin" --include='*.kt' app/src/main | wc -l                # 3 ملفات تركيب فقط
```

---

## ملحق أ: خط الأساس السابق (محفوظ للتتبع)

الخط السابق (2026-10-01) وصف الحالة عند `f63697b` على الفرع `arena/01a0f442-mushrea-ai`، أي **قبل** المراحل 1–5:

| المقياس | القيمة السابقة |
|---|---|
| HEAD | `f63697b` («docs: add Phase 2 verification report») |
| `main` | `441691a` (لم يتغيّر) |
| ملفات `app/src/main` | 358 |
| أسطر `app/src/main` | 76,394 |
| ملفات `app/src/test` | 180 |
| اختبارات الوحدة | 1,323 |
| `app/src/androidTest` | 9 ملفات / 23 اختبارًا (غير مُنفَّذة) |
| الإجمالي (كل ملفات Kotlin) | 553 ملفًا / 103,972 سطرًا |
| الاستثناءات المعمارية | 19 |
| دليل CI المعتمد | تشغيل `36780160410` على `441691a` (test-and-build + static-analysis + lint) |
| مخزونات آخر بناء كامل | debug = 41,229,417 بايت · release-unsigned = 14,505,054 بايت |

> **حالة المرحلة 0 (هذه):** ✅ مكتملة — لم يُعدَّل أي كود إنتاجي، ولم يُحذف أي ملف، ولم تُضَف أي ميزة. المخرجات: تحديث هذا الملف + قيد في `DEVELOPMENT_LOG.md`.
