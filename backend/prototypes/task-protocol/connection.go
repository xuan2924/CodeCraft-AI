package protocol

// Session models authoritative authentication, including an unavailable database.
type Session struct {
	Valid, Available bool
	Expires          int
}

// Connection models an SSE authorization gate. Time is injected in seconds.
type Connection struct {
	session   *Session
	nextCheck int
	closed    bool
}

// Connect checks identity before opening a model connection.
func Connect(session *Session, now int) *Connection {
	connection := &Connection{session: session, nextCheck: now + 5}
	connection.CanSend(now)
	return connection
}

// CanSend checks authority before EACH batch; admitted bytes cannot be recalled.
func (c *Connection) CanSend(now int) bool {
	if !c.session.Available || !c.session.Valid || now >= c.session.Expires {
		c.closed = true
	}
	return !c.closed
}

// Tick checks idle connections at least every five seconds in the model clock.
func (c *Connection) Tick(now int) {
	if now >= c.nextCheck {
		c.CanSend(now)
		c.nextCheck = now + 5
	}
}

// IsClosed observes connection closure without triggering an authorization check.
func (c *Connection) IsClosed() bool { return c.closed }

// NotifyRevoked closes a connection promptly; Tick covers a lost notification.
func (c *Connection) NotifyRevoked() { c.closed = true }

// EventConsumer models sequence deduplication for one already validated stream ID.
type EventConsumer struct {
	cursor        int
	needsSnapshot bool
}

// NewEventConsumer starts from an atomically installed snapshot cursor.
func NewEventConsumer(cursor int) *EventConsumer { return &EventConsumer{cursor: cursor} }

// Accept ignores duplicates and refuses gaps until a fresh snapshot is installed.
func (c *EventConsumer) Accept(event Event) bool {
	if c.needsSnapshot || event.Sequence <= c.cursor {
		return false
	}
	if event.Sequence != c.cursor+1 {
		c.needsSnapshot = true
		return false
	}
	c.cursor = event.Sequence
	return true
}

// NeedsSnapshot reports whether a gap made incremental observation unsafe.
func (c *EventConsumer) NeedsSnapshot() bool { return c.needsSnapshot }
