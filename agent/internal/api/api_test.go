package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"gomonitor/agent/internal/apierror"
	"gomonitor/agent/internal/collector"
	"gomonitor/agent/internal/config"
	"gomonitor/agent/internal/models"
	"gomonitor/agent/internal/storage"
	"gomonitor/agent/internal/wshub"
)

const testToken = "super-secret-test-token"

var (
	quiet = slog.New(slog.NewTextHandler(io.Discard, nil))
	// now is a bucket boundary for every period, which keeps expectations simple.
	now = time.Unix(1_800_000_000, 0).UTC()
)

type env struct {
	t      *testing.T
	url    string
	store  *storage.Store
	latest *models.Snapshot
}

type options struct {
	noSystem bool
	noSample bool
	origins  []string
}

func newEnv(t *testing.T, o options) *env {
	t.Helper()
	store, err := storage.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.APIToken = testToken
	cfg.AgentID = "test-agent"
	cfg.CORSAllowedOrigins = o.origins

	e := &env{t: t, store: store}
	var system *collector.System
	collectors := []collector.Collector{collector.NewSimulated()}
	if !o.noSystem {
		system = collector.NewSystem(5)
		collectors = append(collectors, system)
	}
	var series []models.SeriesDescriptor
	for _, c := range collectors {
		series = append(series, c.Series()...)
	}
	if err := store.RegisterSeries(context.Background(), series); err != nil {
		t.Fatal(err)
	}
	if !o.noSample {
		e.latest = &models.Snapshot{
			AgentID: "test-agent", Hostname: "host", OperatingSystem: "linux", Architecture: "amd64",
			Timestamp: now, UptimeSeconds: 99,
			Readings: []models.Reading{{SeriesID: models.SeriesCPUTotal, Value: 12.5}},
		}
		if !o.noSystem {
			e.latest.CPU = &models.CPU{TotalPercent: 12.5, CorePercentages: []float64{10, 15}}
			e.latest.Network = &models.Network{BytesSentTotal: 10, BytesReceivedTotal: 20}
			e.latest.Disks = []models.Disk{{MountPoint: "/", Filesystem: "ext4", TotalBytes: 100}}
			e.latest.Processes = []models.Process{
				{PID: 1, Name: "a", CPUPercent: 30, MemoryPercent: 1},
				{PID: 2, Name: "b", CPUPercent: 20, MemoryPercent: 9},
				{PID: 3, Name: "c", CPUPercent: 10, MemoryPercent: 5},
			}
		}
	}

	hub := wshub.New(wshub.Options{AgentID: cfg.AgentID, Token: testToken, MaxClients: 4,
		CollectionInterval: cfg.CollectionInterval, Logger: quiet})
	srv := httptest.NewServer(NewHandler(Deps{
		Config: cfg, Version: "test", Latest: func() *models.Snapshot { return e.latest },
		History: store, System: system, WebSocket: hub, Logger: quiet, Now: func() time.Time { return now },
	}))
	t.Cleanup(func() {
		hub.Close()
		srv.Close()
		store.Close()
	})
	e.url = srv.URL
	return e
}

type response struct {
	status int
	header http.Header
	body   []byte
}

func (r response) errorCode(t *testing.T) string {
	t.Helper()
	var body apierror.Body
	if err := json.Unmarshal(r.body, &body); err != nil || body.Error.Code == "" || body.Error.Message == "" {
		t.Fatalf("not a well-formed error body (status %d): %s", r.status, r.body)
	}
	return body.Error.Code
}

func (r response) decode(t *testing.T, into any) {
	t.Helper()
	if err := json.Unmarshal(r.body, into); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, r.body)
	}
}

