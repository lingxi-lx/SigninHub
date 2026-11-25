package com.signhub.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 本地签到记录（SQLite）。
 *
 * 唯一键是 provider + day + account_key：同一家服务同一天可以有多个账号各一条记录。
 * 早先只按 provider + day 建唯一键，多账号签到会互相覆盖（只剩最后一个账号的结果），
 * 其它账号的记录直接丢失 —— 那是数据丢失，不是展示问题。
 */
class HistoryStore(ctx: Context) {

    data class Rec(
        val id: Long,
        val provider: String,
        val day: String,
        val status: String,
        val message: String,
        val points: String,
        val time: String,
        val totalPoints: Int = -1,   // 签到时读到的账号持有积分（-1 = 未知）
        val streakDays: Int = -1,    // 上游返回的连续签到天数（-1 = 未知）
        val accountKey: String = DEFAULT_ACCOUNT,
        val usedPoints: Int = -1,    // 账号累计已用（-1 = 未知），资产卡翻面用
    )

    private val helper = object : SQLiteOpenHelper(ctx, "signhub_history.db", null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_SQL)
        }

        /**
         * 迁移。
         *
         * 以前每一段都是 `try { ... } catch (_: Exception) {}`：任何一步失败都被吞掉，
         * 数据库停在「改了一半」的状态 —— 表已经 RENAME 成 records_v4、新表没建起来，
         * 或者新表建了但数据没搬过去。之后所有读写都落在一个空表上，
         * 用户看到的是「历史记录全没了」，而且没有任何日志能查。
         *
         * 现在：失败一律 Log.e + 抛出去，让 SQLiteOpenHelper 的升级事务整体回滚
         * （下次启动会重新尝试），至少不会留下半成品。ADD COLUMN 先查 PRAGMA，
         * 已经存在就跳过 —— 重复执行安全。
         */
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            Log.i(TAG, "升级数据库 v$old -> v$new")
            if (old < 2 && !hasColumn(db, "records", "total_points")) {
                exec(db, "ALTER TABLE records ADD COLUMN total_points INTEGER NOT NULL DEFAULT -1")
            }
            if (old < 3 && !hasColumn(db, "records", "streak_days")) {
                exec(db, "ALTER TABLE records ADD COLUMN streak_days INTEGER NOT NULL DEFAULT -1")
            }
            if (old < 4) {
                // v1.17 及以前把「活动累计发放 total_credits」当成了持有积分（2200 这类虚高值），
                // 口径已在 v1.18 改为余额接口求和。旧记录里的数全不可信，一律清成未知，
                // 让首页回落到「缓存/后台补数」，不展示假数字。
                exec(db, "UPDATE records SET total_points = -1")
            }
            if (old < 5) {
                // SQLite 改不了 UNIQUE 约束，只能重建表。老记录都归到 DEFAULT_ACCOUNT
                // （v5 之前本来就只能存一个账号，没有账号维度可区分）。
                if (!hasTable(db, "records_v4")) {
                    exec(db, "ALTER TABLE records RENAME TO records_v4")
                }
                exec(db, CREATE_SQL)
                exec(db,
                    """INSERT OR REPLACE INTO records
                       (id,provider,day,status,message,points,created_at,total_points,streak_days,account_key)
                       SELECT id,provider,day,status,message,points,created_at,total_points,streak_days,
                              '$DEFAULT_ACCOUNT'
                       FROM records_v4"""
                )
                exec(db, "DROP TABLE records_v4")
            }
            if (old < 6 && !hasColumn(db, "records", "used_points")) {
                // v6：资产卡要翻面显示「累计已用」，加一列；老记录一律 -1（未知）
                exec(db, "ALTER TABLE records ADD COLUMN used_points INTEGER NOT NULL DEFAULT -1")
            }
            Log.i(TAG, "数据库升级完成")
        }

        private fun exec(db: SQLiteDatabase, sql: String) {
            try {
                db.execSQL(sql)
            } catch (e: Exception) {
                Log.e(TAG, "迁移失败，SQL=${sql.take(120)}", e)
                throw e   // 交给 SQLiteOpenHelper 回滚整个升级事务，别留下半迁移的库
            }
        }

        private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(name)
            ).use { it.moveToFirst() }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
            try {
                db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    var found = false
                    while (c.moveToNext()) {
                        if (c.getString(c.getColumnIndexOrThrow("name")) == column) {
                            found = true
                            break
                        }
                    }
                    found
                }
            } catch (_: Exception) {
                false
            }
    }

    private fun now(): String =
        LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /**
     * 写入/覆盖当天某个账号的记录。accountKey 相同才覆盖，不同账号各留一条。
     *
     * ★ 不允许「失败覆盖成功」（用户 m24705）：
     *   以前是裸 `insertWithOnConflict(..., CONFLICT_REPLACE)` —— 谁最后写谁赢。
     *   实机事故：Trae / MiniMax 在前台已经签成功（绿勾），随后用户点了
     *   「立即测试后台签到」，后台 Worker 因为 CheckinGate 锁泄漏报
     *   `IllegalMonitorStateException`，那条 **fail** 就把当天的 success 覆盖掉了。
     *   于是首页从「已签到」变回「未签到」，进度环从 60% 掉回 40%，
     *   而用户当天其实是签过的 —— 数据库里的事实被一次「测试」抹掉。
     *
     *   现在的规则：同 (provider, day, account_key) 已有 success/already 时，
     *   后续的 fail **只合并积分数字，不改 status/message**。
     *   反向（fail → success/already）照常覆盖，因为那才是真实进展。
     *   用户若真想清掉，可以「重置凭据」或换一天。
     */
    fun upsert(day: String, r: CheckinResult, accountKey: String = DEFAULT_ACCOUNT) {
        val db = helper.writableDatabase
        val prev = existingStatus(db, day, r.provider, accountKey)
        val keepPrev = RecordMerge.keepPrev(prev, r.status)
        val cv = ContentValues().apply {
            put("provider", r.provider)
            put("day", day)
            if (keepPrev) {
                // 保留原状态与原文案，只把这次读到的余额补进去（可能比上次新）
                put("status", prev)
                put("message", existingMessage(db, day, r.provider, accountKey) ?: "")
                put("points", existingPoints(db, day, r.provider, accountKey) ?: "")
            } else {
                put("status", r.status)
                put("message", r.message)
                put("points", r.points)
            }
            put("created_at", now())
            // BUGS-v2.63-290 P2-1：失败分支只带 status/message，三个数字字段是默认的 -1。
            // 以前无条件写，于是「签到成功读到 773 → 再失败一次 → 首页变 —」。
            // -1 是「这次没读到」，必须保留库里已有的值（与后端 db.add_sign_log 同口径）。
            put("total_points", totalOf(db, "total_points", day, r.provider, accountKey, r.totalPoints))
            put("streak_days", totalOf(db, "streak_days", day, r.provider, accountKey, r.streakDays))
            put("used_points", totalOf(db, "used_points", day, r.provider, accountKey, r.usedPoints))
            put("account_key", accountKey)
        }
        db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun isOk(status: String?): Boolean = RecordMerge.isOk(status)

    /** 这次读到的数字（next）≥ 0 就用它，否则保留库里旧值。 */
    private fun totalOf(
        db: SQLiteDatabase, col: String, day: String, provider: String, accountKey: String, next: Int
    ): Int = RecordMerge.total(oneColumn(db, col, day, provider, accountKey)?.toIntOrNull() ?: -1, next)

    private fun existingStatus(db: SQLiteDatabase, day: String, provider: String, accountKey: String): String? =
        oneColumn(db, "status", day, provider, accountKey)

    private fun existingMessage(db: SQLiteDatabase, day: String, provider: String, accountKey: String): String? =
        oneColumn(db, "message", day, provider, accountKey)

    private fun existingPoints(db: SQLiteDatabase, day: String, provider: String, accountKey: String): String? =
        oneColumn(db, "points", day, provider, accountKey)

    private fun oneColumn(db: SQLiteDatabase, col: String, day: String, provider: String, accountKey: String): String? =
        try {
            db.rawQuery(
                "SELECT $col FROM records WHERE day=? AND provider=? AND account_key=? LIMIT 1",
                arrayOf(day, provider, accountKey)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }

    /**
     * 只更新某天某 provider 的持有积分 / 连登天数，不动 status / message / points。
     *
     * 为什么需要它：首页卡片与 Token 健康的 `total_points` 读的是**当天签到记录**
     * （见 LocalApi.providerRow 的优先级），而「刷新余额」查到的新读数原先只写进
     * SharedPreferences 缓存 —— 于是资产卡变了、首页与 Token 健康却纹丝不动。
     *
     * 当天还没有任何记录时**补一条 idle 占位**（用户 m14001）：以前是纯 UPDATE，
     * 查不到记录就什么都不做 —— 于是「刚填完签到配置、今天还没签」这个最常见的场景下，
     * 余额刷新拿到了数字却无处可写，卡片永远显示「—」。
     * 补出来的行 status='idle'，`recordsOf()` 的 rank 里 idle 排最低（0），
     * 不会盖掉真实的 success/already/fail；统计与记录页也都把 idle 排除在外。
     *
     * accountKey 为 null 时更新该 provider 当天所有账号（多账号合计口径）。
     */
    fun updateTotals(
        day: String,
        provider: String,
        total: Int = -1,
        streak: Int = -1,
        accountKey: String? = null,
        used: Int = -1,
    ): Boolean {
        if (total < 0 && streak < 0 && used < 0) return false
        val cv = ContentValues().apply {
            if (total >= 0) put("total_points", total)
            if (streak >= 0) put("streak_days", streak)
            if (used >= 0) put("used_points", used)
        }
        val where = if (accountKey == null) "day=? AND provider=?" else "day=? AND provider=? AND account_key=?"
        val args = if (accountKey == null) arrayOf(day, provider) else arrayOf(day, provider, accountKey)
        val db = helper.writableDatabase
        val n = db.update("records", cv, where, args)
        if (n > 0) return true
        // 没有当天记录 → 插一条 idle 占位，把这次读到的数字存下来（只有余额、没有签到动作）
        val ins = ContentValues().apply {
            put("provider", provider)
            put("day", day)
            put("status", "idle")
            put("message", "今日尚未执行")
            put("points", "")
            put("created_at", now())
            put("total_points", if (total >= 0) total else -1)
            put("streak_days", if (streak >= 0) streak else -1)
            put("used_points", if (used >= 0) used else -1)
            put("account_key", accountKey ?: DEFAULT_ACCOUNT)
        }
        return db.insertWithOnConflict("records", null, ins, SQLiteDatabase.CONFLICT_REPLACE) > 0
    }

    /**
     * 某天各服务的最新记录，按 provider 聚合。
     *
     * 多账号时同一 provider 有多条：取「最差」的那条代表整体（fail 优先），
     * 否则一个账号失败会被另一个账号的成功掩盖，首页显示成已签到。
     */
    fun recordsOf(day: String): Map<String, Rec> {
        val out = mutableMapOf<String, Rec>()
        helper.readableDatabase.rawQuery(
            "SELECT id,provider,day,status,message,points,created_at,total_points,streak_days,account_key," +
                "used_points FROM records WHERE day=? ORDER BY id ASC",
            arrayOf(day)
        ).use { c ->
            while (c.moveToNext()) {
                val r = readRec(c)
                val prev = out[r.provider]
                out[r.provider] = if (prev == null || rank(r.status) > rank(prev.status)) r else prev
            }
        }
        return out
    }

    /**
     * 一次查出多天各服务的最新记录（day -> provider -> Rec）。
     *
     * 首页的「近 7 天」以前是循环 7 次调 recordsOf()，也就是 7 条 SQL；
     * 加上 /api/status 每 25 秒跑一次，等于每秒都在扫库。这里用一条
     * `WHERE day IN (?,?,...)` 拿完，聚合逻辑与 recordsOf() 完全一致（取最差）。
     */
    fun recordsOfDays(days: List<String>): Map<String, Map<String, Rec>> {
        val out = mutableMapOf<String, MutableMap<String, Rec>>()
        if (days.isEmpty()) return out
        val marks = days.joinToString(",") { "?" }
        helper.readableDatabase.rawQuery(
            "SELECT id,provider,day,status,message,points,created_at,total_points,streak_days,account_key," +
                "used_points FROM records WHERE day IN ($marks) ORDER BY id ASC",
            days.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val r = readRec(c)
                val perDay = out.getOrPut(r.day) { mutableMapOf() }
                val prev = perDay[r.provider]
                perDay[r.provider] = if (prev == null || rank(r.status) > rank(prev.status)) r else prev
            }
        }
        return out
    }

    /** 某天某 provider 的全部账号记录（多账号明细）。 */
    fun recordsOfProvider(day: String, provider: String): List<Rec> {
        val out = mutableListOf<Rec>()
        helper.readableDatabase.rawQuery(
            "SELECT id,provider,day,status,message,points,created_at,total_points,streak_days,account_key," +
                "used_points FROM records WHERE day=? AND provider=? ORDER BY id ASC",
            arrayOf(day, provider)
        ).use { c ->
            while (c.moveToNext()) out.add(readRec(c))
        }
        return out
    }

    /**
     * 当天该账号是否已经签过 —— 签过就把那条记录原样包成 [CheckinResult] 返回，否则 null。
     *
     * 为什么要这个（用户 m06895）：6 点手动签过、8 点定时任务又跑一遍，会把所有账号
     * 再打一次上游。本地原本只有两处「跳过」机制，都盖不住这个场景：
     *   - `CheckinGate` 的复用表只活 8 秒（`RECENT_TTL_MS`），跨 2 小时早过期；
     *   - `HwTodayMark` 只覆盖华为一家。
     * 于是 WorkBuddy / Trae / Qoder / MiniMax / 智谱 / 千帆 每家都真发一次请求。
     *
     * **返回的是原记录本身**（status/message/points/total/streak/used 全部照抄），
     * 不是伪造一条「已签到」—— 调用方拿到后照常 `upsert`，写回去的值与库里一致，
     * 不会把已有积分/文案冲掉，也不会让首页从「已签到 773」跳成「已签到」。
     *
     * 只认 success / already 两种状态；fail 不算签过（用户点重试必须能重打）。
     */
    fun doneResult(
        day: String,
        provider: String,
        accountKey: String = DEFAULT_ACCOUNT,
    ): CheckinResult? {
        val rec = recordsOfProvider(day, provider).firstOrNull { it.accountKey == accountKey }
            ?: return null
        if (rec.status != "success" && rec.status != "already") return null
        return CheckinResult(
            provider = rec.provider,
            status = rec.status,
            message = rec.message,
            points = rec.points,
            totalPoints = rec.totalPoints,
            streakDays = rec.streakDays,
            usedPoints = rec.usedPoints,
        )
    }

    /**
     * 当天**已签完**的账号，一次查完，键是 `provider + "|" + accountKey`。
     *
     * 给 `LocalApi.checkinAll()` 用：那边的每账号网络请求都在线程池里跑，
     * 而本项目硬规则是「线程池里绝不碰 SQLite」，所以必须**在派 job 之前**
     * 在调用线程上把这张表查好，job 里只做 `map[key]` 查表。
     *
     * 只收 success / already —— fail 不算签过，用户点重试必须能重打。
     */
    fun doneMap(day: String): Map<String, CheckinResult> {
        val out = mutableMapOf<String, CheckinResult>()
        helper.readableDatabase.rawQuery(
            "SELECT id,provider,day,status,message,points,created_at,total_points,streak_days,account_key," +
                "used_points FROM records WHERE day=? AND (status='success' OR status='already')",
            arrayOf(day)
        ).use { c ->
            while (c.moveToNext()) {
                val r = readRec(c)
                out[r.provider + "|" + r.accountKey] = CheckinResult(
                    provider = r.provider,
                    status = r.status,
                    message = r.message,
                    points = r.points,
                    totalPoints = r.totalPoints,
                    streakDays = r.streakDays,
                    usedPoints = r.usedPoints,
                )
            }
        }
        return out
    }

    /**
     * 删掉某天某 provider 某一个账号的记录（用户 m06802）。
     *
     * 为什么必须有这个：`LocalApi.providerRow` 在没有账号列表时会**拿当天历史记录反推卡片**，
     * 于是「重置凭据」清掉了主账号凭据，那张卡却仍然画在首页 —— 状态还是重置前的
     * 「已签到 773」。用户点完重置看到卡片没消失，只能以为没生效。
     *
     * 只删指定的 account_key，不动同 provider 的其他账号（多账号不受重置影响）。
     */
    fun deleteAccount(day: String, provider: String, accountKey: String = DEFAULT_ACCOUNT): Int =
        try {
            helper.writableDatabase.delete(
                "records", "day=? AND provider=? AND account_key=?",
                arrayOf(day, provider, accountKey)
            )
        } catch (e: Exception) {
            Log.e(TAG, "删除记录失败 provider=$provider day=$day key=$accountKey", e)
            0
        }

    private fun readRec(c: android.database.Cursor): Rec = Rec(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
        c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getInt(7), c.getInt(8),
        c.getString(9) ?: DEFAULT_ACCOUNT, c.getInt(10),
    )

    private fun rank(status: String): Int = when (status) {
        "fail" -> 3
        "success" -> 2
        "already" -> 1
        else -> 0
    }

    fun recentLogs(limit: Int): List<Rec> {
        // status='idle' 是 updateTotals() 补出来的「只有余额、没有签到动作」的占位行，
        // 不属于签到历史 —— 记录页不该出现它们。
        val out = mutableListOf<Rec>()
        helper.readableDatabase.rawQuery(
            "SELECT id,provider,day,status,message,points,created_at,total_points,streak_days,account_key," +
                "used_points FROM records WHERE status<>'idle' ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readRec(c))
        }
        return out
    }

    /**
     * 全表统计。
     *
     * 以前是 `SELECT status FROM records` 然后把每一行拉进 Kotlin 再累加 ——
     * 记录累积到几千条时，每次 /api/status（25 秒一次）都要遍历一遍全表，
     * 而这里需要的只是三个计数。改成一条聚合 SQL，交给 SQLite 数。
     */
    fun statsAll(): org.json.JSONObject {
        var total = 0; var success = 0; var fail = 0
        // 排除 idle 占位行（updateTotals 补出来的、没有签到动作的记录），
        // 否则「总记录数」会把每次余额刷新都算进去。
        helper.readableDatabase.rawQuery(
            """SELECT COUNT(*),
                      SUM(CASE WHEN status IN ('success','already') THEN 1 ELSE 0 END),
                      SUM(CASE WHEN status = 'fail' THEN 1 ELSE 0 END)
               FROM records WHERE status<>'idle'""", null
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                success = if (c.isNull(1)) 0 else c.getInt(1)
                fail = if (c.isNull(2)) 0 else c.getInt(2)
            }
        }
        return org.json.JSONObject().apply {
            put("total", total); put("success", success); put("fail", fail)
        }
    }

    /**
     * 从今天往前数连续「已签到」的天数。
     *
     * 只统计真正参与签到的服务的记录：`idle`（当天只刷了余额、没签到）与空记录
     * 不算漏签，否则没签过的那家永远是 idle，streak 会恒为 0。
     *
     * 判据必须是「这一天的每一条记录都成功」，而不是「每个 provider 的最差状态」：
     * recordsOf() 为了首页展示会把同一 provider 的多账号折叠成最差那条，
     * 拿它算 streak 时，多账号用户只要有 1 个账号失败（或记录里混进一条 fail），
     * 该 provider 当天就永远算「没签完」，连登天数恒为 0。
     * 这里直接扫当天全部行，逐行判断。
     */
    fun streak(): Int {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        var n = 0
        for (i in 0..59) {
            val d = today.minusDays(i.toLong()).format(DateTimeFormatter.ISO_DATE)
            val (has, allOk) = dayDone(d)
            if (!has) {
                if (i == 0) continue  // 今天还没签，从昨天开始算
                else break
            }
            if (allOk) n++
            else {
                if (i == 0) continue
                else break
            }
        }
        return n
    }

    /**
     * 某天是否「全部签完」。返回 (是否有非 idle 记录, 是否每条都 success/already)。
     *
     * 一次 SQL 搞定：不经过 recordsOf 的「按 provider 取最差」折叠，
     * 所以多账号里每个账号都参与判定。
     */
    fun dayDone(day: String): Pair<Boolean, Boolean> {
        var has = false
        var allOk = true
        helper.readableDatabase.rawQuery(
            "SELECT status FROM records WHERE day=? AND status<>'idle'", arrayOf(day)
        ).use { c ->
            while (c.moveToNext()) {
                has = true
                val s = c.getString(0)
                if (s != "success" && s != "already") {
                    allOk = false
                    break
                }
            }
        }
        return Pair(has, has && allOk)
    }

    companion object {
        private const val TAG = "HistoryStore"

        /** v5 之前的记录没有账号维度，统一归到这个键。 */
        const val DEFAULT_ACCOUNT = "default"

        /** 建表语句：onCreate 与 v5 迁移共用，避免两处 schema 漂移。 */
        private const val CREATE_SQL = """CREATE TABLE records(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    provider TEXT NOT NULL,
                    day TEXT NOT NULL,
                    status TEXT NOT NULL,
                    message TEXT,
                    points TEXT,
                    created_at TEXT NOT NULL,
                    total_points INTEGER NOT NULL DEFAULT -1,
                    streak_days INTEGER NOT NULL DEFAULT -1,
                    used_points INTEGER NOT NULL DEFAULT -1,
                    account_key TEXT NOT NULL DEFAULT 'default',
                    UNIQUE(provider, day, account_key) ON CONFLICT REPLACE
                )"""
    }
}

/**
 * 记录合并规则（BUGS-v2.63-290 P2-1）。
 *
 * 抽成纯函数是为了能在 JVM 单测里锁住语义（见 docs/TEST-COVERAGE-PLAN.html 的 L1-1）：
 * 这段判定原先直接写在 upsert 里，只有「状态 / 文案 / 增量」被保护，
 * 「总积分」这一路从来没有被断言过，于是漏了整整一版。
 */
internal object RecordMerge {
    fun isOk(s: String?): Boolean = s == "success" || s == "already"

    /** 已有 success/already、这次不是 → 保留旧状态与旧文案 */
    fun keepPrev(prev: String?, next: String?): Boolean = isOk(prev) && !isOk(next)

    /**
     * total_points / streak_days / used_points 的合并。
     *
     * -1 的语义是「这次没读到」（读数读不到一律返 -1，绝不返 0），
     * 此时必须保留旧值，否则首页「当前可用」会从 773 变成「—」。
     * 注意 -2 是**后端**的「该服务没有积分概念」哨兵，安卓侧不用，别顺手搬过来。
     */
    fun total(prev: Int, next: Int): Int = if (next >= 0) next else prev
}
