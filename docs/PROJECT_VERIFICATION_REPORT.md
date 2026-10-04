# PROJECT_VERIFICATION_REPORT.md

> **تقرير التحقق من مشروع Mushrea Code — المرحلة الثانية (Static + Build + Runtime + Gap Verification)**
> الشجرة المدقَّقة: `hishamalmushrea-cloud/Mushrea.AI` عند الالتزام `441691a` (الفرع `arena/01a0f442-mushrea-ai`)، مع تقرير المرحلة الأولى `docs/PROJECT_ANALYSIS_AR.md` كخط أساس (Baseline) — لم يُعد كتابته ولم يُحذف.
> **تاريخ التحقق:** 2026-10-01.
> **القاعدة الحاكمة للتقرير:** لا تُستخدم «يعمل» إلا بعد التحقق الفعلي، و«موجود في الكود» عند الدليل الساكن فقط، و«غير قابل للتحقق» عند منع البيئة. **لم يُعدَّل أي كود ولا أي ملف من المشروع أثناء هذه المرحلة**؛ كل ملفات الفحص والاختبار كُتبت خارج المستودع في `/tmp/phase2/`.

---

## 1. Executive Summary

### 1.1 لماذا لم يكتمل التحقق كما طُلب

طُلب في هذه المرحلة بناء المشروع وتشغيل اختباراته وتجربة ميزاته فعليًا. البيئة الحالية **لا تسمح بذلك**، والسبب كلّه بيئي وليس مشروعيًا (التفصيل في الأقسام 3–5):

| العقبة | الحالة الفعلية | الأثر |
|---|---|---|
| Android SDK | غير موجود، ولا يمكن تنزيله (`dl.google.com` محجوب) | لا بناء ولا lint ولا اختبارات وحدات محليًا |
| توزيعة Gradle 8.9 | `services.gradle.org` محجوب (فشل `./gradlew` بـ SSL peer shut down) | لا يمكن تشغيل أي مهمة Gradle |
| مستودعات Maven | `repo1.maven.org` / `maven.google.com` / `plugins.gradle.org` محجوبة | لا يمكن حلّ أي اعتمادية حتى لو توفّرت التوزيعة |
| محاكي/جهاز | لا جهاز، ولا `/dev/kvm`، ولا `adb` | لا تشغيل للتطبيق ولا اختبارات أجهزة |
| الشبكة الخارجية | مسموح فقط: `github.com`, `api.github.com`, `pypi.org`, `codeload.github.com` | لا تنزيلات Android، لكن سمح بجلب JDK 17 عبر PyPI وقراءة سجل CI الحقيقي |

JDK 17 **تم توفيره فعليًا** (`jdk4py==17.0.9.2` عبر pip → OpenJDK Temurin 17.0.9)، لكنه بلا Gradle وبلا SDK لا يكفي.

### 1.2 ما استُبدل به التحقق المحلي: أدلة CI الحقيقية

مستودع GitHub متاح، لذلك تم التحقق من **مخرجات CI الفعلية** على نفس الالتزام `441691a` (تشغيل `36780160410`):

| المهمة | النتيجة | الدلالة |
|---|---|---|
| `test-and-build` | ✅ نجحت | شغّلت `:app:testGithubDebugUnitTest` (كل اختبارات الوحدات الـ1323) + `assembleGithubDebug` + `assembleGithubDebugAndroidTest` (تصريف فقط) |
| `Build release APK` | ✅ نجحت | بناء R8/تصغير كامل لنسخة الإصدار |
| `static-analysis` (detekt + spotless) | ✅ نجحت | لا ملاحظات مُصعَّدة (خطوة «Surface findings» تُخطّت) |
| `lint` (`:app:lintGithubDebug`) | ✅ نجحت | لا أخطاء lint |

والمخزونات (Artifacts) موجودة وغير منتهية: **debug APK = 41,229,417 بايت**، و**release unsigned = 14,505,054 بايت**. لكن تنزيلها من هذه البيئة فشل (نطاق `*.blob.core.windows.net` محجوب)، فلم أتمكن من فحص الـAPK نفسه.

### 1.3 ما تم التحقق منه عمليًا بالتنفيذ في هذه البيئة

كل ما يلي نُفِّذ فعليًا من داخل المستودع (قراءة فقط) وبواسطة مكوّنات المشروع نفسها:

| المكوّن | ما نُفِّذ | النتيجة |
|---|---|---|
| `mushreacode-device-mcp.py` | تشغيل خادم MCP كامل عبر stdio + جسر وهمي يحاكي التطبيق + استدعاء **كل الأدوات الـ88** | 88 أداة فريدة = 88 معالجًا؛ **86 أداة** عبرت الجسر، و2 محليتان (`device_get_context`, `device_press`)، 0 مهلة، 0 أخطاء RPC |
| مسارات الخطأ في نفس الخادم | رفض من التطبيق + عدم رد + نتيجة قديمة + JSON تالف | رسالة الرفض تُسلَّم حرفيًا؛ المهلة 15.0 ث للمهمة القصيرة؛ **لا تسرّب لنتيجة قديمة**؛ JSON التالف لا يُسقط الخادم |
| `mushreacode-schedule-mcp.py` | تشغيل الخادم + تطبيق وهمي يستهلك `pending/` ويكتب `responses/` | **8/8 أدوات** تعمل، والعمليات المرسلة مطابقة (`list,get,runs,create,update,delete,setEnabled,runNow`)، ومهلة 90.1 ث مطابقة للثابت |
| `mushreacode-browser-mcp.py` | تشغيل الخادم + استدعاء الأدوات السبع | `browser_show` كتب ملف الأمر بنجاح؛ الأدوات الست المعتمدة على CDP تفشل برسالة واضحة (لا WebView هنا) |
| `mushrea-code-claude-permission-hook.sh` | allow/deny/timeout/question/no-jq/always-rules | يعمل في كل المسارات؛ **واكتُشف خطأ حقيقي**: قاعدة «السماح الدائم» ذات البادئة لا تُطابق أبدًا (خلل jq) |
| `scripts/commit-msg-hook.sh` | 4 رسائل (نطاق/بسيطة/Merge/غير صحيحة) | يرفض غير المطابق ويقبل الصحيح ✅ |
| جدار حماية وكيل الجهاز | إعادة اشتقاق الأرقام من الشفرة المصدرية + مقارنة الأدوات/المعالجات/التنفيذ | 89 إجراءً، AUTO=57، CONFIRM الصريح=**19**، و**13 إجراءً حسّاسًا يسقط إلى AUTO بسبب فرع `else` المتساهل** (تصحيح لرقم تقرير المرحلة الأولى) |
| البيان (Manifest) | تحليل برمجي كامل بـ Python | 8 Activities، 9 Services، 8 Receivers، 2 Providers، **30 صلاحية**، 9 مكوّنات مُصدَّرة/ضمنية، 5 خدمات أمامية |

### 1.4 الخلاصة

المشروع **يُبنى ويجتاز اختباراته الوحدوية و lint و detekt على CI فعليًا**، وطبقة الأتمتة الخارجية (خوادم MCP والخطّافات) **تعمل فعليًا على مستوى العقد**، لكن **لا يمكن اليوم القول إن أي سلوك داخل تطبيق Android قد تم التحقق منه عمليًا**: كل ما يتصل بالواجهة والتشغيل على الجهاز يبقى «موجود في الكود» أو «غير قابل للتحقق». وتمّت خلال هذه المرحلة تصحيحات جوهرية لتقرير المرحلة الأولى (أهمها: تصنيف أذونات جدار الحماية، وميزة Always-allow المعطوبة، وتبعية `eddsa` غير المستخدمة).

---

## 2. What Was Verified

الرموز: ✅ **تم التحقق عمليًا** (نُفِّذ فعلًا) · 🔵 **موجود في الكود** (دليل ساكن فقط) · ⛔ **غير قابل للتحقق** (البيئة تمنع).

| # | العنصر | الطريقة | النتيجة | الحكم |
|---|---|---|---|---|
| 1 | بيئة الفحص (نظام/موارد/شبكة) | أوامر فعلية | Debian 12، 2 vCPU، 3GB RAM، بلا `/dev/kvm`، قائمة نطاقات مسموحة | ✅ |
| 2 | JDK 17 | `pip3 install --break-system-packages jdk4py==17.0.9.2` | `java -version` = Temurin 17.0.9 | ✅ |
| 3 | بناء المشروع محليًا | `./gradlew --version` | فشل: `SSL peer shut down incorrectly` أثناء تنزيل Gradle 8.9 | ✅ (فشل موثّق) |
| 4 | بناء المشروع على CI | قراءة تشغيلات GitHub Actions | نجاح test-and-build/static-analysis/lint على `441691a` | ✅ |
| 5 | حزمة اختبارات الوحدات (1323) | تشغيل CI لمهمة `testGithubDebugUnitTest` | نجحت كحزمة؛ تعذّر استخراج نتائج كل اختبار على حدة (سجلات CI محجوبة) | 🟡 جزئي |
| 6 | اختبارات الأجهزة (23) | فحص سير العمل | تُصرَّف (`assembleGithubDebugAndroidTest`) ولا تُنفَّذ قط | ⛔ |
| 7 | خادم MCP للجهاز | تشغيل فعلي + 88 استدعاء | 88/88 قابلة للاستدعاء، 86 جسر، 2 محلية | ✅ |
| 8 | خادم MCP للجدولة | تشغيل فعلي + تطبيق وهمي | 8/8 أدوات، رفض ومهلة 90 ث صحيحة | ✅ |
| 9 | خادم MCP للمتصفح | تشغيل فعلي | `browser_show` ✅؛ بقية الأدوات تحتاج WebView (فشل نظيف) | 🟡 جزئي |
| 10 | خطاف صلاحيات Claude | 7 سيناريوهات تنفيذ | allow/deny/timeout/question/no-jq/بلا بادئة ✅، وبادئة 🔴 | ✅ |
| 11 | خطاف رسائل الالتزام | 4 حالات | يعمل | ✅ |
| 12 | جدار حماية وكيل الجهاز (الأرقام) | إعادة اشتقاق + مقارنة | 89/57/19/13/39 — وتصحيح خطأ التصنيف | ✅ |
| 13 | تكافؤ أدوات MCP ⇄ المعالجات | استيراد الوحدة وعدّ المجموعات | 88 = 88، لا فرق | ✅ |
| 14 | تكافؤ إجراءات MCP ⇄ جدار الحماية | مقارنة مجموعات | 85 حرفيًا + 4 ديناميكية = 89 بلا فرق | ✅ |
| 15 | البيان (Manifest) | تحليل XML برمجي | الأعداد والقوائم أعلاه | ✅ |
| 16 | فحص استهلاك التبعيات | بحث استيراد لكل مكتبة | `eddsa` غير مستخدمة إطلاقًا؛ البقية مستخدمة | ✅ |
| 17 | أي سلوك داخل تطبيق Android | — | لا جهاز/محاكي/SDK | ⛔ |
| 18 | USB / scrcpy / مكالمات / صوت / جدولة تطبيقية | — | لا عتاد | ⛔ |
| 19 | أداء التطبيق (ذاكرة/بطارية/تقطيع) | — | لا قياس ممكن | ⛔ |

