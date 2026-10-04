# RUNTIME_MANAGER.md — مدير الران‑تايم المركزي

> **المرحلة 2 — Runtime Manager.** هذه الوثيقة تصف ما يملكه المشروع **اليوم** لإدارة الران‑تايم، مكوّنًا مكوّنًا، وما نُفِّذ في هذه المرحلة، وما زال ناقصًا. كل جملة فيها لها دليل في الكود مع مسار الملف.
> المرجع المعماري: `docs/architecture/ARCHITECTURE.md` (§4 و§6.1).

---

## 1. الفكرة

لا يوجد في المشروع «مدير ران‑تايم» واحد باسم واحد؛ توجد **أربع طبقات إدارة قائمة** يترابط بعضها ببعض. المرحلة 2 **لم تُنشئ مديرًا خامسًا موازيًا** (وهو ما نهت عنه قواعد العمل)، بل:

1. **فكّت الالتحام** الذي كان يجعل `data` تعتمد على `runtime` (دورة كاملة).
2. **وحّدت مفردات دورة الحياة** في نوع واحد يقرأه كل شيء.
3. **ثبّتت المصادر الأربعة القائمة** كما هي، ووثّقت مسؤولية كل واحد منها بدقة.

---

## 2. المكوّنات الأربعة القائمة (لا تكرار)

| المكوّن | الملف | مسؤوليته |
|---|---|---|
| `RuntimeRegistry` | `runtime/RuntimeRegistry.kt` | **الاكتشاف والاختيار**: يبني قائمة الأهداف (`RuntimeTarget`) من الران‑تايم المحلي + الاتصالات البعيدة المخزَّنة، ويقرر الهدف المختار والهدف الذي يخدم وكيلًا معيّنًا (`targetFor(agent)`) ويحفظ الاختيار |
| `RuntimeTarget` | `runtime/RuntimeTarget.kt` | **عقد هدف واحد**: `state: StateFlow<RuntimeState>` (Connected/Connecting/Disconnected/Unavailable/Failed) + `lifecycle` + connect/disconnect/health/workspaces + بروتوكول OpenCode (`OpenCodeBackend`) |
| `LocalRuntimeManager` | `runtime/local/LocalRuntimeManager.kt` (666 سطرًا) | **دورة حياة البيئة المحلية المشتركة**: تثبيت/تشغيل/إيقاف/حذف/تحديث/تراجع، الحالة `LocalRuntimeStatus`، فحص الصحة عبر منفذ، عدد مرات إعادة التشغيل، آخر رمز خروج، حفظ البيانات الوصفية |
| وحدّات الوكلاء | `runtime/local/{ClaudeCode,Antigravity,Codex}Controller.kt` | **حالة كل وكيل على حدة**: التثبيت، الإصدار، التحقق من التحديث، حالة تسجيل الدخول، وضع الصلاحيات — كل واحد بنموذج حالته الخاص |

**العلاقة:** `RuntimeRegistry` يعرف *ما هو متاح*؛ `RuntimeTarget` يقول *ما حالة الاتصال*؛ `LocalRuntimeManager` يدير *العملية المحلية*؛ ووحدّات الوكلاء تدير *تثبيت كل وكيل*. لا تعارض بينها، لكنها كانت تتكلم أربع لغات مختلفة — وهذا ما وحدته هذه المرحلة.

---

## 3. ما نُفِّذ في المرحلة 2

### 3.1 مفردات دورة حياة واحدة

`com.mushrea.code.core.runtime.RuntimeLifecycle`:

```
Unknown → Available → Installing → Installed → Starting → Running
                 ↑                                          │
                 │                                          ▼
                 +--- Stopped ←-------------------------- Stopping
   (أي حالة) → Failed
```

مع:
- `busy` (Installing/Starting/Stopping) و`usable` (Running فقط) — **منفصلتان بالتصميم**، فلا يمكن وصف عملية جارية بأنها جاهزة.
- `RuntimeHealth`: HEALTHY · DEGRADED · UNREACHABLE · UNKNOWN — الصحة **مفصولة عن دورة الحياة** لأن ران‑تايم قد يكون Running ومع ذلك لا يجيب (الحالة التي يعالجها التطبيق فعلًا في المحادثة).
- `RuntimeSnapshot`: `id, name, agent, lifecycle, health, version, port, environment, error` — نموذج القراءة الواحد الذي طلبته المرحلة.

