# WorldLogger

> 一个 NeoForge 模组，可以将重要的 Minecraft 世界事件记录到 MySQL，允许管理员通过命令、游戏内 GUI 和 AI 数据库助手进行查看。

<p align="center">
<a href="README.md">简体中文</a> | <a href="./i18n/README.EN-US.md">English</a>
</p>

## 项目简介

WorldLogger 是一个面向 Minecraft NeoForge 服务端的日志模组。它把玩家登录、退出、死亡、丢物、经验变化、聊天、命令、容器交互、方块放置/破坏、爆炸、实体死亡和实体生成等事件记录到 MySQL 数据库中，并提供三种查看方式：

- 聊天栏命令：`/worldlogger select` 和 `/worldlogger search`
- 图形界面：`/worldlogger gui`
- AI 助手：`/worldlogger ai ...`

它的核心目标是把 Minecraft 世界里“发生了什么”变成可查询、可回放、可分析的数据。服务器像一个观察员，监听游戏世界的关键事件，把事件整理成数据库记录，再通过命令、GUI 和 AI 帮管理员理解这些记录。

## 快速演示

以下步骤可以帮助你快速了解 WorldLogger 的用法：

1. 启动 MySQL，并创建数据库（如 `world_logger`）。
2. 启动 NeoForge 专用服务器，配置好数据库连接（见下文“数据库配置”）。
3. 进入服务器，做一些会触发记录的事情，例如：
    - 登录
    - 发送聊天消息
    - 执行命令
    - 打开箱子并移动物品
    - 破坏或放置方块
    - 丢出物品
    - 让实体生成或死亡
4. 使用命令查看日志：
    - `/worldlogger select PLAYER_LOGIN_INFO 1` – 在聊天栏查看登录记录
    - `/worldlogger select PLAYER_CONTAINER_INFO 1` – 查看容器操作记录
    - `/worldlogger search` – 搜索当前位置附近 16 格内的记录
    - `/worldlogger gui` – 打开图形界面浏览所有表
5. 如果配置了 AI 助手（见下文“AI 配置”）：
    - `/worldlogger ai 总结一下我最近登录后做了什么`

## 主要功能

### 记录的事件类型

WorldLogger 会记录以下事件，并分别存入不同的数据库表（完整表名见后文）：

- 玩家登录、退出（含时间、坐标、IP）
- 玩家死亡（含伤害来源、死亡消息）
- 玩家丢失物品（普通丢物或死亡掉落）
- 玩家经验变化（含变化量和来源）
- 聊天消息（含原始文本和富文本组件）
- 命令执行（含来源：玩家/命令方块/控制台等）
- 容器交互（打开期间发生的物品取放/修改）
- 方块放置（由任何实体放置）
- 方块破坏（玩家破坏、非玩家实体破坏、爆炸破坏）
- 非玩家实体死亡与生成

### 查询方式

#### 1. 聊天栏命令（管理员）

- `/worldlogger select <表名> [页码]` – 查看某张表的记录，每页显示一条，点击可翻页。
- `/worldlogger search [页码]` – 搜索当前玩家附近 16 格内的所有事件记录，按时间倒序排列（普通玩家也可使用）。

#### 2. 图形界面（管理员）

- `/worldlogger gui` – 打开一个可视化的浏览界面：
    - 左侧列出所有可查询的表。
    - 顶部有搜索框，可按玩家 UUID、玩家名或坐标进行筛选。
    - 右侧显示选中记录的完整字段内容，长文本会自动换行和滚动。

#### 3. AI 助手（管理员）

- `/worldlogger ai <消息>` – 与 AI 助手对话。服务端 AI 可以调用只读数据库工具，帮你分析日志。
- `/worldlogger ai reset` – 清除当前玩家的 AI 对话上下文和待审批请求。
- `/worldlogger ai approve <ID>` – 当 AI 需要读取超过自动限制的数据量时，会生成审批 ID，管理员用此命令批准后继续。

AI 助手支持以下数据库工具（只读）：

- 列出所有可查询的表
- 查看某张表的字段结构和总行数
- 查询某张表的具体数据行
- 搜索当前玩家附近的记录
- 获取某玩家最近登录后的活动时间线

如果 AI 请求的数据量超过自动限制（默认 20 条），需要管理员手动批准后才能执行。

## 配置说明

WorldLogger 的配置文件由 NeoForge 自动生成，通常位于 `config/` 或世界文件夹下的 `serverconfig/` 目录。你需要设置以下内容：

### 数据库配置

| 配置项              | 默认值                        | 说明                            |
| ------------------- | ----------------------------- | ------------------------------- |
| `database_url`      | `jdbc:mysql://localhost:3306` | MySQL JDBC 地址（不含数据库名） |
| `database_name`     | `world_logger`                | 使用的数据库名称                |
| `database_username` | `root`                        | MySQL 用户名                    |
| `database_password` | 空字符串                      | MySQL 密码                      |
| `thread_number`     | `8`                           | 数据库读写线程数                |

> [!IMPORTANT]
> **准备数据库示例：**
>
> ```sql
> CREATE DATABASE world_logger CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
> CREATE USER 'worldlogger'@'localhost' IDENTIFIED BY 'your_password';
> GRANT ALL PRIVILEGES ON world_logger.* TO 'worldlogger'@'localhost';
> FLUSH PRIVILEGES;
> ```
>
> 然后在配置文件中填入对应的用户名和密码。

### AI 配置（可选）

AI 功能默认关闭。如需启用，请在配置中设置：

