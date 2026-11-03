using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;
using WinRT;

namespace ApexCompanion.Capture.Windows;

/// WGC only. No hooks, input automation, process handles, game memory or desktop fallback.
internal sealed class WindowCapture(IImageOcr ocr, Func<ParsedFrame, Task> consume, Action<string> status) : IAsyncDisposable
{
    private readonly SemaphoreSlim processing = new(1, 1);
    private CancellationTokenSource? cancellation;
    private Direct3D11CaptureFramePool? pool;
    private GraphicsCaptureSession? session;
    private GraphicsCaptureItem? item;
    private IDirect3DDevice? device;
    private SizeInt32 size;
    private long sequence, lastSample;
    public int SamplingMilliseconds { get; set; } = 2000;
    public string SelectedWindow => item?.DisplayName ?? "None";
    public async Task<bool> StartAsync(IntPtr owner)
    {
        await StopAsync();
        if (!GraphicsCaptureSession.IsSupported()) throw new NotSupportedException("Windows capture is unavailable. No bypass or alternate capture will be attempted.");
        var picker = new GraphicsCapturePicker(); WinRT.Interop.InitializeWithWindow.Initialize(picker, owner);
        var selected = await picker.PickSingleItemAsync(); if (selected is null) return false;
        // The system picker also offers displays. Reject those and unrelated windows.
        // Match the returned title to a visible top-level Apex window, without opening its process.
        var hwnd = UniqueVisibleApexWindow(selected.DisplayName);
        if (hwnd == IntPtr.Zero) throw new InvalidOperationException("Select the unique Apex Legends application window, not a display or another application.");
        // Never capture the returned picker item: it could be a display with a colliding name.
        // The explicit picker action authorizes this exact, uniquely matched native WINDOW only.
        item = CreateForWindow(hwnd); size = item.Size;
        if (!SupportedSize(size)) throw new NotSupportedException("Unsupported selected-window dimensions. No capture pool was allocated.");
        device = CreateDevice(); cancellation = new();
        pool = Direct3D11CaptureFramePool.CreateFreeThreaded(device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, size);
        pool.FrameArrived += FrameArrived; item.Closed += ItemClosed;
        session = pool.CreateCaptureSession(item); if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 19041)) session.IsCursorCaptureEnabled = false;
        session.StartCapture(); status("Tracking selected window. Prototype layout; automatic acceptance disabled."); return true;
    }
    private void ItemClosed(GraphicsCaptureItem sender, object args) { cancellation?.Cancel(); status("Selected window closed. Tracking stopped; select the window again after restart."); }
    private async void FrameArrived(Direct3D11CaptureFramePool sender, object args)
    {
        var acquired = false;
        try
        {
            var token = cancellation?.Token ?? new CancellationToken(true);
            if (token.IsCancellationRequested) return;
            using var frame = sender.TryGetNextFrame(); if (frame is null) return;
            if (!await processing.WaitAsync(0)) return;
            acquired = true;
            token.ThrowIfCancellationRequested();
            var elapsed = Stopwatch.GetTimestamp();
            if (elapsed - lastSample < Stopwatch.Frequency * SamplingMilliseconds / 1000) return;
            lastSample = elapsed;
            if (frame.ContentSize.Width != size.Width || frame.ContentSize.Height != size.Height)
            {
                size = frame.ContentSize;
                if (!SupportedSize(size)) { cancellation?.Cancel(); status("Unsupported resized window dimensions. Tracking halted before capture-pool allocation."); return; }
                if (size.Width > 0 && size.Height > 0) sender.Recreate(device!, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, size);
                status("Window resized. Waiting for a complete new frame."); return;
            }
            if (size.Width < 640 || size.Height < 360 || size.Width > 7680 || size.Height > 4320) { status("Unsupported window size. No match inferred."); return; }
            using var bitmap = await SoftwareBitmap.CreateCopyFromSurfaceAsync(frame.Surface);
            token.ThrowIfCancellationRequested();
            var header = await ocr.ReadAsync(await CropPng(bitmap, LayoutConfig.Prototype.Header), token);
            IReadOnlyList<OcrLine> statistics = [];
            if (header.Any(x => x.Confidence >= .65 && x.Text.Contains("MATCH SUMMARY", StringComparison.OrdinalIgnoreCase)))
                statistics = await ocr.ReadAsync(await CropPng(bitmap, LayoutConfig.Prototype.Statistics), token);
            token.ThrowIfCancellationRequested();
            var reading = new OcrReading(Interlocked.Increment(ref sequence), DateTimeOffset.UtcNow.ToUnixTimeSeconds(), size.Width, size.Height, header, statistics);
            var classifier = new EnglishResultsClassifier(); var parsed = new EnglishFieldExtractor().Extract(reading, classifier.Classify(reading));
            await consume(parsed);
        }
        catch (OperationCanceledException) { }
        catch (Exception) { cancellation?.Cancel(); status("Capture, OCR or journal storage unavailable. Tracking halted; failed writes are not counted as saved matches. Check selected window, local OCR and journal disk/capacity before restarting."); }
        finally { if (acquired) processing.Release(); }
    }
    private static bool SupportedSize(SizeInt32 value) => value.Width is >= 640 and <= 7680 && value.Height is >= 360 and <= 4320;
    private static async Task<byte[]> CropPng(SoftwareBitmap bitmap, LayoutRegion region)
    {
        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetSoftwareBitmap(bitmap);
        encoder.BitmapTransform.Bounds = new BitmapBounds { X = (uint)(bitmap.PixelWidth * region.X), Y = (uint)(bitmap.PixelHeight * region.Y), Width = (uint)(bitmap.PixelWidth * region.Width), Height = (uint)(bitmap.PixelHeight * region.Height) };
        // CPU OCR resolution is capped independently of high-DPI/game rendering resolution.
        var width = Math.Min(1920, bitmap.PixelWidth); encoder.BitmapTransform.ScaledWidth = (uint)width;
        encoder.BitmapTransform.ScaledHeight = (uint)(bitmap.PixelHeight * (double)width / bitmap.PixelWidth);
        // Bounds are specified after scaling by the Windows imaging contract.
        encoder.BitmapTransform.Bounds = new BitmapBounds { X = (uint)(width * region.X), Y = (uint)(encoder.BitmapTransform.ScaledHeight * region.Y), Width = (uint)(width * region.Width), Height = (uint)(encoder.BitmapTransform.ScaledHeight * region.Height) };
        await encoder.FlushAsync();
        if (stream.Size > 16_777_216) throw new InvalidDataException("Transient image capacity exceeded.");
        stream.Seek(0); using var reader = new DataReader(stream); await reader.LoadAsync((uint)stream.Size);
        var bytes = new byte[(int)stream.Size]; reader.ReadBytes(bytes); return bytes;
    }
    public async Task StopAsync()
    {
        cancellation?.Cancel();
        if (pool is not null) pool.FrameArrived -= FrameArrived;
        if (item is not null) item.Closed -= ItemClosed;
        await processing.WaitAsync();
        try { session?.Dispose(); pool?.Dispose(); device?.Dispose(); session = null; pool = null; device = null; item = null; cancellation?.Dispose(); cancellation = null; }
        finally { processing.Release(); }
    }
    public async ValueTask DisposeAsync() { await StopAsync(); processing.Dispose(); }
    private static IntPtr UniqueVisibleApexWindow(string title)
    {
        if (!title.Contains("Apex Legends", StringComparison.OrdinalIgnoreCase)) return IntPtr.Zero;
        var found = new List<IntPtr>();
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            var text = new StringBuilder(512); GetWindowText(hwnd, text, text.Capacity);
            if (text.ToString() == title) found.Add(hwnd);
            return true;
        }, IntPtr.Zero);
        return found.Count == 1 ? found[0] : IntPtr.Zero;
    }
    [ComImport, Guid("3628e81b-3cac-4c60-8d94-289deec5e6e7"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IGraphicsCaptureItemInterop
    {
        [PreserveSig] int CreateForWindow(IntPtr window, in Guid iid, out IntPtr item);
        [PreserveSig] int CreateForMonitor(IntPtr monitor, in Guid iid, out IntPtr item);
    }
    [DllImport("combase.dll", CharSet = CharSet.Unicode)] private static extern int WindowsCreateString(string source, uint length, out IntPtr hstring);
    [DllImport("combase.dll")] private static extern int WindowsDeleteString(IntPtr hstring);
    [DllImport("combase.dll")] private static extern int RoGetActivationFactory(IntPtr name, in Guid iid, out IntPtr factory);
    private static GraphicsCaptureItem CreateForWindow(IntPtr window)
    {
        const string runtimeClass = "Windows.Graphics.Capture.GraphicsCaptureItem";
        IntPtr hstring = IntPtr.Zero, factory = IntPtr.Zero, result = IntPtr.Zero;
        object? factoryObject = null;
        try
        {
            Marshal.ThrowExceptionForHR(WindowsCreateString(runtimeClass, (uint)runtimeClass.Length, out hstring));
            var interopIid = typeof(IGraphicsCaptureItemInterop).GUID;
            Marshal.ThrowExceptionForHR(RoGetActivationFactory(hstring, in interopIid, out factory));
            factoryObject = Marshal.GetObjectForIUnknown(factory);
            var itemIid = new Guid("79c3f95b-31f7-4ec2-a464-632ef5d30760");
            Marshal.ThrowExceptionForHR(((IGraphicsCaptureItemInterop)factoryObject).CreateForWindow(window, in itemIid, out result));
            return MarshalInterface<GraphicsCaptureItem>.FromAbi(result);
        }
        finally
        {
            if (factoryObject is not null) Marshal.ReleaseComObject(factoryObject);
            if (result != IntPtr.Zero) Marshal.Release(result);
            if (factory != IntPtr.Zero) Marshal.Release(factory);
            if (hstring != IntPtr.Zero) WindowsDeleteString(hstring);
        }
    }
    private delegate bool EnumWindowProc(IntPtr window, IntPtr parameter);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool EnumWindows(EnumWindowProc callback, IntPtr parameter);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder text, int maximum);
    [DllImport("d3d11.dll")] private static extern int D3D11CreateDevice(IntPtr adapter, int driverType, IntPtr software, uint flags, IntPtr levels, uint levelCount, uint sdkVersion, out IntPtr device, out int featureLevel, out IntPtr context);
    [DllImport("d3d11.dll")] private static extern int CreateDirect3D11DeviceFromDXGIDevice(IntPtr dxgiDevice, out IntPtr graphicsDevice);
    private static IDirect3DDevice CreateDevice()
    {
        IntPtr native = IntPtr.Zero, context = IntPtr.Zero, dxgi = IntPtr.Zero, inspectable = IntPtr.Zero;
        try
        {
            Marshal.ThrowExceptionForHR(D3D11CreateDevice(IntPtr.Zero, 1, IntPtr.Zero, 0x20, IntPtr.Zero, 0, 7, out native, out _, out context));
            var iid = new Guid("54ec77fa-1377-44e6-8c32-88fd5f44c84c"); Marshal.ThrowExceptionForHR(Marshal.QueryInterface(native, in iid, out dxgi));
            Marshal.ThrowExceptionForHR(CreateDirect3D11DeviceFromDXGIDevice(dxgi, out inspectable));
            return MarshalInterface<IDirect3DDevice>.FromAbi(inspectable);
        }
        finally { foreach (var p in new[] { inspectable, dxgi, context, native }) if (p != IntPtr.Zero) Marshal.Release(p); }
    }
}
