package byd.cxkcxkckx.pluginupdate.updater;

public class UpdateInfo {
    private final String pluginName;
    private final String currentVersion;
    private final String newVersion;
    private final String updateSource;
    private String downloadUrl; // Can be set later
    // 补充：来源类型与在来源上的实际匹配项名称（用于 Spigot 泛搜索显示）
    private String sourceType;     // e.g. "spigot", "github", "modrinth", "hangar"
    private String resolvedName;   // 在来源上的实际名称（如 Spigot 搜索返回的资源名）
    private boolean fromSearch;    // 是否来自搜索结果（而不是明确指定来源）

    public UpdateInfo(String pluginName, String currentVersion, String newVersion, String updateSource) {
        this.pluginName = pluginName;
        this.currentVersion = currentVersion;
        this.newVersion = newVersion;
        this.updateSource = updateSource;
    }

    public String getPluginName() {
        return pluginName;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getNewVersion() {
        return newVersion;
    }

    public String getUpdateSource() {
        return updateSource;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getResolvedName() {
        return resolvedName;
    }

    public void setResolvedName(String resolvedName) {
        this.resolvedName = resolvedName;
    }

    public boolean isFromSearch() {
        return fromSearch;
    }

    public void setFromSearch(boolean fromSearch) {
        this.fromSearch = fromSearch;
    }
}
