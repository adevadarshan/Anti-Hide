package com.antihide.v2.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Layer 2: the integrated 4-stage authentication lifecycle.
 *
 * Stage 1 physical opt-in pairing window -> Stage 2 session issuance -> Stage 3 reload bootstrap
 * -> Stage 4 per-command CSRF authorization. Every stage compares raw bytes in constant time and
 * never performs a name-service lookup.
 */

/** Persisted shape of one session slot. Peer bytes are stored Base64URL encoded. */
data class StoredSession(
    val sessionId: String,
    val csrfToken: String,
    val boundPeerIpBase64: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun boundPeerBytes(): ByteArray = Base64.getUrlDecoder().decode(boundPeerIpBase64)

    fun encode(): String = listOf(
        sessionId, csrfToken, boundPeerIpBase64, createdAtEpochMs.toString(), expiresAtEpochMs.toString(),
    ).joinToString("|")

    companion object {
        fun decode(line: String): StoredSession? {
            val parts = line.split('|')
            if (parts.size != 5) return null
            val created = parts[3].toLongOrNull() ?: return null
            val expires = parts[4].toLongOrNull() ?: return null
            return StoredSession(parts[0], parts[1], parts[2], created, expires)
        }
    }
}

/** Storage seam so the Android Keystore-backed store can be swapped for an in-memory test store. */
interface SessionStore {
    fun readAll(): List<StoredSession>

    fun writeAll(sessions: List<StoredSession>)
}

/** HMAC seam. The Android implementation never lets the key bytes leave the Keystore. */
fun interface CsrfKeyProvider {
    fun hmacSha256(input: ByteArray): ByteArray
}

sealed interface PairingWindow {
    /** Nothing open. The only reachable state unless the phone owner physically opened a window. */
    data object Closed : PairingWindow

    data class Open(
        val code: String,
        val openedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val failedAttempts: Int,
    ) : PairingWindow

    /** Five failed attempts consumed the code. A new window must be opened on the phone. */
    data object LockedOut : PairingWindow
}

class AuthorizedSession(
    val sessionId: String,
    val csrfToken: String,
    val boundPeerIp: ByteArray,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun matchesPeer(remoteBytes: ByteArray): Boolean = MessageDigest.isEqual(boundPeerIp, remoteBytes)
}

/** Stage 1 + 2 result. [httpStatus] is the exact code the wire must carry. */
sealed interface PairResult {
    val httpStatus: Int

    data class Success(val session: AuthorizedSession) : PairResult {
        override val httpStatus: Int = 200
    }

    /** Window closed, expired, or never opened. */
    data object WindowClosed : PairResult {
        override val httpStatus: Int = 401
    }

    /** Window open but the submitted code did not match. */
    data object BadCode : PairResult {
        override val httpStatus: Int = 401
    }

    /** Five failed attempts consumed the code. */
    data object RateLimited : PairResult {
        override val httpStatus: Int = 429
    }
}

/** Stage 3 result. */
sealed interface SessionCheck {
    data class Authenticated(val session: AuthorizedSession) : SessionCheck

    /** Missing cookie, unknown session, expired session, or a peer-IP mismatch. */
    data object Unauthorized : SessionCheck
}

/** Stage 4 result. */
sealed interface CommandAuth {
    data class Authorized(val session: AuthorizedSession) : CommandAuth

    /** Session cookie missing, unknown, expired, or bound to a different peer IP. */
    data object Unauthorized : CommandAuth

    /** `X-AntiHide-CSRF` header absent or not matching the session token. */
    data object InvalidCsrf : CommandAuth
}

