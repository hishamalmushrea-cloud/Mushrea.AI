# BASELINE.md — خط الأساس قبل بدء التطوير المرحلي

> **المرحلة 0 — Baseline.** هذا الملف **توثيقي فقط**: لم يُعدَّل أي كود، ولم يُحذف أي ملف، ولم تُضَف أي ميزة.
> الغرض: تثبيت نقطة مرجعية دقيقة (Git / Build / Tests / المقاسات / الاعتماديات / الميزات / المشاكل المعروفة / قيود البيئة) تُقاس عليها كل مرحلة تطوير قادمة.
> **تاريخ التسجيل:** 2026-10-01 · **الفرع:** `arena/01a0f442-mushrea-ai`.

---

## 1. الحالة في Git

| البند | القيمة |
|---|---|
| الفرع العامل | `arena/01a0f442-mushrea-ai` |
| HEAD المحلي | `f63697b` — «docs: add Phase 2 verification report (build, tests, runtime, gaps)» |
| HEAD المرفوع على origin | `f63697be2b338eaf1028faa7a3d8362fff73a7d7` (مطابق) |
| سلسلة الالتزامات | `441691a` (أساس المرحلة، = `origin/main`) → `59f1f53` (تقرير المرحلة 1) → `f63697b` (تقرير المرحلة 2) |
| فرع `main` | `441691aebd8eb03959ec9b979773d282a79d58c2` |
| شجرة العمل | نظيفة (لا تعديلات معلّقة) |
| فروع arena أخرى على origin | `arena/01a0e502-…` · `arena/01a0f2f9-…` · `arena/01a0f370-…` (فروع جلسات أخرى — لا تُلمس) |

**ملاحظة تحقق مهمة:** أُعيد استنساخ البيئة الرملية (Sandbox) في بداية هذه المرحلة، ففُقدت الحالة المؤقتة القديمة (`/tmp/phase2`). تحقّقتُ أن العامل المحلي يطابق المرفوع **بمقارنة هاش كل ملف** قبل أي شيء:

```text
docs/PROJECT_ANALYSIS_AR.md          → 88c010e287f19d1430c51464154173491b59f03d  (مطابق للمرفوع)
docs/PROJECT_VERIFICATION_REPORT.md  → 5b3093aa0cc5afe8a6d2e75e401a5e15d9c458e9  (مطابق للمرفوع)
```

فلا يوجد أي فقدان للتقارير. (أي أصول اختبار سابقة في `/tmp` تُعاد إنشاؤها عند الحاجة لأنها غير مخزنة داخل المستودع.)

---

## 2. حالة البناء (Build State)

### 2.1 إعدادات البناء الحالية (ثابتة كمرجع)

| البند | القيمة |
|---|---|
| نظام البناء | Gradle **8.9** (wrapper) · Android Gradle Plugin **8.5.2** |
| Kotlin | **2.0.21** + Compose Compiler plugin + KSP `2.0.21-1.0.28` + kotlinx.serialization |
| JDK المطلوب | **17** (مستخدم في CI: Temurin 17) |
| `compileSdk` / `targetSdk` / `minSdk` | 35 / 35 / **26** |
| النكهات (Flavors) | بُعد `distribution`: `github` (بـ Firebase) و`fdroid` (بلا Google/Firebase كلّيًا عبر `-Pmushreacode.fdroidBuild=true`) |
| النسخ | `debug` (بلا تصغير) · `release` (**`isMinifyEnabled=true` + `isShrinkResources=true`** + `proguard-rules.pro`) |
| ABI | `arm64-v8a` + `x86_64` فقط |
| الوحدات | `:app` + `:benchmark` (وحدة `com.android.test`، `targetProjectPath=":app"`) |
| ملفات Gradle | 4 ملفات `.gradle.kts` + `gradle/libs.versions.toml` غير موجود (الإصدارات مكتوبة مباشرة) |

### 2.2 البناء في هذه البيئة: **مستحيل — قيد بيئي موثّق**

