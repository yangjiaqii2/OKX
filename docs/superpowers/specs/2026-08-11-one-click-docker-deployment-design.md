# OKX 一键 Docker 部署设计

## 目标

将后端、前端和 MySQL 收敛为可重复部署的 Docker Compose 应用。目标服务器只需要 Docker、Compose、部署文件和事先准备好的 `.env`；启动命令固定为 `./deploy.sh`，数据库由 Flyway 自动迁移。

## 部署结构

- `okx-app`：单个不可变应用镜像。多阶段构建分别执行 `npm ci && npm run build` 与 `mvn clean package`，运行阶段仅保留 Java 17 JRE、应用 JAR 和前端静态文件。
- `mysql`：使用官方 MySQL 8.4 镜像和命名卷；仅加入 Compose 内部网络，不开放宿主机 3306。
- Spring Boot：从 `/app/static` 提供 React 产物；通过容器环境连接 `mysql:3306`；Flyway 在应用接收流量前执行全部版本化迁移。
- Compose：MySQL 健康后才启动应用；两个服务均使用 `unless-stopped`；应用对外只暴露一个可配置 HTTP 端口。

## 镜像分发

开发机使用 `./scripts/build-image.sh` 构建 `${OKX_APP_IMAGE}`。镜像推送到任意 OCI Registry 后，服务器 `.env` 填写同一镜像地址，`./deploy.sh` 执行拉取、配置校验和启动。部署脚本使用 `--no-build`，避免服务器意外从源码构建出不同产物。

## 配置与安全边界

- `.env.example` 只包含变量名和安全占位符，不包含凭据。
- 必填项由 Compose 插值直接失败关闭：应用镜像、MySQL 密码、管理员密码、AES-256-GCM 主密钥和交易模式。
- `TRADING_MODE` 默认 `DISABLED`；`LIVE` 还必须设置 `LIVE_DEPLOY_CONFIRM=I_UNDERSTAND_LIVE_TRADING`，否则部署脚本拒绝启动。
- OKX、AI 凭据只通过 `.env` 注入，不写入镜像层；容器以非 root 用户运行。
- 数据库卷不会被普通 `docker compose down` 删除；只有显式 `down -v` 才会删除数据。

## 启动与故障行为

1. `deploy.sh` 校验 Docker Compose、`.env`、交易模式和 Compose 结构。
2. 拉取应用与 MySQL 镜像。
3. 创建内部网络和 MySQL 数据卷。
4. MySQL 健康后启动应用；Flyway 自动建表或升级。
5. 脚本等待应用容器健康；超时则输出容器状态和最近日志并返回失败。

应用或 MySQL 异常退出后由 Docker 自动重启。数据库不可用、迁移失败或必填密钥缺失时，应用保持失败状态，不绕过启动错误。

## 验证范围

- 静态校验：部署文件齐全、Compose 能展开、应用依赖 MySQL 健康检查、MySQL 不暴露宿主机端口、默认交易模式禁用。
- 构建校验：前端 TypeScript/Vite、Maven 全测试与打包、Docker 镜像构建。
- 运行校验：空数据库首次启动、Flyway 自动建表、应用健康、首页和 API 可访问、重启后数据卷保留。
- 安全校验：`.env` 被 Git 忽略、镜像中不包含 `.env`、部署输出不打印凭据。

## 不在本次范围

- 不自动推送镜像仓库，因为仓库地址和登录权限由部署方决定。
- 不自动开启 LIVE，不自动创建或绑定 OKX API 凭据。
- 不引入 Kubernetes、Redis、Nginx、Kafka 或外部迁移服务。
- 不在本次部署变更中顺带修改交易策略和风控实现；相关问题进入独立整改方案。
