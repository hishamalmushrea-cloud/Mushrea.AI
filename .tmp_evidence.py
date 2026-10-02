#!/usr/bin/env python3
"""Record the first real CI evidence (run 36965385897, head f2e68b7) in the three documents
and correct everything that was written while no execution evidence existed."""

from pathlib import Path

ROOT = Path("/home/user/Mushrea.AI")
RUN = "36965385897"
HEAD = "f2e68b7"

# ------------------------------------------------------------------ FEATURE_MATRIX.md
m = ROOT / "docs/development/FEATURE_MATRIX.md"
s = m.read_text(encoding="utf-8")

old = """> **الفرع:** `arena/01a0f971-mushrea-ai` · **الرأس عند الكتابة:** `dd6bc7c` · **التاريخ:** 2026-10-02
>
> **حالة الأدلة (مهم):** لا JDK/Gradle/Android SDK/جهاز محليًا، وتشغيل CI الكامل بعد تغييرات هذه
> الجلسة متوقّف على صلاحية `actions: write` (اليوم: `403`). لذلك كل خلية «Runtime Verified» أدناه
> تصف **الرأس الحالي** = **لا** — أي أن هذه المصفوفة **لا تدّعي** تشغيل أي سلوك على جهاز/محاكي.
>
> **وبيان أمين للفارق:** الأساس قبل تغييرات الجلسة (`1f551b4` وما قبله) له **تشغيلات PR خضراء
> موثَّقة** (`36800962203` · `36801349852`) تشمل `test-and-build` و`lint` و`static-analysis`؛
> أما ما بُني فوقه في هذه الجلسة (`0d11e9f` → رأس الفرع) فلا يملك أي دليل تشغيل، فقط `auto-format`.
> فالمطلوب ليس «بناءً من الصفر» بل **إعادة الدليل على الرأس الحالي**."""
assert s.count(old) == 1
new = """> **الفرع:** `arena/01a0f971-mushrea-ai` · **الرأس المُتحقَّق منه:** `f2e68b7` · **التاريخ:** 2026-10-02
>
> **حالة الأدلة الآن:** تشغيل **`36965385897`** على `f2e68b7` **نجح بكل مهامه** (بناء + اختبارات +
> lint + R8 + الفاحصون) — التفصيل في §4. لذلك:
>  * **Tested** صار يعني: الاختبارات **نُفِّذت فعلًا** في ذلك التشغيل، لا «مكتوبة فقط»؛
>  * **Runtime Verified** يبقى **لا** لكل ما يحتاج جهازًا: لم يُشغَّل أي اختبار على جهاز/محاكي،
>    واختبارات الأجهزة الـ23 **صُرِّفت فقط** ضمن التشغيل ولم تُنفَّذ.
>
> **الفارق مع الأساس:** الأساس `1f551b4` كان أخضر عبر تشغيلات PR (`36800962203` · `36801349852`)،
> وتغييرات هذه الجلسة لم يكن لها أي دليل تشغيل حتى تشغيل `36965385897` — وهو ما أعاد الدليل،
> بعد أن كشفت أول عملية بناء حقيقية **ست علل تصريف/موارد** في عمل الجلسة (§4.1)."""
s = s.replace(old, new)

old = "| 3 اختبارات (`feature/onboarding`) + `OnboardingGateTest` — مكتوبة، غير مُنفَّذة |"
assert s.count(old) == 1
s = s.replace(old, "| 3 اختبارات (`feature/onboarding`) + `OnboardingGateTest` — ✅ مُنفَّذة في تشغيل CI |")

old = "241 (`feature/chat`) + 61 (`core/api`) + 5 ملفات اختبار جهاز (E2E/Bidi/Image/Voice/Picker) — غير مُنفَّذة"
assert s.count(old) == 1
s = s.replace(
    old,
    "241 (`feature/chat`) + 61 (`core/api`) — ✅ مُنفَّذة في التشغيل · 5 ملفات اختبار جهاز **صُرِّفت ولم تُشغَّل**",
)

# a compact evidence section, inserted before the deferred list
anchor = "## 3) ما لا يمكن ادعاؤه اليوم"
assert s.count(anchor) == 1
evidence = """## 3) دليل التشغيل المُثبَت — تشغيل CI `36965385897` على `f2e68b7`

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

"""
s = s.replace(anchor, evidence + anchor)

old = """1. **البناء والاختبارات على الرأس الحالي:** لا JDK/Gradle/SDK محليًا، وتشغيل CI الكامل على الفرع
   يحتاج صلاحية `actions: write` (اليوم `403`) أو تشغيلًا يدويًا من الواجهة. الأساس `1f551b4` كان
   أخضر بالكامل عبر تشغيلات PR (`36800962203` · `36801349852` · `36796083093`)، وتغييرات هذه الجلسة
   (`0d11e9f` → `e7a6619`) بلا أي دليل تشغيل."""
