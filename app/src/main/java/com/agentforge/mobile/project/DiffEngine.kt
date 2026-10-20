package com.agentforge.mobile.project

/**
 * Computes human-readable Unified Diffs between original and modified file contents,
 * and applies structured SEARCH/REPLACE or unified patches safely with strict verification.
 */
object DiffEngine {

    data class PatchOperation(
        val searchBlock: String,
        val replaceBlock: String
    )

    data class PatchApplicationResult(
        val isSuccess: Boolean,
        val updatedContent: String,
        val appliedHunks: Int = 0,
        val diffPreview: String = "",
        val errorMessage: String? = null
    )

    /**
     * Generates a standard unified diff (`--- a/path`, `+++ b/path`, `@@ ... @@`)
     * using LCS (Longest Common Subsequence) over lines.
     */
    fun computeUnifiedDiff(
        relativePath: String,
        originalText: String,
        modifiedText: String,
        contextLines: Int = 3
    ): String {
        if (originalText == modifiedText) {
            return "No differences in $relativePath"
        }

        val oldLines = originalText.replace("\r\n", "\n").split("\n")
        val newLines = modifiedText.replace("\r\n", "\n").split("\n")

        val edits = computeLineEdits(oldLines, newLines)
        val sb = StringBuilder()
        sb.appendLine("--- a/$relativePath")
        sb.appendLine("+++ b/$relativePath")

        // Group edits with surrounding context
        val changedIndices = edits.mapIndexedNotNull { index, edit ->
            if (edit.type != EditType.EQUAL) index else null
        }
        if (changedIndices.isEmpty()) return "No differences in $relativePath"

        var i = 0
        while (i < changedIndices.size) {
            val startEditIdx = (changedIndices[i] - contextLines).coerceAtLeast(0)
            var endEditIdx = (changedIndices[i] + contextLines).coerceAtMost(edits.lastIndex)

            while (i + 1 < changedIndices.size && changedIndices[i + 1] - contextLines <= endEditIdx + 1) {
                i++
                endEditIdx = (changedIndices[i] + contextLines).coerceAtMost(edits.lastIndex)
            }

            val hunkSlice = edits.subList(startEditIdx, endEditIdx + 1)
            val oldStart = (hunkSlice.firstOrNull { it.oldLineNumber > 0 }?.oldLineNumber ?: 1)
            val newStart = (hunkSlice.firstOrNull { it.newLineNumber > 0 }?.newLineNumber ?: 1)
            val oldCount = hunkSlice.count { it.type == EditType.EQUAL || it.type == EditType.DELETE }
            val newCount = hunkSlice.count { it.type == EditType.EQUAL || it.type == EditType.INSERT }

            sb.appendLine("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            for (edit in hunkSlice) {
                when (edit.type) {
                    EditType.EQUAL -> sb.appendLine(" ${edit.line}")
                    EditType.DELETE -> sb.appendLine("-${edit.line}")
                    EditType.INSERT -> sb.appendLine("+${edit.line}")
                }
            }
            i++
        }

        return sb.toString().trimEnd()
    }

    /**
     * Applies either explicit `<<<< SEARCH ... ==== ... >>>> REPLACE` blocks
     * or direct search/replace pairs, verifying that every search block matches accurately.
     */
    fun applyPatch(
        relativePath: String,
        originalContent: String,
        patchText: String
    ): PatchApplicationResult {
        val normalizedOriginal = originalContent.replace("\r\n", "\n")
        val normalizedPatch = patchText.replace("\r\n", "\n")

        val operations = parseSearchReplaceBlocks(normalizedPatch)
        if (operations.isEmpty()) {
            return PatchApplicationResult(
                isSuccess = false,
                updatedContent = originalContent,
                errorMessage = "Patch does not contain valid SEARCH/REPLACE blocks (`<<<< SEARCH ... ==== ... >>>> REPLACE`)."
            )
        }

        var workingContent = normalizedOriginal
        var applied = 0

        for ((index, op) in operations.withIndex()) {
            if (op.searchBlock.isEmpty()) {
                return PatchApplicationResult(
                    isSuccess = false,
                    updatedContent = originalContent,
                    errorMessage = "Hunk #${index + 1} has an empty SEARCH block. Use write_file for full file replacement."
                )
            }
            val matchIndex = workingContent.indexOf(op.searchBlock)
            if (matchIndex < 0) {
                return PatchApplicationResult(
                    isSuccess = false,
                    updatedContent = originalContent,
                    errorMessage = "Hunk #${index + 1} SEARCH block was not found in '$relativePath'. Ensure exact lines are matched."
                )
            }
            workingContent = workingContent.substring(0, matchIndex) +
                op.replaceBlock +
                workingContent.substring(matchIndex + op.searchBlock.length)
            applied++
        }

        val diff = computeUnifiedDiff(relativePath, normalizedOriginal, workingContent)
        return PatchApplicationResult(
            isSuccess = true,
            updatedContent = workingContent,
            appliedHunks = applied,
            diffPreview = diff
        )
    }

    fun parseSearchReplaceBlocks(patchText: String): List<PatchOperation> {
        val results = mutableListOf<PatchOperation>()
        val regex = Regex(
            "<{4,}\s*SEARCH\r?\n([\s\S]*?)\r?\n={4,}\r?\n([\s\S]*?)\r?\n>{4,}\s*REPLACE"
        )
        for (match in regex.findAll(patchText)) {
            results.add(
                PatchOperation(
                    searchBlock = match.groupValues[1],
                    replaceBlock = match.groupValues[2]
                )
            )
        }
        return results
    }

    private enum class EditType { EQUAL, DELETE, INSERT }

    private data class LineEdit(
        val type: EditType,
        val line: String,
        val oldLineNumber: Int,
        val newLineNumber: Int
    )

    private fun computeLineEdits(oldLines: List<String>, newLines: List<String>): List<LineEdit> {
        val n = oldLines.size
        val m = newLines.size
        // Guard against huge files in LCS table
        if (n * m > 2_500_000) {
            val simple = mutableListOf<LineEdit>()
            oldLines.forEachIndexed { idx, s -> simple.add(LineEdit(EditType.DELETE, s, idx + 1, 0)) }
            newLines.forEachIndexed { idx, s -> simple.add(LineEdit(EditType.INSERT, s, 0, idx + 1)) }
            return simple
        }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) {
                    1 + dp[i + 1][j + 1]
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val edits = mutableListOf<LineEdit>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (oldLines[i] == newLines[j]) {
                edits.add(LineEdit(EditType.EQUAL, oldLines[i], i + 1, j + 1))
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                edits.add(LineEdit(EditType.DELETE, oldLines[i], i + 1, 0))
                i++
            } else {
                edits.add(LineEdit(EditType.INSERT, newLines[j], 0, j + 1))
                j++
            }
        }
        while (i < n) {
            edits.add(LineEdit(EditType.DELETE, oldLines[i], i + 1, 0))
            i++
        }
        while (j < m) {
            edits.add(LineEdit(EditType.INSERT, newLines[j], 0, j + 1))
            j++
        }
        return edits
    }
}
