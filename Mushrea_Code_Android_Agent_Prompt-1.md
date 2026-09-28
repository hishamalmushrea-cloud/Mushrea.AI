# Mushrea Code — Android General-Purpose AI Agent

## 0. المهمة

أريد منك تطوير المشروع الحالي الموجود أمامك، والذي أصبح اسمه بالفعل:

**Mushrea Code**

لقد قمت مسبقًا بتعديل المشروع وإعادة تسميته، لذلك:

- لا تعِد تسمية المشروع.
- لا ترجع الاسم إلى AndCode.
- لا تنشئ مشروعًا جديدًا.
- لا تبدأ من الصفر.
- لا تلغِ التعديلات الموجودة.
- اعتبر الحالة الحالية للمشروع هي نقطة البداية الرسمية.

أريد تحويل الوكيل الموجود داخل Mushrea Code إلى **وكيل ذكاء اصطناعي عامًا للتحكم بجهاز Android**، مع الحفاظ الكامل على قدراته الحالية كـ Coding Agent.

---

# 1. الرؤية النهائية

أريد أن يصبح Mushrea Code قادرًا على التعامل مع الهاتف بطريقة قريبة من طريقة تعامل الإنسان معه، ضمن قدرات Android والصلاحيات التي يمنحها المستخدم.

المستخدم يتحدث معه صوتيًا أو نصيًا بشكل طبيعي، والوكيل:

1. يفهم الهدف.
2. يعرف حالة الجهاز الحالية.
3. يعرف التطبيق والشاشة الحالية.
4. يفهم السياق.
5. يخطط للخطوات.
6. ينفذها.
7. يراقب النتيجة.
8. يتحقق من النجاح.
9. يتعافى من الأخطاء عند الإمكان.
10. يخبر المستخدم بوضوح بالنتيجة.

أمثلة:

> افتح YouTube.

> افتح WhatsApp وابحث عن أحمد.

> ابحث عن ملف عقد الإيجار وافتحه.

> افتح YouTube وابحث عن آخر فيديو عن Android.

> أنا داخل هذه الصفحة، ما هذا؟ اشرحه لي.

> ابحث عن شيء مشابه لهذا.

> لخص لي هذا الفيديو.

> أرسل هذا الملف لمحمد.

الهدف ليس إضافة أوامر ثابتة فقط، بل إنشاء **Android Device Agent** عام.

---

# 2. لا تخرب الوكيل الموجود

هذه قاعدة أساسية وغير قابلة للتفاوض.

حافظ على قدرات Mushrea Code الحالية، خصوصًا:

- Coding Agent.
- البرمجة.
- Terminal.
- Git/GitHub.
- إدارة الملفات الحالية.
- Voice.
- Wake Word.
- Digital Assistant.
- Sessions.
- Tool system.
- Permissions/Approvals.
- Scheduled Tasks.
- أي ميزات أخرى موجودة حاليًا.

الإضافة الجديدة يجب أن تكون امتدادًا للمشروع وليست استبدالًا له.

لا تعيد كتابة المشروع بالكامل لمجرد أن تصميمًا جديدًا يبدو أفضل.

---

# 3. المعمارية المستهدفة

التصور المنطقي:

```text
Mushrea Code
│
├── AI Agent Core
│   ├── Intent Understanding
│   ├── Context Engine
│   ├── Task Planner
│   ├── Memory
│   └── Agent Loop
│
├── Coding Tools
│   ├── Code
│   ├── Git
│   ├── GitHub
│   ├── Terminal
│   ├── Build
│   └── Test
│
└── Device Agent
    ├── App Control
    ├── Screen Control
    ├── Accessibility
    ├── Touch / Gesture
    ├── Text Input
    ├── File Control
    ├── Notification Control
    ├── Android Intents
    ├── Device State
    ├── Context
    ├── Verification
    └── Error Recovery
```

استخدم بنية المشروع الحالية إذا كان لديها تصميم أفضل. لا تفرض أسماء الملفات أو الحزم حرفيًا.

المهم هو الفصل المنطقي بين Agent Core وCoding Tools وDevice Tools.

---

# 4. Device Agent

أضف طبقة واضحة مسؤولة عن التحكم بالجهاز.

يجب أن يستطيع الوكيل، ضمن صلاحيات Android:

- فتح التطبيقات.
- معرفة التطبيق الحالي.
- معرفة الشاشة الحالية.
- قراءة عناصر الواجهة التي يسمح بها النظام.
- الضغط.
- النقر المطول.
- التمرير.
- السحب.
- كتابة النص.
- حذف النص.
- الرجوع.
- العودة إلى Home.
- فتح Recent Apps عندما يكون ذلك ممكنًا.
- البحث في الملفات.
- فتح الملفات.
- مشاركة الملفات.
- التعامل مع الإشعارات ضمن الصلاحيات المتاحة.
- استخدام Intents.
- تنفيذ مهام متعددة الخطوات.
- التحقق من النتائج.
- استعادة المهمة عند الفشل.

