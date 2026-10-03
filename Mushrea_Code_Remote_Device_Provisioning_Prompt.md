# مهمة: بناء منظومة Remote Peer ADB كاملة وقابلة للتوسع داخل Mushrea Code

المشروع: `hishamalmushrea-cloud/Mushrea.AI`

الفرع الحالي يحتوي بالفعل على تنفيذ Peer ADB وQR وmDNS وADB runtime وDynamic Capability / General Execution Architecture.

أريد منك الآن **تطوير هذه المنظومة إلى نظام Remote Device Provisioning & Persistent Remote ADB كامل وقابل للتوسع**.

لا أريد مجرد ميزة `adb connect`.

أريد أن أصل في النهاية إلى تجربة مثل:

> «اتصل بهذا الهاتف وجهّزه للعمل عن بعد.»

ثم يتولى Mushrea Code اكتشاف أفضل طريقة متاحة، وإجراء الإعدادات الممكنة، والتحقق منها، ثم إنشاء قناة اتصال قابلة لإعادة الاستخدام.

---

# المبادئ الأساسية

- لا تعيد بناء المكونات الموجودة إلا عند وجود عيب معماري واضح.
- لا تجعل قائمة الأدوات أو الأوامر الحالية سقفًا للنظام.
- اجعل Transport وProvider وCapability قابلة للاكتشاف والتوسعة.
- لا تعتمد على IP أو port كهوية.
- لا تعرض ADB مباشرة للإنترنت؛ استخدم ADB TLS أو شبكة خاصة/overlay عند الحاجة.
- لا تتجاوز مصادقة Android أو الحواجز التي يفرضها النظام.
- إذا احتاجت خطوة إلى تفاعل المستخدم، اطلب أقل تدخل ممكن ثم أكمل.
- لا تدّعِ نجاحًا لم يُختبر فعليًا.
- لا تغيّر `main` ولا تدمج إليه.
- اعمل على الفرع الحالي.
- لا تستخدم Flutter أو NDK أو خدمات مدفوعة إلا إذا أثبت التحليل أن ذلك ضروري فعلًا.

---

# 1. الهدف النهائي

عندما أعطي الوكيل جهاز Android مستهدفًا، يجب أن يحاول الوصول إليه وتجهيزه للعمل عن بعد بأكبر قدر ممكن من الأتمتة.

```text
Target Android Device
        │
        ▼
Connection Discovery
        │
        ├── Existing Peer ADB
        ├── Wireless Debugging QR
        ├── Wireless Debugging pairing code
        ├── mDNS
        ├── Known IP/port
        ├── USB ADB
        ├── Local network
        ├── Tailscale
        ├── Headscale
        ├── VPN/private network
        └── Future transports
        │
        ▼
Provisioning Engine
        │
        ├── Detect Android/API
        ├── Detect ADB
        ├── Detect Wireless Debugging
        ├── Detect network
        ├── Detect available privileges
        ├── Detect installed components
        ├── Detect available routes
        └── Detect what can be automated
        │
        ▼
Remote Device Setup
        │
        ├── Pair
        ├── Connect
        ├── Verify identity
        ├── Establish persistent route
        ├── Configure supported remote connectivity
        ├── Install optional companion components where appropriate
        ├── Configure supported persistence mechanisms
        └── Verify recovery after reconnect/reboot
        │
        ▼
Persistent Peer
        │
        ▼
Dynamic Capability Discovery
        │
        ▼
General Execution Engine
        │
        ▼
AI Agent
```

---

# 2. لا تجعل التصميم مقيدًا بمجموعة أوامر ثابتة

لدينا بالفعل:

`ExecutionRequest`

`ExecutionPlanner`

`ExecutionProvider`

`CapabilityReport`

`PeerAdbProvider`

`PeerAdbBridge`

`PermissionCenter`

وسّع هذه المعمارية لتصبح **General Execution / Dynamic Capability Platform**.

لا تجعل النظام يعتمد على قائمة مغلقة من العمليات.

يجب أن يستطيع اكتشاف:

- ما الذي يستطيع الجهاز فعله؟
- ما الذي يستطيع ADB فعله؟
- ما الأدوات الموجودة؟
- ما البرامج الموجودة؟
- ما الأوامر المتاحة؟
- ما الخدمات الموجودة؟
- ما طرق الاتصال المتاحة؟
- ما المسارات الشبكية المتاحة؟
- ما الصلاحيات المتاحة؟
- ما القيود التي يفرضها Android؟

