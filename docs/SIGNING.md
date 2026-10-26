# Подпись APK и обновления

## Неизменные параметры

- `applicationId` = `io.personal.filemanager` — **не менять никогда** после первой постоянной установки.
- `versionCode` / `versionName` — только в `app/build.gradle.kts` (`appVersionCode`, `appVersionName`).
  `versionCode` строго растёт для каждого устанавливаемого обновления.

## Сейчас: DEBUG/TEST APK

- CI допускает только **исходный** debug-ключ (alias `androiddebugkey`, стандартный пароль debug `android`).
  SHA-256 сертификата: `07b0ea83a028f013c3a3d08e2e5138d3d8d2cd8fe22cb7b8378f5f00ae5bb588`.
  Кэш читается через `actions/cache/restore` без записи новых ключей. Скрипт `tools/restore_debug_keystore.sh`
  сверяет сертификат до сборки и не переписывает подходящий существующий keystore. При отсутствии ключа или
  другой подписи CI завершается ошибкой, не генерируя замену. Если уже настроен `PFM_DEBUG_KEYSTORE_B64`,
  можно восстановить тот же исходный ключ из этого секрета (base64 от существующего `debug.keystore`).
- Старый кэш `pfm-debug-keystore-v1` был привязан к ветке `ccr-01aed2a6-se8umi` и недоступен из `main`.
  В CI #40305412451 cache miss привёл к генерации разных ключей в параллельных заданиях. Восстанавливать
  нужно исходный ключ из этой ветки/резервной копии, а не ключи из неудачного запуска `main`.
  Кэш может истечь или быть удалён; он не заменяет защищённую резервную копию у владельца.
- Перед установкой на эмулятор проверяются подписи текущего приложения, тестового пакета и предыдущего
  опубликованного APK. Проверка обновления: установка предыдущего APK → создание заметки → `adb install -r`
  нового APK → проверка заметки. Удаление приложения и очистка данных не используются.
- Точный диагноз, доступные отпечатки и ограничения проверки: [инцидент CI #40305412451](CI_SIGNING_INCIDENT.md).
- **Release-версию нельзя установить поверх debug без удаления приложения** (разные подписи). При удалении
  пропадут данные приложения (настройки, заметки, ссылки, избранное) — **перед этим сделайте резервную копию**
  в «Резервные копии» и восстановите её после установки release. Обычные файлы телефона не затрагиваются.

## Постоянная release-подпись: однократная настройка владельцем

CI соберёт подписанный release-APK автоматически, как только в репозитории появятся 4 секрета:
`PFM_KEYSTORE_B64`, `PFM_STORE_PASSWORD`, `PFM_KEY_ALIAS`, `PFM_KEY_PASSWORD`.
Ключ и пароли не попадают в Git, логи и артефакты; в CI ключ существует только во временном файле раннера.

### Шаг 1. Создать ключ (один раз)

Где выполнять: любой ваш Linux-сервер с Docker — `ВАШ_СЕРВЕР`. Лучше не продовый; команда ничего не меняет
в системе, кроме создания папки `/root/pfm-signing`. Если папка уже существует, блок остановится.

```
( set -e; command -v docker >/dev/null; test ! -e /root/pfm-signing; mkdir -p /root/pfm-signing; chmod 700 /root/pfm-signing; PASS=$(head -c 32 /dev/urandom | base64 | tr -d '/+=\n' | head -c 28); printf '%s\n' "$PASS" > /root/pfm-signing/store-password.txt; chmod 600 /root/pfm-signing/store-password.txt; docker run --rm -v /root/pfm-signing:/k eclipse-temurin:21-jdk keytool -genkeypair -keystore /k/release.jks -storetype PKCS12 -alias pfm -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PASS" -keypass "$PASS" -dname "CN=Personal File Manager"; chmod 600 /root/pfm-signing/release.jks; base64 -w0 /root/pfm-signing/release.jks > /root/pfm-signing/release.jks.b64; sha256sum /root/pfm-signing/release.jks; ls -la /root/pfm-signing )
```

Ожидаемый результат: в `/root/pfm-signing` лежат `release.jks`, `release.jks.b64`, `store-password.txt`
и выведена SHA-256 ключа.

### Шаг 2. Резервная копия ключа (обязательно)

Скачайте `release.jks` и `store-password.txt` (например, через SFTP в Termius) и сохраните **в двух местах**
вне сервера (ПК/USB и менеджер паролей/личное облако). Без ключа обновлять установленное приложение будет
невозможно — только удалять и ставить заново.

### Шаг 3. Секреты GitHub

GitHub → репозиторий → Settings → Secrets and variables → Actions → New repository secret:

| Имя | Значение |
|---|---|
| `PFM_KEYSTORE_B64` | содержимое `release.jks.b64` (одна длинная строка) |
| `PFM_STORE_PASSWORD` | содержимое `store-password.txt` |
| `PFM_KEY_ALIAS` | `pfm` |
| `PFM_KEY_PASSWORD` | то же, что `PFM_STORE_PASSWORD` |

После этого любой коммит с `[apk]` создаёт пререлиз, где рядом с DEBUG лежит `…-release-…-arm64-….apk`.

### Шаг 4. Убрать ключ с сервера (по желанию, после проверки копий)

```
( set -e; test -d /root/pfm-signing; shred -u /root/pfm-signing/release.jks /root/pfm-signing/release.jks.b64 /root/pfm-signing/store-password.txt; rmdir /root/pfm-signing; echo removed )
```

Откат: ключ восстанавливается только из ваших резервных копий шага 2.

## Переход с DEBUG на постоянную установку

1. В текущей debug-версии: «⋮ → Резервные копии → Создать копию» (лучше с паролем), сохранить файл вне приложения.
2. Удалить debug-приложение, установить release-APK (arm64).
3. «Резервные копии → Восстановить» из файла шага 1.
4. Дальше обновления release ставятся поверх без удаления (тот же ключ, растущий versionCode).

Документация Android: https://developer.android.com/studio/publish/app-signing
