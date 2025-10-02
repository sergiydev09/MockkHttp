"""
MockkHttp mitmproxy Addon
=========================

mitmproxy addon that intercepts and pauses HTTP/HTTPS flows,
allowing the IntelliJ plugin to modify responses in real-time.

Modes:
- recording: Only captures and sends to plugin (does not pause)
- debug: Intercepts, pauses and waits for plugin modifications
- mockk: Automatically applies mock rules from plugin (does not pause)

Usage:
  mitmdump -s debug_interceptor.py --set intercept_mode=mockk --set plugin_port=9999
"""

import asyncio
import logging
import json
import threading
from typing import Dict
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.request import Request, urlopen
from urllib.error import URLError
from mitmproxy import http, ctx

# Global dictionary to keep intercepted flows
intercepted_flows: Dict[str, http.HTTPFlow] = {}

# Logger
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class ControlHTTPHandler(BaseHTTPRequestHandler):
    """
    Handler for the control HTTP server (receives commands from the plugin)
    """

    def log_message(self, format, *args):
        """Suppress automatic HTTP server logs"""
        pass

    def do_GET(self):
        """Handle GET requests"""
        if self.path == '/status':
            self.handle_status()
        elif self.path.startswith('/mock-match'):
            self.handle_mock_match()
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        """Handle POST requests"""
        if self.path == '/resume':
            self.handle_resume()
        else:
            self.send_error(404, "Not Found")

    def handle_status(self):
        """
        GET /status - Return addon status
        """
        response = {
            'status': 'running',
            'mode': self.server.addon.mode,
            'intercepted_count': len(intercepted_flows),
            'intercepted_flows': list(intercepted_flows.keys()),
            'plugin_port': self.server.addon.plugin_port,
            'plugin_client_port': self.server.addon.plugin_client_port
        }

        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(response).encode('utf-8'))

    def handle_mock_match(self):
        """
        GET /mock-match?method=GET&url=https://... - Check if plugin has a mock rule for this request
        Returns mock data or empty response
        """
        try:
            from urllib.parse import urlparse, parse_qs, urlencode

            # Parse query parameters
            parsed = urlparse(self.path)
            params = parse_qs(parsed.query)

            method = params.get('method', [''])[0]
            url = params.get('url', [''])[0]

            if not method or not url:
                self.send_error(400, "Missing method or url parameter")
                return

            # Ask plugin if it has a mock rule
            try:
                from urllib.request import Request, urlopen

                # Use urlencode for proper escaping when forwarding to plugin
                query_params = {'method': method, 'url': url}
                query_string = urlencode(query_params)
                plugin_url = f"http://{self.server.addon.plugin_host}:{self.server.addon.plugin_client_port}/mock-match?{query_string}"

                req = Request(plugin_url)
                with urlopen(req, timeout=1) as response:
                    if response.status == 200:
                        # Plugin returned mock data
                        mock_data = response.read()
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.end_headers()
                        self.wfile.write(mock_data)
                    else:
                        # No mock found
                        self.send_response(404)
                        self.send_header('Content-Type', 'application/json')
                        self.end_headers()
                        self.wfile.write(b'{}')
            except:
                # Plugin not available or no mock
                self.send_response(404)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b'{}')

        except Exception as e:
            ctx.log.error(f"❌ Error in handle_mock_match: {e}")
            self.send_error(500, str(e))

    def handle_resume(self):
        """
        POST /resume - Resume a paused flow with optional modifications
        """
        try:
            # Read request body
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body.decode('utf-8'))

            flow_id = data.get('flow_id')

            if not flow_id:
                self.send_error(400, "Missing flow_id")
                return

            if flow_id not in intercepted_flows:
                ctx.log.warn(f"⚠️  Flow {flow_id} not found (already resumed or expired?)")
                self.send_error(404, "Flow not found")
                return

            flow = intercepted_flows[flow_id]

            # Apply modifications if present
            if 'modified_response' in data:
                mod = data['modified_response']

                # Modify status code
                if 'status_code' in mod:
                    flow.response.status_code = mod['status_code']
                    ctx.log.info(f"✏️  Modified status: {mod['status_code']}")

                # Modify headers
                if 'headers' in mod:
                    for key, value in mod['headers'].items():
                        flow.response.headers[key] = value
                    ctx.log.info(f"✏️  Modified headers: {len(mod['headers'])} headers")

                # Modify content
                if 'content' in mod:
                    content_str = mod['content']
                    flow.response.content = content_str.encode('utf-8')
                    ctx.log.info(f"✏️  Modified content: {len(content_str)} bytes")

                ctx.log.info(f"✅ Response modified for flow {flow_id}")
            else:
                ctx.log.info(f"▶️  Forwarding original response for flow {flow_id}")

            # ⭐ REANUDAR EL FLOW ⭐
            # Esto desbloquea el await flow.wait_for_resume()
            flow.resume()

            response = {
                'status': 'resumed',
                'flow_id': flow_id
            }

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response).encode('utf-8'))

        except Exception as e:
            ctx.log.error(f"❌ Error in handle_resume: {e}")
            self.send_error(500, str(e))


