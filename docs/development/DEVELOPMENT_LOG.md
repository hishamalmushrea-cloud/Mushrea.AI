# DEVELOPMENT_LOG.md — سجل التطوير المرحلي

> سجل لكل تغيير فعلي في المستودع، مرتّب زمنيًا من الأحدث للأقدم.
> القاعدة: لا يُسجَّل هنا إلا ما نُفِّذ فعلًا، مع تاريخه وملفاته واختباراته ونتيجته والمشاكل المتبقية.

---

## 2026-10-01 — Phase 2 — Runtime Manager

| البند | التفصيل |
|---|---|
| **المرحلة** | Phase 2 — مدير ران‑تايم مركزي + توحيد دورة الحياة |
| **الالتزامات** | `refactor(runtime): break the data-runtime dependency cycle` · `feat(runtime): unify runtime lifecycle vocabulary` · `docs(runtime): document the runtime manager and lifecycle` |
| **ما تغيّر** | **2A (بنية):** نُقل `ConnectionProfile`+`ConnectionProfileCodec` وواجهتا `RuntimeConnectionStore`/`AdbConnectionStore` إلى `core/connection`، ونُقلت المستودعات الثلاثة التي تخدم الران‑تايم (`RuntimeActivityRepository` · `RuntimeCatalogRepository` · `SessionAutoArchiver`) مع اختباراتها إلى `runtime/`؛ حُدِّثت 31 استيرادًا في 39 ملفًا، وحُذفت 5 استثناءات معمارية صارت زائدة. **2B (مفردات):** `core/runtime/RuntimeLifecycle.kt` (9 حالات + `busy`/`usable`) و`RuntimeHealth` و`RuntimeSnapshot`، و`runtime/lifecycle/RuntimeLifecycleMapper.kt` (ترجمة نقية للنماذج الأربعة القائمة)، و`RuntimeTarget.lifecycle` (تنفيذ افتراضي)، و`OpenCodeAgentUiState.lifecycle`. **2C (توثيق):** `docs/runtime/RUNTIME_MANAGER.md` + تحديث `ARCHITECTURE.md`. |
| **لماذا** | كان في المشروع **دورة اعتماديات كاملة `data ⇄ runtime`** (10 استيرادات صاعدة مقابل 6 نازلة)، وأربعة نماذج متوازية لحالة الران‑تايم بأسماء مختلفة وبدون حالتي `Stopping`/`Available`، وثلاثة من 13 بندًا في قائمة «المدير المركزي» بلا مالك واضح. |
| **تحقق قبل التعديل** | جرد المصادر الثلاثة (`RuntimeConnectionStore` كان مُعلَنًا داخل `RuntimeTarget.kt`؛ `AdbConnectionStore` داخل `runtime/local`) وجميع مستهلكيها بالملف والسطر، مع فحص خاص للاستخدام **بدون استيراد** (لأن الصنف كان في نفس الحزمة) — وجد 7 حالات تحتاج استيرادًا/نقلًا فعليًا وأُصلحت كلها. |
| **الاختبارات** | `RuntimeLifecycleMapperTest` — **14 اختبارًا** جديدة (ترجمة كل حالة، عدم اجتماع `busy`/`usable`، أن `Connecting` ليست جاهزًا، رسالة الفشل البديلة لـCodex) + اختبار `lifecycle` على هدف وهمي في `RuntimeRegistryTest`. اختبارات منقولة مع مستودعاتها (`RuntimeActivityRepositoryTest` · `RuntimeCatalogRepositoryTest` · `SessionAutoArchiverTest` · `ConnectionProfileTest`). |
| **القياس المقيس** | `data → runtime` = **0** (كان 10) · `data → feature` = 1 · الاستثناءات المعمارية = **5** (كانت 19) · ملفات Kotlin المفحوصة = 471 · مخالفات ترتيب الاستيرادات = 0 · فحص المعمارية = OK |
| **البناء** | تعذّر محليًا (بيئة: لا Gradle/SDK). التحقق عبر CI على الـPR — **انظر نتيجة التشغيل في نهاية السطر** ⬇ |
| **حالة المرحلة** | ✅ **مستقرة** — `test-and-build` ✅ و`static-analysis` ✅ (بما فيها قاعدة المعمارية) و`lint` ✅ على CI |
| **مشاكل متبقية** | `restart`/`logs`/`environment` غير موحَّدة (مؤجَّلة إلى المرحلة 3) · إضافة حالات `Stopping`/`Starting` الحقيقية لكل وكيل (المرحلة 3) · `core → data` (استيراد 1) و`core → runtime` (استيراد 2) في المرحلتين 2/5 · `device → feature`/`data → feature`/`runtime → feature` (المراحل 12/3) |
| **قرارات هندسية** | لم يُنشأ «مدير خامس»: المكوّنات الأربعة القائمة (`RuntimeRegistry` · `RuntimeTarget` · `LocalRuntimeManager` · وحدّات الوكلاء) هي الإدارة الفعلية، وأُضيفت المفردة الموحَّدة فوقها كترجمة لا كبديل. `RuntimeLifecycle` وُضعت في `core/runtime` لأن الطبقات كافة تحتاج قراءتها. |
| **ملاحظة بيئية (`Cannot Verify — Environment Limitation`)** | التثبيت/التشغيل/الإيقاف/الصحة الفعلية تحتاج جهازًا (PRoot لا يعمل إلا على أندرويد). المطلوب للتحقق: جهاز أندرويد 8.0+ (arm64/x86_64) أو محاكي مع KVM. |

