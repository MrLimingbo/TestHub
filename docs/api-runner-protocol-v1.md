# API Runner Redis Streams 协议 v1

本协议用于 TestHub 控制面与独立 API Runner 的内部通信。Redis 必须部署在私有网络中，并使用独立凭据；浏览器和终端用户不能直接访问这些 Stream。

## Stream 与消费者组

| 用途 | Key |
| --- | --- |
| 任务 | `testhub:runner:api:tasks:v1` |
| 取消指令 | `testhub:runner:api:cancellations:v1` |
| Runner 事件 | `testhub:runner:api:events:v1` |
| Runner 安全日志 | `testhub:runner:api:logs:v1` |
| 脱敏 HTTP 请求结果（v2） | `testhub:runner:api:http-results:v2` |
| 脱敏提取/断言结果 | `testhub:runner:api:step-results:v1` |
| 脱敏 pm.test 统计 | `testhub:runner:api:script-results:v1` |
| 无效任务死信 | `testhub:runner:api:dead-letter:v1` |
| Runner 成员集合 | `testhub:runner:api:members:v1` |
| 单 Runner 心跳 | `testhub:runner:api:heartbeat:v1:{runnerId}` |

任务消费者组固定为 `testhub-api-runner-v1`，取消指令消费者组固定为 `testhub-api-runner-cancellations-v1`。消费者组从创建之后的新消息开始消费；任务与取消指令各使用独立阻塞连接，防止预检等待阻塞取消确认。控制面以 `testhub-platform-api-runner-logs-v1`、`testhub-platform-api-runner-http-results-v2`、`testhub-platform-api-runner-step-results-v1` 和 `testhub-platform-api-runner-script-results-v1` 独立消费者组读取安全日志、HTTP 结果、步骤结果和脚本统计。

## 任务消息

任务消息的 `contract` 必须为 `testhub.api-runner.task`，`protocolVersion` 必须为 `1`。必须且只能有以下字段：

| 字段 | 说明 |
| --- | --- |
| `taskId`、`executionId` | 控制面创建的 ULID |
| `organizationId`、`projectId`、`scenarioId` | 强制作用域及场景身份 |
| `scenarioVersion`、`attempt` | 正整数版本及尝试次数 |
| `requestedAt`、`deadlineAt` | ISO-8601 时间；截止时间晚于请求时间 |
| `manifestRef` | 仅允许 `api-automation/manifests/{scenarioId}/v{scenarioVersion}.json` |
| `traceId` | 请求追踪标识 |

任务消息不允许携带环境密钥、认证令牌、Cookie、请求体、脚本文本或可执行代码。Runner 不会从任务消息读取清单；默认配置下它仅保留短暂的安全预检窗口，随后发送 `REJECTED / EXECUTION_DISABLED`。只有满足本协议的可信 Runner 和显式双端开关才可领取下文的受限 HTTP 工件。

## 执行安全清单

控制面每次创建执行任务时，都会在同一 MySQL 事务中向 `api_automation_execution_manifest` 固化一份不可变的安全清单。该清单只包含场景版本、环境标识、失败策略、步骤 ID/类型/显示名称/配置项名称，以及变量名称与来源（`SCENARIO`、`PROJECT_PARAMETER`、`EXECUTION_PROFILE`、`EXECUTION_OVERRIDE`、`DATA_SET`、`ENVIRONMENT_SECRET`、`RUNNER_LOCAL`）。自清单版本 3 起，`REQUEST` 步骤还固定其 HTTP 方法；自版本 5 起，`PARALLEL` 步骤还固定分支数量和允许的方法集合，用于校验脱敏结果与原始步骤一致。它不包含步骤配置值、请求地址、请求体、脚本、变量值或环境密钥值。

