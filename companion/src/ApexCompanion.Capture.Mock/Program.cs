using System.Net;
using System.Text;
using ApexCompanion.Capture;
using ApexCompanion.Capture.Server;

if (args.Length < 3 || args.Length > 4)
{
    Console.Error.WriteLine("Synthetic local HTTPS mock only. Usage: <private-ipv4> <port> <private-data-dir> [--add-synthetic]"); return 2;
}
var address = IPAddress.Parse(args[0]); var port = int.Parse(args[1]);
if (!LocalSyncServer.IsPrivateAddress(address)) throw new ArgumentException("Private IPv4 only.");
var directory = Path.GetFullPath(args[2]); Directory.CreateDirectory(directory);
if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
using var processLock = new FileStream(Path.Combine(directory, "process.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
var journal = new CaptureJournal(Path.Combine(directory, "matches.json"));
if (args.Length == 4 && args[3] == "--add-synthetic")
{
    var eventId = journal.State.CompanionId + ":synthetic:" + Guid.NewGuid().ToString("N");
    journal.AddSyntheticAccepted(new(1, eventId, null, MatchMode.BATTLE_ROYALE, "Synthetic map", "Synthetic legend", 3, 2, 1234, 4, null,
        DateTimeOffset.UtcNow.ToUnixTimeSeconds(), TimestampSource.RESULT_OBSERVED, MatchSource.WINDOWS_CAPTURE,
        MatchQuality.USER_CONFIRMED, eventId, "synthetic-v1", new() { ["kills"] = 1, ["assists"] = 1, ["damage"] = 1, ["placement"] = 1 }));
}
var secrets = new PairingSecrets(Path.Combine(directory, "pairing.secret"), journal.State.CompanionId, address);
var pairing = secrets.GetOrCreatePairing(new Uri($"https://{address}:{port}"), journal.State.Identity);
// Explicit local test provisioning, not telemetry or a console secret dump.
var pairingPath = Path.Combine(directory, "pairing.json");
await File.WriteAllTextAsync(pairingPath, WireJson.Encode(pairing), new UTF8Encoding(false));
if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(pairingPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
await using var server = new LocalSyncServer(journal, secrets, address, port); await server.StartAsync();
Console.WriteLine("SYNTHETIC mock HTTPS started. Pairing document is in the chosen private directory. No game capture. Ctrl+C stops listener.");
await server.WaitForShutdownAsync();
return 0;
