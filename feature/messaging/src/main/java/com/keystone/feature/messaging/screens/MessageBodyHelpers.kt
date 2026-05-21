package com.keystone.feature.messaging.screens

/**
 * Heuristic: is this message body an "emoji-only" message that
 * deserves jumbo rendering?
 *
 * Rule of thumb stolen from WhatsApp / iMessage: when the user sends
 * a message that is JUST one or two emoji and nothing else, the
 * message renders at ~3x normal size so it reads more like a
 * reaction than a sentence. We render at `displaySmall` (~42sp) in
 * that case; everything else renders at `bodyMedium`.
 *
 * The detection is intentionally simple and conservative:
 *   - Empty / whitespace-only → not jumbo.
 *   - Body contains any ASCII letter or digit → not jumbo.
 *   - Body's codepoint count > 8 → not jumbo (avoids a 12-emoji
 *     novel taking up the whole screen).
 *   - At least one non-ASCII codepoint must be present (otherwise
 *     it's pure ASCII punctuation, which isn't "emoji").
 *
 * Edge cases like skin-tone modifiers + ZWJ family sequences inflate
 * the codepoint count past 1 even for a single visible emoji — the
 * 8-codepoint ceiling accommodates ~3 grapheme clusters in practice.
 */
internal fun String.isJumboEmoji(): Boolean {
    val cps = codePoints().toArray()
    if (cps.isEmpty() || cps.size > 8) return false
    var hasNonAscii = false
    for (cp in cps) {
        if (Character.isWhitespace(cp)) continue
        if (cp < 0x80) {
            // Any ASCII non-whitespace char (letters, digits,
            // punctuation) disqualifies — even a single ! or ?
            // means the user typed text, not just an emoji.
            return false
        }
        hasNonAscii = true
    }
    return hasNonAscii
}
