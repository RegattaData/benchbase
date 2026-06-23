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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.Map;

/**
 * ResultSet wrapper that instruments two lifecycle events to close the timing gap visible between
 * QUERY_END and the next statement event (BATCH_ADD, UPDATE_START, etc.):
 *
 * <ul>
 *   <li><b>RS_NEXT_1</b> — first call to {@code next()}, with {@code duration_ns} measured from the
 *       moment {@code executeQuery()} returned this ResultSet. A large value here means the Regatta
 *       driver fetches rows lazily and the first {@code next()} call sends an extra round-trip to
 *       the server.
 *   <li><b>RS_CLOSE</b> — {@code close()}, with {@code duration_ns} of the close call itself. A
 *       large value here means the driver sends a cursor-release message to the server on close.
 * </ul>
 *
 * {@link LoggingPreparedStatement#close()} separately logs <b>STMT_CLOSE</b>.
 */
class LoggingResultSet implements ResultSet {

  private final ResultSet delegate;
  private final String sql;

  /** nanoTime() snapshot taken right after executeQuery() returned — used for RS_NEXT_1. */
  private final long createdAtNs = System.nanoTime();

  private boolean firstNextDone = false;

  LoggingResultSet(ResultSet delegate, String sql) {
    this.delegate = delegate;
    this.sql = sql;
  }

  // -------------------------------------------------------------------------
  // Instrumented methods
  // -------------------------------------------------------------------------

  @Override
  public boolean next() throws SQLException {
    if (!firstNextDone) {
      firstNextDone = true;
      LoggingPreparedStatement.writeEventPublic("RS_NEXT_1", System.nanoTime() - createdAtNs, sql);
    }
    return delegate.next();
  }

  @Override
  public void close() throws SQLException {
    long start = System.nanoTime();
    try {
      delegate.close();
    } finally {
      LoggingPreparedStatement.writeEventPublic("RS_CLOSE", System.nanoTime() - start, sql);
    }
  }

  // -------------------------------------------------------------------------
  // Pure delegation
  // -------------------------------------------------------------------------

  @Override
  public boolean wasNull() throws SQLException {
    return delegate.wasNull();
  }

  @Override
  public String getString(int c) throws SQLException {
    return delegate.getString(c);
  }

  @Override
  public boolean getBoolean(int c) throws SQLException {
    return delegate.getBoolean(c);
  }

  @Override
  public byte getByte(int c) throws SQLException {
    return delegate.getByte(c);
  }

  @Override
  public short getShort(int c) throws SQLException {
    return delegate.getShort(c);
  }

  @Override
  public int getInt(int c) throws SQLException {
    return delegate.getInt(c);
  }

  @Override
  public long getLong(int c) throws SQLException {
    return delegate.getLong(c);
  }

  @Override
  public float getFloat(int c) throws SQLException {
    return delegate.getFloat(c);
  }