ثم يقرر الـAI كيف يستخدم القدرات المتاحة.

---

# 3. Generic Command / Script Execution

أريد دعم تنفيذ عام، وليس فقط العمليات المعرفة مسبقًا.

يجب أن يدعم النظام عند الحاجة:

```text
SHELL
EXEC
SCRIPT
PUSH
PULL
INSTALL
UNINSTALL
FORWARD
REVERSE
LOGCAT
SCREENSHOT
INPUT
UIAUTOMATOR
PACKAGE_MANAGER
ACTIVITY_MANAGER
SETTINGS
DUMPSYS
GETPROP
PROBE
CUSTOM
```

لكن لا تجعل هذه القائمة سقفًا معماريًا.

إذا اكتشف النظام أداة أو قدرة جديدة على الجهاز، يجب أن يستطيع استخدامها من خلال طبقة عامة.

مثلًا:

```text
python3
busybox
toybox
curl
wget
git
sqlite3
ffmpeg
termux
sh
bash
```

إذا كانت موجودة ومسموحًا باستخدامها، يجب أن تظهر كقدرات قابلة للاستخدام.

---

# 4. Capability Discovery

طوّر Capability Discovery ليكون عميقًا.

## الجهاز

```text
manufacturer
model
device
product
Android version
API level
build
ABI
kernel
security patch
```

## ADB

```text
ADB version
transport
TLS
pairing
authorized state
shell
root availability
```

## البرامج

اكتشاف ما يمكن اكتشافه بأمان:

```text
which
command -v
pm
am
cmd
settings
dumpsys
toybox
sh
su
python
python3
busybox
curl
wget
git
sqlite3
ffmpeg
```

ولا تفترض أن أي أداة موجودة.

---

# 5. Device Capability Profile

أنشئ:

```text
DeviceCapabilityProfile
```

لكل جهاز.

مثال:

```text
Android 15
Xiaomi
ARM64
Wireless Debugging
ADB TLS
No root
toybox
no python
no busybox
Tailscale installed
Headscale connected
```

ويجب أن يستخدم الـAI هذا الملف لتحديد الخطة.

---

# 6. Provisioning Engine

أنشئ:

```text
DeviceProvisioningEngine
```

وظيفته:

```text
DISCOVER
ASSESS
PLAN
PROVISION
VERIFY
REGISTER
MONITOR
RECOVER
```

مثال:

```text
1. اكتشف الجهاز
2. تحقق من الهوية
3. تحقق من Android
4. تحقق من ADB
5. تحقق من Wireless Debugging
6. تحقق من الشبكة
7. تحقق من mDNS
8. تحقق من المسارات البديلة
9. اختر أفضل Transport
10. نفّذ خطوات الإعداد الممكنة
11. تحقق
12. سجل النتيجة
```

---

# 7. دراسة adb-auto-enable

افحص أولًا المشروع:

`mouldybread/adb-auto-enable`

والمشاريع/الأكواد مفتوحة المصدر المرتبطة به.

حلّل:

```text
license
architecture
dependencies
ADB implementation
boot handling
WRITE_SECURE_SETTINGS usage
port management
network discovery
mDNS
key storage
foreground service
Android compatibility
known limitations
security implications
```

ثم قرر هندسيًا:

```text
reuse
adapt
extract components
rewrite portions
or implement an equivalent subsystem
```

إذا كان الترخيص يسمح بالدمج، ادمج فقط ما نحتاجه فعليًا.

لا تضف dependency غير ضرورية.

---

# 8. لا تعتمد على 5555 فقط

5555 ليس هوية الجهاز ولا يجب أن يكون أساس التصميم.

دعم:

```text
dynamic wireless debugging port
mDNS
pairing service
connect service
known endpoint
private-network endpoint
Tailscale IP
Headscale IP
VPN endpoint
USB
future transports
```

إذا أمكن تثبيت منفذ معين على جهاز معين فليكن خيارًا، وليس شرطًا.

---

# 9. Remote Network Layer

أريد طبقة:

```text
RemoteConnectivityProvider
```

يمكنها دعم:

```text
LAN
Wi-Fi
Ethernet
VPN
Tailscale
Headscale
other private overlay networks
future transports
```