清单使用 SHA-256 校验和，并通过受项目读取权限保护的 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/manifest` 提供审阅。当前 `manifestRef` 仍只是场景版本的协议化引用，Runner 不读取该安全清单；真实执行接入时将另行设计短时、受限的运行时工件传递机制，且不得通过 Redis Stream 或执行记录传递密钥明文。

自清单版本 2 起，控制面会在提交前扫描步骤字符串配置中的 `{{变量名}}` 模板引用。每个引用必须能在当前环境的密钥、场景变量、项目参数、运行配置集或本次执行覆盖中解析；唯一例外是已在前序 `EXTRACT` 步骤定义的 `RUNNER_LOCAL` 变量。未定义、前序未提取或覆盖静态变量的引用会在写入执行记录和 Outbox 前被拒绝。静态来源优先级从高到低固定为：环境密钥、本次执行覆盖、数据集行、运行配置集、场景变量、项目参数。清单仅记录最终来源和 `stepId.configKey` 使用位置，绝不记录配置内容或变量取值。

在提交前，具有项目执行权限的用户可调用 `POST /api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/execution-configuration-preview` 传入同样的可选运行配置集与一次性覆盖变量。该只读预览复用任务创建时的解析规则，返回最终环境、配置集 ID/版本、变量名称、最终来源及使用位置；它不创建执行、Outbox、审计事件或运行时快照，也不返回变量或密钥取值。

## 冻结非敏感运行时变量

控制面在同一事务内还会向 `api_automation_execution_runtime_snapshot` 写入运行时变量快照。快照只包含当前场景实际引用、且最终来源为场景变量或项目参数的非敏感值；后续修改场景或项目参数不会影响已经创建的执行。`RUNNER_LOCAL` 仅在 Runner 内存中产生，因此绝不写入该快照。最终来源为环境密钥时，只保存密钥名称到 `withheldSecretNames`，不读取数据库密文、不解密、更不会写入密钥值。

快照同样使用 SHA-256 校验和。受项目读取权限保护的 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/runtime-snapshot` 只返回校验和、计数、环境、非敏感变量名称及来源，以及已保留的环境密钥名称；即使是非敏感变量值也不会从该接口返回。该内部快照不是 Runner 配置领取接口，不会通过 Redis Stream、租约接口或浏览器传递给 Runner；`RUNTIME_CONFIGURATION_DISABLED` 保持不变。

## 取消与时限

取消指令的 `contract` 为 `testhub.api-runner.cancel`，`protocolVersion` 为 `1`，且必须且只能有 `commandId`、`taskId`、`executionId`、`requestedAt`、`reasonCode` 字段。`reasonCode` 仅允许 `USER_REQUESTED` 或 `EXECUTION_TIMEOUT`；不接受浏览器传入的任意文本。

控制面会先在 MySQL 中更新任务状态，再以同一事务 Outbox 写入取消指令：未投递的任务直接标记为 `CANCELLED`；已投递或投递中的任务进入 `CANCELLING`，直到 Runner 以 `CANCELLED` 事件确认。Runner 保存任务取消标记 90 分钟，并且在预检窗口结束前发现标记时只确认任务、不产生执行或拒绝事件。

控制面每秒检查截止时间。到期的活动任务收敛为 `TIMED_OUT`，未投递任务会被撤销，已投递任务会追加固定的超时取消指令。事件晚到时不得覆盖终态。

## 安全 Runner 日志

Runner 日志的 `contract` 为 `testhub.api-runner.log`。日志必须且只能包含 `taskId`、`executionId`、`runnerId`、`level`、`code`、`message`、`occurredAt` 和协议字段。控制面不接受自由文本，`code`、`level` 与 `message` 必须匹配以下固定组合：

| 代码 | 级别 | 固定消息 |
| --- | --- | --- |
| `TASK_RECEIVED` | `INFO` | `Task accepted for safety precheck.` |
| `PRECHECK_WAITING` | `INFO` | `Waiting for the safety precheck window to close.` |
| `CANCELLATION_ACCEPTED` | `INFO` | `Cancellation command accepted without executing user content.` |
| `TASK_CANCELLED` | `INFO` | `Task acknowledgement stopped because cancellation is active.` |
| `EXECUTION_DISABLED` | `WARN` | `Execution engine remains disabled; user content was not run.` |
| `PARALLEL_REQUESTS_STARTED` | `INFO` | `A bounded parallel request group started without shared scripts or variables.` |
| `PARALLEL_REQUESTS_COMPLETED` | `INFO` | `A bounded parallel request group completed without exposing request content.` |
| `HTTP_RETRY_SCHEDULED` | `INFO` | `A bounded retry was scheduled for a transient safe-method HTTP outcome.` |
| `FAILURE_CONTINUED` | `WARN` | `The configured continue strategy allowed later bounded steps to run after a failure.` |
| `PRE_REQUEST_SCRIPT_COMPLETED` | `INFO` | `The isolated pre-request script completed without exposing variables.` |
| `POST_RESPONSE_SCRIPT_COMPLETED` | `INFO` | `The isolated post-response script completed without exposing variables or response content.` |
| `PRE_REQUEST_SCRIPT_FAILED` | `WARN` | `The isolated pre-request script failed; no script output was persisted.` |
| `POST_RESPONSE_SCRIPT_FAILED` | `WARN` | `The isolated post-response script failed; no script output was persisted.` |

