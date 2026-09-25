package com.example.ft8vox.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.qso.DecodeFilterTag
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
    val dialHz = longPreferencesKey("dial_hz")
    val protocolName = stringPreferencesKey("protocol_name")
    val selectedFreqHz = intPreferencesKey("selected_freq_hz")
    val txParity = intPreferencesKey("tx_parity")
    val holdTxFreq = booleanPreferencesKey("hold_tx_freq")
    val callFirst = stringPreferencesKey("call_first")
    val maxRetries = intPreferencesKey("max_retries")
    val cqOnly = booleanPreferencesKey("cq_only")
    val excludeWorked = booleanPreferencesKey("exclude_worked")
    val filterTags = stringSetPreferencesKey("filter_tags")
    val callFilter = stringPreferencesKey("call_filter")
    val ignoredCalls = stringSetPreferencesKey("ignored_calls")
    val txQueue = stringPreferencesKey("tx_queue")
    val macros = stringPreferencesKey("macros")
    val mapCqShowCall = booleanPreferencesKey("map_cq_show_call")
    val mapCqShowSnr = booleanPreferencesKey("map_cq_show_snr")
    val mapShowLinkText = booleanPreferencesKey("map_show_link_text")
    val voxTrigger = stringPreferencesKey("vox_trigger")
    val voxDelayMs = intPreferencesKey("vox_delay_ms")
    val voxThresholdDb = intPreferencesKey("vox_threshold_db")
    val txLeadTone = booleanPreferencesKey("tx_lead_tone")
    val txLeadToneMs = intPreferencesKey("tx_lead_tone_ms")
    val outputDevice = stringPreferencesKey("output_device")
    val pttDelayMs = intPreferencesKey("ptt_delay_ms")
    val watchdogMs = intPreferencesKey("watchdog_ms")
    val inputDevice = stringPreferencesKey("input_device")
    val inputGainDb = intPreferencesKey("input_gain_db")
    val txOffsetMs = intPreferencesKey("tx_offset_ms")
    val hlNewCqZone = booleanPreferencesKey("hl_new_cq_zone")
    val hlNewItu = booleanPreferencesKey("hl_new_itu")
    val hlNewEntity = booleanPreferencesKey("hl_new_entity")
    val hlNewGrid = booleanPreferencesKey("hl_new_grid")
    val hlNewPrefix = booleanPreferencesKey("hl_new_prefix")
    val hlNewCall = booleanPreferencesKey("hl_new_call")
    val workedStyle = stringPreferencesKey("worked_style")
    val beepOnMyCall = booleanPreferencesKey("beep_on_my_call")
    val endMarkMyCall = booleanPreferencesKey("end_mark_my_call")
    val endMarkActive = booleanPreferencesKey("end_mark_active")
    val themeMode = stringPreferencesKey("theme_mode")
    val fontSize = stringPreferencesKey("font_size")
    val waterfallPalette = stringPreferencesKey("waterfall_palette")
    val cloudLogEnabled = booleanPreferencesKey("cloudlog_enabled")
    val lotwEnabled = booleanPreferencesKey("lotw_enabled")
    val eqslEnabled = booleanPreferencesKey("eqsl_enabled")
    val lanServerEnabled = booleanPreferencesKey("lan_server_enabled")
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

/** 读取筛选项；旧版本只有 cqOnly 时迁移为「CQ」，缺失则用默认值。 */
private fun readFilterTags(prefs: Preferences, defaults: Set<DecodeFilterTag>): Set<DecodeFilterTag> {
    val stored = prefs[Keys.filterTags]
    if (stored != null) {
        return stored.mapNotNull { name -> DecodeFilterTag.entries.firstOrNull { it.name == name } }.toSet()
    }
    return when (prefs[Keys.cqOnly]) {
        true -> setOf(DecodeFilterTag.CQ)
        false, null -> defaults
    }
}