الهدف:

> الجهازان يمكن أن يكونا على شبكتين مختلفتين ومع ذلك يستطيع Mushrea Code الوصول إلى الهاتف المستهدف عبر شبكة خاصة عندما يكون ذلك مدعومًا.

---

# 10. Headscale / Tailscale

ادرس دمج Headscale/Tailscale كطبقة شبكة خاصة.

لا تفترض أن التطبيق يستطيع تثبيت أو التحكم في تطبيقات خارجية دون تفاعل المستخدم.

بدل ذلك:

```text
Detect Tailscale
Detect Headscale
Detect connectivity
Detect assigned address
Detect routes
Detect reachability
```

إذا احتاج المستخدم إلى تثبيت عميل:

```text
Guide user
Deep-link where possible
verify installation
continue automatically
```

إذا كانت هناك API أو Intent أو URI رسمية، استخدمها.

لا تعتمد على undocumented hacks.

---

# 11. لا تعرض ADB مباشرة للإنترنت

لا تجعل التصميم:

```text
Internet
   ↓
5555
   ↓
ADB
```

التصميم المفضل:

```text
Internet
   ↓
Encrypted Private Overlay
   ↓
Headscale/Tailscale/VPN
   ↓
Target Device
   ↓
ADB
```

أو:

```text
LAN
 ↓
ADB TLS
 ↓
Target
```

ويجب أن يعرف النظام الفرق بين:

```text
LOCAL
LAN
PRIVATE_OVERLAY
PUBLIC_INTERNET
```

ويختار المسار المناسب.

---

# 12. Persistent Device Identity

لا تعتمد على:

```text
IP
port
hostname
```

كهوية.

استخدم ما هو متاح مثل:

```text
ADB serial
device fingerprint
ADB identity/public-key-derived identity
model
manufacturer
```

وأنشئ:

```text
PersistentPeerIdentity
```

بحيث إذا تغير:

```text
IP
port
Wi-Fi
network
```

يمكن للنظام إعادة اكتشاف الجهاز عندما تسمح آلية الاتصال بذلك.

---

# 13. Reconnection Engine

أنشئ:

```text
PeerReconnectionManager
```

وظيفته:

```text
DISCONNECTED
   ↓
DISCOVER
   ↓
IDENTIFY
   ↓
RECONNECT
   ↓
VERIFY
   ↓
READY
```

مع backoff ذكي.

لا تستخدم polling عدوانيًا.

---

# 14. Recovery Engine

إذا حدث:

```text
Wi-Fi changed
IP changed
port changed
router changed
ADB restarted
device rebooted
VPN changed
Tailscale changed
Headscale changed
```

يجب أن يحاول النظام:

```text
rediscover
reconnect
verify
restore session
```

إذا كان ذلك ممكنًا.

---

# 15. One-command Provisioning

أريد في النهاية واجهة يستطيع الـAI فهمها مثل:

> اتصل بهذا الهاتف وجهزه للعمل عن بعد.

فيحوّلها إلى:

```text
DISCOVER DEVICE
IDENTIFY DEVICE
ASSESS
CHECK CONNECTION
CHECK ADB
CHECK WIRELESS DEBUGGING
CHECK PRIVATE NETWORK
SELECT TRANSPORT
PAIR
CONNECT
VERIFY
DISCOVER CAPABILITIES
CONFIGURE PERSISTENCE
REGISTER DEVICE
TEST REMOTE EXECUTION
REPORT
```

ولا تنفذ خطوات غير ممكنة.

بل صنّف النتيجة إلى:

```text
Completed
Skipped
Requires User Action
Unsupported
Failed
```

---

# 16. AI Planning

اجعل الـAI لا يعرف مسبقًا كل طريقة اتصال.

بدل:

```text
if phone == x
    use method y
```

استخدم:

```text
Capability discovery
+
Planner
+
Providers
+
Fallbacks
```

مثال:

```text
إذا كان USB متاحًا:
    استخدم USB

وإلا إذا كان Wireless Debugging متاحًا:
    استخدم ADB pairing

وإلا إذا كان الجهاز معروفًا على الشبكة:
    حاول discovery

وإلا إذا كان private overlay موجودًا:
    استخدمه

وإلا:
    اطلب من المستخدم خطوة واحدة فقط لازمة
```

