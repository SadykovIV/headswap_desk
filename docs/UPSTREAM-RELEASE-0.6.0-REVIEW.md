# Проверка нового оригинального HeadSwap 4.1.1

Проверено 9 ноября 2026 года через GitHub API. Это read-only аудит: merge, смена версии HeadSwap Desktop и публикация не выполнялись.

**Активация HeadSwap Desktop с телефона по Wi-Fi и ввод шести цифр через уведомление реализованы в 0.6.0.** Отсутствие ниже отдельных внутренних механизмов оригинала не означает отсутствия этой функции. У нас есть собственные `PhonePairingService`, RemoteInput, поиск порта, сопряжение и запуск активированного доступа; исправления оригинала проверяются на применимость к этому пути.

## Точный состав релиза

- Последний релиз: [HeadSwap 4.1.1](https://github.com/YegorkaPomidorka/headswap/releases/tag/v4.1.1), опубликован `2026-11-09T09:53:49Z`.
- Tag `v4.1.1` и проверенный `main` указывают на commit [`43c32c6a00f7bbfe99f5db12a60abbd40484a26b`](https://github.com/YegorkaPomidorka/headswap/commit/43c32c6a00f7bbfe99f5db12a60abbd40484a26b), versionCode 73.
- После предыдущей проверки `5c2b66778a650f1b612846e91f98b31aceb7bd06` изменён **только `android/app/build.gradle.kts`**: версия 4.1.0/code72 → 4.1.1/code73. [Сравнение](https://github.com/YegorkaPomidorka/headswap/compare/5c2b66778a650f1b612846e91f98b31aceb7bd06...43c32c6a00f7bbfe99f5db12a60abbd40484a26b).
- От сохранённой исследованной базы `8ab72c8846e7777ac355ae6260ab1ce78cba0af4` добавлено изменение пяти файлов собственного лаунчера и теста: выбор любого плеера в обоих слотах. Помимо этого — лишь номер версии. [Полное сравнение с нашей исследованной базой](https://github.com/YegorkaPomidorka/headswap/compare/8ab72c8846e7777ac355ae6260ab1ce78cba0af4...43c32c6a00f7bbfe99f5db12a60abbd40484a26b).

Release notes описывают весь переход **от тега 4.1.0**, а не только последние коммиты после нашего snapshot. Поэтому упомянутые там исправления Pixel/Samsung и шторки не являются пропущенными новыми изменениями: они уже присутствовали внутри `8ab72c8`. Это проверено отдельным [сравнением тегов 4.1.0 → 4.1.1](https://github.com/YegorkaPomidorka/headswap/compare/v4.1.0...v4.1.1).

## Применимость к HeadSwap Desktop

| Изменение оригинала | Источник и поведение | Решение для нашей 0.6.0 |
|---|---|---|
| Android 17 Pixel: не блокировать активацию из-за чтения USB debugging как выключенного | [`38f3597`](https://github.com/YegorkaPomidorka/headswap/commit/38f35973e527a05a559502838e2feae7914f6202), `VdmDesktop.usbDebugMissing`: проверка `Settings.Global.ADB_ENABLED` ограничена Samsung. `PhoneActivationBlock` добавляет диагностические поля. | В нашем пути `PhonePairingService` и `DexSetupActivity` нет такого USB-debug gate и нет зависимости от `ADB_ENABLED`. Уведомление сопряжения проверяет собственные разрешения/канал. Ошибку не воспроизводит сама логика; полный self-pair подтверждён на API37. Новая правка не нужна. |
| Samsung: восстановить внешний shell VDM host без Wi-Fi после рестарта adbd | [`1b25f0d`](https://github.com/YegorkaPomidorka/headswap/commit/1b25f0d15483d07a62ead7ad9d08d236ebe7ceb3), [`3a851b3`](https://github.com/YegorkaPomidorka/headswap/commit/3a851b3662ba2ad0a871f4ae87e47ab8432d4c2f). `VdmHostRestart` после успешного Wi-Fi restart запрашивает у adbd `tcpip:5555`, затем использует этот порт на loopback. [`ADR 0033`](https://github.com/YegorkaPomidorka/headswap/blob/43c32c6a00f7bbfe99f5db12a60abbd40484a26b/docs/adr/0033-android17-vdm-shell-host.md) описывает lifetime и ограничения. | У нас нет `VdmHostMain/VdmHostRestart`: основной producer — неэкспортируемый сервис приложения `:delegated`, отдельный backend Shizuku. Восстановление нашего helper реализовано в `BridgeService` и проверяет текущий живой USB/TCP6 output. Этот restart имеет смысл вместе с VDM backend; отдельное копирование `tcpip:5555` изменило бы режим отладки телефона, не создав изоляции. По уточнению пользователя VDM теперь подлежит обязательной адаптации, а способы запуска/восстановления проверяются вместе с ним. До завершения этой работы физическая блокировка Android17 в нашем APK остаётся незакрытым требованием. |
| Положение язычка шторки, обход текста status bar, ширина на широком экране и компактность 1280×480 | [`7115082`](https://github.com/YegorkaPomidorka/headswap/commit/7115082bd02afdce8053efead7bc3dce4441d9cf), [`d419892`](https://github.com/YegorkaPomidorka/headswap/commit/d419892fe3f5024a874d29a1d10fb4ce801b5f45), [`2ea60f9`](https://github.com/YegorkaPomidorka/headswap/commit/2ea60f96e2c27cca8644821edbbb8e3c5769c56b). Изменены Compose-разметка и координаты плиток собственного лаунчера оригинала. | Наша `HuNotificationShade` — отдельное окно на принадлежащем нам дисплее, также для DriveDeck/внешних компонентов, без той сетки плиток. Раскрытая ширина уже ограничена экраном и составляет 60%; исходные `LauncherRoot/LauncherSpec` здесь отсутствуют. Прямой patch неприменим. Совпадение UX на всех внешних лаунчерах и 1280×480 не заявляется. |
| Любой плеер в каждом из двух музыкальных слотов | [`f74ebea`](https://github.com/YegorkaPomidorka/headswap/commit/f74ebea82fe957d4e5cea27219f19d550d22f060): `PlayerChip.Kind.APP`, списки всех плееров, устранение дублирования слотов, обновление выбора и иконки. | В нашей архитектуре нет `PlayerChip` и двух слотов оригинального Compose-лаунчера. Собственный каталог допускает любые опубликованные приложения; `DesktopMusicPlayback` использует выбранную Android локальную MediaSession без списка разрешённых музыкальных пакетов. DriveDeck владеет своей музыкальной карточкой. Изменение не переносится как исправление транспорта или автозапуска. |

## Проверка транспорта, медиа, ввода и блокировки

В diff **8ab72c8 → 4.1.1** отсутствуют изменения `CarLifeSession`, TCP/USB-транспорта, кодера/GL, звукового захвата, временных меток, hardware input и политик независимости дисплея. Никакого нового upstream исправления этих механизмов после изученной базы не найдено.

В diff **4.1.0 → 4.1.1** изменения desktop относятся к проверке активации и перезапуску shell VDM host, описанным выше. Нового универсального API для Алисы, синхронности A/V или независимой блокировки всех Android этот релиз не добавляет. Он также не устраняет ограничение legacy ADB DeX в нашей собственной архитектуре.

Итог сверки delta: новую версию оригинала учли, дополнительных исправлений транспорта/медиа/ввода после исследованной базы нет. Это не означает завершение всех пользовательских требований: VDM-механизм уже присутствует в оригинале, но ещё не перенесён в HeadSwap Desktop. По повторному уточнению пользователя его адаптация и проверка настоящей блокировки Android17 обязательны до готовности 0.6.0; см. продолжение основного плана. Собственные дефекты ввода и жизненного цикла проверяются независимо.

Ответы GitHub API (`tag.json`, `delta.json`, `full.json`, `release-delta.json`) сохранены вне Git в `../artifacts/upstream-review-4.1.1/`. Это позволяет повторить проверку состава релиза, не полагаясь только на его описание.

## Повторная сверка компонентов

В `2026-11-09T10:24:42Z` повторно прочитаны метаданные релизов; APK не скачивались. Версии, размеры и GitHub SHA-256 совпадают с ранее проверенными источниками:

| Компонент | Проверенный релиз | APK / размер / SHA-256 |
|---|---|---|
| DriveDeck | [stable-code331](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/stable-code331), 0.6.9, опубликован 7 ноября 2026 | `DriveDeck-0.6.9-code331.apk`, 838817 байт, `e32ac90ed66d7d325653ed475cd3623d41d7737aeb321813991aedab7b06a003` |
| CarBridge | [v0.52-headswap.2](https://github.com/kukulkanod-source/CarBridge-0.52-HeadSwap-profile/releases/tag/v0.52-headswap.2), опубликован 4 ноября 2026, prerelease | `CarBridge-0.52-HeadSwap-profile-v2-debug.apk`, 4345554 байт, `a7a0181fe236a6507ca060cf11cf7fc276c74bbe5ebc5223e47be0715df2e54b` |

Для CarBridge проверен полный список опубликованных релизов: GitHub `/releases/latest` исключает prerelease и возвращает 404, поэтому он один не подходит для такой сверки. Более нового выпуска в списке нет. Полный commit HeadSwap, время проверки и assets всех трёх проектов записаны в `../artifacts/upstream-review-4.1.1/sources.json`.
