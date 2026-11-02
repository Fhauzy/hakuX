package com.rfandango.haku_x.data

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The physical controllers: which controller plays as which of the Xbox's
 * four players, and each controller's mapping onto the Xbox controller (the
 * Duke). The core's activity routes every controller's events through its
 * mapping to its player (NativeCoreActivity); the settings edit them.
 *
 * Stored in one JSON file both processes read afresh (the game runs in its
 * own process; shared preferences would serve each its cached copy), written
 * whole and renamed into place.
 */

/** The Duke's inputs, as a mapping binds them. */
enum class XInput(val stick: Boolean = false) {
  A, B, X, Y, BLACK, WHITE, START, BACK, LTHUMB, RTHUMB,
  DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, LT, RT,
  LS_X(true), LS_Y(true), RS_X(true), RS_Y(true),
}

/** One source of an input: a key, or one half of an axis (dir +1 / -1; a stick input's direction). */
sealed class Binding {
  data class Key(val code: Int) : Binding()
  data class Axis(val axis: Int, val dir: Int) : Binding()

  fun encode(): String = when (this) {
    is Key -> "k$code"
    is Axis -> "a$axis${if (dir < 0) "-" else "+"}"
  }

  companion object {
    fun decode(s: String): Binding? = when {
      s.startsWith("k") -> s.drop(1).toIntOrNull()?.let { Key(it) }
      s.startsWith("a") && s.length > 2 ->
        s.substring(1, s.length - 1).toIntOrNull()?.let { Axis(it, if (s.last() == '-') -1 else 1) }
      else -> null
    }
  }
}

/** A controller's mapping: up to two bindings per input, the sticks' dead zones, the press threshold of an axis bound to a button. */
data class PadProfile(
  val bind: Map<XInput, List<Binding>>,
  val deadzoneL: Float = 0f,
  val deadzoneR: Float = 0f,
  val threshold: Float = 0.5f,
) {
  fun toJson(): JSONObject = JSONObject().apply {
    put("deadzoneL", deadzoneL.toDouble())
    put("deadzoneR", deadzoneR.toDouble())
    put("threshold", threshold.toDouble())
    put("bind", JSONObject().apply {
      for ((input, list) in bind) put(input.name, JSONArray(list.map { it.encode() }))
    })
  }

  /** A and B, X and Y trade places (a controller laid out the other way round). */
  fun swapFaceButtons(): PadProfile {
    val m = bind.toMutableMap()
    fun swap(a: XInput, b: XInput) {
      val ba = m[a]
      val bb = m[b]
      if (bb != null) m[a] = bb else m.remove(a)
      if (ba != null) m[b] = ba else m.remove(b)
    }
    swap(XInput.A, XInput.B)
    swap(XInput.X, XInput.Y)
    return copy(bind = m)
  }

  companion object {
    fun fromJson(o: JSONObject): PadProfile {
      val b = o.optJSONObject("bind") ?: JSONObject()
      val bind = mutableMapOf<XInput, List<Binding>>()
      for (input in XInput.entries) {
        val arr = b.optJSONArray(input.name) ?: continue
        bind[input] = (0 until arr.length()).mapNotNull { Binding.decode(arr.optString(it)) }
      }
      return PadProfile(
        bind,
        o.optDouble("deadzoneL", 0.0).toFloat(),
        o.optDouble("deadzoneR", 0.0).toFloat(),
        o.optDouble("threshold", 0.5).toFloat(),
      )
    }
  }
}

/** A player's controller: the next connected one (AUTO), a chosen one, or none (OFF: no controller plugged in for that player). */
data class PlayerSlot(val mode: String = AUTO, val key: String = "", val name: String = "") {
  companion object {
    const val AUTO = "auto"
    const val DEVICE = "device"
    const val OFF = "off"
  }
}

/** The Duke's state, as the core takes it (NativeCoreActivity.nativeSetPad). */
class PadOut {
  var buttons = 0
  val analog = IntArray(8)    /* A, B, X, Y, black, white, left trigger, right trigger: 0-255 */
  var lx = 0
  var ly = 0
  var rx = 0
  var ry = 0

