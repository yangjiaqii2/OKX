# Quant Assistant

OKX USDT 永续合约量化实盘控制台。当前系统主流程聚焦 OKX 合约扫描、AI 计划、人工确认、自动交易、账户风控和实盘执行。

## 当前能力

- OKX 合约机会池：读取 OKX 行情、K 线、资金费率、成交量、持仓量、订单簿、BTC/ETH 环境和新闻风险，生成候选合约与评分。
- AI 交易计划：基于候选合约生成开仓方向、入场价、止损、止盈、杠杆、置信度和风险说明。
- 待确认订单：手动计划先进入人工审核台，填写保证金并复核后才提交 OKX 实盘。
- 自动交易：可在系统控制页开启总预算、严格风控/无风控模式、最低分数和最低杠杆。
- 风控与账户：查看 OKX 总权益、可用余额、持仓快照、风险等级、拒绝原因、建议杠杆和自动交易收益概览。
- 持仓操作：支持从控制台提交 OKX 平仓请求。
- 账号绑定：每个登录用户独立绑定 OKX API Key、Secret、Passphrase 到数据库，支持验证、解绑和脱敏回显。
- 审计记录：按登录用户记录自动交易成功提交到 OKX 的委托，便于追踪成交链路。
- 登录保护：数据库用户登录后进入量化实盘控制台，密码使用 PBKDF2-SHA256 哈希保存。

## 技术栈

- 后端：Java 17、Spring Boot 3、Spring MVC、Spring Data JPA、Flyway、MySQL。
- 前端：React 18、React Admin 5、MUI 6、Vite、TypeScript。
- 交易所：OKX REST / WebSocket。
- AI：OpenAI-compatible 接口，默认模型配置为 `kimi-k2.6`。
- 兼容组件：仓库内仍保留少量历史兼容服务；当前 README 和前端主入口按 OKX 合约实盘控制台维护。

## 重要风险

当前默认按实盘交易实现：

```yaml
trading:
  mode: SEMI_AUTO
  okx-live-enabled: true
  require-user-confirm: true
```

确认订单、自动交易和平仓都会调用 OKX 实盘接口。不要绑定带提现权限的 OKX API；建议只开启读取和交易权限，并在 OKX 后台配置 IP 白名单。第一次使用建议先用小额预算、严格风控模式、单仓验证完整链路。

## 环境要求

- JDK 17
- Maven 3.9+
- Node.js 18+ 和 npm
- MySQL 8
- 可访问 OKX API 的网络环境

## 配置

后端配置入口在 `src/main/resources/application.yml`，本地覆盖可使用环境变量：

```text
MYSQL_HOST=127.0.0.1
MYSQL_PORT=33066
MYSQL_DATABASE=OKX
MYSQL_USERNAME=root
MYSQL_PASSWORD=<password>

QUANT_AUTH_USERNAME=admin
QUANT_AUTH_PASSWORD=admin123

OKX_API_KEY=<optional>
OKX_API_SECRET=<optional>
OKX_API_PASSPHRASE=<optional>

AI_API_KEY=<optional>
AI_BASE_URL=https://api.moonshot.cn/v1
AI_MODEL=kimi-k2.6
```

也可以在前端“OKX账号绑定”页面录入 OKX API 信息。绑定后数据会按当前登录用户写入 `quant_okx_credential`，接口只返回脱敏 API Key，不回显 Secret 和 Passphrase。谁开启自动交易，后续定时自动交易执行就使用谁绑定的 OKX API。

## 启动

### Windows 一键启动

```powershell
cd D:\code\View
.\start-dev.ps1
```

只检查启动命令：

```powershell
.\start-dev.ps1 -CheckOnly -SkipInstall
```

当前 OKX 合约主流程不需要兼容服务，可跳过：

```powershell
.\start-dev.ps1 -SkipAkShare
```

启动后访问：

```text
http://<this-machine-ip>:5173
```

默认登录账号：

```text
admin / admin123
```