| المحاولة | النتيجة |
|---|---|
| توفير JDK 17 | ✅ ممكن عبر PyPI (`jdk4py==17.0.9.2`) |
| تنزيل Gradle 8.9 | ❌ `services.gradle.org` محجوب |
| Android SDK | ❌ `dl.google.com` محجوب — ولا يوجد SDK محلي |
| مستودعات Maven | ❌ `repo1.maven.org` / `maven.google.com` / `plugins.gradle.org` محجوبة |
| النطاقات المسموحة فقط | `github.com`, `api.github.com`, `codeload.github.com`, `pypi.org` |

### 2.3 دليل البناء الحقيقي: CI (بديل موثّق)

| التشغيل | الالتزام | الفرع | النتيجة | الوظائف التي نُفِّذت فعليًا |
|---|---|---|---|---|
| `36780160410` | `441691a` | `main` | ✅ success | **test-and-build** (اختبارات الوحدات + assembleGithubDebug + assembleGithubDebugAndroidTest + assembleGithubRelease) · **static-analysis** (detekt + spotless) · **lint** |
| `36783304292` | `59f1f53` | الفرع الحالي | ✅ success | `auto-format` فقط |
| `36785072713` | `f63697b` | الفرع الحالي | ✅ success | `auto-format` فقط |

**دلالة مهمة يجب ألّا تُنسى:** وظائف `test-and-build` و`lint` و`static-analysis` مشروطة في سير العمل بـ
`if: github.event_name != 'push' || github.ref == 'refs/heads/main'` — أي أنها **لا تعمل عند الدفع إلى فرع `arena/**`**، بل تعمل عند الدفع إلى `main` أو عند فتح **Pull Request**.
لذلك: أقوى دليل بناء/اختبار متاح حاليًا هو تشغيل `36780160410` على `441691a`، والفرق بينه وبين `f63697b` هو **ملفا توثيق فقط** (لا كود ولا موارد ولا Gradle). وللحصول على دليل كامل على HEAD الحالي يلزم **فتح PR** (يُشغِّل السلسلة كاملة).
- مخزونات آخر بناء كامل: `mushrea-code-debug` = 41,229,417 بايت · `mushrea-code-release-unsigned` = 14,505,054 بايت (غير منتهية، وتنزيلها من هذه البيئة محجوب).

---

## 3. حالة الاختبارات (Test State)

| الحزمة | الملفات | عدد `@Test` | تُشغَّل أين؟ | حالة آخر تشغيل |
|---|---|---|---|---|
| اختبارات الوحدات `app/src/test` | **180** | **1323** | CI على `main`/PR: `:app:testGithubDebugUnitTest` | ✅ نجحت على `441691a` (تشغيل `36780160410`) |
| اختبارات الأجهزة `app/src/androidTest` | **9** | **23** | ❌ **لا تُنفَّذ في أي مكان** (CI يصرّفها فقط: `assembleGithubDebugAndroidTest`) | ⛔ لا تشغيل |
| Benchmarks `benchmark/` | 2 | 0 (`@Test` = مولدات/قواعد) | ❌ لا تُشغَّل (تحتاج جهازًا/محاكيًا) | ⛔ لا تشغيل |

- تشغيل الاختبارات محليًا هنا **مستحيل** (لا Gradle/لا SDK) — التفصيل في §9.
- لا يوجد قياس تغطية فعّال (`jacoco` مضاف بلا مهام تغطية).
- 9 ملفات اختبارات الأجهزة: `ChatFlowE2E`, `ChatImageViewer`, `ChatVoice`, `ChatBidiDirection`, `DrawerGesture`, `AgentAuthDialogs`, `LegalScreen`, `LocalRuntimeUpdater`, `ModelAndRuntimePickerSheet`.

---

## 4. المقاسات والبنية (Sizes & Structure)

### 4.1 الأسطر والملفات (Kotlin)

| النطاق | عدد الملفات | عدد الأسطر |
|---|---|---|
| `app/src/main` | 358 | 76,394 |
| `app/src/test` | 180 | 26,034 |
| `app/src/androidTest` | 9 | 759 |
| `benchmark/src` | 2 | 76 |
| **الإجمالي** | **553** | **103,972** |

### 4.2 البنية

