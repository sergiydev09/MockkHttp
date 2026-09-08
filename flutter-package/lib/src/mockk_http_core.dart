import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'ios_bundle_info.dart';
import 'mockk_http_client.dart';
import 'http_overrides.dart';
import 'models.dart';

/// Core MockkHttp interceptor logic shared between dio and HttpOverrides.
///
/// Implements the same mode flow as the Android library:
/// - IDLE: no capture session — pass through, capture nothing
/// - RECORDING: capture traffic, don't block
/// - DEBUG: capture traffic, block for user modification
/// - MOCKK: return mock if available, no block
/// - MOCKK_DEBUG: return mock if available, block for user modification
class MockkHttpCore {
  final MockkHttpPluginClient client;
  final String? packageName;
  final String? projectId;

  /// Enable/disable interceptor globally.
  static bool isEnabled = true;

  /// No capture session owns this app's traffic: nobody is listening, so there is
  /// nothing to buffer, serialise or send. The plugin answers this whenever no
  /// project is registered — i.e. whenever nobody has pressed Start — and also
  /// when a running session's package filter excludes this app.
  static const String modeIdle = 'IDLE';

  static const String modeRecording = 'RECORDING';
  static const String modeDebug = 'DEBUG';
  static const String modeMockk = 'MOCKK';
  static const String modeMockkDebug = 'MOCKK_DEBUG';

  /// The mode to act on for one request — the single place the fallback lives.
  ///
  /// The fallback used to be [modeRecording], which turned "I do not know" into
  /// "record everything": with the plugin idle (or unreachable mid-request) the app
  /// still buffered every response body in memory and opened a second socket per
  /// request to ship a flow the plugin immediately dropped. [modeIdle] is the cheap
  /// reading of the same uncertainty, and it self-corrects on the very next request.
  static String modeOf(MockCheckResponse? check) => check?.mode ?? modeIdle;

  /// Whether the two capture layers coordinate so that ONE logical request is captured once.
  ///
  /// This used to be a 500 ms window keyed on the request: a second identical request inside
  /// it was dropped inside the app — never captured, never mockable, no counter anywhere. Two
  /// screens asking for the same forecast 12 ms apart is normal app behaviour, and so is an
  /// immediate retry, which is exactly the thing `await_flow count:2` exists to observe. A
  /// log that silently discards what the app sent is not a source of truth.
  ///
  /// What the window was really for is one request seen by TWO layers: a `dio` interceptor on
  /// top, and the global [MockkHttpOverrides] underneath it (dio's default adapter builds its
  /// `HttpClient` through `HttpOverrides.global`). That is a question of identity, so the outer
  /// layer gives the request one: a claim with a nonce, carried down to dart:io in a Dart zone
  /// that the adapter wrapper opens around the real adapter. The inner layer steps
  /// aside for exactly the request that carries the nonce it knows, and for nothing else — a
  /// genuine second request, from dio or from any other stack, carries no nonce (or its own)
  /// and is captured on its own. No key, no clock, nothing to misattribute.
  static bool enableDeduplication = true;

  /// How the outer layer hands its claim to the inner layer: as a zone value. The dio
  /// interceptor wraps the Dio's adapter and runs the real one inside a zone carrying the claim;
  /// the inner layer's `openUrl`, reached from that zone however many awaits later, reads it
  /// back. Nothing of it ever touches the request the app wrote, on any adapter.
  static const Symbol zoneClaimKey = #mockkHttpInnerPassClaim;

  /// Claims nobody ever came back for — a request resolved by a later interceptor without
  /// running the response chain, so neither the inner layer nor `onResponse` saw it — are
  /// garbage, not a hazard: only their own nonce could ever consume them. There is no expiry by
  /// age: a claim's age says nothing about whether its request is still alive (one held for
  /// half a minute by a token-refresh lock is alive and must still be honoured), and the last
  /// clock in this package was the 500 ms window that started all this. The map is capped by
  /// size only, dropping the oldest entries, at a count no live traffic reaches — and every
  /// eviction is counted in the report (`claims_evicted`), so if it ever happens it is visible.
  static const int _maxClaims = 16384;

  /// Insertion-ordered, so the oldest claim is the first key.
  static final Map<String, InnerPassClaim> _innerPassClaims = {};

