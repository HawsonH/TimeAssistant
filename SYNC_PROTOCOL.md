# TimeAssistant LAN Sync Protocol

This document is the interoperability contract for Android and desktop clients. Protocol version 1 is intentionally account-free: one device listens on the local network and displays a six-digit pairing code; the other connects with the host address, port, and code.

## Transport

- TCP using Java ServerSocket-compatible semantics.
- Default port: 37681. A caller may configure another port; port 0 is allowed only when hosting to request an ephemeral port.
- Each message is one frame: a 4-byte unsigned-size-compatible, big-endian signed integer N, followed by exactly N bytes of UTF-8 JSON.
- N must be between 1 and 2,097,152 bytes. Reject the frame before allocation when it is outside that range.
- Connect timeout: 5 seconds. Read timeout: 10 seconds.
- One connection carries one sync_request and one sync_response or error, then closes.
- JSON must be a single object. Duplicate object keys, invalid UTF-8, invalid JSON, and nesting deeper than 64 levels are rejected.

The protocol is designed for a trusted local network. The pairing code prevents accidental pairing but is not encryption. Do not expose the listener to the public internet. A future protocol version may add authenticated encryption.

## Identity and pairing

- protocolVersion is the integer 1.
- deviceId is a stable, caller-generated identifier matching [A-Za-z0-9._:-]{1,128}. It must not be an API key, hostname, or user-entered secret.
- pairingCode is exactly six decimal digits, including possible leading zeroes. Hosts generate it with a cryptographically secure random generator.
- requestId is a non-empty opaque string of at most 128 characters. A UUID is recommended.
- targetDeviceId binds a response to its intended receiver. A request may omit it during first pairing; later requests should include the remembered peer deviceId.

After a successful join, the joining side receives a Peer containing host, port, pairingCode, and deviceId. The calling layer may persist those fields and restore them with setPeer. The host can persist an incoming peer only when the request advertises listenPort. A sender must omit listenPort unless it has already bound a listener and is actively accepting sync connections on that port; a configured-but-unbound port is not a listenPort.

Android DeviceSyncManager.join is intentionally outbound-only and does not start a listener. DeviceSyncManager.joinBidirectionally first binds a local listener that accepts the supplied remote pairingCode, then sends the first sync_request with the bound listenPort. Binding must succeed before the request is sent. This shared pairing code and reachable port allow the original host to retain the joining device as a Peer and initiate later syncs in the reverse direction.

## Snapshot schema

All timestamps are Unix epoch milliseconds encoded as JSON integers. The canonical top-level shape is:

~~~json
{
  "tasks": [],
  "taskTombstones": [],
  "chats": [],
  "focusHistory": [],
  "currentFocus": null,
  "settings": {}
}
~~~

tasks, chats, and focusHistory contain objects with:

- id: required non-empty string, stable across devices.
- createdAt: creation timestamp.
- updatedAt: last modification timestamp. When present it wins over createdAt for conflict resolution.
- Additional domain fields are preserved. Task scheduling, completion, postponement, cancellation, parent/subtask, and duration fields belong in the task object.

taskTombstones records physical task deletions so an older task on another device is not resurrected. Each tombstone is an object with a required stable id and at least one integer version timestamp: deletedAt or updatedAt. When both timestamps are present, updatedAt is the tombstone version; otherwise deletedAt is the version. Additional fields are preserved.

Tombstones are merged by id using their version timestamp and the canonical JSON tie-breaker. The winning tombstone then competes with the winning task of the same id. The tombstone wins when its version is greater than or equal to the task's updatedAt (or createdAt when updatedAt is absent). A strictly newer task explicitly resurrects the task and removes the older tombstone. Output contains only the winner for a given id, and taskTombstones is sorted by id.

currentFocus is null or an object. It should contain updatedAt plus the active task/session state. The object with the greater updatedAt wins.

settings is an object merged one top-level key at a time. For reliable last-write-wins behavior, a setting should use a versioned value:

~~~json
{
  "focusMinutes": {"value": 25, "updatedAt": 1785672000000},
  "model": {"value": "example-model", "updatedAt": 1785672000000}
}
~~~

