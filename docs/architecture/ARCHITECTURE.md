# ARCHITECTURE.md — معمارية Mushrea Code

> **المرحلة 1 — Architecture Hardening.** هذه الوثيقة تصف **الواقع الحالي للكود** (AS-IS) بالأدلة، ثم تحدد **العقود المستهدفة** (TO-BE) مرتّبةً على مراحل التطوير القادمة، وتُثبّت **قواعد الطبقات** المفروضة آليًا.
> **قاعدة الوثيقة:** لا يُكتب هنا شيء غير مُشتق من الكود، وكل رقم مذكور قابل لإعادة الإنتاج بالأوامر المذكورة في نهاية الملف.
> **المراجع:** `docs/PROJECT_ANALYSIS_AR.md` (المرحلة 1) · `docs/PROJECT_VERIFICATION_REPORT.md` (المرحلة 2) · `docs/development/BASELINE.md` (المرحلة 0).

---

## 1. نظرة عامة

Mushrea Code وحدة Gradle واحدة (`:app`) تضمّ كل الكود في 5 طبقات عليا + جذر تركيب:

```
core  <  data  <  runtime  <  device  <  feature  <  ui
```

القراءة: كل طبقة يجوز أن تعتمد على ما هو **أسفلها**، ولا يجوز أن تعتمد على ما هو **أعلاها**. الرسم الكامل للسلوك كما طلبت (UI → Feature → Domain → Runtime → Agent → Tool → Permission → Execution → Result → Audit) **ليس تتابعًا خطيًا في هذا الكود**، بل ثلاثة مسارات تنفيذ تمرّ عبر الطبقات نفسها. القسم 4 يوضّح ذلك موضعًا بموضع.

| الطبقة | الحزم | المسؤولية | الملفات | الأسطر |
|---|---|---|---|---|
| `core` | api, diagnostics, lifecycle, locale, notification, runtime, security, storage, util | نماذج وعقود وخدمات مؤسِّسة لا تعرف شيئًا عن الواجهة | 28 | 3,820 |
| `data` | connection, local, repository, schedule, settings | التخزين والإعدادات والمستودعات | 18 | 3,024 |
| `runtime` | (الجذر) + `runtime/local` + `runtime/remote` | الوكلاء وتشغيلهم وبروتوكولاتهم | 92 | 18,218 |
| `device` | الجذر + bluetooth, call, mirror, network, payload, remote, ssh, termux, usb, usbhub | التحكم بالجهاز والعتاد والأدوات التنفيذية | 71 | 13,616 |
| `feature` | activity, assistant, browser, chat, onboarding, schedule, settings, share, support, wakeword, widget, workspace | الشاشات ومنطق العرض | 119 | 31,653 |
| `ui` | الجذر + components, navigation, theme | الهيكل العام والتنقّل ومجموعة العرض المشتركة | 21 | 4,606 |
| `di` · `startup` · الجذر | — | جذر التركيب (Composition Root): ربط الطبقات فقط | 9 | 1,457 |

**استثناء مقصود:** `ui.theme` و`ui.components` و`ui.ViewModelFactory` و`ui.runtimeAgentIcon` و`ui.runtimeTargetLabel` تُعدّ **أساس عرض** يجوز استيرادها من أي طبقة، لأن الشاشات في `feature` ترسم بها فعليًا. أما **هيكل التطبيق** (`ui.MushreaCodeApp`, `ui.AppDrawerContent`, `ui.navigation.*`) فمحجوز لجذر التركيب.

**جذر التركيب** (ملفات `com.mushrea.code` الجذرية، `di/AppModule.kt`, `di/ViewModelModule.kt`, `startup/`) مستثنى من القواعد: وظيفته الوحيدة أن يعرف كل الطبقات.

---

## 2. الأدلة: مصفوفة الاعتماديات المقيسة

المصفوفة التالية مستخرجة آليًا من **359 ملف إنتاج** (أرقامها = عدد الاستيرادات):

