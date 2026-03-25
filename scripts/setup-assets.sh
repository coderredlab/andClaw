#!/bin/bash
#
# andClaw - 빌드 준비 스크립트 (proot + assets 번들)
#
# proot 바이너리를 jniLibs에 배치하고,
# Ubuntu arm64 rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 을
# install_time_assets/src/main/assets/ 에 배치한다.
#
# 필요 조건:
#   - Docker Desktop (arm64 에뮬레이션 지원)
#   - curl, ar, tar
#
# 사용법:
#   chmod +x scripts/setup-assets.sh
#   ./scripts/setup-assets.sh
#
# 이 스크립트 실행 후 생성되는 파일:
#   jniLibs/arm64-v8a/
#     libproot.so, libtalloc.so, libproot-loader.so, libproot-loader32.so
#
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
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$PROJECT_DIR/install_time_assets/src/main/assets"

# URLs & Versions
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb"
TALLOC_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
NODEJS_VERSION="v24.2.0"
NODEJS_URL="https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz"
PLAYWRIGHT_VERSION="1.49.1"
TERMUX_PROOT_COMMIT="${TERMUX_PROOT_COMMIT:-4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f}"
CUSTOM_LOADER32_SCRIPT="$SCRIPT_DIR/build-proot-loader32-16kb.sh"
BUNDLE_FINGERPRINT_NEEDS_UPDATE="false"

require_readelf() {
    if ! command -v readelf >/dev/null 2>&1; then
        echo "ERROR: readelf가 필요합니다 (binutils 설치 필요)"
        exit 1
    fi
}

is_elf_16kb_compatible() {
    local elf_path="$1"
    local aligns

    if [ ! -f "$elf_path" ]; then
        return 1
    fi

    aligns=$(readelf -W -l "$elf_path" | awk '/^[[:space:]]*LOAD[[:space:]]/ { print $NF }')
    if [ -z "$aligns" ]; then
        return 1
    fi

    while read -r align; do
        if [ "$align" != "0x4000" ]; then
            return 1
        fi
    done <<< "$aligns"

    return 0
}

verify_jnilib_16kb() {
    local libs=(
        "libproot.so"
        "libtalloc.so"
        "libproot-loader.so"
    )
    local loader32="$JNILIBS_DIR/libproot-loader32.so"
    local failed=0

    echo "   jniLibs 16KB 정렬 검증 중..."

    for lib in "${libs[@]}"; do
        local path="$JNILIBS_DIR/$lib"
        if [ ! -f "$path" ]; then
            echo "   ERROR: $lib 파일이 없습니다"
            failed=1
            continue
        fi
        if is_elf_16kb_compatible "$path"; then
            echo "   OK: $lib (16KB)"
        else
            echo "   ERROR: $lib 는 16KB 정렬이 아닙니다"
            failed=1
        fi
    done

    if [ -f "$loader32" ]; then
        if is_elf_16kb_compatible "$loader32"; then
            echo "   OK: libproot-loader32.so (16KB)"
        else
            echo "   ERROR: libproot-loader32.so 는 16KB 정렬이 아닙니다"
            failed=1
        fi
    else
        echo "   WARNING: libproot-loader32.so 없음"
    fi

    if [ "$failed" -ne 0 ]; then
        echo "ERROR: 16KB 정렬 검증 실패"
        exit 1
    fi
}

ensure_loader32_16kb() {
    local loader32="$JNILIBS_DIR/libproot-loader32.so"

    if [ ! -f "$loader32" ]; then
        echo "   libproot-loader32.so가 없어 소스 빌드를 시도합니다"
    elif is_elf_16kb_compatible "$loader32"; then
        echo "   libproot-loader32.so 이미 16KB 호환"
        return
    else
        echo "   libproot-loader32.so가 4KB 정렬이라 소스 빌드로 교체합니다"
    fi

    if [ ! -x "$CUSTOM_LOADER32_SCRIPT" ]; then
        echo "ERROR: $CUSTOM_LOADER32_SCRIPT 실행 권한 또는 파일이 없습니다"
        exit 1
    fi

    "$CUSTOM_LOADER32_SCRIPT" "$loader32" "$TERMUX_PROOT_COMMIT"

    if ! is_elf_16kb_compatible "$loader32"; then
        echo "ERROR: 소스 빌드 후에도 libproot-loader32.so 16KB 검증 실패"
        exit 1
    fi
}

echo "============================================"
echo "  andClaw - 빌드 준비 (proot + assets)"
echo "============================================"
echo ""

