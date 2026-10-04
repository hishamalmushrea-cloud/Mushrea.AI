# FEATURE_MATRIX.md — مصفوفة الميزات وحالة التحقق

> **الغرض:** جدول واحد يجيب عن سؤال «ما الذي يعمل فعلًا؟» لكل ميزة، ويفصل بشكل صريح بين
> **وجود الكود** و**وجود اختبار** و**تشغيل حقيقي موثَّق**. لا يُحدَّث هذا الملف بالنيّة، بل بالدليل.
>
> **الفرع:** `arena/01a0f971-mushrea-ai` · **الرأس المُتحقَّق منه:** `03e23e0` (تشغيل `37030584185`، ست مهام ✅ — ويليه هذا الالتزام التوثيقي وحده) · **التاريخ:** 2026-10-02
> (أي التزام توثيقي بعده لا يغيّر شجرة البناء: لا اختبار يقرأ هذا الملف ولا `DEVELOPMENT_LOG`.)
>
> **حالة الأدلة الآن:** تشغيل **`36965385897`** على `f2e68b7` **نجح بكل مهامه** (بناء + اختبارات +
> lint + R8 + الفاحصون) — التفصيل في §4. لذلك:
>  * **Tested** صار يعني: الاختبارات **نُفِّذت فعلًا** في ذلك التشغيل، لا «مكتوبة فقط»؛
>  * **Runtime Verified** يبقى **لا** لكل ما يحتاج جهازًا: لم يُشغَّل أي اختبار على جهاز/محاكي،
>    واختبارات الأجهزة الـ23 **صُرِّفت فقط** ضمن التشغيل ولم تُنفَّذ.
>
> **الفارق مع الأساس:** الأساس `1f551b4` كان أخضر عبر تشغيلات PR (`36800962203` · `36801349852`)،
> وتغييرات هذه الجلسة لم يكن لها أي دليل تشغيل حتى تشغيل `36965385897` — وهو ما أعاد الدليل،
> بعد أن كشفت أول عملية بناء حقيقية **ست علل تصريف/موارد** في عمل الجلسة (§4.1).

---

## 0) كيف تُقرأ الجداول

| العمود | معناه بدقة | ما الذي يغيّره |
|---|---|---|
| **Implemented** | الكود قائم في الشجرة وله مستهلك حقيقي (شاشة/جسر/أداة)، وليس نوعًا معلَّقًا | حذف أو إضافة مسار فعلي |
| **Tested** | يوجد ملف/ملفات اختبار للسلوك، ويُذكر نوعها وعددها. **「مكتوب」≠「ناجح」**: ما لم يُشغَّل يُوسَم صراحة | تشغيل فعلي في CI أو على جهاز يرفع الخلية إلى موثَّق |
| **Runtime Verified** | أثر تشغيل حقيقي (CI أخضر، أو جلسة على جهاز/محاكي، أو لقطة سجل) | لقطة تشغيل: `CI run` أو `adb` أو سجل الجهاز |
| **Hardware Required** | ما يجب توفّره للتحقق الفعلي (USB host، هاتف ثانٍ، كاميرا، ميكروفون، شريحة اتصال…) | لا يتغيّر إلا بتغيير المنتَج |
| **Status** | مفردات الحالة: `Verified` · `Partially Verified` · `Code Present` · `Prototype` · `Broken` · `Unsupported` · `Dead-Unused` · `Cannot Verify` | كل رفع يحتاج دليلًا في عمود Runtime Verified |

**قاعدة الفصل:** أي خلية تقول «Code Present» تعني حرفيًا: الكود موجود، **ولم يُثبَت سلوكه**.
لا يُكتب `Verified` إلا مع دليل تشغيل مُشار إليه.

---

## 1) ميزات التطبيق

