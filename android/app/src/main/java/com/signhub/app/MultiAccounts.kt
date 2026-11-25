package com.signhub.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Trae / Qoder / MiniMax 的多账号存储。
 *
 * 三家原来各只有「一组扁平字段」存在 DataStore 里（见 Settings.kt），也就是只能有一个账号。
 * 这里照 WorkBuddy 的 WbMulti.kt 做一份同样的结构：一个 SharedPreferences 文件存一个
 * JSONArray，每条账号是 JSON，键固定 "list"。
 *
 * 为什么不用 DataStore：多账号是「整个列表一起 read-modify-write」，用 SharedPreferences
 * 的同步 apply/commit 更好控制时序；而且这三份数据只在本机用，不需要 Flow 观察。
 */

/**
 * 多账号存储的公共工具。
 */
internal object AccountIds {
    /**
     * 由凭据内容算一个稳定 id。
     *
     * 以前缺 id 时用 `System.currentTimeMillis()` 兜底 —— 同一条记录每次读出来都是新 id，
     * 于是所有「按 id 更新 / 按 id 删除」全部失效（update 找不到行、remove 删不掉、
     * 界面每刷新一次就像多了个新账号）。改用内容哈希后，同一条凭据永远得到同一个 id。
     */
    fun stable(vararg parts: String): String {
        val src = parts.filter { it.isNotBlank() }.joinToString("|")
        if (src.isBlank()) return "acc-" + System.currentTimeMillis().toString()
        return try {
            val h = MessageDigest.getInstance("SHA-256")
                .digest(("signhub-acc:" + src).toByteArray(Charsets.UTF_8))
            "acc-" + h.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Exception) {
            "acc-" + Integer.toHexString(src.hashCode())
        }
    }
}

// ─────────────────────────── Trae Solo ───────────────────────────

