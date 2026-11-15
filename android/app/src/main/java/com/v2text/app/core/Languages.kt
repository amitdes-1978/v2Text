package com.v2text.app.core

/** Explicit language profiles. Mixed-language quality must be measured, not assumed. */
data class LanguageProfile(
    val code: String,
    val label: String,
    val whisperLanguage: String?,   // null = auto-detect
    val androidLocale: String,      // BCP-47 tag for the system on-device recognizer
    val prompt: String,             // decoder priming text (style / script hint)
    val fillers: List<String>,
)

object Languages {
    val COMMON_FILLERS = listOf("um", "umm", "uh", "uhh", "erm", "er", "ah", "hmm", "mm")

    val PROFILES: List<LanguageProfile> = listOf(
        LanguageProfile("en-IN", "English (India)", "en", "en-IN",
            "Office dictation in Indian English. Names, amounts in rupees, times like 3 pm.", COMMON_FILLERS),
        LanguageProfile("hi", "Hindi (हिंदी)", "hi", "hi-IN",
            "नमस्ते, यह हिंदी में बोला गया संदेश है।", COMMON_FILLERS + listOf("अं", "उम्म", "हम्म")),
        // Romanised Hinglish: decode as English with a code-switched prompt so Hindi words stay in Latin script.
        LanguageProfile("hinglish", "Hinglish (Roman)", "en", "en-IN",
            "Haan, main kal office aaunga. Meeting 3 baje hai, report bhej dena.", COMMON_FILLERS),
        LanguageProfile("gu", "Gujarati (ગુજરાતી)", "gu", "gu-IN",
            "નમસ્તે, આ ગુજરાતીમાં બોલાયેલો સંદેશ છે.", COMMON_FILLERS + listOf("અં")),
        LanguageProfile("auto", "Auto-detect", null, "en-IN", "", COMMON_FILLERS),
    )

    fun get(code: String): LanguageProfile = PROFILES.firstOrNull { it.code == code } ?: PROFILES.last()
}
