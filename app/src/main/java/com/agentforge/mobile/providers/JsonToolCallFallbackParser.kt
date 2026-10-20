package com.agentforge.mobile.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Strict, schema-validated JSON fallback parser for models that do not support native
 * OpenAI/Gemini tool calling.
 *
 * Models using this fallback are instructed via a system prompt suffix to emit tool calls
 * inside a ```json_tool_call ... ``` fenced block or structured JSON object:
 * `{"tool_calls": [{"name": "read_file", "arguments": {"path": "README.md"}}]}`.
 *
 * Any tool call whose name is not in [allowedTools] or that is missing required parameters
 * is rejected before execution.
 */
object JsonToolCallFallbackParser {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun buildFallbackSystemPromptInstruction(tools: List<ToolDefinitionSchema>): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("## Structured JSON Tool Calling Protocol")
        sb.appendLine("When you need to execute a tool on the project, output a fenced block labeled `json_tool_call` with this exact JSON schema:")
        sb.appendLine("```json_tool_call")
        sb.appendLine("{"tool_calls": [{"name": "<tool_name>", "arguments": {"<param>": "<value>"}}]}")
        sb.appendLine("```")
        sb.appendLine("Available tools and required parameters:")
        for (tool in tools) {
            val req = tool.requiredParameters.joinToString(", ")
            sb.appendLine("- `${tool.name}` (required: [$req]): ${tool.description}")
        }
        sb.appendLine("Do NOT invent tool names or omit required arguments.")
        return sb.toString()
    }

    data class FallbackParseOutput(
        val cleanedAssistantText: String,
        val validToolCalls: List<AgentToolCallRequest>,
        val validationErrors: List<String>
    )

    fun extractAndValidateToolCalls(
        rawText: String,
        allowedTools: List<ToolDefinitionSchema>
    ): FallbackParseOutput {
        val toolMap = allowedTools.associateBy { it.name }
        val validCalls = mutableListOf<AgentToolCallRequest>()
        val errors = mutableListOf<String>()

        // Match ```json_tool_call ... ``` or ```json ... ``` blocks containing "tool_calls"
        val blockRegex = Regex("```(?:json_tool_call|json)?\s*\r?\n([\s\S]*?)\r?\n```")
        var cleanedText = rawText

        for (match in blockRegex.findAll(rawText)) {
            val candidateJson = match.groupValues[1].trim()
            if (!candidateJson.contains(""tool_calls"") && !candidateJson.contains(""name"")) {
                continue
            }

            try {
                val root = lenientJson.parseToJsonElement(candidateJson).jsonObject
                val callsArray: JsonArray? = when {
                    "tool_calls" in root -> root["tool_calls"]?.jsonArray
                    "name" in root && "arguments" in root -> JsonArray(listOf(root))
                    else -> null
                }

                if (callsArray != null) {
                    for (item in callsArray) {
                        val obj = item.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content?.trim() ?: ""
                        val argsObj = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())

                        val schema = toolMap[name]
                        if (schema == null) {
                            errors.add("Rejected unknown tool name '$name' in JSON fallback.")
                            continue
                        }

                        val missingRequired = schema.requiredParameters.filter { reqKey ->
                            !argsObj.containsKey(reqKey)
                        }
                        if (missingRequired.isNotEmpty()) {
                            errors.add("Rejected tool '$name': missing required arguments $missingRequired.")
                            continue
                        }

                        validCalls.add(
                            AgentToolCallRequest(
                                id = "call_${UUID.randomUUID().toString().take(8)}",
                                toolName = name,
                                arguments = argsObj
                            )
                        )
                    }
                    cleanedText = cleanedText.replace(match.value, "").trim()
                }
            } catch (e: Exception) {
                errors.add("Malformed JSON tool call block: ${e.message}")
            }
        }

        return FallbackParseOutput(
            cleanedAssistantText = cleanedText,
            validToolCalls = validCalls,
            validationErrors = errors
        )
    }
}
