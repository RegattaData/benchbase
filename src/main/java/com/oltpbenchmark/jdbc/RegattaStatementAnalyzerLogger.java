package com.oltpbenchmark.jdbc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Wrapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures Regatta statement-analyzer output into per-query JSON and summary CSV files.
 *
 * <p>Activated by setting the JVM property {@code -Dbenchbase.stmt_anlz} (any value except
 * {@code false}). Query logging ({@code -Dbenchbase.querylog}) must also be enabled.
 *
 * <p>Files are written under: {@code ./benchbase_stmt_anlz_yyyyMMdd_HHmmss}
 */
public final class RegattaStatementAnalyzerLogger {

  /** JVM property that enables the statement analyzer. */
  public static final String SYSPROP = "benchbase.stmt_anlz";

  /**
   * Returns {@code true} when {@code -Dbenchbase.stmt_anlz} is set to any value other than
   * {@code "false"}. Query logging must also be active for captures to proceed.
   */
  public static boolean isEnabled() {
    String val = System.getProperty(SYSPROP);
    return val != null && !val.equalsIgnoreCase("false");
  }

  private static final DateTimeFormatter DIR_TS_FMT =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
  private static final Object LOCK = new Object();

  private static Path outputDir;

  private static final Map<String, QueryWriters> writersByQuery = new HashMap<>();
  private static final Map<Connection, AnalyzerInvoker> invokerByConnection =
      new IdentityHashMap<>();
  private static final Map<Connection, Connection> resolvedConnByConnection =
      new IdentityHashMap<>();
  private static final Map<Connection, Boolean> configuredConnection = new IdentityHashMap<>();

  private static volatile boolean shutdownHookRegistered = false;

  private RegattaStatementAnalyzerLogger() {
    // utility class
  }

  /**
   * Deterministic statement code that matches the suffix used in stmt_anlz output filenames.
   *
   * <p>Format: {@code <txn_type_slug>_<sha1-10>}
   */
  public static String statementCodeFor(String txnType, String sql) {
    if (sql == null || sql.isBlank()) {
      return "";
    }
    String normalizedSql = normalizeSql(sql);
    if (normalizedSql.isBlank()) {
      return "";
    }
    String safeTxn = sanitizeFilePart(txnType);
    String queryKey = txnType + "::" + normalizedSql;
    String hash = sha1Hex(queryKey).substring(0, 10);
    return safeTxn + "_" + hash;
  }

  public static void capture(
      Connection conn, String event, long durationNs, String sql, long txnId, String txnType) {
    if (!isEnabled()
        || !LoggingPreparedStatement.isEnabled()
        || conn == null
        || sql == null
        || sql.isBlank()
        || !isAnalyzerEvent(event)) {
      return;
    }

    Connection resolvedConn;
    synchronized (LOCK) {
      resolvedConn = getOrResolveConnection(conn);
      if (resolvedConn == null) {
        return;
      }
    }

    AnalyzerInvoker invoker;
    synchronized (LOCK) {
      invoker = getOrCreateInvoker(resolvedConn);
      if (invoker == null) {
        return;
      }
      if (!Boolean.TRUE.equals(configuredConnection.get(resolvedConn))) {
        if (!configureAnalyzer(resolvedConn, invoker)) {
          return;
        }
        configuredConnection.put(resolvedConn, Boolean.TRUE);
      }
    }

    String analyzerJson;
    try {
      analyzerJson = invoker.getAnalyzerJson(resolvedConn);
    } catch (Exception e) {
      return;
    }
    if (analyzerJson == null || analyzerJson.isBlank()) {
      return;
    }

    long nowMs = System.currentTimeMillis();
    double elapsedMs = durationNs / 1_000_000.0;
    String normalizedSql = normalizeSql(sql);
    String queryKey = txnType + "::" + normalizedSql;

    synchronized (LOCK) {
      try {
        QueryWriters q = getOrCreateWriters(queryKey, txnType, normalizedSql);
        q.writeJson(
            nowMs, txnId, txnType, event, durationNs, elapsedMs, normalizedSql, analyzerJson);
      } catch (IOException ioe) {
        // Keep benchmark execution unaffected by diagnostics output failures.
      }
    }
  }

