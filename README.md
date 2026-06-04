# WorldLogger

WorldLogger 是一个基于 **Minecraft NeoForge 26.1.2** 的服务端日志模组，用于记录玩家、实体、方块、容器、经验、聊天、命令等世界事件，并把数据写入 MySQL 数据库。

这个项目的重点不是“把事件打印到控制台”，而是把 Minecraft 运行时事件整理成可以长期查询、分页展示、GUI 浏览的数据。项目中也包含了一些常见服务端模组开发问题的解决方案，例如：

- 数据库读写不能阻塞 Minecraft 主线程。
- 客户端命令如何向服务器请求数据库查询。
- 服务器异步查询完成后如何把结果安全返回给玩家。
- 聊天栏输出过多时如何分页。
- 客户端语言本地化为什么不能放在服务端提前 `getString()`。
- 容器物品“取出又放回”为什么不能只比较关闭时的最终状态。
- 专用服务器为什么不能直接加载客户端 GUI 类。

## 运行环境

| 项目 | 版本 |
| --- | --- |
| Minecraft / NeoForge 模板版本 | `26.1.2` |
| NeoForge | `26.1.2.71` |
| Java | `25` |
| 数据库 | MySQL |
| MySQL 驱动 | `com.mysql:mysql-connector-j:9.6.0` |
| 数据库连接池 | `HikariCP:6.2.1` |

项目使用 Gradle Wrapper，不需要手动安装 Gradle。

## 功能概览

WorldLogger 当前记录的数据包括：

- 玩家基础信息。
- 玩家登录、退出。
- 玩家死亡、死亡掉落物、死亡经验掉落。
- 玩家普通经验变化。
- 玩家主动丢弃物品。
- 玩家聊天消息。
- 命令执行来源与命令内容。
- 玩家打开容器后产生的槽位变化。
- 玩家破坏方块。
- 实体放置方块。
- 实体破坏方块。
- 爆炸破坏方块。
- 非玩家实体死亡。
- 非玩家实体生成。

查询方式包括：

- `/worldlogger select <table> [page]`
  - 查看某张表的数据。
  - 聊天栏分页显示。
  - 一页显示一条完整记录。
  - 长 JSON/NBT 字段会压缩显示，避免刷屏。

- `/worldlogger search [page]`
  - 以执行命令的玩家为中心，搜索半径 16 格内的所有相关记录。
  - 默认一页显示 5 条。
  - 按最新时间排序。
  - 排除聊天和命令表，重点展示世界行为。

- `/worldlogger gui`
  - 打开客户端 GUI。
  - 左侧选择数据表。
  - 上方搜索框可按玩家 UUID、玩家名、坐标过滤。
  - 一页显示一条记录。
  - GUI 中可以查看更完整的原始字段，例如 JSON、NBT、长文本。

- `/worldlogger ai <message>`
  - 和 OpenAI GPT 系列模型对话。
  - 多人服务器使用服务器 AI 配置，并允许 AI 在需要时调用只读数据库工具分析数据。
  - 单人游戏使用客户端 AI 配置，只支持日常聊天，不访问数据库。
  - 请求会携带玩家客户端语言代码，AI 会按执行命令玩家的客户端语言回答。
  - 如果 AI 请求的数据库搜索深度超过服务器配置的自动限制，聊天栏会显示可点击的审批提示。

## 快速开始

### 1. 准备 MySQL

先创建数据库，例如：

