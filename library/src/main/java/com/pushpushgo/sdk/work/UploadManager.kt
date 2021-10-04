package com.pushpushgo.sdk.work

import androidx.work.*
import com.pushpushgo.sdk.PushPushGo
import com.pushpushgo.sdk.data.Event
import com.pushpushgo.sdk.data.EventJsonAdapter
import com.pushpushgo.sdk.data.EventType
import com.pushpushgo.sdk.data.Payload
import com.pushpushgo.sdk.network.SharedPreferencesHelper
import com.pushpushgo.sdk.work.UploadWorker.Companion.BEACON
import com.pushpushgo.sdk.work.UploadWorker.Companion.DATA
import com.pushpushgo.sdk.work.UploadWorker.Companion.EVENT
import com.pushpushgo.sdk.work.UploadWorker.Companion.REGISTER
import com.pushpushgo.sdk.work.UploadWorker.Companion.TYPE
import com.pushpushgo.sdk.work.UploadWorker.Companion.UNREGISTER
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

internal class UploadManager(
    private val workManager: WorkManager,
    private val sharedPref: SharedPreferencesHelper,
) {

    private val uploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val errorHandler = CoroutineExceptionHandler { _, e -> Timber.e(e) }

    private val eventAdapter by lazy { EventJsonAdapter(Moshi.Builder().build()) }

    private val uploadDelegate by lazy { UploadDelegate() }

    companion object {
        const val UPLOAD_DELAY = 10L
        const val UPLOAD_RETRY_DELAY = 30L
        const val UPLOAD_DEFERRED_ENABLE = false
    }

    fun sendRegister(token: String?) {
        Timber.tag(PushPushGo.TAG).d("Register enqueued")

        enqueueJob(REGISTER, isMustRunImmediately = true, data = token)
        listOf(UNREGISTER).forEach {
            workManager.cancelAllWorkByTag(it)
        }
    }

    fun sendUnregister() {
        if (!sharedPref.isSubscribed) {
            Timber.tag(PushPushGo.TAG).d("Can't unregister, because device not registered. Skipping")
            return
        }

        Timber.tag(PushPushGo.TAG).d("Unregister enqueued")

        enqueueJob(UNREGISTER, isMustRunImmediately = true)
        listOf(REGISTER, EVENT, BEACON).forEach {
            workManager.cancelAllWorkByTag(it)
        }
    }

    fun sendEvent(type: EventType, buttonId: Int, campaign: String) {
        Timber.tag(PushPushGo.TAG).d("Event enqueued: ($type, $buttonId, $campaign)")

        val eventContent = eventAdapter.toJson(
            Event(
                type = type.value,
                payload = Payload(
                    button = buttonId,
                    campaign = campaign,
                    subscriber = sharedPref.subscriberId
                )
            )
        )

        if (UPLOAD_DEFERRED_ENABLE) enqueueJob(
            name = EVENT,
            isMustRunImmediately = true,
            data = eventContent
        ) else uploadScope.launch(errorHandler) {
            uploadDelegate.doNetworkWork(EVENT, eventContent)
        }
    }

    fun sendBeacon(beacon: JSONObject) {
        if (!sharedPref.isSubscribed) {
            Timber.tag(PushPushGo.TAG).d("Beacon not enqueued. Reason: not subscribed")
            return
        }

        Timber.tag(PushPushGo.TAG).d("Beacon enqueued: $beacon")

        if (UPLOAD_DEFERRED_ENABLE) enqueueJob(
            name = BEACON,
            data = beacon.toString()
        ) else uploadScope.launch(errorHandler) {
            uploadDelegate.doNetworkWork(BEACON, beacon.toString())
        }
    }

    private fun enqueueJob(name: String, data: String? = null, isMustRunImmediately: Boolean = false) {
        workManager.enqueueUniqueWork(
            name,
            if (name == REGISTER || name == UNREGISTER) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(TYPE to name, DATA to data)
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, UPLOAD_RETRY_DELAY, TimeUnit.SECONDS)
                .setInitialDelay(if (isMustRunImmediately || isJobAlreadyEnqueued(name)) 0 else UPLOAD_DELAY, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                ).build()
        )
    }

    private fun isJobAlreadyEnqueued(name: String) = try {
        workManager.getWorkInfosForUniqueWork(name).get().any {
            it.state == WorkInfo.State.ENQUEUED
        }
    } catch (e: InterruptedException) {
        false
    }
}
