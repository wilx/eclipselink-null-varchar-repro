# Null parameter type loss during IN collection expansion

EclipseLink correctly infers the null ID parameter's `Long` type. The type is
discarded later, when collection expansion replaces a typed `DatabaseField`
with raw Java `null` in the JDBC parameter list. The binding code then falls
back to `Types.VARCHAR`, and PostgreSQL rejects the comparison against `bigint`.

## Evidence and scope

The behavior was traced with read-only JDI breakpoints in the released
EclipseLink 5.0.1 binary, using this repository's unchanged two-entity test.
The debugger inspected variables and call stacks without invoking target
methods or modifying provider code. A delegating JDBC wrapper independently
recorded the actual JDBC setter calls against PostgreSQL 16.14 with PostgreSQL
JDBC 42.7.13.

The debugger run reproduced the same result: eight test cases, four assertion
failures caused by the bug, and no test errors. All eight non-null control
queries succeeded. Both null-first and non-null-first execution orders
reproduced the failure. Detailed debugger observations below concern the
null-first executions.

The failing relationship query is:

```jpql
SELECT c FROM Child c
WHERE c.parent.id = :id AND c.status IN :statuses
```

Here, `id` is null and `statuses` is the nonempty collection `[ACTIVE, INACTIVE]`,
mapped to database values `[0, 1]`.

Source links below are pinned to the revision embedded in the tested 5.0.1
release. The five linked Java files were checked against the released source
artifact and match byte for byte.

## 1. Query argument metadata contains the correct type

[`DatabaseQuery.buildArgumentFields()`][argument-fields] creates a field for each
argument and assigns its inferred Java type. The debugger observed:

```text
argumentTypes = [java.lang.Long.class, java.util.Collection.class]
argumentField = DatabaseField{name="id", type=java.lang.Long.class}
translationRow[id] = null
```

The field's `sqlType` is `DatabaseField.NULL_SQL_TYPE` (`-2147483648`), indicating
that no explicit JDBC type was assigned. Its Java type is sufficient to obtain
`Types.BIGINT` later.

[`ParameterExpression.getValue()`][parameter-value] returns null for the ID.
There are two distinct pieces of metadata at this point:

- The expression has `ParameterExpression.type = Long.class`, while its own
  `DatabaseField.type` is unset.
- The translation row holds a separate `DatabaseField` with `type = Long.class`.

The unset type on the expression's field does not itself cause this failure:
the next step explicitly obtains the typed field from the translation row.

## 2. Normal parameter translation preserves the typed null

[`DatabaseCall.translate()`][typed-null] detects the null value, looks up
`fieldFromRow`, and calls `chooseMostSpecificFieldForNullBinding()`.
The debugger observed that the selected `translatedValue` is the same typed
field object held by the translation row:

```text
fieldFromRow    = DatabaseField{name="id", type=java.lang.Long.class}
translatedValue = DatabaseField{name="id", type=java.lang.Long.class}
```

This field acts as a typed-null marker in the bind-value list. It is not a
database value to be transmitted directly.

Translating the status collection produces `[0, 1]` and sets
`hasParameterizedIN = true`. Immediately before the
[collection-expansion call][expand-call], the parameter list is:

```text
[DatabaseField{id, Long}, [0, 1]]
```

## 3. Collection expansion discards the scalar parameter's type

`DatasourceCall.translateQueryStringForParameterizedIN()` traverses every
placeholder in the SQL, including the ID equality predicate. The
[problematic branch][loss-branch] is:

```java
//handle null passed into ...IN (?) as a parameter
} else if (parameter instanceof DatabaseField && translationRow.get(parameter) == null){
    parametersValues.add(null);
    writer.write("(?)");
}
```

The condition does not establish that the current parameter belongs to an
`IN` expression or has a collection type. The scalar ID's typed-null marker is
a `DatabaseField`, and its value in the translation row is null, so it also
matches this branch.

The debugger stopped on `parametersValues.add(null)` with:

```text
parameterIndex = 0
parameter = DatabaseField{name="id", type=java.lang.Long.class}
```

The resulting transition was:

```text
Before expansion: [DatabaseField{id, Long}, [0, 1]]
After expansion:  [null, 0, 1]
```

`setParameters(parametersValues)` installs this new list on the call. The
translation row still contains the original `Long` metadata afterward; only
the list used for binding has lost it.

The same branch changes the scalar placeholder from `?` to `(?)`, giving this
observable SQL change:

```text
Before: WHERE ((PARENT_ID = ?) AND (STATUS IN ?))
After:  WHERE ((PARENT_ID = (?)) AND (STATUS IN (?,?)))
```

## 4. JDBC binding reaches the VARCHAR fallback

When execution proceeds, [`DatabaseCall.prepareStatement()`][bind-loop] iterates
over the rebuilt list. For the first JDBC parameter it passes raw null to the
platform. The debugger observed the following call path:

```text
DatabaseCall.prepareStatement()
  -> PostgreSQLPlatform.setParameterValueInDatabaseCall()
  -> DatabasePlatform.setParameterValueInDatabaseCall()
       parameter == null
       -> PostgreSQLPlatform.getJDBCType(null)
       -> DatabasePlatform.getJDBCType(null)
            returns Types.VARCHAR
       -> statement.setNull(1, Types.VARCHAR)
```

The [raw-null binding branch][raw-null] explicitly calls
`getJDBCType((Class<?>) null)`. The [null-type fallback][varchar-fallback]
returns `Types.VARCHAR` (`12`). Both branches were reached in the debugger.
The independent JDBC recording confirmed:

```text
{1=setNull(VARCHAR,12), 2=setInt(0), 3=setInt(1)}
ERROR: operator does not exist: bigint = character varying
SQLState: 42883
```

## Controls and underlying cause

Without `IN :statuses`, `hasParameterizedIN` remains false and collection
expansion is skipped. The typed field reaches
`PostgreSQLPlatform.setNullFromDatabaseField()`, producing
`setNull(1, Types.BIGINT)` (`-5`), and the query correctly returns no rows.

Using the child's scalar `c.id` instead of `c.parent.id`, while retaining the
status collection, reproduces the same type loss and PostgreSQL error.
Relationship traversal is therefore not required for this failure.

The branch was introduced by
[commit `57eb0c4c797`][introducing-commit], titled
“Allow an empty collection or null as a parameter in an 'IN' expression.”
It treats any typed-null marker as though it represented a null collection
argument, including scalar nulls elsewhere in the query.

A repair needs to preserve typed scalar nulls during collection expansion
while retaining support for genuinely null collection arguments and empty
collections. Changing the global VARCHAR fallback would not correct the
earlier loss of metadata. No provider fix or validation of a proposed fix is
included in this investigation.

[argument-fields]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/queries/DatabaseQuery.java#L2044-L2055
[parameter-value]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/expressions/ParameterExpression.java#L226-L351
[typed-null]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabaseCall.java#L1161-L1171
[expand-call]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabaseCall.java#L1201-L1204
[loss-branch]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatasourceCall.java#L1430-L1443
[bind-loop]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabaseCall.java#L793-L797
[raw-null]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabasePlatform.java#L1890-L1894
[varchar-fallback]: https://github.com/eclipse-ee4j/eclipselink/blob/b81f6203960431f4ef8a54433b094b5acea71bdd/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabasePlatform.java#L790-L792
[introducing-commit]: https://github.com/eclipse-ee4j/eclipselink/commit/57eb0c4c7973e0499525e83661417d683fb3a4da
