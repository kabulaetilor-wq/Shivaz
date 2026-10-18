package com.saash.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    if (Env.jwtSecret == "dev-secret-change-me") {
        println("ATTENTION : JWT_SECRET non défini, secret de développement utilisé.")
    }
    Db.init()
    embeddedServer(Netty, port = Env.port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(StatusPages) {
        exception<HttpError> { call, e ->
            call.respond(e.code, buildJsonObject { put("error", e.message ?: "Erreur") })
        }
        exception<Throwable> { call, e ->
            val name = e::class.simpleName ?: ""
            if (name.contains("BadRequest") || name.contains("ContentTransformation") || name.contains("Serialization")) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "Requête invalide") })
            } else {
                call.application.environment.log.error("Erreur non gérée", e)
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject { put("error", "Erreur serveur") })
            }
        }
    }

    install(Authentication) {
        jwt("auth") {
            verifier(JWT.require(Algorithm.HMAC256(Env.jwtSecret)).build())
            validate { cred ->
                if (cred.payload.getClaim("uid").asInt() != null) JWTPrincipal(cred.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "Non connecté") })
            }
        }
    }

    routing {
        publicRoutes()
        authenticate("auth") {
            apiRoutes()
        }
    }

    // Vérification de santé de tous les connecteurs, toutes les 60 secondes.
    launch(Dispatchers.IO) {
        delay(5_000)
        while (isActive) {
            try {
                Repo.checkAll(Repo.pingable())
            } catch (e: Exception) {
                environment.log.error("Erreur du moniteur", e)
            }
            delay(60_000)
        }
    }
}