### 3.2 الترجمة (بلا إعادة كتابة)

`runtime/lifecycle/RuntimeLifecycleMapper.kt` — دوال نقية تترجم:

| المصدر | الناتج |
|---|---|
| `LocalRuntimeStatus` (8 حالات) | `Available` · `Installing(step)` · `Starting` · `Stopped` · `Running(version)` · `Failed(reason)` · `Failed(ABI)` |
| `RuntimeState` (5 حالات) | `Stopped` · `Starting` · `Running(version)` · `Available` · `Failed(reason)` |
| `ClaudeCodeUiState` / `ClaudeInstallStatus` | `Available` · `Installing(null)` · `Installed` · `Failed(message)` |
| `AntigravityControllerState` / `AntigravityInstallStatus` | `Available` · `Installing(step)` · `Installed` · `Failed(message)` |
| `CodexUiState` / `CodexInstallStatus` | `Available` · `Installing(step)` · `Installed` · `Running(version)` بعد التثبيت **وتسجيل الدخول** · `Failed(reason)` |

ملاحظات صدق مقصودة:
- **Claude `Installing` لا يحمل وصف خطوة** لأن `ClaudeInstallStatus.Installing(step: Int)` يحمل **معرّف مورد نصّي** لا اسمًا؛ الترجمة النقية لا تستطيع حلّ الموارد، فتركنا الحقل `null` بدل تلفيق نص.
- **Claude/Antigravity بعد نجاح التثبيت = `Installed` لا `Running`** لأنهما لا يشغّلان خادمًا دائمًا؛ تشغيلهما يبدأ عملية لكل دور.
- **Codex = `Running` فقط بعد التثبيت والتسجيل معًا**، و`Installed` تعني «جاهز لتسجيل الدخول».

### 3.3 الربط الحقيقي (مستهلكان، لا كود معلَّق)

| المستهلك | الملف | الشكل |
|---|---|---|
| كل هدف ران‑تايم | `runtime/RuntimeTarget.kt` | `val lifecycle: Flow<RuntimeLifecycle>` — **تنفيذ افتراضي مبني على `state`**، فلا ينكسر أي هدف قائم ولا تُكرَّر الترجمة في كل هدف |
| شاشة إعدادات OpenCode | `feature/settings/OpenCodeAgentSettingsViewModel.kt` | `OpenCodeAgentUiState.lifecycle` — مشتق من `status`، فلا يمكن أن ينحرف عن الحالة المعروضة |

### 3.4 الاختبارات

| الملف | العدد | ماذا يثبت |
|---|---|---|
| `app/src/test/java/com/mushrea/code/runtime/lifecycle/RuntimeLifecycleMapperTest.kt` | 14 اختبارًا | أن كل حالة مصدر تُترجم إلى الحالة الصحيحة، وأن `busy`/`usable` لا تجتمعان، وأن «الاتصال الجاري» لا يُقرأ كـ«جاهز»، وأن فشل التثبيت بلا رسالة يعطي رسالة |
| `app/src/test/java/com/mushrea/code/runtime/RuntimeRegistryTest.kt` | +1 اختبار | أن أي `RuntimeTarget` يعرض المفردة الموحَّدة عبر `lifecycle` مع تغيّر حالته |

---

## 4. تغطية قائمة المطلوب (تشغيل مركزي)

ما طلبته المرحلة نصًّا، مقابل الواقع بعد هذه المرحلة:

