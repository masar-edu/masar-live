package common

import (
	"context"
	"sync"
	"sync/atomic"
)

type SafeChannelByte struct {
	ch     chan []byte
	closed atomic.Bool
	frozen atomic.Bool
	once   sync.Once
}

func NewSafeChannelByte(size int) *SafeChannelByte {
	return &SafeChannelByte{ch: make(chan []byte, size)}
}

func (s *SafeChannelByte) TrySend(value []byte) bool {
	if s.closed.Load() || s.frozen.Load() {
		return false
	}
	select {
	case s.ch <- value:
		return true
	default:
		// full: drop
		return false
	}
}

func (s *SafeChannelByte) SendWait(ctx context.Context, value []byte) bool {
	if s.closed.Load() || s.frozen.Load() {
		return false
	}
	select {
	case s.ch <- value:
		return true
	case <-ctx.Done():
		return false
	}
}

func (s *SafeChannelByte) Receive() ([]byte, bool)       { v, ok := <-s.ch; return v, ok }
func (s *SafeChannelByte) ReceiveChannel() <-chan []byte { return s.ch }

func (s *SafeChannelByte) Closed() bool { return s.closed.Load() }

func (s *SafeChannelByte) Close() {
	s.once.Do(func() {
		s.frozen.Store(false)
		s.closed.Store(true)
		close(s.ch)
	})
}

func (s *SafeChannelByte) Frozen() bool     { return s.frozen.Load() }
func (s *SafeChannelByte) FreezeChannel()   { s.frozen.Store(true) }
func (s *SafeChannelByte) UnfreezeChannel() { s.frozen.Store(false) }