| الميزة | Implemented | Tested | Runtime Verified | Hardware Required | Status |
|---|---|---|---|---|---|
| الإقلاع وOnboarding والبوابة الأولى | ✅ | 3 اختبارات (`feature/onboarding`) + `OnboardingGateTest` — ✅ مُنفَّذة في تشغيل CI | ❌ لا دليل | لا | `Code Present` |
| المحادثة: جلسات، بث، أدوات، استدلال، موافقات | ✅ | 241 (`feature/chat`) + 61 (`core/api`) — ✅ مُنفَّذة في التشغيل · 5 ملفات اختبار جهاز **صُرِّفت ولم تُشغَّل** | ❌ لا دليل | ميكروفون للصوت · شبكة | `Code Present` |
| المرفقات والصور وعارض الوسائط | ✅ | ضمن `feature/chat` + اختبار جهاز للعارض | ❌ لا دليل | لا | `Code Present` |
| Voice/TTS في الواجهة | ✅ | 30 (`feature/assistant`) + 5 (`core/voice`) | ❌ لا دليل | ميكروفون (إدخال) · مخرَج صوت | `Code Present` |
| Workspaces: مساحات، متصفح ملفات، عارض كود | ✅ | 45 (`feature/workspace`) | ❌ لا دليل | تخزين خارجي (وصول الملفات) | `Code Present` |
| الطرفية المدمجة (تبويب حقيقي `TerminalScreen`/`TerminalViewModel` — Phase 2) | ✅ | 9 (`device/termux`) | ❌ لا دليل | تطبيق Termux مثبَّت | `Code Present` |
| اتصال PC/OpenCode البعيد: بروفايلات، اكتشاف LAN، اختبار اتصال | ✅ | 2 (`core/connection`) + جزء من 61 (`core/api`) | ❌ لا دليل | شبكة محلية | `Code Present` |
| فحص QR للاتصال | ✅ استيراد فقط — التطبيق لا **يولّد** رمزًا (`ConnectionQrPayload.format` بلا مُنتِج) | `ConnectionQrPayloadTest` | ❌ لا دليل | كاميرا | `Code Present` |
| تثبيت الشهادة `pinSha256` (Phase 2 — بند 2.7) | ✅ حقل + تحقق base64 + تمييز فشل التثبيت + حفظ عبر التعديل | `ConnectionPinTest` (4) + 5 (`ConnectionFormStateTest`) + 2 (`OpenCodeApiClientTest`) | ❌ لا دليل | خادم https بشهادة حقيقية | `Code Present` |
| الران‑تايم المحلي: تثبيت/تشغيل/إيقاف + تحديث + تنزيل موثَّق | ✅ | 457 (`runtime/local`) + اختبار جهاز للمُحدِّث | ❌ لا دليل | تخزين خارجي · شبكة | `Code Present` |
| الوكلاء: OpenCode · Claude Code · Antigravity · Codex | ✅ | 16 (`runtime/agent`) + 16 (`runtime/lifecycle`) | ❌ لا دليل | تخزين · شبكة (تثبيت) | `Code Present` (README يدّعي Stable/Beta — ادّعاء غير مُثبَت هنا) |
| سجل الران‑تايم والتبديل بين الأهداف (handoff) | ✅ | `RuntimeRegistryTest` + `runtime/remote` (2) | ❌ لا دليل | لا | `Code Present` |
| جدولة المهام (منبّهات دقيقة، إعادة محاولة، حارس تشغيل، سجل تنفيذ) | ✅ | 45 (`feature/schedule`) | ❌ لا دليل | إذن المنبّهات الدقيقة · استثناء البطارية | `Code Present` |
| كلمة التنبيه (Vosk: كتالوج/تثبيت/مطابقة/تجاوز) | ✅ | 35 (`feature/wakeword`) | ❌ لا دليل | ميكروفون · تنزيل نموذج · خدمة أمامية | `Code Present` |
| وكيل الجهاز: جسر، سياسة صلاحيات، جدار ناري، تأكيد/إيقاف طارئ | ✅ | 21 (`device/tool`) + 10 (`device/permission`) + 11 (`DeviceToolCatalogTest`) | ❌ لا دليل | لا (التحقق يحتاج جهازًا) | `Code Present` |
| **إصدار موقَّع (Release signing)** | القياس موحَّد في `verify_release_apks.sh`؛ المساران المعتادان يرفضان شهادة مختلفة عن `.github/signing-identity.sha256`. الـbootstrap لأول نشر فقط ويرفض إعادة توليد المفتاح بعد نشر/وسم/مغلّف قائم | `37208874499` ✅: النكهتان v1+v2+v3 وموقِّع واحد بالبصمة `ed8842ed…` (أُعيد التوليد لأن هوية `27e45f68…` بلا نسخة احتياطية مؤكَّدة). فك CMS محليًا طابق شهادة الـP12 البصمة نفسها | 20 اختبار Python/OpenSSL للبوابة ✅؛ استعادة P12 من المغلف ✅ | `v1.2.26` ما زالت مسودة. النشر ينتظر تأكيد المالك أنه حمّل أرشيف المفتاح الخاص | `Partially Verified` — التوقيع مُقاس؛ الجهاز الحقيقي والنشر لم يُثبتا بعد |
| **مركز الصلاحيات الموحَّد (P2)** | ✅ `core/permission/PermissionCenter` + سياسة الجهاز `DeviceToolPolicy` وسياسة الران‑تايم `RuntimePermissionPolicy`؛ القرار من المركز في: الجسر · دورة حياة الران‑تايم · تصريح الوكيل المسبق (محادثة/صوت/جدولة) | `PermissionCenterTest` (21) · `DeviceToolPolicyTest` (13) · `RuntimePermissionPolicyTest` (11) + `check_permission_center.py` (807 فحوص) — ✅ مُنفَّذة في تشغيل CI | ❌ لا دليل (لا تنفيذ حقيقي على جهاز) | لا | `Code Present` |
| تقييم التوافر قبل التنفيذ (Phase 2 — 2.1) | ✅ 19 شرطًا: `Ready`/`Blocked(reason)`/`CallDependent` قبل نافذة التأكيد | `DeviceAvailabilityTest` (5) | ❌ لا دليل | حسب الشرط (USB/طرفية/اتصال…) | `Code Present` |
| التحقق بعد التنفيذ (Phase 2 — 2.2) | ✅ جزئي مُعلن: تحقق فعلي للملفات وUSB وSSH و`open_app`؛ البقية `verified=false` بسبب | `ToolVerificationTest` (5) + `DeviceAuditLogTest` (4) | ❌ لا دليل | جهاز + وسيط (USB/SSH) | `Partially Verified` (التغطية جزئية بالتصميم، والتشغيل غير مُثبَت) |
| سجل تدقيق وكيل الجهاز (نسق 4: `verified`/`verification` + `params_digest` + نافذة `started_at`/`ended_at`/`duration_ms`) | ✅ | `DeviceAuditLogTest` (9) — ✅ مُنفَّذة في تشغيل CI | ❌ لا دليل | لا | `Code Present` |
| USB/ADB: تفويض، نقل، مفاتيح، مركز USB، MTP | ✅ | 9 (`device/usb`) + 12 (`device/usbhub`) + 12 (`device/payload`) | ❌ لا دليل | **USB host** + هاتف ثانٍ بوضع ADB/MTP | `Code Present` |
| SSH (تنفيذ/رفع/تنزيل/سرد) | ✅ | لا اختبارات مخصّصة | ❌ لا دليل | مضيف SSH + بيانات اعتماد | `Code Present` |
| المرآة (scrcpy) | ✅ | 14 (`device/mirror`) | ❌ لا دليل | ADB عبر USB + هاتف ثانٍ | `Code Present` |
| وكيل المكالمات (اتصال/رد/سجل) | ✅ | 30 (`device/call`) | ❌ لا دليل | شريحة اتصال + دور تطبيق الاتصال الافتراضي | `Code Present` |
| Bluetooth (فحص/ربط) | ✅ | لا اختبارات مخصّصة | ❌ لا دليل | بلوتوث مفتوح | `Code Present` |
| الشبكة كأدوات جهاز (dns/http/ping/port/ws/wifi) | ✅ | لا اختبارات مخصّصة | ❌ لا دليل | شبكة | `Code Present` |
| طبقة HTTP موحَّدة (Phase 2 — 2.5) | ✅ `HttpClients` (api/download/short) بلا `OkHttpClient()` افتراضي في الإنتاج | مُغطّاة ضمن اختبارات العملاء | ❌ لا دليل | لا | `Code Present` |
| تكامل GitHub (دخول بجهاز + إصدارات + نجمة الدعم) | ✅ | 33 (`feature/settings`) + أجزاء `core/api` | ❌ لا دليل | شبكة + حساب GitHub | `Code Present` |
| الأمان والخصوصية (سياسة الصلاحيات، تنقيح الأسرار، FileProvider مُضيَّق) | ✅ | 31 (`core/security`) | ❌ لا دليل | لا | `Code Present` |
| التشخيص وتقارير الأعطال | ✅ | 20 (`core/diagnostics`) | ❌ لا دليل | لا | `Code Present` |
| المتصفح الضيف + مراقب أوامر المتصفح | ✅ | لا اختبارات مخصّصة | ❌ لا دليل | شبكة | `Code Present` (تغطية صفرية) |
| استقبال المشاركة + أداة الإدخال السريع (Widget) | ✅ | لا اختبارات مخصّصة | ❌ لا دليل | لا | `Code Present` (تغطية صفرية) |
| الشاشات القانونية والامتثال | ✅ | اختبارات `compliance` + اختبار جهاز للشاشة | ❌ لا دليل | لا | `Code Present` |