**ملفات الفحص** (خارج المستودع): `/tmp/phase2/harness_mcp.py`، `test_device_mcp.py`، `test_device_scenarios.py`، `test_device_scenarios2.py`، `test_schedule_mcp.py`، `test_browser_mcp.py`، `schedule_wrapper.py`، `verify_firewall2.py`، `manifest_audit.py`، و`device_sweep.json`، `firewall_parity2.json`، `manifest_components.json`. المستودع لم يُلمس.

---

## 3. Build Results

### 3.1 محاولة البناء المحلي (فشلت — سبب بيئي)

| البند | القيمة |
|---|---|
| الأمر | `JAVA_HOME=<jdk4py> ./gradlew --version` |
| الخطأ | `java.io.EOFException: SSL peer shut down incorrectly` (وأعلاه `SSLException`) |
| الملف/السطر | `org.gradle.wrapper.Download.downloadInternal(Download.java:129)` ← `Install.forceFetch(Install.java:171)` |
| السبب المباشر | الملف المطلوب `https://services.gradle.org/distributions/gradle-8.9-bin.zip` غير قابل للوصول من هذه البيئة |
| المشكلة في المشروع أم البيئة؟ | **البيئة** — المشروع يحدد `distributionUrl` القياسي في `gradle/wrapper/gradle-wrapper.properties`، ولا شيء غير طبيعي فيه |
| ما الذي يمكن إصلاحه | لا شيء في المشروع. الحل بيئي: شبكة تسمح بـ`services.gradle.org` و`dl.google.com` و`repo1.maven.org`، أو Gradle توزيعة + SDK + كاش Maven محلي |

### 3.2 مصفوفة الشبكة المقيسة (سبب الاستحالة)

| النطاق | HTTP | ملاحظة |
|---|---|---|
| `github.com`, `api.github.com` | 200 | يعمل |
| `pypi.org` (+`files.pythonhosted.org`) | 200 | لذلك نجح تركيب JDK |
| `codeload.github.com` | 301 | يستجيب |
| `services.gradle.org`, `dl.google.com`, `maven.google.com`, `repo1.maven.org`, `plugins.gradle.org`, `deb.debian.org`, `raw.githubusercontent.com`, `objects.githubusercontent.com`, `adoptium.net`, `download.oracle.com`, `cache-redirector.jetbrains.com` | **000** | فشل TLS حتى مع فرض IPv4 (`SSL_ERROR_SYSCALL`)، وDNS يحلّها جميعًا → حجب بفلترة SNI لا بمشكلة DNS |
| `*.blob.core.windows.net` (سجلات/مخزونات GitHub Actions) | **EOF** | لهذا تعذّر تنزيل الـAPK وسجلات الوظائف |

### 3.3 بديل التحقق: بناء CI الفعلي

| البند | القيمة |
|---|---|
| التشغيل | `36780160410` — push إلى `main` عند الالتزام `441691a` |
| المدة الكلية | 2m19s |
| المهام | `test-and-build` ✅ · `static-analysis` ✅ · `lint` ✅ · `auto-format` (متخطاة) |
| أوامر المهمة الأساسية | `./gradlew :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest` ثم `./gradlew :app:assembleGithubRelease` |
| مخزونات | `mushrea-code-debug` (41,229,417 B) و`mushrea-code-release-unsigned` (14,505,054 B)، غير منتهية |
| خطوات التحقق من سكربتات الران‑تايم | «Validate runtime build scripts: success» (تشمل فحص أصول Termux عبر Python) |
| سجل الفشل | خطوات «Summarize…» و«Surface…» كلها متخطاة = لا مخرجات فشل |

**الحكم على البناء:** 🟡 **مُتحقَّق منه عبر CI فقط**. البناء نجح فعلًا (Debug + Release/R8) على نفس الشجرة المدقَّقة، لكنه **لم يُعَد إنتاجه محليًا**، ولم يُفحص ناتج الـAPK (لا يمكن تنزيله).

---

## 4. Unit Test Results

### 4.1 المحاولة المحلية

⛔ **لم أتمكن من تشغيل أي اختبار وحدات محليًا.** لا Gradle (التوزيعة غير قابلة للتنزيل) ولا Android SDK ولا `local.properties`. الأمر المتوقع `./gradlew :app:testGithubDebugUnitTest` يفشل قبل تنفيذ أي اختبار.

### 4.2 ما يمكن إثباته من CI

| البند | القيمة |
|---|---|
| الحزمة | 180 ملف اختبار، **1323** دالة `@Test` |
| أمر CI | `:app:testGithubDebugUnitTest` ضمن الخطوة «Test and build debug APK» |
| النتيجة على `441691a` | ✅ الخطوة نجحت — وGradle يُفشل المهمة عند أي اختبار فاشل، فالاستنتاج: **الحزمة كاملة نجحت على CI** |
| التفاصيل الفردية | ⛔ غير متاحة: سجلات الوظائف وتقارير XML على `blob.core.windows.net` المحجوب، والمخزونات لا تحوي تقارير اختبارات |
| تشغيلات أخرى | كل تشغيلات `main` الحديثة في `gh run list` بحالة success (لا فشل مسجّل) |

| السؤال | الجواب |
|---|---|
| الاختبار موجود؟ | نعم — 1323 |
| تم تشغيله؟ | نعم، **على CI** (وليس بواسطتي محليًا) |
| نجح؟ | نعم، كحزمة (دليل: نجاح الخطوة) |
| فشل؟ | لا فشل مسجَّل في هذا التشغيل |
| السبب إن لم يُشغَّل محليًا | بيئة: لا Gradle/SDK/شبكة Maven |

**الإجمالي:** نجحت (حسب CI) = 1323 · فشلت = 0 مسجّل · لم يمكن تشغيلها محليًا = 1323 · نسبة النجاح على CI = 100% للحزمة (دون تفصيل فردي).

---

## 5. Instrumentation Test Results

| البند | القيمة |
|---|---|
| الملفات | 9 ملفات في `app/src/androidTest` |
| عدد `@Test` | 23 |
| ما يفعله CI | يصنّفها فقط: `:app:assembleGithubDebugAndroidTest` |
| تنفيذ فعلي | **لا يوجد في أي سير عمل** `connectedAndroidTest` ولا إدارة محاكي |
| محليًا | ⛔ لا محاكي/جهاز، ولا `/dev/kvm` |

| الملف | الموضوع |
|---|---|
| `ChatFlowE2ETest.kt` | تدفق المحادثة عبر الواجهة (Compose rule) |
| `ChatImageViewerInstrumentedTest.kt` | عارض الصور |
| `ChatVoiceInstrumentedTest.kt` | عناصر الصوت في المحادثة |
| `ChatBidiDirectionInstrumentedTest.kt` | اتجاه النص ثنائي الاتجاه |
| `DrawerGestureInstrumentedTest.kt` | إيماءة الدرج |
| `AgentAuthDialogsInstrumentedTest.kt` | حوارات تسجيل دخول الوكلاء |
| `LegalScreenInstrumentedTest.kt` | الشاشة القانونية |
| `LocalRuntimeUpdaterInstrumentedTest.kt` | تحديث الران‑تايم (عناصر الواجهة) |
| `ModelAndRuntimePickerSheetInstrumentedTest.kt` | منتقي النموذج/الران‑تايم |

**الحكم:** ⛔ **غير قابل للتحقق** — هذه الاختبارات الـ23 لم تُنفَّذ في أي مكان (لا محليًا ولا CI)، وتقرير المرحلة الأولى كان محقًا في هذا، لكن `docs/device-matrix.md` (سطرا 8–9) يوحي بتشغيل `connectedDebugAndroidTest` على CI — وهذا **غير صحيح** (تناقض وثيقة/واقع مُعاد تأكيده).

---

## 6. Runtime Verification

### 6.1 ما نُفِّذ فعلًا (مع قياسات)

| السيناريو | القياس | النتيجة |
|---|---|---|
| مسح 88 أداة على جسر وهمي | 26 ثانية إجمالًا، ~0.3 ث للأداة | 86 عبرت الجسر، 2 محليتان، 0 مهلة/0 خطأ RPC |
| `device_press` back/home/recents | 0.3 ث لكل | أرسل `press_back`/`press_home`/`open_recents` بدقة (يثبت قيم الجدار الأربع الديناميكية) |
| `device_press` قيمة غير صحيحة | 0.0 ث | رفض محلي فوري بلا دور‑تريب |
| رفض من التطبيق (`ok:false`) | 0.3 ث | الرسالة الأصلية تصل كما هي كخطأ أداة |
| لا رد من التطبيق | 15.0 ث | رسالة المهلة التشخيصية الصحيحة |
| نتيجة قديمة بمعرّف مختلف | — | **لا تسرّب** (تجاهلها وانتظر) |
| نتيجة JSON تالفة | 15.0 ث | تجاهل آمن ثم مهلة نظيفة |
| `device_get_context` | 0.0 ث | يقرأ ملف السياق مباشرة بلا جسر |
| جدولة: 8 عمليات | 0.3 ث لكل | مطابقة تامة للعمليات والتوقيعات |
| جدولة: رفض التطبيق | 0.3 ث | `BridgeError: unknown schedule id` |
| جدولة: لا تطبيق | 90.1 ث | `no reply from the MushreaCode app within 90 s` |
| متصفح: `browser_show` | 0.0 ث | كتب `.mushrea-code/browser-command.json` بالمحتوى الصحيح |
| متصفح: أدوات CDP | 0.0 ث | `CdpError: WebView devtools socket not found…` (فشل نظيف ومقصود) |
| خطاف Claude: allow/deny | 1–2 ث | مخرجات `hookSpecificOutput` الصحيحة |
| خطاف Claude: مهلة | مهلة 2 ث | deny مع «User did not respond in time» وتنظيف |
| خطاف Claude: سؤال | — | `kind: "question"` + الحقول كاملة في ملف الطلب |
| خطاف Claude: no-jq | 1.1 ث | مسار sed البديل يعمل |
| خطاف Claude: always-rule بلا بادئة | 0.26 ث | سماح فوري |
| خطاف Claude: always-rule ببادئة | **مهلة 20 ث** | 🔴 **لا يعمل** — انظر §23 |
| خطاف الالتزام | <0.1 ث | 4/4 حالات صحيحة |

### 6.2 ما لم يمكن تنفيذه (وسببه)

