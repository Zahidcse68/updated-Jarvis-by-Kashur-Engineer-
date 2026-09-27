[app]

# (str) Title of your application
title = MARK LIV

# (str) Package name
package.name = markliv

# (str) Package domain (needed for android/ios packaging)
package.domain = org.kashurengineer

# (str) Source code where the main.py lives
source.dir = .

# (list) Source files to include (let empty to include all the files)
source.include_exts = py,png,jpg,kv,atlas,json,txt,obj,ico

# (list) List of directory to exclude
source.exclude_dirs = tests, bin, .venv, .git, .github

# (str) Application versioning (method 1)
version = 1.0.0

# (list) Application requirements
# comma separated e.g. requirements = sqlite3,kivy
requirements = python3,kivy,requests,websockets,numpy,android,certifi,urllib3

# (str) Supported orientation (one of landscape, sensorLandscape, portrait or all)
orientation = portrait

# -----------------------------------------------------------------------------
# Android specific

# (list) Permissions
android.permissions = INTERNET, RECORD_AUDIO, CAMERA, MODIFY_AUDIO_SETTINGS, ACCESS_NETWORK_STATE

# (int) Target Android API, should be as high as possible.
android.api = 33

# (int) Minimum API your APK will support.
android.minapi = 21

# (list) List of Java .jar files to add to the libs so that pyjnius can access
# their classes. Don't add jars that you do not need, since each jar adds
# a lot to the size of the final APK.
# android.add_jars = foo.jar

# (str) The Android arch to build for, choices: armeabi-v7a, arm64-v8a, x86, x86_64
android.archs = arm64-v8a, armeabi-v7a

# (bool) enables Android auto backup feature (Android API >=23)
android.allow_backup = True

# (bool) If True, then skip trying to update the Android sdk
# This can be useful to avoid excess Internet downloads or save time
# when an update is due and you already have the correct SDK.
android.skip_update = False

# (bool) If True, then automatically accept SDK license
# agreements. This is intended for automation only.
android.accept_sdk_license = True

# -----------------------------------------------------------------------------
# Buildozer settings

[buildozer]

# (int) Log level (0 = error only, 1 = info, 2 = debug (with command output))
log_level = 2

# (int) Display warning if buildozer is run as root (0 = False, 1 = True)
warn_on_root = 1
