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
    val serverUrl: String = "",
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
    /**
     * 华为 **auth 域**（`auth.huaweicloud.com`）的 Cookie（V2.79 加）。
     *
     * 这是「静默重登」的钥匙：devcloud 域的 `SESSION_ID` 是 Session 级、静置
     * 30~60 分钟就失效，而 auth 域的登录凭据还在时，WebView 打开 `chat/home`
     * 会自动 302 过去再回跳，下发一份**全新的** `SESSION_ID`，用户不用输手机号。
     * 以前只采集 devcloud 域，auth 域从来没读过，所以这份长期凭据一直白放着。
     *
     * 判据见 `HwRenew`：为空则静默重登必然失败（登录页会停在输手机号那一步），
     * 直接跳过那 18 秒。
     */
    val hwAuthCookie: String = "",
    val hwPortalCookie: String = "",

    /**
     * 豆包（www.doubao.com）网页登录后采集的整串 Cookie（V2.66 重新接入）。
     *
     * 判据是 `sessionid` / `sid_tt` / `uid_tt` / `sid_guard` 这一组；纯 OkHttp 直打
     * `/alice/ug/campaign/landing/{get,checkin}`，不需要 a_bogus 签名。
     * 详见 `DoubaoWebCheckin.kt` 的类注释与 `docs/DOUBAO.md`。
     */
    val dbCookie: String = "",
    /** 上次探测成功的活动 scene，留空则按候选列表逐个试。 */
    val dbScene: String = "",

    /**
     * 智谱清言（chatglm.cn）网页登录后采集的整串 Cookie（V2.76 接入）。
     *
     * 只用到里面两个：`chatglm_token`（约 24h）与 `chatglm_refresh_token`（约 50 天）。
     * 签到走 `ZhipuCheckin` 纯 OkHttp（签名能本地算），不需要 WebView。
     * **判据必须看 JWT 的 `is_guest`** —— 游客也有一套完整 token。
     */
    val zpCookie: String = "",
    /** 设备号。留空则从 JWT 的 `device_id` 解，解不到按 uid 派生（实测不是硬校验）。 */
    val zpDeviceId: String = "",

    /**
     * 百度智能云 / 百度搭子 DuMate（console.bce.baidu.com）采集的整串 Cookie（V2.76 接入）。
     *
     * 关键两个：`BDUSS`（登录票据）与 `bce-user-info`（控制台身份，同时是 CSRF 令牌来源）。
     * 签到走 `DumateCheckin` 纯 OkHttp，接口是 `/api/dumate/points/{loginBonusInfo,loginBonus}`。
     */
    val qfCookie: String = "",

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
    private val KEY_HW_AUTH = stringPreferencesKey("hw_auth_cookie")
    private val KEY_HW_PORTAL = stringPreferencesKey("hw_portal_cookie")
    private val KEY_DB_COOKIE = stringPreferencesKey("db_cookie")
    private val KEY_DB_SCENE = stringPreferencesKey("db_scene")
    private val KEY_ZP_COOKIE = stringPreferencesKey("zp_cookie")
    private val KEY_ZP_DEVICE = stringPreferencesKey("zp_device")
    private val KEY_QF_COOKIE = stringPreferencesKey("qf_cookie")

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
            serverUrl = p[KEY_URL] ?: "",
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
            hwAuthCookie = d(p[KEY_HW_AUTH] ?: ""),
            hwPortalCookie = d(p[KEY_HW_PORTAL] ?: ""),
            dbCookie = d(p[KEY_DB_COOKIE] ?: ""),
            dbScene = p[KEY_DB_SCENE] ?: "",
            zpCookie = d(p[KEY_ZP_COOKIE] ?: ""),
            zpDeviceId = p[KEY_ZP_DEVICE] ?: "",
            qfCookie = d(p[KEY_QF_COOKIE] ?: ""),
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
            p[KEY_DB_SCENE] = s.dbScene
            p[KEY_ZP_DEVICE] = s.zpDeviceId

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
            putCred(KEY_HW_AUTH, s.hwAuthCookie)
            putCred(KEY_HW_PORTAL, s.hwPortalCookie)
            putCred(KEY_DB_COOKIE, s.dbCookie)
            putCred(KEY_ZP_COOKIE, s.zpCookie)
            putCred(KEY_QF_COOKIE, s.qfCookie)
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
