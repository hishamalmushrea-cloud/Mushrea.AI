# PHASE1_AUDIT.md — تدقيق الأساس (Foundation Audit)

> **المرحلة 1 — تدقيق فقط.** لم يُعدَّل أي كود إنتاجي، ولم يُحذف أي ملف، ولم يُضَف أي نظام. المخرج: قرارات موثَّقة لكل منطقة أساسية + خطة تنفيذ صغيرة للمرحلة التالية.
> **تاريخ التدقيق:** 2026-10-02 · **الفرع:** `arena/01a0f971-mushrea-ai` · **HEAD:** `4a26bf8` (فوق `1f551b4`).
> **خط الأساس المرجعي:** `docs/development/BASELINE.md`.
> **قاعدة الوثيقة:** كل حكم هنا مصحوب بدليل من الكود (`مسار:سطر` أو نتيجة بحث قابل لإعادة الإنتاج). لا يُكتب «موجود» بمعنى «يعمل»؛ مراتب الحالة هي: Verified · Partially Verified · Code Present · Prototype · Broken · Unsupported · Dead/Unused · Cannot Verify.

---

## 0. ملخص القرارات في جدول واحد

| المنطقة | ما هو موجود فعلًا | القرار | السبب المختصر |
|---|---|---|---|
| **Runtime** — المفردات | `RuntimeLifecycle` · `RuntimeHealth` · `RuntimeLifecycleMapper` مستخدمة فعليًا | **يُبقى** | تحل مشكلة «أربعة نماذج حالة» فعلًا وتُترجم لا تستبدل |
| **Runtime** — الهدف/الاختيار | `RuntimeRegistry` + `RuntimeTarget` + `RuntimeCapabilities` | **يُبقى** | هو المدير الفعلي؛ لا حاجة لمكوّن جديد |
| **Runtime** — read model | `RuntimeSnapshot` مُعلَن وبلا أي مُنتِج أو مستهلك | **يُوحَّد** (يُنتَج فعلًا) أو يُعلَن ميتًا | عقد جيد معطَّل؛ القرار في §3.1 |
| **Runtime** — العمليات | `LocalRuntimeManager` + `LocalRuntimeServiceController` + 4 controllers | **يُبقى** | التغطية غير متماثلة بحكم الواقع (وكيل كخادم ≠ وكيل لكل دور) |
| **Agents** | `AgentManager` + `AgentSnapshot` + `AgentCapabilities` + 4 `AgentStatusSource` + `AgentAuthMapper` | **يُبقى — لا يُلمس** | التوحيد جرى فعلًا؛ لا abstraction جديدة |
| **Tools** — السجل | `DeviceToolCatalog` (90 مدخلًا) + `check_tool_catalog.py` + 11 اختبارًا | **يُبقى ويُكمَل** | مصدر حقيقة واحد مفروض آليًا |
| **Tools** — availability | `requires` (19 شرطًا) مُعلَن و**بلا أي مُقيِّم** | **يُكمَل (أولوية 1)** | أكبر فجوة قابلة للإغلاق بالبيانات الموجودة |
| **Tools** — verification | تحقق فعلي موجود في 3 مواضع فقط | **يُكمَل (أولوية 2)** | جوهر «لا تفترض النجاح» |
| **Security** — القرار | `ToolPermissionPolicy` نقطة واحدة + سبب إلزامي | **يُبقى** | يُغلق ثغرة `else → AUTO` عند الطبقة الصحيحة |
| **Security** — الطوارئ/التأكيد | `DeviceAgentStore` + `DeviceConfirmReceiver` + مهلة | **يُبقى** | مسار كامل ومسدود الإرسال (`exported=false`) |
| **Security** — FileProvider | `device_file_paths.xml` يعرض `.` لكل من `files-path`/`cache-path`/`external-path` | **يُضيَّق** | الحيطة الدفاعية (الوصول غير ممكن اليوم عبر الوكيل) |
| **Security** — certificate pinning | `pinSha256` مدعوم في العميل و**لا يوضع من أي واجهة** | **قرار مالك** | ميزة أمنية مُنفَّذة وغير موصولة |
| **التركيب/DI** | جذر تركيب فعلي في `MushreaCodeApplication` + Koin مُشغَّل بلا مستهلك | **يُوحَّد (حذف Koin لاحقًا)** | نظامان لنفس الوظيفة + انحراف مثبت بين الإعدادين |
| **التخزين** | `SecureSettingsRepository` (71 مفتاحًا) + `AppPreferencesRepository` + نسختا تخزين مشفَّرتين + مسودات عادية | **يُبقى** | التقسيم صحيح دلاليًا (أسرار/إعدادات/مسودات) |
| **التخزين** — Room | 4 ملفات + 3 اعتماديات + KSP، صفر مستهلك | **يُحذف لاحقًا** | دليل مكتمل على الموت (§6.3) |
| **الشبكة** | 14 عميل `OkHttpClient()` افتراضيًا + عملاء مخصوصون في 3 مواضع | **يُوحَّد بمِلفات مُعدَّة (profiles)** | تجمّع اتصالات وثريدات مكرَّر ومهل افتراضية غير مقصودة |
| **«Remote»** | نظامان: `runtime/remote` (OpenCode) و`device/remote` (نقل ملفات) | **لا يُدمجان** | وظيفتان مختلفتان تمامًا (§7.3) |
| **Terminal** | تنفيذ حقيقي (`TerminalScreen`+`TerminalViewModel`) + Placeholder في تبويب | **يُوحَّد** | Placeholder ليس نظامًا بديلًا بل واجهة ناقصة |
| **الكود الميت** | Room · ForgeClient · KeepAwakeHelper · VoiceActivityDetector · DragDropAttachHelper · TabletSettingsLayout · ConnectionStatus · RuntimeSnapshot | **يُحذف لاحقًا بعد تحصين الأدلة** | كل عنصر له سطر دليل في §8 |
| **`eddsa` / `slf4j-nop`** | مُعلَنان، و`eddsa` يُلمَّح إليه من قاعدة R8 لـsshj | **يُبقى (تصحيح تصنيف سابق)** | ليسا «غير مستخدمين» بالمعنى الآمن للحذف (§8.2) |

---

## 1. نطاق التدقيق وطريقته