⛔ تشغيل التطبيق · Onboarding · Workspace · Local Runtime · OpenCode · Claude/Antigravity/Codex · Streaming · Tool calls · Permissions · Sessions · إعادة التشغيل · Device Agent على جهاز · USB · Mirror/scrcpy · المكالمات · الصوت · Vosk · الجدولة الفعلية (AlarmManager) · الشبكة الفعلية · OTA/Payload على ملف حقيقي.
السبب الوحيد في كل بند: لا جهاز أندرويد/محاكي/SDK في البيئة + حجب المصادر.

---

## 7. Feature Verification Matrix

**وسيلة الإيضاح:** Code = هل الميزة موجودة في الكود؟ · Unit = تغطية اختبارات الوحدات (عدد الملفات/الاختبارات التقريبي من الفحص) · Build = هل تُصرَّف/تُبنى في CI؟ · Runtime = هل تم تشغيلها فعليًا في هذه البيئة؟ · Hardware = هل تحتاج عتادًا غير متاح؟ · Final = التصنيف النهائي.

| # | الميزة | Code | Unit Tests | Build | Runtime | Hardware | Final Status |
|---|---|---|---|---|---|---|---|
| 1 | بناء Debug APK | ✅ | — | ✅ CI (`assembleGithubDebug`) | ⛔ (Gradle محجوب) | لا | 🟡 Partially Verified |
| 2 | بناء Release/R8 | ✅ | — | ✅ CI (`assembleGithubRelease`) | ⛔ | لا | 🟡 Partially Verified |
| 3 | توقيع الإصدار الحقيقي | ✅ | — | غير مُنفَّذ (يحتاج أسرار) | ⛔ | لا | ❓ Cannot Determine |
| 4 | اختبارات الوحدات (1323) | ✅ | 180 ملفًا | ✅ CI (تشغيل كامل) | ⛔ محليًا | لا | 🟡 Partially Verified |
| 5 | اختبارات الأجهزة (23) | ✅ | 9 ملفات | تُصرَّف فقط | ⛔ | نعم | ❓ Cannot Determine |
| 6 | Baseline Profile | ✅ | 0 | وحدة `:benchmark` تُبنى | ⛔ | نعم (pixel6Api34) | 🔵 Code Present |
| 7 | محادثة متدفقة SSE | ✅ | كثيفة (ChatViewModel ×4، ApiClient) | ✅ | ⛔ | لا | 🔵 Code Present |
| 8 | موافقات الأدوات + إشعاراتها | ✅ | ✅ | ✅ | ⛔ | لا | 🔵 Code Present |
| 9 | أسئلة تفاعلية (Questions) | ✅ | ✅ | ✅ | ⛔ | لا | 🔵 Code Present |
| 10 | جلسات/أرشفة/استئناف | ✅ | ✅ | ✅ | ⛔ | لا | 🔵 Code Present |
| 11 | Rان‑تايم محلي PRoot/Alpine | ✅ | ✅ (Installer/Updater/Manifest) | ✅ | ⛔ | نعم | 🔵 Code Present |
| 12 | تنزيلات الران‑تايم بتحقق SHA-256 | ✅ | ✅ | ✅ | ⛔ | لا (شبكة فقط) | 🔵 Code Present |
| 13 | OpenCode محلي (HTTP 4097) | ✅ | ✅ | ✅ | ⛔ | لا | 🔵 Code Present |
| 14 | OpenCode بعيد + QR + Pin | ✅ | ✅ (Url/QR/Client) | ✅ | ⛔ | شبكة LAN | 🔵 Code Present |
| 15 | Claude Code + جسر الموافقات | ✅ | ✅ (Parser/Bridge) | ✅ | ⛔ | نعم | 🔵 Code Present |
| 16 | خطاف صلاحيات Claude (الملف) | ✅ | — | يُشحن في assets | ✅ **نُفِّذ فعليًا** | لا | 🟢 Verified Working (ما عدا always-allow ببادئة) |
| 17 | Antigravity (Debian+PTY) | ✅ | ✅ (20+ ملفًا) | ✅ | ⛔ | نعم | 🔵 Code Present |
| 18 | Codex (app-server) | ✅ | ✅ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 19 | MCP الجهاز — الأدوات | ✅ | ✅ (Firewall/Codec) | يُشحن في assets | ✅ **88/88 استُدعيت** | لا | 🟢 Verified Working (طبقة العميل) |
| 20 | MCP الجهاز — التنفيذ داخل التطبيق | ✅ | ✅ | ✅ | ⛔ | نعم (Accessibility) | 🔵 Code Present |
| 21 | جدار حماية الأذونات (89 إجراءً) | ✅ | ✅ جزئيًا | ✅ | 🔁 أُعيد اشتقاقه | لا | 🟡 Partially Verified (خلل تصنيف، §23) |
| 22 | وضع «قراءة فقط» (39 إجراءً) | ✅ | ✅ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 23 | سجل التدقيق + تصديره | ✅ | ✅ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 24 | الإيقاف الطارئ | ✅ | ✅ (StopPhrases) | ✅ | ⛔ | نعم | 🔵 Code Present |
| 25 | MCP الجدولة (8 أدوات) | ✅ | ✅ | يُشحن | ✅ **نُفِّذ بالكامل** | لا | 🟢 Verified Working (طبقة العميل) |
| 26 | جدولة AlarmManager/FGS فعليًا | ✅ | ✅ | ✅ | ⛔ | نعم | ❓ Cannot Determine |
| 27 | MCP المتصفح (7 أدوات) | ✅ | — | يُشحن | 🟡 (1/7 نُفِّذ) | نعم (WebView) | 🟡 Partially Verified |
| 28 | المتصفح الضيف (WebView+CDP) | ✅ | — | ✅ | ⛔ | نعم | 🔵 Code Present |
| 29 | USB/ADB (shell/list/pull/push/install/logcat/screenshot) | ✅ | ✅ جزئيًا | ✅ | ⛔ | نعم (OTG+جهاز) | ❓ Cannot Determine |
| 30 | ADB لاسلكي | ✅ | ✅ | ✅ | ⛔ | نعم | ❓ Cannot Determine |
| 31 | منافذ تسلسلية (USB Serial) | ✅ | ❌ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 32 | Fastboot (قراءة فقط) | ✅ | ✅ جزئيًا | ✅ | ⛔ | نعم | 🔵 Code Present |
| 33 | MTP (list/download) | ✅ | ❌ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 34 | HID / كاميرات / تخزين | ✅ | ❌ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 35 | Mirror (screenrecord) | ✅ | ✅ جزئيًا | ✅ | ⛔ | نعم (جهازان) | ❓ Cannot Determine |
| 36 | scrcpy تحكم كامل | ✅ | ✅ جزئيًا | ✅ | ⛔ | نعم (جهازان) | ❓ Cannot Determine |
| 37 | وكيل المكالمات | ✅ | ✅ | ✅ | ⛔ (محظور بالتوجيه) | نعم | ❓ Cannot Determine |
| 38 | الإملاء الصوتي + TTS (3 مزوّدين) | ✅ | ✅ | ✅ | ⛔ | ميكروفون/مفاتيح | ❓ Cannot Determine |
| 39 | كلمة التنبيه Vosk | ✅ | ✅ (14 ملفًا) | ✅ | ⛔ | ميكروفون + تنزيل 40-48MB | 🔵 Code Present |
| 40 | Voice Interaction Service | ✅ | ❌ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 41 | SSH/SFTP/SCP | ✅ | ❌ | ✅ | ⛔ | شبكة + خادم | ❓ Cannot Determine |
| 42 | SMB/FTP/WebDAV/mDNS | ✅ | ❌ | ✅ | ⛔ | شبكة + خادم | ❓ Cannot Determine |
| 43 | أدوات الشبكة (wifi/dns/ping/port/http/ws) | ✅ | ❌ | ✅ | ⛔ | شبكة | ❓ Cannot Determine |
| 44 | Bluetooth/BLE | ✅ | ❌ | ✅ | ⛔ | عتاد BT | ❓ Cannot Determine |
| 45 | Termux bridge (سياسة رفض) | ✅ | ✅ (Policy) | ✅ | ⛔ | تطبيق Termux | 🔵 Code Present |
| 46 | تحليل Payload + حاجز الروم | ✅ | ✅ | ✅ | ⛔ | ملف OTA | 🔵 Code Present |
| 47 | GitHub OAuth + PR status | ✅ | ✅ | ✅ | ⛔ | شبكة + حساب | 🔵 Code Present |
| 48 | وكيل الجهاز: أدوات الملفات | ✅ | ✅ | ✅ | 🟡 (استُدعيت عبر الجسر فقط) | نعم | 🔵 Code Present |
| 49 | الويدجت + هدف المشاركة + الاختصارات | ✅ | ❌ | ✅ | ⛔ | نعم | 🔵 Code Present |
| 50 | 8 لغات + فحص i18n في CI | ✅ | ✅ | ✅ (i18n-check) | ✅ جزئيًا (عدّ/مطابقة) | لا | 🟢 Verified Working (فحص المطابقة) |
| 51 | المستندات القانونية داخل التطبيق | ✅ | ✅ | ✅ | ⛔ | لا | 🔵 Code Present |
| 52 | التحليلات/سجل التعطّل | ✅ | ✅ | ✅ (نسختا github/fdroid) | ⛔ | لا | 🔵 Code Present |
| 53 | تبويب طرفية داخل مستكشف العمل | ✅ (واجهة فقط) | ❌ | ✅ | ⛔ | لا | 🟠 Prototype / Placeholder |
| 54 | الطرفية الحقيقية (شاشة منفصلة) | ✅ | جزئي | ✅ | ⛔ | نعم | 🔵 Code Present |
| 55 | طبقة Room لكاش الجلسات | ✅ | ❌ | ✅ | — | لا | ⚪ Dead / Unused |
| 56 | عملاء Forge (GitLab/Gitea) | ✅ | ❌ | ✅ | — | لا | ⚪ Dead / Unused |
| 57 | كاشف النشاط الصوتي / KeepAwakeHelper / تخطيط التابلت / مساعد السحب | ✅ | ❌ | ✅ | — | لا | ⚪ Dead / Unused |
| 58 | تبعية `eddsa` | ✅ (مُعلنة) | ❌ | ✅ (تُشحن) | — | لا | ⚪ Dead / Unused |

---

## 8. Agent Verification

المطلوب كان اختبار كل وكيل على حدة (يبدأ؟ يسجّل الدخول؟ يستقبل Prompt؟ يعيد Response؟ الأدوات؟ استمرار الجلسة؟ الإيقاف؟). **لم أتمكن من اختبار هذه الجزئية عمليًا، لذلك تم تقييمها من خلال الكود فقط** — لا جهاز، ولا PRoot على أندرويد، ولا مقدم خدمة نموذج.