data class TraeAccount(
    val id: String,
    val token: String = "",
    val session: String = "",
    val deviceId: String = "",
    val global: Boolean = false,
    val lastStatus: String = "",
    val lastMessage: String = "",
    val lastCheckAt: Long = 0L,
    val totalPoints: Int = -1,
    val streakDays: Int = -1,
    val usedPoints: Int = -1,
    /**
     * 读进来时的**原始密文**，只在「解不开」时需要用来保护数据（N1，BUGS-v2.1）。
     *
     * 解密失败时 [token] 会被 `decryptOrEmpty` 映射成空串，而 `toJson()` 里
     * `encrypt("")` 返回空串 → 一次 save 就把完好的密文抹掉。保留原密文后，
     * `encryptKeep("", tokenRaw)` 会原样写回，等用户重填或密钥恢复。
     */
    val tokenRaw: String = "",
    val sessionRaw: String = "",
    /**
     * 账号昵称（用户 m16091）。来自 `GetUserInfo` 的 `Result.ScreenName`。
     *
     * 网页登录时读不到（那个接口要 `x-cloudide-token`，网页只给 session），
     * 所以登录后由 [Nicknames] 后台补一次；读不到就留空，界面回落到「账号 N」。
     */
    val nickname: String = "",
    /**
     * 手机号（用户 m16555）。存的是**上游原样给的打码串**，例如 `166******71`。
     *
     * 为什么是打码串：`GetUserInfo` 只返回 `Result.NonPlainTextMobile`，
     * 中间 6 位被星号替换，服务端不下发明文（这是正确的做法，我们也不该存明文）。
     * 用户要的正是「手机号码带星号一起显示」—— 一眼能认出是哪个号，又不会把号码存进本机。
     *
     * 取不到就留空，界面回落到 [displayName] 的原有逻辑（昵称 / token 末位 / ID）。
     */
    val phone: String = "",
    /**
     * JWT 里的上游账号 uid（用户 m18704「每个账号积分都一致」的根因修复）。
     *
     * 同一个 Trae 账号重新登录一次就会得到**新 token + 新 session**，所以
     * `sameTrae()` 只比这两样时，同一个号会被当成多个号 —— 三张卡拿同一账号的三枚
     * token 去查余额，界面就是三个一样的数字。uid 不随登录轮换，才是「同一个人」的判据。
     *
     * 为空时退回原来的 token/session 比对（不会误合并）；登录/解析时用
     * `LocalCheckin.jwtUid(token)` 补。
     */
    val uid: String = "",
    /**
     * 用户自定义的显示名（用户 m02619）。
     *
     * 与 [nickname] 分开存：nickname 是上游接口返回的、每次登录都会被刷新，
     * 用户改的名字必须不被后续 upsert 覆盖，所以独立一个字段。
     * 留空 = 用自动名（[displayName] 的老逻辑）。
     */
    val alias: String = "",
) {
    fun displayName(): String = when {
        alias.isNotBlank() -> alias
        phone.isNotBlank() -> phone
        nickname.isNotBlank() -> nickname
        token.isNotBlank() -> "Trae · " + token.takeLast(6)
        session.isNotBlank() -> "Trae · session " + session.takeLast(6)
        else -> "Trae 账号"
    }

    fun metaLine(): String = buildList {
        if (deviceId.isNotBlank()) add("设备 $deviceId")
        add(if (global) "国际版" else "国内版")
    }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        // token / session 是「能直接拿去签到」的长期凭据，落盘前过 SecretBox。
        // 与 Settings.kt 同一套密钥（Android Keystore），老明文数据 decrypt 时原样返回，
        // 下次 save 自动变成密文，不需要迁移脚本。
        //
        // encryptKeep：明文为空但原密文还在（= 上次解密失败）时原样写回原密文，
        // 否则「解不开 → 映射成空 → 保存 → 覆盖」会把凭据彻底抹掉（N1，BUGS-v2.1）。
        put("token", SecretBox.encryptKeep(token, tokenRaw))
        put("session", SecretBox.encryptKeep(session, sessionRaw))
        put("deviceId", deviceId); put("global", global)
        put("lastStatus", lastStatus); put("lastMessage", lastMessage)
        put("lastCheckAt", lastCheckAt); put("totalPoints", totalPoints)
        put("streakDays", streakDays); put("usedPoints", usedPoints)
        put("nickname", nickname)
        put("phone", phone)
        put("uid", uid)
        put("alias", alias)
    }

    companion object {
        fun fromJson(o: JSONObject): TraeAccount {
            // 先解出明文再算 id：密文每次加密 IV 都不同，用密文算哈希会导致 id 每次变。
            // decryptOrEmpty 而不是 decrypt：哨兵值不能被当成凭据（N1，BUGS-v2.1）。
            val tokRaw = o.optString("token", "")
            val sesRaw = o.optString("session", "")
            val tok = SecretBox.decryptOrEmpty(tokRaw)
            val ses = SecretBox.decryptOrEmpty(sesRaw)
            return TraeAccount(
            // id 缺失时按凭据内容算稳定值，不再用时间戳（否则每次读都是新 id）
            id = o.optString("id", "").ifBlank {
                AccountIds.stable(tok, ses, o.optString("deviceId", ""))
            },
            token = tok,
            session = ses,
            deviceId = o.optString("deviceId", ""),
            global = o.optBoolean("global", false),
            lastStatus = o.optString("lastStatus", ""),
            lastMessage = o.optString("lastMessage", ""),
            lastCheckAt = o.optLong("lastCheckAt", 0L),
            totalPoints = o.optInt("totalPoints", -1),
            streakDays = o.optInt("streakDays", -1),
            usedPoints = o.optInt("usedPoints", -1),
            tokenRaw = tokRaw,
            sessionRaw = sesRaw,
            nickname = o.optString("nickname", ""),
            phone = o.optString("phone", ""),
            // uid 没存过（v2.27 之前的老数据）就现场从 JWT 里解一次 ——
            // 不写回磁盘也无所谓，判重时用得上就够了；下次 upsert 会顺带存下来。
            uid = o.optString("uid", "").ifBlank {
                if (tok.length >= 10) LocalCheckin.jwtUid(tok) else ""
            },
            alias = o.optString("alias", ""),
            )
        }
    }
}