```sql
CREATE DATABASE world_logger CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

模组启动时会自动创建所需数据表，因此通常不需要手动执行建表 SQL。

### 2. 配置数据库连接

第一次运行后，NeoForge 会生成 common 配置文件。需要配置以下内容：

```toml
database_url = "jdbc:mysql://localhost:3306"
database_name = "world_logger"
database_username = "root"
database_password = ""
thread_number = 8
```

字段说明：

- `database_url`
  - MySQL JDBC 地址，不包含数据库名。

- `database_name`
  - 要连接的数据库名。

- `database_username`
  - MySQL 用户名。

- `database_password`
  - MySQL 密码。

- `thread_number`
  - 数据库异步线程池大小。
  - 数值越大，并发能力越强，但数据库压力也越大。

### 3. 构建项目

```powershell
.\gradlew.bat build
```

只检查 Java 编译：

```powershell
.\gradlew.bat compileJava
```

## 项目结构

```text
src/main/java/com/xiaoyu/worldlogger
├─ WorldLogger.java                  # 模组入口，注册网络、事件、配置和服务端生命周期
├─ Config.java                       # 数据库连接与线程池配置
├─ command/
│  └─ MainCommand.java               # /worldlogger 命令入口
├─ mysql/
│  ├─ InitMySQL.java                 # HikariCP 连接池
│  ├─ MySQLExecutorService.java      # MySQL 异步线程池
│  ├─ DataBase.java                  # 数据表初始化
│  └─ WriteTable.java                # 通用写表方法
├─ network/
│  ├─ WorldLoggerNetwork.java        # 网络包注册与处理
│  └─ payload/                       # 请求包与响应包
├─ query/
│  ├─ TableQueryService.java         # 聊天栏 select 查询
│  ├─ WorldSearchQueryService.java   # 附近范围搜索
│  └─ GuiTableQueryService.java      # GUI 原始数据查询
├─ writetable/                       # 各类事件监听与写表逻辑
├─ data/                             # 玩家、死亡、方块等数据快照
├─ utils/                            # 字符串、物品、哈希、容器比较工具
├─ event/                            # 额外事件上下文缓存
└─ client/
   ├─ WorldLoggerClientHooks.java    # 客户端桥接类
   └─ screen/WorldLoggerGuiScreen.java
```

## 数据库表

当前会创建以下表：

| 表名 | 作用 |
| --- | --- |
| `PLAYER_BASE_INFO` | 玩家基础信息，每个 UUID 一行 |
| `PLAYER_LOGIN_INFO` | 玩家登录记录 |
| `PLAYER_LOGOUT_INFO` | 玩家退出记录 |
| `PLAYER_DEATH_INFO` | 玩家死亡记录 |
| `PLAYER_LOST_ITEM` | 玩家丢弃物品或死亡掉落物记录 |
| `PLAYER_XP_INFO` | 玩家经验变化记录 |
| `EXECUTE_COMMAND_INFO` | 命令执行记录 |
| `SERVER_CHAT_INFO` | 聊天记录 |
| `PLAYER_CONTAINER_INFO` | 容器槽位变化记录 |
| `ENTITY_PLACE_BLOCK` | 实体放置方块记录 |
| `PLAYER_BREAK_INFO` | 玩家破坏方块记录 |
| `ENTITY_BREAK_INFO` | 实体破坏方块记录 |
| `EXPLOSION_BREAK_BLOCK` | 爆炸破坏方块记录 |
| `ENTITY_DEATH_INFO` | 非玩家实体死亡记录 |
| `ENTITY_SPAWN_INFO` | 非玩家实体生成记录 |

## 整体架构

WorldLogger 的整体数据流可以理解为：

```text
Minecraft 事件
    ↓
writetable 事件监听器
    ↓
提取普通数据快照
    ↓
MySQLExecutorService 异步线程池
    ↓
HikariCP 获取数据库连接
    ↓
PreparedStatement 写入 MySQL
```

查询数据时的数据流是另一条线：

```text
玩家输入 /worldlogger select/search/gui
    ↓
客户端命令发送请求包
    ↓
服务器校验权限和参数
    ↓
MySQLExecutorService 异步查询
    ↓
服务器线程发送响应包
    ↓
