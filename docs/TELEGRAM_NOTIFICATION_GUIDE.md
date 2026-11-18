# Telegram 交易通知配置

系统只向 Telegram 发送交易状态通知，不接收买入、卖出、平仓、切换 LIVE 等机器人命令。Telegram 故障不会改变订单和仓位状态；待发送消息写入 MySQL Outbox，重启后继续发送。

## 通知卡片与发送时机

Telegram 使用官方 `sendMessage + HTML` 生成手机端易扫描的原生卡片，不依赖图片或额外客户端。Telegram 不支持 CSS 字号和自定义文字颜色，因此用加粗标题及 🟢/🔴/🟡/⚠️ 表达盈利、亏损、处理中和风险状态。

- 开仓卡片仅在实际成交且保护单已提交后发送；显示方向、真实成交均价、杠杆、币数量与 contracts、名义价值、保证金、当前有效止损/止盈、计划最大亏损、可用金额和总资产。
- 平仓卡片把已确认的净盈利/净亏损放在第二行加粗；显示开平仓均价、数量、持仓时长、毛盈亏、手续费、资金费、净盈亏、保证金收益率、可用金额和总资产。
- 若资金费或任一净收益组成尚未完成对账，只显示“已确认毛盈利/毛亏损”，净盈亏显示“同步中”，不会把未知值写成 `0`。
- 其他即时卡片包括：订单提交状态未知、未成交撤单、保护异常、止盈调整、平仓处理中和恢复异常。
- 普通订单刚提交、短暂部分成交和内部 ID 不再推送；部分成交超时且剩余单已撤销后，使用“部分成交并已保护”开仓卡片。
- SWAP 数量优先展示实际币数量，例如 `0.02 ETH（2 contracts）`；换算读取 OKX `ctVal/ctValCcy`，规则不可用时只显示 contracts，不猜测币数量。

## 1. 创建机器人

1. 在 Telegram 搜索官方 `@BotFather`。
2. 发送 `/newbot`，按提示设置名称和用户名。
3. 保存 Bot Token。Token 等同密码，不要发到聊天、Git、截图或日志；泄漏后在 BotFather 使用 `/revoke` 立即轮换。
4. 打开新机器人，发送 `/start`。机器人不能主动联系从未与它开始会话的用户。

## 2. 获取 Chat ID

先向机器人发送一条消息，然后在本机执行（不要把返回结果公开）：

```bash
curl "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates"
```

读取 `result[].message.chat.id`：私聊通常为正数，群组通常为负数。群组需要先把机器人加入群，并在群里发送一条消息；频道需要把机器人设为管理员并使用频道 Chat ID。

## 3. 配置 `.env`

只编辑仓库根目录 `.env`：

```dotenv
NOTIFICATION_ENABLED=true
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=填入BotFather生成的Token
TELEGRAM_CHAT_ID=填入ChatID
TELEGRAM_SEND_TIMEOUT_SECONDS=8
TELEGRAM_MAX_ATTEMPTS=5
```

不要把 `.env` 放入 `src/main/resources`，否则本地启动不会自动加载，而且可能被打进 JAR。部署时运行：

```bash
./deploy.sh --check
./deploy.sh
```

## 4. 验证

登录系统取得会话后调用：

```bash
curl -X POST -b cookies.txt http://127.0.0.1:8080/api/quant/notifications/telegram/test
curl -b cookies.txt http://127.0.0.1:8080/api/quant/notifications/status
```

状态包含 `pending`、`retry`、`failed` 和 `lastSentAt`。真实验收应至少观察一次受保护开仓、未成交撤单、风险告警和平仓结算卡片；测试消息成功不代表交易通知全链路已验收。

## 5. 常见错误

- HTTP 400：Chat ID 或消息参数错误。
- HTTP 401：Token 无效或已撤销。
- HTTP 403：用户未 `/start`、机器人被拉黑或群权限不足。
- HTTP 429：触发 Telegram 限流，系统按 `retry_after` 延迟重试。
- HTTP 5xx/超时：有界退避重试，达到上限后标记 `FAILED`，不会影响交易。

API 以 Telegram 官方 Bot API 为准：https://core.telegram.org/bots/api