class SessionAuthManager(
    private val store: SessionStore,
    private val csrfKey: CsrfKeyProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    val maxFailedAttempts: Int = 5,
    val pairingWindowMs: Long = 120_000L,
    val sessionTtlMs: Long = 14L * 24L * 60L * 60L * 1000L,
    val maxConcurrentSessions: Int = 4,
) {
    private val _pairingWindow = MutableStateFlow<PairingWindow>(PairingWindow.Closed)
    val pairingWindow: StateFlow<PairingWindow> = _pairingWindow.asStateFlow()

    private val lock = Any()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    init {
        purgeExpired()
    }

    /** Stage 1: the phone owner physically opens a window and receives a 40-bit CSPRNG code. */
    fun openPairingWindow(): String = synchronized(lock) {
        val code = newCode()
        _pairingWindow.value = PairingWindow.Open(code, clock(), clock() + pairingWindowMs, 0)
        code
    }

    fun activeSessionCount(): Int = synchronized(lock) {
        purgeExpiredLocked()
        store.readAll().size
    }

    fun revokeAllSessions(): Int = synchronized(lock) {
        val n = store.readAll().size
        store.writeAll(emptyList())
        n
    }

    /** Stage 2: exchange a pairing code for an HttpOnly session. */
    fun submitPairingCode(code: String, remoteBytes: ByteArray): PairResult = synchronized(lock) {
        purgeExpiredLocked()
        when (val window = _pairingWindow.value) {
            is PairingWindow.LockedOut -> return@synchronized PairResult.RateLimited
            is PairingWindow.Closed -> return@synchronized PairResult.WindowClosed
            is PairingWindow.Open -> {
                if (clock() > window.expiresAtEpochMs) {
                    _pairingWindow.value = PairingWindow.Closed
                    return@synchronized PairResult.WindowClosed
                }
                if (!constantTimeEquals(normalizeCode(code), normalizeCode(window.code))) {
                    val attempts = window.failedAttempts + 1
                    _pairingWindow.value =
                        if (attempts >= maxFailedAttempts) PairingWindow.LockedOut
                        else window.copy(failedAttempts = attempts)
                    return@synchronized PairResult.BadCode
                }
            }
        }

        // Single use: the code dies the moment it is redeemed.
        _pairingWindow.value = PairingWindow.Closed
        createSessionLocked(remoteBytes)
    }

    /** Stage 3: reload bootstrap via the HttpOnly cookie. */
    fun bootstrapSession(sessionId: String?, remoteBytes: ByteArray): SessionCheck = synchronized(lock) {
        val session = lookupLocked(sessionId) ?: return@synchronized SessionCheck.Unauthorized
        if (!session.matchesPeer(remoteBytes)) return@synchronized SessionCheck.Unauthorized
        SessionCheck.Authenticated(session)
    }

    /** Stage 4: cookie + bound peer IP + per-session HMAC CSRF header. */
    fun authorizeCommand(sessionId: String?, remoteBytes: ByteArray, csrfHeader: String?): CommandAuth =
        synchronized(lock) {
            val session = lookupLocked(sessionId) ?: return@synchronized CommandAuth.Unauthorized
            if (!session.matchesPeer(remoteBytes)) return@synchronized CommandAuth.Unauthorized
            if (csrfHeader.isNullOrEmpty()) return@synchronized CommandAuth.InvalidCsrf
            if (!constantTimeEquals(csrfHeader, session.csrfToken)) return@synchronized CommandAuth.InvalidCsrf
            CommandAuth.Authorized(session)
        }

    private fun lookupLocked(sessionId: String?): AuthorizedSession? {
        if (sessionId.isNullOrEmpty()) return null
        purgeExpiredLocked()
        val stored = store.readAll().firstOrNull { it.sessionId == sessionId } ?: return null
        return AuthorizedSession(
            stored.sessionId,
            stored.csrfToken,
            stored.boundPeerBytes(),
            stored.createdAtEpochMs,
            stored.expiresAtEpochMs,
        )
    }

    private fun createSessionLocked(remoteBytes: ByteArray): PairResult.Success {
        val sessionId = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        val csrfToken = encoder.encodeToString(csrfKey.hmacSha256(sessionId.toByteArray(Charsets.UTF_8)))
        val now = clock()
        val session = AuthorizedSession(sessionId, csrfToken, remoteBytes.copyOf(), now, now + sessionTtlMs)

        val slots = store.readAll().toMutableList()
        slots += StoredSession(
            sessionId = sessionId,
            csrfToken = csrfToken,
            boundPeerIpBase64 = encoder.encodeToString(remoteBytes),
            createdAtEpochMs = now,
            expiresAtEpochMs = now + sessionTtlMs,
        )
        // Oldest-first eviction keeps four live origins (numeric IP + MagicDNS + spares) online.
        while (slots.size > maxConcurrentSessions) slots.remove(slots.minByOrNull { it.createdAtEpochMs }!!)
        store.writeAll(slots)
        return PairResult.Success(session)
    }

    private fun purgeExpired() = synchronized(lock) { purgeExpiredLocked() }

    private fun purgeExpiredLocked() {
        val now = clock()
        val all = store.readAll()
        val live = all.filter { it.expiresAtEpochMs > now }
        if (live.size != all.size) store.writeAll(live)
    }

    /** 8 Crockford Base32 characters = 40 bits of CSPRNG entropy, displayed as XXXX-XXXX. */
    private fun newCode(): String {
        val sb = StringBuilder(8)
        repeat(8) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        val body = sb.toString()
        return body.substring(0, 4) + "-" + body.substring(4)
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Compares in constant time and returns false immediately for a length mismatch. */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        fun normalizeCode(raw: String): String =
            raw.uppercase().filter { it.isLetterOrDigit() }
    }
}

/** Test/dev HMAC provider: one random in-memory key for the process lifetime. */
class InMemoryCsrfKeyProvider : CsrfKeyProvider {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    override fun hmacSha256(input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(input)
}

/** Test/dev store: no persistence, no Android dependency. */
class InMemorySessionStore(initial: List<StoredSession> = emptyList()) : SessionStore {
    private val slots = ArrayList(initial)

    override fun readAll(): List<StoredSession> = synchronized(slots) { ArrayList(slots) }

    override fun writeAll(sessions: List<StoredSession>) {
        synchronized(slots) {
            slots.clear()
            slots.addAll(sessions)
        }
    }
}