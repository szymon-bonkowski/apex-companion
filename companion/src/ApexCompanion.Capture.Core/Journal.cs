using System.Text;

namespace ApexCompanion.Capture;

public sealed record QueuedMatch(long Cursor, MatchRecord Record);
public sealed record JournalState(int SchemaVersion, string CompanionId, PlayerIdentity? Identity, long NextCursor,
    List<QueuedMatch> Accepted, List<MatchRecord> Candidates, CaptureCheckpoint Checkpoint, long AcknowledgedCursor = 0);

/// Durable bounded queue. Never trim unsynchronized records; capacity failure leaves prior bytes intact.
public sealed class CaptureJournal
{
    public const int MaxBytes = 16_777_216;
    private readonly string path;
    private readonly object gate = new();
    public JournalState State { get; private set; }
    public CaptureJournal(string path, PlayerIdentity? identity = null)
    {
        this.path = Path.GetFullPath(path); Directory.CreateDirectory(Path.GetDirectoryName(this.path)!);
        if (File.Exists(path))
        {
            if (new FileInfo(path).Length > MaxBytes) throw new InvalidDataException("Journal capacity exceeded. Original retained.");
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (input.Length > MaxBytes) throw new InvalidDataException("Journal capacity exceeded.");
            var bytes = new byte[checked((int)input.Length)]; input.ReadExactly(bytes);
            if (input.ReadByte() != -1) throw new InvalidDataException("Journal changed during read.");
            State = WireJson.Decode<JournalState>(new UTF8Encoding(false, true).GetString(bytes)); Validate(State);
            if (identity is not null && State.Identity != identity) throw new InvalidDataException("Journal belongs to another player.");
        }
        else { State = new(1, Guid.NewGuid().ToString("N"), identity, 1, [], [], new()); Save(State); }
    }
    public void UpsertCandidate(MatchRecord record, CaptureCheckpoint checkpoint)
    {
        lock (gate)
        {
            record.Validate(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            if (record.Identity != State.Identity || record.Quality != MatchQuality.REVIEW_REQUIRED) throw new InvalidDataException("Candidate context invalid.");
            var accepted = State.Accepted.SingleOrDefault(x => x.Record.Id == record.Id);
            if (accepted is not null) return; // A later OCR attempt never changes confirmed data.
            var prior = State.Candidates.SingleOrDefault(x => x.Id == record.Id);
            if (prior is not null && (prior.EvidenceEventId != record.EvidenceEventId || prior.TimestampSeconds != record.TimestampSeconds)) throw new InvalidDataException("Conflicting event identity.");
            Save(State with { Candidates = State.Candidates.Where(x => x.Id != record.Id).Append(record).ToList(), Checkpoint = checkpoint });
        }
    }
    public void AcceptAutomatic(MatchRecord record, CaptureCheckpoint checkpoint)
    {
        lock (gate)
        {
            record.Validate(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            if (record.Quality != MatchQuality.AUTO_ACCEPTED || record.Source != MatchSource.WINDOWS_CAPTURE) throw new InvalidDataException("Not an automatically accepted capture.");
            var existing = State.Accepted.SingleOrDefault(x => x.Record.Id == record.Id);
            if (existing is not null)
            {
                if (WireJson.Encode(existing.Record) != WireJson.Encode(record)) throw new InvalidDataException("Conflicting accepted event.");
                return;
            }
            Save(State with { NextCursor = checked(State.NextCursor + 1), Accepted = [.. State.Accepted, new(State.NextCursor, record)],
                Candidates = State.Candidates.Where(x => x.Id != record.Id).ToList(), Checkpoint = checkpoint });
        }
    }
    public MatchRecord Confirm(string id)
    {
        lock (gate)
        {
            var previous = State.Accepted.SingleOrDefault(x => x.Record.Id == id);
            if (previous is not null) return previous.Record;
            var candidate = State.Candidates.Single(x => x.Id == id);
            if (candidate.Kills is null && candidate.Assists is null && candidate.Damage is null && candidate.Placement is null && candidate.RpDelta is null)
                throw new InvalidDataException("No supported statistic was observed. Use mobile manual entry instead.");
            var confirmed = candidate with { Quality = MatchQuality.USER_CONFIRMED };
            Save(State with { NextCursor = checked(State.NextCursor + 1), Candidates = State.Candidates.Where(x => x.Id != id).ToList(), Accepted = [.. State.Accepted, new(State.NextCursor, confirmed)] });
            return confirmed;
        }
    }
    public void AddSyntheticAccepted(MatchRecord record)
    {
        lock (gate)
        {
            record.Validate(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            if (State.Accepted.Any(x => x.Record.Id == record.Id)) return;
            Save(State with { NextCursor = checked(State.NextCursor + 1), Accepted = [.. State.Accepted, new(State.NextCursor, record)] });
        }
    }
    public void Acknowledge(long cursor)
    {
        lock (gate) { if (cursor < 0 || cursor >= State.NextCursor) throw new InvalidDataException("Invalid acknowledgement cursor."); if (cursor > State.AcknowledgedCursor) Save(State with { AcknowledgedCursor = cursor }); }
    }
    public (long Cursor, List<MatchRecord> Records) Batch(long after)
    {
        lock (gate)
        {
            if (after < 0 || after >= State.NextCursor) throw new InvalidDataException("Invalid cursor; re-pair instead of resetting journal.");
            var matches = State.Accepted.Where(x => x.Cursor > after).OrderBy(x => x.Cursor).Take(100).ToList();
            return (matches.Count == 0 ? after : matches[^1].Cursor, matches.Select(x => x.Record).ToList());
        }
    }
    private static void Validate(JournalState s)
    {
        if (s.SchemaVersion != 1 || !MatchRecord.ValidId(s.CompanionId) || s.NextCursor < 1 || s.Accepted.Count > 10000 || s.Candidates.Count > 1000 || s.AcknowledgedCursor < 0 || s.AcknowledgedCursor >= s.NextCursor)
            throw new InvalidDataException("Unsupported/corrupt/full journal. Original retained.");
        var all = s.Accepted.Select(x => x.Record).Concat(s.Candidates).ToList();
        if (all.Select(x => x.Id).Distinct().Count() != all.Count || all.Where(x => x.EvidenceEventId is not null).Select(x => x.EvidenceEventId).Distinct().Count() != all.Count(x => x.EvidenceEventId is not null) ||
            s.Accepted.Select(x => x.Cursor).Distinct().Count() != s.Accepted.Count || s.Accepted.Any(x => x.Cursor < 1 || x.Cursor >= s.NextCursor || x.Record.Quality == MatchQuality.REVIEW_REQUIRED) || s.Candidates.Any(x => x.Quality != MatchQuality.REVIEW_REQUIRED))
            throw new InvalidDataException("Conflicting journal events/cursors.");
        foreach (var record in all) { record.Validate(DateTimeOffset.UtcNow.ToUnixTimeSeconds()); if (record.Identity != s.Identity) throw new InvalidDataException("Foreign player record."); }
    }
    private void Save(JournalState next)
    {
        Validate(next); var bytes = Encoding.UTF8.GetBytes(WireJson.Encode(next));
        if (bytes.Length > MaxBytes) throw new IOException("Journal full. Export/archive explicitly; no records were discarded.");
        var staged = path + ".new";
        try
        {
            using (var file = new FileStream(staged, FileMode.Create, FileAccess.Write, FileShare.None)) { file.Write(bytes); file.Flush(flushToDisk: true); }
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(staged, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            if (File.Exists(path)) File.Replace(staged, path, path + ".bak"); else File.Move(staged, path);
            State = next;
        }
        finally { if (File.Exists(staged)) File.Delete(staged); }
    }
}
