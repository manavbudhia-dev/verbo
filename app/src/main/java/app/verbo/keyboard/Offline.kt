package app.verbo.keyboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Small jobs done on the phone, with no request and no cost: fixing spelling and tone with Gemini Nano
 * (on phones that have it, through ML Kit GenAI) and translating with ML Kit's downloadable models.
 * Every call blocks, so run it on a worker thread. Null means "can't do it here", and the cloud is used.
 */
object Offline {
    /** The on-device jobs, matched from the task the keyboard was about to send. */
    enum class Kind { PROOFREAD, SHORTEN, PROFESSIONAL, FRIENDLY, REPHRASE }

    fun kindFor(task: String?): Kind? {
        if (task == null) return null
        fun skill(name: String) = Store.DEFAULT_SKILLS.firstOrNull { it.name == name }?.let { Ai.skillTask(it.instruction) }
        return when (task) {
            skill("Polish") -> Kind.PROOFREAD
            skill("Shorter") -> Kind.SHORTEN
            skill("Formal") -> Kind.PROFESSIONAL
            skill("Casual") -> Kind.FRIENDLY
            else -> null
        }
    }

    /** "Task: translate the text into Hindi. Return only the translation…" -> "Hindi". Single-language only. */
    fun translateTarget(task: String?): String? =
        task?.let { Regex("""^Task: translate the text into ([^,.]+)\. Return only the translation""").find(it)?.groupValues?.get(1) }

    fun online(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { true }

    /** Does the job on the phone, or returns null. Only English text is attempted for Gemini Nano jobs. */
    fun run(context: Context, kind: Kind, text: String): String? = if (!english(text)) null else try {
        if (kind == Kind.PROOFREAD) {
            val client = Proofreading.getClient(ProofreaderOptions.builder(context)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD).setLanguage(ProofreaderOptions.Language.ENGLISH).build())
            try {
                if (client.checkFeatureStatus().get(5, TimeUnit.SECONDS) != FeatureStatus.AVAILABLE) null
                else client.runInference(ProofreadingRequest.builder(text).build()).get(20, TimeUnit.SECONDS).results.firstOrNull()?.text
            } finally { client.close() }
        } else {
            val type = when (kind) {
                Kind.SHORTEN -> RewriterOptions.OutputType.SHORTEN
                Kind.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
                Kind.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
                else -> RewriterOptions.OutputType.REPHRASE
            }
            val client = Rewriting.getClient(RewriterOptions.builder(context).setOutputType(type).setLanguage(RewriterOptions.Language.ENGLISH).build())
            try {
                if (client.checkFeatureStatus().get(5, TimeUnit.SECONDS) != FeatureStatus.AVAILABLE) null
                else client.runInference(RewritingRequest.builder(text).build()).get(20, TimeUnit.SECONDS).results.firstOrNull()?.text
            } finally { client.close() }
        }
    } catch (e: Throwable) { null }?.trim()?.ifEmpty { null }

    /** Gemini Nano's proofreading and rewriting are English-only here; Hinglish and other scripts go to the AI service. */
    fun english(text: String) = text.none { it.code > 0x24F && it.isLetter() } && Memory.hinglishShare(text) < 0.12

    /** ML Kit's code for a language name from the keyboard's list ("Chinese (Simplified)" -> "zh"). */
    fun languageCode(name: String): String? {
        val base = name.substringBefore(" (").trim()
        return TranslateLanguage.getAllLanguages().firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(base, true) }
    }

    /** Translates on the phone; downloads the language pair the first time (about 30 MB each), on Wi-Fi or mobile data. */
    fun translate(text: String, target: String, allowDownload: Boolean): String? = try {
        val to = languageCode(target) ?: return null
        val from = if (text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.DEVANAGARI }) TranslateLanguage.HINDI else TranslateLanguage.ENGLISH
        if (from == to) null else {
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
            try {
                if (allowDownload) Tasks.await(translator.downloadModelIfNeeded(), 120, TimeUnit.SECONDS)
                Tasks.await(translator.translate(text), 20, TimeUnit.SECONDS)
            } finally { translator.close() }
        }
    } catch (e: Throwable) { null }

    /** One line for the settings page: whether Gemini Nano can run here. */
    fun status(context: Context): String = try {
        val client = Proofreading.getClient(ProofreaderOptions.builder(context)
            .setInputType(ProofreaderOptions.InputType.KEYBOARD).setLanguage(ProofreaderOptions.Language.ENGLISH).build())
        try {
            when (client.checkFeatureStatus().get(5, TimeUnit.SECONDS)) {
                FeatureStatus.AVAILABLE -> "Gemini Nano is ready on this phone"
                FeatureStatus.DOWNLOADABLE -> "Gemini Nano can be downloaded on this phone"
                FeatureStatus.DOWNLOADING -> "Gemini Nano is downloading"
                else -> "This phone has no Gemini Nano; translation still works offline"
            }
        } finally { client.close() }
    } catch (e: Throwable) { "This phone has no Gemini Nano; translation still works offline" }

    /** Starts the downloads: Gemini Nano (where offered) and translation for the chosen languages. */
    fun download(context: Context, languages: List<String>, done: (String) -> Unit) {
        Thread {
            var nano = "Gemini Nano isn't offered on this phone"
            try {
                val client = Proofreading.getClient(ProofreaderOptions.builder(context)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD).setLanguage(ProofreaderOptions.Language.ENGLISH).build())
                val status = client.checkFeatureStatus().get(10, TimeUnit.SECONDS)
                if (status == FeatureStatus.AVAILABLE) nano = "Gemini Nano is ready"
                else if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                    client.downloadFeature(object : DownloadCallback {
                        override fun onDownloadStarted(bytesToDownload: Long) {}
                        override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                        override fun onDownloadCompleted() {}
                        override fun onDownloadFailed(e: GenAiException) {}
                    }).get(10, TimeUnit.MINUTES)
                    nano = "Gemini Nano downloaded"
                }
            } catch (e: Throwable) { }
            val got = languages.filter { translate("hello", it, allowDownload = true) != null }
            done("$nano. Offline translation: ${if (got.isEmpty()) "none yet" else got.joinToString(", ")}")
        }.start()
    }
}

/** Token use and cost of each request, per month, so the user can see what Verbo spends and cap it. */
object Meter {
    private val current = ThreadLocal<LongArray>()

    fun begin() = current.set(LongArray(3))
    /** Called by each service after every response: input tokens, output tokens, web searches. */
    fun add(input: Long, output: Long, searches: Long = 0) {
        current.get()?.let { it[0] += input; it[1] += output; it[2] += searches }
    }
    fun end(): LongArray = current.get().also { current.remove() } ?: LongArray(3)

    /** US dollars per million tokens (input, output) for a model, or null when the price isn't known. */
    fun price(model: String): Pair<Double, Double>? = when {
        model.contains("haiku-5-5") || model.contains("haiku-5.5") -> 0.10 to 0.50
        model.contains("sonnet-5-5") || model.contains("sonnet-5.5") -> 2.0 to 10.0
        model.contains("opus-5-5") || model.contains("opus-5.5") -> 4.0 to 20.0
        else -> null
    }

    fun cost(model: String, input: Long, output: Long, searches: Long): Double {
        val p = price(model) ?: (0.0 to 0.0)
        return input / 1e6 * p.first + output / 1e6 * p.second + searches * 0.01
    }
}
