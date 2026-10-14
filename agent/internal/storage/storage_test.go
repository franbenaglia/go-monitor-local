package storage

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"gomonitor/agent/internal/models"
)

var ctx = context.Background()

func openTemp(t *testing.T) *Store {
	t.Helper()
	// A directory with a space and a nested folder checks path handling.
	path := filepath.Join(t.TempDir(), "my data", "gomonitor.db")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("database not created at the requested path: %v", err)
	}
	var mode string
	if err := s.db.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("journal mode = %q, %v; want wal", mode, err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func snapshotAt(ts time.Time, cpu float64) *models.Snapshot {
	return &models.Snapshot{
		AgentID: "test", Hostname: "host", OperatingSystem: "linux", Architecture: "amd64",
		Timestamp: ts, UptimeSeconds: 100,
		Readings: []models.Reading{
			{SeriesID: models.SeriesCPUTotal, Value: cpu},
			{SeriesID: "sim.temperature", Value: 20 + cpu/10},
		},
		Disks:     []models.Disk{{MountPoint: "/", Filesystem: "ext4", TotalBytes: 1000, UsedBytes: 400, AvailableBytes: 600, UsedPercent: 40}},
		Processes: []models.Process{{PID: 1, Name: "init", CPUPercent: 1, MemoryPercent: 2}, {PID: 2, Name: "go", CPUPercent: 3, MemoryPercent: 4}},
	}
}

func count(t *testing.T, s *Store, table string) int {
	t.Helper()
	var n int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestSaveStoresQueryableColumns(t *testing.T) {
	s := openTemp(t)
	ts := time.Unix(1_800_000_000, 0)
	if err := s.Save(ctx, snapshotAt(ts, 50)); err != nil {
		t.Fatal(err)
	}
	for table, want := range map[string]int{"samples": 1, "readings": 2, "disk_samples": 1, "process_samples": 2} {
		if got := count(t, s, table); got != want {
			t.Errorf("%s: %d rows, want %d", table, got, want)
		}
	}

	var value float64
	if err := s.db.QueryRow(`SELECT value FROM readings WHERE series_id = ? AND ts = ?`, models.SeriesCPUTotal, ts.Unix()).Scan(&value); err != nil || value != 50 {
		t.Fatalf("reading not queryable by column: %v %v", value, err)
	}
	var name string
	var used int64
	if err := s.db.QueryRow(`SELECT name FROM process_samples WHERE pid = 2`).Scan(&name); err != nil || name != "go" {
		t.Fatalf("process not queryable: %q %v", name, err)
	}
	if err := s.db.QueryRow(`SELECT used_bytes FROM disk_samples WHERE mount_point = '/'`).Scan(&used); err != nil || used != 400 {
		t.Fatalf("disk not queryable: %v %v", used, err)
	}

	// Saving the same timestamp again replaces, it does not duplicate or fail.
	if err := s.Save(ctx, snapshotAt(ts, 60)); err != nil {
		t.Fatal(err)
	}
	if got := count(t, s, "readings"); got != 2 {
		t.Fatalf("duplicate rows after saving the same timestamp: %d", got)
	}
}

func TestHistoryAggregatesInBuckets(t *testing.T) {
	s := openTemp(t)
	base := time.Unix(1_800_000_000, 0) // multiple of 60
	// 5-second samples for two minutes; cpu goes 0, 1, 2, ... 23.
	for i := range 24 {
		if err := s.Save(ctx, snapshotAt(base.Add(time.Duration(i)*5*time.Second), float64(i))); err != nil {
			t.Fatal(err)
		}
	}

	points, err := s.History(ctx, models.SeriesCPUTotal, base, base.Add(2*time.Minute), time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if len(points) != 2 {
		t.Fatalf("got %d buckets, want 2: %+v", len(points), points)
	}
	first, second := points[0], points[1]
	if !first.T.Equal(base) || first.Min != 0 || first.Max != 11 || first.Avg != 5.5 {
		t.Errorf("first bucket wrong: %+v", first)
	}
	if !second.T.Equal(base.Add(time.Minute)) || second.Min != 12 || second.Max != 23 || second.Avg != 17.5 {
		t.Errorf("second bucket wrong: %+v", second)
	}
	if first.T.Location() != time.UTC {
		t.Errorf("bucket time must be UTC")
	}

	// The upper bound is exclusive and the lower bound inclusive.
	points, err = s.History(ctx, models.SeriesCPUTotal, base, base.Add(5*time.Second), 5*time.Second)
	if err != nil || len(points) != 1 || points[0].Avg != 0 {
		t.Fatalf("bounds wrong: %+v %v", points, err)
	}

	// Each series is independent.
	points, err = s.History(ctx, "sim.temperature", base, base.Add(2*time.Minute), 2*time.Minute)
	if err != nil || len(points) != 1 || points[0].Min != 20 {
		t.Fatalf("second series wrong: %+v %v", points, err)
	}

	// An unknown series or an empty range yields an empty, non-nil list.
	points, err = s.History(ctx, "nope", base, base.Add(time.Hour), time.Minute)
	if err != nil || points == nil || len(points) != 0 {
		t.Fatalf("unknown series: %#v %v", points, err)
	}
	if _, err := s.History(ctx, models.SeriesCPUTotal, base, base.Add(time.Hour), 0); err == nil {
		t.Fatal("a zero bucket must be rejected")
	}
}

func TestHistoryOmitsEmptyBuckets(t *testing.T) {
	s := openTemp(t)
	base := time.Unix(1_800_000_000, 0)
	for _, offset := range []time.Duration{0, 10 * time.Minute} {
		if err := s.Save(ctx, snapshotAt(base.Add(offset), 10)); err != nil {
			t.Fatal(err)
		}
	}
	points, err := s.History(ctx, models.SeriesCPUTotal, base, base.Add(time.Hour), time.Minute)
	if err != nil || len(points) != 2 {
		t.Fatalf("expected only the 2 buckets with data, got %+v %v", points, err)
	}
}

func TestPruneAppliesRetention(t *testing.T) {
	s := openTemp(t)
	now := time.Unix(1_800_000_000, 0)
	old := now.Add(-8 * 24 * time.Hour)
	recent := now.Add(-time.Hour)
	for _, ts := range []time.Time{old, old.Add(5 * time.Second), recent} {
		if err := s.Save(ctx, snapshotAt(ts, 1)); err != nil {
			t.Fatal(err)
		}
	}

	removed, err := s.Prune(ctx, now.Add(-7*24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if removed != 2 {
		t.Fatalf("removed %d samples, want 2", removed)
	}
	for table, want := range map[string]int{"samples": 1, "readings": 2, "disk_samples": 1, "process_samples": 2} {
		if got := count(t, s, table); got != want {
			t.Errorf("%s: %d rows left, want %d", table, got, want)
		}
	}
	var ts int64
	if err := s.db.QueryRow(`SELECT ts FROM samples`).Scan(&ts); err != nil || ts != recent.Unix() {
		t.Fatalf("the recent sample must survive: %v %v", ts, err)
	}
}

func TestSeriesCatalog(t *testing.T) {
	s := openTemp(t)
	active := []models.SeriesDescriptor{
		{ID: models.SeriesCPUTotal, Collector: "system", Name: "CPU", Kind: "cpu", Unit: "percent", Min: models.Float(0), Max: models.Float(100)},
	}
	if err := s.RegisterSeries(ctx, active); err != nil {
		t.Fatal(err)
	}
	// A series from a collector that was enabled in the past.
	if _, err := s.db.Exec(`INSERT INTO series (id, collector, name, kind, unit) VALUES ('old.sensor', 'gone', 'Old', 'generic', 'count')`); err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1_800_000_000, 0)
	old := &models.Snapshot{Timestamp: now.Add(-48 * time.Hour), Readings: []models.Reading{{SeriesID: "old.sensor", Value: 1}}}
	if err := s.Save(ctx, old); err != nil {
		t.Fatal(err)
	}

	list, err := s.ListSeries(ctx)
	if err != nil || len(list) != 2 {
		t.Fatalf("expected active and historical series: %+v %v", list, err)
	}

	// Re-registering updates the description instead of failing.
	active[0].Name = "CPU total"
	if err := s.RegisterSeries(ctx, active); err != nil {
		t.Fatal(err)
	}

	// Once its history is gone, the inactive series is forgotten; the active
	// one stays even though it has no readings.
	if _, err := s.Prune(ctx, now.Add(-24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	list, err = s.ListSeries(ctx)
	if err != nil || len(list) != 1 {
		t.Fatalf("expected only the active series: %+v %v", list, err)
	}
	got := list[0]
	if got.ID != models.SeriesCPUTotal || got.Name != "CPU total" || got.Min == nil || *got.Max != 100 {
		t.Fatalf("unexpected descriptor: %+v", got)
	}
}

func TestReopenKeepsData(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gomonitor.db")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ts := time.Unix(1_800_000_000, 0)
	if err := s.Save(ctx, snapshotAt(ts, 7)); err != nil {
		t.Fatal(err)
	}
	s.Close()

	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	points, err := s.History(ctx, models.SeriesCPUTotal, ts, ts.Add(time.Minute), time.Minute)
	if err != nil || len(points) != 1 || points[0].Avg != 7 {
		t.Fatalf("data lost after reopening: %+v %v", points, err)
	}
}

func TestConcurrentReadsAndWrites(t *testing.T) {
	s := openTemp(t)
	base := time.Unix(1_800_000_000, 0)
	done := make(chan error, 2)
	go func() {
		for i := range 50 {
			if err := s.Save(ctx, snapshotAt(base.Add(time.Duration(i)*time.Second), float64(i))); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	go func() {
		for range 50 {
			if _, err := s.History(ctx, models.SeriesCPUTotal, base, base.Add(time.Hour), time.Second); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	for range 2 {
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	}
}
