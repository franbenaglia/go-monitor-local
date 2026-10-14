// Package wshub pushes every snapshot to the connected WebSocket clients.
// The message flow, close codes and limits are specified in docs/PROTOCOL.md.
package wshub

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"gomonitor/agent/internal/apierror"
	"gomonitor/agent/internal/models"
)

// Application close codes.
const (
	CloseAuthTimeout    websocket.StatusCode = 4001
	CloseInvalidToken   websocket.StatusCode = 4003
	CloseTooManyMsgs    websocket.StatusCode = 4008
	CloseInvalidMessage websocket.StatusCode = 4400
)

const (
	maxMessageBytes = 4096
	rateWindow      = 10 * time.Second
	rateMaxMessages = 20
	writeTimeout    = 10 * time.Second
)

type Options struct {
	AgentID            string
	Token              string
	MaxClients         int
	CollectionInterval time.Duration
	Logger             *slog.Logger

	// Timings; zero values use the protocol defaults. Tests shorten them.
	AuthTimeout  time.Duration
	PingInterval time.Duration
	PingTimeout  time.Duration
}

// Hub tracks the connected clients.
type Hub struct {
	opts   Options
	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu      sync.Mutex
	closed  bool
	pending int                          // slots reserved for connections still upgrading
	conns   map[*websocket.Conn]struct{} // every open connection, authenticated or not
	clients map[*client]struct{}         // authenticated, receiving metrics
	last    []byte                       // last metrics message, for new clients
}

type client struct {
	conn    *websocket.Conn
	metrics chan []byte // holds only the most recent snapshot
	control chan []byte // pong replies
}

type inbound struct {
	Type  string `json:"type"`
	Token string `json:"token"`
}

func New(opts Options) *Hub {
	if opts.AuthTimeout == 0 {
		opts.AuthTimeout = 5 * time.Second
	}
	if opts.PingInterval == 0 {
		opts.PingInterval = 20 * time.Second
	}
	if opts.PingTimeout == 0 {
		opts.PingTimeout = 10 * time.Second
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Hub{
		opts: opts, ctx: ctx, cancel: cancel,
		conns:   map[*websocket.Conn]struct{}{},
		clients: map[*client]struct{}{},
	}
}

// Clients returns the number of authenticated clients.
func (h *Hub) Clients() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.clients)
}

// Broadcast sends a snapshot to every authenticated client. It never blocks:
// a client that has not read the previous snapshot gets it replaced.
func (h *Hub) Broadcast(snap *models.Snapshot) {
	msg, err := json.Marshal(struct {
		Type string           `json:"type"`
		Data *models.Snapshot `json:"data"`
	}{"metrics", snap})
	if err != nil {
		h.opts.Logger.Error("encode snapshot", "error", err)
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.last = msg
	for c := range h.clients {
		c.offer(msg)
	}
}

// offer replaces whatever snapshot is still waiting to be written.
func (c *client) offer(msg []byte) {
	for {
		select {
		case c.metrics <- msg:
			return
		default:
		}
		select {
		case <-c.metrics:
		default:
		}
	}
}

// ServeHTTP upgrades the request. The Origin header must have been validated
// by the caller.
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// The slot is reserved before upgrading so unauthenticated connections
	// count towards the limit too.
	if !h.reserve() {
		apierror.Write(w, http.StatusServiceUnavailable, apierror.TooManyConnections, "Too many WebSocket clients are connected")
		return
	}
	defer h.wg.Done()

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		h.release(nil)
		return
	}
	conn.SetReadLimit(maxMessageBytes)
	if !h.track(conn) {
		conn.Close(websocket.StatusGoingAway, "agent shutting down")
		return
	}
	defer h.release(conn)
	defer conn.CloseNow()

	h.serve(conn, r.RemoteAddr)
}

func (h *Hub) reserve() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed || len(h.conns)+h.pending >= h.opts.MaxClients {
		return false
	}
	h.pending++
	h.wg.Add(1)
	return true
}

func (h *Hub) track(conn *websocket.Conn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.pending--
	if h.closed {
		return false
	}
	h.conns[conn] = struct{}{}
	return true
}

func (h *Hub) release(conn *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if conn == nil {
		h.pending--
		return
	}
	delete(h.conns, conn)
}