```
            core  data runtime device feature  ui   di startup (root)
core           9     1       2      0       0    0    0   0      0
data          16     2       0      0       1    0    0   0      0
runtime      258     3      67      0       1    0    0   0      0
device         7     0       1     46       3    2    0   0      0
feature      122    37     115      4      37   58    0   0      0
ui             3     2      15      1      57   30    0   0      0
di             2     6      21      1       5    0    0   0      0
startup        0     0       4      0       0    0    0   0      0
(root)        17     7      42      1      10    2    2   4      0
```
> الأرقام محدَّثة بعد المرحلة 2 (كانت `data → runtime = 10` و`core → feature = 1`).

**قراءة المصفوفة:**

- الاتجاه السائد سليم: `feature → runtime/core/data` (111/119/44)، `runtime → core` (235)، `device → core` (7).
- **الالتحام الأخطر (`data ⇄ runtime`) — ✅ فُكّ في المرحلة 2.** كان 10 استيرادات صاعدة من `data` إلى `runtime` مقابل 6 نازلة. الحل: نقل `ConnectionProfile` + واجهتي `RuntimeConnectionStore`/`AdbConnectionStore` إلى `core/connection`، ونقل المستودعات الثلاثة التي تخدم الران‑تايم (`RuntimeActivityRepository`, `RuntimeCatalogRepository`, `SessionAutoArchiver`) إلى `runtime/`. النتيجة المقيسة: **`data → runtime = 0`** و`runtime → data = 3` (اتجاه واحد مسموح).
- خطوط صاعدة صغيرة أخرى (كلها موثّقة كاستثناءات مؤقتة في §3).

---

## 3. المخالفات المكتشفة وقراراتها

### 3.1 ما أُصلح في هذه المرحلة (نقل بلا تغيير سلوكي)

| # | الاعتماد | الملفات | الإصلاح |
|---|---|---|---|
| 1 | `core/api/GitHubApiClient` → `feature/workspace/GitHubReference` | 1 مصدر + 2 مستهلكين | نُقل النموذج إلى `core/api/GitHubReference.kt` (نموذج مشترك بحق، لا يخصّ ميزة) |
| 2 | `data/connection/SecureSettingsRepository` → `feature/wakeword/WakeWordGrammar` | 2 مصدر | نُقل إلى `core/voice/WakeWordGrammar.kt` |
| 3 | `data/settings/AppPreferencesRepository` → `feature/wakeword/WakeWordGrammar` | — | (نفس النقل أعلاه) |

النتيجة المقيسة: `core → feature` = **0** (كان 1)، و`data → feature` = **1** (كان 3).

**نقل جُرِّب ثم تُرِجِع بوعي:** نقل `WorkspaceFolders` إلى `core/workspace` ثبت أنه **يخلق** اعتمادًا صاعدًا جديدًا `core → runtime` لأنه يستخدم `runtime.WorkspaceRef`، فتم التراجع عنه وإدراجه في المرحلة 3 مع نقل `WorkspaceRef` نفسه. هذا مثبَّت في أداة الفحص كاستثناء مؤقّت بدل أن يبقى مخفيًا.

### 3.2 الاستثناءات المُثبَّتة (5 استيرادات في 4 ملفات بعد المرحلة 2) — لكل منها مرحلة إزالة

| من → إلى | الملفات | السبب | المرحلة |
|---|---|---|---|
| `core → data` | `AppLanguage` | اللغة تُقرأ من `SecureSettingsRepository` مباشرة بدل منفذ في `core` | Phase 13 |
| `core → runtime` | `PermissionActionReceiver` · `RuntimeNotificationHelper` | نموذج قرار الصلاحية `PermissionResponse` يجب أن ينتقل إلى مركز الصلاحيات | Phase 5 |
| `data → feature` | `AppPreferencesRepository` | `TtsTuning` يبني `TTSProviderConfig` الذي ما زال في `feature/assistant` | Phase 12 |
| `device → feature` | `CallAgentService` | وكيل المكالمات يقود `SpeechRecognizerManager`/`TTSManager` مباشرة بدل منفذ صوتي | Phase 12 |
| `runtime → feature` | `ClaudeWorkspaceFiles` | `WorkspaceFolders` يجب أن ينتقل إلى `core/workspace` مع `WorkspaceRef` | Phase 3 |