- **قُرئت الملفات فعلًا** لا أسماؤها: كل حكم أدناه يشير إلى `ملف:سطر` أو إلى نتيجة أمر إعادة إنتاج.
- **الفحوص الثلاثة أُعيد تشغيلها** على هذه الشجرة: `check_architecture.py` ✅ · `check_tool_catalog.py` ✅ · `check_permission_hook.py` ✅.
- **التحقق من «غير المستخدم»** جرى بستة أساليب: مراجع Kotlin في `app/src` و`benchmark` و`build.gradle.kts` · ملفات XML/الأصول · سكربتات Python · اختبارات الوحدة/الأجهزة · grep على `docs/` لالتقاط ما يوثّق سلوكًا خفيًّا · فحص قواعد R8 (`proguard-rules.pro`) لالتقاط الاستخدام الانعكاسي.
- **لا بناء ولا تشغيل محليًا** (البيئة بلا JDK/Gradle/SDK/جهاز) — كل ما يلزم تشغيله على جهاز مصنَّف `Cannot Verify — Environment Limitation`.

---

## 2. Runtime

### 2.1 الموجود فعلًا (Verified بقراءة الكود)

| المكوّن | الملف | ما يقدّمه |
|---|---|---|
| `RuntimeRegistry` | `runtime/RuntimeRegistry.kt` | قائمة الأهداف، الاختيار، `selectIfUnset`, إضافة/حذف اتصال بعيد، `unusableProfileIds`, `targetFor(agent)` |
| `RuntimeTarget` | `runtime/RuntimeTarget.kt:88` | `type`, `state: StateFlow<RuntimeState>`, `lifecycle` (تنفيذ افتراضي عبر المapper), `agent`, `capabilities`, `connect/disconnect/listWorkspaces` |
| `RuntimeLifecycle` | `core/runtime/RuntimeLifecycle.kt` | 9 حالات + `busy`/`usable` + `RuntimeHealth` (4 قيم) |
| `RuntimeSnapshot` | نفس الملف | `id/name/agent/lifecycle/health/version/port/environment/error` — **بلا مُنتِج ولا مستهلك** |
| عمليات OpenCode | `runtime/local/LocalRuntimeManager.kt` | install/start/ensureRunning/stop/delete/reinstall/installFullDevelopmentTools/checkForUpdate/updateToLatest/rollback + `status/installedPort/isHealthy/lastExitCode/restartCount` |
| أوامر الخدمة | `runtime/local/LocalRuntimeService.kt:747` | 9 أفعال (`INSTALL_AND_START`, `START`, `STOP`, `RESTART`, `REINSTALL`, `UPDATE`, `ROLLBACK`, `DELETE`, `INSTALL_FULL_DEVELOPMENT_TOOLS`) |
| تشخيص/بيئة/سجلات | `runtime/local/LocalRuntimeDiagnostics.kt` | `LocalRuntimeDiagnosticsCollector.collect()` (مقاييس عملية، فحص أدوات، ذيل السجل) |
| وكلاء آخرون | `runtime/local/{Claude,Antigravity,Codex}Controller.kt` | install/update/refresh/تسجيل دخول/أوضاع صلاحيات؛ **بلا start/stop** |
| هدف بعيد | `runtime/remote/RemoteRuntimeTarget.kt` | عبر `OpenCodeApiClient` |

### 2.2 الفجوات (Evidence)

1. **`RuntimeSnapshot` عقد معطَّل:** `grep -rn "RuntimeSnapshot" --include='*.kt' app/src | grep -v core/runtime/RuntimeLifecycle.kt` ⇒ لا نتيجة (الوحيد ذكر توثيقي في `AgentSnapshot.kt:44`).
2. **لا سطح موحَّد للعمليات:** الشاشات تنادي `app.localRuntimeManager` / `app.localRuntimeController` / `app.claudeCodeController` / `app.antigravityController` / `app.codexController` مباشرة؛ مثال: `ui/MushreaCodeApp.kt` يفرّع على الوكيل المختار عند بدء التثبيت، و`WorkspaceViewModel` يستقبل `localRuntimeManager` + `localRuntimeController` + `claudeCode` (قابل لـnull).
3. **العمليات غير متماثلة بحكم الواقع** — وهذا ليس نقصًا: `AgentManager` يوثّق صراحةً أن Claude/Antigravity/Codex تعمل «عملية لكل دور» فلا يوجد `stop()` حقيقي لها. أي `start()/stop()` موحَّد سيكون تمثيلًا لا حقيقة.

### 2.3 القرار

- **يُبقى:** كل ما في §2.1. لا «Runtime Manager» جديد: `RuntimeRegistry` (اكتشاف/اختيار) + `AgentManager` (حالة الوكلاء) + controllers (عمليات) هي الإدارة الفعلية.
- **يُوحَّد:** عقد القراءة. القرار الموصى به: **إنتاج `RuntimeSnapshot` داخل `RuntimeRegistry`** من المصادر القائمة (لا نسخ حالة، بل `combine` فوق `targets` + `state` + `localRuntimeManager.status` + إصدارات الوكلاء)، واستخدامه في مواضع تحتاج «نظرة واحدة» (الدرج/شاشة الخادم). البديل (حذف النوع) أرخص لكنه يُهدر نموذجًا مبنيًا ومختبرًا فعليًا (`RuntimeLifecycleMapperTest` 16 اختبارًا). القرار النهائي مطروح في §9.
- **لا يُلمس:** منطق الاختيار في `RuntimeRegistry` · `RestartBackoff`/`IdleStopTracker`/Watchdog في `LocalRuntimeService` (منطق حسّاس ومُختبَر) · دلالات `RuntimeLifecycle`.

---

## 3. Agents

### 3.1 الموجود فعلًا

- `AgentManager` (`runtime/agent/AgentManager.kt`) يجمع 4 مصادر إلى `snapshots: StateFlow<List<AgentSnapshot>>`، **ولا يملك حالة** ولا يخترع قيمًا: ما لا يُبلَّغ عنه يصبح `UNKNOWN`/null.
- `AgentSnapshot` يحمل `lifecycle/health/auth/version/error/capabilities` + قواعد `ready`/`usable` معلنة في مكان واحد (`ready` = مثبَّت ومُسجَّل الدخول إن كان يتطلب تسجيلًا).
- `AgentCapabilities` تُقرأ من التوصيل الفعلي في `runtime/local/AgentStatusSources.kt` لكل وكيل (install/update/signIn/permissionModes/systemPrompts/mcp/serverLifecycle)، مع تعليقات تشير للملف الذي يثبت كل قيمة.
- تُستهلك فعليًا في `ui/navigation/SettingsNavGraph.kt:51` و`feature/settings/AgentSettingsScreen.kt` و`AgentStatusPresentation.kt`.

