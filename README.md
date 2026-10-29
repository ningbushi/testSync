# SyncPlugins

SyncPlugins 是一个 Windows 桌面托盘工具。它监听本地目录，将新增、修改、重命名和删除的改动按修改时间逐文件提交并推送到 GitHub，也可以从 GitHub 克隆或手动下载云端更新。

终端用户不需要安装 Java、Git 或 SSH 客户端。Windows 安装包内置精简 Java 21 运行时，Git 操作和 SSH 连接由应用自身完成。应用保留独立签名的 BouncyCastle JAR，以支持 Ed25519 SSH 私钥。

## 功能

- 递归监听工作区，忽略 `.git`；提交时遵循仓库已有的 `.gitignore`。
- 使用单线程队列按修改时间逐文件提交、逐文件推送；同一文件排队期间的连续修改只提交最新内容。
- 主窗口显示同步进度条、已完成文件数、本批次文件总数和当前正在处理的文件。
- 支持已有 Git 仓库、普通非 Git 目录和全新下载目录；空远端时自动建仓，远端有内容时自动克隆。
- 分支输入框既可选择远端现有分支，也可直接输入新分支名；确认后会自动创建本地或远端分支。
- 本地和云端同时有内容时先预览差异，可选择云端为准、本地为准或独立保留。
- 支持 OpenSSH 私钥、Ed25519、RSA、ECDSA 和可选私钥口令。
- 私钥口令使用 Windows DPAPI 加密；明文不会写入配置文件。
- 首次 SSH 连接会展示 GitHub 主机密钥的 SHA-256 指纹，确认后保存到应用自己的 `known_hosts`。
- 后台只检查云端更新，不自动修改本地；点击“从云端下载”才会更新工作区。
- 本地下载干净时直接快进；有历史或未提交改动时先备份再覆盖。自动保留最近 3 份备份，可使用“备份”页面恢复。
- 离线时先在本地提交，恢复网络后自动重试推送。
- 开机自启、系统托盘、暂停/恢复、立即同步、操作日志和单实例保护。
- 在提交前阻止超过 GitHub 100 MB 单文件限制的文件。

## 首次使用

1. 安装并启动 SyncPlugins。
2. 选择已有 Git 仓库、普通资料目录，或填写一个尚不存在的下载目录。
3. 填写 GitHub SSH 地址，例如 `git@github.com:owner/repository.git`，并检测可用分支。
4. 选择 OpenSSH 私钥并填写可选口令。
5. 点击“测试连接”。首次连接时，将应用显示的 SHA-256 指纹与 GitHub 官方指纹核验后确认。
6. 填写 Git 提交姓名和邮箱，点击“保存并开始同步”。

保存后，现有未提交改动会按修改时间进入队列，每个文件分别提交和推送。之后关闭主窗口会缩到系统托盘。

## 数据位置

- 配置：`%APPDATA%\SyncPlugins\config.json`
- 运行状态：`%LOCALAPPDATA%\SyncPluginsData\state.json`
- 主机密钥：`%APPDATA%\SyncPlugins\known_hosts`
- DPAPI 加密口令：`%APPDATA%\SyncPlugins\secret.bin`
- 日志：`%LOCALAPPDATA%\SyncPluginsData\syncplugins.log`
- 下载前备份：`%LOCALAPPDATA%\SyncPluginsData\backups`

私钥本体不会被复制或同步。请勿把私钥放在被监听的仓库目录中。最近输入保存在 `%APPDATA%\SyncPlugins\ui-state.json`，不包含私钥口令；不同私钥的口令在 DPAPI 加密文件中分别保存。

## 自动同步行为

- 文件稳定 3 秒后进入队列，每完成一个文件即提交并推送，然后处理下一个。
- 按用户设置的检查间隔核对工作区和云端状态，补偿可能丢失的文件系统事件；默认 30 秒，可设置为 1–3600 秒。
- 推送前若 GitHub 分支被其他位置修改，应用暂停并保留本地状态。请在外部 Git 工具中完成合并或变基并处理好本地提交，再点击“处理后继续”。
- 网络、认证或远端冲突会暂停队列并退避重试；超过 GitHub 限制或无法读取的单个文件会标记失败，然后继续处理其他文件。
- 应用退出前会尝试执行一次最终同步；如果失败，改动仍保留在本地，下次启动继续核对。

## 开发与测试

环境要求：

- JDK 21，且存在 `jpackage`
- Maven 3.10+

运行：

```powershell
.\scripts\run-dev.ps1
```

运行测试：

```powershell
mvn test
```

测试使用临时裸仓库模拟 GitHub，不访问真实网络。覆盖自动建仓、自动克隆、三种初始化冲突处理、逐文件队列、删除、备份恢复、云端下载、输入记忆、远端分叉、100 MB 限制和 DPAPI 口令存储。

## 构建 Windows 发布物

免安装应用目录（内置 Java 运行时）：

```powershell
.\scripts\build-installer.ps1 -Portable
```

输出位于 `dist\SyncPlugins`，可整体移动或压缩分发。

EXE 安装包：

```powershell
.\scripts\build-installer.ps1
```

输出位于 `dist\SyncPlugins-1.1.4.exe`。构建机需安装 WiX Toolset 3.14 或更高版本；也可用 `-WixBin "C:\path\to\wix\tools"` 指定包含 `candle.exe` 和 `light.exe` 的目录。终端用户不需要安装 WiX 或 Java。

第一版安装包未做代码签名，Windows SmartScreen 可能显示未知发布者提示。

## 当前边界

- 一个工作区对应一个 GitHub 仓库和一个当前分支。
- 仅支持 `github.com` 的 SSH 远端。
- 不支持 Git LFS、自动冲突合并、强制推送、多任务、GitHub Enterprise 或自动更新。
