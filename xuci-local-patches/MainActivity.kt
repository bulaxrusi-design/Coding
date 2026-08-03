package com.example.llama

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * xuci1.0 Local Alpha.
 * No account, API key, paid endpoint or token billing is used.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var engine: InferenceEngine

    private val messages = mutableListOf<Message>()
    private val messageAdapter = MessageAdapter(messages)
    private val lastAssistantMsg = StringBuilder()
    private val prefs by lazy { getSharedPreferences("xuci_local_memory", MODE_PRIVATE) }

    private var generationJob: Job? = null
    private var isModelReady = false
    private var isPreparingModel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)

        addAssistant(
            "გამარჯობა. მე ვარ xuci1.0 Local — უფასო ლოკალური coding პარტნიორი. " +
                "პირველ გაშვებაზე ჩამოვტვირთავ დაახლოებით 429 MB მოდელს; შემდეგ API key და ინტერნეტი აღარ დამჭირდება."
        )

        userActionFab.setOnClickListener {
            when {
                isModelReady -> handleUserInput()
                !isPreparingModel && ::engine.isInitialized -> prepareLocalModel(false)
            }
        }

        lifecycleScope.launch {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                prepareLocalModel(false)
            } catch (t: Throwable) {
                Log.e(TAG, "Engine initialization failed", t)
                showFailure("ლოკალური ძრავა ვერ ჩაირთო: ${safeMessage(t)}")
            }
        }
    }

    private fun prepareLocalModel(forceDownload: Boolean) {
        if (isPreparingModel || !::engine.isInitialized) return
        isPreparingModel = true
        isModelReady = false
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelsDir = ensureModelsDirectory()
                val modelFile = File(modelsDir, MODEL_FILE_NAME)
                val marker = File(modelsDir, "$MODEL_FILE_NAME.ok")

                if (forceDownload) {
                    modelFile.delete()
                    marker.delete()
                }

                if (!modelFile.exists() || !marker.exists()) {
                    downloadModel(modelFile)
                    updateStatus("მოდელის მთლიანობის შემოწმება…")
                    val actual = sha256(modelFile)
                    check(actual.equals(MODEL_SHA256, ignoreCase = true)) {
                        modelFile.delete()
                        "SHA-256 არ ემთხვევა. მიღებულია $actual"
                    }
                    marker.writeText(actual)
                }

                updateStatus("მოდელის ჩატვირთვა RAM-ში…")
                modelFile.inputStream().buffered(BUFFER_SIZE).use { input ->
                    engine.loadModel(input)
                }

                withContext(Dispatchers.Main) {
                    isPreparingModel = false
                    isModelReady = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    statusTv.text = "LOCAL • Qwen2.5-Coder 0.5B Q4 • API key არ სჭირდება • მეხსიერება ჩართულია"
                    userInputEt.hint = "მკითხე Android, Python, APK ან სხვა რამ…"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    addAssistant("ტვინი მზადაა. პასუხები მთლიანად შენს ტელეფონზე გენერირდება.")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Model preparation failed", t)
                withContext(Dispatchers.Main) {
                    isPreparingModel = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    showFailure("მოდელის მომზადება ვერ დასრულდა: ${safeMessage(t)}")
                }
            }
        }
    }

    private suspend fun downloadModel(destination: File) {
        updateStatus("უფასო მოდელის ჩამოტვირთვა იწყება…")
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()

        var currentUrl = URL(MODEL_URL)
        var connection: HttpURLConnection? = null
        repeat(MAX_REDIRECTS) {
            connection?.disconnect()
            connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "xuci1.0-local-android")
                setRequestProperty("Accept-Encoding", "identity")
                connect()
            }

            val code = connection!!.responseCode
            if (code in 300..399) {
                val location = connection!!.getHeaderField("Location")
                    ?: error("გადამისამართების მისამართი ვერ მოიძებნა")
                currentUrl = URL(currentUrl, location)
            } else {
                check(code in 200..299) { "HTTP $code" }
                val total = connection!!.contentLengthLong
                BufferedInputStream(connection!!.inputStream, BUFFER_SIZE).use { input ->
                    BufferedOutputStream(FileOutputStream(partial), BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = ((copied * 100L) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    updateStatus("მოდელი იტვირთება: $percent% • ${formatBytes(copied)} / ${formatBytes(total)}")
                                }
                            }
                        }
                    }
                }

                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                connection!!.disconnect()
                return
            }
        }
        connection?.disconnect()
        error("ძალიან ბევრი HTTP გადამისამართება")
    }

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "ჯერ კითხვა დაწერე", Toast.LENGTH_SHORT).show()
            return
        }

        if (handleLocalCommand(userMsg)) {
            userInputEt.text = null
            return
        }

        userInputEt.text = null
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        addUser(userMsg)
        lastAssistantMsg.clear()
        messages.add(Message("", false))
        messageAdapter.notifyItemInserted(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)

        val prompt = buildPrompt(userMsg)
        generationJob = lifecycleScope.launch {
            engine.sendUserPrompt(prompt)
                .onCompletion { cause ->
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                    if (cause == null) {
                        rememberExchange(userMsg, lastAssistantMsg.toString())
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "გენერაცია შეწყდა: ${safeMessage(cause)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .collect { token ->
                    val last = messages.lastIndex
                    if (last >= 0) {
                        lastAssistantMsg.append(token)
                        messages[last] = Message(lastAssistantMsg.toString().trimStart(), false)
                        messageAdapter.notifyItemChanged(last)
                        messagesRv.scrollToPosition(last)
                    }
                }
        }
    }

    private fun buildPrompt(userMsg: String): String {
        val memory = prefs.getString(KEY_MEMORY, "").orEmpty().takeLast(MAX_MEMORY_CHARS)
        val agent = routeAgent(userMsg)
        return """
            შენ ხარ xuci1.0 — ოფლაინ პირადი coding პარტნიორი Samsung Galaxy A70-ზე.
            პასუხი დაწერე ქართულად, გასაგებად და პრაქტიკულად. კოდი დატოვე მის ბუნებრივ პროგრამირების ენაზე.
            არ მოიგონო შესრულებული ტესტი ან ფაილი. უცნობი რამ პირდაპირ აღნიშნე.
            აქტიური სპეციალისტი: $agent
            წინა ადგილობრივი გამოცდილება:
            $memory

            მომხმარებლის მოთხოვნა:
            $userMsg
        """.trimIndent()
    }

    private fun routeAgent(text: String): String {
        val q = text.lowercase(Locale.ROOT)
        return when {
            listOf("apk", "აპკ", "manifest", "smali", "dex", "decompile").any(q::contains) ->
                "APK Analyst — APK სტრუქტურა, Manifest, DEX და უსაფრთხოება"
            listOf("python", "პითონ", ".py", "pip").any(q::contains) ->
                "Python Engineer — გამართული Python და შემოწმების გზა"
            listOf("android", "kotlin", "java", "gradle", "compose", "ანდროიდ").any(q::contains) ->
                "Android Engineer — Kotlin, Java, Gradle და Android SDK"
            listOf("error", "exception", "crash", "შეცდომ", "ლოგ").any(q::contains) ->
                "Debugger — ძირეული მიზეზი და მინიმალური გამოსწორება"
            else -> "General Reasoning and Coding Agent"
        }
    }

    private fun handleLocalCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("დაიმახსოვრე:", ignoreCase = true)) {
            val lesson = trimmed.substringAfter(":").trim()
            if (lesson.isNotEmpty()) {
                val old = prefs.getString(KEY_MEMORY, "").orEmpty()
                prefs.edit().putString(KEY_MEMORY, (old + "\n• " + lesson).takeLast(MAX_MEMORY_CHARS)).apply()
                addUser(text)
                addAssistant("დავიმახსოვრე და შემდეგ პასუხებში გავითვალისწინებ.")
            }
            return true
        }
        if (trimmed.equals("/memory", ignoreCase = true)) {
            addUser(text)
            val memory = prefs.getString(KEY_MEMORY, "").orEmpty()
            addAssistant(if (memory.isBlank()) "მეხსიერება ჯერ ცარიელია." else memory)
            return true
        }
        if (trimmed.equals("/clear-memory", ignoreCase = true)) {
            prefs.edit().remove(KEY_MEMORY).apply()
            addUser(text)
            addAssistant("ადგილობრივი სასწავლო მეხსიერება გასუფთავდა.")
            return true
        }
        return false
    }

    private fun rememberExchange(user: String, assistant: String) {
        if (assistant.isBlank()) return
        val old = prefs.getString(KEY_MEMORY, "").orEmpty()
        val compact = "\nQ: ${user.take(350)}\nA: ${assistant.take(700)}"
        prefs.edit().putString(KEY_MEMORY, (old + compact).takeLast(MAX_MEMORY_CHARS)).apply()
    }

    private fun addUser(text: String) {
        messages.add(Message(text, true))
        messageAdapter.notifyItemInserted(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
    }

    private fun addAssistant(text: String) {
        messages.add(Message(text, false))
        messageAdapter.notifyItemInserted(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
    }

    private fun showFailure(message: String) {
        statusTv.text = message
        userInputEt.hint = "დააჭირე ღილაკს ხელახლა საცდელად"
        userActionFab.setImageResource(R.drawable.outline_folder_open_24)
        userActionFab.isEnabled = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private suspend fun updateStatus(text: String) {
        withContext(Dispatchers.Main) { statusTv.text = text }
    }

    private fun ensureModelsDirectory(): File = File(filesDir, "models").also {
        if (it.exists() && !it.isDirectory) it.delete()
        if (!it.exists()) check(it.mkdirs()) { "models საქაღალდე ვერ შეიქმნა" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    private fun safeMessage(t: Throwable): String = t.message?.take(300) ?: t.javaClass.simpleName

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val MODEL_FILE_NAME = "qwen2.5-coder-0.5b-instruct-q4_0.gguf"
        private const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_0.gguf?download=true"
        private const val MODEL_SHA256 = "9739055e046d62a937e5b7879012209ef40ebea8a1569a96028de491f3f091d5"
        private const val BUFFER_SIZE = 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private const val KEY_MEMORY = "experience_memory"
        private const val MAX_MEMORY_CHARS = 6000
    }
}
