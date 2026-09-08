//go:build !unix

package libcore

import "errors"

func sendFdToProtect(fd int, path string) error {
	_ = fd
	_ = path
	return errors.New("sendFdToProtect is not supported on this platform")
}