### 3.2 القرار

**لا يُلمس، ولا تُضاف abstraction.** شرط «معرفة الوكيل/حالته/capabilities/جلساته/أخطائه» محقَّق؛ و«تشغيله/إيقافه/إعادة تشغيله» محدود بـ`serverLifecycle` وهو قرار معماري صحيح ومُعلَن. الفجوة الوحيدة: **الأحداث** — تُقرأ اليوم عبر `RuntimeActivityRepository.events` للهدف المختار فقط، والوكلاء الأربعة يقدّمون `events()` (`ClaudeCodeTarget:478`, `AntigravityTarget:267`, `CodexTarget:249`)، أي أن القناة موحَّدة. لا إجراء.

---

## 4. Tools

### 4.1 الحقول الموجودة في `DeviceTool` (`device/tool/DeviceToolCatalog.kt:149`)

`id` · `mcpTools` · `purpose` · `family` (19) · `risk` (3) · `confirmation` (3) · `timeoutMillis` · `requires` (19 شرطًا) · `requiredParams` · `readOnly` · `configurable` · `transport`.

**قياسات مُتحقَّقة الآن:** 90 مدخلًا · 89 إجراءً · 88 أداة وكيل · `readOnly = true` ×39 · `confirmation = CONFIRM` ×32 · `STRONG` ×0 · `WORKSPACE_FILE` ×1 (`get_context`).

### 4.2 مقابل القائمة المطلوبة

| الحقل المطلوب | الحالة | الدليل |
|---|---|---|
| id / name / description | ✅ | `id`, `mcpTools`, `purpose` |
| input | ✅ جزئيًا | `requiredParams` + `inputSchema` في سكربت MCP (يفحصهما الثابت D) |
| output | ❌ | لا مخطط؛ التبرير موثَّق في `ARCHITECTURE.md §6.3` (لا مخطط صادق واحد) |
| risk / confirmation / timeout | ✅ | مفروضة باختبارات (11) |
| audit | ✅ | `AuditPolicy` (كلها `SUMMARY` اليوم) |
| permission | ❌ (مقصود) | الصلاحيات تُقرأ من نظام أندرويد وقت الطلب؛ إعلانها بلا مصدر حقيقة = تسمية |
| cancellation | ❌ (مقصود) | لا إلغاء منتصف النداء؛ الإيقاف بين الخطوات فقط — إعلان `true` سيكون ادّعاءً |
| **availability** | 🟡 **مُعلَنة وغير مُقيَّمة** | `requires` معرَّف لكل أداة، و`ToolRequirement` **بلا أي مستهلك خارج ملف السجل** |

**دليل فجوة availability:** `grep -rn "ToolRequirement\b" --include='*.kt' app/src/main | grep -v DeviceToolCatalog.kt` ⇒ **صفر نتيجة**. و`DeviceReadiness` (`device/DeviceReadiness.kt`) يفحص 5 أشياء بقائمة مكتوبة يدويًا (accessibility، قناة الأوامر، وجود rootfs، صلاحيات المكالمات، كون التطبيق dialer افتراضيًا) ولا يقرأ `requires` إطلاقًا.

### 4.3 فجوة التحقق بعد التنفيذ (Verification)

| الأداة | هل تتحقق النتيجة؟ | الدليل |
|---|---|---|
| `open_app` | ✅ (تفحّص المقدمة، وتعلن الفشل صراحة) | `DeviceAgentBridge.kt:519` — «could not verify foreground state» |
| `delete_file` | ✅ | `DeviceFileAgent.kt:111-112`: `if (!ok || file.exists()) throw` |
| `rename_file` | ✅ | `DeviceFileAgent.kt:127-128`: `if (!ok || !target.isFile) throw` |
| `tap`/`type_text`/`scroll`/`swipe`/`press` | ❌ | لا قراءة شاشة بعد التنفيذ |
| `move_file`/`copy_file` | ❌ جزئيًا | `moveOrCopy` يرمي عند فشل العملية لكن لا يقارن الحجم/الوجود بعدها |
| USB (`push`/`pull`/`install`) · SSH · MTP | ❌ | `UsbExecutor`/`SshExecutor` تُبلّغ نجاح الإرسال (`bytes`) دون تأكيد الهدف |
| `share_file` | ❌ (بحكم الواقع) | إتمام المشاركة في تطبيق آخر — لا يمكن تأكيده من التطبيق؛ يجب أن يُصرَّح بذلك |

**نتيجة التدقيق:** الوعد المعماري `execute → verify` (مكتوب في رأس `DeviceAgentBridge.kt:54`) مُحقَّق في **3 مواضع** فقط من ~90 إجراءً. هذا هو الفرق بين «الكود موجود» و«السلوك مؤكَّد».

### 4.4 القرار

- **يُبقى:** السجل الواحد + الفاحص (11 ثابتًا A–K) + الاختبارات. **لا Tool Registry ثانٍ.**
- **يُكمَل (أولوية 1):** تقييم `requires` إلى نتيجة `Ready | Blocked(reason)` عبر محرّك توافر يعيد استخدام الأدوات الموجودة للفحص (`DeviceReadiness`, `MushreaCodeAccessibilityService.isRunning`, `DeviceStorageAccess`, `PhoneCallController.capabilities`, `TermuxBridge.status`, `UsbDeviceAgent`, `BluetoothExecutor`…)، ويُعرض في شاشة وكيل الجهاز ويُرفَق بسبب الرفض عند تعذّر الأداة.
- **يُكمَل (أولوية 2):** عقد تحقق لكل إجراء: `verified: Boolean` + `verification: String` في نتيجة الأمر وسطر التدقيق، مع مُتحقِّقات حقيقية للمجموعة التي يمكن تأكيدها (ملفات/مقدمة التطبيق/حجم النقل/وجود حزمة)، و**إعلان صريح `verified=false` للبقية** بدل الادعاء. لا ادعاء نجاح.
- **لا يُضاف الآن:** `outputSchema` و`cancellable` و`requiredPermissions` — لأنها بلا مستهلك ولأن إعلانها سيكون تسمية لا عقدًا. تُبقى موثَّقة كفجوات مقصودة.

---

## 5. Security

### 5.1 الموجود فعلًا (سلسلة كاملة)