func (e *env) do(method, path string, headers map[string]string, body io.Reader) response {
	e.t.Helper()
	req, err := http.NewRequest(method, e.url+path, body)
	if err != nil {
		e.t.Fatal(err)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return response{status: resp.StatusCode, header: resp.Header, body: data}
}

// get performs an authenticated GET.
func (e *env) get(path string) response {
	e.t.Helper()
	return e.do(http.MethodGet, path, map[string]string{"Authorization": "Bearer " + testToken}, nil)
}

func expectError(t *testing.T, r response, status int, code string) {
	t.Helper()
	if r.status != status {
		t.Fatalf("status %d, want %d: %s", r.status, status, r.body)
	}
	if got := r.errorCode(t); got != code {
		t.Fatalf("error code %q, want %q", got, code)
	}
}

var privatePaths = []string{
	"/api/v1/status", "/api/v1/series", "/api/v1/history", "/api/v1/processes", "/api/v1/system",
	"/api/v1/network", "/api/v1/disks", "/api/v1/config/public", "/api/v1/interfaces",
}

func TestHealthNeedsNoToken(t *testing.T) {
	e := newEnv(t, options{})
	r := e.do(http.MethodGet, "/api/v1/health", nil, nil)
	if r.status != http.StatusOK {
		t.Fatalf("status %d: %s", r.status, r.body)
	}
	var body struct {
		Status          string `json:"status"`
		AgentID         string `json:"agent_id"`
		ProtocolVersion int    `json:"protocol_version"`
	}
	r.decode(t, &body)
	if body.Status != "ok" || body.AgentID != "test-agent" || body.ProtocolVersion != models.ProtocolVersion {
		t.Fatalf("unexpected body: %s", r.body)
	}
	if ct := r.header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("content type %q", ct)
	}
}

func TestAuthentication(t *testing.T) {
	e := newEnv(t, options{})
	bad := map[string]map[string]string{
		"no header":     nil,
		"wrong token":   {"Authorization": "Bearer nope"},
		"prefix only":   {"Authorization": "Bearer "},
		"token prefix":  {"Authorization": "Bearer " + testToken[:10]},
		"token suffix":  {"Authorization": "Bearer " + testToken + "x"},
		"basic scheme":  {"Authorization": "Basic " + testToken},
		"no scheme":     {"Authorization": testToken},
		"lower scheme":  {"Authorization": "bearer " + testToken},
		"other header":  {"X-Token": testToken},
		"query ignored": nil,
	}
	for _, path := range privatePaths {
		for name, headers := range bad {
			p := path
			if name == "query ignored" {
				p += "?token=" + testToken // tokens in the URL are never accepted
			}
			expectError(t, e.do(http.MethodGet, p, headers, nil), http.StatusUnauthorized, apierror.Unauthorized)
		}
		if r := e.get(path); r.status != http.StatusOK {
			t.Errorf("%s with a valid token: status %d: %s", path, r.status, r.body)
		}
	}
}

func TestTokenIsNeverReturned(t *testing.T) {
	e := newEnv(t, options{})
	for _, path := range append([]string{"/api/v1/health"}, privatePaths...) {
		if r := e.get(path); strings.Contains(string(r.body), testToken) {
			t.Errorf("%s leaks the token", path)
		}
	}
}

func TestRoutingErrors(t *testing.T) {
	e := newEnv(t, options{})
	auth := map[string]string{"Authorization": "Bearer " + testToken}

	expectError(t, e.get("/api/v1/nope"), http.StatusNotFound, apierror.NotFound)
	expectError(t, e.get("/"), http.StatusNotFound, apierror.NotFound)
	expectError(t, e.get("/api/v2/status"), http.StatusNotFound, apierror.NotFound)

	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch} {
		r := e.do(method, "/api/v1/status", auth, nil)
		expectError(t, r, http.StatusMethodNotAllowed, apierror.MethodNotAllowed)
		if r.header.Get("Allow") != "GET" {
			t.Errorf("%s: missing Allow header", method)
		}
	}

	big := strings.NewReader(strings.Repeat("x", maxBodyBytes+1))
	expectError(t, e.do(http.MethodGet, "/api/v1/status", auth, big), http.StatusRequestEntityTooLarge, apierror.PayloadTooLarge)

	long := "/api/v1/history?series=" + strings.Repeat("a", maxURLBytes)
	expectError(t, e.get(long), http.StatusBadRequest, apierror.InvalidParameter)
}