---

# 5. App Control

أضف قدرات عامة مثل:

```text
open_app
get_current_app
switch_app
open_url
go_home
press_back
open_recent_apps
```

مثال:

> افتح YouTube.

يجب أن يستطيع تنفيذها مباشرة من الجهاز حتى عندما لا تكون واجهة Mushrea Code الرئيسية أمام المستخدم، ضمن قيود Android.

استخدم Android APIs/Intents الرسمية عندما تكون مناسبة.

إذا كانت Accessibility أفضل لحالة معينة، استخدمها.

---

# 6. Screen Control

أضف قدرات مثل:

```text
read_screen
inspect_ui
find_element
find_text
tap
long_press
swipe
scroll
type_text
clear_text
press_back
press_home
```

الأولوية:

```text
Android API
↓
Accessibility semantics
↓
UI element/action
↓
Coordinate fallback عند الضرورة
```

لا تجعل coordinate-based automation هو الحل الأساسي.

---

# 7. Accessibility Engine

استخدم Android Accessibility Service بالطريقة الرسمية.

بعد أن يمنح المستخدم الصلاحية، يجب أن يستطيع الوكيل قدر الإمكان:

- معرفة التطبيق الحالي.
- قراءة Accessibility tree.
- قراءة النصوص المتاحة.
- قراءة Content Descriptions.
- معرفة أدوار عناصر UI.
- العثور على الأزرار والعناصر.
- الضغط على العناصر.
- التمرير.
- الكتابة.
- تنفيذ إجراءات Accessibility المدعومة.

مثلاً إذا قال المستخدم:

> اضغط بحث.

الأفضل أن يبحث عن عنصر UI اسمه أو وصفه "بحث"، وليس الاعتماد على:

```text
tap(x, y)
```

إذا تعذر الوصول إلى العنصر بطريقة semantic، يمكن استخدام fallback مناسب.

---

# 8. Screen Understanding

أريد أن يستطيع الوكيل فهم حالة الشاشة الحالية.

مثال:

```text
Current app:
YouTube

Current screen:
Home

Visible elements:
Search
Home
Subscriptions
...
```

إذا قال المستخدم:

> اضغط بحث.

يبحث في الشاشة الحالية عن العنصر المناسب.

إذا قال:

> ما هذه الصفحة؟

يفهم محتوى الشاشة الحالية قبل الإجابة.

---

# 9. Context Engine

هذه من أهم الميزات.

يجب أن يعرف النظام قدر الإمكان:

- التطبيق الحالي.
- الشاشة الحالية.
- العناصر الظاهرة.
- العنصر المحدد حاليًا.
- الملف الحالي.
- الصفحة الحالية.
- الفيديو الحالي.
- آخر إجراء.
- المهمة الحالية.
- الخطوة الحالية.
- نتيجة الخطوة السابقة.
- المحادثة الحالية.
- حالة التأكيد.

يجب أن يفهم كلمات مثل:

- هذا.
- هذه.
- هنا.
- افتحه.
- أرسله.
- شاركه.
- اضغط عليها.
- ارجع.
- تابع.
- ابحث عن شيء مشابه لهذا.

مثال:

المستخدم يفتح ملفًا ثم يقول:

> أرسله لمحمد.

إذا كان السياق واضحًا، يجب أن يفهم أن "أرسله" يشير إلى الملف الحالي.

---

# 10. Context Confidence

أضف مفهومًا مثل:

```text
High
Medium
Low
```

مثلاً:

**High**

يوجد عنصر واحد واضح يشير إليه المستخدم.

**Medium**

الوكيل لديه تفسير مرجح لكنه ليس مؤكدًا.

**Low**

هناك عدة عناصر محتملة.

إذا كان مستوى الثقة منخفضًا، خصوصًا في عملية حساسة، يجب أن يسأل المستخدم بدل التخمين.

مثال:

> أي ملف تقصد؟ وجدت ملفين متشابهين.

---

# 11. الأمر "ما هذا؟"

يجب أن يفهم الوكيل:

> ما هذا؟

> ما هذه الصفحة؟

> اشرح لي هذا.

> ماذا يعني هذا؟

> ما وظيفة هذا الخيار؟

حسب التطبيق والسياق.

مثلاً داخل Browser:

> ما هذه الصفحة؟ اشرحها لي.

ينفذ:

```text
Detect current app
↓
Inspect current screen
↓
Read accessible content
↓
Understand page
↓
Explain
```

لا يطلب من المستخدم نسخ النص يدويًا إذا كان يمكن الوصول إليه رسميًا.

---

# 12. Contextual Explanation

