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
	"net/http/httptrace"
	"net/netip"
	"os"
	"os/signal"
	"strings"
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
		installProtectedDefaults(prot, *dnsServer, *debug)
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
//
// The protected signaling sockets are also MSS-clamped (TCP_MAXSEG) so an oversized
// inbound TLS certificate flight cannot be silently dropped on a reduced-MTU routed
// path, and the transport carries explicit handshake/response timeouts so any stall
// surfaces as a typed error in seconds rather than blocking until the readiness
// deadline. Under debug, each request's connection stages are traced.
//
// IPv4-only: the signaling dials are forced to tcp4, which also limits lookups to A
// records. On a routed/VPN path the underlying physical interface is commonly
// v4-only; a carrier host that also publishes an AAAA (e.g. framatalk.org) would
// otherwise have its protected socket dial the unreachable v6 address and hang
// silently until the readiness deadline instead of connecting over v4. DNS queries
// themselves are forced to udp4/tcp4 too, unless dnsServer is a v6 literal.
func installProtectedDefaults(prot *protector, dnsServer string, debug bool) {
	control := func(network, _ string, c syscall.RawConn) error {
		var perr error
		if err := c.Control(func(fd uintptr) {
			if !prot.Protect(int(fd)) {
				perr = unix.EPERM
				return
			}
			// Cap the advertised MSS on the protected signaling sockets. On a routed
			// path the effective MTU is often below 1500 with ICMP frag-needed
			// filtered, so path-MTU discovery never converges; a large inbound flight
			// (a TLS certificate, an HTTP body, or Jicofo's conference reply) arrives
			// in full-size segments, one is dropped, and TCP head-of-line blocking
			// stalls the whole stream until the readiness deadline. A conservative MSS
			// (advertised in our SYN, so it bounds the segments the peer sends US) keeps
			// those inbound segments small enough to pass. Must be set pre-connect,
			// which Control guarantees.
			if strings.HasPrefix(network, "tcp") {
				if serr := unix.SetsockoptInt(int(fd), unix.IPPROTO_TCP, unix.TCP_MAXSEG, 1000); serr != nil && debug {
					log.Printf("trace: set TCP_MAXSEG failed: %v", serr)
				}
				// Also clamp the receive buffer BEFORE connect. The TCP window scale is
				// negotiated in the SYN from SO_RCVBUF, so a small buffer shrinks the
				// advertised receive window and forces the peer to send its response in
				// several small flights instead of one large burst. If a middlebox on the
				// routed path mishandles window scaling or polices large bursts (the
				// signature here: small inbound stanzas pass, large ones stall even with the
				// MSS clamp proven on the wire), a small window lets the body trickle in.
				// Must be set pre-connect. 32 KiB is large enough for signaling throughput
				// yet small enough to cap any single flight.
				if serr := unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_RCVBUF, 32*1024); serr != nil && debug {
					log.Printf("trace: set SO_RCVBUF failed: %v", serr)
				}
			}
		}); err != nil {
			return err
		}
		return perr
	}

	// forceIPv4Network rewrites tcp/tcp6 -> tcp4 and udp/udp6 -> udp4 so a
	// published AAAA never causes a silent hang on a v4-only physical path.
	forceIPv4Network := func(network string) string {
		switch network {
		case "tcp", "tcp6":
			return "tcp4"
		case "udp", "udp6":
			return "udp4"
		}
		return network
	}

	if dnsServer != "" {
		// Resolver that dials the configured DNS directly over a protected socket.
		// dnsServer must be an IP:port literal (e.g. 9.9.9.9:53) to avoid recursion.
		// Only rewrite the query transport when the server is v4: forcing udp4/tcp4
		// toward a v6 literal (e.g. [2620:fe::fe]:53) would fail every lookup.
		// netip.ParseAddr (unlike net.ParseIP) also handles zone-scoped literals
		// such as fe80::1%wlan0.
		dnsV4 := true
		if host, _, err := net.SplitHostPort(dnsServer); err == nil {
			if a, aerr := netip.ParseAddr(host); aerr == nil && !a.Is4() && !a.Is4In6() {
				dnsV4 = false
			}
		}
		net.DefaultResolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				if dnsV4 {
					network = forceIPv4Network(network)
				}
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
	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialer.DialContext(ctx, forceIPv4Network(network), addr)
		},
		// Disable HTTP/2 for signaling. The WebSocket upgrade the XMPP client performs
		// cannot ride an h2 connection; when a carrier negotiates h2 via ALPN (observed
		// on framatalk.org) the upgrade request stalls with no response until the
		// header timeout, and only a subsequent h1 attempt succeeds. Pinning h1 avoids
		// the wasted round and connects on the first try.
		ForceAttemptHTTP2:     false,
		TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 15 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
	if debug {
		// Stage-trace the signaling dials so a stall (DNS / TCP connect / TLS
		// handshake / header write / first response byte) is pinpointed instead of
		// showing up as 60s of silence. Only under -debug (logs request URLs).
		http.DefaultTransport = traceTransport{next: transport}
	} else {
		http.DefaultTransport = transport
	}
}

