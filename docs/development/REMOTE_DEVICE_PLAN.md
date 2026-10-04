# REMOTE_DEVICE_PLAN.md — خطة Remote Device Provisioning (تدقيق + معمارية + مراحل)

> **المرجع الإطاري:** `Mushrea_Code_Remote_Device_Provisioning_Prompt.md` (جذره في المستودع).
> **قاعدة هذا الملف:** كل سطر هنا مقيس من الكود الحالي أو من مصدر رسمي، ولا يُدَّعى سلوك لم يُختبر.
> **الفرع:** `arena/01a0f971-mushrea-ai` — لا دمج ولا رفع إلى `main`.

---

## 1) CURRENT STATE — ما هو موجود فعلًا اليوم (بعد جولة التنفيذ العام)

| الطبقة | الحالة | التفصيل |
|---|---|---|
| **النقل (Peer ADB)** | ✅ كود مُصرَّف ومُختبَر بلا جهاز | `PeerAdbSession` (QR/رمز/إعادة اتصال)، `NsdPeerServiceDiscovery`، `AdbCommandLine`، `PeerAdbProvider`، `PeerAdbBridge` (بوابة + مسارات + تحقّق + سجل) — ينفّذ `adb` الحقيقي داخل الـruntime (android-tools 35.0.2-r21) |
| **القدرات** | ✅ | `CapabilityReport` بمفردات `kind:name`، سبر واحد لـ86 برنامجًا + applets + خدمات + حقائق fs/priv/pkg/debug/build + دعم رمز الخروج |
| **التخطيط والتنفيذ العام** | ✅ | `ExecutionPlanner` (41 وصفة كبيانات) + `ExecutionProviderRegistry` + `PeerAdbProvider`، وكل طلب يمر `PermissionCenter → PeerAdbBridge → Provider → adb` |
| **الصلاحيات** | ✅ | `PeerDevicePolicy` تقرّر بالأثر والسياق، و`PermissionCenter` يفرض القراءة فقط والإيقاف الطارئ مركزيًا |
| **سجل الأجهزة** | 🟡 جزئي | `PeerDeviceRegistry` يحفظ الهوية والعنوان والحالة والقدرات في `SharedPreferences` مشفَّرة، الدمج بلا فقدان، لكن: **لا اتصال ولا ثقة ولا حالة تجهيز ولا عمر جلسة** |
| **الناقل البديل** | 🟡 موجود لكن خارج المعمارية | `device/usb/` فيه ADB كامل فوق USB (`AdbClient`، `AdbSync`، توقيع المفاتيح، `enableTcpip`، `tcpShell`)، و`device/mirror/` فيه جلسة scrcpy — لكن **لا يُعرَض أيٌّ منهما كـ`ExecutionProvider`** |
| **الشبكات الخاصة** | ❌ غير موجود | لا كشف لـTailscale/Headscale/VPN، ولا تصنيف لنطاق العنوان (LAN/overlay/public)، ولا اختيار مسار حسب النطاق |
| **التجهيز (Provisioning)** | ❌ غير موجود | لا محرّك خطوات، ولا تصنيف نتائج (Completed/Skipped/RequiresUserAction/Unsupported/Failed)، ولا إعادة اتصال آلية عند تغيّر الشبكة أو المنفذ |
| **الاستمرارية بعد إعادة التشغيل** | ❌ | Wireless debugging يُطفأ نفسه على Android 14+ بعد إعادة التشغيل/الخمول، والمنفذ يتغيّر — لا شيء يعالج ذلك اليوم |

---

## 2) GAPS — الفجوات مرتّبة بالأثر الهندسي