يجب أن يعمل الأمر نفسه في تطبيقات مختلفة.

### Browser

> ما هذه الصفحة؟

يشرح الصفحة.

### YouTube

> ما هذا الفيديو؟

يستخدم المعلومات المتاحة على الشاشة والسياق الحالي.

### Files

> ما هذا الملف؟

يحدد الملف الحالي ويشرح اسمه ونوعه والمعلومات المتاحة.

### WhatsApp

> ما هذه الرسالة؟

يحدد الرسالة أو السياق الحالي ويشرحها.

### Settings

> ما هذا الخيار؟

يحدد الخيار الظاهر ويشرح وظيفته.

### Gallery

> ما هذه الصورة؟

يمكن تحليل الصورة إذا كانت هناك آلية تحليل صور متاحة ومصرح بها.

---

# 13. Current Screen Tools

أضف قدرات منطقية مثل:

```text
explain_current_screen
summarize_current_screen
extract_from_screen
```

### explain_current_screen

يشرح ما يظهر أمام المستخدم.

### summarize_current_screen

يلخص المحتوى الحالي.

### extract_from_screen

يستخرج معلومة محددة.

أمثلة:

> ما اسم الشركة الموجودة في هذه الصفحة؟

> ما السعر؟

> ما التاريخ؟

> ما اسم الملف؟

> ما عنوان المكان؟

---

# 14. أسئلة عن الشاشة الحالية

دعم أسئلة مثل:

> ما هذا؟

> ماذا يعني هذا؟

> ماذا أفعل هنا؟

> ما الخطوة التالية؟

> هل هذا صحيح؟

> ما الفرق بين الخيارين الظاهرين؟

> أين أضغط؟

> أين أجد الإعداد الذي أريده؟

الوكيل يستخدم الشاشة الحالية والسياق للإجابة.

---

# 15. Visual Understanding

إذا كانت معلومات الشاشة غير متاحة عبر Accessibility وحدها، يمكن استخدام Screenshot analysis أو آلية مناسبة أخرى عند الحاجة، بشرط احترام الخصوصية والصلاحيات.

الأولوية:

```text
Accessibility / UI hierarchy
↓
Accessible text
↓
Application metadata
↓
Screenshot analysis عند الحاجة
```

لا ترسل Screenshots أو محتوى الجهاز إلى خدمات خارجية بلا حاجة أو دون سياسة واضحة.

---

# 16. Current Selection

يجب أن يستطيع النظام ربط:

```text
هذا
هذه
هنا
الموجود أمامي
العنصر المحدد
الملف الحالي
الفيديو الحالي
الصفحة الحالية
```

بالسياق المناسب.

مثال:

المستخدم داخل YouTube:

> ابحث عن شيء مشابه لهذا الفيديو.

يجب أن يفهم أن:

```text
"This video"
=
Current YouTube video
```

إذا كان السياق واضحًا.

---

# 17. البحث داخل التطبيق الحالي

هذه ميزة أساسية.

إذا كان المستخدم داخل المتصفح وقال:

> ابحث عن شيء مشابه لهذا.

يجب أن:

```text
Understand current page
↓
Extract useful context
↓
Find browser search
↓
Enter query
↓
Search
↓
Return/inspect results
```

ولا تفتح واجهة Mushrea Code أمام المستخدم إلا إذا كانت هناك حاجة.

---

# 18. Contextual Search

دعم أوامر مثل:

> ابحث عن هذا.

> ابحث عن شيء مشابه لهذا.

> ابحث عن معلومات أكثر عن هذا.

> ابحث عن هذا الاسم.

> ابحث عن هذا الموضوع.

استخدم:

```text
Current Screen
+
Selected Element
+
Visible Text
+
Current App
+
Conversation Context
```

لإنشاء استعلام مناسب.

---

# 19. In-App Navigation

يجب أن يستطيع الوكيل العمل داخل التطبيق الحالي.

## Browser

> افتح البحث.

> ابحث عن Android Agents.

> افتح النتيجة الثانية.

> ارجع.

> مرر للأسفل.

> افتح هذا الرابط.

## YouTube

> ابحث عن Android Studio.

> افتح الفيديو الثاني.

> انزل إلى التعليقات.

## Files

> افتح Downloads.

> ابحث عن PDF.

> افتح الملف.

## WhatsApp

> افتح المحادثة مع أحمد.

> ابحث عن رسالة تحتوي على "الموعد".

> افتح هذه المحادثة.

لا تفترض أن جميع التطبيقات تستخدم نفس الواجهة.

---

# 20. لا تعتمد على تطبيق واحد

لا تبنِ النظام أساسًا على:

```text
WhatsAppAgent
YouTubeAgent
TelegramAgent
ChromeAgent
```

بل ابنِ:

# Generic Android Interaction Engine

