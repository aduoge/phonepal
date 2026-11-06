# 部署小满网页版

把 `web-server` 装到一台有公网 IP 的 Linux 云服务器上，Caddy 负责 HTTPS，学员用 iPhone 的 Safari 打开网址、添加到主屏幕。

```
iPhone Safari ──HTTPS 443──> Caddy（自动证书） ──http 127.0.0.1:8787──> web-server（systemd 服务 xiaoman-web）
                                                                         ├─> 模型接口（学员自己的 Key，或课堂令牌 → 讲师的 Key）
                                                                         └─> MCP 服务（高德、AI 热点……）
```

| 文件 | 作用 |
|---|---|
| `install.sh` | 在你自己的电脑上运行：构建、上传、安装 Java/Caddy（可选）、写配置、启动、健康检查 |
| `rollback.sh` | 切回上一个版本，或列出、切换到指定版本 |
| `xiaoman-web.service` | systemd 服务，web-server 只监听 127.0.0.1 |
| `Caddyfile.template` | Caddy 反向代理模板，`__SITE__`、`__PORT__` 由脚本替换 |
| `xiaoman-tokens` | 课堂令牌管理命令，装到服务器 `/usr/local/bin`，以服务用户运行 `web-server tokens` |

## 准备

- 一台 Linux 服务器（Ubuntu 22.04/24.04、Debian 12 测试路径最顺；1 核 2G 足够 30 人课堂），ssh 账号有 sudo。
- 云厂商安全组只放行 TCP 80、443 和 ssh 端口。8787 不要放行。
- 网址二选一：
  - 自有域名：把 A 记录指向服务器公网 IP，例如 `xiaoman.example.com`。
  - 没有域名：用 `<公网IP>.sslip.io`，例如 `203.0.113.10.sslip.io`，sslip.io 会把它解析回这个 IP，Caddy 照样能申请证书。
- 本机能构建：仓库根目录有 `./gradlew`，需要 JDK 17；只构建网页版，不需要 Android SDK。

## 一键安装

```bash
# 自有域名
deploy/install.sh ubuntu@203.0.113.10 --domain xiaoman.example.com --install-deps

# 没有域名
deploy/install.sh ubuntu@203.0.113.10 --sslip --install-deps

# 指定访问码、时区，并开启 12306 火车票（会在服务器上装 Node.js）
deploy/install.sh ubuntu@203.0.113.10 --sslip --access-code camp2-xiaoman --timezone Asia/Shanghai --with-node --install-deps
```

脚本做的事：

1. 本机构建 `:web-server:installDist`（`-Pxiaoman.iosOnly=true`）。已有产物可用 `--dist DIR --skip-build`。
2. 打包上传到服务器临时目录，用 sudo 执行服务器端步骤。
3. 检查 Java 17：缺了就提示安装命令；加 `--install-deps` 时用 apt 安装 `openjdk-17-jre-headless`。Caddy 同理（官方 apt 源）。
4. 新建系统用户 `xiaoman`，版本放到 `/opt/xiaoman-web/releases/<时间>`，`/opt/xiaoman-web/current` 指向当前版本。
5. 写 `/etc/xiaoman-web/env`（权限 0640，只有 root 和服务能读）：访问码、端口、时区、12306 开关、JVM 内存。
6. 安装并启动 systemd 服务 `xiaoman-web`，写入 `/etc/caddy/Caddyfile`（旧文件备份为 `Caddyfile.bak.<时间>`），重载 Caddy。
7. 健康检查：服务器本机 `http://127.0.0.1:8787/api/info`，再从你的电脑访问 `https://<网址>/api/info`（第一次申请证书可能要几十秒）。

没有给 `--access-code` 时，第一次安装会随机生成一个，只在结束时打印一次；之后再运行不传就沿用原来的。

## 课堂上怎么用

1. 学员用 Safari 打开 `https://<网址>`，输入访问码。
2. 「设置」里填模型地址、模型名（方舟接入点 ID）和自己的 API Key。
3. 「服务」里勾选 AI 热点（免 Key）、高德等；点「测试连接」。
4. Safari 底部「分享 → 添加到主屏幕」，之后从桌面图标打开。

## 课堂令牌（讲师 Key 兜底）

