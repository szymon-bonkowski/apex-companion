import AppIntents
import SwiftUI
import WidgetKit

enum RotationMode: String, AppEnum {
    case ranked = "ranked"
    case pubs = "battle_royale"
    case mixtape = "ltm"
    case control = "control"

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Playlist"
    static var caseDisplayRepresentations: [RotationMode: DisplayRepresentation] = [
        .ranked: "Ranked", .pubs: "Pubs", .mixtape: "Mixtape", .control: "Control"
    ]
}

struct MapConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Map rotation"
    static var description = IntentDescription("Choose the playlist for this widget. Missing source data stays unavailable.")
    @Parameter(title: "Playlist", default: .ranked) var mode: RotationMode
    @Parameter(title: "Design", default: .follow) var design: NativeWidgetStyle
    @Parameter(title: "Accent", default: .theme) var accent: NativeWidgetAccent
    @Parameter(title: "Density", default: .follow) var density: NativeWidgetDensity
    @Parameter(title: "Surface", default: .follow) var surface: NativeWidgetSurface
    var appearance: NativeWidgetAppearance { .resolve(style: design, accent: accent, density: density, surface: surface) }
}

// This wire projection is created by RotationWidgetCodec in shared Kotlin.
// Swift selects KMP-produced entries; it never extrapolates a map cycle or fetches data.
private struct WidgetProjection: Decodable {
    let schemaVersion: Int
    let generatedAtEpochSeconds: Int64
    let modes: [ProjectedMode]
}

private struct ProjectedMode: Decodable {
    let id: String
    let label: String
    let entries: [ProjectedEntry]
}

private struct ProjectedMap: Decodable {
    let mapId: String
    let mapName: String
    let startEpochSeconds: Int64
    let endEpochSeconds: Int64

    var hasValidBounds: Bool {
        startEpochSeconds >= 0 && startEpochSeconds < endEpochSeconds
            && endEpochSeconds <= 4_102_444_800 && !mapName.isEmpty && mapName.count <= 160
    }
}

private struct ProjectedEntry: Decodable {
    let effectiveAtEpochSeconds: Int64
    let state: String
    let source: String
    let lastSyncedAtEpochSeconds: Int64?
    let validUntilEpochSeconds: Int64?
    let current: ProjectedMap?
    let next: ProjectedMap?

    var isValid: Bool {
        ["LIVE", "STALE", "UNAVAILABLE", "DEMO"].contains(state)
            && effectiveAtEpochSeconds >= 0 && effectiveAtEpochSeconds <= 4_102_444_800
            && (validUntilEpochSeconds.map { $0 > effectiveAtEpochSeconds && $0 <= 4_102_444_800 } ?? true)
            && (lastSyncedAtEpochSeconds.map { $0 >= 0 && $0 <= 4_102_444_800 } ?? true)
            && !source.isEmpty && source.count <= 160
            && (current?.hasValidBounds ?? true) && (next?.hasValidBounds ?? true)
    }
}

struct MapEntry: TimelineEntry {
    let date: Date
    let modeLabel: String
    let state: String
    let currentName: String?
    let currentEnds: Date?
    let nextName: String?
    let nextStarts: Date?
    let lastSync: Date?
    let isDemo: Bool
    let validUntil: Date?
    var appearance = NativeWidgetAppearance()

    static func unavailable(_ date: Date, mode: String) -> MapEntry {
        MapEntry(date: date, modeLabel: mode, state: "UNAVAILABLE", currentName: nil,
                 currentEnds: nil, nextName: nil, nextStarts: nil, lastSync: nil, isDemo: false, validUntil: nil)
    }
}