---

## 2) عائلات أدوات وكيل الجهاز (19 عائلة · 98 أداة · 96 موجَّهة للوكيل · 36 CONFIRM / 61 AUTO · 42 قراءة فقط)

كل الأدوات مُعلَنة في `device/tool/DeviceToolCatalog.kt` ومُقيَّمة في `DeviceAvailability` ومُدقَّقة في
`DeviceAuditLog`؛ ما تحتاجه كل عائلة من عتاد هو ما يجعل التحقق الفعلي ممكنًا أو مستحيلًا هنا.

| العائلة | الأدوات | ما يلزم للتحقق الفعلي | Status |
|---|---|---|---|
| SCREEN (لمس/كتابة/تمرير/لقطات/مقدمة) | 20 | إمكانية الوصول مفعَّلة + جهاز حقيقي (التحقق الشاشي معلن غير قابل للإثبات من التطبيق) | `Code Present` |
| USB (ADB/MTP/سيريال/مركز/سجل) | 16 | USB host + هاتف/وسيط | `Code Present` |
| FILES (حذف/نقل/نسخ/سرد/قراءة) | 7 | تخزين خارجي ممنوح | `Code Present` |
| NETWORK (`dns_lookup` · `http_request` · `net_ping` · `port_check` · `websocket` · `wifi_info`) | 6 | شبكة | `Code Present` |
| CALL (اتصال/رد/إنهاء) | 6 | شريحة + دور الاتصال الافتراضي | `Code Present` |
| TERMUX (تشغيل/حالة/fastboot) | 4 | تطبيق Termux + جسر مُصرَّح | `Code Present` |
| SSH (تنفيذ/رفع/تنزيل/سرد) | 4 | مضيف SSH | `Code Present` |
| MIRROR (scrcpy) | 4 | ADB USB | `Code Present` |
| HUB (مركز USB) | 4 | مركز USB فعلي | `Code Present` |
| BT | 4 | بلوتوث | `Code Present` |
| REMOTE (نقل ملفات لهدف بعيد) | 3 | هدف بعيد | `Code Present` |
| PAYLOAD (حمولة/سكربت) | 3 | ملف حمولة + جهاز | `Code Present` |
| STATUS | 2 | لا | `Code Present` |
| SERIAL | 2 | محوّل USB‑Serial | `Code Present` |
| MTP | 2 | جهاز بوضع MTP | `Code Present` |
| SAFETY (إيقاف/سلامة) | 1 | لا | `Code Present` |
| CONTEXT (سياق الجهاز) | 1 | لا | `Code Present` |
| AUDIT (قراءة التدقيق) | 1 | لا | `Code Present` |

