# Keystone — F-Droid Release Process

F-Droid is Keystone's canonical Android distribution channel. It is
the only major Android app store consistent with the project's
anti-platform posture: F-Droid does not require a Google account,
does not link installs to a real identity, and builds every listed
app from source in their own infrastructure.

This document is for the Keystone maintainer cutting a release. It
covers (1) what F-Droid expects, (2) the deviations from F-Droid's
reproducible-build rules that Keystone currently ships with and
how we plan to close them, and (3) the step-by-step release
checklist.

## 1. What F-Droid expects

Two artefacts:

1. **The build recipe** at `metadata/com.keystone.yml`. This file
   is drafted in this repo and submitted as a PR to
   [`f-droid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata).
   F-Droid's CI uses it to build Keystone in their sandbox.

2. **Fastlane metadata** at `fastlane/metadata/android/en-US/`:
   - `title.txt` — store title.
   - `short_description.txt` — one-line tagline.
   - `full_description.txt` — long-form description.
   - `changelogs/<versionCode>.txt` — per-release changelog.
   - `images/` — graphic/icon/screenshots (deferred until UI freeze).

F-Droid pulls these directly from this repo at the tagged commit.
The submission PR points at the tag; F-Droid handles the rest.

## 2. Reproducible-build deviations

F-Droid's strongest guarantee is that the APK they distribute was
built bit-for-bit from the source they show you. Keystone fails this
check today because two of our dependencies arrive as pre-built
native binaries:

| Dependency | What's pre-built | Impact |
|---|---|---|
| `com.goterl:lazysodium-android:5.1.0@aar` | `libsodium.so` for arm64-v8a and x86_64 | We can't reproduce the libsodium binary from F-Droid's build sandbox. |
| `io.matthewnelson.kmp-tor:resource-exec-tor:408.13.2` | The `tor` binary itself, per architecture | Same — tor's reproducible-build pipeline is upstream and we don't run it. |
| `io.matthewnelson.kmp-tor:resource-noexec-tor:408.13.2` | The `-noexec` shim | Same. |

All three are open-source and the sources are published, but the
build pipelines are upstream's and we currently consume the
pre-built AARs from Maven Central / JitPack.

### Plan to close the gap

For the first listing we **accept the deviation** and document it
above. F-Droid will publish Keystone but the reproducible-build
badge will be missing.

To earn the badge later we have two paths, in order of effort:

- **Vendor and rebuild libsodium.** Stop pulling `lazysodium-android`
  as an AAR; vendor lazysodium's Java/Kotlin source, build
  `libsodium.so` from upstream tarball in our Gradle build via NDK.
  Cost: one new NDK module, ~200 lines of CMake glue, F-Droid CI
  becomes slow.
- **Vendor and rebuild tor.** Same shape but harder. tor's reproducible
  build pipeline already exists upstream; we'd need to call into it
  from F-Droid's sandbox. The kmp-tor maintainer publishes detached
  signatures for the binaries — using those plus a vendored copy of
  `tor.tar.xz` is the cheapest path.

Tracked as a separate effort in NEXT-STEPS.md §9.

## 3. Release checklist

For every release Keystone ships to F-Droid:

1. **Bump the version.** Edit `app/build.gradle.kts`:
   - `versionCode` — strictly increasing integer. Convention:
     versionCode N == sprint number (v0.1.0=1, v0.2=2, … v0.6.7=7).
   - `versionName` — semver. F-Droid displays this to users.

2. **Add a changelog entry.** Write
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
   This is what the F-Droid client shows on the update screen. Keep
   it to ~5 lines, focused on user-visible changes.

3. **Update `CurrentVersion` / `CurrentVersionCode` in
   `metadata/com.keystone.yml`** to match. Add a new `Builds:` entry
   with the new `versionName` / `versionCode` / `commit: vN.N.N`.

4. **Run the full build.** From the repo root:
   ```
   ./gradlew :app:assembleRelease \
       -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2
   ```
   Verify the APK is signed (`apksigner verify --print-certs ...`),
   note the size delta, install on a phone, smoke-test the core
   flow (handshake → message → sync).

5. **Tag and push.** The tag *must* match the `Builds: commit:` field:
   ```
   git tag -a v0.6.7 -m "v0.6.7 — F-Droid release plumbing"
   git push origin v0.6.7
   ```

6. **Submit the fdroiddata PR.**
   - Fork `f-droid/fdroiddata` on GitLab.
   - Copy `metadata/com.keystone.yml` from this repo into
     `fdroiddata/metadata/com.keystone.yml`.
   - Open a PR. F-Droid's CI runs the build in their sandbox and
     comments on the PR with the diff. Address any feedback.

7. **Document the release** by committing the version bump to
   `main` (or `android`) with a one-line subject like
   `v0.6.7 — F-Droid release plumbing`.

## 4. Why not Google Play

We will never ship to the Play Store. Reasons:

- **Developer account.** Google Play requires a real identity tied to
  a billing account. Keystone's threat model assumes the developer
  may be a target — and the developer's identity must not be
  required to ship the binary.
- **Play Integrity.** Google's hardware attestation gates apps and
  exposes a fingerprint of the device to the app. Keystone refuses
  to participate in remote attestation as a user.
- **In-app review / takedown risk.** Play can pull the app on a
  trademark complaint, a policy "violation" related to encryption
  in countries Google does business in, or any other ground. F-Droid
  cannot be removed in the same way because its repo is mirrored by
  every running client.

Distribution paths Keystone supports:

| Path | Tier | Who hosts |
|---|---|---|
| F-Droid | 1 | f-droid.org + ~50 mirrors |
| F-Droid community mirror | 1 | Any HTTPS host willing to mirror |
| Peer share (`feature:onboarding/share/`) | 2 | An already-onboarded peer |
| Tor onion mirror | 3 (future) | Any peer running embedded Tor |
| Direct APK download from GitHub releases | 4 (fallback) | github.com — assumes attacker hasn't hijacked the cert |

The onion-mirror path is the answer for jurisdictions where every
public distribution channel is hostile — fetch the signed APK
through the same embedded Tor we already ship.
