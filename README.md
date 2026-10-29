# SyncPlugins

SyncPlugins 是一个面向 Windows 10/11 的 GitHub 文件自动同步工具。它可以同时管理多个本地目录，按用户设置的间隔扫描文件变化，将每个任务中的新增、修改、重命名和删除按修改时间逐文件提交并推送到各自指定的 GitHub 仓库或分支，也支持从 GitHub 自动初始化或手动下载。

发布的 Windows 安装包内置精简 Java 21 运行时。终端用户不需要单独安装 Java、Git 或 OpenSSH。

当前版本：**1.2.4**

## 主要功能

### 工作区初始化

- 支持已有 Git 仓库。
- 支持已有普通文件夹：远端为空时自动执行 Git 初始化并上传。
- 支持空目录：远端有内容时自动克隆。
- 支持尚不存在的下载路径：自动创建目录并克隆。
- 不允许在另一个 Git 仓库内部创建嵌套仓库。

### 多任务监听

- 可同时添加多个同步任务。
- 每个任务独立配置本地目录、GitHub 仓库、分支、私钥、提交身份和检查间隔。
- 本地目录 A、B 可以对应不同仓库，也可以对应同一仓库的不同分支。
- 每个任务拥有独立文件监听器、远端状态、逐文件队列、日志和下载备份。
- 主窗口可在任务间切换，并分别执行同步、暂停、恢复、下载和备份操作。
- “全部同步”和“全部暂停”可一次操作所有任务。
- 不允许两个任务配置相同的本地目录。

### 本地与云端都有内容

初始化前展示：

- 本地独有文件。
- 云端独有文件。
- 同名但内容不同的文件。

用户可以选择：

- **云端为准**：备份本地后，以云端工作区为准。
- **本地为准**：保留云端 Git 历史，以本地文件覆盖同名文件，并删除云端独有文件。
- **独立保留**：保留双方独有文件；同名冲突将本地版本重命名为 `<文件名>.local-<时间戳>`。

### 分支管理

- 自动读取 GitHub 远端现有分支。
- 分支输入框可选择现有分支，也可直接输入新分支名。
- 已有 Git 仓库创建新分支时，以当前本地 HEAD 为起点。
- 普通目录、新克隆目录创建新分支时，以 GitHub 默认分支为起点。
- GitHub 为空仓库时，直接使用输入的分支名创建初始分支。
- 首次推送时自动在 GitHub 创建对应远端分支。

### 逐文件同步队列

- 每个任务同时只处理一个文件，按文件修改时间排序，同一时间按路径排序。
- 每个文件单独暂存、单独提交、立即推送。
- 提交消息包含操作类型和文件路径，例如：

```text
SyncPlugins auto-sync: add docs/guide.md
SyncPlugins auto-sync: modify image.png
SyncPlugins auto-sync: delete old.txt
```

- 同一文件在排队期间再次修改时，只提交最新内容，并保留第一次入队位置。
- 程序不常驻实时监听文件系统，只按任务设置的检查间隔扫描目录。
- 定时扫描发现文件稳定后进入处理阶段。
- 应用启动和正常退出时都会强制执行一次全量检查。
- 应用重启后根据 Git 工作区状态重建未完成队列。
- `.git` 目录不参与扫描；提交遵循仓库现有 `.gitignore`。
- Git 本身不跟踪空文件夹。

### 进度显示

- 主窗口显示确定进度条。
- 显示“已完成数 / 本批次总数”。
- 显示当前正在处理的文件路径。
- 云端下载和备份恢复时显示不确定进度动画。
- 最小化后，托盘悬浮提示和右键菜单顶部会显示当前进度。

### 云端下载

- 后台定期检查 GitHub 更新，但不会自动修改本地。
- 用户点击“从云端下载”后才更新工作区。
- 本地干净且可以快进时，直接更新到远端分支。
- 本地有未提交改动、额外提交或分叉时，创建备份后以云端覆盖。
- 自动保留最近 3 份下载前备份。
- 可以在“备份”窗口中恢复或删除备份。

### 输入记忆

- 最近使用的目录、SSH 地址、分支、私钥路径、姓名和邮箱会保存为可编辑历史。
- 每类历史最多保留 10 条。
- 未完成的配置草稿也会保存，即使取消设置或连接失败。
- 不同私钥的口令分别保存。
- 私钥口令通过 Windows DPAPI 加密，不写入普通配置或历史文件。
- 设置页面提供“清除历史与凭据”。

