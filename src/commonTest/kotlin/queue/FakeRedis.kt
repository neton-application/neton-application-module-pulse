package queue

import kotlin.time.Duration
import neton.redis.RedisClient
import neton.redis.RedisPipeline

/**
 * In-memory RedisClient for queue tests. Only the list and counter operations PulseQueue uses are
 * real; everything else throws, so a future change that starts depending on some other command
 * fails loudly here rather than silently doing nothing.
 */
class FakeRedis : RedisClient {
    val lists = LinkedHashMap<String, MutableList<String>>()
    val values = LinkedHashMap<String, String>()

    private fun list(key: String) = lists.getOrPut(key) { mutableListOf() }

    override suspend fun rpush(key: String, value: Any) { list(key).add(value.toString()) }
    override suspend fun lpush(key: String, value: Any) { list(key).add(0, value.toString()) }
    override suspend fun lpop(key: String): String? = list(key).removeFirstOrNull()
    override suspend fun lrange(key: String, start: Int, end: Int): List<String> {
        val l = list(key)
        if (l.isEmpty()) return emptyList()
        val from = start.coerceIn(0, l.size)
        val to = (if (end < 0) l.size + end else end).coerceIn(-1, l.size - 1)
        return if (to < from) emptyList() else l.subList(from, to + 1).toList()
    }

    override suspend fun incr(key: String): Long {
        val next = (values[key]?.toLongOrNull() ?: 0L) + 1
        values[key] = next.toString()
        return next
    }

    override suspend fun getValue(key: String): String? = values[key]

    // ---- not used by PulseQueue
    private fun unsupported(op: String): Nothing = throw UnsupportedOperationException("FakeRedis: $op")
    override suspend fun close() {}
    override suspend fun set(key: String, value: Any, ttl: Duration?) { values[key] = value.toString() }
    override suspend fun getBytes(key: String): ByteArray? = unsupported("getBytes")
    override suspend fun setBytes(key: String, value: ByteArray, ttl: Duration?) = unsupported("setBytes")
    override suspend fun delete(key: String) { values.remove(key); lists.remove(key) }
    override suspend fun exists(key: String): Boolean = values.containsKey(key) || lists.containsKey(key)
    override suspend fun expire(key: String, ttl: Duration) = unsupported("expire")
    override suspend fun decr(key: String): Long = unsupported("decr")
    override suspend fun hset(key: String, field: String, value: Any) = unsupported("hset")
    override suspend fun hget(key: String, field: String): String? = unsupported("hget")
    override suspend fun hgetAll(key: String): Map<String, String> = unsupported("hgetAll")
    override suspend fun sadd(key: String, value: Any) = unsupported("sadd")
    override suspend fun smembers(key: String): Set<String> = unsupported("smembers")
    override suspend fun pipeline(block: RedisPipeline.() -> Unit) = unsupported("pipeline")
    override suspend fun collectScanKeys(matchPattern: String, allowKeysFallback: Boolean, block: suspend (String) -> Unit) =
        unsupported("collectScanKeys")
    override suspend fun info(section: String?): String? = unsupported("info")
    override suspend fun dbSize(): Long = unsupported("dbSize")
    override suspend fun setIfAbsent(key: String, value: String, pxMillis: Long): Boolean = unsupported("setIfAbsent")
    override suspend fun scriptLoad(script: String): String = unsupported("scriptLoad")
    override suspend fun evalToLong(script: String, keys: List<String>, args: List<String>): Long? = unsupported("evalToLong")
    override suspend fun evalshaToLong(sha: String, keys: List<String>, args: List<String>): Long? = unsupported("evalshaToLong")
}
