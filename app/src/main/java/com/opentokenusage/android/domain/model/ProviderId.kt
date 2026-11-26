package com.opentokenusage.android.domain.model

/** How the user supplies credentials for a provider on Android. */
enum class ProviderAuthMode {
    API_KEY,
    OAUTH_OR_JSON,
    SESSION_OR_OAUTH,
}

/** Supported AI service providers with direct Android API or OAuth access. */
enum class ProviderId(
    val id: String,
    val displayName: String,
    val brandColorRgb: Long,
    val authMode: ProviderAuthMode,
    val keyHelpUrl: String,
    val localFileHint: String = "",
    val supportsRegion: Boolean = false,
    val supportsCustomHost: Boolean = false,
    val statusPageUrl: String? = null,
) {
    CLAUDE(
        id = "claude",
        displayName = "Claude",
        brandColorRgb = 0xFFDE7356,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://claude.ai",
        localFileHint = "~/.claude/.credentials.json",
        statusPageUrl = "https://status.claude.com",
    ),
    CODEX(
        id = "codex",
        displayName = "Codex",
        brandColorRgb = 0xFF10A37F,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://chatgpt.com/codex",
        localFileHint = "~/.codex/auth.json",
        statusPageUrl = "https://status.openai.com",
    ),
    CURSOR(
        id = "cursor",
        displayName = "Cursor",
        brandColorRgb = 0xFF2563EB,
        authMode = ProviderAuthMode.SESSION_OR_OAUTH,
        keyHelpUrl = "https://cursor.com/settings",
        localFileHint = "Cursor API Key (crsr_...)、cursorAuth/accessToken、または WorkosCursorSessionToken Cookie",
        statusPageUrl = "https://status.cursor.com",
    ),
    COPILOT(
        id = "copilot",
        displayName = "GitHub Copilot",
        brandColorRgb = 0xFFA855F7,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://github.com/settings/tokens",
        localFileHint = "`gh auth token` または GitHub PAT (copilot/read:user)",
        supportsCustomHost = true,
        statusPageUrl = "https://www.githubstatus.com",
    ),
    ANTIGRAVITY(
        id = "antigravity",
        displayName = "Antigravity",
        brandColorRgb = 0xFF4285F4,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "",
        localFileHint = "Google ログイン、または OAuth トークン / antigravity-accounts.json",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        brandColorRgb = 0xFF6467F2,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://openrouter.ai/settings/keys",
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        brandColorRgb = 0xFF4D6BFE,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://platform.deepseek.com/api_keys",
        statusPageUrl = "https://status.deepseek.com",
    ),
    ZAI(
        id = "zai",
        displayName = "Z.ai",
        brandColorRgb = 0xFF4B5563,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://z.ai/manage-apikey/apikey-list",
    ),
    MINIMAX(
        id = "minimax",
        displayName = "MiniMax",
        brandColorRgb = 0xFFF5433C,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://platform.minimax.io/user-center/basic-information/interface-key",
        supportsRegion = true,
    ),
    SYNTHETIC(
        id = "synthetic",
        displayName = "Synthetic",
        brandColorRgb = 0xFF14B8A6,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://synthetic.new/settings",
    ),
    OPENCODE_GO(
        id = "opencode-go",
        displayName = "OpenCode Go",
        brandColorRgb = 0xFF38BDF8,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://opencode.ai/zen",
    ),
    AMP(
        id = "amp",
        displayName = "Amp",
        brandColorRgb = 0xFFF34E3F,
        authMode = ProviderAuthMode.API_KEY,
        keyHelpUrl = "https://ampcode.com/settings",
    ),
    KIMI(
        id = "kimi",
        displayName = "Kimi Code",
        brandColorRgb = 0xFF3B82F6,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://www.kimi.com/code",
        localFileHint = "~/.kimi/credentials/kimi-code.json",
    ),
    GROK(
        id = "grok",
        displayName = "Grok",
        brandColorRgb = 0xFF6B7280,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://accounts.x.ai",
        localFileHint = "~/.grok/auth.json または Cursor API Key (crsr_...)",
        statusPageUrl = "https://status.x.ai",
    ),
    MUSE(
        id = "muse",
        displayName = "Muse Code",
        brandColorRgb = 0xFF0668E1,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://meta.ai",
        localFileHint = "~/.config/muse/auth.json",
    ),
    FACTORY(
        id = "factory",
        displayName = "Factory / Droid",
        brandColorRgb = 0xFF52525B,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://app.factory.ai",
        localFileHint = "~/.factory/auth.json",
    ),
    DEVIN(
        id = "devin",
        displayName = "Devin",
        brandColorRgb = 0xFF22C55E,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://app.devin.ai",
        localFileHint = "~/.local/share/devin/credentials.toml (api_key / apiKey)",
    ),
    KIRO(
        id = "kiro",
        displayName = "Kiro",
        brandColorRgb = 0xFFC09CFF,
        authMode = ProviderAuthMode.OAUTH_OR_JSON,
        keyHelpUrl = "https://kiro.dev",
        localFileHint = "~/.aws/sso/cache/kiro-auth-token.json (+ profileArn)",
    );

    companion object {
        fun fromId(raw: String): ProviderId? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
    }
}
