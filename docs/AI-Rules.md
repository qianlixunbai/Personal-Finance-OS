# AI Development Rules

Version: 1.0

---

# AI Role

AI 是开发助手，不是项目负责人。

AI 不负责决定项目方向。

AI 必须遵守项目文档。

---

# Before Coding

任何代码生成之前：

必须：

阅读：

- Development Guide
- SRS
- Architecture
- Database
- API

如果缺失文档：

必须停止开发。

---

# Module Rule

每次：

只允许开发：

一个模块。

禁止：

同时开发多个模块。

---

# Refactor Rule

禁止：

重写整个项目。

禁止：

大规模重构。

禁止：

修改已经稳定模块。

如果必须修改：

说明：

修改原因

影响范围

风险

等待确认。

---

# Database Rule

禁止：

修改数据库结构。

如果需要：

必须：

说明原因。

等待确认。

---

# API Rule

禁止：

修改已有 API。

新增 API：

必须保持兼容。

---

# Output Rule

输出顺序：

1.

需求理解

2.

设计思路

3.

实现方案

4.

代码

5.

影响范围

禁止：

直接输出大量代码。

---

# Code Rule

生成代码：

必须：

符合项目规范。

必须：

易维护。

必须：

高可读。

禁止：

重复代码。

禁止：

无意义封装。

---

# Scope Rule

AI 只能：

修改当前任务。

禁止：

修改其它模块。

禁止：

优化无关代码。

---

# Bug Fix Rule

修复 Bug：

只能：

修改相关代码。

禁止：

顺便重构整个模块。

---

# Review Rule

完成后：

必须：

说明：

修改内容

影响模块

风险

是否兼容

---

# Token Optimization

禁止：

读取整个项目。

优先：

阅读：

相关模块。

优先：

局部 Patch。

避免：

重复生成代码。

---

# Final Rule

AI 永远遵循：

稳定

兼容

简单

可维护

而不是：

一次性完成所有功能。