| # | المطلوب | الحالة | الدليل / الفجوة |
|---|---|---|---|
| 1 | Runtime discovery | ✅ موجود | `RuntimeRegistry.buildTargets()` + `unusableProfileIds` |
| 2 | Runtime installation | ✅ موجود | `LocalRuntimeManager.installAndStart/reinstall/installFullDevelopmentTools` + مثبّتات الوكلاء |
| 3 | Runtime lifecycle | ✅ **موحَّد الآن** | `RuntimeLifecycle` + المُترجِم + `RuntimeTarget.lifecycle` |
| 4 | Start | ✅ موجود | `LocalRuntimeManager.start/ensureRunning` + `startAction` لكل وكيل |
| 5 | Stop | ✅ موجود | `LocalRuntimeManager.stop` + `stopAction` |
| 6 | Restart | 🟡 **جزئي** | موجود في شاشة وكيل OpenCode (`restartAction`) وفي مدير البيئة المحلية، وليس كعملية موحَّدة لكل الران‑تايمات (وكيل Codex/Claude لا يملك «restart») |
| 7 | Status | ✅ **موحَّد الآن** | `RuntimeSnapshot` |
| 8 | Health | 🟡 جزئي | `LocalRuntimeManager.isHealthy()` (فحص منفذ) + `RuntimeTarget.health()`؛ لا فحص صحة دوري مركزي، والصحة من حالة الاتصال فقط (`RuntimeLifecycleMapper.healthOf`) |
| 9 | Logs | 🟡 جزئي | لا حتى الآن سوى سجلات العملية/التشخيص: `lastExitCode()`, `lastExitAtMillis()`, `restartCount()` + `LocalRuntimeDiagnostics`. **لا واجهة سجلات موحَّدة بعد** — مؤجَّلة إلى المرحلة 3 (مع الوكيل) |
| 10 | Sessions | ✅ موجود | عبر `OpenCodeBackend` (listSessions/createSession/listMessages…) و`RuntimeCatalogRepository` |
| 11 | Version | ✅ موجود | `LocalRuntimeStatus.Ready.version` + `CodexUiState.version` + `version` في الحزم المثبّتة |
| 12 | Environment | 🟡 جزئي | ABI/عدم الدعم (`UnsupportedAbi`), جذر البيانات (`filesDir/runtime`), بيانات وصفية للأدوات (`LocalRuntimeMetadata`) — لا وصف بيئة موحَّد بعد |
| 13 | Error state | ✅ **موحَّد الآن** | `RuntimeLifecycle.Failed(reason)` + `RuntimeSnapshot.error` |

**الخلاصة:** من 13 بندًا: 9 مكتملة، و3 جزئية (restart/logs/health+environment)، وكل النواقص موثّقة هنا كبنود للمراحل القادمة بدل ادّعاء اكتمالها.

---

## 5. ما لم يُنفَّذ في هذه المرحلة (مقصود)

| البند | لماذا أُجّل | المرحلة |
|---|---|---|
| دمج `ClaudeInstallStatus`/`AntigravityInstallStatus`/`CodexInstallStatus` في نموذج واحد | يتطلب تغييرًا في واجهات الوكلاء وشاشاتهم؛ حدوده تظهر مع «مدير الوكلاء» | Phase 3 |
| إضافة `Stopping`/`Starting` فعليتين لكل وكيل | لا وجود لعملية طويلة الإيقاف اليوم؛ إضافتها بلا حالة حقيقية = حالة وهمية | Phase 3 (مع دورة حياة العملية) |
| واجهة سجلات موحَّدة لكل ران‑تايم | تحتاج قناة سجلات لكل وكيل (`CodexJsonRpcClient`/PTY/Antigravity) قبل عرضها | Phase 3 |
| فحص صحة دوري مركزي | لا فائدة بلا مستهلك يعرضه؛ والفحص الحالي عند الطلب | Phase 6 (مع وكيل الجهاز) |

---

## 6. كيف يُختبر هذا (وما لا يمكن اختباره هنا)

| ما يمكن اختباره | الطريقة |
|---|---|
| الترجمة بين الحالات | `RuntimeLifecycleMapperTest` (وحدة، تعمل في CI) |
| عرض الهدف لدورة الحياة | `RuntimeRegistryTest` (وحدة، تعمل في CI) |
| التثبيت/التشغيل/الإيقاف الفعلي | **Cannot Verify — Environment Limitation**: يحتاج جهازًا/محاكيًا (PRoot لا يعمل إلا على أندرويد) |
| الصحة الفعلية عبر المنفذ | **Cannot Verify — Environment Limitation**: يحتاج تشغيل الران‑تايم المحلي داخل الجهاز |
