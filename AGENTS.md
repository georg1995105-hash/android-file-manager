# Agent instructions — Android File Manager

## Обязательные принципы исполнения — 27.11.2026

- Качество результата важнее поспешности: работай эффективно, но не сокращай необходимые проверки ради скорости. Выявляй первопричину, проверяй граничные случаи, регрессии и фактическую работоспособность. Не обещай отсутствие ошибок без доказательств.
- При неудаче изучи конкретную причину, исправь её и проверь снова. Если выбранный подход не работает, исследуй задачу с другого технического угла и используй другие разрешённые штатные инструменты. Не повторяй бесполезные попытки и не обходи ограничения безопасности или доступа.
- Владелец определяет цели, коммерческие условия и распоряжается своими приложениями и инфраструктурой. Его прямое поручение — основание выполнить действия в указанном объёме с доступными полномочиями. Не спорь о целесообразности уже принятого решения, не требуй повторного доказательства владения или лишнего согласования обычных операций без конкретных оснований. Соблюдай обязательные ограничения платформы, фактические права доступа и установленные проектом защитные процедуры; при реальном препятствии сообщи точную причину и выполни остальные доступные этапы.
- Различай диагностику и поручение на изменение. При поручении действуй самостоятельно: понять → проверить состояние → исправить → проверить результат → завершить. Сохраняй данные, рабочие функции и согласованные настройки; на production проверяй живое состояние, обеспечивай соразмерный backup/rollback и проверку после внедрения.
- Не останавливайся на промежуточном коммите, сборке или отчёте. Для приложения самостоятельно доводи согласованные изменения до штатного подписанного релиза и OTA, если проект имеет официальный OTA-канал и владелец не ограничил задачу. Запускай необходимые этапы без повторной команды; сохраняй требования конкретного проекта к проверкам, подписи и каналам публикации. Для инфраструктуры критерий завершения — проверенное действие в живой среде, не только успех команды.
- Читай актуальный AGENTS.md при начале и возобновлении работы. Не подменяй актуальные сведения памятью. Чётко разделяй сделанное, проверенное и оставшееся; при объективном блокере фиксируй результат и следующий шаг. Не меняй защитные настройки и не запускай новые фоновые автоматизации ради формального завершения.

Use `PROJECT_STATUS.md` and the relevant part of `PROJECT_SPEC.md` as the source of truth for the existing app; read other files only as needed. Work only in this repository. Do not modify the separate gallery or other projects.

- Finish the requested work autonomously: identify the problem, make a minimal compatible change, run necessary targeted checks, and produce an installable APK when requested. Do not stop at a plan, intermediate progress update, or a code-only result when an APK was requested.
- Protect personal files and existing features. For copy/move/delete/restore/backup operations, test against disposable fixtures; do not touch real user data. Preserve app ID, stored settings, version upgrade path, and signing identity.
- Keep test effort proportional: compile/targeted tests first, then one critical scenario; run phone and tablet emulators only for relevant cross-device behavior or release acceptance. Do not launch repeated full CI runs or new branches/tags merely to retry the same failed test.
- CI build uses `.github/workflows/android.yml` and JDK 21. Verify test compilation before long device workflows. When CI fails, inspect the specific failure and rerun only the affected scope after a fix. Never call a successful build proof of real-device behavior.
- Keep owner release keys and credentials out of Git. Do not rotate debug signing casually: changing signing identity can prevent updates over installed APKs. Publishing to app stores is separate work and requires an explicit request.
- Respect concurrent work: use an appropriate task branch; no force pushes, resets or overwriting someone else's changes. Existing working code is more important than refactoring.
- Report succinctly: what changed, commit/branch, what actually passed, artifact link when applicable, and exact remaining device test/blocker. Solve routine environment problems yourself if possible; otherwise preserve verified progress rather than loop on identical retries.


## Состояние задачи и передача другому агенту

- Для новых задач используй [docs/agent/README.md](docs/agent/README.md): Issue → PR → текущий head SHA → GitHub Actions → непроверенное и следующий шаг. Исторический `PROJECT_STATUS.md` нельзя принимать за актуальный статус ветки без проверки.
- После каждого этапа обновляй живой handoff в PR; не объявляй сборку подтверждением реального устройства и не переносить успешный CI со старого SHA на новый коммит.
- Используй только очищенные данные для поиска и индексов; не передавай в облачный граф знаний ключи подписи, личные файлы, токены, приватные пути и содержимое устройств.
