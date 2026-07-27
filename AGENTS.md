# AGENTS.md
本文件定义本仓库中AI Coding Agent必须长期遵守的开发约束。任务提示词说明“本次实现什么”，本文件说明“始终怎样实现”。本文件优先于一般实现习惯，除非用户明确要求例外。

## 1.工作流程
1.修改前先检查根目录及相关模块`pom.xml`、已有模型、接口、实现、配置类、错误码、Controller、DTO及实际依赖版本。
2.以仓库真实代码、包名、构造器、字段及框架API为准，不得凭记忆猜测。
3.优先复用已有抽象，禁止创建职责重复的`V2`、`NewXxx`、`AnotherXxx`。
4.只实现当前任务范围；阻断问题做最小修复，非阻断问题只报告。
5.禁止无关重构、包迁移、配置改造、依赖引入及后续阶段提前实现。
6.不得为了演示成功添加固定答案、Fake实现、隐藏接口或权限绕过。
7.完成后必须真实编译；环境不足时明确说明未验证内容，不得伪造结果。

## 2.模块职责
### agent-core
-存放稳定领域模型、值对象、错误码、Store抽象和框架无关SPI。
-禁止依赖Spring、SpringWeb、SpringAI、LangGraph4j、Jackson、数据库、Redis及其他上层模块。
-禁止出现`@Component`、`@Service`、`@Configuration`、Servlet、ChatClient、CompiledGraph等类型。
### agent-runtime
-存放ReAct、Supervisor、Registry、Gateway默认实现、工具拦截器、权限规则及LangGraph4j编排。
-允许依赖LangGraph4j；禁止依赖SpringAI、SpringWeb、agent-infrastructure、agent-api。
-runtime实现默认保持纯Java，不添加Spring注解。
### agent-application
-存放登录、会话、Agent/Supervisor调用、用户和角色管理等应用用例。
-保持纯Java；禁止依赖SpringWeb、SpringAI、LangGraph4j、Servlet及具体基础设施实现。
### agent-infrastructure
-存放SpringAI/Jackson等技术适配、内存或持久化实现、密码哈希、ID生成器及Spring Bean装配。
-只实现core/runtime定义的接口，不承载业务流程。
### agent-api
-存放Controller、HTTP DTO、MVC Interceptor、异常映射。
-Controller保持薄层，只调用Application Service。
### agent-bootstrap
-负责启动和最终装配，可存放受开关控制的Sample Agent、Supervisor、用户和角色。
-禁止在主启动类中直接实现业务初始化和执行逻辑。

## 3.依赖方向
总体方向：
`agent-api→agent-application→agent-runtime→agent-core`
`agent-infrastructure`实现core/runtime接口并由Spring装配。
禁止core依赖runtime、runtime依赖infrastructure、application依赖api、Controller直接操作图或模型底层对象。

## 4.Spring装配
1.runtime和application禁止使用`@Component`、`@Service`、`@Autowired`、`@Configuration`。
2.依赖统一使用构造器注入。
3.Spring Bean通过`@Configuration`+`@Bean`装配。
4.默认实现应支持`@ConditionalOnMissingBean`替换。
5.禁止字段注入、`ApplicationContext`主动查Bean、静态Bean持有及启动类大量手工`new`。
6.无模型配置时，框架查询、身份、Store等非模型能力应尽量正常启动。

## 5.第三方框架原则
1.允许依赖框架的模块内，原生接口能准确表达需求时必须直接使用。
2.只有为隔离第三方类型泄漏、供应商变化或真实语义转换时才创建Adapter/Mapper。
3.Adapter必须使用明确泛型，禁止`Object`通用参数、反射及`instanceof`类型分派。
4.使用SpringAI、LangGraph4j前必须检查当前实际版本和接口，禁止凭记忆创造API。
5.第三方类型不得泄漏至core、application和api的稳定协议。

