/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.*
import android.util.Log
import android.util.Rational
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.updateMargins
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import emu.skyline.BuildConfig
import emu.skyline.applet.swkbd.SoftwareKeyboardConfig
import emu.skyline.applet.swkbd.SoftwareKeyboardDialog
import emu.skyline.databinding.EmuActivityBinding
import emu.skyline.input.*
import emu.skyline.loader.getRomFormat
import emu.skyline.utils.ByteBufferSerializable
import emu.skyline.utils.GpuDriverHelper
import emu.skyline.utils.NativeSettings
import emu.skyline.utils.PreferenceSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class EmulationActivity : AppCompatActivity(), SurfaceHolder.Callback, View.OnTouchListener, DisplayManager.DisplayListener {
    companion object {
        private val Tag = EmulationActivity::class.java.simpleName
        const val ReturnToMainTag = "returnToMain"

        /**
         * The Kotlin thread on which emulation code executes
         */
        private var emulationThread : Thread? = null
    }

    private val binding by lazy { EmuActivityBinding.inflate(layoutInflater) }

    /**
     * A map of [Vibrator]s that correspond to [InputManager.controllers]
     */
    private var vibrators = HashMap<Int, Vibrator>()

    /**
     * If the emulation thread should call [returnToMain] or not
     */
    @Volatile
    private var shouldFinish : Boolean = true

    /**
     * If the activity should return to [MainActivity] or just call [finishAffinity]
     */
    private var returnToMain : Boolean = false

    /**
     * The desired refresh rate to present at in Hz
     */
    private var desiredRefreshRate = 60f

    private var isEmulatorPaused = false

    private lateinit var pictureInPictureParamsBuilder : PictureInPictureParams.Builder
    private val intentActionPause = "${BuildConfig.APPLICATION_ID}.ACTION_EMULATOR_PAUSE"
    private val intentActionMute = "${BuildConfig.APPLICATION_ID}.ACTION_EMULATOR_MUTE"
    private lateinit var pictureInPictureReceiver : BroadcastReceiver

    @Inject
    lateinit var preferenceSettings : PreferenceSettings

    lateinit var nativeSettings : NativeSettings

    @Inject
    lateinit var inputManager : InputManager

    lateinit var inputHandler : InputHandler

    private var gameSurface : Surface? = null

    /**
     * This is the entry point into the emulation code for libskyline
     *
     * @param romUri The URI of the ROM as a string, used to print out in the logs
     * @param romType The type of the ROM as an enum value
     * @param romFd The file descriptor of the ROM object
     * @param nativeSettings The settings to be used by libskyline
     * @param publicAppFilesPath The full path to the public app files directory
     * @param privateAppFilesPath The full path to the private app files directory
     * @param nativeLibraryPath The full path to the app native library directory
     * @param assetManager The asset manager used for accessing app assets
     */
    private external fun executeApplication(romUri : String, romType : Int, romFd : Int, nativeSettings : NativeSettings, publicAppFilesPath : String, privateAppFilesPath : String, nativeLibraryPath : String, assetManager : AssetManager)

    /**
     * @param join If the function should only return after all the threads join or immediately
     * @return If it successfully caused [emulationThread] to gracefully stop or do so asynchronously when not joined
     */
    private external fun stopEmulation(join : Boolean) : Boolean

    /**
     * This sets the surface object in libskyline to the provided value, emulation is halted if set to null
     *
     * @param surface The value to set surface to
     * @return If the value was successfully set
     */
    private external fun setSurface(surface : Surface?) : Boolean

    /**
     * @param play If the audio should be playing or be stopped till it is resumed by calling this again
     */
    private external fun changeAudioStatus(play : Boolean)

    var fps : Int = 0
    var averageFrametime : Float = 0.0f
    var averageFrametimeDeviation : Float = 0.0f

    /**
     * Writes the current performance statistics into [fps], [averageFrametime] and [averageFrametimeDeviation] fields
     */
    private external fun updatePerformanceStatistics()

    /**
     * @see [InputHandler.initializeControllers]
     */
    @Suppress("unused")
    private fun initializeControllers() {
        inputHandler.initializeControllers()
        inputHandler.initialiseMotionSensors(this)
    }

    /**
     * Forces a 60Hz refresh rate for the primary display when [enable] is true, otherwise selects the highest available refresh rate
     */
    private fun force60HzRefreshRate(enable : Boolean) {
        // Hack for MIUI devices since they don't support the standard Android APIs
        try {
            val setFpsIntent = Intent("com.miui.powerkeeper.SET_ACTIVITY_FPS")
            setFpsIntent.putExtra("package_name", "skyline.emu")
            setFpsIntent.putExtra("isEnter", enable)
            sendBroadcast(setFpsIntent)
        } catch (_ : Exception) {
        }

        @Suppress("DEPRECATION") val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!! else windowManager.defaultDisplay
        if (enable)
            display?.supportedModes?.minByOrNull { abs(it.refreshRate - 60f) }?.let { window.attributes.preferredDisplayModeId = it.modeId }
        else
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { window.attributes.preferredDisplayModeId = it.modeId }
    }

    /**
     * Return from emulation to either [MainActivity] or the activity on the back stack
     */
    @SuppressWarnings("WeakerAccess")
    fun returnFromEmulation() {
        if (shouldFinish) {
            runOnUiThread {
                if (shouldFinish) {
                    shouldFinish = false
                    if (returnToMain)
                        startActivity(Intent(applicationContext, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    /**
     * @note Any caller has to handle the application potentially being restarted with the supplied intent
     */
    private fun executeApplication(intent : Intent) {
        if (emulationThread?.isAlive == true) {
            shouldFinish = false
            if (stopEmulation(false))
                emulationThread!!.join(250)

            if (emulationThread!!.isAlive) {
                finishAffinity()
                startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
        }

        shouldFinish = true
        returnToMain = intent.getBooleanExtra(ReturnToMainTag, false)

        val rom = intent.data!!
        val romType = getRomFormat(rom, contentResolver).ordinal

        @SuppressLint("Recycle")
        val romFd = contentResolver.openFileDescriptor(rom, "r")!!

        GpuDriverHelper.ensureFileRedirectDir(this)
        emulationThread = Thread {
            executeApplication(rom.toString(), romType, romFd.detachFd(), nativeSettings, applicationContext.getPublicFilesDir().canonicalPath + "/", applicationContext.filesDir.canonicalPath + "/", applicationInfo.nativeLibraryDir + "/", assets)
            returnFromEmulation()
        }

        emulationThread!!.start()
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = preferenceSettings.orientation
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        inputHandler = InputHandler(inputManager, preferenceSettings)
        nativeSettings = NativeSettings(this, preferenceSettings)

        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android might not allow child views to overlap the system bars
            // Override this behavior and force content to extend into the cutout area
            window.setDecorFitsSystemWindows(false)

            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        }

        if (preferenceSettings.respectDisplayCutout) {
            binding.perfStats.setOnApplyWindowInsetsListener(insetsOrMarginHandler)
            binding.onScreenControllerToggle.setOnApplyWindowInsetsListener(insetsOrMarginHandler)
        }

        binding.gameView.holder.addCallback(this)

        binding.gameView.setAspectRatio(
            when (preferenceSettings.aspectRatio) {
                0 -> Rational(16, 9)
                1 -> Rational(21, 9)
                else -> null
            }
        )

        if (preferenceSettings.perfStats) {
            if (preferenceSettings.disableFrameThrottling)
                binding.perfStats.setTextColor(getColor(R.color.colorPerfStatsSecondary))

            binding.perfStats.apply {
                postDelayed(object : Runnable {
                    override fun run() {
                        updatePerformanceStatistics()
                        text = "$fps FPS\n${"%.1f".format(averageFrametime)}±${"%.2f".format(averageFrametimeDeviation)}ms"
                        postDelayed(this, 250)
                    }
                }, 250)
                setOnClickListener {
                    val newValue = !preferenceSettings.disableFrameThrottling
                    preferenceSettings.disableFrameThrottling = newValue
                    nativeSettings.disableFrameThrottling = newValue

                    var color = if (newValue) getColor(R.color.colorPerfStatsSecondary) else getColor(R.color.colorPerfStatsPrimary)
                    binding.perfStats.setTextColor(color)
                    nativeSettings.updateNative()
                }
            }
        }

        force60HzRefreshRate(!preferenceSettings.maxRefreshRate)
        getSystemService<DisplayManager>()?.registerDisplayListener(this, null)

        pictureInPictureParamsBuilder = getPictureInPictureBuilder()

        binding.gameView.setOnTouchListener(this)

        // Hide on screen controls when first controller is not set
        binding.onScreenControllerView.apply {
            controllerType = inputHandler.getFirstControllerType()
            isGone = controllerType == ControllerType.None || !preferenceSettings.onScreenControl
            setOnButtonStateChangedListener(::onButtonStateChanged)
            setOnStickStateChangedListener(::onStickStateChanged)
            hapticFeedback = preferenceSettings.onScreenControl && preferenceSettings.onScreenControlFeedback
            recenterSticks = preferenceSettings.onScreenControlRecenterSticks
        }

        binding.onScreenControllerToggle.apply {
            isGone = binding.onScreenControllerView.isGone
            setOnClickListener { binding.onScreenControllerView.isInvisible = !binding.onScreenControllerView.isInvisible }
        }

        binding.onScreenPauseToggle.apply {
            isGone = binding.onScreenControllerView.isGone
            setOnClickListener {
                if (isEmulatorPaused) {
                    resumeEmulator()
                    binding.onScreenPauseToggle.setImageResource(R.drawable.ic_pause)
                } else {
                    pauseEmulator()
                    binding.onScreenPauseToggle.setImageResource(R.drawable.ic_play)
                }
            }
        }

        executeApplication(intent!!)
    }

    @SuppressWarnings("WeakerAccess")
    fun pauseEmulator() {
        if (isEmulatorPaused) return
        setSurface(null)
        changeAudioStatus(false)
        isEmulatorPaused = true
    }

    @SuppressWarnings("WeakerAccess")
    fun resumeEmulator() {
        if (!isEmulatorPaused) return
        gameSurface?.let { setSurface(it) }
        if (!preferenceSettings.isAudioOutputDisabled)
            changeAudioStatus(true)
        isEmulatorPaused = false
    }

    override fun onPause() {
        super.onPause()

        if (preferenceSettings.forceMaxGpuClocks)
            GpuDriverHelper.forceMaxGpuClocks(false)

        pauseEmulator()
    }

    override fun onStart() {
        super.onStart()

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnFromEmulation()
            }
        })
    }

    override fun onResume() {
        super.onResume()

        resumeEmulator()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun getPictureInPictureBuilder() : PictureInPictureParams.Builder {
        val pictureInPictureParamsBuilder = PictureInPictureParams.Builder()

        val pictureInPictureActions : MutableList<RemoteAction> = mutableListOf()
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pauseIcon = Icon.createWithResource(this, R.drawable.ic_pause)
        val pausePendingIntent = PendingIntent.getBroadcast(this, R.drawable.ic_pause, Intent(intentActionPause), pendingFlags)
        val pauseRemoteAction = RemoteAction(pauseIcon, getString(R.string.pause), getString(R.string.pause_emulator), pausePendingIntent)
        pictureInPictureActions.add(pauseRemoteAction)

        if (!preferenceSettings.isAudioOutputDisabled) {
            val muteIcon = Icon.createWithResource(this, R.drawable.ic_volume_mute)
            val mutePendingIntent = PendingIntent.getBroadcast(this, R.drawable.ic_volume_mute, Intent(intentActionMute), pendingFlags)
            val muteRemoteAction = RemoteAction(muteIcon, getString(R.string.mute), getString(R.string.disable_audio_output), mutePendingIntent)
            pictureInPictureActions.add(muteRemoteAction)
        }

        pictureInPictureParamsBuilder.setActions(pictureInPictureActions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            pictureInPictureParamsBuilder.setAutoEnterEnabled(true)

        setPictureInPictureParams(pictureInPictureParamsBuilder.build())

        return pictureInPictureParamsBuilder
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            pictureInPictureReceiver = object : BroadcastReceiver() {
                override fun onReceive(context : Context?, intent : Intent) {
                    if (intent.action == intentActionPause)
                        pauseEmulator()
                    else if (intent.action == intentActionMute)
                        changeAudioStatus(false)
                }
            }

            IntentFilter().apply {
                addAction(intentActionPause)
                if (!preferenceSettings.isAudioOutputDisabled)
                    addAction(intentActionMute)
            }.also {
                registerReceiver(pictureInPictureReceiver, it)
            }

            binding.onScreenControllerView.isGone = true
            binding.onScreenControllerToggle.isGone = true
            binding.onScreenPauseToggle.isGone = true
        } else {
            try {
                if (this::pictureInPictureReceiver.isInitialized)
                    unregisterReceiver(pictureInPictureReceiver)
            } catch (ignored : Exception) {
                // Perfectly acceptable and should be ignored
            }

            resumeEmulator()
            
            binding.onScreenControllerView.apply {
                controllerType = inputHandler.getFirstControllerType()
                isGone = controllerType == ControllerType.None || !preferenceSettings.onScreenControl
            }
            binding.onScreenControllerToggle.apply {
                isGone = binding.onScreenControllerView.isGone
            }
            binding.onScreenPauseToggle.apply {
                isGone = binding.onScreenControllerView.isGone
            }
        }
    }

    /**
     * Stop the currently executing ROM and replace it with the one specified in the new intent
     */
    override fun onNewIntent(intent : Intent?) {
        super.onNewIntent(intent!!)
        if (getIntent().data != intent.data) {
            setIntent(intent)
            executeApplication(intent)
        }
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isInPictureInPictureMode)
            enterPictureInPictureMode(pictureInPictureParamsBuilder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldFinish = false

        // Stop forcing 60Hz on exit to allow the skyline UI to run at high refresh rates
        getSystemService<DisplayManager>()?.unregisterDisplayListener(this)
        force60HzRefreshRate(false)
        if (preferenceSettings.forceMaxGpuClocks)
            GpuDriverHelper.forceMaxGpuClocks(false)

        stopEmulation(false)
        vibrators.forEach { (_, vibrator) -> vibrator.cancel() }
        vibrators.clear()
    }

    override fun surfaceCreated(holder : SurfaceHolder) {
        Log.d(Tag, "surfaceCreated Holder: $holder")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        // Note: We need FRAME_RATE_COMPATIBILITY_FIXED_SOURCE as there will be a degradation of user experience with FRAME_RATE_COMPATIBILITY_DEFAULT due to game speed alterations when the frame rate doesn't match the display refresh rate
            holder.surface.setFrameRate(desiredRefreshRate, if (preferenceSettings.maxRefreshRate) Surface.FRAME_RATE_COMPATIBILITY_DEFAULT else Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)

        while (emulationThread!!.isAlive)
            if (setSurface(holder.surface)) {
                gameSurface = holder.surface
                return
            }
    }

    /**
     * This is purely used for debugging surface changes
     */
    override fun surfaceChanged(holder : SurfaceHolder, format : Int, width : Int, height : Int) {
        Log.d(Tag, "surfaceChanged Holder: $holder, Format: $format, Width: $width, Height: $height")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            holder.surface.setFrameRate(desiredRefreshRate, if (preferenceSettings.maxRefreshRate) Surface.FRAME_RATE_COMPATIBILITY_DEFAULT else Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }

    override fun surfaceDestroyed(holder : SurfaceHolder) {
        Log.d(Tag, "surfaceDestroyed Holder: $holder")
        while (emulationThread!!.isAlive)
            if (setSurface(null)) {
                gameSurface = null
                return
            }
    }

    override fun dispatchKeyEvent(event : KeyEvent) : Boolean {
        return if (inputHandler.handleKeyEvent(event)) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event : MotionEvent) : Boolean {
        return if (inputHandler.handleMotionEvent(event)) true else super.dispatchGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view : View, event : MotionEvent) : Boolean {
        return inputHandler.handleTouchEvent(view, event)
    }

    private fun onButtonStateChanged(buttonId : ButtonId, state : ButtonState) = InputHandler.setButtonState(0, buttonId.value(), state.state)

    private fun onStickStateChanged(stickId : StickId, position : PointF) {
        InputHandler.setAxisValue(0, stickId.xAxis.ordinal, (position.x * Short.MAX_VALUE).toInt())
        InputHandler.setAxisValue(0, stickId.yAxis.ordinal, (-position.y * Short.MAX_VALUE).toInt()) // Y is inverted, since drawing starts from top left
    }

    @SuppressLint("WrongConstant")
    @Suppress("unused")
    fun vibrateDevice(index : Int, timing : LongArray, amplitude : IntArray) {
        val vibrator = if (vibrators[index] != null) {
            vibrators[index]
        } else {
            inputManager.controllers[index]!!.rumbleDeviceDescriptor?.let {
                if (it == Controller.BuiltinRumbleDeviceDescriptor) {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                        vibratorManager.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    }
                    vibrators[index] = vibrator
                    vibrator
                } else {
                    for (id in InputDevice.getDeviceIds()) {
                        val device = InputDevice.getDevice(id)
                        if (device.descriptor == inputManager.controllers[index]!!.rumbleDeviceDescriptor) {
                            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                device.vibratorManager.defaultVibrator
                            } else {
                                @Suppress("DEPRECATION")
                                device.vibrator!!
                            }
                            vibrators[index] = vibrator
                            return@let vibrator
                        }
                    }
                    return@let null
                }
            }
        }

        vibrator?.let {
            val effect = VibrationEffect.createWaveform(timing, amplitude, 0)
            it.vibrate(effect)
        }
    }

    @Suppress("unused")
    fun clearVibrationDevice(index : Int) {
        vibrators[index]?.cancel()
    }

    @Suppress("unused")
    fun showKeyboard(buffer : ByteBuffer, initialText : String) : SoftwareKeyboardDialog? {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val config = ByteBufferSerializable.createFromByteBuffer(SoftwareKeyboardConfig::class, buffer) as SoftwareKeyboardConfig

        val keyboardDialog = SoftwareKeyboardDialog.newInstance(config, initialText)
        runOnUiThread {
            val transaction = supportFragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction
                .add(android.R.id.content, keyboardDialog)
                .addToBackStack(null)
                .commit()
        }
        return keyboardDialog
    }

    @Suppress("unused")
    fun waitForSubmitOrCancel(dialog : SoftwareKeyboardDialog) : Array<Any?> {
        return dialog.waitForSubmitOrCancel().let { arrayOf(if (it.cancelled) 1 else 0, it.text) }
    }

    @Suppress("unused")
    fun closeKeyboard(dialog : SoftwareKeyboardDialog) {
        runOnUiThread { dialog.dismiss() }
    }

    @Suppress("unused")
    fun showValidationResult(dialog : SoftwareKeyboardDialog, validationResult : Int, message : String) : Int {
        val confirm = validationResult == SoftwareKeyboardDialog.validationConfirm
        var accepted = false
        val validatorResult = FutureTask { return@FutureTask accepted }
        runOnUiThread {
            val builder = MaterialAlertDialogBuilder(dialog.requireContext())
            builder.setMessage(message)
            builder.setPositiveButton(if (confirm) getString(android.R.string.ok) else getString(android.R.string.cancel)) { _, _ -> accepted = confirm }
            if (confirm)
                builder.setNegativeButton(getString(android.R.string.cancel)) { _, _ -> }
            builder.setOnDismissListener { validatorResult.run() }
            builder.show()
        }
        return if (validatorResult.get()) 0 else 1
    }

    /**
     * @return A version code in Vulkan's format with 14-bit patch + 10-bit major and minor components
     */
    @ExperimentalUnsignedTypes
    @Suppress("unused")
    fun getVersionCode() : Int {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split('-')[0].split('.').map { it.toUInt() }
        return ((major shl 22) or (minor shl 12) or (patch)).toInt()
    }

    private val insetsOrMarginHandler = View.OnApplyWindowInsetsListener { view, insets ->
        insets.displayCutout?.let {
            val defaultHorizontalMargin = view.resources.getDimensionPixelSize(R.dimen.onScreenItemHorizontalMargin)
            val left = if (it.safeInsetLeft == 0) defaultHorizontalMargin else it.safeInsetLeft
            val right = if (it.safeInsetRight == 0) defaultHorizontalMargin else it.safeInsetRight

            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.updateMargins(left = left, right = right)
            view.layoutParams = params
        }
        insets
    }

    override fun onDisplayChanged(displayId : Int) {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!! else windowManager.defaultDisplay
        if (display.displayId == displayId)
            force60HzRefreshRate(!preferenceSettings.maxRefreshRate)
    }

    override fun onDisplayAdded(displayId : Int) {}

    override fun onDisplayRemoved(displayId : Int) {}
}
