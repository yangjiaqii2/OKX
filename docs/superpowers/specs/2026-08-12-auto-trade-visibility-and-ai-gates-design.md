# 自动交易可用性与 AI 软硬规则修复设计

日期：2026-08-12

## 目标

修复系统控制显示自动交易已开启、底层配置却拒绝执行的问题；让 AI 明确区分不可绕过的硬风险与仅供参考的规则信号；让自动交易审计页展示真实决策依据并避免跨运行环境时间差导致新记录被旧记录遮挡。

## 设计

1. `SystemControlController` 同时读取持久化运行开关与 `quant.agent.auto-trade.enabled`。配置关闭时，策略引擎显示 `AUTO_DISABLED_BY_CONFIG`，交易引擎回到 `MANUAL_CONFIRM`，原因显示 `AUTO_TRADE_DISABLED_BY_CONFIG`；不能再显示 `AUTO_ENABLED`。
2. AI 候选仍必须先通过 instrument、价格、成交额、Funding、市场风险、重大新闻和极端价差硬门槛。传给 AI 的 `signalType`、综合评分、规则原因和规则风险改名为 `softRule*`，Prompt 明确它们不是硬否决；AI 可以独立返回 `TRADE` 或 `WAIT`，但不能绕过后续确定性风控。
3. 自动交易审计页同时展示原始评分、最终评分、信号、AI 来源、置信度、入场方式和选择理由。后端按自增 `id` 倒序分页，避免历史时区错位记录覆盖当前运行记录。
4. MySQL JDBC 连接统一使用 UTC 会话；服务日志仍使用 Asia/Shanghai，API 返回 `Instant`，浏览器负责本地化显示。历史错位数据不自动改写。
5. 根目录 `.env` 是 Docker 唯一部署配置源。把已有 AI 配置安全迁移到根目录，设置 `QUANT_AGENT_AUTO_TRADE_ENABLED=true`；保持 `TRADING_MODE=DEMO`，不切换 LIVE。

## 安全边界

- 不降低 OKX 写网关、TradingMode、readiness、订单幂等、持仓、日损、流动性与精度硬保护。
- 不把 AI `WAIT` 自动改成交易，也不保证产生订单。
- 不修改评分阈值、策略指标、杠杆和止盈止损参数。
- AI 技术故障继续走规则保底；AI 合法 `WAIT` 继续安全跳过。
- 不迁移或删除现有交易记录。

## 验收

- 配置 Guard 关闭、运行开关开启时，API 不再返回 `AUTO_ENABLED`。
- AI Prompt 明确软规则语义，候选字段不再叫 `riskTags`。
- 审计 API 按插入顺序返回最新记录，前端可看到两类评分和 AI 理由。
- 新 Docker 记录的 API 时间与浏览器显示只相差正常时区转换，不再额外偏移八小时。
- DEMO 容器中自动交易配置和 AI Key 均生效，日志不再出现 `auto_trade_disabled_by_config`。