  // ── the numbers ────────────────────────────────────────────────────────────────────────────
  //
  // Nothing in this package drops a request any more, so there is no "dropped" counter to
  // publish. What CAN be checked from outside is that one request is one flow, and these are
  // the terms of that check: every message the package sends carries them (see [clientReport]),
  // and the plugin shows the latest under `client` on `status` and on the flow listing.
  static int _flowsSentByDio = 0;
  static int _flowsSentByIo = 0;
  static int _claimsMade = 0;
  static int _passesYielded = 0;
  static int _claimsWithdrawn = 0;
  static int _stoodBack = 0;
  static int _claimsUntakenBeneath = 0;
  static int _claimsNotCarried = 0;
  static int _claimsNotFetched = 0;
  static int _claimsUnresolved = 0;
  static int _wrapperReplaced = 0;
  static int _claimsEvicted = 0;
  static int _beneathWitnessEvicted = 0;
  static int _reportSeq = 0;

  /// Changes every time the app process starts, so a reader can tell "the app restarted and its
  /// counters begin at zero" from "the app went quiet". [_startedAt] says when.
  static final String _runId = _randomHex(8);
  static final int _startedAt = DateTime.now().millisecondsSinceEpoch;

  /// What the package tells the plugin about itself with every message: library, version,
  /// platform, how it coordinates its layers, and the counters above. Older plugins ignore the
  /// field; the 1.8.0 plugin exposes the latest report as `client`.
  static Map<String, dynamic> clientReport() => {
        'library': 'mockk_http',
        'version': MockkHttp.version,
        'platform': MockkHttp.platformLabel(),
        'run_id': _runId,
        'started_at': _startedAt,
        // Every message opens its own socket, so two can be served out of the order they were
        // built; the plugin keeps the report with the highest seq per run, never an older one.
        'seq': ++_reportSeq,
        'dedup': {
          'enabled': enableDeduplication,
          'window_ms': 0,
          'controllable': false
        },
        'caps': const ['identityClaims', 'lineFramed', 'idle'],
        'stats': {
          'flows_sent': _flowsSentByDio + _flowsSentByIo,
          'flows_sent_by_dio': _flowsSentByDio,
          'flows_sent_by_io': _flowsSentByIo,
          'claims_made': _claimsMade,
          'passes_yielded': _passesYielded,
          'claims_withdrawn': _claimsWithdrawn,
          'stood_back': _stoodBack,
          // The two ways a claim ends without being taken, told apart by a fact the wrapper
          // records on the claim itself: whether the fetch went down through it at all.
          'claims_untaken_beneath': _claimsUntakenBeneath,
          'claims_not_carried': _claimsNotCarried,
          'claims_not_fetched': _claimsNotFetched,
          'claims_unresolved': _claimsUnresolved,
          'wrapper_replaced': _wrapperReplaced,
          'beneath_witness_evicted': _beneathWitnessEvicted,
          'claims_evicted': _claimsEvicted,
          // Claims alive right now: requests the dio layer let go down that have not ended. A
          // number that stays put with nothing in flight is a request that never came back to
          // this layer — a cache interceptor that resolved without propagating, most often — and
          // that request is not in the flow list (audit round 13, AX).
          'claims_pending': _innerPassClaims.length,
        },
      };

  /// Zeroes the counters. Test seam.
  static void resetStats() {
    _flowsSentByDio = 0;
    _flowsSentByIo = 0;
    _claimsMade = 0;
    _passesYielded = 0;
    _claimsWithdrawn = 0;
    _stoodBack = 0;
    _claimsUntakenBeneath = 0;
    _claimsNotCarried = 0;
    _claimsNotFetched = 0;
    _claimsUnresolved = 0;
    _wrapperReplaced = 0;
    _beneathWitnessEvicted = 0;
    _claimsEvicted = 0;
  }

  /// dio's adapter was found replaced — by the app, since the wrapper was last put on. Any
  /// request in flight at that moment went down without its claim.
  static void noteWrapperReplaced() => _wrapperReplaced++;

  /// The dio interceptor stood back for a request because the global override captures it.
  static void noteStoodBack() => _stoodBack++;

  MockkHttpCore({
    MockkHttpPluginClient? client,
    this.packageName,
    this.projectId,
  }) : client = client ?? MockkHttpPluginClient();

