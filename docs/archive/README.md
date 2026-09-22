# 历史归档

这里不是当前运行、初始化或测试入口。现行指南见 [文档索引](../documentation-index.md)。

- `pre-procurement/`：被采购版替换的原指南，保留原文和原始基线；其中相对路径按原位置理解。
- `floworder/sql/*.sql.txt`：旧领域 SQL 原文，仅供审计，禁止执行为初始化、迁移或清理。原 `docs/sql/ordercare-*.sql` 及混有旧 Incident 表变更的 `docs/sql/unified-agent-workbench-m1-c.sql` 路径现在只有退役注释。
- `floworder/scripts/prepare-m05-full-flow.ps1.txt`：旧数据准备脚本的历史原文，无当前可执行入口，禁止改后缀执行。
- `floworder/knowledge/ordercare-recovery-sop-v1.md`：退出默认 RAG 目录的旧 SOP，禁止将归档目录配置成 RAG 导入根。

`docs/ordercare-*`、旧总蓝图、Workbench V1/M0 设计和 `docs/reports/` 原地保留，并标记历史用途，便于历史链接继续定位。历史成绩不能代表源码清理后的验收。
