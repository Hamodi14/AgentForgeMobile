package com.agentforge.mobile.execution

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class TermuxCommandCallbackResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errMessage: String?
)

/**
 * Receives asynchronous command results returned by Termux's `RunCommandService`
 * via `com.termux.RUN_COMMAND_PENDING_INTENT`.
 */
class TermuxResultService : Service() {

    companion object {
        private val _resultsFlow = MutableSharedFlow<TermuxCommandCallbackResult>(extraBufferCapacity = 16)
        val resultsFlow: SharedFlow<TermuxCommandCallbackResult> = _resultsFlow.asSharedFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultBundle = intent?.getBundleExtra("result")
        if (resultBundle != null) {
            val stdout = resultBundle.getString("stdout", "")
            val stderr = resultBundle.getString("stderr", "")
            val exitCode = resultBundle.getInt("exitCode", -1)
            val errmsg = resultBundle.getString("errmsg")
            _resultsFlow.tryEmit(
                TermuxCommandCallbackResult(
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode,
                    errMessage = errmsg
                )
            )
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
