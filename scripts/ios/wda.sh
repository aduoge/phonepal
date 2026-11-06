#!/usr/bin/env bash
# macOS only. v16.15.3 is the pinned WDA release (2026-11-03); set WDA_REF for another compatible tag.
set -euo pipefail
usage() {
  cat <<'EOF'
用法：bash scripts/ios/wda.sh <setup|start|forward|url|status|stop|--help>
  setup    下载并签名构建 WDA（请先在 Xcode 登录 Apple ID）
  start    后台启动已构建的 WDA，等待服务就绪
  forward  后台建立 USB 转发；优先使用 iproxy，否则安装 pymobiledevice3（不插线时不需要）
  url      输出手机上的 WDA 局域网地址（start 时记录），供 XIAOMAN_WDA_URL 使用
  status   读取 WDA /status（默认本机转发端口，设置了 XIAOMAN_WDA_URL 时用它）
  stop     仅停止本脚本记录且身份匹配的转发与 Xcode 进程
环境变量：
  UDID              iPhone 标识；缺省选第一台已连接真机
  TEAM_ID           setup 必填，Xcode 中选择的开发团队 ID
  WDA_BUNDLE_ID     签名 bundleId，默认 com.xiaoman.WebDriverAgentRunner；建议改成自己的唯一 ID
  WDA_REF           固定 tag，默认 v16.15.3；可按 Xcode/iOS 兼容性调整
  WDA_PORT          本机转发端口，默认 8100（设备端始终为 8100）
  WDA_START_TIMEOUT 启动等待秒数，默认 120
  XIAOMAN_IOS_HOME 状态根目录，默认 ~/.xiaoman-ios；所有状态在其 wda/ 下
也可通过设备 Wi-Fi IP 连接：XIAOMAN_WDA_URL=http://<ip>:8100。
脚本不处理 Apple ID 凭据；免费账号的签名通常需每 7 天重签。
EOF
}
case "${1:-}" in ''|--help|-h|help) usage; exit 0 ;; setup|start|forward|url|status|stop) action="$1" ;; *) usage >&2; exit 2 ;; esac
[[ $# -eq 1 ]] || { usage >&2; exit 2; }
[[ "$(uname -s)" == Darwin ]] || { echo '此脚本的运行命令需要 macOS；--help 可在其他系统查看。' >&2; exit 1; }
state="${XIAOMAN_IOS_HOME:-$HOME/.xiaoman-ios}/wda"
source_dir="$state/WebDriverAgent"
port="${WDA_PORT:-8100}"
[[ "$port" =~ ^[0-9]+$ && ${#port} -le 5 ]] && (( 10#$port >= 1 && 10#$port <= 65535 )) || { echo 'WDA_PORT 必须是 1..65535。' >&2; exit 2; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1" >&2; exit 1; }; }
# A timestamp prevents a reused PID from authorizing a kill of an unrelated process.
process_identity() { ps -p "$1" -o lstart= 2>/dev/null || true; }
is_owned() {
  local file="$1" pid identity
  [[ -f "$file" ]] || return 1
  IFS= read -r pid < "$file" || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  identity="$(sed -n '2p' "$file")"
  [[ -n "$identity" && "$(process_identity "$pid")" == "$identity" ]] && kill -0 "$pid" 2>/dev/null
}
record_pid() {
  local file="$1" pid="$2" identity
  identity="$(process_identity "$pid")"
  [[ -n "$identity" ]] || { echo '后台进程未能启动，请检查日志。' >&2; return 1; }
  printf '%s\n%s\n' "$pid" "$identity" > "$file"
}
stop_one() {
  local file="$1" pid
  if is_owned "$file"; then
    IFS= read -r pid < "$file"
    kill "$pid"
    echo "已请求停止进程 $pid。"
  elif [[ -f "$file" ]]; then
    echo "记录已过期或身份不匹配，未停止任何进程：$file"
  fi
  rm -f "$file"
}
select_device() {
  need xcrun
  if [[ -z "${UDID:-}" ]]; then
    UDID="$(xcrun xctrace list devices 2>/dev/null | awk '
      /^== Devices ==/ { real=1; next }
      /^==/ { real=0 }
      real && !/Simulator/ && /\([0-9]+(\.[0-9]+)+\)[[:space:]]+\([0-9A-Fa-f-]+\)[[:space:]]*$/ {
        line=$0; sub(/^.*\(/,"",line); sub(/\)[[:space:]]*$/,"",line); print line; exit
      }')"
  fi
  [[ -n "${UDID:-}" ]] || { echo '未找到已连接真机。请解锁、信任此电脑，或设置 UDID。' >&2; exit 1; }
  echo "设备 UDID：$UDID"
}
xcode_args() {
  build_args=(-project "$source_dir/WebDriverAgent.xcodeproj" -scheme WebDriverAgentRunner
    -destination "id=$UDID" -derivedDataPath "$state/DerivedData")
}
case "$action" in
  url)
    [[ -s "$state/device-url" ]] || { echo '还没有记录设备地址，请先执行 start。' >&2; exit 1; }
    cat "$state/device-url"
    ;;
  status)
    need curl
    exec curl --fail --silent --show-error --connect-timeout 3 --max-time 5 "${XIAOMAN_WDA_URL:-http://127.0.0.1:$port}/status"
    ;;
  stop)
    stop_one "$state/forward.pid"
    stop_one "$state/xcodebuild.pid"
    ;;
  setup)
    need git; need xcodebuild
    [[ -n "${TEAM_ID:-}" ]] || { echo '请先在 Xcode > Settings > Accounts 登录 Apple ID，再设置 TEAM_ID。' >&2; exit 2; }
    select_device
    if is_owned "$state/xcodebuild.pid"; then echo '请先 stop，再重新构建。' >&2; exit 1; fi
    mkdir -p "$state/tmp"
    export TMPDIR="$state/tmp/"
    ref="${WDA_REF:-v16.15.3}"
    if [[ ! -d "$source_dir/.git" ]]; then
      git clone --depth 1 --branch "$ref" https://github.com/appium/WebDriverAgent "$source_dir"
    else
      git -C "$source_dir" fetch --depth 1 origin "$ref"
      git -C "$source_dir" checkout --detach FETCH_HEAD
    fi
    xcode_args
    echo '构建会请求 Xcode 使用已登录的 Apple ID 签名；脚本不读取账号凭据。'
    xcodebuild build-for-testing "${build_args[@]}" -allowProvisioningUpdates \
      "DEVELOPMENT_TEAM=$TEAM_ID" "PRODUCT_BUNDLE_IDENTIFIER=${WDA_BUNDLE_ID:-com.xiaoman.WebDriverAgentRunner}"
    printf '%s\n' "$UDID" > "$state/built-udid"
    echo '构建完成。请在 iPhone 信任开发者证书，然后执行 start。'
    ;;
  start)
    need xcodebuild; need curl
    [[ -f "$state/built-udid" ]] || { echo '请先成功执行 setup。' >&2; exit 1; }
    if is_owned "$state/xcodebuild.pid"; then echo "WDA 已启动，日志：$state/xcodebuild.log"; exit 0; fi
    if [[ -z "${UDID:-}" ]]; then IFS= read -r UDID < "$state/built-udid"; fi
    select_device
    built_udid="$(cat "$state/built-udid")"
    [[ "$UDID" == "$built_udid" ]] || { echo '当前设备与构建设备不同，请对该设备重新 setup。' >&2; exit 1; }
    timeout="${WDA_START_TIMEOUT:-120}"
    [[ "$timeout" =~ ^[0-9]+$ && ${#timeout} -le 6 ]] && (( 10#$timeout > 0 )) || { echo 'WDA_START_TIMEOUT 必须是正整数（最多六位）。' >&2; exit 2; }
    export TMPDIR="$state/tmp/"
    xcode_args
    nohup xcodebuild test-without-building "${build_args[@]}" > "$state/xcodebuild.log" 2>&1 < /dev/null &
    record_pid "$state/xcodebuild.pid" "$!"
    deadline=$((SECONDS + 10#$timeout))
    ready=0
    while (( SECONDS < deadline )); do
      is_owned "$state/xcodebuild.pid" || { echo "WDA 进程退出，请查看 $state/xcodebuild.log" >&2; exit 1; }
      if grep -q 'ServerURLHere' "$state/xcodebuild.log" || curl --fail --silent --connect-timeout 1 --max-time 2 "http://127.0.0.1:$port/status" >/dev/null 2>&1; then ready=1; break; fi
      sleep 1
    done
    if (( ready == 0 )); then echo "等待超时，进程仍在后台；查看 $state/xcodebuild.log，或执行 stop。" >&2; exit 1; fi
    device_url="$(sed -n 's/.*ServerURLHere->\(http[^<[:space:]]*\).*/\1/p' "$state/xcodebuild.log" | sed -n '1p')"
    if [[ -n "$device_url" ]]; then
      printf '%s\n' "${device_url%/}" > "$state/device-url"
      echo "设备上的 WDA URL：$device_url（不插线时：export XIAOMAN_WDA_URL=\"\$(bash scripts/ios/wda.sh url)\"）"
    else echo 'WDA /status 已可达；设备 URL 请查看日志中的 ServerURLHere（设备端口 8100）。'; fi
    echo "日志：$state/xcodebuild.log；USB 转发请执行 forward。"
    ;;
  forward)
    if is_owned "$state/forward.pid"; then echo '转发进程已在运行；可执行 status 检查。'; exit 0; fi
    select_device
    mkdir -p "$state/tmp"
    export TMPDIR="$state/tmp/"
    if command -v iproxy >/dev/null 2>&1; then
      forward_args=(iproxy -u "$UDID" -s 127.0.0.1 "$port" 8100)
    else
      need python3
      [[ -x "$state/venv/bin/python3" ]] || python3 -m venv "$state/venv"
      if ! "$state/venv/bin/python3" -c 'import pymobiledevice3' >/dev/null 2>&1; then
        PIP_CACHE_DIR="$state/pip-cache" "$state/venv/bin/python3" -m pip install pymobiledevice3
      fi
      forward_args=("$state/venv/bin/pymobiledevice3" usbmux forward --serial "$UDID" "$port" 8100)
    fi
    nohup "${forward_args[@]}" > "$state/forward.log" 2>&1 < /dev/null &
    record_pid "$state/forward.pid" "$!"
    sleep 1
    is_owned "$state/forward.pid" || { echo "转发进程退出，请查看 $state/forward.log" >&2; exit 1; }
    echo "USB 转发已启动：127.0.0.1:$port -> 设备 8100。用 status 验证。"
    echo '也可使用 Wi-Fi：XIAOMAN_WDA_URL=http://<设备IP>:8100（仅在可信网络使用）。'
    ;;
esac
