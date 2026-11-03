using System.Net;
using System.Net.Sockets;
using ApexCompanion.Capture.Server;

namespace ApexCompanion.Capture.Windows;

internal sealed class CaptureForm : Form
{
    private readonly Label health = new() { AutoSize = true, MaximumSize = new(700, 0) };
    private readonly Label counts = new() { AutoSize = true };
    private readonly Label lastResult = new() { AutoSize = true, MaximumSize = new(690, 0) };
    private readonly TextBox engine = new() { Width = 650, Text = @"C:\Program Files\Tesseract-OCR\tesseract.exe" };
    private readonly TextBox address = new() { Width = 180 };
    private readonly NumericUpDown sampling = new() { Minimum = 1000, Maximum = 10000, Value = 2000, Increment = 500, Width = 100 };
    private readonly ListBox candidates = new() { Width = 690, Height = 160 };
    private readonly Button start = new() { Text = "Choose Apex window / Start", AutoSize = true };
    private readonly Button stop = new() { Text = "STOP capture", AutoSize = true };
    private readonly Button resumeSync = new() { Text = "Start / resume paired local sync", AutoSize = true, Enabled = false };
    private readonly CaptureJournal journal = null!;
    private readonly CaptureMachine machine = null!;
    private readonly PairingSecrets? secrets;
    private WindowCapture? capture;
    private LocalSyncServer? server;
    private bool closing, cleanupComplete;
    private readonly string dataDirectory;
    private Mutex? singleInstance;
    private bool ownsInstance;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    public CaptureForm(PlayerIdentity? identity = null)
    {
        Text = "Apex Companion Auto Capture — prototype"; Width = 760; Height = 690;
        var scope = identity is null ? "independent" : "player-" + identity.Platform + "-" + identity.Uid;
        dataDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ApexCompanion", "Capture", scope);
        address.Text = Dns.GetHostAddresses(Dns.GetHostName()).FirstOrDefault(x => x.AddressFamily == AddressFamily.InterNetwork && LocalSyncServer.IsPrivateAddress(x) && !IPAddress.IsLoopback(x))?.ToString() ?? "127.0.0.1";
        var layout = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, Dock = DockStyle.Fill, Padding = new(20), AutoScroll = true };
        Controls.Add(layout);
        layout.Controls.Add(new Label { Text = identity is null ? "Scope: independent, unlinked history" : $"Scope: {identity.Platform} UID {identity.Uid} (user configured; never read from game)", AutoSize = true });
        layout.Controls.Add(new Label { Text = "Local, opt-in selected-window capture. No video or raw frames are saved.", AutoSize = true });
        layout.Controls.Add(new Label { Text = "Unvalidated English prototype: results require review. No live Apex compatibility claim.", AutoSize = true });
        layout.Controls.Add(new Label { Text = "Installed local Tesseract 5 executable (English data required)", AutoSize = true }); layout.Controls.Add(engine);
        layout.Controls.Add(new Label { Text = "Sampling interval (ms; one OCR job at a time)", AutoSize = true }); layout.Controls.Add(sampling);
        layout.Controls.Add(start); layout.Controls.Add(stop); layout.Controls.Add(health); layout.Controls.Add(counts); layout.Controls.Add(lastResult);
        layout.Controls.Add(new Label { Text = "Candidates — select to review extracted fields", AutoSize = true }); layout.Controls.Add(candidates);
        var review = new Button { Text = "Review and confirm selected candidate", AutoSize = true }; layout.Controls.Add(review);
        layout.Controls.Add(new Label { Text = "Selected private LAN IPv4 (phone must reach this address), HTTPS port 43821", AutoSize = true }); layout.Controls.Add(address);
        layout.Controls.Add(resumeSync);
        var pair = new Button { Text = "Pair new device / rotate pairing code", AutoSize = true }; layout.Controls.Add(pair);
        var unpair = new Button { Text = "Unpair and stop local sync", AutoSize = true }; layout.Controls.Add(unpair);
        var privacy = new Label { Text = "Stop immediately with STOP capture or close this window. OS capture border stays enabled.\nDo not use if capture is blocked or your game rules prohibit it. No bypass is provided.", AutoSize = true, MaximumSize = new(690, 0) }; layout.Controls.Add(privacy);
        FormClosed += (_, _) => { if (ownsInstance) singleInstance?.ReleaseMutex(); singleInstance?.Dispose(); };
        try
        {
            var scopeId = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(dataDirectory)));
            singleInstance = new Mutex(true, "Local\\ApexCompanionCapture-" + scopeId, out ownsInstance);
            if (!ownsInstance) {
                health.Text = "Another companion already owns this identity journal. Use its existing window; no second writer was started.";
                start.Enabled = stop.Enabled = pair.Enabled = unpair.Enabled = review.Enabled = false; return;
            }
            journal = new(Path.Combine(dataDirectory, "matches-v1.json"), identity);
            machine = new(journal.State.CompanionId, journal.State.Identity, journal.State.Checkpoint);
            secrets = new(Path.Combine(dataDirectory, "pairing.dpapi"), journal.State.CompanionId, IPAddress.Parse(address.Text));
        }
        catch (Exception) { health.Text = "Private journal/credentials unreadable. Original files retained. Restore/recover them explicitly before tracking."; start.Enabled = stop.Enabled = pair.Enabled = unpair.Enabled = review.Enabled = false; return; }
        resumeSync.Enabled = secrets!.HasPairing;
        health.Text = "DISABLED — capture and sync do not start automatically. Saved pairing can be resumed without copying a new code.";
        start.Click += async (_, _) => await Guard(async () =>
        {
            start.Enabled = false; if (capture is not null) { var previousCapture = capture; capture = null; await previousCapture.DisposeAsync(); }
            if (!File.Exists(engine.Text)) throw new FileNotFoundException("Choose an installed local Tesseract executable.");
            lock (machine) machine.Enable(); capture = new(new TesseractOcr(engine.Text), Consume, SetStatus) { SamplingMilliseconds = (int)sampling.Value };
            if (!await capture.StartAsync(Handle)) { lock (machine) machine.Disable(); health.Text = "DISABLED — picker cancelled."; }
            RefreshCounts(); start.Enabled = true;
        });
        stop.Click += async (_, _) => await Guard(async () => { lock (machine) machine.Disable(); if (capture is not null) await capture.StopAsync(); health.Text = "DISABLED — no capture processing."; start.Enabled = true; });
        review.Click += (_, _) =>
        {
            if (candidates.SelectedItem is not CandidateItem selected) return;
            var r = selected.Record;
            var summary = $"Observed {DateTimeOffset.FromUnixTimeSeconds(r.TimestampSeconds):u}\nKills: {r.Kills?.ToString() ?? "unknown"}\nAssists: {r.Assists?.ToString() ?? "unknown"}\nDamage: {r.Damage?.ToString() ?? "unknown"}\nPlacement: {r.Placement?.ToString() ?? "unknown"}\nRP: {r.RpDelta?.ToString() ?? "unknown"}\nMode/map/legend: unknown\n\nConfirm only if these visible values are correct. This queues the record for your paired mobile app. Original machine provenance is retained. Cancel leaves the candidate provisional.";
            if (MessageBox.Show(this, summary, "Review machine candidate", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            try { journal.Confirm(r.Id); RefreshCounts(); } catch (Exception) { health.Text = "Confirmation failed; saved data retained. Check capacity and that at least one statistic is present."; }
        };
        resumeSync.Click += async (_, _) => await Guard(async () =>
        {
            if (!secrets!.HasPairing) throw new InvalidOperationException("Pair a device first.");
            await StartListener(); health.Text = "Local sync resumed with the saved pairing. No new code is needed on the phone.";
        });
        pair.Click += async (_, _) => await Guard(async () =>
        {
            var bind = IPAddress.Parse(address.Text); if (!LocalSyncServer.IsPrivateAddress(bind)) throw new ArgumentException("Choose private IPv4.");
            // An existing certificate's SAN must cover this address. Changing interface requires explicit credential reset/re-pairing.
            await StartListener();
            var document = secrets!.Pair(new Uri($"https://{bind}:43821"), journal.State.Identity);
            using var dialog = new Form { Text = "Private one-time pairing configuration", Width = 720, Height = 350 };
            dialog.Controls.Add(new TextBox { Multiline = true, ReadOnly = true, Dock = DockStyle.Fill, Text = WireJson.Encode(document), WordWrap = true });
            dialog.ShowDialog(this); health.Text = "Local HTTPS sync enabled. Copy pairing configuration only to your own mobile app. A new pairing revokes the old token.";
        });
        unpair.Click += async (_, _) => await Guard(async () => { secrets!.Revoke(); if (server is not null) { await server.DisposeAsync(); server = null; } health.Text = "Pairing revoked. Local listener stopped."; });
        FormClosing += async (_, e) =>
        {
            if (cleanupComplete) return;
            e.Cancel = true;
            if (closing) return;
            closing = true; Enabled = false; lock (machine) machine.Disable(); timer.Stop();
            try { if (capture is not null) await capture.DisposeAsync(); if (server is not null) await server.DisposeAsync(); }
            finally { cleanupComplete = true; Close(); }
        };
        timer.Tick += (_, _) => RefreshCounts(); timer.Start(); RefreshCounts();
    }
    private async Task StartListener()
    {
        var bind = IPAddress.Parse(address.Text);
        if (!LocalSyncServer.IsPrivateAddress(bind)) throw new ArgumentException("Choose private IPv4.");
        if (server is not null) return;
        var candidate = new LocalSyncServer(journal, secrets!, bind, 43821);
        try { await candidate.StartAsync(); server = candidate; }
        catch { await candidate.DisposeAsync(); throw; }
    }
    private Task Consume(ParsedFrame frame)
    {
        // Background capture worker: journal commit precedes UI publication.
        CaptureUpdate update; lock (machine) { update = machine.Observe(frame); if (update.Candidate is { } record) { if (record.Quality == MatchQuality.AUTO_ACCEPTED) journal.AcceptAutomatic(record, machine.Checkpoint); else journal.UpsertCandidate(record, machine.Checkpoint); } }
        SetStatus($"{update.State} · screen {frame.Screen} · {capture?.SelectedWindow}"); return Task.CompletedTask;
    }
    private void SetStatus(string value) { if (!IsDisposed && IsHandleCreated) BeginInvoke(() => health.Text = value); }
    private async Task Guard(Func<Task> action) { try { await action(); } catch (Exception) { lock (machine) machine.Disable(); if (capture is not null) { try { await capture.StopAsync(); } catch (Exception) { } } start.Enabled = true; health.Text = "Operation failed. Private saved data retained. Check selected window, installed OCR, local interface and storage capacity."; } }
    private void RefreshCounts()
    {
        var s = journal.State;
        resumeSync.Enabled = secrets?.HasPairing == true;
        counts.Text = $"Auto accepted: {s.Accepted.Count(x => x.Record.Quality == MatchQuality.AUTO_ACCEPTED)} · User confirmed: {s.Accepted.Count(x => x.Record.Quality == MatchQuality.USER_CONFIRMED)} · Review: {s.Candidates.Count} · Pending sync: {s.Accepted.Count(x => x.Cursor > s.AcknowledgedCursor)}";
        var latest = s.Accepted.Select(x => x.Record).Concat(s.Candidates).OrderByDescending(x => x.TimestampSeconds).FirstOrDefault();
        lastResult.Text = latest is null ? "Last observed result: none" : $"Last saved result: {DateTimeOffset.FromUnixTimeSeconds(latest.TimestampSeconds):u} · {latest.Quality} · K {latest.Kills?.ToString() ?? "?"} · A {latest.Assists?.ToString() ?? "?"} · Damage {latest.Damage?.ToString() ?? "?"}";
        var selectedId = (candidates.SelectedItem as CandidateItem)?.Record.Id;
        candidates.Items.Clear(); foreach (var r in s.Candidates.OrderByDescending(x => x.TimestampSeconds)) { var item = new CandidateItem(r); candidates.Items.Add(item); if (r.Id == selectedId) candidates.SelectedItem = item; }
    }
    private sealed record CandidateItem(MatchRecord Record) { public override string ToString() => $"{DateTimeOffset.FromUnixTimeSeconds(Record.TimestampSeconds):u}  K {Record.Kills?.ToString() ?? "?"}  A {Record.Assists?.ToString() ?? "?"}  D {Record.Damage?.ToString() ?? "?"}  Placement {Record.Placement?.ToString() ?? "?"}"; }
}
