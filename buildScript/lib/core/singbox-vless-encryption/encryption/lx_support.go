package encryption

// lx: local replacements for the two tiny helpers the upstream reference kept in
// its own `common/xray/{cpuid,crypto}` packages. Vendoring those wholesale would
// drag in a large Xray-compat tree for ~20 lines of logic, so the pieces this
// package actually uses live here instead.

import (
	"crypto/rand"
	"math/big"
	"runtime"

	"golang.org/x/sys/cpu"
)

// hasAESGCMHardware reports whether the CPU accelerates AES-GCM. The handshake
// picks AES-GCM over ChaCha20-Poly1305 when it does, matching what the peer
// expects to negotiate. Kept in sync with crypto/tls/cipher_suites.go.
var hasAESGCMHardware = (cpu.X86.HasAES && cpu.X86.HasPCLMULQDQ) ||
	(cpu.ARM64.HasAES && cpu.ARM64.HasPMULL) ||
	(cpu.S390X.HasAES && cpu.S390X.HasAESCBC && cpu.S390X.HasGHASH) ||
	runtime.GOARCH == "ppc64" || runtime.GOARCH == "ppc64le"

// randBetween returns a uniform random value in [from, to). Used for the
// padding/delay ranges, which are cosmetic on the wire but must not be
// predictable, hence crypto/rand rather than math/rand.
func randBetween(from int64, to int64) int64 {
	if from == to {
		return from
	}
	if from > to {
		from, to = to, from
	}
	bigInt, _ := rand.Int(rand.Reader, big.NewInt(to-from))
	return from + bigInt.Int64()
}
