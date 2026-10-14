// Package storage keeps the history in a local SQLite database.
//
// Readings live in a narrow table keyed by series and time, so a new sensor
// needs no schema change (see D3 in docs/DECISIONS.md).
package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"

	"gomonitor/agent/internal/models"
)

const schema = `
CREATE TABLE IF NOT EXISTS series (
	id        TEXT PRIMARY KEY,
	collector TEXT NOT NULL,
	name      TEXT NOT NULL,
	kind      TEXT NOT NULL,
	unit      TEXT NOT NULL,
	min       REAL,
	max       REAL
);

CREATE TABLE IF NOT EXISTS samples (
	ts               INTEGER PRIMARY KEY,
	agent_id         TEXT NOT NULL,
	hostname         TEXT NOT NULL,
	operating_system TEXT NOT NULL,
	architecture     TEXT NOT NULL,
	uptime_seconds   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS readings (
	series_id TEXT NOT NULL,
	ts        INTEGER NOT NULL,
	value     REAL NOT NULL,
	PRIMARY KEY (series_id, ts)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_readings_ts ON readings (ts);

CREATE TABLE IF NOT EXISTS disk_samples (
	ts              INTEGER NOT NULL,
	mount_point     TEXT NOT NULL,
	filesystem      TEXT NOT NULL,
	total_bytes     INTEGER NOT NULL,
	used_bytes      INTEGER NOT NULL,
	available_bytes INTEGER NOT NULL,
	used_percent    REAL NOT NULL,
	PRIMARY KEY (ts, mount_point)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS process_samples (
	ts             INTEGER NOT NULL,
	pid            INTEGER NOT NULL,
	name           TEXT NOT NULL,
	cpu_percent    REAL NOT NULL,
	memory_percent REAL NOT NULL,
	PRIMARY KEY (ts, pid)
) WITHOUT ROWID;
`

// maxHistoryPoints is a hard cap on the points returned per series, on top of
// the bucket sizes the API chooses.
const maxHistoryPoints = 1000

// Store is the history database. It is safe for concurrent use.
type Store struct {
	db *sql.DB

	mu     sync.RWMutex
	active map[string]bool // series produced by the enabled collectors
}

// Open opens the database at path, creating it and its directory if needed.
func Open(path string) (*Store, error) {
	if dir := filepath.Dir(path); dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create database directory: %w", err)
		}
	}
	// WAL lets the API read while the collector writes; NORMAL sync is safe
	// with WAL and writes far less often, which matters on SD cards.
	// A plain path (not a file: URI) keeps Windows paths working; the driver
	// still reads the pragmas after the question mark.
	dsn := path + "?_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("initialize database %s: %w", path, err)
	}
	return &Store{db: db, active: map[string]bool{}}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// RegisterSeries records the series of the enabled collectors, updating the
// description of the ones already known.
func (s *Store) RegisterSeries(ctx context.Context, series []models.SeriesDescriptor) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, d := range series {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO series (id, collector, name, kind, unit, min, max) VALUES (?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET collector = excluded.collector, name = excluded.name,
				kind = excluded.kind, unit = excluded.unit, min = excluded.min, max = excluded.max`,
			d.ID, d.Collector, d.Name, d.Kind, d.Unit, nullable(d.Min), nullable(d.Max))
		if err != nil {
			return fmt.Errorf("register series %s: %w", d.ID, err)
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	s.mu.Lock()
	for _, d := range series {
		s.active[d.ID] = true
	}
	s.mu.Unlock()
	return nil
}

// ListSeries returns the active series and those that still have history.
func (s *Store) ListSeries(ctx context.Context) ([]models.SeriesDescriptor, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, collector, name, kind, unit, min, max FROM series ORDER BY collector, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []models.SeriesDescriptor{}
	for rows.Next() {
		var d models.SeriesDescriptor
		var lo, hi sql.NullFloat64
		if err := rows.Scan(&d.ID, &d.Collector, &d.Name, &d.Kind, &d.Unit, &lo, &hi); err != nil {
			return nil, err
		}
		if lo.Valid {
			d.Min = &lo.Float64
		}
		if hi.Valid {
			d.Max = &hi.Float64
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// Save stores one snapshot atomically. Saving the same timestamp twice
// replaces the earlier data.
func (s *Store) Save(ctx context.Context, snap *models.Snapshot) error {
	ts := snap.Timestamp.Unix()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		INSERT OR REPLACE INTO samples (ts, agent_id, hostname, operating_system, architecture, uptime_seconds)
		VALUES (?, ?, ?, ?, ?, ?)`,
		ts, snap.AgentID, snap.Hostname, snap.OperatingSystem, snap.Architecture, int64(snap.UptimeSeconds)); err != nil {
		return fmt.Errorf("save sample: %w", err)
	}

	if len(snap.Readings) > 0 {
		stmt, err := tx.PrepareContext(ctx, `INSERT OR REPLACE INTO readings (series_id, ts, value) VALUES (?, ?, ?)`)
		if err != nil {
			return err
		}
		defer stmt.Close()
		for _, r := range snap.Readings {
			if _, err := stmt.ExecContext(ctx, r.SeriesID, ts, r.Value); err != nil {
				return fmt.Errorf("save reading %s: %w", r.SeriesID, err)
			}
		}
	}

	for _, d := range snap.Disks {
		if _, err := tx.ExecContext(ctx, `
			INSERT OR REPLACE INTO disk_samples (ts, mount_point, filesystem, total_bytes, used_bytes, available_bytes, used_percent)
			VALUES (?, ?, ?, ?, ?, ?, ?)`,
			ts, d.MountPoint, d.Filesystem, int64(d.TotalBytes), int64(d.UsedBytes), int64(d.AvailableBytes), d.UsedPercent); err != nil {
			return fmt.Errorf("save disk %s: %w", d.MountPoint, err)
		}
	}

	for _, p := range snap.Processes {
		if _, err := tx.ExecContext(ctx, `
			INSERT OR REPLACE INTO process_samples (ts, pid, name, cpu_percent, memory_percent) VALUES (?, ?, ?, ?, ?)`,
			ts, p.PID, p.Name, p.CPUPercent, p.MemoryPercent); err != nil {
			return fmt.Errorf("save process %d: %w", p.PID, err)
		}
	}
	return tx.Commit()
}