mkdir -p "$JNILIBS_DIR"
mkdir -p "$ASSETS_DIR"

# ══════════════════════════════════════════════
#  Part 1: proot 바이너리 (jniLibs)
# ══════════════════════════════════════════════

if [ -f "$JNILIBS_DIR/libproot.so" ] && [ -f "$JNILIBS_DIR/libtalloc.so" ]; then
    echo "[1/7] proot 바이너리 이미 존재, 건너뜀"
    ls -lh "$JNILIBS_DIR/"*.so 2>/dev/null | while read line; do echo "   $line"; done
else
    echo "[1/7] proot 바이너리 설정 중..."

    TMP_DIR=$(mktemp -d)
    trap "rm -rf $TMP_DIR" EXIT

    # proot 다운로드 & 추출
    echo "   proot 패키지 다운로드 중..."
    curl -fSL "$PROOT_DEB_URL" -o "$TMP_DIR/proot.deb"

    cd "$TMP_DIR"
    mkdir -p proot_extract
    cd proot_extract
    ar x ../proot.deb
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz 2>/dev/null || (xz -d data.tar.xz && tar xf data.tar 2>/dev/null) || true
    elif [ -f data.tar.gz ]; then
        tar xzf data.tar.gz 2>/dev/null || true
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst && tar xf data.tar 2>/dev/null || true
    fi

    PROOT_BIN=$(find . -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "   ERROR: proot 바이너리를 찾을 수 없습니다!"
        exit 1
    fi

    # libtalloc 다운로드 & 추출
    echo "   libtalloc 패키지 다운로드 중..."
    cd "$TMP_DIR"
    curl -fSL "$TALLOC_DEB_URL" -o "$TMP_DIR/talloc.deb"

    mkdir -p talloc_extract
    cd talloc_extract
    ar x ../talloc.deb
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz 2>/dev/null || (xz -d data.tar.xz && tar xf data.tar 2>/dev/null) || true
    elif [ -f data.tar.gz ]; then
        tar xzf data.tar.gz 2>/dev/null || true
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst && tar xf data.tar 2>/dev/null || true
    fi

    TALLOC_LIB=$(find . -name "libtalloc.so*" -type f | head -1)
    if [ -z "$TALLOC_LIB" ]; then
        echo "   ERROR: libtalloc.so 를 찾을 수 없습니다!"
        exit 1
    fi

    # jniLibs 에 배치
    cp "$TMP_DIR/proot_extract/$PROOT_BIN" "$JNILIBS_DIR/libproot.so"
    cp "$TMP_DIR/talloc_extract/$TALLOC_LIB" "$JNILIBS_DIR/libtalloc.so"

    LOADER_BIN=$(find "$TMP_DIR/proot_extract" -name "loader" -path "*/proot/*" -type f 2>/dev/null | head -1)
    LOADER32_BIN=$(find "$TMP_DIR/proot_extract" -name "loader32" -path "*/proot/*" -type f 2>/dev/null | head -1)

    if [ -n "$LOADER_BIN" ]; then
        cp "$LOADER_BIN" "$JNILIBS_DIR/libproot-loader.so"
        chmod +x "$JNILIBS_DIR/libproot-loader.so"
        echo "   proot-loader: OK"
    else
        echo "   WARNING: proot-loader 없음"
    fi

    if [ -n "$LOADER32_BIN" ]; then
        cp "$LOADER32_BIN" "$JNILIBS_DIR/libproot-loader32.so"
        chmod +x "$JNILIBS_DIR/libproot-loader32.so"
        echo "   proot-loader32: OK"
    else
        echo "   WARNING: proot-loader32 없음"
    fi

    chmod +x "$JNILIBS_DIR/libproot.so"
    chmod +x "$JNILIBS_DIR/libtalloc.so"

    rm -rf "$TMP_DIR"
    trap - EXIT

    echo "   proot 바이너리 설정 완료"
    ls -lh "$JNILIBS_DIR/"
fi

require_readelf
ensure_loader32_16kb
verify_jnilib_16kb

# ══════════════════════════════════════════════
#  Part 2: assets 번들
# ══════════════════════════════════════════════

# ── 2. Ubuntu rootfs 다운로드 ──
ROOTFS_FILE="$ASSETS_DIR/rootfs.tar.gz.bin"
if [ -f "$ROOTFS_FILE" ]; then
    echo "[2/7] rootfs.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$ROOTFS_FILE" | cut -f1)"
