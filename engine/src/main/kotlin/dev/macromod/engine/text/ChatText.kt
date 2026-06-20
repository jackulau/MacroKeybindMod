package dev.macromod.engine.text

/** The Minecraft section-sign formatting/colour code pattern: `§` followed by one code char. */
private val SECTION_CODE = Regex("§.")

/**
 * Remove Minecraft `§x` formatting/colour codes from [text].
 *
 * Single source of truth for "clean chat text": shared by the `strip` action and the host's
 * `%CHATCLEAN%` chat variable so both strip identically. Backs the documented contract that
 * `%CHAT%` carries the raw line (with codes) and `%CHATCLEAN%` carries it without
 * (VARIABLES.md / EVENTS.md / ARCHITECTURE-REFERENCE.md).
 */
fun stripFormattingCodes(text: String): String = text.replace(SECTION_CODE, "")
