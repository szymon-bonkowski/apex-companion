namespace ApexCompanion.Capture;

/// A layout may enter this registry only after rights-cleared held-out real-screen validation.
/// Production is deliberately empty. No user configuration or UI switch can enable a prototype.
public sealed record ValidatedLayoutRule(int MinimumResultFrames, double MinimumConfidence, IReadOnlySet<string> RequiredFields);
public sealed record AcceptanceEvidence(string LayoutVersion, bool ObservedDistinctTransition, int ResultFrames,
    MatchMode Mode, int ModeReadings, double ModeConfidence, IReadOnlyDictionary<string, int> StableFieldReadings,
    IReadOnlyDictionary<string, double> Confidence, IReadOnlySet<string> Contradictions);
public sealed class CaptureAcceptancePolicy
{
    private readonly IReadOnlyDictionary<string, ValidatedLayoutRule> validatedLayouts;
    public static CaptureAcceptancePolicy Production { get; } = new(new Dictionary<string, ValidatedLayoutRule>());
    public CaptureAcceptancePolicy(IReadOnlyDictionary<string, ValidatedLayoutRule> validatedLayouts)
    {
        // Defensive copies prevent mutable caller data from widening an already configured policy.
        this.validatedLayouts = validatedLayouts.ToDictionary(x => x.Key, x => x.Value with { RequiredFields = new HashSet<string>(x.Value.RequiredFields) });
        foreach (var (version, rule) in this.validatedLayouts)
            if (!MatchRecord.ValidId(version) || rule.MinimumResultFrames < 3 || (!double.IsFinite(rule.MinimumConfidence) || rule.MinimumConfidence is < .95 or > 1) || rule.RequiredFields.Count == 0 || rule.RequiredFields.Any(x => x is not ("kills" or "assists" or "damage" or "placement" or "rpDelta")))
                throw new ArgumentException("Invalid validated-layout acceptance rule.");
    }
    public bool CanAccept(AcceptanceEvidence e)
    {
        if (!validatedLayouts.TryGetValue(e.LayoutVersion, out var rule) || !e.ObservedDistinctTransition || e.Contradictions.Count != 0 ||
            e.Mode == MatchMode.UNKNOWN || e.ResultFrames < rule.MinimumResultFrames || e.ModeReadings < rule.MinimumResultFrames || e.ModeConfidence < rule.MinimumConfidence) return false;
        return double.IsFinite(e.ModeConfidence) &&
            e.Confidence.All(field => double.IsFinite(field.Value) && field.Value >= rule.MinimumConfidence &&
                e.StableFieldReadings.GetValueOrDefault(field.Key) >= rule.MinimumResultFrames) &&
            rule.RequiredFields.All(field => e.StableFieldReadings.GetValueOrDefault(field) >= rule.MinimumResultFrames &&
            e.Confidence.GetValueOrDefault(field) >= rule.MinimumConfidence);
    }
}
