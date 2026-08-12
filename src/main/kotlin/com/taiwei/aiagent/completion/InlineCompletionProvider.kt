package com.taiwei.aiagent.completion

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.taiwei.aiagent.settings.AiAgentSettings
import com.taiwei.aiagent.util.JavaPluginAvailability
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class InlineCompletionProvider : DebouncedInlineCompletionProvider() {

    companion object {
        private val LOG = Logger.getInstance(InlineCompletionProvider::class.java)

        private const val MAX_CONTEXT_CHARS = 3000
        private const val DEBOUNCE_MS = 300L

        private val STOP_PATTERNS = listOf(
            // LLM wrapped the completion in a markdown code block; the fence marks the end
            "\n```",
            // LLM appended a prose explanation after the code — not part of the completion
            "\nExplanation",
            // LLM appended an inline note after the code — not part of the completion
            "\nNote:",
            // Chinese equivalents of "Note" / "Explanation" from LLM prose output
            "\n注意",
            "\n说明",
        )

        private val EXT_LANGUAGE_MAP = mapOf(
            "java" to "Java", "kt" to "Kotlin", "kts" to "Kotlin",
            "py" to "Python", "js" to "JavaScript", "ts" to "TypeScript",
            "jsx" to "JSX", "tsx" to "TSX",
            "go" to "Go", "rs" to "Rust", "rb" to "Ruby",
            "php" to "PHP", "cs" to "C#", "cpp" to "C++", "c" to "C",
            "h" to "C", "hpp" to "C++",
            "swift" to "Swift", "scala" to "Scala", "dart" to "Dart",
            "vue" to "Vue", "html" to "HTML", "css" to "CSS",
            "scss" to "SCSS", "less" to "Less",
            "sql" to "SQL", "sh" to "Shell", "bash" to "Shell",
            "xml" to "XML", "json" to "JSON", "yaml" to "YAML", "yml" to "YAML",
            "md" to "Markdown",
        )

        private val FIM_MODEL_PATTERNS = listOf(
            "deepseek-coder", "deepseek-coder-v2", "deepseek-v2",
            "starcoder", "code-llama", "codellama", "codegemma",
            "qwen2.5-coder", "qwen-coder",
        )

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val activeJob = AtomicReference<Job?>(null)

    private val completionPromptTemplate: String by lazy { loadTemplate("templates/completion_prompt.vm") }

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("taiwei-inline-completion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return AiAgentSettings.getInstance().isCompletionEnabled
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return DEBOUNCE_MS.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val job = kotlin.coroutines.coroutineContext[Job]
        val previous = activeJob.getAndSet(job)
        if (previous != null && previous != job) {
            previous.cancel()
        }

        val editor = request.editor
        val document = editor.document
        val offset = editor.caretModel.offset

        val text = document.text
        if (text.isBlank()) return InlineCompletionSuggestion.Empty

        val rawPrefix = text.substring(0, minOf(offset, text.length))
        val rawSuffix = if (offset < text.length) text.substring(offset) else ""

        if (rawPrefix.trim().isEmpty()) {
            return InlineCompletionSuggestion.Empty
        }

        val language = detectLanguage(editor)
        val languageContext = if (language.isNotEmpty()) "[$language] " else ""

        val psiContext = buildPsiContext(editor, offset)
        val smartPrefix = psiContext + smartTruncatePrefix(rawPrefix, MAX_CONTEXT_CHARS)
        val smartSuffix = smartTruncateSuffix(rawSuffix, MAX_CONTEXT_CHARS)

        val settings = AiAgentSettings.getInstance()
        val useFim = supportsFim(settings.model)

        val completion = if (useFim) {
            callLlmFim(smartPrefix, smartSuffix)
        } else {
            callLlmStreaming(smartPrefix, smartSuffix, languageContext)
        }
        completion ?: return InlineCompletionSuggestion.Empty

        val currentIndent = getCurrentLineIndent(text, offset)
        val processed = postProcess(completion, rawPrefix)
        if (processed.isEmpty()) return InlineCompletionSuggestion.Empty

        val indented = applyAutoIndent(processed, currentIndent)

        val element = InlineCompletionGrayTextElement(indented)
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase(), flowOf(element))
    }

    // ==================== 1. FIM 检测 ====================

    private fun supportsFim(model: String): Boolean {
        if (model.isEmpty()) return false
        val lower = model.lowercase()
        return FIM_MODEL_PATTERNS.any { lower.contains(it) }
    }

    // ==================== 2. PSI 上下文提取 ====================

    /**
     * Extract imports, enclosing class signature, and enclosing method signature at the cursor
     * to provide the LLM with structural context beyond the raw character window.
     * Runs in a ReadAction so it is safe to call from any thread.
     */
    private fun buildPsiContext(editor: Editor, offset: Int): String {
        return try {
            ReadAction.compute<String, Exception> {
                val project = editor.project ?: return@compute ""
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                    ?: return@compute ""

                val sb = StringBuilder()
                val isJavaPluginAvailable = JavaPluginAvailability.isJavaPluginAvailable()

                // Collect import statements (Java)
                if (isJavaPluginAvailable && psiFile is PsiJavaFile) {
                    val importList = psiFile.importList
                    if (importList != null && importList.allImportStatements.isNotEmpty()) {
                        importList.allImportStatements.forEach { sb.append(it.text).append("\n") }
                        sb.append("\n")
                    }
                }

                // Collect imports for other file types by scanning leading lines (Kotlin, etc.)
                if (!isJavaPluginAvailable || psiFile !is PsiJavaFile) {
                    val text = psiFile.text
                    val lines = text.lines()
                    val importLines = lines.takeWhile { it.isBlank() || it.startsWith("import ") || it.startsWith("package ") }
                    if (importLines.isNotEmpty()) {
                        importLines.filter { it.startsWith("import ") }.forEach { sb.append(it).append("\n") }
                        if (sb.isNotEmpty()) sb.append("\n")
                    }
                }

                // Find enclosing PSI elements at the cursor
                val element = psiFile.findElementAt(minOf(offset, psiFile.textLength - 1).coerceAtLeast(0))
                if (isJavaPluginAvailable && element != null) {
                    val enclosingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
                    val enclosingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

                    if (enclosingClass != null) {
                        sb.append("// class: ").append(enclosingClass.name)
                        val superClass = enclosingClass.superClass
                        if (superClass != null && superClass.name != "Object") {
                            sb.append(" extends ").append(superClass.name)
                        }
                        enclosingClass.interfaces.forEach { sb.append(" implements ").append(it.name) }
                        sb.append("\n")

                        // Include field declarations for context
                        enclosingClass.fields.forEach { field ->
                            sb.append("  ").append(field.type.presentableText).append(" ").append(field.name).append(";\n")
                        }
                    }

                    if (enclosingMethod != null) {
                        sb.append("// method: ").append(enclosingMethod.name)
                            .append(enclosingMethod.parameterList.text).append("\n")
                    }

                    if (sb.isNotEmpty()) sb.append("\n")
                }

                sb.toString()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    // ==================== 3. 语言检测 ====================

    private fun detectLanguage(editor: com.intellij.openapi.editor.Editor): String {
        val vFile: VirtualFile = editor.virtualFile ?: return ""
        val ext = vFile.extension?.lowercase() ?: return ""
        return EXT_LANGUAGE_MAP[ext] ?: ""
    }

    // ==================== 4. 智能上下文截断 ====================

    private fun smartTruncatePrefix(rawPrefix: String, maxChars: Int): String {
        if (rawPrefix.length <= maxChars) return rawPrefix

        val candidate = rawPrefix.substring(rawPrefix.length - maxChars)

        val lines = candidate.lines()
        var startLine = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) {
                startLine = i
                break
            }
        }

        return lines.subList(startLine, lines.size).joinToString("\n")
    }

    private fun smartTruncateSuffix(rawSuffix: String, maxChars: Int): String {
        if (rawSuffix.length <= maxChars) return rawSuffix
        val truncated = rawSuffix.substring(0, maxChars)
        val lastNewline = truncated.lastIndexOf('\n')
        return if (lastNewline > 0) truncated.substring(0, lastNewline) else truncated
    }

    // ==================== 4. 自动缩进 ====================

    private fun getCurrentLineIndent(fullText: String, offset: Int): String {
        val lineStart = fullText.lastIndexOf('\n', offset - 1.coerceAtMost(fullText.length)) + 1
        val lineEnd = fullText.indexOf('\n', offset).let { if (it < 0) fullText.length else it }
        val line = fullText.substring(lineStart, lineEnd)
        val match = Regex("^(\\s*)").find(line)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun applyAutoIndent(completion: String, baseIndent: String): String {
        if (baseIndent.isEmpty()) return completion
        val lines = completion.split("\n")
        if (lines.size <= 1) return completion

        val result = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            if (index == 0) {
                result.append(line)
            } else {
                if (line.isBlank()) {
                    result.append("")
                } else {
                    result.append(baseIndent).append(line)
                }
            }
            if (index < lines.size - 1) {
                result.append("\n")
            }
        }
        return result.toString()
    }

    // ==================== 5. FIM 模式 LLM 调用 ====================

    private suspend fun callLlmFim(prefix: String, suffix: String): String? {
        val settings = AiAgentSettings.getInstance()
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val model = settings.model

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) return null

        val requestBody = JsonObject()
        requestBody.addProperty("model", model)
        requestBody.addProperty("prompt", prefix)
        requestBody.addProperty("suffix", suffix)
        requestBody.addProperty("max_tokens", 256)
        requestBody.addProperty("temperature", 0.0)
        requestBody.addProperty("stream", true)

        val url = if (baseUrl.endsWith("/")) "${baseUrl}beta/completions" else "$baseUrl/beta/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return executeStreamingRequest(request)
    }

    // ==================== 6. Chat 模式 LLM 调用 ====================

    private suspend fun callLlmStreaming(prefix: String, suffix: String, languageContext: String): String? {
        val settings = AiAgentSettings.getInstance()
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val model = settings.model

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) return null

        val prompt = buildPrompt(prefix, suffix, languageContext)

        val requestBody = JsonObject()
        requestBody.addProperty("model", model)
        requestBody.addProperty("max_tokens", 256)
        requestBody.addProperty("temperature", 0.0)
        requestBody.addProperty("stream", true)

        val streamOptions = JsonObject()
        streamOptions.addProperty("include_usage", true)
        requestBody.add("stream_options", streamOptions)

        val messages = JsonArray()
        val msg = JsonObject()
        msg.addProperty("role", "user")
        msg.addProperty("content", prompt)
        messages.add(msg)
        requestBody.add("messages", messages)

        val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions" else "$baseUrl/chat/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return executeStreamingRequest(request)
    }

    private suspend fun executeStreamingRequest(request: Request): String? {
        return withContext(Dispatchers.IO) {
            try {
                val accumulated = StringBuilder()

                val call = httpClient.newCall(request)
                val response = call.execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext null
                }

                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), StandardCharsets.UTF_8))
                var line: String?
                var hasError = false
                while (reader.readLine().also { line = it } != null) {
                    val dataLine = line ?: continue
                    if (!dataLine.startsWith("data:")) continue
                    val data = dataLine.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JsonParser.parseString(data).asJsonObject
                        if (json.has("error") && !json.get("error").isJsonNull) {
                            hasError = true
                            break
                        }

                        val choices = json.getAsJsonArray("choices") ?: continue
                        if (choices.size() == 0) continue
                        val choice = choices[0].asJsonObject

                        if (choice.has("text")) {
                            accumulated.append(choice.get("text").asString)
                        } else {
                            val delta = choice.getAsJsonObject("delta") ?: continue
                            if (delta.has("content") && !delta.get("content").isJsonNull) {
                                accumulated.append(delta.get("content").asString)
                            }
                        }
                    } catch (_: Exception) {
                        // skip unparseable lines
                    }
                }
                reader.close()
                response.close()

                if (hasError) {
                    LOG.warn("Streaming completion error: API error in stream")
                    return@withContext null
                }

                val result = accumulated.toString().trim()
                if (result.isEmpty()) null else result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("LLM streaming completion call failed", e)
                null
            }
        }
    }

    // ==================== 7. 提示词构建 ====================

    private fun buildPrompt(prefix: String, suffix: String, languageContext: String): String {
        val suffixPart = if (suffix.isNotEmpty()) suffix else ""
        return completionPromptTemplate
            .replace("\${languageContext}", languageContext)
            .replace("\${prefix}", prefix)
            .replace("\${suffix}", suffixPart)
    }

    private fun loadTemplate(resourcePath: String): String {
        return try {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).readText()
            } ?: "<PRE> \${prefix}<SUF> \${suffix}<MID>"
        } catch (e: Exception) {
            LOG.warn("Failed to load completion template", e)
            "<PRE> \${prefix}<SUF> \${suffix}<MID>"
        }
    }

    // ==================== 8. 结果后处理 ====================

    private fun postProcess(completion: String, rawPrefix: String): String {
        var result = completion

        result = stripCodeFence(result)

        for (pattern in STOP_PATTERNS) {
            val idx = result.indexOf(pattern)
            if (idx > 0) {
                result = result.substring(0, idx)
            }
        }

        result = removeDuplicatePrefix(result, rawPrefix)
        result = result.trimEnd()

        if (result.endsWith("\n}") || result.endsWith("\n)")) {
            val trimmed = result.trimEnd().removeSuffix("}").removeSuffix(")").trimEnd()
            if (trimmed.isNotEmpty()) {
                result = trimmed
            }
        }

        return result
    }

    private fun stripCodeFence(text: String): String {
        var result = text.trim()
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1)
            } else {
                result = result.removePrefix("```")
            }
        }
        if (result.endsWith("```")) {
            result = result.removeSuffix("```")
        }
        return result.trimEnd()
    }

    private fun removeDuplicatePrefix(completion: String, prefix: String): String {
        if (completion.isEmpty() || prefix.isEmpty()) return completion

        val window = prefix.takeLast(80)

        val maxOverlap = minOf(completion.length, window.length, 80)
        for (overlapLen in maxOverlap downTo 4) {
            val prefixTail = window.substring(window.length - overlapLen)
            if (completion.startsWith(prefixTail)) {
                return completion.substring(overlapLen)
            }
        }

        return completion
    }
}