客户端聊天栏或 GUI 显示结果
```

这样设计的核心原因是：**Minecraft 主线程不能被数据库操作阻塞**。

## 重点问题与解决方案

### 1. 数据库查询为什么不能在主线程执行

最开始遇到的问题是：如果在命令执行时直接查询 MySQL，数据量少时看起来没问题，但一旦表数据多、MySQL 响应慢、网络抖动，服务器主线程就会被卡住。

Minecraft 服务端主线程负责：

- 游戏 tick。
- 实体 AI。
- 方块更新。
- 玩家交互。
- 命令处理。
- 网络包中的一部分逻辑。

如果在这个线程里执行慢 SQL，表现就是服务器卡顿，甚至超时。

解决方式是使用 `MySQLExecutorService`：

```java
CompletableFuture.supplyAsync(() -> queryTable(table, page), executor)
        .whenComplete((queryPage, throwable) -> server.executeIfPossible(() -> {
            // 回到服务器线程后再给玩家发包
        }));
```

这里分成两步：

1. `supplyAsync`
   - 在线程池中执行数据库查询。
   - 不阻塞游戏主线程。

2. `server.executeIfPossible`
   - 查询完成后回到服务器线程。
   - 再读取在线玩家、发送响应包。

为什么不能异步线程查完后直接操作玩家对象？

因为 Minecraft 大部分对象不是线程安全的。异步线程里直接操作玩家、世界、实体，可能出现随机错误。因此异步线程只做数据库工作，Minecraft 对象相关操作回到服务器线程。

### 2. “异步线程还没执行完，方法就 return 了”怎么办

命令方法必须很快返回，这是正常的。异步逻辑不是靠方法返回值把数据带回来，而是靠“回调”和“网络响应”。

也就是说：

```text
命令执行
    ↓
发送请求
    ↓
命令方法返回
    ↓
服务器异步查询
    ↓
服务器发响应包
    ↓