يمكن إضافة integrations خاصة لاحقًا عندما تكون مفيدة، لكن الأساس يجب أن يعتمد على قدرات Android العامة.

---

# 21. Cross-App Context

يجب أن يستطيع الوكيل الانتقال بين التطبيقات مع الحفاظ على هدف المهمة وسياقها.

مثال:

> هذا المقال أعجبني، أرسله لمحمد في WhatsApp.

التنفيذ:

```text
Current Browser Page
↓
Identify current article/link
↓
Share
↓
WhatsApp
↓
Find محمد
↓
Send
↓
Verify
```

مثال آخر:

> احفظ هذا الملف في Documents.

```text
Current File
↓
Move/Copy
↓
Documents
↓
Verify
```

---

# 22. Context Must Survive App Changes

إذا انتقل الوكيل من تطبيق إلى آخر بسبب المهمة، لا يفقد الهدف الأصلي.

مثال:

```text
Goal:
Send current article to Ahmed

Current App:
Browser

↓
Share

Current App:
WhatsApp

↓
Find Ahmed

↓
Send

↓
Verify
```

---

# 23. Human-Like Context

أريد أن يتصرف الوكيل كمساعد يجلس بجانب المستخدم.

المستخدم لا ينبغي أن يضطر إلى إعطائه خطوات تقنية.

بدل:

> افتح Chrome ثم ابحث عن النص X ثم افتح النتيجة...

يمكنه أن يقول:

> ابحث عن شيء مشابه لهذا.

والوكيل يستنتج الخطوات.

---

# 24. Agent Loop

لا أريد:

```text
Command
↓
Action
↓
Done
```

أريد:

```text
GOAL
↓
OBSERVE
↓
UNDERSTAND CONTEXT
↓
PLAN
↓
ACT
↓
OBSERVE
↓
VERIFY
↓
SUCCESS?
├── YES → DONE
└── NO
     ↓
  RECOVER
     ↓
  REPLAN
     ↓
    ACT
```

يجب أن يستطيع تعديل خطته أثناء التنفيذ.

---

# 25. Verification Engine

بعد كل خطوة مهمة، تحقق من النتيجة.

مثال:

> افتح YouTube.

بعد التنفيذ:

```text
Check current package
Check current screen
```

ثم:

```text
SUCCESS
```

إذا لم يتم فتحه:

```text
FAILED
↓
Observe again
↓
Try fallback
```

لا تقل للمستخدم إن المهمة نجحت إذا لم يتم التحقق منها.

---

# 26. Error Recovery

إذا فشلت خطوة:

1. أعد قراءة الشاشة.
2. حدّث حالة الجهاز.
3. ابحث عن العنصر بطريقة مختلفة.
4. استخدم Android API إذا كان أفضل.
5. استخدم Accessibility إذا كان مناسبًا.
6. جرّب Scroll.
7. جرّب Back إذا كان منطقيًا.
8. أعد التخطيط.

ضع حدًا للمحاولات لمنع Loop لا نهائي.

---

# 27. Multi-Step Tasks

يجب أن يستطيع تنفيذ مهام طويلة.

مثال:

> افتح WhatsApp، ابحث عن أحمد، افتح المحادثة، واكتب له "سأتصل بك لاحقًا".

الخطوات:

```text
Open WhatsApp
↓
Observe
↓
Find Search
↓
Tap
↓
Type أحمد
↓
Find أحمد
↓
Open conversation
↓
Find message field
↓
Type message
↓
Verify
```

لا تعتبر المهمة ناجحة إلا بعد التحقق.

---

# 28. File Agent

أضف قدرات مثل:

```text
search_files
search_by_name
search_by_extension
open_file
copy_file
move_file
rename_file
share_file
```

مثال:

> ابحث عن ملف Mushrea.pdf وافتحه.

يبحث، ويرتب النتائج، ويختار أفضل نتيجة إذا كان واضحًا، ثم يفتحها ويتحقق من النجاح.

إذا كانت هناك عدة نتائج متشابهة ولا يمكن تحديد المقصود بثقة، يسأل المستخدم.

استخدم APIs الرسمية وStorage Access Framework عندما تكون مناسبة.

---

# 29. Sharing وAndroid Intents

استخدم Android Sharesheet وIntents الرسمية عندما تكون مناسبة.

أمثلة:

> شارك هذا الملف مع WhatsApp.

> افتح هذا الرابط في Chrome.

استخدم Android APIs بدل محاكاة اللمس عندما يكون API الرسمي أفضل.

---

# 30. Voice

حافظ على Voice/Wake Word الموجود في المشروع.

أريد:

> Mushrea، افتح YouTube.

ثم:

> Mushrea، ابحث عن Android.

الصوت يجب أن يكون واجهة للـ Agent نفسه وليس نظامًا منفصلًا.

---

# 31. Background Operation