func TestNotReadyBeforeFirstSample(t *testing.T) {
	e := newEnv(t, options{noSample: true})
	for _, path := range []string{"/api/v1/status", "/api/v1/processes", "/api/v1/network", "/api/v1/disks"} {
		expectError(t, e.get(path), http.StatusServiceUnavailable, apierror.NotReady)
	}
	// These do not depend on a sample.
	for _, path := range []string{"/api/v1/health", "/api/v1/series", "/api/v1/history", "/api/v1/config/public", "/api/v1/system"} {
		if r := e.get(path); r.status != http.StatusOK {
			t.Errorf("%s: status %d: %s", path, r.status, r.body)
		}
	}
}

func TestStatusReturnsLatestSnapshot(t *testing.T) {
	e := newEnv(t, options{})
	var snap models.Snapshot
	r := e.get("/api/v1/status")
	r.decode(t, &snap)
	if snap.AgentID != "test-agent" || snap.UptimeSeconds != 99 || snap.CPU == nil || len(snap.Processes) != 3 {
		t.Fatalf("unexpected snapshot: %s", r.body)
	}
	if !strings.Contains(string(r.body), `"timestamp":"2027-01-15T08:00:00Z"`) {
		t.Fatalf("timestamp must be RFC 3339 UTC in seconds: %s", r.body)
	}
}

func TestSensorOnlyAgent(t *testing.T) {
	e := newEnv(t, options{noSystem: true})
	for _, path := range []string{"/api/v1/system", "/api/v1/network", "/api/v1/disks", "/api/v1/processes"} {
		expectError(t, e.get(path), http.StatusNotFound, apierror.NotFound)
	}

	r := e.get("/api/v1/status")
	if r.status != http.StatusOK {
		t.Fatalf("status %d", r.status)
	}
	for _, block := range []string{`"cpu"`, `"memory"`, `"disks"`, `"network"`, `"processes"`} {
		if strings.Contains(string(r.body), block) {
			t.Errorf("a sensor-only agent must omit %s: %s", block, r.body)
		}
	}

	// The default history series are system ones; none exist here.
	var hist historyResponse
	e.get("/api/v1/history").decode(t, &hist)
	if len(hist.Series) != 0 {
		t.Fatalf("expected no default series, got %+v", hist.Series)
	}
	e.get("/api/v1/history?series=sim.temperature").decode(t, &hist)
	if len(hist.Series) != 1 || hist.Series[0].Unit != models.UnitCelsius {
		t.Fatalf("sensor history must work without the system collector: %+v", hist.Series)
	}
}

type historyResponse struct {
	Period        string    `json:"period"`
	From          time.Time `json:"from"`
	To            time.Time `json:"to"`
	BucketSeconds int64     `json:"bucket_seconds"`
	Series        []struct {
		ID     string                `json:"id"`
		Unit   string                `json:"unit"`
		Points []models.HistoryPoint `json:"points"`
	} `json:"series"`
}

func TestHistoryPeriodValidation(t *testing.T) {
	e := newEnv(t, options{})
	valid := map[string]struct {
		bucket int64
		length time.Duration
	}{
		"15m": {5, 15 * time.Minute},
		"1h":  {10, time.Hour},
		"6h":  {60, 6 * time.Hour},
		"24h": {300, 24 * time.Hour},
		"7d":  {1800, 7 * 24 * time.Hour},
	}
	for name, want := range valid {
		var hist historyResponse
		r := e.get("/api/v1/history?period=" + name)
		if r.status != http.StatusOK {
			t.Fatalf("period %s: status %d: %s", name, r.status, r.body)
		}
		r.decode(t, &hist)
		if hist.Period != name || hist.BucketSeconds != want.bucket || hist.To.Sub(hist.From) != want.length {
			t.Errorf("period %s: %+v", name, hist)
		}
		if hist.To.Before(now) || hist.To.Sub(now) > time.Duration(want.bucket)*time.Second {
			t.Errorf("period %s: range must end at the bucket in progress, got to=%v", name, hist.To)
		}
	}

	// No period means 15 minutes.
	var hist historyResponse
	e.get("/api/v1/history").decode(t, &hist)
	if hist.Period != "15m" || len(hist.Series) != 4 {
		t.Fatalf("unexpected defaults: %+v", hist)
	}

	for _, bad := range []string{"", "2h", "15M", "15", "1d", "30d", "-1h", "1h%20", "15m%3BDROP", "999999h"} {
		expectError(t, e.get("/api/v1/history?period="+bad), http.StatusBadRequest, apierror.InvalidPeriod)
	}
}

