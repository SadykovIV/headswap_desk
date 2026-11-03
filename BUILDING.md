# Сборка HeadSwap Desktop

Для сборки APK нужен JDK 17 и Android SDK с платформой API 37 и Build Tools 36.0.0. Укажите путь к JDK в `JAVA_HOME`, к SDK — в `ANDROID_HOME`. При первом запуске Gradle скачает зависимости. В репозитории есть Gradle Wrapper 9.8.0.

Из корня проекта на Linux или macOS выполните

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

На Windows команды те же, вместо `./gradlew` используйте `.\gradlew.bat`. Установочный файл будет в `android/app/build/outputs/apk/debug/app-debug.apk`. Для проверки подписи выполните `apksigner verify --print-certs app-debug.apk` из каталога с APK. Утилита `apksigner` входит в Android Build Tools.

APK для отладки Gradle подписывает локальным ключом. Android разрешает обновление уже установленного пакета только при совместимой подписи. Сохраните свой ключ для последующих обновлений.

Для проверки USB-звука и панели на отдельном эмуляторе Android 13–17 соберите в каталоге `android` также `./gradlew :app:assembleDebugAndroidTest`. Установите обычный и тестовый APK на этот эмулятор. Замените `emulator-5554` на его идентификатор из `adb devices`.

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument --no-hidden-api-checks -w -e probe audio com.headswap.desk.test/com.headswap.desk.qa.CompatibilityProbe
python3 ../tools/qa/panel_probe.py --serial emulator-5554
```

`probe audio` проверяет музыкальный и навигационный тон от другого UID и исключение звука будильника. Скрипт панели измеряет переход внутреннего дисплея ON → OFF → ON. Он также проверяет возврат экрана при потере связи и при отсутствии продления таймера. Код `INSTRUMENTATION_CODE: -1` означает, что проверка прошла. Тестовый APK не входит в сборку для водителя.

## Дополнительный профиль DriveDeck

Готовые APK для установки на телефон и порядок активации приведены в [инструкции](INSTALL.md).

Для профиля Android Desktop достаточно APK HeadSwap. Профиль DriveDeck использует отдельный пакет `ru.drivedeck.headswap`. Он получает доступ к службе вывода второго экрана по разрешению уровня `signature`. Если собираете эту пару самостоятельно, подпишите оба APK одним ключом. Companion с вашей локальной подписью не сможет подключиться к HeadSwap, подписанному другим разработчиком.

Патч рассчитан на оригинальный DriveDeck 0.6.8, **code300**. Скачайте APK из релиза автора. Скрипт проверит SHA-256 входного файла, затем соберёт адаптированный **code304**. Для этого нужны Python 3, JDK 17, SDK с каталогами `platforms/android-37.0` и `build-tools/36.0.0`, Apktool 3.0.3 и тот же стандартный debug keystore, которым вы подписали HeadSwap. Путь назначения `--work` должен вести в отдельный каталог результатов. [Оригинальный релиз DriveDeck](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/stable-code300) · [Команда сборки, параметры и контрольные суммы](tools/drivedeck-companion/README.md#пересборка)

## Обновления компонентов

Каждый поддерживаемый пакет явно указан в `DesktopComponent`. Для DriveDeck опубликуйте файл вида `DriveDeck-HeadSwap-0.6.8-code304.apk` в GitHub Releases репозитория HeadSwap. Он может находиться в обычном выпуске `vX.Y.Z` или в отдельном выпуске `component-drivedeck-304`. Каталог выбирает максимальный совместимый номер сборки независимо от версии HeadSwap, включая опубликованные prerelease. APK должен иметь подпись HeadSwap и соответствующие имени файла versionName и versionCode. GitHub предоставляет размер и digest SHA-256; файлы без них, черновики и сторонние ссылки каталог не показывает.

После установки обоих APK и тестового APK на одноразовый эмулятор запустите `adb -s emulator-5554 shell am instrument -w -e probe components com.headswap.desk.test/com.headswap.desk.qa.CompatibilityProbe`. Эта проверка подтверждает отказ в установке чужого пакета, повторной версии и файла с неверной контрольной суммой. Для сквозной проверки обновите предыдущий DriveDeck из «Компонентов» через системный установщик Android и проверьте сохранённый выбор приложений.

Формулу масштаба окон оба APK компилируют из одного исходника в `tools/drivedeck-companion/shared`. Старый DriveDeck использует свой прежний расчёт. Контракт для нового компонента добавляет опциональную транзакцию 11 и capability 16 в существующий Binder-интерфейс.

## Состав

Основа — HeadSwap в ревизии `77dc69c`. Исходники Android-приложения распространяются под GPL-3.0. Лицензии библиотек scrcpy, Kadb и Shizuku находятся в `android/app/src/main/assets/dex/`, лицензии шрифтов Golos Text и JetBrains Mono — в `android/app/src/main/assets/licenses/`. Права на оригинальное приложение DriveDeck остаются у его правообладателя. [Лицензия HeadSwap](LICENSE) · [Исходная ревизия HeadSwap](https://github.com/YegorkaPomidorka/headswap/commit/77dc69c)
