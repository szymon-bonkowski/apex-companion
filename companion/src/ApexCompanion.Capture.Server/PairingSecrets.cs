using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using ApexCompanion.Capture;

namespace ApexCompanion.Capture.Server;

public sealed record PairingDocument(string Endpoint, string CertificatePin, string Token, string CompanionId, PlayerIdentity? Identity);
internal sealed record SecretDocument(int Version, string CompanionId, string CertificatePfx, string? Token);

/// Windows current-user DPAPI; Linux is explicitly a mock with mode0600 files, not a credential vault.
public sealed class PairingSecrets
{
    private readonly string path;
    private readonly object gate = new();
    private SecretDocument secret;
    public X509Certificate2 Certificate { get; }
    public PairingSecrets(string path, string companionId, IPAddress address)
    {
        this.path = Path.GetFullPath(path); Directory.CreateDirectory(Path.GetDirectoryName(this.path)!);
        if (File.Exists(path))
        {
            if (new FileInfo(path).Length > 65536) throw new InvalidDataException("Invalid credential file.");
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (input.Length > 65536) throw new InvalidDataException("Invalid credential file.");
            var bytes = new byte[checked((int)input.Length)]; input.ReadExactly(bytes);
            if (input.ReadByte() != -1) throw new InvalidDataException("Credential file changed during read.");
            secret = WireJson.Decode<SecretDocument>(Encoding.UTF8.GetString(Unprotect(bytes)));
            if (secret.Version != 1 || secret.CompanionId != companionId) throw new InvalidDataException("Credential identity mismatch.");
            Certificate = X509CertificateLoader.LoadPkcs12(Convert.FromBase64String(secret.CertificatePfx), null, X509KeyStorageFlags.EphemeralKeySet);
            if (Certificate.NotAfter.ToUniversalTime() <= DateTime.UtcNow) throw new InvalidDataException("Local certificate expired. Revoke pairing and regenerate explicitly.");
        }
        else
        {
            using var key = RSA.Create(2048);
            var request = new CertificateRequest("CN=Apex Companion Local Capture", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            var san = new SubjectAlternativeNameBuilder(); san.AddIpAddress(address); san.AddIpAddress(IPAddress.Loopback); san.AddIpAddress(IPAddress.Parse("10.0.2.2"));
            request.CertificateExtensions.Add(san.Build()); request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            Certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(2));
            secret = new(1, companionId, Convert.ToBase64String(Certificate.Export(X509ContentType.Pfx)), null); Persist(secret);
        }
    }
    public bool HasPairing { get { lock (gate) return secret.Token is not null; } }
    /// Resume an explicitly configured device without rotating its protected credential.
    public PairingDocument GetOrCreatePairing(Uri endpoint, PlayerIdentity? identity)
    {
        lock (gate)
        {
            if (secret.Token is null) return Pair(endpoint, identity);
            return new(endpoint.GetLeftPart(UriPartial.Authority), Convert.ToHexStringLower(SHA256.HashData(Certificate.RawData)), secret.Token, secret.CompanionId, identity);
        }
    }
    public PairingDocument Pair(Uri endpoint, PlayerIdentity? identity)
    {
        lock (gate)
        {
            var next = secret with { Token = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32)) }; Persist(next);
            return new(endpoint.GetLeftPart(UriPartial.Authority), Convert.ToHexStringLower(SHA256.HashData(Certificate.RawData)), next.Token!, secret.CompanionId, identity);
        }
    }
    public bool Authenticate(string? bearer)
    {
        lock (gate)
        {
            if (secret.Token is null || bearer is null || !bearer.StartsWith("Bearer ", StringComparison.Ordinal)) return false;
            var token = bearer[7..];
            return token.Length == 64 && CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(token), Encoding.ASCII.GetBytes(secret.Token));
        }
    }
    public void Revoke() { lock (gate) Persist(secret with { Token = null }); }
    private void Persist(SecretDocument next)
    {
        var bytes = Protect(Encoding.UTF8.GetBytes(WireJson.Encode(next))); var staged = path + ".new";
        try
        {
            using (var stream = new FileStream(staged, FileMode.Create, FileAccess.Write, FileShare.None)) { stream.Write(bytes); stream.Flush(true); }
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(staged, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(staged, path, true); secret = next;
        }
        finally { if (File.Exists(staged)) File.Delete(staged); }
    }
    private static byte[] Protect(byte[] bytes) => OperatingSystem.IsWindows() ? Dpapi(bytes, false) : bytes;
    private static byte[] Unprotect(byte[] bytes) => OperatingSystem.IsWindows() ? Dpapi(bytes, true) : bytes;
    [StructLayout(LayoutKind.Sequential)] private struct DataBlob { public int Length; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(ref DataBlob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out DataBlob output);
    [DllImport("crypt32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(ref DataBlob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out DataBlob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);
    private static byte[] Dpapi(byte[] bytes, bool decode)
    {
        var input = new DataBlob { Length = bytes.Length, Data = Marshal.AllocHGlobal(bytes.Length) }; var output = new DataBlob();
        try
        {
            Marshal.Copy(bytes, 0, input.Data, bytes.Length);
            var ok = decode ? CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output) : CryptProtectData(ref input, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!ok) throw new CryptographicException(Marshal.GetLastWin32Error());
            var result = new byte[output.Length]; Marshal.Copy(output.Data, result, 0, result.Length); return result;
        }
        finally { Marshal.FreeHGlobal(input.Data); if (output.Data != IntPtr.Zero) LocalFree(output.Data); }
    }
}