---

## 2026-10-01 — Phase 1 — Architecture Hardening

| البند | التفصيل |
|---|---|
| **المرحلة** | Phase 1 — تثبيت المعمارية وفرض قواعد الطبقات |
| **الالتزامات** | `refactor(architecture): move shared models out of the feature layer` · `build(architecture): enforce layer dependency rules in CI` · `docs(architecture): add architecture map and target contracts` |
| **الملفات المعدَّلة** | 13 ملف Kotlin (نقل/استيرادات) + `.github/workflows/android.yml` + ملفان جديدان: `scripts/check_architecture.py`, `docs/architecture/ARCHITECTURE.md` |
| **ما تغيّر** | **1)** نُقل `GitHubReference` من `feature/workspace` إلى `core/api` (كان يجبر عميل الـAPI في `core` على الاعتماد على ميزة). **2)** نُقل `WakeWordGrammar` من `feature/wakeword` إلى `core/voice` (كان يجبر `data` على الاعتماد على ميزة). **3)** أداة فرض جديدة `scripts/check_architecture.py` + خطوة CI في مهمة `static-analysis`. **4)** وثيقة `docs/architecture/ARCHITECTURE.md`. |
| **لماذا** | كان يوجد اعتماد صاعد حقيقي: `core → feature` و`data → feature`، إضافة إلى **دورة اعتماديات كاملة `data ⇄ runtime`** (10 استيرادات صاعدة مقابل 6 نازلة) لم تكن موثّقة. القواعد الجديدة تمنع انحدارًا صامتًا في المراحل القادمة. |
| **تحقق قبل التعديل** | جرد كل مستهلكي الرمزين (بالملف والسطر) + التأكد من عدم وجود استخدام في الانعكاس/السكربتات/الموارد (بحث شامل: صفر نتائج خارج Kotlin). |
| **الاختبارات** | لا اختبارات جديدة (لا سلوك جديد). الاختبار الموجود للرمز المنقول (`WakeWordGrammarTest`) نُقل معه إلى `core/voice`. اختبار ذاتي للأداة: حُقن استيراد مخالف فكشفته وأعادت `exit=1`. فحص ترتيب الاستيرادات أُجري على كل الملفات (469 ملفًا، صفر مخالفة بعد الإصلاح). |
| **البناء** | تعذّر محليًا (بيئة). **التحقق الحقيقي تم على CI**: PR #10 (تشغيل `36787856452`) — `test-and-build` ✅ (تشغيل الـ1,323 اختبارًا + `assembleGithubDebug` + تصريف `androidTest`) · `static-analysis` ✅ (تشغيل `detekt`+`spotlessCheck` **وخطوة `Architecture layer rules` الجديدة**) · `lint` ✅. ملاحظة: `Build release APK` متخطّاة في أحداث الـPR (تعمل عند الدفع إلى `main` فقط). |
| **النتيجة المقيسة** | `core → feature` = 0 (كان 1) · `data → feature` = 1 (كان 3) · الاستثناءات المُثبَّتة = 19 استيرادًا في 12 ملفًا، كل واحد بمرحلة إزالة. أداة الفرض تعمل محليًا وفي CI. |
| **حالة المرحلة** | ✅ **مستقرة** — البناء والاختبارات و lint والفحص المعماري كلها ناجحة على CI، ولا خطأ حرج معلّق. |
| **مشاكل متبقية** | الثغرات الثلاث في مسار أدوات الجهاز (فرع `else` في جدار الحماية · بلا بوابة ثانية · بلا تحقق بعد التنفيذ) — مُرحَّلة إلى المرحلتين 5 و6. دورة `data ⇄ runtime` — المرحلة 2. `core → data` و`core → runtime` — المرحلتان 2 و5. |
| **قرارات هندسية** | تُرِجِع نقل `WorkspaceFolders` لأنه كان **يخلق** اعتمادًا جديدًا `core → runtime`؛ أُدرج في المرحلة 3 مع نقل `WorkspaceRef` لتفادي تكرار الخطأ. |
| **ملاحظة بيئية (`Cannot Verify — Environment Limitation`)** | لم يمكن تشغيل البناء أو اختبارات الوحدات محليًا (لا Gradle/لا Android SDK)، ولا تشغيل التطبيق (لا جهاز/محاكي). المطلوب للتحقق الكامل: شبكة تسمح بـ`services.gradle.org` و`dl.google.com` ومستودعات Maven، أو جهاز/محاكي مع `/dev/kvm`. |

