import AppIntents
import SwiftUI
import WidgetKit

// Implemented — Not Verified. Pure native WidgetKit presentation; no networking or private-store access.
enum NativeWidgetStyle: String, AppEnum, Decodable {
    case follow = "FOLLOW", carbon = "CARBON", liquid = "LIQUID", editorial = "EDITORIAL", tactical = "TACTICAL"
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Design"
    static var caseDisplayRepresentations: [NativeWidgetStyle: DisplayRepresentation] = [
        .follow: "Follow app", .carbon: "Carbon", .liquid: "Liquid", .editorial: "Editorial", .tactical: "Tactical"
    ]
}
enum NativeWidgetAccent: String, AppEnum {
    case theme, turquoise, violet, amber, blue
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Accent"
    static var caseDisplayRepresentations: [NativeWidgetAccent: DisplayRepresentation] = [
        .theme: "Theme default", .turquoise: "Turquoise", .violet: "Violet", .amber: "Amber", .blue: "Blue"
    ]
    var hex: String? { switch self {
        case .theme: nil; case .turquoise: "6EE7D1"; case .violet: "D3B9FF"; case .amber: "FFAA75"; case .blue: "91C8FF"
    } }
}
enum NativeWidgetDensity: String, AppEnum, Decodable {
    case follow = "FOLLOW", compact = "COMPACT", comfortable = "COMFORTABLE", spacious = "SPACIOUS"
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Density"
    static var caseDisplayRepresentations: [NativeWidgetDensity: DisplayRepresentation] = [
        .follow: "Follow app", .compact: "Compact", .comfortable: "Comfortable", .spacious: "Spacious"
    ]
}
enum NativeWidgetSurface: String, AppEnum {
    case follow, dark, light, oled
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Surface"
    static var caseDisplayRepresentations: [NativeWidgetSurface: DisplayRepresentation] = [
        .follow: "Theme default", .dark: "Dark", .light: "Light", .oled: "OLED black"
    ]
}

private struct AppearanceProjection: Decodable {
    let schemaVersion: Int
    let style: NativeWidgetStyle
    let accentHex: String
    let dark: Bool
    let density: NativeWidgetDensity
    let trueBlack: Bool
    var valid: Bool { schemaVersion == 1 && style != .follow && density != .follow && accentHex.range(of: "^[0-9A-Fa-f]{6}$", options: .regularExpression) != nil }
}

struct NativeWidgetAppearance {
    var style: NativeWidgetStyle = .carbon
    var accentHex = "6EE7D1"
    var dark = true
    var density: NativeWidgetDensity = .comfortable
    var trueBlack = false

    static func resolve(style: NativeWidgetStyle, accent: NativeWidgetAccent, density: NativeWidgetDensity, surface: NativeWidgetSurface) -> NativeWidgetAppearance {
        var app = NativeWidgetAppearance()
        if let group = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
           let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
           let data = try? Data(contentsOf: directory.appendingPathComponent("widget-appearance-v1.json")), data.count <= 2048,
           let projection = try? JSONDecoder().decode(AppearanceProjection.self, from: data), projection.valid {
            app = NativeWidgetAppearance(style: projection.style, accentHex: projection.accentHex, dark: projection.dark, density: projection.density, trueBlack: projection.trueBlack)
        }
        var result = app
        if style != .follow { result.style = style; result.dark = style != .editorial }
        switch surface {
            case .dark, .oled: result.dark = true
            case .light: result.dark = false
            case .follow: break
        }
        result.trueBlack = surface == .oled || (surface == .follow && style == .follow && app.trueBlack && result.dark)
        if density != .follow { result.density = density }
        result.accentHex = accent.hex ?? (style == .follow ? app.accentHex : result.defaultAccent)
        return result
    }

    var defaultAccent: String { switch style {
        case .liquid: dark ? "D3B9FF" : "67429B"
        case .editorial: dark ? "E5C39B" : "795137"
        case .tactical: dark ? "FFAA75" : "9D3C07"
        default: dark ? "6EE7D1" : "006B5C"
    } }
    var backgroundRGB: UInt32 { if dark && trueBlack { return 0 }; return switch style {
        case .liquid: dark ? 0x20243D : 0xECE7F6
        case .editorial: dark ? 0x24211F : 0xF7F1E7
        case .tactical: dark ? 0x171E27 : 0xEBEFF2
        default: dark ? 0x0D1317 : 0xF1F6F4
    } }
    var surfaceRGB: UInt32 { switch style {
        case .liquid: dark ? 0x343957 : 0xFEFCFF
        case .editorial: dark ? 0x342E29 : 0xECE2D2
        case .tactical: dark ? 0x293441 : 0xDCE4EA
        default: dark ? 0x1B262D : 0xE3EEEA
    } }
    var ink: Color { Self.color(dark ? 0xF4F6F8 : 0x20252B) }
    var muted: Color { Self.color(dark ? 0xBCC7D1 : 0x48515B) }
    var background: Color { Self.color(backgroundRGB) }
    var surface: Color { Self.color(surfaceRGB) }
    var accent: Color {
        let requested = UInt32(accentHex, radix: 16) ?? 0
        let safe = Self.contrast(requested, backgroundRGB) >= 4.5 && Self.contrast(requested, surfaceRGB) >= 4.5
        return Self.color(safe ? requested : (UInt32(defaultAccent, radix: 16) ?? 0x6EE7D1))
    }
    var fontDesign: Font.Design { style == .editorial ? .serif : style == .tactical ? .monospaced : style == .liquid ? .rounded : .default }
    var spacing: CGFloat { density == .compact ? 4 : density == .spacious ? 10 : 7 }
    private static func color(_ rgb: UInt32) -> Color {
        Color(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255)
    }
    private static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        func luminance(_ rgb: UInt32) -> Double {
            func channel(_ shift: UInt32) -> Double {
                let value = Double((rgb >> shift) & 255) / 255
                return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        let first = luminance(a), second = luminance(b)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }
}

struct NativeWidgetBackground: View {
    let appearance: NativeWidgetAppearance
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    var body: some View {
        if appearance.style == .liquid && !reduceTransparency && contrast != .increased {
            LinearGradient(colors: [appearance.surface, appearance.background], startPoint: .topLeading, endPoint: .bottomTrailing)
        } else { appearance.background }
    }
}

struct NativeWidgetIdentity: View {
    let appearance: NativeWidgetAppearance
    let title: String
    var body: some View {
        switch appearance.style {
            case .editorial: Rectangle().fill(appearance.accent).frame(height: 1).accessibilityHidden(true)
            case .tactical: Text("// \(title.uppercased())").font(.system(.caption2, design: .monospaced).weight(.bold)).foregroundStyle(appearance.accent)
            case .liquid: Text(title).font(.caption2.weight(.semibold)).foregroundStyle(appearance.accent).padding(.horizontal, 8).padding(.vertical, 3).background(appearance.surface, in: Capsule())
            default: EmptyView()
        }
    }
}
