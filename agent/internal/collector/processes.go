package collector

import (
	"context"
	"sort"
	"time"

	"github.com/shirou/gopsutil/v4/process"

	"gomonitor/agent/internal/models"
)

// SelectTop returns the union of the n processes using the most CPU and the n
// using the most memory, without duplicates, ordered by CPU descending.
func SelectTop(procs []models.Process, n int) []models.Process {
	if n <= 0 || len(procs) == 0 {
		return []models.Process{}
	}
	byCPU := append([]models.Process(nil), procs...)
	sort.SliceStable(byCPU, func(i, j int) bool { return lessCPU(byCPU[i], byCPU[j]) })
	byMem := append([]models.Process(nil), procs...)
	sort.SliceStable(byMem, func(i, j int) bool {
		if byMem[i].MemoryPercent != byMem[j].MemoryPercent {
			return byMem[i].MemoryPercent > byMem[j].MemoryPercent
		}
		return byMem[i].PID < byMem[j].PID
	})

	seen := make(map[int32]bool, 2*n)
	out := make([]models.Process, 0, 2*n)
	for _, list := range [][]models.Process{byCPU, byMem} {
		for _, p := range list[:min(n, len(list))] {
			if !seen[p.PID] {
				seen[p.PID] = true
				out = append(out, p)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return lessCPU(out[i], out[j]) })
	return out
}

func lessCPU(a, b models.Process) bool {
	if a.CPUPercent != b.CPUPercent {
		return a.CPUPercent > b.CPUPercent
	}
	if a.MemoryPercent != b.MemoryPercent {
		return a.MemoryPercent > b.MemoryPercent
	}
	return a.PID < b.PID
}

// procState is what is remembered about a process between two cycles.
type procState struct {
	createTime int64   // milliseconds since epoch; detects PID reuse
	cpuSeconds float64 // cumulative user + system CPU time
}

// cpuPercent is the CPU used between two cycles, where 100 means one full
// core. A process seen for the first time, or a PID that was reused by a new
// process, reports zero.
func cpuPercent(prev procState, known bool, cur procState, elapsedSeconds float64) float64 {
	if !known || elapsedSeconds <= 0 || prev.createTime != cur.createTime || cur.cpuSeconds < prev.cpuSeconds {
		return 0
	}
	return (cur.cpuSeconds - prev.cpuSeconds) / elapsedSeconds * 100
}

// processTracker measures per-process CPU usage between cycles.
type processTracker struct {
	prev   map[int32]procState
	prevAt time.Time
}

// collect returns the top processes. A process that exits mid-collection or
// denies access to its data is skipped.
func (t *processTracker) collect(ctx context.Context, now time.Time, totalMemory uint64, topN int) ([]models.Process, error) {
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return nil, err
	}
	elapsed := now.Sub(t.prevAt).Seconds()
	states := make(map[int32]procState, len(pids))
	handles := make(map[int32]*process.Process, len(pids))
	all := make([]models.Process, 0, len(pids))

	for _, pid := range pids {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		p, err := process.NewProcessWithContext(ctx, pid)
		if err != nil {
			continue
		}
		times, err := p.TimesWithContext(ctx)
		if err != nil {
			continue
		}
		created, err := p.CreateTimeWithContext(ctx)
		if err != nil {
			continue
		}
		cur := procState{createTime: created, cpuSeconds: times.User + times.System}
		states[pid] = cur

		var memPercent float64
		if mem, err := p.MemoryInfoWithContext(ctx); err == nil && mem != nil && totalMemory > 0 {
			memPercent = float64(mem.RSS) / float64(totalMemory) * 100
		}
		prev, known := t.prev[pid]
		all = append(all, models.Process{
			PID:           pid,
			CPUPercent:    cpuPercent(prev, known, cur, elapsed),
			MemoryPercent: memPercent,
		})
		handles[pid] = p
	}
	t.prev = states
	t.prevAt = now

	// Names are only read for the selected processes: it is one more read per
	// process and the rest are discarded anyway.
	top := SelectTop(all, topN)
	named := top[:0]
	for _, proc := range top {
		name, err := handles[proc.PID].NameWithContext(ctx)
		if err != nil || name == "" {
			continue
		}
		proc.Name = name
		named = append(named, proc)
	}
	return named, nil
}