func TestMalformedQueryIsRejected(t *testing.T) {
	e := newEnv(t, options{})
	// Go drops parameters containing a raw semicolon; without an explicit
	// check this request would silently fall back to the defaults.
	for _, path := range []string{"/api/v1/history?period=15m;DROP", "/api/v1/history?period=%zz", "/api/v1/processes?limit=5;x"} {
		expectError(t, e.get(path), http.StatusBadRequest, apierror.InvalidParameter)
	}
}

func TestHistorySeriesValidation(t *testing.T) {
	e := newEnv(t, options{})
	expectError(t, e.get("/api/v1/history?series=does.not.exist"), http.StatusNotFound, apierror.UnknownSeries)
	for _, bad := range []string{"", "cpu.total_percent,", "CPU", "a%20b", "x'%20OR%201=1", strings.Repeat("a", 65)} {
		expectError(t, e.get("/api/v1/history?series="+bad), http.StatusBadRequest, apierror.InvalidParameter)
	}

	// More series than the limit, all of them valid and distinct.
	var many []models.SeriesDescriptor
	var ids []string
	for i := range maxHistorySeries + 1 {
		id := "extra.s" + string(rune('a'+i))
		ids = append(ids, id)
		many = append(many, models.SeriesDescriptor{ID: id, Collector: "extra", Name: id, Kind: "generic", Unit: "count"})
	}
	if err := e.store.RegisterSeries(context.Background(), many); err != nil {
		t.Fatal(err)
	}
	expectError(t, e.get("/api/v1/history?series="+strings.Join(ids, ",")), http.StatusBadRequest, apierror.InvalidParameter)
	if r := e.get("/api/v1/history?series=" + strings.Join(ids[:maxHistorySeries], ",")); r.status != http.StatusOK {
		t.Fatalf("the maximum number of series must be accepted: %d %s", r.status, r.body)
	}

	// Repeating a series does not repeat it in the answer.
	var hist historyResponse
	e.get("/api/v1/history?series=sim.humidity,sim.humidity").decode(t, &hist)
	if len(hist.Series) != 1 {
		t.Fatalf("duplicates must be collapsed: %+v", hist.Series)
	}
}

func TestHistoryReturnsStoredDataWithinLimits(t *testing.T) {
	e := newEnv(t, options{})
	ctx := context.Background()
	// Two hours of 5-second samples ending now: more than any period at full
	// resolution would be allowed to return.
	const samples = 2 * 60 * 12
	for i := range samples {
		ts := now.Add(-time.Duration(i) * 5 * time.Second)
		err := e.store.Save(ctx, &models.Snapshot{Timestamp: ts, Readings: []models.Reading{
			{SeriesID: models.SeriesCPUTotal, Value: float64(i % 100)},
			{SeriesID: "sim.temperature", Value: 21},
		}})
		if err != nil {
			t.Fatal(err)
		}
	}

	limits := map[string]int{"15m": 180, "1h": 360, "6h": 360, "24h": 288, "7d": 336}
	for name, limit := range limits {
		var hist historyResponse
		e.get("/api/v1/history?period="+name+"&series=cpu.total_percent,sim.temperature").decode(t, &hist)
		if len(hist.Series) != 2 || hist.Series[0].ID != models.SeriesCPUTotal || hist.Series[1].ID != "sim.temperature" {
			t.Fatalf("period %s: unexpected series %+v", name, hist.Series)
		}
		for _, s := range hist.Series {
			if len(s.Points) == 0 || len(s.Points) > limit {
				t.Errorf("period %s, %s: %d points, limit %d", name, s.ID, len(s.Points), limit)
			}
			for i, p := range s.Points {
				if p.T.Before(hist.From) || !p.T.Before(hist.To) {
					t.Fatalf("period %s: point outside the range: %v", name, p.T)
				}
				if i > 0 && !p.T.After(s.Points[i-1].T) {
					t.Fatalf("period %s: points are not in ascending order", name)
				}
				if p.Min > p.Avg || p.Avg > p.Max {
					t.Fatalf("period %s: inconsistent aggregate %+v", name, p)
				}
			}
		}
	}

	// At full resolution the newest point is the newest sample.
	var hist historyResponse
	e.get("/api/v1/history?period=15m&series=cpu.total_percent").decode(t, &hist)
	points := hist.Series[0].Points
	if len(points) != 180 {
		t.Fatalf("15m at 5s must be exactly 180 points, got %d", len(points))
	}
	if last := points[len(points)-1]; !last.T.Equal(now) || last.Avg != 0 {
		t.Fatalf("newest point wrong: %+v", last)
	}
	if hist.Series[0].Unit != models.UnitPercent {
		t.Fatalf("unit missing: %+v", hist.Series[0])
	}
}

