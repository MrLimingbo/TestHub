# Postman 接口文件导入操作路径

## 本次文件与目标

- 文件：`/Users/limingbo/Downloads/AI-Agent.postman_collection.json`
- 格式：Postman Collection v2.1
- 目标项目：`交易中台 V3`
- 目标目录：`接口测试 / 接口 / AI相关`

## 页面操作路径

1. 打开 TestHub 登录页：<http://127.0.0.1:5173/login>。
2. 登录后，在页面右上角的项目选择器中选择 **交易中台 V3**。
3. 在左侧主导航中进入 **接口测试**。
4. 在接口管理树中展开 **接口**，选择 **AI相关** 目录。
5. 点击接口管理工具栏中的 **更多（…）**，选择 **导入 / 导出**。
6. 在“导入 / 导出接口资产”弹窗中选择 **Postman Collection**。
7. 将“目标接口目录”设置为 **AI相关**。
8. 点击 **选择文件**，选择：

   ```text
   /Users/limingbo/Downloads/AI-Agent.postman_collection.json
   ```

9. 确认文件名显示正确后，点击 **校验并导入**。
10. 等待成功结果出现，再关闭弹窗。回到左侧目录树，展开 **AI相关** 检查导入结果。

## 导入规则

- 支持 Postman Collection v2.0、v2.1 JSON 文件，单个文件最大 10 MiB。
- 导入前会完成格式、目录、接口和请求数据校验；校验失败时不会留下部分数据。
- 相同请求方法和路径的 Postman 请求会归并为一个接口，每条原始请求保存为该接口下的调试用例。
- 没有 URL 的 Postman 草稿请求会跳过，并在导入结果中显示警告。
- `Authorization`、`Cookie`、Token、API Key 等敏感值不会以明文保存到接口示例或调试用例中；需要执行接口时，应改用项目环境变量。

## 本次导入验收值

`AI-Agent.postman_collection.json` 共包含 23 条请求，预期结果为：

- 导入接口：11 个；
- 生成调试用例：22 个；
- 跳过无 URL 草稿：1 个。

进入任一导入接口后，可在接口工作台的“用例”区域查看由 Postman 请求生成的调试用例。

## 常见问题

- 页面提示“无法初始化安全会话”或无法连接 Platform API：在项目根目录重新执行 `./start.sh --with-api-runner`，脚本显示恢复检查通过后刷新页面。
- 文件超过 10 MiB：在 Postman 中按业务目录拆分 Collection 后分别导入。
- 导入提示格式不支持：确认文件 `info.schema` 指向 Postman Collection v2.0 或 v2.1 schema，而不是 Postman Environment 或数据文件。
- 导入后接口不能直接执行：先在 **系统设置 / 环境管理** 中配置当前项目环境，并将敏感认证值放入环境变量，再在接口工作台选择该环境。