private fun splitLines(s: String?): List<String>? =
    s?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        myCall = this[Keys.myCall] ?: defaults.myCall,
        myGrid = this[Keys.myGrid] ?: defaults.myGrid,
        note = this[Keys.note] ?: defaults.note,
        // 波段名允许自定义（非空、≤16 字符）；未知段名不再被丢弃
        band = this[Keys.band]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 } ?: defaults.band,
        dialHz = (this[Keys.dialHz] ?: defaults.dialHz).takeIf { it in 0..BandPlan.MAX_FREQ_HZ } ?: defaults.dialHz,
        protocolName = this[Keys.protocolName] ?: defaults.protocolName,
        selectedFreqHz = this[Keys.selectedFreqHz] ?: defaults.selectedFreqHz,
        txParity = (this[Keys.txParity] ?: defaults.txParity).coerceIn(TX_PARITY_EVEN, TX_PARITY_AUTO),
        holdTxFreq = this[Keys.holdTxFreq] ?: defaults.holdTxFreq,
        callFirst = enumOr(Keys.callFirst, defaults.callFirst),
        maxRetries = (this[Keys.maxRetries] ?: defaults.maxRetries).coerceIn(1, 20),
        filterTags = readFilterTags(this, defaults.filterTags),
        callFilter = this[Keys.callFilter] ?: defaults.callFilter,
        ignoredCalls = this[Keys.ignoredCalls]
            ?.mapNotNull { it.trim().uppercase().takeIf { c -> c.isNotEmpty() } }
            ?.toSet()
            ?: defaults.ignoredCalls,
        txQueue = splitLines(this[Keys.txQueue]) ?: defaults.txQueue,
        macros = (splitLines(this[Keys.macros]) ?: defaults.macros).ifEmpty { defaults.macros },
        mapCqFlagShowCall = this[Keys.mapCqShowCall] ?: defaults.mapCqFlagShowCall,
        mapCqFlagShowSnr = this[Keys.mapCqShowSnr] ?: defaults.mapCqFlagShowSnr,
        mapShowLinkText = this[Keys.mapShowLinkText] ?: defaults.mapShowLinkText,
        voxTrigger = enumOr(Keys.voxTrigger, defaults.voxTrigger),
        voxDelayMs = (this[Keys.voxDelayMs] ?: defaults.voxDelayMs).coerceIn(50, 1000),
        voxThresholdDb = (this[Keys.voxThresholdDb] ?: defaults.voxThresholdDb).coerceIn(-60, -20),
        txLeadTone = this[Keys.txLeadTone] ?: defaults.txLeadTone,
        txLeadToneMs = (this[Keys.txLeadToneMs] ?: defaults.txLeadToneMs).coerceIn(0, 2000),
        outputDevice = this[Keys.outputDevice] ?: defaults.outputDevice,
        pttDelayMs = (this[Keys.pttDelayMs] ?: defaults.pttDelayMs).coerceIn(0, 500),
        watchdogMs = (this[Keys.watchdogMs] ?: defaults.watchdogMs).coerceIn(1000, 60000),
        inputDevice = this[Keys.inputDevice] ?: defaults.inputDevice,
        inputGainDb = (this[Keys.inputGainDb] ?: defaults.inputGainDb).coerceIn(-12, 30),
        txOffsetMs = (this[Keys.txOffsetMs] ?: defaults.txOffsetMs).coerceIn(0, 15000),
        highlightNewCqZone = this[Keys.hlNewCqZone] ?: defaults.highlightNewCqZone,
        highlightNewItu = this[Keys.hlNewItu] ?: defaults.highlightNewItu,
        highlightNewEntity = this[Keys.hlNewEntity] ?: defaults.highlightNewEntity,
        highlightNewGrid = this[Keys.hlNewGrid] ?: defaults.highlightNewGrid,
        highlightNewPrefix = this[Keys.hlNewPrefix] ?: defaults.highlightNewPrefix,
        highlightNewCall = this[Keys.hlNewCall] ?: defaults.highlightNewCall,
        workedStyle = enumOr(Keys.workedStyle, defaults.workedStyle),
        beepOnMyCall = this[Keys.beepOnMyCall] ?: defaults.beepOnMyCall,
        endMarkMyCall = this[Keys.endMarkMyCall] ?: defaults.endMarkMyCall,
        endMarkActive = this[Keys.endMarkActive] ?: defaults.endMarkActive,
        themeMode = enumOr(Keys.themeMode, defaults.themeMode),
        fontSize = enumOr(Keys.fontSize, defaults.fontSize),
        waterfallPalette = enumOr(Keys.waterfallPalette, defaults.waterfallPalette),
        cloudLogEnabled = this[Keys.cloudLogEnabled] ?: defaults.cloudLogEnabled,
        lotwEnabled = this[Keys.lotwEnabled] ?: defaults.lotwEnabled,
        eqslEnabled = this[Keys.eqslEnabled] ?: defaults.eqslEnabled,
        lanServerEnabled = this[Keys.lanServerEnabled] ?: defaults.lanServerEnabled,
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
        ).clamped(),
    )
}

