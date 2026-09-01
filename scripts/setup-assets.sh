#!/bin/bash
#
# andClaw - 빌드 준비 스크립트 (assets 번들)
#
# Ubuntu arm64 rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 을
# install_time_assets/src/main/assets/ 에 배치한다.
#
# 필요 조건:
#   - Docker Desktop (arm64 에뮬레이션 지원)
#   - curl, tar
#
# 사용법:
#   chmod +x scripts/setup-assets.sh
#   ./scripts/setup-assets.sh
#
# 이 스크립트 실행 후 생성되는 파일:
#   install_time_assets/src/main/assets/
#     rootfs.tar.gz.bin                     (~30MB)   Ubuntu 24.04 arm64 base
#     node-arm64.tar.gz.bin                 (~25MB)   Node.js 24 arm64 linux
#     system-tools-arm64.tar.gz.bin         (~80-100MB) git, curl, python3, 시스템 libs
#     openclaw/                             OpenClaw 파일 트리 (증분 업데이트 최적화)
#     playwright-chromium-arm64.tar.gz.bin  (~150-180MB) Chromium headless_shell
#
# 업데이트 시 변경: openclaw/ 디렉토리만 갱신하면 됨
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_DIR/install_time_assets/src/main/assets"

# URLs & Versions
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
NODEJS_VERSION="v24.18.0"
NODEJS_URL="https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz"
PLAYWRIGHT_VERSION="1.49.1"
BUNDLE_FINGERPRINT_NEEDS_UPDATE="false"
FORCE_OPENCLAW_REBUILD="${FORCE_OPENCLAW_REBUILD:-false}"

echo "============================================"
echo "  andClaw - 빌드 준비 (assets 번들)"
echo "============================================"
echo ""

mkdir -p "$ASSETS_DIR"

# ══════════════════════════════════════════════
#  assets 번들
# ══════════════════════════════════════════════

# ── 1. Ubuntu rootfs 다운로드 ──
ROOTFS_FILE="$ASSETS_DIR/rootfs.tar.gz.bin"
if [ -f "$ROOTFS_FILE" ]; then
    echo "[1/6] rootfs.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$ROOTFS_FILE" | cut -f1)"
else
    echo "[1/6] Ubuntu 24.04 arm64 rootfs 다운로드 중..."
    echo "   URL: $ROOTFS_URL"
    curl -fSL "$ROOTFS_URL" -o "$ROOTFS_FILE"
    echo "   완료: $(du -h "$ROOTFS_FILE" | cut -f1)"
fi

# ── 2. Node.js 다운로드 ──
NODEJS_FILE="$ASSETS_DIR/node-arm64.tar.gz.bin"
NODEJS_ASSET_VERSION="$(tar -tzf "$NODEJS_FILE" 2>/dev/null | head -n 1 | sed -n 's#^node-\(v[0-9][^/]*\)-linux-arm64/$#\1#p' || true)"
if [ -f "$NODEJS_FILE" ] && [ "$NODEJS_ASSET_VERSION" = "$NODEJS_VERSION" ]; then
    echo "[2/6] node-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   버전: $NODEJS_ASSET_VERSION"
    echo "   크기: $(du -h "$NODEJS_FILE" | cut -f1)"
else
    if [ -f "$NODEJS_FILE" ]; then
        echo "[2/6] Node.js 버전 불일치 감지, 재다운로드"
        echo "   현재: ${NODEJS_ASSET_VERSION:-unknown}"
        echo "   목표: $NODEJS_VERSION"
        rm -f "$NODEJS_FILE"
    fi
    echo "[2/6] Node.js $NODEJS_VERSION arm64 다운로드 중..."
    echo "   URL: $NODEJS_URL"
    curl -fSL "$NODEJS_URL" -o "$NODEJS_FILE"
    echo "   완료: $(du -h "$NODEJS_FILE" | cut -f1)"
    BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"
fi

# ── Docker 필수 확인 ──
check_docker() {
    if ! command -v docker &>/dev/null; then
        echo "   ERROR: Docker가 필요합니다"
        exit 1
    fi

    if ! docker info >/dev/null 2>&1; then
        echo "   ERROR: Docker 데몬에 연결할 수 없습니다"
        exit 1
    fi

    if ! docker run --rm --platform linux/arm64 ubuntu:24.04 true >/dev/null 2>&1; then
        echo "   arm64 Docker 에뮬레이션이 없어 binfmt 설치를 시도합니다..."
        docker run --privileged --rm tonistiigi/binfmt --install arm64 >/dev/null

        if ! docker run --rm --platform linux/arm64 ubuntu:24.04 true >/dev/null 2>&1; then
            echo "   ERROR: linux/arm64 Docker 실행에 실패했습니다 (binfmt/QEMU 확인 필요)"
            exit 1
        fi
    fi
}

# ── 3. 시스템 도구 번들 (Docker Build 1) ──
TOOLS_FILE="$ASSETS_DIR/system-tools-arm64.tar.gz.bin"
if [ -f "$TOOLS_FILE" ]; then
    echo "[3/6] system-tools-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$TOOLS_FILE" | cut -f1)"
