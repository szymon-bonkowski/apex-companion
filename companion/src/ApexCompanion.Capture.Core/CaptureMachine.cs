namespace ApexCompanion.Capture;

public sealed record CaptureCheckpoint(string? LastEventId = null, string? LastFingerprint = null);
public sealed record CaptureUpdate(CaptureState State, MatchRecord? Candidate = null);

/// Only observed gameplay followed by repeated results can open an event. Restart never arms itself.
public sealed class CaptureMachine(string companionId, PlayerIdentity? identity, CaptureCheckpoint checkpoint, CaptureAcceptancePolicy? acceptancePolicy = null)
{
    private long lastSequence = -1, lastTime, firstGameplay;
    private string? eventId;
    private int resultFrames;
    private readonly Dictionary<string, int> stable = [];
    private readonly Dictionary<string, int> stableReadings = [];
    private readonly CaptureAcceptancePolicy policy = acceptancePolicy ?? CaptureAcceptancePolicy.Production;
    private MatchMode mode = MatchMode.UNKNOWN;
    private int modeReadings;
    private double modeConfidence = 1;
    private readonly Dictionary<string, double> confidence = [];
    private readonly HashSet<string> contradictory = [];
    private long resultTime;
    private bool armed, eventAccepted;
    private string parser = LayoutConfig.Prototype.Version;
    public CaptureState State { get; private set; } = CaptureState.DISABLED;
    public CaptureCheckpoint Checkpoint { get; private set; } = checkpoint;
    public void Enable() { Disable(); State = CaptureState.WAITING_FOR_GAME; }
    public void Disable() { State = CaptureState.DISABLED; armed = false; eventAccepted = false; firstGameplay = 0; eventId = null; resultFrames = 0; stable.Clear(); confidence.Clear(); contradictory.Clear(); stableReadings.Clear(); mode = MatchMode.UNKNOWN; modeReadings = 0; modeConfidence = 1; lastSequence = -1; lastTime = 0; }
    public CaptureUpdate Observe(ParsedFrame f)
    {
        if (State == CaptureState.DISABLED || f.Sequence <= lastSequence || f.TimestampSeconds < lastTime) return new(State);
        lastSequence = f.Sequence; lastTime = f.TimestampSeconds;
        if (f.Screen == ScreenKind.IN_MATCH)
        {
            if (eventId is not null) { eventAccepted = false; eventId = null; resultFrames = 0; stable.Clear(); confidence.Clear(); contradictory.Clear(); stableReadings.Clear(); mode = MatchMode.UNKNOWN; modeReadings = 0; modeConfidence = 1; firstGameplay = 0; }
            if (firstGameplay == 0) firstGameplay = f.TimestampSeconds;
            if (f.TimestampSeconds - firstGameplay >= 20) armed = true;
            State = CaptureState.IN_MATCH; return new(State);
        }
        if (f.Screen == ScreenKind.RESULTS)
        {
            firstGameplay = 0;
            if (eventAccepted && eventId is not null) return new(State);
            if (eventId is null)
            {
                if (!armed) { State = CaptureState.WAITING_FOR_GAME; return new(State); }
                armed = false; eventId = companionId + ":" + Guid.NewGuid().ToString("N"); resultTime = f.TimestampSeconds; parser = f.ParserVersion;
                State = CaptureState.RESULTS_DETECTED;
            }
            if (f.ParserVersion != parser) { State = CaptureState.REVIEW_REQUIRED; return new(State); }
            resultFrames++;
            if (f.Mode != MatchMode.UNKNOWN && !contradictory.Contains("mode"))
            {
                if (mode != MatchMode.UNKNOWN && mode != f.Mode) { contradictory.Add("mode"); mode = MatchMode.UNKNOWN; modeReadings = 0; }
                else { mode = f.Mode; modeReadings++; modeConfidence = Math.Min(modeConfidence, f.ModeConfidence); }
            }
            foreach (var (field, value) in f.Fields)
            {
                if (contradictory.Contains(field)) continue;
                if (stable.TryGetValue(field, out var previous) && previous != value) { stable.Remove(field); confidence.Remove(field); stableReadings.Remove(field); contradictory.Add(field); }
                else { stable[field] = value; stableReadings[field] = stableReadings.GetValueOrDefault(field) + 1; confidence[field] = Math.Min(confidence.GetValueOrDefault(field, 1), f.Confidence.GetValueOrDefault(field, 0)); }
            }
            State = resultFrames < 3 || f.TimestampSeconds - resultTime < 2 ? CaptureState.EXTRACTING : CaptureState.REVIEW_REQUIRED;
            if (resultFrames < 3 || f.TimestampSeconds - resultTime < 2) return new(State);
            var capturedConfidence = new Dictionary<string, double>(confidence);
            if (mode != MatchMode.UNKNOWN) capturedConfidence["mode"] = modeConfidence;
            var accepted = policy.CanAccept(new(parser, true, resultFrames, mode, modeReadings, modeConfidence, stableReadings, confidence, contradictory));
            var record = new MatchRecord(1, eventId!, identity, mode, null, null,
                Value("kills"), Value("assists"), Value("damage"), Value("placement"), Value("rpDelta"), resultTime,
                TimestampSource.RESULT_OBSERVED, MatchSource.WINDOWS_CAPTURE, accepted ? MatchQuality.AUTO_ACCEPTED : MatchQuality.REVIEW_REQUIRED,
                eventId, parser, capturedConfidence);
            State = accepted ? CaptureState.AUTO_ACCEPTED : CaptureState.REVIEW_REQUIRED;
            eventAccepted = accepted;
            record.Validate(f.TimestampSeconds);
            Checkpoint = new(eventId, Fingerprint(record));
            return new(State, record);
        }
        // Capture loss/alt-tab/loading/spectating do not create a boundary or rearm an event.
        if (f.Screen == ScreenKind.MENU) State = CaptureState.RETURNED_TO_MENU;
        else if (f.Screen == ScreenKind.UNAVAILABLE) { State = CaptureState.WAITING_FOR_GAME; firstGameplay = 0; }
        else if (f.Screen != ScreenKind.UNKNOWN) firstGameplay = 0;
        return new(State);
    }
    private int? Value(string field) => stable.TryGetValue(field, out var v) ? v : null;
    public static string Fingerprint(MatchRecord r) => Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
        $"{r.Identity?.Uid}:{r.Identity?.Platform}|{r.Kills}|{r.Assists}|{r.Damage}|{r.Placement}|{r.RpDelta}|{r.ParserVersion}")));
}
