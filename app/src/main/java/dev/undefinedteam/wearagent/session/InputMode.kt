package dev.undefinedteam.wearagent.session

enum class InputMode {
    KEYBOARD,
    VOICE,
    ;

    companion object {
        fun fromStored(value: String?): InputMode = when (value) {
            VOICE.name -> VOICE
            else -> KEYBOARD
        }
    }
}