1. **العنوان هو الهوية عمليًا**: `reconnect()` يحتاج `_adb-tls-connect` أو عنوانًا مخزَّنًا؛ لا هوية مشتقّة من المفتاح تستمر عبر تغيّر العنوان.
2. **مسار واحد للنقل**: كل التنفيذ يمر `PeerAdbProvider` (runtime adb/TCP)؛ USB موجود في الكود لكنه ليس مسارًا في المعمارية، فلا بديل عند فشل الشبكة.
3. **لا تقييم اتصال**: لا يعرف النظام إن كان الجهاز على نفس الـLAN أم على overlay خاص أم على الشبكة العامة، ولا يميّز الثقة.
4. **لا إعادة اتصال مُهندَسة**: لا backoff، ولا اكتشاف متكرر، ولا حالة جلسة.
5. **لا نجاة بعد إعادة تشغيل الجهاز**: لا قياس لما إذا كان wireless debugging ما زال مفعّلًا بعد reboot، ولا خطوة تُبقيه.
6. **لا وصف «جهّزه للعمل عن بعد»**: الأوامر موجودة والوصفات موجودة، لكن لا *خطة تجهيز* تجمعها وتصنّف نتائجها.
7. **لا أدوات وكيل للتجهيز**: 8 أدوات `peer_*` تغطي الجلسة والتنفيذ ولا تغطي «جهّز الجهاز».
8. **القدرات لا تصف الشبكة**: لا `net:` ولا `perm:`، فلا يستطيع المخطِّط أن يميّز «الشبكة الخاصة متاحة» من «لا مسار».

---

## 3) REUSABLE COMPONENTS — ما نُعيد استخدامه ولا نعيد بناءه

| المكوّن | الاستخدام في هذا العمل |
|---|---|
| `PeerAdbSession.connectAndVerify` | التنفيذ الفعلي لأي مسار TCP (LAN أو overlay) — لا ADB ثانٍ |
| `PeerAdbProvider` / `AdbCommandLine` | كل أمر على الهاتف الآخر يبقى من مكان واحد |
| `PeerCapabilityDiscovery` | مصدر الحقائق: هوية، `build:`، `debug:`, `pkg:` — والتوسعة أسئلة جديدة فيه |
| `PeerDeviceRegistry` | سجل الأجهزة الدائم: نضيف إليه حقول الاتصال والثقة والتجهيز (دمج لا استبدال) |
| `AdbClient` + `AdvProtocol/Sync/Keys` (USB) | ناقل ثانٍ حقيقي بلا ADB خارجي: تنفيذ عبر USB وتمكين TCP عبر `tcpip:` |
| `UsbDeviceAgent.enableTcpip()` | خطوة التمهيد (bootstrap) التي تحوّل قناة USB إلى قناة TCP |
| `ExecutionPlanner/ExecutionProvider/ExecutionRecipe` | أي «تجهيز» يُعبَّر عنه كوصفات قدرات وناقل، لا كمنطق جديد |
| `PermissionCenter/PeerDevicePolicy` | كل خطوة تجهيز تمر البوابة نفسها |
| `NsdManager` (Android رسميًا) | اكتشاف `_adb-tls-connect._tcp` والاقتران — بلا مكتبة mDNS خارجية |
| `ConnectivityManager`/`LinkProperties` (Android رسميًا) | نطاق الشبكة والعنوان المحلي — بلا إذن إضافي |

---

## 4) LICENSE ANALYSIS — ما فحصته من مشاريع مفتوحة، وقراره

| المشروع | الترخيص | ما فيه | القرار |
|---|---|---|---|
| **mouldybread/adb-auto-enable** (وبدائله `adbctl`، `Auto_ADB`) | MIT | تطبيق على **الجهاز الهدف** يفعّل wireless debugging عند الإقلاع عبر `adb_wifi_enabled=1` بعد منح `WRITE_SECURE_SETTINGS`، ويكتشف المنفذ العشوائي (mDNS ثم مسح مآخذ)، ثم يتصل بـ`127.0.0.1` ويرسل `tcpip:<port>` | **لا دمج كود** (Java/Android app مستقل، ولا نحتاج نسخًا): نأخذ **الفكرة** فقط، ونطبّق ما يمكن تطبيقه من جهة المضيف: كشف `debug:wireless_enabled`، تثبيت منفذ عبر `tcpip:` عبر قناة موجودة، وتوثيق أن الاستمرارية الكاملة تتطلب مكوّنًا مرافقًا على الهاتف الآخر (خطوة `RequiresUserAction` موثّقة) |
| **tailscale/tailscale-android** | BSD-3-Clause | عميل Tailscale الرسمي (Go/Kotlin، يعتمد `libtailscale`/VPN service) | **لا دمج**: إعادة استخدامه تعني `VpnService` خاصًا بنا + مكتبة أصلية ثقيلة. البديل المعماري: كشف العميل وحالته والاتصال، واستخدام **العنوان الذي يمنحه** كمسار `PRIVATE_OVERLAY` |
| **AOSP `adb_wifi` / adb** | Apache-2.0 | بروتوكول الاقتران، `tcpip:`، mDNS | الاستخدام سلوكيًا عبر `adb` الحقيقي (كما هو مُنفَّذ)، والتوثيق مقتبس |
| **Genymobile/scrcpy** | Apache-2.0 | بروتوكول التحكم/الفيديو | موجود أصلًا في `device/mirror`؛ لا شيء جديد الآن |
| مكتبات mDNS/Tailscale جديدة | — | — | **لا تُضاف**: `NsdManager` + `ConnectivityManager` يكفيان لما نحتاجه |

