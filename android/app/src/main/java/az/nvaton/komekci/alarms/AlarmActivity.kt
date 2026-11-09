package az.nvaton.komekci.alarms

import az.nvaton.komekci.ui.TonalButton
import az.nvaton.komekci.ui.PrimaryButton
import az.nvaton.komekci.ui.AppIcons
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import az.nvaton.komekci.core.tr
import az.nvaton.komekci.komekci
import az.nvaton.komekci.ui.KomekciTheme
import java.lang.ref.WeakReference

/**
 * The ringing alarm: big time and label, «Söndür» and «5 dəqiqə sonra». Says what the alarm is for, then rings
 * until stopped (at most [MAX_RING_MS]). Shows over the lock screen and turns the screen on.
 */
class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val label = intent.getStringExtra(AlarmReceiver.EXTRA_LABEL).orEmpty()
        val time = intent.getStringExtra(AlarmReceiver.EXTRA_TIME).orEmpty()
        setContent {
            KomekciTheme {
                val colors = MaterialTheme.colorScheme
                Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(120.dp).clip(CircleShape).background(colors.primaryContainer),
                            ) { Icon(AppIcons.AlarmFilled, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(64.dp)) }
                            Text(time, style = MaterialTheme.typography.displayLarge, color = colors.onBackground)
                            Text(
                                label.ifBlank { tr("Zəngli saat", "Alarm") },
                                style = MaterialTheme.typography.headlineMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        PrimaryButton(
                            tr("Söndür", "Stop"),
                            { AlarmReceiver.stop(this@AlarmActivity) },
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.StopFilled,
                            height = 88.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        TonalButton(
                            tr("5 dəqiqə sonra", "In 5 minutes"),
                            {
                                AlarmReceiver.stop(this@AlarmActivity)
                                AlarmReceiver.snooze(this@AlarmActivity, label)
                            },
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.Snooze,
                            height = 72.dp,
                        )
                    }
                }
            }
        }

        // First say what it is for, then ring.
        val speech = if (label.isBlank()) tr("Saat $time. Zəngli saat.", "It's $time. Your alarm.") else tr("Saat $time. $label.", "It's $time. $label.")
        komekci.speaker.speak(speech) { startRinging() }
        handler.postDelayed({ AlarmReceiver.stop(this) }, MAX_RING_MS)
    }

    private fun startRinging() {
        if (isFinishing || player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(this@AlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        komekci.speaker.stop()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_RING_MS = 3 * 60_000L
        private var current: WeakReference<AlarmActivity>? = null

        fun finishRinging() {
            current?.get()?.finish()
        }
    }
}
