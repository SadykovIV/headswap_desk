# Android 17: независимый экран при настоящей PIN-блокировке

Дата: 2026-11-09. Исследование на новом одноразовом AVD `headswap060-vdm-api37`, `emulator-5860`, отдельный ADB-server `5156`. Пользовательский Samsung не затрагивался. Production APK не менялся: main33 SHA `e6e5b32dff72354005486f74e995a472502c4d559e14da44e6a627fdb704e021`. QA35 SHA `4c2c1f1094d017110bc460f1c3c05ab336a8ebfbb25c646da2040bac631a2509`.

## Результат

**Механизм VirtualDevice работает на проверенном Android 17:** экран реальной Activity другого UID продолжает обновляться и принимает касания, когда основной экран выключен, а телефон действительно защищён PIN и заблокирован. Последний кадр не выдаётся за работающую трансляцию: проверены новые timestamp, изменяющиеся пиксели и реакция на отдельное касание.

Это доказательство одного VirtualDevice/VirtualDisplay в отдельном shell-процессе. Оно пока не доказывает готовность интеграции с HeadSwap, жизнь helper после обрыва ADB/USB, составной экран с дочерними окнами, Samsung или конкретную магнитолу.

## Проверенные границы API

- В framework.jar именно этого API37 есть только `VirtualDeviceManager.createVirtualDevice(int associationId, VirtualDeviceParams)`. Перегрузки без association нет ни в manager, ни в Binder-интерфейсе. Одноимённый метод с одним `params` в AOSP — `VirtualDeviceManagerInternal.LocalService`, доступный внутри `system_server`, а не приложению.
- Обычный UID HeadSwap до активации не имеет `CREATE_VIRTUAL_DEVICE` и `ADD_ALWAYS_UNLOCKED_DISPLAY`. После ограниченного `UiAutomation.adoptShellPermissionIdentity` оба права фактически доступны. У Shell они предоставляются ролью `SYSTEM_SHELL`; отсутствие разрешения в Shell manifest не означает отсутствие grant.
- Одних прав недостаточно: `createVirtualDevice(-1, params)` проходит проверку permission и отклоняется с `No association with ID -1`. Нужна CDM-запись соответствующего владельца/профиля.
- Получать чужую роль или снимать PIN для этого не требуется. Прототип использует настоящий UID2000, `com.android.shell` AttributionSource и собственную экспериментальную CDM-запись; проверка UID в сервисе сохраняется.

Первичные источники: [VirtualDeviceManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/companion/virtual/VirtualDeviceManager.java), [VirtualDeviceManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/companion/java/com/android/server/companion/virtual/VirtualDeviceManagerService.java), [ограниченное наследование прав Shell](https://developer.android.com/reference/android/app/UiAutomation#adoptShellPermissionIdentity(java.lang.String...)). Результаты конкретного образа AVD имеют приоритет перед выводами по другой ветке AOSP.

## Минимальный положительный эксперимент

1. Сохранены CDM associations, роли, PIN-state. Baseline: associations пуст, `secure=false`, `locked=false`; helper проверяет `uid=2000`, `SDK=37`, `HARDWARE=ranchu/goldfish`.
2. Создана ровно одна запись командой `cmd companiondevice associate 0 com.android.shell 02:48:53:06:00:37 android.app.role.COMPANION_DEVICE_APP_STREAMING false`. Возвращён ID1. `apply-restored-payload` **не применялся**. Именованный синтаксис, напечатанный `help`, фактически не поддерживался активным parser: `--mac-address` был принят за MAC и отклонён без изменения записей. Это требует проверки результата, а не предположения по help.
3. Shell helper создал VirtualDevice с `LOCK_STATE_ALWAYS_UNLOCKED`, затем trusted own-content VirtualDisplay 640×360, 128 DPI. Дисплей2 получил отдельную группу1. Запущена только `VirtualDeviceLockFixture` из отдельного test APK. У неё нет `showWhenLocked` и `KEEP_SCREEN_ON`; меняющийся рисунок работает только пока Activity resumed.
4. Установлен известный тестовый PIN на исходно незащищённом AVD и выполнен `KEYCODE_SLEEP`. Измерено: `secure=true`, `locked=true`, физический экран `OFF(1)`, виртуальный `ON(2)`.
5. После блокировки за 1,5 секунды число новых кадров выросло 483→496, PTS 373549262885→375200986969, CRC изменился. Одно `input -d 2 tap 120 260` изменило marker `touches=0→1` и цвет прямоугольника с цианового на пурпурный. Телефон остался защищённым, заблокированным и выключенным. После касания получено 510 кадров.
6. Helper штатно закрыл VirtualDevice. Удалена только проверенная тестовая запись ID1/уникальный MAC. Снят только созданный тестом PIN с его известным значением. Baseline восстановлен: associations пуст, secure/locked false, остался один физический дисплей. Role holders совпадают с baseline; изменился лишь технический `packages_hash`, а не назначение роли.

Временный helper первоначально не смог прочитать KeyguardManager из `app_process`: Android17 требует инициализации ApplicationSharedMemory для WindowManagerGlobal. Диагностическое чтение заменено на read-only ITrustManager Binder; обход блокировки или изменение глобальных framework flags не применялись.

## Доказательства

Каталог workspace `artifacts/runtime-qa/0.6.0-vdm/`:

- `api37-gates.txt`: реальные permissions до/после adoption, перечень методов, отрицательный association gate.
- `api37-helper-baseline.txt`, `api37-after-associate.json`: точные baseline/созданная запись.
- `api37-cmd-associate.txt`, `api37-cmd-associate-positional.txt`: различие help и активного parser.
- `api37-pin-lock-positive.json`, `api37-frames.csv`: secure lock, группы/состояния, новые кадры и касание.
- `api37-before-pin.png`, `api37-locked-after-touch.png`: просмотренные изображения реальной тестовой Activity.
- `api37-after-cleanup.json`, `api37-role-*-cleanup.txt`, `api37-display-after-cleanup.txt`: адресная очистка.
- `HeadSwapVdmProbe.java`, `helper-dex.zip`: воспроизводимый временный helper. Production не зависит от него.

Следующий отдельный эксперимент — создать VDM и заранее выделенные дисплеи через короткоживущую shell factory с lifetime/callback Binder приложения, затем завершить shell-процесс. До проверки нельзя обещать, что это сохранит независимый экран при USB-переподключении.
