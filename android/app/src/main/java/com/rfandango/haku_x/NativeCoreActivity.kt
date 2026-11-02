package com.rfandango.haku_x

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.documentfile.provider.DocumentFile
import com.rfandango.haku_x.data.AppPrefs
import com.rfandango.haku_x.data.CheatInfo
import com.rfandango.haku_x.data.Controllers
import com.rfandango.haku_x.data.GameOverrides
import com.rfandango.haku_x.data.Logs
import com.rfandango.haku_x.data.ModCatalog
import com.rfandango.haku_x.data.ModChoices
import com.rfandango.haku_x.data.ModRun
import com.rfandango.haku_x.data.Network
import com.rfandango.haku_x.data.PlayerSlot
import com.rfandango.haku_x.data.RunSettings
import com.rfandango.haku_x.data.SaveStateStore
import com.rfandango.haku_x.data.XboxDisc
import com.rfandango.haku_x.data.hdd.Dashboard
import com.rfandango.haku_x.input.PadRouter
import com.rfandango.haku_x.overlay.OverlayEditorBar
import com.rfandango.haku_x.ui.theme.AppTheme
import java.io.File
import java.util.TimeZone
import org.libsdl.app.SDLActivity

/**
 * The QEMU-free core: libhakuxcore.so (FEX JIT, reimplemented kernel and
 * devices, the HLE GPU) on SDL3, in its own process. SDL_main gets the disc as
 * a descriptor opened here and the core's hard disk folder in app storage.
 * The on-screen controller feeds the core directly; physical gamepads come
 * through SDL3.
 */
class NativeCoreActivity : SDLActivity(), InputManager.InputDeviceListener {
  private var discArg = ""
  private var onScreenController: OnScreenController? = null
  private var fpsView: TextView? = null
  private var hitchView: TextView? = null
  /* the settings' overlays, read as the game comes back (onResume) */
  private var fpsOn = false
  private var hitchesOn = false
  private val fpsHandler = Handler(Looper.getMainLooper())
  /** The overlays' redraws and the game's GPU work (the controls' and the lines below). */
  private val overlayPacer = OverlayPacer({ nativeGpuBusyMs() }, { nativeGpuGap() })
  /** The FPS and stutter lines' update waiting on overlayPacer since (uptime ms), 0: none. */
  private var linesWantedSince = 0L
  private val fpsRunnable = object : Runnable {
    override fun run() {
      if (linesWantedSince == 0L) {
        linesWantedSince = SystemClock.uptimeMillis()
      }
      if (!overlayPacer.mayDraw(linesWantedSince)) {
        fpsHandler.postDelayed(this, LINES_RETRY_MS)
        return
      }
      linesWantedSince = 0L
      val s = nativeGetFrameStats()
      if (fpsOn && s != null && s.size >= 3) {
        var text = "FPS: %.0f  |  %.1fms avg  /  %.1fms last".format(s[0], s[1], s[2])
        if (s[1] > 0f && s[2] > 2f * s[1]) {
          text += " *" /* the last frame took over twice the average */
        }
        fpsView?.text = text
      }
      hitchView?.takeIf { hitchesOn }?.let { v ->
        val hitches = nativeGetHleHitches()
        if (hitches.isNotEmpty()) {
          v.text = hitches
          if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        } else if (v.visibility != View.GONE) {
          v.visibility = View.GONE
        }
      }
      fpsHandler.postDelayed(this, FPS_INTERVAL_MS)
    }
  }

