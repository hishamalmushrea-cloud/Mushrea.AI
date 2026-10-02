#!/usr/bin/env python3
"""Record the audit-contract completion (trail format 4) in the four documents that describe it."""

from pathlib import Path

ROOT = Path("/home/user/Mushrea.AI")
COMMIT = "395bbc7"

# --------------------------------------------------------------- ARCHITECTURE.md
a = ROOT / "docs/architecture/ARCHITECTURE.md"
s = a.read_text(encoding="utf-8")

old = "**ما تبقّى من العقد (مؤجَّل بصراحة، لا ادّعاء):** `paramsDigest` (بصمة بدل تخزين المعاملات) · `startedAt`/`endedAt` · والتدقيق الموحَّد للوكلاء والجدولة والشبكة: اليوم يغطّي **وكيل الجهاز وحده** (`verifyOutcome` أُنجز في المرحلة 2 بالحقلين `verified`/`verification`)."
assert s.count(old) == 1
s = s.replace(
    old,
    """**المرحلة 2 (تكملة):** `FORMAT_VERSION = 4` — أُكمل باقي العقد: `params_digest` (بصمة
HMAC‑SHA256 لأول 128 بت على المعاملات **بعد** `SecretRedaction`، بمفتاح يُولَّد مرة لكل عملية ⇒ تُقارَن
السطور داخل الجلسة ولا يمكن عكس البصمة إلى سرّ قصير من مستند يُسلَّم لطرف آخر)، و`started_at`/`ended_at`
+ `duration_ms` للأمر المنفَّذ فقط (أمر مرفوض أو محجوب بلا نافذة زمنية لأنه لم يُنفَّذ أصلًا — تُحذف
المفاتيح لا تُكتب أصفارًا). و`DeviceAuditLogTest` (9 اختبارات) يثبّت النسق الرابع والغياب المتعمَّد
للنافذة وثبات البصمة وتمييزها وعدم تسرّب أي قيمة بشكل اعتماد.

**ما تبقّى من العقد (مؤجَّل بصراحة، لا ادّعاء):** التدقيق الموحَّد للوكلاء والجدولة والشبكة: اليوم يغطّي
**وكيل الجهاز وحده**. و`verifyOutcome` أُنجز في المرحلة 2 بالحقلين `verified`/`verification`، فاكتمل
الثلاثي الذي كان ناقصًا (`paramsDigest` · `startedAt`/`endedAt` · `verifyOutcome`).""",
)
a.write_text(s, encoding="utf-8")
print("ARCHITECTURE §6.5 updated")

# ------------------------------------------------------------------ DEVICE_AGENT.md
d = ROOT / "docs/DEVICE_AGENT.md"
t = d.read_text(encoding="utf-8")

old = """- Bridge: polling loop in the accessibility service, workspace tracking, a verification verdict on
  every result (`verified` + `verification`: foreground check after open_app, existence and byte
  counts after file ops, the other side's own size after USB/SSH transfers, and an explicit
  "no independent check" for everything else), an availability gate in front of execution,
  confirmation notifications, stop handling, structured activity log"""
assert t.count(old) == 1
t = t.replace(
    old,
    """- Bridge: polling loop in the accessibility service, workspace tracking, a verification verdict on
  every result (`verified` + `verification`: foreground check after open_app, existence and byte
  counts after file ops, the other side's own size after USB/SSH transfers, and an explicit
  "no independent check" for everything else), an availability gate in front of execution,
  confirmation notifications, stop handling, structured activity log whose export carries a keyed
  `params_digest` instead of the parameters and a `started_at`/`ended_at`/`duration_ms` window for
  every command that actually executed (export format 4)""",
)
d.write_text(t, encoding="utf-8")
print("DEVICE_AGENT updated")

# ------------------------------------------------------------------ FEATURE_MATRIX.md
m = ROOT / "docs/development/FEATURE_MATRIX.md"
u = m.read_text(encoding="utf-8")

old = "| سجل تدقيق وكيل الجهاز (نسق 3 مع `verified`/`verification`) | ✅ | `DeviceAuditLogTest` (4) | ❌ لا دليل | لا | `Code Present` |"
assert u.count(old) == 1
u = u.replace(
    old,
    "| سجل تدقيق وكيل الجهاز (نسق 4: `verified`/`verification` + `params_digest` + نافذة `started_at`/`ended_at`/`duration_ms`) | ✅ | `DeviceAuditLogTest` (9) — ✅ مُنفَّذة في تشغيل CI | ❌ لا دليل | لا | `Code Present` |",
)

old = """3. **التحقق الشاشي لأدوات اللمس/الكتابة/التمرير/السحب والمكالمات والمشاركة:** لا يمكن إثباته من
   التطبيق نفسه (يُعلن كذلك في نتيجة كل أداة بدل ادّعاء النجاح)."""
assert u.count(old) == 1
u = u.replace(
    old,
    """3. **التحقق الشاشي لأدوات اللمس/الكتابة/التمرير/السحب والمكالمات والمشاركة:** لا يمكن إثباته من
   التطبيق نفسه (يُعلن كذلك في نتيجة كل أداة بدل ادّعاء النجاح).
   **ولا ينطبق على التدقيق:** عقد التدقيق اكتمل في نسق 4 (`params_digest` + `started_at`/`ended_at`)،
   والمتبقّي منه هو **التدقيق الموحَّد** عبر الوكلاء/الجدولة/الشبكة (يحتاج تعريف مالك لمخطط مشترك).""",
)
m.write_text(u, encoding="utf-8")
print("FEATURE_MATRIX updated")