else
    echo "[2/7] Ubuntu 24.04 arm64 rootfs 다운로드 중..."
    echo "   URL: $ROOTFS_URL"
    curl -fSL "$ROOTFS_URL" -o "$ROOTFS_FILE"
    echo "   완료: $(du -h "$ROOTFS_FILE" | cut -f1)"
fi

# ── 3. Node.js 다운로드 ──
NODEJS_FILE="$ASSETS_DIR/node-arm64.tar.gz.bin"
NODEJS_ASSET_VERSION="$(tar -tzf "$NODEJS_FILE" 2>/dev/null | head -n 1 | sed -n 's#^node-\(v[0-9][^/]*\)-linux-arm64/$#\1#p' || true)"
if [ -f "$NODEJS_FILE" ] && [ "$NODEJS_ASSET_VERSION" = "$NODEJS_VERSION" ]; then
    echo "[3/7] node-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   버전: $NODEJS_ASSET_VERSION"
    echo "   크기: $(du -h "$NODEJS_FILE" | cut -f1)"
else
    if [ -f "$NODEJS_FILE" ]; then
        echo "[3/7] Node.js 버전 불일치 감지, 재다운로드"
        echo "   현재: ${NODEJS_ASSET_VERSION:-unknown}"
        echo "   목표: $NODEJS_VERSION"
        rm -f "$NODEJS_FILE"
    fi
    echo "[3/7] Node.js $NODEJS_VERSION arm64 다운로드 중..."
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

# ── 4. 시스템 도구 번들 (Docker Build 1) ──
TOOLS_FILE="$ASSETS_DIR/system-tools-arm64.tar.gz.bin"
if [ -f "$TOOLS_FILE" ]; then
    echo "[4/7] system-tools-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$TOOLS_FILE" | cut -f1)"
else
    echo "[4/7] 시스템 도구 번들 빌드 중 (Docker)..."
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
            diff patch \\
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
            etc/ssl/certs \\
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

# ── 5. OpenClaw 파일 자산 (Docker Build 2) ──
OPENCLAW_ASSET_DIR="$ASSETS_DIR/openclaw"
OPENCLAW_MAIN="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/openclaw.mjs"
OPENCLAW_BIN="$OPENCLAW_ASSET_DIR/usr/local/bin/openclaw"
OPENCLAW_ANTHROPIC_PARSER="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/node_modules/@anthropic-ai/sdk/andclaw_us__vendor/partial-json-parser/parser.js"
OPENCLAW_PACKAGE_JSON="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/package.json"
OPENCLAW_CACHE_DIR="$PROJECT_DIR/install_time_assets/cache"
OPENCLAW_CACHE_ARCHIVE="$OPENCLAW_CACHE_DIR/openclaw-assets.tar.gz"

extract_json_version() {
    local json_path="$1"
    if [ ! -f "$json_path" ]; then
        return 1
    fi
    sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$json_path" | head -1
}

get_openclaw_latest_version() {
    curl -fsSL "https://registry.npmjs.org/openclaw/latest" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -1
}

version_lt() {
    local current="$1"
    local target="$2"
    [ "$current" != "$target" ] && [ "$(printf '%s\n%s\n' "$current" "$target" | sort -V | head -1)" = "$current" ]
}