  /* A game that can't run here: the GPU driver lacks what the HLE GPU needs,
   * or the HLE GPU does not support the game. Say which and go back, instead
   * of a black screen. */
  private var fatalShown = false
  private val fatalRunnable = object : Runnable {
    override fun run() {
      val why = nativeGetFatal()
      if (why != null && !fatalShown) {
        fatalShown = true
        val kind = nativeGetFatalKind()
        val driver = kind == FATAL_DRIVER
        /* another driver can be chosen on Adreno and Mali (elsewhere the
         * system's); the advice that names Turnip is Adreno's alone */
        val custom = GpuDriverHelper.supportsCustomDriverLoading()
        val turnip = GpuDriverHelper.isAdreno()
        AlertDialog.Builder(this@NativeCoreActivity)
          .setTitle(
            when (kind) {
              FATAL_DRIVER -> R.string.native_fatal_title
              FATAL_STATE -> R.string.state_load_failed_title
              FATAL_GPU_LOST -> R.string.native_gpu_lost_title
              FATAL_BOOT -> R.string.native_boot_failed_title
              else -> R.string.native_incompatible_title
            }
          )
          .setMessage(
            when {
              driver -> getString(if (turnip) R.string.native_fatal_gpu else R.string.native_fatal_gpu_system, why)
              kind == FATAL_STATE -> why
              kind == FATAL_GPU_LOST ->
                getString(if (custom) R.string.native_gpu_lost else R.string.native_gpu_lost_system, why)
              kind == FATAL_BOOT -> getString(R.string.native_boot_failed, why)
              else -> getString(R.string.native_incompatible)
            }
          )
          .setCancelable(false)
          .setPositiveButton(android.R.string.ok) { _, _ -> exitGame() }
          .show()
        return
      }
      /* The title quick-rebooted into another of its XBEs (XLaunchNewImage:
       * Morrowind's New Game starts morrowind.xbe): the core lives for one
       * run, so it starts again in a new process with "relaunch". */
      if (nativeGetRelaunch()) {
        relaunchCore()
        return
      }
      /* The title left (to the dashboard, a reboot, a shutdown): the game
       * closes, as on a console. */
      if (nativeGetQuit()) {
        Log.i(TAG, "the title left: closing the game")
        exitGame()
        return
      }
      nativeTakeTwinConsole()?.let(::showTwinConsole)
      fpsHandler.postDelayed(this, FPS_INTERVAL_MS)
    }
  }

  /**
   * Another console on the network has this one's identity (the app's hard
   * disk copied from one device to the other: the same serial number and MAC
   * address), and neither sees the other's system link games. Said once a
   * run, with the way out: a new identity for this console (its HD key kept,
   * so its saves stay valid), from the game's start, at once.
   */
  private var twinShown = false

  private fun showTwinConsole(address: String) {
    if (twinShown) return
    twinShown = true
    Log.i(TAG, "network: the console at $address has this one's identity")
    showMenuDialog(
      AlertDialog.Builder(this)
        .setTitle(R.string.network_twin_title)
        .setMessage(getString(R.string.network_twin_message, address))
        .setPositiveButton(R.string.network_twin_renew) { _, _ -> renewIdentity() }
        .setNegativeButton(R.string.network_twin_later, null)
    )
  }

  /** The core gives the console a new identity as it next loads the EEPROM (native/kernel/ex.c eeprom_renew). */
  private fun renewIdentity() {
    val marker = File(hddDir(), "eeprom.renew")
    if (runCatching { marker.writeText("") }.isFailure) {
      Toast.makeText(this, R.string.network_twin_failed, Toast.LENGTH_LONG).show()
      return
    }
    Log.i(TAG, "network: a new identity asked; the game starts again")
    restartGame()
  }

  /** The emulated hard disk's folder (the core's <hdd>). */
  private fun hddDir() = File(filesDir, "native/hdd")

