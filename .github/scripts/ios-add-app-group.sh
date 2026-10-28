#!/bin/bash
# Gives the app and its extensions in an unsigned archive the App Group of the BG widget (2026-10-28).
#
# Usage: ios-add-app-group.sh path/to/App.xcarchive
#
# The workflow archives with CODE_SIGNING_ALLOWED=NO (see the archive step for why), and an unsigned
# archive carries no entitlements. The export step signs, and it keeps only the entitlements it finds in
# the archive that the provisioning profile also allows, so without this the App Group would be dropped
# and the widget would never see a value. This signs every bundle ad hoc ("-", no certificate) with the
# App Group; the export then replaces these signatures with the real one.
#
# The group is "group." plus the app id, as `WidgetBgStore.groupId` in the app expects. It must also be
# set up in the Apple developer portal, or the export fails because the profile does not allow it.
set -euo pipefail

ARCHIVE="$1"
APP=$(find "$ARCHIVE/Products/Applications" -maxdepth 1 -name "*.app" | head -n 1)
if [ -z "$APP" ]; then
  echo "::error::No app found in $ARCHIVE"
  exit 1
fi
APP_ID=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$APP/Info.plist")
ENTITLEMENTS="$(mktemp -d)/appgroup.entitlements"
cat > "$ENTITLEMENTS" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>com.apple.security.application-groups</key>
  <array>
    <string>group.${APP_ID}</string>
  </array>
</dict>
</plist>
PLIST

# Inside out: codesign refuses a bundle whose nested code is not signed. Frameworks get no entitlements.
find "$APP" -depth \( -name "*.framework" -o -name "*.dylib" \) -print0 | while IFS= read -r -d '' code; do
  codesign --force --sign - "$code"
done
if [ -d "$APP/PlugIns" ]; then
  for extension in "$APP"/PlugIns/*.appex; do
    [ -e "$extension" ] || continue
    codesign --force --sign - --entitlements "$ENTITLEMENTS" "$extension"
  done
fi
codesign --force --sign - --entitlements "$ENTITLEMENTS" "$APP"

echo "App Group group.${APP_ID} added to $(basename "$APP") and its extensions:"
codesign -d --entitlements - "$APP" 2>/dev/null | grep -A3 "application-groups" || true
