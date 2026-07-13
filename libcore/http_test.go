package libcore

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

const raceTestTimeout = 2 * time.Second

type trackingReadCloser struct {
	reader io.Reader
	closed chan struct{}
	once   sync.Once
}

func newTrackingReadCloser(content string) *trackingReadCloser {
	return &trackingReadCloser{
		reader: strings.NewReader(content),
		closed: make(chan struct{}),
	}
}

func (r *trackingReadCloser) Read(p []byte) (int, error) {
	return r.reader.Read(p)
}

func (r *trackingReadCloser) Close() error {
	r.once.Do(func() { close(r.closed) })
	return nil
}

type httpRaceResult struct {
	response *http.Response
	err      error
}

func waitForSignal(t *testing.T, signal <-chan struct{}, description string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(raceTestTimeout):
		t.Fatalf("timed out waiting for %s", description)
	}
}

func waitForWorkers(t *testing.T, workers *sync.WaitGroup) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		workers.Wait()
		close(done)
	}()
	waitForSignal(t, done, "request functions")
}

func waitForRaceResult(t *testing.T, resultCh <-chan httpRaceResult) httpRaceResult {
	t.Helper()
	select {
	case result := <-resultCh:
		return result
	case <-time.After(raceTestTimeout):
		t.Fatal("timed out waiting for request race")
		return httpRaceResult{}
	}
}

func assertBodyClosed(t *testing.T, body *trackingReadCloser) {
	t.Helper()
	waitForSignal(t, body.closed, "response body close")
}

func TestRaceHTTPRequestsTimeoutCancelsWorkersAndClosesBodies(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	firstBody := newTrackingReadCloser("first")
	secondBody := newTrackingReadCloser("second")
	var workers sync.WaitGroup
	workers.Add(2)

	blockedRequest := func(body *trackingReadCloser) requestFunc {
		return func(requestCtx context.Context) (*http.Response, error) {
			defer workers.Done()
			<-requestCtx.Done()
			return &http.Response{StatusCode: http.StatusOK, Body: body}, nil
		}
	}

	resultCh := make(chan httpRaceResult, 1)
	go func() {
		response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
			{label: "first", request: blockedRequest(firstBody)},
			{label: "second", request: blockedRequest(secondBody)},
		})
		resultCh <- httpRaceResult{response: response, err: err}
	}()

	result := waitForRaceResult(t, resultCh)
	if result.response != nil {
		t.Fatal("timeout returned a response")
	}
	if !errors.Is(result.err, context.DeadlineExceeded) {
		t.Fatalf("timeout error = %v, want context deadline exceeded", result.err)
	}
	waitForWorkers(t, &workers)
	assertBodyClosed(t, firstBody)
	assertBodyClosed(t, secondBody)
}

func TestRaceHTTPRequestsEmptyResponse(t *testing.T) {
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "empty",
			request: func(context.Context) (*http.Response, error) {
				return nil, nil
			},
		},
	})

	if response != nil {
		t.Fatal("empty result returned a response")
	}
	if err == nil || !strings.Contains(err.Error(), "empty response") {
		t.Fatalf("empty result error = %v, want empty response", err)
	}
}

func TestRaceHTTPRequestsWorkerPanicIsReturned(t *testing.T) {
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "panicked",
			request: func(context.Context) (*http.Response, error) {
				panic("worker panic")
			},
		},
	})

	if response != nil {
		t.Fatal("panicked request returned a response")
	}
	if err == nil || !strings.Contains(err.Error(), "panicked: http panic: worker panic") {
		t.Fatalf("panic error = %v, want labeled worker panic", err)
	}
}

func TestRaceHTTPRequestsFirstSuccessCancelsLoserAndKeepsWinnerReadable(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	winnerBody := newTrackingReadCloser("winner")
	lateBody := newTrackingReadCloser("late")
	lateStarted := make(chan struct{})
	lateReturned := make(chan struct{})
	var winnerRequestDone <-chan struct{}

	response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
		{
			label: "winner",
			request: func(requestCtx context.Context) (*http.Response, error) {
				winnerRequestDone = requestCtx.Done()
				<-lateStarted
				return &http.Response{StatusCode: http.StatusOK, Body: winnerBody}, nil
			},
		},
		{
			label: "late",
			request: func(requestCtx context.Context) (*http.Response, error) {
				close(lateStarted)
				<-requestCtx.Done()
				close(lateReturned)
				return &http.Response{StatusCode: http.StatusOK, Body: lateBody}, nil
			},
		},
	})
	if err != nil {
		cancel()
		t.Fatalf("request race failed: %v", err)
	}
	if response == nil {
		cancel()
		t.Fatal("request race returned no response")
	}

	waitForSignal(t, lateReturned, "late request function")
	assertBodyClosed(t, lateBody)
	cancel()

	select {
	case <-winnerBody.closed:
		t.Fatal("winning response body was closed")
	case <-winnerRequestDone:
		t.Fatal("winning request context was cancelled before body close")
	default:
	}

	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read winning body: %v", err)
	}
	if string(content) != "winner" {
		t.Fatalf("winning body = %q, want winner", content)
	}
	if err := response.Body.Close(); err != nil {
		t.Fatalf("close winning body: %v", err)
	}
	waitForSignal(t, winnerRequestDone, "winning request cancellation")
}