private fun AppSettings.writeTo(prefs: MutablePreferences) {
    prefs[Keys.myCall] = myCall
    prefs[Keys.myGrid] = myGrid
    prefs[Keys.note] = note
    prefs[Keys.band] = band
    prefs[Keys.dialHz] = dialHz
    prefs[Keys.protocolName] = protocolName
    prefs[Keys.selectedFreqHz] = selectedFreqHz
    prefs[Keys.txParity] = txParity
    prefs[Keys.holdTxFreq] = holdTxFreq
    prefs[Keys.callFirst] = callFirst.name
    prefs[Keys.maxRetries] = maxRetries
    prefs[Keys.filterTags] = filterTags.map { it.name }.toSet()
    prefs[Keys.callFilter] = callFilter
    prefs[Keys.ignoredCalls] = ignoredCalls
    prefs[Keys.txQueue] = txQueue.joinToString("\n")
    prefs[Keys.macros] = macros.joinToString("\n")
    prefs[Keys.mapCqShowCall] = mapCqFlagShowCall
    prefs[Keys.mapCqShowSnr] = mapCqFlagShowSnr
    prefs[Keys.mapShowLinkText] = mapShowLinkText
    prefs[Keys.voxTrigger] = voxTrigger.name
    prefs[Keys.voxDelayMs] = voxDelayMs
    prefs[Keys.voxThresholdDb] = voxThresholdDb
    prefs[Keys.txLeadTone] = txLeadTone
    prefs[Keys.txLeadToneMs] = txLeadToneMs
    prefs[Keys.outputDevice] = outputDevice
    prefs[Keys.pttDelayMs] = pttDelayMs
    prefs[Keys.watchdogMs] = watchdogMs
    prefs[Keys.inputDevice] = inputDevice
    prefs[Keys.inputGainDb] = inputGainDb
    prefs[Keys.txOffsetMs] = txOffsetMs
    prefs[Keys.hlNewCqZone] = highlightNewCqZone
    prefs[Keys.hlNewItu] = highlightNewItu
    prefs[Keys.hlNewEntity] = highlightNewEntity
    prefs[Keys.hlNewGrid] = highlightNewGrid
    prefs[Keys.hlNewPrefix] = highlightNewPrefix
    prefs[Keys.hlNewCall] = highlightNewCall
    prefs[Keys.workedStyle] = workedStyle.name
    prefs[Keys.beepOnMyCall] = beepOnMyCall
    prefs[Keys.endMarkMyCall] = endMarkMyCall
    prefs[Keys.endMarkActive] = endMarkActive
    prefs[Keys.themeMode] = themeMode.name
    prefs[Keys.fontSize] = fontSize.name
    prefs[Keys.waterfallPalette] = waterfallPalette.name
    prefs[Keys.cloudLogEnabled] = cloudLogEnabled
    prefs[Keys.lotwEnabled] = lotwEnabled
    prefs[Keys.eqslEnabled] = eqslEnabled
    prefs[Keys.lanServerEnabled] = lanServerEnabled
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