客户端收到响应后显示
```

所以 `/worldlogger select` 的命令返回值只表示“请求是否成功发送”，不表示“查询结果已经拿到”。

这也是为什么项目中有这些 payload：

- `SelectTableRequestPayload`
- `SelectTableResponsePayload`
- `SearchRequestPayload`
- `SearchResponsePayload`
- `GuiTableRequestPayload`
- `GuiTableResponsePayload`

请求和响应被拆开后，异步查询就不再依赖命令方法本身的返回值。

### 3. 为什么客户端命令还要服务器重新检查权限

虽然命令注册时写了：

```java
.requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
```

但这只是客户端命令侧的限制，不能作为最终安全依据。客户端永远不应该被完全信任。

所以服务器收到网络包后，还会重新检查：

```java
if (!serverPlayer.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
    sendToPlayer(serverPlayer, SelectTableResponsePayload.error(...));
    return;
}
```

这可以防止恶意客户端绕过命令提示，直接构造网络包请求数据库。

### 4. 表名为什么必须使用白名单

SQL 里值可以使用 `PreparedStatement` 的 `?` 参数绑定，但表名和列名不能这样绑定。

错误示例：

```java
String sql = "SELECT * FROM " + playerInput;
```

如果 `playerInput` 是恶意字符串，就可能造成 SQL 注入。

项目中使用 `ListData` 保存允许查询的表名：

```java
String normalizedTable = ListData.findTable(table);
if (normalizedTable == null) {
    throw new SQLException("Unknown table: " + table);
}
```

只有白名单里的表名才会进入 SQL 拼接。

搜索值则继续使用参数绑定：

```java
statement.setString(parameterIndex++, "%" + filter + "%");
```

### 5. 为什么返回结果曾经总是英文

问题原因是：如果服务端提前调用：

```java
Component.translatable("xxx").getString()
```

那么文本会按服务端当前语言解析。专用服务端通常没有玩家客户端的语言环境，因此很容易得到 `en_us`。

解决方式是：**服务端不要提前把本地化文本转成字符串**。

现在的做法是：

- 服务端发送列名、语言 key 或参数。
- 客户端收到响应后再创建 `Component.translatable(...)`。
- 最终显示时使用玩家客户端语言文件。

例如 select 查询中，服务端返回的是：

```text
columnName = player_uuid
value = xxxxx
```

客户端显示时再做：

```java
Component.translatableWithFallback("text.worldlogger.name." + columnName, columnName)
```

这样客户端是中文就显示中文，客户端是英文就显示英文。

### 6. 聊天栏为什么需要分页

数据库字段里有很多可能非常长的内容：

- `block_nbt`
- `lost_item`
- `source_item`
- `modify_item`
- `command`
- `component_message`
- 爆炸破坏方块列表 JSON

如果一次性发到聊天栏，会出现两个问题：

1. 玩家聊天栏被大量文本刷屏。
2. 网络包和聊天组件过大，可能影响体验。

所以现在有两套查询策略：

#### 聊天栏 select

`TableQueryService`：

- 一页只显示一条记录。
- 使用 `LIMIT 2 OFFSET ?`。
- 第一条用于显示，第二条用于判断是否存在下一页。
- 长字段截断为较短文本。
- 容器物品 JSON 只提取物品 ID。

#### GUI 查询

`GuiTableQueryService`：

- 一页只显示一条记录。
- 不主动压缩字段。
- 长文本通过 `GuiTableResponsePayload` 分块传输。
- 更适合查看完整 JSON/NBT。

### 7. `/worldlogger search` 为什么要再做一次分页

附近搜索会从多张表聚合数据。如果玩家附近发生过大量事件，例如多人搬箱子、爆炸、刷怪、频繁放方块，那么一次搜索可能返回很多条。

最初直接输出所有结果时，聊天栏会被撑爆。

现在的解决方式：

- 搜索半径固定为 16 格。
- 每页显示 5 条。
- 所有结果按时间倒序，最新的排在最前面。
- 最多保留前 128 条，防止极端情况下查询结果过多。
- 有下一页时显示可点击的“点击以显示下一页”。

### 8. 附近搜索为什么要解析坐标字符串

当前数据库中坐标保存为字符串：

```text
[X: 100, Y: 64, Z: -30]
```

这对人类阅读很友好，但对 SQL 范围查询不友好。

所以 `WorldSearchQueryService` 目前的做法是：

1. 先通过 `world LIKE ?` 粗略筛选维度。
2. 从坐标字符串中用正则解析 X/Y/Z。
3. 在 Java 中计算平方距离：

```java
dx * dx + dy * dy + dz * dz <= radius * radius
```

使用平方距离是为了避免开平方计算。

这个方案兼容当前数据库结构，但不是最高效的。以后如果要优化，可以把坐标拆成数字列：

```text
x INT
y INT
z INT
```

然后直接在 SQL 中做范围筛选。

### 9. `death_id` 的作用

一次死亡会产生多类记录：

- `PLAYER_DEATH_INFO`
- `PLAYER_LOST_ITEM`
- `PLAYER_XP_INFO`

这些记录不在同一张表里，但它们属于同一次死亡。

因此项目使用 `death_id` 关联它们：

```text
death_id = sha1(player_uuid + player_name + death_timestamp)
```

死亡事件先保存当前时间戳到缓存中。之后掉落物事件和经验掉落事件根据同一个时间戳生成相同的 `death_id`。

玩家退出时会清理死亡时间缓存，避免临时数据长期保留。

### 10. GUI 为什么需要 requestId

GUI 查询是异步的。假设玩家快速操作：

1. 选择 `PLAYER_LOGIN_INFO`，发送请求 1。
2. 马上选择 `PLAYER_DEATH_INFO`，发送请求 2。
3. 请求 2 先返回。
4. 请求 1 后返回。

如果不处理，请求 1 的旧数据会覆盖请求 2 的新界面。

解决方式是 `requestId`：

- 每次 GUI 发请求时 `activeRequestId++`。
- 请求包带上当前 `requestId`。
- 服务器响应时原样带回。
- 客户端只接受等于当前 `activeRequestId` 的响应。

旧响应会被直接丢弃。

### 11. GUI 为什么要使用客户端桥接类

`WorldLoggerNetwork` 是 common 代码，专用服务器也会加载。

如果它直接 import：

```java
net.minecraft.client.Minecraft
net.minecraft.client.gui.screens.Screen
```

专用服务器可能因为没有客户端类而崩溃。

因此项目使用：

```java
WorldLoggerClientHooks
```

并在 common 网络类中通过反射调用：

```java
Class<?> hooks = Class.forName("com.xiaoyu.worldlogger.client.WorldLoggerClientHooks");
```

这样客户端 GUI 逻辑留在 client 包中，专用服务器不会在类加载阶段直接解析客户端类。

### 12. 为什么写数据库前要先提取快照

Minecraft 事件对象、玩家对象、世界对象、ItemStack、BlockState 都属于游戏运行时对象。

异步线程里长期引用这些对象有风险：

- 对象可能在下一 tick 改变。
- 玩家可能已经下线。
- 世界状态可能已经变化。
- 很多 Minecraft 对象不是线程安全的。

所以事件处理器通常先做：

```java
PlayerSessionData data = new PlayerSessionData(player, level);
BlockInfoData blockData = new BlockInfoData(blockState, blockPos, level);
```

然后异步线程只使用这些普通字符串、数字和 JSON。

这也是项目中 `data/` 包存在的原因。

## 命令说明

### `/worldlogger select <table> [page]`

查询指定表的一页数据。

示例：

```text
/worldlogger select PLAYER_LOGIN_INFO
/worldlogger select PLAYER_LOGIN_INFO 2
```

特点：

- 表名支持大小写不敏感匹配。
- 表名来自 `ListData` 白名单。
- 一页显示一条记录。
- 有下一页时会显示可点击文本。

### `/worldlogger search [page]`

搜索玩家当前位置半径 16 格内的记录。

示例：

```text
/worldlogger search
/worldlogger search 3
```

特点：

- 一页默认 5 条。
- 按最新时间排序。
- 会聚合多张表。
- 不包含 `SERVER_CHAT_INFO` 和 `EXECUTE_COMMAND_INFO`。

### `/worldlogger gui`

打开数据库查看 GUI。

特点：

- 左侧是表列表。
- 搜索框支持玩家 UUID、玩家名、坐标。
- 一页显示一条记录。
- 支持查看较长原始字段。
- 支持滚轮和滚动条拖动。

### `/worldlogger ai <message>`

和 AI 对话。

示例：

```text
/worldlogger ai 你好，帮我解释一下这个模组的日志表结构
/worldlogger ai 总结一下最近的玩家死亡和容器变化
/worldlogger ai 帮我分析 PLAYER_CONTAINER_INFO 里有没有异常的大量取出记录
```

多人服务器中，命令会发送到服务器：

- 使用服务器配置文件中的 API 地址、API Key 和模型。
- AI 可以调用只读数据库工具。
- 请求会附带玩家客户端语言代码，例如 `zh_cn` 或 `en_us`，服务端会把它写进 AI 提示词，避免数据库内容或旧上下文把回答语言带偏。
- 当玩家询问“某个玩家最近一次进入游戏后做了什么”时，提示词会要求 AI 优先调用专用的玩家活动时间线工具，而不是只列出表名。
- 服务器会重新检查管理员权限。

单人游戏中，命令会直接在客户端调用 AI：

- 使用客户端配置文件。
- 不调用数据库工具。
- 不会查询、总结或分析任何数据库记录，也不会把 `/worldlogger gui`、`/worldlogger select`、`/worldlogger search` 当成客户端 AI 的数据库查询替代方案。
- 只能日常聊天和回答一般问题；需要 AI 分析数据库时，要在多人服务器/服务端 AI 环境中执行。

### `/worldlogger ai approve <id>`

批准 AI 的超深度数据库查询。

当 AI 想读取的数据库深度超过 `max_auto_search_depth` 时，服务器不会立刻执行工具，而是返回提示：

```text
AI 想以深度 80 查询数据库，超过了自动限制 3。
批准后最多按配置上限 20 条执行。[点击以同意本次搜索]
```

玩家可以直接点击聊天栏中的审批文本，也可以手动执行：

```text
/worldlogger ai approve ABCD1234
```

审批只对当前这一次工具调用有效，并且会在一段时间后过期。即使 AI 请求了比 `max_approved_search_depth` 更大的深度，实际工具读取数量也会被裁剪到配置上限。

### `/worldlogger ai reset`

重置当前玩家的 AI 对话上下文。

WorldLogger AI 会保存玩家上一轮 OpenAI `response_id`，用于让模型延续上下文。如果想重新开始对话，可以执行：

```text
/worldlogger ai reset
```

## AI 功能说明

WorldLogger 的 AI 功能使用 OpenAI Responses API。项目没有引入 OpenAI SDK，而是使用 Java 25 自带的 `HttpClient` 直接发 HTTP 请求。

这样做的原因：

- 依赖更少。
- 更容易支持 OpenAI 兼容 API 地址。
- 用户可以通过配置切换模型、API 地址和密钥。

### AI 配置

AI 配置分为两套。

#### 服务器配置

服务器配置用于多人服务器：

```toml
enabled = false
api_base_url = "https://api.openai.com/v1"
api_key = ""
model = "gpt-5.5"
max_auto_search_depth = 3
max_approved_search_depth = 20
max_tool_iterations = 6
max_output_tokens = 1200
request_timeout_seconds = 60
debug_log_payloads = false
```

字段说明：

- `enabled`
  - 是否启用服务器 AI。

- `api_base_url`
  - OpenAI 或 OpenAI 兼容 API 地址。
  - 默认是 `https://api.openai.com/v1`。

