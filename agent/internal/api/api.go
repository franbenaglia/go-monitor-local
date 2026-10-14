// Package api serves the REST endpoints specified in docs/PROTOCOL.md.
package api

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"gomonitor/agent/internal/apierror"
	"gomonitor/agent/internal/collector"
	"gomonitor/agent/internal/config"
	"gomonitor/agent/internal/models"
	"gomonitor/agent/internal/netinfo"
)

const (
	maxBodyBytes     = 1024
	maxURLBytes      = 2048
	maxHistorySeries = 16
	maxProcessLimit  = 100
)

// History is the part of the store the API reads.
type History interface {
	ListSeries(ctx context.Context) ([]models.SeriesDescriptor, error)
	History(ctx context.Context, seriesID string, from, to time.Time, bucket time.Duration) ([]models.HistoryPoint, error)
}

// Deps are the components the API reads from.
type Deps struct {
	Config  config.Config
	Version string
	Latest  func() *models.Snapshot
	History History
	// System is nil when the system collector is disabled.
	System *collector.System
	// WebSocket handles /api/v1/ws.
	WebSocket http.Handler
	Logger    *slog.Logger
	// Now is replaceable in tests.
	Now func() time.Time
}

type server struct {
	Deps
	tokenHash [sha256.Size]byte
}

// NewHandler builds the HTTP handler for the whole API.
func NewHandler(d Deps) http.Handler {
	if d.Now == nil {
		d.Now = time.Now
	}
	s := &server{Deps: d, tokenHash: sha256.Sum256([]byte(d.Config.APIToken))}

	mux := http.NewServeMux()
	route := func(path string, h http.HandlerFunc, needsToken bool) {
		var handler http.Handler = h
		if needsToken {
			handler = s.requireToken(handler)
		}
		mux.Handle("/api/v1/"+path, getOnly(handler))
	}
	route("health", s.health, false)
	route("status", s.status, true)
	route("series", s.series, true)
	route("history", s.history, true)
	route("processes", s.processes, true)
	route("system", s.system, true)
	route("network", s.network, true)
	route("disks", s.disks, true)
	route("config/public", s.publicConfig, true)
	route("interfaces", s.interfaces, true)
	// The WebSocket authenticates with its first message, not a header.
	mux.Handle("/api/v1/ws", getOnly(d.WebSocket))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		apierror.Write(w, http.StatusNotFound, apierror.NotFound, "The requested resource does not exist")
	})

	return s.recoverPanics(s.logRequests(limitRequest(s.checkOrigin(mux))))
}

// ---- middleware ----

func (s *server) recoverPanics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil && v != http.ErrAbortHandler {
				s.Logger.Error("panic serving request", "path", r.URL.Path, "panic", v)
				apierror.Write(w, http.StatusInternalServerError, apierror.InternalError, "Internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// Unwrap lets the WebSocket upgrade reach the underlying connection.
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// logRequests logs path and status only: never headers or query strings.
func (s *server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		s.Logger.Debug("request", "method", r.Method, "path", r.URL.Path, "status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(), "remote", r.RemoteAddr)
	})
}

func limitRequest(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ContentLength > maxBodyBytes {
			apierror.Write(w, http.StatusRequestEntityTooLarge, apierror.PayloadTooLarge, "Request body is too large")
			return
		}
		if len(r.URL.RequestURI()) > maxURLBytes {
			apierror.Write(w, http.StatusBadRequest, apierror.InvalidParameter, "Request URL is too long")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		next.ServeHTTP(w, r)
	})
}

// checkOrigin implements D5: native clients send no Origin and pass; browser
// origins must be listed in cors_allowed_origins.
func (s *server) checkOrigin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin == "" {
			next.ServeHTTP(w, r)
			return
		}
		if !slices.Contains(s.Config.CORSAllowedOrigins, origin) {
			apierror.Write(w, http.StatusForbidden, apierror.OriginNotAllowed, "This origin is not allowed to use the API")
			return
		}
		h := w.Header()
		h.Set("Access-Control-Allow-Origin", origin)
		h.Add("Vary", "Origin")
		if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
			h.Set("Access-Control-Allow-Methods", "GET")
			h.Set("Access-Control-Allow-Headers", "Authorization")
			h.Set("Access-Control-Max-Age", "600")
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func getOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", "GET")
			apierror.Write(w, http.StatusMethodNotAllowed, apierror.MethodNotAllowed, "Only GET is supported")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *server) requireToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		// Hashing first makes the comparison independent of the token length.
		given := sha256.Sum256([]byte(token))
		if !ok || subtle.ConstantTimeCompare(given[:], s.tokenHash[:]) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			apierror.Write(w, http.StatusUnauthorized, apierror.Unauthorized, "A valid token is required")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, v any) {
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Set("X-Content-Type-Options", "nosniff")
	_ = json.NewEncoder(w).Encode(v)
}

