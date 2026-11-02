# Сборка HeadSwap Desktop

Для сборки APK нужен JDK 17 и Android SDK с платформой API 37 и Build Tools 36.0.0. Укажите путь к JDK в `JAVA_HOME`, к SDK — в `ANDROID_HOME`. При первом запуске Gradle скачает зависимости. В репозитории есть Gradle Wrapper 9.8.0.

Из корня проекта на Linux или macOS выполните

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

На Windows команды те же, вместо `./gradlew` используйте `.\gradlew.bat`. Установочный файл будет в `android/app/build/outputs/apk/debug/app-debug.apk`. Для проверки подписи выполните `apksigner verify --print-certs app-debug.apk` из каталога с APK. Утилита `apksigner` входит в Android Build Tools.

APK для отладки Gradle подписывает локальным ключом. Android разрешает обновление уже установленного пакета только при совместимой подписи. Сохраните свой ключ для последующих обновлений.

## Дополнительный профиль DriveDeck

Готовые APK для установки на телефон и порядок активации приведены в [инструкции](INSTALL.md).

Для профиля Android Desktop достаточно APK HeadSwap. Профиль DriveDeck использует отдельный пакет `ru.drivedeck.headswap`. Он получает доступ к службе вывода второго экрана по разрешению уровня `signature`. Если собираете эту пару самостоятельно, подпишите оба APK одним ключом. Companion с вашей локальной подписью не сможет подключиться к HeadSwap, подписанному другим разработчиком.

Патч рассчитан на оригинальный DriveDeck 0.6.7, **code258**. Скачайте APK из релиза автора. Скрипт проверит SHA-256 входного файла, затем соберёт адаптированный **code263**. Для этого нужны Python 3, JDK 17, SDK с каталогами `platforms/android-37.0` и `build-tools/36.0.0`, Apktool 3.0.3 и тот же стандартный debug keystore, которым вы подписали HeadSwap. Путь назначения `--work` должен вести в отдельный каталог результатов. [Оригинальный релиз DriveDeck](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/stable-code258) · [Команда сборки, параметры и контрольные суммы](tools/drivedeck-companion/README.md#пересборка)

## Состав

Основа — HeadSwap в ревизии `77dc69c`. Исходники Android-приложения распространяются под GPL-3.0. Лицензии библиотек scrcpy, Kadb и Shizuku находятся в `android/app/src/main/assets/dex/`, лицензии шрифтов Golos Text и JetBrains Mono — в `android/app/src/main/assets/licenses/`. Права на оригинальное приложение DriveDeck остаются у его правообладателя. [Лицензия HeadSwap](LICENSE) · [Исходная ревизия HeadSwap](https://github.com/YegorkaPomidorka/headswap/commit/77dc69c)