| السؤال | OpenCode | Claude Code | Antigravity | Codex |
|---|---|---|---|---|
| هل يبدأ؟ | 🔵 موجود في الكود (خادم HTTP على 127.0.0.1:4097) | 🔵 (عملية داخل PRoot عبر stdin/stdout) | 🔵 (Debian + PTY + `agy`) | 🔵 (`app-server` JSON-RPC) |
| هل يسجّل الدخول؟ | 🔵 OAuth/API key عبر واجهة المزوّدين | 🔵 `claude auth login` + التقاط الرابط والكود | 🔵 OAuth (URL+كود) | 🔵 ChatGPT/مفتاح API |
| هل يستقبل Prompt؟ | 🔵 `POST session/{id}/prompt_async` | 🔵 كتابة JSON على stdin | 🔵 كتابة داخل PTY | 🔵 JSON-RPC |
| هل يعيد Response؟ | 🔵 SSE + استقصاء احتياطي 3 ث (مهلة 120 ث) | 🔵 أسطر stream-json | 🔵 stream-json | 🔵 JSON-RPC notifications |
| هل الأدوات تعمل؟ | 🔵 (أدوات OpenCode) | 🔵 عبر هوك الصلاحيات (المتحقَّق منه هنا) | 🔵 `mcp_config.json` | 🔵 MCP عبر `codex mcp` |
| هل الجلسة تستمر؟ | 🔵 تُقرأ من الران‑تايم | 🔵 سجل جلسات محلي | 🔵 سجل جلسات محلي | 🔵 خيوط app-server |
| هل إيقافه يعمل؟ | 🔵 `abort` | 🔵 إنهاء العملية | 🔵 إشارة PTY | 🔵 إنهاء app-server |
| تحقق فعلي | ⛔ | ⛔ (عدا الخطاف) | ⛔ | ⛔ |
| **الحكم** | 🔵 Code Present | 🔵 Code Present | 🔵 Code Present | 🔵 Code Present |

**ما تم التحقق منه فعليًا في منظومة الوكلاء:** خطاف صلاحيات Claude (سكربت الضيف) — 7 سيناريوهات نُفِّذت (انظر §6 و§23)، وشحن أدوات MCP التي يستدعيها كل وكيل (تم التحقق من محتواها وعدّها).

---

## 9. Device Agent Verification

### 9.1 ما نُفِّذ فعليًا

| الاختبار | النتيجة |
|---|---|
| تشغيل خادم MCP عبر stdio | ✅ تهيئة MCP صحيحة، `tools/list` = 88 أداة فريدة |
| استدعاء كل الأدوات الـ88 بمدخلات مولّدة من `inputSchema` | ✅ 0 مهلة، 0 خطأ RPC، 86 عبرت الجسر |
| الأدوات المحلية بلا جسر | ✅ `device_get_context` (قراءة ملف) و`device_press` (تحقق وسائط) |
| تخطيط الأزرار | ✅ back→`press_back`، home→`press_home`، recents→`open_recents`، قيمة خاطئة→رفض محلي |
| رفض التطبيق | ✅ رسالة الرفض تصل حرفيًا — `refused: read_screen is not allowed in read-only mode` |
| غياب التطبيق | ✅ مهلة 15 ث برسالة تشخيصية، **ولا تسرّب لنتيجة قديمة بمعرّف آخر** |
| JSON تالف من التطبيق | ✅ لا انهيار، تجاهل ثم مهلة نظيفة |
| تكافؤ الأدوات/المعالجات | ✅ 88 = 88 (لا أداة بلا معالج ولا العكس) |

### 9.2 إعادة اشتقاق أرقام الجدار من الشفرة (لا من التقرير السابق)

| الرقم | القيمة المُعاد اشتقاقها | مطابقة تقرير المرحلة الأولى |
|---|---|---|
| `ALL_ACTIONS` | **89** | ✅ مطابق |
| `AUTO_ACTIONS` (القائمة الصريحة) | **57** | ✅ مطابق |
| التصنيف الصريح CONFIRM في `levelFor` | **19** | ❌ التقرير قال 32 |
| إجراءات **غير مصنَّفة** تسقط إلى AUTO عبر `else` | **13** | ❌ لم تُذكر |
| `READ_ONLY_ACTIONS` | **39** | ✅ مطابق |
| الأدوات ↔ المعالجات | 88 ⇄ 88 | ✅ مطابق |
| إجراءات MCP (85 حرفيًا + 4 ديناميكية) ⊆ ALL | 89/89 | ✅ مطابق |

**التصنيف الفعلي إذن:** 89 إجراءً = **70 يٌنفَّذ بلا سؤال** + **19 يسأل**. الـ13 غير المصنَّفة هي:
`usb_shell, usb_pull, usb_push, usb_install, usb_screenshot, usb_logcat, usb_transfer_media, usb_serial_send, usb_tcpip_enable, tcp_shell, ssh_exec, ssh_download, ssh_upload` — وهي إجراءات تغيّر حالة جهاز آخر/تبدأ جلسات، وتوثيق `UsbExecutor` نفسه يصف بعضها بأنها `(CONFIRM)`. سببها فرع `else -> ConfirmationLevel.AUTO` في `DeviceActionFirewall.levelFor` (انتشار §23).

### 9.3 حماية إضافية مُتحقَّق منها في الكود (ساكنًا)

- رفض الإجراءات غير المعروفة قبل الجدار: `if (command.action !in DeviceActionFirewall.ALL_ACTIONS)`.
- وضع القراءة فقط يمنع كل ما ليس في قائمة الـ39 (عدا `stop_agent`).
- الإيقاف الطارئ يُفحص قبل كل خطوة (`store.consumeStopRequest()`).
- سجل النشاط يقيد الحجم، وتصدير التدقيق يذكر الإجراء/النتيجة/الملخص/الوقت دون معاملات.
- تصعيد النقر الحسّاس (`Pay now`, «احذف الملف», Send message) إلى CONFIRM — مع اختبارات وحدات.

### 9.4 ما لم يُختبر إطلاقًا (لا جهاز/لا Accessibility)

فتح تطبيق فعلي · قراءة شاشة · بحث عناصر · نقر/كتابة/تمرير · العودة/Home/Recents · حذف/نقل/نسخ ملفات · تأكيد إجراءات CONFIRM عبر إشعار · Audit log فعلي.

---

## 10. USB Verification

**لا يوجد عتاد USB في البيئة**، لذلك: لم يُوصَل أي جهاز، ولم يُنفَّذ أي إجراء USB حقيقي، **ولم تُجرَّب أي عملية تفليش/مسح/محو** (وهي مرفوضة أصلًا في السياسة).

| البند | الحالة |
|---|---|
| كشف USB | ⛔ لا عتاد · أدوات MCP مستدعاة على الجسر (params صحيحة: `usb_devices`) |
| ADB shell/list/pull/push/install/logcat/screenshot | ⛔ · 9 أدوات MCP تحققت على مستوى العقد (بما فيها المهلات الخاصة: `usb_pull` 600 ث، `usb_shell` 180 ث) |
| ADB لاسلكي | ⛔ · `usb_tcpip_enable` + إعادة اتصال تلقائية موجودة بدليل كودي |
| Serial | ⛔ · `usb_serial_send` (baudrate 115200 افتراضيًا) و`usb_serial_read` (1000ms) تحقّقا |
| Fastboot getvar | ⛔ · `fastboot_getvar`/`fastboot_getvar_full (reveal_token=false افتراضًا)` تحقّقا |
| MTP list/download | ⛔ · الأداة تحققت على العقد؛ **لا أداة رفع (upload) موجودة** |
| HID / Hub / تخزين / كاميرا | ⛔ · `hid_read`, `usb_hub_list`, `storage_volumes`, `camera_list` تحققت |
| **السياسة الوقائية** | ✅ ساكنًا: `TermuxCommandPolicy` يرفض `flash/erase/format/stage/lock/unlock/update/set_active` وكل `oem ...` (مع سبب مكتوب لكل رفض) |

---

## 11. Network Verification

| البند | الحالة |
|---|---|
| ما نُفِّذ فعليًا | 🔁 لم يُختبر أي اتصال شبكي من التطبيق؛ لكن **سياسة الشبكة للبيئة نفسها قِيست** (مصفوفة §3.2) وهي دليل عملي على آلية الحجب لا على وظائف التطبيق |
| عقد أدوات الشبكة في MCP | ✅ `wifi_info, dns_lookup, net_ping, port_check, http_request, websocket, net_browse, remote_list, remote_download, ssh_*` — كلها استُدعيت وتحقق مرورها بالمعاملات الصحيحة |
| LAN discovery / mDNS | ⛔ (لا جهاز) · 🔵 الكود يستخدم `NsdManager` + `net_browse` بـ7 أنواع خدمات |
| Remote OpenCode (HTTPS/HTTP/QR/Pin) | ⛔ · 🔵 التحقق البرمجي لقواعد المنع: `OpenCodeUrl.isTrustedCleartextHost` يقبل فقط loopback/10.x/127.x/169.254/172.16-31/192.168/100.64-127/`.local`/IPv6 ULA — مع تعليق صريح أن NSC يسمح cleartext لأن Android لا يستطيع التعبير عن نطاقات IP |
| Certificate pinning | 🔵 `CertificatePinner` عند تزويد `pinSha256` (لا اختبار فعلي) |
| SSH/SFTP/SCP · SMB/FTP/WebDAV | ⛔ (لا خوادم/لا جهاز) · 🔵 كود كامل + **صفر اختبارات وحدة** لهذه الطبقة |
| Bluetooth/BLE | ⛔ · 🔵 (`neverForLocation` موجودة) |

---

## 12. Voice Verification

| البند | الحالة |
|---|---|
| Speech recognition | ⛔ لا ميكروفون/جهاز · 🔵 `SpeechRecognizerManager` + خدمة تعرّف |
| Android TTS | ⛔ · 🔵 موجود |
| OpenAI TTS | ⛔ · 🔵 يحتاج مفتاح API (يُخزَّن مشفّرًا) — لم يُختبر ولم يُكشف أي مفتاح |
| ElevenLabs | ⛔ · 🔵 يحتاج مفتاح API |
| Vosk (نموذج + كشف) | ⛔ · 🔵 تنزيل 40MB/48MB عند الطلب. **ملاحظة أمنية مؤكدة:** لا تحقق SHA-256/توقيع للنموذج المنزّل (بخلاف بقية أصول الران‑تايم)، وسكربت `sign_wakeword_pack.py` يتيم (يشير إلى صنف غير موجود) |
| Wake word / Barge-in | ⛔ · 🔵 `WakeWordService` (FGS microphone) + `BargeInPolicy` |
| Voice Interaction Service | ⛔ · 🔵 خدمة مُصدَّرة بصلاحية `BIND_VOICE_INTERACTION` (تحقق من البيان) |