class DebugInterceptor:
    """
    Main addon for MockkHttp
    """

    def __init__(self):
        self.http_server = None
        self.server_thread = None
        self.mode = "recording"  # "recording", "debug", or "mockk"
        self.plugin_port = 9999
        self.plugin_host = "127.0.0.1"
        self.plugin_client_port = 8765  # Port where the plugin listens

    def load(self, loader):
        """
        Loads the addon options
        """
        loader.add_option(
            name="intercept_mode",
            typespec=str,
            default="recording",
            help="Mode: 'recording', 'debug', or 'mockk'"
        )
        loader.add_option(
            name="plugin_port",
            typespec=int,
            default=9999,
            help="Port for addon control server (receives /resume from plugin)"
        )
        loader.add_option(
            name="plugin_client_port",
            typespec=int,
            default=8765,
            help="Port where plugin listens (addon sends /intercept to plugin)"
        )

    def running(self):
        """
        Called when mitmproxy is running
        Initializes the HTTP server to receive commands from the plugin
        """
        self.mode = ctx.options.intercept_mode
        self.plugin_port = ctx.options.plugin_port
        self.plugin_client_port = ctx.options.plugin_client_port

        # Create HTTP server in separate thread
        def run_server():
            try:
                # Allow address reuse to avoid "Address already in use"
                import socket
                HTTPServer.allow_reuse_address = True

                self.http_server = HTTPServer((self.plugin_host, self.plugin_port), ControlHTTPHandler)
                self.http_server.addon = self  # Pass reference to addon
                ctx.log.info(f"🚀 MockkHttp control server listening on {self.plugin_host}:{self.plugin_port}")
                self.http_server.serve_forever()
            except OSError as e:
                if "Address already in use" in str(e):
                    ctx.log.warn(f"⚠️  Port {self.plugin_port} already in use. Control server not started.")
                    ctx.log.warn(f"   This may happen if a previous instance is still running.")
                else:
                    ctx.log.error(f"❌ Failed to start control server: {e}")

        self.server_thread = threading.Thread(target=run_server, daemon=True)
        self.server_thread.start()

        ctx.log.info(f"🚀 MockkHttp addon started")
        ctx.log.info(f"   Mode: {self.mode}")
        ctx.log.info(f"   Control server: http://{self.plugin_host}:{self.plugin_port}")
        ctx.log.info(f"   Plugin client: http://{self.plugin_host}:{self.plugin_client_port}")
        ctx.log.info(f"   UID filtering: Managed by iptables on emulator")

    def done(self):
        """
        Cleanup when mitmproxy shuts down
        """
        ctx.log.info("Shutting down MockkHttp addon...")
        if self.http_server:
            self.http_server.shutdown()

    def request(self, flow: http.HTTPFlow):
        """
        Hook executed when a request is received
        """
        if self.mode != "debug":
            return

        # Basic log in debug mode
        ctx.log.info(f"📤 Request: {flow.request.method} {flow.request.pretty_url}")

    async def response(self, flow: http.HTTPFlow):
        """
        Hook executed when a response is received from the server

        THIS IS THE KEY FUNCTION:
        - In recording mode: only sends to plugin
        - In debug mode: PAUSES the flow and waits for plugin response
        - In mockk mode: Queries and applies mock rules automatically
        - UID filtering: Done at iptables level on emulator (only traffic from selected app arrives)
        """
        if self.mode == "recording":
            # Recording mode: only send to plugin for logging
            await self.send_to_plugin(flow, pause=False)
            return

        elif self.mode == "debug":
            # Debug mode: PAUSE and wait
            ctx.log.info(f"🔴 INTERCEPTING: {flow.request.method} {flow.request.pretty_url}")

            # 1. Mark as intercepted
            flow.intercept()

            # 2. Save reference in global dictionary
            intercepted_flows[flow.id] = flow

            # 3. Send to plugin
            await self.send_to_plugin(flow, pause=True)

            # 4. ⭐⭐⭐ WAIT UNTIL PLUGIN CALLS flow.resume() ⭐⭐⭐
            # This line BLOCKS the flow until flow.resume() is called
            ctx.log.info(f"⏸️  Waiting for user input on flow {flow.id}")
            await flow.wait_for_resume()
            ctx.log.info(f"▶️  Flow {flow.id} resumed by user")

            # 5. Clean up
            if flow.id in intercepted_flows:
                del intercepted_flows[flow.id]

        elif self.mode == "mockk":
            # Mockk mode: Query plugin for mock rule and apply it
            mock_data = await self.check_for_mock_rule(flow)

            if mock_data:
                # Apply mock
                ctx.log.info(f"📋 APPLYING MOCK: {mock_data.get('rule_name', 'Unknown')} to {flow.request.method} {flow.request.pretty_url}")

                if 'status_code' in mock_data:
                    flow.response.status_code = mock_data['status_code']

                if 'headers' in mock_data:
                    for key, value in mock_data['headers'].items():
                        flow.response.headers[key] = value

                if 'content' in mock_data:
                    flow.response.content = mock_data['content'].encode('utf-8')

                # Mark flow as mocked
                flow.metadata['mock_applied'] = True
                flow.metadata['mock_rule_name'] = mock_data.get('rule_name', 'Unknown')
                flow.metadata['mock_rule_id'] = mock_data.get('rule_id', '')

            # Send to plugin for logging (with or without mock applied)
            await self.send_to_plugin(flow, pause=False)

    async def check_for_mock_rule(self, flow: http.HTTPFlow):
        """
        Queries the plugin if it has a mock rule that matches this request.
        Sends parsed URL instead of complete URL to avoid encoding problems.
        """
        try:
            from urllib.parse import urlencode, urlparse, parse_qs
            from urllib.request import Request, urlopen

            # Parse URL into components
            parsed_url = urlparse(flow.request.pretty_url)

            # Build params with structured URL
            params = {
                'method': flow.request.method,
                'host': parsed_url.hostname or '',
                'path': parsed_url.path or '/',
            }

            # Add query parameters with "query_" prefix
            if parsed_url.query:
                query_params = parse_qs(parsed_url.query)
                for key, values in query_params.items():
                    # Use first value if multiple
                    params[f'query_{key}'] = values[0] if values else ''

            # Now we can safely encode everything without worrying about & in URLs
            query_string = urlencode(params)
            plugin_url = f"http://{self.plugin_host}:{self.plugin_client_port}/mock-match?{query_string}"

            ctx.log.debug(f"📡 Querying plugin: {params['method']} {params['host']}{params['path']}")
            ctx.log.debug(f"   Query params: {[k.replace('query_', '') for k in params.keys() if k.startswith('query_')]}")

            req = Request(plugin_url)

            # Execute in thread to not block async
            loop = asyncio.get_event_loop()
            response_data = await loop.run_in_executor(None, self._query_plugin_for_mock, req)

            return response_data

        except Exception as e:
            ctx.log.debug(f"No mock rule found or plugin unavailable: {e}")
            return None

    def _query_plugin_for_mock(self, req):
        """Helper to query plugin for mock rule synchronously"""
        try:
            with urlopen(req, timeout=1) as response:
                if response.status == 200:
                    body = response.read()
                    data = json.loads(body.decode('utf-8'))
                    if data:  # Si hay data, hay mock
                        return data
            return None
        except:
            return None

    async def send_to_plugin(self, flow: http.HTTPFlow, pause: bool):
        """
        Sends flow information to the IntelliJ plugin

        POST http://localhost:8765/intercept
        Body: JSON with request and response
        """
        try:
            # Prepare request data
            request_data = {
                "method": flow.request.method,
                "url": flow.request.pretty_url,
                "host": flow.request.host,
                "path": flow.request.path,
                "headers": dict(flow.request.headers),
                "content": self._decode_content(flow.request.content) if flow.request.content else ""
            }

            # Prepare response data
            response_data = None
            if flow.response:
                response_data = {
                    "status_code": flow.response.status_code,
                    "reason": flow.response.reason,
                    "headers": dict(flow.response.headers),
                    "content": self._decode_content(flow.response.content) if flow.response.content else ""
                }

            # Complete payload
            payload = {
                "flow_id": flow.id,
                "paused": pause,
                "request": request_data,
                "response": response_data,
                "timestamp": flow.request.timestamp_start,
                "duration": (flow.response.timestamp_end - flow.request.timestamp_start) if flow.response else 0,
                "mock_applied": flow.metadata.get('mock_applied', False),
                "mock_rule_name": flow.metadata.get('mock_rule_name'),
                "mock_rule_id": flow.metadata.get('mock_rule_id')
            }

            # Enviar al plugin via HTTP POST usando urllib
            url = f"http://{self.plugin_host}:{self.plugin_client_port}/intercept"
            json_data = json.dumps(payload).encode('utf-8')

            req = Request(
                url,
                data=json_data,
                headers={'Content-Type': 'application/json'}
            )

            # Execute in thread to not block async
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, self._send_request, req, flow.id)

        except Exception as e:
            ctx.log.error(f"❌ Error sending to plugin: {e}")

    def _send_request(self, req, flow_id):
        """Helper to send HTTP request synchronously"""
        try:
            with urlopen(req, timeout=2) as response:
                if response.status == 200:
                    ctx.log.debug(f"✅ Sent flow {flow_id} to plugin")
                else:
                    ctx.log.warn(f"⚠️  Plugin responded with {response.status}")
        except URLError as e:
            if "timed out" in str(e):
                ctx.log.warn("⚠️  Plugin not responding (timeout)")
            elif "Connection refused" in str(e):
                ctx.log.warn("⚠️  Cannot connect to plugin (is it running?)")
            else:
                ctx.log.warn(f"⚠️  Error connecting to plugin: {e}")

    def _decode_content(self, content: bytes) -> str:
        """
        Decodes content bytes to string
        Tries UTF-8, then latin-1, then hex
        """
        if not content:
            return ""

        try:
            return content.decode('utf-8')
        except UnicodeDecodeError:
            try:
                return content.decode('latin-1')
            except:
                # If cannot decode, return as hex
                return content.hex()