  private static boolean isAnalyzerEvent(String event) {
    if (event == null) {
      return false;
    }
    return "QUERY_END".equals(event)
        || "UPDATE_END".equals(event)
        || "EXECUTE_END".equals(event)
        || "BATCH_END".equals(event);
  }

  private static Connection getOrResolveConnection(Connection conn) {
    Connection cached = resolvedConnByConnection.get(conn);
    if (cached != null) {
      return cached;
    }
    Connection resolved = resolveRegattaConnection(conn);
    if (resolved == null) {
      return null;
    }
    resolvedConnByConnection.put(conn, resolved);
    if (resolved != conn) {
      resolvedConnByConnection.put(resolved, resolved);
    }
    return resolved;
  }

  private static AnalyzerInvoker getOrCreateInvoker(Connection conn) {
    AnalyzerInvoker invoker = invokerByConnection.get(conn);
    if (invoker != null) {
      return invoker;
    }

    Connection resolved = resolveRegattaConnection(conn);
    if (resolved == null) {
      invokerByConnection.put(conn, null);
      return null;
    }
    invoker = AnalyzerInvoker.create(resolved.getClass());
    invokerByConnection.put(conn, invoker);
    return invoker;
  }

  private static boolean configureAnalyzer(Connection conn, AnalyzerInvoker invoker) {
    try {
      invoker.configure(conn);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static Connection resolveRegattaConnection(Connection conn) {
    Connection current = conn;
    for (int i = 0; i < 4; i++) {
      if (current == null) {
        return null;
      }
      if (hasAnalyzerApi(current.getClass())) {
        return current;
      }
      if (!(current instanceof Wrapper)) {
        return null;
      }
      try {
        Connection unwrapped = ((Wrapper) current).unwrap(Connection.class);
        if (unwrapped == current) {
          return null;
        }
        current = unwrapped;
      } catch (SQLException e) {
        return null;
      }
    }
    return null;
  }

  private static boolean hasAnalyzerApi(Class<?> cls) {
    for (java.lang.reflect.Method m : cls.getMethods()) {
      if ("getStatementAnalyzer".equals(m.getName()) && m.getParameterCount() == 1) {
        return true;
      }
    }
    return false;
  }

  private static QueryWriters getOrCreateWriters(String queryKey, String txnType, String sql)
      throws IOException {
    QueryWriters existing = writersByQuery.get(queryKey);
    if (existing != null) {
      return existing;
    }

    Path dir = getOrCreateOutputDir();
    String hash = sha1Hex(queryKey).substring(0, 10);
    String safeTxn = sanitizeFilePart(txnType);
    String baseName = safeTxn + "_" + hash;

    Path jsonPath = dir.resolve(baseName + ".json");
    Path summaryCsvPath = dir.resolve(baseName + "_summary.csv");

    QueryWriters q = new QueryWriters(jsonPath, summaryCsvPath);
    writersByQuery.put(queryKey, q);
    return q;
  }

  private static Path getOrCreateOutputDir() throws IOException {
    if (outputDir != null) {
      return outputDir;
    }
    String ts = LocalDateTime.now().format(DIR_TS_FMT);
    outputDir = Paths.get(System.getProperty("user.dir"), "benchbase_stmt_anlz_" + ts);
    Files.createDirectories(outputDir);

    if (!shutdownHookRegistered) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    synchronized (LOCK) {
                      for (QueryWriters q : writersByQuery.values()) {
                        q.closeQuietly();
                      }
                    }
                  }));
      shutdownHookRegistered = true;
    }
    return outputDir;
  }

  private static String normalizeSql(String sql) {
    String normalized = sql.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    normalized = normalized.replaceAll("'(?:''|[^'])*'", "?");
    normalized = normalized.replaceAll("\\b\\d+(?:\\.\\d+)?\\b", "?");
    return normalized;
  }

  private static String sanitizeFilePart(String s) {
    if (s == null || s.isBlank()) {
      return "unknown";
    }
    return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
  }

  private static String sha1Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 not available", e);
    }
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static Path resolveBareMetalParsingDir() {
    String override = System.getProperty("benchbase.stmt_anlz.parsing_dir");
    if (override != null && !override.isBlank()) {
      return Paths.get(override);
    }
    return Paths.get(System.getProperty("user.home"), "git", "bare-metal", "parsing");
  }

  private static boolean runPythonCommand(List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      // Consume process output to avoid deadlocks on full buffers.
      try (var in = p.getInputStream()) {
        while (in.read() != -1) {
          // no-op
        }
      }
      int code = p.waitFor();
      return code == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void generateSummaryCsv(Path jsonPath, Path summaryCsvPath) {
    Path parsingDir = resolveBareMetalParsingDir();
    Path jsonToCsvScript = parsingDir.resolve("json_to_csv.py");
    Path processScript = parsingDir.resolve("process_stmt_anlz.py");
    if (!Files.isRegularFile(jsonToCsvScript) || !Files.isRegularFile(processScript)) {
      return;
    }

    String summaryName = summaryCsvPath.getFileName().toString();
    String rawName;
    if (summaryName.endsWith("_summary.csv")) {
      rawName = summaryName.substring(0, summaryName.length() - "_summary.csv".length()) + ".csv";
    } else {
      rawName = summaryName + ".raw.csv";
    }
    Path rawCsvPath = summaryCsvPath.getParent().resolve(rawName);

    List<String> jsonToCsvCmd = new ArrayList<>();
    jsonToCsvCmd.add("python3");
    jsonToCsvCmd.add(jsonToCsvScript.toString());
    jsonToCsvCmd.add(jsonPath.toString());
    jsonToCsvCmd.add(rawCsvPath.toString());
    if (!runPythonCommand(jsonToCsvCmd)) {
      return;
    }

    List<String> summarizeCmd = new ArrayList<>();
    summarizeCmd.add("python3");
    summarizeCmd.add(processScript.toString());
    summarizeCmd.add(rawCsvPath.toString());
    if (!runPythonCommand(summarizeCmd)) {
      return;
    }

    Path generatedSummary =
        rawCsvPath.resolveSibling(
            rawCsvPath.getFileName().toString().replaceFirst("\\.csv$", "_summary.csv"));
    if (!generatedSummary.equals(summaryCsvPath) && Files.exists(generatedSummary)) {
      try {
        Files.move(
            generatedSummary, summaryCsvPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException ignored) {
      }
    }

    try {
      Files.deleteIfExists(rawCsvPath);
    } catch (IOException ignored) {
    }
  }

  private static final class QueryWriters {
    private final Path jsonPath;
    private final Path summaryCsvPath;
    private final BufferedWriter jsonWriter;
    private boolean firstJsonRecord = true;
    private boolean closed = false;

    QueryWriters(Path jsonPath, Path summaryCsvPath) throws IOException {
      this.jsonPath = jsonPath;
      this.summaryCsvPath = summaryCsvPath;
      this.jsonWriter =
          Files.newBufferedWriter(
              jsonPath,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);

      jsonWriter.write("[\n");
      jsonWriter.flush();
    }

    void writeJson(
        long epochMs,
        long txnId,
        String txnType,
        String event,
        long durationNs,
        double elapsedMs,
        String normalizedSql,
        String analyzerJson)
        throws IOException {
      if (closed) {
        return;
      }
      if (!firstJsonRecord) {
        jsonWriter.write(",\n");
      }
      firstJsonRecord = false;

      String trimmedAnalyzer = analyzerJson.trim();
      String analyzerField =
          (trimmedAnalyzer.startsWith("{") || trimmedAnalyzer.startsWith("["))
              ? trimmedAnalyzer
              : '"' + escapeJson(trimmedAnalyzer) + '"';

      jsonWriter.write("  {");
      jsonWriter.write("\"record_epoch_ms\":");
      jsonWriter.write(Long.toString(epochMs));
      jsonWriter.write(",\"txn_id\":");
      jsonWriter.write(Long.toString(txnId));
      jsonWriter.write(",\"txn_type\":\"");
      jsonWriter.write(escapeJson(txnType));
      jsonWriter.write("\"");
      jsonWriter.write(",\"event\":\"");
      jsonWriter.write(escapeJson(event));
      jsonWriter.write("\"");
      jsonWriter.write(",\"duration_ns\":");
      jsonWriter.write(Long.toString(durationNs));
      jsonWriter.write(",\"elapsed_ms\":");
      jsonWriter.write(Double.toString(elapsedMs));
      jsonWriter.write(",\"normalized_sql\":\"");
      jsonWriter.write(escapeJson(normalizedSql));
      jsonWriter.write("\"");
      jsonWriter.write(",\"statement_analyzer\":");
      jsonWriter.write(analyzerField);
      jsonWriter.write("}");
      jsonWriter.flush();
    }

    void closeQuietly() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        jsonWriter.write("\n]\n");
      } catch (IOException ignored) {
      }
      try {
        jsonWriter.close();
      } catch (IOException ignored) {
      }

      generateSummaryCsv(jsonPath, summaryCsvPath);
    }
  }

  private static final class AnalyzerInvoker {
    private final java.lang.reflect.Method getStatementAnalyzer;
    private final java.lang.reflect.Method setDepth;
    private final java.lang.reflect.Method setWidth;
    private final Object compactFormat;
    private final Object segmentDepth;
    private final int maxWidth;

    private AnalyzerInvoker(
        java.lang.reflect.Method getStatementAnalyzer,
        java.lang.reflect.Method setDepth,
        java.lang.reflect.Method setWidth,
        Object compactFormat,
        Object segmentDepth,
        int maxWidth) {
      this.getStatementAnalyzer = getStatementAnalyzer;
      this.setDepth = setDepth;
      this.setWidth = setWidth;
      this.compactFormat = compactFormat;
      this.segmentDepth = segmentDepth;
      this.maxWidth = maxWidth;
    }

    static AnalyzerInvoker create(Class<?> connClass) {
      try {
        java.lang.reflect.Method get = null;
        for (java.lang.reflect.Method m : connClass.getMethods()) {
          if ("getStatementAnalyzer".equals(m.getName()) && m.getParameterCount() == 1) {
            get = m;
            break;
          }
        }
        if (get == null) {
          return null;
        }

        Class<?> jsonFormatEnum = get.getParameterTypes()[0];
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object compact = Enum.valueOf((Class) jsonFormatEnum.asSubclass(Enum.class), "COMPACT");

        java.lang.reflect.Method setDepth =
            connClass.getMethod(
                "setStatementAnalyzerVerbosityDepth",
                Class.forName(
                    jsonFormatEnum
                        .getName()
                        .replace(
                            "StatementAnalyzerJsonFormat", "StatementAnalyzerVerbosityDepth")));

        Class<?> depthEnum = setDepth.getParameterTypes()[0];
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object segment = Enum.valueOf((Class) depthEnum.asSubclass(Enum.class), "SEGMENT");

        java.lang.reflect.Method setWidth =
            connClass.getMethod("setStatementAnalyzerVerbosityWidth", int.class);
        java.lang.reflect.Field maxWidthField = connClass.getField("MAX_ANALYZE_WIDTH");
        int maxWidth = maxWidthField.getInt(null);

        return new AnalyzerInvoker(get, setDepth, setWidth, compact, segment, maxWidth);
      } catch (Exception e) {
        return null;
      }
    }

    void configure(Connection conn) throws Exception {
      setDepth.invoke(conn, segmentDepth);
      setWidth.invoke(conn, maxWidth);
    }

    String getAnalyzerJson(Connection conn) throws Exception {
      Object value = getStatementAnalyzer.invoke(conn, compactFormat);
      return value == null ? null : value.toString();
    }
  }
}
