# Input Bridge TCP Protocol

The Android app exposes a loopback-only TCP server for the Android Studio
plugin. ADB forwards the same port from the computer to the device:

```bash
adb forward tcp:18080 tcp:18080
```

The client connects to `127.0.0.1:18080`; there is no HTTP path.
The server accepts one client at a time; a second client receives
`SERVER_BUSY` and is closed.

## Layers

The transport connection and the business protocol are separate:

- `:core:framing` handles byte framing only.
- `:core:connection-client` opens a TCP socket and handles client-side
  framing, I/O, and Ping/Pong.
- `:core:connection-server` accepts a TCP socket and handles server-side
  framing, I/O, single-client admission, and Ping/Pong.
- `:core:crypto` performs the transport handshake, derives session keys from
  the shared secret, and encrypts/decrypts transport records.
- `:protocol` defines the versioned JSON business messages.

Encryption is a transport concern and does not change the JSON business
protocol. The client and App receive only authenticated business messages.

## Frame format

After the transport handshake, every frame is:

```text
[4-byte unsigned big-endian encrypted-record length][nonce][ciphertext][authentication tag]
```

The receiver must read exactly the declared number of bytes. One TCP read may
contain part of a frame, one complete frame, or several frames. A length above
`512 KiB + 28 bytes`, a negative signed representation, incomplete payload, or
invalid authentication causes the connection to close. The length is checked
before allocating the payload buffer. The length itself is not encrypted: it
is needed to find the end of the encrypted record, but it contains no business
data.

The decrypted payload is one UTF-8 JSON object with a `type` discriminator.
Unknown fields are ignored; unknown message types and malformed JSON are
protocol errors.

## Encrypted transport

The App and plugin must be built with the same shared secret. Pass it to
Gradle with `-PinputBridgeSharedSecret=<secret>`. When the property is absent,
both products use the development default
`input-bridge-development-default-secret`; this default must not be used for a
production distribution.

Before any business JSON is sent, the client and server exchange a small
binary transport handshake. It authenticates both sides using the shared
secret and fresh random client/server nonces. A successful handshake derives
separate AES-256-GCM keys and nonce prefixes for each direction. The transport
protocol version is `1`; it is independent of the business protocol version.

Each encrypted record contains a 12-byte nonce, AES-GCM ciphertext including a
16-byte authentication tag, and no plaintext header besides the record length.
The nonce contains a direction-specific prefix and a monotonically increasing
record sequence. Reordered, replayed, tampered, or wrong-direction records are
rejected. The current design uses a pre-shared secret and does not provide
forward secrecy; there is no legacy unencrypted transport compatibility mode.

The maximum decrypted business payload remains `512 KiB`. The framing limit is
`512 KiB + 28 bytes` to account for the encrypted record overhead.

## Constants

| Name | Value |
| --- | --- |
| Business protocol version | `3` |
| Transport protocol version | `1` |
| Server host | `127.0.0.1` |
| Server and ADB-forward port | `18080` |
| Maximum frame payload | `512 KiB` |
| Server Ping interval | `15 seconds` |
| Server Pong timeout | `30 seconds` |

The client TCP connect timeout is `2 seconds`. Plugin requests wait at most
`4 seconds`; ADB commands retain their `5-second` timeout.

## Business handshake

After the encrypted transport handshake, the client must send `hello` first:

```json
{"type":"hello","protocolVersion":3,"requestId":"hello-1"}
```

The server responds with `hello_ack` and echoes the request ID:

```json
{
  "type": "hello_ack",
  "status": "ok",
  "appVersion": "<bridgeVersion>",
  "protocolVersion": 3,
  "serverTime": 1783780000000,
  "requestId": "hello-1"
}
```

The server then sends the current `text_snapshot`. The initial snapshot has no
request ID. An unsupported version returns `UNSUPPORTED_PROTOCOL_VERSION` and
closes the connection.

## Business messages

Commands that expect a response carry a unique `requestId`; the response must
echo it.

### `text_snapshot`

```json
{"type":"text_snapshot","text":"当前输入内容\n第二行 😀","version":17,"updatedAt":1783780000000,"requestId":null}
```

The server sends this after the handshake and in response to `get_snapshot`:

```json
{"type":"get_snapshot","requestId":"snapshot-1"}
```

### `text_changed`

The server pushes the latest in-memory repository state whenever it changes:

```json
{"type":"text_changed","text":"new text","version":18,"updatedAt":1783780001000}
```

The client must not poll to compensate for this event.

### `clear`

```json
{"type":"clear","expectedVersion":17,"requestId":"clear-1"}
```

Success:

```json
{"type":"clear_succeeded","clearedVersion":17,"newVersion":18,"requestId":"clear-1"}
```

If the expected version is stale:

```json
{"type":"version_conflict","currentVersion":19,"requestId":"clear-1"}
```

### `error`

Errors include `code`, `message`, optional `details`, and an optional
`requestId`. Codes include `SERVER_BUSY`, `INVALID_HANDSHAKE`,
`UNSUPPORTED_PROTOCOL_VERSION`, `MALFORMED_MESSAGE`,
`UNEXPECTED_MESSAGE`, `INVALID_EXPECTED_VERSION`, and
`TEXT_CLEAR_FAILED`.

## Connection heartbeat

Ping/Pong are business-protocol messages handled internally by the connection
modules, so App and plugin business code do not receive them:

```json
{"type":"ping","requestId":"ping-1"}
{"type":"pong","requestId":"ping-1"}
```

The server sends `ping` every 15 seconds. The client automatically replies
with `pong` using the same request ID. If the server does not receive the
matching Pong within 30 seconds, it closes the connection. Both sides also
reply to an incoming Ping, which keeps the connection behavior symmetric.

## Compatibility and errors

Only the business protocol has a negotiated version. Connection-layer changes
are shipped to the Android app and plugin together and do not provide a legacy
compatibility mode. The current migration intentionally does not preserve the
old transport.

The plugin reports connection closure or transport failure as offline and
recovers only when the user selects Reconnect. It may rebuild ADB forwarding
once after a connection failure.