func (s *server) internalError(w http.ResponseWriter, r *http.Request, err error) {
	s.Logger.Error("request failed", "path", r.URL.Path, "error", err)
	apierror.Write(w, http.StatusInternalServerError, apierror.InternalError, "Internal error")
}

// parseQuery rejects malformed query strings instead of silently dropping
// the parameters that fail to parse.
func parseQuery(w http.ResponseWriter, r *http.Request) (url.Values, bool) {
	query, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		apierror.Write(w, http.StatusBadRequest, apierror.InvalidParameter, "The query string is malformed")
		return nil, false
	}
	return query, true
}

// latest returns the last snapshot or answers 503 when there is none yet.
func (s *server) latest(w http.ResponseWriter) *models.Snapshot {
	snap := s.Latest()
	if snap == nil {
		apierror.Write(w, http.StatusServiceUnavailable, apierror.NotReady, "No sample has been collected yet")
	}
	return snap
}

// systemSnapshot is latest for the endpoints that only exist with the system
// collector.
func (s *server) systemSnapshot(w http.ResponseWriter) *models.Snapshot {
	if s.System == nil {
		apierror.Write(w, http.StatusNotFound, apierror.NotFound, "The system collector is disabled on this agent")
		return nil
	}
	return s.latest(w)
}

// ---- handlers ----

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, map[string]any{
		"status":           "ok",
		"agent_id":         s.Config.AgentID,
		"protocol_version": models.ProtocolVersion,
	})
}

func (s *server) status(w http.ResponseWriter, _ *http.Request) {
	if snap := s.latest(w); snap != nil {
		writeJSON(w, snap)
	}
}

func (s *server) series(w http.ResponseWriter, r *http.Request) {
	list, err := s.History.ListSeries(r.Context())
	if err != nil {
		s.internalError(w, r, err)
		return
	}
	writeJSON(w, map[string]any{"series": list})
}

type period struct {
	length time.Duration
	bucket time.Duration
}

// periods maps each allowed period to its bucket size, chosen so a series
// never returns more than 360 points.
var periods = map[string]period{
	"15m": {15 * time.Minute, 5 * time.Second},
	"1h":  {time.Hour, 10 * time.Second},
	"6h":  {6 * time.Hour, time.Minute},
	"24h": {24 * time.Hour, 5 * time.Minute},
	"7d":  {7 * 24 * time.Hour, 30 * time.Minute},
}

var defaultHistorySeries = []string{
	models.SeriesCPUTotal, models.SeriesMemPercent, models.SeriesNetSentRate, models.SeriesNetRecvRate,
}

var seriesIDPattern = regexp.MustCompile(`^[a-z0-9_.-]{1,64}$`)

type historySeries struct {
	ID     string                `json:"id"`
	Unit   string                `json:"unit"`
	Points []models.HistoryPoint `json:"points"`
}

func (s *server) history(w http.ResponseWriter, r *http.Request) {
	query, ok := parseQuery(w, r)
	if !ok {
		return
	}

	name := "15m"
	if query.Has("period") {
		name = query.Get("period")
	}
	p, known := periods[name]
	if !known {
		apierror.Write(w, http.StatusBadRequest, apierror.InvalidPeriod, "The requested history period is not valid")
		return
	}

	catalog, err := s.History.ListSeries(r.Context())
	if err != nil {
		s.internalError(w, r, err)
		return
	}
	units := make(map[string]string, len(catalog))
	for _, d := range catalog {
		units[d.ID] = d.Unit
	}

	var ids []string
	if query.Has("series") {
		for _, id := range strings.Split(query.Get("series"), ",") {
			if !seriesIDPattern.MatchString(id) {
				apierror.Write(w, http.StatusBadRequest, apierror.InvalidParameter, "The series parameter must be a comma-separated list of series ids")
				return
			}
			if _, known := units[id]; !known {
				apierror.Write(w, http.StatusNotFound, apierror.UnknownSeries, "Unknown series: "+id)
				return
			}
			if !slices.Contains(ids, id) {
				ids = append(ids, id)
			}
		}
		if len(ids) > maxHistorySeries {
			apierror.Write(w, http.StatusBadRequest, apierror.InvalidParameter, "Too many series requested; the maximum is "+strconv.Itoa(maxHistorySeries))
			return
		}
	} else {
		// The defaults are system series; skip the ones this agent lacks.
		for _, id := range defaultHistorySeries {
			if _, known := units[id]; known {
				ids = append(ids, id)
			}
		}
	}

	// Buckets cannot be finer than the collection interval.
	bucket := max(p.bucket, s.Config.CollectionInterval.Round(time.Second))
	bucketSeconds := int64(bucket / time.Second)
	// The range ends at the end of the bucket in progress, so the newest
	// readings are included and the number of buckets is exact.
	to := time.Unix((s.Now().Unix()/bucketSeconds+1)*bucketSeconds, 0).UTC()
	from := to.Add(-p.length)

	out := make([]historySeries, 0, len(ids))
	for _, id := range ids {
		points, err := s.History.History(r.Context(), id, from, to, bucket)
		if err != nil {
			s.internalError(w, r, err)
			return
		}
		out = append(out, historySeries{ID: id, Unit: units[id], Points: points})
	}
	writeJSON(w, map[string]any{
		"period":         name,
		"from":           from,
		"to":             to,
		"bucket_seconds": bucketSeconds,
		"series":         out,
	})
}

