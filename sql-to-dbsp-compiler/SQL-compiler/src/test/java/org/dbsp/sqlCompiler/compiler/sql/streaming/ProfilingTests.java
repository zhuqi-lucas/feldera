package org.dbsp.sqlCompiler.compiler.sql.streaming;

import org.dbsp.sqlCompiler.CompilerMain;
import org.dbsp.sqlCompiler.compiler.errors.CompilerMessages;
import org.dbsp.sqlCompiler.compiler.sql.StreamingTestBase;
import org.dbsp.sqlCompiler.compiler.sql.tools.BaseSQLTests;
import org.dbsp.util.Linq;
import org.dbsp.util.Utilities;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Some tests that use the profiling API to measure perf at runtime */
public class ProfilingTests extends StreamingTestBase {
    Long[] measure(String program, String main) throws IOException, InterruptedException, SQLException {
        File script = createInputScript(program);
        CompilerMessages messages = CompilerMain.execute(
                "-o", BaseSQLTests.testFilePath, "--handles", "-i",
                script.getPath());
        messages.print();
        Assert.assertEquals(0, messages.errorCount());

        String mainFilePath = rustDirectory + "/main.rs";
        File file = new File(mainFilePath);
        try (PrintWriter mainFile = new PrintWriter(file, StandardCharsets.UTF_8)) {
            mainFile.print(main);
        }
        //file.deleteOnExit();
        Utilities.compileAndTestRust(rustDirectory, true, "--release");
        File mainFile = new File(mainFilePath);
        boolean deleted;
        deleted = mainFile.delete();
        Assert.assertTrue(deleted);

        // After executing this Rust program the output is in file "mem.txt"
        // It contains three numbers: time taken (ms), memory used (bytes), and late records.
        String outFile = "mem.txt";
        Path outFilePath = Paths.get(rustDirectory, "..", outFile);
        List<String> strings = Files.readAllLines(outFilePath);
        // System.out.println(strings);
        Assert.assertEquals(1, strings.size());
        String[] split = strings.get(0).split(",");
        Assert.assertEquals(3, split.length);
        deleted = outFilePath.toFile().delete();
        Assert.assertTrue(deleted);
        return Linq.map(split, Long::parseLong, Long.class);
    }

    String createMain(String rustDataGenerator) {
        String preamble = """
                #[allow(unused_imports)]
                use dbsp::{
                    algebra::F64,
                    circuit::{
                        CircuitConfig,
                        metrics::TOTAL_LATE_RECORDS,
                    },
                    utils::{Tup2, Tup3},
                    zset,
                };

                use feldera_sqllib::{
                    append_to_collection_handle,
                    read_output_handle,
                    casts::cast_to_Timestamp_s,
                };

                use std::{
                    collections::HashMap,
                    io::Write,
                    ops::Add,
                    fs::File,
                    time::SystemTime,
                };

                use metrics::{Key, SharedString, Unit};
                use metrics_util::{
                    debugging::{DebugValue, DebuggingRecorder},
                    CompositeKey, MetricKind,
                };

                use temp::circuit;
                use dbsp::circuit::Layout;
                use uuid::Uuid;

                type MetricsSnapshot = HashMap<CompositeKey, (Option<Unit>, Option<SharedString>, DebugValue)>;

                fn parse_counter(metrics: &MetricsSnapshot, name: &'static str) -> u64 {
                    if let Some((_, _, DebugValue::Counter(value))) = metrics.get(&CompositeKey::new(
                        MetricKind::Counter,
                        Key::from_static_name(name),
                    )) {
                        *value
                    } else {
                        0
                    }
                }

                #[test]
                // Run the circuit generated by 'circuit' for a while then measure the
                // memory consumption.  Write the time taken and the memory used into
                // a file called "mem.txt".
                pub fn test() {
                    let recorder = DebuggingRecorder::new();
                    let snapshotter = recorder.snapshotter();
                    recorder.install().unwrap();

                    let (mut circuit, streams) = circuit(
                         CircuitConfig {
                             layout: Layout::new_solo(2),
                             storage: None,
                             min_storage_bytes: usize::MAX,
                             init_checkpoint: Uuid::nil(),
                         }).expect("could not build circuit");
                    // uncomment if you want a CPU profile
                    // let _ = circuit.enable_cpu_profiler();
                    let start = SystemTime::now();
                """;
        String postamble = """
                    let metrics = snapshotter.snapshot();
                    let decoded_metrics: MetricsSnapshot = metrics.into_hashmap();
                    let late = parse_counter(&decoded_metrics, TOTAL_LATE_RECORDS);

                    let profile = circuit.retrieve_profile().expect("could not get profile");
                    let end = SystemTime::now();
                    let duration = end.duration_since(start).expect("could not get time");

                    // println!("{:?}", profile);
                    let mut data = String::new();
                    data.push_str(&format!("{},{},{}\\n",
                                           duration.as_millis(),
                                           profile.total_used_bytes().unwrap().bytes,
                                           late));
                    let mut file = File::create("mem.txt").expect("Could not create file");
                    file.write_all(data.as_bytes()).expect("Could not write data");
                    // println!("{:?},{:?},{:?}", duration, profile.total_used_bytes().unwrap(), late);
                }""";
        return preamble + rustDataGenerator + postamble;
    }

