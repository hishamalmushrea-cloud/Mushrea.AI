# REMOTE_DEVICE_TEST_PLAN.md — اختبار الهاتفين، الذي ينفّذه المالك

> **الغرض:** قائمة واحدة قابلة للتنفيذ على هاتفين حقيقيين. كل ما في هذا الملف **لم يُنفَّذ عندنا**:
> CI يُثبت البناء والاختبارات الساكنة واختبارات الوحدة فقط، والفرق بين «أُثبت في CI» و«أُثبت على
> هاتف» هو سبب وجود هذا الملف.
>
> **المفردات المستخدمة في كل بند:** `Verified on real device` (أثبته تشغيلك) · `Requires user interaction`
> (يحتاج لمسة منك ولا يمكن لأي تطبيق فعلها) · `Requires special permission` · `Requires root` ·
> `OEM dependent` (يختلف بين المصنّعين) · `Android limitation` (قيد في المنصة نفسها) ·
> `Cannot verify` (لم يثبته أحد بعد).
>
> **المرجع المعماري:** `docs/architecture/REMOTE_DEVICE_ARCHITECTURE.md` · **الأدوات:** `docs/tools/TOOL_REGISTRY.md` §3.

---

## 0) قبل البدء

| الشرط | التفصيل |
|---|---|
| الهاتف ب (الهدف) | Android 11+ (Wireless debugging) — Developer options → **Wireless debugging → ON** |
| الشبكة | الهاتفان على نفس شبكة Wi‑Fi، أو على Tailscale/Headscale (المسار يُصنَّف `private_overlay`) |
| التطبيق | Mushrea Code على الهاتف أ مع الران‑تايم المحلي جاهزًا (يوفّر `adb` الحقيقي) |
| ما تسجّله | مخرجات كل أداة كما هي (JSON) + شاشة Wireless debugging على ب عند كل خطوة |

**قاعدة واحدة تحكم كل البنود:** لا يُقبل «نجح» بلا أثر — كل بند يقيس شيئًا على الهاتف ب (أمر رجع،
إعداد تغيّر، لقطة، قائمة).

---

## 1) الاقتران والتجهيز

| # | الخطوة | المتوقّع | التصنيف المتوقّع |
|---|---|---|---|
| 1.1 | على ب: **Pair device with pairing code** → اقرأ العنوان والمنفذ والرمز | يظهر عنوان ومنفذ ورمز من 6 أرقام | `Requires user interaction` |
| 1.2 | `peer_provision {serial, code:"<الرمز>", hints:["<المنفذ>:<العنوان>"], purpose:"REMOTE_CONTROL"}` | تقرير بحالة `PROVISIONED` أو `NEEDS_USER` بتعليمة **واحدة** واضحة، وبكل خطوة نتيجتها من الخمس | `Cannot verify` مسبقًا — يثبته تشغيلك |
| 1.3 | أعد الخطوة 1.2 بعد إغلاق شاشة الاقتران | `NEEDS_USER` مع «افتح التصحيح اللاسلكي…» — لا فشل غامض، ولا نجاح كاذب | `Requires user interaction` |
| 1.4 | `peer_devices` أو شاشة الأجهزة | الجهاز يظهر بـ`state=VERIFIED` **فقط** بعد أمر رجع، ومعه `readiness` و`trust` | `Cannot verify` مسبقًا |

## 2) الطريق والمنافذ

| # | الخطوة | المتوقّع | التصنيف المتوقع |
|---|---|---|---|
| 2.1 | `peer_endpoints {serial}` | نطاق كل مسار (`loopback` · `lan` · `private_overlay` · `public_internet`) وسبب اختيار الأفضل | `Cannot verify` مسبقًا |
| 2.2 | كرّر 2.1 على Tailscale | المسار يُصنَّف `private_overlay` وليس `lan` | `Requires user interaction` (تشغيل Tailscale على الجهازين) |
| 2.3 | أغلق شاشة Wireless debugging على ب، انتظر دقيقتين، `peer_reconnect {serial}` | `ready:true` أو `GAVE_UP` **بسببه**؛ وإن تغيّر المنفذ يكتشفه من جديد — بلا محاولات متّصلة | `Cannot verify` مسبقًا |
| 2.4 | أعِد تشغيل ب ثم 2.3 | المنفذ الجديد يُكتشف، أو `RequiresUserAction` صريح | `Android limitation` (المنصة تُطفئ التصحيح اللاسلكي بإعادة التشغيل) |

