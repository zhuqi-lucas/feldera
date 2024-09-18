package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateTraceRetainKeysOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateTraceRetainValuesOperator;
import org.dbsp.sqlCompiler.compiler.CompilerOptions;
import org.dbsp.sqlCompiler.compiler.DBSPCompiler;
import org.dbsp.sqlCompiler.compiler.StderrErrorReporter;
import org.dbsp.sqlCompiler.compiler.visitors.inner.InnerVisitor;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.compiler.visitors.outer.MonotoneAnalyzer;
import org.dbsp.sqlCompiler.compiler.visitors.outer.Monotonicity;
import org.dbsp.sqlCompiler.ir.expression.DBSPCastExpression;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool;
import org.dbsp.util.Logger;
import org.junit.Assert;
import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.junit.Test;

/** Regression tests that failed in incremental mode using the Catalog API */
public class IncrementalRegressionTests extends SqlIoTest {
    @Override
    public DBSPCompiler testCompiler() {
        CompilerOptions options = this.testOptions(true, true);
        // This causes the use of SourceSet operators
        options.ioOptions.emitHandles = false;
        // Without the following ORDER BY causes failures
        options.languageOptions.ignoreOrderBy = true;
        return new DBSPCompiler(options);
    }

    @Test
    public void issue2514() {
        String sql = """
            CREATE TABLE transaction (
                ts TIMESTAMP LATENESS INTERVAL 1 DAYS,
                amt DOUBLE,
                customer_id BIGINT NOT NULL,
                state VARCHAR
            );
            
            CREATE MATERIALIZED VIEW red_transactions AS
            SELECT
                *
            FROM
                transaction
            WHERE
                state = 'CA';""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void testControl() {
        // Test using a control table to save changes from a query
        String query = """
                CREATE TABLE T(COL1 INT, COL2 BIGINT);
                create table control (id int);
                
                CREATE LOCAL VIEW test  AS
                SELECT
                    COL1,
                    COUNT(*),
                    SUM(COL2)
                FROM T
                GROUP BY T.COL1;
                
                CREATE VIEW output as
                select * from
                test where exists (select 1 from control);""";
        this.compileRustTestCase(query);
    }

    @Test
    public void issue2243() {
        String sql = """
                CREATE TABLE CUSTOMER (cc_num bigint not null, ts timestamp lateness interval 0 day);
                CREATE TABLE TRANSACTION (cc_num bigint not null, ts timestamp lateness interval 0 day);
                
                CREATE VIEW V AS
                SELECT t.*
                FROM
                transaction as t JOIN customer as c
                ON
                    t.cc_num = c.cc_num
                WHERE
                    t.ts <= c.ts and t.ts + INTERVAL 7 DAY >= c.ts;
                
                create view V2 as
                select SUM(5) from v group by ts;""";
        CompilerCircuitStream ccs = this.getCCS(sql);
        this.addRustTestCase(ccs);
        CircuitVisitor visitor = new CircuitVisitor(new StderrErrorReporter()) {
            int integrateTraceKeys = 0;
            int integrateTraceValues = 0;

            @Override
            public void postorder(DBSPIntegrateTraceRetainKeysOperator operator) {
                this.integrateTraceKeys++;
            }

            @Override
            public void postorder(DBSPIntegrateTraceRetainValuesOperator operator) {
                this.integrateTraceValues++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(1, this.integrateTraceKeys);
                Assert.assertEquals(2, this.integrateTraceValues);
            }
        };
        InnerVisitor findBoolCasts = new InnerVisitor(new StderrErrorReporter()) {
            int unsafeBoolCasts = 0;

            @Override
            public void postorder(DBSPCastExpression expression) {
                if (!expression.getType().mayBeNull &&
                        expression.getType().is(DBSPTypeBool.class) &&
                        expression.source.getType().mayBeNull)
                    unsafeBoolCasts++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(0, this.unsafeBoolCasts);
            }
        };
        visitor.apply(ccs.circuit);
        findBoolCasts.getCircuitVisitor().apply(ccs.circuit);
    }

    @Test
    public void issue2242() {
        String sql = """
                create table TRANSACTION (
                    cc_num bigint,
                    unix_time timestamp,
                    id bigint
                );
                
                CREATE VIEW TRANSACTION_MONTHLY_AGGREGATE AS
                select cc_num, window_start as month_start, COUNT(*) num_transactions from TABLE(
                  TUMBLE(
                    TABLE transaction,
                    DESCRIPTOR(unix_time),
                    INTERVAL 1 MONTH))
                GROUP BY cc_num, window_start;
                """;
        this.statementsFailingInCompilation(sql, "Tumbling window intervals must be 'short'");
    }

    @Test
    public void issue2039() {
        String sql = """
                CREATE TABLE transactions (
                    id INT NOT NULL PRIMARY KEY,
                    ts TIMESTAMP LATENESS INTERVAL 0 HOURS,
                    user_id INT,
                    AMOUNT DECIMAL
                );""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2043() {
        String sql =
                """
                CREATE TABLE transactions (
                    id INT NOT NULL PRIMARY KEY,
                    ts TIMESTAMP LATENESS INTERVAL 0 SECONDS,
                    user_id INT,
                    AMOUNT DECIMAL
                ) with ('materialized' = 'true');
                
                CREATE MATERIALIZED VIEW window_computation AS SELECT
                    user_id,
                    COUNT(*) AS transaction_count_by_user
                    FROM transactions
                    WHERE ts > NOW() - INTERVAL 1 DAY and ts <= NOW()
                    GROUP BY user_id;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2043uppercase() {
        // Simulate a different unquotedCasing flag
        String sql =
                """
                CREATE TABLE transactions (
                    id INT NOT NULL PRIMARY KEY,
                    ts TIMESTAMP LATENESS INTERVAL 0 SECONDS,
                    user_id INT,
                    AMOUNT DECIMAL
                ) with ('materialized' = 'true');
                
                CREATE MATERIALIZED VIEW window_computation AS SELECT
                    user_id,
                    COUNT(*) AS transaction_count_by_user
                    FROM transactions
                    WHERE ts > NOW() - INTERVAL 1 DAY and ts <= NOW()
                    GROUP BY user_id;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.options.languageOptions.throwOnError = false;
        compiler.options.languageOptions.unquotedCasing = "upper";
        compiler.compileStatements(sql);
        getCircuit(compiler);
        Assert.assertEquals(0, compiler.messages.exitCode);
    }

    @Test
    public void issue2018() {
        String sql = """
                CREATE TABLE customer (
                    c_id INT NOT NULL,
                    c_d_id INT NOT NULL,
                    c_w_id INT NOT NULL,
                    c_first VARCHAR(16),
                    c_middle CHAR(2),
                    c_last VARCHAR(16),
                    c_street_1 VARCHAR(20),
                    c_street_2 VARCHAR(20),
                    c_city VARCHAR(20),
                    c_state CHAR(2),
                    c_zip CHAR(9),
                    c_phone CHAR(16),
                    c_since TIMESTAMP,
                    c_credit CHAR(2),
                    c_credit_lim DECIMAL(12,2),
                    c_discount DECIMAL(4,4),
                    c_balance DECIMAL(12,2),
                    c_ytd_payment DECIMAL(12,2),
                    c_payment_cnt INT,
                    c_delivery_cnt INT,
                    c_data VARCHAR(500),
                    PRIMARY KEY (c_w_id, c_d_id, c_id),
                    FOREIGN KEY (c_w_id, c_d_id) REFERENCES district(d_w_id, d_id)
                );
                
                CREATE TABLE transaction_parameters (
                    txn_id INT NOT NULL PRIMARY KEY,
                    w_id INT,
                    d_id INT,
                    c_id INT,
                    c_w_id INT,
                    c_d_id INT,
                    c_last VARCHAR(20), -- TODO check
                    h_amount DECIMAL(5,2),
                    h_date TIMESTAMP,
                    datetime_ TIMESTAMP
                );
                
                -- incremental fails with this query present
                CREATE VIEW cust_enum AS
                SELECT c.c_first, c.c_middle, c.c_id,
                    c.c_street_1, c.c_street_2, c.c_city, c.c_state, c.c_zip,
                    c.c_phone, c.c_credit, c.c_credit_lim,
                    c.c_discount, c.c_balance, c.c_since
                FROM customer AS c,
                     transaction_parameters AS t
                WHERE c.c_last = t.c_last
                  AND c.c_d_id = t.c_d_id
                  AND c.c_w_id = t.c_w_id
                ORDER BY c_first;
                
                CREATE VIEW cust_agg AS
                SELECT ARRAY_AGG(c_id ORDER BY c_first) AS cust_array
                FROM (SELECT c.c_id, c.c_first
                      FROM customer AS c,
                          transaction_parameters AS t
                      WHERE c.c_last = t.c_last
                        AND c.c_d_id = t.c_d_id
                        AND c.c_w_id = t.c_w_id
                      ORDER BY c_first);
                
                CREATE VIEW cust_med AS
                SELECT c.c_first, c.c_middle, c.c_id,
                    c.c_street_1, c.c_street_2, c.c_city, c.c_state, c.c_zip,
                    c.c_phone, c.c_credit, c.c_credit_lim,
                    c.c_discount, c.c_balance, c.c_since
                FROM customer as c,
                     cust_agg as a,
                     transaction_parameters as t
                WHERE c.c_id = a.cust_array[(ARRAY_LENGTH(a.cust_array) / 2) + 1];
                """;
        this.compileRustTestCase(sql);
    }
}