func TestProcesses(t *testing.T) {
	e := newEnv(t, options{})
	type body struct {
		Timestamp time.Time        `json:"timestamp"`
		Processes []models.Process `json:"processes"`
	}
	pids := func(path string) []int32 {
		var b body
		r := e.get(path)
		if r.status != http.StatusOK {
			t.Fatalf("%s: status %d: %s", path, r.status, r.body)
		}
		r.decode(t, &b)
		var out []int32
		for _, p := range b.Processes {
			out = append(out, p.PID)
		}
		return out
	}
	equal := func(a, b []int32) bool {
		if len(a) != len(b) {
			return false
		}
		for i := range a {
			if a[i] != b[i] {
				return false
			}
		}
		return true
	}

	if got := pids("/api/v1/processes"); !equal(got, []int32{1, 2, 3}) {
		t.Errorf("default order must be by CPU: %v", got)
	}
	if got := pids("/api/v1/processes?sort=memory"); !equal(got, []int32{2, 3, 1}) {
		t.Errorf("order by memory: %v", got)
	}
	if got := pids("/api/v1/processes?sort=memory&limit=2"); !equal(got, []int32{2, 3}) {
		t.Errorf("limit: %v", got)
	}
	if got := pids("/api/v1/processes?limit=100"); len(got) != 3 {
		t.Errorf("a limit above the available count returns all: %v", got)
	}
	// Sorting a response must not reorder the shared snapshot.
	if e.latest.Processes[0].PID != 1 {
		t.Fatal("the shared snapshot was modified")
	}

	for _, bad := range []string{"sort=name", "sort=", "limit=0", "limit=-1", "limit=101", "limit=abc", "limit=1.5", "limit="} {
		expectError(t, e.get("/api/v1/processes?"+bad), http.StatusBadRequest, apierror.InvalidParameter)
	}
}

func TestInformationalEndpoints(t *testing.T) {
	e := newEnv(t, options{})

	var cfg struct {
		AgentID    string   `json:"agent_id"`
		Interval   int      `json:"collection_interval_seconds"`
		Retention  int64    `json:"retention_seconds"`
		Top        int      `json:"top_processes"`
		Collectors []string `json:"collectors"`
	}
	e.get("/api/v1/config/public").decode(t, &cfg)
	if cfg.AgentID != "test-agent" || cfg.Interval != 5 || cfg.Retention != 604800 || cfg.Top != 10 || len(cfg.Collectors) == 0 {
		t.Errorf("unexpected public config: %+v", cfg)
	}

	var sys models.SystemInfo
	e.get("/api/v1/system").decode(t, &sys)
	if sys.AgentID != "test-agent" || sys.AgentVersion != "test" || sys.Hostname != "host" || sys.CPUCores < 1 || sys.MemoryTotalBytes == 0 {
		t.Errorf("unexpected system info: %+v", sys)
	}

	var series struct {
		Series []models.SeriesDescriptor `json:"series"`
	}
	e.get("/api/v1/series").decode(t, &series)
	if len(series.Series) != 7 {
		t.Errorf("expected 5 system and 2 simulated series, got %d", len(series.Series))
	}

	var ifaces struct {
		Port       int `json:"port"`
		Interfaces []struct {
			Name, Address string
			Recommended   bool
		} `json:"interfaces"`
	}
	e.get("/api/v1/interfaces").decode(t, &ifaces)
	if ifaces.Port != 8080 || ifaces.Interfaces == nil {
		t.Errorf("unexpected interfaces: %+v", ifaces)
	}

	var network struct {
		Total      models.Network            `json:"total"`
		Interfaces []models.InterfaceTraffic `json:"interfaces"`
	}
	e.get("/api/v1/network").decode(t, &network)
	if network.Total.BytesReceivedTotal != 20 || network.Interfaces == nil {
		t.Errorf("unexpected network: %+v", network)
	}

	var disks struct {
		Disks []models.Disk `json:"disks"`
	}
	e.get("/api/v1/disks").decode(t, &disks)
	if len(disks.Disks) != 1 || disks.Disks[0].MountPoint != "/" {
		t.Errorf("unexpected disks: %+v", disks)
	}
}