  fun packedAnalog(): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((analog[i].coerceIn(0, 255).toLong()) shl (8 * i))
    return v
  }

  fun active(input: XInput): Boolean = when (input) {
    XInput.A -> analog[0] > 0
    XInput.B -> analog[1] > 0
    XInput.X -> analog[2] > 0
    XInput.Y -> analog[3] > 0
    XInput.BLACK -> analog[4] > 0
    XInput.WHITE -> analog[5] > 0
    XInput.LT -> analog[6] > 0
    XInput.RT -> analog[7] > 0
    XInput.LS_X -> lx != 0
    XInput.LS_Y -> ly != 0
    XInput.RS_X -> rx != 0
    XInput.RS_Y -> ry != 0
    else -> buttons and (DIGITAL[input] ?: 0) != 0
  }

  companion object {
    /* NC_PAD_* (native/dev/usb.h) */
    val DIGITAL = mapOf(
      XInput.DPAD_UP to 0x01, XInput.DPAD_DOWN to 0x02, XInput.DPAD_LEFT to 0x04, XInput.DPAD_RIGHT to 0x08,
      XInput.START to 0x10, XInput.BACK to 0x20, XInput.LTHUMB to 0x40, XInput.RTHUMB to 0x80,
    )
    val ANALOG = listOf(XInput.A, XInput.B, XInput.X, XInput.Y, XInput.BLACK, XInput.WHITE, XInput.LT, XInput.RT)
  }
}

/** One controller's live state (keys down, axes) and its Duke through a profile. */
class PadState {
  val keys = HashSet<Int>()
  val axes = FloatArray(MotionEvent.AXIS_GENERIC_16 + 1)

  /** A key down or up: the state changed (a repeat does not). */
  fun onKey(e: KeyEvent): Boolean = when (e.action) {
    KeyEvent.ACTION_DOWN -> keys.add(e.keyCode)
    KeyEvent.ACTION_UP -> keys.remove(e.keyCode)
    else -> false
  }

  fun onMotion(e: MotionEvent) {
    for (a in axes.indices) axes[a] = e.getAxisValue(a)
  }

  private fun value(b: Binding): Float = when (b) {
    is Binding.Key -> if (b.code in keys) 1f else 0f
    is Binding.Axis -> if (b.axis in axes.indices) axes[b.axis] * b.dir else 0f
  }

  fun compute(p: PadProfile, out: PadOut = PadOut()): PadOut {
    out.buttons = 0
    out.analog.fill(0)
    for (input in XInput.entries) {
      val list = p.bind[input] ?: continue
      if (input.stick) {
        var v = 0f
        for (b in list) {
          val x = value(b)
          if (kotlin.math.abs(x) > kotlin.math.abs(v)) v = x
        }
        val dz = if (input == XInput.LS_X || input == XInput.LS_Y) p.deadzoneL else p.deadzoneR
        val m = kotlin.math.abs(v)
        v = if (m <= dz) 0f else kotlin.math.sign(v) * (m - dz) / (1f - dz)
        val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        when (input) {
          XInput.LS_X -> out.lx = s
          XInput.LS_Y -> out.ly = s
          XInput.RS_X -> out.rx = s
          else -> out.ry = s
        }
        continue
      }
      var v = 0f
      for (b in list) v = maxOf(v, value(b))
      val bit = PadOut.DIGITAL[input]
      if (bit != null) {
        if (v >= p.threshold) out.buttons = out.buttons or bit
      } else {
        val i = PadOut.ANALOG.indexOf(input)
        /* a key fully pressed; an axis bound to a face button, its pressure */
        out.analog[i] = maxOf(out.analog[i], (v.coerceIn(0f, 1f) * 255f).toInt())
      }
    }
    return out
  }
}

object Controllers {
  const val PLAYERS = 4
  private const val FILE = "controllers.json"

