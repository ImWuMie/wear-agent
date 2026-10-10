package dev.undefinedteam.wearagent.session

/** Speech recognition lifecycle exposed from MainActivity to the chat UI. */
enum class VoiceState {
    /** Mic idle; the voice row shows its static label. */
    IDLE,

    /** Recognizer listening; payload is the latest partial transcription. */
    LISTENING,

    /** Recognition ended without a usable result. */
    ERROR,
}
