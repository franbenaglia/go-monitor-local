// Package models defines the data exchanged between the agent and its clients.
// The JSON shape is specified in docs/PROTOCOL.md.
package models

import "time"

// ProtocolVersion is reported by the agent so clients can detect incompatibilities.
const ProtocolVersion = 1

// SeriesDescriptor describes one scalar quantity the agent measures over time.
type SeriesDescriptor struct {
	ID        string   `json:"id"`
	Collector string   `json:"collector"`
	Name      string   `json:"name"`
	Kind      string   `json:"kind"`
	Unit      string   `json:"unit"`
	Min       *float64 `json:"min,omitempty"`
	Max       *float64 `json:"max,omitempty"`
}

// Reading is the value of a series at the snapshot's timestamp.
type Reading struct {
	SeriesID string  `json:"series_id"`
	Value    float64 `json:"value"`
}

// Snapshot is what the agent produces on every collection cycle. The typed
// blocks are only present when the system collector is enabled.
type Snapshot struct {
	AgentID         string    `json:"agent_id"`
	Hostname        string    `json:"hostname"`
	OperatingSystem string    `json:"operating_system"`
	Architecture    string    `json:"architecture"`
	Timestamp       time.Time `json:"timestamp"`
	UptimeSeconds   uint64    `json:"uptime_seconds"`
	Readings        []Reading `json:"readings"`

	CPU       *CPU      `json:"cpu,omitempty"`
	Memory    *Memory   `json:"memory,omitempty"`
	Disks     []Disk    `json:"disks,omitempty"`
	Network   *Network  `json:"network,omitempty"`
	Processes []Process `json:"processes,omitempty"`
}

// AddReading appends a reading to the snapshot.
func (s *Snapshot) AddReading(seriesID string, value float64) {
	s.Readings = append(s.Readings, Reading{SeriesID: seriesID, Value: value})
}

type CPU struct {
	TotalPercent    float64   `json:"total_percent"`
	CorePercentages []float64 `json:"core_percentages"`
}

type Memory struct {
	TotalBytes     uint64  `json:"total_bytes"`
	UsedBytes      uint64  `json:"used_bytes"`
	AvailableBytes uint64  `json:"available_bytes"`
	UsedPercent    float64 `json:"used_percent"`
}

type Disk struct {
	MountPoint     string  `json:"mount_point"`
	Filesystem     string  `json:"filesystem"`
	TotalBytes     uint64  `json:"total_bytes"`
	UsedBytes      uint64  `json:"used_bytes"`
	AvailableBytes uint64  `json:"available_bytes"`
	UsedPercent    float64 `json:"used_percent"`
}

type Network struct {
	BytesSentTotal         uint64  `json:"bytes_sent_total"`
	BytesReceivedTotal     uint64  `json:"bytes_received_total"`
	BytesSentPerSecond     float64 `json:"bytes_sent_per_second"`
	BytesReceivedPerSecond float64 `json:"bytes_received_per_second"`
}

// InterfaceTraffic is the per-interface detail served by /api/v1/network.
type InterfaceTraffic struct {
	Name string `json:"name"`
	Network
}

type Process struct {
	PID           int32   `json:"pid"`
	Name          string  `json:"name"`
	CPUPercent    float64 `json:"cpu_percent"`
	MemoryPercent float64 `json:"memory_percent"`
}

// SystemInfo is the mostly static description served by /api/v1/system.
type SystemInfo struct {
	AgentID          string `json:"agent_id"`
	AgentVersion     string `json:"agent_version"`
	Hostname         string `json:"hostname"`
	OperatingSystem  string `json:"operating_system"`
	Platform         string `json:"platform"`
	Architecture     string `json:"architecture"`
	CPUModel         string `json:"cpu_model"`
	CPUCores         int    `json:"cpu_cores"`
	MemoryTotalBytes uint64 `json:"memory_total_bytes"`
	UptimeSeconds    uint64 `json:"uptime_seconds"`
}

// HistoryPoint aggregates the readings of one series inside a time bucket.
type HistoryPoint struct {
	T   time.Time `json:"t"`
	Avg float64   `json:"avg"`
	Min float64   `json:"min"`
	Max float64   `json:"max"`
}

// Series identifiers and vocabulary shared by collectors.
const (
	SeriesCPUTotal    = "cpu.total_percent"
	SeriesMemPercent  = "memory.used_percent"
	SeriesMemBytes    = "memory.used_bytes"
	SeriesNetSentRate = "network.bytes_sent_per_second"
	SeriesNetRecvRate = "network.bytes_received_per_second"

	KindCPU             = "cpu"
	KindMemory          = "memory"
	KindNetworkUpload   = "network_upload"
	KindNetworkDownload = "network_download"
	KindTemperature     = "temperature"
	KindHumidity        = "humidity"
	KindGeneric         = "generic"

	UnitPercent        = "percent"
	UnitBytes          = "bytes"
	UnitBytesPerSecond = "bytes_per_second"
	UnitCelsius        = "celsius"
)

// Float returns a pointer to v, for the optional Min and Max of a descriptor.
func Float(v float64) *float64 { return &v }
