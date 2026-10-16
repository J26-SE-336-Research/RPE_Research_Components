package rpe

import "sync"

type CancellationState struct{ Requested, DeadlineExceeded bool }

// CancellationToken retains intent only; it never kills work or rolls back writes.
type CancellationToken struct {
	mu    sync.Mutex
	state CancellationState
}

func (t *CancellationToken) RequestCancel(reason Reason) error {
	if reason != ReasonDeadline && reason != ReasonCancelled {
		return problem(CodeInvalidSyntax, "", "invalid cancellation reason")
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.state.Requested = true
	if reason == ReasonDeadline {
		t.state.DeadlineExceeded = true
	}
	return nil
}
func (t *CancellationToken) Snapshot() CancellationState {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.state
}

type LogicalCallState struct {
	mu                   sync.Mutex
	terminal, successful bool
}

func (s *LogicalCallState) RecordResult(r ResponseContext) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.terminal = s.terminal || r.TerminalState || r.DeadlineExceeded || r.CancellationTriggered
	s.successful = s.successful || r.Outcome == OutcomeOK
}
func (s *LogicalCallState) MarkTerminal() { s.mu.Lock(); defer s.mu.Unlock(); s.terminal = true }
func (s *LogicalCallState) RetryBlocked() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.terminal || s.successful
}