---

## 2.1) Peer ADB — هاتف ثانٍ عبر التصحيح اللاسلكي

| الميزة | Implemented | Tested | Runtime Verified | Hardware Required | Status |
|---|---|---|---|---|---|
| حمولة QR (`WIFI:T:ADB;S:…;P:…;;`) — توليد وتحليل ورفض غير ADB | ✅ | 9 اختبارات (`PeerAdbPairingPayloadTest`) — ✅ مُنفَّذة في CI | ❌ لا دليل على مسح فعلي | هاتفان | `Partially Verified` (البُنية مُثبَتة بالاختبار؛ قبول الهاتف للرمز يحتاج هاتفين) |
| اكتشاف mDNS لأنواع الخدمات الثلاثة ومطابقة اسم الجلسة | ✅ | 8 اختبارات (`PeerAdbServiceTest`) — ✅ مُنفَّذة في CI (تحليل الاسم والنوع) | ❌ لا بثّ حقيقي | شبكة محلية بهاتفين | `Partially Verified` |
| اقتران (`adb pair`) واتصال (`adb connect`) وربط التسلسل الحقيقي | ✅ | مُختبَر نصيًا ضمن `PeerAdbServiceTest`/`AdbCommandLineTest` | ❌ لم يُنفَّذ `adb` حقيقي | هاتفان + runtime مثبَّت | `Cannot Verify` |
| اكتشاف القدرات العام `kind:name` — ~80 برنامجًا + applets الخاصة بـ`toybox` + خدمات `cmd`/`service` + حقائق مقيسة (قراءة/كتابة fs، صلاحية، مدير حزم، تصحيح، بناء) وقدرات النقل | ✅ بلا سقف: أي قدرة يكتشفها مزوّد لاحقًا تقع في تصنيفها من بادئتها | 8 اختبارات (`PeerCapabilityScriptTest`) — تُنفَّذ في CI | ❌ لا سبر على هاتف حقيقي | هاتف ثانٍ | `Partially Verified` (التحليل والقواعد مُثبَتة؛ القراءة الحقيقية لا) |
| نموذج القدرات: `UNKNOWN ≠ MISSING`، الدمج والفرق، وقراءة خرائط الإصدارات السابقة بالأسماء البديلة بلا ترحيل | ✅ `core/execution/CapabilityModel.kt` + `CapabilityAliases.kt` | 4 اختبارات (`CapabilityAliasesTest`) — تُنفَّذ في CI | ❌ لا دليل | لا | `Partially Verified` |
| تصنيف الأوامر وحارس الإقرار الكاذب | ✅ | 8 + 14 اختبارًا (`PeerCommandClassifierTest`، `PeerAdbProviderTest`) — ✅ مُنفَّذة في CI | ❌ لا دليل | لا | `Partially Verified` |
| **قدرات الشبكة المقيسة `net:`** (`wifi` · `adb_wifi` · `airplane`) — تُقرأ بـ`settings get` بلا أثر جانبي في نفس السبر المجمَّع، ولها تصنيفها الخاص `CapabilityKind.NETWORK` | ✅ — `CapabilityModel` + `PeerCapabilityScript` | 3 اختبارات جديدة (`PeerCapabilityScriptTest` 8 → 11) | ❌ لا سبر على هاتف حقيقي | هاتف ثانٍ | `Partially Verified` (التحليل والقواعد مُثبَتة؛ القراءة الحقيقية لا) |
| **وصفات التجهيز `provision.*`** (إبقاء مستيقظًا · إبقاء الواي فاي · قراءة مفتاح التصحيح اللاسلكي · منفذ ADB ثابت) في كتالوج التنفيذ العام نفسه | ✅ — `ExecutionRecipes` (37 → 41 وصفة) | 3 + 3 اختبارات جديدة (`ExecutionRecipesTest` 12 → 15 · `ExecutionRecipePlanTest` 14 → 17) | ❌ لا تنفيذ على هاتف | هاتف ثانٍ | `Partially Verified` |
| سياسة `PEER_DEVICE` **بالأثر والسياق** (mutating/destructive/risk/source/preAuthorized) لا بقائمة أسماء، مع بقاء القراءة فقط والإيقاف الطارئ قاعدتين مركزيتين | ✅ مُسجَّلة فعلًا في `PermissionCenter` داخل التطبيق | اختبارات `PeerDevicePolicyTest` + `PermissionCenterTest` | ❌ لا دليل | لا | `Partially Verified` |
| سجل التنفيذ ومفردات المراحل | ✅ | 6 اختبارات (`ExecutionLogTest`) — ✅ مُنفَّذة في CI | ❌ لا دليل | لا | `Partially Verified` |
| **وصفات التنفيذ العامة** (37 هدفًا: معلومات، حزم وتطبيقات، ملفات، شاشة وإدخال، شجرة واجهة، سجل، إعدادات، خدمات، سكربتات، سطر شل عام) كبيانات لا كود، ومعاملات مُتحقَّق منها | ✅ `ExecutionRecipes.kt` + `ExecutionRecipe.kt` | 12 اختبارًا (`ExecutionRecipesTest`) + 5 (`ShellLineTest`) — تُنفَّذ في CI | ❌ لا تنفيذ على هاتف | هاتف ثانٍ للسلوك الحقيقي | `Partially Verified` |
| **التخطيط بالهدف**: هدف (أو وصف بالعربية/الإنجليزية) → مسارات مرتّبة حسب القدرات المُقاسة + متطلبات المزوّد، وسبب كل مسار متخطّى محفوظ | ✅ `ExecutionPlanner.kt` | 14 اختبارًا (`ExecutionRecipePlanTest`) — تُنفَّذ في CI | ❌ لا دليل | لا | `Partially Verified` |
| **تنفيذ الهدف عبر الجسر** مع بوابة على *كل* طلب (خطوة، بديل، تحقّق)، وسلسلة احتياط مسجَّلة السبب، وتمييز «نُفِّذ» عن «تحقّق» | ✅ `PeerAdbBridge.executeGoal` | 8 اختبارات (`PeerGoalExecutionTest`) — تُنفَّذ في CI | ❌ لا هاتف | هاتفان | `Partially Verified` |
| أدوات الوكيل الثمانية (`peer_*`) — 4 مواضع متطابقة؛ و`peer_plan`/`peer_execute` صارا يطلبان **هدفًا** (`recipe` + `parameters` أو `goal` بالكلمات) مع بقاء الشكل القديم للتوافق | ✅ | فاحص `check_tool_catalog.py` (98 مدخلًا · 96 اسمًا · 97 إجراءً · 36/61/42) | ❌ لا دليل | هاتفان | `Code Present` |
| **تصنيف نطاق العنوان** (`LOOPBACK` · `LOCAL_LINK` · `LAN` · `PRIVATE_OVERLAY` · `PUBLIC_INTERNET` · `UNKNOWN`) وتمييز نفق Tailscale/Headscale (100.64/10 و`tun*/wg*`) عن الشبكة المحلية | ✅ `core/connectivity/NetworkScope.kt` | 7 اختبارات (`NetworkScopeTest`) — تُنفَّذ في CI | ❌ قراءة واجهات حقيقية | لا | `Partially Verified` |
| **ترتيب المسارات وشرحها**: `Endpoint` + `EndpointSource` (سُلِّم ← أُعلن ← سُمِّي ← ذُكر) + `RouteCatalogue` يرتّب بالنطاق ثم بالدليل ويكتب السبب | ✅ `core/connectivity/{Endpoint,Connectivity}.kt` | 6 اختبارات (`ConnectivityResolverTest`) — تُنفَّذ في CI | ❌ لا شبكة حقيقية | لا | `Partially Verified` |
| **مزوّدا شبكة/مسارات قابلان للتوسّع**: `ConnectivityProvider` (جهازنا) و`EndpointProvider` (مسارات الهدف) — إضافة شبكة خاصة = مزوّد جديد | ✅ `AndroidNetworkStateProvider` + `RememberedEndpointProvider` + `AdbLiveEndpointProvider` | غير مغطّى باختبار وحدة (يعتمد Android) | ❌ لا جهاز | جهاز حقيقي | `Code Present` |
| **محرّك التجهيز**: خطوات مصنَّفة بخمس نتائج (`Completed/Skipped/RequiresUserAction/Unsupported/Failed`)، توقّف عند المستخدم بتعليمة واحدة، وإعادة قراءة الحقائق بعد كل خطوة تُغيّر الواقع | ✅ `core/provisioning/*` + `device/provisioning/DeviceProvisioningHost` | 14 اختبارًا (`ProvisioningTest`) — تُنفَّذ في CI | ❌ لا تنفيذ على هاتف | هاتفان (الاختبار في §8 من وثيقة المعمارية) | `Partially Verified` |
| **هوية ثابتة**: `identityKey` مشتقّة (سيريال + حقائق الهوية) تصمد لتغيّر العنوان وتتغيّر لتغيّر الجهاز، وتُقارَن قبل تبنّي أي مسار | ✅ `core/peer/PeerIdentityDigest.kt` | 6 اختبارات (`PeerIdentityDigestTest`) — تُنفَّذ في CI | ❌ لا جهاز | هاتف ثانٍ للتغيير الحقيقي | `Partially Verified` |
| **إعادة اتصال محسوبة**: سلّم `DISCOVERING→CONNECTING→VERIFYING→READY`، فواصل أسّية بسقف 60s، رفض جهاز مختلف على عنوان قديم، ولا polling متّصل | ✅ `device/bridge/PeerReconnectionManager.kt` | 7 اختبارات (`PeerReconnectionManagerTest`) — تُنفَّذ في CI | ❌ لا جهاز | هاتفان | `Partially Verified` |
| **سجل الجهاز يتذكّر ما يُثبَت**: دمج لا يمحو معلومة، رتبة مُثبَتة لا تنخفض، قائمة مسارات (حتى 8) الأحدث أولًا | ✅ `PeerDeviceRegistry` + `PeerDevice` | 5 اختبارات (`ProvisioningRegistryTest`) — تُنفَّذ في CI | ❌ لا دليل | لا | `Partially Verified` |
| **أدوات التجهيز**: `peer_provision` (CONFIRM، 300s) · `peer_reconnect` (CONFIRM) · `peer_endpoints` (AUTO) — 4 مواضع متطابقة | ✅ | فاحص `check_tool_catalog.py` (101 مدخلًا · 99 اسمًا · 100 إجراءً · 38/62/43) | ❌ لا دليل | هاتفان | `Code Present` |
| **ما يستحيل على أي تطبيق**: تشغيل Wireless debugging من المضيف، قراءة المنفذ العشوائي عبر API، البقاء بعد إعادة تشغيل الهاتف | موثَّق كخطوة `RequiresUserAction` بتعليمة واحدة + مسار `PERSIST` لما هو مسموح | — | — | — | `Android limitation` + `OEM dependent` |
| رحلة كاملة: QR → اقتران → اكتشاف → TLS → أمر → خرج → تحقّق | ✅ الكود | ✖ لا اختبار تكامل حقيقي بعد | ❌ لا دليل | هاتفان Android 11+ | `Cannot Verify` |