أريد أن يستطيع Mushrea Code العمل عندما تكون واجهته الرئيسية غير مفتوحة، ضمن ما يسمح به Android.

ادرس واستخدم عند الحاجة:

- Accessibility Service.
- Foreground Service.
- Notifications.
- WorkManager.
- Scheduled Tasks.
- Android Assistant APIs.

لا تتحايل على قيود Android.

إذا كانت عملية معينة غير متاحة في الخلفية، استخدم الطريقة الرسمية المناسبة أو أخبر المستخدم بوضوح.

---

# 32. Digital Assistant

إذا كانت البنية الحالية تدعم Android Digital Assistant، اربط Device Agent بها.

الهدف:

```text
Home Screen
↓
Assistant
↓
"Mushrea افتح YouTube"
↓
Device Agent
↓
YouTube
```

بدون الحاجة إلى فتح واجهة Mushrea Code الرئيسية لكل مهمة.

---

# 33. Widget / Quick Access

استفد من Widget الموجودة.

يفضل توفير اختصارات مثل:

- Ask Mushrea.
- Voice Command.
- Stop Agent.
- Recent Task.

لا تضفها من جديد إذا كانت موجودة بالفعل؛ وسّع الموجود.

---

# 34. Automation Engine

استفد من Scheduled Tasks الموجودة، ووسعها لتدعم Device Automation.

أمثلة:

> كل يوم الساعة 8 افتح التقويم.

> عندما يصل ملف PDF إلى Downloads، انقله إلى Documents.

نفذ ذلك فقط ضمن إمكانيات Android الرسمية.

لا تسمح للمهام المجدولة بتنفيذ إجراءات حساسة دون سياسة تأكيد مناسبة.

---

# 35. Permission Firewall

لأن الوكيل سيحصل على قدرات قوية، أضف سياسة واضحة للعمليات.

مثال:

```text
Open App             → Automatic
Read Screen          → Automatic
Search Files         → Automatic
Open File            → Automatic
Scroll               → Automatic
Type Text            → Automatic

Delete File          → Confirmation
Send Message         → Configurable
Post Content         → Confirmation
Purchase             → Strong Confirmation
Money Transfer       → Always Confirmation
Change Security      → Confirmation
Delete Account       → Strong Confirmation
```

اجعل السياسة قابلة للتخصيص.

استفد من نظام Tool Approval الموجود بدل إنشاء نظام متعارض معه.

---

# 36. Emergency Stop

أضف زرًا واضحًا:

# STOP AGENT

وأيضًا أمرًا صوتيًا:

> توقف.

عند تنفيذه يجب إيقاف المهمة الحالية بأسرع ما يمكن.

يجب ألا يبدأ الوكيل تنفيذ خطوة جديدة بعد طلب الإيقاف.

---

# 37. Activity Log

أريد سجلًا واضحًا للمهام.

مثال:

```text
User:
"افتح تقرير Mushrea"

Agent:
Searching files...

Found:
Mushrea_Report.pdf

Agent:
Opening...

Verification:
Success
```

يجب أن يكون السجل مفيدًا للتشخيص وليس مجرد سجل ضخم.

---

# 38. Explainability

إذا فشلت العملية، لا تقل فقط:

> حدث خطأ.

وضح السبب قدر الإمكان.

مثلاً:

> لم أستطع فتح الملف لأن التطبيق الذي يدعمه غير متاح.

أو:

> وجدت ملفين متشابهين، لذلك أحتاج منك تحديد الملف.

أو:

> لم أرسل الرسالة لأن العملية تحتاج إلى تأكيد.

---

# 39. عدم الادعاء بالنجاح

قاعدة أساسية:

إذا لم تستطع التحقق:

> لم أستطع التحقق من نجاح العملية.

إذا فشلت:

> فشلت العملية بسبب...

إذا احتجت إلى صلاحية:

> أحتاج إلى تفعيل صلاحية Accessibility.

لا تدّع تنفيذ شيء لم يتم تنفيذه.

---

# 40. الأمان

لا تستخدم:

- تجاوز حماية Android.
- استغلال ثغرات.
- تجاوز sandbox.
- سرقة بيانات.
- تجاوز قفل الجهاز.
- إخفاء نشاط الوكيل عن المستخدم.
- تجاوز صلاحيات التطبيقات.

إذا منع Android عملية معينة، احترم ذلك واستخدم الطريقة الرسمية.

---

# 41. Coding Agent يجب أن يبقى

يجب أن يبقى Mushrea Code قادرًا على:

```text
Read Code
Edit Code
Run Terminal
Build
Test
Git
GitHub
```

وفي الوقت نفسه:

```text
Open Apps
Control Screen
Search Files
Interact With UI
Understand Current Screen
Automate Tasks
```

المستخدم يستطيع من نفس الوكيل أن يقول:

> أصلح المشروع وابنه.

أو:

> افتح WhatsApp.

أو:

> ما هذه الصفحة؟

أو:

> ابحث عن شيء مشابه لهذا.

---

# 42. Self-Development Capability

بما أن Mushrea Code بيئة Coding Agent، حافظ على إمكانية تطوير المشروع نفسه:

```text
Understand request
↓
Inspect code
↓
Modify code
↓
Build
↓
Test
↓
Analyze errors
↓
Fix
↓
Rebuild
↓
Report
```

لكن لا تسمح له بإجراء تغييرات خطرة على المستودع دون مراجعة مناسبة.

---

# 43. لا تستخدم Flutter

المشروع يجب أن يبقى Android Native.

لا تنقل المشروع إلى Flutter.

لا تضف NDK إلا إذا أثبتت أن مكونًا محددًا لا يمكن تنفيذه بدونه.

لا تضف Agent ثانيًا بلا حاجة إذا كان Agent Core الحالي يستطيع القيام بالدور.

---

# 44. لا تضف خدمات مدفوعة كشرط أساسي

أريد أن يعمل Device Control قدر الإمكان باستخدام:

- Android APIs.
- المكتبات المفتوحة المصدر.
- المكونات الموجودة أصلًا.
- الخدمات المحلية/المجانية عند الإمكان.

لا تجعل خدمة مدفوعة شرطًا للتحكم بالجهاز.

إذا كان نموذج AI يحتاج مزودًا خارجيًا، افصل ذلك عن Device Control.

---

# 45. الخصوصية

لأن الوكيل يستطيع رؤية أجزاء حساسة من الجهاز، الخصوصية جزء أساسي من التصميم.

وضح للمستخدم:

- ما الذي يقرأه.
- ما الذي يستطيع الوصول إليه.
- ما الذي يمكن إرساله إلى نموذج AI.
- ما الذي يبقى محليًا.
- ما الصلاحيات المطلوبة.
- ما الذي فعله الوكيل.

لا ترسل محتوى الشاشة أو الملفات إلى خدمة خارجية إلا عند الحاجة وبحسب إعدادات المستخدم.

---

# 46. عدم افتراض التطبيق

إذا كان المستخدم داخل تطبيق غير معروف للوكيل، لا تتوقف مباشرة.

حاول استخدام القدرات العامة:

```text
Current App
↓
Accessibility
↓
UI hierarchy
↓
Screen context
↓
Generic Actions
```

إذا تعذر تنفيذ الطلب بسبب عدم توفر عناصر قابلة للوصول أو قيود التطبيق، أخبر المستخدم بوضوح.

---

# 47. الاختبارات

بعد كل مرحلة:

```text
Build
↓
Unit Tests
↓
Integration Tests
↓
Regression Tests
```

ثم Manual QA على جهاز Android حقيقي.

اختبر:

1. Open App.
2. Back.
3. Home.
4. Recent Apps.
5. Read Screen.
6. Find Element.
7. Tap.
8. Long Press.
9. Swipe.
10. Scroll.
11. Type.
12. Search File.
13. Open File.
14. Explain Current Screen.
15. Summarize Current Screen.
16. Contextual Search.
17. Multi-Step Task.
18. Verification.
19. Recovery.
20. Stop Agent.
21. Voice.
22. Wake Word.
23. Background Execution.
24. Digital Assistant.
25. Existing Coding Agent.

تأكد أن وظائف Mushrea Code القديمة ما زالت تعمل.

---

# 48. Acceptance Tests

## Browser

أثناء وجود المستخدم في المتصفح:

> ما هذه الصفحة؟ اشرحها لي.

يجب أن يفهم الصفحة الحالية ويشرحها.

---

> ابحث عن شيء مشابه لهذا.

يجب أن يستخدم الصفحة الحالية لإنشاء استعلام، ثم ينفذه في المتصفح الحالي عندما يكون ذلك ممكنًا.

---

> ابحث عن معلومات أكثر عن هذا الموضوع.

---

> افتح النتيجة الثانية.

---

> ارجع.

---

## YouTube

أثناء وجود المستخدم داخل YouTube:

> ما هذا الفيديو؟

---

> لخص لي هذا.

---

> ابحث عن شيء مشابه لهذا.

---

> افتح الفيديو الثاني.

---

> انزل إلى التعليقات.

---

## Files

أثناء وجود المستخدم داخل مدير الملفات:

> ما هذا الملف؟

---

> افتحه.

---

> ابحث عن ملفات PDF مشابهة لهذا.

---

## WhatsApp

أثناء وجود المستخدم داخل WhatsApp:

> ما هذه الرسالة؟

---

> لخص هذه المحادثة.

---

> ابحث عن رسالة تحتوي على كلمة "الموعد".

---

> أرسل هذه الصورة لمحمد.