  /// Clears every outstanding claim. Test seam: the map is static, so without this one test's
  /// requests leak into the next one's.
  static void resetDeduplicationState() {
    _innerPassClaims.clear();
    _liveByIdentity.clear();
    _unclaimedBeneath.clear();
  }

  /// What the layer beneath saw go past WITHOUT a claim, by identity (method + URL) and order
  /// (a sequence, no clock), bounded by size. A claim that ends untaken asks here whether the
  /// layer beneath saw its request while the claim was alive: that is what a fetch through an
  /// adapter the app swapped in looks like from below, and it is the one witness of a duplicate
  /// that is not a guess about adapters or counters (audit round 11, AP).
  static final List<_UnclaimedBeneath> _unclaimedBeneath = [];
  static int _beneathSeq = 0;
  static const int _maxUnclaimedBeneath = 1024;

  /// The inner layer ([MockkHttpOverrides]) is opening a request that carries no claim.
  static void noteUnclaimedBeneath(String method, Uri url) {
    if (!enableDeduplication) return;
    // A sighting can only ever answer for a claim of the SAME identity that already existed when
    // it happened. Anything else is unclaimable, and keeping it would only push out ones that
    // matter and make the overflow counter mean nothing (audit round 12, AU: an app without dio
    // filled the list; round 13, AX: one claim that never ends — a cache that resolved without
    // propagating — kept the list open to every request in the process). Matching on identity
    // here bounds the list by the traffic to the claimed URLs alone, whatever else is in flight.
    final m = method.toUpperCase();
    final u = url.toString();
    if (!_liveByIdentity.containsKey('$m $u')) return;
    _unclaimedBeneath.add(_UnclaimedBeneath(++_beneathSeq, m, u));
    while (_unclaimedBeneath.length > _maxUnclaimedBeneath) {
      _unclaimedBeneath.removeAt(0);
      _beneathWitnessEvicted++;
    }
  }

  /// Takes the first unclaimed pass beneath that matches [claim] and happened after it was made;
  /// each sighting answers for one claim only.
  static bool _takeUnclaimedBeneath(InnerPassClaim claim) {
    for (var i = 0; i < _unclaimedBeneath.length; i++) {
      final seen = _unclaimedBeneath[i];
      if (seen.seq > claim.beneathSeqWhenMade &&
          seen.method == claim.method &&
          seen.url == claim.url) {
        _unclaimedBeneath.removeAt(i);
        return true;
      }
    }
    return false;
  }

  /// Test seam over [_generateId], which is private and has no other observable output.
  static String debugGenerateId() => _generateId();

  /// The OUTER layer (a `dio` interceptor) is about to capture a request itself and lets it
  /// continue down to dart:io: the pass the inner layer is about to make over it must not
  /// capture it a second time. Returns the claim the adapter wrapper carries down under
  /// [zoneClaimKey], or null when coordination is off. [method] and [url] are the request's
  /// identity as the layer beneath will see it, should it go past there without the claim.
  InnerPassClaim? claimInnerPass(String method, Uri url) {
    if (!enableDeduplication) return null;
    while (_innerPassClaims.length >= _maxClaims) {
      final evicted = _innerPassClaims.remove(_innerPassClaims.keys.first);
      if (evicted != null) _forgetIdentity(evicted);
      _claimsEvicted++;
    }
    final claim = InnerPassClaim._(
      _randomHex(16),
      DateTime.now().millisecondsSinceEpoch,
      method.toUpperCase(),
      url.toString(),
      _beneathSeq,
    );
    _innerPassClaims[claim.nonce] = claim;
    _liveByIdentity.update(claim.identity, (n) => n + 1, ifAbsent: () => 1);
    _claimsMade++;
    return claim;
  }

  /// The INNER layer ([MockkHttpOverrides]) found [nonce] on a request: is it a claim a layer
  /// above made? `true` consumes it, so it can never be charged to a second request.
  bool consumeInnerPass(String nonce) {
    if (!enableDeduplication) return false;
    final claim = _innerPassClaims.remove(nonce);
    if (claim == null) return false;
    _forgetIdentity(claim);
    claim._consumed = true;
    _passesYielded++;
    _forgetSightingsIfIdle();
    return true;
  }