首次启动会用 `QUANT_AUTH_USERNAME` 和 `QUANT_AUTH_PASSWORD` 初始化管理员账号；初始化后登录校验走数据库用户表 `quant_auth_user`，修改密码不会再依赖环境变量。

### 手动启动

后端：

```powershell
cd D:\code\View
mvn "-Dmaven.repo.local=D:\code\View\.m2\repository" spring-boot:run
```

前端：

```powershell
cd D:\code\View\frontend
npm install
npm run dev
```

Linux/macOS 可直接使用 Maven 和 npm 对应命令：

```bash
mvn spring-boot:run

cd frontend
npm install
npm run dev
```

前端默认访问后端：

```text
http://<this-machine-ip>:8080/api/quant
```

如后端在其他地址，设置 `VITE_QUANT_API_URL` 覆盖默认值。

## 页面入口

- 量化实盘控制台：账户、风控、持仓、自动交易收益、机会池和待确认动作总览。
- OKX合约：查看当前 OKX 持仓和合约行情，生成 AI 计划，提交平仓。
- 待确认订单：人工复核计划并提交 OKX 实盘。
- 自动交易记录：查看当前登录用户自动交易成功提交到 OKX 的委托记录。
- 账户与风控：查看 OKX 账户余额、持仓快照和风控解释。
- OKX账号绑定：绑定、验证、解绑 OKX API。
- 账号安全：修改当前账号密码，管理员可创建用户、查看用户列表和启停用户。
- 系统控制：紧急停止、恢复运行、开启/关闭自动交易。

## 怎么使用

### 首次使用

1. 启动后端和前端，打开 `http://<this-machine-ip>:5173`。
2. 用默认账号 `admin / admin123` 登录。
3. 进入“账号安全”，修改管理员密码；需要多人登录时由管理员创建新用户。
4. 进入“OKX账号绑定”，填写 `API Key`、`Secret`、`Passphrase`。
5. 点击“验证接口”，确认当前登录用户绑定的 OKX API 返回可用余额和账户模式。
6. 进入“账户与风控”，确认账户余额、持仓、风险状态正常。
7. 进入“OKX合约”，等待自动刷新或手动触发扫描，查看候选、评分、方向、风险标签和当前持仓。
8. 想手动下单时，点击生成 AI 计划，计划会进入“待确认订单”。
9. 进入“待确认订单”，填写本次保证金，复核方向、杠杆、入场价、止损、止盈和风险说明后点击“实盘确认”。
10. 成交和持仓状态以 OKX 为准；前端“账户与风控”和“OKX合约”用于同步查看。

### 日常使用

- 先看“量化实盘控制台”：确认账户模式不是 `OKX_UNBOUND` 或 `OKX_ERROR`。
- 控制台顶部会展示自动交易收益概览；当前版本收益为估算口径：已实现收益暂按 0，未实现收益来自 OKX 当前持仓浮盈，完整净收益后续需接成交、手续费和资金费流水。
- 再看“OKX合约”：优先看高分、`AUTO_TRADE_ALLOWED`、低风险标签、盈亏比足够的候选。
- 手动交易走“生成 AI 计划 -> 待确认订单 -> 填保证金 -> 实盘确认”。
- 自动交易前先进入“系统控制”：设置总预算，选择严格风控，确认紧急停止未开启。
- 任何异常先点“紧急停止”，它会关闭自动交易，但不会删除历史数据或撤销 OKX 已提交订单。

## 系统怎么分析

### 扫描频率和候选池

- 后端启动后会立即扫描一次。
- 默认每 `60` 秒扫描一次 OKX 合约市场，对应 `quant.contract.scan-interval-seconds`。
- 扫描接口读取 OKX `SWAP` ticker，只保留 `*-USDT-SWAP`。
- 初筛条件：
  - 24h 成交额不低于 `50,000,000 USDT`。
  - 24h 振幅不高于 `40%`。
  - 按 24h 成交额取前 `30` 个进入深度分析。
  - 最终按排序分、综合分、成交额取前 `10` 个展示。

