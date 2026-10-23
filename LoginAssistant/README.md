# LoginAssistant — Fabric 客户端辅助模组

目标环境：Minecraft Java 1.21.10、Fabric Loader、Fabric API、Java 21。

> 这是源码项目，不是已验证的发布版。当前环境没有可用的 Gradle 构建环境/依赖仓库连接，因此尚未生成或验证可安装的 JAR。请在本机完成构建并测试。

## 功能

- `/assist`：打开 GUI。
- 按服务器地址分别保存已知密码、候选密码和自动登录设置。
- GUI 中可以添加候选、根据关键词生成本地候选、筛选/选择候选、将候选填入“已知密码”输入框。
- `/assist add <密码>`：把你手动提供的候选保存到本地。
- `/assist known <密码>`：设置当前服务器的已知密码。
- `/assist search <关键词>`：在本地候选中搜索。
- `/assist generate <关键词>`：在本地生成常见变体。生成结果不会自动向服务器提交。
- `/assist auto on|off`：开关已知密码自动登录；只有检测到登录提示时才会使用已保存的密码。
- `/assist rejoin on|off`：开关普通断线后的自动重连。
- `/assist connect`：封禁保护期结束后，由用户手动尝试连接上次服务器。
- `/assist status`：查看状态。
- 自动登录失败后不会自动循环尝试其他候选密码。

## 临时封禁保护

Mixin 会检查客户端收到的断开原因。若提示包含 `temporarily banned`、`temporary ban`、`too many failed login attempts`、`too many incorrect passwords` 或常见中文表达，模组会启用默认 10 分钟的保护计时：

- 清除待处理的自动登录提示。
- 取消待处理的自动重连。
- 保护期间 `/assist connect` 会拒绝发起连接。
- 保护时间结束后不会自动重新连接；请先确认服务器允许重新登录，再手动执行 `/assist connect`。

注意：这是按文本匹配的客户端保护机制，不是服务器权威封禁状态。不同服务器的提示可能不同；如果提示没有匹配到，保护机制就可能无法识别。服务器实际限制时长也可能不是 10 分钟。

## 构建

需要：

- JDK 21
- Gradle（建议使用兼容 Fabric Loom 的 Gradle 版本）
- 可访问 Fabric Maven、Gradle Plugin Portal、Maven Central 的网络

在项目根目录执行：

```bash
gradle build
```

构建成功后检查 `build/libs/` 中生成的 JAR。将适用的 JAR 放入 Minecraft 1.21.10 Fabric 实例的 `mods` 文件夹，并确保已安装兼容版本的 Fabric API。

建议用 IntelliJ IDEA 打开项目根目录并以 Gradle 项目导入，然后执行 Gradle `build` 任务。若编译失败，请保存完整错误日志；Minecraft/Fabric 映射的 API 签名可能需要根据实际依赖版本调整。

## 安装与使用

1. 先备份 Minecraft 实例和配置。
2. 将构建生成的 JAR 放入该实例的 `.minecraft/mods/`（启动器可能使用独立游戏目录）。
3. 启动 Minecraft 1.21.10 Fabric。
4. 进入你有权访问的服务器，输入 `/assist` 打开界面。
5. 先保存已知密码，再按需开启自动登录。
6. 如需普通断线重连，开启“普通断线重连”。临时封禁检测到后，模组会停止自动重连。

## 配置和隐私

配置文件位置：`<游戏目录>/config/loginassistant.json`。

按用户要求，密码以明文保存。任何能读取此文件的人都可能看到密码。不要分享此文件、截图或日志；不要在公共聊天中输入密码。建议只在自己管理或获准访问的服务器上使用。

候选生成和搜索均在本地完成，不会自动提交候选。不要将此工具用于规避服务器封禁或登录限制。
