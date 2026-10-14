package collector

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/shirou/gopsutil/v4/disk"

	"gomonitor/agent/internal/models"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestRateCalculator(t *testing.T) {
	t0 := time.Unix(1000, 0)
	var r RateCalculator

	// First sample: nothing to compare against.
	got := r.Update(t0, map[string]Counters{"eth0": {Sent: 1000, Recv: 5000}})
	if got["eth0"] != (Rates{}) {
		t.Fatalf("first sample must report zero, got %+v", got["eth0"])
	}

	// Normal case: delta divided by elapsed seconds.
	got = r.Update(t0.Add(5*time.Second), map[string]Counters{"eth0": {Sent: 6000, Recv: 55000}})
	if got["eth0"].Sent != 1000 || got["eth0"].Recv != 10000 {
		t.Fatalf("unexpected rates: %+v", got["eth0"])
	}

	// Counters went backwards (reset): zero, never negative or huge.
	got = r.Update(t0.Add(10*time.Second), map[string]Counters{"eth0": {Sent: 100, Recv: 200}})
	if got["eth0"] != (Rates{}) {
		t.Fatalf("reset must report zero, got %+v", got["eth0"])
	}

	// After the reset the next delta is measured from the new baseline.
	got = r.Update(t0.Add(15*time.Second), map[string]Counters{"eth0": {Sent: 600, Recv: 700}})
	if got["eth0"].Sent != 100 || got["eth0"].Recv != 100 {
		t.Fatalf("unexpected rates after reset: %+v", got["eth0"])
	}
}

func TestRateCalculatorInterfaceChanges(t *testing.T) {
	t0 := time.Unix(1000, 0)
	var r RateCalculator
	r.Update(t0, map[string]Counters{"eth0": {Sent: 1000, Recv: 1000}})

	// eth0 disappears, wlan0 appears with large cumulative counters.
	got := r.Update(t0.Add(5*time.Second), map[string]Counters{"wlan0": {Sent: 9_000_000, Recv: 9_000_000}})
	if _, ok := got["eth0"]; ok {
		t.Fatal("a removed interface must not be reported")
	}
	if got["wlan0"] != (Rates{}) {
		t.Fatalf("a new interface must report zero on its first cycle, got %+v", got["wlan0"])
	}

	// eth0 comes back: it was forgotten, so it starts from zero again.
	got = r.Update(t0.Add(10*time.Second), map[string]Counters{
		"wlan0": {Sent: 9_000_500, Recv: 9_001_000},
		"eth0":  {Sent: 50, Recv: 50},
	})
	if got["eth0"] != (Rates{}) || got["wlan0"].Sent != 100 || got["wlan0"].Recv != 200 {
		t.Fatalf("unexpected rates: %+v", got)
	}
}

func TestRateCalculatorOnlyOneDirectionResets(t *testing.T) {
	t0 := time.Unix(1000, 0)
	var r RateCalculator
	r.Update(t0, map[string]Counters{"eth0": {Sent: 1000, Recv: 1000}})
	got := r.Update(t0.Add(2*time.Second), map[string]Counters{"eth0": {Sent: 10, Recv: 3000}})
	if got["eth0"].Sent != 0 || got["eth0"].Recv != 1000 {
		t.Fatalf("unexpected rates: %+v", got["eth0"])
	}
}

func TestRateCalculatorNoElapsedTime(t *testing.T) {
	t0 := time.Unix(1000, 0)
	var r RateCalculator
	r.Update(t0, map[string]Counters{"eth0": {Sent: 1000, Recv: 1000}})
	for _, at := range []time.Time{t0, t0.Add(-time.Second)} {
		got := r.Update(at, map[string]Counters{"eth0": {Sent: 2000, Recv: 2000}})
		if got["eth0"] != (Rates{}) {
			t.Fatalf("no or negative elapsed time must report zero, got %+v", got["eth0"])
		}
	}
}

func TestSelectTopDeduplicates(t *testing.T) {
	procs := []models.Process{
		{PID: 1, CPUPercent: 90, MemoryPercent: 1},
		{PID: 2, CPUPercent: 50, MemoryPercent: 40}, // top in both lists
		{PID: 3, CPUPercent: 1, MemoryPercent: 30},
		{PID: 4, CPUPercent: 0.5, MemoryPercent: 0.5},
		{PID: 5, CPUPercent: 0, MemoryPercent: 20},
	}
	got := SelectTop(procs, 2)
	// CPU top 2: 1, 2. Memory top 2: 2, 3. Union: 1, 2, 3.
	wantPIDs := []int32{1, 2, 3}
	if len(got) != len(wantPIDs) {
		t.Fatalf("got %d processes, want %d: %+v", len(got), len(wantPIDs), got)
	}
	for i, pid := range wantPIDs {
		if got[i].PID != pid {
			t.Fatalf("position %d: got pid %d, want %d (%+v)", i, got[i].PID, pid, got)
		}
	}
}