---

# 17. Dynamic Provider Architecture

اجعل إضافة Transport جديد مستقبلًا سهلة:

```text
ExecutionProvider
ConnectivityProvider
ProvisioningProvider
CapabilityProvider
DeviceDiscoveryProvider
```

بحيث يمكن لاحقًا إضافة:

```text
UsbProvider
PeerAdbProvider
SshProvider
HttpProvider
WebSocketProvider
TailscaleProvider
HeadscaleProvider
FutureProvider
```

دون إعادة تصميم PermissionCenter.

---

# 18. Plugins / Extensions

ادرس إمكانية جعل Mushrea Code قابلًا للتوسع عبر:

```text
Provider
Plugin
Script
Capability Adapter
Tool Definition
```

بحيث يمكن إضافة قدرة مستقبلية دون إعادة كتابة النظام الأساسي.

لكن أي تنفيذ يجب أن يبقى ضمن معمارية التنفيذ العامة الموجودة.

---

# 19. لا تجعل AI مقيدًا بقائمة الأدوات الحالية

لا أريد أن يكون:

```text
96 tools = maximum capability
```

بل:

```text
96 tools = currently registered capabilities
```

ويجب أن يستطيع النظام عند الإمكان:

```text
discover capability
create execution plan
generate script
use existing runtime
use discovered binaries
use provider
install optional dependency where appropriate
execute
verify
register new capability
```

أي أن عدد الأدوات ليس سقف النظام.

---

# 20. Transport / Provider Integrity

لا تجعل الـAI يفتح Transport جديدًا بطريقة تتجاوز المعمارية.

كل Transport جديد يجب أن يصبح Provider.

وكل تنفيذ يجب أن يصبح ExecutionRequest.

المسار العام:

```text
AI
 ↓
ExecutionPlanner
 ↓
PermissionCenter
 ↓
DeviceBridge
 ↓
Provider
 ↓
Transport
 ↓
Target
```

---

# 21. Remote Command Engine

يدعم:

```text
single command
command chain
script
multi-step plan
parallel operations
conditional operations
retry
timeout
rollback where possible
verification
```

مثال:

```text
detect → install → configure → restart → verify
```

---

# 22. Remote File Engine

دعم عام عند السماح به:

```text
list
stat
read
write
push
pull
delete
rename
mkdir
permissions
archive
extract
```

مع عدم افتراض مسارات معينة.

---

# 23. Remote App Management

دعم قدرات مثل:

```text
list packages
inspect package
install APK
uninstall
enable
disable
start
stop
force-stop
clear data
grant/revoke where Android permits
```

واجعلها قدرات ضمن Execution Engine، لا سقفًا ثابتًا من الأدوات.

---

# 24. Remote Diagnostics

يستطيع الـAI جمع ما تسمح به البيئة مثل:

```text
logcat
dumpsys
getprop
packages
processes
network state
battery
storage
memory
CPU
Android version
security patch
ADB state
VPN state
Tailscale state
```

ثم بناء تقرير تشخيصي.

---

# 25. Remote Screen / Interaction

استكشف دعم:

```text
screenshot
screen recording
input
tap
swipe
keyevent
UIAutomator
```

وإذا كان هناك طريق عملي لدمج scrcpy أو بروتوكول مشابه، صمّم Provider له بدل ربطه مباشرة بالـADB bridge.

---

# 26. Device Fleet

لا تصمم النظام لهاتف واحد.

دعم:

```text
Device A
Device B
Device C
...
```

وكل طلب يحتوي:

```text
targetDevice
```

صريحًا.

مثال:

> نفذ هذا الأمر على الهاتف Xiaomi وليس Samsung.

---

# 27. Device Registry

أنشئ سجلًا دائمًا:

```text
Device
Identity
LastSeen
Transport
Endpoint
Capabilities
Connectivity
Trust
ProvisioningState
```

ولا تخزن الأسرار الحساسة بطريقة غير آمنة.

---

# 28. Verification

لا تقل:

```text
Connected
```

لمجرد نجاح TCP.

استخدم مستويات مثل:

```text
DISCOVERED
REACHABLE
PAIRED
CONNECTED
AUTHENTICATED
IDENTIFIED
CAPABILITIES_VERIFIED
EXECUTION_VERIFIED
READY
```

---

# 29. اختبار الهاتفين

