package com.oltpbenchmark.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/** Statement wrapper that instruments plain Statement SQL dispatch and close events. */
public class LoggingStatement implements Statement {

  private final Statement delegate;

  public LoggingStatement(Statement delegate) {
    this.delegate = delegate;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("QUERY_START", 0L, sql);
    try {
      ResultSet rs = delegate.executeQuery(sql);
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("QUERY_END", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "QUERY_END",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      return new LoggingResultSet(rs, sql);
    } catch (SQLException e) {
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("QUERY_ERROR", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "QUERY_ERROR",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      throw e;
    }
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      int result = delegate.executeUpdate(sql);
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "UPDATE_END",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      return result;
    } catch (SQLException e) {
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "UPDATE_ERROR",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      throw e;
    }
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("EXECUTE_START", 0L, sql);
    try {
      boolean result = delegate.execute(sql);
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("EXECUTE_END", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "EXECUTE_END",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      return result;
    } catch (SQLException e) {
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("EXECUTE_ERROR", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "EXECUTE_ERROR",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      throw e;
    }
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    LoggingPreparedStatement.writeEventPublic("BATCH_ADD", 0L, sql);
    delegate.addBatch(sql);
  }

  @Override
  public int[] executeBatch() throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("BATCH_START", 0L, "");
    try {
      int[] result = delegate.executeBatch();
      LoggingPreparedStatement.writeEventPublic("BATCH_END", System.nanoTime() - start, "");
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("BATCH_ERROR", System.nanoTime() - start, "");
      throw e;
    }
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      long result = delegate.executeLargeUpdate(sql);
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "UPDATE_END",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      return result;
    } catch (SQLException e) {
      long duration = System.nanoTime() - start;
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", duration, sql);
      RegattaStatementAnalyzerLogger.capture(
          delegate.getConnection(),
          "UPDATE_ERROR",
          duration,
          sql,
          LoggingPreparedStatement.getCurrentTxnId(),
          LoggingPreparedStatement.getCurrentTxnType());
      throw e;
    }
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("BATCH_START", 0L, "");
    try {
      long[] result = delegate.executeLargeBatch();
      LoggingPreparedStatement.writeEventPublic("BATCH_END", System.nanoTime() - start, "");
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("BATCH_ERROR", System.nanoTime() - start, "");
      throw e;
    }
  }

  @Override
  public void close() throws SQLException {
    long start = System.nanoTime();
    try {
      delegate.close();
    } finally {
      LoggingPreparedStatement.writeEventPublic("STMT_CLOSE", System.nanoTime() - start, "");
    }
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    ResultSet rs = delegate.getResultSet();
    return rs == null ? null : new LoggingResultSet(rs, "");
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    ResultSet rs = delegate.getGeneratedKeys();
    return rs == null ? null : new LoggingResultSet(rs, "");
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
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      int result = delegate.executeUpdate(sql, autoGeneratedKeys);
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      int result = delegate.executeUpdate(sql, columnIndexes);
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      int result = delegate.executeUpdate(sql, columnNames);
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("EXECUTE_START", 0L, sql);
    try {
      boolean result = delegate.execute(sql, autoGeneratedKeys);
      LoggingPreparedStatement.writeEventPublic("EXECUTE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("EXECUTE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("EXECUTE_START", 0L, sql);
    try {
      boolean result = delegate.execute(sql, columnIndexes);
      LoggingPreparedStatement.writeEventPublic("EXECUTE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("EXECUTE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("EXECUTE_START", 0L, sql);
    try {
      boolean result = delegate.execute(sql, columnNames);
      LoggingPreparedStatement.writeEventPublic("EXECUTE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("EXECUTE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
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
  public long getLargeUpdateCount() throws SQLException {
    return delegate.getLargeUpdateCount();
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    delegate.setLargeMaxRows(max);
  }

  @Override
  public long getLargeMaxRows() throws SQLException {
    return delegate.getLargeMaxRows();
  }

  @Override
  public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      long result = delegate.executeLargeUpdate(sql, autoGeneratedKeys);
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      long result = delegate.executeLargeUpdate(sql, columnIndexes);
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
    long start = System.nanoTime();
    LoggingPreparedStatement.writeEventPublic("UPDATE_START", 0L, sql);
    try {
      long result = delegate.executeLargeUpdate(sql, columnNames);
      LoggingPreparedStatement.writeEventPublic("UPDATE_END", System.nanoTime() - start, sql);
      return result;
    } catch (SQLException e) {
      LoggingPreparedStatement.writeEventPublic("UPDATE_ERROR", System.nanoTime() - start, sql);
      throw e;
    }
  }

  @Override
  public String enquoteLiteral(String val) throws SQLException {
    return delegate.enquoteLiteral(val);
  }

  @Override
  public String enquoteIdentifier(String identifier, boolean alwaysQuote) throws SQLException {
    return delegate.enquoteIdentifier(identifier, alwaysQuote);
  }

  @Override
  public boolean isSimpleIdentifier(String identifier) throws SQLException {
    return delegate.isSimpleIdentifier(identifier);
  }

  @Override
  public String enquoteNCharLiteral(String val) throws SQLException {
    return delegate.enquoteNCharLiteral(val);
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this) || delegate.isWrapperFor(iface);
  }
}
