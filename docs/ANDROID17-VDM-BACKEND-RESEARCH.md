# Android 17: VirtualDeviceManager и независимая блокировка ГУ

Проверка исходников и отдельного runtime-прототипа: 9 ноября 2026. Это исследование границ нового backend, а не заявление о готовности интеграции. Проверки выполнялись на disposable AVD; пользовательский телефон не изменялся.

## Вывод

На Android 17 создание обычного `VirtualDisplay` и создание дисплея устройства `VirtualDeviceManager` — разные пути. Для второго существует отдельная группа дисплеев и политика `LOCK_STATE_ALWAYS_UNLOCKED`. Это подходящий механизм для сохранения рабочего стола ГУ при блокировке телефона; его нужно проверить вместе с дочерними окнами, вводом, клавиатурой и жизненным циклом USB.

Делегирования `CREATE_VIRTUAL_DEVICE` приложению недостаточно. VDM требует Companion association с разрешённым профилем, принадлежащую тому же пакету и пользователю. Все четыре подходящих профиля в Android 17 соответствуют системным ролям. Поэтому обычный HeadSwap APK не может получить такую association через пользовательский диалог даже при временном праве на создание VDM.

Рабочее направление — выделенный shell-хост, как в оригинале, но с обычной командой CDM для создания association, проверкой фактической группы дисплея и явным жизненным циклом. Восстановление синтетического backup не является обязательным шагом AOSP: штатная команда `associate` проходит тот же processor и выдачу роли. Синтаксис команды в Android 17 изменился; старый positional-вызов оригинала не соответствует новому `cmdOptions`.

## Первичные источники

Снимки ветки `android17-release`:

- frameworks/base: `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`.
- packages/modules/Permission: `1093eca7407576c72e51b1a88550ee0667a6e5dc`.
- Локальные исходники и SHA-256: `../artifacts/0.6.0-local/android17-vdm-research/sources.json` относительно корня репозитория. Эта папка находится в workspace вне Git.
- Оригинал HeadSwap: ADR 0033 и `VdmHostMain.kt` из изученного снимка `8ab72c8`; соответствующий код не изменился в релизе 4.1.1 (`43c32c6a00f7bbfe99f5db12a60abbd40484a26b`).

Основные ссылки:

- [VirtualDeviceManager](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/companion/virtual/VirtualDeviceManager.java): `createVirtualDevice(int, VirtualDeviceParams)`, строки 225–250.
- [VirtualDeviceManagerService](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/companion/java/com/android/server/companion/virtual/VirtualDeviceManagerService.java): профили 127–132, Binder-метод 609–636, owner attribution 652–670, поиск association 891–909.
- [roles.xml](https://android.googlesource.com/platform/packages/modules/Permission/+/1093eca7407576c72e51b1a88550ee0667a6e5dc/PermissionController/res/xml/roles.xml): SYSTEM_SHELL 663–700, APP_STREAMING 1286–1293, NEARBY_DEVICE_STREAMING 1378–1385, VIRTUAL_DEVICE 2083–2100.
- [Role.java](https://android.googlesource.com/platform/packages/modules/Permission/+/1093eca7407576c72e51b1a88550ee0667a6e5dc/PermissionController/role-controller/java/com/android/role/controller/model/Role.java): проверка `FLAG_SYSTEM` 901–910.
- [CompanionDeviceShellCommand](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/companion/java/com/android/server/companion/CompanionDeviceShellCommand.java): обе формы `associate`, 107–179.

## Проверки платформы

| Путь | Обязательные проверки | Вывод |
| --- | --- | --- |
| `VDM.createVirtualDevice(associationId, params)` | `CREATE_VIRTUAL_DEVICE`; association того же пакета/пользователя; один из 4 профилей; attribution UID и package | Временное permission delegation проходит только первую проверку. |
| `VDM.createVirtualDevice(params)` без association | Такого Binder/public-SystemApi overload нет в Android 17; подтверждено также inventory framework.jar API 37 | Нельзя строить backend на отсутствующем методе. |
| Внутренний `createLocalVirtualDevice` | Private метод внутри system_server, используется отдельным computer-control механизмом | Не является API для HeadSwap. |
| Association с профилем `null`, WATCH, COMPUTER и т. п. | Профиль не входит в список VDM | Обычного пользовательского CDM-сопряжения недостаточно. |
| APP_STREAMING / NEARBY_DEVICE_STREAMING / VIRTUAL_DEVICE / AUTOMOTIVE для HeadSwap APK | Системная роль; `Role.isPackageMinimallyQualifiedAsUser` проверяет `ApplicationInfo.FLAG_SYSTEM` | Диалог согласия не отменяет системную проверку. |
| APP_STREAMING для `com.android.shell` | Shell является системным пакетом; роль SYSTEM_SHELL уже даёт CREATE_VIRTUAL_DEVICE | Реализуемое направление. |
| Public `CDM.associate()` от Shell | Требуется `<uses-feature companion_device_setup>`; Shell его не объявляет; исключение только для UID 1000 | Даже self-managed запрос с пользовательским согласием упирается в manifest. |
| `AssociationRequest.setSkipRoleGrant(true)` | TestApi, `ASSOCIATE_COMPANION_DEVICES` и отсутствие защищённого keyguard | Не подходит телефону с PIN; не использовать как исправление. |
| `cmd companiondevice associate` от Shell | Стандартный служебный путь CDM; processor и реальная выдача роли; правильный синтаксис версии | Предпочтительный bootstrap, проверяется на AVD с PIN. |

`CREATE_VIRTUAL_DEVICE` не обязательно видно в Shell Manifest: оно выдаётся ролью `SYSTEM_SHELL`. Там же есть `ADD_ALWAYS_UNLOCKED_DISPLAY`, а с API 36 — `ASSOCIATE_COMPANION_DEVICES`. Проверять нужно фактические grants и роли, а не только список uses-permission.

`AssociationRequest.setExtraPermissions()` в API 37 не даёт альтернативы: публичный список дополнительных групп содержит только nearby-devices. Нельзя запросить через него CREATE_VIRTUAL_DEVICE.

## CDM: почему оригинал использует restore, и что можно улучшить

Оригинал сначала вызывает `apply-restored-payload`, создавая запись APP_STREAMING для `com.android.shell` с заранее выбранным MAC. Затем пробует старую форму `associate USER PACKAGE MAC PROFILE`.

В исходниках Android 17 при включённом `cmdOptions` требуется именованный синтаксис:

```text
cmd companiondevice associate USER com.android.shell --mac-address MAC --profile android.app.role.COMPANION_DEVICE_APP_STREAMING --self-managed false
```

В проверяемом AVD 37 справка показывает эту новую форму, но реальный parser отверг `--mac-address` как MAC, то есть использует старую positional-ветку. Поэтому одной справки недостаточно: выбирать форму нужно по результату и проверять созданную association. Это не доказательство, что старый вызов оригинала сломан на каждой прошивке Android 17.

Команда принимает и self-managed association без MAC. Это потенциально более точная модель USB/Wi-Fi проекции, чем выдуманный Bluetooth-адрес. Её нужно отдельно подтвердить runtime; успешная проба с MAC не доказывает этот вариант.

И `associate`, и восстановление установленного пакета вызывают `maybeGrantRoleAndStoreAssociation`. Это означает, что restore не заменяет выдачу роли и не устраняет фундаментальную проверку системного пакета. Основное различие здесь — интерфейс bootstrap и версия синтаксиса.

Production-реализация должна владеть только своей записью: фиксировать созданный ID, валидировать package/profile/user, не переиспользовать случайную association оригинального HeadSwap и не удалять чужие записи. При неоднозначном результате создания безопаснее сообщить ошибку, чем выбирать первую запись Shell.

## Минимальная схема backend

1. При явной активации на API 37 проверить доступность VDM, grants Shell и поддерживаемую форму `cmd companiondevice associate`.
2. Создать либо проверить собственную CDM association; выбрать настоящий пользовательский ID, а не всегда `0`.
3. В shell-хосте создать `VirtualDevice` с `LOCK_STATE_ALWAYS_UNLOCKED`. Не менять блокировку телефона, PIN и системные таймеры.
4. Создавать **родительский и встроенные дочерние дисплеи** через это устройство. Использовать TRUSTED/OWN_CONTENT_ONLY и DEVICE_DISPLAY_GROUP; не возвращать обычный display в группу телефона при ошибке.
5. Проверить фактический `displayGroupId > 0`, принадлежность устройству и состояние при настоящей блокировке PIN. Один успешный API-вызов или выданное право не считается подтверждением.
6. Выдать приложению ограниченный Binder-интерфейс для собственных дисплеев: создание, resize/surface, release. Проверять UID клиента, nonce активации и generation сеанса. Не отдавать произвольный shell execution.
7. При остановке/смерти клиента закрыть дочерние дисплеи, родительский display и VirtualDevice. При смерти хоста остановить или восстановить этот backend с явным статусом; не продолжать с незаметно заблокированным экраном.

VDM строго проверяет owner UID в `VirtualDeviceImpl.createVirtualDisplay` (1582) и `checkCallerIsDeviceOwner` (1780). Передача приложению сырого Shell-owned `IVirtualDevice` не даёт ему права создавать следующие дочерние дисплеи. Поэтому lifetime хоста относится ко всему рабочему столу, а не только к первоначальному parent display.

## Что ещё надо доказать

- Работа с PIN **в интеграции**: main display действительно заблокирован, а собственные parent/child продолжают рисовать и принимать ввод.
- Home, Back, клавиатура, запуск обычных приложений и ограничения remote-display manifest. Не расширять доступ вопреки `canDisplayOnRemoteDevices=false`.
- Переподключение USB на настоящем Samsung. Shell-хост из `app_process` может погибать вместе с группой adbd, тогда как наша текущая app-UID активация сохраняется. Успех на AVD не доказывает независимость от USB lifecycle Samsung.
- Чистый shutdown и повторная активация без накопления associations, устройств, дисплеев или ролей.

Оригинальный автоматический `tcpip:5555` — отдельное изменение ADB lifecycle, не обязательное условие самого VDM. Оно не включено этим исследованием. Инструментировать `com.android.shell` нашим APK также не является заменой: ActivityManagerService 15357–15379 требует совпадения подписей на production Android.

## Подтверждённый runtime-прототип API 37

Отдельная проба создала Shell association обычной positional-командой `associate`. `apply-restored-payload` не применялся. Через неё создан один VDM display и запущена одна QA Activity.

При установленном тестовом PIN одновременно зафиксированы `secure=true`, `locked=true`, `phoneState=OFF`, `displayState=ON`, `groupId=1`. Число полученных кадров, их timestamps и CRC менялись после блокировки. Касание на этом дисплее записало marker `touches=1` при всё ещё заблокированном телефоне. Это положительное доказательство механизма VDM, но не доказательство родительского/дочерних окон HeadSwap или устойчивости при USB-переключении Samsung.

Материалы вне Git: `../artifacts/runtime-qa/0.6.0-vdm/`:

- `api37-gates.txt`: CREATE/ADD_ALWAYS до и после временного делегирования; единственный overload; отсутствие association отклонено.
- `api37-cmd-associate-positional.txt`, `api37-after-associate.json`: штатное создание записи Shell.
- `api37-pin-lock-positive.json`, `api37-frames.csv`: одновременные lock/power/display/frame/touch показания.
- `api37-before-pin.png`, `api37-locked-after-touch.png`, `HeadSwapVdmProbe.java`: изображения и исходник изолированной пробы.

Удаление association должно выполняться по собственному ID. `DisassociationProcessor.disassociate` проверяет другие записи того же профиля перед снятием роли; если Shell сейчас foreground, удаление может быть отложено через состояние `revoked`. После закрытия хоста требуется сверка с исходным списком, а не безусловное удаление всех Shell associations.
