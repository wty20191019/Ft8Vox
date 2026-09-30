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
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.DecodeFilterTag
import com.example.ft8vox.qso.FollowRoster
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
    val sameFreqTx = booleanPreferencesKey("same_freq_tx")
    // 自动程序（照 FT8CN 四项，见 docs/Ft8Vox.md）
    val autoSupervisionMinutes = intPreferencesKey("auto_supervision_minutes")
    val autoNoReplyLimit = intPreferencesKey("auto_no_reply_limit")
    val autoFollowCq = booleanPreferencesKey("auto_follow_cq")
    val autoCallFollow = booleanPreferencesKey("auto_call_follow")
    //
    // ---- 以下为**旧键（静默保留，便于回滚）**：不再读取、不再写入 ----
    // 旧「自动程序」档位 / 排序 / 重发 / 保护限制字段。
    @Suppress("unused")
    val autoMode = stringPreferencesKey("auto_mode")
    @Suppress("unused")
    val autoDecodeTiming = stringPreferencesKey("auto_decode_timing")
    @Suppress("unused")
    val autoAllowRepeat = booleanPreferencesKey("auto_allow_repeat")
    @Suppress("unused")
    val autoSort = stringPreferencesKey("auto_sort")
    @Suppress("unused")
    val autoReportPriority = booleanPreferencesKey("auto_report_priority")
    @Suppress("unused")
    val autoGiveUpRetry = booleanPreferencesKey("auto_give_up_retry")
    @Suppress("unused")
    val autoRetryLimit = intPreferencesKey("auto_retry_limit")
    @Suppress("unused")
    val autoStopNoQso = booleanPreferencesKey("auto_stop_no_qso")
    @Suppress("unused")
    val autoNoQsoMinutes = intPreferencesKey("auto_no_qso_minutes")
    @Suppress("unused")
    val autoStopTxTotal = booleanPreferencesKey("auto_stop_tx_total")
    @Suppress("unused")
    val autoTxTotalMinutes = intPreferencesKey("auto_tx_total_minutes")
    val cqOnly = booleanPreferencesKey("cq_only")
    val excludeWorked = booleanPreferencesKey("exclude_worked")
    val filterTags = stringSetPreferencesKey("filter_tags")
    val callFilter = stringPreferencesKey("call_filter")
    val ignoredCalls = stringSetPreferencesKey("ignored_calls")
    val followCalls = stringSetPreferencesKey("follow_calls")
    val autoFollowOrder = stringPreferencesKey("auto_follow_order")
    val cqPrefixes = stringPreferencesKey("cq_prefixes")
    val cqPrefixIndex = intPreferencesKey("cq_prefix_index")
    val mapCqShowCall = booleanPreferencesKey("map_cq_show_call")
    val mapCqShowSnr = booleanPreferencesKey("map_cq_show_snr")
    val mapShowLinkText = booleanPreferencesKey("map_show_link_text")
    val txLeadTone = booleanPreferencesKey("tx_lead_tone")
    val txLeadToneMs = intPreferencesKey("tx_lead_tone_ms")
    val outputDevice = stringPreferencesKey("output_device")
    val outputGainDb = intPreferencesKey("output_gain_db")
    val pttDelayMs = intPreferencesKey("ptt_delay_ms")
    val watchdogMs = intPreferencesKey("watchdog_ms")
    val inputDevice = stringPreferencesKey("input_device")
    val inputGainDb = intPreferencesKey("input_gain_db")
    val slotOffsetMs = intPreferencesKey("slot_offset_ms")
    // 解码高亮开关与「已通联呈现方式」不再持久化（docs/Ft8Vox.md：颜色恒启用）
    val beepOnMyCall = booleanPreferencesKey("beep_on_my_call")
    val themeMode = stringPreferencesKey("theme_mode")
    val fontSize = stringPreferencesKey("font_size")
    val waterfallHeight = stringPreferencesKey("waterfall_height")
    val sampleRate = stringPreferencesKey("sample_rate")
    // 解码预设不再持久化：改由 `AppSettings.decodePreset` 从解码参数反推（旧 `decode_preset` 键不再读写）
    val decodeTimeOsr = intPreferencesKey("decode_time_osr")
    val decodeFreqOsr = intPreferencesKey("decode_freq_osr")
    val decodeMinScore = intPreferencesKey("decode_min_score")
    val decodeLdpc = intPreferencesKey("decode_ldpc")
    val decodeMaxCandidates = intPreferencesKey("decode_max_candidates")
    val decodeMaxDecoded = intPreferencesKey("decode_max_decoded")
    val decodePasses = intPreferencesKey("decode_passes")
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

