# HeadSwap Profile SDK v1 для Android

[Полная инструкция разработчика](DEVELOPER-GUIDE.md) · [Пример собственного рабочего стола](STARTER.md).

Для сборки нужны JDK 17 и Android SDK Platform 37. Для проверки на ГУ — HeadSwap Desktop 0.5.0-dev и телефон с Android 13+. Комплект содержит сессионный SDK в формате AAR, соответствующие исходники с тестами, AIDL, три примера APK в исходном виде и Gradle Wrapper. Стабильная версия HeadSwap 0.4.1 не поддерживает API v1.

Чтобы получить **свой** независимый проект, распакуйте ZIP, откройте терминал в корне и выполните:

```sh
python3 tools/new_profile.py ../RouteBoard --package org.example.routeboard --mode routeboard --name "Мой рабочий стол"
cd ../RouteBoard
./gradlew :app:assembleDebug
```

Для Windows используйте `py -3` и `.\gradlew.bat`. APK — `app/build/outputs/apk/debug/app-debug.apk`. Установите его, остановите проекцию и выберите своё имя в **«Профиль» на главном экране HeadSwap**. Подтвердите издателя и подключите ГУ.

Готовые варианты можно собрать из корня самого SDK без генератора:

| Gradle-задача | Что получится |
| --- | --- |
| `:app:assembleStarterDebug` | Стартовый рабочий стол: полноэкранный лаунчер, собственные каталог и док, реальное окно приложения и музыкальная панель |
| `:app:assembleExampleDebug` | Демо-панель: минимальная Activity, запрос запуска и проверка размеров |
| `:app:assembleCarbridgePreviewDebug` | Музыкальная панель HeadSwap: медиа и согласие пользователя. Имя Gradle-варианта историческое; это самостоятельный пример HeadSwap. |

`libs/headswap-desktop-profile-sdk-v1-dev.aar` можно подключить и к готовому проекту. Сборка одной библиотеки с тестами: `./gradlew -p sdk-source assembleRelease testDebugUnitTest`. Условия распространения см. в `LICENSE`.