func TestRaceHTTPRequestsRealWinnerBodySurvivesParentCancellation(t *testing.T) {
	loserStarted := make(chan struct{})
	loserStopped := make(chan struct{})
	releaseWinnerBody := make(chan struct{})
	var releaseOnce sync.Once
	releaseWinner := func() { releaseOnce.Do(func() { close(releaseWinnerBody) }) }

	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/winner":
			<-loserStarted
			writer.WriteHeader(http.StatusOK)
			writer.(http.Flusher).Flush()
			<-releaseWinnerBody
			_, _ = io.WriteString(writer, "winner")
		case "/loser":
			close(loserStarted)
			<-request.Context().Done()
			close(loserStopped)
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()
	defer releaseWinner()

	ctx, cancel := context.WithCancel(context.Background())
	request := func(path string) requestFunc {
		return func(requestCtx context.Context) (*http.Response, error) {
			req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, server.URL+path, nil)
			if err != nil {
				return nil, err
			}
			return server.Client().Do(req)
		}
	}

	response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
		{label: "winner", request: request("/winner")},
		{label: "loser", request: request("/loser")},
	})
	if err != nil {
		cancel()
		t.Fatalf("request race failed: %v", err)
	}
	if response == nil {
		cancel()
		t.Fatal("request race returned no real response")
	}
	waitForSignal(t, loserStopped, "real losing request cancellation")

	cancel()
	releaseWinner()
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read real winning body after parent cancellation: %v", err)
	}
	if string(content) != "winner" {
		t.Fatalf("real winning body = %q, want winner", content)
	}
	if err := response.Body.Close(); err != nil {
		t.Fatalf("close real winning body: %v", err)
	}
}

func TestRaceHTTPRequestsFailureAndDeadlineAreRetained(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	workerErr := errors.New("worker failed")
	failureReturned := make(chan struct{})
	response, err := raceHTTPRequests(ctx, []labeledRequestFunc{
		{
			label: "failed",
			request: func(context.Context) (*http.Response, error) {
				close(failureReturned)
				return nil, workerErr
			},
		},
		{
			label: "blocked",
			request: func(requestCtx context.Context) (*http.Response, error) {
				<-failureReturned
				<-requestCtx.Done()
				return nil, requestCtx.Err()
			},
		},
	})

	if response != nil {
		t.Fatal("failed request race returned a response")
	}
	if !errors.Is(err, workerErr) {
		t.Fatalf("request race error = %v, want worker failure", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("request race error = %v, want deadline exceeded", err)
	}
}

func TestRaceHTTPRequestsAllFailuresAreJoined(t *testing.T) {
	firstErr := errors.New("first failure")
	secondErr := errors.New("second failure")
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "first",
			request: func(context.Context) (*http.Response, error) {
				return nil, firstErr
			},
		},
		{
			label: "second",
			request: func(context.Context) (*http.Response, error) {
				return nil, secondErr
			},
		},
	})

	if response != nil {
		t.Fatal("failed request race returned a response")
	}
	if err == nil {
		t.Fatal("all-failed request race returned a nil error")
	}
	if !errors.Is(err, firstErr) || !errors.Is(err, secondErr) {
		t.Fatalf("joined error = %v, want both worker errors", err)
	}
}

func TestRaceHTTPRequestsRejectsNonOKAndClosesBody(t *testing.T) {
	body := newTrackingReadCloser("rejected")
	response, err := raceHTTPRequests(context.Background(), []labeledRequestFunc{
		{
			label: "http(s)",
			request: func(context.Context) (*http.Response, error) {
				return &http.Response{
					StatusCode: http.StatusTeapot,
					Status:     "418 I'm a teapot",
					Body:       body,
				}, nil
			},
		},
	})

	if response != nil {
		t.Fatal("non-200 request returned a response")
	}
	if err == nil || !strings.Contains(err.Error(), "rejected") {
		t.Fatalf("non-200 error = %v, want response body text", err)
	}
	assertBodyClosed(t, body)
}