**قاعدة صريحة:** لا شيء في هذا القسم يُوصف بأنه `Verified` أو «يعمل على جهاز حقيقي» قبل تشغيل
موثَّق بين هاتفين. التفاصيل المعمارية في `docs/architecture/PEER_ADB.md` (النقل) و`docs/architecture/EXECUTION.md` (القدرات والوصفات والمخطِّط والمزوّدون) و`docs/architecture/REMOTE_DEVICE_ARCHITECTURE.md` (التجهيز الدائم: النطاق، الهوية، إعادة الاتصال)؛ والتدقيق والخطة في `docs/development/REMOTE_DEVICE_PLAN.md`؛ وقائمة الاختبار الحقيقي التي ينفّذها المالك في `docs/development/REMOTE_DEVICE_TEST_PLAN.md`.

## 2.2) دليل التشغيل المُثبَت — الجولة الحالية: تشغيل CI [`37169996851`](https://github.com/hishamalmushrea-cloud/Mushrea.AI/actions/runs/37169996851) على `62e3ac4`

> **قناة الدليل الآن:** بعد فتح PR #11 من هذا الفرع إلى `main` حُذف `verify-branch.yml` (المؤقت)، وصار دليل البناء/الاختبارات يأتي من **Android CI على الـPR** (تشغيل `37170711036` على `7b80bc2`).