  /// With no claim alive, no sighting can be claimed any more: drop them, so the list holds
  /// only what can still serve and its overflow counter keeps its meaning.
  static void _forgetSightingsIfIdle() {
    if (_innerPassClaims.isEmpty) _unclaimedBeneath.clear();
  }

  /// Live claims by identity (`METHOD url`), so the inner layer's question "could any live claim
  /// take this?" is one map lookup per request, however many claims are alive — a stuck claim
  /// per cache hit (README, "One thing dio itself hides") must not make every dart:io request
  /// scan them all (adversarial review of audit round 13).
  static final Map<String, int> _liveByIdentity = {};

  static void _forgetIdentity(InnerPassClaim claim) {
    final left = (_liveByIdentity[claim.identity] ?? 1) - 1;
    if (left <= 0) {
      _liveByIdentity.remove(claim.identity);
    } else {
      _liveByIdentity[claim.identity] = left;
    }
  }

  /// The outer layer's request is over. A claim the inner layer never took is withdrawn, and
  /// how it ended is returned — and counted — by two witnesses, never by a guess: the caller
  /// says whether the adapter of THIS Dio was replaced while the claim was alive (the only way
  /// a request of its Dio can be fetched without the wrapper), and the layer beneath says
  /// whether it saw this claim's identity go past unclaimed after the claim was made. Both, and
  /// the request was reported twice; either missing, and nothing beneath saw this request. A
  /// claim the inner layer DID take has already been removed by [consumeInnerPass].
  ClaimEnd releaseInnerPass(InnerPassClaim? claim,
      {bool adapterReplacedMeanwhile = false}) {
    if (claim == null) return ClaimEnd.none;
    if (claim._consumed) return ClaimEnd.taken;
    if (_innerPassClaims.remove(claim.nonce) == null) return ClaimEnd.none;
    _forgetIdentity(claim);
    _claimsWithdrawn++;
    try {
      return _classifyWithdrawn(claim, adapterReplacedMeanwhile);
    } finally {
      _forgetSightingsIfIdle();
    }
  }

  ClaimEnd _classifyWithdrawn(
      InnerPassClaim claim, bool adapterReplacedMeanwhile) {
    // Went down through the wrapper and nothing beneath took it: a native adapter.
    if (claim.carried) {
      _claimsUntakenBeneath++;
      return ClaimEnd.untakenBeneath;
    }
    // Never went down through the wrapper. A raw fetch needs the adapter of this Dio to have
    // been replaced while the claim was alive — without that, any fetch went through the
    // wrapper and would have been carried, so nothing beneath ever saw this request: answered
    // or cancelled above the adapter (audit round 11, AP).
    if (!adapterReplacedMeanwhile) {
      _claimsNotFetched++;
      return ClaimEnd.notFetched;
    }
    // Replaced while alive: a raw fetch was possible. It leaves a trace beneath — the dart:io
    // layer saw this identity go past unclaimed after the claim was made — and with that trace
    // the request was reported twice. Without it, this layer does not know: the request may
    // have been answered above the adapter, or fetched under a URL the adapter rewrote and
    // reported twice all the same (audit round 12, AT). Silence must not pose as certainty, so
    // that is a state of its own.
    if (_takeUnclaimedBeneath(claim)) {
      _claimsNotCarried++;
      return ClaimEnd.notCarried;
    }
    _claimsUnresolved++;
    return ClaimEnd.unresolved;
  }

  /// Number of unconsumed claims. Visible for testing.
  static int debugPendingClaims() => _innerPassClaims.length;

  /// Build a [RequestData] from method, URI, and headers.
  RequestData buildRequestData(
    String method,
    Uri uri,
    Map<String, String> headers, {
    String body = '',
  }) {
    return RequestData(
      method: method,
      url: uri.toString(),
      headers: headers,
      body: body,
    );
  }

  /// Build a [FlowData] from request and response info.
  /// [source] names the layer that captured: 'dio' or 'io'. Counted for [clientReport].
  FlowData buildFlowData({
    String source = 'io',
    required RequestData request,
    required int statusCode,
    required Map<String, String> responseHeaders,
    required String responseBody,
    required int durationMs,
  }) {
    if (source == 'dio') {
      _flowsSentByDio++;
    } else {
      _flowsSentByIo++;
    }
    return FlowData(
      flowId: _generateId(),
      request: request,
      response: ResponseData(
        statusCode: statusCode,
        headers: responseHeaders,
        body: responseBody,
      ),
      timestamp: DateTime.now().millisecondsSinceEpoch,
      duration: durationMs,
      projectId: projectId,
      packageName: packageName,
    );
  }

