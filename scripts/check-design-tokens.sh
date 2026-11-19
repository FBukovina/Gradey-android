#!/usr/bin/env bash
# Fails when UI code outside core-ui bypasses the design system: colour literals, raw text sizes,
# hand-typed corner radii, legacy components or raw Material controls. Run from the repository root.
set -euo pipefail
cd "$(dirname "$0")/.."

files=$(find app feature-* -path '*/src/main/*' -name '*.kt' | grep -v '/build/' | sort)
status=0

check() {
    local label="$1" pattern="$2"
    local hits
    hits=$(grep -nE "$pattern" $files 2>/dev/null || true)
    if [[ -n "$hits" ]]; then
        echo "✗ $label"
        echo "$hits" | sed 's/^/    /'
        status=1
    fi
}

check "colour literals (use GradeyColors / gradeyColors tokens)" 'Color\(0x'
check "raw text sizes (use GradeyType)" 'fontSize *='
check "hand-typed corner radii (use GradeyRadius or CircleShape)" 'RoundedCornerShape\([0-9]'
check "components removed in 0.5 (use the core-ui replacements)" '\b(GradeyCard|GradeyHero|GradeySectionCard|GradeyScreen|GradeyPageHeader|SettingsSurface|SettingsRow|SettingsHeader|SettingsCloseButton|LinkPill|NavigationRow|AbsenceRiskRing|GradeyIconButton|bleedHorizontally|MetadataRow|SectionHeaderRow|floatingNavigationContentPadding|floatingNavigationBarHeight)\b'
check "raw Material controls (use core-ui buttons, fields, dialogs and sheets)" '(^|[^A-Za-z.])(Button|TextButton|OutlinedButton|FilledTonalButton|IconButton|FilterChip|AssistChip|Card|ElevatedCard|OutlinedTextField|TextField|AlertDialog|ModalBottomSheet|Checkbox|LinearProgressIndicator|HorizontalDivider)\('
check "shadows and blur outside core-ui" '\.(shadow|blur)\('

if [[ $status -eq 0 ]]; then echo "✓ design tokens: no violations in $(echo "$files" | wc -l | tr -d ' ') files"; fi
exit $status