object TraeAccountStore {
    private const val PREF = "trae_accounts"
    private const val KEY = "list"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<TraeAccount> {
        val raw = prefs(ctx).getString(KEY, "") ?: return mutableListOf()
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<TraeAccount>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(TraeAccount.fromJson(it)) }
            out
        } catch (e: Exception) {
            // 以前这里直接返回空列表：JSON 一旦损坏，界面上所有账号凭空消失，
            // 用户以为被删了，实际数据还在 prefs 里。至少留个日志，并且不要把损坏的
            // 原文覆盖掉（save 之前先备份一份原始串）。
            Log.e("TraeAccountStore", "账号列表解析失败，原文保留在 prefs[$PREF/$KEY]", e)
            prefs(ctx).edit().putString(KEY + "_broken", raw).apply()
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<TraeAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        // commit 而不是 apply：upsert 是「读-改-写」，用 apply 时两次连续调用可能
        // 后一次读到前一次落盘前的旧值，把刚加的账号覆盖掉。
        prefs(ctx).edit().putString(KEY, arr.toString()).commit()
    }

    /** 同一个人（uid / token / session 相同）就替换，否则追加；id 保留原值。 */
    fun upsert(ctx: Context, acc: TraeAccount): TraeAccount {
        val list = all(ctx)
        val idx = list.indexOfFirst {
            // uid 优先（用户 m18704）：同一个号重新登录会换 token + session，
            // 只有 uid 不变；不按 uid 判就会把同一个号存成好几条。
            (acc.uid.isNotBlank() && it.uid == acc.uid) ||
                (acc.token.isNotBlank() && it.token == acc.token) ||
                (acc.session.isNotBlank() && it.session == acc.session)
        }
        val saved = if (idx >= 0) {
            val old = list[idx]
            // 入参留空的字段沿用旧值：以前无条件 copy 会把 old.token / old.session
            // 冲成空串（比如登录只回 session 时，原本填好的 token 就被抹掉了）。
            acc.copy(
                id = old.id,
                token = acc.token.ifBlank { old.token },
                session = acc.session.ifBlank { old.session },
                // Raw 与明文同进退（BUGS-v2.63-291 P2-1）：解密失败时明文是空串、
                // 只有 Raw 还握着磁盘上的密文，这里不沿用就等于让 toJson 写出空串，
                // 旧密文被永久抹掉 —— 用户必须重登。范式见 WbMulti.kt:187-188。
                tokenRaw = if (acc.token.isBlank()) old.tokenRaw else acc.tokenRaw,
                sessionRaw = if (acc.session.isBlank()) old.sessionRaw else acc.sessionRaw,
                deviceId = acc.deviceId.ifBlank { old.deviceId },
                lastStatus = old.lastStatus, lastMessage = old.lastMessage,
                lastCheckAt = old.lastCheckAt, totalPoints = old.totalPoints,
                streakDays = old.streakDays, usedPoints = old.usedPoints,
                // 昵称是后台补的：这次没带（登录流程拿不到）就沿用旧的，别把已取到的名字抹掉
                nickname = acc.nickname.ifBlank { old.nickname },
                phone = acc.phone.ifBlank { old.phone },
                uid = acc.uid.ifBlank { old.uid },
                // 自定义名（用户 m02619）：登录/刷新流程带不上它，命中已有账号时沿用旧值，
                // 否则用户改过的名字会被下一次登录冲掉。清空改名走 update()。
                alias = acc.alias.ifBlank { old.alias },
            ).also { list[idx] = it }
        } else {
            list.add(acc); acc
        }
        save(ctx, list)
        return saved
    }

    fun update(ctx: Context, acc: TraeAccount) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == acc.id }
        if (idx >= 0) { list[idx] = acc; save(ctx, list) }
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    /**
     * 一次性迁移：把每个账号的 x-device-id 修成「按该账号凭据确定性派生」的值。
     *
     * 背景（用户 m11601 / m12030，v2.6 定论）：9074 的真实语义是「这个设备号在该账号下
     * **没有注册记录**」，上游文案「当前参与用户太多」是误导。三个版本都判错了：
     *   · v2.3 多账号共用主账号设备号 → 账号 2 用的是别人的注册记录 → 9074；
     *   · v2.4 每账号 `DeviceId.generate()` → 每次登录号都变，永远「未注册」→ 9074；
     *   · v2.5 把所有号统一成主账号那串 → 又回到 v2.3 的问题 → 9074；
     *   · 更糟的是每次 9074 都自动换号并写回设置，形成死循环（设备号被反复改成
     *     5250250676249340 → 5252260219850605 → 5256668681776575）。
     *
     * 正确做法（两条硬规则）：① 一个账号一个号，绝不共用；② 永久稳定。
     * 于是改成按账号凭据 `DeviceId.derive(session 或 token)` 派生 —— 同一账号无论重装、
     * 换机、过多久都算出同一串，一次注册长期有效。
     *
     * **为什么列表里的账号一律重算，而不是「已合法就保留」**：
     * v2.3~v2.5 给列表账号写的号全是 `DeviceId.generate()` 造的随机数（网页登录拿不到
     * 真实机器号），格式合法但服务端没有注册记录 —— 正是现在 9074 的那个号。既然它
     * 从来就没work过，保留它没有任何收益；换成确定性派生的号至少「稳定」，配合下面
     * 的提示（去客户端登录一次 / 手填真实机器号）才能真正修好。
     * 主账号不在这里动（它当前能签成功，见 LocalApi.init 里单独处理的逻辑）。
     *
     * @return 实际改动的账号数（0 表示无需迁移）
     */
    fun migrateDevices(ctx: Context): Int {
        val list = all(ctx)
        if (list.isEmpty()) return 0
        var changed = 0
        list.forEachIndexed { i, acc ->
            // v2.19：**已有合法号一律保留，绝不重算**。
            //
            // 旧实现无条件 `derive(session.ifBlank { token })` 覆盖，看起来「确定性派生很稳」，
            // 实际是个致命死循环：
            //   · seed 是 `session.ifBlank { token }` —— token 约 8 小时轮换、session 约 14 天轮换；
            //   · 每次 App 启动都重算一次，凭据一换号就跟着换；
            //   · 服务端看到的是「同一账号天天换新设备」，直接命中风控封禁（9074
            //     = ErrWorkRiskControlBanned，见官方错误码枚举）。
            // 用户两张截图的设备号从 5170205103104225 变成 6210351913917244 就是铁证。
            //
            // 设备号的唯一正确语义：**一次写定、终身不变**。只有空/非法才派生。
            if (DeviceId.isValid(acc.deviceId)) return@forEachIndexed
            val seed = acc.session.ifBlank { acc.token }
            if (seed.isBlank()) return@forEachIndexed
            val derived = DeviceId.derive(seed)
            if (derived.isNotBlank()) {
                list[i] = acc.copy(deviceId = derived)
                changed++
            }
        }
        if (changed > 0) {
            save(ctx, list)
            Log.i("TraeAccountStore", "按账号凭据重算设备号：改了 $changed 个账号")
        }
        return changed
    }
}

