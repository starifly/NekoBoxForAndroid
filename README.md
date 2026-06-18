# NekoBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

## Disclaimer

> This project is intended solely for technical research and code learning purposes and does not provide any form of network proxy service. Please do not use this project for any activity that violates local laws and regulations, and do not use it in production environments. Users are fully responsible for any risks that may arise from using this project. If you download or reference this project, please delete all related content within 24 hours and avoid long-term storage, distribution, or dissemination of any part of it. **The author reserves the right to modify, update, or remove any part of this project or its contents at any time without prior notice.**

## Downloads

**The Google Play version has been controlled by a third party since May 2024 and is a non-open
source version. Please do not download it.**

## Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* ShadowsocksR
* VMess
* Trojan
* VLESS
* AnyTLS/AnyReality
* Snell 1/2/3/4/5
* ShadowTLS
* TUIC
* Juicity
* Hysteria 1/2 (including Gecko obfuscation, bundled)
* WireGuard
* MasterDnsVPN (DNS-tunnel, bundled)
* Mieru (bundled)
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (bundled)

<details>
<summary>XHTTP Extra TLS configuration example</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "b.yourdomain.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
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
<summary>XHTTP Extra Reality configuration example</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
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

Trojan-Go requires its external plugin for full proxy support.

## Supported Subscription Format

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

## Contributors

![Contributors](https://contrib.rocks/image?repo=starifly/NekoBoxForAndroid)