  /// A unique id for one captured flow.
  ///
  /// Both "random" blocks used to come from the clock — `microsecondsSinceEpoch` truncated to its
  /// four MOST significant hex digits, which barely move over hours — so they were effectively
  /// constant AND identical to each other. Two requests started in the same millisecond therefore
  /// shared a flowId, which is routine: this fires whenever an app refreshes two endpoints at once.
  /// A duplicated flowId breaks `flows action:"get"` and `from_flow_id`, the documented path for
  /// turning a captured call into a mock rule.
  static String _generateId() {
    final now = DateTime.now().millisecondsSinceEpoch;
    return '${now.toRadixString(16)}-${_randomHex(4)}-${_randomHex(4)}';
  }

  /// [length] hex characters of real entropy.
  static String _randomHex(int length) {
    final buffer = StringBuffer();
    for (var i = 0; i < length; i++) {
      buffer.write(_random.nextInt(16).toRadixString(16));
    }
    return buffer.toString();
  }

  /// Not `Random.secure()`: flow ids are collision-avoidance, not a security boundary, and the
  /// secure generator draws from the OS entropy pool on every capture.
  static final math.Random _random = math.Random();
}

/// How a claim ended, as [MockkHttpCore.releaseInnerPass] reports it.
enum ClaimEnd {
  /// There was no claim to release (or it had already been evicted).
  none,

  /// The layer beneath took it: a pass was yielded, nothing to withdraw.
  taken,

  /// Went down through the adapter wrapper and nothing beneath took it (a native adapter).
  untakenBeneath,

  /// Never went down through the wrapper, and the layer beneath saw the request go past without
  /// its claim: it was fetched through an adapter the app swapped in while it was in flight, and
  /// reported twice.
  notCarried,

  /// Never went down through the wrapper and the adapter of its Dio was not replaced while it
  /// was alive, so nothing beneath can have seen it: answered or cancelled above the adapter.
  notFetched,

  /// Never went down through the wrapper, the adapter of its Dio WAS replaced while it was
  /// alive, and nothing beneath matched it: answered above the adapter, sent through an adapter
  /// that bypasses dart:io, or fetched under a URL the adapter rewrote and reported twice — this
  /// layer cannot tell which, and says so rather than claim nobody saw it.
  unresolved,
}

/// One request the inner layer saw go past without a claim: its identity and its place in order.
class _UnclaimedBeneath {
  const _UnclaimedBeneath(this.seq, this.method, this.url);

  final int seq;
  final String method;
  final String url;
}

/// One outer-layer claim on one request: the nonce it travels under, when it was made, and
/// whether the inner layer has taken it.
class InnerPassClaim {
  InnerPassClaim._(
      this.nonce, this.madeAt, this.method, this.url, this.beneathSeqWhenMade);

  final String nonce;
  final int madeAt;

  /// The request's identity as the layer beneath sees it: upper-case method and the URL as written.
  final String method;
  final String url;

  String get identity => '$method $url';

  /// The inner layer's sighting sequence when this claim was made: only a sighting after it can
  /// be this request.
  final int beneathSeqWhenMade;

  /// How many times the interceptor that made this claim had found its Dio's adapter replaced
  /// when the claim was made; set by that interceptor, compared by it when the request ends.
  int adapterReplacementsWhenMade = 0;

  bool _consumed = false;

  /// Set by the adapter wrapper when the fetch went down through it carrying this claim.
  bool carried = false;

  /// Whether the inner layer has already stepped aside for this claim. Visible for testing.
  bool get consumed => _consumed;
}

/// Main entry point for MockkHttp initialization.
///
/// ```dart
/// void main() {
///   MockkHttp.init();  // That's it — package name auto-detected
///   runApp(MyApp());
/// }
/// ```
class MockkHttp {
  MockkHttp._();

  static const String version = '1.8.0';