- `api_key`
  - API 密钥。
  - 不要提交到公开仓库。

- `model`
  - 使用的 GPT 系列模型名。
  - 可以按需要改成其他支持 Responses API 的模型。

- `max_auto_search_depth`
  - AI 不需要玩家确认时，最多可以读取多少条数据库数据。

- `max_approved_search_depth`
  - 玩家确认后，AI 最多可以读取多少条数据库数据。
  - 这是绝对上限，用来防止一次请求读取过多数据。

- `max_tool_iterations`
  - 一轮 AI 请求中最多允许多少次工具调用循环。

- `max_output_tokens`
  - AI 回复最大 token 数。

- `request_timeout_seconds`
  - OpenAI HTTP 请求超时时间。

- `debug_log_payloads`
  - 是否把发送给 OpenAI 的请求体和 OpenAI 返回的响应体写入 DEBUG 日志。
  - 默认关闭，因为请求体和响应体可能包含玩家聊天、提示词、工具输出和数据库查询结果。
  - 这个日志不会打印 `Authorization` 请求头，因此不会主动输出 API Key。
  - 需要日志级别允许 DEBUG 输出时才会显示，通常会进入 Minecraft/服务器的 `latest.log`。

#### 客户端配置

客户端配置用于单人游戏：

```toml
enabled = false
api_base_url = "https://api.openai.com/v1"
api_key = ""
model = "gpt-5.5"
max_output_tokens = 1000
request_timeout_seconds = 60
debug_log_payloads = false
```

