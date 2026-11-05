import AppIntents
import SwiftUI
import WidgetKit

// Implemented — Not Verified. No Apple tooling has been run for this source.
enum RankedPlatform: String, AppEnum {
    case player = "PLAYER", pc = "PC", playStation = "PS4", xbox = "X1", nintendo = "SWITCH"
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Apex platform"
    static var caseDisplayRepresentations: [RankedPlatform: DisplayRepresentation] = [
        .player: "Linked player's platform", .pc: "PC", .playStation: "PlayStation", .xbox: "Xbox", .nintendo: "Nintendo Switch"
    ]
}
struct RankedConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Ranked information"
    static var description = IntentDescription("Uses the app's cached projection. No widget provider requests.")
    @Parameter(title: "Predator platform", default: .player) var platform: RankedPlatform
    @Parameter(title: "Show update details", default: true) var details: Bool
    @Parameter(title: "Design", default: .follow) var design: NativeWidgetStyle
    @Parameter(title: "Accent", default: .theme) var accent: NativeWidgetAccent
    @Parameter(title: "Density", default: .follow) var density: NativeWidgetDensity
    @Parameter(title: "Surface", default: .follow) var surface: NativeWidgetSurface
    var appearance: NativeWidgetAppearance { .resolve(style: design, accent: accent, density: density, surface: surface) }
}
private struct RankedProjection: Decodable {
    let schemaVersion: Int
    let generatedAtEpochSeconds: Int64
    let zone: String
    let entries: [RankedProjectedEntry]
}
struct RankedProjectedPredator: Decodable {
    let platform: String
    let state: String
    let rp: Int64
    let remainingRp: Int64?
    let masterPopulation: Int64?
    let observedAtEpochSeconds: Int64
    var isValid: Bool {
        ["PC", "PS4", "X1", "SWITCH"].contains(platform) && ["LIVE", "STALE"].contains(state)
        && rp >= 0 && rp <= 1_000_000_000
        && (remainingRp.map { $0 >= 0 && $0 <= 1_000_000_000 } ?? true)
        && (masterPopulation.map { $0 >= 0 } ?? true)
        && observedAtEpochSeconds >= 0 && observedAtEpochSeconds <= 4_102_444_800
    }
}
struct RankedProjectedEntry: Decodable {
    let effectiveAtEpochSeconds: Int64
    let validUntilEpochSeconds: Int64
    let state: String
    let source: String
    let observedAtEpochSeconds: Int64?
    let rankLabel: String
    let platform: String?
    let currentRp: Int64?
    let targetRp: Int64?
    let remainingRp: Int64?
    let rpPerDay: Int64?
    let fraction: Double
    let goalStatus: String?
    let predator: [RankedProjectedPredator]
    var isValid: Bool {
        ["LIVE", "STALE", "UNAVAILABLE", "MANUAL"].contains(state)
        && effectiveAtEpochSeconds >= 0 && validUntilEpochSeconds > effectiveAtEpochSeconds
        && validUntilEpochSeconds <= 4_102_444_800
        && (observedAtEpochSeconds.map { $0 >= 0 && $0 <= 4_102_444_800 } ?? true)
        && !source.isEmpty && source.count <= 160 && rankLabel.count <= 160
        && [currentRp, targetRp, remainingRp, rpPerDay].allSatisfy { $0.map { $0 >= 0 && $0 <= 1_000_000_000 } ?? true }
        && fraction.isFinite && fraction >= 0 && fraction <= 1
        && predator.count <= 4 && predator.allSatisfy(\.isValid)
        && Set(predator.map(\.platform)).count == predator.count
    }
}
struct RankedEntry: TimelineEntry {
    let date: Date
    let projected: RankedProjectedEntry?
    let platform: RankedPlatform
    let details: Bool
    var appearance = NativeWidgetAppearance()
    static func unavailable(_ date: Date, configuration: RankedConfiguration) -> RankedEntry {
        RankedEntry(date: date, projected: nil, platform: configuration.platform, details: configuration.details, appearance: configuration.appearance)
    }
}
private enum RankedSnapshotReader {
    static func entries(configuration: RankedConfiguration, now: Date) -> [RankedEntry] {
        let epoch = Int64(now.timeIntervalSince1970)
        guard let group = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
              let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let data = try? Data(contentsOf: directory.appendingPathComponent("ranked-widget-v1.json")),
              data.count <= 131_072,
              let projection = try? JSONDecoder().decode(RankedProjection.self, from: data),
              projection.schemaVersion == 1, projection.generatedAtEpochSeconds >= 0,
              projection.generatedAtEpochSeconds <= epoch, projection.entries.count <= 32,
              !projection.entries.isEmpty, projection.entries.allSatisfy(\.isValid),
              zip(projection.entries, projection.entries.dropFirst()).allSatisfy({ $0.0.effectiveAtEpochSeconds < $0.1.effectiveAtEpochSeconds }) else {
            return [.unavailable(now, configuration: configuration)]
        }
        let current = projection.entries.last { $0.effectiveAtEpochSeconds <= epoch }
        var result = [RankedEntry(date: now, projected: current, platform: configuration.platform, details: configuration.details, appearance: configuration.appearance)]
        result += projection.entries.filter { $0.effectiveAtEpochSeconds > epoch }.map {
            RankedEntry(date: Date(timeIntervalSince1970: Double($0.effectiveAtEpochSeconds)), projected: $0,
                        platform: configuration.platform, details: configuration.details, appearance: configuration.appearance)
        }
        return result
    }
}
struct RankedTimelineProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> RankedEntry { .unavailable(Date(), configuration: RankedConfiguration()) }
    func snapshot(for configuration: RankedConfiguration, in context: Context) async -> RankedEntry {
        RankedSnapshotReader.entries(configuration: configuration, now: Date())[0]
    }
    func timeline(for configuration: RankedConfiguration, in context: Context) async -> Timeline<RankedEntry> {
        let now = Date()
        return Timeline(entries: RankedSnapshotReader.entries(configuration: configuration, now: now), policy: .after(now.addingTimeInterval(1800)))
    }
}
enum RankedNativeKind { case progress, goal, predator }
struct RankedNativeView: View {
    @Environment(\.widgetFamily) private var family
    let entry: RankedEntry
    let kind: RankedNativeKind
    var body: some View {
        let projected = entry.projected
        let now = Int64(Date().timeIntervalSince1970)
        let selectedPlatform = entry.platform == .player ? (projected?.platform ?? "PC") : entry.platform.rawValue
        let threshold = projected?.predator.first { $0.platform == selectedPlatform }
        let observed = kind == .predator ? threshold?.observedAtEpochSeconds : projected?.observedAtEpochSeconds
        let fresh = observed.map { now >= $0 && now < $0 + (kind == .predator ? 1800 : 300) } ?? false
        let originalState = kind == .predator ? (threshold?.state ?? "UNAVAILABLE") : (projected?.state ?? "UNAVAILABLE")
        let state = originalState == "LIVE" && !fresh ? "STALE" : originalState
        let validMath = projected.map { now < $0.validUntilEpochSeconds } ?? false
        let appearance = entry.appearance
        VStack(alignment: .leading, spacing: family == .systemSmall ? 3 : appearance.spacing) {
            if family != .systemSmall || appearance.style == .editorial { NativeWidgetIdentity(appearance: appearance, title: "Ranked telemetry") }
            HStack {
                Text(kind == .predator ? "Predator cutoff" : kind == .goal ? "Ranked goal" : "Rank progress").font(.caption.weight(.semibold)).lineLimit(1)
                Spacer(minLength: 2)
                Text(state).font(.caption2.weight(.bold)).foregroundStyle(appearance.accent)
            }
            if kind == .predator {
                Text(threshold.map { "\($0.rp) RP" } ?? "Cutoff unavailable").font(.system(appearance.style == .editorial ? .title : .title2, design: appearance.fontDesign).weight(.bold)).minimumScaleFactor(0.7)
                Text(selectedPlatform).font(.caption)
                Text(threshold?.remainingRp.map { "\($0) RP to cutoff" } ?? "Player RP unavailable").font(.caption)
                if family != .systemSmall { Text("Dynamic cutoff · no position guaranteed").font(.caption2).foregroundStyle(appearance.muted) }
                if family == .systemLarge, let population = threshold?.masterPopulation { Text("\(population) Master + Predator players").font(.caption) }
            } else {
                if kind == .progress && family != .systemSmall { Text(projected?.rankLabel ?? "Rank unavailable").font(.headline).lineLimit(1) }
                Text(projected?.currentRp.map { "\($0) RP" } ?? "RP unavailable").font(.system(appearance.style == .editorial ? .title : .title2, design: appearance.fontDesign).weight(.bold)).minimumScaleFactor(0.7)
                if let target = projected?.targetRp {
                    Text("Target \(target) RP").font(.caption).lineLimit(1)
                    if family != .systemSmall { Text(projected?.remainingRp.map { "\($0) RP remaining" } ?? "Current RP required").font(.caption) }
                    ProgressView(value: projected?.fraction ?? 0).accessibilityLabel("Goal progress")
                    if validMath, let daily = projected?.rpPerDay { Text("\(daily) RP/day").font(.caption) }
                    else if let status = projected?.goalStatus { Text(status.lowercased().replacingOccurrences(of: "_", with: " ")).font(.caption2) }
                } else { Text("Set a goal in Apex Companion").font(.caption) }
            }
            Spacer(minLength: 0)
            if let observed = observed {
                if entry.details {
                    Text(Date(timeIntervalSince1970: Double(observed)), format: .dateTime.month().day().hour().minute())
                        .font(.caption2).foregroundStyle(appearance.muted)
                } else {
                    HStack(spacing: 4) { Text("Updated"); Text(Date(timeIntervalSince1970: Double(observed)), style: .relative) }.font(.caption2).foregroundStyle(appearance.muted)
                }
            } else { Text("No successful update").font(.caption2).foregroundStyle(appearance.muted) }
            Text(kind == .predator ? "Apex Legends Status" : (projected?.source ?? "Open app to configure")).font(.caption2).foregroundStyle(appearance.muted).lineLimit(2)
        }
        .foregroundStyle(appearance.ink)
        .tint(appearance.accent)
        .environment(\.colorScheme, appearance.dark ? .dark : .light)
        .containerBackground(for: .widget) { NativeWidgetBackground(appearance: appearance) }
        .widgetURL(URL(string: "apexcompanion://ranked"))
        .accessibilityElement(children: .combine)
    }
}
struct RankProgressNativeWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "ApexCompanionRankProgress", intent: RankedConfiguration.self, provider: RankedTimelineProvider()) { RankedNativeView(entry: $0, kind: .progress) }
            .configurationDisplayName("Rank progress").description("Current cached rank, RP and personal target.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
struct RankedGoalNativeWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "ApexCompanionRankedGoal", intent: RankedConfiguration.self, provider: RankedTimelineProvider()) { RankedNativeView(entry: $0, kind: .goal) }
            .configurationDisplayName("Ranked goal").description("Shared goal arithmetic with explicit source and freshness.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
struct PredatorNativeWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "ApexCompanionPredator", intent: RankedConfiguration.self, provider: RankedTimelineProvider()) { RankedNativeView(entry: $0, kind: .predator) }
            .configurationDisplayName("Predator cutoff").description("Dynamic platform-specific cutoff from the app cache.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
