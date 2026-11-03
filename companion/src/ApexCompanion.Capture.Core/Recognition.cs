using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace ApexCompanion.Capture;

public enum ScreenKind { UNKNOWN, IN_MATCH, RESULTS, MENU, LOADING, SPECTATOR, SESSION_SUMMARY, UNAVAILABLE }
public enum CaptureState { DISABLED, WAITING_FOR_GAME, IN_MATCH, RESULTS_DETECTED, EXTRACTING, CANDIDATE_READY, AUTO_ACCEPTED, REVIEW_REQUIRED, RETURNED_TO_MENU }
public sealed record OcrLine(string Text, double Confidence);
public sealed record OcrReading(long Sequence, long TimestampSeconds, int Width, int Height, IReadOnlyList<OcrLine> Header, IReadOnlyList<OcrLine> Statistics);
public sealed record ParsedFrame(long Sequence, long TimestampSeconds, ScreenKind Screen, string ParserVersion, IReadOnlyDictionary<string, int> Fields, IReadOnlyDictionary<string, double> Confidence, MatchMode Mode = MatchMode.UNKNOWN, double ModeConfidence = 0);
public sealed record LayoutRegion(double X, double Y, double Width, double Height);
public sealed record LayoutConfig(string Version, LayoutRegion Header, LayoutRegion Statistics)
{
    // Deliberately an UNVALIDATED English prototype, not a supported Apex season layout.
    public static readonly LayoutConfig Prototype = new("english-prototype-v1", new(0.05, 0, 0.9, 0.25), new(0.05, 0.20, 0.9, 0.70));
    public static bool AutomaticAcceptanceEnabled => false; // No real, held-out Apex corpus was supplied.
}
public interface IScreenClassifier { ScreenKind Classify(OcrReading reading); }
public interface IFieldExtractor { ParsedFrame Extract(OcrReading reading, ScreenKind kind); }
public interface IImageOcr { Task<IReadOnlyList<OcrLine>> ReadAsync(byte[] encodedImage, CancellationToken cancellationToken); }

public sealed class EnglishResultsClassifier : IScreenClassifier
{
    public ScreenKind Classify(OcrReading r)
    {
        if (r.Width < 640 || r.Height < 360 || r.Width > 7680 || r.Height > 4320) return ScreenKind.UNKNOWN;
        var header = string.Join(" ", r.Header.Where(x => x.Confidence >= .65).Select(x => x.Text)).ToUpperInvariant();
        var stats = string.Join(" ", r.Statistics.Where(x => x.Confidence >= .65).Select(x => x.Text)).ToUpperInvariant();
        if (header.Contains("SESSION SUMMARY") || stats.Contains("SESSION SUMMARY")) return ScreenKind.SESSION_SUMMARY;
        if (header.Contains("SPECTAT") || stats.Contains("SPECTATING")) return ScreenKind.SPECTATOR;
        if (header.Contains("MATCH SUMMARY") && (stats.Contains("DAMAGE DEALT") || stats.Contains("SQUAD PLACED"))) return ScreenKind.RESULTS;
        if (header.Contains("SQUADS LEFT") || header.Contains("PLAYERS LEFT")) return ScreenKind.IN_MATCH;
        if (header.Contains("LOADING") || header.Contains("CONNECTING")) return ScreenKind.LOADING;
        if (header.Contains("PLAY APEX") || header.Contains("LOBBY")) return ScreenKind.MENU;
        return ScreenKind.UNKNOWN;
    }
}

