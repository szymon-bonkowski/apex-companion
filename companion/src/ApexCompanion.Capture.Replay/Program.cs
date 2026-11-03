using System.Diagnostics;
using System.Text.Json;
using ApexCompanion.Capture;

if (args.Length != 3) { Console.Error.WriteLine("Usage: <local-tesseract-executable> <synthetic-fixture-directory> <evidence-json-output>"); return 2; }
var directory = Path.GetFullPath(args[1]);
var manifestPath = Path.Combine(directory, "manifest.json");
if (new FileInfo(manifestPath).Length > 1_048_576) throw new InvalidDataException("Fixture manifest too large.");
using var manifest = JsonDocument.Parse(File.ReadAllText(manifestPath));
var root = manifest.RootElement;
if (root.GetProperty("schemaVersion").GetInt32() != 1 || root.GetProperty("dataset").GetString() != "original-synthetic-v1")
    throw new InvalidDataException("Only the documented original synthetic fixture set is supported.");
var ocr = new TesseractOcr(args[0]); var classifier = new EnglishResultsClassifier(); var extractor = new EnglishFieldExtractor();
var fields = new Dictionary<string, (int Expected, int Exact, int Wrong, int Missing)>();
var examples = new List<object>(); int positives = 0, negatives = 0, falsePositive = 0, falseNegative = 0; long sequence = 0;
var timer = Stopwatch.StartNew();
foreach (var sample in root.GetProperty("samples").EnumerateArray())
{
    var started = Stopwatch.GetTimestamp();
    byte[] Image(string key)
    {
        var name = sample.GetProperty(key).GetString()!;
        if (Path.GetFileName(name) != name || !name.EndsWith(".png", StringComparison.Ordinal)) throw new InvalidDataException("Only local fixture PNG names allowed.");
        var path = Path.Combine(directory, name); if (new FileInfo(path).Length > 16_777_216) throw new InvalidDataException("Fixture too large.");
        return File.ReadAllBytes(path);
    }
    var reading = new OcrReading(++sequence, DateTimeOffset.UtcNow.ToUnixTimeSeconds(), sample.GetProperty("width").GetInt32(), sample.GetProperty("height").GetInt32(),
        await ocr.ReadAsync(Image("headerImage"), CancellationToken.None), await ocr.ReadAsync(Image("statisticsImage"), CancellationToken.None));
    var screen = classifier.Classify(reading); var parsed = extractor.Extract(reading, screen);
    var expectedScreen = Enum.Parse<ScreenKind>(sample.GetProperty("screen").GetString()!);
    if (expectedScreen == ScreenKind.RESULTS) { positives++; if (screen != ScreenKind.RESULTS) falseNegative++; }
    else { negatives++; if (screen == ScreenKind.RESULTS) falsePositive++; }
    var details = new Dictionary<string, object>();
    foreach (var expected in sample.GetProperty("fields").EnumerateObject())
    {
        var target = expected.Value.GetInt32(); var exists = parsed.Fields.TryGetValue(expected.Name, out var value);
        var previous = fields.GetValueOrDefault(expected.Name);
        fields[expected.Name] = (previous.Expected + 1, previous.Exact + (exists && value == target ? 1 : 0), previous.Wrong + (exists && value != target ? 1 : 0), previous.Missing + (!exists ? 1 : 0));
        details[expected.Name] = new { expected = target, actual = exists ? (int?)value : null, confidence = parsed.Confidence.GetValueOrDefault(expected.Name) };
    }
    examples.Add(new { id = sample.GetProperty("id").GetString(), expectedScreen = expectedScreen.ToString(), actualScreen = screen.ToString(), fields = details,
        elapsedMilliseconds = Stopwatch.GetElapsedTime(started).TotalMilliseconds });
}
timer.Stop();
var evidence = new {
    schemaVersion = 1, dataset = "original-synthetic-v1", realApexFixtureCount = 0, heldOutRealFixtureCount = 0, syntheticOnly = true,
    layout = LayoutConfig.Prototype.Version, autoAcceptanceEnabled = LayoutConfig.AutomaticAcceptanceEnabled,
    platform = System.Runtime.InteropServices.RuntimeInformation.OSDescription, runtime = Environment.Version.ToString(), measuredUtc = DateTimeOffset.UtcNow,
    resultClassification = new { positives, negatives, falsePositive, falseNegative },
    fields = fields.ToDictionary(x => x.Key, x => new { expected = x.Value.Expected, exact = x.Value.Exact, wrong = x.Value.Wrong, missing = x.Value.Missing }),
    elapsedMilliseconds = timer.Elapsed.TotalMilliseconds, parentPeakWorkingSetBytes = Process.GetCurrentProcess().PeakWorkingSet64,
    resourceScope = "Linux serial replay elapsed time; parent .NET working set excludes Tesseract children. Not Windows/game overhead.", examples
};
await File.WriteAllTextAsync(args[2], JsonSerializer.Serialize(evidence, new JsonSerializerOptions { WriteIndented = true }) + "\n");
Console.WriteLine($"Synthetic fixtures={examples.Count}, positive={positives}, negative={negatives}, FP={falsePositive}, FN={falseNegative}. Real Apex fixtures=0; autoaccept disabled.");
return falsePositive + falseNegative + fields.Values.Sum(x => x.Wrong + x.Missing) == 0 ? 0 : 1;