**متطلبات خارجية لتفعيل الصوت (بلا كشف مفاتيح):** مفاتيح OpenAI/ElevenLabs اختيارية، تنزيل نموذج Vosk، منح RECORD_AUDIO، واختيار التطبيق مساعدًا افتراضيًا.

---

## 13. Call Agent Verification

لم يُجرَّب أي اتصال — لا جهاز، ولا أرقام، ولا اتصال فعلي (التزامًا بالتوجيه: بيئة Mock فقط).

| البند | الحالة |
|---|---|
| آلة حالة المكالمة | 🔵 `CallStateMachine` + اختبارات `CallAgentCoreTest` |
| مكالمة واردة | 🔵 `IncomingCallReceiver` (مُصدَّر، `PHONE_STATE`)، سياسة `ALLOW_ASSISTANT/TAKE_MESSAGE/DO_NOT_ANSWER` |
| مكالمة صادرة | 🔵 `PhoneCallController` عبر Telecom/AudioManager فقط |
| محرّك المحادثة | 🔵 `ConversationEngine` نقي قابل للاختبار + `AgentCallBrain` (يُعطَّل بعد دورين بلا رد) |
| معالجة الأذونات | 🔵 كل شيء capability‑gated (لا التفاف على الصلاحيات) |
| STOP / TAKE OVER / END | 🔵 إشعار FGS بأزرار + مستقبلات غير مُصدَّرة |
| قيود OTP/كلمات المرور | 🔵 `CallPolicy` يمنع نطق أكواد/passwords ويصعّد الطلب (اختبارات موجودة) |
| **عدم تسجيل المكالمة** | ✅ مؤكَّد بالفحص: لا يوجد أي كود تسجيل مكالمات في المشروع (بحث شامل عن مسجّلات الصوت المرتبطة بالمكالمة) |
| الأدوات الخمس على MCP | ✅ استُدعيت عبر الجسر: `find_contact, call_agent, call_state, call_stop, call_log, call_summaries` |
| **الحكم** | ❓ Cannot Determine (لا يمكن اختبار السلوك الفعلي) |

---

## 14. Scheduling Verification

| البند | الحالة |
|---|---|
| إنشاء جدولة | ✅ **تم التحقق عمليًا على طبقة العميل**: `schedule_create` أرسل `op=create` مع `{prompt, cron, runtimeId}` |
| Cron parsing | 🔵 منطق التحليل في Kotlin (`ScheduleModels`) + اختبارات وحدات؛ **لم يُنفَّذ فعليًا** لأن التطبيق لم يعمل |
| الجدولة مرة واحدة | 🔵 `oneTimeAt` موجود في مخطط الأداة والاختبارات |
| Enable/disable | ✅ عقد العميل: `op=setEnabled` مع `{scheduleId, enabled}` |
| Run now | ✅ عقد العميل: `op=runNow` |
| AlarmManager | ⛔ **غير قابل للاختبار هنا صراحة** — يحتاج جهازًا؛ لا يمكن ولو محاكاة المنبّه لأن التطبيق لا يُبنى |
| Foreground service | ⛔ لا تشغيل · 🔵 `ScheduleExecutionService` بنوع specialUse (من البيان) |
| Retry / Watchdog | 🔵 `ScheduleRetryPolicy` + `ScheduleRunWatchdog` + اختبارات |
| Boot rescheduling / Timezone | 🔵 مستقبلات `BOOT_COMPLETED/MY_PACKAGE_REPLACED/TIMEZONE_CHANGED` في البيان؛ سلوك غير مُختبر |
| المهلة | ✅ قيست: 90.1 ث بلا رد (مطابقة للثابت 90) |
| **الحكم** | 🟡 Partially Verified (طبقة العميل 🟢، التطبيق ⛔) |

---

## 15. Mirror/scrcpy Verification

| البند | الحالة |
|---|---|
| كشف الجهاز | ⛔ لا جهاز ثانٍ |
| ADB للمرآة | ⛔ |
| Mirror (عرض فقط) | ⛔ · 🔵 `screenrecord --output-format=h264` مع تدوير كل ~3 دقائق |
| Video stream / فك الترميز | ⛔ · 🔵 `MirrorDecoder` (لا قواعد R8 مخصّصة للمفكّك — لا يلزم) |
| Touch/Keyboard/Navigation (scrcpy) | ⛔ · 🔵 `ScrcpyControlMessages` + اختبارات؛ الحقن عبر قنوات ADB |
| Session termination / إعادة الاتصال | ⛔ · 🔵 منطق موجود |
| مصدر `scrcpy-server-4.0` | ✅ **داخل المستودع** (`assets/scrcpy/scrcpy-server-4.0`، 732,226 بايت) ويُدفع إلى `/data/local/tmp` — **بلا تحقق تجزئة** عند الدفع |
| **الحكم** | ❓ Cannot Determine (لا يمكن قياس الأداء/التوافق هنا) |

---

## 16. OTA/Payload Verification

نُفِّذت **فحوص قراءة فقط**؛ لم تُنفَّذ أي عملية تفليش/محو (ولا توجد قدرة عليها في المشروع أصلًا).

| البند | الحالة |
|---|---|
| قراءة archive | 🔵 `PayloadArchive` (ZIP ⇒ payload.bin، ترويسة magic، قراءة Manifest) |
| اكتشاف payload.bin | 🔵 مدعوم عبر ZIP |
| التحقق من السلامة | 🟡 فحص ترويسات العينات (`verifyLocalHeaderSample`) + فك XZ — **لا تحقق توقيع AVB**، والكود يصرّح بذلك: «signature check here, and the result says so» |
| قراءة metadata | 🔵 `payload_info` (الأقسام/العمليات/الأحجام) |
| device mismatch | 🔵 `PayloadGuard` يقارن اسم الجهاز المُعلن (pre-device/updater-script/اسم مجلد fastboot) ويضع `blocking` |
| safety preflight | ✅ عقد الأداة استُدعي (`safety_preflight`)، والمنطق 🔵 يفصل التذكيرات عن الفحوص |
| MCP contract | ✅ `payload_info`, `payload_extract`, `payload_guard` عبرت الجسر بمعاملاتها الصحيحة |
| **الحكم** | 🔵 Code Present (لا ملف OTA حقيقي في البيئة) |

---

## 17. Security Verification

### 17.1 نتائج البنية الظاهرة (Static → مؤكدة بالتحليل البرمجي)

| البند | القيمة |
|---|---|
| الصلاحيات المعلنة | 30 صلاحية |
| المكوّنات | 8 Activities · 9 Services · 8 Receivers · 2 Providers |
| المكوّنات المُصدَّرة أو الضمنية | 9 (انظر الجدول) |
| خدمات أمامية | 5: `LocalRuntimeService` (specialUse)، `CodexKeepAliveService` (specialUse)، `ScheduleExecutionService` (specialUse)، `WakeWordService` (microphone)، `CallAgentService` (microphone\|phoneCall) |
| Deep links | `content://` فقط (عبر FileProvider) — لا روابط خارجية |
| النسخ الاحتياطي | `allowBackup=false` + `dataExtractionRules` |
| NSC | cleartext مسموح في `base-config` مع تعليق مُبرِّر + بوابة تطبيقية `OpenCodeUrl.isTrustedCleartextHost`؛ شهادات المستخدم مضافة في `debug-overrides` (بناء Debug فقط) |

**المكوّنات المصدَّرة:** `MainActivity` (LAUNCHER)، `DeviceAgentActivity` (LAUNCHER ثانٍ)، `StopAgentActivity` (بدون مرشّح)، `ShareReceiverActivity` (SEND/VIEW)، `QuickInputWidgetProvider` (APPWIDGET_UPDATE)، `IncomingCallReceiver` (PHONE_STATE)، إضافة إلى 3 خدمات مربوطة محمية بصلاحيات `BIND_VOICE_INTERACTION` و`BIND_RECOGNITION_SERVICE` و`BIND_ACCESSIBILITY_SERVICE` (هذه الأخيرة هي الأهم أمنيًا وهي محمية بشكل صحيح).

### 17.2 نتائج التحقق الأمني العملي