**الخلاصة:** لا تبعية جديدة، ولا كود منسوخ؛ الجديد معماري بالكامل، والمصادر استُخدمت للتحقق من الحقائق.

---

## 5) PROPOSED ARCHITECTURE

```text
                      ┌─────────────────────────── ProvisioningPlanner (core) ──────────────────────────┐
     facts:           │  facts + goals → خطوات مرتّبة، كل خطوة لها: ماذا، لماذا، ومتى تُصنَّف RequiresUser  │
     CapabilityReport │                                                                            │
     ConnectivityReport│                                                                           │
     device state/trust│                                                                           │
                      └───────────────────────────────┬────────────────────────────────────────────┘
                                                      ▼
                                        ProvisioningEngine (core)
                          تنفيذ الخطوات عبر ProvisioningHost + تصنيف النتيجة:
                    Completed · Skipped · RequiresUserAction · Unsupported · Failed (+ سبب + دليل)
                                                      │
                      ┌───────────────────────────────┴──────────────────────────────┐
                      ▼                                                              ▼
      PeerProvisioningHost (device)                                   ProvisioningReport (data)
      ├ الفرز: endpoint catalogue (LAN · PRIVATE_OVERLAY · USB · discover · explicit)
      ├ الاتصال: PeerTransport لكل مسار (tcp عبر PeerAdbSession · usb عبر AdbClient)
      ├ التحقق: أمر حقيقي (probe) ← لا «متصل» بلا جواب
      ├ التثبيت: مدة الجلسة (linger) + إبقاء Wi-Fi + منفذ مثبَّت عند الحاجة
      └ إعادة الاتصال: PeerReconnectionManager (backoff + اكتشاف + هوية)
                                                      │
                                                      ▼
                                  PeerAdbBridge → Provider → adb/UsbClient → الهاتف
```

**قواعد التصميم المُلزِمة:**

1. **النقل قابل للتبديل**: كل ناقل = `PeerTransport` يعلن `id/kind/scopes/isAvailable()/endpoint()`، و`ConnectivityProvider` يعلن ما يستطيع اكتشافه. إضافة Tailscale لاحقًا = مزوّد جديد يقرأ عنوانه، لا تعديل في المخطِّط.
2. **الهوية ليست العنوان**: `PeerIdentityKey` مشتقّة من بصمة مفتاح المضيف + `ro.serialno`/`adbSerial`، والمخزَّن يحمل *قائمة عناوين* لا عنوانًا واحدًا.
3. **النطاق معروف دائمًا**: `NetworkScope` = `LOOPBACK · LOCAL · LAN · PRIVATE_OVERLAY · PUBLIC_INTERNET · UNKNOWN`، والاختيار يفضّل الأقرب/الأسلم ثم يشرح السبب.
4. **لا خطوة تجهيز بلا بوابة**: كل خطوة تغيير تمر `PermissionCenter`؛ القراءة فقط لا.
5. **لا ادّعاء**: `PeerDeviceState` تبقى كما هي (DISCOVERED…VERIFIED)، ويُضاف `DeviceReadiness` للتجهيز (PROVISIONED/READY_FOR_REMOTE) بشرط دليل مقيس.
6. **التصنيف لا الفشل**: نتيجة أي خطوة واحدة من الخمس، و`RequiresUserAction` ليست خطأ بل خطوة بانتظار المستخدم مع نصّ واحد واضح.

---

## 6) PHASE PLAN — المراحل والتنفيذ

