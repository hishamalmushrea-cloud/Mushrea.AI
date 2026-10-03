# REMOTE_DEVICE_ARCHITECTURE.md — Remote Device Provisioning & Persistent Remote ADB

> الحالة: **Phase R1–R7 منفَّذة** (connectivity + provisioning + reconnect + tools + docs).
> التدقيق والخطة الكاملة: `docs/development/REMOTE_DEVICE_PLAN.md`.
> الإطار الأصلي: `Mushrea_Code_Remote_Device_Provisioning_Prompt.md` (جذر المستودع).

هذا المستند يشرح **ما بُني، وبأي قاعدة، وما لا يدّعيه**. القاعدة الأولى فيه: كل عبارة "يعمل" مصدرها قياس
(تجميع/اختبار/تشغيل حقيقي)، وكل ما لم يُقس بعد مكتوب صراحةً في §7.

---

## 1. المشكلة التي يحلّها

قبل هذه المرحلة كان الاتصال بهاتف آخر يعمل، لكنه يعمل **مرة واحدة بيد المستخدم**:

* المُعرّف كان العنوان عمليًا: `reconnect()` يحتاج إعلان mDNS أو عنوانًا مخزّنًا من الجلسة الماضية؛
  وعندما يتغيّر المنفذ (وهو يتغيّر في كل مرة تُشغَّل فيها Wireless debugging) لا يبقى سوى إعلان جديد.
* لا شيء يعرف **أين** يعيش العنوان: هل `100.101.102.103` شبكة محلية أم نفق خاص أم شبكة عامة؟
* لا شيء يميّز "اتصلنا سابقًا" من "لا نعرف هذا الجهاز" — لا ثقة (trust)، ولا مُعرّف ثابت.
* لا شيء يعيد الجهاز بعد أن ينام أو ينتقل، ولا شيء يميّز «الاتصال أعيد» عن «أمر حقيقي رجع».
* لا خطوة واحدة تسمّى «جهّز هذا الهاتف للعمل عن بعد»، ولا تصنيف لما يمكن ولما يستحيل تلقائيًا.

## 2. المكوّنات الجديدة (ومكانها في الطبقات)

| المكوّن | الطبقة | القاعدة التي يمثّلها |
|---|---|---|
| `core/connectivity/NetworkScope.kt` | core | العنوان يحمل معنى أمنيًا: loopback / link-local / LAN / overlay خاص / عام / مجهول |
| `core/connectivity/Endpoint.kt` | core | `Endpoint` = كيف نصل، و`EndpointSource` = ما الدليل؛ **لا هوية في العنوان** |
| `core/connectivity/Connectivity.kt` | core | `ConnectivityProvider` (حقائق الجهاز) و`EndpointProvider` (مسارات الجهاز الآخر) — نقاط توصيل لا كود جديد |
| `core/provisioning/ProvisioningModels.kt` | core | `DeviceReadiness` (سلّم مقيس) · `ProvisioningStep` · `StepOutcome` (خمس نتائج) · `ProvisioningFacts` |
| `core/provisioning/ProvisioningPlanner.kt` | core | الخطة تُبنى من الحقائق، لا من اسم الجهاز |
| `core/provisioning/ProvisioningEngine.kt` | core | الترتيب والتصنيف؛ التوقف عند المستخدم؛ الحالة النهائية من القياس لا من النية |
| `core/peer/PeerTrust.kt` | core | الثقة مستوى صريح (UNKNOWN/TOFU/USER_APPROVED/REVOKED) لا أثر جانبي للاقتران |
| `core/peer/PeerIdentityDigest.kt` | core | هوية مشتقّة (SHA‑256 مختصرة) تصمد لتغيّر العنوان وتتغيّر لتغيّر الجهاز |
| `device/provisioning/DeviceProvisioningHost.kt` | device | تنفيذ الخطوات: mDNS، `adb pair`، `connectAndVerify`، السبر، وإعدادات الاستمرارية |
| `device/provisioning/PeerProvisioningService.kt` | device | نقطة الدخول الواحدة: `provision` · `reconnect` · `endpoints` |
| `device/bridge/PeerReconnectionManager.kt` | device | سلّم إعادة الاتصال + backoff مُحدَّد السقف، وحماية الهوية |
| `device/connectivity/*` | device | `AndroidNetworkStateProvider` (الشبكات والنطاقات) + مزوّدا المسارات (مخزَّن، `adb devices -l`) |
| `device/PeerExecutor.kt` (إضافة) | device | ثلاث أدوات: `peer_provision` · `peer_reconnect` · `peer_endpoints` |

**الطبقات:** لا شيء في `core/*` يستورد من `device/*` — و`check_architecture.py` يفرض ذلك.

## 3. كيف يعمل التجهيز (خطوة بخطوة، كما نُفِّذ)