客户端 AI 不包含数据库工具。原因是客户端没有 MySQL 连接池，也不应该直接读取服务器数据库。因此客户端 AI 的提示词会明确禁止它声称自己查询过数据库，也不会建议玩家通过 GUI 或查询命令绕过这个限制；数据库总结、玩家行为分析、命令历史分析等功能都必须交给服务器 AI。

客户端配置里的 `debug_log_payloads` 只记录客户端 AI 的请求体和响应体，仍然可能包含玩家输入，所以也建议只在排查问题时临时打开。它同样需要日志级别允许 DEBUG 输出。

### AI 工具

服务器 AI 当前提供以下只读工具：

| 工具名 | 作用 |
| --- | --- |
| `worldlogger_list_tables` | 列出允许查询的 WorldLogger 表 |
| `worldlogger_describe_table` | 查看某张表的字段和总行数 |
| `worldlogger_query_table` | 查询某张表的若干行 |
| `worldlogger_search_near_player` | 搜索执行命令玩家附近的记录 |
| `worldlogger_player_activity_after_latest_login` | 查找指定玩家最近一次登录后的跨表活动时间线 |

工具安全限制：

- 所有工具只读，不会写数据库。
- 表名必须来自 `ListData` 白名单。
- 搜索值使用 `PreparedStatement` 参数绑定。
- 字段值会被截断，避免把完整 NBT 或大 JSON 全量发给模型。
- 物品 JSON 会尽量压缩成物品 ID，减少把 `custom_data`、NBT 等大字段发送给模型。
- 搜索深度超过自动限制时，需要玩家点击确认；确认后仍不能超过 `max_approved_search_depth`。

