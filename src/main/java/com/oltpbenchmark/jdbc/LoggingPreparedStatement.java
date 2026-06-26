/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.oltpbenchmark.jdbc;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A PreparedStatement wrapper that logs every query dispatch and transaction boundary to a CSV
 * file, for diagnosing latency.
 *
 * <p>Enable by passing the JVM property at runtime:
 *
 * <pre>  -Dbenchbase.querylog=/path/to/query_log.csv</pre>
 *
 * <p>Log columns:
 *
 * <pre>  timestamp_ns, epoch_ms, worker_thread, txn_id, txn_type, event, duration_ns, sql</pre>
 *
 * <p>Events:
 *
 * <ul>
 *   <li>TXN_START / TXN_COMMIT / TXN_ROLLBACK — transaction boundaries (logged by Worker)
 *   <li>QUERY_START / QUERY_END / QUERY_ERROR — executeQuery()
 *   <li>UPDATE_START / UPDATE_END / UPDATE_ERROR — executeUpdate()
 *   <li>EXECUTE_START / EXECUTE_END / EXECUTE_ERROR — execute()
 *   <li>BATCH_ADD — addBatch() with the current parameter values
 *   <li>BATCH_START / BATCH_END / BATCH_ERROR — executeBatch()
 * </ul>
 */
public class LoggingPreparedStatement implements PreparedStatement {

  // -------------------------------------------------------------------------
  // Static / shared state
  // -------------------------------------------------------------------------

  /** JVM property name used to specify the output file path. */
  public static final String SYSPROP = "benchbase.querylog";

  private static volatile PrintWriter logWriter = null;
  private static volatile boolean enabled = false;
  private static final Object writerLock = new Object();

  /** Monotonically increasing transaction counter (global across all workers). */
  private static final AtomicLong txnCounter = new AtomicLong(0);

  /** Per-thread current transaction ID (-1 if outside a transaction). */
  private static final ThreadLocal<Long> currentTxnId = ThreadLocal.withInitial(() -> -1L);

  /** Per-thread current transaction type name. */
  private static final ThreadLocal<String> currentTxnType =
      ThreadLocal.withInitial(() -> "UNKNOWN");

  static {
    String path = System.getProperty(SYSPROP);
    if (path != null && !path.isBlank()) {
      enable(path);
    }
  }

  /** Open the log file and enable logging. Overwrites any existing file. */
  public static void enable(String filePath) {
    synchronized (writerLock) {
      try {
        PrintWriter pw =
            new PrintWriter(new BufferedWriter(new FileWriter(filePath, false), 65536));
        pw.println("timestamp_ns,epoch_ms,worker_thread,txn_id,txn_type,event,duration_ns,sql");
        pw.flush();
        logWriter = pw;
        enabled = true;
        System.out.println("[QueryLogger] Logging to: " + filePath);
      } catch (IOException e) {
        System.err.println("[QueryLogger] Failed to open log file: " + e.getMessage());
      }
    }
  }

  /** Close the log file and disable logging. */
  public static void disable() {
    synchronized (writerLock) {
      enabled = false;
      if (logWriter != null) {
        logWriter.close();
        logWriter = null;
      }
    }
  }

  public static boolean isEnabled() {
    return enabled;
  }

  // -------------------------------------------------------------------------
  // Transaction boundary helpers — called from Worker.java
  // -------------------------------------------------------------------------

  /** Log the start of a transaction. Must be called before executeWork(). */
  public static void logTxnStart(String txnType) {
    if (!enabled) return;
    long txnId = txnCounter.incrementAndGet();
    currentTxnId.set(txnId);
    currentTxnType.set(txnType);
    writeEvent("TXN_START", 0L, "");
  }

  /** Log a transaction-end event (TXN_COMMIT or TXN_ROLLBACK). */
  public static void logTxnEnd(String event) {
    if (!enabled) return;
    writeEvent(event, 0L, "");
  }

