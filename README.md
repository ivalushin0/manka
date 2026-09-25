# Manka

Обход DPI для Android **без VPN** — для телефонов с root (Magisk, KernelSU, APatch).

Manka объединяет возможности [CDPI UI](https://github.com/Storik4pro/cdpiui) и
[TG WS Proxy](https://github.com/Flowseal/tg-ws-proxy) и добавляет автоподбор стратегий в духе
[ByeByeDPI](https://github.com/romanvht/ByeByeDPI).

## Возможности

- **Три движка обхода:** zapret2 (Lua-стратегии), zapret (nfqws) и ByeDPI (прозрачный прокси).
  Трафик перехватывается через iptables (NFQUEUE / REDIRECT), VPN-слот телефона остаётся свободным.
- **TG WS Proxy** — локальный MTProto-прокси для Telegram (Rust-версия
  [tg-ws-proxy-rs](https://github.com/valnesfjord/tg-ws-proxy-rs)). Запускается root-модулем,
  работает в фоне и после перезагрузки. Экономичный режим без заранее открытых соединений.
- **Автоподбор стратегий:** по кнопке и периодически в фоне. Если стратегия перестала работать,
  Manka сама подберёт новую. Во время проверки через тестовый движок идёт только трафик самой Manka.
- **Магазин CDPI UI:** наборы пресетов (Flowseal zapret-discord-youtube, YtDisBystro и др.)
  и списки доменов. Наборы встроены в APK и обновляются из сети. Формат пресетов CDPI UI
  (IC/UC, переменные, игровой фильтр) поддерживается, аргументы winws переводятся в nfqws.
- **Стратегии по сервисам** (zapret / zapret2): YouTube, Instagram/Facebook, TikTok и Discord могут
  получить свою стратегию, ограниченную их доменами; остальное идёт по основной. Автоподбор умеет
  подбирать для каждого сервиса отдельно или для всех по очереди.
- **Профили сетей:** мобильный интернет, любой Wi-Fi и отдельные Wi-Fi сети (по SSID) — со своим
  движком и стратегией, переключаются сами.
- **Шифрованный DNS** (DNS-over-HTTPS через [dnsproxy](https://github.com/AdguardTeam/dnsproxy)):
  провайдер не может подменить адреса заблокированных сайтов.
- **Раздача:** обход и DNS для устройств на точке доступа / USB / Bluetooth телефона.
- **Голос Discord (UDP)** — отдельный профиль для звонков.
- **Диагностика** DNS, IPv6 и адресов приложений (Instagram, YouTube, TikTok, Facebook) и статус
  сервисов на главном экране.
- **Исключения:** приложения (по UID, чёрный или белый список) и сайты, которые обход не трогает.
- Обновление приложения и модуля по кнопке, автообновление наборов магазина, резервная копия
  настроек, уведомление со статусом, нагрузка процессов (CPU / память).
- Плитка в шторке, русский и английский интерфейс.

## Установка

1. Скачайте `Manka-*.apk` со страницы [Releases](../../releases) или из артефактов последней сборки в Actions.
2. Откройте Manka и выдайте root-доступ.
3. Нажмите «Установить модуль» — модуль ставится через Magisk / KernelSU / APatch и начинает работать сразу.
4. Нажмите «Подобрать» на главном экране или включите обход кнопкой питания.

Модуль можно поставить и вручную: `Manka-module-*.zip` из Releases через менеджер root.

## Как это устроено

```
app (Kotlin, Compose)  ──su──>  /data/adb/modules/manka/manka.sh
                                 ├─ nfqws / nfqws2  <── iptables mangle NFQUEUE
                                 ├─ ciadpi -E        <── iptables nat REDIRECT
                                 └─ tg-ws-proxy      127.0.0.1:1443
настройки: /data/adb/manka/{settings.conf,args/*.args,kits/,lists/}
```

- `manka.sh` — управление (start/stop/status/test-start), супервизор перезапускает упавшие службы.
- `service.sh` запускает всё при загрузке телефона.
- Приложение пишет конфигурацию и вызывает скрипт; фоновая проверка — WorkManager.

## Сборка

Всё собирается в GitHub Actions (`.github/workflows/build.yml`):

- zapret и zapret2 — официальные Android-бинарники из релизов bol-van;
- ByeDPI — сборка из исходников через Android NDK;
- tg-ws-proxy-rs — сборка через cargo-ndk;
- dnsproxy (DNS-over-HTTPS) — официальные статические сборки AdGuard;
- снимок магазина CDPI UI кладётся в assets.

Для подписи APK одним ключом добавьте секреты репозитория `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Без них APK подписывается временным debug-ключом.

Локально: Android Studio (JDK 17), затем выполните `scripts/build-natives.sh` и `scripts/fetch-store.sh`
на Linux/WSL, либо скачайте `manka-module` из артефактов Actions и положите как `app/src/main/assets/module.zip`.

## Лицензии

Код Manka — MIT. Используемые компоненты:
[zapret](https://github.com/bol-van/zapret) и [zapret2](https://github.com/bol-van/zapret2) (MIT),
[ByeDPI](https://github.com/hufrea/byedpi) (MIT),
(https://github.com/AdguardTeam/dnsproxy) (Apache-2.0),
каталог [CDPIUI-Store](https://github.com/Storik4pro/CDPIUI-Store) и наборы пресетов — по лицензиям их авторов.