# ------------------------------------------------------------------ PHASE1_AUDIT.md
p = ROOT / "docs/development/PHASE1_AUDIT.md"
v = p.read_text(encoding="utf-8")
old = "`paramsDigest`/`startedAt`/`endedAt` · "
assert v.count(old) == 1, v.count(old)
v = v.replace(old, "")
old = "التدقيق الموحَّد للوكلاء/الجدولة/الشبكة ·"
assert v.count(old) >= 1
v = v.replace(
    old,
    "التدقيق الموحَّد للوكلاء/الجدولة/الشبكة (يحتاج تعريف مالك) — أما عقد تدقيق وكيل الجهاز فقد اكتمل في نسق 4 (`params_digest` + `started_at`/`ended_at`، `395bbc7`) ·",
    1,
)
p.write_text(v, encoding="utf-8")
print("PHASE1_AUDIT deferral list updated")

# ------------------------------------------------------------------ DEVELOPMENT_LOG.md
log = ROOT / "docs/development/DEVELOPMENT_LOG.md"
w = log.read_text(encoding="utf-8")

old = "(4) التدقيق الموحَّد للوكلاء/الجدولة/الشبكة و`paramsDigest`/`startedAt`/`endedAt` مؤجَّلة."
assert w.count(old) == 1
w = w.replace(
    old,
    "(4) التدقيق الموحَّد للوكلاء/الجدولة/الشبكة **مؤجَّل** (يحتاج تعريف مالك لمخطط مشترك)، أما "
    "~~`paramsDigest`/`startedAt`/`endedAt`~~ فقد **نُفِّذت**: نسق التدقيق 4 بـ`params_digest` "
    "(HMAC على المعاملات بعد التنقيح) ونافذة `started_at`/`ended_at`/`duration_ms` للأمر المنفَّذ.",
)

anchor = "---\n\n## 2026-10-02 — Phase 1 — تدقيق الأساس"
assert w.count(anchor) == 1
entry = """---

## 2026-10-02 — Phase 2 (تكملة) — إكمال عقد التدقيق: بصمة المعاملات ونافذة التنفيذ

| البند | التفصيل |
|---|---|
| **الالتزام** | `feat(device): complete the audit contract with a parameter digest and the execution window` |
| **ما تغيّر** | `DeviceAuditLog.FORMAT_VERSION = 4`: كل سطر يحمل `params_digest` — بصمة **HMAC‑SHA256** (أول 128 بت، بصيغة `hmac-sha256-<hex>`) على المعاملات **بعد** تمريرها على `SecretRedaction`، بمفتاح يُولَّد مرة واحدة لكل عملية. الاختيار مقصود: تجزئة مجرّدة لسرّ قصير داخل مستند يُسلَّم لطرف آخر قابلة للكسر بالقوة الغاشمة، أما HMAC بمفتاح يموت مع العملية فيقارن السطور داخل الجلسة ولا يُعكس. وتُضاف البصمة لكل سطر له معاملات — بما فيه المرفوض والمحجوب، فالـ«ما طُلب» هو المهم هناك. `started_at` قبل تشغيل المنفّذ و`ended_at` لحظة كتابة السطر (= `ts`)، و`duration_ms` يُشتق عند التصدير. الأمر المرفوض/المحجوب **بلا نافذة زمنية** (لم يُنفَّذ أصلًا) فتُحذف المفاتيح ولا تُكتب أصفارًا. |
| **لماذا** | عقد التدقيق المكتوب في `ARCHITECTURE §6.5` يسمّي `paramsDigest`/`startedAt`/`endedAt`، وكان المنفَّذ منه ثلاثة حقول فقط (`actor` · `tool` · `decision`/`result` · `verifyOutcome`)؛ هذا البند «إكمال ما هو قائم» لا ميزة جديدة، ويمنح مراجعة الجلسة ما ينقصها: تمييز الأوامر بلا تخزين معاملات، وقياس زمن التنفيذ. |
| **الاختبارات** | `DeviceAuditLogTest` **9** اختبارات (كانت 4؛ +5): نسق 4 بحقوله · غياب النافذة المتعمَّد لأمر لم يُنفَّذ · ثبات البصمة وتمييزها للمعاملات المختلفة ومعاملات متطابقة · عدم بقاء أي قيمة بشكل اعتماد داخل البصمة · لا بصمة بلا معاملات. |
| **البناء والتحقق** | محليًا: الفاحصون الثلاثة ✅ + توازن الأقواس + XML (المعتاد، بلا JDK). وCI: تشغيل `36968230954` على `395bbc7` — `lint` ✅ · **اختبارات الوحدة ✅** · `assembleGithubDebug` + تصريف اختبارات الأجهزة ✅ · `assembleGithubRelease` بـR8 ✅ · `auto-format` أصلح سطرين طويلين **ودفع** `17f7b92` · مهمة `static-analysis` سقطت على **spotless** فقط (تنسيق، وقد أصلحه CI) لا على السلوك. |
| **حالة البند** | ✅ نُفِّذ ومُثبَت بالبناء والاختبارات، مع تصحيح تنسيقي من CI (لا تغيير سلوك). |

"""
w = w.replace(anchor, entry + anchor)
log.write_text(w, encoding="utf-8")
print("log: increment entry added")
