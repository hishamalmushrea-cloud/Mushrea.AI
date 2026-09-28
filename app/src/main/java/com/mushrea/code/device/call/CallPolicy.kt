package com.mushrea.code.device.call

import com.mushrea.code.device.AppResolver

/**
 * Who the agent may talk to, what it may say, and where it must stop and fetch the user
 * (spec sections 13/14/28/29/30/31/32). Everything defaults to the safe side: unknown numbers are
 * not answered, sensitive topics are never handled without an explicit user decision, and the
 * agent introduces itself as an automated assistant instead of impersonating the user (31).
 */
object CallPolicy {
    /** What to do with a ringing call, per rule (spec section 14). */
    enum class IncomingAction {
        /** Answer and run the conversation agent. */
        ALLOW_ASSISTANT,

        /** Answer, ask purpose, take a structured message, hang up, notify. */
        TAKE_MESSAGE,

        /** Let it ring (never auto-declined). */
        DO_NOT_ANSWER,
    }

    /** Where an agent utterance sits on the safety ladder (spec section 32). */
    enum class UtteranceClass {
        /** Questions, relaying user-provided info, messages, polite glue. */
        ALLOWED,

        /** Commitments and binding-sounding language — needs the user first (section 28). */
        REQUIRES_USER_CONFIRMATION,

        /** OTP/passwords/financial instructions: never spoken, no matter what (29/30). */
        REFUSED,
    }

    /** Caller-side requests the agent must escalate instead of acting on (sections 28-30). */
    private val sensitiveRequestKeywords =
        listOf(
            // English
            "otp",
            "verification code",
            "one time code",
            "one-time code",
            "password",
            "passcode",
            "pin code",
            "recovery code",
            "transfer money",
            "send money",
            "make the payment",
            "pay now",
            "buy it",
            "purchase it",
            "sign the contract",
            "agree to the contract",
            "cancel my account",
            "change my password",
            // Arabic, written post-normalization (alef folding, ة→ه, ى→ي)
            "رمز التحقق",
            "رمز التاكيد",
            "كود التحقق",
            "كلمه المرور",
            "كلمه السر",
            "الرقم السري",
            "رمز الاستعاده",
            "حول المال",
            "حول الفلوس",
            "ارسل المال",
            "ادفع الان",
            "قم بالدفع",
            "اشتري",
            "وقع العقد",
            "وافق على العقد",
            "الغي حسابي",
            "غير كلمه المرور",
        )

    /** Commitment markers are phrases, not substrings, to keep false positives cheap. */
    private val commitmentMarkers =
        listOf("اتعهد", "اوافق", "سادفع", "نتفق علي", "i promise", "we agree", "i confirm", "we have a deal")

    /**
     * True when the caller is asking for something the agent must never handle alone: money,
     * commitments, credentials, codes. Conservative: a miss costs nothing (the topic is treated
     * as ordinary conversation only if truly absent), so the list stays precise.
     */
    fun isSensitiveCallerRequest(utterance: String): Boolean {
        val normalized = AppResolver.normalize(utterance)
        if (normalized.isBlank()) return false
        val moneyPair =
            (normalized.contains("المال") || normalized.contains("الفلوس")) &&
                (normalized.contains("حول") || normalized.contains("ارسل") || normalized.contains("ادفع"))
        return sensitiveRequestKeywords.any(normalized::contains) || moneyPair ||
            (normalized.contains("رمز") && (normalized.contains("تحقق") || normalized.contains("تاكيد")))
    }

    /**
     * The identity disclosure the agent opens with (spec sections 4/31). The template is
     * customizable but the disclosure itself is not removable: a rendered line that lost the
     * assistant marker falls back to the default Arabic disclosure.
     */
    fun introLine(
        userName: String,
        purposeSummary: String,
        template: String? = null,
    ): String {
        val purpose = purposeSummary.takeIf { it.isNotBlank() }?.let { " " } ?: ""
        val custom = template?.takeIf { it.isNotBlank() }
        if (custom != null) {
            val rendered = custom.replace("{user}", userName).replace("{purpose}", purposeSummary)
            if (mentionsAssistant(rendered)) return rendered
        }
        return "مرحبا، أنا المساعد الآلي لـ $userName. تم مني التواصل معك$purpose."
    }

    private fun mentionsAssistant(text: String): Boolean {
        val lower = AppResolver.normalize(text).lowercase()
        return lower.contains("مساعد") || lower.contains("الي") || lower.contains("assistant") || lower.contains("automated")
    }

    /** Classifies an agent-intended utterance before it is ever spoken (spec section 32). */
    fun classifyAgentUtterance(text: String): UtteranceClass {
        val normalized = AppResolver.normalize(text)
        if (normalized.isBlank()) return UtteranceClass.REFUSED
        if (isSensitiveCallerRequest(text)) return UtteranceClass.REFUSED
        if (commitmentMarkers.any(normalized::contains)) return UtteranceClass.REQUIRES_USER_CONFIRMATION
        return UtteranceClass.ALLOWED
    }

    /**
     * Resolves the action for a ringing call: an explicit per-contact rule wins, then the
     * star default, then the safe fallback (unknown numbers are left ringing) — section 15.
     */
    fun resolveIncoming(
        callerLabel: String?,
        explicitRules: Map<String, IncomingAction>,
        allowKnownContactsByDefault: Boolean = false,
    ): IncomingAction {
        callerLabel?.let { label -> explicitRules[label]?.let { return it } }
        explicitRules["*"]?.let { return it }
        if (callerLabel != null && allowKnownContactsByDefault) return IncomingAction.TAKE_MESSAGE
        return IncomingAction.DO_NOT_ANSWER
    }
}
