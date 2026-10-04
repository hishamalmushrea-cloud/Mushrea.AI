# P2 — خطة مركز الصلاحيات الموحَّد (Permission Center)

> خطة قصيرة قبل التنفيذ، كما طُلب في المهمة. الجذر المرجعي لهذه الخطة: `5a19b52`.

## 1) أين توجد السياسة الحالية؟ (نتيجة التدقيق)

| الموضع | ما هو فعلًا |
|---|---|
| `core/permission/ConfirmationLevel.kt` | مفردات المستوى: **AUTO / CONFIRM / STRONG** — بلا `DENY` وبلا الاسم الموحَّد `STRONG_CONFIRM`. |
| `core/permission/PermissionDecision.kt` | نتيجة القرار (`Allow`/`Confirm`/`Deny` + سبب) + `PermissionActor { AGENT, USER, SYSTEM }`. |
| `core/permission/PermissionResponse.kt` | إجابة المستخدم لطلب الوكيل نفسه (`ONCE`/`ALWAYS`/`REJECT`) — عالم مختلف: بروتوكول الران‑تايم لا تفويض أدواتنا. |
| `device/permission/ToolPermissionPolicy.kt` | **السياسة القوية القائمة**: تجمع السجل + تجاوزات المستخدم + القراءة فقط + تصعيد النقر الحسّاس. |
| `device/DeviceActionFirewall.kt` | مصدر المستوى من السجل + كلمات النقر الحسّاسة + قائمة القراءة فقط. |
| `device/DeviceAgentBridge.process()` | نقطة الإنفاذ الفعلية: إيقاف طارئ ← قرار ← توافر ← تأكيد ← تنفيذ ← تحقق ← تدقيق. |
| `device/tool/DeviceToolCatalog.kt` | 90 أداة بمستواها ومخاطرها (`ToolRisk`) و`readOnly` و`family`. |
| `feature/schedule/ScheduleExecutionService` | سياسة **منافسة**: `autoAcceptPermissions` كشرط `if` مباشر، وبدء الران‑تايم المحلي تلقائيًا. |
| `feature/chat/ChatViewModel` · `feature/assistant/MushreaCodeVoiceSession` | نفس شرط `autoAcceptPermissions` مكرَّرًا في موضعين آخرين. |
| `runtime/local/*` · `feature/workspace/WorkspaceViewModel` · `startup/RuntimeAutoStartInitializer` | استدعاءات دورة حياة الران‑تايم (تشغيل/إيقاف/إعادة تثبيت) بلا أي قرار مسجَّل. |

**نتيجة مهمة:** جميع أدوات الجهاز (Device · Network · SSH · USB · Files · Remote · Mirror · Termux ·
Calls) **تمرّ أصلًا** عبر الجسر ← `ToolPermissionPolicy`؛ فالمنفّذات لا تُستدعى من أي مكان آخر
(تحقّق بـ`grep`). الفجوة الحقيقية ليست في هذه الأنظمة، بل في: (أ) غياب مركز/نموذج موحَّد،
(ب) سياسة `autoAcceptPermissions` المنافسة في ثلاثة مواضع، (ج) دورة حياة الران‑تايم بلا قرار،
(د) غياب حارس يمنع الالتفاف مستقبلًا.

## 2) ما الذي سيُنقل إلى Permission Center؟

1. **مفردات القرار**: `AUTO / CONFIRM / STRONG_CONFIRM / DENY` كنموذج واحد.
2. **تركيب القرار**: مركز واحد يستقبل طلبًا موحَّدًا (`PermissionRequest`) ويعيد نتيجة موحَّدة
   (`PermissionResult`) مع السبب وسجل القرار.
3. **قواعد السلامة العامة**: الإيقاف الطارئ، ووضع القراءة فقط كشبكة أمان مركزية (لا تُترك لكل نظام)،
   و**الرفض الافتراضي** لأي عملية/مجال بلا سياسة (`fail-closed`).
4. **توحيد `autoAcceptPermissions`**: يتحوّل من `if` مكرَّر إلى *مدخل* في الطلب (`preAuthorized`)
   تقرأه سياسة مسجَّلة، فيصبح قرارًا مُدقَّقًا لا التفافًا.

## 3) ما الذي يبقى داخل كل نظام؟

- **مستوى كل أداة ومخاطرها وقائمة القراءة فقط** — تبقى في `DeviceToolCatalog` (مصدر واحد قائم).
- **منطق `ToolPermissionPolicy`** كما هو (السجل + التجاوزات + تصعيد النقر) — يُغلَّف بسياسة جهاز،
  لا يُهدَم ولا يُعاد كتابته.
- **آليات التأكيد الحالية** (شاشة/إشعار الجهاز، `awaitConfirmation`) — تُعاد استخدامها كما هي.
- **ترجمة بروتوكولات الوكلاء** (`PermissionResponse`) ومنطق كل ران‑تايم — لا تُمَس.
- **التدقيق**: يبقى `DeviceAuditLog` كما هو؛ المركز يمرّر قراره إليه ولا يعيد بناء التدقيق.
- **التحقق بعد التنفيذ** (`OutcomeVerification`) — يبقى مستقلًا عن قرار الصلاحية.

## 4) كيف يُمنع التعارض مستقبلًا؟

1. **حارس معماري جديد** `scripts/check_permission_center.py` يُشغَّل في CI ويفشل عند:
   - استدعاء أي منفّذ حساس (USB/SSH/Files/Remote/Termux/Network/Call/BT/Hub/Serial/Payload/Mirror)
     من خارج الجسر,
   - وجود فحص `autoAcceptPermissions` خارج السياسة المسجَّلة,
   - عدم تسجيل سياسة لكل مجال، أو غياب المركز من جذر التركيب.
2. **الرفض الافتراضي** في المركز: مجال/عملية بلا سياسة = `DENY`، فلا يمكن لنظام جديد أن يمر تلقائيًا.
3. **التوثيق**: يُحدَّث عقد §6.4 في `ARCHITECTURE.md` فيصبح القسم "منفَّذ" لا "مستهدفًا".

## 5) كيف تتوافق البنية مع الطبقات الست؟

```
core/permission/            ← المركز والنموذج (تعرفها كل الطبقات، ولا تعرف هي أحدًا)
  ConfirmationLevel (AUTO|CONFIRM|STRONG_CONFIRM|DENY) · PermissionRisk
  PermissionDomain · PermissionRequest · PermissionResult · PermissionPolicy · PermissionCenter
device/permission/          ← سياسة مجال الجهاز (تغلّف ToolPermissionPolicy القائمة)
runtime/permission/         ← سياسة الران‑تايم/الوكيل (auto-accept + دورة الحياة)
الجذر (MushreaCodeApplication) ← يبني المركز ويسجّل السياسات ويمرّره (Composition Root)
الجسر/الجدولة/المحادثة/الصوت  ← يطلبون القرار ثم ينفّذون
```

`core` لا يعتمد على `device`/`runtime` (اتجاه الطبقات محفوظ: السياسات تعتمد على `core`، والمركز لا
يعتمد على شيء). جذر التركيب وحده يعرف الجميع، وهذا هو وضعه المقصود في المعمارية.
