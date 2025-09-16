package common

import (
	"context"
	"sync"
	"sync/atomic"
)

type SafeChannel struct {
	ch     chan interface{}
	closed atomic.Bool
	frozen atomic.Bool
	once   sync.Once
}

func NewSafeChannel(size int) *SafeChannel {
	return &SafeChannel{
		ch: make(chan interface{}, size),
	}
}

func (s *SafeChannel) TrySend(value interface{}) bool {
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

func (s *SafeChannel) SendWait(ctx context.Context, value interface{}) bool {
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

func (s *SafeChannel) Receive() (interface{}, bool) {
	val, ok := <-s.ch
	return val, ok
}

func (s *SafeChannel) ReceiveChannel() <-chan interface{} {
	return s.ch
}

func (s *SafeChannel) Closed() bool {
	return s.closed.Load()
}

func (s *SafeChannel) Close() {
	s.once.Do(func() {
		s.frozen.Store(false)
		s.closed.Store(true)
		close(s.ch)
	})
}

func (s *SafeChannel) Frozen() bool {
	return s.frozen.Load()
}

func (s *SafeChannel) FreezeChannel() {
	s.frozen.Store(true)
}

func (s *SafeChannel) UnfreezeChannel() {
	s.frozen.Store(false)
}