// History returns the readings of one series between from (inclusive) and to
// (exclusive), aggregated in buckets of the given size. Buckets without
// readings are omitted.
func (s *Store) History(ctx context.Context, seriesID string, from, to time.Time, bucket time.Duration) ([]models.HistoryPoint, error) {
	seconds := int64(bucket / time.Second)
	if seconds < 1 {
		return nil, errors.New("bucket must be at least one second")
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT (ts / ?) * ? AS bucket, AVG(value), MIN(value), MAX(value)
		FROM readings
		WHERE series_id = ? AND ts >= ? AND ts < ?
		GROUP BY bucket
		ORDER BY bucket
		LIMIT ?`,
		seconds, seconds, seriesID, from.Unix(), to.Unix(), maxHistoryPoints)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	points := []models.HistoryPoint{}
	for rows.Next() {
		var ts int64
		var p models.HistoryPoint
		if err := rows.Scan(&ts, &p.Avg, &p.Min, &p.Max); err != nil {
			return nil, err
		}
		p.T = time.Unix(ts, 0).UTC()
		// AVG leaves binary noise such as 5.380000000000001.
		p.Avg = math.Round(p.Avg*1000) / 1000
		points = append(points, p)
	}
	return points, rows.Err()
}

// Prune deletes everything older than cutoff and forgets series that are no
// longer produced and have no history left. It returns the samples removed.
func (s *Store) Prune(ctx context.Context, cutoff time.Time) (int64, error) {
	ts := cutoff.Unix()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	res, err := tx.ExecContext(ctx, `DELETE FROM samples WHERE ts < ?`, ts)
	if err != nil {
		return 0, err
	}
	removed, _ := res.RowsAffected()
	for _, table := range []string{"readings", "disk_samples", "process_samples"} {
		if _, err := tx.ExecContext(ctx, `DELETE FROM `+table+` WHERE ts < ?`, ts); err != nil {
			return 0, fmt.Errorf("prune %s: %w", table, err)
		}
	}

	rows, err := tx.QueryContext(ctx, `SELECT id FROM series WHERE id NOT IN (SELECT DISTINCT series_id FROM readings)`)
	if err != nil {
		return 0, err
	}
	var empty []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return 0, err
		}
		empty = append(empty, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	s.mu.RLock()
	for _, id := range empty {
		if s.active[id] {
			continue
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM series WHERE id = ?`, id); err != nil {
			s.mu.RUnlock()
			return 0, err
		}
	}
	s.mu.RUnlock()

	return removed, tx.Commit()
}

// RunRetention prunes at startup and then periodically until ctx is cancelled.
func (s *Store) RunRetention(ctx context.Context, retention, every time.Duration, onResult func(removed int64, err error)) {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		removed, err := s.Prune(ctx, time.Now().Add(-retention))
		if ctx.Err() != nil {
			return
		}
		onResult(removed, err)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func nullable(v *float64) any {
	if v == nil {
		return nil
	}
	return *v
}
