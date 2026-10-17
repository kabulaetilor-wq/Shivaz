package com.saash.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object Notifier {
    private const val CHANNEL = "alerts"

    fun send(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alertes SaaS H", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // permission refusée : on ignore
        }
    }
}

/** Interroge le serveur toutes les 15 minutes quand l'app est fermée. */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val api = Api(applicationContext)
        if (api.token.isBlank() || api.baseUrl.isBlank() || !api.notifyEnabled) return Result.success()
        return try {
            val list = JSONArray(api.call("GET", "/notifications?after=${api.lastNotifId}")).objs().map { it.toNotif() }
            if (list.isNotEmpty()) {
                val first = api.lastNotifId == 0
                api.lastNotifId = list.maxOf { it.id }
                if (!first) {
                    list.sortedBy { it.id }.takeLast(5).forEach {
                        Notifier.send(applicationContext, it.id, it.title, it.body)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object Scheduler {
    fun start(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork("saash-poll", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