assert s.count(old) == 1
s = s.replace(
    old,
    """1. **البناء والاختبارات:** ✅ **لم تبقَ فجوة على الرأس المُتحقَّق منه** (`f2e68b7`): تشغيل
   `36965385897` أثبت التصريف والاختبارات وlint وR8 (§3). يبقى محليًا `Cannot Verify` (لا
   JDK/Gradle/SDK)، ويبقى تشغيل `Android CI` نفسه على الفرع معطَّلًا بشرط `main`/PR وصلاحية
   `actions: write` (403) — فالدليل جاء من سير `Branch Verification` المؤقّت (§3).""",
)

old = """**ما يرفع الخلايا:** تشغيل `Android CI` كاملًا على رأس الفرع (يدويًا من الواجهة، أو تلقائيًا بعد
`actions: write`، أو بفتح PR) → `test-and-build` + `lint` + `static-analysis` خضراء؛ ثم جلسة جهاز
حقيقي/محاكي للأدوات المتعذّرة، مع لقطة سجل تُشار إليها في هذا الملف (رقم تشغيل أو `adb`)."""
assert s.count(old) == 1
s = s.replace(
    old,
    """**ما يرفع الخلايا الآن:** لا شيء متبقٍّ على مستوى البناء/الاختبارات (§3)، وتبقى **جلسة جهاز
حقيقي/محاكي** هي ما يرفع `Runtime Verified` للميزات المرتبطة بالعتاد، مع لقطة سجل تُشار إليها هنا
(رقم تشغيل أو `adb`). وتشغيل اختبارات الأجهزة الـ23 نفسها يحتاج جهازًا متصلًا (`connectedGithubDebugAndroidTest`),
وهو ما لا يملكه أي سير عمل في المستودع.""",
)

m.write_text(s, encoding="utf-8")
print("matrix: evidence section added, claims corrected")

# ------------------------------------------------------------ DEVELOPMENT_LOG.md
log = ROOT / "docs/development/DEVELOPMENT_LOG.md"
t = log.read_text(encoding="utf-8")

old = "| **البناء والتحقق الفعلي** |"
i = t.index(old)
j = t.index("\n", i)
row = t[i:j]
assert "Cannot Verify" in row
new_row = (
    "| **البناء والتحقق الفعلي** | ✅ **مُثبَت بأثر تشغيل:** تشغيل `36965385897` على `f2e68b7` نجح في المهام الخمس — "
    "`static-analysis` (الفاحصون الثلاثة + detekt + spotless) · تحديث قفل Termux · `lintGithubDebug` · "
    "**`testGithubDebugUnitTest` بـ1,403 اختبارًا (0 فشل)** + `assembleGithubDebug` + تصريف اختبارات الأجهزة · "
    "`assembleGithubRelease` مع R8. **وما زال `Cannot Verify — Environment Limitation`:** لا JDK/Gradle/SDK/جهاز "
    "محليًا، واختبارات الأجهزة (23) لم تُنفَّذ (صُرِّفت فقط)، وسلوك العتاد غير مُثبَت. وتشغيل CI جاء من سير "
    "`Branch Verification` المؤقّت لأن `Android CI` على الفرع مقيَّد بـ`main`/PR و`gh workflow run` يرد 403. |"
)
t = t[:i] + new_row + t[j + 1 :]

# the first real build found real defects: record it
anchor = "| **الفحوص العقدية الثلاثة** |"
assert t.count(anchor) == 1
defects = (
    "| **ما كشفه أول بناء فعلي (6 علل مُصلَحة)** | (1) الفرع لم يكن يُصرَّف: `DeviceAgentBridge` يستدعي "
    "`ToolVerification` غير الموجود (الصحيح `OutcomeVerification`) وناقص استيراد `DeviceAvailability`. "
    "(2) `WorkspaceExplorerScreen` بلا استيراد `LocalContext`. (3) خمس أيقونات تستخدم "
    "`?attr/colorControlNormal` من نطاق appcompat الذي خرج من الشجرة ⇒ فشل ربط موارد الإصدار "
    "(`9bdd00b`). (4) فاصلة عليا غير مهروبة في `values-fr` ⇒ AAPT2 `Invalid unicode escape` (`04aa559`). "
    "(5) قفل Termux مثبَّت على `proot 5.1.107.95` حُذف upstream من كل المرايا ⇒ أي بناء إصدار يفشل "
    "(`5be63a5` دفعه CI من أداة المشروع إلى `5.1.107.96`). (6) اختبار التثبيت الجديد يقارن base64 "
    "بتمثيل OkHttp الـhex (`f2e68b7`). والدرس: detekt/spotless لا يريان التصريف ولا الروابط. |\n"
)
k = t.index(anchor)
t = t[:k] + defects + t[k:]

