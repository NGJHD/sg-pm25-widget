# Release guide

How to build a new version of SG PM2.5 Widget and publish the APK on GitHub,
step by step. Commands are for **Windows**: type them into **PowerShell** or
**Command Prompt**, opened in the project folder `C:\GitHub\PSI-Widget`.

> Quick way to open one there: open the folder in File Explorer, click the
> address bar, type `powershell` and press Enter.

---

## Before you start (one-time)

This PC already has everything. If you ever move to another PC, you need:

| What | Check with | This PC |
|---|---|---|
| Java 17 or newer | `java -version` | Temurin 21 |
| Android SDK | `local.properties` says `sdk.dir=...` | `C:\Users\ngjhd\AppData\Local\Android\Sdk` |
| Git | `git --version` | yes |
| GitHub CLI, signed in | `gh auth status` | signed in as NGJHD |
| **The signing key** | see below | yes |

### The signing key: back it up

Two files, both deliberately **kept out of git**:

- `C:\Users\ngjhd\keystores\sgpm25-release.jks`: the key itself
- `C:\GitHub\PSI-Widget\keystore.properties`: its passwords

**Back up both** (a USB stick or a password manager). Every update must be
signed with this same key. Without it, a new version **will not install over
the old one**: you would have to uninstall, which removes your widgets. It is
also the key registered for Android developer verification
(SHA-256 `AE:67:FA:5B:2F:67:A8:BB:2B:F9:C4:2C:F6:C9:1B:41:33:9C:5F:34:56:0C:03:6C:CA:90:5E:12:49:C2:65:2C`).

Never commit these two files and never paste the passwords anywhere public.

---

## Making a release

### 1. Make your changes

Edit whatever you want. To try a change on your phone first, do steps 2-3,
then step 6.

### 2. Bump the version

Open `app\build.gradle.kts` and find:

```kotlin
versionCode = 7
versionName = "1.5"
```

- **`versionCode`: always add 1** (7 -> 8). Android refuses to install an
  APK whose versionCode is lower than the installed one, so forgetting this
  is the most common reason an update "will not install".
- **`versionName`**: what people see, e.g. `"1.6"`. Your choice.

### 3. Build the APK

```
gradlew.bat :app:assembleRelease
```

This takes a minute or two. It must end with **`BUILD SUCCESSFUL`**. The APK
is then at:

```
app\build\outputs\apk\release\app-release.apk
```

If you only see `app-release-unsigned.apk` there, the key was not found:
see Troubleshooting.

Optional, but recommended. This runs the automatic checks (region maths,
colour bands, the X:20 schedule):

```
gradlew.bat :app:testDebugUnitTest
```

### 4. Save your changes to GitHub

```
git add -A
git commit -m "v1.6: what you changed"
git push
```

`git status` should list only your own changes. If `keystore.properties` or
any `.jks` file ever shows up there, **stop**: do not commit it.

### 5. Publish the release

Copy the APK to a nicer name, then create the release (replace `1.6`
everywhere with your `versionName`):

```
copy app\build\outputs\apk\release\app-release.apk sg-pm25-widget-v1.6.apk
gh release create v1.6 sg-pm25-widget-v1.6.apk --title "v1.6" --notes "What changed in this version."
del sg-pm25-widget-v1.6.apk
```

That's it. The release is at
<https://github.com/NGJHD/sg-pm25-widget/releases>.

**Without the command line:** open that page, click **Draft a new release**,
type a tag like `v1.6` (choose "Create new tag"), drag the APK into the
"Attach binaries" box, and click **Publish release**.

### 6. Install it on the phone

Either:

- **On the phone:** open the releases page, download the `.apk`, open it, and
  allow your browser or file manager to install unknown apps if asked. It
  installs over the old version and keeps your widgets.
- **Over USB** (USB debugging on):
  ```
  C:\Users\ngjhd\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\release\app-release.apk
  ```

---

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| **"App not installed"** / "package conflicts" on the phone | (a) `versionCode` was not raised: bump it (step 2) and rebuild. (b) The installed app was signed with a different key, e.g. a **debug** build (`assembleDebug`). Debug and release builds cannot replace each other; uninstall first (this removes your widgets). |
| Only `app-release-unsigned.apk` is produced | `keystore.properties` is missing or points to the wrong place. It must be in the project folder and contain `storeFile=C:/Users/ngjhd/keystores/sgpm25-release.jks` (forward slashes) plus the passwords. Restore it from your backup. |
| `BUILD FAILED` mentioning Java / `JAVA_HOME` | Install Java 17+ (Eclipse Temurin) and reopen the terminal. |
| `BUILD FAILED` with `e: ...` lines | A code error. The `e:` line gives the file and line number. |
| `SDK location not found` | `local.properties` is missing. Create it containing `sdk.dir=C\:\\Users\\ngjhd\\AppData\\Local\\Android\\Sdk` |
| `gh: command not found` or "not logged in" | Install GitHub CLI (<https://cli.github.com>), then run `gh auth login`. Or use the website route in step 5. |
| `gh release create` says the tag already exists | That version was already released. Bump `versionName` (and `versionCode`), or delete the old release on GitHub first. |
| Phone blocks the install as "unverified developer" | The package `com.ngjhd.sgpm25widget` must be registered in the Android developer console with the key above. Installing over USB (`adb install`) is not affected. |
