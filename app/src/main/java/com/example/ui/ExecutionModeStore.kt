package com.example.ui

import android.content.SharedPreferences
import com.example.data.model.ExecutionMode

/**
 * §36 execution-mode persistence.
 *
 * Scenario: the operator selects a mode, backgrounds the app and returns. The mode must
 * still be the one they chose instead of silently resetting to PAPER every launch.
 *
 * Only the mode *name* is stored — never credentials or session state (§3 zero-
 * persistence mandate). A stored live mode is restored only when a session is already
 * unlocked; otherwise the safe PAPER default applies, so a cold start can never
 * silently re-arm live order routing.
 */
object ExecutionModeStore {

    const val PREFS_NAME = "execution_mode_prefs"
    const val KEY_EXECUTION_MODE = "execution_mode"

    fun load(prefs: SharedPreferences, hasActiveSession: Boolean): ExecutionMode {
        val stored = prefs.getString(KEY_EXECUTION_MODE, null) ?: return ExecutionMode.PAPER
        val mode = runCatching { ExecutionMode.valueOf(stored) }.getOrNull()
            ?: return ExecutionMode.PAPER
        val isLive = mode == ExecutionMode.CAPPED_LIVE || mode == ExecutionMode.SCALED_LIVE
        return if (isLive && !hasActiveSession) ExecutionMode.PAPER else mode
    }

    fun save(prefs: SharedPreferences, mode: ExecutionMode) {
        prefs.edit().putString(KEY_EXECUTION_MODE, mode.name).apply()
    }
}
