# 账号管理 keyword 搜索契约

2026-11-04，对应D06-A / OBS-03D-E007。

GET /api/v1/accounts的keyword在账号、姓名、邮箱、电话四列按字面子串搜索。保持原trim、小写归一化及数据库排序规则，省略/null/空串/全空白仍按原未删除账号集合查询；停用账号仍在系统账号列表中，软删除账号仍过滤。分页归一化和排序沿用原规则。

百分号、下划线和反斜杠是普通搜索字符。列表与count共用同一个绑定参数构造，四列均显式指定ESCAPE '!'；先将输入中的!变成!!，再将%和_转义。由于显式LIKE escape不是反斜杠，匹配阶段将反斜杠作为字面字符。业务代码通过JDBC绑定keyword，不将输入拼接进SQL；驱动参数编码仍须与服务端连接模式一致。选定escape字符自身仍须字面化。

[MySQL官方LIKE定义](https://dev.mysql.com/doc/refman/8.4/en/string-comparison-functions.html#operator_like)说明%/_通配符、显式ESCAPE和NO_BACKSLASH_ESCAPES的默认规则差异；[H2官方LIKE语法](https://h2database.com/html/grammar.html#like_predicate_right_hand_side)也支持显式ESCAPE。本产品采用上述字面规则，H2与独立MySQL实际行为以本轮测试结果为准，不改变数据库全局模式或排序。

MySQL模式变体在建立自有测试连接时通过官方[sessionVariables属性](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-session.html)配置，分别验证普通模式和NO_BACKSLASH_ESCAPES。当前[Connector/J 9.7.0会话状态实现](https://github.com/mysql/mysql-connector-j/blob/9.7.0/src/main/protocol-impl/java/com/mysql/cj/protocol/a/NativeServerSession.java)从缓存的sql_mode判断反斜杠编码；在用连接中途手工SET模式不作为本轮支持承诺。测试先核对参数回显，再验证同一生产仓库的列表和count，不修改生产驱动配置。

输入继续作为预编译参数，引用号或SQL片段仍是查询数据。原观察是LIKE通配语义，不作为已确认SQL注入缺陷。账号权限、项目PAT限制、响应字段和审计规则保持。

客户端继续用URLSearchParams编码普通keyword，不添加数据库LIKE转义，也不提供通配语法开关。其他模块搜索、全局数据库配置、账号写入及身份校验不在本次变更范围。
