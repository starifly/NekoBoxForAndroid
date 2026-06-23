<details>
<summary>Описание оригинала</summary>

# NekoBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

## Отказ от ответственности

> Этот проект предназначен исключительно для технического исследования и изучения кода и не предоставляет никаких услуг сетевого прокси. Не используйте проект для деятельности, нарушающей местные законы и правила. Не используйте его в производственной среде. Пользователь несёт полную ответственность за любые риски, связанные с использованием проекта. Если вы скачали или использовали материалы проекта, удалите их в течение 24 часов и не храните, не распространяйте и не публикуйте их на постоянной основе. **Автор оставляет за собой право в любой момент изменять, обновлять или удалять проект либо его содержимое без предварительного уведомления.**

## Изменения в этом репозитории

В этом репозитории, помимо исходной функциональности проекта, внесены дополнительные изменения:

- Обновлён процесс release-сборки: итоговые APK собираются в заранее определённую директорию с артефактами, чтобы их было проще находить после сборки.
- Скорректирована логика действия **Clear Cache & Restart** для более корректной очистки кэша и повторного запуска приложения через ярлык.
- Устранена уязвимость связанная с раскрытием выходного адреса VPN через SOCKS5 прокси без авторизации.

## Загрузка

[![GitHub All Releases](https://img.shields.io/github/downloads/Matsuridayo/NekoBoxForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/starifly/NekoBoxForAndroid/releases)

[Скачать релизы с GitHub](https://github.com/starifly/NekoBoxForAndroid/releases)

**Версия из Google Play с мая 2024 года контролируется третьей стороной и не является open-source-версией. Не рекомендуется её скачивать.**

## Журнал изменений и Telegram-канал

https://t.me/Matsuridayo

## Домашняя страница и документация

https://matsuridayo.github.io

## Поддерживаемые прокси-протоколы

- SOCKS (4/4a/5)
- HTTP(S)
- SSH
- Shadowsocks
- ShadowsocksR
- VMess
- Trojan
- VLESS
- AnyTLS/AnyReality
- ShadowTLS
- TUIC
- Juicity
- Hysteria 1/2
- WireGuard
- Trojan-Go (trojan-go-plugin)
- NaïveProxy (naive-plugin)
- Mieru (mieru-plugin)

<details>
<summary>Пример конфигурации XHTTP Extra TLS</summary>

<pre><code class="language-json">
{
	"x_padding_bytes": "0-0",
	"sc_max_each_post_bytes": "0-0",
	"sc_min_posts_interval_ms": "0-0",
	"sc_stream_up_server_secs": "0-0",
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
	"download": {
		"mode": "auto",
		"host": "b.yourdomain.com",
		"path": "/xhttp",
		"x_padding_bytes": "0-0",
		"sc_max_each_post_bytes": "0-0",
		"sc_min_posts_interval_ms": "0-0",
		"sc_stream_up_server_secs": "0-0",
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "b.yourdomain.com",
			"alpn": "h2",
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

<details>
<summary>Пример конфигурации XHTTP Extra Reality</summary>

<pre><code class="language-json">
{
	"x_padding_bytes": "0-0",
	"sc_max_each_post_bytes": "0-0",
	"sc_min_posts_interval_ms": "0-0",
	"sc_stream_up_server_secs": "0-0",
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
		"x_padding_bytes": "0-0",
		"sc_max_each_post_bytes": "0-0",
		"sc_min_posts_interval_ms": "0-0",
		"sc_stream_up_server_secs": "0-0",
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "example.com",
			"reality": {
				"enabled": true,
				"public_key": "$(your_publicKey)",
				"short_id": "$(your_shortId)"
			},
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

Скачать плагины для полной поддержки прокси можно [здесь](https://matsuridayo.github.io/nb4a-plugin/).

## Поддерживаемые форматы подписок

- Некоторые широко используемые форматы, включая Shadowsocks, ClashMeta и v2rayN
- sing-box outbound

Поддерживается только разбор outbound-конфигураций, то есть узлов. Информация о правилах маршрутизации и прочих политиках игнорируется.

## Благодарности

### Core

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

### Android GUI

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

### Web Dashboard

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
</details>

# NekoBox for Android
[Форк этого репозитория (в котором относительно ваниллы есть xhttp)](https://github.com/starifly/NekoBoxForAndroid)

## Основные правки относительно него
+ Устранена утечка через tun0
+ Устранена утечка через proxy (внедрена авторизация)
+ Socks5 и HTTP разнесены на разные порты (логин\пароль общие)
+ Добавлена поддержка протокола AmneziaWG 2.0: заменена ванильная реализация WG от SagerNet на AWG от [Amnezia](https://github.com/amnezia-vpn). Работоспособность ванильного wg под вопросом (не проверял - неначем)
+ Добавлена возможность указать HWID в настройках подписки и включить отправку (если не указывать - сгенерится)
+ Добавлены типы профилей "Водопад" и "Самый быстрый" по образу и подобию "Прокси-цепочки". "Самый быстрый" при запуске пингует выбранные в профиле конфиги и выбирает с наименьшим пингом. "Водопад" при запуске пингует конфинги, НО выбирает первый рабочий по порядку, указанному пользователем (работает первый - первый, не работает первый - второй, не работает второй - третий и тд). Так-же они периодически перепроверяют и переключаются если есть основания (пропажа связи у текущего, разница 50мс в меньшую у быстрейшего, активизация вышестоящего у водопада)

### "tls illegal"
+ Возможность вывести на рабочий стол ярлык "Clear Cache & Reboot" - чистит кэш и делает холодный перезапуск как при смене уровня логирования (да, смена уровня логирования это чинит)
+ **_Возможно_** устранена первопричина: как понял проблема в "залипании" и общей db у профилей - db теперь разные + при запуске vless с tls применяется experimental.cache_file.enabled = false (тоесть битой db быть не должно так как ее не будет)

## Минусы
+ Кажется сломалась маршрутизация при "Персональном DNS" - как решение либо отключить маршрутизацию DNS в NB и включить "Персональный DNS" в системе (рекомендую), либо отключить маршрутизацию DNS и включить "FakeDNS"
+ На текущий момент профили AWG добавляются через импорт конфига или ручками (по QR не подтягиватеся)

## Рекомендации (уровня ИМХО)
+ Прописать "Персональный DNS" в настройках системы (например dns.adguard-dns.com)
+ В NB:
   - [x] Обновить Geosite\GeoIP (3 точки сверху в маршрутах > "Управление ресурсами маршрутов")
   - [x] Маршрут > Новый > "dst ip" = geoip:ru, "outbound" = Обход > Сохранить и вниз списка
   - [x] Маршрут > Новый > "Domain" = geosite:category-ru, "outbound" = Обход > Сохранить и выше ip
   - [x] Маршрут > Новый > "Пользовательская конфигурация" > {"ip_is_private": true,"outbound": "direct"} > outbound = Обход > Сохранить и вверх списка
   - [x] "Режим VPN для приложений" (прокси и выбрать нужные)
   - [x] "Обход LAN"
   - [x] "Обход LAN в ядре"
   - [x] "Определить адрес назначения"
   - [x] "RuleSet Update Interval" > 72h
   - [x] Удаленный\Прямой DNS (указать DoH, например https://dns.adguard-dns.com/dns-query)
   - [x] "Правило домена..." - prefer_ipv4 (ipv6 хорошо, но не в России - не распространено)
   - [ ] "Включить маршрутизацию DNS"
   - [ ] "Включить FakeDNS"
   - [x] Socks5\HTTP порт - между 20000 и 40000


## Тесты

<details>
<summary>RKNHardering</summary>
<a href="https://github.com/ISAIandCO/NekoBox_SF/blob/main/.github/RKNHardering.jpg">
    <img src="/.github/RKNHardering.jpg" alt="RKNHardering" width="1500">
</a>
</details>

<details>
<summary>YourVPNDead</summary>	
<a href="https://github.com/ISAIandCO/NekoBox_SF/blob/main/.github/YourVPNDead.jpg">
    <img src="/.github/YourVPNDead.jpg" alt="YourVPNDead" width="1500">
</a>
</details>

<details>
<summary>Termux (tun0)</summary>	
<a href="https://github.com/ISAIandCO/NekoBox_SF/blob/main/.github/Termux.png">
    <img src="/.github/Termux.png" alt="Termux" width="500">
</a>
</details>