// ─────────────────────────── Qoder CN ───────────────────────────

data class QoderAccount(
    val id: String,
    val token: String = "",
    val refresh: String = "",
    val uid: String = "",
    val machineId: String = "",
    val region: String = "cn",
    val lastStatus: String = "",
    val lastMessage: String = "",
    val lastCheckAt: Long = 0L,
    val totalPoints: Int = -1,
    val streakDays: Int = -1,
    val usedPoints: Int = -1,
    /** 读进来的原始密文，用于「解不开时原样写回」（N1，BUGS-v2.1），见 TraeAccount 注释。 */
    val tokenRaw: String = "",
    val refreshRaw: String = "",
    /** 账号昵称（用户 m16091）：来自 `GET /api/v1/collaboration/users/me` 的 `nickname`。 */
    val nickname: String = "",
    /** 手机号打码串（用户 m16555）。Qoder 没有下发表单手机号，能读到才有值，见 TraeAccount 注释。 */
    val phone: String = "",
    /** 用户自定义的显示名（用户 m02619），留空则用自动名，见 TraeAccount.alias 注释。 */
    val alias: String = "",
) {
    fun displayName(): String = when {
        alias.isNotBlank() -> alias
        phone.isNotBlank() -> phone
        nickname.isNotBlank() -> nickname
        uid.isNotBlank() -> "Qoder · " + uid.take(8)
        refresh.isNotBlank() -> "Qoder · " + refresh.takeLast(6)
        token.isNotBlank() -> "Qoder · " + token.takeLast(6)
        else -> "Qoder 账号"
    }

    fun metaLine(): String = buildList {
        if (token.isNotBlank()) add("dt- token")
        if (refresh.isNotBlank()) add("drt- 自动续期")
        if (machineId.isNotBlank()) add("设备指纹已填")
    }.joinToString(" · ").ifBlank { "仅 refreshToken" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        // dt- token / drt- refreshToken 都是长期凭据，落盘前加密（见 TraeAccount.toJson 注释）
        put("token", SecretBox.encryptKeep(token, tokenRaw))
        put("refresh", SecretBox.encryptKeep(refresh, refreshRaw))
        put("uid", uid); put("machineId", machineId); put("region", region)
        put("lastStatus", lastStatus); put("lastMessage", lastMessage)
        put("lastCheckAt", lastCheckAt); put("totalPoints", totalPoints)
        put("streakDays", streakDays); put("usedPoints", usedPoints)
        put("nickname", nickname)
        put("phone", phone)
        put("alias", alias)
    }

    companion object {
        fun fromJson(o: JSONObject): QoderAccount {
            // 先解密再算 id / 判重，否则每次密文不同会让同一个账号变成两条
            val tokRaw = o.optString("token", "")
            val refRaw = o.optString("refresh", "")
            val tok = SecretBox.decryptOrEmpty(tokRaw)
            val ref = SecretBox.decryptOrEmpty(refRaw)
            return QoderAccount(
            id = o.optString("id", "").ifBlank {
                AccountIds.stable(o.optString("uid", ""), ref, tok)
            },
            token = tok,
            refresh = ref,
            uid = o.optString("uid", ""),
            machineId = o.optString("machineId", ""),
            region = o.optString("region", "cn"),
            lastStatus = o.optString("lastStatus", ""),
            lastMessage = o.optString("lastMessage", ""),
            lastCheckAt = o.optLong("lastCheckAt", 0L),
            totalPoints = o.optInt("totalPoints", -1),
            streakDays = o.optInt("streakDays", -1),
            usedPoints = o.optInt("usedPoints", -1),
            tokenRaw = tokRaw,
            refreshRaw = refRaw,
            nickname = o.optString("nickname", ""),
            phone = o.optString("phone", ""),
            alias = o.optString("alias", ""),
            )
        }
    }
}