### 每个候选分析了哪些数据

- 行情：最新价、24h 开盘价、24h 高低点、24h 成交额、买一卖一价差。
- K 线：`5m` 最近 260 根、聚合 `20m` 最近 60 根、`1H` 最近 72 根、`4H` 最近 60 根、`1D` 最近 30 根。
- 技术指标：EMA20、EMA60、MACD histogram、RSI14、ATR14、ADX、布林带宽、20 周期均量、量能倍数。
- K 线结构：最近摆动高低点、上影线/下影线、连续影线、收盘位置、结构是否顺多/顺空。
- 衍生品数据：资金费率、持仓量、持仓量变化。
- 订单簿：价差 bps、买盘深度、卖盘深度、预估滑点、是否满足流动性门控。
- 市场环境：BTC/ETH 短周期方向、市场风险等级、是否与候选方向冲突。
- 新闻风险：最近 24 小时最多 10 条相关新闻，识别安全事件、交易所限制、监管调查、重大负面和可信利好。

### 信号类型

- `STRONG_LONG`：今日涨幅约 `2%` 到 `10%`，20m 多头结构，量能放大，RSI 在健康区间。
- `PULLBACK_LONG`：今日涨幅约 `5%` 到 `15%`，20m 多头结构，1h/4h 没有明显反向。
- `TREND_SHORT`：今日跌幅约 `-10%` 到 `-3%`，20m 空头结构，量能放大，RSI 没有过度超卖。
- `REVERSAL_SHORT`：涨幅偏高后出现上影、动能转弱或短线转空，按低杠杆反转空处理。
- `WAIT_OVERHEATED`：涨幅超过 `15%` 且 RSI、EMA 距离、上影线或资金费率显示追高风险。
- `WAIT_OVERSOLD`：跌幅超过 `15%` 且 RSI、EMA 距离、下影线或资金费率显示追空风险。
- `NEUTRAL`：涨跌幅接近横盘，趋势弱或 ADX 偏低。
- `NO_TRADE`：数据不足、极端波动、结构不满足、止损/盈亏比不合格或风险过高。

### 候选动作

- `AUTO_TRADE_ALLOWED`：信号可交易、综合分不低于 `80`、5m 入场节奏为 `READY`、新闻风险低。
- `WAIT_CONFIRM`：可观察但需要人工确认，常见原因是新闻风险未知/中等、5m 节奏不完全就绪或分数不足。
- `WAIT`：等待回踩、等待复测或市场结构不明确。
- `NO_TRADE`：禁止生成实盘交易动作。

## 具体打分

系统对每个候选先计算七个 0-100 子分，再按权重合成综合分：

```text
综合分 =
  趋势分       * 25%
+ 量能分       * 25%
+ 流动性分     * 15%
+ 波动质量分   * 10%
+ OI/资金费率分 * 10%
+ 市场环境分    * 8%
+ 新闻风险分    * 7%
```

前端展示的因子分是加权后的贡献值，满分分别是：

```text
趋势 25，量能 25，流动性 15，波动 10，OI/资金 10，市场 8，新闻 7，总计 100
```

### 子分含义

- 趋势分：信号类型、20m/1h/4h 方向、EMA20/EMA60、MACD、结构高低点和今日涨跌幅共同决定。顺势结构加分，多周期冲突扣分。
- 量能分：量能倍数、价格和成交量方向是否一致、是否异常放量共同决定。
- 流动性分：24h 成交额、订单簿价差、买卖盘深度共同决定。成交额越大、价差越小、深度越好分越高。
- 波动质量分：ATR 百分比、影线风险、布林带宽共同决定。波动太小没有空间，波动太大容易降低分数。
- OI/资金费率分：持仓量变化是否支持方向、资金费率是否拥挤、是否达到极端费率共同决定。
- 市场环境分：BTC/ETH 方向是否支持当前候选方向，整体市场风险是否过高。
- 新闻风险分：`LOW` 通常高分；`UNKNOWN` 会降级到人工确认；`HIGH` 或 `CRITICAL` 会禁止自动交易。

