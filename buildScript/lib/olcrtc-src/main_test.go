package main

import (
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func TestWaitAfterReadySignalIsGraceful(t *testing.T) {
	signals := make(chan os.Signal, 1)
	signals <- syscall.SIGTERM

	if !waitAfterReady(signals, make(chan time.Time), func() bool { return true }) {
		t.Fatal("signal shutdown was reported as runtime failure")
	}
}

func TestWaitAfterReadyStopsOnFirstUnhealthyTick(t *testing.T) {
	ticks := make(chan time.Time, 1)
	ticks <- time.Now()

	if waitAfterReady(make(chan os.Signal), ticks, func() bool { return false }) {
		t.Fatal("stopped runtime was reported as graceful shutdown")
	}
}

func TestPublishReadyMarkerReplacesStaleMarker(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ready")
	if err := os.WriteFile(path, []byte("stale\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := publishReadyMarker(path); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "ready\n" {
		t.Fatalf("marker content = %q, want ready", content)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Fatalf("marker mode = %o, want 600", mode)
	}
}

func TestPublishReadyMarkerAllowsDisabledMarker(t *testing.T) {
	if err := publishReadyMarker(""); err != nil {
		t.Fatal(err)
	}
}

func TestWaitAfterReadyHealthyTicksContinue(t *testing.T) {
	signals := make(chan os.Signal)
	ticks := make(chan time.Time)
	result := make(chan bool, 1)
	checks := 0
	go func() {
		result <- waitAfterReady(signals, ticks, func() bool {
			checks++
			return true
		})
	}()

	ticks <- time.Now()
	signals <- syscall.SIGTERM
	if graceful := <-result; !graceful {
		t.Fatal("healthy runtime tick stopped the wait loop")
	}
	if checks != 1 {
		t.Fatalf("running checks = %d, want 1", checks)
	}
}