object QoderAccountStore {
    private const val PREF = "qoder_accounts"
    private const val KEY = "list"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<QoderAccount> {
        val raw = prefs(ctx).getString(KEY, "") ?: return mutableListOf()
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<QoderAccount>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(QoderAccount.fromJson(it)) }
            out
        } catch (e: Exception) {
            Log.e("QoderAccountStore", "账号列表解析失败，原文保留在 prefs[$PREF/$KEY]", e)
            prefs(ctx).edit().putString(KEY + "_broken", raw).apply()
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<QoderAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).commit()
    }

    fun upsert(ctx: Context, acc: QoderAccount): QoderAccount {
        val list = all(ctx)
        val idx = list.indexOfFirst {
            (acc.refresh.isNotBlank() && it.refresh == acc.refresh) ||
                (acc.token.isNotBlank() && it.token == acc.token) ||
                (acc.uid.isNotBlank() && it.uid == acc.uid)
        }
        val saved = if (idx >= 0) {
            val old = list[idx]
            acc.copy(
                id = old.id,
                token = acc.token.ifBlank { old.token },
                refresh = acc.refresh.ifBlank { old.refresh },
                // 同上（P2-1）：drt- 是长期凭据，丢了只能重新扫码
                tokenRaw = if (acc.token.isBlank()) old.tokenRaw else acc.tokenRaw,
                refreshRaw = if (acc.refresh.isBlank()) old.refreshRaw else acc.refreshRaw,
                uid = acc.uid.ifBlank { old.uid },
                machineId = acc.machineId.ifBlank { old.machineId },
                lastStatus = old.lastStatus, lastMessage = old.lastMessage,
                lastCheckAt = old.lastCheckAt, totalPoints = old.totalPoints,
                streakDays = old.streakDays, usedPoints = old.usedPoints,
                nickname = acc.nickname.ifBlank { old.nickname },
                phone = acc.phone.ifBlank { old.phone },
                alias = acc.alias.ifBlank { old.alias },
            ).also { list[idx] = it }
        } else {
            list.add(acc); acc
        }
        save(ctx, list)
        return saved
    }

    fun update(ctx: Context, acc: QoderAccount) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == acc.id }
        if (idx >= 0) { list[idx] = acc; save(ctx, list) }
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }
}

// ─────────────────────────── MiniMax Code ───────────────────────────

data class MmAccount(
    val id: String,
    val token: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val lastStatus: String = "",
    val lastMessage: String = "",
    val lastCheckAt: Long = 0L,
    val totalPoints: Int = -1,
    val streakDays: Int = -1,
    val usedPoints: Int = -1,
    /** 读进来的原始密文，用于「解不开时原样写回」（N1，BUGS-v2.1），见 TraeAccount 注释。 */
    val tokenRaw: String = "",
    /**
     * 账号昵称（用户 m16091）。
     *
     * MiniMax 网页端没有可用的昵称接口（扫完 24 个 bundle，`nickname` 只出现在 i18n 文案里，
     * `/minimax-cloud/api/v1/user` 下的候选路径（info、profile、me 等 15 个）全是 404），
     * 所以这里主要靠登录时从 localStorage 的 `user_detail_agent` 里顺手读 name/nickname，
     * 读不到就留空，界面回落到「MiniMax · userId 前 8 位」。
     *
     * 注意：本文件任何注释里都不能出现斜杠加星号那种写法 —— Kotlin 的块注释可以嵌套，
     * 写进去会把外层的结束符吃掉，整个文件后半段变成注释（v2.21 踩过，报 Unclosed comment）。
     */
    val nickname: String = "",
    /** 手机号打码串（用户 m16555）。MiniMax 无昵称/手机号接口，只能登录时顺手读，见 TraeAccount 注释。 */
    val phone: String = "",
    /** 用户自定义的显示名（用户 m02619），留空则用自动名，见 TraeAccount 的 alias 注释。 */
    val alias: String = "",
) {
    fun displayName(): String = when {
        alias.isNotBlank() -> alias
        phone.isNotBlank() -> phone
        nickname.isNotBlank() -> nickname
        userId.isNotBlank() -> "MiniMax · " + userId.take(8)
        token.isNotBlank() -> "MiniMax · " + token.takeLast(6)
        else -> "MiniMax 账号"
    }

    fun metaLine(): String = buildList {
        if (deviceId.isNotBlank()) add("设备 " + deviceId.take(8))
        add("mmoat_ token")
    }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        // mmoat_ token 是长期凭据，落盘前加密（见 TraeAccount.toJson 注释）
        put("token", SecretBox.encryptKeep(token, tokenRaw))
        put("userId", userId); put("deviceId", deviceId)
        put("lastStatus", lastStatus); put("lastMessage", lastMessage)
        put("lastCheckAt", lastCheckAt); put("totalPoints", totalPoints)
        put("streakDays", streakDays); put("usedPoints", usedPoints)
        put("nickname", nickname)
        put("phone", phone)
        put("alias", alias)
    }

    companion object {
        fun fromJson(o: JSONObject): MmAccount {
            val tokRaw = o.optString("token", "")
            val tok = SecretBox.decryptOrEmpty(tokRaw)
            return MmAccount(
            id = o.optString("id", "").ifBlank {
                AccountIds.stable(o.optString("userId", ""), tok)
            },
            token = tok,
            userId = o.optString("userId", ""),
            deviceId = o.optString("deviceId", ""),
            lastStatus = o.optString("lastStatus", ""),
            lastMessage = o.optString("lastMessage", ""),
            lastCheckAt = o.optLong("lastCheckAt", 0L),
            totalPoints = o.optInt("totalPoints", -1),
            streakDays = o.optInt("streakDays", -1),
            usedPoints = o.optInt("usedPoints", -1),
            tokenRaw = tokRaw,
            nickname = o.optString("nickname", ""),
            phone = o.optString("phone", ""),
            alias = o.optString("alias", ""),
            )
        }
    }
}