restore_openclaw_from_cache() {
    if [ ! -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
        return 1
    fi

    echo "   OpenClaw 캐시 복원 시도: $OPENCLAW_CACHE_ARCHIVE"
    rm -rf "$OPENCLAW_ASSET_DIR"
    mkdir -p "$ASSETS_DIR"

    if ! tar -xzf "$OPENCLAW_CACHE_ARCHIVE" -C "$ASSETS_DIR"; then
        echo "   WARNING: OpenClaw 캐시 복원 실패"
        return 1
    fi

    [ -d "$OPENCLAW_ASSET_DIR" ]
}

save_openclaw_to_cache() {
    if [ ! -d "$OPENCLAW_ASSET_DIR" ]; then
        return 1
    fi

    mkdir -p "$OPENCLAW_CACHE_DIR"
    tar -czf "$OPENCLAW_CACHE_ARCHIVE.tmp" -C "$ASSETS_DIR" openclaw
    if ! mv -f "$OPENCLAW_CACHE_ARCHIVE.tmp" "$OPENCLAW_CACHE_ARCHIVE" 2>/dev/null; then
        cp -f "$OPENCLAW_CACHE_ARCHIVE.tmp" "$OPENCLAW_CACHE_ARCHIVE" 2>/dev/null || true
        rm -f "$OPENCLAW_CACHE_ARCHIVE.tmp" 2>/dev/null || true
    fi

    if [ ! -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
        return 1
    fi
    echo "   OpenClaw 캐시 저장: $OPENCLAW_CACHE_ARCHIVE"
}

ensure_openclaw_json_parser_shim() {
    for sdk_root in \
        "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/node_modules/@anthropic-ai/sdk" \
        "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/extensions/memory-lancedb/node_modules/@anthropic-ai/sdk" \
    ; do
        partial_js="$sdk_root/_vendor/partial-json-parser/parser.js"
        partial_mjs="$sdk_root/_vendor/partial-json-parser/parser.mjs"
        shim_dir="$sdk_root/_vendor/json-parser"
        if [ -f "$partial_js" ]; then
            mkdir -p "$shim_dir"
            printf '%s\n' 'module.exports = require("../partial-json-parser/parser.js");' > "$shim_dir/json-parser.js"
        fi
        if [ -f "$partial_mjs" ]; then
            mkdir -p "$shim_dir"
            printf '%s\n' 'export * from "../partial-json-parser/parser.mjs";' > "$shim_dir/json-parser.mjs"
        fi
    done
}

encode_openclaw_underscore_paths() {
    local openclaw_root="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw"
    if [ ! -d "$openclaw_root" ]; then
        return
    fi
    # Android assets 패키징에서 '_*' 파일/디렉토리가 누락될 수 있어 안전한 이름으로 인코딩
    # files first, then directories (depth-first) for safe renaming
    find "$openclaw_root" -depth -name '_*' -type f -exec bash -c '
        for f; do
            dir="$(dirname "$f")"
            name="$(basename "$f")"
            mv "$f" "$dir/andclaw_us__${name#_}"
        done
    ' _ {} +
    find "$openclaw_root" -depth -name '_*' -type d -exec bash -c '
        for d; do
            parent="$(dirname "$d")"
            name="$(basename "$d")"
            mv "$d" "$parent/andclaw_us__${name#_}"
        done
    ' _ {} +
}


OPENCLAW_ASSET_VERSION="$(extract_json_version "$OPENCLAW_PACKAGE_JSON" 2>/dev/null || true)"
OPENCLAW_LATEST_VERSION="$(get_openclaw_latest_version 2>/dev/null || true)"
OPENCLAW_BUILD_REASON=""
OPENCLAW_WAS_REBUILT="false"

if [ ! -f "$OPENCLAW_MAIN" ] || [ ! -f "$OPENCLAW_BIN" ] || [ ! -f "$OPENCLAW_ANTHROPIC_PARSER" ]; then
    OPENCLAW_BUILD_REASON="필수 파일 누락"
elif [ -n "$OPENCLAW_ASSET_VERSION" ] && [ -n "$OPENCLAW_LATEST_VERSION" ] && version_lt "$OPENCLAW_ASSET_VERSION" "$OPENCLAW_LATEST_VERSION"; then
    OPENCLAW_BUILD_REASON="버전 업그레이드 필요 ($OPENCLAW_ASSET_VERSION -> $OPENCLAW_LATEST_VERSION)"
elif [ -z "$OPENCLAW_LATEST_VERSION" ]; then
    echo "[5/7] WARNING: openclaw 최신 버전 조회 실패, 기존 자산 버전을 유지합니다"
fi

if [ -n "$OPENCLAW_BUILD_REASON" ] && [ -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
    if restore_openclaw_from_cache; then
        OPENCLAW_ASSET_VERSION="$(extract_json_version "$OPENCLAW_PACKAGE_JSON" 2>/dev/null || true)"
        OPENCLAW_BUILD_REASON=""
        # 캐시 복원으로 openclaw 디렉토리가 실제 변경될 수 있으므로
        # fingerprint는 반드시 재생성한다.
        BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"

        if [ ! -f "$OPENCLAW_MAIN" ] || [ ! -f "$OPENCLAW_BIN" ] || [ ! -f "$OPENCLAW_ANTHROPIC_PARSER" ]; then
            OPENCLAW_BUILD_REASON="캐시 복원 결과 필수 파일 누락"
        elif [ -n "$OPENCLAW_ASSET_VERSION" ] && [ -n "$OPENCLAW_LATEST_VERSION" ] && version_lt "$OPENCLAW_ASSET_VERSION" "$OPENCLAW_LATEST_VERSION"; then
            OPENCLAW_BUILD_REASON="캐시 버전 업그레이드 필요 ($OPENCLAW_ASSET_VERSION -> $OPENCLAW_LATEST_VERSION)"
        else
            echo "   OpenClaw 캐시 복원 성공"
        fi
    fi
fi

if [ -z "$OPENCLAW_BUILD_REASON" ]; then
    echo "[5/7] openclaw 디렉토리 자산 이미 존재, 건너뜀"
    if [ -n "$OPENCLAW_ASSET_VERSION" ]; then
        echo "   버전: $OPENCLAW_ASSET_VERSION"
    fi
    if [ -n "$OPENCLAW_LATEST_VERSION" ]; then
        echo "   최신: $OPENCLAW_LATEST_VERSION"
    fi
    echo "   크기: $(du -sh "$OPENCLAW_ASSET_DIR" | cut -f1)"
else
    echo "[5/7] OpenClaw 디렉토리 자산 빌드 중 (Docker)..."
    echo "   사유: $OPENCLAW_BUILD_REASON"
    check_docker

    OPENCLAW_INSTALL_SPEC="openclaw"
    if [ -n "$OPENCLAW_LATEST_VERSION" ]; then
        OPENCLAW_INSTALL_SPEC="openclaw@$OPENCLAW_LATEST_VERSION"
    fi
    echo "   npm install -g $OPENCLAW_INSTALL_SPEC"

    docker rm -f andclaw-openclaw-builder 2>/dev/null || true
    rm -rf "$OPENCLAW_ASSET_DIR"
    mkdir -p "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules" "$OPENCLAW_ASSET_DIR/usr/local/bin"

    docker run --platform linux/arm64 --name andclaw-openclaw-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        # node-gyp fallback 빌드를 위해 Python/컴파일러를 함께 설치한다.
        apt-get install -y -qq --no-install-recommends \
            curl ca-certificates git \
            python3 python-is-python3 \
            make g++ pkg-config

        echo '--- Installing Node.js ---'
        curl -fsSL https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz | tar xz -C /usr/local --strip-components=1
        node --version
        python3 --version
        npm --version

        echo '--- Installing OpenClaw ---'
        # 일부 환경에서 node-gyp가 python 경로를 자동 감지하지 못해 명시적으로 지정한다.
        PYTHON=/usr/bin/python3 npm_config_python=/usr/bin/python3 npm install -g $OPENCLAW_INSTALL_SPEC 2>&1

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

        # WhatsApp extension 의존성 설치 (npm 패키지에 node_modules 누락 대응)
        WA_EXT=/usr/local/lib/node_modules/openclaw/dist/extensions/whatsapp
        if [ -f \$WA_EXT/package.json ] && [ ! -d \$WA_EXT/node_modules ]; then
            echo '--- Installing WhatsApp extension dependencies ---'
            # workspace:* 참조 제거 후 production deps만 설치
            WA_PKG=\$WA_EXT/package.json python3 -c 'import json,os;p=os.environ[\"WA_PKG\"];d=json.load(open(p));d.pop(\"devDependencies\",None);d.pop(\"peerDependencies\",None);open(p,\"w\").write(json.dumps(d,indent=2))'
            cd \$WA_EXT && npm install --omit=dev 2>&1
            # extension 내부 .bin symlink도 제거
            find \$WA_EXT/node_modules -path '*/.bin/*' -type l -delete || true
            cd /
            du -sh \$WA_EXT/node_modules
        else
            echo '--- WhatsApp extension dependencies already present, skipping ---'
        fi

        # Windows docker cp에서 symlink 생성 권한 오류를 피하기 위해 .bin 심링크 제거
        find /usr/local/lib/node_modules/openclaw/node_modules -path '*/.bin/*' -type l -delete || true

        # openclaw bin symlink -> 셸 래퍼로 교체 (ESM 상대 경로 호환성)
        rm -f /usr/local/bin/openclaw
        printf '#!/bin/sh\nexec node /usr/local/lib/node_modules/openclaw/openclaw.mjs \"\$@\"\n' > /usr/local/bin/openclaw
        chmod +x /usr/local/bin/openclaw

        ls -lh /usr/local/bin/openclaw
        echo '--- DONE ---'
    "

    docker cp andclaw-openclaw-builder:/usr/local/lib/node_modules/openclaw "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw"
    docker cp andclaw-openclaw-builder:/usr/local/bin/openclaw "$OPENCLAW_ASSET_DIR/usr/local/bin/openclaw"
    docker rm andclaw-openclaw-builder
    OPENCLAW_WAS_REBUILT="true"
    BUNDLE_FINGERPRINT_NEEDS_UPDATE="true"

    echo "   완료: $(du -sh "$OPENCLAW_ASSET_DIR" | cut -f1)"
fi

# 버전이 이미 최신과 동일하면 OpenClaw 후처리는 건너뜀
if [ -z "$OPENCLAW_BUILD_REASON" ] && [ -n "$OPENCLAW_ASSET_VERSION" ] && [ -n "$OPENCLAW_LATEST_VERSION" ] && [ "$OPENCLAW_ASSET_VERSION" = "$OPENCLAW_LATEST_VERSION" ]; then
    echo "   OpenClaw 버전 동일($OPENCLAW_ASSET_VERSION), shim/underscore 인코딩 스킵"
else
    # 일부 npm 조합에서 _vendor/json-parser 경로를 요구하므로, 에셋 복사 후 호스트에서 shim을 다시 보장한다.
    ensure_openclaw_json_parser_shim
    # assets 패키징 전 _* 경로 인코딩
    encode_openclaw_underscore_paths
fi

if [ "$OPENCLAW_WAS_REBUILT" = "true" ] || [ ! -f "$OPENCLAW_CACHE_ARCHIVE" ]; then
    save_openclaw_to_cache || echo "   WARNING: OpenClaw 캐시 저장 실패"
fi

# ── 6. Playwright Chromium 번들 (Docker Build 3) ──
PLAYWRIGHT_FILE="$ASSETS_DIR/playwright-chromium-arm64.tar.gz.bin"
if [ -f "$PLAYWRIGHT_FILE" ]; then
    echo "[6/7] playwright-chromium-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
else
    echo "[6/7] Playwright Chromium 번들 빌드 중 (Docker)..."
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

# ── 7. 정리 ──
echo "[7/7] 정리 중..."

# 기존 통합 번들 삭제
OLD_BUNDLE="$ASSETS_DIR/openclaw-bundle-arm64.tar.gz.bin"
if [ -f "$OLD_BUNDLE" ]; then
    echo "   기존 통합 번들 삭제: openclaw-bundle-arm64.tar.gz.bin"
    rm -f "$OLD_BUNDLE"
fi

# 기존 OpenClaw tar 자산 삭제 (디렉토리 방식으로 이관)
OLD_OPENCLAW_TAR="$ASSETS_DIR/openclaw-arm64.tar.gz.bin"
if [ -f "$OLD_OPENCLAW_TAR" ]; then
    echo "   구형 OpenClaw tar 삭제: openclaw-arm64.tar.gz.bin"
    rm -f "$OLD_OPENCLAW_TAR"
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
    "openclaw": [
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
    if tar --help 2>/dev/null | grep -q -- '--sort='; then
        OPENCLAW_SHA256="$(
            tar -C "$ASSETS_DIR" \
                --sort=name \
                --mtime='UTC 1970-01-01' \
                --owner=0 \
                --group=0 \
                --numeric-owner \
                -cf - openclaw \
                | sha256sum \
                | awk '{print $1}'
        )"
    else
        OPENCLAW_SHA256="$(
            tar -C "$ASSETS_DIR" -cf - openclaw \
                | sha256sum \
                | awk '{print $1}'
        )"
    fi
    PLAYWRIGHT_SHA256="$(sha256sum "$ASSETS_DIR/playwright-chromium-arm64.tar.gz.bin" | awk '{print $1}')"

    cat > "$BUNDLE_FINGERPRINT" <<JSON
{
  "version": 1,
  "assets": {
    "node-arm64.tar.gz.bin": { "sha256": "$NODE_SHA256" },
    "system-tools-arm64.tar.gz.bin": { "sha256": "$TOOLS_SHA256" },
    "openclaw": { "sha256": "$OPENCLAW_SHA256" },
    "playwright-chromium-arm64.tar.gz.bin": { "sha256": "$PLAYWRIGHT_SHA256" }
  }
}
JSON
    echo "   bundle-fingerprint.json 생성 완료"
else
    echo "   bundle-fingerprint.json 유지 (번들 변경 없음)"
fi

echo ""
echo "============================================"
echo "  완료!"
echo "============================================"
echo ""
echo "jniLibs:"
ls -lh "$JNILIBS_DIR/"
echo ""
echo "assets:"
ls -lh "$ASSETS_DIR/"
echo ""
echo "총 assets 크기: $(du -sh "$ASSETS_DIR/" | cut -f1)"
echo ""
echo "다음 단계: Android Studio 에서 빌드"
