# 多维滑动窗口限流器
实现 `limiter.SlidingWindow`。每个 `(tenant, route)` 元组独立计量，容量与扣减单位均为正 long。使用注入的 Clock，窗口为 `(now-windowMillis, now]`；时钟回拨时仍使用已见最大毫秒值。只登记成功的扣减；并发调用不得超卖，极端时间戳与容量不得溢出。非法单位、规则或窗口抛 IllegalArgumentException。基础阶段 reserve 为零，不考查 update。
