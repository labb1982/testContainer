package nosql;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint;
import com.ing.data.cassandra.jdbc.CassandraDriver;
import com.ing.data.cassandra.jdbc.utils.JdbcUrlUtil;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.ext.cassandra.database.CassandraDatabase;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.io.IOException;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;


import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class CassandraTest {

    public static final int ORIGINAL_PORT = 9042;

    private static void extracted(CqlSession session, String query) {
        ResultSet result = session.execute(query);
        do {
            Row one = result.one();
            if (one == null) {
                break;
            }
            ColumnDefinitions columnDefinitions = one.getColumnDefinitions();
            for (ColumnDefinition cd : columnDefinitions) {
                log.info("column:{}, value:{}", cd, one.getObject(cd.getName()));
            }
        } while (true);
    }

    private static CassandraContainer cassandraContainer;


    private static boolean startedByMe;
    @BeforeAll
    static void b4All() {
        cassandraContainer = new CassandraContainer("cassandra:" +   "5.0"            //        "3.11.2"
        ).withExposedPorts(ORIGINAL_PORT);
        try {
            cassandraContainer.start();
            log.info("cassandraContainer:{}", cassandraContainer);
            startedByMe = true;
        } catch (Exception e) {
            log.error("", e);//            throw new RuntimeException(e);
        }

    }

    @AfterAll
    static void afterAll() {
        if(startedByMe){
            cassandraContainer.stop();
        }
    }

    @Test
    void name() throws LiquibaseException, SQLException {
        assertTrue(cassandraContainer.isRunning());

        Awaitility.await()//.atLeast(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(50))
                .between(/*50*/1, TimeUnit.SECONDS, 500, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                //ConditionTimeoutException: Condition was evaluated in 1 seconds 41 milliseconds
                // which is earlier than expected minimum timeout 50 seconds
                .until(CassandraTest::isHostAccessible);


        int poolSize = Integer.getInteger("poolSize", 3);
        DriverConfigLoader driverConfigLoader = DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.HEARTBEAT_INTERVAL, Duration.ofSeconds(10))
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, /*consistencyLevel.name()*/ConsistencyLevel.LOCAL_QUORUM.name())
                .withString(DefaultDriverOption.RETRY_POLICY_CLASS, "DefaultRetryPolicy")
                .withString(DefaultDriverOption.RECONNECTION_POLICY_CLASS, "ConstantReconnectionPolicy")
                .withDuration(DefaultDriverOption.RECONNECTION_BASE_DELAY, Duration.ofSeconds(1))
                .withString(DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS, "DcInferringLoadBalancingPolicy")
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, poolSize)
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 0)
                .withBoolean(DefaultDriverOption.NETTY_DAEMON, "true".equalsIgnoreCase(System.getProperty("intelliedge.netty.daemon", "true")))
                .build();

        CqlSessionBuilder sessionBuilder = CqlSession.builder()
                .addContactEndPoints(

                        List.of(
                                new DefaultEndPoint(
                                        cassandraContainer.getContactPoint()// new InetSocketAddress("localhost", ORIGINAL_PORT)
                                ))
                )
                .withConfigLoader(driverConfigLoader)//                .withAuthCredentials("", "")
                .withKeyspace("system")
                .withLocalDatacenter(cassandraContainer.getLocalDatacenter()); ///"datacenter1"

        CqlSession session = sessionBuilder.build();

        session.execute("CREATE KEYSPACE IF NOT EXISTS test_keyspace WITH replication = {'class':'SimpleStrategy', 'replication_factor':'1'};");
        // Establish a connection to the Cassandra keyspace
        String jdbcUrl = String.format("jdbc:cassandra://%s:%d"   + "/%s"   //+"?localDC=%s"
                 , cassandraContainer.getHost(),
                cassandraContainer.getMappedPort(9042) ,"test_keyspace"            //, cassandraContainer.getLocalDatacenter()
                 );

        Properties properties = new Properties();
        properties.put(JdbcUrlUtil.TAG_LOCAL_DATACENTER, cassandraContainer.getLocalDatacenter());

        try(Connection cassandraConnection = new CassandraDriver().connect(jdbcUrl, properties);
            JdbcConnection connection = new JdbcConnection(cassandraConnection);) {

            CassandraDatabase database = new CassandraDatabase(); //DatabaseFactory.getInstance().findCorrectDatabaseImplementation(connection);
            database.setConnection(connection);
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-nosql-master.yaml",
                    new ClassLoaderResourceAccessor(), database);
            //liquibase.forceReleaseLocks();
;
            liquibase.update("");

            extracted(session, "select * from local");
            extracted(session, "SELECT * FROM system_schema.keyspaces");
            extracted(session, "SELECT * FROM system_schema.tables");
            session.close();
        }
    }


    private static boolean isHostAccessible() {


        try (Socket socket = new Socket("localhost", cassandraContainer.getMappedPort(9042))) {
            return true;
        } catch (IOException e) {
            log.error("", e);
           return false;
        }
    }
}
