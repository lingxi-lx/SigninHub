package com.signhub.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据字段的本地加密（Android Keystore + AES/GCM）。
 *
 * 为什么需要：Settings.kt 里的 wbToken / traeToken / traeSession / qoderToken /
 * qoderRefresh / mmToken / notify.secret 全部是「直接能拿去登录」的长期凭据，
 * 以前以明文写进 DataStore（一个普通 XML 文件）。虽然 allowBackup 已经关了、
 * dataExtractionRules 也排除了备份，但 root 设备、adb backup 的旧版本、
 * 以及任何拿到 /data/data 读权限的进程都能直接读走。
 *
 * 设计取舍：
 *   - 用 Android Keystore 里的 AES 密钥，密钥本身不出 TEE/StrongBox，读不到明文密钥；
 *   - 密文格式带前缀 `enc:v1:`，解密时看到没有前缀就原样返回 ——
 *     这样老版本留下的明文设置能无缝读出来，下次保存时自动变成密文，不需要迁移代码；
 *   - **解密失败返回 [DECRYPT_FAILED] 哨兵，而不是空串**。空串会被上层当成
 *     「用户清空了凭据」，下一次任意一次设置保存就把完好的密文覆盖成 ""，
 *     从此再也解不回来（BUGS-v1.63 P1-1）。哨兵让上层能区分「真的空」与「解不开」。
 *   - **加密失败不再退回明文（M3，BUGS-v2.61）**。原实现 `catch { return plain }` 会在
 *     Keystore 异常时把 accessToken / refreshToken / cookie **明文写进
 *     SharedPreferences / DataStore** —— 那正是本文件存在的理由被绕过，而且用户
 *     完全无感（只有一个黄条）。现在抛 [CryptoUnavailable]，调用方据此**放弃写盘**
 *     （保留上一次的密文），前端红条提示「本机凭据未加密，请重启 App」。
 */
internal object SecretBox {
    private const val TAG = "SecretBox"
    private const val KEY_ALIAS = "signhub_secrets_v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * 本机密钥不可用（Keystore 初始化失败 / 密钥被系统清掉 / 硬件不支持）。
     *
     * 抛这个而不是退回明文：明文落盘比「这次存不进去」严重得多 —— 存不进去
     * 用户重试一次就好，明文落盘则是永久泄露。
     */
    class CryptoUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 解密失败的哨兵值。
     *
     * 用 `\u0000` 开头保证它不可能与任何真实 token 撞上（凭据都是可见 ASCII）。
     * 上层必须把它映射成空串**并且**置「凭据需要重填」标志，绝不能把它当凭据发出去。
     */
    const val DECRYPT_FAILED = "\u0000DECRYPT_FAILED"

