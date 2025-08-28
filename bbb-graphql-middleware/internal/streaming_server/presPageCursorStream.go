package streamingserver

import (
	"bytes"
	"encoding/json"
	"maps"
	"sync"

	"bbb-graphql-middleware/internal/common"
)

var (
	QueryIdPlaceholder        = "--------------QUERY-ID--------------" // 36 chars
	QueryIdPlaceholderInBytes = []byte(QueryIdPlaceholder)
)

func HandleSendCursorPositionEvtMsg(receivedMessage common.RedisMessage, browserConnectionsMutex *sync.RWMutex, browserConnections map[string]*common.BrowserConnection) {
	receivedCursorIsFromViewer := receivedMessage.Core.Body["userIsViewer"].(bool)
	xPercent := receivedMessage.Core.Body["xPercent"].(float64)
	yPercent := receivedMessage.Core.Body["yPercent"].(float64)

	item := map[string]any{
		"xPercent":   xPercent,
		"yPercent":   yPercent,
		"userId":     receivedMessage.Core.Header.UserId,
		"__typename": "pres_page_cursor",
	}

	browserResponseData := map[string]any{
		"id":   QueryIdPlaceholder,
		"type": "next",
		"payload": map[string]any{
			"data": map[string]any{
				"pres_page_cursor_stream": []any{
					item,
				},
			},
		},
	}
	jsonDataNext, _ := json.Marshal(browserResponseData)

	browserConnectionsMutex.RLock()
	for _, browserConnection := range browserConnections {
		if browserConnection.MeetingId == receivedMessage.Core.Header.MeetingId {
			userHasViewersCursorLocked := browserConnection.BBBWebSessionVariables["x-hasura-cursorlockeduserid"] == browserConnection.UserId

			if !receivedCursorIsFromViewer || !userHasViewersCursorLocked { // check for lock settings "See other viewers cursors"
				browserConnection.ActiveStreamingsMutex.RLock()
				if queryId, exists := browserConnection.ActiveStreamings["getCursorCoordinatesStream"]; exists {
					queryIdInBytes := []byte(queryId)
					browserConnection.FromHasuraToBrowserChannel.Send(bytes.Replace(jsonDataNext, QueryIdPlaceholderInBytes, queryIdInBytes, 1))
				}
				browserConnection.ActiveStreamingsMutex.RUnlock()
			}
		}
	}
	browserConnectionsMutex.RUnlock()

	StoreCursorsCache(
		receivedMessage.Core.Header.MeetingId,
		receivedMessage.Core.Header.UserId,
		item,
	)
}

func SendPreviousCursorPosition(browserConnection *common.BrowserConnection, queryId string) {
	previousMessages, existsPreviousMessages := GetCursorsCache(browserConnection.MeetingId)
	if existsPreviousMessages {
		items := make([]any, 0, len(previousMessages))
		for _, message := range previousMessages {
			items = append(items, message)
		}

		browserResponseData := map[string]any{
			"id":   queryId,
			"type": "next",
			"payload": map[string]any{
				"data": map[string]any{
					"pres_page_cursor_stream": items,
				},
			},
		}
		jsonDataNext, _ := json.Marshal(browserResponseData)
		browserConnection.FromHasuraToBrowserChannel.Send(jsonDataNext)
	}
}

// the cache will use meetingId + userId as keys, as it needs to store only the last position for each user
var (
	CursorsCache      = make(map[string]map[string]map[string]any)
	CursorsCacheMutex sync.RWMutex
)

func GetCursorsCache(meetingId string) (map[string]map[string]any, bool) {
	CursorsCacheMutex.RLock()
	defer CursorsCacheMutex.RUnlock()
	rows, ok := CursorsCache[meetingId]
	if !ok {
		return nil, false
	}
	// Deep copy the map
	copyRows := make(map[string]map[string]any, len(rows))
	for userId, row := range rows {
		newRow := make(map[string]any, len(row))
		maps.Copy(newRow, row)
		copyRows[userId] = newRow
	}

	return copyRows, true
}

func StoreCursorsCache(meetingId string, userId string, row map[string]any) {
	CursorsCacheMutex.Lock()
	defer CursorsCacheMutex.Unlock()

	if _, exists := CursorsCache[meetingId]; !exists {
		CursorsCache[meetingId] = make(map[string]map[string]any)
	}
	CursorsCache[meetingId][userId] = row
}

func RemoveMeetingCursorsCache(meetingId string) {
	CursorsCacheMutex.Lock()
	defer CursorsCacheMutex.Unlock()
	delete(CursorsCache, meetingId)
}

func RemoveUserCursorsCache(meetingId string, userId string) {
	CursorsCacheMutex.Lock()
	defer CursorsCacheMutex.Unlock()

	if _, exists := CursorsCache[meetingId]; exists {
		delete(CursorsCache[meetingId], userId)
	}
}
