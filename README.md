# EclipseLink null BIGINT parameter reproduction

Reproduced with EclipseLink 5.0.1, PostgreSQL 16.14, PostgreSQL JDBC 42.7.13,
Jakarta Persistence 3.2.0, JDK 25.0.3, and Maven 3.9.16.

Exactly two entities are used: `Parent` with a generated `Long` ID, and
`Child` with a non-null many-to-one parent and an ordinal status.
The test verifies the real database column is `bigint NOT NULL` and persists
one parent and one active child.

## Run

From this directory:

```sh
bash run.sh
```

Use Java 17 or later, with `mvn` on PATH, or set `MVN` to its executable path.
Docker must be available. The script starts `postgres:16` on
an automatically assigned localhost port and removes its container on exit.
The PostgreSQL image tag is movable; the observed run used image ID
`sha256:88a36c64c1003dad93f56daa12d1f8916ec66d1fa3e5fb1fb0ae7cb77efd56d1`.

The expected result on 5.0.1 is a **failed build: 8 tests, 4 failures, 0 errors**.
Tests assert correct query behavior, so reproducing the bug makes them fail.
They do not assert that the bug must remain present. Both null and non-null
queries run and are recorded before assertions, regardless of execution order.

## Observed results

Each shape runs with null first and with non-null first. Every case has a fresh
persistence factory and EclipseLink session. Every query uses a fresh entity
manager. SQL and JDBC calls confirm execution against PostgreSQL.

| Predicate | Null binding | Null result | Non-null result |
| --- | --- | --- | --- |
| `c.parent.id = :id` | `setNull(1, BIGINT)` | Empty | Matching child |
| `c.parent.id = :id AND c.status IN :statuses` | `setNull(1, VARCHAR)` | SQLState 42883 | Matching child |
| `p.id = :id` | `setNull(1, BIGINT)` | Empty | Matching parent |
| `c.id = :id AND c.status IN :statuses` | `setNull(1, VARCHAR)` | SQLState 42883 | Matching child |

Both orders produce the same results. The status list is nonempty and contains
`ACTIVE` and `INACTIVE`, stored as ordinals 0 and 1.

The relationship query with a status collection produces:

```text
SELECT ID, STATUS, PARENT_ID FROM CHILD
WHERE ((PARENT_ID = (?)) AND (STATUS IN (?,?)))
JDBC: {1=setNull(VARCHAR,12), 2=setInt(0), 3=setInt(1)}
ERROR: operator does not exist: bigint = character varying
SQLState: 42883
```

The delegating JDBC wrapper preserves driver behavior and logs actual setter
calls. The full exception chain is captured in the Surefire output.

## Source finding

See [ANALYSIS.md](ANALYSIS.md) for the debugger-confirmed parameter lifecycle,
the exact point where type information is lost, and source permalinks.

Inspection of the released 5.0.1 sources identifies the following path:

1. `DatabaseCall.translate(...)` preserves type information by representing a
   null bind value as a `DatabaseField`. A collection parameter triggers
   `translateQueryStringForParameterizedIN(...)` (lines 1202-1204).
2. `DatasourceCall.translateQueryStringForParameterizedIN(...)` processes all
   parameters. At lines 1431-1433, a `DatabaseField` whose translation-row value
   is null becomes raw `null`, and its marker becomes `(?)`. This also catches
   the scalar comparison parameter, not just the collection parameter.
3. `DatabasePlatform.setParameterValueInDatabaseCall(...)`, lines 1890-1892,
   binds raw null through `getJDBCType((Class<?>) null)`, which returns VARCHAR.

The JDBC observations and SQL marker change agree with this source path.
Relationship-path type inference alone is not the trigger: removing the
collection predicate succeeds, and using the child's scalar ID with the
collection predicate still fails.

## Files and evidence

- `src/test/java/repro/NullVarcharTest.java`: four query shapes, each in two orders.
- `src/test/java/repro/Parent.java` and `Child.java`: the two entities.
- `src/test/java/repro/RecordingDataSource.java`: JDBC recording wrapper.
- `target/results.tsv`: generated on each run, with one row for each of the 16 query executions.
- `target/surefire-reports/repro.NullVarcharTest-output.txt`: SQL, JDBC calls,
  provider logs, and exception chains.
- `run.log`: Maven build and test summary.

Build output and logs are ignored by Git. The source analysis uses the published
EclipseLink 5.0.1 source artifact.