| المهمة | النتيجة | ما تُثبته بالضبط |
|---|---|---|
| static analysis (detekt + spotless + الفاحصون) | ✅ success | القواعد الأربع على كود جولة التجهيز (896 فحص صلاحيات · 101/99/100 أداة) |
| refresh the pinned Termux package lock / generated licence data | ✅ success | لا تغيير في القفل ولا في الترخيص: لم تُضَف أي تبعية في هذه الجولة |
| unit tests + debug APK + instrumentation compile | ✅ success | **إعادة تشغيل كامل حزمة الوحدات** — 1,638 المُثبَتة في `37164241427` + **9 جديدة لقدرات الشبكة ووصفات التجهيز** = 1,647 (العدد من مصادر الاختبار؛ التشغيل هو الدليل على نجاح الحزمة) · `assembleGithubDebug` · تصريف اختبارات الأجهزة (23 صُرِّفت ولم تُشغَّل) |
| android lint · release APK (R8 minified) | ✅ success | `lintGithubDebug` بلا أخطاء · `assembleGithubRelease` مع R8 |

**ما يعنيه:** منطق التجهيز وإعادة الاتصال والهوية والنطاق مُثبَت بالاختبارات؛ والتصريف والـlint والـR8 مُثبتة بأثر.
**ما أضافه تشغيل الواجهة (`cb05ba8`):** زر «تجهيز للعمل عن بُعد» في شاشة الأجهزة وصفّي «التجهيز» و«الثقة» يُصرَّفون ويجتازون `lintGithubDebug` و `assembleGithubRelease` مع R8، بعد أن أثبت تشغيل `37164241427` على `3cb9498` منطق التجهيز نفسه (46 اختبارًا جديدًا).
**ما أضافه تشغيل إصلاح الواجهة (`62e3ac4`):** بطاقة الجهاز تعرض سطر «النقل» (القناة التي وصل منها آخر أمر) — كان الحقل مُعبَّأ ولا يُقرأ؛ والأثر: ست مهام ✅ مع النص الجديد في اللغات الثماني عبر `lintGithubDebug`.
**ما أضافه تشغيل R5 (`c765a7e`):** `net:` مقيسة في السبر (`net:wifi` · `net:adb_wifi` · `net:airplane`) وأربع وصفات `provision.*` — 9 اختبارات جديدة، والبناء والـlint والـR8 والحزمة كاملة خضراء.
**ما لا يعنيه:** لا تشغيل على جهاز حقيقي — أي شيء يلمس شبكة أو هاتفًا ثانيًا يبقى `Cannot Verify` حتى اختبار المالك.

## 3) دليل التشغيل المُثبَت (جولات سابقة) — تشغيل CI `36965385897` على `f2e68b7`

| المهمة | النتيجة | ما تُثبته بالضبط |
|---|---|---|
| static analysis (detekt + spotless + الفاحصون الثلاثة) | ✅ success | قواعد الطبقات وسجل الأدوات وسلوك خطاف الصلاحيات + detekt + spotless على الرأس |
| refresh the pinned Termux package lock | ✅ success | أداة المشروع أعادت حلّ القفل من فهرس Termux الحالي (بعد أن حُذفت النسخ المثبَّتة upstream) |
| android lint (githubDebug) | ✅ success | `:app:lintGithubDebug` بلا أخطاء |
| unit tests + debug APK + instrumentation compile | ✅ success | **1,403 اختبار وحدة نُفِّذت ونجحت** · بناء `assembleGithubDebug` · تصريف `assembleGithubDebugAndroidTest` (الاختبارات الـ23 صُرِّفت ولم تُشغَّل) |
| release APK (R8 minified) | ✅ success | `assembleGithubRelease` كاملًا مع R8 والتقليص |

