# OKX 交易引擎跨实例互斥与发布交接设计

**当前腾讯云发布配置保持交易禁用。** 本设计供未来独立立项与模拟验收。本次没有实现租约、增加数据库表或接入交易账户。`.miren/app.toml` 固定一个 Web 实例，激活 `miren,evaluation`。交易模式为 `DISABLED`，自动交易关闭。Miren profile 同时关闭自动合约扫描及恢复，OKX REST 和私有 WS 指向本机关闭的端口。当前发布无需让两个 JVM 协调交易权。[部署配置](../.miren/app.toml) · [Miren profile](../src/main/resources/application-miren.yml) · [启动守卫](../src/main/java/com/example/quant/config/EvaluationTradingGuard.java#L19)

## 已验证的故障窗口

local 在切版时记录到新旧 JVM 至少同时存活 5.95 秒，另一次在同一采样点记录到两个沙箱均为 running。Miren v0.18.0 对当前未挂应用盘的 Web 服务先启动新池，随后回收旧池，因此单实例配置不足以约束旧进程中的定时任务或在途请求。5.95 秒是一次观测的下界，不宜用作租约超时或停机等待时间。[本地验收记录](miren.md#local-验收记录--2026-11-24) · [Miren 发布控制器](https://github.com/mirendev/runtime/blob/v0.18.0/controllers/deployment/launcher.go#L370-L394)

| 现有路径 | 已有保障及交接影响 |
|---|---|
| 启动、定时、手动扫描与自动确认 | 扫描使用 JVM 内 AtomicBoolean，自动执行另有进程内执行锁和币种在途表。手动确认同步在单个订单对象上。两个 JVM 可同时观察到可用预算和空闲仓位。[扫描](../src/main/java/com/example/quant/task/ContractMarketScanTask.java#L75) · [执行锁](../src/main/java/com/example/quant/agent/execution/AutoTradeService.java#L71) · [手动确认](../src/main/java/com/example/quant/order/OrderConfirmService.java#L178) |
| 决策与订单落库 | V17 对用户、策略、合约、周期和 K 线时间加唯一约束，另约束客户单号。策略服务捕获唯一键冲突，陈旧 PROCESSING 转 RECONCILING。V33 约束平仓意图，待处理订单另有乐观版本。不同业务意图仍可能同时动用同一账户资金。[V17](../src/main/resources/db/migration/V17__strategy_decision_idempotency.sql#L15) · [决策领取](../src/main/java/com/example/quant/strategydecision/StrategyDecisionService.java#L44) · [V33](../src/main/resources/db/migration/V33__close_intent_idempotency.sql) · [订单版本](../src/main/java/com/example/quant/order/PendingOrderEntity.java#L16) |
| 交易所写入 | 设置杠杆、下单、条件单、撤单、撤条件单、改单、平仓均经过 OkxRestOrderGateway。DISABLED 在所有写入前生效。降风险分支另校验账户、持仓、方向与数量，通过后可在普通启动就绪检查之前返回。跨实例写权必须同时覆盖增仓和降风险路径。设置杠杆和随后下单属于两次独立外部调用。[交易网关](../src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java#L224) · [降风险验证](../src/main/java/com/example/quant/okxtrade/ExposureReductionGuard.java#L25) · [提交顺序](../src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java#L114) |
| 恢复、私有事件和账本 | 启动及账户绑定事件可触发恢复，定时任务按 autoTradeOwnerUsername 恢复，私有 WS 可触发周期对账。V34 启动预检遇未决生命周期会阻断。成交与账单有唯一键。已有恢复会检查稳定客户单号、入场终态、持仓及成交账本，账本未闭合时可保留 CLOSED_RECONCILING 和预算。[启动预检](../src/main/java/com/example/quant/task/TradingStartupRecovery.java#L47) · [恢复任务](../src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java#L113) · [WS](../src/main/java/com/example/quant/crypto/OkxWebSocketService.java#L117) · [平仓恢复](../src/main/java/com/example/quant/account/ClosePositionRecoveryService.java#L153) · [成交账本](../src/main/resources/db/migration/V19__trade_fill_ledger.sql#L18) · [账户账本](../src/main/resources/db/migration/V21__account_bill_ledger.sql#L17) |

## 未来实现

**一个交易账户只允许一个能向交易所签名发送的执行者。** 账户键包含交易场所、模拟或实盘环境和经验证的账户或子账户稳定标识。API Key 换绑和用户名变化应映射回同一账户。两个用户名指向同一交易账户时也应争用同一行租约。账户归属无法确认时阻断写入。禁用环境始终无需绑定真实账户。若将来让不同集群服务同一交易账户，它们必须共用同一写权控制面与出口，或在平台层确保其中仅一边具有交易能力。

### 在 OKX 自己的 MySQL 库分配写权

1. 为每个账户长期保存一行租约，字段包含账户键、执行者进程随机 ID、版本、单调递增的 epoch、到期时间和发布状态。用数据库时间判断过期，进程重启时产生新的执行者 ID。版本号不能区分同一版本的两次启动。
2. 在短事务内按主键锁定租约行，取得锁后读取数据库当前时间，再判断可否领取或续租。首次建行走唯一键冲突重读。新任执行者接管时递增 epoch。续租须匹配当前执行者、epoch 和未过期条件。超时、连接中断、提交结果不明都进入停止分派状态，按新连接重读租约。交接只解除所属权，保留 epoch 历史。恢复旧数据库备份时隔离所有执行者并更换写权代际后才能重新协调。[MySQL 8.4 InnoDB 锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
3. 资金预留、持仓生命周期、风控状态、账本游标、订单恢复及写命令出队，在各自的短事务中校验并锁定同一账户租约，随后以期望业务版本更新。与接管并发的旧事务先完成或被拒绝，过期结果不得覆盖新状态。等待交易所网络响应期间释放数据库锁。同一操作的预留和状态变化事务性落库。
4. 部署中的新 Web 进程先承接页面和查询。定时触发、手工确认/平仓、系统控制、换绑事件和 WS 回调统一提交带账户和稳定业务标识的意图。消费方按账户串行判定是否执行。重复事件可入去重收件箱，交易状态和持仓归属的最终更新服从当前 epoch。沿用现有账户权限、策略、仓位和降风险守卫。

### 保护交易所发送边界

把签名与网络发送收敛到唯一执行出口。Web 进程不能旁路直连真实交易接口，进程权限、密钥解密权限和网络策略应能共同做到这一点。只把 SDK 调用包一层租约判断仍有竞态：旧 JVM 可在检查后暂停，新 JVM 获得租约，旧 JVM 继续发送。交易所不会根据本地 MySQL 的 epoch 拒绝这个请求。

出口接收操作时检查当前 epoch 和发布状态，按账户串行受理，拒绝旧代际。旧执行者退出或被隔离前，出口保持封闭，不向下一代授予发送能力。若签名出口自身需切版，须先确认旧发送者已终止或在独立网络控制处已被撤销出站能力，且旧的已受理请求已逐项对账。无法证明隔离时保持停止发送并告警。初次引入这一控制时，必须先隔离仍能直接签名的旧版。仅凭 TTL 到期、进程心跳消失、路由切到新版或某次查单返回空，都不足以接管。

| 阶段 | 持久化及恢复规则 |
|---|---|
| 待发送 | 在一次事务中固化操作 ID、账户、参数摘要、期望订单版本和既有客户单号。普通单、保护单、平仓单和改单各自保留操作标识。重试沿用原标识。发现相同标识但参数不同时阻断并审计。 |
| 已开始发送 | 发送前先提交在途状态、epoch 和请求序号。按每个外部动作分开登记结果，包括杠杆设置。若死在状态落库与实际发送之间，也保守地视作结果未知。 |
| 结果未知 | 按原标识查询当前单、历史单、成交记录及实际持仓。条件单核对对应 algo 单号与状态，撤改操作核对目标和最终参数，杠杆操作读取适用账户/合约状态。证据不足时保留预算、占用和保护关系，暂停冲突动作，进入人工对账。不可自动换一个客户单号再发。 |
| 已确认 | 根据交易所确认的事实做幂等状态转换。回执、WS 及账单可能迟到或乱序，去重后保留为证据。入场撤单 ACK 后须再读订单终态，处理撤单窗口内的部分成交，重新确认仓位。平仓账本完整后释放预算。[入场终态核验](../src/main/java/com/example/quant/okxtrade/EntryOrderReconciliation.java#L11) · [交易适配器对账](../src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java#L150) |

### 发布、故障与回滚

| 时点 | 允许的动作与进入下一步的条件 |
|---|---|
| 当前禁交易发布 | 只发布禁用配置、无交易凭据的版本，保持回环隔离。核对运行状态、私有 WS 和未知订单数。回滚目标也须满足相同条件。 |
| 将来模拟演练开始 | 在共享控制面将账户标为 DRAINING，记录当时的发布版本与 epoch。停止新开仓、新意图分派。由旧任唯一执行者处理已在途事项，仅在原有守卫允许下维持已确认的持仓保护。保留交易所有效保护单。取消保护属于单独受控操作。 |
| 旧版交出写权 | 等待已接纳命令得到可审计结果或进入持久化的待人工状态。核对持仓、挂单、保护和账本。按当前 V34 规则，在已有持仓、未决生命周期或无法证明保护有效时中止正常发布，维持既有安全处置，不自动平仓、不执行批量撤单。到达允许交接的边界后撤销旧出口能力并记录证据。 |
| 新版接管 | 新实例先以只读方式预热。证实旧执行者已隔离后领取新 epoch，重新拉取数据库及模拟交易所事实，检查未知订单、预算、保护、成交、WS 与既有就绪条件。全部通过且控制面允许才开启模拟发送。Web 健康检查单独验证。 |
| 崩溃或网络分区 | 自动续租失败即停发。旧进程恢复、响应超时、双主、账户身份冲突、DB 回退或交易所不可用时保留冻结。若有持仓保护告警，交由获得授权的唯一值守方处理，记录操作并重新对账。 |
| 回滚 | 先冻结，按相同流程撤销新版写权并核对在途操作。回滚镜像需兼容已迁移的 schema 和租约、意图协议。原版执行者重新加入时领取更高 epoch。回滚镜像前已完成的订单、Flyway 迁移、账本和租约历史都要保留。旧镜像若不能遵守写权协议，仅可在禁用交易、隔离凭据和出站的条件下用于页面或查询。 |

## 模拟验收门槛

未来验收使用两个独立 JVM、专用 MySQL 和能记录请求、延迟回执的交易所 Mock。记录真实的发送次数、执行者 ID、账户、业务 ID、epoch 与状态变化，日志屏蔽签名和凭据。以下是尚待实施的验证，已有的单元测试可复用其对账用例。[决策去重用例](../src/test/java/com/example/quant/strategydecision/StrategyDecisionServiceTest.java#L48) · [未知提交恢复用例](../src/test/java/com/example/quant/task/AutoTradeRecoveryTaskTest.java#L256) · [平仓与迟到成交用例](../src/test/java/com/example/quant/account/ClosePositionRecoveryServiceTest.java#L77)

| 故障注入 | 通过条件 |
|---|---|
| 两版本同时收到同一自动/手动确认，两个用户名绑定同一账户 | 只产生一个有效业务意图、一次资金预留。同一意图最多产生一次未经确认的外部发送。独立账户沿用各自权限。 |
| 旧进程在最后一次校验后暂停超过租期，再恢复执行 | 新任未获隔离证明时持续停发，旧请求在出口被拒。旧 epoch 更新数据库影响行数为 0。 |
| 交易所已受理，回执丢失，JVM 在发送前后或 ACK 落库前退出 | 新任按原 ID 对账，Mock 未收到第二个开仓/平仓请求。既有保护单、预算和未决记录仍存在。 |
| 部分成交、撤单 ACK 后迟到成交、保护改单回执丢失、WS 重放 | 再查终态和持仓，确认有效保护与剩余数量，成交和账单不重复。缺证保持待对账，不释放预算或撤销有效保护。 |
| DB 断连、滚动发布超时、持仓未平、旧版回滚、运行 DISABLED | 无法取得发送权或越过原有预检时，没有新增外部写。DISABLED 下模拟交易所收到的私有写请求为零，包含所有降风险入口。 |

验收目标是重叠发布期间写权唯一、结果未知可追溯、恢复不会盲目重发。即使上述演练通过，真实交易授权仍属于单独的业务决定。
