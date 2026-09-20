package com.buddy.lockguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.buddy.lockguard.core.RideConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.lockGuardStore: DataStore<Preferences> by preferencesDataStore(name = "lockguard_settings")

/**
 * 只存阈值。位置数据完全不落盘——这是隐私设计的硬约束。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val separationMeters = floatPreferencesKey("separation_meters")
        val stillConfirmMs = longPreferencesKey("still_confirm_ms")
        val level2Meters = floatPreferencesKey("level2_meters")
        val escalationMs = longPreferencesKey("escalation_ms")
        val freeMinutes = intPreferencesKey("free_minutes")
        val accuracyFilterMeters = floatPreferencesKey("accuracy_filter_meters")
    }

    val configFlow: Flow<RideConfig> = context.lockGuardStore.data.map { prefs ->
        val base = RideConfig()
        base.copy(
            separationMeters = prefs[Keys.separationMeters] ?: base.separationMeters,
            stillConfirmMs = prefs[Keys.stillConfirmMs] ?: base.stillConfirmMs,
            level2Meters = prefs[Keys.level2Meters] ?: base.level2Meters,
            escalationDurationMs = prefs[Keys.escalationMs] ?: base.escalationDurationMs,
            freeMinutes = prefs[Keys.freeMinutes] ?: base.freeMinutes,
            accuracyFilterMeters = prefs[Keys.accuracyFilterMeters] ?: base.accuracyFilterMeters,
        )
    }

    suspend fun current(): RideConfig = configFlow.first()

    suspend fun setSeparationMeters(value: Float) {
        context.lockGuardStore.edit { it[Keys.separationMeters] = value }
    }

    suspend fun setStillConfirmMs(value: Long) {
        context.lockGuardStore.edit { it[Keys.stillConfirmMs] = value }
    }

    suspend fun setLevel2Meters(value: Float) {
        context.lockGuardStore.edit { it[Keys.level2Meters] = value }
    }

    suspend fun setEscalationMs(value: Long) {
        context.lockGuardStore.edit { it[Keys.escalationMs] = value }
    }

    suspend fun setFreeMinutes(value: Int) {
        context.lockGuardStore.edit { it[Keys.freeMinutes] = value }
    }
}
