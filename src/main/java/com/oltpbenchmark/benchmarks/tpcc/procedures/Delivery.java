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

package com.oltpbenchmark.benchmarks.tpcc.procedures;

import com.oltpbenchmark.api.SQLStmt;
import com.oltpbenchmark.benchmarks.tpcc.TPCCConfig;
import com.oltpbenchmark.benchmarks.tpcc.TPCCConstants;
import com.oltpbenchmark.benchmarks.tpcc.TPCCUtil;
import com.oltpbenchmark.benchmarks.tpcc.TPCCWorker;
import com.oltpbenchmark.types.DatabaseType;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Delivery extends TPCCProcedure {

  private static final Logger LOG = LoggerFactory.getLogger(Delivery.class);

  public SQLStmt delivGetOrderIdSQL =
      new SQLStmt(
          """
            SELECT NO_O_ID FROM %s
             WHERE NO_D_ID = ?
               AND NO_W_ID = ?
             ORDER BY NO_O_ID ASC
             LIMIT 1
        """
              .formatted(TPCCConstants.TABLENAME_NEWORDER));

  public SQLStmt delivDeleteNewOrderSQL =
      new SQLStmt(
          """
            DELETE FROM %s
            WHERE NO_O_ID = ?
            AND NO_D_ID = ?
            AND NO_W_ID = ?
        """
              .formatted(TPCCConstants.TABLENAME_NEWORDER));

  public SQLStmt delivGetCustIdSQL =
      new SQLStmt(
          """
            SELECT O_C_ID FROM %s
            WHERE O_ID = ?
            AND O_D_ID = ?
            AND O_W_ID = ?
        """
              .formatted(TPCCConstants.TABLENAME_OPENORDER));

  public SQLStmt delivUpdateCarrierIdSQL =
      new SQLStmt(
          """
        UPDATE %s
           SET O_CARRIER_ID = ?
         WHERE O_ID = ?
           AND O_D_ID = ?
           AND O_W_ID = ?
    """
              .formatted(TPCCConstants.TABLENAME_OPENORDER));

  public SQLStmt delivUpdateDeliveryDateSQL =
      new SQLStmt(
          """
        UPDATE %s
           SET OL_DELIVERY_D = ?
         WHERE OL_O_ID = ?
           AND OL_D_ID = ?
           AND OL_W_ID = ?
    """
              .formatted(TPCCConstants.TABLENAME_ORDERLINE));

  public SQLStmt delivSumOrderAmountSQL =
      new SQLStmt(
          """
        SELECT SUM(OL_AMOUNT) AS OL_TOTAL
          FROM %s
         WHERE OL_O_ID = ?
           AND OL_D_ID = ?
           AND OL_W_ID = ?
    """
              .formatted(TPCCConstants.TABLENAME_ORDERLINE));

  public SQLStmt delivUpdateCustBalDelivCntSQL =
      new SQLStmt(
          """
        UPDATE %s
           SET C_BALANCE = C_BALANCE + ?,
               C_DELIVERY_CNT = C_DELIVERY_CNT + 1
         WHERE C_W_ID = ?
           AND C_D_ID = ?
           AND C_ID = ?
    """
              .formatted(TPCCConstants.TABLENAME_CUSTOMER));

  // Regatta: combined per-district DELETE + RETURNING NO_O_ID.
  // Replaces the two-step delivGetOrderIdSQL + delivDeleteNewOrderSQL.
  public SQLStmt delivDeleteReturningSQL =
      new SQLStmt(
          """
              DELETE FROM %s
               WHERE NO_O_ID = ?
                 AND NO_D_ID = ?
                 AND NO_W_ID = ?
          """
              .formatted(TPCCConstants.TABLENAME_NEWORDER));

  /** Per-district info collected in Phase 1 of the Regatta delivery flow. */
  private record DistrictOrder(int dId, int noOId, long oKey) {}

  public void run(
      Connection conn,
      Random gen,
      int w_id,
      int numWarehouses,
      int terminalDistrictLowerID,
      int terminalDistrictUpperID,
      TPCCWorker w)
      throws SQLException {

    int o_carrier_id = TPCCUtil.randomNumber(1, 10, gen);

    int d_id;

    int[] orderIDs = new int[10];

    if (this.getDbType() == DatabaseType.REGATTA) {
      // Phase 1: per-district DELETE RETURNING — collapses getOrderId + deleteOrder.
      List<DistrictOrder> activeOrders =
          deleteOrdersAndCollect(conn, w_id, terminalDistrictUpperID, orderIDs);

      if (!activeOrders.isEmpty()) {
        // Phase 2a: batch UPDATE OORDER carrier → returns O_KEY, O_C_ID.
        Map<Long, Integer> oKeyToCustId =
            batchUpdateCarrierAndGetCustIds(conn, activeOrders, o_carrier_id);

        // Phase 2b: batch SUM(OL_AMOUNT) per order.
        Map<Long, Float> oKeyToTotal = batchSumOrderAmounts(conn, activeOrders);

        // Phase 2c: batch UPDATE ORDER_LINE delivery date.
        batchUpdateDeliveryDate(conn, activeOrders);

        // Phase 3: single CASE-based UPDATE CUSTOMER.
        batchUpdateCustomerBalances(conn, w_id, activeOrders, oKeyToCustId, oKeyToTotal);
      }
    } else {
      for (d_id = 1; d_id <= terminalDistrictUpperID; d_id++) {
        Integer no_o_id = getOrderId(conn, w_id, d_id);

        if (no_o_id == null) {
          continue;
        }

        orderIDs[d_id - 1] = no_o_id;

        deleteOrder(conn, w_id, d_id, no_o_id);

        int customerId = getCustomerId(conn, w_id, d_id, no_o_id);

        updateCarrierId(conn, w_id, o_carrier_id, d_id, no_o_id);

        updateDeliveryDate(conn, w_id, d_id, no_o_id);

        float orderLineTotal = getOrderLineTotal(conn, w_id, d_id, no_o_id);

        updateBalanceAndDelivery(conn, w_id, d_id, customerId, orderLineTotal);
      }
    }

    if (LOG.isTraceEnabled()) {
      StringBuilder terminalMessage = new StringBuilder();
      terminalMessage.append(
          "\n+---------------------------- DELIVERY ---------------------------+\n");
      terminalMessage.append(" Date: ");
      terminalMessage.append(TPCCUtil.getCurrentTime());
      terminalMessage.append("\n\n Warehouse: ");
      terminalMessage.append(w_id);
      terminalMessage.append("\n Carrier:   ");
      terminalMessage.append(o_carrier_id);
      terminalMessage.append("\n\n Delivered Orders\n");
      for (int i = 1; i <= TPCCConfig.configDistPerWhse; i++) {
        if (orderIDs[i - 1] >= 0) {
          terminalMessage.append("  District ");
          terminalMessage.append(i < 10 ? " " : "");
          terminalMessage.append(i);
          terminalMessage.append(": Order number ");
          terminalMessage.append(orderIDs[i - 1]);
          terminalMessage.append(" was delivered.\n");
        }
      }
      terminalMessage.append(
          "+-----------------------------------------------------------------+\n\n");
      LOG.trace(terminalMessage.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // Regatta-specific batched helpers
  // ---------------------------------------------------------------------------

  private List<DistrictOrder> deleteOrdersAndCollect(
      Connection conn, int w_id, int numDistricts, int[] orderIDs) throws SQLException {
    List<DistrictOrder> result = new ArrayList<>(numDistricts);
    try (PreparedStatement stmt = this.getPreparedStatement(conn, delivDeleteReturningSQL)) {
      for (int dId = 1; dId <= numDistricts; dId++) {
        stmt.setLong(1, TPCCUtil.concatOrderKey(w_id, dId, 1));
        stmt.setLong(2, TPCCUtil.concatOrderKey(w_id, dId, Integer.MAX_VALUE));
        try (ResultSet rs = stmt.executeQuery()) {
          if (!rs.next()) {
            LOG.warn("District has no new orders [W_ID={}, D_ID={}]", w_id, dId);
            continue;
          }
          int noOId = rs.getInt(1);
          long oKey = TPCCUtil.concatOrderKey(w_id, dId, noOId);
          orderIDs[dId - 1] = noOId;
          result.add(new DistrictOrder(dId, noOId, oKey));
        }
      }
    }
    return result;
  }

  private Map<Long, Integer> batchUpdateCarrierAndGetCustIds(
      Connection conn, List<DistrictOrder> activeOrders, int carrierId) throws SQLException {
    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE /*++ INDEX(OORDER, COLUMN O_KEY)*/ ")
        .append(TPCCConstants.TABLENAME_OPENORDER)
        .append(" SET O_CARRIER_ID = ")
        .append(carrierId)
        .append(" WHERE O_KEY IN (");
    boolean first = true;
    for (DistrictOrder order : activeOrders) {
      if (!first) sql.append(", ");
      sql.append(order.oKey());
      first = false;
    }
    sql.append(") RETURNING O_KEY, O_C_ID");

    Map<Long, Integer> oKeyToCustId = new LinkedHashMap<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql.toString())) {
      while (rs.next()) {
        // RETURNING O_KEY, O_C_ID.
        oKeyToCustId.put(rs.getLong(1), rs.getInt(2));
      }
    }
    if (oKeyToCustId.size() != activeOrders.size()) {
      throw new RuntimeException(
          "batchUpdateCarrier: expected "
              + activeOrders.size()
              + " rows updated, got "
              + oKeyToCustId.size());
    }
    return oKeyToCustId;
  }

  private Map<Long, Float> batchSumOrderAmounts(Connection conn, List<DistrictOrder> activeOrders)
      throws SQLException {
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT /*++ INDEX(ORDER_LINE, COLUMN OL_O_KEY)*/ OL_O_KEY,")
        .append(" SUM(OL_AMOUNT) AS OL_TOTAL")
        .append(" FROM ")
        .append(TPCCConstants.TABLENAME_ORDERLINE)
        .append(" WHERE OL_O_KEY IN (");
    boolean first = true;
    for (DistrictOrder order : activeOrders) {
      if (!first) sql.append(", ");
      sql.append(order.oKey());
      first = false;
    }
    sql.append(") GROUP BY OL_O_KEY");

    Map<Long, Float> oKeyToTotal = new LinkedHashMap<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql.toString())) {
      while (rs.next()) {
        // SELECT OL_O_KEY, SUM(OL_AMOUNT) AS OL_TOTAL.
        oKeyToTotal.put(rs.getLong(1), rs.getFloat(2));
      }
    }
    return oKeyToTotal;
  }

  private void batchUpdateDeliveryDate(Connection conn, List<DistrictOrder> activeOrders)
      throws SQLException {
    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE /*++ INDEX(ORDER_LINE, COLUMN OL_O_KEY)*/ ")
        .append(TPCCConstants.TABLENAME_ORDERLINE)
        .append(" SET OL_DELIVERY_D = '")
        .append(timestamp)
        .append("' WHERE OL_O_KEY IN (");
    boolean first = true;
    for (DistrictOrder order : activeOrders) {
      if (!first) sql.append(", ");
      sql.append(order.oKey());
      first = false;
    }
    sql.append(")");

    try (Statement stmt = conn.createStatement()) {
      int result = stmt.executeUpdate(sql.toString());
      if (result == 0) {
        throw new RuntimeException("batchUpdateDeliveryDate: no ORDER_LINE rows updated");
      }
    }
  }

  private void batchUpdateCustomerBalances(
      Connection conn,
      int w_id,
      List<DistrictOrder> activeOrders,
      Map<Long, Integer> oKeyToCustId,
      Map<Long, Float> oKeyToTotal)
      throws SQLException {
    List<long[]> custKeyAndTotalBits = new ArrayList<>(activeOrders.size());
    for (DistrictOrder order : activeOrders) {
      Integer cId = oKeyToCustId.get(order.oKey());
      Float total = oKeyToTotal.get(order.oKey());
      if (cId == null || total == null) {
        throw new RuntimeException(
            "batchUpdateCustomerBalances: missing data for O_KEY=" + order.oKey());
      }
      long cKey = TPCCUtil.concatCustomerKey(w_id, order.dId(), cId);
      custKeyAndTotalBits.add(new long[] {cKey, Float.floatToRawIntBits(total)});
    }

    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE /*++ INDEX(CUSTOMER, COLUMN C_KEY)*/ ")
        .append(TPCCConstants.TABLENAME_CUSTOMER)
        .append(
            " SET C_DELIVERY_CNT = C_DELIVERY_CNT + 1,\n       C_BALANCE = C_BALANCE + CASE C_KEY");
    for (long[] entry : custKeyAndTotalBits) {
      float total = Float.intBitsToFloat((int) entry[1]);
      sql.append("\n        WHEN ").append(entry[0]).append(" THEN ").append(total);
    }
    sql.append("\n       END\n WHERE C_KEY IN (");
    boolean first = true;
    for (long[] entry : custKeyAndTotalBits) {
      if (!first) sql.append(", ");
      sql.append(entry[0]);
      first = false;
    }
    sql.append(")");

    try (Statement stmt = conn.createStatement()) {
      int result = stmt.executeUpdate(sql.toString());
      if (result == 0) {
        LOG.warn("batchUpdateCustomerBalances: no CUSTOMER rows updated");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Original per-district helpers (non-Regatta path, unchanged)
  // ---------------------------------------------------------------------------

  private Integer getOrderId(Connection conn, int w_id, int d_id) throws SQLException {

    try (PreparedStatement delivGetOrderId = this.getPreparedStatement(conn, delivGetOrderIdSQL)) {
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivGetOrderId.setLong(1, TPCCUtil.concatOrderKey(w_id, d_id, 1));
        delivGetOrderId.setLong(2, TPCCUtil.concatOrderKey(w_id, d_id, Integer.MAX_VALUE));
      } else {
        delivGetOrderId.setInt(1, d_id);
        delivGetOrderId.setInt(2, w_id);
      }

      try (ResultSet rs = delivGetOrderId.executeQuery()) {

        if (!rs.next()) {
          // This district has no new orders.  This can happen but should be rare

          LOG.warn(String.format("District has no new orders [W_ID=%d, D_ID=%d]", w_id, d_id));

          return null;
        }

        return rs.getInt("NO_O_ID");
      }
    }
  }

  private void deleteOrder(Connection conn, int w_id, int d_id, int no_o_id) throws SQLException {
    try (PreparedStatement delivDeleteNewOrder =
        this.getPreparedStatement(conn, delivDeleteNewOrderSQL)) {
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivDeleteNewOrder.setLong(1, TPCCUtil.concatOrderKey(w_id, d_id, no_o_id));
      } else {
        delivDeleteNewOrder.setInt(1, no_o_id);
        delivDeleteNewOrder.setInt(2, d_id);
        delivDeleteNewOrder.setInt(3, w_id);
      }

      int result = delivDeleteNewOrder.executeUpdate();

      if (result != 1) {
        // This code used to run in a loop in an attempt to make this work
        // with MySQL's default weird consistency level. We just always run
        // this as SERIALIZABLE instead. I don't *think* that fixing this one
        // error makes this work with MySQL's default consistency.
        // Careful auditing would be required.
        String msg =
            String.format(
                "NewOrder delete failed. Not running with SERIALIZABLE isolation? [w_id=%d, d_id=%d, no_o_id=%d]",
                w_id, d_id, no_o_id);
        throw new UserAbortException(msg);
      }
    }
  }

  private int getCustomerId(Connection conn, int w_id, int d_id, int no_o_id) throws SQLException {

    try (PreparedStatement delivGetCustId = this.getPreparedStatement(conn, delivGetCustIdSQL)) {
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivGetCustId.setLong(1, TPCCUtil.concatOrderKey(w_id, d_id, no_o_id));
      } else {
        delivGetCustId.setInt(1, no_o_id);
        delivGetCustId.setInt(2, d_id);
        delivGetCustId.setInt(3, w_id);
      }

      try (ResultSet rs = delivGetCustId.executeQuery()) {

        if (!rs.next()) {
          String msg =
              String.format(
                  "Failed to retrieve ORDER record [W_ID=%d, D_ID=%d, O_ID=%d]",
                  w_id, d_id, no_o_id);
          throw new RuntimeException(msg);
        }

        return rs.getInt("O_C_ID");
      }
    }
  }

  private void updateCarrierId(Connection conn, int w_id, int o_carrier_id, int d_id, int no_o_id)
      throws SQLException {
    try (PreparedStatement delivUpdateCarrierId =
        this.getPreparedStatement(conn, delivUpdateCarrierIdSQL)) {
      delivUpdateCarrierId.setInt(1, o_carrier_id);
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivUpdateCarrierId.setLong(2, TPCCUtil.concatOrderKey(w_id, d_id, no_o_id));
      } else {
        delivUpdateCarrierId.setInt(2, no_o_id);
        delivUpdateCarrierId.setInt(3, d_id);
        delivUpdateCarrierId.setInt(4, w_id);
      }

      int result = delivUpdateCarrierId.executeUpdate();

      if (result != 1) {
        String msg =
            String.format(
                "Failed to update ORDER record [W_ID=%d, D_ID=%d, O_ID=%d]", w_id, d_id, no_o_id);
        throw new RuntimeException(msg);
      }
    }
  }

  private void updateDeliveryDate(Connection conn, int w_id, int d_id, int no_o_id)
      throws SQLException {
    Timestamp timestamp = new Timestamp(System.currentTimeMillis());

    try (PreparedStatement delivUpdateDeliveryDate =
        this.getPreparedStatement(conn, delivUpdateDeliveryDateSQL)) {
      delivUpdateDeliveryDate.setTimestamp(1, timestamp);
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivUpdateDeliveryDate.setLong(2, TPCCUtil.concatOrderKey(w_id, d_id, no_o_id));
      } else {
        delivUpdateDeliveryDate.setInt(2, no_o_id);
        delivUpdateDeliveryDate.setInt(3, d_id);
        delivUpdateDeliveryDate.setInt(4, w_id);
      }

      int result = delivUpdateDeliveryDate.executeUpdate();

      if (result == 0) {
        String msg =
            String.format(
                "Failed to update ORDER_LINE records [W_ID=%d, D_ID=%d, O_ID=%d]",
                w_id, d_id, no_o_id);
        throw new RuntimeException(msg);
      }
    }
  }

  private float getOrderLineTotal(Connection conn, int w_id, int d_id, int no_o_id)
      throws SQLException {
    try (PreparedStatement delivSumOrderAmount =
        this.getPreparedStatement(conn, delivSumOrderAmountSQL)) {
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivSumOrderAmount.setLong(1, TPCCUtil.concatOrderKey(w_id, d_id, no_o_id));
      } else {
        delivSumOrderAmount.setInt(1, no_o_id);
        delivSumOrderAmount.setInt(2, d_id);
        delivSumOrderAmount.setInt(3, w_id);
      }

      try (ResultSet rs = delivSumOrderAmount.executeQuery()) {
        if (!rs.next()) {
          String msg =
              String.format(
                  "Failed to retrieve ORDER_LINE records [W_ID=%d, D_ID=%d, O_ID=%d]",
                  w_id, d_id, no_o_id);
          throw new RuntimeException(msg);
        }

        return rs.getFloat("OL_TOTAL");
      }
    }
  }

  private void updateBalanceAndDelivery(
      Connection conn, int w_id, int d_id, int c_id, float orderLineTotal) throws SQLException {

    try (PreparedStatement delivUpdateCustBalDelivCnt =
        this.getPreparedStatement(conn, delivUpdateCustBalDelivCntSQL)) {
      delivUpdateCustBalDelivCnt.setBigDecimal(1, BigDecimal.valueOf(orderLineTotal));
      if (this.getDbType() == DatabaseType.REGATTA) {
        delivUpdateCustBalDelivCnt.setLong(2, TPCCUtil.concatCustomerKey(w_id, d_id, c_id));
      } else {
        delivUpdateCustBalDelivCnt.setInt(2, w_id);
        delivUpdateCustBalDelivCnt.setInt(3, d_id);
        delivUpdateCustBalDelivCnt.setInt(4, c_id);
      }

      int result = delivUpdateCustBalDelivCnt.executeUpdate();

      if (result == 0) {
        String msg =
            String.format(
                "Failed to update CUSTOMER record [W_ID=%d, D_ID=%d, C_ID=%d]", w_id, d_id, c_id);
        throw new RuntimeException(msg);
      }
    }
  }
}