| البند | العدد/التفصيل |
|---|---|
| الحزم الرئيسية | `core` (api, diagnostics, lifecycle, locale, notification, runtime, security, storage, util) · `data` (connection, local, repository, schedule, settings) · `device` (bluetooth, call, mirror, network, payload, remote, ssh, termux, usb, usbhub + 22 ملفًا في الجذر) · `feature` (activity, assistant, browser, chat, onboarding, schedule, settings, share, support, wakeword, widget, workspace) · `di` |
| الشاشات | 23 ملف `*Screen.kt` · المسارات: 40 |
| ViewModels | 13 |
| الأصول (assets) | 4 سكربتات/servers (`mushreacode-{device,browser,schedule}-mcp.py` + `mushrea-code-claude-permission-hook.sh`) · `mushreacode-code-agent-context.md` (13.6KB) · `scrcpy-server-4.0` (732,226 بايت) · `local-runtime-manifest.json` · `legal/` |
| سكربتات البناء/الأدوات | 8 في `scripts/` منها `prepare_android_runtime_assets.py` و`prepare_android_runtime_native_libs.py` و`battery_benchmark.sh` (بلا نتائج مسجّلة) |
| سير العمل CI | 7 ملفات: `android.yml`, `commitlint.yml`, `fdroid-repository.yml`, `i18n-check.yml`, `ocr-review.yml`, `pr-apk-build.yml`, `release.yml` |
| الموارد | 1,220 سلسلة نصية · 7 لغات (`values-*`) |
| التوثيق | 16 ملفًا في `docs/` |

### 4.3 البيان (Manifest) — مؤكَّد بالتحليل هذه المرحلة

| البند | القيمة |
|---|---|
| Activities / Services / Receivers / Providers | 8 / 9 / 8 / 2 |
| الصلاحيات المعلنة | 30 |
| مكوّنات مُصدَّرة أو ضمنية | 9 (منها 3 خدمات نظامية محمية بـ`BIND_*`) |
| `uses-feature` / `queries` | 3 / 1 |
| خدمات أمامية | 5 (specialUse ×3، microphone، microphone\|phoneCall) |

---

## 5. أهم الاعتماديات (Key Dependencies)

| المجموعة | الاعتماديات الرئيسية |
|---|---|
| واجهة | Compose BOM `2024.12.01` · Material3 + icons-extended · Navigation-Compose `2.8.5` · Activity-Compose `1.9.3` |
| أساسيات | core-ktx `1.15.0` · lifecycle `2.8.7` · coroutines `1.9.0` · startup `1.2.0` · profileinstaller `1.4.1` |
| شبكة | OkHttp `4.12.0` + okhttp-sse · kotlinx-serialization-json `1.7.3` |
| تخزين | security-crypto `1.1.0-alpha06` · documentfile `1.0.1` · **Room 2.6.1 (`room-runtime/ktx` + KSP compiler)** |
| حقن التبعيات | Koin `4.0.1` (+ koin-androidx-compose) |
| اتصال بعيد | sshj `0.38.0` · smbj `0.14.0` · commons-net `3.11.1` · slf4j-nop `2.0.13` |
| USB/عتاد | usb-serial-for-android `3.7.0` |
| صوت | vosk-android `0.3.75` |
| ضغط/تحليل | commons-compress `1.27.1` · xz `1.9` (**مُعلنة مرتين**) · **eddsa `0.3.0`** |
| QR | journeyapps zxing-android-embedded `4.3.0` |
| تشخيص (نكهة github) | Firebase Analytics/Crashlytics |
| أدوات الجودة | detekt `1.23.6` · spotless/ktlint `6.25.0` · **jacoco (بلا مهام تصدر تقارير)** |

**ملاحظات جرد (من تقرير المرحلة 2، تبقى صالحة):** `eddsa` غير مستخدمة إطلاقًا · Room بلا مستهلك فعلي (طبقة ميتة) · `xz` مكرّرة · `security-crypto` إصدار alpha.

---

## 6. أهم الميزات — باختصار مرجعي

التفصيل الكامل والتصنيفات في `docs/PROJECT_VERIFICATION_REPORT.md` (§7، §30). الملخّص:

