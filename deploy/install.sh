#!/usr/bin/env bash
# 一键把小满网页版装到一台 Linux 云服务器（Debian/Ubuntu 优先，其他发行版按提示手动装依赖）。
# 在你自己的电脑上、仓库根目录运行；通过 ssh 登录服务器，需要服务器账号有 sudo 权限。
#
# 用法：
#   deploy/install.sh <ssh 目标> (--domain 域名 | --sslip [公网IP]) [选项]
#
#   <ssh 目标>            例如 ubuntu@203.0.113.10，或 ~/.ssh/config 里的主机别名
#   --domain NAME         自有域名（A 记录已指向服务器），例如 xiaoman.example.com
#   --sslip [IP]          没有域名时用 <IP>.sslip.io；IP 省略时从 ssh 目标里取，取不到就问服务器
#   --access-code CODE    课堂访问码；不填就随机生成并打印一次（再次安装时不填则保留原来的）
#   --timezone ZONE       提醒用的时区，默认 Asia/Shanghai
#   --port N              web-server 本机端口，默认 8787（只在服务器本机监听）
#   --with-node           同时安装 Node.js，开启 12306 火车票（stdio MCP），并预热 npx 缓存
#   --install-deps        服务器缺 Java 17 / Caddy 时自动用 apt 安装（不加则只提示怎么装）
#   --dist DIR            已构建好的 installDist 目录；默认自动构建 :web-server:installDist
#   --skip-build          不构建，直接用 --dist 或默认构建目录里的产物
#
#   课堂令牌（可选，默认关闭；讲师用自己的方舟 Key 兜底，学员只拿令牌）：
#   --class-models LIST   模型白名单，如 xiaoman-class=doubao-seed-1-6-250615（别名=真实模型ID，逗号分隔）
#   --class-key-file F    从本机文件 F 读讲师的方舟 Key（不要把 Key 写在命令行里）
#   --class-key-stdin     从标准输入读 Key（终端里会提示输入，不回显）
#   --class-upstream-url U  上游地址，默认 https://ark.cn-beijing.volces.com/api/v3
#   --class-web-model N   网页版课堂模式用的白名单名，默认白名单第一个
#   --class-off           关闭课堂令牌（删除服务器上的 Key 文件和相关设置）
#   不给这些参数时沿用服务器上原来的课堂令牌设置。Key 存在服务器 /etc/xiaoman-web/class-upstream.key（0640）。
#
#   -h, --help            显示本帮助
#
# 再次运行即为“更新”：上传新版本到 /opt/xiaoman-web/releases/<时间>，切换 current 链接并重启，
# 旧版本保留，可用 deploy/rollback.sh 回滚。访问码、时区保存在服务器 /etc/xiaoman-web/env。
set -euo pipefail

usage() { sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "错误：$*" >&2; exit 1; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="" domain="" sslip=false sslip_ip="" access_code="" timezone="Asia/Shanghai" port=8787
with_node=false install_deps=false dist="" skip_build=false
class_models="" class_key_file="" class_key_stdin=false class_url="" class_web_model="" class_off=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --domain) domain="${2:?--domain 需要域名}"; shift 2 ;;
    --sslip) sslip=true; if [[ "${2:-}" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then sslip_ip="$2"; shift 2; else shift; fi ;;
    --access-code) access_code="${2:?--access-code 需要值}"; shift 2 ;;
    --timezone) timezone="${2:?--timezone 需要值}"; shift 2 ;;
    --port) port="${2:?--port 需要值}"; shift 2 ;;
    --with-node) with_node=true; shift ;;
    --install-deps) install_deps=true; shift ;;
    --dist) dist="${2:?--dist 需要目录}"; shift 2 ;;
    --skip-build) skip_build=true; shift ;;
    --class-models) class_models="${2:?--class-models 需要值}"; shift 2 ;;
    --class-key-file) class_key_file="${2:?--class-key-file 需要文件}"; shift 2 ;;
    --class-key-stdin) class_key_stdin=true; shift ;;
    --class-upstream-url) class_url="${2:?--class-upstream-url 需要值}"; shift 2 ;;
    --class-web-model) class_web_model="${2:?--class-web-model 需要值}"; shift 2 ;;
    --class-off) class_off=true; shift ;;
    -*) die "未知参数 $1（--help 查看用法）" ;;
    *) [[ -z "$target" ]] || die "只能指定一个 ssh 目标"; target="$1"; shift ;;
  esac