```
طلب: «جهّز الهاتف X للعمل عن بعد»
  │
  ├─ FACTS  ← DeviceProvisioningHost.facts()
  │      readiness (مقيس) · trust · capabilities (سبر سابق) · connectivity · routes
  │      routes = ConnectivityResolver = (mDNS/adb-live) + (رموز مخزَّنة) + (عناوين الطلب)
  │      مرتّبة: LOOPBACK ← LOCAL_LINK ← LAN ← PRIVATE_OVERLAY ← PUBLIC ← UNKNOWN
  │
  ├─ PLAN   ← ProvisioningPlanner.plan()
  │      (pair إن لم تكن الثقة تكفي) → connect → verify → capabilities
  │      → [تعطيل مطلوب من المستخدم إن لا مسار] → persist* → test-execution → register
  │
  └─ RUN    ← ProvisioningEngine
         كل خطوة → StepOutcome ∈ {Completed, Skipped, RequiresUserAction, Unsupported, Failed}
         الحقائق تُعاد قراءتها بعد كل خطوة تغيّر الواقع
         الحالة النهائية: PROVISIONED · NEEDS_USER · PARTIAL · FAILED
         readiness النهائي يُشتق من القياس، ويُخزَّن في سجل الجهاز
```

**الخطوات التي تُكتب على الجهاز الآخر** (إبقاء Wi‑Fi صاحيًا، إبقاء الشاشة أثناء الشحن، منفذ TCP ثابت)
لا تُنفَّذ مباشرةً: كل واحدة تُرسل كـ`ExecutionRequest` عبر `PeerAdbBridge`، أي **عبر Permission Center
ثم المزوّد ثم adb**. لا يوجد في مسار التجهيز أي طريق ثانٍ إلى adb.

## 4. كيف تُختار الطريق (وسبب كل اختيار)

الترتيب قاعدة لا تخمين، والسبب نصّ يُطبع للمستخدم:

* **النطاق أولًا**: `127.0.0.1` قبل الشبكة المحلية، وقبل النفق الخاص، وقبل العنوان العام.
* **الدليل ثانيًا**: ما سُلِّم من قناة حيّة (`HANDOVER`) قبل ما أُعلن الآن (`ANNOUNCED`)، وقبل ما نَـذكره
  (`REMEMBERED`)، وقبل ما سمّاه المستخدم (`EXPLICIT`).
* **العام مرفوض افتراضيًا**: مسار على عنوان عام لا يدخل الخطة إلا إذا طلب المستدعي `allow_public`،
  ويُذكر في ملخّص الخطة أنه *رُفض عمدًا* — وهذا يختلف عن «لا يوجد مسار».
* **الملاحظة الحرجة**: نطاقات `100.64.0.0/10` (Tailscale/Headscale) وواجهات `tun*/wg*/tailscale*` تُصنَّف
  `PRIVATE_OVERLAY` لا `LAN`: هي خاصة، لكنها خاصة **لأن نفقًا يوجّهها**، وإخفاء ذلك يُفقد المستخدم معلومة
  يحتاجها (سعة الشبكة، من يديرها، وهل هي مشفّرة من الطرف إلى الطرف).

## 5. الاستمرارية وإعادة الاتصال

* **الهوية**: `identityKey` = SHA‑256 مختصر فوق السيريال + (model, manufacturer, androidVersion, sdk, abi).
  يُكتب عند أول سبر ناجح، ويُقارن قبل تبنّي أي مسار بعد إعادة الاتصال.
* **العناوين قائمة لا عنوان**: `PeerDevice.knownEndpoints` (حتى 8، الأحدث أولًا)؛ فشل مسار لا يمحو البقية.
* **سلّم الإعادة**: `DISCOVERING → CONNECTING → VERIFYING → READY` (أو `FAILED_ATTEMPT` لكل مسار،
  ثم `GAVE_UP` مع السبب). `ready` تعني أن **أمرًا رجع**، لا أن مقبسًا فُتح.
* **الانتظار**: `ReconnectPolicy.delayBefore(n)` = 0, 2s, 4s, 8s… بسقف 60s، وبعدد محاولات محدود.
  لا polling متّصل ولا حلقة لا تنتهي — البطارية والشبكة هما سبب وجود السقف.

## 6. ما يستحيل على أي تطبيق (وهذا ليس نقصًا في التنفيذ)

