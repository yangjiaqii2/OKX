# OKX 一键 Docker 部署实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 生成可发布的应用镜像，并让任意 Docker 服务器通过 `.env` 与 `./deploy.sh` 启动应用和 MySQL。

**Architecture:** 多阶段 Dockerfile 构建 React 与 Spring Boot，Java 17 非 root 运行镜像统一托管前后端；Compose 管理应用、MySQL、内部网络、健康检查和持久卷，Flyway负责自动建表。

**Tech Stack:** Docker Engine、Docker Compose v2、Eclipse Temurin 17、Maven 3.9、Node.js 20、MySQL 8.4、Spring Boot Flyway。

## Global Constraints

- 默认 `TRADING_MODE=DISABLED`，部署脚本不得自动切换 LIVE。
- `.env` 和交易凭据不得进入 Git、镜像层或日志。
- MySQL 不暴露宿主机端口，数据使用命名卷持久化。
- 不新增与一键部署无关的基础设施或应用依赖。

---

### Task 1: 部署契约校验

**Files:**
- Create: `scripts/test-deployment.sh`

**Interfaces:**
- Consumes: `Dockerfile`、`docker-compose.yml`、`.env.example`、`scripts/deploy.sh`
- Produces: 可重复执行的部署静态验收命令 `bash scripts/test-deployment.sh`

- [ ] **Step 1: 写入部署校验脚本**，检查必需文件、非 root 运行、Compose 服务、健康依赖、MySQL 无宿主机端口和 Flyway 配置。
- [ ] **Step 2: 执行 `bash scripts/test-deployment.sh`**，预期因新部署契约尚未实现而失败。

### Task 2: 最小生产镜像与 Compose

**Files:**
- Modify: `Dockerfile`
- Modify: `.dockerignore`
- Modify: `docker-compose.yml`
- Create: `.env.example`

**Interfaces:**
- Consumes: `pom.xml`、`frontend/package-lock.json`、Spring 环境变量
- Produces: OCI 应用镜像及 `app + mysql` Compose 栈

- [x] **Step 1: 将 Dockerfile 改为前端、后端、JRE 三阶段构建**，运行容器使用固定非 root 用户。
- [x] **Step 2: 让 Compose 只暴露应用端口**，MySQL 使用健康检查和命名卷，应用等待 MySQL 健康。
- [x] **Step 3: 新增 `.env.example`**，包含镜像、端口、数据库、管理员、主密钥、交易模式、AI 和 OKX 可选配置。
- [x] **Step 4: 重跑部署校验脚本**，全部静态约束通过。

### Task 3: 构建与一键部署脚本

**Files:**
- Create: `scripts/build-image.sh`
- Create: `deploy.sh`

**Interfaces:**
- Consumes: `.env` 中 `OKX_APP_IMAGE`、`TRADING_MODE` 和 `LIVE_DEPLOY_CONFIRM`
- Produces: 本地镜像构建命令与服务器一键部署命令

- [x] **Step 1: 实现 `scripts/build-image.sh`**，从 `.env` 读取镜像名并调用 `docker build`。
- [x] **Step 2: 实现 `deploy.sh`**，校验配置、拒绝未二次确认的 LIVE、拉取镜像、启动并等待健康。
- [x] **Step 3: 运行 `bash -n scripts/build-image.sh deploy.sh scripts/test-deployment.sh`**，shell 语法通过。

### Task 4: 文档与完整验证

**Files:**
- Modify: `README.md`
- Modify: `docs/CODEBASE_DOC.md`
- Create: `docs/TRADING_RISK_REMEDIATION_PLAN_2026-08-11.md`

**Interfaces:**
- Consumes: 实际部署命令和前一轮代码复核证据
- Produces: 运维手册、变更记录和按优先级执行的交易整改方案

- [x] **Step 1: 更新 README**，记录构建、推送、首次部署、升级、日志、备份和回滚命令。
- [x] **Step 2: 更新 CODEBASE_DOC**，记录容器职责、配置、迁移、验证和 2026-08-11 变更条目。
- [x] **Step 3: 写入交易整改方案**，列出 Critical/High/Medium 根因、最小修复点、验收测试和实施顺序。
- [x] **Step 4: 执行 `mvn clean package -DskipTests=false`、`npm run build`、部署校验、Compose 配置检查、Docker 构建和 `git diff --check`**；镜像 push 未执行，因为本轮未提供目标镜像仓库与登录权限。
