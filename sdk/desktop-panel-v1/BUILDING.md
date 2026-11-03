# Сборка и подключение HeadSwap Profile SDK v1

Это отдельная Android-библиотека для профилей **локальной версии HeadSwap 0.5.0-dev**. В AAR входят `ProfileContract`, публичный Java-класс `ProfileSession` и сгенерированный Binder-интерфейс. Библиотека не добавляет в манифест разработчика разрешения, Activity или сервисы. APK профиля подписывает его автор.

## Сборка AAR

Требуются JDK 17 и Android SDK Platform 37. `JAVA_HOME` указывает на JDK, `ANDROID_HOME` — на SDK. Из корня репозитория выполните

```sh
./android/gradlew -p sdk/desktop-panel-v1 assembleRelease testDebugUnitTest
```

Для Windows используйте `android\gradlew.bat`. Результат — `sdk/desktop-panel-v1/build/outputs/aar/headswap-profile-sdk-release.aar`. Для сборки без сети можно добавить `--offline`, если Gradle, Android Gradle Plugin 9.4.1 и зависимости тестов уже загружены. Самостоятельная сборка SDK не требует APK HeadSwap, ключей подписи, телефона или AVD.

**Готовый AAR.** Скопируйте файл из ZIP в `app/libs/` проекта профиля. Если собрали AAR самостоятельно, переименуйте его в `headswap-desktop-profile-sdk-v1-dev.aar` для следующего примера. Добавьте в `app/build.gradle.kts`

```kotlin
dependencies { implementation(files("libs/headswap-desktop-profile-sdk-v1-dev.aar")) }
```

**Модуль Gradle из исходников.** Укажите `com.android.library` версии `9.4.1` в настройках плагинов своего проекта, включите модуль в `settings.gradle.kts` и добавьте зависимость в приложение. Здесь `../headswap_desk` — ваша локальная копия репозитория.

В `settings.gradle.kts`

```kotlin
include(":desktop-panel-sdk")
project(":desktop-panel-sdk").projectDir = file("../headswap_desk/sdk/desktop-panel-v1")
```

В `app/build.gradle.kts`

```kotlin
dependencies { implementation(project(":desktop-panel-sdk")) }
```

Используйте один вариант зависимости. AIDL-заглушки уже находятся в `classes.jar`. Если ранее вы копировали `IDesktopPanelSession.aidl` в приложение, удалите эту копию перед подключением библиотеки. Минимальная версия Android — API 33.

## Получение сеанса

Объявите Activity профиля по [руководству SDK](README.md). В `onCreate` получите текущую сессию. В `onNewIntent` замените её целиком, включая случай автономного запуска.

```java
private ProfileSession session;

@Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    session = ProfileSession.from(getIntent());
}

@Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    session = ProfileSession.from(intent);
}
```

Импортируйте `com.headswap.desk.component.api.ProfileSession`. Публичные именованные константы находятся в `ProfileContract` из того же пакета. После обычного запуска через системный launcher `from` вернёт `null`. Автономный интерфейс и собственные настройки профиля могут продолжать работу.

При нажатии пользователем на ярлык вызовите `session.requestLaunch(componentName)` с точной публичной `MAIN`/`LAUNCHER` Activity либо `session.openCatalog()` для открытия каталога HeadSwap. Перед показом кнопок проверьте `session != null` и `canLaunchApps()` / `canOpenCatalog()`. Результат `true` означает, что хост принял запрос к обработке. `false` возможен после отзыва сессии, при неподходящей цели и при потере Binder. Хост повторно проверяет подписанта, UID, целевой компонент и текущее окно. Не сохраняйте Binder между запусками и не передавайте его другим процессам. Обычный Intent может прийти от любого приложения и не подтверждает личность отправителя.

## Резерв для микрофона и голосового канала

`CAP_MICROPHONE_INPUT` и `CAP_VOICE_CHANNEL` обозначают две отдельные возможные функции. В текущем SDK для них нет команд. `canUseMicrophone()` и `canUseVoiceChannel()` возвращают `false`. `supports(bit)` принимает один реализованный бит. Нулевое значение, объединение нескольких битов, неизвестные и зарезервированные биты дают `false`, даже если отправитель включил их в маску.

Для будущей реализации потребуется отдельное согласие пользователя на конкретную сессию и источник звука. До передачи хост согласует направление потока, формат, владельца микрофона, аудиофокус и приоритет текущей голосовой операции. При блокировке, отключении ГУ или отзыве разрешения хост прекращает захват, закрывает канал и инвалидирует выданный доступ. Содержимое аудио не пишется в отчёты. Навигационные подсказки и метаданные медиаплеера остаются отдельными возможностями. Само объявление бита не включает запись или передачу звука.
