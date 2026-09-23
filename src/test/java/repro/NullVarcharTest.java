package repro;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.Query;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.eclipse.persistence.Version;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.postgresql.ds.PGSimpleDataSource;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class NullVarcharTest {
    enum Shape {
        RELATIONSHIP("SELECT c FROM Child c WHERE c.parent.id = :id"),
        RELATIONSHIP_STATUSES("SELECT c FROM Child c WHERE c.parent.id = :id AND c.status IN :statuses"),
        SCALAR("SELECT p FROM Parent p WHERE p.id = :id"),
        SCALAR_CHILD_STATUSES("SELECT c FROM Child c WHERE c.id = :id AND c.status IN :statuses");

        final String jpql;
        Shape(String jpql) { this.jpql = jpql; }
    }

    @Parameterized.Parameters(name = "{0}, nullFirst={1}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (Shape shape : Shape.values()) {
            cases.add(new Object[]{shape, true});
            cases.add(new Object[]{shape, false});
        }
        return cases;
    }

    private static final Path RESULTS = Path.of(System.getProperty("repro.output", "target/results.tsv"));
    private final Shape shape;
    private final boolean nullFirst;

    public NullVarcharTest(Shape shape, boolean nullFirst) {
        this.shape = shape;
        this.nullFirst = nullFirst;
    }

    @BeforeClass
    public static void startResults() throws Exception {
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, "shape\tnullFirst\targument\trows\tsqlState\terror\tjdbc\n");
        System.out.println("[VERSION] EclipseLink " + Version.getVersion());
    }

    @Test
    public void compareNullAndNonNull() throws Exception {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(System.getProperty("repro.jdbc.url", "jdbc:postgresql://127.0.0.1:25432/eclipselink_repro"));
        postgres.setUser(System.getProperty("repro.jdbc.user", "repro"));
        postgres.setPassword(System.getProperty("repro.jdbc.password", "repro"));
        prepareSchema(postgres);
        RecordingDataSource recorded = new RecordingDataSource(postgres);
        // A different session name prevents factory/session reuse between parameterized cases.
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("repro", Map.of(
                "jakarta.persistence.nonJtaDataSource", recorded,
                "eclipselink.session-name", shape + "-nullFirst-" + nullFirst));
        try {
            Long existingId = seed(emf);
            Long newParentId = new Parent().id;
            assertNull("An unpersisted parent has no generated ID", newParentId);
            Outcome nullOutcome;
            Outcome nonNullOutcome;
            if (nullFirst) {
                nullOutcome = query(emf, recorded, newParentId);
                nonNullOutcome = query(emf, recorded, existingId);
            } else {
                nonNullOutcome = query(emf, recorded, existingId);
                nullOutcome = query(emf, recorded, newParentId);
            }
            // Always run and record both calls, even if the first one fails.
            assertNull("Non-null control must succeed", nonNullOutcome.failure);
            assertEquals("Non-null control must find the fixture", 1, nonNullOutcome.rows.size());
            if (shape == Shape.SCALAR) {
                assertEquals(existingId, ((Parent) nonNullOutcome.rows.get(0)).id);
            } else {
                assertEquals(Long.valueOf(1), ((Child) nonNullOutcome.rows.get(0)).id);
            }
            // A null comparison parameter should execute successfully and match no rows.
            // If the bug reproduces, this test goes red and results.tsv preserves the evidence.
            if (nullOutcome.failure != null) {
                throw new AssertionError("Null parameter failed for " + shape, nullOutcome.failure);
            }
            assertTrue("Equality to SQL NULL should match no rows", nullOutcome.rows.isEmpty());
        } finally {
            emf.close();
        }
    }

    private static void prepareSchema(PGSimpleDataSource postgres) throws Exception {
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            System.out.println("[VERSION] PostgreSQL " + connection.getMetaData().getDatabaseProductVersion()
                    + "; JDBC " + connection.getMetaData().getDriverVersion());
            statement.execute("DROP TABLE IF EXISTS CHILD");
            statement.execute("DROP TABLE IF EXISTS PARENT");
            statement.execute("DROP SEQUENCE IF EXISTS PARENT_SEQ");
            statement.execute("CREATE SEQUENCE PARENT_SEQ START WITH 1");
            statement.execute("CREATE TABLE PARENT (ID BIGINT NOT NULL PRIMARY KEY)");
            statement.execute("CREATE TABLE CHILD (ID BIGINT NOT NULL PRIMARY KEY, "
                    + "PARENT_ID BIGINT NOT NULL REFERENCES PARENT(ID), STATUS INTEGER NOT NULL)");
            try (ResultSet result = statement.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'child' AND column_name = 'parent_id'")) {
                assertTrue(result.next());
                assertEquals("bigint", result.getString(1));
                assertEquals("NO", result.getString(2));
                System.out.println("[SCHEMA] CHILD.PARENT_ID = bigint NOT NULL");
            }
        }
    }

    private static Long seed(EntityManagerFactory emf) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Parent parent = new Parent();
            em.persist(parent);
            em.persist(new Child(1L, parent, Child.Status.ACTIVE));
            em.getTransaction().commit();
            assertNotNull(parent.id);
            return parent.id;
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    private Outcome query(EntityManagerFactory emf, RecordingDataSource recorded, Long id) throws Exception {
        recorded.executions.clear();
        EntityManager em = emf.createEntityManager();
        List<?> rows = List.of();
        RuntimeException failure = null;
        String sqlState = "";
        System.out.println("[CASE] " + shape + " nullFirst=" + nullFirst + " id=" + id);
        try {
            Query query = em.createQuery(shape.jpql).setParameter("id", id);
            if (shape == Shape.RELATIONSHIP_STATUSES || shape == Shape.SCALAR_CHILD_STATUSES) {
                query.setParameter("statuses", List.of(Child.Status.ACTIVE, Child.Status.INACTIVE));
            }
            rows = query.getResultList();
        } catch (RuntimeException exception) {
            failure = exception;
            exception.printStackTrace(System.out);
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlException) {
                    sqlState = sqlException.getSQLState();
                }
            }
        } finally {
            em.close();
        }
        assertFalse("The query must actually reach JDBC", recorded.executions.isEmpty());
        String result = shape + "\t" + nullFirst + "\t" + id + "\t"
                + (failure == null ? rows.size() : "ERROR") + "\t" + sqlState + "\t"
                + (failure == null ? "" : failure.toString().replaceAll("[\\t\\r\\n]+", " "))
                + "\t" + String.join(" || ", recorded.executions) + "\n";
        Files.writeString(RESULTS, result, StandardOpenOption.APPEND);
        System.out.println("[RESULT] " + result.strip());
        return new Outcome(rows, failure);
    }

    private record Outcome(List<?> rows, RuntimeException failure) {}
}
