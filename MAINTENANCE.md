# ABB maintenance guide

## Repository layout and branch policy

- `stable` is the maintained snapshot branch.
- `fix/robust-app-launch` is the working branch for ongoing changes.
- Do not develop directly on `stable`. Review and test a change on the working branch first, then merge or fast-forward it into `stable`.
- Keep each functional change in a separate commit. To undo a bad change, revert that commit instead of rewriting history.

## Build and test

Every push and manual workflow run executes:

1. JVM unit tests (`gradle test`).
2. Release APK compilation (`gradle assembleRelease`).
3. APK existence check.
4. APK upload as the `abb-release` workflow artifact.

A normal branch build does **not** publish or overwrite a GitHub Release. Only a version tag beginning with `v` publishes a release APK. This prevents routine development builds from silently replacing the stable download.

There is currently a small unit-test suite for Android-user-specific app identity keys. Add regression tests for every new pure logic function and run the full workflow before promoting a change.

## Protecting the stable branch

The repository currently has no server-enforced branch protection rules. In GitHub, open **Settings → Branches → Add branch protection rule**, target `stable`, then enable **Require a pull request before merging**, **Require status checks to pass before merging** (select the `apk` job after its first successful run), and **Block force pushes**. The connector used for this maintenance work can inspect branch protection but cannot create the rule, so this one setting must be completed in GitHub's web interface.

The workflow also cancels older in-progress builds on the same ref when a newer commit arrives, avoiding a queue of obsolete APK builds.

## Permanent Android signing key — critical

The previous workflow used the GitHub runner's generated debug signing key. GitHub-hosted runners are temporary, so that key is not a reliable long-term app identity. A new permanent upload key must be configured before publishing the next stable APK.

1. Generate a private keystore on a trusted computer (example: `keytool -genkeypair -v -keystore abb-upload.jks -storetype JKS -alias abb-upload -keyalg RSA -keysize 2048 -validity 10000`). Keep at least two encrypted backups in separate safe locations. Never commit the keystore, passwords, or Base64 text to this repository. On Linux, produce the secret value with `base64 -w 0 abb-upload.jks`; on macOS use `base64 < abb-upload.jks | tr -d '\\n'`; on Windows PowerShell use `[Convert]::ToBase64String([IO.File]::ReadAllBytes('abb-upload.jks'))`.
2. In GitHub, open **Settings → Secrets and variables → Actions → New repository secret**.
3. Add these four repository secrets:
   - `SIGNING_KEYSTORE_BASE64`: Base64 encoding of the complete keystore file.
   - `SIGNING_STORE_PASSWORD`: keystore password.
   - `SIGNING_KEY_ALIAS`: key alias.
   - `SIGNING_KEY_PASSWORD`: key password.
4. Verify the keystore backup can be restored before relying on it. Losing this key can prevent future APKs from updating an installed copy.

For a tagged release, the workflow refuses to proceed if any signing secret is missing. Development-branch builds can still be produced with the debug key, but they are test builds and must not be treated as update-compatible release APKs.

**Upgrade warning:** the old `v1.0.0` APK was built with a runner-generated debug key. The permanent key configured later will have a different certificate. Android will reject an in-place update across different certificates; installing the permanently signed build may require uninstalling the old app first, which can erase app data. Back up anything important before that transition.

## Publishing a stable version

1. Merge the tested code into `stable`.
2. Update the Android `versionCode` and `versionName` for the new release. Never reuse a version code.
3. Confirm all four signing secrets are configured.
4. Create and push a new version tag, for example `v1.0.1`, pointing at the reviewed stable commit.
5. Wait for the tag-triggered workflow to finish successfully.
6. Check the release page and install-test the exact APK on the target Android device before calling it stable.

Do not overwrite an existing version tag or reuse a release number.

## Recovery

- If a new code change breaks the app, revert the offending commit on the working branch and let CI build it again.
- If a release is defective, do not silently replace the APK under an already-published version tag. Prepare a corrected commit and publish a new patch version.
- Keep the last known-good release and its source tag available.
- Never delete the only copy of the signing keystore or its backups.

## Device acceptance checklist

- App installs and opens on the target Android version.
- Shizuku permission flow works after the user taps Refresh.
- Cached left list appears immediately on startup.
- Refresh restores the cached list before the slower system scan.
- Multi-user entries remain distinct (`package:userId`).
- Launch, disable, whitelist, and refresh behavior still work.
- No periodic permission polling has been introduced.