object MmAccountStore {
    private const val PREF = "mm_accounts"
    private const val KEY = "list"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<MmAccount> {
        val raw = prefs(ctx).getString(KEY, "") ?: return mutableListOf()
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<MmAccount>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(MmAccount.fromJson(it)) }
            out
        } catch (e: Exception) {
            Log.e("MmAccountStore", "账号列表解析失败，原文保留在 prefs[$PREF/$KEY]", e)
            prefs(ctx).edit().putString(KEY + "_broken", raw).apply()
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<MmAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).commit()
    }

    fun upsert(ctx: Context, acc: MmAccount): MmAccount {
        val list = all(ctx)
        val idx = list.indexOfFirst {
            (acc.token.isNotBlank() && it.token == acc.token) ||
                (acc.userId.isNotBlank() && it.userId == acc.userId)
        }
        val saved = if (idx >= 0) {
            val old = list[idx]
            acc.copy(
                id = old.id,
                token = acc.token.ifBlank { old.token },
                userId = acc.userId.ifBlank { old.userId },
                // 同上（P2-1）：token 解不开时保留原密文，别写出空串
                tokenRaw = if (acc.token.isBlank()) old.tokenRaw else acc.tokenRaw,
                deviceId = acc.deviceId.ifBlank { old.deviceId },
                lastStatus = old.lastStatus, lastMessage = old.lastMessage,
                lastCheckAt = old.lastCheckAt, totalPoints = old.totalPoints,
                streakDays = old.streakDays, usedPoints = old.usedPoints,
                nickname = acc.nickname.ifBlank { old.nickname },
                phone = acc.phone.ifBlank { old.phone },
                alias = acc.alias.ifBlank { old.alias },
            ).also { list[idx] = it }
        } else {
            list.add(acc); acc
        }
        save(ctx, list)
        return saved
    }

    fun update(ctx: Context, acc: MmAccount) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == acc.id }
        if (idx >= 0) { list[idx] = acc; save(ctx, list) }
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }
}

// ─────────────────────────── 华为码道 ───────────────────────────

/**
 * 华为码道账号（用户 m24258：「把华为码道新增多账号登录」）。
 *
 * 此前华为是单账号：一份 Cookie = 一个会话，换号就是覆盖。之所以能做多账号，
 * 是因为华为的凭据**就是整条 Cookie**（`devclouddevuibjJ_SESSION_ID` +
 * `agencyID` + `devclouddevuibjtcftk`），每个账号一套互不干扰，没有设备指纹绑定 ——
 * 不像 Trae 有 `x-device-id`、Qoder 有 `Cosy-MachineId` 这类跨账号必须唯一的字段。
 *
 * ## 账号身份（BUGS-v2.46 P1）
 * 最初 `id` 与判重都用「整条 Cookie」的哈希，于是**重新登录一次就被当成全新账号**：
 * 华为的 `devclouddevuibjJ_SESSION_ID` 是会话级的，重登必然换一个。三处后果同时出现
 * （账号列表多一条同名项、`HistoryStore` 的 `account_key` 跟着变导致当天两条记录分裂、
 * `HwTodayMark` 桶名变了导致「今日已签」标记读不到）。
 *
 * 现在身份统一由 [identityOf] 决定：
 *   1. **优先 `agencyID`** —— 华为只在真正登录后补发这个附属 cookie，它跟账号走、
 *      不跟会话走（`shortId()` 早就拿它区分账号）。这是唯一能跨重登保持稳定的字段。
 *   2. 拿不到 `agencyID` 时回退「整条 Cookie 的哈希」—— 与旧行为一致，
 *      至少不会把同一份会话拆成两条。
 *
 * 注意：`agencyID` 在码道前端 bundle 里 0 命中（前端不引用它），所以无法从代码层
 * 证明它一定稳定。因此 `HwAccountStore.upsert` 做成**三级判重 + 命中即保留旧 id**：
 * 即使哪天 `agencyID` 变了，也只是退化成「按 Cookie 判重」的旧行为，不会更糟。
 */
