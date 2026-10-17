package com.saash.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

object Env {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val jwtSecret: String = System.getenv("JWT_SECRET") ?: "dev-secret-change-me"
    val dataKey: String = System.getenv("DATA_KEY") ?: jwtSecret
    val publicUrl: String = (System.getenv("PUBLIC_URL") ?: "http://localhost:$port").trimEnd('/')
    val allowPrivateTargets: Boolean = System.getenv("ALLOW_PRIVATE_TARGETS") == "true"
}

/** Accès PostgreSQL en JDBC simple (requêtes paramétrées uniquement). */
object Db {
    private lateinit var ds: HikariDataSource

    fun init() {
        val raw = System.getenv("DATABASE_URL") ?: "postgresql://postgres:postgres@localhost:5432/saash"
        val uri = URI(raw.replace("postgres://", "postgresql://"))
        val info = (uri.userInfo ?: ":").split(":", limit = 2)
        val port = if (uri.port > 0) uri.port else 5432
        val ssl = System.getenv("DB_SSL") == "true"

        val cfg = HikariConfig()
        cfg.jdbcUrl = "jdbc:postgresql://${uri.host}:$port${uri.path}" + if (ssl) "?sslmode=require" else ""
        cfg.username = info[0]
        cfg.password = info.getOrElse(1) { "" }
        cfg.maximumPoolSize = 8

        var attempt = 0
        while (true) {
            try {
                ds = HikariDataSource(cfg)
                break
            } catch (e: Exception) {
                attempt++
                if (attempt >= 10) throw e
                println("Base de données indisponible, nouvel essai ($attempt)…")
                Thread.sleep(3000)
            }
        }
        migrate()
    }

    private fun bind(ps: PreparedStatement, params: List<Any?>) {
        params.forEachIndexed { i, p ->
            if (p == null) ps.setNull(i + 1, Types.NULL) else ps.setObject(i + 1, p)
        }
    }

    fun <T> query(sql: String, params: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<T>()
                    while (rs.next()) out.add(map(rs))
                    out
                }
            }
        }

    fun exec(sql: String, params: List<Any?> = emptyList()): Int =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeUpdate()
            }
        }

    /** INSERT ... RETURNING id */
    fun insert(sql: String, params: List<Any?> = emptyList()): Int =
        query(sql, params) { it.getInt(1) }.first()

    private fun migrate() {
        val statements = listOf(
            """CREATE TABLE IF NOT EXISTS orgs(
                id SERIAL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, created_at BIGINT NOT NULL,
                api_key TEXT NOT NULL DEFAULT '', callback_url TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE IF NOT EXISTS users(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                pass_hash TEXT NOT NULL, role TEXT NOT NULL, created_at BIGINT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS connectors(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'apikey',
                base_url TEXT NOT NULL DEFAULT '', health_path TEXT NOT NULL DEFAULT '',
                auth_type TEXT NOT NULL DEFAULT 'none', auth_name TEXT NOT NULL DEFAULT '',
                secret TEXT NOT NULL DEFAULT '', config TEXT NOT NULL DEFAULT '{}',
                webhook_token TEXT NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'unknown',
                latency INT NOT NULL DEFAULT 0, checks INT NOT NULL DEFAULT 0, ok_checks INT NOT NULL DEFAULT 0,
                last_checked BIGINT NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS incidents(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, connector_id INT, title TEXT NOT NULL,
                started_at BIGINT NOT NULL, resolved_at BIGINT NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS access_requests(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, connector_id INT, requester TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'pending',
                decided_by TEXT NOT NULL DEFAULT '', created_at BIGINT NOT NULL, decided_at BIGINT NOT NULL DEFAULT 0,
                ref TEXT NOT NULL DEFAULT '')""",
            "ALTER TABLE access_requests ADD COLUMN IF NOT EXISTS ref TEXT NOT NULL DEFAULT ''",
            """CREATE TABLE IF NOT EXISTS events(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, connector_id INT, type TEXT NOT NULL,
                user_ref TEXT NOT NULL DEFAULT '', minutes INT NOT NULL DEFAULT 0, payload TEXT NOT NULL DEFAULT '{}',
                ts BIGINT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS events_org_ts ON events(org_id, ts)",
            """CREATE TABLE IF NOT EXISTS dashboards(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, name TEXT NOT NULL, layout TEXT NOT NULL,
                updated_at BIGINT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS org_charts(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, name TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS org_nodes(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, chart_id INT, parent_id INT, name TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '', user_id INT)""",
            """CREATE TABLE IF NOT EXISTS controls(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, type TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL,
                code TEXT NOT NULL, connector_id INT, method TEXT NOT NULL DEFAULT 'POST', path TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '', state BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS controls_code ON controls(org_id, code)",
            """CREATE TABLE IF NOT EXISTS forms(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, name TEXT NOT NULL, fields TEXT NOT NULL,
                target TEXT NOT NULL DEFAULT 'custom')""",
            """CREATE TABLE IF NOT EXISTS form_submissions(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, form_id INT NOT NULL, data TEXT NOT NULL,
                submitter TEXT NOT NULL DEFAULT '', ts BIGINT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS automations(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, name TEXT NOT NULL, trigger TEXT NOT NULL,
                config TEXT NOT NULL DEFAULT '{}', action TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE)""",
            """CREATE TABLE IF NOT EXISTS notifications(
                id SERIAL PRIMARY KEY, org_id INT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
                ts BIGINT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS oauth_states(
                state TEXT PRIMARY KEY, org_id INT NOT NULL, connector_id INT NOT NULL, ts BIGINT NOT NULL)"""
        )
        statements.forEach { exec(it) }
    }
}