/**
 * CQ 前缀格子：与 [splitLines] 不同，**保留空格子**（空串＝普通 CQ），因此不能去空。
 *
 * `cqPrefixes` 的格子序号要稳定（选了第几格由 `cqPrefixIndex` 记住），去空会让下标错位。
 */
private fun readPrefixSlots(s: String?): List<String>? =
    s?.split('\n')?.map { it.trim() }?.takeIf { it.isNotEmpty() }

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    val followCalls = this[Keys.followCalls]
        ?.mapNotNull { it.trim().uppercase().takeIf { c -> c.isNotEmpty() } }
        ?.toSet()
        ?: defaults.followCalls
    // 「自动收录」顺序：去空、去重、只留仍在名单里的、并夹到上限（恒为 followCalls 子集）
    val autoFollowOrder = (splitLines(this[Keys.autoFollowOrder]) ?: defaults.autoFollowOrder)
        .mapNotNull { it.trim().uppercase().takeIf { c -> c.isNotEmpty() } }
        .distinct()
        .filter { it in followCalls }
        .take(FollowRoster.AUTO_MAX)
    // CQ 前缀格子：保留空格子（空串＝普通 CQ），最后按格子数夹一下选中的下标
    val cqPrefixes = readPrefixSlots(this[Keys.cqPrefixes]) ?: defaults.cqPrefixes
    val cqPrefixIndex = (this[Keys.cqPrefixIndex] ?: defaults.cqPrefixIndex)
        .coerceIn(0, (cqPrefixes.size - 1).coerceAtLeast(0))
    return AppSettings(
        myCall = this[Keys.myCall] ?: defaults.myCall,
        myGrid = this[Keys.myGrid] ?: defaults.myGrid,
        note = this[Keys.note] ?: defaults.note,
        // 波段名允许自定义（非空、≤16 字符）；未知段名不再被丢弃
        band = this[Keys.band]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 } ?: defaults.band,
        dialHz = (this[Keys.dialHz] ?: defaults.dialHz).takeIf { it in 0..BandPlan.MAX_FREQ_HZ } ?: defaults.dialHz,
        protocolName = this[Keys.protocolName] ?: defaults.protocolName,
        selectedFreqHz = this[Keys.selectedFreqHz] ?: defaults.selectedFreqHz,
        sameFreqTx = this[Keys.sameFreqTx] ?: defaults.sameFreqTx,
        auto = AutoProgramSettings(
            supervisionMinutes = (this[Keys.autoSupervisionMinutes]
                ?: defaults.auto.supervisionMinutes).coerceIn(0, 95),
            noReplyLimit = (this[Keys.autoNoReplyLimit]
                ?: defaults.auto.noReplyLimit).coerceIn(0, 30),
            autoAddCqToFollow = this[Keys.autoFollowCq] ?: defaults.auto.autoAddCqToFollow,
            autoCallFollow = this[Keys.autoCallFollow] ?: defaults.auto.autoCallFollow,
        ),
        filterTags = readFilterTags(this, defaults.filterTags),
        callFilter = this[Keys.callFilter] ?: defaults.callFilter,
        ignoredCalls = this[Keys.ignoredCalls]
            ?.mapNotNull { it.trim().uppercase().takeIf { c -> c.isNotEmpty() } }
            ?.toSet()
            ?: defaults.ignoredCalls,
        followCalls = followCalls,
        autoFollowOrder = autoFollowOrder,
        cqPrefixes = cqPrefixes,
        cqPrefixIndex = cqPrefixIndex,
        mapCqFlagShowCall = this[Keys.mapCqShowCall] ?: defaults.mapCqFlagShowCall,
        mapCqFlagShowSnr = this[Keys.mapCqShowSnr] ?: defaults.mapCqFlagShowSnr,
        mapShowLinkText = this[Keys.mapShowLinkText] ?: defaults.mapShowLinkText,
        txLeadTone = this[Keys.txLeadTone] ?: defaults.txLeadTone,
        txLeadToneMs = (this[Keys.txLeadToneMs] ?: defaults.txLeadToneMs).coerceIn(0, 2000),
        outputDevice = this[Keys.outputDevice] ?: defaults.outputDevice,
        outputGainDb = clampOutputGainDb(this[Keys.outputGainDb] ?: defaults.outputGainDb),
        pttDelayMs = (this[Keys.pttDelayMs] ?: defaults.pttDelayMs).coerceIn(0, 500),
        watchdogMs = (this[Keys.watchdogMs] ?: defaults.watchdogMs).coerceIn(1000, 60000),
        inputDevice = this[Keys.inputDevice] ?: defaults.inputDevice,
        inputGainDb = (this[Keys.inputGainDb] ?: defaults.inputGainDb).coerceIn(-12, 30),
        slotOffsetMs = (this[Keys.slotOffsetMs] ?: defaults.slotOffsetMs)
            .coerceIn(-SLOT_OFFSET_LIMIT_MS, SLOT_OFFSET_LIMIT_MS),
        beepOnMyCall = this[Keys.beepOnMyCall] ?: defaults.beepOnMyCall,
        themeMode = enumOr(Keys.themeMode, defaults.themeMode),
        fontSize = enumOr(Keys.fontSize, defaults.fontSize),
        waterfallHeight = enumOr(Keys.waterfallHeight, defaults.waterfallHeight),
        sampleRate = enumOr(Keys.sampleRate, defaults.sampleRate),
        decode = DecodeSettings(
            timeOsr = this[Keys.decodeTimeOsr] ?: defaults.decode.timeOsr,
            freqOsr = this[Keys.decodeFreqOsr] ?: defaults.decode.freqOsr,
            minScore = this[Keys.decodeMinScore] ?: defaults.decode.minScore,
            ldpcIterations = this[Keys.decodeLdpc] ?: defaults.decode.ldpcIterations,
            maxCandidates = this[Keys.decodeMaxCandidates] ?: defaults.decode.maxCandidates,
            maxDecoded = this[Keys.decodeMaxDecoded] ?: defaults.decode.maxDecoded,
            passes = this[Keys.decodePasses] ?: defaults.decode.passes,
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
    prefs[Keys.sameFreqTx] = sameFreqTx
    prefs[Keys.autoSupervisionMinutes] = auto.supervisionMinutes
    prefs[Keys.autoNoReplyLimit] = auto.noReplyLimit
    prefs[Keys.autoFollowCq] = auto.autoAddCqToFollow
    prefs[Keys.autoCallFollow] = auto.autoCallFollow
    prefs[Keys.filterTags] = filterTags.map { it.name }.toSet()
    prefs[Keys.callFilter] = callFilter
    prefs[Keys.ignoredCalls] = ignoredCalls
    prefs[Keys.followCalls] = followCalls
    prefs[Keys.autoFollowOrder] = autoFollowOrder.joinToString("\n")
    prefs[Keys.cqPrefixes] = cqPrefixes.joinToString("\n")
    prefs[Keys.cqPrefixIndex] = cqPrefixIndex
    prefs[Keys.mapCqShowCall] = mapCqFlagShowCall
    prefs[Keys.mapCqShowSnr] = mapCqFlagShowSnr
    prefs[Keys.mapShowLinkText] = mapShowLinkText
    prefs[Keys.txLeadTone] = txLeadTone
    prefs[Keys.txLeadToneMs] = txLeadToneMs
    prefs[Keys.outputDevice] = outputDevice
    prefs[Keys.outputGainDb] = outputGainDb
    prefs[Keys.pttDelayMs] = pttDelayMs
    prefs[Keys.watchdogMs] = watchdogMs
    prefs[Keys.inputDevice] = inputDevice
    prefs[Keys.inputGainDb] = inputGainDb
    prefs[Keys.slotOffsetMs] = slotOffsetMs
    prefs[Keys.beepOnMyCall] = beepOnMyCall
    prefs[Keys.themeMode] = themeMode.name
    prefs[Keys.fontSize] = fontSize.name
    prefs[Keys.waterfallHeight] = waterfallHeight.name
    prefs[Keys.sampleRate] = sampleRate.name
    prefs[Keys.decodeTimeOsr] = decode.timeOsr
    prefs[Keys.decodeFreqOsr] = decode.freqOsr
    prefs[Keys.decodeMinScore] = decode.minScore
    prefs[Keys.decodeLdpc] = decode.ldpcIterations
    prefs[Keys.decodeMaxCandidates] = decode.maxCandidates
    prefs[Keys.decodeMaxDecoded] = decode.maxDecoded
    prefs[Keys.decodePasses] = decode.passes
    prefs[Keys.decodeFMin] = decode.fMinHz
    prefs[Keys.decodeFMax] = decode.fMaxHz
}
