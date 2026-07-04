// Command olcrtc-socks is a thin client-side wrapper around the olcRTC mobile
// package, built as an Android native executable (libolcrtc.so) and launched as
// a child-process sidecar by NekoBox (like the mieru/naive/masterdnsvpn plugins).
//
// It runs ONLY the olcRTC client (cnc role): it opens a loopback SOCKS5 listener
// that a sing-box `socks` outbound dials, and tunnels traffic out over the
// configured meet service. It never runs the olcRTC server.
//
// Sockets are kept out of the VPN tun by sending each new fd to libcore's
// protect_path unix-socket server (the same mechanism mieru/masterdnsvpn use),
// so upstream dials bypass the tun instead of looping through it.
//
// The carrier signaling (Jitsi XMPP + colibri websocket) is dialed inside the
// third-party j library via coder/websocket with a nil HTTPClient, so it uses
// Go's http.DefaultClient/http.DefaultTransport and the default resolver. We
// therefore install a process-global protected transport + resolver BEFORE
// starting olcRTC: the resolver dials the configured DNS (e.g. 9.9.9.9:53) over
// a protected socket (avoiding the VPN fake-IP resolver), and every dialed fd is
// protected via protect_path so it bypasses the tun. The WebRTC/pion media sockets
// do NOT flow through http.DefaultTransport, so they are protected separately: the
// pinned olcRTC engine installs a ProtectedNet via SettingEngine.SetNet whenever a
// protector is set (upstream internal/protect/pionnet.go + the jitsi SetNet hook,
// openlibrecommunity/olcrtc#111), so the mobile.SetProtector call below also keeps
// the media path off the tun.
package main

import (
	"context"
	"crypto/tls"
	"flag"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"syscall"
	"time"

	"golang.org/x/sys/unix"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

func main() {
	var (
		carrier     = flag.String("carrier", "", "carrier: jitsi|telemost|wbstream")
		transport   = flag.String("transport", "vp8channel", "transport: vp8channel|datachannel")
		roomID      = flag.String("room", "", "carrier-specific room id / url")
		clientID    = flag.String("client-id", "", "client (device) id")
		keyHex      = flag.String("key", "", "64-char hex encryption key")
		socksPort   = flag.Int("socks-port", 0, "local SOCKS5 listen port")
		socksUser   = flag.String("socks-user", "", "SOCKS5 username (optional)")
		socksPass   = flag.String("socks-pass", "", "SOCKS5 password (optional)")
		dnsServer   = flag.String("dns", "", "resolver host:port used by the tunnel")
		vp8FPS      = flag.Int("vp8-fps", 30, "vp8 fps")
		vp8Batch    = flag.Int("vp8-batch", 8, "vp8 batch size")
		protectPath = flag.String("protect-path", "", "path to libcore protect unix socket")
		readyMillis = flag.Int("ready-timeout-ms", 60000, "readiness wait in ms")
		debug       = flag.Bool("debug", false, "verbose logging")
	)
	flag.Parse()

	mobile.SetDebug(*debug)
	if !*debug {
		// Quiet by default so room ids / carrier urls are not written to logs.
		log.SetOutput(os.Stderr)
	}

	prot := &protector{path: *protectPath}

	// Install the process-global protected transport + resolver BEFORE any
	// network use, so the j library's http.DefaultClient websocket dials resolve
	// off the VPN fake-IP resolver and route their sockets around the tun.
	if *protectPath != "" {
		installProtectedDefaults(prot, *dnsServer)
		mobile.SetProtector(prot)
	}
	if *dnsServer != "" {
		mobile.SetDNS(*dnsServer)
	}
	mobile.SetVP8Options(*vp8FPS, *vp8Batch)

	if err := mobile.StartWithTransport(
		*carrier, *transport, *roomID, *clientID, *keyHex,
		*socksPort, *socksUser, *socksPass,
	); err != nil {
		log.Fatalf("olcrtc start: %v", err)
	}
	if err := mobile.WaitReady(*readyMillis); err != nil {
		mobile.Stop()
		log.Fatalf("olcrtc wait ready: %v", err)
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	mobile.Stop()
}

// installProtectedDefaults replaces net.DefaultResolver and http.DefaultTransport
// so that (1) hostname lookups use dnsServer over a protected UDP/TCP socket
// instead of Android's VPN fake-IP resolver, and (2) every TCP socket dialed by
// the default HTTP client (the j library's websocket handshakes) is protected via
// protect_path before connect. Must run before any goroutine creates a client.
func installProtectedDefaults(prot *protector, dnsServer string) {
	control := func(_, _ string, c syscall.RawConn) error {
		var perr error
		if err := c.Control(func(fd uintptr) {
			if !prot.Protect(int(fd)) {
				perr = unix.EPERM
			}
		}); err != nil {
			return err
		}
		return perr
	}

	if dnsServer != "" {
		// dnsServer must be an IP:port literal (e.g. 9.9.9.9:53): a hostname here
		// would be resolved through this same resolver, recursing until timeout.
		// Fail fast on misconfiguration instead. netip.ParseAddr (unlike
		// net.ParseIP) also accepts zone-scoped literals such as fe80::1%wlan0.
		host, _, err := net.SplitHostPort(dnsServer)
		if err != nil {
			log.Fatalf("olcrtc: -dns must be an IP:port literal: %v", err)
		}
		if _, err := netip.ParseAddr(host); err != nil {
			log.Fatalf("olcrtc: -dns host %q is not an IP literal", host)
		}
		// Resolver that dials the configured DNS directly over a protected socket.
		net.DefaultResolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				d := net.Dialer{Timeout: 10 * time.Second, Control: control}
				return d.DialContext(ctx, network, dnsServer)
			},
		}
	}

	dialer := &net.Dialer{
		Timeout:   30 * time.Second,
		KeepAlive: 30 * time.Second,
		Control:   control,
		Resolver:  net.DefaultResolver,
	}
	http.DefaultTransport = &http.Transport{
		Proxy:       http.ProxyFromEnvironment,
		DialContext: dialer.DialContext,
		// Disable HTTP/2 for signaling. The WebSocket upgrade the XMPP client
		// performs cannot ride an h2 connection (RFC 7540 forbids Connection:
		// Upgrade); when a server negotiates h2 via ALPN the upgrade request
		// stalls with no response until the header timeout. Pinning h1 connects
		// on the first try.
		ForceAttemptHTTP2:     false,
		TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 15 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
}

// protector sends each new socket fd to libcore's protect_path unix-socket
// server, which calls VpnService.protect(fd). This mirrors libcore's
// sendFdToProtect client direction (SCM_RIGHTS fd passing + 1-byte ack).
type protector struct {
	path string
}

func (p *protector) Protect(fd int) bool {
	if p.path == "" {
		return true
	}
	return sendFdToProtect(fd, p.path) == nil
}

func sendFdToProtect(fd int, path string) error {
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return err
	}
	defer unix.Close(socketFd)

	var timeout unix.Timeval
	timeout.Usec = 100 * 1000
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout)
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout)

	if err := unix.Connect(socketFd, &unix.SockaddrUnix{Name: path}); err != nil {
		return err
	}
	if err := unix.Sendmsg(socketFd, nil, unix.UnixRights(fd), nil, 0); err != nil {
		return err
	}
	dummy := []byte{1}
	n, err := unix.Read(socketFd, dummy)
	if err != nil {
		return err
	}
	if n != 1 {
		return unix.EINVAL
	}
	return nil
}