done

[[ -n "$target" ]] || { usage; exit 1; }
[[ -n "$domain" || "$sslip" == true ]] || die "请用 --domain 或 --sslip 指定网址"
[[ -z "$domain" || "$sslip" == false ]] || die "--domain 和 --sslip 只能选一个"
[[ "$port" =~ ^[0-9]+$ ]] && (( port > 0 && port < 65536 )) || die "端口无效：$port"
[[ "$timezone" =~ ^[A-Za-z_+-]+(/[A-Za-z0-9_+-]+)*$ ]] || die "时区格式无效：$timezone"
[[ -z "$domain" || "$domain" =~ ^[A-Za-z0-9.-]+$ ]] || die "域名格式无效：$domain"
if [[ -n "$access_code" ]]; then
  [[ "$access_code" =~ ^[A-Za-z0-9._~-]{6,64}$ ]] || die "访问码需要 6-64 位字母、数字或 ._~-"
fi

[[ -z "$class_models" || "$class_models" =~ ^[A-Za-z0-9._:/=,-]+$ ]] || die "--class-models 格式：别名=模型ID,别名2=模型ID2"
[[ -z "$class_url" || "$class_url" =~ ^https?://[A-Za-z0-9.:/_-]+$ ]] || die "--class-upstream-url 无效：$class_url"
[[ -z "$class_web_model" || "$class_web_model" =~ ^[A-Za-z0-9._:/-]+$ ]] || die "--class-web-model 格式无效"
[[ -z "$class_key_file" || "$class_key_stdin" == false ]] || die "--class-key-file 和 --class-key-stdin 只能选一个"
[[ "$class_off" == false || ( -z "$class_models$class_key_file$class_url$class_web_model" && "$class_key_stdin" == false ) ]] || die "--class-off 不能和其他 --class-* 一起用"

# 讲师的方舟 Key：只从文件或标准输入读，经 scp 传到服务器，不出现在命令行、ps 和日志里。
class_key=""
if [[ -n "$class_key_file" ]]; then
  [[ -r "$class_key_file" ]] || die "读不到 $class_key_file"
  class_key="$(tr -d '\r\n' < "$class_key_file")"
elif [[ "$class_key_stdin" == true ]]; then
  if [[ -t 0 ]]; then read -r -s -p "粘贴讲师的方舟 API Key（不回显）：" class_key; echo; else IFS= read -r class_key || true; fi
  class_key="${class_key//$'\r'/}"
fi
if [[ "$class_key_stdin" == true || -n "$class_key_file" ]]; then
  [[ "$class_key" =~ ^[A-Za-z0-9._~+/=:-]{8,256}$ ]] || die "方舟 Key 为空或含有不支持的字符"
fi

ssh_opts=(-o ConnectTimeout=15)
remote() { ssh "${ssh_opts[@]}" "$target" "$@"; }

# 1. 构建 installDist（与 scripts/gradle.sh 一样把构建目录放在仓库外时，也能找到产物）。
if [[ -z "$dist" ]]; then
  if [[ "$skip_build" == false ]]; then
    echo "==> 构建 web-server（-Pxiaoman.iosOnly=true，不需要 Android SDK）"
    if [[ -n "${XIAOMAN_TOOLCHAIN:-}" || -n "${XIAOMAN_BUILD_DIR:-}" ]]; then
      "$here/scripts/gradle.sh" -Pxiaoman.iosOnly=true :web-server:installDist
    else
      "$here/gradlew" -p "$here" -Pxiaoman.iosOnly=true :web-server:installDist
    fi
  fi
  for candidate in "${XIAOMAN_BUILD_DIR:-/nonexistent}/web-server/install/web-server" "$here/web-server/build/install/web-server"; do
    [[ -x "$candidate/bin/web-server" ]] && { dist="$candidate"; break; }
  done
  if [[ -z "$dist" && -n "${XIAOMAN_TOOLCHAIN:-}" ]]; then
    key="$(printf '%s' "$here" | { sha1sum 2>/dev/null || shasum -a 1; } | cut -c1-12)"
    candidate="$(dirname "$XIAOMAN_TOOLCHAIN")/builds/$key/out/web-server/install/web-server"
    [[ -x "$candidate/bin/web-server" ]] && dist="$candidate"
  fi
fi
[[ -n "$dist" && -x "$dist/bin/web-server" ]] || die "找不到 installDist 产物，请用 --dist 指定 .../install/web-server 目录"
echo "==> 产物：$dist"

# 2. 确定网址。
if [[ "$sslip" == true ]]; then
  if [[ -z "$sslip_ip" ]]; then
    host_part="${target#*@}"
    if [[ "$host_part" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then sslip_ip="$host_part"
    else
      sslip_ip="$(remote "curl -fsS --max-time 5 https://api.ipify.org || curl -fsS --max-time 5 https://ifconfig.me" 2>/dev/null || true)"
    fi
  fi
  [[ "$sslip_ip" =~ ^[0-9]+(\.[0-9]+){3}$ ]] || die "没拿到服务器公网 IP，请写成 --sslip 203.0.113.10"
  site="$sslip_ip.sslip.io"
else
  site="$domain"
fi
echo "==> 网址：https://$site"

# 3. 打包上传（产物 + unit + Caddyfile 模板 + 服务器端安装步骤）。访问码通过文件传输，不出现在命令行里。
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
umask 077
release="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$work/bundle"
tar -C "$(dirname "$dist")" -czf "$work/bundle/web-server.tgz" "$(basename "$dist")"
cp "$here/deploy/xiaoman-web.service" "$here/deploy/Caddyfile.template" "$work/bundle/"
{
  printf 'RELEASE=%q\nSITE=%q\nPORT=%q\nTZ_NAME=%q\nWITH_NODE=%q\nINSTALL_DEPS=%q\n' \
    "$release" "$site" "$port" "$timezone" "$with_node" "$install_deps"
  printf 'NEW_ACCESS_CODE=%q\n' "$access_code"
  printf 'CLASS_MODELS=%q\nCLASS_URL=%q\nCLASS_WEB_MODEL=%q\nCLASS_OFF=%q\n' "$class_models" "$class_url" "$class_web_model" "$class_off"
} > "$work/bundle/settings"
if [[ -n "$class_key" ]]; then printf '%s\n' "$class_key" > "$work/bundle/class.key"; fi
cp "$here/deploy/xiaoman-tokens" "$work/bundle/"
cat > "$work/bundle/remote.sh" <<'REMOTE'
#!/usr/bin/env bash
# Runs on the server as root, from the uploaded bundle directory.
set -euo pipefail
cd "$(dirname "$0")"
source ./settings
say() { echo "[服务器] $*"; }
have() { command -v "$1" >/dev/null 2>&1; }
apt_ok() { have apt-get && [[ "$INSTALL_DEPS" == true ]]; }

# Java 17+
java_major() { java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
if ! have java || [[ "$(java_major)" -lt 17 ]]; then
  if apt_ok; then say "安装 Java 17 运行时"; apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jre-headless
  else
    echo "缺少 Java 17 运行时。Ubuntu/Debian：sudo apt-get install -y openjdk-17-jre-headless；"
    echo "CentOS/Alibaba Cloud Linux：sudo dnf install -y java-17-openjdk-headless。装好后重新运行，或加 --install-deps。"; exit 2
  fi
fi
say "Java：$(java -version 2>&1 | head -1)"

# Caddy
if ! have caddy; then
  if apt_ok; then
    say "安装 Caddy（官方 apt 源）"
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq && apt-get install -y -qq caddy
  else
    echo "缺少 Caddy。安装方法见 https://caddyserver.com/docs/install ，装好后重新运行，或加 --install-deps。"; exit 2
  fi
fi

# Node.js (12306)
stdio_mode=off
if [[ "$WITH_NODE" == true ]]; then
  if ! have node || ! have npx; then
    if apt_ok; then say "安装 Node.js"; DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs npm
    else echo "缺少 Node.js（12306 需要）。请先安装 Node.js 18+，或加 --install-deps。"; exit 2; fi
  fi
  stdio_mode=auto
  node_major="$(node -v 2>/dev/null | sed 's/^v\([0-9]*\).*/\1/')"
  if [[ "${node_major:-0}" -lt 18 ]]; then
    say "Node.js 版本 $(node -v 2>/dev/null) 太旧（12306-mcp 需要 18+），先不开启 12306。可用 NodeSource 安装新版后重新运行。"
    stdio_mode=off
  fi
fi

# 用户与目录
id xiaoman >/dev/null 2>&1 || useradd --system --home-dir /var/lib/xiaoman-web --shell /usr/sbin/nologin xiaoman
install -d -o xiaoman -g xiaoman -m 0750 /var/lib/xiaoman-web
install -d -m 0755 /opt/xiaoman-web/releases
install -d -m 0750 -g xiaoman /etc/xiaoman-web

dest="/opt/xiaoman-web/releases/$RELEASE"
mkdir -p "$dest"
tar -xzf web-server.tgz -C "$dest" --strip-components=1
chmod -R a+rX "$dest"

# 访问码：新给的优先；否则沿用旧的；都没有就随机生成。
env_file=/etc/xiaoman-web/env
old_code=""
[[ -f "$env_file" ]] && old_code="$(sed -n 's/^XIAOMAN_WEB_ACCESS_CODE=//p' "$env_file" | head -1)"
code="${NEW_ACCESS_CODE:-$old_code}"
old_value() { [[ -f "$env_file" ]] && sed -n "s/^$1=//p" "$env_file" | head -1 || true; }

# 课堂令牌：新给的设置优先，否则沿用旧的；Key 只放在 0640 的文件里。
key_file=/etc/xiaoman-web/class-upstream.key
if [[ "$CLASS_OFF" == true ]]; then
  rm -f "$key_file"; class_models="" class_url="" class_web_model=""
else
  if [[ -f class.key ]]; then install -o root -g xiaoman -m 0640 class.key "$key_file"; rm -f class.key; fi
  class_models="${CLASS_MODELS:-$(old_value XIAOMAN_CLASS_MODELS)}"
  class_url="${CLASS_URL:-$(old_value XIAOMAN_CLASS_UPSTREAM_URL)}"
  class_web_model="${CLASS_WEB_MODEL:-$(old_value XIAOMAN_CLASS_WEB_MODEL)}"
fi
class_tuning="$( [[ -f "$env_file" && "$CLASS_OFF" != true ]] && grep -E '^XIAOMAN_CLASS_(TOKENS|TOKEN_RPM|TOKEN_CONCURRENCY|MAX_CONCURRENT|MAX_BODY_MB|TIMEOUT_SECONDS)=' "$env_file" || true)"
class_state=off
if [[ -n "$class_models" && -f "$key_file" ]]; then class_state=on
elif [[ -n "$class_models" ]]; then say "已设置课堂模型白名单，但服务器上还没有方舟 Key（用 --class-key-file 或 --class-key-stdin 上传），课堂令牌保持关闭"
elif [[ -f "$key_file" ]]; then say "服务器上有方舟 Key，但没有模型白名单（--class-models），课堂令牌保持关闭"
fi
generated=false
if [[ -z "$code" ]]; then code="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 12)"; generated=true; fi
umask 027
cat > "$env_file.new" <<ENV
# 小满网页版设置。改完后：sudo systemctl restart xiaoman-web
XIAOMAN_WEB_ACCESS_CODE=$code
XIAOMAN_PORT=$PORT
XIAOMAN_TZ=$TZ_NAME
TZ=$TZ_NAME
# auto：PATH 里有 node 和 npx 时开启 12306；off：关闭
XIAOMAN_STDIO_MCP=$stdio_mode
JAVA_OPTS=-Xmx384m -XX:+UseSerialGC
ENV
if [[ "$class_state" == on ]]; then
  {
    echo "# 课堂令牌：讲师 Key 在 $key_file；令牌用 sudo xiaoman-tokens 管理"
    echo "XIAOMAN_CLASS_UPSTREAM_KEY_FILE=$key_file"
    echo "XIAOMAN_CLASS_MODELS=$class_models"
    [[ -n "$class_url" ]] && echo "XIAOMAN_CLASS_UPSTREAM_URL=$class_url"
    [[ -n "$class_web_model" ]] && echo "XIAOMAN_CLASS_WEB_MODEL=$class_web_model"
    [[ -n "$class_tuning" ]] && printf '%s\n' "$class_tuning"
    true
  } >> "$env_file.new"
fi
chgrp xiaoman "$env_file.new"; chmod 0640 "$env_file.new"; mv "$env_file.new" "$env_file"

install -m 0644 xiaoman-web.service /etc/systemd/system/xiaoman-web.service
install -m 0755 xiaoman-tokens /usr/local/bin/xiaoman-tokens
previous="$(readlink -f /opt/xiaoman-web/current 2>/dev/null || true)"
ln -sfn "$dest" /opt/xiaoman-web/current
[[ -n "$previous" && "$previous" != "$dest" ]] && echo "$previous" > /opt/xiaoman-web/previous
systemctl daemon-reload
systemctl enable xiaoman-web >/dev/null 2>&1
systemctl restart xiaoman-web

# Caddy：备份旧配置后写入。
[[ -f /etc/caddy/Caddyfile ]] && cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak.$RELEASE"
sed -e "s/__SITE__/$SITE/" -e "s/__PORT__/$PORT/" Caddyfile.template > /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
systemctl enable caddy >/dev/null 2>&1 || true
systemctl reload caddy 2>/dev/null || systemctl restart caddy

# 预热 npx 缓存，避免第一次查火车票时超时。
if [[ "$stdio_mode" == auto ]]; then
  say "预热 12306-mcp（下载 npm 包）"
  # 在服务用户可读的目录里跑；ssh 会话的 /root 对 xiaoman 不可访问，npx 会报 EACCES。
  (cd /var/lib/xiaoman-web && sudo -u xiaoman HOME=/var/lib/xiaoman-web timeout 120 npx -y 12306-mcp </dev/null >/dev/null 2>&1) || true
  if find /var/lib/xiaoman-web/.npm/_npx -maxdepth 3 -name 12306-mcp -type d 2>/dev/null | grep -q .; then say "12306-mcp 已缓存"; else say "预热未完成，第一次使用时会再下载"; fi
fi

# 防火墙提示：只开 80/443。
if have ufw && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
  say "ufw 已放行 80/443；不要放行 $PORT"
else
  say "请在云厂商安全组里只放行 TCP 80 和 443（以及你的 ssh 端口），不要放行 $PORT"
fi

# 本机健康检查
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/info" >/dev/null; then break; fi
  sleep 1
done
curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/info" >/dev/null || { journalctl -u xiaoman-web -n 30 --no-pager; exit 3; }
say "web-server 已在 127.0.0.1:$PORT 运行（版本目录 $dest）"
journalctl -u xiaoman-web -n 5 --no-pager -o cat | grep -F -e '12306' -e '课堂令牌' || true
if [[ "$generated" == true ]]; then echo "ACCESS_CODE_GENERATED=$code"; fi
REMOTE
chmod +x "$work/bundle/remote.sh"

echo "==> 上传到 $target"
remote_dir="$(remote 'mktemp -d /tmp/xiaoman-web.XXXXXX')"
scp -q "${ssh_opts[@]}" -r "$work/bundle/." "$target:$remote_dir/"
echo "==> 在服务器上安装（需要 sudo）"
# -tt：sudo 可能要输入密码。输出同时写入本地临时文件，用来取出随机生成的访问码。
set +e
remote -tt "sudo bash '$remote_dir/remote.sh'; status=\$?; rm -rf '$remote_dir'; exit \$status" | tee "$work/remote.log"
status=${PIPESTATUS[0]}
set -e
[[ $status -eq 0 ]] || die "服务器端安装失败（退出码 $status），请看上面的输出"

generated_code="$(sed -n 's/^ACCESS_CODE_GENERATED=\([A-Za-z0-9]*\).*/\1/p' "$work/remote.log" | tail -1)"

# 4. 公网健康检查：证书第一次申请可能要几十秒。
echo "==> 检查 https://$site/api/info"
ok=false
for _ in $(seq 1 30); do
  if curl -fsS --max-time 5 "https://$site/api/info" >/dev/null 2>&1; then ok=true; break; fi
  sleep 3
done
if [[ "$ok" == true ]]; then
  echo "==> 完成：https://$site"
  curl -fsS --max-time 5 "https://$site/api/info"; echo
else
  echo "公网还访问不到 https://$site/api/info。请检查：安全组是否放行 80/443、域名解析是否生效；"
  echo "服务器上查看：sudo journalctl -u caddy -n 50 --no-pager"
fi
if [[ -n "$generated_code" ]]; then
  echo "访问码（只显示这一次，发给学员）：$generated_code"
elif [[ -n "$access_code" ]]; then
  echo "访问码已设置为你指定的值。"
else
  echo "访问码沿用服务器上原来的设置（sudo cat /etc/xiaoman-web/env 查看）。"
fi