# Addon instance (required by mitmproxy)
addons = [DebugInterceptor()]


"""
===================
USAGE INSTRUCTIONS
===================

1. RECORDING MODE (only capture, don't pause):

   mitmdump \
     -s debug_interceptor.py \
     --set intercept_mode=recording \
     --listen-host 0.0.0.0 \
     --listen-port 8080

2. DEBUG MODE (pause and wait for modifications):

   mitmdump \
     -s debug_interceptor.py \
     --set intercept_mode=debug \
     --set plugin_port=9999 \
     --set plugin_client_port=8765 \
     --listen-host 0.0.0.0 \
     --listen-port 8080

3. WITH WEB UI (easier for debugging):

   mitmweb \
     -s debug_interceptor.py \
     --set intercept_mode=debug \
     --web-host 127.0.0.1 \
     --web-port 8081

4. RESUME A FLOW MANUALLY (for testing):

   curl -X POST http://localhost:9999/resume \
     -H "Content-Type: application/json" \
     -d '{
       "flow_id": "flow-uuid",
       "modified_response": {
         "status_code": 200,
         "headers": {"X-Modified": "true"},
         "content": "{\"hacked\": true}"
       }
     }'

5. CHECK STATUS:

   curl http://localhost:9999/status

==================
IMPORTANT NOTES
==================

- The addon starts an HTTP server on port 9999 (configurable)
- The plugin must have an HTTP server on port 8765 (configurable)
- Flows are only paused in "debug" mode
- In "recording" mode, flows pass without pausing
- Paused flows wait indefinitely until resumed
- If the plugin is not running, requests will fail with timeout
- This addon uses ONLY the Python standard library (no pip install required)

==================
DEBUGGING
==================

To see detailed logs:

  mitmdump \
    -s debug_interceptor.py \
    --set intercept_mode=debug \
    --set console_eventlog_verbosity=debug

Important logs:
  🚀 = Addon started
  📤 = Request received
  🔴 = Flow intercepted
  ⏸️  = Waiting for user
  ▶️  = Flow resumed
  ✏️  = Response modified
  ✅ = Operation successful
  ⚠️  = Warning
  ❌ = Error
"""