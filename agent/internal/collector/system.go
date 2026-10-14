package collector

import (
	"context"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/host"
	"github.com/shirou/gopsutil/v4/mem"
	gnet "github.com/shirou/gopsutil/v4/net"

	"gomonitor/agent/internal/models"
	"gomonitor/agent/internal/netinfo"
)

// System collects the metrics of the computer the agent runs on.
type System struct {
	topN  int
	rates RateCalculator
	procs processTracker

	mu         sync.RWMutex
	interfaces []models.InterfaceTraffic
	networkAt  time.Time
	cpuModel   string
}

func NewSystem(topProcesses int) *System {
	s := &System{topN: topProcesses}
	// Prime the CPU counters so the first cycle reports usage since startup
	// instead of zero.
	_, _ = cpu.Percent(0, true)
	return s
}

func (s *System) Name() string { return "system" }

func (s *System) Series() []models.SeriesDescriptor {
	pct := func(id, name, kind string) models.SeriesDescriptor {
		return models.SeriesDescriptor{ID: id, Collector: s.Name(), Name: name, Kind: kind,
			Unit: models.UnitPercent, Min: models.Float(0), Max: models.Float(100)}
	}
	return []models.SeriesDescriptor{
		pct(models.SeriesCPUTotal, "CPU total", models.KindCPU),
		pct(models.SeriesMemPercent, "Memoria usada", models.KindMemory),
		{ID: models.SeriesMemBytes, Collector: s.Name(), Name: "Memoria usada (bytes)", Kind: models.KindMemory,
			Unit: models.UnitBytes, Min: models.Float(0)},
		{ID: models.SeriesNetSentRate, Collector: s.Name(), Name: "Subida", Kind: models.KindNetworkUpload,
			Unit: models.UnitBytesPerSecond, Min: models.Float(0)},
		{ID: models.SeriesNetRecvRate, Collector: s.Name(), Name: "Bajada", Kind: models.KindNetworkDownload,
			Unit: models.UnitBytesPerSecond, Min: models.Float(0)},
	}
}

// Collect fills the typed blocks and the system series. Each part is
// independent: a failure in one is reported but does not drop the others.
func (s *System) Collect(ctx context.Context, snap *models.Snapshot) error {
	now := time.Now()
	var errs []error

	if c, err := collectCPU(ctx); err != nil {
		errs = append(errs, fmt.Errorf("cpu: %w", err))
	} else {
		snap.CPU = c
		snap.AddReading(models.SeriesCPUTotal, c.TotalPercent)
	}

	var totalMemory uint64
	if m, err := collectMemory(ctx); err != nil {
		errs = append(errs, fmt.Errorf("memory: %w", err))
	} else {
		totalMemory = m.TotalBytes
		snap.Memory = m
		snap.AddReading(models.SeriesMemPercent, m.UsedPercent)
		snap.AddReading(models.SeriesMemBytes, float64(m.UsedBytes))
	}

	if d, err := collectDisks(ctx); err != nil {
		errs = append(errs, fmt.Errorf("disks: %w", err))
	} else {
		snap.Disks = d
	}

	if n, err := s.collectNetwork(ctx, now); err != nil {
		errs = append(errs, fmt.Errorf("network: %w", err))
	} else {
		snap.Network = n
		snap.AddReading(models.SeriesNetSentRate, n.BytesSentPerSecond)
		snap.AddReading(models.SeriesNetRecvRate, n.BytesReceivedPerSecond)
	}

	if p, err := s.procs.collect(ctx, now, totalMemory, s.topN); err != nil {
		errs = append(errs, fmt.Errorf("processes: %w", err))
	} else {
		for i := range p {
			p[i].CPUPercent = round2(p[i].CPUPercent)
			p[i].MemoryPercent = round2(p[i].MemoryPercent)
		}
		snap.Processes = p
	}

	return errors.Join(errs...)
}

func collectCPU(ctx context.Context) (*models.CPU, error) {
	cores, err := cpu.PercentWithContext(ctx, 0, true)
	if err != nil {
		return nil, err
	}
	if len(cores) == 0 {
		return nil, errors.New("no CPU cores reported")
	}
	var sum float64
	for i, c := range cores {
		cores[i] = round2(clamp(c, 0, 100))
		sum += cores[i]
	}
	return &models.CPU{TotalPercent: round2(sum / float64(len(cores))), CorePercentages: cores}, nil
}

func collectMemory(ctx context.Context) (*models.Memory, error) {
	vm, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil {
		return nil, err
	}
	if vm.Total == 0 {
		return nil, errors.New("total memory reported as zero")
	}
	// Used is defined as total minus available so the three figures add up.
	available := min(vm.Available, vm.Total)
	used := vm.Total - available
	return &models.Memory{
		TotalBytes:     vm.Total,
		UsedBytes:      used,
		AvailableBytes: available,
		UsedPercent:    round2(float64(used) / float64(vm.Total) * 100),
	}, nil
}

// Filesystems and mount points that are not real storage the user cares about.
var (
	ignoredFilesystems = map[string]bool{
		"squashfs": true, "tmpfs": true, "devtmpfs": true, "overlay": true, "iso9660": true,
		"proc": true, "sysfs": true, "cgroup": true, "cgroup2": true, "devfs": true, "autofs": true,
		"efivarfs": true, "ramfs": true, "nsfs": true, "tracefs": true, "debugfs": true, "fuse.portal": true,
	}
	ignoredMountPrefixes = []string{"/snap/", "/var/lib/docker/", "/var/lib/containers/", "/boot/efi", "/run/", "/sys/", "/proc/", "/dev/"}
)

