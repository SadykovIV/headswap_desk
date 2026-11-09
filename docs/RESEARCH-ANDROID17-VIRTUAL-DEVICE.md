# Android 17: независимый экран при настоящей PIN-блокировке

Дата: 2026-11-09. Исследование на новом одноразовом AVD `headswap060-vdm-api37`, `emulator-5860`, отдельный ADB-server `5156`. Пользовательский Samsung не затрагивался. Production APK не менялся: main33 SHA `e6e5b32dff72354005486f74e995a472502c4d559e14da44e6a627fdb704e021`. QA35 SHA `4c2c1f1094d017110bc460f1c3c05ab336a8ebfbb25c646da2040bac631a2509`.

## Результат

**Механизм VirtualDevice работает на проверенном Android 17:** экран реальной Activity другого UID продолжает обновляться и принимает касания, когда основной экран выключен, а телефон действительно защищён PIN и заблокирован. Последний кадр не выдаётся за работающую трансляцию: проверены новые timestamp, изменяющиеся пиксели и реакция на отдельное касание.

Первоначальный эксперимент ниже доказывает один VirtualDevice/VirtualDisplay в отдельном shell-процессе. Проверка производственной интеграции с родителем и дочерними окнами добавлена отдельным разделом в конце. Физическое USB-переподключение, Samsung и конкретная магнитола требуют собственных испытаний.

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

Вариант заранее выделенных дисплеев через короткоживущую shell factory не выбран для production. Реализация сохраняет настоящий shell UID владельца в ограниченном VDM host; все native create/resize/release проходят через него, без переноса callback-токенов между UID.

## Производственный host: build40

Main APK SHA `c33db1b5c32cf4dbc0376ac528cef4884583a5a7f19926deb38163a871160827`, QA SHA `e5e1a618d8a8f5bc1bdbcb3c7dd2ceeca2d50762032f7014be43b940588b8021`. Проверено на том же одноразовом API37, исключительно с `-s emulator-5860`.

`vdm-runtime` использует реальные `VdmHostBootstrap`, одноразовую nonce, provider с проверкой UID2000/живого PID, `DelegatedInputGateway`, delegated producer и shell `VdmHostCore`. Результат PASS:

- Родитель 1280×720 и дочернее окно 640×360 находятся в одной ненулевой группе3.
- При настоящей PIN-блокировке физический дисплей остаётся выключенным; получено ещё7 кадров с новым timestamp, а касание изменило marker отдельного тестового приложения. PNG просмотрен: `touch=1` и пурпурный прямоугольник видимы.
- Второе дочернее окно создано уже при PIN-блокировке; изменение DPI первого до160 и закрытие второго подтверждены платформой.
- Штатный stop удаляет все native displays с ACK, оставляя host пригодным для следующего сеанса. Завершение активации приводит к настоящей смерти host Binder.
- После завершения теста `secure=false`, `locked=false`, associations пусты; созданный тестовый PIN и только собственная association удалены.

Отдельно проверена настоящая `ManualAccessLauncher --audio --screen`: новая активация и повтор команды в существующей lease возвращают `HEADSWAP_READY`. Heartbeat подтверждает клавиатуру, звуковой маршрут, управление панелью и независимый экран. Адресно завершён только проверенный PID собственного host; app PID6334 сохранился. Повторная ручная команда восстановила host с новым PID6548 вместо6397 и новой authenticated handoff. Это проверка восстановления через компьютер, а не доказательство самовосстановления без доступного ADB.

История обнаруженных ошибок сохранена: build36 не запускал bootstrap из-за QA `Runtime.exec`, которому передали shell-присваивание вместо argv; build38/39 выявили нестабильное имя generated aconfig Flags в app_process. Production parser теперь определяется отрицательной командой с заведомо недопустимым MAC до создания association, с проверкой неизменности baseline. Первый build40 QA был ошибочно запущен без процессного `--no-hidden-api-checks`; повтор выполнен с теми же флагами, что штатная активация, без изменения production group guard.

Дополнительные evidence в том же каталоге:

- `api37-vdm-runtime-build40-hidden-api.txt`, `api37-runtime40-before-lock.png`, `api37-runtime40-locked-touch.png`, `api37-runtime40-pass-cleanup.json`.
- `api37-manual-activation-build40.txt`, `api37-manual-reactivation-build40.txt`, `api37-manual-heartbeat40.json`.
- `api37-manual-host-recovery40.txt`, `api37-manual-host-recovery40.json`.

QA41 SHA `3d168458d9f982e31b04a6999e3269b507cc2ca1361301df1216815fcde2f573` с тем же main40 дополнительно подтвердил создание нового родителя после полного stop при сохраняющейся PIN-блокировке (`newParentSessionWhilePinLocked=true`). `hardware-input` и `desktop-knob` также PASS через настоящий host: фокус двух дочерних окон, касания, rotary/OK, double-Back/Home, обычный Back без IME, отклонение старого host callback. Результаты: `api37-vdm-runtime-main40-qa41.txt`, `api37-vdm-hardware-input-main40-qa41.txt`, `api37-vdm-desktop-knob-main40-qa41.txt`.