| المجموعة | الحالة الإجمالية |
|---|---|
| محادثة/جلسات/موافقات/أسئلة (OpenCode) | 🔵 موجود في الكود — لم يُشغَّل عمليًا |
| وكلاء: Claude Code، Antigravity، Codex | 🔵 موجود في الكود — لم يُشغَّل عمليًا |
| وكيل الجهاز + جدار حماية + MCP (88 أداة) | 🟡 طبقة العميل تحققت بالتنفيذ فعليًا · التنفيذ داخل التطبيق غير قابل للتحقق |
| MCP الجدولة (8 أدوات) | 🟢 طبقة العميل متحققة بالتنفيذ |
| MCP المتصفح (7 أدوات) | 🟡 أداة واحدة متحققة، والباقي يحتاج WebView حيًّا |
| USB/ADB/Serial/Fastboot/MTP/HID | ❓ غير قابل للتحقق (لا عتاد) |
| Terminal / scrcpy-Mirror / Voice / Calls / Network | ❓ أو 🔵 حسب القسم المذكور |
| البناء + اختبارات الوحدات + lint/detekt | 🟡 متحقق عبر CI على `441691a` |

---

## 7. المشاكل المعروفة عند خط الأساس (مدخلات المراحل القادمة)

مأخوذة من تقرير المرحلة 2 بعد التحقق، **ولم تُصلَح بعد** (ستعالج بترتيب P0…P5):

| # | المشكلة | الموقع | الأولوية |
|---|---|---|---|
| 1 | جدار الحماية: 13 إجراءً حسّاسًا تسقط إلى AUTO عبر فرع `else` (بدل CONFIRM) | `device/DeviceActionFirewall.kt` | **P0 — أمن** |
| 2 | «السماح الدائم» ببادئة الأمر لا يُطابق أبدًا (خطأ jq) + `isAlwaysAllowed` بلا مستدعٍ | `assets/scripts/mushrea-code-claude-permission-hook.sh` + `ClaudePermissionBridge.kt` | **P0** |
| 3 | اختبارات الأجهزة (23) لا تُنفَّذ في أي مكان؛ ووثيقة `device-matrix.md` تدّعي عكس ذلك | `.github/workflows/android.yml` + `docs/device-matrix.md` | **P0** |
| 4 | فرص التماسك المعماري: توزّع إدارة الران‑تايم/الوكلاء/الأدوات بين مكونات متعددة بلا مصدر واحد للحقيقة | `core/runtime` · `runtime/local` · `device` · خوادم MCP | **P0 (المراحل 1–5)** |
| 5 | Dead/Unused مؤكد: Room layer · ForgeClient · TabletSettingsLayout · DragDropAttachHelper · VoiceActivityDetector · KeepAwakeHelper · OpenCode models · `eddsa` · TerminalTabPlaceholder | موزّعة | P4 |
| 6 | نموذج Vosk بلا تحقق تجزئة · `google-services.json` مُلتزَم · NSC يسمح cleartext في base-config | `device`/`feature/wakeword` · `app/` · `res/xml` | P0/P2 |
| 7 | انحراف وثائق: `RELEASE.md` (أسماء متغيرات التوقيع `ANDROID_CODE_*` مقابل `AND_CODE_*` في الكود)، `COMPLETION_CHECKLIST.md`، `device-matrix.md` | `docs/` | P4 |
| 8 | لا قياس تغطية ولا قياس أداء (jacoco معطّل، `battery_benchmark.sh` بلا نتائج) | `build.gradle.kts` · `scripts/` | P1/P4 |

---

## 8. اتفاقيات المستودع الملزمة (مرجع سريع)

| الاتفاقية | المصدر |
|---|---|
| الرسائل بصيغة Conventional Commits: `<type>(<scope>): <description>` | `scripts/commit-msg-hook.sh` + `.github/workflows/commitlint.yml` (النطاق حرّ) |
| تقرير عربي صادق لكل مرحلة (يعمل / منفَّذ بلا اختبار / مؤجَّل) | `HANDOFF.md` |
| لا دمج/إغلاق/حذف PR أو فرع أو إصدار دون أمر صريح «ادمج» | تعليمات الجلسة + `AGENTS.md` |
| لا وسوم/إصدارات تلقائية | تعليمات الجلسة |
| الميزات تُوصَل من الطرف للطرف (firewall + bridge + MCP + agent-context + tests) لا أسماء/واجهات | `HANDOFF.md` |
| «inventory قبل الاقتراح» — جرد بالبحث قبل أي إضافة | `HANDOFF.md` |
| لا تجاوز للصلاحيات/قفل الشاشة — تدهور صادق | تعليمات الجلسة |
| لا أسرار في الكود (`google-services.json` موجود؛ وجوده يُذكر فقط ولا يُكشف أي مفتاح) | تعليمات الجلسة |

