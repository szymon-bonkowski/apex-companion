using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using ApexCompanion.Capture;
using ApexCompanion.Capture.Server;
using Xunit;

namespace ApexCompanion.Capture.Tests;

public sealed class CaptureTests
{
    private static readonly long Now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    private static MatchRecord Record(string id = "pc:event1", MatchQuality quality = MatchQuality.REVIEW_REQUIRED) => new(1, id, null, MatchMode.UNKNOWN, null, null, 3, 1, 1234, 4, null, Now - 100, TimestampSource.RESULT_OBSERVED, MatchSource.WINDOWS_CAPTURE, quality, id, "synthetic-v1", new() { ["kills"] = .95, ["damage"] = .98 });
    private static ParsedFrame Frame(long seq, long time, ScreenKind screen, params (string Field, int Value)[] fields) => new(seq, time, screen, "synthetic-v1", fields.ToDictionary(x => x.Field, x => x.Value), fields.ToDictionary(x => x.Field, _ => .98));
    private static CaptureMachine Armed()
    {
        var m = new CaptureMachine("pc", null, new()); m.Enable();
        m.Observe(Frame(1, Now - 80, ScreenKind.IN_MATCH)); m.Observe(Frame(2, Now - 59, ScreenKind.IN_MATCH)); return m;
    }
    [Fact] public void NullableStatsAreNotZero() { var r = Record() with { Kills = null, Placement = null, FieldConfidence = new() { ["damage"] = .98 } }; r.Validate(Now); var decoded = WireJson.Decode<MatchRecord>(WireJson.Encode(r)); Assert.Null(decoded.Kills); Assert.Null(decoded.Placement); Assert.Equal(r.Id, decoded.EvidenceEventId); }
    [Theory] [InlineData(-1, 0, 0, 1, 0)] [InlineData(101, 0, 0, 1, 0)] [InlineData(1, -1, 0, 1, 0)] [InlineData(1, 1, 100001, 1, 0)] [InlineData(1, 1, 0, 0, 0)] [InlineData(1, 1, 0, 101, 0)] [InlineData(1, 1, 0, 1, 10001)]
    public void InvalidFieldsRejected(int kills, int assists, int damage, int placement, int rp) => Assert.Throws<InvalidDataException>(() => (Record() with { Kills = kills, Assists = assists, Damage = damage, Placement = placement, RpDelta = rp }).Validate(Now));
    [Fact] public void ProvenanceAndConfidenceRejectLies()
    {
        Assert.Throws<InvalidDataException>(() => (Record() with { Source = MatchSource.MANUAL }).Validate(Now));
        Assert.Throws<InvalidDataException>(() => (Record() with { EvidenceEventId = null }).Validate(Now));
        Assert.Throws<InvalidDataException>(() => (Record() with { FieldConfidence = new() { ["kills"] = double.NaN } }).Validate(Now));
        Assert.Throws<InvalidDataException>(() => (Record() with { Identity = new("9", "Unknown") }).Validate(Now));
    }
    [Fact] public void ConfidenceRequiresPresentFieldAndAutoAcceptanceContext()
    {
        Assert.Throws<InvalidDataException>(() => (Record() with { Kills = null }).Validate(Now));
        Assert.Throws<InvalidDataException>(() => (Record() with { Quality = MatchQuality.AUTO_ACCEPTED }).Validate(Now));
        Assert.Throws<InvalidDataException>(() => (Record() with { Revision = 1000001 }).Validate(Now));
        (Record() with { Quality = MatchQuality.AUTO_ACCEPTED, Mode = MatchMode.RANKED }).Validate(Now);
    }
    [Fact] public void MissingUnknownAndDuplicateJsonKeysFailClosed()
    {
        Assert.ThrowsAny<Exception>(() => WireJson.Decode<MatchRecord>("{\"id\":\"x\"}"));
        Assert.ThrowsAny<Exception>(() => WireJson.Decode<MatchRecord>(WireJson.Encode(Record()).Replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknown\":1")));
        Assert.Throws<InvalidDataException>(() => WireJson.Decode<MatchRecord>(WireJson.Encode(Record()).Replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")));
    }
    [Theory] [InlineData("SESSION SUMMARY", "DAMAGE DEALT 100", ScreenKind.SESSION_SUMMARY)] [InlineData("SPECTATING", "KILLS 3", ScreenKind.SPECTATOR)] [InlineData("LOADING", "", ScreenKind.LOADING)] [InlineData("PLAY APEX LOBBY", "", ScreenKind.MENU)] [InlineData("MATCH SUMMARY", "DAMAGE DEALT 100", ScreenKind.RESULTS)] [InlineData("SQUADS LEFT 10", "", ScreenKind.IN_MATCH)] [InlineData("MATCH SUMMARY", "", ScreenKind.UNKNOWN)]
    public void ClassificationUsesOnlyVisibleLabels(string header, string stats, ScreenKind expected)
    { var r = new OcrReading(1, Now, 1920, 1080, [new(header, .99)], [new(stats, .99)]); Assert.Equal(expected, new EnglishResultsClassifier().Classify(r)); }
    [Fact] public void UnsupportedResolutionAndLowConfidenceNotResults()
    {
        var reading = new OcrReading(1, Now, 1920, 1080, [new("MATCH SUMMARY", .2)], [new("DAMAGE DEALT 100", .99)]);
        Assert.Equal(ScreenKind.UNKNOWN, new EnglishResultsClassifier().Classify(reading));
        Assert.Equal(ScreenKind.UNKNOWN, new EnglishResultsClassifier().Classify(reading with { Width = 200 }));
    }
    [Fact] public void ParserNeverCorrectsAmbiguousGlyphOrContradictoryNumber()
    {
        var reading = new OcrReading(1, Now, 1280, 720, [], [new("KILLS O", .99), new("ASSISTS 2", .99), new("ASSISTS 3", .99), new("DAMAGE DEALT 1234", .9), new("RP DELTA -45", .99), new("SQUAD PLACED 0", 1)]);
        var f = new EnglishFieldExtractor().Extract(reading, ScreenKind.RESULTS);
        Assert.False(f.Fields.ContainsKey("kills")); Assert.False(f.Fields.ContainsKey("assists")); Assert.False(f.Fields.ContainsKey("placement")); Assert.Equal(1234, f.Fields["damage"]); Assert.Equal(-45, f.Fields["rpDelta"]);
    }
    [Fact] public void StartupResultsLoadingAltTabSpectatingNeverCreateMatch()
    {
        var m = new CaptureMachine("pc", null, new("pc:old", "old")); m.Enable();
        foreach (var kind in new[] { ScreenKind.RESULTS, ScreenKind.LOADING, ScreenKind.SPECTATOR, ScreenKind.RESULTS, ScreenKind.UNAVAILABLE, ScreenKind.RESULTS, ScreenKind.SESSION_SUMMARY })
            Assert.Null(m.Observe(Frame((long)kind + 20, Now, kind, ("kills", 3))).Candidate);
    }
    [Fact] public void ThreeTimedResultsAfterGameplayProduceOneStableEvent()
    {
        var m = Armed(); Assert.Null(m.Observe(Frame(3, Now - 50, ScreenKind.RESULTS, ("kills", 3))).Candidate);
        Assert.Null(m.Observe(Frame(4, Now - 49, ScreenKind.RESULTS, ("kills", 3))).Candidate);
        var first = m.Observe(Frame(5, Now - 48, ScreenKind.RESULTS, ("kills", 3))).Candidate!;
        var repeat = m.Observe(Frame(6, Now - 47, ScreenKind.RESULTS, ("kills", 3))).Candidate!;
        Assert.Equal(first.Id, repeat.Id); Assert.Equal(MatchQuality.REVIEW_REQUIRED, first.Quality); Assert.False(LayoutConfig.AutomaticAcceptanceEnabled);
    }
    [Fact] public void OutOfOrderFramesCannotMutateCandidate()
    {
        var m = Armed(); m.Observe(Frame(3, Now - 50, ScreenKind.RESULTS, ("kills", 3))); m.Observe(Frame(4, Now - 49, ScreenKind.RESULTS, ("kills", 3)));
        Assert.Null(m.Observe(Frame(3, Now - 48, ScreenKind.RESULTS, ("kills", 9))).Candidate);
        var r = m.Observe(Frame(5, Now - 48, ScreenKind.RESULTS, ("kills", 3))).Candidate!; Assert.Equal(3, r.Kills);
    }
    [Fact] public void TemporalDisagreementClearsOnlyThatField()
    {
        var m = Armed(); m.Observe(Frame(3, Now - 50, ScreenKind.RESULTS, ("kills", 3), ("damage", 100)));
        m.Observe(Frame(4, Now - 49, ScreenKind.RESULTS, ("kills", 8), ("damage", 100)));
        var r = m.Observe(Frame(5, Now - 48, ScreenKind.RESULTS, ("kills", 3), ("damage", 100))).Candidate!;
        Assert.Null(r.Kills); Assert.Equal(100, r.Damage); Assert.False(r.FieldConfidence.ContainsKey("kills"));
    }
    [Fact] public void SuccessiveGamesEvenIdenticalStatisticsGetDistinctEvents()
    {
        var m = Armed(); m.Observe(Frame(3, Now - 50, ScreenKind.RESULTS)); m.Observe(Frame(4, Now - 49, ScreenKind.RESULTS)); var r1 = m.Observe(Frame(5, Now - 48, ScreenKind.RESULTS, ("kills", 3))).Candidate!;
        m.Observe(Frame(6, Now - 47, ScreenKind.MENU)); m.Observe(Frame(7, Now - 46, ScreenKind.IN_MATCH)); m.Observe(Frame(8, Now - 25, ScreenKind.IN_MATCH));
        m.Observe(Frame(9, Now - 20, ScreenKind.RESULTS)); m.Observe(Frame(10, Now - 19, ScreenKind.RESULTS)); var r2 = m.Observe(Frame(11, Now - 18, ScreenKind.RESULTS, ("kills", 3))).Candidate!;
        Assert.NotEqual(r1.Id, r2.Id); Assert.Equal(r1.Kills, r2.Kills);
    }
    private static CaptureAcceptancePolicy SyntheticPolicy() => new(new Dictionary<string, ValidatedLayoutRule>
    { ["synthetic-v1"] = new(3, .97, new HashSet<string> { "kills", "damage", "placement" }) });
    private static ParsedFrame EligibleFrame(long seq, long time) => Frame(seq, time, ScreenKind.RESULTS, ("kills", 3), ("damage", 1234), ("placement", 4)) with { Mode = MatchMode.RANKED, ModeConfidence = .99 };
    [Fact] public void ApprovedSyntheticPolicyQueuesTwoGamesWithoutInteractionAndNeverDuplicatesAfterAltTab()
    {
        using var dir = new TemporaryDirectory(); var journal = new CaptureJournal(Path.Combine(dir.Path, "matches.json"));
        var m = new CaptureMachine(journal.State.CompanionId, null, new(), SyntheticPolicy()); m.Enable();
        long seq = 0;
        for (var game = 0; game < 2; game++)
        {
            var t = Now - 200 + game * 70;
            m.Observe(Frame(++seq, t, ScreenKind.IN_MATCH)); m.Observe(Frame(++seq, t + 21, ScreenKind.IN_MATCH));
            Assert.Null(m.Observe(EligibleFrame(++seq, t + 30)).Candidate); Assert.Null(m.Observe(EligibleFrame(++seq, t + 31)).Candidate);
            var update = m.Observe(EligibleFrame(++seq, t + 32)); Assert.Equal(CaptureState.AUTO_ACCEPTED, update.State);
            journal.AcceptAutomatic(update.Candidate!, m.Checkpoint); journal.AcceptAutomatic(update.Candidate!, m.Checkpoint);
            m.Observe(Frame(++seq, t + 33, ScreenKind.UNAVAILABLE)); m.Observe(Frame(++seq, t + 34, ScreenKind.MENU));
            Assert.Null(m.Observe(EligibleFrame(++seq, t + 35)).Candidate);
        }
        Assert.Equal(2, journal.State.Accepted.Count); Assert.Empty(journal.State.Candidates);
        Assert.All(journal.Batch(0).Records, r => Assert.Equal(MatchQuality.AUTO_ACCEPTED, r.Quality));
        Assert.Equal(2, new CaptureJournal(Path.Combine(dir.Path, "matches.json")).State.Accepted.Count);
    }
    [Fact] public void ProductionPolicyDoesNotAutoAcceptEvenPerfectSyntheticEvidence()
    {
        var m = Armed(); m.Observe(EligibleFrame(3, Now - 50)); m.Observe(EligibleFrame(4, Now - 49));
        var r = m.Observe(EligibleFrame(5, Now - 48)).Candidate!; Assert.Equal(MatchQuality.REVIEW_REQUIRED, r.Quality);
    }
    [Fact] public void ContradictionRejectsApprovedPolicyAndClearsAmbiguousField()
    {
        var m = new CaptureMachine("pc", null, new(), SyntheticPolicy()); m.Enable(); m.Observe(Frame(1, Now - 80, ScreenKind.IN_MATCH)); m.Observe(Frame(2, Now - 59, ScreenKind.IN_MATCH));
        m.Observe(EligibleFrame(3, Now - 50)); m.Observe(EligibleFrame(4, Now - 49) with { Fields = new Dictionary<string, int> { ["kills"] = 9, ["damage"] = 1234, ["placement"] = 4 } });
        var r = m.Observe(EligibleFrame(5, Now - 48)).Candidate!; Assert.Equal(MatchQuality.REVIEW_REQUIRED, r.Quality); Assert.Null(r.Kills);
    }
    [Fact] public void UnapprovedLayoutAndMissingModeCannotAutoAccept()
    {
        var m = new CaptureMachine("pc", null, new(), SyntheticPolicy()); m.Enable(); m.Observe(Frame(1, Now - 80, ScreenKind.IN_MATCH)); m.Observe(Frame(2, Now - 59, ScreenKind.IN_MATCH));
        m.Observe(EligibleFrame(3, Now - 50) with { ParserVersion = "changed-layout" }); m.Observe(EligibleFrame(4, Now - 49) with { ParserVersion = "changed-layout" });
        Assert.Equal(MatchQuality.REVIEW_REQUIRED, m.Observe(EligibleFrame(5, Now - 48) with { ParserVersion = "changed-layout" }).Candidate!.Quality);
        var evidence = new AcceptanceEvidence("synthetic-v1", true, 9, MatchMode.UNKNOWN, 0, 0, new Dictionary<string, int>(), new Dictionary<string, double>(), new HashSet<string>());
        Assert.False(SyntheticPolicy().CanAccept(evidence));
    }
    [Fact] public void WeakOrSingleReadingOptionalFieldPreventsAutomaticAcceptance()
    {
        var evidence = new AcceptanceEvidence("synthetic-v1", true, 3, MatchMode.RANKED, 3, .99,
            new Dictionary<string, int> { ["kills"] = 3, ["damage"] = 3, ["placement"] = 3, ["assists"] = 1 },
            new Dictionary<string, double> { ["kills"] = .99, ["damage"] = .99, ["placement"] = .99, ["assists"] = .99 }, new HashSet<string>());
        Assert.False(SyntheticPolicy().CanAccept(evidence));
        Assert.False(SyntheticPolicy().CanAccept(evidence with { ModeConfidence = double.NaN }));
        Assert.Throws<ArgumentException>(() => new CaptureAcceptancePolicy(new Dictionary<string, ValidatedLayoutRule>
            { ["bad-rule"] = new(3, double.NaN, new HashSet<string> { "kills" }) }));
    }
    [Fact] public void DisabledPipelineNeverCreatesCandidates()
    { var m = Armed(); m.Disable(); Assert.Null(m.Observe(Frame(3, Now, ScreenKind.RESULTS)).Candidate); Assert.Equal(CaptureState.DISABLED, m.State); }
    [Fact] public void LocalQueueSurvivesRestartRepeatedFramesAndAck()
    {
        using var dir = new TemporaryDirectory(); var path = Path.Combine(dir.Path, "matches.json"); var journal = new CaptureJournal(path);
        journal.UpsertCandidate(Record(), new("pc:event1")); journal.UpsertCandidate(Record(), new("pc:event1")); Assert.Single(journal.State.Candidates);
        journal.Confirm("pc:event1"); journal.Confirm("pc:event1"); Assert.Single(journal.State.Accepted);
        var restart = new CaptureJournal(path); Assert.Single(restart.State.Accepted); Assert.Empty(restart.State.Candidates); Assert.Equal(1, restart.Batch(0).Cursor);
        restart.Acknowledge(1); restart.Acknowledge(1); Assert.Single(restart.State.Accepted); Assert.Empty(restart.Batch(1).Records);
        journal.UpsertCandidate(Record() with { Kills = 9 }, new()); Assert.Single(journal.State.Accepted); // stale OCR can't overwrite confirmed.
    }
    [Fact] public void CorruptFutureJournalRetainsOriginalBytes()
    {
        using var dir = new TemporaryDirectory(); var path = Path.Combine(dir.Path, "matches.json"); var journal = new CaptureJournal(path);
        var future = File.ReadAllText(path).Replace("\"schemaVersion\":1", "\"schemaVersion\":99"); File.WriteAllText(path, future);
        Assert.Throws<InvalidDataException>(() => new CaptureJournal(path)); Assert.Equal(future, File.ReadAllText(path));
    }
    [Fact] public void InterruptedStagingDoesNotReplaceCommittedState()
    {
        using var dir = new TemporaryDirectory(); var path = Path.Combine(dir.Path, "matches.json"); var journal = new CaptureJournal(path); journal.UpsertCandidate(Record(), new());
        File.WriteAllText(path + ".new", "INTERRUPTED"); var restart = new CaptureJournal(path); Assert.Single(restart.State.Candidates); restart.Confirm("pc:event1"); Assert.False(File.Exists(path + ".new"));
    }
    [Fact] public void FailedAtomicStageLeavesMemoryAndDiskAtPriorCommit()
    {
        using var dir = new TemporaryDirectory(); var path = Path.Combine(dir.Path, "matches.json"); var journal = new CaptureJournal(path); var original = File.ReadAllText(path);
        Directory.CreateDirectory(path + ".new");
        Assert.ThrowsAny<Exception>(() => journal.UpsertCandidate(Record(), new()));
        Assert.Empty(journal.State.Candidates); Assert.Equal(original, File.ReadAllText(path));
    }
    [Fact] public void OversizedJournalFailsBeforeParsingAndRetainsFile()
    {
        using var dir = new TemporaryDirectory(); var path = Path.Combine(dir.Path, "matches.json");
        using (var file = File.Create(path)) file.SetLength(CaptureJournal.MaxBytes + 1L);
        Assert.Throws<InvalidDataException>(() => new CaptureJournal(path)); Assert.Equal(CaptureJournal.MaxBytes + 1L, new FileInfo(path).Length);
    }
    [Fact] public void IdentityMismatchAndUnknownCursorReject()
    {
        using var dir = new TemporaryDirectory(); var journal = new CaptureJournal(Path.Combine(dir.Path, "matches.json"), new("9", "PC"));
        Assert.Throws<InvalidDataException>(() => journal.UpsertCandidate(Record(), new())); Assert.Throws<InvalidDataException>(() => journal.Batch(1)); Assert.Throws<InvalidDataException>(() => journal.Acknowledge(-1));
    }
    [Fact] public void TsvKeepsPerLineMinimumConfidenceAndIgnoresInvalidWords()
    {
        var tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n5\t1\t1\t1\t1\t1\t1\t1\t1\t1\t99\tKILLS\n5\t1\t1\t1\t1\t2\t1\t1\t1\t1\t92\t3\n";
        var lines = TesseractOcr.ParseTsv(tsv); Assert.Single(lines); Assert.Equal("KILLS 3", lines[0].Text); Assert.Equal(.92, lines[0].Confidence);
    }
    [Fact] public async Task HttpsRejectsWrongTokenAndPinAndSupportsReplayAckRevoke()
    {
        using var dir = new TemporaryDirectory(); var journal = new CaptureJournal(Path.Combine(dir.Path, "matches.json")); journal.AddSyntheticAccepted(Record(quality: MatchQuality.USER_CONFIRMED));
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
        var secrets = new PairingSecrets(Path.Combine(dir.Path, "secret"), journal.State.CompanionId, IPAddress.Loopback);
        var pairing = secrets.Pair(new Uri($"https://127.0.0.1:{port}"), null);
        await using var server = new LocalSyncServer(journal, secrets, IPAddress.Loopback, port); await server.StartAsync();
        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
        handler.ServerCertificateCustomValidationCallback = (_, certificate, _, errors) => certificate is not null &&
            (errors & SslPolicyErrors.RemoteCertificateNameMismatch) == 0 && certificate.NotAfter.ToUniversalTime() > DateTime.UtcNow &&
            Convert.ToHexStringLower(SHA256.HashData(certificate.RawData)) == pairing.CertificatePin;
        using var client = new HttpClient(handler) { BaseAddress = new Uri(pairing.Endpoint) };
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/v1/matches?after=0")).StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", new string('0', 64)); Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/v1/matches?after=0")).StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", pairing.Token);
        var first = await client.GetStringAsync("/v1/matches?after=0"); var retry = await client.GetStringAsync("/v1/matches?after=0"); Assert.Equal(first, retry); Assert.Contains("pc:event1", first);
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsync("/v1/ack", new StringContent("{\"cursor\":1}", Encoding.UTF8, "application/json"))).StatusCode);
        Assert.Contains("\"records\":[]", await client.GetStringAsync("/v1/matches?after=1"));
        using var wrongHandler = new HttpClientHandler { ServerCertificateCustomValidationCallback = (_, _, _, _) => false };
        using var wrongPinClient = new HttpClient(wrongHandler); await Assert.ThrowsAsync<HttpRequestException>(() => wrongPinClient.GetAsync(pairing.Endpoint + "/v1/matches?after=0"));
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsync("/v1/unpair", null)).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/v1/matches?after=0")).StatusCode);
        var restored = new PairingSecrets(Path.Combine(dir.Path, "secret"), journal.State.CompanionId, IPAddress.Loopback); Assert.False(restored.Authenticate("Bearer " + pairing.Token));
    }
    [Fact] public void PairingSurvivesRestartUntilExplicitRotationOrRevocation()
    {
        using var directory = new TemporaryDirectory();
        var path = Path.Combine(directory.Path, "secret"); var id = "synthetic-companion";
        var endpoint = new Uri("https://127.0.0.1:43821");
        var first = new PairingSecrets(path, id, IPAddress.Loopback);
        var paired = first.GetOrCreatePairing(endpoint, null);
        var restarted = new PairingSecrets(path, id, IPAddress.Loopback);
        Assert.True(restarted.HasPairing);
        var resumed = restarted.GetOrCreatePairing(endpoint, null);
        Assert.Equal(paired.Token, resumed.Token); Assert.Equal(paired.CertificatePin, resumed.CertificatePin);
        Assert.True(restarted.Authenticate("Bearer " + paired.Token));
        var rotated = restarted.Pair(endpoint, null);
        Assert.NotEqual(paired.Token, rotated.Token); Assert.False(restarted.Authenticate("Bearer " + paired.Token));
        restarted.Revoke(); Assert.False(restarted.HasPairing);
    }
    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "apex-capture-test-" + Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, true);
    }
}
