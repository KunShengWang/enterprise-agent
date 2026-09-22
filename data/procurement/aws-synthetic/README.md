# AWS synthetic procurement data

来源：`aws-samples/sample-multi-agent-procure-to-pay`

本目录仅保留第一阶段采购寻源 MVP 使用的供应商基础数据。
数据为 synthetic and fabricated for demonstration，来源仓库采用 MIT No Attribution
许可证；本项目不把它们当作生产供应商或实时报价数据。

本目录是 AWS Base Dataset。Phase 1 只消费 `01_suppliers.json` 做无 scenario 时的
供应商 fallback discovery；workstation 的供应商专属报价、交期和规格来自
`data/procurement/scenarios/complex_workstation_01.json`，不应把 fixture 报价描述为
AWS 原始数据。