data class HwAccount(
    val id: String,
    val cookie: String = "",
    /**
     * auth 域（`auth.huaweicloud.com`）Cookie（V2.79 加）—— 静默重登的钥匙。
     *
     * 每个华为账号在 auth 域都有自己的一份登录凭据，所以**必须按账号存**，
     * 不能共用设置页里那一份（多账号时共用会把 A 号的凭据拿去给 B 号续期）。
     * 留空则该账号走不了自动续期，只能手动重登。
     */
    val authCookie: String = "",
    val lastStatus: String = "",
    val lastMessage: String = "",
    val lastCheckAt: Long = 0L,
    val totalPoints: Int = -1,
    val streakDays: Int = -1,
    val usedPoints: Int = -1,
    /** 读进来的原始密文，用于「解不开时原样写回」（N1，BUGS-v2.1），见 TraeAccount 注释。 */
    val cookieRaw: String = "",
    /**
     * auth 域 Cookie 的原始密文（BUGS-v2.63-290 P3-4）。
     *
     * 与 [cookieRaw] 同理：Keystore 暂时不可用（设备刚开机未解锁、系统密钥失效、
     * 个别 ROM 异常）时 `encryptOrNull` 返回 null，以前那行会兜成 ""，
     * 把磁盘上原有的 auth 密文覆盖成空 —— 之后华为会话过期、`HwRenew` 没有
     * auth 域凭据可用，静默重登永久失效，用户只能重新扫码。
     */
    val authCookieRaw: String = "",
    /** 备注名（华为没有昵称/手机号接口，只能用户自己填，留空则显示短 id）。 */
    val nickname: String = "",
    /**
     * 用户自定义的显示名（用户 m02619）。
     *
     * 华为这里与 [nickname] 语义重合（两者都只能手填），保留两个字段是为了跟其它三家
     * 的 `displayName()` 优先级链一致；[alias] 优先，[nickname] 作为历史数据兜底
     * （V2.67 之前用户填的备注都写在 nickname 里）。
     */
    val alias: String = "",
) {
    fun displayName(): String = when {
        alias.isNotBlank() -> alias
        nickname.isNotBlank() -> nickname
        else -> "华为 · " + shortId()
    }

    /** cookie 里 `agencyID` 的后 6 位；取不到就用 id 前 6 位。 */
    fun shortId(): String {
        val v = agencyOf(cookie)
        if (v.length >= 6) return v.takeLast(6)
        return id.take(6)
    }

    fun metaLine(): String = buildList {
        add(if (cookie.contains("agencyID=")) "含 agencyID" else "缺 agencyID")
        add("Cookie " + cookie.length + " 字符")
    }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        // 整条 Cookie 就是凭据，落盘前加密（见 TraeAccount.toJson 注释）
        put("cookie", SecretBox.encryptKeep(cookie, cookieRaw))
        // 加密失败（Keystore 暂不可用）必须保留原密文，不能写成空串 —— 见 authCookieRaw 注释
        put("authCookie", SecretBox.encryptKeep(authCookie, authCookieRaw))
        put("lastStatus", lastStatus); put("lastMessage", lastMessage)
        put("lastCheckAt", lastCheckAt); put("totalPoints", totalPoints)
        put("streakDays", streakDays); put("usedPoints", usedPoints)
        put("nickname", nickname)
        put("alias", alias)
    }

    companion object {
        /**
         * 账号身份键（BUGS-v2.46 P1）：优先 `agencyID`，拿不到回退整条 Cookie。
         *
         * 用它派生 `id`、判重、以及 `HwTodayMark` / `HwVerified` 的分桶 —— 三处必须是
         * 同一个口径，只改一处会让键更乱（报告原话）。
         *
         * 放在 companion 里而不是实例方法：`fromJson` 要先算 id 才能构造实例，
         * 实例方法在那个位置用不了（`HwAccount.identityOf(...)` 会报 Unresolved reference）。
         */
        fun identityOf(cookie: String): String {
            val a = agencyOf(cookie)
            return if (a.isNotBlank()) "agency:" + a else "cookie:" + cookie
        }

        /** 从 Cookie 里抠 `agencyID` 的值；没有返回空串。 */
        fun agencyOf(cookie: String): String {
            val m = Regex("agencyID=([^;]+)").find(cookie) ?: return ""
            return m.groupValues.getOrNull(1)?.trim().orEmpty()
        }

        /**
         * 键派生函数（BUGS-v2.48 P3-2）：`identityOf` 的 md5 前 12 位（48 位十六进制）。
         *
         * `HwTodayMark.bucket` 与 `HwVerified.key` 都调它、各自加前缀 —— 早先两处各写一套
         * （一处 md5 前 12 位、一处 `String.hashCode()` 的 32 位），注释却都说「同一个身份口径」。
         * 只有 `identityOf` 那层相同，哈希算法与位宽都不同：`hashCode` 是 32 位，
         * 按生日界约 7.7 万个键就有 50% 碰撞概率。实际账号数远达不到，但两处口径不一致
         * 会让后来人误以为「它们是一回事」，改 `identityOf` 或统一清理时踩坑。
         */
        fun keyOf(cookie: String): String {
            if (cookie.isBlank()) return ""
            return try {
                val d = java.security.MessageDigest.getInstance("MD5")
                    .digest(identityOf(cookie).toByteArray(Charsets.UTF_8))
                val sb = StringBuilder(d.size * 2)
                for (x in d) {
                    val v = x.toInt() and 0xFF
                    if (v < 16) sb.append('0')
                    sb.append(Integer.toHexString(v))
                }
                sb.substring(0, 12)
            } catch (_: Exception) {
                Integer.toHexString(identityOf(cookie).hashCode())
            }
        }

        fun fromJson(o: JSONObject): HwAccount {
            val raw = o.optString("cookie", "")
            val ck = SecretBox.decryptOrEmpty(raw)
            val authRaw = o.optString("authCookie", "")
            return HwAccount(
                id = o.optString("id", "").ifBlank { AccountIds.stable(identityOf(ck)) },
                cookie = ck,
                authCookie = SecretBox.decryptOrEmpty(authRaw),
                lastStatus = o.optString("lastStatus", ""),
                lastMessage = o.optString("lastMessage", ""),
                lastCheckAt = o.optLong("lastCheckAt", 0L),
                totalPoints = o.optInt("totalPoints", -1),
                streakDays = o.optInt("streakDays", -1),
                usedPoints = o.optInt("usedPoints", -1),
                cookieRaw = raw,
                authCookieRaw = authRaw,
                nickname = o.optString("nickname", ""),
                alias = o.optString("alias", ""),
            )
        }
    }
}