**سياسة:** لا استثناء جديد بدون (سبب + مرحلة إزالة) داخل `scripts/check_architecture.py`. القائمة مصمَّمة لـ**تتقلّص فقط**.

---

## 4. المسارات الثلاثة (من الفعل إلى السجل)

### 4.1 مسار المحادثة/الوكيل

```
Compose Screen (feature/chat/*)  →  ChatViewModel (2,767 سطرًا)
   → RuntimeRegistry (اختيار الهدف)  →  RuntimeTarget : OpenCodeBackend
   → LocalOpenCodeBackend (HTTP 127.0.0.1:4097) | RemoteOpenCodeBackend (SSE)
     | ClaudeCodeRuntime | AntigravityRuntime | CodexRuntime (process/stream-json)
   → OpenCodeEventParser / ClaudeStreamJsonParser / AntigravityStreamJsonParser / CodexItemParser
   → ChatUiState (StreamingText, ToolCall, PatchDiff, Permission, Question)
- الموافقات:  PermissionActionReceiver → PermissionResponse → الران‑تايم
- الجلسات:    SessionDatabase (Room — غير مستخدم) / بيانات الران‑تايم / EncryptedSharedPreferences
```

**الملاحظة المعمارية:** لا توجد طبقة `domain` مستقلة؛ دورها تؤدّيه `data/repository` + `core/api` (نماذج). هذا مقصود ومسجَّل هنا كواقع، وليس نقصًا بحد ذاته.

### 4.2 مسار أدوات الجهاز (الأهم أمنيًا)

```
الوكيل في الضيف → خادم MCP بايثون (assets/scripts/mushreacode-device-mcp.py، 88 أداة)
   → ملف القناة:  <workspace>/.mushrea-code/device-command.json
   → DeviceAgentBridge (خادم الملفات + الحلقة):
        1) فحص الإيقاف الطارئ        store.consumeStopRequest()
        2) بوابة الوجود              command.action !in DeviceActionFirewall.ALL_ACTIONS → رفض
        3) وضع القراءة فقط           READ_ONLY_ACTIONS
        4) جدار الحماية              firewall.levelFor(action) + overrides + تصعيد النقر
        5) التأكيد                   awaitConfirmation(150 ث) عند != AUTO
        6) التنفيذ                   execute(...) → 90 فرعًا (منها callExecutor المجمّع)
        7) النتيجة                   ملف device-result.json + معرّف مطابق
   → Audit:  DeviceAuditLog (تصدير JSON) + سجل النشاط في DeviceAgentStore
   → الأوامر يجلبها: MushreaCodeAccessibilityService (التي تستضيف الجسر)
```

**المشاكل المؤكَّدة في هذا المسار (من تقرير المرحلة 2، لم تُلمس في هذه المرحلة):**

1. **13 إجراءً حسّاسًا** (USB shell/pull/push/install، SSH exec/download/upload، TCP shell، serial، tcpip…) تقع في فرع `else -> ConfirmationLevel.AUTO` فتُنفَّذ بلا تأكيد، بينما توثيق المنفّذات نفسها يصفها `(CONFIRM)`. التصنيف الفعلي: **70 AUTO / 19 CONFIRM** لا 57/32.
2. **لا بوابة تأكيد ثانية** في `UsbExecutor`/`SshExecutor` (التعليقات قديمة).
3. **دورة «رؤية → تنفيذ» بلا تحقق نتيجة**: التنفيذ يكتب نجاحًا/خطأً، لكن لا خطوة تحقق مستقلة تتحقق من أن الأثر المطلوب وقع فعلًا.

### 4.3 مسار الجدولة

```
ScheduleRepository (مشفَّر) → AlarmManager → ScheduleExecutionService (FGS specialUse)
   → RuntimeRegistry → الران‑تايم المختار → جلسة/موجه
   → ScheduleRunWatchdog + ScheduleRetryPolicy + إشعارات
   → ScheduleRunStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }
```

