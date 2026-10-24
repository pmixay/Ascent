package com.pmixay.ascent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A visible download owner that survives closing the Activity or removing its recent task. */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = HttpsModelSource()
    private var transfer: Job? = null
    private lateinit var network: ModelNetworkGate
    private var finishing = false
    private var lastNotification = 0L
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        running.value = true
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Подготовка распознавания речи и обновление моделей"
            setShowBadge(false)
        })
        try {
            startForeground(NOTIFICATION, notification("Подготавливаю модели…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            network = ModelNetworkGate(this, source) { message -> report(message, "waiting") }
        } catch (_: RuntimeException) {
            finishing = true
            ModelDownloads.recovery(this, replace = true)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (finishing) { stopSelf(startId); return START_NOT_STICKY }
        network.wakeForVisibleApp()
        if (transfer?.isActive != true) {
            transfer = scope.launch {
                try {
                    report("Проверяю модели…", "check")
                    ModelDownloads.transfer(this@ModelDownloadService, ModelTransferControl(source,
                        beforeRequest = network::beforeRequest,
                        retryRequest = network::retry,
                        shouldYield = { false })) { progress ->
                        val now = System.nanoTime()
                        if (now - lastNotification >= 1_000_000_000 || progress.verifying) {
                            val message = if (progress.verifying) "Проверяю модели…" else "Загружаю модели…"
                            report(message, if (progress.verifying) "verify" else "download", progress.completedBytes, progress.totalBytes)
                            lastNotification = now
                        }
                    }
                    finishing = true
                    report("Модели готовы", "ready")
                    ModelDownloads.cancelRecovery(this@ModelDownloadService)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    finishing = true
                    if (retryableModelFailure(error)) {
                        report("Загрузка приостановлена. Повторю автоматически", "waiting")
                        ModelDownloads.recovery(this@ModelDownloadService, replace = true,
                            delaySeconds = (((error as? ModelHttpException)?.retryAfterMs ?: 0) + 999) / 1000)
                    } else {
                        report(error.message?.take(200) ?: "Не удалось подготовить модели", "error")
                        ModelDownloads.cancelRecovery(this@ModelDownloadService)
                    }
                } finally {
                    source.cancelActiveRequest()
                    network.close()
                    if (finishing) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun report(message: String, phase: String, completed: Long? = null, total: Long? = null) {
        ModelDownloads.publish(this, message, phase, completed, total)
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message, completed, total))
        }
    }

    private fun notification(message: String, completed: Long? = null, total: Long? = null): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = if (completed != null && total != null && total > 0)
            "$message ${completed / 1_000_000} из ${(total + 999_999) / 1_000_000} МБ" else message
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ascent — модели речи")
            .setContentText(detail)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, if (completed != null && total != null && total > 0)
                (completed.toDouble() / total * 1000).toInt().coerceIn(0, 1000) else 0,
                completed == null || total == null || total <= 0)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync to six background hours per day. Preserve chunks
        // and hand recovery to the scheduler instead of restarting around that limit.
        finishing = true
        ModelDownloads.publish(this, "Android приостановил фоновую загрузку. Прогресс сохранён", "waiting")
        source.cancelActiveRequest()
        if (::network.isInitialized) network.close()
        scope.cancel()
        ModelDownloads.recovery(this, replace = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        source.cancelActiveRequest()
        if (::network.isInitialized) network.close()
        scope.cancel()
        running.value = false
        if (!finishing) ModelDownloads.recovery(this, replace = true)
        super.onDestroy()
    }

    companion object {
        internal val running = MutableStateFlow(false)
        private const val CHANNEL = "ascent-model-downloads"
        private const val NOTIFICATION = 4101
    }
}

/** Android adapter around an interruptible network gate; the retry policy is independently testable. */
internal class ModelNetworkGate(
    context: Context,
    private val source: ModelSource,
    waiting: (String) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val wakeMonitor = Any()
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ascent:ModelDownload").apply { setReferenceCounted(false) }
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val gate = DownloadRecoveryGate(
        waiting = { online -> waiting(if (online) "Связь прервалась. Возобновляю загрузку…"
            else "Нет подключения. Продолжу, когда появится интернет") },
        active = { synchronized(wakeMonitor) { wakeLock.acquire(TimeUnit.MINUTES.toMillis(10)) } },
        inactive = ::releaseWakeLock,
        connectionInterrupted = source::cancelActiveRequest,
    )
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { gate.networkAvailable(network.networkHandle) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            gate.networkCapabilities(network.networkHandle,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        override fun onLost(network: Network) { gate.networkLost(network.networkHandle) }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            gate.networkBlocked(network.networkHandle, blocked)
        }
    }
    init { manager.registerDefaultNetworkCallback(callback) }

    fun wakeForVisibleApp() = gate.wakeForVisibleApp()
    fun beforeRequest() = gate.beforeRequest()
    fun retry(error: IOException, attempt: Int) = gate.retry((error as? ModelHttpException)?.retryAfterMs ?: 0, attempt)
    private fun releaseWakeLock() = synchronized(wakeMonitor) { if (wakeLock.isHeld) wakeLock.release() }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            gate.close()
            manager.unregisterNetworkCallback(callback)
        }
    }
}
