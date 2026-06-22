#!/usr/bin/env python3
import pathlib
import sys


def patch_file(path: pathlib.Path, old: str, new: str):
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        if "func (g *URLTestGroup) Select" in old and "func firstAvailableIndex" in text:
            return
        if "DeleteURLTestHistory(outbound.Tag())" in old and "s.checkAfterFailure()" in text:
            return
        raise RuntimeError(f"anchor not found in {path}")
    path.write_text(text.replace(old, new, 1))


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_sing_box_balancers.py <sing-box-dir>")
    root = pathlib.Path(sys.argv[1]).resolve()

    group_options = root / "option" / "group.go"
    patch_file(
        group_options,
        '''type URLTestOutboundOptions struct {
\tOutbounds                 []string           `json:"outbounds"`
\tURL                       string             `json:"url,omitempty"`
\tInterval                  badoption.Duration `json:"interval,omitempty"`
\tTolerance                 uint16             `json:"tolerance,omitempty"`
\tIdleTimeout               badoption.Duration `json:"idle_timeout,omitempty"`
\tInterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
}
''',
        '''type URLTestOutboundOptions struct {
\tOutbounds                 []string           `json:"outbounds"`
\tURL                       string             `json:"url,omitempty"`
\tInterval                  badoption.Duration `json:"interval,omitempty"`
\tTolerance                 uint16             `json:"tolerance,omitempty"`
\tIdleTimeout               badoption.Duration `json:"idle_timeout,omitempty"`
\tTimeout                   badoption.Duration `json:"timeout,omitempty"`
\tStrategy                  string             `json:"strategy,omitempty"`
\tManagedByParent           bool               `json:"managed_by_parent,omitempty"`
\tWaitForInitial            bool               `json:"wait_for_initial,omitempty"`
\tInterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
}
''',
    )

    urltest = root / "protocol" / "group" / "urltest.go"
    patch_file(
        urltest,
        '''\tinterval                     time.Duration
\ttolerance                    uint16
\tidleTimeout                  time.Duration
\tgroup                        *URLTestGroup
''',
        '''\tinterval                     time.Duration
\ttolerance                    uint16
\tidleTimeout                  time.Duration
\ttimeout                      time.Duration
\tstrategy                     string
\tmanagedByParent              bool
\twaitForInitial               bool
\tgroup                        *URLTestGroup
''',
    )
    patch_file(
        urltest,
        '''\t\tinterval:                     time.Duration(options.Interval),
\t\ttolerance:                    options.Tolerance,
\t\tidleTimeout:                  time.Duration(options.IdleTimeout),
\t\tinterruptExternalConnections: options.InterruptExistConnections,
''',
        '''\t\tinterval:                     time.Duration(options.Interval),
\t\ttolerance:                    options.Tolerance,
\t\tidleTimeout:                  time.Duration(options.IdleTimeout),
\t\ttimeout:                      time.Duration(options.Timeout),
\t\tstrategy:                     options.Strategy,
\t\tmanagedByParent:              options.ManagedByParent,
\t\twaitForInitial:               options.WaitForInitial,
\t\tinterruptExternalConnections: options.InterruptExistConnections,
''',
    )
    patch_file(
        urltest,
        '''\tgroup, err := NewURLTestGroup(s.ctx, s.outbound, s.logger, outbounds, s.link, s.interval, s.tolerance, s.idleTimeout, s.interruptExternalConnections)
''',
        '''\tgroup, err := NewURLTestGroup(s.ctx, s.outbound, s.logger, outbounds, s.link, s.interval, s.tolerance, s.idleTimeout, s.timeout, s.strategy, s.managedByParent, s.interruptExternalConnections)
''',
    )
    patch_file(
        urltest,
        '''func (s *URLTest) PostStart() error {
\ts.group.PostStart()
\treturn nil
}
''',
        '''func (s *URLTest) PostStart() error {
\tif !s.managedByParent {
\t\ts.group.PostStart(s.waitForInitial)
\t}
\treturn nil
}
''',
    )
    patch_file(
        urltest,
        '''\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(outbound.Tag())
\treturn nil, err
''',
        '''\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(RealTag(outbound))
\tgo s.group.CheckOutbounds(true)
\treturn nil, err
''',
    )
    patch_file(
        urltest,
        '''\tif err == nil {
\t\treturn s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
\t}
\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(outbound.Tag())
\treturn nil, err
''',
        '''\tif err == nil {
\t\treturn s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
\t}
\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(RealTag(outbound))
\tgo s.group.CheckOutbounds(true)
\treturn nil, err
''',
    )
    patch_file(
        urltest,
        '''\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(outbound.Tag())
\treturn nil, err
''',
        '''\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(RealTag(outbound))
\tgo s.group.CheckOutbounds(true)
\treturn nil, err
''',
    )
    patch_file(
        urltest,
        '''func (s *URLTest) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
''',
        '''func (s *URLTest) checkAfterFailure() {
\tif !s.managedByParent {
\t\tgo s.group.CheckOutbounds(true)
\t}
}

func (s *URLTest) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
''',
    )
    patched_urltest = urltest.read_text().replace(
        "\tgo s.group.CheckOutbounds(true)\n\treturn nil, err",
        "\ts.checkAfterFailure()\n\treturn nil, err",
    )
    urltest.write_text(patched_urltest)
    patch_file(
        urltest,
        '''\tidleTimeout                  time.Duration
\thistory                      adapter.URLTestHistoryStorage
''',
        '''\tidleTimeout                  time.Duration
\ttimeout                      time.Duration
\tstrategy                     string
\tmanagedByParent              bool
\thistory                      adapter.URLTestHistoryStorage
''',
    )
    patch_file(
        urltest,
        '''func NewURLTestGroup(ctx context.Context, outboundManager adapter.OutboundManager, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, tolerance uint16, idleTimeout time.Duration, interruptExternalConnections bool) (*URLTestGroup, error) {
''',
        '''func NewURLTestGroup(ctx context.Context, outboundManager adapter.OutboundManager, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, tolerance uint16, idleTimeout time.Duration, timeout time.Duration, strategy string, managedByParent bool, interruptExternalConnections bool) (*URLTestGroup, error) {
''',
    )
    patch_file(
        urltest,
        '''\tif idleTimeout == 0 {
\t\tidleTimeout = C.DefaultURLTestIdleTimeout
\t}
\tif interval > idleTimeout {
''',
        '''\tif idleTimeout == 0 {
\t\tidleTimeout = C.DefaultURLTestIdleTimeout
\t}
\tif timeout == 0 {
\t\ttimeout = C.TCPTimeout
\t}
\tif strategy == "" {
\t\tstrategy = "fastest"
\t}
\tif strategy != "fastest" && strategy != "priority" {
\t\treturn nil, E.New("unknown urltest strategy: ", strategy)
\t}
\tif interval > idleTimeout {
''',
    )
    patch_file(
        urltest,
        '''\t\tidleTimeout:                  idleTimeout,
\t\thistory:                      history,
''',
        '''\t\tidleTimeout:                  idleTimeout,
\t\ttimeout:                      timeout,
\t\tstrategy:                     strategy,
\t\tmanagedByParent:              managedByParent,
\t\thistory:                      history,
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) PostStart() {
\tg.access.Lock()
\tdefer g.access.Unlock()
\tg.started = true
\tg.lastActive.Store(time.Now())
\tgo g.CheckOutbounds(false)
}
''',
        '''func (g *URLTestGroup) PostStart(waitForInitial bool) {
\tg.access.Lock()
\tdefer g.access.Unlock()
\tg.started = true
\tg.lastActive.Store(time.Now())
\tif waitForInitial {
\t\tg.CheckOutbounds(true)
\t} else {
\t\tgo g.CheckOutbounds(false)
\t}
}
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) Touch() {
\tif !g.started {
''',
        '''func (g *URLTestGroup) Touch() {
\tif g.managedByParent {
\t\treturn
\t}
\tif !g.started {
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
\tvar minDelay uint16
''',
        '''func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
\tif g.strategy == "priority" {
\t\tfor _, detour := range g.outbounds {
\t\t\tif !common.Contains(detour.Network(), network) {
\t\t\t\tcontinue
\t\t\t}
\t\t\tif history := g.history.LoadURLTestHistory(RealTag(detour)); history != nil {
\t\t\t\treturn detour, true
\t\t\t}
\t\t}
\t\tfor _, detour := range g.outbounds {
\t\t\tif common.Contains(detour.Network(), network) {
\t\t\t\treturn detour, false
\t\t\t}
\t\t}
\t\treturn nil, false
\t}
\tvar minDelay uint16
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
\tif g.strategy == "priority" {
\t\tfor _, detour := range g.outbounds {
\t\t\tif !common.Contains(detour.Network(), network) {
\t\t\t\tcontinue
\t\t\t}
\t\t\tif history := g.history.LoadURLTestHistory(RealTag(detour)); history != nil {
\t\t\t\treturn detour, true
\t\t\t}
\t\t}
\t\tfor _, detour := range g.outbounds {
\t\t\tif common.Contains(detour.Network(), network) {
\t\t\t\treturn detour, false
\t\t\t}
\t\t}
\t\treturn nil, false
\t}
''',
        '''func firstAvailableIndex(available []bool) (int, bool) {
\tfor index, isAvailable := range available {
\t\tif isAvailable {
\t\t\treturn index, true
\t\t}
\t}
\treturn 0, false
}

func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
\tif g.strategy == "priority" {
\t\tvar candidates []adapter.Outbound
\t\tvar available []bool
\t\tfor _, detour := range g.outbounds {
\t\t\tif !common.Contains(detour.Network(), network) {
\t\t\t\tcontinue
\t\t\t}
\t\t\tcandidates = append(candidates, detour)
\t\t\tavailable = append(available, g.history.LoadURLTestHistory(RealTag(detour)) != nil)
\t\t}
\t\tif index, exists := firstAvailableIndex(available); exists {
\t\t\treturn candidates[index], true
\t\t}
\t\treturn nil, false
\t}
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
\tresult := make(map[string]uint16)
\tif g.checking.Swap(true) {
\t\treturn result, nil
\t}
\tdefer g.checking.Store(false)
\tb, _ := batch.New(ctx, batch.WithConcurrencyNum[any](10))
''',
        '''func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
\tresult := make(map[string]uint16)
\tif g.checking.Swap(true) {
\t\treturn result, nil
\t}
\tdefer g.checking.Store(false)
\tif shouldCheckPrioritySequential(g.strategy, force) {
\t\tfor _, detour := range g.outbounds {
\t\t\ttag := detour.Tag()
\t\t\tif nested, isNested := detour.(*URLTest); isNested && nested.managedByParent {
\t\t\t\t_, _ = nested.group.urlTest(ctx, force)
\t\t\t\thistory := g.history.LoadURLTestHistory(RealTag(detour))
\t\t\t\tif history != nil {
\t\t\t\t\tresult[tag] = history.Delay
\t\t\t\t\tbreak
\t\t\t\t}
\t\t\t\tcontinue
\t\t\t}
\t\t\trealTag := RealTag(detour)
\t\t\thistory := g.history.LoadURLTestHistory(realTag)
\t\t\tif !force && history != nil && time.Since(history.Time) < g.interval {
\t\t\t\tresult[tag] = history.Delay
\t\t\t\tbreak
\t\t\t}
\t\t\tp, loaded := g.outbound.Outbound(realTag)
\t\t\tif !loaded {
\t\t\t\tcontinue
\t\t\t}
\t\t\ttestCtx, cancel := context.WithTimeout(ctx, g.timeout)
\t\t\tt, err := urltest.URLTest(testCtx, g.link, p)
\t\t\tcancel()
\t\t\tif err != nil {
\t\t\t\tg.logger.Debug("outbound ", tag, " unavailable: ", err)
\t\t\t\tg.history.DeleteURLTestHistory(realTag)
\t\t\t\tcontinue
\t\t\t}
\t\t\tg.logger.Debug("outbound ", tag, " available: ", t, "ms")
\t\t\tg.history.StoreURLTestHistory(realTag, &adapter.URLTestHistory{Time: time.Now(), Delay: t})
\t\t\tresult[tag] = t
\t\t\tbreak
\t\t}
\t\tg.performUpdateCheck()
\t\treturn result, nil
\t}
\tb, _ := batch.New(ctx, batch.WithConcurrencyNum[any](10))
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
''',
        '''func shouldCheckPrioritySequential(strategy string, force bool) bool {
\treturn strategy == "priority" && !force
}

func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
''',
    )
    patch_file(
        urltest,
        '''\tfor _, detour := range g.outbounds {
\t\ttag := detour.Tag()
\t\trealTag := RealTag(detour)
''',
        '''\tfor _, detour := range g.outbounds {
\t\ttag := detour.Tag()
\t\tif nested, isNested := detour.(*URLTest); isNested && nested.managedByParent {
\t\t\tif checked[tag] {
\t\t\t\tcontinue
\t\t\t}
\t\t\tchecked[tag] = true
\t\t\tb.Go(tag, func() (any, error) {
\t\t\t\t_, _ = nested.group.urlTest(ctx, true)
\t\t\t\thistory := g.history.LoadURLTestHistory(RealTag(detour))
\t\t\t\tif history != nil {
\t\t\t\t\tresultAccess.Lock()
\t\t\t\t\tresult[tag] = history.Delay
\t\t\t\t\tresultAccess.Unlock()
\t\t\t\t}
\t\t\t\treturn nil, nil
\t\t\t})
\t\t\tcontinue
\t\t}
\t\trealTag := RealTag(detour)
''',
    )
    patch_file(
        urltest,
        '''\t\t\ttestCtx, cancel := context.WithTimeout(g.ctx, C.TCPTimeout)
''',
        '''\t\t\ttestCtx, cancel := context.WithTimeout(g.ctx, g.timeout)
''',
    )
    patch_file(
        urltest,
        '''func (g *URLTestGroup) performUpdateCheck() {
\tvar updated bool
\tif outbound, exists := g.Select(N.NetworkTCP); outbound != nil && (g.selectedOutboundTCP == nil || (exists && outbound != g.selectedOutboundTCP)) {
\t\tif g.selectedOutboundTCP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundTCP = outbound
\t}
\tif outbound, exists := g.Select(N.NetworkUDP); outbound != nil && (g.selectedOutboundUDP == nil || (exists && outbound != g.selectedOutboundUDP)) {
\t\tif g.selectedOutboundUDP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundUDP = outbound
\t}
\tif updated {
\t\tg.interruptGroup.Interrupt(g.interruptExternalConnections)
\t}
}
''',
        '''func (g *URLTestGroup) performUpdateCheck() {
\tvar updated bool
\toutboundTCP, existsTCP := g.Select(N.NetworkTCP)
\tif outboundTCP == nil {
\t\tif g.strategy == "priority" && g.selectedOutboundTCP != nil {
\t\t\tg.selectedOutboundTCP = nil
\t\t\tupdated = true
\t\t}
\t} else if g.selectedOutboundTCP == nil || (existsTCP && outboundTCP != g.selectedOutboundTCP) {
\t\tif g.selectedOutboundTCP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundTCP = outboundTCP
\t}
\toutboundUDP, existsUDP := g.Select(N.NetworkUDP)
\tif outboundUDP == nil {
\t\tif g.strategy == "priority" && g.selectedOutboundUDP != nil {
\t\t\tg.selectedOutboundUDP = nil
\t\t\tupdated = true
\t\t}
\t} else if g.selectedOutboundUDP == nil || (existsUDP && outboundUDP != g.selectedOutboundUDP) {
\t\tif g.selectedOutboundUDP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundUDP = outboundUDP
\t}
\tif updated {
\t\tg.interruptGroup.Interrupt(g.interruptExternalConnections)
\t}
}
''',
    )

    strategy_test = root / "protocol" / "group" / "urltest_strategy_test.go"
    strategy_test.write_text('''package group

import "testing"

func TestFirstAvailableIndexUsesPriorityOrder(t *testing.T) {
\tindex, available := firstAvailableIndex([]bool{false, true, true})
\tif !available || index != 1 {
\t\tt.Fatalf("expected second candidate, got index=%d available=%v", index, available)
\t}
}

func TestFirstAvailableIndexIgnoresLatencyOrder(t *testing.T) {
\tindex, available := firstAvailableIndex([]bool{true, true})
\tif !available || index != 0 {
\t\tt.Fatalf("expected first listed candidate, got index=%d available=%v", index, available)
\t}
}

func TestFirstAvailableIndexReportsUnavailable(t *testing.T) {
\tindex, available := firstAvailableIndex([]bool{false, false})
\tif available || index != 0 {
\t\tt.Fatalf("expected no available candidate, got index=%d available=%v", index, available)
\t}
}

func TestPriorityCheckModes(t *testing.T) {
\tif !shouldCheckPrioritySequential("priority", false) {
\t\tt.Fatal("scheduled priority check must be sequential")
\t}
\tif shouldCheckPrioritySequential("priority", true) {
\t\tt.Fatal("forced priority check must be parallel")
\t}
\tif shouldCheckPrioritySequential("fastest", false) {
\t\tt.Fatal("fastest check must remain parallel")
\t}
}

func TestManagedURLTestDoesNotScheduleIndependentCheck(t *testing.T) {
\tmanaged := &URLTest{managedByParent: true}
\tmanaged.checkAfterFailure()
}
''')


if __name__ == "__main__":
    main()
