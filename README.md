# Quant Assistant

Java 17 / Spring Boot 3 backend plus React Admin frontend for A股分析 and OKX合约实盘控制.

## Included

- A股分析：候选列表、扫描、报告接口，数据来自本地 AkShare 服务。
- OKX合约：候选列表、交易计划、待确认订单、人工确认、实盘提交。
- 登录保护：本地单用户登录后进入量化实盘控制台。
- 账号绑定：绑定 OKX API Key、Secret、Passphrase 到数据库，并提供接口验证按钮。
- 风控与账户：读取 OKX 账户余额、可用余额、持仓快照和风控状态。
- 数据库：默认读取 `src/main/resources/application.properties` 中的 MySQL 配置。

## Trading Mode

当前项目按实盘交易实现：

```yaml
trading:
  okx-live-enabled: true
  require-user-confirm: true
```

确认订单会调用 OKX 实盘下单接口。不要绑定带提现权限的 OKX API；请在 OKX 后台开启读取和交易权限，并确认 IP 白名单包含当前后端服务器出口 IP。

## Start

一键启动 AkShare、后端和前端：

```powershell
cd D:\code\View
.\start-dev.ps1
```

只检查命令：

```powershell
.\start-dev.ps1 -CheckOnly -SkipDocker -SkipInstall
```

启动后打开：

```text
http://<this-machine-ip>:5173
```

默认登录账号：

```text
admin / admin123
```

生产环境请设置：

```text
QUANT_AUTH_USERNAME=<your-user>
QUANT_AUTH_PASSWORD=<strong-password>
```

前端默认访问：

```text
http://<this-machine-ip>:8080/api/quant
```

如后端在其他地址，设置 `VITE_QUANT_API_URL` 覆盖默认值。

## Manual Startup

```powershell
cd D:\code\View\akshare-service
D:\code\View\.akshare-venv\Scripts\python.exe -m uvicorn server:app --host 0.0.0.0 --port 18080

cd D:\code\View
mvn "-Dmaven.repo.local=D:\code\View\.m2\repository" spring-boot:run

cd D:\code\View\frontend
npm run dev
```

## OKX API Fields

- `API Key`：OKX 后台创建 API 后显示的 Key。
- `Secret`：创建 API 时显示的 Secret，只显示一次。
- `Passphrase`：创建 API 时你自己填写的口令，不是登录密码。

绑定后点击“验证接口”。OKX API 信息会保存到数据库表 `quant_okx_credential`，后端重启后不需要重复输入；接口只返回脱敏后的 API Key，不回显 Secret 和 Passphrase。

如果返回 401，优先检查 Key/Secret/Passphrase 是否属于同一个 API、读取/交易权限是否开启、IP 白名单是否正确。

## Tests

```powershell
mvn "-Dmaven.repo.local=D:\code\View\.m2\repository" test

cd D:\code\View\frontend
npm run build
```

## API Examples

```http
GET  http://<backend-host>:8080/api/quant/stock/candidates
GET  http://<backend-host>:8080/api/quant/contract/candidates
POST http://<backend-host>:8080/api/quant/contract/trade-plan?instId=BTC-USDT-SWAP
POST http://<backend-host>:8080/api/quant/contract/pending-order?instId=BTC-USDT-SWAP
GET  http://<backend-host>:8080/api/quant/orders/pending
POST http://<backend-host>:8080/api/quant/orders/confirm?id=<pending-order-uuid>
GET  http://<backend-host>:8080/api/quant/account/binding-status
POST http://<backend-host>:8080/api/quant/account/bind
POST http://<backend-host>:8080/api/quant/account/verify
POST http://<backend-host>:8080/api/quant/account/unbind
```