  /// Initialize MockkHttp with global [HttpOverrides].
  ///
  /// This intercepts ALL HTTP traffic from `dart:io` HttpClient,
  /// including packages like `http` that use it internally.
  ///
  /// Call this before `runApp()`. Works on Android emulators, iOS Simulators
  /// (Apple Silicon or Intel), and physical iOS devices (with [host]).
  ///
  /// [port] - Plugin port (default: 9876)
  /// [packageName] - Override auto-detected package/bundle id (rarely needed)
  /// [host] - Override the plugin host. Not needed on emulators/simulators.
  ///   For a PHYSICAL iOS device pass your Mac's LAN IP, e.g.
  ///   `MockkHttp.init(host: '192.168.1.50')`, and add
  ///   `NSLocalNetworkUsageDescription` to the app's Info.plist (iOS 14+
  ///   shows a local-network permission prompt on first connection).
  static void init({
    int port = 9876,
    String? packageName,
    String? host,
    bool announce = true,
  }) {
    final resolvedPackage = packageName ?? autoDetectPackageName();

    final client = MockkHttpPluginClient(port: port, host: host);

    assert(() {
      print('┌──────────────────────────────────────────────');
      print('│ MockkHttp v$version (${_platformLabel()})');
      print('│ Package: ${resolvedPackage ?? "unknown"}');
      print('│ Host: ${client.host}:$port');
      if (Platform.isIOS &&
          !MockkHttpPluginClient.isIosSimulator &&
          host == null) {
        print('│ ⚠️ Physical iOS device without host: pass your');
        print('│    Mac\'s LAN IP: MockkHttp.init(host: "192.168.x.x")');
      }
      print('└──────────────────────────────────────────────');
      return true;
    }());

    client.setPackageName(resolvedPackage);
    final core = MockkHttpCore(client: client, packageName: resolvedPackage);
    MockkHttpOverrides.install(core);

    // Write marker file so the IntelliJ plugin can detect this app
    _writeMarkerFile(resolvedPackage);

    // Announce ourselves right away, then keep trying until the plugin answers.
    //
    // Until 1.7.0 the PING fired only from the request path, so an app that was running
    // but idle was invisible to the plugin's scan — and an app started BEFORE the plugin
    // stayed invisible until it happened to make a request. The retry also re-registers
    // the package after an IDE restart, which is the only thing that clears the plugin's
    // list of announced apps.
    if (announce) _startAnnouncing(client);
  }

  /// Cancels any announce loop from a previous [init] — mostly a convenience for tests.
  static void stopAnnouncing() {
    _announceTimer?.cancel();
    _announceTimer = null;
  }

  static Timer? _announceTimer;

  static void _startAnnouncing(MockkHttpPluginClient client) {
    stopAnnouncing();

    // Fire-and-forget: init() is called from main() and must never throw or block.
    unawaited(client.isPluginConnected());

    _announceTimer = Timer.periodic(const Duration(seconds: 20), (timer) async {
      if (client.hostConfirmed) {
        // The plugin knows about us; the request path keeps the registration alive.
        timer.cancel();
        _announceTimer = null;
        return;
      }
      await client.isPluginConnected();
    });
  }

  /// 'Android', 'iOS Simulator', 'iOS Device', or the OS name. Sent in every [MockkHttpCore.clientReport].
  static String platformLabel() => _platformLabel();

  static String _platformLabel() {
    if (Platform.isAndroid) return 'Android';
    if (Platform.isIOS) {
      return MockkHttpPluginClient.isIosSimulator
          ? 'iOS Simulator'
          : 'iOS Device';
    }
    return Platform.operatingSystem;
  }

  /// Auto-detect the app identifier for the current platform.
  /// - Android: /proc/self/cmdline contains the package name.
  /// - iOS: the process environment carries `__CFBundleIdentifier`.
  static String? autoDetectPackageName() {
    if (Platform.isAndroid) {
      try {
        final cmdline = File('/proc/self/cmdline').readAsStringSync();
        // cmdline is null-terminated, take first segment
        final packageName = cmdline.split('\x00').first.trim();
        if (packageName.isNotEmpty && packageName.contains('.')) {
          return packageName;
        }
      } catch (_) {}
      return null;
    }

    if (Platform.isIOS) {
      // Env vars first (present on some launch paths)...
      final bundleId = Platform.environment['__CFBundleIdentifier'];
      if (bundleId != null && bundleId.isNotEmpty) return bundleId;

      final xpcName = Platform.environment['XPC_SERVICE_NAME'];
      if (xpcName != null && xpcName.startsWith('UIKitApplication:')) {
        final raw = xpcName.substring('UIKitApplication:'.length);
        final end = raw.indexOf('[');
        final id = (end > 0 ? raw.substring(0, end) : raw).trim();
        if (id.isNotEmpty) return id;
      }

      // ...but on recent iOS runtimes Platform.environment is EMPTY, so the
      // reliable source is the app's own Info.plist (next to the executable).
      return readIosBundleIdentifier();
    }

    return null;
  }