```
الوكيل → DeviceAgentBridge.process()
   ├── consumeStopRequest()                       (إيقاف طارئ بين الخطوات)
   ├── ToolPermissionPolicy.decide(action, tapLabel, actor)
   │      ├── معرّف غير معروف ⇒ Deny(reason)      (إغلاق فرع else القديم)
   │      ├── Read-Only ⇒ Deny ما لم يكن قارئًا (يتقدّم على تجاوز المستخدم)
   │      ├── الجدول + التجاوزات ⇒ AUTO | Confirm(level)
   │      └── تصعيد اللمس الحسّاس ⇒ CONFIRM
   ├── Confirm ⇒ DeviceAgentStore.requestConfirmation + إشعار + DeviceConfirmReceiver + مهلة 120s
   ├── التنفيذ عبر موزّعات الجسر (66 فرع إرسال)
   └── السجل: DeviceAuditLog (FORMAT_VERSION=2: actor/tool/risk/decision/reason)
```

- **الافتراضي محافظ:** `DeviceAgentStore.readOnlyMode()` يعيد `true` حين لا يوجد ملف (`device/DeviceAgentStore.kt:116`).
- **سياسات المعاملات في مواضعها:** `TermuxCommandPolicy` (default-deny: يرفض `flash/erase/stage/lock/unlock/…` وكل `oem …`)، `PayloadGuard` (تطابق codename)، `StopPhrases`، `CallPolicy`.
- **التأكيد والطوارئ مسدودان:** `DeviceConfirmReceiver` و`StopAgentReceiver` بـ`android:exported="false"` وإجراء مُعرَّف (`AndroidManifest.xml`).
- **أسرار:** لا مفاتيح في Git؛ `ConnectionProfile.toString()` يُخفي كلمة المرور والـpin؛ `SecretRedaction` قبل السجلات؛ `AdbKeys` مفتاح خاص في `filesDir`؛ TOFU لـSCP (`filesDir/remote/scp_known_hosts.json`).

### 5.2 الفجوات

1. **`device_file_paths.xml` أوسع من اللازم:** يعرض `external-path` + `external-files-path` + `files-path` + `cache-path` بمسار `.`. الوصول الفعلي **مسدود** لأن `DeviceFileAgent.resolveTargetFile()` يرفض أي مسار خارج `allowedRoots()` (تحقق بـ`canonicalFile`)، لكن العرض الأوسع لا يخدم شيئًا: `FileProviderUri.forFile` يُستخدَم من `DeviceFileAgent` فقط (مصدران: سطرا 86 و98)، والمسارات المسموحة هي جذور التخزين الخارجي فقط.
2. **`pinSha256` غير موصول:** مدعوم في `OpenCodeApiClient.defaultHttpClient` (يضيف `CertificatePinner`) ومعرَّف في `ConnectionProfile:17`، لكن لا واجهة ولا QR يضعه (`ConnectionQrPayload` لا يذكره) ⇒ الميزة موجودة ومعطَّلة عمليًا.
3. **التدقيق يغطي وكيل الجهاز وحده:** لا تدقيق موحَّد لعمليات الوكلاء/الجدولة/الشبكة (موثَّق كفجوة في `ARCHITECTURE.md §6.5`).
4. **`google-services.json` مُلتزَم** بقيم Placeholder (`project_number: 000000000000`، `api_key: PLACEHOLDER_API_KEY_REPLACE_ME`) — لا سر، لكن وجوده يُبقى شرطًا لبناء نكهة `github`.

### 5.3 القرار

- **يُبقى ولا يُلمس:** `ToolPermissionPolicy` · `DeviceActionFirewall` (تصنيف) · `TermuxCommandPolicy` · وضع القراءة فقط · مسار التأكيد · الإيقاف الطارئ · `DeviceAuditLog` · `SecretRedaction`/`CrashReportSanitizer` · بوابة `OpenCodeUrl` للـcleartext.
- **يُضيَّق (تغيير صغير):** `device_file_paths.xml` إلى `<external-path path="." />` فقط، مع تعليق يشرح أن وكيل الملفات يرفض كل مسار خارج جذور التخزين. لا يفقد شيئًا ويعيد الدفاع بالطبقات.
- **قرار مالك مطلوب:** `pinSha256` — إمّا توصيله بحقل في نموذج الاتصال (وحينها يعمل الحماية فعلًا)، أو توثيقه كـ**Unsupported** وإزالته من العقد لاحقًا. لا يُترك «موجودًا بالاسم».
- **مؤجَّل بصراحة:** توحيد التدقيق عبر الأنظمة (يحتاج تعريف مالك ومخطط تخزين) — يُقيَّم بعد إغلاق Verification.

---

## 6. التركيب / DI

### 6.1 الواقع

| المكوّن | الحجم | الحالة |
|---|---|---|
| `MushreaCodeApplication.onCreate` | 716 سطرًا (الملف كاملًا) | **جذر التركيب الفعلي**: يبني الـinstaller/launcher/الوكلاء/المستودعات/الجسر/الجدولة |
| `di/AppModule.kt` + `di/ViewModelModule.kt` | 290 سطرًا | Koin: `startKoin { modules(appModule, viewModelModule) }` في `MushreaCodeApplication.onCreate:171` |
| `ui/ViewModelFactory.kt` | 14 سطرًا | `ViewModelProvider.Factory` عام يعمل بالـlambda — **المسار المستخدم فعليًا** في `ui/MushreaCodeApp.kt` |

**دليل عدم الاستخدام:** `grep -rn "koinViewModel\|by inject\|KoinComponent\|getKoin" --include='*.kt' app/src benchmark` ⇒ **صفر**. الملفات الوحيدة التي تستورد `org.koin` هي الثلاثة أعلاه (بدء التشغيل والتسجيل فقط).

**انحراف مثبت بين الإعدادين** (أي أن نسخة Koin ليست مكافئة بل أدنى):

| القدرة | `MushreaCodeApplication` | `di/AppModule.kt` |
|---|---|---|
| `onPermissionResolved` (إلغاء الإشعار بعد الاستجابة) | ✅ `:550` | ❌ غائب |
| `onSessionStalled` (تشخيص التعطّل + إشعار + تحليلات) | ✅ `:570` | ❌ غائب |
| `unreadStore` (الجلسات غير المقروءة) | ✅ `:574` | ❌ غائب |
| `providerCache` لمستودع الكتالوج | ✅ `MushreaCodeApplication:531` | ❌ غير مُمرَّر |

### 6.2 القرار

