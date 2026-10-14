package wshub

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"gomonitor/agent/internal/models"
)

const testToken = "secret-token"

type message struct {
	Type                      string           `json:"type"`
	AgentID                   string           `json:"agent_id"`
	ProtocolVersion           int              `json:"protocol_version"`
	CollectionIntervalSeconds int              `json:"collection_interval_seconds"`
	Data                      *models.Snapshot `json:"data"`
	Error                     *struct {
		Code string `json:"code"`
	} `json:"error"`
}

func newHub(t *testing.T, mutate func(*Options)) (*Hub, string) {
	t.Helper()
	opts := Options{
		AgentID: "test-agent", Token: testToken, MaxClients: 4, CollectionInterval: 5 * time.Second,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	if mutate != nil {
		mutate(&opts)
	}
	hub := New(opts)
	srv := httptest.NewServer(hub)
	t.Cleanup(func() {
		hub.Close()
		srv.Close()
	})
	return hub, "ws" + strings.TrimPrefix(srv.URL, "http")
}

func testCtx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func dial(t *testing.T, url string) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.Dial(testCtx(t), url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

func send(t *testing.T, conn *websocket.Conn, text string) {
	t.Helper()
	if err := conn.Write(testCtx(t), websocket.MessageText, []byte(text)); err != nil {
		t.Fatalf("write: %v", err)
	}
}

func receive(t *testing.T, conn *websocket.Conn) message {
	t.Helper()
	_, data, err := conn.Read(testCtx(t))
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var m message
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("invalid JSON %q: %v", data, err)
	}
	return m
}

// expectClose reads until the connection closes and returns the close code,
// along with the messages received before it.
func expectClose(t *testing.T, conn *websocket.Conn) (websocket.StatusCode, []message) {
	t.Helper()
	var msgs []message
	for {
		_, data, err := conn.Read(testCtx(t))
		if err != nil {
			code := websocket.CloseStatus(err)
			if code == -1 {
				t.Fatalf("connection ended without a close frame: %v", err)
			}
			return code, msgs
		}
		var m message
		_ = json.Unmarshal(data, &m)
		msgs = append(msgs, m)
	}
}

func authenticate(t *testing.T, conn *websocket.Conn) message {
	t.Helper()
	send(t, conn, `{"type":"authenticate","token":"`+testToken+`"}`)
	m := receive(t, conn)
	if m.Type != "authenticated" {
		t.Fatalf("expected authenticated, got %+v", m)
	}
	return m
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func snapshot(uptime uint64) *models.Snapshot {
	return &models.Snapshot{AgentID: "test-agent", UptimeSeconds: uptime, Readings: []models.Reading{}}
}

func TestAuthenticationAndMetrics(t *testing.T) {
	hub, url := newHub(t, nil)
	hub.Broadcast(snapshot(1)) // collected before anyone connected

	conn := dial(t, url)
	hello := authenticate(t, conn)
	if hello.AgentID != "test-agent" || hello.ProtocolVersion != models.ProtocolVersion || hello.CollectionIntervalSeconds != 5 {
		t.Fatalf("unexpected authenticated message: %+v", hello)
	}

	// The last snapshot arrives right away, without waiting for a cycle.
	first := receive(t, conn)
	if first.Type != "metrics" || first.Data == nil || first.Data.UptimeSeconds != 1 {
		t.Fatalf("expected the last snapshot, got %+v", first)
	}

	hub.Broadcast(snapshot(2))
	next := receive(t, conn)
	if next.Type != "metrics" || next.Data.UptimeSeconds != 2 {
		t.Fatalf("expected the new snapshot, got %+v", next)
	}
}

func TestInvalidTokenIsRejected(t *testing.T) {
	hub, url := newHub(t, nil)
	conn := dial(t, url)
	send(t, conn, `{"type":"authenticate","token":"wrong"}`)

	code, msgs := expectClose(t, conn)
	if code != CloseInvalidToken {
		t.Fatalf("close code %d, want %d", code, CloseInvalidToken)
	}
	if len(msgs) != 1 || msgs[0].Type != "error" || msgs[0].Error == nil || msgs[0].Error.Code != "unauthorized" {
		t.Fatalf("expected one unauthorized error message, got %+v", msgs)
	}
	if hub.Clients() != 0 {
		t.Fatal("a rejected client must not be registered")
	}
}

func TestUnauthenticatedConnectionIsClosed(t *testing.T) {
	hub, url := newHub(t, func(o *Options) { o.AuthTimeout = 100 * time.Millisecond })
	hub.Broadcast(snapshot(1))
	conn := dial(t, url)

	start := time.Now()
	code, msgs := expectClose(t, conn)
	if code != CloseAuthTimeout {
		t.Fatalf("close code %d, want %d", code, CloseAuthTimeout)
	}
	if len(msgs) != 0 {
		t.Fatalf("no data may be sent before authentication, got %+v", msgs)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("took %v to close an unauthenticated connection", elapsed)
	}
}

func TestFirstMessageMustAuthenticate(t *testing.T) {
	for name, first := range map[string]string{
		"ping first": `{"type":"ping"}`,
		"not json":   `hello`,
		"no type":    `{"token":"` + testToken + `"}`,
	} {
		t.Run(name, func(t *testing.T) {
			_, url := newHub(t, nil)
			conn := dial(t, url)
			send(t, conn, first)
			if code, _ := expectClose(t, conn); code != CloseInvalidMessage {
				t.Fatalf("close code %d, want %d", code, CloseInvalidMessage)
			}
		})
	}
}

func TestPingPong(t *testing.T) {
	_, url := newHub(t, nil)
	conn := dial(t, url)
	authenticate(t, conn)
	send(t, conn, `{"type":"ping"}`)
	if m := receive(t, conn); m.Type != "pong" {
		t.Fatalf("expected pong, got %+v", m)
	}
}

func TestUnknownMessageTypeClosesConnection(t *testing.T) {
	_, url := newHub(t, nil)
	conn := dial(t, url)
	authenticate(t, conn)
	send(t, conn, `{"type":"run_command","command":"rm -rf /"}`)
	if code, _ := expectClose(t, conn); code != CloseInvalidMessage {
		t.Fatalf("close code %d, want %d", code, CloseInvalidMessage)
	}
}

func TestMessageSizeLimit(t *testing.T) {
	_, url := newHub(t, nil)
	conn := dial(t, url)
	authenticate(t, conn)
	send(t, conn, `{"type":"ping","padding":"`+strings.Repeat("x", maxMessageBytes)+`"}`)
	if code, _ := expectClose(t, conn); code != websocket.StatusMessageTooBig {
		t.Fatalf("close code %d, want %d", code, websocket.StatusMessageTooBig)
	}
}

func TestMessageRateLimit(t *testing.T) {
	_, url := newHub(t, nil)
	conn := dial(t, url)
	authenticate(t, conn)
	for range rateMaxMessages + 1 {
		// The connection may already be closing while we write the last ones.
		_ = conn.Write(testCtx(t), websocket.MessageText, []byte(`{"type":"ping"}`))
	}
	if code, _ := expectClose(t, conn); code != CloseTooManyMsgs {
		t.Fatalf("close code %d, want %d", code, CloseTooManyMsgs)
	}
}

func TestConnectionLimit(t *testing.T) {
	hub, url := newHub(t, func(o *Options) { o.MaxClients = 2 })
	a := dial(t, url)
	authenticate(t, a)
	dial(t, url) // connected but not authenticated: still uses a slot

	_, resp, err := websocket.Dial(testCtx(t), url, nil)
	if err == nil {
		t.Fatal("a third connection must be refused")
	}
	if resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %+v", resp)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "too_many_connections") {
		t.Fatalf("unexpected body: %s", body)
	}

	// Closing one frees its slot.
	a.Close(websocket.StatusNormalClosure, "")
	waitFor(t, "the slot to be released", func() bool { return hub.Clients() == 0 })
	waitFor(t, "a new connection to be accepted", func() bool {
		c, _, err := websocket.Dial(testCtx(t), url, nil)
		if err != nil {
			return false
		}
		c.CloseNow()
		return true
	})
}

func TestSeveralClientsReceiveSnapshots(t *testing.T) {
	hub, url := newHub(t, nil)
	conns := []*websocket.Conn{dial(t, url), dial(t, url), dial(t, url)}
	for _, c := range conns {
		authenticate(t, c)
	}
	waitFor(t, "three clients", func() bool { return hub.Clients() == 3 })
	hub.Broadcast(snapshot(7))
	for i, c := range conns {
		if m := receive(t, c); m.Type != "metrics" || m.Data.UptimeSeconds != 7 {
			t.Fatalf("client %d got %+v", i, m)
		}
	}
}

func TestSlowClientDoesNotBlockAndGetsLatest(t *testing.T) {
	hub, url := newHub(t, nil)
	slow := dial(t, url)
	authenticate(t, slow)
	waitFor(t, "the client to register", func() bool { return hub.Clients() == 1 })

	// The client reads nothing while the agent keeps producing. The messages
	// are large enough to fill the socket buffers, so the writer really stalls.
	const total = 1000
	padding := make([]models.Reading, 400)
	for i := range padding {
		padding[i] = models.Reading{SeriesID: "padding.series.with.a.long.name", Value: float64(i)}
	}
	// A Broadcast that waited for the client would never finish, since the
	// client only starts reading after the loop; the timeout is just a bound.
	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := range total {
			snap := snapshot(uint64(i + 1))
			snap.Readings = padding
			hub.Broadcast(snap)
		}
	}()
	select {
	case <-done:
	case <-time.After(30 * time.Second):
		t.Fatal("Broadcast blocked on a slow client")
	}

	// When it finally reads, it catches up to the newest snapshot without
	// having to read all of the ones it missed.
	read := 0
	for {
		m := receive(t, slow)
		read++
		if m.Data != nil && m.Data.UptimeSeconds == total {
			break
		}
		if read > total/2 {
			t.Fatalf("old snapshots were queued instead of replaced")
		}
	}
}