DriveDeck создал Settings/Clock child tasks и вернул их через Home. Его fullscreen-кадр просмотрен и действительно содержит Settings и отдельные кнопки Back/Home. Однако старый QA histogram дал ложный отрицательный результат на мелком сером шрифте API37 (105 тёмных samples при пороге250), а split-снимок сделан до появления кадров дочерних приложений. Поэтому screenshot/одно касание Home/full split на этом проходе не объявляются окончательно проверенными: исправляется именно ожидание в QA, production по этому результату не изменяется.

На точке build41 ещё не были подтверждены отображение/закрытие IME и полностью отрисованный split компонентов через VDM, восстановление через собственное TLS-сопряжение, физический USB и OEM-прошивки. Эти пункты не подменяются результатом отдельного host-теста.

## Граница USB-перезапуска без Wi-Fi

Работа уже живого host не использует ADB-сокет непрерывно: обычный CarLife и блокировка телефона не требуют повторного сопряжения. Но `setsid`/`nohup` не превращают shell-процесс в Android app-процесс и не выводят его из cgroup сервиса `adbd`. AOSP `Service::Reap` и остановка сервиса завершают его process group; отдельная POSIX session не устраняет этот механизм. [AOSP init/service.cpp](https://android.googlesource.com/platform/system/core/+/refs/heads/main/init/service.cpp)

В оригинале `8ab72c8` это явно отмечено в `dex/VdmHostRestart.kt:16–20`. Его способ восстановления без Wi-Fi на Samsung включает изменение режима самого adbd: `pinViaAdbd()` отправляет `tcpip:5555`; позднее выбираются сохранённый порт, 5555, `service.adb.tls.port` и mDNS. Проверка `ADB_ENABLED` и выбор только Samsung являются дополнительными OEM-условиями оригинала, не универсальными разрешениями Android.

`tcpip:PORT` в AOSP вызывает `restart_tcp_service`, а параметр `service.adb.tcp.port` заставляет adbd слушать TCP/VSOCK. Это изменение системного отладочного listener, а не приватный локальный VPN или обычная возможность приложения. Оно не применяется нашим recovery скрытно. [ADB services.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/services.cpp), [ADB main.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/main.cpp)

Гарантии текущей реализации ограничены следующими условиями:

- Host жив: для отображения и касаний Wi-Fi не нужен.
- Host завершился, app activation жива и настоящий собственный ADB listener доступен: возможен bounded recovery с существующими ключами и новой nonce. Manual recovery уже проверен, TLS-проверка выполняется отдельно.
- Host завершился, Wi-Fi/беспроводная отладка отключили listener либо его порт изменился и не обнаруживается: автоматическое восстановление без компьютера сейчас не гарантируется. Один сохранённый порт сам по себе не создаёт listener.

Изменение глобального режима adbd или OEM-проверки настроек не внесены. Физический USB-reset на конкретном Samsung должен отдельно показать, остаётся ли host живым и какой listener доступен после переключения в CarLife.

## Проверка реального Samsung, build42

Отдельный физический прогон подтвердил активацию с телефона по Wi-Fi и самостоятельное восстановление после отключения USB при настоящей блокировке. Это измерение выполнено владельцем основного задания на Samsung; AVD не подменяет его.

- `artifacts/0.6.0-local/samsung-vdm/phone-activation42-sanitized.txt` и `physical-lock42-summary.json`: телефон активировал доступ сам; физическая панель DOZE и keyguard locked, VDM-группа24 и parent139/child140–141 остаются ON, изображение и касания подтверждены пользователем.
- `usb-replug42-logcat.txt`: после EIO/отключения кабеля старый TLS-порт перестал отвечать. Приложение сохранило PID16023, обнаружило новый listener, восстановило host18726 и группу25 с parent142/child143–144. Музыка получила pause/resume ACK, в приёмнике наблюдалось новое видео DriveDeck. Дополнительная активация, TCP5555 и компьютерная команда не использовались. Wi-Fi и беспроводная отладка оставались включены.

Добавлено read-only обнаружение `service.adb.tls.port`: только валидный порт1–65535 на127.0.0.1, затем существующие ADB-ключи и сверка приватного владельца активации. Свойство не меняется, listener не создаётся. Эта добавленная ветка требует следующей сборки; физический PASS build42 произошёл через прежнее mDNS-discovery.

Повторное UI-first-pair на AVD после интеграции VDM пока не завершено. Скрипт ошибочно нажал текст инструкции вместо действия RemoteInput и открыл Settings; это не считается ни успешным сопряжением, ни доказательством дефекта приложения. Проверки реальной phone activation и saved-TLS recovery на Samsung выше остаются отдельными положительными доказательствами.
