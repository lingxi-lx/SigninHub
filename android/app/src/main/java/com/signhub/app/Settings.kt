package com.signhub.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "signhub_settings")

data class AppSettings(
    // 遥控 PC 后端（原有）
    val serverUrl: String = "http://192.168.1.100:8787",
    val token: String = "",
    val reminderEnabled: Boolean = false,
    val reminderTime: String = "08:30",

    // 本地签到（新增，脱离电脑）
    val localMode: Boolean = false,
    val wbToken: String = "",
    val wbRefreshToken: String = "",
    val wbUid: String = "",
    val wbEnterpriseId: String = "",
    val wbDomain: String = "",
    val wbGlobal: Boolean = false,
    val traeToken: String = "",
    val traeDeviceId: String = "",
    val traeGlobal: Boolean = false,
    /** X-Cloudide-Session：约 14 天有效，用来把过期的 Cloud-IDE-Token 换新（~8h） */
    val traeSession: String = "",

    // Qoder CN（每日积分活动，dt- 开头 token + Cosy-MachineId）
    val qoderRegion: String = "cn",
    val qoderToken: String = "",
    // drt- 开头，有效期一年；token 过期时用它自动续期，手机上推荐只填这个
    val qoderRefresh: String = "",
    val qoderUid: String = "",
    val qoderMachineId: String = "",

    // MiniMax Code（每日签到 7 天一轮，mmoat_ 开头 token）
    val mmToken: String = "",
    val mmUserId: String = "",
    val mmDeviceId: String = "",

    // 华为码道（DevCloud PromptCenter，站内登录后采集的整串 Cookie）
    // cookie 必填（含 devclouddevuibjJ_SESSION_ID）；portalCookie 只用于读积分余额。
    val hwCookie: String = "",
    val hwPortalCookie: String = "",

    /**
     * 有凭据字段解密失败（Keystore 密钥被清 / 换机还原 / 密文损坏）。
     *
     * 此时凭据一律读成空串（不把乱码当 token 发出去），但**保存时必须拒绝覆盖**——
     * 否则任意一次设置保存就把仍然完好的密文抹成 ""，永久丢失（BUGS-v1.63 P1-1）。
     * 界面据此提示「凭据需要重新填写（系统密钥已变更）」。
     */
    val credsLost: Boolean = false,
)

object SettingsStore {
    private val KEY_URL = stringPreferencesKey("server_url")
    private val KEY_TOKEN = stringPreferencesKey("token")
    private val KEY_REMINDER_ON = booleanPreferencesKey("reminder_enabled")
    private val KEY_REMINDER_TIME = stringPreferencesKey("reminder_time")

    private val KEY_LOCAL_MODE = booleanPreferencesKey("local_mode")
    private val KEY_WB_TOKEN = stringPreferencesKey("wb_token")
    private val KEY_WB_REFRESH = stringPreferencesKey("wb_refresh_token")
    private val KEY_WB_UID = stringPreferencesKey("wb_uid")
    private val KEY_WB_ENT = stringPreferencesKey("wb_ent")
    private val KEY_WB_DOMAIN = stringPreferencesKey("wb_domain")
    private val KEY_WB_GLOBAL = booleanPreferencesKey("wb_global")
    private val KEY_TRAE_TOKEN = stringPreferencesKey("trae_token")
    private val KEY_TRAE_DEVICE = stringPreferencesKey("trae_device")
    private val KEY_TRAE_GLOBAL = booleanPreferencesKey("trae_global")
    private val KEY_TRAE_SESSION = stringPreferencesKey("trae_session")

    private val KEY_QD_REGION = stringPreferencesKey("qoder_region")
    private val KEY_QD_TOKEN = stringPreferencesKey("qoder_token")
    private val KEY_QD_REFRESH = stringPreferencesKey("qoder_refresh")
    private val KEY_QD_UID = stringPreferencesKey("qoder_uid")
    private val KEY_QD_MACHINE = stringPreferencesKey("qoder_machine")

    private val KEY_MM_TOKEN = stringPreferencesKey("mm_token")
    private val KEY_MM_UID = stringPreferencesKey("mm_uid")
    private val KEY_MM_DEVICE = stringPreferencesKey("mm_device")
    private val KEY_HW_COOKIE = stringPreferencesKey("hw_cookie")
    private val KEY_HW_PORTAL = stringPreferencesKey("hw_portal_cookie")

