package byd.cxkcxkckx.pluginupdate.updater;

import java.util.regex.Pattern;

public class VersionComparator {

    // v-prefix like v1.2.3
    private static final Pattern VERSION_PREFIX_PATTERN = Pattern.compile("^[vV]");
    // build metadata after '+' (ignore per SemVer)
    private static final Pattern BUILD_META_PATTERN = Pattern.compile("\\+.*$");
    private static final Pattern JAR_SUFFIX_PATTERN = Pattern.compile("\\.jar$");

    // Normalize but keep pre-release part (e.g., -beta), only strip build metadata
    public static String normalizeVersion(String version) {
        if (version == null) return "unknown";
        version = version.trim();
        if (version.equalsIgnoreCase("unknown") || version.equalsIgnoreCase("直链")) {
            return version;
        }
        // strip leading v
        version = VERSION_PREFIX_PATTERN.matcher(version).replaceAll("");
        // strip .jar suffix if provided
        version = JAR_SUFFIX_PATTERN.matcher(version).replaceAll("");
        // strip build metadata (+...)
        version = BUILD_META_PATTERN.matcher(version).replaceAll("");
        return version.trim();
    }

    public static boolean versionEquals(String v1, String v2) {
        if (v1 == null || v2 == null) return false;
        if (v1.equals(v2)) return true;
        return normalizeVersion(v1).equals(normalizeVersion(v2));
    }

    // SemVer compare with pre-release handling:
    // - Compare core major.minor.patch numerically
    // - If core equals, a version WITHOUT pre-release is greater than WITH pre-release
    // - If both have pre-release, compare dot-separated identifiers:
    //   numeric identifiers compare numerically; non-numeric lexicographically; shorter list is smaller if equal prefix
    public static boolean isNewerVersion(String newVersion, String currentVersion) {
        if ("unknown".equalsIgnoreCase(currentVersion) || "直链".equalsIgnoreCase(currentVersion)) return true;
        if ("unknown".equalsIgnoreCase(newVersion) || "直链".equalsIgnoreCase(newVersion)) return false;
        if (versionEquals(newVersion, currentVersion)) return false;

        String n = normalizeVersion(newVersion);
        String c = normalizeVersion(currentVersion);

        SemVer nv = SemVer.parse(n);
        SemVer cv = SemVer.parse(c);

        int coreCmp = nv.compareCore(cv);
        if (coreCmp != 0) {
            return coreCmp > 0;
        }

        // Core equal: handle pre-release precedence
        return nv.comparePreRelease(cv) > 0;
    }

    // Minimal SemVer helper
    static class SemVer {
        final int major, minor, patch;
        final String[] pre; // pre-release identifiers, null if none

        SemVer(int major, int minor, int patch, String[] pre) {
            this.major = major; this.minor = minor; this.patch = patch; this.pre = pre;
        }

        static SemVer parse(String v) {
            // split into core and pre-release by first '-'
            String core = v;
            String[] pre = null;
            int dash = v.indexOf('-');
            if (dash >= 0) {
                core = v.substring(0, dash);
                String preStr = v.substring(dash + 1);
                pre = preStr.isEmpty() ? new String[0] : preStr.split("\\.");
            }
            String[] nums = core.split("\\.");
            int major = nums.length > 0 ? parseIntSafe(nums[0]) : 0;
            int minor = nums.length > 1 ? parseIntSafe(nums[1]) : 0;
            int patch = nums.length > 2 ? parseIntSafe(nums[2]) : 0;
            return new SemVer(major, minor, patch, pre);
        }

        int compareCore(SemVer other) {
            if (this.major != other.major) return Integer.compare(this.major, other.major);
            if (this.minor != other.minor) return Integer.compare(this.minor, other.minor);
            return Integer.compare(this.patch, other.patch);
        }

        int comparePreRelease(SemVer other) {
            boolean thisHasPre = this.pre != null;
            boolean otherHasPre = other.pre != null;
            if (!thisHasPre && !otherHasPre) return 0;        // both stable
            if (!thisHasPre) return 1;                         // stable > pre
            if (!otherHasPre) return -1;                       // pre < stable
            // both have pre: compare identifiers
            int len = Math.max(this.pre.length, other.pre.length);
            for (int i = 0; i < len; i++) {
                String a = i < this.pre.length ? this.pre[i] : null;
                String b = i < other.pre.length ? other.pre[i] : null;
                if (a == null && b == null) return 0;
                if (a == null) return -1; // shorter pre is smaller
                if (b == null) return 1;
                Integer ai = tryParseInt(a);
                Integer bi = tryParseInt(b);
                if (ai != null && bi != null) {
                    int cmp = Integer.compare(ai, bi);
                    if (cmp != 0) return cmp;
                } else if (ai != null) {
                    // numeric < alphanumeric per SemVer
                    return -1;
                } else if (bi != null) {
                    return 1;
                } else {
                    int cmp = a.compareTo(b);
                    if (cmp != 0) return cmp;
                }
            }
            return 0;
        }

        static int parseIntSafe(String s) {
            try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
        }
        static Integer tryParseInt(String s) {
            try { return Integer.valueOf(s); } catch (Exception e) { return null; }
        }
    }
}
