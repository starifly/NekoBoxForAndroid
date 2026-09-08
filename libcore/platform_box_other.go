//go:build !unix

package libcore

import (
	"errors"

	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
)

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	_ = options
	_ = platformOptions
	return nil, errors.New("OpenInterface is not supported on this platform")
}