else
    echo "[3/6] 시스템 도구 번들 빌드 중 (Docker)..."
    echo "   git, curl, python3, 시스템 libs, Chromium deps 포함"
    check_docker

    docker rm -f andclaw-tools-builder 2>/dev/null || true

    docker run --platform linux/arm64 --name andclaw-tools-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        echo '--- apt-get update ---'
        apt-get update -qq

        # libs 스냅샷 (설치 전)
        echo '--- Snapshot libs before installs ---'
        find /usr/lib -type f -o -type l 2>/dev/null | sort > /tmp/libs-before.txt

        echo '--- Installing core tools ---'
        apt-get install -y -qq --no-install-recommends \\
            curl wget git ca-certificates \\
            sqlite3 \\
            python3 python3-pip python3-venv \\
            openssh-client rsync \\
            jq zip unzip \\
            less vim-tiny \\
            coreutils findutils procps \\
            iproute2 iputils-ping dnsutils net-tools \\
            file diffutils patch

        git --version
        python3 --version
        curl --version | head -1

        # Chromium 시스템 의존성
        echo '--- Installing Chromium system dependencies ---'
        apt-get install -y -qq --no-install-recommends \\
            libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \\
            libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \\
            libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libasound2t64 \\
            libx11-6 libxcb1 libxext6 libdbus-1-3 fonts-liberation \\
            libatspi2.0-0 libxcursor1 libxi6 libxtst6

        echo '--- Snapshot libs after installs ---'
        find /usr/lib -type f -o -type l 2>/dev/null | sort > /tmp/libs-after.txt
        comm -13 /tmp/libs-before.txt /tmp/libs-after.txt > /tmp/new-libs.txt
        echo \"New libs count: \$(wc -l < /tmp/new-libs.txt)\"

        # 바이너리 목록 생성
        echo '--- Collecting binaries ---'
        > /tmp/bin-list.txt
        for bin in \\
            curl wget \\
            git git-receive-pack git-upload-archive git-upload-pack \\
            sqlite3 \\
            python3 pip3 python3-config \\
            ssh scp sftp ssh-keygen ssh-keyscan \\
            rsync \\
            jq zip unzip \\
            less vim.tiny \\
            ls dir vdir cp mv rm mkdir rmdir chmod chown chgrp ln cat \\
            head tail sort uniq wc cut tr tee paste \\
            grep egrep fgrep sed awk \\
            find xargs locate \\
            tar gzip gunzip bzip2 \\
            basename dirname readlink realpath \\
            date touch stat file md5sum sha256sum \\
            diff patch openssl \\
            ps kill top free \\
            ip ss ping nslookup dig host netstat \\
            id whoami hostname uname env printenv \\
            expr test true false sleep seq dd \\
        ; do
            for dir in /usr/bin /usr/local/bin /bin /usr/sbin /sbin; do
                if [ -e \"\$dir/\$bin\" ]; then
                    echo \"\${dir#/}/\$bin\" >> /tmp/bin-list.txt
                    break
                fi
            done
        done
        sort -u -o /tmp/bin-list.txt /tmp/bin-list.txt

        # 심볼릭 링크 바이너리의 실제 타겟도 함께 포함한다.
        # 예: usr/bin/python3 -> python3.12 인 경우 usr/bin/python3.12 누락 방지
        > /tmp/bin-symlink-targets.txt
        while IFS= read -r rel_path; do
            [ -n \"\$rel_path\" ] || continue
            abs_path=\"/\$rel_path\"
            if [ ! -L \"\$abs_path\" ]; then
                continue
            fi

            link_target=\$(readlink \"\$abs_path\" 2>/dev/null || true)
            if [ -n \"\$link_target\" ]; then
                case \"\$link_target\" in
                    /*) target_path=\"\$link_target\" ;;
                    *) target_path=\"\$(dirname \"\$abs_path\")/\$link_target\" ;;
                esac
                if [ -e \"\$target_path\" ]; then
                    echo \"\${target_path#/}\" >> /tmp/bin-symlink-targets.txt
                fi
            fi

            resolved_path=\$(readlink -f \"\$abs_path\" 2>/dev/null || true)
            if [ -n \"\$resolved_path\" ] && [ -e \"\$resolved_path\" ]; then
                echo \"\${resolved_path#/}\" >> /tmp/bin-symlink-targets.txt
            fi
        done < /tmp/bin-list.txt

        if [ -s /tmp/bin-symlink-targets.txt ]; then
            sort -u -o /tmp/bin-symlink-targets.txt /tmp/bin-symlink-targets.txt
            cat /tmp/bin-symlink-targets.txt >> /tmp/bin-list.txt
            sort -u -o /tmp/bin-list.txt /tmp/bin-list.txt
        fi

        echo \"Binaries to bundle: \$(wc -l < /tmp/bin-list.txt)\"

        echo '--- Creating system-tools bundle ---'
        > /tmp/system-tools-include.raw
        for pattern in \\
            usr/lib/git-core \\
            usr/share/git-core \\
            usr/share/perl \\
            usr/lib/python3* \\
            usr/lib/python3 \\
            usr/share/python3 \\
            usr/share/fonts/truetype/liberation \\
            etc/ssl \\
            usr/lib/ssl \\
            usr/share/ca-certificates
        do
            for abs_path in /\$pattern; do
                [ -e "\$abs_path" ] || continue
                echo "\${abs_path#/}" >> /tmp/system-tools-include.raw
            done
        done
        cat /tmp/bin-list.txt >> /tmp/system-tools-include.raw
        sed 's|^/||' /tmp/new-libs.txt >> /tmp/system-tools-include.raw

        > /tmp/system-tools-include.txt
        while IFS= read -r rel_path; do
            [ -n \"\$rel_path\" ] || continue
            rel_path=\"\${rel_path#./}\"
            rel_path=\"\${rel_path#/}\"
            [ -n \"\$rel_path\" ] || continue
            [ -e \"/\$rel_path\" ] || continue
            echo \"\$rel_path\" >> /tmp/system-tools-include.txt
        done < /tmp/system-tools-include.raw
        sort -u -o /tmp/system-tools-include.txt /tmp/system-tools-include.txt

        echo \"system-tools include entries: \$(wc -l < /tmp/system-tools-include.txt)\"
        cd /
        tar --hard-dereference -czf /tmp/system-tools.tar.gz -T /tmp/system-tools-include.txt
        ls -lh /tmp/system-tools.tar.gz
        echo '--- DONE ---'
    "

    docker cp andclaw-tools-builder:/tmp/system-tools.tar.gz "$TOOLS_FILE"
    docker rm andclaw-tools-builder

    echo "   완료: $(du -h "$TOOLS_FILE" | cut -f1)"
    BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"
fi

# ── 4. OpenClaw tar.gz 번들 (Docker Build 2) ──
OPENCLAW_TAR="$ASSETS_DIR/openclaw-arm64.tar.gz.bin"
OPENCLAW_CACHE_DIR="$PROJECT_DIR/install_time_assets/cache"
OPENCLAW_CACHE_ARCHIVE="$OPENCLAW_CACHE_DIR/openclaw-arm64.tar.gz.bin"
# 버전 확인을 위해 임시로 디렉토리를 사용 (캐시 복원 시)
OPENCLAW_TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$OPENCLAW_TEMP_DIR"' EXIT

extract_json_version() {
    local json_path="$1"
    if [ ! -f "$json_path" ]; then
        return 1
    fi
    sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$json_path" | head -1
}

get_openclaw_registry_latest_version() {
    curl -fsSL "https://registry.npmjs.org/openclaw/latest" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -1
}

get_codex_latest_version() {
    curl -fsSL "https://registry.npmjs.org/@openclaw%2Fcodex/latest" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -1
}

correction_release_base() {
    printf '%s\n' "$1" | sed 's/-[0-9][0-9]*$//'
}

openclaw_version_exists() {
    curl -fsSL "https://registry.npmjs.org/openclaw/$1" >/dev/null
}

restore_openclaw_from_cache() {
    if [ ! -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
        return 1
    fi

    echo "   OpenClaw 캐시 복원 시도: $OPENCLAW_CACHE_ARCHIVE"
    cp -f "$OPENCLAW_CACHE_ARCHIVE" "$OPENCLAW_TAR" 2>/dev/null || return 1
    [ -f "$OPENCLAW_TAR" ]
}

save_openclaw_to_cache() {
    if [ ! -f "$OPENCLAW_TAR" ]; then
        return 1
    fi

    mkdir -p "$OPENCLAW_CACHE_DIR"
    cp -f "$OPENCLAW_TAR" "$OPENCLAW_CACHE_ARCHIVE.tmp" 2>/dev/null || return 1
    if ! mv -f "$OPENCLAW_CACHE_ARCHIVE.tmp" "$OPENCLAW_CACHE_ARCHIVE" 2>/dev/null; then
        cp -f "$OPENCLAW_CACHE_ARCHIVE.tmp" "$OPENCLAW_CACHE_ARCHIVE" 2>/dev/null || true
        rm -f "$OPENCLAW_CACHE_ARCHIVE.tmp" 2>/dev/null || true
    fi

    if [ ! -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
        return 1
    fi
    echo "   OpenClaw 캐시 저장: $OPENCLAW_CACHE_ARCHIVE"
}

# tar.gz에서 package.json을 추출하여 버전을 읽는다
extract_openclaw_version_from_tar() {
    local tar_file="$1"
    if [ ! -f "$tar_file" ]; then
        return 1
    fi
    tar xzf "$tar_file" -C "$OPENCLAW_TEMP_DIR" \
        "usr/local/lib/node_modules/openclaw/package.json" 2>/dev/null || return 1
    local pkg_json="$OPENCLAW_TEMP_DIR/usr/local/lib/node_modules/openclaw/package.json"
    if [ -f "$pkg_json" ]; then
        extract_json_version "$pkg_json" 2>/dev/null
    else
        return 1
    fi
}


OPENCLAW_ASSET_VERSION=""
if [ -f "$OPENCLAW_TAR" ]; then
    OPENCLAW_ASSET_VERSION="$(extract_openclaw_version_from_tar "$OPENCLAW_TAR" 2>/dev/null || true)"
    rm -rf "$OPENCLAW_TEMP_DIR"/* 2>/dev/null || true
fi
OPENCLAW_REGISTRY_LATEST_VERSION="$(get_openclaw_registry_latest_version 2>/dev/null || true)"
CODEX_LATEST_VERSION="$(get_codex_latest_version 2>/dev/null || true)"
OPENCLAW_LATEST_VERSION=""
if [ -n "$OPENCLAW_REGISTRY_LATEST_VERSION" ] && [ -n "$CODEX_LATEST_VERSION" ]; then
    if [ "$OPENCLAW_REGISTRY_LATEST_VERSION" = "$CODEX_LATEST_VERSION" ]; then
        OPENCLAW_LATEST_VERSION="$OPENCLAW_REGISTRY_LATEST_VERSION"
    elif [ "$(correction_release_base "$OPENCLAW_REGISTRY_LATEST_VERSION")" = "$(correction_release_base "$CODEX_LATEST_VERSION")" ] &&
         openclaw_version_exists "$CODEX_LATEST_VERSION"; then
        OPENCLAW_LATEST_VERSION="$CODEX_LATEST_VERSION"
        echo "[4/6] Codex 호환 버전 선택: OpenClaw $OPENCLAW_REGISTRY_LATEST_VERSION 대신 $OPENCLAW_LATEST_VERSION"
    else
        echo "[4/6] ERROR: OpenClaw $OPENCLAW_REGISTRY_LATEST_VERSION 과 Codex $CODEX_LATEST_VERSION 의 호환 버전을 찾을 수 없습니다"
        exit 1
    fi
fi
OPENCLAW_BUILD_REASON=""
OPENCLAW_WAS_REBUILT="false"

if [ "$FORCE_OPENCLAW_REBUILD" = "true" ]; then
    OPENCLAW_BUILD_REASON="강제 재빌드 요청"
elif [ ! -f "$OPENCLAW_TAR" ]; then
    OPENCLAW_BUILD_REASON="tar.gz.bin 파일 없음"
elif [ -n "$OPENCLAW_ASSET_VERSION" ] && [ -n "$OPENCLAW_LATEST_VERSION" ] && [ "$OPENCLAW_ASSET_VERSION" != "$OPENCLAW_LATEST_VERSION" ]; then
    OPENCLAW_BUILD_REASON="호환 버전 동기화 필요 ($OPENCLAW_ASSET_VERSION -> $OPENCLAW_LATEST_VERSION)"
elif [ -z "$OPENCLAW_LATEST_VERSION" ]; then
    echo "[4/6] WARNING: openclaw 최신 버전 조회 실패, 기존 자산 버전을 유지합니다"
fi

if [ "$FORCE_OPENCLAW_REBUILD" != "true" ] && [ -n "$OPENCLAW_BUILD_REASON" ] && [ -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
    if restore_openclaw_from_cache; then
        OPENCLAW_ASSET_VERSION="$(extract_openclaw_version_from_tar "$OPENCLAW_TAR" 2>/dev/null || true)"
        rm -rf "$OPENCLAW_TEMP_DIR"/* 2>/dev/null || true
        OPENCLAW_BUILD_REASON=""
        BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"

        if [ -n "$OPENCLAW_ASSET_VERSION" ] && [ -n "$OPENCLAW_LATEST_VERSION" ] && [ "$OPENCLAW_ASSET_VERSION" != "$OPENCLAW_LATEST_VERSION" ]; then
            OPENCLAW_BUILD_REASON="캐시 호환 버전 동기화 필요 ($OPENCLAW_ASSET_VERSION -> $OPENCLAW_LATEST_VERSION)"
        else
            echo "   OpenClaw 캐시 복원 성공"
        fi
    fi
fi

if [ -z "$OPENCLAW_BUILD_REASON" ]; then
    echo "[4/6] openclaw-arm64.tar.gz.bin 이미 존재, 건너뜀"
    if [ -n "$OPENCLAW_ASSET_VERSION" ]; then
        echo "   버전: $OPENCLAW_ASSET_VERSION"
    fi
    if [ -n "$OPENCLAW_REGISTRY_LATEST_VERSION" ]; then
        echo "   레지스트리 최신: $OPENCLAW_REGISTRY_LATEST_VERSION"
    fi
    if [ -n "$OPENCLAW_LATEST_VERSION" ]; then
        echo "   Codex 호환 목표: $OPENCLAW_LATEST_VERSION"
    fi
    echo "   크기: $(du -h "$OPENCLAW_TAR" | cut -f1)"
else
    echo "[4/6] OpenClaw tar.gz 번들 빌드 중 (Docker)..."
    echo "   사유: $OPENCLAW_BUILD_REASON"
    check_docker

    OPENCLAW_INSTALL_SPEC="openclaw"
    if [ -n "$OPENCLAW_LATEST_VERSION" ]; then
        OPENCLAW_INSTALL_SPEC="openclaw@$OPENCLAW_LATEST_VERSION"
    fi
    echo "   npm install -g $OPENCLAW_INSTALL_SPEC"

    docker rm -f andclaw-openclaw-builder 2>/dev/null || true

    # 베이스 이미지 빌드/재사용 (apt + Node.js 사전 설치)
    BUILDER_IMAGE="andclaw-openclaw-base:node-$NODEJS_VERSION"
    if ! docker image inspect "$BUILDER_IMAGE" >/dev/null 2>&1; then
        echo "   베이스 이미지 빌드 중: $BUILDER_IMAGE"
        docker build --platform linux/arm64 \
            --build-arg NODEJS_VERSION=$NODEJS_VERSION \
            -t "$BUILDER_IMAGE" \
            -f "$SCRIPT_DIR/Dockerfile.openclaw-builder" \
            "$SCRIPT_DIR"
    else
        echo "   베이스 이미지 재사용: $BUILDER_IMAGE"
    fi

    docker run --platform linux/arm64 -i --name andclaw-openclaw-builder "$BUILDER_IMAGE" bash -c "
        set -e

        echo '--- Installing OpenClaw ---'
        # 일부 환경에서 node-gyp가 python 경로를 자동 감지하지 못해 명시적으로 지정한다.
        OPENCLAW_EAGER_BUNDLED_PLUGIN_DEPS=1 \
          PYTHON=/usr/bin/python3 \
          npm_config_python=/usr/bin/python3 \
          npm install -g $OPENCLAW_INSTALL_SPEC 2>&1

        echo '--- Verifying node:sqlite + FTS5 + sqlite-vec ---'
        cat > /tmp/verify-node-sqlite.cjs <<'NODE'
const { DatabaseSync } = require('node:sqlite');

const db = new DatabaseSync(':memory:', { allowExtension: true });
db.prepare(\"SELECT fts5('test') AS ok\").get();
db.exec('CREATE VIRTUAL TABLE IF NOT EXISTS andclaw_fts_probe USING fts5(text)');

const sqliteVec = require('/usr/local/lib/node_modules/openclaw/node_modules/sqlite-vec');
sqliteVec.load(db);
db.exec('CREATE VIRTUAL TABLE IF NOT EXISTS andclaw_vec_probe USING vec0(embedding float[3])');

console.log('node:sqlite/fts5/sqlite-vec probe: ok');
NODE
        node /tmp/verify-node-sqlite.cjs

        # 호환성: 일부 번들/의존성은 구 경로(_vendor/json-parser/json-parser.js)를 참조할 수 있다.
        # 최신 SDK는 _vendor/partial-json-parser/parser.js를 사용하므로 shim을 생성해 둘 다 동작하게 한다.
        for sdk_root in \
            /usr/local/lib/node_modules/openclaw/node_modules/@anthropic-ai/sdk \
            /usr/local/lib/node_modules/openclaw/extensions/memory-lancedb/node_modules/@anthropic-ai/sdk \
        ; do
            if [ -d \$sdk_root/_vendor/partial-json-parser ]; then
                mkdir -p \$sdk_root/_vendor/json-parser
                printf '%s\n' 'module.exports = require("../partial-json-parser/parser.js");' > \$sdk_root/_vendor/json-parser/json-parser.js
                printf '%s\n' 'export * from "../partial-json-parser/parser.mjs";' > \$sdk_root/_vendor/json-parser/json-parser.mjs
            fi
        done

        echo '--- Bundled plugin runtime dependencies: installed during asset build ---'

        echo '--- Installing andClaw-managed external/runtime plugins ---'
        OPENCLAW_ASSET_VERSION=\"\$(node -e 'console.log(require(\"/usr/local/lib/node_modules/openclaw/package.json\").version)')\"
        cat > /tmp/resolve-andclaw-plugin-specs.cjs <<'NODE'
const { execFileSync } = require('node:child_process');

const openClawVersion = process.env.OPENCLAW_ASSET_VERSION;
const correctionReleaseBase = (version) => version.replace(/-\d+$/, '');
const openClawBaseVersion = correctionReleaseBase(openClawVersion);
const packageNames = [
  '@openclaw/whatsapp',
  '@openclaw/discord',
  '@openclaw/codex',
  '@openclaw/brave-plugin',
  '@openclaw/zai-provider',
];

const specs = packageNames.map((packageName) => {
  const pluginVersion = execFileSync(
    'npm',
    ['view', packageName + '@latest', 'version'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] },
  ).trim();
  const pluginBaseVersion = correctionReleaseBase(pluginVersion);
  if (packageName === '@openclaw/codex') {
    if (pluginVersion !== openClawVersion) {
      throw new Error(
        packageName + ' latest version ' + pluginVersion +
          ' must exactly match version-bound OpenClaw host ' + openClawVersion,
      );
    }
  } else if (pluginBaseVersion !== openClawBaseVersion) {
    throw new Error(
      packageName + ' latest version ' + pluginVersion +
        ' does not match OpenClaw base version ' + openClawBaseVersion,
    );
  }
  console.error('--- andClaw plugin resolved: ' + packageName + '@' + pluginVersion + ' ---');
  return packageName + '@' + pluginVersion;
});

process.stdout.write(specs.join(' '));
NODE
        ANDCLAW_BUNDLED_PLUGINS=\"\$(OPENCLAW_ASSET_VERSION=\"\$OPENCLAW_ASSET_VERSION\" node /tmp/resolve-andclaw-plugin-specs.cjs)\"
        ANDCLAW_BUNDLED_PLUGIN_ROOT=/root/.openclaw/andclaw-bundled-plugins
        ANDCLAW_BUNDLED_PLUGIN_NPM_ROOT=/root/.openclaw/andclaw-bundled-plugins/npm
        rm -rf \"\$ANDCLAW_BUNDLED_PLUGIN_ROOT\"
        mkdir -p \"\$ANDCLAW_BUNDLED_PLUGIN_NPM_ROOT\"
        PYTHON=/usr/bin/python3 \
          npm_config_python=/usr/bin/python3 \
          npm install --prefix \"\$ANDCLAW_BUNDLED_PLUGIN_NPM_ROOT\" \
            --omit=dev --no-audit --no-fund --loglevel=error \
            \$ANDCLAW_BUNDLED_PLUGINS
        rm -rf \"\$ANDCLAW_BUNDLED_PLUGIN_NPM_ROOT/node_modules/openclaw\"
        ln -s ../../../../../usr/local/lib/node_modules/openclaw \"\$ANDCLAW_BUNDLED_PLUGIN_NPM_ROOT/node_modules/openclaw\"
        for plugin_package in whatsapp discord codex brave-plugin zai-provider; do
          plugin_node_modules=\"\$ANDCLAW_BUNDLED_PLUGIN_NPM_ROOT/node_modules/@openclaw/\$plugin_package/node_modules\"
          mkdir -p \"\$plugin_node_modules\"
          rm -rf \"\$plugin_node_modules/openclaw\"
          ln -s ../../../openclaw \"\$plugin_node_modules/openclaw\"
        done

        cat > /tmp/write-andclaw-plugin-install-records.cjs <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const openClawDist = '/usr/local/lib/node_modules/openclaw/dist';
  const pluginRoot = '/root/.openclaw/andclaw-bundled-plugins';
  const npmRoot = path.join(pluginRoot, 'npm');
  const installedAt = new Date().toISOString();
  const plugins = [
    { id: 'whatsapp', packageName: '@openclaw/whatsapp' },
    { id: 'discord', packageName: '@openclaw/discord' },
    { id: 'codex', packageName: '@openclaw/codex' },
    { id: 'brave', packageName: '@openclaw/brave-plugin' },
    { id: 'zai', packageName: '@openclaw/zai-provider' },
  ];
  const installRecords = {};

  for (const plugin of plugins) {
    const installPath = path.join(npmRoot, 'node_modules', plugin.packageName);
    const packageJson = JSON.parse(fs.readFileSync(path.join(installPath, 'package.json'), 'utf8'));
    installRecords[plugin.id] = {
      source: 'npm',
      spec: plugin.packageName + '@' + packageJson.version,
      installPath,
      version: packageJson.version,
      resolvedName: plugin.packageName,
      resolvedVersion: packageJson.version,
      resolvedSpec: plugin.packageName + '@' + packageJson.version,
      installedAt,
    };
  }

  const storeModuleCandidates = fs.readdirSync(openClawDist)
    .filter((name) => /^installed-plugin-index-store-[A-Za-z0-9_-]+\.js$/.test(name));
  if (storeModuleCandidates.length === 0) {
    throw new Error('Cannot find OpenClaw installed plugin index store module');
  }
  let refreshPersistedInstalledPluginIndexSync = null;
  for (const candidate of storeModuleCandidates) {
    const mod = await import(pathToFileURL(path.join(openClawDist, candidate)).href);
    const exportedRefresh = Object.values(mod).find(
      (value) =>
        typeof value === 'function' &&
        value.name === 'refreshPersistedInstalledPluginIndexSync',
    );
    if (exportedRefresh) {
      refreshPersistedInstalledPluginIndexSync = exportedRefresh;
      break;
    }
  }
  if (!refreshPersistedInstalledPluginIndexSync) {
    throw new Error('No installed-plugin-index-store candidate exports refreshPersistedInstalledPluginIndexSync. Candidates tried: ' + storeModuleCandidates.join(', '));
  }
  const index = refreshPersistedInstalledPluginIndexSync({
    reason: 'source-changed',
    installRecords,
    env: {
      ...process.env,
      OPENCLAW_STATE_DIR: '/root/.openclaw',
    },
  });
  fs.writeFileSync(path.join(pluginRoot, 'install-records.json'), JSON.stringify(index, null, 2) + '\\n');
})();
NODE
        node /tmp/write-andclaw-plugin-install-records.cjs
        rm -f /root/.openclaw/openclaw.json
        rm -rf /root/.openclaw/plugins
        echo '--- andClaw-managed external/runtime plugins: installed ---'

        # Windows docker cp에서 symlink 생성 권한 오류를 피하기 위해 .bin 심링크 제거
        find /usr/local/lib/node_modules/openclaw/node_modules -path '*/.bin/*' -type l -delete || true

        # (pruning은 docker cp 후 호스트에서 실행)

        # openclaw bin symlink -> 셸 래퍼로 교체 (ESM 상대 경로 호환성)
        rm -f /usr/local/bin/openclaw
        printf '#!/bin/sh\nexec node /usr/local/lib/node_modules/openclaw/openclaw.mjs \"\$@\"\n' > /usr/local/bin/openclaw
        chmod +x /usr/local/bin/openclaw

        ls -lh /usr/local/bin/openclaw

        echo '--- Creating tar bundle ---'
        cd / && tar cf /tmp/openclaw-arm64.tar usr/local/lib/node_modules/openclaw usr/local/bin/openclaw root/.openclaw/andclaw-bundled-plugins
        ls -lh /tmp/openclaw-arm64.tar
        echo '--- DONE ---'
    "

    docker cp andclaw-openclaw-builder:/tmp/openclaw-arm64.tar /tmp/openclaw-arm64.tar

    # pruning 전 원본 백업
    mkdir -p "$PROJECT_DIR/backups"
    gzip -c /tmp/openclaw-arm64.tar > "$PROJECT_DIR/backups/openclaw-arm64-pre-prune.tar.gz"
    echo "   pruning 전 원본 백업: backups/openclaw-arm64-pre-prune.tar.gz"

    # 앱 크기 최적화: node_modules에서 불필요한 파일 제거
    echo "   호스트에서 node_modules pruning 중..."
    PRUNE_DIR=/tmp/openclaw-prune-$$
    mkdir -p "$PRUNE_DIR" && cd "$PRUNE_DIR"
    tar xf /tmp/openclaw-arm64.tar
    BEFORE=$(find . -type f | wc -l)
    find . -path '*/node_modules/*' -name '*.d.ts' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name '*.d.mts' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name '*.d.cts' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name '*.map' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name 'README*' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name 'CHANGELOG*' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name '.npmignore' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name '.eslintrc*' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name 'tsconfig*' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name '.editorconfig' -delete 2>/dev/null || true
    find . -path '*/node_modules/*' -name 'Makefile' -delete 2>/dev/null || true
    # 테스트/예제 디렉토리만 삭제 (docs, md, txt, ts 등은 런타임에 필요할 수 있어 보존)
    find . -path '*/node_modules/*' -type d \( -name "test" -o -name "tests" -o -name "__tests__" -o -name "example" -o -name "examples" -o -name ".github" \) -exec rm -rf {} + 2>/dev/null || true
    # 미사용 플랫폼 native addon 삭제 (arm64-linux만 유지)
    NM_DIR="./usr/local/lib/node_modules/openclaw/node_modules"
    for d in "$NM_DIR"/@img/sharp-*; do
      [ -d "$d" ] || continue; b=$(basename "$d")
      [ "$b" = "sharp-linux-arm64" ] || [ "$b" = "sharp-libvips-linux-arm64" ] && continue
      echo "   [prune] removing platform addon: @img/$b"
      rm -rf "$d"
    done
    for d in "$NM_DIR"/@lancedb/*; do
      [ -d "$d" ] || continue
      b=$(basename "$d")
      [ "$b" = "lancedb-linux-arm64-gnu" ] || [ "$b" = "lancedb" ] && continue
      echo "   [prune] removing platform addon: @lancedb/$b"
      rm -rf "$d"
    done
    for d in "$NM_DIR"/@napi-rs/*; do
      [ -d "$d" ] || continue
      b=$(basename "$d")
      [ "$b" = "canvas-linux-arm64-gnu" ] || [ "$b" = "canvas" ] || [ "$b" = "wasm-runtime" ] && continue
      echo "   [prune] removing platform addon: @napi-rs/$b"
      rm -rf "$d"
    done
    for d in "$NM_DIR"/@snazzah/*; do
      [ -d "$d" ] || continue
      b=$(basename "$d")
      [ "$b" = "davey-linux-arm64-gnu" ] || [ "$b" = "davey" ] && continue
      echo "   [prune] removing platform addon: @snazzah/$b"
      rm -rf "$d"
    done
    if [ -d "$NM_DIR/koffi/build/koffi" ]; then
      for pd in "$NM_DIR"/koffi/build/koffi/*/; do
        [ "$(basename "${pd%/}")" != "linux_arm64" ] && rm -rf "$pd" && echo "   [prune] removing koffi platform: $(basename "${pd%/}")"
      done
    fi
    # Codex는 Android arm64에서 실행하므로 다른 OS/아키텍처 바이너리는 번들에서 제거
    CODEX_PLATFORM_DIR="./root/.openclaw/andclaw-bundled-plugins/npm/node_modules/@openclaw/codex/node_modules/@openai"
    for d in "$CODEX_PLATFORM_DIR"/codex-*; do
      [ -d "$d" ] || continue
      b=$(basename "$d")
      [ "$b" = "codex-linux-arm64" ] && continue
      echo "   [prune] removing Codex platform package: @openai/$b"
      rm -rf "$d"
    done
    # discord extension 내 x64 addon 삭제
    EXT_DIR="./usr/local/lib/node_modules/openclaw/dist/extensions"
    rm -rf "$EXT_DIR/discord/node_modules/@snazzah/davey-linux-x64-gnu" 2>/dev/null || true
    # 빈 디렉토리 정리
    find . -path '*/node_modules/*' -type d -empty -delete 2>/dev/null || true

    # ── 미사용 채널/프로바이더 extension 삭제 (임시 주석 처리: 4.1 테스트) ──
    # EXT_DIR="./usr/local/lib/node_modules/openclaw/dist/extensions"
    # # 미사용 채널
    # UNUSED_CHANNELS="bluebubbles imessage irc line matrix mattermost slack feishu signal nostr googlechat msteams qqbot tlon twitch zalo zalouser synology-chat nextcloud-talk diffs"
    # # 미사용 프로바이더 (andClaw에서 지원하지 않는 것들)
    # UNUSED_PROVIDERS="amazon-bedrock anthropic-vertex byteplus chutes huggingface kilocode litellm lobster microsoft microsoft-foundry modelstudio moonshot nvidia qianfan sglang vllm venice volcengine xiaomi together xai copilot-proxy cloudflare-ai-gateway vercel-ai-gateway"
    # if [ -d "$EXT_DIR" ]; then
    #     for unused in $UNUSED_CHANNELS $UNUSED_PROVIDERS; do
    #         if [ -d "$EXT_DIR/$unused" ]; then
    #             echo "   [prune] removing unused extension: $unused"
    #             rm -rf "$EXT_DIR/$unused"
    #         fi
    #     done
    #     # 남은 extension의 불필요한 node_modules 삭제
    #     USED_EXTENSIONS="whatsapp telegram discord browser memory-core memory-lancedb brave"
    #     for ext_nm_dir in "$EXT_DIR"/*/node_modules; do
    #         [ -d "$ext_nm_dir" ] || continue
    #         ext_name=$(basename "$(dirname "$ext_nm_dir")")
    #         is_used=false
    #         for used in $USED_EXTENSIONS; do
    #             if [ "$ext_name" = "$used" ]; then
    #                 is_used=true
    #                 break
    #             fi
    #         done
    #         if [ "$is_used" = "false" ]; then
    #             echo "   [prune] unused extension node_modules: $ext_name"
    #             rm -rf "$ext_nm_dir"
    #         fi
    #     done
    # fi

    AFTER=$(find . -type f | wc -l)
    echo "   Pruned: $BEFORE -> $AFTER files"

    # 성능 패치: prewarmConfiguredPrimaryModel 스킵
    # implicit provider discovery가 30+ 플러그인을 순차 로드하면서
    # 모바일 환경에서 ~60초 소요되지만, andClaw는 env로 API 키를
    # 전달하므로 discovery 결과가 항상 0건. 완전히 무의미한 작업이라 스킵.
    PREWARM_FILE=$(grep -rl "async function prewarmConfiguredPrimaryModel" usr/local/lib/node_modules/openclaw/dist/*.js 2>/dev/null || true)
    if [ -n "$PREWARM_FILE" ]; then
        sed -i 's/async function prewarmConfiguredPrimaryModel(params) *{/async function prewarmConfiguredPrimaryModel(params){if(globalThis.OPENCLAW_SKIP_MODEL_WARMUP)return;/' "$PREWARM_FILE"
        echo "   patched prewarmConfiguredPrimaryModel skip in $(basename "$PREWARM_FILE")"
    else
        echo "   ⚠ prewarmConfiguredPrimaryModel not found — skip patch not applied"
    fi

    tar cf /tmp/openclaw-arm64.tar usr/ root/.openclaw/andclaw-bundled-plugins
    cd / && rm -rf "$PRUNE_DIR"

    echo "   호스트에서 gzip 압축 중..."
    gzip -f /tmp/openclaw-arm64.tar
    mv /tmp/openclaw-arm64.tar.gz "$OPENCLAW_TAR"
    docker rm andclaw-openclaw-builder
    OPENCLAW_WAS_REBUILT="true"
    BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"

    OPENCLAW_ASSET_VERSION="$(extract_openclaw_version_from_tar "$OPENCLAW_TAR" 2>/dev/null || true)"
    rm -rf "$OPENCLAW_TEMP_DIR"/* 2>/dev/null || true
    echo "   완료: $(du -h "$OPENCLAW_TAR" | cut -f1)"
fi

# openclaw 버전 파일 생성 (SetupManager에서 번들 버전 확인용)
if [ -n "$OPENCLAW_ASSET_VERSION" ]; then
    echo "$OPENCLAW_ASSET_VERSION" > "$ASSETS_DIR/openclaw-version.txt"
    echo "   openclaw-version.txt: $OPENCLAW_ASSET_VERSION"
fi

if [ "$OPENCLAW_WAS_REBUILT" = "true" ] || [ ! -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
    save_openclaw_to_cache || echo "   WARNING: OpenClaw 캐시 저장 실패"
fi

# ── 5. Playwright Chromium 번들 (Docker Build 3) ──
PLAYWRIGHT_FILE="$ASSETS_DIR/playwright-chromium-arm64.tar.gz.bin"
if [ -f "$PLAYWRIGHT_FILE" ]; then
    echo "[5/6] playwright-chromium-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
else
    echo "[5/6] Playwright Chromium 번들 빌드 중 (Docker)..."
    echo "   Playwright $PLAYWRIGHT_VERSION, Chromium headless_shell only"
    check_docker

    docker rm -f andclaw-playwright-builder 2>/dev/null || true

    docker run --platform linux/arm64 --name andclaw-playwright-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends curl ca-certificates

        echo '--- Installing Node.js ---'
        curl -fsSL https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz | tar xz -C /usr/local --strip-components=1
        node --version

        echo '--- Installing Playwright Chromium ---'
        npx playwright@$PLAYWRIGHT_VERSION install chromium 2>&1

        PW_DIR=/root/.cache/ms-playwright

        # full chromium 디렉토리 삭제 (chromium-XXXX)
        FULL_CHROME_DIR=\$(find \$PW_DIR -maxdepth 1 -type d -name 'chromium-*' 2>/dev/null | head -1)
        if [ -n \"\$FULL_CHROME_DIR\" ]; then
            echo \"Removing full chrome: \$FULL_CHROME_DIR (\$(du -sh \$FULL_CHROME_DIR | cut -f1))\"
            rm -rf \"\$FULL_CHROME_DIR\"
        fi

        # headless_shell 경량화
        HS_DIR=\$(find \$PW_DIR -maxdepth 1 -type d -name 'chromium_headless_shell-*' 2>/dev/null | head -1)
        if [ -n \"\$HS_DIR\" ]; then
            HS_CHROME_DIR=\"\$HS_DIR/chrome-linux\"
            if [ -d \"\$HS_CHROME_DIR/locales\" ]; then
                find \"\$HS_CHROME_DIR/locales\" -name '*.pak' ! -name 'en-US.pak' -delete
                echo \"Locales trimmed\"
            fi
            rm -f \"\$HS_CHROME_DIR/chrome_crashpad_handler\" 2>/dev/null
            rm -rf \"\$HS_CHROME_DIR/MEIPreload\" 2>/dev/null
            echo \"headless_shell size: \$(du -sh \$HS_DIR | cut -f1)\"
        else
            echo \"WARNING: headless_shell not found!\"
        fi
        echo \"Total playwright size: \$(du -sh \$PW_DIR | cut -f1)\"

        echo '--- Creating playwright bundle ---'
        cd /
        tar czf /tmp/playwright.tar.gz \\
            root/.cache/ms-playwright
        ls -lh /tmp/playwright.tar.gz
        echo '--- DONE ---'
    "

    docker cp andclaw-playwright-builder:/tmp/playwright.tar.gz "$PLAYWRIGHT_FILE"
    docker rm andclaw-playwright-builder

    echo "   완료: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
    BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"
fi

# ── 6. 정리 ──
echo "[6/6] 정리 중..."

# 기존 통합 번들 삭제
OLD_BUNDLE="$ASSETS_DIR/openclaw-bundle-arm64.tar.gz.bin"
if [ -f "$OLD_BUNDLE" ]; then
    echo "   기존 통합 번들 삭제: openclaw-bundle-arm64.tar.gz.bin"
    rm -f "$OLD_BUNDLE"
fi

# 기존 OpenClaw 디렉토리 자산 삭제 (tar.gz 방식으로 이관)
if [ -d "$ASSETS_DIR/openclaw" ]; then
    echo "   구형 OpenClaw 디렉토리 삭제: openclaw/"
    rm -rf "$ASSETS_DIR/openclaw"
fi

# 실행 권한 복원을 위한 매니페스트 생성
EXEC_MANIFEST="$ASSETS_DIR/executable-manifest.json"
cat > "$EXEC_MANIFEST" <<'JSON'
{
  "assets": {
    "system-tools-arm64.tar.gz.bin": [
      "usr/bin/git",
      "usr/lib/git-core/git*",
      "usr/lib/git-core/scalar"
    ],
    "openclaw-arm64.tar.gz.bin": [
      "usr/local/bin/openclaw"
    ]
  }
}
JSON
echo "   executable-manifest.json 생성 완료"

# 번들 변경 감지를 위한 fingerprint 매니페스트 생성
BUNDLE_FINGERPRINT="$ASSETS_DIR/bundle-fingerprint.json"
if [ "$BUNDLE_FINGERPRINT_NEEDS_UPDATE" = "true" ] || [ ! -f "$BUNDLE_FINGERPRINT" ]; then
    NODE_SHA256="$(sha256sum "$ASSETS_DIR/node-arm64.tar.gz.bin" | awk '{print $1}')"
    TOOLS_SHA256="$(sha256sum "$ASSETS_DIR/system-tools-arm64.tar.gz.bin" | awk '{print $1}')"
    OPENCLAW_SHA256="$(sha256sum "$OPENCLAW_TAR" | awk '{print $1}')"
    PLAYWRIGHT_SHA256="$(sha256sum "$ASSETS_DIR/playwright-chromium-arm64.tar.gz.bin" | awk '{print $1}')"

    cat > "$BUNDLE_FINGERPRINT" <<JSON
{
  "version": 1,
  "assets": {
    "node-arm64.tar.gz.bin": { "sha256": "$NODE_SHA256" },
    "system-tools-arm64.tar.gz.bin": { "sha256": "$TOOLS_SHA256" },
    "openclaw-arm64.tar.gz.bin": { "sha256": "$OPENCLAW_SHA256" },
    "playwright-chromium-arm64.tar.gz.bin": { "sha256": "$PLAYWRIGHT_SHA256" }
  }
}
JSON
    echo "   bundle-fingerprint.json 생성 완료"
else
    echo "   bundle-fingerprint.json 유지 (번들 변경 없음)"
fi

# 모델 카탈로그 JSON 생성 (모델 선택 성능 최적화)
echo "모델 카탈로그 생성 중..."
"$SCRIPT_DIR/generate-model-catalog.sh"

echo ""
echo "============================================"
echo "  완료!"
echo "============================================"
echo ""
echo "assets:"
ls -lh "$ASSETS_DIR/"
echo ""
echo "총 assets 크기: $(du -sh "$ASSETS_DIR/" | cut -f1)"
echo ""
echo "다음 단계: Android Studio 에서 빌드"