public sealed partial class EnglishFieldExtractor : IFieldExtractor
{
    private static readonly (string Field, Regex Pattern, int Min, int Max)[] Patterns =
    [
        ("kills", new(@"^KILLS\s*:?\s*([0-9]{1,3})$", RegexOptions.CultureInvariant), 0, 100),
        ("assists", new(@"^ASSISTS\s*:?\s*([0-9]{1,3})$", RegexOptions.CultureInvariant), 0, 100),
        ("damage", new(@"^DAMAGE DEALT\s*:?\s*([0-9]{1,6})$", RegexOptions.CultureInvariant), 0, 100000),
        ("placement", new(@"^SQUAD PLACED\s*:?\s*#?\s*([0-9]{1,3})$", RegexOptions.CultureInvariant), 1, 100),
        ("rpDelta", new(@"^RP (?:CHANGE|DELTA)\s*:?\s*([+-][0-9]{1,5})$", RegexOptions.CultureInvariant), -10000, 10000)
    ];
    public ParsedFrame Extract(OcrReading r, ScreenKind kind)
    {
        var fields = new Dictionary<string, int>(); var confidence = new Dictionary<string, double>();
        if (kind == ScreenKind.RESULTS)
        foreach (var rule in Patterns)
        {
            var values = r.Statistics.Where(l => l.Confidence >= .65).Select(l => (Line: l, Match: rule.Pattern.Match(l.Text.Trim().ToUpperInvariant())))
                .Where(x => x.Match.Success).Select(x => (Value: int.Parse(x.Match.Groups[1].Value, CultureInfo.InvariantCulture), x.Line.Confidence)).ToList();
            if (values.Count != 0 && values.All(x => x.Value == values[0].Value) && values[0].Value >= rule.Min && values[0].Value <= rule.Max)
            { fields[rule.Field] = values[0].Value; confidence[rule.Field] = values.Min(x => x.Confidence); }
        }
        var modes = kind == ScreenKind.RESULTS ? r.Statistics.Where(x => x.Confidence >= .65).Select(x => (Text: x.Text.Trim().ToUpperInvariant(), x.Confidence))
            .Where(x => x.Text is "MODE RANKED" or "MODE BATTLE ROYALE" or "MODE MIXTAPE").ToList() : [];
        var mode = modes.Count > 0 && modes.All(x => x.Text == modes[0].Text) ? modes[0].Text switch { "MODE RANKED" => MatchMode.RANKED, "MODE BATTLE ROYALE" => MatchMode.BATTLE_ROYALE, "MODE MIXTAPE" => MatchMode.MIXTAPE, _ => MatchMode.UNKNOWN } : MatchMode.UNKNOWN;
        return new(r.Sequence, r.TimestampSeconds, kind, LayoutConfig.Prototype.Version, fields, confidence, mode, mode == MatchMode.UNKNOWN ? 0 : modes.Min(x => x.Confidence));
    }
}

/// A bounded external offline engine. PNG bytes use stdin; no screenshot is written to disk.
public sealed class TesseractOcr(string executable) : IImageOcr
{
    public async Task<IReadOnlyList<OcrLine>> ReadAsync(byte[] encodedImage, CancellationToken cancellationToken)
    {
        if (encodedImage.Length is < 1 or > 16_777_216) throw new InvalidDataException("Image too large.");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken); timeout.CancelAfter(TimeSpan.FromSeconds(8));
        var psi = new ProcessStartInfo(executable) { RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
        foreach (var arg in new[] { "stdin", "stdout", "-l", "eng", "--psm", "6", "tsv" }) psi.ArgumentList.Add(arg);
        psi.Environment["OMP_THREAD_LIMIT"] = "1";
        using var process = Process.Start(psi) ?? throw new IOException("Local OCR engine unavailable.");
        try
        {
            var outputTask = ReadBounded(process.StandardOutput, 1_048_576, timeout.Token);
            var errorTask = ReadBounded(process.StandardError, 65536, timeout.Token); // Never log incidental engine output.
            await process.StandardInput.BaseStream.WriteAsync(encodedImage, timeout.Token); process.StandardInput.Close();
            await process.WaitForExitAsync(timeout.Token);
            var output = await outputTask; await errorTask;
            if (process.ExitCode != 0) throw new IOException("Local OCR failed; check installed English language data.");
            return ParseTsv(output);
        }
        finally { if (!process.HasExited) process.Kill(entireProcessTree: true); }
    }
    private static async Task<string> ReadBounded(StreamReader reader, int max, CancellationToken token)
    {
        var builder = new StringBuilder(); var chars = new char[4096];
        int read; while ((read = await reader.ReadAsync(chars, token)) != 0) { if (builder.Length + read > max) throw new InvalidDataException("OCR output capacity exceeded."); builder.Append(chars, 0, read); }
        return builder.ToString();
    }
    public static IReadOnlyList<OcrLine> ParseTsv(string tsv)
    {
        var lines = new Dictionary<string, List<(string Text, double Confidence)>>();
        foreach (var row in tsv.Split('\n').Skip(1).Take(10000))
        {
            var c = row.TrimEnd('\r').Split('\t');
            if (c.Length != 12 || c[0] != "5" || string.IsNullOrWhiteSpace(c[11]) || c[11].Length > 100 || !double.TryParse(c[10], CultureInfo.InvariantCulture, out var conf) || !double.IsFinite(conf) || conf is < 0 or > 100) continue;
            var key = string.Join(":", c.Skip(1).Take(4));
            if (!lines.TryGetValue(key, out var words)) lines[key] = words = [];
            words.Add((c[11], conf / 100));
        }
        return lines.Values.Select(words => new OcrLine(string.Join(" ", words.Select(x => x.Text)), words.Min(x => x.Confidence))).ToList();
    }
}