func TestDeadConnectionIsDetected(t *testing.T) {
	hub, url := newHub(t, func(o *Options) {
		o.PingInterval = 50 * time.Millisecond
		o.PingTimeout = 100 * time.Millisecond
	})
	conn := dial(t, url)
	authenticate(t, conn)
	waitFor(t, "the client to register", func() bool { return hub.Clients() == 1 })

	// From here the client never reads, so it never answers pings: the same
	// as a phone that left the Wi-Fi without closing the socket.
	waitFor(t, "the dead client to be dropped", func() bool { return hub.Clients() == 0 })
}

func TestClientDisconnectIsHandled(t *testing.T) {
	hub, url := newHub(t, nil)
	conn := dial(t, url)
	authenticate(t, conn)
	waitFor(t, "the client to register", func() bool { return hub.Clients() == 1 })

	conn.CloseNow() // abrupt, no close handshake
	waitFor(t, "the client to be removed", func() bool { return hub.Clients() == 0 })
	hub.Broadcast(snapshot(1)) // must not panic or block with no clients
}

func TestCloseDisconnectsClients(t *testing.T) {
	hub, url := newHub(t, nil)
	authed := dial(t, url)
	authenticate(t, authed)
	pending := dial(t, url) // never authenticates
	waitFor(t, "the client to register", func() bool { return hub.Clients() == 1 })

	codes := make(chan websocket.StatusCode, 2)
	for _, c := range []*websocket.Conn{authed, pending} {
		go func() {
			_, _, err := c.Read(context.Background())
			codes <- websocket.CloseStatus(err)
		}()
	}

	done := make(chan struct{})
	go func() { hub.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not return")
	}
	for range 2 {
		if code := <-codes; code != websocket.StatusGoingAway {
			t.Fatalf("close code %d, want %d", code, websocket.StatusGoingAway)
		}
	}

	_, resp, err := websocket.Dial(testCtx(t), url, nil)
	if err == nil || resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("connections after Close must be refused, got %v %+v", err, resp)
	}
}
