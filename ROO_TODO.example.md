# ROO_TODO.example.md - 任务执行与检查清单规范

本文件是 Roo 每次执行任务时的标准检查清单模板。在开始新任务时，请复制此模板并根据具体任务内容进行调整。

---

## 📋 任务检查清单 (TODO List)

- [ ] **1. 任务分析与规划**
  - [ ] 仔细阅读用户需求，明确修改范围（Android 端 `app/` 还是 Go 核心 `libcore/`）。
  - [ ] 检查相关代码文件，理解现有逻辑，避免破坏已有功能。
  - [ ] 制定清晰、分步的实施计划。

- [ ] **2. 小步快跑式代码实现**
  - [ ] 每次只修改一个微小且自洽的功能点，避免一次性进行大规模、跨模块的修改。
  - [ ] 遵循项目现有的编码规范（Kotlin/Java/Go）。
  - [ ] 在修改关键逻辑时，添加必要的日志输出（如使用 `Logs.d` 或 Go 的 `log.Println`），以便在 GitHub Actions 编译出的 APK 中进行调试。

- [ ] **3. 语法与静态检查**
  - [ ] 检查修改过的文件，确保没有明显的语法错误或未导入的包。
  - [ ] 如果修改了 `libcore`，确保 `go.mod` 和 `go.sum` 保持一致。

- [ ] **4. 提交与 GitHub Actions 验证**
  - [ ] 提交代码并推送到远程仓库，触发 GitHub Actions 自动构建。
  - [ ] 监控 GitHub Actions 构建状态（[`.github/workflows/build.yml`](.github/workflows/build.yml)）。
  - [ ] 如果构建失败，根据 CI 日志进行针对性修复，继续保持小步提交。
  - [ ] 构建成功后，下载生成的测试 APK 进行真机功能验证。

- [ ] **5. 🔴 关键步骤：同步 REPO_SCHEMA.md**
  - [ ] 评估本次改动是否影响了项目架构、目录结构、新增协议、构建流程或关键配置。
  - [ ] 如果有任何结构性或配置上的变化，**必须**立即更新 [`REPO_SCHEMA.md`](REPO_SCHEMA.md)。
  - [ ] 确保 [`REPO_SCHEMA.md`](REPO_SCHEMA.md) 中的描述与当前代码库状态 100% 一致。

- [ ] **6. 任务收尾与交付**
  - [ ] 确认所有修改已通过 CI 验证且功能正常。
  - [ ] 确认 [`REPO_SCHEMA.md`](REPO_SCHEMA.md) 已同步更新。
  - [ ] 使用 `attempt_completion` 工具向用户汇报最终结果。

---

## 💡 核心开发原则

1. **小步提交 (Small Commits)**：由于项目所有者不做安卓开发，所有的验证都高度依赖 GitHub Actions。请务必将任务拆解为极小的步骤，频繁提交，利用 CI 快速反馈。
2. **文档即代码 (Documentation as Code)**：[`REPO_SCHEMA.md`](REPO_SCHEMA.md) 是项目唯一的架构真理来源，任何结构性改动如果不反映在文档中，均视为未完成。
3. **无侵入性 (Non-intrusive)**：尽量不修改 SagerNet 的核心框架逻辑，优先在 `moe.matsuri.nb4a` 专属包中进行扩展。