## 6.LangGraph4j规范
1.同步节点实现`NodeAction<State>`，注册时使用原生`node_async(node)`。
2.异步节点实现`AsyncNodeAction<State>`，直接注册。
3.同步路由实现`EdgeAction<State>`或兼容方法引用，使用原生`edge_async(...)`。
4.异步路由实现`AsyncEdgeAction<State>`，直接注册。
5.禁止`wrapNode(Object)`、`wrapRouter(Object)`、反射或`instanceof`节点分派。
6.禁止重复实现`node_async`、`edge_async`或语义相同的平行接口。
7.GraphFactory只负责Channel、节点、边、路由及图编译。
8.GraphFactory不得调用模型、工具、子Agent、查Spring Bean或保存运行State。
9.自定义State可继承`AgentState`，值仍存父类Map；禁止重复定义同名Java字段形成双份状态。
10.State访问必须集中且类型安全，不得在节点中散落字符串Key和未检查强转。
11.节点只返回本轮状态增量；Appender Channel只返回新增元素，不返回完整历史。
12.`iteration`使用覆盖语义，禁止Channel累加后节点再次`+1`。
13.禁止static可变State、ThreadLocal State、跨请求复用State及全局锁串行全部请求。
14.CompiledGraph是否复用必须基于当前版本线程安全语义；不确定时优先每次构建独立图。

## 7.模型调用
1.所有模型调用统一经过`ModelInvocationGateway`。
2.Controller、Application Service和节点不得直接创建或调用SpringAI ChatClient/ChatModel。
3.SpringAI适配器只负责消息、工具和响应映射及供应商差异。
4.SpringAI不得自动执行工具；模型只返回ToolCall。
5.不得向LLM发送userId、sessionId、roles、permissions、RunContext、密码、密钥、内部异常和Java实现信息。
6.模型输出属于非可信输入，结构化结果必须解析、白名单校验和字段规范化。
7.不得要求或保存详细思维链，只允许简洁决策摘要。

## 8.工具执行
1.所有真实工具执行必须经过`ToolInvocationGateway`。
2.禁止Controller、Application Service、节点、Supervisor直接调用`AgentTool.execute`。
3.推荐治理顺序：异常治理→审计→ACL→参数校验→Terminal执行器。
4.实际Bean列表顺序必须结合现有链执行方向确认，禁止依赖偶然顺序。
5.拦截器链禁止共享可变index和ThreadLocal；每次调用使用独立不可变推进状态。
6.AgentDefinition.allowedTools决定模型可见工具；用户ACL决定是否允许真实执行，两层必须同时成立。
7.参数错误、权限拒绝和可预期业务失败应返回`ToolResult.success=false`。
8.普通失败不得直接终止ReAct，应经Observe转成`ToolAgentMessage(error=true)`回灌模型。
9.只有无法形成有效ToolResult的框架内部异常才进入失败路径。
10.具体工具内部禁止编写ACL、Session校验和重复网关治理逻辑。

## 9.ReAct规范
图结构保持：
`START→reason→路由`
`EXECUTE_TOOLS→execute_tools→observe→reason`
`COMPLETE/MAX_ITERATIONS/FAIL→对应终止节点→END`
1.Reason每轮只调用模型一次，只暴露allowedTools，不执行工具。
2.模型有ToolCall时交给路由；无ToolCall且有最终内容时进入Complete。
3.ToolExecution按当前约定顺序处理全部ToolCall，只通过Gateway执行并保持调用结果配对。
4.Observe使用原ToolCall ID和工具名生成ToolAgentMessage，失败时`error=true`。
5.Observe处理完成后清空本轮pendingToolCalls和latestToolResults。
6.ReAct iteration表示已完成的Reason模型调用次数，初始0，仅Reason成功调用后+1。
7.最后一次允许调用返回最终答案时正常完成；仍返回ToolCall时不再执行新工具。

## 10.Supervisor规范
1.Supervisor只负责分析、拆分、选择成员Agent、接收AgentResult及汇总。
2.Supervisor不得直接执行业务工具，不得把子Agent注册为SpringAI Tool。
3.所有子Agent必须通过`ReactAgentEngine`执行。
4.每个子任务使用独立AgentTask、childRunId、childThreadId和ReactAgentState。
5.子Agent继承父RunContext中的userId、sessionId、roles、permissions，但不得提升权限。
6.Supervisor只接收标准AgentResult，不访问子Agent完整消息、State、ToolTrace和异常对象。
7.子Agent结果回灌Supervisor使用普通观察消息，不使用ToolAgentMessage。
8.观察内容必须截断并过滤敏感信息。
9.Supervisor iteration仅统计Supervisor模型调用，子Agent iteration不影响它。
10.最后一次调用返回FINISH时正常完成；仍返回DISPATCH时不执行新任务。