  @Override
  public double getDouble(int c) throws SQLException {
    return delegate.getDouble(c);
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(int c, int s) throws SQLException {
    return delegate.getBigDecimal(c, s);
  }

  @Override
  public byte[] getBytes(int c) throws SQLException {
    return delegate.getBytes(c);
  }

  @Override
  public Date getDate(int c) throws SQLException {
    return delegate.getDate(c);
  }

  @Override
  public Time getTime(int c) throws SQLException {
    return delegate.getTime(c);
  }

  @Override
  public Timestamp getTimestamp(int c) throws SQLException {
    return delegate.getTimestamp(c);
  }

  @Override
  public InputStream getAsciiStream(int c) throws SQLException {
    return delegate.getAsciiStream(c);
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(int c) throws SQLException {
    return delegate.getUnicodeStream(c);
  }

  @Override
  public InputStream getBinaryStream(int c) throws SQLException {
    return delegate.getBinaryStream(c);
  }

  @Override
  public String getString(String c) throws SQLException {
    return delegate.getString(c);
  }

  @Override
  public boolean getBoolean(String c) throws SQLException {
    return delegate.getBoolean(c);
  }

  @Override
  public byte getByte(String c) throws SQLException {
    return delegate.getByte(c);
  }

  @Override
  public short getShort(String c) throws SQLException {
    return delegate.getShort(c);
  }

  @Override
  public int getInt(String c) throws SQLException {
    return delegate.getInt(c);
  }

  @Override
  public long getLong(String c) throws SQLException {
    return delegate.getLong(c);
  }

  @Override
  public float getFloat(String c) throws SQLException {
    return delegate.getFloat(c);
  }

  @Override
  public double getDouble(String c) throws SQLException {
    return delegate.getDouble(c);
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(String c, int s) throws SQLException {
    return delegate.getBigDecimal(c, s);
  }

  @Override
  public byte[] getBytes(String c) throws SQLException {
    return delegate.getBytes(c);
  }

  @Override
  public Date getDate(String c) throws SQLException {
    return delegate.getDate(c);
  }

  @Override
  public Time getTime(String c) throws SQLException {
    return delegate.getTime(c);
  }

  @Override
  public Timestamp getTimestamp(String c) throws SQLException {
    return delegate.getTimestamp(c);
  }

  @Override
  public InputStream getAsciiStream(String c) throws SQLException {
    return delegate.getAsciiStream(c);
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(String c) throws SQLException {
    return delegate.getUnicodeStream(c);
  }

  @Override
  public InputStream getBinaryStream(String c) throws SQLException {
    return delegate.getBinaryStream(c);
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
  public String getCursorName() throws SQLException {
    return delegate.getCursorName();
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return delegate.getMetaData();
  }

  @Override
  public Object getObject(int c) throws SQLException {
    return delegate.getObject(c);
  }

  @Override
  public Object getObject(String c) throws SQLException {
    return delegate.getObject(c);
  }

  @Override
  public int findColumn(String c) throws SQLException {
    return delegate.findColumn(c);
  }

  @Override
  public Reader getCharacterStream(int c) throws SQLException {
    return delegate.getCharacterStream(c);
  }

  @Override
  public Reader getCharacterStream(String c) throws SQLException {
    return delegate.getCharacterStream(c);
  }

  @Override
  public BigDecimal getBigDecimal(int c) throws SQLException {
    return delegate.getBigDecimal(c);
  }

  @Override
  public BigDecimal getBigDecimal(String c) throws SQLException {
    return delegate.getBigDecimal(c);
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    return delegate.isBeforeFirst();
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    return delegate.isAfterLast();
  }

  @Override
  public boolean isFirst() throws SQLException {
    return delegate.isFirst();
  }

  @Override
  public boolean isLast() throws SQLException {
    return delegate.isLast();
  }

  @Override
  public void beforeFirst() throws SQLException {
    delegate.beforeFirst();
  }

  @Override
  public void afterLast() throws SQLException {
    delegate.afterLast();
  }

  @Override
  public boolean first() throws SQLException {
    return delegate.first();
  }

  @Override
  public boolean last() throws SQLException {
    return delegate.last();
  }

  @Override
  public int getRow() throws SQLException {
    return delegate.getRow();
  }

  @Override
  public boolean absolute(int r) throws SQLException {
    return delegate.absolute(r);
  }

  @Override
  public boolean relative(int r) throws SQLException {
    return delegate.relative(r);
  }

  @Override
  public boolean previous() throws SQLException {
    return delegate.previous();
  }

  @Override
  public void setFetchDirection(int d) throws SQLException {
    delegate.setFetchDirection(d);
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return delegate.getFetchDirection();
  }

  @Override
  public void setFetchSize(int r) throws SQLException {
    delegate.setFetchSize(r);
  }

  @Override
  public int getFetchSize() throws SQLException {
    return delegate.getFetchSize();
  }

  @Override
  public int getType() throws SQLException {
    return delegate.getType();
  }

  @Override
  public int getConcurrency() throws SQLException {
    return delegate.getConcurrency();
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    return delegate.rowUpdated();
  }

  @Override
  public boolean rowInserted() throws SQLException {
    return delegate.rowInserted();
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    return delegate.rowDeleted();
  }

  @Override
  public void updateNull(int c) throws SQLException {
    delegate.updateNull(c);
  }

  @Override
  public void updateBoolean(int c, boolean x) throws SQLException {
    delegate.updateBoolean(c, x);
  }

  @Override
  public void updateByte(int c, byte x) throws SQLException {
    delegate.updateByte(c, x);
  }

  @Override
  public void updateShort(int c, short x) throws SQLException {
    delegate.updateShort(c, x);
  }

  @Override
  public void updateInt(int c, int x) throws SQLException {
    delegate.updateInt(c, x);
  }

  @Override
  public void updateLong(int c, long x) throws SQLException {
    delegate.updateLong(c, x);
  }

  @Override
  public void updateFloat(int c, float x) throws SQLException {
    delegate.updateFloat(c, x);
  }

  @Override
  public void updateDouble(int c, double x) throws SQLException {
    delegate.updateDouble(c, x);
  }

  @Override
  public void updateBigDecimal(int c, BigDecimal x) throws SQLException {
    delegate.updateBigDecimal(c, x);
  }

  @Override
  public void updateString(int c, String x) throws SQLException {
    delegate.updateString(c, x);
  }

  @Override
  public void updateBytes(int c, byte[] x) throws SQLException {
    delegate.updateBytes(c, x);
  }

  @Override
  public void updateDate(int c, Date x) throws SQLException {
    delegate.updateDate(c, x);
  }

  @Override
  public void updateTime(int c, Time x) throws SQLException {
    delegate.updateTime(c, x);
  }

  @Override
  public void updateTimestamp(int c, Timestamp x) throws SQLException {
    delegate.updateTimestamp(c, x);
  }

  @Override
  public void updateAsciiStream(int c, InputStream x, int l) throws SQLException {
    delegate.updateAsciiStream(c, x, l);
  }

  @Override
  public void updateBinaryStream(int c, InputStream x, int l) throws SQLException {
    delegate.updateBinaryStream(c, x, l);
  }

  @Override
  public void updateCharacterStream(int c, Reader x, int l) throws SQLException {
    delegate.updateCharacterStream(c, x, l);
  }

  @Override
  public void updateObject(int c, Object x, int s) throws SQLException {
    delegate.updateObject(c, x, s);
  }

  @Override
  public void updateObject(int c, Object x) throws SQLException {
    delegate.updateObject(c, x);
  }

  @Override
  public void updateNull(String c) throws SQLException {
    delegate.updateNull(c);
  }

  @Override
  public void updateBoolean(String c, boolean x) throws SQLException {
    delegate.updateBoolean(c, x);
  }

  @Override
  public void updateByte(String c, byte x) throws SQLException {
    delegate.updateByte(c, x);
  }

  @Override
  public void updateShort(String c, short x) throws SQLException {
    delegate.updateShort(c, x);
  }

  @Override
  public void updateInt(String c, int x) throws SQLException {
    delegate.updateInt(c, x);
  }

  @Override
  public void updateLong(String c, long x) throws SQLException {
    delegate.updateLong(c, x);
  }

  @Override
  public void updateFloat(String c, float x) throws SQLException {
    delegate.updateFloat(c, x);
  }

  @Override
  public void updateDouble(String c, double x) throws SQLException {
    delegate.updateDouble(c, x);
  }

  @Override
  public void updateBigDecimal(String c, BigDecimal x) throws SQLException {
    delegate.updateBigDecimal(c, x);
  }

  @Override
  public void updateString(String c, String x) throws SQLException {
    delegate.updateString(c, x);
  }

  @Override
  public void updateBytes(String c, byte[] x) throws SQLException {
    delegate.updateBytes(c, x);
  }

  @Override
  public void updateDate(String c, Date x) throws SQLException {
    delegate.updateDate(c, x);
  }

  @Override
  public void updateTime(String c, Time x) throws SQLException {
    delegate.updateTime(c, x);
  }

  @Override
  public void updateTimestamp(String c, Timestamp x) throws SQLException {
    delegate.updateTimestamp(c, x);
  }

  @Override
  public void updateAsciiStream(String c, InputStream x, int l) throws SQLException {
    delegate.updateAsciiStream(c, x, l);
  }

  @Override
  public void updateBinaryStream(String c, InputStream x, int l) throws SQLException {
    delegate.updateBinaryStream(c, x, l);
  }

  @Override
  public void updateCharacterStream(String c, Reader x, int l) throws SQLException {
    delegate.updateCharacterStream(c, x, l);
  }

  @Override
  public void updateObject(String c, Object x, int s) throws SQLException {
    delegate.updateObject(c, x, s);
  }

  @Override
  public void updateObject(String c, Object x) throws SQLException {
    delegate.updateObject(c, x);
  }

  @Override
  public void insertRow() throws SQLException {
    delegate.insertRow();
  }

  @Override
  public void updateRow() throws SQLException {
    delegate.updateRow();
  }

  @Override
  public void deleteRow() throws SQLException {
    delegate.deleteRow();
  }

  @Override
  public void refreshRow() throws SQLException {
    delegate.refreshRow();
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    delegate.cancelRowUpdates();
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    delegate.moveToInsertRow();
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    delegate.moveToCurrentRow();
  }

  @Override
  public Statement getStatement() throws SQLException {
    return delegate.getStatement();
  }

  @Override
  public Object getObject(int c, Map<String, Class<?>> m) throws SQLException {
    return delegate.getObject(c, m);
  }

  @Override
  public Ref getRef(int c) throws SQLException {
    return delegate.getRef(c);
  }

  @Override
  public Blob getBlob(int c) throws SQLException {
    return delegate.getBlob(c);
  }

  @Override
  public Clob getClob(int c) throws SQLException {
    return delegate.getClob(c);
  }

  @Override
  public Array getArray(int c) throws SQLException {
    return delegate.getArray(c);
  }

  @Override
  public Object getObject(String c, Map<String, Class<?>> m) throws SQLException {
    return delegate.getObject(c, m);
  }

  @Override
  public Ref getRef(String c) throws SQLException {
    return delegate.getRef(c);
  }

  @Override
  public Blob getBlob(String c) throws SQLException {
    return delegate.getBlob(c);
  }

  @Override
  public Clob getClob(String c) throws SQLException {
    return delegate.getClob(c);
  }

  @Override
  public Array getArray(String c) throws SQLException {
    return delegate.getArray(c);
  }

  @Override
  public Date getDate(int c, Calendar cal) throws SQLException {
    return delegate.getDate(c, cal);
  }

  @Override
  public Date getDate(String c, Calendar cal) throws SQLException {
    return delegate.getDate(c, cal);
  }

  @Override
  public Time getTime(int c, Calendar cal) throws SQLException {
    return delegate.getTime(c, cal);
  }

  @Override
  public Time getTime(String c, Calendar cal) throws SQLException {
    return delegate.getTime(c, cal);
  }

  @Override
  public Timestamp getTimestamp(int c, Calendar cal) throws SQLException {
    return delegate.getTimestamp(c, cal);
  }

  @Override
  public Timestamp getTimestamp(String c, Calendar cal) throws SQLException {
    return delegate.getTimestamp(c, cal);
  }

  @Override
  public URL getURL(int c) throws SQLException {
    return delegate.getURL(c);
  }

  @Override
  public URL getURL(String c) throws SQLException {
    return delegate.getURL(c);
  }

  @Override
  public void updateRef(int c, Ref x) throws SQLException {
    delegate.updateRef(c, x);
  }

  @Override
  public void updateRef(String c, Ref x) throws SQLException {
    delegate.updateRef(c, x);
  }

  @Override
  public void updateBlob(int c, Blob x) throws SQLException {
    delegate.updateBlob(c, x);
  }

  @Override
  public void updateBlob(String c, Blob x) throws SQLException {
    delegate.updateBlob(c, x);
  }

  @Override
  public void updateClob(int c, Clob x) throws SQLException {
    delegate.updateClob(c, x);
  }

  @Override
  public void updateClob(String c, Clob x) throws SQLException {
    delegate.updateClob(c, x);
  }

  @Override
  public void updateArray(int c, Array x) throws SQLException {
    delegate.updateArray(c, x);
  }

  @Override
  public void updateArray(String c, Array x) throws SQLException {
    delegate.updateArray(c, x);
  }

  @Override
  public RowId getRowId(int c) throws SQLException {
    return delegate.getRowId(c);
  }

  @Override
  public RowId getRowId(String c) throws SQLException {
    return delegate.getRowId(c);
  }

  @Override
  public void updateRowId(int c, RowId x) throws SQLException {
    delegate.updateRowId(c, x);
  }

  @Override
  public void updateRowId(String c, RowId x) throws SQLException {
    delegate.updateRowId(c, x);
  }

  @Override
  public int getHoldability() throws SQLException {
    return delegate.getHoldability();
  }

  @Override
  public boolean isClosed() throws SQLException {
    return delegate.isClosed();
  }

  @Override
  public void updateNString(int c, String s) throws SQLException {
    delegate.updateNString(c, s);
  }

  @Override
  public void updateNString(String c, String s) throws SQLException {
    delegate.updateNString(c, s);
  }

  @Override
  public void updateNClob(int c, NClob x) throws SQLException {
    delegate.updateNClob(c, x);
  }

  @Override
  public void updateNClob(String c, NClob x) throws SQLException {
    delegate.updateNClob(c, x);
  }

  @Override
  public NClob getNClob(int c) throws SQLException {
    return delegate.getNClob(c);
  }

  @Override
  public NClob getNClob(String c) throws SQLException {
    return delegate.getNClob(c);
  }

  @Override
  public SQLXML getSQLXML(int c) throws SQLException {
    return delegate.getSQLXML(c);
  }

  @Override
  public SQLXML getSQLXML(String c) throws SQLException {
    return delegate.getSQLXML(c);
  }

  @Override
  public void updateSQLXML(int c, SQLXML x) throws SQLException {
    delegate.updateSQLXML(c, x);
  }

  @Override
  public void updateSQLXML(String c, SQLXML x) throws SQLException {
    delegate.updateSQLXML(c, x);
  }

  @Override
  public String getNString(int c) throws SQLException {
    return delegate.getNString(c);
  }

  @Override
  public String getNString(String c) throws SQLException {
    return delegate.getNString(c);
  }

  @Override
  public Reader getNCharacterStream(int c) throws SQLException {
    return delegate.getNCharacterStream(c);
  }

  @Override
  public Reader getNCharacterStream(String c) throws SQLException {
    return delegate.getNCharacterStream(c);
  }

  @Override
  public void updateNCharacterStream(int c, Reader x, long l) throws SQLException {
    delegate.updateNCharacterStream(c, x, l);
  }

  @Override
  public void updateNCharacterStream(String c, Reader x, long l) throws SQLException {
    delegate.updateNCharacterStream(c, x, l);
  }

  @Override
  public void updateAsciiStream(int c, InputStream x, long l) throws SQLException {
    delegate.updateAsciiStream(c, x, l);
  }

  @Override
  public void updateBinaryStream(int c, InputStream x, long l) throws SQLException {
    delegate.updateBinaryStream(c, x, l);
  }

  @Override
  public void updateCharacterStream(int c, Reader x, long l) throws SQLException {
    delegate.updateCharacterStream(c, x, l);
  }

  @Override
  public void updateAsciiStream(String c, InputStream x, long l) throws SQLException {
    delegate.updateAsciiStream(c, x, l);
  }

  @Override
  public void updateBinaryStream(String c, InputStream x, long l) throws SQLException {
    delegate.updateBinaryStream(c, x, l);
  }

  @Override
  public void updateCharacterStream(String c, Reader x, long l) throws SQLException {
    delegate.updateCharacterStream(c, x, l);
  }

  @Override
  public void updateBlob(int c, InputStream x, long l) throws SQLException {
    delegate.updateBlob(c, x, l);
  }

  @Override
  public void updateBlob(String c, InputStream x, long l) throws SQLException {
    delegate.updateBlob(c, x, l);
  }

  @Override
  public void updateClob(int c, Reader x, long l) throws SQLException {
    delegate.updateClob(c, x, l);
  }

  @Override
  public void updateClob(String c, Reader x, long l) throws SQLException {
    delegate.updateClob(c, x, l);
  }

  @Override
  public void updateNClob(int c, Reader x, long l) throws SQLException {
    delegate.updateNClob(c, x, l);
  }

  @Override
  public void updateNClob(String c, Reader x, long l) throws SQLException {
    delegate.updateNClob(c, x, l);
  }

  @Override
  public <T> T getObject(int c, Class<T> t) throws SQLException {
    return delegate.getObject(c, t);
  }

  @Override
  public <T> T getObject(String c, Class<T> t) throws SQLException {
    return delegate.getObject(c, t);
  }

  @Override
  public void updateNCharacterStream(int c, Reader x) throws SQLException {
    delegate.updateNCharacterStream(c, x);
  }

  @Override
  public void updateNCharacterStream(String c, Reader x) throws SQLException {
    delegate.updateNCharacterStream(c, x);
  }

  @Override
  public void updateAsciiStream(int c, InputStream x) throws SQLException {
    delegate.updateAsciiStream(c, x);
  }

  @Override
  public void updateBinaryStream(int c, InputStream x) throws SQLException {
    delegate.updateBinaryStream(c, x);
  }

  @Override
  public void updateCharacterStream(int c, Reader x) throws SQLException {
    delegate.updateCharacterStream(c, x);
  }

  @Override
  public void updateAsciiStream(String c, InputStream x) throws SQLException {
    delegate.updateAsciiStream(c, x);
  }

  @Override
  public void updateBinaryStream(String c, InputStream x) throws SQLException {
    delegate.updateBinaryStream(c, x);
  }

  @Override
  public void updateCharacterStream(String c, Reader x) throws SQLException {
    delegate.updateCharacterStream(c, x);
  }

  @Override
  public void updateBlob(int c, InputStream x) throws SQLException {
    delegate.updateBlob(c, x);
  }

  @Override
  public void updateBlob(String c, InputStream x) throws SQLException {
    delegate.updateBlob(c, x);
  }

  @Override
  public void updateClob(int c, Reader x) throws SQLException {
    delegate.updateClob(c, x);
  }

  @Override
  public void updateClob(String c, Reader x) throws SQLException {
    delegate.updateClob(c, x);
  }

  @Override
  public void updateNClob(int c, Reader x) throws SQLException {
    delegate.updateNClob(c, x);
  }

  @Override
  public void updateNClob(String c, Reader x) throws SQLException {
    delegate.updateNClob(c, x);
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
