package execution

import (
	"bytes"
	"crypto/rand"
	"time"
	"unicode/utf8"
)

const eventCountLimit = 4096
const eventBytesLimit = 4 * 1024 * 1024
const eventChunkLimit = 16 * 1024

// Event is a retained, ordered observation. The result lives independently of
// this bounded window, so eviction never removes the authoritative outcome.
type Event struct {
	Sequence     uint64    `json:"sequence"`
	Type         string    `json:"type"`
	OccurredAt   time.Time `json:"occurredAt"`
	Channel      string    `json:"channel,omitempty"`
	Data         []byte    `json:"data,omitempty"`
	State        string    `json:"state,omitempty"`
	DroppedBytes uint64    `json:"droppedBytes,omitempty"`
}

func logBytes(record Result) uint64 {
	count := record.DroppedBytes
	for _, chunk := range record.Logs {
		count += uint64(len(chunk.Data))
	}
	return count
}

// appendEvents is called in the same durable critical section as result writes.
func appendEvents(record *Result, previous Result) {
	if record.StreamID == "" {
		record.StreamID = previous.StreamID
	}
	if record.StreamID == "" {
		record.StreamID = rand.Text()
	}
	if record.StreamID != previous.StreamID {
		previous = Result{}
	}
	record.Sequence, record.Events = previous.Sequence, previous.Events
	appendEvent := func(event Event) {
		record.Sequence++
		event.Sequence = record.Sequence
		event.OccurredAt = time.Now().UTC()
		record.Events = append(record.Events, event)
	}
	if record.DroppedBytes != previous.DroppedBytes {
		appendEvent(Event{Type: "log_truncated", DroppedBytes: record.DroppedBytes})
	}
	offset := record.DroppedBytes
	seen := logBytes(previous)
	for _, chunk := range record.Logs {
		end := offset + uint64(len(chunk.Data))
		if end > seen {
			start := uint64(0)
			if seen > offset {
				start = seen - offset
			}
			text := bytes.ToValidUTF8(chunk.Data[start:], []byte("\uFFFD"))
			for len(text) > 0 {
				size := min(len(text), eventChunkLimit)
				for size < len(text) && !utf8.RuneStart(text[size]) {
					size--
				}
				appendEvent(Event{Type: "log", Channel: chunk.Channel, Data: bytes.Clone(text[:size])})
				text = text[size:]
			}
		}
		offset = end
	}
	if record.State != previous.State || record.Cleanup != previous.Cleanup || record.CopyCleanup != previous.CopyCleanup {
		kind := "progress"
		if record.FinishedAt != nil {
			kind = "task_result"
		}
		appendEvent(Event{Type: kind, State: record.State})
	}
	size := 0
	for _, event := range record.Events {
		size += len(event.Data) + 256
	}
	for len(record.Events) > eventCountLimit || size > eventBytesLimit {
		size -= len(record.Events[0].Data) + 256
		record.Events[0] = Event{}
		record.Events = record.Events[1:]
	}
}
