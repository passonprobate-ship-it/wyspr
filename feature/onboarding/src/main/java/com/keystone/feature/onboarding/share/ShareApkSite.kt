package com.keystone.feature.onboarding.share

/**
 * The HTML body served at GET /. Inline CSS, no JS — works on every
 * stock browser, no third-party assets, no fingerprinting surface.
 *
 * Styling mirrors Keystone's design tokens (dark surface, monospace
 * for identifiers, soft-green accent). The page is deliberately
 * "explainer-first": the user is reading this on a phone they trust
 * about an app they don't, so the consent affordance lives below the
 * privacy summary, not above it.
 */
internal object ShareApkSite {

    fun renderHtml(
        versionName: String,
        apkSha256: String,
        apkSizeBytes: Long,
    ): String {
        val sizeMb = "%.1f".format(apkSizeBytes / (1024.0 * 1024.0))
        val sha256Pretty = apkSha256.chunked(8).joinToString(" ")
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex,nofollow">
<title>Keystone — invitation</title>
<style>
  :root {
    --bg: #0a0f0d;
    --surface: #111817;
    --border: #1f2b27;
    --text: #d4e2dc;
    --muted: #7c9088;
    --accent: #80e0c0;
    --accent-dark: #2c5648;
    --warn: #f0c674;
    color-scheme: dark;
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0; padding: 0;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    line-height: 1.45;
  }
  body {
    padding: 24px 20px 48px;
    max-width: 560px; margin: 0 auto;
  }
  h1 {
    font-size: 28px; letter-spacing: -0.01em;
    margin: 8px 0 4px;
  }
  h2 {
    font-size: 14px; text-transform: uppercase; letter-spacing: 0.08em;
    color: var(--muted); margin: 28px 0 10px;
  }
  p { margin: 10px 0; font-size: 16px; }
  .tag {
    display: inline-block;
    color: var(--accent);
    border: 1px solid var(--accent-dark);
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    margin-bottom: 12px;
  }
  ul { padding-left: 20px; margin: 8px 0; }
  li { margin: 4px 0; }
  .panel {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 16px;
    margin: 16px 0;
  }
  .mono {
    font-family: ui-monospace, SFMono-Regular, "Roboto Mono", Menlo, monospace;
    font-size: 13px;
    color: var(--accent);
    word-break: break-all;
  }
  .button {
    display: block; width: 100%;
    background: var(--accent);
    color: #051210;
    padding: 16px;
    border: 0;
    border-radius: 8px;
    text-align: center;
    font-weight: 700;
    font-size: 17px;
    text-decoration: none;
    margin-top: 24px;
    letter-spacing: 0.01em;
  }
  .button:active { background: #6fc8aa; }
  .button-row {
    display: flex; gap: 8px;
    margin-top: 24px;
  }
  .button-row .button { margin-top: 0; }
  .secondary {
    background: transparent;
    color: var(--muted);
    border: 1px solid var(--border);
  }
  .footer {
    margin-top: 32px;
    font-size: 12px;
    color: var(--muted);
    text-align: center;
  }
  .warn {
    color: var(--warn);
    font-size: 13px;
    margin-top: 12px;
  }
</style>
</head>
<body>
  <span class="tag">Invitation</span>
  <h1>You've been invited to Keystone</h1>
  <p>
    Someone you trust handed you their phone and asked you to install
    this app. Read what it does before you tap install — you don't
    need an account, an email, or a phone number, and nothing you
    install here is checked against any online server.
  </p>

  <h2>What Keystone is</h2>
  <p>
    Keystone is a private, peer-to-peer app for small trusted
    communities. People who know each other in real life build a web
    of trust by meeting in person and pairing devices. After that,
    your devices talk to each other directly — over Bluetooth or
    WiFi Direct — with no central server in the middle.
  </p>

  <h2>What it stores on your phone</h2>
  <ul>
    <li>An identity key, stored in your phone's hardware secure element. Never leaves the device.</li>
    <li>The list of people you've paired with, encrypted locally with SQLCipher.</li>
    <li>Any messages, balances, or records exchanged with those people. All encrypted at rest.</li>
  </ul>

  <h2>What it does not do</h2>
  <ul>
    <li>No internet connection required after install.</li>
    <li>No analytics, no telemetry, no third-party SDKs.</li>
    <li>No phone number, email, or account.</li>
    <li>Source available; releases are reproducible.</li>
  </ul>

  <h2>What you're about to install</h2>
  <div class="panel">
    <div style="font-size:13px; color: var(--muted);">Version</div>
    <div class="mono">$versionName ($sizeMb MB)</div>
    <div style="height: 12px;"></div>
    <div style="font-size:13px; color: var(--muted);">SHA-256 (verify with the person who invited you)</div>
    <div class="mono">$sha256Pretty</div>
  </div>

  <p class="warn">
    Your browser will warn that this download is "from an unknown source."
    That's expected — Keystone is not on the Play Store. Allow the
    download, then allow installation when prompted.
  </p>

  <a class="button" href="/keystone.apk">I agree — download Keystone</a>

  <div class="footer">
    Served directly from the device that invited you, over your local
    network. No internet, no third-party server.
  </div>
</body>
</html>
""".trimIndent()
    }
}
