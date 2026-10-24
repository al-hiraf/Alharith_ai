package com.alharith.ai.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alharith.ai.data.Prefs
import com.alharith.ai.data.SharedBrain
import java.util.concurrent.TimeUnit

/**
 * يجلب إشعارات خادم رفيق (تذكيرات، مهام الفريق، الموافقات…) والتطبيق مغلق.
 * أندرويد يشغّله كل ١٥ دقيقة تقريبًا عند توفر الإنترنت؛ قد يتأخر في وضع توفير البطارية.
 */
class NoticeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Prefs.sharedBrainReady) return Result.success()
        return try {
            SharedBrain.syncNow()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val NAME = "rafiq-server-notices"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<NoticeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