### 同步状态与日志

- 主窗口展示监听状态、仓库、分支、最后成功时间、队列、失败项、错误原因和操作记录。
- 顶部任务选择器显示所有任务及当前状态；进度条和详情区显示选中任务。
- 系统托盘使用 Swing 自定义菜单，使用中文字体，避免原生 AWT 菜单乱码。
- 托盘菜单提供打开主窗口、立即同步、暂停/恢复、云端下载、备份管理、打开仓库和退出。
- 单实例运行，防止同时启动多个同步进程。
- 支持登录 Windows 后自动启动。

### 错误处理

- 超过 GitHub 100 MB 限制或无法读取的单文件会标记失败并提示，然后继续处理其他文件。
- SSH 认证失败会暂停并提示。
- 网络中断会暂停队列并自动退避重试，本地文件保持不变。
- GitHub 分支被其他位置修改、删除或发生分叉时暂停同步，不自动合并、重置或强制推送。
- 修复外部问题后，可使用“处理后继续”重新检查。

## 首次使用

1. 将 OpenSSH 公钥添加到 GitHub 账户。
2. 安装并启动 SyncPlugins。首次启动会引导创建第一个同步任务。
3. 填写任务名称，选择已有仓库、普通目录，或填写一个尚不存在的下载位置。
4. 填写 GitHub SSH 地址，例如：

```text
git@github.com:owner/repository.git
```

5. 检测远端后，选择现有分支或直接输入新分支名。
6. 选择 OpenSSH 私钥并填写可选口令。
7. 填写 Git 提交姓名和邮箱。
8. 设置检查间隔，范围为 1–3600 秒，默认 30 秒。
9. 点击“检测并连接”。
10. 首次 SSH 连接时，将应用显示的主机密钥 SHA-256 指纹与 GitHub 官方指纹核验后确认。
11. 点击“保存并开始同步”。
12. 在“任务管理”中点击“新增任务”，重复上述步骤即可添加文件夹 B。

首次保存后，现有未提交文件会按队列逐文件提交并推送。程序完全按设置间隔扫描；应用启动和正常退出时会强制同步一次。发布包 JVM 使用 `-Xms32m -Xmx256m`、受限 Metaspace 和 Serial GC，以降低常驻内存。

## 操作入口

主窗口底部提供：

- 设置
- 打开仓库目录
- 从云端下载
- 备份
- 立即同步
- 暂停 / 恢复

关闭主窗口会缩到系统托盘。右键托盘图标可以同步所有任务、暂停所有任务、下载当前选中任务或打开任务管理。

## 数据位置

### Roaming

- 旧版单任务配置（迁移前）：`%APPDATA%\SyncPlugins\config.json`
- 多任务配置：`%APPDATA%\SyncPlugins\tasks\<任务ID>.json`
- 输入历史和草稿：`%APPDATA%\SyncPlugins\ui-state.json`
- SSH 主机密钥：`%APPDATA%\SyncPlugins\known_hosts`
- DPAPI 加密口令：`%APPDATA%\SyncPlugins\secret.bin`

### Local

- 运行状态：`%LOCALAPPDATA%\SyncPluginsData\state.json`
- 多任务状态：`%LOCALAPPDATA%\SyncPluginsData\states\<任务ID>.json`
- 日志：`%LOCALAPPDATA%\SyncPluginsData\syncplugins.log.0`
- 日志轮转：`syncplugins.log.1`、`syncplugins.log.2`
- 下载前备份：`%LOCALAPPDATA%\SyncPluginsData\backups\<任务ID>`
- 单实例锁：`%LOCALAPPDATA%\SyncPluginsData\instance.lock`

应用升级不会清理 `SyncPluginsData`。旧版单任务配置会迁移为 `tasks` 目录中的首个任务，旧状态和备份也会迁移到该任务。

私钥本体不会被复制或上传。请勿将私钥放在被监听的仓库目录中。

## 安全说明

- GitHub 连接仅通过应用内置 Apache Mina SSHD 完成。
- 支持 OpenSSH Ed25519、RSA、ECDSA 私钥。
- BouncyCastle 以独立签名 JAR 分发，不会被合并进 fat JAR。
- 私钥口令按私钥路径分别使用 Windows DPAPI 加密。
- 首次连接要求确认 GitHub 主机密钥指纹。
- 云端覆盖本地属于用户主动操作，但仍会先生成本地备份。
- 安装包未做代码签名，Windows SmartScreen 可能显示“未知发布者”。