Unversioned conflicts use canonical JSON lexical order as a deterministic tie-breaker. This guarantees convergence but does not express user intent, so desktop implementations should emit updatedAt.

The key apiKey is forbidden at every nesting level. Matching is case-insensitive and ignores underscore or hyphen, so api_key and API-Key are also removed. SnapshotProvider.applyMergedSnapshot must interpret a missing API key as “preserve the local secret”, never as “clear the local secret”.

Unknown top-level fields are preserved using the same updatedAt/createdAt and canonical tie-break rules. Object keys are sorted in output, entity arrays are sorted by id, and numbers use canonical decimal form.

## Request

~~~json
{
  "type": "sync_request",
  "protocolVersion": 1,
  "requestId": "2c748bb8-d574-4b9b-bb77-bc676d3212e6",
  "deviceId": "android-a1b2c3",
  "targetDeviceId": "desktop-7f8e9d",
  "pairingCode": "042731",
  "listenPort": 37681,
  "snapshot": {
    "tasks": [],
    "taskTombstones": [],
    "chats": [],
    "focusHistory": [],
    "currentFocus": null,
    "settings": {}
  }
}
~~~

targetDeviceId and listenPort are optional during the first exchange. The receiver validates type, protocolVersion, requestId, pairingCode, deviceId, and targetDeviceId when supplied. A device must reject its own deviceId as the remote identity.

The host merges its current snapshot with the request snapshot, applies the merged result locally, and returns that same merged snapshot.

## Success response

~~~json
{
  "type": "sync_response",
  "protocolVersion": 1,
  "requestId": "2c748bb8-d574-4b9b-bb77-bc676d3212e6",
  "deviceId": "desktop-7f8e9d",
  "targetDeviceId": "android-a1b2c3",
  "pairingCode": "042731",
  "snapshot": {
    "tasks": [],
    "taskTombstones": [],
    "chats": [],
    "focusHistory": [],
    "currentFocus": null,
    "settings": {}
  }
}
~~~

The requester validates the echoed requestId, pairingCode, targetDeviceId, and remembered peer deviceId before applying data. It re-merges the response with its latest local snapshot so an edit made while the request was in flight is not silently discarded.

## Error response

~~~json
{
  "type": "error",
  "protocolVersion": 1,
  "requestId": "2c748bb8-d574-4b9b-bb77-bc676d3212e6",
  "deviceId": "desktop-7f8e9d",
  "targetDeviceId": "android-a1b2c3",
  "errorCode": "INVALID_PAIRING_CODE",
  "message": "The six-digit pairing code was rejected."
}
~~~

Defined errorCode values:

- INVALID_ARGUMENT
- STOPPED
- NOT_PAIRED
- IO_ERROR
- TIMEOUT
- MESSAGE_TOO_LARGE
- MALFORMED_MESSAGE
- MALFORMED_SNAPSHOT
- UNSUPPORTED_PROTOCOL
- INVALID_PAIRING_CODE
- INVALID_DEVICE_ID
- PEER_MISMATCH
- SNAPSHOT_PROVIDER_ERROR
- REMOTE_ERROR

Error messages are intentionally generic and must never contain raw snapshots, credentials, exception text, or API responses. Android DeviceSyncManager dispatches all callbacks through the main-thread executor.

## Merge algorithm

1. Strictly parse both snapshots and recursively remove forbidden API-key fields.
2. For tasks, chats, and focusHistory, group by id.
3. For duplicate ids, select the greater updatedAt; otherwise select the greater createdAt.
4. Merge taskTombstones by id using updatedAt when present, otherwise deletedAt.
5. Resolve each task against its tombstone: deletion wins an equal version; only a strictly newer task survives and removes the tombstone.
6. For equal timestamps within the same entity kind, select the lexically greater canonical JSON object.
7. Sort the resulting arrays by id.
8. Select currentFocus by updatedAt, with the same canonical tie-breaker.
9. Merge settings per key, excluding API keys. Prefer versioned setting objects.
10. Sort every object’s keys and serialize without insignificant whitespace.

This algorithm is commutative for valid snapshots: swapping local and remote inputs yields identical canonical JSON.
