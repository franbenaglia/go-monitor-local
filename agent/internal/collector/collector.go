// Package collector gathers readings on a fixed interval. Each source of data
// implements Collector; the Manager combines them into snapshots.
package collector

import (
	"context"
	"log/slog"
	"sync/atomic"
	"time"

	"gomonitor/agent/internal/models"
)

// Collector produces the readings of one data source. Adding a sensor means
// adding a Collector; API, storage and clients work on series and need no change.
type Collector interface {
	// Name identifies the collector in the configuration and in descriptors.
	Name() string
	// Series describes every series this collector can emit.
	Series() []models.SeriesDescriptor
	// Collect adds this cycle's readings to snap. An error is logged and does
	// not prevent the other collectors from contributing.
	Collect(ctx context.Context, snap *models.Snapshot) error
}

// Identity holds the fields that identify the agent in every snapshot.
type Identity struct {
	AgentID         string
	Hostname        string
	OperatingSystem string
	Architecture    string
}

// Sink receives every snapshot. Snapshots are shared and must not be modified.
type Sink func(*models.Snapshot)

// Manager runs the collectors and publishes their snapshots.
type Manager struct {
	identity   Identity
	collectors []Collector
	interval   time.Duration
	uptime     func() uint64
	logger     *slog.Logger
	sinks      []Sink
	latest     atomic.Pointer[models.Snapshot]
}

func NewManager(identity Identity, collectors []Collector, interval time.Duration, uptime func() uint64, logger *slog.Logger) *Manager {
	return &Manager{identity: identity, collectors: collectors, interval: interval, uptime: uptime, logger: logger}
}

// AddSink registers a receiver. It must be called before Run.
func (m *Manager) AddSink(s Sink) { m.sinks = append(m.sinks, s) }

// Series returns the descriptors of all enabled collectors.
func (m *Manager) Series() []models.SeriesDescriptor {
	var out []models.SeriesDescriptor
	for _, c := range m.collectors {
		out = append(out, c.Series()...)
	}
	return out
}

// Latest returns the most recent snapshot, or nil before the first cycle.
func (m *Manager) Latest() *models.Snapshot { return m.latest.Load() }

// Run collects immediately and then on every interval until ctx is cancelled.
func (m *Manager) Run(ctx context.Context) {
	ticker := time.NewTicker(m.interval)
	defer ticker.Stop()
	for {
		m.CollectOnce(ctx, time.Now())
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// CollectOnce runs one cycle and publishes the resulting snapshot.
func (m *Manager) CollectOnce(ctx context.Context, now time.Time) *models.Snapshot {
	snap := &models.Snapshot{
		AgentID:         m.identity.AgentID,
		Hostname:        m.identity.Hostname,
		OperatingSystem: m.identity.OperatingSystem,
		Architecture:    m.identity.Architecture,
		Timestamp:       now.UTC().Truncate(time.Second),
		UptimeSeconds:   m.uptime(),
		Readings:        []models.Reading{},
	}
	for _, c := range m.collectors {
		if err := c.Collect(ctx, snap); err != nil && ctx.Err() == nil {
			m.logger.Warn("collector failed", "collector", c.Name(), "error", err)
		}
	}
	if ctx.Err() != nil {
		return nil
	}
	m.latest.Store(snap)
	for _, sink := range m.sinks {
		sink(snap)
	}
	return snap
}
