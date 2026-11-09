package az.nvaton.komekci

import az.nvaton.komekci.core.tr
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import az.nvaton.komekci.a11y.ScreenReader
import az.nvaton.komekci.ui.CameraScreen
import az.nvaton.komekci.ui.ContactsImportScreen
import az.nvaton.komekci.ui.HomeScreen
import az.nvaton.komekci.ui.KomekciTheme
import az.nvaton.komekci.ui.SettingsScreen
import az.nvaton.komekci.ui.rememberResumeTick
import az.nvaton.komekci.ui.setupStatus
import az.nvaton.komekci.wake.WakeWord
import az.nvaton.komekci.wake.WakeWordService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            KomekciTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                val contacts by komekci.contacts.contacts.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val resumeTick = rememberResumeTick()
                val setup = remember(resumeTick, state.screen) { setupStatus(context) }

                val askMicOnStart = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) WakeWordService.start(context)
                }
                val askMicThenListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        WakeWordService.start(context)
                        vm.onMicPressed()
                    }
                }
                LaunchedEffect(Unit) { if (!setup.mic) askMicOnStart.launch(Manifest.permission.RECORD_AUDIO) }
                val onMic = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) vm.onMicPressed() else askMicThenListen.launch(Manifest.permission.RECORD_AUDIO)
                }

                BackHandler(enabled = state.screen != Screen.HOME) { vm.back() }

                Box {
                    when (state.screen) {
                        Screen.HOME -> HomeScreen(
                            state = state,
                            setupComplete = setup.complete,
                            onMic = onMic,
                            onCamera = vm::openCamera,
                            onSettings = vm::openSettings,
                            onChoice = vm::submitText,
                            onSubmitText = vm::submitText,
                            onEditDraft = vm::editDraft,
                            onEditStart = vm::pauseForTyping,
                            onVoiceStop = vm::stopVoiceRecording,
                            onVoiceSend = vm::sendVoiceNow,
                            onVoiceReRecord = vm::reRecordVoice,
                            onVoiceCancel = vm::cancelVoice,
                        )
                        Screen.CAMERA -> CameraScreen(
                            state = state,
                            onRead = vm::readView,
                            onAsk = onMic,
                            onCapture = vm::analyzeImage,
                            onCaptureFailed = vm::captureFailed,
                            onBack = vm::back,
                            onRepeat = vm::repeatVision,
                            onRetake = vm::retakePhoto,
                        )
                        Screen.SETTINGS -> SettingsScreen(state, contacts, setup, vm)
                        Screen.CONTACTS -> ContactsImportScreen(contacts, vm)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        WakeWord.uiVisible = true
        WakeWordService.start(this) // Android only lets the microphone service start while the app is on screen
    }

    override fun onStop() {
        WakeWord.uiVisible = false
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE, false) == true) {
            intent.removeExtra(EXTRA_WAKE)
            showOverLockScreen()
        }
        handleDebugCommand(intent)
    }

    /** "Əli" while the phone is locked: wake the screen and show the app, like Siri. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    /**
     * Debug builds only, for testing without speaking:
     * adb shell am start -n az.nvaton.komekci/.MainActivity --es command "nəvəmə yaz salam"
     * adb shell am start -n az.nvaton.komekci/.MainActivity --ez simulate_wake true
     */
    private fun handleDebugCommand(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra(EXTRA_SIMULATE_WAKE, false)) {
            intent.removeExtra(EXTRA_SIMULATE_WAKE)
            vm.simulateWake()
        }
        if (intent.getBooleanExtra(EXTRA_STOP, false)) { // --ez stop true
            intent.removeExtra(EXTRA_STOP)
            vm.stopEverything()
        }
        // Feeds a WAV from the app's files dir to the wake-word detector as if the microphone heard it:
        // adb exec-in run-as az.nvaton.komekci sh -c 'cat > files/ali.wav' < ali.wav
        // adb shell am start -n az.nvaton.komekci/.MainActivity --es wake_test ali.wav
        intent.getStringExtra(EXTRA_WAKE_TEST)?.let { name ->
            intent.removeExtra(EXTRA_WAKE_TEST)
            java.io.File(filesDir, name).takeIf { it.isFile }?.let { WakeWord.inject(it.readBytes()) }
        }
        // "Əli, ekranı oxu" without the microphone: steps aside, reads the app behind, then asks as if spoken.
        // adb shell am start -n az.nvaton.komekci/.MainActivity --ez screen_test true
        if (intent.getBooleanExtra(EXTRA_SCREEN_TEST, false)) {
            intent.removeExtra(EXTRA_SCREEN_TEST)
            moveTaskToBack(true)
            window.decorView.postDelayed({
                Thread {
                    ScreenReader.capture()
                    runOnUiThread { vm.submitText(tr("ekranı oxu", "read the screen")) }
                }.start()
            }, 1_500)
        }
        // adb shell am start -n az.nvaton.komekci/.MainActivity --es voice_test Murad
        intent.getStringExtra(EXTRA_VOICE_TEST)?.let { name ->
            intent.removeExtra(EXTRA_VOICE_TEST)
            vm.debugStartVoice(name)
            return
        }
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        intent.removeExtra(EXTRA_COMMAND)
        vm.submitText(command)
    }

    companion object {
        /** Set by the wake-word service when it brings the app up. */
        const val EXTRA_WAKE = "wake"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_SIMULATE_WAKE = "simulate_wake"
        private const val EXTRA_WAKE_TEST = "wake_test"
        private const val EXTRA_SCREEN_TEST = "screen_test"
        private const val EXTRA_STOP = "stop"
        private const val EXTRA_VOICE_TEST = "voice_test"
    }
}
