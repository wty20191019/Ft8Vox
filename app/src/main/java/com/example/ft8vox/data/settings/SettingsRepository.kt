package com.example.ft8vox.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ft8vox.data.BandPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val STORE_NAME = "ft8vox_settings"

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

/**
 * 设置持久化（DataStore Preferences）。
 *
 * 读取：暴露一个 [Flow]，设置变化会自动下发到 ViewModel。
 * 写入：统一走 [update]，在 DataStore 事务内做「读-改-写」，避免并发覆盖。
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    /** 全部设置；IO 异常时回落到默认值而不是让 Flow 终止。 */
    val settings: Flow<AppSettings> = appContext.settingsStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toAppSettings() }

    /** 读-改-写。 */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        appContext.settingsStore.edit { prefs ->
            transform(prefs.toAppSettings()).writeTo(prefs)
        }
    }

    /** 覆写全部设置。 */
    suspend fun set(settings: AppSettings) {
        appContext.settingsStore.edit { settings.writeTo(it) }
    }
}

private object Keys {
    val myCall = stringPreferencesKey("my_call")
    val myGrid = stringPreferencesKey("my_grid")
    val note = stringPreferencesKey("note")
    val band = stringPreferencesKey("band")
    val protocolName = stringPreferencesKey("protocol_name")
    val selectedFreqHz = intPreferencesKey("selected_freq_hz")
    val txParity = intPreferencesKey("tx_parity")
    val holdTxFreq = booleanPreferencesKey("hold_tx_freq")
    val callFirst = stringPreferencesKey("call_first")
    val maxRetries = intPreferencesKey("max_retries")
    val cqOnly = booleanPreferencesKey("cq_only")
    val excludeWorked = booleanPreferencesKey("exclude_worked")
    val callFilter = stringPreferencesKey("call_filter")
    val waterfallHeight = stringPreferencesKey("waterfall_height")
    val sampleRate = stringPreferencesKey("sample_rate")
    val decodePreset = stringPreferencesKey("decode_preset")
    val decodeTimeOsr = intPreferencesKey("decode_time_osr")
    val decodeFreqOsr = intPreferencesKey("decode_freq_osr")
    val decodeMinScore = intPreferencesKey("decode_min_score")
    val decodeLdpc = intPreferencesKey("decode_ldpc")
    val decodeMaxCandidates = intPreferencesKey("decode_max_candidates")
    val decodeMaxDecoded = intPreferencesKey("decode_max_decoded")
    val decodeFMin = intPreferencesKey("decode_f_min")
    val decodeFMax = intPreferencesKey("decode_f_max")
}

private inline fun <reified T : Enum<T>> Preferences.enumOr(key: Preferences.Key<String>, fallback: T): T {
    val name = this[key] ?: return fallback
    return enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        myCall = this[Keys.myCall] ?: defaults.myCall,
        myGrid = this[Keys.myGrid] ?: defaults.myGrid,
        note = this[Keys.note] ?: defaults.note,
        band = this[Keys.band]?.takeIf { BandPlan.contains(it) } ?: defaults.band,
        protocolName = this[Keys.protocolName] ?: defaults.protocolName,
        selectedFreqHz = this[Keys.selectedFreqHz] ?: defaults.selectedFreqHz,
        txParity = (this[Keys.txParity] ?: defaults.txParity).coerceIn(0, 1),
        holdTxFreq = this[Keys.holdTxFreq] ?: defaults.holdTxFreq,
        callFirst = enumOr(Keys.callFirst, defaults.callFirst),
        maxRetries = (this[Keys.maxRetries] ?: defaults.maxRetries).coerceIn(1, 20),
        cqOnly = this[Keys.cqOnly] ?: defaults.cqOnly,
        excludeWorked = this[Keys.excludeWorked] ?: defaults.excludeWorked,
        callFilter = this[Keys.callFilter] ?: defaults.callFilter,
        waterfallHeight = enumOr(Keys.waterfallHeight, defaults.waterfallHeight),
        sampleRate = enumOr(Keys.sampleRate, defaults.sampleRate),
        decodePreset = enumOr(Keys.decodePreset, defaults.decodePreset),
        decode = DecodeSettings(
            timeOsr = this[Keys.decodeTimeOsr] ?: defaults.decode.timeOsr,
            freqOsr = this[Keys.decodeFreqOsr] ?: defaults.decode.freqOsr,
            minScore = this[Keys.decodeMinScore] ?: defaults.decode.minScore,
            ldpcIterations = this[Keys.decodeLdpc] ?: defaults.decode.ldpcIterations,
            maxCandidates = this[Keys.decodeMaxCandidates] ?: defaults.decode.maxCandidates,
            maxDecoded = this[Keys.decodeMaxDecoded] ?: defaults.decode.maxDecoded,
            fMinHz = this[Keys.decodeFMin] ?: defaults.decode.fMinHz,
            fMaxHz = this[Keys.decodeFMax] ?: defaults.decode.fMaxHz,
        ),
    )
}

private fun AppSettings.writeTo(prefs: MutablePreferences) {
    prefs[Keys.myCall] = myCall
    prefs[Keys.myGrid] = myGrid
    prefs[Keys.note] = note
    prefs[Keys.band] = band
    prefs[Keys.protocolName] = protocolName
    prefs[Keys.selectedFreqHz] = selectedFreqHz
    prefs[Keys.txParity] = txParity
    prefs[Keys.holdTxFreq] = holdTxFreq
    prefs[Keys.callFirst] = callFirst.name
    prefs[Keys.maxRetries] = maxRetries
    prefs[Keys.cqOnly] = cqOnly
    prefs[Keys.excludeWorked] = excludeWorked
    prefs[Keys.callFilter] = callFilter
    prefs[Keys.waterfallHeight] = waterfallHeight.name
    prefs[Keys.sampleRate] = sampleRate.name
    prefs[Keys.decodePreset] = decodePreset.name
    prefs[Keys.decodeTimeOsr] = decode.timeOsr
    prefs[Keys.decodeFreqOsr] = decode.freqOsr
    prefs[Keys.decodeMinScore] = decode.minScore
    prefs[Keys.decodeLdpc] = decode.ldpcIterations
    prefs[Keys.decodeMaxCandidates] = decode.maxCandidates
    prefs[Keys.decodeMaxDecoded] = decode.maxDecoded
    prefs[Keys.decodeFMin] = decode.fMinHz
    prefs[Keys.decodeFMax] = decode.fMaxHz
}