控制面将通过字段校验的少量安全日志存入 MySQL，并以 Redis Stream 记录 ID 去重。请求 URL、头、Cookie、密钥、请求体、脚本和任意 Runner 自由文本均不得进入该日志流。未来真实执行产生的大体积日志、附件和报告仍写入 MinIO。

## Runner 心跳与事件

Runner 每 10 秒向对应心跳 key 写入 JSON，TTL 默认为 30 秒，同时把 `runnerId` 加入成员集合。心跳记录包含 `runnerType=api`、协议版本、Runner 版本、开始和最近心跳时间，以及 `executionEnabled=false`。

Runner 可选使用运行环境变量 `RUNNER_HEARTBEAT_KEY` 对心跳进行 HMAC-SHA-256 签名；控制面使用仅存在于自身运行环境的 `TESTHUB_RUNNER_HEARTBEAT_KEY` 验证签名。两个变量必须使用相同的至少 32 字符密钥，且不得写入仓库、数据库、Redis Stream、日志或任务记录。签名负载仅为 `runnerId`、Runner 类型、协议版本、Runner 版本、执行开关及两个时间字段。控制面把状态标记为 `VERIFIED`、`UNVERIFIED` 或 `NOT_CONFIGURED`；只有 `VERIFIED` Runner 才能在后续版本申请敏感运行时配置，未验证 Runner 仍只能执行当前的安全预检。

## Runner 内部能力发现

可信 Runner 可在配置运行环境变量 `TESTHUB_CONTROL_PLANE_URL` 后调用内部 `GET /internal/api-runner/v1/capabilities`。该地址只能是控制面的 HTTP(S) 根地址，且必须同时配置 `RUNNER_HEARTBEAT_KEY`；浏览器、CLI 与用户会话不能调用此接口。

请求包含 `X-TestHub-Runner-Id`、`X-TestHub-Runner-Timestamp`、`X-TestHub-Runner-Nonce` 和 `X-TestHub-Runner-Signature`。签名为 HMAC-SHA-256，固定负载依次是协议目的、`GET`、路径、Runner 标识、时间戳和 nonce，并以换行连接。控制面必须同时确认：密钥已配置、Runner 的当前心跳为 `VERIFIED`、时间戳位于 60 秒窗口内、签名恒定时间匹配，且 Redis 中的 nonce 在两分钟内未被使用。

默认接口只返回 `SAFETY_PRECHECK` 与 `SIGNED_CAPABILITY_DISCOVERY`。在控制面与可信 Runner 都显式开启受限 HTTP 执行后，才会附加 `HTTP_REQUEST_EXECUTION`、`FLOW_CONTROL`、`PARALLEL_REQUEST_GROUP`、`ISOLATED_SCRIPT_EXECUTION`，并声明工件类型 `HTTP_FLOW_CONTROL_SCRIPTS_V2`。旧 Runner 不识别该能力时会保持安全预检，不会领取或执行新工件。无论开关状态，环境密钥交接始终保持 withheld；能力发现不会返回场景、任务、变量、请求内容、脚本、认证信息或任何密钥值。

## 单任务运行时租约

可信 Runner 可调用内部 `POST /internal/api-runner/v1/runtime-leases`，请求体仅允许 `taskId` 与 `executionId` 两个 ULID。HMAC 固定负载在能力发现的字段之后追加这两个标识，因此不能把已签名请求替换到另一任务。控制面只在任务 Outbox 已投递、执行状态仍为 `QUEUED`、`ACCEPTED` 或 `RUNNING`，且任务尚未超过截止时间时签发租约。

租约为 32 字节随机值的 Base64URL 表示，只在响应中返回一次；Redis 只存储 SHA-256 哈希、Runner 标识、任务标识、执行标识与截止时间，并以较短的任务截止时间或 5 分钟为 TTL 上限。每个执行只能领取一次；重复领取返回冲突，签名不匹配、过期、未投递或终态任务返回拒绝。

未启用受限 HTTP 执行时，租约 `purpose` 固定为 `RUNTIME_CONFIGURATION_DISABLED`，Runner 也不会主动领取它。仅在双端开关、签名校验和网络白名单均满足时，目的才是 `HTTP_REQUEST_EXECUTION`，且只能领取下文所述的最小 HTTP 工件；不存在密钥或用户脚本领取接口。

## 受限 HTTP 请求执行