没注册成功火山账号的学员，可以用讲师发的「课堂令牌」（形如 `xm-7k3f9q…`，每人一个）。服务器持有讲师的方舟 Key 并代为调用，学员只拿到令牌，永远拿不到真实 Key。安卓 App 和 iPhone 网页版都能用，按方舟返回的 usage 记账，有每日/总量上限，课后统一作废。没配置时这项功能完全关闭，其他行为不变。

### 开启（一次）

```bash
# Key 放在本机文件里（或用 --class-key-stdin 粘贴），不要写在命令行上
deploy/install.sh ubuntu@203.0.113.10 --sslip \
  --class-models xiaoman-class=doubao-seed-1-6-250615 \
  --class-key-file ~/ark-key.txt
```

- `--class-models`：模型白名单，`学员填的名字=真实模型 ID（或接入点 ep-…）`，逗号分隔多个。学员请求的模型必须在白名单里；操作模型要看截图，请选支持图片输入的模型。
- Key 传到服务器 `/etc/xiaoman-web/class-upstream.key`（root:xiaoman，0640），env 里只写文件路径。以后再运行 install.sh 不带这些参数就沿用；`--class-off` 关闭并删除 Key 文件。
- 限额等可选项直接写进 `/etc/xiaoman-web/env` 后重启服务（再次安装会保留）：`XIAOMAN_CLASS_TOKEN_RPM`（每令牌每分钟请求数，默认 30）、`XIAOMAN_CLASS_TOKEN_CONCURRENCY`（每令牌同时请求数，默认 2）、`XIAOMAN_CLASS_MAX_CONCURRENT`（全服务器同时请求数，默认 32）、`XIAOMAN_CLASS_MAX_BODY_MB`（请求体上限，默认 8）、`XIAOMAN_CLASS_TIMEOUT_SECONDS`（默认 180）、`XIAOMAN_CLASS_TOKENS`（令牌文件，默认 `/var/lib/xiaoman-web/class-tokens.json`）。
- `XIAOMAN_CLASS_THINKING`（默认 disabled，关闭深度思考省费用；keep 为原样转发）
- 令牌文件生成之前功能仍是关闭的；生成后约 1 秒内自动开启，不用重启。启动日志和 `/api/info` 的 `classTokens` 字段显示状态。

### 课前：生成令牌

在服务器上运行（`xiaoman-tokens` 会自动以服务用户 xiaoman 运行）：

```bash
sudo xiaoman-tokens generate 30 --days 2 --daily 500000 --total 2000000 \
  --csv --base-url https://203.0.113.10.sslip.io/v1 --model xiaoman-class > tokens.csv
sudo xiaoman-tokens list
```

- 标签自动编号为「学员 01」「学员 02」…，再次生成会接着编号（`--label A班` 换前缀）。
- 明文令牌只在生成时输出一次，文件里只存 SHA-256 哈希。`tokens.csv` 带 BOM，Excel 可直接打开；用完删除。
- 上限单位是模型 token（输入 + 输出）。500 元预算可以粗算：总上限 × 人数 × 单价不超过预算，并留出余量；`--daily none` / `--total none` 表示不限。
- 过期默认 3 天，可用 `--days`、`--hours` 或 `--expires "2026-11-08 22:00"`。

### 课上：发放与查看

把 CSV 里每人一行发给学员（私聊，不要发到群里）。学员怎么填：

| | 填写 |
|---|---|
| 安卓 App「设置」 | 聊天模型和操作模型都填：Base URL `https://<网址>/v1`，模型名 `xiaoman-class`（白名单里的名字），API Key 填课堂令牌。可以先填聊天模型，再点操作模型下的「与聊天模型相同」复制地址和 Key，模型名另填 |
| iPhone 网页版「设置」 | 选「使用课堂令牌」，只粘贴令牌，点保存；不用填模型地址、模型名和 Key |

Base URL 必须以 `/v1` 结尾、后面不要加 `/chat/completions`（App 会自己拼）。App 走 `/v1` 不需要访问码；网页版仍要先输入访问码。

```bash
sudo xiaoman-tokens list                         # 今日/每日上限、累计/总上限、请求数、最近使用（运行中的用量每 5 秒写回一次）
sudo xiaoman-tokens limit 学员07 --daily 800000   # 给某人加额度；--all 调整全部
sudo xiaoman-tokens revoke 学员07                 # 令牌泄露时单独作废（可用标签、完整令牌或 xm-7k3 这样的前缀）
sudo xiaoman-tokens restore 学员07                # 误作废时恢复
```