### 4.4 مسار الصوت

```
WakeWordService (FGS microphone) → VoskWakeWordDetector (نموذج Vosk)
   → MushreaCodeVoiceSession (ASR/TTS/BargeIn) → التطبيق/الوكيل
```

### 4.5 مسارات مساندة

| المسار | السلسلة |
|---|---|
| USB/ADB | `device/usb/*` + `usbhub/*` → ADB/Serial/Fastboot/MTP/HID/GUI — بلا بوابة تأكيد ثانية |
| المرآة | `device/mirror/*` → ADB → `scrcpy-server-4.0` (داخل assets) → فك ترميز → Surface |
| الشبكة | `device/network`, `device/ssh`, `device/remote` → OkHttp/sshj/smbj/commons-net/NsdManager |
| OTA | `device/payload` → `PayloadArchive` → فحص ترويسات (بلا تحقق AVB) → `PayloadGuard` |

---

## 5. القواعد المفروضة آليًا

**الأداة:** `scripts/check_architecture.py` (بايثون قياسي، بلا اعتماديات).

```bash
python3 scripts/check_architecture.py           # يفشل عند أي مخالفة غير مصرَّح بها
python3 scripts/check_architecture.py --matrix  # + طباعة المصفوفة كاملة
```

**التشغيل في CI:** أُضيفت خطوة **Architecture layer rules** في مهمة `static-analysis` ضمن `.github/workflows/android.yml`، قبل عمل Gradle مباشرة.

**اختبار ذاتي مُنفَّذ:** حُقن استيراد مخالف (`core → feature`) في نسخة معزولة، والأداة كشفته وأعادت `exit=1` — أي أن الفرض فعّال لا شكلي.

**القواعد:**

| # | القاعدة |
|---|---|
| L1 | `core` لا يعتمد على `data` أو `runtime` أو `device` أو `feature` أو `ui` |
| L2 | `data` لا يعتمد على `runtime` أو `device` أو `feature` أو `ui` |
| L3 | `runtime` لا يعتمد على `device` أو `feature` أو `ui` |
| L4 | `device` لا يعتمد على `feature` أو `ui` |
| L5 | `feature` لا يعتمد على هيكل `ui` (ويجوز له أساس العرض) |
| L6 | كل استثناء مذكور باسم الملف والسبب والمرحلة، والقائمة تتقلّص فقط |

---

## 6. العقود المستهدفة (رسم للمراحل القادمة — بلا كود جديد الآن)

هذه العقود تُنفَّذ في المراحل 2–5، وتُكتب هنا حتى تكون هي المرجع عند التنفيذ (وتمنع إنشاء نسخ مكررة).

### 6.1 دورة حياة الران‑تايم (المرحلة 2 — ✅ نُفِّذ)

**ما نُفِّذ:** مفردات واحدة `com.mushrea.code.core.runtime.RuntimeLifecycle` (تسع حالات: Unknown · Available · Installing · Installed · Starting · Running · Stopping · Stopped · Failed + `busy`/`usable`) مع `RuntimeHealth` و`RuntimeSnapshot`، و`RuntimeLifecycleMapper` (دوال نقية) يترجم النماذج الأربعة القائمة إليها، و`RuntimeTarget.lifecycle: Flow<RuntimeLifecycle>` (تنفيذ افتراضي فلا ينكسر أي هدف قائم)، و`OpenCodeAgentUiState.lifecycle` كمستهلك حقيقي في شاشة إعدادات الوكيل. اختبارات: `RuntimeLifecycleMapperTest` (14 اختبارًا) + اختبار دورة الحياة على هدف وهمي داخل `RuntimeRegistryTest`.
**ما تبقّى في المراحل القادمة:** دمج النماذج الأربعة نفسها في نموذج واحد (يحل محل `ClaudeInstallStatus`/`AntigravityInstallStatus`/`CodexInstallStatus`)، وإضافة حالات `Stopping`/`Available` الفعلية لكل وكيل.

