# DEVELOPMENT_LOG.md — سجل التطوير المرحلي

> سجل لكل تغيير فعلي في المستودع، مرتّب زمنيًا من الأحدث للأقدم.
> القاعدة: لا يُسجَّل هنا إلا ما نُفِّذ فعلًا، مع تاريخه وملفاته واختباراته ونتيجته والمشاكل المتبقية.

---

## 2026-10-01 — Phase 3 — Agent Manager

| البند | التفصيل |
|---|---|
| **المرحلة** | Phase 3 — مدير الوكلاء + توحيد لقطة الوكيل + إزالة آخر اعتماد صاعد |
| **الالتزامات** | `refactor(core): move workspace refs and folders into core` · `feat(agent): unify agent status through an agent manager` · `build(architecture): drop exceptions resolved in this phase` · `fix(agent): resolve moved workspace reference and kdoc clash` · `fix(agent): read the live snapshot and drive the aggregated flows in tests` · `refactor(agent): keep only the aggregation surface that has callers` |
| **ما تغيّر** | **3A (بنية):** نُقل `WorkspaceRef` من `runtime/RuntimeTarget.kt` إلى `core/workspace/`، ونُقل `WorkspaceFolders` (+اختباره) من `feature/workspace` إلى `core/workspace` — فزال آخر اعتماد صاعد `runtime → feature` (كان مُثبَّتًا كاستثناء في المرحلة 2 بعد محاولة فاشلة). **3B (مفردة + مدير):** `core/agent/AgentAuthState.kt` (7 حالات + `signedIn`/`busy`/`needsUserAction`) · `runtime/agent/AgentSnapshot.kt` (+`AgentCapabilities` بالقدرات المقروءة من الكود: install/update/signIn/permissionModes/systemPrompts/mcp/serverLifecycle) · `runtime/agent/AgentAuthMapper.kt` (Claude/Antigravity/Codex → المفردة) · `runtime/agent/AgentStatusSource.kt` + `runtime/local/AgentStatusSources.kt` (4 محوّلات رقيقة حول وحدات التحكم القائمة) · `runtime/agent/AgentManager.kt` (سطحه ثلاثة أسماء: `snapshots` · `snapshot` · `snapshotFlow`) · `feature/settings/AgentStatusPresentation.kt` (دالة عرض واحدة) + حذف **5 دوال مكررة** من شاشات الوكلاء وربط 4 مسارات في `SettingsNavGraph` باللقطة الموحَّدة. **3C (توثيق):** `docs/agents/AGENT_MANAGER.md` + تحديث `ARCHITECTURE.md` (§3 و§6.2 و§9). |
| **لماذا** | كان لكل وكيل حالة تسجيل دخول بنموذجه الخاص (نموذجان شبه متطابقين لـClaude/Antigravity + `Boolean` لـCodex)، وكل شاشة تعيد اشتقاق «جاهز؟» و«قابل للاستخدام؟» بنفسها، ولا يوجد مكان واحد يجيب «ما حالة الوكلاء الأربعة؟». لا يوجد سجل خامس: المدير لا يملك حالة، بل يجمع الوحدات القائمة عبر محوّلات. |
| **تحقق قبل التعديل** | جرد الوحدّات الثلاث العامة (`ClaudeCodeController` · `AntigravityController` · `CodexController`) وحالاتها وأدوارها (`update()` موجودة عند Claude/Antigravity فقط؛ `mcpServers()` في `OpenCodeBackend`) + جرد كل مستهلكي `WorkspaceRef`/`WorkspaceFolders` بالملف والسطر (ومنهم حالات **بدون استيراد** لأن الصنف كان في نفس الحزمة) قبل أي نقل. |
| **الاختبارات** | `AgentManagerTest` — **16 اختبارًا** جديدة: لقطة الوكيل المجهول، `ready` مع/بدون تسجيل دخول، `busy` أثناء التثبيت، `usable` للخادم وحده، ظهور رسالة الفشل، القدرات لكل وكيل، فصل حقائق كل وكيل في التجميع، **قراءة اللقطة الحيّة** (اختبار انحدار للخطأ المكتشف على CI)، خرائط تحويل حالات الدخول للوكلاء الثلاثة، قواعد `busy`/`signedIn`/`needsUserAction`، وخرائط دورة الحياة من الأنواع الثلاثة. |
| **القياس المقيس** | الاستثناءات المعمارية = **7 استيرادات في 5 ملفات** (كانت 8 في 6 ملفات، وحُذف استثناء `runtime → feature` نهائيًا) · `runtime → feature` = **0** · فحص المعمارية = OK · ملفات Kotlin الإنتاجية المفحوصة = 371 · سطح `AgentManager` العام = 3 أسماء (بعد حذف 5 دوال بلا مستهلك) · صفر استيراد غير مستخدم/غير مرتّب في الملفات المعدَّلة. |
| **البناء** | تعذّر محليًا (البيئة: لا Gradle/SDK). **التحقق تم على CI**: `1eebc7c` — `test-and-build` ✅ (تشغيل اختبارات الوحدة + `assembleGithubDebug` + تصريف `androidTest` + سكربتات الران‑تايم) · `static-analysis` ✅ (`detekt` + `spotlessCheck` + **قاعدة المعمارية**) · `lint` ✅ · `Commit Lint` ✅. |
| **دورة الإصلاح الموثّقة** | فشل 1 على `3423665` (PR event): `SettingsNavGraph.kt:69` و`ChatViewModelTest.kt:1981` كانا يشيران إلى `com.mushrea.code.runtime.WorkspaceRef` بالاسم الكامل — الاستيرادات نُقلت لكن الاسم المؤهَّل داخليًا لم يُلمس → أُصلح في `8df01e0`؛ + `spotlessKotlin` رفض KDoc مزدوجًا في رأس `AgentStatusSources.kt` (قاعدة `no-consecutive-comment`) → دُمج. فشل 2 على `d045847`: 3 اختبارات (وأكثرها مُقتطَع على `head -3`) لأن التأكيدات كانت تقرأ `snapshot()` قبل أن تُدفَّأ التدفقات → أُصلح بقراءة اللقطة الحيّة + استخدام نمط `TestScope(StandardTestDispatcher(testScheduler))` نفسه المعتمد في اختبارات المستودع. |
| **حالة المرحلة** | ✅ **مستقرة** — كل فحوص CI خضراء على آخر التزام كود (`1eebc7c`)، ولا خطأ حرج معلّق. |
| **مشاكل متبقية** | لا سجل أحداث موحَّد لعمليات الوكلاء (Phase 5) · لا `Codex.update()` (لا مسار إصدارات في وحدته) · لا توحيد لنماذج التثبيت الثلاثة نفسها (التوحيد يجري في المُترجِم؛ الدمج يُعاد النظر فيه عند أول حاجة فعلية) · اختبارات الأجهزة (23) ما زالت لا تُنفَّذ في أي سير عمل · `device → feature` و`data → feature` و`core → data`/`core → runtime` استثناءات قائمة بمراحلها. |
| **قرارات هندسية** | **لم تُختلق واجهة `start()/stop()` موحَّدة** لأن ثلاثة وكلاء لا يملكون خادمًا (`AgentCapabilities.serverLifecycle` يعلن الفرق) · **لم تُدمج نماذج التثبيت الثلاثة** لأن دمجها يمسّ كل وحدة وشاشاتها بلا فائدة سلوكية · **لم يُبنَ سجل خامس** · سطح المدير اقتُصر على ما له مستهلك فعلي (حُذفت `isReady`/`isBusy`/`capabilities()`/`knows()`/`lifecycleOf()`/`refreshAll()` بعد أول مراجعة لأنها كانت تعيد التعبير عن حقول اللقطة أو بلا مستهلك — «لا API ميت») · شاشة OpenCode أُبقيت على نص حالتها الخاص (يحمل خطوة التثبيت والمنفذ) ووُحّد علم التشغيل فقط. |
| **ملاحظة بيئية (`Cannot Verify — Environment Limitation`)** | لم يمكن تشغيل أي وكيل فعليًا (تثبيت/دخول/دور) لأن ذلك يحتاج PRoot على جهاز أندرويد + شبكة غير محجوبة + حساب. المطلوب للتحقق: جهاز arm64/x86_64 (أو محاكي مع KVM) واتصال يقبل تنزيلات الران‑تايم. |