## 本地文件与冲突备份

下载前备份由两部分组成：

1. 本地 Git 提交快照，保存为 `syncplugins-backup/<时间戳>` 本地分支。
2. 未跟踪文件复制到 `%LOCALAPPDATA%\SyncPluginsData\backups\<时间戳>\files`。

如果仓库状态无法安全创建 Git 快照，会回退为文件备份。

默认仅保留最近 3 份备份。恢复备份会覆盖当前工作区，因此界面会先要求确认。

## 开发环境

要求：

- JDK 21
- Maven 3.10+
- Windows 10/11
- `jpackage`

开发运行：

```powershell
.\scripts\run-dev.ps1
```

运行测试：

```powershell
mvn test
```

完整验证：

```powershell
mvn verify
```

测试使用临时裸仓库模拟 GitHub，不访问真实网络。覆盖：

- 已有仓库、普通目录、自动建仓和自动克隆。
- 多个独立任务按设置间隔扫描并分别推送到不同仓库。
- 本地与云端冲突的三种初始化处理方式。
- 新分支创建和首次远端推送。
- 多文件逐文件提交、修改时间排序、删除和重复修改合并。
- 超限文件跳过、后续文件继续。
- 手动下载、快进、备份、覆盖和恢复。
- 最近 3 份备份清理。
- 输入草稿、历史记录、配置 v1→v2 迁移和多私钥口令。
- 进度状态、自动监听和逐文件推送。

联网 SSH 手动测试可通过环境变量启用：

```powershell
$env:SYNCPLUGINS_RUN_SSH_TEST="true"
$env:SYNCPLUGINS_TEST_REPOSITORY="W:\path\to\repo"
$env:SYNCPLUGINS_TEST_REMOTE="git@github.com:owner/repo.git"
$env:SYNCPLUGINS_TEST_BRANCH="main"
$env:SYNCPLUGINS_TEST_KEY="W:\path\to\private-key"
$env:SYNCPLUGINS_TEST_KNOWN_HOSTS="$env:APPDATA\SyncPlugins\known_hosts"
mvn -q -Dtest=SshConnectionManualTest test
```

## 构建 Windows 发布物

### 便携版

```powershell
.\scripts\build-installer.ps1 -Portable
```

输出目录：

```text
dist\SyncPlugins
```

该目录包含内置 Java 运行时，可整体移动或压缩分发。

### EXE 安装包

```powershell
.\scripts\build-installer.ps1
```

输出：

```text
dist\SyncPlugins-1.2.4.exe
```

生成 EXE 需要构建机安装 WiX Toolset 3.14 或更高版本。也可以指定 WiX 工具目录：

```powershell
.\scripts\build-installer.ps1 -WixBin "C:\path\to\wix\tools"
```

终端用户不需要安装 Maven、WiX 或 Java。

## 当前限制

- 每个任务对应一个工作区、一个 GitHub 仓库和一个分支；一个应用实例可包含多个任务。
- 仅支持 `github.com` 的 SSH 远端，不支持 HTTPS/PAT 或 GitHub Enterprise。
- 不支持 Git LFS、子模块自动同步、自动冲突合并或强制推送。
- 不自动创建 GitHub 仓库，仓库必须先存在。
- 不进行自动更新。
- 安装包暂未进行代码签名。

## 故障排查

### SSH 认证失败

检查：

- 私钥路径是否正确。
- 私钥是否有口令。
- 对应公钥是否添加到 GitHub。
- 私钥文件权限是否仅允许当前用户读取。

### 主机密钥确认

首次连接必须确认 GitHub 主机密钥指纹。不要把未知来源的 `known_hosts` 文件直接放入配置目录。

### 远端冲突

如果 GitHub 分支被其他位置修改，应用会暂停。可以：

- 在外部 Git 工具中处理分支后点击“处理后继续”。
- 点击“从云端下载”，备份本地后使用云端版本。

### 文件始终没有上传

检查主窗口中的：

- 错误信息。
- 队列剩余数量。
- 失败项数量。
- 当前监听状态。

然后查看 `%LOCALAPPDATA%\SyncPluginsData\syncplugins.log.0`。

### 托盘菜单显示异常

1.1.4 起已使用 Swing 自定义菜单，不再使用 AWT 原生菜单。如果仍显示异常，请确认运行版本为 1.2.4，并提供 `syncplugins.log.0`。
