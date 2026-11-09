# План интеграции VirtualDevice в HeadSwap Desktop

Дата: 2026-11-09. Исходный план составлен перед локальным checkpoint `a3438e6` (main33/input17). **Обновление: owner-preserving VDM host и интеграция реализованы в исходниках, private protocol18 скомпилирован и проверен unit-тестами; production runtime main40 на API37 подтвердил parent/child одной группы, реальный PIN, продолжающиеся кадры/касания, создание child под блокировкой и resize/release. Остальные профильные и аварийные регрессии ещё выполняются.** Предыдущая матрица main33 не доказывает работоспособность нового backend. Пул токенов ниже остаётся только исследованным и не используется.

Цель — один штатный `VirtualDevice` на активный сеанс рабочего стола: parent, обе плитки и fullscreen-child принадлежат одному устройству и его группе. Сохраняются DriveDeck, публичный component SDK, передача Surface, текущий DPI, IME и исправления Back/rotary. Способ законного получения VirtualDevice проверяется отдельным исследованием; синтетическая CDM-привязка, изменение чужих политик и отключение keyguard в этот план не входят.

## Что установлено по исходникам оригинала

Изучен snapshot `8ab72c8846e7777ac355ae6260ab1ce78cba0af4`: `VdmHostMain`, `VdmDesktop`, `OwnedDisplay`, `DelegatedDesktopRuntime`, `DelegatedInputGateway`, `IVdmHost`, ADR0033. По сообщению основного агента эти механизмы не менялись в актуальном upstream4.1.1.

- `OwnedDisplay` — небольшой интерфейс `display / resize / release`. `LocalDisplay` оборачивает локальный VirtualDisplay, `GatewayDisplay` — ID дисплея с операциями через app gateway. Это подходящий минимальный seam: наш `DexUserService` уже использует почти только эти три операции.
- `VdmHostMain` работает под настоящим shell UID, держит один `device` и карту созданных им VirtualDisplay. Передаёт Binder приложению, проверяет UID клиента, наблюдает смерть app-lifetime Binder.
- `newDevice()` задаёт `LOCK_STATE_ALWAYS_UNLOCKED`; `VdmDesktop.hostFlags()` снимает OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED из запроса дисплея и добавляет DEVICE_DISPLAY_GROUP. Реальную группу оригинал читает отдельно.
- **Не переносим буквально:** оригинал создаёт shell CDM association через синтетический restored-payload/associate. Это не штатный пользовательский association.
- Оригинал пытается VDM отдельно для каждого дисплея и при ошибке создаёт обычный VD. Для нашего требования это недостаточно: так parent может быть в VirtualDevice, а child — в группе телефона. Нужен единый выбор backend на весь сеанс.
- Shell-host оригинала погибает при некоторых USB/adbd переключениях. ADR0033 описывает повторный старт через wireless ADB и отдельный Samsung `tcpip:5555` путь. Это не доказательство независимости от Wi-Fi/ADB и не эквивалент нашему сохранению активации при переподключении USB. Автоматически открывать TCP5555 или вводить manufacturer exception в новой реализации не предлагается.