## 11.身份与Session
1.正式请求身份必须来自服务器验证后的UserSession。
2.客户端不得决定userId、roles、permissions、runId、threadId、systemPrompt、allowedTools或maxIterations。
3.RunContext中的userId、sessionId、roles、permissions必须来自已验证Session。
4.一个用户可以拥有多个Session；每次登录创建一个新Session并返回本次sessionId。
5.登出默认只撤销当前Session，不影响同用户其他Session。
6.禁止通过sessionId格式推断用户，禁止在sessionId中编码userId。
7.禁止static当前用户、ThreadLocal当前用户及记录sessionId。
8.Session权限采用登录时快照：角色或权限变化后撤销受影响用户旧Session，重新登录获取新权限。
9.不得直接修改已有UserSession的roles或permissions。
10.SessionStore、UserStore、RoleStore接口放在core，内存实现在infrastructure。

## 12.权限与ACL
1.工具权限格式：`tool:{toolName}:invoke`；全工具权限：`tool:*:invoke`。
2.精确权限或通配权限任一存在则允许，否则拒绝；匹配必须完全匹配。
3.禁止根据角色名`ADMIN`、用户名`admin`或Agent名特殊放行。
4.管理权限同样通过稳定permission code判断，禁止Controller硬编码角色。
5.工具ACL只读取当前`ToolInvocation.runContext.permissions`。
6.权限为空、RunContext缺失或身份不完整时默认拒绝。
7.开发API如需全部工具权限，应由服务器创建的dev RunContext显式包含`tool:*:invoke`，禁止隐藏绕过。
8.认证失败返回401且不进入Agent；已认证但管理操作无权限返回403。
9.Agent内部工具越权通常返回失败ToolResult并回灌模型，不直接把整个请求转成HTTP403。
10.权限、密码、用户状态或角色变化后，应撤销受影响用户Session。

## 13.多用户隔离
1.Session按sessionId查询并明确关联userId。
2.Memory按userId隔离，Checkpoint按threadId隔离，必要时校验userId/threadId归属。
3.禁止使用客户端传入userId作为可信隔离依据。
4.不同请求不得共享RunContext、State或授权缓存。
5.同一拦截器和Engine必须支持不同用户并发调用。
6.admin请求不得影响visitor权限，visitor拒绝不得影响admin执行。
7.身份对LLM隐藏，对节点和工具可见，但不得被修改。

## 14.Store规范
1.Store接口位于core，实现位于infrastructure。
2.内存实现使用`ConcurrentHashMap`或明确线程安全结构，禁止static全局Map。
3.查询未找到返回`Optional.empty()`；list返回不可变快照。
4.create和update语义分离，重复创建不得静默覆盖。
5.多索引必须保持一致，删除和撤销需明确幂等语义。
6.Store不负责登录、生成ID、权限解析、构建RunContext、调用模型或执行工具。

## 15.错误与日志
1.优先复用`AgentErrorCode`、`AgentFrameworkException`和统一HTTP错误结构。
2.禁止创建语义重复错误码。
3.异常转换必须使用明确错误码并保留服务端cause，禁止无语义`RuntimeException`。
4.不得使用null表示失败、未找到、空集合或终止结果。
5.客户端不得收到堆栈、内部类名、文件路径、API Key、模型原始响应、完整Prompt、密码、hash或sessionId。
6.日志可记录runId、threadId、agentName、supervisorName、toolName、iteration、stopReason、errorCode和授权结果。
7.日志禁止记录密码、credentialHash、sessionId、X-Session-Id、API Key、完整messages、完整模型响应、工具完整参数结果及完整权限集合。
8.不得在MDC保存sessionId。