old = "(1) تشغيل CI الكامل مطلوب لتأكيد التصريف والاختبارات."
assert t.count(old) == 1
t = t.replace(
    old,
    "(1) ~~تشغيل CI الكامل مطلوب~~ — **تم**: تشغيل `36965385897` أخضر بالكامل على `f2e68b7`؛ ويبقى "
    "مؤقّتًا سير `Branch Verification` على فرع الجلسة (يُزال قبل أي دمج أو عند توفر `actions: write`).",
)

old = "| **حالة المرحلة** | ✅ **مستقرة على مستوى الشجرة والتزامات نظيفة**، جزئية على مستوى التحقق الحقيقي: كل تغيير كود مرّ بالدورة Plan → Implement → (فحوص عقدية) → Review، واختبارات جديدة لكل منطق نقي، لكن **تشغيل الاختبارات والبناء لم يحدث بعد** (قيد البيئة/الصلاحية أعلاه) — ولا يُدَّعى غير ذلك. |"
assert t.count(old) == 1
t = t.replace(
    old,
    "| **حالة المرحلة** | ✅ **مكتملة بدليل تشغيل:** `36965385897` على `f2e68b7` — تصريف كامل + 1,403 اختبار وحدة + "
    "lint + R8 + فاحصون، بعد إصلاح ست علل حقيقية كشفها البناء الأول. وما بقي غير مُثبَت مُعلن صراحة: سلوك "
    "العتاد واختبارات الأجهزة الـ23 (تصريف فقط). |",
)
log.write_text(t, encoding="utf-8")
print("log: evidence row + defects row + status updated")

# ---------------------------------------------------------------- PHASE1_AUDIT.md
a = ROOT / "docs/development/PHASE1_AUDIT.md"
u = a.read_text(encoding="utf-8")

old = "**حتى ذلك الحين تبقى حالة البناء/الاختبارات `Cannot Verify — Environment Limitation` كما هو مُسجَّل في §13 وسجل التطوير.**"
assert u.count(old) == 1
u = u.replace(
    old,
    """**وبعد ذلك تحقّق الدليل فعلًا:** أُضيف سير مؤقّت على فرع الجلسة وحده (`Branch Verification`) يعمل عند
الدفع بلا أي صلاحية إضافية، فأنتج تشغيلًا ناجحًا بكل مهامه — `36965385897` على `f2e68b7` (تصريف + 1,403
اختبار وحدة + lint + R8 + الفاحصون، وقبله إصلاح ست علل تصريف/موارد حقيقية). التفصيل في §13 وفي سجل
التطوير. ويبقى `Cannot Verify` ما يحتاج جهازًا: اختبارات الأجهزة الـ23 لم تُنفَّذ (صُرِّفت فقط).""",
)

old = "| إصلاحات صغيرة خارجة عن الخطة | ✅ نُفِّذت | في التزامات 2.4/2.6 |"
assert u.count(old) == 1
u = u.replace(
    old,
    "| دليل البناء والاختبار | ✅ **مُثبَت** | `ci: run the full verification set on the session branch` + إصلاحات البناء | تشغيل `36965385897` على `f2e68b7`: الفاحصون الثلاثة + detekt + spotless ✅ · `lintGithubDebug` ✅ · **1,403 اختبار وحدة ✅ (0 فشل)** + `assembleGithubDebug` + تصريف اختبارات الأجهزة ✅ · `assembleGithubRelease` بـR8 ✅ — وست علل حقيقية كشفها البناء أُصلحت في `63a4548`/`9bdd00b`/`04aa559`/`5be63a5`/`f2e68b7` |\n"
    "| إصلاحات صغيرة خارجة عن الخطة | ✅ نُفِّذت | في التزامات 2.4/2.6 |",
)
a.write_text(u, encoding="utf-8")
print("audit: section 11 + outcome table updated")

# ------------------------------------------- the temporary workflow's own header
w = ROOT / ".github/workflows/verify-branch.yml"
v = w.read_text(encoding="utf-8")
old = """# product's CI policy. See docs/development/DEVELOPMENT_LOG.md (Phase 2, CI reality row)."""
assert v.count(old) == 1
v = v.replace(
    old,
    """# product's CI policy.
#
# Status: it produced the Phase 2 evidence (run 36965385897 on f2e68b7 — all five jobs green).
# It stays so later changes on this branch can still be verified, and must be removed before the
# branch is merged anywhere, or as soon as Android CI can be dispatched (the owner granting
# actions: write) or a pull request exists. See docs/development/DEVELOPMENT_LOG.md, Phase 2.""",
)
w.write_text(v, encoding="utf-8")
print("workflow header: status and removal condition recorded")
