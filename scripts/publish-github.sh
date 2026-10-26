#!/usr/bin/env bash
set -euo pipefail

# Build a release APK and publish it to GitHub.
#
# Usage: scripts/publish-github.sh [owner/repo]
#
# Steps:
#   1. clean build the release APK (R8, ~2.7 MB)
#   2. copy it to dist/qinghe-<version>.apk and remove older dist APKs
#   3. update the SHA-256 line in README.md
#   4. git add -A, commit, and push to origin
#   5. create the GitHub Release v<version> with the APK attached
#
# The commit/push step is automatic: publishing is the "save point" for updates.
#
# Auth: uses `gh` when installed; otherwise reads GITHUB_TOKEN / GH_TOKEN, or a
# local `.github-token` file (git-ignored).

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="${1:-newbeezzc/qinghe-ledger}"
if [[ "$REPO" != */* ]]; then
    echo "Usage: scripts/publish-github.sh owner/repo" >&2
    exit 1
fi

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
if [[ -z "$VERSION" ]]; then
    echo "Cannot read versionName from app/build.gradle.kts" >&2
    exit 1
fi

echo "==> Building release v${VERSION} (versionCode ${VERSION_CODE})"
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
    ./gradlew.bat clean :app:assembleRelease
else
    ./gradlew clean :app:assembleRelease
fi

mkdir -p dist
find dist -maxdepth 1 -name 'qinghe-*.apk' ! -name "qinghe-${VERSION}.apk" -delete
APK="dist/qinghe-${VERSION}.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

SIZE_MB="$(du -m "$APK" | cut -f1)"
if [[ "$SIZE_MB" -gt 10 ]]; then
    echo "APK is ${SIZE_MB} MB, expected ~3 MB. Aborting (likely a dirty incremental packaging)." >&2
    exit 1
fi

SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
echo "==> APK ${APK} (${SIZE_MB} MB) sha256=${SHA}"

# Update README: versioned APK paths, current version line, and the SHA block.
python - "$VERSION" "$SHA" <<'PY'
import re, sys
version, sha = sys.argv[1], sys.argv[2]
path = "README.md"
text = open(path, encoding="utf-8").read()
text = re.sub(r"qinghe-\d+\.\d+\.\d+\.apk", f"qinghe-{version}.apk", text)
text = re.sub(r"v\d+\.\d+\.\d+ · release", f"v{version} · release", text)
text = re.sub(r"(?m)^[0-9a-f]{64}$", sha, text)
open(path, "w", encoding="utf-8", newline="\n").write(text)
PY

echo "==> Committing and pushing"
git add -A
if git diff --cached --quiet; then
    echo "    nothing to commit"
else
    git commit -m "release: v${VERSION}" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
fi
git push origin HEAD

echo "==> Publishing GitHub Release v${VERSION}"
NOTES="青禾记账 v${VERSION}"
if command -v gh >/dev/null 2>&1; then
    if gh release view "v${VERSION}" --repo "$REPO" >/dev/null 2>&1; then
        gh release upload "v${VERSION}" "$APK" --repo "$REPO" --clobber
    else
        gh release create "v${VERSION}" "$APK" --repo "$REPO" --title "v${VERSION}" --notes "$NOTES"
    fi
else
    TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
    if [[ -z "$TOKEN" && -f "$ROOT/.github-token" ]]; then
        TOKEN="$(tr -d '\r\n' < "$ROOT/.github-token")"
    fi
    if [[ -z "$TOKEN" ]]; then
        echo "No GitHub token. Install gh, or set GITHUB_TOKEN, or write a PAT to .github-token." >&2
        exit 1
    fi
    API="https://api.github.com/repos/$REPO"
    UPLOADS="https://uploads.github.com/repos/$REPO"
    RELEASE_ID="$(curl -sS -H "Authorization: Bearer $TOKEN" "$API/releases/tags/v${VERSION}" \
        | python -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || true)"
    if [[ -z "$RELEASE_ID" ]]; then
        RELEASE_ID="$(python - "$VERSION" "$NOTES" <<'PY' | curl -sS -X POST \
            -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
            --data-binary @- "$API/releases" | python -c "import sys,json; print(json.load(sys.stdin).get('id',''))"
import json, sys
print(json.dumps({"tag_name": f"v{sys.argv[1]}", "name": f"v{sys.argv[1]}", "body": sys.argv[2]}))
PY
)"
    fi
    if [[ -z "$RELEASE_ID" ]]; then
        echo "Failed to create or find the release." >&2
        exit 1
    fi
    ASSET_NAME="$(basename "$APK")"
    EXISTING_ASSET_ID="$(curl -sS -H "Authorization: Bearer $TOKEN" "$API/releases/${RELEASE_ID}" \
        | ASSET_NAME="$ASSET_NAME" python -c "import os,sys,json; d=json.load(sys.stdin); print(next((a['id'] for a in d.get('assets',[]) if a['name']==os.environ['ASSET_NAME']),''))" 2>/dev/null || true)"
    if [[ -n "$EXISTING_ASSET_ID" ]]; then
        echo "    replacing existing asset ${ASSET_NAME}"
        curl -sS -X DELETE -H "Authorization: Bearer $TOKEN" "$API/releases/assets/${EXISTING_ASSET_ID}" >/dev/null
    fi
    if ! UPLOAD_RESULT="$(curl -sS --fail-with-body -X POST -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@${APK}" \
        "$UPLOADS/releases/${RELEASE_ID}/assets?name=${ASSET_NAME}")"; then
        echo "Asset upload failed:" >&2
        echo "$UPLOAD_RESULT" >&2
        exit 1
    fi
    UPLOADED_SIZE="$(printf '%s' "$UPLOAD_RESULT" | python -c "import sys,json; print(json.load(sys.stdin).get('size',0))")"
    LOCAL_SIZE="$(stat -c%s "$APK")"
    if [[ "$UPLOADED_SIZE" -ne "$LOCAL_SIZE" ]]; then
        echo "Upload size mismatch: local ${LOCAL_SIZE}, remote ${UPLOADED_SIZE}" >&2
        exit 1
    fi
    echo "    asset ${ASSET_NAME} (${UPLOADED_SIZE} bytes) uploaded"
fi

echo
echo "Published v${VERSION} to ${REPO}"
echo "App update source is built in (${REPO}); tap 检查更新 to install."
