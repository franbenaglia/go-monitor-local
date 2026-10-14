package collector

import (
	"context"
	"math"
	"time"

	"gomonitor/agent/internal/models"
)

const (
	SeriesSimTemperature = "sim.temperature"
	SeriesSimHumidity    = "sim.humidity"
)

// Simulated emits plausible sensor readings without any hardware. It exists to
// develop and test the generic series path that real sensors will use.
type Simulated struct{}

func NewSimulated() *Simulated { return &Simulated{} }

func (Simulated) Name() string { return "simulated" }

func (s Simulated) Series() []models.SeriesDescriptor {
	return []models.SeriesDescriptor{
		{ID: SeriesSimTemperature, Collector: s.Name(), Name: "Temperatura (simulada)", Kind: models.KindTemperature,
			Unit: models.UnitCelsius, Min: models.Float(-10), Max: models.Float(50)},
		{ID: SeriesSimHumidity, Collector: s.Name(), Name: "Humedad (simulada)", Kind: models.KindHumidity,
			Unit: models.UnitPercent, Min: models.Float(0), Max: models.Float(100)},
	}
}

func (Simulated) Collect(_ context.Context, snap *models.Snapshot) error {
	temperature, humidity := simulate(snap.Timestamp)
	snap.AddReading(SeriesSimTemperature, temperature)
	snap.AddReading(SeriesSimHumidity, humidity)
	return nil
}

// simulate is a pure function of time: a slow ten-minute wave with a faster
// ripple on top, so charts show movement at every zoom level.
func simulate(t time.Time) (temperature, humidity float64) {
	seconds := float64(t.Unix())
	slow := math.Sin(2 * math.Pi * seconds / 600)
	ripple := math.Sin(2 * math.Pi * seconds / 37)
	temperature = round2(22 + 4*slow + 0.4*ripple)
	humidity = round2(55 - 12*slow + 1.5*ripple)
	return temperature, humidity
}