يجب تطبيق سياسة التأكيد المناسبة قبل الإرسال إذا كانت مطلوبة.

---

## Settings

أثناء وجود المستخدم داخل Settings:

> ما هذا الخيار؟

---

> ماذا يحدث إذا فعلته؟

---

> أين أجد إعداد X؟

---

# 49. مثال End-to-End

المستخدم في الصفحة الرئيسية:

> Mushrea، افتح Chrome.

الوكيل:

```text
Detect Home
↓
Open Chrome
↓
Verify
```

المستخدم:

> ابحث عن Android Agents.

الوكيل:

```text
Current App = Chrome
↓
Find Search
↓
Type query
↓
Search
↓
Verify
```

المستخدم:

> افتح النتيجة الثانية.

الوكيل:

```text
Observe
↓
Find results
↓
Identify second result
↓
Tap
↓
Verify
```

المستخدم:

> ما هذه الصفحة؟

الوكيل:

```text
Current App = Chrome
↓
Read current screen
↓
Understand page
↓
Explain
```

المستخدم:

> ابحث عن شيء مشابه لهذا.

الوكيل:

```text
Understand current page
↓
Generate contextual query
↓
Use current browser search
↓
Search
↓
Verify
```

المستخدم:

> أرسل هذا الرابط لمحمد في WhatsApp.

الوكيل:

```text
Identify current page/link
↓
Share
↓
WhatsApp
↓
Find محمد
↓
Apply confirmation policy
↓
Send
↓
Verify
```

هذا هو المستوى المطلوب.

---

# 50. Agent Loop النهائي

يجب أن تصبح التجربة:

```text
                 USER
                   │
                   ▼
          Natural Language
                   │
                   ▼
             Context Engine
                   │
        ┌──────────┴──────────┐
        │                     │
   Current App          Current Screen
        │                     │
        └──────────┬──────────┘
                   ▼
          Screen Understanding
                   │
                   ▼
             Goal Detection
                   │
                   ▼
                Planner
                   │
                   ▼
             Device Actions
                   │
                   ▼
                Observe
                   │
                   ▼
                Verify
                   │
              ┌────┴────┐
              │         │
           Success    Recovery
              │         │
              ▼         ▼
             DONE     Re-plan
```

الهدف النهائي:

**لا أريد أن أضطر إلى مغادرة التطبيق الحالي لكي أستخدم Mushrea Code.**

إذا كنت في Browser، يعمل معي داخل Browser.

إذا كنت في YouTube، يعمل معي داخل YouTube.

إذا كنت في Files، يعمل معي داخل Files.

إذا كنت في WhatsApp، يعمل معي داخل WhatsApp.

إذا كنت في Settings، يعمل معي داخل Settings.

إذا كنت في أي تطبيق آخر، يحاول استخدام قدرات Android العامة للتعامل معه.

يجب أن يشعر المستخدم بأن **Mushrea Code موجود معه فوق الهاتف كله، وليس داخل تطبيق Mushrea Code فقط**.

---

# 51. طريقة التنفيذ

لا تبدأ بكل شيء دفعة واحدة.

ابدأ بـ Vertical Slice حقيقي:

```text
Voice/Text
↓
Agent
↓
open_app
↓
Android
↓
Verify
```

اختبره.

ثم:

```text
read_screen
↓
find_element
↓
tap
↓
verify
```

ثم:

```text
Current App
↓
Current Screen
↓
Context
↓
"ما هذا؟"
```

ثم:

```text
Contextual Search
↓
In-App Navigation
```

ثم:

```text
Multi-Step Agent
↓
Recovery
↓
Automation
```

---

# 52. مراحل التنفيذ

## Phase 1 — Device Agent Core

أنشئ الأساس مع أقل تغييرات ممكنة على المشروع.

## Phase 2 — App Control

إضافة فتح التطبيقات، Home، Back، Current App، Intents.

## Phase 3 — Accessibility

إضافة Accessibility Service والعمليات الأساسية.

## Phase 4 — Screen + UI Interaction

إضافة:

- Read Screen.
- Find Element.
- Tap.
- Long Press.
- Swipe.
- Scroll.
- Type.

## Phase 5 — Context Engine

إضافة:

- Current App.
- Current Screen.
- Current Element.
- Current File.
- Current Page.
- Current Task.
- Context Confidence.

## Phase 6 — Explain Current Screen

إضافة:

- Explain.
- Summarize.
- Extract.

## Phase 7 — Contextual Search + In-App Navigation

إضافة:

- Search current app.
- Search based on current context.
- Navigate within current app.

## Phase 8 — File Agent

إضافة البحث والفتح والمشاركة والنقل والنسخ وإعادة التسمية.

## Phase 9 — Agent Loop

إضافة:

```text
Observe
Plan
Act
Verify
Recover
```