默认不启用 HTTP 执行。只有控制面设置 `TESTHUB_API_RUNNER_HTTP_EXECUTION_ENABLED=true`，并且 Runner 同时配置 `RUNNER_HTTP_EXECUTION_ENABLED=true`、`TESTHUB_CONTROL_PLANE_URL`、`RUNNER_HEARTBEAT_KEY` 和非空 `RUNNER_ALLOWED_ORIGINS` 时，可信 Runner 才会将心跳标识为 `executionEnabled=true`，能力发现返回 `HTTP_FLOW_CONTROL_SCRIPTS_V2`，短时租约的 `purpose` 才会变为 `HTTP_REQUEST_EXECUTION`。

启用后，Runner 可用同一租约调用 `POST /internal/api-runner/v1/runtime-configuration`。请求体和签名绑定规则与租约校验相同：只包含任务标识、执行标识、租约，签名只携带租约 SHA-256 指纹。控制面从提交版本对应的不可变场景修订和运行时变量快照即时构建工件；工件不写入 MySQL、Redis Stream、日志或浏览器。

工件同时携带不可变场景修订中的 `failureStrategy`。缺失字段按兼容规则视为 `STOP`，只接受 `STOP` 与 `CONTINUE`：`STOP` 在首个状态码断言或任一隔离脚本失败后停止；`CONTINUE` 仅允许状态码断言和后置脚本失败后继续后续受限步骤，Runner 会写入固定的 `FAILURE_CONTINUED` 日志，执行最终仍收敛为 `FAILED`。前置脚本失败和没有可用 HTTP 状态的传输失败始终停止，避免在未准备变量或无可审阅状态的情况下继续。