func TestOriginPolicy(t *testing.T) {
	const allowed = "http://localhost:5173"
	e := newEnv(t, options{origins: []string{allowed}})
	auth := func(origin string) map[string]string {
		return map[string]string{"Authorization": "Bearer " + testToken, "Origin": origin}
	}

	// A browser page from elsewhere is refused even with a valid token.
	expectError(t, e.do(http.MethodGet, "/api/v1/status", auth("http://evil.example"), nil), http.StatusForbidden, apierror.OriginNotAllowed)
	expectError(t, e.do(http.MethodGet, "/api/v1/health", auth("null"), nil), http.StatusForbidden, apierror.OriginNotAllowed)

	r := e.do(http.MethodGet, "/api/v1/status", auth(allowed), nil)
	if r.status != http.StatusOK || r.header.Get("Access-Control-Allow-Origin") != allowed {
		t.Fatalf("allowed origin: status %d, headers %v", r.status, r.header)
	}

	// Preflight carries no token.
	r = e.do(http.MethodOptions, "/api/v1/status", map[string]string{
		"Origin": allowed, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization",
	}, nil)
	if r.status != http.StatusNoContent || !strings.Contains(r.header.Get("Access-Control-Allow-Headers"), "Authorization") {
		t.Fatalf("preflight: status %d, headers %v", r.status, r.header)
	}

	// Without an Origin header (native clients) no CORS header is sent.
	if r := e.get("/api/v1/status"); r.status != http.StatusOK || r.header.Get("Access-Control-Allow-Origin") != "" {
		t.Fatalf("native client: status %d, headers %v", r.status, r.header)
	}

	// With the default empty list every browser origin is refused.
	closed := newEnv(t, options{})
	expectError(t, closed.do(http.MethodGet, "/api/v1/health", map[string]string{"Origin": allowed}, nil), http.StatusForbidden, apierror.OriginNotAllowed)
}

// TestWebSocketThroughAPI checks the upgrade works behind the API middleware
// and that the origin policy applies to it.
func TestWebSocketThroughAPI(t *testing.T) {
	e := newEnv(t, options{})
	wsURL := "ws" + strings.TrimPrefix(e.url, "http") + "/api/v1/ws"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.CloseNow()
	if err := conn.Write(ctx, websocket.MessageText, []byte(`{"type":"authenticate","token":"`+testToken+`"}`)); err != nil {
		t.Fatal(err)
	}
	_, data, err := conn.Read(ctx)
	if err != nil || !strings.Contains(string(data), `"type":"authenticated"`) {
		t.Fatalf("expected authenticated, got %s %v", data, err)
	}

	_, resp, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPHeader: http.Header{"Origin": {"http://evil.example"}}})
	if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("a WebSocket from a browser origin must be refused, got %v %+v", err, resp)
	}

	expectError(t, e.do(http.MethodPost, "/api/v1/ws", nil, nil), http.StatusMethodNotAllowed, apierror.MethodNotAllowed)
}
