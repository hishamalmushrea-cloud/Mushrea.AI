package com.mushrea.code.device

/**
 * Concept-level expansion of screen queries, so natural commands find controls no label shares
 * text with: "زر الرجوع" must land on the element whose content description is "Back", and
 * "search box" on the field hinted "بحث".
 *
 * A query's words are matched against concept term groups (both languages, written
 * post-normalization: AppResolver.normalize folds the alef seats, ة→ه and ى→ي). Every term of a
 * matched concept then scores against element labels — capped below a direct query hit — and role
 * words in the query ("زر", "button", "حقل", "field") add a bonus for elements carrying that
 * trait. A query that names no concept behaves exactly as before.
 */
object ScreenSearch {

    private val conceptGroups: Map<String, Set<String>> =
        mapOf(
            "back" to setOf("back", "رجوع", "الرجوع", "ارجع", "خلف"),
            "home" to setOf("home", "الرئيسيه", "رئيسيه"),
            "search" to setOf("search", "بحث", "البحث", "ابحث"),
            "settings" to setOf("settings", "اعدادات", "الاعدادات"),
            "play" to setOf("play", "تشغيل", "التشغيل", "شغل"),
            "pause" to setOf("pause", "ايقاف", "مؤقت"),
            "send" to setOf("send", "ارسال", "الارسال", "ارسل"),
            "delete" to setOf("delete", "حذف", "الحذف", "احذف", "ازاله"),
            "share" to setOf("share", "مشاركه", "شارك"),
            "like" to setOf("like", "اعجاب", "لايك"),
            "menu" to setOf("menu", "المزيد", "more", "قائمه", "القائمه"),
            "confirm" to setOf("confirm", "تاكيد", "التاكيد", "موافق", "موافقه", "ok", "نعم", "yes"),
            "cancel" to setOf("cancel", "الغاء", "الالغاء", "الغ", "تجاهل"),
            "next" to setOf("next", "التالي", "تالي"),
            "previous" to setOf("previous", "prev", "السابق"),
            "close" to setOf("close", "اغلاق", "الاغلاق", "اغلق", "dismiss"),
            "refresh" to setOf("refresh", "تحديث"),
            "login" to setOf("login", "signin", "دخول"),
            "logout" to setOf("logout", "خروج", "الخروج"),
            "buy" to setOf("buy", "purchase", "order", "شراء", "اشتري", "اطلب"),
            "cart" to setOf("cart", "سله", "السله"),
            "notifications" to setOf("notifications", "اشعارات", "الاشعارات", "تنبيهات"),
            "profile" to setOf("profile", "الشخصي"),
            "download" to setOf("download", "تنزيل", "تحميل"),
            "camera" to setOf("camera", "كاميرا", "الكاميرا"),
            "microphone" to setOf("microphone", "mic", "مايك", "ميكروفون"),
            "favorite" to setOf("favorite", "favourite", "star", "مفضله", "المفضله"),
        )

    private val termToConcept: Map<String, String> by lazy {
        buildMap {
            conceptGroups.forEach { (concept, terms) -> terms.forEach { term -> put(term, concept) } }
        }
    }

    private val CLICKABLE_ROLES = setOf("button", "زر", "زرار", "ايقونه", "icon")

    private val EDITABLE_ROLES = setOf("field", "box", "input", "حقل", "ادخال", "كتابه", "صندوق")

    /** The labels a query should additionally match, plus the element role the query asks for. */
    data class Expansion(
        val terms: Set<String>,
        val wantClickable: Boolean?,
        val wantEditable: Boolean?,
    ) {
        val isEmpty: Boolean
            get() = terms.isEmpty() && wantClickable == null && wantEditable == null
    }

    fun expand(query: String): Expansion {
        val words = AppResolver.normalize(query).split(' ').filter(String::isNotBlank).toSet()
        if (words.isEmpty()) return Expansion(emptySet(), null, null)
        val concepts = words.mapNotNull(termToConcept::get).toSet()
        val siblings = concepts.flatMap { conceptGroups.getValue(it) }.toSet()
        return Expansion(
            terms = siblings,
            wantClickable = if ((words & CLICKABLE_ROLES).isNotEmpty()) true else null,
            wantEditable = if ((words & EDITABLE_ROLES).isNotEmpty()) true else null,
        )
    }
}