## Phase 10 — Multi-Step Tasks

دعم المهام الطويلة.

## Phase 11 — Background + Voice + Assistant

دمج:

- Foreground Service.
- Wake Word.
- Digital Assistant.
- Widget.
- Notifications.

## Phase 12 — Automation

دمج Scheduled Tasks مع Device Agent.

## Phase 13 — Security

إضافة:

- Permission Firewall.
- Confirmations.
- Activity Log.
- Emergency Stop.

## Phase 14 — Testing + Optimization

Build + Unit Tests + Integration Tests + Regression Tests + Manual QA.

---

# 53. Git Discipline

إذا كان العمل يتم داخل Git:

- لا تعمل مباشرة على main إذا كان من الأفضل إنشاء branch.
- استخدم فروعًا واضحة.
- استخدم commits صغيرة وواضحة.
- لا تستخدم force push.
- لا تحذف تاريخ المشروع.
- لا تنشئ Release أو Tag تلقائيًا.
- لا تدمج تغييرات كبيرة بلا تحقق.

أسماء محتملة:

```text
feature/device-agent-core
feature/accessibility-engine
feature/device-actions
feature/device-context
feature/device-files
feature/agent-loop
feature/device-automation
feature/device-security
```

استخدم ما يناسب حالة المشروع الحالية.

---

# 54. قواعد Android

إذا كان مطلب معين غير ممكن بسبب Android restrictions:

1. حدد القيد.
2. ابحث عن API رسمي بديل.
3. استخدم البديل.
4. إن لم يوجد، صمم fallback.
5. إن لم يكن ممكنًا، أخبر المستخدم بوضوح.

لا تتحايل على:

- Android sandbox.
- Permission model.
- App isolation.
- Device security.
- Lock screen.
- صلاحيات التطبيقات.

---

# 55. لا تدّع تنفيذ شيء لم تنفذه

في نهاية كل مرحلة اذكر:

```text
Implemented
Tested
Not tested
Blocked
Not possible due to Android restriction
```

لا تعتبر أي ميزة مكتملة بدون دليل مناسب.

---

# 56. المهمة الآن

ابدأ من **الحالة الحالية لمشروع Mushrea Code**.

لا تعِد تسمية المشروع.

لا تنشئ مشروعًا جديدًا.

لا تحذف التعديلات السابقة.

لا تعِد بناء المشروع بالكامل.

افحص البنية الحالية بما يكفي لتحديد أفضل نقاط الدمج، ثم ابدأ التنفيذ تدريجيًا.

نفذ كل مرحلة بهذه الدورة:

```text
Inspect
↓
Plan
↓
Implement
↓
Build
↓
Test
↓
Fix
↓
Regression Check
↓
Next Phase
```

وفي نهاية العمل قدم تقريرًا واضحًا يتضمن:

- ما تم تنفيذه.
- المكونات والملفات التي تغيرت.
- الاختبارات التي نجحت.
- الاختبارات التي لم تنفذ.
- المشاكل المتبقية.
- قيود Android.
- الصلاحيات المطلوبة من المستخدم.
- أي أجزاء تحتاج إلى Manual QA.
- الخطوات التالية.

---

# النتيجة النهائية المطلوبة

أريد أن يتحول Mushrea Code من Coding Agent فقط إلى:

# MUSHREA CODE
## General-Purpose Android AI Agent

وكيل واحد يستطيع:

**البرمجة**

```text
Code
Git
GitHub
Terminal
Build
Test
```

**التحكم بالهاتف**

```text
Apps
Screen
Touch
Gestures
Text
Files
Notifications
Intents
```

**فهم الشاشة**

```text
Current App
Current Screen
Current Element
Current Context
Explain
Summarize
Extract
```

**التنفيذ الذكي**

```text
Plan
Observe
Act
Verify
Recover
```

**الأتمتة**

```text
Tasks
Schedules
Triggers
Workflows
```

**التفاعل**

```text
Voice
Text
Wake Word
Digital Assistant
Widget
```

مع:

```text
Permission Firewall
Confirmation System
Activity Log
Emergency Stop
Privacy Controls
```

والهدف النهائي هو أن أستطيع التعامل مع الهاتف بشكل طبيعي جدًا، مثل:

> "افتح YouTube."

> "ابحث عن هذا."

> "ما هذا؟ اشرحه لي."

> "افتح هذا الملف."

> "ابحث عن شيء مشابه لهذا."

> "أرسله لمحمد."

> "ارجع."

> "مرر للأسفل."

> "افعل كل ما يلزم لإكمال هذه المهمة."

ويفهم Mushrea Code السياق الحالي وينفذ الخطوات اللازمة، مع الالتزام بقدرات Android الرسمية وسياسات الأمان والصلاحيات.

**ابدأ التنفيذ الآن من المشروع الحالي.**