object HwAccountStore {
    private const val PREF = "hw_accounts"
    private const val KEY = "list"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<HwAccount> {
        val raw = prefs(ctx).getString(KEY, "") ?: return mutableListOf()
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<HwAccount>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(HwAccount.fromJson(it)) }
            out
        } catch (e: Exception) {
            Log.e("HwAccountStore", "账号列表解析失败，原文保留在 prefs[$PREF/$KEY]", e)
            prefs(ctx).edit().putString(KEY + "_broken", raw).apply()
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<HwAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).commit()
    }

    /**
     * 新增或更新一个账号。
     *
     * 判重是**三级**（BUGS-v2.46 P1），按可靠度从高到低：
     *   1. 同 `agencyID` —— 同一账号重登（`SESSION_ID` 换了）也能认出来；
     *   2. 同整条 Cookie —— `agencyID` 拿不到时的兜底（与旧行为一致）；
     *   3. 同 id —— 上面两级都没命中但 id 撞了（极少见，防御性）。
     *
     * 命中任何一级都**保留旧 id**：`HistoryStore` 的 `account_key` 由 id 派生，
     * 换 id 等于把这个人今天的历史记录切成两半（`allOkOf` 会因新键当天没记录而判「没签完」）。
     */
    fun upsert(ctx: Context, acc: HwAccount): HwAccount {
        val list = all(ctx)
        val aAgency = HwAccount.agencyOf(acc.cookie)
        val idx = list.indexOfFirst { old ->
            (aAgency.isNotBlank() && HwAccount.agencyOf(old.cookie) == aAgency) ||
                (acc.cookie.isNotBlank() && old.cookie == acc.cookie) ||
                old.id == acc.id
        }
        val saved = if (idx >= 0) {
            val old = list[idx]
            acc.copy(
                id = old.id,
                cookie = acc.cookie.ifBlank { old.cookie },
                authCookie = acc.authCookie.ifBlank { old.authCookie },
                // 同上（P2-1）：整条 Cookie 与 auth 域 Cookie 都靠 Raw 兜底，
                // 少任一条，Keystore 短暂不可用时就会把静默重登能力写没。
                cookieRaw = if (acc.cookie.isBlank()) old.cookieRaw else acc.cookieRaw,
                authCookieRaw = if (acc.authCookie.isBlank()) old.authCookieRaw else acc.authCookieRaw,
                lastStatus = old.lastStatus, lastMessage = old.lastMessage,
                lastCheckAt = old.lastCheckAt, totalPoints = old.totalPoints,
                streakDays = old.streakDays, usedPoints = old.usedPoints,
                nickname = acc.nickname.ifBlank { old.nickname },
                alias = acc.alias.ifBlank { old.alias },
            ).also { list[idx] = it }
        } else {
            list.add(acc); acc
        }
        save(ctx, list)
        return saved
    }

    fun update(ctx: Context, acc: HwAccount) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == acc.id }
        if (idx >= 0) { list[idx] = acc; save(ctx, list) }
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }
}
