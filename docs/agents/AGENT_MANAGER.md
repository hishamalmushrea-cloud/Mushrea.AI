# AGENT_MANAGER.md — إدارة الوكلاء الأربعة

> **المرحلة 3 — Agent Manager.** هذه الوثيقة تصف كيف يدار كل وكيل في Mushrea Code بعد التوحيد: ما هو مشترك، وما يختلف بين الوكلاء **بسبب حقيقي** لا بسبب تاريخ التطوير. كل جملة مدعومة بمسار ملف.
> المراجع: `docs/architecture/ARCHITECTURE.md` (§6.2) · `docs/runtime/RUNTIME_MANAGER.md` (المرحلة 2).

---

## 1. الوكلاء الأربعة (الواقع)

| الوكيل | المعرّف | كيف يعمل | التثبيت | التحديث | تسجيل الدخول | أوضاع الصلاحيات | System Prompts | MCP | دورة تشغيل |
|---|---|---|---|---|---|---|---|---|---|
| **OpenCode** | `opencode` | خادم HTTP محلي (`127.0.0.1:4097`) يعمل في PRoot | نعم | نعم | لا (مصادقة المزوّدين منفصلة) | — | نعم | نعم | **خادم دائم** (تشغيل/إيقاف/إعادة) |
| **Claude Code** | `claude-code` | عملية لكل دور داخل Alpine | نعم | نعم | نعم (متصفح + كود) | نعم | نعم | نعم | عملية لكل دور |
| **Antigravity** | `antigravity` | `agy` داخل PTY على Debian | نعم | نعم | نعم (Google) | نعم | — | نعم | عملية لكل دور |
| **Codex** | `codex` | `app-server` (JSON-RPC) | نعم | **لا** (`CodexController` بلا `update()`) | نعم (ChatGPT/مفتاح) | — | — | نعم | عملية لكل دور |

**كل قيمة في الجدول مأخوذة من الكود**، والجدول نفسه هو محتوى `AgentCapabilities` في الكود (`runtime/local/AgentStatusSources.kt`).

---

## 2. ما نُفِّذ في هذه المرحلة

### 2.1 مفردات تسجيل دخول واحدة

`core/agent/AgentAuthState.kt`:

```
Unknown · SignedOut · Starting · AwaitingBrowser(url, transcript) · Verifying · SignedIn(account) · Failed(message, transcript)
```

مع `signedIn` (فقط `SignedIn`) و`busy` (أثناء تدفق الدخول) و`needsUserAction` (متصفح أو فشل).

**قبل التوحيد:** كان `ClaudeAuthCoordinator.State` و`AntigravityAuthCoordinator.State` نموذجين شبه متطابقين مكتوبين مرتين، وCodex يكتفي بـ`Boolean`. الآن يُترجَم الثلاثة إلى مفردة واحدة عبر `runtime/agent/AgentAuthMapper.kt` (دوال نقية، مُختبَرة).

### 2.2 لقطة واحدة لكل وكيل

`runtime/agent/AgentSnapshot.kt`:

| الحقل | المعنى |
|---|---|
| `agent` | هوية الوكيل (`LocalAgent`) |
| `lifecycle` | مفردة المرحلة 2 (`RuntimeLifecycle`) |
| `health` | `RuntimeHealth` |
| `auth` | `AgentAuthState` |
| `version` · `error` | ما يعرفه الوكيل عن نفسه |
| `capabilities` | `AgentCapabilities` (الجدول أعلاه) |
| `busy` · `ready` · `usable` | قواعد مشتقة، مكتوبة **مرة واحدة** |

**قاعدة `ready` الموحَّدة:** «مثبَّت» (Running/Installed/Stopped) **و** إن كان الوكيل يحتاج تسجيل دخول فهو مُسجَّل. هذه هي القاعدة نفسها التي كان يعيد كتابتها كل من شاشات الوكلاء الأربع.

### 2.3 المدير الذي لا يملك حالة

`runtime/agent/AgentManager.kt`:

```kotlin
val snapshots: StateFlow<List<AgentSnapshot>>   // كل الوكلاء
fun snapshot(agent): AgentSnapshot              // وكيل واحد (Unknown إن لم يُعرف)
fun knows(agent): Boolean                       // الاكتشاف
fun capabilities(agent): AgentCapabilities
suspend fun refreshAll()                        // إعادة قراءة الجميع
fun snapshotFlow(agent): Flow<AgentSnapshot>    // للواجهة
```

**لا يملك حالة خاصة به**: يقرأ من `AgentStatusSource` لكل وكيل (محوّلات رقيقة حول الوحدّات القائمة في `runtime/local/AgentStatusSources.kt`)، فلا توجد نسخة ثانية من الحقيقة.

### 2.4 إزالة التكرار من الواجهة

