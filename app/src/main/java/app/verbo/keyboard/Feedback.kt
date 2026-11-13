package app.verbo.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Key sound and vibration. Used by the keyboard on every key, and by the settings screen for previews. */
class Feedback(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var soundOn = true
    private var hapticOn = true
    private var pack = "system"
    private var volume = 0.6f
    private var strength = "medium"

    private var pool: SoundPool? = null
    private var loadedPack = ""
    private var keySound = 0
    private var enterSound = 0

    /** True while the phone's Battery Saver is on and the user lets Verbo follow it. */
    var saving = false
        private set

    fun configure(store: Store) {
        saving = store.batterySaver && power?.isPowerSaveMode == true
        soundOn = store.sound && !saving
        hapticOn = store.haptic && !saving
        pack = store.soundPack
        volume = store.soundVolume / 100f
        strength = store.hapticStrength
        if (soundOn && pack != "system") load() else release()
    }

    private fun load() {
        if (loadedPack == pack && pool != null) return
        release()
        val res = PACK_RES[pack] ?: return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        keySound = p.load(context, res, 1)
        enterSound = if (pack == "typewriter") p.load(context, R.raw.snd_typewriter_bell, 1) else 0
        pool = p
        loadedPack = pack
    }

    /** Frees the audio resources; they are loaded again the next time the keyboard is configured. */
    fun release() {
        pool?.release()
        pool = null
        loadedPack = ""
    }

    fun press(type: KeyboardView.Type) {
        if (hapticOn) vibrate()
        if (!soundOn) return
        val p = pool
        if (p == null) {
            audio?.playSoundEffect(
                when (type) {
                    KeyboardView.Type.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                    KeyboardView.Type.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    KeyboardView.Type.BAR -> AudioManager.FX_KEYPRESS_SPACEBAR
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }, volume,
            )
            return
        }
        // One sample per pack; the bigger keys play it lower so they sound heavier.
        if (type == KeyboardView.Type.ENTER && enterSound != 0) {
            p.play(enterSound, volume * 0.7f, volume * 0.7f, 1, 0, 1f)
            return
        }
        val rate = when (type) {
            KeyboardView.Type.BAR -> 0.8f
            KeyboardView.Type.ENTER -> 0.72f
            KeyboardView.Type.BACKSPACE -> 1.18f
            KeyboardView.Type.CHAR -> 1f
            else -> 0.9f
        }
        p.play(keySound, volume, volume, 1, 0, rate)
    }

    private fun vibrate() {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val (ms, amplitude, predefined) = when (strength) {
                "light" -> Triple(10L, 70, 2 /* EFFECT_TICK */)
                "strong" -> Triple(32L, 255, 5 /* EFFECT_HEAVY_CLICK */)
                else -> Triple(20L, 170, 0 /* EFFECT_CLICK */)
            }
            // Phones that can set the motor's strength get an exact pulse; others get the nearest built-in effect.
            v.vibrate(
                if (v.hasAmplitudeControl() || Build.VERSION.SDK_INT < 29) VibrationEffect.createOneShot(ms, amplitude)
                else VibrationEffect.createPredefined(predefined),
            )
        } catch (e: Exception) {
            // Some devices refuse vibration in certain modes; typing must carry on regardless.
        }
    }

    companion object {
        val PACKS = listOf(
            "system" to "Phone default", "typewriter" to "Typewriter", "mechanical" to "Mechanical keys",
            "pop" to "Pop", "drop" to "Water drop", "soft" to "Soft tap",
        )
        private val PACK_RES = mapOf(
            "typewriter" to R.raw.snd_typewriter, "mechanical" to R.raw.snd_mechanical,
            "pop" to R.raw.snd_pop, "drop" to R.raw.snd_drop, "soft" to R.raw.snd_soft,
        )
        val STRENGTHS = listOf("light" to "Light", "medium" to "Medium", "strong" to "Strong")
    }
}
