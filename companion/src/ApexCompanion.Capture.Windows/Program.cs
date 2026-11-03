namespace ApexCompanion.Capture.Windows;
internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        if (args.SequenceEqual(new[] { "--synthetic-window" })) { Application.Run(new SyntheticResultsWindow()); return; }
        PlayerIdentity? identity = null;
        if (args.Length != 0)
        {
            if (args.Length != 4 || args[0] != "--uid" || args[2] != "--platform" || args[1].Length is < 1 or > 64 || args[1].Any(c => c is < '0' or > '9') || args[3] is not ("PC" or "PLAYSTATION" or "XBOX" or "SWITCH"))
            { MessageBox.Show("Usage: no arguments for independent history; or --uid <numeric UID> --platform PC|PLAYSTATION|XBOX|SWITCH. Separate capture identity is user configured, not read from the game."); return; }
            identity = new(args[1], args[3]);
        }
        Application.Run(new CaptureForm(identity));
    }
}