### 排序分

候选列表最终不是只按综合分排序，还会计算 `finalRankScore`：

```text
排序分 =
  综合分       * 70%
+ 盈亏比分     * 10%
+ 流动性分     * 8%
+ 量能分       * 7%
+ 市场环境分   * 5%
```

当候选不是可交易信号、订单簿不可交易、过热等待或超跌等待时，排序分会被上限压低。第一名候选还必须满足可交易信号、`AUTO_TRADE_ALLOWED`、流动性不低、量能不低、盈亏比不低于 `1.5`、止损距离不超过 `5%`、资金费率不极端。

### 门控提示与硬性条件

生成计划和自动交易前会检查以下条件。部分条件会直接让候选进入观察或跳过，部分条件会写入交易门控提示和风险标签：

- 综合分不低于 `75`。
- 趋势贡献分不低于 `18/25`。
- 量能贡献分不低于 `17/25`。
- 流动性贡献分不低于 `10/15`。
- 新闻贡献分不低于 `7/7`，否则会提示新闻风险分不足。
- 盈亏比不低于 `1.5`。
- 止损距离不超过 `5%`。
- 信号必须是 `STRONG_LONG`、`PULLBACK_LONG`、`TREND_SHORT` 或 `REVERSAL_SHORT`。
- 新闻风险不能是 `HIGH` 或 `CRITICAL`；`UNKNOWN` 只能进入人工确认。
- 实时价差默认不高于 `8 bps`。
- 市场环境风险不能达到高风险以上。

不满足硬性条件时，系统可能只输出观察计划，不进入实盘提交；不满足提示条件时，计划仍会带上对应风险说明，后续自动交易门控可能继续跳过。

## AI 计划怎么生成

AI 只分析当前候选，不允许推荐其他合约。输入内容包括：

- 合约、最新价、24h 涨跌幅、24h 成交额。
- 趋势方向、综合分、七项因子分、最终排序分。
- 系统建议入场价、止损价、止盈价、盈亏比和杠杆上限。
- 信号类型、候选动作、K 线结构、新闻风险。
- 5m 涨跌幅、量能倍数、资金费率、持仓量、ATR。
- 订单簿价差、买卖盘深度、BTC/ETH 趋势和风险标签。

AI 必须返回严格 JSON：

```json
{
  "action": "OPEN_LONG | OPEN_SHORT | WAIT",
  "orderType": "LIMIT | MARKET",
  "entryPrice": 0,
  "stopLossPrice": 0,
  "takeProfitPrice": 0,
  "leverage": 1,
  "confidence": 0.65,
  "reasonList": [],
  "riskList": [],
  "invalidCondition": ""
}
```

如果 AI 不可用、返回无效 JSON、建议 `WAIT` 或盈亏比不足，系统会使用本地规则兜底。兜底计划仍会继承系统分析的方向、入场价、止损、止盈、杠杆上限和风险标签。

## 手动交易怎么走

1. 在“OKX合约”页面选择候选，点击生成 AI 计划。
2. 系统根据候选、AI 输出、杠杆决策、仓位计算和风控结果生成 `TradePlan`。
3. `TradePlan` 进入“待确认订单”，订单默认 `120` 秒过期。
4. 在“待确认订单”填写保证金金额。
5. 点击“实盘确认”后，系统会重新读取订单簿，检查价差、深度、风险和订单状态。
6. 风控通过后调用 OKX 下单接口。
7. OKX 返回委托号后，订单状态变为已提交；如果 OKX 拒单，会记录拒绝原因。

手动确认时，如果实时深度不足，系统会按订单簿可用深度和 `20%` 深度使用率自动缩小保证金；如果价差超过阈值或风控拒绝，订单不会提交 OKX。

## 自动交易怎么走

### 开启方式

进入“系统控制”：