private enum SnapshotReader {
    static func timeline(mode: RotationMode, now: Date) -> [MapEntry] {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
              let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let data = try? Data(contentsOf: directory.appendingPathComponent("map-widget-v1.json")),
              data.count <= 1_048_576,
              let projection = try? JSONDecoder().decode(WidgetProjection.self, from: data),
              projection.schemaVersion == 1,
              projection.generatedAtEpochSeconds >= 946_684_800,
              projection.generatedAtEpochSeconds <= Int64(now.timeIntervalSince1970) + 300,
              projection.modes.count <= 40,
              Set(projection.modes.map(\.id)).count == projection.modes.count,
              let selected = projection.modes.first(where: { $0.id == mode.rawValue }),
              !selected.label.isEmpty, selected.label.count <= 120,
              selected.entries.count <= 256,
              !selected.entries.isEmpty,
              selected.entries.allSatisfy(\.isValid) else {
            return [.unavailable(now, mode: mode.rawValue)]
        }
        let sorted = selected.entries
        // KMP writes ordered unique entries. Reject ambiguous order rather than repair source intent.
        guard zip(sorted, sorted.dropFirst()).allSatisfy({ pair in
            pair.0.effectiveAtEpochSeconds < pair.1.effectiveAtEpochSeconds
        }) else {
            return [.unavailable(now, mode: selected.label)]
        }
        let epoch = Int64(now.timeIntervalSince1970)
        let current = sorted.last(where: { $0.effectiveAtEpochSeconds <= epoch })
        var entries = [current.map { render($0, at: now, label: selected.label) }
            ?? .unavailable(now, mode: selected.label)]
        entries += sorted.filter { $0.effectiveAtEpochSeconds > epoch }.map {
            render($0, at: Date(timeIntervalSince1970: Double($0.effectiveAtEpochSeconds)), label: selected.label)
        }
        // Include a defensive end entry even if an interrupted writer omitted a terminal projection.
        if let last = sorted.last, let end = last.validUntilEpochSeconds,
           end > epoch, end > last.effectiveAtEpochSeconds {
            entries.append(.unavailable(Date(timeIntervalSince1970: Double(end)), mode: selected.label))
        }
        return entries
    }

    private static func render(_ projected: ProjectedEntry, at date: Date, label: String) -> MapEntry {
        let epoch = Int64(date.timeIntervalSince1970)
        let current = projected.current.flatMap {
            $0.startEpochSeconds <= epoch && epoch < $0.endEpochSeconds ? $0 : nil
        }
        let next = projected.next.flatMap { $0.startEpochSeconds >= epoch ? $0 : nil }
        let expired = projected.validUntilEpochSeconds.map { epoch >= $0 } ?? false
        let safeState: String
        if projected.state == "DEMO" {
            safeState = "DEMO"
        } else if current == nil {
            safeState = "UNAVAILABLE"
        } else if expired && projected.state == "LIVE" {
            safeState = "STALE"
        } else {
            safeState = projected.state
        }
        return MapEntry(
            date: date, modeLabel: label, state: safeState,
            currentName: current?.mapName,
            currentEnds: current.map { Date(timeIntervalSince1970: Double($0.endEpochSeconds)) },
            nextName: next?.mapName,
            nextStarts: next.map { Date(timeIntervalSince1970: Double($0.startEpochSeconds)) },
            lastSync: projected.lastSyncedAtEpochSeconds.map { Date(timeIntervalSince1970: Double($0)) },
            isDemo: projected.state == "DEMO",
            validUntil: projected.validUntilEpochSeconds.map { Date(timeIntervalSince1970: Double($0)) }
        )
    }
}

struct MapTimelineProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> MapEntry {
        .unavailable(Date(), mode: "Ranked")
    }

    func snapshot(for configuration: MapConfiguration, in context: Context) async -> MapEntry {
        var entry = SnapshotReader.timeline(mode: configuration.mode, now: Date())[0]
        entry.appearance = configuration.appearance
        return entry
    }

    func timeline(for configuration: MapConfiguration, in context: Context) async -> Timeline<MapEntry> {
        let now = Date()
        let entries = SnapshotReader.timeline(mode: configuration.mode, now: now).map { original in
            var entry = original; entry.appearance = configuration.appearance; return entry
        }
        return Timeline(entries: entries,
                        policy: .after(now.addingTimeInterval(15 * 60)))
    }
}