| # | النتيجة | الدليل | التقييم |
|---|---|---|---|
| 1 | **فرع `else` في جدار الحماية متساهل** → 13 إجراءً حسّاسًا (USB/SSH) تُنفَّذ بلا تأكيد | إعادة اشتقاق `DeviceActionFirewall.levelFor` + غياب أي بوابة ثانية في `DeviceAgentBridge` (سطر 211) والمنفّذات | خطر فعلي |
| 2 | **«السماح الدائم» ببادئة لا يعمل** (خطأ jq) + `isAlwaysAllowed` في Kotlin بلا أي مستدعٍ | تنفيذ الخطاف فعليًا + إثبات jq (exit=5: `Cannot index string with "commandPrefix"`) + grep | وظيفة معطوبة (تفشل بالرفض الآمن: تسأل كل مرة) |
| 3 | لا أسرار مكتوبة في المصدر | فحص أنماط (`client_secret`, `api_key=`, `BEGIN PRIVATE KEY`…) = صفر | جيد |
| 4 | `app/google-services.json` مُلتزَم في Git | وجود الملف (معرّفات مشروع فقط، بلا `oauth_client` ولا مفاتيح خادم) | يحتاج تحسينًا |
| 5 | نموذج Vosk بلا تحقق تجزئة/توقيع | `VoskModelInstaller` (لا SHA-256) مقابل بقية الأصول المثبّتة | يحتاج تحسينًا |
| 6 | `scrcpy-server-4.0` داخل المستودع بلا تحقق عند الدفع | `UsbExecutor.kt:352` | منخفض (مصدره المستودع) |
| 7 | NSC يسمح cleartext على مستوى `base-config` | الملف نفسه يشرح السبب؛ البوابة التطبيقية سليمة (تُختبر في وحدات) | يحتاج تحسينًا |
| 8 | `StopAgentActivity` مُصدَّر بلا صلاحية | تحليل البيان | منخفض (شاشة إيقاف؛ لا تنفّذ إجراءً خطِرًا بنفسها) |
| 9 | `IncomingCallReceiver` مُصدَّر لـ`PHONE_STATE` | تحليل البيان | متوسط (بثّ صوتي مزيف نظريًا؛ التنفيذ الفعلي capability‑gated) |
| 10 | تصدير التدقيق لا يحوي المعاملات/المحتوى | كود + اختبار | جيد |
| 11 | `TermuxCommandPolicy` رفض‑افتراضي (flash/erase/oem مرفوضة بسبب مكتوب) | كود | جيد |
| 12 | لا كود لتسجيل المكالمات إطلاقًا | بحث شامل | جيد |
| 13 | سياسة OTP في المكالمات | `CallPolicy` + اختبارات | جيد |
| 14 | `isAlwaysAllowed` غير مستخدم (انظر #2) | grep | يزيد سطح الالتباس |
| 15 | تبعية `eddsa` غير مستخدمة إطلاقًا | فحص استيراد شامل = 0 نتيجة | تنظيف |

> لم تُستغل أي ثغرة؛ كل الفحوص دفاعية (قراءة كود/تنفيذ سكربتات المشروع نفسها).

---

## 18. Performance Findings

**قاعدة هذا القسم:** لا ادّعاء لأي «مشكلة أداء مؤكدة» بلا قياس أو دليل قوي. لا يوجد في هذه البيئة أي جهاز/محاكي، لذلك **لم يُقَس أداء التطبيق إطلاقًا** (استهلاك ذاكرة، زمن إطار، بطارية، زمن إقلاع). ما يلي إمّا قياسات فعلية لمكوّنات نُفِّذت، أو ملاحظات سببية مستندة إلى الكود تُصنَّف بوضوح.

### 18.1 قياسات فعلية (من هذه المرحلة)

| القياس | القيمة | المصدر |
|---|---|---|
| زمن استجابة أداة MCP واحدة عبر الجسر | ~0.3 ث (وهو = `POLL_INTERVAL` في خادم الجهاز وخادم الجدولة) | 88 استدعاءً = 26 ث إجمالًا |
| مهلة أداة `device_set_task` | 15.0 ث (مطابقة للثابت) | تنفيذ فعلي |
| مهلة أدوات CONFIRM | 150 ث (ثابت، لم تُختبر عمليًا) | كود |
| مهلة أدوات USB الطويلة | 600 ث (`usb_pull`) / 180 ث (`usb_shell`) | كود |
| مهلة جسر الجدولة | 90.1 ث (مقيسة) | تنفيذ فعلي |
| سماح always-rule بلا بادئة | 0.26 ث | تنفيذ فعلي |
| زمن سكربت الالتزام | <0.1 ث | تنفيذ فعلي |

### 18.2 ملاحظات مستندة إلى الكود (تُقرأ كاحتمالات لا كأحكام)

| العنصر | الدليل | التصنيف |
|---|---|---|
| حلقات استقصاء متكررة | جسر الجهاز 0.3 ث، مراقبة الجودة 30 ث، فحص التعطّل 30 ث، ADB 30 ث، Watchdog 5/60 ث | 🟡 احتمال كلفة بطارية؛ معلوم أن مراقبة الجودة تتوقف خارج المقدمة |
| `ChatUiState` يحمل `Bitmap` للصور | كود `ChatViewModel` | 🟡 احتمال ضغط ذاكرة في محادثات طويلة — **لم يُقَس** |
| أعداد كبيرة في ملفات مفردة | `ChatViewModel` 2767 سطرًا، `ChatHomeScreen` 2235 | 🟡 كلفة صيانة لا كلفة أداء مُقاسة |
| `RuntimeWorkTracker` يعتمد Leases | كود | 🟢 تصميم يمنع WakeLock دائم |
| قصّ مخرجات الأدوات | `MAX_TOOL_OUTPUT_CHARS = 4000` (كود) | 🟢 |
| لا WorkManager في المشروع | فحص شامل = 0 | 🟢 (الفروض الخلفية كلها FGS/AlarmManager) |

---

## 19. Code vs Reality

| الفئة | العناصر |
|---|---|
| **تبدو مكتملة في الكود لكنها فشلت عمليًا** | 1) «السماح الدائم» ببادئة الأمر: خطاف Claude لا يطابق أبدًا (تحقق تنفيذي + إثبات jq) — ونتيجته الآمنة أنها تسأل كل مرة. 2) تصنيف جدار الحماية: 13 إجراءً حسّاسًا يُفترض أن يظهر فيها سؤال تأكيد (كما توحي توثيقات `UsbExecutor`) لكنها تُنفَّذ بلا سؤال بسبب `else → AUTO`. 3) اختبارات الأجهزة الـ23 «موجودة» لكنها لا تُنفَّذ في أي مكان. |
| **تعمل (مُتحقَّق منها)** | خوادم MCP الثلاثة على مستوى العقد (88/8/7 أدوات)، خطاف صلاحيات Claude (allow/deny/timeout/question/no-jq/always-rule بلا بادئة)، خطاف رسائل الالتزام، فحوص الترجمة، بناء CI كامل + حزمة اختبارات الوحدات. |
| **تعمل جزئيًا** | خادم المتصفح (أداة ملف واحدة تعمل؛ 6 أدوات CDP تحتاج WebView حيًّا)، «السماح الدائم» (يعمل بلا بادئة فقط)، جسر الجهاز (طبقة العميل فقط، أما التنفيذ فغير مُختبر). |
| **تحتاج Hardware** | كل مسارات الجهاز: Accessibility، USB/ADB، Serial، Fastboot، MTP، HID، Hub، الكاميرات، Mirror/scrcpy، المكالمات، الصوت/Vosk، Bluetooth، المنبّهات. |
| **تحتاج External Service** | OpenCode البعيد (خادم على PC)، SSH/SMB/FTP/WebDAV/mDNS، DNS/ping/port/http/websocket، حدود OAuth (GitHub)، مزوّدو النماذج، TTS السحابي، مصادر تنزيل الران‑تايم (Alpine/OpenCode/Debian/agy/Codex)، وموقع نماذج Vosk. |
| **تحتاج Account/API Key** | GitHub OAuth (Client ID مُمرَّر وقت البناء)، مزوّدو النماذج (OAuth أو مفتاح)، OpenAI TTS، ElevenLabs، حساب Claude، تسجيل Antigravity، تسجيل Codex. |
| **لا يمكن اختبارها في هذه البيئة** | كل ما سبق + البناء المحلي + lint/detekt محليًا + Benchmarks + أي قياس أداء. |
| **توثيق يخالف الكود** | `docs/device-matrix.md` يدّعي تشغيل `connectedDebugAndroidTest` على CI (غير صحيح)، `docs/RELEASE.md` يستخدم `ANDROID_CODE_*` بينما الكود يقرأ `AND_CODE_*` ويذكر `CHANGELOG.md` غير الموجود، وتقرير المرحلة الأولى نفسه يحتوي رقماً خاطئًا (57+32) ووصفًا خاطئًا لـ`eddsa` كمستخدمة. |

---

## 20. Previously Missed Features

عناصر لم يذكرها تقرير المرحلة الأولى أو وصفها وصفًا غير دقيق، ظهرت في إعادة الفحص المستقلة:

1. **الفرع المتساهل في جدار الحماية** (`else -> AUTO`) وما ينتج عنه من 13 إجراءً غير مصنَّف ينفَّذ بلا تأكيد.
2. **خطأ jq في `always-rules`** داخل خطاف Claude (بادئة الأمر لا تُطابق أبدًا)، وأن العداد المقابل في Kotlin (`isAlwaysAllowed`) **مُعرَّف ولا يُستدعى**.
3. **تبعية `net.i2p.crypto:eddsa:0.3.0` غير مستخدمة** إطلاقًا (لا استيراد واحد) — خلافًا لوصف المرحلة الأولى بأنها للتحقق من AVB.
4. **`ActivityViewModel` مرتبط فعلًا** بالمشروع (`ui/MushreaCodeApp.kt` + `di/ViewModelModule.kt`) — لا شاشة مستقلة له، لكنه ليس كودًا ميتًا.
5. **عدد الشاشات المضبوط:** 23 ملف `*Screen.kt` (وليس 30 شاشة كما قد يوحي جدول المرحلة الأولى الذي كان يمزج الشاشات بالأنشطة).
6. **لا وجود لـWorkManager/Workers** في المشروع (0 نتائج) — كل الخلفية AlarmManager/FGS/مستقبلات.
7. **لا نظام Feature Flags**: `BuildConfig` لا يحوي سوى `VERSION_NAME` و`DEBUG` و`GITHUB_CLIENT_ID` و`BUILD_TYPE`.
8. **Providers = 2 فقط**: `androidx.startup.InitializationProvider` و`androidx.core.content.FileProvider` (الأول تهيئة، الثاني مشاركة ملفات).
9. **5 خدمات أمامية بأنواعها الدقيقة** مؤكَّدة من البيان (specialUse×3، microphone، microphone|phoneCall).
10. **أسماء ملفات androidTest التسعة** صارت مؤكَّدة (كانت في المرحلة الأولى أعدادًا فقط)، وعدد اختبارات الوحدات بقي 1323 دون تغيير.
11. **حزمة PyPI باسم `sdkmanager`** ليست Android SDK manager بل أداة عامة لإدارة إصدارات SDKs عبر git — مسار بديل مغلق.
12. **قياس فعلي لسياسة شبكة البيئة** (فلترة SNI مع DNS يعمل) — مفيد لتفسير أي محاولة بناء مستقبلية.

---

## 21. Dead Code

لا شيء حُذف. القائمة المنسَّقة (مع إضافات هذه المرحلة):

| العنصر | الموقع | الدليل |
|---|---|---|
| `ForgeClient` + GitHub/GitLab/Gitea | `core/api/ForgeClient.kt` | لا استدعاء خارج الملف |
| طبقة Room كاملة | `data/local/{SessionCacheRepository, SessionDatabase, dao, entities}` | لا `Room.databaseBuilder` في المشروع |
| `TabletSettingsLayout` | `feature/settings/` | بلا مستدعٍ |
| `DragDropAttachHelper` | `feature/workspace/` | بلا مستدعٍ |
| `VoiceActivityDetector` | `feature/wakeword/` | بلا مستدعٍ |
| `KeepAwakeHelper` | `feature/assistant/` | بلا مستدعٍ (WakeLock يُدار مركزيًا) |
| `AssistantProfile(+Resolver)` | `feature/assistant/` | يُستخدم في اختبار فقط |
| `OpenCodeConfig`, `OpenCodeCacheTokens` ونماذج شقيقة | `core/api/*Models.kt` | بلا استهلاك |
| `scripts/sign_wakeword_pack.py` | `scripts/` | يستهدف `WakeWordPackManager` غير الموجود |
| `TerminalTabPlaceholder` | `feature/workspace/WorkspaceExplorerScreen.kt` | واجهة وهمية داخل تبويب |
| **`ClaudePermissionBridge.isAlwaysAllowed`** | `runtime/local/ClaudePermissionBridge.kt:244` | **جديد: صفر مستدعِين** في المشروع/الاختبارات |
| **تبعية `eddsa`** | `app/build.gradle.kts:256` | **جديد: صفر استيراد** |
| `jacoco` بلا مهام تغطية | `build.gradle.kts` | لا تقرير ولا عتبة |
| `org.tukaani:xz` مُعلنة مرتين | `app/build.gradle.kts` | تكرار إعلان |

---

## 22. Incomplete Features