1. **يُبقى:** `MushreaCodeApplication` كجذر تركيب واحد، و`ViewModelFactory` كما هو.
2. **يُوحَّد (حذف لاحقًا):** إزالة Koin بالكامل — `startKoin` + `di/AppModule.kt` + `di/ViewModelModule.kt` + الاعتماديتان + تأكيد أن `EXEMPT_LAYERS` في `check_architecture.py` لا يبقى فيها طبقة غير موجودة. المبرر: نظامان لنفس الوظيفة، والثاني أدنى وظيفيًا وأخطر (أي مسار يبنيه مستقبلًا سيفقد الإشعارات والتشخيص). لا اعتماد لـKoin في الاختبارات (تحقّق: صفر).
3. **لا يُقسَّم الآن:** `MushreaCodeApplication` (716 سطرًا) — التقسيم لمجرد الطول مرفوض؛ إن احتاج لاحقًا، فالفصل الطبيعي هو إخراج بناء الوكلاء إلى دالة/مصنع واحد لكل وكيل، ويؤجَّل لأن أي حركة هنا تلامس كل مسار إقلاع.

---

## 7. التخزين والشبكة

### 7.1 التخزين — الواقع

| المخزن | النوع | المحتوى | الحالة |
|---|---|---|---|
| `SecureSettingsRepository` | EncryptedSharedPreferences (AES256-GCM/Keystore) | **71 مفتاحًا**: اتصالات، مفاتيح مزوّدين، GitHub token، إعدادات TTS/كلمة التنبيه، unread، onboarding، مفضّلات النماذج… | يُبقى |
| `ScheduleRepository` | EncryptedSharedPreferences مستقل | الجداول + تاريخ التشغيل (+ ترحيل من prefs قديم غير مشفَّر) | يُبقى |
| `DraftRepository` | SharedPreferences عادي | مسودات نصية لكل جلسة | يُبقى (لا أسرار؛ المحتوى نص المستخدم فقط) |
| `ProviderCatalogCache` | ملفات في `filesDir/catalog-cache` | كتالوج المزوّدين مقلَّمًا (~14KB بدل ~4MB) | يُبقى |
| `data/local/*` (Room) | Room DB v1 | جلسات/رسائل | **يُحذف لاحقًا** |

**دليل موت Room:** مراجع `SessionDatabase`/`SessionDao`/`SessionEntity`/`MessageEntity`/`SessionCacheRepository` توجد **داخل الحزمة `data/local` فقط**، ولا ذكر لها في أي ملف Kotlin آخر، ولا في اختبار، ولا في أصول/سكربتات، ولا `exportSchema`. (المرجع الوحيد خارجها هو اعتماديات Gradle + معالج KSP.)

**قرار التقسيم:** لا يُقسَّم `SecureSettingsRepository` الآن. الملاحظة أنه يحمل 71 مفتاحًا غير متجانس، لكن البديل (facades لكل نطاق فوق نفس ملف التشفير) تحسين تنظيمي لا سلوكي، وقيمته أقل من كلفة لمس كل قارئ. يُسجَّل كدين تقني مُراقَب.

### 7.2 الشبكة — الواقع

| المسار | العميل | المهل/الخصوصية |
|---|---|---|
| OpenCode API | عميل لكل `ConnectionProfile` | 15s connect · 120s read · 30s write + **CertificatePinner عند وجود `pinSha256`** |
| مصادقة المزوّدين (OAuth) | عميل منفصل | 10 دقائق call/read |
| بث الأحداث SSE | عميل مخصص | `readTimeout = 0` |
| أدوات الشبكة (`NetworkExecutor`) | بنّاء لكل طلب | يتبع `timeout_seconds` المطلوب من الوكيل (سلوك أداة، لا عميل عام) |
| 14 موضعًا آخر | `OkHttpClient()` افتراضي | مهل OkHttp الافتراضية (10s) بلا `callTimeout`، و**تجمّع اتصالات/ثريدات مستقل لكل موضع** |

### 7.3 «Remote» نظامان مختلفان (تنبيه لمنع دمج خاطئ)

| النظام | الموقع | الوظيفة |
|---|---|---|
| `runtime/remote` | `RemoteOpenCodeBackend` + `OpenCodeApiClient` | تشغيل وكيل OpenCode جالس على PC (SSE، جلسات، جلسات فرعية) |
| `device/remote` | `RemoteExecutor`/`SshAgent`/`MtpdAgent`… | نقل ملفات وأوامر إلى أجهزة/خوادم المستخدم (SSH/SFTP/SMB/FTP/WebDAV/SCP/mDNS) |

**لا يُدمجان** — لا تقاطع في النماذج ولا في المستهلكين.

### 7.4 قرار الشبكة

**يُوحَّد (تغيير صغير):** إدخال «ملفات عملاء» معدّة (`api` / `download` / `short`) في `core` وإحلالها مكان الـ14 افتراضيًا، مع إبقاء `NetworkExecutor` على بنّائه الخاص (لأن المهلة هنا **مدخل أداة** لا إعداد عام). الفائدة: تجمّع اتصالات واحد، مهل صريحة، وإزالة احتمال نسيان مهلة في مسار جديد. **لا يُغيَّر** `network_security_config` (سببه موثَّق وبوابته في الكود) ولا مسار الـpinning.

---

## 8. الكود الميت: أدلة القرار لكل عنصر

### 8.1 عناصر مُثبت موتها (لا مراجع في أي من المسارات الستة)

| العنصر | الملف | المراجع | القرار |
|---|---|---|---|
| Room layer | `data/local/{SessionDatabase,SessionDao,SessionEntity,MessageEntity,SessionCacheRepository}.kt` | صفر خارج الحزمة | **يُحذف لاحقًا** (+3 اعتماديات +KSP) |
| `ForgeClient` (GitHub/GitLab/Gitea) | `core/api/ForgeClient.kt` (3 أصناف + 6 نماذج) | صفر | **يُحذف لاحقًا** إلا إذا أقرّ المالك خطة «forges متعددة» |
| `KeepAwakeHelper` | `feature/assistant/KeepAwakeHelper.kt` | صفر — يحلّ محله `RuntimeWorkTracker` | **يُحذف لاحقًا** |
| `VoiceActivityDetector` | `feature/wakeword/VoiceActivityDetector.kt` | صفر | **يُحذف لاحقًا** |
| `DragDropAttachHelper` | `feature/workspace/DragDropAttachHelper.kt` | صفر | **يُحذف لاحقًا** |
| `TabletSettingsLayout` | `feature/settings/TabletSettingsLayout.kt` | صفر (تعريف فقط) | **يُحذف لاحقًا** |
| `ConnectionStatus` + `ConnectionQualityMonitor` | `core/api/ConnectionQualityMonitor.kt` | **تصحيح بعد إعادة التحقق أثناء التنفيذ:** `ConnectionStatus` كان نوعًا **مستخدمًا داخل نموذج مستخدم** (`ConnectionQuality.status`)، لكن لا شاشة تقرأ النموذج كله (`connectionQuality` كان يُكتب في `ChatUiState` ولا يُقرأ في أي مكان) ⇒ الحقيقة أن **المكرّي كان يعمل استقصاءً كل 30 ثانية بلا مستهلك للنتيجة**. القرار التنفيذي: حُذف المكرّي والحقل والنوع معه (لا دمج) |
| `RuntimeSnapshot` | `core/runtime/RuntimeLifecycle.kt` | صفر | **قرار مالك**: يُنتَج فعلًا (§2.3) أو يُحذف |