学员遇到的错误都是 OpenAI 格式的 JSON：令牌无效/作废/过期返回 401，超额或太频繁返回 429（`daily_quota_exceeded`、`rate_limit_exceeded` 等）。

### 课后：统一作废

```bash
sudo xiaoman-tokens revoke-all
deploy/install.sh ubuntu@203.0.113.10 --sslip --class-off   # 可选：彻底关闭并删除服务器上的讲师 Key
```

之后建议在方舟控制台删除或轮换这把 Key。

### 自助领取（按 TRAE 论坛账号，每人一份）

不想逐个私发令牌时，可以让学员自己领：打开 `https://<网址>/claim`（不需要访问码），填 TRAE 论坛用户名 → 页面给出一句一次性验证短语（如「领取小满令牌 K7Q3M2」，10 分钟有效）→ 学员用这个账号在领取帖里回复这句话 → 点「我已回复」，服务器只读论坛公开接口找到这条回复后当场生成令牌，显示一次。同一论坛账号（按论坛用户 ID 认，用户名大小写不敏感）只能领一份；被作废的不会补发。领取页还给出安卓填写方法，并能「一键用于本网页版」（写入网页版的课堂令牌模式）。

需要先开启上面的课堂令牌。课前步骤：

1. 在论坛发一个领取帖，写明「打开 https://<网址>/claim，按页面提示回复验证短语」。记下帖子编号（链接 `/t/标题/465300` 里的数字）。帖子要公开可见（未登录也能打开）。
2. 配置并重启（再次安装会保留这些设置）：

   ```bash
   deploy/install.sh ubuntu@203.0.113.10 --sslip --claim-topic 465300 --claim-expires "2026-11-12 21:00"
   # 可选：--claim-max 50 --claim-total 1000000 --claim-daily 1000000
   #       --claim-created-before 2026-11-01（只限这天前注册的账号） --claim-min-trust 1（最低信任等级）
   ```

   对应 `/etc/xiaoman-web/env` 里的 `XIAOMAN_CLAIM_TOPIC_ID`、`XIAOMAN_CLAIM_EXPIRES`（按 `XIAOMAN_TZ`）、`XIAOMAN_CLAIM_MAX`（默认 50）、`XIAOMAN_CLAIM_TOTAL_TOKENS`（每份总额度，默认 1000000）、`XIAOMAN_CLAIM_DAILY_TOKENS`（默认同总额度）、`XIAOMAN_CLAIM_FORUM`（默认 `https://forum.trae.cn`）、`XIAOMAN_CLAIM_MIN_CREATED_BEFORE`、`XIAOMAN_CLAIM_MIN_TRUST`（默认 0）。启动日志有一行「自助领取：…」，`/api/info` 的 `claim.state` 为 `open`/`full`/`expired`/`off`。
3. 用自己的论坛账号走一遍领取，确认能拿到令牌并聊天，然后 `sudo xiaoman-tokens reset-claim 你的用户名` 把这份收回（或留着自己用）。

课上与课后：

```bash
sudo xiaoman-tokens claims                  # 论坛账号 → 令牌前缀、状态、用量、领取时间；末行是已领份数
sudo xiaoman-tokens revoke 论坛:某某         # 作废某人的令牌（不会补发）；restore 论坛:某某 恢复
sudo xiaoman-tokens reset-claim 某某         # 学员丢了令牌：作废旧令牌并允许该账号重新领
sudo xiaoman-tokens limit --all --expires "2026-11-13 21:00"   # 统一延期（领到的令牌也在内；新领的仍按 XIAOMAN_CLAIM_EXPIRES）
sudo xiaoman-tokens revoke-all              # 课后统一作废
deploy/install.sh ubuntu@203.0.113.10 --sslip --claim-off       # 关闭领取页（已发的令牌不受影响）
```

- 份数满了、到了过期时间或功能关闭时，领取页直接显示对应提示；重置会腾出一份名额。
- 防刷：每个 IP 每分钟最多获取 5 次短语、核对 20 次，全站每分钟各 120 次；用户名先做格式校验再访问论坛；论坛请求 10 秒超时，论坛不可达时提示稍后重试，不发令牌。每次核对最多读帖子最新 300 条回复。
- 刚回复可能要等几秒才能被读到；新账号的回复如果进了论坛审核队列，要等审核通过。
- 日志只记「自助领取 论坛:用户名(xm-xxx) 已发放」，不记完整令牌；令牌标签就是「论坛:用户名」。

### 安全