| الميزة | ما الموجود | ما الناقص | الدليل |
|---|---|---|---|
| MTP | عرض + تنزيل | رفع (upload) | لا أداة `mtp_upload` |
| HID | قراءة خام | فك المفاتيح إلى إدخال منطقي | `hid_read` فقط |
| الكاميرا | سرد الأجهزة | التقاط صورة | `camera_list` فقط |
| Termux/تفليش | قراءة + أدوات مساعدة | المرحلة الكتابية (flash/unlock/erase) ممتنعة عمدًا | تعليق في `DeviceActionFirewall` |
| طرفية داخل المستكشف | واجهة فقط | تنفيذ حقيقي (المتوفر في شاشة منفصلة) | `TerminalTabPlaceholder` |
| كاش جلسات محلي | Room مُعرَّفة | ربط فعلي/تخزين بلا شبكة | لا مستدعِين |
| «السماح الدائم» | حفظ القواعد + مسار بلا بادئة | مطابقة البادئة + استدعاء `isAlwaysAllowed` | §23 |
| تصنيف الإجراءات | 89 إجراءً | تصنيف 13 إجراءً ساقطًا | §23 |
| فحص AVB للـpayload | فحص ترويسات فقط | تحقق توقيع | تعليق صريح في `PayloadGuard` |
| Benchmarks | مولّدان | لا تشغيل آلي/تخزين نتائج | لا مهام CI |
| تغطية الاختبارات | 1323 اختبارًا | لا قياس تغطية (jacoco معطّل) | لا تقارير |

---

## 23. Broken Features

| # | العنصر | الموقع | الدليل القاطع | الأثر |
|---|---|---|---|---|
| 1 | **«السماح الدائم» ببادئة الأمر** | `assets/scripts/mushrea-code-claude-permission-hook.sh` (قسم always-rules) | إعادة إنتاج jq: `jq: error: Cannot index string with "commandPrefix"` + تنفيذ الخطاف فعليًا → انتظار حتى المهلة بدل السماح الفوري. البرهان المصحَّح (`. as $r`) يعطي النتيجة الصحيحة | المستخدم يضطر للموافقة كل مرة — تفشل بالرفض الآمن (لا خطر أمني، خسارة وظيفية) |
| 2 | **تصنيف 13 إجراءً في جدار الحماية** | `device/DeviceActionFirewall.kt` (`levelFor`, فرع `else`) | إعادة اشتقاق المجموعات: `ALL-AUTO = 32` لكن التصنيف الصريح = 19؛ 13 إجراءً يسقط إلى AUTO | إجراءات تغيّر حالة جهاز آخر (USB shell/install/push/pull…) تعمل بلا تأكيد — خطر أمني/سلوكي |
| 3 | **ادّعاء تشغيل اختبارات الأجهزة في CI** | `docs/device-matrix.md` (سطرا 8–9) | لا `connectedAndroidTest` في أي سير عمل | توثيق مضلِّل (لا خلل برمجي) |
| 4 | **أسماء متغيرات التوقيع في وثيقة الإصدار** | `docs/RELEASE.md` | الكود يقرأ `AND_CODE_*`؛ الوثيقة تكتب `ANDROID_CODE_*` | إصدار محلي موقَّع يفشل «بصمت» (يُبنى unsigned) |
| 5 | **رقم الجدار في تقرير المرحلة الأولى** | `docs/PROJECT_ANALYSIS_AR.md` §1/§26 | أُعيد اشتقاقه هنا: 19 لا 32 (و13 ساقطة) | مرجع سابق يحتاج قراءة هذا التقرير معه |

> ملاحظة مهمة: لم يُصلَح أي شيء — هذا القسم توثيقي فقط كما طلبت.

---

## 24. External Dependencies

### 24.1 ما هو داخل المشروع مقابل ما يُنزَّل خارجيًا

| المكوّن | داخل المستودع؟ | المصدر الخارجي | التحقق من السلامة |
|---|---|---|---|
| PRoot (+ libandroid-shmem/libtalloc) | ✅ وصفات بناء + مصدر `runtime_tools/` | تُبنى في CI محليًا | ✅ بناء محلي عبر سكربتات المشروع |
| Alpine minirootfs | ❌ | `dl-cdn.alpinelinux.org` | ✅ SHA-256 مثبّت في `local-runtime-manifest.json` |
| ثنائي OpenCode 1.18.5 | ❌ | GitHub Releases للمشروع الأصلي | ✅ SHA-256 |
| **scrcpy-server 4.0** | ✅ `assets/scrcpy/` (732KB) | — | ❌ بلا تحقق عند الدفع إلى الجهاز |
| Debian bookworm slim (لـAntigravity) | ❌ | Docker Hub (طبقات OCI) | ✅ SHA-256 لكل طبقة + `bsdutils` من `deb.debian.org` بتجزئة |
| `agy` (Antigravity 1.1.7) | ❌ | GitHub Releases | ✅ SHA-256 + `verifyArchive` |
| Codex CLI | ❌ | قناة إصدارات (registry) | ✅ SHA-512 مطابق لتجزئة القناة |
| **نموذج Vosk** | ❌ | `alphacephei.com` | ❌ **بلا أي تحقق (HTTPS فقط)** |
| خوادم MCP الثلاثة + الخطّافات | ✅ `assets/scripts/` | — | مصدر Python مراجَع/مُنفَّذ هنا |
| المكتبات (AndroidX/Compose/Koin/OkHttp/Vosk/sshj/smbj/…) | ❌ | Maven (محجوب هنا) | تعتمد على مستودعات موقّعة |

### 24.2 اعتماديات وتحليل

| بند | النتيجة |
|---|---|
| غير مستخدمة | `net.i2p.crypto:eddsa:0.3.0` (صفر استيراد)، و`room-*` بلا استهلاك فعلي (تستخدمها طبقة ميتة)، و`jacoco` بلا مهام |
| إصدارات Alpha/Beta | `androidx.security:security-crypto:1.1.0-alpha06` |
| مكتبات أصلية (Native) | لا `jniLibs` داخل المستودع عدا ما يولّده سكربت البناء (`prepareOpenCodeRuntimeNativeLibs`)؛ Vosk/PRoot يعتمدان على .so تُحزَّم وقت البناء |
| قيود ABI | `arm64-v8a` + `x86_64` فقط |
| الترخيص | MIT للتطبيق + `THIRD_PARTY_NOTICES` للوكلاء (الوكلاء أنفسهم ليسوا مشمولين) |
| مخاطر Supply‑chain | الأبرز: Vosk (بلا تجزئة)، و`google-services.json` مُلتزَم، وتنزيلات وقت التشغيل من 5 نطاقات مختلفة (مع تجزئات لـ4 منها) |

---

## 25. Hardware Requirements

| القدرة | العتاد المطلوب | الحالة في هذه البيئة |
|---|---|---|
| تشغيل التطبيق/الواجهة | هاتف أندرويد 8.0+ (arm64/x86_64) أو محاكي | ⛔ لا شيء متاح |
| وكيل الجهاز | جهاز + تمكين Accessibility يدويًا | ⛔ |
| USB/ADB/Serial/Fastboot/MTP/HID | منفذ USB-OTG + كابل + جهاز آخر بوضعية مصحّح USB | ⛔ |
| المرآة/scrcpy | جهاز ثانٍ | ⛔ |
| وكيل المكالمات | بطاقة SIM/اتصال + دور Dialer | ⛔ |
| الصوت | ميكروفون + مكبّر + خدمة تعرّف | ⛔ |
| الجدولة/المنبّهات | جهاز حقيقي (AlarmManager/PowerManager) | ⛔ |
| Bluetooth/BLE | شريحة BT + أذونات 12+ | ⛔ |
| Benchmark/Baseline | محاكي `pixel6Api34` أو جهاز | ⛔ ولا `/dev/kvm` |

---

## 26. External Services Requirements

| الخدمة | الغرض | إلزامية؟ | ملاحظة أمنية |
|---|---|---|---|
| مستودعات تنزيل الران‑تايم (Alpine/OpenCode/Debian/agy/Codex) | تثبيت البيئة والوكلاء | نعم للتثبيت | تجزئات مثبّتة عدا Vosk |
| Vosk models (`alphacephei.com`) | كلمة التنبيه | اختيارية | **بلا تجزئة** |
| مزوّدو النماذج (عبر الوكلاء) | تشغيل الذكاء | نعم عمليًا | لا يمرّ عبر خادم المشروع |
| GitHub (OAuth Device Flow + API) | تسجيل الدخول وPRs | اختيارية | `GITHUB_CLIENT_ID` وقت البناء |
| Firebase (Analytics/Crashlytics) | تشخيصات | نسخة github فقط | معطّل في debug، Analytics opt‑in |
| OpenAI TTS / ElevenLabs | قراءة الردود | اختيارية | مفاتيح مشفّرة على الجهاز |
| خادم OpenCode بعيد | بديل التشغيل المحلي | اختيارية | فلترة cleartext + Pin اختياري |
| خوادم SSH/SMB/FTP/WebDAV/mDNS | ميزات الاتصال | اختيارية | TOFU لـSSH، لا اختبارات |
| تطبيق Termux (+ `allow-external-apps`) | جسر Termux | اختيارية | صلاحية RUN_COMMAND |
| خدمات أندرويد النظامية (Telecom، Accessibility، NLS، MediaSession) | وظائف النظام | نعم | منح يدوي/دور افتراضي |

---

## 27. Known Limitations

1. **البيئة الحالية**: لا بناء ولا تشغيل ولا اختبار على جهاز؛ كل ما يتصل بأندرويد غير مُتحقَّق منه عمليًا هنا.
2. **حزمة اختبارات الأجهزة (23) لا تُنفَّذ في أي مكان** — لا CI ولا محلي.
3. **جدول أجهزة الاختبار الفعلي معلّق** (`docs/device-matrix.md` صفوفه اليدوية فارغة).
4. **لا قياس أداء/بطارية**: سكربت `battery_benchmark.sh` موجود بلا نتائج مسجّلة، و`jacoco` معطَّل بلا مهام تغطية.
5. **ميزات ناقصة معلنة** (MTP upload، HID decode، camera capture، المرحلة الكتابية للتفليش، طرفية تبويب المستكشف).
6. **GPU/تسريع**: Mirror يستخدم `screenrecord` (3 دقائق/دورة) وscrcpy للتحكم؛ لا مسار بديل موثّق.
7. **إصدارات ألفا** (`security-crypto`) وتنزيلات وقت تشغيل من مصادر خارجية.
8. **وثائق قديمة** (RELEASE/CI/device-matrix/COMPLETION_CHECKLIST) تخلق فجوة مع الواقع.
9. **حِزَم العمل الضخمة** (ChatViewModel 2767 سطرًا) ترفع كلفة التغيير.
10. **لا دعم لغير أندرويد/غير ABI المذكورين**.

