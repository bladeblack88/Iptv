# 📺 IPTV Player — Россия

<p align="center">
  <strong>Лёгкий IPTV-плеер для браузера, Android и телевизоров с Google TV / Android TV</strong><br>
  Каналы, избранное и управление с пульта — в одном интерфейсе.
</p>

<p align="center">
  <a href="https://github.com/bladeblack88/Iptv/actions/workflows/deploy.yml"><img src="https://github.com/bladeblack88/Iptv/actions/workflows/deploy.yml/badge.svg" alt="Deploy IPTV"></a>
  <a href="https://github.com/bladeblack88/Iptv/actions/workflows/website-smoke-test.yml"><img src="https://github.com/bladeblack88/Iptv/actions/workflows/website-smoke-test.yml/badge.svg" alt="Website browser tests"></a>
  <a href="https://github.com/bladeblack88/Iptv/actions/workflows/build-apk.yml"><img src="https://github.com/bladeblack88/Iptv/actions/workflows/build-apk.yml/badge.svg" alt="Android build and tests"></a>
</p>

<p align="center">
  <a href="https://bladeblack88.github.io/Iptv/"><strong>▶ Открыть IPTV Player</strong></a>
  &nbsp; • &nbsp;
  <a href="https://github.com/bladeblack88/Iptv/releases/download/apk-latest/app-debug.apk"><strong>⬇ Скачать APK</strong></a>
</p>

---

## ✨ Возможности

- **Телевизионный интерфейс** — крупные элементы и навигация с D-pad / пульта.
- **Воспроизведение потоков** — поддержка HLS, MPEG-TS и DASH через соответствующие web-плееры и возможности браузера.
- **HD-подборка** — автоматическая фильтрация каналов с заявленным качеством от 720p.
- **Перец в начале списка** — закреплён отдельной записью; это исключение из HD-фильтра, поскольку источник помечен как SD.
- **Избранное и список каналов** — быстрый доступ к нужным каналам.
- **Полноэкранный просмотр** — отдельная обработка режима для встроенного плеера и внешних web-плееров.
- **Автообновление плейлиста** — проверка доступности потоков и удаление дублей.
- **Адаптация под Android TV** — приложение работает в Android WebView.

## 🚀 Как пользоваться

1. Откройте [веб-версию](https://bladeblack88.github.io/Iptv/) или установите APK на Android / Android TV.
2. Дождитесь загрузки списка каналов.
3. Выберите канал в списке. На телевизоре перемещайтесь стрелками пульта и подтверждайте выбор кнопкой OK.
4. Добавляйте нужные каналы в избранное, чтобы быстрее находить их в следующий раз.

> Доступность и качество изображения зависят от исходного потока, региона и интернет-соединения. Проверка ссылки не гарантирует непрерывную трансляцию.

## 📱 Android-приложение

- **Package:** `com.bladeblack88.iptv`
- **Версия проекта:** `1.9` (versionCode `10`)
- **Минимальная версия Android:** Android 6.0 (API 23)
- **Сборка:** Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21, Java 17
- **APK:** [последняя debug-сборка](https://github.com/bladeblack88/Iptv/releases/download/apk-latest/app-debug.apk)

APK собирается GitHub Actions. Для локальной сборки с установленными Android SDK и Gradle 8.9:

```bash
gradle --no-daemon assembleDebug
```

Готовый файл появится в `app/build/outputs/apk/debug/app-debug.apk`.

## 🧰 Плейлист и автоматизация

Основной файл каналов: [`working.m3u`](working.m3u).

Скрипт [`scripts/check_playlist.py`](scripts/check_playlist.py):

1. Загружает русскоязычный список каналов из IPTV-org.
2. Оставляет записи с явно указанным качеством 720p или выше.
3. Добавляет закреплённый канал «Перец».
4. Проверяет потоки дважды с ограничением времени ожидания.
5. Убирает дубли и предпочитает более быстрый проверенный адрес.
6. Сохраняет результат в `working.m3u`.

Обновление выполняется GitHub Actions по расписанию раз в 6 часов и вручную через вкладку **Actions**. Проверка потоков — технический тест доступности на момент проверки, а не гарантия, что канал будет работать у каждого провайдера.

## 🧪 Проверки качества

В репозитории настроены автоматические проверки:

| Проверка | Что проверяется |
|---|---|
| Website browser smoke test | Загрузка опубликованной страницы, наличие списка каналов и отсутствие ошибок JavaScript в браузере |
| Playlist refresh | Загрузка исходного M3U, проверка потоков, формирование рабочего плейлиста |
| Android emulator tests | Управление с пульта, фокус элементов и работа Android WebView |
| Android APK build | Компиляция и публикация APK |

Последние проверки веб-версии и обновления плейлиста: [GitHub Actions](https://github.com/bladeblack88/Iptv/actions).

## 🗂️ Структура проекта

```text
.
├── index.html                 # Веб-интерфейс и логика IPTV-плеера
├── working.m3u                # Рабочий плейлист
├── scripts/
│   ├── check_playlist.py      # Сборка и проверка HD-плейлиста
│   └── check_working_playlist.py
├── app/
│   └── src/
│       ├── main/              # Android WebView-приложение
│       └── androidTest/       # Инструментальные тесты
└── .github/workflows/         # CI, тесты, APK и публикация сайта
```

## ⚙️ Технологии

- HTML, CSS и JavaScript
- [hls.js](https://github.com/video-dev/hls.js/)
- [mpegts.js](https://github.com/xqq/mpegts.js/)
- [dash.js](https://github.com/Dash-Industry-Forum/dash.js/)
- Kotlin и Android WebView
- Python 3 и GitHub Actions
- [IPTV-org](https://github.com/iptv-org/iptv) — источник русскоязычного списка каналов

## ⚠️ Важно

Проект предоставляет интерфейс для воспроизведения доступных сетевых потоков. Права на трансляции принадлежат соответствующим правообладателям и поставщикам. Используйте только те потоки, к которым у вас есть законный доступ. Стабильность сторонних источников проект гарантировать не может.

---

<p align="center">
  Сделано для удобного просмотра IPTV на большом экране 💜
</p>
