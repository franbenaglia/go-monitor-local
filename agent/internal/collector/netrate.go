package collector

import "time"

// Counters are the cumulative byte counters of one network interface.
type Counters struct {
	Sent uint64
	Recv uint64
}

// Rates are bytes per second.
type Rates struct {
	Sent float64
	Recv float64
}

// RateCalculator turns cumulative counters into speeds by comparing two
// consecutive samples.
type RateCalculator struct {
	prev   map[string]Counters
	prevAt time.Time
}

// Update returns the speed of every interface in cur. An interface reports
// zero on the cycle where it first appears or where its counters went
// backwards (driver reset, counter wrap, interface re-created), so a reset
// never shows up as a huge or negative speed.
func (r *RateCalculator) Update(now time.Time, cur map[string]Counters) map[string]Rates {
	rates := make(map[string]Rates, len(cur))
	elapsed := now.Sub(r.prevAt).Seconds()
	valid := r.prev != nil && elapsed > 0

	for name, c := range cur {
		var rate Rates
		if p, ok := r.prev[name]; valid && ok {
			if c.Sent >= p.Sent {
				rate.Sent = float64(c.Sent-p.Sent) / elapsed
			}
			if c.Recv >= p.Recv {
				rate.Recv = float64(c.Recv-p.Recv) / elapsed
			}
		}
		rates[name] = rate
	}

	r.prev = cur
	r.prevAt = now
	return rates
}
