package com.opentokenusage.android.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.opentokenusage.android.R
import com.opentokenusage.android.domain.model.ProviderId

object ProviderVisuals {
    @DrawableRes
    fun iconRes(providerId: String): Int = when (providerId.lowercase()) {
        "amp" -> R.drawable.ic_provider_amp
        "antigravity" -> R.drawable.ic_provider_antigravity
        "claude" -> R.drawable.ic_provider_claude
        "codex" -> R.drawable.ic_provider_codex
        "copilot" -> R.drawable.ic_provider_copilot
        "cursor" -> R.drawable.ic_provider_cursor
        "deepseek" -> R.drawable.ic_provider_deepseek
        "devin" -> R.drawable.ic_provider_devin
        "factory" -> R.drawable.ic_provider_factory
        "grok" -> R.drawable.ic_provider_grok
        "kimi" -> R.drawable.ic_provider_kimi
        "kiro" -> R.drawable.ic_provider_kiro
        "minimax" -> R.drawable.ic_provider_minimax
        "muse" -> R.drawable.ic_provider_muse
        "opencode-go" -> R.drawable.ic_provider_opencode_go
        "openrouter" -> R.drawable.ic_provider_openrouter
        "synthetic" -> R.drawable.ic_provider_synthetic
        "zai" -> R.drawable.ic_provider_zai
        else -> R.drawable.ic_launcher_foreground
    }

    fun barColor(providerId: String, isDark: Boolean): Color {
        val raw = ProviderId.fromId(providerId)?.brandColorRgb ?: 0xFF0078D4
        val c = Color(raw)
        // Ensure monochrome or near-black brand colors stay crisp against dark OLED surfaces
        return if (isDark && raw == 0xFF4B5563) Color(0xFF9CA3AF) else c
    }

    fun iconTint(providerId: String, isDark: Boolean): Color =
        when (providerId.lowercase()) {
            "grok", "opencode-go" -> Color(0xFF000000)
            "codex", "cursor", "devin", "factory", "kimi", "synthetic", "zai" ->
                if (isDark) Color(0xFFF3F4F6) else Color(0xFF18181B)
            else -> barColor(providerId, isDark)
        }
}
