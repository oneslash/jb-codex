package com.jetbrains.codex.ui

/**
 * Utility to mask sensitive information in approval dialogs and logs.
 * Per spec section 8: "Mask secrets in logs and approval dialogs"
 */
object SecretMasker {

    private val secretPatterns = listOf(
        // API Keys
        Regex("""(api[_-]?key|apikey)\s*[:=]\s*["']?([a-zA-Z0-9_\-]{20,})["']?""", RegexOption.IGNORE_CASE),
        Regex("""(sk-[a-zA-Z0-9]{20,})"""), // OpenAI style keys

        // Auth tokens
        Regex("""(token|auth|bearer)\s*[:=]\s*["']?([a-zA-Z0-9_\-\.]{20,})["']?""", RegexOption.IGNORE_CASE),

        // AWS credentials
        Regex("""(aws[_-]?access[_-]?key[_-]?id)\s*[:=]\s*["']?([A-Z0-9]{20})["']?""", RegexOption.IGNORE_CASE),
        Regex("""(aws[_-]?secret[_-]?access[_-]?key)\s*[:=]\s*["']?([A-Za-z0-9/+=]{40})["']?""", RegexOption.IGNORE_CASE),

        // Passwords
        Regex("""(password|passwd|pwd)\s*[:=]\s*["']?([^\s"']{6,})["']?""", RegexOption.IGNORE_CASE),

        // Private keys (PEM format)
        Regex("""-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----[\s\S]+?-----END (RSA |EC |OPENSSH )?PRIVATE KEY-----"""),

        // Database URLs with passwords
        Regex("""(jdbc:|postgresql:|mysql:|mongodb\+srv:)//[^:]+:([^@]+)@"""),

        // Generic secrets in environment variables
        Regex("""([A-Z_]+SECRET[A-Z_]*)\s*[:=]\s*["']?([^\s"']{8,})["']?"""),
        Regex("""([A-Z_]+KEY[A-Z_]*)\s*[:=]\s*["']?([^\s"']{8,})["']?"""),

        // GitHub tokens
        Regex("""(ghp_[a-zA-Z0-9]{36})"""),
        Regex("""(github_pat_[a-zA-Z0-9_]{82})"""),

        // SSH keys
        Regex("""(ssh-rsa\s+[A-Za-z0-9+/]+={0,2})"""),
        Regex("""(ssh-ed25519\s+[A-Za-z0-9+/]+={0,2})""")
    )

    /**
     * Mask sensitive information in the given text.
     * Returns the text with secrets replaced by [MASKED]
     */
    fun mask(text: String): String {
        var masked = text

        for (pattern in secretPatterns) {
            masked = pattern.replace(masked) { matchResult ->
                when (matchResult.groupValues.size) {
                    // Pattern with capture groups (key + value)
                    3 -> "${matchResult.groupValues[1]}=[MASKED]"
                    // Simple pattern (just the secret)
                    2 -> "[MASKED]"
                    // Full match only
                    else -> "[MASKED]"
                }
            }
        }

        return masked
    }

    /**
     * Mask sensitive command arguments.
     * Handles common patterns like:
     * - curl -H "Authorization: Bearer token"
     * - git clone https://user:password@github.com/repo
     * - export API_KEY=secret
     */
    fun maskCommand(command: List<String>): List<String> {
        return command.mapIndexed { index, arg ->
            // Check if previous arg was a flag that might contain secrets
            val prevArg = if (index > 0) command[index - 1] else null
            val isSensitiveFlag = prevArg != null && prevArg.matches(
                Regex("""--(token|password|secret|key|auth|credential).*""", RegexOption.IGNORE_CASE)
            )

            if (isSensitiveFlag) {
                "[MASKED]"
            } else {
                mask(arg)
            }
        }
    }

    /**
     * Mask file paths that might contain secrets in their names
     */
    fun maskPath(path: String): String {
        val sensitiveFiles = listOf(
            ".env",
            "credentials.json",
            "secrets.yaml",
            "id_rsa",
            "id_ed25519",
            ".npmrc",
            ".pypirc",
            "config.yml"
        )

        val fileName = path.substringAfterLast("/")
        return if (sensitiveFiles.any { fileName.contains(it, ignoreCase = true) }) {
            "${path.substringBeforeLast("/")}/<sensitive-file>"
        } else {
            path
        }
    }
}
