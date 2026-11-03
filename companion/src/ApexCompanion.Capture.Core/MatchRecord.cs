using System.Text.Json;
using System.Text.Json.Serialization;

namespace ApexCompanion.Capture;

public sealed record PlayerIdentity(string Uid, string Platform);
public enum MatchMode { UNKNOWN, BATTLE_ROYALE, RANKED, MIXTAPE }
public enum MatchSource { WINDOWS_CAPTURE, SCREENSHOT, MANUAL }
public enum MatchQuality { REVIEW_REQUIRED, AUTO_ACCEPTED, USER_CONFIRMED }
public enum TimestampSource { RESULT_OBSERVED, USER_ENTERED }

/// The portable allowlist. This object never contains OCR text, pixels, secrets or local paths.
public sealed record MatchRecord(
    int SchemaVersion, string Id, PlayerIdentity? Identity, MatchMode Mode, string? Map, string? Legend,
    int? Kills, int? Assists, int? Damage, int? Placement, int? RpDelta, long TimestampSeconds,
    TimestampSource TimestampSource, MatchSource Source, MatchQuality Quality,
    string? EvidenceEventId, string? ParserVersion, Dictionary<string, double> FieldConfidence, int Revision = 0)
{
    public void Validate(long now)
    {
        if (SchemaVersion != 1 || !ValidId(Id) || TimestampSeconds < 1 || TimestampSeconds > now || Revision is < 0 or > 1000000)
            throw new InvalidDataException("Invalid match metadata.");
        if (!Enum.IsDefined(Mode) || !Enum.IsDefined(Source) || !Enum.IsDefined(Quality) || !Enum.IsDefined(TimestampSource))
            throw new InvalidDataException("Unsupported match enum.");
        if (Identity is not null && (Identity.Uid.Length is < 1 or > 64 || Identity.Uid.Any(c => c is < '0' or > '9') ||
            Identity.Platform is not ("PC" or "PLAYSTATION" or "XBOX" or "SWITCH"))) throw new InvalidDataException("Invalid player identity.");
        if (!OptionalText(Map) || !OptionalText(Legend) || Kills is < 0 or > 100 || Assists is < 0 or > 100 ||
            Damage is < 0 or > 100000 || Placement is < 1 or > 100 || RpDelta is < -10000 or > 10000)
            throw new InvalidDataException("Invalid statistic; bounds are defensive schema limits, not game rules.");
        if (Source == MatchSource.MANUAL && Quality != MatchQuality.USER_CONFIRMED)
            throw new InvalidDataException("Manual records require confirmation.");
        if (Source != MatchSource.MANUAL && (!ValidId(EvidenceEventId) || (!OptionalText(ParserVersion) || ParserVersion is null)))
            throw new InvalidDataException("Machine records require immutable event/parser identity.");
        if (Source == MatchSource.MANUAL && (EvidenceEventId is not null || ParserVersion is not null))
            throw new InvalidDataException("Manual records cannot claim capture evidence.");
        if (Source == MatchSource.MANUAL && FieldConfidence.Count != 0) throw new InvalidDataException("Manual records cannot claim machine confidence.");
        if (Quality == MatchQuality.AUTO_ACCEPTED && (Source != MatchSource.WINDOWS_CAPTURE || TimestampSource != TimestampSource.RESULT_OBSERVED || Mode == MatchMode.UNKNOWN || FieldConfidence.Count == 0))
            throw new InvalidDataException("Auto acceptance needs explicit capture and field evidence.");
        string[] fields = ["mode", "map", "legend", "kills", "assists", "damage", "placement", "rpDelta"];
        if (FieldConfidence.Count > fields.Length || FieldConfidence.Any(x => !fields.Contains(x.Key) || !double.IsFinite(x.Value) || x.Value is < 0 or > 1))
            throw new InvalidDataException("Invalid field confidence.");
        foreach (var field in FieldConfidence.Keys)
        {
            var present = field switch { "mode" => Mode != MatchMode.UNKNOWN, "map" => Map is not null, "legend" => Legend is not null, "kills" => Kills is not null, "assists" => Assists is not null, "damage" => Damage is not null, "placement" => Placement is not null, "rpDelta" => RpDelta is not null, _ => false };
            if (!present) throw new InvalidDataException("Confidence for absent field.");
        }
    }
    public static bool ValidId(string? s) => s is { Length: >= 1 and <= 180 } && s.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.' or ':');
    private static bool OptionalText(string? s) => s is null || (s.Length is >= 1 and <= 100 && !string.IsNullOrWhiteSpace(s) && !s.Any(char.IsControl));
}

public static class WireJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        MaxDepth = 16,
        RespectRequiredConstructorParameters = true,
        WriteIndented = false,
        Converters = { new JsonStringEnumConverter(allowIntegerValues: false) }
    };
    public static string Encode<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Decode<T>(string value)
    {
        using var doc = JsonDocument.Parse(value, new() { MaxDepth = 16 });
        RejectDuplicates(doc.RootElement);
        return JsonSerializer.Deserialize<T>(value, Options) ?? throw new InvalidDataException("Null document.");
    }
    private static void RejectDuplicates(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var field in element.EnumerateObject()) { if (!seen.Add(field.Name)) throw new InvalidDataException("Duplicate JSON key."); RejectDuplicates(field.Value); }
        }
        else if (element.ValueKind == JsonValueKind.Array) foreach (var item in element.EnumerateArray()) RejectDuplicates(item);
    }
}