### AI 富文本回复

AI 最终回复会优先使用 WorldLogger 约定的富文本 JSON，再由 `AiComponentFormatter` 转成 Minecraft `Component`：

```json
{"worldlogger_component":[{"text":"结论","color":"gold","bold":true},{"text":"\n没有发现明显异常。","color":"green"}]}
```

支持的片段字段包括：

- `text`、`color`、`bold`、`italic`、`underlined`、`strikethrough`、`obfuscated`
- `hover_text`、`insertion`、`font`、`shadow_color`、`no_shadow`，其中 `hover_text` 可以是普通文本，也可以是富文本片段对象或数组
- `click`，支持 `open_url`、`suggest_command`、`copy_to_clipboard`、`change_page`
- `run_command` 只允许 `/worldlogger select`、`/worldlogger search`、`/worldlogger gui` 这类查看命令

如果 AI 没有按 JSON 格式返回，代码会退回普通文本显示，并把常见 Markdown 标记如 `**加粗**`、反引号代码片段转成基础 Component 样式。

### AI 工具调用流程

完整流程如下：

```text
玩家执行 /worldlogger ai ...
    ↓
客户端把请求发给服务器
    ↓
服务器调用 OpenAI Responses API
    ↓
模型判断是否需要数据库工具
    ↓
如果不需要工具：直接回复
    ↓
如果需要工具：服务器检查搜索深度
    ↓
深度未超过限制：执行只读数据库工具
    ↓
深度超过限制：暂停并显示可点击审批提示
    ↓
玩家点击审批后：按 max_approved_search_depth 上限继续执行工具
    ↓
工具结果发回 OpenAI
    ↓
模型根据工具结果总结并返回
    ↓
服务器把最终答案发给玩家聊天栏
```

### 为什么要审批搜索深度

AI 分析数据库时可能会请求比较大的读取深度，例如：

```text
读取 PLAYER_CONTAINER_INFO 最新 100 条
```

这会带来三个问题：

1. 数据库压力变大。
2. 发送给模型的数据量变多。
3. 可能包含更多玩家行为隐私。

因此服务器有两个限制：

- `max_auto_search_depth`
  - AI 可以自动执行的深度。

- `max_approved_search_depth`
  - 玩家确认后仍然不能超过的最大深度。
  - 如果 AI 请求 80 条，而这里配置为 20 条，实际执行时只会读取 20 条。

这样 AI 可以自己判断需要多少数据，但超过自动范围时，最终决定权仍然在执行命令的人手里。聊天栏的审批文本可以点击执行，也保留 `/worldlogger ai approve <id>` 作为手动方式。