| الطلب | الحقيقة | ما يفعله النظام |
|---|---|---|
| «شغّل Wireless debugging على الهاتف الآخر من هنا» | لا واجهة عامة في Android؛ الطرق التي تفعل ذلك تحتاج تطبيقًا على الهدف مُنِح `WRITE_SECURE_SETTINGS` (إذن على مستوى التوقيع أو عبر ADB موجود أصلًا) | خطوة `ENABLE_REMOTE_ACCESS` غير آلية مع **تعليمة واحدة**، وتُصنَّف `RequiresUserAction` |
| «اعرف منفذ Wireless debugging العشوائي عبر API» | لا يُكشف (`mPort`/`AdbConnectionInfo` ليست للقراءة العامة) | البحث عبر إعلان mDNS، أو `adb devices -l`، أو لقطة مخزَّنة من جلسة سابقة |
| «ابقَ متصلًا بعد إعادة تشغيل الهاتف» | Wireless debugging يُطفأ نفسه عند إعادة التشغيل/الخمول (سياسة المنصة) | مسار `PERSIST` يكتب ما هو مسموح (Wi‑Fi صاحٍ، الشاشة أثناء الشحن، منفذ مثبَّت عند توفّر `svc`)، والباقي `RequiresUserAction` موثّق |
| «امنح إعدادات عبر ADB من تطبيقنا داخل التطبيق» | `pm grant` يعمل فقط على **الهاتف نفسه** لأن ADB يعمل عليه؛ لا يستطيع هاتف أن يمنح إذنًا لهاتف آخر إلا عبر قناة ADB قائمة | خطوة USB الحالية (`tcpip:`) تبقى كما هي، والتمكين الكامل للهدف يبقى بيد المستخدم |

## 7. ما لم يُثبَت بعد (تصنيف صريح)

| البند | التصنيف |
|---|---|
| البناء والاختبارات الساكنة والوحدة | **Verified by CI** (run الالتزام في `DEVELOPMENT_LOG.md`) |
| سلّم إعادة الاتصال، الترتيب، حماية الهوية، الدمج في السجل | **Verified by unit tests** |
| قراءة الشبكات على جهاز حقيقي (`AndroidNetworkStateProvider`) | **Cannot verify** — يحتاج جهازًا حقيقيًا (لا يمكن محاكاة كل مسار Wi‑Fi/نفق في اختبار وحدة) |
| الاقتران/الاتصال/الكتابة على الهاتف الثاني فعليًا | **Cannot verify — Requires two devices**؛ السكربت في §8 |
| `settings put` على مصنّعين مختلفين | **OEM dependent**؛ الفشل يُصنَّف `Unsupported` مع رسالة الجهاز |
| إبقاء الاتصال بعد reboot هدفٍ حقيقي | **Android limitation** + **Requires user interaction** (المنصة تُطفئ Wireless debugging) |

## 8. خطة اختبار الجهازين (ينفّذها المالك — لا ندّعيها)

1. على الهاتف ب: Developer options → Wireless debugging → **ON**، و`Pair device with pairing code` → اقرأ العنوان والمنفذ والرمز.
2. `peer_provision {serial, code, hints:["<المنفذ>:<العنوان>"], purpose:"REMOTE_CONTROL"}` → يجب أن ينتهي
   `PROVISIONED` (أو `NEEDS_USER` مع تعليمة واحدة إن كان هناك ما يحتاج لمسة).
3. `peer_endpoints {serial}` → يجب أن يُظهر نطاق المسار (`lan` مثلًا) وسبب اختياره؛ على Tailscale يجب أن يظهر `private_overlay`.
4. أغلق شاشة Wireless debugging على ب، انتظر دقيقتين، ثم `peer_reconnect {serial}` → يجب أن يعود `ready:true`
   أو `GAVE_UP` بسببه (لا نجاح كاذب).
5. أعِد تشغيل الهاتف ب ثم أعد 4 → السجل المتوقّع: منفذ جديد، إما إعادة اكتشاف عبر mDNS أو `RequiresUserAction` صريح.
6. جرّب `purpose:"TEST"` → يجب ألا يكتب شيئًا على ب (`persist*` غير مخطَّطة).

## 9. القواعد التي تحكم هذا العمل

1. **لا ناقل باسم في المخطِّط**: كل ناقل مزوّد (`PeerTransport`/`ExecutionProvider`) يعلن ما يوفّره.
2. **لا هوية في عنوان**: العنوان تلميح، والهوية مشتقّة ومقارَنة.
3. **لا ادّعاء بلا قياس**: `ready`/`VERIFIED`/`PROVISIONED` كلها مشروطة بقياس، وما لم يُقس يُكتب في §7.
4. **لا مسار يلتف على البوابة**: كل تغيير على الجهاز الآخر يمر `PermissionCenter → PeerAdbBridge → Provider → adb`.
5. **لا حدّ صناعي**: أُضيفت ثلاث أدوات؛ وغياب أداة ليست نهاية الطريق — البحث دائمًا عبر القدرات والوصفات.
6. **لا تبعيات جديدة**: كل ما بُني يستخدم `NsdManager` و`ConnectivityManager` و`java.security` الموجودين.
