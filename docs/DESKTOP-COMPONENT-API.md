# RFC. Независимые компоненты рабочего стола HeadSwap Desktop

**Статус на 3 ноября 2026 года — контракт предварительной версии HeadSwap Desktop 0.5.0.** В стабильной версии **0.4.1** распознавания таких модулей нет. Цель 0.5.0 — управляемое окно внутри Android Desktop. `API_MAJOR=1` обозначает версию контракта. У адаптера CarBridge собственные `versionName` и `versionCode`. Публикация реестра и декларативные карточки относятся к будущим этапам.

## Граница версии 0.4.1

Автор может выпустить APK под собственным пакетом и ключом. Включённая экспортируемая Activity с `MAIN` и `LAUNCHER` появится в каталоге Android Desktop после установки на телефон. Так можно проверить интерфейс на ГУ до подключения профиля. [Выбор приложений](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/desktop/DesktopAppCatalog.kt#L16-L30) · [Запуск Activity](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/desktop/DesktopAppPane.kt#L130-L150)

В 0.4.1 три встроенных профиля. Основной APK обновляется отдельно, каталог дополнительных APK содержит DriveDeck. Даже для локального файла установщик проверяет пакет и подпись HeadSwap. Закрытый сервис окон сверяет UID DriveDeck и подпись вызывающего при каждом вызове. [Профили](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/dex/DesktopModePolicy.kt#L4-L60) · [Каталог](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/update/ComponentCatalog.kt#L6-L41) · [Проверка APK](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/update/ComponentPackages.kt#L47-L92) · [Авторизация сервиса](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/embed/EmbeddedDisplayService.kt#L12-L32)

## Этап 1. Минимальный хост в проекте 0.5.0

**CarBridge — отдельный профиль в главном списке рядом с Android Desktop, DriveDeck и Samsung DeX.** Другие установленные адаптеры также появляются там как самостоятельные варианты. У CarBridge и сторонних профилей один внутренний движок — Android Desktop от HeadSwap. Его `AndroidDesktopActivity` владеет родительским дисплеем и управляет окнами и вводом. В обычном режиме боковая панель, каталог, «Домой» и «Назад» принадлежат HeadSwap. Профиль с `PROFILE_CHROME=true`, включая адаптер CarBridge, рисует собственные док, домашний экран и меню в **дочерней Activity**. HeadSwap размещает окно приложения поверх неё по состоянию компоновки, которое сообщает профиль. Хост задаёт геометрию, DPI и жизненный цикл окна. CarLife и системный `HOME` телефона остаются под прежним управлением. DriveDeck и Samsung DeX используют свои штатные точки входа. [Корень и навигация Android Desktop](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/desktop/AndroidDesktopActivity.kt#L144-L173) · [Точки входа движков](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/dex/DesktopModePolicy.kt#L40-L60)

Автор объявляет точку входа в манифесте своего APK.

```xml
<activity android:name=".HeadUnitDesktopActivity"
    android:label="CarBridge"
    android:exported="true" android:resizeableActivity="true">
    <intent-filter>
        <action android:name="com.headswap.desk.action.DESKTOP_COMPONENT" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="com.headswap.desk.component.API_MAJOR"
        android:value="1" />
    <meta-data android:name="com.headswap.desk.component.PROFILE"
        android:value="android-desktop" />
    <meta-data android:name="com.headswap.desk.component.MODE_ID"
        android:value="carbridge" />
    <meta-data android:name="com.headswap.desk.component.DASHBOARD"
        android:value="true" />
    <meta-data android:name="com.headswap.desk.component.PROFILE_CHROME"
        android:value="true" />
</activity>
```

Метаданные размещаются на Activity. `PROFILE=android-desktop` указывает поддерживаемый движок. `MODE_ID=carbridge` связывает запись с профилем CarBridge в главном списке. Для другого модуля `MODE_ID` необязателен. Функции и выпуск APK остаются ответственностью автора. Экспортируемую Activity может вызвать любой процесс. Она должна безопасно обработать автономный запуск и не доверять входящим extras с идентификаторами дисплеев. Вход `MAIN`/`LAUNCHER` позволяет запускать APK как обычное приложение в 0.4.1.

**Порядок работы хоста**

1. Хост объявляет `<queries>` для `DESKTOP_COMPONENT` и `DEFAULT`. Для текущего Android-пользователя проверяются `enabled`, `exported`, доступность Activity хосту, `API_MAJOR=1`, `PROFILE=android-desktop`, пакет и полное имя Activity. Общий UID, неизвестный профиль или некорректный `MODE_ID` исключают кандидата. [Видимость пакетов Android](https://developer.android.com/training/package-visibility/declaring)
2. Автор передаёт пользователю APK и отдельно публичный отпечаток сертификата. После установки пользователь открывает **главный выбор профиля**, нажимает CarBridge или профиль другого автора и перед подтверждением сверяет пакет и отпечатки. Для CarBridge без установленного адаптера выбор недоступен. Одноимённые кандидаты показываются отдельно. Хост запоминает полный `ComponentName`, серийный номер Android-пользователя и набор SHA-256 текущих подписантов. Выбор можно сбросить. Новый сертификат требует нового подтверждения. [SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo)
3. Корень создаёт окно через `DesktopAppPane` / `EmbeddedSessionRegistry`, проверяет профиль, поколение сеанса, подписанта, разрешения и принадлежность дисплея. Цель — явный `ComponentName`, дисплей — дочерний ID, отличный от нуля и родительского. Модуль не получает ID родителя. При падении модуля хост освобождает его слот и показывает каталог. «Домой» ведёт туда же, «Назад» действует внутри активного окна. [Путь запуска 0.4.1](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/desktop/DesktopAppPane.kt#L130-L150) · [Правила запуска Android](https://source.android.com/docs/core/display/multi_display/activity-launch)

Проверить регистрацию Activity на тестовом телефоне можно командой

```sh
adb -s "$SERIAL" shell cmd package query-activities --brief --user current \
  -a com.headswap.desk.action.DESKTOP_COMPONENT -c android.intent.category.DEFAULT
```

Команда проверяет фильтр манифеста. Для точки входа допустимы `standard` или `singleTop` с `documentLaunchMode="none"` и `allowTaskReparenting="false"`. Подойдут значения по умолчанию. Так адаптер может открыть отдельную задачу, даже если его приложение запущено на телефоне. Хост задаёт дочерний дисплей через `ActivityOptions.setLaunchDisplayId` либо эквивалент backend и сверяет фактическое размещение. Если экран не совпал, хост оставляет каталог доступным, не перемещая и не закрывая телефонную задачу. [ActivityOptions](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int))

**Запуск приложения из панели.** Хост вкладывает в Intent выбранной панели `Bundle` под ключом `com.headswap.desk.extra.DESKTOP_SESSION` с временным Binder `session`. По нажатию ярлыка панель вызывает `IDesktopPanelSession.requestLaunch(packageName, activityName)` с пакетом и полным именем класса. Хост сверяет UID выбранного издателя, доверие, активное окно и доступность публичной `MAIN`/`LAUNCHER` Activity. Ответ `true` означает принятие запроса к обработке. Произвольные Intent, ввод и идентификаторы дисплеев этот API не принимает. При смене панели прежний Binder отзывается. Автономный запуск Activity также обходится без Binder. [Публичный SDK панели](../sdk/desktop-panel-v1/README.md)

**Авторская оболочка.** Необязательные `DASHBOARD=true` и `PROFILE_CHROME=true` вместе позволяют автору рисовать рабочий стол и док во всей области своей Activity. Поверх неё HeadSwap располагает дочернее окно навигации с собственным вводом и DPI. Вызов `setDashboardLayout` сообщает сторону и видимость музыки, видимость приложения и измеренную высоту нижнего дока. Профиль может раскрыть собственное меню и затем вернуть уже открытую карту. Обычная панель работает с одним окном. [Контракт компоновки](../sdk/desktop-panel-v1/BUILDING.md#собственная-оболочка-и-нижняя-панель)

**Доступ к плееру.** Пользователь отдельно разрешает чтение текущего трека и управление воспроизведением в диалоге выбора конкретной панели. Прежнее доверие к APK само по себе музыку не открывает. При доступном локальном плеере `ProfileSession.getNowPlaying()` возвращает название, исполнителя, состояние и необязательную уменьшенную обложку. Три команды переключают трек назад, вперёд и между воспроизведением и паузой. Каждая операция требует видимого окна панели, действующего сеанса, прежней подписи, UID и согласия. [Использование Media API](../sdk/desktop-panel-v1/BUILDING.md#активный-трек-и-управление-воспроизведением)

## Будущий каталог и доставка обновлений

В локальном тесте 0.5.0 участвуют только уже установленные APK. Для распространения компонентов позже предлагаем подписанный реестр. Автор подаёт пакет, имя Activity, публичный отпечаток сертификата, совместимость, адрес выпуска, лицензию и контакт поддержки. Для каждой сборки реестр фиксирует `versionCode`, минимальный Android, URL, размер и SHA-256 файла. Хост проверяет подпись индекса встроенным публичным ключом, затем сравнивает скачанный APK с записью реестра. Закрытый ключ остаётся у издателя.

Загрузка завершается передачей проверенного файла системному установщику с подтверждением пользователя. Уже установленный APK можно обнаружить и без загрузки. Неизвестная подпись, понижение версии, несовместимый API, несовпадение имени или отзыв издателя оставляют компонент неактивным. Для ротации одиночного ключа нужна новая одобренная запись и подтверждённая Android цепочка подписей от ранее закреплённого сертификата. Для APK с несколькими текущими подписантами проверяется весь набор. При замене или удалении пакета хост отзывает активные разрешения сеанса и заново проверяет запись. [Сведения Android о подписантах и ротации](https://developer.android.com/reference/android/content/pm/SigningInfo)

Пример записи будущего реестра для адаптера CarBridge.

```json
{
  "schemaVersion": 1,
  "kind": "desktop-ui",
  "profile": "android-desktop",
  "modeId": "carbridge",
  "packageName": "com.example.carbridge.headswap",
  "activity": "com.example.carbridge.headswap.HeadUnitDesktopActivity",
  "apiMajor": 1,
  "capabilities": [],
  "signerSha256": "<SHA-256 сертификата издателя>",
  "release": {
    "versionCode": 1,
    "minSdk": 33,
    "apkUrl": "<утверждённый HTTPS-адрес APK>",
    "apkBytes": 123456,
    "apkSha256": "<SHA-256 этого APK>"
  }
}
```

## Этап 2. Действия и карточки на рабочем столе хоста

Дальнейший этап позволит авторам описывать собственные наборы карточек для размещения средствами HeadSwap. В текущей тестовой сборке автор сам рисует док и управляет своей оболочкой, а хост проверяет запросы и размещает окно приложения.

Для кнопки «Открыть навигацию» модуль предлагает текст, действие и цель. HeadSwap размещает кнопку, ждёт нажатия, проверяет доступность цели самому модулю и запускает её в своём слоте. Компоновка и ввод принадлежат хосту. Разрешение ограничено UID издателя, Android-пользователем, профилем и поколением сеанса. Отключение ГУ, смена профиля, смерть Binder или замена APK отзывают его. Сохраняются лимит окон, бюджет пикселей и подтверждённое освобождение поверхностей. [Ограничения геометрии](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/dex/EmbeddedDisplayPolicy.kt#L5-L48) · [Владение окнами](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/java/com/headswap/desk/embed/EmbeddedSessionRegistry.kt#L116-L154)

Декларативные музыкальные карточки смогут опираться на доступ к плееру, уже реализованный для открытой Activity. Новый контракт версионируется независимо от закрытого AIDL DriveDeck. [Действующий интерфейс DriveDeck](https://github.com/SadykovIV/headswap_desk/blob/v0.4.1/android/app/src/main/aidl/com/headswap/desk/embed/IEmbeddedDisplayGateway.aidl#L5-L27)

Для микрофона и голосового канала SDK отдельно резервирует `CAP_MICROPHONE_INPUT` и `CAP_VOICE_CHANNEL`. В 0.5.0 оба недоступны. Сначала предстоит согласовать разрешение пользователя, источник, направление потока, аудиофокус и отзыв при блокировке или отключении. [Резерв возможностей](../sdk/desktop-panel-v1/BUILDING.md#резерв-для-микрофона-и-голосового-канала)

## Условия приёмки по этапам

- **База 0.5.0.** Два APK с одинаковым `MODE_ID=carbridge` и разными ключами доступны для индивидуального выбора. Обновление с прежними сертификатами сохраняет выбор. Сброс доверия, изменённая подпись, несовместимый API и отключённая Activity не могут запустить выбранную панель.
- CarBridge выбирается непосредственно в главном списке профилей. При его запуске поднимается штатный корень Android Desktop, затем интерфейс CarBridge в дочерней области. Выбор обычного Android Desktop, DriveDeck или Samsung DeX не запускает адаптер. Проверяются тёплая Activity на телефоне и исчезновение дочернего окна во время запуска.
- Сессия переживает поворот, изменение DPI ГУ, смерть оболочки, удаление пакета и повторный USB-сеанс. Дисплей телефона сохраняет прежний масштаб и системный `HOME`.
- **Музыкальная панель.** Без галочки доступа, после сворачивания панели или отключения ГУ чтение возвращает пустой результат, а команды плеера отклоняются. Тестовый модуль не может использовать чужой идентификатор окна или устаревший Binder или запускать защищённую Activity через полномочия хоста.
- Диагностика показывает пакет, версию API, этап и причину перехода на встроенный рабочий стол. Музыка, маршрут и содержимое окон в экспортируемый отчёт не попадают.

[Работающий пример для 0.4.1](../examples/component-activity/README.md) · [Пилот CarBridge](CARBRIDGE-PILOT.md)