---

## 9. قيود البيئة الحالية (Environment Limitations)

| القيد | التفصيل | ما يحتاجه الاختبار الحقيقي |
|---|---|---|
| لا بناء محلي | لا Gradle (محجوب) ولا SDK ولا مستودعات Maven | شبكة تسمح بـ`services.gradle.org` + `dl.google.com` + `repo1.maven.org`، أو كاش/مرآة داخلية |
| لا تشغيل ولا اختبار حقيقي | لا جهاز، لا محاكي، لا `/dev/kvm`، لا `adb` | جهاز أندرويد فعلي أو محاكي مع KVM |
| لا عتاد | لا USB/OTG، لا ميكروفون، لا شريحة اتصال، لا Bluetooth | عتاد مطابق + أجهزة ثانوية للـUSB/المرآة |
| لا خدمات خارجية | SSH/SMB/FTP/WebDAV/OpenCode البعيد/مزوّدو النماذج/تنزيلات الران‑تايم | خوادم اختبار + شبكة مفتوحة |
| سجلات CI ومخزوناته | `*.blob.core.windows.net` محجوب → لا نتائج اختبار فردية ولا تنزيل APK | — (يكفي نجاح الوظائف كدليل حزمة) |
| الحالة المؤقتة | `/tmp` يُفقد عند إعادة الاستنساخ | إعادة إنشاء أدوات الفحص عند الحاجة (سكربتات الفحوص تُكتب في `/tmp` ولا تُخزَّن في المستودع) |

**البديل المعتمد للتحقق:** التطوير + الفحص الساكن + اختبارات الوحدات الجديدة (تُصمَّم لتكون قابلة للتشغيل في CI)، والاستدلال على النتيجة من سير عمل GitHub الفعلي. وكل ما لا يمكن التحقق منه يُكتب صراحة **`Cannot Verify — Environment Limitation`**.

---

## 10. ترتيب المراحل القادمة (P0 → P5)

| المرحلة | المحتوى | الحالة |
|---|---|---|
| **Phase 1** | Architecture Hardening — تثبيت المسار: UI → Feature → Domain → Runtime → Agent → Tool Registry → Permission/Safety → Execution → Result → Audit | **التالية** |
| Phase 2 | Runtime Manager مركزي (lifecycle موحّد لكل runtime) | تنتظر |
| Phase 3 | Agent Manager موحّد (اكتشاف/حالة/تشغيل/إيقاف/أدوات/صلاحيات/جلسات) | تنتظر |
| Phase 4 | Tool Registry مركزي (id/schema/risk/permissions/confirmation/timeout/audit) | تنتظر |
| Phase 5 | Permission & Safety Center (توحيد منطق الأمان) + إصلاح المشكلتين #1 و#2 | تنتظر |
| Phase 6–13 | Device Agent → USB → Mirror → Terminal → Browser → Scheduling → Voice → Network | تنتظر |
| Phase 14–20 | GitHub · تنظيف Dead Code · Testing · Build Verification · Regression · Documentation · Feature Matrix | تنتظر |
| Phase 21+ | Development Log مستمر · Git لكل مرحلة مستقرة | يبدأ من الآن |

---

### ملحق: أوامر إعادة إنتاج أرقام هذا الملف

```bash
git rev-parse HEAD && git status --short
find app/src/main -name '*.kt' | wc -l          # 358
find app/src/main -name '*.kt' -exec cat {} + | wc -l   # 76394
grep -ro "@Test" app/src/test | wc -l           # 1323
grep -ro "@Test" app/src/androidTest | wc -l    # 23
gh run list -R hishamalmushrea-cloud/Mushrea.AI --limit 6
gh run view -R hishamalmushrea-cloud/Mushrea.AI 36785072713 --json jobs
grep -n "compileSdk\|minSdk\|targetSdk\|abiFilters" app/build.gradle.kts
```

> **حالة المرحلة 0:** ✅ مكتملة — لم يُعدَّل أي كود، ولم يُحذف أي ملف، ولم تُضَف أي ميزة. المخرج الوحيد: هذا الملف.