### 8.2 عنصران يُصحَّح تصنيفهما (لا يُحذفان)

| العنصر | التصنيف السابق | التصنيف الصحيح | الدليل |
|---|---|---|---|
| `net.i2p.crypto:eddsa` | «غير مستخدمة إطلاقًا» | **مُعلَنة وغير مُستدعاة من كودنا، لكن sshj يلمّح إليها اختياريًا** | `proguard-rules.pro:78` يشرح أن دعم EdDSA في sshj يلمس `sun.security.x509` ويُضاف `-dontwarn`؛ الحذف قد يعطّل مفاتيح Ed25519 في SSH |
| `org.slf4j:slf4j-nop` | «غير مستخدمة» | **ربط تسجيل وقت التشغيل** لـsshj/smbj (يمنع ضجيج «No SLF4J providers») | لا استيراد مباشر، لكنه موجود لخدمة مكتبات تستخدم SLF4J API |

**القرار للاثنين:** يُبقيان، ويُصحَّح وصفهما في الوثائق (تعارض موثَّق: `PROJECT_VERIFICATION_REPORT.md §81` يقول «eddsa غير مستخدمة إطلاقًا»، و`PROJECT_ANALYSIS_AR.md:729` يقول إنها لتحقّق AVB — كلاهما غير دقيق).

### 8.3 عنصر ليس ميتًا (تصحيح)

`TerminalTabPlaceholder` (`WorkspaceExplorerScreen.kt:319,329`) **مستخدم فعلًا** — لكنه واجهة ناقصة داخل تبويب، وليست بديلًا للنظام الحقيقي. القرار: **يُوحَّد** (§9.4) لا يُحذف.

---

## 9. خطة التنفيذ الصغيرة (Phase 2) — مرتَّبة بالقيمة والخطورة

> كل بند: الملفات · الاختبار · الخطورة · معيار الخروج. تعتمد على موافقة المالك على القرارات المعلَّمة 🔶.

| # | البند | الملفات المتأثرة (تقديري) | الاختبار | الخطورة | معيار الخروج |
|---|---|---|---|---|---|
| **2.1** | **تقييم التوافر (availability)**: محرّك يترجم `requires` إلى `Ready/Blocked(reason)` بمسبارات موجودة، وعرضه في شاشة وكيل الجهاز + فحص قدرة قبل أدوات USB/Termux | `device/tool/DeviceAvailability.kt` (جديد صغير) · `device/DeviceReadiness.kt` · `device/DeviceAgentActivity.kt` · اختبار جديد | اختبار وحدة بمسبارات وهمية لكل شرط + سبب غير فارغ | منخفضة (إضافة فوق عقد قائم) | كل شرط من الـ19 له تقييم مُختبَر، ولا سطر واجهة يفترض التوافر |
| **2.2** | **التحقق بعد التنفيذ (verification)**: `verified` + `verification` في نتيجة الأمر وسطر التدقيق، ومُتحقِّقات فعلية للملفات/المقدمة/حجم النقل/وجود الحزمة، و`verified=false` صريح للبقية | `device/DeviceAgentBridge.kt` · `device/DeviceFileAgent.kt` · `device/usb/UsbExecutor.kt` · `device/ssh/SshExecutor.kt` · `device/DeviceAuditLog.kt` · `assets/scripts/mushreacode-device-mcp.py` (عرض الحقل فقط) | اختبارات لكل مُتحقِّق + ثابت جديد في `check_tool_catalog.py` (كل أداة تُعلن سياسة تحققها) | متوسطة (تلامس مسار التنفيذ) | لا أداة تُبلّغ نجاحًا بلا حقل تحقق؛ والفحوص الثلاثة خضراء |
| **2.3** | **حذف Koin** وجذر التركيب الثاني | `di/AppModule.kt` · `di/ViewModelModule.kt` · `MushreaCodeApplication.kt` (سطر `startKoin`) · `app/build.gradle.kts` (اعتماديتان) · `scripts/check_architecture.py` (`EXEMPT_LAYERS`) | لا اختبارات جديدة؛ التحقق = بناء + اختبارات + الفحوص | منخفضة–متوسطة (تغيير بناء) | صفر مراجع `org.koin`، والبناء أخضر |
| **2.4** | **توحيد الطرفية**: استخدام `TerminalScreen`+`TerminalViewModel` الحقيقيين في تبويب المستكشف وحذف `TerminalTabPlaceholder` | `feature/workspace/WorkspaceExplorerScreen.kt` · `feature/workspace/TerminalViewModel.kt` (كشف المُنشئ عبر الـfactory القائم) · `ui/navigation/WorkspaceNavGraph.kt` | اختبار وحدة للمنطق القائم + تحقق بصري (Cannot Verify هنا) | منخفضة | لا مسار واجهة يعرض طرفية وهمية |
| **2.5** | **توحيد عملاء OkHttp** بمِلفات معدّة | `core/network/HttpClients.kt` (جديد) · 14 موضعًا يستبدل `OkHttpClient()` | اختبار وحدة على إعداد البنّاء (مهل/بروفايل) | منخفضة–متوسطة | لا `OkHttpClient()` افتراضي في `app/src/main` |
| **2.6** | **تحصين صغير**: تضييق `device_file_paths.xml` | `res/xml/device_file_paths.xml` | لا وحدة؛ تدقيق XML + مراجعة | منخفضة جدًا | المسار الخارجي فقط، مع تعليق مبرِّر |
| **2.7** | 🔶 **`pinSha256`**: توصيله بالواجهة/QR أو إعلانه Unsupported | `feature/workspace/ConnectionFormState.kt` · `ConnectionDialog.kt` · `core/security/ConnectionQrPayload.kt` (إن وُصل) | اختبار ترميز QR/الحالة | منخفضة | لا خيار أمني «معلَّق» |
| **2.8** | 🔶 **`RuntimeSnapshot`**: إنتاجه في `RuntimeRegistry` أو حذفه | `runtime/RuntimeRegistry.kt` (+مستهلك واحد) · أو حذف النوع | اختبار تدفّق اللقطة | منخفضة | لا نوع بلا مُنتِج ومستهلك |
| **2.9** | **حذف الميت** (بعد 2.1–2.6 خضراء): Room · ForgeClient · KeepAwakeHelper · VoiceActivityDetector · DragDropAttachHelper · TabletSettingsLayout · ConnectionStatus | ملفات §8.1 + `app/build.gradle.kts` (Room+KSP) | بناء + اختبارات (لا اختبارات جديدة) | متوسطة (بناء/TSP) | البناء والإصدار أخضران، وصفر مراجع |

