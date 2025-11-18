package com.samsung.screenshoterjetpackcompose

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class ScreenshotService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeSessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForegroundNotification(getString(R.string.notification_preparing))
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    startProjection(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_PREVIEW_DECISION -> {
                val accepted = intent.getBooleanExtra(EXTRA_PREVIEW_ACCEPTED, false)
                handlePreviewDecision(accepted)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionObserverJob?.cancel()
        serviceScope.cancel()
        FloatingCaptureOverlay.hide(this)
        ScreenCaptureManager.release()
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
            ?: run {
                stopSelf()
                return
            }
        ScreenCaptureManager.initialize(this, projection)
        ensureForegroundNotification(getString(R.string.notification_ready))
        showFloatingControls()
    }

    private fun ensureForegroundNotification(content: String) {
        val notification = buildNotification(content)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.samsung.screenshoter.action.START"
        const val ACTION_PREVIEW_DECISION = "com.samsung.screenshoter.action.PREVIEW_DECISION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_PREVIEW_ACCEPTED = "extra_preview_accepted"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_capture"
        private const val OVERLAY_HIDE_DELAY_MS = 300L
        private const val CAPTURE_STABILIZE_DELAY_MS = 120L
        private const val OVERLAY_RESUME_DELAY_MS = 200L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenshotService::class.java))
        }
    }

    private fun showFloatingControls() {
        FloatingCaptureOverlay.show(this) {
            serviceScope.launch {
                FloatingCaptureOverlay.hide(this@ScreenshotService)
                delay(OVERLAY_HIDE_DELAY_MS)
                val requiresConfirmation = ScreenCaptureManager.shouldConfirmBeforeSaving()
                if (requiresConfirmation) {
                    val captured = try {
                        delay(CAPTURE_STABILIZE_DELAY_MS)
                        ScreenCaptureManager.captureForPreview()
                    } finally {
                        delay(OVERLAY_RESUME_DELAY_MS)
                    }
                    if (captured) {
                        launchPreviewActivity()
                    } else {
                        val message = getString(R.string.capture_failed)
                        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                        if (ScreenCaptureManager.isReady()) {
                            showFloatingControls()
                        }
                    }
                } else {
                    val uri = try {
                        delay(CAPTURE_STABILIZE_DELAY_MS)
                        ScreenCaptureManager.captureAndStore(applicationContext)
                    } finally {
                        delay(OVERLAY_RESUME_DELAY_MS)
                    }
                    val message = if (uri != null) {
                        getString(R.string.capture_saved_simple, resolveFolderLabel())
                    } else {
                        getString(R.string.capture_failed)
                    }
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                    if (ScreenCaptureManager.isReady()) {
                        showFloatingControls()
                    }
                }
            }
        }
    }

    private fun launchPreviewActivity() {
        val intent = Intent(this, PreviewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun handlePreviewDecision(accepted: Boolean) {
        serviceScope.launch {
            val message = if (accepted) {
                val pending = ScreenCaptureManager.getPendingCapture()
                val folderLabel = pending?.let {
                    ScreenCaptureManager.getFolderLabel(applicationContext, it.subDirectory)
                } ?: resolveFolderLabel()
                val uri = ScreenCaptureManager.persistPendingCapture(applicationContext)
                if (uri != null) {
                    getString(R.string.capture_saved_simple, folderLabel)
                } else {
                    getString(R.string.capture_failed)
                }
            } else {
                ScreenCaptureManager.discardPendingCapture()
                getString(R.string.capture_discarded)
            }

            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

            delay(OVERLAY_RESUME_DELAY_MS)
            if (ScreenCaptureManager.isReady()) {
                showFloatingControls()
            } else {
                stopSelf()
            }
        }
    }

    private fun observeSessionState() {
        sessionObserverJob = serviceScope.launch {
            ScreenCaptureManager.isSessionActive.collectLatest { active ->
                if (!active) {
                    FloatingCaptureOverlay.hide(this@ScreenshotService)
                }
            }
        }
    }

    private fun resolveFolderLabel(): String {
        return when (val folder = ScreenCaptureManager.currentSubdirectory.value) {
            "" -> getString(R.string.folder_default)
            "movies" -> getString(R.string.folder_movies)
            "food" -> getString(R.string.folder_food)
            "shopping" -> getString(R.string.folder_shopping)
            "conversation" -> getString(R.string.folder_conversation)
            "location" -> getString(R.string.folder_location)
            "coupon" -> getString(R.string.folder_coupon)
            "calendar" -> getString(R.string.folder_calendar)
            "restaurant" -> getString(R.string.folder_restaurant)
            "fashion" -> getString(R.string.folder_fashion)
            "transportation" -> getString(R.string.folder_transportation)
            "humor" -> getString(R.string.folder_humor)
            "article" -> getString(R.string.folder_article)
            "music" -> getString(R.string.folder_music)
            "people" -> getString(R.string.folder_people)
            "books" -> getString(R.string.folder_books)
            "stock" -> getString(R.string.folder_stock)
            "sports" -> getString(R.string.folder_sports)
            "health" -> getString(R.string.folder_health)
            else -> folder.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.getDefault())
                } else {
                    char.toString()
                }
            }
        }
    }
}