// relevantPartitions drops pseudo filesystems and keeps one mount point per
// device, so bind mounts and btrfs subvolumes are not listed twice.
func relevantPartitions(parts []disk.PartitionStat) []disk.PartitionStat {
	seen := map[string]bool{}
	var out []disk.PartitionStat
next:
	for _, p := range parts {
		if ignoredFilesystems[strings.ToLower(p.Fstype)] {
			continue
		}
		for _, prefix := range ignoredMountPrefixes {
			if strings.HasPrefix(p.Mountpoint+"/", prefix) || strings.HasPrefix(p.Mountpoint, prefix) {
				continue next
			}
		}
		if p.Device != "" {
			if seen[p.Device] {
				continue
			}
			seen[p.Device] = true
		}
		out = append(out, p)
	}
	return out
}

func collectDisks(ctx context.Context) ([]models.Disk, error) {
	parts, err := disk.PartitionsWithContext(ctx, false)
	if err != nil && len(parts) == 0 {
		return nil, err
	}
	disks := []models.Disk{}
	for _, p := range relevantPartitions(parts) {
		u, err := disk.UsageWithContext(ctx, p.Mountpoint)
		if err != nil || u.Total == 0 {
			continue // unmounted in the meantime, or not readable
		}
		disks = append(disks, models.Disk{
			MountPoint:     p.Mountpoint,
			Filesystem:     p.Fstype,
			TotalBytes:     u.Total,
			UsedBytes:      u.Used,
			AvailableBytes: u.Free,
			UsedPercent:    round2(u.UsedPercent),
		})
	}
	sort.Slice(disks, func(i, j int) bool { return disks[i].MountPoint < disks[j].MountPoint })
	return disks, nil
}

func (s *System) collectNetwork(ctx context.Context, now time.Time) (*models.Network, error) {
	stats, err := gnet.IOCountersWithContext(ctx, true)
	if err != nil {
		return nil, err
	}
	loopback := netinfo.LoopbackNames()
	cur := make(map[string]Counters, len(stats))
	for _, st := range stats {
		if loopback[st.Name] || netinfo.IsLoopbackName(st.Name) {
			continue
		}
		cur[st.Name] = Counters{Sent: st.BytesSent, Recv: st.BytesRecv}
	}
	rates := s.rates.Update(now, cur)

	total := &models.Network{}
	detail := make([]models.InterfaceTraffic, 0, len(cur))
	for name, c := range cur {
		r := rates[name]
		detail = append(detail, models.InterfaceTraffic{Name: name, Network: models.Network{
			BytesSentTotal: c.Sent, BytesReceivedTotal: c.Recv,
			BytesSentPerSecond: math.Round(r.Sent), BytesReceivedPerSecond: math.Round(r.Recv),
		}})
		// Bridges, containers and VPN tunnels carry traffic that is also
		// counted on the physical interface; adding them would double count.
		if netinfo.IsVirtualName(name) {
			continue
		}
		total.BytesSentTotal += c.Sent
		total.BytesReceivedTotal += c.Recv
		total.BytesSentPerSecond += r.Sent
		total.BytesReceivedPerSecond += r.Recv
	}
	total.BytesSentPerSecond = math.Round(total.BytesSentPerSecond)
	total.BytesReceivedPerSecond = math.Round(total.BytesReceivedPerSecond)
	sort.Slice(detail, func(i, j int) bool { return detail[i].Name < detail[j].Name })

	s.mu.Lock()
	s.interfaces, s.networkAt = detail, now
	s.mu.Unlock()
	return total, nil
}

// InterfaceTraffic returns the per-interface detail of the last cycle.
func (s *System) InterfaceTraffic() []models.InterfaceTraffic {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]models.InterfaceTraffic{}, s.interfaces...)
}

// Info describes the computer. Identity fields are filled by the caller.
func (s *System) Info(ctx context.Context) models.SystemInfo {
	var info models.SystemInfo
	if h, err := host.InfoWithContext(ctx); err == nil {
		info.Platform = strings.TrimSpace(h.Platform + " " + h.PlatformVersion)
		info.UptimeSeconds = h.Uptime
	}
	if n, err := cpu.CountsWithContext(ctx, true); err == nil {
		info.CPUCores = n
	}
	if vm, err := mem.VirtualMemoryWithContext(ctx); err == nil {
		info.MemoryTotalBytes = vm.Total
	}
	info.CPUModel = s.cachedCPUModel(ctx)
	return info
}

func (s *System) cachedCPUModel(ctx context.Context) string {
	s.mu.RLock()
	model := s.cpuModel
	s.mu.RUnlock()
	if model != "" {
		return model
	}
	if infos, err := cpu.InfoWithContext(ctx); err == nil && len(infos) > 0 {
		model = strings.TrimSpace(infos[0].ModelName)
	}
	s.mu.Lock()
	s.cpuModel = model
	s.mu.Unlock()
	return model
}

// Uptime returns the seconds since the computer booted, or zero if unknown.
func Uptime() uint64 {
	up, err := host.Uptime()
	if err != nil {
		return 0
	}
	return up
}

func round2(v float64) float64 { return math.Round(v*100) / 100 }

func clamp(v, lo, hi float64) float64 { return math.Max(lo, math.Min(hi, v)) }