该工件只支持 `REQUEST` 步骤，且配置只能是 `method`、`url`、`body`、`contentType`、`maxAttempts`。方法仅允许 `GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`HEAD`、`OPTIONS`，正文最长 64 KiB。`maxAttempts` 默认为 1，只有 `GET`、`HEAD`、`OPTIONS` 可设为 2 或 3；仅网络失败、408、429 和 5xx 会触发重试，重试间隔固定 250ms，并在取消或截止时间不足时停止。环境密钥引用、脚本、Cookie、请求头、重定向、数据库操作及其他步骤类型会被拒绝。Runner 只访问与 `RUNNER_ALLOWED_ORIGINS` 精确匹配的 HTTP(S) Origin，并拒绝本地、私有、链路本地和保留地址的 DNS 结果；校验通过的解析地址会固定用于该次连接，避免校验后重新解析。

自工件版本 2 起，Runner 还接受严格顺序的 `EXTRACT` 和 `ASSERT` 步骤：两者只能位于至少一个请求之后，提取来源固定为 `LAST_STATUS_CODE`，提取变量仅保存在本任务 Runner 内存；断言只支持 100–599 的整数状态码与最近一次 HTTP 状态码精确匹配。后续请求可引用已提取的变量，但变量不得出现在 URL 的 scheme、host 或 port 中，Runner 会在替换后重新校验 Origin 和网络边界。任意响应正文、响应头、Cookie、异常内容和提取变量值均不会回传或持久化。

自工件版本 3 起，Runner 还支持固定流程控制：`WAIT` 只能携带 0–600000 毫秒延迟，且绝不晚于任务截止时间；`CONDITION` 只能用最近一次 HTTP 状态码按 `EQUALS` 或 `NOT_EQUALS` 判断 100–599 的常量，并只在不满足时跳过紧随的一个请求；`LOOP` 只能紧随请求并以 1–10 的总次数重复该请求。控制面和 Runner 都会验证步骤相邻关系与循环展开后的 300 次请求上限。它们不支持表达式、动态代码、嵌套循环、并发或响应正文解析；Runner 只写入固定的控制日志，不写入条件取值、循环数据或请求内容。

## 隔离 JavaScript 脚本（工件版本 4）

自工件版本 4 起，场景可以保存最长 8 KiB 的前置和后置 JavaScript。前置脚本在每个 HTTP 请求前运行，后置脚本在每个 HTTP 响应后运行。它们均只在受限 API Runner 容器内同步执行，控制面只验证、保存和冻结场景修订，绝不解释或运行用户代码。

脚本以短时、签名绑定的 `runtime-configuration` 响应交付给可信 Runner，仍不会进入 Redis Stream、Outbox、执行清单、运行时快照、浏览器接口、日志或报告。工件同时携带本次执行冻结的非敏感变量；后置脚本可读 `response.status`，前后脚本可读写 `variables`。响应正文、响应头、Cookie、请求头、环境密钥和异常详情均不可用。脚本对变量的变更只存在于单个 Runner 任务内存中，并受请求模板的 Origin 白名单和 SSRF 校验约束。

Runner 使用没有宿主对象或回调的 Node `vm` 上下文，以 JSON 作为唯一跨边界格式，并禁用字符串/wasm 代码生成、模块、网络、文件、进程、计时器和异步 API；同步 CPU 时间上限为 150ms。Runner 容器以非 root 用户、只读根文件系统、无 Linux capability、`no-new-privileges`、64 个 PID 与 256 MiB 内存限制运行。容器级隔离是安全边界，VM 是额外的输入和资源防护层。脚本失败只产生固定的 `PRE_REQUEST_SCRIPT_FAILED` 或 `POST_RESPONSE_SCRIPT_FAILED` 日志，绝不回传脚本文本、变量值或异常详情。

能力发现将工件类型声明为 `HTTP_FLOW_CONTROL_SCRIPTS_V2`，并增加 `ISOLATED_SCRIPT_EXECUTION` 与 `PARALLEL_REQUEST_GROUP`。旧 Runner 不识别该类型时会保持安全预检，不会领取或执行 v5 工件。控制面仍拒绝环境密钥交接。

## 受限并行请求组（工件版本 5）

自工件版本 5 起，`PARALLEL` 步骤可包含 2–5 条固定 HTTP 分支。每条分支沿用单请求的 Origin、SSRF、方法、正文、内容类型和安全重试限制；Runner 为每条分支分配独立的逻辑请求序号，但所有脱敏 HTTP 结果都使用该并行步骤的 `stepId`。Runner 先等待所有分支结算，再写入固定的开始/结束日志，因此不会因一个失败分支而丢失其他已开始分支的脱敏结果。

并行组没有共享可变状态：场景只要包含并行组，就禁止前后置脚本；组内只能引用已冻结的静态变量，不能引用 `EXTRACT` 产生的 `RUNNER_LOCAL` 变量。`EXTRACT`、`ASSERT`、`CONDITION` 和 `LOOP` 不能直接作用于并行组；需要回到单请求上下文时，必须在组后添加一个普通 `REQUEST`。控制面和 Runner 都执行相同的 2–5 分支、顺序、变量和脚本边界校验。

## 失败策略与并行组结构化报告（报告版本 5）

`GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/report` 的版本 5 在既有脱敏汇总之外保留 `failureStrategy`。它只包含冻结的 `strategy`（`STOP` 或 `CONTINUE`）与 `continuedFailureCount`：后者是执行中已经持久化的固定 `FAILURE_CONTINUED` Runner 日志数量。该计数帮助 CLI/CI 判断“失败后继续”是否实际发生，但不导出日志消息、断言名称、异常、脚本文本、URL、请求/响应、变量或密钥。未触发继续执行时计数为 0。

版本 5 还增加 `parallelGroups`，每个冻结的 `PARALLEL` 父步骤仅导出 `stepId`、配置分支数、最终完成/成功/失败分支数、尝试及重试次数、最小和最大逻辑请求序号，以及全部尝试的累计耗时。`parallelGroups` 本身不导出分支名称、URL、方法、请求/响应内容、状态文本、变量、密钥或任何自由文本；CLI/CI 可以据此判断并发扇出及重试结果，而不会获得可重放的请求素材。

## 脱敏 HTTP 请求结果

受限 HTTP 工件被执行时，Runner 对每个已尝试的请求向 v2 结果流写入一条 `testhub.api-runner.http-result`、协议版本 `2` 的记录。消息必须且只能包含 `taskId`、`executionId`、`runnerId`、`stepId`、`requestSequence`、`attemptNumber`、`method`、`outcome`、`statusCode`、`durationMillis`、`occurredAt` 以及协议字段。`requestSequence` 标识逻辑请求，`attemptNumber` 取值 1–3；`outcome=SUCCEEDED` 仅允许 2xx 状态码；失败请求保留非 2xx 状态码。建立连接前失败时 `statusCode=0`，控制面持久化为 `NULL`。

消息中不得出现请求地址、正文、请求头、Cookie、认证信息、响应头、响应正文、变量值、密钥、异常堆栈或自由文本。控制面仅在任务/执行存在、步骤 ID 与方法匹配不可变的 v3 清单、并且记录字段严格合规时写入 `api_automation_execution_http_result`；Stream 记录 ID 和执行内 `(requestSequence, attemptNumber)` 均参与去重。项目读取者可通过 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/http-results` 查看步骤标识、逻辑请求序号、尝试号、方法、成功或失败、HTTP 状态、耗时、Runner 和时间，响应中同样不包含任何请求或响应内容。

