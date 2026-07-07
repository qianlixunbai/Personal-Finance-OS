# Database Review

## Review Object

docs/03-Architecture/Database.md

## Review Status

Passed with minor revisions

## Review Scope

本次 Review 覆盖：

- 当前 PostgreSQL schema 基线
- 当前 6 张表
- 当前 5 个 Entity
- asset_prices 有表但无 Entity / Mapper
- assets 缺少 account_id
- market_value 派生数据一致性风险
- logic-delete-field 配置与表结构不匹配
- Dashboard / Analytics 不保存人工统计结果
- Future Evolution 边界

## Minor Revisions Applied

本次小修已完成：

- 补充系统分类例外：categories.user_id IS NULL 且 is_system = TRUE 表示全局系统分类，普通用户只读不可改删
- 补充 asset_prices 属于市场参考数据，不是用户私有业务数据，因此当前不含 user_id
- 补充 spring.sql.init.mode: always 适合开发阶段，生产应迁移到 Flyway / Liquibase
- 补充 users.status / accounts.status 当前仍为 VARCHAR，尚无 CHECK 约束
- 补充 UNIQUE(symbol, price_date) 通常已支持组合查询，无需重复普通索引

## Review Conclusion

Database.md 可以作为后续 schema 修正、migration 规划和 API.md 设计的上游依据。

但需要注意：

- Database.md 不代表 Target Design 已全部实现
- schema.sql 暂未修改
- Entity / Mapper 暂未修改
- 当前 Known Gaps 应作为后续 Review issue 或 ADR 候选项处理

## Follow-up Items

后续待处理：

- assets.account_id schema/entity 修正
- AssetPrice Entity / Mapper
- asset_prices.updated_at
- logic-delete 配置与 schema 对齐
- Flyway / Liquibase migration
- 更完整的转账模型
- 投资交易流水
- 汇率表
- 审计日志