  /**
   * A real controller: gamepad or joystick sources with stick axes. Phones
   * have built-in devices that claim gamepad keys without being one (the
   * Xiaomi 17's uinput-xiaomi reports KEYBOARD | GAMEPAD).
   */
  fun isGameController(d: InputDevice?): Boolean {
    if (d == null || d.isVirtual) return false
    val name = d.name?.lowercase() ?: ""
    if (listOf("accelerometer", "gyroscope", "sensor", "gpio", "uinput").any { it in name }) return false
    val gamepad = (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    val joystick = (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    if (!gamepad && !joystick) return false
    /* Analog sticks, or at least a hat (a pad in digital mode, or a PlayStation-style
     * clone, reports only the d-pad as a hat). */
    val sticks = d.getMotionRange(MotionEvent.AXIS_X) != null && d.getMotionRange(MotionEvent.AXIS_Y) != null
    val hat = d.getMotionRange(MotionEvent.AXIS_HAT_X) != null && d.getMotionRange(MotionEvent.AXIS_HAT_Y) != null
    return sticks || hat
  }

  /** The connected controllers, by device id. */
  fun connected(): List<InputDevice> =
    InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { isGameController(it) }

  /**
   * A controller's model (its mapping by default): vendor, product, name.
   * Android's descriptor tells units apart only when they report a unique id
   * (Bluetooth); identical wired controllers get it by the order they were
   * connected in, so a mapping of one unit can move to the other.
   */
  fun modelKey(d: InputDevice) = "m:%04x:%04x:%s".format(d.vendorId, d.productId, d.name ?: "")
  fun unitKey(d: InputDevice) = "u:${d.descriptor}"

  /**
   * A controller's mapping when none is saved: Android's standard gamepad
   * keys, and the axes the device has. The right stick is Z / RZ on most
   * Android controllers (the RP5's own), RX / RY on others; the triggers
   * LTRIGGER / RTRIGGER, else BRAKE / GAS; the d-pad a hat or keys. As the
   * SDL gamepad the core read before: the shoulders are white and black.
   * Back is the select button, and the back key only on a controller without
   * one (whose select sends it): a controller with both (the RP5's own Back,
   * beside its select) keeps its back key for the menu (NativeCoreActivity).
   */
  fun defaults(d: InputDevice?): PadProfile {
    fun has(axis: Int) = d?.getMotionRange(axis) != null
    fun signed(axis: Int) = (d?.getMotionRange(axis)?.min ?: 0f) < 0f
    val (rsx, rsy) = when {
      has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ) && signed(MotionEvent.AXIS_Z) ->
        MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
      else -> MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
    }
    fun trigger(main: Int, alt: Int, key: Int): List<Binding> = buildList {
      when {
        has(main) -> add(Binding.Axis(main, 1))
        has(alt) -> add(Binding.Axis(alt, 1))
      }
      add(Binding.Key(key))
    }
    fun key(vararg codes: Int) = codes.map { Binding.Key(it) }
    val hat = has(MotionEvent.AXIS_HAT_X)
    val select = d?.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)?.firstOrNull() == true
    fun dpad(axis: Int, dir: Int, code: Int) =
      if (hat) listOf(Binding.Axis(axis, dir), Binding.Key(code)) else key(code)
    return PadProfile(
      mapOf(
        XInput.A to key(KeyEvent.KEYCODE_BUTTON_A),
        XInput.B to key(KeyEvent.KEYCODE_BUTTON_B),
        XInput.X to key(KeyEvent.KEYCODE_BUTTON_X),
        XInput.Y to key(KeyEvent.KEYCODE_BUTTON_Y),
        XInput.BLACK to key(KeyEvent.KEYCODE_BUTTON_R1),
        XInput.WHITE to key(KeyEvent.KEYCODE_BUTTON_L1),
        XInput.START to key(KeyEvent.KEYCODE_BUTTON_START),
        XInput.BACK to if (select) key(KeyEvent.KEYCODE_BUTTON_SELECT)
                       else key(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK),
        XInput.LTHUMB to key(KeyEvent.KEYCODE_BUTTON_THUMBL),
        XInput.RTHUMB to key(KeyEvent.KEYCODE_BUTTON_THUMBR),
        XInput.DPAD_UP to dpad(MotionEvent.AXIS_HAT_Y, -1, KeyEvent.KEYCODE_DPAD_UP),
        XInput.DPAD_DOWN to dpad(MotionEvent.AXIS_HAT_Y, 1, KeyEvent.KEYCODE_DPAD_DOWN),
        XInput.DPAD_LEFT to dpad(MotionEvent.AXIS_HAT_X, -1, KeyEvent.KEYCODE_DPAD_LEFT),
        XInput.DPAD_RIGHT to dpad(MotionEvent.AXIS_HAT_X, 1, KeyEvent.KEYCODE_DPAD_RIGHT),
        XInput.LT to trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, KeyEvent.KEYCODE_BUTTON_L2),
        XInput.RT to trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, KeyEvent.KEYCODE_BUTTON_R2),
        /* Android's Y axes point down, the Duke's up */
        XInput.LS_X to listOf(Binding.Axis(MotionEvent.AXIS_X, 1)),
        XInput.LS_Y to listOf(Binding.Axis(MotionEvent.AXIS_Y, -1)),
        XInput.RS_X to listOf(Binding.Axis(rsx, 1)),
        XInput.RS_Y to listOf(Binding.Axis(rsy, -1)),
      ),
    )
  }

  /** The whole configuration, as stored. */
  data class Config(
    val slots: List<PlayerSlot> = List(PLAYERS) { PlayerSlot() },
    val profiles: Map<String, PadProfile> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
  ) {
    /** A controller's mapping: its own, else its model's, else the defaults. */
    fun profileFor(d: InputDevice): PadProfile =
      profiles[unitKey(d)] ?: profiles[modelKey(d)] ?: defaults(d)

    /** Whether the controller has a mapping of its own, apart from its model's. */
    fun hasUnitProfile(d: InputDevice) = profiles.containsKey(unitKey(d))

    /**
     * The players with a controller plugged in (bit n: player n + 1): player
     * 1's always (the on-screen controls play as it), the others' while a
     * connected controller plays as them (assign), as on the console, where
     * a port with no one at it has nothing plugged in.
     */
    fun padMask(devices: List<InputDevice>): Int {
      var m = 1
      for (p in assign(devices).values) m = m or (1 shl p)
      return m
    }

    /**
     * Which controller plays as which player: chosen controllers first, then
     * the AUTO players take the remaining ones in device order. device id ->
     * player.
     */
    fun assign(devices: List<InputDevice>): Map<Int, Int> {
      val out = mutableMapOf<Int, Int>()
      val free = devices.toMutableList()
      for ((p, s) in slots.withIndex()) {
        if (s.mode != PlayerSlot.DEVICE) continue
        val d = free.firstOrNull { unitKey(it) == s.key } ?: continue
        out[d.id] = p
        free.remove(d)
      }
      for ((p, s) in slots.withIndex()) {
        if (s.mode != PlayerSlot.AUTO || p in out.values) continue
        val d = free.firstOrNull() ?: break
        out[d.id] = p
        free.remove(d)
      }
      return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
      put("slots", JSONArray(slots.map {
        JSONObject().put("mode", it.mode).put("key", it.key).put("name", it.name)
      }))
      put("profiles", JSONObject().apply { for ((k, v) in profiles) put(k, v.toJson()) })
      put("names", JSONObject().apply { for ((k, v) in names) put(k, v) })
    }
  }

  fun load(context: Context): Config {
    val f = File(context.filesDir, FILE)
    if (!f.exists()) return Config()
    return try {
      val o = JSONObject(f.readText())
      val sa = o.optJSONArray("slots")
      val slots = List(PLAYERS) { i ->
        val s = sa?.optJSONObject(i)
        if (s == null) PlayerSlot() else PlayerSlot(s.optString("mode", PlayerSlot.AUTO), s.optString("key"), s.optString("name"))
      }
      val po = o.optJSONObject("profiles") ?: JSONObject()
      val profiles = po.keys().asSequence().associateWith { PadProfile.fromJson(po.getJSONObject(it)) }
      val no = o.optJSONObject("names") ?: JSONObject()
      val names = no.keys().asSequence().associateWith { no.getString(it) }
      Config(slots, profiles, names)
    } catch (_: Exception) {
      Config()
    }
  }

  fun save(context: Context, c: Config) {
    val f = File(context.filesDir, FILE)
    val tmp = File(context.filesDir, "$FILE.tmp")
    tmp.writeText(c.toJson().toString())
    tmp.renameTo(f)
  }

  /** A binding as the settings show it. */
  fun label(b: Binding): String = when (b) {
    is Binding.Key -> KeyEvent.keyCodeToString(b.code).removePrefix("KEYCODE_").replace("BUTTON_", "")
    is Binding.Axis -> MotionEvent.axisToString(b.axis).removePrefix("AXIS_") + if (b.dir < 0) " −" else " +"
  }
}