**قاعدة التنفيذ:** لا يُنتقل بند قبل أن يمر الذي قبله بالدورة: Plan → Implement → Build → Test → Review → Verify → Document. وأي بند يكشف مشكلة حرجة يوقف السلسلة حتى تُصلَح.

### 9.1 ما لا يجب لمسه في هذه المرحلة (ولماذا)

| العنصر | السبب |
|---|---|
| منطق `LocalRuntimeService` (Watchdog/إيقاف الخمول/`RestartBackoff`) | حسّاس، مختبَر، وتعليقاته تشرح أخطاء وقعت فعلًا |
| `RuntimeLifecycle` ودلالات `busy`/`usable` | مفردات مشتركة عبر كل الطبقات |
| `check_tool_catalog.py` ثوابته A–K | تُوسَّع لا تُرخَّى |
| مخطط قناة ملفات MCP (request/result/timeout) | عقد بين التطبيق والوكيل الجالس في الضيف |
| سلسلة تثبيت الران‑تايم ومهام Gradle المخصّصة (`prepare*`) | تكلفتها عالية وخطؤها يكسر كل بناء |
| `network_security_config` + `OpenCodeUrl` | سببهما موثَّق وبوابتهما في الكود |
| قواعد R8 (`proguard-rules.pro`) وحفظ النماذج | كسرها يظهر فقط في الإصدار المُصغَّر |

---

## 10. مخاطر الانحدار التي يجب مراقبتها في Phase 2

| البند | ما قد ينكسر | الحماية المقترحة |
|---|---|---|
| 2.2 (verification) | مسار تنفيذ الأوامر (90 فرعًا) — أكثر مسار حساس | إضافة حقل لا استبدال تدفّق؛ الإيقاف/التأكيد لا يُلمسان؛ ثابت جديد في الفاحص |
| 2.3 (حذف Koin) | بناء النكهتين (`github`/`fdroid`) | التحقق عبر بناء CI كامل على الفرع (غير متاح بهذه الصلاحية — §11، أُعيد قياسه في Phase 2) |
| 2.9 (حذف Room) | KSP لم يبقَ له مستهلك ⇒ يجب إزالة الـplugin أيضًا، وإلا فشل البناء | تنفيذ منفصل ومُتحقَّق منه ببناء كامل |
| 2.4 (الطرفية) | دورة حياة الـViewModel داخل تبويب (إنشاء/تخزين) | إعادة استخدام `RetainedPanel` القائم وعدم إنشاء VM جديد لكل إعادة تركيب |
| 2.5 (OkHttp) | مهل النقل الطويل (تنزيل الران‑تايم) | بروفايل `download` بلا read timeout صريح |

**المناطق التي يجب اختبارها يدويًا بعد Phase 2** (Cannot Verify هنا): الإقلاع/Onboarding · المحادثة · Workspace/الطرفية · وكيل الجهاز (تأكيد + إيقاف) · USB · الجدولة · الصوت.

---

## 11. آلية التحقق دون دمج ودون PR — الواقع بعد Phase 2

اختبارات CI الثقيلة (`test-and-build`/`lint`/`static-analysis`) مشروطة بـ`github.event_name != 'push' || github.ref == 'refs/heads/main'`، وتشغيل فرع الجلسة لا يُنتج منها شيئًا. كان سير العمل يحمل `workflow_dispatch`، لكن **القياس الفعلي في Phase 2 صحّح ذلك:** الأمر التالي يفشل بصلاحية التطبيق:

```bash
gh workflow run android.yml --ref arena/01a0f971-mushrea-ai   # → HTTP 403: Resource not accessible by integration
```

التطبيق بلا `actions: write`، فلا يمكن تشغيل التحقق الكامل من داخل الجلسة، ولم يُفتح PR ولم يُلمس `main` احترامًا لقواعد المالك. **ما يعمل فعلًا ونُفِّذ:** الدفع إلى فرع الجلسة يشغّل `auto-format` (`spotlessApply` على كل ملفات Kotlin، فيلتقط أخطاء التنسيق/البنية السطحية)، ونتيجة أي تشغيل تُقرأ بالأمر:

```bash
gh api "repos/hishamalmushrea-cloud/Mushrea.AI/actions/runs?head_sha=<SHA>&per_page=20" --jq '.workflow_runs[] | "\(.id) \(.name) \(.event) \(.conclusion)"'
```

**ما يلزم للتحقق الكامل (قرار المالك):** منح التطبيق صلاحية `actions: write`، أو فتح PR (فيُشغّل السير الكامل على الفرع)، أو توسيع شرط التشغيل ليغطي فروع `arena/**`. حتى ذلك الحين تبقى حالة البناء/الاختبارات `Cannot Verify — Environment Limitation` كما هو مُسجَّل في §13 وسجل التطوير.

---

## 12. قيود التحقق لهذه المرحلة

| ما لا يمكن التحقق منه هنا | السبب | ما يلزم |
|---|---|---|
| أي سلوك تشغيلي على جهاز (Accessibility، التأكيد الفعلي، USB، مكالمات، طرفية، إشعارات) | لا جهاز/محاكي/`adb` | جهاز Android + عتاد مطابق |
| البناء والاختبارات محليًا | لا JDK/Gradle/SDK | شبكة تسمح بمستودعات Gradle/Google |
| نصّ سجل CI الفردي | `*.blob.core.windows.net` محجوب | — (نجاح المهام كدليل حزمة) |