**الواقع قبل التوحيد:** كانت الحالة موزَّعة على **أربعة نماذج متوازية**:

| النموذج | الحالات | الموقع |
|---|---|---|
| `LocalRuntimeStatus` | NotInstalled · Installing · Stopped · Starting · Updating · Ready · Broken · UnsupportedAbi | `runtime/LocalRuntimeStatus.kt` |
| `RuntimeState` | Disconnected · Connecting · Connected · Unavailable · Failed | `runtime/RuntimeTarget.kt` |
| `ClaudeInstallStatus` | Idle · Installing · Ready · Failed | `runtime/local/ClaudeCodeController.kt` |
| `AntigravityInstallStatus` | Idle · Installing · Ready · Failed | `runtime/local/AntigravityController.kt` |
| `CodexInstallStatus` | Idle · Installing(progress, step) · Failed | `runtime/local/CodexController.kt` |

**العقد الموحَّد المقترح:** دورة واحدة لكل وكيل:

```
Unknown → Available → Installing → Installed → Starting → Running → Stopping → Stopped → Failed
                                                 ↖_____________ مراقبة الصحة ______________↙
```

مع حقل واحد للصحة (`health`) وحقل واحد للإصدار، وتقليل الأنواع الأربعة إلى نموذج واحد + مُحوِّل عرضي (adapter) لكل وكيل.

### 6.2 دورة حياة الوكيل (المرحلة 3)

**الواقع اليوم:** أربعة وكلاء في `LocalAgent` (`OPEN_CODE`, `CLAUDE_CODE`, `ANTIGRAVITY`, `CODEX`) ولكل واحد: Runtime + Target + Controller + Installer + Launcher + Parser — أي ~84 ملفًا في `runtime/local` بلا واجهة مشتركة.

**العقد المقترح:** واجهة واحدة:

```kotlin
interface AgentRuntime {
    val id: String; val displayName: String
    val state: StateFlow<AgentState>       // 6.1
    val capabilities: RuntimeCapabilities  // موجود فعلًا ويُعاد استخدامه
    val tools: List<ToolId>                // من سجل الأدوات (6.3)
    suspend fun start(); suspend fun stop(); suspend fun restart()
    suspend fun session(): AgentSession
    fun logs(): Flow<String>
}
```

**يُعاد استخدام الموجود:** `RuntimeCapabilities` و`OpenCodeBackend` و`RuntimeWorkTracker` تبقى كما هي؛ المطلوب توحيد *الهيكل* حولها لا استبدالها.

### 6.3 سجل الأدوات (المرحلة 4)

**الواقع اليوم:** تعريف الأداة موزَّع على أربعة أماكن لا رابط آلي بينها:

| المكان | ما فيه | العدد |
|---|---|---|
| `assets/scripts/mushreacode-*.py` | `inputSchema` ووصف الأداة | 88 + 8 + 7 |
| `device/DeviceActionFirewall.kt` | `ALL_ACTIONS` (89) + التصنيف | 89 |
| `device/DeviceAgentBridge.kt` | فروع التنفيذ | 90 |
| `assets/mushrea-code-agent-context.md` | إرشاد الوكيل النصّي | — |

**العقد المقترح (يُشتق مرة واحدة ويُولَّد منه MCP والجدار والجسر):**

```
Tool {
  id, name, description,
  inputSchema, outputSchema,
  risk: LOW | MEDIUM | HIGH | CRITICAL,
  requiredPermissions: Set<Permission>,
  confirmation: AUTO | CONFIRM | STRONG,
  timeoutMillis, cancellable: Bool,
  auditPolicy: NONE | SUMMARY | FULL,
  availability: (device, capability) -> Bool
}
```

**مثالان ببيانات حقيقية من الكود اليوم:**

| | `device.tap` | `device.delete_file` |
|---|---|---|
| Risk (مقترح) | LOW (مع تصعيد نصّي) | HIGH |
| Permission | ACCESSIBILITY | (وصول للملفات + تأكيد مستخدم) |
| Confirmation اليوم | AUTO، ويصعد إلى CONFIRM عند لمس عناصر حسّاسة (`Pay now`, «احذف الملف») | CONFIRM |
| Audit | YES | YES |

