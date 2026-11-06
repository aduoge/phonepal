#!/usr/bin/env bash
# Read-only diagnostics; no installs, signing, settings changes or credential access.
set -euo pipefail
usage() {
  cat <<'EOF'
用法：bash scripts/ios/doctor.sh <check|--help>
check 只读检查 macOS、Xcode、iOS SDK、已连接真机、Java 17 与 WDA /status。
XIAOMAN_WDA_URL 默认 http://127.0.0.1:${WDA_PORT:-8100}；WDA_PORT 默认 8100。
本脚本不安装软件、不修改系统设置。无参数只显示本用法。
EOF
}
case "${1:-}" in ''|--help|-h|help) usage; exit 0 ;; check) ;; *) usage >&2; exit 2 ;; esac
[[ $# -eq 1 ]] || { usage >&2; exit 2; }
[[ "$(uname -s)" == Darwin ]] || { echo '检查需要 macOS；--help 可在其他系统查看。' >&2; exit 1; }
issues=0
warn() { echo "注意：$*"; issues=$((issues + 1)); }
at_least() {
  local actual="$1" required="$2" a b c x y z
  IFS=. read -r a b c <<< "$actual"
  IFS=. read -r x y z <<< "$required"
  (( ${a:-0} > ${x:-0} || (${a:-0} == ${x:-0} && ${b:-0} > ${y:-0}) || (${a:-0} == ${x:-0} && ${b:-0} == ${y:-0} && ${c:-0} >= ${z:-0}) ))
}
mac_version="$(sw_vers -productVersion)"
echo "macOS：$mac_version"
xcode_version=''
if command -v xcode-select >/dev/null 2>&1; then
  if path="$(xcode-select -p 2>/dev/null)"; then echo "Xcode 路径：$path"; else warn '尚未选择 Xcode。'; fi
fi
if command -v xcodebuild >/dev/null 2>&1; then
  if version_output="$(xcodebuild -version 2>&1)"; then
    printf '%s\n' "$version_output"
    xcode_version="$(printf '%s\n' "$version_output" | awk '/^Xcode / {print $2; exit}')"
    [[ "$xcode_version" =~ ^[0-9]+(\.[0-9]+)*$ ]] || xcode_version=''
  else warn "无法读取 Xcode 版本：$version_output"; fi
  echo 'Xcode 支持的 SDK：'
  if ! xcodebuild -showsdks; then warn '无法读取 SDK，请确认安装的是完整 Xcode。'; fi
else warn '找不到 xcodebuild，请安装完整 Xcode。'; fi
if [[ -n "$xcode_version" ]]; then
  if at_least "$xcode_version" 26; then
    at_least "$mac_version" 15.6 || warn 'Xcode 26 需要 macOS 15.6 或以上。'
  elif at_least "$xcode_version" 16; then
    at_least "$mac_version" 14.5 || warn 'Xcode 16.x 需要 macOS 14.5 或以上。'
  fi
fi
if command -v xcrun >/dev/null 2>&1 && devices="$(xcrun xctrace list devices 2>&1)"; then
  echo '设备列表（包括名称和 iOS 版本）：'
  printf '%s\n' "$devices"
  connected="$(printf '%s\n' "$devices" | awk '
    /^== Devices ==/ {real=1; next} /^==/ {real=0}
    real && !/Simulator/ && /\([0-9]+(\.[0-9]+)+\)[[:space:]]+\([0-9A-Fa-f-]+\)[[:space:]]*$/ {print}')"
  if [[ -z "$connected" ]]; then warn '未发现已连接真机，请解锁手机、检查数据线并信任此电脑。'; fi
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    ios_version="$(printf '%s\n' "$line" | sed -nE 's/.*\(([0-9]+(\.[0-9]+)*)\)[[:space:]]*\([0-9A-Fa-f-]+\).*/\1/p')"
    [[ -n "$ios_version" ]] || { warn "无法解析设备 iOS 版本，请手动核对：$line"; continue; }
    required=''
    if at_least "$ios_version" 26; then required=26
    elif at_least "$ios_version" 18; then required=16
    elif at_least "$ios_version" 17.4; then required=15.3; fi
    if [[ -n "$required" ]]; then
      if [[ -z "$xcode_version" ]] || ! at_least "$xcode_version" "$required"; then
        warn "设备 iOS $ios_version 需要 Xcode $required+；Xcode 版本过旧无法支持该 iOS 版本。"
      fi
    fi
  done <<< "$connected"
else warn '无法列出设备，请确认 Xcode 可用且已完成首次启动。'; fi
echo '请在 iPhone 开启：设置 > 隐私与安全性 > 开发者模式，并在连接时选择「信任此电脑」。'
if command -v java >/dev/null 2>&1 && java_output="$(java -version 2>&1)"; then
  printf '%s\n' "$java_output"
  java_major="$(printf '%s\n' "$java_output" | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
  if [[ "$java_major" == 17 ]]; then echo 'Java 17 可用。'; else warn '当前 Java 不是 17；请安装 JDK 17 并为 ios-runner 选择它。'; fi
else warn 'Java 不可用，请安装 JDK 17。'; fi
wda_url="${XIAOMAN_WDA_URL:-http://127.0.0.1:${WDA_PORT:-8100}}"
echo '检查 WDA /status（连接超时 3 秒，总超时 5 秒）。'
if command -v curl >/dev/null 2>&1 && curl --fail --silent --show-error --connect-timeout 3 --max-time 5 "${wda_url%/}/status"; then
  printf '\nWDA /status 可达。\n'
else warn 'WDA /status 不可达，请检查 start、forward 或 XIAOMAN_WDA_URL。'; fi
if (( issues == 0 )); then echo '检查完成，未发现上述问题；开发者模式和证书信任仍需在手机上确认。'; else echo "检查完成，有 $issues 项需处理。"; fi
