#!/usr/bin/env bash
set -euo pipefail
export PYTHONUTF8=1
export GIT_TERMINAL_PROMPT=0

# Publish the already-built release APK to Gitee.
#
# Usage: scripts/publish-gitee.sh [owner/repo]
#
# Setup (one time):
#   1. create a public repository on Gitee (real-name verification required)
#   2. git remote add gitee https://gitee.com/<owner>/<repo>.git
#      (store credentials in .git-credentials or use an SSH remote)
#   3. write a Gitee personal access token with the `projects` scope to
#      .gitee-token (git-ignored)
#
# The script pushes the current commit and tag, creates the Gitee Release if
# needed, replaces an existing asset with the same name, and uploads the APK.
# It is safe to re-run.
#
# Auth: GITEE_TOKEN env var, or .gitee-token at the repo root.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="${1:-${GITEE_REPO:-newbeezzc/qinghe-ledger}}"
if [[ "$REPO" != */* ]]; then
    echo "Usage: scripts/publish-gitee.sh owner/repo" >&2
    exit 1
fi

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
if [[ -z "$VERSION" ]]; then
    echo "Cannot read versionName from app/build.gradle.kts" >&2
    exit 1
fi

APK="dist/qinghe-${VERSION}.apk"
if [[ ! -f "$APK" ]]; then
    echo "APK ${APK} not found. Run scripts/publish-github.sh first." >&2
    exit 1
fi

TOKEN="${GITEE_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$ROOT/.gitee-token" ]]; then
    TOKEN="$(tr -d '\r\n' < "$ROOT/.gitee-token")"
fi
if [[ -z "$TOKEN" ]]; then
    echo "No Gitee token. Set GITEE_TOKEN or write a personal access token to .gitee-token." >&2
    exit 1
fi

REMOTE="${GITEE_REMOTE:-gitee}"
BRANCH="${GITEE_BRANCH:-main}"
if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
    echo "Git remote '${REMOTE}' is not configured." >&2
    echo "Run: git remote add ${REMOTE} https://gitee.com/${REPO}.git" >&2
    exit 1
fi

TAG="v${VERSION}"
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null 2>&1; then
    COMMIT="$(git rev-list -n 1 "${TAG}")"
else
    COMMIT="$(git rev-parse HEAD)"
fi
API="https://gitee.com/api/v5/repos/${REPO}"
AUTH=(-H "Authorization: token ${TOKEN}")

echo "==> Pushing ${COMMIT} and ${TAG} to ${REMOTE}"
git push "$REMOTE" "HEAD:${BRANCH}"
if ! git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null 2>&1; then
    git tag "$TAG" "$COMMIT" >/dev/null
fi
git push "$REMOTE" "refs/tags/${TAG}"

echo "==> Finding or creating Gitee Release ${TAG}"
RELEASE_ID="$(curl -sS -m 30 "${AUTH[@]}" "$API/releases/tags/${TAG}" \
    | python -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || true)"

if [[ -z "$RELEASE_ID" ]]; then
    NOTES_FILE="$ROOT/docs/release-notes/${TAG}.md"
    RELEASE_JSON="$(python - "$VERSION" "$NOTES_FILE" "$COMMIT" <<'PY'
import json, os, sys
version, notes_path, commit = sys.argv[1:4]
if os.path.exists(notes_path):
    body = open(notes_path, encoding="utf-8").read()
else:
    body = f"青禾记账 v{version}"
print(json.dumps({
    "tag_name": f"v{version}",
    "name": f"v{version}",
    "body": body,
    "target_commitish": commit,
    "prerelease": False,
}, ensure_ascii=False))
PY
)"
    RESPONSE="$(curl -sS -m 60 --fail-with-body -X POST "${AUTH[@]}" \
        -H "Content-Type: application/json" \
        --data-binary "$RELEASE_JSON" \
        "$API/releases")" || {
        echo "Failed to create the Gitee Release." >&2
        exit 1
    }
    RELEASE_ID="$(printf '%s' "$RESPONSE" \
        | python -c "import sys,json; print(json.load(sys.stdin).get('id',''))")"
fi

if [[ -z "$RELEASE_ID" ]]; then
    echo "Could not determine the Gitee Release id." >&2
    exit 1
fi

ASSET_NAME="$(basename "$APK")"
echo "==> Replacing attachment ${ASSET_NAME} on release ${RELEASE_ID}"
EXISTING_ID="$(curl -sS -m 30 "${AUTH[@]}" "$API/releases/${RELEASE_ID}/attach_files" \
    | ASSET_NAME="$ASSET_NAME" python -c \
    "import os,sys,json; d=json.load(sys.stdin); print(next((a.get('id') for a in d if a.get('name')==os.environ['ASSET_NAME']),''))" \
    2>/dev/null || true)"
if [[ -n "$EXISTING_ID" ]]; then
    curl -sS -m 30 -X DELETE "${AUTH[@]}" \
        "$API/releases/${RELEASE_ID}/attach_files/${EXISTING_ID}" >/dev/null || true
fi

echo "==> Uploading ${APK}"
UPLOAD="$(curl -sS -m 300 --fail-with-body -X POST "${AUTH[@]}" \
    -F "file=@${APK}" \
    "$API/releases/${RELEASE_ID}/attach_files")" || {
    echo "Failed to upload the APK to Gitee." >&2
    exit 1
}

printf '%s' "$UPLOAD" | python -c "
import json, sys
d = json.load(sys.stdin)
print('    uploaded', d.get('name', ''), d.get('size', ''), d.get('browser_download_url', ''))
"

EXPECTED_SIZE="$(stat -c%s "$APK")"
UPLOADED_SIZE="$(printf '%s' "$UPLOAD" | python -c "import sys,json; print(json.load(sys.stdin).get('size',0))")"
if [[ "$UPLOADED_SIZE" != "0" && "$UPLOADED_SIZE" != "$EXPECTED_SIZE" ]]; then
    echo "Uploaded size ${UPLOADED_SIZE} does not match local ${EXPECTED_SIZE}." >&2
    exit 1
fi

echo
echo "Published v${VERSION} to Gitee (${REPO})"
echo "App Gitee update source: ${REPO}"
