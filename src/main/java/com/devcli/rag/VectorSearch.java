package com.devcli.rag;

import com.devcli.util.VectorMath;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.rag.VectorStore.*;

/** 向量与关键词候选检索、ANN 缓存及实时内容校验；共享存储连接和索引版本。 */
final class VectorSearch {
    private final Connection connection;
    private final String projectPath;
    private final String classpathEpoch;
    private final VectorStore store;

    VectorSearch(Connection connection, String projectPath, String classpathEpoch, VectorStore store) {
        this.connection = connection;
        this.projectPath = projectPath;
        this.classpathEpoch = classpathEpoch;
        this.store = store;
    }

    private static final Logger log = LoggerFactory.getLogger(VectorSearch.class);

    volatile AnnSnapshot annSnapshot;

    volatile SearchDiagnostics lastSearchDiagnostics = SearchDiagnostics.empty();

    /**
     * 语义检索：根据查询向量返回最相似的 TopK 代码块
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK) throws SQLException {
        if (queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }
        long started = System.nanoTime();
        AnnSnapshot snapshot = ensureAnnSnapshot();
        List<SearchResult> results = new ArrayList<>();
        if (snapshot.index() != null) {
            for (AnnVectorIndex.Neighbor neighbor : snapshot.index().search(queryEmbedding, topK)) {
                IndexedChunk chunk = snapshot.chunks().get(neighbor.id());
                if (chunk != null) {
                    results.add(indexedSearchResult(chunk, neighbor.similarity()));
                }
            }
            lastSearchDiagnostics = new SearchDiagnostics("HNSW", "NONE", snapshot.chunks().size(),
                    elapsedMillis(started), false, "");
        } else {
            for (IndexedChunk chunk : snapshot.chunks().values()) {
                results.add(indexedSearchResult(chunk, cosineSimilarity(queryEmbedding, chunk.vector())));
            }
            results.sort((left, right) -> Double.compare(right.similarity(), left.similarity()));
            if (results.size() > topK) {
                results = new ArrayList<>(results.subList(0, topK));
            }
            lastSearchDiagnostics = new SearchDiagnostics("EXACT_FALLBACK", "NONE", snapshot.chunks().size(),
                    elapsedMillis(started), true, snapshot.degradationReason());
        }
        return refreshExternalChanges(results);
    }

    /**
     * 根据关键词检索代码块（不经过 Embedding，用于精确匹配类名/方法名）
     */
    public List<SearchResult> searchByKeyword(String keyword) throws SQLException {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        long started = System.nanoTime();
        boolean useFts = keyword.codePointCount(0, keyword.length()) >= 3;
        String sql = useFts ? """
                SELECT c.file_path, c.chunk_type, c.name, c.content,
                       c.index_epoch, c.symbol_version, c.classpath_epoch
                FROM code_chunks_fts
                JOIN code_chunks c ON c.id = code_chunks_fts.rowid
                WHERE code_chunks_fts MATCH ? AND c.project_path = ?
                ORDER BY bm25(code_chunks_fts, 0.0, 1.0, 3.0, 1.0), c.name
                LIMIT 200
                """ : """
                SELECT file_path, chunk_type, name, content, index_epoch, symbol_version, classpath_epoch
                FROM code_chunks
                WHERE project_path = ? AND (name = ? OR name LIKE ? ESCAPE '\\' OR file_path LIKE ? ESCAPE '\\')
                ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, name
                LIMIT 200
                """;
        List<SearchResult> results = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (useFts) {
                ps.setString(1, ftsPhrase(keyword));
                ps.setString(2, projectPath);
            } else {
                String escaped = escapeLike(keyword);
                ps.setString(1, projectPath);
                ps.setString(2, keyword);
                ps.setString(3, escaped + "%");
                ps.setString(4, "%/" + escaped + "%");
                ps.setString(5, keyword);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(searchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            0.3,
                            rs.getString("index_epoch"),
                            rs.getString("symbol_version"),
                            rs.getString("classpath_epoch")
                    ));
                }
            }
        }
        lastSearchDiagnostics = new SearchDiagnostics("NONE", useFts ? "FTS5_TRIGRAM" : "BTREE_PREFIX",
                results.size(), elapsedMillis(started), false, "");
        return mergeDirtyKeywordCandidates(keyword, refreshExternalChanges(results));
    }

    public List<SearchResult> findChunksByName(String name, int limit) throws SQLException {
        if (name == null || name.isBlank() || limit <= 0) {
            return List.of();
        }
        String sql = """
                SELECT file_path, chunk_type, name, content, index_epoch, symbol_version, classpath_epoch FROM code_chunks
                WHERE project_path = ? AND (name = ? OR name LIKE ? ESCAPE '\\')
                ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, chunk_type DESC, name
                LIMIT ?
                """;
        List<SearchResult> results = new ArrayList<>();
        String escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String prefixPattern = escaped + "(%";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            ps.setString(3, prefixPattern);
            ps.setString(4, name);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(searchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            0.0,
                            rs.getString("index_epoch"),
                            rs.getString("symbol_version"),
                            rs.getString("classpath_epoch")
                    ));
                }
            }
        }
        return refreshExternalChanges(results);
    }

    synchronized AnnSnapshot ensureAnnSnapshot() throws SQLException {
        long generation = store.currentIndexGeneration();
        AnnSnapshot current = annSnapshot;
        if (current != null && current.generation() == generation) {
            return current;
        }

        List<AnnVectorIndex.Entry> vectors = new ArrayList<>();
        Map<Long, IndexedChunk> chunks = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, file_path, chunk_type, name, content, embedding_blob, embedding_json,
                       index_epoch, symbol_version, classpath_epoch
                FROM code_chunks
                WHERE project_path = ? AND (embedding_blob IS NOT NULL OR embedding_json IS NOT NULL)
                ORDER BY id
                """)) {
            statement.setString(1, projectPath);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    byte[] blob = rows.getBytes("embedding_blob");
                    float[] vector = blob == null
                            ? store.jsonToEmbedding(rows.getString("embedding_json"))
                            : VectorCodec.decode(blob);
                    vectors.add(new AnnVectorIndex.Entry(id, vector));
                    chunks.put(id, new IndexedChunk(
                            rows.getString("file_path"), rows.getString("chunk_type"),
                            rows.getString("name"), rows.getString("content"), vector,
                            rows.getString("index_epoch"), rows.getString("symbol_version"),
                            rows.getString("classpath_epoch")));
                }
            }
        }

        AnnVectorIndex index = null;
        String degradationReason = "";
        try {
            index = AnnVectorIndex.build(vectors);
        } catch (RuntimeException failure) {
            degradationReason = "HNSW_BUILD_FAILED: " + failure.getMessage();
            log.warn("HNSW 索引构建失败，显式降级为精确检索: {}", failure.getMessage());
        }
        AnnSnapshot built = new AnnSnapshot(generation, index, Map.copyOf(chunks), degradationReason);
        annSnapshot = built;
        return built;
    }

    synchronized void invalidateAnnIndex() {
        annSnapshot = null;
    }

    SearchResult indexedSearchResult(IndexedChunk chunk, double similarity) throws SQLException {
        return searchResult(chunk.filePath(), chunk.chunkType(), chunk.name(), chunk.content(), similarity,
                chunk.indexEpoch(), chunk.symbolVersion(), chunk.classpathEpoch());
    }

    static String ftsPhrase(String keyword) {
        return "\"" + keyword.strip().replace("\"", "\"\"") + "\"";
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    double cosineSimilarity(float[] a, float[] b) {
        return VectorMath.cosineSimilarity(a, b);
    }

    SearchResult searchResult(String filePath, String chunkType, String name, String content, double similarity) {
        String symbolVersion = SymbolVersion.from(filePath, chunkType, name, content, classpathEpoch).value();
        return new SearchResult(filePath, chunkType, name, content, similarity,
                symbolVersion, classpathEpoch, IndexEpoch.none().value(), List.of());
    }

    SearchResult searchResult(String filePath, String chunkType, String name, String content, double similarity,
                                      String indexEpoch, String symbolVersion, String resultClasspathEpoch) throws SQLException {
        String effectiveClasspathEpoch = blankToDefault(resultClasspathEpoch, classpathEpoch);
        String effectiveSymbolVersion = blankToDefault(symbolVersion,
                SymbolVersion.from(filePath, chunkType, name, content, effectiveClasspathEpoch).value());
        String effectiveIndexEpoch = blankToDefault(indexEpoch, IndexEpoch.none().value());
        IndexFreshness freshness = indexFreshness(filePath, effectiveIndexEpoch);
        return new SearchResult(filePath, chunkType, name, content, similarity,
                effectiveSymbolVersion,
                effectiveClasspathEpoch,
                effectiveIndexEpoch,
                store.getInvalidationsForSymbol(SymbolSnapshot.symbolKey(filePath, chunkType, name), 3),
                freshness);
    }

    /**
     * 只校验已经进入最终候选集的块，兜住 IDE/脚本直接修改主项目但未经过工具写入的情况。
     */
    List<SearchResult> refreshExternalChanges(List<SearchResult> results) throws SQLException {
        if (results == null || results.isEmpty()) return List.of();
        List<SearchResult> refreshed = new ArrayList<>(results.size());
        Map<String, LiveFile> liveFiles = new HashMap<>();
        LinkedHashSet<String> newlyDirty = new LinkedHashSet<>();
        for (SearchResult result : results) {
            LiveChunk live = readLiveChunk(result.filePath(), result.chunkType(), result.name(), liveFiles);
            if (!live.verifiable()) {
                refreshed.add(result);
                continue;
            }
            boolean changed = live.content().filter(result.content()::equals).isEmpty();
            if (result.freshness() != IndexFreshness.DIRTY && !changed) {
                refreshed.add(result);
                continue;
            }
            if (changed) {
                newlyDirty.add(result.filePath());
            }
            String currentContent = live.content().orElse(
                    "索引命中的代码块已从当前文件中移除，请重新读取文件确认现状。");
            refreshed.add(new SearchResult(result.filePath(), result.chunkType(), result.name(),
                    currentContent, result.similarity(), result.symbolVersion(),
                    result.classpathEpoch(), result.indexEpoch(), result.invalidations(),
                    IndexFreshness.DIRTY));
        }
        if (!newlyDirty.isEmpty()) {
            try {
                store.markDirtyFiles(List.copyOf(newlyDirty));
            } catch (SQLException e) {
                log.warn("持久化外部文件索引失效标记失败: {}", e.getMessage());
            }
        }
        return refreshed;
    }

    /**
     * 旧索引只能提供已经存在的候选。文件进入 DIRTY 后，再从当前文件实时分块并合并关键词命中，
     * 使新增方法和新增配置键无需等待下一轮索引也能被精确检索发现。
     */
    List<SearchResult> mergeDirtyKeywordCandidates(String keyword,
                                                            List<SearchResult> indexedResults)
            throws SQLException {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (SearchResult result : indexedResults) {
            merged.put(liveResultKey(result.filePath(), result.chunkType(), result.name()), result);
        }

        for (Path file : dirtyProjectFiles()) {
            LiveFile liveFile = readLiveFile(file.toString());
            if (!liveFile.verifiable() || liveFile.rawContent() == null) continue;
            for (CodeChunk chunk : liveFile.chunks()) {
                if (!containsKeyword(chunk, keyword)) continue;
                SearchResult liveResult = liveDirtyResult(chunk);
                merged.put(liveResultKey(liveResult.filePath(), liveResult.chunkType(), liveResult.name()),
                        liveResult);
            }
        }
        return List.copyOf(merged.values());
    }

    List<Path> dirtyProjectFiles() throws SQLException {
        String sql = """
                SELECT file_path FROM code_index_dirty_files
                WHERE project_path = ?
                ORDER BY marked_at_ms, file_path
                """;
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    resolveProjectPath(rs.getString(1)).ifPresent(files::add);
                }
            }
        }
        return List.copyOf(files);
    }

    SearchResult liveDirtyResult(CodeChunk chunk) throws SQLException {
        String symbolVersion = SymbolVersion.from(chunk.filePath(), chunk.chunkType(), chunk.name(),
                chunk.content(), classpathEpoch).value();
        return new SearchResult(chunk.filePath(), chunk.chunkType(), chunk.name(), chunk.content(),
                0.3, symbolVersion, classpathEpoch, store.currentIndexEpoch(),
                store.getInvalidationsForSymbol(SymbolSnapshot.symbolKey(
                        chunk.filePath(), chunk.chunkType(), chunk.name()), 3),
                IndexFreshness.DIRTY);
    }

    static boolean containsKeyword(CodeChunk chunk, String keyword) {
        String needle = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        return chunk.name().toLowerCase(Locale.ROOT).contains(needle)
                || chunk.filePath().toLowerCase(Locale.ROOT).contains(needle)
                || chunk.content().toLowerCase(Locale.ROOT).contains(needle);
    }

    String liveResultKey(String filePath, String chunkType, String name) {
        String canonicalPath = resolveProjectPath(filePath)
                .map(Path::toString)
                .orElse(filePath == null ? "" : filePath);
        String logicalName = name == null ? "" : name;
        if ("file".equals(chunkType)) {
            int segment = logicalName.lastIndexOf('#');
            logicalName = segment >= 0 && logicalName.substring(segment + 1).matches("\\d+")
                    ? "file" + logicalName.substring(segment)
                    : "file";
        }
        return canonicalPath + "#" + chunkType + "#" + logicalName;
    }

    LiveChunk readLiveChunk(String filePath, String chunkType, String name,
                                    Map<String, LiveFile> liveFiles) {
        LiveFile liveFile = liveFiles.computeIfAbsent(filePath, this::readLiveFile);
        if (!liveFile.verifiable()) return LiveChunk.unverifiable();
        if (liveFile.rawContent() == null) return LiveChunk.missing();
        if ("file".equals(chunkType) && (name == null || !name.matches(".*#\\d+$"))) {
            return LiveChunk.present(liveFile.rawContent());
        }
        for (CodeChunk chunk : liveFile.chunks()) {
            if (chunk.chunkType().equals(chunkType) && sameChunkName(chunkType, chunk.name(), name)) {
                return LiveChunk.present(chunk.content());
            }
        }
        return LiveChunk.missing();
    }

    LiveFile readLiveFile(String filePath) {
        try {
            Path file = resolveProjectPath(filePath).orElse(null);
            if (file == null) return LiveFile.unverifiable();
            if (!java.nio.file.Files.isRegularFile(file)) return LiveFile.missing();
            String rawContent = java.nio.file.Files.readString(file);
            return new LiveFile(true, rawContent, new CodeChunker().chunkFile(file));
        } catch (Exception ignored) {
            return LiveFile.unverifiable();
        }
    }

    Optional<Path> resolveProjectPath(String filePath) {
        try {
            Path root = Path.of(projectPath).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(root) || filePath == null || filePath.isBlank()) {
                return Optional.empty();
            }
            Path candidate = Path.of(filePath);
            Path file = (candidate.isAbsolute() ? candidate : root.resolve(candidate))
                    .toAbsolutePath().normalize();
            return file.startsWith(root) ? Optional.of(file) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    String relativeProjectPath(Path file) {
        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static boolean sameChunkName(String chunkType, String currentName, String indexedName) {
        if (java.util.Objects.equals(currentName, indexedName)) return true;
        if (!"file".equals(chunkType) || indexedName == null) return false;
        int segment = indexedName.lastIndexOf('#');
        return segment >= 0 && currentName.endsWith(indexedName.substring(segment));
    }

    IndexFreshness indexFreshness(String filePath, String resultEpoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM code_index_dirty_files
                WHERE project_path = ? AND file_path = ? LIMIT 1
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, filePath);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return IndexFreshness.DIRTY;
            }
        }
        return safeIndexEpoch(resultEpoch).equals(store.currentIndexEpoch())
                && "CURRENT".equals(currentIndexStatus())
                ? IndexFreshness.CURRENT : IndexFreshness.STALE;
    }

    String currentIndexStatus() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM code_index_state
                WHERE project_path = ?
                """)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "CURRENT";
            }
        }
    }

    static String safeIndexEpoch(String indexEpoch) {
        return blankToDefault(indexEpoch, IndexEpoch.none().value());
    }

    static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    record LiveChunk(boolean verifiable, Optional<String> content) {
        static LiveChunk present(String content) {
            return new LiveChunk(true, Optional.ofNullable(content));
        }

        static LiveChunk missing() {
            return new LiveChunk(true, Optional.empty());
        }

        static LiveChunk unverifiable() {
            return new LiveChunk(false, Optional.empty());
        }
    }

    record LiveFile(boolean verifiable, String rawContent, List<CodeChunk> chunks) {
        LiveFile {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }

        static LiveFile missing() {
            return new LiveFile(true, null, List.of());
        }

        static LiveFile unverifiable() {
            return new LiveFile(false, null, List.of());
        }
    }

    record IndexedChunk(String filePath, String chunkType, String name, String content,
                                float[] vector, String indexEpoch, String symbolVersion,
                                String classpathEpoch) {
    }

    record AnnSnapshot(long generation, AnnVectorIndex index,
                               Map<Long, IndexedChunk> chunks, String degradationReason) {
    }

    public SearchDiagnostics lastSearchDiagnostics() {
        return lastSearchDiagnostics;
    }
}