  /** The game from its start again, in a new core process (RelaunchActivity, no frame held). */
  private fun restartGame() {
    if (relaunchStarted) return
    relaunchStarted = true
    RelaunchActivity.heldFrame(this).delete()
    startActivity(
      Intent(this, RelaunchActivity::class.java)
        .putExtra(EXTRA_DVD_URI, discUri?.toString() ?: intent.getStringExtra(EXTRA_DVD_URI))
        .putExtra(EXTRA_RESTART, true)
        .putExtra(
          LauncherActivity.EXTRA_LAUNCHED_EXTERNALLY,
          intent.getBooleanExtra(LauncherActivity.EXTRA_LAUNCHED_EXTERNALLY, false),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
      RelaunchActivity.noTransition(this).toBundle(),
    )
    finish()
    RelaunchActivity.noCloseTransition(this)
    android.os.Process.killProcess(android.os.Process.myPid())
  }

  override fun getLibraries(): Array<String> = arrayOf("SDL3", "hakuxcore")

  override fun getArguments(): Array<String> {
    /* Controllers come through Android's input events (PadRouter), not SDL's
     * gamepad API. SDL's HIDAPI driver, on by default, claims a wired PlayStation
     * (or Xbox) controller over USB and detaches the kernel driver, so Android
     * drops the controller as an input device and the router never sees it.
     * Off: the controller stays an ordinary Android gamepad, wired or not.
     * (SDL reads a hint's environment variable when the hint isn't set.) */
    runCatching {
      SDLActivity.nativeSetenv("SDL_JOYSTICK_HIDAPI", "0")
      SDLActivity.nativeSetenv("SDL_JOYSTICK_HIDAPI_PS4", "0")
      SDLActivity.nativeSetenv("SDL_JOYSTICK_HIDAPI_PS5", "0")
      SDLActivity.nativeSetenv("SDL_JOYSTICK_HIDAPI_PS3", "0")
      SDLActivity.nativeSetenv("SDL_JOYSTICK_HIDAPI_XBOX", "0")
    }
    val args = mutableListOf(discArg, hddDir().absolutePath)
    /* the dashboard: C:\xboxdash.xbe with no disc (discArg "none") */
    if (dashboard) args += "dash"
    val state = intent.getStringExtra(EXTRA_LOADSTATE)
    if (state != null) {
      args += "loadstate=$state"
    } else if (intent.getBooleanExtra(EXTRA_RELAUNCH, false)) {
      args += "relaunch"
    }
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()?.let {
      args += "version=$it"
    }
    /* The app's settings, under this game's overrides. */
    val settings = RunSettings(AppPrefs(this), gameOverrides())
    /* Every frame presented at once, no vblank wait: on by default. */
    if (settings.uncapped) {
      args += "uncap"
    }
    /* The HLE GPU's render scale, 1-4x the title's resolution. */
    args += "scale=${settings.renderScale}"
    /* The picture's shape: auto (the game's), 4:3, 16:9 or stretch. */
    args += "aspect=${settings.aspectRatio}"
    /* Anti-aliasing: FXAA over each frame, and how a picture smaller than
     * the screen is scaled up (a larger one is averaged down). */
    args += "fxaa=${if (settings.fxaa) 1 else 0}"
    args += "upscale=${settings.upscaleFilter}"
    /* The console's video settings (widescreen, 480p/720p/1080i, the cable)
     * and its language. */
    args += "video=%x".format(settings.videoFlags())
    args += "avpack=${settings.avPack}"
    args += "lang=${settings.xboxLanguage()}"
    /* The console's clock (the phone's time, or as set in the settings) and
     * the phone's time zone. */
    AppPrefs(this).clockOffset?.let { args += "clock=$it" }
    args += "tz=${TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000}"
    /* Shaders compiled off the render thread (draws needing one skipped
     * meanwhile), and the ones cached built before the game starts. */
    args += "asyncpipe=${if (settings.asyncShaders) 1 else 0}"
    args += "prebuild=${if (settings.prebuildShaders) 1 else 0}"
    /* Reads take a quarter of the console's drives' time, their thread
     * waiting (the game's other threads run meanwhile, as on the console). */
    args += "devtime=${if (settings.consoleTiming) 1 else 0}"
    /* The console's network (native/net): system link with the consoles on
     * this network (their broadcasts reach it under the multicast lock), or
     * through an XLink Kai engine; the Internet through NAT, Insignia's DNS
     * server given by DHCP (Xbox Live); off, no cable. */
    val prefs = AppPrefs(this)
    when (prefs.networkMode) {
      AppPrefs.NET_LAN -> {
        args += "net=lan"
        Network.broadcastAddress(this)?.let { args += "lanbcast=$it" }
        prefs.lanPeers.replace(" ", "").takeIf { it.isNotEmpty() }?.let { args += "lanpeers=$it" }
      }
      AppPrefs.NET_KAI -> prefs.kaiEngine.trim().takeIf { it.isNotEmpty() }?.let {
        args += "net=kai"
        args += "kai=$it"
      }
      AppPrefs.NET_INSIGNIA -> {
        args += "net=nat"
        args += "dns=${prefs.insigniaDns.trim().ifEmpty { AppPrefs.INSIGNIA_DNS }}"
      }
    }
    /* The game's mods (their files and XBE patches) and cheats (mods/):
     * the manifest the core reads as the disc opens. */
    modsArg()?.let { args += it }
    /* The players with a controller plugged in at boot: player 1 and those a
     * connected controller plays as (later ones: syncPads). */
    plugged = Controllers.load(this).padMask(Controllers.connected())
    args += "pads=%x".format(plugged)
    /* The custom Vulkan driver installed in the settings (Turnip), loaded
     * by the core through adrenotools; none: the system driver. */
    GpuDriverHelper.init(this)
    val driverLib = GpuDriverHelper.getInstalledDriverLibrary()
    if (!driverLib.isNullOrEmpty() && GpuDriverHelper.supportsCustomDriverLoading()) {
      args += "vkdriver=${GpuDriverHelper.driverInstallDir}|$driverLib|${GpuDriverHelper.hookLibDir}"
    }
    return args.toTypedArray()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    /* the in-game menus (framework dialogs) light or dark, as the app's theme is */
    if (AppTheme.isDark(this)) {
      setTheme(R.style.Theme_HakuX_Game_Dark)
    }
    discArg = openDisc()
    Logs.startGame(this, gameName(), when {
      intent.getBooleanExtra(EXTRA_RELAUNCH, false) -> "relaunched into another XBE"
      intent.hasExtra(EXTRA_LOADSTATE) -> "loading a savestate"
      else -> "started"
    })
    super.onCreate(savedInstanceState)
    if (AppPrefs(this).networkMode == AppPrefs.NET_LAN) {
      multicastLock = Network.multicastLock(this)
    }
    setupOnScreenController()
    setupFpsOverlay()
    setupShaderBuildLine()
    if (intent.getBooleanExtra(EXTRA_RELAUNCH, false) || intent.hasExtra(EXTRA_LOADSTATE)) {
      showHeldFrame(intent.getStringExtra(EXTRA_LOADSTATE))
    }
    router = PadRouter(
      this,
      send = { p, o -> nativeSetPad(p, o.buttons, o.packedAnalog(), o.lx, o.ly, o.rx, o.ry) },
      rumbleOf = { p -> nativeGetRumble(p) },
    ).also { it.reassign() }
    (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
    }
  }

  /**
   * The emulation stops while the activity is paused (the device suspends,
   * another app in front) or the menu is open, and goes on where it stopped
   * when neither is (nativeSetPaused).
   */
  private var activityPaused = false
  private var menuDialogs = 0
  private var nativePaused: Boolean? = null

  private fun syncPause() {
    val want = activityPaused || menuDialogs > 0
    if (nativePaused != want) {
      nativePaused = want
      nativeSetPaused(want)
    }
  }

  /**
   * A dialog of the menu (the menu, Controllers, a player's controller): the
   * emulation stops while one is open. The next one opens before the one
   * chosen from closes, so the game stays stopped from one to the next.
   */
  private fun showMenuDialog(b: AlertDialog.Builder): AlertDialog {
    menuDialogs++
    syncPause()
    b.setOnDismissListener {
      menuDialogs--
      syncPause()
    }
    return b.show()
  }

  /** Savestates (push-buffer mode): the menu's Save state and Load state. */
  private val saveStates by lazy {
    SaveStates(
      this,
      gameKey(),
      discUri?.toString() ?: intent.getStringExtra(EXTRA_DVD_URI),
      ::showMenuDialog,
      ::captureFrame,
    )
  }

  /** debug: debug.hakux.state=save:<slot> / load:<slot>, as the menu's (scripted runs). */
  private val stateRequestRunnable = object : Runnable {
    override fun run() {
      nativeTakeStateRequest()?.let { req ->
        val slot = req.substringAfter(':').toIntOrNull()
        if (slot != null && slot in 1..SaveStateStore.SLOTS && nativeStatesAvailable()) {
          when (req.substringBefore(':')) {
            "save" -> saveStates.save(slot)
            "load" -> if (saveStates.state(slot).exists()) saveStates.load(slot)
          }
        }
      }
      fpsHandler.postDelayed(this, STATE_POLL_MS)
    }
  }

  /** The frame on screen, copied from the game's surface (null: it couldn't be). */
  private fun captureFrame(done: (Bitmap?) -> Unit) {
    val surface = mSurface
    if (surface == null || surface.width <= 0 || surface.height <= 0) {
      done(null)
      return
    }
    val frame = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(surface, frame, { result -> done(if (result == PixelCopy.SUCCESS) frame else null) }, fpsHandler)
    } catch (e: IllegalArgumentException) {
      done(null)
    }
  }