## 脱敏提取与断言结果

每个成功执行的受限提取或断言步骤会写入 `testhub.api-runner.step-result`、协议版本 `1` 的记录。该记录必须且只能包含任务/执行/Runner/步骤标识、步骤序号、步骤类型、固定结果状态、已观察 HTTP 状态码和时间。`EXTRACT` 的结果状态只能是 `COMPLETED`；`ASSERT` 只能是 `PASSED` 或 `FAILED`。它不包含变量名、变量值、期望值、地址、请求或响应内容。

控制面只接收冻结清单中类型匹配的 `EXTRACT` 或 `ASSERT` 步骤，并以 Stream 记录 ID 与执行内步骤序号去重，持久化到 `api_automation_execution_step_result`。拥有项目读取权限的成员可通过 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/step-results` 审阅步骤类型、结果和已观察状态码。

## 脱敏 pm.test 断言统计

每次配置了 `pm.test()` 的前置或后置脚本执行后，Runner 向 `testhub:runner:api:script-results:v1` 写入一条 `testhub.api-runner.script-result`、协议版本 `1` 的固定记录。它必须且只能包含任务/执行/Runner 标识、`phase`（`PRE_REQUEST` 或 `POST_RESPONSE`）、`testCount`、`passedCount`、`failedCount` 和时间。每次脚本最多 100 条断言，且通过数与失败数之和必须等于总数。

记录不含断言名称、断言表达式、异常、脚本文本、变量名或变量值、请求/响应内容、Cookie、请求头、URL 或任何密钥。Runner 会先投递该聚合计数，再将包含失败断言的脚本标记为固定脚本失败；控制面只在该阶段的脚本已在冻结清单中配置时接收记录，并使用 Stream 记录 ID 去重，持久化到 `api_automation_execution_script_result`。拥有项目读取权限的成员可通过 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/script-results` 查看安全计数；执行报告 v3 也只包含这些聚合结果和汇总计数。

## 无敏感运行时索引

持有租约的可信 Runner 可调用内部 `POST /internal/api-runner/v1/runtime-leases/inspect` 校验租约。请求体严格只包含 `taskId`、`executionId` 与租约值；签名固定负载追加租约的 SHA-256 指纹，而不是租约明文。控制面使用恒定时间比较 Redis 中保存的哈希，并再次检查 Runner、任务、执行状态、投递状态和截止时间。

校验成功只返回执行标识、到期时间、环境标识、场景版本、清单校验和，以及已冻结变量引用的名称、来源和是否为密钥。若该执行存在非敏感运行时变量快照，结果还会返回快照是否存在、快照校验和、非敏感变量计数与已保留密钥名计数，供后续最小化配置包做完整性绑定。它不回显租约，也不返回变量值、环境密钥、请求配置、脚本、步骤内容或可执行工件。该索引用于最小化配置工件的完整性检查；它的 `purpose` 会反映当前 Runner 的受限 HTTP 执行开关，但永远不包含变量值。

事件消息的 `contract` 为 `testhub.api-runner.event`。当前骨架收到合法任务后只发送 `REJECTED / EXECUTION_DISABLED` 事件，再确认任务；不合法消息只写入死信流的消息 ID、错误代码和字段名，绝不复制不可信字段值。

控制面提交场景时，会在同一个 MySQL 事务中保存执行记录和 `api_runner_outbox` 待投递项。调度器将 Outbox 写入任务流后才标记为已发送；每条消息的投递预算固定为 5 次，失败后等待 5 秒重试，达到上限后执行记录收敛为 `FAILED / DELIVERY_RETRY_EXHAUSTED`（取消指令则为 `CANCELLATION_DELIVERY_FAILED`）。控制面以独立消费者组消费 Runner 事件，并将结果回写到执行记录。当前该链路只能产生安全的预检拒绝或取消确认，不会执行用户内容。

## 兼容与安全规则

- 增加、删除或改变字段含义必须升级协议版本和 Stream key，不能静默兼容。
- 所有 ID 与范围均须由控制面校验；Runner 不接受浏览器输入。
- 控制面状态接口只读取未过期心跳，并要求调用者拥有目标项目读取权限。
- 同一 Runner 使用独立的阻塞读取连接与命令连接，确保 `XREADGROUP BLOCK` 不会阻塞心跳或状态事件。