    static String stripLateness(String query) {
        String[] lines = query.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lateness = line.indexOf("LATENESS");
            if (lateness > 0) {
                boolean comma = line.endsWith(",");
                line = line.substring(0, lateness);
                if (comma) line += ",";
                lines[i] = line;
            }
        }
        return String.join("\n", lines);
    }

    @Test
    public void profileLateness() throws IOException, InterruptedException, SQLException {
        String sql = """
                CREATE TABLE series (
                        distance DOUBLE,
                        pickup TIMESTAMP NOT NULL LATENESS INTERVAL '1:00' HOURS TO MINUTES
                );
                CREATE VIEW V AS
                SELECT MAX(distance), CAST(pickup AS DATE)
                FROM series GROUP BY CAST(pickup AS DATE);
                """;
        // Rust program which profiles the circuit.
        String main = this.createMain("""
                    // Initial data value for timestamp
                    let mut timestamp = cast_to_Timestamp_s("2024-01-10 10:10:10".to_string());
                    for i in 0..1000000 {
                        let value = Some(F64::new(i.into()));
                        timestamp = timestamp.add(20000);
                        let input = zset!(Tup2::new(value, timestamp) => 1);
                        append_to_collection_handle(&input, &streams.0);
                        if i % 1000 == 0 {
                            let _ = circuit.step().expect("could not run circuit");
                            let _ = &read_output_handle(&streams.1);
                            /*
                            let end = SystemTime::now();
                            let profile = circuit.retrieve_profile().expect("could not get profile");
                            let duration = end.duration_since(start).expect("could not get time");
                            println!("{:?},{:?}", duration.as_millis(), profile.total_used_bytes().unwrap().bytes);
                            */
                        }
                    }""");
        this.profile(sql, main);
    }

    void profile(String sql, String main) throws SQLException, IOException, InterruptedException {
        Long[] p0 = this.measure(stripLateness(sql), main);
        Long[] p1 = this.measure(sql, main);
        // Memory consumption of program without lateness is expected to be higher
        // (because it cannot discard old data).
        if (p0[1] < 1.5 * p1[1]) {
            System.err.println("Profile statistics without and with lateness:");
            System.err.println(Arrays.toString(p0));
            System.err.println(Arrays.toString(p1));
            assert false;
        }
        // No late records
        assert p0[2] == 0 && p1[2] == 0;
    }

    @Test
    public void profileRetainValues() throws IOException, InterruptedException, SQLException {
        // Based on Q9.  Check whether integrate_trace_retain_values works as expected.
        String sql = """
                CREATE TABLE auction (
                   date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE,
                   expires   TIMESTAMP NOT NULL,
                   id        INT
                );

                CREATE TABLE bid (
                   date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE,
                   price INT,
                   auction INT
                );

                CREATE VIEW Q9 AS
                SELECT A.*, B.price, B.date_time AS bid_dateTime
                FROM auction A, bid B
                WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires;
                """;
        // Rust program which profiles the circuit.
        String main = this.createMain("""
                    // Initial data value for timestamp
                    let mut timestamp = cast_to_Timestamp_s("2024-01-10 10:10:10".to_string());
                    for i in 0..1000000 {
                        let expire = timestamp.add(1000000);
                        timestamp = timestamp.add(20000);
                        let auction = zset!(Tup3::new(timestamp, expire, Some(i)) => 1);
                        append_to_collection_handle(&auction, &streams.0);
                        let bid = zset!(Tup3::new(timestamp.add(100), Some(i), Some(i)) => 1);
                        append_to_collection_handle(&bid, &streams.1);
                        if i % 100 == 0 {
                            let _ = circuit.step().expect("could not run circuit");
                            let _ = &read_output_handle(&streams.2);
                            /*
                            let end = SystemTime::now();
                            let profile = circuit.retrieve_profile().expect("could not get profile");
                            let duration = end.duration_since(start).expect("could not get time");
                            println!("{:?},{:?}", duration.as_millis(), profile.total_used_bytes().unwrap().bytes);
                            */
                        }
                    }""");
        this.profile(sql, main);
    }

    @Test
    public void issue2228() throws SQLException, IOException, InterruptedException {
        String sql = """
                CREATE TABLE transaction (
                    id int NOT NULL,
                    unix_time BIGINT NOT NULL
                );

                CREATE TABLE feedback (
                    id int,
                    unix_time bigint LATENESS 3600 * 24
                );

                CREATE VIEW TRANSACTIONS AS
                SELECT t.*
                FROM transaction as t JOIN feedback as f
                ON t.id = f.id
                WHERE t.unix_time >= f.unix_time - 3600 * 24 * 7 ;
                """;

        String main = this.createMain("""
                let _ = circuit.step().expect("could not run circuit");
                let _ = circuit.step().expect("could not run circuit");
                """);
        this.measure(sql, main);
    }
}
