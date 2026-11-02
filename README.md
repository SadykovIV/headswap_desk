<img src="docs/icon.png" alt="Иконка HeadSwap Desktop" width="88" align="right">

# HeadSwap Desktop

HeadSwap Desktop выводит приложения Android-телефона на штатную магнитолу с **Baidu CarLife по USB**. На ГУ появляется отдельный рабочий стол. Касания с магнитолы передаются телефону, а установленный на телефоне домашний экран сохраняется.

**Версия 0.2.13 experimental · Android 13+ · пакет `com.headswap.desk`**

[Скачать HeadSwap Desktop и DriveDeck · HeadSwap](https://github.com/SadykovIV/headswap_desk/releases/tag/v0.2.13) · [Установка и запуск](INSTALL.md)

| Профиль | Что появляется на ГУ | Что установить на телефон |
| --- | --- | --- |
| Android Desktop | Рабочий стол со списком приложений телефона | HeadSwap Desktop |
| DriveDeck | Автомобильный лаунчер с картой и музыкальным плеером. Совместимые приложения можно расположить рядом | HeadSwap Desktop и адаптированный DriveDeck code263 |
| Samsung DeX | Рабочий стол DeX на совместимом Samsung | HeadSwap Desktop и Shizuku |

При первой активации выбраны передача музыки и подсказок по USB и управление панелью телефона. Снять любую отметку можно в «Дополнительно» перед активацией. Там же задаются разрешение, масштаб интерфейса и целевая частота до 60 кадров/с. Доступные режимы и частота зависят от кодера телефона, приложений и магнитолы.

## На экране головного устройства

Кадры с тестового приёмника CarLife. На первом снимке виден список приложений Android Desktop 0.2.12. Второй показывает два приложения в DriveDeck во время более раннего USB-сеанса 1 ноября 2026 года.

| Домашний экран Android Desktop | Два приложения в DriveDeck |
| --- | --- |
| [![Список приложений на ГУ](docs/screenshots/03-hu-home.png)](docs/screenshots/03-hu-home.png) | [![Тест звука HeadSwap и Яндекс Музыка на ГУ](docs/screenshots/06-hu-drivedeck-two-apps.png)](docs/screenshots/06-hu-drivedeck-two-apps.png) |

[Все скриншоты телефона и ГУ](docs/screenshots)

## Авторы и происхождение

- **YegorkaPomidorka** — автор HeadSwap. Из этого проекта взяты USB-транспорт и поддержка протокола Baidu CarLife. Основа адаптации — ревизия `77dc69c`. [HeadSwap](https://github.com/YegorkaPomidorka/headswap) · [Профиль автора](https://github.com/YegorkaPomidorka)
- **RotkivKiv** — автор автомобильного лаунчера DriveDeck. Для работы на отдельном дисплее HeadSwap подготовлены патчи к DriveDeck 0.6.7 code258. [Страница обновлений DriveDeck](https://github.com/RotkivKiv/DriveDeck-Updates) · [Профиль автора](https://github.com/RotkivKiv)
- **SadykovIV** публикует эту независимую адаптацию HeadSwap Desktop. Она добавляет рабочий стол Android, профиль DriveDeck и вывод на CarLife без запуска Android Auto. [Репозиторий адаптации](https://github.com/SadykovIV/headswap_desk)

## Сборка и лицензии

Исходники HeadSwap Desktop распространяются по GPL-3.0. Изменения для DriveDeck также доступны в виде исходных патчей. Права на исходное приложение и ресурсы DriveDeck принадлежат их правообладателям. [Сборка и сторонние компоненты](BUILDING.md) · [Лицензия](LICENSE)