1. 确认系统不是紧急停止状态。
2. 填写“自动交易总预算”，例如 `100 USDT`。
3. 选择风险模式：
   - `严格风控`：完整执行质量、相关性、门控、预确认刷新、流动性和规则风控。
   - `无风控`：按最低分数强制生成计划，并跳过部分预确认和规则风控，风险显著更高。
4. 设置无风控最低分，范围 `60` 到 `100`，默认 `70`。
5. 设置自动交易最低杠杆，范围 `1` 到 `20`，默认 `1`。
6. 点击开启自动交易。

### 执行链路

每轮扫描后自动交易会按以下顺序执行：

1. 检查配置开关 `quant.agent.auto-trade.enabled`。
2. 检查运行时开关是否已开启，且没有紧急停止。
3. 加全局自动交易锁，避免同一时间重复执行。
4. 读取当前 OKX 持仓和进行中的订单，避免同一合约重复开仓。
5. 计算当前市场状态和最大可持仓数。
6. 从候选中按排序分、综合分、成交额取前 `5` 个作为尝试池。
7. 严格风控模式下执行仓位质量、相关性、候选门控。
8. 为候选生成交易计划；如果计划是观察或无入场，则跳过。
9. 严格风控模式下做预确认刷新：价格偏离、价差、5m 反转、BTC/ETH 反转、新闻风险、资金费率。
10. 按总预算、仓位槽位、候选评分、风险上限、账户可用余额计算本单保证金。
11. 预占预算，生成自动待确认订单。
12. 调用自动确认，提交 OKX 实盘订单。
13. 成功提交后按当前自动交易所属用户记录到“自动交易记录”；跳过和失败主要写后端日志。

### 仓位数量和质量

默认自动交易目标是补到 `3` 个持仓/进行中订单，最多 `3` 个：

- 牛市或熊市环境：最多 `3` 仓。
- 震荡环境：最多 `1` 仓。
- 高波动环境：最多 `1` 仓。
- 风险关闭环境：`0` 仓。

仓位越多，新候选要求越高：

```text
当前 0 仓：综合分 >= 80，排序分 >= 80
当前 1 仓：综合分 >= 84，排序分 >= 84
当前 2 仓：综合分 >= 88，排序分 >= 88
高波动环境：最低综合分和排序分至少 88
```

相关性控制：

- BTC 和 ETH 不计入非 BTC/ETH 同向仓位限制。
- 非 BTC/ETH 的同方向仓位默认最多 `2` 个。
- 高波动环境下非 BTC/ETH 的同方向仓位最多 `1` 个。

### 自动交易预算

系统控制页填写的金额是自动交易总预算，不是每单保证金。默认预算按仓位槽位分配：

```text
第 1 仓：总预算 * 45%
第 2 仓：总预算 * 30%
第 3 仓：总预算 * 25%
```

评分会继续调整可用保证金：

```text
排序分 >= 90：100%
85-90：90%
80-85：80%
75-80：60%
70-75：60%
60-70：60%
60 以下：0，不下单
```

同时还会受以下限制：

- 单笔保证金不能低于 `5 USDT`。
- 单个仓位最多使用总预算的 `50%`。
- 目标使用率 `90%`，最低目标使用率 `80%`，最高使用率 `100%`。
- 已使用预算和已预占预算会从总预算中扣除。
- 如果读取到 OKX 可用余额，本轮分配不能超过账户可用余额。
- 严格风控模式会按止损距离和单笔风险反推最大保证金。

### 预确认刷新

严格风控模式在提交前会再次检查：

- 当前价相对计划入场价偏离不超过 `0.25%`。
- 当前价差不超过 `0.05%`。
- 5m 节奏没有反向。
- BTC/ETH 没有共同反向。
- 新闻风险仍为 `LOW`。
- 资金费率绝对值不超过 `0.0015`。

任一不满足会跳过当前候选，继续尝试下一个候选。

### 无风控模式说明

无风控模式会：