---

## 2026-10-01 — Phase 2 — Runtime Manager

| البند | التفصيل |
|---|---|
| **المرحلة** | Phase 2 — مدير ران‑تايم مركزي + توحيد دورة الحياة |
| **الالتزامات** | `refactor(runtime): break the data-runtime dependency cycle` · `feat(runtime): unify runtime lifecycle vocabulary` · `docs(runtime): document the runtime manager and lifecycle` |
| **ما تغيّر** | **2A (بنية):** نُقل `ConnectionProfile`+`ConnectionProfileCodec` وواجهتا `RuntimeConnectionStore`/`AdbConnectionStore` إلى `core/connection`، ونُقلت المستودعات الثلاثة التي تخدم الران‑تايم (`RuntimeActivityRepository` · `RuntimeCatalogRepository` · `SessionAutoArchiver`) مع اختباراتها إلى `runtime/`؛ حُدِّثت 31 استيرادًا في 39 ملفًا، وحُذفت 5 استثناءات معمارية صارت زائدة. **2B (مفردات):** `core/runtime/RuntimeLifecycle.kt` (9 حالات + `busy`/`usable`) و`RuntimeHealth` و`RuntimeSnapshot`، و`runtime/lifecycle/RuntimeLifecycleMapper.kt` (ترجمة نقية للنماذج الأربعة القائمة)، و`RuntimeTarget.lifecycle` (تنفيذ افتراضي)، و`OpenCodeAgentUiState.lifecycle`. **2C (توثيق):** `docs/runtime/RUNTIME_MANAGER.md` + تحديث `ARCHITECTURE.md`. |
| **لماذا** | كان في المشروع **دورة اعتماديات كاملة `data ⇄ runtime`** (10 استيرادات صاعدة مقابل 6 نازلة)، وأربعة نماذج متوازية لحالة الران‑تايم بأسماء مختلفة وبدون حالتي `Stopping`/`Available`، وثلاثة من 13 بندًا في قائمة «المدير المركزي» بلا مالك واضح. |
| **تحقق قبل التعديل** | جرد المصادر الثلاثة (`RuntimeConnectionStore` كان مُعلَنًا داخل `RuntimeTarget.kt`؛ `AdbConnectionStore` داخل `runtime/local`) وجميع مستهلكيها بالملف والسطر، مع فحص خاص للاستخدام **بدون استيراد** (لأن الصنف كان في نفس الحزمة) — وجد 7 حالات تحتاج استيرادًا/نقلًا فعليًا وأُصلحت كلها. |
| **الاختبارات** | `RuntimeLifecycleMapperTest` — **16 اختبارًا** جديدة (ترجمة كل حالة، عدم اجتماع `busy`/`usable`، أن `Connecting` ليست جاهزًا، رسالة الفشل البديلة لـCodex) + اختبار `lifecycle` على هدف وهمي في `RuntimeRegistryTest`. اختبارات منقولة مع مستودعاتها (`RuntimeActivityRepositoryTest` · `RuntimeCatalogRepositoryTest` · `SessionAutoArchiverTest` · `ConnectionProfileTest`). |
| **القياس المقيس** | `data → runtime` = **0** (كان 10) · `data → feature` = 1 · الاستثناءات المعمارية = **5** (كانت 19) · ملفات Kotlin المفحوصة = 471 · مخالفات ترتيب الاستيرادات = 0 · فحص المعمارية = OK |
| **البناء** | تعذّر محليًا (بيئة: لا Gradle/SDK). **التحقق تم على CI**: تشغيل `36790130322` على `0c940ac` بعد 3 دورات إصلاح — `test-and-build` ✅ (تشغيل **1,340 اختبارًا** = 1,323 + 17 جديدًا، و`assembleGithubDebug`، وتصريف `androidTest`) · `static-analysis` ✅ (`detekt` + `spotlessCheck` + **قاعدة المعمارية**) · `lint` ✅. **دورة الإصلاح الموثّقة:** فشل 1 = رموز نُقلت مع ملفاتها (UnreadSessionStore/RuntimeCatalogState) → فصلت إلى `core` والاستيرادات صُحّحت؛ فشل 2 = استيرادات داخل نفس الحزمة يرفضها ktlint (أصلحها CI تلقائيًا في `21fd8f6`) + رمز شقيق (`RuntimeActivityMessages`) بقي في `data` وتطلّب استيرادًا صريحًا؛ فشل 3 = استيراد ناقص `CodexInstallStatus` في ملفي الجديد. كلها أُصلحت قبل الانتقال. |
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