كل ما لم يُتحقق منه يُبقى مصنَّفًا بصراحة: **`Cannot Verify — Environment Limitation`**.

---

## ملحق: أوامر إعادة إنتاج أهم الأدلة

```bash
# RuntimeSnapshot بلا مستهلك
grep -rn "RuntimeSnapshot" --include='*.kt' app/src | grep -v core/runtime/RuntimeLifecycle.kt

# ToolRequirement بلا مُقيِّم
grep -rn "ToolRequirement\b" --include='*.kt' app/src/main | grep -v device/tool/DeviceToolCatalog.kt

# Koin بلا طلب حلّ
grep -rn "koinViewModel\|by inject\|KoinComponent\|getKoin" --include='*.kt' app/src benchmark

# انحراف إعداد Koin عن جذر التركيب
grep -n "onPermissionResolved\|onSessionStalled\|unreadStore" app/src/main/java/com/mushrea/code/MushreaCodeApplication.kt
grep -n "onPermissionAsked\|onSessionIdle\|onSessionError\|onQuestionAsked" app/src/main/java/com/mushrea/code/di/AppModule.kt

# Room ميت
grep -rn "SessionDatabase\|SessionDao\|SessionCacheRepository" --include='*.kt' app/src | grep -v "data/local/"

# عملاء OkHttp الافتراضيون
grep -rn "OkHttpClient()" --include='*.kt' app/src/main | wc -l

# الأصناف الميتة
for n in ForgeClient KeepAwakeHelper VoiceActivityDetector DragDropAttachHelper TabletSettingsLayout; do
  echo "$n: $(grep -rn "\b$n\b" --include='*.kt' app/src benchmark | grep -vc "src/main/java/com/mushrea/code/.*/$n\.kt")"
done
```

> **حالة المرحلة 1:** ✅ مكتملة — تدقيق بلا تعديل كود. المخرج: هذا الملف. الانتقال إلى Phase 2 ينتظر موافقة المالك على القرارات المعلَّمة 🔶 واختيار بنود الخطة.

---

## 13. نتيجة تنفيذ Phase 2 (تُحدَّث مع التنفيذ)

| البند | الحالة | الالتزام | الدليل |
|---|---|---|---|
| 2.1 تقييم التوافر | ✅ نُفِّذ | `feat(device): evaluate tool requirements and verify every outcome` | `device/tool/DeviceAvailability.kt` (19 شرطًا: Ready/Blocked(reason)/CallDependent) · فحص قبل نافذة التأكيد في `DeviceAgentBridge.process()` · بند الجاهزية يعرض الأدوات المتوقّفة وأسبابها بـ7 لغات · `DeviceAvailabilityTest` (5 اختبارات) |
| 2.2 التحقق بعد التنفيذ | ✅ نُفِّذ جزئيًا وبصدق | نفسه | `device/tool/ToolVerification.kt` + حقلان في كل نتيجة · تحقق فعلي: ملفات (وجود/حجم/اختفاء الأصل) · USB push/pull · SSH upload/download · سحب شجري لكل ملف · `open_app` · البقية `verified=false` بسبب معلن · التدقيق نسق 3 (`DeviceAuditLogTest` 4 اختبارات) · `ToolVerificationTest` (5) |
| 2.3 حذف Koin | ✅ نُفِّذ | `refactor: drop the dead subsystems…` | `di/` محذوف · `startKoin` محذوف · الاعتماديتان محذوفتان · `EXEMPT_LAYERS` بلا `di` · صفر مراجع `org.koin` |
| 2.4 توحيد الطرفية | ✅ نُفِّذ | `refactor(workspace): the terminal tab runs the real terminal` | `WorkspaceExplorerScreen` صار يستخدم `TerminalScreen`+`TerminalViewModel` (مفتاح لكل تبويب) وحُذف `TerminalTabPlaceholder` |
| 2.5 عملاء HTTP | ✅ نُفِّذ | `refactor(network): one shared client per profile…` | `core/network/HttpClients.kt` (api/download/short) · صفر `OkHttpClient()` افتراضي في `app/src/main` (الاختبارات فقط) |
| 2.6 تضييق FileProvider | ✅ نُفِّذ | `fix(security,docs): narrow the file provider…` | `device_file_paths.xml` = `external-path` فقط مع تعليق مبرِّر |
| 2.7 `pinSha256` | 🔶 بانتظار قرار المالك | — | لم يُمَس (`OpenCodeApiClient` يبنيه على بروفايل `api` المشترك) |
| 2.8 `RuntimeSnapshot` | ✅ حُذف | `refactor: drop the dead subsystems…` | صفر مراجع بعد الحذف؛ وشرح في `RuntimeLifecycle.kt` أن إعادته تحتاج مُنتِجًا أولًا |
| 2.9 حذف الميت | ✅ نُفِّذ | `refactor: drop the dead subsystems…` | Room (5 ملفات + 3 اعتماديات + KSP + إضافة KSP في الجذر) · `ForgeClient` · `KeepAwakeHelper` · `VoiceActivityDetector` · `DragDropAttachHelper` · `TabletSettingsLayout` · `ConnectionQualityMonitor` · `RuntimeSnapshot` · `xz` المكرّرة |
| إصلاحات صغيرة خارجة عن الخطة | ✅ نُفِّذت | في التزامات 2.4/2.6 | `device-matrix.md` (ادعاء `connectedDebugAndroidTest` أُبطل) · `RELEASE.md` (`AND_CODE_*`) |
| تصحيح تصنيف | ✅ | ضمن التزام الحذف | `eddsa` و`slf4j-nop` **ليسا ميتين** (سبب مكتوب في `app/build.gradle.kts` وقاعدة R8) |

**ما لم يُنفَّذ في Phase 2 (مؤجَّل بصراحة):** `pinSha256` (بانتظار قرار) · التحقق الشاشي لـ`tap`/`type_text`/`scroll`/`swipe` والمكالمات والمشاركة (لا يمكن إثباته من التطبيق) · التدقيق الموحَّد للوكلاء/الجدولة/الشبكة · `paramsDigest`/`startedAt`/`endedAt` · تقسيم `SecureSettingsRepository`/`MushreaCodeApplication` · اختبار الأجهزة (23 اختبارًا) لا يزال غير مُنفَّذ في أي سير عمل.
