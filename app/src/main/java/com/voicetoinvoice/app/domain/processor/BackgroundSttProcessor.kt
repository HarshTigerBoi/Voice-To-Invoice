package com.voicetoinvoice.app.domain.processor

import android.content.Context
import android.util.Log
import com.voicetoinvoice.app.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Enqueues QUEUED [SttJobRecord]s onto WorkManager. The actual STT/parsing pipeline
 * lives server-side (supabase/functions/process-voice-job/index.ts) and is driven by
 * [SttWorker] -- this class used to also carry a full client-side parse pipeline
 * (processSingleJob), but that method had zero call sites (dead since the move to
 * server-first processing) and had drifted out of sync with the server on
 * multi-item price handling. Removed rather than kept as an unreachable second
 * implementation of the same logic -- see ISSUE-029.
 */
class BackgroundSttProcessor(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val db = AppDatabase.getInstance(context)
    private var processingJob: Job? = null

    fun triggerQueueProcessing() {
        if (processingJob?.isActive == true) return

        processingJob = scope.launch(Dispatchers.IO) {
            val queuedJobs = db.sttJobDao().getQueuedJobsList()
            for (job in queuedJobs) {
                try {
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<SttWorker>()
                        .setInputData(
                            androidx.work.workDataOf(
                                SttWorker.KEY_JOB_ID to job.id,
                                SttWorker.KEY_AUDIO_PATH to job.audioFilePath
                            )
                        )
                        .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                } catch (e: Exception) {
                    Log.e("BackgroundSttProcessor", "Failed to enqueue SttWorker for job ${job.id}", e)
                }
            }
        }
    }
}