func (s *server) processes(w http.ResponseWriter, r *http.Request) {
	query, ok := parseQuery(w, r)
	if !ok {
		return
	}
	sortBy := "cpu"
	if query.Has("sort") {
		sortBy = query.Get("sort")
	}
	if sortBy != "cpu" && sortBy != "memory" {
		apierror.Write(w, http.StatusBadRequest, apierror.InvalidParameter, "sort must be cpu or memory")
		return
	}
	limit := 0
	if query.Has("limit") {
		n, err := strconv.Atoi(query.Get("limit"))
		if err != nil || n < 1 || n > maxProcessLimit {
			apierror.Write(w, http.StatusBadRequest, apierror.InvalidParameter, "limit must be between 1 and "+strconv.Itoa(maxProcessLimit))
			return
		}
		limit = n
	}
	snap := s.systemSnapshot(w)
	if snap == nil {
		return
	}

	// The snapshot is shared; sort a copy.
	procs := append([]models.Process{}, snap.Processes...)
	if sortBy == "memory" {
		sort.SliceStable(procs, func(i, j int) bool { return procs[i].MemoryPercent > procs[j].MemoryPercent })
	}
	if limit > 0 && limit < len(procs) {
		procs = procs[:limit]
	}
	writeJSON(w, map[string]any{"timestamp": snap.Timestamp, "processes": procs})
}

func (s *server) system(w http.ResponseWriter, r *http.Request) {
	if s.System == nil {
		apierror.Write(w, http.StatusNotFound, apierror.NotFound, "The system collector is disabled on this agent")
		return
	}
	info := s.System.Info(r.Context())
	info.AgentID = s.Config.AgentID
	info.AgentVersion = s.Version
	if snap := s.Latest(); snap != nil {
		info.Hostname = snap.Hostname
		info.OperatingSystem = snap.OperatingSystem
		info.Architecture = snap.Architecture
	}
	writeJSON(w, info)
}

func (s *server) network(w http.ResponseWriter, _ *http.Request) {
	snap := s.systemSnapshot(w)
	if snap == nil {
		return
	}
	total := models.Network{}
	if snap.Network != nil {
		total = *snap.Network
	}
	writeJSON(w, map[string]any{
		"timestamp":  snap.Timestamp,
		"total":      total,
		"interfaces": s.System.InterfaceTraffic(),
	})
}

func (s *server) disks(w http.ResponseWriter, _ *http.Request) {
	snap := s.systemSnapshot(w)
	if snap == nil {
		return
	}
	disks := snap.Disks
	if disks == nil {
		disks = []models.Disk{}
	}
	writeJSON(w, map[string]any{"timestamp": snap.Timestamp, "disks": disks})
}

func (s *server) publicConfig(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, map[string]any{
		"agent_id":                    s.Config.AgentID,
		"protocol_version":            models.ProtocolVersion,
		"collection_interval_seconds": int(s.Config.CollectionInterval / time.Second),
		"retention_seconds":           int64(s.Config.Retention / time.Second),
		"top_processes":               s.Config.TopProcesses,
		"collectors":                  s.Config.Collectors,
	})
}

func (s *server) interfaces(w http.ResponseWriter, r *http.Request) {
	list, err := netinfo.List()
	if err != nil {
		s.internalError(w, r, err)
		return
	}
	if list == nil {
		list = []netinfo.Interface{}
	}
	writeJSON(w, map[string]any{"port": s.Config.Port, "interfaces": list})
}