func TestSelectTopEdgeCases(t *testing.T) {
	procs := []models.Process{{PID: 1, CPUPercent: 1}, {PID: 2, CPUPercent: 2}}
	if got := SelectTop(procs, 10); len(got) != 2 || got[0].PID != 2 {
		t.Fatalf("fewer processes than n: %+v", got)
	}
	if got := SelectTop(procs, 0); len(got) != 0 {
		t.Fatalf("n = 0 must return nothing: %+v", got)
	}
	if got := SelectTop(nil, 5); got == nil || len(got) != 0 {
		t.Fatalf("no processes must return an empty, non-nil slice: %#v", got)
	}
	// The input must not be reordered.
	if procs[0].PID != 1 {
		t.Fatal("SelectTop modified its input")
	}
}

func TestCPUPercent(t *testing.T) {
	prev := procState{createTime: 100, cpuSeconds: 10}
	if got := cpuPercent(prev, true, procState{createTime: 100, cpuSeconds: 12.5}, 5); got != 50 {
		t.Fatalf("got %v, want 50", got)
	}
	if got := cpuPercent(prev, false, procState{createTime: 100, cpuSeconds: 12.5}, 5); got != 0 {
		t.Fatalf("unknown process must report zero, got %v", got)
	}
	if got := cpuPercent(prev, true, procState{createTime: 999, cpuSeconds: 50}, 5); got != 0 {
		t.Fatalf("reused pid must report zero, got %v", got)
	}
	if got := cpuPercent(prev, true, procState{createTime: 100, cpuSeconds: 5}, 5); got != 0 {
		t.Fatalf("decreasing cpu time must report zero, got %v", got)
	}
	if got := cpuPercent(prev, true, procState{createTime: 100, cpuSeconds: 12}, 0); got != 0 {
		t.Fatalf("zero elapsed must report zero, got %v", got)
	}
}

func TestRelevantPartitions(t *testing.T) {
	parts := []disk.PartitionStat{
		{Device: "/dev/sda2", Mountpoint: "/", Fstype: "ext4"},
		{Device: "/dev/sda1", Mountpoint: "/boot/efi", Fstype: "vfat"},
		{Device: "/dev/loop3", Mountpoint: "/snap/core22/1234", Fstype: "squashfs"},
		{Device: "/dev/sda2", Mountpoint: "/mnt/bind", Fstype: "ext4"}, // same device again
		{Device: "tmpfs", Mountpoint: "/run/user/1000", Fstype: "tmpfs"},
		{Device: "/dev/sdb1", Mountpoint: "/home", Fstype: "ext4"},
		{Device: "C:", Mountpoint: "C:", Fstype: "NTFS"},
	}
	got := relevantPartitions(parts)
	want := []string{"/", "/home", "C:"}
	if len(got) != len(want) {
		t.Fatalf("got %+v, want mount points %v", got, want)
	}
	for i, mp := range want {
		if got[i].Mountpoint != mp {
			t.Fatalf("position %d: got %s, want %s", i, got[i].Mountpoint, mp)
		}
	}
}

// TestSystemCollectsRealMetrics runs the real collector on this machine and
// checks the values are sane.
func TestSystemCollectsRealMetrics(t *testing.T) {
	s := NewSystem(5)
	ctx := context.Background()

	first := &models.Snapshot{}
	if err := s.Collect(ctx, first); err != nil {
		t.Logf("partial failure on first cycle: %v", err)
	}
	time.Sleep(300 * time.Millisecond)
	snap := &models.Snapshot{}
	if err := s.Collect(ctx, snap); err != nil {
		t.Logf("partial failure on second cycle: %v", err)
	}

	if snap.CPU == nil || len(snap.CPU.CorePercentages) == 0 {
		t.Fatal("no CPU data")
	}
	if snap.CPU.TotalPercent < 0 || snap.CPU.TotalPercent > 100 {
		t.Errorf("CPU total out of range: %v", snap.CPU.TotalPercent)
	}
	if snap.Memory == nil || snap.Memory.TotalBytes == 0 {
		t.Fatal("no memory data")
	}
	if snap.Memory.UsedBytes+snap.Memory.AvailableBytes != snap.Memory.TotalBytes {
		t.Errorf("memory figures do not add up: %+v", snap.Memory)
	}
	if snap.Memory.UsedPercent <= 0 || snap.Memory.UsedPercent > 100 {
		t.Errorf("memory percent out of range: %v", snap.Memory.UsedPercent)
	}
	if snap.Network == nil {
		t.Fatal("no network data")
	}
	if snap.Network.BytesSentPerSecond < 0 || snap.Network.BytesReceivedPerSecond < 0 {
		t.Errorf("negative network speed: %+v", snap.Network)
	}
	if first.Network != nil && (first.Network.BytesSentPerSecond != 0 || first.Network.BytesReceivedPerSecond != 0) {
		t.Errorf("first cycle must report zero speed: %+v", first.Network)
	}
	if len(snap.Processes) == 0 {
		t.Fatal("no processes")
	}
	if len(snap.Processes) > 10 {
		t.Errorf("more than 2*topN processes: %d", len(snap.Processes))
	}
	seen := map[int32]bool{}
	for _, p := range snap.Processes {
		if seen[p.PID] {
			t.Errorf("duplicated pid %d", p.PID)
		}
		seen[p.PID] = true
		if p.Name == "" {
			t.Errorf("process %d without name", p.PID)
		}
	}

	readings := map[string]float64{}
	for _, r := range snap.Readings {
		readings[r.SeriesID] = r.Value
	}
	for _, d := range s.Series() {
		if _, ok := readings[d.ID]; !ok {
			t.Errorf("series %s declared but not emitted", d.ID)
		}
	}
	if readings[models.SeriesCPUTotal] != snap.CPU.TotalPercent {
		t.Errorf("cpu reading and block disagree")
	}
}

