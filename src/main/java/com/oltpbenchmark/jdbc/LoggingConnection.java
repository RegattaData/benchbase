package com.oltpbenchmark.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Connection wrapper that instruments Connection lifecycle events (prepareStatement, commit,
 * rollback).
 */
public class LoggingConnection implements Connection {

  private final Connection delegate;

  public LoggingConnection(Connection delegate) {
    this.delegate = delegate;
  }

  public static Connection wrap(Connection conn) {
    if (conn == null) {
      return null;
    }
    if (conn instanceof LoggingConnection) {
      return conn;
    }
    return new LoggingConnection(conn);
  }

  @Override
  public java.sql.Statement createStatement() throws java.sql.SQLException {
    java.sql.Statement stmt = delegate.createStatement();
    if (LoggingPreparedStatement.isEnabled() && !(stmt instanceof LoggingStatement)) {
      return new LoggingStatement(stmt);
    }
    return stmt;
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String arg0)
      throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      PreparedStatement ps = delegate.prepareStatement(arg0);
      if (logging) {
        return new LoggingPreparedStatement(ps, arg0);
      }
      return ps;
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("PREPARE_STMT1", System.nanoTime() - start, arg0);
      }
    }
  }

  @Override
  public java.sql.CallableStatement prepareCall(java.lang.String arg0)
      throws java.sql.SQLException {
    return delegate.prepareCall(arg0);
  }

  @Override
  public java.lang.String nativeSQL(java.lang.String arg0) throws java.sql.SQLException {
    return delegate.nativeSQL(arg0);
  }

  @Override
  public void setAutoCommit(boolean arg0) throws java.sql.SQLException {
    delegate.setAutoCommit(arg0);
  }

  @Override
  public boolean getAutoCommit() throws java.sql.SQLException {
    return delegate.getAutoCommit();
  }

  @Override
  public void commit() throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      delegate.commit();
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("CONN_COMMIT", System.nanoTime() - start, "");
      }
    }
  }

  @Override
  public void rollback() throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      delegate.rollback();
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("CONN_ROLLBACK", System.nanoTime() - start, "");
      }
    }
  }

  @Override
  public void close() throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      delegate.close();
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("CONN_CLOSE", System.nanoTime() - start, "");
      }
    }
  }

  @Override
  public boolean isClosed() throws java.sql.SQLException {
    return delegate.isClosed();
  }

  @Override
  public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      return delegate.getMetaData();
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic(
            "METADATA_FETCH", System.nanoTime() - start, "connection_metadata");
      }
    }
  }

  @Override
  public void setReadOnly(boolean arg0) throws java.sql.SQLException {
    delegate.setReadOnly(arg0);
  }

  @Override
  public boolean isReadOnly() throws java.sql.SQLException {
    return delegate.isReadOnly();
  }

  @Override
  public void setCatalog(java.lang.String arg0) throws java.sql.SQLException {
    delegate.setCatalog(arg0);
  }

  @Override
  public java.lang.String getCatalog() throws java.sql.SQLException {
    return delegate.getCatalog();
  }

  @Override
  public void setTransactionIsolation(int arg0) throws java.sql.SQLException {
    delegate.setTransactionIsolation(arg0);
  }

  @Override
  public int getTransactionIsolation() throws java.sql.SQLException {
    return delegate.getTransactionIsolation();
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
    return delegate.getWarnings();
  }

  @Override
  public void clearWarnings() throws java.sql.SQLException {
    delegate.clearWarnings();
  }

  @Override
  public java.sql.Statement createStatement(int arg0, int arg1) throws java.sql.SQLException {
    java.sql.Statement stmt = delegate.createStatement(arg0, arg1);
    if (LoggingPreparedStatement.isEnabled() && !(stmt instanceof LoggingStatement)) {
      return new LoggingStatement(stmt);
    }
    return stmt;
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1, int arg2)
      throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      PreparedStatement ps = delegate.prepareStatement(arg0, arg1, arg2);
      if (logging) {
        return new LoggingPreparedStatement(ps, arg0);
      }
      return ps;
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("PREPARE_STMT2", System.nanoTime() - start, arg0);
      }
    }
  }

  @Override
  public java.sql.CallableStatement prepareCall(java.lang.String arg0, int arg1, int arg2)
      throws java.sql.SQLException {
    return delegate.prepareCall(arg0, arg1, arg2);
  }

  @Override
  public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap()
      throws java.sql.SQLException {
    return delegate.getTypeMap();
  }

  @Override
  public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> arg0)
      throws java.sql.SQLException {
    delegate.setTypeMap(arg0);
  }

  @Override
  public void setHoldability(int arg0) throws java.sql.SQLException {
    delegate.setHoldability(arg0);
  }

  @Override
  public int getHoldability() throws java.sql.SQLException {
    return delegate.getHoldability();
  }

  @Override
  public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
    return delegate.setSavepoint();
  }

  @Override
  public java.sql.Savepoint setSavepoint(java.lang.String arg0) throws java.sql.SQLException {
    return delegate.setSavepoint(arg0);
  }

  @Override
  public void rollback(java.sql.Savepoint arg0) throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      delegate.rollback(arg0);
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("CONN_ROLLBACK", System.nanoTime() - start, "");
      }
    }
  }

  @Override
  public void releaseSavepoint(java.sql.Savepoint arg0) throws java.sql.SQLException {
    delegate.releaseSavepoint(arg0);
  }

  @Override
  public java.sql.Statement createStatement(int arg0, int arg1, int arg2)
      throws java.sql.SQLException {
    java.sql.Statement stmt = delegate.createStatement(arg0, arg1, arg2);
    if (LoggingPreparedStatement.isEnabled() && !(stmt instanceof LoggingStatement)) {
      return new LoggingStatement(stmt);
    }
    return stmt;
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(
      java.lang.String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      PreparedStatement ps = delegate.prepareStatement(arg0, arg1, arg2, arg3);
      if (logging) {
        return new LoggingPreparedStatement(ps, arg0);
      }
      return ps;
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("PREPARE_STMT3", System.nanoTime() - start, arg0);
      }
    }
  }

  @Override
  public java.sql.CallableStatement prepareCall(java.lang.String arg0, int arg1, int arg2, int arg3)
      throws java.sql.SQLException {
    return delegate.prepareCall(arg0, arg1, arg2, arg3);
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1)
      throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      PreparedStatement ps = delegate.prepareStatement(arg0, arg1);
      if (logging) {
        return new LoggingPreparedStatement(ps, arg0);
      }
      return ps;
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("PREPARE_STMT4", System.nanoTime() - start, arg0);
      }
    }
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int[] arg1)
      throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      PreparedStatement ps = delegate.prepareStatement(arg0, arg1);
      if (logging) {
        return new LoggingPreparedStatement(ps, arg0);
      }
      return ps;
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("PREPARE_STMT5", System.nanoTime() - start, arg0);
      }
    }
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, java.lang.String[] arg1)
      throws java.sql.SQLException {
    long start = System.nanoTime();
    boolean logging = LoggingPreparedStatement.isEnabled();
    try {
      PreparedStatement ps = delegate.prepareStatement(arg0, arg1);
      if (logging) {
        return new LoggingPreparedStatement(ps, arg0);
      }
      return ps;
    } finally {
      if (logging) {
        LoggingPreparedStatement.writeEventPublic("PREPARE_STMT6", System.nanoTime() - start, arg0);
      }
    }
  }

  @Override
  public java.sql.Clob createClob() throws java.sql.SQLException {
    return delegate.createClob();
  }

  @Override
  public java.sql.Blob createBlob() throws java.sql.SQLException {
    return delegate.createBlob();
  }

  @Override
  public java.sql.NClob createNClob() throws java.sql.SQLException {
    return delegate.createNClob();
  }

  @Override
  public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
    return delegate.createSQLXML();
  }

  @Override
  public boolean isValid(int arg0) throws java.sql.SQLException {
    return delegate.isValid(arg0);
  }

  @Override
  public void setClientInfo(java.lang.String arg0, java.lang.String arg1)
      throws java.sql.SQLClientInfoException {
    delegate.setClientInfo(arg0, arg1);
  }

  @Override
  public void setClientInfo(java.util.Properties arg0) throws java.sql.SQLClientInfoException {
    delegate.setClientInfo(arg0);
  }

  @Override
  public java.lang.String getClientInfo(java.lang.String arg0) throws java.sql.SQLException {
    return delegate.getClientInfo(arg0);
  }

  @Override
  public java.util.Properties getClientInfo() throws java.sql.SQLException {
    return delegate.getClientInfo();
  }

  @Override
  public java.sql.Array createArrayOf(java.lang.String arg0, java.lang.Object[] arg1)
      throws java.sql.SQLException {
    return delegate.createArrayOf(arg0, arg1);
  }

  @Override
  public java.sql.Struct createStruct(java.lang.String arg0, java.lang.Object[] arg1)
      throws java.sql.SQLException {
    return delegate.createStruct(arg0, arg1);
  }

  @Override
  public void setSchema(java.lang.String arg0) throws java.sql.SQLException {
    delegate.setSchema(arg0);
  }

  @Override
  public java.lang.String getSchema() throws java.sql.SQLException {
    return delegate.getSchema();
  }

  @Override
  public void abort(java.util.concurrent.Executor arg0) throws java.sql.SQLException {
    delegate.abort(arg0);
  }

  @Override
  public void setNetworkTimeout(java.util.concurrent.Executor arg0, int arg1)
      throws java.sql.SQLException {
    delegate.setNetworkTimeout(arg0, arg1);
  }

  @Override
  public int getNetworkTimeout() throws java.sql.SQLException {
    return delegate.getNetworkTimeout();
  }

  @Override
  public void beginRequest() throws java.sql.SQLException {
    delegate.beginRequest();
  }

  @Override
  public void endRequest() throws java.sql.SQLException {
    delegate.endRequest();
  }

  @Override
  public boolean setShardingKeyIfValid(
      java.sql.ShardingKey arg0, java.sql.ShardingKey arg1, int arg2) throws java.sql.SQLException {
    return delegate.setShardingKeyIfValid(arg0, arg1, arg2);
  }

  @Override
  public boolean setShardingKeyIfValid(java.sql.ShardingKey arg0, int arg1)
      throws java.sql.SQLException {
    return delegate.setShardingKeyIfValid(arg0, arg1);
  }

  @Override
  public void setShardingKey(java.sql.ShardingKey arg0, java.sql.ShardingKey arg1)
      throws java.sql.SQLException {
    delegate.setShardingKey(arg0, arg1);
  }

  @Override
  public void setShardingKey(java.sql.ShardingKey arg0) throws java.sql.SQLException {
    delegate.setShardingKey(arg0);
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
