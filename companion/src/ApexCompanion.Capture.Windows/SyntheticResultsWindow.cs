namespace ApexCompanion.Capture.Windows;

/// Original typography only, for ordinary-window WGC verification on Windows. No game assets or automation.
internal sealed class SyntheticResultsWindow : Form
{
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    private DateTimeOffset? started;
    public SyntheticResultsWindow()
    {
        Text = "Apex Legends — SYNTHETIC TEST WINDOW"; Width = 1280; Height = 760; BackColor = Color.FromArgb(22, 27, 33); DoubleBuffered = true;
        var cycle = new Button { Text = "Run two synthetic HUD → result → menu cycles (about 1 minute)", Dock = DockStyle.Bottom, Height = 50 };
        cycle.Click += (_, _) => { started = DateTimeOffset.UtcNow; timer.Start(); Invalidate(); }; Controls.Add(cycle);
        timer.Tick += (_, _) => { if (started is not null && (DateTimeOffset.UtcNow - started).Value.TotalSeconds >= 62) timer.Stop(); Invalidate(); };
        FormClosed += (_, _) => timer.Dispose();
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e); var age = started is null ? -1 : (int)(DateTimeOffset.UtcNow - started).Value.TotalSeconds;
        var phase = age < 0 ? -1 : age % 31;
        var title = phase < 0 ? "SYNTHETIC WINDOW — NO APEX DATA" : phase < 22 ? "SQUADS LEFT 12" : phase < 29 ? "MATCH SUMMARY" : "PLAY APEX LOBBY";
        using var font = new Font(FontFamily.GenericSansSerif, Math.Max(16, ClientSize.Width / 42f), FontStyle.Regular, GraphicsUnit.Pixel);
        e.Graphics.DrawString(title, font, Brushes.White, ClientSize.Width * .07f, ClientSize.Height * .07f);
        var lines = phase is >= 22 and < 29 ? new[] { "KILLS 3", "ASSISTS 2", "DAMAGE DEALT 1234", "SQUAD PLACED 4", "RP DELTA +123", "MODE RANKED" } : new[] { "Original synthetic test content only." };
        for (var i = 0; i < lines.Length; i++) e.Graphics.DrawString(lines[i], font, Brushes.White, ClientSize.Width * .07f, ClientSize.Height * .29f + i * font.Size * 1.7f);
    }
}
