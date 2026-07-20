#!/bin/bash

set -e

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*"
}

short_sha=$(git rev-parse --short=8 "$CI_COMMIT_SHA")
if [[ -n "$CI_COMMIT_TAG" ]]; then
  short_sha="${short_sha}_${CI_COMMIT_TAG}"
fi

log "Commit Message: $CI_COMMIT_MESSAGE"

# 判断部署服务
if [[ "$CI_COMMIT_MESSAGE" == *"/fullnode"* ]]; then
  SERVICE="FullNode"
else
  log "无需部署，无匹配关键词（/fullnode）"
  exit 0
fi

log "开始部署...$SERVICE:$short_sha.jar"

cd /data/${SERVICE}/

aws s3 cp "s3://tronlink-dev/backend/tronlink-FullNode/${SERVICE}_$short_sha.zip" "${SERVICE}_$short_sha.zip"

unzip -o "${SERVICE}_$short_sha.zip"

mv -f "${SERVICE}:$short_sha.jar" "${SERVICE}.jar"

sudo /usr/local/bin/supervisorctl restart fullnode

log "等待启动...$SERVICE:$short_sha.jar"

rm -f "${SERVICE}_$short_sha.zip" "${SERVICE}:$short_sha.jar.asc"