بعد انتهاء التنفيذ، لا تعتبر المهمة مكتملة بمجرد:

```text
compile
unit tests
lint
R8
```

يجب تجهيز وتنفيذ خطة اختبار فعلية على جهازين.

اختبر قدر الإمكان:

```text
QR pairing
pairing code
mDNS
ADB TLS
connect
identity
shell
command execution
file transfer
install
capability discovery
disconnect
reconnect
IP change
port change
Wi-Fi change
device reboot
private-network connectivity
```

وسجّل النتائج.

---

# 30. Android limitations

لا تفترض أن Android يسمح لنا بكل شيء.

لكل خطوة حدّد:

```text
Supported
Requires User
Requires ADB
Requires special permission
Requires root
OEM dependent
Impossible through public Android API
```

إذا كان الشيء غير ممكن من داخل التطبيق، لا تتحايل على Android.

ابحث عن:

```text
official API
ADB mechanism
system-supported mechanism
accessibility where appropriate
VPN API
user-directed settings
```

ثم اختر أفضل مسار قانوني وتقني.

---

# 31. البحث قبل التنفيذ

قبل كتابة الكود، ابحث في:

```text
AOSP
Android Developer documentation
ADB source
adb_wifi documentation
adb-auto-enable
Headscale
Tailscale Android
open-source ADB implementations
scrcpy
Android wireless debugging implementations
mDNS implementations
```

واستخرج فقط ما يسمح به الترخيص.

لا تفترض صحة أي README.

---

# 32. لا تضف Dependencies بلا حاجة

قبل إضافة مكتبة:

```text
ابحث عن الموجود في المشروع
ثم AOSP/platform
ثم المكتبات الموجودة
ثم open-source implementation
ثم dependency جديدة فقط إذا كانت ضرورية
```

ولا تستخدم:

```text
Flutter
NDK
paid services
```

إلا إذا أثبت التحليل أن هناك حاجة حقيقية لا يمكن تحقيقها بالمعمارية الحالية.

---

# 33. حافظ على الموجود

لا تعيد بناء:

```text
PermissionCenter
PeerAdbBridge
AdbShellRunner
LocalRuntimeCommandRunner
ExecutionPlanner
ExecutionProvider
CapabilityReport
DeviceRegistry
```

إلا إذا وجدت عيبًا معماريًا واضحًا.

طوّرها.

---

# 34. General AI Execution — لا تجعل قائمة القدرات سقفًا

لا أريد أن يكون Mushrea Code مقيدًا بقائمة ثابتة من:

- الأوامر
- الأدوات
- العمليات
- Providers
- Transports
- Scripts
- Capabilities
- الأجهزة
- طرق الاتصال
- حالات الاستخدام

القوائم الموجودة في المشروع هي قدرات معروفة حاليًا وليست حدودًا للنظام.

إذا طلب المستخدم شيئًا غير موجود حاليًا، يجب على الـAI أن يتعامل معه كمشكلة تنفيذ عامة، لا كـ"أمر غير مدعوم" مباشرة.

المسار المطلوب:

```text
Understand request
        ↓
Inspect current capabilities
        ↓
Search available tools/mechanisms
        ↓
Inspect target device
        ↓
Determine possible approaches
        ↓
Select best available approach
        ↓
Create/compose execution plan
        ↓
Use existing Provider if possible
        ↓
Create adapter/provider/script/capability when necessary
        ↓
Execute
        ↓
Verify
        ↓
Register/discover new capability when useful
```

لا تستخدم:

```text
if operation not in predefined list:
    return UNSUPPORTED
```

بل:

```text
if operation is unknown:
    investigate
    discover
    plan
    adapt
    execute if technically possible
    verify
```

إذا كان هناك أكثر من طريق لتحقيق الطلب، يستطيع الـAI مقارنة الطرق واختيار الأنسب.

هذه أمثلة فقط وليست قائمة مغلقة:

```text
ADB
USB
Wireless ADB
mDNS
Private network
VPN
Tailscale
Headscale
SSH
HTTP
WebSocket
local runtime
existing binaries
scripts
device APIs
future providers
```

---

# 35. Open-Ended Architecture

صمّم Mushrea Code باعتباره General AI Execution Platform وليس تطبيقًا يحتوي على مجموعة أوامر محدودة.