| 配置项                      | 默认值                      | 说明                                          |
| --------------------------- | --------------------------- | --------------------------------------------- |
| `enabled`                   | `false`                     | 是否启用 AI 功能                              |
| `api_base_url`              | `https://api.openai.com/v1` | OpenAI-compatible API 地址                    |
| `api_key`                   | (空)                        | API 密钥                                      |
| `model`                     | (空)                        | 模型名称（如 `gpt-4o`）                       |
| `max_auto_search_depth`     | `20`                        | AI 自动执行的最大数据行数，超出需要管理员审批 |
| `max_approved_search_depth` | `256`                       | 管理员批准后允许的最大数据行数                |
| `max_tool_iterations`       | `10`                        | AI 工具调用最大迭代次数                       |
| `max_output_tokens`         | `4096`                      | AI 回复的最大 token 数                        |
| `request_timeout_seconds`   | `60`                        | AI 请求超时时间（秒）                         |
| `debug_log_payloads`        | `false`                     | 是否在日志中打印 AI 请求/响应（调试用）       |

> 注意：AI 使用的 API 需兼容 OpenAI Responses API 格式。API Key 请妥善保管，默认不会记录在日志中。

## 命令参考

所有命令需在游戏聊天栏中输入：

| 命令                                | 权限     | 作用                                     |
| ----------------------------------- | -------- | ---------------------------------------- |
| `/worldlogger select <表名> [页码]` | 管理员   | 在聊天栏查看某张表的一条记录，可点击翻页 |
| `/worldlogger search [页码]`        | 普通玩家 | 搜索当前位置附近 16 格内的记录           |
| `/worldlogger gui`                  | 管理员   | 打开图形界面浏览所有表                   |
| `/worldlogger ai <消息>`            | 管理员   | 与 AI 助手对话（需启用 AI 配置）         |
| `/worldlogger ai approve <ID>`      | 管理员   | 批准 AI 深度查询请求                     |
| `/worldlogger ai reset`             | 管理员   | 清除当前玩家的 AI 上下文和待审批请求     |

## 数据库表一览

WorldLogger 会在启动时自动创建以下 15 张表（如果不存在）：

| 表名                    | 记录内容                                            |
| ----------------------- | --------------------------------------------------- |
| `PLAYER_BASE_INFO`      | 玩家基本信息（UUID、名字、累计游戏时间）            |
| `PLAYER_LOGIN_INFO`     | 每次登录的时间、IP、坐标、世界                      |
| `PLAYER_LOGOUT_INFO`    | 每次退出的时间、坐标、世界                          |
| `PLAYER_DEATH_INFO`     | 死亡类型、坐标、世界、伤害来源、武器、死亡消息      |
| `PLAYER_LOST_ITEM`      | 丢物或死亡掉落物的物品信息（可关联死亡 ID）         |
| `PLAYER_XP_INFO`        | 经验变化类型、来源、变化量、总经验、坐标            |
| `EXECUTE_COMMAND_INFO`  | 命令执行源、坐标、世界、原始命令                    |
| `SERVER_CHAT_INFO`      | 聊天玩家、坐标、世界、原始消息及富文本组件          |
| `PLAYER_CONTAINER_INFO` | 容器 ID、槽位、变化前后物品、变化类型（取/放/修改） |
| `ENTITY_PLACE_BLOCK`    | 放置方块的实体、坐标、世界、方块 ID 及 NBT          |
| `PLAYER_BREAK_INFO`     | 玩家破坏方块的坐标、世界、方块 ID 及 NBT            |
| `ENTITY_BREAK_INFO`     | 非玩家实体破坏方块的坐标、世界、方块 ID 及 NBT      |
| `EXPLOSION_BREAK_BLOCK` | 爆炸影响的方块 JSON 列表、爆炸来源、位置            |
| `ENTITY_DEATH_INFO`     | 非玩家实体的死亡信息、伤害来源                      |
| `ENTITY_SPAWN_INFO`     | 非玩家实体的生成信息                                |

所有表都包含时间戳字段，便于按时间顺序查询。

## 技术要求与兼容性

| 项目项         | 内容                       |
| -------------- | -------------------------- |
| Mod ID         | `worldlogger`              |
| 当前版本       | `1.1.3`                    |
| Minecraft 版本 | `1.21.1` (NeoForge 26.1.2) |
| NeoForge 版本  | `26.1.2.73`                |
| Java           | 需要 Java 25 或更高版本    |
| 数据库         | MySQL 5.7 或更高版本       |
| 构建工具       | Gradle                     |
| 许可证         | MIT                        |
| 作者           | `create_xiaoyu`            |

## 构建与安装（开发者/服主）

### 安装模组

1. 从发布页面下载 `worldlogger-<version>.jar`。
2. 放入 NeoForge 服务器的 `mods/` 目录。
3. 启动服务器，配置文件会自动生成，按需修改数据库和 AI 设置。
4. 重启服务器即可生效。

### 自行构建

```bash
./gradlew build
```

构建产物位于 `build/libs/` 目录。

### 开发环境运行

- 客户端：`./gradlew runClient`
- 专用服务器：`./gradlew runServer`

## 常见问题

**Q：客户端需要安装这个模组吗？**  
A：客户端可以不安装，但安装后可以获得图形界面和 AI 聊天功能。服务端必须安装。

**Q：数据量大了会不会卡服？**  
A：所有数据库读写都在独立的线程池中执行，不会阻塞游戏主线程。你可以根据服务器性能调整 `thread_number` 参数。

**Q：AI 助手会修改数据库吗？**  
A：不会。AI 工具全部为只读查询，不会执行任何写入或删除操作。

**Q：如何重置 AI 对话？**  
A：使用 `/worldlogger ai reset` 即可清空当前玩家的对话上下文。

**Q：附近搜索的范围可以调整吗？**  
A：目前固定为 16 格半径，暂不支持自定义，如有需求可后续扩展。