  // -------------------------------------------------------------------------
  // Core logging
  // -------------------------------------------------------------------------

  /** Package-private so LoggingResultSet can emit RS_NEXT_1 and RS_CLOSE events. */
  static void writeEventPublic(String event, long durationNs, String sql) {
    writeEvent(event, durationNs, sql);
  }

  private static void writeEvent(String event, long durationNs, String sql) {
    PrintWriter pw = logWriter;
    if (pw == null) return;
    long ns = System.nanoTime();
    long ms = System.currentTimeMillis();
    String thread = Thread.currentThread().getName();
    long txnId = currentTxnId.get();
    String txnType = currentTxnType.get();
    // Escape for CSV: wrap sql in double-quotes, escape internal double-quotes
    String safeSql = "\"" + sql.replace("\"", "\"\"").replace("\n", " ").replace("\r", "") + "\"";
    String line =
        ns
            + ","
            + ms
            + ","
            + thread
            + ","
            + txnId
            + ","
            + txnType
            + ","
            + event
            + ","
            + durationNs
            + ","
            + safeSql;
    synchronized (writerLock) {
      pw.println(line);
      pw.flush();
    }
  }

  // -------------------------------------------------------------------------
  // Instance state
  // -------------------------------------------------------------------------

  private final PreparedStatement delegate;

  /** Original SQL template (with ? placeholders), single-line for readability. */
  private final String sqlTemplate;

  /**
   * Current parameter values, keyed by 1-based index. Populated by setXxx() calls; used by
   * buildSql() to reconstruct the full statement for logging.
   */
  private final Map<Integer, Object> params = new TreeMap<>();

  public LoggingPreparedStatement(PreparedStatement delegate, String sqlTemplate) {
    this.delegate = delegate;
    this.sqlTemplate =
        sqlTemplate.trim().replace("\n", " ").replace("\r", "").replaceAll(" +", " ");
  }