إذا ظهرت حاجة جديدة، يجب أن يكون المسار الطبيعي:

```text
User Request
    ↓
AI Understanding
    ↓
Capability Discovery
    ↓
Environment Discovery
    ↓
Tool Discovery
    ↓
Strategy Selection
    ↓
Dynamic Planning
    ↓
Execution
    ↓
Verification
```

إذا احتاج الطلب إلى قدرة غير موجودة حاليًا، ابحث أولًا عن إمكانية تحقيقها باستخدام الموجود.

إذا لم يكن ذلك كافيًا، يستطيع النظام تصميم امتداد مناسب مثل:

```text
Provider
Adapter
Script
Command
Tool
Capability
Transport
Protocol implementation
Integration
Runtime component
```

بحيث لا يحتاج النظام إلى إعادة تصميم معماريته بالكامل.

مثال:

اليوم:

```text
PeerAdbProvider
```

وغدًا:

```text
SshProvider
```

ثم:

```text
NewTransportProvider
```

ويصبح متاحًا للـAI من خلال نفس General Execution Architecture.

لا تضع سقفًا اصطناعيًا مثل:

```text
90 tools فقط
96 tools فقط
هذه العمليات فقط
هذه الأجهزة فقط
هذا Transport فقط
هذا النوع من Scripts فقط
```

بل اجعل النظام قادرًا على اكتشاف وتوسيع قدراته.

## Self-Expansion

عندما يواجه الـAI طلبًا جديدًا:

1. يحلل المطلوب.
2. يفحص ما هو متاح.
3. يبحث عن الأدوات والمكتبات والبروتوكولات المناسبة.
4. يحدد ما إذا كان التنفيذ ممكنًا.
5. يختار أفضل طريقة.
6. يستخدم الموجود إن أمكن.
7. يبني Adapter/Provider/Script/Capability عند الحاجة.
8. يختبر ما بناه.
9. يتحقق من النتيجة.
10. يجعل القدرة الجديدة قابلة لإعادة الاستخدام مستقبلًا.

لا أريد أن يكون كل طلب مستقبلي سببًا لإعادة تصميم Mushrea Code.

---

# قاعدة التصميم الأساسية

> The AI should discover capabilities, not depend on a fixed capability list.

> The architecture should provide extension points, not artificial limits.

> The tool registry describes what is currently known; it does not define everything the system can ever do.

إذا كان هناك شيء جديد يمكن تنفيذه تقنيًا باستخدام البيئة والأدوات المتاحة، يجب أن يكون Mushrea Code قادرًا على اكتشاف الطريق إليه بدل رفضه فقط لأنه لم يكن موجودًا وقت تصميم التطبيق.

أي قدرة جديدة يجب أن تصبح جزءًا من نفس منظومة التنفيذ العامة، بحيث يستطيع الـAI استخدامها لاحقًا دون إعادة بناء النظام الأساسي بالكامل.

---

# 36. Documentation

أنشئ وثائق:

```text
REMOTE_DEVICE_ARCHITECTURE.md
DEVICE_PROVISIONING.md
REMOTE_CONNECTIVITY.md
DYNAMIC_CAPABILITIES.md
HEADSCALE_INTEGRATION.md
ADB_AUTO_ENABLE_ANALYSIS.md
REMOTE_DEVICE_TEST_PLAN.md
```

مع رسم معماري واضح.

---

# 37. التنفيذ المرحلي

نفذ المشروع على مراحل، وليس دفعة واحدة إذا كان ذلك يقلل الجودة.

### Phase 1
Audit + architecture.

### Phase 2
Dynamic Capability / General Execution.

### Phase 3
Provisioning Engine.

### Phase 4
ADB auto-reconnect / persistent peer.

### Phase 5
adb-auto-enable integration/adaptation حيثما يسمح الترخيص.

### Phase 6
Remote Connectivity abstraction.

### Phase 7
Tailscale/Headscale integration/compatibility.

### Phase 8
Recovery + reconnection.

### Phase 9
Multi-device registry.

### Phase 10
Remote diagnostics/files/apps/screen.

### Phase 11
Real two-device testing.

### Phase 12
Documentation + final verification.

يمكنك تقسيم المرحلة إلى دفعات أصغر إذا كان ذلك أفضل هندسيًا.

بعد كل مرحلة:

```text
implement
test
build
lint
static analysis
CI
review
fix
```