// traceTransport wraps an http.RoundTripper and logs the connection stages of each
// request via net/http/httptrace, so a stall is attributable to a specific stage
// (DNS / TCP connect / TLS handshake / header write / first response byte) rather
// than showing up as silence until the readiness deadline. Debug-only.
type traceTransport struct{ next http.RoundTripper }

func (t traceTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	trace := &httptrace.ClientTrace{
		GetConn: func(hp string) { log.Printf("trace: get-conn %s", hp) },
		GotConn: func(gci httptrace.GotConnInfo) {
			log.Printf("trace: got-conn reused=%v %s", gci.Reused, gci.Conn.RemoteAddr())
			logAdvMSS(gci.Conn)
		},
		DNSStart:          func(i httptrace.DNSStartInfo) { log.Printf("trace: dns-start %s", i.Host) },
		DNSDone:           func(i httptrace.DNSDoneInfo) { log.Printf("trace: dns-done %v err=%v", i.Addrs, i.Err) },
		ConnectStart:      func(nw, addr string) { log.Printf("trace: connect-start %s %s", nw, addr) },
		ConnectDone:       func(nw, addr string, err error) { log.Printf("trace: connect-done %s %s err=%v", nw, addr, err) },
		TLSHandshakeStart: func() { log.Printf("trace: tls-start") },
		TLSHandshakeDone: func(cs tls.ConnectionState, err error) {
			log.Printf("trace: tls-done proto=%q err=%v", cs.NegotiatedProtocol, err)
		},
		WroteHeaders:         func() { log.Printf("trace: wrote-headers") },
		GotFirstResponseByte: func() { log.Printf("trace: first-byte") },
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))
	log.Printf("trace: round-trip %s %s", req.Method, req.URL)
	return t.next.RoundTrip(req)
}

// logAdvMSS reports the connection's advertised/receive MSS from TCP_INFO so the trace
// shows whether the TCP_MAXSEG clamp actually took effect on the SYN (advmss ~= clamp)
// rather than the default (~1460). GotConn hands us a *tls.Conn, which does NOT expose
// SyscallConn directly; it exposes NetConn() to reach the underlying *net.TCPConn whose
// SyscallConn does. Unwrap the TLS layer first (Go >= 1.18), otherwise the assertion
// fails silently and no advmss is ever logged.
//
// It also starts a short background poll of TCP_INFO on the same fd. During a large-
// inbound stall this shows whether ANYTHING is arriving (bytes_received / segs_in) or
// the flight dies upstream entirely, and whether out-of-order arrivals climb (a hole at
// the front of the stream = size-selective drop). This is the only on-device proxy for
// an inbound packet capture, since the protected socket bypasses the tun and an
// unrooted app cannot pcap the physical path.
func logAdvMSS(conn net.Conn) {
	if nc, ok := conn.(interface{ NetConn() net.Conn }); ok {
		conn = nc.NetConn()
	}
	type syscallConn interface {
		SyscallConn() (syscall.RawConn, error)
	}
	sc, ok := conn.(syscallConn)
	if !ok {
		log.Printf("trace: tcp-info no syscall.Conn (%T)", conn)
		return
	}
	raw, err := sc.SyscallConn()
	if err != nil {
		return
	}
	_ = raw.Control(func(fd uintptr) {
		if ti, terr := unix.GetsockoptTCPInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_INFO); terr == nil {
			log.Printf("trace: tcp-info advmss=%d rcv_mss=%d snd_mss=%d", ti.Advmss, ti.Rcv_mss, ti.Snd_mss)
		}
	})
	go pollTCPInfo(raw)
}

// pollTCPInfo samples inbound TCP_INFO counters every 500ms for a window that spans a
// typical large-inbound stall (~16s, the config.js / Jicofo reply timeout). Frozen
// bytes_received + segs_in => the whole flight dies upstream (server/return-path, no
// client fix). Rising segs_in with frozen bytes_received and climbing rcv_ooopack =>
// the head (large) segment is dropped selectively while later segments arrive out of
// order => a permanent hole at the front of the stream. Debug-only.
func pollTCPInfo(raw syscall.RawConn) {
	for i := 0; i < 32; i++ {
		_ = raw.Control(func(fd uintptr) {
			if ti, terr := unix.GetsockoptTCPInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_INFO); terr == nil {
				log.Printf("trace: tcp-info-poll t=%dms bytes_recv=%d segs_in=%d ooo=%d rcv_wnd=%d rtt=%dus",
					i*500, ti.Bytes_received, ti.Segs_in, ti.Rcv_ooopack, ti.Rcv_wnd, ti.Rtt)
			}
		})
		time.Sleep(500 * time.Millisecond)
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