---

## 28. Critical Issues

| # | المشكلة | الخطورة | الموقع | الدليل | الأثر |
|---|---|---|---|---|---|
| 1 | 13 إجراءً حسّاسًا تُنفَّذ بلا تأكيد (fail-open) | **عالية** | `DeviceActionFirewall.levelFor` | إعادة اشتقاق المجموعات + مسار التنفيذ في `DeviceAgentBridge:211` | تنفيذ عمليات تغيّر حالة جهاز آخر بلا موافقة |
| 2 | اختبارات الأجهزة لا تُنفَّذ في أي مكان | **عالية** | `.github/workflows/android.yml` | لا `connectedAndroidTest`؛ 0/23 مُنفَّذ | انحدارات واجهة/تكامل غير مكتشفة |
| 3 | «السماح الدائم» ببادئة معطوب | متوسطة | خطاف Claude (jq) | تنفيذ فعلي + إثبات jq | وظيفة لا تعمل (تفشل آمنة) |
| 4 | نموذج Vosk بلا تحقق تجزئة | متوسطة | `feature/wakeword/VoskModelInstaller` | قراءة كود = لا SHA-256 | مخاطرة سلسلة توريد |
| 5 | لا يمكن إعادة إنتاج البناء في بيئات مقيّدة الشبكة | متوسطة | بنية البناء تعتمد التنزيل الحي | فشل `./gradlew` الموثّق | لا بناء خارج CI/الشبكات المفتوحة |
| 6 | طبقات لا اختبار لها: USB/SSH/SMB/FTP/WebDAV/شبكة/BT | متوسطة | `device/*` | 0 ملفات اختبار لهذه الحزم | أخطاء ميدانية محتملة |
| 7 | `docs/device-matrix.md` و`docs/RELEASE.md` مخالفتان للواقع | منخفضة | `docs/` | مذكور §19 | إرباك للمهندس الجديد |
| 8 | كود ميت واسع (Room/Forge/…, `isAlwaysAllowed`, `eddsa`) | منخفضة | §21 | جرد موثّق | حجم/التباس وصيانة |
| 9 | `google-services.json` مُلتزَم | منخفضة | `app/` | وجود الملف | معرّفات عامة فقط |
| 10 | `sec-crypto alpha06` | منخفضة | `app/build.gradle.kts` | الإصدار | مخاطرة مستقبلية |

---

## 29. Recommended Fixes

> توصيات فقط — **لم يُنفَّذ أي تعديل**.

| # | الإصلاح | الموقع المقترح | الصيغة المقترحة |
|---|---|---|---|
| 1 | إغلاق الجدار في الاتجاه الآمن | `DeviceActionFirewall.levelFor` | جعل السقوط الافتراضي `CONFIRM` (لا AUTO)، أو إضافة الـ13 إلى قائمة CONFIRM الصريحة + اختبار يتحقق أن `AUTO ∪ CONFIRM == ALL` وأن كل إجراء يغيّر الحالة داخل CONFIRM |
| 2 | تصحيح مطابقة القاعدة في الخطاف | `mushrea-code-claude-permission-hook.sh` | `. as $r | ... ($c | startswith($r.commandPrefix))` كما في الإثبات المُنفَّذ هنا |
| 3 | استخدام منطق Kotlin بدل الاعتماد على الخطاف فقط | `ClaudePermissionBridge` | استدعاء `isAlwaysAllowed` قبل عرض الطلب (أو إزالة الدالة والاعتماد على الخطاف بعد إصلاحه) |
| 4 | تثبيت تجزئة نموذج Vosk | `VoskModelCatalog` + `VoskModelInstaller` | إضافة `sha256` لكل `VoskModelSpec` والتحقق قبل الفك |
| 5 | تشغيل اختبارات الأجهزة فعليًا | `.github/workflows/android.yml` | مهمة اختيارية بـmanaged device أو تصحيح `docs/device-matrix.md` صراحةً أنها لا تُشغَّل في CI |
| 6 | توحيد أسماء متغيرات التوقيع | `docs/RELEASE.md` | `AND_CODE_*`، وحذف/إنشاء `CHANGELOG.md` |
| 7 | اختبارات لطبقة الجهات الخارجية | `device/{ssh,remote,network,bluetooth}` | محاكيات خدمة (MockWebServer-style) + اختبارات عقد |
| 8 | تنظيف الكود الميت | §21 | حذف الطبقات غير المستخدمة وإزالة `eddsa`/`jacoco`/تكرار `xz` (لاحقًا، بأمر منفصل) |
| 9 | قياس التغطية | `build.gradle.kts` | مهمة `jacocoTestReport` + عتبة |
| 10 | تقليل نقاط الاستقصاء | الجسور | قناة LocalSocket/ContentProvider بدل ملفات مع استقصاء 0.3 ث (تحسين لاحق) |
| 11 | مراجعة NSC | `network_security_config.xml` | تقييد cleartext عبر `domain-config` حيث أمكن + اختبار يمنع أي طلب غير مُصفّى |
| 12 | تحديث الوثائق | `docs/CI.md`, `docs/device-matrix.md`, `docs/COMPLETION_CHECKLIST.md`, `HANDOFF.md` | مزامنة مع الحالة الفعلية |

---

## 30. Final Project Capability Map

### 30.1 ما يستطيع Mushrea Code فعله اليوم — بحسب ما أمكن التحقق منه

| السؤال | الجواب الصادق | الحكم |
|---|---|---|
| هل التطبيق يُبنى؟ | نعم — أثبته CI على الالتزام المدقَّق (debug + release/R8)، **ولم يُعَد إنتاجه محليًا** | 🟡 |
| هل يجتاز اختباراته الوحدوية؟ | نعم كحزمة على CI (1323 اختبارًا)، دون تفصيل فردي متاح | 🟡 |
| هل اختبارات الأجهزة تعمل؟ | **غير مُتحقَّق منها في أي مكان** | ❓ |
| هل أدوات وكيل الجهاز مُختبَرة؟ | طبقة العميل (MCP) نعم — تنفيذ فعلي؛ أما التنفيذ داخل التطبيق فلا | 🟡 |
| هل جدولة الأدوات تعمل؟ | طبقة العميل (8/8) نعم؛ AlarmManager/التنفيذ التطبيقي لا | 🟡 |
| هل خطاف الصلاحيات يحمي فعلًا؟ | نعم في المسارات المُختبرة (allow/deny/timeout/question)، مع خلل في «السماح الدائم» ببادئة | 🟡 |
| هل جدار الحماية يطابق ادّعاءه؟ | **لا**: 13 إجراءً يفلت من التأكيد، والرقم الصحيح 19 لا 32 | 🔴 |
| هل يعمل على هاتف حقيقي؟ | **غير قابل للتحقق في هذه البيئة**؛ الأدلة المتاحة كود + CI | ❓ |
| هل يمكن بناء نسخة موقّعة؟ | يلزم أسرار توقيع غير موجودة هنا | ❓ |
| هل الأداء مقبول؟ | لا قياسات | ❓ |

### 30.2 الفرق باختصار: ماذا يقول الكود ↔ ماذا ثبت

| الكود يقول | الواقع بعد التحقق |
|---|---|
| «89 إجراءً: 57 تلقائي + 32 يحتاج تأكيدًا» | **89 إجراءً: 70 تلقائي (57 + 13 ساقطة) + 19 تأكيد** |
| «السماح الدائم يعمل» | يعمل **بلا بادئة** فقط؛ ببادئة معطوب (jq) وبديله في Kotlin غير مستدعى |
| «اختبارات أجهزة موجودة» | موجودة كمصدر لكن **لم تُنفَّذ في أي مكان** |
| «Vosk نموذج يُنزَّل» | صحيح، **بلا تحقق تجزئة** |
| «البناء يعمل» | يعمل **على CI**، وغير قابل للتكرار في بيئة محجوبة الشبكة |

### 30.3 الخلاصة النهائية

**Mushrea Code، كما هو اليوم، مشروع كبير ومتماسك يُبنى ويجتاز حزمة اختبارات ضخمة على CI، وطبقة الأتمتة الخارجية فيه (خوادم MCP والخطّافات) تعمل فعلًا على مستوى العقد — وقد أثبت ذلك تنفيذٌ حقيقي في هذه الجلسة.** لكن الفجوة بين «ما يقوله الكود» و«ما ثبت» ليست صفرًا: هناك **خلل أمني حقيقي في جدار الحماية (13 إجراءً يفلت من التأكيد)**، و**عطب وظيفي في ميزة «السماح الدائم»**، و**23 اختبار أجهزة لا تُنفَّذ**، و**قياسات أداء غائبة تمامًا**. أما كل ما يتصل بسلوك التطبيق على عتاد حقيقي — الوكلاء، USB، المرآة، المكالمات، الصوت، التنبيهات — فيبقى **«موجود في الكود» لا «تم التحقق عمليًا»**، ولا يمكن تجاوز ذلك إلا ببيئة فيها Android SDK وجهاز/محاكي وشبكة غير محجوبة.

---

## ملحق: سجل الأوامر الأساسية المنفّذة (قابل لإعادة الإنتاج)

```bash
# 1) JDK (نجح)
pip3 install --break-system-packages jdk4py==17.0.9.2
export JAVA_HOME=/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime

# 2) محاولة البناء (فشلت — بيئيًا)
./gradlew --version
# java.io.EOFException: SSL peer shut down incorrectly  (services.gradle.org)

# 3) أدلة CI
gh run list -R hishamalmushrea-cloud/Mushrea.AI --limit 5
gh run view -R hishamalmushrea-cloud/Mushrea.AI 36780160410 --json jobs

# 4) خوادم MCP (نجحت — من /tmp/phase2)
python3 test_device_mcp.py        # 88 أداة، 86 جسر، 0 مهلة
python3 test_device_scenarios2.py # press/refusal/context
python3 test_schedule_mcp.py      # 8/8 + رفض + مهلة 90.1s
python3 test_browser_mcp.py       # browser_show + CDP error-path

# 5) الخطافات
sh mushrea-code-claude-permission-hook.sh   # allow/deny/timeout/question/no-jq/always-rule
bash scripts/commit-msg-hook.sh <msg-file>  # 4/4

# 6) إعادة الاشتقاق والفحص الساكن
python3 verify_firewall2.py   # 89 = 57 + 19 + 13 (ساقطة) ; READ_ONLY=39
python3 manifest_audit.py     # 30 صلاحية، 9 مكوّنات مُصدَّرة، 5 FGS
```

> **نهاية التقرير.** لم يُعدَّل أي كود أو ملف من المشروع أثناء إعداده؛ التغيير الوحيد في المستودع هو إضافة هذا الملف نفسه `docs/PROJECT_VERIFICATION_REPORT.md`.
