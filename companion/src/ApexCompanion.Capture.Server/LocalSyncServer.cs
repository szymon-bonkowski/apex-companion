using System.Net;
using System.Security.Authentication;
using System.Text.Json;
using ApexCompanion.Capture;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace ApexCompanion.Capture.Server;

public sealed class LocalSyncServer : IAsyncDisposable
{
    private readonly WebApplication app;
    public LocalSyncServer(CaptureJournal journal, PairingSecrets secrets, IPAddress bindAddress, int port)
    {
        if (!IsPrivateAddress(bindAddress) || port is < 1024 or > 65535) throw new ArgumentException("Select one private IPv4 interface and an unprivileged port.");
        var builder = WebApplication.CreateSlimBuilder(); builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.Limits.MaxRequestBodySize = 4096;
            options.Limits.MaxConcurrentConnections = 8;
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
            options.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(15);
            options.Listen(bindAddress, port, listen => listen.UseHttps(https => { https.ServerCertificate = secrets.Certificate; https.SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13; }));
        });
        app = builder.Build();
        app.Use(async (context, next) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (!secrets.Authenticate(context.Request.Headers.Authorization.ToString())) { context.Response.StatusCode = 401; return; }
            try { await next(context); }
            catch (Exception e) when (e is InvalidDataException or JsonException or ArgumentException or InvalidOperationException)
            { context.Response.StatusCode = 400; } // No OCR/private values in error responses.
        });
        app.MapGet("/v1/matches", async context =>
        {
            if (context.Request.Query.Count != 1 || !long.TryParse(context.Request.Query["after"], out var after)) { context.Response.StatusCode = 400; return; }
            var batch = journal.Batch(after);
            var result = WireJson.Encode(new { schemaVersion = 1, journal.State.CompanionId, cursor = batch.Cursor, records = batch.Records });
            if (System.Text.Encoding.UTF8.GetByteCount(result) > 1_048_576) throw new InvalidDataException("Sync batch exceeded capacity.");
            context.Response.ContentType = "application/json"; await context.Response.WriteAsync(result);
        });
        app.MapPost("/v1/ack", async context =>
        {
            using var doc = await JsonDocument.ParseAsync(context.Request.Body, new() { MaxDepth = 3 });
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object || root.EnumerateObject().Count() != 1 || !root.TryGetProperty("cursor", out var cursor)) throw new InvalidDataException("Invalid ack.");
            journal.Acknowledge(cursor.GetInt64()); context.Response.StatusCode = 204;
        });
        app.MapPost("/v1/unpair", context => { secrets.Revoke(); context.Response.StatusCode = 204; return Task.CompletedTask; });
    }
    public Task StartAsync(CancellationToken token = default) => app.StartAsync(token);
    public Task WaitForShutdownAsync() => app.WaitForShutdownAsync();
    public async ValueTask DisposeAsync() { await app.StopAsync(); await app.DisposeAsync(); }
    public static bool IsPrivateAddress(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && (bytes[0] == 10 || bytes[0] == 127 || (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31));
    }
}