Источники: [OwnedDisplay](https://github.com/YegorkaPomidorka/headswap/blob/8ab72c8846e7777ac355ae6260ab1ce78cba0af4/android/app/src/main/java/com/yegorkapomidorka/headswap/dex/OwnedDisplay.kt), [VdmHostMain](https://github.com/YegorkaPomidorka/headswap/blob/8ab72c8846e7777ac355ae6260ab1ce78cba0af4/android/app/src/main/java/com/yegorkapomidorka/headswap/dex/VdmHostMain.kt), [ADR0033](https://github.com/YegorkaPomidorka/headswap/blob/8ab72c8846e7777ac355ae6260ab1ce78cba0af4/docs/adr/0033-android17-vdm-shell-host.md).

## Выбор архитектуры

Сохраняем `DexUserService` как единственный движок сеанса, children, input и teardown. Добавляем backend создания дисплеев, не второй лаунчер и не второй registry.

```text
CarLife / codec Surface                  DriveDeck / component Surface
          │                                         │
          └──────── DexUserService Session ──────────┘
                    parent + ChildSession tokens
                    input protocol17 / DPI / limits
                              │
                        OwnedDisplay family
                  ┌───────────┴───────────┐
             LOCAL backend           VDM backend
           local VirtualDisplay     one VirtualDevice
                                    parent + all children
```

Рекомендуемая минимальная форма:

1. `OwnedDisplay` предоставляет `display`, `resize`, подтверждённый `release`, metadata семейства и **native producer lifetime**. Метаданные содержат backend, generation/family token, owner UID, native device ID и связь с host instance; сами Android display ID не являются полномочием.
2. `DesktopRuntimeAccess.createDisplay(...)` принимает lifetime/session сведения, создаёт parent и неизменяемое семейство. `createChildDisplay(parent: OwnedDisplay, ...)` берёт backend **из parent**, не пытается выбрать его заново. Это небольшое изменение двух точек создания; factory не прячет изменяемое глобальное «текущее устройство» в переиспользуемом RuntimeAccess.
3. `Session.display` и `ChildSession.display` меняют тип VirtualDisplay → OwnedDisplay. Проверки геометрии, доверенного флага, лимитов пикселей, displayId>0, отсутствие совпадения parent/child остаются.
4. Для LOCAL backend используется тонкий wrapper над существующим VirtualDisplay; его поведение на Android13–16 не меняется. Для VDM backend parent, две плитки и дополнительный fullscreen-child создаются одним family/device. В текущих пределах это максимум parent + 2 tiles + 1 fullscreen.
5. Отказ создания child в VDM — ошибка этого запроса с корректным rollback, **не** неявное создание child вне VDM. Для перехода на совместимый LOCAL режим закрывается весь сеанс, создаётся новое поколение и явно меняется capability независимой блокировки.

Профили Android Desktop, DriveDeck и компоненты Android Desktop используют одну эту политику. Samsung DeX остаётся штатным vendor profile: перенос его специальных eligibility/windowing требований в VirtualDevice не предполагается без отдельного доказательства. VDM не выбирается только по строке производителя; необходим рабочий factory и проверенная capability конкретного сеанса.

## Получение VirtualDevice — отдельный обязательный gate

`VirtualDeviceFactory` должен возвращать устройство с подтверждёнными полномочиями. Он изолирует результат текущего исследования: допустимый no-association API, настоящий пользовательский CDM-процесс либо штатная OEM/system интеграция. К моменту обновления плана отдельный shell-прототип на API37 прошёл штатное создание association через `cmd companiondevice associate` без restored payload и создание одного VDM display: настоящий PIN/keyguard, телефон OFF, VD в группе1 ON, продолжающиеся frames/PTS/CRC и касание. Это доказательство механизма независимости одного дисплея, **не** завершённая интеграция parent/children, перенос владения в app или устойчивость после смерти shell. Точные runtime evidence и параметры прототипа сохраняет основной агент.

Если устройство может принадлежать самому активированному app process, интерфейс OwnedDisplay всё равно нужен, но shell-host и лишний IPC не нужны. Если законный путь требует shell UID, используем отдельный native host с ограниченным Binder контрактом. `CREATE_VIRTUAL_DEVICE` само по себе не считается доказательством допустимой attribution/association/роли.

У каждого результата factory фиксируются реальный UID, Android user, policy, deviceId и способ получения. Сопоставлять app user с `Process.myUid()/100000` в shell-host нельзя автоматически: shell обычно UID2000, а приложение может быть в другом профиле. Неподдержанный профиль должен получать явный отказ, а не запуск в основном пользователе.

## Вариант без постоянного shell-host: границы передачи capability

**Статус: SOURCE-CHECKED, NOT IMPLEMENTED, runtime делегирования ожидается.** Проверены точные исходники `android17-release`, frameworks/base commit `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`. Это отличается от доказательства поведения конкретного OEM и от ABI стабильного публичного Android SDK.

Передать приложению только `IVirtualDevice` и app-owned lifetime Binder недостаточно для последующего создания дисплеев. `VirtualDeviceManagerService.createVirtualDevice` проверяет `AttributionSource.enforceCallingUid()` и принадлежность package вызывающему UID; `VirtualDeviceImpl.mOwnerUid` берётся из этой attribution. Lifetime token определяет срок жизни, но не владельца. `VirtualDeviceImpl.createVirtualDisplay()` вызывает `checkCallerIsDeviceOwner()`: допускается owner UID либо SYSTEM_UID1000, permission override в этой проверке отсутствует. Временные CREATE_VIRTUAL_DEVICE/ADD_TRUSTED_DISPLAY/ADD_ALWAYS_UNLOCKED_DISPLAY не превращают UID приложения в shell. Обычный `IDisplayManager.createVirtualDisplay` не предоставляет альтернативный attach: передаёт `virtualDevice=null` в private internal factory. [VDM service](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/companion/java/com/android/server/companion/virtual/VirtualDeviceManagerService.java), [device owner checks](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/companion/java/com/android/server/companion/virtual/VirtualDeviceImpl.java).

Более узкий вариант по этому исходнику осуществим: shell **заранее** создаёт ограниченный набор VD с `IVirtualDisplayCallback` Binder, принадлежащими app/producer, и device с app-owned lifetime. Затем app меняет размер/Surface или освобождает уже существующий VD через `IDisplayManager`, предъявляя точный callback token. В этой версии `resizeVirtualDisplay`, `setVirtualDisplaySurface`, `releaseVirtualDisplay` очищают calling identity и обращаются к `VirtualDisplayAdapter` по callback Binder; дополнительных UID-проверок на этих трёх путях нет. DMS `linkToDeath` привязан к переданному callback, а не PID вызвавшей shell-команды. `IVirtualDevice.close()` также не вызывает owner gate в просмотренной версии. Эти исключения нельзя ошибочно обобщать на остальные методы device. [DMS](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/display/DisplayManagerService.java), [VirtualDisplayAdapter](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/display/VirtualDisplayAdapter.java).

Это пока кандидат на **пул нативных slot**, а не готовая замена create/release. Для нашего максимума нужны parent + две плитки + fullscreen, а lease поколения приложения остаются отдельными от постоянных slot. Важные блокеры перед выбором такого backend:

- Передавать lifetime/callback токены должен сам остающийся процесс; callback, созданный shell и только пересланный app, всё равно умрёт вместе с shell. Нужен отдельный test-only bridge, без production provider и без передачи callable device Binder сторонним компонентам.
- Вызов `releaseVirtualDisplay` безвозвратно уничтожает slot; app UID не сможет его восстановить после выхода shell. Значит обычный session/child close должен парковать slot, а «Завершить доступ» — действительно уничтожать family. Это изменение контракта, которое нельзя скрывать внутри прежнего `release()`.
- `setSurface(null)` делает VD STATE_OFF, но не уничтожает его задачи. Наш current close использует `DESTROY_CONTENT_ON_REMOVAL`, поэтому без явного подтверждённого удаления только задач этого slot старое приложение/его звук/IME смогут пережить lease и попасть следующему компоненту. Сейчас отдельного разрешения REMOVE_TASKS в delegated activation нет; добавлять его без обоснования и тестов не следует.
- `setSurface(null)` возвращается после изменения server-side ссылки и постановки traversal; это **не** доказанный fence полного native отключения producer от прежней Surface. ACK disposal, отсутствие старых кадров после смены consumer и timeout/death должны быть отдельно доказаны. Обычное ожидание фиксированной задержки не заменяет этот контракт.
- IME/device policies и создание новых virtual input objects остаются owner-gated; неизменяемые параметры нужно установить до выхода shell, ввод/IME затем оставлять нашему существующему подтверждённому gateway. Нужны правильный actual owner UID и membership proof.
- `IDisplayManager.aidl` всё ещё описывает эти операции как same-UID, несмотря на рассмотренную реализацию. В другой просмотренной ревизии VDA аргумент ownerUid у release используется для учёта квоты, а не для проверки доступа; одно наличие этого аргумента не доказывает закрытие пути. Тем не менее успешный API37 probe не даёт обещания универсального межпроцессного API; capability следует определять по реальной платформе, не по одному SDK_INT.

Минимальный эксперимент: один app-owned device token и callback → transient shell create → shell действительно завершён → app attach Surface/resize → новые кадры/касания при PIN lock → detach → attach другой Surface с новым изображением → release → подтверждённое исчезновение display/device после app death. Затем отдельно четыре slot и повторные lease/задачи/IME. Отрицательный вызов create из app UID должен подтвердить owner gate. Пока эти этапы не пройдены, выбор между host и pool остаётся открытым; автоматическое включение TCP5555 не требуется ни для эксперимента, ни как скрытый fallback.

## Shell Binder и поверхности

Для shell-варианта сохраняем цепочку `:delegated DexUserService → same-UID DelegatedInputGateway → authenticated VDM host`. Host Binder не передаётся компонентам. Shizuku backend может создавать VirtualDevice в собственном shell-процессе после того же factory gate; тогда отдельный host не нужен.

Предлагаемый private контракт — типизированная family capability:

- authenticated `openFamily(producerLifetime, sessionLifetime, generation, desktopMode)`;
- `createParent(family, displayLease, Surface, validatedSpec)`;
- `createChild(family, parentLease, childLease, Surface, validatedSpec)`;
- `resize(family, displayLease, validatedSpec)`;
- `release(family, displayLease)` и `closeFamily(family)` с подтверждением завершения native release;
- metadata и пустой native-producer lifetime Binder.

Это эскиз контрактов, не готовые AIDL методы. Изменения private AIDL требуют следующей версии протокола и startup gate; component SDK v1 и уже проверенный смысл `routeHostBack` protocol17 сохраняются.

Host проверяет ожидаемый app UID/user, текущую activation/session capability и конкретные lease tokens. Handoff должен быть связан с текущим одноразовым activation challenge, чтобы старый host не подменил новое поколение. Один только displayId, display name, TRUSTED или «ownerUid=2000» не разрешает операцию на произвольном shell-дисплее.

Флаги и диапазоны задаются нашей политикой; произвольные flags/name/размеры из внешнего компонента не пробрасываются в host. PUBLIC/TRUSTED, геометрия и доступность Display в app process проверяются до регистрации. Все parent/children должны иметь один подтверждённый deviceId и ненулевую device display group; одних запрошенных флагов недостаточно.

Surface передаётся стандартным Binder parcel без копирования видеопотока. У каждого процесса есть собственная полученная Surface-ссылка. Host хранит её рядом с native VirtualDisplay и освобождает **после** VirtualDisplay.release; gateway освобождает только свою промежуточную parcel-ссылку, не Surface владельца. Ошибка/потеря ответа после create должна закрывать именно созданный lease, даже если displayId не успел дойти клиенту. Нужен fault-test на эту границу.

## Самая важная граница: producer lifetime и ACK

Сейчас `terminateStuckService()` и `childProducerLifetime()` опираются на факт: уничтожение процесса DexUserService уничтожает и его native VirtualDisplay. При удалённом VDM host это предположение неверно. Убийство `:delegated` не доказывает, что отдельный host уже перестал писать в Surface.

Поэтому простой порт GatewayDisplay **недостаточен**. Нужны одновременно:

1. Host связывает family с Binder самого producer-процесса и app/session lifetime. Только Session.token недостаточно: он может принадлежать ещё живому главному процессу после гибели producer.
2. Нормальный `releaseChild/stop` возвращает ACK только после remote native release. Сохраняется порядок: запрет новых операций → cancel input → revoke input/IME lease → native VD release → Surface release → unlink.
3. Компоненту через `childProducerLifetime()` возвращается пустой Binder **процесса, реально владеющего native VD**, а не callable VDM interface и не Binder уже промежуточного producer. LOCAL backend продолжает возвращать существующий producer lifetime.
4. Если промежуточный producer умер или завис с неподтверждённым release, host должен гарантированно закрыть связанную family. Для аварийного пути нужен отдельно проверенный механизм завершения самого native host при невозможности ACK, чтобы его lifetime death действительно разрешал disposal Surface у consumer. Обычный death callback, поставленный в очередь за зависшим create/release, такой гарантии не даёт.
5. При одном native host допускается только одно активное семейство сеанса. Нормальное закрытие может оставить host для следующего подключения; поздняя смерть старого producer после unlink не должна завершать новый сеанс. Если для надёжной гарантии нужен отдельный host на family, это будет отдельное решение о запуске/восстановлении, а не скрытая деталь wrapper.

Этот gate блокирует production-интеграцию до fault-injection тестов. Нельзя заменить ACK задержкой, polling «похоже уже закрылось» или освобождать Surface после timeout без доказанного удаления producer.

## Registry, IME, input и геометрия

`EmbeddedSessionRegistry` остаётся единственным публичным арбитром: component UID, opaque handle, parent owner, token и close future сохраняются. DriveDeck не получает Android display ID как capability или доступ к host. Внутри DexUserService остаются текущие children, hardwareTarget/token pinning, double-Back и task-revision проверки.

`DelegatedInputGateway` регистрирует VDM display только по подтверждённому family/lease, а не по общему TRUSTED. Существующие проверки координат, числа контактов, action/source и generation сохраняются. Ввод остаётся текущим INJECT_EVENTS путём на зарегистрированный displayId; переход на VirtualTouchscreen/VirtualKeyboard не нужен для первого порта и не маскирует непроверенную совместимость.

Есть конкретное несовпадение IME ownership: `ShellDisplayIme.enable/isVisible` сейчас проверяет expected owner UID приложения. Shell-owned VDM display имеет другой owner. Нужно передавать фактический owner **из живой family capability**, предварительно проверив membership/token/deviceId, либо выполнять эти две ограниченные операции в native host. Нельзя просто разрешить любой UID2000 или все экраны с именем HeadSwap. Регистрируем LOCAL до запуска Activity; retry и revoke работают на lease identity. Клавиатура, её Back и callback17 затем проходят существующие проверки без второй системы ввода.

`resizeChildDisplay` сохраняет текущий порядок: cancel gesture → native resize того же lease → проверка результата → обновление геометрии/input registry. ID/device/family не меняются. Публичный renderConfig и динамический DPI не меняются; коррекция буфера карты остаётся прежним общим механизмом. Полноэкранный child и обе плитки обязаны оставаться в VDM семье при перестановке/resize и возврате Home.

`DesktopDisplayIsolation` и wake lock должны читать **фактические** display group/flags, сверять family device membership и доказанное lock-state поведение. Не подставляем `independent=true` только потому, что factory вернул VirtualDevice или был вызван setLockState. Если конкретный VDM framework выражает unlock capability иначе, её новая интерпретация вводится только после runtime доказательства. Wake lock не должен удерживать группу телефона0.

## Запуск Activity и Settings

Переиспользуем текущие explicit/exported/launcher проверки, защиту от переноса телефонной задачи и `isActivityStartAllowedOnDisplay`. Перед переносом на shell-owned display отдельно проверяется background launch: ADR оригинала указывает START_ACTIVITIES_FROM_BACKGROUND. Не добавляем широкий shell `start-any-component` API как обход. Если штатному app launch действительно нужно дополнительное временное право, оно оформляется как отдельная capability активации с понятным объяснением.

`android:canDisplayOnRemoteDevices=false` — отдельный opt-out приложения. В проверенном AOSP GenericWindowPolicyController его отсутствие в ActivityInfo блокирует remote Activity **до** default activity policy/exemptions; добавление activity exemption не является обходом этого ограничения. [Проверка AOSP](https://android.googlesource.com/platform/frameworks/base/+/1ed460e656efeceb4f6203316a36c8667da6aef0/services/companion/java/com/android/server/companion/virtual/GenericWindowPolicyController.java), [контракт manifest, default=true](https://developer.android.com/guide/topics/manifest/activity-element#canDisplayOnRemoteDevices).

Оригинал это не решает: VdmHostMain не задаёт override этой политики, а ADR0033 описывает Settings как предположение, ещё не проверенное автором. Нельзя делать вывод, что все Settings запрещены: в просмотренном AOSP Settings main такого opt-out в manifest нет, а OEM и вложенные Activity могут отличаться. [AOSP Settings manifest](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/AndroidManifest.xml).

Для нашего UI нужны отдельные исходы `LAUNCHED`, `REMOTE_DISPLAY_DISALLOWED`, `BACKGROUND_START_DENIED`, `SESSION_LOST`. Проверяются реальный ActivityInfo и framework отказ, включая переходы во внутренние Activity после успешного запуска Settings. Запрещённое окно не открывается тайно на телефоне и не получает child вне VDM. Пользователю предлагается открыть его на телефоне явным действием либо переключить **весь** сеанс в совместимый LOCAL режим с предупреждением о потере независимой блокировки. Изменять Settings APK, app flag или системный policy controller не предлагается.

## Смерть host и восстановление соединения

Событие host death немедленно инвалидирует family, input/IME registrations, child handles и capability независимой блокировки. Callback старого host проверяет host instance + generation и не влияет на замену. Producer и consumer получают корректное завершение через описанный выше native lifetime/ACK; затем существующий connector recovery может создать новое поколение.

Восстановление возможно только после получения нового законного host/factory capability. Если USB/adbd restart убивает единственный shell-host и запустить его без Wi-Fi больше нечем, это **нерешённое ограничение** выбранного размещения. Его нельзя скрывать обычным fallback VD, сохраняющим надпись «независимая блокировка». Проверка реального USB переключения обязательна, даже если обычный stop/start и emulator `kill` проходят.

Media, навигация и component lifecycle тоже требуют повторной проверки: VirtualDevice меняет device context, а политики audio/permissions/IME/recents могут отличаться от обычного VD. В первом порте не создаём VirtualAudioDevice и не заменяем наш зарегистрированный playback mix. Но сохранность USB-звука, media metadata, геопозиции навигатора и переключения приложений доказывается тестами; одно изображение на заблокированном телефоне этого не доказывает.

## Минимальные места изменения — после proof gate

| Файл/зона | Планируемое изменение | Статус |
|---|---|---|
| Новый OwnedDisplay / DisplayFamily | LOCAL wrapper, VDM proxy, backend identity и native lifetime | NOT IMPLEMENTED |
| DesktopRuntimeAccess | Возврат OwnedDisplay; child factory получает parent family | NOT IMPLEMENTED |
| DexUserService | Типы Session/ChildSession, family validation, native lifetime/ACK; input17 сохраняется | NOT IMPLEMENTED |
| DelegatedDesktopRuntime / RuntimeAccess | Выбор parent backend; все children только из его family | NOT IMPLEMENTED |
| DelegatedInputGateway + private AIDL | Узкий family broker, token-bound registration и IME ownership | NOT IMPLEMENTED |
| Native host + authenticated handoff | Только proven factory; native process/lifetime и fault handling | NOT IMPLEMENTED |
| DesktopDisplayIsolation / connector recovery | Реальный capability status; atomic retire/recreate family | NOT IMPLEMENTED |
| EmbeddedSessionRegistry | Сохранить SDK/handles; native producer lifetime для existing ACK contract | NOT IMPLEMENTED |
| UI/status/activation | Явная поддержка/отказ VDM и дополнительные права только при доказанной необходимости | NOT IMPLEMENTED |

Не требуется переписывать CarLife video transport, композитор DriveDeck, управление DPI или semantic navigation. Если runtime доказательства потребуют новых audio/launch политик, они оформляются отдельно, а не добавляются в этот порт скрытно.

## Порядок работ и критерии принятия

1. **Factory proof:** настоящий допустимый вызов на целевом Android17, без fabricated association. Сохранить сигнатуру API, caller identity, разрешения и policy; различать публичный API и разрешённый системный API.
2. **Минимальный family prototype:** parent + два child + fullscreen в одном device/group; реальный PIN keyguard и внутренняя панель OFF, все выходные дисплеи ON с продолжающимися кадрами. Повтор с новым child уже после блокировки.
3. **OwnedDisplay LOCAL refactor:** эквивалентность существующей матрице13–17 до включения VDM. Отдельный commit/этап, чтобы не смешивать изменение типа с новой системной политикой.
4. **VDM lease/ACK:** child resize/release, owner death, producer kill, host kill, late create reply, timeout release, одновременный USB detach/create, старый callback после нового generation. Ни одного orphan VD/Surface или действия на display0.
5. **Functional profiles:** Android Desktop и DriveDeck, две плитки + fullscreen + Home, тачи/IME/Back/rotary, profile component Binder, DPI; тест разрешённого приложения и opt-out fixture. Settings проверяется как конкретная Activity, включая переходы в её разделы.
6. **Media/recovery:** USB/TCP6, playback mix и A/V timestamps, музыка/pause/resume, HUD metadata, навигация/location, реальный USB/adbd restart. Сохранённая активация не считается сохранённым VDM host без наблюдения.
7. **Регрессия:** готовая input17 матрица остаётся базой; новая матрица фиксирует LOCAL/VDM отдельно и native backend hash. Прошивка без capability получает понятный статус, а не ложный PASS независимости.

До выполнения этих gates код не публикуется как завершённое решение независимой блокировки Android17. Строки NOT IMPLEMENTED выше фиксируют исходный план, а актуальное состояние переноса перечислено ниже.

## Состояние переноса после checkpoint

- Реализованы `OwnedDesktopDisplay`, LOCAL wrapper и `VdmDisplayFamily`: выбор один раз на parent, children используют его family, group0/mixed backend не допускаются. Для Android13–16 остаётся LOCAL, для API37+ Android Desktop/DriveDeck требуется VDM, Samsung DeX сохраняет отдельный backend.
- `DelegatedInputGateway.openVdmSession` открывает capability только текущему parent token до регистрации экрана. До первой передачи Surface connector получает пустой Binder настоящего native host. Ввод/IME на UID2000 разрешается только для exact authenticated session и её display membership; проверяется реальный owner/group. Стабильные имена IME не меняются.
- `DexUserService` сохраняет существующие Back/rotary/геометрию/ограничения children, возвращает native lifetime дочерним клиентам. Shizuku открывает VDM внутри собственного shell-процесса. Новый private protocol18 защищает gateway ABI; смысл Back17 не менялся.
- `EmbeddedSessionRegistry.close` больше не принимает смерть промежуточного сервиса как разрешение освободить consumer. Connector получает native-host death независимо от service death; `AaBridge` продолжает удерживать encoder до настоящего ACK. Добавлены unit сценарии для промежуточной смерти и обычного release с живым reusable host.
- Host выполняет native release до Surface disposal. Неизвестный исход create или неудавшийся native release приводит к завершению реального host. Producer death запускает watchdog вне native lock; тем самым зависший Binder не выдаёт ложный ACK. Эти fault paths требуют runtime проверки.
- `VdmHostProvider` принимает bootstrap только от Shell. Живой активированный main-процесс выдаёт одноразовый nonce на 30 секунд; handoff сверяет nonce, main PID, реальный Binder PID хоста, версию сборки и протокол. Новый host не заменяет живой. Завершение активации или отмена конкретного запроса аннулирует challenge; старый death callback сравнивает Binder владельца и не очищает следующий host.
- `IVdmDesktopHost` выдаёт отдельный `IVdmDesktopSession` только одному producer. Внешним компонентам возвращается пустой death-only Binder, а не вызываемый session capability. Проверки UID и текущего session выполняются и в native host. Для host/main/producer смерти и явного shutdown предусмотрено ограниченное завершение с независимым двухсекундным watchdog: зависание native close не удерживает владельца бесконечно.
- CDM bootstrap использует собственный MAC `02:48:53:44:45:53`, отличный от оригинального HeadSwap, точное совпадение Shell/profile/ID и процессный файловый lock. Команда выбирается не по справке или нестабильному имени сгенерированного aconfig-класса, а по безопасной пробе parser: обе ветви отвергают заведомо неверный MAC до создания association. Требуются ненулевой exit, точное исключение MacAddress и неизменный fingerprint списка associations. Слепое чередование синтаксисов, синтетический backup, роль другого приложения и TCP5555 не используются. Стабильные имена отдельных дисплеев сохранены для IME; имя VirtualDevice — `headswap-desk`.
- Recovery использует отменяемый Request до создания parent и собственное сохранённое ADB-сопряжение; TCP5555 не включается. Подтверждённая история изоляции хранится отдельно от сбрасываемого UI-статуса и используется при восстановлении под настоящим keyguard.

Проверка production main40 на API37 прошла через настоящий authenticated host/bootstrap и `DelegatedDesktopRuntime`: parent и child имеют одну ненулевую группу, PIN/keyguard остаётся заблокированным, физическая панель OFF, изображение меняется и касания принимаются; второй child создан уже под PIN, resize/release подтверждены без оставшихся дисплеев. Первое испытание main40 запускалось без обязательного для активированного процесса флага `--no-hidden-api-checks`: framework отказал в конструкторе DisplayInfo, и строгая проверка группы корректно остановила создание. Исправлен запуск QA; production ManualAccessPolicy уже передавал флаг. Проверку владельца или группы не ослабляли. Доказательства находятся в workspace `artifacts/runtime-qa/0.6.0-vdm`, точный успешно пройденный запуск фиксирует общий отчёт основного агента.

LOCAL после refactor проверяется отдельно на зафиксированном main38: API33–36 прошли hardware input, реальный Android Desktop и PIN-isolation (phone OFF, projection ON, group1). Полноэкранный Settings в DriveDeck на API33–36 обнаружил пустой кадр во время загрузки/перехода; сохранены неуспешные результаты и изображения до teardown, причина исследуется отдельно. Это не объявлено пройденным и не скрывается более мягким pixel assertion.

Не заявлены завершёнными: все профили parent+две плитки при PIN lock, IME/Back после блокировки, producer/host crash с проверкой Surface ACK, реальные USB/adbd переходы и полноэкранный профиль в регрессии LOCAL13–16. На main42 API37 прошёл RegisteredPlaybackMix от отдельного test-UID приложения именно на VDM child: media/navigation/assistant дали узнаваемый синтетический тон, alarm negative control — нули; физическая панель оставалась OFF во всех измерениях (QA43). QA44 подтвердил LOCAL IME по WM и Insets при phone OFF, ввод буквы и скрытие одиночным Back без закрытия поля. Перед визуальным подтверждением screenshot добавлен отдельный compositor-frame gate: ранний снимок после изменения Insets мог ещё содержать кадр до анимации клавиатуры. Эти сценарии не подтверждают поведение произвольной OEM-клавиатуры или слышимость на настоящей магнитоле. Unit-mocks с пустым asBinder заменены честными живыми Binder-fakes; production non-null native-lifetime contract сохранён.