- 使用展示综合分判断是否达到最低分。
- 忽略 `AUTO_TRADE_ALLOWED` 和智能入场等待门控，直接生成可提交计划。
- 跳过预确认刷新。
- 自动确认时绕过实时流动性和规则风控检查。
- 仍受紧急停止、总预算、同合约去重、最小保证金和 OKX 接口结果约束。

这个模式只适合你明确接受更高风险时使用。实盘不建议作为默认模式。

## 杠杆、止损和仓位

- 系统信号会先给一个建议杠杆，最高 `5x`。
- `STRONG_LONG`、`TREND_SHORT` 高分时可到 `4x-5x`；`PULLBACK_LONG` 通常更低；`REVERSAL_SHORT` 最高 `3x`。
- 今日涨幅过高、ATR 过高、资金费率拥挤、新闻风险不低都会降低杠杆。
- 杠杆决策会再次根据波动率、止损距离、资金费率和新闻风险压低杠杆。
- 非 BTC/ETH 合约杠杆上限默认不超过 `5x`。
- 波动率大于 `8%`、止损距离大于 `5%`、资金费率绝对值大于 `0.001` 或新闻高风险时，杠杆可能降到 `1x`。
- 仓位按账户权益、可用余额、止损距离和最大亏损倒推；默认单笔风险约 `1%`，单笔保证金上限约 `5%` 账户权益。
- 计划的盈亏比必须不低于 `1.5`。
- 自动交易提交前如果风险动态上限低于订单杠杆，系统会尝试自动降低杠杆后再检查。

## 字段怎么解读

- `score`：综合分，七项加权后的主评分。
- `finalRankScore`：排序分，用于候选优先级和自动交易尝试顺序。
- `factorScore`：七项加权贡献分，显示每个因素贡献了多少分。
- `signalType`：结构化信号类型，决定是否可交易。
- `action`：系统动作，决定自动交易是否允许。
- `entryType`：入场方式，可能是 `LIMIT`、`WAIT_PULLBACK`、`WAIT_RETEST`、`NO_ENTRY`。
- `riskRewardRatio`：计划盈亏比，低于 `1.5` 会被拦截或降级。
- `stopLossPct`：入场价到止损价的距离百分比，超过 `5%` 不进入自动交易。
- `spreadBps`：订单簿价差，越低越好。
- `bidDepthUsdt` / `askDepthUsdt`：买卖盘深度估算。
- `newsRiskLevel`：新闻风险，`LOW` 才允许自动交易。
- `marketRiskLevel`：BTC/ETH 市场环境风险，过高会限制仓位或禁止交易。

## 常用 API

```http
POST /api/quant/auth/login
POST /api/quant/auth/logout
GET  /api/quant/auth/session
GET  /api/quant/auth/users
POST /api/quant/auth/users
POST /api/quant/auth/password/change
POST /api/quant/auth/users/{username}/password
POST /api/quant/auth/users/{username}/enabled

GET  /api/quant/contract/candidates
GET  /api/quant/contract/candles?instId=BTC-USDT-SWAP&bar=15m
POST /api/quant/contract/scan
POST /api/quant/contract/trade-plan?instId=BTC-USDT-SWAP
POST /api/quant/contract/pending-order?instId=BTC-USDT-SWAP

GET  /api/quant/orders/pending
POST /api/quant/orders/confirm?id=<pending-order-uuid>&marginAmount=10
POST /api/quant/orders/cancel?id=<pending-order-uuid>

GET  /api/quant/account/summary
GET  /api/quant/account/positions
POST /api/quant/account/positions/close?instId=BTC-USDT-SWAP
GET  /api/quant/account/binding-status
POST /api/quant/account/bind
POST /api/quant/account/verify
POST /api/quant/account/unbind

GET  /api/quant/risk/status
GET  /api/quant/system/status
POST /api/quant/system/emergency-stop
POST /api/quant/system/resume
POST /api/quant/system/auto-trade/enable
POST /api/quant/system/auto-trade/disable
GET  /api/quant/auto-trade/records?page=0&size=50
GET  /api/quant/auto-trade/profit/summary
```