### 6.4 مركز الصلاحيات والأمان (المرحلة 5)

**التدفق المستهدف مقابل الواقع:**

| الخطوة | أين تُؤدَّى اليوم | الفجوة |
|---|---|---|
| من طلب العملية | الوكيل عبر MCP | لا هوية طالب؛ الوكيل مجهول الهوية في السجل |
| ما العملية | `DeviceCommandCodec` | ✅ |
| لماذا (سياق الطلب) | غير موجود | ❌ |
| الصلاحية المطلوبة | غير مُصرَّحة كحقل | ❌ (الصلاحية معلومة ضمنًا من إعداد التطبيق) |
| مستوى الخطورة | `ConfirmationLevel` (3 مستويات) | 🟡 لا مستوى خطورة مستقل |
| هل تحتاج تأكيدًا | `levelFor` + overrides | 🟡 مع ثغرة الفرع `else` (13 إجراءً) |
| موافقة المستخدم | `awaitConfirmation` + إشعار | ✅ |
| هل هناك سياسة تمنع | `READ_ONLY_ACTIONS`, `TermuxCommandPolicy`, `PayloadGuard`, `StopPhrases` | 🟡 موزَّعة على 4 مواضع |
| التنفيذ | `DeviceAgentBridge.execute` | ✅ |
| التحقق من النتيجة | غير مستقل | ❌ (المرحلة 6) |
| سجل التدقيق | `DeviceAuditLog` | 🟡 يغطي وكيل الجهاز فقط |

**المطلوب:** طبقة واحدة تُسأل قبل التنفيذ: `decide(actor, tool, args, context) → Allow | Confirm | Deny(reason)`، وتُسجَّل نتيجتها؛ وترحيل الثغرات الثلاث المؤكَّدة إليها.

### 6.5 عقد التدقيق (المرحلة 5)

اليوم: `DeviceAuditLog.build()` يُصدر مستند JSON يحوي وقت التصدير + مفاتيح السلامة (وضع القراءة فقط، إقرار المخاطر، تجاوزات الجدار) + سجل النشاط. **الناقص:** لا تدقيق موحَّد لأحداث الوكلاء والجدولة والشبكة. العقد المقترح: سجل واحد بمخطّط ثابت (`actor, tool, paramsDigest, decision, result, startedAt, endedAt, verifyOutcome`) مع تنقيح إلزامي للأسرار (`SecretRedaction` موجود ويُعاد استخدامه).

---

## 7. ما يجب أن يكون عليه التحقق بعد التنفيذ (المرحلة 6)

اليوم لا توجد خطوة تحقق مستقلة لمعظم الأدوات: النتيجة تعني «أُرسل الأمر» لا «وقع الأثر». المطلوب لكل أداة تغيّر الحالة: `verify` صريح (مثال: بعد `delete_file` يُعاد فحص وجود المسار، وبعد `tap` يُقرأ العنصر/الشاشة للتأكد من التغيّر، وبعد `usb_push` يُقارن الحجم/البصمة). الأدوات التي لا تملك تحققًا صريحًا تُصرّح بذلك في نتيجتها بدل ادّعاء النجاح.

---

## 8. الاختبارات لكل طبقة (الواقع والخطة)

| الطبقة | ملفات اختبار اليوم | الفجوة الأهم |
|---|---|---|
| `runtime` | **73** | لا اختبار تكامل بين الوكلاء الأربعة |
| `device` | **20** (منها `DeviceActionFirewallTest`, `DeviceCommandCodecTest`, `StopPhrasesTest`, ومجلدات `usb/`, `mirror/`, `call/`, `payload/`, `termux/`, `usbhub/`) | الـ13 إجراءً غير مغطّاة، ولا اختبارات عقد كاملة لـUSB/SSH |
| `feature` | **55** | اختبارات الأجهزة (23) لا تُنفَّذ في أي سير عمل |
| `data` | **8** | لا اختبار لطبقة Room (الميتة) |
| `core` | **16** | — |
| `ui` | **3** | — |
| أدوات MCP (بايثون) | لا اختبار داخل المستودع | تُختبر يدويًا خارج المستودع (خارج نطاق CI) |