func TestSimulatedIsDeterministicAndInRange(t *testing.T) {
	sim := NewSimulated()
	at := time.Unix(1_800_000_000, 0)
	a := &models.Snapshot{Timestamp: at}
	b := &models.Snapshot{Timestamp: at}
	_ = sim.Collect(context.Background(), a)
	_ = sim.Collect(context.Background(), b)
	if len(a.Readings) != 2 || a.Readings[0] != b.Readings[0] || a.Readings[1] != b.Readings[1] {
		t.Fatalf("simulated readings are not deterministic: %+v vs %+v", a.Readings, b.Readings)
	}
	for i := range 2000 {
		temp, hum := simulate(at.Add(time.Duration(i) * 5 * time.Second))
		if temp < -10 || temp > 50 || hum < 0 || hum > 100 {
			t.Fatalf("out of declared range: %v %v", temp, hum)
		}
	}
}

type fakeCollector struct {
	name string
	err  error
}

func (f fakeCollector) Name() string { return f.name }
func (f fakeCollector) Series() []models.SeriesDescriptor {
	return []models.SeriesDescriptor{{ID: f.name + ".value", Collector: f.name}}
}
func (f fakeCollector) Collect(_ context.Context, snap *models.Snapshot) error {
	if f.err != nil {
		return f.err
	}
	snap.AddReading(f.name+".value", 1)
	return nil
}

func TestManagerPublishesAndSurvivesCollectorFailure(t *testing.T) {
	m := NewManager(
		Identity{AgentID: "test", Hostname: "host", OperatingSystem: "linux", Architecture: "amd64"},
		[]Collector{fakeCollector{name: "broken", err: errors.New("boom")}, fakeCollector{name: "ok"}},
		time.Second, func() uint64 { return 42 }, quiet,
	)
	var received *models.Snapshot
	m.AddSink(func(s *models.Snapshot) { received = s })

	if m.Latest() != nil {
		t.Fatal("Latest must be nil before the first cycle")
	}
	now := time.Date(2026, 10, 13, 23, 0, 0, 999, time.FixedZone("x", -3*3600))
	snap := m.CollectOnce(context.Background(), now)

	if received != snap || m.Latest() != snap {
		t.Fatal("snapshot was not published")
	}
	if snap.AgentID != "test" || snap.UptimeSeconds != 42 {
		t.Fatalf("identity not filled: %+v", snap)
	}
	if snap.Timestamp.Location() != time.UTC || snap.Timestamp.Nanosecond() != 0 {
		t.Fatalf("timestamp must be UTC truncated to seconds: %v", snap.Timestamp)
	}
	if len(snap.Readings) != 1 || snap.Readings[0].SeriesID != "ok.value" {
		t.Fatalf("the healthy collector must still contribute: %+v", snap.Readings)
	}
	if len(m.Series()) != 2 {
		t.Fatalf("expected both collectors' series, got %+v", m.Series())
	}
}

func TestManagerRunStopsOnCancel(t *testing.T) {
	m := NewManager(Identity{AgentID: "t"}, []Collector{fakeCollector{name: "ok"}}, time.Hour, func() uint64 { return 0 }, quiet)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { m.Run(ctx); close(done) }()

	deadline := time.After(2 * time.Second)
	for m.Latest() == nil {
		select {
		case <-deadline:
			t.Fatal("Run did not collect immediately")
		default:
			time.Sleep(5 * time.Millisecond)
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not stop after cancel")
	}
}
