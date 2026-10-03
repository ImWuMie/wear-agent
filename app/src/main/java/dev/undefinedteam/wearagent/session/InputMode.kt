package dev.undefinedteam.wearagent.session

enum class InputMode {
    KEYBOARD,
    VOICE // todo,
    ;

    companion object {
        fun fromStored(value: String?): InputMode = when (value) {
            VOICE.name -> VOICE
            else -> KEYBOARD
        }
    }
}