## 3) القدرات المقيسة (R5)

| # | الخطوة | المتوقّع | التصنيف المتوقّع |
|---|---|---|---|
| 3.1 | `peer_capabilities {serial}` (أو زر «القدرات») | قائمة `kind:name` تشمل `net:wifi` · `net:adb_wifi` · `net:airplane` مع قيمها، ومعها `bin:*` · `applet:*` · `svc:*` · `cmd:*` · `fs:*` · `priv:*` | `Cannot verify` مسبقًا |
| 3.2 | أطفئ Wi‑Fi على ب ثم أعد السبر | `net:wifi` تصبح **MISSING** مع تفصيل يقول إن القراءة `0` — لا UNKNOWN ولا «غير مدعوم» | `OEM dependent` (اسم الإعداد يختلف) |
| 3.3 | أطفئ التصحيح اللاسلكي على ب ثم أعد السبر | `net:adb_wifi` **MISSING** بتفصيل «off» | `Requires user interaction` |
| 3.4 | على هاتف لا يملك الإعداد (إن توفّر) | `net:adb_wifi` = MISSING بتفصيل «this build does not report it» — أي «غير مدعوم» لا «مغلق» | `OEM dependent` |

## 4) التخطيط العام بلا أداة جديدة

| # | الخطوة | المتوقّع | التصنيف المتوقّع |
|---|---|---|---|
| 4.1 | `peer_execute {serial, goal:"أبق الهاتف مستيقظا"}` | خطة من وصفة `provision.stay_awake`، تنفيذ، **وقراءة تحقّق** تُعيد القيمة المكتوبة | `Cannot verify` مسبقًا |
| 4.2 | `peer_execute {serial, goal:"keep wifi on while the phone sleeps"}` | `provision.wifi_alive` + تحقق بالقراءة | `OEM dependent` (الإعداد مهمَل في بعض الإصدارات) |
| 4.3 | `peer_execute {serial, recipe:"provision.wireless_debugging"}` | قراءة فقط لحالة المفتاح — **بلا كتابة** | `Cannot verify` مسبقًا |
| 4.4 | `peer_execute {serial, recipe:"provision.adb_port", parameters:{port:"5555"}}` | على هاتف **بلا root**: رفض صريح باسم القدرة الناقصة (`priv:su`) وبلا تنفيذ. على هاتف **بـroot**: كتابة الخاصية + قراءة تحقّق، ولا إعادة تشغيل تلقائية لـ`adbd` | `Requires root` |
| 4.5 | أي طلب لا وصفة له (مثال: «اقرأ آخر 20 سطرًا من سجل تطبيق معيّن») | لا فشل باسم مفقود: `shell.run` أو وصفة قريبة، مع سبب الاختيار في الخطة | `Cannot verify` مسبقًا |

## 5) الصلاحيات والتدقيق

| # | الخطوة | المتوقّع | التصنيف المتوقّع |
|---|---|---|---|
| 5.1 | شغّل عملية تغيير على ب من الوكيل | نافذة تأكيد واحدة (CONFIRM)، وعمليات القراءة بلا سؤال | `Cannot verify` مسبقًا |
| 5.2 | اقرأ سجل التدقيق | `permission ≠ execution ≠ verification`: قرار المركز، ثم أثر التنفيذ، ثم نتيجة التحقق منفصلة | `Cannot verify` مسبقًا |
| 5.3 | جرّب `purpose:"TEST"` | لا شيء يُكتب على ب (`persist*` غير مخطَّطة) | `Cannot verify` مسبقًا |

---

## 6) ما تعنيه النتيجة

* **كل بند أخضر** ⇒ يُرفع في `FEATURE_MATRIX.md` إلى `Verified on real device` بذكر تشغيلك، لا قبل ذلك.
* **بند أحمر** ⇒ لا يُجمَّل: يُسجَّل بتصنيفه (`OEM dependent` · `Android limitation` · عيب حقيقي) ويُفتح
  إصلاحه بدليل من رسالة الجهاز نفسها.
* **بند لم تجرّبه** ⇒ يبقى `Cannot verify` كما هو الآن؛ لا يُرفع لأن بندًا آخر نجح.

**ما لا يدّعيه هذا الملف:** لا يدّعي أن أيًّا من هذه البنود نُفِّذ. كل ما قيل عنه في CI هو:
«يُبنى، وتُصرَّف اختباراته، وتنجح اختبارات وحدته» — وهذا كل شيء.
