package encryption

// lx: SPEC 105 — let flow `xtls-rprx-vision` run on top of this layer.
//
// Vision in sing-vmess finds the TLS conn beneath it through a private registry
// that only knows crypto/tls and utls, so a *CommonConn was rejected with
// "not a valid supported TLS connection". Xray treats CommonConn like a TLS
// conn: Vision reads its input/rawInput fields, and direct copy goes to the
// conn beneath the encryption layer (proxy.UnwrapRawConn). One registry entry
// gives sing-vmess the same view.

import (
	"net"
	"reflect"
	"unsafe"

	"github.com/sagernet/sing-vmess/vless"
	N "github.com/sagernet/sing/common/network"
)

// Importing vless above runs its init first, so the entry lands after the
// built-in crypto/tls and utls ones.
var _ = vless.FlowVision

//go:linkname visionTLSRegistry github.com/sagernet/sing-vmess/vless.tlsRegistry
var visionTLSRegistry []func(conn net.Conn) (loaded bool, netConn net.Conn, reflectType reflect.Type, reflectPointer uintptr)

func init() {
	visionTLSRegistry = append(visionTLSRegistry, castVisionConn)
}

func castVisionConn(conn net.Conn) (loaded bool, netConn net.Conn, reflectType reflect.Type, reflectPointer uintptr) {
	commonConn, loaded := N.CastReader[*CommonConn](conn)
	if !loaded {
		return
	}
	return true, commonConn.Conn, reflect.TypeOf(commonConn).Elem(), uintptr(unsafe.Pointer(commonConn))
}
