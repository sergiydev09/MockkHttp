# MockkHttp mitmproxy Addon

Python addon for mitmproxy that intercepts and pauses HTTP/HTTPS traffic, allowing modification of responses in real-time from the IntelliJ plugin.

## Installation

```bash
# Install dependencies
pip3 install -r requirements.txt

# Or with Homebrew (Mac)
brew install mitmproxy
```

## Usage

### Recording Mode (capture only)

```bash
mitmdump \
  -s debug_interceptor.py \
  --set intercept_mode=recording \
  --listen-host 0.0.0.0 \
  --listen-port 8080
```

### Debug Mode (pause and edit)

```bash
mitmdump \
  -s debug_interceptor.py \
  --set intercept_mode=debug \
  --set plugin_port=9999 \
  --set plugin_client_port=8765 \
  --listen-host 0.0.0.0 \
  --listen-port 8080
```

### With Web UI

```bash
mitmweb \
  -s debug_interceptor.py \
  --set intercept_mode=debug \
  --web-host 127.0.0.1 \
  --web-port 8081
```

## Architecture

```
Plugin (IntelliJ)          mitmproxy Addon
Port 8765                  Port 9999
     ↑                          ↓
     │    POST /intercept       │
     │◄───────────────────────┤│
     │    (flow data)           │
     │                          │
     │                          │ await flow.wait_for_resume()
     │    POST /resume          │
     │──────────────────────────▶
     │    (modified response)   │
     │                          │ flow.resume()
```

## API

### Addon → Plugin

**POST** `http://localhost:8765/intercept`

Payload:
```json
{
  "flow_id": "uuid",
  "paused": true,
  "request": {
    "method": "GET",
    "url": "https://api.example.com/data",
    "headers": {...},
    "content": "..."
  },
  "response": {
    "status_code": 200,
    "headers": {...},
    "content": "..."
  },
  "timestamp": 1234567890.123,
  "duration": 0.456
}
```

### Plugin → Addon

**POST** `http://localhost:9999/resume`

Payload:
```json
{
  "flow_id": "uuid",
  "modified_response": {
    "status_code": 404,
    "headers": {
      "X-Custom": "value"
    },
    "content": "{\"error\": \"Not found\"}"
  }
}
```

**GET** `http://localhost:9999/status`

Response:
```json
{
  "status": "running",
  "mode": "debug",
  "intercepted_count": 2,
  "intercepted_flows": ["uuid1", "uuid2"]
}
```

## Testing

### Manual Test

```bash
# Terminal 1: Start addon
mitmdump -s debug_interceptor.py --set intercept_mode=debug

# Terminal 2: Make request via proxy
curl -x http://localhost:8080 http://httpbin.org/get

# Terminal 3: Resume the flow
curl -X POST http://localhost:9999/resume \
  -H "Content-Type: application/json" \
  -d '{"flow_id": "get-from-log"}'
```

### Verify Status

```bash
curl http://localhost:9999/status
```

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `intercept_mode` | `recording` | `recording` or `debug` |
| `plugin_port` | `9999` | Addon control server port |
| `plugin_client_port` | `8765` | Port where plugin listens |

## Logs

Logs use emojis for easy identification:

- 🚀 = Addon started
- 📤 = Request received
- 🔴 = Flow intercepted
- ⏸️  = Waiting for user
- ▶️  = Flow resumed
- ✏️  = Response modified
- ✅ = Operation successful
- ⚠️  = Warning
- ❌ = Error

## Troubleshooting

**Error: "Plugin not responding"**
- Verify that the plugin is running
- Verify that port 8765 is free
- Check firewall

**Error: "Flow not found"**
- The flow was already resumed
- The flow_id is incorrect
- The addon was restarted

**Error: "Cannot connect to plugin"**
- The plugin is not listening on 8765
- The plugin stopped
- Port blocked

## Development

### Structure

```python
class DebugInterceptor:
    def load(self, loader):       # Load options
    async def running(self):       # Start HTTP server
    async def done(self):          # Cleanup
    async def request(self, flow): # Request hook
    async def response(self, flow):# Response hook (CRITICAL)
    async def send_to_plugin(...): # Send to plugin
    async def handle_resume(...):  # /resume endpoint
    async def handle_status(...):  # /status endpoint
```

### Modify the Addon

```python
# Add new endpoint
async def handle_custom(self, request):
    return web.json_response({'custom': 'data'})

# Register in running()
self.app.router.add_get('/custom', self.handle_custom)
```

## References

- [mitmproxy Docs](https://docs.mitmproxy.org/stable/)
- [Addon Development](https://docs.mitmproxy.org/stable/addons/overview/)
- [Flow API](https://docs.mitmproxy.org/stable/api/mitmproxy/flow.html)