| المرحلة | المحتوى | الحالة |
|---|---|---|
| **R0 — Audit + architecture** | هذا الملف + `REMOTE_DEVICE_ARCHITECTURE.md` | ✅ (هذا الملف) |
| **R1 — Connectivity model** | `NetworkScope` · `Endpoint` · `ConnectivityProvider` + تقييم نقي قابل للاختبار | ⏳ |
| **R2 — Endpoint catalogue + transports** | مصادر: mDNS · مخزَّن · صريح · USB-loopback؛ نواقل: `tcp` و `usb` بتمهيد `tcpip:` | ⏳ |
| **R3 — Provisioning engine** | نموذج الخطوات/النتائج + مخطِّط + محرّك + تصنيف + تقرير | ⏳ |
| **R4 — Persistence + reconnect** | لِنجر الجلسة، إعادة الاتصال بـbackoff، الاستمرار عبر تغيّر العنوان/المنفذ، وتحقق الهوية قبل التبني | ⏳ |
| **R5 — Provisioning recipes + capability facts** | وصفات `provision.*` (إبقاء Wi-Fi، منع الخمول، منفذ مثبَّت، لِنجر) + قدرات `net:`/`perm:` | ⏳ |
| **R6 — Tool + UI surface** | `peer_provision` + حالة التجهيز في شاشة الأجهزة | ⏳ |
| **R7 — Docs + tests + CI** | الوثائق السبعة + اختبارات + CI | ⏳ |
| **R8 — Real two-device** | خطة اختبار مكتوبة والتنفيذ على المالك (`Cannot Verify` عندنا) | ⏳ |

بقية المراحل التي طلبها الملف (Multi-device Fleet الكامل، ملف/تطبيقات/شاشة عن بعد كوحدات مستقلة، تعدد VPNs) **مغطّاة معماريًا** بنفس المزوّدات والوصفات، ولا تحتاج مرحلة مستقلة: أي واحدة منها تُضاف كمزوّد/وصفة.

---

## 7) RISKS — المخاطر وضوابطها

| الخطر | الضابط |
|---|---|
| تمكين `tcpip:` يفتح قناة أضعف من TLS اللاسلكي فيزيائياً | خطوة صريحة بتأكيد قوي، وتُوصف بأنها `LAN` فقط، ولا تُشغَّل تلقائيًا بلا مسار مشفَّر |
| كتابة إعدادات لا يسمح بها المصنّع | كل خطوة تُقاس نتيجتها؛ الفشل يُصنَّف `Unsupported` أو `OEM dependent` بدليل الرسالة، لا يُدَّعى نجاح |
| اكتشاف متكرر يستهلك البطارية على **هذا** الهاتف | backoff أسّي بسقف، ولا polling متصل؛ الاكتشاف يعمل فقط عند الطلب أو عند تغيّر شبكة |
| تثبيت هوية خاطئة بعد تغيّر العنوان | التبني لا يحدث إلا بعد مطابقة الهوية المشتقّة (سيريال/بصمة) أو تأكيد صريح من المستخدم |
| إضافة تعقيد بلا فائدة | لا تبعيات جديدة، ولا ADB ثانٍ: USB يستخدم `AdbClient` الموجود، والتشغيل يبقى عبر `PeerAdbProvider` |

---

## 8) TEST PLAN

**اختبارات وحدة (تُنفَّذ في CI):**
- تصنيف النطاق لـ IPv4/IPv6 (loopback، link-local، RFC1918، CGNAT/Tailscale 100.64/10، عام).
- ترتيب المسارات: القريب/الخاص قبل العام، والمسار المُثبَت قبل المُخمَّن، والسبب مكتوب.
- الهوية: بذرة مشتقّة مستقرة عبر تغيّر العنوان، ومتغيّرة عند تغيّر الجهاز.
- مخطِّط التجهيز: خطوات مبنية من الحقائق (لا `if device == X`)، وكل خطوة لا يمكن أتمتتها تُصنَّف `RequiresUserAction` بنصّ واحد.
- المحرّك: تنفيذ بترتيب، تخطّي عند غياب الشرط، إيقاف عند `Failed`، وعدم تجاوز البوابة (يُعَدّ عدد مرات البوابة).
- إعادة الاتصال: backoff (فترات متزايدة بسقف)، توقّف بعد النجاح، وعدم قبول جهاز بهوية مختلفة.

**اختبارات تحتاج جهازين (`Cannot Verify` عندنا):** الاقتران QR/رمز على أجهزة حقيقية، ظهور منفذ جديد بعد reboot، عمل `tcpip:` عبر USB على مصنّعين مختلفين، استمرار الاتصال عبر overlay، وسلوك Wi-Fi sleep policy فعليًا.
