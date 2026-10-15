package rpe

import "time"

// Deadline uses a time.Time retaining Go's monotonic clock component.
type Deadline struct{ expiry time.Time }

func NewDeadline(deadlineMillis int64, now time.Time, offsetBoundMillis int64) (Deadline, error) {
	if deadlineMillis < 1 || deadlineMillis > MaxDeadlineUnixMillis || offsetBoundMillis < 0 {
		return Deadline{}, problem(CodeOutOfRange, HeaderDeadline, "invalid deadline or offset bound")
	}
	wall := now.UnixMilli()
	if wall < 0 || wall > MaxDeadlineUnixMillis {
		return Deadline{}, problem(CodeOutOfRange, HeaderDeadline, "unsupported local clock range")
	}
	remaining := deadlineMillis - wall
	if remaining <= 0 || offsetBoundMillis >= remaining {
		remaining = 0
	} else {
		remaining -= offsetBoundMillis
	}
	// time.Duration cannot represent arbitrary distant Unix deadlines.
	if remaining > int64((1<<63-1)/int64(time.Millisecond)) {
		return Deadline{}, problem(CodeOutOfRange, HeaderDeadline, "remaining duration cannot be represented")
	}
	return Deadline{now.Add(time.Duration(remaining) * time.Millisecond)}, nil
}
func (d Deadline) Remaining(now time.Time) time.Duration {
	v := d.expiry.Sub(now)
	if v < 0 {
		return 0
	}
	return v
}
func (d Deadline) RemainingMillis(now time.Time) int64 { return d.Remaining(now).Milliseconds() }
func (d Deadline) IsExpired(now time.Time) bool        { return !now.Before(d.expiry) }
func (d Deadline) Time() time.Time                     { return d.expiry }