  /** Starts the core again into the savestate at path (SaveStates' Load state). */
  fun restartInto(path: String) {
    if (relaunchStarted) return
    relaunchStarted = true
    Log.i(TAG, "savestate: starting the core again into $path")
    val uri = discUri?.toString() ?: intent.getStringExtra(EXTRA_DVD_URI)
    startActivity(
      Intent(this, RelaunchActivity::class.java)
        .putExtra(EXTRA_DVD_URI, uri)
        .putExtra(EXTRA_LOADSTATE, path)
        .putExtra(
          LauncherActivity.EXTRA_LAUNCHED_EXTERNALLY,
          intent.getBooleanExtra(LauncherActivity.EXTRA_LAUNCHED_EXTERNALLY, false),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
      RelaunchActivity.noTransition(this).toBundle(),
    )
    finish()
    RelaunchActivity.noCloseTransition(this)
    android.os.Process.killProcess(android.os.Process.myPid())
  }

  override fun onResume() {
    super.onResume()
    activityPaused = false
    syncPause() /* where it stopped (onPause), unless the menu is open */
    hideSystemBars()
    val prefs = getSharedPreferences("x1box_prefs", Context.MODE_PRIVATE)
    fpsOn = prefs.getBoolean("show_fps", true)
    hitchesOn = BuildConfig.DEBUG && prefs.getBoolean(PREF_SHOW_HITCHES, true)
    fpsView?.visibility = if (fpsOn) View.VISIBLE else View.GONE
    if (!hitchesOn) hitchView?.visibility = View.GONE
    fpsHandler.removeCallbacks(fpsRunnable)
    if (fpsOn || hitchesOn) {
      fpsHandler.postDelayed(fpsRunnable, FPS_INTERVAL_MS)
    }
    fpsHandler.removeCallbacks(fatalRunnable)
    if (!fatalShown) {
      fpsHandler.postDelayed(fatalRunnable, FPS_INTERVAL_MS)
    }
    fpsHandler.removeCallbacks(stateRequestRunnable)
    if (BuildConfig.DEBUG) {
      fpsHandler.postDelayed(stateRequestRunnable, STATE_POLL_MS)
    }
    router?.let {
      it.reload()
      fpsHandler.removeCallbacks(rumbleRunnable)
      fpsHandler.postDelayed(rumbleRunnable, RUMBLE_INTERVAL_MS)
    }
  }

  /**
   * The status and navigation bars stay hidden (a swipe shows them for a
   * while). SDL hides them only once its window is fullscreen; started again
   * after a relaunch, the activity got focus before that and the status bar
   * stayed over the top of the game (and the FPS line).
   */
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) hideSystemBars()
  }

  /** SDL's windo