ولا تنتقل إلى المرحلة التالية إذا كانت المرحلة الحالية غير مستقرة.

---

# 38. قاعدة مهمة جدًا

لا تتوقف عند:

> "هذه الميزة غير موجودة."

بل ابحث عن:

```text
هل توجد API؟
هل يوجد ADB command؟
هل توجد capability؟
هل يوجد provider؟
هل يوجد open-source implementation؟
هل يمكن بناء adapter؟
هل يمكن استخدام runtime؟
هل يمكن استخدام الشبكة الخاصة؟
هل يمكن تنفيذها بعد provisioning؟
هل تحتاج تفاعل المستخدم مرة واحدة فقط؟
```

ثم اختر أفضل مسار.

لكن لا تدّعي إمكانية شيء يمنعه Android فعليًا.

---

# 39. الهدف النهائي للـAI

أريد أن يصبح Mushrea Code قادرًا على فهم طلب مثل:

> "اتصل بهذا الجهاز وجهزه للعمل عن بعد."

ثم يقوم بنفسه بـ:

```text
DISCOVER
IDENTIFY
ASSESS
PLAN
PAIR
CONNECT
PROVISION
CONFIGURE
VERIFY
DISCOVER CAPABILITIES
REGISTER
TEST
MONITOR
RECOVER
```

وإذا احتاج شيئًا لا يمكن للتطبيق فعله وحده:

```text
يطلب من المستخدم أقل تدخل ممكن
```

ثم يكمل تلقائيًا.

---

# 40. معيار النجاح

لا تعتبر المهمة مكتملة لأن:

```text
CI أخضر
```

فقط.

يجب أن يكون لدينا:

```text
CI
+
Unit Tests
+
Integration Tests
+
Real Device Tests
+
Two-Device Tests
+
Reconnect Tests
+
Provisioning Tests
+
Network Change Tests
+
Reboot Tests
```

والأهم:

**يجب أن أستطيع في النهاية أخذ هاتف Android ثاني، واتباع الحد الأدنى من خطوات الإعداد التي يفرضها Android، ثم قول:**

> **"اتصل بهذا الجهاز وجهزه للعمل عن بعد."**

ويقوم Mushrea Code بالباقي بأكبر قدر ممكن من الأتمتة، ويستمر الجهاز في الظهور كـPeer معروف حتى بعد تغير الشبكة أو عنوان IP أو منفذ ADB متى كان ذلك ممكنًا.

---

# تعليمات التنفيذ

ابدأ أولًا بـ **Audit للمعمارية الحالية** وليس كتابة كود عشوائي.

استخدم ما هو موجود بالفعل في المشروع.

افحص المستودعات المفتوحة المصدر ذات الصلة، وخصوصًا:

- adb-auto-enable
- AOSP ADB / Wireless Debugging
- Headscale
- Tailscale Android
- scrcpy
- أي مشاريع مفتوحة المصدر ذات صلة بالـADB provisioning/reconnection

ثم اكتب:

```text
CURRENT STATE
GAPS
REUSABLE COMPONENTS
LICENSE ANALYSIS
PROPOSED ARCHITECTURE
PHASE PLAN
RISKS
TEST PLAN
```

ثم **ابدأ التنفيذ الفعلي مرحلةً مرحلة**.

بعد كل مرحلة:

```text
implement
test
build
lint
static analysis
CI
review
fix
```

ولا تنتقل إلى المرحلة التالية إذا كانت المرحلة الحالية غير مستقرة.

لا تنتظر مني اختيار كل تفصيل صغير.

اتخذ القرارات الهندسية المناسبة بنفسك، واذكر سبب القرار في التقرير.

لكن لا تغيّر `main` ولا تدمج إلى `main`.

اعمل على الفرع الحالي.

وفي النهاية أعطني تقريرًا صريحًا يوضح:

```text
Implemented
Verified by CI
Verified by unit tests
Verified on real device
Cannot verify
Requires user interaction
Requires special permission
Requires root
OEM dependent
Android limitation
```

**الأهم: لا تصمم النظام على أساس أن 5555 أو LAN هو المستقبل. صممه على أساس أن الاتصال Transport قابل للتبديل، وأن الـAI يستطيع اكتشاف القدرات واختيار أفضل Transport وProvisioning Strategy متاحة.**