  /// Name of the in-sandbox marker, read by the plugin via `run-as`.
  static const String markerFileName = 'mockk_http.marker';

  /// Write a marker file so the IntelliJ plugin can detect this app.
  ///
  /// - Android: TWO locations, because neither works everywhere.
  ///   * `{app temp dir}/mockk_http.marker` — inside the app's own sandbox
  ///     (Dart's [Directory.systemTemp] is the app cache dir on Android). The
  ///     plugin reads it with `adb shell run-as {pkg} cat cache/...`, which
  ///     works on production devices for any debuggable build. This is the
  ///     only one that works on a physical phone.
  ///   * `/data/local/tmp/mockk_http_{pkg}` — a legacy fallback that no longer
  ///     fires anywhere we have measured. On a real device the directory is
  ///     `drwxrwx--x shell shell`, so an ordinary app cannot write there; and on
  ///     an API 34 emulator the write was verified NOT to happen either (the
  ///     cache marker above is what made detection work there). Kept only for
  ///     old emulator images we have no data on: it costs one failed syscall,
  ///     fails silently by design, and detection never depended on it.
  /// - iOS Simulator: `{data container}/Documents/.mockk_http` — the plugin
  ///   finds it via `xcrun simctl get_app_container {udid} {bundleid} data`.
  static void _writeMarkerFile(String? packageName) {
    if (packageName == null) return;

    if (Platform.isAndroid) {
      // In-sandbox marker: the only one that works on real hardware.
      _writeAndroidSandboxMarker(packageName, 'flutter:$version:$packageName');
    }

    try {
      if (Platform.isAndroid) {
        final marker = File('/data/local/tmp/mockk_http_$packageName');
        marker.writeAsStringSync('flutter');
      } else if (Platform.isIOS) {
        final home = Platform.environment['HOME'];
        if (home == null || home.isEmpty) return;
        final marker = File('$home/Documents/.mockk_http');
        marker.writeAsStringSync('flutter:$packageName');
      }
    } catch (_) {
      // Non-critical — detection falls back to PING announcements
    }
  }

  /// Write [payload] inside the app's own data directory, where `run-as` can read it.
  ///
  /// The path is derived from `/proc/self/status` rather than taken from
  /// [Directory.systemTemp]: Android sets no `TMPDIR` for app processes (verified on a
  /// Pixel 7a — `TMPDIR` is empty), so `systemTemp` resolves to `/tmp`, which does not
  /// exist on Android. The app's uid encodes the Android user: uid 10312 → user 0 →
  /// `/data/user/0/<pkg>/`.
  static void _writeAndroidSandboxMarker(String packageName, String payload) {
    try {
      final status = File('/proc/self/status').readAsStringSync();
      final uid =
          int.parse(RegExp(r'Uid:\s+(\d+)').firstMatch(status)!.group(1)!);
      final userId = uid ~/ 100000; // 0 normally, 10+ on a work profile

      for (final base in [
        '/data/user/$userId/$packageName',
        '/data/data/$packageName',
      ]) {
        for (final dir in ['cache', 'files']) {
          try {
            File('$base/$dir/$markerFileName').writeAsStringSync(payload);
            return;
          } catch (_) {
            // Try the next location.
          }
        }
      }
    } catch (_) {
      // Non-critical — detection falls back to PING / on-device APK inspection.
    }
  }

  /// Disable MockkHttp globally.
  static void disable() {
    MockkHttpCore.isEnabled = false;
  }

  /// Enable MockkHttp globally.
  static void enable() {
    MockkHttpCore.isEnabled = true;
  }

  /// Enable/disable the coordination between the `dio` interceptor and the global
  /// [MockkHttpOverrides] that keeps one request from being captured by both. Off, every
  /// layer captures everything it sees.
  static set enableDeduplication(bool value) {
    MockkHttpCore.enableDeduplication = value;
  }
}