    /** 进程内密钥缓存：Keystore 是 binder 调用，比内存读慢两个数量级（P2-2）。 */
    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * 解密结果缓存（密文 -> 明文）。
     *
     * 每次 /api/status 都要解 8 个字段，而 /api/status 每 25 秒跑一次；
     * 密文在进程内是稳定不变的，缓存掉可以省掉 8 次 AES-GCM 运算。
     * 上限 32 条（凭据字段总共不到 20 个），accessOrder=true 做 LRU。
     *
     * L2（BUGS-v2.61）：加了 5 分钟 TTL。原来只有 LRU 上限、没有过期 ——
     * 明文 token 会一直驻留在进程堆里（内存 dump / 崩溃日志 / 同进程其它
     * 组件的反射都能捞到），而凭据本身的有效期以天计，等于明文在内存里
     * 活得和进程一样久。过期后下次访问重新解一次，代价可以忽略。
     */
    private val plainCache = object : LinkedHashMap<String, Pair<String, Long>>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?
        ): Boolean = size > 32
    }

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private fun cacheGet(key: String): String? {
        val hit = synchronized(plainCache) { plainCache[key] } ?: return null
        if (System.currentTimeMillis() - hit.second > CACHE_TTL_MS) {
            synchronized(plainCache) { plainCache.remove(key) }
            return null
        }
        return hit.first
    }

    private fun cachePut(key: String, plain: String) {
        synchronized(plainCache) { plainCache[key] = plain to System.currentTimeMillis() }
    }

    /** 加密失败次数：>0 说明 Keystore 不可用，凭据可能以明文落盘（P2-1）。 */
    @Volatile
    private var encFailures = 0

    fun failures(): Int = encFailures

    /** 本机加密是否健康；false 时设置页应提示「本机凭据未加密」。 */
    fun healthy(): Boolean = encFailures == 0

    /**
     * 手动复位失败计数。
     *
     * P2R2（BUGS-v2.2）：[encFailures] 原本只增不减，Keystore 偶发一次失败
     * （首次建 key 的 KeyStoreException、并发初始化竞争）之后 [healthy] 永远 false，
     * 前端那条「本机凭据未加密」黄条就再也消不掉 —— 用户无法判断后续是否已恢复。
     * 现在 encrypt() 成功路径会自动清零，另外留这个入口给「知道了，重试」按钮。
     */
    fun reset() { encFailures = 0 }

    /** 密文前缀；用于判断一个值是否已经加密过。 */
    fun isEncrypted(v: String): Boolean = v.startsWith(PREFIX)

    /**
     * 加密。
     *
     * M3（BUGS-v2.61）：失败时**抛 [CryptoUnavailable]，绝不返回明文**。
     * 原来 catch 里 `return plain` 等于「加密挂了就把长期凭据明文写进
     * SharedPreferences / DataStore」，而这一切只留一个 Log.e + 一个黄条 ——
     * 用户根本不知道自己的 accessToken 已经裸奔。
     * 调用方（[encryptKeep] / [SettingsStore.save] / [Notify]）都按「存不进去」
     * 处理：跳过这次写盘，保留上一次的密文，由 UI 提示重启 App。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        // 已经是密文就别重复加密（重复套娃会导致解密一次仍是密文）
        if (isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
            // N7（BUGS-v2.1）：encrypt 之后要把结果塞进解密缓存。
            // 否则 save() 完立刻 flow() 读回来时缓存未命中，白跑一次 AES-GCM + Keystore。
            cachePut(out, plain)
            // P2R2（BUGS-v2.2）：成功一次就说明 Keystore 已恢复，把历史失败计数清掉。
            // 否则偶发一次失败会让 healthy() 永远返回 false，前端黄条永不消失。
            if (encFailures != 0) encFailures = 0
            out
        } catch (e: Exception) {
            encFailures++
            Log.e(TAG, "加密失败，本次不落盘（第 $encFailures 次）", e)
            throw CryptoUnavailable("本机密钥不可用，请重启 App 后重试", e)
        }
    }

    /** [encrypt] 的安全版：失败返回 null，让调用方跳过写盘而不是写明文。 */
    fun encryptOrNull(plain: String): String? = try {
        encrypt(plain)
    } catch (e: CryptoUnavailable) {
        null
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        // 老数据是明文：原样返回，等下次 save 时再加密
        if (!isEncrypted(stored)) return stored
        cacheGet(stored)?.let { return it }
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return DECRYPT_FAILED
            val iv = raw.copyOfRange(0, IV_BYTES)
            val ct = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val out = String(cipher.doFinal(ct), Charsets.UTF_8)
            cachePut(stored, out)
            out
        } catch (e: Exception) {
            // 密钥被清（换机 / 清数据 / 用户删了锁屏）或密文损坏。
            //
            // 关键：**不能返回空串**。空串会被 SettingsStore.save() 当成「用户清空了」
            // 而覆盖掉仍然完好的密文，从此永久丢失（P1-1）。返回哨兵，让上层决定
            // 「提示重填」而不是「静默抹掉」。
            Log.e(TAG, "解密失败，返回哨兵值（保留密文，等用户重填）", e)
            cachedKey = null        // 密钥可能已失效，下次强制重建
            synchronized(plainCache) { plainCache.clear() }
            DECRYPT_FAILED        }
    }

    /**
     * 解密并**保证不把哨兵当成凭据**。
     *
     * N1（BUGS-v2.1）：[DECRYPT_FAILED] 是一个非空字符串，所以
     * `MultiAccounts` / `WbMulti` / `Notify` 那 5 处 `decrypt()` 调用点拿到的
     * 哨兵能顺利通过 `isNotBlank()` 判断，随后：
     *   1. 被当成真 token 塞进 `Authorization: Bearer` 发给上游；
     *   2. 更糟的是 `upsert()` 会把哨兵 `encrypt()` 后写回磁盘，
     *      覆盖掉仍然完好的密文 —— 正是 P1-1 想防的事，只是换了个入口。
     *
     * 所有「取凭据去用」的地方都应该走这个函数；`decrypt()` 只留给
     * 「需要知道到底解没解开」的场合（如 Settings.flow 要置 credsLost）。
     */
    fun decryptOrEmpty(stored: String): String {
        val v = decrypt(stored)
        return if (v == DECRYPT_FAILED) "" else v
    }

    /** 解密失败哨兵的判别（供上层在拿到 [decrypt] 原始返回值时判断）。 */
    fun isFailed(v: String): Boolean = v == DECRYPT_FAILED

    /**
     * 落盘用：明文为空但**原密文仍在**时，原样写回原密文。
     *
     * N1（BUGS-v2.1）的写入侧：`decryptOrEmpty` 把哨兵映射成 `""` 之后，
     * 只要用户做了任何会触发 `save()` 的动作（改备注、签到回写状态、增删账号），
     * `encrypt("")` 就会返回 `""` 把那条完好的密文覆盖掉 —— 数据还是丢了，
     * 只是从「写哨兵」变成「写空串」，症状一样（凭据永久失效）。
     *
     * 所以对象里除了明文还要保留「读进来时的原始密文」（`*Raw` 字段），
     * 写回时如果明文是空的、而原密文确实是密文，就**沿用原密文**。
     * 用户真的主动清空凭据时 `raw` 也是空，仍然能正常清掉。
     */
    fun encryptKeep(plain: String, rawIfLost: String): String {
        // 最后一道防线：哨兵绝不允许落盘。哪怕上层漏了 decryptOrEmpty，
        // 也不会把 "\u0000DECRYPT_FAILED" 加密后覆盖掉原密文（N1）。
        if (isFailed(plain)) return rawIfLost
        if (plain.isEmpty() && rawIfLost.isNotEmpty() && isEncrypted(rawIfLost)) return rawIfLost
        // M3：加密失败时沿用原密文（可能为空），绝不返回明文。
        return encryptOrNull(plain) ?: rawIfLost
    }

    /**
     * 锁顺序约定（N8，BUGS-v2.1）：
     *
     * 本文件有两把锁 —— `this`（保护 [cachedKey]）与 [plainCache] 对象锁。
     * 当前**唯一的合法顺序**是「先 plainCache、后 this」，且两者不嵌套持有：
     *   · `decrypt()` 先在 :97 拿 plainCache（用完即放），再调 `key()` 拿 this；
     *   · catch 分支同样先释放 this 再拿 plainCache。
     *
     * **禁止在持有 `this` 锁时访问 [plainCache]** —— 那会与 decrypt 形成
     * AB-BA 死锁。后续任何改动都要守住这条。
     */
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        // 不要求解锁：后台 Worker 也要能读凭据签到，否则自动签到在锁屏时必然失败
                        .setUserAuthenticationRequired(false)
                        .build()
                )
                gen.generateKey()
            }
            cachedKey = k
            return k
        }
    }
}