struct MapWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: MapEntry

    var body: some View {
        // A delayed native render may receive an old timeline entry. Only downgrade
        // it here; selecting future maps remains the common projection's job.
        let now = Date()
        let currentVisible = entry.currentName != nil && (entry.currentEnds.map { now < $0 } ?? false)
        let nextVisible = entry.nextStarts.map { now <= $0 } ?? false
        let displayState = !currentVisible ? (entry.isDemo ? "DEMO" : "UNAVAILABLE")
            : entry.state == "LIVE" && (entry.validUntil.map { now >= $0 } ?? false) ? "STALE" : entry.state
        let appearance = entry.appearance
        VStack(alignment: .leading, spacing: family == .systemSmall ? 3 : appearance.spacing) {
            if family != .systemSmall || appearance.style == .editorial { NativeWidgetIdentity(appearance: appearance, title: "Map rotation") }
            HStack {
                Text(entry.modeLabel).font(.caption.weight(.semibold))
                Spacer(minLength: 0)
                Text(displayState).font(.caption2.weight(.bold))
                    .foregroundStyle(appearance.accent)
            }
            Text(currentVisible ? (entry.currentName ?? "Map unavailable") : "Map unavailable")
                .font(.system(family == .systemSmall ? .headline : (appearance.style == .editorial ? .title : .title2), design: appearance.fontDesign).weight(.bold))
                .minimumScaleFactor(0.8).lineLimit(2)
            if currentVisible, let ends = entry.currentEnds {
                HStack(spacing: 4) {
                    Text("Ends")
                    Text(ends, style: .time).monospacedDigit()
                }.font(.caption)
            }
            if nextVisible, let name = entry.nextName, let starts = entry.nextStarts {
                if family == .systemSmall {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Next: \(name)").lineLimit(1)
                        Text(starts, style: .time).monospacedDigit()
                    }.font(.caption2)
                } else {
                    HStack(spacing: 4) {
                        Text("Next: \(name)").lineLimit(1)
                        Text(starts, style: .time).monospacedDigit()
                    }.font(.subheadline)
                }
            } else {
                Text("Next map not provided").font(.caption2).foregroundStyle(appearance.muted)
            }
            if let synced = entry.lastSync {
                HStack(spacing: 4) {
                    Text("Updated")
                    Text(synced, style: .time)
                }.font(.caption2).foregroundStyle(appearance.muted)
            } else { Text("No successful update").font(.caption2).foregroundStyle(appearance.muted) }
            Spacer(minLength: 0)
            Text(entry.isDemo ? "DEMO · Not live game data" : "Apex Legends Status · Cached schedule")
                .font(.caption2).foregroundStyle(appearance.muted).lineLimit(2)
            if family == .systemLarge && !entry.isDemo {
                Link("Data provided by Apex Legends Status", destination: URL(string: "https://apexlegendsstatus.com")!)
                    .font(.caption2)
            }
            if entry.currentName == nil && family != .systemSmall {
                Text("Open Apex Companion to synchronize. Refresh timing is controlled by iOS.")
                    .font(.caption2).foregroundStyle(appearance.muted)
            }
        }
        .foregroundStyle(appearance.ink)
        .tint(appearance.accent)
        .environment(\.colorScheme, appearance.dark ? .dark : .light)
        .containerBackground(for: .widget) { NativeWidgetBackground(appearance: appearance) }
        .widgetURL(URL(string: "apexcompanion://maps"))
        .accessibilityElement(children: .combine)
    }
}

struct ApexCompanionMapWidget: Widget {
    let kind = "ApexCompanionMap"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: MapConfiguration.self, provider: MapTimelineProvider()) {
            MapWidgetView(entry: $0)
        }
        .configurationDisplayName("Map rotation")
        .description("Current and next map from the app’s validated cache. No widget network requests.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// Implemented — Not Verified. Existing extension target includes this source folder.
@main
struct ApexCompanionWidgetBundle: WidgetBundle {
    var body: some Widget {
        ApexCompanionMapWidget()
        RankProgressNativeWidget()
        RankedGoalNativeWidget()
        PredatorNativeWidget()
    }
}