**ما يعنيه هذا:** التصريف الكامل للنكهتين، وتشغيل كل اختبارات الوحدة، وlint، وR8 — كلها
**مُثبتة بأثر تشغيل** على `f2e68b7`. وما لا يعنيه: لم يُشغَّل أي شيء على جهاز/محاكي، فسلوك
الميزات المرتبطة بالعتاد يبقى `Code Present` لا `Verified`.

### 3.1 ست علل حقيقية كشفها أول بناء فعلي (كلها أُصلحت)

| # | العلّة | الإصلاح |
|---|---|---|
| 1 | فرع الجلسة **لم يكن يُصرَّف**: `DeviceAgentBridge` يستدعي `ToolVerification` وهو غير موجود (النوع الفعلي `OutcomeVerification`)، وناقص استيراد `DeviceAvailability` | `63a4548` |
| 2 | `WorkspaceExplorerScreen` بلا استيراد `LocalContext` (تبويب الطرفية من Phase 2) | `63a4548` |
| 3 | خمس أيقونات تستخدم `?attr/colorControlNormal` — صفة كانت تتوفّر عبر `appcompat` الذي خرج من الشجرة مع حذف الأنظمة الميتة ⇒ فشل ربط موارد الإصدار | `9bdd00b` (صار `?android:attr/…`) |
| 4 | نص فرنسي (`readiness_tools_ready`) بفاصلة عليا غير مهروبة ⇒ `Invalid unicode escape sequence` في AAPT2 | `04aa559` |
| 5 | قفل Termux مثبَّت على إصدارات **حذفها upstream** (404 في كل المرايا) ⇒ أي بناء إصدار يفشل | `5be63a5` (CI دفعه من أداة المشروع: `proot 5.1.107.96`) |
| 6 | اختبار التثبيت الجديد يقارن base64 بما يعرضه OkHttp كـ`ByteString` hex | `f2e68b7` |

الدرس المسجَّل: **detekt/spotless لا يريان التصريف ولا الروابط** — أربع من هذه الست لا يكتشفها
إلا بناء كامل، وهذا بالضبط ما لم يكن الفرع يملكه قبل هذا التشغيل.

### 3.2 تشغيلات إضافية خضراء (ست مهام بعد إضافة مهمة الرخص)

| التشغيل | الرأس | النتيجة | ما أضافه بالضبط |
|---|---|---|---|
| `36972564153` | `f98285e` | 6/6 ✅ | أول تشغيل لمهمة الرخص؛ أظهر بنصّه أن `releaseRuntimeClasspath` **غير موجود** (`configuration 'releaseRuntimeClasspath' not found in configuration container`) → CI دفع `76eb706` |
| `36973239564` | `834a2a1` | 6/6 ✅ | بعد إصلاح السكربت: القائمة صارت النسخ **المحلولة** (`fragment:1.8.5` · `core:1.15.0` · بلا koin/room) → CI دفع `8b420fe` |
| `36974071614` | `fd8e71f` | 6/6 ✅ | أول توليد فعلي لتجميع NOTICE؛ أثبت أن الملف الملتزم كان ناقصًا (`commons-net`، 4→5 أقسام) → CI دفع `0d2c690` |
| `36969045137` · `36969826517` | `2dc2887` · `963adab` | 5/5 ✅ | توثيق عقد التدقيق (نسق 4) وإدراج إجمالي الاختبارات (1,408) |
| `36974716494` | `3798780` | 6/6 ✅ | يتحقق من شجرة `0d2c690` المولَّدة (بما فيها `commons-net` المضاف) ومن التوثيق؛ ومهمة الرخص لم تجد شيئًا لتلتزمه ⇒ **التوليد مستقر ومتكرِّر بأمان** |
| `37011764251` | `1d30c71` | 6/6 ✅ | الحملة الختامية لمحاذاة Bouncy Castle: R8 ✅ بعد إعادة قاعدة `sun.security` (الصنف المفقود صار **مُصنَّفًا بالاسم**، §3.3)، مع 1,408 اختبار وحدة وبناءي الـdebug/الـinstrumentation وlint |

### 3.3 تشخيص بالأثر — كيف سُمّي كل سبب بدل التخمين

| ما فشل | النص الذي أثبته الأثر | كيف ظهر | الإصلاح |
|---|---|---|---|
| بناءان (`debug` و`release`) بعد محاذاة Bouncy Castle | `3 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'` من `bcprov`/`bcpkix`/`bcutil` ‏1.79 | تشغيل `37010397018` على `2a53f2d`: أنماط الالتقاط القديمة لم تطابق سطر السبب (يُحتفظ بعدد محدود من التعليقات لكل خطوة)، فأُضيف التقاط يُطوي مقطع «What went wrong» كاملًا في تعليق واحد. وقد سقط أول إصدار من هذا الالتقاط صامتًا لأن `head -c` يقطع النص قبل سطر جديده فلا تُنفَّذ حلقة `read` — أُعيد إنتاجه محليًا على سجل بديل ثم صُحّح | استثناء المسار في `packaging.resources` (`7c2d13f`) — أندرويد بلا OSGi/JPMS |
| مهمة R8 في الإصدار | `ERROR: R8: Missing class sun.security.x509.X509Key (referenced from: void net.i2p.crypto.eddsa.EdDSAEngine.engineInitVerify(java.security.PublicKey))` | تشغيل `37010985210` على `7c2d13f`: قاعدة `-dontwarn sun.security.x509.**` أُزيلت **مؤقتًا** ليفصح R8 عن الصنف بنفسه | إعادة القاعدة بتعليق مبني على هذا النص (المرجع في bytecode حزمة 0.3.0 المنشورة، لا في مصدر master — ولهذا لم يجده البحث النصي) |

