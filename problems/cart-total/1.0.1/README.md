# 购物车金额计算 1.0.1

45 分钟，无挑战。实现 `interview.CartTotal.total(List<Item>)`，保留公开签名及嵌套 record `Item(long unitPriceCents, int quantity)`。

- 金额用整数分表示；总额是各行单价 × 数量之和，返回 long。
- 空购物车返回 0；单价或数量为 0 合法。
- 负单价或负数量抛出 `IllegalArgumentException`。
- null 列表或 null 元素抛出 `NullPointerException`。
- 单行乘法或累计加法溢出 long 时抛出 `ArithmeticException`，不得静默回绕。
- 示例：799 分 × 3 + 100 分 × 2 = 2597 分。
- 不要求不同非法条件同时存在时的异常优先级。不设置算法性能判级；平台超时只保护容量。

可编辑 `src/main/java/**`、`src/test/java/**` 中的文本文件；不允许链接、上传二进制或修改 `pom.xml`。可自行写练习测试，但平台的 `base` 只使用独立只读的 `acceptance/` 权威测试。验收源码公开，不授权修改权威副本。首期题目只编译 Java 源码，不处理候选资源或注解处理器配置。

`initial/` 是准备工作区的唯一来源；`reference/` 只用于维护者发布验证，**不分发给作答者，不打入执行镜像**。初始代码可编译，8 个权威测试中预期 6 个失败；参考解应全部通过。评价标准是上述功能规则及确定性测试结果，尚无 AI 评分。

仓库根目录运行：

```sh
node --test scripts/problem-contract.test.mjs
node scripts/validate-problem.mjs problems/cart-total/1.0.1 problems/.validation/1.0.1
```

第二条需要 Docker Desktop Linux 容器或 Linux Docker、Node 24；仅镜像构建联网。运行容器禁网。验证记录输出到 `problems/.validation/1.0.1/`（不跟踪），命令不是生产执行服务，也不会把题目写入业务可选列表。发布流程、最小格式和剩余门槛见 `docs/design/problem-package.md`。

本版本在固定镜像内增加 Maven 编译失败采集器。它只识别固定 JDK 21 的部分源码语法/缺符号诊断；未知诊断、输出写入或权限错误保守归为环境错误，不以 stdout/stderr 判定。采集器不提供防恶意代码作弊保证。正式执行切片验证：`node scripts/test-execution.mjs`；该验证不等于本版本已发布，也不改变已有 1.0.0 演练的镜像身份。