## 16.Java代码质量
1.禁止Lombok，优先使用record、enum和final class。
2.集合和Map构造时复制，对外返回不可变快照。
3.禁止字段注入、static可变业务状态、ThreadLocal业务状态。
4.禁止`Object`通用业务参数、反射分派及大量`instanceof`分派。
5.禁止无意义Facade、Delegate、Manager、Helper和只转发不增值的Adapter。
6.禁止吞异常、固定假结果、复制已有Gateway治理逻辑。
7.类职责单一，方法和类型使用明确泛型。
8.所有终止路径必须产生明确AgentResult或抛结构化异常。

## 17.API与前端
1.Controller使用专门请求/响应DTO，不直接返回内部领域实现和第三方对象。
2.客户端Body禁止提交userId、sessionId、roles、permissions、systemPrompt、allowedTools、memberAgents、maxIterations和credentialHash。
3.前端使用Vue3+Vite+JavaScript，禁止TypeScript，除非用户明确改变。
4.前端不得直连模型、保存API Key、密码、hash或把sessionId放进URL/Body。
5.Session访问、HTTP客户端、401/403处理必须集中封装。
6.前端权限判断只用于展示和导航，后端始终是最终安全边界。
7.正式前端不得调用`/api/dev/**`。
8.涉及前端修改时必须执行真实构建。

## 18.配置与Sample
1.禁止提交真实API Key、密码、私钥、固定sessionId和敏感`.env`。
2.敏感值通过环境变量或安全配置注入。
3.Sample Agent、Supervisor、用户和角色必须受`agent.sample.enabled`等开关控制。
4.Sample Provider只提供定义，不直接操作Registry、执行模型或工具。
5.Sample密码不得硬编码；缺少密码时应用应继续启动但不创建对应用户。
6.禁止创建Fake ModelClient帮助无模型环境伪装成功。

## 19.文档、测试和Git
除非用户明确要求，禁止：
-新增测试脚本或测试工程
-创建或修改README、使用说明、升级记录、验收报告
-执行git commit、git push或修改远程仓库
-修改本AGENTS.md
`.kscc-prompts/`仅用于本地提示词并应加入`.gitignore`。
可以运行已有测试，但不要额外生成测试脚本。

## 20.编译与最终输出
后端修改后至少执行：
`mvn clean compile -DskipTests`
`mvn -pl agent-bootstrap -am package -DskipTests`
前端修改后按现有锁文件使用对应包管理器，并执行真实构建。
失败时继续修复；无法修复时说明准确阻塞原因，不得伪造成功。
最终输出至少包含：
1.新增和修改文件。
2.核心实现及调用链。
3.重要架构选择。
4.条件装配或配置行为。
5.编译、打包、启动和接口验证结果。
6.未完成验证及准确原因。
7.发现但未处理的非本批问题。
禁止把计划描述成已完成结果。

## 21.提交前检查
-已检查真实代码和实际框架版本
-未创建重复抽象或修改无关模块
-模块依赖方向正确
-core未引入技术框架依赖
-runtime/application保持纯Java
-Spring Bean统一配置装配
-无字段注入、ThreadLocal和static业务状态
-LangGraph4j使用原生NodeAction/AsyncNodeAction及node_async/edge_async
-GraphFactory只负责构图
-模型调用经过ModelInvocationGateway
-工具执行经过ToolInvocationGateway
-SpringAI未自动执行工具
-ACL未写入具体工具
-身份和权限来自已验证Session
-RunContext未发送给LLM
-日志和响应未泄漏敏感信息
-无Fake实现、固定结果和隐藏绕过
-未提前实现范围外功能
-未生成无关测试、文档或Git提交
-后端编译及bootstrap打包通过
-涉及前端时前端构建通过
-最终输出未伪造验证结果

22. ## Windows 构建环境

- 项目根目录：`C:\Users\KC\Desktop\agent-platform`
- JAVA_HOME：`C:\Users\KC\.jdks\openjdk-26.0.1`
- Maven：`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven\lib\maven3\bin\mvn.cmd`
- 不要扫描注册表或磁盘寻找 Java/Maven。
- 完成全部修改后只执行一次编译。