**الدرس:** لا تُشخَّص فشل البناء بالبحث في المصادر وحدها؛ مرجعان حقيقيان هنا (`module-info`/OSGi في جرّات منشورة، و`X509Key` في bytecode) لم يظهرا في أي بحث نصي، وظهرا فقط ببناء فعلي وبإفصاح R8.

## 4) ما لا يمكن ادعاؤه اليوم (`Cannot Verify — Environment Limitation`)

1. **البناء والاختبارات:** ✅ **لم تبقَ فجوة على الرأس المُتحقَّق منه** (`169a99c`، P2): تشغيل
   `37029566210` أثبت ست مهام (1,453 اختبار وحدة ✅ · debug ✅ · تصريف اختبارات الأجهزة ✅ · lint ✅ ·
   R8 ✅ · الفحوص العشرون ✅) كما أثبتت تشغيلات سابقة (`37020415047` على `5a19b52`): تشغيل
   `36965385897` أثبت التصريف والاختبارات وlint وR8 (§3)، وتشغيلات الرخص §3.2 أعادت الإثبات مع
   المهمة المضافة، و`37011764251` أثبت محاذاة Bouncy Castle وقاعدة R8 المعادة (§3.3). يبقى محليًا `Cannot Verify` (لا
   JDK/Gradle/SDK)، ويبقى تشغيل `Android CI` نفسه على الفرع معطَّلًا بشرط `main`/PR وصلاحية
   `actions: write` (403) — فالدليل جاء من سير `Branch Verification` المؤقّت (§3).
2. **اختبارات الأجهزة (23 اختبارًا في 9 ملفات) — تحتاج جهازًا/محاكيًا:** مكتوبة و**صُرِّفت فقط**
   في CI، ولم تُشغَّل في أي سير عمل ولا على جهاز، **ولا تُعدّ ناجحة بالاستنتاج** — تشمل E2E للمحادثة
   والاتجاه Bidi وعارض الصور والصوت ومنتقي النموذج والشاشات القانونية والمُحدِّث.
3. **التحقق الشاشي لأدوات اللمس/الكتابة/التمرير/السحب والمكالمات والمشاركة:** لا يمكن إثباته من داخل
   التطبيق نفسه (يُعلن كذلك في نتيجة كل أداة بدل ادّعاء النجاح).
   **ولا ينطبق ذلك على التدقيق:** عقده اكتمل في نسق 4 (`params_digest` + `started_at`/`ended_at`)،
   والمتبقّي منه هو **التدقيق الموحَّد** عبر الوكلاء/الجدولة/الشبكة (يحتاج تعريف مالك لمخطط مشترك).
4. **دقة ادعاءات README** (`Stable`/`Beta` للوكلاء الأربعة): ادعاءات منتج لا تُثبتها هذه البيئة.
5. **تحذير أمني على تبعية مشحونة (قرار مالك مطلوب):** `net.i2p.crypto:eddsa:0.3.0` — تبعية runtime
   يعلنها `sshj` نفسه ويقودها مباشرة لتنفيذ `ssh-ed25519` (سواء مفاتيح المضيف أو ملفات مفاتيح
   المستخدم) — تحمل CVE-2020-36843 / GHSA-p53j-g8pw-4w5f: تطويع توقيع Ed25519 في **التحقق** (يمكن
   اشتقاق توقيع صالح آخر للرسالة نفسها؛ لا يمكن تزوير توقيع لرسالة مختلفة). **0.3.0 هو آخر إصدار
   نُشر على Maven أصلًا** (لا 0.3.1)، فالإبقاء هو الخيار الوحيد غير المُخِل بالوظيفة. الحل الجذري:
   ترقية `sshj` إلى `0.41.x` (تسحب `bcprov/bcpkix 1.84` و**تُسقط eddsa** من الشجرة) — وهي ترقية
   مكتبة تحتاج جلسة SSH حقيقية للتحقق ⇒ **قرار مالك** قبل تنفيذها. *خليط BouncyCastle عولج هنا
  : وحدات المجموعة الثلاث مثبَّتة الآن على `1.79` بقيد صريح وبوابة CI تمنع الانحدار (§3.3).*

**ما يرفع الخلايا الآن:** لا شيء متبقٍّ على مستوى البناء/الاختبارات (§3)، وتبقى **جلسة جهاز
حقيقي/محاكي** هي ما يرفع `Runtime Verified` للميزات المرتبطة بالعتاد، مع لقطة سجل تُشار إليها هنا
(رقم تشغيل أو `adb`). وتشغيل اختبارات الأجهزة الـ23 نفسها يحتاج جهازًا متصلًا (`connectedGithubDebugAndroidTest`),
وهو ما لا يملكه أي سير عمل في المستودع.
