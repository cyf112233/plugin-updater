package byd.cxkcxkckx.pluginupdate.updater;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.bukkit.Bukkit;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import byd.cxkcxkckx.pluginupdate.pluginupdate;

public class PluginUpdater {

    private final pluginupdate plugin;
    private final File configFile;
    private Map<String, Object> config;
    private final Gson gson = new Gson();
    private final Yaml yaml = new Yaml();
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "PluginUpdater-Worker");
        t.setDaemon(true);
        return t;
    });

    public PluginUpdater(pluginupdate plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "plugin_updater_config.yaml");
        loadConfig();
    }

    @SuppressWarnings("unchecked")
    public void loadConfig() {
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                config = yaml.load(fis);
            } catch (IOException e) {
                plugin.getLogger().warning("无法加载 plugin_updater_config.yaml: " + e.getMessage());
                config = null;
            }
        } else {
            try {
                // 首次运行：将打包在资源中的默认模板复制到数据目录
                plugin.getDataFolder().mkdirs();
                // 若已存在则不覆盖
                plugin.saveResource("plugin_updater_config.yaml", false);
                plugin.getLogger().info("已在插件数据目录生成默认的 plugin_updater_config.yaml。");
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    config = yaml.load(fis);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("未在插件数据目录找到 plugin_updater_config.yaml，且默认模板复制失败: " + ex.getMessage());
                config = null;
            }
        }
        // 扫描已安装插件，补全配置并持久化
        ensureConfigHasInstalledPlugins();
        saveConfigToFile();
    }

    public CompletableFuture<List<UpdateInfo>> checkUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            if (config == null || !config.containsKey("plugins")) {
                throw new IllegalStateException("更新配置无效或未找到插件列表。");
            }

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> plugins = (Map<String, Map<String, Object>>) config.get("plugins");
            List<CompletableFuture<UpdateInfo>> futures = new ArrayList<>();

            for (Map.Entry<String, Map<String, Object>> entry : plugins.entrySet()) {
                String pluginName = entry.getKey();
                Map<String, Object> pluginConfig = entry.getValue();

                if (!toBoolean(pluginConfig.get("enabled"), true)) {
                    continue;
                }

                // 不再因 update_source 为空而跳过；checkSinglePlugin 会自行决定如何处理（包括从 JAR website 或名称搜索）
                futures.add(checkSinglePlugin(pluginName, pluginConfig));
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()))
                    .join();
        });
    }

    public CompletableFuture<List<UpdateInfo>> checkUpdatesWithProgress(java.util.function.BiConsumer<Integer, Integer> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            if (config == null || !config.containsKey("plugins")) {
                throw new IllegalStateException("更新配置无效或未找到插件列表。");
            }

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> plugins = (Map<String, Map<String, Object>>) config.get("plugins");
            List<CompletableFuture<UpdateInfo>> futures = new ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger totalCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger doneCounter = new java.util.concurrent.atomic.AtomicInteger(0);

            for (Map.Entry<String, Map<String, Object>> entry : plugins.entrySet()) {
                String pluginName = entry.getKey();
                Map<String, Object> pluginConfig = entry.getValue();

                if (!toBoolean(pluginConfig.get("enabled"), true)) {
                    continue;
                }

                totalCount.incrementAndGet();
                CompletableFuture<UpdateInfo> f = checkSinglePlugin(pluginName, pluginConfig)
                        .whenComplete((res, ex) -> {
                            int done = doneCounter.incrementAndGet();
                            if (progressCallback != null) {
                                try { progressCallback.accept(done, totalCount.get()); } catch (Throwable ignore) {}
                            }
                        });
                futures.add(f);
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()))
                    .join();
        });
    }

    private CompletableFuture<UpdateInfo> checkSinglePlugin(String pluginName, Map<String, Object> pluginConfig) {
        return CompletableFuture.supplyAsync(() -> {
            UpdateInfo result = null;
            try {
                String jarFilename = String.valueOf(pluginConfig.getOrDefault("jar_filename", pluginName + ".jar"));
                File pluginFile = new File(plugin.getDataFolder().getParentFile(), jarFilename);
                String currentVersion = pluginFile.exists() ? getPluginVersionFromJar(pluginFile) : "0.0.0";

                String updateSource = (String) pluginConfig.get("update_source");

                // 构建候选来源列表：配置 > JAR website > 自动识别
                List<String> candidateSources = new ArrayList<>();
                if (updateSource != null && !updateSource.isEmpty()) {
                    candidateSources.add(updateSource);
                }
                String website = getPluginWebsiteFromJar(pluginFile);
                if (website != null && !website.isEmpty()) {
                    candidateSources.add(website);
                }
                if (candidateSources.isEmpty()) {
                    // 仅支持 Modrinth，不再自动从其他平台探测
                }

                // 轮询各来源（顺序：Modrinth → Spiget → Hangar → GitHub(按名称与作者搜索)）
                boolean ghPre = toBoolean(pluginConfig.get("github_allow_pre_release"), false);
                boolean mrPre = toBoolean(pluginConfig.get("modrinth_allow_pre_release"), false);
                List<String> authors = getPluginAuthorsFromJar(pluginFile);
                String[] latestVersionInfo = tryLatestAcrossSources(
                        candidateSources,
                        pluginName,
                        authors,
                        jarFilename,
                        ghPre,
                        mrPre
                );

                if (latestVersionInfo != null) {
                    String newVersion = latestVersionInfo[0];
                    String downloadUrl = latestVersionInfo[1];
                    String usedSourceUrl = (updateSource != null && !updateSource.isEmpty())
                            ? updateSource
                            : (!candidateSources.isEmpty() ? candidateSources.get(0) : "");
                    // 若无显式来源URL（如通过搜索命中），则回退为下载链接，避免GUI出现“更新源”空白
                    if (usedSourceUrl == null || usedSourceUrl.isEmpty()) {
                        usedSourceUrl = downloadUrl;
                    }
                    String sourceType = latestVersionInfo.length >= 3 ? latestVersionInfo[2] : "modrinth";
                    String resolvedName = latestVersionInfo.length >= 4 ? latestVersionInfo[3] : null;

                    if (VersionComparator.isNewerVersion(newVersion, currentVersion)) {
                        UpdateInfo info = new UpdateInfo(pluginName, currentVersion, newVersion, usedSourceUrl);
                        info.setDownloadUrl(downloadUrl);
                        // 记录来源类型（用于GUI显示）
                        info.setSourceType(sourceType);
                        // 若来源解析到了实际名称（例如Spigot搜索），则补充显示
                        if (resolvedName != null && !resolvedName.isEmpty()) {
                            info.setResolvedName(resolvedName);
                        }
                        // 标记是否来自搜索：当配置未指定来源且 JAR 未提供 website，
                        // 并且我们为 usedSourceUrl 采用了下载链接作为回退时，视为搜索命中
                        // 但 GitHub 来源不标记为搜索（因为 GitHub 不进行搜索，只处理显式URL）
                        boolean fromSearchFlag =
                                (updateSource == null || updateSource.isEmpty()) &&
                                (website == null || website.isEmpty()) &&
                                (usedSourceUrl != null && usedSourceUrl.equals(downloadUrl)) &&
                                !"github".equalsIgnoreCase(sourceType);
                        info.setFromSearch(fromSearchFlag);

                        result = info;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("检查插件 " + pluginName + " 更新失败: " + e.getMessage());
            }
            return result;
        }, executor);
    }

    private String getPluginVersionFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) {
                entry = jar.getEntry("bungee.yml");
            }
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    Map<String, Object> yml = yaml.load(is);
                    return yml.getOrDefault("version", "unknown").toString();
                }
            }
        } catch (IOException e) {
            // Ignore
        }
        return "unknown";
    }
    
    // 新增：从 JAR 的 plugin.yml 或 bungee.yml 提取插件网站
    private String getPluginWebsiteFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) {
                entry = jar.getEntry("bungee.yml");
            }
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    Map<String, Object> yml = yaml.load(is);
                    Object website = yml.get("website");
                    return website == null ? null : website.toString();
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }
    
    // 新增：从 JAR 的 plugin.yml 或 bungee.yml 提取插件名称
    private String getPluginNameFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) {
                entry = jar.getEntry("bungee.yml");
            }
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    Map<String, Object> yml = yaml.load(is);
                    Object name = yml.get("name");
                    return name == null ? null : name.toString();
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    // 新增：从 JAR 读取作者列表（authors/author）
    @SuppressWarnings("unchecked")
    private List<String> getPluginAuthorsFromJar(File jarFile) {
        List<String> authors = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) entry = jar.getEntry("bungee.yml");
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    Map<String, Object> yml = yaml.load(is);
                    Object authorsObj = yml.get("authors");
                    Object authorObj = yml.get("author");
                    if (authorsObj instanceof List) {
                        for (Object a : (List<?>) authorsObj) {
                            if (a != null) {
                                String s = String.valueOf(a).trim();
                                if (!s.isEmpty()) authors.add(s);
                            }
                        }
                    }
                    if (authorObj instanceof String) {
                        String s = ((String) authorObj).trim();
                        if (!s.isEmpty() && !authors.contains(s)) authors.add(s);
                    }
                }
            }
        } catch (IOException ignored) {}
        return authors;
    }

    // 新增：Spiget 搜索资源ID（按名称），返回 [resourceId, resourceName, similarity]
    private String[] spigetSearchResourceIdByName(String query) throws IOException {
        String encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8.name());
        String api = "https://api.spiget.org/v2/search/resources/" + encoded + "?size=10";
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from " + api);
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            Type listType = new TypeToken<ArrayList<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> hits = gson.fromJson(reader, listType);
            if (hits == null || hits.isEmpty()) return null;
            
            // 智能匹配选择最佳结果
            String[] candidates = new String[hits.size()];
            for (int i = 0; i < hits.size(); i++) {
                candidates[i] = String.valueOf(hits.get(i).getOrDefault("name", ""));
            }
            
            int bestIdx = StringSimilarity.bestMatch(query, candidates);
            if (bestIdx == -1) bestIdx = 0;
            
            Map<String, Object> best = hits.get(bestIdx);
            String resourceName = String.valueOf(best.getOrDefault("name", ""));
            double similarity = StringSimilarity.similarity(query, resourceName);
            
            Object idObj = best.get("id");
            String resourceId = null;
            if (idObj instanceof Number) {
                resourceId = String.valueOf(((Number) idObj).intValue());
            } else if (idObj != null) {
                try { resourceId = String.valueOf(Integer.parseInt(String.valueOf(idObj))); } catch (Exception ignore) {}
            }
            
            if (resourceId == null) return null;
            return new String[]{resourceId, resourceName, String.valueOf((int)(similarity * 100))};
        }
    }


    // 新增：确保配置包含所有已安装插件的占位条目
    @SuppressWarnings("unchecked")
    private void ensureConfigHasInstalledPlugins() {
        if (config == null) {
            config = new java.util.LinkedHashMap<>();
        }
        Map<String, Map<String, Object>> pluginsMap = (Map<String, Map<String, Object>>) config.get("plugins");
        if (pluginsMap == null) {
            pluginsMap = new java.util.LinkedHashMap<>();
            config.put("plugins", pluginsMap);
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        File[] jars = pluginsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (jars == null) return;

        String selfName = plugin.getName();
        for (File jar : jars) {
            String detectedName = getPluginNameFromJar(jar);
            if (detectedName == null || detectedName.isEmpty()) {
                String n = jar.getName();
                detectedName = n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n;
            }
            if (selfName != null && detectedName.equalsIgnoreCase(selfName)) {
                continue; // 跳过本插件
            }

            Map<String, Object> entry = pluginsMap.get(detectedName);
            if (entry == null) {
                entry = new java.util.LinkedHashMap<>();
                entry.put("enabled", true);
                entry.put("jar_filename", jar.getName());
                entry.put("update_source", "");
                entry.put("github_allow_pre_release", false);
                entry.put("modrinth_allow_pre_release", false);
                pluginsMap.put(detectedName, entry);
            } else {
                entry.putIfAbsent("enabled", true);
                entry.putIfAbsent("jar_filename", jar.getName());
                entry.putIfAbsent("update_source", "");
                entry.putIfAbsent("github_allow_pre_release", false);
                entry.putIfAbsent("modrinth_allow_pre_release", false);
            }
        }
    }


    // 来源尝试顺序调整：Modrinth → Spiget → Hangar → GitHub(按名称与作者搜索)，移除DD直链
    private String[] tryLatestAcrossSources(List<String> candidateSources,
                                            String pluginName,
                                            List<String> pluginAuthors,
                                            String jarFilename,
                                            boolean githubAllowPreRelease,
                                            boolean modrinthAllowPreRelease) {
        // 统一基础名称（用于搜索）
        String baseName = (pluginName != null && !pluginName.isEmpty())
                ? pluginName
                : (jarFilename != null ? (jarFilename.endsWith(".jar") ? jarFilename.substring(0, jarFilename.length() - 4) : jarFilename) : "");

        // 从候选来源中提取各平台的优先URL/slug
        String modrinthCandidate = null;
        String spigotCandidate = null;
        String githubCandidate = null;
        for (String src : candidateSources) {
            if (src == null || src.isEmpty()) continue;
            String u = src.toLowerCase();
            if (modrinthCandidate == null && (u.contains("modrinth.com") || !src.startsWith("http"))) {
                modrinthCandidate = src;
                continue;
            }
            if (spigotCandidate == null && (u.contains("spigotmc.org") || u.contains("spigot.org"))) {
                spigotCandidate = src;
                continue;
            }
            if (githubCandidate == null && (u.contains("github.com") || u.contains("githubusercontent.com") || u.contains("raw.githubusercontent.com") || u.contains("dgithub.xyz") || u.contains("gh.llkk.cc"))) {
                githubCandidate = src;
            }
        }

        // 1) Modrinth：先用显式URL/slug，否则按名称搜索
        try {
            if (modrinthCandidate != null) {
                String[] r = getModrinthLatestVersion(modrinthCandidate, modrinthAllowPreRelease);
                if (r != null) return new String[]{r[0], r[1], "modrinth"};
            }
            if (baseName != null && !baseName.isEmpty()) {
                String[] r = modrinthSearchLatestVersion(baseName, modrinthAllowPreRelease);
                if (r != null && r.length >= 4) {
                    return new String[]{r[0], r[1], "modrinth", r[3]};
                } else if (r != null) {
                    return new String[]{r[0], r[1], "modrinth"};
                }
            }
        } catch (Exception ignored) {}

        // 2) Spiget（Spigot）：优先显式URL，否则按名称搜索资源ID
        try {
            if (spigotCandidate != null) {
                String[] r = getSpigotLatestVersion(spigotCandidate);
                if (r != null) return new String[]{r[0], r[1], "spigot"};
            } else if (baseName != null && !baseName.isEmpty()) {
                String[] searchResult = spigetSearchResourceIdByName(baseName);
                if (searchResult != null && searchResult.length >= 3) {
                    String resourceId = searchResult[0];
                    String resourceName = searchResult[1];
                    String similarity = searchResult[2];
                    String url = "https://www.spigotmc.org/resources/" + resourceId;
                    String[] r = getSpigotLatestVersion(url);
                    if (r != null) {
                        String nameWithScore = resourceName + " §7(匹配度:" + similarity + "%)";
                        return new String[]{r[0], r[1], "spigot", nameWithScore};
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3) GitHub：仅当显式URL可用时尝试（不进行搜索）
        try {
            if (githubCandidate != null) {
                String[] r = getGithubLatestVersion(githubCandidate, githubAllowPreRelease);
                if (r != null) return new String[]{r[0], r[1], "github"};
            }
        } catch (Exception ignored) {}

        // 若四个平台都未命中则跳过（与“没有最新版”处理一致）
        return null;
    }

    // Modrinth：获取最新版本（支持预发布）
    // 行为调整：
    // 1) 若可能，从服务器获取 Minecraft 版本（如 1.20.6），优先按加载器尝试：spigot -> paper
    // 2) 请求 /v2/project/{slug}/version?loaders=["..."]&game_versions=["..."] 返回 JSON 列表
    // 3) 在返回列表中选取 version_number “最大”的那个（用 VersionComparator 判断），
    //    再在其 files 中选 primary==true，否则取第一个，返回其 url
    // 4) 若按加载器+版本均无结果，回退到不带过滤参数的旧逻辑
    private String[] getModrinthLatestVersion(String urlOrSlug, boolean allowPreRelease) throws IOException {
        String slug = urlOrSlug;
        if (slug == null) return null;
        // 允许传入完整 URL
        if (slug.contains("modrinth.com")) {
            // 形如 https://modrinth.com/plugin/<slug> 或 https://modrinth.com/project/<slug>
            int idxPlugin = slug.indexOf("/plugin/");
            int idxProject = slug.indexOf("/project/");
            int idx = -1;
            int baseLen = 0;
            if (idxPlugin != -1) {
                idx = idxPlugin;
                baseLen = "/plugin/".length();
            } else if (idxProject != -1) {
                idx = idxProject;
                baseLen = "/project/".length();
            }
            if (idx != -1) {
                slug = slug.substring(idx + baseLen);
                int slash = slug.indexOf('/');
                if (slash != -1) slug = slug.substring(0, slash);
            }
        }

        // 从 Bukkit 获取服务器 Minecraft 版本（如 "1.20.6"），不可用时置空
        String mcVersion = null;
        try {
            // 兼容 spigot-api：使用 Bukkit.getBukkitVersion()，形如 "1.20.6-R0.1-SNAPSHOT"
            String bv = Bukkit.getBukkitVersion();
            if (bv != null) {
                int dash = bv.indexOf('-');
                mcVersion = (dash > 0 ? bv.substring(0, dash) : bv).trim();
            }
        } catch (Throwable ignored) {}

        // 优先 spigot → bukkit → paper（强制服务端加载器，避免匹配到客户端整合包）
        String[] loaderPrefs = new String[] { "spigot", "bukkit", "paper" };
        for (String loader : loaderPrefs) {
            String loadersParam = java.net.URLEncoder.encode("[\"" + loader + "\"]", java.nio.charset.StandardCharsets.UTF_8.name());
            String api = "https://api.modrinth.com/v2/project/" + slug + "/version?loaders=" + loadersParam;
            if (mcVersion != null && !mcVersion.isEmpty()) {
                String gvParam = java.net.URLEncoder.encode("[\"" + mcVersion + "\"]", java.nio.charset.StandardCharsets.UTF_8.name());
                api += "&game_versions=" + gvParam;
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int __code = conn.getResponseCode();
            if (__code < 200 || __code >= 300) throw new IOException("HTTP " + __code + " from " + api);
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                Type listType = new TypeToken<ArrayList<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> versions = gson.fromJson(reader, listType);
                if (versions != null && !versions.isEmpty()) {
                    // 过滤版本类型（不允许预发布时仅取 release）
                    List<Map<String, Object>> candidates = versions.stream().filter(v -> {
                        String vt = String.valueOf(v.get("version_type"));
                        return allowPreRelease || "release".equalsIgnoreCase(vt);
                    }).collect(Collectors.toList());
                    if (candidates.isEmpty()) continue;

                    // 选取 version_number 最大的
                    Map<String, Object> best = null;
                    String bestVer = null;
                    for (Map<String, Object> v : candidates) {
                        String ver = String.valueOf(v.getOrDefault("version_number", v.getOrDefault("name", "unknown")));
                        if (best == null) {
                            best = v; bestVer = ver;
                        } else {
                            if (VersionComparator.isNewerVersion(ver, bestVer)) {
                                best = v; bestVer = ver;
                            }
                        }
                    }
                    if (best != null) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> files = (List<Map<String, Object>>) best.get("files");
                        // 只取 .jar：优先 primary 的 .jar，其次第一个 .jar；若没有 .jar 则放弃该版本
                        String downloadUrl = "";
                        if (files != null && !files.isEmpty()) {
                            Map<String, Object> primaryJar = null;
                            Map<String, Object> firstJar = null;
                            for (Map<String, Object> f : files) {
                                String fname = String.valueOf(f.getOrDefault("filename", ""));
                                String furl = String.valueOf(f.getOrDefault("url", ""));
                                boolean isJar = (fname != null && fname.toLowerCase().endsWith(".jar"))
                                        || (furl != null && furl.toLowerCase().endsWith(".jar"));
                                if (!isJar) continue;
                                if (firstJar == null) firstJar = f;
                                Object primaryFlag = f.get("primary");
                                if (primaryFlag instanceof Boolean && ((Boolean) primaryFlag)) {
                                    primaryJar = f; break;
                                }
                            }
                            Map<String, Object> chosen = primaryJar != null ? primaryJar : firstJar;
                            downloadUrl = chosen == null ? "" : String.valueOf(chosen.getOrDefault("url", ""));
                        }
                        if (downloadUrl == null || downloadUrl.isEmpty()) return null;
                        return new String[]{bestVer, downloadUrl};
                    }
                }
            } catch (IOException ignored) {
                // 若该 loader 请求失败，尝试下一个 loader
            }
        }

        // 回退：不加过滤参数，取 release 首个，否则第一个（旧逻辑）
        String api = "https://api.modrinth.com/v2/project/" + slug + "/version";
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        int __code = conn.getResponseCode();
        if (__code < 200 || __code >= 300) throw new IOException("HTTP " + __code + " from " + api);
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            Type listType = new TypeToken<ArrayList<Map<String, Object>>>(){}.getType();
            List<Map<String, Object>> versions = gson.fromJson(reader, listType);
            if (versions == null || versions.isEmpty()) return null;

            // 仍然基于 version_number 选择最大（更稳妥），并强制服务端加载器筛选（spigot → bukkit → paper）
            List<Map<String, Object>> spigotList = new ArrayList<>();
            List<Map<String, Object>> bukkitList = new ArrayList<>();
            List<Map<String, Object>> paperList = new ArrayList<>();
            for (Map<String, Object> v : versions) {
                // 版本类型过滤（不允许预发布时仅取 release）
                String vt = String.valueOf(v.get("version_type"));
                if (!(allowPreRelease || "release".equalsIgnoreCase(vt))) continue;

                // 加载器过滤：只接受 spigot/bukkit/paper
                boolean hasSpigot = false, hasBukkit = false, hasPaper = false;
                Object loadersObj = v.get("loaders");
                if (loadersObj instanceof List) {
                    for (Object lo : (List<?>) loadersObj) {
                        String ls = String.valueOf(lo).toLowerCase();
                        if (ls.equals("spigot")) hasSpigot = true;
                        else if (ls.equals("bukkit")) hasBukkit = true;
                        else if (ls.equals("paper")) hasPaper = true;
                    }
                }
                if (!(hasSpigot || hasBukkit || hasPaper)) continue;

                // 游戏版本过滤：若能获取到服务器版本，则要求版本声明包含它
                if (mcVersion != null && !mcVersion.isEmpty()) {
                    Object gvObj = v.get("game_versions");
                    boolean matchGv = false;
                    if (gvObj instanceof List) {
                        for (Object gvo : (List<?>) gvObj) {
                            if (mcVersion.equalsIgnoreCase(String.valueOf(gvo))) { matchGv = true; break; }
                        }
                    }
                    if (!matchGv) continue;
                }

                if (hasSpigot) spigotList.add(v);
                else if (hasBukkit) bukkitList.add(v);
                else if (hasPaper) paperList.add(v);
            }

            // 依次选择候选列表：spigot > bukkit > paper
            List<Map<String, Object>> candidates = !spigotList.isEmpty() ? spigotList
                    : (!bukkitList.isEmpty() ? bukkitList : paperList);
            if (candidates == null || candidates.isEmpty()) return null;

            Map<String, Object> best = null;
            String bestVer = null;
            for (Map<String, Object> v : candidates) {
                String ver = String.valueOf(v.getOrDefault("version_number", v.getOrDefault("name", "unknown")));
                if (best == null) {
                    best = v; bestVer = ver;
                } else if (VersionComparator.isNewerVersion(ver, bestVer)) {
                    best = v; bestVer = ver;
                }
            }
            if (best == null) return null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) best.get("files");
            // 只取 .jar：优先 primary 的 .jar，其次第一个 .jar；若没有 .jar 则放弃该版本
            String downloadUrl = "";
            if (files != null && !files.isEmpty()) {
                Map<String, Object> primaryJar = null;
                Map<String, Object> firstJar = null;
                for (Map<String, Object> f : files) {
                    String fname = String.valueOf(f.getOrDefault("filename", ""));
                    String furl = String.valueOf(f.getOrDefault("url", ""));
                    boolean isJar = (fname != null && fname.toLowerCase().endsWith(".jar"))
                            || (furl != null && furl.toLowerCase().endsWith(".jar"));
                    if (!isJar) continue;
                    if (firstJar == null) firstJar = f;
                    Object primaryFlag = f.get("primary");
                    if (primaryFlag instanceof Boolean && ((Boolean) primaryFlag)) {
                        primaryJar = f; break;
                    }
                }
                Map<String, Object> chosen = primaryJar != null ? primaryJar : firstJar;
                downloadUrl = chosen == null ? "" : String.valueOf(chosen.getOrDefault("url", ""));
            }
            if (downloadUrl == null || downloadUrl.isEmpty()) return null;
            return new String[]{bestVer, downloadUrl};
        }
    }

    // Modrinth：搜索并返回最新版本（返回格式：[version, downloadUrl, sourceType, projectTitle]）
    private String[] modrinthSearchLatestVersion(String query, boolean allowPreRelease) throws IOException {
        if (query == null || query.isEmpty()) return null;
        String encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8.name());
        String api = "https://api.modrinth.com/v2/search?query=" + encoded + "&limit=10";
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        int __code = conn.getResponseCode();
        if (__code < 200 || __code >= 300) throw new IOException("HTTP " + __code + " from " + api);

        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) data.get("hits");
            if (hits == null || hits.isEmpty()) return null;
            
            // 过滤 plugin 类型
            List<Map<String, Object>> pluginHits = new ArrayList<>();
            for (Map<String, Object> h : hits) {
                String pt = String.valueOf(h.getOrDefault("project_type", ""));
                if ("plugin".equalsIgnoreCase(pt)) {
                    pluginHits.add(h);
                }
            }
            if (pluginHits.isEmpty()) pluginHits = hits;
            
            // 智能匹配选择最佳结果
            String[] candidates = new String[pluginHits.size()];
            for (int i = 0; i < pluginHits.size(); i++) {
                candidates[i] = String.valueOf(pluginHits.get(i).getOrDefault("title", ""));
            }
            
            int bestIdx = StringSimilarity.bestMatch(query, candidates);
            if (bestIdx == -1) bestIdx = 0;
            
            Map<String, Object> best = pluginHits.get(bestIdx);
            String slug = String.valueOf(best.getOrDefault("slug", best.get("project_id")));
            String projectTitle = String.valueOf(best.getOrDefault("title", slug));
            double similarity = StringSimilarity.similarity(query, projectTitle);
            
            if (slug == null || slug.isEmpty()) return null;
            String[] result = getModrinthLatestVersion(slug, allowPreRelease);
            if (result != null && projectTitle != null && !projectTitle.isEmpty()) {
                String titleWithScore = projectTitle + " §7(匹配度:" + (int)(similarity * 100) + "%)";
                return new String[]{result[0], result[1], "modrinth", titleWithScore};
            }
            return result;
        }
    }
 
    // GitHub 代理应用（可选，从配置 global.github_proxy 读取）
    private String applyGithubProxy(String url) {
        if (url == null) return null;
        String proxy = getGlobalString("github_proxy", "");
        if (proxy.isEmpty()) return url;

        String u = url;
        if (u.contains(proxy)) return u; // 已应用过
        if (proxy.endsWith("/")) {
            return proxy + u;
        }
        // 域名替换模式
        String[] domains = new String[]{"github.com", "githubusercontent.com", "raw.githubusercontent.com"};
        for (String d : domains) {
            if (u.contains(d)) {
                return u.replace(d, proxy);
            }
        }
        return u;
    }

    // 读取全局字符串配置（若缺失返回默认值）
    @SuppressWarnings("unchecked")
    private String getGlobalString(String key, String def) {
        try {
            Map<String, Object> g = (Map<String, Object>) (config == null ? null : config.get("global"));
            Object v = g == null ? null : g.get(key);
            return v == null ? def : String.valueOf(v);
        } catch (Exception e) {
            return def;
        }
    }

    // 统一的布尔解析：支持 Boolean、数字、字符串（true/false/yes/no/1/0）
    private boolean toBoolean(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("n") || s.equals("off") || s.equals("0")) return false;
        return def;
    }

    // HEAD 检查 URL 是否存在
    private boolean urlExists(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "pluginupdater/1.0.0 (DarkForest/2.0; LocationBroadcasted)");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (IOException e) {
            return false;
        }
    }

    // GitHub：获取最新版本与下载链接（支持允许预发布）
    private String[] getGithubLatestVersion(String url, boolean allowPreRelease) throws IOException {
        // 解析 owner/repo
        URL u = new URL(url);
        String path = u.getPath(); // /owner/repo/...
        String[] parts = path.split("/");
        String owner = null, repo = null;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (owner == null) { owner = parts[i]; continue; }
            if (repo == null) { repo = parts[i]; break; }
        }
        if (owner == null || repo == null) throw new IOException("无效的GitHub仓库URL: " + url);

        String apiBase = "https://api.github.com";
        String releasesApi = apiBase + "/repos/" + owner + "/" + repo + "/releases";
        String latestApi = releasesApi + "/latest";
        String token = getGlobalString("github_token", "");

        java.util.function.Function<String, HttpURLConnection> open = (String api) -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
                conn.setRequestProperty("Accept", "application/json");
                if (!token.isEmpty()) conn.setRequestProperty("Authorization", "token " + token);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                return conn;
            } catch (IOException e) { throw new RuntimeException(e); }
        };

        Map<String, Object> release = null;
        if (allowPreRelease) {
            HttpURLConnection conn = open.apply(releasesApi);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from " + releasesApi);
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                Type listType = new TypeToken<ArrayList<Map<String, Object>>>() {}.getType();
                List<Map<String, Object>> releases = gson.fromJson(reader, listType);
                if (releases != null && !releases.isEmpty()) {
                    release = releases.get(0);
                }
            }
        } else {
            HttpURLConnection conn = open.apply(latestApi);
            int code = conn.getResponseCode();
            if (code == 404) {
                // 回退到 tags
                String tagsApi = apiBase + "/repos/" + owner + "/" + repo + "/tags";
                HttpURLConnection tconn = open.apply(tagsApi);
                int tcode = tconn.getResponseCode();
                if (tcode < 200 || tcode >= 300) throw new IOException("HTTP " + tcode + " from " + tagsApi);
                try (InputStreamReader reader = new InputStreamReader(tconn.getInputStream())) {
                    Type listType = new TypeToken<ArrayList<Map<String, Object>>>() {}.getType();
                    List<Map<String, Object>> tags = gson.fromJson(reader, listType);
                    if (tags == null || tags.isEmpty()) return null;
                    String tag = String.valueOf(tags.get(0).getOrDefault("name", "unknown"));
                    // 构造常见命名的JAR直链并测试
                    String[] candidates = new String[] {
                            "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/" + repo + "-" + tag + ".jar",
                            "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/" + repo + ".jar",
                            "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/" + owner + "-" + repo + "-" + tag + ".jar",
                            "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/" + repo + "-" + tag.replace("v", "") + ".jar"
                    };
                    for (String c : candidates) {
                        String proxied = applyGithubProxy(c);
                        if (urlExists(proxied)) {
                            return new String[]{tag, proxied};
                        }
                    }
                    // 仅接受 .jar 资产；不再回退到源码ZIP
                    return null;
                }
            } else {
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from " + latestApi);
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                    release = gson.fromJson(reader, Map.class);
                }
            }
        }

        if (release == null) return null;

        // 查找 JAR 资产
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assets = (List<Map<String, Object>>) release.get("assets");
        String tagName = String.valueOf(release.getOrDefault("tag_name", release.getOrDefault("name", "unknown")));
        if (assets != null) {
            for (Map<String, Object> a : assets) {
                String name = String.valueOf(a.getOrDefault("name", ""));
                String dl = String.valueOf(a.getOrDefault("browser_download_url", ""));
                if (name.endsWith(".jar") && !dl.isEmpty()) {
                    String proxied = applyGithubProxy(dl);
                    return new String[]{tagName, proxied};
                }
            }
        }

        // 若没有 JAR 资产，尝试常见命名
        String[] candidates = new String[] {
                "https://github.com/" + owner + "/" + repo + "/releases/download/" + tagName + "/" + repo + "-" + tagName + ".jar",
                "https://github.com/" + owner + "/" + repo + "/releases/download/" + tagName + "/" + repo + ".jar",
                "https://github.com/" + owner + "/" + repo + "/releases/download/" + tagName + "/" + owner + "-" + repo + "-" + tagName + ".jar",
                "https://github.com/" + owner + "/" + repo + "/releases/download/" + tagName + "/" + repo + "-" + tagName.replace("v", "") + ".jar"
        };
        for (String c : candidates) {
            String proxied = applyGithubProxy(c);
            if (urlExists(proxied)) {
                return new String[]{tagName, proxied};
            }
        }
        // 仅接受 .jar 资产；不再回退到源码ZIP
        return null;
    }

    // Spigot（Spiget）下载与版本检索
    private String extractSpigotResourceId(String url) throws IOException {
        String u = url == null ? "" : url;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("/resources/(?:([^/.]+)\\.)?(\\d+)/?");
        java.util.regex.Matcher m = p.matcher(u);
        if (m.find()) {
            return m.group(2);
        }
        // 回退：尝试末尾数字
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("/(\\d+)/?$").matcher(u);
        if (m2.find()) {
            return m2.group(1);
        }
        throw new IOException("无法从URL中提取Spigot资源ID: " + url);
    }

    private String[] getSpigotLatestVersion(String url) throws IOException {
        String resourceId = extractSpigotResourceId(url);
        String apiBase = "https://api.spiget.org/v2/resources/" + resourceId;

        // 优先使用 /versions/latest
        String latestApi = apiBase + "/versions/latest";
        HttpURLConnection conn = (HttpURLConnection) new URL(latestApi).openConnection();
        conn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                Map<String, Object> latest = gson.fromJson(reader, Map.class);
                String versionName = String.valueOf(latest.getOrDefault("name", "unknown"));
                String downloadUrl = apiBase + "/download";
                return new String[]{versionName, downloadUrl};
            }
        } else {
            // 回退：获取全部版本，取最后一个
            String versionsApi = apiBase + "/versions?size=10000";
            HttpURLConnection vconn = (HttpURLConnection) new URL(versionsApi).openConnection();
            vconn.setRequestProperty("User-Agent", "PluginUpdateChecker/1.0");
            vconn.setRequestProperty("Accept", "application/json");
            vconn.setConnectTimeout(10000);
            vconn.setReadTimeout(15000);
            int vcode = vconn.getResponseCode();
            if (vcode < 200 || vcode >= 300) throw new IOException("HTTP " + vcode + " from " + versionsApi);
            try (InputStreamReader reader = new InputStreamReader(vconn.getInputStream())) {
                Type listType = new TypeToken<ArrayList<Map<String, Object>>>() {}.getType();
                List<Map<String, Object>> versions = gson.fromJson(reader, listType);
                if (versions == null || versions.isEmpty()) return null;
                Map<String, Object> latest = versions.get(versions.size() - 1);
                String versionName = String.valueOf(latest.getOrDefault("name", "unknown"));
                String downloadUrl = apiBase + "/download";
                return new String[]{versionName, downloadUrl};
            }
        }
    }

 
     // 新增：保存配置到文件
    private void saveConfigToFile() {
        try (java.io.FileWriter writer = new java.io.FileWriter(configFile)) {
            yaml.dump(config, writer);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存 plugin_updater_config.yaml: " + e.getMessage());
        }
    }

    // 关闭线程池（插件禁用时调用）
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