## 测试与构建

后端测试：

```powershell
mvn "-Dmaven.repo.local=D:\code\View\.m2\repository" test
```

前端构建：

```powershell
cd D:\code\View\frontend
npm run build
```

Linux/macOS：

```bash
mvn test

cd frontend
npm run build
```

## OKX API 排查

- `API Key`、`Secret`、`Passphrase` 必须来自同一个 OKX API。
- `Passphrase` 是创建 API 时手动填写的口令，不是 OKX 登录密码。
- API 权限需要包含读取和交易，不要开启提现权限。
- 如果返回 401，优先检查 API 三件套、交易权限和 IP 白名单。
- 如果账户或持仓为空，先在“OKX账号绑定”页面点击“验证接口”，再检查 OKX 账户模式和资金账户余额。

## 变更日志

### 2026-06-18 - 用户维度 OKX Key 与自动交易收益概览

- 变更摘要：OKX API 绑定改为按当前登录用户隔离，自动交易开启时记录所属用户，定时自动交易会使用该用户绑定的 OKX Key；控制台新增自动交易收益概览卡片，并优化登录页、OKX 绑定页和账号安全页的移动端交互。
- 影响文件：`src/main/java/com/example/quant/auth/AuthUserContext.java`、`src/main/java/com/example/quant/auth/AuthInterceptor.java`、`src/main/java/com/example/quant/account/*`、`src/main/java/com/example/quant/agent/execution/AutoTradeProfitService.java`、`src/main/java/com/example/quant/agent/execution/AutoTradeRecordEntity.java`、`src/main/java/com/example/quant/controller/AutoTradeRecordController.java`、`src/main/resources/db/migration/V9__user_scoped_okx_credentials.sql`、`src/main/resources/db/migration/V10__user_scoped_auto_trade_records.sql`、`frontend/src/pages/Dashboard.tsx`、`frontend/src/pages/LoginPage.tsx`、`frontend/src/pages/AccountBindingPage.tsx`、`frontend/src/pages/SecurityPage.tsx`、`frontend/src/api/quantApi.ts`、`README.md`
- 影响：新增 `GET /api/quant/auto-trade/profit/summary`；`quant_okx_credential` 增加用户维度索引；`auto_trade_record` 新增 `user_name` 字段和索引；账号绑定、自动交易记录列表和收益汇总按当前登录用户隔离。收益概览当前为 `ESTIMATED_UNREALIZED_ONLY` 口径，已实现收益暂按 0，未实现收益来自 OKX 当前持仓。
- 验证：`mvn test` 通过，140 个后端测试 0 失败；`npm run build` 通过，Vite 仅提示 bundle 大小警告。

### 2026-06-18 - 数据库用户登录和账号安全页

- 变更摘要：登录从配置文件明文账号改为数据库用户，默认 `admin/admin123` 仅用于首次初始化，新增 PBKDF2-SHA256 密码哈希、创建用户、改密码和用户启停能力，并优化登录页。
- 影响文件：`src/main/java/com/example/quant/auth/*`、`src/main/java/com/example/quant/controller/AuthController.java`、`src/main/resources/db/migration/V8__auth_users.sql`、`frontend/src/pages/LoginPage.tsx`、`frontend/src/pages/SecurityPage.tsx`、`frontend/src/api/auth.ts`、`README.md`
- 影响：新增 `quant_auth_user` 表；`POST /api/quant/auth/login` 仍保持不变；新增 `/api/quant/auth/users`、`/api/quant/auth/password/change`、`/api/quant/auth/users/{username}/password`、`/api/quant/auth/users/{username}/enabled`；`/api/quant/auth/users/**` 需要有效登录 token。
- 验证：`mvn test` 通过，135 个后端测试 0 失败；`npm run build` 通过，前端产物已生成到 `frontend/dist`。
