// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.pgsql;

import org.apache.commons.lang3.RandomUtils;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.postgresql.core.TransactionState;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.minicluster.MiniYBClusterBuilder;
import org.yb.util.RandomNumberUtil;
import org.yb.util.SanitizerUtil;
import org.yb.util.YBTestRunnerNonTsanOnly;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import static org.yb.AssertionWrappers.*;

@RunWith(value=YBTestRunnerNonTsanOnly.class)
public class TestPgTransactions extends BasePgSQLTest {

  // A "skew" of 0.3 means that we expect the difference between the win percentage of the first
  // and the second txn would be under 30% of the total number of attempts, i.e. if one transaction
  // wins in 35% of cases and the other wins in 65% cases, we're still fine.
  private static final double DEFAULT_SKEW_THRESHOLD = 0.3;

  private static final Logger LOG = LoggerFactory.getLogger(TestPgTransactions.class);

  private static boolean isYBTransactionError(PSQLException ex) {
    String msg = ex.getMessage();
    // TODO: test for error codes here when we move to more PostgreSQL-friendly transaction errors.
    return (
        msg.contains("Conflicts with higher priority transaction") ||
        msg.contains("Transaction aborted") ||
        msg.contains("Transaction expired") ||
        msg.contains("Restart read required") ||
        msg.contains("Conflicts with committed transaction") ||
        msg.contains(
            "current transaction is aborted, commands ignored until end of transaction block")) ||
        msg.contains("Value write after transaction start");
  }

  private void checkTransactionFairness(
      int numFirstWinners,
      int numSecondWinners,
      int totalIterations,
      double skewThreshold) {
    double skew = Math.abs(numFirstWinners - numSecondWinners) * 1.0 / totalIterations;
    LOG.info("Skew between the number of wins by two connections: " + skew);
    assertTrue("Expecting the skew to be below the threshold " + skewThreshold + ", got " + skew,
        skew < skewThreshold);
  }

  @Override
  protected void customizeMiniClusterBuilder(MiniYBClusterBuilder builder) {
    super.customizeMiniClusterBuilder(builder);
    builder.enablePgTransactions(true);
  }

  @Test
  public void testTableWithoutPrimaryKey() throws Exception {
    Statement statement = connection.createStatement();
    statement.execute("CREATE TABLE t (thread_id TEXT, attempt_id TEXT, k INT, v INT)");
    final int NUM_THREADS = 4;
    final int NUM_INCREMENTS_PER_THREAD = 100;
    ExecutorService ecs = Executors.newFixedThreadPool(NUM_THREADS);
    final AtomicBoolean hadErrors = new AtomicBoolean();
    for (int i = 1; i <= NUM_THREADS; ++i) {
      final int threadIndex = i;
      ecs.submit(() -> {
        LOG.info("Workload thread " + threadIndex + " starting");
        int numIncrements = 0;
        try (Connection conn =
                createConnection(IsolationLevel.REPEATABLE_READ, AutoCommit.ENABLED)) {
          Statement stmt = conn.createStatement();
          int currentValue = 0x01010100 * threadIndex;
          stmt.execute("INSERT INTO t (thread_id, attempt_id, k, v) VALUES (" +
              "'thread_" + threadIndex + "', 'thread_" + threadIndex + "_attempt_0" +
              "', " + threadIndex + ", " + currentValue + ")");
          int attemptIndex = 1;
          while (numIncrements < NUM_INCREMENTS_PER_THREAD && !hadErrors.get()) {
            String attemptId = "thread_" + threadIndex + "_attempt_" + attemptIndex;
            try {
              LOG.info("Thread " + threadIndex + ": trying to update from " +
                       numIncrements + " to " + (numIncrements + 1));
              int rowsUpdated =
                  stmt.executeUpdate("UPDATE t SET v = v + 1, attempt_id = '" + attemptId +
                      "' WHERE k = " + threadIndex);
              assertEquals(1, rowsUpdated);

              numIncrements++;
              currentValue++;
              LOG.info("Thread " + threadIndex + " is verifying the value at attemptIndex=" +
                       attemptIndex + ": attemptId=" + attemptId);

              ResultSet res = stmt.executeQuery(
                  "SELECT attempt_id, v FROM t WHERE k = " + threadIndex);
              LOG.info(
                  "Thread " + threadIndex + " finished reading the result after attemptIndex=" +
                  attemptIndex);

              assertTrue(res.next());
              int value = res.getInt("v");
              if (value != currentValue) {
                assertEquals(
                    "Invalid result in thread " + threadIndex + ", attempt index " + attemptIndex +
                    ", num increments reported as successful: " + numIncrements +
                    ", expected value (hex): " + String.format("0x%08X", currentValue) +
                    ", actual value (hex): " + String.format("0x%08X", value) +
                    ", attempt id: " + attemptId,
                    currentValue, value);
                hadErrors.set(true);
              }
            } catch (PSQLException ex) {
              LOG.warn("Error updating/verifying in thread " + threadIndex);
            }
            attemptIndex++;
          }
        } catch (Exception ex) {
          LOG.error("Exception in thread " + threadIndex, ex);
          hadErrors.set(true);
        } finally {
          LOG.info("Workload thread " + threadIndex + " exiting, numIncrements=" + numIncrements);
        }
      });
    }
    ecs.shutdown();
    ecs.awaitTermination(30, TimeUnit.SECONDS);
    ResultSet rsAll = statement.executeQuery("SELECT k, v FROM t");
    while (rsAll.next()) {
      LOG.info("Row found at the end: k=" + rsAll.getInt("k") + ", v=" + rsAll.getInt("v"));
    }

    for (int i = 1; i <= NUM_THREADS; ++i) {
      ResultSet rs = statement.executeQuery(
          "SELECT v FROM t WHERE k = " + i);
      assertTrue("Did not find any values with k=" + i, rs.next());
      int v = rs.getInt("v");
      LOG.info("Value for k=" + i + ": " + v);
      assertEquals(0x01010100 * i + NUM_INCREMENTS_PER_THREAD, v);
    }
    assertFalse("Test had errors, look for 'Exception in thread' above", hadErrors.get());
  }

