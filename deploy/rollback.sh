#!/usr/bin/env bash
# 把服务器上的小满网页版切回上一个版本（或指定版本）并重启。在你自己的电脑上运行。
#
# 用法：
#   deploy/rollback.sh <ssh 目标>              切回上一次安装前的版本
#   deploy/rollback.sh <ssh 目标> --list       列出服务器上保留的版本
#   deploy/rollback.sh <ssh 目标> 20261105-221500   切到指定版本
set -euo pipefail
target="${1:?用法：deploy/rollback.sh <ssh 目标> [--list | 版本]}"
choice="${2:-}"
if [[ -n "$choice" && "$choice" != --list && ! "$choice" =~ ^[0-9]{8}-[0-9]{6}$ ]]; then
  echo "版本名应形如 20261105-221500" >&2; exit 1
fi
# 服务器端脚本先以 base64 传过去再用 sudo 执行；-tt 让 sudo 可以提示输入密码。
script="$(cat <<'REMOTE'
set -euo pipefail
choice="$1"
base=/opt/xiaoman-web
current="$(readlink -f "$base/current" 2>/dev/null || true)"
if [[ "$choice" == --list ]]; then
  ls -1 "$base/releases"; echo "当前：$(basename "$current")"; exit 0
fi
if [[ -n "$choice" ]]; then target_dir="$base/releases/$choice"
else target_dir="$(cat "$base/previous" 2>/dev/null || true)"; fi
[[ -n "$target_dir" && -x "$target_dir/bin/web-server" ]] || { echo "找不到要回滚的版本：${target_dir:-（没有记录上一个版本）}"; exit 1; }
ln -sfn "$target_dir" "$base/current"
[[ -n "$current" && "$current" != "$target_dir" ]] && echo "$current" > "$base/previous"
systemctl restart xiaoman-web
port="$(sed -n 's/^XIAOMAN_PORT=//p' /etc/xiaoman-web/env)"
for _ in $(seq 1 30); do curl -fsS --max-time 3 "http://127.0.0.1:${port:-8787}/api/info" >/dev/null && break; sleep 1; done
curl -fsS --max-time 3 "http://127.0.0.1:${port:-8787}/api/info" && echo
echo "已切换到 $(basename "$target_dir")"
REMOTE
)"
encoded="$(printf '%s\n' "$script" | base64 | tr -d '\n')"
ssh -tt -o ConnectTimeout=15 "$target" \
  "f=\$(mktemp); echo '$encoded' | base64 -d > \"\$f\"; sudo bash \"\$f\" '$choice'; s=\$?; rm -f \"\$f\"; exit \$s"