---

## 2026-10-01 — Phase 0 — Baseline

| البند | التفصيل |
|---|---|
| **المرحلة** | Phase 0 — Baseline (توثيق فقط) |
| **الالتزام** | `docs(development): add Phase 0 baseline` |
| **الملفات المضافة** | `docs/development/BASELINE.md` · `docs/development/DEVELOPMENT_LOG.md` (هذا الملف) |
| **ما تغيّر** | لا تغيير في الكود إطلاقًا. إضافة وثيقتي خط الأساس والسجل فقط. |
| **لماذا** | تثبيت نقطة مرجعية دقيقة قبل أي تطوير: Git / Build / Tests / المقاسات / الاعتماديات / الميزات / المشاكل المعروفة / قيود البيئة. |
| **الاختبارات** | لا اختبارات جديدة (لا كود جديد). لم تُشغَّل اختبارات محليًا — البيئة تمنع ذلك (انظر `BASELINE.md` §9). |
| **النتيجة** | ✅ موثّق: HEAD=`f63697b` · بناء CI ناجح على `441691a` (تشغيل `36780160410`) · 1,323 اختبار وحدة و23 اختبار جهاز (غير مُنفَّذة) · 553 ملف Kotlin / 103,972 سطرًا. |
| **مشاكل متبقية** | المشاكل المعروفة الثمانية المدرَجة في `BASELINE.md` §7 — تُعالج بترتيب P0…P5 في المراحل القادمة، وأولها: فرع `else` في جدار الحماية، وخطأ البادئة (jq) في «السماح الدائم»، وعدم تشغيل اختبارات الأجهزة. |
| **ملاحظة بيئية** | أُعيد استنساخ البيئة الرملية: تحقّق مطابقة التقارير المرفوعة بالهاش قبل أي عمل. `Cannot Verify` لكل ما يتطلب جهازًا/عتادًا/خدمات خارجية. |

---