  @Test
  public void testSnapshotReadDelayWrite() throws Exception {
    runReadDelayWriteTest(IsolationLevel.REPEATABLE_READ);
  }

  @Test
  public void testSerializableReadDelayWrite() throws Exception {
    runReadDelayWriteTest(IsolationLevel.SERIALIZABLE);
  }

  private void runReadDelayWriteTest(final IsolationLevel isolationLevel) throws Exception {
    Connection setupConn = createConnection(isolationLevel, AutoCommit.ENABLED);
    Statement statement = connection.createStatement();
    statement.execute("CREATE TABLE counters (k INT PRIMARY KEY, v INT)");

    final int INCREMENTS_PER_THREAD = 250;
    final int NUM_COUNTERS = 2;
    final int numTServers = miniCluster.getNumTServers();
    final int numThreads = 4;

    // Initialize counters.
    {
      PreparedStatement insertStmt = connection.prepareStatement(
          "INSERT INTO counters (k, v) VALUES (?, ?)");
      for (int i = 0; i < NUM_COUNTERS; ++i) {
        insertStmt.setInt(1, i);
        insertStmt.setInt(2, 0);
        insertStmt.executeUpdate();
      }
    }

    List<Thread> threads = new ArrayList<Thread>();
    AtomicBoolean hadErrors = new AtomicBoolean(false);

    final Object lastValueLock = new Object();
    final int[] numIncrementsByCounter = new int[NUM_COUNTERS];

    for (int i = 1; i <= numThreads; ++i) {
      final int threadIndex = i;
      threads.add(new Thread(() -> {
        LOG.info("Workload thread " + threadIndex + " is starting");
        Random rand = new Random(System.currentTimeMillis() * 137 + threadIndex);
        int numIncrementsDone = 0;
        try (Connection conn = createPgConnectionToTServer(
                threadIndex % numTServers,
                isolationLevel,
                AutoCommit.DISABLED)) {
          PreparedStatement selectStmt = conn.prepareStatement(
              "SELECT v FROM counters WHERE k = ?");
          PreparedStatement updateStmt = conn.prepareStatement(
              "UPDATE counters SET v = ? WHERE k = ?");
          long attemptId =
              1000 * 1000 * 1000L * threadIndex +
              1000 * 1000L * Math.abs(RandomNumberUtil.getRandomGenerator().nextInt(1000));
          while (numIncrementsDone < INCREMENTS_PER_THREAD && !hadErrors.get()) {
            ++attemptId;
            boolean committed = false;
            try {
              int counterIndex = rand.nextInt(NUM_COUNTERS);

              selectStmt.setInt(1, counterIndex);

              // The value of the counter that we'll read should be
              int initialValueLowerBound;
              synchronized (lastValueLock) {
                initialValueLowerBound = numIncrementsByCounter[counterIndex];
              }

              ResultSet rs = selectStmt.executeQuery();
              assertTrue(rs.next());
              int currentValue = rs.getInt(1);
              int delayMs = 2 + rand.nextInt(15);
              LOG.info(
                  "Thread " + threadIndex + " read counter " + counterIndex + ", got value " +
                  currentValue +
                  (currentValue == initialValueLowerBound
                      ? " as expected"
                      : " and expected to get at least " + initialValueLowerBound) +
                  ", will sleep for " + delayMs + " ms. attemptId=" + attemptId);

              Thread.sleep(delayMs);
              LOG.info("Thread " + threadIndex + " finished sleeping for " + delayMs + " ms" +
                       ", attemptId=" + attemptId);
              if (hadErrors.get()) {
                LOG.info("Thread " + threadIndex + " is exiting in the middle of an iteration " +
                         "because errors happened in another thread.");
                break;
              }

              int updatedValue = currentValue + 1;
              updateStmt.setInt(1, updatedValue);
              updateStmt.setInt(2, counterIndex);
              assertEquals(1, updateStmt.executeUpdate());
              LOG.info("Thread " + threadIndex + " successfully updated value of counter " +
                       counterIndex + " to " + updatedValue +", attemptId=" + attemptId);

              conn.commit();
              committed = true;
              LOG.info(
                  "Thread " + threadIndex + " successfully committed value " + updatedValue +
                  " to counter " + counterIndex + ". attemptId=" + attemptId);

              synchronized (lastValueLock) {
                numIncrementsByCounter[counterIndex]++;
              }
              numIncrementsDone++;

              if (currentValue < initialValueLowerBound) {
                assertTrue(
                    "IMPORTANT ERROR. In thread " + threadIndex + ": " +
                    "expected counter " + counterIndex + " to be at least at " +
                        initialValueLowerBound + " at the beginning of a successful increment, " +
                        "but got " + currentValue + ". attemptId=" + attemptId,
                    false);
                hadErrors.set(true);
              }
            } catch (PSQLException ex) {
              if (!isYBTransactionError(ex)) {
                throw ex;
              }
              LOG.info(
                  "Got an exception in thread " + threadIndex + ", attemptId=" + attemptId, ex);
            } finally {
              if (!committed) {
                LOG.info("Rolling back the transaction on thread " + threadIndex +
                         ", attemptId=" + attemptId);
                conn.rollback();
              }
            }
          }
        } catch (Exception ex) {
          LOG.error("Unhandled exception in thread " + threadIndex, ex);
          hadErrors.set(true);
        } finally {
          LOG.info("Workload thread " + threadIndex +
                   " has finished, numIncrementsDone=" + numIncrementsDone);
        }
      }));
    }

    for (Thread thread : threads) {
      thread.start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    Statement checkStmt = connection.createStatement();
    ResultSet finalResult = checkStmt.executeQuery("SELECT k, v FROM counters");
    int total = 0;
    while (finalResult.next()) {
      int k = finalResult.getInt("k");
      int v = finalResult.getInt("v");
      LOG.info("Final row: k=" + k + " v=" + v);
      total += v;
    }

    int expectedResult = 0;
    for (int i = 0; i < NUM_COUNTERS; ++i) {
      expectedResult += numIncrementsByCounter[i];
    }
    assertEquals(expectedResult, total);

    assertFalse("Had errors", hadErrors.get());
  }

  @Test
  public void testSerializableWholeHashVsScanConflict() throws Exception {
    createSimpleTable("test", "v", PartitioningMode.HASH);
    final IsolationLevel isolation = IsolationLevel.SERIALIZABLE;
    Connection connection1 = createConnection(isolation, AutoCommit.DISABLED);
    Statement statement1 = connection1.createStatement();

    Connection connection2 = createConnection(isolation, AutoCommit.ENABLED);
    Statement statement2 = connection2.createStatement();

    int numSuccess1 = 0;
    int numSuccess2 = 0;
    final int TOTAL_ITERATIONS = 100;
    for (int i = 0; i < TOTAL_ITERATIONS; ++i) {
      int h1 = i;
      LOG.debug("Inserting the first row within a transaction but not committing yet");
      statement1.execute("INSERT INTO test(h, r, v) VALUES (" + h1 + ", 2, 3)");

      LOG.debug("Trying to read the first row from another connection");

      PSQLException ex2 = null;
      try {
        assertFalse(statement2.executeQuery("SELECT h, r, v FROM test WHERE h = " + h1).next());
      } catch (PSQLException ex) {
        ex2 = ex;
      }

      PSQLException ex1 = null;
      try {
        connection1.commit();
      } catch (PSQLException ex) {
        ex1 = ex;
      }

      final boolean succeeded1 = ex1 == null;
      final boolean succeeded2 = ex2 == null;
      assertNotEquals("Expecting exactly one transaction to succeed", succeeded1, succeeded2);
      LOG.info("ex1=" + ex1);
      LOG.info("ex2=" + ex2);
      if (ex1 != null) {
        assertTrue(isYBTransactionError(ex1));
      }
      if (ex2 != null) {
        assertTrue(isYBTransactionError(ex2));
      }
      if (succeeded1) {
        numSuccess1++;
      }
      if (succeeded2) {
        numSuccess2++;
      }
    }
    LOG.info("INSERT succeeded " + numSuccess1 + " times, " +
             "SELECT succeeded " + numSuccess2 + " times");
    checkTransactionFairness(numSuccess1, numSuccess2, TOTAL_ITERATIONS, DEFAULT_SKEW_THRESHOLD);
  }

  @Test
  public void testBasicTransaction() throws Exception {
    createSimpleTable("test", "v", PartitioningMode.HASH);
    final IsolationLevel isolationLevel = IsolationLevel.REPEATABLE_READ;
    Connection connection1 = createConnection(isolationLevel, AutoCommit.DISABLED);
    Statement statement = connection1.createStatement();

    // For the second connection we still enable auto-commit, so that every new SELECT will see
    // a new snapshot of the database.
    Connection connection2 = createConnection(isolationLevel, AutoCommit.ENABLED);
    Statement statement2 = connection2.createStatement();

    for (int i = 0; i < 100; ++i) {
      try {
        int h1 = i * 10;
        int h2 = i * 10 + 1;
        LOG.debug("Inserting the first row within a transaction but not committing yet");
        statement.execute("INSERT INTO test(h, r, v) VALUES (" + h1 + ", 2, 3)");

        LOG.debug("Trying to read the first row from another connection");
        assertFalse(statement2.executeQuery("SELECT h, r, v FROM test WHERE h = " + h1).next());

        LOG.debug("Inserting the second row within a transaction but not committing yet");
        statement.execute("INSERT INTO test(h, r, v) VALUES (" + h2 + ", 5, 6)");

        LOG.debug("Trying to read the second row from another connection");
        assertFalse(statement2.executeQuery("SELECT h, r, v FROM test WHERE h = " + h2).next());

        LOG.debug("Committing the transaction");
        connection1.commit();

        LOG.debug("Checking first row from the other connection");
        ResultSet rs = statement2.executeQuery("SELECT h, r, v FROM test WHERE h = " + h1);
        assertTrue(rs.next());
        assertEquals(h1, rs.getInt("h"));
        assertEquals(2, rs.getInt("r"));
        assertEquals(3, rs.getInt("v"));

        LOG.debug("Checking second row from the other connection");
        rs = statement2.executeQuery("SELECT h, r, v FROM test WHERE h = " + h2);
        assertTrue(rs.next());
        assertEquals(h2, rs.getInt("h"));
        assertEquals(5, rs.getInt("r"));
        assertEquals(6, rs.getInt("v"));
      } catch (PSQLException ex) {
        LOG.error("Caught a PSQLException at iteration i=" + i, ex);
        throw ex;
      }
    }
  }

  /**
   * This test runs conflicting transactions trying to insert the same row and verifies that
   * exactly one of them gets committed.
   */
  @Test
  public void testTransactionConflicts() throws Exception {
    createSimpleTable("test", "v");
    final IsolationLevel isolation = IsolationLevel.REPEATABLE_READ;

    Connection connection1 = createConnection(isolation, AutoCommit.DISABLED);
    Statement statement1 = connection1.createStatement();

    Connection connection2 = createConnection(isolation, AutoCommit.DISABLED);
    Statement statement2 = connection2.createStatement();

    int numFirstWinners = 0;
    int numSecondWinners = 0;
    final int totalIterations = SanitizerUtil.nonTsanVsTsan(300, 100);
    for (int i = 1; i <= totalIterations; ++i) {
      LOG.info("Starting iteration: i=" + i);
      if (RandomUtils.nextBoolean()) {
        // Shuffle the two connections between iterations.
        Connection tmpConnection = connection1;
        connection1 = connection2;
        connection2 = tmpConnection;

        Statement tmpStatement = statement1;
        statement1 = statement2;
        statement2 = tmpStatement;
      }

      executeWithTimeout(statement1,
          String.format("INSERT INTO test(h, r, v) VALUES (%d, %d, %d)", i, i, 100 * i));
      boolean executed2 = false;
      try {
        executeWithTimeout(statement2,
            String.format("INSERT INTO test(h, r, v) VALUES (%d, %d, %d)", i, i, 200 * i));
        executed2 = true;
      } catch (PSQLException ex) {
        // TODO: validate the exception message.
        // Not reporting a stack trace here on purpose, because this will happen a lot in a test.
        LOG.info("Error while inserting on the second connection:" + ex.getMessage());
      }
      TransactionState txnState1BeforeCommit = getPgTxnState(connection1);
      TransactionState txnState2BeforeCommit = getPgTxnState(connection2);

      boolean committed1 = commitAndCatchException(connection1, "first connection");
      TransactionState txnState1AfterCommit = getPgTxnState(connection1);

      boolean committed2 = commitAndCatchException(connection2, "second connection");
      TransactionState txnState2AfterCommit = getPgTxnState(connection2);

      LOG.info("i=" + i +
          " executed2=" + executed2 +
          " committed1=" + committed1 +
          " committed2=" + committed2 +
          " txnState1BeforeCommit=" + txnState1BeforeCommit +
          " txnState2BeforeCommit=" + txnState2BeforeCommit +
          " txnState1AfterCommit=" + txnState1AfterCommit +
          " txnState2AfterCommit=" + txnState2AfterCommit +
          " numFirstWinners=" + numFirstWinners +
          " numSecondWinners=" + numSecondWinners);

      // Whether or not a transaction commits successfully, its state is changed to IDLE after the
      // commit attempt.
      assertEquals(TransactionState.IDLE, txnState1AfterCommit);
      assertEquals(TransactionState.IDLE, txnState2AfterCommit);

      if (!committed1 && !committed2) {
        // TODO: if this happens, look at why two transactions could fail at the same time.
        throw new AssertionError("Did not expect both transactions to fail!");
      }

      if (executed2) {
        assertFalse(committed1);
        assertTrue(committed2);
        assertEquals(TransactionState.OPEN, txnState1BeforeCommit);
        assertEquals(TransactionState.OPEN, txnState2BeforeCommit);
        numSecondWinners++;
      } else {
        assertTrue(committed1);
        // It looks like in case we get an error on an operation on the second connection, the
        // commit on that connection succeeds. This makes sense in a way because the client already
        // knows that the second transaction failed from the original operation failure. BTW the
        // second transaction is already in a FAILED state before we successfully "commit" it:
        //
        // executed2=false
        // committed1=true
        // committed2=true
        // txnState1BeforeCommit=OPEN
        // txnState2BeforeCommit=FAILED
        // txnState1AfterCommit=IDLE
        // txnState2AfterCommit=IDLE
        //
        // TODO: verify if this is consistent with vanilla PostgreSQL behavior.
        // assertFalse(committed2);
        assertEquals(TransactionState.OPEN, txnState1BeforeCommit);
        assertEquals(TransactionState.FAILED, txnState2BeforeCommit);

        numFirstWinners++;
      }
    }
    LOG.info(String.format(
        "First txn won in %d cases, second won in %d cases", numFirstWinners, numSecondWinners));
    checkTransactionFairness(numFirstWinners, numSecondWinners, totalIterations, 0.3);
  }

  @Test
  public void testFailedTransactions() throws Exception {
    createSimpleTable("test", "v");
    final IsolationLevel isolation = IsolationLevel.REPEATABLE_READ;
    try (
         Connection connection1 = createConnection(isolation, AutoCommit.ENABLED);
         Statement statement1 = connection1.createStatement();
         Connection connection2 = createConnection(isolation, AutoCommit.DISABLED);
         Statement statement2 = connection2.createStatement()) {
      Set<Row> expectedRows = new HashSet<>();
      String scanQuery = "SELECT * FROM test";

      // Check second-op failure with auto-commit (first op should get committed).
      statement1.execute("INSERT INTO test(h, r, v) VALUES (1, 1, 1)");
      expectedRows.add(new Row(1, 1, 1));
      runInvalidQuery(statement1,
                      "INSERT INTO test(h, r, v) VALUES (1, 1, 2)",
                      "Duplicate key");
      assertRowSet(scanQuery, expectedRows);

      // Check second op failure with no-auto-commit (both ops should get aborted).
      statement2.execute("INSERT INTO test(h, r, v) VALUES (1, 2, 1)");
      runInvalidQuery(statement2,
                      "INSERT INTO test(h, r, v) VALUES (1, 1, 2)",
                      "Duplicate key");
      connection2.commit(); // Overkill, transaction should already be aborted, this will be noop.
      assertRowSet(scanQuery, expectedRows);

      // Check failure for one value set -- primary key (1,1) already exists.
      runInvalidQuery(statement1,
                      "INSERT INTO test(h, r, v) VALUES (1, 2, 1), (1, 1, 2)",
                      "Duplicate key");
      // Entire query should get aborted.
      assertRowSet(scanQuery, expectedRows);

      // Check failure for query with WITH clause side-effect -- primary key (1,1) already exists.
      String query = "WITH ret AS (INSERT INTO test(h, r, v) VALUES (2, 2, 2) RETURNING h, r, v) " +
          "INSERT INTO test(h,r,v) SELECT h - 1, r - 1, v FROM ret";
      runInvalidQuery(statement1, query, "Duplicate key");
      // Entire query should get aborted (including the WITH clause INSERT).
      assertRowSet(scanQuery, expectedRows);

      // Check failure within function with side-effect -- primary key (1,1) already exists.
      // TODO enable this test once functions are enabled in YSQL.
      if (false) {
        statement1.execute("CREATE FUNCTION bar(in int) RETURNS int AS $$ " +
                              "INSERT INTO test(h,r,v) VALUES($1,$1,$1) RETURNING h - 1;$$" +
                              "LANGUAGE SQL;");
        runInvalidQuery(statement1,
                        "INSERT INTO test(h, r, v) VALUES (bar(2), 1, 1)",
                        "Duplicate key");
        // Entire query should get aborted (including the function's side-effect).
        assertRowSet(scanQuery, expectedRows);
      }

    }

  }

  private void testSingleRowTransactionGuards(List<String> stmts, List<String> guard_start_stmts,
                                              List<String> guard_end_stmts) throws Exception {
    Statement statement = connection.createStatement();

    // With guard (e.g. BEGIN/END, secondary index, trigger, etc.), statements should use txn path.
    for (String guard_start_stmt : guard_start_stmts) {
      statement.execute(guard_start_stmt);
    }
    for (String stmt : stmts) {
      verifyStatementTxnMetric(statement, stmt, 1);
    }

    // After ending guard, statements should go back to using non-txn path.
    for (String guard_end_stmt : guard_end_stmts) {
      statement.execute(guard_end_stmt);
    }
    for (String stmt : stmts) {
      verifyStatementTxnMetric(statement, stmt, 0);
    }
  }

  private void testSingleRowTransactionGuards(List<String> stmts, String guard_start_stmt,
                                              String guard_end_stmt) throws Exception {
    testSingleRowTransactionGuards(stmts,
                                   Arrays.asList(guard_start_stmt),
                                   Arrays.asList(guard_end_stmt));
  }

  private void testSingleRowStatements(List<String> stmts) throws Exception {
    // Verify standalone statements use non-txn path.
    Statement statement = connection.createStatement();
    for (String stmt : stmts) {
      verifyStatementTxnMetric(statement, stmt, 0);
    }

    // Test in txn block.
    testSingleRowTransactionGuards(
        stmts,
        "BEGIN",
        "END");

    // Test with secondary index.
    testSingleRowTransactionGuards(
        stmts,
        "CREATE INDEX test_index ON test (v)",
        "DROP INDEX test_index");

    // Test with trigger.
    testSingleRowTransactionGuards(
        stmts,
        "CREATE TRIGGER test_trigger BEFORE UPDATE ON test " +
        "FOR EACH ROW EXECUTE PROCEDURE suppress_redundant_updates_trigger()",
        "DROP TRIGGER test_trigger ON test");

    // Test with foreign key.
    testSingleRowTransactionGuards(
        stmts,
        Arrays.asList(
            "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE",
            "DROP TABLE IF EXISTS foreign_table",
            "CREATE TABLE foreign_table (v int PRIMARY KEY)",
            "INSERT INTO foreign_table VALUES (1), (2)",
            "DROP TABLE IF EXISTS test",
            "CREATE TABLE test (k int PRIMARY KEY, v int references foreign_table(v))"),
        Arrays.asList(
            "DROP TABLE test",
            "DROP TABLE foreign_table",
            "CREATE TABLE test (k int PRIMARY KEY, v int)",
            "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ"));
  }

  @Test
  public void testSingleRowNoTransaction() throws Exception {
    Statement statement = connection.createStatement();
    statement.execute("CREATE TABLE test (k int PRIMARY KEY, v int)");

    // Test regular INSERT/UPDATE/DELETE single-row statements.
    testSingleRowStatements(
        Arrays.asList(
            "INSERT INTO test VALUES (1, 1)",
            "UPDATE test SET v = 2 WHERE k = 1",
            "DELETE FROM test WHERE k = 1"));

    // Test INSERT/UPDATE/DELETE single-row prepared statements.
    statement.execute("PREPARE insert_stmt (int, int) AS INSERT INTO test VALUES ($1, $2)");
    statement.execute("PREPARE delete_stmt (int) AS DELETE FROM test WHERE k = $1");
    statement.execute("PREPARE update_stmt (int, int) AS UPDATE test SET v = $2 WHERE k = $1");
    testSingleRowStatements(
        Arrays.asList(
            "EXECUTE insert_stmt (1, 1)",
            "EXECUTE update_stmt (1, 2)",
            "EXECUTE delete_stmt (1)"));

    // Verify statements with WITH clause use txn path.
    verifyStatementTxnMetric(statement,
                             "WITH test2 AS (UPDATE test SET v = 2 WHERE k = 1) " +
                             "UPDATE test SET v = 3 WHERE k = 1", 1);

    // Verify JDBC single-row prepared statements use non-txn path.
    int oldTxnValue = getMetricCounter(TRANSACTIONS_METRIC);

    PreparedStatement insertStatement =
      connection.prepareStatement("INSERT INTO test VALUES (?, ?)");
    insertStatement.setInt(1, 1);
    insertStatement.setInt(2, 1);
    insertStatement.executeUpdate();

    PreparedStatement deleteStatement =
      connection.prepareStatement("DELETE FROM test WHERE k = ?");
    deleteStatement.setInt(1, 1);
    deleteStatement.executeUpdate();

    PreparedStatement updateStatement =
      connection.prepareStatement("UPDATE test SET v = ? WHERE k = ?");
    updateStatement.setInt(1, 1);
    updateStatement.setInt(2, 1);
    updateStatement.executeUpdate();

    int newTxnValue = getMetricCounter(TRANSACTIONS_METRIC);
    assertEquals(oldTxnValue, newTxnValue);
  }
}