- 服务器不记录请求和回复内容；日志每次请求一行：接口、状态码、耗时、令牌标签和前 6 位（如 `学员 01(xm-7k3)`）、token 用量。上游 Key 不出现在响应和日志里，上游错误信息会过滤掉 Key。
- 每令牌并发和每分钟请求数、全服务器并发都有上限；请求体超过 8MB 返回 413。Caddy 对 `/v1/*` 放宽到 10MB，其他接口仍是 1MB。
- 令牌文件只能在服务器本机管理，没有网络管理接口。

## 更新

改完代码后重新运行同一条 `install.sh` 命令即可。新版本放进新的 releases 目录再切换，旧版本保留；访问码、时区沿用服务器上的设置。学员浏览器里的设置、Key、记忆都不受影响。

改访问码或时区：重新运行时带上 `--access-code` / `--timezone`，或在服务器上编辑 `/etc/xiaoman-web/env` 后 `sudo systemctl restart xiaoman-web`。

## 回滚

```bash
deploy/rollback.sh ubuntu@203.0.113.10            # 回到上一次安装前的版本
deploy/rollback.sh ubuntu@203.0.113.10 --list     # 看服务器上保留了哪些版本
deploy/rollback.sh ubuntu@203.0.113.10 20261105-221500
```

旧版本不会自动删除，空间不够时手动删 `/opt/xiaoman-web/releases/` 里不用的目录（别删 current 指向的那个）。

## 查看日志与状态

```bash
sudo systemctl status xiaoman-web
sudo journalctl -u xiaoman-web -f          # web-server：只记录接口名、状态码和耗时，不含消息、Key 和记忆
sudo journalctl -u caddy -n 100 --no-pager # 证书申请、反向代理错误
curl -s https://<网址>/api/info            # {"name":"Xiaoman","version":...,"stdioMcp":...}
```

启动日志里有一行说明 12306 是否开启。

## 12306 火车票

12306 是社区的 stdio MCP（`npx -y 12306-mcp`），需要服务器装 Node.js 18+：

- 用 `--with-node` 安装时，服务以 `--stdio-mcp auto` 运行：找到 `node` 和 `npx` 就开启，并预热 npm 缓存（第一次下载可能超过 30 秒，预热后约 3 秒连上）。
- 不加 `--with-node`，或系统自带的 Node.js 太旧（例如 Ubuntu 22.04 apt 里的 12.x），服务以 `--stdio-mcp off` 运行，网页上 12306 显示为不可用。可以按 https://github.com/nodesource/distributions 装新版后重新运行。
- 每个勾选了 12306 的学员会话会在服务器上起一个 Node 进程（约 50-80MB 内存），会话结束即关闭，同时最多 20 个（`--max-stdio` 可调）。子进程只拿到 PATH、HOME、代理等少数环境变量，拿不到访问码。

## 安全注意

- 访问码：拿到网址和访问码的人都能用这台服务器转发请求。课后换一个访问码或停掉服务（`sudo systemctl stop xiaoman-web`）。访问码通过 `/etc/xiaoman-web/env` 传给服务，不出现在命令行和 `ps` 里。
- 只开 80/443：web-server 只监听 127.0.0.1，所有外部访问都经过 Caddy 的 HTTPS。不要把 8787 加进安全组。
- 课堂令牌：讲师 Key 只在服务器上（`/etc/xiaoman-web/class-upstream.key`），见上面「课堂令牌」一节。
- Key 经服务器转发但不落盘：学员的模型 Key 和服务 Key 保存在各自浏览器里，每次对话随请求发给服务器，服务器只在内存里用来调用模型和 MCP，不写磁盘、不写日志；返回给网页的内容会把这些 Key 替换成 `***`。服务器管理员理论上能在内存里看到它们，所以请用自己控制的服务器，并提醒学员用额度有限的 Key、课后可在平台上删除。
- 记忆不在服务器：学员的长期记忆存在各自浏览器的 localStorage 里，每次对话带给服务器、只在这一轮使用。服务器重启或会话过期都不会丢，也不会留在服务器上。
- 不访问内网：模型地址和 MCP 地址解析到 127.0.0.1、10.x、192.168.x、169.254.x 等都会被拒绝，学员不能借服务器探测云上内网。不要在生产环境加 `--allow-private`。
- Caddy 默认不记访问日志。如果要开，按 `Caddyfile.template` 里的注释过滤掉 `X-Xiaoman-Access`、`Authorization` 请求头。
