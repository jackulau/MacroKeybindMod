package dev.macromod.engine.text

/** The Minecraft section-sign formatting/colour code pattern: `§` followed by one code char. */
private val SECTION_CODE = Regex("§.")

/** An `&`-prefixed colour/format code — a valid code char (0-9 a-f k-o r) NOT already escaped by a leading `&`. */
private val AMP_CODE = Regex("(?<!&)&([0-9a-fklmnor])")

/**
 * Convert `&`-prefixed colour/format codes to Minecraft `§` codes, matching MKB's `Util.convertAmpCodes`:
 * `&c` -> `§c` (only the valid code chars `0-9 a-f k-o r`), then `&&` -> a literal `&`. The negative
 * lookbehind leaves an escaped `&&c` as a literal `&c`, and a stray `&` (or `&z`) is untouched.
 *
 * Lets macro authors write the easy-to-type `&` codes instead of the hard-to-enter `§`. Single source of
 * truth shared by the display-text actions (log/logto/title/toast/popupmessage) so all convert identically.
 */
fun convertAmpCodes(text: String): String =
    text.replace(AMP_CODE) { "§" + it.groupValues[1] }.replace("&&", "&")

/**
 * Remove Minecraft `§x` formatting/colour codes from [text].
 *
 * Single source of truth for "clean chat text": shared by the `strip` action and the host's
 * `%CHATCLEAN%` chat variable so both strip identically. Backs the documented contract that
 * `%CHAT%` carries the raw line (with codes) and `%CHATCLEAN%` carries it without
 * (VARIABLES.md / EVENTS.md / ARCHITECTURE-REFERENCE.md).
 */
fun stripFormattingCodes(text: String): String = text.replace(SECTION_CODE, "")
