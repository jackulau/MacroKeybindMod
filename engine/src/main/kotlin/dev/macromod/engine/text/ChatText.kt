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
fun convertAmpCodes(text: String): String {
    // Fast-path the common per-tick output: a status line like log("Mining: 50 blocks") holds no `&`,
    // so there is no colour code AND no `&&` escape to convert. `&` is the sole trigger for BOTH passes,
    // so its absence means the result is the input — skip the AMP_CODE Matcher (measured ~184 B/call).
    if (text.indexOf('&') < 0) return text
    return text.replace(AMP_CODE) { "§" + it.groupValues[1] }.replace("&&", "&")
}

/**
 * Remove Minecraft `§x` formatting/colour codes from [text].
 *
 * Single source of truth for "clean chat text": shared by the `strip` action and the host's
 * `%CHATCLEAN%` chat variable so both strip identically. Backs the documented contract that
 * `%CHAT%` carries the raw line (with codes) and `%CHATCLEAN%` carries it without
 * (VARIABLES.md / EVENTS.md / ARCHITECTURE-REFERENCE.md).
 */
fun stripFormattingCodes(text: String): String {
    // Same fast-path: text with no `§` has nothing to strip (the `strip` action / %CHATCLEAN% on an
    // already-clean line), so skip the SECTION_CODE Matcher (measured ~176 B/call) and return as-is.
    if (text.indexOf('§') < 0) return text
    return text.replace(SECTION_CODE, "")
}

/** A chat line split into its sender and the message body (the `%CHATPLAYER%` / `%CHATMESSAGE%` pair). */
data class ChatSender(val player: String, val message: String)

// The four sender-extraction patterns ported verbatim from MKB OnChatProvider (all CASE_INSENSITIVE,
// like its `Pattern.compile(..., 2)`). Sender names are 2-16 of [a-z0-9_] — MKB's own character class.
private val VANILLA_QUOTE = Regex("^<([a-z0-9_]{2,16})>", RegexOption.IGNORE_CASE)
private val LIKELY_QUOTE = Regex("^([a-z0-9_]{2,16}):|^<(.+?)>", RegexOption.IGNORE_CASE)
private val BESTGUESS_QUOTE = Regex("<(.+?)>|\\[(.+?)]|\\((.+?)\\)", RegexOption.IGNORE_CASE)
private val ACTUAL_NAME = Regex("([a-z0-9_]{2,16})", RegexOption.IGNORE_CASE)

/**
 * Parse the sender + message body out of a formatting-stripped chat line, mirroring MKB
 * `OnChatProvider.guessPlayer`'s single-message path. Backs the documented `onChat` contract that
 * `%CHATPLAYER%` is the line's sender and `%CHATMESSAGE%` is the message with that sender prefix
 * removed (EVENTS.md) — the host prefers a real client-signed sender when it has one and falls back
 * to this guess for unsigned (GAME-channel / plugin-formatted) lines.
 *
 * Tiers (first match wins), each yielding (sender, body-with-prefix-stripped):
 *  1. vanilla `<name> body`                  → name + body
 *  2. `name: body`                           → name + body
 *  3. any bracketed/quoted token `<x>`/`[x]`/`(x)` with a name inside → that name + line minus the token
 *  4. no sender pattern                      → "" + the line unchanged
 *
 * Deliberately omits guessPlayer's stateful `followOnLikely` continuation (256+-char split messages):
 * a pure function can't carry the previous line's sender, and normal chat never triggers it.
 */
fun parseChatSender(clean: String): ChatSender {
    // Tier 1 — vanilla `<name> ...` (the gate mirrors MKB: startsWith '<' AND contains '>').
    if (clean.startsWith("<") && clean.indexOf('>') > -1) {
        VANILLA_QUOTE.find(clean)?.let {
            return ChatSender(it.groupValues[1], VANILLA_QUOTE.replaceFirst(clean, "").trim())
        }
    }
    // Tiers 2-3 — a `name:` prefix, else any bracketed/quoted token anywhere in the line.
    var bestGuess: String? = null
    var matched: Regex? = null
    LIKELY_QUOTE.find(clean)?.let { m ->
        matched = LIKELY_QUOTE
        bestGuess = m.groupValues[1].ifEmpty { m.groupValues[2] } // `name:` group, else `<x>` group
    } ?: BESTGUESS_QUOTE.find(clean)?.let { m ->
        matched = BESTGUESS_QUOTE
        bestGuess = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } // the one matched alternation
    }
    val guess = bestGuess
    val pattern = matched
    if (guess != null && pattern != null) {
        val name = ACTUAL_NAME.find(guess)
        // A name token inside the token → strip the matched prefix from the body; otherwise MKB keeps
        // the raw guess and leaves the message untouched (its `else if (guess != null)` is dead here —
        // that branch only fires for a followOnLikely carry, which we don't model).
        return if (name != null) ChatSender(name.groupValues[1], pattern.replaceFirst(clean, "").trim())
        else ChatSender(guess, clean)
    }
    // Tier 4 — no sender.
    return ChatSender("", clean)
}