func (h *Hub) serve(conn *websocket.Conn, remote string) {
	ctx, cancel := context.WithCancel(h.ctx)
	defer cancel()
	log := h.opts.Logger.With("remote", remote)

	if !h.authenticate(ctx, conn, log) {
		return
	}

	c := &client{conn: conn, metrics: make(chan []byte, 1), control: make(chan []byte, 4)}
	hello, _ := json.Marshal(map[string]any{
		"type":                        "authenticated",
		"agent_id":                    h.opts.AgentID,
		"protocol_version":            models.ProtocolVersion,
		"collection_interval_seconds": int(h.opts.CollectionInterval / time.Second),
	})
	if err := write(ctx, conn, hello); err != nil {
		return
	}

	h.mu.Lock()
	h.clients[c] = struct{}{}
	if h.last != nil {
		c.offer(h.last)
	}
	h.mu.Unlock()
	log.Info("websocket client connected")
	defer func() {
		h.mu.Lock()
		delete(h.clients, c)
		h.mu.Unlock()
		log.Info("websocket client disconnected")
	}()

	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		defer cancel()
		h.writeLoop(ctx, c)
	}()
	h.readLoop(ctx, c)
	cancel()
	<-writerDone
}

// authenticate waits for the first message, which must carry the token.
func (h *Hub) authenticate(ctx context.Context, conn *websocket.Conn, log *slog.Logger) bool {
	// A timer closes the connection with our own code; cancelling the read
	// context instead would make the library close it with a generic one.
	timer := time.AfterFunc(h.opts.AuthTimeout, func() {
		log.Debug("websocket authentication timed out")
		conn.Close(CloseAuthTimeout, "authentication timeout")
	})
	typ, data, err := conn.Read(ctx)
	if !timer.Stop() || err != nil {
		return false
	}

	var msg inbound
	if typ != websocket.MessageText || json.Unmarshal(data, &msg) != nil || msg.Type != "authenticate" {
		reject(ctx, conn, CloseInvalidMessage, apierror.InvalidParameter, "The first message must be of type authenticate")
		return false
	}
	if subtle.ConstantTimeCompare([]byte(msg.Token), []byte(h.opts.Token)) != 1 {
		log.Warn("websocket authentication failed")
		reject(ctx, conn, CloseInvalidToken, apierror.Unauthorized, "Invalid token")
		return false
	}
	return true
}

func (h *Hub) readLoop(ctx context.Context, c *client) {
	windowStart := time.Now()
	count := 0
	pong := []byte(`{"type":"pong"}`)
	for {
		typ, data, err := c.conn.Read(ctx)
		if err != nil {
			return // closed by the peer, by shutdown, or message too big
		}
		if now := time.Now(); now.Sub(windowStart) > rateWindow {
			windowStart, count = now, 0
		}
		if count++; count > rateMaxMessages {
			reject(ctx, c.conn, CloseTooManyMsgs, apierror.InvalidParameter, "Too many messages")
			return
		}

		var msg inbound
		if typ != websocket.MessageText || json.Unmarshal(data, &msg) != nil {
			reject(ctx, c.conn, CloseInvalidMessage, apierror.InvalidParameter, "Messages must be JSON text")
			return
		}
		switch msg.Type {
		case "ping":
			select {
			case c.control <- pong:
			default: // replies already queued; the client is not reading
			}
		default:
			reject(ctx, c.conn, CloseInvalidMessage, apierror.InvalidParameter, "Unknown message type")
			return
		}
	}
}

func (h *Hub) writeLoop(ctx context.Context, c *client) {
	ticker := time.NewTicker(h.opts.PingInterval)
	defer ticker.Stop()
	for {
		var msg []byte
		select {
		case <-ctx.Done():
			return
		case msg = <-c.control:
		case msg = <-c.metrics:
		case <-ticker.C:
			// Detects phones that left the network without closing.
			pingCtx, cancel := context.WithTimeout(ctx, h.opts.PingTimeout)
			err := c.conn.Ping(pingCtx)
			cancel()
			if err != nil {
				c.conn.CloseNow()
				return
			}
			continue
		}
		if err := write(ctx, c.conn, msg); err != nil {
			c.conn.CloseNow()
			return
		}
	}
}

func write(ctx context.Context, conn *websocket.Conn, msg []byte) error {
	ctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return conn.Write(ctx, websocket.MessageText, msg)
}

// reject tells the client why and closes with an application code.
func reject(ctx context.Context, conn *websocket.Conn, code websocket.StatusCode, errCode, message string) {
	body, _ := json.Marshal(struct {
		Type  string          `json:"type"`
		Error apierror.Detail `json:"error"`
	}{"error", apierror.Detail{Code: errCode, Message: message}})
	_ = write(ctx, conn, body)
	conn.Close(code, message)
}

// Close disconnects every client with "going away" and waits for the
// handlers to finish. New connections are refused afterwards.
func (h *Hub) Close() {
	h.mu.Lock()
	h.closed = true
	conns := make([]*websocket.Conn, 0, len(h.conns))
	for c := range h.conns {
		conns = append(conns, c)
	}
	h.mu.Unlock()

	var closing sync.WaitGroup
	for _, c := range conns {
		closing.Add(1)
		go func() {
			defer closing.Done()
			c.Close(websocket.StatusGoingAway, "agent shutting down")
		}()
	}
	closing.Wait()
	h.cancel()
	h.wg.Wait()
}