  /**
   * Replace '?' placeholders in the SQL template with the current parameter values, producing a
   * human-readable query string suitable for EXPLAIN or direct execution.
   */
  private String buildSql() {
    if (params.isEmpty()) return sqlTemplate;
    StringBuilder sb = new StringBuilder(sqlTemplate.length() + params.size() * 8);
    int paramIdx = 1;
    for (int i = 0; i < sqlTemplate.length(); i++) {
      char c = sqlTemplate.charAt(i);
      if (c == '?') {
        Object val = params.get(paramIdx++);
        if (val == null) {
          sb.append("NULL");
        } else if (val instanceof String s) {
          sb.append('\'').append(s.replace("'", "''")).append('\'');
        } else if (val instanceof Timestamp || val instanceof Date || val instanceof Time) {
          sb.append('\'').append(val).append('\'');
        } else {
          sb.append(val);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // -------------------------------------------------------------------------
  // Execute methods — the ones that actually dispatch a query (with logging)
  // -------------------------------------------------------------------------

  @Override
  public ResultSet executeQuery() throws SQLException {
    String sql = buildSql();
    long start = System.nanoTime();
    writeEvent("QUERY_START", 0L, sql);
    try {
      ResultSet rs = delegate.executeQuery();
      writeEvent("QUERY_END", System.nanoTime() - start, sql);
      return new LoggingResultSet(rs, sql);
    } catch (SQLException e) {
      writeEvent("QUERY_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public int executeUpdate() throws SQLException {
    String sql = buildSql();
    long start = System.nanoTime();
    writeEvent("UPDATE_START", 0L, sql);
    try {
      int result = delegate.executeUpdate();
      writeEvent("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      writeEvent("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public boolean execute() throws SQLException {
    String sql = buildSql();
    long start = System.nanoTime();
    writeEvent("EXECUTE_START", 0L, sql);
    try {
      boolean result = delegate.execute();
      writeEvent("EXECUTE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      writeEvent("EXECUTE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  /**
   * Log the current parameter set at the moment each row is added to the batch. This lets you see
   * exactly what rows were batched before executeBatch() fires.
   */
  @Override
  public void addBatch() throws SQLException {
    if (enabled) {
      writeEvent("BATCH_ADD", 0L, buildSql());
    }
    delegate.addBatch();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    long start = System.nanoTime();
    writeEvent("BATCH_START", 0L, sqlTemplate);
    try {
      int[] result = delegate.executeBatch();
      writeEvent("BATCH_END", System.nanoTime() - start, sqlTemplate);
      return result;
    } catch (SQLException e) {
      writeEvent("BATCH_ERROR", System.nanoTime() - start, sqlTemplate);
      throw e;
    }
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    String sql = buildSql();
    long start = System.nanoTime();
    writeEvent("UPDATE_START", 0L, sql);
    try {
      long result = delegate.executeLargeUpdate();
      writeEvent("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      writeEvent("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    long start = System.nanoTime();
    writeEvent("BATCH_START", 0L, sqlTemplate);
    try {
      long[] result = delegate.executeLargeBatch();
      writeEvent("BATCH_END", System.nanoTime() - start, sqlTemplate);
      return result;
    } catch (SQLException e) {
      writeEvent("BATCH_ERROR", System.nanoTime() - start, sqlTemplate);
      throw e;
    }
  }

  // -------------------------------------------------------------------------
  // Parameter setters — capture values for buildSql(), then delegate
  // -------------------------------------------------------------------------

  @Override
  public void setNull(int idx, int sqlType) throws SQLException {
    params.put(idx, null);
    delegate.setNull(idx, sqlType);
  }

  @Override
  public void setNull(int idx, int sqlType, String typeName) throws SQLException {
    params.put(idx, null);
    delegate.setNull(idx, sqlType, typeName);
  }

  @Override
  public void setBoolean(int idx, boolean x) throws SQLException {
    params.put(idx, x);
    delegate.setBoolean(idx, x);
  }

  @Override
  public void setByte(int idx, byte x) throws SQLException {
    params.put(idx, x);
    delegate.setByte(idx, x);
  }

  @Override
  public void setShort(int idx, short x) throws SQLException {
    params.put(idx, x);
    delegate.setShort(idx, x);
  }

  @Override
  public void setInt(int idx, int x) throws SQLException {
    params.put(idx, x);
    delegate.setInt(idx, x);
  }

  @Override
  public void setLong(int idx, long x) throws SQLException {
    params.put(idx, x);
    delegate.setLong(idx, x);
  }

  @Override
  public void setFloat(int idx, float x) throws SQLException {
    params.put(idx, x);
    delegate.setFloat(idx, x);
  }

  @Override
  public void setDouble(int idx, double x) throws SQLException {
    params.put(idx, x);
    delegate.setDouble(idx, x);
  }

  @Override
  public void setBigDecimal(int idx, BigDecimal x) throws SQLException {
    params.put(idx, x);
    delegate.setBigDecimal(idx, x);
  }

  @Override
  public void setString(int idx, String x) throws SQLException {
    params.put(idx, x);
    delegate.setString(idx, x);
  }

  @Override
  public void setBytes(int idx, byte[] x) throws SQLException {
    params.put(idx, Arrays.toString(x));
    delegate.setBytes(idx, x);
  }

  @Override
  public void setDate(int idx, Date x) throws SQLException {
    params.put(idx, x);
    delegate.setDate(idx, x);
  }

  @Override
  public void setDate(int idx, Date x, Calendar cal) throws SQLException {
    params.put(idx, x);
    delegate.setDate(idx, x, cal);
  }

  @Override
  public void setTime(int idx, Time x) throws SQLException {
    params.put(idx, x);
    delegate.setTime(idx, x);
  }

  @Override
  public void setTime(int idx, Time x, Calendar cal) throws SQLException {
    params.put(idx, x);
    delegate.setTime(idx, x, cal);
  }

  @Override
  public void setTimestamp(int idx, Timestamp x) throws SQLException {
    params.put(idx, x);
    delegate.setTimestamp(idx, x);
  }

  @Override
  public void setTimestamp(int idx, Timestamp x, Calendar cal) throws SQLException {
    params.put(idx, x);
    delegate.setTimestamp(idx, x, cal);
  }

  @Override
  public void setObject(int idx, Object x, int targetSqlType) throws SQLException {
    params.put(idx, x);
    delegate.setObject(idx, x, targetSqlType);
  }

  @Override
  public void setObject(int idx, Object x) throws SQLException {
    params.put(idx, x);
    delegate.setObject(idx, x);
  }

  @Override
  public void setObject(int idx, Object x, int targetSqlType, int scaleOrLength)
      throws SQLException {
    params.put(idx, x);
    delegate.setObject(idx, x, targetSqlType, scaleOrLength);
  }

  @Override
  public void clearParameters() throws SQLException {
    params.clear();
    delegate.clearParameters();
  }

  // -------------------------------------------------------------------------
  // Remaining PreparedStatement setters — pure delegation, no logging needed
  // -------------------------------------------------------------------------

  @Override
  public void setAsciiStream(int idx, InputStream x, int length) throws SQLException {
    delegate.setAsciiStream(idx, x, length);
  }

  @Override
  public void setAsciiStream(int idx, InputStream x, long length) throws SQLException {
    delegate.setAsciiStream(idx, x, length);
  }

  @Override
  public void setAsciiStream(int idx, InputStream x) throws SQLException {
    delegate.setAsciiStream(idx, x);
  }

  @Override
  @Deprecated
  public void setUnicodeStream(int idx, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int idx, InputStream x, int length) throws SQLException {
    delegate.setBinaryStream(idx, x, length);
  }

  @Override
  public void setBinaryStream(int idx, InputStream x, long length) throws SQLException {
    delegate.setBinaryStream(idx, x, length);
  }

  @Override
  public void setBinaryStream(int idx, InputStream x) throws SQLException {
    delegate.setBinaryStream(idx, x);
  }

  @Override
  public void setCharacterStream(int idx, Reader reader, int length) throws SQLException {
    delegate.setCharacterStream(idx, reader, length);
  }

  @Override
  public void setCharacterStream(int idx, Reader reader, long length) throws SQLException {
    delegate.setCharacterStream(idx, reader, length);
  }

  @Override
  public void setCharacterStream(int idx, Reader reader) throws SQLException {
    delegate.setCharacterStream(idx, reader);
  }

  @Override
  public void setRef(int idx, Ref x) throws SQLException {
    delegate.setRef(idx, x);
  }

  @Override
  public void setBlob(int idx, Blob x) throws SQLException {
    delegate.setBlob(idx, x);
  }

  @Override
  public void setBlob(int idx, InputStream inputStream, long length) throws SQLException {
    delegate.setBlob(idx, inputStream, length);
  }

  @Override
  public void setBlob(int idx, InputStream inputStream) throws SQLException {
    delegate.setBlob(idx, inputStream);
  }

  @Override
  public void setClob(int idx, Clob x) throws SQLException {
    delegate.setClob(idx, x);
  }

  @Override
  public void setClob(int idx, Reader reader, long length) throws SQLException {
    delegate.setClob(idx, reader, length);
  }

  @Override
  public void setClob(int idx, Reader reader) throws SQLException {
    delegate.setClob(idx, reader);
  }

  @Override
  public void setArray(int idx, Array x) throws SQLException {
    delegate.setArray(idx, x);
  }

  @Override
  public void setURL(int idx, URL x) throws SQLException {
    delegate.setURL(idx, x);
  }

  @Override
  public void setRowId(int idx, RowId x) throws SQLException {
    delegate.setRowId(idx, x);
  }

  @Override
  public void setNString(int idx, String value) throws SQLException {
    delegate.setNString(idx, value);
  }

  @Override
  public void setNCharacterStream(int idx, Reader value, long length) throws SQLException {
    delegate.setNCharacterStream(idx, value, length);
  }

  @Override
  public void setNCharacterStream(int idx, Reader value) throws SQLException {
    delegate.setNCharacterStream(idx, value);
  }

  @Override
  public void setNClob(int idx, NClob value) throws SQLException {
    delegate.setNClob(idx, value);
  }

  @Override
  public void setNClob(int idx, Reader reader, long length) throws SQLException {
    delegate.setNClob(idx, reader, length);
  }

  @Override
  public void setNClob(int idx, Reader reader) throws SQLException {
    delegate.setNClob(idx, reader);
  }

  @Override
  public void setSQLXML(int idx, SQLXML xmlObject) throws SQLException {
    delegate.setSQLXML(idx, xmlObject);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return delegate.getMetaData();
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    long start = System.nanoTime();
    try {
      return delegate.getParameterMetaData();
    } finally {
      if (enabled) {
        writeEvent("METADATA_FETCH", System.nanoTime() - start, sqlTemplate);
      }
    }
  }

  // -------------------------------------------------------------------------
  // Statement interface — pure delegation
  // -------------------------------------------------------------------------

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return delegate.executeQuery(sql);
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return delegate.executeUpdate(sql);
  }

  @Override
  public void close() throws SQLException {
    long start = System.nanoTime();
    try {
      delegate.close();
    } finally {
      writeEvent("STMT_CLOSE", System.nanoTime() - start, sqlTemplate);
    }
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return delegate.getMaxFieldSize();
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    delegate.setMaxFieldSize(max);
  }

  @Override
  public int getMaxRows() throws SQLException {
    return delegate.getMaxRows();
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    delegate.setMaxRows(max);
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    delegate.setEscapeProcessing(enable);
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    return delegate.getQueryTimeout();
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    delegate.setQueryTimeout(seconds);
  }

  @Override
  public void cancel() throws SQLException {
    delegate.cancel();
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return delegate.getWarnings();
  }

  @Override
  public void clearWarnings() throws SQLException {
    delegate.clearWarnings();
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    delegate.setCursorName(name);
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return delegate.execute(sql);
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return delegate.getResultSet();
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return delegate.getUpdateCount();
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return delegate.getMoreResults();
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    delegate.setFetchDirection(direction);
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return delegate.getFetchDirection();
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    delegate.setFetchSize(rows);
  }

  @Override
  public int getFetchSize() throws SQLException {
    return delegate.getFetchSize();
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return delegate.getResultSetConcurrency();
  }

  @Override
  public int getResultSetType() throws SQLException {
    return delegate.getResultSetType();
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    delegate.addBatch(sql);
  }

  @Override
  public void clearBatch() throws SQLException {
    delegate.clearBatch();
  }

  @Override
  public Connection getConnection() throws SQLException {
    return delegate.getConnection();
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return delegate.getMoreResults(current);
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    return delegate.getGeneratedKeys();
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return delegate.executeUpdate(sql, autoGeneratedKeys);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return delegate.executeUpdate(sql, columnIndexes);
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return delegate.executeUpdate(sql, columnNames);
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return delegate.execute(sql, autoGeneratedKeys);
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return delegate.execute(sql, columnIndexes);
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return delegate.execute(sql, columnNames);
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return delegate.getResultSetHoldability();
  }

  @Override
  public boolean isClosed() throws SQLException {
    return delegate.isClosed();
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    delegate.setPoolable(poolable);
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return delegate.isPoolable();
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    delegate.closeOnCompletion();
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return delegate.isCloseOnCompletion();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return delegate.isWrapperFor(iface);
  }
}