## 开发注意事项

### 数据库操作规则

- 不要在 Minecraft 主线程直接执行慢 SQL。
- 写入和查询都应该走 `MySQLExecutorService`。
- 获取连接使用 `InitMySQL.getMySQLConnection()`。
- 使用 try-with-resources 自动关闭 `Connection` 和 `PreparedStatement`。
- SQL 值使用 `PreparedStatement` 的 `?` 参数绑定。
- 表名必须来自 `ListData` 白名单。

### 网络包规则

- 客户端只发送请求，不直接访问数据库。
- 服务器收到请求后必须重新检查权限。
- 查询结果通过响应包返回。
- 复杂或长文本要限制长度，必要时分块。

### 本地化规则

- 服务端不要提前调用 `Component.translatable(...).getString()` 生成最终文本。
- 尽量把语言 key、列名、参数发给客户端。
- 客户端再创建 `Component.translatable(...)`。

### GUI 规则

- common 代码不要直接 import 客户端类。
- GUI 异步响应要使用 requestId 防止旧响应覆盖新响应。
- 长文本渲染时要使用 scissor 裁剪，避免画出面板。
- 输入框搜索要做延迟请求，避免每输入一个字符都查数据库。

## 目前的局限与后续优化方向

### 1. 坐标字段仍然是字符串

现在坐标保存为：

```text
[X: 1, Y: 64, Z: 2]
```

优点是人类易读，缺点是不适合 SQL 范围查询。

后续可以新增数字字段：

```text
x INT
y INT
z INT
```

这样 `/worldlogger search` 可以直接通过 SQL 过滤范围。

### 2. 表结构迁移还不完整

`DataBase.InitDataBaseTable` 使用 `CREATE TABLE IF NOT EXISTS`，适合第一次建表。

但如果以后给旧表新增字段，已有数据库不会自动补字段。

后续可以加入：

```sql
ALTER TABLE ...
```

或者写一个简单的 schema version 迁移系统。

### 3. 容器记录仍然依赖菜单槽位结构

当前使用：

```java
container.slots.size() - 36
```

推断容器自身槽位数量。对大多数普通容器可用，但某些特殊模组 GUI 可能不完全符合这个结构。

后续可以针对常见容器类型做更精确的槽位识别。

### 4. 搜索结果仍然是聚合后内存分页

附近搜索当前会查询多张表、解析坐标、合并列表、排序、分页。

数据量较大时，可以继续优化：

- 给 `time`、`world`、坐标列加索引。
- 坐标拆成数字列后直接 SQL 范围筛选。
- 每张表先限制查询时间范围。
- 允许命令参数指定半径、时间范围、表类型。

## 常见问题

### MySQL 没连上怎么办

检查：

- MySQL 服务是否启动。
- `database_url` 是否正确。
- `database_name` 是否已经创建。
- 用户名和密码是否正确。
- MySQL 用户是否有建表、插入、查询权限。

### 命令没有权限怎么办

`/worldlogger` 查询命令需要管理员权限：

```java
Permissions.COMMANDS_ADMIN
```

请确认玩家是 OP，或者权限系统给了对应权限。

### GUI 搜索没有结果怎么办

GUI 搜索当前主要匹配：

- `player_uuid`
- `player_name`
- `pos`
- 以 `_pos` 结尾的坐标列

不会搜索所有 TEXT 字段，这是为了避免对大字段做 LIKE 导致数据库压力过大。

### 聊天栏 select 看不到完整 JSON 怎么办

这是预期行为。

聊天栏查询会压缩长字段，完整数据请使用：

```text
/worldlogger gui
```

## 构建验证

当前项目可通过：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
```

如果只是修改 README 或语言文件，一般不需要重新编译；如果修改 Java 源码，建议至少运行 `compileJava`。