| قبل | بعد |
|---|---|
| `CodexUiState.statusLabel()` · `ClaudeCodeUiState.statusLabel()` + `isReady()` · `AntigravityControllerState.statusLabel()` + `isReady()` | **دالة واحدة** `AgentSnapshot.statusLabel()` + `ready` في `feature/settings/AgentStatusPresentation.kt` |
| شاشات الوكلاء تقرأ من أربعة أنواع حالة مختلفة | الشاشات تقرأ `snapshot` الموحَّد (يُجمع في مخطط التنقّل)، وتُبقي حالتها الخاصة للعناصر التفصيلية فقط |

**استثناء مقصود وموثَّق:** شاشة OpenCode تُبقي نصّ حالتها الخاص (`state.status.displayName()`) لأنها تحمل تفصيلًا لا يوجد في التسمية الموحَّدة (خطوة التثبيت الحالية، المنفذ). أما علم «يعمل» فقد أصبح من اللقطة الموحَّدة (`snapshot.usable`) وهو مطابق للسلوك السابق (running فقط).

### 2.5 نقل `WorkspaceRef`/`WorkspaceFolders` إلى `core`

آخر استثناء «طبقة أعلى من طبقتها» أُزيل: `runtime → feature` صار **0**. النقل تم إلى `core/workspace/`، والناقل والاختبار تحرّكا معه.

---

## 3. متطلبات المرحلة: ما تحقق وما بقي

| المطلوب | الحالة | الدليل / السبب |
|---|---|---|
| اكتشاف الوكلاء | ✅ | `AgentManager.agents` من المصادر المسجّلة؛ `LocalAgent` هو سجل الهوية |
| معرفة الحالة | ✅ | `AgentSnapshot` (lifecycle + auth + version + error) |
| تشغيل / إيقاف / إعادة تشغيل | 🟡 **حسب الوكيل** | OpenCode فقط (خادم دائم) عبر `LocalRuntimeManager` — والثلاثة الآخرون **لا يملكون خادمًا** فتشغيلهم = بدء دور. `AgentCapabilities.serverLifecycle` يعلن الفرق، ولم تُختلق واجهة `start()` وهمية |
| معرفة الأدوات المتاحة | ✅ | `AgentCapabilities`: install · update · signIn · permissionModes · systemPrompts · mcp · serverLifecycle |
| معرفة الصلاحيات المطلوبة | ✅ | `permissionModes` لكل وكيل (Claude/Antigravity) + سياسة صلاحيات أندرويد كاملة في البيان |
| إدارة الجلسات | ✅ | عبر `OpenCodeBackend` لكل هدف (المرحلة 2) |
| التعامل مع الأخطاء | ✅ | `AgentSnapshot.error` + `RuntimeLifecycle.Failed(reason)` + `AgentAuthState.Failed(message, transcript)` |
| تسجيل الأحداث | 🟡 جزئي | سجل نشاط الران‑تايم (`RuntimeActivityRepository`) + تدقيق وكيل الجهاز؛ **لا سجل موحَّد لعمليات الوكلاء** — مؤجَّل إلى Phase 5 (مركز الصلاحيات والتدقيق) |
| ألا يكون لكل وكيل نظام lifecycle خاص بلا سبب | ✅ | مفردة `RuntimeLifecycle` + `AgentAuthState` + لقطة واحدة؛ الاختلافات الباقية معلنة في `AgentCapabilities` |

---

## 4. ما لم يُنفَّذ (مقصود)

| البند | السبب | المرحلة |
|---|---|---|
| توحيد النماذج **نفسها** (`ClaudeInstallStatus` … إلخ) في نوع واحد | التوحيد يتم في المُترجِم؛ دمج الأنواع يمسّ كل وحدة تحكم وشاشاتها بلا فائدة سلوكية الآن | عند أول حاجة فعلية (Phase 6+) |
| واجهة `start()/stop()` موحدة | لا وجود لخادم في ثلاثة وكلاء → ستكون واجهة كاذبة | — (قرار ثابت) |
| سجل أحداث موحَّد لكل الوكلاء | يحتاج عقد تدقيق واحد | **Phase 5** |
| `Codex.update()` | يحتاج مسار إصدارات Codex؛ `CodexReleaseClient` موجود لكن لا `update()` في وحدته | عند الطلب |

---

## 5. الاختبارات

| الملف | العدد | يثبت |
|---|---|---|
| `app/src/test/java/com/mushrea/code/runtime/agent/AgentManagerTest.kt` | 16 اختبارًا | الاكتشاف، الحالة المجهولة، `ready` مع/بدون تسجيل دخول، `busy`، `usable` للخادم وحده، الأخطاء، القدرات لكل وكيل، التجميع والانفصال بين الوكلاء، `refreshAll`، وخرائط التحويل من حالات Claude/Antigravity/Codex |

**Cannot Verify — Environment Limitation:** لا يمكن اختبار تشغيل وكيل فعلي (تثبيت/دخول/دور) في هذه البيئة: يحتاج جهاز أندرويد (PRoot) + شبكة + حساب. المطلوب للتحقق: جهاز arm64/x86_64 أو محاكي مع KVM، وشبكة غير محجوبة لتنزيلات الران‑تايم.
