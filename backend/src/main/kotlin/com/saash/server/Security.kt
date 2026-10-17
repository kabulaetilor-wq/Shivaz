package com.saash.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HttpError(val code: HttpStatusCode, message: String) : Exception(message)

fun err(code: Int, message: String): Nothing = throw HttpError(HttpStatusCode.fromValue(code), message)

// ---------- Aides JSON ----------
fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

fun JsonObject.int(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

// ---------- Contexte utilisateur (JWT) ----------
class Ctx(val uid: Int, val orgId: Int, val role: String) {
    fun need(vararg roles: String) {
        if (role !in roles) err(403, "Droits insuffisants")
    }
}

fun ApplicationCall.ctx(): Ctx {
    val p = principal<JWTPrincipal>() ?: err(401, "Non connecté")
    return Ctx(
        p.payload.getClaim("uid").asInt(),
        p.payload.getClaim("org").asInt(),
        p.payload.getClaim("role").asString()
    )
}

fun ApplicationCall.pathId(name: String = "id"): Int =
    parameters[name]?.toIntOrNull() ?: err(400, "Identifiant invalide")

object Security {
    private val rnd = SecureRandom()

    fun token(bytes: Int = 24): String {
        val b = ByteArray(bytes)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun jwt(uid: Int, org: Int, role: String): String =
        JWT.create()
            .withClaim("uid", uid)
            .withClaim("org", org)
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + 14L * 24 * 3600 * 1000))
            .sign(Algorithm.HMAC256(Env.jwtSecret))

    // ----- Chiffrement des clés API / tokens (AES-GCM) -----
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(Env.dataKey.toByteArray()), "AES"
    )

    fun enc(plain: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(12)
        rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    fun dec(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val all = Base64.getDecoder().decode(stored)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all.copyOfRange(12, all.size)))
        } catch (e: Exception) {
            ""
        }
    }

    // ----- Limitation des essais de connexion -----
    private val fails = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun checkLoginAllowed(email: String) {
        val (n, since) = fails[email] ?: return
        if (System.currentTimeMillis() - since > 10 * 60_000) {
            fails.remove(email)
        } else if (n >= 10) {
            err(429, "Trop d'essais. Réessayez dans quelques minutes.")
        }
    }

    fun loginFailed(email: String) {
        val now = System.currentTimeMillis()
        val cur = fails[email]
        fails[email] = if (cur == null || now - cur.second > 10 * 60_000) Pair(1, now) else Pair(cur.first + 1, cur.second)
    }

    fun loginOk(email: String) {
        fails.remove(email)
    }
}

/** Empêche le serveur d'appeler des adresses internes (SSRF). */
object Ssrf {
    fun check(raw: String) {
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            err(400, "URL invalide")
        }
        if (uri.scheme != "http" && uri.scheme != "https") err(400, "Seuls http et https sont autorisés")
        val host = uri.host ?: err(400, "URL invalide")
        if (Env.allowPrivateTargets) return
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            err(400, "Hôte introuvable")
        }
        for (a in addrs) {
            val b = a.address
            val ula = b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress || ula
            ) err(403, "Adresse interne interdite")
        }
    }
}
