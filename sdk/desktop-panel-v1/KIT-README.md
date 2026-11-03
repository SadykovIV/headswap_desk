# HeadSwap Desktop Profile SDK v1 · локальная сборка

Комплект содержит библиотеку AAR, public AIDL, исходники двух вариантов демонстрационной панели и Gradle Wrapper. Для сборки нужны JDK 17 и Android SDK 37. При первом запуске Gradle загрузит инструменты сборки. Ключ HeadSwap не требуется.

1. Распакуйте ZIP в отдельный каталог. Задайте `JAVA_HOME` и `ANDROID_HOME`. На macOS/Linux из корня распакованного комплекта выполните:

   ```sh
   ./gradlew :app:assembleExampleDebug :app:assembleCarbridgePreviewDebug
   ```

   На Windows используйте `gradlew.bat`. APK появятся в `app/build/outputs/apk/example/debug/` и `app/build/outputs/apk/carbridgePreview/debug/`. Gradle-проект из этого ZIP использует приложенный файл `libs/headswap-desktop-profile-sdk-v1-dev.aar`.
2. Установите HeadSwap Desktop **0.5.0-dev** и APK выбранной панели на тестовый Android 13+. Остановите проекцию, откройте **«Профиль» на главном экране** и выберите «Демо-панель» или «CarBridge» для тестового адаптера. Подтвердите пакет, класс, код версии и SHA-256 **сертификата**. Закрытый ключ остаётся у автора. Тестовый адаптер CarBridge из комплекта написан для проверки именованного профиля; он не является продуктом автора CarBridge Offline.
3. Подключитесь к ГУ. Используйте свою сетку и избранное, вызов для открытия программы и кнопку возвращения в каталог HeadSwap. Проверьте изменение размера и стороны собственной панели, Home, Back, отключение USB, обновление и удаление APK.
4. Для своего режима измените `applicationId`, имя Activity, подпись и подписи элементов интерфейса. В `AndroidManifest.xml` оставьте фильтр `com.headswap.desk.action.DESKTOP_COMPONENT` с `DEFAULT` и метаданные на Activity. `API_MAJOR=1`, `PROFILE=android-desktop`; `MODE_ID` — ваше стабильное имя. У автора CarBridge оно равно `carbridge`.
5. Для подключения к уже существующему проекту скопируйте AAR в его каталог `app/libs/` и добавьте `implementation(files("libs/headswap-desktop-profile-sdk-v1-dev.aar"))` в `dependencies` модуля. Класс `com.headswap.desk.component.api.ProfileSession` возвращает временный клиент через `ProfileSession.from(intent)`. `requestLaunch(ComponentName)` запрашивает обычную доступную Activity из набора MAIN/LAUNCHER, `openCatalog()` открывает штатный каталог. Вызывайте обе операции в рабочем потоке и обрабатывайте `false` и отсутствие клиента. Для запуска приложение должно предлагать экспортируемый вход `standard` или `singleTop` без специального разрешения, нестандартного режима документов и переноса задач. Устройство выдаёт новый дескриптор при новом открытии профиля.
6. `canUseMicrophone()` и `canUseVoiceChannel()` возвращают `false`. Микрофон и голосовой канал зарезервированы отдельно для будущей версии. Разрешения записи и методов передачи звука в SDK v1 нет.

Рабочая область принадлежит Activity автора. Родительский дисплей, системные кнопки, ввод, окна других приложений и сам CarLife остаются у HeadSwap. Этот комплект предназначен для локального тестирования и не публиковался в репозитории пакетов.
