package dev.undefinedteam.wearagent.session

enum class ApiKind {
    COMPLETIONS,
    RESPONSES,
    ANTHROPIC,
    ;

    companion object {
        fun fromStored(value: String?): ApiKind =
            entries.firstOrNull { it.name == value } ?: COMPLETIONS
    }
}