**الخطة:** لكل عقد جديد في 6.1–6.5 اختبار سلوكي حقيقي (لا عدّ اختبارات)، + فحص معماري يمنع الانحدار (منفَّذ).

---

## 9. خريطة المراحل → الملفات

| المرحلة | الملفات/الحزم المستهدفة |
|---|---|
| 2 — Runtime Manager | `runtime/LocalRuntimeStatus.kt` · `runtime/RuntimeTarget.kt` · `runtime/local/*Controller.kt` · `data/repository/Runtime*Repository.kt` (فك دورة data⇄runtime) |
| 3 — Agent Manager | `runtime/LocalAgent.kt` · `runtime/local/{Claude,Antigravity,Codex}*` · `runtime/OpenCodeBackend.kt` |
| 4 — Tool Registry | `assets/scripts/mushreacode-*-mcp.py` · `device/DeviceActionFirewall.kt` · `device/DeviceAgentBridge.kt` · `assets/mushrea-code-agent-context.md` |
| 5 — Permission & Safety | `device/DeviceActionFirewall.kt` (فرع `else`) · `runtime/local/ClaudePermissionBridge.kt` + الخطاف · `device/DeviceAuditLog.kt` · `device/termux/TermuxCommandPolicy.kt` |
| 6 — Device Agent | `device/*` (تحقق بعد التنفيذ) |
| 9 — Terminal | `feature/workspace/TerminalTabPlaceholder` + الطرفية الحقيقية |
| 12 — Voice | `feature/assistant/{TTSProvider,TtsTuning,SpeechResult}` → `core/voice` |
| 13 — Network/Remote | `core/locale/AppLanguage.kt` (منفذ إعدادات) · `device/{ssh,network,remote}` |
| 15 — Cleanup | Room layer · `ForgeClient` · `eddsa` · `xz` المكرّرة · `TerminalTabPlaceholder` |

---

## 10. حدود ملزمة (لا تُخترق في أي مرحلة)

1. **لا تغيير للتقنية الأساسية:** Kotlin/Compose/Koin/OkHttp/Gradle كما هي.
2. **لا أسرار في الكود:** الإعدادات تمرّ عبر `EncryptedSharedPreferences`/Gradle properties كما هو معمول.
3. **العمليات الخطرة:** لا تنفيذ لأي منها بلا قرار صريح من مركز الصلاحيات (6.4). لا تفليش/محو/تصفير أبدًا.
4. **لا حذف** لأي كود إلا بعد إثبات كونه غير مستخدم (بحث + انعكاس + سكربتات بناء + اختبارات).
5. **لا إضافة ميزة موجودة:** تُكمَل الموجود بدل إنشاء نسخة جديدة (ينطبق بشكل خاص على Terminal/Browser/Audio).
6. **كل مرحلة:** تنفيذ → بناء → اختبار → مراجعة → توثيق، ولا انتقال مع خطأ حرج.

---

### ملحق: إعادة إنتاج أرقام هذه الوثيقة

```bash
python3 scripts/check_architecture.py --matrix     # المصفوفة + المخالفات + الاستثناءات
find app/src/main/java/com/mushrea/code -name '*.kt' | wc -l           # 359 ملف إنتاج
grep -rn "else -> ConfirmationLevel.AUTO" app/src/main/java/com/mushrea/code/device/DeviceActionFirewall.kt
grep -c 'def tool_' app/src/main/assets/scripts/mushreacode-device-mcp.py
```

> **حالة المرحلة 1:** مكتملة — نقلان بلا تغيير سلوكي، أداة فرض مع اختبار ذاتي، وربط بـ CI، وتوثيق معمارية كامل. التفاصيل في `docs/development/DEVELOPMENT_LOG.md`.
