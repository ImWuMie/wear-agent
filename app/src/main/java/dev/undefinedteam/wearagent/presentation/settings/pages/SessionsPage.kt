package dev.undefinedteam.wearagent.presentation.settings.pages

import androidx.compose.runtime.mutableStateOf
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage
import dev.undefinedteam.wearagent.session.SessionInfo
import dev.undefinedteam.wearagent.session.SessionLog

/** Snapshot state for the session list; shared so mutations re-render items. */
private class SessionsState {
    val listed = mutableStateOf(emptyList<SessionInfo>())
    fun refresh(log: SessionLog) {
        listed.value = log.sessions()
    }
}

/** Sessions category: pick, create, and delete chat sessions. */
val SessionsPage: SettingsPage = settingsPage("sessions", titleRes = R.string.sessions) { list ->
    val state = shared<SessionsState>("sessions") { SessionsState().also { it.refresh(sessions) } }

    fun refresh() = state.refresh(sessions)

    list.row(R.string.new_session) {
        launch {
            store.setSession(sessions.create())
            refresh()
        }
    }
    state.listed.value.forEach { session ->
        list.toggle(session.title, session.id == current.sessionId) {
            launch { store.setSession(session.id) }
        }
        if (session.id == current.sessionId) {
            list.row(R.string.delete_session) {
                launch {
                    sessions.deleteSession(session.id)
                    store.setSession(sessions.sessions().firstOrNull()?.id.orEmpty())
                    refresh()
                }
            }
        }
    }
}