    /**
     * 读设置。
     *
     * 所有凭据字段都过一遍 SecretBox.decrypt：
     *   - 老版本存的明文（没有 `enc:v1:` 前缀）原样返回，行为完全不变；
     *   - 新版本存的密文在这里还原成真 token，调用方拿到的仍然是明文 token，
     *     所以下游（LocalCheckin / WbAuth / Notify）一行都不用改；
     *   - **解密失败（返回 SecretBox.DECRYPT_FAILED 哨兵）时映射成空串并置 credsLost**，
     *     绝不把哨兵或乱码当 token 发给上游（BUGS-v1.63 P1-1）。
     */
    fun flow(context: Context): Flow<AppSettings> = context.dataStore.data.map { p ->
        var lost = false
        fun d(v: String): String {
            val out = SecretBox.decrypt(v)
            if (out == SecretBox.DECRYPT_FAILED) { lost = true; return "" }
            return out
        }
        AppSettings(
            serverUrl = p[KEY_URL] ?: "http://192.168.1.100:8787",
            token = d(p[KEY_TOKEN] ?: ""),
            reminderEnabled = p[KEY_REMINDER_ON] ?: false,
            reminderTime = p[KEY_REMINDER_TIME] ?: "08:30",
            localMode = p[KEY_LOCAL_MODE] ?: false,
            wbToken = d(p[KEY_WB_TOKEN] ?: ""),
            wbRefreshToken = d(p[KEY_WB_REFRESH] ?: ""),
            wbUid = p[KEY_WB_UID] ?: "",
            wbEnterpriseId = p[KEY_WB_ENT] ?: "",
            wbDomain = p[KEY_WB_DOMAIN] ?: "",
            wbGlobal = p[KEY_WB_GLOBAL] ?: false,
            traeToken = d(p[KEY_TRAE_TOKEN] ?: ""),
            traeDeviceId = p[KEY_TRAE_DEVICE] ?: "",
            traeGlobal = p[KEY_TRAE_GLOBAL] ?: false,
            traeSession = d(p[KEY_TRAE_SESSION] ?: ""),
            qoderRegion = p[KEY_QD_REGION] ?: "cn",
            qoderToken = d(p[KEY_QD_TOKEN] ?: ""),
            qoderRefresh = d(p[KEY_QD_REFRESH] ?: ""),
            qoderUid = p[KEY_QD_UID] ?: "",
            qoderMachineId = p[KEY_QD_MACHINE] ?: "",
            mmToken = d(p[KEY_MM_TOKEN] ?: ""),
            mmUserId = p[KEY_MM_UID] ?: "",
            mmDeviceId = p[KEY_MM_DEVICE] ?: "",
            hwCookie = d(p[KEY_HW_COOKIE] ?: ""),
            hwPortalCookie = d(p[KEY_HW_PORTAL] ?: ""),
            credsLost = lost,
        )
    }

    /**
     * 保存设置。凭据字段落盘前统一加密（幂等：已是密文不会重复套娃）。
     * 非凭据字段（uid / region / 时间 / 开关）保持明文 —— 它们不是秘密，
     * 加密反而会让设置页的调试和迁移变复杂。
     *
     * **解密失败的字段必须跳过，不能写回**：`s.credsLost` 为真时，`s.xxxToken` 全是
     * 空串（读不出来），直接 `encrypt("")` 会把完好的密文覆盖成 ""，永久丢失（P1-1）。
     * 这里改成「读出来是空 且 credsLost」就不碰那个 key —— 保留原密文，等密钥恢复
     * 或用户重新登录时再自然更新。
     */
    suspend fun save(context: Context, s: AppSettings) {
        var cryptoFailed = false
        context.dataStore.edit { p ->
            p[KEY_URL] = s.serverUrl
            p[KEY_REMINDER_ON] = s.reminderEnabled
            p[KEY_REMINDER_TIME] = s.reminderTime
            p[KEY_LOCAL_MODE] = s.localMode
            p[KEY_WB_UID] = s.wbUid
            p[KEY_WB_ENT] = s.wbEnterpriseId
            p[KEY_WB_DOMAIN] = s.wbDomain
            p[KEY_WB_GLOBAL] = s.wbGlobal
            p[KEY_TRAE_DEVICE] = s.traeDeviceId
            p[KEY_TRAE_GLOBAL] = s.traeGlobal
            p[KEY_QD_REGION] = s.qoderRegion
            p[KEY_QD_UID] = s.qoderUid
            p[KEY_QD_MACHINE] = s.qoderMachineId
            p[KEY_MM_UID] = s.mmUserId
            p[KEY_MM_DEVICE] = s.mmDeviceId

            // 凭据字段：credsLost 时空值视为「读不出来」而不是「用户清空」，跳过不覆盖。
            // M3（BUGS-v2.61）：加密失败（Keystore 不可用）时也不写 —— encryptOrNull
            // 返回 null 就跳过这个 key，保留上一次的密文，由 UI 提示重启 App。
            // 原来 encrypt() 会退回明文，等于把 token 裸写进 DataStore。
            fun putCred(key: androidx.datastore.preferences.core.Preferences.Key<String>, v: String) {
                if (v.isEmpty() && s.credsLost) return
                if (v.isEmpty()) { p[key] = ""; return }
                val enc = SecretBox.encryptOrNull(v)
                if (enc == null) { cryptoFailed = true; return }
                p[key] = enc
            }
            putCred(KEY_TOKEN, s.token)
            putCred(KEY_WB_TOKEN, s.wbToken)
            putCred(KEY_WB_REFRESH, s.wbRefreshToken)
            putCred(KEY_TRAE_TOKEN, s.traeToken)
            putCred(KEY_TRAE_SESSION, s.traeSession)
            putCred(KEY_QD_TOKEN, s.qoderToken)
            putCred(KEY_QD_REFRESH, s.qoderRefresh)
            putCred(KEY_MM_TOKEN, s.mmToken)
            putCred(KEY_HW_COOKIE, s.hwCookie)
            putCred(KEY_HW_PORTAL, s.hwPortalCookie)
        }
        if (cryptoFailed) {
            // M3：明确告知「这次没存进去」，而不是静默把明文写下去。
            android.util.Log.e(
                "SettingsStore",
                "凭据加密失败，本次保存跳过密文字段（保留旧密文），请提示用户重启 App"
            )
        }
    }
}
